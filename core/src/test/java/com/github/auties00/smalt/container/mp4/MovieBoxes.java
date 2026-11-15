package com.github.auties00.smalt.container.mp4;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.zip.Deflater;

/**
 * Rewrites the movie box of a file for the QuickTime compressed movie and data reference tests: replaces the
 * top-level {@code moov}, compresses it into a {@code cmov} atom, and replaces the {@code dref} box of each track.
 * The rewritten movies keep 32-bit box sizes, as every movie these tests rewrite has them.
 */
final class MovieBoxes {
    /** The boxes walked on the way from {@code moov} to the {@code dref} of each track. */
    private static final Set<String> CONTAINERS = Set.of("moov", "trak", "mdia", "minf", "dinf");

    private MovieBoxes() {
    }

    /** One top-level box: its offset, size and type. */
    record TopBox(int offset, int size, String type) {
    }

    static List<TopBox> topLevel(byte[] file) {
        var boxes = new ArrayList<TopBox>();
        var position = 0;
        while (position + 8 <= file.length) {
            var size = ByteBuffer.wrap(file, position, 4).getInt();
            boxes.add(new TopBox(position, size, new String(file, position + 4, 4, StandardCharsets.ISO_8859_1)));
            position += size;
        }
        return boxes;
    }

    static TopBox find(byte[] file, String type) {
        return topLevel(file).stream().filter(box -> box.type().equals(type)).findFirst().orElseThrow();
    }

    static byte[] box(String type, byte[] payload) {
        return ByteBuffer.allocate(8 + payload.length).putInt(8 + payload.length)
                .put(type.getBytes(StandardCharsets.ISO_8859_1)).put(payload).array();
    }

    static byte[] fullBox(String type, int flags, byte[] payload) {
        return box(type, ByteBuffer.allocate(4 + payload.length).putInt(flags).put(payload).array());
    }

    /** Returns the file with its top-level box of a type replaced by other bytes. */
    static byte[] replace(byte[] file, String type, byte[] replacement) {
        var box = find(file, type);
        var out = new ByteArrayOutputStream();
        out.write(file, 0, box.offset());
        out.writeBytes(replacement);
        out.write(file, box.offset() + box.size(), file.length - box.offset() - box.size());
        return out.toByteArray();
    }

    /** Returns the {@code moov} box of a file, header included. */
    static byte[] movie(byte[] file) {
        var box = find(file, "moov");
        var movie = new byte[box.size()];
        System.arraycopy(file, box.offset(), movie, 0, box.size());
        return movie;
    }

    /** Compresses a {@code moov} box into a {@code moov} holding a {@code cmov} atom with the given algorithm. */
    static byte[] cmov(byte[] moov, String algorithm) {
        var deflater = new Deflater();
        deflater.setInput(moov);
        deflater.finish();
        var compressed = new ByteArrayOutputStream();
        var buffer = new byte[8192];
        while (!deflater.finished()) {
            compressed.write(buffer, 0, deflater.deflate(buffer));
        }
        deflater.end();
        var cmvd = ByteBuffer.allocate(4 + compressed.size()).putInt(moov.length).put(compressed.toByteArray()).array();
        var children = new ByteArrayOutputStream();
        children.writeBytes(box("dcom", algorithm.getBytes(StandardCharsets.ISO_8859_1)));
        children.writeBytes(box("cmvd", cmvd));
        return box("moov", box("cmov", children.toByteArray()));
    }

    /** Pads a box with a {@code free} box to a size, which must leave at least 8 bytes for it. */
    static byte[] padded(byte[] box, int size) {
        if (size - box.length < 8) {
            throw new AssertionError("no room for a free box: " + box.length + " of " + size);
        }
        var out = new ByteArrayOutputStream();
        out.writeBytes(box);
        out.writeBytes(box("free", new byte[size - box.length - 8]));
        return out.toByteArray();
    }

    /**
     * Rebuilds a {@code moov} box, replacing the payload of the {@code dref} box of each track by what the editor
     * returns for the track's index and the old payload.
     */
    static byte[] editReferences(byte[] moov, BiFunction<Integer, byte[], byte[]> editor) {
        var tracks = new int[]{0};
        var payload = rebuild(moov, 8, moov.length, "moov", tracks, editor);
        return box("moov", payload);
    }

    private static byte[] rebuild(byte[] data, int from, int to, String parent, int[] tracks,
                                  BiFunction<Integer, byte[], byte[]> editor) {
        var out = new ByteArrayOutputStream();
        var position = from;
        while (position + 8 <= to) {
            var size = ByteBuffer.wrap(data, position, 4).getInt();
            var type = new String(data, position + 4, 4, StandardCharsets.ISO_8859_1);
            var payload = new byte[size - 8];
            System.arraycopy(data, position + 8, payload, 0, payload.length);
            if (type.equals("trak")) {
                tracks[0]++;
            }
            if (CONTAINERS.contains(type)) {
                payload = rebuild(data, position + 8, position + size, type, tracks, editor);
            } else if (type.equals("dref") && parent.equals("dinf")) {
                payload = editor.apply(tracks[0] - 1, payload);
            }
            out.writeBytes(box(type, payload));
            position += size;
        }
        return out.toByteArray();
    }

    /** Rebuilds a {@code moov} box with the chunk offsets ({@code stco} or {@code co64}) of one track moved by a delta. */
    static byte[] shiftChunkOffsets(byte[] moov, int track, long delta) {
        var tracks = new int[]{0};
        return box("moov", shift(moov, 8, moov.length, tracks, track, delta));
    }

    private static byte[] shift(byte[] data, int from, int to, int[] tracks, int track, long delta) {
        var out = new ByteArrayOutputStream();
        var position = from;
        while (position + 8 <= to) {
            var size = ByteBuffer.wrap(data, position, 4).getInt();
            var type = new String(data, position + 4, 4, StandardCharsets.ISO_8859_1);
            var payload = new byte[size - 8];
            System.arraycopy(data, position + 8, payload, 0, payload.length);
            if (type.equals("trak")) {
                tracks[0]++;
            }
            if (type.equals("stbl") || CONTAINERS.contains(type)) {
                payload = shift(data, position + 8, position + size, tracks, track, delta);
            } else if ((type.equals("stco") || type.equals("co64")) && tracks[0] - 1 == track) {
                var buffer = ByteBuffer.wrap(payload);
                var count = buffer.getInt(4);
                for (var i = 0; i < count; i++) {
                    if (type.equals("stco")) {
                        buffer.putInt(8 + 4 * i, Math.toIntExact(Integer.toUnsignedLong(buffer.getInt(8 + 4 * i)) + delta));
                    } else {
                        buffer.putLong(8 + 8 * i, buffer.getLong(8 + 8 * i) + delta);
                    }
                }
            }
            out.writeBytes(box(type, payload));
            position += size;
        }
        return out.toByteArray();
    }

    /** Returns the payload of a {@code dref} box holding the given entries. */
    static byte[] dref(byte[]... entries) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[4]);
        out.writeBytes(ByteBuffer.allocate(4).putInt(entries.length).array());
        for (var entry : entries) {
            out.writeBytes(entry);
        }
        return out.toByteArray();
    }

    /** Returns a {@code url } entry naming another file. */
    static byte[] url(String location) {
        var bytes = location.getBytes(StandardCharsets.UTF_8);
        return fullBox("url ", 0, java.util.Arrays.copyOf(bytes, bytes.length + 1));
    }

    /** Returns a self-contained {@code url } entry. */
    static byte[] self() {
        return fullBox("url ", 1, new byte[0]);
    }

    /**
     * Returns an {@code alis} entry holding a version 2 Mac OS alias record of a file one level below a volume: its
     * file name, its Mac OS path (tag 2) and its POSIX path (tag 18), with the levels from and to the common
     * directory of 1, so that a reader resolves it next to the referencing file.
     */
    static byte[] alias(String volume, String fileName) {
        var record = ByteBuffer.allocate(150);
        record.putInt(0);
        record.putShort((short) 0);
        record.putShort((short) 2);
        record.putShort((short) 0);
        var name = volume.getBytes(StandardCharsets.ISO_8859_1);
        record.position(10);
        record.put((byte) name.length).put(name);
        var file = fileName.getBytes(StandardCharsets.ISO_8859_1);
        record.position(50);
        record.put((byte) file.length).put(file);
        record.position(130);
        record.putShort((short) 1);
        record.putShort((short) 1);
        var out = new ByteArrayOutputStream();
        out.writeBytes(record.array());
        tag(out, 2, (volume + ":" + fileName).getBytes(StandardCharsets.ISO_8859_1));
        tag(out, 18, ("/" + fileName).getBytes(StandardCharsets.UTF_8));
        out.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFF, 0, 0});
        var bytes = out.toByteArray();
        ByteBuffer.wrap(bytes).putShort(4, (short) bytes.length);
        return fullBox("alis", 0, bytes);
    }

    private static void tag(ByteArrayOutputStream out, int tag, byte[] data) {
        out.writeBytes(ByteBuffer.allocate(4).putShort((short) tag).putShort((short) data.length).array());
        out.writeBytes(data);
        if ((data.length & 1) != 0) {
            out.write(0);
        }
    }
}
