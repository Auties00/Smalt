package com.github.auties00.smalt.capture.internal.audio;

import com.github.auties00.smalt.capture.audio.AudioSampleFormat;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Converts between the sample encodings of audio devices and the float and 16-bit samples of
 * sessions.
 *
 * <p>Device samples are little-endian. Integer samples of {@code b} bits map to floats by
 * dividing by {@code 2^(b-1)}; floats map back by multiplying, rounding to nearest and clamping
 * to the integer range, and float device samples are clamped to {@code [-1, 1]}. The 16-bit
 * conversions of sessions follow the same rule with {@code b = 16}.
 */
public final class SampleCodec {
    /**
     * A little-endian unaligned 16-bit integer.
     */
    private static final ValueLayout.OfShort S16 = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /**
     * A little-endian unaligned 32-bit integer.
     */
    private static final ValueLayout.OfInt S32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /**
     * A little-endian unaligned 32-bit float.
     */
    private static final ValueLayout.OfFloat F32 = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    /**
     * An unsigned byte.
     */
    private static final ValueLayout.OfByte U8 = ValueLayout.JAVA_BYTE;

    /**
     * Prevents instantiation.
     */
    private SampleCodec() {
        throw new AssertionError();
    }

    /**
     * Decodes device samples into floats.
     *
     * @param format the device's sample encoding
     * @param source the device samples
     * @param target receives the floats
     * @param offset the index of the first float written
     * @param samples the number of samples, all channels counted
     */
    public static void decode(AudioSampleFormat format, MemorySegment source, float[] target, int offset, int samples) {
        switch (format) {
            case FLOAT32 -> MemorySegment.copy(source, F32, 0, target, offset, samples);
            case PCM_S16 -> {
                for (var i = 0; i < samples; i++) {
                    target[offset + i] = source.get(S16, 2L * i) * 0x1p-15f;
                }
            }
            case PCM_S24 -> {
                for (var i = 0; i < samples; i++) {
                    var at = 3L * i;
                    var value = (source.get(U8, at) & 0xFF) | (source.get(U8, at + 1) & 0xFF) << 8 | source.get(U8, at + 2) << 16;
                    target[offset + i] = value * 0x1p-23f;
                }
            }
            case PCM_S32 -> {
                for (var i = 0; i < samples; i++) {
                    target[offset + i] = (float) (source.get(S32, 4L * i) * 0x1p-31);
                }
            }
        }
    }

    /**
     * Encodes floats into device samples.
     *
     * @param format the device's sample encoding
     * @param source the floats
     * @param offset the index of the first float
     * @param target receives the device samples
     * @param samples the number of samples, all channels counted
     */
    public static void encode(AudioSampleFormat format, float[] source, int offset, MemorySegment target, int samples) {
        switch (format) {
            case FLOAT32 -> {
                for (var i = 0; i < samples; i++) {
                    target.set(F32, 4L * i, clamp(source[offset + i]));
                }
            }
            case PCM_S16 -> {
                for (var i = 0; i < samples; i++) {
                    target.set(S16, 2L * i, toShort(source[offset + i]));
                }
            }
            case PCM_S24 -> {
                for (var i = 0; i < samples; i++) {
                    var value = (int) Math.clamp(Math.round(source[offset + i] * 0x1p23), -0x800000L, 0x7FFFFFL);
                    var at = 3L * i;
                    target.set(U8, at, (byte) value);
                    target.set(U8, at + 1, (byte) (value >> 8));
                    target.set(U8, at + 2, (byte) (value >> 16));
                }
            }
            case PCM_S32 -> {
                for (var i = 0; i < samples; i++) {
                    var value = Math.clamp(Math.round(source[offset + i] * 0x1p31), Integer.MIN_VALUE, Integer.MAX_VALUE);
                    target.set(S32, 4L * i, (int) value);
                }
            }
        }
    }

    /**
     * Converts a float sample to a 16-bit one.
     *
     * @param sample the sample, nominally in {@code [-1, 1]}
     * @return {@code round(sample * 32768)} clamped to the 16-bit range
     */
    public static short toShort(float sample) {
        return (short) Math.clamp(Math.round(sample * 32768.0f), Short.MIN_VALUE, Short.MAX_VALUE);
    }

    /**
     * Converts a 16-bit sample to a float one.
     *
     * @return {@code sample / 32768}
     */
    public static float toFloat(short sample) {
        return sample * 0x1p-15f;
    }

    /**
     * Clamps a float sample to {@code [-1, 1]}, reading NaN as zero.
     *
     * @return the clamped sample
     */
    public static float clamp(float sample) {
        if (sample != sample) {
            return 0;
        }
        return Math.clamp(sample, -1.0f, 1.0f);
    }
}
