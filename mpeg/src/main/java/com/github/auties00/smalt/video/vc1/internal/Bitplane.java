package com.github.auties00.smalt.video.vc1.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Decodes one bitplane of SMPTE ST 421 section 8.7: a binary value per macroblock, coded at the picture level or, in
 * raw mode, in each macroblock header.
 *
 * <p>Instances are not thread-safe; a decoder keeps one per bitplane syntax element and reuses it.
 */
final class Bitplane {
    /**
     * The value of each macroblock, rows {@link #width} apart.
     */
    private byte[] values = new byte[0];

    /**
     * Whether the plane is coded in raw mode.
     */
    private boolean raw;

    /**
     * The macroblock columns of the plane.
     */
    private int width;

    /**
     * {@return whether the plane is coded in raw mode, so each macroblock header carries its bit}
     */
    boolean raw() {
        return raw;
    }

    /**
     * Returns the value of a macroblock.
     *
     * @param x the macroblock column
     * @param y the macroblock row
     * @return the value, 0 or 1
     */
    int get(int x, int y) {
        return values[y * width + x];
    }

    /**
     * Sets the value of a macroblock, for raw mode.
     *
     * @param x the macroblock column
     * @param y the macroblock row
     * @param value the value, 0 or 1
     */
    void set(int x, int y, int value) {
        values[y * width + x] = (byte) value;
    }

    /**
     * Marks the plane as absent: every value is 0 and no macroblock carries a bit.
     *
     * @param columns the macroblock columns
     * @param rows the macroblock rows
     */
    void clear(int columns, int rows) {
        ensure(columns, rows);
        java.util.Arrays.fill(values, 0, columns * rows, (byte) 0);
        raw = false;
    }

    /**
     * Sizes the plane for a picture.
     *
     * @param columns the macroblock columns
     * @param rows the macroblock rows
     */
    private void ensure(int columns, int rows) {
        width = columns;
        if (values.length < columns * rows) {
            values = new byte[columns * rows];
        }
    }

    /**
     * Decodes the plane.
     *
     * @param bits the reader, at INVERT
     * @param columns the macroblock columns
     * @param rows the macroblock rows
     * @throws MediaException.Malformed if a code is invalid
     */
    void decode(Bits bits, int columns, int rows) {
        ensure(columns, rows);
        var invert = bits.readBit();
        var mode = Vc1Codes.BITPLANE_MODE.decode(bits);
        var count = columns * rows;
        raw = false;
        switch (mode) {
            case 0 -> {
                raw = true;
                java.util.Arrays.fill(values, 0, count, (byte) 0);
                return;
            }
            case 1, 2 -> norm2(bits, count);
            case 3, 4 -> norm6(bits, columns, rows);
            case 5 -> {
                for (var y = 0; y < rows; y++) {
                    rowSkip(bits, y, 0, columns);
                }
            }
            case 6 -> {
                for (var x = 0; x < columns; x++) {
                    columnSkip(bits, x, 0, rows);
                }
            }
            default -> throw new MediaException.Malformed("invalid VC-1 bitplane coding mode");
        }
        if (mode == 2 || mode == 4) {
            differential(columns, rows, invert);
        } else if (invert != 0) {
            for (var i = 0; i < count; i++) {
                values[i] ^= 1;
            }
        }
    }

    /**
     * Decodes the Norm-2 mode: pairs of values in raster order, the first value alone when the count is odd.
     *
     * @param bits the reader
     * @param count the number of values
     */
    private void norm2(Bits bits, int count) {
        var i = 0;
        if ((count & 1) != 0) {
            values[0] = (byte) bits.readBit();
            i = 1;
        }
        for (; i < count; i += 2) {
            var pair = Vc1Codes.NORM2.decode(bits);
            if (pair < 0) {
                throw new MediaException.Malformed("invalid VC-1 Norm-2 bitplane code");
            }
            values[i] = (byte) (pair & 1);
            values[i + 1] = (byte) (pair >>> 1);
        }
    }

    /**
     * Decodes the Norm-6 mode: 2x3 or 3x2 tiles, then the columns and the row the tiles leave out in column-skip and
     * row-skip coding.
     *
     * @param bits the reader
     * @param columns the macroblock columns
     * @param rows the macroblock rows
     */
    private void norm6(Bits bits, int columns, int rows) {
        int skipColumns;
        var skipRows = 0;
        if (rows % 3 == 0 && columns % 3 != 0) {
            skipColumns = columns & 1;
            for (var y = 0; y < rows; y += 3) {
                for (var x = skipColumns; x < columns; x += 2) {
                    var k = tile(bits);
                    for (var i = 0; i < 6; i++) {
                        values[(y + i / 2) * width + x + (i & 1)] = (byte) ((k >>> i) & 1);
                    }
                }
            }
        } else {
            skipColumns = columns % 3;
            skipRows = rows & 1;
            for (var y = skipRows; y < rows; y += 2) {
                for (var x = skipColumns; x < columns; x += 3) {
                    var k = tile(bits);
                    for (var i = 0; i < 6; i++) {
                        values[(y + i / 3) * width + x + i % 3] = (byte) ((k >>> i) & 1);
                    }
                }
            }
        }
        for (var x = 0; x < skipColumns; x++) {
            columnSkip(bits, x, 0, rows);
        }
        if (skipRows != 0) {
            rowSkip(bits, 0, skipColumns, columns);
        }
    }

    /**
     * Reads one Norm-6 tile.
     *
     * @param bits the reader
     * @return the six values of the tile, the first in bit 0
     * @throws MediaException.Malformed if the code is invalid
     */
    private static int tile(Bits bits) {
        var k = Vc1Codes.NORM6.decode(bits);
        if (k < 0) {
            throw new MediaException.Malformed("invalid VC-1 Norm-6 bitplane code");
        }
        return k;
    }

    /**
     * Decodes one row in row-skip coding: a 0 bit for a row of zeros, or a 1 bit then one bit per value.
     *
     * @param bits the reader
     * @param y the row
     * @param from the first column
     * @param to the column after the last
     */
    private void rowSkip(Bits bits, int y, int from, int to) {
        if (bits.readBit() == 0) {
            for (var x = from; x < to; x++) {
                values[y * width + x] = 0;
            }
        } else {
            for (var x = from; x < to; x++) {
                values[y * width + x] = (byte) bits.readBit();
            }
        }
    }

    /**
     * Decodes one column in column-skip coding: a 0 bit for a column of zeros, or a 1 bit then one bit per value.
     *
     * @param bits the reader
     * @param x the column
     * @param from the first row
     * @param to the row after the last
     */
    private void columnSkip(Bits bits, int x, int from, int to) {
        if (bits.readBit() == 0) {
            for (var y = from; y < to; y++) {
                values[y * width + x] = 0;
            }
        } else {
            for (var y = from; y < to; y++) {
                values[y * width + x] = (byte) bits.readBit();
            }
        }
    }

    /**
     * Undoes the differential coding of the Diff-2 and Diff-6 modes (section 8.7.3.7): each value is predicted from the
     * value to its left, the value above, or the INVERT flag.
     *
     * @param columns the macroblock columns
     * @param rows the macroblock rows
     * @param invert the INVERT flag
     */
    private void differential(int columns, int rows, int invert) {
        for (var y = 0; y < rows; y++) {
            for (var x = 0; x < columns; x++) {
                int predictor;
                if (x == 0 && y == 0) {
                    predictor = invert;
                } else if (x == 0) {
                    predictor = values[(y - 1) * width];
                } else if (y > 0 && values[(y - 1) * width + x] != values[y * width + x - 1]) {
                    predictor = invert;
                } else {
                    predictor = values[y * width + x - 1];
                }
                values[y * width + x] ^= (byte) predictor;
            }
        }
    }
}
