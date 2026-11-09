package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.PixelLayout;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Implements the channel operations of {@link ImageOps}: splitting a picture into one picture per channel,
 * merging single-channel pictures and replacing the alpha of a picture. Arguments are validated by the caller
 * unless a method says otherwise.
 */
final class ChannelSplit {
    /**
     * Prevents instantiation.
     */
    private ChannelSplit() {
        throw new AssertionError();
    }

    /**
     * Splits a picture into one {@link PixelLayout#GRAY} picture per channel.
     *
     * @param picture the picture to split
     * @return the channels, in the order of the picture's layout
     */
    static List<ChannelPicture> split(Picture picture) {
        var channels = switch (picture) {
            case ChannelPicture channel -> channel;
            case Rgba16Picture _, Yuv16Picture _ -> ChannelOps.toChannel16(picture);
            default -> ChannelOps.toChannel8(picture);
        };
        var width = channels.width();
        var height = channels.height();
        var count = channels.channelCount();
        OpChecks.checkArray((long) width * height * count);
        var planes = new ArrayList<ChannelPicture>(count);
        for (var c = 0; c < count; c++) {
            var plane = ChannelOps.allocateLike(channels, width, height, PixelLayout.GRAY);
            for (var y = 0; y < height; y++) {
                var source = y * channels.stride() + c;
                var target = y * width;
                switch (channels) {
                    case Channel8Picture eight -> {
                        var src = eight.pixels();
                        var dst = ((Channel8Picture) plane).pixels();
                        for (var x = 0; x < width; x++) {
                            dst[target + x] = src[source + x * count];
                        }
                    }
                    case Channel16Picture sixteen -> {
                        var src = sixteen.pixels();
                        var dst = ((Channel16Picture) plane).pixels();
                        for (var x = 0; x < width; x++) {
                            dst[target + x] = src[source + x * count];
                        }
                    }
                    case ChannelFloatPicture floating -> {
                        var src = floating.pixels();
                        var dst = ((ChannelFloatPicture) plane).pixels();
                        for (var x = 0; x < width; x++) {
                            dst[target + x] = src[source + x * count];
                        }
                    }
                }
            }
            planes.add(plane);
        }
        return List.copyOf(planes);
    }

    /**
     * Interleaves single-channel pictures into one picture of a layout.
     *
     * @param layout the layout of the result
     * @param channels the channels, one per channel of {@code layout}
     * @return the merged picture
     * @throws IllegalArgumentException if the number of pictures differs from the layout's channel count, or a
     * picture is not a single-channel {@link ChannelPicture} of the size and sample type of the first one
     */
    static ChannelPicture merge(PixelLayout layout, List<? extends Picture> channels) {
        var count = layout.count();
        if (channels.size() != count) {
            throw new IllegalArgumentException(channels.size() + " pictures cannot fill the " + count
                    + " channels of " + layout);
        }
        if (!(channels.getFirst() instanceof ChannelPicture first) || first.channelCount() != 1) {
            throw new IllegalArgumentException("channel 0 is not a single-channel picture: " + channels.getFirst());
        }
        var width = first.width();
        var height = first.height();
        for (var c = 1; c < count; c++) {
            var channel = channels.get(c);
            if (!(channel instanceof ChannelPicture picture) || picture.channelCount() != 1
                    || picture.getClass() != first.getClass()) {
                throw new IllegalArgumentException("channel " + c + " is not a single-channel picture of the sample"
                        + " type of channel 0: " + channel);
            }
            if (picture.width() != width || picture.height() != height) {
                throw new IllegalArgumentException("channel " + c + " is " + picture.width() + "x" + picture.height()
                        + ", channel 0 is " + width + "x" + height);
            }
        }
        OpChecks.checkArray((long) width * height * count);
        var out = ChannelOps.allocateLike(first, width, height, layout);
        for (var c = 0; c < count; c++) {
            var plane = (ChannelPicture) channels.get(c);
            for (var y = 0; y < height; y++) {
                var source = y * plane.stride();
                var target = y * out.stride() + c;
                switch (out) {
                    case Channel8Picture eight -> {
                        var src = ((Channel8Picture) plane).pixels();
                        var dst = eight.pixels();
                        for (var x = 0; x < width; x++) {
                            dst[target + x * count] = src[source + x];
                        }
                    }
                    case Channel16Picture sixteen -> {
                        var src = ((Channel16Picture) plane).pixels();
                        var dst = sixteen.pixels();
                        for (var x = 0; x < width; x++) {
                            dst[target + x * count] = src[source + x];
                        }
                    }
                    case ChannelFloatPicture floating -> {
                        var src = ((ChannelFloatPicture) plane).pixels();
                        var dst = floating.pixels();
                        for (var x = 0; x < width; x++) {
                            dst[target + x * count] = src[source + x];
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * Returns the alpha values a picture supplies: the samples of a single-channel {@link ChannelPicture}, or the
     * BT.601 luma of the packed RGBA form of any other picture.
     *
     * @param alpha the picture of the alpha values
     * @param width the width the picture must have
     * @param height the height the picture must have
     * @return the values, row after row, at the returned depth
     * @throws IllegalArgumentException if the picture is not {@code width} by {@code height}
     */
    static Values alphaValues(Picture alpha, int width, int height) {
        if (alpha.width() != width || alpha.height() != height) {
            throw new IllegalArgumentException("the " + alpha.width() + "x" + alpha.height()
                    + " alpha does not match the " + width + "x" + height + " picture");
        }
        var values = new int[width * height];
        if (alpha instanceof ChannelPicture channels && channels.channelCount() == 1) {
            var row = new int[width];
            for (var y = 0; y < height; y++) {
                ChannelOps.load(channels, y, row);
                System.arraycopy(row, 0, values, y * width, width);
            }
            return new Values(values, ChannelOps.sourceMax(channels));
        }
        var packed = Canvas.toPacked(alpha);
        var rgba = new int[4];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                Tone.read(packed, x, y, rgba);
                values[y * width + x] = Tone.luma601(rgba[0], rgba[1], rgba[2]);
            }
        }
        return new Values(values, Tone.max(packed));
    }

    /**
     * Replaces the alpha of a picture.
     *
     * @param picture the picture whose alpha to replace, of any type
     * @param alpha the alpha values, row after row
     * @return the picture with the alpha, as {@link ImageOps#putAlpha(Picture, Picture)} describes
     * @throws MediaException.Unsupported if the picture is a channel picture with premultiplied alpha
     */
    static Picture putAlpha(Picture picture, Values alpha) {
        var width = picture.width();
        var height = picture.height();
        if (picture instanceof ChannelPicture channels) {
            return putAlpha(channels, alpha);
        }
        var packed = Canvas.toPacked(picture);
        OpChecks.checkArray(4L * width * height);
        switch (packed) {
            case RgbaPicture rgba -> {
                var out = RgbaPicture.allocate(width, height, true);
                var src = rgba.pixels();
                var dst = out.pixels();
                for (var y = 0; y < height; y++) {
                    var s = y * rgba.stride();
                    var d = y * out.stride();
                    for (var x = 0; x < width; x++) {
                        dst[d + 4 * x] = src[s + 4 * x];
                        dst[d + 4 * x + 1] = src[s + 4 * x + 1];
                        dst[d + 4 * x + 2] = src[s + 4 * x + 2];
                        dst[d + 4 * x + 3] = (byte) alpha.at(y * width + x, 255);
                    }
                }
                return out;
            }
            case Rgba16Picture rgba -> {
                var out = Rgba16Picture.allocate(width, height, true);
                var src = rgba.pixels();
                var dst = out.pixels();
                for (var y = 0; y < height; y++) {
                    var s = y * rgba.stride();
                    var d = y * out.stride();
                    for (var x = 0; x < width; x++) {
                        dst[d + 4 * x] = src[s + 4 * x];
                        dst[d + 4 * x + 1] = src[s + 4 * x + 1];
                        dst[d + 4 * x + 2] = src[s + 4 * x + 2];
                        dst[d + 4 * x + 3] = (short) alpha.at(y * width + x, 65535);
                    }
                }
                return out;
            }
            default -> throw new IllegalArgumentException("not a packed picture: " + packed);
        }
    }

    /**
     * Replaces or appends the straight alpha channel of a channel picture.
     *
     * @param picture the channel picture
     * @param alpha the alpha values
     * @return the new picture, of the same sample type
     * @throws MediaException.Unsupported if the picture has premultiplied alpha
     */
    private static ChannelPicture putAlpha(ChannelPicture picture, Values alpha) {
        var layout = picture.layout();
        if (layout.isAlphaPremultiplied()) {
            throw new MediaException.Unsupported("premultiplied alpha", "replacing a premultiplied alpha channel"
                    + " would leave the colors multiplied by the old alpha; convert the picture first");
        }
        var index = layout.alphaIndex();
        var target = index >= 0 ? layout : layout.withChannel(Channel.of(ChannelType.ALPHA));
        var alphaIndex = index >= 0 ? index : layout.count();
        var width = picture.width();
        var height = picture.height();
        var sourceCount = picture.channelCount();
        var count = target.count();
        OpChecks.checkArray((long) width * height * count);
        var out = ChannelOps.allocateLike(picture, width, height, target);
        for (var y = 0; y < height; y++) {
            var s = y * picture.stride();
            var d = y * out.stride();
            for (var x = 0; x < width; x++) {
                for (var c = 0; c < count; c++) {
                    var to = d + x * count + c;
                    var from = s + x * sourceCount + c;
                    switch (out) {
                        case Channel8Picture eight -> eight.pixels()[to] = c == alphaIndex
                                ? (byte) alpha.at(y * width + x, 255) : ((Channel8Picture) picture).pixels()[from];
                        case Channel16Picture sixteen -> sixteen.pixels()[to] = c == alphaIndex
                                ? (short) alpha.at(y * width + x, 65535) : ((Channel16Picture) picture).pixels()[from];
                        case ChannelFloatPicture floating -> floating.pixels()[to] = c == alphaIndex
                                ? (float) alpha.values[y * width + x] / alpha.max
                                : ((ChannelFloatPicture) picture).pixels()[from];
                    }
                }
            }
        }
        return out;
    }

    /**
     * Holds integer alpha values at a depth.
     *
     * @param values the values, row after row
     * @param max the largest value of the depth, 255 or 65535
     */
    record Values(int[] values, int max) {
        /**
         * Returns one value rescaled to a depth.
         *
         * @param index the index of the pixel
         * @param to the largest value of the target depth, 255 or 65535
         * @return the value, rescaled as {@link ChannelOps#rescale} does
         */
        int at(int index, int to) {
            return ChannelOps.rescale(values[index], max, to);
        }

        /**
         * Returns a uniform alpha.
         *
         * @param alpha the alpha, {@code 0..255}
         * @param count the number of pixels
         * @return the values
         */
        static Values uniform(int alpha, int count) {
            var values = new int[count];
            Arrays.fill(values, alpha);
            return new Values(values, 255);
        }
    }
}
