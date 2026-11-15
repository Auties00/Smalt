package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.audio.AudioStreamDecoder;
import com.github.auties00.smalt.audio.AudioStreamDecoderOptions;
import com.github.auties00.smalt.audio.SampleFormat;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;

import java.util.Arrays;

/**
 * Decodes an audio track that a concatenation may continue with the tracks of later files, each with a decoder of
 * its own.
 *
 * <p>Until {@link #startSegment(AudioTrack, long)} the decoder behaves as one {@link AudioStreamDecoder}. At the start
 * of a file, the current decoder is finished and drained first, then the output is padded with silence or cut so that
 * the next file's first sample falls at the file's start time, then a decoder created from the file's track continues.
 * The output format stays the one of the first decoder, so the options must fix it (a sampling rate and a layout)
 * whenever later files may differ.
 *
 * <p>An instance is not thread-safe.
 */
final class SegmentedAudioDecoder implements AutoCloseable {
    private final AudioStreamDecoderOptions options;

    /**
     * The decoder of the current file.
     */
    private AudioStreamDecoder current;

    /**
     * The decoder of the previous file being drained, or {@code null}.
     */
    private AudioStreamDecoder draining;

    /**
     * The output index the current file starts at, set once {@link #draining} is empty, or {@code -1} before.
     */
    private long boundary = -1;

    /**
     * The start of the current file in microseconds, waiting for {@link #draining} to empty, or {@code -1}.
     */
    private long pendingBoundaryMicros = -1;

    /**
     * The silent frames still to output before the current file.
     */
    private long padding;

    /**
     * The frames of the current file still to drop.
     */
    private long skip;

    /**
     * The number of frames output.
     */
    private long produced;

    /**
     * Constructs a decoder.
     *
     * @param first the decoder of the first file, which this decoder owns
     * @param options the options {@code first} was created with, for the decoders of later files
     */
    SegmentedAudioDecoder(AudioStreamDecoder first, AudioStreamDecoderOptions options) {
        this.options = options;
        this.current = first;
    }

    /**
     * {@return the decoder of the current file}
     */
    AudioStreamDecoder current() {
        return current;
    }

    /**
     * {@return the output sampling rate in hertz}
     */
    int sampleRate() {
        return current.sampleRate();
    }

    /**
     * {@return the sample size of integer output, or 0}
     */
    int bitsPerSample() {
        return current.bitsPerSample();
    }

    /**
     * Decodes the next packet of the current file.
     *
     * @param packet the next packet of the track
     */
    void decode(Packet packet) {
        current.decode(packet);
    }

    /**
     * Ends the current file and continues with another.
     *
     * @param track the next file's track, with the first file's identifier and time base
     * @param startMicros the start of the next file on the concatenated timeline, in microseconds
     */
    void startSegment(AudioTrack track, long startMicros) {
        current.finish();
        var next = AudioStreamDecoder.create(track, options);
        if (draining != null) {
            // a file without packets for this track: the previous one is still draining
            current.close();
        } else {
            draining = current;
        }
        current = next;
        pendingBoundaryMicros = startMicros;
        padding = 0;
        skip = 0;
    }

    /**
     * Ends the last file.
     */
    void finish() {
        current.finish();
    }

    /**
     * Moves converted float samples.
     *
     * @param planes the destination planes
     * @param offset the index of the first sample written
     * @param frames the largest number of frames moved
     * @return the number of frames moved, {@code 0} when more input is needed or the output ended
     */
    int read(float[][] planes, int offset, int frames) {
        return read(planes, offset, frames, SampleFormat.FLOAT);
    }

    /**
     * Moves integer samples.
     *
     * @param planes the destination planes
     * @param offset the index of the first sample written
     * @param frames the largest number of frames moved
     * @return the number of frames moved
     */
    int readIntegers(int[][] planes, int offset, int frames) {
        return read(planes, offset, frames, SampleFormat.INTEGER);
    }

    /**
     * Moves double samples.
     *
     * @param planes the destination planes
     * @param offset the index of the first sample written
     * @param frames the largest number of frames moved
     * @return the number of frames moved
     */
    int readDoubles(double[][] planes, int offset, int frames) {
        return read(planes, offset, frames, SampleFormat.DOUBLE);
    }

    /**
     * Moves samples of a format.
     *
     * @param planes the destination planes, of the format's array type
     * @param offset the index of the first sample written
     * @param frames the largest number of frames moved
     * @param format the format of the planes
     * @return the number of frames moved
     */
    private int read(Object[] planes, int offset, int frames, SampleFormat format) {
        while (true) {
            if (draining != null) {
                var count = readFrom(draining, planes, offset, frames, format);
                if (count > 0) {
                    produced += count;
                    return count;
                }
                draining.close();
                draining = null;
            }
            if (pendingBoundaryMicros >= 0) {
                boundary = Math.round(pendingBoundaryMicros * (double) current.sampleRate() / 1_000_000);
                pendingBoundaryMicros = -1;
                padding = Math.max(0, boundary - produced);
                skip = Math.max(0, produced - boundary);
            }
            if (padding > 0) {
                var count = (int) Math.min(padding, frames);
                for (var plane : planes) {
                    switch (plane) {
                        case float[] samples -> Arrays.fill(samples, offset, offset + count, 0f);
                        case int[] samples -> Arrays.fill(samples, offset, offset + count, 0);
                        case double[] samples -> Arrays.fill(samples, offset, offset + count, 0.0);
                        default -> throw new IllegalArgumentException("unexpected plane type");
                    }
                }
                padding -= count;
                produced += count;
                return count;
            }
            var count = readFrom(current, planes, offset, frames, format);
            if (count == 0) {
                return 0;
            }
            if (skip > 0) {
                var dropped = (int) Math.min(skip, count);
                skip -= dropped;
                count -= dropped;
                if (count == 0) {
                    continue;
                }
                for (var plane : planes) {
                    System.arraycopy(plane, offset + dropped, plane, offset, count);
                }
            }
            produced += count;
            return count;
        }
    }

    /**
     * Reads from one decoder.
     *
     * @param decoder the decoder to read
     * @param planes the destination planes
     * @param offset the index of the first sample written
     * @param frames the largest number of frames moved
     * @param format the format of the planes
     * @return the number of frames moved
     */
    private static int readFrom(AudioStreamDecoder decoder, Object[] planes, int offset, int frames,
                                SampleFormat format) {
        return switch (format) {
            case FLOAT -> decoder.read((float[][]) planes, offset, frames);
            case INTEGER -> decoder.readIntegers((int[][]) planes, offset, frames);
            case DOUBLE -> decoder.readDoubles((double[][]) planes, offset, frames);
        };
    }

    @Override
    public void close() {
        try {
            if (draining != null) {
                draining.close();
            }
        } finally {
            current.close();
        }
    }
}
