package com.github.auties00.smalt.image.hdr;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.internal.raster.RasterInput;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Encodes pictures as Radiance RGBE (HDR) images.
 *
 * <p>The red, green and blue values are written as they are, taken as linear light in the BT.709 primaries with the
 * D65 white, which the header's {@code PRIMARIES} line records: the values of a
 * {@link com.github.auties00.smalt.image.ChannelFloatPicture ChannelFloatPicture}, integer samples divided by their
 * largest value, a gray picture's samples replicated; alpha is dropped. A pixel is coded as the shared exponent of
 * its largest component: with {@code m} that component, {@code m = f * 2^e} and {@code f} in {@code [0.5, 1)}, the
 * mantissas are {@code floor(c * 256 * f / m)} and the exponent byte {@code e + 128}; a pixel whose largest
 * component is below {@code 1e-32}, not finite, or negative is written as zero, and negative components as zero.
 * The resolution string is {@code -Y height +X width}.
 *
 * <p>This class is thread-safe.
 */
public final class HdrEncoder {
    private HdrEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture with the {@link HdrEncoderOptions#defaults()}.
     *
     * @param picture the picture to encode
     * @return the complete file
     * @see #encode(Picture, HdrEncoderOptions)
     */
    public static byte[] encode(Picture picture) {
        return encode(picture, HdrEncoderOptions.defaults());
    }

    /**
     * Encodes a picture.
     *
     * @param picture the picture to encode
     * @param options the scanline coding
     * @return the complete file
     */
    public static byte[] encode(Picture picture, HdrEncoderOptions options) {
        var sink = MediaSink.memory();
        encode(picture, options, sink);
        return sink.toByteArray();
    }

    /**
     * Encodes a picture and writes it to a sink.
     *
     * <p>The bytes written are those {@link #encode(Picture, HdrEncoderOptions)} returns.
     *
     * @param picture the picture to encode
     * @param options the scanline coding
     * @param sink the output, written from its position; not closed
     * @throws com.github.auties00.smalt.MediaException.Io if writing the sink fails
     */
    public static void encode(Picture picture, HdrEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        try (var _ = ParallelBands.operation()) {
            var floats = RasterInput.floats(picture, false, false);
            var width = floats.width();
            var height = floats.height();
            var pixels = floats.pixels();
            var writer = new ByteWriter(width * height * 4 + 256);
            ascii(writer, "#?RADIANCE\nFORMAT=32-bit_rle_rgbe\n"
                    + "PRIMARIES=0.6400 0.3300 0.3000 0.6000 0.1500 0.0600 0.3127 0.3290\n\n-Y " + height + " +X "
                    + width + "\n");
            var line = new byte[width * 4];
            var runLength = options.runLength() && width >= 8 && width <= 0x7FFF;
            var component = new byte[width];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var at = (y * width + x) * 3;
                    rgbe(pixels[at], pixels[at + 1], pixels[at + 2], line, 4 * x);
                }
                if (!runLength) {
                    writer.bytes(line);
                    continue;
                }
                writer.u8(2);
                writer.u8(2);
                writer.u8(width >>> 8);
                writer.u8(width & 0xFF);
                for (var channel = 0; channel < 4; channel++) {
                    for (var x = 0; x < width; x++) {
                        component[x] = line[4 * x + channel];
                    }
                    runs(writer, component);
                }
            }
            writer.writeTo(sink);
        }
    }

    /**
     * Codes one pixel as RGBE.
     */
    private static void rgbe(float r, float g, float b, byte[] out, int at) {
        var red = r > 0 ? r : 0f;
        var green = g > 0 ? g : 0f;
        var blue = b > 0 ? b : 0f;
        var max = Math.max(red, Math.max(green, blue));
        if (!(max >= 1e-32f) || !Float.isFinite(max)) {
            out[at] = 0;
            out[at + 1] = 0;
            out[at + 2] = 0;
            out[at + 3] = 0;
            return;
        }
        // frexp: max = fraction * 2^exponent with fraction in [0.5, 1)
        var exponent = Math.getExponent(max) + 1;
        var fraction = Math.scalb((double) max, -exponent);
        var scale = fraction * 256.0 / max;
        out[at] = (byte) Math.min(255, (int) (red * scale));
        out[at + 1] = (byte) Math.min(255, (int) (green * scale));
        out[at + 2] = (byte) Math.min(255, (int) (blue * scale));
        out[at + 3] = (byte) Math.clamp(exponent + 128, 0, 255);
    }

    /**
     * Writes one component of a scanline with the adaptive run-length coding: runs of at least four equal bytes as
     * run packets of up to 127 bytes, the bytes between them as literal packets of up to 128.
     */
    private static void runs(ByteWriter writer, byte[] data) {
        var length = data.length;
        var current = 0;
        while (current < length) {
            var begin = current;
            var run = 0;
            while (begin < length) {
                run = 1;
                while (begin + run < length && run < 127 && data[begin + run] == data[begin]) {
                    run++;
                }
                if (run >= 4) {
                    break;
                }
                begin += run;
                run = 0;
            }
            while (current < begin) {
                var count = Math.min(128, begin - current);
                writer.u8(count);
                writer.bytes(data, current, count);
                current += count;
            }
            if (run >= 4) {
                writer.u8(128 + run);
                writer.u8(data[begin]);
                current = begin + run;
            }
        }
    }

    /**
     * Writes ASCII text.
     */
    private static void ascii(ByteWriter writer, String text) {
        writer.bytes(text.getBytes(StandardCharsets.US_ASCII));
    }
}
