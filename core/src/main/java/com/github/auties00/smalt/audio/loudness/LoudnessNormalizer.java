package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.audio.ChannelLayout;

import java.util.Objects;

/**
 * Normalizes the loudness of planar float audio in a single pass, without measuring the program first.
 *
 * <p>The gain follows the loudness of the program around each moment: every 100 ms it is set to
 * {@link LoudnessNormalization#targetLufs()} minus the loudness of the 3 s of input centered on that
 * moment, gated as ITU-R BS.1770-4 gates the integrated loudness (the 400 ms blocks of the window above
 * -70 LUFS, then above a relative gate 10 LU below their mean); it is capped by
 * {@link LoudnessNormalization#maxGainDb()}, and it is held while no block of the window passes the
 * gates or their loudness is more than 20 LU below the integrated loudness of the input so far, so that
 * pauses are not raised to the target. Between two updates the linear gain moves in a straight line. When
 * the normalization has a true-peak ceiling, a {@link TruePeakLimiter} with that ceiling, clamped to -60..0
 * dBTP, follows the gain, whatever {@link LoudnessNormalization#peakLimiting()} says.
 *
 * <p>A program of steady loudness comes out at the target. A program whose loudness changes comes out
 * close to the target overall, with its loudness range compressed: changes slower than a few seconds are
 * leveled. For a program that must keep its dynamics, measure it first and apply one gain
 * ({@link LoudnessNormalization#gainDb(LoudnessStats)}).
 *
 * <p>The normalizer has <em>zero net delay</em>: {@link #process(float[][], int, int, float[][], int)}
 * withholds the {@link #delay()} frames whose gain needs later input, {@link #flush(float[][], int)}
 * releases them, and the stream yields as many frames as it received. The output does not depend on how
 * the input is split into calls.
 *
 * <p>Instances are not thread-safe. After {@link #flush(float[][], int)} a normalizer is back in its
 * initial state and can process a new stream.
 */
public final class LoudnessNormalizer {
    /**
     * The number of gain updates per second.
     */
    private static final int UPDATES_PER_SECOND = 10;

    /**
     * The look-ahead, in updates: half the 3 s window.
     */
    private static final int LOOKAHEAD_UPDATES = 15;

    /**
     * The window, in updates.
     */
    private static final int WINDOW_UPDATES = 30;

    /**
     * The number of updates in a 400 ms block.
     */
    private static final int BLOCK_UPDATES = 4;

    /**
     * The relative gate of the window, as an energy ratio: 10 LU.
     */
    private static final double WINDOW_GATE = 0.1;

    /**
     * The lowest loudness that moves the gain, in LUFS.
     */
    private static final double ABSOLUTE_GATE = -70;

    /**
     * How far below the integrated loudness a moment may be and still move the gain, in LU.
     */
    private static final double RELATIVE_GATE = 20;

    /**
     * The settings.
     */
    private final LoudnessNormalization normalization;

    /**
     * The number of channels.
     */
    private final int channels;

    /**
     * The sampling rate in hertz.
     */
    private final int sampleRate;

    /**
     * The meter of the input.
     */
    private final LoudnessMeter meter;

    /**
     * The limiter after the gain, or {@code null} without a ceiling.
     */
    private final TruePeakLimiter limiter;

    /**
     * The number of frames between gain updates.
     */
    private final int block;

    /**
     * The number of frames of input the gain looks ahead.
     */
    private final int lookahead;

    /**
     * The delayed input of each channel, a ring of {@link #lookahead} plus one block.
     */
    private final float[][] delayed;

    /**
     * One block of gained samples on its way to the limiter or the output.
     */
    private final float[][] gained;

    /**
     * The number of input frames received since the start of the stream.
     */
    private long received;

    /**
     * The number of frames the gain has been applied to.
     */
    private long applied;

    /**
     * The linear gain at the start of the block being applied.
     */
    private double startGain;

    /**
     * The linear gain at the end of the block being applied.
     */
    private double endGain;

    /**
     * The last gain update, in dB.
     */
    private double gainDb;

    /**
     * The energies of the 400 ms blocks ending at the last {@link #WINDOW_UPDATES} update points, a ring.
     */
    private final double[] window;

    /**
     * The number of valid entries of {@link #window}.
     */
    private int windowCount;

    /**
     * The index of {@link #window} the next block energy goes to.
     */
    private int windowNext;

    /**
     * Constructs a normalizer.
     *
     * @param layout the channel layout, which sets the loudness weights
     * @param sampleRate the sampling rate in hertz, from 8000 to 2822400
     * @param normalization the target, the gain limit and the true-peak ceiling
     * @throws IllegalArgumentException if the sampling rate is out of range or the layout has more than
     * 1024 channels
     */
    public LoudnessNormalizer(ChannelLayout layout, int sampleRate, LoudnessNormalization normalization) {
        Objects.requireNonNull(layout, "layout");
        this.normalization = Objects.requireNonNull(normalization, "normalization");
        this.meter = new LoudnessMeter(layout, sampleRate,
                LoudnessMeterOptions.builder().truePeak(false).build());
        this.channels = layout.channels();
        this.sampleRate = sampleRate;
        var ceiling = normalization.truePeakCeilingDb();
        this.limiter = ceiling.isPresent()
                ? new TruePeakLimiter(channels, sampleRate, TruePeakLimiterOptions.of(Math.clamp(ceiling.getAsDouble(),
                -60, 0)))
                : null;
        this.block = sampleRate / UPDATES_PER_SECOND;
        this.lookahead = block * LOOKAHEAD_UPDATES;
        this.delayed = new float[channels][lookahead + block];
        this.gained = new float[channels][block];
        this.window = new double[WINDOW_UPDATES];
        reset();
    }

    /**
     * {@return the normalization this normalizer applies}
     */
    public LoudnessNormalization normalization() {
        return normalization;
    }

    /**
     * {@return the number of channels}
     */
    public int channels() {
        return channels;
    }

    /**
     * {@return the sampling rate in hertz}
     */
    public int sampleRate() {
        return sampleRate;
    }

    /**
     * {@return the gain of the last update, in dB; 0 before the first}
     */
    public double gainDb() {
        return gainDb;
    }

    /**
     * Returns the largest number of frames the normalizer withholds until later input or
     * {@link #flush(float[][], int)} releases them.
     *
     * <p>This is a latency, not a shift: the output is never delayed relative to the input.
     *
     * @return the look-ahead in frames: 1.6 s plus the limiter's {@link TruePeakLimiter#delay()}
     */
    public int delay() {
        return lookahead + block + (limiter == null ? 0 : limiter.delay());
    }

    /**
     * Returns the largest number of frames the next call can write: a
     * {@link #process(float[][], int, int, float[][], int)} of {@code inputFrames} frames, or
     * {@link #flush(float[][], int)} after it.
     *
     * @param inputFrames the number of input frames about to be processed; non-negative
     * @return the bound, in frames
     * @throws IllegalArgumentException if {@code inputFrames} is negative
     */
    public int maxOutputFrames(int inputFrames) {
        if (inputFrames < 0) {
            throw new IllegalArgumentException("negative frame count " + inputFrames);
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.min(received, delay()) + (long) inputFrames);
    }

    /**
     * Normalizes a block of input and writes every output frame whose gain is known.
     *
     * @param input the input planes, one per channel
     * @param inputOffset the index in each input plane of the first frame
     * @param frames the number of input frames; non-negative
     * @param output the output planes, one per channel, distinct from the input planes
     * @param outputOffset the index in each output plane of the first frame written
     * @return the number of frames written to each output plane, at most {@code maxOutputFrames(frames)}
     * @throws IllegalArgumentException if a plane count differs from {@link #channels()}, an input plane
     * holds fewer than {@code inputOffset + frames} samples, an output plane cannot hold
     * {@code maxOutputFrames(frames)} frames at {@code outputOffset}, or an offset or {@code frames} is
     * negative
     */
    public int process(float[][] input, int inputOffset, int frames, float[][] output, int outputOffset) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (input.length != channels || output.length != channels) {
            throw new IllegalArgumentException("expected " + channels + " planes, got " + input.length + " input and "
                    + output.length + " output planes");
        }
        if (frames < 0 || inputOffset < 0 || outputOffset < 0) {
            throw new IllegalArgumentException("negative frame count or offset");
        }
        var capacity = maxOutputFrames(frames);
        for (var c = 0; c < channels; c++) {
            if (Objects.requireNonNull(input[c], "input plane").length - (long) inputOffset < frames) {
                throw new IllegalArgumentException("input plane " + c + " holds fewer than " + frames + " frames");
            }
            if (Objects.requireNonNull(output[c], "output plane").length - (long) outputOffset < capacity) {
                throw new IllegalArgumentException("output plane " + c + " cannot hold " + capacity + " frames");
            }
        }
        var written = 0;
        var consumed = 0;
        while (consumed < frames) {
            var count = (int) Math.min(frames - consumed, block - received % block);
            meter.accept(input, inputOffset + consumed, count);
            for (var c = 0; c < channels; c++) {
                var plane = delayed[c];
                for (var i = 0; i < count; i++) {
                    plane[(int) ((received + i) % plane.length)] = input[c][inputOffset + consumed + i];
                }
            }
            received += count;
            consumed += count;
            if (received % block == 0) {
                if (received >= (long) block * BLOCK_UPDATES) {
                    window[windowNext] = StrictMath.pow(10, (meter.momentary() + 0.691) / 10);
                    windowNext = (windowNext + 1) % WINDOW_UPDATES;
                    windowCount = Math.min(windowCount + 1, WINDOW_UPDATES);
                }
                if (received - applied > lookahead) {
                    update();
                    written += apply(block, output, outputOffset + written);
                }
            }
        }
        return written;
    }

    /**
     * Ends the stream: writes every withheld frame and returns the normalizer to its initial state.
     *
     * @param output the output planes, one per channel
     * @param outputOffset the index in each output plane of the first frame written
     * @return the number of frames written to each output plane, at most {@code maxOutputFrames(0)}
     * @throws IllegalArgumentException if the plane count differs from {@link #channels()} or an output
     * plane cannot hold the frames produced
     */
    public int flush(float[][] output, int outputOffset) {
        Objects.requireNonNull(output, "output");
        if (output.length != channels) {
            throw new IllegalArgumentException("expected " + channels + " output planes, got " + output.length);
        }
        if (outputOffset < 0) {
            throw new IllegalArgumentException("negative offset " + outputOffset);
        }
        var capacity = maxOutputFrames(0);
        for (var c = 0; c < channels; c++) {
            if (Objects.requireNonNull(output[c], "output plane").length - (long) outputOffset < capacity) {
                throw new IllegalArgumentException("output plane " + c + " cannot hold " + capacity + " frames");
            }
        }
        var written = 0;
        while (applied < received) {
            update();
            written += apply((int) Math.min(block, received - applied), output, outputOffset + written);
        }
        if (limiter != null) {
            written += limiter.flush(output, outputOffset + written);
        }
        reset();
        return written;
    }

    /**
     * Computes the gain at the end of the next block to apply from the loudness measured so far.
     */
    private void update() {
        var integrated = meter.integrated();
        var loudness = windowLoudness();
        if (loudness >= ABSOLUTE_GATE && !(loudness < integrated - RELATIVE_GATE)) {
            var maxGain = normalization.maxGainDb();
            var gain = normalization.targetLufs() - loudness;
            gainDb = maxGain.isPresent() ? Math.min(gain, maxGain.getAsDouble()) : gain;
        }
        startGain = endGain;
        endGain = StrictMath.pow(10, gainDb / 20);
        if (applied == 0) {
            startGain = endGain;
        }
    }

    /**
     * Returns the gated loudness of the blocks in the window.
     *
     * @return the loudness in LUFS, negative infinity when no block passes the gates
     */
    private double windowLoudness() {
        var gate = StrictMath.pow(10, (ABSOLUTE_GATE + 0.691) / 10);
        var sum = 0.0;
        var count = 0;
        for (var index = 0; index < windowCount; index++) {
            if (window[index] >= gate) {
                sum += window[index];
                count++;
            }
        }
        if (count == 0) {
            return Double.NEGATIVE_INFINITY;
        }
        var relative = Math.max(gate, sum / count * WINDOW_GATE);
        sum = 0;
        count = 0;
        for (var index = 0; index < windowCount; index++) {
            if (window[index] >= relative) {
                sum += window[index];
                count++;
            }
        }
        return count == 0 ? Double.NEGATIVE_INFINITY : LoudnessMeter.loudnessOf(sum / count);
    }

    /**
     * Applies the gain ramp to the next delayed frames and passes them to the limiter or the output.
     *
     * @param count the number of frames, at most one block
     * @param output the output planes
     * @param outputOffset the index of the first frame written
     * @return the number of frames written
     */
    private int apply(int count, float[][] output, int outputOffset) {
        var target = limiter == null ? output : gained;
        var offset = limiter == null ? outputOffset : 0;
        for (var c = 0; c < channels; c++) {
            var plane = delayed[c];
            var out = target[c];
            for (var i = 0; i < count; i++) {
                var gain = startGain + (endGain - startGain) * (i + 1) / block;
                out[offset + i] = (float) (plane[(int) ((applied + i) % plane.length)] * gain);
            }
        }
        applied += count;
        return limiter == null ? count : limiter.process(gained, 0, count, output, outputOffset);
    }

    /**
     * Returns the normalizer to the start of a new stream.
     */
    private void reset() {
        meter.reset();
        received = 0;
        applied = 0;
        gainDb = 0;
        startGain = 1;
        endGain = 1;
        windowCount = 0;
        windowNext = 0;
    }
}
