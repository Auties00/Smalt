/* Ports Monkey's Audio SDK 13.45 (Source/MACLib/Old/AntiPredictorOld.cpp, AntiPredictorFastOld.cpp,
   AntiPredictorNormalOld.cpp, AntiPredictorHighOld.cpp, AntiPredictorExtraHighOld.cpp). Copyright (c) 2000 - 2026
   Matthew T. Ashland; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.ape;

/**
 * The anti-predictors of Monkey's Audio files before version 3.930, one per compression level and range of versions.
 *
 * <p>Each method reads the residuals of a channel's frame from {@code input}, which it may overwrite, and writes the
 * samples to {@code output}, except {@link #fast3320} which works in place. Arguments are not checked; values
 * outside the documented ranges give unspecified results.
 */
final class ApeAntiPredictors {
    private ApeAntiPredictors() {
        throw new AssertionError();
    }

    /**
     * Applies the anti-predictor of a compression level and version to a channel's frame, leaving the samples in
     * {@code data}.
     *
     * @param version the file version times 1000
     * @param level the compression level
     * @param data the residuals on input, the samples on output
     * @param temp a scratch array of the same length
     * @param count the number of samples
     * @param coefficientCount the number of extra high offset pairs minus one, or -1 when none were read
     * @param a the first offsets of each pair (extra high before 3.800)
     * @param b the second offsets of each pair
     * @param adapt a scratch array of at least {@code count} elements
     * @param shorts a scratch array of at least {@code count} elements
     */
    static void apply(int version, int level, int[] data, int[] temp, int count, int coefficientCount, long[] a,
                      long[] b, short[] adapt, short[] shorts) {
        switch (level) {
            case 1000 -> {
                if (version < 3320) {
                    System.arraycopy(data, 0, temp, 0, count);
                    fast0000(temp, data, count);
                } else {
                    fast3320(data, count);
                }
            }
            case 2000 -> {
                System.arraycopy(data, 0, temp, 0, count);
                if (version < 3320) {
                    normal0000(temp, data, count);
                } else if (version < 3800) {
                    normal3320(temp, data, count);
                } else {
                    normal3800(temp, data, count);
                }
            }
            case 3000 -> {
                System.arraycopy(data, 0, temp, 0, count);
                if (version < 3320) {
                    high0000(temp, data, count);
                } else if (version < 3600) {
                    high3320(temp, data, count);
                } else if (version < 3700) {
                    high3600(temp, data, count);
                } else if (version < 3800) {
                    high3700(temp, data, count);
                } else {
                    high3800(temp, data, count);
                }
            }
            default -> {
                System.arraycopy(data, 0, temp, 0, count);
                if (version < 3320) {
                    extraHigh0000(temp, data, count, coefficientCount, a, b);
                } else if (version < 3600) {
                    extraHigh3320(temp, data, count, coefficientCount, a, b);
                } else if (version < 3700) {
                    extraHighPairs(temp, data, count, coefficientCount, a, b);
                    high3600(temp, data, count);
                } else if (version < 3800) {
                    extraHighPairs(temp, data, count, coefficientCount, a, b);
                    high3700(temp, data, count);
                } else {
                    extraHigh3800(temp, data, count, version, adapt, shorts);
                }
            }
        }
    }

    private static void offset(int[] input, int[] output, int count, int offset, int deltaM) {
        System.arraycopy(input, 0, output, 0, offset);
        var m = 0;
        for (var q = offset; q < count; q++) {
            var previous = output[q - offset];
            output[q] = input[q] + ((previous * m) >> 12);
            if ((previous ^ input[q]) > 0) {
                m += deltaM;
            } else {
                m -= deltaM;
            }
        }
    }

    static void fast0000(int[] input, int[] output, int count) {
        if (count < 32) {
            System.arraycopy(input, 0, output, 0, count);
            return;
        }
        output[0] = input[0];
        for (var i = 1; i < 8; i++) {
            output[i] = input[i] + output[i - 1];
        }
        var m = 4000;
        var p = output[7] * 2 - output[6];
        var pw = (p * m) >> 12;
        for (var q = 8; q < count; q++) {
            output[q] = input[q] + pw;
            if (input[q] > 0) {
                m += p > 0 ? 4 : -4;
            } else if (input[q] < 0) {
                m += p > 0 ? -4 : 4;
            }
            p = output[q] * 2 - output[q - 1];
            pw = (p * m) >> 12;
        }
    }

    static void fast3320(int[] data, int count) {
        if (count < 3) {
            return;
        }
        var m = 375;
        var ip2 = data[1];
        var ip3 = data[0];
        var op1 = data[1];
        for (var q = 2; q < count; q++) {
            var p = ip2 + ip2 - ip3;
            ip3 = ip2;
            ip2 = data[q] + ((p * m) >> 9);
            if ((data[q] ^ p) > 0) {
                m++;
            } else {
                m--;
            }
            data[q] = ip2 + op1;
            op1 = data[q];
        }
    }

    static void normal0000(int[] input, int[] output, int count) {
        if (count < 32) {
            System.arraycopy(input, 0, output, 0, count);
            return;
        }
        System.arraycopy(input, 0, output, 0, 8);
        var m = 300;
        var p = output[7] * 3 - output[6] * 3 + output[5];
        var pw = (p * m) >> 12;
        for (var q = 8; q < count; q++) {
            output[q] = input[q] + pw;
            if (input[q] > 0) {
                m += p > 0 ? 4 : -4;
            } else if (input[q] < 0) {
                m += p > 0 ? -4 : 4;
            }
            p = output[q] * 3 - output[q - 1] * 3 + output[q - 2];
            pw = (p * m) >> 12;
        }
        System.arraycopy(output, 0, input, 0, 8);
        m = 3000;
        p = input[7] * 2 - input[6];
        pw = (p * m) >> 12;
        for (var q = 8; q < count; q++) {
            input[q] = output[q] + pw;
            if (output[q] > 0) {
                m += p > 0 ? 12 : -12;
            } else if (output[q] < 0) {
                m += p > 0 ? -12 : 12;
            }
            p = input[q] * 2 - input[q - 1];
            pw = (p * m) >> 12;
        }
        output[0] = input[0];
        for (var i = 1; i < 8; i++) {
            output[i] = input[i] + output[i - 1];
        }
        m = 3900;
        p = output[7];
        pw = (p * m) >> 12;
        for (var q = 8; q < count; q++) {
            output[q] = input[q] + pw;
            if (input[q] > 0) {
                m += p > 0 ? 1 : -1;
            } else if (input[q] < 0) {
                m += p > 0 ? -1 : 1;
            }
            p = output[q];
            pw = (p * m) >> 12;
        }
    }

    static void normal3320(int[] input, int[] output, int count) {
        if (count < 8) {
            System.arraycopy(input, 0, output, 0, count);
            return;
        }
        System.arraycopy(input, 0, output, 0, 5);
        var m1 = 0;
        var m2 = 64;
        var m3 = 28;
        var p3 = 3 * (output[4] - output[3]) + output[2];
        var p2 = input[4] + ((input[2] - input[3]) << 3) - input[1] + input[0];
        var p1 = output[4];
        for (var q = 5; q < count; q++) {
            var op0 = input[q] + ((p1 * m1) >> 8);
            if ((input[q] ^ p1) > 0) {
                m1++;
            } else {
                m1--;
            }
            p1 = op0;
            input[q] = op0 + ((p2 * m2) >> 11);
            if ((op0 ^ p2) > 0) {
                m2++;
            } else {
                m2--;
            }
            p2 = input[q] + ((input[q - 2] - input[q - 1]) << 3) - input[q - 3] + input[q - 4];
            output[q] = input[q] + ((p3 * m3) >> 9);
            if ((input[q] ^ p3) > 0) {
                m3++;
            } else {
                m3--;
            }
            p3 = 3 * (output[q] - output[q - 1]) + output[q - 2];
        }
        var m4 = 370;
        var m5 = 3900;
        output[1] = input[1] + output[0];
        output[2] = input[2] + output[1];
        output[3] = input[3] + output[2];
        output[4] = input[4] + output[3];
        var p4 = 2 * input[4] - input[3];
        var p5 = output[4];
        var ip1 = input[4];
        for (var q = 5; q < count; q++) {
            var value = output[q];
            var ip0 = value + ((p4 * m4) >> 9);
            if ((value ^ p4) > 0) {
                m4++;
            } else {
                m4--;
            }
            p4 = 2 * ip0 - ip1;
            output[q] = ip0 + ((p5 * m5) >> 12);
            if ((ip0 ^ p5) > 0) {
                m5++;
            } else {
                m5--;
            }
            p5 = output[q];
            ip1 = ip0;
        }
    }

    static void normal3800(int[] input, int[] output, int count) {
        final var first = 4;
        if (count < 8) {
            System.arraycopy(input, 0, output, 0, count);
            return;
        }
        System.arraycopy(input, 0, output, 0, first);
        int m2 = 64, m3 = 115, m4 = 64, m5 = 740, m6 = 0;
        var p4 = input[first - 1];
        var p3 = (input[first - 1] - input[first - 2]) << 1;
        var p2 = input[first - 1] + ((input[first - 3] - input[first - 2]) << 3);
        var ipp2 = input[first - 2];
        var p7 = 2 * input[first - 1] - input[first - 2];
        var opp = output[first - 1];
        for (var q = 1; q < first; q++) {
            output[q] += output[q - 1];
        }
        for (var q = first; q < count; q++) {
            var i = input[q];
            var o = i + ((p2 * m2 + p3 * m3 + p4 * m4) >> 11);
            if (i > 0) {
                m2 -= ((p2 >> 30) & 2) - 1;
                m3 -= ((p3 >> 28) & 8) - 4;
                m4 -= ((p4 >> 28) & 8) - 4;
            } else if (i < 0) {
                m2 += ((p2 >> 30) & 2) - 1;
                m3 += ((p3 >> 28) & 8) - 4;
                m4 += ((p4 >> 28) & 8) - 4;
            }
            p2 = o + ((ipp2 - p4) << 3);
            p3 = (o - p4) << 1;
            ipp2 = p4;
            p4 = o;
            o += (p7 * m5 - opp * m6) >> 10;
            if (p4 > 0) {
                m5 -= ((p7 >> 29) & 4) - 2;
                m6 += ((opp >> 30) & 2) - 1;
            } else if (p4 < 0) {
                m5 += ((p7 >> 29) & 4) - 2;
                m6 -= ((opp >> 30) & 2) - 1;
            }
            p7 = 2 * o - opp;
            opp = o;
            output[q] = o + ((output[q - 1] * 31) >> 5);
        }
    }

    static void high0000(int[] input, int[] output, int count) {
        if (count < 32) {
            System.arraycopy(input, 0, output, 0, count);
            return;
        }
        System.arraycopy(input, 0, output, 0, 8);
        var m = 0;
        for (var q = 8; q < count; q++) {
            var p = 5 * output[q - 1] - 10 * output[q - 2] + 12 * output[q - 3] - 7 * output[q - 4] + output[q - 5];
            var pw = (p * m) >> 12;
            output[q] = input[q] + pw;
            if (input[q] > 0) {
                m += p > 0 ? 1 : -1;
            } else if (input[q] < 0) {
                m += p > 0 ? -1 : 1;
            }
        }
        System.arraycopy(output, 0, input, 0, 8);
        m = 0;
        for (var q = 8; q < count; q++) {
            var p = 4 * input[q - 1] - 6 * input[q - 2] + 4 * input[q - 3] - input[q - 4];
            var pw = (p * m) >> 12;
            var out = output[q];
            input[q] = out + pw;
            if (out > 0) {
                m += p > 0 ? 2 : -2;
            } else if (out < 0) {
                m += p > 0 ? -2 : 2;
            }
        }
        normal0000(input, output, count);
    }

    static void high3320(int[] input, int[] output, int count) {
        if (count < 8) {
            System.arraycopy(input, 0, output, 0, count);
            return;
        }
        offset(input, output, count, 2, 12);
        offset(output, input, count, 3, 12);
        offset(input, output, count, 4, 12);
        offset(output, input, count, 5, 12);
        offset(input, output, count, 6, 12);
        offset(output, input, count, 7, 12);
        normal3320(input, output, count);
    }

    static void high3600(int[] input, int[] output, int count) {
        if (count < 16) {
            System.arraycopy(input, 0, output, 0, count);
            return;
        }
        System.arraycopy(input, 0, output, 0, 13);
        var bm = new int[13];
        var bp = new int[13];
        int m2 = 64, m3 = 28, m4 = 16;
        var p4 = input[12];
        var p3 = (input[12] - input[11]) << 1;
        var p2 = input[12] + ((input[10] - input[11]) << 3);
        for (var i = 0; i < 13; i++) {
            bp[i] = output[12 - i];
        }
        for (var q = 13; q < count; q++) {
            input[q] = input[q] - 1;
            var op0 = input[q] - ((bp[0] * bm[0]) >> 8) + ((bp[1] * bm[1]) >> 8) - ((bp[2] * bm[2]) >> 8)
                    - ((bp[3] * bm[3]) >> 8) - ((bp[4] * bm[4]) >> 8) - ((bp[5] * bm[5]) >> 8)
                    - ((bp[6] * bm[6]) >> 8) - ((bp[7] * bm[7]) >> 8) - ((bp[8] * bm[8]) >> 8)
                    + ((bp[9] * bm[9]) >> 8) + ((bp[10] * bm[10]) >> 8) + ((bp[11] * bm[11]) >> 8)
                    + ((bp[12] * bm[12]) >> 8);
            if (input[q] > 0) {
                bm[0] -= bp[0] > 0 ? 1 : -1;
                bm[1] += bp[1] >= 0 ? 1 : -1;
                bm[2] -= bp[2] > 0 ? 1 : -1;
                bm[3] -= bp[3] >= 0 ? 1 : -1;
                bm[4] -= bp[4] > 0 ? 1 : -1;
                bm[5] -= bp[5] >= 0 ? 1 : -1;
                bm[6] -= bp[6] > 0 ? 1 : -1;
                bm[7] -= bp[7] >= 0 ? 1 : -1;
                bm[8] -= bp[8] > 0 ? 1 : -1;
                bm[9] += bp[9] >= 0 ? 1 : -1;
                bm[10] += bp[10] > 0 ? 1 : -1;
                bm[11] += bp[11] >= 0 ? 1 : -1;
                bm[12] += bp[12] > 0 ? 1 : -1;
            } else if (input[q] < 0) {
                bm[0] -= bp[0] <= 0 ? 1 : -1;
                bm[1] += bp[1] < 0 ? 1 : -1;
                bm[2] -= bp[2] <= 0 ? 1 : -1;
                bm[3] -= bp[3] < 0 ? 1 : -1;
                bm[4] -= bp[4] <= 0 ? 1 : -1;
                bm[5] -= bp[5] < 0 ? 1 : -1;
                bm[6] -= bp[6] <= 0 ? 1 : -1;
                bm[7] -= bp[7] < 0 ? 1 : -1;
                bm[8] -= bp[8] <= 0 ? 1 : -1;
                bm[9] += bp[9] < 0 ? 1 : -1;
                bm[10] += bp[10] <= 0 ? 1 : -1;
                bm[11] += bp[11] < 0 ? 1 : -1;
                bm[12] += bp[12] <= 0 ? 1 : -1;
            }
            System.arraycopy(bp, 0, bp, 1, 12);
            bp[0] = op0;
            input[q] = op0 + ((p2 * m2) >> 11) + ((p3 * m3) >> 9) + ((p4 * m4) >> 9);
            if (op0 > 0) {
                m2 -= p2 > 0 ? -1 : 1;
                m3 -= p3 > 0 ? -1 : 1;
                m4 -= p4 > 0 ? -1 : 1;
            } else if (op0 < 0) {
                m2 -= p2 > 0 ? 1 : -1;
                m3 -= p3 > 0 ? 1 : -1;
                m4 -= p4 > 0 ? 1 : -1;
            }
            p2 = input[q] + ((input[q - 2] - input[q - 1]) << 3);
            p3 = (input[q] - input[q - 1]) << 1;
            p4 = input[q];
            output[q] = input[q];
        }
        m4 = 370;
        for (var i = 1; i <= 12; i++) {
            output[i] = input[i] + output[i - 1];
        }
        p4 = 2 * input[12] - input[11];
        var p6 = 0;
        var p5 = output[12];
        var m6 = 0;
        var ip1 = input[12];
        for (var q = 13; q < count; q++) {
            var out = output[q];
            var ip0 = out + ((p4 * m4) >> 9) - ((p6 * m6) >> 10);
            if ((out ^ p4) >= 0) {
                m4++;
            } else {
                m4--;
            }
            if ((out ^ p6) >= 0) {
                m6--;
            } else {
                m6++;
            }
            p4 = 2 * ip0 - ip1;
            p6 = ip0;
            output[q] = ip0 + ((p5 * 31) >> 5);
            p5 = output[q];
            ip1 = ip0;
        }
    }

    static void high3700(int[] input, int[] output, int count) {
        final var first = 16;
        if (count < 20) {
            System.arraycopy(input, 0, output, 0, count);
            return;
        }
        System.arraycopy(input, 0, output, 0, first);
        var bm = new int[first];
        int m2 = 64, m3 = 115, m4 = 64, m5 = 740, m6 = 0;
        var p4 = input[first - 1];
        var p3 = (input[first - 1] - input[first - 2]) << 1;
        var p2 = input[first - 1] + ((input[first - 3] - input[first - 2]) << 3);
        var ipp2 = input[first - 2];
        var ipp1 = input[first - 1];
        var p7 = 2 * input[first - 1] - input[first - 2];
        var opp = output[first - 1];
        for (var q = 1; q < first; q++) {
            output[q] += output[q - 1];
        }
        for (var q = first; q < count; q++) {
            var original = input[q] - 1;
            var sum = 0;
            for (var j = 0; j < 16; j++) {
                sum += input[q - 1 - j] * bm[j];
            }
            input[q] = original - (sum >> 8);
            if (original > 0) {
                for (var j = 0; j < 16; j += 2) {
                    bm[j] -= input[q - 1 - j] > 0 ? 1 : -1;
                    bm[j + 1] += (int) (((Integer.toUnsignedLong(input[q - 2 - j]) >>> 30) & 2) - 1);
                }
            } else if (original < 0) {
                for (var j = 0; j < 16; j += 2) {
                    bm[j] -= input[q - 1 - j] <= 0 ? 1 : -1;
                    bm[j + 1] -= (int) (((Integer.toUnsignedLong(input[q - 2 - j]) >>> 30) & 2) - 1);
                }
            }
            var ip = input[q];
            var op = ip + ((p2 * m2 + p3 * m3 + p4 * m4) >> 11);
            if (ip > 0) {
                m2 -= p2 > 0 ? -1 : 1;
                m3 -= p3 > 0 ? -4 : 4;
                m4 -= p4 > 0 ? -4 : 4;
            } else if (ip < 0) {
                m2 -= p2 > 0 ? 1 : -1;
                m3 -= p3 > 0 ? 4 : -4;
                m4 -= p4 > 0 ? 4 : -4;
            }
            p4 = op;
            p2 = p4 + ((ipp2 - ipp1) << 3);
            p3 = (p4 - ipp1) << 1;
            ipp2 = ipp1;
            ipp1 = p4;
            op += (p7 * m5 - opp * m6) >> 10;
            if ((ipp1 ^ p7) >= 0) {
                m5 += 2;
            } else {
                m5 -= 2;
            }
            if ((ipp1 ^ opp) >= 0) {
                m6--;
            } else {
                m6++;
            }
            p7 = 2 * op - opp;
            opp = op;
            output[q] = op + ((output[q - 1] * 31) >> 5);
        }
    }

    static void high3800(int[] input, int[] output, int count) {
        final var first = 16;
        if (count < 20) {
            System.arraycopy(input, 0, output, 0, count);
            return;
        }
        System.arraycopy(input, 0, output, 0, first);
        var bm = new int[first];
        int m2 = 64, m3 = 115, m4 = 64, m5 = 740, m6 = 0;
        var p4 = input[first - 1];
        var p3 = (input[first - 1] - input[first - 2]) << 1;
        var p2 = input[first - 1] + ((input[first - 3] - input[first - 2]) << 3);
        var ipp2 = input[first - 2];
        var p7 = 2 * input[first - 1] - input[first - 2];
        var opp = output[first - 1];
        for (var q = 1; q < first; q++) {
            output[q] += output[q - 1];
        }
        for (var q = first; q < count; q++) {
            var dot = 0;
            var current = input[q];
            var base = q - first;
            if (current > 0) {
                for (var j = 0; j < first; j++) {
                    var value = input[base + j];
                    dot += value * bm[j];
                    bm[j] += ((value >> 30) & 2) - 1;
                }
            } else if (current < 0) {
                for (var j = 0; j < first; j++) {
                    var value = input[base + j];
                    dot += value * bm[j];
                    bm[j] -= ((value >> 30) & 2) - 1;
                }
            } else {
                for (var j = 0; j < first; j++) {
                    dot += input[base + j] * bm[j];
                }
            }
            input[q] -= dot >> 9;
            var ip = input[q];
            var op = ip + ((p2 * m2 + p3 * m3 + p4 * m4) >> 11);
            if (ip > 0) {
                m2 -= ((p2 >> 30) & 2) - 1;
                m3 -= ((p3 >> 28) & 8) - 4;
                m4 -= ((p4 >> 28) & 8) - 4;
            } else if (ip < 0) {
                m2 += ((p2 >> 30) & 2) - 1;
                m3 += ((p3 >> 28) & 8) - 4;
                m4 += ((p4 >> 28) & 8) - 4;
            }
            p2 = op + ((ipp2 - p4) << 3);
            p3 = (op - p4) << 1;
            ipp2 = p4;
            p4 = op;
            op += (p7 * m5 - opp * m6) >> 10;
            if (p4 > 0) {
                m5 -= ((p7 >> 29) & 4) - 2;
                m6 += ((opp >> 30) & 2) - 1;
            } else if (p4 < 0) {
                m5 += ((p7 >> 29) & 4) - 2;
                m6 -= ((opp >> 30) & 2) - 1;
            }
            p7 = 2 * op - opp;
            opp = op;
            output[q] = op + ((output[q - 1] * 31) >> 5);
        }
    }

    private static void extraOffset0000(int[] input, int[] output, int count, long g, int dm, int maxOrder) {
        if (g == 0 || count <= maxOrder) {
            System.arraycopy(input, 0, output, 0, count);
            return;
        }
        System.arraycopy(input, 0, output, 0, maxOrder);
        var gap = (int) g;
        for (var q = maxOrder; q < count; q++) {
            output[q] = dm > 0 ? input[q] + (output[q - gap] >> 3) : input[q] - (output[q - gap] >> 3);
        }
    }

    static void extraHigh0000(int[] input, int[] output, int count, int iterations, long[] a, long[] b) {
        for (var z = iterations; z >= 0; z--) {
            extraOffset0000(input, output, count, b[z], -1, 64);
            extraOffset0000(output, input, count, a[z], 1, 64);
        }
        high0000(input, output, count);
    }

    private static void extraOffset3320(int[] input, int[] output, int count, long g, int dm, int maxOrder) {
        if (g == 0 || count <= maxOrder) {
            System.arraycopy(input, 0, output, 0, count);
            return;
        }
        System.arraycopy(input, 0, output, 0, maxOrder);
        var gap = (int) g;
        var m = 512;
        for (var q = maxOrder; q < count; q++) {
            var previous = output[q - gap];
            if (dm > 0) {
                output[q] = input[q] + ((previous * m) >> 12);
                m += (input[q] ^ previous) > 0 ? 8 : -8;
            } else {
                output[q] = input[q] - ((previous * m) >> 12);
                m += (input[q] ^ previous) > 0 ? -8 : 8;
            }
        }
    }

    static void extraHigh3320(int[] input, int[] output, int count, int iterations, long[] a, long[] b) {
        for (var z = iterations; z >= 0; z--) {
            extraOffset3320(input, output, count, b[z], -1, 32);
            extraOffset3320(output, input, count, a[z], 1, 32);
        }
        high0000(input, output, count);
    }

    private static void extraOffsetPair(int[] input, int[] output, int count, long g1, long g2, int maxOrder) {
        if (g1 == 0 || g2 == 0 || count <= maxOrder) {
            System.arraycopy(input, 0, output, 0, count);
            return;
        }
        System.arraycopy(input, 0, output, 0, maxOrder);
        var gap1 = (int) g1;
        var gap2 = (int) g2;
        var m = 64;
        var m2 = 64;
        for (var q = maxOrder; q < count; q++) {
            var first = output[q - gap1];
            var second = output[q - gap2];
            output[q] = input[q] + ((first * m) >> 9) - ((second * m2) >> 9);
            if ((input[q] ^ first) > 0) {
                m++;
            } else {
                m--;
            }
            if ((input[q] ^ second) > 0) {
                m2--;
            } else {
                m2++;
            }
        }
    }

    /**
     * Applies the offset pairs of the extra high level of versions 3.600 to 3.800, leaving the result in
     * {@code input}.
     */
    private static void extraHighPairs(int[] input, int[] output, int count, int iterations, long[] a, long[] b) {
        for (var z = iterations; z >= 0; ) {
            extraOffsetPair(input, output, count, a[z], b[z], 64);
            z--;
            if (z >= 0) {
                extraOffsetPair(output, input, count, a[z], b[z], 64);
                z--;
            } else {
                System.arraycopy(output, 0, input, 0, count);
                break;
            }
        }
    }

    static void extraHigh3800(int[] input, int[] output, int count, int version, short[] adaptFactor,
                              short[] shorts) {
        var stageElements = version < 3830 ? 128 : 256;
        var stageShift = version < 3830 ? 11 : 12;
        var maxElements = version < 3830 ? 134 : 262;
        var first = version < 3830 ? 128 : 256;
        var stageCShift = version < 3830 ? 10 : 11;
        if (count < maxElements) {
            System.arraycopy(input, 0, output, 0, count);
            return;
        }
        System.arraycopy(input, 0, output, 0, first);
        var bm = new short[256];
        int m2 = 64, m3 = 115, m4 = 64, m5 = 740, m6 = 0;
        var p4 = input[first - 1];
        var p3 = (input[first - 1] - input[first - 2]) << 1;
        var p2 = input[first - 1] + ((input[first - 3] - input[first - 2]) << 3);
        var ipp2 = input[first - 2];
        var p7 = 2 * input[first - 1] - input[first - 2];
        var opp = output[first - 1];
        for (var q = 1; q < first; q++) {
            output[q] += output[q - 1];
        }
        for (var q = 0; q < first; q++) {
            adaptFactor[q] = (short) (((input[q] >> 30) & 2) - 1);
            shorts[q] = (short) input[q];
        }
        var fm = new int[9];
        var fp = new int[9];
        for (var q = first; q < count; q++) {
            if (version >= 3830) {
                var dot = 0;
                fp[0] = input[q];
                if (fp[0] == 0) {
                    for (var j = 8; j >= 1; j--) {
                        dot += fp[j] * fm[j];
                        fp[j] = fp[j - 1];
                    }
                } else if (fp[0] > 0) {
                    for (var j = 8; j >= 1; j--) {
                        dot += fp[j] * fm[j];
                        fm[j] += ((fp[j] >> 30) & 2) - 1;
                        fp[j] = fp[j - 1];
                    }
                } else {
                    for (var j = 8; j >= 1; j--) {
                        dot += fp[j] * fm[j];
                        fm[j] -= ((fp[j] >> 30) & 2) - 1;
                        fp[j] = fp[j - 1];
                    }
                }
                input[q] -= dot >> 9;
            }
            var original = input[q];
            shorts[q] = (short) input[q];
            adaptFactor[q] = (short) (((input[q] >> 30) & 2) - 1);
            input[q] -= conventionalDotProduct(shorts, bm, adaptFactor, q - first, original, stageElements)
                    >> stageShift;
            shorts[q] = (short) input[q];
            adaptFactor[q] = (short) (((input[q] >> 30) & 2) - 1);
            var ip = input[q];
            var op = ip + ((p2 * m2 + p3 * m3 + p4 * m4) >> 11);
            if (ip > 0) {
                m2 -= ((p2 >> 30) & 2) - 1;
                m3 -= ((p3 >> 28) & 8) - 4;
                m4 -= ((p4 >> 28) & 8) - 4;
            } else if (ip < 0) {
                m2 += ((p2 >> 30) & 2) - 1;
                m3 += ((p3 >> 28) & 8) - 4;
                m4 += ((p4 >> 28) & 8) - 4;
            }
            p2 = op + ((ipp2 - p4) << 3);
            p3 = (op - p4) << 1;
            ipp2 = p4;
            p4 = op;
            op += (p7 * m5 - opp * m6) >> stageCShift;
            if (p4 > 0) {
                m5 -= ((p7 >> 29) & 4) - 2;
                m6 += ((opp >> 30) & 2) - 1;
            } else if (p4 < 0) {
                m5 += ((p7 >> 29) & 4) - 2;
                m6 -= ((opp >> 30) & 2) - 1;
            }
            p7 = 2 * op - opp;
            opp = op;
            output[q] = op + ((output[q - 1] * 31) >> 5);
        }
    }

    private static int conventionalDotProduct(short[] input, short[] bm, short[] adapt, int base, int op,
                                              int count) {
        var dot = 0;
        if (op == 0) {
            for (var i = 0; i < count; i++) {
                dot += input[base + i] * bm[i];
            }
        } else if (op > 0) {
            for (var i = 0; i < count; i++) {
                dot += input[base + i] * bm[i];
                bm[i] += adapt[base + i];
            }
        } else {
            for (var i = 0; i < count; i++) {
                dot += input[base + i] * bm[i];
                bm[i] -= adapt[base + i];
            }
        }
        return dot;
    }
}
