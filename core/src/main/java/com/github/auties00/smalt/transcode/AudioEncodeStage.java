package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoder;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioStreamDecoder;
import com.github.auties00.smalt.audio.AudioStreamDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.DoubleAudioBuffer;
import com.github.auties00.smalt.audio.IntegerAudioBuffer;
import com.github.auties00.smalt.audio.SampleFormat;
import com.github.auties00.smalt.audio.loudness.LoudnessMeter;
import com.github.auties00.smalt.audio.loudness.LoudnessNormalization;
import com.github.auties00.smalt.audio.loudness.LoudnessNormalizer;
import com.github.auties00.smalt.audio.loudness.LoudnessStats;
import com.github.auties00.smalt.audio.loudness.TruePeakLimiter;
import com.github.auties00.smalt.audio.loudness.TruePeakLimiterOptions;
import com.github.auties00.smalt.audio.process.TimeStretcher;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.internal.codec.EncoderTail;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

/**
 * Decodes one audio track and encodes it again.
 *
 * <p>An {@link AudioStreamDecoder} turns the packets into exactly the samples a player outputs,
 * mixed and resampled to the encoder's input format. The stage drops the samples before the start
 * of the kept range, stops after the end, runs the {@link AudioChain} (pause removal, tempo and pitch, filters,
 * fades), applies the gain, and encodes. With a loudness
 * normalization, {@link #normalize(Demuxer, LoudnessNormalization, Cancellation)} first decodes the
 * kept range from a second demuxer and measures it after the chain, and the gain it computes replaces the
 * requested one; a peak-limiting normalization then runs the samples through a
 * {@link TruePeakLimiter}, and a single-pass one through a {@link LoudnessNormalizer} instead of the
 * measuring pass. When the demuxer seeks
 * before the start instead of reading from it ({@link #measure(Demuxer)}, then
 * {@link #startAfterSeek(AudioStreamDecoder.Timing)}), the decoder restarts sample-accurately at
 * the start, resampling in phase with a decode from the beginning of the track, so the encoder
 * receives the same samples either way. When the samples need no conversion (same rate and layout,
 * no gain, no chain step) and both codecs code integers, the decoder outputs the source's integers and the encoder
 * takes them, so a lossless source re-encodes losslessly at any sample size; failing that, an encoder
 * that takes 64-bit floats receives them, exact for 64-bit float sources. A lossless encoder keeps the
 * sample size the track declares, or else the one its codec configuration declares. Encoder packets
 * go to the muxer one packet late, so the last one can be shortened to end at the last kept sample:
 * muxers turn that into the end trim (the final Ogg granule position, the MP4 edit list, the Matroska
 * discard padding), so a player outputs exactly the kept samples. The files of a concatenation are decoded by a
 * {@link SegmentedAudioDecoder}.
 *
 * <p>An instance is not thread-safe.
 */
final class AudioEncodeStage implements Stage {
    /**
     * The number of samples per channel moved per encoder call.
     */
    private static final int BLOCK_FRAMES = 4096;

    /**
     * Describes the timing changes of an encoded stream.
     *
     * @param speed the playback speed, {@code 1} for none
     * @param cutsMicros the removed input ranges, {@code start, end} pairs in microseconds from the start of the kept
     * range, sorted and disjoint
     * @param outputMicros the output duration in microseconds, or {@code -1} when unknown
     * @param convert whether the decoder must convert to a fixed rate and layout, as when later files of a
     * concatenation may differ from the first
     */
    record Timing(double speed, long[] cutsMicros, long outputMicros, boolean convert) {
        /**
         * The timing of a stream without changes.
         */
        static final Timing NONE = new Timing(1, new long[0], -1, false);
    }

    private final AudioTrack source;

    /**
     * The decoder producing the encoder's input format.
     */
    private final SegmentedAudioDecoder decoder;

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
    private final AudioTranscodeOptions options;
    private final Timing timing;

    /**
     * Writes the encoder packets to the muxer one behind, trimming the last.
     */
    private final EncoderTail tail;

    /**
     * The block moving float samples from the decoder to the encoder, or {@code null} on the exact
     * paths.
     */
    private final AudioBuffer block;

    /**
     * The planes moving integer samples from the decoder to the encoder, or {@code null} unless the
     * decoder outputs integers.
     */
    private final int[][] integerPlanes;

    /**
     * The block wrapping {@link #integerPlanes}, recreated when the sample size becomes known.
     */
    private IntegerAudioBuffer integerBlock;

    /**
     * The block moving double samples from the decoder to the encoder, or {@code null} unless the
     * decoder outputs doubles.
     */
    private final DoubleAudioBuffer doubleBlock;

    /**
     * The block the chain's output is copied into for the encoder, or {@code null} without a chain.
     */
    private final AudioBuffer chained;

    /**
     * The chain of sample steps, or {@code null} when it changes nothing.
     */
    private final AudioChain chain;

    /**
     * Receives the chain's output.
     */
    private final AudioChain.Receiver chainOutput;

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

    /**
     * The number of kept samples passed on towards the encoder, ahead of {@link #written} by the
     * frames a processor withholds.
     */
    private long taken;

    /**
     * The single-pass loudness normalizer the samples go through, or {@code null}.
     */
    private final LoudnessNormalizer normalizer;

    /**
     * The true-peak limiter the samples go through, or {@code null}.
     */
    private final TruePeakLimiter limiter;

    /**
     * The block holding the output of {@link #normalizer} or {@link #limiter}, or {@code null}
     * without one.
     */
    private final AudioBuffer processed;

    private boolean done;

    /**
     * Constructs a stage, choosing the encoder input format and declaring the output track.
     *
     * @param source the input track
     * @param codec the codec to produce
     * @param options the audio settings
     * @param timing the timing changes
     * @param outputId the output track identifier
     * @param output the muxer receiving the packets
     * @param limits the decoder limits
     * @param startMicros the start of the kept range, in microseconds
     * @param endMicros the end of the kept range in microseconds, or {@code -1} for the end of the track
     * @throws IllegalArgumentException if the codec cannot code the stream in any input format, a filter's
     * frequency is not below half the output sampling rate, a fade-out at the end has no known output duration, or
     * a speed change that does not keep the pitch takes the pitch out of range
     * @throws MediaException.Unsupported if the codec cannot be decoded or encoded
     */
    AudioEncodeStage(AudioTrack source, Codec codec, AudioTranscodeOptions options, Timing timing, int outputId,
                     OutputMuxer output, MediaLimits limits, long startMicros, long endMicros) {
        this.source = source;
        this.output = output;
        this.options = options;
        this.timing = timing;
        this.startMicros = startMicros;
        AudioEncoder created = null;
        AudioStreamDecoder probe = null;
        SegmentedAudioDecoder decoding = null;
        var probeOptions = AudioStreamDecoderOptions.builder().limits(limits)
                .verifyIntegrity(options.verifyIntegrity()).build();
        AudioStreamDecoderOptions decodingOptions;
        var changes = timing.speed() != 1 || timing.cutsMicros().length > 0 || options.pitch() != 1
                || !options.filters().isEmpty() || !options.fades().isEmpty();
        try {
            var integerOptions = probeOptions.toBuilder().sampleFormat(SampleFormat.INTEGER).build();
            try {
                probe = AudioStreamDecoder.create(source, integerOptions);
            } catch (MediaException.Unsupported exception) {
                // the source codec has no integer samples: the double or float path decodes it
                probe = AudioStreamDecoder.create(source, probeOptions);
            }
            var integers = probe.sampleFormat() == SampleFormat.INTEGER;
            var rate = options.sampleRate().orElse(probe.sampleRate());
            var layout = options.outputLayout(probe.layout());
            // a container may leave the size of a lossless codec's samples to its configuration (FLAC or ALAC in
            // Matroska), which the integer decoder reads
            var bits = source.bitsPerSample() > 0 ? source.bitsPerSample() : integers ? probe.bitsPerSample() : 0;
            created = createEncoder(codec, rate, layout, options, bits);
            var unconverted = !timing.convert() && created.sampleRate() == probe.sampleRate()
                    && created.layout().equals(probe.layout()) && options.mixing().matrix().isEmpty();
            var exact = unconverted && !changes && options.gainDb() == 0 && options.loudnessNormalization().isEmpty();
            if (exact && integers && created.supportsIntegerInput()) {
                decodingOptions = integerOptions;
                decoding = new SegmentedAudioDecoder(probe, decodingOptions);
            } else if (unconverted && !integers && !(exact && created.supportsDoubleInput())) {
                decodingOptions = probeOptions;
                decoding = new SegmentedAudioDecoder(probe, decodingOptions);
            } else {
                probe.close();
                probe = null;
                if (exact && created.supportsDoubleInput()) {
                    decodingOptions = probeOptions.toBuilder().sampleFormat(SampleFormat.DOUBLE).build();
                } else if (unconverted) {
                    decodingOptions = probeOptions;
                } else {
                    decodingOptions = AudioStreamDecoderOptions.of(created.sampleRate(), created.layout()).toBuilder()
                            .limits(limits).verifyIntegrity(options.verifyIntegrity()).mixing(options.mixing())
                            .build();
                }
                decoding = new SegmentedAudioDecoder(AudioStreamDecoder.create(source, decodingOptions),
                        decodingOptions);
            }
            probe = null;
        } catch (RuntimeException exception) {
            if (probe != null) {
                probe.close();
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
        var format = decoder.current().sampleFormat();
        this.block = format == SampleFormat.FLOAT ? new AudioBuffer(encoder.layout(), rate, BLOCK_FRAMES) : null;
        this.integerPlanes = format == SampleFormat.INTEGER ? new int[encoder.layout().channels()][BLOCK_FRAMES] : null;
        this.doubleBlock = format == SampleFormat.DOUBLE ? new DoubleAudioBuffer(encoder.layout(), rate, BLOCK_FRAMES) : null;
        AudioChain built;
        try {
            built = changes ? createChain() : null;
        } catch (RuntimeException exception) {
            close();
            throw exception;
        }
        this.chain = built;
        this.chained = chain == null ? null : new AudioBuffer(encoder.layout(), rate, BLOCK_FRAMES);
        this.chainOutput = this::encodeChained;
        var normalization = options.loudnessNormalization().orElse(null);
        var ceiling = normalization == null ? null : normalization.truePeakCeilingDb();
        if (normalization != null && options.singlePassNormalization()) {
            this.normalizer = new LoudnessNormalizer(encoder.layout(), rate, normalization);
            this.limiter = null;
            this.processed = new AudioBuffer(encoder.layout(), rate, normalizer.delay() + BLOCK_FRAMES);
        } else if (normalization != null && normalization.peakLimiting() && ceiling.isPresent()) {
            this.normalizer = null;
            this.limiter = new TruePeakLimiter(encoder.layout().channels(), rate,
                    TruePeakLimiterOptions.of(Math.clamp(ceiling.getAsDouble(), -60, 0)));
            this.processed = new AudioBuffer(encoder.layout(), rate, Math.max(BLOCK_FRAMES, limiter.delay()));
        } else {
            this.normalizer = null;
            this.limiter = null;
            this.processed = null;
        }
        var outputFrames = keep == Long.MAX_VALUE ? -1 : chain == null ? keep : chain.outputFrames(keep);
        this.declared = EncoderTail.track(encoder, outputId)
                .language(source.language().orElse(null))
                .duration(outputFrames)
                .build();
        this.tail = new EncoderTail(outputId, output::write);
        output.declare(declared);
    }

    /**
     * Creates the chain of sample steps at the encoder's input format.
     *
     * @return the chain
     * @throws IllegalArgumentException if a filter's frequency is not below half the sampling rate, a fade-out at
     * the end has no known output duration, or a speed change that does not keep the pitch multiplies the pitch
     * factor out of range
     */
    private AudioChain createChain() {
        var rate = encoder.sampleRate();
        var micros = timing.cutsMicros();
        var cuts = new long[micros.length];
        var samples = Rational.timeBaseOf(rate);
        for (var index = 0; index < micros.length; index++) {
            cuts[index] = Rational.MICROSECONDS.rescale(micros[index], samples);
        }
        var pitch = options.preservePitch() ? options.pitch() : options.pitch() * timing.speed();
        if (!(pitch >= TimeStretcher.MIN_FACTOR && pitch <= TimeStretcher.MAX_FACTOR)) {
            throw new IllegalArgumentException("a speed of " + timing.speed() + " that does not keep the pitch, with a "
                    + "pitch of " + options.pitch() + ", changes the pitch by " + pitch + ", outside "
                    + TimeStretcher.MIN_FACTOR + " to " + TimeStretcher.MAX_FACTOR);
        }
        return new AudioChain(encoder.layout().channels(), rate, BLOCK_FRAMES, cuts, timing.speed(), pitch,
                options.filters(), options.fades(), timing.outputMicros());
    }

    /**
     * Creates the encoder, falling back to 48 kHz, to the ACN and SN3D form of an ambisonic layout and
     * to stereo when the codec rejects the requested input format and the options leave it free.
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
        decoder.current().startAt(startMicros, timing);
        dropRemaining = 0;
    }

    /**
     * Measures the kept range of the input track and sets the gain that normalizes it.
     *
     * <p>The track is decoded from the start of the demuxer exactly as the encoding pass decodes
     * it (same decoder output format, same dropped and kept samples, same chain) and fed to a
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
        var measuringChain = chain == null ? null : createChain();
        AudioChain.Receiver metered = (planes, frames) -> meter.accept(planes, 0, frames);
        var measuringOptions = floatOptions();
        try (var measuring = new SegmentedAudioDecoder(AudioStreamDecoder.create(source, measuringOptions),
                measuringOptions)) {
            if (demuxer instanceof SegmentedDemuxer segmented) {
                segmented.segmentListener((trackId, track, segmentStart) -> {
                    if (trackId == source.id()) {
                        measuring.startSegment((AudioTrack) track, segmentStart);
                    }
                });
            }
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
                        if (offset > 0) {
                            for (var plane : planes) {
                                System.arraycopy(plane, offset, plane, 0, count);
                            }
                        }
                        if (measuringChain == null) {
                            meter.accept(planes, 0, count);
                        } else {
                            measuringChain.process(planes, count, metered);
                        }
                        kept += count;
                    }
                }
                if (kept >= keep) {
                    break;
                }
            }
            if (measuringChain != null) {
                measuringChain.finish(metered);
            }
        }
        LoudnessStats loudness = meter.stats();
        gain = (float) StrictMath.pow(10, normalization.gainDb(loudness) / 20);
    }

    /**
     * {@return the decoder options with float output, for the measuring pass}
     */
    private AudioStreamDecoderOptions floatOptions() {
        return decoderOptions.sampleFormat() == SampleFormat.FLOAT ? decoderOptions
                : decoderOptions.toBuilder().sampleFormat(SampleFormat.FLOAT).build();
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

    @Override
    public void startSegment(Track track, long startMicros) {
        if (done) {
            return;
        }
        decoder.startSegment((AudioTrack) track, startMicros);
    }

    /**
     * Moves every decoded sample through the trim, the chain and the gain into the encoder.
     */
    private void drain() {
        if (block == null) {
            drainExact();
            return;
        }
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
            if (taken + frames >= keep) {
                frames = (int) (keep - taken);
                done = true;
            }
            if (frames <= 0) {
                break;
            }
            taken += frames;
            if (chain != null) {
                chain.process(planes, frames, chainOutput);
            } else {
                encodeFloats(block, frames);
            }
        }
    }

    /**
     * Encodes a block the chain delivered.
     *
     * @param planes the samples, from index zero
     * @param frames the number of frames
     */
    private void encodeChained(float[][] planes, int frames) {
        var target = chained.planes();
        for (var channel = 0; channel < target.length; channel++) {
            System.arraycopy(planes[channel], 0, target[channel], 0, frames);
        }
        encodeFloats(chained, frames);
    }

    /**
     * Applies the gain and the loudness processors to a float block and encodes it.
     *
     * @param buffer the block, its planes holding the samples from index zero
     * @param frames the number of frames
     */
    private void encodeFloats(AudioBuffer buffer, int frames) {
        var planes = buffer.planes();
        if (gain != 1f) {
            for (var plane : planes) {
                for (var index = 0; index < frames; index++) {
                    plane[index] *= gain;
                }
            }
        }
        if (processed == null) {
            buffer.setFrames(frames);
            encoder.encode(buffer, tail);
            written += frames;
        } else if (normalizer != null) {
            encodeProcessed(normalizer.process(planes, 0, frames, processed.planes(), 0));
        } else {
            encodeProcessed(limiter.process(planes, 0, frames, processed.planes(), 0));
        }
    }

    /**
     * Encodes the frames a processor wrote into {@link #processed}.
     *
     * @param frames the number of frames written
     */
    private void encodeProcessed(int frames) {
        if (frames > 0) {
            processed.setFrames(frames);
            encoder.encode(processed, tail);
            written += frames;
        }
    }

    /**
     * Moves every decoded integer or double sample through the trim into the encoder.
     */
    private void drainExact() {
        Object[] planes = integerPlanes != null ? integerPlanes : doubleBlock.planes();
        int frames;
        while (!done && (frames = integerPlanes != null ? decoder.readIntegers(integerPlanes, 0, BLOCK_FRAMES)
                : decoder.readDoubles(doubleBlock.planes(), 0, BLOCK_FRAMES)) > 0) {
            if (dropRemaining > 0) {
                var offset = (int) Math.min(dropRemaining, frames);
                dropRemaining -= offset;
                frames -= offset;
                if (frames == 0) {
                    continue;
                }
                for (var plane : planes) {
                    System.arraycopy(plane, offset, plane, 0, frames);
                }
            }
            if (taken + frames >= keep) {
                frames = (int) (keep - taken);
                done = true;
            }
            if (frames <= 0) {
                break;
            }
            taken += frames;
            if (integerPlanes != null) {
                var bits = decoder.bitsPerSample();
                if (integerBlock == null || integerBlock.bitsPerSample() != bits) {
                    integerBlock = new IntegerAudioBuffer(encoder.layout(), encoder.sampleRate(), bits, integerPlanes, 0);
                }
                integerBlock.setFrames(frames);
                encoder.encode(integerBlock, tail);
            } else {
                doubleBlock.setFrames(frames);
                encoder.encode(doubleBlock, tail);
            }
            written += frames;
        }
    }

    @Override
    public void finish() {
        if (!done) {
            decoder.finish();
            drain();
        }
        if (chain != null) {
            chain.finish(chainOutput);
        }
        if (normalizer != null) {
            encodeProcessed(normalizer.flush(processed.planes(), 0));
        } else if (limiter != null) {
            encodeProcessed(limiter.flush(processed.planes(), 0));
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
            if (decoder != null) {
                decoder.close();
            }
        } finally {
            if (encoder != null) {
                encoder.close();
            }
        }
    }
}
