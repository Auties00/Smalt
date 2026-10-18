/* Ports libwebp 1.6.0 (src/utils/bit_writer_utils.c, VP8BitWriter). Copyright (c) 2010, Google Inc.; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Encodes booleans with the VP8 boolean entropy coder (RFC 6386 section 7) into a growable
 * buffer, and assembles frames from the coded partitions.
 *
 * <p>The output is byte-identical to libwebp's {@code VP8BitWriter}, including the carry
 * propagation through pending {@code 0xFF} bytes and the final padding. Instances are not
 * thread-safe.
 */
final class BoolEncoder {
    /**
     * The renormalization shift of every range value: {@link Vp8EncTables#NORM} below 127 and
     * zero from 127 on.
     */
    private static final byte[] SHIFTS = new byte[256];

    /**
     * The renormalized range of every range value: {@link Vp8EncTables#NEW_RANGE} below 127 and
     * the value itself from 127 on.
     */
    private static final short[] RANGES = new short[256];

    static {
        for (var r = 0; r < 256; r++) {
            SHIFTS[r] = (byte) (r < 127 ? Vp8EncTables.NORM[r] : 0);
            RANGES[r] = (short) (r < 127 ? Vp8EncTables.NEW_RANGE[r] : r);
        }
    }

    /**
     * The current range minus one.
     */
    private int range = 254;

    /**
     * The pending low bits of the arithmetic code.
     */
    private int value;

    /**
     * The number of pending {@code 0xFF} bytes awaiting a possible carry.
     */
    private int run;

    /**
     * The number of pending bits, minus 8.
     */
    private int nbBits = -8;

    /**
     * The output bytes.
     */
    private byte[] buf;

    /**
     * The number of bytes written to {@link #buf}.
     */
    private int pos;

    /**
     * Creates an encoder with an initial capacity.
     *
     * @param expectedSize the expected output size in bytes
     */
    BoolEncoder(int expectedSize) {
        buf = new byte[Math.max(1024, expectedSize)];
    }

    /**
     * Discards every coded bit, keeping the buffer, so the encoder can code a new partition.
     */
    void reset() {
        range = 254;
        value = 0;
        run = 0;
        nbBits = -8;
        pos = 0;
    }

    /**
     * Makes room for more output bytes.
     *
     * @param extra the number of bytes about to be written
     */
    private void ensure(int extra) {
        var needed = (long) pos + extra;
        if (needed > buf.length) {
            if (needed > Integer.MAX_VALUE - 16) {
                throw new IllegalStateException("vp8 partition too large");
            }
            buf = Arrays.copyOf(buf, (int) Math.max(needed, Math.min(Integer.MAX_VALUE - 16, 2L * buf.length)));
        }
    }

    /**
     * Emits the settled high byte of the pending code.
     */
    private void flush() {
        var s = 8 + nbBits;
        var bits = value >> s;
        value -= bits << s;
        nbBits -= 8;
        if ((bits & 0xFF) != 0xFF) {
            ensure(run + 1);
            if ((bits & 0x100) != 0 && pos > 0) {
                buf[pos - 1]++;
            }
            if (run > 0) {
                var v = (bits & 0x100) != 0 ? (byte) 0x00 : (byte) 0xFF;
                for (; run > 0; run--) {
                    buf[pos++] = v;
                }
            }
            buf[pos++] = (byte) bits;
        } else {
            run++;
        }
    }

    /**
     * Encodes one boolean.
     *
     * @param bit  the value, zero or nonzero
     * @param prob the probability of a zero, in {@code [0, 255]}
     * @return {@code bit} normalized to {@code 0} or {@code 1}
     */
    int putBit(int bit, int prob) {
        var split = (range * prob) >> 8;
        if (bit != 0) {
            value += split + 1;
            range -= split + 1;
        } else {
            range = split;
        }
        if (range < 127) {
            var shift = Vp8EncTables.NORM[range];
            range = Vp8EncTables.NEW_RANGE[range];
            value <<= shift;
            nbBits += shift;
            if (nbBits > 0) {
                flush();
            }
        }
        return bit != 0 ? 1 : 0;
    }

    /**
     * Encodes a sequence of recorded tokens, each exactly as {@link #putBit} would, with the
     * coder state held in local variables and branch-free range updates.
     *
     * <p>Each token is a 16-bit value: bit 15 is the coded bit; when bit 14 is set, bits 0 to 7
     * hold a constant probability, otherwise bits 0 to 13 index {@code probas}.
     *
     * @param tokens the recorded tokens, in coding order
     * @param count the number of tokens to encode
     * @param probas the adaptive probabilities
     */
    void putTokens(short[] tokens, int count, int[] probas) {
        var range = this.range;
        var value = this.value;
        var nbBits = this.nbBits;
        var shifts = SHIFTS;
        var ranges = RANGES;
        for (var i = 0; i < count; i++) {
            int token = tokens[i];
            var adaptive = probas[token & 0x3FFF];
            var prob = (token & EncProba.FIXED_PROBA_BIT) != 0 ? token & 0xFF : adaptive;
            var split = (range * prob) >> 8;
            var mask = token >> 31;
            var one = split + 1;
            value += one & mask;
            range = split + ((range - one - split) & mask);
            var shift = shifts[range];
            range = ranges[range];
            value <<= shift;
            nbBits += shift;
            if (nbBits > 0) {
                this.value = value;
                this.nbBits = nbBits;
                flush();
                value = this.value;
                nbBits = this.nbBits;
            }
        }
        this.range = range;
        this.value = value;
        this.nbBits = nbBits;
    }

    /**
     * Encodes one boolean with probability one half.
     *
     * @param bit the value, zero or nonzero
     * @return {@code bit} normalized to {@code 0} or {@code 1}
     */
    int putBitUniform(int bit) {
        var split = range >> 1;
        if (bit != 0) {
            value += split + 1;
            range -= split + 1;
        } else {
            range = split;
        }
        if (range < 127) {
            range = Vp8EncTables.NEW_RANGE[range];
            value <<= 1;
            nbBits += 1;
            if (nbBits > 0) {
                flush();
            }
        }
        return bit != 0 ? 1 : 0;
    }

    /**
     * Encodes an unsigned literal, most significant bit first, with probability one half.
     *
     * @param v     the value
     * @param count the number of bits, in {@code [1, 31]}
     */
    void putBits(int v, int count) {
        for (var mask = 1 << (count - 1); mask != 0; mask >>>= 1) {
            putBitUniform(v & mask);
        }
    }

    /**
     * Encodes an optional signed value: a presence flag, the magnitude, and the sign.
     *
     * @param v     the value
     * @param count the magnitude width in bits
     */
    void putSignedBits(int v, int count) {
        if (putBitUniform(v != 0 ? 1 : 0) == 0) {
            return;
        }
        if (v < 0) {
            putBits((-v << 1) | 1, count + 1);
        } else {
            putBits(v << 1, count + 1);
        }
    }

    /**
     * Pads and flushes the code so the partition can be decoded in full.
     */
    void finish() {
        putBits(0, 9 - nbBits);
        nbBits = 0;
        flush();
    }

    /**
     * Returns the number of bytes written.
     *
     * @return the size in bytes
     */
    int size() {
        return pos;
    }

    /**
     * Returns the output buffer; only the first {@link #size()} bytes are valid.
     *
     * @return the buffer, not a copy
     */
    byte[] buffer() {
        return buf;
    }

    /**
     * Assembles a VP8 frame (RFC 6386 section 9.1) from its finished partitions: the frame tag,
     * the start code and dimensions of a key frame, the first partition, the sizes of the token
     * partitions but the last, and the token partitions.
     *
     * @param key whether the frame is a key frame
     * @param version the bitstream version, from 0 to 3
     * @param width the picture width of a key frame, from 1 to 16383
     * @param height the picture height of a key frame, from 1 to 16383
     * @param first the first partition, finished
     * @param parts the token partitions, finished; 1, 2, 4 or 8 of them
     * @return the frame
     * @throws MediaException.Unsupported if a partition exceeds the size the format allows
     */
    static byte[] assembleFrame(boolean key, int version, int width, int height, BoolEncoder first,
                                BoolEncoder[] parts) {
        var size0 = first.size();
        if (size0 >= 1 << 19) {
            throw new MediaException.Unsupported("vp8 partition 0 overflow",
                    "the vp8 first partition exceeds 512 KiB (" + size0 + " bytes)");
        }
        var n = parts.length;
        long total = (key ? 10 : 3) + size0 + 3L * (n - 1);
        for (var p = 0; p < n; p++) {
            total += parts[p].size();
            if (p < n - 1 && parts[p].size() >= 1 << 24) {
                throw new MediaException.Unsupported("vp8 partition overflow",
                        "a vp8 token partition exceeds 16 MiB (" + parts[p].size() + " bytes)");
            }
        }
        if (total > Integer.MAX_VALUE - 16) {
            throw new MediaException.Unsupported("vp8 frame too large", "the vp8 frame exceeds 2 GiB");
        }
        var out = new byte[(int) total];
        var tag = (size0 << 5) | (1 << 4) | (version << 1) | (key ? 0 : 1);
        out[0] = (byte) tag;
        out[1] = (byte) (tag >> 8);
        out[2] = (byte) (tag >> 16);
        var pos = 3;
        if (key) {
            out[3] = (byte) 0x9D;
            out[4] = 0x01;
            out[5] = 0x2A;
            out[6] = (byte) width;
            out[7] = (byte) (width >> 8);
            out[8] = (byte) height;
            out[9] = (byte) (height >> 8);
            pos = 10;
        }
        System.arraycopy(first.buffer(), 0, out, pos, size0);
        pos += size0;
        for (var p = 0; p < n - 1; p++) {
            var size = parts[p].size();
            out[pos++] = (byte) size;
            out[pos++] = (byte) (size >> 8);
            out[pos++] = (byte) (size >> 16);
        }
        for (var part : parts) {
            System.arraycopy(part.buffer(), 0, out, pos, part.size());
            pos += part.size();
        }
        return out;
    }
}
