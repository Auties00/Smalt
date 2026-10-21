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
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Synthesizes the pictures and image files the still-image training workloads decode and encode,
 * deterministically.
 *
 * <p>The PNG, GIF, BMP and lossless JPEG files are written or rewritten here rather than by Smalt's
 * encoders, so that they take paths Smalt's encoders never produce: every PNG row filter type alone and in
 * runs, GIF frames that update a window with transparent unchanged pixels, and 64-bit, 10-bit mask and CMYK
 * BMP, lossless JPEG, 12-bit JPEG, JPEG-compressed TIFF and the rarer TIFF sample layouts, which Smalt does
 * not encode.
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
     * Rewrites the color masks of a 32-bit BMP with a V4 or V5 header as 10-10-10-2, a layout Smalt decodes
     * but does not encode.
     *
     * @param argb a 32-bit {@code BI_BITFIELDS} file with a V4 or V5 header, not modified
     * @return the file with 10-bit red, green and blue and 2-bit alpha masks
     */
    static byte[] bmpMasks1010102(byte[] argb) {
        var file = argb.clone();
        ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).putInt(54, 0x3FF0_0000).putInt(58, 0x000F_FC00)
                .putInt(62, 0x0000_03FF).putInt(66, 0xC000_0000);
        return file;
    }

    /**
     * Marks the pixels of an uncompressed 32-bit BMP as CMYK ({@code BI_CMYK}), a layout Smalt decodes but
     * does not encode.
     *
     * @param xrgb a 32-bit {@code BI_RGB} file, not modified
     * @return the file with the {@code BI_CMYK} compression
     */
    static byte[] bmpCmyk(byte[] xrgb) {
        var file = xrgb.clone();
        ByteBuffer.wrap(file).order(ByteOrder.LITTLE_ENDIAN).putInt(30, 11);
        return file;
    }

    /**
     * Writes a picture as a bottom-up 64-bit BMP, the layout Smalt decodes but does not encode: linear-light
     * s2.13 blue, green, red and alpha, the color decoded from sRGB.
     *
     * @param picture the picture to write
     * @return the BMP file
     */
    static byte[] bmp64(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var offset = 14 + 40;
        var file = ByteBuffer.allocate(offset + width * 8 * height).order(ByteOrder.LITTLE_ENDIAN);
        file.put((byte) 'B').put((byte) 'M').putInt(file.capacity()).putInt(0).putInt(offset);
        file.putInt(40).putInt(width).putInt(height).putShort((short) 1).putShort((short) 64).putInt(0)
                .putInt(width * 8 * height).putInt(0).putInt(0).putInt(0).putInt(0);
        var linear = new short[256];
        for (var value = 0; value < 256; value++) {
            var encoded = value / 255.0;
            var decoded = encoded <= 0.04045 ? encoded / 12.92 : Math.pow((encoded + 0.055) / 1.055, 2.4);
            linear[value] = (short) Math.round(decoded * 8192);
        }
        for (var y = height - 1; y >= 0; y--) {
            for (var x = 0; x < width; x++) {
                var o = y * picture.stride() + x * 4;
                file.putShort(linear[picture.pixels()[o + 2] & 0xFF]).putShort(linear[picture.pixels()[o + 1] & 0xFF])
                        .putShort(linear[picture.pixels()[o] & 0xFF])
                        .putShort((short) ((picture.pixels()[o + 3] & 0xFF) * 8192 / 255));
            }
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
     * Writes a lossless JFIF JPEG (SOF3) of 8-bit Y'CbCr 4:2:0, the luma of factors 2x2 and the
     * chroma of factors 1x1, as one block-interleaved scan with predictor 1 and the Huffman table
     * of {@link #losslessJpeg}.
     *
     * @param picture the 8-bit source picture of even width and height, not modified; its green,
     * blue and red channels stand for the luma and the two chroma components
     * @return the JPEG file
     */
    static byte[] subsampledLosslessJpeg(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var planes = new int[][]{new int[width * height], new int[width * height / 4], new int[width * height / 4]};
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + x * 4;
                planes[0][y * width + x] = picture.pixels()[at + 1] & 0xFF;
                if ((x & 1) == 0 && (y & 1) == 0) {
                    planes[1][(y / 2) * (width / 2) + x / 2] = picture.pixels()[at + 2] & 0xFF;
                    planes[2][(y / 2) * (width / 2) + x / 2] = picture.pixels()[at] & 0xFF;
                }
            }
        }
        var out = new ByteArrayOutputStream();
        marker(out, 0xD8);
        marker(out, 0xE0);
        out.writeBytes(new byte[]{0, 16, 'J', 'F', 'I', 'F', 0, 1, 2, 0, 0, 1, 0, 1, 0, 0});
        marker(out, 0xC3);
        u16be(out, 17);
        out.write(8);
        u16be(out, height);
        u16be(out, width);
        out.write(3);
        for (var c = 0; c < 3; c++) {
            out.write(c + 1);
            out.write(c == 0 ? 0x22 : 0x11);
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
        marker(out, 0xDA);
        u16be(out, 12);
        out.write(3);
        for (var c = 0; c < 3; c++) {
            out.write(c + 1);
            out.write(0);
        }
        out.write(1);
        out.write(0);
        out.write(0);
        var bits = new HuffmanBits(out);
        var widths = new int[]{width, width / 2, width / 2};
        var factors = new int[]{2, 1, 1};
        for (var unitRow = 0; unitRow < height / 2; unitRow++) {
            for (var unit = 0; unit < width / 2; unit++) {
                for (var c = 0; c < 3; c++) {
                    var plane = planes[c];
                    var stride = widths[c];
                    for (var by = 0; by < factors[c]; by++) {
                        for (var bx = 0; bx < factors[c]; bx++) {
                            var x = unit * factors[c] + bx;
                            var y = unitRow * factors[c] + by;
                            var index = y * stride + x;
                            int prediction;
                            if (y == 0) {
                                prediction = x == 0 ? 128 : plane[index - 1];
                            } else {
                                prediction = x == 0 ? plane[index - stride] : plane[index - 1];
                            }
                            var difference = plane[index] - prediction;
                            var category = 32 - Integer.numberOfLeadingZeros(Math.abs(difference));
                            bits.write(category, 5);
                            if (category > 0) {
                                bits.write(difference >= 0 ? difference : difference + (1 << category) - 1,
                                        category);
                            }
                        }
                    }
                }
            }
        }
        bits.flush();
        marker(out, 0xD9);
        return out.toByteArray();
    }

    /**
     * Converts a baseline JPEG to a QuickTime Motion-JPEG format B frame: a header of ten 32-bit
     * words padded to 48 bytes, the quantization, Huffman, frame and scan segments merged by kind
     * and stripped of their marker codes, and the entropy-coded data without its stuffed bytes.
     *
     * @param jpeg the JPEG, ending with EOI and without restart markers, not modified
     * @return the frame
     */
    static byte[] formatB(byte[] jpeg) {
        var body = new ByteArrayOutputStream();
        var offsets = new int[4];
        var kinds = new int[]{0xDB, 0xC4, 0xC0, 0xDA};
        var scan = 0;
        for (var slot = 0; slot < 4; slot++) {
            var payload = new ByteArrayOutputStream();
            for (var at = 2; at + 3 < jpeg.length; ) {
                var marker = jpeg[at + 1] & 0xFF;
                var end = at + 2 + (((jpeg[at + 2] & 0xFF) << 8) | (jpeg[at + 3] & 0xFF));
                if (marker == kinds[slot]) {
                    payload.write(jpeg, at + 4, end - at - 4);
                }
                if (marker == 0xDA) {
                    scan = end;
                    break;
                }
                at = end;
            }
            offsets[slot] = 48 + body.size();
            u16be(body, payload.size() + 2);
            body.writeBytes(payload.toByteArray());
        }
        var data = 48 + body.size();
        for (var i = scan; i < jpeg.length - 2; i++) {
            body.write(jpeg[i]);
            if (jpeg[i] == (byte) 0xFF) {
                i++;
            }
        }
        var size = 48 + body.size();
        var header = ByteBuffer.allocate(48).putInt(0).putInt(0x6D6A7067).putInt(size).putInt(size).putInt(0);
        for (var offset : offsets) {
            header.putInt(offset);
        }
        header.putInt(data);
        var frame = new byte[size];
        System.arraycopy(header.array(), 0, frame, 0, 48);
        System.arraycopy(body.toByteArray(), 0, frame, 48, body.size());
        return frame;
    }

    /**
     * Returns a copy of a sequential 8-bit JPEG whose frame header declares 12-bit precision: a
     * valid 12-bit JPEG whose samples lie within 128 of mid-gray, since the coefficients code
     * 8-bit samples.
     *
     * @param jpeg an 8-bit JPEG with Huffman tables that cover its coefficients, not modified
     * @return the 12-bit JPEG
     */
    static byte[] twelveBitJpeg(byte[] jpeg) {
        var out = jpeg.clone();
        var at = 2;
        while (true) {
            var marker = out[at + 1] & 0xFF;
            if (marker >= 0xC0 && marker <= 0xC2) {
                out[at + 4] = 12;
                return out;
            }
            at += 2 + (((out[at + 2] & 0xFF) << 8) | (out[at + 3] & 0xFF));
        }
    }

    /**
     * Writes a little-endian TIFF holding one JPEG (compression 7) as its single strip, Y'CbCr
     * 4:2:0 with 12-bit samples.
     *
     * @param jpeg the 12-bit 4:2:0 JPEG
     * @param width the image width
     * @param height the image height
     * @return the TIFF file
     */
    static byte[] jpegTiff(byte[] jpeg, int width, int height) {
        int[][] entries = {{256, 4, 1, width}, {257, 4, 1, height}, {258, 3, 3, 0}, {259, 3, 1, 7},
                {262, 3, 1, 6}, {273, 4, 1, 8}, {277, 3, 1, 3}, {278, 4, 1, height}, {279, 4, 1, jpeg.length},
                {530, 3, 2, 2 | 2 << 16}};
        var data = 8 + jpeg.length + (jpeg.length & 1);
        var bits = data + 2 + entries.length * 12 + 4;
        entries[2][3] = bits;
        var buffer = ByteBuffer.allocate(bits + 6).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(data);
        buffer.put(jpeg);
        buffer.position(data);
        buffer.putShort((short) entries.length);
        for (var entry : entries) {
            buffer.putShort((short) entry[0]).putShort((short) entry[1]).putInt(entry[2]);
            if (entry[1] == 3 && entry[2] == 1) {
                buffer.putShort((short) entry[3]).putShort((short) 0);
            } else {
                buffer.putInt(entry[3]);
            }
        }
        buffer.putInt(0);
        buffer.putShort((short) 12).putShort((short) 12).putShort((short) 12);
        return buffer.array();
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

    /**
     * Returns uncompressed TIFF files of the layouts the TIFF decoder normalizes or converts on paths of
     * their own: 16-bit YCbCr data units subsampled 2x2, 8-bit YCbCr in separate planes with 2x2
     * subsampled Cb and Cr planes and an alpha plane, RGB of 5, 6 and 5 bits, complex integers, 24-bit
     * floating point, floating-point L*a*b*, and an RGB page followed by a transparency mask of twice
     * its size.
     *
     * @param picture the source of the samples, of even width and height
     * @return the files
     */
    static List<byte[]> tiffVariants(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var count = width * height;
        var pixels = picture.pixels();
        var stride = picture.stride();
        var files = new ArrayList<byte[]>();
        var units = new ByteArrayOutputStream();
        var luma = new byte[count];
        var cb = new byte[count / 4];
        var cr = new byte[count / 4];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                luma[y * width + x] = pixels[y * stride + x * 4];
            }
        }
        for (var y = 0; y < height; y += 2) {
            for (var x = 0; x < width; x += 2) {
                cb[(y / 2) * (width / 2) + x / 2] = pixels[y * stride + x * 4 + 1];
                cr[(y / 2) * (width / 2) + x / 2] = pixels[y * stride + x * 4 + 2];
                for (var dy = 0; dy < 2; dy++) {
                    for (var dx = 0; dx < 2; dx++) {
                        var value = pixels[(y + dy) * stride + (x + dx) * 4] & 0xFF;
                        units.write(value);
                        units.write(value);
                    }
                }
                for (var c = 1; c <= 2; c++) {
                    units.write(pixels[y * stride + x * 4 + c]);
                    units.write(pixels[y * stride + x * 4 + c]);
                }
            }
        }
        files.add(rawTiff(new RawPage(width, height, new int[]{16, 16, 16}, new int[][]{{262, 6}, {530, 2, 2}},
                new byte[][]{units.toByteArray()})));
        var alpha = new byte[count];
        for (var i = 0; i < count; i++) {
            alpha[i] = pixels[(i / width) * stride + (i % width) * 4 + 3];
        }
        files.add(rawTiff(new RawPage(width, height, new int[]{8, 8, 8, 8},
                new int[][]{{262, 6}, {530, 2, 2}, {284, 2}, {338, 2}}, new byte[][]{luma, cb, cr, alpha})));
        var rgb565 = new byte[count * 2];
        var complex = new byte[count * 4];
        var float24 = new byte[count * 3];
        var lab = ByteBuffer.allocate(count * 12).order(ByteOrder.LITTLE_ENDIAN);
        for (var i = 0; i < count; i++) {
            var at = (i / width) * stride + (i % width) * 4;
            var r = pixels[at] & 0xFF;
            var g = pixels[at + 1] & 0xFF;
            var b = pixels[at + 2] & 0xFF;
            var packed = (r >> 3) << 11 | (g >> 2) << 5 | b >> 3;
            rgb565[i * 2] = (byte) (packed >>> 8);
            rgb565[i * 2 + 1] = (byte) packed;
            complex[i * 4] = (byte) (r - 128);
            complex[i * 4 + 1] = (byte) ((r - 128) >> 8);
            complex[i * 4 + 2] = (byte) (g - 128);
            complex[i * 4 + 3] = (byte) ((g - 128) >> 8);
            // exponent 62 (values 0.5 to 1), the fraction from the red and green samples
            float24[i * 3] = 62;
            float24[i * 3 + 1] = (byte) r;
            float24[i * 3 + 2] = (byte) g;
            lab.putFloat(r * 100 / 255.0F).putFloat(g - 128).putFloat(b - 128);
        }
        files.add(rawTiff(new RawPage(width, height, new int[]{5, 6, 5}, new int[][]{{262, 2}},
                new byte[][]{rgb565})));
        files.add(rawTiff(new RawPage(width, height, new int[]{32}, new int[][]{{262, 1}, {339, 5}},
                new byte[][]{complex})));
        files.add(rawTiff(new RawPage(width, height, new int[]{24}, new int[][]{{262, 1}, {339, 3}},
                new byte[][]{float24})));
        files.add(rawTiff(new RawPage(width, height, new int[]{32, 32, 32}, new int[][]{{262, 8}, {339, 3, 3, 3}},
                new byte[][]{lab.array()})));
        var rgb = new byte[count * 3];
        for (var i = 0; i < count; i++) {
            System.arraycopy(pixels, (i / width) * stride + (i % width) * 4, rgb, i * 3, 3);
        }
        var maskRow = (width * 2 + 7) / 8;
        var mask = new byte[maskRow * height * 2];
        for (var y = 0; y < height * 2; y++) {
            for (var x = 0; x < width * 2; x++) {
                if (Math.hypot(x - width, y - height) < Math.min(width, height) * 0.8) {
                    mask[y * maskRow + x / 8] |= (byte) (0x80 >>> (x & 7));
                }
            }
        }
        files.add(rawTiff(new RawPage(width, height, new int[]{8, 8, 8}, new int[][]{{262, 2}}, new byte[][]{rgb}),
                new RawPage(width * 2, height * 2, new int[]{1}, new int[][]{{254, 4}, {262, 4}},
                        new byte[][]{mask})));
        return files;
    }

    /**
     * Describes an uncompressed page of {@link #rawTiff}: one strip per plane.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param bits the bits per sample of each sample
     * @param tags further SHORT tags, each the tag number followed by its values
     * @param planes the strip of each plane, one for interleaved samples
     */
    private record RawPage(int width, int height, int[] bits, int[][] tags, byte[][] planes) {
    }

    /**
     * Writes uncompressed pages as one little-endian classic TIFF file, chained in order.
     *
     * @param pages the pages, in file order
     * @return the file
     */
    private static byte[] rawTiff(RawPage... pages) {
        var blocks = new byte[pages.length][];
        var directories = new int[pages.length];
        var size = 8;
        for (var pass = 0; pass < 2; pass++) {
            size = 8;
            for (var i = 0; i < pages.length; i++) {
                var next = pass == 0 || i + 1 == pages.length ? 0 : directories[i + 1];
                blocks[i] = rawPage(pages[i], size, next);
                directories[i] = size + dataLength(pages[i]);
                size += blocks[i].length;
            }
        }
        var buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(directories[0]);
        for (var block : blocks) {
            buffer.put(block);
        }
        return buffer.array();
    }

    /**
     * Returns the size of the strips of a page, each padded to an even length.
     *
     * @param page the page to measure
     * @return the size in bytes
     */
    private static int dataLength(RawPage page) {
        var length = 0;
        for (var plane : page.planes()) {
            length += plane.length + (plane.length & 1);
        }
        return length;
    }

    /**
     * Writes the strips and the directory of one page.
     *
     * @param page the page to write
     * @param base the file offset of the block
     * @param next the offset of the next directory, or 0
     * @return the block
     */
    private static byte[] rawPage(RawPage page, int base, int next) {
        var samples = page.bits().length;
        var entries = new TreeMap<Integer, int[]>();
        entries.put(256, new int[]{4, page.width()});
        entries.put(257, new int[]{4, page.height()});
        var bits = new int[samples + 1];
        bits[0] = 3;
        System.arraycopy(page.bits(), 0, bits, 1, samples);
        entries.put(258, bits);
        entries.put(259, new int[]{3, 1});
        entries.put(277, new int[]{3, samples});
        entries.put(278, new int[]{4, page.height()});
        var offsets = new int[page.planes().length + 1];
        var counts = new int[page.planes().length + 1];
        offsets[0] = 4;
        counts[0] = 4;
        var at = base;
        for (var i = 0; i < page.planes().length; i++) {
            offsets[i + 1] = at;
            counts[i + 1] = page.planes()[i].length;
            at += page.planes()[i].length + (page.planes()[i].length & 1);
        }
        entries.put(273, offsets);
        entries.put(279, counts);
        for (var tag : page.tags()) {
            var values = new int[tag.length];
            values[0] = 3;
            System.arraycopy(tag, 1, values, 1, tag.length - 1);
            entries.put(tag[0], values);
        }
        var overflowAt = at + 2 + entries.size() * 12 + 4;
        var table = ByteBuffer.allocate(2 + entries.size() * 12 + 4).order(ByteOrder.LITTLE_ENDIAN);
        var overflow = ByteBuffer.allocate(1024).order(ByteOrder.LITTLE_ENDIAN);
        table.putShort((short) entries.size());
        for (var entry : entries.entrySet()) {
            var type = entry.getValue()[0];
            var values = entry.getValue().length - 1;
            var width = type == 3 ? 2 : 4;
            table.putShort((short) (int) entry.getKey()).putShort((short) type).putInt(values);
            var inline = values * width <= 4;
            if (!inline) {
                table.putInt(overflowAt + overflow.position());
            }
            var target = inline ? table : overflow;
            var start = target.position();
            for (var i = 1; i <= values; i++) {
                if (width == 2) {
                    target.putShort((short) entry.getValue()[i]);
                } else {
                    target.putInt(entry.getValue()[i]);
                }
            }
            if (inline) {
                target.position(start + 4);
            }
        }
        table.putInt(next);
        var out = new ByteArrayOutputStream();
        for (var plane : page.planes()) {
            out.writeBytes(plane);
            if ((plane.length & 1) != 0) {
                out.write(0);
            }
        }
        out.writeBytes(table.array());
        out.write(overflow.array(), 0, overflow.position());
        return out.toByteArray();
    }

    /**
     * Returns TIFF files of the compressions Smalt decodes but does not write, coded here in their simplest
     * valid forms from the luma and colors of a picture: an XZ stream of literal-only LZMA2 data behind the
     * delta filter, Lerc2 version 2 tiles of bit-stuffed 8-bit samples and a one-sweep floating-point blob,
     * sequential and progressive JBIG entities of arbitrary coded data (any arithmetic-coded data decodes),
     * PixarLog codes of RGB and of 16-bit gray, and NeXT runs and literal rows.
     *
     * @param picture the source of the samples
     * @return the files
     */
    static List<byte[]> tiffCompressions(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var pixels = picture.pixels();
        var stride = picture.stride();
        var count = width * height;
        var luma = new byte[count];
        var rgb = new byte[count * 3];
        for (var i = 0; i < count; i++) {
            var at = (i / width) * stride + (i % width) * 4;
            luma[i] = pixels[at + 1];
            System.arraycopy(pixels, at, rgb, i * 3, 3);
        }
        var files = new ArrayList<byte[]>();
        files.add(rawTiff(new RawPage(width, height, new int[]{8, 8, 8}, new int[][]{{259, 34925}, {262, 2}},
                new byte[][]{xz(rgb, 3)})));
        files.add(rawTiff(new RawPage(width, height, new int[]{8}, new int[][]{{259, 34887}, {262, 1}, {50674, 4, 0}},
                new byte[][]{lercTiles(luma, width, height)})));
        var floats = ByteBuffer.allocate(count * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (var value : luma) {
            floats.putFloat((value & 0xFF) / 255.0F);
        }
        files.add(rawTiff(new RawPage(width, height, new int[]{32}, new int[][]{{259, 34887}, {262, 1}, {339, 3},
                {50674, 4, 0}}, new byte[][]{lercSweep(floats.array(), width, height)})));
        var random = new java.util.Random(34661);
        files.add(rawTiff(new RawPage(width, height, new int[]{1}, new int[][]{{259, 34661}, {262, 0}, {266, 2}},
                new byte[][]{jbig(width, height, 0, height, random)})));
        files.add(rawTiff(new RawPage(width, height, new int[]{1}, new int[][]{{259, 34661}, {262, 0}, {266, 2}},
                new byte[][]{jbig(width, height, 2, 8, random)})));
        files.add(rawTiff(new RawPage(width, height, new int[]{8, 8, 8}, new int[][]{{259, 32909}, {262, 2}},
                new byte[][]{pixarLog(rgb, width * 3, height, 3)})));
        files.add(rawTiff(new RawPage(width, height, new int[]{16}, new int[][]{{259, 32909}, {262, 1}},
                new byte[][]{pixarLog(luma, width, height, 1)})));
        files.add(rawTiff(new RawPage(width, height, new int[]{2}, new int[][]{{259, 32766}, {262, 1}},
                new byte[][]{next(luma, width, height)})));
        return files;
    }

    /**
     * Codes bytes as one XZ stream with a CRC32 check: the delta filter, then LZMA2 chunks of literals only.
     *
     * @param data the bytes
     * @param distance the delta distance
     * @return the stream
     */
    private static byte[] xz(byte[] data, int distance) {
        var delta = new byte[data.length];
        for (var i = 0; i < data.length; i++) {
            delta[i] = (byte) (data[i] - (i >= distance ? data[i - distance] : 0));
        }
        var lzma2 = new ByteArrayOutputStream();
        var literals = new short[0x300 << 3];
        var isMatch = new short[12 * 16];
        java.util.Arrays.fill(literals, (short) 1024);
        java.util.Arrays.fill(isMatch, (short) 1024);
        for (var start = 0; start < delta.length; start += 32768) {
            var end = Math.min(delta.length, start + 32768);
            var coder = new RangeEncoder();
            for (var i = start; i < end; i++) {
                coder.bit(isMatch, 0, 0);
                var previous = i > 0 ? delta[i - 1] & 0xFF : 0;
                var base = 0x300 * (previous >> 5);
                var symbol = 1;
                for (var bit = 7; bit >= 0; bit--) {
                    var value = (delta[i] >> bit) & 1;
                    coder.bit(literals, base + symbol, value);
                    symbol = symbol << 1 | value;
                }
            }
            var chunk = coder.finish();
            var unpacked = end - start - 1;
            // the first chunk resets the dictionary and sets lc=3, lp=0, pb=0; later ones only continue
            lzma2.write((start == 0 ? 0xE0 : 0x80) | unpacked >>> 16);
            lzma2.write(unpacked >>> 8);
            lzma2.write(unpacked);
            lzma2.write((chunk.length - 1) >>> 8);
            lzma2.write(chunk.length - 1);
            if (start == 0) {
                lzma2.write(3);
            }
            lzma2.writeBytes(chunk);
        }
        lzma2.write(0);
        var compressed = lzma2.toByteArray();
        var out = new ByteArrayOutputStream();
        var flags = new byte[]{0, 1};
        out.writeBytes(new byte[]{(byte) 0xFD, '7', 'z', 'X', 'Z', 0});
        out.writeBytes(flags);
        writeCrc(out, flags, 0, 2);
        var header = new byte[]{2, 1, 3, 1, (byte) (distance - 1), 0x21, 1, 16};
        out.writeBytes(header);
        writeCrc(out, header, 0, header.length);
        out.writeBytes(compressed);
        for (var padded = compressed.length; (padded & 3) != 0; padded++) {
            out.write(0);
        }
        writeCrc(out, data, 0, data.length);
        var index = new ByteArrayOutputStream();
        index.write(0);
        index.write(1);
        writeVli(index, 12L + compressed.length + 4);
        writeVli(index, data.length);
        while (index.size() % 4 != 0) {
            index.write(0);
        }
        var indexBytes = index.toByteArray();
        out.writeBytes(indexBytes);
        writeCrc(out, indexBytes, 0, indexBytes.length);
        var footer = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN).putInt((indexBytes.length + 4) / 4 - 1)
                .put(flags).array();
        writeCrc(out, footer, 0, footer.length);
        out.writeBytes(footer);
        out.write('Y');
        out.write('Z');
        return out.toByteArray();
    }

    private static void writeCrc(ByteArrayOutputStream out, byte[] data, int offset, int length) {
        var crc = new CRC32();
        crc.update(data, offset, length);
        var value = (int) crc.getValue();
        for (var i = 0; i < 4; i++) {
            out.write(value >>> (8 * i));
        }
    }

    private static void writeVli(ByteArrayOutputStream out, long value) {
        while (value >= 0x80) {
            out.write((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write((int) value);
    }

    /**
     * Encodes binary decisions with the LZMA range coder.
     */
    private static final class RangeEncoder {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private long low;
        private int range = 0xFFFFFFFF;
        private int cache;
        private long cacheSize = 1;

        void bit(short[] probabilities, int index, int bit) {
            int probability = probabilities[index];
            var bound = (range >>> 11) * probability;
            if (bit == 0) {
                range = bound;
                probabilities[index] = (short) (probability + ((2048 - probability) >>> 5));
            } else {
                low += bound & 0xFFFFFFFFL;
                range -= bound;
                probabilities[index] = (short) (probability - (probability >>> 5));
            }
            while ((range & 0xFF000000) == 0) {
                range <<= 8;
                shiftLow();
            }
        }

        private void shiftLow() {
            var high = (int) (low >>> 32);
            if (high != 0 || low < 0xFF000000L) {
                var temp = cache;
                do {
                    out.write(temp + high);
                    temp = 0xFF;
                } while (--cacheSize != 0);
                cache = (int) (low >>> 24) & 0xFF;
            }
            cacheSize++;
            low = (low & 0x00FFFFFFL) << 8;
        }

        byte[] finish() {
            for (var i = 0; i < 5; i++) {
                shiftLow();
            }
            return out.toByteArray();
        }
    }

    /**
     * Codes 8-bit samples as a Lerc2 version 2 blob of 8x8 tiles, constant tiles as such and the others
     * bit-stuffed, high bit first, losslessly.
     *
     * @param samples the 8-bit samples, one byte each
     * @param width the columns
     * @param height the rows
     * @return the blob
     */
    private static byte[] lercTiles(byte[] samples, int width, int height) {
        var body = new ByteArrayOutputStream();
        body.writeBytes(new byte[4]);
        body.write(0);
        body.write(0);
        var min = 255;
        var max = 0;
        for (var sample : samples) {
            min = Math.min(min, sample & 0xFF);
            max = Math.max(max, sample & 0xFF);
        }
        for (var i0 = 0; i0 < height; i0 += 8) {
            for (var j0 = 0; j0 < width; j0 += 8) {
                var tileMin = 255;
                var tileMax = 0;
                var values = new ArrayList<Integer>();
                for (var i = i0; i < Math.min(height, i0 + 8); i++) {
                    for (var j = j0; j < Math.min(width, j0 + 8); j++) {
                        var value = samples[i * width + j] & 0xFF;
                        values.add(value);
                        tileMin = Math.min(tileMin, value);
                        tileMax = Math.max(tileMax, value);
                    }
                }
                var integrity = ((j0 >> 3) & 15) << 2;
                if (tileMin == tileMax) {
                    body.write(integrity | 3);
                    body.write(tileMin);
                    continue;
                }
                body.write(integrity | 1);
                body.write(tileMin);
                var bits = 32 - Integer.numberOfLeadingZeros(tileMax - tileMin);
                body.write(2 << 6 | bits);
                body.write(values.size());
                var words = new int[(values.size() * bits + 31) / 32];
                var bitPos = 0;
                for (var value : values) {
                    var v = value - tileMin;
                    for (var b = bits - 1; b >= 0; b--, bitPos++) {
                        if ((v >> b & 1) != 0) {
                            words[bitPos >> 5] |= 1 << (31 - (bitPos & 31));
                        }
                    }
                }
                var used = (values.size() * bits + 7) / 8;
                var unused = words.length * 4 - used;
                words[words.length - 1] >>>= 8 * unused;
                var packed = ByteBuffer.allocate(words.length * 4).order(ByteOrder.LITTLE_ENDIAN);
                for (var word : words) {
                    packed.putInt(word);
                }
                body.write(packed.array(), 0, used);
            }
        }
        return lercHeader(2, width, height, 8, 1, 0.5, min, max, body.toByteArray());
    }

    /**
     * Codes floating-point samples as a Lerc2 version 2 blob that stores them in one sweep.
     *
     * @param floats the samples, little-endian
     * @param width the columns
     * @param height the rows
     * @return the blob
     */
    private static byte[] lercSweep(byte[] floats, int width, int height) {
        var body = new ByteArrayOutputStream();
        body.writeBytes(new byte[4]);
        body.write(1);
        body.writeBytes(floats);
        var values = ByteBuffer.wrap(floats).order(ByteOrder.LITTLE_ENDIAN);
        var min = Double.MAX_VALUE;
        var max = -Double.MAX_VALUE;
        while (values.hasRemaining()) {
            var value = values.getFloat();
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        return lercHeader(2, width, height, 8, 6, 0, min, max, body.toByteArray());
    }

    private static byte[] lercHeader(int version, int width, int height, int blockSize, int type, double maxError,
                                     double min, double max, byte[] body) {
        var header = ByteBuffer.allocate(6 + 4 + 6 * 4 + 3 * 8).order(ByteOrder.LITTLE_ENDIAN);
        header.put("Lerc2 ".getBytes(StandardCharsets.US_ASCII)).putInt(version);
        var size = header.capacity() + body.length;
        header.putInt(height).putInt(width).putInt(width * height).putInt(blockSize).putInt(size).putInt(type);
        header.putDouble(maxError).putDouble(min).putDouble(max);
        var out = new ByteArrayOutputStream();
        out.writeBytes(header.array());
        out.writeBytes(body);
        return out.toByteArray();
    }

    /**
     * Returns a JBIG bi-level image entity of arbitrary coded data, with deterministic and typical prediction
     * on: sequential when {@code layers} is 0, otherwise progressive with that many differential layers.
     *
     * @param width the columns
     * @param height the rows
     * @param layers the differential layers
     * @param stripeLines the lines of a stripe at the lowest layer
     * @param random the source of the coded data
     * @return the entity
     */
    private static byte[] jbig(int width, int height, int layers, int stripeLines, java.util.Random random) {
        var out = ByteBuffer.allocate(1 << 20);
        out.put((byte) 0).put((byte) layers).put((byte) 1).put((byte) 0).putInt(width).putInt(height)
                .putInt(stripeLines).put((byte) 8).put((byte) 0).put((byte) 0).put((byte) 0x1C);
        var lowest = height;
        for (var d = 0; d < layers; d++) {
            lowest = (lowest + 1) / 2;
        }
        var stripes = (lowest + stripeLines - 1) / stripeLines;
        for (var d = 0; d <= layers; d++) {
            for (var s = 0; s < stripes; s++) {
                for (var i = 0; i < 48; i++) {
                    var value = random.nextInt(256);
                    out.put((byte) value);
                    if (value == 0xFF) {
                        out.put((byte) 0);
                    }
                }
                out.put((byte) 0xFF).put((byte) 2);
            }
        }
        return java.util.Arrays.copyOf(out.array(), out.position());
    }

    /**
     * Codes samples as one PixarLog strip: each sample's 11-bit code (the sample times 8), differenced along
     * each row of every channel, little-endian, in zlib.
     *
     * @param samples the samples, one byte each
     * @param rowSamples the samples of a row
     * @param rows the number of rows
     * @param stride the samples of a pixel
     * @return the strip
     */
    private static byte[] pixarLog(byte[] samples, int rowSamples, int rows, int stride) {
        var words = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (var y = 0; y < rows; y++) {
            for (var i = 0; i < rowSamples; i++) {
                var code = (samples[y * rowSamples + i] & 0xFF) * 8;
                var previous = i >= stride ? (samples[y * rowSamples + i - stride] & 0xFF) * 8 : 0;
                words.putShort((short) (i >= stride ? code - previous : code));
            }
        }
        var deflater = new Deflater(6);
        deflater.setInput(words.array());
        deflater.finish();
        var out = new ByteArrayOutputStream();
        var buffer = new byte[8192];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }

    /**
     * Codes the top two bits of samples as NeXT data: even rows as runs, odd rows as literal rows.
     *
     * @param samples the 8-bit samples, one byte each
     * @param width the columns
     * @param height the rows
     * @return the strip
     */
    private static byte[] next(byte[] samples, int width, int height) {
        var rowBytes = (width * 2 + 7) / 8;
        var out = new ByteArrayOutputStream();
        for (var y = 0; y < height; y++) {
            if ((y & 1) == 1) {
                var packed = new byte[rowBytes];
                for (var x = 0; x < width; x++) {
                    packed[x >> 2] |= (byte) (((samples[y * width + x] & 0xFF) >> 6) << (6 - 2 * (x & 3)));
                }
                out.write(0);
                out.writeBytes(packed);
                continue;
            }
            for (var x = 0; x < width; ) {
                var grey = (samples[y * width + x] & 0xFF) >> 6;
                var run = 1;
                while (x + run < width && run < 63 && (samples[y * width + x + run] & 0xFF) >> 6 == grey) {
                    run++;
                }
                out.write(grey << 6 | run);
                x += run;
            }
        }
        return out.toByteArray();
    }
}
