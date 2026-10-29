package com.github.auties00.smalt.audio.voice.internal.aec3;

/**
 * Runs the per-bin arithmetic of the partitioned frequency-domain adaptive FIR filters.
 *
 * <p>The render spectra are a ring of {@code bufferSize} spectra of 65 bins in flat real and
 * imaginary arrays; partition {@code p} of a filter is multiplied with the render spectrum
 * {@code p} entries after {@code position}, wrapping. With several render channels the ring holds
 * the channels of each block next to each other and the partitions of a filter the channels of
 * each partition next to each other, so that a filter of {@code n} partitions over {@code c}
 * channels is one of {@code n * c} partitions starting at {@code position * c} in a ring of
 * {@code SIZE * c} spectra. Every bin is computed with the same operations in the same order as
 * the reference's scalar code, and bins are independent, so every implementation is
 * bit-identical.
 */
interface AdaptiveFilterKernel {
    /**
     * Computes the filter output spectrum {@code S = sum_p X_p H_p}.
     *
     * @param xRe the real parts of the render ring
     * @param xIm the imaginary parts of the render ring
     * @param position the ring index of the render spectrum of partition 0
     * @param bufferSize the number of spectra of the ring
     * @param numPartitions the number of partitions
     * @param hRe the real parts of the filter partitions
     * @param hIm the imaginary parts of the filter partitions
     * @param sRe receives the real parts of the output
     * @param sIm receives the imaginary parts of the output
     */
    void apply(float[] xRe, float[] xIm, int position, int bufferSize, int numPartitions, float[] hRe, float[] hIm,
               float[] sRe, float[] sIm);

    /**
     * Adds the update {@code conj(X_p) G} to every partition.
     *
     * @param xRe the real parts of the render ring
     * @param xIm the imaginary parts of the render ring
     * @param position the ring index of the render spectrum of partition 0
     * @param bufferSize the number of spectra of the ring
     * @param numPartitions the number of partitions
     * @param gRe the real parts of the update gain
     * @param gIm the imaginary parts of the update gain
     * @param hRe the real parts of the filter partitions, updated
     * @param hIm the imaginary parts of the filter partitions, updated
     */
    void adapt(float[] xRe, float[] xIm, int position, int bufferSize, int numPartitions, float[] gRe, float[] gIm,
               float[] hRe, float[] hIm);

    /**
     * Computes the power response of every partition, the largest {@code |H_p|^2} of its render
     * channels and at least zero.
     *
     * @param numPartitions the number of partitions
     * @param numChannels the number of render channels of each partition
     * @param hRe the real parts of the filter partitions, {@code numChannels} spectra each
     * @param hIm the imaginary parts of the filter partitions
     * @param h2 receives the power responses, 65 per partition
     */
    void frequencyResponse(int numPartitions, int numChannels, float[] hRe, float[] hIm, float[] h2);

    /**
     * Sums the power responses of the partitions into the echo return loss estimate.
     *
     * @param numPartitions the number of partitions
     * @param h2 the power responses, 65 per partition
     * @param erl receives the 65 sums
     */
    void erl(int numPartitions, float[] h2, float[] erl);
}
