package com.github.auties00.smalt.image.gainmap;

import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorModel;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;

/**
 * Reads the log2 gains of a gain map at the pixels of an image, resampling a gain map of another size
 * bilinearly in the log2 domain at the centers of the pixels.
 *
 * <p>Instances are immutable and thread-safe once constructed.
 */
final class GainSampler {
    /**
     * The number of log2 gain planes, 1 or 3.
     */
    final int planes;

    /**
     * The smallest log2 gain over every channel.
     */
    final double minimum;

    /**
     * The largest log2 gain over every channel.
     */
    final double maximum;

    /**
     * The log2 gains per plane, at the gain map's own size.
     */
    private final float[][] gains;

    /**
     * The width of the gain map.
     */
    private final int mapWidth;

    /**
     * The height of the gain map.
     */
    private final int mapHeight;

    /**
     * The left neighbor column of each image column.
     */
    private final int[] left;

    /**
     * The right neighbor column of each image column.
     */
    private final int[] right;

    /**
     * The weight of the right neighbor of each image column.
     */
    private final float[] across;

    /**
     * The height of the image.
     */
    private final int height;

    /**
     * Reads the gains of a gain map for an image.
     *
     * @param image the gain map image
     * @param metadata the gain map metadata
     * @param width the width of the image the gain map applies to
     * @param height the height of the image the gain map applies to
     */
    GainSampler(Picture image, GainMapMetadata metadata, int width, int height) {
        var gray = isGray(image);
        this.planes = gray && !metadata.isMultichannel() ? 1 : 3;
        this.mapWidth = image.width();
        this.mapHeight = image.height();
        this.height = height;
        var count = mapWidth * mapHeight;
        this.gains = new float[planes][count];
        var minimum = Double.POSITIVE_INFINITY;
        var maximum = Double.NEGATIVE_INFINITY;
        for (var plane = 0; plane < planes; plane++) {
            var channel = metadata.channel(planes == 1 ? 0 : plane);
            minimum = Math.min(minimum, channel.minimum());
            maximum = Math.max(maximum, channel.maximum());
        }
        this.minimum = minimum;
        this.maximum = maximum;
        fill(image, metadata, gray);
        this.left = new int[width];
        this.right = new int[width];
        this.across = new float[width];
        for (var x = 0; x < width; x++) {
            var position = (x + 0.5) * mapWidth / width - 0.5;
            var floor = (int) Math.floor(position);
            var fraction = (float) (position - floor);
            if (floor < 0) {
                floor = 0;
                fraction = 0;
            }
            if (floor >= mapWidth - 1) {
                floor = mapWidth - 1;
                fraction = 0;
            }
            left[x] = floor;
            right[x] = Math.min(floor + 1, mapWidth - 1);
            across[x] = fraction;
        }
    }

    /**
     * Returns whether a gain map image holds one gain per pixel rather than one per color channel.
     *
     * @param image the gain map image
     * @return {@code true} for monochrome Y'CbCr and gray channel pictures
     */
    static boolean isGray(Picture image) {
        return switch (image) {
            case YuvPicture yuv -> yuv.chromaFormat() == ChromaFormat.MONOCHROME;
            case Yuv16Picture yuv -> yuv.chromaFormat() == ChromaFormat.MONOCHROME;
            case ChannelPicture channels -> channels.layout().colorModel() == ColorModel.GRAY;
            default -> false;
        };
    }

    /**
     * Converts the samples of the gain map image to log2 gains.
     *
     * @param image the gain map image
     * @param metadata the gain map metadata
     * @param gray whether the image holds one sample per pixel
     */
    private void fill(Picture image, GainMapMetadata metadata, boolean gray) {
        if (image instanceof ChannelFloatPicture floats) {
            var channels = floats.channelCount();
            var pixels = floats.pixels();
            for (var y = 0; y < mapHeight; y++) {
                for (var x = 0; x < mapWidth; x++) {
                    var at = y * floats.stride() + x * channels;
                    for (var plane = 0; plane < planes; plane++) {
                        var sample = pixels[at + (gray ? 0 : plane)];
                        gains[plane][y * mapWidth + x] = (float) metadata.channel(planes == 1 ? 0 : plane)
                                .log2Gain(sample);
                    }
                }
            }
            return;
        }
        if (image.bitDepth() <= 8) {
            var codes = ImageOps.toChannel8(image);
            var tables = tables(metadata, 255);
            var channels = codes.channelCount();
            var pixels = codes.pixels();
            for (var y = 0; y < mapHeight; y++) {
                var at = y * codes.stride();
                for (var x = 0; x < mapWidth; x++, at += channels) {
                    for (var plane = 0; plane < planes; plane++) {
                        gains[plane][y * mapWidth + x] = tables[plane][pixels[at + (gray ? 0 : plane)] & 0xFF];
                    }
                }
            }
            return;
        }
        var codes = ImageOps.toChannel16(image);
        var tables = tables(metadata, 65535);
        var channels = codes.channelCount();
        var pixels = codes.pixels();
        for (var y = 0; y < mapHeight; y++) {
            var at = y * codes.stride();
            for (var x = 0; x < mapWidth; x++, at += channels) {
                for (var plane = 0; plane < planes; plane++) {
                    gains[plane][y * mapWidth + x] = tables[plane][pixels[at + (gray ? 0 : plane)] & 0xFFFF];
                }
            }
        }
    }

    /**
     * Builds the log2 gain of every code value of each plane.
     *
     * @param metadata the gain map metadata
     * @param max the largest code value
     * @return the tables, one per plane
     */
    private float[][] tables(GainMapMetadata metadata, int max) {
        var tables = new float[planes][max + 1];
        for (var plane = 0; plane < planes; plane++) {
            var channel = metadata.channel(planes == 1 ? 0 : plane);
            for (var code = 0; code <= max; code++) {
                tables[plane][code] = (float) channel.log2Gain((double) code / max);
            }
        }
        return tables;
    }

    /**
     * Reads the log2 gains of one row of the image.
     *
     * @param y the row of the image
     * @param plane the plane, from 0 to {@link #planes} minus one
     * @param out the gains, one per column of the image
     */
    void row(int y, int plane, float[] out) {
        var position = (y + 0.5) * mapHeight / height - 0.5;
        var top = (int) Math.floor(position);
        var down = (float) (position - top);
        if (top < 0) {
            top = 0;
            down = 0;
        }
        if (top >= mapHeight - 1) {
            top = mapHeight - 1;
            down = 0;
        }
        var bottom = Math.min(top + 1, mapHeight - 1);
        var source = gains[plane];
        var upper = top * mapWidth;
        var lower = bottom * mapWidth;
        for (var x = 0; x < out.length; x++) {
            var l = left[x];
            var r = right[x];
            var a = across[x];
            var first = source[upper + l] + (source[upper + r] - source[upper + l]) * a;
            var second = source[lower + l] + (source[lower + r] - source[lower + l]) * a;
            out[x] = first + (second - first) * down;
        }
    }
}
