/* Ports the renewal of the bandwidth extension LSP prediction in non-active frames of the CELP decoder of the
   ISO/IEC 14496-3 reference software as distributed with the ISO/IEC 23003-3 reference software (bws_lpc_decoder
   of nb_celp_dec.c in src_lpc), developed by Ron Burns (Hughes Electronics) and edited by Naoya Tanaka (Matsushita
   Communication Industrial Co., Ltd.) and Toshiyuki Nomura (NEC Corporation). ISO/IEC gives users of the MPEG-2
   AAC/MPEG-4 Audio standards free license to this software module or modifications thereof for use in hardware or
   software products claiming conformance to the MPEG-2 AAC/MPEG-4 Audio standards. Copyright (c) 1996.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.celp.internal;

import java.util.Arrays;

/**
 * The bandwidth extension tool of the CELP decoder (ISO/IEC 14496-3 subclauses 3.5.6.3 and 3.5.7.4), which decodes
 * 16 kHz speech from a narrowband multi-pulse core and the bandwidth scalable enhancement layer: twenty LSPs predicted
 * from the narrowband ones, and an excitation of an adaptive codebook vector, the narrowband fixed codebook
 * excitation upsampled, and a second fixed codebook vector.
 *
 * <p>The standard scales the upsampled narrowband vector by its energy like the other two; the reference decoder,
 * whose output the conformance streams carry, scales instead the gained fixed codebook excitation of the core, the
 * pulses of the bitrate scalable layers included, by the table gain alone, and starts the moving average prediction
 * of the upper ten LSPs from the initial LSPs rather than from zero; through the non-active frames that carry no
 * LSP indices of their own, it renews the prediction residuals from the frame's smoothed LSPs. This class does the
 * same.
 *
 * <p>Instances are not thread-safe.
 */
final class BandwidthExtension {
    /**
     * The LPC order.
     */
    private static final int ORDER = 20;

    /**
     * The length of the adaptive codebook history.
     */
    private static final int HISTORY = 306;

    /**
     * The adaptive codebook index of an all-zero vector.
     */
    private static final int ZERO_INDEX = 778;

    /**
     * The structure of the narrowband core.
     */
    private final CelpLayout layout;

    /**
     * The subframes of a frame.
     */
    private final int subframes;

    /**
     * The samples of a subframe.
     */
    private final int subframeSize;

    /**
     * The pulse table of the second fixed codebook.
     */
    private final PulseTable pulses;

    /**
     * The LSPs of the previous frame, in radians.
     */
    private final double[] lspPrevious = new double[ORDER];

    /**
     * The LSPs of the current frame, in radians.
     */
    private final double[] lspCurrent = new double[ORDER];

    /**
     * The LSPs of a subframe, in radians.
     */
    private final double[] lspSubframe = new double[ORDER];

    /**
     * The LSP prediction residuals of the current and the two previous frames.
     */
    private final double[][] residual = new double[3][ORDER];

    /**
     * The synthesis filter of a subframe and its reflection coefficients.
     */
    private final double[] a = new double[ORDER];

    private final double[] reflection = new double[ORDER];

    /**
     * Scratch for the conversions.
     */
    private final double[] work = new double[4 * ORDER + 8];

    /**
     * The past excitation followed by the adaptive codebook vector being built.
     */
    private final double[] history;

    /**
     * The decoded RMS of the last subframe of the previous frame.
     */
    private double previousRms;

    /**
     * The decoded RMS of each subframe.
     */
    private final double[] rms = new double[CelpFrame.MAX_SUBFRAMES];

    /**
     * The vectors and the excitation of a subframe.
     */
    private final double[] adaptive;

    private final double[] fixed1;

    private final double[] fixed2;

    private final double[] excitation;

    /**
     * The synthesis filter memory.
     */
    private final double[] memory = new double[ORDER];

    /**
     * The post-processor, or {@code null} when it is off.
     */
    private final PostFilter postFilter;

    /**
     * The comfort noise generator, or {@code null} without silence compression.
     */
    private final ComfortNoise comfortNoise;

    /**
     * The LSPs of a SID frame, in radians.
     */
    private final double[] sidLsp = new double[ORDER];

    /**
     * Creates the tool.
     *
     * @param layout the structure of the narrowband core
     * @param configuration {@code BWS_configuration}
     * @param postFilter whether to run the post-processor
     * @param silenceCompression whether the core uses silence compression
     */
    BandwidthExtension(CelpLayout layout, int configuration, boolean postFilter, boolean silenceCompression) {
        this.layout = layout;
        this.subframes = layout.bandwidthSubframes;
        this.subframeSize = 2 * layout.frameSize / subframes;
        this.pulses = PulseTable.of(subframeSize, CelpLayout.bandwidthPulses(configuration),
                CelpLayout.bandwidthPositionBits(configuration));
        for (var i = 0; i < ORDER; i++) {
            lspPrevious[i] = Math.PI * (i + 1) / (ORDER + 1);
            if (i >= ORDER / 2) {
                for (var n = 0; n < residual.length; n++) {
                    residual[n][i] = lspPrevious[i];
                }
            }
        }
        this.history = new double[HISTORY + subframeSize + 24];
        this.adaptive = new double[subframeSize];
        this.fixed1 = new double[subframeSize];
        this.fixed2 = new double[subframeSize];
        this.excitation = new double[subframeSize];
        this.postFilter = postFilter ? new PostFilter(ORDER, subframeSize, true) : null;
        this.comfortNoise = silenceCompression ? new ComfortNoise(ORDER, subframeSize, subframes, 16000, null, 0,
                Math.PI) : null;
    }

    /**
     * Decodes the 16 kHz speech of an active frame.
     *
     * @param frame the fields of the frame, with those of the bandwidth scalable layer
     * @param narrowband the decoded narrowband LSPs of the frame, normalized
     * @param fixed the gained narrowband fixed codebook excitation of the frame, the pulses of the bitrate scalable
     * layers included
     * @param delays the narrowband integer pitch delay of each core subframe, 0 for an all-zero adaptive vector
     * @param fractions the narrowband fractional pitch delay of each core subframe, in sixths
     * @param out the speech, {@code 2 * frameSize} samples
     */
    void decode(CelpFrame frame, double[] narrowband, double[] fixed, int[] delays, int[] fractions, double[] out) {
        if (comfortNoise != null) {
            comfortNoise.activeFrame();
        }
        Lsp.decodeBandwidth(frame.lpc, false, narrowband, residual, lspCurrent);
        previousRms = CelpCore.decodeRms(frame.signalMode, frame.rms, subframes, previousRms, rms);
        var m = subframeSize;
        var mode = frame.signalMode;
        for (var j = 0; j < subframes; j++) {
            prepare(j);
            var start = j * m / 2;
            var core = start / layout.subframeSize;
            var delay = delays[core];
            int index;
            if (delay == 0) {
                index = ZERO_INDEX;
            } else {
                var open = 2 * delay + (fractions[core] != 0 ? 1 : 0);
                var openIndex = (open - 32) * 3 + 2;
                var startIndex = Math.max(openIndex - 4, 0);
                if (startIndex + 7 >= ZERO_INDEX) {
                    startIndex = ZERO_INDEX - 8;
                }
                index = startIndex + frame.bandwidthDelay[j];
            }
            int wideDelay;
            int wideFraction;
            if (index >= ZERO_INDEX) {
                wideDelay = 0;
                wideFraction = 0;
            } else if (index < 2) {
                wideDelay = 32;
                wideFraction = (2 * (index + 1)) % 6;
            } else {
                wideDelay = 32 + 2 * (index - 2) / 6;
                wideFraction = (2 * (index - 2)) % 6;
            }
            CelpCore.adaptiveVector(history, HISTORY, CelpTables.WB_INTERPOLATION, 11, wideDelay, wideFraction,
                    adaptive, m);
            for (var i = 0; i < m / 2; i++) {
                fixed1[2 * i] = fixed[start + i];
                fixed1[2 * i + 1] = 0;
            }
            pulses.decode(frame.bandwidthPositions[j], frame.bandwidthSigns[j], fixed2, null);
            CelpCore.combFilter(fixed2, m, wideDelay, mode);
            var norm = CelpCore.residualEnergy(rms[j], m, reflection, ORDER);
            var gain = frame.bandwidthGain[j];
            var ngn = CelpTables.BWS_GAIN_N[(gain >> 7) * 4 + mode];
            var nga = CelpTables.BWS_GAIN[(gain & 127) * 8 + 2 * mode];
            var ngf = CelpTables.BWS_GAIN[(gain & 127) * 8 + 2 * mode + 1];
            var ea = CelpCore.energy(adaptive, m);
            var e2 = CelpCore.energy(fixed2, m);
            var ga = ea > 0 ? nga * Math.sqrt(norm / ea) : 0;
            var gn = ngn;
            var gf = e2 > 0 ? ngf * Math.sqrt(norm / e2) : 0;
            for (var i = 0; i < m; i++) {
                excitation[i] = ga * adaptive[i] + gn * fixed1[i] + gf * fixed2[i];
            }
            System.arraycopy(history, m, history, 0, HISTORY - m);
            System.arraycopy(excitation, 0, history, HISTORY - m, m);
            CelpCore.synthesize(excitation, a, memory, ORDER, out, j * m, m);
            if (postFilter != null) {
                postFilter.apply(out, j * m, m, a, wideDelay, ga);
            }
        }
        System.arraycopy(lspCurrent, 0, lspPrevious, 0, ORDER);
    }

    /**
     * Decodes the 16 kHz comfort noise of a non-active frame of silence compression.
     *
     * @param frame the fields of the frame, with the SID LSP indices of the bandwidth scalable layer for
     * {@code TX_flag} 2
     * @param narrowband the narrowband LSPs of the frame, normalized
     * @param out the speech, {@code 2 * frameSize} samples
     */
    void decodeSilent(CelpFrame frame, double[] narrowband, double[] out) {
        var tx = frame.txFlag;
        double[] decoded = null;
        if (tx == 2) {
            Lsp.decodeBandwidth(frame.lpc, true, narrowband, residual, sidLsp);
            decoded = sidLsp;
        }
        comfortNoise.startFrame(tx, lspPrevious, decoded);
        if (tx != 2) {
            Lsp.renewBandwidth(comfortNoise.lspSmoothed, narrowband, residual);
        }
        if (tx >= 2) {
            previousRms = CelpCore.decodeRms(0, frame.rms, subframes, previousRms, rms);
        }
        comfortNoise.prepareLevel(tx, rms[subframes - 1]);
        System.arraycopy(comfortNoise.lspSmoothed, 0, lspCurrent, 0, ORDER);
        var m = subframeSize;
        for (var j = 0; j < subframes; j++) {
            prepare(j);
            comfortNoise.excitation(tx, j, rms[j], reflection, excitation);
            CelpCore.synthesize(excitation, a, memory, ORDER, out, j * m, m);
            if (postFilter != null) {
                postFilter.apply(out, j * m, m, a, 0, 0);
            }
        }
        comfortNoise.endFrame(tx);
        System.arraycopy(lspCurrent, 0, lspPrevious, 0, ORDER);
        Arrays.fill(history, 0);
    }

    /**
     * Interpolates the LSPs of a subframe and derives its synthesis filter and reflection coefficients.
     */
    private void prepare(int subframe) {
        var ratio = (subframe + 1) / (double) subframes;
        for (var i = 0; i < ORDER; i++) {
            lspSubframe[i] = (1 - ratio) * lspPrevious[i] + ratio * lspCurrent[i];
        }
        Lsp.toLpc(lspSubframe, 1, ORDER, a, work);
        Lsp.toReflection(a, ORDER, reflection, work);
    }
}
