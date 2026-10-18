package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Synthesizes the pictures and image files the still-image training workloads decode and encode,
 * deterministically.
 *
 * <p>The PNG, GIF, BMP and lossless JPEG files are written here rather than by Smalt's encoders,
 * so that they take paths Smalt's encoders never produce: every PNG row filter type alone and in
 * runs, GIF frames that update a window with transparent unchanged pixels, and BMP and lossless
 * JPEG, which Smalt does not encode.
 */
final class SyntheticImages {
    /**
     * The PNG row filter types {@link #cycledFilterPng} cycles through: Sub and Up rows, Average and
     * Paeth rows both alone and in pairs, and an unfiltered row.
     */
    private static final int[] FILTER_CYCLE = {1, 2, 3, 4, 4, 3, 3, 0, 4, 2, 1, 4};

    /**
     * Prevents instantiation.
     */
    private SyntheticImages() {
        throw new AssertionError();
    }

    /**
     * Returns an RGBA picture derived from a 4:2:0 picture, opaque or with a round alpha mask that
     * fades out towards the edges.
     *
     * @param source the source picture
     * @param alpha whether the picture has the alpha mask
     * @return the picture
     */
    static RgbaPicture rgba(YuvPicture source, boolean alpha) {
        var width = source.width();
        var height = source.height();
        var pixels = new byte[width * height * 4];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var luma = source.y()[y * source.yStride() + x] & 0xFF;
                var u = source.u()[(y >> 1) * source.uvStride() + (x >> 1)] & 0xFF;
                var o = (y * width + x) * 4;
                pixels[o] = (byte) luma;
                pixels[o + 1] = (byte) ((luma + u) >> 1);
                pixels[o + 2] = (byte) (255 - u);
                var distance = Math.hypot(x - width / 2.0, y - height / 2.0);
                pixels[o + 3] = alpha ? (byte) Math.clamp((int) ((height / 2.0 - distance) * 3), 0, 255) : (byte) 255;
            }
        }
        return RgbaPicture.of(width, height, width * 4, pixels, alpha);
    }

    /**
     * Returns the frames of a moving square shape with a round alpha mask, followed by a flat,
     * opaque frame whose blocks all quantize to zero, the branch real images take on solid
     * backgrounds.
     *
     * @param size the side of the frames in pixels
     * @param count the number of moving frames
     * @return {@code count + 1} pictures
     */
    static List<RgbaPicture> alphaFrames(int size, int count) {
        var frames = new ArrayList<RgbaPicture>();
        var half = size / 2;
        for (var picture : SyntheticVideo.natural(size, size, count)) {
            var pixels = new byte[size * size * 4];
            for (var y = 0; y < size; y++) {
                for (var x = 0; x < size; x++) {
                    var luma = picture.y()[y * picture.yStride() + x] & 0xFF;
                    var o = (y * size + x) * 4;
                    pixels[o] = (byte) luma;
                    pixels[o + 1] = (byte) (255 - luma);
                    pixels[o + 2] = (byte) (luma ^ x);
                    pixels[o + 3] = (byte) Math.clamp((int) ((half - 8 - Math.hypot(x - half, y - half)) * 4), 0, 255);
                }
            }
            frames.add(RgbaPicture.of(size, size, size * 4, pixels, true));
        }
        var flat = new byte[size * size * 4];
        for (var o = 0; o < flat.length; o += 4) {
            flat[o] = (byte) 200;
            flat[o + 1] = (byte) 40;
            flat[o + 2] = (byte) 90;
            flat[o + 3] = (byte) 255;
        }
        frames.add(RgbaPicture.of(size, size, size * 4, flat, true));
        return frames;
    }

    /**
     * Returns a 16-bit copy of an 8-bit picture, every sample scaled to the full 16-bit range and
     * the low byte varied, so that the result does not round back to the 8-bit picture exactly.
     *
     * @param picture the 8-bit source picture, not modified
     * @return the 16-bit picture
     */
    static Rgba16Picture deep(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var pixels = new short[width * height * 4];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width * 4; x++) {
                var value = picture.pixels()[y * picture.stride() + x] & 0xFF;
                pixels[y * width * 4 + x] = (short) ((value << 8) | ((value + x + y) & 0xFF));
            }
        }
        return Rgba16Picture.of(width, height, width * 4, pixels, picture.hasAlpha());
    }

    /**
     * Writes a picture as a bottom-up BMP: 32-bit BGRA with a {@code BITMAPV4HEADER} when the
     * picture has alpha, 24-bit BGR with a {@code BITMAPINFOHEADER} otherwise.
     *
     * @param picture the picture to write
     * @return the BMP file
     */
    static byte[] bmp(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var alpha = picture.hasAlpha();
        var headerSize = alpha ? 108 : 40;
        var rowSize = alpha ? width * 4 : (width * 3 + 3) & ~3;
        var offset = 14 + headerSize;
        var file = ByteBuffer.allocate(offset + rowSize * height).order(ByteOrder.LITTLE_ENDIAN);
        file.put((byte) 'B').put((byte) 'M').putInt(file.capacity()).putInt(0).putInt(offset);
        file.putInt(headerSize).putInt(width).putInt(height).putShort((short) 1).putShort((short) (alpha ? 32 : 24))
                .putInt(alpha ? 3 : 0).putInt(rowSize * height).putInt(2835).putInt(2835).putInt(0).putInt(0);
        if (alpha) {
            file.putInt(0x00FF0000).putInt(0x0000FF00).putInt(0x000000FF).putInt(0xFF000000).putInt(0x73524742);
            file.position(14 + headerSize);
        }
        for (var y = height - 1; y >= 0; y--) {
            var start = file.position();
            for (var x = 0; x < width; x++) {
                var o = y * picture.stride() + x * 4;
                file.put(picture.pixels()[o + 2]).put(picture.pixels()[o + 1]).put(picture.pixels()[o]);
                if (alpha) {
                    file.put(picture.pixels()[o + 3]);
                }
            }
            file.position(start + rowSize);
        }
        return file.array();
    }

    /**
     * Writes an 8-bit RGB PNG, or RGBA when the picture has alpha, whose rows cycle through the
     * filter types of {@link #FILTER_CYCLE}.
     *
     * @param picture the picture to write
     * @return the PNG file
     */
    static byte[] cycledFilterPng(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var bpp = picture.hasAlpha() ? 4 : 3;
        var rowBytes = width * bpp;
        var raw = new byte[height * (rowBytes + 1)];
        var previous = new byte[rowBytes];
        var current = new byte[rowBytes];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                System.arraycopy(picture.pixels(), y * picture.stride() + 4 * x, current, bpp * x, bpp);
            }
            var type = FILTER_CYCLE[y % FILTER_CYCLE.length];
            var o = y * (rowBytes + 1);
            raw[o] = (byte) type;
            for (var i = 0; i < rowBytes; i++) {
                var a = i >= bpp ? current[i - bpp] & 0xFF : 0;
                var b = previous[i] & 0xFF;
                var c = i >= bpp ? previous[i - bpp] & 0xFF : 0;
                raw[o + 1 + i] = (byte) ((current[i] & 0xFF) - predictor(type, a, b, c));
            }
            var swap = previous;
            previous = current;
            current = swap;
        }
        var deflater = new Deflater(6);
        deflater.setInput(raw);
        deflater.finish();
        var compressed = new ByteArrayOutputStream();
        var buffer = new byte[65536];
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 0x1A, 10});
        var header = ByteBuffer.allocate(13).putInt(width).putInt(height)
                .put((byte) 8).put((byte) (bpp == 4 ? 6 : 2)).put((byte) 0).put((byte) 0).put((byte) 0).array();
        chunk(out, "IHDR", header);
        chunk(out, "IDAT", compressed.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /**
     * Returns the prediction of a PNG row filter (ISO/IEC 15948 clause 9).
     *
     * @param type the filter type, from 0 to 4
     * @param a the sample to the left
     * @param b the sample above
     * @param c the sample above and to the left
     * @return the prediction
     */
    private static int predictor(int type, int a, int b, int c) {
        return switch (type) {
            case 1 -> a;
            case 2 -> b;
            case 3 -> (a + b) >> 1;
            case 4 -> {
                var p = a + b - c;
                var pa = Math.abs(p - a);
                var pb = Math.abs(p - b);
                var pc = Math.abs(p - c);
                yield pa <= pb && pa <= pc ? a : pb <= pc ? b : c;
            }
            default -> 0;
        };
    }

    /**
     * Writes one PNG chunk: its length, type, data and CRC.
     *
     * @param out the file being written
     * @param type the four-letter chunk type
     * @param data the chunk data
     */
    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        var name = type.getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32();
        crc.update(name);
        crc.update(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        out.writeBytes(name);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }

    /**
     * Returns the samples of a lossless JPEG of a picture: its green channel for one component or
     * its red, green and blue channels for three, scaled from 8 bits to the precision with the low
     * bits varied, so that no two precisions code the same values.
     *
     * @param picture the 8-bit source picture, not modified
     * @param components the number of components, 1 or 3
     * @param precision the sample precision, from 2 to 16
     * @return the samples, row-major and component-interleaved
     */
    static int[] losslessSamples(RgbaPicture picture, int components, int precision) {
        var width = picture.width();
        var height = picture.height();
        var samples = new int[width * height * components];
        var low = (1 << Math.max(0, precision - 8)) - 1;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                for (var c = 0; c < components; c++) {
                    var channel = components == 1 ? 1 : c;
                    var value = picture.pixels()[y * picture.stride() + x * 4 + channel] & 0xFF;
                    samples[(y * width + x) * components + c] = precision >= 8
                            ? (value << (precision - 8)) | ((value + x + y) & low)
                            : value >> (8 - precision);
                }
            }
        }
        return samples;
    }

    /**
     * Writes a lossless JPEG (SOF3) with one interleaved scan, no point transform and a Huffman
     * table in which every difference category has a 5-bit code.
     *
     * @param samples the samples, row-major and component-interleaved, each below
     * {@code 1 << precision}
     * @param width the width in pixels
     * @param height the height in pixels
     * @param ids the identifier of each component, which selects RGB for {@code 'R'}, {@code 'G'},
     * {@code 'B'}
     * @param precision the sample precision, from 2 to 16
     * @param predictor the predictor, from 1 to 7
     * @param restartRows the rows of each restart interval, or 0 for none
     * @return the JPEG file
     */
    static byte[] losslessJpeg(int[] samples, int width, int height, int[] ids, int precision, int predictor,
                               int restartRows) {
        var components = ids.length;
        var out = new ByteArrayOutputStream();
        marker(out, 0xD8);
        marker(out, 0xC3);
        u16be(out, 8 + 3 * components);
        out.write(precision);
        u16be(out, height);
        u16be(out, width);
        out.write(components);
        for (var id : ids) {
            out.write(id);
            out.write(0x11);
            out.write(0);
        }
        marker(out, 0xC4);
        u16be(out, 2 + 17 + 17);
        out.write(0);
        for (var length = 1; length <= 16; length++) {
            out.write(length == 5 ? 17 : 0);
        }
        for (var category = 0; category <= 16; category++) {
            out.write(category);
        }
        if (restartRows > 0) {
            marker(out, 0xDD);
            u16be(out, 4);
            u16be(out, restartRows * width);
        }
        marker(out, 0xDA);
        u16be(out, 6 + 2 * components);
        out.write(components);
        for (var id : ids) {
            out.write(id);
            out.write(0);
        }
        out.write(predictor);
        out.write(0);
        out.write(0);
        var bits = new HuffmanBits(out);
        var stride = width * components;
        var initial = 1 << (precision - 1);
        var interval = 0;
        for (var row = 0; row < height; row++) {
            var first = restartRows > 0 ? row % restartRows == 0 : row == 0;
            if (first && row > 0) {
                bits.flush();
                marker(out, 0xD0 + (interval++ & 7));
            }
            for (var column = 0; column < width; column++) {
                for (var c = 0; c < components; c++) {
                    var index = row * stride + column * components + c;
                    var difference = (samples[index] - prediction(samples, index, first, column, components, stride,
                            predictor, initial)) & 0xFFFF;
                    if (difference >= 32768) {
                        difference -= 65536;
                    }
                    if (difference == -32768) {
                        bits.write(16, 5);
                        continue;
                    }
                    var category = 32 - Integer.numberOfLeadingZeros(Math.abs(difference));
                    bits.write(category, 5);
                    if (category > 0) {
                        bits.write(difference >= 0 ? difference : difference + (1 << category) - 1, category);
                    }
                }
            }
        }
        bits.flush();
        marker(out, 0xD9);
        return out.toByteArray();
    }

    /**
     * Returns the prediction of a lossless JPEG sample: the initial value at the start of the image
     * or of a restart interval, the left neighbor on the first row of either, the upper neighbor at
     * the start of other rows, and the predictor's combination elsewhere.
     *
     * @param samples the samples, row-major and component-interleaved
     * @param index the index of the sample
     * @param first whether the row is the first of the image or of a restart interval
     * @param column the column of the sample
     * @param components the number of components
     * @param stride the samples per row
     * @param predictor the predictor, from 1 to 7
     * @param initial the prediction of the first sample, {@code 1 << (precision - 1)}
     * @return the prediction
     */
    private static int prediction(int[] samples, int index, boolean first, int column, int components, int stride,
                                  int predictor, int initial) {
        if (first) {
            return column == 0 ? initial : samples[index - components];
        }
        if (column == 0) {
            return samples[index - stride];
        }
        var a = samples[index - components];
        var b = samples[index - stride];
        var c = samples[index - stride - components];
        return switch (predictor) {
            case 1 -> a;
            case 2 -> b;
            case 3 -> c;
            case 4 -> a + b - c;
            case 5 -> a + ((b - c) >> 1);
            case 6 -> b + ((a - c) >> 1);
            default -> (a + b) >> 1;
        };
    }

    /**
     * Writes a JPEG marker.
     *
     * @param out the file being written
     * @param marker the marker code, after the {@code FF} byte
     */
    private static void marker(ByteArrayOutputStream out, int marker) {
        out.write(0xFF);
        out.write(marker);
    }

    /**
     * Writes a 16-bit big-endian value.
     *
     * @param out the file being written
     * @param value the value, of which the low 16 bits are written
     */
    private static void u16be(ByteArrayOutputStream out, int value) {
        out.write(value >>> 8);
        out.write(value);
    }

    /**
     * Packs the bits of JPEG entropy-coded data, most significant bit first, stuffing a zero byte
     * after every {@code FF} byte.
     */
    private static final class HuffmanBits {
        private final ByteArrayOutputStream out;
        private long buffer;
        private int count;

        private HuffmanBits(ByteArrayOutputStream out) {
            this.out = out;
        }

        private void write(int value, int bits) {
            buffer = (buffer << bits) | (value & ((1L << bits) - 1));
            count += bits;
            while (count >= 8) {
                var b = (int) (buffer >>> (count - 8)) & 0xFF;
                out.write(b);
                if (b == 0xFF) {
                    out.write(0);
                }
                count -= 8;
            }
        }

        /**
         * Pads the last byte with one bits, as the end of a scan or of a restart interval needs.
         */
        private void flush() {
            if (count > 0) {
                write((1 << (8 - count)) - 1, 8 - count);
            }
            buffer = 0;
            count = 0;
        }
    }

    /**
     * Writes a looping animated GIF: a full first frame, then frames that update a moving 200x150
     * window and leave the pixels that did not change transparent, every frame quantized to a
     * 6x6x6 color cube.
     *
     * @param width the width in pixels, more than 200
     * @param height the height in pixels, more than 150
     * @param count the number of frames
     * @return the GIF file
     */
    static byte[] animatedGif(int width, int height, int count) {
        var frames = SyntheticVideo.natural(width, height, count);
        var out = new ByteArrayOutputStream();
        out.writeBytes("GIF89a".getBytes(StandardCharsets.US_ASCII));
        u16(out, width);
        u16(out, height);
        out.write(0xF7);
        out.write(0);
        out.write(0);
        for (var i = 0; i < 256; i++) {
            var color = i < 216 ? i : 0;
            out.write(color / 36 * 51);
            out.write(color / 6 % 6 * 51);
            out.write(color % 6 * 51);
        }
        out.write(0x21);
        out.write(0xFF);
        out.write(11);
        out.writeBytes("NETSCAPE2.0".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[]{3, 1, 0, 0, 0});
        var previous = new byte[width * height];
        for (var t = 0; t < count; t++) {
            var picture = frames.get(t);
            var indices = new byte[width * height];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var luma = picture.y()[y * picture.yStride() + x] & 0xFF;
                    var u = picture.u()[(y >> 1) * picture.uvStride() + (x >> 1)] & 0xFF;
                    var v = picture.v()[(y >> 1) * picture.uvStride() + (x >> 1)] & 0xFF;
                    indices[y * width + x] = (byte) ((luma * 6 >> 8) * 36 + (u * 6 >> 8) * 6 + (v * 6 >> 8));
                }
            }
            var left = t == 0 ? 0 : (t * 23) % (width - 200);
            var top = t == 0 ? 0 : (t * 11) % (height - 150);
            var w = t == 0 ? width : 200;
            var h = t == 0 ? height : 150;
            var rectangle = new byte[w * h];
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    var index = (top + y) * width + left + x;
                    rectangle[y * w + x] = t > 0 && indices[index] == previous[index] ? (byte) 255 : indices[index];
                    previous[index] = indices[index];
                }
            }
            out.writeBytes(new byte[]{0x21, (byte) 0xF9, 4, (byte) (1 << 2 | (t > 0 ? 1 : 0)), 4, 0, (byte) 255, 0});
            out.write(0x2C);
            u16(out, left);
            u16(out, top);
            u16(out, w);
            u16(out, h);
            out.write(0);
            out.write(8);
            var data = lzw(rectangle, 8);
            for (var offset = 0; offset < data.length; offset += 255) {
                var size = Math.min(255, data.length - offset);
                out.write(size);
                out.write(data, offset, size);
            }
            out.write(0);
        }
        out.write(0x3B);
        return out.toByteArray();
    }

    /**
     * Writes a 16-bit little-endian value.
     *
     * @param out the file being written
     * @param value the value, of which the low 16 bits are written
     */
    private static void u16(ByteArrayOutputStream out, int value) {
        out.write(value);
        out.write(value >>> 8);
    }

    /**
     * LZW-encodes GIF color indices, emitting a clear code whenever the code table fills.
     *
     * @param indices the color indices
     * @param minimumCodeSize the minimum code size of the image data
     * @return the packed codes, without the sub-block framing
     */
    private static byte[] lzw(byte[] indices, int minimumCodeSize) {
        var out = new ByteArrayOutputStream();
        long bits = 0;
        var count = 0;
        var clear = 1 << minimumCodeSize;
        var codeSize = minimumCodeSize + 1;
        var maxCode = clear + 1;
        var table = new HashMap<Integer, Integer>();
        var codes = new ArrayList<int[]>(List.of(new int[]{clear, codeSize}));
        var current = -1;
        for (var value : indices) {
            var index = value & 0xFF;
            if (current < 0) {
                current = index;
                continue;
            }
            var found = table.get(current << 8 | index);
            if (found != null) {
                current = found;
                continue;
            }
            codes.add(new int[]{current, codeSize});
            table.put(current << 8 | index, ++maxCode);
            if (maxCode >= (1 << codeSize) && codeSize < 12) {
                codeSize++;
            }
            if (maxCode == 4095) {
                codes.add(new int[]{clear, codeSize});
                table.clear();
                codeSize = minimumCodeSize + 1;
                maxCode = clear + 1;
            }
            current = index;
        }
        codes.add(new int[]{current, codeSize});
        codes.add(new int[]{clear + 1, codeSize});
        for (var code : codes) {
            bits |= (long) code[0] << count;
            count += code[1];
            while (count >= 8) {
                out.write((int) bits & 0xFF);
                bits >>>= 8;
                count -= 8;
            }
        }
        if (count > 0) {
            out.write((int) bits & 0xFF);
        }
        return out.toByteArray();
    }
}
