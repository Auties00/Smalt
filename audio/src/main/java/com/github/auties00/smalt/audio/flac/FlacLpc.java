package com.github.auties00.smalt.audio.flac;

/**
 * Restores linear-predictive FLAC subframes in place.
 *
 * <p>For every sample after the warm-up, the prediction is the sum of {@code order} products of a
 * quantized coefficient and a previous sample, accumulated in 64 bits and shifted right; the
 * decoded sample is the residual plus the prediction. Orders 1 to 12, the orders every standard
 * encoder preset uses, have fully unrolled loops that hold the coefficients and the prediction
 * window in locals; higher orders use a generic loop unrolled by four.
 *
 * @implNote This implementation mirrors the per-order unrolled restore loops of libFLAC's
 * {@code lpc.c} (BSD-3-Clause). The loop body is a recurrence, since each sample depends on the
 * one decoded just before it, so it is not vectorized; unrolling removes the inner loop overhead,
 * keeping the window in registers takes the store-to-load forwarding of the newest sample off the
 * critical path, and adding the newest sample's product last lets the older products overlap with
 * its latency. The sum of
 * the products is exact in 64 bits for any legal stream (at most 32 products of a 15-bit
 * coefficient and a 33-bit sample), so the association order of the additions does not matter.
 */
final class FlacLpc {
    /**
     * Prevents instantiation.
     */
    private FlacLpc() {
        throw new AssertionError();
    }

    /**
     * Restores a subframe: {@code out[order, blockSize)} holds the residual on entry and the
     * samples on return.
     *
     * @param out          the warm-up samples followed by the residual
     * @param blockSize    the number of samples
     * @param coefficients the quantized coefficients, the first applying to the newest sample
     * @param order        the predictor order, from 1 to 32
     * @param shift        the right shift applied to each prediction
     */
    static void restore(int[] out, int blockSize, int[] coefficients, int order, int shift) {
        switch (order) {
            case 1 -> restore1(out, blockSize, coefficients, shift);
            case 2 -> restore2(out, blockSize, coefficients, shift);
            case 3 -> restore3(out, blockSize, coefficients, shift);
            case 4 -> restore4(out, blockSize, coefficients, shift);
            case 5 -> restore5(out, blockSize, coefficients, shift);
            case 6 -> restore6(out, blockSize, coefficients, shift);
            case 7 -> restore7(out, blockSize, coefficients, shift);
            case 8 -> restore8(out, blockSize, coefficients, shift);
            case 9 -> restore9(out, blockSize, coefficients, shift);
            case 10 -> restore10(out, blockSize, coefficients, shift);
            case 11 -> restore11(out, blockSize, coefficients, shift);
            case 12 -> restore12(out, blockSize, coefficients, shift);
            default -> restoreGeneric(out, blockSize, coefficients, order, shift);
        }
    }

    /**
     * Restores a subframe of order 1, keeping the prediction window in locals.
     *
     * @param out          the warm-up samples followed by the residual
     * @param blockSize    the number of samples
     * @param coefficients the quantized coefficients
     * @param shift        the right shift applied to each prediction
     */
    private static void restore1(int[] out, int blockSize, int[] coefficients, int shift) {
        long c0 = coefficients[0];
        var s1 = out[0];
        for (var i = 1; i < blockSize; i++) {
            var sum = c0 * s1;
            var sample = out[i] + (int) (sum >> shift);
            out[i] = sample;
            s1 = sample;
        }
    }

    /**
     * Restores a subframe of order 2, keeping the prediction window in locals.
     *
     * @param out          the warm-up samples followed by the residual
     * @param blockSize    the number of samples
     * @param coefficients the quantized coefficients
     * @param shift        the right shift applied to each prediction
     */
    private static void restore2(int[] out, int blockSize, int[] coefficients, int shift) {
        long c0 = coefficients[0];
        long c1 = coefficients[1];
        var s1 = out[1];
        var s2 = out[0];
        for (var i = 2; i < blockSize; i++) {
            var sum = c1 * s2 + c0 * s1;
            var sample = out[i] + (int) (sum >> shift);
            out[i] = sample;
            s2 = s1;
            s1 = sample;
        }
    }

    /**
     * Restores a subframe of order 3, keeping the prediction window in locals.
     *
     * @param out          the warm-up samples followed by the residual
     * @param blockSize    the number of samples
     * @param coefficients the quantized coefficients
     * @param shift        the right shift applied to each prediction
     */
    private static void restore3(int[] out, int blockSize, int[] coefficients, int shift) {
        long c0 = coefficients[0];
        long c1 = coefficients[1];
        long c2 = coefficients[2];
        var s1 = out[2];
        var s2 = out[1];
        var s3 = out[0];
        for (var i = 3; i < blockSize; i++) {
            var sum = (c1 * s2 + c2 * s3) + c0 * s1;
            var sample = out[i] + (int) (sum >> shift);
            out[i] = sample;
            s3 = s2;
            s2 = s1;
            s1 = sample;
        }
    }

    /**
     * Restores a subframe of order 4, keeping the prediction window in locals.
     *
     * @param out          the warm-up samples followed by the residual
     * @param blockSize    the number of samples
     * @param coefficients the quantized coefficients
     * @param shift        the right shift applied to each prediction
     */
    private static void restore4(int[] out, int blockSize, int[] coefficients, int shift) {
        long c0 = coefficients[0];
        long c1 = coefficients[1];
        long c2 = coefficients[2];
        long c3 = coefficients[3];
        var s1 = out[3];
        var s2 = out[2];
        var s3 = out[1];
        var s4 = out[0];
        for (var i = 4; i < blockSize; i++) {
            var sum = (c1 * s2 + c2 * s3) + c3 * s4 + c0 * s1;
            var sample = out[i] + (int) (sum >> shift);
            out[i] = sample;
            s4 = s3;
            s3 = s2;
            s2 = s1;
            s1 = sample;
        }
    }

    /**
     * Restores a subframe of order 5, keeping the prediction window in locals.
     *
     * @param out          the warm-up samples followed by the residual
     * @param blockSize    the number of samples
     * @param coefficients the quantized coefficients
     * @param shift        the right shift applied to each prediction
     */
    private static void restore5(int[] out, int blockSize, int[] coefficients, int shift) {
        long c0 = coefficients[0];
        long c1 = coefficients[1];
        long c2 = coefficients[2];
        long c3 = coefficients[3];
        long c4 = coefficients[4];
        var s1 = out[4];
        var s2 = out[3];
        var s3 = out[2];
        var s4 = out[1];
        var s5 = out[0];
        for (var i = 5; i < blockSize; i++) {
            var sum = (c1 * s2 + c2 * s3) + (c3 * s4 + c4 * s5) + c0 * s1;
            var sample = out[i] + (int) (sum >> shift);
            out[i] = sample;
            s5 = s4;
            s4 = s3;
            s3 = s2;
            s2 = s1;
            s1 = sample;
        }
    }

    /**
     * Restores a subframe of order 6, keeping the prediction window in locals.
     *
     * @param out          the warm-up samples followed by the residual
     * @param blockSize    the number of samples
     * @param coefficients the quantized coefficients
     * @param shift        the right shift applied to each prediction
     */
    private static void restore6(int[] out, int blockSize, int[] coefficients, int shift) {
        long c0 = coefficients[0];
        long c1 = coefficients[1];
        long c2 = coefficients[2];
        long c3 = coefficients[3];
        long c4 = coefficients[4];
        long c5 = coefficients[5];
        var s1 = out[5];
        var s2 = out[4];
        var s3 = out[3];
        var s4 = out[2];
        var s5 = out[1];
        var s6 = out[0];
        for (var i = 6; i < blockSize; i++) {
            var sum = (c1 * s2 + c2 * s3) + (c3 * s4 + c4 * s5) + c5 * s6 + c0 * s1;
            var sample = out[i] + (int) (sum >> shift);
            out[i] = sample;
            s6 = s5;
            s5 = s4;
            s4 = s3;
            s3 = s2;
            s2 = s1;
            s1 = sample;
        }
    }

    /**
     * Restores a subframe of order 7, keeping the prediction window in locals.
     *
     * @param out          the warm-up samples followed by the residual
     * @param blockSize    the number of samples
     * @param coefficients the quantized coefficients
     * @param shift        the right shift applied to each prediction
     */
    private static void restore7(int[] out, int blockSize, int[] coefficients, int shift) {
        long c0 = coefficients[0];
        long c1 = coefficients[1];
        long c2 = coefficients[2];
        long c3 = coefficients[3];
        long c4 = coefficients[4];
        long c5 = coefficients[5];
        long c6 = coefficients[6];
        var s1 = out[6];
        var s2 = out[5];
        var s3 = out[4];
        var s4 = out[3];
        var s5 = out[2];
        var s6 = out[1];
        var s7 = out[0];
        for (var i = 7; i < blockSize; i++) {
            var sum = (c1 * s2 + c2 * s3) + (c3 * s4 + c4 * s5) + (c5 * s6 + c6 * s7) + c0 * s1;
            var sample = out[i] + (int) (sum >> shift);
            out[i] = sample;
            s7 = s6;
            s6 = s5;
            s5 = s4;
            s4 = s3;
            s3 = s2;
            s2 = s1;
            s1 = sample;
        }
    }

    /**
     * Restores a subframe of order 8, keeping the prediction window in locals.
     *
     * @param out          the warm-up samples followed by the residual
     * @param blockSize    the number of samples
     * @param coefficients the quantized coefficients
     * @param shift        the right shift applied to each prediction
     */
    private static void restore8(int[] out, int blockSize, int[] coefficients, int shift) {
        long c0 = coefficients[0];
        long c1 = coefficients[1];
        long c2 = coefficients[2];
        long c3 = coefficients[3];
        long c4 = coefficients[4];
        long c5 = coefficients[5];
        long c6 = coefficients[6];
        long c7 = coefficients[7];
        var s1 = out[7];
        var s2 = out[6];
        var s3 = out[5];
        var s4 = out[4];
        var s5 = out[3];
        var s6 = out[2];
        var s7 = out[1];
        var s8 = out[0];
        for (var i = 8; i < blockSize; i++) {
            var sum = (c1 * s2 + c2 * s3) + (c3 * s4 + c4 * s5) + (c5 * s6 + c6 * s7) + c7 * s8 + c0 * s1;
            var sample = out[i] + (int) (sum >> shift);
            out[i] = sample;
            s8 = s7;
            s7 = s6;
            s6 = s5;
            s5 = s4;
            s4 = s3;
            s3 = s2;
            s2 = s1;
            s1 = sample;
        }
    }

    /**
     * Restores a subframe of order 9, keeping the prediction window in locals.
     *
     * @param out          the warm-up samples followed by the residual
     * @param blockSize    the number of samples
     * @param coefficients the quantized coefficients
     * @param shift        the right shift applied to each prediction
     */
    private static void restore9(int[] out, int blockSize, int[] coefficients, int shift) {
        long c0 = coefficients[0];
        long c1 = coefficients[1];
        long c2 = coefficients[2];
        long c3 = coefficients[3];
        long c4 = coefficients[4];
        long c5 = coefficients[5];
        long c6 = coefficients[6];
        long c7 = coefficients[7];
        long c8 = coefficients[8];
        var s1 = out[8];
        var s2 = out[7];
        var s3 = out[6];
        var s4 = out[5];
        var s5 = out[4];
        var s6 = out[3];
        var s7 = out[2];
        var s8 = out[1];
        var s9 = out[0];
        for (var i = 9; i < blockSize; i++) {
            var sum = (c1 * s2 + c2 * s3) + (c3 * s4 + c4 * s5) + (c5 * s6 + c6 * s7) + (c7 * s8 + c8 * s9)
                    + c0 * s1;
            var sample = out[i] + (int) (sum >> shift);
            out[i] = sample;
            s9 = s8;
            s8 = s7;
            s7 = s6;
            s6 = s5;
            s5 = s4;
            s4 = s3;
            s3 = s2;
            s2 = s1;
            s1 = sample;
        }
    }

    /**
     * Restores a subframe of order 10, keeping the prediction window in locals.
     *
     * @param out          the warm-up samples followed by the residual
     * @param blockSize    the number of samples
     * @param coefficients the quantized coefficients
     * @param shift        the right shift applied to each prediction
     */
    private static void restore10(int[] out, int blockSize, int[] coefficients, int shift) {
        long c0 = coefficients[0];
        long c1 = coefficients[1];
        long c2 = coefficients[2];
        long c3 = coefficients[3];
        long c4 = coefficients[4];
        long c5 = coefficients[5];
        long c6 = coefficients[6];
        long c7 = coefficients[7];
        long c8 = coefficients[8];
        long c9 = coefficients[9];
        var s1 = out[9];
        var s2 = out[8];
        var s3 = out[7];
        var s4 = out[6];
        var s5 = out[5];
        var s6 = out[4];
        var s7 = out[3];
        var s8 = out[2];
        var s9 = out[1];
        var s10 = out[0];
        for (var i = 10; i < blockSize; i++) {
            var sum = (c1 * s2 + c2 * s3) + (c3 * s4 + c4 * s5) + (c5 * s6 + c6 * s7) + (c7 * s8 + c8 * s9)
                    + c9 * s10 + c0 * s1;
            var sample = out[i] + (int) (sum >> shift);
            out[i] = sample;
            s10 = s9;
            s9 = s8;
            s8 = s7;
            s7 = s6;
            s6 = s5;
            s5 = s4;
            s4 = s3;
            s3 = s2;
            s2 = s1;
            s1 = sample;
        }
    }

    /**
     * Restores a subframe of order 11, keeping the prediction window in locals.
     *
     * @param out          the warm-up samples followed by the residual
     * @param blockSize    the number of samples
     * @param coefficients the quantized coefficients
     * @param shift        the right shift applied to each prediction
     */
    private static void restore11(int[] out, int blockSize, int[] coefficients, int shift) {
        long c0 = coefficients[0];
        long c1 = coefficients[1];
        long c2 = coefficients[2];
        long c3 = coefficients[3];
        long c4 = coefficients[4];
        long c5 = coefficients[5];
        long c6 = coefficients[6];
        long c7 = coefficients[7];
        long c8 = coefficients[8];
        long c9 = coefficients[9];
        long c10 = coefficients[10];
        var s1 = out[10];
        var s2 = out[9];
        var s3 = out[8];
        var s4 = out[7];
        var s5 = out[6];
        var s6 = out[5];
        var s7 = out[4];
        var s8 = out[3];
        var s9 = out[2];
        var s10 = out[1];
        var s11 = out[0];
        for (var i = 11; i < blockSize; i++) {
            var sum = (c1 * s2 + c2 * s3) + (c3 * s4 + c4 * s5) + (c5 * s6 + c6 * s7) + (c7 * s8 + c8 * s9)
                    + (c9 * s10 + c10 * s11) + c0 * s1;
            var sample = out[i] + (int) (sum >> shift);
            out[i] = sample;
            s11 = s10;
            s10 = s9;
            s9 = s8;
            s8 = s7;
            s7 = s6;
            s6 = s5;
            s5 = s4;
            s4 = s3;
            s3 = s2;
            s2 = s1;
            s1 = sample;
        }
    }

    /**
     * Restores a subframe of order 12, keeping the prediction window in locals.
     *
     * @param out          the warm-up samples followed by the residual
     * @param blockSize    the number of samples
     * @param coefficients the quantized coefficients
     * @param shift        the right shift applied to each prediction
     */
    private static void restore12(int[] out, int blockSize, int[] coefficients, int shift) {
        long c0 = coefficients[0];
        long c1 = coefficients[1];
        long c2 = coefficients[2];
        long c3 = coefficients[3];
        long c4 = coefficients[4];
        long c5 = coefficients[5];
        long c6 = coefficients[6];
        long c7 = coefficients[7];
        long c8 = coefficients[8];
        long c9 = coefficients[9];
        long c10 = coefficients[10];
        long c11 = coefficients[11];
        var s1 = out[11];
        var s2 = out[10];
        var s3 = out[9];
        var s4 = out[8];
        var s5 = out[7];
        var s6 = out[6];
        var s7 = out[5];
        var s8 = out[4];
        var s9 = out[3];
        var s10 = out[2];
        var s11 = out[1];
        var s12 = out[0];
        for (var i = 12; i < blockSize; i++) {
            var sum = (c1 * s2 + c2 * s3) + (c3 * s4 + c4 * s5) + (c5 * s6 + c6 * s7) + (c7 * s8 + c8 * s9)
                    + (c9 * s10 + c10 * s11) + c11 * s12 + c0 * s1;
            var sample = out[i] + (int) (sum >> shift);
            out[i] = sample;
            s12 = s11;
            s11 = s10;
            s10 = s9;
            s9 = s8;
            s8 = s7;
            s7 = s6;
            s6 = s5;
            s5 = s4;
            s4 = s3;
            s3 = s2;
            s2 = s1;
            s1 = sample;
        }
    }

    /**
     * Restores a subframe of any order with a loop unrolled by four.
     *
     * @param out          the warm-up samples followed by the residual
     * @param blockSize    the number of samples
     * @param coefficients the quantized coefficients
     * @param order        the predictor order, from 1 to 32
     * @param shift        the right shift applied to each prediction
     */
    private static void restoreGeneric(int[] out, int blockSize, int[] coefficients, int order, int shift) {
        var quads = order & ~3;
        for (var i = order; i < blockSize; i++) {
            var sum = 0L;
            var j = 0;
            for (; j < quads; j += 4) {
                sum += (long) coefficients[j] * out[i - 1 - j] + (long) coefficients[j + 1] * out[i - 2 - j]
                        + (long) coefficients[j + 2] * out[i - 3 - j] + (long) coefficients[j + 3] * out[i - 4 - j];
            }
            for (; j < order; j++) {
                sum += (long) coefficients[j] * out[i - 1 - j];
            }
            out[i] += (int) (sum >> shift);
        }
    }
}
