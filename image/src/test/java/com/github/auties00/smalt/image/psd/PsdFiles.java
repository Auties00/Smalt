package com.github.auties00.smalt.image.psd;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.zip.Deflater;

/**
 * Builds Photoshop documents for the tests from channel planes.
 */
final class PsdFiles {
    private final int version;
    private final int width;
    private final int height;
    private final int depth;
    private final int mode;
    private byte[] colorData = new byte[0];
    private final ByteArrayOutputStream resources = new ByteArrayOutputStream();
    private int layerCount;
    private boolean tagged;

    PsdFiles(int version, int width, int height, int depth, int mode) {
        this.version = version;
        this.width = width;
        this.height = height;
        this.depth = depth;
        this.mode = mode;
    }

    PsdFiles colorData(byte[] data) {
        this.colorData = data;
        return this;
    }

    /** Declares a layer section whose layer count is the given value (negative for a transparent composite). */
    PsdFiles layerCount(int count) {
        this.layerCount = count;
        return this;
    }

    /**
     * Declares the layer count in a {@code Lr16} block of the additional layer information, behind an empty layer
     * info and global mask, as Photoshop writes 16 and 32-bit documents.
     */
    PsdFiles taggedLayerCount(int count) {
        this.layerCount = count;
        this.tagged = true;
        return this;
    }

    PsdFiles resource(int id, byte[] data) {
        try {
            var out = new DataOutputStream(resources);
            out.writeBytes("8BIM");
            out.writeShort(id);
            out.writeShort(0);
            out.writeInt(data.length);
            out.write(data);
            if ((data.length & 1) != 0) {
                out.write(0);
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return this;
    }

    int rowBytes() {
        return depth == 1 ? (width + 7) / 8 : width * depth / 8;
    }

    /** Writes the document with the planes compressed as {@code compression} (0 raw, 1 RLE, 2 ZIP, 3 ZIP + prediction). */
    byte[] build(int compression, byte[]... planes) {
        try {
            var bytes = new ByteArrayOutputStream();
            var out = new DataOutputStream(bytes);
            out.writeBytes("8BPS");
            out.writeShort(version);
            out.write(new byte[6]);
            out.writeShort(planes.length);
            out.writeInt(height);
            out.writeInt(width);
            out.writeShort(depth);
            out.writeShort(mode);
            out.writeInt(colorData.length);
            out.write(colorData);
            out.writeInt(resources.size());
            resources.writeTo(out);
            if (layerCount == 0) {
                if (version == 2) {
                    out.writeLong(0);
                } else {
                    out.writeInt(0);
                }
            } else if (tagged) {
                // an empty layer info and global mask, then an unrelated block and the Lr16 block (8-byte
                // length in a PSB)
                var lengthSize = version == 2 ? 8 : 4;
                var other = 4 + 4 + 4 + 4;
                var section = lengthSize + 4 + other + 4 + 4 + lengthSize + 2;
                if (version == 2) {
                    out.writeLong(section);
                    out.writeLong(0);
                } else {
                    out.writeInt(section);
                    out.writeInt(0);
                }
                out.writeInt(0);
                out.writeBytes("8BIMPatt");
                out.writeInt(4);
                out.writeInt(0);
                out.writeBytes("8BIMLr16");
                if (version == 2) {
                    out.writeLong(2);
                } else {
                    out.writeInt(2);
                }
                out.writeShort(layerCount);
            } else {
                // a layer info block holding only the count, then an empty global mask block
                if (version == 2) {
                    out.writeLong(8 + 2 + 4 + 2);
                    out.writeLong(2);
                } else {
                    out.writeInt(4 + 2 + 4 + 2);
                    out.writeInt(2);
                }
                out.writeShort(layerCount);
                out.writeInt(0);
                out.writeShort(0);
            }
            out.writeShort(compression);
            var rows = rowBytes();
            switch (compression) {
                case 0 -> {
                    for (var plane : planes) {
                        out.write(plane);
                    }
                }
                case 1 -> {
                    var packed = new byte[planes.length * height][];
                    for (var channel = 0; channel < planes.length; channel++) {
                        for (var y = 0; y < height; y++) {
                            packed[channel * height + y] = packBits(planes[channel], y * rows, rows);
                        }
                    }
                    for (var row : packed) {
                        if (version == 2) {
                            out.writeInt(row.length);
                        } else {
                            out.writeShort(row.length);
                        }
                    }
                    for (var row : packed) {
                        out.write(row);
                    }
                }
                default -> {
                    var deflater = new Deflater();
                    var all = new ByteArrayOutputStream();
                    for (var plane : planes) {
                        all.write(compression == 3 ? predict(plane, rows) : plane);
                    }
                    deflater.setInput(all.toByteArray());
                    deflater.finish();
                    var buffer = new byte[8192];
                    while (!deflater.finished()) {
                        out.write(buffer, 0, deflater.deflate(buffer));
                    }
                    deflater.end();
                }
            }
            return bytes.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private byte[] predict(byte[] plane, int rows) {
        var out = plane.clone();
        for (var y = 0; y < height; y++) {
            var base = y * rows;
            switch (depth) {
                case 16 -> {
                    for (var x = width - 1; x > 0; x--) {
                        var at = base + 2 * x;
                        var value = ((plane[at] & 0xFF) << 8 | (plane[at + 1] & 0xFF))
                                - ((plane[at - 2] & 0xFF) << 8 | (plane[at - 1] & 0xFF));
                        out[at] = (byte) (value >>> 8);
                        out[at + 1] = (byte) value;
                    }
                }
                case 32 -> {
                    var shuffled = new byte[rows];
                    for (var x = 0; x < width; x++) {
                        for (var b = 0; b < 4; b++) {
                            shuffled[b * width + x] = plane[base + 4 * x + b];
                        }
                    }
                    for (var x = rows - 1; x > 0; x--) {
                        out[base + x] = (byte) (shuffled[x] - shuffled[x - 1]);
                    }
                    out[base] = shuffled[0];
                }
                default -> {
                    for (var x = rows - 1; x > 0; x--) {
                        out[base + x] = (byte) (plane[base + x] - plane[base + x - 1]);
                    }
                }
            }
        }
        return out;
    }

    /**
     * Returns a copy of a document whose version information resource (1057) says it has no merged image, adding the
     * resource when the document has none, so that decoders render the composite from the layers.
     */
    static byte[] withoutComposite(byte[] psd) {
        var position = 26;
        var colorLength = (int) u32(psd, position);
        position += 4 + colorLength;
        var resourcesLength = (int) u32(psd, position);
        var resourcesStart = position + 4;
        var at = resourcesStart;
        var end = resourcesStart + resourcesLength;
        while (at + 12 <= end) {
            var id = (psd[at + 4] & 0xFF) << 8 | (psd[at + 5] & 0xFF);
            var nameBytes = (1 + (psd[at + 6] & 0xFF) + 1) & ~1;
            var sizeAt = at + 6 + nameBytes;
            var size = (int) u32(psd, sizeAt);
            if (id == 1057 && size >= 5) {
                var copy = psd.clone();
                copy[sizeAt + 4 + 4] = 0;
                return copy;
            }
            at = sizeAt + 4 + ((size + 1) & ~1);
        }
        try {
            var resource = new ByteArrayOutputStream();
            var out = new DataOutputStream(resource);
            out.writeBytes("8BIM");
            out.writeShort(1057);
            out.writeShort(0);
            out.writeInt(17);
            out.writeInt(1);
            out.writeByte(0);
            out.writeInt(0);
            out.writeInt(0);
            out.writeInt(1);
            out.writeByte(0);
            var added = resource.toByteArray();
            var result = new ByteArrayOutputStream();
            result.write(psd, 0, resourcesStart - 4);
            new DataOutputStream(result).writeInt(resourcesLength + added.length);
            result.write(psd, resourcesStart, resourcesLength);
            result.write(added);
            result.write(psd, end, psd.length - end);
            return result.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static long u32(byte[] data, int at) {
        return (data[at] & 0xFFL) << 24 | (data[at + 1] & 0xFFL) << 16 | (data[at + 2] & 0xFFL) << 8
                | (data[at + 3] & 0xFFL);
    }

    /** PackBits: runs of three or more equal bytes as run packets, the rest as literal packets. */
    static byte[] packBits(byte[] data, int offset, int length) {
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
}
