/* Ports libwebp 1.6.0 (src/utils/bit_reader_utils.c, bit_reader_inl_utils.h). Copyright (c) 2010, Google Inc.; BSD
   3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;

/**
 * Decodes the boolean entropy coded partitions of a VP8 frame (RFC 6386 section 7).
 *
 * <p>The decoder reads one partition of a byte array. Reading past the end of the partition
 * never fails: missing bytes read as zero, and {@link #isEof()} reports that the decoder needed at
 * least one byte beyond the end, which a conforming stream never requires because encoders pad
 * every partition. Instances are not thread-safe.
 *
 * @implNote This implementation refills a 64-bit window 56 bits at a time, stores the range minus
 * one and derives the renormalization shift from the leading-zero count; it is arithmetically
 * identical to libvpx's {@code vp8dx_decode_bool}.
 */
public final class BoolDecoder {
    /**
     * Reads eight bytes of a byte array as a big-endian {@code long}.
     */
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    /**
     * The bytes being decoded.
     */
    private byte[] buf;

    /**
     * The index of the next byte to load into the window.
     */
    private int pos;

    /**
     * The index one past the last byte of the partition.
     */
    private int end;

    /**
     * The first index from which an eight-byte bulk load would cross {@link #end}.
     */
    private int loadMax;

    /**
     * The bit window; the active bits are the {@code bits + 8} low-order bits.
     */
    private long value;

    /**
     * The number of buffered bits beyond the eight bits compared against the split, possibly
     * negative when the window must be refilled.
     */
    private int bits;

    /**
     * The current range minus one, in {@code [126, 254]}.
     */
    private int range;

    /**
     * Whether the decoder has needed a byte beyond the end of the partition.
     */
    private boolean eof;

    /**
     * Creates a decoder that must be initialized with {@link #init(byte[], int, int)} before use.
     */
    public BoolDecoder() {
    }

    /**
     * Starts decoding a partition.
     *
     * @param data   the array holding the partition
     * @param offset the index of the first byte of the partition
     * @param length the partition size in bytes; non-negative and within the array
     */
    public void init(byte[] data, int offset, int length) {
        this.buf = data;
        this.pos = offset;
        this.end = offset + length;
        this.loadMax = length >= 8 ? offset + length - 7 : offset;
        this.value = 0;
        this.bits = -8;
        this.range = 254;
        this.eof = false;
        loadNewBytes();
    }

    /**
     * Returns whether the decoder has needed data beyond the end of its partition.
     *
     * @return {@code true} once a byte past the end was consumed
     */
    public boolean isEof() {
        return eof;
    }

    /**
     * Refills the window, 56 bits at a time while enough bytes remain.
     */
    private void loadNewBytes() {
        if (pos < loadMax) {
            var in = (long) LONG_BE.get(buf, pos);
            pos += 7;
            value = (in >>> 8) | (value << 56);
            bits += 56;
        } else {
            loadFinalBytes();
        }
    }

    /**
     * Refills the window one byte at a time near the end of the partition, shifting in zeros
     * past the end.
     */
    private void loadFinalBytes() {
        if (pos < end) {
            bits += 8;
            value = (buf[pos++] & 0xFFL) | (value << 8);
        } else if (!eof) {
            value <<= 8;
            bits += 8;
            eof = true;
        } else {
            bits = 0;
        }
    }

    /**
     * Decodes one boolean whose probability of being zero is {@code prob / 256}.
     *
     * @param prob the probability of a zero, in {@code [0, 255]}
     * @return the decoded bit, {@code 0} or {@code 1}
     */
    public int readBool(int prob) {
        var range = this.range;
        if (bits < 0) {
            loadNewBytes();
        }
        var pos = bits;
        var split = (range * prob) >>> 8;
        var window = (int) (value >>> pos);
        int bit;
        if (window > split) {
            range -= split;
            value -= (long) (split + 1) << pos;
            bit = 1;
        } else {
            range = split + 1;
            bit = 0;
        }
        var shift = 7 ^ (31 - Integer.numberOfLeadingZeros(range));
        bits = pos - shift;
        this.range = (range << shift) - 1;
        return bit;
    }

    /**
     * Decodes one boolean with probability one half.
     *
     * @return the decoded bit
     */
    public int readBit() {
        return readBool(128);
    }

    /**
     * Decodes an unsigned literal of the given width, most significant bit first.
     *
     * @param count the number of bits, in {@code [0, 31]}
     * @return the literal
     */
    public int readLiteral(int count) {
        var v = 0;
        while (count-- > 0) {
            v |= readBool(128) << count;
        }
        return v;
    }

    /**
     * Decodes the tokens of one coefficient block into dequantized coefficients (RFC 6386
     * section 13), like libvpx's {@code GetCoeffs}.
     *
     * <p>The decoder state is held in local variables for the whole block, which keeps it in
     * registers; the result is identical to decoding every token with {@link #readBool(int)}.
     *
     * @param probs      the token probabilities, {@code [type][band][context][node]}
     * @param bandOffset the index of the probabilities of each scan position's band, for this
     *                   block type
     * @param bandBase   the index of this block type's first entry in {@code bandOffset}
     * @param ctx        the nonzero context, {@code 0} to {@code 2}
     * @param first      the first scan position, {@code 0} or {@code 1}
     * @param zigzag     the raster position of each scan position in {@code coef}
     * @param coef       the coefficient storage
     * @param out        the index of the block's first coefficient in {@code coef}
     * @param dc         the DC dequantization factor
     * @param ac         the AC dequantization factor
     * @return the scan position after the last decoded token, {@code 0} when the block is empty
     */
    public int decodeCoefficients(int[] probs, int[] bandOffset, int bandBase, int ctx, int first, int[] zigzag,
                                  short[] coef, int out, int dc, int ac) {
        var buf = this.buf;
        var loadMax = this.loadMax;
        var pos = this.pos;
        var value = this.value;
        var bits = this.bits;
        var range = this.range;
        int split;
        int shift;
        int b;
        var n = first;
        var p = bandOffset[bandBase + n] + ctx * 11;
        var result = 0;
        if (bits < 0) {
            if (pos < loadMax) {
                value = ((long) LONG_BE.get(buf, pos) >>> 8) | (value << 56);
                pos += 7;
                bits += 56;
            } else {
                this.value = value;
                this.bits = bits;
                this.pos = pos;
                loadFinalBytes();
                value = this.value;
                bits = this.bits;
                pos = this.pos;
            }
        }
        split = (range * (probs[p])) >>> 8;
        if ((int) (value >>> bits) > split) {
            range -= split;
            value -= (long) (split + 1) << bits;
            b = 1;
        } else {
            range = split + 1;
            b = 0;
        }
        shift = Integer.numberOfLeadingZeros(range) - 24;
        range = (range << shift) - 1;
        bits -= shift;
        if (b != 0) {
            while (true) {
                ++n;
                if (bits < 0) {
                    if (pos < loadMax) {
                        value = ((long) LONG_BE.get(buf, pos) >>> 8) | (value << 56);
                        pos += 7;
                        bits += 56;
                    } else {
                        this.value = value;
                        this.bits = bits;
                        this.pos = pos;
                        loadFinalBytes();
                        value = this.value;
                        bits = this.bits;
                        pos = this.pos;
                    }
                }
                split = (range * (probs[p + 1])) >>> 8;
                if ((int) (value >>> bits) > split) {
                    range -= split;
                    value -= (long) (split + 1) << bits;
                    b = 1;
                } else {
                    range = split + 1;
                    b = 0;
                }
                shift = Integer.numberOfLeadingZeros(range) - 24;
                range = (range << shift) - 1;
                bits -= shift;
                if (b == 0) {
                    p = bandOffset[bandBase + n];
                    if (n == 16) {
                        result = 16;
                        break;
                    }
                    continue;
                }
                int v;
                if (bits < 0) {
                    if (pos < loadMax) {
                        value = ((long) LONG_BE.get(buf, pos) >>> 8) | (value << 56);
                        pos += 7;
                        bits += 56;
                    } else {
                        this.value = value;
                        this.bits = bits;
                        this.pos = pos;
                        loadFinalBytes();
                        value = this.value;
                        bits = this.bits;
                        pos = this.pos;
                    }
                }
                split = (range * (probs[p + 2])) >>> 8;
                if ((int) (value >>> bits) > split) {
                    range -= split;
                    value -= (long) (split + 1) << bits;
                    b = 1;
                } else {
                    range = split + 1;
                    b = 0;
                }
                shift = Integer.numberOfLeadingZeros(range) - 24;
                range = (range << shift) - 1;
                bits -= shift;
                if (b == 0) {
                    p = bandOffset[bandBase + n] + 11;
                    v = 1;
                } else {
                    if (bits < 0) {
                        if (pos < loadMax) {
                            value = ((long) LONG_BE.get(buf, pos) >>> 8) | (value << 56);
                            pos += 7;
                            bits += 56;
                        } else {
                            this.value = value;
                            this.bits = bits;
                            this.pos = pos;
                            loadFinalBytes();
                            value = this.value;
                            bits = this.bits;
                            pos = this.pos;
                        }
                    }
                    split = (range * (probs[p + 3])) >>> 8;
                    if ((int) (value >>> bits) > split) {
                        range -= split;
                        value -= (long) (split + 1) << bits;
                        b = 1;
                    } else {
                        range = split + 1;
                        b = 0;
                    }
                    shift = Integer.numberOfLeadingZeros(range) - 24;
                    range = (range << shift) - 1;
                    bits -= shift;
                    if (b == 0) {
                        if (bits < 0) {
                            if (pos < loadMax) {
                                value = ((long) LONG_BE.get(buf, pos) >>> 8) | (value << 56);
                                pos += 7;
                                bits += 56;
                            } else {
                                this.value = value;
                                this.bits = bits;
                                this.pos = pos;
                                loadFinalBytes();
                                value = this.value;
                                bits = this.bits;
                                pos = this.pos;
                            }
                        }
                        split = (range * (probs[p + 4])) >>> 8;
                        if ((int) (value >>> bits) > split) {
                            range -= split;
                            value -= (long) (split + 1) << bits;
                            b = 1;
                        } else {
                            range = split + 1;
                            b = 0;
                        }
                        shift = Integer.numberOfLeadingZeros(range) - 24;
                        range = (range << shift) - 1;
                        bits -= shift;
                        if (b == 0) {
                            v = 2;
                        } else {
                            if (bits < 0) {
                                if (pos < loadMax) {
                                    value = ((long) LONG_BE.get(buf, pos) >>> 8) | (value << 56);
                                    pos += 7;
                                    bits += 56;
                                } else {
                                    this.value = value;
                                    this.bits = bits;
                                    this.pos = pos;
                                    loadFinalBytes();
                                    value = this.value;
                                    bits = this.bits;
                                    pos = this.pos;
                                }
                            }
                            split = (range * (probs[p + 5])) >>> 8;
                            if ((int) (value >>> bits) > split) {
                                range -= split;
                                value -= (long) (split + 1) << bits;
                                b = 1;
                            } else {
                                range = split + 1;
                                b = 0;
                            }
                            shift = Integer.numberOfLeadingZeros(range) - 24;
                            range = (range << shift) - 1;
                            bits -= shift;
                            v = 3 + b;
                        }
                    } else {
                        if (bits < 0) {
                            if (pos < loadMax) {
                                value = ((long) LONG_BE.get(buf, pos) >>> 8) | (value << 56);
                                pos += 7;
                                bits += 56;
                            } else {
                                this.value = value;
                                this.bits = bits;
                                this.pos = pos;
                                loadFinalBytes();
                                value = this.value;
                                bits = this.bits;
                                pos = this.pos;
                            }
                        }
                        split = (range * (probs[p + 6])) >>> 8;
                        if ((int) (value >>> bits) > split) {
                            range -= split;
                            value -= (long) (split + 1) << bits;
                            b = 1;
                        } else {
                            range = split + 1;
                            b = 0;
                        }
                        shift = Integer.numberOfLeadingZeros(range) - 24;
                        range = (range << shift) - 1;
                        bits -= shift;
                        if (b == 0) {
                            if (bits < 0) {
                                if (pos < loadMax) {
                                    value = ((long) LONG_BE.get(buf, pos) >>> 8) | (value << 56);
                                    pos += 7;
                                    bits += 56;
                                } else {
                                    this.value = value;
                                    this.bits = bits;
                                    this.pos = pos;
                                    loadFinalBytes();
                                    value = this.value;
                                    bits = this.bits;
                                    pos = this.pos;
                                }
                            }
                            split = (range * (probs[p + 7])) >>> 8;
                            if ((int) (value >>> bits) > split) {
                                range -= split;
                                value -= (long) (split + 1) << bits;
                                b = 1;
                            } else {
                                range = split + 1;
                                b = 0;
                            }
                            shift = Integer.numberOfLeadingZeros(range) - 24;
                            range = (range << shift) - 1;
                            bits -= shift;
                            if (b == 0) {
                                if (bits < 0) {
                                    if (pos < loadMax) {
                                        value = ((long) LONG_BE.get(buf, pos) >>> 8) | (value << 56);
                                        pos += 7;
                                        bits += 56;
                                    } else {
                                        this.value = value;
                                        this.bits = bits;
                                        this.pos = pos;
                                        loadFinalBytes();
                                        value = this.value;
                                        bits = this.bits;
                                        pos = this.pos;
                                    }
                                }
                                split = (range * (159)) >>> 8;
                                if ((int) (value >>> bits) > split) {
                                    range -= split;
                                    value -= (long) (split + 1) << bits;
                                    b = 1;
                                } else {
                                    range = split + 1;
                                    b = 0;
                                }
                                shift = Integer.numberOfLeadingZeros(range) - 24;
                                range = (range << shift) - 1;
                                bits -= shift;
                                v = 5 + b;
                            } else {
                                if (bits < 0) {
                                    if (pos < loadMax) {
                                        value = ((long) LONG_BE.get(buf, pos) >>> 8) | (value << 56);
                                        pos += 7;
                                        bits += 56;
                                    } else {
                                        this.value = value;
                                        this.bits = bits;
                                        this.pos = pos;
                                        loadFinalBytes();
                                        value = this.value;
                                        bits = this.bits;
                                        pos = this.pos;
                                    }
                                }
                                split = (range * (165)) >>> 8;
                                if ((int) (value >>> bits) > split) {
                                    range -= split;
                                    value -= (long) (split + 1) << bits;
                                    b = 1;
                                } else {
                                    range = split + 1;
                                    b = 0;
                                }
                                shift = Integer.numberOfLeadingZeros(range) - 24;
                                range = (range << shift) - 1;
                                bits -= shift;
                                v = 7 + 2 * b;
                                if (bits < 0) {
                                    if (pos < loadMax) {
                                        value = ((long) LONG_BE.get(buf, pos) >>> 8) | (value << 56);
                                        pos += 7;
                                        bits += 56;
                                    } else {
                                        this.value = value;
                                        this.bits = bits;
                                        this.pos = pos;
                                        loadFinalBytes();
                                        value = this.value;
                                        bits = this.bits;
                                        pos = this.pos;
                                    }
                                }
                                split = (range * (145)) >>> 8;
                                if ((int) (value >>> bits) > split) {
                                    range -= split;
                                    value -= (long) (split + 1) << bits;
                                    b = 1;
                                } else {
                                    range = split + 1;
                                    b = 0;
                                }
                                shift = Integer.numberOfLeadingZeros(range) - 24;
                                range = (range << shift) - 1;
                                bits -= shift;
                                v += b;
                            }
                        } else {
                            int bit1;
                            if (bits < 0) {
                                if (pos < loadMax) {
                                    value = ((long) LONG_BE.get(buf, pos) >>> 8) | (value << 56);
                                    pos += 7;
                                    bits += 56;
                                } else {
                                    this.value = value;
                                    this.bits = bits;
                                    this.pos = pos;
                                    loadFinalBytes();
                                    value = this.value;
                                    bits = this.bits;
                                    pos = this.pos;
                                }
                            }
                            split = (range * (probs[p + 8])) >>> 8;
                            if ((int) (value >>> bits) > split) {
                                range -= split;
                                value -= (long) (split + 1) << bits;
                                bit1 = 1;
                            } else {
                                range = split + 1;
                                bit1 = 0;
                            }
                            shift = Integer.numberOfLeadingZeros(range) - 24;
                            range = (range << shift) - 1;
                            bits -= shift;
                            int bit0;
                            if (bits < 0) {
                                if (pos < loadMax) {
                                    value = ((long) LONG_BE.get(buf, pos) >>> 8) | (value << 56);
                                    pos += 7;
                                    bits += 56;
                                } else {
                                    this.value = value;
                                    this.bits = bits;
                                    this.pos = pos;
                                    loadFinalBytes();
                                    value = this.value;
                                    bits = this.bits;
                                    pos = this.pos;
                                }
                            }
                            split = (range * (probs[p + 9 + bit1])) >>> 8;
                            if ((int) (value >>> bits) > split) {
                                range -= split;
                                value -= (long) (split + 1) << bits;
                                bit0 = 1;
                            } else {
                                range = split + 1;
                                bit0 = 0;
                            }
                            shift = Integer.numberOfLeadingZeros(range) - 24;
                            range = (range << shift) - 1;
                            bits -= shift;
                            var cat = 2 * bit1 + bit0;
                            var tab = switch (cat) {
                                case 0 -> Vp8Tables.CAT3_PROBS;
                                case 1 -> Vp8Tables.CAT4_PROBS;
                                case 2 -> Vp8Tables.CAT5_PROBS;
                                default -> Vp8Tables.CAT6_PROBS;
                            };
                            v = 0;
                            for (var t : tab) {
                                if (bits < 0) {
                                    if (pos < loadMax) {
                                        value = ((long) LONG_BE.get(buf, pos) >>> 8) | (value << 56);
                                        pos += 7;
                                        bits += 56;
                                    } else {
                                        this.value = value;
                                        this.bits = bits;
                                        this.pos = pos;
                                        loadFinalBytes();
                                        value = this.value;
                                        bits = this.bits;
                                        pos = this.pos;
                                    }
                                }
                                split = (range * (t)) >>> 8;
                                if ((int) (value >>> bits) > split) {
                                    range -= split;
                                    value -= (long) (split + 1) << bits;
                                    b = 1;
                                } else {
                                    range = split + 1;
                                    b = 0;
                                }
                                shift = Integer.numberOfLeadingZeros(range) - 24;
                                range = (range << shift) - 1;
                                bits -= shift;
                                v += v + b;
                            }
                            v += 3 + (8 << cat);
                        }
                    }
                    p = bandOffset[bandBase + n] + 22;
                }
                if (bits < 0) {
                    if (pos < loadMax) {
                        value = ((long) LONG_BE.get(buf, pos) >>> 8) | (value << 56);
                        pos += 7;
                        bits += 56;
                    } else {
                        this.value = value;
                        this.bits = bits;
                        this.pos = pos;
                        loadFinalBytes();
                        value = this.value;
                        bits = this.bits;
                        pos = this.pos;
                    }
                }
                split = range >>> 1;
                var mask = (split - (int) (value >>> bits)) >> 31;
                bits -= 1;
                range = (range + mask) | 1;
                value -= (long) ((split + 1) & mask) << bits + 1;
                var index = n - 1;
                coef[out + zigzag[index]] = (short) (((v ^ mask) - mask) * (index > 0 ? ac : dc));
                if (n == 16) {
                    result = 16;
                    break;
                }
                if (bits < 0) {
                    if (pos < loadMax) {
                        value = ((long) LONG_BE.get(buf, pos) >>> 8) | (value << 56);
                        pos += 7;
                        bits += 56;
                    } else {
                        this.value = value;
                        this.bits = bits;
                        this.pos = pos;
                        loadFinalBytes();
                        value = this.value;
                        bits = this.bits;
                        pos = this.pos;
                    }
                }
                split = (range * (probs[p])) >>> 8;
                if ((int) (value >>> bits) > split) {
                    range -= split;
                    value -= (long) (split + 1) << bits;
                    b = 1;
                } else {
                    range = split + 1;
                    b = 0;
                }
                shift = Integer.numberOfLeadingZeros(range) - 24;
                range = (range << shift) - 1;
                bits -= shift;
                if (b == 0) {
                    result = n;
                    break;
                }
            }
        }
        this.pos = pos;
        this.value = value;
        this.bits = bits;
        this.range = range;
        return result;
    }

    /**
     * Decodes a symbol with a probability tree.
     *
     * @param tree   the tree: pairs of child indices, leaves negated
     * @param probs  the node probabilities
     * @param offset the index of the first node probability in {@code probs}
     * @return the decoded leaf value
     */
    public int readTree(int[] tree, int[] probs, int offset) {
        var i = 0;
        do {
            i = tree[i + readBool(probs[offset + (i >> 1)])];
        } while (i > 0);
        return -i;
    }
}
