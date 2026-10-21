/* Ports PacketVideo aacdec, Android 4.0.4 (ps_*.cpp, the baseline parametric stereo decoder).
   Copyright 1998-2010 PacketVideo; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.sbr;

import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

import java.util.Arrays;

/**
 * Decodes parametric stereo (HE-AAC v2): turns the QMF subbands of the mono core and its
 * spectral band replication into a left and a right channel, guided by the inter-channel
 * intensity differences, coherences and phase differences of each frame.
 *
 * <p>This is the full decoder of ISO/IEC 14496-3 8.6.4: the lowest QMF subbands of each slot are
 * split into hybrid sub-subbands (10 of them in the 10 and 20 stereo band configuration, 32 in
 * the 34 stereo band one), the per-band power drives a transient detector, a decorrelated copy is
 * built by all-pass filters (below QMF subband 23) or plain delays (above), and the mono and
 * decorrelated signals are mixed into the two channels by mixing procedure R<sub>a</sub> or
 * R<sub>b</sub>, with the phase rotations of the inter-channel and overall phase differences when
 * the stream carries them, interpolated over each envelope. The configuration follows the stream
 * frame by frame, mapping the mixing state between the two band layouts when it changes. The
 * hybrid filterbank reads six slots ahead, which the spectral band replication buffer provides
 * for the five lowest subbands, so the stage adds no delay. Until the first parametric stereo
 * data arrives, both channels equal the mono signal.
 *
 * @implNote This implementation ports the bitstream reader, the 20-band transient detector and
 * decorrelator and the R<sub>a</sub> mixing of the PacketVideo AAC decoder from fixed to floating
 * point, and adds from the specification the 34-band hybrid filterbank, the phase parameters,
 * mixing procedure R<sub>b</sub> and the switching between band configurations; the
 * decorrelator's phase factors and decay coefficients are computed from the formulas their tables
 * were derived from.
 */
final class PsDecoder {
    /**
     * The number of parameter bins of the 34-band configuration, the larger one.
     */
    private static final int MAX_BINS = 34;

    /**
     * The number of phase parameter bins of the 10 and 20 band configuration.
     */
    private static final int IPD_BINS_20 = 11;

    /**
     * The number of phase parameter bins of the 34 band configuration.
     */
    private static final int IPD_BINS_34 = 17;

    /**
     * The number of all-pass sub-subbands of the 34-band configuration, the larger one.
     */
    private static final int MAX_ALLPASS = 50;

    /**
     * The number of hybrid sub-subbands of the 34-band configuration, the larger one.
     */
    private static final int MAX_HYBRID = 32;

    /**
     * The first QMF subband decorrelated by a plain delay instead of all-pass filters.
     */
    private static final int ALLPASS_END = 23;

    /**
     * The first QMF subband delayed by one slot instead of fourteen.
     */
    private static final int SHORT_DELAY_START = 35;

    /**
     * The delay of the subbands from {@link #ALLPASS_END} to {@link #SHORT_DELAY_START}.
     */
    private static final int LONG_DELAY = 14;

    /**
     * The number of envelopes a frame can hold, including the one appended to reach its end.
     */
    private static final int MAX_ENVELOPES = 5;

    /**
     * The parameter bin of each hybrid sub-subband of the 20-band configuration (ISO/IEC 14496-3
     * Table 8.48).
     */
    private static final int[] HYBRID_BINS_20 = {1, 0, 0, 1, 2, 3, 4, 5, 6, 7};

    /**
     * The parameter bin of each hybrid sub-subband of the 34-band configuration (Table 8.49).
     */
    private static final int[] HYBRID_BINS_34 = {
            0, 1, 2, 3, 4, 5, 6, 6, 7, 2, 1, 0, 10, 10, 4, 5, 6, 7, 8, 9, 10, 11, 12, 9, 14, 11, 12, 13, 14, 15, 16, 13
    };

    /**
     * The two 20-bin indices averaged into each of the 34 bins (ISO/IEC 14496-3 Table 8.45), a
     * repeated index copying its value.
     */
    private static final int[] MAP_20_TO_34 = {
            0, 0, 0, 1, 1, 1, 2, 2, 2, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 7, 7, 8, 8, 8, 8, 9, 9, 9, 9, 10, 10, 11, 11,
            12, 12, 13, 13, 14, 14, 14, 14, 15, 15, 15, 15, 16, 16, 16, 16, 17, 17, 17, 17, 18, 18, 18, 18, 18, 18, 18,
            18, 19, 19, 19, 19
    };

    /**
     * The number of leading hybrid sub-subbands of the 20-band configuration whose phase
     * parameters are conjugated (the starred entries of Table 8.48).
     */
    private static final int CONJUGATED_20 = 2;

    /**
     * The first and last plus one hybrid sub-subbands of the 34-band configuration whose phase
     * parameters are conjugated (the starred entries of Table 8.49).
     */
    private static final int[] CONJUGATED_34 = {9, 12};

    /**
     * The first QMF subband of each QMF-only parameter group of the 20-band configuration, and
     * the end of the last group.
     */
    private static final int[] QMF_BORDERS_20 = {3, 4, 5, 6, 7, 8, 9, 11, 14, 18, 23, 35, 64};

    /**
     * The first QMF subband of each QMF-only parameter group of the 34-band configuration, and
     * the end of the last group.
     */
    private static final int[] QMF_BORDERS_34 = {5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 21, 24, 27, 30, 33, 37, 41, 64};

    /**
     * The center frequency of each hybrid sub-subband of the 20-band configuration, in QMF
     * subband units (Table 8.40).
     */
    private static final double[] CENTERS_20 = {-3 / 8.0, -1 / 8.0, 1 / 8.0, 3 / 8.0, 5 / 8.0, 7 / 8.0, 5 / 4.0,
            7 / 4.0, 9 / 4.0, 11 / 4.0};

    /**
     * The center frequency of each hybrid sub-subband of the 34-band configuration (Table 8.41).
     */
    private static final double[] CENTERS_34 = {
            1 / 12.0, 3 / 12.0, 5 / 12.0, 7 / 12.0, 9 / 12.0, 11 / 12.0, 13 / 12.0, 15 / 12.0, 17 / 12.0, -5 / 12.0,
            -3 / 12.0, -1 / 12.0, 17 / 8.0, 19 / 8.0, 5 / 8.0, 7 / 8.0, 9 / 8.0, 11 / 8.0, 13 / 8.0, 15 / 8.0,
            9 / 4.0, 11 / 4.0, 13 / 4.0, 7 / 4.0, 17 / 4.0, 11 / 4.0, 13 / 4.0, 15 / 4.0, 17 / 4.0, 19 / 4.0,
            21 / 4.0, 15 / 4.0
    };

    /**
     * The 13 taps of the prototype of the 8-band complex hybrid filter of the 20-band
     * configuration (Table 8.37).
     */
    private static final double[] PROTOTYPE_20_8 = {
            0.00746082949812, 0.02270420949825, 0.04546865930473, 0.07266113929591, 0.09885108575264,
            0.11793710567217, 0.125, 0.11793710567217, 0.09885108575264, 0.07266113929591,
            0.04546865930473, 0.02270420949825, 0.00746082949812
    };

    /**
     * The 13 taps of the prototype of the 2-band real hybrid filter (Table 8.37).
     */
    private static final double[] PROTOTYPE_2 = {
            0, 0.01899487526049, 0, -0.07293139167538, 0, 0.30596630545168, 0.5,
            0.30596630545168, 0, -0.07293139167538, 0, 0.01899487526049, 0
    };

    /**
     * The 13 taps of the prototype of the 12-band hybrid filter of the 34-band configuration
     * (Table 8.38).
     */
    private static final double[] PROTOTYPE_34_12 = {
            0.04081179924692, 0.03812810994926, 0.05144908135699, 0.06399831151592, 0.07428313801106,
            0.08100347892914, 0.08333333333333, 0.08100347892914, 0.07428313801106, 0.06399831151592,
            0.05144908135699, 0.03812810994926, 0.04081179924692
    };

    /**
     * The 13 taps of the prototype of the 8-band hybrid filter of the 34-band configuration.
     */
    private static final double[] PROTOTYPE_34_8 = {
            0.01565675600122, 0.03752716391991, 0.05417891378782, 0.08417044116767, 0.10307344158036,
            0.12222452249753, 0.125, 0.12222452249753, 0.10307344158036, 0.08417044116767,
            0.05417891378782, 0.03752716391991, 0.01565675600122
    };

    /**
     * The 13 taps of the prototype of the 4-band hybrid filters of the 34-band configuration.
     */
    private static final double[] PROTOTYPE_34_4 = {
            -0.05908211155639, -0.04871498374946, 0, 0.07778723915851, 0.16486303567403,
            0.23279856662996, 0.25, 0.23279856662996, 0.16486303567403, 0.07778723915851,
            0, -0.04871498374946, -0.05908211155639
    };

    /**
     * The delays of the three serial all-pass links.
     */
    private static final int[] LINK_DELAYS = {3, 4, 5};

    /**
     * The decay coefficients of the three all-pass links.
     */
    private static final double[] LINK_DECAYS = {0.65143905753106, 0.56471812200776, 0.48954165955695};

    /**
     * The fractional delay of the all-pass phase factor, {@code q_phi}.
     */
    private static final double PHASE_FRACTION = 0.39;

    /**
     * The fractional delays of the three all-pass links.
     */
    private static final double[] LINK_FRACTIONS = {0.43, 0.75, 0.347};

    /**
     * The intensity differences of the coarse quantizer, in dB.
     */
    private static final double[] IID_COARSE = {-25, -18, -14, -10, -7, -4, -2, 0, 2, 4, 7, 10, 14, 18, 25};

    /**
     * The intensity differences of the fine quantizer, in dB.
     */
    private static final double[] IID_FINE = {
            -50, -45, -40, -35, -30, -25, -22, -19, -16, -13, -10, -8, -6, -4, -2, 0,
            2, 4, 6, 8, 10, 13, 16, 19, 22, 25, 30, 35, 40, 45, 50
    };

    /**
     * The coherences of the quantizer.
     */
    private static final double[] ICC_VALUES = {1, 0.937, 0.84118, 0.60092, 0.36764, 0, -0.589, -1};

    /**
     * The channel scale of each coarse intensity difference, {@code sqrt(2 / (1 + 10^(iid / 10)))}.
     */
    private static final double[] SCALE_COARSE = scales(IID_COARSE);

    /**
     * The channel scale of each fine intensity difference.
     */
    private static final double[] SCALE_FINE = scales(IID_FINE);

    /**
     * The real parts of the hybrid filter taps of the 20-band configuration's QMF subband 0, by
     * filter output and tap.
     */
    private static final double[][] TAPS_20_RE = new double[8][];

    /**
     * The imaginary parts of the hybrid filter taps of the 20-band configuration's QMF subband 0.
     */
    private static final double[][] TAPS_20_IM = new double[8][];

    /**
     * The real parts of the hybrid filter taps of the 34-band configuration, by sub-subband and
     * tap.
     */
    private static final double[][] TAPS_34_RE = new double[MAX_HYBRID][];

    /**
     * The imaginary parts of the hybrid filter taps of the 34-band configuration.
     */
    private static final double[][] TAPS_34_IM = new double[MAX_HYBRID][];

    static {
        complexTaps(PROTOTYPE_20_8, 8, TAPS_20_RE, TAPS_20_IM, 0);
        complexTaps(PROTOTYPE_34_12, 12, TAPS_34_RE, TAPS_34_IM, 0);
        complexTaps(PROTOTYPE_34_8, 8, TAPS_34_RE, TAPS_34_IM, 12);
        for (var band = 0; band < 3; band++) {
            complexTaps(PROTOTYPE_34_4, 4, TAPS_34_RE, TAPS_34_IM, 20 + 4 * band);
        }
    }

    /**
     * The 20-band layout, then the 34-band one.
     */
    private static final Layout[] LAYOUTS = {
            new Layout(10, 7, 3, 20, IPD_BINS_20, 30, 3, HYBRID_BINS_20, 0, CONJUGATED_20, QMF_BORDERS_20, CENTERS_20),
            new Layout(32, 27, 5, 34, IPD_BINS_34, 50, 5, HYBRID_BINS_34, CONJUGATED_34[0], CONJUGATED_34[1],
                    QMF_BORDERS_34, CENTERS_34)
    };

    /**
     * Whether intensity differences are transmitted.
     */
    private boolean enableIid;

    /**
     * Whether coherences are transmitted.
     */
    private boolean enableIcc;

    /**
     * Whether an extension follows the parameters.
     */
    private boolean enableExtension;

    /**
     * The {@code iid_mode} of the last header, from 0 to 7.
     */
    private int iidMode;

    /**
     * The {@code icc_mode} of the last header, from 0 to 7.
     */
    private int iccMode;

    /**
     * Whether the last frame's data carried phase parameters.
     */
    private boolean enableIpdOpd;

    /**
     * Whether a header was received.
     */
    private boolean headerReceived;

    /**
     * Whether this frame carried parametric stereo data.
     */
    private boolean dataAvailable;

    /**
     * Whether any parametric stereo data was received; until then the channels stay equal.
     */
    private boolean seen;

    /**
     * Whether the envelope borders are transmitted.
     */
    private boolean variableBorders;

    /**
     * The number of envelopes of the frame.
     */
    private int envelopes;

    /**
     * Whether the frame's single envelope holds the previous frame's parameters instead of
     * carrying new ones.
     */
    private boolean held;

    /**
     * The envelope borders, in QMF slots.
     */
    private final int[] borders = new int[MAX_ENVELOPES + 1];

    /**
     * The intensity difference indices, by envelope and bin.
     */
    private final int[][] iid = new int[MAX_ENVELOPES][MAX_BINS];

    /**
     * The coherence indices, by envelope and bin.
     */
    private final int[][] icc = new int[MAX_ENVELOPES][MAX_BINS];

    /**
     * The inter-channel phase difference indices, by envelope and bin.
     */
    private final int[][] ipd = new int[MAX_ENVELOPES][MAX_BINS];

    /**
     * The overall phase difference indices, by envelope and bin.
     */
    private final int[][] opd = new int[MAX_ENVELOPES][MAX_BINS];

    /**
     * The number of bins the frame's intensity differences are resolved on: 20 (10-bin data
     * widened) or 34.
     */
    private int iidCount = 20;

    /**
     * The number of bins the frame's coherences are resolved on.
     */
    private int iccCount = 20;

    /**
     * The number of bins the frame's phase parameters are resolved on: 11 or 17.
     */
    private int ipdCount = IPD_BINS_20;

    /**
     * Whether each envelope's intensity differences are coded in time.
     */
    private final boolean[] iidTime = new boolean[4];

    /**
     * Whether each envelope's coherences are coded in time.
     */
    private final boolean[] iccTime = new boolean[4];

    /**
     * Whether each envelope's inter-channel phase differences are coded in time.
     */
    private final boolean[] ipdTime = new boolean[4];

    /**
     * Whether each envelope's overall phase differences are coded in time.
     */
    private final boolean[] opdTime = new boolean[4];

    /**
     * The intensity difference indices of the previous frame's last envelope.
     */
    private final int[] iidPrevious = new int[MAX_BINS];

    /**
     * The coherence indices of the previous frame's last envelope.
     */
    private final int[] iccPrevious = new int[MAX_BINS];

    /**
     * The inter-channel phase difference indices of the previous frame's last envelope.
     */
    private final int[] ipdPrevious = new int[MAX_BINS];

    /**
     * The overall phase difference indices of the previous frame's last envelope.
     */
    private final int[] opdPrevious = new int[MAX_BINS];

    /**
     * The number of bins of {@link #iidPrevious}.
     */
    private int iidPreviousCount = 20;

    /**
     * The number of bins of {@link #iccPrevious}.
     */
    private int iccPreviousCount = 20;

    /**
     * The number of bins of {@link #ipdPrevious} and {@link #opdPrevious}.
     */
    private int ipdPreviousCount = IPD_BINS_20;

    /**
     * Whether the frame uses the 34 stereo band configuration.
     */
    private boolean use34;

    /**
     * Whether the previous frame used the 34 stereo band configuration.
     */
    private boolean use34Previous;

    /**
     * The intensity difference indices of the current envelope on the frame's stereo bands.
     */
    private final int[] bandIid = new int[MAX_BINS];

    /**
     * The coherence indices of the current envelope on the frame's stereo bands.
     */
    private final int[] bandIcc = new int[MAX_BINS];

    /**
     * The inter-channel phase difference indices of the current envelope on the frame's stereo
     * bands.
     */
    private final int[] bandIpd = new int[MAX_BINS];

    /**
     * The overall phase difference indices of the current envelope on the frame's stereo bands.
     */
    private final int[] bandOpd = new int[MAX_BINS];

    /**
     * The inter-channel phase difference indices of the last parameter position, by stereo band.
     */
    private final int[] ipdHistory1 = new int[MAX_BINS];

    /**
     * The overall phase difference indices of the last parameter position.
     */
    private final int[] opdHistory1 = new int[MAX_BINS];

    /**
     * The inter-channel phase difference indices of the parameter position before the last.
     */
    private final int[] ipdHistory2 = new int[MAX_BINS];

    /**
     * The overall phase difference indices of the parameter position before the last.
     */
    private final int[] opdHistory2 = new int[MAX_BINS];

    /**
     * The hybrid filter input history of QMF subbands 0 to 4, real parts, twelve slots each,
     * oldest first.
     */
    private final double[][] historyRe = new double[5][12];

    /**
     * The hybrid filter input history, imaginary parts.
     */
    private final double[][] historyIm = new double[5][12];

    /**
     * The mono hybrid sub-subbands of the current slot, real parts, overwritten with the left
     * channel.
     */
    private final double[] monoRe = new double[MAX_HYBRID];

    /**
     * The mono hybrid sub-subbands of the current slot, imaginary parts.
     */
    private final double[] monoIm = new double[MAX_HYBRID];

    /**
     * The decorrelated hybrid sub-subbands of the current slot, real parts, overwritten with the
     * right channel.
     */
    private final double[] sideRe = new double[MAX_HYBRID];

    /**
     * The decorrelated hybrid sub-subbands of the current slot, imaginary parts.
     */
    private final double[] sideIm = new double[MAX_HYBRID];

    /**
     * The outputs of the 8-band filter of the 20-band configuration before merging, real parts.
     */
    private final double[] scratchRe = new double[8];

    /**
     * The outputs of the 8-band filter before merging, imaginary parts.
     */
    private final double[] scratchIm = new double[8];

    /**
     * The smoothed power of each bin.
     */
    private final double[] smoothPower = new double[MAX_BINS];

    /**
     * The decaying peak power of each bin.
     */
    private final double[] peakPower = new double[MAX_BINS];

    /**
     * The smoothed difference between the peak and the power of each bin.
     */
    private final double[] peakDifference = new double[MAX_BINS];

    /**
     * The power of each bin in the current slot.
     */
    private final double[] power = new double[MAX_BINS];

    /**
     * The transient attenuation of each bin in the current slot.
     */
    private final double[] attenuation = new double[MAX_BINS];

    /**
     * The two-slot delay of the all-pass sub-subbands, real parts, by sub-subband and position.
     */
    private final double[][] shortDelayRe = new double[MAX_ALLPASS][2];

    /**
     * The two-slot delay of the all-pass sub-subbands, imaginary parts.
     */
    private final double[][] shortDelayIm = new double[MAX_ALLPASS][2];

    /**
     * The position in {@link #shortDelayRe} of the current slot.
     */
    private int shortDelayIndex;

    /**
     * The delay lines of the all-pass links, real parts, by link, position and sub-subband.
     */
    private final double[][][] linkRe = new double[3][][];

    /**
     * The delay lines of the all-pass links, imaginary parts.
     */
    private final double[][][] linkIm = new double[3][][];

    /**
     * The position of the current slot in each link's delay line.
     */
    private final int[] linkIndex = new int[3];

    /**
     * The delay lines of the subbands decorrelated by plain delays, real parts, by subband
     * (from {@link #ALLPASS_END}) and position.
     */
    private final double[][] delayRe = new double[64 - ALLPASS_END][LONG_DELAY];

    /**
     * The delay lines of the subbands decorrelated by plain delays, imaginary parts.
     */
    private final double[][] delayIm = new double[64 - ALLPASS_END][LONG_DELAY];

    /**
     * The position of the current slot in each plain delay line.
     */
    private final int[] delayIndex = new int[64 - ALLPASS_END];

    /**
     * The real parts of the end-of-envelope mixing coefficients {@code h11}, {@code h12},
     * {@code h21} and {@code h22}, by coefficient and bin.
     */
    private final double[][] targetRe = new double[4][MAX_BINS];

    /**
     * The imaginary parts of the end-of-envelope mixing coefficients.
     */
    private final double[][] targetIm = new double[4][MAX_BINS];

    /**
     * The real parts of the mixing coefficients at the end of the previous envelope, by
     * coefficient and bin.
     */
    private final double[][] previousRe = new double[4][MAX_BINS];

    /**
     * The imaginary parts of the mixing coefficients at the end of the previous envelope.
     */
    private final double[][] previousIm = new double[4][MAX_BINS];

    /**
     * The interpolated mixing coefficients of the current slot, real parts, by coefficient and
     * group (the hybrid sub-subbands, then the QMF-only groups).
     */
    private final double[][] mixRe = new double[4][MAX_HYBRID + 18];

    /**
     * The interpolated mixing coefficients of the current slot, imaginary parts.
     */
    private final double[][] mixIm = new double[4][MAX_HYBRID + 18];

    /**
     * The per-slot increments of {@link #mixRe}.
     */
    private final double[][] stepRe = new double[4][MAX_HYBRID + 18];

    /**
     * The per-slot increments of {@link #mixIm}.
     */
    private final double[][] stepIm = new double[4][MAX_HYBRID + 18];

    /**
     * Creates a decoder with silent filter memories.
     */
    PsDecoder() {
        for (var link = 0; link < 3; link++) {
            linkRe[link] = new double[LINK_DELAYS[link]][MAX_ALLPASS];
            linkIm[link] = new double[LINK_DELAYS[link]][MAX_ALLPASS];
        }
        Arrays.fill(previousRe[0], 1);
        Arrays.fill(previousRe[1], 1);
    }

    /**
     * Fills the taps of a complex (type A) hybrid filter bank, ISO/IEC 14496-3 8.6.4.3, ordered
     * to run over a history held oldest first.
     *
     * @param prototype the 13 prototype taps
     * @param bands the number of sub-subbands
     * @param re the destination real parts, by sub-subband
     * @param im the destination imaginary parts
     * @param offset the index of the first sub-subband in the destinations
     */
    private static void complexTaps(double[] prototype, int bands, double[][] re, double[][] im, int offset) {
        for (var q = 0; q < bands; q++) {
            re[offset + q] = new double[13];
            im[offset + q] = new double[13];
            for (var n = 0; n < 13; n++) {
                var angle = -2 * Math.PI / bands * (q + 0.5) * (n - 6);
                re[offset + q][n] = prototype[n] * Math.cos(angle);
                im[offset + q][n] = prototype[n] * Math.sin(angle);
            }
        }
    }

    /**
     * Computes the channel scale of each intensity difference.
     *
     * @param levels the intensity differences in dB
     * @return the scales
     */
    private static double[] scales(double[] levels) {
        var result = new double[levels.length];
        for (var i = 0; i < levels.length; i++) {
            result[i] = Math.sqrt(2 / (1 + Math.pow(10, levels[i] / 10)));
        }
        return result;
    }

    /**
     * Returns whether any parametric stereo data was received.
     *
     * @return {@code true} once the channels may differ
     */
    boolean seen() {
        return seen;
    }

    /**
     * Reads one {@code ps_data} element.
     *
     * @param reader the bit source
     * @param available the number of bits of the enclosing extension
     * @return the number of bits read
     */
    int read(AacBitReader reader, long available) {
        var start = reader.bitPosition();
        if (reader.readFlag()) {
            headerReceived = true;
            enableIid = reader.readFlag();
            if (enableIid) {
                iidMode = reader.read(3);
            }
            enableIcc = reader.readFlag();
            if (enableIcc) {
                iccMode = reader.read(3);
            }
            enableExtension = reader.readFlag();
        }
        variableBorders = reader.readFlag();
        var index = reader.read(2);
        envelopes = variableBorders ? index + 1 : switch (index) {
            case 0 -> 0;
            case 1 -> 1;
            case 2 -> 2;
            default -> 4;
        };
        if (variableBorders) {
            for (var e = 1; e <= envelopes; e++) {
                borders[e] = reader.read(5) + 1;
            }
        }
        if (!headerReceived || (enableIid && iidMode > 5) || (enableIcc && iccMode > 5)) {
            dataAvailable = false;
            return (int) (reader.bitPosition() - start);
        }
        var iidBins = binCount(iidMode % 3);
        var iccBins = binCount(iccMode % 3);
        var fine = iidMode > 2;
        for (var e = 0; e < envelopes; e++) {
            if (enableIid) {
                iidTime[e] = reader.readFlag();
                var table = fine ? (iidTime[e] ? SbrHuffman.IID_FINE_TIME : SbrHuffman.IID_FINE_FREQUENCY)
                        : (iidTime[e] ? SbrHuffman.IID_TIME : SbrHuffman.IID_FREQUENCY);
                for (var b = 0; b < iidBins; b++) {
                    iid[e][b] = SbrHuffman.decode(table, reader);
                }
            }
        }
        for (var e = 0; e < envelopes; e++) {
            if (enableIcc) {
                iccTime[e] = reader.readFlag();
                var table = iccTime[e] ? SbrHuffman.ICC_TIME : SbrHuffman.ICC_FREQUENCY;
                for (var b = 0; b < iccBins; b++) {
                    icc[e][b] = SbrHuffman.decode(table, reader);
                }
            }
        }
        enableIpdOpd = false;
        if (enableExtension) {
            var count = reader.read(4);
            if (count == 15) {
                count += reader.read(8);
            }
            var left = 8L * count;
            while (left > 7) {
                var id = reader.read(2);
                left -= 2;
                if (id == 0) {
                    var extensionStart = reader.bitPosition();
                    readPhases(reader, iidBins);
                    left -= reader.bitPosition() - extensionStart;
                }
            }
            if (left < 0) {
                dataAvailable = false;
                return (int) (reader.bitPosition() - start);
            }
            reader.skip(left);
        }
        dataAvailable = true;
        seen = true;
        return (int) (reader.bitPosition() - start);
    }

    /**
     * Reads the version 0 extension: the phase parameters of every envelope, ISO/IEC 14496-3
     * Table 8.10.
     *
     * @param reader the bit source
     * @param iidBins the number of intensity difference bins, which sets the number of phase bins
     */
    private void readPhases(AacBitReader reader, int iidBins) {
        enableIpdOpd = reader.readFlag();
        if (enableIpdOpd) {
            var bins = phaseBinCount(iidBins);
            for (var e = 0; e < envelopes; e++) {
                ipdTime[e] = reader.readFlag();
                var ipdTable = ipdTime[e] ? SbrHuffman.IPD_TIME : SbrHuffman.IPD_FREQUENCY;
                for (var b = 0; b < bins; b++) {
                    ipd[e][b] = SbrHuffman.decode(ipdTable, reader);
                }
                opdTime[e] = reader.readFlag();
                var opdTable = opdTime[e] ? SbrHuffman.OPD_TIME : SbrHuffman.OPD_FREQUENCY;
                for (var b = 0; b < bins; b++) {
                    opd[e][b] = SbrHuffman.decode(opdTable, reader);
                }
            }
        }
        reader.readFlag();
    }

    /**
     * Discards the data read for the current frame, after the enclosing payload proved corrupt.
     */
    void discard() {
        dataAvailable = false;
    }

    /**
     * Returns the number of bins of a frequency resolution.
     *
     * @param resolution 0, 1 or 2
     * @return 10, 20 or 34
     */
    private static int binCount(int resolution) {
        return switch (resolution) {
            case 0 -> 10;
            case 1 -> 20;
            default -> 34;
        };
    }

    /**
     * Returns the number of phase parameters transmitted for a number of intensity difference
     * bins, ISO/IEC 14496-3 Table 8.24.
     *
     * @param iidBins 10, 20 or 34
     * @return 5, 11 or 17
     */
    private static int phaseBinCount(int iidBins) {
        return switch (iidBins) {
            case 10 -> 5;
            case 20 -> IPD_BINS_20;
            default -> IPD_BINS_34;
        };
    }

    /**
     * Resolves the frame's parameters: differential decoding, the envelope borders and the
     * stereo band configuration.
     *
     * @param slots the number of QMF slots per frame
     */
    private void resolve(int slots) {
        var fixedBorders = !variableBorders || !dataAvailable;
        if (!dataAvailable) {
            envelopes = 0;
        }
        if (enableIid || enableIcc) {
            var iidBins = enableIid ? binCount(iidMode % 3) : 20;
            var iccBins = enableIcc ? binCount(iccMode % 3) : 20;
            use34 = iidBins == 34 || iccBins == 34;
        }
        var iidBins = binCount(iidMode % 3);
        var iccBins = binCount(iccMode % 3);
        var ipdBins = phaseBinCount(iidBins);
        var iidSteps = iidMode > 2 ? 15 : 7;
        for (var e = 0; e < envelopes; e++) {
            var first = e == 0;
            differential(enableIid, iid[e], first ? iidPrevious : iid[e - 1], iidTime[e], iidBins, -iidSteps, iidSteps,
                    false);
            differential(enableIcc, icc[e], first ? iccPrevious : icc[e - 1], iccTime[e], iccBins, 0, 7, false);
            differential(enableIpdOpd, ipd[e], first ? ipdPrevious : ipd[e - 1], ipdTime[e], ipdBins, 0, 7, true);
            differential(enableIpdOpd, opd[e], first ? opdPrevious : opd[e - 1], opdTime[e], ipdBins, 0, 7, true);
        }
        held = envelopes == 0;
        if (held) {
            envelopes = 1;
            hold(enableIid, iid[0], iidPrevious);
            hold(enableIcc, icc[0], iccPrevious);
            hold(enableIpdOpd, ipd[0], ipdPrevious);
            hold(enableIpdOpd, opd[0], opdPrevious);
            iidCount = enableIid ? iidPreviousCount : 20;
            iccCount = enableIcc ? iccPreviousCount : 20;
            ipdCount = enableIpdOpd ? ipdPreviousCount : IPD_BINS_20;
            held = enableIpdOpd;
        } else {
            iidCount = iidBins == 34 ? 34 : 20;
            iccCount = iccBins == 34 ? 34 : 20;
            ipdCount = ipdBins == IPD_BINS_34 ? IPD_BINS_34 : IPD_BINS_20;
        }
        System.arraycopy(iid[envelopes - 1], 0, iidPrevious, 0, MAX_BINS);
        System.arraycopy(icc[envelopes - 1], 0, iccPrevious, 0, MAX_BINS);
        System.arraycopy(ipd[envelopes - 1], 0, ipdPrevious, 0, MAX_BINS);
        System.arraycopy(opd[envelopes - 1], 0, opdPrevious, 0, MAX_BINS);
        iidPreviousCount = iidCount;
        iccPreviousCount = iccCount;
        ipdPreviousCount = ipdCount;
        dataAvailable = false;
        borders[0] = 0;
        if (fixedBorders) {
            for (var e = 1; e < envelopes; e++) {
                borders[e] = e * slots / envelopes;
            }
            borders[envelopes] = slots;
        } else {
            if (borders[envelopes] < slots) {
                envelopes++;
                borders[envelopes] = slots;
                System.arraycopy(iid[envelopes - 2], 0, iid[envelopes - 1], 0, MAX_BINS);
                System.arraycopy(icc[envelopes - 2], 0, icc[envelopes - 1], 0, MAX_BINS);
                System.arraycopy(ipd[envelopes - 2], 0, ipd[envelopes - 1], 0, MAX_BINS);
                System.arraycopy(opd[envelopes - 2], 0, opd[envelopes - 1], 0, MAX_BINS);
            }
            for (var e = 1; e < envelopes; e++) {
                var upper = slots - envelopes + e;
                if (borders[e] > upper) {
                    borders[e] = upper;
                } else if (borders[e] < borders[e - 1] + 1) {
                    borders[e] = borders[e - 1] + 1;
                }
            }
            borders[envelopes] = slots;
        }
    }

    /**
     * Sets the single envelope of a frame without new data: the previous frame's parameters
     * when the parameter is enabled, the defaults otherwise.
     *
     * @param enabled whether the parameter is enabled
     * @param values the envelope's values
     * @param previous the previous frame's last values
     */
    private static void hold(boolean enabled, int[] values, int[] previous) {
        if (enabled) {
            System.arraycopy(previous, 0, values, 0, MAX_BINS);
        } else {
            Arrays.fill(values, 0);
        }
    }

    /**
     * Resolves the differential coding of one envelope's parameters, clamping them to their
     * range or wrapping them modulo 8, and widens 10-bin parameters (5-bin phases) to twice as
     * many bins; the bins above stay zero.
     *
     * @param enabled whether the parameter is transmitted; zero otherwise
     * @param values the coded values, resolved in place
     * @param reference the previous envelope's values
     * @param time whether the values are coded in time
     * @param count the number of bins
     * @param min the smallest index
     * @param max the largest index
     * @param modular whether the values wrap modulo 8 instead of clamping
     */
    private static void differential(boolean enabled, int[] values, int[] reference, boolean time, int count, int min,
                                     int max, boolean modular) {
        var stride = count == 10 || count == 5 ? 2 : 1;
        if (enabled) {
            if (!time) {
                values[0] = limit(values[0], min, max, modular);
                for (var i = 1; i < count; i++) {
                    values[i] = limit(values[i - 1] + values[i], min, max, modular);
                }
            } else {
                for (var i = 0; i < count; i++) {
                    values[i] = limit(reference[i * stride] + values[i], min, max, modular);
                }
            }
        } else {
            Arrays.fill(values, 0, count, 0);
        }
        if (stride == 2) {
            for (var i = 2 * count - 1; i > 0; i--) {
                values[i] = values[i >> 1];
            }
        }
        Arrays.fill(values, stride * count, MAX_BINS, 0);
    }

    /**
     * Brings a resolved index into its range.
     *
     * @param value the index
     * @param min the smallest index
     * @param max the largest index
     * @param modular whether the index wraps modulo 8 instead of clamping
     * @return the index in range
     */
    private static int limit(int value, int min, int max, boolean modular) {
        return modular ? value & 7 : Math.clamp(value, min, max);
    }

    /**
     * Maps parameter indices from 20 to 34 bins, ISO/IEC 14496-3 Table 8.45, with integer
     * averaging.
     *
     * @param source the 20 values
     * @param destination the destination of the 34 values, not {@code source}
     * @param count the number of destination values to write: 34, or 17 for phases
     */
    private static void map20To34(int[] source, int[] destination, int count) {
        for (var i = 0; i < count; i++) {
            destination[i] = (source[MAP_20_TO_34[2 * i]] + source[MAP_20_TO_34[2 * i + 1]]) / 2;
        }
        Arrays.fill(destination, count, MAX_BINS, 0);
    }

    /**
     * Maps parameter indices from 34 to 20 bins, ISO/IEC 14496-3 Table 8.46, with integer
     * averaging.
     *
     * @param source the 34 values
     * @param destination the destination of the 20 values, not {@code source}
     * @param count the number of destination values to write: 20, or 11 for phases
     */
    private static void map34To20(int[] source, int[] destination, int count) {
        destination[0] = (2 * source[0] + source[1]) / 3;
        destination[1] = (source[1] + 2 * source[2]) / 3;
        destination[2] = (2 * source[3] + source[4]) / 3;
        destination[3] = (source[4] + 2 * source[5]) / 3;
        destination[4] = (source[6] + source[7]) / 2;
        destination[5] = (source[8] + source[9]) / 2;
        destination[6] = source[10];
        destination[7] = source[11];
        destination[8] = (source[12] + source[13]) / 2;
        destination[9] = (source[14] + source[15]) / 2;
        destination[10] = source[16];
        destination[11] = source[17];
        destination[12] = source[18];
        destination[13] = source[19];
        destination[14] = (source[20] + source[21]) / 2;
        destination[15] = (source[22] + source[23]) / 2;
        destination[16] = (source[24] + source[25]) / 2;
        destination[17] = (source[26] + source[27]) / 2;
        destination[18] = (source[28] + source[29] + source[30] + source[31]) / 4;
        destination[19] = (source[32] + source[33]) / 2;
        Arrays.fill(destination, count, MAX_BINS, 0);
    }

    /**
     * Brings one envelope's parameter indices onto the frame's stereo bands.
     *
     * @param values the indices on {@code count} bins
     * @param count the number of bins of the indices: 20 or 34 (11 or 17 for phases)
     * @param destination the destination indices on the frame's stereo bands
     * @param phases whether the indices are phase parameters
     */
    private void toBands(int[] values, int count, int[] destination, boolean phases) {
        var wide = count == 34 || count == IPD_BINS_34;
        if (wide == use34) {
            System.arraycopy(values, 0, destination, 0, MAX_BINS);
        } else if (use34) {
            map20To34(values, destination, phases ? IPD_BINS_34 : 34);
        } else {
            map34To20(values, destination, phases ? IPD_BINS_20 : 20);
        }
    }

    /**
     * Turns the mono subband matrix of a frame into the left (in place) and right channels.
     *
     * @param re the mono real parts, 64 per slot, with six slots of look-ahead for the
     * five lowest subbands; overwritten with the left channel
     * @param im the mono imaginary parts
     * @param rightRe the destination right real parts, 64 per slot
     * @param rightIm the destination right imaginary parts
     * @param slots the number of slots
     * @param usb the subband after the last one that carries signal
     */
    void process(float[] re, float[] im, float[] rightRe, float[] rightIm, int slots, int usb) {
        resolve(slots);
        if (use34 != use34Previous) {
            switchLayout();
        }
        var layout = LAYOUTS[use34 ? 1 : 0];
        var envelope = 0;
        for (var slot = 0; slot < slots; slot++) {
            if (envelope < envelopes && slot == borders[envelope]) {
                startEnvelope(layout, envelope);
                envelope++;
            }
            var row = slot * 64;
            if (use34) {
                analyze34(re, im, row);
            } else {
                analyze20(re, im, row);
            }
            detectTransients(layout, re, im, row, usb);
            decorrelate(layout, re, im, rightRe, rightIm, row, usb);
            mix(layout, re, im, rightRe, rightIm, row, usb);
            synthesize(re, im, rightRe, rightIm, row);
        }
        use34Previous = use34;
    }

    /**
     * Changes the stereo band configuration at the start of a frame, ISO/IEC 14496-3 Table 8.47:
     * maps the mixing coefficients at the end of the previous frame onto the new bands, and
     * resets the phase smoothing and the decorrelator.
     */
    private void switchLayout() {
        for (var c = 0; c < 4; c++) {
            if (use34) {
                mapCoefficients20To34(previousRe[c]);
                mapCoefficients20To34(previousIm[c]);
            } else {
                mapCoefficients34To20(previousRe[c]);
                mapCoefficients34To20(previousIm[c]);
            }
        }
        Arrays.fill(ipdHistory1, 0);
        Arrays.fill(opdHistory1, 0);
        Arrays.fill(ipdHistory2, 0);
        Arrays.fill(opdHistory2, 0);
        for (var band = 0; band < MAX_ALLPASS; band++) {
            Arrays.fill(shortDelayRe[band], 0);
            Arrays.fill(shortDelayIm[band], 0);
        }
        for (var link = 0; link < 3; link++) {
            for (var position = 0; position < LINK_DELAYS[link]; position++) {
                Arrays.fill(linkRe[link][position], 0);
                Arrays.fill(linkIm[link][position], 0);
            }
        }
        for (var line = 0; line < delayRe.length; line++) {
            Arrays.fill(delayRe[line], 0);
            Arrays.fill(delayIm[line], 0);
        }
        Arrays.fill(smoothPower, 0);
        Arrays.fill(peakPower, 0);
        Arrays.fill(peakDifference, 0);
    }

    /**
     * Maps mixing coefficients from 20 to 34 bins in place, Table 8.45 with real averaging.
     *
     * @param values the coefficients
     */
    private static void mapCoefficients20To34(double[] values) {
        var source = Arrays.copyOf(values, 20);
        values[0] = source[0];
        values[1] = (source[0] + source[1]) / 2;
        values[2] = source[1];
        values[3] = source[2];
        values[4] = (source[2] + source[3]) / 2;
        values[5] = source[3];
        values[6] = source[4];
        values[7] = source[4];
        values[8] = source[5];
        values[9] = source[5];
        values[10] = source[6];
        values[11] = source[7];
        values[12] = source[8];
        values[13] = source[8];
        values[14] = source[9];
        values[15] = source[9];
        values[16] = source[10];
        values[17] = source[11];
        values[18] = source[12];
        values[19] = source[13];
        values[20] = source[14];
        values[21] = source[14];
        values[22] = source[15];
        values[23] = source[15];
        values[24] = source[16];
        values[25] = source[16];
        values[26] = source[17];
        values[27] = source[17];
        values[28] = source[18];
        values[29] = source[18];
        values[30] = source[18];
        values[31] = source[18];
        values[32] = source[19];
        values[33] = source[19];
    }

    /**
     * Maps mixing coefficients from 34 to 20 bins in place, Table 8.46 with real averaging.
     *
     * @param values the coefficients
     */
    private static void mapCoefficients34To20(double[] values) {
        values[0] = (2 * values[0] + values[1]) / 3;
        values[1] = (values[1] + 2 * values[2]) / 3;
        values[2] = (2 * values[3] + values[4]) / 3;
        values[3] = (values[4] + 2 * values[5]) / 3;
        values[4] = (values[6] + values[7]) / 2;
        values[5] = (values[8] + values[9]) / 2;
        values[6] = values[10];
        values[7] = values[11];
        values[8] = (values[12] + values[13]) / 2;
        values[9] = (values[14] + values[15]) / 2;
        values[10] = values[16];
        values[11] = values[17];
        values[12] = values[18];
        values[13] = values[19];
        values[14] = (values[20] + values[21]) / 2;
        values[15] = (values[22] + values[23]) / 2;
        values[16] = (values[24] + values[25]) / 2;
        values[17] = (values[26] + values[27]) / 2;
        values[18] = (values[28] + values[29] + values[30] + values[31]) / 4;
        values[19] = (values[32] + values[33]) / 2;
        Arrays.fill(values, 20, MAX_BINS, 0);
    }

    /**
     * Computes the mixing coefficients at the end of an envelope and the per-slot increments
     * that interpolate towards them, ISO/IEC 14496-3 8.6.4.6.2 to 8.6.4.6.4.
     *
     * @param layout the stereo band layout
     * @param envelope the envelope index
     */
    private void startEnvelope(Layout layout, int envelope) {
        var bins = layout.bins;
        if (held) {
            for (var c = 0; c < 4; c++) {
                System.arraycopy(previousRe[c], 0, targetRe[c], 0, bins);
                System.arraycopy(previousIm[c], 0, targetIm[c], 0, bins);
            }
        } else {
            computeTargets(layout, envelope);
        }
        var length = borders[envelope + 1] - borders[envelope];
        for (var group = 0; group < layout.groups; group++) {
            var bin = layout.groupBins[group];
            var sign = layout.groupConjugated[group] ? -1 : 1;
            for (var c = 0; c < 4; c++) {
                var startRe = previousRe[c][bin];
                var startIm = sign * previousIm[c][bin];
                mixRe[c][group] = startRe;
                mixIm[c][group] = startIm;
                stepRe[c][group] = (targetRe[c][bin] - startRe) / length;
                stepIm[c][group] = (sign * targetIm[c][bin] - startIm) / length;
            }
        }
        for (var c = 0; c < 4; c++) {
            System.arraycopy(targetRe[c], 0, previousRe[c], 0, bins);
            System.arraycopy(targetIm[c], 0, previousIm[c], 0, bins);
        }
    }

    /**
     * Computes the mixing coefficients of an envelope's parameters on every stereo band.
     *
     * @param layout the stereo band layout
     * @param envelope the envelope index
     */
    private void computeTargets(Layout layout, int envelope) {
        toBands(iid[envelope], iidCount, bandIid, false);
        toBands(icc[envelope], iccCount, bandIcc, false);
        toBands(ipd[envelope], ipdCount, bandIpd, true);
        toBands(opd[envelope], ipdCount, bandOpd, true);
        var fine = iidMode > 2;
        var procedureB = enableIcc && iccMode > 2;
        for (var bin = 0; bin < layout.bins; bin++) {
            mixingMatrix(procedureB, fine, bandIid[bin], bandIcc[bin], matrix);
            var h11 = matrix[0];
            var h12 = matrix[1];
            var h21 = matrix[2];
            var h22 = matrix[3];
            if (enableIpdOpd && bin < layout.ipdBins) {
                var opdRe = 0.25 * PHASE_COS[opdHistory2[bin]] + 0.5 * PHASE_COS[opdHistory1[bin]]
                        + PHASE_COS[bandOpd[bin]];
                var opdIm = 0.25 * PHASE_SIN[opdHistory2[bin]] + 0.5 * PHASE_SIN[opdHistory1[bin]]
                        + PHASE_SIN[bandOpd[bin]];
                var ipdRe = 0.25 * PHASE_COS[ipdHistory2[bin]] + 0.5 * PHASE_COS[ipdHistory1[bin]]
                        + PHASE_COS[bandIpd[bin]];
                var ipdIm = 0.25 * PHASE_SIN[ipdHistory2[bin]] + 0.5 * PHASE_SIN[ipdHistory1[bin]]
                        + PHASE_SIN[bandIpd[bin]];
                var phase1 = Math.atan2(opdIm, opdRe);
                var phase2 = phase1 - Math.atan2(ipdIm, ipdRe);
                var cos1 = Math.cos(phase1);
                var sin1 = Math.sin(phase1);
                var cos2 = Math.cos(phase2);
                var sin2 = Math.sin(phase2);
                targetRe[0][bin] = h11 * cos1;
                targetIm[0][bin] = h11 * sin1;
                targetRe[1][bin] = h12 * cos2;
                targetIm[1][bin] = h12 * sin2;
                targetRe[2][bin] = h21 * cos1;
                targetIm[2][bin] = h21 * sin1;
                targetRe[3][bin] = h22 * cos2;
                targetIm[3][bin] = h22 * sin2;
            } else {
                targetRe[0][bin] = h11;
                targetRe[1][bin] = h12;
                targetRe[2][bin] = h21;
                targetRe[3][bin] = h22;
                for (var c = 0; c < 4; c++) {
                    targetIm[c][bin] = 0;
                }
            }
        }
        for (var bin = 0; bin < layout.ipdBins; bin++) {
            ipdHistory2[bin] = ipdHistory1[bin];
            opdHistory2[bin] = opdHistory1[bin];
            ipdHistory1[bin] = enableIpdOpd ? bandIpd[bin] : 0;
            opdHistory1[bin] = enableIpdOpd ? bandOpd[bin] : 0;
        }
    }

    /**
     * The real mixing coefficients {@code h11}, {@code h12}, {@code h21} and {@code h22} of the
     * current bin.
     */
    private final double[] matrix = new double[4];

    /**
     * Computes the real mixing coefficients of one stereo band, ISO/IEC 14496-3 8.6.4.6.2.
     *
     * <p>With uncorrelated mono and decorrelated inputs of equal power, the outputs of both
     * procedures carry twice that power in total, split in the ratio of the intensity difference,
     * with the normalized correlation of the coherence (at least 0.05 for procedure
     * R<sub>b</sub>).
     *
     * @param procedureB whether to use mixing procedure R<sub>b</sub> instead of R<sub>a</sub>
     * @param fine whether the intensity difference index is on the fine quantizer
     * @param iidIndex the intensity difference index, from -7 to 7 (-15 to 15 when fine)
     * @param iccIndex the coherence index, from 0 to 7
     * @param h the destination of {@code h11}, {@code h12}, {@code h21} and {@code h22}
     */
    static void mixingMatrix(boolean procedureB, boolean fine, int iidIndex, int iccIndex, double[] h) {
        var steps = fine ? 15 : 7;
        if (!procedureB) {
            var scales = fine ? SCALE_FINE : SCALE_COARSE;
            var scaleRight = scales[steps + iidIndex];
            var scaleLeft = scales[steps - iidIndex];
            var alpha = 0.5 * Math.acos(ICC_VALUES[iccIndex]);
            var beta = alpha * (scaleRight - scaleLeft) / Math.sqrt(2);
            h[0] = scaleLeft * Math.cos(beta + alpha);
            h[1] = scaleRight * Math.cos(beta - alpha);
            h[2] = scaleLeft * Math.sin(beta + alpha);
            h[3] = scaleRight * Math.sin(beta - alpha);
        } else {
            var levels = fine ? IID_FINE : IID_COARSE;
            var c = Math.pow(10, levels[steps + iidIndex] / 20);
            var rho = Math.max(ICC_VALUES[iccIndex], 0.05);
            var alpha = c == 1 ? Math.PI / 4 : 0.5 * Math.atan(2 * c * rho / (c * c - 1));
            alpha -= Math.floor(alpha / (Math.PI / 2)) * (Math.PI / 2);
            var sum = c + 1 / c;
            var mu = 1 + (4 * rho * rho - 4) / (sum * sum);
            var root = Math.sqrt(mu);
            var gamma = Math.atan(Math.sqrt((1 - root) / (1 + root)));
            h[0] = Math.sqrt(2) * Math.cos(alpha) * Math.cos(gamma);
            h[1] = Math.sqrt(2) * Math.sin(alpha) * Math.cos(gamma);
            h[2] = -Math.sqrt(2) * Math.sin(alpha) * Math.sin(gamma);
            h[3] = Math.sqrt(2) * Math.cos(alpha) * Math.sin(gamma);
        }
    }

    /**
     * The cosine of each phase parameter index, ISO/IEC 14496-3 Table 8.31.
     */
    private static final double[] PHASE_COS = new double[8];

    /**
     * The sine of each phase parameter index.
     */
    private static final double[] PHASE_SIN = new double[8];

    static {
        for (var i = 0; i < 8; i++) {
            PHASE_COS[i] = Math.cos(i * Math.PI / 4);
            PHASE_SIN[i] = Math.sin(i * Math.PI / 4);
        }
    }

    /**
     * Splits QMF subbands 0 to 2 of a slot into the ten hybrid sub-subbands of the 20-band
     * configuration and advances the filter history.
     *
     * @param re the mono real parts
     * @param im the mono imaginary parts
     * @param row the index of the slot's first subband
     */
    private void analyze20(float[] re, float[] im, int row) {
        var ahead = row + 6 * 64;
        for (var q = 0; q < 8; q++) {
            filter(0, re[ahead], im[ahead], TAPS_20_RE[q], TAPS_20_IM[q]);
            scratchRe[q] = filterRe;
            scratchIm[q] = filterIm;
        }
        monoRe[0] = scratchRe[6];
        monoIm[0] = scratchIm[6];
        monoRe[1] = scratchRe[7];
        monoIm[1] = scratchIm[7];
        monoRe[2] = scratchRe[0];
        monoIm[2] = scratchIm[0];
        monoRe[3] = scratchRe[1];
        monoIm[3] = scratchIm[1];
        monoRe[4] = scratchRe[2] + scratchRe[5];
        monoIm[4] = scratchIm[2] + scratchIm[5];
        monoRe[5] = scratchRe[3] + scratchRe[4];
        monoIm[5] = scratchIm[3] + scratchIm[4];
        for (var band = 1; band < 3; band++) {
            var historyR = historyRe[band];
            var historyI = historyIm[band];
            double newestRe = re[ahead + band];
            double newestIm = im[ahead + band];
            double lowRe = PROTOTYPE_2[12] * newestRe;
            double lowIm = PROTOTYPE_2[12] * newestIm;
            double highRe = 0;
            double highIm = 0;
            for (var n = 0; n < 12; n++) {
                var tap = PROTOTYPE_2[n];
                if (tap == 0) {
                    continue;
                }
                if ((n & 1) == 0) {
                    lowRe += tap * historyR[n];
                    lowIm += tap * historyI[n];
                } else {
                    highRe += tap * historyR[n];
                    highIm += tap * historyI[n];
                }
            }
            // QMF subband 1 is spectrally reversed, so its low-pass output is the upper sub-subband
            var lower = band == 1 ? 6 : 8;
            var low = band == 1 ? lower + 1 : lower;
            var high = band == 1 ? lower : lower + 1;
            monoRe[low] = lowRe + highRe;
            monoIm[low] = lowIm + highIm;
            monoRe[high] = lowRe - highRe;
            monoIm[high] = lowIm - highIm;
        }
        advanceHistory(re, im, ahead);
    }

    /**
     * Splits QMF subbands 0 to 4 of a slot into the 32 hybrid sub-subbands of the 34-band
     * configuration and advances the filter history.
     *
     * @param re the mono real parts
     * @param im the mono imaginary parts
     * @param row the index of the slot's first subband
     */
    private void analyze34(float[] re, float[] im, int row) {
        var ahead = row + 6 * 64;
        for (var k = 0; k < MAX_HYBRID; k++) {
            var band = k < 12 ? 0 : k < 20 ? 1 : 2 + (k - 20) / 4;
            filter(band, re[ahead + band], im[ahead + band], TAPS_34_RE[k], TAPS_34_IM[k]);
            monoRe[k] = filterRe;
            monoIm[k] = filterIm;
        }
        advanceHistory(re, im, ahead);
    }

    /**
     * The real part of the last {@link #filter} output.
     */
    private double filterRe;

    /**
     * The imaginary part of the last {@link #filter} output.
     */
    private double filterIm;

    /**
     * Runs one complex hybrid filter over a QMF subband's history and its newest sample,
     * leaving the output in {@link #filterRe} and {@link #filterIm}.
     *
     * @param band the QMF subband
     * @param newestRe the newest sample's real part
     * @param newestIm the newest sample's imaginary part
     * @param tapsRe the real parts of the 13 taps
     * @param tapsIm the imaginary parts of the 13 taps
     */
    private void filter(int band, double newestRe, double newestIm, double[] tapsRe, double[] tapsIm) {
        var historyR = historyRe[band];
        var historyI = historyIm[band];
        double sumRe = 0;
        double sumIm = 0;
        for (var n = 0; n < 12; n++) {
            sumRe += tapsRe[n] * historyR[n] - tapsIm[n] * historyI[n];
            sumIm += tapsRe[n] * historyI[n] + tapsIm[n] * historyR[n];
        }
        sumRe += tapsRe[12] * newestRe - tapsIm[12] * newestIm;
        sumIm += tapsRe[12] * newestIm + tapsIm[12] * newestRe;
        filterRe = sumRe;
        filterIm = sumIm;
    }

    /**
     * Appends the newest sample of QMF subbands 0 to 4 to the hybrid filter history.
     *
     * @param re the mono real parts
     * @param im the mono imaginary parts
     * @param ahead the index of the look-ahead slot's first subband
     */
    private void advanceHistory(float[] re, float[] im, int ahead) {
        for (var band = 0; band < 5; band++) {
            var historyR = historyRe[band];
            var historyI = historyIm[band];
            System.arraycopy(historyR, 1, historyR, 0, 11);
            System.arraycopy(historyI, 1, historyI, 0, 11);
            historyR[11] = re[ahead + band];
            historyI[11] = im[ahead + band];
        }
    }

    /**
     * Measures the power of each bin and derives the transient attenuation, ISO/IEC 14496-3
     * 8.6.4.5.3.
     *
     * @param layout the stereo band layout
     * @param re the mono real parts
     * @param im the mono imaginary parts
     * @param row the index of the slot's first subband
     * @param usb the subband after the last one that carries signal
     */
    private void detectTransients(Layout layout, float[] re, float[] im, int row, int usb) {
        var bins = layout.bins;
        Arrays.fill(power, 0, bins, 0);
        for (var k = 0; k < layout.hybridBands; k++) {
            power[layout.hybridBins[k]] += monoRe[k] * monoRe[k] + monoIm[k] * monoIm[k];
        }
        var end = Math.min(usb, 64);
        for (var band = layout.firstQmf; band < end; band++) {
            double r = re[row + band];
            double i = im[row + band];
            power[layout.qmfBins[band]] += r * r + i * i;
        }
        for (var bin = 0; bin < bins; bin++) {
            var decayed = peakPower[bin] * 0.765928338364649;
            var difference = peakDifference[bin] * 0.75;
            if (decayed < power[bin]) {
                decayed = power[bin];
            } else {
                difference += (decayed - power[bin]) * 0.25;
            }
            peakPower[bin] = decayed;
            peakDifference[bin] = difference;
            var smoothed = smoothPower[bin] + (power[bin] - smoothPower[bin]) * 0.25;
            smoothPower[bin] = smoothed;
            var scaledDifference = difference * 1.5;
            attenuation[bin] = scaledDifference <= smoothed ? 1 : smoothed / scaledDifference;
        }
    }

    /**
     * Builds the decorrelated signal of a slot, ISO/IEC 14496-3 8.6.4.5.
     *
     * @param layout the stereo band layout
     * @param re the mono real parts
     * @param im the mono imaginary parts
     * @param rightRe the destination real parts of the decorrelated QMF subbands
     * @param rightIm the destination imaginary parts
     * @param row the index of the slot's first subband
     * @param usb the subband after the last one that carries signal
     */
    private void decorrelate(Layout layout, float[] re, float[] im, float[] rightRe, float[] rightIm, int row,
                             int usb) {
        for (var k = 0; k < layout.hybridBands; k++) {
            allPass(layout, k, monoRe[k], monoIm[k]);
            var gain = attenuation[layout.hybridBins[k]];
            sideRe[k] = allPassRe * gain;
            sideIm[k] = allPassIm * gain;
        }
        var allPassEnd = Math.min(usb, ALLPASS_END);
        for (var band = layout.firstQmf; band < allPassEnd; band++) {
            allPass(layout, band + layout.qmfOffset, re[row + band], im[row + band]);
            var gain = attenuation[layout.qmfBins[band]];
            rightRe[row + band] = (float) (allPassRe * gain);
            rightIm[row + band] = (float) (allPassIm * gain);
        }
        for (var band = Math.max(allPassEnd, layout.firstQmf); band < ALLPASS_END; band++) {
            allPass(layout, band + layout.qmfOffset, 0, 0);
        }
        var delayEnd = Math.min(usb, 64);
        for (var band = ALLPASS_END; band < delayEnd; band++) {
            var line = band - ALLPASS_END;
            var length = band < SHORT_DELAY_START ? LONG_DELAY : 1;
            var position = delayIndex[line];
            var gain = attenuation[layout.qmfBins[band]];
            rightRe[row + band] = (float) (delayRe[line][position] * gain);
            rightIm[row + band] = (float) (delayIm[line][position] * gain);
            delayRe[line][position] = re[row + band];
            delayIm[line][position] = im[row + band];
            delayIndex[line] = position + 1 == length ? 0 : position + 1;
        }
        for (var band = delayEnd; band < 64; band++) {
            rightRe[row + band] = 0;
            rightIm[row + band] = 0;
        }
        shortDelayIndex ^= 1;
        for (var link = 0; link < 3; link++) {
            linkIndex[link] = linkIndex[link] + 1 == LINK_DELAYS[link] ? 0 : linkIndex[link] + 1;
        }
    }

    /**
     * The real part of the last all-pass output.
     */
    private double allPassRe;

    /**
     * The imaginary part of the last all-pass output.
     */
    private double allPassIm;

    /**
     * Runs one sample through the fractional delay and the three all-pass links of a
     * sub-subband, leaving the output in {@link #allPassRe} and {@link #allPassIm}.
     *
     * @param layout the stereo band layout
     * @param k the all-pass sub-subband
     * @param inRe the input real part
     * @param inIm the input imaginary part
     */
    private void allPass(Layout layout, int k, double inRe, double inIm) {
        var delayedRe = shortDelayRe[k][shortDelayIndex];
        var delayedIm = shortDelayIm[k][shortDelayIndex];
        shortDelayRe[k][shortDelayIndex] = inRe;
        shortDelayIm[k][shortDelayIndex] = inIm;
        var re = delayedRe * layout.phaseRe[k] - delayedIm * layout.phaseIm[k];
        var im = delayedIm * layout.phaseRe[k] + delayedRe * layout.phaseIm[k];
        for (var link = 0; link < 3; link++) {
            var position = linkIndex[link];
            var storedRe = linkRe[link][position][k];
            var storedIm = linkIm[link][position][k];
            var factorRe = layout.linkPhaseRe[link][k];
            var factorIm = layout.linkPhaseIm[link][k];
            var rotatedRe = storedRe * factorRe - storedIm * factorIm;
            var rotatedIm = storedIm * factorRe + storedRe * factorIm;
            var decay = layout.linkDecay[link][k];
            var outRe = rotatedRe - decay * re;
            var outIm = rotatedIm - decay * im;
            linkRe[link][position][k] = re + decay * outRe;
            linkIm[link][position][k] = im + decay * outIm;
            re = outRe;
            im = outIm;
        }
        allPassRe = re;
        allPassIm = im;
    }

    /**
     * Mixes the mono and decorrelated signals of a slot into the two channels, ISO/IEC 14496-3
     * 8.6.4.6.
     *
     * @param layout the stereo band layout
     * @param re the mono real parts, overwritten with the left channel
     * @param im the mono imaginary parts, overwritten with the left channel
     * @param rightRe the decorrelated real parts, overwritten with the right channel
     * @param rightIm the decorrelated imaginary parts, overwritten with the right channel
     * @param row the index of the slot's first subband
     * @param usb the subband after the last one that carries signal
     */
    private void mix(Layout layout, float[] re, float[] im, float[] rightRe, float[] rightIm, int row, int usb) {
        for (var c = 0; c < 4; c++) {
            var currentRe = mixRe[c];
            var currentIm = mixIm[c];
            var deltaRe = stepRe[c];
            var deltaIm = stepIm[c];
            for (var group = 0; group < layout.groups; group++) {
                currentRe[group] += deltaRe[group];
                currentIm[group] += deltaIm[group];
            }
        }
        var h11Re = mixRe[0];
        var h11Im = mixIm[0];
        var h12Re = mixRe[1];
        var h12Im = mixIm[1];
        var h21Re = mixRe[2];
        var h21Im = mixIm[2];
        var h22Re = mixRe[3];
        var h22Im = mixIm[3];
        for (var k = 0; k < layout.hybridBands; k++) {
            var sRe = monoRe[k];
            var sIm = monoIm[k];
            var dRe = sideRe[k];
            var dIm = sideIm[k];
            monoRe[k] = h11Re[k] * sRe - h11Im[k] * sIm + h21Re[k] * dRe - h21Im[k] * dIm;
            monoIm[k] = h11Im[k] * sRe + h11Re[k] * sIm + h21Im[k] * dRe + h21Re[k] * dIm;
            sideRe[k] = h12Re[k] * sRe - h12Im[k] * sIm + h22Re[k] * dRe - h22Im[k] * dIm;
            sideIm[k] = h12Im[k] * sRe + h12Re[k] * sIm + h22Im[k] * dRe + h22Re[k] * dIm;
        }
        var borders = layout.qmfBorders;
        for (var i = 0; i + 1 < borders.length; i++) {
            var group = layout.hybridBands + i;
            var end = Math.min(usb, borders[i + 1]);
            for (var band = borders[i]; band < end; band++) {
                var index = row + band;
                double sRe = re[index];
                double sIm = im[index];
                double dRe = rightRe[index];
                double dIm = rightIm[index];
                re[index] = (float) (h11Re[group] * sRe - h11Im[group] * sIm + h21Re[group] * dRe - h21Im[group] * dIm);
                im[index] = (float) (h11Im[group] * sRe + h11Re[group] * sIm + h21Im[group] * dRe + h21Re[group] * dIm);
                rightRe[index] = (float) (h12Re[group] * sRe - h12Im[group] * sIm + h22Re[group] * dRe
                        - h22Im[group] * dIm);
                rightIm[index] = (float) (h12Im[group] * sRe + h12Re[group] * sIm + h22Im[group] * dRe
                        + h22Re[group] * dIm);
            }
        }
        for (var band = usb; band < 64; band++) {
            rightRe[row + band] = 0;
            rightIm[row + band] = 0;
        }
    }

    /**
     * Merges the hybrid sub-subbands of both channels back into their QMF subbands, ISO/IEC
     * 14496-3 8.6.4.7.
     *
     * @param re the left real parts
     * @param im the left imaginary parts
     * @param rightRe the right real parts
     * @param rightIm the right imaginary parts
     * @param row the index of the slot's first subband
     */
    private void synthesize(float[] re, float[] im, float[] rightRe, float[] rightIm, int row) {
        var splits = use34 ? SPLITS_34 : SPLITS_20;
        var k = 0;
        for (var band = 0; band < splits.length; band++) {
            double leftR = 0;
            double leftI = 0;
            double rightR = 0;
            double rightI = 0;
            for (var end = k + splits[band]; k < end; k++) {
                leftR += monoRe[k];
                leftI += monoIm[k];
                rightR += sideRe[k];
                rightI += sideIm[k];
            }
            re[row + band] = (float) leftR;
            im[row + band] = (float) leftI;
            rightRe[row + band] = (float) rightR;
            rightIm[row + band] = (float) rightI;
        }
    }

    /**
     * The number of hybrid sub-subbands of each split QMF subband of the 20-band configuration.
     */
    private static final int[] SPLITS_20 = {6, 2, 2};

    /**
     * The number of hybrid sub-subbands of each split QMF subband of the 34-band configuration.
     */
    private static final int[] SPLITS_34 = {12, 8, 4, 4, 4};

    /**
     * The constants of one stereo band configuration: its hybrid sub-subbands, parameter groups
     * and decorrelator coefficients.
     */
    private static final class Layout {
        /**
         * The number of hybrid sub-subbands.
         */
        final int hybridBands;

        /**
         * The offset from a QMF subband past the split ones to its sub-subband index.
         */
        final int qmfOffset;

        /**
         * The first QMF subband that is not split.
         */
        final int firstQmf;

        /**
         * The number of parameter bins.
         */
        final int bins;

        /**
         * The number of bins that carry phase parameters.
         */
        final int ipdBins;

        /**
         * The parameter bin of each hybrid sub-subband.
         */
        final int[] hybridBins;

        /**
         * The parameter bin of each QMF subband past the split ones.
         */
        final int[] qmfBins = new int[64];

        /**
         * The borders of the QMF-only parameter groups.
         */
        final int[] qmfBorders;

        /**
         * The number of parameter groups: one per hybrid sub-subband, then the QMF-only groups.
         */
        final int groups;

        /**
         * The parameter bin of each group.
         */
        final int[] groupBins;

        /**
         * Whether each group takes the complex conjugate of its bin's coefficients.
         */
        final boolean[] groupConjugated;

        /**
         * The real part of the fractional delay phase factor of each all-pass sub-subband.
         */
        final double[] phaseRe;

        /**
         * The imaginary part of the fractional delay phase factor of each all-pass sub-subband.
         */
        final double[] phaseIm;

        /**
         * The real parts of the link phase factors, by link and all-pass sub-subband.
         */
        final double[][] linkPhaseRe;

        /**
         * The imaginary parts of the link phase factors.
         */
        final double[][] linkPhaseIm;

        /**
         * The decay coefficients, by link and all-pass sub-subband.
         */
        final double[][] linkDecay;

        /**
         * Builds a layout from the tables of ISO/IEC 14496-3 8.6.4.
         *
         * @param hybridBands the number of hybrid sub-subbands
         * @param qmfOffset the offset from a QMF subband to its sub-subband index
         * @param firstQmf the first QMF subband that is not split
         * @param bins the number of parameter bins
         * @param ipdBins the number of bins that carry phase parameters
         * @param allPassBands the number of all-pass sub-subbands
         * @param decayCutoff the last QMF subband whose all-pass filters do not decay
         * @param hybridBins the parameter bin of each hybrid sub-subband
         * @param conjugatedStart the first hybrid sub-subband with conjugated coefficients
         * @param conjugatedEnd the hybrid sub-subband after the last conjugated one
         * @param qmfBorders the borders of the QMF-only parameter groups
         * @param centers the center frequency of each hybrid sub-subband
         */
        Layout(int hybridBands, int qmfOffset, int firstQmf, int bins, int ipdBins, int allPassBands, int decayCutoff,
               int[] hybridBins, int conjugatedStart, int conjugatedEnd, int[] qmfBorders, double[] centers) {
            this.hybridBands = hybridBands;
            this.qmfOffset = qmfOffset;
            this.firstQmf = firstQmf;
            this.bins = bins;
            this.ipdBins = ipdBins;
            this.hybridBins = hybridBins;
            this.qmfBorders = qmfBorders;
            this.groups = hybridBands + qmfBorders.length - 1;
            this.groupBins = new int[groups];
            this.groupConjugated = new boolean[groups];
            for (var k = 0; k < hybridBands; k++) {
                groupBins[k] = hybridBins[k];
                groupConjugated[k] = k >= conjugatedStart && k < conjugatedEnd;
            }
            var firstQmfBin = bins - (qmfBorders.length - 1);
            for (var i = 0; i + 1 < qmfBorders.length; i++) {
                groupBins[hybridBands + i] = firstQmfBin + i;
                for (var band = qmfBorders[i]; band < qmfBorders[i + 1]; band++) {
                    qmfBins[band] = firstQmfBin + i;
                }
            }
            this.phaseRe = new double[allPassBands];
            this.phaseIm = new double[allPassBands];
            this.linkPhaseRe = new double[3][allPassBands];
            this.linkPhaseIm = new double[3][allPassBands];
            this.linkDecay = new double[3][allPassBands];
            for (var k = 0; k < allPassBands; k++) {
                double center;
                double decay;
                if (k < hybridBands) {
                    center = centers[k];
                    decay = 1;
                } else {
                    var qmf = k - qmfOffset;
                    center = qmf + 0.5;
                    decay = qmf <= decayCutoff ? 1 : Math.max(0, 1 - 0.05 * (qmf - decayCutoff));
                }
                var angle = -Math.PI * PHASE_FRACTION * center;
                phaseRe[k] = Math.cos(angle);
                phaseIm[k] = Math.sin(angle);
                for (var link = 0; link < 3; link++) {
                    var linkAngle = -Math.PI * LINK_FRACTIONS[link] * center;
                    linkPhaseRe[link][k] = Math.cos(linkAngle);
                    linkPhaseIm[link][k] = Math.sin(linkAngle);
                    linkDecay[link][k] = LINK_DECAYS[link] * decay;
                }
            }
        }
    }
}
