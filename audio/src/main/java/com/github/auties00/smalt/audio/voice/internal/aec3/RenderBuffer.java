/* Ports webrtc-audio-processing 2.1 (modules/audio_processing/aec3/render_buffer.cc, block_buffer.cc,
   spectrum_buffer.cc, fft_buffer.cc). Copyright (c) 2011, The WebRTC project authors; BSD 3-Clause License. Modified
   for Smalt. */
package com.github.auties00.smalt.audio.voice.internal.aec3;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.BLOCK_SIZE;
import static com.github.auties00.smalt.audio.voice.internal.aec3.Aec3.FFT_LENGTH_BY_2_PLUS_1;

/**
 * Holds the render blocks, their spectra and their power spectra in rings with independent read
 * and write positions ({@code RenderBuffer} with its {@code BlockBuffer}, {@code SpectrumBuffer}
 * and {@code FftBuffer}).
 *
 * <p>Block slot {@code s} holds every band of every channel, band by band, 64 samples each:
 * band {@code b} of channel {@code c} starts at {@code ((s * bands + b) * channels + c) * 64}.
 * Spectrum slot {@code s} holds the 65 bins of the lowest band of every channel, channel {@code c}
 * starting at {@code (s * channels + c) * 65}; the spectrum rings run backwards, the block ring
 * forwards.
 */
final class RenderBuffer {
    /**
     * The number of slots of each ring.
     */
    static final int SIZE = (BLOCK_SIZE / Aec3.DOWN_SAMPLING_FACTOR
            * (Aec3.MATCHED_FILTER_ALIGNMENT_SHIFT_SIZE_SUB_BLOCKS * Aec3.NUM_FILTERS + Aec3.MATCHED_FILTER_WINDOW_SIZE_SUB_BLOCKS + 1))
            / (BLOCK_SIZE / Aec3.DOWN_SAMPLING_FACTOR) + Aec3.REFINED_LENGTH_BLOCKS + 1;

    /**
     * The number of bands of a block.
     */
    final int numBands;

    /**
     * The number of render channels.
     */
    final int numChannels;

    /**
     * The blocks.
     */
    final float[] blocks;

    /**
     * The slot of the next block to write.
     */
    int blockWrite;

    /**
     * The slot of the current block.
     */
    int blockRead;

    /**
     * The power spectra.
     */
    final float[] spectra;

    /**
     * The slot of the next power spectrum to write.
     */
    int spectrumWrite;

    /**
     * The slot of the current power spectrum.
     */
    int spectrumRead;

    /**
     * The real parts of the spectra.
     */
    final float[] fftRe;

    /**
     * The imaginary parts of the spectra.
     */
    final float[] fftIm;

    /**
     * The slot of the next spectrum to write.
     */
    int fftWrite;

    /**
     * The slot of the current spectrum.
     */
    int fftRead;

    /**
     * Whether the render signal was active since the last capture block.
     */
    boolean renderActivity;

    /**
     * Creates an empty buffer.
     *
     * @param numBands the number of bands of a block
     * @param numChannels the number of render channels
     */
    RenderBuffer(int numBands, int numChannels) {
        this.numBands = numBands;
        this.numChannels = numChannels;
        this.blocks = new float[SIZE * numBands * numChannels * BLOCK_SIZE];
        this.spectra = new float[SIZE * numChannels * FFT_LENGTH_BY_2_PLUS_1];
        this.fftRe = new float[SIZE * numChannels * FFT_LENGTH_BY_2_PLUS_1];
        this.fftIm = new float[SIZE * numChannels * FFT_LENGTH_BY_2_PLUS_1];
    }

    static int incIndex(int index) {
        return index < SIZE - 1 ? index + 1 : 0;
    }

    static int decIndex(int index) {
        return index > 0 ? index - 1 : SIZE - 1;
    }

    static int offsetIndex(int index, int offset) {
        return (SIZE + index + offset) % SIZE;
    }

    /**
     * Returns the index of a band of a channel of a block in {@link #blocks}.
     *
     * @param bufferOffsetBlocks the block relative to the current one
     * @param band the index of the frequency band
     * @param channel the index of the render channel
     * @return the index of the first of its 64 samples
     */
    int blockOffset(int bufferOffsetBlocks, int band, int channel) {
        return blockSlotOffset(offsetIndex(blockRead, bufferOffsetBlocks), band, channel);
    }

    /**
     * Returns the index of a band of a channel of a block slot in {@link #blocks}.
     *
     * @param slot the block slot
     * @param band the index of the frequency band
     * @param channel the index of the render channel
     * @return the index of the first of its 64 samples
     */
    int blockSlotOffset(int slot, int band, int channel) {
        return ((slot * numBands + band) * numChannels + channel) * BLOCK_SIZE;
    }

    /**
     * Returns the index of the power spectrum of a channel in {@link #spectra}.
     *
     * @param bufferOffsetFfts the spectrum relative to the current one
     * @param channel the index of the render channel
     * @return the index of the first of its 65 bins
     */
    int spectrumOffset(int bufferOffsetFfts, int channel) {
        return (offsetIndex(spectrumRead, bufferOffsetFfts) * numChannels + channel) * FFT_LENGTH_BY_2_PLUS_1;
    }

    /**
     * {@return the slot of the current spectrum, the position of the filters' first partition}
     */
    int position() {
        return fftRead;
    }

    /**
     * Sums the power spectra of every channel over the latest spectra.
     *
     * @param numSpectra the number of spectra
     * @param x2 receives the 65 sums
     */
    void spectralSum(int numSpectra, float[] x2) {
        Arrays.fill(x2, 0.f);
        var position = spectrumRead;
        for (var j = 0; j < numSpectra; ++j) {
            for (var ch = 0; ch < numChannels; ch++) {
                var base = (position * numChannels + ch) * FFT_LENGTH_BY_2_PLUS_1;
                for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                    x2[k] += spectra[base + k];
                }
            }
            position = incIndex(position);
        }
    }

    /**
     * Sums the power spectra of every channel over two numbers of latest spectra.
     *
     * @param numSpectraShorter the smaller number of spectra
     * @param numSpectraLonger the larger number of spectra
     * @param x2Shorter receives the sums over the smaller number
     * @param x2Longer receives the sums over the larger number
     */
    void spectralSums(int numSpectraShorter, int numSpectraLonger, float[] x2Shorter, float[] x2Longer) {
        Arrays.fill(x2Shorter, 0.f);
        var position = spectrumRead;
        var j = 0;
        for (; j < numSpectraShorter; ++j) {
            for (var ch = 0; ch < numChannels; ch++) {
                var base = (position * numChannels + ch) * FFT_LENGTH_BY_2_PLUS_1;
                for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                    x2Shorter[k] += spectra[base + k];
                }
            }
            position = incIndex(position);
        }
        System.arraycopy(x2Shorter, 0, x2Longer, 0, FFT_LENGTH_BY_2_PLUS_1);
        for (; j < numSpectraLonger; ++j) {
            for (var ch = 0; ch < numChannels; ch++) {
                var base = (position * numChannels + ch) * FFT_LENGTH_BY_2_PLUS_1;
                for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                    x2Longer[k] += spectra[base + k];
                }
            }
            position = incIndex(position);
        }
    }

    /**
     * Sums the power spectra of every render channel of a spectrum slot, or copies the only one.
     *
     * @param bufferOffsetFfts the spectrum relative to the current one
     * @param out receives the 65 bins
     */
    void channelSum(int bufferOffsetFfts, float[] out) {
        var base = offsetIndex(spectrumRead, bufferOffsetFfts) * numChannels * FFT_LENGTH_BY_2_PLUS_1;
        if (numChannels == 1) {
            System.arraycopy(spectra, base, out, 0, FFT_LENGTH_BY_2_PLUS_1);
            return;
        }
        Arrays.fill(out, 0.f);
        for (var ch = 0; ch < numChannels; ch++) {
            var o = base + ch * FFT_LENGTH_BY_2_PLUS_1;
            for (var k = 0; k < FFT_LENGTH_BY_2_PLUS_1; ++k) {
                out[k] += spectra[o + k];
            }
        }
    }

    int headroom() {
        return fftWrite < fftRead ? fftRead - fftWrite : SIZE - fftWrite + fftRead;
    }
}
