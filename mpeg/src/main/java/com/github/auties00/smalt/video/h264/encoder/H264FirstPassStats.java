package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.h264.encoder.internal.PassRecords;

import java.nio.ByteBuffer;
import java.time.Duration;

/**
 * The first pass statistics of a two-pass H.264 encode: one record per picture of the video, which the second
 * pass, an {@link H264Encoder} whose {@link H264EncoderOptions#firstPassStats()} holds them, reads to spend its
 * bitrate over the whole video.
 *
 * <p>An {@link H264FirstPass} produces the statistics; {@link #toByteArray()} and
 * {@link #fromByteArray(byte[])} store and restore them, for example in a file between the two passes. The
 * serialized form is specific to this library and may change between releases. Instances are immutable and
 * thread-safe.
 */
public final class H264FirstPassStats {
    /**
     * The magic number of the serialized form, ASCII {@code "H26S"}.
     */
    private static final int MAGIC = 0x48323653;

    /**
     * The version of the serialized form.
     */
    private static final int VERSION = 1;

    /**
     * The size of the serialized header in bytes: magic, version, width, height and frame count.
     */
    private static final int HEADER = 20;

    /**
     * The size of a frame's serialized record in bytes: picture type, quantizer, size and duration.
     */
    private static final int RECORD = 4 + 4 + 8 + 8;

    private final int width;

    private final int height;

    private final PassRecords records;

    /**
     * Constructs statistics.
     *
     * @param width the picture width
     * @param height the picture height
     * @param records the records of the frames
     */
    H264FirstPassStats(int width, int height, PassRecords records) {
        this.width = width;
        this.height = height;
        this.records = records;
    }

    /**
     * Restores statistics from their serialized form.
     *
     * @param data the bytes {@link #toByteArray()} returned
     * @return the statistics
     * @throws MediaException.Malformed if {@code data} is not the serialized form of first pass statistics of
     * this version
     */
    public static H264FirstPassStats fromByteArray(byte[] data) {
        if (data.length < HEADER) {
            throw new MediaException.Malformed("h264 first pass statistics are truncated");
        }
        var buffer = ByteBuffer.wrap(data);
        if (buffer.getInt() != MAGIC) {
            throw new MediaException.Malformed("not h264 first pass statistics");
        }
        var version = buffer.getInt();
        if (version != VERSION) {
            throw new MediaException.Malformed("unsupported h264 first pass statistics version " + version);
        }
        var width = buffer.getInt();
        var height = buffer.getInt();
        var frames = buffer.getInt();
        if (width < 1 || height < 1 || frames < 0) {
            throw new MediaException.Malformed("invalid h264 first pass statistics header");
        }
        if ((long) frames * RECORD != data.length - HEADER) {
            throw new MediaException.Malformed("h264 first pass statistics hold " + (data.length - HEADER)
                    + " bytes of records, expected " + (long) frames * RECORD);
        }
        var types = new byte[frames];
        var qps = new int[frames];
        var bits = new long[frames];
        var seconds = new double[frames];
        for (var i = 0; i < frames; i++) {
            var type = buffer.getInt();
            if (type < PassRecords.INTRA || type > PassRecords.BIDIRECTIONAL) {
                throw new MediaException.Malformed("frame " + i + " of h264 first pass statistics has picture type "
                        + type);
            }
            types[i] = (byte) type;
            qps[i] = buffer.getInt();
            bits[i] = buffer.getLong();
            seconds[i] = buffer.getDouble();
        }
        try {
            return new H264FirstPassStats(width, height, new PassRecords(types, qps, bits, seconds));
        } catch (IllegalArgumentException exception) {
            throw new MediaException.Malformed("invalid h264 first pass statistics: " + exception.getMessage());
        }
    }

    /**
     * Returns the serialized form, which {@link #fromByteArray(byte[])} restores.
     *
     * @return a new array holding the statistics
     */
    public byte[] toByteArray() {
        var frames = records.frames();
        var buffer = ByteBuffer.allocate(HEADER + frames * RECORD);
        buffer.putInt(MAGIC).putInt(VERSION).putInt(width).putInt(height).putInt(frames);
        for (var i = 0; i < frames; i++) {
            buffer.putInt(records.type(i)).putInt(records.qp(i)).putLong(records.bits(i))
                    .putDouble(records.seconds(i));
        }
        return buffer.array();
    }

    /**
     * {@return the number of pictures the first pass measured}
     */
    public int frameCount() {
        return records.frames();
    }

    /**
     * {@return the picture width the first pass measured, in pixels}
     */
    public int width() {
        return width;
    }

    /**
     * {@return the picture height the first pass measured, in pixels}
     */
    public int height() {
        return height;
    }

    /**
     * {@return the total duration of the measured pictures, rounded to the nanosecond}
     *
     * <p>Each picture counts as rate control counts it: its declared duration, or else the time since the
     * previous picture's timestamp, or the nominal frame duration for the first picture, kept between an
     * eighth of the nominal frame duration and eight times it.
     */
    public Duration duration() {
        return Duration.ofNanos(Math.round(records.duration() * 1e9));
    }

    /**
     * {@return the records, for the encoder}
     */
    PassRecords records() {
        return records;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof H264FirstPassStats that && width == that.width && height == that.height
                && records.equals(that.records);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * width + height) + records.hashCode();
    }

    @Override
    public String toString() {
        return "H264FirstPassStats[" + width + "x" + height + ", frames=" + frameCount() + "]";
    }
}
