package com.github.auties00.smalt.desktop.imageio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;

import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Objects;

/**
 * Presents the bytes of an {@link ImageInputStream}, from its position when the source is
 * created, as a {@link MediaSource}.
 *
 * <p>Reads seek the stream, so the stream's position is undefined while the source is in use, and
 * reads are serialized because an {@link ImageInputStream} has one cursor. The source never
 * closes the stream: the stream belongs to the caller of
 * {@link javax.imageio.ImageReader#setInput(Object)}.
 */
final class ImageInputStreamSource implements MediaSource {
    /**
     * The stream.
     */
    private final ImageInputStream stream;

    /**
     * The stream position of the first byte of the source.
     */
    private final long start;

    /**
     * The number of bytes from {@link #start} to the end of the stream.
     */
    private final long size;

    /**
     * Guards the stream's cursor.
     */
    private final Object lock = new Object();

    /**
     * Constructs a source over a stream of known length.
     *
     * @param stream the stream to read, not closed by the source
     * @param start the stream position of the first byte
     * @param size the number of bytes from {@code start} to the end of the stream
     */
    private ImageInputStreamSource(ImageInputStream stream, long start, long size) {
        this.stream = stream;
        this.start = start;
        this.size = size;
    }

    /**
     * Creates a source over the rest of a stream.
     *
     * <p>A stream that reports its length is read in place; any other stream is read to its end
     * into memory, because Smalt's decoders need the input's length.
     *
     * @param stream the stream, positioned at the first byte of the image
     * @return the source
     * @throws IOException if reading the stream fails
     */
    static MediaSource of(ImageInputStream stream) throws IOException {
        Objects.requireNonNull(stream, "stream");
        var start = stream.getStreamPosition();
        var length = stream.length();
        if (length >= 0) {
            return new ImageInputStreamSource(stream, start, Math.max(0, length - start));
        }
        var buffer = new ByteArrayOutputStream();
        var chunk = new byte[64 * 1024];
        int read;
        while ((read = stream.read(chunk)) > 0) {
            buffer.write(chunk, 0, read);
        }
        stream.seek(start);
        return MediaSource.of(buffer.toByteArray());
    }

    /**
     * Reads the first bytes of a stream and restores its position.
     *
     * @param stream the stream to peek at, positioned at the first byte to read
     * @param length the number of bytes wanted
     * @return the bytes read, possibly fewer than {@code length} at the end of the stream
     * @throws IOException if reading the stream fails
     */
    static byte[] peek(ImageInputStream stream, int length) throws IOException {
        var header = new byte[length];
        stream.mark();
        try {
            var done = 0;
            while (done < length) {
                var read = stream.read(header, done, length - done);
                if (read <= 0) {
                    break;
                }
                done += read;
            }
            return done == length ? header : Arrays.copyOf(header, done);
        } finally {
            stream.reset();
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
        if (position >= size) {
            return -1;
        }
        var wanted = (int) Math.min(length, size - position);
        synchronized (lock) {
            try {
                stream.seek(start + position);
                return stream.read(buffer, offset, wanted);
            } catch (IOException exception) {
                throw new MediaException.Io("cannot read the image stream", exception);
            }
        }
    }

    @Override
    public void close() {
    }
}
