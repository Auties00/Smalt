package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.video.av1.internal.encoder.FirstPassAnalyzer;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Objects;

/**
 * The measurements of every frame of a video that an {@link Av1FirstPass} took, which a
 * good-quality {@link Av1Encoder} reads ({@link Av1EncoderOptions#firstPassStatistics()}) to code
 * the same video in a second pass with knowledge of the whole stream.
 *
 * <p>The serialized form ({@link #toByteArray()}, {@link #parse(byte[])}) holds the picture size
 * and the measurements, so statistics can be kept between the passes, in a file for example; it is
 * specific to this library and to its version of the analysis. Instances are immutable and
 * thread-safe.
 */
public final class Av1FirstPassStatistics {
    /**
     * The first bytes of the serialized form.
     */
    private static final long MAGIC = 0x534D414C54465031L;

    /**
     * The version of the serialized form.
     */
    private static final int VERSION = 1;

    /**
     * The number of values measured per frame.
     */
    static final int VALUES = FirstPassAnalyzer.VALUES;

    private final int width;

    private final int height;

    private final double[] values;

    /**
     * Creates statistics.
     *
     * @param width the picture width
     * @param height the picture height
     * @param values the values of every frame, {@link #VALUES} per frame
     */
    Av1FirstPassStatistics(int width, int height, double[] values) {
        this.width = width;
        this.height = height;
        this.values = values;
    }

    /**
     * {@return the width of the measured pictures}
     */
    public int width() {
        return width;
    }

    /**
     * {@return the height of the measured pictures}
     */
    public int height() {
        return height;
    }

    /**
     * {@return the number of measured frames}
     */
    public int frameCount() {
        return values.length / VALUES;
    }

    /**
     * Returns the values of the frames, {@link #VALUES} per frame in display order.
     *
     * @return the values; the array is shared and must not be modified
     */
    double[] values() {
        return values;
    }

    /**
     * Returns the serialized form.
     *
     * @return the bytes
     */
    public byte[] toByteArray() {
        var out = ByteBuffer.allocate(8 + 4 * 4 + values.length * 8);
        out.putLong(MAGIC).putInt(VERSION).putInt(width).putInt(height).putInt(frameCount());
        for (var v : values) {
            out.putDouble(v);
        }
        return out.array();
    }

    /**
     * Reads statistics from their serialized form.
     *
     * @param data the bytes {@link #toByteArray()} returned
     * @return the statistics
     * @throws IllegalArgumentException if the bytes are not serialized statistics of this version,
     * or hold a measurement no first pass takes (a negative error, or a share of blocks outside
     * [0, 1])
     */
    public static Av1FirstPassStatistics parse(byte[] data) {
        Objects.requireNonNull(data, "data");
        var in = ByteBuffer.wrap(data);
        if (data.length < 24 || in.getLong() != MAGIC) {
            throw new IllegalArgumentException("not AV1 first pass statistics");
        }
        var version = in.getInt();
        if (version != VERSION) {
            throw new IllegalArgumentException("unsupported first pass statistics version " + version);
        }
        var width = in.getInt();
        var height = in.getInt();
        var frames = in.getInt();
        if (width < 1 || height < 1 || frames < 0 || (long) frames * VALUES * 8 != in.remaining()) {
            throw new IllegalArgumentException("corrupt first pass statistics");
        }
        var values = new double[frames * VALUES];
        for (var i = 0; i < values.length; i++) {
            values[i] = in.getDouble();
            if (!plausible(i % VALUES, values[i])) {
                throw new IllegalArgumentException("corrupt first pass statistics");
            }
        }
        return new Av1FirstPassStatistics(width, height, values);
    }

    /**
     * Returns whether a value can be a measurement: finite, an error not negative and a share of
     * blocks within [0, 1].
     *
     * @param index the index of the value within its frame's values: the three errors, then the
     * four shares of blocks, then the motion values
     * @param value the measured value
     * @return {@code true} when plausible
     */
    private static boolean plausible(int index, double value) {
        if (!Double.isFinite(value)) {
            return false;
        }
        if (index < 3) {
            return value >= 0;
        }
        return index >= 7 || value >= 0 && value <= 1;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Av1FirstPassStatistics that && width == that.width && height == that.height
                && Arrays.equals(values, that.values);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * width + height) + Arrays.hashCode(values);
    }

    @Override
    public String toString() {
        return "Av1FirstPassStatistics[" + width + "x" + height + ", " + frameCount() + " frames]";
    }
}
