package com.github.auties00.smalt.audio.voice.internal.aec3;

import com.github.auties00.smalt.audio.voice.internal.CascadedBiQuadFilter;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;

/**
 * Decimates a block by four for the delay estimator: a sixth-order low-pass anti-aliasing filter,
 * a second-order high-pass filter, then every fourth sample.
 *
 * <p>When it flushes tiny values, the decimator zeroes, after every block, the filter states and
 * decimated samples whose magnitude is below {@link Aec3#FLUSH_FLOOR}: a silent signal then
 * decimates to exact zeros rather than to the subnormal numbers the filters decay into, which
 * would slow down every product of the matched filters that later reads them.
 *
 * @implNote This implementation ports WebRTC's {@code modules/audio_processing/aec3/decimator.cc}
 * for the default down-sampling factor of four, with the filter sections of
 * {@code GetLowPassFilterDS4} and {@code GetHighPassFilter}.
 */
final class Decimator {
    /**
     * The anti-aliasing low-pass filter.
     */
    private final CascadedBiQuadFilter antiAliasingFilter = CascadedBiQuadFilter.ofZerosAndPoles(new float[][]{
            {-0.08873842f, 0.99605496f, 0.75916227f, 0.23841065f, 0.26250696827f, 0},
            {0.62273832f, 0.78243018f, 0.74892112f, 0.5410152f, 0.26250696827f, 0},
            {0.71107693f, 0.70311421f, 0.74895534f, 0.63924616f, 0.26250696827f, 0}
    });

    /**
     * The high-pass filter that removes the low-frequency noise.
     */
    private final CascadedBiQuadFilter noiseReductionFilter = CascadedBiQuadFilter.ofZerosAndPoles(new float[][]{
            {1.f, 0.f, 0.72712179f, 0.21296904f, 0.7570763753338849f, 0}
    });

    /**
     * The filtered block before decimation.
     */
    private final float[] filtered = new float[BLOCK_SIZE];

    /**
     * Whether tiny filter states and samples are zeroed after every block.
     */
    private final boolean flushTiny;

    /**
     * Creates a decimator with zero filter state.
     *
     * @param flushTiny whether tiny filter states and samples are zeroed after every block;
     *                  {@code false} reproduces the reference bit for bit
     */
    Decimator(boolean flushTiny) {
        this.flushTiny = flushTiny;
    }

    /**
     * Decimates one block by four.
     *
     * @param in       the samples
     * @param inOffset the index of the first of the 64 samples
     * @param out      receives the 16 decimated samples
     */
    void decimate(float[] in, int inOffset, float[] out) {
        var x = filtered;
        antiAliasingFilter.process(in, inOffset, x, 0, BLOCK_SIZE);
        noiseReductionFilter.process(x, 0, BLOCK_SIZE);
        for (int j = 0, k = 0; j < BLOCK_SIZE / Aec3.DOWN_SAMPLING_FACTOR; ++j, k += Aec3.DOWN_SAMPLING_FACTOR) {
            out[j] = x[k];
        }
        if (flushTiny) {
            antiAliasingFilter.flushStates(Aec3.FLUSH_FLOOR);
            noiseReductionFilter.flushStates(Aec3.FLUSH_FLOOR);
            for (var j = 0; j < BLOCK_SIZE / Aec3.DOWN_SAMPLING_FACTOR; ++j) {
                if (Math.abs(out[j]) < Aec3.FLUSH_FLOOR) {
                    out[j] = 0.f;
                }
            }
        }
    }
}
