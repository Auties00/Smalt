package com.github.auties00.smalt.image.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteArrays;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Moves the absolute file offsets that the track boxes of an ISO-BMFF file hold (ISO/IEC
 * 14496-12): the chunk offsets of {@code stco} and {@code co64}, the auxiliary-information offsets
 * of a {@code saio} box in a sample table, the base data offset of a {@code tfhd} box, and the
 * fragment offsets of a {@code tfra} box.
 */
final class TrackOffsets {
    /**
     * The boxes whose children are boxes, among those that can lead to an offset.
     */
    private static final Set<String> CONTAINERS = Set.of("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex",
            "moof", "traf", "mfra", "udta", "tref", "sinf", "schi");

    /**
     * The deepest box nesting followed.
     */
    private static final int MAX_DEPTH = 16;

    /**
     * Prevents instantiation.
     */
    private TrackOffsets() {
        throw new AssertionError();
    }

    /**
     * Adds a shift to every offset at or after a position.
     *
     * @param box a top-level {@code moov}, {@code moof} or {@code mfra} box, patched in place
     * @param from the first original offset that moves
     * @param delta the shift
     * @throws MediaException.Malformed if a box is corrupt
     * @throws MediaException.Unsupported if a track carries item locations of its own (a
     * {@code meta} box with an {@code iloc} box)
     * @throws MediaException.LimitExceeded if a moved offset does not fit its field
     */
    static void shift(byte[] box, long from, long delta) {
        walk(box, 0, box.length, from, delta, 0, false);
    }

    /**
     * Patches the boxes of a range.
     *
     * @param data the top-level box
     * @param start the offset of the first box
     * @param end the end of the range
     * @param from the first original offset that moves
     * @param delta the shift
     * @param depth the nesting depth
     * @param inSampleTable whether the range is the content of a {@code stbl} box
     */
    private static void walk(byte[] data, int start, int end, long from, long delta, int depth, boolean inSampleTable) {
        if (depth > MAX_DEPTH) {
            throw new MediaException.Malformed("ISO-BMFF boxes nested more than " + MAX_DEPTH + " deep");
        }
        var at = start;
        while (end - at >= 8) {
            long size = ByteArrays.u32be(data, at);
            var header = 8;
            if (size == 1) {
                if (end - at < 16) {
                    throw new MediaException.Malformed("truncated ISO-BMFF box header");
                }
                size = ByteArrays.i64be(data, at + 8);
                header = 16;
            } else if (size == 0) {
                size = end - at;
            }
            if (size < header || size > end - at) {
                throw new MediaException.Malformed("invalid ISO-BMFF box size " + size + " in a track box");
            }
            var type = new String(data, at + 4, 4, StandardCharsets.ISO_8859_1);
            var payload = at + header;
            var boxEnd = (int) (at + size);
            if (depth == 0 || CONTAINERS.contains(type)) {
                walk(data, payload, boxEnd, from, delta, depth + 1, type.equals("stbl"));
            } else {
                switch (type) {
                    case "stco" -> table(data, payload, boxEnd, 4, from, delta);
                    case "co64" -> table(data, payload, boxEnd, 8, from, delta);
                    case "saio" -> {
                        if (inSampleTable) {
                            saio(data, payload, boxEnd, from, delta);
                        }
                    }
                    case "tfhd" -> tfhd(data, payload, boxEnd, from, delta);
                    case "tfra" -> tfra(data, payload, boxEnd, from, delta);
                    case "meta" -> checkMeta(data, payload + 4, boxEnd);
                    default -> {
                    }
                }
            }
            at = boxEnd;
        }
    }

    /**
     * Patches a chunk offset table.
     *
     * @param data the box bytes
     * @param payload the start of the full box payload (version and flags)
     * @param end the end of the box
     * @param width the size of each offset, 4 or 8
     * @param from the first original offset that moves
     * @param delta the shift
     */
    private static void table(byte[] data, int payload, int end, int width, long from, long delta) {
        if (end - payload < 8) {
            throw new MediaException.Malformed("truncated chunk offset box");
        }
        var count = ByteArrays.u32be(data, payload + 4);
        if (count > (end - payload - 8) / width) {
            throw new MediaException.Malformed("chunk offset box with " + count + " entries runs past its end");
        }
        for (var i = 0; i < count; i++) {
            move(data, payload + 8 + i * width, width, from, delta);
        }
    }

    /**
     * Patches a sample auxiliary information offsets box.
     *
     * @param data the box bytes
     * @param payload the start of the full box payload
     * @param end the end of the box
     * @param from the first original offset that moves
     * @param delta the shift
     */
    private static void saio(byte[] data, int payload, int end, long from, long delta) {
        if (end - payload < 8) {
            throw new MediaException.Malformed("truncated saio box");
        }
        var version = data[payload] & 0xFF;
        var flags = ByteArrays.u32be(data, payload) & 0xFFFFFF;
        var at = payload + 4 + ((flags & 1) != 0 ? 8 : 0);
        if (end - at < 4) {
            throw new MediaException.Malformed("truncated saio box");
        }
        var count = ByteArrays.u32be(data, at);
        var width = version == 0 ? 4 : 8;
        if (count > (end - at - 4) / width) {
            throw new MediaException.Malformed("saio box with " + count + " entries runs past its end");
        }
        for (var i = 0; i < count; i++) {
            move(data, at + 4 + i * width, width, from, delta);
        }
    }

    /**
     * Patches the base data offset of a track fragment header, when present.
     *
     * @param data the box bytes
     * @param payload the start of the full box payload
     * @param end the end of the box
     * @param from the first original offset that moves
     * @param delta the shift
     */
    private static void tfhd(byte[] data, int payload, int end, long from, long delta) {
        if (end - payload < 8) {
            throw new MediaException.Malformed("truncated tfhd box");
        }
        var flags = ByteArrays.u32be(data, payload) & 0xFFFFFF;
        if ((flags & 1) != 0) {
            if (end - payload < 16) {
                throw new MediaException.Malformed("truncated tfhd box");
            }
            move(data, payload + 8, 8, from, delta);
        }
    }

    /**
     * Patches the fragment offsets of a track fragment random access box.
     *
     * @param data the box bytes
     * @param payload the start of the full box payload
     * @param end the end of the box
     * @param from the first original offset that moves
     * @param delta the shift
     */
    private static void tfra(byte[] data, int payload, int end, long from, long delta) {
        if (end - payload < 16) {
            throw new MediaException.Malformed("truncated tfra box");
        }
        var version = data[payload] & 0xFF;
        var sizes = ByteArrays.u32be(data, payload + 8);
        var count = ByteArrays.u32be(data, payload + 12);
        var timeWidth = version == 1 ? 8 : 4;
        var entry = 2 * timeWidth + (int) ((sizes >>> 4 & 3) + (sizes >>> 2 & 3) + (sizes & 3)) + 3;
        if (count > (end - payload - 16) / entry) {
            throw new MediaException.Malformed("tfra box with " + count + " entries runs past its end");
        }
        for (var i = 0; i < count; i++) {
            move(data, payload + 16 + i * entry + timeWidth, timeWidth, from, delta);
        }
    }

    /**
     * Rejects a track-level {@code meta} box that locates items by file offsets.
     *
     * @param data the box bytes
     * @param start the start of the children of {@code meta}
     * @param end the end of {@code meta}
     * @throws MediaException.Unsupported if {@code meta} holds an {@code iloc} box
     */
    private static void checkMeta(byte[] data, int start, int end) {
        var at = start;
        while (end - at >= 8) {
            var size = ByteArrays.u32be(data, at);
            if (size < 8 || size > end - at) {
                return;
            }
            if (new String(data, at + 4, 4, StandardCharsets.ISO_8859_1).equals("iloc")) {
                throw new MediaException.Unsupported("heif", "a HEIF file whose tracks locate items of their own cannot "
                        + "be edited");
            }
            at += (int) size;
        }
    }

    /**
     * Moves one big-endian offset field when it points at or after the position.
     *
     * @param data the bytes
     * @param at the offset of the field
     * @param width the field size, 4 or 8
     * @param from the first original offset that moves
     * @param delta the shift
     * @throws MediaException.LimitExceeded if the moved offset does not fit the field
     */
    private static void move(byte[] data, int at, int width, long from, long delta) {
        var value = width == 4 ? ByteArrays.u32be(data, at) : ByteArrays.i64be(data, at);
        if (value < from) {
            return;
        }
        var moved = value + delta;
        if (moved < 0 || (width == 4 && moved > 0xFFFF_FFFFL)) {
            throw new MediaException.LimitExceeded("a moved track offset " + moved + " does not fit " + 8 * width + " bits");
        }
        if (width == 4) {
            ByteArrays.put32be(data, at, moved);
        } else {
            ByteArrays.put64be(data, at, moved);
        }
    }
}
