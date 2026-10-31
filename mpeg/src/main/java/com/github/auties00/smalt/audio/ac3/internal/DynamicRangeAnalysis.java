package com.github.auties00.smalt.audio.ac3.internal;

import com.github.auties00.smalt.audio.ac3.Ac3DynamicRangeProfile;

import java.util.Arrays;

/**
 * Computes the dynamic range control words of AC-3 and E-AC-3 frames from the input of a program
 * (ATSC A/52 sections 5.4.2.8, 5.4.3.20, 7.7.1 and 7.7.2): a dynamic range word {@code dynrng} per
 * block from a line mode profile and a heavy compression word {@code compr} per frame from an RF
 * mode profile.
 *
 * <p>The level of a block is the power of its full-bandwidth channels summed over the channels,
 * in dB relative to the power of a full-scale sine, shifted so that the declared dialogue level
 * falls at -31 dB. The line mode gain follows the profile's gain of each block's level at once when
 * it falls and with a time constant of {@link #RELEASE_SECONDS} when it rises, so that loud onsets
 * are cut without delay and the boost of quiet passages returns smoothly. The RF mode gain of a
 * frame is the profile's gain of the frame's loudest block, limited so that a decoder in RF mode,
 * which raises the output by 11 dB and normalizes the dialogue level to -20 dBFS, keeps the frame's
 * peak below full scale. Instances are not thread-safe.
 *
 * @implNote This implementation measures the level of each block without frequency weighting and
 * chooses the release time; neither is specified, and both change only the words, never their
 * validity.
 */
public final class DynamicRangeAnalysis {
    /**
     * The time constant of a rising line mode gain, in seconds.
     */
    static final double RELEASE_SECONDS = 1.0;

    /**
     * The dialogue level a decoder in RF mode normalizes to, in dBFS: the -31 dBFS of line mode
     * raised by the 11 dB RF mode adds.
     */
    private static final double RF_DIALOGUE = -20;

    /**
     * The line mode profile.
     */
    private final Ac3DynamicRangeProfile line;

    /**
     * The RF mode profile.
     */
    private final Ac3DynamicRangeProfile rf;

    /**
     * The shift from a measured level to the level the profiles take: {@code -31} minus the declared
     * dialogue level.
     */
    private final double shift;

    /**
     * The declared dialogue level in dBFS.
     */
    private final int dialogueLevel;

    /**
     * The fraction of the remaining rise a line mode gain makes per block.
     */
    private final double release;

    /**
     * The line mode gain of the last block, in dB.
     */
    private double gain;

    /**
     * Whether a block was analyzed since the start or the last reset.
     */
    private boolean started;

    /**
     * The dynamic range word of each block of the frame analyzed last, or {@link FrameEncoder#NO_WORD}.
     */
    private final int[] words;

    /**
     * The heavy compression word of the frame analyzed last, or {@link FrameEncoder#NO_WORD}.
     */
    private int compression = FrameEncoder.NO_WORD;

    /**
     * Creates the analysis of a program.
     *
     * @param line the line mode profile
     * @param rf the RF mode profile
     * @param dialogueLevel the declared dialogue level in dBFS, from -31 to -1
     * @param sampleRate the sampling rate
     * @param blocks the blocks per frame
     */
    public DynamicRangeAnalysis(Ac3DynamicRangeProfile line, Ac3DynamicRangeProfile rf, int dialogueLevel,
                                int sampleRate, int blocks) {
        this.line = line;
        this.rf = rf;
        this.dialogueLevel = dialogueLevel;
        this.shift = -31 - dialogueLevel;
        this.release = 1 - StrictMath.exp(-256.0 / sampleRate / RELEASE_SECONDS);
        this.words = new int[blocks];
        Arrays.fill(words, FrameEncoder.NO_WORD);
    }

    /**
     * {@return whether the analysis computes any word}
     */
    public boolean active() {
        return line != Ac3DynamicRangeProfile.NONE || rf != Ac3DynamicRangeProfile.NONE;
    }

    /**
     * Analyzes the input of a frame.
     *
     * @param planes the samples of each channel
     * @param channels the indexes of the full-bandwidth channels in {@code planes}
     * @param offset the index of the frame's first sample in each plane
     */
    public void analyze(float[][] planes, int[] channels, int offset) {
        var loudest = Double.NEGATIVE_INFINITY;
        var peak = 0.0f;
        for (var b = 0; b < words.length; b++) {
            var power = 0.0;
            for (var ch : channels) {
                var plane = planes[ch];
                for (var n = offset + 256 * b; n < offset + 256 * b + 256; n++) {
                    var sample = plane[n];
                    power += (double) sample * sample;
                    peak = Math.max(peak, Math.abs(sample));
                }
            }
            // a full-scale sine has a mean square of one half
            var level = 10 * StrictMath.log10(Math.max(power / 256 * 2, 1e-12)) + shift;
            loudest = Math.max(loudest, level);
            if (line == Ac3DynamicRangeProfile.NONE) {
                words[b] = FrameEncoder.NO_WORD;
                continue;
            }
            var target = line.gain(level);
            gain = !started || target < gain ? target : gain + (target - gain) * release;
            started = true;
            words[b] = dynamicRangeWord(gain);
        }
        if (rf == Ac3DynamicRangeProfile.NONE) {
            compression = FrameEncoder.NO_WORD;
            return;
        }
        var target = rf.gain(loudest);
        if (peak > 0) {
            var headroom = -20 * StrictMath.log10(peak) - (RF_DIALOGUE - dialogueLevel);
            target = Math.min(target, headroom);
        }
        compression = compressionWord(target);
    }

    /**
     * {@return the dynamic range words of the blocks of the frame analyzed last}
     */
    public int[] words() {
        return words;
    }

    /**
     * {@return the heavy compression word of the frame analyzed last, or {@link FrameEncoder#NO_WORD}}
     */
    public int compression() {
        return compression;
    }

    /**
     * Returns the dynamic range word closest to a gain at or below it: the gain of a word with
     * exponent {@code X} (a signed 3-bit value) and mantissa {@code Y} is
     * {@code (1 + Y / 32) * 2^X}, from -24.08 to +23.95 dB.
     *
     * @param decibels the gain in dB
     * @return the signed 8-bit word
     */
    static int dynamicRangeWord(double decibels) {
        var linear = StrictMath.pow(10, decibels / 20);
        var exponent = (int) Math.floor(StrictMath.log(linear) / StrictMath.log(2));
        exponent = Math.clamp(exponent, -4, 3);
        var mantissa = (int) Math.floor((linear / Math.scalb(1.0, exponent) - 1) * 32 + 1e-9);
        mantissa = Math.clamp(mantissa, 0, 31);
        return (byte) ((exponent & 7) << 5 | mantissa);
    }

    /**
     * Returns the heavy compression word closest to a gain at or below it: the gain of a word with
     * exponent {@code X} (a signed 4-bit value) and mantissa {@code Y} is
     * {@code (1 + Y / 16) * 2^X}, from -48.16 to +47.89 dB.
     *
     * @param decibels the gain in dB
     * @return the unsigned 8-bit word
     */
    static int compressionWord(double decibels) {
        var linear = StrictMath.pow(10, decibels / 20);
        var exponent = (int) Math.floor(StrictMath.log(linear) / StrictMath.log(2));
        exponent = Math.clamp(exponent, -8, 7);
        var mantissa = (int) Math.floor((linear / Math.scalb(1.0, exponent) - 1) * 16 + 1e-9);
        mantissa = Math.clamp(mantissa, 0, 15);
        return (exponent & 15) << 4 | mantissa;
    }
}
