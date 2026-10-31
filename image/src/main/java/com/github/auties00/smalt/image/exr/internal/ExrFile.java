/* Ports OpenEXR 3.3.5 (src/lib/OpenEXRCore: the version field, part headers and chunk offset
   tables). Copyright Contributors to the OpenEXR Project;
   BSD-3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.image.exr.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds a parsed OpenEXR file: its part headers and the offset of every chunk, and decodes the samples of a part.
 */
public final class ExrFile {
    private final byte[] data;

    private final List<ExrHeader> parts;

    private final long[][] offsets;

    private final boolean multipart;

    private ExrFile(byte[] data, List<ExrHeader> parts, long[][] offsets, boolean multipart) {
        this.data = data;
        this.parts = parts;
        this.offsets = offsets;
        this.multipart = multipart;
    }

    /**
     * Parses a file.
     *
     * @param data the whole file
     * @param limits the resource bounds the part count is checked against
     * @return the file
     * @throws MediaException.Malformed if the file is invalid
     * @throws MediaException.Unsupported if the file uses a feature the decoder lacks
     * @throws MediaException.LimitExceeded if the file has more parts than the frame limit
     */
    public static ExrFile read(byte[] data, MediaLimits limits) {
        if (data.length < 8 || ExrHeader.i32(data, 0) != 20000630) {
            throw new MediaException.Malformed("invalid OpenEXR magic number");
        }
        var version = ExrHeader.i32(data, 4);
        if ((version & 0xFF) != 2) {
            throw new MediaException.Unsupported("exr", "file version " + (version & 0xFF));
        }
        var flags = version & ~0xFF;
        if ((flags & ~(0x200 | 0x400 | 0x800 | 0x1000)) != 0) {
            throw new MediaException.Unsupported("exr", "version flags 0x" + Integer.toHexString(flags));
        }
        var multipart = (flags & 0x1000) != 0;
        var parts = new ArrayList<ExrHeader>();
        var position = 8;
        var end = new int[1];
        if (multipart) {
            while (true) {
                if (position >= data.length) {
                    throw new MediaException.Malformed("the OpenEXR part headers end early");
                }
                if (data[position] == 0) {
                    position++;
                    break;
                }
                parts.add(ExrHeader.read(data, position, flags, true, end));
                position = end[0];
                LimitChecks.checkFrames(limits, parts.size());
            }
            if (parts.isEmpty()) {
                throw new MediaException.Malformed("the OpenEXR file has no part");
            }
        } else {
            parts.add(ExrHeader.read(data, position, flags, false, end));
            position = end[0];
        }
        var offsets = new long[parts.size()][];
        for (var part = 0; part < parts.size(); part++) {
            var header = parts.get(part);
            long count;
            var declared = header.attribute("chunkCount");
            if (multipart || declared != null) {
                if (declared == null || declared.length < 4) {
                    throw new MediaException.Malformed("an OpenEXR part has no chunk count");
                }
                count = ExrHeader.i32(declared, 0);
            } else {
                count = header.chunkCount();
            }
            if (count < 0 || position + 8 * count > data.length) {
                throw new MediaException.Malformed("the OpenEXR offset table runs past the file");
            }
            LimitChecks.checkAllocation(limits, 8 * count);
            var table = new long[(int) count];
            for (var index = 0; index < count; index++) {
                table[index] = ExrHeader.i64(data, position + 8 * index);
            }
            offsets[part] = table;
            position += (int) (8 * count);
        }
        var file = new ExrFile(data, List.copyOf(parts), offsets, multipart);
        file.repairOffsets(position);
        return file;
    }

    /**
     * Replaces an offset table holding an entry outside the file by the offsets found by walking the chunks that
     * follow the tables, as OpenEXR reconstructs the table of an incompletely written file.
     */
    private void repairOffsets(int firstChunk) {
        var broken = false;
        for (var table : offsets) {
            for (var offset : table) {
                if (offset < firstChunk || offset >= data.length) {
                    broken = true;
                    break;
                }
            }
        }
        if (!broken) {
            return;
        }
        var cursors = new int[parts.size()];
        var rebuilt = new long[parts.size()][];
        for (var part = 0; part < parts.size(); part++) {
            rebuilt[part] = new long[offsets[part].length];
            java.util.Arrays.fill(rebuilt[part], -1);
        }
        long position = firstChunk;
        while (position + 8 <= data.length) {
            var at = (int) position;
            var part = 0;
            if (multipart) {
                part = ExrHeader.i32(data, at);
                at += 4;
                if (part < 0 || part >= parts.size()) {
                    break;
                }
            }
            var header = parts.get(part);
            var length = chunkLength(header, at);
            if (length < 0 || at + length > data.length) {
                break;
            }
            var index = chunkIndex(header, at);
            if (index < 0 || index >= rebuilt[part].length) {
                index = cursors[part];
            }
            if (index < rebuilt[part].length) {
                rebuilt[part][index] = position;
                cursors[part]++;
            }
            position = at + length;
        }
        for (var part = 0; part < parts.size(); part++) {
            for (var index = 0; index < offsets[part].length; index++) {
                var original = offsets[part][index];
                if (original < firstChunk || original >= data.length) {
                    offsets[part][index] = rebuilt[part][index];
                }
            }
        }
    }

    /**
     * {@return the bytes of the chunk whose header starts after the part number, or -1}
     */
    private long chunkLength(ExrHeader header, int at) {
        if (header.deep()) {
            var fixed = header.tiled() ? 16 : 4;
            if (at + fixed + 24 > data.length) {
                return -1;
            }
            var table = ExrHeader.i64(data, at + fixed);
            var packed = ExrHeader.i64(data, at + fixed + 8);
            if (table < 0 || packed < 0) {
                return -1;
            }
            return fixed + 24 + table + packed;
        }
        var fixed = header.tiled() ? 16 : 4;
        if (at + fixed + 4 > data.length) {
            return -1;
        }
        var size = ExrHeader.i32(data, at + fixed);
        return size < 0 ? -1 : fixed + 4L + size;
    }

    /**
     * {@return the offset table index of the chunk whose header starts after the part number, or -1}
     */
    private int chunkIndex(ExrHeader header, int at) {
        if (!header.tiled()) {
            var y = ExrHeader.i32(data, at);
            var relative = (long) y - header.yMin();
            if (relative < 0 || relative % header.linesPerChunk() != 0) {
                return -1;
            }
            return (int) Math.min(Integer.MAX_VALUE, relative / header.linesPerChunk());
        }
        var tx = ExrHeader.i32(data, at);
        var ty = ExrHeader.i32(data, at + 4);
        var lx = ExrHeader.i32(data, at + 8);
        var ly = ExrHeader.i32(data, at + 12);
        var index = tileIndex(header, tx, ty, lx, ly);
        return index > Integer.MAX_VALUE ? -1 : (int) index;
    }

    /**
     * {@return the offset table index of a tile, or -1 when the tile does not exist}
     *
     * @param header the part
     * @param tx the tile column
     * @param ty the tile row
     * @param lx the horizontal level
     * @param ly the vertical level
     */
    public static long tileIndex(ExrHeader header, int tx, int ty, int lx, int ly) {
        if (lx < 0 || ly < 0 || lx >= header.levelsX() || ly >= header.levelsY()
                || (header.levelMode() != ExrHeader.RIPMAP_LEVELS && lx != ly)) {
            return -1;
        }
        var columns = ExrHeader.tiles(header.levelSize(header.width(), lx), header.tileWidth());
        var rows = ExrHeader.tiles(header.levelSize(header.height(), ly), header.tileHeight());
        if (tx < 0 || ty < 0 || tx >= columns || ty >= rows) {
            return -1;
        }
        var index = 0L;
        if (header.levelMode() == ExrHeader.RIPMAP_LEVELS) {
            for (var y = 0; y <= ly; y++) {
                for (var x = 0; x < header.levelsX(); x++) {
                    if (y == ly && x == lx) {
                        return index + ty * columns + tx;
                    }
                    index += ExrHeader.tiles(header.levelSize(header.width(), x), header.tileWidth())
                            * ExrHeader.tiles(header.levelSize(header.height(), y), header.tileHeight());
                }
            }
        }
        for (var level = 0; level < lx; level++) {
            index += ExrHeader.tiles(header.levelSize(header.width(), level), header.tileWidth())
                    * ExrHeader.tiles(header.levelSize(header.height(), level), header.tileHeight());
        }
        return index + ty * columns + tx;
    }

    /**
     * {@return the part headers}
     */
    public List<ExrHeader> parts() {
        return parts;
    }

    /**
     * {@return the bytes of the file}
     */
    public byte[] data() {
        return data;
    }

    /**
     * {@return whether the file holds several parts}
     */
    public boolean multipart() {
        return multipart;
    }

    /**
     * {@return the offset of a chunk, after its part number in a multi-part file}
     *
     * @param part the part index
     * @param index the offset table index
     * @throws MediaException.Malformed if the chunk is missing or belongs to another part
     */
    public int chunk(int part, long index) {
        if (index < 0 || index >= offsets[part].length) {
            throw new MediaException.Malformed("the OpenEXR part " + part + " has no chunk " + index);
        }
        var offset = offsets[part][(int) index];
        if (offset < 8 || offset + 8 > data.length) {
            throw new MediaException.Malformed("the OpenEXR chunk " + index + " of part " + part
                    + " lies outside the file");
        }
        var at = (int) offset;
        if (multipart) {
            if (ExrHeader.i32(data, at) != part) {
                throw new MediaException.Malformed("the OpenEXR chunk " + index + " belongs to another part");
            }
            at += 4;
        }
        return at;
    }
}
