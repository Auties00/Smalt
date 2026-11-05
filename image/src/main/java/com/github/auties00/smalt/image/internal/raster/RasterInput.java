package com.github.auties00.smalt.image.internal.raster;

import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.ops.ImageOps;

/**
 * Reads the samples of a picture to encode in the layouts the simple raster encoders write.
 */
public final class RasterInput {
    private RasterInput() {
        throw new AssertionError();
    }

    /**
     * {@return whether a picture holds gray samples: a channel picture whose color channels are gray}
     *
     * @param picture the picture to read
     */
    public static boolean isGray(Picture picture) {
        return picture instanceof ChannelPicture channels && channels.layout().colorModel() == ColorModel.GRAY;
    }

    /**
     * Returns the samples of a picture as 16-bit gray or RGB, followed by alpha when the picture has it and
     * {@code alpha} asks for it.
     *
     * <p>Gray pictures keep their gray samples when {@code gray} is set and replicate them otherwise; other
     * pictures are converted to RGB as {@link ImageOps#toRgba16(Picture)} converts them, and reduced to gray with
     * the BT.601 weights, {@code (299 R + 587 G + 114 B + 500) / 1000}, when {@code gray} is set. 8-bit samples are
     * widened as {@code v * 257}.
     *
     * @param picture the picture to read
     * @param gray whether to return one gray channel
     * @param alpha whether to keep the alpha channel
     * @return the samples, with a tight stride, in the {@link PixelLayout#GRAY}, {@link PixelLayout#GRAY_ALPHA},
     * {@link PixelLayout#RGB} or {@link PixelLayout#RGBA} layout
     */
    public static Channel16Picture samples(Picture picture, boolean gray, boolean alpha) {
        var keepAlpha = alpha && picture.hasAlpha();
        var target = gray ? (keepAlpha ? PixelLayout.GRAY_ALPHA : PixelLayout.GRAY)
                : keepAlpha ? PixelLayout.RGBA : PixelLayout.RGB;
        Channel16Picture source;
        if (isGray(picture)) {
            source = ImageOps.toChannel16(picture);
        } else if (picture instanceof ChannelPicture channels && (channels.layout().equals(PixelLayout.RGB)
                || channels.layout().equals(PixelLayout.RGBA))) {
            source = ImageOps.toChannel16(picture);
        } else {
            source = ImageOps.toChannel16(ImageOps.toRgba16(picture));
        }
        if (source.layout().equals(target) && source.stride() == source.width() * target.count()) {
            return source;
        }
        var layout = source.layout();
        var width = source.width();
        var height = source.height();
        var in = source.pixels();
        var stride = source.stride();
        var channels = layout.count();
        var sourceGray = layout.colorModel() == ColorModel.GRAY;
        var sourceAlpha = layout.alphaIndex();
        var result = Channel16Picture.allocate(width, height, target);
        var out = result.pixels();
        var outChannels = target.count();
        for (var y = 0; y < height; y++) {
            var row = y * stride;
            var outRow = y * width * outChannels;
            for (var x = 0; x < width; x++) {
                var at = row + x * channels;
                var to = outRow + x * outChannels;
                int r;
                int g;
                int b;
                if (sourceGray) {
                    r = g = b = in[at] & 0xFFFF;
                } else {
                    r = in[at] & 0xFFFF;
                    g = in[at + 1] & 0xFFFF;
                    b = in[at + 2] & 0xFFFF;
                }
                if (gray) {
                    out[to] = (short) (sourceGray ? r : (int) ((299L * r + 587L * g + 114L * b + 500) / 1000));
                } else {
                    out[to] = (short) r;
                    out[to + 1] = (short) g;
                    out[to + 2] = (short) b;
                }
                if (keepAlpha) {
                    out[to + outChannels - 1] = sourceAlpha >= 0 ? in[at + sourceAlpha] : (short) 0xFFFF;
                }
            }
        }
        return result;
    }

    /**
     * Returns the samples of a picture as floating-point gray or RGB, followed by alpha when the picture has it and
     * {@code alpha} asks for it.
     *
     * <p>A {@link ChannelFloatPicture} of a gray or RGB layout keeps its values; other pictures are first read as
     * {@link #samples(Picture, boolean, boolean)} reads them and divided by 65535.
     *
     * @param picture the picture to read
     * @param gray whether to return one gray channel
     * @param alpha whether to keep the alpha channel
     * @return the samples, with a tight stride, in the {@link PixelLayout#GRAY}, {@link PixelLayout#GRAY_ALPHA},
     * {@link PixelLayout#RGB} or {@link PixelLayout#RGBA} layout
     */
    public static ChannelFloatPicture floats(Picture picture, boolean gray, boolean alpha) {
        var keepAlpha = alpha && picture.hasAlpha();
        var target = gray ? (keepAlpha ? PixelLayout.GRAY_ALPHA : PixelLayout.GRAY)
                : keepAlpha ? PixelLayout.RGBA : PixelLayout.RGB;
        if (picture instanceof ChannelFloatPicture floats && RasterOutput.plain(floats.layout())) {
            if (floats.layout().equals(target) && floats.stride() == floats.width() * target.count()) {
                return floats;
            }
            var layout = floats.layout();
            var width = floats.width();
            var height = floats.height();
            var in = floats.pixels();
            var channels = layout.count();
            var sourceGray = layout.colorModel() == ColorModel.GRAY;
            var sourceAlpha = layout.alphaIndex();
            var result = ChannelFloatPicture.allocate(width, height, target);
            var out = result.pixels();
            var outChannels = target.count();
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var at = y * floats.stride() + x * channels;
                    var to = (y * width + x) * outChannels;
                    var r = in[at];
                    var g = sourceGray ? r : in[at + 1];
                    var b = sourceGray ? r : in[at + 2];
                    if (gray) {
                        out[to] = sourceGray ? r : (float) (0.299 * r + 0.587 * g + 0.114 * b);
                    } else {
                        out[to] = r;
                        out[to + 1] = g;
                        out[to + 2] = b;
                    }
                    if (keepAlpha) {
                        out[to + outChannels - 1] = sourceAlpha >= 0 ? in[at + sourceAlpha] : 1f;
                    }
                }
            }
            return result;
        }
        var wide = samples(picture, gray, alpha);
        var result = ChannelFloatPicture.allocate(wide.width(), wide.height(), wide.layout());
        var in = wide.pixels();
        var out = result.pixels();
        for (var index = 0; index < out.length; index++) {
            out[index] = (in[index] & 0xFFFF) / 65535f;
        }
        return result;
    }
}
