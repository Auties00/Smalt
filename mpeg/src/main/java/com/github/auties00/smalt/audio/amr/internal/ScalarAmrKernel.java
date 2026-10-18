package com.github.auties00.smalt.audio.amr.internal;

/**
 * Implements {@link AmrKernel} with plain loops; the reference implementation.
 */
final class ScalarAmrKernel implements AmrKernel {
    /**
     * Creates the kernel.
     */
    ScalarAmrKernel() {
    }

    @Override
    public void fir(short[] x, int xOffset, int[] c, int taps, int count, int[] out, int outOffset) {
        for (var n = 0; n < count; n++) {
            var sum = 0;
            for (var i = 0; i < taps; i++) {
                sum += x[xOffset + n + i] * c[i];
            }
            out[outOffset + n] = sum;
        }
    }

    @Override
    public void firReverse(short[] x, int xOffset, int[] c, int taps, int count, int[] out, int outOffset) {
        for (var n = 0; n < count; n++) {
            var sum = 0;
            for (var i = 0; i < taps; i++) {
                sum += x[xOffset + n - i] * c[i];
            }
            out[outOffset + n] = sum;
        }
    }

    @Override
    public void pairSearch(int kind, short[] block, int[] alp1, short[] psj, short[] ta, short[] tb, int[] out) {
        for (var t = 0; t < 8; t++) {
            var bad = 0;
            var sqk = -1;
            var alpk = 1;
            var jk = 0;
            for (var j = 0; j < 8; j++) {
                var ps2 = (short) (psj[j] + ta[t]);
                var sq = (short) ((ps2 * ps2) >> 15);
                int alp;
                if (kind == 0) {
                    var raw = (((alp1[j] + block[8 * j + t]) >> 2) + tb[t]) >> 1;
                    alp = raw;
                    bad |= raw > 32767 ? 1 : 0;
                } else {
                    var rrShift = kind == 1 ? 12 : kind == 2 ? 11 : 10;
                    var tbShift = kind == 3 ? 13 : 14;
                    alp = (short) ((alp1[j] + (block[8 * j + t] << rrShift) + (tb[t] << tbShift)) >> 16);
                }
                bad |= alp <= 0 ? 1 : 0;
                if (sq * alpk > sqk * alp) {
                    sqk = sq;
                    alpk = alp;
                    jk = j;
                }
            }
            out[t] = sqk;
            out[8 + t] = alpk;
            out[16 + t] = jk;
            out[24 + t] = bad;
        }
    }
}
