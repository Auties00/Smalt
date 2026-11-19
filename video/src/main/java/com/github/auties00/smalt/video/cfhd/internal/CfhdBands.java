/* Ports cineform-sdk (Codec/vlc.c GetRlcSigned, Codec/codebooks.c ScaleFSM, Codec/decoder.c DeQuantFSM and
 * DecodeBandFSM16sNoGapWithPeaks: the decoding of run-length coded highpass bands). Copyright GoPro Inc; Apache License
 * 2.0 or MIT. Modified for Smalt. */
package com.github.auties00.smalt.video.cfhd.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Decodes the run-length coded highpass bands of CineForm samples.
 *
 * <p>A band is a sequence of codewords of its codeset, each standing for a run of zeros or for one magnitude followed
 * by a sign bit (1 for negative), read most significant bit first until the band end code. The codes cover the rows
 * of the band padded to a multiple of 8 coefficients, as GoPro's decoder lays them out in memory; runs passing the end
 * of the band are cut there. Magnitudes are expanded by the codeset's companding curve and multiplied by the band's
 * quantization with 16-bit wraparound, and a value whose magnitude exceeds the band's peak level is replaced by the
 * next entry of its peak table. Instances hold the decoding tries of the three codesets and are immutable and
 * thread-safe.
 */
public final class CfhdBands {
    /**
     * The leaf value of the band end code.
     */
    private static final int END = -2;

    /**
     * The decoder shared by every sample.
     */
    public static final CfhdBands INSTANCE = new CfhdBands();

    /**
     * The tries of the codesets, two child indices per node.
     */
    private final int[][] children = new int[3][];

    /**
     * The leaves of the tries: for each node, the index of its codeword, {@link #END} or -1 for an inner node.
     */
    private final int[][] leaves = new int[3][];

    /**
     * The codewords of the codesets, as {@link CfhdCodebooks} lists them.
     */
    private final int[][] codes = {CfhdCodebooks.CODESET_9, CfhdCodebooks.CODESET_17, CfhdCodebooks.CODESET_18};

    private CfhdBands() {
        int[] endSizes = {CfhdCodebooks.END_SIZE_9, CfhdCodebooks.END_SIZE_17, CfhdCodebooks.END_SIZE_18};
        int[] endCodes = {CfhdCodebooks.END_CODE_9, CfhdCodebooks.END_CODE_17, CfhdCodebooks.END_CODE_18};
        for (var set = 0; set < 3; set++) {
            var book = codes[set];
            var capacity = 2;
            for (var i = 0; i < book.length; i += 4) {
                capacity += book[i];
            }
            capacity += endSizes[set];
            var child = new int[2 * capacity];
            var leaf = new int[capacity];
            java.util.Arrays.fill(child, -1);
            java.util.Arrays.fill(leaf, -1);
            var nodes = 1;
            for (var entry = 0; entry <= book.length / 4; entry++) {
                var size = entry < book.length / 4 ? book[entry * 4] : endSizes[set];
                var bits = entry < book.length / 4 ? book[entry * 4 + 1] : endCodes[set];
                var node = 0;
                for (var bit = size - 1; bit >= 0; bit--) {
                    var b = bits >>> bit & 1;
                    if (child[node * 2 + b] < 0) {
                        child[node * 2 + b] = nodes++;
                    }
                    node = child[node * 2 + b];
                }
                leaf[node] = entry < book.length / 4 ? entry : END;
            }
            children[set] = child;
            leaves[set] = leaf;
        }
    }

    /**
     * Returns a magnitude after the companding of a codeset.
     *
     * @param set the codeset, 0 for codeset 9, 1 for codeset 17 and 2 for codeset 18
     * @param magnitude the coded magnitude
     * @return the expanded magnitude
     */
    static int expand(int set, int magnitude) {
        return switch (set) {
            case 1 -> magnitude + (int) ((double) magnitude * magnitude * magnitude * 768 / (256 * 256 * 256));
            case 2 -> magnitude;
            default -> {
                var value = magnitude;
                if (value >= 40) {
                    if (value >= 54) {
                        value = ((value - 54) << 2) + 54;
                    }
                    value = ((value - 40) << 2) + 40;
                }
                yield value;
            }
        };
    }

    /**
     * Decodes one band into a plane of coefficients.
     *
     * @param data the sample
     * @param start the index of the band's first byte
     * @param end the index after the last byte the band may use
     * @param set the codeset, from 0 to 2
     * @param quantization the band's quantization, at least 1
     * @param peakLevel the largest magnitude stored in the codes, or 0 when the band has no peak table
     * @param peakTable the index of the band's peak table, entries of 16-bit little-endian signed values
     * @param out receives the coefficients, row after row, {@code width} per row
     * @param width the band width; the codes cover rows padded to a multiple of 8 coefficients, whose padding is
     * dropped
     * @param height the band height
     * @return the index of the first byte after the band end code, rounded up to a multiple of 4 from {@code start}
     * @throws MediaException.Malformed if a code is invalid or the data ends before the band end code
     */
    public int decode(byte[] data, int start, int end, int set, int quantization, int peakLevel, int peakTable,
            short[] out, int width, int height) {
        var child = children[set];
        var leaf = leaves[set];
        var book = codes[set];
        var stride = width + 7 & ~7;
        var total = stride * height;
        var position = 0;
        var bit = (long) start * 8;
        var limit = (long) end * 8;
        var peak = peakTable;
        java.util.Arrays.fill(out, 0, width * height, (short) 0);
        while (true) {
            var node = 0;
            while (leaf[node] == -1) {
                if (bit >= limit) {
                    throw new MediaException.Malformed("CineForm band ends before its end code");
                }
                var b = data[(int) (bit >>> 3)] >>> (7 - (int) (bit & 7)) & 1;
                bit++;
                node = child[node * 2 + b];
                if (node < 0) {
                    throw new MediaException.Malformed("CineForm band holds an invalid code");
                }
            }
            var entry = leaf[node];
            if (entry == END) {
                break;
            }
            var count = book[entry * 4 + 2];
            var magnitude = book[entry * 4 + 3];
            if (magnitude == 0) {
                // encoders may close a band with a zero run longer than what is left of it
                position = (int) Math.min(total, (long) position + count);
                continue;
            }
            if (bit >= limit) {
                throw new MediaException.Malformed("CineForm band ends before a sign bit");
            }
            var negative = (data[(int) (bit >>> 3)] >>> (7 - (int) (bit & 7)) & 1) != 0;
            bit++;
            int value = (short) (expand(set, magnitude) * quantization);
            if (negative) {
                value = (short) -value;
            }
            if (peakLevel > 0 && Math.abs(value) > peakLevel) {
                if (peak < 0 || peak + 2 > end) {
                    throw new MediaException.Malformed("CineForm band refers past its peak table");
                }
                value = (short) ((data[peak] & 0xFF) | data[peak + 1] << 8);
                peak += 2;
            }
            // a run passing the end of the band is cut there, as GoPro's decoder cuts it
            var run = Math.min(count, total - position);
            for (var i = 0; i < run; i++, position++) {
                var column = position % stride;
                if (column < width) {
                    out[position / stride * width + column] = (short) value;
                }
            }
        }
        var consumed = (int) ((bit + 7) >>> 3) - start;
        return start + (consumed + 3 & ~3);
    }
}
