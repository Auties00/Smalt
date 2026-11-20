package com.github.auties00.smalt.image.tga;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.internal.raster.RasterInput;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.task.ParallelBands;
import com.github.auties00.smalt.io.MediaSink;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Encodes pictures as Truevision TGA 2.0 images.
 *
 * <p>A gray channel picture without alpha is written as 8-bit gray (image type 3 or 11); any other picture is reduced
 * to 8-bit RGBA as {@link ImageOps#toRgba(Picture)} reduces it and written as 24-bit BGR, or as 32-bit BGRA with 8
 * attribute bits when it has alpha (type 2 or 10). Every file ends with a TGA 2.0 footer; a file with alpha also
 * carries an extension area whose attributes type (3) declares the attribute bits straight alpha. The output decodes
 * with {@link TgaDecoder} to the pixels written.
 *
 * <p>This class is thread-safe.
 */
public final class TgaEncoder {
    /**
     * The size of an extension area.
     */
    private static final int EXTENSION = 495;

    private TgaEncoder() {
        throw new AssertionError();
    }

    /**
     * Encodes a picture with the {@link TgaEncoderOptions#defaults()}.
     *
     * @param picture the picture, at most 65535 pixels on each side
     * @return the complete file
     * @throws MediaException.LimitExceeded if a side of the picture exceeds 65535 pixels
     * @see #encode(Picture, TgaEncoderOptions)
     */
    public static byte[] encode(Picture picture) {
        return encode(picture, TgaEncoderOptions.defaults());
    }

    /**
     * Encodes a picture.
     *
     * @param picture the picture, at most 65535 pixels on each side
     * @param options the coding and the row order
     * @return the complete file
     * @throws MediaException.LimitExceeded if a side of the picture exceeds 65535 pixels
     */
    public static byte[] encode(Picture picture, TgaEncoderOptions options) {
        var sink = MediaSink.memory();
        encode(picture, options, sink);
        return sink.toByteArray();
    }

    /**
     * Encodes a picture and writes it to a sink.
     *
     * <p>The bytes written are those {@link #encode(Picture, TgaEncoderOptions)} returns.
     *
     * @param picture the picture, at most 65535 pixels on each side
     * @param options the coding and the row order
     * @param sink the output, written from its position; not closed
     * @throws MediaException.LimitExceeded if a side of the picture exceeds 65535 pixels
     * @throws MediaException.Io if writing the sink fails
     */
    public static void encode(Picture picture, TgaEncoderOptions options, MediaSink sink) {
        Objects.requireNonNull(picture, "picture");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(sink, "sink");
        try (var _ = ParallelBands.operation()) {
            var width = picture.width();
            var height = picture.height();
            if (width > 65535 || height > 65535) {
                throw new MediaException.LimitExceeded("a TGA image is at most 65535 pixels on each side, not "
                        + width + "x" + height);
            }
            var gray = RasterInput.isGray(picture) && !picture.hasAlpha();
            var alpha = !gray && picture.hasAlpha();
            var bytes = gray ? 1 : alpha ? 4 : 3;
            var rows = new byte[width * height * bytes];
            if (gray) {
                var samples = RasterInput.samples(picture, true, false).pixels();
                for (var index = 0; index < rows.length; index++) {
                    rows[index] = (byte) (((samples[index] & 0xFFFF) * 255 + 32767) / 65535);
                }
            } else {
                var rgba = ImageOps.toRgba(picture);
                var pixels = rgba.pixels();
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        var in = y * rgba.stride() + 4 * x;
                        var out = (y * width + x) * bytes;
                        rows[out] = pixels[in + 2];
                        rows[out + 1] = pixels[in + 1];
                        rows[out + 2] = pixels[in];
                        if (alpha) {
                            rows[out + 3] = pixels[in + 3];
                        }
                    }
                }
            }
            var writer = new ByteWriter(18 + rows.length + 64);
            writer.u8(0);
            writer.u8(0);
            writer.u8((gray ? 3 : 2) | (options.runLength() ? 8 : 0));
            writer.zeros(5);
            writer.u16le(0);
            writer.u16le(0);
            writer.u16le(width);
            writer.u16le(height);
            writer.u8(bytes * 8);
            writer.u8((alpha ? 8 : 0) | (options.topDown() ? 0x20 : 0));
            var rowBytes = width * bytes;
            for (var stored = 0; stored < height; stored++) {
                var y = options.topDown() ? stored : height - 1 - stored;
                if (options.runLength()) {
                    runLength(writer, rows, y * rowBytes, width, bytes);
                } else {
                    writer.bytes(rows, y * rowBytes, rowBytes);
                }
            }
            var extension = 0L;
            if (alpha) {
                extension = writer.size();
                writer.u16le(EXTENSION);
                writer.zeros(EXTENSION - 3);
                writer.u8(3);
            }
            writer.u32le(extension);
            writer.u32le(0);
            writer.bytes("TRUEVISION-XFILE.\0".getBytes(StandardCharsets.US_ASCII));
            writer.writeTo(sink);
        }
    }

    /**
     * Writes one scanline as run-length packets: a run of two or more equal pixels as a run packet, other pixels in
     * raw packets, at most 128 pixels per packet.
     */
    private static void runLength(ByteWriter writer, byte[] rows, int start, int width, int bytes) {
        var x = 0;
        while (x < width) {
            var run = 1;
            while (x + run < width && run < 128 && same(rows, start + x * bytes, start + (x + run) * bytes, bytes)) {
                run++;
            }
            if (run >= 2) {
                writer.u8(0x80 | (run - 1));
                writer.bytes(rows, start + x * bytes, bytes);
                x += run;
                continue;
            }
            var raw = 1;
            while (x + raw < width && raw < 128 && !(x + raw + 1 < width
                    && same(rows, start + (x + raw) * bytes, start + (x + raw + 1) * bytes, bytes))) {
                raw++;
            }
            writer.u8(raw - 1);
            writer.bytes(rows, start + x * bytes, raw * bytes);
            x += raw;
        }
    }

    /**
     * {@return whether two pixels are equal}
     */
    private static boolean same(byte[] data, int first, int second, int bytes) {
        for (var index = 0; index < bytes; index++) {
            if (data[first + index] != data[second + index]) {
                return false;
            }
        }
        return true;
    }
}
