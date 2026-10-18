package com.github.auties00.smalt.video.intra.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.color.LumaWeights;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Locale;

/**
 * Prepares any {@link Picture} for the intra encoders: converts it to limited-range Y'CbCr (or
 * RGB) planes of the encoder's bit depth and chroma layout, padded to whole macroblocks.
 *
 * <p>Y'CbCr pictures keep their matrix: their samples are widened or rounded to the target depth,
 * full-range samples are mapped to limited range, and the chroma planes are resampled when the
 * layouts differ, with box averaging to reduce and linear interpolation to enlarge. RGB pictures
 * are converted with the luma weights of the requested matrix (BT.709 for an unspecified one), at
 * full precision, each subsampled chroma sample averaging the pixels it covers; a matrix that two
 * luma weights do not define is unsupported. Alpha, when requested, comes from the
 * alpha plane or channel, rescaled to the alpha depth; a picture without alpha gets opaque alpha.
 */
public final class IntraInput {
    /**
     * Prevents instantiation.
     */
    private IntraInput() {
        throw new AssertionError();
    }

    /**
     * Converts a picture to padded Y'CbCr planes.
     *
     * @param picture the picture to convert, RGBA or planar Y'CbCr at any depth
     * @param format the chroma layout of the result: {@link ChromaFormat#YUV420},
     * {@link ChromaFormat#YUV422} or {@link ChromaFormat#YUV444}
     * @param depth the bit depth of the result, from 8 to 16
     * @param rgbColor the color description an RGB picture is converted with; its range is
     * ignored, the result being limited range
     * @param alphaDepth the bit depth of the alpha plane, or {@code 0} for no alpha plane
     * @param paddedWidth the width of the planes, at least the picture's and a multiple of 16
     * @param paddedHeight the height of the planes, at least the picture's
     * @param threads the thread count: {@code 1} for the calling thread, otherwise the shared
     * pool with at most this many threads ({@code 0} for no cap)
     * @param recycled planes of an earlier call whose arrays the result may reuse when their sizes
     * fit, or {@code null}; the caller must not use them afterwards
     * @return the planes
     * @throws MediaException.Unsupported if the picture is RGB and two luma weights do not define
     * the matrix of {@code rgbColor}
     */
    public static IntraPlanes yuv(Picture picture, ChromaFormat format, int depth, ColorInfo rgbColor, int alphaDepth,
                                  int paddedWidth, int paddedHeight, int threads, IntraPlanes recycled) {
        var width = picture.width();
        var height = picture.height();
        var shiftX = format.shiftX();
        var shiftY = format.shiftY();
        var chromaStride = paddedWidth >> shiftX;
        var chromaRows = paddedHeight >> shiftY;
        var y = plane(recycled == null ? null : recycled.first(), paddedWidth * paddedHeight);
        var u = plane(recycled == null ? null : recycled.second(), chromaStride * chromaRows);
        var v = plane(recycled == null ? null : recycled.third(), chromaStride * chromaRows);
        var alpha = alphaDepth > 0 ? plane(recycled == null ? null : recycled.alpha(), paddedWidth * paddedHeight)
                : null;
        ColorInfo color;
        switch (picture) {
            case RgbaPicture rgba -> {
                rgbToYuv(rgba.pixels(), null, rgba.stride(), 8, width, height, format, depth, rgbColor, y, u, v,
                        paddedWidth, chromaStride);
                color = limited(rgbColor);
                if (alpha != null) {
                    var pixels = rgba.pixels();
                    for (var row = 0; row < height; row++) {
                        for (var x = 0; x < width; x++) {
                            alpha[row * paddedWidth + x] = (short) rescale(pixels[row * rgba.stride() + x * 4 + 3] & 0xFF,
                                    8, alphaDepth);
                        }
                    }
                }
            }
            case Rgba16Picture rgba -> {
                rgbToYuv(null, rgba.pixels(), rgba.stride(), 16, width, height, format, depth, rgbColor, y, u, v,
                        paddedWidth, chromaStride);
                color = limited(rgbColor);
                if (alpha != null) {
                    var pixels = rgba.pixels();
                    for (var row = 0; row < height; row++) {
                        for (var x = 0; x < width; x++) {
                            alpha[row * paddedWidth + x] = (short) rescale(pixels[row * rgba.stride() + x * 4 + 3] & 0xFFFF,
                                    16, alphaDepth);
                        }
                    }
                }
            }
            case YuvPicture yuv -> {
                var full = yuv.color().range() == ColorRange.FULL;
                color = yuv.color().withRange(ColorRange.LIMITED);
                if (yuv.chromaFormat() == format && format.hasChroma()) {
                    copyPlanes(yuv.y(), null, yuv.u(), null, yuv.v(), null, yuv.yStride(), yuv.uvStride(), 8, width,
                            height, format, depth, full, y, u, v, paddedWidth, chromaStride, threads);
                } else {
                    copyPlane(yuv.y(), null, yuv.yStride(), 8, width, height, depth, full, true, y, paddedWidth, threads);
                    chroma(yuv.chromaFormat(), yuv.u(), null, yuv.v(), null, yuv.uvStride(), 8, width, height, format,
                            depth, full, u, v, chromaStride, threads);
                }
                if (alpha != null && yuv.alpha() != null) {
                    for (var row = 0; row < height; row++) {
                        for (var x = 0; x < width; x++) {
                            alpha[row * paddedWidth + x] = (short) rescale(yuv.alpha()[row * yuv.alphaStride() + x] & 0xFF,
                                    8, alphaDepth);
                        }
                    }
                } else if (alpha != null) {
                    java.util.Arrays.fill(alpha, (short) ((1 << alphaDepth) - 1));
                }
            }
            case Yuv16Picture yuv -> {
                var full = yuv.color().range() == ColorRange.FULL;
                color = yuv.color().withRange(ColorRange.LIMITED);
                if (yuv.chromaFormat() == format && format.hasChroma()) {
                    copyPlanes(null, yuv.y(), null, yuv.u(), null, yuv.v(), yuv.yStride(), yuv.uvStride(),
                            yuv.bitDepth(), width, height, format, depth, full, y, u, v, paddedWidth, chromaStride,
                            threads);
                } else {
                    copyPlane(null, yuv.y(), yuv.yStride(), yuv.bitDepth(), width, height, depth, full, true, y,
                            paddedWidth, threads);
                    chroma(yuv.chromaFormat(), null, yuv.u(), null, yuv.v(), yuv.uvStride(), yuv.bitDepth(), width,
                            height, format, depth, full, u, v, chromaStride, threads);
                }
                if (alpha != null && yuv.alpha() != null) {
                    for (var row = 0; row < height; row++) {
                        for (var x = 0; x < width; x++) {
                            alpha[row * paddedWidth + x] = (short) rescale(yuv.alpha()[row * yuv.alphaStride() + x] & 0xFFFF,
                                    yuv.bitDepth(), alphaDepth);
                        }
                    }
                } else if (alpha != null) {
                    java.util.Arrays.fill(alpha, (short) ((1 << alphaDepth) - 1));
                }
            }
        }
        if (alpha != null && !picture.hasAlpha() && !(picture instanceof YuvPicture) && !(picture instanceof Yuv16Picture)) {
            java.util.Arrays.fill(alpha, (short) ((1 << alphaDepth) - 1));
        }
        pad(y, paddedWidth, width, height, paddedHeight);
        var chromaWidth = (width + (1 << shiftX) - 1) >> shiftX;
        var chromaHeight = (height + (1 << shiftY) - 1) >> shiftY;
        pad(u, chromaStride, chromaWidth, chromaHeight, chromaRows);
        pad(v, chromaStride, chromaWidth, chromaHeight, chromaRows);
        if (alpha != null) {
            pad(alpha, paddedWidth, width, height, paddedHeight);
        }
        return new IntraPlanes(y, u, v, alpha, paddedWidth, chromaStride, paddedHeight, depth, alphaDepth, color);
    }

    /**
     * Prepares the conversion of a Y'CbCr picture whose chroma layout is the target layout, without
     * converting it: the caller converts the picture one macroblock row at a time with
     * {@link Deferred#fill}, so that the conversion runs inside the parallel work that consumes the
     * rows.
     *
     * @param picture the Y'CbCr picture to convert, in the target chroma layout
     * @param format the chroma layout of the result
     * @param depth the bit depth of the result, from 8 to 16
     * @param paddedWidth the width of the planes, at least the picture's and a multiple of 16
     * @param paddedHeight the height of the planes, a multiple of 16 less than 16 rows above the
     * picture's
     * @param recycled planes of an earlier call whose arrays may be reused, or {@code null}
     * @return the deferred conversion, or {@code null} when the picture is not a Y'CbCr picture of
     * the target layout with chroma, or the padding exceeds one macroblock row
     */
    public static Deferred yuvDeferred(Picture picture, ChromaFormat format, int depth, int paddedWidth,
                                       int paddedHeight, IntraPlanes recycled) {
        if (!format.hasChroma() || paddedHeight - picture.height() >= 16 || (paddedHeight & 15) != 0) {
            return null;
        }
        byte[] y8 = null;
        byte[] u8 = null;
        byte[] v8 = null;
        short[] y16 = null;
        short[] u16 = null;
        short[] v16 = null;
        int yStride;
        int uvStride;
        int sourceDepth;
        ColorInfo sourceColor;
        switch (picture) {
            case YuvPicture yuv when yuv.chromaFormat() == format -> {
                y8 = yuv.y();
                u8 = yuv.u();
                v8 = yuv.v();
                yStride = yuv.yStride();
                uvStride = yuv.uvStride();
                sourceDepth = 8;
                sourceColor = yuv.color();
            }
            case Yuv16Picture yuv when yuv.chromaFormat() == format -> {
                y16 = yuv.y();
                u16 = yuv.u();
                v16 = yuv.v();
                yStride = yuv.yStride();
                uvStride = yuv.uvStride();
                sourceDepth = yuv.bitDepth();
                sourceColor = yuv.color();
            }
            default -> {
                return null;
            }
        }
        var chromaStride = paddedWidth >> format.shiftX();
        var chromaRows = paddedHeight >> format.shiftY();
        var planes = new IntraPlanes(plane(recycled == null ? null : recycled.first(), paddedWidth * paddedHeight),
                plane(recycled == null ? null : recycled.second(), chromaStride * chromaRows),
                plane(recycled == null ? null : recycled.third(), chromaStride * chromaRows), null, paddedWidth,
                chromaStride, paddedHeight, depth, 0, sourceColor.withRange(ColorRange.LIMITED));
        return new Deferred(planes, picture.width(), picture.height(), format, sourceDepth,
                sourceColor.range() == ColorRange.FULL, y8, y16, u8, u16, v8, v16, yStride, uvStride);
    }

    /**
     * Converts a Y'CbCr picture into prepared planes one macroblock row at a time.
     *
     * <p>{@link #fill} of different rows may run concurrently; it writes only the rows of its
     * macroblock row of every plane.
     */
    public static final class Deferred {
        /**
         * The planes being filled.
         */
        private final IntraPlanes planes;

        /**
         * The picture width.
         */
        private final int width;

        /**
         * The picture height.
         */
        private final int height;

        /**
         * The chroma layout.
         */
        private final ChromaFormat format;

        /**
         * The source bit depth.
         */
        private final int sourceDepth;

        /**
         * Whether the source is full range.
         */
        private final boolean full;

        /**
         * The 8-bit luma source, or {@code null}.
         */
        private final byte[] y8;

        /**
         * The 16-bit luma source, or {@code null}.
         */
        private final short[] y16;

        /**
         * The 8-bit Cb source, or {@code null}.
         */
        private final byte[] u8;

        /**
         * The 16-bit Cb source, or {@code null}.
         */
        private final short[] u16;

        /**
         * The 8-bit Cr source, or {@code null}.
         */
        private final byte[] v8;

        /**
         * The 16-bit Cr source, or {@code null}.
         */
        private final short[] v16;

        /**
         * The luma source row stride.
         */
        private final int yStride;

        /**
         * The chroma source row stride.
         */
        private final int uvStride;

        /**
         * Constructs a deferred conversion.
         *
         * @param planes the target planes
         * @param width the picture width
         * @param height the picture height
         * @param format the chroma layout
         * @param sourceDepth the source bit depth
         * @param full whether the source is full range
         * @param y8 the 8-bit luma source, or {@code null}
         * @param y16 the 16-bit luma source, or {@code null}
         * @param u8 the 8-bit Cb source, or {@code null}
         * @param u16 the 16-bit Cb source, or {@code null}
         * @param v8 the 8-bit Cr source, or {@code null}
         * @param v16 the 16-bit Cr source, or {@code null}
         * @param yStride the luma source row stride
         * @param uvStride the chroma source row stride
         */
        private Deferred(IntraPlanes planes, int width, int height, ChromaFormat format, int sourceDepth,
                         boolean full, byte[] y8, short[] y16, byte[] u8, short[] u16, byte[] v8, short[] v16,
                         int yStride, int uvStride) {
            this.planes = planes;
            this.width = width;
            this.height = height;
            this.format = format;
            this.sourceDepth = sourceDepth;
            this.full = full;
            this.y8 = y8;
            this.y16 = y16;
            this.u8 = u8;
            this.u16 = u16;
            this.v8 = v8;
            this.v16 = v16;
            this.yStride = yStride;
            this.uvStride = uvStride;
        }

        /**
         * Returns the planes, whose rows are valid once {@link #fill} has run for them.
         *
         * @return the planes
         */
        public IntraPlanes planes() {
            return planes;
        }

        /**
         * Converts and pads the 16 luma rows of a macroblock row, and the chroma rows they cover.
         *
         * @param macroblockRow the macroblock row
         */
        public void fill(int macroblockRow) {
            var shiftX = format.shiftX();
            var shiftY = format.shiftY();
            var depth = planes.depth();
            var from = macroblockRow * 16;
            var to = Math.min(from + 16, height);
            var chromaWidth = (width + (1 << shiftX) - 1) >> shiftX;
            var chromaHeight = (height + (1 << shiftY) - 1) >> shiftY;
            if (from < height) {
                copyRows(y8, y16, yStride, sourceDepth, width, depth, full, true, planes.first(), planes.lumaStride(),
                        from, to);
                padColumns(planes.first(), planes.lumaStride(), width, from, to);
                var chromaFrom = from >> shiftY;
                var chromaTo = Math.min((to + (1 << shiftY) - 1) >> shiftY, chromaHeight);
                copyRows(u8, u16, uvStride, sourceDepth, chromaWidth, depth, full, false, planes.second(),
                        planes.chromaStride(), chromaFrom, chromaTo);
                copyRows(v8, v16, uvStride, sourceDepth, chromaWidth, depth, full, false, planes.third(),
                        planes.chromaStride(), chromaFrom, chromaTo);
                padColumns(planes.second(), planes.chromaStride(), chromaWidth, chromaFrom, chromaTo);
                padColumns(planes.third(), planes.chromaStride(), chromaWidth, chromaFrom, chromaTo);
            }
            if (from + 16 > height) {
                var first = Math.max(from, height);
                for (var row = first; row < from + 16; row++) {
                    System.arraycopy(planes.first(), (height - 1) * planes.lumaStride(), planes.first(),
                            row * planes.lumaStride(), planes.lumaStride());
                }
                var chromaFirst = Math.max(from >> shiftY, chromaHeight);
                for (var row = chromaFirst; row < (from + 16) >> shiftY; row++) {
                    System.arraycopy(planes.second(), (chromaHeight - 1) * planes.chromaStride(), planes.second(),
                            row * planes.chromaStride(), planes.chromaStride());
                    System.arraycopy(planes.third(), (chromaHeight - 1) * planes.chromaStride(), planes.third(),
                            row * planes.chromaStride(), planes.chromaStride());
                }
            }
        }
    }

    /**
     * Fills the padding columns of rows of a plane by replicating the last column of the picture
     * area.
     *
     * @param plane the plane to pad, row by row
     * @param stride its row stride, the padded width
     * @param width the width of the picture area
     * @param from the first row
     * @param to the row after the last
     */
    private static void padColumns(short[] plane, int stride, int width, int from, int to) {
        if (width < stride) {
            for (var row = from; row < to; row++) {
                java.util.Arrays.fill(plane, row * stride + width, (row + 1) * stride, plane[row * stride + width - 1]);
            }
        }
    }

    /**
     * Returns an array of a length, reusing a previous one when it has that length.
     *
     * @param old the array to reuse, or {@code null}
     * @param length the required length, in samples
     * @return {@code old} if it has the length, otherwise a new array
     */
    private static short[] plane(short[] old, int length) {
        return old != null && old.length == length ? old : new short[length];
    }

    /**
     * Converts a picture to padded full-range R'G'B' planes of one depth.
     *
     * @param picture the picture; Y'CbCr pictures are converted with their own matrix
     * @param depth the bit depth of the result, from 8 to 16
     * @param alphaDepth the bit depth of the alpha plane, or {@code 0} for no alpha plane
     * @param paddedWidth the width of the planes, at least the picture's
     * @param paddedHeight the height of the planes, at least the picture's
     * @return the planes: red, green and blue
     */
    public static IntraPlanes rgb(Picture picture, int depth, int alphaDepth, int paddedWidth, int paddedHeight) {
        var width = picture.width();
        var height = picture.height();
        var r = new short[paddedWidth * paddedHeight];
        var g = new short[paddedWidth * paddedHeight];
        var b = new short[paddedWidth * paddedHeight];
        var alpha = alphaDepth > 0 ? new short[paddedWidth * paddedHeight] : null;
        var rgba = picture instanceof Rgba16Picture wide ? wide
                : com.github.auties00.smalt.image.ops.ImageOps.toSixteenBit(picture instanceof RgbaPicture narrow ? narrow
                : com.github.auties00.smalt.image.ops.ImageOps.toRgba(picture));
        var pixels = rgba.pixels();
        for (var row = 0; row < height; row++) {
            var from = row * rgba.stride();
            var to = row * paddedWidth;
            for (var x = 0; x < width; x++, from += 4) {
                r[to + x] = (short) rescale(pixels[from] & 0xFFFF, 16, depth);
                g[to + x] = (short) rescale(pixels[from + 1] & 0xFFFF, 16, depth);
                b[to + x] = (short) rescale(pixels[from + 2] & 0xFFFF, 16, depth);
                if (alpha != null) {
                    alpha[to + x] = (short) rescale(pixels[from + 3] & 0xFFFF, 16, alphaDepth);
                }
            }
        }
        pad(r, paddedWidth, width, height, paddedHeight);
        pad(g, paddedWidth, width, height, paddedHeight);
        pad(b, paddedWidth, width, height, paddedHeight);
        if (alpha != null) {
            pad(alpha, paddedWidth, width, height, paddedHeight);
        }
        var color = new ColorInfo(MatrixCoefficients.IDENTITY, ColorRange.FULL,
                com.github.auties00.smalt.image.ColorPrimaries.BT709,
                com.github.auties00.smalt.image.TransferCharacteristics.BT709);
        return new IntraPlanes(r, g, b, alpha, paddedWidth, paddedWidth, paddedHeight, depth, alphaDepth, color);
    }

    /**
     * Returns a color description in limited range.
     *
     * @param color the description
     * @return the description with {@link ColorRange#LIMITED}
     */
    private static ColorInfo limited(ColorInfo color) {
        return color.withRange(ColorRange.LIMITED);
    }

    /**
     * Rescales an unsigned value between bit depths so that the largest value maps to the largest
     * value, rounding to nearest.
     *
     * @param value the unsigned sample value, in {@code from} bits
     * @param from its bit depth
     * @param to the target bit depth
     * @return the rescaled value
     */
    private static int rescale(int value, int from, int to) {
        if (from == to) {
            return value;
        }
        var inMax = (1L << from) - 1;
        var outMax = (1L << to) - 1;
        return (int) ((value * outMax + inMax / 2) / inMax);
    }

    /**
     * Copies the three planes of a Y'CbCr picture whose layout is the target layout to the target
     * depth, mapping full range to limited range, in one set of parallel bands of rows.
     *
     * @param y8 the 8-bit luma source, or {@code null}
     * @param y16 the 16-bit luma source, or {@code null}
     * @param u8 the 8-bit Cb source, or {@code null}
     * @param u16 the 16-bit Cb source, or {@code null}
     * @param v8 the 8-bit Cr source, or {@code null}
     * @param v16 the 16-bit Cr source, or {@code null}
     * @param yStride the luma source row stride
     * @param uvStride the chroma source row stride
     * @param sourceDepth the source bit depth
     * @param width the luma width
     * @param height the luma height
     * @param layout the chroma layout of the source and the target
     * @param depth the target bit depth
     * @param full whether the source is full range
     * @param y the target luma plane
     * @param u the target Cb plane
     * @param v the target Cr plane
     * @param yOutStride the target luma row stride
     * @param uvOutStride the target chroma row stride
     * @param threads the thread count, as for {@link #yuv}
     */
    private static void copyPlanes(byte[] y8, short[] y16, byte[] u8, short[] u16, byte[] v8, short[] v16, int yStride,
                                   int uvStride, int sourceDepth, int width, int height, ChromaFormat layout, int depth,
                                   boolean full, short[] y, short[] u, short[] v, int yOutStride, int uvOutStride,
                                   int threads) {
        var chromaWidth = layout.chromaWidth(width);
        var chromaHeight = layout.chromaHeight(height);
        var bands = IntraBands.count(threads, chromaHeight, ((long) width * height + 2L * chromaWidth * chromaHeight) / 8);
        if (bands == 1) {
            copyRows(y8, y16, yStride, sourceDepth, width, depth, full, true, y, yOutStride, 0, height);
            copyRows(u8, u16, uvStride, sourceDepth, chromaWidth, depth, full, false, u, uvOutStride, 0, chromaHeight);
            copyRows(v8, v16, uvStride, sourceDepth, chromaWidth, depth, full, false, v, uvOutStride, 0, chromaHeight);
            return;
        }
        ParallelBands.run(bands, band -> {
            copyRows(y8, y16, yStride, sourceDepth, width, depth, full, true, y, yOutStride,
                    ParallelBands.start(band, bands, height), ParallelBands.start(band + 1, bands, height));
            var from = ParallelBands.start(band, bands, chromaHeight);
            var to = ParallelBands.start(band + 1, bands, chromaHeight);
            copyRows(u8, u16, uvStride, sourceDepth, chromaWidth, depth, full, false, u, uvOutStride, from, to);
            copyRows(v8, v16, uvStride, sourceDepth, chromaWidth, depth, full, false, v, uvOutStride, from, to);
        });
    }

    /**
     * Copies a plane to the target depth, mapping full range to limited range, in parallel bands
     * of rows when the plane is large.
     *
     * @param narrow the 8-bit source, or {@code null}
     * @param wide the 16-bit source, or {@code null}
     * @param stride the source row stride
     * @param sourceDepth the source bit depth
     * @param width the number of columns
     * @param height the number of rows
     * @param depth the target bit depth
     * @param full whether the source is full range
     * @param luma whether the plane is luma rather than chroma
     * @param out the target plane
     * @param outStride the target row stride
     * @param threads the thread count, as for {@link #yuv}
     */
    private static void copyPlane(byte[] narrow, short[] wide, int stride, int sourceDepth, int width, int height,
                                  int depth, boolean full, boolean luma, short[] out, int outStride, int threads) {
        var bands = IntraBands.count(threads, height, (long) width * height / 8);
        if (bands == 1) {
            copyRows(narrow, wide, stride, sourceDepth, width, depth, full, luma, out, outStride, 0, height);
            return;
        }
        ParallelBands.run(bands, band -> copyRows(narrow, wide, stride, sourceDepth, width, depth, full, luma, out,
                outStride, ParallelBands.start(band, bands, height), ParallelBands.start(band + 1, bands, height)));
    }

    /**
     * Copies rows of a plane to the target depth, mapping full range to limited range.
     *
     * @param narrow the 8-bit source, or {@code null}
     * @param wide the 16-bit source, or {@code null}
     * @param stride the source row stride
     * @param sourceDepth the source bit depth
     * @param width the number of columns
     * @param depth the target bit depth
     * @param full whether the source is full range
     * @param luma whether the plane is luma rather than chroma
     * @param out the target plane
     * @param outStride the target row stride
     * @param from the first row
     * @param to the row after the last
     */
    private static void copyRows(byte[] narrow, short[] wide, int stride, int sourceDepth, int width, int depth,
                                 boolean full, boolean luma, short[] out, int outStride, int from, int to) {
        var max = (1 << depth) - 1;
        if (!full && sourceDepth == depth && wide != null) {
            for (var row = from; row < to; row++) {
                System.arraycopy(wide, row * stride, out, row * outStride, width);
            }
            return;
        }
        if (!full && sourceDepth <= depth && narrow != null) {
            var shift = depth - sourceDepth;
            for (var row = from; row < to; row++) {
                var source = row * stride;
                var target = row * outStride;
                for (var x = 0; x < width; x++) {
                    out[target + x] = (short) ((narrow[source + x] & 0xFF) << shift);
                }
            }
            return;
        }
        for (var row = from; row < to; row++) {
            for (var x = 0; x < width; x++) {
                var value = narrow != null ? narrow[row * stride + x] & 0xFF : wide[row * stride + x] & 0xFFFF;
                out[row * outStride + x] = (short) convert(value, sourceDepth, depth, full, luma, max);
            }
        }
    }

    /**
     * Converts one Y'CbCr sample to the target depth and limited range.
     *
     * @param value the sample
     * @param sourceDepth its bit depth
     * @param depth the target bit depth
     * @param full whether the sample is full range
     * @param luma whether it is a luma sample
     * @param max the largest target sample
     * @return the converted sample
     */
    private static int convert(int value, int sourceDepth, int depth, boolean full, boolean luma, int max) {
        if (full) {
            var unit = (double) value / ((1 << sourceDepth) - 1);
            var scale = 1 << (depth - 8);
            var limited = luma ? (16 + 219 * unit) * scale : (128 + 224 * (unit - 0.5)) * scale;
            return Math.clamp(Math.round(limited), 0, max);
        }
        if (sourceDepth <= depth) {
            return value << (depth - sourceDepth);
        }
        var shift = sourceDepth - depth;
        return Math.min(max, (value + (1 << (shift - 1))) >> shift);
    }

    /**
     * Converts the chroma planes of a Y'CbCr picture to the target layout and depth.
     *
     * @param source the source layout
     * @param u8 the 8-bit Cb source, or {@code null}
     * @param u16 the 16-bit Cb source, or {@code null}
     * @param v8 the 8-bit Cr source, or {@code null}
     * @param v16 the 16-bit Cr source, or {@code null}
     * @param stride the source chroma row stride
     * @param sourceDepth the source bit depth
     * @param width the luma width
     * @param height the luma height
     * @param target the target layout
     * @param depth the target bit depth
     * @param full whether the source is full range
     * @param u the target Cb plane
     * @param v the target Cr plane
     * @param outStride the target chroma row stride
     * @param threads the thread count, as for {@link #yuv}
     */
    private static void chroma(ChromaFormat source, byte[] u8, short[] u16, byte[] v8, short[] v16, int stride,
                               int sourceDepth, int width, int height, ChromaFormat target, int depth, boolean full,
                               short[] u, short[] v, int outStride, int threads) {
        var max = (1 << depth) - 1;
        if (!source.hasChroma()) {
            var neutral = (short) (1 << (depth - 1));
            java.util.Arrays.fill(u, neutral);
            java.util.Arrays.fill(v, neutral);
            return;
        }
        var sourceWidth = source.chromaWidth(width);
        var sourceHeight = source.chromaHeight(height);
        var targetWidth = target.chromaWidth(width);
        var targetHeight = target.chromaHeight(height);
        if (source == target) {
            copyPlane(u8, u16, stride, sourceDepth, sourceWidth, sourceHeight, depth, full, false, u, outStride, threads);
            copyPlane(v8, v16, stride, sourceDepth, sourceWidth, sourceHeight, depth, full, false, v, outStride, threads);
            return;
        }
        for (var plane = 0; plane < 2; plane++) {
            var narrow = plane == 0 ? u8 : v8;
            var wide = plane == 0 ? u16 : v16;
            var out = plane == 0 ? u : v;
            var converted = new int[sourceWidth * sourceHeight];
            for (var row = 0; row < sourceHeight; row++) {
                for (var x = 0; x < sourceWidth; x++) {
                    var value = narrow != null ? narrow[row * stride + x] & 0xFF : wide[row * stride + x] & 0xFFFF;
                    converted[row * sourceWidth + x] = convert(value, sourceDepth, depth, full, false, max);
                }
            }
            var columns = resampleColumns(converted, sourceWidth, sourceHeight, targetWidth, max);
            var rows = resampleRows(columns, targetWidth, sourceHeight, targetHeight, max);
            for (var row = 0; row < targetHeight; row++) {
                for (var x = 0; x < targetWidth; x++) {
                    out[row * outStride + x] = (short) rows[row * targetWidth + x];
                }
            }
        }
    }

    /**
     * Resamples the columns of a plane: halving by averaging pairs, doubling by linear
     * interpolation, or copying.
     *
     * @param in the plane
     * @param width its width
     * @param height its height
     * @param target the target width
     * @param max the largest sample
     * @return the resampled plane
     */
    private static int[] resampleColumns(int[] in, int width, int height, int target, int max) {
        if (target == width) {
            return in;
        }
        var out = new int[target * height];
        for (var row = 0; row < height; row++) {
            var from = row * width;
            for (var x = 0; x < target; x++) {
                int value;
                if (target < width) {
                    var a = in[from + Math.min(2 * x, width - 1)];
                    var b = in[from + Math.min(2 * x + 1, width - 1)];
                    value = (a + b + 1) >> 1;
                } else {
                    var k = x >> 1;
                    value = (x & 1) == 0 ? in[from + k] : (in[from + k] + in[from + Math.min(k + 1, width - 1)] + 1) >> 1;
                }
                out[row * target + x] = Math.min(max, value);
            }
        }
        return out;
    }

    /**
     * Resamples the rows of a plane: halving by averaging pairs, doubling by linear interpolation
     * between the interstitial sample positions, or copying.
     *
     * @param in the plane
     * @param width its width
     * @param height its height
     * @param target the target height
     * @param max the largest sample
     * @return the resampled plane
     */
    private static int[] resampleRows(int[] in, int width, int height, int target, int max) {
        if (target == height) {
            return in;
        }
        var out = new int[width * target];
        for (var row = 0; row < target; row++) {
            for (var x = 0; x < width; x++) {
                int value;
                if (target < height) {
                    var a = in[Math.min(2 * row, height - 1) * width + x];
                    var b = in[Math.min(2 * row + 1, height - 1) * width + x];
                    value = (a + b + 1) >> 1;
                } else {
                    var k = row >> 1;
                    var near = in[k * width + x];
                    var neighbor = (row & 1) == 0 ? in[Math.max(k - 1, 0) * width + x]
                            : in[Math.min(k + 1, height - 1) * width + x];
                    value = (3 * near + neighbor + 2) >> 2;
                }
                out[row * width + x] = Math.min(max, value);
            }
        }
        return out;
    }

    /**
     * Converts R'G'B' samples to limited-range Y'CbCr planes.
     *
     * @param narrow the 8-bit RGBA samples, or {@code null}
     * @param wide the 16-bit RGBA samples, or {@code null}
     * @param stride the source row stride in samples
     * @param sourceDepth 8 or 16
     * @param width the picture width, in pixels
     * @param height the picture height, in pixels
     * @param format the target chroma layout
     * @param depth the target bit depth
     * @param color the color description whose matrix is used
     * @param y the luma plane
     * @param u the Cb plane
     * @param v the Cr plane
     * @param lumaStride the luma row stride
     * @param chromaStride the chroma row stride
     */
    private static void rgbToYuv(byte[] narrow, short[] wide, int stride, int sourceDepth, int width, int height,
                                 ChromaFormat format, int depth, ColorInfo color, short[] y, short[] u, short[] v,
                                 int lumaStride, int chromaStride) {
        var weights = weights(color.matrix());
        var kr = weights.kr();
        var kb = weights.kb();
        var kg = weights.kg();
        var scale = 1 << (depth - 8);
        var max = (1 << depth) - 1;
        var unit = 1.0 / ((1 << sourceDepth) - 1);
        var cb = new double[width * height];
        var cr = new double[width * height];
        for (var row = 0; row < height; row++) {
            for (var x = 0; x < width; x++) {
                var at = row * stride + x * 4;
                double r;
                double g;
                double b;
                if (narrow != null) {
                    r = (narrow[at] & 0xFF) * unit;
                    g = (narrow[at + 1] & 0xFF) * unit;
                    b = (narrow[at + 2] & 0xFF) * unit;
                } else {
                    r = (wide[at] & 0xFFFF) * unit;
                    g = (wide[at + 1] & 0xFFFF) * unit;
                    b = (wide[at + 2] & 0xFFFF) * unit;
                }
                var luma = kr * r + kg * g + kb * b;
                y[row * lumaStride + x] = (short) Math.clamp(Math.round((16 + 219 * luma) * scale), 0, max);
                cb[row * width + x] = (b - luma) / (2 * (1 - kb));
                cr[row * width + x] = (r - luma) / (2 * (1 - kr));
            }
        }
        var shiftX = format.shiftX();
        var shiftY = format.shiftY();
        var chromaWidth = format.chromaWidth(width);
        var chromaHeight = format.chromaHeight(height);
        for (var row = 0; row < chromaHeight; row++) {
            for (var x = 0; x < chromaWidth; x++) {
                var sumB = 0.0;
                var sumR = 0.0;
                var count = 0;
                for (var dy = 0; dy < 1 << shiftY; dy++) {
                    var sy = (row << shiftY) + dy;
                    if (sy >= height) {
                        continue;
                    }
                    for (var dx = 0; dx < 1 << shiftX; dx++) {
                        var sx = (x << shiftX) + dx;
                        if (sx >= width) {
                            continue;
                        }
                        sumB += cb[sy * width + sx];
                        sumR += cr[sy * width + sx];
                        count++;
                    }
                }
                u[row * chromaStride + x] = (short) Math.clamp(Math.round((128 + 224 * sumB / count) * scale), 0, max);
                v[row * chromaStride + x] = (short) Math.clamp(Math.round((128 + 224 * sumR / count) * scale), 0, max);
            }
        }
    }

    /**
     * Returns the luma weights RGB samples are converted with.
     *
     * @param matrix the matrix of the requested color description
     * @return the matrix's weights, or those of BT.709 for an unspecified matrix
     * @throws MediaException.Unsupported if two luma weights do not define {@code matrix}
     */
    static LumaWeights weights(MatrixCoefficients matrix) {
        if (matrix == MatrixCoefficients.UNSPECIFIED) {
            return LumaWeights.BT709;
        }
        return LumaWeights.of(matrix).orElseThrow(() -> new MediaException.Unsupported(
                "matrix " + matrix.name().toLowerCase(Locale.ROOT),
                "RGB input cannot be converted to Y'CbCr with the " + matrix + " matrix coefficients"));
    }

    /**
     * Fills the padding of a plane by replicating its last column and last row.
     *
     * @param plane the plane to pad, row by row
     * @param stride its row stride, the padded width
     * @param width the width of the picture area
     * @param height the height of the picture area
     * @param rows the number of rows of the plane
     */
    private static void pad(short[] plane, int stride, int width, int height, int rows) {
        if (width < stride) {
            for (var row = 0; row < height; row++) {
                var edge = plane[row * stride + width - 1];
                java.util.Arrays.fill(plane, row * stride + width, (row + 1) * stride, edge);
            }
        }
        for (var row = height; row < rows; row++) {
            System.arraycopy(plane, (height - 1) * stride, plane, row * stride, stride);
        }
    }
}
