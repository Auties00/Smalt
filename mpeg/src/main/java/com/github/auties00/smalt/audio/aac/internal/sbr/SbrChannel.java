/* Ports PacketVideo aacdec, Android 4.0.4 (calc_sbr_envelope.cpp). Copyright 1998-2010 PacketVideo; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.sbr;

import java.util.Arrays;

/**
 * Runs spectral band replication on one channel: the QMF analysis of the core signal, the
 * resolution of the delta-coded scalefactors, the high frequency generation and adjustment, the
 * assembly of the subband matrix and the state carried between frames.
 *
 * <p>Time is counted in QMF slots, two per SBR time slot. The low band buffer holds the eight
 * slots of the previous frame that the generator reads back ({@code t_HFGen}) followed by the
 * current frame's analysis; the high band buffer is indexed by output slot and may extend six
 * slots past the frame, the part of the frame's last envelope that reaches into the next frame,
 * which is kept as the carry. The output slot {@code l} reads the low band at buffer slot
 * {@code l + 2} ({@code t_HFAdj}). The first slots of a frame, up to where the previous frame's
 * last envelope ended ({@code l_Temp}), take their high band from the carry, above the previous
 * frame's crossover. The low band holds only the subbands below the crossover of the frame it
 * was analyzed in, as the standard defines {@code X_Low}: a frame with a high band clears the
 * subbands from its crossover up, which matters when the crossover rises between frames.
 *
 * <p>The low delay spectral band replication of AAC-ELD runs on low delay QMF banks with one QMF slot
 * per time slot and no delay between the bands: the low band buffer holds the two slots of the previous
 * frame the generator's prediction reads, the output slot {@code l} reads the low band at buffer slot
 * {@code l + 2}, the covariance spans the frame's own slots, and envelopes end within the frame.
 *
 * @implNote This implementation follows the HF generation and HF adjustment of ISO/IEC 14496-3 in the
 * high quality (complex) mode, in double precision for the covariance and gain computations and
 * single precision for the subband samples; the order of the limiter, noise and smoothing steps
 * and the indexing of the noise and sinusoid generators follow the PacketVideo AAC decoder.
 * Energies are computed on the 16-bit sample scale the standard's dequantization refers to, which
 * the QMF banks convert to and from.
 */
final class SbrChannel {
    /**
     * The number of low band slots kept from the previous frame, {@code t_HFGen}.
     */
    static final int LOW_LEAD = 8;

    /**
     * The number of high band slots carried into the next frame.
     */
    static final int HIGH_LEAD = 6;

    /**
     * The offset between an output slot and the low band slot it reads, {@code t_HFAdj}.
     */
    private static final int ADJUST = 2;

    /**
     * The gain smoothing filter {@code h_smooth}, newest slot first.
     */
    private static final double[] SMOOTHING = {
            0.33333333333333, 0.30150283239582, 0.21816949906249, 0.11516383427084, 0.03183050093751
    };

    /**
     * The limiter gain of each value of {@code bs_limiter_gains}: -3, 0, 3 dB and infinity.
     */
    private static final double[] LIMITER_GAINS = {0.70795, 1.0, 1.41254, 1e10};

    /**
     * The largest gain the limiter allows.
     */
    private static final double MAX_GAIN = 1e5;

    /**
     * The largest compensation gain the limiter applies, 4 dB.
     */
    private static final double MAX_BOOST = 1.584893192;

    /**
     * The small energy {@code EPS0} that keeps empty limiter bands finite.
     */
    private static final double EPSILON = 1e-12;

    /**
     * The real parts of the noise table, repeated past its end for the 64 entries a slot may
     * read beyond it.
     */
    private static final float[] NOISE_REAL = new float[512 + 64];

    /**
     * The imaginary parts of the noise table, repeated like {@link #NOISE_REAL}.
     */
    private static final float[] NOISE_IMAGINARY = new float[512 + 64];

    static {
        for (var i = 0; i < NOISE_REAL.length; i++) {
            NOISE_REAL[i] = (float) SbrTables.NOISE_REAL[i & 511];
            NOISE_IMAGINARY[i] = (float) SbrTables.NOISE_IMAGINARY[i & 511];
        }
    }

    /**
     * The number of QMF slots per frame, 32 or 30.
     */
    final int slots;

    /**
     * The gain and noise application kernel.
     */
    private final SbrKernel kernel;

    /**
     * The gain of each high subband in the current slot.
     */
    private final float[] slotGain = new float[64];

    /**
     * The noise level of each high subband in the current slot, zero where no noise is added.
     */
    private final float[] slotNoise = new float[64];

    /**
     * The high subbands that carry a sinusoid in the current envelope.
     */
    private final int[] sineBands = new int[64];

    /**
     * The analysis bank.
     */
    private final QmfAnalysis analysis;

    /**
     * The synthesis bank.
     */
    final QmfSynthesis synthesis;

    /**
     * The real parts of the low band, 32 subbands per slot.
     */
    private final float[] lowRe;

    /**
     * The imaginary parts of the low band.
     */
    private final float[] lowIm;

    /**
     * The real parts of the high band, 64 subbands per output slot.
     */
    final float[] highRe;

    /**
     * The imaginary parts of the high band.
     */
    final float[] highIm;

    /**
     * The covariance sums of the low subbands, 32 values for each of eight sums.
     */
    private final double[] covariance = new double[256];

    /**
     * The real parts of the first linear prediction coefficient of each low subband.
     */
    private final double[] alpha0Re = new double[32];

    /**
     * The imaginary parts of the first linear prediction coefficient.
     */
    private final double[] alpha0Im = new double[32];

    /**
     * The real parts of the second linear prediction coefficient.
     */
    private final double[] alpha1Re = new double[32];

    /**
     * The imaginary parts of the second linear prediction coefficient.
     */
    private final double[] alpha1Im = new double[32];

    /**
     * The chirp factor of each noise floor band, carried between frames.
     */
    private final double[] bandwidth = new double[SbrFrequencies.MAX_NOISE_BANDS];

    /**
     * The inverse filtering mode of each noise floor band in the previous frame.
     */
    private final int[] previousInverseFiltering = new int[SbrFrequencies.MAX_NOISE_BANDS];

    /**
     * The scalefactors of the previous frame's last envelope.
     */
    private final int[] previousEnvelope = new int[64];

    /**
     * The frequency resolution of {@link #previousEnvelope}.
     */
    private int previousResolution = 1;

    /**
     * The scalefactors of the previous frame's last noise floor envelope.
     */
    private final int[] previousNoise = new int[SbrFrequencies.MAX_NOISE_BANDS];

    /**
     * The dequantized envelope energies of the current frame, by envelope and band.
     */
    final double[][] envelopeEnergy = new double[SbrFrameData.MAX_ENVELOPES][64];

    /**
     * The dequantized noise floor ratios of the current frame, by envelope and band.
     */
    final double[][] noiseFloor = new double[2][SbrFrequencies.MAX_NOISE_BANDS];

    /**
     * The gains of each output slot of the frame, at index {@code slot + 4}, preceded by the
     * four slots the smoothing filter reaches back to.
     */
    private final double[][] gainHistory;

    /**
     * The noise levels of each output slot, indexed like {@link #gainHistory}.
     */
    private final double[][] noiseHistory;

    /**
     * Whether the histories hold the gains of a previous frame; a reset clears it so that the
     * first envelope after it fills them.
     */
    private boolean historyValid;

    /**
     * The output slot after the last one the previous frame adjusted, whose four predecessors
     * open the histories of the next frame.
     */
    private int historyEnd;

    /**
     * The index into the noise table of the last noise sample used.
     */
    private int noiseIndex;

    /**
     * The phase of the sinusoid generator for the next slot.
     */
    private int sineIndex;

    /**
     * Whether each subband carried a sinusoid in the last envelope of the previous frame: a
     * flagged sinusoid starts at the transient envelope, or continues from the frame before.
     */
    private final boolean[] previousHarmonic = new boolean[64];

    /**
     * Whether each subband carries a sinusoid flag in the current frame.
     */
    private final boolean[] harmonic = new boolean[64];

    /**
     * Whether the previous frame placed a transient at the start of this one.
     */
    private boolean transientAtStart;

    /**
     * The first high subband of the previous frame, or 32 when it had no high band.
     */
    private int previousKx = 32;

    /**
     * The subband after the previous frame's high band.
     */
    private int previousEnd = 32;

    /**
     * The real parts of the previous frame's high band past its end, 64 subbands per slot.
     */
    private final float[] carryRe = new float[HIGH_LEAD * 64];

    /**
     * The imaginary parts of the carry.
     */
    private final float[] carryIm = new float[HIGH_LEAD * 64];

    /**
     * The number of leading slots of the current frame taken from the carry.
     */
    private int carrySlots;

    /**
     * The output slot after the current frame's last envelope.
     */
    private int lastSlot;

    /**
     * The estimated energy of each high subband in the current envelope.
     */
    private final double[] energy = new double[64];

    /**
     * The reference energy of each high subband in the current envelope.
     */
    private final double[] reference = new double[64];

    /**
     * The gain of each high subband in the current envelope.
     */
    private final double[] gain = new double[64];

    /**
     * The noise level of each high subband in the current envelope.
     */
    private final double[] noiseLevel = new double[64];

    /**
     * The sinusoid level of each high subband in the current envelope.
     */
    private final double[] sineLevel = new double[64];

    /**
     * The number of low band slots kept from the previous frame: {@link #LOW_LEAD}, or 2 for low
     * delay spectral band replication.
     */
    private final int lowLead;

    /**
     * The number of QMF slots per time slot: 2, or 1 for low delay spectral band replication.
     */
    private final int rate;

    /**
     * The number of covariance rows past the frame's slots: 6, or 0 for low delay spectral band
     * replication.
     */
    private final int covarianceExtra;

    /**
     * Creates a channel with silent filterbanks.
     *
     * @param frameLength the number of core samples per frame, 1024 or 960
     * @param bands the number of synthesis subbands, 64 or 32 (downsampled)
     * @param kernel the kernel running the filterbank transforms and the gain application
     */
    SbrChannel(int frameLength, int bands, SbrKernel kernel) {
        this(frameLength, bands, kernel, false);
    }

    /**
     * Creates a channel with silent filterbanks, optionally for low delay spectral band replication.
     *
     * @param frameLength the number of core samples per frame: 1024 or 960, or 512 or 480 for low delay
     * @param bands the number of synthesis subbands, 64 or 32 (downsampled)
     * @param kernel the kernel running the filterbank transforms and the gain application
     * @param lowDelay whether the channel runs the low delay spectral band replication of AAC-ELD
     */
    SbrChannel(int frameLength, int bands, SbrKernel kernel, boolean lowDelay) {
        this.slots = frameLength / 32;
        this.lowLead = lowDelay ? ADJUST : LOW_LEAD;
        this.rate = lowDelay ? 1 : 2;
        this.covarianceExtra = lowDelay ? 0 : 6;
        this.gainHistory = new double[slots + HIGH_LEAD + 4][64];
        this.noiseHistory = new double[slots + HIGH_LEAD + 4][64];
        this.kernel = kernel;
        this.analysis = new QmfAnalysis(kernel, lowDelay);
        this.synthesis = new QmfSynthesis(bands, kernel, lowDelay);
        this.lowRe = new float[(slots + lowLead) * 32];
        this.lowIm = new float[(slots + lowLead) * 32];
        this.highRe = new float[(slots + HIGH_LEAD) * 64];
        this.highIm = new float[(slots + HIGH_LEAD) * 64];
    }

    /**
     * Analyzes the current frame's core samples into the low band.
     *
     * @param core the core samples
     */
    void analyze(float[] core) {
        analysis.analyze(core, slots, lowRe, lowIm, lowLead * 32);
    }

    /**
     * Forgets the scalefactors and the gain history, after a header changed the frequency
     * tables.
     */
    void reset() {
        Arrays.fill(previousEnvelope, 0);
        previousResolution = 1;
        Arrays.fill(previousNoise, 0);
        noiseIndex = 0;
        historyValid = false;
    }

    /**
     * Resolves the delta-coded scalefactors of a frame against the previous ones and checks
     * their range.
     *
     * <p>The previous values are replaced only when every value is in range.
     *
     * @param data the frame data, resolved in place
     * @param tables the frequency tables
     * @return {@code true} when every scalefactor is in range
     */
    boolean resolve(SbrFrameData data, SbrFrequencies tables) {
        var envelopeLimit = data.balance ? 126 : 127;
        for (var l = 0; l < data.envelopes; l++) {
            var resolution = data.resolution[l];
            var bands = tables.bands(resolution);
            var values = data.envelope[l];
            if (data.envelopeTime[l]) {
                var reference = l == 0 ? previousEnvelope : data.envelope[l - 1];
                var referenceResolution = l == 0 ? previousResolution : data.resolution[l - 1];
                for (var k = 0; k < bands; k++) {
                    values[k] += reference[mapBand(tables, k, resolution, referenceResolution)];
                }
            } else {
                for (var k = 1; k < bands; k++) {
                    values[k] += values[k - 1];
                }
            }
            for (var k = 0; k < bands; k++) {
                if (values[k] < 0 || values[k] > envelopeLimit) {
                    return false;
                }
            }
        }
        var noiseLimit = data.balance ? 60 : rate == 1 ? 31 : 30;
        for (var l = 0; l < data.noiseEnvelopes; l++) {
            var values = data.noise[l];
            if (data.noiseTime[l]) {
                var reference = l == 0 ? previousNoise : data.noise[l - 1];
                for (var k = 0; k < tables.noiseBands; k++) {
                    values[k] += reference[k];
                }
            } else {
                for (var k = 1; k < tables.noiseBands; k++) {
                    values[k] += values[k - 1];
                }
            }
            for (var k = 0; k < tables.noiseBands; k++) {
                if (values[k] < 0 || values[k] > noiseLimit) {
                    return false;
                }
            }
        }
        var last = data.envelopes - 1;
        System.arraycopy(data.envelope[last], 0, previousEnvelope, 0, tables.bands(data.resolution[last]));
        previousResolution = data.resolution[last];
        System.arraycopy(data.noise[data.noiseEnvelopes - 1], 0, previousNoise, 0, tables.noiseBands);
        return true;
    }

    /**
     * Returns the band of another frequency resolution that a time delta refers to.
     *
     * @param tables the frequency tables
     * @param band the band in the current resolution
     * @param resolution the current resolution
     * @param reference the resolution of the reference envelope
     * @return the band of the reference envelope
     */
    private static int mapBand(SbrFrequencies tables, int band, int resolution, int reference) {
        if (resolution == reference) {
            return band;
        }
        if (resolution == 0) {
            return band == 0 ? 0 : 2 * band - (tables.highBands & 1);
        }
        var border = tables.high[band];
        var lowBand = 0;
        while (lowBand + 1 < tables.lowBands && tables.low[lowBand + 1] <= border) {
            lowBand++;
        }
        return lowBand;
    }

    /**
     * Generates and adjusts the high band of the current frame.
     *
     * @param data the resolved frame data
     * @param tables the frequency tables
     * @param header the SBR header in effect
     */
    void generate(SbrFrameData data, SbrFrequencies tables, SbrHeader header) {
        var start = rate * data.borders[0];
        var end = rate * data.borders[data.envelopes];
        lastSlot = end;
        for (var slot = lowLead; slot < lowLead + slots; slot++) {
            Arrays.fill(lowRe, slot * 32 + tables.kx, slot * 32 + 32, 0);
            Arrays.fill(lowIm, slot * 32 + tables.kx, slot * 32 + 32, 0);
        }
        predict(tables.k0);
        chirp(data, tables.noiseBands);
        patch(tables, start, end);
        adjust(data, tables, header);
    }

    /**
     * Computes the linear prediction coefficients of the low subbands from their covariance
     * over the frame, as the HF generation of ISO/IEC 14496-3 specifies.
     *
     * @param k0 the first subband of the master table; subbands below it are patch sources
     */
    private void predict(int k0) {
        kernel.covariance(lowRe, lowIm, k0, slots + covarianceExtra, covariance);
        for (var band = 1; band < k0; band++) {
            var r01r = covariance[band];
            var r01i = covariance[32 + band];
            var r02r = covariance[64 + band];
            var r02i = covariance[96 + band];
            var r11 = covariance[128 + band];
            var r12r = covariance[160 + band];
            var r12i = covariance[192 + band];
            var r22 = covariance[224 + band];
            double a1r = 0;
            double a1i = 0;
            var determinant = r11 * r22 - (r12r * r12r + r12i * r12i) / (1 + 1e-6);
            if (determinant != 0) {
                a1r = (r01r * r12r - r01i * r12i - r02r * r11) / determinant;
                a1i = (r01r * r12i + r01i * r12r - r02i * r11) / determinant;
            }
            double a0r = 0;
            double a0i = 0;
            if (r11 != 0) {
                a0r = -(r01r + a1r * r12r + a1i * r12i) / r11;
                a0i = -(r01i + a1i * r12r - a1r * r12i) / r11;
            }
            if (a0r * a0r + a0i * a0i >= 16 || a1r * a1r + a1i * a1i >= 16) {
                a0r = 0;
                a0i = 0;
                a1r = 0;
                a1i = 0;
            }
            alpha0Re[band] = a0r;
            alpha0Im[band] = a0i;
            alpha1Re[band] = a1r;
            alpha1Im[band] = a1i;
        }
    }

    /**
     * Updates the chirp factors from the inverse filtering modes, as the HF generation of
     * ISO/IEC 14496-3 specifies.
     *
     * @param data the frame data
     * @param bands the number of noise floor bands
     */
    private void chirp(SbrFrameData data, int bands) {
        for (var i = 0; i < bands; i++) {
            var mode = data.inverseFiltering[i];
            var previous = previousInverseFiltering[i];
            var target = switch (mode) {
                case 0 -> previous == 1 ? 0.6 : 0.0;
                case 1 -> previous == 0 ? 0.6 : 0.75;
                case 2 -> 0.9;
                default -> 0.98;
            };
            var value = target < bandwidth[i] ? 0.75 * target + 0.25 * bandwidth[i]
                    : 0.90625 * target + 0.09375 * bandwidth[i];
            if (value < 0.015625) {
                value = 0;
            } else if (value >= 0.99609375) {
                value = 0.99609375;
            }
            bandwidth[i] = value;
            previousInverseFiltering[i] = mode;
        }
    }

    /**
     * Copies the low subbands into the high band through the patches, filtered by the chirped
     * linear prediction.
     *
     * @param tables the frequency tables
     * @param start the first output slot
     * @param end the output slot after the last
     */
    private void patch(SbrFrequencies tables, int start, int end) {
        var target = tables.kx;
        var noiseBand = 0;
        for (var p = 0; p < tables.patches; p++) {
            for (var x = 0; x < tables.patchSubbands[p]; x++, target++) {
                var source = tables.patchStart[p] + x;
                while (noiseBand + 1 < tables.noiseBands && target >= tables.noise[noiseBand + 1]) {
                    noiseBand++;
                }
                var bw = bandwidth[noiseBand];
                var a0r = (float) (bw * alpha0Re[source]);
                var a0i = (float) (bw * alpha0Im[source]);
                var a1r = (float) (bw * bw * alpha1Re[source]);
                var a1i = (float) (bw * bw * alpha1Im[source]);
                for (var slot = start; slot < end; slot++) {
                    var s0 = (slot + ADJUST) * 32 + source;
                    var s1 = s0 - 32;
                    var s2 = s0 - 64;
                    var re = lowRe[s0] + a0r * lowRe[s1] - a0i * lowIm[s1] + a1r * lowRe[s2] - a1i * lowIm[s2];
                    var im = lowIm[s0] + a0r * lowIm[s1] + a0i * lowRe[s1] + a1r * lowIm[s2] + a1i * lowRe[s2];
                    highRe[slot * 64 + target] = re;
                    highIm[slot * 64 + target] = im;
                }
            }
        }
    }

    /**
     * Adjusts the envelope of the generated high band and adds noise and sinusoids, as the HF
     * adjustment of ISO/IEC 14496-3 specifies.
     *
     * @param data the resolved frame data
     * @param tables the frequency tables
     * @param header the SBR header in effect
     */
    private void adjust(SbrFrameData data, SbrFrequencies tables, SbrHeader header) {
        var kx = tables.kx;
        var m = tables.m;
        tables.updateLimiter(header.limiterBands);
        Arrays.fill(harmonic, false);
        if (data.harmonics) {
            for (var i = 0; i < tables.highBands; i++) {
                if (data.harmonic[i]) {
                    harmonic[(tables.high[i] + tables.high[i + 1]) >> 1] = true;
                }
            }
        }
        var limiterGain = LIMITER_GAINS[header.limiterGains];
        for (var l = 0; l < data.envelopes; l++) {
            var first = rate * data.borders[l];
            var last = rate * data.borders[l + 1];
            var noNoise = l == data.transientEnvelope || (l == 0 && transientAtStart);
            var noiseEnvelope = data.noiseEnvelopes > 1 && data.borders[l] >= data.noiseBorders[1] ? 1 : 0;
            estimate(kx, m, first, last);
            mapGains(data, tables, header, l, noiseEnvelope, noNoise);
            limit(tables, limiterGain, noNoise);
            apply(kx, m, first, last, noNoise, header.smoothingMode == 0 && !noNoise, l == 0);
        }
        var lastSines = data.envelopes - 1 >= data.transientEnvelope;
        for (var k = 0; k < 64; k++) {
            previousHarmonic[k] = harmonic[k] && (lastSines || previousHarmonic[k]);
        }
        historyEnd = rate * data.borders[data.envelopes];
        transientAtStart = data.transientEnvelope == data.envelopes;
    }

    /**
     * Estimates the energy of each high subband over an envelope.
     *
     * @param kx the first high subband
     * @param m the number of high subbands
     * @param first the envelope's first output slot
     * @param last the output slot after the envelope
     */
    private void estimate(int kx, int m, int first, int last) {
        var scale = 1.0 / (last - first);
        for (var k = 0; k < m; k++) {
            double sum = 0;
            for (var slot = first; slot < last; slot++) {
                var index = slot * 64 + kx + k;
                double re = highRe[index];
                double im = highIm[index];
                sum += re * re + im * im;
            }
            energy[k] = sum * scale;
        }
    }

    /**
     * Maps the envelope and noise floor data onto the high subbands and computes their gains,
     * noise levels and sinusoid levels.
     *
     * @param data the frame data
     * @param tables the frequency tables
     * @param header the SBR header in effect
     * @param envelope the envelope index
     * @param noiseEnvelope the index of the noise floor envelope covering it
     * @param noNoise whether the envelope starts at a transient and takes no noise
     */
    private void mapGains(SbrFrameData data, SbrFrequencies tables, SbrHeader header, int envelope,
                          int noiseEnvelope, boolean noNoise) {
        var kx = tables.kx;
        var table = tables.table(data.resolution[envelope]);
        var bands = tables.bands(data.resolution[envelope]);
        var energies = envelopeEnergy[envelope];
        var floors = noiseFloor[noiseEnvelope];
        var sinesAllowed = envelope >= data.transientEnvelope;
        var noiseBand = 0;
        for (var i = 0; i < bands; i++) {
            var from = table[i];
            var to = table[i + 1];
            if (header.interpolateFrequency == 0) {
                double sum = 0;
                for (var k = from; k < to; k++) {
                    sum += energy[k - kx];
                }
                var average = sum / (to - from);
                for (var k = from; k < to; k++) {
                    energy[k - kx] = average;
                }
            }
            var sineInBand = false;
            for (var k = from; k < to; k++) {
                sineInBand |= harmonic[k] && (sinesAllowed || previousHarmonic[k]);
            }
            var target = energies[i];
            for (var k = from; k < to; k++) {
                while (noiseBand + 1 < tables.noiseBands && k >= tables.noise[noiseBand + 1]) {
                    noiseBand++;
                }
                var q = floors[noiseBand];
                var current = 1 + energy[k - kx];
                var index = k - kx;
                reference[index] = target;
                if (sineInBand) {
                    gain[index] = Math.sqrt(target * q / (current * (1 + q)));
                } else if (noNoise) {
                    gain[index] = Math.sqrt(target / current);
                } else {
                    gain[index] = Math.sqrt(target / (current * (1 + q)));
                }
                noiseLevel[index] = Math.sqrt(target * q / (1 + q));
                var sine = harmonic[k] && (sinesAllowed || previousHarmonic[k]);
                sineLevel[index] = sine ? Math.sqrt(target / (1 + q)) : 0;
            }
        }
    }

    /**
     * Limits the gains of each limiter band and compensates the energy the limiting removed.
     *
     * @param tables the frequency tables
     * @param limiterGain the limiter gain
     * @param noNoise whether the envelope takes no noise
     */
    private void limit(SbrFrequencies tables, double limiterGain, boolean noNoise) {
        var kx = tables.kx;
        for (var band = 0; band < tables.limiterBands; band++) {
            var from = tables.limiter[band] - kx;
            var to = tables.limiter[band + 1] - kx;
            double referenceSum = 0;
            double energySum = 0;
            for (var k = from; k < to; k++) {
                referenceSum += reference[k];
                energySum += energy[k];
            }
            var maximum = Math.min(limiterGain * Math.sqrt((EPSILON + referenceSum) / (EPSILON + energySum)), MAX_GAIN);
            double adjusted = 0;
            for (var k = from; k < to; k++) {
                if (gain[k] > maximum) {
                    noiseLevel[k] *= maximum / gain[k];
                    gain[k] = maximum;
                }
                adjusted += energy[k] * gain[k] * gain[k] + sineLevel[k] * sineLevel[k];
                if (!noNoise && sineLevel[k] == 0) {
                    adjusted += noiseLevel[k] * noiseLevel[k];
                }
            }
            var boost = Math.min(Math.sqrt((EPSILON + referenceSum) / (EPSILON + adjusted)), MAX_BOOST);
            for (var k = from; k < to; k++) {
                gain[k] *= boost;
                noiseLevel[k] *= boost;
                sineLevel[k] *= boost;
            }
        }
    }

    /**
     * Applies the gains, the noise and the sinusoids to the slots of an envelope.
     *
     * <p>The smoothing filter of a slot weighs the gains of the slot and of the four before it.
     * Ahead of the frame's first slot these are the gains of the last four slots the previous
     * frame adjusted; right after a reset they are the first envelope's own gains.
     *
     * @param kx the first high subband
     * @param m the number of high subbands
     * @param first the envelope's first output slot
     * @param last the output slot after the envelope
     * @param noNoise whether the envelope takes no noise
     * @param smooth whether the gains are smoothed over time
     * @param opening whether the envelope is the first of the frame
     */
    private void apply(int kx, int m, int first, int last, boolean noNoise, boolean smooth, boolean opening) {
        if (opening) {
            for (var h = 0; h < 4; h++) {
                if (historyValid) {
                    System.arraycopy(gainHistory[historyEnd + h], 0, gainHistory[first + h], 0, 64);
                    System.arraycopy(noiseHistory[historyEnd + h], 0, noiseHistory[first + h], 0, 64);
                } else {
                    System.arraycopy(gain, 0, gainHistory[first + h], 0, m);
                    System.arraycopy(noiseLevel, 0, noiseHistory[first + h], 0, m);
                }
            }
            historyValid = true;
        }
        var sines = 0;
        for (var k = 0; k < m; k++) {
            if (sineLevel[k] != 0) {
                sineBands[sines++] = k;
            }
        }
        final var w0 = SMOOTHING[0];
        final var w1 = SMOOTHING[1];
        final var w2 = SMOOTHING[2];
        final var w3 = SMOOTHING[3];
        final var w4 = SMOOTHING[4];
        for (var slot = first; slot < last; slot++) {
            var newest = slot + 4;
            System.arraycopy(gain, 0, gainHistory[newest], 0, m);
            System.arraycopy(noiseLevel, 0, noiseHistory[newest], 0, m);
            if (smooth) {
                var g0 = gainHistory[newest];
                var g1 = gainHistory[newest - 1];
                var g2 = gainHistory[newest - 2];
                var g3 = gainHistory[newest - 3];
                var g4 = gainHistory[newest - 4];
                var q0 = noiseHistory[newest];
                var q1 = noiseHistory[newest - 1];
                var q2 = noiseHistory[newest - 2];
                var q3 = noiseHistory[newest - 3];
                var q4 = noiseHistory[newest - 4];
                for (var k = 0; k < m; k++) {
                    var g = 0.0;
                    g += g0[k] * w0;
                    g += g1[k] * w1;
                    g += g2[k] * w2;
                    g += g3[k] * w3;
                    g += g4[k] * w4;
                    slotGain[k] = (float) g;
                    var q = 0.0;
                    q += q0[k] * w0;
                    q += q1[k] * w1;
                    q += q2[k] * w2;
                    q += q3[k] * w3;
                    q += q4[k] * w4;
                    slotNoise[k] = (float) q;
                }
            } else {
                for (var k = 0; k < m; k++) {
                    slotGain[k] = (float) gain[k];
                    slotNoise[k] = (float) noiseLevel[k];
                }
            }
            if (noNoise) {
                Arrays.fill(slotNoise, 0, m, 0f);
            } else {
                for (var i = 0; i < sines; i++) {
                    slotNoise[sineBands[i]] = 0f;
                }
            }
            var row = slot * 64 + kx;
            kernel.applyGains(highRe, highIm, row, slotGain, slotNoise, NOISE_REAL, NOISE_IMAGINARY, noiseIndex + 1, m);
            for (var i = 0; i < sines; i++) {
                var k = sineBands[i];
                var level = (float) sineLevel[k];
                var odd = ((kx + k) & 1) != 0;
                switch (sineIndex) {
                    case 0 -> highRe[row + k] += level;
                    case 1 -> highIm[row + k] += odd ? -level : level;
                    case 2 -> highRe[row + k] -= level;
                    default -> highIm[row + k] += odd ? level : -level;
                }
            }
            noiseIndex = (noiseIndex + m) & 511;
            sineIndex = (sineIndex + 1) & 3;
        }
    }

    /**
     * Writes the subband matrix of the current frame for the synthesis bank.
     *
     * @param active whether the high band of this frame was generated
     * @param kx the first high subband of this frame, when active
     * @param re the destination real parts, 64 subbands per slot
     * @param im the destination imaginary parts
     * @param lookahead the number of extra slots after the frame whose five lowest subbands are
     * written too, for the parametric stereo hybrid filterbank
     */
    void assemble(boolean active, int kx, float[] re, float[] im, int lookahead) {
        var current = active ? kx : 32;
        var leading = Math.min(carrySlots, slots);
        if (re == highRe && im == highIm && lookahead == 0) {
            assembleInPlace(current, leading);
            return;
        }
        for (var slot = 0; slot < slots; slot++) {
            var row = slot * 64;
            var lowRow = (slot + ADJUST) * 32;
            if (slot < leading) {
                System.arraycopy(lowRe, lowRow, re, row, previousKx);
                System.arraycopy(lowIm, lowRow, im, row, previousKx);
                System.arraycopy(carryRe, row + previousKx, re, row + previousKx, previousEnd - previousKx);
                System.arraycopy(carryIm, row + previousKx, im, row + previousKx, previousEnd - previousKx);
                Arrays.fill(re, row + previousEnd, row + 64, 0);
                Arrays.fill(im, row + previousEnd, row + 64, 0);
            } else {
                System.arraycopy(lowRe, lowRow, re, row, current);
                System.arraycopy(lowIm, lowRow, im, row, current);
                System.arraycopy(highRe, row + current, re, row + current, 64 - current);
                System.arraycopy(highIm, row + current, im, row + current, 64 - current);
            }
        }
        for (var slot = slots; slot < slots + lookahead; slot++) {
            var row = slot * 64;
            var lowRow = (slot + ADJUST) * 32;
            Arrays.fill(re, row, row + 64, 0);
            Arrays.fill(im, row, row + 64, 0);
            System.arraycopy(lowRe, lowRow, re, row, 5);
            System.arraycopy(lowIm, lowRow, im, row, 5);
        }
    }

    /**
     * Writes the subband matrix of the current frame over the high band buffer, which already
     * holds the high band of every slot past the carried ones and zeros above it.
     *
     * @param current the first high subband of this frame, or 32 when it has none
     * @param leading the number of leading slots taken from the carry
     */
    private void assembleInPlace(int current, int leading) {
        for (var slot = 0; slot < leading; slot++) {
            var row = slot * 64;
            var lowRow = (slot + ADJUST) * 32;
            System.arraycopy(lowRe, lowRow, highRe, row, previousKx);
            System.arraycopy(lowIm, lowRow, highIm, row, previousKx);
            System.arraycopy(carryRe, row + previousKx, highRe, row + previousKx, previousEnd - previousKx);
            System.arraycopy(carryIm, row + previousKx, highIm, row + previousKx, previousEnd - previousKx);
            Arrays.fill(highRe, row + previousEnd, row + 64, 0);
            Arrays.fill(highIm, row + previousEnd, row + 64, 0);
        }
        for (var slot = leading; slot < slots; slot++) {
            var lowRow = (slot + ADJUST) * 32;
            System.arraycopy(lowRe, lowRow, highRe, slot * 64, current);
            System.arraycopy(lowIm, lowRow, highIm, slot * 64, current);
        }
    }

    /**
     * Keeps the state that reaches into the next frame: the low band history, the carry of the
     * high band and the crossover.
     *
     * @param active whether the high band of this frame was generated
     * @param kx the first high subband of this frame, when active
     * @param end the subband after the high band of this frame, when active
     */
    void finish(boolean active, int kx, int end) {
        System.arraycopy(lowRe, slots * 32, lowRe, 0, lowLead * 32);
        System.arraycopy(lowIm, slots * 32, lowIm, 0, lowLead * 32);
        System.arraycopy(highRe, slots * 64, carryRe, 0, HIGH_LEAD * 64);
        System.arraycopy(highIm, slots * 64, carryIm, 0, HIGH_LEAD * 64);
        Arrays.fill(highRe, 0);
        Arrays.fill(highIm, 0);
        carrySlots = active ? Math.max(0, lastSlot - slots) : 0;
        previousKx = active ? kx : 32;
        previousEnd = active ? end : 32;
        if (!active) {
            transientAtStart = false;
        }
    }
}
