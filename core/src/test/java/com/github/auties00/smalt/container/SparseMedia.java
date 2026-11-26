package com.github.auties00.smalt.container;

import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/**
 * A file of several gigabytes held in memory as its nonzero pages: muxers write files past 4 GiB of mostly zero
 * payload into a {@link Sink} in a few megabytes, demuxers read them back through {@link #source()}, and
 * {@link #save(Path)} writes them as a sparse file, which ffmpeg reads without the zeros taking disk space.
 */
public final class SparseMedia {
    /** The page size, in bytes. */
    private static final int PAGE = 1 << 16;

    /** The pages holding a nonzero byte, by index. */
    private final TreeMap<Long, byte[]> pages = new TreeMap<>();

    /** The size, in bytes. */
    private long size;

    /** {@return the size of the file, in bytes} */
    public long size() {
        return size;
    }

    /** {@return the number of bytes of the pages kept in memory} */
    public long resident() {
        return (long) pages.size() * PAGE;
    }

    /**
     * Writes bytes at a position.
     *
     * @param position the position
     * @param data     the bytes
     * @param offset   the index of the first byte
     * @param length   the number of bytes
     */
    public void write(long position, byte[] data, int offset, int length) {
        var at = 0;
        while (at < length) {
            var absolute = position + at;
            var index = absolute / PAGE;
            var within = (int) (absolute % PAGE);
            var count = Math.min(PAGE - within, length - at);
            var page = pages.get(index);
            if (page == null) {
                if (isZero(data, offset + at, count)) {
                    at += count;
                    continue;
                }
                page = new byte[PAGE];
            }
            System.arraycopy(data, offset + at, page, within, count);
            if (isZero(page, 0, PAGE)) {
                pages.remove(index);
            } else {
                pages.put(index, page);
            }
            at += count;
        }
        size = Math.max(size, position + length);
    }

    /**
     * Reads bytes at a position; past the end, nothing.
     *
     * @param position the position
     * @param buffer   the destination
     * @param offset   the index of the first byte of the destination
     * @param length   the most bytes to read
     * @return the number of bytes read, or {@code -1} at the end
     */
    public int read(long position, byte[] buffer, int offset, int length) {
        if (position >= size) {
            return length == 0 ? 0 : -1;
        }
        var count = (int) Math.min(length, size - position);
        var at = 0;
        while (at < count) {
            var absolute = position + at;
            var index = absolute / PAGE;
            var within = (int) (absolute % PAGE);
            var chunk = Math.min(PAGE - within, count - at);
            var page = pages.get(index);
            if (page == null) {
                Arrays.fill(buffer, offset + at, offset + at + chunk, (byte) 0);
            } else {
                System.arraycopy(page, within, buffer, offset + at, chunk);
            }
            at += chunk;
        }
        return count;
    }

    private static boolean isZero(byte[] data, int offset, int length) {
        for (var index = offset; index < offset + length; index++) {
            if (data[index] != 0) {
                return false;
            }
        }
        return true;
    }

    /** {@return a seekable sink writing into this file from its start} */
    public Sink sink() {
        return new Sink();
    }

    /** {@return a source reading this file} */
    public MediaSource source() {
        return new MediaSource() {
            @Override
            public long size() {
                return size;
            }

            @Override
            public int read(long position, byte[] buffer, int offset, int length) {
                return SparseMedia.this.read(position, buffer, offset, length);
            }

            @Override
            public void close() {
            }
        };
    }

    /**
     * Returns a copy of some bytes.
     *
     * @param position the position
     * @param length   the number of bytes
     * @return the bytes
     */
    public byte[] bytes(long position, int length) {
        var out = new byte[length];
        read(position, out, 0, length);
        return out;
    }

    /**
     * Writes this file as a sparse file: its nonzero pages, and its size.
     *
     * @param file the file
     * @return the file
     */
    public Path save(Path file) {
        try {
            Files.deleteIfExists(file);
            try (var channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE,
                    StandardOpenOption.SPARSE)) {
                for (Map.Entry<Long, byte[]> entry : pages.entrySet()) {
                    var position = entry.getKey() * PAGE;
                    var length = (int) Math.min(PAGE, size - position);
                    channel.write(ByteBuffer.wrap(entry.getValue(), 0, length), position);
                }
                if (channel.size() < size) {
                    channel.write(ByteBuffer.wrap(new byte[1]), size - 1);
                }
            }
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** A seekable sink into the file. */
    public final class Sink implements MediaSink {
        private long position;

        @Override
        public long position() {
            return position;
        }

        @Override
        public void position(long position) {
            this.position = position;
        }

        @Override
        public boolean isSeekable() {
            return true;
        }

        @Override
        public void write(ByteBuffer data) {
            var length = data.remaining();
            if (data.hasArray()) {
                SparseMedia.this.write(position, data.array(), data.arrayOffset() + data.position(), length);
                data.position(data.limit());
            } else {
                var bytes = new byte[length];
                data.get(bytes);
                SparseMedia.this.write(position, bytes, 0, length);
            }
            position += length;
        }

        @Override
        public void close() {
        }
    }
}
