package com.github.auties00.smalt.metadata;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Walks the box tree of an MP4 file held in memory, for structural assertions on files the tag editor and the
 * muxers write.
 */
final class Mp4Atoms {
    /** One box: its type, the offset of its header, its header size and its total size. */
    record Box(String type, int offset, int header, int size) {
        int payload() {
            return offset + header;
        }

        int end() {
            return offset + size;
        }
    }

    private Mp4Atoms() {
    }

    static List<Box> children(byte[] data, int start, int end) {
        var result = new ArrayList<Box>();
        var position = start;
        while (end - position >= 8) {
            long size = u32(data, position);
            var header = 8;
            if (size == 1) {
                size = u64(data, position + 8);
                header = 16;
            } else if (size == 0) {
                size = end - position;
            }
            if (size < header || size > end - position) {
                throw new AssertionError("broken box at " + position);
            }
            result.add(new Box(new String(data, position + 4, 4, StandardCharsets.ISO_8859_1), position, header, (int) size));
            position += (int) size;
        }
        return result;
    }

    static List<Box> top(byte[] data) {
        return children(data, 0, data.length);
    }

    /** Returns every box reached by a slash-separated path of types, such as {@code moov/trak/tkhd}. */
    static List<Box> all(byte[] data, String path) {
        var level = top(data);
        var parts = path.split("/");
        var matches = new ArrayList<Box>();
        collect(data, level, parts, 0, matches);
        return matches;
    }

    private static void collect(byte[] data, List<Box> level, String[] parts, int depth, List<Box> matches) {
        for (var box : level) {
            if (!box.type().equals(parts[depth])) {
                continue;
            }
            if (depth == parts.length - 1) {
                matches.add(box);
            } else {
                var start = box.payload() + (box.type().equals("meta") ? 4 : 0);
                collect(data, children(data, start, box.end()), parts, depth + 1, matches);
            }
        }
    }

    /** Returns every box reached by a path below a box, such as {@code mdia/hdlr} below a {@code trak}. */
    static List<Box> all(byte[] data, Box parent, String path) {
        var matches = new ArrayList<Box>();
        collect(data, children(data, parent.payload(), parent.end()), path.split("/"), 0, matches);
        return matches;
    }

    static Box first(byte[] data, Box parent, String path) {
        var matches = all(data, parent, path);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    static long u32(byte[] data, int offset) {
        return ((data[offset] & 0xFFL) << 24) | ((data[offset + 1] & 0xFF) << 16) | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    static long u64(byte[] data, int offset) {
        return (u32(data, offset) << 32) | u32(data, offset + 4);
    }

    static String type(byte[] data, int offset) {
        return new String(data, offset, 4, StandardCharsets.ISO_8859_1);
    }

    /** Returns the track identifier in a {@code trak}'s {@code tkhd}. */
    static long trackId(byte[] data, Box trak) {
        var tkhd = first(data, trak, "tkhd");
        var version = data[tkhd.payload()] & 0xFF;
        return u32(data, tkhd.payload() + (version == 1 ? 20 : 12));
    }

    /** Returns the {@code tkhd} flags of a {@code trak}. */
    static long trackFlags(byte[] data, Box trak) {
        return u32(data, first(data, trak, "tkhd").payload()) & 0xFFFFFF;
    }

    /** Returns the handler type of a {@code trak}. */
    static String handler(byte[] data, Box trak) {
        return type(data, first(data, trak, "mdia/hdlr").payload() + 8);
    }

    /** Returns the track identifiers a {@code trak} references as chapter tracks. */
    static List<Long> chapterReferences(byte[] data, Box trak) {
        var result = new ArrayList<Long>();
        for (var chap : all(data, trak, "tref/chap")) {
            for (var at = chap.payload(); at + 4 <= chap.end(); at += 4) {
                result.add(u32(data, at));
            }
        }
        return result;
    }

    /** Returns a copy of the file whose {@code chpl} box is renamed {@code free}, which hides the Nero chapters. */
    static byte[] withoutNeroChapters(byte[] data) {
        var copy = data.clone();
        var boxes = all(copy, "moov/udta/chpl");
        if (boxes.isEmpty()) {
            throw new AssertionError("no chpl box");
        }
        for (var box : boxes) {
            System.arraycopy("free".getBytes(StandardCharsets.ISO_8859_1), 0, copy, box.offset() + 4, 4);
        }
        return copy;
    }

    /** Returns the {@code data} atoms of an {@code ilst} item as (type, payload) pairs. */
    static List<Object[]> itemData(byte[] data, String code) {
        var result = new ArrayList<Object[]>();
        for (var item : all(data, "moov/udta/meta/ilst/" + code)) {
            for (var atom : children(data, item.payload(), item.end())) {
                if (atom.type().equals("data")) {
                    var payload = java.util.Arrays.copyOfRange(data, atom.payload() + 8, atom.end());
                    result.add(new Object[]{(int) (u32(data, atom.payload()) & 0xFFFFFF), payload});
                }
            }
        }
        return result;
    }
}
