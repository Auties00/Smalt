package com.github.auties00.smalt.desktop.sound;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSink;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * Streams the bytes of a compressed file encoded on the fly from a PCM stream, under the codec's
 * {@link AudioFormat}.
 *
 * <p>The bytes are those of a file of the codec's elementary or Ogg container, written as it is produced:
 * PCM is read from the source and encoded as the bytes are read, and the file ends once the source does.
 * Closing the stream closes the source.
 */
final class EncodingAudioInputStream extends AudioInputStream {
    /**
     * The codec and container of the file.
     */
    private final EncodedFiles.Target target;

    /**
     * The format of the PCM source.
     */
    private final AudioFormat input;

    /**
     * Constructs the stream.
     *
     * @param pump the encoder over the source
     * @param format the compressed format
     */
    private EncodingAudioInputStream(Pump pump, AudioFormat format) {
        super(pump, format, AudioSystem.NOT_SPECIFIED);
        this.target = pump.target;
        this.input = pump.source.getFormat();
    }

    /**
     * Opens a stream that encodes a PCM stream.
     *
     * @param source the PCM stream, in a format {@link SampledAudio} supports at a positive whole rate;
     * closed with the returned stream
     * @param target the codec and container
     * @param outputRate the coded sample rate, or {@link AudioSystem#NOT_SPECIFIED} for the one the codec picks
     * @param outputChannels the coded channel count, or {@link AudioSystem#NOT_SPECIFIED} for the one the codec
     * picks
     * @return the stream
     * @throws IllegalArgumentException if the codec cannot code the source's format or the coded format asked
     * for
     * @throws MediaException.Unsupported if no encoder of the codec is present
     */
    static EncodingAudioInputStream open(AudioInputStream source, EncodedFiles.Target target, int outputRate,
                                         int outputChannels) {
        var pump = new Pump(source, target, outputRate, outputChannels);
        var track = pump.encoder.writer().track();
        var format = new AudioFormat(SmaltAudioFormats.encodingOf(target.codec()), track.sampleRate(),
                AudioSystem.NOT_SPECIFIED, track.layout().orElseThrow().channels(), AudioSystem.NOT_SPECIFIED,
                AudioSystem.NOT_SPECIFIED, false);
        return new EncodingAudioInputStream(pump, format);
    }

    /**
     * {@return the codec and container of the file}
     */
    EncodedFiles.Target target() {
        return target;
    }

    /**
     * {@return the format of the PCM source}
     */
    AudioFormat input() {
        return input;
    }

    /**
     * Reads PCM from the source, encodes it and hands out the encoded bytes.
     */
    private static final class Pump extends InputStream {
        /**
         * The PCM source.
         */
        private final AudioInputStream source;

        /**
         * The codec and container.
         */
        private final EncodedFiles.Target target;

        /**
         * The encoder writing into {@link #pending}.
         */
        private final EncodedFiles.PcmEncoder encoder;

        /**
         * The PCM block read from the source.
         */
        private final byte[] block;

        /**
         * The size of one source frame, in bytes.
         */
        private final int frameSize;

        /**
         * The encoded bytes not yet read.
         */
        private byte[] pending = new byte[8192];

        /**
         * The index in {@link #pending} of the next byte to read.
         */
        private int start;

        /**
         * The index in {@link #pending} past the last encoded byte.
         */
        private int end;

        /**
         * The number of encoded bytes produced so far.
         */
        private long produced;

        /**
         * Whether the source ended and the file is complete.
         */
        private boolean finished;

        /**
         * Whether the stream is closed.
         */
        private boolean closed;

        /**
         * Constructs the pump.
         *
         * @param source the PCM source
         * @param target the codec and container
         * @param outputRate the coded sample rate, or {@link AudioSystem#NOT_SPECIFIED}
         * @param outputChannels the coded channel count, or {@link AudioSystem#NOT_SPECIFIED}
         */
        private Pump(AudioInputStream source, EncodedFiles.Target target, int outputRate, int outputChannels) {
            this.source = Objects.requireNonNull(source, "source");
            this.target = Objects.requireNonNull(target, "target");
            var format = source.getFormat();
            this.frameSize = SampledAudio.frameSize(format);
            this.block = new byte[EncodedFiles.PcmEncoder.BLOCK_FRAMES * frameSize];
            this.encoder = target.open(format, outputRate, outputChannels, new QueueSink());
        }

        @Override
        public int read() throws IOException {
            if (!fill()) {
                return -1;
            }
            return pending[start++] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (length == 0) {
                return 0;
            }
            if (!fill()) {
                return -1;
            }
            var count = Math.min(length, end - start);
            System.arraycopy(pending, start, buffer, offset, count);
            start += count;
            return count;
        }

        @Override
        public int available() {
            return end - start;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            try {
                encoder.close();
            } finally {
                source.close();
            }
        }

        /**
         * Encodes source blocks until encoded bytes are available or the file is complete.
         *
         * @return {@code true} when bytes are available, {@code false} at the end of the file
         * @throws IOException if the stream is closed, reading the source fails or encoding fails
         */
        private boolean fill() throws IOException {
            if (closed) {
                throw new IOException("the stream is closed");
            }
            try {
                while (start == end && !finished) {
                    var read = source.readNBytes(block, 0, block.length);
                    encoder.write(block, 0, read / frameSize);
                    if (read < block.length) {
                        encoder.finish();
                        finished = true;
                    }
                }
            } catch (MediaException exception) {
                throw new IOException("cannot encode the audio: " + exception.getMessage(), exception);
            }
            return start < end;
        }

        /**
         * Appends encoded bytes to {@link #pending}, compacting or growing it.
         *
         * @param data the bytes, consumed
         */
        private void append(ByteBuffer data) {
            var length = data.remaining();
            if (pending.length - end < length) {
                var live = end - start;
                if (start > 0) {
                    System.arraycopy(pending, start, pending, 0, live);
                    start = 0;
                    end = live;
                }
                if (pending.length - end < length) {
                    pending = Arrays.copyOf(pending, Math.max(pending.length * 2, end + length));
                }
            }
            data.get(pending, end, length);
            end += length;
            produced += length;
        }

        /**
         * A streaming sink that appends to the pump's pending bytes.
         */
        private final class QueueSink implements MediaSink {
            @Override
            public long position() {
                return produced;
            }

            @Override
            public void position(long position) {
                if (position < 0) {
                    throw new IllegalArgumentException("negative position " + position);
                }
                if (position != produced) {
                    throw new MediaException.Unsupported("seek", "the encoded stream cannot seek");
                }
            }

            @Override
            public boolean isSeekable() {
                return false;
            }

            @Override
            public void write(ByteBuffer data) {
                append(data);
            }

            @Override
            public void close() {
            }
        }
    }
}
