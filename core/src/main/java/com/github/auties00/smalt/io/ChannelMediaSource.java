package com.github.auties00.smalt.io;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.LocalSource;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/**
 * Implements {@link MediaSource} over a {@link SeekableByteChannel}.
 *
 * <p>A {@link FileChannel} is read with its thread-safe positional reads. Any other channel must
 * be repositioned before each read, so those reads are serialized on a private lock and restore
 * nothing: the channel position is owned by this source while it is in use.
 */
final class ChannelMediaSource implements MediaSource, LocalSource {
    /**
     * The channel read by this source.
     */
    private final SeekableByteChannel channel;

    /**
     * The file channel view of {@link #channel}, or {@code null} when it is not a file channel.
     */
    private final FileChannel fileChannel;

    /**
     * Whether {@link #close()} closes {@link #channel}.
     */
    private final boolean owned;

    /**
     * The channel size sampled at construction.
     */
    private final long size;

    /**
     * Serializes repositioning reads on channels that are not file channels.
     */
    private final Object lock;

    /**
     * Whether {@link #close()} already ran.
     */
    private volatile boolean closed;

    /**
     * Constructs a source over {@code channel}.
     *
     * @param channel the channel to read
     * @param owned   whether {@link #close()} closes the channel
     * @throws NullPointerException if {@code channel} is {@code null}
     * @throws MediaException.Io    if the channel size cannot be read
     */
    ChannelMediaSource(SeekableByteChannel channel, boolean owned) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.fileChannel = channel instanceof FileChannel file ? file : null;
        this.owned = owned;
        this.lock = new Object();
        try {
            this.size = channel.size();
        } catch (IOException exception) {
            throw new MediaException.Io("cannot read the channel size", exception);
        }
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public int read(long position, byte[] buffer, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (position < 0) {
            throw new IndexOutOfBoundsException("negative position " + position);
        }
        if (length == 0) {
            return 0;
        }
        if (position >= size) {
            return -1;
        }
        var target = ByteBuffer.wrap(buffer, offset, (int) Math.min(length, size - position));
        try {
            if (fileChannel != null) {
                return fileChannel.read(target, position);
            }
            synchronized (lock) {
                channel.position(position);
                return channel.read(target);
            }
        } catch (IOException exception) {
            throw new MediaException.Io("cannot read " + length + " bytes at offset " + position, exception);
        }
    }

    /**
     * Returns whether the channel is a {@link FileChannel}, whose bytes are in a local file; any
     * other channel may fetch its bytes from anywhere.
     *
     * @return {@code true} for a file channel
     */
    @Override
    public boolean isLocal() {
        return fileChannel != null;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (!owned) {
            return;
        }
        try {
            channel.close();
        } catch (IOException exception) {
            throw new MediaException.Io("cannot close the channel", exception);
        }
    }
}
