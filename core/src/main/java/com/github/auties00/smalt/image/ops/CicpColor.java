package com.github.auties00.smalt.image.ops;

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
import com.github.auties00.smalt.internal.color.YcbcrModel;
import com.github.auties00.smalt.internal.color.YcgcoR;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.function.Supplier;

/**
 * Converts planar pictures, 8-bit or deeper, whose matrix coefficients the fixed-point coefficients of
 * {@link ColorMatrix} do not cover: SMPTE ST 2085, the constant-luminance and chromaticity-derived matrices, ICtCp,
 * IPT-C2, YCgCo-Re and YCgCo-Ro. Every pixel goes through the ITU-T H.273 equations of {@link YcbcrModel} or
 * {@link YcgcoR} in double precision, rounding once.
 *
 * <p>Code values map to normalized signals as H.273 quantizes them for the picture's range and depth. Subsampled
 * chroma is reconstructed bilinearly at the centered positions of the chroma samples
 * ({@link ChromaUpsampling#TRIANGLE}, edges replicated) or replicated ({@link ChromaUpsampling#NEAREST}); a
 * reconstructed YCgCo-R chroma value is rounded
 * to the nearest integer before the integer inverse. Converting to a subsampled layout averages the color differences
 * each pixel converts to over the pixels a chroma sample covers, with equal weights or weighted by alpha; luma is
 * computed per pixel. A monochrome picture is read with zero color differences. Alpha is rescaled exactly between
 * depths. Converting to R'G'B' clamps to the R'G'B' cube; conversions between Y'CbCr descriptions do not.
 */
final class CicpColor {
    /**
     * The approximate number of operations per pixel of a conversion, which weighs its work against
     * {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long WORK = 400;

    /**
     * Prevents instantiation.
     */
    private CicpColor() {
        throw new AssertionError();
    }

    /**
     * Returns whether pictures of a color description convert here rather than through {@link ColorMatrix}.
     *
     * @param color the color description
     * @return {@code true} unless the resolved matrix is the identity, YCgCo or defined by two fixed luma weights
     */
    static boolean handles(ColorInfo color) {
        var matrix = color.matrix().effective();
        return matrix != MatrixCoefficients.IDENTITY && matrix != MatrixCoefficients.YCGCO
                && LumaWeights.of(matrix).isEmpty();
    }

    /**
     * Converts a planar picture to packed R'G'B'.
     *
     * @param picture the {@link YuvPicture} or {@link Yuv16Picture} to convert
     * @param upsampling how to reconstruct subsampled chroma
     * @param shorts the 16-bit destination, or {@code null}
     * @param bytes the 8-bit destination, or {@code null}
     * @param stride the destination row stride in samples
     */
    static void toPacked(Picture picture, ChromaUpsampling upsampling, short[] shorts, byte[] bytes, int stride) {
        var planes = new Planes(picture);
        var reader = new Reader(planes, upsampling);
        var width = planes.width;
        var height = planes.height;
        var max = shorts != null ? 65535 : 255;
        var alphaMax = (1 << planes.depth) - 1;
        var bands = ParallelBands.bands(height, WORK * width * height);
        ParallelBands.run(bands, band -> {
            var rgb = new double[3 * width];
            var scratch = reader.scratch();
            var last = ParallelBands.start(band + 1, bands, height);
            for (var row = ParallelBands.start(band, bands, height); row < last; row++) {
                reader.row(row, rgb, scratch);
                var o = row * stride;
                for (var x = 0; x < width; x++, o += 4) {
                    var red = quantize(rgb[x], max);
                    var green = quantize(rgb[width + x], max);
                    var blue = quantize(rgb[2 * width + x], max);
                    var alpha = planes.hasAlpha ? DeepColor.rescale(planes.alpha(row * planes.alphaStride + x), alphaMax,
                            max) : max;
                    if (shorts != null) {
                        shorts[o] = (short) red;
                        shorts[o + 1] = (short) green;
                        shorts[o + 2] = (short) blue;
                        shorts[o + 3] = (short) alpha;
                    } else {
                        bytes[o] = (byte) red;
                        bytes[o + 1] = (byte) green;
                        bytes[o + 2] = (byte) blue;
                        bytes[o + 3] = (byte) alpha;
                    }
                }
            }
        });
    }

    /**
     * Converts a packed picture to a planar one.
     *
     * @param packed the {@link RgbaPicture} or {@link Rgba16Picture} to convert
     * @param out the {@link YuvPicture} or {@link Yuv16Picture} destination, of the same size, with an alpha plane
     * when {@code packed} reports alpha
     * @param weighted whether to weight averaged chroma by alpha
     */
    static void fromPacked(Picture packed, Picture out, boolean weighted) {
        var width = packed.width();
        var writer = new Writer(new Planes(out), weighted && packed.hasAlpha());
        switch (packed) {
            case Rgba16Picture picture -> {
                var pixels = picture.pixels();
                writer.run(() -> (row, rgb, alpha) -> {
                    var p = row * picture.stride();
                    for (var x = 0; x < width; x++, p += 4) {
                        rgb[x] = (pixels[p] & 0xFFFF) / 65535.0;
                        rgb[width + x] = (pixels[p + 1] & 0xFFFF) / 65535.0;
                        rgb[2 * width + x] = (pixels[p + 2] & 0xFFFF) / 65535.0;
                        alpha[x] = picture.hasAlpha() ? pixels[p + 3] & 0xFFFF : 65535;
                    }
                }, 65535);
            }
            case RgbaPicture picture -> {
                var pixels = picture.pixels();
                writer.run(() -> (row, rgb, alpha) -> {
                    var p = row * picture.stride();
                    for (var x = 0; x < width; x++, p += 4) {
                        rgb[x] = (pixels[p] & 0xFF) / 255.0;
                        rgb[width + x] = (pixels[p + 1] & 0xFF) / 255.0;
                        rgb[2 * width + x] = (pixels[p + 2] & 0xFF) / 255.0;
                        alpha[x] = picture.hasAlpha() ? pixels[p + 3] & 0xFF : 255;
                    }
                }, 255);
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + packed);
        }
    }

    /**
     * Converts a planar picture to another planar layout, color description or depth through unclamped R'G'B'.
     *
     * @param picture the {@link YuvPicture} or {@link Yuv16Picture} to convert
     * @param out the {@link YuvPicture} or {@link Yuv16Picture} destination, of the same size, with an alpha plane
     * when {@code picture} has one
     * @param weighted whether to weight averaged chroma by alpha
     */
    static void convert(Picture picture, Picture out, boolean weighted) {
        var planes = new Planes(picture);
        var reader = new Reader(planes, ChromaUpsampling.TRIANGLE);
        var writer = new Writer(new Planes(out), weighted && planes.hasAlpha);
        var width = planes.width;
        var alphaMax = (1 << planes.depth) - 1;
        writer.run(() -> {
            var scratch = reader.scratch();
            return (row, rgb, alpha) -> {
                reader.row(row, rgb, scratch);
                for (var x = 0; x < width; x++) {
                    alpha[x] = planes.hasAlpha ? planes.alpha(row * planes.alphaStride + x) : alphaMax;
                }
            };
        }, alphaMax);
    }

    /**
     * Converts an 8-bit R'G'B' color to the Y'CbCr samples of a color description and depth.
     *
     * @param color the color description
     * @param depth the depth of the samples, 8 to 16
     * @param rgb the color as {@code 0xRRGGBB}
     * @return the Y, Cb and Cr samples, rounded to nearest and clamped
     */
    static long[] background(ColorInfo color, int depth, int rgb) {
        var codec = new Codec(color, depth);
        var codes = new double[3];
        codec.encode(((rgb >>> 16) & 0xFF) / 255.0, ((rgb >>> 8) & 0xFF) / 255.0, (rgb & 0xFF) / 255.0, codes,
                new int[3]);
        var max = (1 << depth) - 1;
        return new long[]{codec.round(codes[0], max), codec.round(codes[1], max), codec.round(codes[2], max)};
    }

    /**
     * Quantizes a normalized R'G'B' signal to a packed sample.
     *
     * @param signal the signal; NaN gives {@code 0}
     * @param max the largest sample
     * @return the sample, rounded half up and clamped
     */
    private static int quantize(double signal, int max) {
        return (int) Math.round(Math.clamp(signal * max, 0, max));
    }

    /**
     * Maps the code values of one color description and depth to and from normalized R'G'B' signals.
     */
    private static final class Codec {
        private final YcbcrModel model;
        private final MatrixCoefficients matrix;
        private final ColorRange range;
        private final int depth;
        private final int rgbDepth;
        private final double[] scale = new double[3];
        private final double[] offset = new double[3];

        /**
         * Constructs a codec.
         *
         * @param color the color description
         * @param depth the depth of the samples, 8 to 16
         */
        Codec(ColorInfo color, int depth) {
            this.matrix = color.matrix().effective();
            this.range = color.range();
            this.depth = depth;
            if (YcgcoR.applies(matrix)) {
                this.model = null;
                this.rgbDepth = YcgcoR.rgbDepth(matrix, depth);
                offset[1] = offset[2] = 1 << (depth - 1);
            } else {
                this.model = YcbcrModel.of(color);
                this.rgbDepth = 0;
                for (var c = 0; c < 3; c++) {
                    scale[c] = model.scale(c, range, depth);
                    offset[c] = model.offset(c, range, depth);
                }
            }
        }

        /**
         * {@return the code value of a zero color difference}
         */
        double neutral() {
            return offset[1];
        }

        /**
         * Converts code values to normalized R'G'B' signals.
         *
         * @param y the luma code value
         * @param cb the Cb code value, possibly interpolated
         * @param cr the Cr code value, possibly interpolated
         * @param rgb the destination of the signals, at least 3 long
         * @param ints a scratch array, at least 3 long
         */
        void decode(double y, double cb, double cr, double[] rgb, int[] ints) {
            if (model != null) {
                model.toRgb((y - offset[0]) / scale[0], (cb - offset[1]) / scale[1], (cr - offset[2]) / scale[2], rgb);
                return;
            }
            YcgcoR.inverse((int) y, (int) Math.round(cb), (int) Math.round(cr), depth, rgbDepth, ints);
            for (var c = 0; c < 3; c++) {
                rgb[c] = YcgcoR.toSignal(ints[c], range, rgbDepth);
            }
        }

        /**
         * Converts normalized R'G'B' signals to unrounded code values.
         *
         * @param r the red signal
         * @param g the green signal
         * @param b the blue signal
         * @param codes the destination of the Y, Cb and Cr code values, at least 3 long
         * @param ints a scratch array, at least 3 long
         */
        void encode(double r, double g, double b, double[] codes, int[] ints) {
            if (model != null) {
                model.toYcbcr(r, g, b, codes);
                for (var c = 0; c < 3; c++) {
                    codes[c] = codes[c] * scale[c] + offset[c];
                }
                return;
            }
            YcgcoR.forward(YcgcoR.toSample(r, range, rgbDepth), YcgcoR.toSample(g, range, rgbDepth),
                    YcgcoR.toSample(b, range, rgbDepth), depth, ints);
            codes[0] = ints[0];
            codes[1] = ints[1];
            codes[2] = ints[2];
        }

        /**
         * Rounds a code value half up and clamps it.
         *
         * @param code the code value; NaN gives {@code 0}
         * @param max the largest code value
         * @return the sample
         */
        int round(double code, int max) {
            return (int) Math.round(Math.clamp(code, 0, max));
        }
    }

    /**
     * Reads and writes the samples of an 8-bit or deeper planar picture.
     */
    private static final class Planes {
        private final byte[] y8;
        private final byte[] u8;
        private final byte[] v8;
        private final byte[] a8;
        private final short[] y16;
        private final short[] u16;
        private final short[] v16;
        private final short[] a16;
        private final int width;
        private final int height;
        private final int yStride;
        private final int uvStride;
        private final int alphaStride;
        private final int chromaWidth;
        private final int chromaHeight;
        private final int depth;
        private final boolean hasAlpha;
        private final ChromaFormat format;
        private final ColorInfo color;

        /**
         * Constructs the view of a picture.
         *
         * @param picture the {@link YuvPicture} or {@link Yuv16Picture}
         */
        Planes(Picture picture) {
            switch (picture) {
                case YuvPicture yuv -> {
                    y8 = yuv.y();
                    u8 = yuv.u();
                    v8 = yuv.v();
                    a8 = yuv.alpha();
                    y16 = u16 = v16 = a16 = null;
                    yStride = yuv.yStride();
                    uvStride = yuv.uvStride();
                    alphaStride = yuv.alphaStride();
                    chromaWidth = yuv.chromaWidth();
                    chromaHeight = yuv.chromaHeight();
                    depth = 8;
                    format = yuv.chromaFormat();
                    color = yuv.color();
                }
                case Yuv16Picture yuv -> {
                    y16 = yuv.y();
                    u16 = yuv.u();
                    v16 = yuv.v();
                    a16 = yuv.alpha();
                    y8 = u8 = v8 = a8 = null;
                    yStride = yuv.yStride();
                    uvStride = yuv.uvStride();
                    alphaStride = yuv.alphaStride();
                    chromaWidth = yuv.chromaWidth();
                    chromaHeight = yuv.chromaHeight();
                    depth = yuv.bitDepth();
                    format = yuv.chromaFormat();
                    color = yuv.color();
                }
                default -> throw new IllegalArgumentException("not a planar picture: " + picture);
            }
            width = picture.width();
            height = picture.height();
            hasAlpha = a8 != null || a16 != null;
        }

        int y(int index) {
            return y8 != null ? y8[index] & 0xFF : y16[index] & 0xFFFF;
        }

        int u(int index) {
            return u8 != null ? u8[index] & 0xFF : u16[index] & 0xFFFF;
        }

        int v(int index) {
            return v8 != null ? v8[index] & 0xFF : v16[index] & 0xFFFF;
        }

        int alpha(int index) {
            return a8 != null ? a8[index] & 0xFF : a16[index] & 0xFFFF;
        }

        void setY(int index, int value) {
            if (y8 != null) {
                y8[index] = (byte) value;
            } else {
                y16[index] = (short) value;
            }
        }

        void setU(int index, int value) {
            if (u8 != null) {
                u8[index] = (byte) value;
            } else {
                u16[index] = (short) value;
            }
        }

        void setV(int index, int value) {
            if (v8 != null) {
                v8[index] = (byte) value;
            } else {
                v16[index] = (short) value;
            }
        }

        void setAlpha(int index, int value) {
            if (a8 != null) {
                a8[index] = (byte) value;
            } else {
                a16[index] = (short) value;
            }
        }
    }

    /**
     * Converts the rows of a planar picture to unclamped normalized R'G'B', planar: red at {@code [0, width)}, green
     * at {@code [width, 2 * width)}, blue at {@code [2 * width, 3 * width)}. Instances are immutable; the scratch a
     * thread passes is its own.
     */
    private static final class Reader {
        private final Planes planes;
        private final Codec codec;
        private final boolean nearest;
        private final int[] left;
        private final int[] right;
        private final double[] fraction;

        /**
         * Constructs a reader.
         *
         * @param planes the picture to read
         * @param upsampling how to reconstruct subsampled chroma
         */
        Reader(Planes planes, ChromaUpsampling upsampling) {
            this.planes = planes;
            this.codec = new Codec(planes.color, planes.depth);
            this.nearest = upsampling == ChromaUpsampling.NEAREST;
            var width = planes.width;
            this.left = new int[width];
            this.right = new int[width];
            this.fraction = new double[width];
            var format = planes.format;
            if (format.hasChroma()) {
                for (var x = 0; x < width; x++) {
                    if (nearest || format.shiftX() == 0) {
                        left[x] = right[x] = Math.min(x >> format.shiftX(), planes.chromaWidth - 1);
                        continue;
                    }
                    var position = (x + 0.5) / (1 << format.shiftX()) - 0.5;
                    var floor = (int) Math.floor(position);
                    left[x] = Math.clamp(floor, 0, planes.chromaWidth - 1);
                    right[x] = Math.min(planes.chromaWidth - 1, left[x] + 1);
                    fraction[x] = position < 0 ? 0 : position - floor;
                }
            }
        }

        /**
         * Allocates the scratch of one thread: the vertically interpolated Cb and Cr rows and the conversion
         * temporaries.
         *
         * @return the scratch
         */
        Scratch scratch() {
            var length = Math.max(1, planes.chromaWidth);
            return new Scratch(new double[length], new double[length], new double[3], new int[3]);
        }

        /**
         * Converts one row.
         *
         * @param row the index of the row to convert
         * @param rgb the destination, at least {@code 3 * width} long
         * @param scratch the thread's scratch from {@link #scratch()}
         */
        void row(int row, double[] rgb, Scratch scratch) {
            var width = planes.width;
            var lumaRow = row * planes.yStride;
            var format = planes.format;
            var pixel = scratch.pixel();
            var ints = scratch.ints();
            if (!format.hasChroma()) {
                var neutral = codec.neutral();
                for (var x = 0; x < width; x++) {
                    codec.decode(planes.y(lumaRow + x), neutral, neutral, pixel, ints);
                    rgb[x] = pixel[0];
                    rgb[width + x] = pixel[1];
                    rgb[2 * width + x] = pixel[2];
                }
                return;
            }
            var chromaHeight = planes.chromaHeight;
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
            var cb = scratch.cb();
            var cr = scratch.cr();
            var stride = planes.uvStride;
            for (var x = 0; x < planes.chromaWidth; x++) {
                var u0 = planes.u(top * stride + x);
                var u1 = planes.u(bottom * stride + x);
                var v0 = planes.v(top * stride + x);
                var v1 = planes.v(bottom * stride + x);
                cb[x] = u0 + (u1 - u0) * weight;
                cr[x] = v0 + (v1 - v0) * weight;
            }
            for (var x = 0; x < width; x++) {
                var l = left[x];
                var r = right[x];
                var f = fraction[x];
                codec.decode(planes.y(lumaRow + x), cb[l] + (cb[r] - cb[l]) * f, cr[l] + (cr[r] - cr[l]) * f, pixel,
                        ints);
                rgb[x] = pixel[0];
                rgb[width + x] = pixel[1];
                rgb[2 * width + x] = pixel[2];
            }
        }
    }

    /**
     * Holds the scratch arrays of one reading thread.
     *
     * @param cb the vertically interpolated Cb row
     * @param cr the vertically interpolated Cr row
     * @param pixel the R'G'B' signals of one pixel
     * @param ints the integer temporaries of one pixel
     */
    private record Scratch(double[] cb, double[] cr, double[] pixel, int[] ints) {
    }

    /**
     * Supplies one row of unclamped normalized R'G'B' signals and the row's alpha.
     */
    @FunctionalInterface
    private interface RowSource {
        /**
         * Reads one row.
         *
         * @param row the index of the row to read
         * @param rgb the planar destination of red, green and blue, {@code width} each
         * @param alpha the destination of the alpha values, opaque when the source has none
         */
        void read(int row, double[] rgb, int[] alpha);
    }

    /**
     * Writes rows of normalized R'G'B' signals into a planar picture.
     */
    private static final class Writer {
        private final Planes out;
        private final boolean weighted;
        private final Codec codec;

        /**
         * Constructs a writer.
         *
         * @param out the destination picture
         * @param weighted whether to weight averaged chroma by alpha
         */
        Writer(Planes out, boolean weighted) {
            this.out = out;
            this.weighted = weighted;
            this.codec = new Codec(out.color, out.depth);
        }

        /**
         * Converts every row of the source, in bands of chroma rows.
         *
         * @param sources the supplier of the row source of each band, called once per band
         * @param alphaMax the largest alpha value of the source
         */
        void run(Supplier<RowSource> sources, int alphaMax) {
            var width = out.width;
            var height = out.height;
            var format = out.format;
            var shiftY = format.hasChroma() ? format.shiftY() : 0;
            var groups = format.hasChroma() ? out.chromaHeight : height;
            var bands = ParallelBands.bands(groups, WORK * width * height);
            ParallelBands.run(bands, band -> {
                var source = sources.get();
                var rgb = new double[3 * width];
                var alpha = new int[width];
                var chroma = new double[4 * width];
                var alphas = new int[2 * width];
                var codes = new double[3];
                var ints = new int[3];
                var last = ParallelBands.start(band + 1, bands, groups);
                for (var group = ParallelBands.start(band, bands, groups); group < last; group++) {
                    var top = group << shiftY;
                    var rows = shiftY == 1 && top + 1 < height ? 2 : 1;
                    for (var j = 0; j < rows; j++) {
                        source.read(top + j, rgb, alpha);
                        luma(top + j, rgb, alpha, alphaMax, chroma, j * 2 * width, codes, ints);
                        System.arraycopy(alpha, 0, alphas, j * width, width);
                    }
                    if (format.hasChroma()) {
                        chroma(group, chroma, alphas, rows);
                    }
                }
            });
        }

        /**
         * Writes the luma and alpha of one row and keeps the row's color differences.
         *
         * @param row the index of the row to write
         * @param rgb the row's R'G'B' signals
         * @param alpha the row's alpha values
         * @param alphaMax the largest alpha value of the source
         * @param chroma the destination of the row's Cb and Cr code values, interleaved per pixel
         * @param offset the index in {@code chroma} of the row's first pixel
         * @param codes a scratch array of 3 code values
         * @param ints a scratch array of 3 integers
         */
        private void luma(int row, double[] rgb, int[] alpha, int alphaMax, double[] chroma, int offset,
                          double[] codes, int[] ints) {
            var width = out.width;
            var max = (1 << out.depth) - 1;
            var o = row * out.yStride;
            for (var x = 0; x < width; x++) {
                codec.encode(rgb[x], rgb[width + x], rgb[2 * width + x], codes, ints);
                out.setY(o + x, codec.round(codes[0], max));
                chroma[offset + 2 * x] = codes[1];
                chroma[offset + 2 * x + 1] = codes[2];
            }
            if (out.hasAlpha) {
                var ao = row * out.alphaStride;
                for (var x = 0; x < width; x++) {
                    out.setAlpha(ao + x, DeepColor.rescale(alpha[x], alphaMax, max));
                }
            }
        }

        /**
         * Writes one row of chroma from the color differences of the one or two rows it covers.
         *
         * @param chromaRow the chroma row
         * @param chroma the Cb and Cr code values of the covered rows, interleaved per pixel, a row every
         * {@code 2 * width}
         * @param alphas the alpha values of the covered rows, a row every {@code width}
         * @param rows the number of covered rows, 1 or 2
         */
        private void chroma(int chromaRow, double[] chroma, int[] alphas, int rows) {
            var width = out.width;
            var pairX = out.format.shiftX() == 1;
            var max = (1 << out.depth) - 1;
            var o = chromaRow * out.uvStride;
            for (var k = 0; k < out.chromaWidth; k++) {
                var x0 = pairX ? 2 * k : k;
                var columns = pairX && 2 * k + 1 < width ? 2 : 1;
                var cb = 0.0;
                var cr = 0.0;
                var total = 0.0;
                if (weighted) {
                    for (var j = 0; j < rows; j++) {
                        for (var i = 0; i < columns; i++) {
                            var a = alphas[j * width + x0 + i];
                            cb += a * chroma[j * 2 * width + 2 * (x0 + i)];
                            cr += a * chroma[j * 2 * width + 2 * (x0 + i) + 1];
                            total += a;
                        }
                    }
                }
                if (!(total > 0)) {
                    cb = cr = 0;
                    for (var j = 0; j < rows; j++) {
                        for (var i = 0; i < columns; i++) {
                            cb += chroma[j * 2 * width + 2 * (x0 + i)];
                            cr += chroma[j * 2 * width + 2 * (x0 + i) + 1];
                        }
                    }
                    total = rows * columns;
                }
                out.setU(o + k, codec.round(cb / total, max));
                out.setV(o + k, codec.round(cr / total, max));
            }
        }
    }
}
