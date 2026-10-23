package com.github.auties00.smalt.image.tiff.internal;

import com.github.auties00.smalt.image.Channel;
import com.github.auties00.smalt.image.Channel16Picture;
import com.github.auties00.smalt.image.Channel8Picture;
import com.github.auties00.smalt.image.ChannelFloatPicture;
import com.github.auties00.smalt.image.ChannelPicture;
import com.github.auties00.smalt.image.ChannelType;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Applies a TIFF transparency mask (photometric interpretation 4) to the picture of the page it
 * masks.
 *
 * <p>The mask covers the whole picture whatever the two sizes. Each picture pixel takes the
 * coverage of the mask over its area: when the mask is at least as large as the picture in a
 * direction, pixel {@code x} covers the mask columns {@code x * mw / w} (inclusive) to
 * {@code (x + 1) * mw / w} (exclusive), integer division, and likewise rows; when it is smaller,
 * the pixel takes the single mask column {@code ((2x + 1) * mw) / (2w)}, the one under its center.
 * The coverage is the mean of the covered mask values, each 0 (exterior) to 255 (interior), and
 * the pixel's alpha is multiplied by it, rounding to nearest; a picture without alpha gains an
 * alpha channel holding the coverage, so a mask of 1-bit samples at the picture's size makes the
 * exterior transparent and leaves the interior opaque. Premultiplied color channels of a channel
 * picture are multiplied by the coverage too. Unspecified extra channels are unchanged.
 */
public final class TransparencyMask {
    /**
     * Prevents instantiation.
     */
    private TransparencyMask() {
        throw new AssertionError();
    }

    /**
     * Applies a mask.
     *
     * @param picture the decoded picture of the masked page, packed RGBA or a channel picture
     * @param mask the decoded mask, whose red channel holds the mask values
     * @return the masked picture, with alpha; the input picture's arrays are reused unless it is a
     * channel picture without alpha
     */
    public static Picture apply(Picture picture, RgbaPicture mask) {
        var width = picture.width();
        var height = picture.height();
        var columns = spans(width, mask.width());
        var rows = spans(height, mask.height());
        var target = withAlpha(picture);
        var opaque = !picture.hasAlpha();
        var bands = ParallelBands.bands(height, (long) mask.width() * mask.height() + (long) width * height);
        ParallelBands.run(bands, band -> {
            var first = ParallelBands.start(band, bands, height);
            var last = ParallelBands.start(band + 1, bands, height);
            var pixels = mask.pixels();
            var stride = mask.stride();
            var sums = new long[width];
            var counts = new long[width];
            for (var y = first; y < last; y++) {
                var rowSpan = rows[y * 2 + 1] - rows[y * 2];
                for (var x = 0; x < width; x++) {
                    var sum = 0L;
                    for (var my = rows[y * 2]; my < rows[y * 2 + 1]; my++) {
                        var at = my * stride;
                        for (var mx = columns[x * 2]; mx < columns[x * 2 + 1]; mx++) {
                            sum += pixels[at + mx * 4] & 0xFF;
                        }
                    }
                    sums[x] = sum;
                    counts[x] = (long) rowSpan * (columns[x * 2 + 1] - columns[x * 2]);
                }
                applyRow(target, opaque, y, sums, counts);
            }
        });
        return target;
    }

    /**
     * Computes the mask span of each picture column or row.
     *
     * @param size the picture size in that direction
     * @param maskSize the mask size in that direction
     * @return the first and the end (exclusive) mask index of each picture index, interleaved
     */
    private static int[] spans(int size, int maskSize) {
        var spans = new int[size * 2];
        for (var i = 0; i < size; i++) {
            if (maskSize >= size) {
                spans[i * 2] = (int) ((long) i * maskSize / size);
                spans[i * 2 + 1] = (int) ((long) (i + 1) * maskSize / size);
            } else {
                var center = (int) ((2L * i + 1) * maskSize / (2L * size));
                spans[i * 2] = center;
                spans[i * 2 + 1] = center + 1;
            }
        }
        return spans;
    }

    /**
     * Returns a picture that carries alpha, the input itself unless it is a channel picture without
     * an alpha channel, which is copied with an opaque alpha channel appended.
     *
     * @param picture the decoded picture
     * @return the picture to scale the alpha of
     */
    private static Picture withAlpha(Picture picture) {
        if (!(picture instanceof ChannelPicture channels) || channels.layout().alphaIndex() >= 0) {
            return switch (picture) {
                case RgbaPicture rgba when !rgba.hasAlpha() ->
                        RgbaPicture.of(rgba.width(), rgba.height(), rgba.stride(), rgba.pixels(), true);
                case Rgba16Picture rgba when !rgba.hasAlpha() ->
                        Rgba16Picture.of(rgba.width(), rgba.height(), rgba.stride(), rgba.pixels(), true);
                case RgbaPicture rgba -> rgba;
                case Rgba16Picture rgba -> rgba;
                case ChannelPicture channelPicture -> channelPicture;
                default -> throw new IllegalArgumentException("a TIFF page decodes to RGBA or channel pictures");
            };
        }
        var width = picture.width();
        var height = picture.height();
        var count = channels.layout().count();
        var outCount = count + 1;
        var widened = channels.layout().withChannel(Channel.of(ChannelType.ALPHA));
        return switch (channels) {
            case Channel8Picture narrow -> {
                var out = new byte[width * height * outCount];
                Arrays.fill(out, (byte) 0xFF);
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        System.arraycopy(narrow.pixels(), y * narrow.stride() + x * count, out,
                                (y * width + x) * outCount, count);
                    }
                }
                yield Channel8Picture.of(width, height, width * outCount, out, widened);
            }
            case Channel16Picture wide -> {
                var out = new short[width * height * outCount];
                Arrays.fill(out, (short) 0xFFFF);
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        System.arraycopy(wide.pixels(), y * wide.stride() + x * count, out,
                                (y * width + x) * outCount, count);
                    }
                }
                yield Channel16Picture.of(width, height, width * outCount, out, widened);
            }
            case ChannelFloatPicture floating -> {
                var out = new float[width * height * outCount];
                Arrays.fill(out, 1.0F);
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        System.arraycopy(floating.pixels(), y * floating.stride() + x * count, out,
                                (y * width + x) * outCount, count);
                    }
                }
                yield ChannelFloatPicture.of(width, height, width * outCount, out, widened);
            }
        };
    }

    /**
     * Scales the alpha of one picture row by the coverage of each pixel.
     *
     * @param picture the picture, carrying alpha
     * @param opaque whether the alpha of packed RGBA is taken as opaque rather than read
     * @param y the row
     * @param sums the sum of the covered mask values of each pixel
     * @param counts the number of covered mask values of each pixel
     */
    private static void applyRow(Picture picture, boolean opaque, int y, long[] sums, long[] counts) {
        var width = picture.width();
        switch (picture) {
            case RgbaPicture rgba -> {
                var pixels = rgba.pixels();
                var base = y * rgba.stride() + 3;
                for (var x = 0; x < width; x++) {
                    var at = base + x * 4;
                    pixels[at] = (byte) scale(opaque ? 255 : pixels[at] & 0xFF, sums[x], counts[x]);
                }
            }
            case Rgba16Picture rgba -> {
                var pixels = rgba.pixels();
                var base = y * rgba.stride() + 3;
                for (var x = 0; x < width; x++) {
                    var at = base + x * 4;
                    pixels[at] = (short) scale(opaque ? 65535 : pixels[at] & 0xFFFF, sums[x], counts[x]);
                }
            }
            case ChannelPicture channels -> applyChannelRow(channels, y, sums, counts);
            default -> throw new IllegalArgumentException("a TIFF page decodes to RGBA or channel pictures");
        }
    }

    /**
     * Scales the alpha, and premultiplied colors, of one row of a channel picture with an alpha
     * channel.
     *
     * @param picture the channel picture, with an alpha channel, whose row is scaled in place
     * @param y the row
     * @param sums the sum of the covered mask values of each pixel
     * @param counts the number of covered mask values of each pixel
     */
    private static void applyChannelRow(ChannelPicture picture, int y, long[] sums, long[] counts) {
        var layout = picture.layout();
        var width = picture.width();
        var alpha = layout.alphaIndex();
        var colors = layout.isAlphaPremultiplied() ? layout.colorChannels() : 0;
        var count = layout.count();
        switch (picture) {
            case Channel8Picture narrow -> {
                var pixels = narrow.pixels();
                for (var x = 0; x < width; x++) {
                    var base = y * narrow.stride() + x * count;
                    pixels[base + alpha] = (byte) scale(pixels[base + alpha] & 0xFF, sums[x], counts[x]);
                    for (var c = 0; c < colors; c++) {
                        pixels[base + c] = (byte) scale(pixels[base + c] & 0xFF, sums[x], counts[x]);
                    }
                }
            }
            case Channel16Picture wide -> {
                var pixels = wide.pixels();
                for (var x = 0; x < width; x++) {
                    var base = y * wide.stride() + x * count;
                    pixels[base + alpha] = (short) scale(pixels[base + alpha] & 0xFFFF, sums[x], counts[x]);
                    for (var c = 0; c < colors; c++) {
                        pixels[base + c] = (short) scale(pixels[base + c] & 0xFFFF, sums[x], counts[x]);
                    }
                }
            }
            case ChannelFloatPicture floating -> {
                var pixels = floating.pixels();
                for (var x = 0; x < width; x++) {
                    var base = y * floating.stride() + x * count;
                    var factor = (float) (sums[x] / (counts[x] * 255.0));
                    pixels[base + alpha] *= factor;
                    for (var c = 0; c < colors; c++) {
                        pixels[base + c] *= factor;
                    }
                }
            }
        }
    }

    /**
     * Scales a value by a coverage, rounding to nearest.
     *
     * @param value the value to scale, at most 65535
     * @param sum the sum of the covered mask values, at most {@code count * 255}
     * @param count the number of covered mask values, fewer than {@code 2^31}
     * @return {@code round(value * sum / (count * 255))}
     */
    private static int scale(int value, long sum, long count) {
        var denominator = count * 255;
        return (int) ((value * sum * 2 + denominator) / (denominator * 2));
    }
}
