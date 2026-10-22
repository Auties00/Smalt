/* Ports OpenJPH 0.21.3 (src/core/coding/ojph_block_encoder.cpp, ojph_encode_codeblock32). Copyright (c) 2019,
   Aous Naman; Copyright (c) 2019, Kakadu Software Pty Ltd, Australia; Copyright (c) 2019, The University of New
   South Wales, Sydney, Australia; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jp2.internal;

import java.util.Arrays;

/**
 * Codes the quantized coefficients of a code-block with the high-throughput block coder (ISO/IEC 15444-15): one HT
 * set, an HT cleanup pass at a chosen bit-plane, optionally followed by the HT SigProp and HT MagRef passes of the
 * bit-plane below.
 *
 * <p>The coefficients are loaded once with {@link #load}; each {@code encode} call then codes them at a bit-plane
 * and reports the weighted distortion reduction of each pass, computed exactly from the reconstruction a decoder
 * makes. An instance is reused for many code-blocks but is not thread-safe.
 *
 * @implNote This implementation follows OpenJPH's {@code ojph_encode_codeblock32} for the cleanup pass. OpenJPH has
 * no SigProp or MagRef encoder; those passes are coded as the exact inverse of {@link HtDecoder}'s, in its scan
 * order, with their byte-streams packed one after the other in the refinement segment.
 */
final class HtEncoder {
    /**
     * The MEL exponents of each state (Table 2 of ISO/IEC 15444-15).
     */
    private static final int[] MEL_EXPONENTS = {0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 4, 5};

    /**
     * The largest code-block area.
     */
    private static final int MAX_AREA = 4096;

    /**
     * The SigProp membership bits a sample of each stripe row adds to its later neighbors when it becomes
     * significant: the sample below, and the right neighbors.
     */
    private static final int[] PROPAGATION = {0x32, 0x74, 0xE8, 0xC0};

    /**
     * The coefficients, row-major: the sign in bit 31 and the magnitude with {@link #fraction} fractional bits
     * below.
     */
    private final int[] values = new int[MAX_AREA];

    /**
     * The number of fractional bits of {@link #values}: {@link Tier1Tables#NMSEDEC_FRACBITS}, or fewer when the
     * largest magnitude of the code-block would not fit in 31 bits with them.
     */
    private int fraction;

    /**
     * The cleanup significance of each four-row stripe, eight columns per word as {@link HtDecoder} lays it out,
     * with one spare word per stripe.
     */
    private final int[] sigma = new int[1032 + 129];

    /**
     * The SigProp membership words, laid out as {@link #sigma}.
     */
    private final int[] membership = new int[1032 + 129];

    /**
     * The largest exponent of each pair of columns of the quad row above.
     */
    private final int[] exponents = new int[516];

    /**
     * The significance context bits of each pair of columns of the quad row above.
     */
    private final int[] contexts = new int[516];

    /**
     * The MagSgn bytes.
     */
    private byte[] magSgn = new byte[MAX_AREA * 5 + 64];

    /**
     * The MEL bytes.
     */
    private final byte[] mel = new byte[1024];

    /**
     * The VLC bytes, written backward from the end.
     */
    private final byte[] vlc = new byte[4096];

    /**
     * The SigProp bytes.
     */
    private final byte[] sigProp = new byte[MAX_AREA + 64];

    /**
     * The MagRef bytes, written backward from the end.
     */
    private final byte[] magRef = new byte[MAX_AREA + 64];

    /**
     * The SigProp writer.
     */
    private final BitSink sigPropSink = new BitSink();

    /**
     * The MagRef writer.
     */
    private final ReverseSink magRefSink = new ReverseSink();

    /**
     * The exponents of the samples of the current quad pair.
     */
    private final int[] eq = new int[8];

    /**
     * The MagSgn values of the samples of the current quad pair.
     */
    private final int[] signs = new int[8];

    /**
     * Whether vertically causal context formation is selected.
     */
    private boolean causal;

    /**
     * The coded bytes of the last {@code encode}: the cleanup segment followed by the refinement segment.
     */
    private byte[] out = new byte[MAX_AREA * 6 + 256];

    /**
     * The code-block width.
     */
    private int width;

    /**
     * The code-block height.
     */
    private int height;

    /**
     * The number of magnitude bit-planes of the largest coefficient.
     */
    private int bitPlanes;

    /**
     * Whether coefficients are integers reconstructed without a midpoint offset (the 5-3 wavelet).
     */
    private boolean reversible;

    /**
     * The weight converting a squared error in quantization steps to the allocation's distortion unit.
     */
    private double scale;

    /**
     * The length of the last cleanup segment.
     */
    private int cleanupLength;

    /**
     * The length of the SigProp bytes of the last refinement segment.
     */
    private int sigPropLength;

    /**
     * The length of the MagRef bytes of the last refinement segment.
     */
    private int magRefLength;

    /**
     * The cumulative weighted distortion reduction after each pass of the last {@code encode}.
     */
    private final double[] distortions = new double[3];

    /**
     * The state of the MEL coder: position, pending bits, free bits, run, state index and threshold.
     */
    private int melPosition;

    /**
     * The bits of the MEL byte being filled.
     */
    private int melTmp;

    /**
     * The number of free bits of the MEL byte being filled.
     */
    private int melRemaining;

    /**
     * The length of the current MEL run of zero events.
     */
    private int melRun;

    /**
     * The MEL state index, 0 to 12.
     */
    private int melK;

    /**
     * The run length at which a one is emitted.
     */
    private int melThreshold;

    /**
     * The number of VLC bytes written.
     */
    private int vlcPosition;

    /**
     * The number of bits used in the VLC byte being filled.
     */
    private int vlcUsed;

    /**
     * The bits of the VLC byte being filled.
     */
    private int vlcTmp;

    /**
     * Whether the last VLC byte written is above {@code 0x8F}.
     */
    private boolean vlcAbove;

    /**
     * The number of MagSgn bytes written.
     */
    private int msPosition;

    /**
     * The number of bits the MagSgn byte being filled can hold.
     */
    private int msMax;

    /**
     * The number of bits used in the MagSgn byte being filled.
     */
    private int msUsed;

    /**
     * The bits of the MagSgn byte being filled.
     */
    private int msTmp;

    /**
     * Constructs an encoder.
     */
    HtEncoder() {
    }

    /**
     * Loads the coefficients of a code-block.
     *
     * @param samples the integer coefficients of the 5-3 wavelet, or {@code null}
     * @param floats the floating-point coefficients of the 9-7 wavelet, or {@code null}
     * @param offset the index of the block's first coefficient
     * @param stride the row stride of the coefficients
     * @param width the code-block width
     * @param height the code-block height
     * @param stepSize the quantization step size
     * @param reversible whether the 5-3 wavelet is used
     * @param scale the weight of a squared error of one quantization step
     * @param causal whether vertically causal context formation is selected
     * @return the number of magnitude bit-planes of the largest coefficient, 0 when every one is zero
     */
    int load(int[] samples, float[] floats, int offset, int stride, int width, int height, float stepSize,
             boolean reversible, double scale, boolean causal) {
        this.causal = causal;
        this.width = width;
        this.height = height;
        this.reversible = reversible;
        this.scale = scale;
        var max = quantize(samples, floats, offset, stride, stepSize, Tier1Tables.NMSEDEC_FRACBITS);
        fraction = Tier1Tables.NMSEDEC_FRACBITS;
        var excess = 64 - Long.numberOfLeadingZeros(max) - 31;
        if (excess > 0) {
            fraction = Math.max(0, fraction - excess);
            max = quantize(samples, floats, offset, stride, stepSize, fraction);
        }
        var planes = max == 0 ? 0 : 64 - Long.numberOfLeadingZeros(max) - fraction;
        bitPlanes = Math.max(planes, 0);
        return bitPlanes;
    }

    /**
     * Quantizes the coefficients of the loaded code-block into {@link #values}, with a number of fractional bits,
     * as {@link Tier1Encoder} quantizes them.
     *
     * @param samples the integer coefficients of the 5-3 wavelet, or {@code null}
     * @param floats the floating-point coefficients of the 9-7 wavelet, or {@code null}
     * @param offset the index of the block's first coefficient
     * @param stride the row stride of the coefficients
     * @param stepSize the quantization step size
     * @param bits the number of fractional bits
     * @return the largest magnitude, which {@link #values} holds saturated to 31 bits
     */
    private long quantize(int[] samples, float[] floats, int offset, int stride, float stepSize, int bits) {
        var max = 0L;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var index = offset + y * stride + x;
                var value = reversible ? (long) samples[index] << bits
                        : (long) Math.rint((floats[index] / stepSize) * (1 << bits));
                var magnitude = Math.abs(value);
                max = Math.max(max, magnitude);
                var stored = (int) Math.min(magnitude, Integer.MAX_VALUE);
                values[y * width + x] = value < 0 ? stored | 0x80000000 : stored;
            }
        }
        return max;
    }

    /**
     * Returns the coded bytes of the last {@code encode}.
     *
     * @return the cleanup segment followed by the refinement segment, valid until the next call
     */
    byte[] bytes() {
        return out;
    }

    /**
     * Returns the length of the last cleanup segment.
     *
     * @return the length in bytes
     */
    int cleanupLength() {
        return cleanupLength;
    }

    /**
     * Returns the length of the SigProp part of the last refinement segment.
     *
     * @return the length in bytes
     */
    int sigPropLength() {
        return sigPropLength;
    }

    /**
     * Returns the length of the MagRef part of the last refinement segment.
     *
     * @return the length in bytes
     */
    int magRefLength() {
        return magRefLength;
    }

    /**
     * Returns the cumulative weighted distortion reduction after a pass of the last {@code encode}.
     *
     * @param pass the pass, 0 for the cleanup pass
     * @return the reduction
     */
    double distortion(int pass) {
        return distortions[pass];
    }

    /**
     * Codes the cleanup pass at a bit-plane and, when asked, the SigProp and MagRef passes of the bit-plane below.
     *
     * @param plane the least significant bit-plane the cleanup pass codes, from 0 to the loaded bit-plane count minus
     *              one; at least 1 with refinement
     * @param refine whether the SigProp and MagRef passes are coded
     */
    void encode(int plane, boolean refine) {
        cleanup(plane);
        var total = cleanupLength;
        distortions[0] = cleanupDistortion(plane);
        sigPropLength = 0;
        magRefLength = 0;
        if (refine) {
            refinement(plane);
            if (out.length < total + sigPropLength + magRefLength) {
                out = Arrays.copyOf(out, total + sigPropLength + magRefLength + 256);
            }
            System.arraycopy(sigProp, 0, out, total, sigPropLength);
            System.arraycopy(magRef, magRef.length - magRefLength, out, total + sigPropLength, magRefLength);
        }
    }

    /**
     * Returns the reconstruction of a magnitude decoded down to a bit-plane, in the coefficients' fixed-point unit.
     *
     * @param magnitude the integer magnitude
     * @param plane the least significant bit-plane decoded
     * @return the reconstructed magnitude, 0 when nothing above the bit-plane is set
     */
    private long reconstruction(int magnitude, int plane) {
        var mu = magnitude >>> plane;
        if (mu == 0) {
            return 0;
        }
        long value = (long) mu << plane;
        long r;
        if (reversible) {
            r = plane > 0 ? (value << 1) + (1L << plane) : value << 1;
        } else {
            r = (value << 1) + (1L << plane);
        }
        return fraction > 0 ? r << (fraction - 1) : r >> 1;
    }

    /**
     * Returns the distortion reduction of one coefficient reconstructed at a value.
     *
     * @param value the fixed-point magnitude
     * @param reconstruction the fixed-point reconstruction
     * @return the reduction, in the allocation's unit
     */
    private double gain(int value, long reconstruction) {
        var error = value - reconstruction;
        var reduction = (double) value * value - (double) error * error;
        return reduction * scale / (double) (1L << (2 * fraction));
    }

    /**
     * Returns the distortion reduction of the cleanup pass at a bit-plane.
     *
     * @param plane the least significant bit-plane coded
     * @return the reduction
     */
    private double cleanupDistortion(int plane) {
        var total = 0.0;
        var size = width * height;
        for (var i = 0; i < size; i++) {
            var value = values[i] & 0x7FFFFFFF;
            var magnitude = value >>> fraction;
            if ((magnitude >>> plane) != 0) {
                total += gain(value, reconstruction(magnitude, plane));
            }
        }
        return total;
    }

    /**
     * Codes the HT cleanup pass at a bit-plane into {@link #out}.
     *
     * @param plane the least significant bit-plane coded
     */
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
        var shift = plane + fraction;
        var e = exponents;
        var cx = contexts;
        Arrays.fill(e, 0);
        Arrays.fill(cx, 0);
        var eq = this.eq;
        var s = signs;
        var w = width;
        var h = height;
        var cq0 = 0;
        var lep = 0;
        var lcxp = 0;
        for (var x = 0; x < w; x += 4) {
            var rho0 = 0;
            var rho1 = 0;
            var emax0 = 0;
            var emax1 = 0;
            Arrays.fill(eq, 0);
            Arrays.fill(s, 0);
            var base = x;
            rho0 |= sample(base, shift, eq, s, 0);
            rho0 |= h > 1 ? sample(base + w, shift, eq, s, 1) << 1 : 0;
            if (x + 1 < w) {
                rho0 |= sample(base + 1, shift, eq, s, 2) << 2;
                rho0 |= h > 1 ? sample(base + 1 + w, shift, eq, s, 3) << 3 : 0;
            }
            emax0 = Math.max(Math.max(eq[0], eq[1]), Math.max(eq[2], eq[3]));
            var uq0Bound = Math.max(emax0, 1);
            var uq0 = uq0Bound - 1;
            var uq1 = 0;
            var eps0 = uq0 > 0 ? epsilon(eq, 0, emax0) : 0;
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
            magnitudes(rho0, uq0Bound, tuple0, s, 0);
            if (x + 2 < w) {
                rho1 |= sample(base + 2, shift, eq, s, 4);
                rho1 |= h > 1 ? sample(base + 2 + w, shift, eq, s, 5) << 1 : 0;
                if (x + 3 < w) {
                    rho1 |= sample(base + 3, shift, eq, s, 6) << 2;
                    rho1 |= h > 1 ? sample(base + 3 + w, shift, eq, s, 7) << 3 : 0;
                }
                var cq1 = (rho0 >> 1) | (rho0 & 1);
                emax1 = Math.max(Math.max(eq[4], eq[5]), Math.max(eq[6], eq[7]));
                var uq1Bound = Math.max(emax1, 1);
                uq1 = uq1Bound - 1;
                var eps1 = uq1 > 0 ? epsilon(eq, 4, emax1) : 0;
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
                magnitudes(rho1, uq1Bound, tuple1, s, 4);
            }
            if (uq0 > 0 && uq1 > 0) {
                melEncode(Math.min(uq0, uq1) > 2);
            }
            if (uq0 > 2 && uq1 > 2) {
                uvlcPrefix(uq0 - 2);
                uvlcPrefix(uq1 - 2);
                uvlcSuffix(uq0 - 2);
                uvlcSuffix(uq1 - 2);
            } else if (uq0 > 2 && uq1 > 0) {
                uvlcPrefix(uq0);
                vlcEncode(uq1 - 1, 1);
                uvlcSuffix(uq0);
            } else {
                uvlcPrefix(uq0);
                uvlcPrefix(uq1);
                uvlcSuffix(uq0);
                uvlcSuffix(uq1);
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
                Arrays.fill(s, 0);
                var base = row + x;
                rho0 |= sample(base, shift, eq, s, 0);
                rho0 |= below ? sample(base + w, shift, eq, s, 1) << 1 : 0;
                if (x + 1 < w) {
                    rho0 |= sample(base + 1, shift, eq, s, 2) << 2;
                    rho0 |= below ? sample(base + 1 + w, shift, eq, s, 3) << 3 : 0;
                }
                var emax0 = Math.max(Math.max(eq[0], eq[1]), Math.max(eq[2], eq[3]));
                var kappa = (rho0 & (rho0 - 1)) != 0 ? Math.max(1, maxE) : 1;
                var uq0Bound = Math.max(emax0, kappa);
                var uq0 = uq0Bound - kappa;
                var uq1 = 0;
                var eps0 = uq0 > 0 ? epsilon(eq, 0, emax0) : 0;
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
                magnitudes(rho0, uq0Bound, tuple0, s, 0);
                if (x + 2 < w) {
                    rho1 |= sample(base + 2, shift, eq, s, 4);
                    rho1 |= below ? sample(base + 2 + w, shift, eq, s, 5) << 1 : 0;
                    if (x + 3 < w) {
                        rho1 |= sample(base + 3, shift, eq, s, 6) << 2;
                        rho1 |= below ? sample(base + 3 + w, shift, eq, s, 7) << 3 : 0;
                    }
                    var emax1 = Math.max(Math.max(eq[4], eq[5]), Math.max(eq[6], eq[7]));
                    kappa = (rho1 & (rho1 - 1)) != 0 ? Math.max(1, maxE) : 1;
                    cq1 |= ((rho0 & 4) >> 1) | ((rho0 & 8) >> 2);
                    var uq1Bound = Math.max(emax1, kappa);
                    uq1 = uq1Bound - kappa;
                    var eps1 = uq1 > 0 ? epsilon(eq, 4, emax1) : 0;
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
                    magnitudes(rho1, uq1Bound, tuple1, s, 4);
                }
                uvlcPrefix(uq0);
                uvlcPrefix(uq1);
                uvlcSuffix(uq0);
                uvlcSuffix(uq1);
                cq0 |= ((rho1 & 4) >> 1) | ((rho1 & 8) >> 2);
            }
        }
        terminateMelVlc();
        msTerminate();
        var length = msPosition + melPosition + vlcPosition;
        if (out.length < length + MAX_AREA + 256) {
            out = new byte[length + MAX_AREA * 2 + 256];
        }
        System.arraycopy(magSgn, 0, out, 0, msPosition);
        System.arraycopy(mel, 0, out, msPosition, melPosition);
        System.arraycopy(vlc, vlc.length - vlcPosition, out, msPosition + melPosition, vlcPosition);
        var suffix = melPosition + vlcPosition;
        out[length - 1] = (byte) (suffix >> 4);
        out[length - 2] = (byte) ((out[length - 2] & 0xF0) | (suffix & 0xF));
        cleanupLength = length;
    }

    /**
     * Prepares one sample of a quad for the cleanup pass.
     *
     * @param index the sample's index in {@link #values}
     * @param shift the bit-plane coded plus the fractional bits
     * @param eq receives the sample's exponent
     * @param s receives the sample's MagSgn value
     * @param slot the sample's position in the quad pair, 0 to 7
     * @return 1 when the sample is significant, 0 otherwise
     */
    private int sample(int index, int shift, int[] eq, int[] s, int slot) {
        var value = values[index];
        var mu = (value & 0x7FFFFFFF) >>> shift;
        if (mu == 0) {
            return 0;
        }
        var v = 2 * mu - 1;
        eq[slot] = 32 - Integer.numberOfLeadingZeros(v);
        s[slot] = v - 1 + (value >>> 31);
        return 1;
    }

    /**
     * Returns the EMB pattern of the samples of a quad whose exponent equals the quad's largest.
     *
     * @param eq the exponents of the quad pair
     * @param first the slot of the quad's first sample
     * @param max the quad's largest exponent
     * @return the pattern, one bit per sample
     */
    private static int epsilon(int[] eq, int first, int max) {
        return (eq[first] == max ? 1 : 0) | (eq[first + 1] == max ? 2 : 0) | (eq[first + 2] == max ? 4 : 0)
                | (eq[first + 3] == max ? 8 : 0);
    }

    /**
     * Writes the MagSgn bits of the significant samples of a quad.
     *
     * @param rho the quad's significance pattern
     * @param bound the quad's exponent bound
     * @param tuple the VLC tuple chosen for the quad, whose low bits are the {@code e_k} pattern
     * @param s the MagSgn values of the quad pair
     * @param first the slot of the quad's first sample
     */
    private void magnitudes(int rho, int bound, int tuple, int[] s, int first) {
        for (var i = 0; i < 4; i++) {
            if ((rho & (1 << i)) != 0) {
                var m = bound - ((tuple >> i) & 1);
                msEncode(s[first + i] & ((1 << m) - 1), m);
            }
        }
    }

    /**
     * Writes the U-VLC prefix of a value.
     *
     * @param u the value
     */
    private void uvlcPrefix(int u) {
        var code = HtTables.UVLC[u];
        vlcEncode(code & 0x7, (code >> 3) & 0x3);
    }

    /**
     * Writes the U-VLC suffix of a value.
     *
     * @param u the value
     */
    private void uvlcSuffix(int u) {
        var code = HtTables.UVLC[u];
        vlcEncode((code >> 5) & 0x1F, (code >> 10) & 0x7);
    }

    /**
     * Emits one MEL bit.
     *
     * @param bit the bit value, 0 or 1
     */
    private void melEmit(int bit) {
        melTmp = (melTmp << 1) + bit;
        melRemaining--;
        if (melRemaining == 0) {
            mel[melPosition++] = (byte) melTmp;
            melRemaining = melTmp == 0xFF ? 7 : 8;
            melTmp = 0;
        }
    }

    /**
     * Codes one MEL event.
     *
     * @param bit the event
     */
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

    /**
     * Writes VLC bits backward, stuffing after bytes above {@code 0x8F}.
     *
     * @param codeword the bits, first bit lowest
     * @param length the number of bits
     */
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

    /**
     * Ends the MEL and VLC byte-streams, fusing their last bytes when possible.
     */
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

    /**
     * Writes MagSgn bits forward, stuffing after {@code 0xFF} bytes.
     *
     * @param codeword the bits, first bit lowest
     * @param length the number of bits, up to 31
     */
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

    /**
     * Ends the MagSgn byte-stream, padding with ones that the decoder supplies itself.
     */
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

    /**
     * Codes the HT SigProp and HT MagRef passes of the bit-plane below the cleanup pass's, recording the
     * distortion reduction of each.
     *
     * @param plane the cleanup pass's least significant bit-plane, at least 1
     */
    private void refinement(int plane) {
        var refined = plane - 1;
        var w = width;
        var h = height;
        var words = ((w + 7) >>> 3) + 1;
        var stripes = (h + 3) >>> 2;
        Arrays.fill(sigma, 0, stripes * words + words, 0);
        Arrays.fill(membership, 0, stripes * words + words, 0);
        var cleanupShift = plane + fraction;
        for (var y = 0; y < h; y++) {
            var word = (y >>> 2) * words;
            var bit = y & 3;
            for (var x = 0; x < w; x++) {
                if (((values[y * w + x] & 0x7FFFFFFF) >>> cleanupShift) != 0) {
                    sigma[word + (x >>> 3)] |= 1 << (((x & 7) << 2) + bit);
                }
            }
        }
        var bits = sigPropSink;
        var magRefWriter = magRefSink;
        bits.reset();
        magRefWriter.reset();
        var sigPropGain = 0.0;
        var magRefGain = 0.0;
        var refineShift = refined + fraction;
        for (var s = 0; s < stripes; s++) {
            var row = s * 4;
            var sig = s * words;
            for (var i = 0; i < w; i += 8) {
                var word = sigma[sig + (i >>> 3)];
                if (word == 0) {
                    continue;
                }
                for (var j = 0; j < 8 && i + j < w; j++) {
                    for (var r = 0; r < 4; r++) {
                        if ((word & (1 << ((j << 2) + r))) != 0) {
                            var index = (row + r) * w + i + j;
                            var value = values[index] & 0x7FFFFFFF;
                            magRefWriter.put((value >>> refineShift) & 1);
                            var magnitude = value >>> fraction;
                            magRefGain += gain(value, reconstruction(magnitude, refined))
                                    - gain(value, reconstruction(magnitude, plane));
                        }
                    }
                }
            }
        }
        for (var s = 0; s < stripes; s++) {
            var row = s * 4;
            var cur = s * words;
            var nxt = cur + words;
            var hasNext = h - row > 4;
            var pattern = 0xFFFFFFFF;
            if (h - row == 3) {
                pattern = 0x77777777;
            } else if (h - row == 2) {
                pattern = 0x33333333;
            } else if (h - row == 1) {
                pattern = 0x11111111;
            }
            var previous = 0;
            for (int i = 0, k = 0; i < w; i += 8, k++) {
                var sg = sigma[cur + k];
                var m = sg;
                m |= previous >>> 28;
                m |= sg << 4;
                m |= sg >>> 4;
                m |= sigma[cur + k + 1] << 28;
                previous = sg;
                var z = m;
                z |= (m & 0x77777777) << 1;
                z |= (m & 0xEEEEEEEE) >>> 1;
                membership[cur + k] |= z & ~sg;
            }
            if (hasNext) {
                previous = 0;
                for (int i = 0, k = 0; i < w; i += 8, k++) {
                    var ns = sigma[nxt + k];
                    var t = ns;
                    t |= previous >>> 28;
                    t |= ns << 4;
                    t |= ns >>> 4;
                    t |= sigma[nxt + k + 1] << 28;
                    previous = ns;
                    if (!causal) {
                        membership[cur + k] |= (t & 0x11111111) << 3;
                    }
                    membership[cur + k] &= ~sigma[cur + k];
                }
            }
            for (int i = 0, k = 0; i < w; i += 8, k++) {
                var mbr = membership[cur + k] & pattern;
                var newSig = 0;
                if (mbr != 0) {
                    for (var n = 0; n < 8; n += 4) {
                        var insignificant = ~sigma[cur + k] & pattern;
                        var end = n + 4 + i < w ? n + 4 : w - i;
                        var columnMask = 0xF << (4 * n);
                        for (var j = n; j < end; j++, columnMask <<= 4) {
                            if ((columnMask & mbr) == 0) {
                                continue;
                            }
                            var sampleMask = 0x11111111 & columnMask;
                            for (var r = 0; r < 4; r++) {
                                if ((mbr & sampleMask) != 0) {
                                    var value = values[(row + r) * w + i + j] & 0x7FFFFFFF;
                                    var bit = (value >>> refineShift) & 1;
                                    bits.put(bit);
                                    if (bit != 0) {
                                        newSig |= sampleMask;
                                        mbr |= (PROPAGATION[r] << (j * 4)) & insignificant;
                                        var magnitude = value >>> fraction;
                                        sigPropGain += gain(value, reconstruction(magnitude, refined));
                                    }
                                }
                                sampleMask += sampleMask;
                            }
                        }
                        if ((newSig & (0xFFFF << (4 * n))) != 0) {
                            columnMask = 0xF << (4 * n);
                            for (var j = n; j < end; j++, columnMask <<= 4) {
                                if ((columnMask & newSig) == 0) {
                                    continue;
                                }
                                var sampleMask = 0x11111111 & columnMask;
                                for (var r = 0; r < 4; r++) {
                                    if ((newSig & sampleMask) != 0) {
                                        bits.put(values[(row + r) * w + i + j] >>> 31);
                                    }
                                    sampleMask += sampleMask;
                                }
                            }
                        }
                        if (n == 4) {
                            var t = newSig >>> 28;
                            t |= ((t & 0xE) >>> 1) | ((t & 7) << 1);
                            membership[cur + k + 1] |= t & ~sigma[cur + k + 1];
                        }
                    }
                }
                if (hasNext) {
                    newSig |= sigma[cur + k];
                    var ux = (newSig & 0x88888888) >>> 3;
                    var tx = ux | (ux << 4) | (ux >>> 4);
                    if (k > 0) {
                        membership[nxt + k - 1] |= (ux << 28) & ~sigma[nxt + k - 1];
                    }
                    membership[nxt + k] |= tx & ~sigma[nxt + k];
                    membership[nxt + k + 1] |= (ux >>> 28) & ~sigma[nxt + k + 1];
                }
            }
        }
        sigPropLength = bits.finish();
        magRefLength = magRefWriter.finish();
        distortions[1] = distortions[0] + sigPropGain;
        distortions[2] = distortions[1] + magRefGain;
    }

    /**
     * Writes the SigProp byte-stream forward, stuffing after {@code 0xFF} bytes.
     */
    private final class BitSink {
        /**
         * The number of bytes written.
         */
        private int position;

        /**
         * The bits of the byte being filled.
         */
        private int tmp;

        /**
         * The number of bits used in the byte being filled.
         */
        private int used;

        /**
         * The number of bits the byte being filled can hold.
         */
        private int max;

        /**
         * Starts an empty stream.
         */
        void reset() {
            position = 0;
            tmp = 0;
            used = 0;
            max = 8;
        }

        /**
         * Writes one bit.
         *
         * @param bit the bit value, 0 or 1
         */
        void put(int bit) {
            tmp |= bit << used;
            if (++used == max) {
                sigProp[position++] = (byte) tmp;
                max = tmp == 0xFF ? 7 : 8;
                tmp = 0;
                used = 0;
            }
        }

        /**
         * Ends the stream, padding its last byte with zeros.
         *
         * @return the number of bytes written
         */
        int finish() {
            if (used != 0) {
                sigProp[position++] = (byte) tmp;
            }
            return position;
        }
    }

    /**
     * Writes the MagRef byte-stream backward from the end of {@link #magRef}, stuffing a zero bit after a byte above
     * {@code 0x8F} whenever the next seven bits are all ones.
     */
    private final class ReverseSink {
        /**
         * The number of bytes written.
         */
        private int position;

        /**
         * The bits of the byte being filled.
         */
        private int tmp;

        /**
         * The number of bits used in the byte being filled.
         */
        private int used;

        /**
         * Whether the last byte written is above {@code 0x8F}; a decoder starts as if one were.
         */
        private boolean above;

        /**
         * Starts an empty stream.
         */
        void reset() {
            position = 0;
            tmp = 0;
            used = 0;
            above = true;
        }

        /**
         * Writes one bit.
         *
         * @param bit the bit value, 0 or 1
         */
        void put(int bit) {
            tmp |= bit << used;
            used++;
            if (used == 7 && above && tmp == 0x7F) {
                emit();
            } else if (used == 8) {
                emit();
            }
        }

        /**
         * Writes the byte being filled.
         */
        private void emit() {
            magRef[magRef.length - 1 - position] = (byte) tmp;
            position++;
            above = tmp > 0x8F;
            tmp = 0;
            used = 0;
        }

        /**
         * Ends the stream, padding its last byte with zeros.
         *
         * @return the number of bytes written
         */
        int finish() {
            if (used != 0) {
                emit();
            }
            return position;
        }
    }
}
