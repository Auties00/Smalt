/* Ports libopus 1.5.2 (celt/celt_decoder.c). Copyright Xiph.Org Foundation, CSIRO and Jean-Marc Valin; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.audio.opus.internal.celt;

import com.github.auties00.smalt.audio.opus.internal.dnn.LpcnetPlc;

import java.util.Arrays;

/**
 * Decodes CELT frames, as used by the CELT-only and hybrid modes of Opus (RFC 6716 section 4.3),
 * including the pitch-based and noise-based packet-loss concealment.
 *
 * <p>The decoder always runs the 48 kHz mode internally and decimates its output to the rate
 * given at construction. Output samples are interleaved floats nominally in {@code [-1, 1]}.
 * Any byte sequence decodes without failing: a corrupt frame yields some audio.
 *
 * <p>A decoder put in deep PLC mode ({@link #setDeepPlc}) behaves as the reference built with
 * {@code ENABLE_DEEP_PLC}: it feeds the 16 kHz deep PLC with the decoded history and, at a complexity of at least 5 or
 * while DRED features are queued, conceals with it.
 *
 * @implNote This implementation omits custom modes, keeps the trailing arrays of the reference state
 * ({@code _decode_mem}, {@code lpc}, {@code oldBandE}, {@code oldLogE}, {@code oldLogE2}, {@code backgroundLogE}) as
 * separate arrays and allocates the stack buffers of a frame once per decoder. The float build ignores the
 * {@code accum} argument, and so does this implementation. A start band not below the end band, which the reference
 * only asserts against, is rejected with {@link Celt#OPUS_BAD_ARG}.
 */
public final class CeltDecoder {
    /**
     * The length of the decoded history kept per channel ({@code DECODE_BUFFER_SIZE}).
     */
    public static final int DECODE_BUFFER_SIZE = 2048;

    /**
     * The largest pitch lag of the pitch-based concealment ({@code PLC_PITCH_LAG_MAX}).
     */
    private static final int PLC_PITCH_LAG_MAX = 720;

    /**
     * The number of 10 ms frames of 16 kHz history the deep PLC receives on a loss ({@code PLC_UPDATE_FRAMES}).
     */
    private static final int PLC_UPDATE_FRAMES = 4;

    /**
     * The number of 16 kHz samples of {@link #PLC_UPDATE_FRAMES} frames ({@code PLC_UPDATE_SAMPLES}).
     */
    private static final int PLC_UPDATE_SAMPLES = PLC_UPDATE_FRAMES * LpcnetPlc.FRAME_SIZE;

    /**
     * The order of the 3:1 resampling filter between 48 and 16 kHz ({@code SINC_ORDER}).
     */
    private static final int SINC_ORDER = 48;

    /**
     * The pre-emphasis of the deep PLC ({@code PREEMPHASIS} of the LPCNet analysis).
     */
    private static final float DNN_PREEMPHASIS = 0.85f;

    /**
     * The 3:1 resampling filter, {@code h=cos(pi/2*abs(sin([-24:24]/48*pi*23./24)).^2);
     * b=sinc([-24:24]/3*1.02).*h; b=b/sum(b)} ({@code sinc_filter}).
     */
    private static final float[] SINC_FILTER = {
        4.2931e-05f, -0.000190293f, -0.000816132f, -0.000637162f, 0.00141662f, 0.00354764f, 0.00184368f,
        -0.00428274f, -0.00856105f, -0.0034003f, 0.00930201f, 0.0159616f, 0.00489785f, -0.0169649f, -0.0259484f,
        -0.00596856f, 0.0286551f, 0.0405872f, 0.00649994f, -0.0509284f, -0.0716655f, -0.00665212f, 0.134336f,
        0.278927f, 0.339995f, 0.278927f, 0.134336f, -0.00665212f, -0.0716655f, -0.0509284f, 0.00649994f,
        0.0405872f, 0.0286551f, -0.00596856f, -0.0259484f, -0.0169649f, 0.00489785f, 0.0159616f, 0.00930201f,
        -0.0034003f, -0.00856105f, -0.00428274f, 0.00184368f, 0.00354764f, 0.00141662f, -0.000637162f,
        -0.000816132f, -0.000190293f, 4.2931e-05f
    };

    /**
     * Whether the decoder behaves as the reference built with the deep PLC.
     */
    private boolean deepPlc;

    /**
     * The decoder complexity, from 0 to 10; the deep PLC conceals on its own from 5.
     */
    private int complexity;

    /**
     * The 16 kHz deep PLC output not yet resampled ({@code plc_pcm}).
     */
    private final short[] plc_pcm = new short[PLC_UPDATE_SAMPLES];

    /**
     * The number of valid samples of {@link #plc_pcm}.
     */
    private int plc_fill;

    /**
     * The pre-emphasis memory between the deep PLC and the history ({@code plc_preemphasis_mem}).
     */
    private float plc_preemphasis_mem;

    /**
     * The mono 48 kHz history of {@code update_plc_state}.
     */
    private final float[] buf48k = new float[DECODE_BUFFER_SIZE];

    /**
     * The 16 kHz history of {@code update_plc_state}.
     */
    private final short[] buf16k = new short[PLC_UPDATE_SAMPLES];

    /**
     * The pitch-based concealment of the first overlap of each channel, cross-faded into the deep PLC output.
     */
    private final float[] bufCopy;

    /**
     * The smallest pitch lag of the pitch-based concealment ({@code PLC_PITCH_LAG_MIN}).
     */
    private static final int PLC_PITCH_LAG_MIN = 100;

    /**
     * The constant added before de-emphasis to keep the recursion out of denormals
     * ({@code VERY_SMALL}).
     */
    private static final float VERY_SMALL = 1e-30f;

    /**
     * The scale from CELT's internal 16-bit range to nominal floats ({@code 1/CELT_SIG_SCALE}).
     */
    private static final float SCALEOUT = 1 / MathOps.CELT_SIG_SCALE;

    /**
     * The mode.
     */
    private final CeltMode mode;

    /**
     * The MDCT overlap.
     */
    private final int overlap;

    /**
     * The number of output channels.
     */
    private final int channels;

    /**
     * The number of channels coded in the stream.
     */
    private int stream_channels;

    /**
     * The output decimation factor.
     */
    private int downsample;

    /**
     * The first decoded band.
     */
    private int start;

    /**
     * The end band.
     */
    private int end;

    /**
     * Nonzero to disable stereo phase inversion.
     */
    private int disable_inv;

    /**
     * The final range of the last frame, unsigned.
     */
    private int rng;

    /**
     * The pitch lag found by the last concealment.
     */
    private int last_pitch_index;

    /**
     * The number of short blocks concealed since the last good frame.
     */
    private int loss_duration;

    /**
     * Nonzero to use noise-based concealment until two consecutive frames arrive.
     */
    private int skip_plc;

    /**
     * The post-filter period of the current frame.
     */
    private int postfilter_period;

    /**
     * The post-filter period of the previous frame.
     */
    private int postfilter_period_old;

    /**
     * The post-filter gain of the current frame.
     */
    private float postfilter_gain;

    /**
     * The post-filter gain of the previous frame.
     */
    private float postfilter_gain_old;

    /**
     * The post-filter tapset of the current frame.
     */
    private int postfilter_tapset;

    /**
     * The post-filter tapset of the previous frame.
     */
    private int postfilter_tapset_old;

    /**
     * Nonzero when the concealed audio must be pre-filtered and folded before the next frame.
     */
    private int prefilter_and_fold;

    /**
     * The de-emphasis memory of each channel.
     */
    private final float[] preemph_memD = new float[2];

    /**
     * The decoded history of each channel, {@code DECODE_BUFFER_SIZE + overlap} samples each.
     */
    private final float[] decode_mem;

    /**
     * The concealment LPC of each channel.
     */
    private final float[] lpc;

    /**
     * The decoded band energies of both channels.
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
     * The background noise energies.
     */
    private final float[] backgroundLogE;

    /**
     * The decoder used when the caller passes none.
     */
    private final RangeDecoder ownDec = new RangeDecoder();

    /**
     * The band shape scratch.
     */
    private final Bands bands;

    /**
     * The pitch analysis scratch.
     */
    private final Pitch.Scratch pitchScratch = new Pitch.Scratch();

    /**
     * The normalized coefficients of a frame.
     */
    private final float[] X;

    /**
     * The denormalized coefficients of the synthesis.
     */
    private final float[] freq;

    /**
     * The FFT scratch of the inverse MDCT.
     */
    private final float[] mdctScratch;

    /**
     * The de-emphasis output before decimation.
     */
    private final float[] deemphScratch;

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
     * The intensity band returned by the allocation.
     */
    private final int[] intensityOut = new int[1];

    /**
     * The dual stereo flag returned by the allocation.
     */
    private final int[] dualStereoOut = new int[1];

    /**
     * The balance returned by the allocation.
     */
    private final int[] balanceOut = new int[1];

    /**
     * The 2x-decimated history of the concealment pitch search.
     */
    private final float[] lpPitchBuf = new float[DECODE_BUFFER_SIZE >> 1];

    /**
     * The excitation of the pitch-based concealment, {@code CELT_LPC_ORDER} samples of history
     * first.
     */
    private final float[] excBuf = new float[Celt.MAX_PERIOD + CeltLpc.CELT_LPC_ORDER];

    /**
     * The whitened excitation of the pitch-based concealment.
     */
    private final float[] firTmp = new float[Celt.MAX_PERIOD];

    /**
     * The autocorrelation of the concealment LPC.
     */
    private final float[] acBuf = new float[CeltLpc.CELT_LPC_ORDER + 1];

    /**
     * The synthesis filter memory of the concealment.
     */
    private final float[] lpcMem = new float[CeltLpc.CELT_LPC_ORDER];

    /**
     * The pre-filtered overlap of the concealment fold.
     */
    private final float[] etmp;

    /**
     * Creates a decoder ({@code celt_decoder_init}).
     *
     * @param Fs       the output rate: 8000, 12000, 16000, 24000 or 48000
     * @param channels the number of output channels, 1 or 2
     * @throws IllegalArgumentException if the rate or channel count is unsupported
     */
    public CeltDecoder(int Fs, int channels) {
        if (channels < 1 || channels > 2) {
            throw new IllegalArgumentException("invalid channel count " + channels);
        }
        var ds = Celt.resamplingFactor(Fs);
        if (ds == 0) {
            throw new IllegalArgumentException("unsupported sampling rate " + Fs);
        }
        this.mode = CeltMode.MODE_48000_960;
        this.overlap = mode.overlap;
        this.stream_channels = this.channels = channels;
        this.downsample = ds;
        this.start = 0;
        this.end = mode.effEBands;
        this.disable_inv = channels == 1 ? 1 : 0;
        var nb = mode.nbEBands;
        var maxN = mode.shortMdctSize << mode.maxLM;
        this.decode_mem = new float[channels * (DECODE_BUFFER_SIZE + overlap)];
        this.lpc = new float[channels * CeltLpc.CELT_LPC_ORDER];
        this.oldBandE = new float[2 * nb];
        this.oldLogE = new float[2 * nb];
        this.oldLogE2 = new float[2 * nb];
        this.backgroundLogE = new float[2 * nb];
        this.bands = new Bands(mode);
        this.X = new float[2 * maxN];
        this.freq = new float[maxN];
        this.deemphScratch = new float[maxN];
        this.mdctScratch = new float[maxN];
        this.fine_quant = new int[nb];
        this.pulses = new int[nb];
        this.cap = new int[nb];
        this.offsets = new int[nb];
        this.fine_priority = new int[nb];
        this.tf_res = new int[nb];
        this.collapse_masks = new int[2 * nb];
        this.allocScratch = new int[][]{new int[nb], new int[nb], new int[nb], new int[nb]};
        this.etmp = new float[overlap];
        this.bufCopy = new float[channels * overlap];
        resetState();
    }

    /**
     * Selects whether the decoder behaves as the reference built with the deep PLC, and the complexity that enables
     * it ({@code OPUS_SET_COMPLEXITY}).
     *
     * @param enabled whether the deep PLC is built in
     * @param complexity the decoder complexity, from 0 to 10
     */
    public void setDeepPlc(boolean enabled, int complexity) {
        this.deepPlc = enabled;
        this.complexity = complexity;
    }

    /**
     * Returns the index in {@link #decode_mem} of the first history sample of a channel.
     *
     * @param c the channel
     * @return the index
     */
    private int decodeMem(int c) {
        return c * (DECODE_BUFFER_SIZE + overlap);
    }

    /**
     * Resets the decoder to its initial state, keeping the configuration
     * ({@code OPUS_RESET_STATE}).
     */
    public void resetState() {
        rng = 0;
        last_pitch_index = 0;
        loss_duration = 0;
        skip_plc = 0;
        postfilter_period = 0;
        postfilter_period_old = 0;
        postfilter_gain = 0;
        postfilter_gain_old = 0;
        postfilter_tapset = 0;
        postfilter_tapset_old = 0;
        prefilter_and_fold = 0;
        preemph_memD[0] = 0;
        preemph_memD[1] = 0;
        Arrays.fill(decode_mem, 0);
        Arrays.fill(lpc, 0);
        Arrays.fill(oldBandE, 0);
        Arrays.fill(backgroundLogE, 0);
        for (var i = 0; i < 2 * mode.nbEBands; i++) {
            oldLogE[i] = oldLogE2[i] = -28.f;
        }
        skip_plc = 1;
        Arrays.fill(plc_pcm, (short) 0);
        plc_fill = 0;
        plc_preemphasis_mem = 0;
    }

    /**
     * Sets the first decoded band ({@code CELT_SET_START_BAND}).
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
     * Returns the final range of the last decoded frame ({@code OPUS_GET_FINAL_RANGE}).
     *
     * @return the unsigned 32-bit range
     */
    public int getFinalRange() {
        return rng;
    }

    /**
     * Returns the post-filter pitch period of the last frame ({@code OPUS_GET_PITCH}).
     *
     * @return the period in samples at 48 kHz
     */
    public int getPitch() {
        return postfilter_period;
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
     * Enables or disables stereo phase inversion ({@code OPUS_SET_PHASE_INVERSION_DISABLED}).
     *
     * @param disabled {@code true} to disable phase inversion
     */
    public void setPhaseInversionDisabled(boolean disabled) {
        disable_inv = disabled ? 1 : 0;
    }

    /**
     * Applies de-emphasis, decimation and scaling to the synthesized signal ({@code deemphasis}).
     *
     * @param outSyn the synthesis buffer ({@link #decode_mem})
     * @param in0    the index of the first channel's signal
     * @param in1    the index of the second channel's signal
     * @param pcm    receives the interleaved output
     * @param pcmOff the index of the first output sample
     * @param N      the number of 48 kHz samples
     * @param C      the number of channels
     * @param ds     the decimation factor
     * @param coef   the pre-emphasis coefficients
     * @param mem    the de-emphasis memory of each channel
     */
    private void deemphasis(float[] outSyn, int in0, int in1, float[] pcm, int pcmOff, int N, int C, int ds,
                            float[] coef, float[] mem) {
        var coef0 = coef[0];
        if (ds == 1 && C == 2) {
            var m0 = mem[0];
            var m1 = mem[1];
            for (var j = 0; j < N; j++) {
                var tmp0 = outSyn[in0 + j] + VERY_SMALL + m0;
                var tmp1 = outSyn[in1 + j] + VERY_SMALL + m1;
                m0 = coef0 * tmp0;
                m1 = coef0 * tmp1;
                pcm[pcmOff + 2 * j] = tmp0 * SCALEOUT;
                pcm[pcmOff + 2 * j + 1] = tmp1 * SCALEOUT;
            }
            mem[0] = m0;
            mem[1] = m1;
            return;
        }
        var Nd = N / ds;
        var scratch = deemphScratch;
        var c = 0;
        do {
            var m = mem[c];
            var x = c == 0 ? in0 : in1;
            var y = pcmOff + c;
            if (ds > 1) {
                for (var j = 0; j < N; j++) {
                    var tmp = outSyn[x + j] + VERY_SMALL + m;
                    m = coef0 * tmp;
                    scratch[j] = tmp;
                }
                mem[c] = m;
                for (var j = 0; j < Nd; j++) {
                    pcm[y + j * C] = scratch[j * ds] * SCALEOUT;
                }
            } else {
                for (var j = 0; j < N; j++) {
                    var tmp = outSyn[x + j] + VERY_SMALL + m;
                    m = coef0 * tmp;
                    pcm[y + j * C] = tmp * SCALEOUT;
                }
                mem[c] = m;
            }
        } while (++c < C);
    }

    /**
     * Denormalizes the decoded bands and runs the inverse MDCTs into the history
     * ({@code celt_synthesis}).
     *
     * @param Xn          the normalized coefficients
     * @param outSyn0     the index in {@link #decode_mem} of the first channel's output
     * @param outSyn1     the index in {@link #decode_mem} of the second channel's output
     * @param bandE       the band log-energies
     * @param start       the first band
     * @param effEnd      the end of the bands holding signal
     * @param C           the number of coded channels
     * @param CC          the number of output channels
     * @param isTransient nonzero for short blocks
     * @param LM          the frame size shift
     * @param downsample  the output decimation factor
     * @param silence     nonzero for a silent frame
     */
    private void celtSynthesis(float[] Xn, int outSyn0, int outSyn1, float[] bandE, int start, int effEnd, int C,
                               int CC, int isTransient, int LM, int downsample, int silence) {
        int M;
        int B;
        int NB;
        int shift;
        var nbEBands = mode.nbEBands;
        var N = mode.shortMdctSize << LM;
        var out = decode_mem;
        M = 1 << LM;
        if (isTransient != 0) {
            B = M;
            NB = mode.shortMdctSize;
            shift = mode.maxLM;
        } else {
            B = 1;
            NB = mode.shortMdctSize << LM;
            shift = mode.maxLM - LM;
        }
        if (CC == 2 && C == 1) {
            Bands.denormalizeBands(mode, Xn, 0, freq, 0, bandE, 0, start, effEnd, M, downsample, silence);
            var freq2 = outSyn1 + overlap / 2;
            System.arraycopy(freq, 0, out, freq2, N);
            for (var b = 0; b < B; b++) {
                Mdct.cltMdctBackward(mode.mdct, out, freq2 + b, out, outSyn0 + NB * b, mode.window, overlap, shift, B, mdctScratch);
            }
            for (var b = 0; b < B; b++) {
                Mdct.cltMdctBackward(mode.mdct, freq, b, out, outSyn1 + NB * b, mode.window, overlap, shift, B, mdctScratch);
            }
        } else if (CC == 1 && C == 2) {
            var freq2 = outSyn0 + overlap / 2;
            Bands.denormalizeBands(mode, Xn, 0, freq, 0, bandE, 0, start, effEnd, M, downsample, silence);
            Bands.denormalizeBands(mode, Xn, N, out, freq2, bandE, nbEBands, start, effEnd, M, downsample, silence);
            for (var i = 0; i < N; i++) {
                freq[i] = .5f * freq[i] + .5f * out[freq2 + i];
            }
            for (var b = 0; b < B; b++) {
                Mdct.cltMdctBackward(mode.mdct, freq, b, out, outSyn0 + NB * b, mode.window, overlap, shift, B, mdctScratch);
            }
        } else {
            var c = 0;
            do {
                Bands.denormalizeBands(mode, Xn, c * N, freq, 0, bandE, c * nbEBands, start, effEnd, M, downsample,
                        silence);
                var outSyn = c == 0 ? outSyn0 : outSyn1;
                for (var b = 0; b < B; b++) {
                    Mdct.cltMdctBackward(mode.mdct, freq, b, out, outSyn + NB * b, mode.window, overlap, shift, B, mdctScratch);
                }
            } while (++c < CC);
        }
    }

    /**
     * Decodes the per-band TF resolution changes ({@code tf_decode}).
     *
     * @param start       the first band
     * @param end         the end band
     * @param isTransient nonzero for short blocks
     * @param tfRes       receives the TF change of each band
     * @param LM          the frame size shift
     * @param dec         the range decoder
     */
    private static void tfDecode(int start, int end, int isTransient, int[] tfRes, int LM, RangeDecoder dec) {
        int i, curr, tfSelect;
        int tfSelectRsv;
        int tfChanged;
        int logp;
        int budget;
        int tell;
        budget = dec.storage * 8;
        tell = dec.tell();
        logp = isTransient != 0 ? 2 : 4;
        tfSelectRsv = LM > 0 && Integer.compareUnsigned(tell + logp + 1, budget) <= 0 ? 1 : 0;
        budget -= tfSelectRsv;
        tfChanged = curr = 0;
        for (i = start; i < end; i++) {
            if (Integer.compareUnsigned(tell + logp, budget) <= 0) {
                curr ^= dec.decBitLogp(logp);
                tell = dec.tell();
                tfChanged |= curr;
            }
            tfRes[i] = curr;
            logp = isTransient != 0 ? 4 : 5;
        }
        tfSelect = 0;
        if (tfSelectRsv != 0 && Celt.TF_SELECT_TABLE[LM][4 * isTransient + tfChanged]
                != Celt.TF_SELECT_TABLE[LM][4 * isTransient + 2 + tfChanged]) {
            tfSelect = dec.decBitLogp(1);
        }
        for (i = start; i < end; i++) {
            tfRes[i] = Celt.TF_SELECT_TABLE[LM][4 * isTransient + 2 * tfSelect + tfRes[i]];
        }
    }

    /**
     * Finds the pitch lag of the decoded history for the concealment
     * ({@code celt_plc_pitch_search}).
     *
     * @param C the number of channels
     * @return the pitch lag
     */
    private int celtPlcPitchSearch(int C) {
        Pitch.pitchDownsample(decode_mem, decodeMem(0), decodeMem(1), lpPitchBuf, DECODE_BUFFER_SIZE, C,
                pitchScratch);
        var pitchIndex = Pitch.pitchSearch(lpPitchBuf, PLC_PITCH_LAG_MAX >> 1, lpPitchBuf, 0,
                DECODE_BUFFER_SIZE - PLC_PITCH_LAG_MAX, PLC_PITCH_LAG_MAX - PLC_PITCH_LAG_MIN, pitchScratch);
        return PLC_PITCH_LAG_MAX - pitchIndex;
    }

    /**
     * Applies the pre-filter to the concealed overlap and simulates TDAC so it blends with the
     * next decoded frame ({@code prefilter_and_fold}).
     *
     * @param N the frame size at 48 kHz
     */
    private void prefilterAndFold(int N) {
        var CC = channels;
        var window = mode.window;
        var c = 0;
        do {
            var dm = decodeMem(c);
            Celt.combFilter(etmp, 0, decode_mem, dm + DECODE_BUFFER_SIZE - N, postfilter_period_old,
                    postfilter_period, overlap, -postfilter_gain_old, -postfilter_gain, postfilter_tapset_old,
                    postfilter_tapset, null, 0);
            for (var i = 0; i < overlap / 2; i++) {
                decode_mem[dm + DECODE_BUFFER_SIZE - N + i] = window[i] * etmp[overlap - 1 - i]
                        + window[overlap - i - 1] * etmp[i];
            }
        } while (++c < CC);
    }

    /**
     * Conceals a lost frame ({@code celt_decode_lost}).
     *
     * @param N  the frame size at 48 kHz
     * @param LM the frame size shift
     */
    private void celtDecodeLost(int N, int LM, LpcnetPlc lpcnet) {
        var C = channels;
        var nbEBands = mode.nbEBands;
        var eBands = mode.eBands;
        var outSyn0 = decodeMem(0) + DECODE_BUFFER_SIZE - N;
        var outSyn1 = decodeMem(C > 1 ? 1 : 0) + DECODE_BUFFER_SIZE - N;
        var lossDuration = loss_duration;
        var st = start;
        boolean noiseBased;
        if (lpcnet != null) {
            noiseBased = st != 0 || (lpcnet.fec_fill_pos == 0 && (skip_plc != 0 || lossDuration >= 80));
        } else {
            noiseBased = lossDuration >= 40 || st != 0 || skip_plc != 0;
        }
        if (noiseBased) {
            int seed;
            int end;
            int effEnd;
            float decay;
            end = this.end;
            effEnd = Math.max(st, Math.min(end, mode.effEBands));
            var c = 0;
            do {
                var dm = decodeMem(c);
                System.arraycopy(decode_mem, dm + N, decode_mem, dm, DECODE_BUFFER_SIZE - N + overlap);
            } while (++c < C);
            if (prefilter_and_fold != 0) {
                prefilterAndFold(N);
            }
            decay = lossDuration == 0 ? 1.5f : .5f;
            c = 0;
            do {
                for (var i = st; i < end; i++) {
                    oldBandE[c * nbEBands + i] = MathOps.max(backgroundLogE[c * nbEBands + i],
                            oldBandE[c * nbEBands + i] - decay);
                }
            } while (++c < C);
            seed = rng;
            for (c = 0; c < C; c++) {
                for (var i = st; i < effEnd; i++) {
                    var boffs = N * c + (eBands[i] << LM);
                    var blen = (eBands[i + 1] - eBands[i]) << LM;
                    for (var j = 0; j < blen; j++) {
                        seed = Bands.celtLcgRand(seed);
                        X[boffs + j] = (float) (seed >> 20);
                    }
                    Vq.renormalizeVector(X, boffs, blen, 1.0f);
                }
            }
            rng = seed;
            celtSynthesis(X, outSyn0, outSyn1, oldBandE, st, effEnd, C, C, 0, LM, downsample, 0);
            prefilter_and_fold = 0;
            skip_plc = 1;
        } else {
            int excLength;
            var window = mode.window;
            var fade = 1.0f;
            int pitchIndex;
            if (lossDuration == 0) {
                if (lpcnet != null) {
                    updatePlcState(lpcnet, C);
                }
                last_pitch_index = pitchIndex = celtPlcPitchSearch(C);
            } else {
                pitchIndex = last_pitch_index;
                fade = .8f;
            }
            excLength = Math.min(2 * pitchIndex, Celt.MAX_PERIOD);
            var exc = excBuf;
            final var excOff = CeltLpc.CELT_LPC_ORDER;
            var c = 0;
            do {
                float decay;
                float attenuation;
                var S1 = 0f;
                var buf = decodeMem(c);
                int extrapolationOffset;
                int extrapolationLen;
                for (var i = 0; i < Celt.MAX_PERIOD + CeltLpc.CELT_LPC_ORDER; i++) {
                    exc[excOff + i - CeltLpc.CELT_LPC_ORDER] =
                            decode_mem[buf + DECODE_BUFFER_SIZE - Celt.MAX_PERIOD - CeltLpc.CELT_LPC_ORDER + i];
                }
                if (lossDuration == 0) {
                    var ac = acBuf;
                    CeltLpc.celtAutocorr(exc, excOff, ac, window, overlap, CeltLpc.CELT_LPC_ORDER, Celt.MAX_PERIOD);
                    ac[0] *= 1.0001f;
                    for (var i = 1; i <= CeltLpc.CELT_LPC_ORDER; i++) {
                        ac[i] -= ac[i] * (0.008f * 0.008f) * i * i;
                    }
                    CeltLpc.celtLpc(lpc, c * CeltLpc.CELT_LPC_ORDER, ac, CeltLpc.CELT_LPC_ORDER);
                }
                CeltLpc.celtFir(exc, excOff + Celt.MAX_PERIOD - excLength, lpc, c * CeltLpc.CELT_LPC_ORDER, firTmp, 0,
                        excLength, CeltLpc.CELT_LPC_ORDER);
                System.arraycopy(firTmp, 0, exc, excOff + Celt.MAX_PERIOD - excLength, excLength);
                {
                    var E1 = 1f;
                    var E2 = 1f;
                    var decayLength = excLength >> 1;
                    for (var i = 0; i < decayLength; i++) {
                        var e = exc[excOff + Celt.MAX_PERIOD - decayLength + i];
                        E1 += e * e;
                        e = exc[excOff + Celt.MAX_PERIOD - 2 * decayLength + i];
                        E2 += e * e;
                    }
                    E1 = MathOps.min(E1, E2);
                    decay = MathOps.celtSqrt(E1 / E2);
                }
                System.arraycopy(decode_mem, buf + N, decode_mem, buf, DECODE_BUFFER_SIZE - N);
                extrapolationOffset = Celt.MAX_PERIOD - pitchIndex;
                extrapolationLen = N + overlap;
                attenuation = fade * decay;
                for (int i = 0, j = 0; i < extrapolationLen; i++, j++) {
                    float tmp;
                    if (j >= pitchIndex) {
                        j -= pitchIndex;
                        attenuation = attenuation * decay;
                    }
                    decode_mem[buf + DECODE_BUFFER_SIZE - N + i] = attenuation * exc[excOff + extrapolationOffset + j];
                    tmp = decode_mem[buf + DECODE_BUFFER_SIZE - Celt.MAX_PERIOD - N + extrapolationOffset + j];
                    S1 += tmp * tmp;
                }
                for (var i = 0; i < CeltLpc.CELT_LPC_ORDER; i++) {
                    lpcMem[i] = decode_mem[buf + DECODE_BUFFER_SIZE - N - 1 - i];
                }
                CeltLpc.celtIir(decode_mem, buf + DECODE_BUFFER_SIZE - N, lpc, c * CeltLpc.CELT_LPC_ORDER, decode_mem,
                        buf + DECODE_BUFFER_SIZE - N, extrapolationLen, CeltLpc.CELT_LPC_ORDER, lpcMem);
                {
                    var S2 = 0f;
                    for (var i = 0; i < extrapolationLen; i++) {
                        var tmp = decode_mem[buf + DECODE_BUFFER_SIZE - N + i];
                        S2 += tmp * tmp;
                    }
                    if (!(S1 > 0.2f * S2)) {
                        for (var i = 0; i < extrapolationLen; i++) {
                            decode_mem[buf + DECODE_BUFFER_SIZE - N + i] = 0;
                        }
                    } else if (S1 < S2) {
                        var ratio = MathOps.celtSqrt((S1 + 1) / (S2 + 1));
                        for (var i = 0; i < overlap; i++) {
                            var tmpG = 1.0f - window[i] * (1.0f - ratio);
                            decode_mem[buf + DECODE_BUFFER_SIZE - N + i] = tmpG * decode_mem[buf + DECODE_BUFFER_SIZE - N + i];
                        }
                        for (var i = overlap; i < extrapolationLen; i++) {
                            decode_mem[buf + DECODE_BUFFER_SIZE - N + i] = ratio * decode_mem[buf + DECODE_BUFFER_SIZE - N + i];
                        }
                    }
                }
            } while (++c < C);
            if (lpcnet != null && (complexity >= 5 || lpcnet.fec_fill_pos > 0)) {
                deepConceal(lpcnet, N, C, lossDuration, window);
            }
            prefilter_and_fold = 1;
        }
        loss_duration = Math.min(10000, lossDuration + (1 << LM));
    }

    /**
     * Feeds the deep PLC with the last 40 ms of history, resampled to 16 kHz, without consuming queued DRED
     * features ({@code update_plc_state}).
     */
    private void updatePlcState(LpcnetPlc lpcnet, int CC) {
        var d0 = decodeMem(0);
        if (CC == 1) {
            System.arraycopy(decode_mem, d0, buf48k, 0, DECODE_BUFFER_SIZE);
        } else {
            var d1 = decodeMem(1);
            for (var i = 0; i < DECODE_BUFFER_SIZE; i++) {
                buf48k[i] = (float) (.5 * (decode_mem[d0 + i] + decode_mem[d1 + i]));
            }
        }
        for (var i = 1; i < DECODE_BUFFER_SIZE; i++) {
            buf48k[i] += DNN_PREEMPHASIS * buf48k[i - 1];
        }
        plc_preemphasis_mem = buf48k[DECODE_BUFFER_SIZE - 1];
        var offset = DECODE_BUFFER_SIZE - SINC_ORDER - 1 - 3 * (PLC_UPDATE_SAMPLES - 1);
        for (var i = 0; i < PLC_UPDATE_SAMPLES; i++) {
            var sum = 0f;
            for (var j = 0; j < SINC_ORDER + 1; j++) {
                sum += buf48k[3 * i + j + offset] * SINC_FILTER[j];
            }
            var low = -32767.f > sum ? -32767.f : sum;
            var clamped = 32767.f < low ? 32767.f : low;
            buf16k[i] = (short) MathOps.float2int(clamped);
        }
        var readPos = lpcnet.fec_read_pos;
        var skip = lpcnet.fec_skip;
        for (var i = 0; i < PLC_UPDATE_FRAMES; i++) {
            lpcnet.update(buf16k, LpcnetPlc.FRAME_SIZE * i);
        }
        lpcnet.fec_read_pos = readPos;
        lpcnet.fec_skip = skip;
    }

    /**
     * Replaces the pitch-based concealment of a frame by the deep PLC output resampled to 48 kHz, cross-faded over
     * the first overlap of a loss; the deep PLC is mono, so both channels get the same signal.
     */
    private void deepConceal(LpcnetPlc lpcnet, int N, int C, int lossDuration, float[] window) {
        var buf = decodeMem(0);
        var c = 0;
        do {
            System.arraycopy(decode_mem, decodeMem(c) + DECODE_BUFFER_SIZE - N, bufCopy, c * overlap, overlap);
        } while (++c < C);
        var samplesNeeded16k = (N + SINC_ORDER + overlap) / 3;
        if (lossDuration == 0) {
            plc_fill = 0;
        }
        while (plc_fill < samplesNeeded16k) {
            lpcnet.conceal(plc_pcm, plc_fill);
            plc_fill += LpcnetPlc.FRAME_SIZE;
        }
        for (var i = 0; i < (N + overlap) / 3; i++) {
            var sum = 0f;
            for (var j = 0; j < 17; j++) {
                sum += 3 * plc_pcm[i + j] * SINC_FILTER[3 * j];
            }
            decode_mem[buf + DECODE_BUFFER_SIZE - N + 3 * i] = sum;
            sum = 0f;
            for (var j = 0; j < 16; j++) {
                sum += 3 * plc_pcm[i + j + 1] * SINC_FILTER[3 * j + 2];
            }
            decode_mem[buf + DECODE_BUFFER_SIZE - N + 3 * i + 1] = sum;
            sum = 0f;
            for (var j = 0; j < 16; j++) {
                sum += 3 * plc_pcm[i + j + 1] * SINC_FILTER[3 * j + 1];
            }
            decode_mem[buf + DECODE_BUFFER_SIZE - N + 3 * i + 2] = sum;
        }
        System.arraycopy(plc_pcm, N / 3, plc_pcm, 0, plc_fill - N / 3);
        plc_fill -= N / 3;
        for (var i = 0; i < N; i++) {
            var tmp = decode_mem[buf + DECODE_BUFFER_SIZE - N + i];
            decode_mem[buf + DECODE_BUFFER_SIZE - N + i] -= DNN_PREEMPHASIS * plc_preemphasis_mem;
            plc_preemphasis_mem = tmp;
        }
        var overlapMem = plc_preemphasis_mem;
        for (var i = 0; i < overlap; i++) {
            var tmp = decode_mem[buf + DECODE_BUFFER_SIZE + i];
            decode_mem[buf + DECODE_BUFFER_SIZE + i] -= DNN_PREEMPHASIS * overlapMem;
            overlapMem = tmp;
        }
        if (C == 2) {
            System.arraycopy(decode_mem, buf, decode_mem, decodeMem(1), DECODE_BUFFER_SIZE + overlap);
        }
        c = 0;
        do {
            if (lossDuration == 0) {
                var d = decodeMem(c) + DECODE_BUFFER_SIZE - N;
                for (var i = 0; i < overlap; i++) {
                    decode_mem[d + i] = (1 - window[i]) * bufCopy[c * overlap + i] + window[i] * decode_mem[d + i];
                }
            }
        } while (++c < C);
    }

    /**
     * Decodes one CELT frame, or conceals a lost one ({@code celt_decode_with_ec}).
     *
     * <p>A {@code null} {@code data} or a {@code len} of at most 1 runs the concealment. When
     * {@code dec} is {@code null} the frame is read from {@code data}; otherwise the frame
     * continues the given range decoder (hybrid mode), which must have been initialized on the
     * same data.
     *
     * @param data      the frame, or {@code null} to conceal
     * @param dataOff   the index of the first frame byte
     * @param len       the frame length in bytes, at most 1275
     * @param pcm       receives {@code frameSize * channels} interleaved samples
     * @param pcmOff    the index of the first output sample
     * @param frameSize the frame size at the output rate
     * @param dec       the range decoder to continue, or {@code null}
     * @param accum     ignored, as in the float build of the reference
     * @return the number of samples per channel written, or a negative Opus error code
     */
    public int decodeWithEc(byte[] data, int dataOff, int len, float[] pcm, int pcmOff, int frameSize,
                            RangeDecoder dec, int accum) {
        return decodeWithEc(data, dataOff, len, pcm, pcmOff, frameSize, dec, accum, null);
    }

    /**
     * Decodes one CELT frame, or conceals a lost one with the deep PLC available
     * ({@code celt_decode_with_ec_dred}).
     *
     * @param data      the frame, or {@code null} to conceal
     * @param dataOff   the index of the first frame byte
     * @param len       the frame length in bytes, at most 1275
     * @param pcm       receives {@code frameSize * channels} interleaved samples
     * @param pcmOff    the index of the first output sample
     * @param frameSize the frame size at the output rate
     * @param dec       the range decoder to continue, or {@code null}
     * @param accum     ignored, as in the float build of the reference
     * @param lpcnet    the deep PLC of the Opus decoder, or {@code null} outside deep PLC mode
     * @return the number of samples per channel written, or a negative Opus error code
     */
    public int decodeWithEc(byte[] data, int dataOff, int len, float[] pcm, int pcmOff, int frameSize,
                            RangeDecoder dec, int accum, LpcnetPlc lpcnet) {
        if (!deepPlc) {
            lpcnet = null;
        }
        int c, i, N;
        int spreadDecision;
        int bits;
        int shortBlocks;
        int isTransient;
        int intraEner;
        var CC = channels;
        int LM, M;
        int st;
        int en;
        int effEnd;
        int codedBands;
        int allocTrim;
        int postfilterPitch;
        float postfilterGain;
        int intensity;
        int dualStereo;
        int totalBits;
        int balance;
        int tell;
        int dynallocLogp;
        int postfilterTapset;
        int antiCollapseRsv;
        var antiCollapseOn = 0;
        int silence;
        var C = stream_channels;
        var nbEBands = mode.nbEBands;
        var eBands = mode.eBands;
        float maxBackgroundIncrease;
        st = start;
        en = end;
        frameSize *= downsample;
        for (LM = 0; LM <= mode.maxLM; LM++) {
            if (mode.shortMdctSize << LM == frameSize) {
                break;
            }
        }
        if (LM > mode.maxLM) {
            return Celt.OPUS_BAD_ARG;
        }
        M = 1 << LM;
        if (len < 0 || len > 1275 || pcm == null || st >= en) {
            return Celt.OPUS_BAD_ARG;
        }
        N = M * mode.shortMdctSize;
        var outSyn0 = decodeMem(0) + DECODE_BUFFER_SIZE - N;
        var outSyn1 = decodeMem(CC > 1 ? 1 : 0) + DECODE_BUFFER_SIZE - N;
        effEnd = en;
        if (effEnd > mode.effEBands) {
            effEnd = mode.effEBands;
        }
        if (data == null || len <= 1) {
            celtDecodeLost(N, LM, lpcnet);
            deemphasis(decode_mem, outSyn0, outSyn1, pcm, pcmOff, N, CC, downsample, mode.preemph, preemph_memD);
            return frameSize / downsample;
        }
        if (lpcnet != null) {
            lpcnet.blend = false;
        }
        if (loss_duration == 0) {
            skip_plc = 0;
        }
        if (dec == null) {
            ownDec.init(data, dataOff, len);
            dec = ownDec;
        }
        if (C == 1) {
            for (i = 0; i < nbEBands; i++) {
                oldBandE[i] = MathOps.max(oldBandE[i], oldBandE[nbEBands + i]);
            }
        }
        totalBits = len * 8;
        tell = dec.tell();
        if (tell >= totalBits) {
            silence = 1;
        } else if (tell == 1) {
            silence = dec.decBitLogp(15);
        } else {
            silence = 0;
        }
        if (silence != 0) {
            tell = len * 8;
            dec.nbitsTotal += tell - dec.tell();
        }
        postfilterGain = 0;
        postfilterPitch = 0;
        postfilterTapset = 0;
        if (st == 0 && tell + 16 <= totalBits) {
            if (dec.decBitLogp(1) != 0) {
                int qg, octave;
                octave = dec.decUint(6);
                postfilterPitch = (16 << octave) + dec.decBits(4 + octave) - 1;
                qg = dec.decBits(3);
                if (dec.tell() + 2 <= totalBits) {
                    postfilterTapset = dec.decIcdf(Celt.TAPSET_ICDF, 2);
                }
                postfilterGain = .09375f * (qg + 1);
            }
            tell = dec.tell();
        }
        if (LM > 0 && tell + 3 <= totalBits) {
            isTransient = dec.decBitLogp(3);
            tell = dec.tell();
        } else {
            isTransient = 0;
        }
        if (isTransient != 0) {
            shortBlocks = M;
        } else {
            shortBlocks = 0;
        }
        intraEner = tell + 3 <= totalBits ? dec.decBitLogp(3) : 0;
        if (intraEner == 0 && loss_duration != 0) {
            c = 0;
            do {
                var safety = 0f;
                var missing = Math.min(10, loss_duration >> LM);
                if (LM == 0) {
                    safety = 1.5f;
                } else if (LM == 1) {
                    safety = .5f;
                }
                for (i = st; i < en; i++) {
                    var idx = c * nbEBands + i;
                    if (oldBandE[idx] < MathOps.max(oldLogE[idx], oldLogE2[idx])) {
                        float slope;
                        float E0, E1, E2;
                        E0 = oldBandE[idx];
                        E1 = oldLogE[idx];
                        E2 = oldLogE2[idx];
                        slope = MathOps.max(E1 - E0, .5f * (E2 - E0));
                        E0 -= MathOps.max(0, (1 + missing) * slope);
                        oldBandE[idx] = MathOps.max(-20.f, E0);
                    } else {
                        oldBandE[idx] = MathOps.min(MathOps.min(oldBandE[idx], oldLogE[idx]), oldLogE2[idx]);
                    }
                    oldBandE[idx] -= safety;
                }
            } while (++c < 2);
        }
        QuantBands.unquantCoarseEnergy(mode, st, en, oldBandE, intraEner, dec, C, LM);
        tfDecode(st, en, isTransient, tf_res, LM, dec);
        tell = dec.tell();
        spreadDecision = Celt.SPREAD_NORMAL;
        if (tell + 4 <= totalBits) {
            spreadDecision = dec.decIcdf(Celt.SPREAD_ICDF, 5);
        }
        Celt.initCaps(mode, cap, LM, C);
        dynallocLogp = 6;
        totalBits <<= EntropyCoder.BITRES;
        tell = dec.tellFrac();
        for (i = st; i < en; i++) {
            int width, quanta;
            int dynallocLoopLogp;
            int boost;
            width = C * (eBands[i + 1] - eBands[i]) << LM;
            quanta = Math.min(width << EntropyCoder.BITRES, Math.max(6 << EntropyCoder.BITRES, width));
            dynallocLoopLogp = dynallocLogp;
            boost = 0;
            while (tell + (dynallocLoopLogp << EntropyCoder.BITRES) < totalBits && boost < cap[i]) {
                int flag;
                flag = dec.decBitLogp(dynallocLoopLogp);
                tell = dec.tellFrac();
                if (flag == 0) {
                    break;
                }
                boost += quanta;
                totalBits -= quanta;
                dynallocLoopLogp = 1;
            }
            offsets[i] = boost;
            if (boost > 0) {
                dynallocLogp = Math.max(2, dynallocLogp - 1);
            }
        }
        allocTrim = tell + (6 << EntropyCoder.BITRES) <= totalBits ? dec.decIcdf(Celt.TRIM_ICDF, 7) : 5;
        bits = ((len * 8) << EntropyCoder.BITRES) - dec.tellFrac() - 1;
        antiCollapseRsv = isTransient != 0 && LM >= 2 && bits >= ((LM + 2) << EntropyCoder.BITRES)
                ? (1 << EntropyCoder.BITRES) : 0;
        bits -= antiCollapseRsv;
        intensityOut[0] = 0;
        dualStereoOut[0] = 0;
        codedBands = Rate.cltComputeAllocation(mode, st, en, offsets, cap, allocTrim, intensityOut, dualStereoOut,
                bits, balanceOut, pulses, fine_quant, fine_priority, C, LM, dec, 0, 0, 0, allocScratch);
        intensity = intensityOut[0];
        dualStereo = dualStereoOut[0];
        balance = balanceOut[0];
        QuantBands.unquantFineEnergy(mode, st, en, oldBandE, fine_quant, dec, C);
        c = 0;
        do {
            var dm = decodeMem(c);
            System.arraycopy(decode_mem, dm + N, decode_mem, dm, DECODE_BUFFER_SIZE - N + overlap);
        } while (++c < CC);
        rng = bands.quantAllBands(0, mode, st, en, X, C == 2 ? N : -1, collapse_masks, null, pulses, shortBlocks,
                spreadDecision, dualStereo, intensity, tf_res, len * (8 << EntropyCoder.BITRES) - antiCollapseRsv,
                balance, dec, LM, codedBands, rng, 0, disable_inv);
        if (antiCollapseRsv > 0) {
            antiCollapseOn = dec.decBits(1);
        }
        QuantBands.unquantEnergyFinalize(mode, st, en, oldBandE, fine_quant, fine_priority, len * 8 - dec.tell(),
                dec, C);
        if (antiCollapseOn != 0) {
            Bands.antiCollapse(mode, X, collapse_masks, LM, C, N, st, en, oldBandE, oldLogE, oldLogE2, pulses, rng);
        }
        if (silence != 0) {
            for (i = 0; i < C * nbEBands; i++) {
                oldBandE[i] = -28.f;
            }
        }
        if (prefilter_and_fold != 0) {
            prefilterAndFold(N);
        }
        celtSynthesis(X, outSyn0, outSyn1, oldBandE, st, effEnd, C, CC, isTransient, LM, downsample, silence);
        c = 0;
        do {
            var outSyn = c == 0 ? outSyn0 : outSyn1;
            postfilter_period = Math.max(postfilter_period, Celt.COMBFILTER_MINPERIOD);
            postfilter_period_old = Math.max(postfilter_period_old, Celt.COMBFILTER_MINPERIOD);
            Celt.combFilter(decode_mem, outSyn, decode_mem, outSyn, postfilter_period_old, postfilter_period,
                    mode.shortMdctSize, postfilter_gain_old, postfilter_gain, postfilter_tapset_old,
                    postfilter_tapset, mode.window, overlap);
            if (LM != 0) {
                Celt.combFilter(decode_mem, outSyn + mode.shortMdctSize, decode_mem, outSyn + mode.shortMdctSize,
                        postfilter_period, postfilterPitch, N - mode.shortMdctSize, postfilter_gain, postfilterGain,
                        postfilter_tapset, postfilterTapset, mode.window, overlap);
            }
        } while (++c < CC);
        postfilter_period_old = postfilter_period;
        postfilter_gain_old = postfilter_gain;
        postfilter_tapset_old = postfilter_tapset;
        postfilter_period = postfilterPitch;
        postfilter_gain = postfilterGain;
        postfilter_tapset = postfilterTapset;
        if (LM != 0) {
            postfilter_period_old = postfilter_period;
            postfilter_gain_old = postfilter_gain;
            postfilter_tapset_old = postfilter_tapset;
        }
        if (C == 1) {
            System.arraycopy(oldBandE, 0, oldBandE, nbEBands, nbEBands);
        }
        if (isTransient == 0) {
            System.arraycopy(oldLogE, 0, oldLogE2, 0, 2 * nbEBands);
            System.arraycopy(oldBandE, 0, oldLogE, 0, 2 * nbEBands);
        } else {
            for (i = 0; i < 2 * nbEBands; i++) {
                oldLogE[i] = MathOps.min(oldLogE[i], oldBandE[i]);
            }
        }
        maxBackgroundIncrease = Math.min(160, loss_duration + M) * 0.001f;
        for (i = 0; i < 2 * nbEBands; i++) {
            backgroundLogE[i] = MathOps.min(backgroundLogE[i] + maxBackgroundIncrease, oldBandE[i]);
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
        } while (++c < 2);
        rng = dec.rng;
        deemphasis(decode_mem, outSyn0, outSyn1, pcm, pcmOff, N, CC, downsample, mode.preemph, preemph_memD);
        loss_duration = 0;
        prefilter_and_fold = 0;
        if (dec.tell() > 8 * len) {
            return Celt.OPUS_INTERNAL_ERROR;
        }
        return frameSize / downsample;
    }
}
