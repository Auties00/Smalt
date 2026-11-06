package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.image.RgbaPicture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.zip.Deflater;

/**
 * Writes the files of the image formats Smalt decodes but does not encode, for the training workloads of their
 * decoders: Photoshop documents (with a merged image, or with layers only) and DirectDraw Surface textures,
 * deterministically, from a synthetic picture.
 */
final class SyntheticImageFiles {
    /**
     * The DDS pixel format flag of a format given by a four-character code.
     */
    private static final int DDPF_FOURCC = 0x4;

    /**
     * The DDS pixel format flag of a format given by channel masks with alpha.
     */
    private static final int DDPF_RGBA = 0x41;

    /**
     * The Photoshop color mode of a grayscale document.
     */
    static final int PSD_GRAY = 1;

    /**
     * The Photoshop color mode of an RGB document.
     */
    static final int PSD_RGB = 3;

    /**
     * The Photoshop color mode of a CMYK document.
     */
    static final int PSD_CMYK = 4;

    /**
     * Prevents instantiation.
     */
    private SyntheticImageFiles() {
        throw new AssertionError();
    }

    /**
     * Writes a Photoshop document whose merged image holds the channels of a picture.
     *
     * <p>A gray document holds the green channel, an RGB one the three colors, a CMYK one the colors and a quarter
     * of the darkness as black, stored inverted as Photoshop stores ink; each document has the picture's alpha as an
     * extra channel, declared the merged image's transparency by a layer count of -1. Sixteen-bit samples repeat each
     * 8-bit value in both bytes.
     *
     * @param mode the color mode: {@link #PSD_GRAY}, {@link #PSD_RGB} or {@link #PSD_CMYK}
     * @param depth the bits per sample, 8 or 16
     * @param compression 0 for raw data, 1 for PackBits rows, 2 for ZIP, 3 for ZIP with prediction
     * @param large whether to write the large document format (PSB)
     * @return the document
     */
    static byte[] psd(RgbaPicture picture, int mode, int depth, int compression, boolean large) {
        var width = picture.width();
        var height = picture.height();
        var colors = mode == PSD_GRAY ? 1 : mode == PSD_RGB ? 3 : 4;
        var bytesPerSample = depth / 8;
        var rowBytes = width * bytesPerSample;
        var planes = new byte[colors + 1][height * rowBytes];
        var pixels = picture.pixels();
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + x * 4;
                var red = pixels[at] & 0xFF;
                var green = pixels[at + 1] & 0xFF;
                var blue = pixels[at + 2] & 0xFF;
                int[] values = switch (mode) {
                    case PSD_GRAY -> new int[]{green};
                    case PSD_RGB -> new int[]{red, green, blue};
                    default -> new int[]{red, green, blue, 255 - (255 - Math.max(red, Math.max(green, blue))) / 4};
                };
                for (var channel = 0; channel <= colors; channel++) {
                    var value = channel < colors ? values[channel] : pixels[at + 3] & 0xFF;
                    for (var b = 0; b < bytesPerSample; b++) {
                        planes[channel][y * rowBytes + x * bytesPerSample + b] = (byte) value;
                    }
                }
            }
        }
        var out = new ByteArrayOutputStream();
        var header = ByteBuffer.allocate(26);
        header.put("8BPS".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putShort((short) (large ? 2 : 1));
        header.put(new byte[6]).putShort((short) planes.length).putInt(height).putInt(width).putShort((short) depth);
        header.putShort((short) mode);
        out.writeBytes(header.array());
        out.writeBytes(ByteBuffer.allocate(8).putInt(0).putInt(0).array());
        // the layer and mask section: a layer info block holding only the count -1, then an empty global mask
        var section = ByteBuffer.allocate(large ? 24 : 16);
        if (large) {
            section.putLong(8 + 2 + 4 + 2).putLong(2);
        } else {
            section.putInt(4 + 2 + 4 + 2).putInt(2);
        }
        section.putShort((short) -1).putInt(0).putShort((short) 0);
        out.writeBytes(section.array());
        out.writeBytes(ByteBuffer.allocate(2).putShort((short) compression).array());
        switch (compression) {
            case 0 -> {
                for (var plane : planes) {
                    out.writeBytes(plane);
                }
            }
            case 1 -> {
                var packed = new byte[planes.length * height][];
                for (var channel = 0; channel < planes.length; channel++) {
                    for (var y = 0; y < height; y++) {
                        packed[channel * height + y] = packBits(planes[channel], y * rowBytes, rowBytes);
                    }
                }
                for (var row : packed) {
                    out.writeBytes(large ? ByteBuffer.allocate(4).putInt(row.length).array()
                            : ByteBuffer.allocate(2).putShort((short) row.length).array());
                }
                for (var row : packed) {
                    out.writeBytes(row);
                }
            }
            default -> {
                var all = new ByteArrayOutputStream();
                for (var plane : planes) {
                    all.writeBytes(compression == 3 ? predict(plane, width, height, bytesPerSample) : plane);
                }
                out.writeBytes(deflate(all.toByteArray()));
            }
        }
        return out.toByteArray();
    }

    /**
     * Writes an 8-bit RGB Photoshop document saved without a merged image, whose decoder renders its composite from
     * the layers: a background holding the picture's colors, a pass-through group at reduced opacity holding a
     * multiply layer with the picture's alpha as transparency and a masked hue layer, a soft light layer and a
     * screen layer clipped to it, each layer PackBits-compressed.
     *
     * @return the document
     */
    static byte[] psdLayered(RgbaPicture picture) {
        var width = picture.width();
        var height = picture.height();
        var count = width * height;
        var pixels = picture.pixels();
        var colors = new byte[3][count];
        var alpha = new byte[count];
        var inverse = new byte[3][count];
        var mask = new byte[count];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var at = y * picture.stride() + x * 4;
                var index = y * width + x;
                for (var channel = 0; channel < 3; channel++) {
                    colors[channel][index] = pixels[at + channel];
                    inverse[channel][index] = (byte) ~pixels[at + (channel + 1) % 3];
                }
                alpha[index] = pixels[at + 3];
                mask[index] = (byte) (x * 255 / Math.max(1, width - 1));
            }
        }
        var records = new ByteArrayOutputStream();
        var data = new ByteArrayOutputStream();
        var full = new int[]{0, 0, height, width};
        var none = new int[]{0, 0, 0, 0};
        layer(records, data, full, new byte[][]{colors[0], colors[1], colors[2]}, null, "norm", 255, false, null, 0);
        layer(records, data, none, new byte[0][], null, "norm", 255, false, null, 3);
        layer(records, data, full, new byte[][]{inverse[0], inverse[1], inverse[2]}, alpha, "mul ", 255, false, null,
                0);
        layer(records, data, full, new byte[][]{colors[2], colors[0], colors[1]}, null, "hue ", 200, false, mask, 0);
        layer(records, data, none, new byte[0][], null, "pass", 180, false, null, 1);
        layer(records, data, full, new byte[][]{inverse[1], colors[1], inverse[2]}, alpha, "sLit", 255, false, null,
                0);
        layer(records, data, full, new byte[][]{colors[1], inverse[0], colors[0]}, null, "scrn", 160, true, null, 0);
        var info = new ByteArrayOutputStream();
        info.writeBytes(ByteBuffer.allocate(2).putShort((short) -7).array());
        info.writeBytes(records.toByteArray());
        info.writeBytes(data.toByteArray());
        if ((info.size() & 1) != 0) {
            info.write(0);
        }
        var out = new ByteArrayOutputStream();
        var header = ByteBuffer.allocate(26);
        header.put("8BPS".getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putShort((short) 1);
        header.put(new byte[6]).putShort((short) 4).putInt(height).putInt(width).putShort((short) 8);
        header.putShort((short) PSD_RGB);
        out.writeBytes(header.array());
        out.writeBytes(ByteBuffer.allocate(4).putInt(0).array());
        // the version information resource (1057), saying the document has no merged image
        var resource = ByteBuffer.allocate(30).put("8BIM".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .putShort((short) 1057).putShort((short) 0).putInt(18).putInt(1).put((byte) 0).putInt(0).putInt(0)
                .putInt(1).put((byte) 0);
        out.writeBytes(ByteBuffer.allocate(4).putInt(resource.capacity()).array());
        out.writeBytes(resource.array());
        out.writeBytes(ByteBuffer.allocate(8).putInt(4 + info.size() + 4).putInt(info.size()).array());
        out.writeBytes(info.toByteArray());
        out.writeBytes(ByteBuffer.allocate(4).putInt(0).array());
        out.writeBytes(ByteBuffer.allocate(2).putShort((short) 0).array());
        out.writeBytes(new byte[4 * count]);
        return out.toByteArray();
    }

    /**
     * Writes one layer record and its PackBits channel data: the colors, then the transparency and a user mask over
     * the layer rectangle when given, with the section divider type of a group record (1 to open, 3 to close) and
     * its blend key.
     */
    private static void layer(ByteArrayOutputStream records, ByteArrayOutputStream data, int[] rect, byte[][] colors,
                              byte[] alpha, String blend, int opacity, boolean clipping, byte[] mask, int divider) {
        var width = rect[3] - rect[1];
        var height = rect[2] - rect[0];
        var planes = new java.util.ArrayList<byte[]>();
        var ids = new java.util.ArrayList<Integer>();
        for (var channel = 0; channel < colors.length; channel++) {
            planes.add(colors[channel]);
            ids.add(channel);
        }
        if (alpha != null) {
            planes.add(alpha);
            ids.add(-1);
        }
        if (mask != null) {
            planes.add(mask);
            ids.add(-2);
        }
        var encoded = new java.util.ArrayList<byte[]>();
        for (var plane : planes) {
            var channel = new ByteArrayOutputStream();
            channel.writeBytes(ByteBuffer.allocate(2).putShort((short) 1).array());
            var rows = new byte[height][];
            for (var y = 0; y < height; y++) {
                rows[y] = packBits(plane, y * width, width);
                channel.writeBytes(ByteBuffer.allocate(2).putShort((short) rows[y].length).array());
            }
            for (var row : rows) {
                channel.writeBytes(row);
            }
            encoded.add(channel.toByteArray());
        }
        var record = ByteBuffer.allocate(18 + 6 * planes.size() + 16);
        record.putInt(rect[0]).putInt(rect[1]).putInt(rect[2]).putInt(rect[3]).putShort((short) planes.size());
        for (var index = 0; index < planes.size(); index++) {
            record.putShort((short) (int) ids.get(index)).putInt(encoded.get(index).length);
        }
        var groupBlend = divider == 1 ? blend : "norm";
        record.put("8BIM".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .put((divider == 0 ? blend : "norm").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        record.put((byte) opacity).put((byte) (clipping ? 1 : 0)).put((byte) 8).put((byte) 0);
        var extra = new ByteArrayOutputStream();
        if (mask != null) {
            extra.writeBytes(ByteBuffer.allocate(24).putInt(20).putInt(rect[0]).putInt(rect[1]).putInt(rect[2])
                    .putInt(rect[3]).put((byte) 0).put((byte) 0).putShort((short) 0).array());
        } else {
            extra.writeBytes(ByteBuffer.allocate(4).putInt(0).array());
        }
        extra.writeBytes(ByteBuffer.allocate(4).putInt(0).array());
        extra.writeBytes(new byte[]{0, 0, 0, 0});
        if (divider != 0) {
            extra.writeBytes("8BIMlsct".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            extra.writeBytes(ByteBuffer.allocate(16).putInt(12).putInt(divider)
                    .put("8BIM".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                    .put(groupBlend.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).array());
        }
        record.putInt(extra.size());
        records.writeBytes(record.array());
        records.writeBytes(extra.toByteArray());
        for (var channel : encoded) {
            data.writeBytes(channel);
        }
    }

    /**
     * Returns a plane with each sample replaced by its difference from the sample to its left, as Photoshop's ZIP
     * with prediction stores 8 and 16-bit samples.
     *
     * @param plane the plane, big-endian samples row by row
     * @param bytes the bytes of a sample, 1 or 2
     * @return the differences
     */
    private static byte[] predict(byte[] plane, int width, int height, int bytes) {
        var out = plane.clone();
        var rowBytes = width * bytes;
        for (var y = 0; y < height; y++) {
            var base = y * rowBytes;
            for (var x = width - 1; x > 0; x--) {
                var at = base + x * bytes;
                if (bytes == 2) {
                    var value = ((plane[at] & 0xFF) << 8 | (plane[at + 1] & 0xFF))
                            - ((plane[at - 2] & 0xFF) << 8 | (plane[at - 1] & 0xFF));
                    out[at] = (byte) (value >>> 8);
                    out[at + 1] = (byte) value;
                } else {
                    out[at] = (byte) (plane[at] - plane[at - 1]);
                }
            }
        }
        return out;
    }

    /**
     * Codes bytes with PackBits: runs of three or more equal bytes as run packets, the rest as literal packets.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @return the packets
     */
    private static byte[] packBits(byte[] data, int offset, int length) {
        var out = new ByteArrayOutputStream();
        var position = 0;
        while (position < length) {
            var run = 1;
            while (position + run < length && run < 128 && data[offset + position + run] == data[offset + position]) {
                run++;
            }
            if (run >= 3) {
                out.write(1 - run);
                out.write(data[offset + position]);
                position += run;
                continue;
            }
            var literal = 1;
            while (position + literal < length && literal < 128 && !(position + literal + 2 < length
                    && data[offset + position + literal] == data[offset + position + literal + 1]
                    && data[offset + position + literal] == data[offset + position + literal + 2])) {
                literal++;
            }
            out.write(literal - 1);
            out.write(data, offset + position, literal);
            position += literal;
        }
        return out.toByteArray();
    }

    /**
     * Writes a DDS texture of a picture as uncompressed 32-bit texels described by channel masks, with every mipmap
     * level, each level a box-filtered half of the one above.
     *
     * @param picture the picture, whose width and height are powers of two
     * @return the texture
     */
    static byte[] ddsMasks(RgbaPicture picture) {
        var data = new ByteArrayOutputStream();
        var levels = 0;
        var level = picture.pixels().clone();
        var width = picture.width();
        var height = picture.height();
        var stride = picture.stride();
        while (true) {
            levels++;
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var at = y * stride + x * 4;
                    data.write(level[at + 2]);
                    data.write(level[at + 1]);
                    data.write(level[at]);
                    data.write(level[at + 3]);
                }
            }
            if (width == 1 && height == 1) {
                break;
            }
            var nextWidth = Math.max(1, width / 2);
            var nextHeight = Math.max(1, height / 2);
            var next = new byte[nextWidth * nextHeight * 4];
            for (var y = 0; y < nextHeight; y++) {
                for (var x = 0; x < nextWidth; x++) {
                    for (var c = 0; c < 4; c++) {
                        var sum = 0;
                        for (var k = 0; k < 4; k++) {
                            var sx = Math.min(width - 1, 2 * x + (k & 1));
                            var sy = Math.min(height - 1, 2 * y + (k >> 1));
                            sum += level[sy * stride + sx * 4 + c] & 0xFF;
                        }
                        next[(y * nextWidth + x) * 4 + c] = (byte) ((sum + 2) / 4);
                    }
                }
            }
            level = next;
            width = nextWidth;
            height = nextHeight;
            stride = nextWidth * 4;
        }
        return ddsHeader(picture.width(), picture.height(), levels, DDPF_RGBA, 0, 32,
                new int[]{0x00FF0000, 0x0000FF00, 0x000000FF, 0xFF000000}, null, data.toByteArray());
    }

    /**
     * Writes a DDS texture of one surface in a DXGI format of a DX10 header, its data deterministic pseudo-random
     * bytes: every bit pattern is a valid block of the block-compressed formats, so the data selects every block
     * mode the format has.
     *
     * @param width the width, a multiple of 4
     * @param height the height, a multiple of 4
     * @param dxgi the DXGI format number
     * @param bytesPerUnit the bytes of a 4x4 block of a block-compressed format, or of a texel otherwise
     * @param blocks whether the format is block compressed
     * @param seed the seed of the data
     * @return the texture
     */
    static byte[] ddsDx10(int width, int height, int dxgi, int bytesPerUnit, boolean blocks, long seed) {
        var units = blocks ? (width / 4) * (height / 4) : width * height;
        var data = new byte[units * bytesPerUnit];
        new Random(seed).nextBytes(data);
        var extension = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN).putInt(dxgi).putInt(3).putInt(0)
                .putInt(1).putInt(0).array();
        return ddsHeader(width, height, 1, DDPF_FOURCC, fourCc("DX10"), 0, new int[4], extension, data);
    }

    /**
     * Writes a DDS texture of one surface in a format of a legacy four-character code, its data deterministic
     * pseudo-random blocks.
     *
     * @param width the width, a multiple of 4
     * @param height the height, a multiple of 4
     * @param code the four-character code, such as {@code DXT1}
     * @param bytesPerBlock the bytes of a 4x4 block
     * @param seed the seed of the data
     * @return the texture
     */
    static byte[] ddsFourCc(int width, int height, String code, int bytesPerBlock, long seed) {
        var data = new byte[(width / 4) * (height / 4) * bytesPerBlock];
        new Random(seed).nextBytes(data);
        return ddsHeader(width, height, 1, DDPF_FOURCC, fourCc(code), 0, new int[4], null, data);
    }

    /**
     * Returns a DDS file: the magic, the 124-byte header, an optional DX10 extension and the surface data.
     *
     * @param mipmaps the number of mipmap levels
     * @param pixelFlags the pixel format flags
     * @param fourCc the four-character code, or {@code 0}
     * @param bits the bits per texel of a mask format, or {@code 0}
     * @param masks the red, green, blue and alpha masks
     * @param extension the DX10 extension, or {@code null}
     * @param data the surface data
     * @return the file
     */
    private static byte[] ddsHeader(int width, int height, int mipmaps, int pixelFlags, int fourCc, int bits,
                                    int[] masks, byte[] extension, byte[] data) {
        var buffer = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put((byte) 'D').put((byte) 'D').put((byte) 'S').put((byte) ' ');
        buffer.putInt(124).putInt(0x1007 | (mipmaps > 1 ? 0x20000 : 0)).putInt(height).putInt(width).putInt(0);
        buffer.putInt(1).putInt(mipmaps);
        buffer.position(76);
        buffer.putInt(32).putInt(pixelFlags).putInt(fourCc).putInt(bits);
        for (var mask : masks) {
            buffer.putInt(mask);
        }
        buffer.putInt(0x1000 | (mipmaps > 1 ? 0x400008 : 0)).putInt(0);
        var out = new ByteArrayOutputStream();
        out.writeBytes(buffer.array());
        if (extension != null) {
            out.writeBytes(extension);
        }
        out.writeBytes(data);
        return out.toByteArray();
    }

    /**
     * {@return the little-endian value of a four-character code}
     *
     */
    private static int fourCc(String code) {
        return code.charAt(0) | code.charAt(1) << 8 | code.charAt(2) << 16 | code.charAt(3) << 24;
    }

    /**
     * {@return bytes compressed with zlib}
     *
     */
    private static byte[] deflate(byte[] bytes) {
        var deflater = new Deflater();
        deflater.setInput(bytes);
        deflater.finish();
        var out = new ByteArrayOutputStream();
        var buffer = new byte[8192];
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        return out.toByteArray();
    }
}
