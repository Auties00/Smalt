package com.github.auties00.smalt.audio.ac3.internal;

import java.util.Arrays;

/**
 * Computes the downmix coefficients of an AC-3 or E-AC-3 program to two channels (Lo/Ro or Lt/Rt)
 * or one, following ATSC A/52 sections 7.8.1 and 7.8.2, Annex D section 3.1 and Annex E section
 * 3.9.
 *
 * <p>The center and surround mix levels come from the frame: the Lo/Ro or Lt/Rt levels of the
 * alternate syntax or of the E-AC-3 mixing metadata when present, the {@code cmixlev} and
 * {@code surmixlev} levels of AC-3 otherwise, and -3 dB when the frame carries neither. The LFE is
 * mixed into the front channels only when an E-AC-3 frame carries an LFE mix level. The
 * coefficients are then scaled down together so that no output can exceed full scale when every
 * input is at full scale.
 */
public final class Downmix {
    /**
     * The output of a downmix.
     */
    public enum Mode {
        /**
         * Two-channel Lo/Ro output.
         */
        STEREO,

        /**
         * Two-channel Lt/Rt (matrix surround compatible) output.
         */
        MATRIX,

        /**
         * One-channel output: the Lo/Ro downmix summed.
         */
        MONO
    }

    /**
     * The levels of the 3-bit mix level codes of Annex D tables D2.3 to D2.6:
     * {@code 2^((2 - code) / 4)} for codes 0 to 6, zero for code 7.
     */
    private static final float[] LEVELS = {1.4142135f, 1.1892071f, 1.0f, 0.8408964f, 0.70710677f, 0.59460354f,
            0.5f, 0.0f};

    /**
     * The center mix levels of {@code cmixlev} (A/52 table 5.9), the reserved code read as -4.5 dB.
     */
    private static final float[] CENTER_LEVELS = {0.70710677f, 0.59460354f, 0.5f, 0.59460354f};

    /**
     * The surround mix levels of {@code surmixlev} (A/52 table 5.10), the reserved code read as
     * -6 dB.
     */
    private static final float[] SURROUND_LEVELS = {0.70710677f, 0.5f, 0.0f, 0.5f};

    /**
     * The level of -3 dB.
     */
    private static final float MINUS_3_DB = 0.70710677f;

    /**
     * Prevents instantiation.
     */
    private Downmix() {
        throw new AssertionError();
    }

    /**
     * Computes the downmix of the coded channels of a frame.
     *
     * @param header the frame header of the independent substream
     * @param mode the output
     * @param out receives the coefficients, by output channel then coded channel (LFE last); at
     * least {@code [2][6]}
     * @return the number of output channels
     */
    public static int coefficients(FrameHeader header, Mode mode, float[][] out) {
        var outputs = mode == Mode.MONO ? 1 : 2;
        var coded = header.channels + (header.lfe ? 1 : 0);
        for (var row = 0; row < outputs; row++) {
            Arrays.fill(out[row], 0, coded, 0);
        }
        var matrix = mode == Mode.MATRIX;
        var acmod = header.acmod;
        if (acmod == 0) {
            if (mode == Mode.MONO) {
                out[0][0] = 0.5f;
                out[0][1] = 0.5f;
            } else {
                out[0][0] = 1;
                out[1][1] = 1;
            }
            return normalize(out, outputs, coded);
        }
        float center;
        float surround;
        if (matrix) {
            center = header.ltrtCenterMixLevel >= 0 ? LEVELS[header.ltrtCenterMixLevel] : MINUS_3_DB;
            surround = header.ltrtSurroundMixLevel >= 0 ? surroundLevel(header.ltrtSurroundMixLevel) : MINUS_3_DB;
        } else if (header.loroCenterMixLevel >= 0 || header.loroSurroundMixLevel >= 0) {
            center = header.loroCenterMixLevel >= 0 ? LEVELS[header.loroCenterMixLevel] : MINUS_3_DB;
            surround = header.loroSurroundMixLevel >= 0 ? surroundLevel(header.loroSurroundMixLevel) : MINUS_3_DB;
        } else {
            center = header.centerMixLevel >= 0 ? CENTER_LEVELS[header.centerMixLevel] : MINUS_3_DB;
            surround = header.surroundMixLevel >= 0 ? SURROUND_LEVELS[header.surroundMixLevel] : MINUS_3_DB;
        }
        var left = new float[coded];
        var right = new float[coded];
        switch (acmod) {
            case 1 -> {
                left[0] = MINUS_3_DB;
                right[0] = MINUS_3_DB;
            }
            case 2 -> {
                left[0] = 1;
                right[1] = 1;
            }
            case 3 -> {
                left[0] = 1;
                right[2] = 1;
                left[1] = center;
                right[1] = center;
            }
            case 4 -> {
                left[0] = 1;
                right[1] = 1;
                surroundSingle(left, right, 2, surround, matrix);
            }
            case 5 -> {
                left[0] = 1;
                right[2] = 1;
                left[1] = center;
                right[1] = center;
                surroundSingle(left, right, 3, surround, matrix);
            }
            case 6 -> {
                left[0] = 1;
                right[1] = 1;
                surroundPair(left, right, 2, surround, matrix);
            }
            default -> {
                left[0] = 1;
                right[2] = 1;
                left[1] = center;
                right[1] = center;
                surroundPair(left, right, 3, surround, matrix);
            }
        }
        if (header.lfe && header.lfeMixLevel >= 0) {
            var lfeLevel = (float) Math.pow(10, (10 - header.lfeMixLevel - 4.5) / 20);
            left[coded - 1] = lfeLevel;
            right[coded - 1] = lfeLevel;
        }
        if (mode == Mode.MONO) {
            for (var ch = 0; ch < coded; ch++) {
                out[0][ch] = left[ch] + right[ch];
            }
        } else {
            System.arraycopy(left, 0, out[0], 0, coded);
            System.arraycopy(right, 0, out[1], 0, coded);
        }
        return normalize(out, outputs, coded);
    }

    /**
     * Returns the level of a 3-bit surround mix level code, whose reserved codes 0 to 2 read as
     * -1.5 dB (Annex D tables D2.4 and D2.6).
     *
     * @param code the 3-bit mix level code
     * @return the level
     */
    private static float surroundLevel(int code) {
        return code < 3 ? LEVELS[3] : LEVELS[code];
    }

    /**
     * Mixes a single surround channel.
     *
     * @param left the left output coefficients
     * @param right the right output coefficients
     * @param channel the coded index of the surround channel
     * @param level the surround mix level
     * @param matrix whether the output is Lt/Rt
     */
    private static void surroundSingle(float[] left, float[] right, int channel, float level, boolean matrix) {
        if (matrix) {
            left[channel] = -level;
            right[channel] = level;
        } else {
            left[channel] = MINUS_3_DB * level;
            right[channel] = MINUS_3_DB * level;
        }
    }

    /**
     * Mixes a pair of surround channels.
     *
     * @param left the left output coefficients
     * @param right the right output coefficients
     * @param first the coded index of the left surround channel
     * @param level the surround mix level
     * @param matrix whether the output is Lt/Rt
     */
    private static void surroundPair(float[] left, float[] right, int first, float level, boolean matrix) {
        if (matrix) {
            left[first] = -level;
            left[first + 1] = -level;
            right[first] = level;
            right[first + 1] = level;
        } else {
            left[first] = level;
            right[first + 1] = level;
        }
    }

    /**
     * Scales the coefficients so that the absolute coefficients of every output sum to at most one.
     *
     * @param out the coefficients, scaled in place
     * @param outputs the number of outputs
     * @param coded the number of coded channels
     * @return {@code outputs}
     */
    private static int normalize(float[][] out, int outputs, int coded) {
        var largest = 0.0f;
        for (var row = 0; row < outputs; row++) {
            var sum = 0.0f;
            for (var ch = 0; ch < coded; ch++) {
                sum += Math.abs(out[row][ch]);
            }
            largest = Math.max(largest, sum);
        }
        if (largest > 1) {
            var scale = 1 / largest;
            for (var row = 0; row < outputs; row++) {
                for (var ch = 0; ch < coded; ch++) {
                    out[row][ch] *= scale;
                }
            }
        }
        return outputs;
    }
}
