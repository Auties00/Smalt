package com.github.auties00.smalt.video.h263.internal;

/**
 * Filters the block edges of a reconstructed picture in the coding loop: the deblocking filter of ITU-T H.263 J.3,
 * and the block boundary filters of the Reduced-Resolution Update mode (Q.7).
 */
final class LoopFilter {
    /**
     * The filter strength of each quantizer (Table J.2).
     */
    private static final int[] STRENGTH = {
            0, 1, 1, 2, 2, 3, 3, 4, 4, 4, 5, 5, 6, 6, 7, 7, 7, 8, 8, 8, 9, 9, 9, 10, 10, 10, 11, 11, 11, 12, 12, 12
    };

    /**
     * Prevents instantiation.
     */
    private LoopFilter() {
    }

    /**
     * Filters every block edge of a picture.
     *
     * @param picture the picture, filtered in place
     * @param mbColumns the macroblock columns of the coding grid
     * @param mbRows the macroblock rows of the coding grid
     * @param scale 1, or 2 for the 32 by 32 macroblocks of the Reduced-Resolution Update mode
     * @param coded whether each macroblock was coded or INTRA
     * @param quant the quantizer of each macroblock
     * @param segments the video picture segment of each macroblock, or {@code null} to filter across segments
     * @param deblocking whether the Deblocking Filter mode is on
     * @param modifiedQuantization whether the chrominance quantizer of the Modified Quantization mode applies
     */
    static void filter(PlusPicture picture, int mbColumns, int mbRows, int scale, boolean[] coded, int[] quant,
                       int[] segments, boolean deblocking, boolean modifiedQuantization) {
        for (var component = 0; component < 3; component++) {
            var plane = picture.plane(component);
            var stride = component == 0 ? picture.lumaStride() : picture.chromaStride();
            var chroma = component != 0;
            var blockSize = 8 * scale;
            var blocksPerMb = chroma ? 1 : 2;
            var columns = mbColumns * blocksPerMb;
            var rows = mbRows * blocksPerMb;
            var shift = chroma ? 0 : 1;
            for (var by = 1; by < rows; by++) {
                for (var bx = 0; bx < columns; bx++) {
                    var first = ((by - 1) >> shift) * mbColumns + (bx >> shift);
                    var second = (by >> shift) * mbColumns + (bx >> shift);
                    if (!active(first, second, coded, segments)) {
                        continue;
                    }
                    var at = by * blockSize * stride + bx * blockSize;
                    var strength = strength(first, second, coded, quant, chroma, modifiedQuantization);
                    for (var i = 0; i < blockSize; i++) {
                        edge(plane, at + i, stride, strength, scale, deblocking);
                    }
                }
            }
            for (var by = 0; by < rows; by++) {
                for (var bx = 1; bx < columns; bx++) {
                    var first = (by >> shift) * mbColumns + ((bx - 1) >> shift);
                    var second = (by >> shift) * mbColumns + (bx >> shift);
                    if (!active(first, second, coded, segments)) {
                        continue;
                    }
                    var at = by * blockSize * stride + bx * blockSize;
                    var strength = strength(first, second, coded, quant, chroma, modifiedQuantization);
                    for (var i = 0; i < blockSize; i++) {
                        edge(plane, at + i * stride, 1, strength, scale, deblocking);
                    }
                }
            }
        }
    }

    /**
     * Returns whether the edge between the blocks of two macroblocks is filtered: one of them is coded, and both are
     * in the same video picture segment when segments separate.
     *
     * @param first the macroblock of block1
     * @param second the macroblock of block2
     * @param coded whether each macroblock was coded
     * @param segments the segments, or {@code null}
     * @return whether the edge is filtered
     */
    private static boolean active(int first, int second, boolean[] coded, int[] segments) {
        if (!coded[first] && !coded[second]) {
            return false;
        }
        return segments == null || segments[first] == segments[second];
    }

    /**
     * Returns the filter strength of an edge: that of block2's quantizer when it is coded, block1's otherwise.
     *
     * @param first the macroblock of block1
     * @param second the macroblock of block2
     * @param coded whether each macroblock was coded
     * @param quant the quantizers
     * @param chroma whether the edge is in a chrominance plane
     * @param modifiedQuantization whether the chrominance quantizer of the Modified Quantization mode applies
     * @return the strength
     */
    private static int strength(int first, int second, boolean[] coded, int[] quant, boolean chroma,
                                boolean modifiedQuantization) {
        var q = quant[coded[second] ? second : first];
        if (chroma && modifiedQuantization) {
            q = MacroblockParser.chromaQuant(q);
        }
        return STRENGTH[q];
    }

    /**
     * Filters one line of samples across an edge.
     *
     * @param plane the plane, filtered in place
     * @param at the index of the first sample after the edge
     * @param step the distance between successive samples of the line
     * @param strength the filter strength
     * @param scale 2 in the Reduced-Resolution Update mode
     * @param deblocking whether the Deblocking Filter mode is on
     */
    private static void edge(byte[] plane, int at, int step, int strength, int scale, boolean deblocking) {
        if (scale == 2 && !deblocking) {
            var a = plane[at - step] & 0xFF;
            var b = plane[at] & 0xFF;
            plane[at - step] = (byte) ((3 * a + b + 2) >> 2);
            plane[at] = (byte) ((a + 3 * b + 2) >> 2);
            return;
        }
        var a = plane[at - 2 * step] & 0xFF;
        var b = plane[at - step] & 0xFF;
        var c = plane[at] & 0xFF;
        var d = plane[at + step] & 0xFF;
        var delta = (a - 4 * b + 4 * c - d) / 8;
        int d1;
        if (scale == 2) {
            d1 = delta;
        } else {
            var magnitude = Math.abs(delta);
            d1 = Integer.signum(delta) * Math.max(0, magnitude - Math.max(0, 2 * (magnitude - strength)));
        }
        var limit = Math.abs(d1 / 2);
        var d2 = Math.max(-limit, Math.min(limit, (a - d) / 4));
        plane[at - 2 * step] = (byte) clip(a - d2);
        plane[at - step] = (byte) clip(b + d1);
        plane[at] = (byte) clip(c - d1);
        plane[at + step] = (byte) clip(d + d2);
    }

    /**
     * Clips a value to the 8-bit sample range.
     *
     * @param value the filtered sample, possibly outside 0 to 255
     * @return the clipped value
     */
    private static int clip(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}
