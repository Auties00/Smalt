/* Ports libxaac (decoder/ixheaacd_usac_ec.c: ixheaacd_usac_ec_state, ixheaacd_usac_lpc_ec_state,
   ixheaacd_usac_ec_get_win_seq, ixheaacd_usac_flip_spec_sign and ixheaacd_usac_lpc_ec; decoder/ixheaacd_ec_rom.c:
   ia_ec_fade_factors and ixheaacd_exc_fade_fac; decoder/ixheaacd_ec_defines.h), commit 2fbadd57.
   Copyright 2023 The Android Open Source Project; Apache License 2.0. Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import java.util.Arrays;

/**
 * Holds the error concealment state of one USAC core channel: whether the last frames were received, the fade
 * position and the spectrum of the last frequency domain frame.
 *
 * <p>Instances are not thread-safe.
 */
final class UsacConcealment {
    /**
     * The state of a received frame.
     */
    static final int OKAY = 0;

    /**
     * The state of a frame concealed by repeating the last spectrum or excitation.
     */
    static final int CONCEAL = 1;

    /**
     * The state of a received frame that follows a loss.
     */
    static final int FADE = 2;

    /**
     * The state of a frame concealed with silence.
     */
    static final int MUTE = 3;

    /**
     * The number of fade steps from full level to silence.
     */
    static final int FADE_FRAMES = 8;

    /**
     * The gain of each fade step, indexed by {@link #fadeIndex}.
     */
    private static final float[] FADE_GAINS = {1.0f, 0.875f, 0.75f, 0.625f, 0.5f, 0.375f, 0.25f, 0.125f, 0.0f};

    /**
     * The attenuation of the repeated TCX excitation by the number of lost divisions, from one to eight.
     */
    static final float[] EXCITATION_FADE = {0.8f, 0.7f, 0.6f, 0.5f, 0.4f, 0.3f, 0.2f, 0.1f};

    /**
     * The amplitude ratio of a long-window line to a short-window line of the same signal.
     */
    private static final float SHORT_TO_LONG = (float) Math.sqrt(8);

    /**
     * The weight of the initial LSF vector in the long-term mean of the first extrapolated vector.
     */
    private static final float BETA = 0.25f;

    /**
     * The weight of the adaptive mean in the long-term mean of the first extrapolated vector.
     */
    private static final float ONE_BETA = 0.75f;

    /**
     * The weight of the previous vector in each extrapolated LSF vector.
     */
    private static final float BFI_FACTOR = 0.90f;

    /**
     * The weight of the long-term mean in each extrapolated LSF vector, and the step by which the mean moves toward
     * the initial vector per further vector.
     */
    private static final float ONE_BFI_FACTOR = 0.10f;

    /**
     * The core frame length, 768 or 1024.
     */
    private final int frameLength;

    /**
     * The final spectrum of the last received frequency domain frame.
     */
    final float[] spectrum;

    /**
     * The window sequence of {@link #spectrum}.
     */
    int sequence = FdChannel.ONLY_LONG;

    /**
     * The window shape of {@link #spectrum}.
     */
    int shape = 1;

    /**
     * Whether the frame before the last one was received.
     */
    private boolean previousOk = true;

    /**
     * Whether the last frame was received.
     */
    private boolean lastOk = true;

    /**
     * The fade position, from 0 (full level) to {@link #FADE_FRAMES} (silence).
     */
    int fadeIndex;

    /**
     * The state of the current frame.
     */
    int state = OKAY;

    /**
     * Constructs the state of a channel.
     *
     * @param frameLength the core frame length, 768 or 1024
     */
    UsacConcealment(int frameLength) {
        this.frameLength = frameLength;
        this.spectrum = new float[frameLength];
    }

    /**
     * {@return whether the last frame was received}
     */
    boolean lastOk() {
        return lastOk;
    }

    /**
     * Advances the state machine of a frequency domain frame ({@code ixheaacd_usac_ec_state}).
     *
     * @param ok whether the frame was received
     */
    void advanceFd(boolean ok) {
        var value = (previousOk ? 4 : 0) + (lastOk ? 2 : 0) + (ok ? 1 : 0);
        switch (value) {
            case 0, 4 -> {
                if (fadeIndex < FADE_FRAMES) {
                    fadeIndex++;
                }
                state = CONCEAL;
            }
            case 1, 2 -> {
                if (fadeIndex > 0) {
                    fadeIndex--;
                }
                state = FADE;
            }
            default -> {
                if (fadeIndex > 0) {
                    fadeIndex--;
                }
                state = OKAY;
            }
        }
        if (fadeIndex == FADE_FRAMES) {
            state = MUTE;
        }
        shift(ok);
    }

    /**
     * Advances the state machine of a linear prediction domain frame ({@code ixheaacd_usac_lpc_ec_state}).
     *
     * @param ok whether the frame was received
     */
    void advanceLpd(boolean ok) {
        if (ok) {
            if (fadeIndex > 0) {
                fadeIndex--;
            }
            state = OKAY;
        } else {
            if (fadeIndex < FADE_FRAMES) {
                fadeIndex++;
            }
            state = CONCEAL;
        }
        if (fadeIndex >= FADE_FRAMES) {
            fadeIndex = FADE_FRAMES;
            state = MUTE;
        }
        shift(ok);
    }

    private void shift(boolean ok) {
        previousOk = lastOk;
        lastOk = ok;
    }

    /**
     * {@return whether the frame before the current one was lost}, which makes a repeated spectrum take random signs
     */
    boolean flipSigns() {
        return !previousOk;
    }

    /**
     * Keeps the final spectrum of a received frequency domain frame.
     *
     * @param source the spectrum
     * @param windowSequence its window sequence
     * @param windowShape its window shape
     */
    void save(float[] source, int windowSequence, int windowShape) {
        System.arraycopy(source, 0, spectrum, 0, frameLength);
        sequence = windowSequence;
        shape = windowShape;
    }

    /**
     * Writes the spectrum of a concealed frequency domain frame and returns its window sequence: the last spectrum,
     * its short windows interleaved into one long window, with random signs after the first lost frame, or silence
     * once muted ({@code ixheaacd_usac_ec_get_win_seq}, {@code ixheaacd_usac_flip_spec_sign}).
     *
     * @param target receives the spectrum
     * @param lastSequence the window sequence of the previous frame
     * @param seed the random state of the signs, updated
     * @return the window sequence of the concealed frame
     */
    int concealSpectrum(float[] target, int lastSequence, int[] seed) {
        var windowSequence = lastSequence == FdChannel.LONG_START || lastSequence == FdChannel.EIGHT_SHORT
                || lastSequence == FdChannel.STOP_START ? FdChannel.LONG_STOP : FdChannel.ONLY_LONG;
        if (state == MUTE) {
            Arrays.fill(target, 0, frameLength, 0f);
            return windowSequence;
        }
        if (sequence == FdChannel.EIGHT_SHORT) {
            var windowLength = frameLength / 8;
            for (var k = 0; k < frameLength; k++) {
                target[k] = SHORT_TO_LONG * spectrum[(k & 7) * windowLength + (k >> 3)];
            }
            System.arraycopy(target, 0, spectrum, 0, frameLength);
            sequence = windowSequence;
        } else {
            System.arraycopy(spectrum, 0, target, 0, frameLength);
        }
        if (flipSigns()) {
            flip(target, frameLength, seed);
        }
        return windowSequence;
    }

    /**
     * Gives each value a random sign ({@code ixheaacd_usac_flip_spec_sign}).
     *
     * @param values the values, changed in place
     * @param count the number of values
     * @param seed the random state, updated
     */
    static void flip(float[] values, int count, int[] seed) {
        var s = seed[0];
        for (var i = 0; i < count; i++) {
            s = s * 69069 + 5;
            if ((s & 0x10000) != 0) {
                values[i] = -values[i];
            }
        }
        seed[0] = s;
    }

    /**
     * Scales a block of core output by the current fade gain; silences it once muted.
     *
     * @param output the samples, changed in place
     * @param count the number of samples
     */
    void fade(float[] output, int count) {
        if (fadeIndex == 0) {
            return;
        }
        var gain = FADE_GAINS[fadeIndex];
        for (var i = 0; i < count; i++) {
            output[i] *= gain;
        }
    }

    /**
     * Extrapolates the five LSF vectors of a lost linear prediction domain frame from the last received one toward
     * the long-term mean ({@code ixheaacd_usac_lpc_ec}).
     *
     * @param lsf receives the vectors, {@code 5 * 16} values
     * @param last the fourth LSF vector of the last received frame, replaced by the last extrapolated one
     * @param adaptiveMean the mean of the last received frame's LSF vectors
     * @param firstLpd whether the last received frame was the first of a run of linear prediction domain frames
     */
    static void extrapolateLsf(float[] lsf, float[] last, float[] adaptiveMean, boolean firstLpd) {
        var order = LpdState.ORDER;
        var initial = LpdTables.LSF_INIT;
        if (firstLpd) {
            System.arraycopy(initial, 0, lsf, 0, order);
            System.arraycopy(initial, 0, last, 0, order);
        } else {
            System.arraycopy(last, 0, lsf, 0, order);
        }
        for (var i = 0; i < order; i++) {
            var mean = BETA * initial[i] + ONE_BETA * adaptiveMean[i];
            lsf[order + i] = BFI_FACTOR * last[i] + ONE_BFI_FACTOR * mean;
        }
        for (var j = 2; j <= 4; j++) {
            for (var i = 0; i < order; i++) {
                var mean = (BETA + j * ONE_BFI_FACTOR) * initial[i] + (ONE_BETA - j * ONE_BFI_FACTOR) * adaptiveMean[i];
                lsf[j * order + i] = BFI_FACTOR * lsf[(j - 1) * order + i] + ONE_BFI_FACTOR * mean;
            }
        }
        System.arraycopy(lsf, 4 * order, last, 0, order);
    }
}
