package com.github.auties00.smalt.image.bmp;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * A test-only BMP writer for every header version, bit depth and compression, with knobs for the
 * broken structures browsers tolerate.
 */
final class BmpFiles {
    int headerSize = 40;
    int width;
    int height;
    int bitCount;
    int compression;
    int[] masks;
    boolean masksAfterHeader;
    int[] palette;
    int colorsUsed;
    byte[] pixelData;
    Long dataOffset;
    byte[] profile;

    static BmpFiles of(int width, int height, int bitCount) {
        var files = new BmpFiles();
        files.width = width;
        files.height = height;
        files.bitCount = bitCount;
        return files;
    }

    BmpFiles header(int size) {
        headerSize = size;
        return this;
    }

    BmpFiles compression(int value) {
        compression = value;
        return this;
    }

    BmpFiles masks(int... values) {
        masks = values;
        return this;
    }

    BmpFiles palette(int[] colors, int used) {
        palette = colors;
        colorsUsed = used;
        return this;
    }

    BmpFiles pixels(byte[] data) {
        pixelData = data;
        return this;
    }

    BmpFiles offset(long value) {
        dataOffset = value;
        return this;
    }

    BmpFiles profile(byte[] data) {
        profile = data;
        return this;
    }

    byte[] bytes() {
        var info = ByteBuffer.allocate(Math.max(headerSize, 124)).order(ByteOrder.LITTLE_ENDIAN);
        info.putInt(0, headerSize);
        if (headerSize == 12) {
            info.putShort(4, (short) width);
            info.putShort(6, (short) height);
            info.putShort(8, (short) 1);
            info.putShort(10, (short) bitCount);
        } else {
            info.putInt(4, width);
            info.putInt(8, height);
            info.putShort(12, (short) 1);
            info.putShort(14, (short) bitCount);
            if (headerSize >= 20) {
                info.putInt(16, compression);
            }
            if (headerSize >= 24) {
                info.putInt(20, pixelData == null ? 0 : pixelData.length);
            }
            if (headerSize >= 36) {
                info.putInt(32, colorsUsed);
            }
            if (masks != null && headerSize >= 52) {
                for (var i = 0; i < Math.min(masks.length, headerSize >= 56 ? 4 : 3); i++) {
                    info.putInt(40 + i * 4, masks[i]);
                }
            }
        }
        var extraMasks = masks != null && headerSize < 52 && headerSize != 12 && (compression == 3 || compression == 6)
                ? (compression == 6 ? 4 : 3) : 0;
        var entrySize = headerSize == 12 ? 3 : 4;
        var paletteBytes = palette == null ? 0 : palette.length * entrySize;
        var headerEnd = 14 + headerSize + extraMasks * 4;
        var offset = dataOffset != null && dataOffset != 0 ? dataOffset : headerEnd + paletteBytes;
        var offsetField = dataOffset != null ? dataOffset : offset;
        var profileOffset = offset + (pixelData == null ? 0 : pixelData.length);
        if (profile != null && headerSize >= 124) {
            info.putInt(56, 0x4D424544);
            info.putInt(112, (int) (profileOffset - 14));
            info.putInt(116, profile.length);
        }
        var out = new ByteArrayOutputStream();
        var file = ByteBuffer.allocate(14).order(ByteOrder.LITTLE_ENDIAN);
        file.put((byte) 'B').put((byte) 'M');
        file.putInt(2, 0x12345);
        file.putInt(10, (int) offsetField);
        out.writeBytes(file.array());
        out.write(info.array(), 0, headerSize);
        for (var i = 0; i < extraMasks; i++) {
            var mask = i < masks.length ? masks[i] : 0;
            out.writeBytes(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(mask).array());
        }
        if (palette != null) {
            for (var color : palette) {
                out.write(color);
                out.write(color >>> 8);
                out.write(color >>> 16);
                if (entrySize == 4) {
                    out.write(0x5A);
                }
            }
        }
        while (out.size() < offset) {
            out.write(0);
        }
        var bytes = out.toByteArray();
        if (bytes.length > offset) {
            bytes = java.util.Arrays.copyOf(bytes, (int) offset);
            out.reset();
            out.writeBytes(bytes);
        }
        if (pixelData != null) {
            out.writeBytes(pixelData);
        }
        if (profile != null) {
            out.writeBytes(profile);
        }
        return out.toByteArray();
    }

    /** Packs pixel values (indices or packed pixels) into bottom-up rows padded to 4 bytes. */
    static byte[] rows(int width, int height, int bitCount, int[] values, boolean topDown) {
        var stride = (width * bitCount + 31) / 32 * 4;
        var out = new byte[stride * height];
        for (var y = 0; y < height; y++) {
            var stored = topDown ? y : height - 1 - y;
            var row = stored * stride;
            for (var x = 0; x < width; x++) {
                var value = values[y * width + x];
                switch (bitCount) {
                    case 1, 2, 4 -> {
                        var bit = x * bitCount;
                        out[row + bit / 8] |= (byte) (value << (8 - bitCount - bit % 8));
                    }
                    case 8 -> out[row + x] = (byte) value;
                    case 16 -> {
                        out[row + x * 2] = (byte) value;
                        out[row + x * 2 + 1] = (byte) (value >>> 8);
                    }
                    case 24 -> {
                        out[row + x * 3] = (byte) value;
                        out[row + x * 3 + 1] = (byte) (value >>> 8);
                        out[row + x * 3 + 2] = (byte) (value >>> 16);
                    }
                    default -> {
                        for (var b = 0; b < 4; b++) {
                            out[row + x * 4 + b] = (byte) (value >>> (8 * b));
                        }
                    }
                }
            }
        }
        return out;
    }
}
