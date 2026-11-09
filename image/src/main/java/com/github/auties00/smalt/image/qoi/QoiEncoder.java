package com.github.auties00.smalt.image.qoi;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.util.Objects;

/**
 * Encodes pictures as Quite OK Image (QOI) files.
 *
 * <p>The picture is reduced to 8-bit RGBA as {@link ImageOps#toRgba(Picture)} reduces it and coded losslessly. The
 * output equals that of the reference encoder {@code qoi.h} byte for byte, end marker included.
 *
 * <p>This class is thread-safe.
 */
public final class QoiEncoder {
    /**
     * The end marker: seven zero bytes and a one.
     */
    private static final byte[] END = {0, 0, 0, 0, 0, 0, 0, 1};

    private QoiEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture with the {@link QoiEncoderOptions#defaults()}.
     *
     * @param picture the picture to encode
     * @return the complete file
     * @see #encode(Picture, QoiEncoderOptions)
     */
    public static byte[] encode(Picture picture) {
        return encode(picture, QoiEncoderOptions.defaults());
    }

    /**
     * Encodes a picture.
     *
     * @param picture the picture to encode
     * @param options the channel count and the colorspace label
     * @return the complete file
     */
    public static byte[] encode(Picture picture, QoiEncoderOptions options) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(options, "options");
        try (var _ = ParallelBands.operation()) {
            var rgba = ImageOps.toRgba(picture);
            var width = rgba.width();
            var height = rgba.height();
            var channels = options.channels().orElse(rgba.hasAlpha() ? 4 : 3);
            var pixels = rgba.pixels();
            var stride = rgba.stride();
            var out = new byte[(int) Math.min(Integer.MAX_VALUE - 16, 14 + (long) width * height * (channels + 1)
                    + END.length)];
            out[0] = 'q';
            out[1] = 'o';
            out[2] = 'i';
            out[3] = 'f';
            putBe(out, 4, width);
            putBe(out, 8, height);
            out[12] = (byte) channels;
            out[13] = (byte) (options.linear() ? 1 : 0);
            var position = 14;
            var index = new int[64];
            var previous = 0x000000FF;
            var run = 0;
            var last = width * height - 1;
            var count = 0;
            for (var y = 0; y < height; y++) {
                var row = y * stride;
                for (var x = 0; x < width; x++, count++) {
                    var at = row + 4 * x;
                    var r = pixels[at] & 0xFF;
                    var g = pixels[at + 1] & 0xFF;
                    var b = pixels[at + 2] & 0xFF;
                    var a = channels == 4 ? pixels[at + 3] & 0xFF : 255;
                    var pixel = r << 24 | g << 16 | b << 8 | a;
                    if (pixel == previous) {
                        run++;
                        if (run == 62 || count == last) {
                            out[position++] = (byte) (0xC0 | (run - 1));
                            run = 0;
                        }
                        continue;
                    }
                    if (run > 0) {
                        out[position++] = (byte) (0xC0 | (run - 1));
                        run = 0;
                    }
                    var slot = (r * 3 + g * 5 + b * 7 + a * 11) & 63;
                    if (index[slot] == pixel) {
                        out[position++] = (byte) slot;
                    } else {
                        index[slot] = pixel;
                        var pa = previous & 0xFF;
                        if (a == pa) {
                            var vr = (byte) (r - (previous >>> 24));
                            var vg = (byte) (g - ((previous >>> 16) & 0xFF));
                            var vb = (byte) (b - ((previous >>> 8) & 0xFF));
                            // the differences wrap as signed bytes, as in the reference encoder
                            var vgr = (byte) (vr - vg);
                            var vgb = (byte) (vb - vg);
                            if (vr > -3 && vr < 2 && vg > -3 && vg < 2 && vb > -3 && vb < 2) {
                                out[position++] = (byte) (0x40 | (vr + 2) << 4 | (vg + 2) << 2 | (vb + 2));
                            } else if (vgr > -9 && vgr < 8 && vg > -33 && vg < 32 && vgb > -9 && vgb < 8) {
                                out[position++] = (byte) (0x80 | (vg + 32));
                                out[position++] = (byte) ((vgr + 8) << 4 | (vgb + 8));
                            } else {
                                out[position++] = (byte) 0xFE;
                                out[position++] = (byte) r;
                                out[position++] = (byte) g;
                                out[position++] = (byte) b;
                            }
                        } else {
                            out[position++] = (byte) 0xFF;
                            out[position++] = (byte) r;
                            out[position++] = (byte) g;
                            out[position++] = (byte) b;
                            out[position++] = (byte) a;
                        }
                    }
                    previous = pixel;
                }
            }
            System.arraycopy(END, 0, out, position, END.length);
            position += END.length;
            return java.util.Arrays.copyOf(out, position);
        }
    }

    /**
     * Encodes a picture and writes it to a sink.
     *
     * <p>The bytes written are those {@link #encode(Picture, QoiEncoderOptions)} returns.
     *
     * @param picture the picture to encode
     * @param options the channel count and the colorspace label
     * @param sink the output, written from its position; not closed
     * @throws com.github.auties00.smalt.MediaException.Io if writing the sink fails
     */
    public static void encode(Picture picture, QoiEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(sink, "sink");
        sink.write(encode(picture, options));
    }

    /**
     * Stores a big-endian 32-bit value.
     */
    private static void putBe(byte[] data, int offset, int value) {
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }
}
