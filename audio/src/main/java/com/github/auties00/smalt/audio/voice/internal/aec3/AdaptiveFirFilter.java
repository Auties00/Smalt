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
 * <p>Each partition holds the 65-bin spectrum of 64 filter taps; the output is the sum of each
 * partition times the render spectrum of the corresponding past block. The filter size can
 * change gradually between an initial and a final number of partitions. After each adaptation
 * one partition, in turn, is constrained to a causal 64-tap response by transforming it to the
 * time domain, zeroing its second half and transforming it back.
 *
 * @implNote This implementation ports {@code AdaptiveFirFilter} of WebRTC's
 * {@code modules/audio_processing/aec3/adaptive_fir_filter.cc} for one render channel; the
 * per-bin loops run through {@link AdaptiveFilterKernel}.
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
     * The real parts of the partitions, 65 per partition.
     */
    final float[] hRe;

    /**
     * The imaginary parts of the partitions, 65 per partition.
     */
    final float[] hIm;

    /**
     * The partition constrained by the next adaptation.
     */
    private int partitionToConstrain;

    /**
     * The scratch buffer of the time-domain partition.
     */
    private final float[] h = new float[FFT_LENGTH];

    /**
     * Creates a filter of zeros.
     *
     * @param maxSizePartitions the largest number of partitions
     * @param initialSizePartitions the initial number of partitions
     * @param sizeChangeDurationBlocks the number of blocks a gradual size change takes
     * @param kernel the kernel of the per-bin loops
     */
    AdaptiveFirFilter(int maxSizePartitions, int initialSizePartitions, int sizeChangeDurationBlocks,
                      AdaptiveFilterKernel kernel) {
        this.kernel = kernel;
        this.maxSizePartitions = maxSizePartitions;
        this.sizeChangeDurationBlocks = sizeChangeDurationBlocks;
        this.currentSizePartitions = initialSizePartitions;
        this.targetSizePartitions = initialSizePartitions;
        this.oldTargetSizePartitions = initialSizePartitions;
        this.hRe = new float[maxSizePartitions * FFT_LENGTH_BY_2_PLUS_1];
        this.hIm = new float[maxSizePartitions * FFT_LENGTH_BY_2_PLUS_1];
        this.oneBySizeChangeDurationBlocks = 1.f / sizeChangeDurationBlocks;
        setSizePartitions(currentSizePartitions, true);
    }

    /**
     * Clears the partitions beyond the current size.
     */
    void handleEchoPathChange() {
        zeroFilter(currentSizePartitions, maxSizePartitions);
    }

    /**
     * Sets the number of partitions, at once or gradually over the size change duration.
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
     * Returns the current number of partitions.
     *
     * @return the number of partitions
     */
    int sizePartitions() {
        return currentSizePartitions;
    }

    /**
     * Computes the filter output spectrum.
     *
     * @param renderBuffer the render rings
     * @param s receives the output spectrum
     */
    void filter(RenderBuffer renderBuffer, FftData s) {
        kernel.apply(renderBuffer.fftRe, renderBuffer.fftIm, renderBuffer.position(), currentSizePartitions, hRe, hIm,
                s.re, s.im);
    }

    /**
     * Adapts the filter with an update gain and constrains one partition.
     *
     * @param renderBuffer the render rings
     * @param g the update gain
     */
    void adapt(RenderBuffer renderBuffer, FftData g) {
        adaptAndUpdateSize(renderBuffer, g);
        constrain();
    }

    /**
     * Adapts the filter with an update gain, constrains one partition, and stores that
     * partition's time-domain response in the impulse response.
     *
     * @param renderBuffer the render rings
     * @param g the update gain
     * @param impulseResponse the impulse response; its length is resized to the current size
     */
    void adapt(RenderBuffer renderBuffer, FftData g, ImpulseResponse impulseResponse) {
        adaptAndUpdateSize(renderBuffer, g);
        constrainAndUpdateImpulseResponse(impulseResponse);
    }

    /**
     * Computes the power response of every current partition.
     *
     * @param h2 receives the power responses, 65 per partition
     * @return the number of partitions written
     */
    int computeFrequencyResponse(float[] h2) {
        kernel.frequencyResponse(currentSizePartitions, hRe, hIm, h2);
        return currentSizePartitions;
    }

    /**
     * Multiplies every partition, including those beyond the current size, by a factor.
     *
     * @param factor the gain applied to every coefficient
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
     * Copies the first partitions of another filter.
     *
     * @param numPartitions the number of partitions of the other filter
     * @param other the filter to copy
     */
    void setFilter(int numPartitions, AdaptiveFirFilter other) {
        var count = Math.min(currentSizePartitions, numPartitions) * FFT_LENGTH_BY_2_PLUS_1;
        System.arraycopy(other.hRe, 0, hRe, 0, count);
        System.arraycopy(other.hIm, 0, hIm, 0, count);
    }

    /**
     * Moves the size one step towards its target.
     */
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

    /**
     * Returns the weighted average of two sizes in single precision.
     *
     * @param from the size moved from
     * @param to the size moved towards
     * @param fromWeight the weight of {@code from}
     * @return {@code from * fromWeight + to * (1 - fromWeight)}
     */
    private static float average(float from, float to, float fromWeight) {
        return from * fromWeight + to * (1.f - fromWeight);
    }

    /**
     * Updates the size and adapts the current partitions.
     *
     * @param renderBuffer the render rings
     * @param g the update gain
     */
    private void adaptAndUpdateSize(RenderBuffer renderBuffer, FftData g) {
        updateSize();
        kernel.adapt(renderBuffer.fftRe, renderBuffer.fftIm, renderBuffer.position(), currentSizePartitions, g.re, g.im,
                hRe, hIm);
    }

    /**
     * Constrains the next partition to a causal response.
     */
    private void constrain() {
        var offset = partitionToConstrain * FFT_LENGTH_BY_2_PLUS_1;
        Aec3Fft.ifft(hRe, hIm, offset, h);
        for (var i = 0; i < FFT_LENGTH_BY_2; i++) {
            h[i] *= INVERSE_SCALE;
        }
        Arrays.fill(h, FFT_LENGTH_BY_2, FFT_LENGTH, 0.f);
        Aec3Fft.fft(h, hRe, hIm, offset);
        partitionToConstrain = partitionToConstrain < (currentSizePartitions - 1) ? partitionToConstrain + 1 : 0;
    }

    /**
     * Constrains the next partition to a causal response and stores it in the impulse response.
     *
     * @param impulseResponse the impulse response
     */
    private void constrainAndUpdateImpulseResponse(ImpulseResponse impulseResponse) {
        impulseResponse.resize(currentSizePartitions * FFT_LENGTH_BY_2);
        var start = partitionToConstrain * FFT_LENGTH_BY_2;
        var offset = partitionToConstrain * FFT_LENGTH_BY_2_PLUS_1;
        Aec3Fft.ifft(hRe, hIm, offset, h);
        for (var i = 0; i < FFT_LENGTH_BY_2; i++) {
            h[i] *= INVERSE_SCALE;
        }
        Arrays.fill(h, FFT_LENGTH_BY_2, FFT_LENGTH, 0.f);
        System.arraycopy(h, 0, impulseResponse.values, start, FFT_LENGTH_BY_2);
        Aec3Fft.fft(h, hRe, hIm, offset);
        partitionToConstrain = partitionToConstrain < (currentSizePartitions - 1) ? partitionToConstrain + 1 : 0;
    }

    /**
     * Clears partitions.
     *
     * @param oldSize the first partition to clear
     * @param newSize the partition after the last to clear
     */
    private void zeroFilter(int oldSize, int newSize) {
        if (newSize > oldSize) {
            Arrays.fill(hRe, oldSize * FFT_LENGTH_BY_2_PLUS_1, newSize * FFT_LENGTH_BY_2_PLUS_1, 0.f);
            Arrays.fill(hIm, oldSize * FFT_LENGTH_BY_2_PLUS_1, newSize * FFT_LENGTH_BY_2_PLUS_1, 0.f);
        }
    }

    /**
     * Holds a time-domain impulse response whose length follows the filter size.
     */
    static final class ImpulseResponse {
        /**
         * The coefficients; entries from {@link #length} on are unused.
         */
        final float[] values;

        /**
         * The current length.
         */
        int length;

        /**
         * Creates a response of zeros.
         *
         * @param capacity the largest length
         */
        ImpulseResponse(int capacity) {
            this.values = new float[capacity];
            this.length = capacity;
        }

        /**
         * Changes the length; entries added by growing are zero.
         *
         * @param newLength the new length
         */
        void resize(int newLength) {
            if (newLength > length) {
                Arrays.fill(values, length, newLength, 0.f);
            }
            length = newLength;
        }
    }
}
