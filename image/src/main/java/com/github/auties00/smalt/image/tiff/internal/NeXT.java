/* Ports libtiff 4.7.1 (tif_next.c, NeXTDecode). Copyright 1988-1997 Sam Leffler, 1991-1997 Silicon Graphics, Inc.;
 * libtiff license. Modified for Smalt. */
package com.github.auties00.smalt.image.tiff.internal;

import java.util.Arrays;

/**
 * Decodes NeXT 2-bit gray data (TIFF compression 32766).
 *
 * <p>Each row starts with a code byte: 0x00 gives the whole row as literal bytes, 0x40 a literal span
 * (a big-endian 16-bit byte offset and byte count, then the bytes) over a white row, and any other
 * value starts runs, each byte holding a 2-bit gray in its top bits and a pixel count in its low six,
 * until the row is full.
 */
final class NeXT {
    private static final int LITERAL_ROW = 0x00;
    private static final int LITERAL_SPAN = 0x40;

    private NeXT() {
        throw new AssertionError();
    }

    /**
     * Decodes rows until the data or the rows end.
     *
     * @param raw the stored bytes
     * @param length the number of stored bytes
     * @param chunk receives the packed 2-bit samples
     * @param rowBytes the bytes of a row
     * @param columns the pixels of a row
     * @param rows the number of rows of the strip or tile
     * @return the bytes of the rows decoded in full
     */
    static int decode(byte[] raw, int length, byte[] chunk, int rowBytes, int columns, int rows) {
        var p = 0;
        for (var row = 0; row < rows; row++) {
            var start = row * rowBytes;
            if (p >= length) {
                return start;
            }
            Arrays.fill(chunk, start, start + rowBytes, (byte) 0xFF);
            var n = raw[p++] & 0xFF;
            if (n == LITERAL_ROW) {
                if (length - p < rowBytes) {
                    return start;
                }
                System.arraycopy(raw, p, chunk, start, rowBytes);
                p += rowBytes;
            } else if (n == LITERAL_SPAN) {
                if (length - p < 4) {
                    return start;
                }
                var offset = (raw[p] & 0xFF) << 8 | (raw[p + 1] & 0xFF);
                var count = (raw[p + 2] & 0xFF) << 8 | (raw[p + 3] & 0xFF);
                if (length - p < 4 + count || offset + count > rowBytes) {
                    return start;
                }
                System.arraycopy(raw, p + 4, chunk, start + offset, count);
                p += 4 + count;
            } else {
                var pixels = 0;
                var op = start;
                var opOffset = 0;
                while (true) {
                    var grey = (n >> 6) & 3;
                    n &= 0x3F;
                    while (n-- > 0 && pixels < columns && opOffset < rowBytes) {
                        switch (pixels++ & 3) {
                            case 0 -> chunk[op] = (byte) (grey << 6);
                            case 1 -> chunk[op] |= (byte) (grey << 4);
                            case 2 -> chunk[op] |= (byte) (grey << 2);
                            default -> {
                                chunk[op++] |= (byte) grey;
                                opOffset++;
                            }
                        }
                    }
                    if (pixels >= columns) {
                        break;
                    }
                    if (opOffset >= rowBytes || p >= length) {
                        return start;
                    }
                    n = raw[p++] & 0xFF;
                }
            }
        }
        return rows * rowBytes;
    }
}
