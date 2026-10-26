package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.process.AudioFifo;
import com.github.auties00.smalt.audio.process.ChannelMixer;
import com.github.auties00.smalt.audio.process.ChannelMixerOptions;
import com.github.auties00.smalt.audio.process.Resampler;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Rational;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Turns the packets of one audio track into exactly the samples a player outputs, in a chosen
 * sampling rate and channel layout.
 *
 * <p>The caller demuxes and feeds the track's packets in order with {@link #decode(Packet)}, then
 * calls {@link #finish()} once the track ends, and drains the converted samples with
 * {@link #read(float[][], int, int)}. The decoder drops the leading samples the container marks
 * as encoder priming ({@link AudioTrack#skipSamples()}), drops the end padding a container marks
 * by shortening the declared duration of the track's last packet, stops after the track's
 * declared duration, mixes to the requested layout and resamples to the requested rate. A packet
 * flagged {@link PacketFlag#DISCARD} is decoded and its output dropped; the
 * {@link com.github.auties00.smalt.track.PacketSideData#discardStart() discard start} of a packet
 * drops the start of its output, and a packet with a
 * {@link com.github.auties00.smalt.track.PacketSideData#discardEnd() discard end} keeps no more
 * output than its duration. A packet whose side data carries a
 * {@link com.github.auties00.smalt.track.PacketSideData#newCodecConfig() new codec configuration}
 * that differs from the one in use drains the codec and continues with a codec created from the
 * new configuration; a change of sampling rate or channel layout is converted to the output's,
 * which stays the one chosen at creation. Packets
 * are decoded one packet late, so the last one is known when it is decoded: the durations of
 * earlier packets can be rounded by a coarse container time base and never cut decoded audio. A
 * packet the codec rejects as corrupt is skipped, as players do; a run of
 * {@value #MAX_CONSECUTIVE_FAILURES} corrupt packets in a row fails.
 *
 * <p>The output is 32-bit floats unless {@link AudioStreamDecoderOptions#sampleFormat()} asks for
 * another {@link SampleFormat}: {@link SampleFormat#INTEGER} outputs the codec's own integer
 * samples bit for bit ({@link #readIntegers(int[][], int, int)}), and {@link SampleFormat#DOUBLE}
 * 64-bit floats ({@link #readDoubles(double[][], int, int)}). Both keep the codec's rate and layout
 * and output its samples unchanged; the trimming is the same in every format. A part of the stream
 * that changes the rate or layout mid-way is converted as in float output, then quantized to the
 * output's sample size (rounded to nearest, ties toward positive infinity, and clamped) or widened to
 * doubles. Integer output keeps the sample size {@link #bitsPerSample()} reports: blocks of a
 * narrower size are shifted left to it, which keeps their values, and blocks of a wider size are
 * rounded to it as converted parts are.
 *
 * <p>The decoder also learns how the track's timestamps map to its output: once two packets
 * produced samples, {@link #origin()} is the difference between a packet's timestamp and the
 * index of its first output sample, which is constant for a container and codec whose timestamps
 * count samples exactly (it is zero for most, and the Opus pre-skip where a container stamps the
 * first Opus packet at zero), and varies when the container rounds them (Matroska stores
 * milliseconds, which do not fall on sample boundaries for most packet durations). A reader
 * that seeks uses it to restart sample-accurately: {@link #measure(Demuxer, AudioTrack, AudioStreamDecoderOptions)}
 * records it, with the track's first timestamp, in a {@link Timing}, which tells where to seek the
 * demuxer and which {@link #startAt(long, Timing)} uses to align the output.
 *
 * <p>An instance is not thread-safe.
 */
public final class AudioStreamDecoder implements AutoCloseable {
    /**
     * The number of consecutive corrupt packets after which decoding fails.
     */
    private static final int MAX_CONSECUTIVE_FAILURES = 16;

    /**
     * The track being decoded.
     */
    private final AudioTrack track;

    /**
     * The codec decoder of {@link #track}, replaced when a packet changes the codec configuration.
     */
    private AudioDecoder decoder;

    /**
     * The options the codec decoders are created with.
     */
    private final AudioDecoderOptions decoderOptions;

    /**
     * The codec configuration of {@link #decoder}, or {@code null} when it has none.
     */
    private byte[] config;

    /**
     * Whether the packet being decoded is decoded only so that later packets decode, its output
     * dropped.
     */
    private boolean packetDiscarded;

    /**
     * The number of decoded samples to drop from the start of the packet being decoded.
     */
    private long packetSkip;

    /**
     * The sampling rate of the output.
     */
    private final int outputRate;

    /**
     * The channel layout of the output.
     */
    private final ChannelLayout outputLayout;

    /**
     * The converted samples not yet read, or {@code null} unless the output is float.
     */
    private final AudioFifo fifo;

    /**
     * The representation of the output samples.
     */
    private final SampleFormat format;

    /**
     * The integer or double samples not yet read, or {@code null} when the output is float.
     */
    private final SampleFifo exact;

    /**
     * The sample size of the integer output, 0 until known or when the output is not integer.
     */
    private int outputBits;

    /**
     * Scratch planes holding integer samples converted to {@link #outputBits}.
     */
    private int[][] rescaled;

    /**
     * Scratch block holding float samples widened to doubles, for a codec without double output.
     */
    private DoubleAudioBuffer widened;

    /**
     * Scratch block holding an exact block scaled to floats, for a part that changes the rate or
     * layout.
     */
    private AudioBuffer narrowed;

    /**
     * Scratch planes holding converted float samples quantized or widened for {@link #exact}.
     */
    private Object[] quantized;

    /**
     * The consumer of the codec's float blocks.
     */
    private final Consumer<AudioBuffer> floatSink = this::accept;

    /**
     * The consumer of the codec's integer blocks.
     */
    private final Consumer<IntegerAudioBuffer> integerSink = this::acceptIntegers;

    /**
     * The consumer of the codec's double blocks.
     */
    private final Consumer<DoubleAudioBuffer> doubleSink = this::acceptDoubles;

    /**
     * The consumer widening the codec's float blocks to doubles.
     */
    private final Consumer<AudioBuffer> wideningSink = this::widen;

    /**
     * The number of decoded samples still to drop as encoder priming.
     */
    private long skipRemaining;

    /**
     * The number of decoded samples still to keep before the declared duration ends.
     */
    private long keepRemaining;

    /**
     * The number of decoded samples the packet being decoded may produce: the declared duration
     * of the track's last packet, or {@link Long#MAX_VALUE} for any other packet and for a last
     * packet that declares none.
     */
    private long packetLimit;

    /**
     * The number of decoded samples the packet being decoded produced so far.
     */
    private long packetProduced;

    /**
     * The mixer converting decoded blocks to {@link #outputLayout}, or {@code null} when none is
     * needed.
     */
    private ChannelMixer mixer;

    /**
     * The settings every {@link #mixer} is created with.
     */
    private final ChannelMixerOptions mixing;

    /**
     * Whether {@link #mixing} holds a caller's matrix, which applies even between equal layouts.
     */
    private final boolean customMatrix;

    /**
     * The layout {@link #mixer} converts from.
     */
    private ChannelLayout mixerInput;

    /**
     * The resampler converting mixed blocks to {@link #outputRate}, or {@code null} when none is
     * needed.
     */
    private Resampler resampler;

    /**
     * The rate {@link #resampler} converts from.
     */
    private int resamplerInput;

    /**
     * Scratch planes holding a mixed block.
     */
    private float[][] mixed;

    /**
     * Scratch planes holding a resampled block.
     */
    private float[][] resampled;

    /**
     * The number of consecutive packets the codec rejected.
     */
    private int consecutiveFailures;

    /**
     * The packet received but not decoded yet, or {@code null}.
     */
    private Packet pending;

    /**
     * The index, in a decode from the start of the track, assumed for the first sample produced
     * after a seek when no second producing packet aligns it.
     */
    private long heldStart;

    /**
     * Whether {@link #finish()} ran.
     */
    private boolean finished;

    /**
     * The presentation time in microseconds the output starts at after a seek, applied when the
     * first packet arrives, or {@code -1} when no seek is pending.
     */
    private long startMicros = -1;

    /**
     * The timestamp of the track's first packet, in the track's time base, or
     * {@link Packet#NO_TIMESTAMP} when unknown.
     */
    private long streamStart = Packet.NO_TIMESTAMP;

    /**
     * The difference between a packet's timestamp, in samples at the codec rate, and the index of
     * its first output sample, or {@link #UNKNOWN}.
     */
    private long origin = UNKNOWN;

    /**
     * Whether {@link #startAt(long, Timing)} ran, so that the decoder does not decode from the
     * start of the track and learns no {@link #origin}.
     */
    private boolean restarted;

    /**
     * Whether every producing packet after the first of a decode from the start agreed with
     * {@link #origin}, so that the track's timestamps place packets to the sample.
     */
    private boolean timestampsExact = true;

    /**
     * The number of samples the codec produced so far, before any trimming.
     */
    private long produced;

    /**
     * The number of packets whose decoding produced samples.
     */
    private int producingPackets;

    /**
     * The packet being decoded, or {@code null} while the codec is flushed.
     */
    private Packet current;

    /**
     * Whether {@link #current} produced samples yet.
     */
    private boolean currentProduced;

    /**
     * Whether the output after a seek waits for the second producing packet to align it.
     */
    private boolean aligning;

    /**
     * The seek target plus the encoder priming, in samples at the codec rate, while aligning.
     */
    private long alignTarget;

    /**
     * The index, at the codec rate in a decode from the start, of the first decoded sample kept
     * after a seek: the target itself, or an earlier sample whose resampling phase matches that of
     * a decode from the start when the output rate differs.
     */
    private long restartIndex;

    /**
     * The output sample a seek starts at, in a decode from the start at the output rate.
     */
    private long outputTarget;

    /**
     * Whether the output after a seek is resampled, so that the restart point keeps the resampling
     * phase of a decode from the start.
     */
    private boolean resampledRestart;

    /**
     * The number of converted samples still to drop before queueing, after a seek that restarts
     * the resampler before the target.
     */
    private long outputSkip;

    /**
     * The samples produced while aligning, copied, in production order.
     */
    private final ArrayDeque<Object> held = new ArrayDeque<>();

    /**
     * The value of {@link #origin} and {@link #streamStart} when unknown.
     */
    static final long UNKNOWN = Long.MIN_VALUE;

    /**
     * The seek target that moves a demuxer to the start of every track, far before any packet.
     */
    private static final long CALIBRATION_TIME = -1_000_000_000_000L;

    /**
     * The largest number of packets of the track {@link #measure(Demuxer, AudioTrack, AudioStreamDecoderOptions)}
     * decodes.
     */
    private static final int CALIBRATION_PACKETS = 64;

    /**
     * Constructs a decoder.
     *
     * @param track   the track to decode
     * @param decoder the codec decoder of {@code track}, owned by this instance
     * @param decoderOptions the options {@code decoder} was created with
     * @param options the output rate, layout and sample format
     * @throws IllegalArgumentException if an exact sample format is combined with a conversion
     * @throws MediaException.Unsupported if the output is integer and the codec has no integer
     * output
     */
    private AudioStreamDecoder(AudioTrack track, AudioDecoder decoder, AudioDecoderOptions decoderOptions,
                               AudioStreamDecoderOptions options) {
        this.track = track;
        this.decoder = decoder;
        this.decoderOptions = decoderOptions;
        this.config = track.codecConfig().orElse(null);
        this.outputRate = options.sampleRate().orElse(decoder.sampleRate());
        this.outputLayout = options.layout().orElse(decoder.layout());
        this.mixing = options.mixing();
        this.customMatrix = mixing.matrix().isPresent();
        this.format = options.sampleFormat();
        if (format != SampleFormat.FLOAT) {
            if (outputRate != decoder.sampleRate() || !outputLayout.equals(decoder.layout())) {
                throw new IllegalArgumentException(format + " output keeps the codec's " + decoder.sampleRate() + " Hz "
                        + decoder.layout() + ", not " + outputRate + " Hz " + outputLayout);
            }
            requireIntegers(decoder);
        }
        this.fifo = format == SampleFormat.FLOAT ? new AudioFifo(outputLayout.channels()) : null;
        this.exact = format == SampleFormat.FLOAT ? null
                : new SampleFifo(format == SampleFormat.INTEGER ? int.class : double.class, outputLayout.channels());
        this.outputBits = format == SampleFormat.INTEGER ? decoder.bitsPerSample() : 0;
        var decoderRate = decoder.sampleRate();
        var trackRate = track.sampleRate() > 0 ? track.sampleRate() : decoderRate;
        this.skipRemaining = Math.round((double) track.skipSamples() * decoderRate / trackRate);
        this.keepRemaining = track.duration() >= 0
                ? track.timeBase().rescale(track.duration(), Rational.timeBaseOf(decoderRate))
                : Long.MAX_VALUE;
        this.packetLimit = Long.MAX_VALUE;
    }

    /**
     * Rejects a codec decoder without integer output when the output is integer.
     *
     * @param codec the codec decoder
     * @throws MediaException.Unsupported if the output is integer and the codec has no integer
     * output
     */
    private void requireIntegers(AudioDecoder codec) {
        if (format == SampleFormat.INTEGER && !codec.supportsIntegerOutput()) {
            throw new MediaException.Unsupported(track.codec().toString(),
                    track.codec() + " has no integer samples to output");
        }
    }

    /**
     * Creates a decoder for a track that keeps the codec's rate and layout, under the default
     * limits.
     *
     * @param track the track to decode
     * @return the decoder, which the caller closes
     * @throws NullPointerException       if {@code track} is {@code null}
     * @throws MediaException.Unsupported if the codec is not decodable
     * @throws MediaException.Malformed   if the track's configuration is corrupt
     */
    public static AudioStreamDecoder create(AudioTrack track) {
        return create(track, AudioStreamDecoderOptions.defaults());
    }

    /**
     * Creates a decoder for a track.
     *
     * @param track the track to decode
     * @param options the output rate, layout and sample format, the integrity check and the limits
     * the codec enforces
     * @return the decoder, which the caller closes
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the sample format is not {@link SampleFormat#FLOAT} and
     * the options ask for a rate or layout other than the codec's
     * @throws MediaException.Unsupported if the codec is not decodable, or the sample format is
     * {@link SampleFormat#INTEGER} and the codec has no integer output
     * @throws MediaException.Malformed if the track's configuration is corrupt
     */
    public static AudioStreamDecoder create(AudioTrack track, AudioStreamDecoderOptions options) {
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        var decoderOptions = AudioDecoderOptions.builder().limits(options.limits())
                .verifyIntegrity(options.verifyIntegrity());
        var rate = options.sampleRate();
        if (rate.isPresent()) {
            decoderOptions.preferredSampleRate(rate.getAsInt());
        }
        var built = decoderOptions.build();
        var decoder = AudioDecoders.create(track, built);
        try {
            return new AudioStreamDecoder(track, decoder, built, options);
        } catch (RuntimeException exception) {
            decoder.close();
            throw exception;
        }
    }

    /**
     * Measures how the timestamps of a track map to the output of a decode from its start, so that
     * a decoder can later restart at any time of the track sample-accurately.
     *
     * <p>The demuxer is moved to the start of the track and the first
     * {@value #CALIBRATION_PACKETS} packets of the track are decoded with a decoder configured by
     * {@code options}. The timing records the timestamp of the track's first packet, the
     * {@link #origin()} of that decode, and whether every packet after the first producing one
     * agrees with that origin, which tells whether the timestamps place packets to the sample
     * ({@link Timing#isExact()}). The demuxer is left at an unspecified position: the caller seeks
     * it afterwards, to {@link Timing#seekMicros(long)}.
     *
     * @apiNote Use this when driving a {@link Demuxer} directly, for instance to decode several
     *          tracks of one file after a seek; {@link AudioReader#seek(long)} measures and
     *          restarts by itself. Measure once per track and reuse the timing for every later
     *          seek; pass the same {@code options} as the decoders that will use it, so that the
     *          codec runs at the same rate.
     *
     * @param demuxer the demuxer reading the track
     * @param track   the track, one of {@code demuxer}'s tracks
     * @param options the output rate, layout and limits of the decoders that will use the timing
     * @return the timing
     * @throws NullPointerException       if an argument is {@code null}
     * @throws IllegalArgumentException   if the demuxer has no track with the track's identifier
     * @throws MediaException.Unsupported if the demuxer cannot seek or the codec is not decodable
     * @throws MediaException.Malformed   if the container or too many consecutive packets are
     *                                    corrupt
     * @throws MediaException.Io          if reading the source fails
     */
    public static Timing measure(Demuxer demuxer, AudioTrack track, AudioStreamDecoderOptions options) {
        Objects.requireNonNull(demuxer, "demuxer");
        Objects.requireNonNull(track, "track");
        Objects.requireNonNull(options, "options");
        if (demuxer.tracks().stream().noneMatch(candidate -> candidate.id() == track.id())) {
            throw new IllegalArgumentException("the demuxer has no track " + track.id());
        }
        demuxer.seek(CALIBRATION_TIME);
        var streamStart = UNKNOWN;
        try (var probe = create(track, options)) {
            for (var count = 0; count < CALIBRATION_PACKETS; ) {
                var packet = demuxer.read();
                if (packet == null) {
                    break;
                }
                if (packet.trackId() != track.id()) {
                    continue;
                }
                if (count++ == 0) {
                    var timestamp = timestamp(packet);
                    streamStart = timestamp == Packet.NO_TIMESTAMP ? UNKNOWN : timestamp;
                }
                probe.decode(packet);
            }
            var origin = probe.origin();
            return new Timing(track, streamStart, origin, probe.codecRate(),
                    streamStart != UNKNOWN && origin != UNKNOWN && probe.timestampsExact);
        }
    }

    /**
     * Makes the output start at a presentation time, for a decoder that will receive packets
     * starting at a key frame at or before that time.
     *
     * <p>The output is exactly the part of a decode from the start of the track that presents at
     * or after {@code micros}, provided the packets received reach back at least
     * {@link Timing#prerollMicros()} before it, as they do after
     * {@link Demuxer#seek(long) seeking} the demuxer to {@link Timing#seekMicros(long)}. When the
     * first packet is the track's first, the decoder behaves as at the start of the track and
     * drops the samples before {@code micros} on top of the encoder priming. Otherwise it holds
     * the samples of the first packet that produces any and aligns them with the second, whose
     * timestamp and the timing's origin give the index its first sample has in a decode from the
     * start; the codec's own priming after a reset (the Opus pre-skip, the silent first Vorbis
     * packet) and the timestamp convention of the container (Ogg, MP4 and Matroska stamp the
     * first Opus packet differently) are then accounted for by construction. The declared
     * duration is shortened by {@code micros}. The alignment is as precise as the timestamps, so
     * it is exact only when {@link Timing#isExact()} holds; otherwise
     * {@link Timing#seekMicros(long)} restarts at the track's first packet, where the samples
     * before {@code micros} are counted rather than placed by timestamps. When the timing's origin
     * is unknown, or a packet lacks timestamps, the first packet is assumed to start where its
     * timestamp says. When the output rate differs from the codec's, the resampler restarts at
     * the latest sample before {@code micros} (by at least its filter length) whose resampling
     * phase is that of a decode from the start, and the converted samples before {@code micros}
     * are dropped, so the converted output matches too.
     *
     * @param micros the presentation time in microseconds; a negative time means zero
     * @param timing the timing {@link #measure(Demuxer, AudioTrack, AudioStreamDecoderOptions) measured}
     *               for this decoder's track
     * @throws NullPointerException     if {@code timing} is {@code null}
     * @throws IllegalArgumentException if {@code timing} was measured for another track
     * @throws IllegalStateException    if a packet was already received
     */
    public void startAt(long micros, Timing timing) {
        Objects.requireNonNull(timing, "timing");
        if (timing.trackId != track.id()) {
            throw new IllegalArgumentException("timing of track " + timing.trackId + ", expected " + track.id());
        }
        if (pending != null || finished || consecutiveFailures != 0) {
            throw new IllegalStateException("the stream already started");
        }
        this.startMicros = Math.max(0, micros);
        this.restarted = true;
        this.streamStart = timing.streamStart == UNKNOWN ? Packet.NO_TIMESTAMP : timing.streamStart;
        var rate = decoder.sampleRate();
        this.origin = timing.origin == UNKNOWN || timing.originRate == rate ? timing.origin
                : Math.round((double) timing.origin * rate / timing.originRate);
    }

    /**
     * Returns the difference between a packet's timestamp, in samples at the codec rate, and the
     * index of its first output sample in a decode from the start of the track.
     *
     * @return the difference, or {@link #UNKNOWN} until two packets produced samples
     */
    long origin() {
        return origin;
    }

    /**
     * Returns the codec's sampling rate, in which {@link #origin()} counts.
     *
     * @return the rate in hertz
     */
    int codecRate() {
        return decoder.sampleRate();
    }

    /**
     * Returns the track being decoded.
     *
     * @return the track
     */
    public AudioTrack track() {
        return track;
    }

    /**
     * Returns the sampling rate of the output.
     *
     * @return the rate in hertz
     */
    public int sampleRate() {
        return outputRate;
    }

    /**
     * Returns the channel layout of the output.
     *
     * @return the layout
     */
    public ChannelLayout layout() {
        return outputLayout;
    }

    /**
     * {@return the representation of the output samples}
     */
    public SampleFormat sampleFormat() {
        return format;
    }

    /**
     * Returns the sample size of the samples {@link #readIntegers(int[][], int, int)} moves.
     *
     * @return the size in bits, from 1 to 32, or 0 when the output is not
     * {@link SampleFormat#INTEGER}, or before the first sample when the stream declares no size
     */
    public int bitsPerSample() {
        return outputBits;
    }

    /**
     * Returns the number of converted samples ready to be read.
     *
     * @return the number of samples per channel
     */
    public int available() {
        return fifo != null ? fifo.size() : exact.size();
    }

    /**
     * Returns whether {@link #finish()} ran and every sample was read.
     *
     * @return {@code true} at the end of the output
     */
    public boolean isDrained() {
        return finished && available() == 0;
    }

    /**
     * Receives the next packet of the track and decodes the packet received before it.
     *
     * @param packet the next packet, in decoding order
     * @throws NullPointerException     if {@code packet} is {@code null}
     * @throws IllegalArgumentException if the packet belongs to another track
     * @throws IllegalStateException    if {@link #finish()} ran
     * @throws MediaException.Malformed if {@value #MAX_CONSECUTIVE_FAILURES} consecutive packets are
     *                                  corrupt
     */
    public void decode(Packet packet) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("the stream is finished");
        }
        if (packet.trackId() != track.id()) {
            throw new IllegalArgumentException("packet of track " + packet.trackId() + ", expected " + track.id());
        }
        if (startMicros >= 0) {
            applyStart(packet);
        }
        if (pending != null) {
            decodeHeld(false);
        }
        pending = packet;
    }

    /**
     * Converts the pending start time into the samples to drop before the first output sample,
     * given the first packet received after a seek.
     *
     * @param packet the first packet
     */
    private void applyStart(Packet packet) {
        var codecRate = decoder.sampleRate();
        var rate = Rational.timeBaseOf(codecRate);
        var target = Rational.MICROSECONDS.rescale(startMicros, rate);
        restartIndex = target;
        if (codecRate != outputRate) {
            outputTarget = Rational.MICROSECONDS.rescale(startMicros, Rational.timeBaseOf(outputRate));
            resampler = new Resampler(codecRate, outputRate, outputLayout.channels(), Resampler.Quality.STANDARD);
            resamplerInput = codecRate;
            resampledRestart = true;
            var period = resamplingPeriod();
            var lookBehind = Math.ceilDiv((long) resampler.delay() * period, outputPeriod()) + 1;
            var earliest = Math.floorDiv(outputTarget * period, outputPeriod()) - lookBehind;
            restartIndex = earliest <= 0 ? 0 : Math.floorDiv(earliest, period) * period;
            outputSkip = outputTarget - restartIndex / period * outputPeriod();
        }
        var timestamp = timestamp(packet);
        var priming = skipRemaining;
        if (timestamp != Packet.NO_TIMESTAMP && timestamp == streamStart) {
            skipRemaining = restartIndex + priming;
        } else if (timestamp != Packet.NO_TIMESTAMP && origin != UNKNOWN) {
            aligning = true;
            alignTarget = restartIndex + priming;
            skipRemaining = 0;
        } else {
            var start = timestamp == Packet.NO_TIMESTAMP ? restartIndex + priming : track.timeBase().rescale(timestamp, rate);
            skipRemaining = Math.max(0, restartIndex + priming - start);
        }
        if (keepRemaining != Long.MAX_VALUE) {
            keepRemaining = Math.max(0, keepRemaining - restartIndex);
        }
        startMicros = -1;
    }

    /**
     * Returns the number of codec-rate samples after which the resampling phase repeats: the
     * codec rate divided by its greatest common divisor with the output rate.
     *
     * @return the period in samples at the codec rate
     */
    private long resamplingPeriod() {
        var rate = decoder.sampleRate();
        return rate / gcd(rate, outputRate);
    }

    /**
     * Returns the number of output samples one {@link #resamplingPeriod()} of codec samples
     * converts to.
     *
     * @return the period in samples at the output rate
     */
    private long outputPeriod() {
        return outputRate / gcd(decoder.sampleRate(), outputRate);
    }

    /**
     * Returns the greatest common divisor of two positive integers.
     *
     * @param a the first integer
     * @param b the second integer
     * @return the divisor
     */
    private static int gcd(int a, int b) {
        while (b != 0) {
            var next = a % b;
            a = b;
            b = next;
        }
        return a;
    }

    /**
     * Returns the timestamp that places a packet: its presentation timestamp, or its decoding
     * timestamp when it has none.
     *
     * @param packet the packet to place
     * @return the timestamp, or {@link Packet#NO_TIMESTAMP}
     */
    private static long timestamp(Packet packet) {
        return packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
    }

    /**
     * Records that the packet being decoded produced its first samples, learning
     * {@link #origin} from the second producing packet of a decode from the start (and checking
     * it against every later one) and aligning the held samples after a seek.
     */
    private void packetProduced() {
        currentProduced = true;
        producingPackets++;
        var timestamp = timestamp(current);
        if (timestamp == Packet.NO_TIMESTAMP) {
            return;
        }
        var index = track.timeBase().rescale(timestamp, Rational.timeBaseOf(decoder.sampleRate()));
        if (!restarted && producingPackets >= 2) {
            if (origin == UNKNOWN) {
                origin = index - produced;
            } else if (index - produced != origin) {
                timestampsExact = false;
            }
        }
        if (aligning && producingPackets >= 2) {
            align(index - origin - produced);
        }
    }

    /**
     * Ends the alignment after a seek: drops the samples before the target and releases the held
     * samples.
     *
     * @param shift the index, in a decode from the start of the track, of the first sample
     *              produced after the seek
     */
    private void align(long shift) {
        aligning = false;
        if (resampledRestart) {
            var priming = alignTarget - restartIndex;
            if (shift > alignTarget) {
                // the pre-roll did not reach the restart point: restart at the next sample in phase
                var period = resamplingPeriod();
                var later = Math.ceilDiv(Math.max(0, shift - priming), period) * period;
                if (keepRemaining != Long.MAX_VALUE) {
                    keepRemaining = Math.max(0, keepRemaining - (later - restartIndex));
                }
                restartIndex = later;
                alignTarget = later + priming;
                outputSkip = Math.max(0, outputTarget - later / period * outputPeriod());
            }
        }
        skipRemaining = Math.max(0, alignTarget - shift);
        while (!held.isEmpty()) {
            trim(held.poll(), false);
        }
    }

    /**
     * Decodes the last packet, trimmed to its declared duration, and drains the codec and the
     * resampler; later calls do nothing.
     *
     * @throws MediaException.Malformed if {@value #MAX_CONSECUTIVE_FAILURES} consecutive packets are
     * corrupt, or the stream fails the integrity check
     * {@link AudioStreamDecoderOptions#verifyIntegrity()} requested
     */
    public void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (pending != null) {
            decodeHeld(true);
        }
        packetLimit = Long.MAX_VALUE;
        packetDiscarded = false;
        packetSkip = 0;
        current = null;
        flushCodec();
        if (aligning) {
            align(heldStart);
        }
        flushResampler();
    }

    /**
     * Moves up to {@code frames} converted samples into {@code planes}.
     *
     * @param planes the destination planes, one per output channel
     * @param offset the index of the first sample written in every plane
     * @param frames the largest number of samples per channel to move
     * @return the number of samples per channel moved
     * @throws IllegalStateException if the output is not {@link SampleFormat#FLOAT}
     */
    public int read(float[][] planes, int offset, int frames) {
        if (fifo == null) {
            throw new IllegalStateException("the output is " + format + ", not FLOAT");
        }
        return fifo.read(planes, offset, Math.min(frames, fifo.size()));
    }

    /**
     * Moves up to {@code frames} integer samples, of {@link #bitsPerSample()} bits, into
     * {@code planes}.
     *
     * @param planes the destination planes, one per output channel
     * @param offset the index of the first sample written in every plane
     * @param frames the largest number of samples per channel to move, non-negative
     * @return the number of samples per channel moved
     * @throws NullPointerException if a plane is {@code null}
     * @throws IllegalArgumentException if the plane count differs from the layout's channel count
     * or {@code frames} is negative
     * @throws IndexOutOfBoundsException if a plane cannot hold the samples moved
     * @throws IllegalStateException if the output is not {@link SampleFormat#INTEGER}
     */
    public int readIntegers(int[][] planes, int offset, int frames) {
        if (format != SampleFormat.INTEGER) {
            throw new IllegalStateException("the output is " + format + ", not INTEGER");
        }
        Objects.requireNonNull(planes, "planes");
        var count = checkRead(planes.length, frames);
        for (var plane : planes) {
            Objects.checkFromIndexSize(offset, count, Objects.requireNonNull(plane, "plane").length);
        }
        return exact.read(planes, offset, count);
    }

    /**
     * Moves up to {@code frames} double samples into {@code planes}.
     *
     * @param planes the destination planes, one per output channel
     * @param offset the index of the first sample written in every plane
     * @param frames the largest number of samples per channel to move, non-negative
     * @return the number of samples per channel moved
     * @throws NullPointerException if a plane is {@code null}
     * @throws IllegalArgumentException if the plane count differs from the layout's channel count
     * or {@code frames} is negative
     * @throws IndexOutOfBoundsException if a plane cannot hold the samples moved
     * @throws IllegalStateException if the output is not {@link SampleFormat#DOUBLE}
     */
    public int readDoubles(double[][] planes, int offset, int frames) {
        if (format != SampleFormat.DOUBLE) {
            throw new IllegalStateException("the output is " + format + ", not DOUBLE");
        }
        Objects.requireNonNull(planes, "planes");
        var count = checkRead(planes.length, frames);
        for (var plane : planes) {
            Objects.checkFromIndexSize(offset, count, Objects.requireNonNull(plane, "plane").length);
        }
        return exact.read(planes, offset, count);
    }

    /**
     * Checks the arguments of an exact read.
     *
     * @param planes the number of planes passed
     * @param frames the largest number of samples asked for
     * @return the number of samples the read moves
     * @throws IllegalArgumentException if the plane count or the frame count is invalid
     */
    private int checkRead(int planes, int frames) {
        if (planes != outputLayout.channels()) {
            throw new IllegalArgumentException("expected " + outputLayout.channels() + " planes, got " + planes);
        }
        if (frames < 0) {
            throw new IllegalArgumentException("negative frame count " + frames);
        }
        return Math.min(frames, exact.size());
    }

    /**
     * Decodes a packet in the codec's output form for {@link #format}.
     *
     * @param packet the next packet of the track
     */
    private void decodeCodec(Packet packet) {
        switch (format) {
            case FLOAT -> decoder.decode(packet, floatSink);
            case INTEGER -> decoder.decodeIntegers(packet, integerSink);
            case DOUBLE -> {
                if (decoder.supportsDoubleOutput()) {
                    decoder.decodeDoubles(packet, doubleSink);
                } else {
                    decoder.decode(packet, wideningSink);
                }
            }
        }
    }

    /**
     * Drains the codec in its output form for {@link #format}.
     */
    private void flushCodec() {
        switch (format) {
            case FLOAT -> decoder.flush(floatSink);
            case INTEGER -> decoder.flushIntegers(integerSink);
            case DOUBLE -> {
                if (decoder.supportsDoubleOutput()) {
                    decoder.flushDoubles(doubleSink);
                } else {
                    decoder.flush(wideningSink);
                }
            }
        }
    }

    /**
     * Decodes {@link #pending}.
     *
     * @param last whether it is the track's last packet, whose declared duration trims it
     */
    private void decodeHeld(boolean last) {
        var packet = pending;
        pending = null;
        var newConfig = packet.sideData().newCodecConfig().orElse(null);
        if (newConfig != null && !Arrays.equals(newConfig, config)) {
            reconfigure(newConfig);
        }
        // TODO: fill a gap between the end of the previous packet and this one's timestamp (an MP4
        // empty or dwell edit after the first media edit) with silence instead of joining the samples
        var sideData = packet.sideData();
        var codecRate = Rational.timeBaseOf(decoder.sampleRate());
        packetLimit = (last && packet.duration() > 0) || (sideData.discardEnd() > 0 && packet.duration() >= 0)
                ? track.timeBase().rescale(packet.duration(), codecRate)
                : Long.MAX_VALUE;
        packetDiscarded = packet.flags().contains(PacketFlag.DISCARD);
        packetSkip = sideData.discardStart() > 0 ? track.timeBase().rescale(sideData.discardStart(), codecRate) : 0;
        packetProduced = 0;
        current = packet;
        currentProduced = false;
        try {
            decodeCodec(packet);
            consecutiveFailures = 0;
        } catch (MediaException.Malformed exception) {
            if (++consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                throw new MediaException.Malformed(consecutiveFailures + " consecutive corrupt audio packets", exception);
            }
        }
    }

    /**
     * Drains the codec and replaces it with one created from a new codec configuration, keeping
     * the remaining priming and duration counts in step with a change of sampling rate.
     *
     * @param newConfig the configuration of the packets from the one being decoded on
     * @throws MediaException.Unsupported if the codec cannot be created for the configuration, or
     * has no integer output when the output is integer
     * @throws MediaException.Malformed if the configuration is corrupt
     */
    private void reconfigure(byte[] newConfig) {
        packetLimit = Long.MAX_VALUE;
        packetDiscarded = false;
        packetSkip = 0;
        current = null;
        flushCodec();
        var builder = track.toBuilder().codecConfig(newConfig);
        if (track.codec() == Codec.AAC) {
            try {
                var aac = AudioConfigs.parseAac(newConfig);
                builder.sampleRate(aac.outputRate());
                if (aac.layout() != null) {
                    builder.layout(aac.layout());
                } else if (aac.channels() > 0) {
                    builder.layout(ChannelLayout.defaultFor(aac.channels()));
                }
            } catch (MediaException.Malformed ignored) {
                // the codec rejects the configuration itself
            }
        }
        var replacement = AudioDecoders.create(builder.build(), decoderOptions);
        try {
            requireIntegers(replacement);
        } catch (RuntimeException exception) {
            replacement.close();
            throw exception;
        }
        var oldRate = decoder.sampleRate();
        decoder.close();
        decoder = replacement;
        config = newConfig;
        var newRate = decoder.sampleRate();
        if (newRate != oldRate) {
            timestampsExact = false;
            skipRemaining = Math.round((double) skipRemaining * newRate / oldRate);
            if (keepRemaining != Long.MAX_VALUE) {
                keepRemaining = Math.round((double) keepRemaining * newRate / oldRate);
            }
        }
    }

    /**
     * Receives one decoded block from the codec: notes the first samples of each packet, for
     * {@link #origin()} and the alignment after a seek, then trims, mixes and resamples it.
     *
     * <p>Trimming applies, in order, the declared duration of the track's last packet, the
     * stream's leading priming, and the stream's declared duration.
     *
     * @param buffer the decoded block, valid only during the call
     */
    private void accept(AudioBuffer buffer) {
        produce(buffer.frames());
        trim(buffer, true);
    }

    /**
     * Receives one decoded integer block from the codec, as {@link #accept(AudioBuffer)} receives a
     * float block.
     *
     * @param buffer the decoded block, valid only during the call
     */
    private void acceptIntegers(IntegerAudioBuffer buffer) {
        produce(buffer.frames());
        trim(buffer, true);
    }

    /**
     * Receives one decoded double block from the codec, as {@link #accept(AudioBuffer)} receives a
     * float block.
     *
     * @param buffer the decoded block, valid only during the call
     */
    private void acceptDoubles(DoubleAudioBuffer buffer) {
        produce(buffer.frames());
        trim(buffer, true);
    }

    /**
     * Receives one decoded float block from a codec without double output, widened to doubles.
     *
     * @param buffer the decoded block, valid only during the call
     */
    private void widen(AudioBuffer buffer) {
        var frames = buffer.frames();
        if (widened == null || widened.capacity() < frames || widened.sampleRate() != buffer.sampleRate()
                || !widened.layout().equals(buffer.layout())) {
            widened = new DoubleAudioBuffer(buffer.layout(), buffer.sampleRate(), Math.max(frames, 1));
        }
        for (var channel = 0; channel < buffer.channels(); channel++) {
            var source = buffer.plane(channel);
            var target = widened.plane(channel);
            for (var i = 0; i < frames; i++) {
                target[i] = source[i];
            }
        }
        widened.setFrames(frames);
        acceptDoubles(widened);
    }

    /**
     * Notes the first samples of each packet, for {@link #origin()} and the alignment after a seek,
     * and counts the samples the codec produced.
     *
     * @param frames the number of samples per channel of a decoded block
     */
    private void produce(int frames) {
        if (current != null && !currentProduced && frames > 0) {
            if (aligning && producingPackets == 0) {
                var timestamp = timestamp(current);
                heldStart = timestamp == Packet.NO_TIMESTAMP || origin == UNKNOWN ? alignTarget
                        : track.timeBase().rescale(timestamp, Rational.timeBaseOf(decoder.sampleRate())) - origin;
            }
            packetProduced();
        }
        produced += frames;
    }

    /**
     * Trims, mixes and resamples one decoded block into the queue, or holds a copy of it while
     * the output after a seek is not aligned yet.
     *
     * <p>A block straight from the codec is first cut to the part of its packet's output that the
     * packet presents: none for a discarded packet, then after the packet's discard start and up
     * to the packet's declared duration where that limits it.
     *
     * @param buffer the decoded {@link AudioBuffer}, {@link IntegerAudioBuffer} or
     * {@link DoubleAudioBuffer}, valid only during the call unless held
     * @param decoded whether the block comes straight from the codec, so that its packet's
     * discards and declared duration apply to it
     */
    private void trim(Object buffer, boolean decoded) {
        var offset = 0;
        var frames = switch (buffer) {
            case AudioBuffer block -> block.frames();
            case IntegerAudioBuffer block -> block.frames();
            case DoubleAudioBuffer block -> block.frames();
            default -> throw new IllegalStateException("not a sample block: " + buffer);
        };
        if (decoded) {
            var before = packetProduced;
            packetProduced += frames;
            if (packetLimit != Long.MAX_VALUE) {
                frames = (int) Math.min(frames, Math.max(0, packetLimit - before));
            }
            if (packetDiscarded) {
                return;
            }
            if (packetSkip > before) {
                var skipped = (int) Math.min(frames, packetSkip - before);
                offset = skipped;
                frames -= skipped;
            }
            if (frames <= 0) {
                return;
            }
        }
        if (aligning) {
            held.add(slice(buffer, offset, frames));
            return;
        }
        if (skipRemaining > 0) {
            var skipped = (int) Math.min(skipRemaining, frames);
            skipRemaining -= skipped;
            offset += skipped;
            frames -= skipped;
        }
        if (frames > keepRemaining) {
            frames = (int) keepRemaining;
        }
        if (frames <= 0) {
            return;
        }
        keepRemaining -= frames;
        switch (buffer) {
            case AudioBuffer block -> resample(mix(block, offset, frames), block.sampleRate(), frames);
            case IntegerAudioBuffer block -> {
                if (converts(block.sampleRate(), block.layout())) {
                    convert(block.layout(), block.sampleRate(), block.planes(), block.bitsPerSample(), offset, frames);
                } else {
                    flushResampler();
                    queueIntegers(block, offset, frames);
                }
            }
            case DoubleAudioBuffer block -> {
                if (converts(block.sampleRate(), block.layout())) {
                    convert(block.layout(), block.sampleRate(), block.planes(), 0, offset, frames);
                } else {
                    flushResampler();
                    exact.write(block.planes(), offset, frames);
                }
            }
            default -> throw new IllegalStateException("not a sample block: " + buffer);
        }
    }

    /**
     * Returns whether an exact block needs the float conversion: its rate or layout differs from
     * the output's.
     *
     * @param rate the block's sampling rate
     * @param layout the block's channel layout
     * @return {@code true} when the block is converted
     */
    private boolean converts(int rate, ChannelLayout layout) {
        return rate != outputRate || !layout.equals(outputLayout);
    }

    /**
     * Converts a slice of an exact block whose rate or layout differs from the output's through
     * the float path, which queues it quantized.
     *
     * @param layout the block's layout
     * @param rate the block's rate
     * @param planes the block's {@code int[]} or {@code double[]} planes
     * @param bits the sample size of integer planes, unused for doubles
     * @param offset the index of the first sample of the slice
     * @param frames the number of samples in the slice
     */
    private void convert(ChannelLayout layout, int rate, Object[] planes, int bits, int offset, int frames) {
        if (narrowed == null || narrowed.capacity() < frames || narrowed.sampleRate() != rate
                || !narrowed.layout().equals(layout)) {
            narrowed = new AudioBuffer(layout, rate, Math.max(frames, 1));
        }
        var scale = Math.scalb(1.0, 1 - bits);
        for (var channel = 0; channel < planes.length; channel++) {
            var target = narrowed.plane(channel);
            if (planes[channel] instanceof int[] source) {
                for (var i = 0; i < frames; i++) {
                    target[i] = (float) (source[offset + i] * scale);
                }
            } else {
                var source = (double[]) planes[channel];
                for (var i = 0; i < frames; i++) {
                    target[i] = (float) source[offset + i];
                }
            }
        }
        narrowed.setFrames(frames);
        resample(mix(narrowed, 0, frames), rate, frames);
    }

    /**
     * Copies a slice of a decoded block into a new block of the same kind.
     *
     * @param buffer the decoded block
     * @param offset the index of the first sample of the slice
     * @param frames the number of samples in the slice
     * @return the copy, whose planes hold the slice from index zero
     */
    private static Object slice(Object buffer, int offset, int frames) {
        switch (buffer) {
            case AudioBuffer block -> {
                var copy = new AudioBuffer(block.layout(), block.sampleRate(), Math.max(1, frames));
                for (var channel = 0; channel < copy.channels(); channel++) {
                    System.arraycopy(block.plane(channel), offset, copy.plane(channel), 0, frames);
                }
                copy.setFrames(frames);
                return copy;
            }
            case IntegerAudioBuffer block -> {
                var copy = new IntegerAudioBuffer(block.layout(), block.sampleRate(), block.bitsPerSample(),
                        Math.max(1, frames));
                for (var channel = 0; channel < copy.channels(); channel++) {
                    System.arraycopy(block.plane(channel), offset, copy.plane(channel), 0, frames);
                }
                copy.setFrames(frames);
                return copy;
            }
            case DoubleAudioBuffer block -> {
                var copy = new DoubleAudioBuffer(block.layout(), block.sampleRate(), Math.max(1, frames));
                for (var channel = 0; channel < copy.channels(); channel++) {
                    System.arraycopy(block.plane(channel), offset, copy.plane(channel), 0, frames);
                }
                copy.setFrames(frames);
                return copy;
            }
            default -> throw new IllegalStateException("not a sample block: " + buffer);
        }
    }

    /**
     * Queues a slice of an integer block at the output's sample size.
     *
     * @param block the decoded block
     * @param offset the index of the first sample of the slice
     * @param frames the number of samples in the slice
     */
    private void queueIntegers(IntegerAudioBuffer block, int offset, int frames) {
        var bits = block.bitsPerSample();
        if (outputBits == 0) {
            outputBits = bits;
        }
        if (bits == outputBits) {
            exact.write(block.planes(), offset, frames);
            return;
        }
        var channels = block.channels();
        if (rescaled == null || rescaled[0].length < frames) {
            rescaled = new int[channels][frames];
        }
        for (var channel = 0; channel < channels; channel++) {
            var source = block.plane(channel);
            var target = rescaled[channel];
            if (bits < outputBits) {
                var shift = outputBits - bits;
                for (var i = 0; i < frames; i++) {
                    target[i] = source[offset + i] << shift;
                }
            } else {
                var shift = bits - outputBits;
                var half = 1L << (shift - 1);
                var max = (1L << (outputBits - 1)) - 1;
                for (var i = 0; i < frames; i++) {
                    target[i] = (int) Math.min((source[offset + i] + half) >> shift, max);
                }
            }
        }
        exact.write(rescaled, 0, frames);
    }

    /**
     * Converts a slice of a decoded block to {@link #outputLayout}.
     *
     * @param buffer the decoded block
     * @param offset the index of the first sample of the slice
     * @param frames the number of samples in the slice
     * @return planes in the output layout holding the slice from index zero
     */
    private float[][] mix(AudioBuffer buffer, int offset, int frames) {
        var input = buffer.planes();
        if (offset != 0) {
            var shifted = new float[input.length][];
            for (var channel = 0; channel < input.length; channel++) {
                shifted[channel] = Arrays.copyOfRange(input[channel], offset, offset + frames);
            }
            input = shifted;
        }
        if (buffer.layout().equals(outputLayout) && !customMatrix) {
            return input;
        }
        if (mixer == null || !buffer.layout().equals(mixerInput)) {
            mixer = new ChannelMixer(buffer.layout(), outputLayout, mixing);
            mixerInput = buffer.layout();
        }
        if (mixed == null || mixed[0].length < frames) {
            mixed = new float[outputLayout.channels()][frames];
        }
        mixer.process(input, mixed, frames);
        return mixed;
    }

    /**
     * Converts mixed samples to {@link #outputRate} and queues them.
     *
     * @param planes the samples in the output layout, from index zero
     * @param rate   their sampling rate
     * @param frames the number of samples
     */
    private void resample(float[][] planes, int rate, int frames) {
        if (rate == outputRate && resampler == null) {
            queue(planes, frames);
            return;
        }
        if (resampler == null || rate != resamplerInput) {
            flushResampler();
            resampler = new Resampler(rate, outputRate, outputLayout.channels(), Resampler.Quality.STANDARD);
            resamplerInput = rate;
        }
        var capacity = resampler.maxOutputFrames(frames);
        if (resampled == null || resampled[0].length < capacity) {
            resampled = new float[outputLayout.channels()][capacity];
        }
        var produced = resampler.process(planes, frames, resampled, 0);
        queue(resampled, produced);
    }

    /**
     * Queues converted samples, first dropping those still due before the start after a seek.
     *
     * @param planes the samples, from index zero
     * @param frames the number of samples
     */
    private void queue(float[][] planes, int frames) {
        var offset = 0;
        if (outputSkip > 0) {
            offset = (int) Math.min(outputSkip, frames);
            outputSkip -= offset;
        }
        if (offset >= frames) {
            return;
        }
        if (fifo != null) {
            fifo.write(planes, offset, frames - offset);
            return;
        }
        var count = frames - offset;
        if (quantized == null || Array.getLength(quantized[0]) < count) {
            quantized = new Object[planes.length];
            for (var channel = 0; channel < planes.length; channel++) {
                quantized[channel] = format == SampleFormat.INTEGER ? new int[count] : new double[count];
            }
        }
        if (format == SampleFormat.INTEGER) {
            if (outputBits == 0) {
                outputBits = 32;
            }
            var scale = Math.scalb(1.0, outputBits - 1);
            var max = (1L << (outputBits - 1)) - 1;
            for (var channel = 0; channel < planes.length; channel++) {
                var source = planes[channel];
                var target = (int[]) quantized[channel];
                for (var i = 0; i < count; i++) {
                    target[i] = (int) Math.clamp(Math.round(source[offset + i] * scale), -max - 1, max);
                }
            }
        } else {
            for (var channel = 0; channel < planes.length; channel++) {
                var source = planes[channel];
                var target = (double[]) quantized[channel];
                for (var i = 0; i < count; i++) {
                    target[i] = source[offset + i];
                }
            }
        }
        exact.write(quantized, 0, count);
    }

    /**
     * Drains the resampler's withheld samples into the queue and discards it.
     */
    private void flushResampler() {
        if (resampler == null) {
            return;
        }
        var capacity = Math.max(resampler.maxOutputFrames(0), 1);
        if (resampled == null || resampled[0].length < capacity) {
            resampled = new float[outputLayout.channels()][capacity];
        }
        var produced = resampler.flush(resampled, 0);
        queue(resampled, produced);
        resampler = null;
    }

    /**
     * Releases the codec decoder.
     */
    @Override
    public void close() {
        decoder.close();
    }

    /**
     * Describes how the timestamps of one audio track map to the output of a decode from the start
     * of the track, as {@link AudioStreamDecoder#measure(Demuxer, AudioTrack, AudioStreamDecoderOptions)}
     * measures it.
     *
     * <p>A timing tells how far before a time a demuxer must be {@link Demuxer#seek(long) seeked}
     * for a decoder restarted at that time to converge ({@link #seekMicros(long)}), and lets
     * {@link AudioStreamDecoder#startAt(long, Timing)} place the first samples decoded after the
     * seek exactly where a decode from the start has them. It holds no reference to the demuxer and
     * stays valid for every later seek in the same file.
     *
     * <p>Instances are immutable and thread-safe.
     */
    public static final class Timing {
        /**
         * The part of the pre-roll that lets a resampler restart in phase, in microseconds.
         */
        private static final long RESAMPLING_MARGIN_MICROS = 20_000;

        /**
         * The track the timing describes.
         */
        private final AudioTrack track;

        /**
         * The identifier of {@link #track}.
         */
        private final int trackId;

        /**
         * The timestamp of the track's first packet, in the track's time base, or {@link #UNKNOWN}.
         */
        private final long streamStart;

        /**
         * The {@link AudioStreamDecoder#origin()} of a decode from the start, or {@link #UNKNOWN}.
         */
        private final long origin;

        /**
         * The codec rate {@link #origin} counts in, in hertz.
         */
        private final int originRate;

        /**
         * Whether the timestamps place packets to the sample.
         */
        private final boolean exact;

        /**
         * Whether the codec's state never converges after a restart, so that every restart begins at
         * the track's first packet.
         */
        private final boolean restartsAtStart;

        /**
         * Constructs a timing.
         *
         * @param track the track the timing describes
         * @param streamStart the timestamp of its first packet, or {@link #UNKNOWN}
         * @param origin the origin of a decode from the start, or {@link #UNKNOWN}
         * @param originRate the codec rate the origin counts in
         * @param exact whether the timestamps place packets to the sample
         */
        Timing(AudioTrack track, long streamStart, long origin, int originRate, boolean exact) {
            this.track = track;
            this.trackId = track.id();
            this.streamStart = streamStart;
            this.origin = origin;
            this.originRate = originRate;
            this.exact = exact;
            this.restartsAtStart = predictsFromStart(track);
        }

        /**
         * Returns whether a track's codec keeps a prediction state that a restart never reproduces:
         * AAC Main (backward-adaptive prediction), AAC LTP, ER AAC LTP and AAC-LD (long term prediction), and
         * AAC-ELD with low delay spectral band replication, whose SBR state no pre-roll of a few frames
         * reproduces, and USAC, whose arithmetic coder context, linear prediction state and enhanced SBR state
         * reach back to the last independent frame and beyond.
         *
         * @param track the track to restart, whose codec configuration names the AAC object type
         * @return {@code true} when every restart must begin at the track's first packet
         */
        private static boolean predictsFromStart(AudioTrack track) {
            var config = track.codecConfig();
            if (track.codec() != Codec.AAC || config.isEmpty()) {
                return false;
            }
            try {
                var aac = AudioConfigs.parseAac(config.get());
                var objectType = aac.objectType();
                return objectType == 1 || objectType == 4 || objectType == 19 || objectType == 23
                        || (objectType == 39 && aac.sbr()) || objectType == 42;
            } catch (MediaException exception) {
                return false;
            }
        }

        /**
         * Returns whether the track's timestamps place every packet to the sample, so that a
         * decoder restarted after a seek aligns its output exactly with a decode from the start.
         *
         * <p>It holds when the first packets of the track, decoded from the start, all start at the
         * sample their timestamp names (up to the constant {@link AudioStreamDecoder#origin()}):
         * in Ogg, MP4, FLAC, MPEG audio, ADTS and PCM files, and in Matroska files whose packet
         * durations are whole multiples of the {@code TimestampScale} (20 ms Opus packets at the
         * default millisecond scale). It fails when the container rounds timestamps, such as
         * Matroska with Vorbis or AAC at 44.1 or 48 kHz, where no number of packets pins the
         * sample down.
         *
         * @return {@code true} when the timestamps are sample-accurate
         */
        public boolean isExact() {
            return exact;
        }

        /**
         * Returns the identifier of the track the timing describes.
         *
         * @return the track identifier
         */
        public int trackId() {
            return trackId;
        }

        /**
         * Returns how long before a seek target decoding must start for the codec's state to
         * converge to that of a decode from the start, and for a decoder that converts the rate to
         * restart its resampler in phase before the target.
         *
         * @implNote This implementation pre-rolls one second of Opus (RFC 7845 asks for at least
         * 80 ms, after which the output is close to the linear decode; the CELT and SILK filter
         * memories keep decaying for a few hundred more milliseconds before the floats agree
         * exactly), two of the longest Vorbis blocks (8192 samples each), four AAC frames, sixteen
         * MPEG audio frames (the Layer III bit reservoir reaches back up to 511 bytes, several
         * frames at low bitrates), nothing for codecs without state between packets (PCM, FLAC,
         * ALAC) and 100 ms for any other codec, plus {@value #RESAMPLING_MARGIN_MICROS}
         * microseconds for the resampler: its filter reaches back a few milliseconds and its phase
         * repeats every {@code inputRate / gcd(inputRate, outputRate)} samples, at most 441 (10 ms)
         * between the common rates.
         *
         * @return the pre-roll in microseconds
         */
        public long prerollMicros() {
            return RESAMPLING_MARGIN_MICROS + codecPrerollMicros();
        }

        /**
         * Returns how long before a seek target decoding must start for the codec's state to
         * converge.
         *
         * @return the pre-roll in microseconds
         */
        private long codecPrerollMicros() {
            var rate = rate();
            return switch (track.codec()) {
                case OPUS -> 1_000_000;
                case VORBIS -> 2L * 8192 * 1_000_000 / rate;
                case AAC -> 4L * 2048 * 1_000_000 / rate;
                case MP3, MP2 -> 16L * 1152 * 1_000_000 / rate;
                case FLAC, ALAC, PCM_S16LE, PCM_S16BE, PCM_S24LE, PCM_S24BE, PCM_S32LE, PCM_S32BE, PCM_S64LE, PCM_S64BE,
                     PCM_F32LE, PCM_F32BE, PCM_F64LE, PCM_F64BE, PCM_U8, PCM_S8, PCM_ALAW, PCM_MULAW -> 0;
                default -> 100_000;
            };
        }

        /**
         * Returns the time to seek the demuxer to before restarting a decoder at a time.
         *
         * <p>The time is {@code micros} (zero when negative) moved by the encoder priming and the
         * measured offset between timestamps and output samples, minus the
         * {@link #prerollMicros() pre-roll}; it may be negative, which seeks to the start. When
         * the timestamps are not sample-accurate ({@link #isExact()} is {@code false}), it is a
         * time before every packet, so that the decoder restarts at the track's first packet and
         * counts the samples up to {@code micros} instead of placing them by rounded timestamps.
         * It is that time too for codecs whose prediction state reaches back to the start of the
         * stream, so that no pre-roll reproduces it: AAC Main and AAC LTP (and ER AAC LTP and AAC-LD), and AAC-ELD
         * with low delay spectral band replication.
         * Every track of a demuxer restarts at its own key frame at or before the target, so a
         * caller decoding several tracks seeks to the earliest target any of them needs.
         *
         * @param micros the time the decoder restarts at, in microseconds
         * @return the seek target in microseconds
         */
        public long seekMicros(long micros) {
            if (!exact || restartsAtStart) {
                // TODO: restart near the target by summing packet durations read without decoding
                return CALIBRATION_TIME;
            }
            var rate = rate();
            var skipMicros = track.sampleRate() > 0 ? track.skipSamples() * 1_000_000 / track.sampleRate() : 0;
            var originMicros = origin == UNKNOWN ? 0 : origin * 1_000_000 / rate;
            return Math.max(0, micros) + skipMicros + originMicros - prerollMicros();
        }

        /**
         * Returns the codec rate the timing counts in.
         *
         * @return the rate in hertz: the measured codec rate, else the track's, else 48 kHz
         */
        private int rate() {
            return originRate > 0 ? originRate : track.sampleRate() > 0 ? track.sampleRate() : 48_000;
        }

        @Override
        public String toString() {
            return "AudioStreamDecoder.Timing[track=" + trackId
                    + ", streamStart=" + (streamStart == UNKNOWN ? "unknown" : streamStart)
                    + ", origin=" + (origin == UNKNOWN ? "unknown" : origin + "@" + originRate + "Hz")
                    + ", exact=" + exact + "]";
        }
    }
}
