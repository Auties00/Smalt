package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioReader;

import javax.sound.sampled.AudioFormat;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Streams the samples an {@link AudioReader} decodes as interleaved PCM bytes of one
 * {@link AudioFormat}.
 *
 * <p>Bytes are produced one decoded block at a time and always in whole frames. A decoding failure
 * surfaces as an {@link IOException} whose cause is the {@link MediaException}.
 */
final class DecodingInputStream extends InputStream {
    /**
     * The reader.
     */
    private final AudioReader reader;

    /**
     * The format of the bytes.
     */
    private final AudioFormat format;

    /**
     * Closed with this stream, after the reader, or {@code null}.
     */
    private final Closeable owner;

    /**
     * The bytes of the current block.
     */
    private byte[] block = new byte[0];

    /**
     * The index in {@link #block} of the next byte.
     */
    private int position;

    /**
     * The number of valid bytes in {@link #block}.
     */
    private int limit;

    /**
     * Whether the reader reached its end.
     */
    private boolean ended;

    /**
     * Whether the stream is closed.
     */
    private boolean closed;

    /**
     * Constructs the stream.
     *
     * @param reader the reader; closed with this stream
     * @param format the PCM format of the bytes, with the reader's channel count
     * @param owner closed with this stream, after the reader, or {@code null}
     */
    DecodingInputStream(AudioReader reader, AudioFormat format, Closeable owner) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.format = Objects.requireNonNull(format, "format");
        this.owner = owner;
    }

    @Override
    public int read() throws IOException {
        if (!fill()) {
            return -1;
        }
        return block[position++] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) {
            return 0;
        }
        var done = 0;
        while (done < length && fill()) {
            var count = Math.min(length - done, limit - position);
            System.arraycopy(block, position, buffer, offset + done, count);
            position += count;
            done += count;
        }
        return done == 0 ? -1 : done;
    }

    @Override
    public int available() {
        return limit - position;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            reader.close();
        } finally {
            if (owner != null) {
                owner.close();
            }
        }
    }

    /**
     * Decodes the next block when the current one is consumed.
     *
     * @return {@code true} when bytes are available, {@code false} at the end
     * @throws IOException if the stream is closed or decoding fails
     */
    private boolean fill() throws IOException {
        if (closed) {
            throw new IOException("the stream is closed");
        }
        while (position == limit) {
            if (ended) {
                return false;
            }
            try {
                var decoded = reader.read();
                if (decoded == null) {
                    ended = true;
                    return false;
                }
                var size = decoded.frames() * SampledAudio.frameSize(format);
                if (block.length < size) {
                    block = new byte[size];
                }
                limit = SampledAudio.write(decoded, format, block, 0);
                position = 0;
            } catch (MediaException exception) {
                throw new IOException("cannot decode the audio: " + exception.getMessage(), exception);
            }
        }
        return true;
    }
}
