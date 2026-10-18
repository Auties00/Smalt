package com.github.auties00.smalt.audio.voice.internal.aec3;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Holds the recent render blocks of AEC3 in three ring buffers, the time-domain blocks, their
 * power spectra and their spectra, and exposes them relative to the read positions the render
 * delay buffer aligns with the capture signal.
 *
 * <p>The block ring advances its indices forwards, the spectrum and spectrum-power rings
 * backwards, so that a positive offset from the read position of a spectral ring reaches older
 * data. Entry {@code i} of the block ring occupies {@code blocks[i * 64, i * 64 + 64)}; entry
 * {@code i} of the spectral rings occupies the 65 floats at {@code i * 65}.
 *
 * @implNote This implementation merges WebRTC's {@code BlockBuffer}, {@code SpectrumBuffer},
 * {@code FftBuffer} and {@code RenderBuffer} ({@code modules/audio_processing/aec3}) for one
 * render channel and one band.
 */
final class RenderBuffer {
    /**
     * The number of entries of every ring, {@code GetRenderDelayBufferSize(4, 5, 13)}.
     */
    static final int SIZE = (BLOCK_SIZE / Aec3.DOWN_SAMPLING_FACTOR
            * (Aec3.MATCHED_FILTER_ALIGNMENT_SHIFT_SIZE_SUB_BLOCKS * Aec3.NUM_FILTERS + Aec3.MATCHED_FILTER_WINDOW_SIZE_SUB_BLOCKS + 1))
            / (BLOCK_SIZE / Aec3.DOWN_SAMPLING_FACTOR) + Aec3.REFINED_LENGTH_BLOCKS + 1;

    /**
     * The time-domain blocks.
     */
    final float[] blocks = new float[SIZE * BLOCK_SIZE];

    /**
     * The write index of the block ring.
     */
    int blockWrite;

    /**
     * The read index of the block ring.
     */
    int blockRead;

    /**
     * The power spectra.
     */
    final float[] spectra = new float[SIZE * FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The write index of the power spectrum ring.
     */
    int spectrumWrite;

    /**
     * The read index of the power spectrum ring.
     */
    int spectrumRead;

    /**
     * The real parts of the spectra.
     */
    final float[] fftRe = new float[SIZE * FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The imaginary parts of the spectra.
     */
    final float[] fftIm = new float[SIZE * FFT_LENGTH_BY_2_PLUS_1];

    /**
     * The write index of the spectrum ring.
     */
    int fftWrite;

    /**
     * The read index of the spectrum ring.
     */
    int fftRead;

    /**
     * Whether the render signal was active since the last capture block.
     */
    boolean renderActivity;

    /**
     * Creates empty rings.
     */
    RenderBuffer() {
    }

    /**
     * Returns the next index of a ring.
     *
     * @param index an index
     * @return {@code index + 1}, wrapped
     */
    static int incIndex(int index) {
        return index < SIZE - 1 ? index + 1 : 0;
    }

    /**
     * Returns the previous index of a ring.
     *
     * @param index an index
     * @return {@code index - 1}, wrapped
     */
    static int decIndex(int index) {
        return index > 0 ? index - 1 : SIZE - 1;
    }

    /**
     * Returns an index moved by an offset, wrapped.
     *
     * @param index  an index
     * @param offset the offset, at least {@code -SIZE}
     * @return {@code (SIZE + index + offset) % SIZE}
     */
    static int offsetIndex(int index, int offset) {
        return (SIZE + index + offset) % SIZE;
    }

    /**
     * Returns the start of the block at an offset from the block read position.
     *
     * @param bufferOffsetBlocks the offset in blocks
     * @return the index of the block's first sample in {@link #blocks}
     */
    int blockOffset(int bufferOffsetBlocks) {
        return offsetIndex(blockRead, bufferOffsetBlocks) * BLOCK_SIZE;
    }

    /**
     * Returns the start of the power spectrum at an offset from the spectrum read position.
     *
     * @param bufferOffsetFfts the offset in spectra; positive offsets reach older spectra
     * @return the index of the spectrum's bin 0 in {@link #spectra}
     */
    int spectrumOffset(int bufferOffsetFfts) {
        return offsetIndex(spectrumRead, bufferOffsetFfts) * FFT_LENGTH_BY_2_PLUS_1;
    }

    /**
     * Returns the read position of the spectrum ring.
     *
     * @return the index of the spectrum aligned with the current capture block
     */
    int position() {
        return fftRead;
    }

    /**
     * Sums the most recent power spectra from the read position.
     *
     * @param numSpectra the number of spectra to sum
     * @param x2         receives the 65 sums
     */
    void spectralSum(int numSpectra, float[] x2) {
        Arrays.fill(x2, 0.f);
        var position = spectrumRead;
        for (var j = 0; j < numSpectra; ++j) {
            var base = position * FFT_LENGTH_BY_2_PLUS_1;
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                x2[k] += spectra[base + k];
            }
            position = incIndex(position);
        }
    }

    /**
     * Sums the most recent power spectra over two lengths.
     *
     * @param numSpectraShorter the number of spectra of the shorter sum
     * @param numSpectraLonger  the number of spectra of the longer sum, at least
     *                          {@code numSpectraShorter}
     * @param x2Shorter         receives the shorter sums
     * @param x2Longer          receives the longer sums
     */
    void spectralSums(int numSpectraShorter, int numSpectraLonger, float[] x2Shorter, float[] x2Longer) {
        Arrays.fill(x2Shorter, 0.f);
        var position = spectrumRead;
        var j = 0;
        for (; j < numSpectraShorter; ++j) {
            var base = position * FFT_LENGTH_BY_2_PLUS_1;
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                x2Shorter[k] += spectra[base + k];
            }
            position = incIndex(position);
        }
        System.arraycopy(x2Shorter, 0, x2Longer, 0, FFT_LENGTH_BY_2_PLUS_1);
        for (; j < numSpectraLonger; ++j) {
            var base = position * FFT_LENGTH_BY_2_PLUS_1;
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                x2Longer[k] += spectra[base + k];
            }
            position = incIndex(position);
        }
    }

    /**
     * Returns the number of spectra between the write and the read position of the spectrum
     * ring.
     *
     * @return the headroom, in {@code [1, SIZE]}
     */
    int headroom() {
        return fftWrite < fftRead ? fftRead - fftWrite : SIZE - fftWrite + fftRead;
    }
}
