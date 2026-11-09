package com.github.auties00.smalt.image.raw;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.Deflater;

/**
 * Writes TIFF and DNG structures for the tests: a first directory, any number of child directories it points to
 * through {@code SubIFDs}, entries of any type, strips or tiles of given bytes, and tags whose values are the
 * offsets of blobs appended to the file (such as {@code ExtraCameraProfiles}).
 */
public final class DngBuilder {
    private final boolean little;
    private final int magic;

    /**
     * The first directory.
     */
    public final Dir first = new Dir();

    private final List<Dir> children = new ArrayList<>();

    public DngBuilder() {
        this(true, 42);
    }

    public DngBuilder(boolean little, int magic) {
        this.little = little;
        this.magic = magic;
    }

    public Dir child() {
        var dir = new Dir();
        children.add(dir);
        return dir;
    }

    public ByteOrder order() {
        return little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
    }

    private record Entry(int tag, int type, int count, byte[] value, int kind) {
    }

    /**
     * One directory: entries, and the strips or tiles its data offsets point to.
     */
    public final class Dir {
        private final List<Entry> entries = new ArrayList<>();
        private byte[][] blocks;
        private boolean tiled;
        private List<byte[]> blobs;
        private int blobTag;

        private ByteBuffer buffer(int size) {
            return ByteBuffer.allocate(size).order(order());
        }

        public Dir remove(int tag) {
            entries.removeIf(entry -> entry.tag == tag);
            return this;
        }

        public Dir raw(int tag, int type, int count, byte[] value) {
            remove(tag);
            entries.add(new Entry(tag, type, count, value, 0));
            return this;
        }

        public Dir shorts(int tag, int... values) {
            var b = buffer(values.length * 2);
            for (var value : values) {
                b.putShort((short) value);
            }
            return raw(tag, 3, values.length, b.array());
        }

        public Dir longs(int tag, long... values) {
            var b = buffer(values.length * 4);
            for (var value : values) {
                b.putInt((int) value);
            }
            return raw(tag, 4, values.length, b.array());
        }

        public Dir rationals(int tag, double... values) {
            var b = buffer(values.length * 8);
            for (var value : values) {
                b.putInt((int) Math.round(value * 1_000_000)).putInt(1_000_000);
            }
            return raw(tag, 5, values.length, b.array());
        }

        public Dir signedRationals(int tag, double... values) {
            var b = buffer(values.length * 8);
            for (var value : values) {
                b.putInt((int) Math.round(value * 10_000)).putInt(10_000);
            }
            return raw(tag, 10, values.length, b.array());
        }

        public Dir floats(int tag, float... values) {
            var b = buffer(values.length * 4);
            for (var value : values) {
                b.putFloat(value);
            }
            return raw(tag, 11, values.length, b.array());
        }

        public Dir bytes(int tag, int... values) {
            var b = new byte[values.length];
            for (var index = 0; index < values.length; index++) {
                b[index] = (byte) values[index];
            }
            return raw(tag, 1, b.length, b);
        }

        public Dir undefined(int tag, byte[] value) {
            return raw(tag, 7, value.length, value);
        }

        public Dir ascii(int tag, String value) {
            var b = (value + "\0").getBytes(StandardCharsets.UTF_8);
            return raw(tag, 2, b.length, b);
        }

        /**
         * Stores the image data in strips of {@code rowsPerStrip} rows.
         */
        public Dir strips(int rowsPerStrip, byte[]... data) {
            remove(322).remove(323).remove(324).remove(325);
            longs(278, rowsPerStrip);
            blocks = data;
            tiled = false;
            return this;
        }

        /**
         * Stores the image data in tiles.
         */
        public Dir tiles(int width, int height, byte[]... data) {
            remove(273).remove(278).remove(279);
            longs(322, width);
            longs(323, height);
            blocks = data;
            tiled = true;
            return this;
        }

        /**
         * Makes a tag whose values are the offsets of blobs appended to the file.
         */
        public Dir blobs(int tag, byte[]... data) {
            blobTag = tag;
            blobs = List.of(data);
            return this;
        }
    }

    public byte[] write() {
        var out = new Output();
        out.bytes(little ? new byte[]{'I', 'I'} : new byte[]{'M', 'M'});
        out.u16(magic);
        out.u32(8);
        var directories = new ArrayList<Dir>();
        directories.add(first);
        directories.addAll(children);
        if (!children.isEmpty()) {
            first.longs(330, new long[children.size()]);
        }
        var offsets = new long[directories.size()];
        var subIfdPatch = -1;
        for (var index = 0; index < directories.size(); index++) {
            var dir = directories.get(index);
            if (dir.blocks != null) {
                var counts = new long[dir.blocks.length];
                for (var k = 0; k < counts.length; k++) {
                    counts[k] = dir.blocks[k].length;
                }
                dir.longs(dir.tiled ? 324 : 273, new long[dir.blocks.length]);
                dir.longs(dir.tiled ? 325 : 279, counts);
            }
            if (dir.blobs != null) {
                dir.longs(dir.blobTag, new long[dir.blobs.size()]);
            }
            dir.entries.sort(Comparator.comparingInt(Entry::tag));
            out.align();
            offsets[index] = out.size();
            out.u16(dir.entries.size());
            var positions = new long[dir.entries.size()];
            for (var e = 0; e < dir.entries.size(); e++) {
                var entry = dir.entries.get(e);
                out.u16(entry.tag);
                out.u16(entry.type);
                out.u32(entry.count);
                positions[e] = out.size();
                if (entry.value.length <= 4) {
                    out.bytes(java.util.Arrays.copyOf(entry.value, 4));
                } else {
                    out.u32(0);
                }
            }
            out.u32(0);
            var valueAt = new long[dir.entries.size()];
            for (var e = 0; e < dir.entries.size(); e++) {
                var entry = dir.entries.get(e);
                if (entry.value.length > 4) {
                    out.align();
                    valueAt[e] = out.size();
                    out.patch32(positions[e], out.size());
                    out.bytes(entry.value);
                } else {
                    valueAt[e] = positions[e];
                }
            }
            if (dir.blocks != null) {
                var tag = dir.tiled ? 324 : 273;
                var e = indexOf(dir, tag);
                for (var k = 0; k < dir.blocks.length; k++) {
                    out.align();
                    out.patch32(valueAt[e] + 4L * k, out.size());
                    out.bytes(dir.blocks[k]);
                }
            }
            if (dir.blobs != null) {
                var e = indexOf(dir, dir.blobTag);
                for (var k = 0; k < dir.blobs.size(); k++) {
                    out.align();
                    out.patch32(valueAt[e] + 4L * k, out.size());
                    out.bytes(dir.blobs.get(k));
                }
            }
            if (index == 0 && !children.isEmpty()) {
                subIfdPatch = (int) valueAt[indexOf(dir, 330)];
            }
        }
        for (var index = 1; index < directories.size(); index++) {
            out.patch32(subIfdPatch + 4L * (index - 1), offsets[index]);
        }
        out.patch32(4, offsets[0]);
        return out.toByteArray();
    }

    private static int indexOf(Dir dir, int tag) {
        for (var index = 0; index < dir.entries.size(); index++) {
            if (dir.entries.get(index).tag == tag) {
                return index;
            }
        }
        throw new IllegalStateException("no tag " + tag);
    }

    private final class Output {
        private byte[] data = new byte[1024];
        private int size;

        int size() {
            return size;
        }

        void bytes(byte[] value) {
            ensure(value.length);
            System.arraycopy(value, 0, data, size, value.length);
            size += value.length;
        }

        void u16(int value) {
            var b = ByteBuffer.allocate(2).order(order()).putShort((short) value).array();
            bytes(b);
        }

        void u32(long value) {
            bytes(ByteBuffer.allocate(4).order(order()).putInt((int) value).array());
        }

        void patch32(long at, long value) {
            var b = ByteBuffer.allocate(4).order(order()).putInt((int) value).array();
            System.arraycopy(b, 0, data, (int) at, 4);
        }

        void align() {
            if ((size & 1) != 0) {
                bytes(new byte[1]);
            }
        }

        private void ensure(int more) {
            if (size + more > data.length) {
                data = java.util.Arrays.copyOf(data, Math.max(data.length * 2, size + more));
            }
        }

        byte[] toByteArray() {
            return java.util.Arrays.copyOf(data, size);
        }
    }

    /**
     * Packs unsigned samples most significant bit first, each row starting on a byte boundary.
     */
    public static byte[] packMsb(long[] samples, int perRow, int bits) {
        var out = new ByteArrayOutputStream();
        for (var start = 0; start < samples.length; start += perRow) {
            var buffer = 0L;
            var buffered = 0;
            for (var index = start; index < start + perRow; index++) {
                for (var bit = bits - 1; bit >= 0; bit--) {
                    buffer = (buffer << 1) | ((samples[index] >>> bit) & 1);
                    buffered++;
                    if (buffered == 8) {
                        out.write((int) buffer);
                        buffer = 0;
                        buffered = 0;
                    }
                }
            }
            if (buffered > 0) {
                out.write((int) (buffer << (8 - buffered)));
            }
        }
        return out.toByteArray();
    }

    /**
     * Writes unsigned 16 or 32-bit samples in a byte order.
     */
    public static byte[] words(long[] samples, int bits, ByteOrder order) {
        var b = ByteBuffer.allocate(samples.length * bits / 8).order(order);
        for (var value : samples) {
            if (bits == 16) {
                b.putShort((short) value);
            } else {
                b.putInt((int) value);
            }
        }
        return b.array();
    }

    public static byte[] deflate(byte[] bytes) {
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
