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
import com.github.auties00.smalt.internal.color.YcgcoR;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;
import java.util.function.Supplier;

/**
 * Converts between planar Y'CbCr of any depth from 8 to 16 bits and packed R'G'B' at full
 * precision, in single-precision floating point with one rounding.
 *
 * <p>A sample {@code v} of depth {@code d} is first mapped to the 8-bit code units the
 * coefficients of {@link ColorMatrix} work in, as ITU-T H.273 quantizes each range: a
 * limited-range sample as {@code v / 2^(d - 8)}, a full-range one as
 * {@code (v - o * 2^(d - 8)) * 255 / (2^d - 1) + o} with {@code o} the component's 8-bit offset
 * ({@code 0} or {@code 128}). Conversions to Y'CbCr apply the inverse map. R'G'B' values are taken
 * in units of {@code 1 / 255} (8-bit samples) or {@code 1 / 65535} (16-bit samples).
 *
 * <p>Subsampled chroma is reconstructed bilinearly at the centered positions of the chroma
 * samples (the triangle filter of {@link ChromaUpsampling#TRIANGLE}, edges replicated) or
 * replicated ({@link ChromaUpsampling#NEAREST}); converting to a subsampled layout averages the
 * R'G'B' values of the pixels each chroma sample covers, with equal weights or weighted by their
 * alpha. Alpha is rescaled exactly between depths, {@code round(a * maxOut / maxIn)}. Converting
 * to R'G'B' clamps to the R'G'B' cube; conversions between Y'CbCr descriptions do not.
 *
 * <p>A conversion from or to a description whose matrix {@link CicpColor#handles} runs in {@link CicpColor}.
 */
final class DeepColor {
    /**
     * The approximate number of operations per pixel of a conversion, which weighs its work
     * against {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long WORK = 24;

    /**
     * Prevents instantiation.
     */
    private DeepColor() {
        throw new AssertionError();
    }

    /**
     * Converts a planar picture to 16-bit packed R'G'B'.
     *
     * @param picture the picture to convert
     * @param upsampling how to reconstruct subsampled chroma
     * @return the packed picture, which reports alpha when the input does
     */
    static Rgba16Picture toRgba16(Yuv16Picture picture, ChromaUpsampling upsampling) {
        OpChecks.checkArray(4L * picture.width() * picture.height());
        var out = Rgba16Picture.allocate(picture.width(), picture.height(), picture.hasAlpha());
        toPacked(picture, upsampling, out.pixels(), null, out.stride());
        return out;
    }

    /**
     * Converts a planar picture to 8-bit packed R'G'B', rounding once.
     *
     * @param picture the picture to convert
     * @param upsampling how to reconstruct subsampled chroma
     * @return the packed picture, which reports alpha when the input does
     */
    static RgbaPicture toRgba(Yuv16Picture picture, ChromaUpsampling upsampling) {
        OpChecks.checkArray(4L * picture.width() * picture.height());
        var out = RgbaPicture.allocate(picture.width(), picture.height(), picture.hasAlpha());
        toPacked(picture, upsampling, null, out.pixels(), out.stride());
        return out;
    }

    /**
     * Converts a planar picture to packed R'G'B' in bands of rows.
     *
     * @param picture the picture to convert
     * @param upsampling how to reconstruct subsampled chroma
     * @param shorts the 16-bit destination, or {@code null}
     * @param bytes the 8-bit destination, or {@code null}
     * @param stride the destination row stride in samples
     */
    private static void toPacked(Yuv16Picture picture, ChromaUpsampling upsampling, short[] shorts, byte[] bytes,
                                 int stride) {
        if (CicpColor.handles(picture.color())) {
            CicpColor.toPacked(picture, upsampling, shorts, bytes, stride);
            return;
        }
        var width = picture.width();
        var height = picture.height();
        var reader = new YuvReader(picture, upsampling);
        var max = shorts != null ? 65535 : 255;
        var scale = max / 255f;
        var alphaMax = (1 << picture.bitDepth()) - 1;
        var bands = ParallelBands.bands(height, WORK * width * height);
        ParallelBands.run(bands, band -> {
            var rgb = new float[3 * width];
            var scratch = reader.scratch();
            var last = ParallelBands.start(band + 1, bands, height);
            for (var row = ParallelBands.start(band, bands, height); row < last; row++) {
                reader.row(row, rgb, scratch);
                var o = row * stride;
                var alphaRow = row * picture.alphaStride();
                for (var x = 0; x < width; x++, o += 4) {
                    var red = round(rgb[x] * scale, max);
                    var green = round(rgb[width + x] * scale, max);
                    var blue = round(rgb[2 * width + x] * scale, max);
                    var alpha = picture.hasAlpha() ? rescale(picture.alpha()[alphaRow + x] & 0xFFFF, alphaMax, max) : max;
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
     * Converts a packed picture, 8 or 16-bit, to planar Y'CbCr.
     *
     * @param packed the {@link RgbaPicture} or {@link Rgba16Picture}
     * @param format the chroma layout of the result
     * @param color the color description of the result
     * @param bitDepth the depth of the result, 8 to 16
     * @param weighted whether to weight chroma by alpha
     * @return the planar picture, with an alpha plane when the input reports alpha
     */
    static Yuv16Picture fromPacked(Picture packed, ChromaFormat format, ColorInfo color, int bitDepth, boolean weighted) {
        var width = packed.width();
        var height = packed.height();
        OpChecks.checkArray((long) width * height);
        var out = Yuv16Picture.allocate(width, height, format, bitDepth, packed.hasAlpha(), color);
        if (CicpColor.handles(color)) {
            CicpColor.fromPacked(packed, out, weighted);
            return out;
        }
        var writer = new YuvWriter(out, weighted && packed.hasAlpha());
        switch (packed) {
            case Rgba16Picture picture -> {
                var unit = 255f / 65535;
                var pixels = picture.pixels();
                writer.run(() -> (row, rgb, alpha) -> {
                    var p = row * picture.stride();
                    for (var x = 0; x < width; x++, p += 4) {
                        rgb[x] = (pixels[p] & 0xFFFF) * unit;
                        rgb[width + x] = (pixels[p + 1] & 0xFFFF) * unit;
                        rgb[2 * width + x] = (pixels[p + 2] & 0xFFFF) * unit;
                        alpha[x] = picture.hasAlpha() ? pixels[p + 3] & 0xFFFF : 65535;
                    }
                }, 65535);
            }
            case RgbaPicture picture -> {
                var pixels = picture.pixels();
                writer.run(() -> (row, rgb, alpha) -> {
                    var p = row * picture.stride();
                    for (var x = 0; x < width; x++, p += 4) {
                        rgb[x] = pixels[p] & 0xFF;
                        rgb[width + x] = pixels[p + 1] & 0xFF;
                        rgb[2 * width + x] = pixels[p + 2] & 0xFF;
                        alpha[x] = picture.hasAlpha() ? pixels[p + 3] & 0xFF : 255;
                    }
                }, 255);
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + packed);
        }
        return out;
    }

    /**
     * Converts a planar picture to another layout, color description or depth.
     *
     * <p>A limited-range picture that only changes depth is converted with
     * {@link HighDepth#toBitDepth(Yuv16Picture, int)}, whose shifts are the limited-range
     * quantization itself, except with the YCgCo-R matrices, whose R'G'B' depth follows the
     * depth. A conversion that involves a matrix {@link CicpColor#handles} runs in
     * {@link CicpColor#convert}. Between other descriptions with the same matrix (other than the
     * identity matrix together with a monochrome layout), the samples stay in Y'CbCr: luma and
     * alpha are remapped sample by sample, and chroma is resampled per axis (halved by averaging
     * pairs, weighted by alpha when asked; doubled with the triangle filter) and remapped, rounded
     * once. Otherwise every pixel is converted to unclamped R'G'B' with its reconstructed chroma
     * and back.
     *
     * @param picture the picture to convert
     * @param format the chroma layout of the result
     * @param color the color description of the result
     * @param bitDepth the depth of the result, 8 to 16
     * @param weighted whether to weight averaged chroma by alpha
     * @return the converted picture, or the input when nothing changes
     */
    static Yuv16Picture convert(Yuv16Picture picture, ChromaFormat format, ColorInfo color, int bitDepth,
                                boolean weighted) {
        if (picture.chromaFormat() == format && picture.color().equals(color)) {
            if (picture.bitDepth() == bitDepth) {
                return picture;
            }
            if (color.range() != ColorRange.FULL && !YcgcoR.applies(color.matrix())) {
                return HighDepth.toBitDepth(picture, bitDepth);
            }
        }
        if (CicpColor.handles(picture.color()) || CicpColor.handles(color)) {
            OpChecks.checkArray((long) picture.width() * picture.height());
            var out = Yuv16Picture.allocate(picture.width(), picture.height(), format, bitDepth, picture.hasAlpha(),
                    color);
            CicpColor.convert(picture, out, weighted);
            return out;
        }
        var source = ColorMatrix.of(picture.color());
        var target = ColorMatrix.of(color);
        var identityInvolved = source.matrix == MatrixCoefficients.IDENTITY || target.matrix == MatrixCoefficients.IDENTITY;
        var monochromeInvolved = !picture.chromaFormat().hasChroma() || !format.hasChroma();
        var width = picture.width();
        var height = picture.height();
        OpChecks.checkArray((long) width * height);
        var out = Yuv16Picture.allocate(width, height, format, bitDepth, picture.hasAlpha(), color);
        if (source.matrix == target.matrix && !(identityInvolved && monochromeInvolved)) {
            planes(picture, out, source, target, weighted);
            return out;
        }
        var reader = new YuvReader(picture, ChromaUpsampling.TRIANGLE);
        var writer = new YuvWriter(out, weighted && picture.hasAlpha());
        var alphaMax = (1 << picture.bitDepth()) - 1;
        writer.run(() -> {
            var scratch = reader.scratch();
            return (row, rgb, alpha) -> {
                reader.row(row, rgb, scratch);
                for (var x = 0; x < width; x++) {
                    alpha[x] = picture.hasAlpha() ? picture.alpha()[row * picture.alphaStride() + x] & 0xFFFF : alphaMax;
                }
            };
        }, alphaMax);
        return out;
    }

    /**
     * Converts the planes of a picture to a description with the same matrix.
     *
     * @param in the source picture
     * @param out the destination picture
     * @param source the coefficients of the source color
     * @param target the coefficients of the target color
     * @param weighted whether to weight halved chroma by alpha
     */
    private static void planes(Yuv16Picture in, Yuv16Picture out, ColorMatrix source, ColorMatrix target,
                               boolean weighted) {
        var width = in.width();
        var height = in.height();
        var from = Quantization.of(source, in.bitDepth());
        var to = Quantization.of(target, out.bitDepth());
        var outMax = (1 << out.bitDepth()) - 1;
        var lumaRatio = (float) (target.scales[0] / source.scales[0]);
        for (var row = 0; row < height; row++) {
            for (var x = 0; x < width; x++) {
                var u = from.decode(0, in.y()[row * in.yStride() + x] & 0xFFFF);
                out.y()[row * out.yStride() + x] = (short) to.encode(0, (u - source.offsets[0]) * lumaRatio
                        + target.offsets[0], outMax);
            }
        }
        if (in.hasAlpha()) {
            var inMax = (1 << in.bitDepth()) - 1;
            for (var row = 0; row < height; row++) {
                for (var x = 0; x < width; x++) {
                    out.alpha()[row * out.alphaStride() + x] = (short) rescale(in.alpha()[row * in.alphaStride() + x]
                            & 0xFFFF, inMax, outMax);
                }
            }
        }
        var format = out.chromaFormat();
        if (!format.hasChroma()) {
            return;
        }
        if (!in.chromaFormat().hasChroma()) {
            for (var c = 1; c < 3; c++) {
                var plane = c == 1 ? out.u() : out.v();
                Arrays.fill(plane, (short) to.encode(c, target.offsets[c], outMax));
            }
            return;
        }
        var directionX = format.shiftX() - in.chromaFormat().shiftX();
        var directionY = format.shiftY() - in.chromaFormat().shiftY();
        float[] coverage = null;
        if (weighted && in.hasAlpha() && (directionX > 0 || directionY > 0) && directionX >= 0 && directionY >= 0) {
            coverage = coverage(in);
        }
        var sourceWidth = in.chromaWidth();
        var sourceHeight = in.chromaHeight();
        var xs = new int[2];
        var ws = new int[2];
        var ys = new int[2];
        var hs = new int[2];
        for (var c = 1; c < 3; c++) {
            var src = c == 1 ? in.u() : in.v();
            var dst = c == 1 ? out.u() : out.v();
            var ratio = (float) (target.scales[c] / source.scales[c]);
            for (var y = 0; y < out.chromaHeight(); y++) {
                var rows = PlaneConversion.taps(directionY, y, sourceHeight, ys, hs);
                for (var x = 0; x < out.chromaWidth(); x++) {
                    var columns = PlaneConversion.taps(directionX, x, sourceWidth, xs, ws);
                    var sum = 0f;
                    var total = 0f;
                    if (coverage != null) {
                        for (var j = 0; j < rows; j++) {
                            for (var i = 0; i < columns; i++) {
                                var weight = coverage[ys[j] * sourceWidth + xs[i]];
                                sum += weight * from.decode(c, src[ys[j] * in.uvStride() + xs[i]] & 0xFFFF);
                                total += weight;
                            }
                        }
                    }
                    if (!(total > 0)) {
                        sum = 0;
                        total = 0;
                        for (var j = 0; j < rows; j++) {
                            for (var i = 0; i < columns; i++) {
                                var weight = hs[j] * ws[i];
                                sum += weight * from.decode(c, src[ys[j] * in.uvStride() + xs[i]] & 0xFFFF);
                                total += weight;
                            }
                        }
                    }
                    var u = (sum / total - source.offsets[c]) * ratio + target.offsets[c];
                    dst[y * out.uvStride() + x] = (short) to.encode(c, u, outMax);
                }
            }
        }
    }

    /**
     * Sums the alpha of the pixels each chroma sample of a picture covers.
     *
     * @param picture the picture, which has an alpha plane
     * @return the coverage of every chroma sample, row by row with the chroma width as stride
     */
    private static float[] coverage(Yuv16Picture picture) {
        var format = picture.chromaFormat();
        var chromaWidth = picture.chromaWidth();
        var coverage = new float[chromaWidth * picture.chromaHeight()];
        for (var y = 0; y < picture.height(); y++) {
            var row = (y >> format.shiftY()) * chromaWidth;
            for (var x = 0; x < picture.width(); x++) {
                coverage[row + (x >> format.shiftX())] += picture.alpha()[y * picture.alphaStride() + x] & 0xFFFF;
            }
        }
        return coverage;
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
        if (CicpColor.handles(color)) {
            return CicpColor.background(color, depth, rgb);
        }
        var matrix = ColorMatrix.of(color);
        var quantization = Quantization.of(matrix, depth);
        var max = (1 << depth) - 1;
        var red = (rgb >>> 16) & 0xFF;
        var green = (rgb >>> 8) & 0xFF;
        var blue = rgb & 0xFF;
        var samples = new long[3];
        for (var c = 0; c < 3; c++) {
            var row = matrix.forward[c];
            var value = row[0] * red + row[1] * green + row[2] * blue + matrix.offsets[c];
            samples[c] = quantization.encode(c, (float) value, max);
        }
        return samples;
    }

    /**
     * Rounds a value to the nearest integer, halves up, and clamps it to {@code 0..max}.
     *
     * @param value the value; NaN gives {@code 0}
     * @param max the largest result
     * @return the rounded value
     */
    static int round(float value, int max) {
        return Math.clamp(Math.round(value), 0, max);
    }

    /**
     * Rescales a full-range sample between two maxima, rounding to nearest.
     *
     * @param value the sample, {@code 0} to {@code maxIn}
     * @param maxIn the largest input sample
     * @param maxOut the largest output sample
     * @return the rescaled sample
     */
    static int rescale(int value, int maxIn, int maxOut) {
        return maxIn == maxOut ? value : (int) (((long) value * maxOut + maxIn / 2) / maxIn);
    }

    /**
     * Maps the code values of one depth and range to and from the 8-bit code units of a
     * {@link ColorMatrix}, per component: {@code v = u * scale + bias}.
     *
     * @param scale the factor of each component
     * @param bias the bias of each component
     */
    private record Quantization(float[] scale, float[] bias) {
        /**
         * Returns the map of a depth for the range and offsets of a matrix.
         *
         * @param matrix the coefficients, whose range and offsets apply
         * @param depth the depth of the samples, 8 to 16
         * @return the map
         */
        static Quantization of(ColorMatrix matrix, int depth) {
            var scale = new float[3];
            var bias = new float[3];
            for (var c = 0; c < 3; c++) {
                if (matrix.range == ColorRange.FULL) {
                    scale[c] = (float) (((1 << depth) - 1) / 255.0);
                    bias[c] = (float) ((matrix.offsets[c] << (depth - 8)) - matrix.offsets[c] * (((1 << depth) - 1) / 255.0));
                } else {
                    scale[c] = 1 << (depth - 8);
                }
            }
            return new Quantization(scale, bias);
        }

        /**
         * Maps a code value to 8-bit units.
         *
         * @param component {@code 0} for luma, {@code 1} for Cb, {@code 2} for Cr
         * @param value the code value
         * @return the value in 8-bit units
         */
        float decode(int component, int value) {
            return (value - bias[component]) / scale[component];
        }

        /**
         * Maps a value in 8-bit units to the nearest code value.
         *
         * @param component {@code 0} for luma, {@code 1} for Cb, {@code 2} for Cr
         * @param value the value in 8-bit units
         * @param max the largest code value
         * @return the code value, clamped
         */
        int encode(int component, float value, int max) {
            return round(value * scale[component] + bias[component], max);
        }
    }

    /**
     * Converts the rows of a planar picture to unclamped R'G'B' in 8-bit code units, planar: red
     * at {@code [0, width)}, green at {@code [width, 2 * width)}, blue at
     * {@code [2 * width, 3 * width)}. Instances are immutable; the scratch arrays a thread passes
     * are its own.
     */
    private static final class YuvReader {
        private final Yuv16Picture picture;
        private final Quantization quantization;
        private final ColorMatrix matrix;
        private final float[] inverse;
        private final float grayScale;
        private final boolean nearest;
        private final int[] left;
        private final int[] right;
        private final float[] fraction;

        /**
         * Constructs a reader.
         *
         * @param picture the picture to read
         * @param upsampling how to reconstruct subsampled chroma
         */
        YuvReader(Yuv16Picture picture, ChromaUpsampling upsampling) {
            this.picture = picture;
            this.matrix = ColorMatrix.of(picture.color());
            this.quantization = Quantization.of(matrix, picture.bitDepth());
            this.inverse = new float[9];
            for (var r = 0; r < 3; r++) {
                for (var c = 0; c < 3; c++) {
                    inverse[3 * r + c] = (float) matrix.inverse[r][c];
                }
            }
            this.grayScale = (float) (255 / matrix.scales[0]);
            var format = picture.chromaFormat();
            this.nearest = upsampling == ChromaUpsampling.NEAREST;
            var width = picture.width();
            this.left = new int[width];
            this.right = new int[width];
            this.fraction = new float[width];
            if (format.hasChroma()) {
                var chromaWidth = picture.chromaWidth();
                for (var x = 0; x < width; x++) {
                    if (nearest || format.shiftX() == 0) {
                        left[x] = right[x] = Math.min(x >> format.shiftX(), chromaWidth - 1);
                        continue;
                    }
                    var position = (x + 0.5) / (1 << format.shiftX()) - 0.5;
                    var floor = (int) Math.floor(position);
                    left[x] = Math.clamp(floor, 0, chromaWidth - 1);
                    right[x] = Math.min(chromaWidth - 1, left[x] + 1);
                    fraction[x] = position < 0 ? 0 : (float) (position - floor);
                }
            }
        }

        /**
         * Allocates the scratch rows of one thread: the vertically interpolated Cb and Cr rows.
         *
         * @return the scratch rows
         */
        float[][] scratch() {
            var length = Math.max(1, picture.chromaWidth());
            return new float[][]{new float[length], new float[length]};
        }

        /**
         * Converts one row.
         *
         * @param row the index of the row to convert
         * @param rgb the destination, at least {@code 3 * width} long
         * @param scratch the thread's scratch rows from {@link #scratch()}
         */
        void row(int row, float[] rgb, float[][] scratch) {
            var width = picture.width();
            var luma = picture.y();
            var lumaRow = row * picture.yStride();
            var format = picture.chromaFormat();
            var offsetY = matrix.offsets[0];
            if (!format.hasChroma()) {
                for (var x = 0; x < width; x++) {
                    var gray = (quantization.decode(0, luma[lumaRow + x] & 0xFFFF) - offsetY) * grayScale;
                    rgb[x] = gray;
                    rgb[width + x] = gray;
                    rgb[2 * width + x] = gray;
                }
                return;
            }
            var chromaHeight = picture.chromaHeight();
            int top;
            int bottom;
            float weight;
            if (nearest || format.shiftY() == 0) {
                top = bottom = Math.min(row >> format.shiftY(), chromaHeight - 1);
                weight = 0;
            } else {
                var position = (row + 0.5) / (1 << format.shiftY()) - 0.5;
                var floor = (int) Math.floor(position);
                top = Math.clamp(floor, 0, chromaHeight - 1);
                bottom = Math.min(chromaHeight - 1, top + 1);
                weight = position < 0 ? 0 : (float) (position - floor);
            }
            var cb = scratch[0];
            var cr = scratch[1];
            var stride = picture.uvStride();
            var u = picture.u();
            var v = picture.v();
            var offsetU = matrix.offsets[1];
            var offsetV = matrix.offsets[2];
            for (var x = 0; x < picture.chromaWidth(); x++) {
                var u0 = quantization.decode(1, u[top * stride + x] & 0xFFFF);
                var u1 = quantization.decode(1, u[bottom * stride + x] & 0xFFFF);
                var v0 = quantization.decode(2, v[top * stride + x] & 0xFFFF);
                var v1 = quantization.decode(2, v[bottom * stride + x] & 0xFFFF);
                cb[x] = u0 + (u1 - u0) * weight - offsetU;
                cr[x] = v0 + (v1 - v0) * weight - offsetV;
            }
            var m = inverse;
            for (var x = 0; x < width; x++) {
                var y = quantization.decode(0, luma[lumaRow + x] & 0xFFFF) - offsetY;
                var l = left[x];
                var r = right[x];
                var f = fraction[x];
                var blue = cb[l] + (cb[r] - cb[l]) * f;
                var red = cr[l] + (cr[r] - cr[l]) * f;
                rgb[x] = m[0] * y + m[1] * blue + m[2] * red;
                rgb[width + x] = m[3] * y + m[4] * blue + m[5] * red;
                rgb[2 * width + x] = m[6] * y + m[7] * blue + m[8] * red;
            }
        }
    }

    /**
     * Supplies one row of unclamped R'G'B' values in 8-bit code units and the row's alpha.
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
        void read(int row, float[] rgb, float[] alpha);
    }

    /**
     * Writes rows of R'G'B' values into a planar picture.
     */
    private static final class YuvWriter {
        private final Yuv16Picture out;
        private final boolean weighted;
        private final ColorMatrix matrix;
        private final Quantization quantization;
        private final float[] forward;

        /**
         * Constructs a writer.
         *
         * @param out the destination picture
         * @param weighted whether to weight averaged chroma by alpha
         */
        YuvWriter(Yuv16Picture out, boolean weighted) {
            this.out = out;
            this.weighted = weighted;
            this.matrix = ColorMatrix.of(out.color());
            this.quantization = Quantization.of(matrix, out.bitDepth());
            this.forward = new float[9];
            for (var r = 0; r < 3; r++) {
                for (var c = 0; c < 3; c++) {
                    forward[3 * r + c] = (float) matrix.forward[r][c];
                }
            }
        }

        /**
         * Converts every row of the source, in bands of chroma rows.
         *
         * @param sources the supplier of the row source of each band, called once per band
         * @param alphaMax the largest alpha value of the source
         */
        void run(Supplier<RowSource> sources, int alphaMax) {
            var width = out.width();
            var height = out.height();
            var format = out.chromaFormat();
            var hasAlpha = out.hasAlpha();
            var shiftY = format.hasChroma() ? format.shiftY() : 0;
            var groups = format.hasChroma() ? out.chromaHeight() : height;
            var bands = ParallelBands.bands(groups, WORK * width * height);
            ParallelBands.run(bands, band -> {
                var source = sources.get();
                var rgbTop = new float[3 * width];
                var rgbBottom = new float[3 * width];
                var alphaTop = new float[width];
                var alphaBottom = new float[width];
                var last = ParallelBands.start(band + 1, bands, groups);
                for (var group = ParallelBands.start(band, bands, groups); group < last; group++) {
                    var top = group << shiftY;
                    source.read(top, rgbTop, alphaTop);
                    luma(top, rgbTop, alphaTop, hasAlpha, alphaMax);
                    var bottom = top;
                    if (shiftY == 1 && top + 1 < height) {
                        bottom = top + 1;
                        source.read(bottom, rgbBottom, alphaBottom);
                        luma(bottom, rgbBottom, alphaBottom, hasAlpha, alphaMax);
                    }
                    if (format.hasChroma()) {
                        chroma(group, rgbTop, alphaTop, bottom == top ? rgbTop : rgbBottom,
                                bottom == top ? alphaTop : alphaBottom);
                    }
                }
            });
        }

        /**
         * Writes the luma and alpha of one row.
         *
         * @param row the index of the row to write
         * @param rgb the row's R'G'B' values
         * @param alpha the row's alpha values
         * @param hasAlpha whether to write alpha
         * @param alphaMax the largest alpha value of the source
         */
        private void luma(int row, float[] rgb, float[] alpha, boolean hasAlpha, int alphaMax) {
            var width = out.width();
            var max = (1 << out.bitDepth()) - 1;
            var f = forward;
            var offset = matrix.offsets[0];
            var y = out.y();
            var o = row * out.yStride();
            for (var x = 0; x < width; x++) {
                var value = f[0] * rgb[x] + f[1] * rgb[width + x] + f[2] * rgb[2 * width + x] + offset;
                y[o + x] = (short) quantization.encode(0, value, max);
            }
            if (hasAlpha) {
                var a = out.alpha();
                var ao = row * out.alphaStride();
                for (var x = 0; x < width; x++) {
                    a[ao + x] = (short) rescale((int) alpha[x], alphaMax, max);
                }
            }
        }

        /**
         * Writes one row of chroma from the one or two rows it covers.
         *
         * @param chromaRow the chroma row
         * @param rgbTop the upper row's R'G'B' values
         * @param alphaTop the upper row's alpha values
         * @param rgbBottom the lower row's R'G'B' values, the upper row's when the chroma row covers one row
         * @param alphaBottom the lower row's alpha values
         */
        private void chroma(int chromaRow, float[] rgbTop, float[] alphaTop, float[] rgbBottom, float[] alphaBottom) {
            var width = out.width();
            var format = out.chromaFormat();
            var pairX = format.shiftX() == 1;
            var max = (1 << out.bitDepth()) - 1;
            var f = forward;
            var u = out.u();
            var v = out.v();
            var o = chromaRow * out.uvStride();
            var rowCount = rgbBottom == rgbTop ? 1 : 2;
            for (var k = 0; k < out.chromaWidth(); k++) {
                var x0 = pairX ? 2 * k : k;
                var columnCount = pairX && 2 * k + 1 < width ? 2 : 1;
                var red = 0f;
                var green = 0f;
                var blue = 0f;
                var total = 0f;
                if (weighted) {
                    for (var j = 0; j < rowCount; j++) {
                        var rgb = j == 0 ? rgbTop : rgbBottom;
                        var alpha = j == 0 ? alphaTop : alphaBottom;
                        for (var i = 0; i < columnCount; i++) {
                            var a = alpha[x0 + i];
                            red += a * rgb[x0 + i];
                            green += a * rgb[width + x0 + i];
                            blue += a * rgb[2 * width + x0 + i];
                            total += a;
                        }
                    }
                }
                if (!(total > 0)) {
                    red = green = blue = 0;
                    for (var j = 0; j < rowCount; j++) {
                        var rgb = j == 0 ? rgbTop : rgbBottom;
                        for (var i = 0; i < columnCount; i++) {
                            red += rgb[x0 + i];
                            green += rgb[width + x0 + i];
                            blue += rgb[2 * width + x0 + i];
                        }
                    }
                    total = rowCount * columnCount;
                }
                red /= total;
                green /= total;
                blue /= total;
                var cb = f[3] * red + f[4] * green + f[5] * blue + matrix.offsets[1];
                var cr = f[6] * red + f[7] * green + f[8] * blue + matrix.offsets[2];
                u[o + k] = (short) quantization.encode(1, cb, max);
                v[o + k] = (short) quantization.encode(2, cr, max);
            }
        }
    }
}
