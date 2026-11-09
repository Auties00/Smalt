/* Ports the smoothing of wideband non-active frames from the unstabilized LSPs (mod_wb_celp_lsp_decode of phi_lpc.c,
   developed by Rakesh Taori and Andy Gerrits, Philips Research Laboratories) and the comb filter gains
   (nec_exc_module.c, developed by Toshiyuki Nomura, NEC Corporation) of the CELP decoder of the ISO/IEC 14496-3
   reference software as distributed with the ISO/IEC 23003-3 reference software (src_lpc). ISO/IEC gives users of the
   MPEG-2 AAC/MPEG-4 Audio standards free license to this software module or modifications thereof for use in hardware
   or software products claiming conformance to the MPEG-2 AAC/MPEG-4 Audio standards. Copyright 1996. Modified for
   Smalt. */
package com.github.auties00.smalt.audio.celp.internal;

import java.util.Arrays;

/**
 * Decodes the frames of a CELP audio object into speech (ISO/IEC 14496-3 subclauses 3.5.6 to 3.5.9): the LPC decoder
 * and interpolator with fine rate control, the regular pulse and multi-pulse excitation generators with the bitrate
 * scalable enhancement layers, the LPC synthesis filter, the comfort noise generator of silence compression, the
 * bandwidth extension to 16 kHz and the optional post-processor.
 *
 * <p>Where the text of the standard and the reference decoder disagree, this class follows the reference decoder,
 * whose output the conformance streams of ISO/IEC 14496-26 carry: the ternary digits of a regular pulse
 * {@code shape_index} map to the amplitudes -1, +1 and 0, the enhancement pulses of the bitrate scalable layers go
 * through the comb filter of the pitch delay before their energy is measured, the gain normalization is the
 * squared RMS times the subframe length, the LSPs are sorted before they are stabilized, and the LSPs of a wideband
 * SID frame are smoothed unstabilized.
 *
 * <p>Instances are not thread-safe.
 */
final class CelpCore {
    /**
     * The longest RPE pitch lag, {@code Lmax}, which is also the length of the RPE adaptive codebook.
     */
    private static final int RPE_MAX_LAG = 295;

    /**
     * The shortest RPE pitch lag, {@code Lmin}.
     */
    private static final int RPE_MIN_LAG = 40;

    /**
     * The largest magnitude of an RPE excitation sample, 64 times 16-bit full scale. The adaptive codebook gain of
     * the RPE coder reaches 173 and is not normalized, so damaged frames would otherwise grow the excitation without
     * bound; the excitation of a valid stream stays far below it.
     */
    private static final double RPE_EXCITATION_LIMIT = 64 * 32768.0;

    /**
     * The adaptive codebook gains of the RPE coder (Table 3.88).
     */
    private static final double[] RPE_ADAPTIVE_GAIN = new double[32];

    /**
     * The first subframe fixed codebook gains of the RPE coder (Table 3.89).
     */
    private static final double[] RPE_FIXED_GAIN = new double[31];

    /**
     * The fixed codebook gain ratios of the later RPE subframes (Table 3.90).
     */
    private static final double[] RPE_FIXED_GAIN_RATIO = {819 / 8192.0, 3205 / 8192.0, 5418 / 8192.0, 7335 / 8192.0,
            9544 / 8192.0, 12763 / 8192.0, 22118 / 8192.0, 53248 / 8192.0};

    /**
     * The gains of the comb filter of the fixed codebook vector per signal mode, {@code cga}: 0.6 and 0.8 rounded to
     * 15 fractional bits, as the reference decoder holds them.
     */
    private static final double[] COMB_GAIN = {0, 0, 19661 / 32768.0, 26214 / 32768.0};

    /**
     * The amplitude of a regular pulse per ternary digit of {@code shape_index}.
     */
    static final int[] TERNARY = {-1, 1, 0};

    static {
        int[] adaptive = {12386, 27800, 38483, 47147, 54827, 61669, 16993, 18520, 20021, 21493, 22938, 24396, 25834,
                27292, 28767, 30284, 31877, 33607, 35550, 37808, 40613, 44122, 48847, 55745, 528, 727, 1388, 2777, 5553,
                11107, 22214, 44426};
        for (var i = 0; i < 32; i++) {
            RPE_ADAPTIVE_GAIN[i] = adaptive[i] / (double) (i < 6 ? 1 << 17 : i < 24 ? 1 << 15 : 1 << 8);
        }
        int[] fixed = {8192, 11578, 14877, 19909, 26573, 34324, 44160, 55564, 8739, 10823, 13458, 16451, 19978, 24039,
                28624, 33682, 39118, 45372, 52626, 60243, 4327, 4954, 5700, 6551, 7649, 8977, 10689, 13066, 16183,
                21291, 32832};
        for (var i = 0; i < 31; i++) {
            RPE_FIXED_GAIN[i] = fixed[i] / (double) (i < 8 ? 1 << 12 : i < 20 ? 1 << 9 : 1 << 5);
        }
    }

    /**
     * The structure of the base layer.
     */
    private final CelpLayout layout;

    /**
     * Whether the base layer uses fine rate control.
     */
    private final boolean fineRateControl;

    /**
     * The LPC order of the core.
     */
    private final int order;

    /**
     * The minimum LSP distance of the core.
     */
    private final double minGap;

    /**
     * The pulse table of the multi-pulse excitation, or {@code null} with regular pulse excitation.
     */
    private final PulseTable pulses;

    /**
     * The gain code book of the multi-pulse excitation.
     */
    private final double[] gainBook;

    /**
     * The adaptive codebook interpolation filter of the multi-pulse excitation.
     */
    private final double[] interpolation;

    /**
     * The half length of {@link #interpolation} in taps, {@code iftap}.
     */
    private final int taps;

    /**
     * The length of the adaptive codebook history of the multi-pulse excitation, {@code pacb_size}.
     */
    private final int historySize;

    /**
     * The shape_delay value of an all-zero adaptive codebook vector.
     */
    private final int zeroDelay;

    /**
     * The LSPs of the previous frame, {@code lsp_previous}, normalized.
     */
    private final double[] lspPrevious;

    /**
     * The wideband LSPs last decoded from an active frame, before their stabilization: a non-active period smooths
     * from these rather than from the ones the synthesis used.
     */
    private final double[] lspDecoded;

    /**
     * The LSPs a frame of fine rate control received for the next frame.
     */
    private final double[] lspPending;

    /**
     * Whether {@link #lspPending} holds LSPs not used yet.
     */
    private boolean pending;

    /**
     * The LSPs of the current frame.
     */
    private final double[] lspCurrent;

    /**
     * The LSPs of a subframe.
     */
    private final double[] lspSubframe;

    /**
     * The synthesis filter coefficients of the current subframe.
     */
    private final double[] a;

    /**
     * The reflection coefficients of the current subframe.
     */
    private final double[] reflection;

    /**
     * Scratch for the LSP and reflection coefficient conversions.
     */
    private final double[] work;

    /**
     * The past excitation of the multi-pulse coder followed by the adaptive codebook vector being built.
     */
    private final double[] history;

    /**
     * The adaptive codebook of the RPE coder, the newest sample last.
     */
    private final double[] rpeHistory;

    /**
     * The fixed codebook gain of the previous RPE subframe.
     */
    private double previousFixedGain;

    /**
     * The decoded RMS of the last subframe of the previous frame, {@code pqxnorm}.
     */
    private double previousRms;

    /**
     * The decoded RMS of each subframe.
     */
    private final double[] rms;

    /**
     * The adaptive codebook vector, the fixed codebook vector and the excitation of a subframe.
     */
    private final double[] adaptive;

    private final double[] fixed;

    private final double[] excitation;

    /**
     * The enhanced excitation of a subframe.
     */
    private final double[] enhanced;

    /**
     * The enhancement fixed codebook vector of a subframe.
     */
    private final double[] enhancementVector;

    /**
     * The pulse positions of the core and enhancement layers of a subframe, for the enhancement pulse tables.
     */
    private final int[][] layerPositions = new int[4][12];

    /**
     * The pulse table of the enhancement layers, or {@code null} with regular pulse excitation.
     */
    private final EnhancementTable enhancementTable;

    /**
     * The ternary amplitudes of the pulses of a regular pulse vector.
     */
    private final int[] rpeAmplitudes = new int[6];

    /**
     * The synthesis filter memory, the newest output first.
     */
    private final double[] synthesisMemory;

    /**
     * The post-processor of the core, or {@code null} when it is off.
     */
    private final PostFilter postFilter;

    /**
     * The integer pitch delay and the adaptive codebook gain of the last subframe, for the post-processor.
     */
    private int lastDelay;

    private double lastAdaptiveGain;

    /**
     * The gained fixed codebook excitation of each subframe of the frame, those of the bitrate scalable layers
     * included, kept for the bandwidth extension tool.
     */
    private final double[] frameFixed;

    /**
     * The integer and fractional pitch delay of each subframe of the frame, for the bandwidth extension tool.
     */
    private final int[] frameDelay;

    private final int[] frameFraction;

    /**
     * The LSPs decoded from a SID frame.
     */
    private final double[] sidLsp;

    /**
     * The stabilized LSPs of a wideband SID frame, which the frames without SID information smooth toward.
     */
    private final double[] sidLspHeld;

    /**
     * The comfort noise generator, or {@code null} without silence compression.
     */
    private final ComfortNoise comfortNoise;

    /**
     * The narrowband speech of a frame decoded with the bandwidth extension.
     */
    private final double[] coreOut;

    /**
     * The bandwidth extension decoder, or {@code null} without a bandwidth scalable layer.
     */
    private final BandwidthExtension bandwidth;

    /**
     * Creates the decoder of an object.
     *
     * @param base the base layer configuration
     * @param bandwidthConfiguration {@code BWS_configuration}, or -1 to decode without the bandwidth extension
     * @param postFilter whether to run the post-processor
     */
    CelpCore(CelpConfig base, CelpLayout layout, int bandwidthConfiguration, boolean postFilter) {
        this.layout = layout;
        this.fineRateControl = base.fineRateControl();
        this.order = layout.lpcOrder;
        this.minGap = layout.wideband ? Lsp.WB_MIN_GAP : Lsp.NB_MIN_GAP;
        var n = layout.subframeSize;
        if (layout.rpe) {
            this.pulses = null;
            this.enhancementTable = null;
            this.gainBook = null;
            this.interpolation = null;
            this.taps = 0;
            this.historySize = 0;
            this.zeroDelay = -1;
        } else {
            this.pulses = PulseTable.of(n, layout.signBits, layout.positionBits);
            this.enhancementTable = new EnhancementTable(n, layout.signBits, layout.enhancementSignBits);
            if (layout.wideband) {
                this.gainBook = n == 80 ? CelpTables.WB_GAIN_80 : CelpTables.WB_GAIN_40;
                this.interpolation = CelpTables.WB_INTERPOLATION;
                this.taps = 11;
                this.historySize = 306;
                this.zeroDelay = 511;
            } else {
                this.gainBook = n == 40 ? CelpTables.NB_GAIN_40 : CelpTables.NB_GAIN_80;
                this.interpolation = CelpTables.NB_INTERPOLATION;
                this.taps = 6;
                this.historySize = 150;
                this.zeroDelay = 255;
            }
        }
        this.lspPrevious = new double[order];
        this.lspDecoded = new double[order];
        for (var i = 0; i < order; i++) {
            lspPrevious[i] = (i + 1) / (double) (order + 1);
            lspDecoded[i] = lspPrevious[i];
        }
        this.lspPending = new double[order];
        this.lspCurrent = new double[order];
        this.lspSubframe = new double[order];
        this.a = new double[order];
        this.reflection = new double[order];
        this.work = new double[4 * order + 8];
        this.history = new double[historySize + n + 2 * 12];
        this.rpeHistory = new double[RPE_MAX_LAG];
        this.rms = new double[CelpFrame.MAX_SUBFRAMES];
        this.adaptive = new double[n];
        this.fixed = new double[n];
        this.excitation = new double[n];
        this.enhanced = new double[n];
        this.enhancementVector = new double[n];
        this.synthesisMemory = new double[order];
        this.postFilter = postFilter ? new PostFilter(order, n, !layout.rpe) : null;
        this.frameFixed = new double[layout.frameSize];
        this.frameDelay = new int[CelpFrame.MAX_SUBFRAMES];
        this.frameFraction = new int[CelpFrame.MAX_SUBFRAMES];
        this.comfortNoise = base.silenceCompression() ? new ComfortNoise(order, n, layout.subframes,
                layout.wideband ? 16000 : 8000, layout.rpe ? layout : null, base.configuration(), 1) : null;
        this.sidLsp = new double[order];
        this.sidLspHeld = new double[order];
        this.bandwidth = bandwidthConfiguration >= 0 && !layout.rpe && !layout.wideband
                ? new BandwidthExtension(layout, bandwidthConfiguration, postFilter, base.silenceCompression()) : null;
        this.coreOut = new double[layout.frameSize];
    }

    /**
     * Decodes one frame.
     *
     * @param frame the fields of the frame
     * @param enhancementLayers the bitrate scalable layers to add, from 0 to 3
     * @param wideband whether to decode the bandwidth extension layer, which the frame then holds
     * @param out the speech, {@code frameSize} samples at the core rate or twice as many with the bandwidth
     * extension, in 16-bit units
     */
    void decode(CelpFrame frame, int enhancementLayers, boolean wideband, double[] out) {
        var extend = wideband && bandwidth != null;
        var core = extend ? coreOut : out;
        if (comfortNoise != null && frame.txFlag != 1) {
            decodeSilent(frame, core);
            if (extend) {
                bandwidth.decodeSilent(frame, lspPrevious, out);
            }
            return;
        }
        if (comfortNoise != null) {
            comfortNoise.activeFrame();
        }
        decodeLsp(frame);
        var n = layout.subframeSize;
        for (var s = 0; s < layout.subframes; s++) {
            interpolate(s, layout.subframes);
            Lsp.toLpc(lspSubframe, Math.PI, order, a, work);
            Lsp.toReflection(a, order, reflection, work);
            if (layout.rpe) {
                rpeSubframe(frame, s);
                System.arraycopy(excitation, 0, enhanced, 0, n);
            } else {
                mpeSubframe(frame, s, enhancementLayers);
            }
            synthesize(enhanced, core, s * n, n);
            if (postFilter != null) {
                postFilter.apply(core, s * n, n, a, lastDelay, lastAdaptiveGain);
            }
        }
        System.arraycopy(lspCurrent, 0, lspPrevious, 0, order);
        if (extend) {
            bandwidth.decode(frame, lspCurrent, frameFixed, frameDelay, frameFraction, out);
        }
    }

    /**
     * Decodes the LSPs of the current frame into {@link #lspCurrent}, following the fine rate control flags.
     */
    private void decodeLsp(CelpFrame frame) {
        if (!fineRateControl || (!frame.interpolation && frame.lpcPresent)) {
            decodeIndices(frame.lpc, lspCurrent, false);
            pending = false;
        } else if (frame.interpolation && frame.lpcPresent) {
            decodeIndices(frame.lpc, lspPending, false);
            for (var i = 0; i < order; i++) {
                lspCurrent[i] = 0.5 * (lspPrevious[i] + lspPending[i]);
            }
            pending = true;
        } else if (pending) {
            System.arraycopy(lspPending, 0, lspCurrent, 0, order);
            pending = false;
        } else {
            System.arraycopy(lspPrevious, 0, lspCurrent, 0, order);
        }
    }

    /**
     * Decodes and stabilizes the LSPs of a set of LPC indices.
     *
     * @param lpc the indices
     * @param out the LSPs
     * @param sid whether the indices are those of a SID frame
     */
    void decodeIndices(int[] lpc, double[] out, boolean sid) {
        if (layout.wideband) {
            Lsp.decodeWideband(lpc, lspPrevious, out, sid);
        } else {
            Lsp.decodeNarrowband(lpc, lspPrevious, out, sid);
        }
        if (!sid && layout.wideband) {
            System.arraycopy(out, 0, lspDecoded, 0, order);
        }
        if (!sid || !layout.wideband) {
            Lsp.stabilize(out, order, minGap);
        }
    }

    /**
     * Interpolates the LSPs of one subframe between those of the previous and the current frame.
     */
    private void interpolate(int subframe, int subframes) {
        var ratio = (subframe + 1) / (double) subframes;
        for (var i = 0; i < order; i++) {
            lspSubframe[i] = (1 - ratio) * lspPrevious[i] + ratio * lspCurrent[i];
        }
    }

    /**
     * Generates the excitation of one subframe of the regular pulse coder (subclause 3.5.7.1.3).
     */
    private void rpeSubframe(CelpFrame frame, int s) {
        var n = layout.subframeSize;
        var lag = RPE_MAX_LAG - RPE_MIN_LAG - frame.delay[s];
        var gainIndex = frame.gain[s];
        var ga = gainIndex > 31 ? -RPE_ADAPTIVE_GAIN[64 - gainIndex - 1] : RPE_ADAPTIVE_GAIN[gainIndex];
        double gf;
        if (s == 0) {
            gf = RPE_FIXED_GAIN[Math.min(frame.fixedGain[s], RPE_FIXED_GAIN.length - 1)];
        } else {
            gf = RPE_FIXED_GAIN_RATIO[frame.fixedGain[s] & 7] * previousFixedGain;
        }
        previousFixedGain = gf;
        rpeFixed((int) frame.positions[s], 1, fixed);
        for (var i = 0; i < n; i++) {
            var past = lag + i < RPE_MAX_LAG ? rpeHistory[lag + i] : 0;
            excitation[i] = Math.clamp(ga * past + gf * fixed[i], -RPE_EXCITATION_LIMIT, RPE_EXCITATION_LIMIT);
        }
        System.arraycopy(rpeHistory, n, rpeHistory, 0, RPE_MAX_LAG - n);
        System.arraycopy(excitation, 0, rpeHistory, RPE_MAX_LAG - n, n);
        lastDelay = RPE_MAX_LAG - lag;
        lastAdaptiveGain = ga;
    }

    /**
     * Builds a regular pulse vector from {@code shape_index}: the phase and the ternary amplitudes on the grid of the
     * pulse spacing.
     *
     * @param index {@code shape_index}
     * @param scale the amplitude of a pulse
     * @param out the vector, cleared first
     */
    void rpeFixed(int index, double scale, double[] out) {
        Arrays.fill(out, 0, layout.subframeSize, 0);
        var spacing = layout.rpeSpacing;
        var phase = index % spacing;
        index /= spacing;
        var amplitudes = rpeAmplitudes;
        for (var p = layout.rpePulses - 1; p >= 0; p--) {
            amplitudes[p] = TERNARY[index % 3];
            index /= 3;
        }
        for (var p = 0; p < layout.rpePulses; p++) {
            var position = phase + spacing * p;
            if (position < layout.subframeSize) {
                out[position] = scale * amplitudes[p];
            }
        }
    }

    /**
     * Generates the excitation of one subframe of the multi-pulse coder (subclause 3.5.7.2.3), the enhanced
     * excitation of the bitrate scalable layers (subclause 3.5.7.3.3) into {@link #enhanced}, and updates the
     * adaptive codebook with the core excitation.
     */
    private void mpeSubframe(CelpFrame frame, int s, int enhancementLayers) {
        var n = layout.subframeSize;
        if (s == 0) {
            decodeRms(frame.signalMode, frame.rms, layout.subframes, rms);
        }
        var delayIndex = frame.delay[s];
        int delay;
        int fraction;
        if (delayIndex == zeroDelay) {
            delay = 0;
            fraction = 0;
        } else if (layout.wideband) {
            if (delayIndex < 216) {
                delay = delayIndex / 3 + 20;
                fraction = (2 * delayIndex) % 6;
            } else if (delayIndex < 398) {
                delay = (delayIndex - 216) / 2 + 92;
                fraction = (3 * (delayIndex - 216)) % 6;
            } else {
                delay = delayIndex - 398 + 183;
                fraction = 0;
            }
        } else {
            if (delayIndex < 162) {
                delay = delayIndex / 3 + 17;
                fraction = (2 * delayIndex) % 6;
            } else if (delayIndex < 200) {
                delay = (delayIndex - 162) / 2 + 71;
                fraction = (3 * (delayIndex - 162)) % 6;
            } else {
                delay = delayIndex - 200 + 90;
                fraction = 0;
            }
        }
        adaptiveVector(history, historySize, interpolation, taps, delay, fraction, adaptive, n);
        pulses.decode(frame.positions[s], frame.signs[s], fixed, layerPositions[0]);
        combFilter(fixed, n, delay, frame.signalMode);
        var norm = residualEnergy(rms[s], n, reflection, order);
        var mode = frame.signalMode;
        var nga = gainBook[frame.gain[s] * 8 + 2 * mode];
        var ngf = gainBook[frame.gain[s] * 8 + 2 * mode + 1];
        var acbEnergy = energy(adaptive, n);
        var fcbEnergy = energy(fixed, n);
        var ga = acbEnergy > 0 ? nga * Math.sqrt(norm / acbEnergy) : 0;
        var gf = fcbEnergy > 0 ? ngf * Math.sqrt(norm / fcbEnergy) : 0;
        for (var i = 0; i < n; i++) {
            excitation[i] = ga * adaptive[i] + gf * fixed[i];
        }
        System.arraycopy(excitation, 0, enhanced, 0, n);
        if (enhancementLayers > 0) {
            enhance(frame, s, enhancementLayers, norm, mode, delay);
        }
        for (var i = 0; i < n; i++) {
            frameFixed[s * n + i] = enhanced[i] - ga * adaptive[i];
        }
        frameDelay[s] = delay;
        frameFraction[s] = fraction;
        System.arraycopy(history, n, history, 0, historySize - n);
        System.arraycopy(excitation, 0, history, historySize - n, n);
        lastDelay = delay;
        lastAdaptiveGain = ga;
    }

    /**
     * Adds the excitation of the bitrate scalable layers to {@link #enhanced}.
     */
    private void enhance(CelpFrame frame, int s, int layers, double norm, int mode, int delay) {
        var n = layout.subframeSize;
        for (var layer = 0; layer < layers; layer++) {
            var positions = layerPositions[layer + 1];
            enhancementTable.decode(layer, layerPositions, frame.enhancementPositions[layer][s],
                    frame.enhancementSigns[layer][s], enhancementVector, positions);
            combFilter(enhancementVector, n, delay, mode);
            var vectorEnergy = energy(enhancementVector, n);
            var nge = CelpTables.ENHANCEMENT_GAIN[frame.enhancementGain[layer][s] * 4 + mode];
            var ge = vectorEnergy > 0 ? nge * Math.sqrt(norm / vectorEnergy) : 0;
            for (var i = 0; i < n; i++) {
                enhanced[i] += ge * enhancementVector[i];
            }
        }
    }

    /**
     * Decodes the RMS of each subframe from {@code rms_index}, interpolated in the mu-law domain from the last
     * subframe of the previous frame (subclause 3.5.7.2.3.2).
     *
     * @param signalMode {@code signal_mode}, or 0 for the fixed parameters of silence compression
     * @param index {@code rms_index}
     * @param subframes the number of subframes
     * @param out the RMS of each subframe
     */
    void decodeRms(int signalMode, int index, int subframes, double[] out) {
        previousRms = decodeRms(signalMode, index, subframes, previousRms, out);
    }

    /**
     * Decodes the RMS of each subframe and returns that of the last one.
     */
    static double decodeRms(int signalMode, int index, int subframes, double previous, double[] out) {
        var rmsMax = signalMode == 0 ? 7932.0 : 15864.0;
        var muLaw = signalMode == 0 ? 1024.0 : 512.0;
        var delta = 1.0 / 64;
        var aa = 1.0 / StrictMath.log10(1.0 + muLaw);
        var bb = rmsMax / muLaw;
        var pwk = aa * StrictMath.log10(1.0 + previous / bb);
        var qwk = delta * (index + 1);
        for (var i = 0; i < subframes; i++) {
            var nwk = (qwk - pwk) * (i + 1) / subframes + pwk;
            out[i] = bb * (StrictMath.pow(10.0, nwk / aa) - 1.0);
        }
        return out[subframes - 1];
    }

    /**
     * Builds the adaptive codebook vector of the multi-pulse coder by interpolating the past excitation at a
     * fractional delay (subclause 3.5.7.2.3.3), and appends it to the history for the delays shorter than a
     * subframe.
     *
     * @param history the past excitation, {@code size} samples, followed by room for the vector
     * @param size the length of the past excitation
     * @param filter the interpolation filter, from the center outward in sixths of a sample
     * @param taps the half length of the filter in samples, {@code iftap}
     * @param delay the integer delay, or 0 for an all-zero vector
     * @param fraction the fractional delay in sixths of a sample
     * @param out the vector
     * @param length the samples of the vector
     */
    static void adaptiveVector(double[] history, int size, double[] filter, int taps, int delay, int fraction,
                               double[] out, int length) {
        Arrays.fill(out, 0, length, 0);
        if (delay == 0) {
            return;
        }
        var tt = 0;
        for (var n = 0; n < length; ) {
            tt += fraction;
            var kt = delay + tt / 6;
            tt %= 6;
            for (var i = 0; i < kt && n < length; i++, n++) {
                var sum = 0.0;
                for (var k = -taps + 1; k <= taps; k++) {
                    var kk = Math.abs((k + 1) * 6 - tt);
                    if (kk >= filter.length) {
                        continue;
                    }
                    var index = size - (kt - i + k + 1);
                    if (index >= 0) {
                        sum += filter[kk] * history[index];
                    }
                }
                out[n] = sum;
                history[size + n] = sum;
            }
        }
    }

    /**
     * Applies the zero-state comb filter of the pitch delay to a fixed codebook vector when the delay is shorter than
     * the subframe.
     */
    static void combFilter(double[] vector, int length, int delay, int signalMode) {
        if (delay <= 0 || delay >= length) {
            return;
        }
        var gain = COMB_GAIN[signalMode];
        for (var i = delay; i < length; i++) {
            vector[i] += gain * vector[i - delay];
        }
    }

    /**
     * {@return the normalization of the gains, the energy of the prediction residual of a subframe; 0 for a filter
     * whose reflection coefficients are not those of a stable one}
     *
     * @param rms the decoded RMS of the subframe
     * @param length the samples of the subframe
     * @param reflection the reflection coefficients of the subframe
     * @param order the LPC order
     */
    static double residualEnergy(double rms, int length, double[] reflection, int order) {
        var norm = rms * rms * length;
        for (var i = 0; i < order; i++) {
            norm *= 1 - reflection[i] * reflection[i];
        }
        return norm > 0 ? norm : 0;
    }

    /**
     * {@return the energy of a vector}
     */
    static double energy(double[] vector, int length) {
        var sum = 0.0;
        for (var i = 0; i < length; i++) {
            sum += vector[i] * vector[i];
        }
        return sum;
    }

    /**
     * Runs the LPC synthesis filter of the current subframe over an excitation.
     *
     * @param excitation the excitation of the subframe
     * @param out the speech
     * @param offset the first sample of the subframe in {@code out}
     * @param length the samples of the subframe
     */
    void synthesize(double[] excitation, double[] out, int offset, int length) {
        synthesize(excitation, a, synthesisMemory, order, out, offset, length);
    }

    /**
     * Runs an LPC synthesis filter, {@code y[n] = x[n] + sum a[k] y[n - k - 1]} (subclause 3.5.8.3).
     */
    static void synthesize(double[] excitation, double[] a, double[] memory, int order, double[] out, int offset,
                           int length) {
        for (var i = 0; i < length; i++) {
            var y = excitation[i];
            for (var k = 0; k < order; k++) {
                y += memory[k] * a[k];
            }
            System.arraycopy(memory, 0, memory, 1, order - 1);
            memory[0] = y;
            out[offset + i] = y;
        }
    }

    /**
     * Decodes a non-active frame of silence compression into comfort noise (subclause 3.5.9.3.3): the LSPs and the
     * RMS of the SID frames, smoothed, drive the synthesis of a random excitation, and the adaptive codebooks are
     * cleared.
     */
    private void decodeSilent(CelpFrame frame, double[] out) {
        var tx = frame.txFlag;
        double[] decoded = null;
        if (tx == 2) {
            decodeIndices(frame.lpc, sidLsp, true);
            decoded = sidLsp;
        }
        comfortNoise.startFrame(tx, layout.wideband ? lspDecoded : pending ? lspPending : lspPrevious, decoded);
        if (decoded != null && layout.wideband) {
            System.arraycopy(decoded, 0, sidLspHeld, 0, order);
            Lsp.stabilize(sidLspHeld, order, minGap);
            comfortNoise.holdSid(sidLspHeld);
        }
        if (tx >= 2) {
            decodeRms(0, frame.rms, layout.subframes, rms);
        }
        comfortNoise.prepareLevel(tx, rms[layout.subframes - 1]);
        System.arraycopy(comfortNoise.lspSmoothed, 0, lspCurrent, 0, order);
        if (layout.wideband) {
            Lsp.stabilize(lspCurrent, order, minGap);
        }
        var n = layout.subframeSize;
        for (var s = 0; s < layout.subframes; s++) {
            interpolate(s, layout.subframes);
            Lsp.toLpc(lspSubframe, Math.PI, order, a, work);
            Lsp.toReflection(a, order, reflection, work);
            comfortNoise.excitation(tx, s, rms[s], reflection, enhanced);
            synthesize(enhanced, out, s * n, n);
            if (postFilter != null) {
                postFilter.apply(out, s * n, n, a, 0, 0);
            }
        }
        comfortNoise.endFrame(tx);
        System.arraycopy(lspCurrent, 0, lspPrevious, 0, order);
        Arrays.fill(history, 0);
        Arrays.fill(rpeHistory, 0);
        pending = false;
    }
}
