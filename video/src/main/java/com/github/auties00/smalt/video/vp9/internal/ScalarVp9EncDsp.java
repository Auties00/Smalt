package com.github.auties00.smalt.video.vp9.internal;

/**
 * Implements the encoder's pixel metrics with plain Java, the reference for
 * {@link Vp9EncDsp}'s contract.
 *
 * @implNote This implementation ports the C kernels of libvpx's {@code vpx_dsp/sad.c},
 *           {@code variance.c} and {@code subtract.c}, BSD-3.
 */
final class ScalarVp9EncDsp extends Vp9EncDsp {
    /**
     * Creates the scalar implementation.
     */
    ScalarVp9EncDsp() {
    }

    @Override
    int sad(byte[] src, int srcOff, int srcStride, byte[] ref, int refOff, int refStride, int w, int h) {
        var sad = 0;
        for (var y = 0; y < h; y++, srcOff += srcStride, refOff += refStride) {
            for (var x = 0; x < w; x++) {
                sad += Math.abs((src[srcOff + x] & 0xFF) - (ref[refOff + x] & 0xFF));
            }
        }
        return sad;
    }

    @Override
    long variance(byte[] src, int srcOff, int srcStride, byte[] ref, int refOff, int refStride, int w, int h) {
        long sse = 0;
        var sum = 0;
        for (var y = 0; y < h; y++, srcOff += srcStride, refOff += refStride) {
            var rowSse = 0;
            for (var x = 0; x < w; x++) {
                var diff = (src[srcOff + x] & 0xFF) - (ref[refOff + x] & 0xFF);
                sum += diff;
                rowSse += diff * diff;
            }
            sse += rowSse;
        }
        return pack(sse, sum);
    }

    @Override
    long subPixelVariance(byte[] ref, int refOff, int refStride, int xOffset, int yOffset, byte[] src, int srcOff,
                          int srcStride, int w, int h, short[] scratch) {
        var h0 = BILINEAR_TAPS[xOffset * 2];
        var h1 = BILINEAR_TAPS[xOffset * 2 + 1];
        for (var y = 0; y <= h; y++) {
            var row = refOff + y * refStride;
            var out = y * w;
            for (var x = 0; x < w; x++) {
                scratch[out + x] = (short) (((ref[row + x] & 0xFF) * h0 + (ref[row + x + 1] & 0xFF) * h1 + 64) >> 7);
            }
        }
        var v0 = BILINEAR_TAPS[yOffset * 2];
        var v1 = BILINEAR_TAPS[yOffset * 2 + 1];
        long sse = 0;
        var sum = 0;
        for (var y = 0; y < h; y++, srcOff += srcStride) {
            var top = y * w;
            for (var x = 0; x < w; x++) {
                var p = (scratch[top + x] * v0 + scratch[top + w + x] * v1 + 64) >> 7;
                var diff = p - (src[srcOff + x] & 0xFF);
                sum += diff;
                sse += diff * diff;
            }
        }
        return pack(sse, sum);
    }

    @Override
    void subtract(short[] diff, int diffOff, int diffStride, byte[] src, int srcOff, int srcStride, byte[] pred,
                  int predOff, int predStride, int w, int h) {
        for (var y = 0; y < h; y++, diffOff += diffStride, srcOff += srcStride, predOff += predStride) {
            for (var x = 0; x < w; x++) {
                diff[diffOff + x] = (short) ((src[srcOff + x] & 0xFF) - (pred[predOff + x] & 0xFF));
            }
        }
    }
}
