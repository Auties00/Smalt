package com.github.auties00.smalt.audio.voice.internal.agc;

import com.github.auties00.smalt.audio.voice.internal.FixedPointMath;

/**
 * Estimates the speech activity of one signal for the gain controller from the short- and
 * long-term statistics of its 4 kHz energy.
 *
 * <p>Each 10 ms frame is decimated to 4 kHz, high-pass filtered and reduced to one energy in
 * dB (Q10); the detector tracks the short- and long-term mean and standard deviation of that
 * energy and returns the log likelihood ratio of speech against background, clamped to
 * {@code [-2048, 2048]} in Q10.
 *
 * @implNote This implementation ports {@code AgcVad}, {@code WebRtcAgc_InitVad} and
 * {@code WebRtcAgc_ProcessVad} of WebRTC's legacy gain controller
 * ({@code modules/audio_processing/agc/legacy/digital_agc.cc}, webrtc-audio-processing 2.1),
 * keeping its 16-bit fields and their wrap-around.
 */
final class AgcVad {
    /**
     * The number of frames after which the long-term statistics stop speeding up their
     * adaptation ({@code kAvgDecayTime}).
     */
    private static final int AVG_DECAY_TIME = 250;

    /**
     * The states of the 8 kHz to 4 kHz decimator.
     */
    private final int[] downState;

    /**
     * The decimated samples of one millisecond at 8 kHz.
     */
    private final short[] buf1;

    /**
     * The decimated samples of one millisecond at 4 kHz.
     */
    private final short[] buf2;

    /**
     * The state of the high-pass filter.
     */
    private short hpState;

    /**
     * The number of updates, saturating at {@link #AVG_DECAY_TIME}.
     */
    short counter;

    /**
     * The log likelihood ratio of speech, in Q10.
     */
    short logRatio;

    /**
     * The long-term mean energy, in Q10.
     */
    private short meanLongTerm;

    /**
     * The long-term energy variance, in Q8.
     */
    private int varianceLongTerm;

    /**
     * The long-term standard deviation of the energy, in Q10.
     */
    short stdLongTerm;

    /**
     * The short-term mean energy, in Q10.
     */
    private short meanShortTerm;

    /**
     * The short-term energy variance, in Q8.
     */
    private int varianceShortTerm;

    /**
     * The short-term standard deviation of the energy, in Q10.
     */
    short stdShortTerm;

    /**
     * Creates a detector in its initial state.
     */
    AgcVad() {
        this.downState = new int[8];
        this.buf1 = new short[8];
        this.buf2 = new short[4];
        init();
    }

    /**
     * Resets the detector ({@code WebRtcAgc_InitVad}).
     */
    void init() {
        hpState = 0;
        logRatio = 0;
        meanLongTerm = (short) (15 << 10);
        varianceLongTerm = 500 << 8;
        stdLongTerm = 0;
        meanShortTerm = (short) (15 << 10);
        varianceShortTerm = 500 << 8;
        stdShortTerm = 0;
        counter = 3;
        java.util.Arrays.fill(downState, 0);
    }

    /**
     * Updates the detector with one 10 ms frame ({@code WebRtcAgc_ProcessVad}).
     *
     * @param in        the samples
     * @param offset    the index of the first sample
     * @param nrSamples the number of samples, {@code 160} at 16 kHz or {@code 80} at 8 kHz
     * @return the log likelihood ratio of speech, in Q10
     */
    short process(short[] in, int offset, int nrSamples) {
        var nrg = 0;
        var hp = hpState;
        var position = offset;
        for (var subframe = 0; subframe < 10; subframe++) {
            if (nrSamples == 160) {
                for (var k = 0; k < 8; k++) {
                    var tmp32 = in[position + 2 * k] + in[position + 2 * k + 1];
                    tmp32 >>= 1;
                    buf1[k] = (short) tmp32;
                }
                position += 16;
                FixedPointMath.downsampleBy2(buf1, 0, 8, buf2, 0, downState);
            } else {
                FixedPointMath.downsampleBy2(in, position, 8, buf2, 0, downState);
                position += 8;
            }
            for (var k = 0; k < 4; k++) {
                var out = buf2[k] + hp;
                var tmp32 = 600 * out;
                hp = (short) ((tmp32 >> 10) - buf2[k]);
                nrg += out * (out / (1 << 6));
                nrg += out * (out % (1 << 6)) / (1 << 6);
            }
        }
        hpState = hp;

        int zeros;
        if ((0xFFFF0000 & nrg) == 0) {
            zeros = 16;
        } else {
            zeros = 0;
        }
        if ((0xFF000000 & (nrg << zeros)) == 0) {
            zeros += 8;
        }
        if ((0xF0000000 & (nrg << zeros)) == 0) {
            zeros += 4;
        }
        if ((0xC0000000 & (nrg << zeros)) == 0) {
            zeros += 2;
        }
        if ((0x80000000 & (nrg << zeros)) == 0) {
            zeros += 1;
        }
        var dB = (short) ((15 - zeros) * (1 << 11));

        if (counter < AVG_DECAY_TIME) {
            counter++;
        }

        var tmp32 = meanShortTerm * 15 + dB;
        meanShortTerm = (short) (tmp32 >> 4);
        tmp32 = (dB * dB) >> 12;
        tmp32 += varianceShortTerm * 15;
        varianceShortTerm = tmp32 / 16;
        tmp32 = meanShortTerm * meanShortTerm;
        tmp32 = (varianceShortTerm << 12) - tmp32;
        stdShortTerm = (short) FixedPointMath.sqrt(tmp32);

        tmp32 = meanLongTerm * counter + dB;
        meanLongTerm = FixedPointMath.divW32W16ResW16(tmp32, FixedPointMath.addSatW16(counter, 1));
        tmp32 = (dB * dB) >> 12;
        tmp32 += varianceLongTerm * counter;
        varianceLongTerm = FixedPointMath.divW32W16(tmp32, FixedPointMath.addSatW16(counter, 1));
        tmp32 = meanLongTerm * meanLongTerm;
        tmp32 = (varianceLongTerm << 12) - tmp32;
        stdLongTerm = (short) FixedPointMath.sqrt(tmp32);

        var tmp16 = 3 << 12;
        tmp32 = tmp16 * (short) (dB - meanLongTerm);
        tmp32 = FixedPointMath.divW32W16(tmp32, stdLongTerm);
        var tmpU16 = 13 << 12;
        var tmp32b = logRatio * tmpU16;
        long tmp64 = tmp32;
        tmp64 += tmp32b >> 10;
        tmp64 >>= 6;
        if (tmp64 > 2048) {
            tmp64 = 2048;
        } else if (tmp64 < -2048) {
            tmp64 = -2048;
        }
        logRatio = (short) tmp64;
        return logRatio;
    }
}
