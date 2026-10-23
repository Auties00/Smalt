package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioStreamDecoder;
import com.github.auties00.smalt.audio.AudioStreamDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.loudness.LoudnessMeter;
import com.github.auties00.smalt.audio.loudness.LoudnessNormalization;
import com.github.auties00.smalt.audio.loudness.LoudnessStats;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.internal.codec.EncoderTail;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;

/**
 * Decodes one audio track and encodes it again.
 *
 * <p>An {@link AudioStreamDecoder} turns the packets into exactly the samples a player outputs,
 * mixed and resampled to the encoder's input format. The stage drops the samples before the start
 * of the kept range, stops after the end, applies the gain, and encodes. With a loudness
 * normalization, {@link #normalize(Demuxer, LoudnessNormalization, Cancellation)} first decodes the
 * kept range from a second demuxer and measures it, and the gain it computes replaces the
 * requested one. When the demuxer seeks
 * before the start instead of reading from it ({@link #measure(Demuxer)}, then
 * {@link #startAfterSeek(AudioStreamDecoder.Timing)}), the decoder restarts sample-accurately at
 * the start, resampling in phase with a decode from the beginning of the track, so the encoder
 * receives the same samples either way. Encoder packets go to the muxer one packet late, so the
 * last one can be shortened to end at the last kept sample: muxers turn that into the end trim
 * (the final Ogg granule position, the MP4 edit list, the Matroska discard padding), so a player
 * outputs exactly the kept samples.
 *
 * <p>An instance is not thread-safe.
 */
final class AudioEncodeStage implements Stage {
    /**
     * The number of samples per channel moved per encoder call.
     */
    private static final int BLOCK_FRAMES = 4096;

    private final AudioTrack source;

    /**
     * The decoder producing the encoder's input format.
     */
    private final AudioStreamDecoder decoder;

    /**
     * The options {@link #decoder} was created with.
     */
    private final AudioStreamDecoderOptions decoderOptions;

    /**
     * The start of the kept range in microseconds.
     */
    private final long startMicros;

    private final AudioEncoder encoder;
    private final AudioTrack declared;
    private final OutputMuxer output;

    /**
     * Writes the encoder packets to the muxer one behind, trimming the last.
     */
    private final EncoderTail tail;

    /**
     * The block moving samples from the decoder to the encoder.
     */
    private final AudioBuffer block;

    /**
     * The linear gain, {@code 1} for none.
     */
    private float gain;

    /**
     * The number of decoded samples still to drop before the start.
     */
    private long dropRemaining;

    /**
     * The number of decoded samples dropped before the start when decoding from the beginning.
     */
    private final long dropAtStart;

    /**
     * The number of samples to keep, or {@link Long#MAX_VALUE} for all.
     */
    private final long keep;

    /**
     * The number of samples encoded.
     */
    private long written;

    private boolean done;

    /**
     * Constructs a stage, choosing the encoder input format and declaring the output track.
     *
     * @param source the input track
     * @param codec the codec to produce
     * @param options the audio settings
     * @param outputId the output track identifier
     * @param output the muxer receiving the packets
     * @param limits the decoder limits
     * @param startMicros the start of the kept range, in microseconds
     * @param endMicros the end of the kept range in microseconds, or {@code -1} for the end of the track
     * @throws IllegalArgumentException if the codec cannot code the stream in any input format
     * @throws MediaException.Unsupported if the codec cannot be decoded or encoded
     */
    AudioEncodeStage(AudioTrack source, Codec codec, AudioTranscodeOptions options, int outputId, OutputMuxer output,
                     MediaLimits limits, long startMicros, long endMicros) {
        this.source = source;
        this.output = output;
        this.startMicros = startMicros;
        AudioEncoder created = null;
        AudioStreamDecoder probe = null;
        AudioStreamDecoder decoding = null;
        var probeOptions = AudioStreamDecoderOptions.builder().limits(limits).build();
        AudioStreamDecoderOptions decodingOptions;
        try {
            probe = AudioStreamDecoder.create(source, probeOptions);
            var rate = options.sampleRate().orElse(probe.sampleRate());
            var layout = options.outputLayout(probe.layout());
            created = createEncoder(codec, rate, layout, options, source.bitsPerSample());
            if (created.sampleRate() == probe.sampleRate() && created.layout().equals(probe.layout())) {
                decoding = probe;
                decodingOptions = probeOptions;
            } else {
                probe.close();
                decodingOptions = AudioStreamDecoderOptions.of(created.sampleRate(), created.layout()).toBuilder()
                        .limits(limits).build();
                decoding = AudioStreamDecoder.create(source, decodingOptions);
            }
            probe = null;
        } catch (RuntimeException exception) {
            if (probe != null) {
                probe.close();
            }
            if (decoding != null) {
                decoding.close();
            }
            if (created != null) {
                created.close();
            }
            throw exception;
        }
        this.encoder = created;
        this.decoder = decoding;
        this.decoderOptions = decodingOptions;
        var rate = encoder.sampleRate();
        var samples = Rational.timeBaseOf(rate);
        this.dropRemaining = Rational.MICROSECONDS.rescale(startMicros, samples);
        this.dropAtStart = dropRemaining;
        this.keep = endMicros < 0 ? Long.MAX_VALUE : Rational.MICROSECONDS.rescale(endMicros - startMicros, samples);
        this.gain = (float) StrictMath.pow(10, options.gainDb() / 20);
        this.block = new AudioBuffer(encoder.layout(), rate, BLOCK_FRAMES);
        this.declared = EncoderTail.track(encoder, outputId)
                .language(source.language().orElse(null))
                .duration(keep == Long.MAX_VALUE ? -1 : keep)
                .build();
        this.tail = new EncoderTail(outputId, output::write);
        output.declare(declared);
    }

    /**
     * Creates the encoder, falling back to 48 kHz and to stereo when the codec rejects the
     * requested input format and the options leave it free.
     *
     * @param codec the output codec
     * @param rate the requested sampling rate
     * @param layout the requested channel layout
     * @param options the audio settings
     * @param bits the source's sample size, which a lossless codec keeps, or 0 when unknown
     * @return the encoder
     * @throws IllegalArgumentException if the codec rejects every candidate format
     */
    private static AudioEncoder createEncoder(Codec codec, int rate, ChannelLayout layout, AudioTranscodeOptions options,
                                              int bits) {
        return EncoderTail.open(codec, rate, layout, options.sampleRate().isPresent(), options.layout().isPresent(),
                (candidateRate, candidateLayout) -> encoderOptions(candidateRate, candidateLayout, options, bits));
    }

    /**
     * Builds the encoder options of one candidate input format.
     *
     * @param rate the sampling rate
     * @param layout the channel layout
     * @param options the audio settings
     * @param bits the source's sample size, or 0 when unknown
     * @return the encoder options
     */
    private static AudioEncoderOptions encoderOptions(int rate, ChannelLayout layout, AudioTranscodeOptions options, int bits) {
        var encoderOptions = AudioEncoderOptions.of(rate, layout);
        if (bits > 0 && bits <= 32) {
            encoderOptions = encoderOptions.toBuilder().bitsPerSample(bits).build();
        }
        var bitrate = options.bitrate();
        return bitrate.isPresent() ? encoderOptions.toBuilder().bitrate(bitrate.getAsInt()).build() : encoderOptions;
    }

    /**
     * Measures how the input track's timestamps map to the decoder's output, for a demuxer about to
     * seek before the start of the kept range.
     *
     * @param demuxer the demuxer, left at an unspecified position
     * @return the timing, whose {@link AudioStreamDecoder.Timing#seekMicros(long)} at the start
     * is the latest seek target this stage accepts
     * @throws MediaException.Unsupported if the demuxer cannot seek
     */
    AudioStreamDecoder.Timing measure(Demuxer demuxer) {
        return AudioStreamDecoder.measure(demuxer, source, decoderOptions);
    }

    /**
     * Makes the stage start from a demuxer seeked to {@code timing.seekMicros(start)} or earlier,
     * instead of decoding from the start of the track and dropping.
     *
     * @param timing the timing {@link #measure(Demuxer) measured} for the input track
     * @throws IllegalStateException if a packet was already received
     */
    void startAfterSeek(AudioStreamDecoder.Timing timing) {
        decoder.startAt(startMicros, timing);
        dropRemaining = 0;
    }

    /**
     * Measures the kept range of the input track and sets the gain that normalizes it.
     *
     * <p>The track is decoded from the start of the demuxer exactly as the encoding pass decodes
     * it (same decoder output format, same dropped and kept samples) and fed to a
     * {@link LoudnessMeter}; the gain {@link LoudnessNormalization#gainDb(LoudnessStats)} returns
     * then replaces the requested one. Must be called before the first packet.
     *
     * @param demuxer a demuxer of the input at its start, used by this pass only
     * @param normalization the target and true-peak ceiling
     * @param cancellation the cancellation of the job, checked between packets
     * @throws IllegalArgumentException if the encoder's sampling rate is below 8 kHz
     */
    void normalize(Demuxer demuxer, LoudnessNormalization normalization, Cancellation cancellation) {
        var meter = new LoudnessMeter(encoder.layout(), encoder.sampleRate());
        try (var measuring = AudioStreamDecoder.create(source, decoderOptions)) {
            var planes = new float[encoder.layout().channels()][BLOCK_FRAMES];
            var drop = dropAtStart;
            var kept = 0L;
            var ended = false;
            while (!ended) {
                cancellation.check();
                var packet = demuxer.read();
                if (packet == null) {
                    measuring.finish();
                    ended = true;
                } else if (packet.trackId() == source.id()) {
                    measuring.decode(packet);
                } else {
                    continue;
                }
                int frames;
                while (kept < keep && (frames = measuring.read(planes, 0, BLOCK_FRAMES)) > 0) {
                    var offset = (int) Math.min(drop, frames);
                    drop -= offset;
                    var count = (int) Math.min(frames - offset, keep - kept);
                    if (count > 0) {
                        meter.accept(planes, offset, count);
                        kept += count;
                    }
                }
                if (kept >= keep) {
                    break;
                }
            }
        }
        LoudnessStats loudness = meter.stats();
        gain = (float) StrictMath.pow(10, normalization.gainDb(loudness) / 20);
    }

    @Override
    public int sourceTrackId() {
        return source.id();
    }

    @Override
    public void accept(Packet packet) {
        if (done) {
            return;
        }
        decoder.decode(packet);
        drain();
    }

    /**
     * Moves every decoded sample through the trim and the gain into the encoder.
     */
    private void drain() {
        var planes = block.planes();
        int frames;
        while (!done && (frames = decoder.read(planes, 0, BLOCK_FRAMES)) > 0) {
            var offset = 0;
            if (dropRemaining > 0) {
                offset = (int) Math.min(dropRemaining, frames);
                dropRemaining -= offset;
                frames -= offset;
                if (frames == 0) {
                    continue;
                }
                for (var plane : planes) {
                    System.arraycopy(plane, offset, plane, 0, frames);
                }
            }
            if (written + frames >= keep) {
                frames = (int) (keep - written);
                done = true;
            }
            if (frames <= 0) {
                break;
            }
            if (gain != 1f) {
                for (var plane : planes) {
                    for (var index = 0; index < frames; index++) {
                        plane[index] *= gain;
                    }
                }
            }
            block.setFrames(frames);
            encoder.encode(block, tail);
            written += frames;
        }
    }

    @Override
    public void finish() {
        if (!done) {
            decoder.finish();
            drain();
        }
        encoder.flush(tail);
        tail.finish(written + encoder.delay());
        encoder.codecConfig().ifPresent(config -> output.updateCodecConfig(declared.id(), config));
        done = true;
    }

    @Override
    public boolean done() {
        return done;
    }

    @Override
    public long positionMicros() {
        return Rational.timeBaseOf(encoder.sampleRate()).toMicros(written);
    }

    @Override
    public TranscodedStream result() {
        return new TranscodedStream(source.id(), declared, false);
    }

    @Override
    public void close() {
        try {
            decoder.close();
        } finally {
            encoder.close();
        }
    }
}
