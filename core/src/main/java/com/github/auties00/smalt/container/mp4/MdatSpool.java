package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSink;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

/**
 * Buffers the media data of a fast-start file until its {@code moov} is written.
 *
 * <p>Data accumulates in memory up to {@value #MEMORY_LIMIT} bytes and then moves to a temporary
 * file that is deleted when the spool is closed (and by the operating system if the process dies
 * first, where it supports delete-on-close). Appends are sequential; {@link #transferTo} copies
 * everything, or a range, to the output in one pass, through the channel-to-channel transfer of the
 * file when the data spilled to disk.
 *
 * <p>In memory the data is a sequence of segments: ranges of blocks that {@link #write} copies
 * into, and whole arrays that {@link #hold} keeps by reference because they are immutable packet
 * payloads.
 *
 * @implNote This implementation copies into blocks that double from {@value #FIRST_BLOCK} bytes
 * up to {@value #MAX_BLOCK} bytes and then stay at that size, so growing never copies what is
 * already buffered. Held payloads cost no copy until {@link #transferTo} or a spill gathers them,
 * {@value #STAGING} bytes at a time, into one staging buffer per output write, which keeps the
 * number of writes as low as with copied blocks.
 */
final class MdatSpool implements AutoCloseable {
    /**
     * The largest amount of data kept in memory.
     */
    static final int MEMORY_LIMIT = 16 << 20;

    /**
     * The size of the first copy block.
     */
    private static final int FIRST_BLOCK = 64 * 1024;

    /**
     * The size of every copy block after the growing ones.
     */
    private static final int MAX_BLOCK = 1 << 20;

    /**
     * The size of the buffer that gathers segments into one output write.
     */
    private static final int STAGING = 256 * 1024;

    /**
     * The directory of the temporary file, or {@code null} for the default.
     */
    private final Path directory;

    /**
     * The arrays of the in-memory segments, or {@code null} once the data moved to
     * {@link #file}.
     */
    private byte[][] segments;

    /**
     * The index of each segment's first byte in its array.
     */
    private int[] segmentOffsets;

    /**
     * The size of each segment.
     */
    private int[] segmentLengths;

    /**
     * The number of segments.
     */
    private int segmentCount;

    /**
     * The block that {@link #write} copies into, or {@code null} before the first copy.
     */
    private byte[] block;

    /**
     * The number of bytes used in {@link #block}.
     */
    private int blockUsed;

    /**
     * The temporary file, or {@code null} while the data is in memory.
     */
    private FileChannel file;

    /**
     * The number of bytes appended.
     */
    private long size;

    /**
     * Constructs an empty spool.
     *
     * @param directory the directory of the temporary file, or {@code null} for the default
     */
    MdatSpool(Path directory) {
        this.directory = directory;
        this.segments = new byte[64][];
        this.segmentOffsets = new int[64];
        this.segmentLengths = new int[64];
    }

    /**
     * Returns the number of bytes appended.
     *
     * @return the spooled size
     */
    long size() {
        return size;
    }

    /**
     * Appends a copy of bytes.
     *
     * @param data the source array
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @throws MediaException.Io if the temporary file cannot be created or written
     */
    void write(byte[] data, int offset, int length) {
        if (segments != null && size + length <= MEMORY_LIMIT) {
            var from = offset;
            var left = length;
            while (left > 0) {
                if (block == null || blockUsed == block.length) {
                    block = new byte[block == null ? FIRST_BLOCK : Math.min(MAX_BLOCK, 2 * block.length)];
                    blockUsed = 0;
                }
                var count = Math.min(left, block.length - blockUsed);
                System.arraycopy(data, from, block, blockUsed, count);
                var last = segmentCount - 1;
                if (last >= 0 && segments[last] == block && segmentOffsets[last] + segmentLengths[last] == blockUsed) {
                    segmentLengths[last] += count;
                } else {
                    addSegment(block, blockUsed, count);
                }
                blockUsed += count;
                from += count;
                left -= count;
            }
            size += length;
            return;
        }
        writeToFile(data, offset, length);
    }

    /**
     * Appends a whole array by reference.
     *
     * <p>The array must never change afterwards, as a {@link com.github.auties00.smalt.track.Packet}
     * payload never does.
     *
     * @param data the bytes
     * @throws MediaException.Io if the temporary file cannot be created or written
     */
    void hold(byte[] data) {
        if (segments != null && size + data.length <= MEMORY_LIMIT) {
            if (data.length > 0) {
                addSegment(data, 0, data.length);
            }
            size += data.length;
            return;
        }
        writeToFile(data, 0, data.length);
    }

    /**
     * Records an in-memory segment.
     *
     * @param data the array
     * @param offset the index of the segment's first byte
     * @param length the segment size
     */
    private void addSegment(byte[] data, int offset, int length) {
        if (segmentCount == segments.length) {
            var capacity = segmentCount * 2;
            segments = Arrays.copyOf(segments, capacity);
            segmentOffsets = Arrays.copyOf(segmentOffsets, capacity);
            segmentLengths = Arrays.copyOf(segmentLengths, capacity);
        }
        segments[segmentCount] = data;
        segmentOffsets[segmentCount] = offset;
        segmentLengths[segmentCount] = length;
        segmentCount++;
    }

    /**
     * Appends bytes to the temporary file, spilling the in-memory data first.
     *
     * @param data the source array
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @throws MediaException.Io if the temporary file cannot be created or written
     */
    private void writeToFile(byte[] data, int offset, int length) {
        try {
            if (file == null) {
                spill();
            }
            var buffer = ByteBuffer.wrap(data, offset, length);
            while (buffer.hasRemaining()) {
                file.write(buffer, size + (length - buffer.remaining()));
            }
            size += length;
        } catch (IOException exception) {
            throw new MediaException.Io("cannot write the media data spool", exception);
        }
    }

    /**
     * Moves the in-memory data to a new temporary file.
     *
     * @throws IOException if the file cannot be created or written
     */
    private void spill() throws IOException {
        var path = directory == null ? Files.createTempFile("smalt-mp4-", ".mdat")
                : Files.createTempFile(directory, "smalt-mp4-", ".mdat");
        try {
            file = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.DELETE_ON_CLOSE);
        } catch (IOException | RuntimeException exception) {
            Files.deleteIfExists(path);
            throw exception;
        }
        drain(file);
        release();
    }

    /**
     * Writes every in-memory segment, in order, at the channel's current position.
     *
     * <p>Segments smaller than the staging buffer are gathered into it, so each output write
     * carries up to {@value #STAGING} bytes.
     *
     * @param output the destination
     * @throws IOException if writing fails
     */
    private void drain(WritableByteChannel output) throws IOException {
        byte[] staging = null;
        var staged = 0;
        for (var index = 0; index < segmentCount; index++) {
            var data = segments[index];
            var offset = segmentOffsets[index];
            var length = segmentLengths[index];
            if (length >= STAGING) {
                if (staged > 0) {
                    writeFully(output, staging, 0, staged);
                    staged = 0;
                }
                writeFully(output, data, offset, length);
                continue;
            }
            if (staging == null) {
                staging = new byte[STAGING];
            }
            if (staged + length > STAGING) {
                writeFully(output, staging, 0, staged);
                staged = 0;
            }
            System.arraycopy(data, offset, staging, staged, length);
            staged += length;
        }
        if (staged > 0) {
            writeFully(output, staging, 0, staged);
        }
    }

    /**
     * Writes a range of bytes completely.
     *
     * @param output the destination
     * @param data the bytes
     * @param offset the index of the first byte
     * @param length the number of bytes
     * @throws IOException if writing fails
     */
    private static void writeFully(WritableByteChannel output, byte[] data, int offset, int length) throws IOException {
        var buffer = ByteBuffer.wrap(data, offset, length);
        while (buffer.hasRemaining()) {
            output.write(buffer);
        }
    }

    /**
     * Drops every reference to in-memory data.
     */
    private void release() {
        segments = null;
        segmentOffsets = null;
        segmentLengths = null;
        segmentCount = 0;
        block = null;
    }

    /**
     * Copies every appended byte to a sink at its current position.
     *
     * @param sink the destination
     * @throws MediaException.Io if reading the spool or writing the output fails
     */
    void transferTo(MediaSink sink) {
        if (segments != null) {
            try {
                drain(new SinkChannel(sink));
            } catch (IOException exception) {
                throw new MediaException.Io("cannot copy the media data spool", exception);
            }
            return;
        }
        transferTo(sink, 0, size);
    }

    /**
     * Copies a range of the appended bytes to a sink at its current position.
     *
     * @param sink the destination
     * @param from the index of the first byte copied
     * @param length the number of bytes copied
     * @throws IndexOutOfBoundsException if the range is not within the appended bytes
     * @throws MediaException.Io if reading the spool or writing the output fails
     */
    void transferTo(MediaSink sink, long from, long length) {
        if (from < 0 || length < 0 || from > size - length) {
            throw new IndexOutOfBoundsException("range " + from + " + " + length + " outside " + size + " bytes");
        }
        var output = new SinkChannel(sink);
        try {
            if (segments != null) {
                var start = 0L;
                for (var index = 0; index < segmentCount && length > 0; index++) {
                    var segmentLength = segmentLengths[index];
                    if (start + segmentLength > from) {
                        var skip = (int) Math.max(0, from - start);
                        var count = (int) Math.min(segmentLength - skip, length);
                        writeFully(output, segments[index], segmentOffsets[index] + skip, count);
                        from += count;
                        length -= count;
                    }
                    start += segmentLength;
                }
                return;
            }
            var position = from;
            var end = from + length;
            while (position < end) {
                var moved = file.transferTo(position, end - position, output);
                if (moved <= 0) {
                    var buffer = ByteBuffer.allocate((int) Math.min(1 << 20, end - position));
                    var read = file.read(buffer, position);
                    if (read <= 0) {
                        throw new IOException("media data spool ended at " + position + " of " + size + " bytes");
                    }
                    buffer.flip();
                    while (buffer.hasRemaining()) {
                        output.write(buffer);
                    }
                    moved = read;
                }
                position += moved;
            }
        } catch (IOException exception) {
            throw new MediaException.Io("cannot copy the media data spool", exception);
        }
    }

    /**
     * Releases the memory and deletes the temporary file.
     *
     * @throws MediaException.Io if the temporary file cannot be closed
     */
    @Override
    public void close() {
        release();
        if (file != null) {
            try {
                file.close();
            } catch (IOException exception) {
                throw new MediaException.Io("cannot delete the media data spool", exception);
            } finally {
                file = null;
            }
        }
    }

    /**
     * Presents a {@link MediaSink} as a channel, so the spool copies to it with the same code
     * that spills to its file.
     */
    private static final class SinkChannel implements WritableByteChannel {
        /**
         * The sink written.
         */
        private final MediaSink sink;

        /**
         * Constructs the channel.
         *
         * @param sink the sink written
         */
        private SinkChannel(MediaSink sink) {
            this.sink = sink;
        }

        /**
         * Writes every remaining byte of a buffer to the sink.
         *
         * @param source the bytes
         * @return the number of bytes written
         */
        @Override
        public int write(ByteBuffer source) {
            var length = source.remaining();
            sink.write(source);
            return length;
        }

        /**
         * {@return {@code true}: the sink's own state decides whether writes succeed}
         */
        @Override
        public boolean isOpen() {
            return true;
        }

        /**
         * Does nothing: the sink belongs to the muxer's caller.
         */
        @Override
        public void close() {
        }
    }
}
