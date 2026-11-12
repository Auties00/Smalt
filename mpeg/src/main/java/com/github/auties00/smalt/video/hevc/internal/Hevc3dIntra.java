/* Ports the depth intra prediction, depth look-up table mapping and segment-wise DC coding of HTM-16.3
   (source/Lib/TLibCommon/TComPrediction.cpp, TComWedgelet.cpp, TComRom.cpp, TComSlice.cpp,
   source/Lib/TLibDecoder/TDecCu.cpp). Copyright (c) 2010-2016, ITU/ISO/IEC; BSD 3-Clause License.
   Modified for Smalt. */
package com.github.auties00.smalt.video.hevc.internal;

/**
 * Computes the depth intra prediction of 3D-HEVC (ITU-T H.265 I.8.4.4.2.2 to I.8.4.4.2.5 and
 * I.8.4.4.3): the wedgelet and contour partition patterns of the intra modes {@code INTRA_WEDGE} and
 * {@code INTRA_CONTOUR} with the DC values of their two segments, the single sample mode
 * {@code INTRA_SINGLE} of the coding units coded with {@code skip_intra_flag}, the depth look-up
 * table of a layer (I.8.3.3) and the DC offset of a coding unit coded with {@code dc_only_flag}.
 *
 * <p>The neighboring samples are those {@link SliceDecoder} gathers for intra prediction:
 * {@code p[ -1 ][ y ]} at index {@code 2 * nTbS - 1 - y} and {@code p[ x ][ -1 ]} at index
 * {@code 2 * nTbS + 1 + x}, after the reference sample substitution. Instances are reused across
 * slices and are not thread-safe.
 */
final class Hevc3dIntra {
    /**
     * The wedgelet partition pattern tables (I.6.6), built once.
     */
    private static final class Wedges {
        /**
         * {@code WedgePatternTable}, indexed {@code [log2BlkSize - 2][pattern]}, each pattern
         * {@code nTbS * nTbS} values of 0 or 1 in raster order.
         */
        static final byte[][][] TABLE = build();
    }

    /**
     * {@code DltIdxToVal} of the current layer, or {@code null} when {@code DltFlag} is 0.
     */
    private int[] idxToVal;

    /**
     * {@code DltValToIdx} of the current layer, or {@code null} when {@code DltFlag} is 0.
     */
    private int[] valToIdx;

    /**
     * The depth look-up table the mapping was derived from, to derive it once per table.
     */
    private int[] dltSource;

    /**
     * The bit depth the mapping was derived for.
     */
    private int dltBitDepth;

    /**
     * The largest sample value, {@code ( 1 << BitDepthY ) - 1}.
     */
    private int maxValue = 255;

    /**
     * The predicted value of the segment with pattern value 0 of the last block predicted, before clipping.
     */
    private int raw0;

    /**
     * The predicted value of the segment with pattern value 1, before clipping.
     */
    private int raw1;

    /**
     * The partition pattern of the current block, {@code nTbS * nTbS} values in raster order.
     */
    private final byte[] pattern = new byte[32 * 32];

    /**
     * Constructs a predictor.
     */
    Hevc3dIntra() {
    }

    /**
     * Returns {@code NumWedgePattern[ log2BlkSize ]}.
     *
     * @param log2Size the block size as a power of two, 2 to 5
     * @return the number of wedgelet patterns
     */
    static int wedgeCount(int log2Size) {
        return Wedges.TABLE[log2Size - 2].length;
    }

    /**
     * Returns the number of bits of {@code wedge_full_tab_idx} for a block size: the fixed-length
     * code of the values below {@link #wedgeCount}.
     *
     * @param log2Size the block size as a power of two, 2 to 5
     * @return the bit count
     */
    static int wedgeIndexBits(int log2Size) {
        return 32 - Integer.numberOfLeadingZeros(wedgeCount(log2Size) - 1);
    }

    /**
     * Returns a wedgelet partition pattern.
     *
     * @param log2Size the block size as a power of two, 2 to 5
     * @param index {@code wedge_full_tab_idx}, below {@link #wedgeCount}
     * @return the pattern, {@code nTbS * nTbS} values of 0 or 1 in raster order; not to be modified
     */
    static byte[] wedge(int log2Size, int index) {
        return Wedges.TABLE[log2Size - 2][index];
    }

    /**
     * Derives the wedgelet partition pattern tables (I.6.6 to I.6.6.2).
     *
     * @return the tables, indexed {@code [log2BlkSize - 2][pattern]}
     */
    private static byte[][][] build() {
        var tables = new byte[4][][];
        for (var log2 = 2; log2 <= 4; log2++) {
            var size = 1 << log2;
            var resShift = log2 == 4 ? 0 : 1;
            var wBlkSize = 1 << (log2 + resShift);
            var list = new java.util.ArrayList<byte[]>();
            var posStart = 0;
            for (var ori = 0; ori < 6; ori++) {
                var posEnd = list.size();
                if (ori == 0 || ori == 4) {
                    var scaleS = log2 > 3 ? 2 : 1;
                    var scaleE = ori < 4 && log2 > 3 ? 2 : 1;
                    for (var m = 0; m < wBlkSize / scaleS; m++) {
                        for (var n = 0; n < wBlkSize / scaleE; n++) {
                            var xS = m * scaleS;
                            var xE = ori == 0 ? 0 : n * scaleE;
                            var yE = ori == 0 ? n * scaleE : wBlkSize - 1;
                            insert(list, generate(size, resShift, xS, 0, xE, yE));
                        }
                    }
                } else {
                    for (var pos = posStart; pos < posEnd; pos++) {
                        var source = list.get(pos);
                        var rotated = new byte[size * size];
                        for (var y = 0; y < size; y++) {
                            for (var x = 0; x < size; x++) {
                                rotated[y * size + x] = (byte) (1 - source[(size - 1 - x) * size + y]);
                            }
                        }
                        insert(list, rotated);
                    }
                }
                posStart = posEnd;
            }
            tables[log2 - 2] = list.toArray(new byte[0][]);
        }
        var base = tables[2];
        var scaled = new byte[base.length][];
        for (var k = 0; k < base.length; k++) {
            var target = new byte[32 * 32];
            for (var y = 0; y < 32; y++) {
                for (var x = 0; x < 32; x++) {
                    target[y * 32 + x] = base[k][(y >> 1) * 16 + (x >> 1)];
                }
            }
            scaled[k] = target;
        }
        tables[3] = scaled;
        return tables;
    }

    /**
     * Generates a wedgelet partition pattern (I.6.6.1): the samples of the line from the start
     * to the end location, and every sample left of the line in the rows above its end, are 1.
     *
     * @param size the pattern size
     * @param resShift the precision of the locations relative to the pattern size
     * @param xS the x of the start location
     * @param yS the y of the start location
     * @param xE the x of the end location
     * @param yE the y of the end location
     * @return the pattern in raster order
     */
    private static byte[] generate(int size, int resShift, int xS, int yS, int xE, int yE) {
        var result = new byte[size * size];
        var x0 = xS;
        var y0 = yS;
        var x1 = xE;
        var y1 = yE;
        var steep = Math.abs(yE - yS) > Math.abs(xE - xS);
        if (steep) {
            var t = x0;
            x0 = y0;
            y0 = t;
            t = x1;
            x1 = y1;
            y1 = t;
        }
        if (x0 > x1) {
            var t = x0;
            x0 = x1;
            x1 = t;
            t = y0;
            y0 = y1;
            y1 = t;
        }
        var sumErr = 0;
        var posY = y0;
        for (var posX = x0; posX <= x1; posX++) {
            if (steep) {
                result[(posX >> resShift) * size + (posY >> resShift)] = 1;
            } else {
                result[(posY >> resShift) * size + (posX >> resShift)] = 1;
            }
            sumErr += Math.abs(y1 - y0) << 1;
            if (sumErr >= x1 - x0) {
                posY += y0 < y1 ? 1 : -1;
                sumErr -= (x1 - x0) << 1;
            }
        }
        for (var y = 0; y <= yE >> resShift; y++) {
            for (var x = 0; x < size && result[y * size + x] == 0; x++) {
                result[y * size + x] = 1;
            }
        }
        return result;
    }

    /**
     * Appends a pattern to a table unless it is plain or equal to, or the inverse of, a pattern
     * already listed (I.6.6.2).
     *
     * @param list the table
     * @param candidate the pattern
     */
    private static void insert(java.util.List<byte[]> list, byte[] candidate) {
        var plain = true;
        for (var value : candidate) {
            if (value != candidate[0]) {
                plain = false;
                break;
            }
        }
        if (plain) {
            return;
        }
        for (var existing : list) {
            var identical = true;
            var inverse = true;
            for (var k = 0; k < candidate.length && (identical || inverse); k++) {
                if (candidate[k] == existing[k]) {
                    inverse = false;
                } else {
                    identical = false;
                }
            }
            if (identical || inverse) {
                return;
            }
        }
        list.add(candidate);
    }

    /**
     * Selects the depth look-up table of the layer being decoded and derives
     * {@code DltIdxToVal} and {@code DltValToIdx} from it (I.8.3.3).
     *
     * @param dltVal {@code DltVal} of the layer, {@code NumValDlt} entries in increasing order, as
     * the picture parameter set derives them, or {@code null} when {@code DltFlag} is 0
     * @param bitDepth {@code BitDepthY}
     */
    void setLookUpTable(int[] dltVal, int bitDepth) {
        maxValue = (1 << bitDepth) - 1;
        if (dltVal == null || dltVal.length == 0) {
            idxToVal = null;
            valToIdx = null;
            dltSource = null;
            return;
        }
        if (dltVal == dltSource && bitDepth == dltBitDepth) {
            return;
        }
        dltSource = dltVal;
        dltBitDepth = bitDepth;
        var range = 1 << bitDepth;
        var count = Math.min(dltVal.length, range);
        idxToVal = new int[range];
        System.arraycopy(dltVal, 0, idxToVal, 0, count);
        valToIdx = new int[range];
        // the values of a table are increasing, so the first index past a depth value only moves forward
        var next = 1;
        for (var d = 0; d < range; d++) {
            while (next < count && idxToVal[next] <= d) {
                next++;
            }
            var found = next < count;
            var lower = found ? next - 1 : 0;
            var upper = found ? next : count - 1;
            valToIdx[d] = Math.abs(d - idxToVal[lower]) < Math.abs(d - idxToVal[upper]) ? lower : upper;
        }
    }

    /**
     * Returns a predicted depth value offset by a DC offset, through the depth look-up table when
     * the layer has one (equations I-93, I-94 and I-97).
     *
     * @param predicted the predicted value
     * @param offset {@code DcOffset}
     * @return the reconstructed value; without a look-up table, {@code predicted + offset}
     * unclipped
     */
    int offsetValue(int predicted, int offset) {
        if (idxToVal == null) {
            return predicted + offset;
        }
        return idxToVal[Math.clamp(valToIdx[Math.clamp(predicted, 0, maxValue)] + offset, 0, maxValue)];
    }

    /**
     * Derives the contour partition pattern of a block from the reconstructed luma samples of the
     * texture picture of its view (equations I-79 and I-80) into the pattern buffer.
     *
     * @param texture the texture picture, of the size of the depth picture
     * @param x the luma x of the block
     * @param y the luma y of the block
     * @param size the block size
     * @return the pattern, {@code size * size} values in raster order, valid until the next call
     */
    byte[] contour(HevcFrame texture, int x, int y, int size) {
        texture.awaitRows(y + size);
        var stride = texture.width;
        var last = size - 1;
        var threshold = (sample(texture, y * stride + x) + sample(texture, (y + last) * stride + x)
                + sample(texture, y * stride + x + last) + sample(texture, (y + last) * stride + x + last)) >> 2;
        for (var j = 0; j < size; j++) {
            var row = (y + j) * stride + x;
            for (var i = 0; i < size; i++) {
                pattern[j * size + i] = (byte) (sample(texture, row + i) > threshold ? 1 : 0);
            }
        }
        return pattern;
    }

    /**
     * Returns a luma sample of a picture.
     *
     * @param frame the picture
     * @param index the sample index
     * @return the value
     */
    private static int sample(HevcFrame frame, int index) {
        return frame.y8 != null ? frame.y8[index] & 0xFF : frame.y[index] & 0xFFFF;
    }

    /**
     * Predicts a block partitioned into two segments (I.8.4.4.2.5): each segment takes the DC
     * value derived from the neighboring samples, offset by its {@code DcOffset} through the
     * depth look-up table, and the block is written to the luma plane of the picture.
     *
     * @param boundary the neighboring samples, after substitution
     * @param size the block size {@code nTbS}
     * @param partition the partition pattern, {@code size * size} values of 0 or 1 in raster order
     * @param offset0 {@code DcOffset} of the segment with pattern value 0
     * @param offset1 {@code DcOffset} of the segment with pattern value 1
     * @param frame the picture being decoded
     * @param x the luma x of the block
     * @param y the luma y of the block
     * @return whether a predicted value lies outside the sample range, which equation I-93 leaves
     * unclipped until the residual is added: the samples hold the clipped values, {@link #raw0()}
     * and {@link #raw1()} the predicted ones
     */
    boolean predictSegments(int[] boundary, int size, byte[] partition, int offset0, int offset1, HevcFrame frame,
                         int x, int y) {
        var corner = 2 * size;
        var last = size - 1;
        var vertEdge = partition[0] != partition[last];
        var horEdge = partition[0] != partition[last * size];
        int dcBr;
        int dcLt;
        if (vertEdge == horEdge) {
            if (horEdge) {
                dcBr = (left(boundary, corner, last) + above(boundary, corner, last)) >> 1;
            } else {
                var vertAbsDiff = Math.abs(left(boundary, corner, 0) - left(boundary, corner, 2 * size - 1));
                var horAbsDiff = Math.abs(above(boundary, corner, 0) - above(boundary, corner, 2 * size - 1));
                dcBr = horAbsDiff > vertAbsDiff ? above(boundary, corner, 2 * size - 1)
                        : left(boundary, corner, 2 * size - 1);
            }
            dcLt = (left(boundary, corner, 0) + above(boundary, corner, 0)) >> 1;
        } else {
            dcBr = horEdge ? left(boundary, corner, last) : above(boundary, corner, last);
            dcLt = horEdge ? above(boundary, corner, last >> 1) : left(boundary, corner, last >> 1);
        }
        var first = partition[0];
        raw0 = offsetValue(first == 0 ? dcLt : dcBr, offset0);
        raw1 = offsetValue(first == 1 ? dcLt : dcBr, offset1);
        var value0 = Math.clamp(raw0, 0, maxValue);
        var value1 = Math.clamp(raw1, 0, maxValue);
        var stride = frame.width;
        for (var j = 0; j < size; j++) {
            var row = (y + j) * stride + x;
            for (var i = 0; i < size; i++) {
                write(frame, row + i, partition[j * size + i] == 0 ? value0 : value1);
            }
        }
        return value0 != raw0 || value1 != raw1;
    }

    /**
     * {@return the predicted value of the segment with pattern value 0 of the last block
     * {@link #predictSegments} predicted, before it was clipped to the sample range}
     */
    int raw0() {
        return raw0;
    }

    /**
     * {@return the predicted value of the segment with pattern value 1 of the last block
     * {@link #predictSegments} predicted, before it was clipped to the sample range}
     */
    int raw1() {
        return raw1;
    }

    /**
     * Fills a block of the luma plane with one value.
     *
     * @param frame the picture being decoded
     * @param x the luma x of the block
     * @param y the luma y of the block
     * @param size the block size
     * @param value the sample value
     */
    static void fill(HevcFrame frame, int x, int y, int size, int value) {
        var stride = frame.width;
        for (var j = 0; j < size; j++) {
            var row = (y + j) * stride + x;
            if (frame.y8 != null) {
                java.util.Arrays.fill(frame.y8, row, row + size, (byte) value);
            } else {
                java.util.Arrays.fill(frame.y, row, row + size, (short) value);
            }
        }
    }

    /**
     * Predicts a block with the vertical or horizontal intra mode from unfiltered neighboring
     * samples, which for blocks of 32 samples and more is a copy of the row above or of the column
     * to the left (8.4.4.2.6).
     *
     * @param boundary the neighboring samples, after substitution
     * @param size the block size
     * @param vertical whether the mode is {@code INTRA_ANGULAR26} rather than {@code INTRA_ANGULAR10}
     * @param frame the picture being decoded
     * @param x the luma x of the block
     * @param y the luma y of the block
     */
    static void predictCopy(int[] boundary, int size, boolean vertical, HevcFrame frame, int x, int y) {
        var corner = 2 * size;
        var stride = frame.width;
        for (var j = 0; j < size; j++) {
            var row = (y + j) * stride + x;
            for (var i = 0; i < size; i++) {
                write(frame, row + i, vertical ? above(boundary, corner, i) : left(boundary, corner, j));
            }
        }
    }

    /**
     * Adds the DC offset of a coding unit coded with {@code dc_only_flag} and without a partition
     * pattern to its predicted luma samples (I.8.4.4.3): through the depth look-up table, the
     * offset applies to the mean of the four corner samples.
     *
     * @param frame the picture being decoded, holding the predicted samples
     * @param x the luma x of the coding block
     * @param y the luma y of the coding block
     * @param size the coding block size
     * @param offset {@code DcOffset[ xTb ][ yTb ][ 0 ]}
     */
    void addDcOffset(HevcFrame frame, int x, int y, int size, int offset) {
        var stride = frame.width;
        var dc = offset;
        if (idxToVal != null) {
            var last = size - 1;
            var predicted = (read(frame, y * stride + x) + read(frame, (y + last) * stride + x)
                    + read(frame, y * stride + x + last) + read(frame, (y + last) * stride + x + last) + 2) >> 2;
            dc = offsetValue(predicted, offset) - predicted;
        }
        addConstant(frame, x, y, size, dc, maxValue);
    }

    /**
     * Adds a constant to a block of the luma plane, clipping to the sample range.
     *
     * @param frame the picture being decoded
     * @param x the luma x of the block
     * @param y the luma y of the block
     * @param size the block size
     * @param value the constant
     * @param maxValue the largest sample value
     */
    static void addConstant(HevcFrame frame, int x, int y, int size, int value, int maxValue) {
        if (value == 0) {
            return;
        }
        var stride = frame.width;
        for (var j = 0; j < size; j++) {
            var row = (y + j) * stride + x;
            for (var i = 0; i < size; i++) {
                write(frame, row + i, Math.clamp(read(frame, row + i) + value, 0, maxValue));
            }
        }
    }

    /**
     * Returns {@code p[ -1 ][ y ]} of the neighboring samples.
     *
     * @param boundary the neighboring samples
     * @param corner the index of {@code p[ -1 ][ -1 ]}
     * @param y the row
     * @return the sample
     */
    private static int left(int[] boundary, int corner, int y) {
        return boundary[corner - 1 - y];
    }

    /**
     * Returns {@code p[ x ][ -1 ]} of the neighboring samples.
     *
     * @param boundary the neighboring samples
     * @param corner the index of {@code p[ -1 ][ -1 ]}
     * @param x the column
     * @return the sample
     */
    private static int above(int[] boundary, int corner, int x) {
        return boundary[corner + 1 + x];
    }

    /**
     * Returns a luma sample of the picture being decoded.
     *
     * @param frame the picture
     * @param index the sample index
     * @return the value
     */
    private static int read(HevcFrame frame, int index) {
        return frame.y8 != null ? frame.y8[index] & 0xFF : frame.y[index] & 0xFFFF;
    }

    /**
     * Stores a luma sample of the picture being decoded.
     *
     * @param frame the picture
     * @param index the sample index
     * @param value the sample value to store
     */
    private static void write(HevcFrame frame, int index, int value) {
        if (frame.y8 != null) {
            frame.y8[index] = (byte) value;
        } else {
            frame.y[index] = (short) value;
        }
    }
}
