package com.github.auties00.smalt.audio.process;

import java.util.Arrays;
import java.util.Objects;

/**
 * Converts planar float audio from one sampling rate to another with a polyphase windowed-sinc
 * filter.
 *
 * <p>The ratio {@code outputRate / inputRate} is reduced to lowest terms {@code L / M}. Output
 * sample {@code n} is the input signal evaluated at the exact input position {@code n * M / L}:
 * its integer part selects the input samples under the filter and its fractional part, a whole
 * multiple of {@code 1 / L}, selects one of {@code L} precomputed filter phases. Positions are
 * derived with integer arithmetic, so there is no drift over arbitrarily long streams.
 *
 * <p>The resampler has <em>zero net delay</em>: output sample {@code 0} is aligned with input
 * sample {@code 0}, and an impulse at input sample {@code N} appears centered on output position
 * {@code N * outputRate / inputRate}. The signal is taken to be zero before its first sample and
 * after its last. Because the filter looks {@link #delay()} output frames ahead, each call to
 * {@link #process(float[][], int, float[][], int)} withholds the frames whose look-ahead has not
 * arrived yet; {@link #flush(float[][], int)} releases them once the input ends. The whole stream
 * then yields exactly {@code round(totalInputFrames * outputRate / inputRate)} frames (halves
 * rounded up), and every output sample is bit-identical however the input is split into calls,
 * because each one is computed from the same inputs and coefficients in the same order.
 *
 * <p>Equal input and output rates copy the input unchanged, with no filtering and no look-ahead.
 *
 * <p>{@link #setRatio(double)} turns the resampler into a variable-ratio one, for example to follow
 * the drift between two audio clocks: from then on output frames are spaced {@code 1 / ratio} input
 * frames apart, the ratio taking effect at the first frame not yet written (withheld frames included),
 * and the stream ends with the last output frame whose position lies at least half a spacing before
 * the end of the input. Positions are kept in fixed point with 32 fractional bits, so they never
 * drift, and output stays bit-identical however the input is split into calls, as long as the ratio
 * changes at the same output frames. The filter stays the one designed for the nominal rates.
 *
 * <p>A resampler is not thread-safe. After {@link #flush(float[][], int)} it is back in its
 * initial state and can resample a new stream.
 *
 * @implNote This implementation follows the textbook design also used by libsoxr and speexdsp:
 * each phase is a Kaiser-windowed sinc sampled at the phase's fractional offset and normalized to
 * unit DC gain, which removes the gain ripple between phases. The sinc's -6 dB point sits at
 * {@code 0.97} of the Nyquist frequency of the lower of the two rates; the window length is the
 * quality's tap count at the lower rate (see {@link Quality}), rounded up to a multiple of eight.
 *
 * <p>Every output is a dot product with one exactly defined arithmetic: the taps are split into
 * eight interleaved fused multiply-add chains by their index modulo eight, and the chain results
 * are summed pairwise in a fixed order. One eight-lane vector accumulator computes exactly that, so
 * the scalar and Vector API kernels are bit-identical. When {@code L} exceeds 1024, or the exact
 * table would exceed 2^22 coefficients, the table instead holds at most 1024 evenly spaced phases
 * and each output interpolates linearly between the two neighboring phases, which bounds memory
 * for rates such as 35467 Hz at the cost of a second dot product per output sample. The
 * variable-ratio mode always interpolates between such phases.
 */
public final class Resampler {
    /**
     * The largest accepted sampling rate, in hertz.
     */
    private static final int MAX_RATE = 1 << 24;

    /**
     * The largest accepted ratio between the two rates, in either direction.
     */
    private static final int MAX_RATIO = 256;

    /**
     * The largest accepted channel count.
     */
    private static final int MAX_CHANNELS = 1024;

    /**
     * The largest number of exact phases before the table switches to interpolated phases.
     */
    private static final int MAX_EXACT_PHASES = 1024;

    /**
     * The largest number of coefficients a phase table may hold.
     */
    private static final int MAX_TABLE_FLOATS = 1 << 22;

    /**
     * The fewest interpolated phases a table may hold.
     */
    private static final int MIN_INTERPOLATED_PHASES = 64;

    /**
     * The cutoff of the sinc as a fraction of the lower rate's Nyquist frequency.
     */
    private static final double CUTOFF = 0.97;

    /**
     * The largest number of input frames appended to the history before output is computed.
     */
    private static final int SLICE = 4096;

    /**
     * The smallest accepted ratio between a variable ratio and the nominal one.
     */
    private static final double MIN_RATIO_FACTOR = 0.5;

    /**
     * The largest accepted ratio between a variable ratio and the nominal one.
     */
    private static final double MAX_RATIO_FACTOR = 2;

    /**
     * The mask of the 32 fractional bits of a fixed-point position.
     */
    private static final long FRACTION_MASK = 0xFFFF_FFFFL;

    /**
     * Selects the trade-off between filter length, stopband attenuation and speed.
     *
     * <p>The tap count is the filter length measured in samples of the lower of the two rates;
     * when downsampling, the filter spans {@code taps * inputRate / outputRate} input samples. The
     * Kaiser window parameter sets the stopband attenuation {@code A}, and together they set the
     * width of the transition band centered on the cutoff, approximately
     * {@code (A - 8) / (2.285 * 2 * pi * (taps - 1))} of the lower rate.
     */
    public enum Quality {
        /**
         * 16 taps, Kaiser beta 7.86 (about 80 dB stopband); the transition band spans about 33% of
         * the lower rate, so the passband is flat to about 32% of the lower rate and the stopband
         * starts at about 65% of it.
         */
        FAST(16, 7.857),

        /**
         * 32 taps, Kaiser beta 10.06 (about 100 dB stopband); the transition band spans about 21%
         * of the lower rate, so the passband is flat to about 38% of the lower rate and the
         * stopband starts at about 59% of it. Comparable to the default of FFmpeg's swresample
         * ({@code filter_size=32}, {@code cutoff=0.97}).
         */
        STANDARD(32, 10.056),

        /**
         * 64 taps, Kaiser beta 12.27 (about 120 dB stopband); the transition band spans about 12%
         * of the lower rate, so the passband is flat to about 42% of the lower rate and the
         * stopband starts at about 55% of it.
         */
        HIGH(64, 12.265);

        /**
         * The filter length in samples of the lower rate.
         */
        private final int taps;

        /**
         * The Kaiser window shape parameter.
         */
        private final double beta;

        /**
         * Constructs a constant.
         *
         * @param taps the filter length at the lower rate
         * @param beta the Kaiser window parameter
         */
        Quality(int taps, double beta) {
            this.taps = taps;
            this.beta = beta;
        }

        /**
         * Returns the filter length in samples of the lower rate.
         *
         * @return the tap count
         */
        public int taps() {
            return taps;
        }

        /**
         * Returns the Kaiser window shape parameter.
         *
         * @return beta
         */
        public double beta() {
            return beta;
        }
    }

    /**
     * The input sampling rate in hertz.
     */
    private final int inputRate;

    /**
     * The output sampling rate in hertz.
     */
    private final int outputRate;

    /**
     * The number of channels.
     */
    private final int channels;

    /**
     * Whether the rates are equal, so input is copied to output unchanged.
     */
    private final boolean passthrough;

    /**
     * The reduced numerator of the rate ratio: the number of outputs per cycle.
     */
    private final int up;

    /**
     * The reduced denominator of the rate ratio: the input advance per cycle.
     */
    private final int down;

    /**
     * The number of filter taps per phase; a positive multiple of eight.
     */
    private final int taps;

    /**
     * Half of {@link #taps}: the look-ahead in input samples.
     */
    private final int half;

    /**
     * The coefficients, {@link #taps} per phase, phase-major.
     */
    private final float[] table;

    /**
     * The kernel computing the outputs.
     */
    private final FirKernel kernel;

    /**
     * The differences between each interpolated phase and the next, laid out like
     * {@link #table}, or {@code null} when the table holds exact phases.
     */
    private final float[] slopes;

    /**
     * The number of phases in {@link #table} when interpolating, or {@code 0} for an exact table.
     */
    private final int interpolatedPhases;

    /**
     * The input history of each channel: samples from absolute index {@link #historyStart}.
     */
    private final float[][] history;

    /**
     * The absolute input index of {@code history[c][0]}; negative while the leading zeros are
     * still held.
     */
    private long historyStart;

    /**
     * The number of valid samples in each history plane.
     */
    private int historyLength;

    /**
     * The number of input frames received since the start of the stream.
     */
    private long inputFrames;

    /**
     * The number of output frames produced since the start of the stream.
     */
    private long produced;

    /**
     * The sinc cutoff in cycles per input sample.
     */
    private final double cutoff;

    /**
     * The Kaiser window parameter.
     */
    private final double beta;

    /**
     * Whether the output spacing follows {@link #ratio} instead of the exact nominal ratio.
     */
    private boolean variable;

    /**
     * The output frames per input frame.
     */
    private double ratio;

    /**
     * The whole part of the input position of the next output frame, in variable mode.
     */
    private long positionWhole;

    /**
     * The fractional part of the input position of the next output frame, in units of {@code 2^-32}.
     */
    private long positionFraction;

    /**
     * The input advance per output frame in variable mode, in units of {@code 2^-32}.
     */
    private long step;

    /**
     * The interpolated phases of the variable mode, built on first use.
     */
    private float[] variableTable;

    /**
     * The differences between consecutive phases of {@link #variableTable}.
     */
    private float[] variableSlopes;

    /**
     * The number of phases of {@link #variableTable}.
     */
    private int variablePhases;

    /**
     * Creates a resampler.
     *
     * @param inputRate  the input sampling rate in hertz; between 1 and 16777216
     * @param outputRate the output sampling rate in hertz; between 1 and 16777216, and within a
     *                   factor of 256 of {@code inputRate}
     * @param channels   the number of channels; between 1 and 1024
     * @param quality    the filter quality
     * @throws NullPointerException     if {@code quality} is {@code null}
     * @throws IllegalArgumentException if a rate, the ratio, or the channel count is out of range
     */
    public Resampler(int inputRate, int outputRate, int channels, Quality quality) {
        this(inputRate, outputRate, channels, quality, FirKernel.SELECTED);
    }

    /**
     * Creates a resampler that computes with the given kernel.
     *
     * @param inputRate  the input sampling rate in hertz
     * @param outputRate the output sampling rate in hertz
     * @param channels   the number of channels
     * @param quality    the filter quality
     * @param kernel     the kernel computing the outputs
     * @throws NullPointerException     if {@code quality} or {@code kernel} is {@code null}
     * @throws IllegalArgumentException if a rate, the ratio, or the channel count is out of range
     */
    Resampler(int inputRate, int outputRate, int channels, Quality quality, FirKernel kernel) {
        Objects.requireNonNull(quality, "quality");
        this.kernel = Objects.requireNonNull(kernel, "kernel");
        if (inputRate < 1 || inputRate > MAX_RATE || outputRate < 1 || outputRate > MAX_RATE) {
            throw new IllegalArgumentException("sampling rates must be between 1 and " + MAX_RATE + " Hz, got "
                    + inputRate + " -> " + outputRate);
        }
        if ((long) inputRate > (long) outputRate * MAX_RATIO || (long) outputRate > (long) inputRate * MAX_RATIO) {
            throw new IllegalArgumentException("resampling ratio " + inputRate + " -> " + outputRate
                    + " exceeds the factor " + MAX_RATIO);
        }
        if (channels < 1 || channels > MAX_CHANNELS) {
            throw new IllegalArgumentException("channel count must be between 1 and " + MAX_CHANNELS + ", got " + channels);
        }
        this.inputRate = inputRate;
        this.outputRate = outputRate;
        this.channels = channels;
        this.passthrough = inputRate == outputRate;
        var gcd = gcd(inputRate, outputRate);
        this.up = outputRate / gcd;
        this.down = inputRate / gcd;
        var span = quality.taps() * Math.max(1.0, (double) inputRate / outputRate);
        this.taps = (int) Math.ceil(span / 8) * 8;
        this.half = taps / 2;
        this.cutoff = CUTOFF * 0.5 * Math.min(inputRate, outputRate) / inputRate;
        this.beta = quality.beta();
        if (up <= MAX_EXACT_PHASES && (long) up * taps <= MAX_TABLE_FLOATS) {
            this.table = design(up, up, taps, cutoff, quality.beta());
            this.slopes = null;
            this.interpolatedPhases = 0;
        } else {
            var phases = (int) Math.max(MIN_INTERPOLATED_PHASES,
                    Math.min(MAX_EXACT_PHASES, MAX_TABLE_FLOATS / (2L * taps) - 1));
            var full = design(phases + 1, phases, taps, cutoff, quality.beta());
            this.table = Arrays.copyOf(full, phases * taps);
            this.slopes = new float[phases * taps];
            for (var i = 0; i < phases * taps; i++) {
                slopes[i] = full[i + taps] - full[i];
            }
            this.interpolatedPhases = phases;
        }
        this.history = new float[channels][taps + SLICE];
        reset();
    }

    /**
     * Builds the coefficient table of a set of evenly spaced phases.
     *
     * @param count      the number of phases to build
     * @param resolution the number of phases per input sample; phase {@code p} sits at the
     *                   fractional offset {@code p / resolution}
     * @param taps       the number of taps per phase
     * @param cutoff     the sinc cutoff in cycles per input sample
     * @param beta       the Kaiser window parameter
     * @return the coefficients, phase-major, each phase normalized to unit sum
     */
    private static float[] design(int count, int resolution, int taps, double cutoff, double beta) {
        var result = new float[count * taps];
        var half = taps / 2;
        var norm = besselI0(beta);
        var row = new double[taps];
        for (var p = 0; p < count; p++) {
            var fraction = (double) p / resolution;
            var sum = 0.0;
            for (var k = 0; k < taps; k++) {
                var t = k - half + 1 - fraction;
                var x = t / half;
                var window = Math.abs(x) >= 1 ? 0 : besselI0(beta * Math.sqrt(1 - x * x)) / norm;
                var value = window * sinc(2 * cutoff * t);
                row[k] = value;
                sum += value;
            }
            for (var k = 0; k < taps; k++) {
                result[p * taps + k] = (float) (row[k] / sum);
            }
        }
        return result;
    }

    /**
     * Returns the normalized sinc function {@code sin(pi x) / (pi x)}.
     *
     * @param x the argument
     * @return the value, {@code 1} at {@code 0}
     */
    private static double sinc(double x) {
        if (Math.abs(x) < 1e-12) {
            return 1;
        }
        var pix = Math.PI * x;
        return StrictMath.sin(pix) / pix;
    }

    /**
     * Returns the zeroth-order modified Bessel function of the first kind.
     *
     * @param x the argument
     * @return {@code I0(x)}, evaluated by its power series to full double precision
     */
    private static double besselI0(double x) {
        var sum = 1.0;
        var term = 1.0;
        var quarter = x * x / 4;
        for (var k = 1; k < 500; k++) {
            term *= quarter / ((double) k * k);
            sum += term;
            if (term < sum * 1e-17) {
                break;
            }
        }
        return sum;
    }

    /**
     * Returns the greatest common divisor of two positive integers.
     *
     * @param a the first value
     * @param b the second value
     * @return the divisor
     */
    private static int gcd(int a, int b) {
        while (b != 0) {
            var t = a % b;
            a = b;
            b = t;
        }
        return a;
    }

    /**
     * Returns the input sampling rate.
     *
     * @return the rate in hertz
     */
    public int inputRate() {
        return inputRate;
    }

    /**
     * Returns the output sampling rate.
     *
     * @return the rate in hertz
     */
    public int outputRate() {
        return outputRate;
    }

    /**
     * Returns the number of channels.
     *
     * @return the channel count
     */
    public int channels() {
        return channels;
    }

    /**
     * Returns the ratio of output frames to input frames: {@code outputRate / inputRate} until
     * {@link #setRatio(double)} changes it.
     *
     * @return the ratio
     */
    public double ratio() {
        return variable ? ratio : (double) outputRate / inputRate;
    }

    /**
     * Sets the ratio of output frames to input frames for every frame not yet written.
     *
     * <p>A ratio equal to the nominal one leaves a resampler that was never given another ratio in
     * its exact mode, and is otherwise kept in variable mode. {@link #flush(float[][], int)} returns
     * the resampler to the nominal ratio.
     *
     * @param ratio the output frames per input frame, within a factor of two of
     * {@code outputRate / inputRate}
     * @throws IllegalArgumentException if {@code ratio} is not finite or out of range
     */
    public void setRatio(double ratio) {
        var nominal = (double) outputRate / inputRate;
        if (!(ratio >= nominal * MIN_RATIO_FACTOR && ratio <= nominal * MAX_RATIO_FACTOR)) {
            throw new IllegalArgumentException("ratio " + ratio + " is not within a factor of two of " + nominal);
        }
        if (!variable) {
            if (ratio == nominal) {
                return;
            }
            enterVariableMode();
        }
        this.ratio = ratio;
        this.step = Math.round(0x1p32 / ratio);
    }

    /**
     * Switches to variable mode at the current output position, building the interpolated phases.
     */
    private void enterVariableMode() {
        if (slopes != null) {
            variableTable = table;
            variableSlopes = slopes;
            variablePhases = interpolatedPhases;
        } else if (variableTable == null) {
            var phases = (int) Math.max(MIN_INTERPOLATED_PHASES,
                    Math.min(MAX_EXACT_PHASES, MAX_TABLE_FLOATS / (2L * taps) - 1));
            var full = design(phases + 1, phases, taps, cutoff, beta);
            variableTable = Arrays.copyOf(full, phases * taps);
            variableSlopes = new float[phases * taps];
            for (var i = 0; i < phases * taps; i++) {
                variableSlopes[i] = full[i + taps] - full[i];
            }
            variablePhases = phases;
        }
        if (passthrough) {
            positionWhole = produced;
            positionFraction = 0;
        } else {
            positionWhole = floorMulDiv(produced, down, up);
            var remainder = produced % up * down % up;
            positionFraction = (remainder << 32) / up;
        }
        variable = true;
    }

    /**
     * Returns the filter's look-ahead: how many output frames {@link #process(float[][], int,
     * float[][], int)} withholds until later input or {@link #flush(float[][], int)} releases
     * them.
     *
     * <p>This is a latency, not a shift: the output is never delayed relative to the input. In
     * variable mode it follows the current ratio.
     *
     * @return the look-ahead in output frames
     */
    public int delay() {
        if (variable) {
            return (int) Math.ceil(half * ratio);
        }
        return passthrough ? 0 : (int) ceilMulDiv(half, up, down);
    }

    /**
     * Returns the largest number of frames the next call can write: {@link #process(float[][],
     * int, float[][], int)} with {@code inputFrames} frames, or {@link #flush(float[][], int)}
     * after it.
     *
     * <p>{@code maxOutputFrames(0)} bounds the output of {@link #flush(float[][], int)} called
     * right away.
     *
     * @param inputFrames the number of input frames about to be processed; non-negative
     * @return the bound, in frames
     * @throws IllegalArgumentException if {@code inputFrames} is negative or the bound exceeds
     *                                  {@link Integer#MAX_VALUE}
     */
    public int maxOutputFrames(int inputFrames) {
        if (inputFrames < 0) {
            throw new IllegalArgumentException("negative frame count " + inputFrames);
        }
        if (variable) {
            var frames = variableTotal(this.inputFrames + inputFrames);
            if (frames > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("output of " + frames + " frames exceeds the largest array");
            }
            return (int) frames;
        }
        if (passthrough) {
            return inputFrames;
        }
        var bound = roundMulDiv(this.inputFrames + inputFrames, up, down) - produced;
        if (bound > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("output of " + bound + " frames exceeds the largest array");
        }
        return (int) Math.max(0, bound);
    }

    /**
     * Resamples a block of input and writes every output frame whose look-ahead is available.
     *
     * @param input        the input planes, one per channel, each holding at least
     *                     {@code inputFrames} samples
     * @param inputFrames  the number of input frames; non-negative
     * @param output       the output planes, one per channel
     * @param outputOffset the index in each output plane of the first frame written
     * @return the number of frames written to each output plane
     * @throws NullPointerException     if an array is {@code null}
     * @throws IllegalArgumentException if the plane counts differ from {@link #channels()}, an
     *                                  input plane is shorter than {@code inputFrames}, or an
     *                                  output plane cannot hold the frames produced
     */
    public int process(float[][] input, int inputFrames, float[][] output, int outputOffset) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        if (input.length != channels || output.length != channels) {
            throw new IllegalArgumentException("expected " + channels + " planes, got " + input.length + " input and "
                    + output.length + " output planes");
        }
        if (inputFrames < 0 || outputOffset < 0) {
            throw new IllegalArgumentException("negative frame count or offset");
        }
        for (var c = 0; c < channels; c++) {
            if (Objects.requireNonNull(input[c], "input plane").length < inputFrames) {
                throw new IllegalArgumentException("input plane " + c + " holds fewer than " + inputFrames + " frames");
            }
        }
        if (passthrough && !variable) {
            checkCapacity(output, outputOffset, inputFrames);
            for (var c = 0; c < channels; c++) {
                System.arraycopy(input[c], 0, output[c], outputOffset, inputFrames);
            }
            rememberTail(input, inputFrames);
            this.inputFrames += inputFrames;
            produced += inputFrames;
            return inputFrames;
        }
        checkCapacity(output, outputOffset, available(this.inputFrames + inputFrames));
        var written = 0;
        var consumed = 0;
        while (consumed < inputFrames) {
            var count = Math.min(SLICE, inputFrames - consumed);
            appendHistory(input, consumed, count);
            this.inputFrames += count;
            consumed += count;
            written += produce(output, outputOffset + written, available(this.inputFrames));
        }
        return written;
    }

    /**
     * Ends the stream: writes every withheld frame, padding the input with zeros, so the stream
     * totals exactly {@code round(totalInputFrames * outputRate / inputRate)} frames, and returns
     * the resampler to its initial state.
     *
     * @param output       the output planes, one per channel
     * @param outputOffset the index in each output plane of the first frame written
     * @return the number of frames written to each output plane, at most
     *         {@code maxOutputFrames(0)}
     * @throws NullPointerException     if an array is {@code null}
     * @throws IllegalArgumentException if the plane count differs from {@link #channels()} or an
     *                                  output plane cannot hold the frames produced
     */
    public int flush(float[][] output, int outputOffset) {
        Objects.requireNonNull(output, "output");
        if (output.length != channels) {
            throw new IllegalArgumentException("expected " + channels + " output planes, got " + output.length);
        }
        if (outputOffset < 0) {
            throw new IllegalArgumentException("negative offset " + outputOffset);
        }
        if (passthrough && !variable) {
            reset();
            return 0;
        }
        var total = variable ? produced + variableTotal(inputFrames) : roundMulDiv(inputFrames, up, down);
        checkCapacity(output, outputOffset, total - produced);
        var written = 0;
        var silence = new float[Math.min(SLICE, taps)];
        var zeros = new float[channels][];
        Arrays.fill(zeros, silence);
        var end = inputFrames;
        while (produced < total) {
            appendHistory(zeros, 0, silence.length);
            end += silence.length;
            written += produce(output, outputOffset + written, Math.min(total - produced, available(end)));
        }
        reset();
        return written;
    }

    /**
     * Returns the resampler to the start of a new stream.
     */
    private void reset() {
        for (var plane : history) {
            Arrays.fill(plane, 0, half, 0.0f);
        }
        historyStart = -half;
        historyLength = half;
        inputFrames = 0;
        produced = 0;
        variable = false;
    }

    /**
     * Keeps the last input frames of a pass-through call in the history, so that a later switch to
     * variable mode filters across the call boundary.
     *
     * @param input the planes just copied
     * @param count the number of frames copied
     */
    private void rememberTail(float[][] input, int count) {
        if (count >= half) {
            for (var c = 0; c < channels; c++) {
                System.arraycopy(input[c], count - half, history[c], 0, half);
            }
        } else {
            var keep = half - count;
            for (var c = 0; c < channels; c++) {
                System.arraycopy(history[c], historyLength - keep, history[c], 0, keep);
                System.arraycopy(input[c], 0, history[c], keep, count);
            }
        }
        historyStart = inputFrames + count - half;
        historyLength = half;
    }

    /**
     * Returns the number of output frames, from the next one on, whose variable-mode position lies
     * at least half a spacing before the end of an input of {@code end} frames; for inputs more than
     * 2^30 frames past the position, an upper bound of it.
     *
     * @param end the number of input frames of the stream
     * @return the frame count, non-negative
     */
    private long variableTotal(long end) {
        var whole = end - positionWhole;
        if (whole <= 0) {
            return 0;
        }
        if (whole >= 1L << 30) {
            return Math.max(0, (long) Math.floor((end - positionWhole - positionFraction * 0x1p-32) * ratio - 0.5) + 2);
        }
        var distance = (whole << 32) - positionFraction - step / 2;
        return distance < 0 ? 0 : distance / step + 1;
    }

    /**
     * Returns the number of not yet produced output frames that can be computed once the input
     * extends to {@code end}.
     *
     * @param end the number of input frames available, including padding
     * @return the number of producible frames, non-negative
     */
    private long available(long end) {
        var last = end - 1 - half;
        if (last < 0) {
            return 0;
        }
        if (variable) {
            var whole = last + 1 - positionWhole;
            if (whole <= 0) {
                return 0;
            }
            var distance = (whole << 32) - positionFraction;
            return (distance + step - 1) / step;
        }
        return Math.max(0, ceilMulDiv(last + 1, up, down) - produced);
    }

    /**
     * Checks that every output plane can hold {@code frames} more frames.
     *
     * @param output the output planes
     * @param offset the index of the first frame written
     * @param frames the number of frames to be written
     * @throws IllegalArgumentException if a plane is too short
     */
    private void checkCapacity(float[][] output, int offset, long frames) {
        for (var c = 0; c < channels; c++) {
            if (Objects.requireNonNull(output[c], "output plane").length - (long) offset < frames) {
                throw new IllegalArgumentException("output plane " + c + " cannot hold " + frames + " frames at offset " + offset);
            }
        }
    }

    /**
     * Returns the input position of the first tap of the next output to produce.
     *
     * @return the absolute input index
     */
    private long nextFirstTap() {
        return (variable ? positionWhole : floorMulDiv(produced, down, up)) - half + 1;
    }

    /**
     * Appends frames to the history, first discarding the samples no future output needs.
     *
     * @param input  the planes to copy from
     * @param offset the index of the first frame
     * @param count  the number of frames, at most {@link #SLICE}
     */
    private void appendHistory(float[][] input, int offset, int count) {
        var discard = (int) Math.min(historyLength, Math.max(0, nextFirstTap() - historyStart));
        if (discard > 0) {
            for (var plane : history) {
                System.arraycopy(plane, discard, plane, 0, historyLength - discard);
            }
            historyStart += discard;
            historyLength -= discard;
        }
        for (var c = 0; c < channels; c++) {
            System.arraycopy(input[c], offset, history[c], historyLength, count);
        }
        historyLength += count;
    }

    /**
     * Computes the next output frames from the history.
     *
     * @param output the output planes
     * @param offset the index of the first frame written
     * @param count  the number of frames to compute; their inputs must be in the history
     * @return {@code count}
     */
    private int produce(float[][] output, int offset, long count) {
        var frames = (int) count;
        if (frames <= 0) {
            return 0;
        }
        if (variable) {
            var start = (int) (positionWhole - historyStart) - half + 1;
            var stepWhole = step >>> 32;
            var stepFraction = step & FRACTION_MASK;
            for (var c = 0; c < channels; c++) {
                kernel.runVariable(history[c], start, positionFraction, (int) stepWhole, stepFraction,
                        variablePhases, variableTable, variableSlopes, taps, output[c], offset, frames);
            }
            var fraction = positionFraction + frames * stepFraction;
            positionWhole += frames * stepWhole + (fraction >>> 32);
            positionFraction = fraction & FRACTION_MASK;
            produced += frames;
            return frames;
        }
        var step = down / up;
        var remainder = down % up;
        var base = (int) (floorMulDiv(produced, down, up) - historyStart) - half + 1;
        var phase = (int) (produced % up * down % up);
        var c = 0;
        if (slopes == null) {
            for (; c + 1 < channels; c += 2) {
                kernel.runPair(history[c], history[c + 1], base, phase, step, remainder, up, table, taps, output[c],
                        output[c + 1], offset, frames);
            }
        }
        for (; c < channels; c++) {
            if (slopes == null) {
                kernel.run(history[c], base, phase, step, remainder, up, table, taps, output[c], offset, frames);
            } else {
                kernel.runInterpolated(history[c], base, phase, step, remainder, up, interpolatedPhases, table, slopes,
                        taps, output[c], offset, frames);
            }
        }
        produced += frames;
        return frames;
    }

    /**
     * Returns {@code floor(a * multiplier / divisor)} without overflow for the value ranges this
     * class uses.
     *
     * @param a          a non-negative value
     * @param multiplier a positive value below {@code 2^24}
     * @param divisor    a positive value below {@code 2^24}
     * @return the rounded-down quotient
     */
    private static long floorMulDiv(long a, int multiplier, int divisor) {
        var quotient = a / divisor;
        var remainder = a % divisor;
        return quotient * multiplier + remainder * multiplier / divisor;
    }

    /**
     * Returns {@code ceil(a * multiplier / divisor)} without overflow for the value ranges this
     * class uses.
     *
     * @param a          a non-negative value
     * @param multiplier a positive value below {@code 2^24}
     * @param divisor    a positive value below {@code 2^24}
     * @return the rounded-up quotient
     */
    private static long ceilMulDiv(long a, int multiplier, int divisor) {
        var quotient = a / divisor;
        var remainder = a % divisor;
        return quotient * multiplier + (remainder * multiplier + divisor - 1) / divisor;
    }

    /**
     * Returns {@code a * multiplier / divisor} rounded to nearest with halves rounded up, without
     * overflow for the value ranges this class uses.
     *
     * @param a          a non-negative value
     * @param multiplier a positive value below {@code 2^24}
     * @param divisor    a positive value below {@code 2^24}
     * @return the rounded quotient
     */
    private static long roundMulDiv(long a, int multiplier, int divisor) {
        var quotient = a / divisor;
        var remainder = a % divisor;
        return quotient * multiplier + (2 * remainder * multiplier + divisor) / (2L * divisor);
    }
}
