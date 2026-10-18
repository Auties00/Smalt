package com.github.auties00.smalt.image.color;

/**
 * Copies the color channels unchanged, for transforms that reduce to nothing.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @param channels the number of channels copied per pixel
 */
record IdentityEngine(int channels) implements ColorEngine {
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
}
