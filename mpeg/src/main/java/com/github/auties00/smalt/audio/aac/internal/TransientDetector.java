/* Ports vo-aacenc 0.1.3 (aacenc/src/block_switch.c). Copyright 2003-2010 VisualOn, Inc.; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal;

/**
 * Detects attacks that call for short windows, one analysis block of a frame's length at a time.
 *
 * <p>The block analyzed for a window is the stretch whose eight segments, each a short window's
 * length (128 or 120 samples), are the
 * centers of that window's eight short windows. Each segment is high-pass filtered and its
 * energy compared with a recursively smoothed energy of the preceding segments: a segment whose
 * energy exceeds the smoothed energy by the attack ratio marks an attack, provided the block's
 * peak segment energy clears a floor.
 *
 * @implNote This implementation follows the block switching of 3GPP TS 26.403: a first-order
 * high-pass
 * {@code y[n] = 0.7548 (x[n] - x[n-1]) + 0.5095 y[n-1]} on 16-bit-scaled samples, a smoothing
 * factor of 0.3, attack ratios of 10 (above 24 kbps per channel) or 18 (below), and a floor of
 * 1e6 per segment.
 */
public final class TransientDetector {
    /**
     * The number of segments per analysis block.
     */
    public static final int SEGMENTS = 8;

    /**
     * The smoothing weight of the newest segment energy.
     */
    private static final float SMOOTHING = 0.3f;

    /**
     * The smallest peak segment energy of a block with an attack.
     */
    private static final float MIN_ATTACK_ENERGY = 1e6f;

    /**
     * The inverse attack ratio.
     */
    private float inverseRatio;

    /**
     * The number of samples per segment.
     */
    private final int segmentLength;

    /**
     * The previous input sample of the high-pass filter.
     */
    private float lastInput;

    /**
     * The previous output sample of the high-pass filter.
     */
    private float lastOutput;

    /**
     * The smoothed segment energy.
     */
    private float smoothed;

    /**
     * The filtered energy of the last segment analyzed.
     */
    private float lastEnergy;

    /**
     * The filtered energy of each segment of the last block.
     */
    private final float[] energies = new float[SEGMENTS];

    /**
     * Whether the last block holds an attack.
     */
    private boolean attack;

    /**
     * The segment of the last attack in the last block.
     */
    private int attackIndex;

    /**
     * The largest segment energy of the last block.
     */
    private float peakEnergy;

    /**
     * Creates a detector.
     *
     * @param channelBitrate the bitrate per channel in bits per second
     * @param frameLength the frame length, 1024 or 960
     */
    public TransientDetector(int channelBitrate, int frameLength) {
        this.segmentLength = frameLength / SEGMENTS;
        retune(channelBitrate);
    }

    /**
     * Adapts the attack threshold to a new bitrate, keeping the filter and energy history.
     *
     * @param channelBitrate the bitrate per channel in bits per second
     */
    public void retune(int channelBitrate) {
        this.inverseRatio = channelBitrate > 24000 ? 0.1f : 0.056f;
    }

    /**
     * Analyzes the next block.
     *
     * @param samples the input samples in {@code [-1, 1]}
     * @param offset the index of the block's first sample
     */
    public void analyze(float[] samples, int offset) {
        var x1 = lastInput;
        var y1 = lastOutput;
        for (var segment = 0; segment < SEGMENTS; segment++) {
            var energy = 0.0f;
            var base = offset + segment * segmentLength;
            for (var i = 0; i < segmentLength; i++) {
                var x = samples[base + i] * 32768f;
                var y = 0.7548f * (x - x1) + 0.5095f * y1;
                x1 = x;
                y1 = y;
                energy += y * y;
            }
            energies[segment] = energy;
        }
        lastInput = x1;
        lastOutput = y1;
        attack = false;
        peakEnergy = 0;
        var previous = lastEnergy;
        for (var segment = 0; segment < SEGMENTS; segment++) {
            smoothed = (1 - SMOOTHING) * smoothed + SMOOTHING * previous;
            if (energies[segment] * inverseRatio > smoothed) {
                attack = true;
                attackIndex = segment;
            }
            previous = energies[segment];
            peakEnergy = Math.max(peakEnergy, previous);
        }
        lastEnergy = previous;
        if (peakEnergy < MIN_ATTACK_ENERGY) {
            attack = false;
        }
    }

    /**
     * Returns whether the last analyzed block holds an attack.
     *
     * @return {@code true} on an attack
     */
    public boolean attack() {
        return attack;
    }

    /**
     * Returns the segment of the last attack of the last analyzed block.
     *
     * @return the segment index, meaningful only when {@link #attack()} holds
     */
    public int attackIndex() {
        return attackIndex;
    }

    /**
     * Returns the largest segment energy of the last analyzed block.
     *
     * @return the energy
     */
    public float peakEnergy() {
        return peakEnergy;
    }
}
