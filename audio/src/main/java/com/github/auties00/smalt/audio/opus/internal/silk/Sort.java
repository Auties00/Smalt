/* Ports libopus 1.5.2 (silk/sort.c). Copyright Skype Limited; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.silk;

/**
 * Sorts short vectors with insertion sorts that are fast on almost sorted input and can stop
 * after the first {@code K} positions are correct.
 */
public final class Sort {
    private Sort() {
        throw new AssertionError();
    }

    /**
     * Sorts the {@code K} smallest of {@code L} values in increasing order and records their
     * original indices ({@code silk_insertion_sort_increasing}).
     *
     * @param a   the values; the first {@code K} become the sorted smallest values
     * @param idx receives the original index of each of the first {@code K} values
     * @param L   the number of values, at least {@code K}
     * @param K   the number of positions that must be correct, positive
     */
    public static void insertionSortIncreasing(int[] a, int[] idx, int L, int K) {
        for (var i = 0; i < K; i++) {
            idx[i] = i;
        }
        for (var i = 1; i < K; i++) {
            var value = a[i];
            int j;
            for (j = i - 1; j >= 0 && value < a[j]; j--) {
                a[j + 1] = a[j];
                idx[j + 1] = idx[j];
            }
            a[j + 1] = value;
            idx[j + 1] = i;
        }
        for (var i = K; i < L; i++) {
            var value = a[i];
            if (value < a[K - 1]) {
                int j;
                for (j = K - 2; j >= 0 && value < a[j]; j--) {
                    a[j + 1] = a[j];
                    idx[j + 1] = idx[j];
                }
                a[j + 1] = value;
                idx[j + 1] = i;
            }
        }
    }

    /**
     * Sorts all 16-bit values in increasing order
     * ({@code silk_insertion_sort_increasing_all_values_int16}).
     *
     * @param a the values, sorted in place
     * @param L the number of values, positive
     */
    public static void insertionSortIncreasingAllValuesInt16(short[] a, int L) {
        for (var i = 1; i < L; i++) {
            int value = a[i];
            int j;
            for (j = i - 1; j >= 0 && value < a[j]; j--) {
                a[j + 1] = a[j];
            }
            a[j + 1] = (short) value;
        }
    }
}
