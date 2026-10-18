package com.github.auties00.smalt.io;

import com.github.auties00.smalt.MediaException;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Provides random-access reads over the bytes of one media input.
 *
 * <p>Reads are positional: {@link #read(long, byte[], int, int)} takes an absolute offset and
 * never moves a shared cursor, so several parsers may read the same source concurrently. Every
 * demuxer and image decoder in this module reads through this interface, which lets callers
 * back an input with a byte array, a {@link ByteBuffer}, a {@link MemorySegment}, a file, an
 * arbitrary {@link SeekableByteChannel}, an {@link InputStream} read once, or their own
 * implementation (for example an HTTP resource fetched with range requests).
 *
 * <p>Implementations must be safe for concurrent calls to {@link #read(long, byte[], int, int)},
 * must report the total length through {@link #size()} or {@code -1} when it is unknown, and must
 * translate every I/O failure into {@link MediaException.Io}. {@link #close()} must be idempotent.
 */
public interface MediaSource extends AutoCloseable {
    /**
     * {@return the total number of bytes in this source, or {@code -1} when the length is not known
     * in advance}
     */
    long size();

    /**
     * Reads up to {@code length} bytes starting at the absolute offset {@code position}.
     *
     * <p>A read with a positive {@code length} at a position before the end of the source stores at
     * least one byte; a read of zero bytes returns 0. A short read is not an error: fewer bytes are
     * returned only when the end of the source is reached or the implementation delivers data in
     * chunks, and callers that need an exact amount use {@link #readFully(long, byte[], int, int)}.
     *
     * @param position the absolute offset of the first byte to read; non-negative
     * @param buffer the destination array
     * @param offset the index in {@code buffer} of the first byte written
     * @param length the maximum number of bytes to read; non-negative
     * @return the number of bytes read, at least 1 when {@code length} is positive, or {@code -1}
     * when {@code length} is positive and {@code position} is at or past the end
     * @throws IndexOutOfBoundsException if the {@code buffer} range is invalid or {@code position}
     * is negative
     * @throws MediaException.Io if the underlying read fails
     */
    int read(long position, byte[] buffer, int offset, int length);

    /**
     * Reads exactly {@code length} bytes starting at the absolute offset {@code position}.
     *
     * @implSpec The default implementation calls {@link #read(long, byte[], int, int)} until
     * {@code length} bytes are stored, and fails as soon as a read returns {@code -1} or, against
     * its contract, 0.
     *
     * @param position the absolute offset of the first byte to read; non-negative
     * @param buffer the destination array
     * @param offset the index in {@code buffer} of the first byte written
     * @param length the exact number of bytes to read; non-negative
     * @throws IndexOutOfBoundsException if the {@code buffer} range is invalid or {@code position}
     * is negative
     * @throws MediaException.Malformed if the source ends before {@code length} bytes were read
     * @throws MediaException.Io if the underlying read fails or returns no byte before the end
     */
    default void readFully(long position, byte[] buffer, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        var done = 0;
        while (done < length) {
            var read = read(position + done, buffer, offset + done, length - done);
            if (read < 0) {
                throw new MediaException.Malformed("unexpected end of input at offset " + (position + done)
                        + " while reading " + length + " bytes");
            }
            if (read == 0) {
                var message = "the source returned no bytes at offset " + (position + done) + " before its end";
                throw new MediaException.Io(message, new IOException(message));
            }
            done += read;
        }
    }

    /**
     * Releases the resources held by this source.
     *
     * <p>A source created over a caller-owned array or channel releases nothing; a source that
     * opened its own file closes it.
     */
    @Override
    void close();

    /**
     * Returns a source over the whole of {@code data}.
     *
     * <p>The array is not copied, so it must not be modified while the source is in use.
     *
     * @param data the bytes to expose
     * @return a source over {@code data}
     */
    static MediaSource of(byte[] data) {
        return new ByteArrayMediaSource(data, 0, data.length);
    }

    /**
     * Returns a source over the range {@code [offset, offset + length)} of {@code data}.
     *
     * <p>The array is not copied, so it must not be modified while the source is in use. Offset
     * zero of the source is {@code data[offset]}.
     *
     * @param data the backing array
     * @param offset the index of the first exposed byte
     * @param length the number of exposed bytes
     * @return a source over the range
     * @throws IndexOutOfBoundsException if the range is invalid
     */
    static MediaSource of(byte[] data, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, data.length);
        return new ByteArrayMediaSource(data, offset, length);
    }

    /**
     * Returns a source over the bytes of a buffer from its position to its limit.
     *
     * <p>The bytes are not copied, so they must not be modified while the source is in use, and
     * the buffer's position, limit and mark are left unchanged. Offset zero of the source is the
     * byte at the buffer's position when this method is called. A heap buffer with an accessible
     * array is read exactly as {@link #of(byte[], int, int)} reads that array; a direct or
     * read-only buffer is read as {@link #of(MemorySegment)} reads
     * {@link MemorySegment#ofBuffer(java.nio.Buffer) its segment}.
     *
     * @param buffer the buffer to expose
     * @return a source over the remaining bytes of {@code buffer}
     * @throws IllegalArgumentException if {@code buffer} is a view of a memory segment confined to
     * one thread
     * @throws IllegalStateException if {@code buffer} is a view of a memory segment whose arena
     * is already closed
     */
    static MediaSource of(ByteBuffer buffer) {
        Objects.requireNonNull(buffer, "buffer");
        if (buffer.hasArray()) {
            return new ByteArrayMediaSource(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
        }
        return new SegmentMediaSource(MemorySegment.ofBuffer(buffer));
    }

    /**
     * Returns a source over the whole of a memory segment, native or on the heap.
     *
     * <p>The segment is not copied, so it must not be modified while the source is in use, and
     * its arena must stay open until the source is closed: {@link #close()} does not close it.
     * Offsets are {@code long}, so a segment larger than 2 GiB is read whole. A heap segment over
     * a byte array is read exactly as {@link #of(byte[], int, int)} reads that array.
     *
     * @apiNote Decoders read their sources from worker threads, so the segment must be accessible
     * from every thread: allocate it in {@link Arena#global()}, {@link Arena#ofAuto()} or
     * {@link Arena#ofShared()}. A segment of {@link Arena#ofConfined()} is rejected here
     * rather than failing with {@link WrongThreadException} at the first read.
     *
     * @param segment the segment to expose
     * @return a source over {@code segment}
     * @throws IllegalArgumentException if {@code segment} is confined to one thread
     * @throws IllegalStateException if the arena of {@code segment} is already closed
     */
    static MediaSource of(MemorySegment segment) {
        SegmentMediaSource.requireShareable(segment);
        if (segment.heapBase().orElse(null) instanceof byte[] array) {
            return new ByteArrayMediaSource(array, (int) segment.address(), (int) segment.byteSize());
        }
        return new SegmentMediaSource(segment);
    }

    /**
     * Returns a source over a caller-owned stream, read once, front to back, with
     * {@link StreamSourceOptions#defaults() the default options}.
     *
     * <p>The source keeps the bytes it reads so that parsers may read any offset again, as
     * {@link #of(InputStream, long, StreamSourceOptions)} describes. The stream is not closed by
     * {@link #close()}.
     *
     * @param stream the stream to read; positioned at the first byte of the media
     * @param length the number of bytes of the media, or {@code -1} when unknown
     * @return a source over {@code stream}
     * @throws IllegalArgumentException if {@code length} is below {@code -1}
     */
    static MediaSource of(InputStream stream, long length) {
        return of(stream, length, StreamSourceOptions.defaults());
    }

    /**
     * Returns a source over a caller-owned stream, read once, front to back.
     *
     * <p>Parsers read their sources at any offset, so the source keeps every byte it has taken
     * from the stream and serves a read below that point without touching the stream again:
     * in memory up to the {@linkplain StreamSourceOptions#memoryBudget() budget}, then in an
     * encrypted, owner-only spill file that is deleted on close and when the process ends, or,
     * for {@linkplain StreamSourceOptions#isMemoryOnly() memory-only} options, nowhere: the read
     * that would exceed the budget fails with {@link MediaException.LimitExceeded}. A read past
     * that point takes more bytes from the stream, under a lock; reads of bytes already taken run
     * concurrently. The demuxers and decoders of this library tell the source which bytes they
     * will never read again, so formats read front to back keep only a small window, while a
     * file whose index follows its media data, such as an MP4 file whose {@code moov} box comes
     * last, keeps its media data until the index is read.
     *
     * <p>The stream is read up to {@code length} bytes and never past them; one that ends sooner
     * fails the read of the missing bytes with {@link MediaException.Io} caused by an
     * {@link java.io.EOFException}. The stream is not closed by {@link #close()}, which releases
     * the kept bytes and deletes the spill file.
     *
     * @apiNote A stream cannot seek, so seeking a demuxer back to bytes it has already released
     * fails with {@link MediaException.Unsupported}; use {@link #of(Path)} or
     * {@link #of(SeekableByteChannel)} when the input is a file and seeking matters.
     *
     * @param stream the stream to read; positioned at the first byte of the media
     * @param length the number of bytes of the media, or {@code -1} when unknown
     * @param options the memory budget and spill directory
     * @return a source over {@code stream}
     * @throws IllegalArgumentException if {@code length} is below {@code -1}
     */
    static MediaSource of(InputStream stream, long length, StreamSourceOptions options) {
        return new StreamMediaSource(Objects.requireNonNull(stream, "stream"), null, length, options, false);
    }

    /**
     * Returns a source over a stream opened on first read, read once, front to back, with
     * {@link StreamSourceOptions#defaults() the default options}.
     *
     * <p>The source behaves as {@link #of(InputStream, long, StreamSourceOptions)} describes,
     * except that the supplier is called once, by the first read that needs the stream, and the
     * stream it returns is closed by {@link #close()}.
     *
     * @param supplier opens the stream; may throw {@link java.io.UncheckedIOException}
     * @param length the number of bytes of the media, or {@code -1} when unknown
     * @return a source over the stream {@code supplier} opens
     * @throws IllegalArgumentException if {@code length} is below {@code -1}
     */
    static MediaSource of(Supplier<? extends InputStream> supplier, long length) {
        return of(supplier, length, StreamSourceOptions.defaults());
    }

    /**
     * Returns a source over a stream opened on first read, read once, front to back.
     *
     * <p>The source behaves as {@link #of(InputStream, long, StreamSourceOptions)} describes,
     * except that the supplier is called once, by the first read that needs the stream, and the
     * stream it returns is closed by {@link #close()}. A supplier that throws an
     * {@link java.io.UncheckedIOException} fails that read with {@link MediaException.Io}.
     *
     * @param supplier opens the stream; may throw {@link java.io.UncheckedIOException}
     * @param length the number of bytes of the media, or {@code -1} when unknown
     * @param options the memory budget and spill directory
     * @return a source over the stream {@code supplier} opens
     * @throws IllegalArgumentException if {@code length} is below {@code -1}
     */
    static MediaSource of(Supplier<? extends InputStream> supplier, long length, StreamSourceOptions options) {
        return new StreamMediaSource(null, Objects.requireNonNull(supplier, "supplier"), length, options, false);
    }

    /**
     * Returns a source over a caller-owned channel.
     *
     * <p>The channel is not closed by {@link #close()}. The size is sampled once, when this
     * method is called. Reads through a {@link FileChannel} use its native positional reads;
     * reads through any other channel are serialized because they must move its position.
     *
     * @param channel the channel to read
     * @return a source over {@code channel}
     * @throws MediaException.Io if the channel size cannot be read
     */
    static MediaSource of(SeekableByteChannel channel) {
        return new ChannelMediaSource(channel, false);
    }

    /**
     * Creates a source over the file at {@code path}.
     *
     * <p>The returned source owns the file handle and closes it in {@link #close()}.
     *
     * @param path the file to open
     * @return a source over the file
     * @throws MediaException.Io if the file cannot be opened or sized
     */
    static MediaSource of(Path path) {
        Objects.requireNonNull(path, "path");
        FileChannel channel;
        try {
            channel = FileChannel.open(path, StandardOpenOption.READ);
        } catch (IOException exception) {
            throw new MediaException.Io("cannot open " + path, exception);
        }
        try {
            return new ChannelMediaSource(channel, true);
        } catch (RuntimeException exception) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }
}
