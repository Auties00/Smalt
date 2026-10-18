/* Ports PacketVideo aacdec, Android 4.0.4 (ps_*.cpp, the baseline parametric stereo decoder).
   Copyright 1998-2010 PacketVideo; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.sbr;

import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

import java.util.Arrays;

/**
 * Decodes parametric stereo (HE-AAC v2): turns the QMF subbands of the mono core and its
 * spectral band replication into a left and a right channel, guided by the inter-channel
 * intensity differences and coherences of each frame.
 *
 * <p>Each slot of the mono matrix is split into hybrid subbands below QMF subband 3, its
 * per-band power drives a transient detector, a decorrelated copy is built by all-pass filters
 * (below QMF subband 23) or plain delays (above), and the mono and decorrelated signals are
 * mixed into the two channels with the parameters of the slot's envelope, interpolated over the
 * envelope. The hybrid filterbank reads six slots ahead, which the spectral band replication
 * buffer provides for the three lowest subbands, so the stage adds no delay.
 *
 * <p>This is the baseline decoder of ISO/IEC 14496-3 Annex 8.A: parameters coded on 34 bands
 * are mapped onto the 20-band configuration, and the phase parameters of the extension (IPD and
 * OPD) are skipped. Until the first parametric stereo data arrives, both channels equal the mono
 * signal.
 *
 * @implNote This implementation ports the baseline parametric stereo decoder of the PacketVideo
 * AAC decoder from fixed to floating point: its bitstream reader, its 20-band
 * groups, its hybrid filters (whose coefficients are those of ISO/IEC 14496-3), its transient
 * detector and decorrelator, and its stereo mixing; the decorrelator's phase factors and decay
 * coefficients are computed from the formulas its tables were derived from.
 */
final class PsDecoder {
    /**
     * The number of parameter bins of the 20-band configuration.
     */
    private static final int BINS = 20;

    /**
     * The number of hybrid subbands (from QMF subbands 0 to 2).
     */
    private static final int HYBRID = 10;

    /**
     * The number of parameter groups: the hybrid subbands followed by groups of QMF subbands.
     */
    private static final int GROUPS = 22;

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
     * The borders of the parameter groups: one hybrid subband each for the first ten groups (in
     * group order), then QMF subband ranges.
     */
    private static final int[] GROUP_BORDERS = {4, 5, 0, 1, 2, 3, 7, 6, 8, 9, 3, 4, 5, 6, 7, 8, 9, 11, 14, 18, 23, 35, 64};

    /**
     * The parameter bin of each group.
     */
    private static final int[] GROUP_BINS = {1, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19};

    /**
     * The center frequency of each hybrid subband, in QMF subband units.
     */
    private static final double[] HYBRID_CENTERS = {0.125, 0.375, 0.625, 0.875, -0.375, -0.125, 1.75, 1.25, 2.25, 2.75};

    /**
     * The 13 taps of the prototype of the 8-band complex hybrid filter.
     */
    private static final double[] PROTOTYPE_8 = {
            0.00746082949812, 0.02270420949825, 0.04546865930473, 0.07266113929591, 0.09885108575264,
            0.11793710567217, 0.125, 0.11793710567217, 0.09885108575264, 0.07266113929591,
            0.04546865930473, 0.02270420949825, 0.00746082949812
    };

    /**
     * The 13 taps of the prototype of the 2-band real hybrid filter.
     */
    private static final double[] PROTOTYPE_2 = {
            0, 0.01899487526049, 0, -0.07293139167538, 0, 0.30596630545168, 0.5,
            0.30596630545168, 0, -0.07293139167538, 0, 0.01899487526049, 0
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
     * The real parts of the 8-band hybrid filter taps, by subband and tap.
     */
    private static final double[][] HYBRID8_RE = new double[8][13];

    /**
     * The imaginary parts of the 8-band hybrid filter taps.
     */
    private static final double[][] HYBRID8_IM = new double[8][13];

    static {
        for (var q = 0; q < 8; q++) {
            var center = q < 4 ? (q + 0.5) / 4 : (q + 0.5) / 4 - 2;
            for (var n = 0; n < 13; n++) {
                var angle = -Math.PI * center * (n - 6);
                HYBRID8_RE[q][n] = PROTOTYPE_8[n] * Math.cos(angle);
                HYBRID8_IM[q][n] = PROTOTYPE_8[n] * Math.sin(angle);
            }
        }
    }

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
     * The frequency resolution of the intensity differences: 0 for 10 bins, 1 for 20, 2 for 34.
     */
    private int iidResolution;

    /**
     * The frequency resolution of the coherences.
     */
    private int iccResolution;

    /**
     * Whether the intensity differences use the fine quantizer.
     */
    private boolean fineIid;

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
     * The envelope borders, in QMF slots.
     */
    private final int[] borders = new int[6];

    /**
     * The intensity difference indices, by envelope and bin.
     */
    private final int[][] iid = new int[6][34];

    /**
     * The coherence indices, by envelope and bin.
     */
    private final int[][] icc = new int[6][34];

    /**
     * Whether each envelope's intensity differences are coded in time.
     */
    private final boolean[] iidTime = new boolean[5];

    /**
     * Whether each envelope's coherences are coded in time.
     */
    private final boolean[] iccTime = new boolean[5];

    /**
     * The intensity difference indices of the previous frame's last envelope.
     */
    private final int[] iidPrevious = new int[34];

    /**
     * The coherence indices of the previous frame's last envelope.
     */
    private final int[] iccPrevious = new int[34];

    /**
     * The hybrid filter input history of QMF subbands 0 to 2, real parts, twelve slots each.
     */
    private final double[][] hybridRe = new double[3][12];

    /**
     * The hybrid filter input history, imaginary parts.
     */
    private final double[][] hybridIm = new double[3][12];

    /**
     * The mono hybrid subbands of the current slot, real parts.
     */
    private final double[] monoHybridRe = new double[HYBRID];

    /**
     * The mono hybrid subbands of the current slot, imaginary parts.
     */
    private final double[] monoHybridIm = new double[HYBRID];

    /**
     * The decorrelated hybrid subbands of the current slot, real parts.
     */
    private final double[] sideHybridRe = new double[HYBRID];

    /**
     * The decorrelated hybrid subbands of the current slot, imaginary parts.
     */
    private final double[] sideHybridIm = new double[HYBRID];

    /**
     * The smoothed power of each bin.
     */
    private final double[] smoothPower = new double[BINS];

    /**
     * The decaying peak power of each bin.
     */
    private final double[] peakPower = new double[BINS];

    /**
     * The smoothed difference between the peak and the power of each bin.
     */
    private final double[] peakDifference = new double[BINS];

    /**
     * The power of each bin in the current slot.
     */
    private final double[] power = new double[BINS];

    /**
     * The transient attenuation of each bin in the current slot.
     */
    private final double[] attenuation = new double[BINS];

    /**
     * The two-slot delay of the all-pass subbands (hybrid subbands first, then QMF subbands 3
     * to 22), real parts, by subband and position.
     */
    private final double[][] shortDelayRe = new double[HYBRID + 20][2];

    /**
     * The two-slot delay of the all-pass subbands, imaginary parts.
     */
    private final double[][] shortDelayIm = new double[HYBRID + 20][2];

    /**
     * The position in {@link #shortDelayRe} of the current slot.
     */
    private int shortDelayIndex;

    /**
     * The delay lines of the all-pass links, real parts, by link, position and subband.
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
     * The phase factor of each all-pass subband, real parts.
     */
    private final double[] phaseRe = new double[HYBRID + 20];

    /**
     * The phase factor of each all-pass subband, imaginary parts.
     */
    private final double[] phaseIm = new double[HYBRID + 20];

    /**
     * The phase factor of each link and all-pass subband, real parts.
     */
    private final double[][] linkPhaseRe = new double[3][HYBRID + 20];

    /**
     * The phase factor of each link and all-pass subband, imaginary parts.
     */
    private final double[][] linkPhaseIm = new double[3][HYBRID + 20];

    /**
     * The decay coefficient of each link and all-pass subband.
     */
    private final double[][] linkDecay = new double[3][HYBRID + 20];

    /**
     * The mixing coefficients at the end of the previous envelope, {@code h11}, by group.
     */
    private final double[] h11 = new double[GROUPS];

    /**
     * The mixing coefficients {@code h12} at the end of the previous envelope.
     */
    private final double[] h12 = new double[GROUPS];

    /**
     * The mixing coefficients {@code h21} at the end of the previous envelope.
     */
    private final double[] h21 = new double[GROUPS];

    /**
     * The mixing coefficients {@code h22} at the end of the previous envelope.
     */
    private final double[] h22 = new double[GROUPS];

    /**
     * The interpolated coefficients {@code h11} of the current slot.
     */
    private final double[] m11 = new double[GROUPS];

    /**
     * The interpolated coefficients {@code h12} of the current slot.
     */
    private final double[] m12 = new double[GROUPS];

    /**
     * The interpolated coefficients {@code h21} of the current slot.
     */
    private final double[] m21 = new double[GROUPS];

    /**
     * The interpolated coefficients {@code h22} of the current slot.
     */
    private final double[] m22 = new double[GROUPS];

    /**
     * The per-slot increment of {@link #m11}.
     */
    private final double[] d11 = new double[GROUPS];

    /**
     * The per-slot increment of {@link #m12}.
     */
    private final double[] d12 = new double[GROUPS];

    /**
     * The per-slot increment of {@link #m21}.
     */
    private final double[] d21 = new double[GROUPS];

    /**
     * The per-slot increment of {@link #m22}.
     */
    private final double[] d22 = new double[GROUPS];

    /**
     * Creates a decoder with silent filter memories.
     */
    PsDecoder() {
        for (var link = 0; link < 3; link++) {
            linkRe[link] = new double[LINK_DELAYS[link]][HYBRID + 20];
            linkIm[link] = new double[LINK_DELAYS[link]][HYBRID + 20];
        }
        for (var band = 0; band < HYBRID + 20; band++) {
            double center;
            double decay;
            if (band < HYBRID) {
                center = HYBRID_CENTERS[band];
                decay = 1;
            } else {
                var qmf = band - HYBRID + 3;
                center = qmf + 0.5;
                decay = Math.max(0, 1 - 0.05 * (qmf - 3));
            }
            var angle = -Math.PI * PHASE_FRACTION * center;
            phaseRe[band] = Math.cos(angle);
            phaseIm[band] = Math.sin(angle);
            for (var link = 0; link < 3; link++) {
                var linkAngle = -Math.PI * LINK_FRACTIONS[link] * center;
                linkPhaseRe[link][band] = Math.cos(linkAngle);
                linkPhaseIm[link][band] = Math.sin(linkAngle);
                linkDecay[link][band] = LINK_DECAYS[link] * decay;
            }
        }
        Arrays.fill(h11, 1);
        Arrays.fill(h12, 1);
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
                var mode = reader.read(3);
                fineIid = mode > 2;
                iidResolution = mode > 2 ? mode - 3 : mode;
            }
            enableIcc = reader.readFlag();
            if (enableIcc) {
                var mode = reader.read(3);
                iccResolution = mode > 2 ? mode - 3 : mode;
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
        if (!headerReceived || iidResolution > 2 || iccResolution > 2) {
            dataAvailable = false;
            return (int) (reader.bitPosition() - start);
        }
        var iidBins = binCount(iidResolution);
        var iccBins = binCount(iccResolution);
        for (var e = 0; e < envelopes; e++) {
            if (enableIid) {
                iidTime[e] = reader.readFlag();
                var table = fineIid ? (iidTime[e] ? SbrHuffman.IID_FINE_TIME : SbrHuffman.IID_FINE_FREQUENCY)
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
        if (enableExtension) {
            var count = reader.read(4);
            if (count == 15) {
                count += reader.read(8);
            }
            reader.skip(8L * count);
        }
        dataAvailable = true;
        seen = true;
        return (int) (reader.bitPosition() - start);
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
     * Resolves the frame's parameters: differential decoding, the envelope borders and the
     * mapping onto 20 bins.
     *
     * @param slots the number of QMF slots per frame
     */
    private void resolve(int slots) {
        var fixedBorders = !variableBorders || !dataAvailable;
        if (!dataAvailable) {
            envelopes = 0;
        }
        var steps = fineIid ? 15 : 7;
        for (var e = 0; e < envelopes; e++) {
            var iidReference = e == 0 ? iidPrevious : iid[e - 1];
            var iccReference = e == 0 ? iccPrevious : icc[e - 1];
            differential(enableIid, iid[e], iidReference, iidTime[e], binCount(iidResolution), -steps, steps);
            differential(enableIcc, icc[e], iccReference, iccTime[e], binCount(iccResolution), 0, 7);
        }
        if (envelopes == 0) {
            envelopes = 1;
            if (enableIid) {
                System.arraycopy(iidPrevious, 0, iid[0], 0, 34);
            } else {
                Arrays.fill(iid[0], 0);
            }
            if (enableIcc) {
                System.arraycopy(iccPrevious, 0, icc[0], 0, 34);
            } else {
                Arrays.fill(icc[0], 0);
            }
        }
        System.arraycopy(iid[envelopes - 1], 0, iidPrevious, 0, 34);
        System.arraycopy(icc[envelopes - 1], 0, iccPrevious, 0, 34);
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
                System.arraycopy(iid[envelopes - 2], 0, iid[envelopes - 1], 0, 34);
                System.arraycopy(icc[envelopes - 2], 0, icc[envelopes - 1], 0, 34);
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
        for (var e = 0; e < envelopes; e++) {
            if (iidResolution == 2) {
                mapTo20(iid[e]);
            }
            if (iccResolution == 2) {
                mapTo20(icc[e]);
            }
        }
    }

    /**
     * Resolves the differential coding of one envelope's parameters, clamping them to their
     * range, and widens 10-bin parameters to 20 bins.
     *
     * @param enabled whether the parameter is transmitted; zero otherwise
     * @param values the coded values, resolved in place
     * @param reference the previous envelope's values
     * @param time whether the values are coded in time
     * @param count the number of bins
     * @param min the smallest index
     * @param max the largest index
     */
    private static void differential(boolean enabled, int[] values, int[] reference, boolean time, int count, int min,
                                     int max) {
        var stride = count == 10 ? 2 : 1;
        if (enabled) {
            if (!time) {
                values[0] = Math.clamp(values[0], min, max);
                for (var i = 1; i < count; i++) {
                    values[i] = Math.clamp(values[i - 1] + values[i], min, max);
                }
            } else {
                for (var i = 0; i < count; i++) {
                    values[i] = Math.clamp(reference[i * stride] + values[i], min, max);
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
    }

    /**
     * Maps 34-bin parameters onto the 20 bins of the baseline decoder, ISO/IEC 14496-3 Annex
     * 8.A.
     *
     * @param values the parameters, mapped in place
     */
    private static void mapTo20(int[] values) {
        values[0] = (2 * values[0] + values[1]) / 3;
        values[1] = (values[1] + 2 * values[2]) / 3;
        values[2] = (2 * values[3] + values[4]) / 3;
        values[3] = (values[4] + 2 * values[5]) / 3;
        values[4] = (values[6] + values[7]) >> 1;
        values[5] = (values[8] + values[9]) >> 1;
        values[6] = values[10];
        values[7] = values[11];
        values[8] = (values[12] + values[13]) >> 1;
        values[9] = (values[14] + values[15]) >> 1;
        values[10] = values[16];
        values[11] = values[17];
        values[12] = values[18];
        values[13] = values[19];
        values[14] = (values[20] + values[21]) >> 1;
        values[15] = (values[22] + values[23]) >> 1;
        values[16] = (values[24] + values[25]) >> 1;
        values[17] = (values[26] + values[27]) >> 1;
        values[18] = (values[28] + values[29] + values[30] + values[31]) >> 2;
        values[19] = (values[32] + values[33]) >> 1;
    }

    /**
     * Turns the mono subband matrix of a frame into the left (in place) and right channels.
     *
     * @param re the mono real parts, 64 per slot, with six slots of look-ahead for the
     * three lowest subbands; overwritten with the left channel
     * @param im the mono imaginary parts
     * @param rightRe the destination right real parts, 64 per slot
     * @param rightIm the destination right imaginary parts
     * @param slots the number of slots
     * @param usb the subband after the last one that carries signal
     */
    void process(float[] re, float[] im, float[] rightRe, float[] rightIm, int slots, int usb) {
        resolve(slots);
        var envelope = 0;
        for (var slot = 0; slot < slots; slot++) {
            if (envelope < envelopes && slot == borders[envelope]) {
                startEnvelope(envelope, slots);
                envelope++;
            }
            var row = slot * 64;
            analyzeHybrid(re, im, row);
            detectTransients(re, im, row, usb);
            decorrelate(re, im, rightRe, rightIm, row, usb);
            mix(re, im, rightRe, rightIm, row, usb);
        }
    }

    /**
     * Computes the mixing coefficients at the end of an envelope and the per-slot increments
     * that interpolate towards them.
     *
     * @param envelope the envelope index
     * @param slots the number of slots per frame
     */
    private void startEnvelope(int envelope, int slots) {
        var length = borders[envelope + 1] - borders[envelope];
        var scales = fineIid ? SCALE_FINE : SCALE_COARSE;
        var steps = fineIid ? 15 : 7;
        for (var group = 0; group < GROUPS; group++) {
            var bin = GROUP_BINS[group];
            var iidIndex = iid[envelope][bin];
            var scaleRight = scales[steps + iidIndex];
            var scaleLeft = scales[steps - iidIndex];
            var alpha = 0.5 * Math.acos(ICC_VALUES[icc[envelope][bin]]);
            var beta = alpha * (scaleRight - scaleLeft) / Math.sqrt(2);
            var n11 = scaleLeft * Math.cos(beta + alpha);
            var n12 = scaleRight * Math.cos(beta - alpha);
            var n21 = scaleLeft * Math.sin(beta + alpha);
            var n22 = scaleRight * Math.sin(beta - alpha);
            m11[group] = h11[group];
            m12[group] = h12[group];
            m21[group] = h21[group];
            m22[group] = h22[group];
            d11[group] = (n11 - h11[group]) / length;
            d12[group] = (n12 - h12[group]) / length;
            d21[group] = (n21 - h21[group]) / length;
            d22[group] = (n22 - h22[group]) / length;
            h11[group] = n11;
            h12[group] = n12;
            h21[group] = n21;
            h22[group] = n22;
        }
    }

    /**
     * Splits QMF subbands 0 to 2 of a slot into the ten hybrid subbands.
     *
     * @param re the mono real parts
     * @param im the mono imaginary parts
     * @param row the index of the slot's first subband
     */
    private void analyzeHybrid(float[] re, float[] im, int row) {
        var ahead = row + 6 * 64;
        for (var band = 0; band < 3; band++) {
            var historyRe = hybridRe[band];
            var historyIm = hybridIm[band];
            double newestRe = re[ahead + band];
            double newestIm = im[ahead + band];
            if (band == 0) {
                for (var q = 0; q < 8; q++) {
                    var tapsRe = HYBRID8_RE[q];
                    var tapsIm = HYBRID8_IM[q];
                    double sumRe = 0;
                    double sumIm = 0;
                    for (var n = 0; n < 12; n++) {
                        sumRe += tapsRe[n] * historyRe[n] - tapsIm[n] * historyIm[n];
                        sumIm += tapsRe[n] * historyIm[n] + tapsIm[n] * historyRe[n];
                    }
                    sumRe += tapsRe[12] * newestRe - tapsIm[12] * newestIm;
                    sumIm += tapsRe[12] * newestIm + tapsIm[12] * newestRe;
                    hybridScratchRe[q] = sumRe;
                    hybridScratchIm[q] = sumIm;
                }
                monoHybridRe[0] = hybridScratchRe[0];
                monoHybridIm[0] = hybridScratchIm[0];
                monoHybridRe[1] = hybridScratchRe[1];
                monoHybridIm[1] = hybridScratchIm[1];
                monoHybridRe[2] = hybridScratchRe[2] + hybridScratchRe[5];
                monoHybridIm[2] = hybridScratchIm[2] + hybridScratchIm[5];
                monoHybridRe[3] = hybridScratchRe[3] + hybridScratchRe[4];
                monoHybridIm[3] = hybridScratchIm[3] + hybridScratchIm[4];
                monoHybridRe[4] = hybridScratchRe[6];
                monoHybridIm[4] = hybridScratchIm[6];
                monoHybridRe[5] = hybridScratchRe[7];
                monoHybridIm[5] = hybridScratchIm[7];
            } else {
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
                        lowRe += tap * historyRe[n];
                        lowIm += tap * historyIm[n];
                    } else {
                        highRe += tap * historyRe[n];
                        highIm += tap * historyIm[n];
                    }
                }
                var center = 4 + 2 * band;
                monoHybridRe[center] = lowRe + highRe;
                monoHybridIm[center] = lowIm + highIm;
                monoHybridRe[center + 1] = lowRe - highRe;
                monoHybridIm[center + 1] = lowIm - highIm;
            }
            System.arraycopy(historyRe, 1, historyRe, 0, 11);
            System.arraycopy(historyIm, 1, historyIm, 0, 11);
            historyRe[11] = newestRe;
            historyIm[11] = newestIm;
        }
    }

    /**
     * The 8-band hybrid outputs before merging, real parts.
     */
    private final double[] hybridScratchRe = new double[8];

    /**
     * The 8-band hybrid outputs before merging, imaginary parts.
     */
    private final double[] hybridScratchIm = new double[8];

    /**
     * Measures the power of each bin and derives the transient attenuation.
     *
     * @param re the mono real parts
     * @param im the mono imaginary parts
     * @param row the index of the slot's first subband
     * @param usb the subband after the last one that carries signal
     */
    private void detectTransients(float[] re, float[] im, int row, int usb) {
        power[0] = hybridPower(0) + hybridPower(5);
        power[1] = hybridPower(1) + hybridPower(4);
        power[2] = hybridPower(2);
        power[3] = hybridPower(3);
        power[4] = hybridPower(7);
        power[5] = hybridPower(6);
        power[6] = hybridPower(8);
        power[7] = hybridPower(9);
        for (var group = HYBRID; group < GROUPS; group++) {
            var end = Math.min(usb, GROUP_BORDERS[group + 1]);
            double sum = 0;
            for (var band = GROUP_BORDERS[group]; band < end; band++) {
                double r = re[row + band];
                double i = im[row + band];
                sum += r * r + i * i;
            }
            power[group - 2] = sum;
        }
        for (var bin = 0; bin < BINS; bin++) {
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
     * Returns the power of a mono hybrid subband.
     *
     * @param band the hybrid subband
     * @return the power
     */
    private double hybridPower(int band) {
        return monoHybridRe[band] * monoHybridRe[band] + monoHybridIm[band] * monoHybridIm[band];
    }

    /**
     * Builds the decorrelated signal of a slot.
     *
     * @param re the mono real parts
     * @param im the mono imaginary parts
     * @param rightRe the destination real parts of the decorrelated QMF subbands
     * @param rightIm the destination imaginary parts
     * @param row the index of the slot's first subband
     * @param usb the subband after the last one that carries signal
     */
    private void decorrelate(float[] re, float[] im, float[] rightRe, float[] rightIm, int row, int usb) {
        for (var group = 0; group < HYBRID; group++) {
            var band = GROUP_BORDERS[group];
            allPass(band, monoHybridRe[band], monoHybridIm[band]);
            var gain = attenuation[GROUP_BINS[group]];
            sideHybridRe[band] = allPassRe * gain;
            sideHybridIm[band] = allPassIm * gain;
        }
        var allPassEnd = Math.min(usb, ALLPASS_END);
        for (var band = 3; band < allPassEnd; band++) {
            allPass(HYBRID + band - 3, re[row + band], im[row + band]);
            var gain = attenuation[binOf(band)];
            rightRe[row + band] = (float) (allPassRe * gain);
            rightIm[row + band] = (float) (allPassIm * gain);
        }
        for (var band = allPassEnd; band < ALLPASS_END; band++) {
            allPass(HYBRID + band - 3, 0, 0);
        }
        var delayEnd = Math.min(usb, 64);
        for (var band = ALLPASS_END; band < delayEnd; band++) {
            var line = band - ALLPASS_END;
            var length = band < SHORT_DELAY_START ? LONG_DELAY : 1;
            var position = delayIndex[line];
            var gain = attenuation[band < SHORT_DELAY_START ? BINS - 2 : BINS - 1];
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
     * Returns the parameter bin of a QMF subband from 3 up.
     *
     * @param band the QMF subband
     * @return the bin
     */
    private static int binOf(int band) {
        var group = HYBRID;
        while (GROUP_BORDERS[group + 1] <= band) {
            group++;
        }
        return GROUP_BINS[group];
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
     * Runs one sample through the fractional delay and the three all-pass links of a subband,
     * leaving the output in {@link #allPassRe} and {@link #allPassIm}.
     *
     * @param band the all-pass subband: a hybrid subband, or {@code 10 + QMF subband - 3}
     * @param inRe the input real part
     * @param inIm the input imaginary part
     */
    private void allPass(int band, double inRe, double inIm) {
        var delayedRe = shortDelayRe[band][shortDelayIndex];
        var delayedIm = shortDelayIm[band][shortDelayIndex];
        shortDelayRe[band][shortDelayIndex] = inRe;
        shortDelayIm[band][shortDelayIndex] = inIm;
        var re = delayedRe * phaseRe[band] - delayedIm * phaseIm[band];
        var im = delayedIm * phaseRe[band] + delayedRe * phaseIm[band];
        for (var link = 0; link < 3; link++) {
            var position = linkIndex[link];
            var storedRe = linkRe[link][position][band];
            var storedIm = linkIm[link][position][band];
            var rotatedRe = storedRe * linkPhaseRe[link][band] - storedIm * linkPhaseIm[link][band];
            var rotatedIm = storedIm * linkPhaseRe[link][band] + storedRe * linkPhaseIm[link][band];
            var decay = linkDecay[link][band];
            var outRe = rotatedRe - decay * re;
            var outIm = rotatedIm - decay * im;
            linkRe[link][position][band] = re + decay * outRe;
            linkIm[link][position][band] = im + decay * outIm;
            re = outRe;
            im = outIm;
        }
        allPassRe = re;
        allPassIm = im;
    }

    /**
     * Mixes the mono and decorrelated signals of a slot into the two channels and merges the
     * hybrid subbands back into QMF subbands 0 to 2.
     *
     * @param re the mono real parts, overwritten with the left channel
     * @param im the mono imaginary parts, overwritten with the left channel
     * @param rightRe the decorrelated real parts, overwritten with the right channel
     * @param rightIm the decorrelated imaginary parts, overwritten with the right channel
     * @param row the index of the slot's first subband
     * @param usb the subband after the last one that carries signal
     */
    private void mix(float[] re, float[] im, float[] rightRe, float[] rightIm, int row, int usb) {
        for (var group = 0; group < GROUPS; group++) {
            m11[group] += d11[group];
            m12[group] += d12[group];
            m21[group] += d21[group];
            m22[group] += d22[group];
        }
        for (var group = 0; group < HYBRID; group++) {
            var band = GROUP_BORDERS[group];
            var monoRe = monoHybridRe[band];
            var monoIm = monoHybridIm[band];
            var sideRe = sideHybridRe[band];
            var sideIm = sideHybridIm[band];
            monoHybridRe[band] = m11[group] * monoRe + m21[group] * sideRe;
            monoHybridIm[band] = m11[group] * monoIm + m21[group] * sideIm;
            sideHybridRe[band] = m12[group] * monoRe + m22[group] * sideRe;
            sideHybridIm[band] = m12[group] * monoIm + m22[group] * sideIm;
        }
        for (var group = HYBRID; group < GROUPS; group++) {
            var end = Math.min(usb, GROUP_BORDERS[group + 1]);
            for (var band = GROUP_BORDERS[group]; band < end; band++) {
                var index = row + band;
                var monoRe = re[index];
                var monoIm = im[index];
                var sideRe = rightRe[index];
                var sideIm = rightIm[index];
                re[index] = (float) (m11[group] * monoRe + m21[group] * sideRe);
                im[index] = (float) (m11[group] * monoIm + m21[group] * sideIm);
                rightRe[index] = (float) (m12[group] * monoRe + m22[group] * sideRe);
                rightIm[index] = (float) (m12[group] * monoIm + m22[group] * sideIm);
            }
        }
        re[row] = (float) (monoHybridRe[0] + monoHybridRe[1] + monoHybridRe[2] + monoHybridRe[3]
                + monoHybridRe[4] + monoHybridRe[5]);
        im[row] = (float) (monoHybridIm[0] + monoHybridIm[1] + monoHybridIm[2] + monoHybridIm[3]
                + monoHybridIm[4] + monoHybridIm[5]);
        rightRe[row] = (float) (sideHybridRe[0] + sideHybridRe[1] + sideHybridRe[2] + sideHybridRe[3]
                + sideHybridRe[4] + sideHybridRe[5]);
        rightIm[row] = (float) (sideHybridIm[0] + sideHybridIm[1] + sideHybridIm[2] + sideHybridIm[3]
                + sideHybridIm[4] + sideHybridIm[5]);
        for (var band = 1; band < 3; band++) {
            var center = 4 + 2 * band;
            re[row + band] = (float) (monoHybridRe[center] + monoHybridRe[center + 1]);
            im[row + band] = (float) (monoHybridIm[center] + monoHybridIm[center + 1]);
            rightRe[row + band] = (float) (sideHybridRe[center] + sideHybridRe[center + 1]);
            rightIm[row + band] = (float) (sideHybridIm[center] + sideHybridIm[center + 1]);
        }
        for (var band = usb; band < 64; band++) {
            rightRe[row + band] = 0;
            rightIm[row + band] = 0;
        }
    }
}
