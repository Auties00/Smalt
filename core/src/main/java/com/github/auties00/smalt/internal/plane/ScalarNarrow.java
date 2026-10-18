package com.github.auties00.smalt.internal.plane;

/**
 * Implements {@link Narrow} in plain Java; the reference implementation.
 */
final class ScalarNarrow extends Narrow {
    /**
     * Constructs the kernels.
     */
    ScalarNarrow() {
    }

    @Override
    public void narrow(short[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride,
                       int width, int height, int shift) {
        var half = shift == 0 ? 0 : 1 << (shift - 1);
        for (var y = 0; y < height; y++) {
            var s = srcOffset + y * srcStride;
            var d = dstOffset + y * dstStride;
            for (var x = 0; x < width; x++) {
                dst[d + x] = (byte) Math.min(((src[s + x] & 0xFFFF) + half) >> shift, 255);
            }
        }
    }
}
