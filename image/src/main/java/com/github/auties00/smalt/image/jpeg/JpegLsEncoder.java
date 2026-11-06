package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.internal.JpegEncoding;
import com.github.auties00.smalt.image.jpeg.internal.JpegLsWriter;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.util.Objects;

/**
 * Encodes pictures as JPEG-LS files (ITU-T T.87 | ISO/IEC 14495-1), lossless or near-lossless, which
 * {@link JpegDecoder} decodes.
 *
 * <p>The picture is coded as:
 * <ul>
 *   <li>one gray component, for a monochrome {@link YuvPicture} or
 *   {@link com.github.auties00.smalt.image.Yuv16Picture Yuv16Picture};</li>
 *   <li>Y'CbCr components in their stored subsampling (4:2:0 as luma sampling factors of 2x2, 4:2:2 as 2x1),
 *   with a SPIFF header declaring full-range BT.601 Y'CbCr, for a planar picture with
 *   {@link com.github.auties00.smalt.image.ColorInfo#JPEG}-compatible samples (BT.601 matrix, full
 *   range);</li>
 *   <li>one gray component, or red, green and blue components, for a {@link Channel8Picture} or
 *   {@link Channel16Picture} of the {@link PixelLayout#GRAY} or {@link PixelLayout#RGB} layout;</li>
 *   <li>red, green and blue components for an {@link RgbaPicture} or
 *   {@link com.github.auties00.smalt.image.Rgba16Picture Rgba16Picture}, whose alpha is ignored, and for every
 *   other picture, converted first with {@link ImageOps#toRgba(Picture)}, or {@link ImageOps#toRgba16(Picture)}
 *   when it has more than 8 bits.</li>
 * </ul>
 * The automatic precision is the bit depth of the picture coded. The output is {@code SOI}, the SPIFF header when
 * written, the {@code APP8 "mrfx"} segment of a color transform, {@code SOF55}, an {@code LSE} segment for
 * non-default preset parameters (and one with the dimensions of a picture wider or taller than 65535), a
 * {@code DRI} segment when restart intervals are used, the scans and {@code EOI}. Without restart intervals the
 * file is byte-identical to CharLS's for the same samples and settings (component identifiers from 1, the SPIFF
 * header with a 1:1 aspect ratio). The output does not depend on the number of threads.
 *
 * <p>This class is thread-safe.
 */
public final class JpegLsEncoder {
    /**
     * The SPIFF color space of gray samples.
     */
    private static final int SPIFF_GRAY = 8;

    /**
     * The SPIFF color space of full-range BT.601 Y'CbCr samples.
     */
    private static final int SPIFF_YCBCR = 3;

    /**
     * The SPIFF color space of RGB samples.
     */
    private static final int SPIFF_RGB = 10;

    /**
     * Prevents instantiation.
     */
    private JpegLsEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture losslessly with the {@link JpegLsEncoderOptions#defaults()}.
     *
     * @param picture the picture; its alpha channel, if any, is ignored
     * @return the JPEG-LS file
     * @see #encode(Picture, JpegLsEncoderOptions)
     */
    public static byte[] encode(Picture picture) {
        return encode(picture, JpegLsEncoderOptions.defaults());
    }

    /**
     * Encodes a picture and writes the JPEG-LS file to a sink, from its position.
     *
     * <p>The bytes written are those {@link #encode(Picture, JpegLsEncoderOptions)} returns.
     *
     * @param picture the picture; its alpha channel, if any, is ignored
     * @param options the settings
     * @param sink the output; not closed
     * @throws IllegalArgumentException if the settings do not fit the picture as
     * {@link #encode(Picture, JpegLsEncoderOptions)} describes
     * @throws com.github.auties00.smalt.MediaException.Io if writing the sink fails
     */
    public static void encode(Picture picture, JpegLsEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(sink, "sink");
        sink.write(encode(picture, options));
    }

    /**
     * Encodes a picture.
     *
     * @param picture the picture; its alpha channel, if any, is ignored, and its samples are coded as the class
     * documentation describes
     * @param options the settings
     * @return the JPEG-LS file
     * @throws IllegalArgumentException if the settings do not fit the picture: a near-lossless bound above half
     * the largest sample value, preset parameters out of the ranges of
     * {@link JpegLsEncoderOptions.PresetParameters}, sample interleaving of subsampled components, or a color
     * transform of samples that are not RGB at 8 or 16 bits coded losslessly in interleaved scans
     */
    public static byte[] encode(Picture picture, JpegLsEncoderOptions options) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            return JpegLsWriter.encode(stream(picture, options));
        }
    }

    /**
     * Converts a picture to the planar samples and settings of a stream.
     *
     * @param picture the picture to code
     * @param options the settings
     * @return the stream
     * @throws IllegalArgumentException if the settings do not fit the picture
     */
    private static JpegLsWriter.Stream stream(Picture picture, JpegLsEncoderOptions options) {
        int[][] planes;
        int[] horizontal;
        int[] vertical;
        int depth;
        int spiff;
        switch (picture) {
            case YuvPicture yuv when yuv.chromaFormat() == ChromaFormat.MONOCHROME -> {
                planes = new int[][]{plane(yuv.y(), yuv.yStride(), yuv.width(), yuv.height())};
                horizontal = new int[]{1};
                vertical = new int[]{1};
                depth = 8;
                spiff = SPIFF_GRAY;
            }
            case Yuv16Picture yuv when yuv.chromaFormat() == ChromaFormat.MONOCHROME -> {
                planes = new int[][]{plane(yuv.y(), yuv.yStride(), yuv.width(), yuv.height())};
                horizontal = new int[]{1};
                vertical = new int[]{1};
                depth = yuv.bitDepth();
                spiff = SPIFF_GRAY;
            }
            case YuvPicture yuv when JpegEncoding.isJpegYcc(yuv) -> {
                var format = yuv.chromaFormat();
                planes = new int[][]{plane(yuv.y(), yuv.yStride(), yuv.width(), yuv.height()),
                        plane(yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight()),
                        plane(yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight())};
                horizontal = new int[]{1 << format.shiftX(), 1, 1};
                vertical = new int[]{1 << format.shiftY(), 1, 1};
                depth = 8;
                spiff = SPIFF_YCBCR;
            }
            case Yuv16Picture yuv when JpegEncoding.isJpegYcc(yuv) -> {
                var format = yuv.chromaFormat();
                planes = new int[][]{plane(yuv.y(), yuv.yStride(), yuv.width(), yuv.height()),
                        plane(yuv.u(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight()),
                        plane(yuv.v(), yuv.uvStride(), yuv.chromaWidth(), yuv.chromaHeight())};
                horizontal = new int[]{1 << format.shiftX(), 1, 1};
                vertical = new int[]{1 << format.shiftY(), 1, 1};
                depth = yuv.bitDepth();
                spiff = SPIFF_YCBCR;
            }
            case RgbaPicture rgba -> {
                planes = rgb(rgba.pixels(), rgba.stride(), rgba.width(), rgba.height());
                horizontal = new int[]{1, 1, 1};
                vertical = new int[]{1, 1, 1};
                depth = 8;
                spiff = SPIFF_RGB;
            }
            case Rgba16Picture rgba -> {
                planes = rgb(rgba.pixels(), rgba.stride(), rgba.width(), rgba.height());
                horizontal = new int[]{1, 1, 1};
                vertical = new int[]{1, 1, 1};
                depth = 16;
                spiff = SPIFF_RGB;
            }
            case Channel8Picture channels when channels.layout().equals(PixelLayout.GRAY)
                    || channels.layout().equals(PixelLayout.RGB) -> {
                planes = channels(channels.pixels(), channels.stride(), channels.width(), channels.height(),
                        channels.channelCount());
                horizontal = ones(planes.length);
                vertical = ones(planes.length);
                depth = 8;
                spiff = planes.length == 1 ? SPIFF_GRAY : SPIFF_RGB;
            }
            case Channel16Picture channels when channels.layout().equals(PixelLayout.GRAY)
                    || channels.layout().equals(PixelLayout.RGB) -> {
                planes = channels(channels.pixels(), channels.stride(), channels.width(), channels.height(),
                        channels.channelCount());
                horizontal = ones(planes.length);
                vertical = ones(planes.length);
                depth = 16;
                spiff = planes.length == 1 ? SPIFF_GRAY : SPIFF_RGB;
            }
            default -> {
                return stream(picture.bitDepth() > 8 ? ImageOps.toRgba16(picture) : ImageOps.toRgba(picture), options);
            }
        }
        var precision = options.precision().orElse(depth);
        if (precision != depth) {
            rescale(planes, depth, precision);
        }
        var maxval = (1 << precision) - 1;
        var near = options.nearLossless();
        if (near > maxval / 2) {
            throw new IllegalArgumentException("near-lossless bound " + near + " exceeds half the largest sample value "
                    + maxval);
        }
        var count = planes.length;
        var subsampled = horizontal[0] != 1 || vertical[0] != 1;
        var interleave = options.interleave().orElse(count == 1 ? JpegLsEncoderOptions.Interleave.NONE
                : JpegLsEncoderOptions.Interleave.LINE);
        if (count == 1) {
            interleave = JpegLsEncoderOptions.Interleave.NONE;
        }
        if (subsampled && interleave == JpegLsEncoderOptions.Interleave.SAMPLE) {
            throw new IllegalArgumentException("sample interleaving needs components of one size");
        }
        var presets = new int[4];
        options.presetParameters().ifPresent(parameters -> {
            presets[0] = parameters.threshold1();
            presets[1] = parameters.threshold2();
            presets[2] = parameters.threshold3();
            presets[3] = parameters.reset();
        });
        checkPresets(presets, maxval, near);
        var transform = options.colorTransform().ordinal();
        if (transform != 0 && (spiff != SPIFF_RGB || near != 0 || (precision != 8 && precision != 16)
                || interleave == JpegLsEncoderOptions.Interleave.NONE)) {
            throw new IllegalArgumentException("the " + options.colorTransform() + " color transform needs RGB samples"
                    + " of 8 or 16 bits coded losslessly in interleaved scans");
        }
        var writeSpiff = options.spiffHeader() || spiff == SPIFF_YCBCR;
        return new JpegLsWriter.Stream(picture.width(), picture.height(), precision, horizontal, vertical, planes,
                interleave.ordinal(), near, presets, transform, options.restartInterval(), writeSpiff ? spiff : -1, 0);
    }

    /**
     * Checks the preset parameters against the ranges of ITU-T T.87 table C.1, defaults taking the place of 0.
     *
     * @param presets {@code T1}, {@code T2}, {@code T3} and {@code RESET}
     * @param maxval the largest sample value
     * @param near the near-lossless bound
     * @throws IllegalArgumentException if a value is out of range
     */
    private static void checkPresets(int[] presets, int maxval, int near) {
        var defaults = JpegLsWriter.defaults(maxval, near);
        var t1 = presets[0] != 0 ? presets[0] : defaults[0];
        var t2 = presets[1] != 0 ? presets[1] : defaults[1];
        var t3 = presets[2] != 0 ? presets[2] : defaults[2];
        var reset = presets[3] != 0 ? presets[3] : defaults[3];
        if (t1 < near + 1 || t1 > maxval || t2 < t1 || t2 > maxval || t3 < t2 || t3 > maxval || reset < 3
                || reset > Math.max(255, maxval)) {
            throw new IllegalArgumentException("JPEG-LS preset parameters T1=" + t1 + " T2=" + t2 + " T3=" + t3
                    + " RESET=" + reset + " are out of range for MAXVAL " + maxval + " and NEAR " + near);
        }
    }

    /**
     * Copies an 8-bit plane.
     *
     * @param samples the samples, in raster order
     * @param stride the row stride
     * @param width the plane width
     * @param height the plane height
     * @return the samples, tightly packed
     */
    private static int[] plane(byte[] samples, int stride, int width, int height) {
        var plane = new int[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                plane[y * width + x] = samples[y * stride + x] & 0xFF;
            }
        }
        return plane;
    }

    /**
     * Copies a 16-bit plane.
     *
     * @param samples the samples, in raster order
     * @param stride the row stride
     * @param width the plane width
     * @param height the plane height
     * @return the samples, tightly packed
     */
    private static int[] plane(short[] samples, int stride, int width, int height) {
        var plane = new int[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                plane[y * width + x] = samples[y * stride + x] & 0xFFFF;
            }
        }
        return plane;
    }

    /**
     * {@return an array of ones}
     *
     * @param count the length
     */
    private static int[] ones(int count) {
        var ones = new int[count];
        java.util.Arrays.fill(ones, 1);
        return ones;
    }

    /**
     * Splits interleaved 8-bit channels into planes.
     *
     * @param pixels the interleaved samples
     * @param stride the row stride in bytes
     * @param width the width in samples
     * @param height the height in rows
     * @param count the number of channels
     * @return one plane per channel
     */
    private static int[][] channels(byte[] pixels, int stride, int width, int height, int count) {
        var planes = new int[count][width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var c = 0; c < count; c++) {
                    planes[c][y * width + x] = pixels[y * stride + count * x + c] & 0xFF;
                }
            }
        }
        return planes;
    }

    /**
     * Splits interleaved 16-bit channels into planes.
     *
     * @param pixels the interleaved samples
     * @param stride the row stride in samples
     * @param width the width in samples
     * @param height the height in rows
     * @param count the number of channels
     * @return one plane per channel
     */
    private static int[][] channels(short[] pixels, int stride, int width, int height, int count) {
        var planes = new int[count][width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var c = 0; c < count; c++) {
                    planes[c][y * width + x] = pixels[y * stride + count * x + c] & 0xFFFF;
                }
            }
        }
        return planes;
    }

    /**
     * Splits 8-bit RGBA pixels into red, green and blue planes.
     *
     * @param pixels the interleaved samples
     * @param stride the row stride in bytes
     * @param width the width in samples
     * @param height the height in rows
     * @return the three planes
     */
    private static int[][] rgb(byte[] pixels, int stride, int width, int height) {
        var planes = new int[3][width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var c = 0; c < 3; c++) {
                    planes[c][y * width + x] = pixels[y * stride + 4 * x + c] & 0xFF;
                }
            }
        }
        return planes;
    }

    /**
     * Splits 16-bit RGBA pixels into red, green and blue planes.
     *
     * @param pixels the interleaved samples
     * @param stride the row stride in samples
     * @param width the width in samples
     * @param height the height in rows
     * @return the three planes
     */
    private static int[][] rgb(short[] pixels, int stride, int width, int height) {
        var planes = new int[3][width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var c = 0; c < 3; c++) {
                    planes[c][y * width + x] = pixels[y * stride + 4 * x + c] & 0xFFFF;
                }
            }
        }
        return planes;
    }

    /**
     * Rescales samples from one bit depth to another, rounding to nearest.
     *
     * @param planes the samples, rescaled in place
     * @param from the bit depth of the samples
     * @param to the bit depth to rescale to
     */
    private static void rescale(int[][] planes, int from, int to) {
        var source = (1L << from) - 1;
        var target = (1L << to) - 1;
        for (var plane : planes) {
            for (var i = 0; i < plane.length; i++) {
                plane[i] = (int) ((plane[i] * target * 2 + source) / (2 * source));
            }
        }
    }
}
