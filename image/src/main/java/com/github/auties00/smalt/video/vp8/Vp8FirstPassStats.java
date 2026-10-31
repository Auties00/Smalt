package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.MediaException;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Holds the first-pass statistics of a two-pass VP8 encode: one record per picture of the clip,
 * which the second pass, a {@link Vp8Encoder} whose {@link Vp8EncoderOptions#firstPassStats()}
 * holds them, reads to place key frames and spread the bitrate over the whole clip.
 *
 * <p>A {@link Vp8FirstPass} produces the statistics; {@link #toByteArray()} and
 * {@link #fromByteArray(byte[])} store and restore them, for example in a file between the two
 * passes. The serialized form is specific to this library and may change between releases.
 * Instances are immutable and thread-safe.
 */
public final class Vp8FirstPassStats {
    /**
     * The magic number of the serialized form, ASCII {@code "VP8S"}.
     */
    private static final int MAGIC = 0x56503853;

    /**
     * The version of the serialized form.
     */
    private static final int VERSION = 1;

    /**
     * The size of the serialized header in bytes: magic, version, width, height and frame count.
     */
    private static final int HEADER = 20;

    /**
     * The number of values of each frame's record.
     */
    static final int FIELDS = 18;

    /**
     * The picture width the statistics were measured on.
     */
    private final int width;

    /**
     * The picture height the statistics were measured on.
     */
    private final int height;

    /**
     * The records, {@link #FIELDS} values per frame in display order.
     */
    private final double[] values;

    /**
     * Constructs statistics from their records.
     *
     * @param width the picture width
     * @param height the picture height
     * @param values the records, owned by the new instance
     */
    Vp8FirstPassStats(int width, int height, double[] values) {
        this.width = width;
        this.height = height;
        this.values = values;
    }

    /**
     * Restores statistics from their serialized form.
     *
     * @param data the bytes {@link #toByteArray()} returned
     * @return the statistics
     * @throws MediaException.Malformed if {@code data} is not the serialized form of first-pass
     * statistics of this version
     */
    public static Vp8FirstPassStats fromByteArray(byte[] data) {
        if (data.length < HEADER) {
            throw new MediaException.Malformed("vp8 first-pass statistics are truncated");
        }
        var buffer = ByteBuffer.wrap(data);
        if (buffer.getInt() != MAGIC) {
            throw new MediaException.Malformed("not vp8 first-pass statistics");
        }
        var version = buffer.getInt();
        if (version != VERSION) {
            throw new MediaException.Malformed("unsupported vp8 first-pass statistics version " + version);
        }
        var width = buffer.getInt();
        var height = buffer.getInt();
        var frames = buffer.getInt();
        if (width < 1 || height < 1 || frames < 0) {
            throw new MediaException.Malformed("invalid vp8 first-pass statistics header");
        }
        if ((long) frames * FIELDS * Double.BYTES != data.length - HEADER) {
            throw new MediaException.Malformed("vp8 first-pass statistics hold " + (data.length - HEADER)
                    + " bytes of records, expected " + (long) frames * FIELDS * Double.BYTES);
        }
        var values = new double[frames * FIELDS];
        for (var i = 0; i < values.length; i++) {
            var value = buffer.getDouble();
            if (!Double.isFinite(value)) {
                throw new MediaException.Malformed("vp8 first-pass statistics hold a non-finite value");
            }
            values[i] = value;
        }
        return new Vp8FirstPassStats(width, height, values);
    }

    /**
     * Returns the serialized form, which {@link #fromByteArray(byte[])} restores.
     *
     * @return a new array holding the statistics
     */
    public byte[] toByteArray() {
        var buffer = ByteBuffer.allocate(HEADER + values.length * Double.BYTES);
        buffer.putInt(MAGIC).putInt(VERSION).putInt(width).putInt(height).putInt(frameCount());
        for (var value : values) {
            buffer.putDouble(value);
        }
        return buffer.array();
    }

    /**
     * {@return the number of pictures the first pass analyzed}
     */
    public int frameCount() {
        return values.length / FIELDS;
    }

    /**
     * {@return the picture width the first pass analyzed, in pixels}
     */
    public int width() {
        return width;
    }

    /**
     * {@return the picture height the first pass analyzed, in pixels}
     */
    public int height() {
        return height;
    }

    /**
     * {@return the records, for the encoder; not to be modified}
     */
    double[] values() {
        return values;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Vp8FirstPassStats that && width == that.width && height == that.height
                && Arrays.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * width + height) + Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return "Vp8FirstPassStats[" + width + "x" + height + ", frames=" + frameCount() + "]";
    }
}
