package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.io.MediaSource;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Streams the bytes of a compressed audio file under its compressed {@link AudioFormat}, keeping
 * the {@link MediaSource} the file is read through so that
 * {@link SmaltFormatConversionProvider} decodes it without copying.
 *
 * <p>Reading this stream yields the file's bytes from its start, as an encoded Java Sound stream
 * does; decoding always starts from the start of the file, however many bytes were read. Closing
 * the stream closes the source, and closing a decoded stream made from it closes this stream, so
 * this stream must stay open while a decoded stream made from it is read.
 */
final class EncodedAudioInputStream extends AudioInputStream {
    /**
     * The source of the file.
     */
    private final MediaSource source;

    /**
     * The reader opened to describe the file, kept for the first decoding, or {@code null}.
     */
    private AudioReader reader;

    /**
     * Guards {@link #reader}.
     */
    private final Object lock = new Object();

    /**
     * Constructs the stream.
     *
     * @param source the source of the file; closed with this stream
     * @param format the compressed format
     * @param reader the reader opened on {@code source} to describe it, handed to the first
     * decoding
     */
    EncodedAudioInputStream(MediaSource source, AudioFormat format, AudioReader reader) {
        super(new SourceInputStream(source), format, AudioSystem.NOT_SPECIFIED);
        this.source = Objects.requireNonNull(source, "source");
        this.reader = reader;
    }

    /**
     * Returns the source of the file.
     *
     * @return the source
     */
    MediaSource source() {
        return source;
    }

    /**
     * Takes the reader opened to describe the file, if no decoding took it yet.
     *
     * @return the reader, positioned at the start, or {@code null}
     */
    AudioReader takeReader() {
        synchronized (lock) {
            var taken = reader;
            reader = null;
            return taken;
        }
    }

    @Override
    public void close() throws IOException {
        var unused = takeReader();
        if (unused != null) {
            unused.close();
        }
        super.close();
    }

    /**
     * Reads a {@link MediaSource} sequentially from its start.
     */
    private static final class SourceInputStream extends InputStream {
        /**
         * The source.
         */
        private final MediaSource source;

        /**
         * The offset of the next byte.
         */
        private long position;

        /**
         * The position {@link #reset()} returns to.
         */
        private long mark;

        /**
         * Constructs the stream.
         *
         * @param source the encoded input, not closed by the stream
         */
        private SourceInputStream(MediaSource source) {
            this.source = source;
        }

        @Override
        public int read() throws IOException {
            var single = new byte[1];
            return read(single, 0, 1) == 1 ? single[0] & 0xFF : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            try {
                var read = source.read(position, buffer, offset, length);
                if (read > 0) {
                    position += read;
                }
                return read;
            } catch (MediaException.Io exception) {
                throw exception.getCause();
            }
        }

        @Override
        public long skip(long count) {
            if (count <= 0) {
                return 0;
            }
            var size = source.size();
            var skipped = size < 0 ? count : Math.max(0, Math.min(count, size - position));
            position += skipped;
            return skipped;
        }

        @Override
        public int available() {
            var size = source.size();
            return size < 0 ? 0 : (int) Math.min(Integer.MAX_VALUE, Math.max(0, size - position));
        }

        @Override
        public boolean markSupported() {
            return true;
        }

        @Override
        public void mark(int limit) {
            mark = position;
        }

        @Override
        public void reset() {
            position = mark;
        }

        @Override
        public void close() {
            source.close();
        }
    }
}
