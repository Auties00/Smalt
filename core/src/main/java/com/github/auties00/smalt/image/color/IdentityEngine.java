package com.github.auties00.smalt.image.color;

/**
 * Copies the color channels unchanged, for transforms that reduce to nothing.
 *
 * <p>Floating-point samples are the signals of a conversion between two equal high-dynamic-range profiles, the only
 * floating-point transform that reduces to nothing, and are clamped to {@code 0..1} as every high-dynamic-range
 * signal is. Instances are immutable and thread-safe.
 *
 * @param channels the number of channels copied per pixel
 */
record IdentityEngine(int channels) implements ColorEngine, FloatEngine {
    @Override
    public void apply(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int count) {
        for (var i = 0; i < count; i++) {
            System.arraycopy(src, srcOffset + i * srcStride, dst, dstOffset + i * dstStride, channels);
        }
    }

    @Override
    public void apply(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride, int count) {
        for (var i = 0; i < count; i++) {
            System.arraycopy(src, srcOffset + i * srcStride, dst, dstOffset + i * dstStride, channels);
        }
    }

    @Override
    public void apply(float[] src, int srcOffset, int srcStride, float[] dst, int dstOffset, int dstStride, int count) {
        for (var i = 0; i < count; i++) {
            var s = srcOffset + i * srcStride;
            var d = dstOffset + i * dstStride;
            for (var c = 0; c < channels; c++) {
                dst[d + c] = Math.clamp(src[s + c], 0.0f, 1.0f);
            }
        }
    }
}
