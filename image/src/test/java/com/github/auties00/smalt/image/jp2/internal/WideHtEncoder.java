/* Ports OpenJPH 0.21.3 (src/core/coding/ojph_block_encoder.cpp, ojph_encode_codeblock32), through HtEncoder.
   Copyright (c) 2019, Aous Naman; Copyright (c) 2019, Kakadu Software Pty Ltd, Australia; Copyright (c) 2019, The
   University of New South Wales, Sydney, Australia; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jp2.internal;

import java.math.BigInteger;
import java.util.Arrays;

/**
 * Codes an HT set of a code-block whose magnitudes are up to 76 bits wide, the 74 magnitude bit-planes a
 * region-of-interest shift reaches (ISO/IEC 15444-15 8.7.3), which {@link HtEncoder} cannot hold.
 *
 * <p>The HT cleanup pass is {@link HtEncoder}'s with arbitrary-precision magnitudes; the HT SigProp and HT MagRef
 * passes depend only on the significance the cleanup pass finds, the bit below its plane and the signs, so they are
 * coded by {@link HtEncoder} from a proxy block holding just those, which keeps the test independent of
 * {@link HtDecoder} for every pass.
 */
final class WideHtEncoder {
    private static final int[] MEL_EXPONENTS = {0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 4, 5};

    private final BigInteger[] magnitudes;
    private final boolean[] negative;
    private final int width;
    private final int height;
    private final boolean causal;

    private final int[] exponents = new int[516];
    private final int[] contexts = new int[516];
    private final int[] eq = new int[8];
    private final BigInteger[] signs = new BigInteger[8];
    private byte[] magSgn;
    private final byte[] mel = new byte[4096];
    private final byte[] vlc = new byte[8192];
    private int melPosition;
    private int melTmp;
    private int melRemaining;
    private int melRun;
    private int melK;
    private int melThreshold;
    private int vlcPosition;
    private int vlcUsed;
    private int vlcTmp;
    private boolean vlcAbove;
    private int msPosition;
    private int msMax;
    private int msUsed;
    private int msTmp;

    private byte[] cleanup;
    private byte[] refinement;
    private int sigPropLength;

    /**
     * Holds a code-block's magnitudes and signs.
     *
     * @param magnitudes the magnitudes, row-major, non-negative
     * @param negative the signs, row-major
     * @param width the code-block width
     * @param height the code-block height
     * @param causal whether vertically causal context formation is selected
     */
    WideHtEncoder(BigInteger[] magnitudes, boolean[] negative, int width, int height, boolean causal) {
        this.magnitudes = magnitudes;
        this.negative = negative;
        this.width = width;
        this.height = height;
        this.causal = causal;
        this.magSgn = new byte[width * height * 12 + 64];
    }

    /** Codes the cleanup pass at a plane and, when asked, the SigProp and MagRef passes of the plane below. */
    void encode(int plane, boolean refine) {
        cleanup(plane);
        refinement = new byte[0];
        sigPropLength = 0;
        if (refine) {
            var proxy = new int[width * height];
            for (var i = 0; i < proxy.length; i++) {
                var value = (magnitudes[i].shiftRight(plane).signum() != 0 ? 2 : 0)
                        | (magnitudes[i].testBit(plane - 1) ? 1 : 0);
                proxy[i] = negative[i] ? -value : value;
            }
            var coder = new HtEncoder();
            coder.load(proxy, null, 0, width, width, height, 1.0f, true, 1.0, causal);
            coder.encode(1, true);
            var start = coder.cleanupLength();
            sigPropLength = coder.sigPropLength();
            refinement = Arrays.copyOfRange(coder.bytes(), start, start + sigPropLength + coder.magRefLength());
        }
    }

    byte[] cleanupBytes() {
        return cleanup;
    }

    byte[] refinementBytes() {
        return refinement;
    }

    int sigPropLength() {
        return sigPropLength;
    }

    private void cleanup(int plane) {
        melPosition = 0;
        melRemaining = 8;
        melTmp = 0;
        melRun = 0;
        melK = 0;
        melThreshold = 1;
        vlcPosition = 1;
        vlc[vlc.length - 1] = (byte) 0xFF;
        vlcUsed = 4;
        vlcTmp = 0xF;
        vlcAbove = true;
        msPosition = 0;
        msMax = 8;
        msUsed = 0;
        msTmp = 0;
        var e = exponents;
        var cx = contexts;
        Arrays.fill(e, 0);
        Arrays.fill(cx, 0);
        var s = signs;
        var w = width;
        var h = height;
        var cq0 = 0;
        var lep = 0;
        var lcxp = 0;
        for (var x = 0; x < w; x += 4) {
            var rho0 = 0;
            var rho1 = 0;
            Arrays.fill(eq, 0);
            Arrays.fill(s, BigInteger.ZERO);
            var base = x;
            rho0 |= sample(base, plane, 0);
            rho0 |= h > 1 ? sample(base + w, plane, 1) << 1 : 0;
            if (x + 1 < w) {
                rho0 |= sample(base + 1, plane, 2) << 2;
                rho0 |= h > 1 ? sample(base + 1 + w, plane, 3) << 3 : 0;
            }
            var emax0 = Math.max(Math.max(eq[0], eq[1]), Math.max(eq[2], eq[3]));
            var uq0Bound = Math.max(emax0, 1);
            var uq0 = uq0Bound - 1;
            var uq1 = 0;
            var eps0 = uq0 > 0 ? epsilon(0, emax0) : 0;
            e[lep] = Math.max(e[lep], eq[1]);
            lep++;
            e[lep] = eq[3];
            cx[lcxp] |= (rho0 & 2) >> 1;
            lcxp++;
            cx[lcxp] = (rho0 & 8) >> 3;
            var tuple0 = HtTables.ENCODE_0[(cq0 << 8) + (rho0 << 4) + eps0];
            vlcEncode(tuple0 >> 8, (tuple0 >> 4) & 7);
            if (cq0 == 0) {
                melEncode(rho0 != 0);
            }
            magnitudes(rho0, uq0Bound, tuple0, 0);
            if (x + 2 < w) {
                rho1 |= sample(base + 2, plane, 4);
                rho1 |= h > 1 ? sample(base + 2 + w, plane, 5) << 1 : 0;
                if (x + 3 < w) {
                    rho1 |= sample(base + 3, plane, 6) << 2;
                    rho1 |= h > 1 ? sample(base + 3 + w, plane, 7) << 3 : 0;
                }
                var cq1 = (rho0 >> 1) | (rho0 & 1);
                var emax1 = Math.max(Math.max(eq[4], eq[5]), Math.max(eq[6], eq[7]));
                var uq1Bound = Math.max(emax1, 1);
                uq1 = uq1Bound - 1;
                var eps1 = uq1 > 0 ? epsilon(4, emax1) : 0;
                e[lep] = Math.max(e[lep], eq[5]);
                lep++;
                e[lep] = eq[7];
                cx[lcxp] |= (rho1 & 2) >> 1;
                lcxp++;
                cx[lcxp] = (rho1 & 8) >> 3;
                var tuple1 = HtTables.ENCODE_0[(cq1 << 8) + (rho1 << 4) + eps1];
                vlcEncode(tuple1 >> 8, (tuple1 >> 4) & 7);
                if (cq1 == 0) {
                    melEncode(rho1 != 0);
                }
                magnitudes(rho1, uq1Bound, tuple1, 4);
            }
            if (uq0 > 0 && uq1 > 0) {
                melEncode(Math.min(uq0, uq1) > 2);
            }
            if (uq0 > 2 && uq1 > 2) {
                uvlcPrefix(uq0 - 2);
                uvlcPrefix(uq1 - 2);
                uvlcSuffix(uq0 - 2);
                uvlcSuffix(uq1 - 2);
                uvlcExtension(uq0 - 2);
                uvlcExtension(uq1 - 2);
            } else if (uq0 > 2 && uq1 > 0) {
                uvlcPrefix(uq0);
                vlcEncode(uq1 - 1, 1);
                uvlcSuffix(uq0);
                uvlcExtension(uq0);
            } else {
                uvlcPrefix(uq0);
                uvlcPrefix(uq1);
                uvlcSuffix(uq0);
                uvlcSuffix(uq1);
                uvlcExtension(uq0);
                uvlcExtension(uq1);
            }
            cq0 = (rho1 >> 1) | (rho1 & 1);
        }
        e[lep + 1] = 0;
        for (var y = 2; y < h; y += 2) {
            lep = 0;
            var maxE = Math.max(e[0], e[1]) - 1;
            e[0] = 0;
            lcxp = 0;
            cq0 = cx[0] + (cx[1] << 2);
            cx[0] = 0;
            var row = y * w;
            var below = y + 1 < h;
            for (var x = 0; x < w; x += 4) {
                var rho0 = 0;
                var rho1 = 0;
                Arrays.fill(eq, 0);
                Arrays.fill(s, BigInteger.ZERO);
                var base = row + x;
                rho0 |= sample(base, plane, 0);
                rho0 |= below ? sample(base + w, plane, 1) << 1 : 0;
                if (x + 1 < w) {
                    rho0 |= sample(base + 1, plane, 2) << 2;
                    rho0 |= below ? sample(base + 1 + w, plane, 3) << 3 : 0;
                }
                var emax0 = Math.max(Math.max(eq[0], eq[1]), Math.max(eq[2], eq[3]));
                var kappa = (rho0 & (rho0 - 1)) != 0 ? Math.max(1, maxE) : 1;
                var uq0Bound = Math.max(emax0, kappa);
                var uq0 = uq0Bound - kappa;
                var uq1 = 0;
                var eps0 = uq0 > 0 ? epsilon(0, emax0) : 0;
                e[lep] = Math.max(e[lep], eq[1]);
                lep++;
                maxE = Math.max(e[lep], e[lep + 1]) - 1;
                e[lep] = eq[3];
                cx[lcxp] |= (rho0 & 2) >> 1;
                lcxp++;
                var cq1 = cx[lcxp] + (cx[lcxp + 1] << 2);
                cx[lcxp] = (rho0 & 8) >> 3;
                var tuple0 = HtTables.ENCODE_1[(cq0 << 8) + (rho0 << 4) + eps0];
                vlcEncode(tuple0 >> 8, (tuple0 >> 4) & 7);
                if (cq0 == 0) {
                    melEncode(rho0 != 0);
                }
                magnitudes(rho0, uq0Bound, tuple0, 0);
                if (x + 2 < w) {
                    rho1 |= sample(base + 2, plane, 4);
                    rho1 |= below ? sample(base + 2 + w, plane, 5) << 1 : 0;
                    if (x + 3 < w) {
                        rho1 |= sample(base + 3, plane, 6) << 2;
                        rho1 |= below ? sample(base + 3 + w, plane, 7) << 3 : 0;
                    }
                    var emax1 = Math.max(Math.max(eq[4], eq[5]), Math.max(eq[6], eq[7]));
                    kappa = (rho1 & (rho1 - 1)) != 0 ? Math.max(1, maxE) : 1;
                    cq1 |= ((rho0 & 4) >> 1) | ((rho0 & 8) >> 2);
                    var uq1Bound = Math.max(emax1, kappa);
                    uq1 = uq1Bound - kappa;
                    var eps1 = uq1 > 0 ? epsilon(4, emax1) : 0;
                    e[lep] = Math.max(e[lep], eq[5]);
                    lep++;
                    maxE = Math.max(e[lep], e[lep + 1]) - 1;
                    e[lep] = eq[7];
                    cx[lcxp] |= (rho1 & 2) >> 1;
                    lcxp++;
                    cq0 = cx[lcxp] + (cx[lcxp + 1] << 2);
                    cx[lcxp] = (rho1 & 8) >> 3;
                    var tuple1 = HtTables.ENCODE_1[(cq1 << 8) + (rho1 << 4) + eps1];
                    vlcEncode(tuple1 >> 8, (tuple1 >> 4) & 7);
                    if (cq1 == 0) {
                        melEncode(rho1 != 0);
                    }
                    magnitudes(rho1, uq1Bound, tuple1, 4);
                }
                uvlcPrefix(uq0);
                uvlcPrefix(uq1);
                uvlcSuffix(uq0);
                uvlcSuffix(uq1);
                uvlcExtension(uq0);
                uvlcExtension(uq1);
                cq0 |= ((rho1 & 4) >> 1) | ((rho1 & 8) >> 2);
            }
        }
        terminateMelVlc();
        msTerminate();
        var length = msPosition + melPosition + vlcPosition;
        var out = new byte[length];
        System.arraycopy(magSgn, 0, out, 0, msPosition);
        System.arraycopy(mel, 0, out, msPosition, melPosition);
        System.arraycopy(vlc, vlc.length - vlcPosition, out, msPosition + melPosition, vlcPosition);
        var suffix = melPosition + vlcPosition;
        out[length - 1] = (byte) (suffix >> 4);
        out[length - 2] = (byte) ((out[length - 2] & 0xF0) | (suffix & 0xF));
        cleanup = out;
    }

    private int sample(int index, int plane, int slot) {
        var mu = magnitudes[index].shiftRight(plane);
        if (mu.signum() == 0) {
            return 0;
        }
        var v = mu.shiftLeft(1).subtract(BigInteger.ONE);
        eq[slot] = v.bitLength();
        signs[slot] = v.subtract(BigInteger.ONE).add(negative[index] ? BigInteger.ONE : BigInteger.ZERO);
        return 1;
    }

    private int epsilon(int first, int max) {
        return (eq[first] == max ? 1 : 0) | (eq[first + 1] == max ? 2 : 0) | (eq[first + 2] == max ? 4 : 0)
                | (eq[first + 3] == max ? 8 : 0);
    }

    private void magnitudes(int rho, int bound, int tuple, int first) {
        for (var i = 0; i < 4; i++) {
            if ((rho & (1 << i)) != 0) {
                var m = bound - ((tuple >> i) & 1);
                var value = signs[first + i];
                for (var done = 0; done < m; done += 16) {
                    var n = Math.min(16, m - done);
                    msEncode(value.shiftRight(done).intValue() & ((1 << n) - 1), n);
                }
            }
        }
    }

    private void uvlcPrefix(int u) {
        var code = HtTables.UVLC[u];
        vlcEncode(code & 0x7, (code >> 3) & 0x3);
    }

    private void uvlcSuffix(int u) {
        var code = HtTables.UVLC[u];
        vlcEncode((code >> 5) & 0x1F, (code >> 10) & 0x7);
    }

    private void uvlcExtension(int u) {
        var code = HtTables.UVLC[u];
        vlcEncode((code >> 13) & 0xF, (code >> 17) & 0x7);
    }

    private void melEmit(int bit) {
        melTmp = (melTmp << 1) + bit;
        melRemaining--;
        if (melRemaining == 0) {
            mel[melPosition++] = (byte) melTmp;
            melRemaining = melTmp == 0xFF ? 7 : 8;
            melTmp = 0;
        }
    }

    private void melEncode(boolean bit) {
        if (!bit) {
            melRun++;
            if (melRun >= melThreshold) {
                melEmit(1);
                melRun = 0;
                melK = Math.min(12, melK + 1);
                melThreshold = 1 << MEL_EXPONENTS[melK];
            }
        } else {
            melEmit(0);
            var t = MEL_EXPONENTS[melK];
            while (t > 0) {
                melEmit((melRun >> --t) & 1);
            }
            melRun = 0;
            melK = Math.max(0, melK - 1);
            melThreshold = 1 << MEL_EXPONENTS[melK];
        }
    }

    private void vlcEncode(int codeword, int length) {
        while (length > 0) {
            var available = 8 - (vlcAbove ? 1 : 0) - vlcUsed;
            var t = Math.min(available, length);
            vlcTmp |= (codeword & ((1 << t) - 1)) << vlcUsed;
            vlcUsed += t;
            available -= t;
            length -= t;
            codeword >>>= t;
            if (available == 0) {
                if (vlcAbove && vlcTmp != 0x7F) {
                    vlcAbove = false;
                    continue;
                }
                vlc[vlc.length - 1 - vlcPosition] = (byte) vlcTmp;
                vlcPosition++;
                vlcAbove = vlcTmp > 0x8F;
                vlcTmp = 0;
                vlcUsed = 0;
            }
        }
    }

    private void terminateMelVlc() {
        if (melRun > 0) {
            melEmit(1);
        }
        melTmp = melTmp << melRemaining;
        var melMask = (0xFF << melRemaining) & 0xFF;
        var vlcMask = 0xFF >> (8 - vlcUsed);
        if ((melMask | vlcMask) == 0) {
            return;
        }
        var fuse = melTmp | vlcTmp;
        if ((((fuse ^ melTmp) & melMask) | ((fuse ^ vlcTmp) & vlcMask)) == 0 && fuse != 0xFF && vlcPosition > 1) {
            mel[melPosition++] = (byte) fuse;
        } else {
            mel[melPosition++] = (byte) melTmp;
            vlc[vlc.length - 1 - vlcPosition] = (byte) vlcTmp;
            vlcPosition++;
        }
    }

    private void msEncode(int codeword, int length) {
        while (length > 0) {
            var t = Math.min(msMax - msUsed, length);
            msTmp |= (codeword & ((1 << t) - 1)) << msUsed;
            msUsed += t;
            codeword >>>= t;
            length -= t;
            if (msUsed >= msMax) {
                magSgn[msPosition++] = (byte) msTmp;
                msMax = (msTmp & 0xFF) == 0xFF ? 7 : 8;
                msTmp = 0;
                msUsed = 0;
            }
        }
    }

    private void msTerminate() {
        if (msUsed != 0) {
            var t = msMax - msUsed;
            msTmp |= (0xFF & ((1 << t) - 1)) << msUsed;
            msUsed += t;
            if ((msTmp & 0xFF) != 0xFF) {
                magSgn[msPosition++] = (byte) msTmp;
            }
        } else if (msMax == 7) {
            msPosition--;
        }
    }
}
