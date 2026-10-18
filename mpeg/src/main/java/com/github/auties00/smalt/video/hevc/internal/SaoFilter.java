package com.github.auties00.smalt.video.hevc.internal;

import java.util.Arrays;

/**
 * Applies the sample adaptive offset process of ITU-T H.265 8.7.3 to a deblocked picture: the band
 * offset and the four edge offset classes of every coding tree block, with the slice, tile and
 * picture boundary rules for edge offset neighbors.
 *
 * <p>A picture may be filtered one range of sample rows at a time, top to bottom, as the
 * deblocking of the rows completes.
 *
 * <p>Instances are not thread-safe.
 *
 * @implNote This implementation decides whether each of the eight neighboring coding tree blocks
 * may be used once per coding tree block: slices and tiles consist of whole coding tree blocks,
 * and the order of two {@code MinTbAddrZs} values of different coding tree blocks is the order of
 * the blocks in tile scan, so the per-sample conditions of 8.7.3.2 depend only on the neighboring
 * block. Samples whose neighbors lie inside the block then run without any check.
 */
public final class SaoFilter {
    /**
     * {@code hPos} and {@code vPos} of Table 8-13, indexed {@code class * 4}: hPos[0], vPos[0],
     * hPos[1], vPos[1].
     */
    private static final int[] OFFSETS = {
            -1, 0, 1, 0,
            0, -1, 0, 1,
            -1, -1, 1, 1,
            1, -1, -1, 1
    };

    /**
     * The deblocked samples of each component, saved row by row before the offsets are applied:
     * the filter of a row reads the deblocked samples of the rows next to it.
     */
    private final short[][] copies = new short[3][];

    /**
     * The deblocked samples of each component of a picture with 8-bit planes, saved like
     * {@link #copies}.
     */
    private final byte[][] copies8 = new byte[3][];

    /**
     * The band offset of each of the 32 bands of the block being filtered.
     */
    private final int[] table = new int[32];

    /**
     * Whether the edge offset of the block being filtered may read each of its eight neighboring
     * blocks and itself, indexed {@code (dy + 1) * 3 + dx + 1}.
     */
    private final boolean[] usable = new boolean[9];

    /**
     * The edge offset of each {@code 2 + sign(a) + sign(b)} value of the block being filtered.
     */
    private final int[] lut = new int[5];

    /**
     * Constructs a filter.
     */
    public SaoFilter() {
    }

    /**
     * Applies the sample adaptive offset to a range of luma sample rows of a deblocked picture and
     * to the chroma rows they cover; the rows above the range must have been filtered already, and
     * the deblocking of the range and of the sample row below it must be complete.
     *
     * @param frame the deblocked picture, modified in place
     * @param pic the coding information of the picture
     * @param from the first luma sample row, which is a multiple of 2
     * @param to the luma sample row after the last, a multiple of 2 or the picture height
     * @param protect whether some samples may have to keep their deblocked value: transquant bypass
     * is enabled in a picture parameter set of the picture, or PCM samples skip the
     * loop filters
     */
    public void filterLines(HevcFrame frame, PictureState pic, int from, int to, boolean protect) {
        var sps = pic.sps;
        if (!sps.saoEnabled || from >= to) {
            return;
        }
        component(pic, 0, frame.y, frame.y8, frame.width, frame.height, protect, from, Math.min(to, frame.height));
        if (frame.chromaFormat != 0) {
            var cFrom = from >> sps.chromaShiftY;
            var cTo = to >= frame.height ? frame.chromaHeight : to >> sps.chromaShiftY;
            component(pic, 1, frame.cb, frame.cb8, frame.chromaWidth, frame.chromaHeight, protect, cFrom, cTo);
            component(pic, 2, frame.cr, frame.cr8, frame.chromaWidth, frame.chromaHeight, protect, cFrom, cTo);
        }
    }

    /**
     * Applies the offsets of one component to a range of sample rows.
     *
     * @param pic the coding information
     * @param cIdx the component
     * @param plane the 16-bit component plane, or {@code null} for an 8-bit picture
     * @param plane8 the 8-bit component plane, or {@code null} for a deeper picture
     * @param width the plane width
     * @param height the plane height
     * @param protect whether some samples may have to keep their deblocked value
     * @param yFrom the first sample row
     * @param yTo the sample row after the last
     */
    private void component(PictureState pic, int cIdx, short[] plane, byte[] plane8, int width, int height,
                           boolean protect, int yFrom, int yTo) {
        var sps = pic.sps;
        var size = sps.sizeInCtbs;
        var widthCtbs = sps.widthInCtbs;
        var any = false;
        var ctbH = sps.ctbSize >> (cIdx == 0 ? 0 : sps.chromaShiftY);
        var from = yFrom / ctbH;
        var to = (yTo + ctbH - 1) / ctbH;
        var last = Math.min(to * widthCtbs, size);
        for (var ctb = from * widthCtbs; ctb < last && !any; ctb++) {
            any = pic.saoType[cIdx * size + ctb] != 0;
        }
        var length = width * height;
        var first = Math.min(yFrom, height) * width;
        var end = Math.min(yTo + 1, height) * width;
        short[] copy = null;
        byte[] copy8 = null;
        if (plane8 != null) {
            copy8 = copies8[cIdx];
            if (copy8 == null || copy8.length < length + HevcFrame.PADDING) {
                copy8 = new byte[length + HevcFrame.PADDING];
                copies8[cIdx] = copy8;
            }
            System.arraycopy(plane8, first, copy8, first, end - first);
        } else {
            copy = copies[cIdx];
            if (copy == null || copy.length < length) {
                copy = new short[length];
                copies[cIdx] = copy;
            }
            System.arraycopy(plane, first, copy, first, end - first);
        }
        if (any) {
            rows(pic, cIdx, plane, copy, plane8, copy8, width, height, protect, from, to, yFrom, yTo);
        }
    }

    /**
     * Applies the offsets of one component to a range of coding tree block rows.
     *
     * @param pic the coding information
     * @param cIdx the component
     * @param plane the 16-bit component plane, or {@code null} for an 8-bit picture
     * @param src the deblocked samples of the 16-bit component, or {@code null}
     * @param plane8 the 8-bit component plane, or {@code null} for a deeper picture
     * @param src8 the deblocked samples of the 8-bit component, or {@code null}
     * @param width the plane width
     * @param height the plane height
     * @param protect whether some samples may have to keep their deblocked value
     * @param from the first coding tree block row
     * @param to the row after the last
     * @param yFrom the first sample row to filter
     * @param yTo the sample row after the last to filter
     */
    private void rows(PictureState pic, int cIdx, short[] plane, short[] src, byte[] plane8, byte[] src8,
                             int width, int height, boolean protect, int from, int to, int yFrom, int yTo) {
        var sps = pic.sps;
        var sx = cIdx == 0 ? 0 : sps.chromaShiftX;
        var sy = cIdx == 0 ? 0 : sps.chromaShiftY;
        var ctbW = sps.ctbSize >> sx;
        var ctbH = sps.ctbSize >> sy;
        var size = sps.sizeInCtbs;
        var bitDepth = cIdx == 0 ? sps.bitDepthLuma : sps.bitDepthChroma;
        for (var ry = from; ry < to; ry++) {
            for (var rx = 0; rx < sps.widthInCtbs; rx++) {
                var ctb = ry * sps.widthInCtbs + rx;
                var header = pic.ctbHeader[ctb];
                if (header == null) {
                    continue;
                }
                if (cIdx == 0 ? !header.saoLuma : !header.saoChroma) {
                    continue;
                }
                var type = pic.saoType[cIdx * size + ctb];
                if (type == 0) {
                    continue;
                }
                var x0 = rx * ctbW;
                var y0 = ry * ctbH;
                var w = Math.min(ctbW, width - x0);
                var h = Math.min(ctbH, height - y0);
                var yLo = Math.max(yFrom - y0, 0);
                var yHi = Math.min(yTo - y0, h);
                var offsetIndex = (cIdx * size + ctb) * 4;
                var sliceClass = pic.saoClass[cIdx * size + ctb];
                if (type == 1) {
                    if (protect) {
                        if (plane8 != null) {
                            band(plane8, src8, width, x0, y0 + yLo, w, yHi - yLo, sliceClass, pic.saoOffset,
                                    offsetIndex, pic, sx, sy, table);
                        } else {
                            band(plane, src, width, x0, y0 + yLo, w, yHi - yLo, sliceClass, pic.saoOffset,
                                    offsetIndex, bitDepth, pic, sx, sy, table);
                        }
                    } else if (plane8 != null) {
                        var offsets = pic.saoOffset;
                        pic.dsp.saoBand(plane8, src8, (y0 + yLo) * width + x0, width, w, yHi - yLo, sliceClass, offsets[offsetIndex],
                                offsets[offsetIndex + 1], offsets[offsetIndex + 2], offsets[offsetIndex + 3]);
                    } else {
                        var offsets = pic.saoOffset;
                        pic.dsp.saoBand(plane, src, (y0 + yLo) * width + x0, width, w, yHi - yLo, sliceClass,
                                bitDepth - 5,
                                offsets[offsetIndex], offsets[offsetIndex + 1], offsets[offsetIndex + 2],
                                offsets[offsetIndex + 3], (1 << bitDepth) - 1);
                    }
                } else {
                    usableNeighbors(pic, header.pps, ctb, rx, ry, header, usable);
                    var offsets = pic.saoOffset;
                    lut[0] = offsets[offsetIndex];
                    lut[1] = offsets[offsetIndex + 1];
                    lut[2] = 0;
                    lut[3] = offsets[offsetIndex + 2];
                    lut[4] = offsets[offsetIndex + 3];
                    if (plane8 != null) {
                        edge(plane8, src8, width, x0, y0, w, h, yLo, yHi, sliceClass, lut, pic, sx, sy, protect,
                                usable);
                    } else {
                        edge(plane, src, width, x0, y0, w, h, yLo, yHi, sliceClass, lut, bitDepth, pic, sx, sy,
                                protect, usable);
                    }
                }
            }
        }
    }

    /**
     * Returns whether the sample covering a luma location must keep its deblocked value: PCM
     * samples with the PCM loop filter disabled and transquant bypass samples.
     *
     * @param pic the coding information
     * @param x the luma x
     * @param y the luma y
     * @return {@code true} when the sample is not modified
     */
    private static boolean protectedSample(PictureState pic, int x, int y) {
        var flags = pic.flags[(y >> 2) * pic.width4 + (x >> 2)];
        if ((flags & PictureState.BYPASS) != 0) {
            return true;
        }
        return (flags & PictureState.PCM) != 0 && pic.sps.pcmEnabled && pic.sps.pcmLoopFilterDisabled;
    }

    /**
     * Applies the band offset of a coding tree block some of whose samples may have to keep their
     * deblocked value (8.7.3.2).
     *
     * @param plane the component plane, modified in place
     * @param src the deblocked samples
     * @param stride the plane width
     * @param x0 the block x
     * @param y0 the block y
     * @param w the block width
     * @param h the block height
     * @param position {@code sao_band_position}
     * @param offsets the offsets array
     * @param offsetIndex the index of the block's four offsets
     * @param bitDepth the component bit depth
     * @param pic the coding information
     * @param sx the horizontal chroma shift
     * @param sy the vertical chroma shift
     * @param table a scratch table of 32 entries
     */
    private static void band(short[] plane, short[] src, int stride, int x0, int y0, int w, int h, int position,
                             short[] offsets, int offsetIndex, int bitDepth, PictureState pic, int sx, int sy,
                             int[] table) {
        Arrays.fill(table, 0);
        for (var k = 0; k < 4; k++) {
            table[(k + position) & 31] = offsets[offsetIndex + k];
        }
        var shift = bitDepth - 5;
        var max = (1 << bitDepth) - 1;
        for (var y = 0; y < h; y++) {
            var row = (y0 + y) * stride + x0;
            for (var x = 0; x < w; x++) {
                if (protectedSample(pic, (x0 + x) << sx, (y0 + y) << sy)) {
                    continue;
                }
                var value = src[row + x] & 0xFFFF;
                plane[row + x] = (short) Math.clamp(value + table[value >> shift], 0, max);
            }
        }
    }

    /**
     * Applies the band offset of a coding tree block of an 8-bit plane some of whose samples may have to keep their
     * deblocked value (8.7.3.2).
     *
     * @param plane the component plane, modified in place
     * @param src the deblocked samples
     * @param stride the plane width
     * @param x0 the block x
     * @param y0 the block y
     * @param w the block width
     * @param h the block height
     * @param position {@code sao_band_position}
     * @param offsets the offsets array
     * @param offsetIndex the index of the block's four offsets
     * @param pic the coding information
     * @param sx the horizontal chroma shift
     * @param sy the vertical chroma shift
     * @param table a scratch table of 32 entries
     */
    private static void band(byte[] plane, byte[] src, int stride, int x0, int y0, int w, int h, int position,
                             short[] offsets, int offsetIndex, PictureState pic, int sx, int sy,
                             int[] table) {
        Arrays.fill(table, 0);
        for (var k = 0; k < 4; k++) {
            table[(k + position) & 31] = offsets[offsetIndex + k];
        }
        for (var y = 0; y < h; y++) {
            var row = (y0 + y) * stride + x0;
            for (var x = 0; x < w; x++) {
                if (protectedSample(pic, (x0 + x) << sx, (y0 + y) << sy)) {
                    continue;
                }
                var value = src[row + x] & 0xFF;
                plane[row + x] = (byte) Math.clamp(value + table[value >> 3], 0, 255);
            }
        }
    }

    /**
     * Decides which of the eight neighboring coding tree blocks of a block its edge offset may
     * read: those inside the picture that are not across a slice boundary that a slice's
     * {@code slice_loop_filter_across_slices_enabled_flag} closes, nor across a tile boundary
     * when {@code loop_filter_across_tiles_enabled_flag} is 0.
     *
     * @param pic the coding information
     * @param pps the picture parameter set
     * @param ctb the raster address of the block
     * @param rx the block column
     * @param ry the block row
     * @param header the slice segment header of the block
     * @param usable receives the decisions, indexed {@code (dy + 1) * 3 + dx + 1}
     */
    private static void usableNeighbors(PictureState pic, HevcPps pps, int ctb, int rx, int ry,
                                         HevcSliceHeader header, boolean[] usable) {
        var sps = pic.sps;
        for (var dy = -1; dy <= 1; dy++) {
            for (var dx = -1; dx <= 1; dx++) {
                var nx = rx + dx;
                var ny = ry + dy;
                var index = (dy + 1) * 3 + dx + 1;
                if (nx < 0 || ny < 0 || nx >= sps.widthInCtbs || ny >= sps.heightInCtbs) {
                    usable[index] = false;
                    continue;
                }
                var ctbN = ny * sps.widthInCtbs + nx;
                if (ctbN == ctb) {
                    usable[index] = true;
                    continue;
                }
                var headerN = pic.ctbHeader[ctbN];
                if (headerN == null) {
                    usable[index] = false;
                    continue;
                }
                var ok = true;
                if (pic.ctbSliceAddr[ctbN] != pic.ctbSliceAddr[ctb]) {
                    var before = pps.ctbAddrRsToTs[ctbN] < pps.ctbAddrRsToTs[ctb];
                    ok = before ? header.loopFilterAcrossSlices : headerN.loopFilterAcrossSlices;
                }
                usable[index] = ok && (pps.loopFilterAcrossTiles
                        || pps.tileId[pps.ctbAddrRsToTs[ctbN]] == pps.tileId[pps.ctbAddrRsToTs[ctb]]);
            }
        }
    }

    /**
     * Applies the edge offset of a coding tree block (8.7.3.2).
     *
     * @param plane the component plane, modified in place
     * @param src the deblocked samples
     * @param stride the plane width
     * @param x0 the block x
     * @param y0 the block y
     * @param w the block width
     * @param h the block height
     * @param yLo the first row of the block to filter
     * @param yHi the row of the block after the last to filter
     * @param eoClass {@code SaoEoClass}
     * @param lut the offset of each {@code 2 + sign(a) + sign(b)} value: the offsets of edge
     * indices 1, 2, 0, 3 and 4
     * @param bitDepth the component bit depth
     * @param pic the coding information
     * @param sx the horizontal chroma shift
     * @param sy the vertical chroma shift
     * @param protect whether some samples may have to keep their deblocked value
     * @param usable the neighboring blocks the filter may read, as computed by
     * {@link #usableNeighbors}
     */
    private static void edge(short[] plane, short[] src, int stride, int x0, int y0, int w, int h, int yLo, int yHi,
                             int eoClass,
                             int[] lut, int bitDepth, PictureState pic, int sx, int sy, boolean protect,
                             boolean[] usable) {
        var hPos0 = OFFSETS[eoClass * 4];
        var vPos0 = OFFSETS[eoClass * 4 + 1];
        var hPos1 = OFFSETS[eoClass * 4 + 2];
        var vPos1 = OFFSETS[eoClass * 4 + 3];
        var deltaA = vPos0 * stride + hPos0;
        var deltaB = vPos1 * stride + hPos1;
        var max = (1 << bitDepth) - 1;
        var right = x0 + w;
        var bottom = y0 + h;
        var first = Math.min(1, w);
        var last = Math.max(first, w - 1);
        var y = yLo;
        while (y < yHi) {
            if (!fastRow(y0, y, bottom, vPos0, vPos1, protect, usable)) {
                var ys = y0 + y;
                var rowA = rowIndex(ys + vPos0, y0, bottom);
                var rowB = rowIndex(ys + vPos1, y0, bottom);
                for (var x = 0; x < w; x++) {
                    edgeSample(plane, src, ys * stride, x0 + x, x0, right, ys, hPos0, hPos1, deltaA, deltaB, rowA, rowB,
                            lut, max, pic, sx, sy, protect, usable);
                }
                y++;
                continue;
            }
            var end = y + 1;
            while (end < yHi && fastRow(y0, end, bottom, vPos0, vPos1, protect, usable)) {
                end++;
            }
            for (var k = y; k < end; k++) {
                var ys = y0 + k;
                var rowA = rowIndex(ys + vPos0, y0, bottom);
                var rowB = rowIndex(ys + vPos1, y0, bottom);
                for (var x = 0; x < first; x++) {
                    edgeSample(plane, src, ys * stride, x0 + x, x0, right, ys, hPos0, hPos1, deltaA, deltaB, rowA, rowB,
                            lut, max, pic, sx, sy, false, usable);
                }
                for (var x = last; x < w; x++) {
                    edgeSample(plane, src, ys * stride, x0 + x, x0, right, ys, hPos0, hPos1, deltaA, deltaB, rowA, rowB,
                            lut, max, pic, sx, sy, false, usable);
                }
            }
            if (last > first) {
                pic.dsp.saoEdge(plane, src, (y0 + y) * stride + x0 + first, stride, last - first, end - y, deltaA,
                        deltaB, lut, max);
            }
            y = end;
        }
    }

    /**
     * Applies the edge offset of a coding tree block of an 8-bit plane (8.7.3.2).
     *
     * @param plane the component plane, modified in place
     * @param src the deblocked samples
     * @param stride the plane width
     * @param x0 the block x
     * @param y0 the block y
     * @param w the block width
     * @param h the block height
     * @param yLo the first row of the block to filter
     * @param yHi the row of the block after the last to filter
     * @param eoClass {@code SaoEoClass}
     * @param lut the offset of each {@code 2 + sign(a) + sign(b)} value: the offsets of edge
     * indices 1, 2, 0, 3 and 4
     * @param pic the coding information
     * @param sx the horizontal chroma shift
     * @param sy the vertical chroma shift
     * @param protect whether some samples may have to keep their deblocked value
     * @param usable the neighboring blocks the filter may read, as computed by
     * {@link #usableNeighbors}
     */
    private static void edge(byte[] plane, byte[] src, int stride, int x0, int y0, int w, int h, int yLo, int yHi,
                             int eoClass,
                             int[] lut, PictureState pic, int sx, int sy, boolean protect,
                             boolean[] usable) {
        var hPos0 = OFFSETS[eoClass * 4];
        var vPos0 = OFFSETS[eoClass * 4 + 1];
        var hPos1 = OFFSETS[eoClass * 4 + 2];
        var vPos1 = OFFSETS[eoClass * 4 + 3];
        var deltaA = vPos0 * stride + hPos0;
        var deltaB = vPos1 * stride + hPos1;
        var right = x0 + w;
        var bottom = y0 + h;
        var first = Math.min(1, w);
        var last = Math.max(first, w - 1);
        var y = yLo;
        while (y < yHi) {
            if (!fastRow(y0, y, bottom, vPos0, vPos1, protect, usable)) {
                var ys = y0 + y;
                var rowA = rowIndex(ys + vPos0, y0, bottom);
                var rowB = rowIndex(ys + vPos1, y0, bottom);
                for (var x = 0; x < w; x++) {
                    edgeSample(plane, src, ys * stride, x0 + x, x0, right, ys, hPos0, hPos1, deltaA, deltaB, rowA, rowB,
                            lut, pic, sx, sy, protect, usable);
                }
                y++;
                continue;
            }
            var end = y + 1;
            while (end < yHi && fastRow(y0, end, bottom, vPos0, vPos1, protect, usable)) {
                end++;
            }
            for (var k = y; k < end; k++) {
                var ys = y0 + k;
                var rowA = rowIndex(ys + vPos0, y0, bottom);
                var rowB = rowIndex(ys + vPos1, y0, bottom);
                for (var x = 0; x < first; x++) {
                    edgeSample(plane, src, ys * stride, x0 + x, x0, right, ys, hPos0, hPos1, deltaA, deltaB, rowA, rowB,
                            lut, pic, sx, sy, false, usable);
                }
                for (var x = last; x < w; x++) {
                    edgeSample(plane, src, ys * stride, x0 + x, x0, right, ys, hPos0, hPos1, deltaA, deltaB, rowA, rowB,
                            lut, pic, sx, sy, false, usable);
                }
            }
            if (last > first) {
                pic.dsp.saoEdge(plane, src, (y0 + y) * stride + x0 + first, stride, last - first, end - y, deltaA,
                        deltaB, lut);
            }
            y = end;
        }
    }

    /**
     * Returns three times the vertical index of the neighboring block holding a sample row: 0 for
     * the block above, 3 for the block itself and 6 for the block below.
     *
     * @param y the sample row
     * @param top the first row of the block
     * @param bottom the row after the block
     * @return the index into the rows of {@link #usableNeighbors}
     */
    private static int rowIndex(int y, int top, int bottom) {
        return (y < top ? 0 : y >= bottom ? 2 : 1) * 3;
    }

    /**
     * Returns whether every sample of a row of a block, except its first and last, may use its two
     * edge offset neighbors without a check.
     *
     * @param y0 the first row of the block
     * @param y the row within the block
     * @param bottom the row after the block
     * @param vPos0 the vertical offset of the first neighbor
     * @param vPos1 the vertical offset of the second neighbor
     * @param protect whether some samples may have to keep their deblocked value
     * @param usable the neighboring blocks the filter may read
     * @return {@code true} when the row's inner samples need no check
     */
    private static boolean fastRow(int y0, int y, int bottom, int vPos0, int vPos1, boolean protect,
                                   boolean[] usable) {
        var ys = y0 + y;
        return !protect && usable[rowIndex(ys + vPos0, y0, bottom) + 1] && usable[rowIndex(ys + vPos1, y0, bottom) + 1];
    }

    /**
     * Applies the edge offset to one sample, checking its neighbors and protection.
     *
     * @param plane the component plane, modified in place
     * @param src the deblocked samples
     * @param row the index of the sample's row
     * @param xs the sample x
     * @param x0 the block x
     * @param right the x after the block
     * @param ys the sample y
     * @param hPos0 the horizontal offset of the first neighbor
     * @param hPos1 the horizontal offset of the second neighbor
     * @param deltaA the index offset of the first neighbor
     * @param deltaB the index offset of the second neighbor
     * @param rowA three times the vertical neighbor block index of the first neighbor
     * @param rowB three times the vertical neighbor block index of the second neighbor
     * @param lut the offsets by {@code 2 + sign(a) + sign(b)}
     * @param max the largest sample value
     * @param pic the coding information
     * @param sx the horizontal chroma shift
     * @param sy the vertical chroma shift
     * @param protect whether some samples may have to keep their deblocked value
     * @param usable the neighboring blocks the filter may read
     */
    private static void edgeSample(short[] plane, short[] src, int row, int xs, int x0, int right, int ys, int hPos0,
                                   int hPos1, int deltaA, int deltaB, int rowA, int rowB, int[] lut, int max,
                                   PictureState pic, int sx, int sy, boolean protect, boolean[] usable) {
        var ax = xs + hPos0;
        var bx = xs + hPos1;
        var colA = ax < x0 ? 0 : ax >= right ? 2 : 1;
        var colB = bx < x0 ? 0 : bx >= right ? 2 : 1;
        if (!usable[rowA + colA] || !usable[rowB + colB]) {
            return;
        }
        if (protect && protectedSample(pic, xs << sx, ys << sy)) {
            return;
        }
        var p = row + xs;
        var value = src[p] & 0xFFFF;
        var edgeIdx = 2 + Integer.signum(value - (src[p + deltaA] & 0xFFFF))
                + Integer.signum(value - (src[p + deltaB] & 0xFFFF));
        plane[p] = (short) Math.clamp(value + lut[edgeIdx], 0, max);
    }

    /**
     * Applies the edge offset to one sample of an 8-bit plane, checking its neighbors and protection.
     *
     * @param plane the component plane, modified in place
     * @param src the deblocked samples
     * @param row the index of the sample's row
     * @param xs the sample x
     * @param x0 the block x
     * @param right the x after the block
     * @param ys the sample y
     * @param hPos0 the horizontal offset of the first neighbor
     * @param hPos1 the horizontal offset of the second neighbor
     * @param deltaA the index offset of the first neighbor
     * @param deltaB the index offset of the second neighbor
     * @param rowA three times the vertical neighbor block index of the first neighbor
     * @param rowB three times the vertical neighbor block index of the second neighbor
     * @param lut the offsets by {@code 2 + sign(a) + sign(b)}
     * @param pic the coding information
     * @param sx the horizontal chroma shift
     * @param sy the vertical chroma shift
     * @param protect whether some samples may have to keep their deblocked value
     * @param usable the neighboring blocks the filter may read
     */
    private static void edgeSample(byte[] plane, byte[] src, int row, int xs, int x0, int right, int ys, int hPos0,
                                   int hPos1, int deltaA, int deltaB, int rowA, int rowB, int[] lut,
                                   PictureState pic, int sx, int sy, boolean protect, boolean[] usable) {
        var ax = xs + hPos0;
        var bx = xs + hPos1;
        var colA = ax < x0 ? 0 : ax >= right ? 2 : 1;
        var colB = bx < x0 ? 0 : bx >= right ? 2 : 1;
        if (!usable[rowA + colA] || !usable[rowB + colB]) {
            return;
        }
        if (protect && protectedSample(pic, xs << sx, ys << sy)) {
            return;
        }
        var p = row + xs;
        var value = src[p] & 0xFF;
        var edgeIdx = 2 + Integer.signum(value - (src[p + deltaA] & 0xFF))
                + Integer.signum(value - (src[p + deltaB] & 0xFF));
        plane[p] = (byte) Math.clamp(value + lut[edgeIdx], 0, 255);
    }
}
