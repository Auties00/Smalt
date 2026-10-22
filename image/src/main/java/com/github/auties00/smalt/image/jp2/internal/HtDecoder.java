/* Ports OpenJPEG 2.5.3 (src/lib/openjp2/ht_dec.c). Copyright (c) 2021, Aous Naman; Copyright (c) 2021, Kakadu
   Software Pty Ltd, Australia; Copyright (c) 2021, The University of New South Wales, Australia; BSD 2-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Decodes the coefficients of a high-throughput code-block (ISO/IEC 15444-15 clause 7): the HT cleanup pass (MEL,
 * VLC and MagSgn bit-streams) and the optional HT SigProp and HT MagRef passes of one HT set.
 *
 * <p>The decoded values have the layout of {@link Tier1Decoder}'s: one extra fractional bit, a fully decoded
 * coefficient being twice its quantized value and one whose last bit-planes are missing placed at the middle of its
 * uncertainty interval. An instance is reused for many code-blocks but is not thread-safe.
 *
 * @implNote This implementation follows OpenJPEG's {@code opj_t1_ht_decode_cblk} sample for sample; its readers
 * consume the byte-streams one byte at a time where OpenJPEG reads aligned 32-bit words, which gives the same bits.
 */
final class HtDecoder {
    /**
     * The offset of the first significance stripe array in {@link #flags}.
     */
    private static final int SIGMA1 = 0;

    /**
     * The offset of the second significance stripe array.
     */
    private static final int SIGMA2 = 132;

    /**
     * The offset of the first SigProp membership array.
     */
    private static final int MBR1 = 264;

    /**
     * The offset of the second SigProp membership array.
     */
    private static final int MBR2 = 396;

    /**
     * The largest code-block area.
     */
    private static final int MAX_AREA = 4096;

    /**
     * The MEL exponents of each state (Table 2 of ISO/IEC 15444-15).
     */
    private static final int[] MEL_EXPONENTS = {0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 4, 5};

    /**
     * The U-VLC prefix decoding of three bits: prefix length in bits 0-1, suffix length in bits 2-4, prefix value in
     * bits 5-7.
     */
    private static final int[] UVLC_PREFIX = {
            3 | (5 << 2) | (5 << 5), 1 | (1 << 5), 2 | (2 << 5), 1 | (1 << 5),
            3 | (1 << 2) | (3 << 5), 1 | (1 << 5), 2 | (2 << 5), 1 | (1 << 5)
    };

    /**
     * The decoded coefficients, row-major.
     */
    private final int[] data = new int[MAX_AREA];

    /**
     * The significance and SigProp membership words: four arrays of 132 words, each word holding four rows of eight
     * columns, one nibble per column with the first row in the nibble's lowest bit.
     */
    private final int[] flags = new int[528];

    /**
     * The quad-row state: per quad, the significance of the north samples in bit 7 and the largest exponent in bits
     * 0-6.
     */
    private final byte[] lineState = new byte[528];

    /**
     * The cleanup and refinement segments of the code-block.
     */
    private byte[] buffer = new byte[8192];

    /**
     * The MEL reader.
     */
    private final Mel mel = new Mel();

    /**
     * The backward VLC reader.
     */
    private final Reverse vlc = new Reverse();

    /**
     * The backward MagRef reader.
     */
    private final Reverse magRef = new Reverse();

    /**
     * The forward MagSgn reader.
     */
    private final Forward magSgn = new Forward();

    /**
     * The forward SigProp reader.
     */
    private final Forward sigProp = new Forward();

    /**
     * The two U values of a quad pair.
     */
    private final int[] u = new int[2];

    /**
     * The exponent of the last magnitude {@link #magnitude} decoded, the bit length of {@code 2 * mu - 1}.
     */
    private int exponent;

    /**
     * Constructs a decoder.
     */
    HtDecoder() {
    }

    /**
     * Returns the decoded coefficients of the last code-block.
     *
     * @return the coefficients, row-major with the code-block's width as stride
     */
    int[] data() {
        return data;
    }

    /**
     * Returns a buffer able to hold a code-block's segments, valid until the next call.
     *
     * @param length the number of bytes
     * @return the buffer
     */
    byte[] buffer(int length) {
        if (buffer.length < length) {
            buffer = new byte[Math.max(length, buffer.length * 2)];
        }
        return buffer;
    }

    /**
     * Clears the coefficients of an empty code-block.
     *
     * @param width the code-block width
     * @param height the code-block height
     */
    void clear(int width, int height) {
        Arrays.fill(data, 0, width * height, 0);
    }

    /**
     * Decodes one HT set held in {@link #buffer(int)}: the cleanup segment at offset 0 followed by the refinement
     * segment.
     *
     * @param cleanup the length of the HT cleanup segment
     * @param refinement the length of the HT refinement segment
     * @param passes the number of passes to decode ({@code Z_blk}), 1 to 3
     * @param magnitudeBits the number of magnitude bit-planes of the sub-band ({@code Mb}, plus the region of
     *                      interest shift)
     * @param skipped the number of magnitude bit-planes above the cleanup pass's, {@code S_blk + 1}
     * @param width the code-block width
     * @param height the code-block height
     * @param causal whether vertically causal context formation is selected
     * @throws MediaException.Malformed if the segments are not a valid HT set
     * @throws MediaException.Unsupported if the sub-band has more than 30 magnitude bit-planes
     */
    void decode(int cleanup, int refinement, int passes, int magnitudeBits, int skipped, int width, int height,
                boolean causal) {
        Arrays.fill(data, 0, width * height, 0);
        Arrays.fill(flags, 0);
        Arrays.fill(lineState, (byte) 0);
        if (magnitudeBits > 30) {
            // TODO: decode HT code-blocks of 31 to 74 magnitude bit-planes in 64-bit arithmetic (OpenJPH's 64-bit
            // block decoder), as deep channels need
            throw new MediaException.Unsupported("JPEG 2000", "HT code-blocks of " + magnitudeBits
                    + " magnitude bit-planes");
        }
        if (skipped > magnitudeBits) {
            throw new MediaException.Malformed("JPEG 2000 HT code-block with " + skipped + " skipped bit-planes of "
                    + magnitudeBits);
        }
        if (skipped == magnitudeBits) {
            passes = 1;
        }
        var p = magnitudeBits + 1 - skipped;
        var bound = skipped + 1;
        var data = this.data;
        var code = buffer;
        if (cleanup < 2) {
            throw new MediaException.Malformed("JPEG 2000 HT cleanup segment of " + cleanup + " bytes");
        }
        var scup = ((code[cleanup - 1] & 0xFF) << 4) + (code[cleanup - 2] & 0xF);
        if (scup < 2 || scup > cleanup || scup > 4079) {
            throw new MediaException.Malformed("invalid JPEG 2000 HT cleanup suffix length " + scup);
        }
        if (!mel.init(code, cleanup, scup)) {
            throw new MediaException.Malformed("invalid JPEG 2000 HT MEL segment");
        }
        vlc.init(code, cleanup, scup);
        magSgn.init(code, 0, cleanup - scup, 0xFF);
        if (passes > 1) {
            sigProp.init(code, cleanup, refinement, 0);
        }
        if (passes > 2) {
            magRef.initMrp(code, cleanup, refinement);
        }
        var flags = this.flags;
        var lines = lineState;
        var stride = width;
        var sip = SIGMA1;
        var sipShift = 0;
        var lsp = 0;
        lines[0] = 0;
        var run = mel.run();
        var cq = 0;
        var sp = 0;
        int vlcValue;
        for (var x = 0; x < width; x += 4) {
            vlcValue = vlc.fetch();
            var q0 = HtTables.DECODE_0[(cq << 7) | (vlcValue & 0x7F)];
            if (cq == 0) {
                run -= 2;
                q0 = run == -1 ? q0 : 0;
                if (run < 0) {
                    run = mel.run();
                }
            }
            cq = ((q0 & 0x10) >>> 4) | ((q0 & 0xE0) >>> 5);
            vlcValue = vlc.advance(q0 & 0x7);
            flags[sip] |= (((q0 & 0x30) >>> 4) | ((q0 & 0xC0) >>> 2)) << sipShift;
            var q1 = 0;
            if (x + 2 < width) {
                q1 = HtTables.DECODE_0[(cq << 7) | (vlcValue & 0x7F)];
                if (cq == 0) {
                    run -= 2;
                    q1 = run == -1 ? q1 : 0;
                    if (run < 0) {
                        run = mel.run();
                    }
                }
                cq = ((q1 & 0x10) >>> 4) | ((q1 & 0xE0) >>> 5);
                vlcValue = vlc.advance(q1 & 0x7);
            }
            flags[sip] |= ((q1 & 0x30) | ((q1 & 0xC0) << 2)) << (4 + sipShift);
            sip += (x & 0x7) != 0 ? 1 : 0;
            sipShift ^= 0x10;
            var mode = ((q0 & 0x8) >>> 3) | ((q1 & 0x8) >>> 2);
            if (mode == 3) {
                run -= 2;
                mode += run == -1 ? 1 : 0;
                if (run < 0) {
                    run = mel.run();
                }
            }
            var consumed = initialUvlc(vlcValue, mode);
            if (u[0] > bound || u[1] > bound) {
                throw new MediaException.Malformed("JPEG 2000 HT exponent bound above the skipped bit-planes");
            }
            vlc.advance(consumed);
            var locs = 0xFF;
            if (x + 4 > width) {
                locs >>>= (x + 4 - width) << 1;
            }
            locs = height > 1 ? locs : (locs & 0x55);
            if (((((q0 & 0xF0) >>> 4) | (q1 & 0xF0)) & ~locs) != 0) {
                throw new MediaException.Malformed("JPEG 2000 HT significance outside the code-block");
            }
            sp = quadPair(q0, q1, u[0], u[1], locs, sp, stride, lsp, p);
            lsp += 2;
        }
        for (var y = 2; y < height; ) {
            sipShift ^= 0x2;
            sipShift &= ~0x10;
            sip = (y & 0x4) != 0 ? SIGMA2 : SIGMA1;
            lsp = 0;
            var ls0 = lines[0] & 0xFF;
            lines[0] = 0;
            sp = y * stride;
            cq = 0;
            for (var x = 0; x < width; x += 4) {
                cq |= ls0 >>> 7;
                cq |= ((lines[lsp + 1] & 0xFF) >>> 5) & 0x4;
                vlcValue = vlc.fetch();
                var q0 = HtTables.DECODE_1[(cq << 7) | (vlcValue & 0x7F)];
                if (cq == 0) {
                    run -= 2;
                    q0 = run == -1 ? q0 : 0;
                    if (run < 0) {
                        run = mel.run();
                    }
                }
                cq = ((q0 & 0x40) >>> 5) | ((q0 & 0x80) >>> 6);
                vlcValue = vlc.advance(q0 & 0x7);
                flags[sip] |= (((q0 & 0x30) >>> 4) | ((q0 & 0xC0) >>> 2)) << sipShift;
                var q1 = 0;
                if (x + 2 < width) {
                    cq |= (lines[lsp + 1] & 0xFF) >>> 7;
                    cq |= ((lines[lsp + 2] & 0xFF) >>> 5) & 0x4;
                    q1 = HtTables.DECODE_1[(cq << 7) | (vlcValue & 0x7F)];
                    if (cq == 0) {
                        run -= 2;
                        q1 = run == -1 ? q1 : 0;
                        if (run < 0) {
                            run = mel.run();
                        }
                    }
                    cq = ((q1 & 0x40) >>> 5) | ((q1 & 0x80) >>> 6);
                    vlcValue = vlc.advance(q1 & 0x7);
                }
                flags[sip] |= ((q1 & 0x30) | ((q1 & 0xC0) << 2)) << (4 + sipShift);
                sip += (x & 0x7) != 0 ? 1 : 0;
                sipShift ^= 0x10;
                var mode = ((q0 & 0x8) >>> 3) | ((q1 & 0x8) >>> 2);
                var consumed = nonInitialUvlc(vlcValue, mode);
                vlc.advance(consumed);
                if (((q0 & 0xF0) & ((q0 & 0xF0) - 1)) != 0) {
                    var e = Math.max(ls0 & 0x7F, lines[lsp + 1] & 0x7F);
                    u[0] += e > 2 ? e - 2 : 0;
                }
                if (((q1 & 0xF0) & ((q1 & 0xF0) - 1)) != 0) {
                    var e = Math.max(lines[lsp + 1] & 0x7F, lines[lsp + 2] & 0x7F);
                    u[1] += e > 2 ? e - 2 : 0;
                }
                if (u[0] > bound || u[1] > bound) {
                    throw new MediaException.Malformed("JPEG 2000 HT exponent bound above the skipped bit-planes");
                }
                ls0 = lines[lsp + 2] & 0xFF;
                lines[lsp + 1] = 0;
                lines[lsp + 2] = 0;
                var locs = 0xFF;
                if (x + 4 > width) {
                    locs >>>= (x + 4 - width) << 1;
                }
                locs = y + 2 <= height ? locs : (locs & 0x55);
                if (((((q0 & 0xF0) >>> 4) | (q1 & 0xF0)) & ~locs) != 0) {
                    throw new MediaException.Malformed("JPEG 2000 HT significance outside the code-block");
                }
                sp = quadPair(q0, q1, u[0], u[1], locs, sp, stride, lsp, p);
                lsp += 2;
            }
            y += 2;
            if (passes > 1 && (y & 3) == 0) {
                if (passes > 2) {
                    magnitudeRefinement((y & 0x4) != 0 ? SIGMA1 : SIGMA2, (y - 4) * stride, width, stride, p);
                }
                if (y >= 4) {
                    membership((y & 0x4) != 0 ? SIGMA1 : SIGMA2, (y & 0x4) != 0 ? MBR1 : MBR2, width);
                }
                if (y >= 8) {
                    significancePropagation(y - 8, width, stride, p, causal, 0xFFFFFFFF, true);
                    var cur = (y & 0x4) != 0 ? SIGMA2 : SIGMA1;
                    Arrays.fill(flags, cur, cur + ((width + 7) >>> 3) + 1, 0);
                }
            }
        }
        if (passes > 1) {
            if (passes > 2 && ((height & 3) == 1 || (height & 3) == 2)) {
                magnitudeRefinement((height & 0x4) != 0 ? SIGMA2 : SIGMA1, (height & 0xFFFFFC) * stride, width, stride,
                        p);
            }
            if ((height & 3) == 1 || (height & 3) == 2) {
                membership((height & 0x4) != 0 ? SIGMA2 : SIGMA1, (height & 0x4) != 0 ? MBR2 : MBR1, width);
            }
            var st = height;
            st -= height > 6 ? (((height + 1) & 3) + 3) : height;
            for (var y = st; y < height; y += 4) {
                var pattern = 0xFFFFFFFF;
                if (height - y == 3) {
                    pattern = 0x77777777;
                } else if (height - y == 2) {
                    pattern = 0x33333333;
                } else if (height - y == 1) {
                    pattern = 0x11111111;
                }
                significancePropagation(y, width, stride, p, causal, pattern, height - y > 4);
            }
        }
        var size = width * height;
        for (var i = 0; i < size; i++) {
            var value = data[i];
            var magnitude = value & 0x7FFFFFFF;
            data[i] = value < 0 ? -magnitude : magnitude;
        }
    }

    /**
     * Decodes the MagSgn values of a quad pair and updates the line state.
     *
     * @param q0 the VLC information of the first quad
     * @param q1 the VLC information of the second quad
     * @param u0 the exponent bound of the first quad
     * @param u1 the exponent bound of the second quad
     * @param locs the mask of the samples inside the code-block
     * @param sp the index of the quad pair's top-left sample
     * @param stride the row stride
     * @param lsp the index of the quad pair's line state
     * @param p the shift placing a decoded magnitude
     * @return the index of the next quad pair's top-left sample
     */
    private int quadPair(int q0, int q1, int u0, int u1, int locs, int sp, int stride, int lsp, int p) {
        var data = this.data;
        var lines = lineState;
        if ((q0 & 0x10) != 0) {
            data[sp] = magnitude(q0, u0, 12, 8, p);
        } else if ((locs & 0x1) != 0) {
            data[sp] = 0;
        }
        if ((q0 & 0x20) != 0) {
            var value = magnitude(q0, u0, 13, 9, p);
            data[sp + stride] = value;
            var t = lines[lsp] & 0x7F;
            var e = exponent;
            lines[lsp] = (byte) (0x80 | Math.max(t, e));
        } else if ((locs & 0x2) != 0) {
            data[sp + stride] = 0;
        }
        lsp++;
        sp++;
        if ((q0 & 0x40) != 0) {
            data[sp] = magnitude(q0, u0, 14, 10, p);
        } else if ((locs & 0x4) != 0) {
            data[sp] = 0;
        }
        lines[lsp] = 0;
        if ((q0 & 0x80) != 0) {
            data[sp + stride] = magnitude(q0, u0, 15, 11, p);
            lines[lsp] = (byte) (0x80 | exponent);
        } else if ((locs & 0x8) != 0) {
            data[sp + stride] = 0;
        }
        sp++;
        if ((q1 & 0x10) != 0) {
            data[sp] = magnitude(q1, u1, 12, 8, p);
        } else if ((locs & 0x10) != 0) {
            data[sp] = 0;
        }
        if ((q1 & 0x20) != 0) {
            data[sp + stride] = magnitude(q1, u1, 13, 9, p);
            var t = lines[lsp] & 0x7F;
            lines[lsp] = (byte) (0x80 | Math.max(t, exponent));
        } else if ((locs & 0x20) != 0) {
            data[sp + stride] = 0;
        }
        lsp++;
        sp++;
        if ((q1 & 0x40) != 0) {
            data[sp] = magnitude(q1, u1, 14, 10, p);
        } else if ((locs & 0x40) != 0) {
            data[sp] = 0;
        }
        lines[lsp] = 0;
        if ((q1 & 0x80) != 0) {
            data[sp + stride] = magnitude(q1, u1, 15, 11, p);
            lines[lsp] = (byte) (0x80 | exponent);
        } else if ((locs & 0x80) != 0) {
            data[sp + stride] = 0;
        }
        sp++;
        return sp;
    }

    /**
     * Decodes one significant sample's magnitude and sign from the MagSgn bit-stream.
     *
     * @param q the VLC information of the quad
     * @param uq the quad's exponent bound
     * @param ekBit the position of the sample's {@code e_k} bit in {@code q}
     * @param e1Bit the position of the sample's {@code e_1} bit in {@code q}
     * @param p the shift placing the magnitude
     * @return the sample in sign-magnitude form, sign in bit 31
     */
    private int magnitude(int q, int uq, int ekBit, int e1Bit, int p) {
        var value = magSgn.fetch();
        var m = uq - ((q >>> ekBit) & 1);
        magSgn.advance(m);
        var sign = value << 31;
        var v = value & ((1 << m) - 1);
        v |= ((q >>> e1Bit) & 1) << m;
        v |= 1;
        exponent = 32 - Integer.numberOfLeadingZeros(v);
        return sign | ((v + 2) << (p - 1));
    }

    /**
     * Decodes the U-VLC of a quad pair of the initial quad row into {@link #u}.
     *
     * @param vlcValue the head of the VLC bit-stream
     * @param mode the {@code u_off} bits of the two quads, plus one when both are set and the MEL event is 1
     * @return the number of bits consumed
     */
    private int initialUvlc(int vlcValue, int mode) {
        var consumed = 0;
        if (mode == 0) {
            u[0] = 1;
            u[1] = 1;
        } else if (mode <= 2) {
            var d = UVLC_PREFIX[vlcValue & 0x7];
            vlcValue >>>= d & 0x3;
            consumed += d & 0x3;
            var suffix = (d >>> 2) & 0x7;
            consumed += suffix;
            d = (d >>> 5) + (vlcValue & ((1 << suffix) - 1));
            u[0] = mode == 1 ? d + 1 : 1;
            u[1] = mode == 1 ? 1 : d + 1;
        } else if (mode == 3) {
            var d1 = UVLC_PREFIX[vlcValue & 0x7];
            vlcValue >>>= d1 & 0x3;
            consumed += d1 & 0x3;
            if ((d1 & 0x3) > 2) {
                u[1] = (vlcValue & 1) + 1 + 1;
                consumed++;
                vlcValue >>>= 1;
                var suffix = (d1 >>> 2) & 0x7;
                consumed += suffix;
                d1 = (d1 >>> 5) + (vlcValue & ((1 << suffix) - 1));
                u[0] = d1 + 1;
            } else {
                var d2 = UVLC_PREFIX[vlcValue & 0x7];
                vlcValue >>>= d2 & 0x3;
                consumed += d2 & 0x3;
                var suffix = (d1 >>> 2) & 0x7;
                consumed += suffix;
                d1 = (d1 >>> 5) + (vlcValue & ((1 << suffix) - 1));
                u[0] = d1 + 1;
                vlcValue >>>= suffix;
                suffix = (d2 >>> 2) & 0x7;
                consumed += suffix;
                d2 = (d2 >>> 5) + (vlcValue & ((1 << suffix) - 1));
                u[1] = d2 + 1;
            }
        } else {
            var d1 = UVLC_PREFIX[vlcValue & 0x7];
            vlcValue >>>= d1 & 0x3;
            consumed += d1 & 0x3;
            var d2 = UVLC_PREFIX[vlcValue & 0x7];
            vlcValue >>>= d2 & 0x3;
            consumed += d2 & 0x3;
            var suffix = (d1 >>> 2) & 0x7;
            consumed += suffix;
            d1 = (d1 >>> 5) + (vlcValue & ((1 << suffix) - 1));
            u[0] = d1 + 3;
            vlcValue >>>= suffix;
            suffix = (d2 >>> 2) & 0x7;
            consumed += suffix;
            d2 = (d2 >>> 5) + (vlcValue & ((1 << suffix) - 1));
            u[1] = d2 + 3;
        }
        return consumed;
    }

    /**
     * Decodes the U-VLC of a quad pair of a non-initial quad row into {@link #u}.
     *
     * @param vlcValue the head of the VLC bit-stream
     * @param mode the {@code u_off} bits of the two quads
     * @return the number of bits consumed
     */
    private int nonInitialUvlc(int vlcValue, int mode) {
        var consumed = 0;
        if (mode == 0) {
            u[0] = 1;
            u[1] = 1;
        } else if (mode <= 2) {
            var d = UVLC_PREFIX[vlcValue & 0x7];
            vlcValue >>>= d & 0x3;
            consumed += d & 0x3;
            var suffix = (d >>> 2) & 0x7;
            consumed += suffix;
            d = (d >>> 5) + (vlcValue & ((1 << suffix) - 1));
            u[0] = mode == 1 ? d + 1 : 1;
            u[1] = mode == 1 ? 1 : d + 1;
        } else {
            var d1 = UVLC_PREFIX[vlcValue & 0x7];
            vlcValue >>>= d1 & 0x3;
            consumed += d1 & 0x3;
            var d2 = UVLC_PREFIX[vlcValue & 0x7];
            vlcValue >>>= d2 & 0x3;
            consumed += d2 & 0x3;
            var suffix = (d1 >>> 2) & 0x7;
            consumed += suffix;
            d1 = (d1 >>> 5) + (vlcValue & ((1 << suffix) - 1));
            u[0] = d1 + 1;
            vlcValue >>>= suffix;
            suffix = (d2 >>> 2) & 0x7;
            consumed += suffix;
            d2 = (d2 >>> 5) + (vlcValue & ((1 << suffix) - 1));
            u[1] = d2 + 1;
        }
        return consumed;
    }

    /**
     * Applies the HT MagRef pass to one four-row stripe.
     *
     * @param sigma the offset of the stripe's significance words
     * @param row the index of the stripe's first sample
     * @param width the code-block width
     * @param stride the row stride
     * @param p the shift placing a decoded magnitude
     */
    private void magnitudeRefinement(int sigma, int row, int width, int stride, int p) {
        var data = this.data;
        var half = 1 << (p - 2);
        for (var i = 0; i < width; i += 8) {
            var cwd = magRef.fetchMrp();
            var sig = flags[sigma++];
            var columnMask = 0xF;
            var dp = row + i;
            if (sig != 0) {
                for (var j = 0; j < 8; j++, dp++) {
                    if ((sig & columnMask) != 0) {
                        var sampleMask = 0x11111111 & columnMask;
                        for (var r = 0; r < 4; r++) {
                            if ((sig & sampleMask) != 0) {
                                var at = dp + r * stride;
                                var symbol = cwd & 1;
                                data[at] ^= (1 - symbol) << (p - 1);
                                data[at] |= half;
                                cwd >>>= 1;
                            }
                            sampleMask += sampleMask;
                        }
                    }
                    columnMask <<= 4;
                }
            }
            magRef.advanceMrp(Integer.bitCount(sig));
        }
    }

    /**
     * Computes the SigProp membership of a stripe from its significance: the insignificant samples with a
     * significant neighbor in the stripe.
     *
     * @param sigma the offset of the stripe's significance words
     * @param mbr the offset of the stripe's membership words
     * @param width the code-block width
     */
    private void membership(int sigma, int mbr, int width) {
        var flags = this.flags;
        var previous = 0;
        for (var i = 0; i < width; i += 8, mbr++, sigma++) {
            var sig = flags[sigma];
            var m = sig;
            m |= previous >>> 28;
            m |= sig << 4;
            m |= sig >>> 4;
            m |= flags[sigma + 1] << 28;
            previous = sig;
            var z = m;
            z |= (m & 0x77777777) << 1;
            z |= (m & 0xEEEEEEEE) >>> 1;
            flags[mbr] = z & ~sig;
        }
    }

    /**
     * Applies the HT SigProp pass to one stripe.
     *
     * @param row the first row of the stripe
     * @param width the code-block width
     * @param stride the row stride
     * @param p the shift placing a decoded magnitude
     * @param causal whether vertically causal context formation is selected
     * @param pattern the mask of the stripe's rows inside the code-block
     * @param addNext whether membership from the next stripe is added
     */
    private void significancePropagation(int row, int width, int stride, int p,
                                         boolean causal, int pattern, boolean addNext) {
        var flags = this.flags;
        var data = this.data;
        var swap = (row & 0x4) != 0;
        var curSig = swap ? SIGMA2 : SIGMA1;
        var curMbr = swap ? MBR2 : MBR1;
        var nxtSig = swap ? SIGMA1 : SIGMA2;
        var nxtMbr = swap ? MBR1 : MBR2;
        if (addNext) {
            var previous = 0;
            for (int i = 0, cs = curSig, cm = curMbr, ns = nxtSig; i < width; i += 8, cm++, cs++, ns++) {
                var t = flags[ns];
                t |= previous >>> 28;
                t |= flags[ns] << 4;
                t |= flags[ns] >>> 4;
                t |= flags[ns + 1] << 28;
                previous = flags[ns];
                if (!causal) {
                    flags[cm] |= (t & 0x11111111) << 3;
                }
                flags[cm] &= ~flags[cs];
            }
        }
        var value = 3 << (p - 2);
        for (var i = 0; i < width; i += 8, curSig++, curMbr++, nxtSig++, nxtMbr++) {
            var mbr = flags[curMbr] & pattern;
            var newSig = 0;
            if (mbr != 0) {
                for (var n = 0; n < 8; n += 4) {
                    var cwd = sigProp.fetch();
                    var count = 0;
                    var dp = row * stride + i + n;
                    var columnMask = 0xF << (4 * n);
                    var insignificant = ~flags[curSig] & pattern;
                    var end = n + 4 + i < width ? n + 4 : width - i;
                    for (var j = n; j < end; j++, dp++, columnMask <<= 4) {
                        if ((columnMask & mbr) == 0) {
                            continue;
                        }
                        var sampleMask = 0x11111111 & columnMask;
                        if ((mbr & sampleMask) != 0) {
                            if ((cwd & 1) != 0) {
                                newSig |= sampleMask;
                                mbr |= (0x32 << (j * 4)) & insignificant;
                            }
                            cwd >>>= 1;
                            count++;
                        }
                        sampleMask += sampleMask;
                        if ((mbr & sampleMask) != 0) {
                            if ((cwd & 1) != 0) {
                                newSig |= sampleMask;
                                mbr |= (0x74 << (j * 4)) & insignificant;
                            }
                            cwd >>>= 1;
                            count++;
                        }
                        sampleMask += sampleMask;
                        if ((mbr & sampleMask) != 0) {
                            if ((cwd & 1) != 0) {
                                newSig |= sampleMask;
                                mbr |= (0xE8 << (j * 4)) & insignificant;
                            }
                            cwd >>>= 1;
                            count++;
                        }
                        sampleMask += sampleMask;
                        if ((mbr & sampleMask) != 0) {
                            if ((cwd & 1) != 0) {
                                newSig |= sampleMask;
                                mbr |= (0xC0 << (j * 4)) & insignificant;
                            }
                            cwd >>>= 1;
                            count++;
                        }
                    }
                    if ((newSig & (0xFFFF << (4 * n))) != 0) {
                        var dq = row * stride + i + n;
                        columnMask = 0xF << (4 * n);
                        for (var j = n; j < end; j++, dq++, columnMask <<= 4) {
                            if ((columnMask & newSig) == 0) {
                                continue;
                            }
                            var sampleMask = 0x11111111 & columnMask;
                            for (var r = 0; r < 4; r++) {
                                if ((newSig & sampleMask) != 0) {
                                    data[dq + r * stride] |= ((cwd & 1) << 31) | value;
                                    cwd >>>= 1;
                                    count++;
                                }
                                sampleMask += sampleMask;
                            }
                        }
                    }
                    sigProp.advance(count);
                    if (n == 4) {
                        var t = newSig >>> 28;
                        t |= ((t & 0xE) >>> 1) | ((t & 7) << 1);
                        flags[curMbr + 1] |= t & ~flags[curSig + 1];
                    }
                }
            }
            newSig |= flags[curSig];
            var ux = (newSig & 0x88888888) >>> 3;
            var tx = ux | (ux << 4) | (ux >>> 4);
            if (i > 0) {
                flags[nxtMbr - 1] |= (ux << 28) & ~flags[nxtSig - 1];
            }
            flags[nxtMbr] |= tx & ~flags[nxtSig];
            flags[nxtMbr + 1] |= (ux >>> 28) & ~flags[nxtSig + 1];
        }
    }

    /**
     * Reads the MEL bit-stream and decodes it into runs of events.
     */
    private static final class Mel {
        /**
         * The codeword bytes.
         */
        private byte[] code;

        /**
         * The index of the next byte.
         */
        private int position;

        /**
         * The bits read ahead, most significant first.
         */
        private long tmp;

        /**
         * The number of bits in {@link #tmp}.
         */
        private int bits;

        /**
         * The number of bytes left.
         */
        private int size;

        /**
         * Whether the next byte loses its most significant bit to unstuffing.
         */
        private boolean unstuff;

        /**
         * The MEL state, 0 to 12.
         */
        private int k;

        /**
         * The number of decoded runs queued in {@link #runs}.
         */
        private int numRuns;

        /**
         * The queued runs, seven bits each.
         */
        private long runs;

        /**
         * Starts reading the MEL segment of a cleanup segment.
         *
         * @param code the codeword bytes
         * @param lcup the cleanup segment length
         * @param scup the length of the MEL and VLC segments
         * @return whether the first bytes are valid
         */
        boolean init(byte[] code, int lcup, int scup) {
            this.code = code;
            position = lcup - scup;
            bits = 0;
            tmp = 0;
            unstuff = false;
            size = scup - 1;
            k = 0;
            numRuns = 0;
            runs = 0;
            for (var i = 0; i < 4; i++) {
                if (unstuff && size > 0 && (code[position] & 0xFF) > 0x8F) {
                    return false;
                }
                long d = size > 0 ? code[position] & 0xFF : 0xFF;
                if (size == 1) {
                    d |= 0xF;
                }
                if (size-- > 0) {
                    position++;
                }
                var dBits = 8 - (unstuff ? 1 : 0);
                tmp = (tmp << dBits) | d;
                bits += dBits;
                unstuff = (d & 0xFF) == 0xFF;
            }
            tmp <<= 64 - bits;
            return true;
        }

        /**
         * Reads and unstuffs up to four bytes.
         */
        private void read() {
            if (bits > 32) {
                return;
            }
            var value = 0xFFFFFFFF;
            if (size > 4) {
                value = (code[position] & 0xFF) | (code[position + 1] & 0xFF) << 8
                        | (code[position + 2] & 0xFF) << 16 | (code[position + 3] & 0xFF) << 24;
                position += 4;
                size -= 4;
            } else if (size > 0) {
                var i = 0;
                while (size > 1) {
                    var v = code[position++] & 0xFF;
                    var m = ~(0xFF << i);
                    value = (value & m) | (v << i);
                    --size;
                    i += 8;
                }
                var v = (code[position++] & 0xFF) | 0xF;
                var m = ~(0xFF << i);
                value = (value & m) | (v << i);
                --size;
            }
            var count = 32 - (unstuff ? 1 : 0);
            var t = value & 0xFF;
            var stuffed = (value & 0xFF) == 0xFF;
            count -= stuffed ? 1 : 0;
            t = t << (8 - (stuffed ? 1 : 0));
            t |= (value >>> 8) & 0xFF;
            stuffed = ((value >>> 8) & 0xFF) == 0xFF;
            count -= stuffed ? 1 : 0;
            t = t << (8 - (stuffed ? 1 : 0));
            t |= (value >>> 16) & 0xFF;
            stuffed = ((value >>> 16) & 0xFF) == 0xFF;
            count -= stuffed ? 1 : 0;
            t = t << (8 - (stuffed ? 1 : 0));
            t |= (value >>> 24) & 0xFF;
            unstuff = ((value >>> 24) & 0xFF) == 0xFF;
            tmp |= (t & 0xFFFFFFFFL) << (64 - count - bits);
            bits += count;
        }

        /**
         * Decodes queued bits into runs.
         */
        private void decode() {
            if (bits < 6) {
                read();
            }
            while (bits >= 6 && numRuns < 8) {
                var exponent = MEL_EXPONENTS[k];
                int run;
                if (tmp < 0) {
                    run = (1 << exponent) - 1;
                    k = Math.min(k + 1, 12);
                    tmp <<= 1;
                    bits -= 1;
                    run = run << 1;
                } else {
                    run = (int) (tmp >>> (63 - exponent)) & ((1 << exponent) - 1);
                    k = Math.max(k - 1, 0);
                    tmp <<= exponent + 1;
                    bits -= exponent + 1;
                    run = (run << 1) + 1;
                }
                var shift = numRuns * 7;
                runs &= ~(0x3FL << shift);
                runs |= ((long) run) << shift;
                numRuns++;
            }
        }

        /**
         * Returns the next run.
         *
         * @return twice the number of zero events, plus one when the run ends with a one event
         */
        int run() {
            if (numRuns == 0) {
                decode();
            }
            var t = (int) (runs & 0x7F);
            runs >>>= 7;
            numRuns--;
            return t;
        }
    }

    /**
     * Reads and unstuffs a byte-stream that grows backward: the VLC and MagRef bit-streams.
     */
    private static final class Reverse {
        /**
         * The codeword bytes.
         */
        private byte[] code;

        /**
         * The index of the next byte.
         */
        private int position;

        /**
         * The bits read ahead, first bit lowest.
         */
        private long tmp;

        /**
         * The number of bits in {@link #tmp}.
         */
        private int bits;

        /**
         * The number of bytes left; negative once a MagRef stream reads past its start.
         */
        private int size;

        /**
         * Whether the last byte read is above {@code 0x8F}.
         */
        private boolean unstuff;

        /**
         * Starts reading the VLC segment backward from the end of a cleanup segment.
         *
         * @param code the codeword bytes
         * @param lcup the cleanup segment length
         * @param scup the length of the MEL and VLC segments
         */
        void init(byte[] code, int lcup, int scup) {
            this.code = code;
            position = lcup - 2;
            size = scup - 2;
            var d = code[position--] & 0xFF;
            tmp = d >>> 4;
            bits = 4 - ((tmp & 7) == 7 ? 1 : 0);
            unstuff = (d | 0xF) > 0x8F;
            var count = Math.min(4, size);
            for (var i = 0; i < count; i++) {
                long b = code[position--] & 0xFF;
                var dBits = 8 - ((unstuff && ((b & 0x7F) == 0x7F)) ? 1 : 0);
                tmp |= b << bits;
                bits += dBits;
                unstuff = b > 0x8F;
            }
            size -= count;
            read();
        }

        /**
         * Starts reading the MagRef bit-stream backward from the end of a refinement segment.
         *
         * @param code the codeword bytes
         * @param lcup the cleanup segment length
         * @param length the refinement segment length
         */
        void initMrp(byte[] code, int lcup, int length) {
            this.code = code;
            position = lcup + length - 1;
            size = length;
            unstuff = true;
            bits = 0;
            tmp = 0;
            for (var i = 0; i < 4; i++) {
                long d = size-- > 0 ? code[position--] & 0xFF : 0;
                var dBits = 8 - ((unstuff && ((d & 0x7F) == 0x7F)) ? 1 : 0);
                tmp |= d << bits;
                bits += dBits;
                unstuff = d > 0x8F;
            }
            read();
        }

        /**
         * Reads and unstuffs up to four bytes, feeding zeros once the stream is exhausted.
         */
        private void read() {
            if (bits > 32) {
                return;
            }
            var value = 0;
            if (size > 3) {
                value = (code[position] & 0xFF) << 24 | (code[position - 1] & 0xFF) << 16
                        | (code[position - 2] & 0xFF) << 8 | (code[position - 3] & 0xFF);
                position -= 4;
                size -= 4;
            } else if (size > 0) {
                var i = 24;
                while (size > 0) {
                    var v = code[position--] & 0xFF;
                    value |= v << i;
                    --size;
                    i -= 8;
                }
            }
            var t = value >>> 24;
            var count = 8 - ((unstuff && (((value >>> 24) & 0x7F) == 0x7F)) ? 1 : 0);
            var stuffed = (value >>> 24) > 0x8F;
            t |= ((value >>> 16) & 0xFF) << count;
            count += 8 - ((stuffed && (((value >>> 16) & 0x7F) == 0x7F)) ? 1 : 0);
            stuffed = ((value >>> 16) & 0xFF) > 0x8F;
            t |= ((value >>> 8) & 0xFF) << count;
            count += 8 - ((stuffed && (((value >>> 8) & 0x7F) == 0x7F)) ? 1 : 0);
            stuffed = ((value >>> 8) & 0xFF) > 0x8F;
            t |= (value & 0xFF) << count;
            count += 8 - ((stuffed && ((value & 0x7F) == 0x7F)) ? 1 : 0);
            stuffed = (value & 0xFF) > 0x8F;
            tmp |= (t & 0xFFFFFFFFL) << bits;
            bits += count;
            unstuff = stuffed;
        }

        /**
         * Returns the next 32 bits of the VLC bit-stream without consuming them.
         *
         * @return the bits, first bit lowest
         */
        int fetch() {
            if (bits < 32) {
                read();
                if (bits < 32) {
                    read();
                }
            }
            return (int) tmp;
        }

        /**
         * Consumes bits of the VLC bit-stream.
         *
         * @param count the number of bits, at most the number read ahead
         * @return the bits that follow, first bit lowest
         */
        int advance(int count) {
            tmp >>>= count;
            bits -= count;
            return (int) tmp;
        }

        /**
         * Returns the next 32 bits of the MagRef bit-stream without consuming them.
         *
         * @return the bits, first bit lowest
         */
        int fetchMrp() {
            return fetch();
        }

        /**
         * Consumes bits of the MagRef bit-stream.
         *
         * @param count the number of bits
         */
        void advanceMrp(int count) {
            advance(count);
        }
    }

    /**
     * Reads and unstuffs a byte-stream that grows forward: the MagSgn and SigProp bit-streams.
     */
    private static final class Forward {
        /**
         * The codeword bytes.
         */
        private byte[] code;

        /**
         * The index of the next byte.
         */
        private int position;

        /**
         * The bits read ahead, first bit lowest.
         */
        private long tmp;

        /**
         * The number of bits in {@link #tmp}.
         */
        private int bits;

        /**
         * Whether the next byte loses its most significant bit to unstuffing.
         */
        private boolean unstuff;

        /**
         * The number of bytes left.
         */
        private int size;

        /**
         * The byte fed once the stream is exhausted, {@code 0} or {@code 0xFF}.
         */
        private int fill;

        /**
         * Starts reading a stream.
         *
         * @param code the codeword bytes
         * @param offset the index of the stream's first byte
         * @param length the stream length
         * @param fill the byte fed once the stream is exhausted
         */
        void init(byte[] code, int offset, int length, int fill) {
            this.code = code;
            position = offset;
            tmp = 0;
            bits = 0;
            unstuff = false;
            size = length;
            this.fill = fill;
            for (var i = 0; i < 4; i++) {
                long d = size-- > 0 ? code[position++] & 0xFF : fill;
                tmp |= d << bits;
                bits += 8 - (unstuff ? 1 : 0);
                unstuff = (d & 0xFF) == 0xFF;
            }
            read();
        }

        /**
         * Reads and unstuffs up to four bytes.
         */
        private void read() {
            int value;
            if (size > 3) {
                value = (code[position] & 0xFF) | (code[position + 1] & 0xFF) << 8
                        | (code[position + 2] & 0xFF) << 16 | (code[position + 3] & 0xFF) << 24;
                position += 4;
                size -= 4;
            } else if (size > 0) {
                var i = 0;
                value = fill != 0 ? 0xFFFFFFFF : 0;
                while (size > 0) {
                    var v = code[position++] & 0xFF;
                    var m = ~(0xFF << i);
                    value = (value & m) | (v << i);
                    --size;
                    i += 8;
                }
            } else {
                value = fill != 0 ? 0xFFFFFFFF : 0;
            }
            var count = 8 - (unstuff ? 1 : 0);
            var t = value & 0xFF;
            var stuffed = (value & 0xFF) == 0xFF;
            t |= ((value >>> 8) & 0xFF) << count;
            count += 8 - (stuffed ? 1 : 0);
            stuffed = ((value >>> 8) & 0xFF) == 0xFF;
            t |= ((value >>> 16) & 0xFF) << count;
            count += 8 - (stuffed ? 1 : 0);
            stuffed = ((value >>> 16) & 0xFF) == 0xFF;
            t |= ((value >>> 24) & 0xFF) << count;
            count += 8 - (stuffed ? 1 : 0);
            unstuff = ((value >>> 24) & 0xFF) == 0xFF;
            tmp |= (t & 0xFFFFFFFFL) << bits;
            bits += count;
        }

        /**
         * Returns the next 32 bits without consuming them.
         *
         * @return the bits, first bit lowest
         */
        int fetch() {
            if (bits < 32) {
                read();
                if (bits < 32) {
                    read();
                }
            }
            return (int) tmp;
        }

        /**
         * Consumes bits.
         *
         * @param count the number of bits, at most the number read ahead
         */
        void advance(int count) {
            tmp >>>= count;
            bits -= count;
        }
    }
}
