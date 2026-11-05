/* Ports LibTIFF 4.7.1 (tif_fax3.c). Copyright Sam Leffler, Silicon Graphics, Inc.; LibTIFF license.
   Modified for Smalt. */
package com.github.auties00.smalt.image.tiff.internal;

import java.util.Arrays;

/**
 * Encodes bilevel rows as CCITT fax data: Modified Huffman run lengths (TIFF compression 2, and
 * the word-aligned variant 32771), ITU-T T.4 Group 3 one- and two-dimensional coding
 * (compression 3) and ITU-T T.6 Group 4 coding (compression 4).
 *
 * <p>The input has one bit per pixel, most significant bit first, rows padded to a byte; 0 bits
 * are white runs and 1 bits black runs. Each strip or tile is coded on its own: Group 3 rows start
 * with an end-of-line code (byte-aligned so that it ends on a byte boundary when the fill-bits
 * option is set, and followed by the tag bit in two-dimensional mode, where every {@code k}-th row
 * starting with the first is coded one-dimensionally); Group 4 data ends with the
 * end-of-facsimile-block code; Modified Huffman rows end on a byte (a 16-bit word for 32771)
 * boundary. No return-to-control sequence is written, since the strip or tile length bounds the
 * data. The output is byte-identical to libtiff's for the same rows and options.
 *
 * <p>An instance is not thread-safe.
 *
 * @implNote This implementation follows libtiff's tif_fax3.c: the order of make-up and terminating
 * codes for long runs, the changing-element search of the two-dimensional coder, the placement of
 * the fill bits.
 */
public final class CcittFaxEncoder {
    /**
     * Compression 2: Modified Huffman, rows byte-aligned.
     */
    public static final int RLE = 2;

    /**
     * Compression 3: ITU-T T.4.
     */
    public static final int T4 = 3;

    /**
     * Compression 4: ITU-T T.6.
     */
    public static final int T6 = 4;

    /**
     * Compression 32771: Modified Huffman, rows aligned to 16-bit words.
     */
    public static final int RLEW = 32771;

    /**
     * The end-of-line code, 11 zeros and a one.
     */
    private static final int EOL = 0x001;

    /**
     * The code of each white run from 0 to 2560: terminating codes for 0 to 63, then make-up
     * codes, packed as {@code length << 16 | bits}; indexed as {@link #code(int[], int)} expects.
     */
    private static final int[] WHITE = codes(CcittFaxDecoder.WHITE_TERMINATING, CcittFaxDecoder.WHITE_MAKEUP);

    /**
     * The black codes, laid out as {@link #WHITE}.
     */
    private static final int[] BLACK = codes(CcittFaxDecoder.BLACK_TERMINATING, CcittFaxDecoder.BLACK_MAKEUP);

    /**
     * The vertical mode codes for offsets -3 to 3, packed as {@code length << 16 | bits}.
     */
    private static final int[] VERTICAL = {
            7 << 16 | 0x03, 6 << 16 | 0x03, 3 << 16 | 0x03, 1 << 16 | 0x1, 3 << 16 | 0x2, 6 << 16 | 0x02,
            7 << 16 | 0x02
    };

    /**
     * The compression code.
     */
    private final int compression;

    /**
     * Whether Group 3 rows may be coded two-dimensionally.
     */
    private final boolean twoDimensional;

    /**
     * Whether Group 3 end-of-line codes end on a byte boundary.
     */
    private final boolean fillBits;

    /**
     * The Group 3 two-dimensional parameter: one row in {@code k} is coded one-dimensionally.
     */
    private final int k;

    /**
     * The coded bytes.
     */
    private byte[] out = new byte[256];

    /**
     * The number of whole bytes in {@link #out}.
     */
    private int size;

    /**
     * The bits not yet written, right-aligned.
     */
    private int pending;

    /**
     * The number of bits in {@link #pending}, from 0 to 7.
     */
    private int pendingBits;

    /**
     * The reference row of the two-dimensional coder.
     */
    private byte[] reference = new byte[0];

    /**
     * Constructs an encoder.
     *
     * @param compression {@link #RLE}, {@link #RLEW}, {@link #T4} or {@link #T6}
     * @param twoDimensional whether Group 3 rows may be coded two-dimensionally (T4Options bit 0)
     * @param fillBits whether Group 3 end-of-line codes end on a byte boundary (T4Options bit 2)
     * @param k the Group 3 two-dimensional parameter, 2 or 4
     */
    public CcittFaxEncoder(int compression, boolean twoDimensional, boolean fillBits, int k) {
        this.compression = compression;
        this.twoDimensional = compression == T4 && twoDimensional;
        this.fillBits = compression == T4 && fillBits;
        this.k = k;
    }

    /**
     * Returns the Group 3 two-dimensional parameter for a vertical resolution.
     *
     * @param dotsPerInch the vertical resolution in rows per inch, or 0 when unknown
     * @return 4 above 150 rows per inch, 2 otherwise
     */
    public static int parameterK(double dotsPerInch) {
        return dotsPerInch > 150 ? 4 : 2;
    }

    /**
     * Codes one strip or tile.
     *
     * @param data the rows
     * @param offset the index of the first row
     * @param rowBytes the distance between rows, at least {@code (width + 7) / 8}
     * @param width the pixels per row, positive
     * @param rows the number of rows
     * @return the coded bytes
     */
    public byte[] encode(byte[] data, int offset, int rowBytes, int width, int rows) {
        size = 0;
        pending = 0;
        pendingBits = 0;
        var packed = (width + 7) >>> 3;
        if (reference.length < packed) {
            reference = new byte[packed];
        }
        Arrays.fill(reference, 0, packed, (byte) 0);
        var oneDimensional = true;
        var remaining = k - 1;
        for (var row = 0; row < rows; row++) {
            var base = offset + row * rowBytes;
            switch (compression) {
                case T6 -> {
                    encode2d(data, base, reference, width);
                    System.arraycopy(data, base, reference, 0, packed);
                }
                case T4 -> {
                    endOfLine(oneDimensional);
                    if (!twoDimensional) {
                        encode1d(data, base, width);
                    } else {
                        if (oneDimensional) {
                            encode1d(data, base, width);
                            oneDimensional = false;
                        } else {
                            encode2d(data, base, reference, width);
                            remaining--;
                        }
                        if (remaining == 0) {
                            oneDimensional = true;
                            remaining = k - 1;
                        } else {
                            System.arraycopy(data, base, reference, 0, packed);
                        }
                    }
                }
                default -> {
                    encode1d(data, base, width);
                    flush();
                    if (compression == RLEW && (size & 1) != 0) {
                        writeByte(0);
                    }
                }
            }
        }
        if (compression == T6) {
            put(EOL, 12);
            put(EOL, 12);
        }
        flush();
        return Arrays.copyOf(out, size);
    }

    /**
     * Writes a Group 3 end-of-line code, preceded by fill bits when they are on and followed by
     * the tag bit in two-dimensional mode.
     *
     * @param oneDimensional whether the next row is coded one-dimensionally
     */
    private void endOfLine(boolean oneDimensional) {
        if (fillBits && pendingBits != 4) {
            put(0, pendingBits < 4 ? 4 - pendingBits : 12 - pendingBits);
        }
        if (twoDimensional) {
            put(EOL << 1 | (oneDimensional ? 1 : 0), 13);
        } else {
            put(EOL, 12);
        }
    }

    /**
     * Codes a row as alternating white and black runs, starting with white.
     *
     * @param data the rows
     * @param base the index of the row
     * @param width the pixels per row
     */
    private void encode1d(byte[] data, int base, int width) {
        var position = 0;
        while (true) {
            var span = span(data, base, position, width, false);
            run(span, WHITE);
            position += span;
            if (position >= width) {
                break;
            }
            span = span(data, base, position, width, true);
            run(span, BLACK);
            position += span;
            if (position >= width) {
                break;
            }
        }
    }

    /**
     * Codes a row against the reference row with the pass, horizontal and vertical modes.
     *
     * @param data the rows
     * @param base the index of the row
     * @param ref the reference row, starting at index 0
     * @param width the pixels per row
     */
    private void encode2d(byte[] data, int base, byte[] ref, int width) {
        var a0 = 0;
        var a1 = pixel(data, base, 0) != 0 ? 0 : difference(data, base, 0, width, 0);
        var b1 = pixel(ref, 0, 0) != 0 ? 0 : difference(ref, 0, 0, width, 0);
        while (true) {
            var b2 = b1 < width ? difference(ref, 0, b1, width, pixel(ref, 0, b1)) : width;
            if (b2 >= a1) {
                var d = b1 - a1;
                if (d < -3 || d > 3) {
                    var a2 = a1 < width ? difference(data, base, a1, width, pixel(data, base, a1)) : width;
                    put(0x1, 3);
                    if (a0 + a1 == 0 || pixel(data, base, a0) == 0) {
                        run(a1 - a0, WHITE);
                        run(a2 - a1, BLACK);
                    } else {
                        run(a1 - a0, BLACK);
                        run(a2 - a1, WHITE);
                    }
                    a0 = a2;
                } else {
                    var code = VERTICAL[d + 3];
                    put(code & 0xFFFF, code >>> 16);
                    a0 = a1;
                }
            } else {
                put(0x1, 4);
                a0 = b2;
            }
            if (a0 >= width) {
                break;
            }
            var color = pixel(data, base, a0);
            a1 = difference(data, base, a0, width, color);
            b1 = difference(ref, 0, a0, width, color ^ 1);
            b1 = difference(ref, 0, b1, width, color);
        }
    }

    /**
     * {@return a pixel of a row, 0 for white and 1 for black}
     *
     * @param data the rows
     * @param base the index of the row
     * @param x the pixel index; the pixel at or past the width reads as the padding stores it
     */
    private static int pixel(byte[] data, int base, int x) {
        return (data[base + (x >>> 3)] >>> (7 - (x & 7))) & 1;
    }

    /**
     * {@return the index of the first pixel at or after a start that differs from a color, or the
     * width when every pixel to the end has that color}
     *
     * @param data the rows
     * @param base the index of the row
     * @param start the first pixel examined
     * @param width the pixels per row
     * @param color the color of the run, 0 or 1
     */
    private static int difference(byte[] data, int base, int start, int width, int color) {
        return start + span(data, base, start, width, color != 0);
    }

    /**
     * {@return the length of the run of one color starting at a pixel, at most to the end of the
     * row}
     *
     * @param data the rows
     * @param base the index of the row
     * @param start the first pixel of the run
     * @param width the pixels per row
     * @param black whether the run is of 1 bits
     */
    private static int span(byte[] data, int base, int start, int width, boolean black) {
        var position = start;
        while (position < width) {
            var value = data[base + (position >>> 3)] & 0xFF;
            if (black) {
                value ^= 0xFF;
            }
            var bit = position & 7;
            var rest = (value << bit) & 0xFF;
            if (rest == 0) {
                position += 8 - bit;
            } else {
                position += Integer.numberOfLeadingZeros(rest) - 24;
                break;
            }
        }
        return Math.min(position, width) - start;
    }

    /**
     * Writes the codes of one run: the 2560 make-up code while at least 2624 pixels remain, one
     * make-up code for the multiple of 64, then the terminating code.
     *
     * @param span the run length
     * @param codes {@link #WHITE} or {@link #BLACK}
     */
    private void run(int span, int[] codes) {
        while (span >= 2624) {
            putCode(code(codes, 2560));
            span -= 2560;
        }
        if (span >= 64) {
            var makeup = span & ~63;
            putCode(code(codes, makeup));
            span -= makeup;
        }
        putCode(code(codes, span));
    }

    /**
     * {@return the packed code of a run length, a terminating length from 0 to 63 or a make-up
     * length that is a multiple of 64 up to 2560}
     *
     * @param codes {@link #WHITE} or {@link #BLACK}
     * @param run the run length
     */
    private static int code(int[] codes, int run) {
        return run < 64 ? codes[run] : codes[63 + (run >>> 6)];
    }

    /**
     * Writes a packed code.
     *
     * @param code {@code length << 16 | bits}
     */
    private void putCode(int code) {
        put(code & 0xFFFF, code >>> 16);
    }

    /**
     * Writes the low bits of a value, most significant first.
     *
     * @param bits the value
     * @param length the number of bits, from 0 to 24
     */
    private void put(int bits, int length) {
        pending = (pending << length) | (bits & ((1 << length) - 1));
        pendingBits += length;
        while (pendingBits >= 8) {
            pendingBits -= 8;
            writeByte(pending >>> pendingBits);
        }
        pending &= (1 << pendingBits) - 1;
    }

    /**
     * Pads the pending bits with zeros to a byte boundary.
     */
    private void flush() {
        if (pendingBits > 0) {
            writeByte(pending << (8 - pendingBits));
            pending = 0;
            pendingBits = 0;
        }
    }

    /**
     * Appends a byte.
     *
     * @param value the byte; bits above 8 are ignored
     */
    private void writeByte(int value) {
        if (size == out.length) {
            out = Arrays.copyOf(out, out.length * 2);
        }
        out[size++] = (byte) value;
    }

    /**
     * Builds a code table from the code strings of T.4 tables 2 and 3.
     *
     * @param terminating the terminating codes for runs 0 to 63
     * @param makeup the make-up codes for runs 64 to 1728
     * @return the codes of runs 0 to 63 followed by the make-up codes of 64 to 2560
     */
    private static int[] codes(String[] terminating, String[] makeup) {
        var extended = CcittFaxDecoder.EXTENDED_MAKEUP;
        var table = new int[64 + makeup.length + extended.length];
        var at = 0;
        for (var code : terminating) {
            table[at++] = pack(code);
        }
        for (var code : makeup) {
            table[at++] = pack(code);
        }
        for (var code : extended) {
            table[at++] = pack(code);
        }
        return table;
    }

    /**
     * {@return a code string packed as {@code length << 16 | bits}}
     *
     * @param code the code as binary digits
     */
    private static int pack(String code) {
        return code.length() << 16 | Integer.parseInt(code, 2);
    }
}
