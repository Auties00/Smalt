package com.github.auties00.smalt.io;

import com.github.auties00.smalt.MediaException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.util.Objects;

/**
 * Implements {@link MediaSink} over a {@link SeekableByteChannel}.
 *
 * <p>Writes and repositioning go straight to the channel, whose position is the sink's position.
 */
final class ChannelMediaSink implements MediaSink {
    /**
     * The channel written by this sink.
     */
    private final SeekableByteChannel channel;

    /**
     * Whether {@link #close()} closes {@link #channel}.
     */
    private final boolean owned;

    /**
     * Whether {@link #close()} already ran.
     */
    private boolean closed;

    /**
     * Constructs a sink over {@code channel}.
     *
     * @param channel the channel to write
     * @param owned   whether {@link #close()} closes the channel
     * @throws NullPointerException if {@code channel} is {@code null}
     */
    ChannelMediaSink(SeekableByteChannel channel, boolean owned) {
        this.channel = Objects.requireNonNull(channel, "channel");
        this.owned = owned;
    }

    @Override
    public long position() {
        try {
            return channel.position();
        } catch (IOException exception) {
            throw new MediaException.Io("cannot read the output position", exception);
        }
    }

    @Override
    public void position(long position) {
        if (position < 0) {
            throw new IllegalArgumentException("negative position " + position);
        }
        try {
            channel.position(position);
        } catch (IOException exception) {
            throw new MediaException.Io("cannot move the output to offset " + position, exception);
        }
    }

    /**
     * Returns {@code true}: a channel can be repositioned.
     *
     * @return {@code true}
     */
    @Override
    public boolean isSeekable() {
        return true;
    }

    @Override
    public void write(ByteBuffer data) {
        Objects.requireNonNull(data, "data");
        try {
            while (data.hasRemaining()) {
                channel.write(data);
            }
        } catch (IOException exception) {
            throw new MediaException.Io("cannot write the output", exception);
        }
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
            throw new MediaException.Io("cannot close the output", exception);
        }
    }
}
