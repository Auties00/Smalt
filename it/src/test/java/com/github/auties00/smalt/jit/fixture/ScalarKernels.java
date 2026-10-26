package com.github.auties00.smalt.jit.fixture;

/**
 * The scalar twins of the fixture kernels.
 */
public final class ScalarKernels {
    private ScalarKernels() {
    }

    /**
     * Computes {@code a * b + a} per element of a range.
     *
     * @param a the first operands
     * @param b the second operands
     * @param out receives the results
     * @param from the first element
     * @param to the element past the last
     */
    public static void mix(int[] a, int[] b, int[] out, int from, int to) {
        for (var i = from; i < to; i++) {
            out[i] = a[i] * b[i] + a[i];
        }
    }

    /**
     * Adds per element.
     *
     * @param a the first operands
     * @param b the second operands
     * @param out receives the sums
     */
    public static void add(int[] a, int[] b, int[] out) {
        for (var i = 0; i < out.length; i++) {
            out[i] = a[i] + b[i];
        }
    }
}
