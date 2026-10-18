package com.github.auties00.smalt.image.color;

/**
 * Implements {@link MatShaperKernel} with scalar code.
 */
final class ScalarMatShaperKernel implements MatShaperKernel {
    /**
     * Creates the kernel.
     */
    ScalarMatShaperKernel() {
    }

    @Override
    public void apply(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int count,
                      Tables tables, int[] scratch) {
        var red = tables.red();
        var green = tables.green();
        var blue = tables.blue();
        var m = tables.matrix();
        var o = tables.offset();
        var m00 = m[0];
        var m01 = m[1];
        var m02 = m[2];
        var m10 = m[3];
        var m11 = m[4];
        var m12 = m[5];
        var m20 = m[6];
        var m21 = m[7];
        var m22 = m[8];
        var k0 = o[0] + 0x2000;
        var k1 = o[1] + 0x2000;
        var k2 = o[2] + 0x2000;
        var redOut = tables.redOut();
        var greenOut = tables.greenOut();
        var blueOut = tables.blueOut();
        var s = srcOffset;
        var d = dstOffset;
        for (var i = 0; i < count; i++) {
            var r = red[src[s] & 0xFF];
            var g = green[src[s + 1] & 0xFF];
            var b = blue[src[s + 2] & 0xFF];
            var l0 = (m00 * r + m01 * g + m02 * b + k0) >> 14;
            var l1 = (m10 * r + m11 * g + m12 * b + k1) >> 14;
            var l2 = (m20 * r + m21 * g + m22 * b + k2) >> 14;
            dst[d] = redOut[Math.min(Math.max(l0, 0), 16384)];
            dst[d + 1] = greenOut[Math.min(Math.max(l1, 0), 16384)];
            dst[d + 2] = blueOut[Math.min(Math.max(l2, 0), 16384)];
            s += srcStride;
            d += dstStride;
        }
    }
}
