/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/adaptive_fir_filter.cc). Copyright (c) 2011, The
   WebRTC project authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Models the echo path with a partitioned frequency-domain adaptive FIR filter.
 *
 * <p>Each partition holds, for every render channel, the 65-bin spectrum of 64 filter taps; the
 * output is the sum over partitions and channels of each partition times the render spectrum of
 * the corresponding past block. The filter size can change gradually between an initial and a
 * final number of partitions. After each adaptation one partition, in turn, is constrained to a
 * causal 64-tap response by transforming it to the time domain, zeroing its second half and
 * transforming it back.
 *
 * @implNote This implementation ports {@code AdaptiveFirFilter} of WebRTC's
 * {@code modules/audio_processing/aec3/adaptive_fir_filter.cc}; the per-bin loops run through
 * {@link AdaptiveFilterKernel}, the render channels of a partition as consecutive partitions.
 */
final class AdaptiveFirFilter {
    /**
     * The scaling of the inverse transform, {@code 1 / 64}.
     */
    private static final float INVERSE_SCALE = 1.0f / FFT_LENGTH_BY_2;

    /**
     * The kernel of the per-bin loops.
     */
    private final AdaptiveFilterKernel kernel;

    /**
     * The number of render channels.
     */
    private final int numChannels;

    /**
     * The largest number of partitions.
     */
    private final int maxSizePartitions;

    /**
     * {@code 1 / sizeChangeDurationBlocks}.
     */
    private final float oneBySizeChangeDurationBlocks;

    /**
     * The number of blocks a gradual size change takes.
     */
    private final int sizeChangeDurationBlocks;

    /**
     * The current number of partitions.
     */
    private int currentSizePartitions;

    /**
     * The number of partitions the size moves towards.
     */
    private int targetSizePartitions;

    /**
     * The number of partitions the size moves from.
     */
    private int oldTargetSizePartitions;

    /**
     * The number of blocks left of the gradual size change.
     */
    private int sizeChangeCounter;

    /**
     * The real parts of the partitions, 65 per partition and channel, channels of a partition
     * next to each other.
     */
    final float[] hRe;

    /**
     * The imaginary parts of the partitions.
     */
    final float[] hIm;

    /**
     * The partition constrained next.
     */
    private int partitionToConstrain;

    /**
     * The scratch time-domain partition.
     */
    private final float[] h = new float[FFT_LENGTH];

    /**
     * Creates a filter of zeros.
     *
     * @param maxSizePartitions the largest number of partitions
     * @param initialSizePartitions the initial number of partitions
     * @param sizeChangeDurationBlocks the number of blocks a gradual size change takes
     * @param numChannels the number of render channels
     * @param kernel the kernel of the per-bin loops
     */
    AdaptiveFirFilter(int maxSizePartitions, int initialSizePartitions, int sizeChangeDurationBlocks, int numChannels,
                      AdaptiveFilterKernel kernel) {
        this.kernel = kernel;
        this.numChannels = numChannels;
        this.maxSizePartitions = maxSizePartitions;
        this.sizeChangeDurationBlocks = sizeChangeDurationBlocks;
        this.currentSizePartitions = initialSizePartitions;
        this.targetSizePartitions = initialSizePartitions;
        this.oldTargetSizePartitions = initialSizePartitions;
        this.hRe = new float[maxSizePartitions * numChannels * FFT_LENGTH_BY_2_PLUS_1];
        this.hIm = new float[maxSizePartitions * numChannels * FFT_LENGTH_BY_2_PLUS_1];
        this.oneBySizeChangeDurationBlocks = 1.f / sizeChangeDurationBlocks;
        setSizePartitions(currentSizePartitions, true);
    }

    /**
     * Zeroes the partitions beyond the current size after an echo path change.
     */
    void handleEchoPathChange() {
        zeroFilter(currentSizePartitions, maxSizePartitions);
    }

    /**
     * Sets the number of partitions, at once or gradually.
     *
     * @param size the number of partitions
     * @param immediateEffect whether the size changes at once
     */
    void setSizePartitions(int size, boolean immediateEffect) {
        targetSizePartitions = Math.min(maxSizePartitions, size);
        if (immediateEffect) {
            var oldSizePartitions = currentSizePartitions;
            currentSizePartitions = targetSizePartitions;
            oldTargetSizePartitions = targetSizePartitions;
            zeroFilter(oldSizePartitions, currentSizePartitions);
            partitionToConstrain = Math.min(partitionToConstrain, currentSizePartitions - 1);
            sizeChangeCounter = 0;
        } else {
            sizeChangeCounter = sizeChangeDurationBlocks;
        }
    }

    /**
     * {@return the current number of partitions}
     */
    int sizePartitions() {
        return currentSizePartitions;
    }

    /**
     * Computes the filter output spectrum.
     *
     * @param renderBuffer the render buffer
     * @param s receives the output spectrum
     */
    void filter(RenderBuffer renderBuffer, FftData s) {
        kernel.apply(renderBuffer.fftRe, renderBuffer.fftIm, renderBuffer.position() * numChannels,
                RenderBuffer.SIZE * numChannels, currentSizePartitions * numChannels, hRe, hIm, s.re, s.im);
    }

    /**
     * Adapts the filter with an update gain and constrains one partition.
     *
     * @param renderBuffer the render buffer
     * @param g the update gain
     */
    void adapt(RenderBuffer renderBuffer, FftData g) {
        adaptAndUpdateSize(renderBuffer, g);
        constrain();
    }

    /**
     * Adapts the filter with an update gain, constrains one partition and updates the
     * corresponding part of the impulse response.
     *
     * @param renderBuffer the render buffer
     * @param g the update gain
     * @param impulseResponse the impulse response, updated
     */
    void adapt(RenderBuffer renderBuffer, FftData g, ImpulseResponse impulseResponse) {
        adaptAndUpdateSize(renderBuffer, g);
        constrainAndUpdateImpulseResponse(impulseResponse);
    }

    /**
     * Computes the power response of every partition, the largest over the render channels.
     *
     * @param h2 receives the power responses, 65 per partition
     * @return the number of partitions written
     */
    int computeFrequencyResponse(float[] h2) {
        kernel.frequencyResponse(currentSizePartitions, numChannels, hRe, hIm, h2);
        return currentSizePartitions;
    }

    /**
     * Scales every partition.
     *
     * @param factor the scale factor
     */
    void scaleFilter(float factor) {
        for (var i = 0; i < hRe.length; i++) {
            hRe[i] *= factor;
        }
        for (var i = 0; i < hIm.length; i++) {
            hIm[i] *= factor;
        }
    }

    /**
     * Copies the first partitions of another filter of the same channel count.
     *
     * @param numPartitions the number of partitions of the other filter
     * @param other the other filter
     */
    void setFilter(int numPartitions, AdaptiveFirFilter other) {
        var count = Math.min(currentSizePartitions, numPartitions) * numChannels * FFT_LENGTH_BY_2_PLUS_1;
        System.arraycopy(other.hRe, 0, hRe, 0, count);
        System.arraycopy(other.hIm, 0, hIm, 0, count);
    }

    private void updateSize() {
        var oldSizePartitions = currentSizePartitions;
        if (sizeChangeCounter > 0) {
            --sizeChangeCounter;
            var changeFactor = sizeChangeCounter * oneBySizeChangeDurationBlocks;
            currentSizePartitions = (int) average(oldTargetSizePartitions, targetSizePartitions, changeFactor);
            partitionToConstrain = Math.min(partitionToConstrain, currentSizePartitions - 1);
        } else {
            currentSizePartitions = targetSizePartitions;
            oldTargetSizePartitions = targetSizePartitions;
        }
        zeroFilter(oldSizePartitions, currentSizePartitions);
    }

    private static float average(float from, float to, float fromWeight) {
        return from * fromWeight + to * (1.f - fromWeight);
    }

    private void adaptAndUpdateSize(RenderBuffer renderBuffer, FftData g) {
        updateSize();
        kernel.adapt(renderBuffer.fftRe, renderBuffer.fftIm, renderBuffer.position() * numChannels,
                RenderBuffer.SIZE * numChannels, currentSizePartitions * numChannels, g.re, g.im, hRe, hIm);
    }

    private void constrain() {
        for (var ch = 0; ch < numChannels; ch++) {
            var offset = (partitionToConstrain * numChannels + ch) * FFT_LENGTH_BY_2_PLUS_1;
            Aec3Fft.ifft(hRe, hIm, offset, h);
            for (var i = 0; i < FFT_LENGTH_BY_2; i++) {
                h[i] *= INVERSE_SCALE;
            }
            Arrays.fill(h, FFT_LENGTH_BY_2, FFT_LENGTH, 0.f);
            Aec3Fft.fft(h, hRe, hIm, offset);
        }
        partitionToConstrain = partitionToConstrain < (currentSizePartitions - 1) ? partitionToConstrain + 1 : 0;
    }

    private void constrainAndUpdateImpulseResponse(ImpulseResponse impulseResponse) {
        impulseResponse.resize(currentSizePartitions * FFT_LENGTH_BY_2);
        var start = partitionToConstrain * FFT_LENGTH_BY_2;
        var values = impulseResponse.values;
        Arrays.fill(values, start, start + FFT_LENGTH_BY_2, 0.f);
        for (var ch = 0; ch < numChannels; ch++) {
            var offset = (partitionToConstrain * numChannels + ch) * FFT_LENGTH_BY_2_PLUS_1;
            Aec3Fft.ifft(hRe, hIm, offset, h);
            for (var i = 0; i < FFT_LENGTH_BY_2; i++) {
                h[i] *= INVERSE_SCALE;
            }
            Arrays.fill(h, FFT_LENGTH_BY_2, FFT_LENGTH, 0.f);
            if (ch == 0) {
                System.arraycopy(h, 0, values, start, FFT_LENGTH_BY_2);
            } else {
                for (int k = 0, j = start; k < FFT_LENGTH_BY_2; ++k, ++j) {
                    if (Math.abs(values[j]) < Math.abs(h[k])) {
                        values[j] = h[k];
                    }
                }
            }
            Aec3Fft.fft(h, hRe, hIm, offset);
        }
        partitionToConstrain = partitionToConstrain < (currentSizePartitions - 1) ? partitionToConstrain + 1 : 0;
    }

    private void zeroFilter(int oldSize, int newSize) {
        if (newSize > oldSize) {
            Arrays.fill(hRe, oldSize * numChannels * FFT_LENGTH_BY_2_PLUS_1, newSize * numChannels * FFT_LENGTH_BY_2_PLUS_1,
                    0.f);
            Arrays.fill(hIm, oldSize * numChannels * FFT_LENGTH_BY_2_PLUS_1, newSize * numChannels * FFT_LENGTH_BY_2_PLUS_1,
                    0.f);
        }
    }

    /**
     * A time-domain impulse response whose length follows the filter size.
     */
    static final class ImpulseResponse {
        /**
         * The taps, at least {@link #length} of them.
         */
        final float[] values;

        /**
         * The number of taps in use.
         */
        int length;

        /**
         * Creates a response of zeros.
         *
         * @param capacity the largest number of taps
         */
        ImpulseResponse(int capacity) {
            this.values = new float[capacity];
            this.length = capacity;
        }

        /**
         * Changes the number of taps in use, zeroing the added ones.
         *
         * @param newLength the new number of taps
         */
        void resize(int newLength) {
            if (newLength > length) {
                Arrays.fill(values, length, newLength, 0.f);
            }
            length = newLength;
        }
    }
}
