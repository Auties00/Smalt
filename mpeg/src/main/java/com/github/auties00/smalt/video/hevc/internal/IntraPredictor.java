package com.github.auties00.smalt.video.hevc.internal;

/**
 * Computes intra sample predictions (ITU-T H.265 8.4.4.2): the filtering of the neighboring
 * samples and the planar, DC and 33 angular modes with their boundary filters.
 *
 * <p>The neighboring samples arrive as one boundary array of {@code 4 * nTbS + 1} entries running
 * from the bottom of the left column up to the corner and then along the top row to the right:
 * index {@code 2 * nTbS - 1 - y} holds {@code p[-1][y]}, index {@code 2 * nTbS} the corner
 * {@code p[-1][-1]}, and index {@code 2 * nTbS + 1 + x} holds {@code p[x][-1]}. In that order the
 * substitution process of 8.4.4.2.2 is a single forward pass and the [1 2 1] filter of 8.4.4.2.3 a
 * single convolution.
 */
public final class IntraPredictor {
    /**
     * {@code intraPredAngle} of Table 8-5, indexed by mode.
     */
    private static final int[] ANGLE = {
            0, 0, 32, 26, 21, 17, 13, 9, 5, 2, 0, -2, -5, -9, -13, -17, -21, -26,
            -32, -26, -21, -17, -13, -9, -5, -2, 0, 2, 5, 9, 13, 17, 21, 26, 32
    };

    /**
     * {@code invAngle} of Table 8-6, indexed by mode (0 where undefined).
     */
    private static final int[] INV_ANGLE = {
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, -4096, -1638, -910, -630, -482, -390, -315,
            -256, -315, -390, -482, -630, -910, -1638, -4096, 0, 0, 0, 0, 0, 0, 0, 0, 0
    };

    /**
     * The filtered copy of the boundary.
     */
    private final int[] filtered = new int[4 * 64 + 1];

    /**
     * The main reference array of the angular modes, with {@link #REF_ORIGIN} entries before index
     * 0 for the projected samples.
     */
    private final int[] refMain = new int[3 * 64 + 2];

    /**
     * The index of {@code ref[0]} in {@link #refMain}.
     */
    private static final int REF_ORIGIN = 64;

    /**
     * Constructs a predictor with its own scratch memory; instances are not thread-safe.
     */
    public IntraPredictor() {
    }

    /**
     * Fills the unavailable entries of a boundary array (8.4.4.2.2).
     *
     * @param boundary  the boundary array, {@code 4 * nTbS + 1} entries
     * @param available whether each entry is available, in the same order
     * @param count     the number of entries, {@code 4 * nTbS + 1}
     * @param bitDepth  the bit depth of the component
     */
    public static void substitute(int[] boundary, boolean[] available, int count, int bitDepth) {
        var first = -1;
        for (var i = 0; i < count; i++) {
            if (available[i]) {
                first = i;
                break;
            }
        }
        if (first < 0) {
            var mid = 1 << (bitDepth - 1);
            for (var i = 0; i < count; i++) {
                boundary[i] = mid;
            }
            return;
        }
        if (first > 0) {
            boundary[0] = boundary[first];
        }
        for (var i = 1; i < count; i++) {
            if (!available[i]) {
                boundary[i] = boundary[i - 1];
            }
        }
    }

    /**
     * Predicts one block.
     *
     * @param boundary        the substituted boundary array of {@code 4 * nTbS + 1} entries; it is
     *                        not modified
     * @param log2Size        the block size as a power of two, 2 to 5
     * @param mode            the intra prediction mode, 0 to 34
     * @param luma            whether the block is a luma block, which enables strong intra
     *                        smoothing and, below 32x32 and unless
     *                        {@code intra_boundary_filtering_disabled_flag} is set, the DC
     *                        boundary filter
     * @param filter          whether the neighboring sample filter of 8.4.4.2.3 may apply: for luma
     *                        blocks and the chroma blocks of 4:4:4 video, unless
     *                        {@code intra_smoothing_disabled_flag} is set
     * @param strongSmoothing {@code strong_intra_smoothing_enabled_flag}
     * @param edgeFilters     whether the vertical and horizontal modes filter their first column or
     *                        row below 32x32: for luma blocks unless
     *                        {@code disableIntraBoundaryFilter} is set
     * @param dcFilter        whether a luma block may filter the DC prediction: unless
     *                        {@code intra_boundary_filtering_disabled_flag} is set
     * @param bitDepth        the bit depth of the component
     * @param out             receives the prediction
     * @param offset          the index of the block's top-left sample in {@code out}
     * @param stride          the row stride of {@code out}
     */
    public void predict(int[] boundary, int log2Size, int mode, boolean luma, boolean filter,
                        boolean strongSmoothing, boolean edgeFilters, boolean dcFilter, int bitDepth, short[] out,
                        int offset, int stride) {
        var size = 1 << log2Size;
        var p = boundary;
        if (filter && mode != 1 && size != 4) {
            var minDist = Math.min(Math.abs(mode - 26), Math.abs(mode - 10));
            var threshold = size == 8 ? 7 : size == 16 ? 1 : 0;
            if (minDist > threshold) {
                p = filter(boundary, log2Size, strongSmoothing && luma && size == 32 ? bitDepth : 0);
            }
        }
        if (mode == 0) {
            planar(p, log2Size, out, offset, stride);
        } else if (mode == 1) {
            dc(p, log2Size, luma && dcFilter && size < 32, out, offset, stride);
        } else {
            angular(p, log2Size, mode, edgeFilters && size < 32, bitDepth, out, offset, stride);
        }
    }

    /**
     * Filters the boundary with the [1 2 1] filter or, for strong intra smoothing, the bilinear
     * interpolation of equations 8-36 to 8-40.
     *
     * @param boundary       the boundary array
     * @param log2Size       the block size as a power of two
     * @param strongBitDepth the luma bit depth when strong smoothing is enabled for this block,
     *                       otherwise 0
     * @return the filtered boundary
     */
    private int[] filter(int[] boundary, int log2Size, int strongBitDepth) {
        var size = 1 << log2Size;
        var last = 4 * size;
        var corner = 2 * size;
        var pf = filtered;
        if (strongBitDepth > 0) {
            var threshold = 1 << (strongBitDepth - 5);
            var bottom = boundary[0];
            var top = boundary[last];
            var c = boundary[corner];
            if (Math.abs(c + top - 2 * boundary[corner + size]) < threshold
                    && Math.abs(c + bottom - 2 * boundary[corner - size]) < threshold) {
                pf[corner] = c;
                pf[0] = bottom;
                pf[last] = top;
                for (var i = 0; i < 63; i++) {
                    pf[corner - 1 - i] = ((63 - i) * c + (i + 1) * bottom + 32) >> 6;
                    pf[corner + 1 + i] = ((63 - i) * c + (i + 1) * top + 32) >> 6;
                }
                return pf;
            }
        }
        pf[0] = boundary[0];
        pf[last] = boundary[last];
        for (var i = 1; i < last; i++) {
            pf[i] = (boundary[i - 1] + 2 * boundary[i] + boundary[i + 1] + 2) >> 2;
        }
        return pf;
    }

    /**
     * Predicts a block in the planar mode (8.4.4.2.4).
     *
     * @param p        the boundary array
     * @param log2Size the block size as a power of two
     * @param out      receives the prediction
     * @param offset   the index of the block's top-left sample
     * @param stride   the row stride
     */
    private static void planar(int[] p, int log2Size, short[] out, int offset, int stride) {
        var size = 1 << log2Size;
        var corner = 2 * size;
        var topRight = p[corner + 1 + size];
        var bottomLeft = p[corner - 1 - size];
        for (var y = 0; y < size; y++) {
            var left = p[corner - 1 - y];
            var row = offset + y * stride;
            for (var x = 0; x < size; x++) {
                var top = p[corner + 1 + x];
                out[row + x] = (short) (((size - 1 - x) * left + (x + 1) * topRight + (size - 1 - y) * top
                        + (y + 1) * bottomLeft + size) >> (log2Size + 1));
            }
        }
    }

    /**
     * Predicts a block in the DC mode (8.4.4.2.5).
     *
     * @param p               the boundary array
     * @param log2Size        the block size as a power of two
     * @param boundaryFilters whether the edge samples are filtered
     * @param out             receives the prediction
     * @param offset          the index of the block's top-left sample
     * @param stride          the row stride
     */
    private static void dc(int[] p, int log2Size, boolean boundaryFilters, short[] out, int offset, int stride) {
        var size = 1 << log2Size;
        var corner = 2 * size;
        var sum = size;
        for (var i = 0; i < size; i++) {
            sum += p[corner + 1 + i] + p[corner - 1 - i];
        }
        var dc = sum >> (log2Size + 1);
        var value = (short) dc;
        for (var y = 0; y < size; y++) {
            var row = offset + y * stride;
            for (var x = 0; x < size; x++) {
                out[row + x] = value;
            }
        }
        if (boundaryFilters) {
            out[offset] = (short) ((p[corner - 1] + 2 * dc + p[corner + 1] + 2) >> 2);
            for (var x = 1; x < size; x++) {
                out[offset + x] = (short) ((p[corner + 1 + x] + 3 * dc + 2) >> 2);
            }
            for (var y = 1; y < size; y++) {
                out[offset + y * stride] = (short) ((p[corner - 1 - y] + 3 * dc + 2) >> 2);
            }
        }
    }

    /**
     * Predicts a block in an angular mode (8.4.4.2.6).
     *
     * @param p               the boundary array
     * @param log2Size        the block size as a power of two
     * @param mode            the mode, 2 to 34
     * @param boundaryFilters whether the vertical and horizontal modes filter their first column or
     *                        row
     * @param bitDepth        the bit depth, for the boundary filter clipping
     * @param out             receives the prediction
     * @param offset          the index of the block's top-left sample
     * @param stride          the row stride
     */
    private void angular(int[] p, int log2Size, int mode, boolean boundaryFilters, int bitDepth, short[] out, int offset,
                         int stride) {
        var size = 1 << log2Size;
        var corner = 2 * size;
        var angle = ANGLE[mode];
        var ref = refMain;
        var o = REF_ORIGIN;
        var vertical = mode >= 18;
        for (var x = 0; x <= size; x++) {
            ref[o + x] = vertical ? p[corner + x] : p[corner - x];
        }
        var last = (size * angle) >> 5;
        if (angle < 0) {
            if (last < -1) {
                var inv = INV_ANGLE[mode];
                for (var x = last; x <= -1; x++) {
                    var index = -1 + ((x * inv + 128) >> 8);
                    ref[o + x] = vertical ? p[corner - 1 - index] : p[corner + 1 + index];
                }
            }
        } else {
            for (var x = size + 1; x <= 2 * size; x++) {
                ref[o + x] = vertical ? p[corner + x] : p[corner - x];
            }
        }
        for (var j = 0; j < size; j++) {
            var position = (j + 1) * angle;
            var index = position >> 5;
            var fraction = position & 31;
            var base = o + index + 1;
            if (vertical) {
                var row = offset + j * stride;
                if (fraction != 0) {
                    for (var i = 0; i < size; i++) {
                        out[row + i] = (short) (((32 - fraction) * ref[base + i] + fraction * ref[base + i + 1] + 16) >> 5);
                    }
                } else {
                    for (var i = 0; i < size; i++) {
                        out[row + i] = (short) ref[base + i];
                    }
                }
            } else {
                var column = offset + j;
                if (fraction != 0) {
                    for (var i = 0; i < size; i++) {
                        out[column + i * stride] = (short) (((32 - fraction) * ref[base + i] + fraction * ref[base + i + 1] + 16) >> 5);
                    }
                } else {
                    for (var i = 0; i < size; i++) {
                        out[column + i * stride] = (short) ref[base + i];
                    }
                }
            }
        }
        if (boundaryFilters && angle == 0) {
            var max = (1 << bitDepth) - 1;
            var c = p[corner];
            if (mode == 26) {
                for (var y = 0; y < size; y++) {
                    out[offset + y * stride] = (short) Math.clamp(p[corner + 1] + ((p[corner - 1 - y] - c) >> 1), 0, max);
                }
            } else {
                for (var x = 0; x < size; x++) {
                    out[offset + x] = (short) Math.clamp(p[corner - 1] + ((p[corner + 1 + x] - c) >> 1), 0, max);
                }
            }
        }
    }
}
