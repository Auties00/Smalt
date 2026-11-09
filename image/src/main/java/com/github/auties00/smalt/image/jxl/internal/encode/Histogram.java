/* Ports libjxl 0.11.2 (lib/jxl/enc_cluster.cc: Histogram, HistogramEntropy, HistogramDistance,
   HistogramKLDivergence). Copyright the JPEG XL Project Authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.encode;

import java.util.Arrays;

/**
 * A histogram of entropy-coding symbols with its cached Shannon entropy.
 *
 * <p>Instances are not thread-safe.
 */
final class Histogram {
    int[] data;

    long total;

    double entropy;

    Histogram() {
        data = new int[16];
    }

    Histogram(Histogram other) {
        data = other.data.clone();
        total = other.total;
        entropy = other.entropy;
    }

    void add(int symbol) {
        if (symbol >= data.length) {
            data = Arrays.copyOf(data, Math.max(symbol + 1, data.length * 2));
        }
        data[symbol]++;
        total++;
    }

    void add(Histogram other) {
        if (other.data.length > data.length) {
            data = Arrays.copyOf(data, other.data.length);
        }
        for (var i = 0; i < other.data.length; i++) {
            data[i] += other.data[i];
        }
        total += other.total;
    }

    void clear() {
        Arrays.fill(data, 0);
        total = 0;
        entropy = 0;
    }

    /**
     * {@return the alphabet size, one past the last symbol with a non-zero count, at least 1}
     */
    int alphabetSize() {
        for (var i = data.length - 1; i >= 0; i--) {
            if (data[i] != 0) {
                return i + 1;
            }
        }
        return 1;
    }

    /**
     * Computes and caches the Shannon entropy in bits of the whole histogram.
     *
     * @return the entropy
     */
    double computeEntropy() {
        entropy = entropyOf(data, data.length, total);
        return entropy;
    }

    static double entropyOf(int[] counts, int length, long total) {
        if (total == 0) {
            return 0;
        }
        var inv = 1.0 / total;
        var sum = 0.0;
        for (var i = 0; i < length; i++) {
            var c = counts[i];
            if (c != 0 && c != total) {
                sum -= c * log2(c * inv);
            }
        }
        return sum;
    }

    static double distance(Histogram a, Histogram b) {
        if (a.total == 0 || b.total == 0) {
            return 0;
        }
        var total = a.total + b.total;
        var inv = 1.0 / total;
        var sum = 0.0;
        var length = Math.max(a.data.length, b.data.length);
        for (var i = 0; i < length; i++) {
            long c = (i < a.data.length ? a.data[i] : 0) + (i < b.data.length ? b.data[i] : 0);
            if (c != 0 && c != total) {
                sum -= c * log2(c * inv);
            }
        }
        return sum - a.entropy - b.entropy;
    }

    static double klDivergence(Histogram actual, Histogram coding) {
        if (actual.total == 0) {
            return 0;
        }
        if (coding.total == 0) {
            return Double.POSITIVE_INFINITY;
        }
        var inv = 1.0 / coding.total;
        var cost = 0.0;
        for (var i = 0; i < actual.data.length; i++) {
            var c = actual.data[i];
            if (c == 0) {
                continue;
            }
            var coded = i < coding.data.length ? coding.data[i] : 0;
            if (coded == 0) {
                return Double.POSITIVE_INFINITY;
            }
            cost -= c * log2(coded * inv);
        }
        return cost - actual.entropy;
    }

    static double log2(double x) {
        return StrictMath.log(x) * 1.4426950408889634;
    }
}
