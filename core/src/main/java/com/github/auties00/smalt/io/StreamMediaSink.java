package com.github.auties00.smalt.io;

import com.github.auties00.smalt.MediaException;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;

/**
 * Implements a streaming {@link MediaSink} over an {@link OutputStream}.
 *
 * <p>The sink only appends: its position counts the bytes written, and a repositioning to any
 * other position fails.
 */
final class StreamMediaSink implements MediaSink {
    /**
     * The size of the scratch array that carries the bytes of a buffer without a backing array.
     */
    private static final int CHUNK = 64 * 1024;

    /**
     * The stream written by this sink.
     */
    private final OutputStream stream;

    /**
     * The number of bytes written.
     */
    private long position;

    /**
     * The scratch array for direct buffers, allocated on first use.
     */
    private byte[] scratch;

    /**
     * Whether {@link #close()} already ran.
     */
    private boolean closed;

    /**
     * Constructs a sink over {@code stream}.
     *
     * @param stream the stream to write; not closed by this sink
     * @throws NullPointerException if {@code stream} is {@code null}
     */
    StreamMediaSink(OutputStream stream) {
        this.stream = Objects.requireNonNull(stream, "stream");
    }

    @Override
    public long position() {
        return position;
    }

    @Override
    public void position(long position) {
        if (position < 0) {
            throw new IllegalArgumentException("negative position " + position);
        }
        if (position != this.position) {
            throw new MediaException.Unsupported("non-seekable output",
                    "the output is a stream at offset " + this.position + " and cannot move to offset " + position);
        }
    }

    /**
     * Returns {@code false}: a stream only appends.
     *
     * @return {@code false}
     */
    @Override
    public boolean isSeekable() {
        return false;
    }

    @Override
    public void write(ByteBuffer data) {
        Objects.requireNonNull(data, "data");
        var length = data.remaining();
        try {
            if (data.hasArray()) {
                stream.write(data.array(), data.arrayOffset() + data.position(), length);
                data.position(data.limit());
            } else {
                if (scratch == null) {
                    scratch = new byte[CHUNK];
                }
                while (data.hasRemaining()) {
                    var count = Math.min(scratch.length, data.remaining());
                    data.get(scratch, 0, count);
                    stream.write(scratch, 0, count);
                }
            }
        } catch (IOException exception) {
            throw new MediaException.Io("cannot write the output", exception);
        }
        position += length;
    }

    /**
     * Flushes the stream without closing it.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            stream.flush();
        } catch (IOException exception) {
            throw new MediaException.Io("cannot flush the output", exception);
        }
    }
}
