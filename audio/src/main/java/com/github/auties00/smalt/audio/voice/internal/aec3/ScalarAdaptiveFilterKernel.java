package com.github.auties00.smalt.audio.voice.internal.aec3;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Implements {@link AdaptiveFilterKernel} with scalar loops; the reference for the vector
 * kernel.
 *
 * @implNote This implementation ports {@code ApplyFilter}, {@code AdaptPartitions},
 * {@code ComputeFrequencyResponse} and {@code ErlComputer} of WebRTC's
 * {@code modules/audio_processing/aec3/adaptive_fir_filter.cc} and
 * {@code adaptive_fir_filter_erl.cc} for one render channel.
 */
final class ScalarAdaptiveFilterKernel implements AdaptiveFilterKernel {
    /**
     * Creates the kernel.
     */
    ScalarAdaptiveFilterKernel() {
    }

    @Override
    public void apply(float[] xRe, float[] xIm, int position, int numPartitions, float[] hRe, float[] hIm, float[] sRe,
                      float[] sIm) {
        Arrays.fill(sRe, 0.f);
        Arrays.fill(sIm, 0.f);
        var index = position;
        for (var p = 0; p < numPartitions; ++p) {
            var xo = index * FFT_LENGTH_BY_2_PLUS_1;
            var ho = p * FFT_LENGTH_BY_2_PLUS_1;
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                var xr = xRe[xo + k];
                var xi = xIm[xo + k];
                var hr = hRe[ho + k];
                var hi = hIm[ho + k];
                sRe[k] += xr * hr - xi * hi;
                sIm[k] += xr * hi + xi * hr;
            }
            index = index < RenderBuffer.SIZE - 1 ? index + 1 : 0;
        }
    }

    @Override
    public void adapt(float[] xRe, float[] xIm, int position, int numPartitions, float[] gRe, float[] gIm, float[] hRe,
                      float[] hIm) {
        var index = position;
        for (var p = 0; p < numPartitions; ++p) {
            var xo = index * FFT_LENGTH_BY_2_PLUS_1;
            var ho = p * FFT_LENGTH_BY_2_PLUS_1;
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                var xr = xRe[xo + k];
                var xi = xIm[xo + k];
                hRe[ho + k] += xr * gRe[k] + xi * gIm[k];
                hIm[ho + k] += xr * gIm[k] - xi * gRe[k];
            }
            index = index < RenderBuffer.SIZE - 1 ? index + 1 : 0;
        }
    }

    @Override
    public void frequencyResponse(int numPartitions, float[] hRe, float[] hIm, float[] h2) {
        for (var p = 0; p < numPartitions; ++p) {
            var o = p * FFT_LENGTH_BY_2_PLUS_1;
            for (var j = 0; j < FFT_LENGTH_BY_2_PLUS_1; ++j) {
                var tmp = hRe[o + j] * hRe[o + j] + hIm[o + j] * hIm[o + j];
                h2[o + j] = 0.f < tmp ? tmp : 0.f;
            }
        }
    }

    @Override
    public void erl(int numPartitions, float[] h2, float[] erl) {
        Arrays.fill(erl, 0.f);
        for (var p = 0; p < numPartitions; ++p) {
            var o = p * FFT_LENGTH_BY_2_PLUS_1;
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                erl[k] = h2[o + k] + erl[k];
            }
        }
    }
}
