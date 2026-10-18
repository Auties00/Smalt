package com.github.auties00.smalt.image.ops;

/**
 * Converts between gamma-encoded sRGB samples and linear light with the IEC 61966-2-1 transfer
 * function.
 */
final class Srgb {
    /**
     * The linear value of every 8-bit sample.
     */
    private static final float[] DECODE8 = new float[256];

    /**
     * The linear values at the midpoints between consecutive 8-bit samples: entry {@code k}
     * (for {@code k >= 1}) is the decoded value of {@code (k - 0.5) / 255}.
     */
    private static final float[] THRESHOLDS8 = new float[256];

    static {
        for (var i = 0; i < 256; i++) {
            DECODE8[i] = (float) decode(i / 255.0);
            THRESHOLDS8[i] = i == 0 ? Float.NEGATIVE_INFINITY : (float) decode((i - 0.5) / 255);
        }
    }

    /**
     * Prevents instantiation.
     */
    private Srgb() {
        throw new AssertionError();
    }

    /**
     * Decodes a gamma-encoded value to linear light.
     *
     * @param value the encoded value, {@code 0..1}
     * @return the linear value
     */
    static double decode(double value) {
        return value <= 0.04045 ? value / 12.92 : Math.pow((value + 0.055) / 1.055, 2.4);
    }

    /**
     * Encodes a linear value with the sRGB transfer function.
     *
     * @param linear the linear value, {@code 0..1}
     * @return the encoded value
     */
    static double encode(double linear) {
        return linear <= 0.0031308 ? linear * 12.92 : 1.055 * Math.pow(linear, 1 / 2.4) - 0.055;
    }

    /**
     * Returns the linear value of an 8-bit sample.
     *
     * @param sample the sample, {@code 0..255}
     * @return the linear value
     */
    static float decode8(int sample) {
        return DECODE8[sample];
    }

    /**
     * Encodes a linear value to the 8-bit sample nearest its encoded value.
     *
     * @param linear the linear value; clamped to {@code 0..1}
     * @return the sample, {@code 0..255}
     */
    static int encode8(float linear) {
        var low = 0;
        var high = 255;
        while (low < high) {
            var mid = (low + high + 1) >>> 1;
            if (THRESHOLDS8[mid] <= linear) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return low;
    }

    /**
     * Encodes a linear value to the nearest 16-bit sample.
     *
     * @param linear the linear value; clamped to {@code 0..1}
     * @return the sample, {@code 0..65535}
     */
    static int encode16(double linear) {
        return (int) Math.round(65535 * encode(Math.clamp(linear, 0, 1)));
    }
}
