package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.color.YcgcoR;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Converts decoded pictures in the YCgCo-Re or YCgCo-Ro matrix to opaque 8-bit RGBA, with the result of
 * {@link com.github.auties00.smalt.image.ops.ImageOps#toRgba(com.github.auties00.smalt.image.Picture)
 * ImageOps.toRgba} on the same samples: the integer inverse transform of ITU-T H.273 recovers the R'G'B' samples
 * at their own depth, two or one bits below the sample depth, and each is quantized once to 8 bits. Subsampled
 * chroma is interpolated bilinearly at the centered positions of its samples (edges replicated) and rounded to the
 * nearest integer before the inverse.
 *
 * <p>An instance keeps the tables of the last frame shape and the state of the frame being converted, so it is not
 * thread-safe; {@link #convert} splits the rows across {@link ParallelBands} and allocates only when the shape or
 * the quantization changes.
 */
public final class YcgcoRgba implements ParallelBands.Band {
    /**
     * The approximate number of operations per pixel, weighed against {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long WORK = 24;

    /**
     * The 8-bit value of each R'G'B' sample of the current quantization.
     */
    private byte[] levels = new byte[0];

    /**
     * The quantization {@link #levels} holds: the R'G'B' depth, or -1 when none was built.
     */
    private int levelsDepth = -1;

    /**
     * Whether {@link #levels} holds the full-range quantization.
     */
    private boolean levelsFull;

    /**
     * The left chroma column of each luma column.
     */
    private int[] left = new int[0];

    /**
     * The right chroma column of each luma column.
     */
    private int[] right = new int[0];

    /**
     * The weight of the right chroma column of each luma column.
     */
    private double[] fraction = new double[0];

    /**
     * The width and format {@link #left}, {@link #right} and {@link #fraction} were built for.
     */
    private int tableWidth = -1;

    /**
     * The format the column tables were built for.
     */
    private ChromaFormat tableFormat;

    /**
     * Whether chroma is replicated rather than interpolated in the current frame.
     */
    private boolean nearest;

    /**
     * The picture being converted.
     */
    private Av1Picture source;

    /**
     * The format of the picture being converted.
     */
    private ChromaFormat format;

    /**
     * The R'G'B' depth of the picture being converted.
     */
    private int rgbDepth;

    /**
     * The destination of the frame being converted.
     */
    private RgbaPicture target;

    /**
     * The number of bands of the frame being converted.
     */
    private int bands;

    /**
     * Converts a decoded picture of the YCgCo-Re or YCgCo-Ro matrix.
     *
     * @param picture the decoded picture
     * @param format the chroma format of the picture
     * @param matrix {@link MatrixCoefficients#YCGCO_RE} or {@link MatrixCoefficients#YCGCO_RO}
     * @param range the quantization range of the R'G'B' samples
     * @param out the opaque destination, of the picture's size
     */
    public void convert(Av1Picture picture, ChromaFormat format, MatrixCoefficients matrix, ColorRange range,
                        RgbaPicture out) {
        var depth = YcgcoR.rgbDepth(matrix, picture.bpc);
        var full = range == ColorRange.FULL;
        if (depth != levelsDepth || full != levelsFull) {
            levels = levels(range, depth);
            levelsDepth = depth;
            levelsFull = full;
        }
        if (picture.w != tableWidth || format != tableFormat) {
            columns(picture.w, format);
        }
        this.source = picture;
        this.format = format;
        this.rgbDepth = depth;
        this.target = out;
        this.nearest = format.shiftX() == 1 && format.chromaWidth(picture.w) <= 2;
        this.bands = ParallelBands.bands(picture.h, WORK * picture.w * picture.h);
        try {
            ParallelBands.run(bands, this);
        } finally {
            this.source = null;
            this.target = null;
        }
    }

    /**
     * Converts one band of rows of the current frame; called by {@link ParallelBands}.
     *
     * @param band the band index
     */
    @Override
    public void run(int band) {
        var picture = source;
        var height = picture.h;
        var last = ParallelBands.start(band + 1, bands, height);
        for (var row = ParallelBands.start(band, bands, height); row < last; row++) {
            if (!format.hasChroma()) {
                monochromeRow(picture, row);
            } else if (format.shiftX() == 0 && format.shiftY() == 0) {
                fullChromaRow(picture, row);
            } else {
                subsampledRow(picture, row);
            }
        }
    }

    /**
     * Converts one row of a monochrome picture, whose color differences are zero.
     *
     * @param picture the decoded picture
     * @param row the row index
     */
    private void monochromeRow(Av1Picture picture, int row) {
        var luma = picture.data[0];
        var lumaRow = row * picture.stride[0];
        var pixels = target.pixels();
        var o = row * target.stride();
        var max = (1 << rgbDepth) - 1;
        for (var x = 0; x < picture.w; x++, o += 4) {
            var gray = levels[Math.clamp(luma[lumaRow + x] & 0xFFFF, 0, max)];
            pixels[o] = gray;
            pixels[o + 1] = gray;
            pixels[o + 2] = gray;
            pixels[o + 3] = (byte) 0xFF;
        }
    }

    /**
     * Converts one row of a 4:4:4 picture.
     *
     * @param picture the decoded picture
     * @param row the row index
     */
    private void fullChromaRow(Av1Picture picture, int row) {
        var luma = picture.data[0];
        var cgPlane = picture.data[1];
        var coPlane = picture.data[2];
        var lumaRow = row * picture.stride[0];
        var chromaRow = row * picture.stride[1];
        var pixels = target.pixels();
        var o = row * target.stride();
        var half = 1 << (picture.bpc - 1);
        var max = (1 << rgbDepth) - 1;
        for (var x = 0; x < picture.w; x++, o += 4) {
            store(pixels, o, luma[lumaRow + x] & 0xFFFF, (cgPlane[chromaRow + x] & 0xFFFF) - half,
                    (coPlane[chromaRow + x] & 0xFFFF) - half, max);
        }
    }

    /**
     * Converts one row of a 4:2:0 or 4:2:2 picture.
     *
     * @param picture the decoded picture
     * @param row the row index
     */
    private void subsampledRow(Av1Picture picture, int row) {
        var chromaHeight = format.chromaHeight(picture.h);
        int top;
        int bottom;
        double weight;
        if (nearest || format.shiftY() == 0) {
            top = bottom = Math.min(row >> format.shiftY(), chromaHeight - 1);
            weight = 0;
        } else {
            var position = (row + 0.5) / (1 << format.shiftY()) - 0.5;
            var floor = (int) Math.floor(position);
            top = Math.clamp(floor, 0, chromaHeight - 1);
            bottom = Math.min(chromaHeight - 1, top + 1);
            weight = position < 0 ? 0 : position - floor;
        }
        var luma = picture.data[0];
        var cgPlane = picture.data[1];
        var coPlane = picture.data[2];
        var lumaRow = row * picture.stride[0];
        var topRow = top * picture.stride[1];
        var bottomRow = bottom * picture.stride[1];
        var pixels = target.pixels();
        var o = row * target.stride();
        var half = 1 << (picture.bpc - 1);
        var max = (1 << rgbDepth) - 1;
        var lefts = nearest ? null : left;
        for (var x = 0; x < picture.w; x++, o += 4) {
            int l;
            int r;
            double f;
            if (lefts == null) {
                l = r = Math.min(x >> format.shiftX(), format.chromaWidth(picture.w) - 1);
                f = 0;
            } else {
                l = lefts[x];
                r = right[x];
                f = fraction[x];
            }
            var cgLeft = interpolate(cgPlane, topRow + l, bottomRow + l, weight);
            var cgRight = interpolate(cgPlane, topRow + r, bottomRow + r, weight);
            var coLeft = interpolate(coPlane, topRow + l, bottomRow + l, weight);
            var coRight = interpolate(coPlane, topRow + r, bottomRow + r, weight);
            var cg = (int) Math.round(cgLeft + (cgRight - cgLeft) * f);
            var co = (int) Math.round(coLeft + (coRight - coLeft) * f);
            store(pixels, o, luma[lumaRow + x] & 0xFFFF, cg - half, co - half, max);
        }
    }

    /**
     * Interpolates a chroma sample between two rows.
     *
     * @param plane the chroma plane
     * @param top the index of the sample in the upper row
     * @param bottom the index of the sample in the lower row
     * @param weight the weight of the lower row
     * @return the interpolated code value
     */
    private static double interpolate(short[] plane, int top, int bottom, double weight) {
        var upper = plane[top] & 0xFFFF;
        var lower = plane[bottom] & 0xFFFF;
        return upper + (lower - upper) * weight;
    }

    /**
     * Inverts the transform of one pixel (ITU-T H.273 equations 62 to 65) and stores its opaque 8-bit RGBA.
     *
     * @param pixels the destination
     * @param o the index of the pixel's red byte
     * @param y the luma sample
     * @param cg the Cg sample minus its offset
     * @param co the Co sample minus its offset
     * @param max the largest R'G'B' sample
     */
    private void store(byte[] pixels, int o, int y, int cg, int co, int max) {
        var t = y - (cg >> 1);
        var b = Math.clamp(t - (co >> 1), 0, max);
        pixels[o] = levels[Math.clamp(b + co, 0, max)];
        pixels[o + 1] = levels[Math.clamp(t + cg, 0, max)];
        pixels[o + 2] = levels[b];
        pixels[o + 3] = (byte) 0xFF;
    }

    /**
     * Builds the 8-bit value of every R'G'B' sample of a depth and range: the sample's normalized signal, scaled
     * to 255, clamped and rounded half up.
     *
     * @param range the quantization range
     * @param depth the R'G'B' depth
     * @return the values, indexed by sample
     */
    private static byte[] levels(ColorRange range, int depth) {
        var values = new byte[1 << depth];
        for (var s = 0; s < values.length; s++) {
            values[s] = (byte) Math.round(Math.clamp(YcgcoR.toSignal(s, range, depth) * 255, 0, 255));
        }
        return values;
    }

    /**
     * Builds the chroma columns of every luma column of a width and format, interpolating at the centered
     * positions of the chroma samples.
     *
     * @param width the luma width
     * @param format the chroma format
     */
    private void columns(int width, ChromaFormat format) {
        if (left.length < width) {
            left = new int[width];
            right = new int[width];
            fraction = new double[width];
        }
        if (format.hasChroma()) {
            var chromaWidth = format.chromaWidth(width);
            for (var x = 0; x < width; x++) {
                if (format.shiftX() == 0) {
                    left[x] = right[x] = Math.min(x, chromaWidth - 1);
                    fraction[x] = 0;
                    continue;
                }
                var position = (x + 0.5) / (1 << format.shiftX()) - 0.5;
                var floor = (int) Math.floor(position);
                left[x] = Math.clamp(floor, 0, chromaWidth - 1);
                right[x] = Math.min(chromaWidth - 1, left[x] + 1);
                fraction[x] = position < 0 ? 0 : position - floor;
            }
        }
        tableWidth = width;
        tableFormat = format;
    }
}
