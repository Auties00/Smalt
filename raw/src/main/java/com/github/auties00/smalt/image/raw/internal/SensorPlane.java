package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Holds the stored sensor samples of a raw image, before any processing: unsigned integers of up
 * to 16 bits in a {@code short[]}, unsigned integers of 17 to 32 bits in an {@code int[]}, or
 * floating-point values in a {@code float[]}.
 *
 * <p>Samples are interleaved: the sample {@code s} of the pixel at row {@code y} and column
 * {@code x} is at index {@code (y * width + x) * samplesPerPixel + s}.
 */
public final class SensorPlane {
    /**
     * The width in pixels.
     */
    final int width;

    /**
     * The height in pixels.
     */
    final int height;

    /**
     * The number of samples of each pixel.
     */
    final int samplesPerPixel;

    /**
     * The integer samples of up to 16 bits, or {@code null} for another storage.
     */
    final short[] integers;

    /**
     * The unsigned integer samples of 17 to 32 bits, or {@code null} for another storage.
     */
    final int[] wide;

    /**
     * The floating-point samples, or {@code null} for an integer plane.
     */
    final float[] floats;

    /**
     * Allocates a plane of integers of up to 16 bits or of floating-point values after checking it
     * against the limits.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param samplesPerPixel the samples of each pixel
     * @param floating whether samples are floating-point
     * @param limits the resource bounds
     */
    SensorPlane(int width, int height, int samplesPerPixel, boolean floating, MediaLimits limits) {
        this(width, height, samplesPerPixel, floating, false, limits);
    }

    /**
     * Allocates a plane after checking it against the limits.
     *
     * @param width the width in pixels
     * @param height the height in pixels
     * @param samplesPerPixel the samples of each pixel
     * @param floating whether samples are floating-point
     * @param wide whether integer samples have more than 16 bits; ignored for floating-point samples
     * @param limits the resource bounds
     */
    SensorPlane(int width, int height, int samplesPerPixel, boolean floating, boolean wide, MediaLimits limits) {
        LimitChecks.checkPicture(limits, width, height);
        var samples = (long) width * height * samplesPerPixel;
        var storeWide = wide && !floating;
        LimitChecks.checkAllocation(limits, samples * (floating || storeWide ? 4 : 2));
        this.width = width;
        this.height = height;
        this.samplesPerPixel = samplesPerPixel;
        this.integers = floating || storeWide ? null : new short[(int) samples];
        this.wide = storeWide ? new int[(int) samples] : null;
        this.floats = floating ? new float[(int) samples] : null;
    }

    /**
     * Allocates the plane that holds the stored samples of a sensor: of 32-bit integers when its
     * reader stores samples of more than 16 bits, of 16-bit integers or floating-point values
     * otherwise.
     *
     * @param sensor the sensor description
     * @param limits the resource bounds
     * @return the plane, of the sensor's stored size
     */
    static SensorPlane of(Sensor sensor, MediaLimits limits) {
        return new SensorPlane(sensor.width, sensor.height, sensor.samplesPerPixel, sensor.floating,
                sensor.wide && sensor.bitsPerSample > 16, limits);
    }

    /**
     * Returns whether the samples are floating-point.
     *
     * @return {@code true} for a floating-point plane
     */
    boolean floating() {
        return floats != null;
    }

    /**
     * Returns one sample as a number.
     *
     * @param index the sample index
     * @return the sample, rounded to single precision for integers of more than 24 bits
     */
    float sample(int index) {
        if (integers != null) {
            return integers[index] & 0xFFFF;
        }
        return wide != null ? (float) Integer.toUnsignedLong(wide[index]) : floats[index];
    }

    /**
     * Stores an integer sample, whatever the integer storage.
     *
     * @param index the sample index
     * @param value the unsigned value, which fits the storage
     */
    void setInteger(int index, long value) {
        if (integers != null) {
            integers[index] = (short) value;
        } else {
            wide[index] = (int) value;
        }
    }

    /**
     * Returns an integer sample, whatever the integer storage.
     *
     * @param index the sample index
     * @return the unsigned value
     */
    long integer(int index) {
        return integers != null ? integers[index] & 0xFFFF : Integer.toUnsignedLong(wide[index]);
    }

    /**
     * {@return the number of samples the plane holds}
     */
    int length() {
        return integers != null ? integers.length : wide != null ? wide.length : floats.length;
    }
}
