/* Ports opencore-amr 0.1.6 (lsp_lsf.cpp, lsp_lsf_tbl.cpp, lsfwt.cpp, reorder.cpp). Copyright PacketVideo; Apache
   License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.amr.internal.nb;

/**
 * Converts between line spectral pairs (cosine domain, Q15) and line spectral frequencies
 * (normalized frequency, Q15 with 16384 at half the sampling rate), weights LSFs for vector
 * quantization and enforces their minimum spacing.
 */
final class Lsf {
    /**
     * Holds the cosine of 64 uniformly spaced frequencies, Q15 ({@code lsp_lsf_tbl.cpp}
     * {@code table}).
     */
    static final short[] TABLE = {
            32767, 32729, 32610, 32413, 32138, 31786, 31357, 30853, 30274, 29622, 28899, 28106,
            27246, 26320, 25330, 24279, 23170, 22006, 20788, 19520, 18205, 16846, 15447, 14010,
            12540, 11039, 9512, 7962, 6393, 4808, 3212, 1608, 0, -1608, -3212, -4808,
            -6393, -7962, -9512, -11039, -12540, -14010, -15447, -16846, -18205, -19520, -20788, -22006,
            -23170, -24279, -25330, -26320, -27246, -28106, -28899, -29622, -30274, -30853, -31357, -31786,
            -32138, -32413, -32610, -32729, -32768
    };

    /**
     * Holds the inverse slopes between consecutive {@link #TABLE} entries, Q12
     * ({@code lsp_lsf_tbl.cpp} {@code slope}).
     */
    static final short[] SLOPE = {
            -26887, -8812, -5323, -3813, -2979, -2444, -2081, -1811, -1608, -1450, -1322, -1219,
            -1132, -1059, -998, -946, -901, -861, -827, -797, -772, -750, -730, -713,
            -699, -687, -677, -668, -662, -657, -654, -652, -652, -654, -657, -662,
            -668, -677, -687, -699, -713, -730, -750, -772, -797, -827, -861, -901,
            -946, -998, -1059, -1132, -1219, -1322, -1450, -1608, -1811, -2081, -2444, -2979,
            -3813, -5323, -8812, -26887
    };

    /**
     * Prevents instantiation.
     */
    private Lsf() {
    }

    /**
     * Converts LSFs to LSPs by table interpolation ({@code Lsf_lsp}).
     *
     * <p>An LSF of 16384 or more, which only out-of-specification decoder parameters produce,
     * converts to the last table value.
     *
     * @implNote This implementation adds that upper bound: the reference interpolates between
     * {@code table[ind]} and {@code table[ind + 1]} without a range check and reads past its
     * 65-entry table there. Values below 16384 convert exactly as in the reference.
     *
     * @param lsf the LSFs
     * @param lsfOffset the index of the first LSF
     * @param lsp receives the LSPs
     * @param lspOffset the index of the first LSP
     * @param m the number of values
     */
    static void lsfLsp(short[] lsf, int lsfOffset, short[] lsp, int lspOffset, int m) {
        for (var i = 0; i < m; i++) {
            var value = lsf[lsfOffset + i];
            var ind = (short) (value >> 8);
            var offset = (short) (value & 0xff);
            if (ind >= 64) {
                lsp[lspOffset + i] = TABLE[64];
                continue;
            }
            var tmp = ((TABLE[ind + 1] - TABLE[ind]) * offset) >> 8;
            lsp[lspOffset + i] = (short) (TABLE[ind] + (short) tmp);
        }
    }

    /**
     * Converts LSPs to LSFs by table search and interpolation ({@code Lsp_lsf}).
     *
     * @param lsp the LSPs, in decreasing order
     * @param lspOffset the index of the first LSP
     * @param lsf receives the LSFs
     * @param lsfOffset the index of the first LSF
     * @param m the number of values
     */
    static void lspLsf(short[] lsp, int lspOffset, short[] lsf, int lsfOffset, int m) {
        var ind = 63;
        for (var i = m - 1; i >= 0; i--) {
            var temp = lsp[lspOffset + i];
            while (TABLE[ind] < temp) {
                ind--;
            }
            var tmp = (temp - TABLE[ind]) * SLOPE[ind];
            tmp = (tmp + 0x800) >> 12;
            lsf[lsfOffset + i] = (short) ((short) tmp + (ind << 8));
        }
    }

    /**
     * Computes the vector quantization weights of 10 LSFs from their spacing ({@code Lsf_wt}).
     *
     * @param lsf the 10 LSFs
     * @param lsfOffset the index of the first LSF
     * @param wf receives the 10 weights
     * @param wfOffset the index of the first weight
     */
    static void lsfWt(short[] lsf, int lsfOffset, short[] wf, int wfOffset) {
        wf[wfOffset] = lsf[lsfOffset + 1];
        for (var k = 0; k < 8; k++) {
            wf[wfOffset + 1 + k] = (short) (lsf[lsfOffset + 2 + k] - lsf[lsfOffset + k]);
        }
        wf[wfOffset + 9] = (short) (16384 - lsf[lsfOffset + 8]);
        for (var i = 0; i < 10; i++) {
            var weight = wf[wfOffset + i];
            var temp = (short) (weight - 1843);
            if (temp > 0) {
                temp = (short) ((temp * 6242) >> 15);
                weight = (short) (1843 - temp);
            } else {
                temp = (short) ((weight * 28160) >> 15);
                weight = (short) (3427 - temp);
            }
            wf[wfOffset + i] = (short) (weight << 3);
        }
    }

    /**
     * Enforces a minimum distance between consecutive LSFs, in place ({@code Reorder_lsf}).
     *
     * @param lsf the LSFs
     * @param offset the index of the first LSF
     * @param minDist the minimum distance
     * @param n the number of values
     */
    static void reorderLsf(short[] lsf, int offset, short minDist, int n) {
        var lsfMin = minDist;
        for (var i = 0; i < n; i++) {
            if (lsf[offset + i] < lsfMin) {
                lsf[offset + i] = lsfMin;
                lsfMin = (short) (lsfMin + minDist);
            } else {
                lsfMin = (short) (lsf[offset + i] + minDist);
            }
        }
    }
}
