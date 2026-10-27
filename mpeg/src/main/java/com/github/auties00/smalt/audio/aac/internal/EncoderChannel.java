/* Ports vo-aacenc 0.1.3 (aacenc/src/psy_main.c, adj_thr.c, pre_echo_control.c).
   Copyright 2003-2010 VisualOn, Inc.; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;

import java.util.Arrays;

/**
 * Encodes one channel of an AAC Low Complexity stream, frame after frame.
 *
 * <p>For each frame the engine calls {@link #analyze} (forward MDCT, low-pass, psychoacoustic
 * thresholds, TNS, window grouping), optionally lets M/S stereo rewrite the grouped spectra of a
 * channel pair, calls {@link #prepare()}, then evaluates {@link #quantize} at several rate
 * control parameters and finally {@link #write writes} the chosen one. Band arrays
 * are indexed {@code group * MAX_BANDS + band}; the grouped spectrum stores each group band by
 * band and, within a band, window by window, which is the order of the spectral data syntax.
 *
 * @implNote This implementation's psychoacoustic model and rate control follow 3GPP TS 26.403:
 * thresholds are
 * the band energies lowered by a signal-to-mask ratio, spread across bands, floored by the
 * threshold in quiet and limited by pre-echo control; the rate control raises them uniformly in the fourth-root
 * (loudness) domain while avoiding spectral holes, and the scalefactor of each band starts from
 * the estimate {@code 8/3 log2(6.75 T / sum sqrt|x|)} and is refined by measuring the actual
 * quantization error. Unlike that encoder, long windows estimate each band's tonality from its
 * spectral flatness and, at low and medium bitrates, mask noise-like bands up to 7 dB closer to
 * their energy than tonal ones, the rate control searches
 * on exact bit counts, and sections are planned optimally by dynamic programming.
 */
public final class EncoderChannel {
    /**
     * The stride of band-indexed arrays.
     */
    public static final int MAX_BANDS = ChannelStream.MAX_BANDS;

    /**
     * The masking ratio of tonal bands and of short windows: thresholds sit 29 dB below band
     * energies.
     */
    private static final float MASKING_RATIO = (float) StrictMath.pow(10, -2.9);

    /**
     * The signal-to-mask ratio of a tonal band, in decibels.
     */
    private static final float TONAL_SMR = 29;

    /**
     * The largest reduction of the signal-to-mask ratio of a noise-like band, in decibels.
     */
    private static final float NOISE_RELIEF = 7;

    /**
     * The spectral flatness, in decibels, at and below which a band counts as fully tonal.
     */
    private static final float TONAL_FLATNESS = -30;

    /**
     * The fewest lines over which a band's spectral flatness is measured; narrower bands borrow
     * lines from their neighbors.
     */
    private static final int FLATNESS_LINES = 16;

    /**
     * The factor by which a threshold may exceed the previous frame's (pre-echo control).
     */
    private static final float PRE_ECHO_INCREASE = 2;

    /**
     * The fraction of the original threshold pre-echo control always keeps.
     */
    private static final float PRE_ECHO_FLOOR = 0.01f;

    /**
     * The window grouping used for each attack position (3GPP TS 26.403).
     */
    private static final int[][] GROUPING = {
            {1, 3, 3, 1}, {1, 1, 3, 3}, {2, 1, 3, 2}, {3, 1, 3, 1}, {3, 1, 1, 3}, {3, 2, 1, 2}, {3, 3, 1, 1}, {3, 3, 1, 1}
    };

    /**
     * The DSP kernel.
     */
    private final AacKernel kernel;

    /**
     * The number of spectral lines of a frame, 1024 or 960.
     */
    private final int length;

    /**
     * The number of spectral lines of a short window.
     */
    private final int shortLength;

    /**
     * The signal-to-mask ratio of a noise-like band at this channel's bitrate, in decibels.
     */
    private float noiseSmr;

    /**
     * The long-window band layout.
     */
    private BandLayout longLayout;

    /**
     * The short-window band layout.
     */
    private BandLayout shortLayout;

    /**
     * The long-window TNS encoder, or {@code null} when TNS is disabled.
     */
    private TnsEncoder longTns;

    /**
     * The short-window TNS encoder, or {@code null} when TNS is disabled.
     */
    private TnsEncoder shortTns;

    /**
     * The forward transform.
     */
    private final ForwardMdct mdct;

    /**
     * The attack detector.
     */
    private final TransientDetector detector;

    /**
     * The window sequence of the current frame.
     */
    private int sequence;

    /**
     * The window shape of the current frame.
     */
    private int shape;

    /**
     * The window shape of the previous frame.
     */
    private int previousShape;

    /**
     * The number of window groups.
     */
    private int groupCount;

    /**
     * The number of windows in each group.
     */
    private final int[] groupLengths = new int[8];

    /**
     * The band layout of the current frame.
     */
    private BandLayout layout;

    /**
     * The spectrum in window order.
     */
    private final float[] spectrum;

    /**
     * The spectrum in grouped order.
     */
    private final float[] grouped;

    /**
     * The magnitudes of the grouped spectrum.
     */
    private final float[] magnitude;

    /**
     * The 3/4 powers of the magnitudes.
     */
    private final float[] power34;

    /**
     * The quantized magnitudes, then the signed quantized values.
     */
    private final int[] quant;

    /**
     * The scratch quantized magnitudes of distortion measurements.
     */
    private final int[] scratch;

    /**
     * The first grouped line of each band.
     */
    private final int[] bandStart = new int[8 * MAX_BANDS];

    /**
     * The grouped line after the last of each band.
     */
    private final int[] bandEnd = new int[8 * MAX_BANDS];

    /**
     * The energy of each band.
     */
    private final float[] energy = new float[8 * MAX_BANDS];

    /**
     * The psychoacoustic threshold of each band.
     */
    private final float[] threshold = new float[8 * MAX_BANDS];

    /**
     * The minimum threshold-to-energy ratio of each band.
     */
    private final float[] minSnr = new float[8 * MAX_BANDS];

    /**
     * The fourth root of the psychoacoustic threshold of each band.
     */
    private final float[] thresholdRoot = new float[8 * MAX_BANDS];

    /**
     * The sum of the square roots of the magnitudes of each band.
     */
    private final float[] formFactor = new float[8 * MAX_BANDS];

    /**
     * The smallest scalefactor that keeps each band's quantized magnitudes in range.
     */
    private final int[] minScalefactor = new int[8 * MAX_BANDS];

    /**
     * The scalefactor of each band.
     */
    private final int[] scalefactors = new int[8 * MAX_BANDS];

    /**
     * The largest quantized magnitude of each band.
     */
    private final int[] maxQuant = new int[8 * MAX_BANDS];

    /**
     * The codebook of each band.
     */
    private final int[] books = new int[8 * MAX_BANDS];

    /**
     * The codebook costs of the bands of one group.
     */
    private final int[] costs = new int[MAX_BANDS * SpectralCoder.BOOKS];

    /**
     * The scratch per-window band energies and thresholds of short windows.
     */
    private final float[] windowEnergy = new float[8 * 16];

    /**
     * The scratch per-window thresholds of short windows.
     */
    private final float[] windowThreshold = new float[8 * 16];

    /**
     * The long-window thresholds of the previous frame, for pre-echo control.
     */
    private final float[] previousLong = new float[MAX_BANDS];

    /**
     * Whether {@link #previousLong} holds usable thresholds.
     */
    private boolean previousLongValid;

    /**
     * The thresholds of the last short window analyzed, for pre-echo control.
     */
    private final float[] previousShort = new float[16];

    /**
     * Whether {@link #previousShort} holds usable thresholds.
     */
    private boolean previousShortValid;

    /**
     * The TNS filter order of each window, 0 when off.
     */
    private final int[] tnsOrders = new int[8];

    /**
     * The quantized TNS coefficients of each window, 12 per window.
     */
    private final int[] tnsIndexes = new int[8 * 12];

    /**
     * Whether any window of the frame uses TNS.
     */
    private boolean tnsActive;

    /**
     * The number of bands transmitted per group.
     */
    private int maxBands;

    /**
     * The global gain.
     */
    private int globalGain;

    /**
     * The number of scalefactors per band whose distortion is memoized.
     */
    private static final int MEMO_SPAN = 64;

    /**
     * The memoized distortion of each band at each scalefactor from the band's bound upwards,
     * {@link #MEMO_SPAN} per band.
     */
    private final float[] distortionMemo = new float[8 * MAX_BANDS * MEMO_SPAN];

    /**
     * The frame stamp of each memoized distortion.
     */
    private final int[] memoStamp = new int[8 * MAX_BANDS * MEMO_SPAN];

    /**
     * The stamp of the current frame's memoized distortions.
     */
    private int frameStamp;

    /**
     * The scalefactor each band's lines in {@link #quant} were last quantized with, or {@code -1}
     * when they were cleared; valid when {@link #quantStamp} holds the current frame's stamp.
     */
    private final int[] quantScalefactor = new int[8 * MAX_BANDS];

    /**
     * The frame stamp of each band's {@link #quantScalefactor}.
     */
    private final int[] quantStamp = new int[8 * MAX_BANDS];

    /**
     * The largest quantized magnitude of each band's last quantization.
     */
    private final int[] quantPeak = new int[8 * MAX_BANDS];

    /**
     * The codebook costs of each band's last counting, {@link SpectralCoder#BOOKS} per band.
     */
    private final int[] costMemo = new int[8 * MAX_BANDS * SpectralCoder.BOOKS];

    /**
     * The frame stamp of each band's {@link #costMemo} entry.
     */
    private final int[] costStamp = new int[8 * MAX_BANDS];

    /**
     * The quantization scalefactor each band's {@link #costMemo} entry was counted from.
     */
    private final int[] costScalefactor = new int[8 * MAX_BANDS];

    /**
     * Whether each band's {@link #costMemo} entry was counted exhaustively.
     */
    private final boolean[] costExhaustive = new boolean[8 * MAX_BANDS];

    /**
     * The quantized TNS coefficients of the window being analyzed.
     */
    private final int[] tnsScratch = new int[12];

    /**
     * The codebook scratch of section planning and writing.
     */
    private final int[] bookScratch = new int[MAX_BANDS];

    /**
     * The section planner of this channel.
     */
    private final SpectralCoder coder = new SpectralCoder();

    /**
     * Creates a channel encoder.
     *
     * @param sampleRate the sampling rate in hertz
     * @param frameLength the frame length, 1024 or 960
     * @param bandwidth the audio bandwidth in hertz
     * @param channelBitrate the bitrate per channel in bits per second
     * @param tns whether temporal noise shaping may be used
     * @param kernel the DSP kernel
     */
    public EncoderChannel(int sampleRate, int frameLength, int bandwidth, int channelBitrate, boolean tns,
                          AacKernel kernel) {
        this.kernel = kernel;
        this.length = frameLength;
        this.shortLength = frameLength / 8;
        this.spectrum = new float[frameLength];
        this.grouped = new float[frameLength];
        this.magnitude = new float[frameLength];
        this.power34 = new float[frameLength];
        this.quant = new int[frameLength];
        this.scratch = new int[frameLength];
        this.noiseSmr = TONAL_SMR - NOISE_RELIEF * Math.clamp((128000 - channelBitrate) / 64000f, 0, 1);
        this.longLayout = new BandLayout(sampleRate, frameLength, false, bandwidth, channelBitrate);
        this.shortLayout = new BandLayout(sampleRate, frameLength, true, bandwidth, channelBitrate);
        this.longTns = tns ? new TnsEncoder(longLayout, sampleRate, false, kernel) : null;
        this.shortTns = tns ? new TnsEncoder(shortLayout, sampleRate, true, kernel) : null;
        this.mdct = new ForwardMdct(frameLength, kernel);
        this.detector = new TransientDetector(channelBitrate, frameLength);
        this.layout = longLayout;
    }

    /**
     * Adapts the channel to a new bandwidth and bitrate from the next frame on, keeping the transform overlap and
     * the attack detector's history; the previous frame's thresholds stop limiting pre-echo when the number of
     * bands changes, since they do not cover the new bands.
     *
     * @param sampleRate the sampling rate in hertz
     * @param bandwidth the audio bandwidth in hertz
     * @param channelBitrate the bitrate per channel in bits per second
     */
    public void retune(int sampleRate, int bandwidth, int channelBitrate) {
        this.noiseSmr = TONAL_SMR - NOISE_RELIEF * Math.clamp((128000 - channelBitrate) / 64000f, 0, 1);
        var longBands = longLayout.count();
        var shortBands = shortLayout.count();
        this.longLayout = new BandLayout(sampleRate, length, false, bandwidth, channelBitrate);
        this.shortLayout = new BandLayout(sampleRate, length, true, bandwidth, channelBitrate);
        if (longLayout.count() != longBands) {
            previousLongValid = false;
        }
        if (shortLayout.count() != shortBands) {
            previousShortValid = false;
        }
        if (longTns != null) {
            this.longTns = new TnsEncoder(longLayout, sampleRate, false, kernel);
            this.shortTns = new TnsEncoder(shortLayout, sampleRate, true, kernel);
        }
        this.layout = longLayout;
        detector.retune(channelBitrate);
    }

    /**
     * Returns the attack detector of the channel.
     *
     * @return the detector
     */
    public TransientDetector detector() {
        return detector;
    }

    /**
     * Transforms and analyzes one frame.
     *
     * @param input the input samples
     * @param offset the index of the first of the frame's {@code 2 * frameLength} samples
     * @param sequence the window sequence
     * @param attackIndex the short window holding the attack, or {@code -1} when the frame has
     * none
     */
    public void analyze(float[] input, int offset, int sequence, int attackIndex) {
        this.sequence = sequence;
        this.shape = sequence == ChannelStream.EIGHT_SHORT_SEQUENCE || sequence == ChannelStream.LONG_START_SEQUENCE
                ? Windows.SINE : Windows.KBD;
        mdct.transform(input, offset, sequence, previousShape, shape, spectrum);
        previousShape = shape;
        tnsActive = false;
        Arrays.fill(tnsOrders, 0);
        if (sequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            analyzeShort(attackIndex);
        } else {
            analyzeLong();
        }
    }

    /**
     * Analyzes a long-window frame.
     */
    private void analyzeLong() {
        layout = longLayout;
        groupCount = 1;
        groupLengths[0] = 1;
        var offsets = layout.offsets();
        var count = layout.count();
        Arrays.fill(spectrum, layout.lowpassLine(), length, 0);
        for (var band = 0; band < count; band++) {
            energy[band] = kernel.energy(spectrum, offsets[band], offsets[band + 1]);
            threshold[band] = energy[band] * maskingRatio(offsets[band], offsets[band + 1], layout.lowpassLine());
        }
        spread(threshold, 0, layout);
        for (var band = 0; band < count; band++) {
            threshold[band] = Math.max(threshold[band], layout.quiet(band));
        }
        if (sequence == ChannelStream.ONLY_LONG_SEQUENCE && previousLongValid) {
            for (var band = 0; band < count; band++) {
                var original = threshold[band];
                var limited = Math.min(original, PRE_ECHO_INCREASE * previousLong[band]);
                previousLong[band] = original;
                threshold[band] = Math.max(limited, PRE_ECHO_FLOOR * original);
            }
        } else {
            System.arraycopy(threshold, 0, previousLong, 0, count);
        }
        previousLongValid = sequence != ChannelStream.LONG_START_SEQUENCE;
        previousShortValid = false;
        if (longTns != null) {
            var order = longTns.apply(spectrum, 0, tnsIndexes);
            if (order > 0) {
                tnsOrders[0] = order;
                tnsActive = true;
                for (var band = longTns.startBand(); band < count; band++) {
                    energy[band] = kernel.energy(spectrum, offsets[band], offsets[band + 1]);
                }
            }
        }
        for (var band = 0; band < count; band++) {
            bandStart[band] = offsets[band];
            bandEnd[band] = offsets[band + 1];
            minSnr[band] = layout.minSnr(band);
        }
        System.arraycopy(spectrum, 0, grouped, 0, length);
    }

    /**
     * Analyzes an eight-short-window frame and groups its windows.
     *
     * @param attackIndex the window holding the attack, or {@code -1}
     */
    private void analyzeShort(int attackIndex) {
        layout = shortLayout;
        var offsets = layout.offsets();
        var count = layout.count();
        if (attackIndex >= 0) {
            groupCount = 4;
            System.arraycopy(GROUPING[attackIndex], 0, groupLengths, 0, 4);
        } else {
            groupCount = 2;
            groupLengths[0] = 4;
            groupLengths[1] = 4;
        }
        for (var window = 0; window < 8; window++) {
            var base = window * shortLength;
            Arrays.fill(spectrum, base + layout.lowpassLine(), base + shortLength, 0);
            var row = window * 16;
            for (var band = 0; band < count; band++) {
                windowEnergy[row + band] = kernel.energy(spectrum, base + offsets[band], base + offsets[band + 1]);
                windowThreshold[row + band] = windowEnergy[row + band] * MASKING_RATIO;
            }
            spread(windowThreshold, row, layout);
            for (var band = 0; band < count; band++) {
                var value = Math.max(windowThreshold[row + band], layout.quiet(band));
                if (previousShortValid) {
                    var limited = Math.min(value, PRE_ECHO_INCREASE * previousShort[band]);
                    previousShort[band] = value;
                    value = Math.max(limited, PRE_ECHO_FLOOR * value);
                } else {
                    previousShort[band] = value;
                }
                windowThreshold[row + band] = value;
            }
            previousShortValid = true;
            if (shortTns != null) {
                var order = shortTns.apply(spectrum, base, tnsScratch);
                if (order > 0) {
                    System.arraycopy(tnsScratch, 0, tnsIndexes, window * 12, order);
                    tnsOrders[window] = order;
                    tnsActive = true;
                    for (var band = shortTns.startBand(); band < count; band++) {
                        windowEnergy[row + band] = kernel.energy(spectrum, base + offsets[band], base + offsets[band + 1]);
                    }
                }
            }
        }
        previousLongValid = false;
        var line = 0;
        var firstWindow = 0;
        for (var group = 0; group < groupCount; group++) {
            var windows = groupLengths[group];
            for (var band = 0; band < count; band++) {
                var index = group * MAX_BANDS + band;
                bandStart[index] = line;
                var groupEnergy = 0.0f;
                var groupThreshold = 0.0f;
                for (var window = firstWindow; window < firstWindow + windows; window++) {
                    var base = window * shortLength;
                    for (var k = offsets[band]; k < offsets[band + 1]; k++) {
                        grouped[line++] = spectrum[base + k];
                    }
                    groupEnergy += windowEnergy[window * 16 + band];
                    groupThreshold += windowThreshold[window * 16 + band];
                }
                bandEnd[index] = line;
                energy[index] = groupEnergy;
                threshold[index] = groupThreshold;
                minSnr[index] = layout.minSnr(band);
            }
            firstWindow += windows;
        }
    }

    /**
     * Returns the masking ratio of a long-window band from its tonality.
     *
     * <p>The tonality is the band's spectral flatness (the ratio of the geometric to the
     * arithmetic mean of the line powers), measured over at least {@link #FLATNESS_LINES}
     * lines: a flatness of {@link #TONAL_FLATNESS} or less is fully tonal and asks for a
     * {@link #TONAL_SMR} signal-to-mask ratio, a flat spectrum is noise-like and asks for up to
     * {@link #NOISE_RELIEF} decibels less, and flatness in between interpolates.
     *
     * @implNote This implementation grants the full relief up to 64 kbps per channel and fades
     * it out towards 128 kbps per channel, where the allocation approaches a plain SNR
     * criterion and noise-like bands keep their share of the bits.
     *
     * @param start the first line of the band
     * @param end the line after the last
     * @param limit the first line removed by the low-pass filter
     * @return the ratio of threshold to energy
     */
    private float maskingRatio(int start, int end, int limit) {
        var width = end - start;
        if (width < FLATNESS_LINES) {
            var pad = (FLATNESS_LINES - width) / 2;
            start = Math.max(0, start - pad);
            end = Math.min(length, start + FLATNESS_LINES);
        }
        end = Math.min(end, limit);
        if (end - start < 2) {
            return MASKING_RATIO;
        }
        var logSum = 0.0f;
        var sum = 0.0f;
        for (var k = start; k < end; k++) {
            var power = spectrum[k] * spectrum[k] + 1e-3f;
            sum += power;
            logSum += fastLog2(power);
        }
        var lines = end - start;
        var flatness = 3.0103f * (logSum / lines - fastLog2(sum / lines));
        var tonality = Math.clamp(flatness / TONAL_FLATNESS, 0, 1);
        var smr = noiseSmr + tonality * (TONAL_SMR - noiseSmr);
        return (float) StrictMath.pow(10, -smr / 10);
    }

    /**
     * Returns an approximation of {@code log2(x)} from the float's exponent and mantissa bits.
     *
     * @implNote This implementation reads the IEEE 754 bits as a fixed-point logarithm with a
     * linear mantissa, whose error stays below 0.09; spectral flatness needs no more.
     *
     * @param x a positive finite value
     * @return the approximate binary logarithm
     */
    private static float fastLog2(float x) {
        return (Float.floatToRawIntBits(x) - 0x3F800000) * (1.0f / (1 << 23));
    }

    /**
     * Spreads thresholds across bands with the layout's masking slopes.
     *
     * @param values the thresholds
     * @param offset the index of band 0
     * @param bands the band layout
     */
    private static void spread(float[] values, int offset, BandLayout bands) {
        var count = bands.count();
        for (var band = 1; band < count; band++) {
            values[offset + band] = Math.max(values[offset + band], values[offset + band - 1] * bands.spreadUp(band));
        }
        for (var band = count - 2; band >= 0; band--) {
            values[offset + band] = Math.max(values[offset + band], values[offset + band + 1] * bands.spreadDown(band));
        }
    }

    /**
     * Returns the window sequence of the current frame.
     *
     * @return the sequence
     */
    public int sequence() {
        return sequence;
    }

    /**
     * Returns the number of window groups of the current frame.
     *
     * @return the group count
     */
    public int groupCount() {
        return groupCount;
    }

    /**
     * Returns the number of bands of the current window size.
     *
     * @return the band count
     */
    public int bandCount() {
        return layout.count();
    }

    /**
     * Returns the number of bands below the low-pass line.
     *
     * @return the active band count
     */
    public int activeBands() {
        return layout.active();
    }

    /**
     * Returns the grouped spectrum, which M/S stereo may rewrite before {@link #prepare()}.
     *
     * @return the spectrum in grouped order
     */
    public float[] grouped() {
        return grouped;
    }

    /**
     * Returns the energy array, which M/S stereo may rewrite before {@link #prepare()}.
     *
     * @return the band energies
     */
    public float[] energies() {
        return energy;
    }

    /**
     * Returns the threshold array, which M/S stereo may rewrite before {@link #prepare()}.
     *
     * @return the band thresholds
     */
    public float[] thresholds() {
        return threshold;
    }

    /**
     * Returns the first grouped line of a band.
     *
     * @param index the band index
     * @return the line
     */
    public int bandStart(int index) {
        return bandStart[index];
    }

    /**
     * Returns the grouped line after the last of a band.
     *
     * @param index the band index
     * @return the line
     */
    public int bandEnd(int index) {
        return bandEnd[index];
    }

    /**
     * Returns the fewest bands the channel may transmit: the end of its TNS range when TNS is on.
     *
     * @return the band count
     */
    public int requiredBands() {
        if (!tnsActive) {
            return 0;
        }
        return sequence == ChannelStream.EIGHT_SHORT_SEQUENCE ? shortTns.stopBand() : longTns.stopBand();
    }

    /**
     * Computes the quantization inputs of the final spectrum: magnitudes, their 3/4 powers,
     * form factors and scalefactor bounds.
     */
    public void prepare() {
        frameStamp++;
        kernel.powerLaw(grouped, magnitude, power34, 0, length);
        var count = layout.count();
        for (var group = 0; group < groupCount; group++) {
            for (var band = 0; band < count; band++) {
                var index = group * MAX_BANDS + band;
                var form = 0.0f;
                var peak = 0.0f;
                for (var k = bandStart[index]; k < bandEnd[index]; k++) {
                    var value = magnitude[k];
                    form += (float) Math.sqrt(value);
                    peak = Math.max(peak, value);
                }
                formFactor[index] = form;
                minScalefactor[index] = Quantization.minimumScalefactor(peak);
                if (peak == 0) {
                    energy[index] = 0;
                }
                thresholdRoot[index] = (float) Math.sqrt(Math.sqrt(threshold[index]));
            }
        }
    }

    /**
     * Returns the largest fourth root of a band energy, the scale of the rate control's loudness
     * reduction.
     *
     * @return the largest {@code energy^(1/4)}
     */
    public float loudnessReach() {
        var reach = 0.0f;
        var count = layout.count();
        for (var group = 0; group < groupCount; group++) {
            for (var band = 0; band < count; band++) {
                reach = Math.max(reach, (float) Math.sqrt(Math.sqrt(energy[group * MAX_BANDS + band])));
            }
        }
        return reach;
    }

    /**
     * Returns the perceptual entropy of the psychoacoustic thresholds, an estimate of the bits
     * the frame needs.
     *
     * @return the perceptual entropy
     */
    public float perceptualEntropy() {
        var pe = 0.0f;
        var count = layout.count();
        for (var group = 0; group < groupCount; group++) {
            for (var band = 0; band < count; band++) {
                var index = group * MAX_BANDS + band;
                var e = energy[index];
                var t = threshold[index];
                if (e <= t || e <= 0) {
                    continue;
                }
                var width = bandEnd[index] - bandStart[index];
                var lines = formFactor[index] / (float) Math.sqrt(Math.sqrt(e / width));
                var ratio = Quantization.log2(e / t);
                pe += (float) (ratio >= 3 ? lines * ratio : lines * (1.3219281 + 0.5593573 * ratio));
            }
        }
        return pe;
    }

    /**
     * Returns the rate-controlled threshold of a band.
     *
     * <p>The parameter runs from -1 to 2 in three regimes. Below 0 the psychoacoustic threshold
     * is lowered by up to 30 dB. From 0 to 1 it is raised in the loudness (fourth-root) domain
     * by up to {@code reach} while holes are avoided: no band's threshold exceeds its energy
     * times its minimum SNR unless the psychoacoustic threshold already does. From 1 to 2 the
     * raise restarts from 0 without hole avoidance, so the weakest bands empty first; the rate
     * control enters this regime only when the hole-avoiding plateau at 1 exceeds the budget.
     * Within each regime fewer bits are needed as the parameter grows.
     *
     * @param index the band index
     * @param parameter the rate control parameter
     * @param reach the loudness reach of the frame
     * @param lowering the threshold factor {@code 10^(3 parameter)} of negative parameters
     * @return the allowed distortion
     */
    private float allowedDistortion(int index, float parameter, float reach, float lowering) {
        var t0 = threshold[index];
        var e = energy[index];
        if (parameter < 0) {
            return t0 * lowering;
        }
        var protect = parameter <= 1;
        var root = thresholdRoot[index] + (protect ? parameter : parameter - 1) * reach;
        var raised = root * root * root * root;
        if (!protect) {
            return raised;
        }
        var floor = e * minSnr[index];
        return raised > floor ? Math.max(floor, t0) : raised;
    }

    /**
     * Chooses scalefactors for a rate control parameter, quantizes the spectrum and returns its
     * cost.
     *
     * <p>Afterwards {@link #maxBands()} is the number of bands the channel needs; a channel pair
     * may raise it with {@link #setMaxBands(int)} before calling {@link #cost(boolean)}.
     *
     * @param parameter the rate control parameter, from -1 to 2
     * @param reach the loudness reach of the frame
     * @param minBands the fewest bands to transmit per group
     */
    public void quantize(float parameter, float reach, int minBands) {
        var count = layout.count();
        var lowest = Integer.MAX_VALUE;
        var highestBound = 0;
        var lowering = parameter < 0 ? (float) StrictMath.pow(10, 3 * parameter) : 1;
        for (var group = 0; group < groupCount; group++) {
            for (var band = 0; band < count; band++) {
                var index = group * MAX_BANDS + band;
                var e = energy[index];
                var allowed = e > 0 ? allowedDistortion(index, parameter, reach, lowering) : 0;
                if (e <= 0 || allowed >= e) {
                    scalefactors[index] = -1;
                    continue;
                }
                var scalefactor = chooseScalefactor(index, allowed);
                scalefactors[index] = scalefactor;
                lowest = Math.min(lowest, scalefactor);
                highestBound = Math.max(highestBound, minScalefactor[index]);
            }
        }
        if (lowest != Integer.MAX_VALUE) {
            var low = Math.max(lowest, highestBound - 60);
            var high = low + 60;
            for (var group = 0; group < groupCount; group++) {
                for (var band = 0; band < count; band++) {
                    var index = group * MAX_BANDS + band;
                    if (scalefactors[index] >= 0) {
                        scalefactors[index] = Math.clamp(scalefactors[index], low, high);
                    }
                }
            }
        }
        maxBands = minBands;
        for (var group = 0; group < groupCount; group++) {
            for (var band = 0; band < count; band++) {
                var index = group * MAX_BANDS + band;
                var start = bandStart[index];
                var end = bandEnd[index];
                var scalefactor = scalefactors[index];
                var current = quantStamp[index] == frameStamp && quantScalefactor[index] == scalefactor;
                if (scalefactor < 0) {
                    maxQuant[index] = 0;
                    if (!current) {
                        Arrays.fill(quant, start, end, 0);
                        quantStamp[index] = frameStamp;
                        quantScalefactor[index] = -1;
                    }
                    continue;
                }
                int peak;
                if (current) {
                    peak = quantPeak[index];
                } else {
                    peak = kernel.quantize(power34, quant, start, end, Quantization.inverseStep(scalefactor));
                    if (peak != 0) {
                        // The sign bit selects negation without a branch; a negative zero or NaN line
                        // quantizes to zero, so negating it changes nothing.
                        for (var k = start; k < end; k++) {
                            var negative = Float.floatToRawIntBits(grouped[k]) >> 31;
                            quant[k] = (quant[k] ^ negative) - negative;
                        }
                    }
                    quantStamp[index] = frameStamp;
                    quantScalefactor[index] = scalefactor;
                    quantPeak[index] = peak;
                }
                maxQuant[index] = peak;
                if (peak == 0) {
                    scalefactors[index] = -1;
                    continue;
                }
                maxBands = Math.max(maxBands, band + 1);
            }
        }
    }

    /**
     * Sets the number of bands transmitted per group, for channel pairs sharing one.
     *
     * @param bands the band count, at least the channel's own
     */
    public void setMaxBands(int bands) {
        this.maxBands = bands;
    }

    /**
     * Returns the number of bands transmitted per group.
     *
     * @return {@code max_sfb}
     */
    public int maxBands() {
        return maxBands;
    }

    /**
     * Picks the coarsest scalefactor whose quantization error stays within the allowed
     * distortion.
     *
     * @param index the band index
     * @param allowed the allowed distortion
     * @return the scalefactor, from the band's bound to 255
     */
    private int chooseScalefactor(int index, float allowed) {
        var form = Math.max(formFactor[index], 1e-9f);
        var estimate = (int) Math.floor(8.0 / 3.0 * Quantization.log2(6.75 * allowed / form)) + 100;
        var bound = minScalefactor[index];
        var scalefactor = Math.clamp(estimate, bound, 255);
        var distortion = measure(index, scalefactor);
        if (distortion > allowed) {
            for (var step = 0; step < 4 && distortion > allowed && scalefactor > bound; step++) {
                scalefactor--;
                distortion = measure(index, scalefactor);
            }
        } else {
            for (var step = 0; step < 4 && scalefactor < 255; step++) {
                var coarser = measure(index, scalefactor + 1);
                if (coarser > allowed) {
                    break;
                }
                scalefactor++;
            }
        }
        return scalefactor;
    }

    /**
     * Measures the quantization error of a band at a scalefactor, memoizing it for the frame.
     *
     * @param index the band index
     * @param scalefactor the scalefactor, at least the band's bound
     * @return the squared error
     */
    private float measure(int index, int scalefactor) {
        var slot = scalefactor - minScalefactor[index];
        var memo = index * MEMO_SPAN + slot;
        if (slot >= 0 && slot < MEMO_SPAN && memoStamp[memo] == frameStamp) {
            return distortionMemo[memo];
        }
        var distortion = kernel.distortion(magnitude, power34, scratch, bandStart[index], bandEnd[index],
                Quantization.inverseStep(scalefactor), Quantization.step(scalefactor));
        if (slot >= 0 && slot < MEMO_SPAN) {
            memoStamp[memo] = frameStamp;
            distortionMemo[memo] = distortion;
        }
        return distortion;
    }

    /**
     * Returns the bits of the current quantization: sections, scalefactors and spectral data.
     *
     * <p>An exhaustive call plans the sections optimally and must precede {@link #write}; a
     * quick call returns an upper bound from a greedy partition over the two cheapest-looking
     * codebook pairs of each band.
     *
     * @param exhaustive whether to plan the sections exactly
     * @return the bits
     */
    public int cost(boolean exhaustive) {
        var total = 0;
        var shortWindow = sequence == ChannelStream.EIGHT_SHORT_SEQUENCE;
        for (var group = 0; group < groupCount; group++) {
            for (var band = 0; band < maxBands; band++) {
                var index = group * MAX_BANDS + band;
                var memo = index * SpectralCoder.BOOKS;
                var offset = band * SpectralCoder.BOOKS;
                if (costStamp[index] == frameStamp && costScalefactor[index] == quantScalefactor[index]
                        && costExhaustive[index] == exhaustive) {
                    System.arraycopy(costMemo, memo, costs, offset, SpectralCoder.BOOKS);
                } else {
                    SpectralCoder.countBand(quant, bandStart[index], bandEnd[index], maxQuant[index], costs, offset,
                            exhaustive);
                    System.arraycopy(costs, offset, costMemo, memo, SpectralCoder.BOOKS);
                    costStamp[index] = frameStamp;
                    costScalefactor[index] = quantScalefactor[index];
                    costExhaustive[index] = exhaustive;
                }
            }
            if (exhaustive) {
                total += coder.plan(costs, maxBands, shortWindow, bookScratch);
                System.arraycopy(bookScratch, 0, books, group * MAX_BANDS, maxBands);
            } else {
                total += greedy(group, shortWindow);
            }
        }
        return total + scalefactorBits();
    }

    /**
     * Returns the cost of the partition that gives each band its cheapest counted codebook and
     * opens a section wherever the codebook changes.
     *
     * @param group the window group index
     * @param shortWindow whether the window sequence is eight short
     * @return the section and spectral bits
     */
    private int greedy(int group, boolean shortWindow) {
        var total = 0;
        var runBook = -1;
        var runLength = 0;
        for (var band = 0; band < maxBands; band++) {
            var base = band * SpectralCoder.BOOKS;
            var book = 0;
            var cheapest = SpectralCoder.FORBIDDEN;
            for (var candidate = 0; candidate < SpectralCoder.BOOKS; candidate++) {
                if (costs[base + candidate] < cheapest) {
                    cheapest = costs[base + candidate];
                    book = candidate;
                }
            }
            total += cheapest;
            books[group * MAX_BANDS + band] = book;
            if (book != runBook) {
                if (runLength > 0) {
                    total += SpectralCoder.sectionHeaderBits(runLength, shortWindow);
                }
                runBook = book;
                runLength = 0;
            }
            runLength++;
        }
        if (runLength > 0) {
            total += SpectralCoder.sectionHeaderBits(runLength, shortWindow);
        }
        return total;
    }

    /**
     * Assigns scalefactors to bands coded with a spectrum codebook although all zero, and
     * returns the bits of the scalefactor data.
     *
     * @return the bits
     */
    private int scalefactorBits() {
        var bits = 0;
        var previous = -1;
        for (var group = 0; group < groupCount; group++) {
            for (var band = 0; band < maxBands; band++) {
                var index = group * MAX_BANDS + band;
                if (books[index] == Codebooks.ZERO_HCB) {
                    continue;
                }
                if (scalefactors[index] < 0) {
                    scalefactors[index] = previous < 0 ? firstScalefactor() : previous;
                }
                if (previous < 0) {
                    globalGain = scalefactors[index];
                    previous = globalGain;
                }
                bits += HuffmanTables.SCALEFACTOR_LENGTHS[scalefactors[index] - previous + 60];
                previous = scalefactors[index];
            }
        }
        if (previous < 0) {
            globalGain = 0;
        }
        return bits;
    }

    /**
     * Returns the scalefactor of the first coded band, or 100 when no band is coded.
     *
     * @return the scalefactor
     */
    private int firstScalefactor() {
        var count = layout.count();
        for (var group = 0; group < groupCount; group++) {
            for (var band = 0; band < count; band++) {
                var index = group * MAX_BANDS + band;
                if (scalefactors[index] >= 0) {
                    return scalefactors[index];
                }
            }
        }
        return 100;
    }

    /**
     * Returns the bits of the channel's fixed side information: global gain, the pulse, TNS and
     * gain control flags, the TNS data and, outside a common window, the {@code ics_info}.
     *
     * @param commonWindow whether the channel shares its {@code ics_info} with a pair
     * @return the bits
     */
    public int sideBits(boolean commonWindow) {
        var bits = 8 + 3 + tnsBits();
        if (!commonWindow) {
            bits += infoBits();
        }
        return bits;
    }

    /**
     * Returns the bits of the {@code ics_info} element.
     *
     * @return the bits
     */
    public int infoBits() {
        return sequence == ChannelStream.EIGHT_SHORT_SEQUENCE ? 1 + 2 + 1 + 4 + 7 : 1 + 2 + 1 + 6 + 1;
    }

    /**
     * Returns the bits of the TNS data.
     *
     * @return the bits, 0 when TNS is off
     */
    private int tnsBits() {
        if (!tnsActive) {
            return 0;
        }
        var shortWindow = sequence == ChannelStream.EIGHT_SHORT_SEQUENCE;
        var windows = shortWindow ? 8 : 1;
        var bits = 0;
        for (var window = 0; window < windows; window++) {
            bits += shortWindow ? 1 : 2;
            var order = tnsOrders[window];
            if (order == 0) {
                continue;
            }
            bits += 1 + (shortWindow ? 4 + 3 : 6 + 5) + 1 + 1;
            bits += order * tnsCoefficientBits(window);
        }
        return bits;
    }

    /**
     * Returns the bits per coefficient of a window's TNS filter, using coefficient compression
     * when every index fits.
     *
     * @param window the short window index, or 0 for a long window
     * @return the coefficient width
     */
    private int tnsCoefficientBits(int window) {
        var resolution = sequence == ChannelStream.EIGHT_SHORT_SEQUENCE ? shortTns.resolution() : longTns.resolution();
        var half = 1 << (resolution - 2);
        for (var i = 0; i < tnsOrders[window]; i++) {
            var index = tnsIndexes[window * 12 + i];
            if (index < -half || index >= half) {
                return resolution;
            }
        }
        return resolution - 1;
    }

    /**
     * Writes the {@code ics_info} element.
     *
     * @param writer the bit sink
     */
    public void writeInfo(MsbBitWriter writer) {
        writer.write(0, 1);
        writer.write(sequence, 2);
        writer.write(shape, 1);
        if (sequence == ChannelStream.EIGHT_SHORT_SEQUENCE) {
            writer.write(maxBands, 4);
            var grouping = 0;
            var window = 0;
            for (var group = 0; group < groupCount; group++) {
                for (var i = 0; i < groupLengths[group]; i++) {
                    if (window > 0) {
                        grouping = grouping << 1 | (i > 0 ? 1 : 0);
                    }
                    window++;
                }
            }
            writer.write(grouping, 7);
        } else {
            writer.write(maxBands, 6);
            writer.write(0, 1);
        }
    }

    /**
     * Writes the channel's {@code individual_channel_stream}; the sections must have been
     * planned by an exhaustive {@link #quantize} call.
     *
     * @param writer the bit sink
     * @param commonWindow whether the {@code ics_info} was written for the pair
     */
    public void write(MsbBitWriter writer, boolean commonWindow) {
        writer.write(globalGain, 8);
        if (!commonWindow) {
            writeInfo(writer);
        }
        var shortWindow = sequence == ChannelStream.EIGHT_SHORT_SEQUENCE;
        for (var group = 0; group < groupCount; group++) {
            System.arraycopy(books, group * MAX_BANDS, bookScratch, 0, maxBands);
            SpectralCoder.writeSections(writer, bookScratch, maxBands, shortWindow);
        }
        var previous = globalGain;
        for (var group = 0; group < groupCount; group++) {
            for (var band = 0; band < maxBands; band++) {
                var index = group * MAX_BANDS + band;
                if (books[index] == Codebooks.ZERO_HCB) {
                    continue;
                }
                var delta = scalefactors[index] - previous + 60;
                writer.write(HuffmanTables.SCALEFACTOR_CODES[delta], HuffmanTables.SCALEFACTOR_LENGTHS[delta]);
                previous = scalefactors[index];
            }
        }
        writer.write(0, 1);
        writer.write(tnsActive ? 1 : 0, 1);
        if (tnsActive) {
            writeTns(writer);
        }
        writer.write(0, 1);
        for (var group = 0; group < groupCount; group++) {
            for (var band = 0; band < maxBands; band++) {
                var index = group * MAX_BANDS + band;
                var book = books[index];
                if (book != Codebooks.ZERO_HCB) {
                    SpectralCoder.writeBand(writer, quant, bandStart[index], bandEnd[index], book);
                }
            }
        }
    }

    /**
     * Writes the TNS data.
     *
     * @param writer the bit sink
     */
    private void writeTns(MsbBitWriter writer) {
        var shortWindow = sequence == ChannelStream.EIGHT_SHORT_SEQUENCE;
        var tns = shortWindow ? shortTns : longTns;
        var windows = shortWindow ? 8 : 1;
        for (var window = 0; window < windows; window++) {
            var order = tnsOrders[window];
            writer.write(order > 0 ? 1 : 0, shortWindow ? 1 : 2);
            if (order == 0) {
                continue;
            }
            writer.write(tns.resolution() - 3, 1);
            writer.write(tns.length(), shortWindow ? 4 : 6);
            writer.write(order, shortWindow ? 3 : 5);
            writer.write(0, 1);
            var bits = tnsCoefficientBits(window);
            writer.write(bits < tns.resolution() ? 1 : 0, 1);
            for (var i = 0; i < order; i++) {
                writer.write(tnsIndexes[window * 12 + i], bits);
            }
        }
    }

}
