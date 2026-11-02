package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.image.RgbaPicture;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Random;
import java.util.zip.Deflater;

/**
 * Writes the files of the image formats Smalt decodes but does not encode, for the training workloads of their
 * decoders: Photoshop documents and DirectDraw Surface textures, deterministically, from a synthetic picture.
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
     * of the darkness as black, stored inverted as Photoshop stores ink; each document has the picture's alpha as an extra channel, declared the merged image's
     * transparency by a layer count of -1. Sixteen-bit samples repeat each 8-bit value in both bytes.
     *
     * @param picture the picture
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
        var section = ByteBuffer.allocate(large ? 30 : 18);
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
     * Returns a plane with each sample replaced by its difference from the sample to its left, as Photoshop's ZIP
     * with prediction stores 8 and 16-bit samples.
     *
     * @param plane the plane, big-endian samples row by row
     * @param width the width
     * @param height the height
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
     * @param width the width
     * @param height the height
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
     * @param code the code
     */
    private static int fourCc(String code) {
        return code.charAt(0) | code.charAt(1) << 8 | code.charAt(2) << 16 | code.charAt(3) << 24;
    }

    /**
     * {@return bytes compressed with zlib}
     *
     * @param bytes the bytes
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
