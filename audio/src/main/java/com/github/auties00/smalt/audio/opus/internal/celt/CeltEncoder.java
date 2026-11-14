/* Ports libopus 1.5.2 (celt/celt_encoder.c). Copyright Xiph.Org Foundation, CSIRO and Jean-Marc Valin; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

import java.util.Arrays;

/**
 * Encodes CELT frames, as used by the CELT-only and hybrid modes of Opus (RFC 6716 section 5.3).
 *
 * <p>The encoder always runs the 48 kHz mode internally; input at a lower rate is upsampled by
 * zero insertion. Input samples are interleaved floats nominally in {@code [-1, 1]}, exactly as
 * {@code opus_encode_float} hands them to CELT.
 *
 * @implNote This implementation omits custom modes, {@code RESYNTH} and the fuzzing hooks, keeps the trailing arrays of
 * the reference state as separate arrays and allocates the stack buffers of a frame once per encoder. A start band not
 * below the end band, which the reference only asserts against, is rejected with {@link Celt#OPUS_BAD_ARG}.
 */
public final class CeltEncoder {
    /**
     * The table of {@code 6 * 64 / x} used by the transient detector ({@code inv_table}).
     */
    private static final short[] INV_TABLE = {
            255, 255, 156, 110, 86, 70, 59, 51, 45, 40, 37, 33, 31, 28, 26, 25,
            23, 22, 21, 20, 19, 18, 17, 16, 16, 15, 15, 14, 13, 13, 12, 12,
            12, 12, 11, 11, 11, 10, 10, 10, 9, 9, 9, 9, 9, 9, 8, 8,
            8, 8, 8, 7, 7, 7, 7, 7, 7, 6, 6, 6, 6, 6, 6, 6,
            6, 6, 6, 6, 6, 6, 6, 6, 6, 5, 5, 5, 5, 5, 5, 5,
            5, 5, 5, 5, 5, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4,
            4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 4, 3, 3,
            3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 3, 2
    };

    /**
     * The equivalent-rate thresholds of the intensity stereo band, in kb/s
     * ({@code intensity_thresholds}).
     */
    private static final float[] INTENSITY_THRESHOLDS = {
            1, 2, 3, 4, 5, 6, 7, 8, 16, 24, 36, 44, 50, 56, 62, 67, 72, 79, 88, 106, 134
    };

    /**
     * The hysteresis of the intensity thresholds ({@code intensity_histeresis}).
     */
    private static final float[] INTENSITY_HYSTERESIS = {
            1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2, 3, 3, 4, 5, 6, 8, 8
    };

    /**
     * The mode.
     */
    private final CeltMode mode;

    /**
     * The number of input channels.
     */
    private final int channels;

    /**
     * The number of channels coded in the stream.
     */
    private int stream_channels;

    /**
     * Nonzero to force intra energy coding.
     */
    private int force_intra;

    /**
     * Nonzero to clip the input to a portable range.
     */
    private final int clip;

    /**
     * Nonzero to disable the pitch pre-filter.
     */
    private int disable_pf;

    /**
     * The encoder complexity, 0 to 10.
     */
    private int complexity;

    /**
     * The upsampling factor from the input rate to 48 kHz.
     */
    private final int upsample;

    /**
     * The first coded band.
     */
    private int start;

    /**
     * The end band.
     */
    private int end;

    /**
     * The target bitrate, or {@link Celt#OPUS_BITRATE_MAX}.
     */
    private int bitrate;

    /**
     * Nonzero for variable bitrate.
     */
    private int vbr;

    /**
     * Nonzero when frames carry a CELT header (custom modes only).
     */
    private int signaling;

    /**
     * Nonzero for constrained variable bitrate.
     */
    private int constrained_vbr;

    /**
     * The expected packet loss percentage.
     */
    private int loss_rate;

    /**
     * The input resolution in bits.
     */
    private int lsb_depth;

    /**
     * Nonzero for an LFE channel.
     */
    private int lfe;

    /**
     * Nonzero to disable stereo phase inversion.
     */
    private int disable_inv;

    /**
     * The final range of the last frame, unsigned.
     */
    private int rng;

    /**
     * The spreading decision of the last frame.
     */
    private int spread_decision;

    /**
     * The delayed intra distortion.
     */
    private final float[] delayedIntra = new float[1];

    /**
     * The recursive average of the spreading decision.
     */
    private final int[] tonal_average = new int[1];

    /**
     * The number of bands coded in the last frame.
     */
    private int lastCodedBands;

    /**
     * The high-frequency average of the spreading decision.
     */
    private final int[] hf_average = new int[1];

    /**
     * The pre-filter tapset decision.
     */
    private final int[] tapset_decision = new int[1];

    /**
     * The pre-filter period of the last frame.
     */
    private int prefilter_period;

    /**
     * The pre-filter gain of the last frame.
     */
    private float prefilter_gain;

    /**
     * The pre-filter tapset of the last frame.
     */
    private int prefilter_tapset;

    /**
     * The number of consecutive transient frames.
     */
    private int consec_transient;

    /**
     * The latest tonality analysis.
     */
    private final AnalysisInfo analysis = new AnalysisInfo();

    /**
     * The latest SILK frame parameters.
     */
    private final SilkInfo silk_info = new SilkInfo();

    /**
     * The pre-emphasis memory of each channel.
     */
    private final float[] preemph_memE = new float[2];

    /**
     * The de-emphasis memory of each channel (resynthesis only).
     */
    private final float[] preemph_memD = new float[2];

    /**
     * The constrained VBR bit reservoir.
     */
    private int vbr_reservoir;

    /**
     * The VBR drift.
     */
    private int vbr_drift;

    /**
     * The VBR offset.
     */
    private int vbr_offset;

    /**
     * The number of VBR frames so far, saturating at 970.
     */
    private int vbr_count;

    /**
     * The peak of the overlap samples of the last frame.
     */
    private float overlap_max;

    /**
     * The stereo saving estimate.
     */
    private float stereo_saving;

    /**
     * The intensity stereo band.
     */
    private int intensity;

    /**
     * The surround masking of each band and channel, or {@code null}.
     */
    private float[] energy_mask;

    /**
     * The average spectral level for temporal VBR.
     */
    private float spec_avg;

    /**
     * The overlap samples of each channel.
     */
    private final float[] in_mem;

    /**
     * The pre-filter history of each channel.
     */
    private final float[] prefilter_mem;

    /**
     * The quantized band energies of each channel.
     */
    private final float[] oldBandE;

    /**
     * The band energies of the previous frame.
     */
    private final float[] oldLogE;

    /**
     * The band energies of the frame before the previous one.
     */
    private final float[] oldLogE2;

    /**
     * The energy quantization error of each band and channel.
     */
    private final float[] energyError;

    /**
     * The encoder used when the caller passes none.
     */
    private final RangeEncoder ownEnc = new RangeEncoder();

    /**
     * The band shape scratch.
     */
    private final Bands bands;

    /**
     * The pitch analysis scratch.
     */
    private final Pitch.Scratch pitchScratch = new Pitch.Scratch();

    /**
     * The coarse energy scratch.
     */
    private final QuantBands.CoarseScratch coarseScratch;

    /**
     * The pre-emphasized, pre-filtered input of each channel with its overlap.
     */
    private final float[] in;

    /**
     * The MDCT coefficients of each channel.
     */
    private final float[] freq;

    /**
     * The normalized coefficients.
     */
    private final float[] X;

    /**
     * The band amplitudes.
     */
    private final float[] bandE;

    /**
     * The band log-energies.
     */
    private final float[] bandLogE;

    /**
     * The band log-energies of the long MDCT (second MDCT) or a copy of {@link #bandLogE}.
     */
    private final float[] bandLogE2;

    /**
     * The energy quantization error of the frame.
     */
    private final float[] error;

    /**
     * The surround dynamic allocation of each band.
     */
    private final float[] surround_dynalloc;

    /**
     * The fine energy bits of each band.
     */
    private final int[] fine_quant;

    /**
     * The PVQ allocation of each band.
     */
    private final int[] pulses;

    /**
     * The allocation cap of each band.
     */
    private final int[] cap;

    /**
     * The dynamic allocation boost of each band.
     */
    private final int[] offsets;

    /**
     * The importance of each band for the TF analysis.
     */
    private final int[] importance;

    /**
     * The spreading weight of each band.
     */
    private final int[] spread_weight;

    /**
     * The fine energy priority of each band.
     */
    private final int[] fine_priority;

    /**
     * The TF resolution change of each band.
     */
    private final int[] tf_res;

    /**
     * The collapse mask of each band and channel.
     */
    private final int[] collapse_masks;

    /**
     * The allocation scratch arrays.
     */
    private final int[][] allocScratch;

    /**
     * The intensity band exchanged with the allocation.
     */
    private final int[] intensityOut = new int[1];

    /**
     * The dual stereo flag exchanged with the allocation.
     */
    private final int[] dualStereoOut = new int[1];

    /**
     * The balance returned by the allocation.
     */
    private final int[] balanceOut = new int[1];

    /**
     * The MDCT windowing scratch.
     */
    private final float[] mdctF;

    /**
     * The MDCT FFT scratch.
     */
    private final float[] mdctF2;

    /**
     * The pre-filter input of each channel with its history.
     */
    private final float[] pre;

    /**
     * The downsampled pre-filter input of the pitch search.
     */
    private final float[] pitchBuf;

    /**
     * The transient detector scratch.
     */
    private final float[] transientTmp;

    /**
     * The TF analysis metric of each band.
     */
    private final int[] tfMetric;

    /**
     * The TF analysis scratch band.
     */
    private final float[] tfTmp;

    /**
     * The TF analysis scratch band for the split-to-minus-one case.
     */
    private final float[] tfTmp1;

    /**
     * The Viterbi path of TF state 0.
     */
    private final int[] tfPath0;

    /**
     * The Viterbi path of TF state 1.
     */
    private final int[] tfPath1;

    /**
     * The spread old energies of the transient patch.
     */
    private final float[] spreadOld = new float[26];

    /**
     * The costs of the two TF select values.
     */
    private final int[] tfSelcost = new int[2];

    /**
     * The dynamic allocation follower.
     */
    private final float[] follower;

    /**
     * The dynamic allocation noise floor.
     */
    private final float[] noise_floor;

    /**
     * The dynamic allocation energies of one channel.
     */
    private final float[] bandLogE3;

    /**
     * The dynamic allocation masking curve.
     */
    private final float[] mask;

    /**
     * The dynamic allocation signal curve.
     */
    private final float[] sig;

    /**
     * The pitch lag exchanged with {@link Pitch#removeDoubling}.
     */
    private final int[] pitchIndexOut = new int[1];

    /**
     * The TF estimate computed by {@link #transientAnalysis}.
     */
    private float tfEstimateOut;

    /**
     * The TF channel computed by {@link #transientAnalysis}.
     */
    private int tfChanOut;

    /**
     * The weak transient flag computed by {@link #transientAnalysis}.
     */
    private int weakTransientOut;

    /**
     * The pitch period computed by {@link #runPrefilter}.
     */
    private int pfPitch;

    /**
     * The pitch gain computed by {@link #runPrefilter}.
     */
    private float pfGain;

    /**
     * The quantized pitch gain computed by {@link #runPrefilter}.
     */
    private int pfQgain;

    /**
     * The total dynamic allocation boost computed by {@link #dynallocAnalysis}.
     */
    private int totBoostOut;

    /**
     * Creates an encoder ({@code celt_encoder_init}).
     *
     * @param Fs       the input rate: 8000, 12000, 16000, 24000 or 48000
     * @param channels the number of input channels, 1 or 2
     * @throws IllegalArgumentException if the rate or channel count is unsupported
     */
    public CeltEncoder(int Fs, int channels) {
        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException("invalid channel count " + channels);
        }
        var up = Celt.resamplingFactor(Fs);
        if (up == 0) {
            throw new IllegalArgumentException("unsupported sampling rate " + Fs);
        }
        this.mode = CeltMode.MODE_48000_960;
        this.stream_channels = this.channels = channels;
        this.upsample = up;
        this.start = 0;
        this.end = mode.effEBands;
        this.signaling = 1;
        this.constrained_vbr = 1;
        this.clip = 1;
        this.bitrate = Celt.OPUS_BITRATE_MAX;
        this.vbr = 0;
        this.force_intra = 0;
        this.complexity = 5;
        this.lsb_depth = 24;
        var nb = mode.nbEBands;
        var overlap = mode.overlap;
        var maxN = mode.shortMdctSize << mode.maxLM;
        this.in_mem = new float[channels * overlap];
        this.prefilter_mem = new float[channels * Celt.COMBFILTER_MAXPERIOD];
        this.oldBandE = new float[channels * nb];
        this.oldLogE = new float[channels * nb];
        this.oldLogE2 = new float[channels * nb];
        this.energyError = new float[channels * nb];
        this.bands = new Bands(mode);
        this.coarseScratch = new QuantBands.CoarseScratch(nb, channels);
        this.in = new float[channels * (maxN + overlap)];
        this.freq = new float[channels * maxN];
        this.X = new float[channels * maxN];
        this.bandE = new float[nb * channels];
        this.bandLogE = new float[nb * channels];
        this.bandLogE2 = new float[nb * channels];
        this.error = new float[nb * channels];
        this.surround_dynalloc = new float[nb * channels];
        this.fine_quant = new int[nb];
        this.pulses = new int[nb];
        this.cap = new int[nb];
        this.offsets = new int[nb];
        this.importance = new int[nb];
        this.spread_weight = new int[nb];
        this.fine_priority = new int[nb];
        this.tf_res = new int[nb];
        this.collapse_masks = new int[2 * nb];
        this.allocScratch = new int[][]{new int[nb], new int[nb], new int[nb], new int[nb]};
        this.mdctF = new float[mode.mdct.n >> 1];
        this.mdctF2 = new float[mode.mdct.n >> 1];
        this.pre = new float[channels * (maxN + Celt.COMBFILTER_MAXPERIOD)];
        this.pitchBuf = new float[(Celt.COMBFILTER_MAXPERIOD + maxN) >> 1];
        this.transientTmp = new float[maxN + overlap];
        var maxBand = (mode.eBands[nb] - mode.eBands[nb - 1]) << mode.maxLM;
        this.tfMetric = new int[nb];
        this.tfTmp = new float[maxBand];
        this.tfTmp1 = new float[maxBand];
        this.tfPath0 = new int[nb];
        this.tfPath1 = new int[nb];
        this.follower = new float[channels * nb];
        this.noise_floor = new float[channels * nb];
        this.bandLogE3 = new float[nb];
        this.mask = new float[nb];
        this.sig = new float[nb];
        resetState();
    }

    /**
     * Resets the encoder to its initial state, keeping the configuration
     * ({@code OPUS_RESET_STATE}).
     *
     * <p>Like the reference, this also clears the analysis, the SILK information and the energy
     * mask.
     */
    public void resetState() {
        rng = 0;
        spread_decision = 0;
        delayedIntra[0] = 0;
        tonal_average[0] = 0;
        lastCodedBands = 0;
        hf_average[0] = 0;
        tapset_decision[0] = 0;
        prefilter_period = 0;
        prefilter_gain = 0;
        prefilter_tapset = 0;
        consec_transient = 0;
        analysis.clear();
        silk_info.signalType = 0;
        silk_info.offset = 0;
        preemph_memE[0] = preemph_memE[1] = 0;
        preemph_memD[0] = preemph_memD[1] = 0;
        vbr_reservoir = 0;
        vbr_drift = 0;
        vbr_offset = 0;
        vbr_count = 0;
        overlap_max = 0;
        stereo_saving = 0;
        intensity = 0;
        energy_mask = null;
        spec_avg = 0;
        Arrays.fill(in_mem, 0);
        Arrays.fill(prefilter_mem, 0);
        Arrays.fill(oldBandE, 0);
        Arrays.fill(energyError, 0);
        for (var i = 0; i < channels * mode.nbEBands; i++) {
            oldLogE[i] = oldLogE2[i] = -28.f;
        }
        vbr_offset = 0;
        delayedIntra[0] = 1;
        spread_decision = Celt.SPREAD_NORMAL;
        tonal_average[0] = 256;
        hf_average[0] = 0;
        tapset_decision[0] = 0;
    }

    /**
     * Sets the encoder complexity ({@code OPUS_SET_COMPLEXITY}).
     *
     * @param value the complexity, 0 to 10
     * @return {@link Celt#OPUS_OK} or {@link Celt#OPUS_BAD_ARG}
     */
    public int setComplexity(int value) {
        if (value < 0 || value > 10) {
            return Celt.OPUS_BAD_ARG;
        }
        complexity = value;
        return Celt.OPUS_OK;
    }

    /**
     * Sets the first coded band ({@code CELT_SET_START_BAND}).
     *
     * @param value the band, 0 to {@code nbEBands - 1}
     * @return {@link Celt#OPUS_OK} or {@link Celt#OPUS_BAD_ARG}
     */
    public int setStartBand(int value) {
        if (value < 0 || value >= mode.nbEBands) {
            return Celt.OPUS_BAD_ARG;
        }
        start = value;
        return Celt.OPUS_OK;
    }

    /**
     * Sets the end band ({@code CELT_SET_END_BAND}).
     *
     * @param value the band, 1 to {@code nbEBands}
     * @return {@link Celt#OPUS_OK} or {@link Celt#OPUS_BAD_ARG}
     */
    public int setEndBand(int value) {
        if (value < 1 || value > mode.nbEBands) {
            return Celt.OPUS_BAD_ARG;
        }
        end = value;
        return Celt.OPUS_OK;
    }

    /**
     * Sets the prediction mode ({@code CELT_SET_PREDICTION}): 0 independent frames, 1 short-term
     * prediction, 2 long-term prediction (the pitch pre-filter) allowed.
     *
     * @param value 0, 1 or 2
     * @return {@link Celt#OPUS_OK} or {@link Celt#OPUS_BAD_ARG}
     */
    public int setPrediction(int value) {
        if (value < 0 || value > 2) {
            return Celt.OPUS_BAD_ARG;
        }
        disable_pf = value <= 1 ? 1 : 0;
        force_intra = value == 0 ? 1 : 0;
        return Celt.OPUS_OK;
    }

    /**
     * Sets the expected packet loss percentage ({@code OPUS_SET_PACKET_LOSS_PERC}).
     *
     * @param value 0 to 100
     * @return {@link Celt#OPUS_OK} or {@link Celt#OPUS_BAD_ARG}
     */
    public int setPacketLossPerc(int value) {
        if (value < 0 || value > 100) {
            return Celt.OPUS_BAD_ARG;
        }
        loss_rate = value;
        return Celt.OPUS_OK;
    }

    /**
     * Enables or disables constrained VBR ({@code OPUS_SET_VBR_CONSTRAINT}).
     *
     * @param value nonzero for constrained VBR
     */
    public void setVbrConstraint(int value) {
        constrained_vbr = value;
    }

    /**
     * Enables or disables VBR ({@code OPUS_SET_VBR}).
     *
     * @param value nonzero for VBR
     */
    public void setVbr(int value) {
        vbr = value;
    }

    /**
     * Sets the target bitrate ({@code OPUS_SET_BITRATE}).
     *
     * @param value the bitrate in bits per second, above 500, or {@link Celt#OPUS_BITRATE_MAX}
     * @return {@link Celt#OPUS_OK} or {@link Celt#OPUS_BAD_ARG}
     */
    public int setBitrate(int value) {
        if (value <= 500 && value != Celt.OPUS_BITRATE_MAX) {
            return Celt.OPUS_BAD_ARG;
        }
        value = Math.min(value, 260000 * channels);
        bitrate = value;
        return Celt.OPUS_OK;
    }

    /**
     * Sets the number of channels coded in the stream ({@code CELT_SET_CHANNELS}).
     *
     * @param value 1 or 2
     * @return {@link Celt#OPUS_OK} or {@link Celt#OPUS_BAD_ARG}
     */
    public int setChannels(int value) {
        if (value < 1 || value > 2) {
            return Celt.OPUS_BAD_ARG;
        }
        stream_channels = value;
        return Celt.OPUS_OK;
    }

    /**
     * Sets the input resolution ({@code OPUS_SET_LSB_DEPTH}).
     *
     * @param value 8 to 24 bits
     * @return {@link Celt#OPUS_OK} or {@link Celt#OPUS_BAD_ARG}
     */
    public int setLsbDepth(int value) {
        if (value < 8 || value > 24) {
            return Celt.OPUS_BAD_ARG;
        }
        lsb_depth = value;
        return Celt.OPUS_OK;
    }

    /**
     * Enables or disables stereo phase inversion ({@code OPUS_SET_PHASE_INVERSION_DISABLED}).
     *
     * @param disabled {@code true} to disable phase inversion
     */
    public void setPhaseInversionDisabled(boolean disabled) {
        disable_inv = disabled ? 1 : 0;
    }

    /**
     * Sets whether frames carry a CELT header ({@code CELT_SET_SIGNALLING}); Opus sets it to 0.
     *
     * @param value the signaling flag
     */
    public void setSignaling(int value) {
        signaling = value;
    }

    /**
     * Sets the tonality analysis of the next frame ({@code CELT_SET_ANALYSIS}); the analysis is
     * copied.
     *
     * @param info the analysis, or {@code null} to keep the current one
     */
    public void setAnalysis(AnalysisInfo info) {
        if (info != null) {
            analysis.copyFrom(info);
        }
    }

    /**
     * Sets the SILK frame parameters of the next frame ({@code CELT_SET_SILK_INFO}); the
     * parameters are copied.
     *
     * @param info the parameters, or {@code null} to keep the current ones
     */
    public void setSilkInfo(SilkInfo info) {
        if (info != null) {
            silk_info.copyFrom(info);
        }
    }

    /**
     * Returns the mode ({@code CELT_GET_MODE}).
     *
     * @return the mode
     */
    public CeltMode getMode() {
        return mode;
    }

    /**
     * Returns the final range of the last encoded frame ({@code OPUS_GET_FINAL_RANGE}).
     *
     * @return the unsigned 32-bit range
     */
    public int getFinalRange() {
        return rng;
    }

    /**
     * Marks the encoder as coding an LFE channel ({@code OPUS_SET_LFE}).
     *
     * @param value nonzero for LFE
     */
    public void setLfe(int value) {
        lfe = value;
    }

    /**
     * Sets the surround masking of each band and channel ({@code OPUS_SET_ENERGY_MASK}).
     *
     * <p>The array is referenced, not copied, as in the reference.
     *
     * @param value the masking, {@code nbEBands} values per channel, or {@code null}
     */
    public void setEnergyMask(float[] value) {
        energy_mask = value;
    }

    /**
     * Detects transients from the high-pass energy envelope of the input and estimates how much
     * temporal resolution the frame needs ({@code transient_analysis}).
     *
     * <p>Sets {@link #tfEstimateOut}, {@link #tfChanOut} and {@link #weakTransientOut}.
     *
     * @param len                 the number of samples per channel
     * @param C                   the number of channels
     * @param allowWeakTransients nonzero to classify weak transients separately
     * @return nonzero for a transient
     */
    private int transientAnalysis(int len, int C, int allowWeakTransients) {
        int i;
        var tmp = transientTmp;
        float mem0, mem1;
        int isTransient;
        var maskMetric = 0;
        int c;
        float tfMax;
        int len2;
        var forwardDecay = .0625f;
        weakTransientOut = 0;
        if (allowWeakTransients != 0) {
            forwardDecay = .03125f;
        }
        len2 = len / 2;
        for (c = 0; c < C; c++) {
            float mean;
            int unmask;
            float norm;
            float maxE;
            mem0 = 0;
            mem1 = 0;
            for (i = 0; i < len; i++) {
                float mem00;
                float x, y;
                x = in[i + c * len];
                y = mem0 + x;
                mem00 = mem0;
                mem0 = mem0 - x + .5f * mem1;
                mem1 = x - mem00;
                tmp[i] = y;
            }
            Arrays.fill(tmp, 0, 12, 0);
            mean = 0;
            mem0 = 0;
            for (i = 0; i < len2; i++) {
                var x2 = tmp[2 * i] * tmp[2 * i] + tmp[2 * i + 1] * tmp[2 * i + 1];
                mean += x2;
                mem0 = x2 + (1.f - forwardDecay) * mem0;
                tmp[i] = forwardDecay * mem0;
            }
            mem0 = 0;
            maxE = 0;
            for (i = len2 - 1; i >= 0; i--) {
                mem0 = tmp[i] + 0.875f * mem0;
                tmp[i] = 0.125f * mem0;
                maxE = MathOps.max(maxE, 0.125f * mem0);
            }
            mean = (float) Math.sqrt(mean * maxE * .5 * len2);
            norm = len2 / (MathOps.EPSILON + mean);
            unmask = 0;
            for (i = 12; i < len2 - 5; i += 4) {
                int id;
                var f = Math.floor(64 * norm * (tmp[i] + MathOps.EPSILON));
                var clamped = 127 < f ? 127 : f;
                id = (int) (0 > clamped ? 0 : clamped);
                unmask += INV_TABLE[id];
            }
            unmask = 64 * unmask * 4 / (6 * (len2 - 17));
            if (unmask > maskMetric) {
                tfChanOut = c;
                maskMetric = unmask;
            }
        }
        isTransient = maskMetric > 200 ? 1 : 0;
        if (allowWeakTransients != 0 && isTransient != 0 && maskMetric < 600) {
            isTransient = 0;
            weakTransientOut = 1;
        }
        tfMax = MathOps.max(0, (float) Math.sqrt(27 * maskMetric) - 42);
        var d = (float) 0.0069 * MathOps.min(163, tfMax) - 0.139;
        tfEstimateOut = (float) Math.sqrt(0 > d ? 0 : d);
        return isTransient;
    }

    /**
     * Detects a sudden energy increase the time-domain detector missed
     * ({@code patch_transient_decision}).
     *
     * @param newE     the new band log-energies
     * @param oldE     the previous band log-energies
     * @param nbEBands the number of bands
     * @param start    the first band
     * @param end      the end band
     * @param C        the number of channels
     * @return nonzero to switch to short blocks
     */
    private int patchTransientDecision(float[] newE, float[] oldE, int nbEBands, int start, int end, int C) {
        int i, c;
        var meanDiff = 0f;
        var spreadOld = this.spreadOld;
        if (C == 1) {
            spreadOld[start] = oldE[start];
            for (i = start + 1; i < end; i++) {
                spreadOld[i] = MathOps.max(spreadOld[i - 1] - 1.0f, oldE[i]);
            }
        } else {
            spreadOld[start] = MathOps.max(oldE[start], oldE[start + nbEBands]);
            for (i = start + 1; i < end; i++) {
                spreadOld[i] = MathOps.max(spreadOld[i - 1] - 1.0f, MathOps.max(oldE[i], oldE[i + nbEBands]));
            }
        }
        for (i = end - 2; i >= start; i--) {
            spreadOld[i] = MathOps.max(spreadOld[i], spreadOld[i + 1] - 1.0f);
        }
        c = 0;
        do {
            for (i = Math.max(2, start); i < end - 1; i++) {
                float x1, x2;
                x1 = MathOps.max(0, newE[i + c * nbEBands]);
                x2 = MathOps.max(0, spreadOld[i]);
                meanDiff = meanDiff + MathOps.max(0, x1 - x2);
            }
        } while (++c < C);
        meanDiff = meanDiff / (float) (C * (end - 1 - Math.max(2, start)));
        return meanDiff > 1.f ? 1 : 0;
    }

    /**
     * Applies the window and computes the MDCTs of every block and channel
     * ({@code compute_mdcts}).
     *
     * @param shortBlocks the number of short blocks, or 0 for one long MDCT
     * @param out         receives the coefficients, interleaved by block
     * @param C           the number of coded channels
     * @param CC          the number of input channels
     * @param LM          the frame size shift
     * @param upsample    the upsampling factor
     */
    private void computeMdcts(int shortBlocks, float[] out, int C, int CC, int LM, int upsample) {
        var overlap = mode.overlap;
        int N;
        int B;
        int shift;
        int i, b, c;
        if (shortBlocks != 0) {
            B = shortBlocks;
            N = mode.shortMdctSize;
            shift = mode.maxLM;
        } else {
            B = 1;
            N = mode.shortMdctSize << LM;
            shift = mode.maxLM - LM;
        }
        c = 0;
        do {
            for (b = 0; b < B; b++) {
                Mdct.cltMdctForward(mode.mdct, in, c * (B * N + overlap) + b * N, out, b + c * N * B, mode.window,
                        overlap, shift, B, mdctF, mdctF2);
            }
        } while (++c < CC);
        if (CC == 2 && C == 1) {
            for (i = 0; i < B * N; i++) {
                out[i] = .5f * out[i] + .5f * out[B * N + i];
            }
        }
        if (upsample != 1) {
            c = 0;
            do {
                var bound = B * N / upsample;
                for (i = 0; i < bound; i++) {
                    out[c * B * N + i] *= upsample;
                }
                Arrays.fill(out, c * B * N + bound, c * B * N + B * N, 0);
            } while (++c < C);
        }
    }

    /**
     * Converts the input to CELT's scale, upsamples it by zero insertion, optionally clips it,
     * and applies the pre-emphasis filter ({@code celt_preemphasis}).
     *
     * @param pcm      the interleaved input
     * @param pcmOff   the index of the first sample of this channel
     * @param inp      receives the pre-emphasized signal
     * @param inpOff   the index of the first output sample
     * @param N        the number of output samples
     * @param CC       the input channel count (the stride of {@code pcm})
     * @param upsample the upsampling factor
     * @param coef     the pre-emphasis coefficients
     * @param mem      the filter memory of each channel
     * @param memIdx   the channel whose memory is used
     * @param clip     nonzero to clip the input
     */
    static void celtPreemphasis(float[] pcm, int pcmOff, float[] inp, int inpOff, int N, int CC,
                                int upsample, float[] coef, float[] mem, int memIdx, int clip) {
        int i;
        var coef0 = coef[0];
        var m = mem[memIdx];
        int Nu;
        if (coef[1] == 0 && upsample == 1 && clip == 0) {
            for (i = 0; i < N; i++) {
                var x = pcm[pcmOff + CC * i] * MathOps.CELT_SIG_SCALE;
                inp[inpOff + i] = x - m;
                m = coef0 * x;
            }
            mem[memIdx] = m;
            return;
        }
        Nu = N / upsample;
        if (upsample != 1) {
            Arrays.fill(inp, inpOff, inpOff + N, 0);
        }
        for (i = 0; i < Nu; i++) {
            inp[inpOff + i * upsample] = pcm[pcmOff + CC * i] * MathOps.CELT_SIG_SCALE;
        }
        if (clip != 0) {
            for (i = 0; i < Nu; i++) {
                inp[inpOff + i * upsample] = MathOps.max(-65536.f, MathOps.min(65536.f, inp[inpOff + i * upsample]));
            }
        }
        for (i = 0; i < N; i++) {
            var x = inp[inpOff + i];
            inp[inpOff + i] = x - m;
            m = coef0 * x;
        }
        mem[memIdx] = m;
    }

    /**
     * Returns the L1 norm of a band biased towards frequency resolution ({@code l1_metric}).
     *
     * @param tmp  the band
     * @param N    the band size
     * @param LM   the resolution level
     * @param bias the bias per level
     * @return the biased L1 norm
     */
    private static float l1Metric(float[] tmp, int N, int LM, float bias) {
        var L1 = 0f;
        for (var i = 0; i < N; i++) {
            L1 += Math.abs(tmp[i]);
        }
        L1 = L1 + (LM * bias) * L1;
        return L1;
    }

    /**
     * Chooses the TF resolution of each band with a Viterbi search over the L1 norms of the
     * Haar-transformed bands ({@code tf_analysis}).
     *
     * @param len         the number of bands
     * @param isTransient nonzero for short blocks
     * @param tfRes       receives the TF change flag of each band
     * @param lambda      the cost of changing resolution between bands
     * @param Xn          the normalized coefficients
     * @param N0          the number of coefficients per channel
     * @param LM          the frame size shift
     * @param tfEstimate  the TF estimate of the transient detector
     * @param tfChan      the channel analyzed
     * @param importance  the importance of each band
     * @return the TF select flag
     */
    private int tfAnalysis(int len, int isTransient, int[] tfRes, int lambda, float[] Xn, int N0, int LM,
                           float tfEstimate, int tfChan, int[] importance) {
        int i;
        var metric = tfMetric;
        int cost0;
        int cost1;
        var path0 = tfPath0;
        var path1 = tfPath1;
        var tmp = tfTmp;
        var tmp1 = tfTmp1;
        int sel;
        var selcost = tfSelcost;
        var tfSelect = 0;
        float bias;
        var m = mode;
        bias = .04f * MathOps.max(-.25f, .5f - tfEstimate);
        for (i = 0; i < len; i++) {
            int k, N;
            int narrow;
            float L1, bestL1;
            var bestLevel = 0;
            N = (m.eBands[i + 1] - m.eBands[i]) << LM;
            narrow = (m.eBands[i + 1] - m.eBands[i]) == 1 ? 1 : 0;
            System.arraycopy(Xn, tfChan * N0 + (m.eBands[i] << LM), tmp, 0, N);
            L1 = l1Metric(tmp, N, isTransient != 0 ? LM : 0, bias);
            bestL1 = L1;
            if (isTransient != 0 && narrow == 0) {
                System.arraycopy(tmp, 0, tmp1, 0, N);
                Bands.haar1(tmp1, 0, N >> LM, 1 << LM);
                L1 = l1Metric(tmp1, N, LM + 1, bias);
                if (L1 < bestL1) {
                    bestL1 = L1;
                    bestLevel = -1;
                }
            }
            for (k = 0; k < LM + (isTransient != 0 || narrow != 0 ? 0 : 1); k++) {
                int B;
                if (isTransient != 0) {
                    B = LM - k - 1;
                } else {
                    B = k + 1;
                }
                Bands.haar1(tmp, 0, N >> k, 1 << k);
                L1 = l1Metric(tmp, N, B, bias);
                if (L1 < bestL1) {
                    bestL1 = L1;
                    bestLevel = k + 1;
                }
            }
            if (isTransient != 0) {
                metric[i] = 2 * bestLevel;
            } else {
                metric[i] = -2 * bestLevel;
            }
            if (narrow != 0 && (metric[i] == 0 || metric[i] == -2 * LM)) {
                metric[i] -= 1;
            }
        }
        var table = Celt.TF_SELECT_TABLE[LM];
        tfSelect = 0;
        for (sel = 0; sel < 2; sel++) {
            cost0 = importance[0] * Math.abs(metric[0] - 2 * table[4 * isTransient + 2 * sel]);
            cost1 = importance[0] * Math.abs(metric[0] - 2 * table[4 * isTransient + 2 * sel + 1])
                    + (isTransient != 0 ? 0 : lambda);
            for (i = 1; i < len; i++) {
                int curr0, curr1;
                curr0 = Math.min(cost0, cost1 + lambda);
                curr1 = Math.min(cost0 + lambda, cost1);
                cost0 = curr0 + importance[i] * Math.abs(metric[i] - 2 * table[4 * isTransient + 2 * sel]);
                cost1 = curr1 + importance[i] * Math.abs(metric[i] - 2 * table[4 * isTransient + 2 * sel + 1]);
            }
            cost0 = Math.min(cost0, cost1);
            selcost[sel] = cost0;
        }
        if (selcost[1] < selcost[0] && isTransient != 0) {
            tfSelect = 1;
        }
        cost0 = importance[0] * Math.abs(metric[0] - 2 * table[4 * isTransient + 2 * tfSelect]);
        cost1 = importance[0] * Math.abs(metric[0] - 2 * table[4 * isTransient + 2 * tfSelect + 1])
                + (isTransient != 0 ? 0 : lambda);
        for (i = 1; i < len; i++) {
            int curr0, curr1;
            int from0, from1;
            from0 = cost0;
            from1 = cost1 + lambda;
            if (from0 < from1) {
                curr0 = from0;
                path0[i] = 0;
            } else {
                curr0 = from1;
                path0[i] = 1;
            }
            from0 = cost0 + lambda;
            from1 = cost1;
            if (from0 < from1) {
                curr1 = from0;
                path1[i] = 0;
            } else {
                curr1 = from1;
                path1[i] = 1;
            }
            cost0 = curr0 + importance[i] * Math.abs(metric[i] - 2 * table[4 * isTransient + 2 * tfSelect]);
            cost1 = curr1 + importance[i] * Math.abs(metric[i] - 2 * table[4 * isTransient + 2 * tfSelect + 1]);
        }
        tfRes[len - 1] = cost0 < cost1 ? 0 : 1;
        for (i = len - 2; i >= 0; i--) {
            if (tfRes[i + 1] == 1) {
                tfRes[i] = path1[i + 1];
            } else {
                tfRes[i] = path0[i + 1];
            }
        }
        return tfSelect;
    }

    /**
     * Encodes the per-band TF resolution changes ({@code tf_encode}).
     *
     * @param start       the first band
     * @param end         the end band
     * @param isTransient nonzero for short blocks
     * @param tfRes       the TF change flag of each band; replaced by the TF change
     * @param LM          the frame size shift
     * @param tfSelect    the TF select flag
     * @param enc         the range encoder
     */
    private static void tfEncode(int start, int end, int isTransient, int[] tfRes, int LM, int tfSelect,
                                 RangeEncoder enc) {
        int curr, i;
        int tfSelectRsv;
        int tfChanged;
        int logp;
        int budget;
        int tell;
        budget = enc.storage * 8;
        tell = enc.tell();
        logp = isTransient != 0 ? 2 : 4;
        tfSelectRsv = LM > 0 && Integer.compareUnsigned(tell + logp + 1, budget) <= 0 ? 1 : 0;
        budget -= tfSelectRsv;
        curr = tfChanged = 0;
        for (i = start; i < end; i++) {
            if (Integer.compareUnsigned(tell + logp, budget) <= 0) {
                enc.encBitLogp(tfRes[i] ^ curr, logp);
                tell = enc.tell();
                curr = tfRes[i];
                tfChanged |= curr;
            } else {
                tfRes[i] = curr;
            }
            logp = isTransient != 0 ? 4 : 5;
        }
        if (tfSelectRsv != 0 && Celt.TF_SELECT_TABLE[LM][4 * isTransient + tfChanged]
                != Celt.TF_SELECT_TABLE[LM][4 * isTransient + 2 + tfChanged]) {
            enc.encBitLogp(tfSelect, 1);
        } else {
            tfSelect = 0;
        }
        for (i = start; i < end; i++) {
            tfRes[i] = Celt.TF_SELECT_TABLE[LM][4 * isTransient + 2 * tfSelect + tfRes[i]];
        }
    }

    /**
     * Chooses the allocation trim from the stereo correlation, the spectral tilt and the
     * analysis ({@code alloc_trim_analysis}).
     *
     * @param Xn           the normalized coefficients
     * @param bandLogE     the band log-energies
     * @param end          the end band
     * @param LM           the frame size shift
     * @param C            the number of channels
     * @param N0           the number of coefficients per channel
     * @param tfEstimate   the TF estimate
     * @param intensity    the intensity band
     * @param surroundTrim the surround trim adjustment
     * @param equivRate    the equivalent bitrate
     * @return the trim index, 0 to 10
     */
    private int allocTrimAnalysis(float[] Xn, float[] bandLogE, int end, int LM, int C, int N0, float tfEstimate,
                                  int intensity, float surroundTrim, int equivRate) {
        int i;
        var diff = 0f;
        int c;
        int trimIndex;
        var trim = 5.f;
        float logXC, logXC2;
        var m = mode;
        if (equivRate < 64000) {
            trim = 4.f;
        } else if (equivRate < 80000) {
            var frac = (equivRate - 64000) >> 10;
            trim = 4.f + (1.f / 16.f) * frac;
        }
        if (C == 2) {
            var sum = 0f;
            float minXC;
            for (i = 0; i < 8; i++) {
                var partial = Pitch.celtInnerProd(Xn, m.eBands[i] << LM, Xn, N0 + (m.eBands[i] << LM),
                        (m.eBands[i + 1] - m.eBands[i]) << LM);
                sum = sum + partial;
            }
            sum = (1.f / 8) * sum;
            sum = MathOps.min(1.f, Math.abs(sum));
            minXC = sum;
            for (i = 8; i < intensity; i++) {
                var partial = Pitch.celtInnerProd(Xn, m.eBands[i] << LM, Xn, N0 + (m.eBands[i] << LM),
                        (m.eBands[i + 1] - m.eBands[i]) << LM);
                minXC = MathOps.min(minXC, Math.abs(partial));
            }
            minXC = MathOps.min(1.f, Math.abs(minXC));
            logXC = MathOps.celtLog2(1.001f - sum * sum);
            logXC2 = MathOps.max(.5f * logXC, MathOps.celtLog2(1.001f - minXC * minXC));
            trim += MathOps.max(-4.f, .75f * logXC);
            stereo_saving = MathOps.min(stereo_saving + 0.25f, -(.5f * logXC2));
        }
        c = 0;
        do {
            for (i = 0; i < end - 1; i++) {
                diff += bandLogE[i + c * m.nbEBands] * (2 + 2 * i - end);
            }
        } while (++c < C);
        diff /= C * (end - 1);
        trim -= MathOps.max(-2.f, MathOps.min(2.f, (diff + 1.f) / 6));
        trim -= surroundTrim;
        trim -= 2 * tfEstimate;
        if (analysis.valid != 0) {
            trim -= MathOps.max(-2.f, MathOps.min(2.f, 2.f * (analysis.tonality_slope + .05f)));
        }
        trimIndex = (int) Math.floor(.5f + trim);
        trimIndex = Math.max(0, Math.min(10, trimIndex));
        return trimIndex;
    }

    /**
     * Decides between dual stereo and mid/side from the L1 norms of the low bands
     * ({@code stereo_analysis}).
     *
     * @param Xn the normalized coefficients
     * @param LM the frame size shift
     * @param N0 the number of coefficients per channel
     * @return nonzero for dual stereo
     */
    private int stereoAnalysis(float[] Xn, int LM, int N0) {
        int i;
        int thetas;
        float sumLR = MathOps.EPSILON, sumMS = MathOps.EPSILON;
        var m = mode;
        for (i = 0; i < 13; i++) {
            int j;
            for (j = m.eBands[i] << LM; j < m.eBands[i + 1] << LM; j++) {
                float L, R, M, S;
                L = Xn[j];
                R = Xn[N0 + j];
                M = L + R;
                S = L - R;
                sumLR = sumLR + (Math.abs(L) + Math.abs(R));
                sumMS = sumMS + (Math.abs(M) + Math.abs(S));
            }
        }
        sumMS = 0.707107f * sumMS;
        thetas = 13;
        if (LM <= 1) {
            thetas -= 8;
        }
        return ((m.eBands[13] << (LM + 1)) + thetas) * sumMS > (m.eBands[13] << (LM + 1)) * sumLR ? 1 : 0;
    }

    /**
     * Returns the median of five values ({@code median_of_5}).
     *
     * @param x    the values
     * @param xOff the index of the first value
     * @return the median
     */
    private static float medianOf5(float[] x, int xOff) {
        float t0, t1, t2, t3, t4;
        t2 = x[xOff + 2];
        if (x[xOff] > x[xOff + 1]) {
            t0 = x[xOff + 1];
            t1 = x[xOff];
        } else {
            t0 = x[xOff];
            t1 = x[xOff + 1];
        }
        if (x[xOff + 3] > x[xOff + 4]) {
            t3 = x[xOff + 4];
            t4 = x[xOff + 3];
        } else {
            t3 = x[xOff + 3];
            t4 = x[xOff + 4];
        }
        if (t0 > t3) {
            var tmp = t0;
            t0 = t3;
            t3 = tmp;
            tmp = t1;
            t1 = t4;
            t4 = tmp;
        }
        if (t2 > t1) {
            if (t1 < t3) {
                return MathOps.min(t2, t3);
            } else {
                return MathOps.min(t4, t1);
            }
        } else {
            if (t2 < t3) {
                return MathOps.min(t1, t3);
            } else {
                return MathOps.min(t2, t4);
            }
        }
    }

    /**
     * Returns the median of three values ({@code median_of_3}).
     *
     * @param x    the values
     * @param xOff the index of the first value
     * @return the median
     */
    private static float medianOf3(float[] x, int xOff) {
        float t0, t1, t2;
        if (x[xOff] > x[xOff + 1]) {
            t0 = x[xOff + 1];
            t1 = x[xOff];
        } else {
            t0 = x[xOff];
            t1 = x[xOff + 1];
        }
        t2 = x[xOff + 2];
        if (t1 < t2) {
            return t1;
        } else if (t0 < t2) {
            return t2;
        } else {
            return t0;
        }
    }

    /**
     * Computes the dynamic allocation boosts, the band importances and the spreading weights from
     * a simple masking model ({@code dynalloc_analysis}).
     *
     * <p>Sets {@link #totBoostOut}.
     *
     * @param bandLogE        the band log-energies
     * @param bandLogE2       the band log-energies of the long MDCT
     * @param oldBandE        the quantized energies of the previous frame
     * @param nbEBands        the number of bands
     * @param start           the first band
     * @param end             the end band
     * @param C               the number of channels
     * @param offsets         receives the boost of each band
     * @param lsbDepth        the input resolution
     * @param logN            the band width logarithms
     * @param isTransient     nonzero for short blocks
     * @param vbr             nonzero for VBR
     * @param constrainedVbr  nonzero for constrained VBR
     * @param eBands          the band edges
     * @param LM              the frame size shift
     * @param effectiveBytes  the bytes available
     * @param lfe             nonzero for LFE
     * @param surroundDynalloc the surround boost of each band
     * @param importance      receives the importance of each band
     * @param spreadWeight    receives the spreading weight of each band
     * @return the maximum depth above the noise floor
     */
    private float dynallocAnalysis(float[] bandLogE, float[] bandLogE2, float[] oldBandE, int nbEBands, int start,
                                   int end, int C, int[] offsets, int lsbDepth, short[] logN, int isTransient,
                                   int vbr, int constrainedVbr, short[] eBands, int LM, int effectiveBytes, int lfe,
                                   float[] surroundDynalloc, int[] importance, int[] spreadWeight) {
        int i, c;
        var totBoost = 0;
        float maxDepth;
        Arrays.fill(offsets, 0, nbEBands, 0);
        maxDepth = -31.9f;
        for (i = 0; i < end; i++) {
            noise_floor[i] = 0.0625f * logN[i] + .5f + (9 - lsbDepth) - QuantBands.E_MEANS[i]
                    + (float) .0062 * (float) ((i + 5) * (i + 5));
        }
        c = 0;
        do {
            for (i = 0; i < end; i++) {
                maxDepth = MathOps.max(maxDepth, bandLogE[c * nbEBands + i] - noise_floor[i]);
            }
        } while (++c < C);
        {
            for (i = 0; i < end; i++) {
                mask[i] = bandLogE[i] - noise_floor[i];
            }
            if (C == 2) {
                for (i = 0; i < end; i++) {
                    mask[i] = MathOps.max(mask[i], bandLogE[nbEBands + i] - noise_floor[i]);
                }
            }
            System.arraycopy(mask, 0, sig, 0, end);
            for (i = 1; i < end; i++) {
                mask[i] = MathOps.max(mask[i], mask[i - 1] - 2.f);
            }
            for (i = end - 2; i >= 0; i--) {
                mask[i] = MathOps.max(mask[i], mask[i + 1] - 3.f);
            }
            for (i = 0; i < end; i++) {
                var smr = sig[i] - MathOps.max(MathOps.max(0, maxDepth - 12.f), mask[i]);
                var shift = Math.min(5, Math.max(0, -(int) Math.floor(.5f + smr)));
                spreadWeight[i] = 32 >> shift;
            }
        }
        if (effectiveBytes >= (30 + 5 * LM) && lfe == 0) {
            var last = 0;
            c = 0;
            do {
                float offset;
                float tmp;
                var f = c * nbEBands;
                System.arraycopy(bandLogE2, c * nbEBands, bandLogE3, 0, end);
                if (LM == 0) {
                    for (i = 0; i < Math.min(8, end); i++) {
                        bandLogE3[i] = MathOps.max(bandLogE2[c * nbEBands + i], oldBandE[c * nbEBands + i]);
                    }
                }
                follower[f] = bandLogE3[0];
                for (i = 1; i < end; i++) {
                    if (bandLogE3[i] > bandLogE3[i - 1] + .5f) {
                        last = i;
                    }
                    follower[f + i] = MathOps.min(follower[f + i - 1] + 1.5f, bandLogE3[i]);
                }
                for (i = last - 1; i >= 0; i--) {
                    follower[f + i] = MathOps.min(follower[f + i], MathOps.min(follower[f + i + 1] + 2.f, bandLogE3[i]));
                }
                offset = 1.f;
                for (i = 2; i < end - 2; i++) {
                    follower[f + i] = MathOps.max(follower[f + i], medianOf5(bandLogE3, i - 2) - offset);
                }
                tmp = medianOf3(bandLogE3, 0) - offset;
                follower[f] = MathOps.max(follower[f], tmp);
                follower[f + 1] = MathOps.max(follower[f + 1], tmp);
                tmp = medianOf3(bandLogE3, end - 3) - offset;
                follower[f + end - 2] = MathOps.max(follower[f + end - 2], tmp);
                follower[f + end - 1] = MathOps.max(follower[f + end - 1], tmp);
                for (i = 0; i < end; i++) {
                    follower[f + i] = MathOps.max(follower[f + i], noise_floor[i]);
                }
            } while (++c < C);
            if (C == 2) {
                for (i = start; i < end; i++) {
                    follower[nbEBands + i] = MathOps.max(follower[nbEBands + i], follower[i] - 4.f);
                    follower[i] = MathOps.max(follower[i], follower[nbEBands + i] - 4.f);
                    follower[i] = .5f * (MathOps.max(0, bandLogE[i] - follower[i])
                            + MathOps.max(0, bandLogE[nbEBands + i] - follower[nbEBands + i]));
                }
            } else {
                for (i = start; i < end; i++) {
                    follower[i] = MathOps.max(0, bandLogE[i] - follower[i]);
                }
            }
            for (i = start; i < end; i++) {
                follower[i] = MathOps.max(follower[i], surroundDynalloc[i]);
            }
            for (i = start; i < end; i++) {
                importance[i] = (int) Math.floor(.5f + 13 * MathOps.celtExp2(MathOps.min(follower[i], 4.f)));
            }
            if ((vbr == 0 || constrainedVbr != 0) && isTransient == 0) {
                for (i = start; i < end; i++) {
                    follower[i] = .5f * follower[i];
                }
            }
            for (i = start; i < end; i++) {
                if (i < 8) {
                    follower[i] *= 2;
                }
                if (i >= 12) {
                    follower[i] = .5f * follower[i];
                }
            }
            if (analysis.valid != 0) {
                for (i = start; i < Math.min(Celt.LEAK_BANDS, end); i++) {
                    follower[i] = follower[i] + (1.f / 64.f) * analysis.leak_boost[i];
                }
            }
            for (i = start; i < end; i++) {
                int width;
                int boost;
                int boostBits;
                follower[i] = MathOps.min(follower[i], 4);
                width = C * (eBands[i + 1] - eBands[i]) << LM;
                if (width < 6) {
                    boost = (int) follower[i];
                    boostBits = boost * width << EntropyCoder.BITRES;
                } else if (width > 48) {
                    boost = (int) (follower[i] * 8);
                    boostBits = (boost * width << EntropyCoder.BITRES) / 8;
                } else {
                    boost = (int) (follower[i] * width / 6);
                    boostBits = boost * 6 << EntropyCoder.BITRES;
                }
                if ((vbr == 0 || (constrainedVbr != 0 && isTransient == 0))
                        && (totBoost + boostBits) >> EntropyCoder.BITRES >> 3 > 2 * effectiveBytes / 3) {
                    var capBits = (2 * effectiveBytes / 3) << EntropyCoder.BITRES << 3;
                    offsets[i] = capBits - totBoost;
                    totBoost = capBits;
                    break;
                } else {
                    offsets[i] = boost;
                    totBoost += boostBits;
                }
            }
        } else {
            for (i = start; i < end; i++) {
                importance[i] = 13;
            }
        }
        totBoostOut = totBoost;
        return maxDepth;
    }

    /**
     * Runs the pitch pre-filter: finds the pitch, decides whether to enable the filter, and
     * filters the input in place ({@code run_prefilter}).
     *
     * <p>Sets {@link #pfPitch}, {@link #pfGain} and {@link #pfQgain}.
     *
     * @param CC               the number of input channels
     * @param N                the frame size
     * @param prefilterTapset  the tapset to use
     * @param enabled          nonzero to search for a pitch
     * @param nbAvailableBytes the bytes available for the frame
     * @return nonzero when the pre-filter is on
     */
    private int runPrefilter(int CC, int N, int prefilterTapset, int enabled, int nbAvailableBytes) {
        int c;
        int pitchIndex;
        float gain1;
        float pfThreshold;
        int pfOn;
        int qg;
        var overlap = mode.overlap;
        var preStride = N + Celt.COMBFILTER_MAXPERIOD;
        c = 0;
        do {
            System.arraycopy(prefilter_mem, c * Celt.COMBFILTER_MAXPERIOD, pre, c * preStride,
                    Celt.COMBFILTER_MAXPERIOD);
            System.arraycopy(in, c * (N + overlap) + overlap, pre, c * preStride + Celt.COMBFILTER_MAXPERIOD, N);
        } while (++c < CC);
        if (enabled != 0) {
            Pitch.pitchDownsample(pre, 0, preStride, pitchBuf, Celt.COMBFILTER_MAXPERIOD + N, CC, pitchScratch);
            pitchIndex = Pitch.pitchSearch(pitchBuf, Celt.COMBFILTER_MAXPERIOD >> 1, pitchBuf, 0, N,
                    Celt.COMBFILTER_MAXPERIOD - 3 * Celt.COMBFILTER_MINPERIOD, pitchScratch);
            pitchIndex = Celt.COMBFILTER_MAXPERIOD - pitchIndex;
            pitchIndexOut[0] = pitchIndex;
            gain1 = Pitch.removeDoubling(pitchBuf, 0, Celt.COMBFILTER_MAXPERIOD, Celt.COMBFILTER_MINPERIOD, N,
                    pitchIndexOut, prefilter_period, prefilter_gain, pitchScratch);
            pitchIndex = pitchIndexOut[0];
            if (pitchIndex > Celt.COMBFILTER_MAXPERIOD - 2) {
                pitchIndex = Celt.COMBFILTER_MAXPERIOD - 2;
            }
            gain1 = .7f * gain1;
            if (loss_rate > 2) {
                gain1 = .5f * gain1;
            }
            if (loss_rate > 4) {
                gain1 = .5f * gain1;
            }
            if (loss_rate > 8) {
                gain1 = 0;
            }
        } else {
            gain1 = 0;
            pitchIndex = Celt.COMBFILTER_MINPERIOD;
        }
        if (analysis.valid != 0) {
            gain1 = gain1 * analysis.max_pitch_ratio;
        }
        pfThreshold = .2f;
        if (Math.abs(pitchIndex - prefilter_period) * 10 > pitchIndex) {
            pfThreshold += .2f;
        }
        if (nbAvailableBytes < 25) {
            pfThreshold += .1f;
        }
        if (nbAvailableBytes < 35) {
            pfThreshold += .1f;
        }
        if (prefilter_gain > .4f) {
            pfThreshold -= .1f;
        }
        if (prefilter_gain > .55f) {
            pfThreshold -= .1f;
        }
        pfThreshold = MathOps.max(pfThreshold, .2f);
        if (gain1 < pfThreshold) {
            gain1 = 0;
            pfOn = 0;
            qg = 0;
        } else {
            if (Math.abs(gain1 - prefilter_gain) < .1f) {
                gain1 = prefilter_gain;
            }
            qg = (int) Math.floor(.5f + gain1 * 32 / 3) - 1;
            qg = Math.max(0, Math.min(7, qg));
            gain1 = 0.09375f * (qg + 1);
            pfOn = 1;
        }
        c = 0;
        do {
            var offset = mode.shortMdctSize - overlap;
            prefilter_period = Math.max(prefilter_period, Celt.COMBFILTER_MINPERIOD);
            System.arraycopy(in_mem, c * overlap, in, c * (N + overlap), overlap);
            if (offset != 0) {
                Celt.combFilter(in, c * (N + overlap) + overlap, pre, c * preStride + Celt.COMBFILTER_MAXPERIOD,
                        prefilter_period, prefilter_period, offset, -prefilter_gain, -prefilter_gain,
                        prefilter_tapset, prefilter_tapset, null, 0);
            }
            Celt.combFilter(in, c * (N + overlap) + overlap + offset, pre,
                    c * preStride + Celt.COMBFILTER_MAXPERIOD + offset, prefilter_period, pitchIndex, N - offset,
                    -prefilter_gain, -gain1, prefilter_tapset, prefilterTapset, mode.window, overlap);
            System.arraycopy(in, c * (N + overlap) + N, in_mem, c * overlap, overlap);
            if (N > Celt.COMBFILTER_MAXPERIOD) {
                System.arraycopy(pre, c * preStride + N, prefilter_mem, c * Celt.COMBFILTER_MAXPERIOD,
                        Celt.COMBFILTER_MAXPERIOD);
            } else {
                System.arraycopy(prefilter_mem, c * Celt.COMBFILTER_MAXPERIOD + N, prefilter_mem,
                        c * Celt.COMBFILTER_MAXPERIOD, Celt.COMBFILTER_MAXPERIOD - N);
                System.arraycopy(pre, c * preStride + Celt.COMBFILTER_MAXPERIOD, prefilter_mem,
                        c * Celt.COMBFILTER_MAXPERIOD + Celt.COMBFILTER_MAXPERIOD - N, N);
            }
        } while (++c < CC);
        pfGain = gain1;
        pfPitch = pitchIndex;
        pfQgain = qg;
        return pfOn;
    }

    /**
     * Computes the VBR target of the frame in 1/8 bits ({@code compute_vbr}).
     *
     * @param baseTarget       the base target
     * @param LM               the frame size shift
     * @param bitrate          the equivalent bitrate
     * @param lastCodedBands   the number of bands coded in the previous frame
     * @param C                the number of channels
     * @param intensity        the intensity band
     * @param constrainedVbr   nonzero for constrained VBR
     * @param stereoSaving     the stereo saving estimate
     * @param totBoost         the total dynamic allocation boost
     * @param tfEstimate       the TF estimate
     * @param pitchChange      nonzero when the pitch changed
     * @param maxDepth         the maximum depth above the noise floor
     * @param lfe              nonzero for LFE
     * @param hasSurroundMask  nonzero when a surround mask is set
     * @param surroundMasking  the surround masking level
     * @param temporalVbr      the temporal VBR adjustment
     * @return the target
     */
    private int computeVbr(int baseTarget, int LM, int bitrate, int lastCodedBands, int C, int intensity,
                           int constrainedVbr, float stereoSaving, int totBoost, float tfEstimate, int pitchChange,
                           float maxDepth, int lfe, int hasSurroundMask, float surroundMasking, float temporalVbr) {
        int target;
        int codedBins;
        int codedBands;
        float tfCalibration;
        var nbEBands = mode.nbEBands;
        var eBands = mode.eBands;
        codedBands = lastCodedBands != 0 ? lastCodedBands : nbEBands;
        codedBins = eBands[codedBands] << LM;
        if (C == 2) {
            codedBins += eBands[Math.min(intensity, codedBands)] << LM;
        }
        target = baseTarget;
        if (analysis.valid != 0 && analysis.activity < .4) {
            target -= (int) ((codedBins << EntropyCoder.BITRES) * (.4f - analysis.activity));
        }
        if (C == 2) {
            int codedStereoBands;
            int codedStereoDof;
            float maxFrac;
            codedStereoBands = Math.min(intensity, codedBands);
            codedStereoDof = (eBands[codedStereoBands] << LM) - codedStereoBands;
            maxFrac = (0.8f * (float) codedStereoDof) / (float) codedBins;
            stereoSaving = MathOps.min(stereoSaving, 1.f);
            target -= (int) MathOps.min(maxFrac * target,
                    (stereoSaving - 0.1f) * (float) (codedStereoDof << EntropyCoder.BITRES));
        }
        target += totBoost - (19 << LM);
        tfCalibration = 0.044f;
        target += (int) ((tfEstimate - tfCalibration) * target);
        if (analysis.valid != 0 && lfe == 0) {
            int tonalTarget;
            float tonal;
            tonal = MathOps.max(0.f, analysis.tonality - .15f) - 0.12f;
            tonalTarget = target + (int) ((codedBins << EntropyCoder.BITRES) * 1.2f * tonal);
            if (pitchChange != 0) {
                tonalTarget += (int) ((codedBins << EntropyCoder.BITRES) * .8f);
            }
            target = tonalTarget;
        }
        if (hasSurroundMask != 0 && lfe == 0) {
            var surroundTarget = target + (int) (surroundMasking * (float) (codedBins << EntropyCoder.BITRES));
            target = Math.max(target / 4, surroundTarget);
        }
        {
            int floorDepth;
            int bins;
            bins = eBands[nbEBands - 2] << LM;
            floorDepth = (int) ((float) (C * bins << EntropyCoder.BITRES) * maxDepth);
            floorDepth = Math.max(floorDepth, target >> 2);
            target = Math.min(target, floorDepth);
        }
        if ((hasSurroundMask == 0 || lfe != 0) && constrainedVbr != 0) {
            target = baseTarget + (int) (0.67f * (target - baseTarget));
        }
        if (hasSurroundMask == 0 && tfEstimate < .2f) {
            float amount;
            float tvbrFactor;
            amount = .0000031f * Math.max(0, Math.min(32000, 96000 - bitrate));
            tvbrFactor = temporalVbr * amount;
            target += (int) (tvbrFactor * target);
        }
        target = Math.min(2 * baseTarget, target);
        return target;
    }

    /**
     * Encodes one CELT frame ({@code celt_encode_with_ec}).
     *
     * <p>When {@code enc} is {@code null} the frame is written to {@code compressed}; otherwise it
     * continues the given range encoder (hybrid mode) and {@code compressed} is unused.
     *
     * @param pcm               the interleaved input, nominally in {@code [-1, 1]}
     * @param pcmOff            the index of the first input sample
     * @param frameSize         the frame size at the input rate
     * @param compressed        the output buffer when {@code enc} is {@code null}
     * @param compressedOff     the index of the first output byte
     * @param nbCompressedBytes the size of the frame budget in bytes, at least 2
     * @param enc               the range encoder to continue, or {@code null}
     * @return the number of bytes of the frame, or a negative Opus error code
     */
    public int encodeWithEc(float[] pcm, int pcmOff, int frameSize, byte[] compressed, int compressedOff,
                            int nbCompressedBytes, RangeEncoder enc) {
        int i, c, N;
        int bits;
        var shortBlocks = 0;
        var isTransient = 0;
        var CC = channels;
        var C = stream_channels;
        int LM, M;
        int tfSelect;
        int nbFilledBytes, nbAvailableBytes;
        int st;
        int en;
        int effEnd;
        int codedBands;
        int allocTrim;
        var pitchIndex = Celt.COMBFILTER_MINPERIOD;
        var gain1 = 0f;
        var dualStereo = 0;
        int effectiveBytes;
        int dynallocLogp;
        int vbrRate;
        int totalBits;
        int totalBoost;
        int balance;
        int tell;
        int tell0Frac;
        var prefilterTapset = 0;
        int pfOn;
        int antiCollapseRsv;
        var antiCollapseOn = 0;
        var silence = 0;
        var tfChan = 0;
        float tfEstimate;
        var pitchChange = 0;
        int totBoost;
        float sampleMax;
        float maxDepth;
        var nbEBands = mode.nbEBands;
        var overlap = mode.overlap;
        var eBands = mode.eBands;
        int secondMdct;
        int signalBandwidth;
        var transientGotDisabled = 0;
        var surroundMasking = 0f;
        var temporalVbr = 0f;
        var surroundTrim = 0f;
        int equivRate;
        int hybrid;
        var weakTransient = 0;
        int enableTfAnalysis;
        st = start;
        en = end;
        hybrid = st != 0 ? 1 : 0;
        tfEstimate = 0;
        if (nbCompressedBytes < 2 || pcm == null || st >= en) {
            return Celt.OPUS_BAD_ARG;
        }
        frameSize *= upsample;
        for (LM = 0; LM <= mode.maxLM; LM++) {
            if (mode.shortMdctSize << LM == frameSize) {
                break;
            }
        }
        if (LM > mode.maxLM) {
            return Celt.OPUS_BAD_ARG;
        }
        M = 1 << LM;
        N = M * mode.shortMdctSize;
        if (enc == null) {
            tell0Frac = tell = 1;
            nbFilledBytes = 0;
        } else {
            tell0Frac = enc.tellFrac();
            tell = enc.tell();
            nbFilledBytes = (tell + 4) >> 3;
        }
        nbCompressedBytes = Math.min(nbCompressedBytes, 1275);
        nbAvailableBytes = nbCompressedBytes - nbFilledBytes;
        if (vbr != 0 && bitrate != Celt.OPUS_BITRATE_MAX) {
            var den = mode.Fs >> EntropyCoder.BITRES;
            vbrRate = (bitrate * frameSize + (den >> 1)) / den;
            effectiveBytes = vbrRate >> (3 + EntropyCoder.BITRES);
        } else {
            int tmp;
            vbrRate = 0;
            tmp = bitrate * frameSize;
            if (tell > 1) {
                tmp += tell * mode.Fs;
            }
            if (bitrate != Celt.OPUS_BITRATE_MAX) {
                nbCompressedBytes = Math.max(2, Math.min(nbCompressedBytes,
                        (tmp + 4 * mode.Fs) / (8 * mode.Fs) - (signaling != 0 ? 1 : 0)));
                if (enc != null) {
                    enc.encShrink(nbCompressedBytes);
                }
            }
            effectiveBytes = nbCompressedBytes - nbFilledBytes;
        }
        equivRate = ((nbCompressedBytes * 8 * 50) << (3 - LM)) - (40 * C + 20) * ((400 >> LM) - 50);
        if (bitrate != Celt.OPUS_BITRATE_MAX) {
            equivRate = Math.min(equivRate, bitrate - (40 * C + 20) * ((400 >> LM) - 50));
        }
        if (enc == null) {
            ownEnc.init(compressed, compressedOff, nbCompressedBytes);
            enc = ownEnc;
        }
        if (vbrRate > 0) {
            if (constrained_vbr != 0) {
                int vbrBound;
                int maxAllowed;
                vbrBound = vbrRate;
                maxAllowed = Math.min(Math.max(tell == 1 ? 2 : 0,
                        (vbrRate + vbrBound - vbr_reservoir) >> (EntropyCoder.BITRES + 3)), nbAvailableBytes);
                if (maxAllowed < nbAvailableBytes) {
                    nbCompressedBytes = nbFilledBytes + maxAllowed;
                    nbAvailableBytes = maxAllowed;
                    enc.encShrink(nbCompressedBytes);
                }
            }
        }
        totalBits = nbCompressedBytes * 8;
        effEnd = en;
        if (effEnd > mode.effEBands) {
            effEnd = mode.effEBands;
        }
        sampleMax = MathOps.max(overlap_max, MathOps.celtMaxabs16(pcm, pcmOff, C * (N - overlap) / upsample));
        overlap_max = MathOps.celtMaxabs16(pcm, pcmOff + C * (N - overlap) / upsample, C * overlap / upsample);
        sampleMax = MathOps.max(sampleMax, overlap_max);
        silence = sampleMax <= (float) 1 / (1 << lsb_depth) ? 1 : 0;
        if (tell == 1) {
            enc.encBitLogp(silence, 15);
        } else {
            silence = 0;
        }
        if (silence != 0) {
            if (vbrRate > 0) {
                effectiveBytes = nbCompressedBytes = Math.min(nbCompressedBytes, nbFilledBytes + 2);
                totalBits = nbCompressedBytes * 8;
                nbAvailableBytes = 2;
                enc.encShrink(nbCompressedBytes);
            }
            tell = nbCompressedBytes * 8;
            enc.nbitsTotal += tell - enc.tell();
        }
        c = 0;
        do {
            var needClip = clip != 0 && sampleMax > 65536.f ? 1 : 0;
            celtPreemphasis(pcm, pcmOff + c, in, c * (N + overlap) + overlap, N, CC, upsample, mode.preemph,
                    preemph_memE, c, needClip);
        } while (++c < CC);
        {
            int enabled;
            int qg;
            enabled = ((lfe != 0 && nbAvailableBytes > 3) || nbAvailableBytes > 12 * C) && hybrid == 0
                    && silence == 0 && disable_pf == 0 && complexity >= 5 ? 1 : 0;
            prefilterTapset = tapset_decision[0];
            pfOn = runPrefilter(CC, N, prefilterTapset, enabled, nbAvailableBytes);
            pitchIndex = pfPitch;
            gain1 = pfGain;
            qg = pfQgain;
            if ((gain1 > .4f || prefilter_gain > .4f) && (analysis.valid == 0 || analysis.tonality > .3)
                    && (pitchIndex > 1.26 * prefilter_period || pitchIndex < .79 * prefilter_period)) {
                pitchChange = 1;
            }
            if (pfOn == 0) {
                if (hybrid == 0 && tell + 16 <= totalBits) {
                    enc.encBitLogp(0, 1);
                }
            } else {
                int octave;
                enc.encBitLogp(1, 1);
                pitchIndex += 1;
                octave = EntropyCoder.ilog(pitchIndex) - 5;
                enc.encUint(octave, 6);
                enc.encBits(pitchIndex - (16 << octave), 4 + octave);
                pitchIndex -= 1;
                enc.encBits(qg, 3);
                enc.encIcdf(prefilterTapset, Celt.TAPSET_ICDF, 2);
            }
        }
        isTransient = 0;
        shortBlocks = 0;
        if (complexity >= 1 && lfe == 0) {
            var allowWeakTransients = hybrid != 0 && effectiveBytes < 15 && silk_info.signalType != 2 ? 1 : 0;
            tfChanOut = tfChan;
            isTransient = transientAnalysis(N + overlap, CC, allowWeakTransients);
            tfEstimate = tfEstimateOut;
            tfChan = tfChanOut;
            weakTransient = weakTransientOut;
        }
        if (LM > 0 && enc.tell() + 3 <= totalBits) {
            if (isTransient != 0) {
                shortBlocks = M;
            }
        } else {
            isTransient = 0;
            transientGotDisabled = 1;
        }
        secondMdct = shortBlocks != 0 && complexity >= 8 ? 1 : 0;
        if (secondMdct != 0) {
            computeMdcts(0, freq, C, CC, LM, upsample);
            Bands.computeBandEnergies(mode, freq, bandE, effEnd, C, LM);
            QuantBands.amp2Log2(mode, effEnd, en, bandE, bandLogE2, C);
            for (c = 0; c < C; c++) {
                for (i = 0; i < en; i++) {
                    bandLogE2[nbEBands * c + i] += .5f * LM;
                }
            }
        }
        computeMdcts(shortBlocks, freq, C, CC, LM, upsample);
        if (CC == 2 && C == 1) {
            tfChan = 0;
        }
        Bands.computeBandEnergies(mode, freq, bandE, effEnd, C, LM);
        if (lfe != 0) {
            for (i = 2; i < en; i++) {
                bandE[i] = MathOps.min(bandE[i], 1e-4f * bandE[0]);
                bandE[i] = MathOps.max(bandE[i], MathOps.EPSILON);
            }
        }
        QuantBands.amp2Log2(mode, effEnd, en, bandE, bandLogE, C);
        Arrays.fill(surround_dynalloc, 0, en, 0);
        if (hybrid == 0 && energy_mask != null && lfe == 0) {
            int maskEnd;
            int midband;
            int countDynalloc;
            var maskAvg = 0f;
            var diff = 0f;
            var count = 0;
            maskEnd = Math.max(2, lastCodedBands);
            for (c = 0; c < C; c++) {
                for (i = 0; i < maskEnd; i++) {
                    float msk;
                    msk = MathOps.max(MathOps.min(energy_mask[nbEBands * c + i], .25f), -2.0f);
                    if (msk > 0) {
                        msk = .5f * msk;
                    }
                    maskAvg += msk * (float) (eBands[i + 1] - eBands[i]);
                    count += eBands[i + 1] - eBands[i];
                    diff += msk * (float) (1 + 2 * i - maskEnd);
                }
            }
            maskAvg = maskAvg / (float) count;
            maskAvg += .2f;
            diff = diff * 6 / (C * (maskEnd - 1) * (maskEnd + 1) * maskEnd);
            diff = .5f * diff;
            diff = MathOps.max(MathOps.min(diff, .031f), -.031f);
            midband = 0;
            while (eBands[midband + 1] < eBands[maskEnd] / 2) {
                midband++;
            }
            countDynalloc = 0;
            for (i = 0; i < maskEnd; i++) {
                float lin;
                float unmask;
                lin = maskAvg + diff * (i - midband);
                if (C == 2) {
                    unmask = MathOps.max(energy_mask[i], energy_mask[nbEBands + i]);
                } else {
                    unmask = energy_mask[i];
                }
                unmask = MathOps.min(unmask, .0f);
                unmask -= lin;
                if (unmask > .25f) {
                    surround_dynalloc[i] = unmask - .25f;
                    countDynalloc++;
                }
            }
            if (countDynalloc >= 3) {
                maskAvg += .25f;
                if (maskAvg > 0) {
                    maskAvg = 0;
                    diff = 0;
                    Arrays.fill(surround_dynalloc, 0, maskEnd, 0);
                } else {
                    for (i = 0; i < maskEnd; i++) {
                        surround_dynalloc[i] = MathOps.max(0, surround_dynalloc[i] - .25f);
                    }
                }
            }
            maskAvg += .2f;
            surroundTrim = 64 * diff;
            surroundMasking = maskAvg;
        }
        if (lfe == 0) {
            var follow = -10.0f;
            var frameAvg = 0f;
            var offset = shortBlocks != 0 ? .5f * LM : 0;
            for (i = st; i < en; i++) {
                follow = MathOps.max(follow - 1.f, bandLogE[i] - offset);
                if (C == 2) {
                    follow = MathOps.max(follow, bandLogE[i + nbEBands] - offset);
                }
                frameAvg += follow;
            }
            frameAvg /= (en - st);
            temporalVbr = frameAvg - spec_avg;
            temporalVbr = MathOps.min(3.f, MathOps.max(-1.5f, temporalVbr));
            spec_avg += .02f * temporalVbr;
        }
        if (secondMdct == 0) {
            System.arraycopy(bandLogE, 0, bandLogE2, 0, C * nbEBands);
        }
        if (LM > 0 && enc.tell() + 3 <= totalBits && isTransient == 0 && complexity >= 5 && lfe == 0 && hybrid == 0) {
            if (patchTransientDecision(bandLogE, oldBandE, nbEBands, st, en, C) != 0) {
                isTransient = 1;
                shortBlocks = M;
                computeMdcts(shortBlocks, freq, C, CC, LM, upsample);
                Bands.computeBandEnergies(mode, freq, bandE, effEnd, C, LM);
                QuantBands.amp2Log2(mode, effEnd, en, bandE, bandLogE, C);
                for (c = 0; c < C; c++) {
                    for (i = 0; i < en; i++) {
                        bandLogE2[nbEBands * c + i] += .5f * LM;
                    }
                }
                tfEstimate = .2f;
            }
        }
        if (LM > 0 && enc.tell() + 3 <= totalBits) {
            enc.encBitLogp(isTransient, 3);
        }
        Bands.normalizeBands(mode, freq, X, bandE, effEnd, C, M);
        enableTfAnalysis = effectiveBytes >= 15 * C && hybrid == 0 && complexity >= 2 && lfe == 0 ? 1 : 0;
        maxDepth = dynallocAnalysis(bandLogE, bandLogE2, oldBandE, nbEBands, st, en, C, offsets, lsb_depth,
                mode.logN, isTransient, vbr, constrained_vbr, eBands, LM, effectiveBytes, lfe, surround_dynalloc,
                importance, spread_weight);
        totBoost = totBoostOut;
        if (enableTfAnalysis != 0) {
            int lambda;
            lambda = Math.max(80, 20480 / effectiveBytes + 2);
            tfSelect = tfAnalysis(effEnd, isTransient, tf_res, lambda, X, N, LM, tfEstimate, tfChan, importance);
            for (i = effEnd; i < en; i++) {
                tf_res[i] = tf_res[effEnd - 1];
            }
        } else if (hybrid != 0 && weakTransient != 0) {
            for (i = 0; i < en; i++) {
                tf_res[i] = 1;
            }
            tfSelect = 0;
        } else if (hybrid != 0 && effectiveBytes < 15 && silk_info.signalType != 2) {
            for (i = 0; i < en; i++) {
                tf_res[i] = 0;
            }
            tfSelect = isTransient;
        } else {
            for (i = 0; i < en; i++) {
                tf_res[i] = isTransient;
            }
            tfSelect = 0;
        }
        c = 0;
        do {
            for (i = st; i < en; i++) {
                if (Math.abs(bandLogE[i + c * nbEBands] - oldBandE[i + c * nbEBands]) < 2.f) {
                    bandLogE[i + c * nbEBands] -= energyError[i + c * nbEBands] * 0.25f;
                }
            }
        } while (++c < C);
        QuantBands.quantCoarseEnergy(mode, st, en, effEnd, bandLogE, oldBandE, totalBits, error, enc, C, LM,
                nbAvailableBytes, force_intra, delayedIntra, complexity >= 4 ? 1 : 0, loss_rate, lfe, coarseScratch);
        tfEncode(st, en, isTransient, tf_res, LM, tfSelect, enc);
        if (enc.tell() + 4 <= totalBits) {
            if (lfe != 0) {
                tapset_decision[0] = 0;
                spread_decision = Celt.SPREAD_NORMAL;
            } else if (hybrid != 0) {
                if (complexity == 0) {
                    spread_decision = Celt.SPREAD_NONE;
                } else if (isTransient != 0) {
                    spread_decision = Celt.SPREAD_NORMAL;
                } else {
                    spread_decision = Celt.SPREAD_AGGRESSIVE;
                }
            } else if (shortBlocks != 0 || complexity < 3 || nbAvailableBytes < 10 * C) {
                if (complexity == 0) {
                    spread_decision = Celt.SPREAD_NONE;
                } else {
                    spread_decision = Celt.SPREAD_NORMAL;
                }
            } else {
                spread_decision = Bands.spreadingDecision(mode, X, tonal_average, spread_decision, hf_average,
                        tapset_decision, pfOn != 0 && shortBlocks == 0 ? 1 : 0, effEnd, C, M, spread_weight);
            }
            enc.encIcdf(spread_decision, Celt.SPREAD_ICDF, 5);
        }
        if (lfe != 0) {
            offsets[0] = Math.min(8, effectiveBytes / 3);
        }
        Celt.initCaps(mode, cap, LM, C);
        dynallocLogp = 6;
        totalBits <<= EntropyCoder.BITRES;
        totalBoost = 0;
        tell = enc.tellFrac();
        for (i = st; i < en; i++) {
            int width, quanta;
            int dynallocLoopLogp;
            int boost;
            int j;
            width = C * (eBands[i + 1] - eBands[i]) << LM;
            quanta = Math.min(width << EntropyCoder.BITRES, Math.max(6 << EntropyCoder.BITRES, width));
            dynallocLoopLogp = dynallocLogp;
            boost = 0;
            for (j = 0; tell + (dynallocLoopLogp << EntropyCoder.BITRES) < totalBits - totalBoost
                    && boost < cap[i]; j++) {
                int flag;
                flag = j < offsets[i] ? 1 : 0;
                enc.encBitLogp(flag, dynallocLoopLogp);
                tell = enc.tellFrac();
                if (flag == 0) {
                    break;
                }
                boost += quanta;
                totalBoost += quanta;
                dynallocLoopLogp = 1;
            }
            if (j != 0) {
                dynallocLogp = Math.max(2, dynallocLogp - 1);
            }
            offsets[i] = boost;
        }
        if (C == 2) {
            if (LM != 0) {
                dualStereo = stereoAnalysis(X, LM, N);
            }
            intensity = Bands.hysteresisDecision((float) (equivRate / 1000), INTENSITY_THRESHOLDS,
                    INTENSITY_HYSTERESIS, 21, intensity);
            intensity = Math.min(en, Math.max(st, intensity));
        }
        allocTrim = 5;
        if (tell + (6 << EntropyCoder.BITRES) <= totalBits - totalBoost) {
            if (st > 0 || lfe != 0) {
                stereo_saving = 0;
                allocTrim = 5;
            } else {
                allocTrim = allocTrimAnalysis(X, bandLogE, en, LM, C, N, tfEstimate, intensity, surroundTrim,
                        equivRate);
            }
            enc.encIcdf(allocTrim, Celt.TRIM_ICDF, 7);
            tell = enc.tellFrac();
        }
        if (vbrRate > 0) {
            float alpha;
            int delta;
            int target, baseTarget;
            int minAllowed;
            var lmDiff = mode.maxLM - LM;
            nbCompressedBytes = Math.min(nbCompressedBytes, 1275 >> (3 - LM));
            if (hybrid == 0) {
                baseTarget = vbrRate - ((40 * C + 20) << EntropyCoder.BITRES);
            } else {
                baseTarget = Math.max(0, vbrRate - ((9 * C + 4) << EntropyCoder.BITRES));
            }
            if (constrained_vbr != 0) {
                baseTarget += vbr_offset >> lmDiff;
            }
            if (hybrid == 0) {
                target = computeVbr(baseTarget, LM, equivRate, lastCodedBands, C, intensity, constrained_vbr,
                        stereo_saving, totBoost, tfEstimate, pitchChange, maxDepth, lfe, energy_mask != null ? 1 : 0,
                        surroundMasking, temporalVbr);
            } else {
                target = baseTarget;
                if (silk_info.offset < 100) {
                    target += 12 << EntropyCoder.BITRES >> (3 - LM);
                }
                if (silk_info.offset > 100) {
                    target -= 18 << EntropyCoder.BITRES >> (3 - LM);
                }
                target += (int) ((tfEstimate - .25f) * (float) (50 << EntropyCoder.BITRES));
                if (tfEstimate > .7f) {
                    target = Math.max(target, 50 << EntropyCoder.BITRES);
                }
            }
            target = target + tell;
            minAllowed = ((tell + totalBoost + (1 << (EntropyCoder.BITRES + 3)) - 1) >> (EntropyCoder.BITRES + 3)) + 2;
            if (hybrid != 0) {
                minAllowed = Math.max(minAllowed, (tell0Frac + (37 << EntropyCoder.BITRES) + totalBoost
                        + (1 << (EntropyCoder.BITRES + 3)) - 1) >> (EntropyCoder.BITRES + 3));
            }
            nbAvailableBytes = (target + (1 << (EntropyCoder.BITRES + 2))) >> (EntropyCoder.BITRES + 3);
            nbAvailableBytes = Math.max(minAllowed, nbAvailableBytes);
            nbAvailableBytes = Math.min(nbCompressedBytes, nbAvailableBytes);
            delta = target - vbrRate;
            target = nbAvailableBytes << (EntropyCoder.BITRES + 3);
            if (silence != 0) {
                nbAvailableBytes = 2;
                target = 2 * 8 << EntropyCoder.BITRES;
                delta = 0;
            }
            if (vbr_count < 970) {
                vbr_count++;
                alpha = MathOps.celtRcp((float) (vbr_count + 20));
            } else {
                alpha = .001f;
            }
            if (constrained_vbr != 0) {
                vbr_reservoir += target - vbrRate;
            }
            if (constrained_vbr != 0) {
                vbr_drift += (int) (alpha * (float) ((delta * (1 << lmDiff)) - vbr_offset - vbr_drift));
                vbr_offset = -vbr_drift;
            }
            if (constrained_vbr != 0 && vbr_reservoir < 0) {
                var adjust = (-vbr_reservoir) / (8 << EntropyCoder.BITRES);
                nbAvailableBytes += silence != 0 ? 0 : adjust;
                vbr_reservoir = 0;
            }
            nbCompressedBytes = Math.min(nbCompressedBytes, nbAvailableBytes);
            enc.encShrink(nbCompressedBytes);
        }
        bits = ((nbCompressedBytes * 8) << EntropyCoder.BITRES) - enc.tellFrac() - 1;
        antiCollapseRsv = isTransient != 0 && LM >= 2 && bits >= ((LM + 2) << EntropyCoder.BITRES)
                ? (1 << EntropyCoder.BITRES) : 0;
        bits -= antiCollapseRsv;
        signalBandwidth = en - 1;
        if (analysis.valid != 0) {
            int minBandwidth;
            if (equivRate < 32000 * C) {
                minBandwidth = 13;
            } else if (equivRate < 48000 * C) {
                minBandwidth = 16;
            } else if (equivRate < 60000 * C) {
                minBandwidth = 18;
            } else if (equivRate < 80000 * C) {
                minBandwidth = 19;
            } else {
                minBandwidth = 20;
            }
            signalBandwidth = Math.max(analysis.bandwidth, minBandwidth);
        }
        if (lfe != 0) {
            signalBandwidth = 1;
        }
        intensityOut[0] = intensity;
        dualStereoOut[0] = dualStereo;
        codedBands = Rate.cltComputeAllocation(mode, st, en, offsets, cap, allocTrim, intensityOut, dualStereoOut,
                bits, balanceOut, pulses, fine_quant, fine_priority, C, LM, enc, 1, lastCodedBands,
                signalBandwidth, allocScratch);
        intensity = intensityOut[0];
        dualStereo = dualStereoOut[0];
        balance = balanceOut[0];
        if (lastCodedBands != 0) {
            lastCodedBands = Math.min(lastCodedBands + 1, Math.max(lastCodedBands - 1, codedBands));
        } else {
            lastCodedBands = codedBands;
        }
        QuantBands.quantFineEnergy(mode, st, en, oldBandE, error, fine_quant, enc, C);
        rng = bands.quantAllBands(1, mode, st, en, X, C == 2 ? N : -1, collapse_masks, bandE, pulses, shortBlocks,
                spread_decision, dualStereo, intensity, tf_res, nbCompressedBytes * (8 << EntropyCoder.BITRES)
                        - antiCollapseRsv, balance, enc, LM, codedBands, rng, complexity, disable_inv);
        if (antiCollapseRsv > 0) {
            antiCollapseOn = consec_transient < 2 ? 1 : 0;
            enc.encBits(antiCollapseOn, 1);
        }
        QuantBands.quantEnergyFinalize(mode, st, en, oldBandE, error, fine_quant, fine_priority,
                nbCompressedBytes * 8 - enc.tell(), enc, C);
        Arrays.fill(energyError, 0, nbEBands * CC, 0);
        c = 0;
        do {
            for (i = st; i < en; i++) {
                energyError[i + c * nbEBands] = MathOps.max(-0.5f, MathOps.min(0.5f, error[i + c * nbEBands]));
            }
        } while (++c < C);
        if (silence != 0) {
            for (i = 0; i < C * nbEBands; i++) {
                oldBandE[i] = -28.f;
            }
        }
        prefilter_period = pitchIndex;
        prefilter_gain = gain1;
        prefilter_tapset = prefilterTapset;
        if (CC == 2 && C == 1) {
            System.arraycopy(oldBandE, 0, oldBandE, nbEBands, nbEBands);
        }
        if (isTransient == 0) {
            System.arraycopy(oldLogE, 0, oldLogE2, 0, CC * nbEBands);
            System.arraycopy(oldBandE, 0, oldLogE, 0, CC * nbEBands);
        } else {
            for (i = 0; i < CC * nbEBands; i++) {
                oldLogE[i] = MathOps.min(oldLogE[i], oldBandE[i]);
            }
        }
        c = 0;
        do {
            for (i = 0; i < st; i++) {
                oldBandE[c * nbEBands + i] = 0;
                oldLogE[c * nbEBands + i] = oldLogE2[c * nbEBands + i] = -28.f;
            }
            for (i = en; i < nbEBands; i++) {
                oldBandE[c * nbEBands + i] = 0;
                oldLogE[c * nbEBands + i] = oldLogE2[c * nbEBands + i] = -28.f;
            }
        } while (++c < CC);
        if (isTransient != 0 || transientGotDisabled != 0) {
            consec_transient++;
        } else {
            consec_transient = 0;
        }
        rng = enc.rng;
        enc.encDone();
        if (enc.getError() != 0) {
            return Celt.OPUS_INTERNAL_ERROR;
        } else {
            return nbCompressedBytes;
        }
    }
}
