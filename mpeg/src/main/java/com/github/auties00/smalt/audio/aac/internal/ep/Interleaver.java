/* Ports the bytewise interleaving and the pass-through of an empty input of interleave.c of the ISO/IEC 14496-5:2001
 * MPEG-4 Audio epTool (NTT DoCoMo, 1998); MPEG reference software licence, see THIRD-PARTY-NOTICES. Modified for
 * Smalt. */
package com.github.auties00.smalt.audio.aac.internal.ep;

/**
 * Undoes and applies one stage of the recursive interleaver of the MPEG-4 audio error protection tool (ISO/IEC
 * 14496-3 subclause 1.8.4.8).
 *
 * <p>A stage writes its protected input {@code X} row by row into a matrix of width {@code W} and its other input
 * {@code Y} into the remaining cells column by column, and reads the matrix column by column. The matrix holds
 * {@code D = (lx + ly) / W} rows, the first {@code d = lx + ly - D * W} columns one more. A Reed-Solomon class is
 * written byte by byte instead, one byte per column of a matrix as wide as the class has bytes; the bits that pad
 * its information to whole bytes occupy cells but are not transmitted. A stage with an empty input passes the
 * other input through unchanged. Bits are held one per byte.
 */
final class Interleaver {
    /**
     * Prevents instantiation.
     */
    private Interleaver() {
    }

    /**
     * Returns the index of the first cell of a column.
     *
     * @param column the column index, from 0
     * @param rows the number of rows of the short columns
     * @param longColumns the number of columns one row longer
     * @return the cell index of the column's first row
     */
    private static int start(int column, int rows, int longColumns) {
        return column * rows + Math.min(column, longColumns);
    }

    /**
     * Separates the two inputs of a bitwise stage.
     *
     * @param in the stage output
     * @param offset the index of its first bit
     * @param total the number of bits, both inputs together
     * @param xLength the number of bits of {@code X}, at most {@code total}
     * @param width the matrix width, positive
     * @param x receives {@code X}
     * @param xOffset the index of {@code X}'s first bit
     * @param y receives {@code Y}
     * @param yOffset the index of {@code Y}'s first bit
     */
    static void split(byte[] in, int offset, int total, int xLength, int width, byte[] x, int xOffset, byte[] y,
                      int yOffset) {
        if (xLength == 0 || xLength == total) {
            System.arraycopy(in, offset, xLength == 0 ? y : x, xLength == 0 ? yOffset : xOffset, total);
            return;
        }
        var rows = total / width;
        var longColumns = total - rows * width;
        var xRows = xLength / width;
        var xLong = xLength - xRows * width;
        for (var i = 0; i < xLength; i++) {
            x[xOffset + i] = in[offset + start(i % width, rows, longColumns) + i / width];
        }
        var next = yOffset;
        for (var column = 0; column < width; column++) {
            var first = start(column, rows, longColumns);
            var height = rows + (column < longColumns ? 1 : 0);
            var taken = xRows + (column < xLong ? 1 : 0);
            var count = height - taken;
            System.arraycopy(in, offset + first + taken, y, next, count);
            next += count;
        }
    }

    /**
     * Combines the two inputs of a bitwise stage.
     *
     * @param x the protected input
     * @param xOffset the index of its first bit
     * @param xLength its number of bits
     * @param y the other input
     * @param yOffset the index of its first bit
     * @param yLength its number of bits
     * @param width the matrix width, positive
     * @param out receives the stage output
     * @param outOffset the index of its first bit
     */
    static void join(byte[] x, int xOffset, int xLength, byte[] y, int yOffset, int yLength, int width, byte[] out,
                     int outOffset) {
        var total = xLength + yLength;
        if (xLength == 0 || yLength == 0) {
            System.arraycopy(xLength == 0 ? y : x, xLength == 0 ? yOffset : xOffset, out, outOffset, total);
            return;
        }
        var rows = total / width;
        var longColumns = total - rows * width;
        var xRows = xLength / width;
        var xLong = xLength - xRows * width;
        for (var i = 0; i < xLength; i++) {
            out[outOffset + start(i % width, rows, longColumns) + i / width] = x[xOffset + i];
        }
        var next = yOffset;
        for (var column = 0; column < width; column++) {
            var first = start(column, rows, longColumns);
            var height = rows + (column < longColumns ? 1 : 0);
            var taken = xRows + (column < xLong ? 1 : 0);
            var count = height - taken;
            System.arraycopy(y, next, out, outOffset + first + taken, count);
            next += count;
        }
    }

    /**
     * Returns the width of the bytewise matrix of a Reed-Solomon class.
     *
     * @param information the number of information bits of the class
     * @param parityBits the number of parity bits
     * @return the number of bytes of the class with its information padded to whole bytes
     */
    private static int byteWidth(int information, int parityBits) {
        return (information + 7) / 8 + parityBits / 8;
    }

    /**
     * Separates the two inputs of a bytewise stage.
     *
     * @param in the stage output
     * @param offset the index of its first bit
     * @param total the number of transmitted bits, both inputs together
     * @param information the number of information bits of the Reed-Solomon class {@code X}
     * @param parityBits the number of its parity bits, a multiple of 8
     * @param x receives {@code X}, its information then its parity
     * @param xOffset the index of {@code X}'s first bit
     * @param y receives {@code Y}
     * @param yOffset the index of {@code Y}'s first bit
     */
    static void splitBytes(byte[] in, int offset, int total, int information, int parityBits, byte[] x, int xOffset,
                           byte[] y, int yOffset) {
        var xLength = information + parityBits;
        if (xLength == 0 || xLength == total) {
            System.arraycopy(in, offset, xLength == 0 ? y : x, xLength == 0 ? yOffset : xOffset, total);
            return;
        }
        var width = byteWidth(information, parityBits);
        var pad = (8 - information % 8) % 8;
        var cells = total + pad;
        var rows = cells / width;
        var longColumns = cells - rows * width;
        var dataColumns = (information + 7) / 8;
        var transmitted = offset;
        var xNext = xOffset;
        var parityNext = xOffset + information;
        var yNext = yOffset;
        for (var column = 0; column < width; column++) {
            var height = rows + (column < longColumns ? 1 : 0);
            for (var row = 0; row < height; row++) {
                if (row < 8) {
                    if (column < dataColumns) {
                        if (column * 8 + row < information) {
                            x[xNext++] = in[transmitted++];
                        }
                    } else {
                        x[parityNext++] = in[transmitted++];
                    }
                } else {
                    y[yNext++] = in[transmitted++];
                }
            }
        }
    }

    /**
     * Combines the two inputs of a bytewise stage.
     *
     * @param x the Reed-Solomon class, its information then its parity
     * @param xOffset the index of its first bit
     * @param information the number of its information bits
     * @param parityBits the number of its parity bits, a multiple of 8
     * @param y the other input
     * @param yOffset the index of its first bit
     * @param yLength its number of bits
     * @param out receives the stage output
     * @param outOffset the index of its first bit
     */
    static void joinBytes(byte[] x, int xOffset, int information, int parityBits, byte[] y, int yOffset, int yLength,
                          byte[] out, int outOffset) {
        var xLength = information + parityBits;
        if (xLength == 0 || yLength == 0) {
            System.arraycopy(xLength == 0 ? y : x, xLength == 0 ? yOffset : xOffset, out, outOffset,
                    xLength + yLength);
            return;
        }
        var width = byteWidth(information, parityBits);
        var pad = (8 - information % 8) % 8;
        var cells = xLength + yLength + pad;
        var rows = cells / width;
        var longColumns = cells - rows * width;
        var dataColumns = (information + 7) / 8;
        var transmitted = outOffset;
        var xNext = xOffset;
        var parityNext = xOffset + information;
        var yNext = yOffset;
        for (var column = 0; column < width; column++) {
            var height = rows + (column < longColumns ? 1 : 0);
            for (var row = 0; row < height; row++) {
                if (row < 8) {
                    if (column < dataColumns) {
                        if (column * 8 + row < information) {
                            out[transmitted++] = x[xNext++];
                        }
                    } else {
                        out[transmitted++] = x[parityNext++];
                    }
                } else {
                    out[transmitted++] = y[yNext++];
                }
            }
        }
    }
}
