package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
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
 * sample rescaled as {@code round(v * 65535 / (2^P - 1))}.
 */
public final class LosslessOutput {
    /**
     * Prevents instantiation.
     */
    private LosslessOutput() {
        throw new AssertionError();
    }

    /**
     * Returns whether {@link #decodePicture} applies to a frame: one of more than 8 bits coded as
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
            jpeg.decode((row, samples) -> {
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
        LimitChecks.checkAllocation(limits, 2L * count * width * height);
        var format = count == 1 ? ChromaFormat.MONOCHROME : ChromaFormat.YUV444;
        var picture = Yuv16Picture.allocate(width, height, format, precision, false, ColorInfo.JPEG);
        var planes = count == 1 ? new short[][]{picture.y()} : new short[][]{picture.y(), picture.u(), picture.v()};
        var strides = count == 1 ? new int[]{picture.yStride()}
                : new int[]{picture.yStride(), picture.uvStride(), picture.uvStride()};
        jpeg.decode((row, samples) -> {
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
}
