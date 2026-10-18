package com.github.auties00.smalt.io;

import com.github.auties00.smalt.MediaException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Receives the bytes of one media output: the counterpart of {@link MediaSource}.
 *
 * <p>Muxers and image encoders write through this interface, which lets callers send an output
 * to a file ({@link #of(Path)}), a caller-owned channel ({@link #of(SeekableByteChannel)}), a
 * stream ({@link #of(OutputStream)}), memory ({@link #memory()}) or an implementation of their
 * own. Writes go to the {@linkplain #position() position} and advance it. A
 * {@linkplain #isSeekable() seekable} sink can be repositioned to rewrite earlier bytes, as a
 * muxer does to patch a size or an index once the media data is written; a streaming sink only
 * appends, so a muxer that must seek rejects it with {@link MediaException.Unsupported}, while
 * streaming muxers (Ogg, fast-start MP4, remuxing) accept it.
 *
 * <p>A sink is used by one writer at a time and is not thread-safe.
 *
 * @implSpec
 * Implementations must write every remaining byte of the buffer passed to
 * {@link #write(ByteBuffer)}, must translate every I/O failure into {@link MediaException.Io},
 * and must make {@link #close()} idempotent. A sink that cannot seek must accept
 * {@link #position(long)} with its current position, as a no-op, and reject any other position.
 */
public interface MediaSink extends AutoCloseable {
    /**
     * Returns the position the next write starts at.
     *
     * <p>For a sink over a channel this is the channel's position; for any other sink it is the
     * number of bytes before the next write, counted from the sink's creation.
     *
     * @return the position, non-negative
     * @throws MediaException.Io if the position cannot be read
     */
    long position();

    /**
     * Moves the position the next write starts at.
     *
     * <p>A position past the end of the bytes written so far leaves a gap whose content is
     * unspecified until it is written.
     *
     * @param position the new position, non-negative
     * @throws IllegalArgumentException   if {@code position} is negative
     * @throws MediaException.Unsupported if the sink is not {@linkplain #isSeekable() seekable}
     *                                    and {@code position} differs from {@link #position()}
     * @throws MediaException.Io          if the position cannot be changed
     */
    void position(long position);

    /**
     * Returns whether the sink can be repositioned to rewrite earlier bytes.
     *
     * @return {@code true} when {@link #position(long)} accepts any position
     */
    boolean isSeekable();

    /**
     * Writes every remaining byte of a buffer at the position and advances the position past
     * them.
     *
     * @param data the bytes to write, from its position to its limit; consumed by the call
     * @throws NullPointerException if {@code data} is {@code null}
     * @throws MediaException.Io    if writing fails
     */
    void write(ByteBuffer data);

    /**
     * Writes a range of a byte array at the position and advances the position past it.
     *
     * @param data   the bytes
     * @param offset the index of the first byte to write
     * @param length the number of bytes to write
     * @throws NullPointerException      if {@code data} is {@code null}
     * @throws IndexOutOfBoundsException if the range is invalid
     * @throws MediaException.Io         if writing fails
     */
    default void write(byte[] data, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, data.length);
        write(ByteBuffer.wrap(data, offset, length));
    }

    /**
     * Writes a byte array at the position and advances the position past it.
     *
     * @param data the bytes
     * @throws NullPointerException if {@code data} is {@code null}
     * @throws MediaException.Io    if writing fails
     */
    default void write(byte[] data) {
        write(data, 0, data.length);
    }

    /**
     * Flushes buffered bytes and releases the resources held by this sink.
     *
     * <p>A sink created over a caller-owned channel or stream flushes it but does not close it; a
     * sink that opened its own file closes it.
     *
     * @throws MediaException.Io if flushing or closing fails
     */
    @Override
    void close();

    /**
     * Opens a sink that writes the file at {@code path}, creating it or truncating it.
     *
     * <p>The returned sink is seekable, starts at position zero, owns the file handle and closes
     * it in {@link #close()}.
     *
     * @param path the file to write
     * @return a sink over the file
     * @throws NullPointerException if {@code path} is {@code null}
     * @throws MediaException.Io    if the file cannot be opened
     */
    static MediaSink of(Path path) {
        Objects.requireNonNull(path, "path");
        try {
            var channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return new ChannelMediaSink(channel, true);
        } catch (IOException exception) {
            throw new MediaException.Io("cannot open " + path, exception);
        }
    }

    /**
     * Returns a seekable sink over a caller-owned channel, writing from the channel's current
     * position.
     *
     * <p>The channel is not closed by {@link #close()}.
     *
     * @param channel the channel to write
     * @return a sink over {@code channel}
     * @throws NullPointerException if {@code channel} is {@code null}
     */
    static MediaSink of(SeekableByteChannel channel) {
        return new ChannelMediaSink(channel, false);
    }

    /**
     * Returns a streaming sink over a caller-owned output stream.
     *
     * <p>The sink is not seekable: it only appends, so only streaming muxers accept it. Its
     * position counts the bytes written through it. {@link #close()} flushes the stream but does
     * not close it.
     *
     * @param stream the stream to write
     * @return a sink over {@code stream}
     * @throws NullPointerException if {@code stream} is {@code null}
     */
    static MediaSink of(OutputStream stream) {
        return new StreamMediaSink(stream);
    }

    /**
     * Returns a seekable sink that collects the output in memory.
     *
     * @return an empty in-memory sink, whose {@link ByteArrayMediaSink#toByteArray()} returns the
     *         bytes written
     */
    static ByteArrayMediaSink memory() {
        return new ByteArrayMediaSink();
    }
}
