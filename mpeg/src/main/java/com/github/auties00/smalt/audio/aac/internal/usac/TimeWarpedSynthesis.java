/* Ports the ISO/IEC 23003-3:2019 reference software (usac_tw_tools.c; the time-warped branch of fd2buffer in
   usac_imdct.c; calc_tw_window and CalculateTwKBDTransition of usac_calcWindow.c; the tw_calc_tw and tw_adjust_past
   calls of decode_chan_ele.c). Copyright Fraunhofer IIS, VoiceAge Corp. and ISO/IEC 2008; ISO/IEC reference
   software licence (use in products conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Runs the time-warped frequency domain synthesis of USAC ({@code tw_mdct}, ISO/IEC 23003-3 subclause 7.17): the
 * warp contour of each frame from its {@code tw_data()}, the windowing of the inverse MDCT output in the warped
 * time domain, its resampling to the linear time domain through a Kaiser windowed sinc interpolator, and the
 * overlap-add.
 *
 * <p>The synthesis keeps half a frame more of the signal than the plain filterbank: its output lags the output of
 * the plain filterbank by half a frame. The overlap of a channel holds two frames of samples. Instances hold
 * scratch memory and are not thread-safe.
 *
 * @implNote This implementation follows the float arithmetic of the reference software statement by statement,
 * including its double precision comparisons and roundings, so that its output matches the reference to the
 * rounding of the window and interpolator tables.
 */
final class TimeWarpedSynthesis {
    /**
     * The number of nodes of the warp contour of a frame ({@code NUM_TW_NODES}).
     */
    static final int NODES = 16;

    /**
     * The interpolator's half length in samples ({@code TW_IPLEN2S}), the border kept around a warped frame.
     */
    private static final int BORDER = 12;

    /**
     * The oversampling of the transition windows ({@code TW_OS_FACTOR_WIN}).
     */
    private static final int WINDOW_OVERSAMPLING = 16;

    /**
     * The oversampling of the interpolator ({@code OS_FACTOR}).
     */
    private static final int OVERSAMPLING = 128;

    /**
     * The half length of the interpolator in oversampled taps ({@code IPLEN2}).
     */
    private static final int TAPS = BORDER * OVERSAMPLING + 1;

    /**
     * The warp ratio of each {@code tw_ratio} index ({@code _tw_ratio_quant_table}).
     */
    private static final float[] RATIOS = {0.982857168f, 0.988571405f, 0.994285703f, 1.0f, 1.0057143f, 1.01142859f,
            1.01714289f, 1.02285719f};

    private static final float BEFORE_CONTOUR = (float) -1.0e5;
    private static final float AFTER_CONTOUR = (float) -2.0e5;

    /**
     * The interpolator ({@code _resamp_filter}), one tap per oversampled position from the center.
     */
    private static final float[] INTERPOLATOR = interpolator();

    /**
     * Holds the time warping state one channel carries from frame to frame.
     */
    static final class State {
        final float[] contour;
        final float[] pitchSum = new float[2];
        final float[] pastPositions;
        final float[] pastWarped;
        final float[] pastTransitions = new float[2];
        final int[] pastBounds = new int[2];
        final float[] positions;
        final float[] transitions = new float[2];
        final int[] bounds = new int[2];

        /**
         * Constructs the state of a channel before its first frame.
         *
         * @param length the core frame length
         */
        State(int length) {
            this.contour = new float[2 * length];
            Arrays.fill(contour, 1.0f);
            pitchSum[0] = length;
            pitchSum[1] = length;
            this.pastPositions = new float[3 * length];
            this.pastWarped = new float[3 * length];
            this.positions = new float[3 * length];
        }
    }

    private final int length;
    private final float[] sineTransition;
    private final float[] kbdTransition;
    private final float[] nodeContour;
    private final float[] warpContour;
    private final float[] timeContour;
    private final float[] warped;
    private final float[] warpedPast;
    private final float[] resampled;
    private final float[] resampledPast;
    private final float[] buffer;
    private final float[] shortScratch;

    /**
     * Constructs the synthesis of a frame length.
     *
     * @param length the core frame length, 768 or 1024
     */
    TimeWarpedSynthesis(int length) {
        this.length = length;
        this.sineTransition = sineTransition(length * WINDOW_OVERSAMPLING);
        this.kbdTransition = kbdTransition(length * WINDOW_OVERSAMPLING);
        this.nodeContour = new float[length];
        this.warpContour = new float[3 * length + 1];
        this.timeContour = new float[3 * length + 1];
        this.warped = new float[3 * length];
        this.warpedPast = new float[3 * length];
        this.resampled = new float[3 * length];
        this.resampledPast = new float[3 * length];
        this.buffer = new float[3 * length];
        this.shortScratch = new float[2 * length / 8];
    }

    /**
     * Computes the warp of the current frame ({@code tw_calc_tw}) and adjusts the right transition of the previous
     * frame to the current window sequence ({@code tw_adjust_past}), for a frame that follows a frequency domain
     * frame.
     *
     * @param state the channel's state
     * @param present {@code tw_data_present}
     * @param ratios the {@code tw_ratio} indices, read when {@code present}
     * @param sequence the window sequence of the frame
     * @param lastSequence the window sequence of the previous frame
     */
    void prepare(State state, boolean present, int[] ratios, int sequence, int lastSequence) {
        Arrays.fill(state.positions, 0f);
        decodeRatios(present, ratios);
        toSamplePositions(state, sequence);
        adjustPast(lastSequence, state.pastBounds, state.pastTransitions);
    }

    /**
     * Synthesizes one frame from the inverse transform of its spectrum, after {@link #prepare}.
     *
     * @param state the channel's state
     * @param transform the inverse MDCT output: {@code 2 * length} samples of one long window, or the
     * {@code 2 * length / 8} samples of each short window one after the other
     * @param sequence the window sequence
     * @param lastSequence the window sequence of the previous frame
     * @param shape the window shape of the frame
     * @param previousShape the window shape of the previous frame
     * @param overlap the overlap of the channel, {@code 2 * length} samples; updated
     * @param out receives the frame's {@code length} samples
     * @param outOffset the index of the first output sample
     */
    void synthesize(State state, float[] transform, int sequence, int lastSequence, int shape, int previousShape,
                    float[] overlap, float[] out, int outOffset) {
        var n = length;
        var extra = n / 8;
        var window = shape == 1 ? kbdTransition : sineTransition;
        var previousWindow = previousShape == 1 ? kbdTransition : sineTransition;
        var o = buffer;
        System.arraycopy(overlap, 0, o, 0, 2 * n);
        Arrays.fill(o, 2 * n, 3 * n, 0f);
        Arrays.fill(warped, 0f);
        System.arraycopy(state.pastWarped, 0, warpedPast, 0, 3 * n);
        if (sequence != FdChannel.EIGHT_SHORT) {
            windowLong(transform, warped, BORDER, state.bounds[0], state.bounds[1], n, state.transitions[0],
                    previousWindow);
        } else {
            windowShort(transform, warped, BORDER, state.bounds[0], state.bounds[1], state.transitions[0],
                    state.transitions[1], previousWindow, window, n / 8);
        }
        resample(warped, 0, 2 * n + 2 * BORDER, n + extra, state.positions, resampled);
        if (lastSequence != FdChannel.EIGHT_SHORT) {
            windowPast(warpedPast, BORDER, state.pastBounds[1], n, state.pastTransitions[1], previousWindow);
        }
        Arrays.fill(resampledPast, 0f);
        resample(warpedPast, n + extra, 2 * n + 2 * BORDER, 3 * n, state.pastPositions, resampledPast);
        for (var i = 0; i < 2 * n - extra; i++) {
            o[extra + i] += resampledPast[n + extra + i];
        }
        for (var i = 0; i < n + extra; i++) {
            o[i] += resampled[i];
        }
        System.arraycopy(o, 0, out, outOffset, n);
        System.arraycopy(o, n, overlap, 0, 2 * n);
        System.arraycopy(warped, 0, state.pastWarped, 0, 2 * n + 2 * BORDER);
        System.arraycopy(state.positions, 0, state.pastPositions, 0, 3 * n);
        state.pastTransitions[0] = state.transitions[0];
        state.pastTransitions[1] = state.transitions[1];
        state.pastBounds[0] = state.bounds[0];
        state.pastBounds[1] = state.bounds[1];
    }

    /**
     * Interpolates the warp contour of the frame from its ratios ({@code w_ratio_dec}) into {@link #nodeContour}.
     */
    private void decodeRatios(boolean present, int[] ratios) {
        var distance = length / NODES;
        var previous = 1.0f;
        for (var i = 0; i < NODES; i++) {
            var node = present ? previous * RATIOS[ratios[i]] : 1.0f;
            var step = (node - previous) / (float) distance;
            for (var j = 0; j < distance; j++) {
                nodeContour[i * distance + j] = previous + (float) (j + 1) * step;
            }
            previous = node;
        }
    }

    /**
     * Turns the warp contours of the previous, current and next frames into the sample positions, the transition
     * lengths and the window bounds of the frame ({@code tw_data_to_sp}).
     */
    private void toSamplePositions(State state, int sequence) {
        var n = length;
        var w = warpContour;
        System.arraycopy(state.contour, 0, w, 0, 2 * n);
        var normalization = 1.0f / w[2 * n - 1];
        for (var i = 0; i < 2 * n; i++) {
            w[i] = w[i] * normalization;
        }
        state.pitchSum[0] *= normalization;
        state.pitchSum[1] *= normalization;
        var nextPitchSum = 0.0f;
        for (var i = 0; i < n; i++) {
            nextPitchSum += nodeContour[i];
        }
        System.arraycopy(nodeContour, 0, w, 2 * n, n);
        System.arraycopy(w, n, state.contour, 0, 2 * n);
        var resolution = n / state.pitchSum[1];
        var t = timeContour;
        t[0] = -state.pitchSum[0] * resolution;
        for (var i = 0; i < 3 * n; i++) {
            t[i + 1] = t[i] + w[i] * resolution;
        }
        var start = (float) (n - 3 * n / 2 - BORDER) + .5f;
        inverseWarp(t, start, 2 * n + 2 * BORDER, state.positions);
        var lastPitchSum = state.pitchSum[0];
        var currentPitchSum = state.pitchSum[1];
        var left = (float) (n / 2) * (lastPitchSum > currentPitchSum ? 1.0f : lastPitchSum / currentPitchSum);
        var right = (float) (n / 2) * (nextPitchSum > currentPitchSum ? 1.0f : nextPitchSum / currentPitchSum);
        switch (sequence) {
            case FdChannel.LONG_STOP, FdChannel.STOP_START -> left /= 8.0f;
            case FdChannel.EIGHT_SHORT -> {
                right /= 8.0f;
                left /= 8.0f;
            }
            default -> {
            }
        }
        state.bounds[0] = (int) Math.ceil(0.5 * (double) n - 0.5 - (double) left);
        state.bounds[1] = (int) Math.floor(1.5 * (double) n - 0.5 + (double) right);
        state.transitions[0] = left;
        state.transitions[1] = right;
        state.pitchSum[0] = currentPitchSum;
        state.pitchSum[1] = nextPitchSum;
    }

    /**
     * Returns the unwarped position of a warped time ({@code WarpTimeInv}).
     */
    private float inverseTime(float[] contour, float time) {
        if (time < contour[0]) {
            return BEFORE_CONTOUR;
        }
        if (time > contour[3 * length]) {
            return AFTER_CONTOUR;
        }
        var i = 0;
        while (time > contour[i + 1]) {
            i++;
        }
        var offset = time - contour[i];
        var width = contour[i + 1] - contour[i];
        offset /= width;
        return (float) i + offset;
    }

    /**
     * Computes the unwarped positions of consecutive warped samples ({@code WarpInvVec}).
     */
    private void inverseWarp(float[] contour, float start, int count, float[] positions) {
        var time = start;
        var j = 0;
        int i;
        while ((i = (int) Math.floor(inverseTime(contour, time - 0.5f))) == (int) BEFORE_CONTOUR) {
            if (inverseTime(contour, time) == AFTER_CONTOUR) {
                throw new MediaException.Malformed("USAC time warp contour has no inverse");
            }
            time += 1.0f;
            j++;
        }
        while (j < count && (double) time + .5 < contour[3 * length]) {
            while (time > contour[i + 1]) {
                i++;
            }
            positions[j] = (float) i + (time - contour[i]) / (contour[i + 1] - contour[i]);
            j++;
            time += 1.0f;
        }
    }

    /**
     * Adjusts the right transition of the previous frame to the current frame ({@code tw_adjust_past}), for a
     * frequency domain frame.
     */
    private void adjustPast(int lastSequence, int[] bounds, float[] transitions) {
        if (lastSequence == FdChannel.LONG_START || lastSequence == FdChannel.STOP_START) {
            transitions[1] /= 8.0f;
        }
        bounds[1] = (int) Math.floor(1.5 * (double) length - 0.5 + (double) transitions[1]);
    }

    /**
     * Windows a long window in the warped domain: its left transition and the zeros outside its bounds
     * ({@code tw_windowing_long}); the right transition waits for the next frame.
     */
    private static void windowLong(float[] in, float[] out, int offset, int start, int end, int n, float left,
                                   float[] leftWindow) {
        System.arraycopy(in, 0, out, offset, 2 * n);
        Arrays.fill(out, offset, offset + Math.max(0, start), 0f);
        for (var i = end + 1; i < 2 * n; i++) {
            out[offset + i] = 0f;
        }
        var scale = 0.5f * (float) n / left * (float) WINDOW_OVERSAMPLING;
        var position = (left + (float) (start - n / 2) + 0.5f) * scale;
        for (var i = n - 1 - start; i >= start; i--) {
            out[offset + i] = out[offset + i] * leftWindow[(int) Math.floor(position)];
            position += scale;
        }
    }

    /**
     * Applies the right transition of the previous frame in the warped domain, in place
     * ({@code tw_windowing_past}).
     */
    private static void windowPast(float[] data, int offset, int end, int n, float right, float[] rightWindow) {
        var scale = 0.5f * (float) n / right * (float) WINDOW_OVERSAMPLING;
        var position = (1.5f * (float) n - (float) end - 0.5f + right) * scale;
        for (var i = 3 * n - 1 - end; i <= end; i++) {
            data[offset + i] = data[offset + i] * rightWindow[(int) Math.floor(position)];
            position += scale;
        }
        for (var i = end + 1; i < 2 * n; i++) {
            data[offset + i] = 0.0f;
        }
    }

    /**
     * Windows and overlaps the eight short windows of a frame in the warped domain ({@code tw_windowing_short}).
     */
    private void windowShort(float[] in, float[] out, int base, int start, int end, float left, float right,
                             float[] leftWindow, float[] rightWindow, int shortLength) {
        var n = shortLength * 8;
        var tmp = shortScratch;
        var offset = n - 4 * shortLength - shortLength / 2;
        var inOffset = 0;
        System.arraycopy(in, inOffset, tmp, 0, 2 * shortLength);
        var scale1 = 0.5f * (float) n / left * (float) WINDOW_OVERSAMPLING;
        var position1 = (left + (float) (start - n / 2) + 0.5f) * scale1;
        var scale2 = (float) (8 * WINDOW_OVERSAMPLING);
        var position2 = scale2 / 2;
        for (var i = 0; i < shortLength; i++) {
            out[base + i + offset] = tmp[i];
        }
        for (var i = 0; i < start; i++) {
            out[base + i] = 0.f;
        }
        for (var i = n - 1 - start; i >= start; i--) {
            out[base + i] *= leftWindow[(int) Math.floor(position1)];
            position1 += scale1;
        }
        for (var i = 0; i < shortLength; i++) {
            out[base + offset + i + shortLength] = tmp[i + shortLength] * rightWindow[(int) Math.floor(position2)];
            position2 += scale2;
        }
        offset += shortLength;
        inOffset += 2 * shortLength;
        for (var k = 1; k < 7; k++) {
            System.arraycopy(in, inOffset, tmp, 0, 2 * shortLength);
            scale1 = (float) (8 * WINDOW_OVERSAMPLING);
            position1 = scale1 / 2;
            position2 = (float) (WINDOW_OVERSAMPLING * n) - position1;
            for (var i = 0; i < shortLength; i++) {
                out[base + i + offset] += tmp[i] * rightWindow[(int) Math.floor(position2)];
                out[base + offset + shortLength + i] = tmp[shortLength + i] * rightWindow[(int) Math.floor(position1)];
                position1 += scale1;
                position2 -= scale1;
            }
            offset += shortLength;
            inOffset += 2 * shortLength;
        }
        System.arraycopy(in, inOffset, tmp, 0, 2 * shortLength);
        scale1 = (float) (8 * WINDOW_OVERSAMPLING);
        position1 = scale1 / 2;
        for (var i = shortLength - 1; i >= 0; i--) {
            out[base + i + offset] += tmp[i] * rightWindow[(int) Math.floor(position1)];
            position1 += scale1;
        }
        for (var i = 0; i < shortLength; i++) {
            out[base + offset + shortLength + i] = tmp[shortLength + i];
        }
        scale2 = 0.5f * (float) n / right * (float) WINDOW_OVERSAMPLING;
        position2 = (1.5f * (float) n - (float) end - 0.5f + right) * scale2;
        for (var i = 3 * n - 1 - end; i <= end; i++) {
            out[base + i] *= rightWindow[(int) Math.floor(position2)];
            position2 += scale2;
        }
        for (var i = end + 1; i < 2 * n; i++) {
            out[base + i] = 0.f;
        }
    }

    /**
     * Resamples a warped signal at the linear output positions from {@code start} to {@code count}
     * ({@code tw_resamp} with an offset of half a sample).
     */
    private static void resample(float[] in, int start, int inCount, int count, float[] positions, float[] out) {
        var center = 0;
        for (var i = start; i < count; i++) {
            var sum = 0.0f;
            while (center < inCount && positions[center] - 0.5f <= (float) i) {
                center++;
            }
            center--;
            out[i] = 0.f;
            if (center < inCount - 1 && center > 0) {
                var fraction = (int) Math.floor(((float) i - (positions[center] - 0.5f))
                        / (positions[center + 1] - positions[center]) * (float) OVERSAMPLING);
                if (fraction > OVERSAMPLING - 1) {
                    fraction = OVERSAMPLING - 1;
                }
                var j = BORDER * OVERSAMPLING + fraction;
                for (var k = center - BORDER; k <= center + BORDER; k++) {
                    if (k >= 0 && k < inCount) {
                        sum += INTERPOLATOR[Math.abs(j)] * in[k];
                    }
                    j -= OVERSAMPLING;
                }
            }
            if (center < 0) {
                center++;
            }
            out[i] = sum;
        }
    }

    /**
     * Builds the Kaiser windowed sinc interpolator ({@code init_resamp_filter}).
     */
    private static float[] interpolator() {
        var alpha = 8.0f;
        var size = TAPS + OVERSAMPLING;
        var taps = new float[size];
        var inverse = 1. / bessel(alpha);
        taps[0] = 1.f;
        for (var i = 1; i < TAPS; i++) {
            if (i % OVERSAMPLING != 0) {
                taps[i] = (float) (bessel(alpha * Math.sqrt(1.0 - (double) (i * i) / (float) (TAPS * TAPS)))
                        * inverse * StrictMath.sin((double) i * Math.PI / (double) OVERSAMPLING) / Math.PI / (double) i
                        * (double) OVERSAMPLING);
            }
        }
        return taps;
    }

    /**
     * Computes the modified Bessel function of the first kind and order zero by its series ({@code Bessel}).
     */
    private static double bessel(double x) {
        x *= 0.5;
        var k = 0.0;
        var p = 1.0;
        var s = 1.0;
        double ds;
        do {
            k += 1.0;
            p *= x / k;
            ds = p * p;
            s += ds;
        } while (ds > 1.0e-17 * s);
        return s;
    }

    /**
     * Returns the falling half of the oversampled sine window ({@code calc_tw_window} for window shape 0), followed
     * by zeros up to twice its length, where rounding may index past its end.
     */
    private static float[] sineTransition(int length) {
        var window = new float[2 * length + 1];
        var increment = 0.5 / (double) length;
        for (var i = 0; i < length; i++) {
            window[i] = (float) StrictMath.cos(Math.PI * increment * (double) i);
        }
        return window;
    }

    /**
     * Returns the falling half of the oversampled KBD window with alpha 4 ({@code CalculateTwKBDTransition}),
     * followed by zeros up to twice its length.
     */
    private static float[] kbdTransition(int length) {
        var window = new float[2 * length + 1];
        var raw = new double[length];
        var alpha = (float) (4.0f * Math.PI);
        var inverse = 1.0 / bessel(alpha);
        var increment = 2.0 / (double) length;
        var sum = inverse;
        for (var i = 0; i < length; i++) {
            raw[i] = bessel(alpha * Math.sqrt(((double) i * increment) * (2. - (double) i * increment))) * inverse;
            sum += raw[i];
        }
        sum = 1.0 / sum;
        var accumulated = 0.0;
        var last = 0.;
        for (var i = 0; i < length; i++) {
            accumulated += raw[i];
            var w = Math.sqrt(accumulated * sum);
            window[length - i] = (float) (0.5 * (w + last));
            last = w;
        }
        window[0] = (float) (0.5 * (1.0 + last));
        return window;
    }
}
