package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Turns the samples of a lossless (SOF3) frame into decoded planes or a picture.
 *
 * <p>The planes of {@link #decodePlanes} hold 8-bit samples, those of any other precision
 * {@code P} rescaled as {@code round(v * 255 / (2^P - 1))}, so that {@link JpegOutput} converts
 * them like the planes of a DCT frame. {@link #decodePicture} keeps the coded precision of a gray,
 * YCbCr or RGB frame of more than 8 bits: gray and YCbCr frames become a {@link Yuv16Picture} of
 * that bit depth holding the samples as coded, and RGB frames an {@link Rgba16Picture} with every
 * sample rescaled as {@code round(v * 65535 / (2^P - 1))}; {@link #decodeCmyk} and
 * {@link #decodeChannels} do the same for CMYK frames and for frames without a color space.
 * Subsampled components reach the frame size by replication.
 */
public final class LosslessOutput {
    /**
     * Prevents instantiation.
     */
    private LosslessOutput() {
        throw new AssertionError();
    }

    /**
     * Returns whether a frame can keep its precision, through {@link #decodePicture} for a lossless
     * frame and {@link JpegOutput#toWidePicture} for a DCT frame: one of more than 8 bits coded as
     * gray, YCbCr or RGB.
     *
     * @param reader the reader, its headers read
     * @param colorSpace the color space of the frame
     * @return {@code true} when the frame can keep its precision
     */
    public static boolean keepsPrecision(JpegReader reader, JpegColorSpace colorSpace) {
        return reader.frame().precision > 8 && (colorSpace == JpegColorSpace.GRAY
                || colorSpace == JpegColorSpace.YCBCR || colorSpace == JpegColorSpace.RGB);
    }

    /**
     * Returns whether {@link #decodeCmyk} applies to a frame: one of more than 8 bits coded as CMYK.
     *
     * @param reader the reader, its headers read
     * @param colorSpace the color space of the frame
     * @return {@code true} when the frame can keep its precision as ink samples
     */
    public static boolean keepsCmykPrecision(JpegReader reader, JpegColorSpace colorSpace) {
        return reader.frame().precision > 8 && colorSpace == JpegColorSpace.CMYK;
    }

    /**
     * Returns whether a lossless frame holds RGB samples through the reversible color transform of
     * ffmpeg's lossless JPEG encoder: a JFIF frame of 9-bit precision whose three or four
     * components have sampling factors of one, the fourth being alpha.
     *
     * @param reader the reader, its headers read
     * @return {@code true} for such a frame
     */
    public static boolean reversibleRgb(JpegReader reader) {
        var frame = reader.frame();
        if (!reader.lossless() || frame.precision != 9 || !reader.hasJfif()
                || (frame.components.length != 3 && frame.components.length != 4)) {
            return false;
        }
        for (var component : frame.components) {
            if (component.h != 1 || component.v != 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decodes a frame for which {@link #reversibleRgb} holds into 8-bit RGBA: with the components
     * {@code Y}, {@code U} and {@code V} of a sample, {@code G = Y - ((U + V - 512) >> 2)},
     * {@code B = U - 256 + G} and {@code R = V - 256 + G}, each kept to 8 bits; the fourth component
     * is the alpha, or the picture is opaque without one.
     *
     * @param reader the reader of the frame
     * @param limits the resource bounds checked before the allocation
     * @return the picture
     * @throws com.github.auties00.smalt.MediaException.Malformed if a header is invalid
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the frame exceeds the limits
     */
    public static RgbaPicture decodeReversibleRgb(JpegReader reader, MediaLimits limits) {
        var jpeg = reader.openLossless();
        var frame = reader.frame();
        var width = frame.width;
        var count = frame.components.length;
        LimitChecks.checkAllocation(limits, 4L * width * frame.height);
        var picture = RgbaPicture.allocate(width, frame.height, count == 4);
        var pixels = picture.pixels();
        var stride = picture.stride();
        jpeg.decode((row, samples) -> {
            var at = row * stride;
            for (var x = 0; x < width; x++) {
                var y = samples[x * count];
                var u = samples[x * count + 1];
                var v = samples[x * count + 2];
                var g = y - ((u + v - 512) >> 2);
                pixels[at + 4 * x] = (byte) (v - 256 + g);
                pixels[at + 4 * x + 1] = (byte) g;
                pixels[at + 4 * x + 2] = (byte) (u - 256 + g);
                pixels[at + 4 * x + 3] = count == 4 ? (byte) samples[x * count + 3] : (byte) 0xFF;
            }
        });
        return picture;
    }

    /**
     * Decodes a lossless frame into 8-bit planes.
     *
     * @param reader the reader of a lossless frame
     * @param limits the resource bounds checked before every allocation
     * @return the planes, at full size
     * @throws com.github.auties00.smalt.MediaException.Malformed if a header is invalid
     * @throws com.github.auties00.smalt.MediaException.Unsupported if the frame uses an unsupported feature
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the frame exceeds the limits
     */
    public static JpegPlanes decodePlanes(JpegReader reader, MediaLimits limits) {
        var jpeg = reader.openLossless();
        var frame = reader.frame();
        var components = frame.components;
        var count = components.length;
        var planes = new byte[count][];
        var strides = new int[count];
        for (var c = 0; c < count; c++) {
            strides[c] = components[c].paddedBlocksWide * 8;
            var bytes = (long) strides[c] * components[c].paddedBlocksHigh * 8;
            LimitChecks.checkAllocation(limits, bytes);
            planes[c] = new byte[(int) bytes];
        }
        var width = frame.width;
        var max = (1 << frame.precision) - 1;
        var exact = frame.precision == 8;
        if (!jpeg.streamsRows()) {
            var columns = new int[count];
            var rows = new int[count];
            for (var c = 0; c < count; c++) {
                columns[c] = Math.min(strides[c], jpeg.componentWidth(c));
                rows[c] = components[c].paddedBlocksHigh * 8;
            }
            jpeg.decodeComponents((c, row, samples) -> {
                if (row >= rows[c]) {
                    return;
                }
                var plane = planes[c];
                var at = row * strides[c];
                for (var x = 0; x < columns[c]; x++) {
                    var value = samples[x] & max;
                    plane[at + x] = (byte) (exact ? value : (value * 510 + max) / (2 * max));
                }
            });
            return new JpegPlanes(frame, planes, strides, 0);
        }
        jpeg.decode((row, samples) -> {
            for (var c = 0; c < count; c++) {
                var plane = planes[c];
                var at = row * strides[c];
                for (var x = 0; x < width; x++) {
                    // the mask keeps the samples of corrupt data in range
                    var value = samples[x * count + c] & max;
                    plane[at + x] = (byte) (exact ? value : (value * 510 + max) / (2 * max));
                }
            }
        });
        return new JpegPlanes(frame, planes, strides, 0);
    }

    /**
     * Decodes a lossless CMYK frame of more than 8 bits to 16-bit ink samples, 0 being no ink, each
     * sample {@code v} of precision {@code P} rescaled as {@code round(v * 65535 / (2^P - 1))}.
     *
     * @param reader the reader of a four-component lossless frame
     * @param inverted whether the samples are stored inverted (Adobe convention)
     * @param limits the resource bounds checked before every allocation
     * @return the picture of the {@link PixelLayout#CMYK} layout
     * @throws com.github.auties00.smalt.MediaException.Malformed if a header is invalid
     * @throws com.github.auties00.smalt.MediaException.Unsupported if the frame uses an unsupported feature
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the frame exceeds the limits
     */
    public static Channel16Picture decodeCmyk(JpegReader reader, boolean inverted, MediaLimits limits) {
        var jpeg = reader.openLossless();
        var frame = reader.frame();
        var width = frame.width;
        var height = frame.height;
        var max = (1 << frame.precision) - 1;
        LimitChecks.checkAllocation(limits, 8L * width * height);
        var picture = Channel16Picture.allocate(width, height, PixelLayout.CMYK);
        var pixels = picture.pixels();
        var stride = picture.stride();
        var twiceMax = 2L * max;
        decodeRows(jpeg, frame, limits, (row, samples) -> {
            var at = row * stride;
            for (var i = 0; i < 4 * width; i++) {
                var value = samples[i] & max;
                if (inverted) {
                    value = max - value;
                }
                pixels[at + i] = (short) ((value * 131070L + max) / twiceMax);
            }
        });
        return picture;
    }

    /**
     * Decodes a lossless frame of more than 8 bits, coded as gray, YCbCr or RGB, at its precision.
     *
     * @param reader the reader of a frame for which {@link #keepsPrecision} holds
     * @param colorSpace the color space of the frame
     * @param limits the resource bounds checked before every allocation
     * @return the picture
     * @throws com.github.auties00.smalt.MediaException.Malformed if a header is invalid
     * @throws com.github.auties00.smalt.MediaException.Unsupported if the frame uses an unsupported feature
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the frame exceeds the limits
     */
    public static Picture decodePicture(JpegReader reader, JpegColorSpace colorSpace, MediaLimits limits) {
        var jpeg = reader.openLossless();
        var frame = reader.frame();
        var width = frame.width;
        var height = frame.height;
        var count = frame.components.length;
        var precision = frame.precision;
        var max = (1 << precision) - 1;
        if (colorSpace == JpegColorSpace.RGB) {
            LimitChecks.checkAllocation(limits, 8L * width * height);
            var picture = Rgba16Picture.allocate(width, height, false);
            var pixels = picture.pixels();
            var stride = picture.stride();
            var twiceMax = 2L * max;
            decodeRows(jpeg, frame, limits, (row, samples) -> {
                var at = row * stride;
                for (var x = 0; x < width; x++) {
                    for (var c = 0; c < 3; c++) {
                        var value = samples[x * 3 + c] & max;
                        pixels[at + 4 * x + c] = (short) ((value * 131070L + max) / twiceMax);
                    }
                    pixels[at + 4 * x + 3] = (short) 0xFFFF;
                }
            });
            return picture;
        }
        if (colorSpace == JpegColorSpace.YCBCR && !jpeg.fullResolution()) {
            var direct = JpegOutput.directFormat(frame, frame.components[0], frame.components[1], frame.components[2]);
            if (direct != null) {
                return decodeSubsampled(jpeg, direct, width, height, precision, limits);
            }
        }
        LimitChecks.checkAllocation(limits, 2L * count * width * height);
        var format = count == 1 ? ChromaFormat.MONOCHROME : ChromaFormat.YUV444;
        var picture = Yuv16Picture.allocate(width, height, format, precision, false, ColorInfo.JPEG);
        var planes = count == 1 ? new short[][]{picture.y()} : new short[][]{picture.y(), picture.u(), picture.v()};
        var strides = count == 1 ? new int[]{picture.yStride()}
                : new int[]{picture.yStride(), picture.uvStride(), picture.uvStride()};
        decodeRows(jpeg, frame, limits, (row, samples) -> {
            for (var c = 0; c < count; c++) {
                var plane = planes[c];
                var at = row * strides[c];
                for (var x = 0; x < width; x++) {
                    plane[at + x] = (short) (samples[x * count + c] & max);
                }
            }
        });
        return picture;
    }

    /**
     * Decodes a block-interleaved YCbCr frame whose sampling factors match a chroma format into a
     * picture of that format, holding the samples as coded.
     *
     * @param jpeg the scan
     * @param format the chroma format of the sampling factors
     * @param width the frame width
     * @param height the frame height
     * @param precision the sample precision
     * @param limits the resource bounds checked before the allocation
     * @return the picture
     */
    private static Picture decodeSubsampled(LosslessJpeg jpeg, ChromaFormat format, int width, int height,
                                            int precision, MediaLimits limits) {
        var chroma = (long) ((width + (1 << format.shiftX()) - 1) >> format.shiftX())
                * ((height + (1 << format.shiftY()) - 1) >> format.shiftY());
        LimitChecks.checkAllocation(limits, 2L * ((long) width * height + 2 * chroma));
        var picture = Yuv16Picture.allocate(width, height, format, precision, false, ColorInfo.JPEG);
        var planes = new short[][]{picture.y(), picture.u(), picture.v()};
        var strides = new int[]{picture.yStride(), picture.uvStride(), picture.uvStride()};
        var columns = new int[]{width, picture.chromaWidth(), picture.chromaWidth()};
        var rows = new int[]{height, picture.chromaHeight(), picture.chromaHeight()};
        var max = (1 << precision) - 1;
        jpeg.decodeComponents((c, row, samples) -> {
            if (row < rows[c]) {
                var plane = planes[c];
                var at = row * strides[c];
                for (var x = 0; x < columns[c]; x++) {
                    plane[at + x] = (short) (samples[x] & max);
                }
            }
        });
        return picture;
    }

    /**
     * Decodes a lossless frame of two or more than four components, of more than 8 bits, to a
     * 16-bit channel picture: every component at the frame resolution, subsampled ones replicated,
     * each sample {@code v} of precision {@code P} rescaled as {@code round(v * 65535 / (2^P - 1))}.
     *
     * @param reader the reader of a lossless frame
     * @param layout the layout of the result, with one channel per component
     * @param limits the resource bounds checked before every allocation
     * @return the picture
     * @throws com.github.auties00.smalt.MediaException.Malformed if a header is invalid
     * @throws com.github.auties00.smalt.MediaException.Unsupported if the frame uses an unsupported feature
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the frame exceeds the limits
     */
    public static Channel16Picture decodeChannels(JpegReader reader, PixelLayout layout, MediaLimits limits) {
        var jpeg = reader.openLossless();
        var frame = reader.frame();
        var width = frame.width;
        var height = frame.height;
        var count = frame.components.length;
        var max = (1 << frame.precision) - 1;
        LimitChecks.checkAllocation(limits, 2L * count * width * height);
        var picture = Channel16Picture.allocate(width, height, layout);
        var pixels = picture.pixels();
        var stride = picture.stride();
        var twiceMax = 2L * max;
        decodeRows(jpeg, frame, limits, (row, samples) -> {
            var at = row * stride;
            for (var i = 0; i < count * width; i++) {
                pixels[at + i] = (short) (((samples[i] & max) * 131070L + max) / twiceMax);
            }
        });
        return picture;
    }

    /**
     * Decodes the rows of a lossless frame at full resolution, the components of each column
     * interleaved; unless the frame streams its rows, the components are first decoded whole and
     * subsampled ones replicated up to the frame resolution.
     *
     * @param jpeg the scan
     * @param frame the frame header
     * @param limits the resource bounds checked before every allocation
     * @param sink receives the rows
     */
    private static void decodeRows(LosslessJpeg jpeg, JpegFrame frame, MediaLimits limits, LosslessJpeg.RowSink sink) {
        if (jpeg.streamsRows()) {
            jpeg.decode(sink);
            return;
        }
        var components = frame.components;
        var count = components.length;
        var width = frame.width;
        var height = frame.height;
        var max = (1 << frame.precision) - 1;
        var planes = new short[count][];
        for (var c = 0; c < count; c++) {
            var component = components[c];
            LimitChecks.checkAllocation(limits, 2L * component.width * component.height);
            planes[c] = new short[component.width * component.height];
        }
        jpeg.decodeComponents((c, row, samples) -> {
            var component = components[c];
            if (row < component.height) {
                var plane = planes[c];
                var at = row * component.width;
                for (var x = 0; x < component.width; x++) {
                    plane[at + x] = (short) (samples[x] & max);
                }
            }
        });
        var full = new short[count][];
        for (var c = 0; c < count; c++) {
            var component = components[c];
            if (component.h == frame.hMax && component.v == frame.vMax) {
                full[c] = planes[c];
            } else {
                LimitChecks.checkAllocation(limits, 2L * width * height);
                full[c] = Upsampler.upsample(planes[c], component.width, component.width, component.height,
                        component.h, component.v, frame.hMax, frame.vMax, width, height, false);
            }
        }
        var samples = new int[width * count];
        for (var y = 0; y < height; y++) {
            var at = y * width;
            for (var c = 0; c < count; c++) {
                var plane = full[c];
                for (var x = 0; x < width; x++) {
                    samples[x * count + c] = plane[at + x] & 0xFFFF;
                }
            }
            sink.row(y, samples);
        }
    }
}
