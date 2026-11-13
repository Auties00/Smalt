/* Ports the comfort noise constants, the smoothing restart decision and the gain normalization of the random pulse
   vector of the CELP decoder of the ISO/IEC 14496-3 reference software as distributed with the ISO/IEC 23003-3
   reference software (nec_cng_module.c of src_lpc), developed by Hironori Ito (NEC Corporation). ISO/IEC gives users
   of the MPEG-2 AAC/MPEG-4 Audio standards free license to this software module or modifications thereof for use in
   hardware or software products claiming conformance to the MPEG-2 AAC/MPEG-4 Audio standards. Copyright (c) 1996.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.celp.internal;

import java.util.Arrays;

/**
 * The comfort noise generator of the CELP silence compression tool (ISO/IEC 14496-3 subclause 3.5.9.3.3): the LSP
 * and RMS smoothers and the excitation of non-active frames, a random multi-pulse or regular pulse vector plus
 * Gaussian noise.
 *
 * <p>The levels follow the reference decoder of the conformance streams of ISO/IEC 14496-26 rather than the text of
 * subclause 3.5.9.3.3, which gives them as energies: the excitation RMS is the decoded RMS times the square root of
 * the product of {@code 1 - k^2} over the reflection coefficients of the synthesis filter, times {@code alpha_s};
 * frames without SID information derive it from the decoded RMS of the last SID frame through their own filters;
 * the random regular pulse vector is scaled by the
 * expected RMS of its pulses rather than by its own energy, and its ternary digits map to -1, 0 and +1 as the text
 * says; the noise gain is that of the text divided by {@code alpha}.
 *
 * <p>Instances are not thread-safe.
 */
final class ComfortNoise {
    /**
     * The initial seed of the random sequence.
     */
    private static final short SEED = 21845;

    /**
     * The scaling factor of the excitation RMS, {@code alpha_s}.
     */
    private static final double RMS_SCALE = 0.8;

    /**
     * The share of the multi-pulse or regular pulse vector in the excitation, {@code alpha}: 0.6 rounded to 13
     * fractional bits, as the reference decoder holds it.
     */
    private static final double ALPHA = 4915 / 8192.0;

    /**
     * The pulses of the random multi-pulse vector and the samples they span: the reference decoder normalizes the
     * random vector by the energy of that many unit pulses in a subframe, the count rounded down, even where the
     * 60-sample subframes of {@code MPE_Configuration} 27 hold only one vector of ten pulses.
     */
    private static final int CNG_PULSES = 10;

    private static final int CNG_PULSE_SPAN = 40;

    /**
     * The amplitude of the pulses of the random regular pulse vector per {@code RPE_Configuration}: the values of
     * the reference decoder, which round those of Table 3.106 to 13 fractional bits.
     */
    private static final double[] RPE_SCALE = {14188 / 8192.0, 14188 / 8192.0, 11217 / 8192.0, 10033 / 8192.0};

    /**
     * The LPC order.
     */
    private final int order;

    /**
     * The samples of a subframe.
     */
    private final int subframeSize;

    /**
     * The subframes of a frame.
     */
    private final int subframes;

    /**
     * The structure of the regular pulse coder, or {@code null} for the multi-pulse coder.
     */
    private final CelpLayout rpe;

    /**
     * The scale of a random regular pulse vector.
     */
    private final double rpeScale;

    /**
     * The amplitudes of the pulses of a random regular pulse vector.
     */
    private final int[] rpeAmplitudes;

    /**
     * The width of the random {@code shape_index} of the regular pulse coder.
     */
    private final int rpeBits;

    /**
     * The gain of the random regular pulse vector per unit of smoothed excitation RMS: {@code alpha} over the RMS of
     * {@link #CNG_PULSES} unit pulses per {@link #CNG_PULSE_SPAN} samples, whose count in a subframe the reference
     * decoder rounds down (7 in the 30-sample subframes of {@code RPE_Configuration} 2, against 7.5).
     */
    private final double rpeGain;

    /**
     * The pulse table of the random multi-pulse vector: 10 pulses in 40 samples.
     */
    private final PulseTable pulses = PulseTable.of(CNG_PULSE_SPAN, CNG_PULSES, 20);

    /**
     * The state of the random sequence.
     */
    private short seed = SEED;

    /**
     * The smoothed LSPs, {@code lsp_current_sm}.
     */
    final double[] lspSmoothed;

    /**
     * The LSPs of the last SID frame, {@code lsp_current_sid}.
     */
    private final double[] lspSid;

    /**
     * The smoothed excitation RMS, {@code norm_sm}.
     */
    private double normSmoothed;

    /**
     * The decoded speech RMS of the last subframe of the last SID frame, from which the frames without SID
     * information derive their excitation RMS ({@code norm_sid}) through their own synthesis filters.
     */
    private double rmsSid;

    /**
     * The decoded speech RMS of the last subframe of the current frame.
     */
    private double rmsLast;

    /**
     * Whether the smoothed excitation RMS restarts from the RMS of each subframe of the current frame.
     */
    private boolean restart;

    /**
     * Whether the previous frame was non-active.
     */
    private boolean silent;

    /**
     * Whether the current frame is the first of its non-active period.
     */
    private boolean firstFrame;

    /**
     * The SID frames after the first of a period that may restart the smoothed RMS, plus one: the SID frames in the
     * first 40 milliseconds, with the frame length rounded down to tens of milliseconds as the reference decoder does.
     */
    private final int restartLength;

    /**
     * The position in the restart window; it survives a period that starts without a SID frame.
     */
    private int restartCount;

    /**
     * Whether an active or a SID frame was decoded since the generator was created: the LSPs of the first SID frame
     * of a stream replace the smoothed ones rather than being smoothed in.
     */
    private boolean lspDecoded;

    /**
     * The random multi-pulse or regular pulse vector and the Gaussian noise of a subframe.
     */
    private final double[] pulseVector;

    private final double[] noise;

    /**
     * The random multi-pulse vector of the second half of an 80-sample subframe.
     */
    private final double[] secondVector = new double[CNG_PULSE_SPAN];

    /**
     * Creates a generator.
     *
     * @param order the LPC order
     * @param subframeSize the samples of a subframe
     * @param subframes the subframes of a frame
     * @param sampleRate the sampling rate of the generated noise
     * @param rpe the structure of the regular pulse coder, or {@code null} for the multi-pulse coder
     * @param rpeConfiguration {@code RPE_Configuration}, for the regular pulse coder
     * @param lspRange the value of an LSP at the Nyquist frequency: 1 for normalized LSPs, pi for LSPs in radians
     */
    ComfortNoise(int order, int subframeSize, int subframes, int sampleRate, CelpLayout rpe, int rpeConfiguration,
                 double lspRange) {
        this.order = order;
        this.subframeSize = subframeSize;
        this.subframes = subframes;
        this.rpe = rpe;
        this.rpeScale = rpe == null ? 0 : RPE_SCALE[rpeConfiguration];
        this.rpeAmplitudes = new int[rpe == null ? 0 : rpe.rpePulses];
        this.rpeBits = rpe == null ? 0 : rpe.positionBits;
        this.rpeGain = rpe == null ? 0
                : ALPHA * Math.sqrt((double) subframeSize / (CNG_PULSES * subframeSize / CNG_PULSE_SPAN));
        this.lspSmoothed = new double[order];
        this.lspSid = new double[order];
        this.restartLength = 4 / Math.max(subframes * subframeSize / (sampleRate / 100), 1);
        for (var i = 0; i < order; i++) {
            lspSid[i] = lspRange * (i + 1) / (order + 1);
        }
        this.pulseVector = new double[subframeSize];
        this.noise = new double[subframeSize];
    }

    /**
     * Replaces the LSPs of the last SID frame that the frames without SID information smooth toward, after
     * {@link #startFrame(int, double[], double[])} smoothed with the decoded ones; the wideband coder holds them
     * stabilized while it smooths with them unstabilized.
     *
     * @param lsp the LSPs
     */
    void holdSid(double[] lsp) {
        System.arraycopy(lsp, 0, lspSid, 0, order);
    }

    /**
     * Records that an active frame is decoded, which ends a non-active period.
     */
    void activeFrame() {
        lspDecoded = true;
        silent = false;
    }

    /**
     * Starts a non-active frame: begins a non-active period after an active frame, and updates the smoothed LSPs.
     * The first SID frame of a stream without an earlier active frame sets the smoothed LSPs to its own, whatever
     * frames without SID information came before it.
     *
     * @param txFlag {@code TX_flag} of the frame, 0, 2 or 3
     * @param activeLsp the LSPs of the end of the previous active period
     * @param decoded the LSPs decoded from the SID frame of {@code TX_flag} 2, or {@code null}
     */
    void startFrame(int txFlag, double[] activeLsp, double[] decoded) {
        firstFrame = !silent;
        if (firstFrame) {
            silent = true;
            seed = SEED;
            System.arraycopy(activeLsp, 0, lspSmoothed, 0, order);
        }
        if (txFlag == 2 && decoded != null) {
            System.arraycopy(decoded, 0, lspSid, 0, order);
            if (lspDecoded) {
                for (var i = 0; i < order; i++) {
                    lspSmoothed[i] = 0.875 * lspSmoothed[i] + 0.125 * decoded[i];
                }
            } else {
                System.arraycopy(decoded, 0, lspSmoothed, 0, order);
                lspDecoded = true;
            }
        } else {
            for (var i = 0; i < order; i++) {
                lspSmoothed[i] = 0.875 * lspSmoothed[i] + 0.125 * lspSid[i];
            }
        }
    }

    /**
     * Decides whether the smoothed excitation RMS restarts from the RMS of each subframe of the current frame: in a
     * SID frame that opens a non-active period, and in the later SID frames of the restart window whose decoded RMS
     * differs by at least 6 dB from that of the last SID frame. A frame without SID information never restarts it.
     *
     * @param txFlag {@code TX_flag} of the frame
     * @param lastRms the decoded RMS of the last subframe of the frame
     */
    void prepareLevel(int txFlag, double lastRms) {
        if (txFlag < 2) {
            restart = false;
            return;
        }
        if (firstFrame) {
            restart = true;
            restartCount = 1;
            return;
        }
        if (restartCount < restartLength) {
            restart = true;
            restartCount++;
        } else if (restartCount == restartLength) {
            restart = false;
            restartCount++;
        }
        if (Math.abs(20 * StrictMath.log10(lastRms / rmsSid)) < 6) {
            restart = false;
        }
    }

    /**
     * Generates the excitation of one subframe of a non-active frame.
     *
     * @param txFlag {@code TX_flag} of the frame
     * @param subframe the index of the subframe in its frame
     * @param rms the decoded RMS of the subframe, for {@code TX_flag} 2 and 3
     * @param reflection the reflection coefficients of the subframe
     * @param out the excitation
     */
    void excitation(int txFlag, int subframe, double rms, double[] reflection, double[] out) {
        var n = subframeSize;
        if (txFlag >= 2) {
            var norm = excitationRms(rms, reflection);
            normSmoothed = restart ? norm : 0.875 * normSmoothed + 0.125 * norm;
            if (subframe == subframes - 1) {
                rmsLast = rms;
            }
        } else {
            normSmoothed = 0.875 * normSmoothed + 0.125 * excitationRms(rmsSid, reflection);
        }
        for (var i = 0; i < n; i++) {
            noise[i] = gauss();
        }
        if (rpe == null) {
            for (var start = 0; start + CNG_PULSE_SPAN <= n; start += CNG_PULSE_SPAN) {
                var positions = randomBits(20);
                var signs = (int) randomBits(CNG_PULSES);
                pulses.decode(positions, signs, start == 0 ? pulseVector : secondVector, null);
                if (start > 0) {
                    System.arraycopy(secondVector, 0, pulseVector, start, CNG_PULSE_SPAN);
                }
            }
        } else {
            var index = (int) randomBits(rpeBits);
            rpePulses(index, pulseVector);
        }

        var pulseEnergy = CelpCore.energy(pulseVector, n);
        var gf = normSmoothed * (rpe != null ? rpeGain
                : ALPHA * Math.sqrt((double) n / (CNG_PULSES * n / CNG_PULSE_SPAN)));
        var a1 = gf * gf * pulseEnergy;
        var a2 = CelpCore.energy(noise, n);
        var a3 = 0.0;
        for (var i = 0; i < n; i++) {
            a3 += gf * pulseVector[i] * noise[i];
        }
        var root = ALPHA * ALPHA * a3 * a3 - (ALPHA * ALPHA - 1) * a1 * a2;
        var gr = a2 > 0 ? (-ALPHA * a3 + Math.sqrt(Math.max(root, 0))) / (a2 * ALPHA) : 0;
        for (var i = 0; i < n; i++) {
            out[i] = gf * pulseVector[i] + gr * noise[i];
        }
    }

    /**
     * {@return the RMS of the excitation that a decoded speech RMS gives through a synthesis filter, scaled by
     * {@link #RMS_SCALE}}
     *
     * @param rms the decoded RMS of the speech
     * @param reflection the reflection coefficients of the filter
     */
    private double excitationRms(double rms, double[] reflection) {
        var gain = 1.0;
        for (var i = 0; i < order; i++) {
            gain *= 1 - reflection[i] * reflection[i];
        }
        return gain > 0 ? rms * Math.sqrt(gain) * RMS_SCALE : 0;
    }

    /**
     * Ends a non-active frame.
     *
     * @param txFlag {@code TX_flag} of the frame
     */
    void endFrame(int txFlag) {
        if (txFlag >= 2) {
            rmsSid = rmsLast;
        }
    }

    /**
     * Builds a regular pulse vector from a random {@code shape_index}.
     */
    private void rpePulses(int index, double[] out) {
        Arrays.fill(out, 0);
        var spacing = rpe.rpeSpacing;
        var phase = index % spacing;
        index /= spacing;
        var amplitudes = rpeAmplitudes;
        for (var p = rpe.rpePulses - 1; p >= 0; p--) {
            amplitudes[p] = index % 3 - 1;
            index /= 3;
        }
        for (var p = 0; p < rpe.rpePulses; p++) {
            var position = phase + spacing * p;
            if (position < subframeSize) {
                out[position] = rpeScale * amplitudes[p];
            }
        }
    }

    /**
     * {@return the next value of the random sequence}
     */
    private short random() {
        seed = (short) (seed * 31821 + 13849);
        return seed;
    }

    /**
     * {@return a sample of Gaussian noise, the scaled sum of twelve values of the random sequence}
     */
    private double gauss() {
        var sum = 0.0f;
        for (var i = 0; i < 12; i++) {
            sum += (float) random();
        }
        sum /= 2 * 32768;
        return sum;
    }

    /**
     * {@return a random field of up to 32 bits drawn from the random sequence}
     */
    private long randomBits(int bits) {
        long value = 0xFFFF & random();
        if (bits > 16) {
            value |= (long) (0xFFFF & random()) << 16;
        }
        if (bits < 32) {
            value &= (1L << bits) - 1;
        }
        return value;
    }
}
