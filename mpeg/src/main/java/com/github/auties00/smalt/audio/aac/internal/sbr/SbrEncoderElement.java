/* Ports libxaac (encoder/ixheaace_sbr_ton_corr_hp.c, ixheaace_sbr_inv_filtering_estimation.c,
   ixheaace_sbr_noise_floor_est.c, ixheaace_sbr_tran_det.c, ixheaace_sbr_env_est.c and the detector tables of
   ixheaace_sbr_rom.c). Copyright (C) 2023 The Android Open Source Project, Ittiam Systems; Apache License 2.0.
   Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.sbr;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;

import java.util.Arrays;

/**
 * Encodes the spectral band replication data of one single channel or channel pair element: the extension payload
 * ({@code EXT_SBR_DATA}) that follows the element in a fill element.
 *
 * <p>Each frame reads the QMF columns of its channels aligned with the decoder's output, detects transients,
 * builds the time grid, estimates the tonality of the original high band and of the low band the decoder patches
 * into it, derives the inverse filtering levels, the noise floors and the bands needing a synthetic sinusoid,
 * quantizes the envelopes and codes everything with the cheaper of time and frequency differences. A channel pair
 * codes its envelopes either per channel or as a level and a balance, whichever is cheaper, when its tuning allows
 * coupling. The SBR header is repeated periodically. Instances are not thread-safe.
 *
 * @implNote This implementation follows the encoder of libxaac for the tonality estimation (second order complex
 * prediction gains over half frames), the inverse filtering decision (smoothed tonalities mapped through region
 * tables with hysteresis), the noise floor estimation and the frame splitting test; its transient detection, time
 * grid and sinusoid detection are simpler designs on the same measures.
 */
final class SbrEncoderElement {
    /**
     * The extension payload type of spectral band replication data without CRC.
     */
    static final int EXT_SBR_DATA = 13;

    /**
     * The {@code bs_extension_id} of parametric stereo data.
     */
    private static final int EXTENSION_PS = 2;

    /**
     * The most bits a payload may take: a fill element carries at most 269 bytes of {@code extension_payload},
     * its {@code extension_type} included.
     */
    static final int MAX_PAYLOAD_BITS = 8 * 269;

    /**
     * The guard added to logarithm arguments.
     */
    private static final double EPS = 1e-18;

    /**
     * The smoothing filter of the inverse filtering detector, oldest frame first.
     */
    private static final double[] INVF_SMOOTHING = {0.125, 0.375, 0.5};

    /**
     * The smoothing filter of the noise floor levels, oldest frame first.
     */
    private static final double[] NOISE_SMOOTHING = {0.05857864376269, 0.2, 0.34142135623731, 0.4};

    /**
     * The borders of the tonality regions of the patched low band, in decibels.
     */
    private static final double[] SBR_REGIONS = {1, 10, 14, 19};

    /**
     * The borders of the tonality regions of the original high band, in decibels.
     */
    private static final double[] ORIGINAL_REGIONS = {0, 3, 7, 10};

    /**
     * The borders of the energy regions, in units of 1.5 times the base-2 logarithm of the energy.
     */
    private static final double[] ENERGY_REGIONS = {25, 30, 35, 40};

    /**
     * The inverse filtering level of each patched and original tonality region, for music.
     */
    private static final int[][] REGION_LEVELS = {
            {2, 1, 0, 0, 0}, {2, 1, 0, 0, 0}, {3, 2, 1, 0, 0}, {3, 3, 2, 0, 0}, {3, 3, 2, 0, 0}
    };

    /**
     * The inverse filtering level of each region in a frame holding a transient, for music.
     */
    private static final int[][] TRANSIENT_REGION_LEVELS = {
            {1, 1, 1, 0, 0}, {1, 1, 1, 0, 0}, {3, 2, 2, 0, 0}, {3, 3, 2, 0, 0}, {3, 3, 2, 0, 0}
    };

    /**
     * The inverse filtering level of each region, for speech.
     */
    private static final int[][] SPEECH_REGION_LEVELS = {
            {2, 2, 1, 0, 0}, {2, 2, 1, 0, 0}, {3, 2, 2, 0, 0}, {3, 3, 2, 0, 0}, {3, 3, 2, 0, 0}
    };

    /**
     * The change of the inverse filtering level in each energy region.
     */
    private static final int[] ENERGY_COMPENSATION = {-4, -3, -2, -1, 0};

    /**
     * The balance values of a channel pair's coupled envelope at 1.5 and 3 dB, away from the center.
     */
    private static final int[][] PAN_STEPS = {{0, 2, 4, 6, 8, 12, 16, 20, 24}, {0, 2, 4, 8, 12}};

    /**
     * The smallest per-subband energy threshold of the transient detector.
     */
    private static final float TRANSIENT_FLOOR = 128000;

    /**
     * The detection function value above which a slot holds a transient.
     */
    private static final float TRANSIENT_THRESHOLD = 13000f / 64;

    /**
     * The number of time slots per frame, 16 or 15.
     */
    private final int timeSlots;

    /**
     * The QMF columns per frame, twice {@link #timeSlots}.
     */
    private final int columnsPerFrame;

    /**
     * The header sent periodically.
     */
    private final SbrHeader header = new SbrHeader();

    /**
     * The frequency band tables the header defines.
     */
    private final SbrFrequencies tables = new SbrFrequencies();

    /**
     * The low subband the decoder patches into each high subband, or -1.
     */
    private final int[] sourceBand = new int[64];

    /**
     * The noise floor band borders.
     */
    private final int[] noiseTable;

    /**
     * The QMF columns of each channel.
     */
    private final SbrColumns[] input;

    /**
     * The channels' encoding state.
     */
    private final Channel[] channels;

    /**
     * The parametric stereo encoder whose data a single channel element carries, or {@code null}.
     */
    private final PsEncoder parametricStereo;

    /**
     * The stereo coding of a pair, one of the {@link SbrTuning} modes.
     */
    private final int stereoMode;

    /**
     * The largest noise floor the encoder sends, as a linear noise-to-signal ratio.
     */
    private final double maxNoise;

    /**
     * Whether every envelope uses the low frequency resolution.
     */
    private final boolean lowResolution;

    /**
     * Whether the inverse filtering and noise floor use their speech tuning.
     */
    private final boolean speech;

    /**
     * The number of frames between two headers.
     */
    private final int headerPeriod;

    /**
     * The spectral change above which a frame without transient is split in two envelopes.
     */
    private final double splitThreshold;

    /**
     * The number of subbands up to the stop frequency, {@code kx + M}.
     */
    private final int usb;

    /**
     * Whether the previous frame coded the pair as level and balance.
     */
    private boolean previousCoupled;

    /**
     * The number of frames encoded.
     */
    private long frames;

    /**
     * The payload writer.
     */
    private final MsbBitWriter writer = new MsbBitWriter(512);

    /**
     * Scratch writer for parametric stereo data.
     */
    private final MsbBitWriter scratch = new MsbBitWriter(256);

    /**
     * The number of bits of the parametric stereo extension in {@link #scratch}, before its alignment.
     */
    private int parametricStereoBits;

    /**
     * The payload size above which a frame is coded with flat envelopes, {@link #MAX_PAYLOAD_BITS} unless a test
     * lowers it.
     */
    private int payloadLimit = MAX_PAYLOAD_BITS;

    /**
     * The level values of a coupled pair, per envelope and band.
     */
    private final int[][] coupledLevel = new int[SbrFrameData.MAX_ENVELOPES][64];

    /**
     * The balance values of a coupled pair, per envelope and band.
     */
    private final int[][] coupledBalance = new int[SbrFrameData.MAX_ENVELOPES][64];

    /**
     * The noise level values of a coupled pair, per noise envelope and band.
     */
    private final int[][] coupledNoiseLevel = new int[2][SbrFrequencies.MAX_NOISE_BANDS];

    /**
     * The noise balance values of a coupled pair, per noise envelope and band.
     */
    private final int[][] coupledNoiseBalance = new int[2][SbrFrequencies.MAX_NOISE_BANDS];

    /**
     * Creates the encoder of one element.
     *
     * @param tuning the frequency range, noise resolution and stereo mode
     * @param outputRate the output sampling rate, twice the core rate
     * @param timeSlots the number of time slots per frame, 16 or 15
     * @param input the QMF columns of each channel, one or two
     * @param parametricStereo the parametric stereo encoder of a single channel element, or {@code null}
     * @param bitrate the bitrate of the element, core and extension, in bits per second
     * @param coreBitrate the part of {@code bitrate} the core codes
     * @throws IllegalArgumentException if the tuning describes invalid frequency band tables
     */
    SbrEncoderElement(SbrTuning tuning, int outputRate, int timeSlots, SbrColumns[] input,
                      PsEncoder parametricStereo, int bitrate, int coreBitrate) {
        this.timeSlots = timeSlots;
        this.columnsPerFrame = 2 * timeSlots;
        this.input = input.clone();
        this.parametricStereo = parametricStereo;
        this.stereoMode = input.length == 2 ? tuning.stereoMode() : SbrTuning.MONO;
        this.maxNoise = switch (tuning.maxNoiseLevel()) {
            case -3 -> 0.5;
            case 3 -> 2.0;
            default -> 4.0;
        };
        this.lowResolution = input.length == 1 ? bitrate <= 18000 : bitrate <= 28000;
        this.speech = bitrate <= 20000;
        header.ampResolution = 1;
        header.startFrequency = tuning.startFrequency();
        header.stopFrequency = tuning.stopFrequency();
        header.crossoverBand = 0;
        header.frequencyScale = tuning.frequencyScale();
        header.alterScale = 1;
        header.noiseBands = tuning.noiseBands();
        if (!tables.update(header, outputRate)) {
            throw new IllegalArgumentException("invalid SBR tuning " + tuning + " at " + outputRate + " Hz");
        }
        this.usb = tables.kx + tables.m;
        Arrays.fill(sourceBand, -1);
        var target = tables.kx;
        for (var p = 0; p < tables.patches; p++) {
            for (var j = 0; j < tables.patchSubbands[p]; j++) {
                sourceBand[target++] = tables.patchStart[p] + j;
            }
        }
        this.noiseTable = Arrays.copyOf(tables.noise, tables.noiseBands + 1);
        var frameSeconds = 2.0 * columnsPerFrame * 32 / outputRate;
        var duration = Math.max(frameSeconds - 0.01, 0.0001);
        var rateFactor = coreBitrate > 0 ? (double) bitrate / coreBitrate : 1;
        this.splitThreshold = 0.000075 / (duration * duration) * rateFactor;
        this.headerPeriod = Math.max(1, (int) Math.round(0.5 / frameSeconds));
        this.channels = new Channel[input.length];
        for (var c = 0; c < channels.length; c++) {
            channels[c] = new Channel(timeSlots);
        }
    }

    /**
     * Returns the first subband of the high band, {@code kx}, where the core's band ends.
     *
     * @return the crossover subband
     */
    int crossover() {
        return tables.kx;
    }

    /**
     * Returns the time grid the last frame coded for a channel.
     *
     * @param channel the channel of the element
     * @return the grid
     */
    SbrGrid grid(int channel) {
        return channels[channel].grid;
    }

    /**
     * Returns the envelope value a decoder reconstructs from the last frame.
     *
     * @param channel the channel of the element; for a coupled pair, 0 holds the level and 1 the balance
     * @param envelope the index of the envelope in the frame
     * @param band the band in the envelope's frequency resolution
     * @return the value
     */
    int codedEnvelope(int channel, int envelope, int band) {
        return channels[channel].codedEnvelope[envelope][band];
    }

    /**
     * Returns the noise floor value a decoder reconstructs from the last frame.
     *
     * @param channel the channel of the element
     * @param envelope the noise envelope
     * @param band the noise band
     * @return the value
     */
    int codedNoise(int channel, int envelope, int band) {
        return channels[channel].codedNoise[envelope][band];
    }

    /**
     * {@return whether the last frame coded the pair as level and balance}
     */
    boolean coupled() {
        return previousCoupled;
    }

    /**
     * {@return the frequency band tables of the element}
     */
    SbrFrequencies tables() {
        return tables;
    }

    /**
     * Encodes one frame and returns its extension payload.
     *
     * @param base the index of the QMF column aligned with the frame's first time slot
     * @return the payload writer, holding {@code extension_type} and {@code sbr_extension_data}
     */
    MsbBitWriter encode(long base) {
        var count = channels.length;
        for (var c = 0; c < count; c++) {
            var channel = channels[c];
            channel.measure(input[c], base);
            channel.detect(usb);
            channel.split = channel.attack < 0 && splitFrame(channel);
        }
        if (count == 2 && stereoMode == SbrTuning.SWITCHED) {
            var left = channels[0];
            var right = channels[1];
            var attack = left.attack < 0 ? right.attack : right.attack < 0 ? left.attack
                    : Math.min(left.attack, right.attack);
            var split = attack < 0 && (left.split || right.split);
            for (var channel : channels) {
                channel.attack = attack;
                channel.split = split;
            }
        }
        for (var channel : channels) {
            var attack = channel.attack >= channel.start ? channel.attack : -1;
            channel.grid.build(timeSlots, channel.start, attack, channel.split, lowResolution);
            channel.frameAmp = channel.grid.frameClass == SbrFrameData.FIXFIX && channel.grid.envelopes == 1
                    ? 0 : header.ampResolution;
            channel.tonality(usb);
            estimateInverseFiltering(channel);
            detectHarmonics(channel);
            estimateNoise(channel);
            estimateEnvelopes(channel);
        }
        var coupled = false;
        if (count == 2 && stereoMode == SbrTuning.SWITCHED && channels[0].grid.sameAs(channels[1].grid)) {
            var separate = frames > 0 && !previousCoupled;
            var independent = channels[0].code(channels[0].envelope, channels[0].noise, false, separate, tables)
                    + channels[1].code(channels[1].envelope, channels[1].noise, false, separate, tables);
            couple();
            var allowed = frames > 0 && previousCoupled;
            var levelBits = channels[0].code(coupledLevel, coupledNoiseLevel, false, allowed, tables);
            var balanceBits = channels[1].code(coupledBalance, coupledNoiseBalance, true, allowed, tables);
            coupled = levelBits + balanceBits < independent;
        }
        var headerFlag = frames % headerPeriod == 0;
        if (parametricStereo != null) {
            scratch.reset();
            scratch.write(EXTENSION_PS, 2);
            parametricStereo.write(scratch, headerFlag || frames == 0);
            parametricStereoBits = (int) scratch.bitCount();
            scratch.alignWithZeros();
        }
        write(headerFlag, coupled);
        if (writer.bitCount() > payloadLimit) {
            // a fill element cannot carry the payload: code every envelope and noise floor flat over frequency,
            // which costs about one bit per band, and the channels of a pair separately
            for (var channel : channels) {
                channel.flatten(tables);
            }
            coupled = false;
            write(headerFlag, false);
            if (writer.bitCount() > MAX_PAYLOAD_BITS) {
                throw new IllegalStateException("SBR payload of " + writer.bitCount() + " bits exceeds a fill element");
            }
        }
        channels[0].commit(false, tables);
        if (count == 2) {
            channels[1].commit(coupled, tables);
        }
        for (var channel : channels) {
            channel.start = channel.grid.end() - timeSlots;
        }
        previousCoupled = coupled;
        frames++;
        return writer;
    }

    /**
     * Sets the payload size above which a frame is coded with flat envelopes, for tests of that path.
     *
     * @param bits the size in bits, at most {@link #MAX_PAYLOAD_BITS}
     */
    void limitPayload(int bits) {
        this.payloadLimit = Math.min(bits, MAX_PAYLOAD_BITS);
    }

    /**
     * Writes the payload of the frame: {@code extension_type}, the header when due, and the element's data.
     *
     * @param headerFlag whether the frame carries the SBR header
     * @param coupled whether to code a channel pair as level and balance
     */
    private void write(boolean headerFlag, boolean coupled) {
        writer.reset();
        writer.write(EXT_SBR_DATA, 4);
        writer.write(headerFlag ? 1 : 0, 1);
        if (headerFlag) {
            writeHeader();
        }
        if (channels.length == 1) {
            writeSingle();
        } else {
            writePair(coupled);
        }
    }

    /**
     * Writes {@code sbr_header}.
     */
    private void writeHeader() {
        writer.write(header.ampResolution, 1);
        writer.write(header.startFrequency, 4);
        writer.write(header.stopFrequency, 4);
        writer.write(header.crossoverBand, 3);
        writer.write(0, 2);
        var extra1 = header.frequencyScale != 2 || header.alterScale != 1 || header.noiseBands != 2;
        writer.write(extra1 ? 1 : 0, 1);
        writer.write(0, 1);
        if (extra1) {
            writer.write(header.frequencyScale, 2);
            writer.write(header.alterScale, 1);
            writer.write(header.noiseBands, 2);
        }
    }

    /**
     * Writes {@code sbr_single_channel_element}, with the parametric stereo data of the frame when the element
     * carries it.
     */
    private void writeSingle() {
        var channel = channels[0];
        writer.write(0, 1);
        channel.grid.write(writer, timeSlots);
        channel.code(channel.envelope, channel.noise, false, true, tables);
        channel.writeDirections(writer);
        channel.writeInverseFiltering(writer, tables.noiseBands);
        channel.writeEnvelope(writer, tables, false);
        channel.writeNoise(writer, tables.noiseBands, false);
        channel.writeHarmonics(writer, tables.highBands);
        if (parametricStereo != null) {
            var bits = parametricStereoBits;
            var size = (bits + 7) >> 3;
            writer.write(1, 1);
            if (size >= 15) {
                writer.write(15, 4);
                writer.write(size - 15, 8);
            } else {
                writer.write(size, 4);
            }
            var bytes = scratch.array();
            for (var i = 0; i < bits >> 3; i++) {
                writer.write(bytes[i] & 0xFF, 8);
            }
            var rest = bits & 7;
            if (rest != 0) {
                writer.write((bytes[bits >> 3] & 0xFF) >> (8 - rest), rest);
                writer.write(0, 8 - rest);
            }
        } else {
            writer.write(0, 1);
        }
    }

    /**
     * Writes {@code sbr_channel_pair_element}.
     *
     * @param coupled whether to code the pair as level and balance
     */
    private void writePair(boolean coupled) {
        var left = channels[0];
        var right = channels[1];
        writer.write(0, 1);
        writer.write(coupled ? 1 : 0, 1);
        var allowed = frames > 0 && coupled == previousCoupled;
        if (coupled) {
            left.code(coupledLevel, coupledNoiseLevel, false, allowed, tables);
            right.code(coupledBalance, coupledNoiseBalance, true, allowed, tables);
            left.grid.write(writer, timeSlots);
            left.writeDirections(writer);
            right.writeDirections(writer);
            left.writeInverseFiltering(writer, tables.noiseBands);
            left.writeEnvelope(writer, tables, false);
            left.writeNoise(writer, tables.noiseBands, false);
            right.writeEnvelope(writer, tables, true);
            right.writeNoise(writer, tables.noiseBands, true);
        } else {
            left.code(left.envelope, left.noise, false, allowed, tables);
            right.code(right.envelope, right.noise, false, allowed, tables);
            left.grid.write(writer, timeSlots);
            right.grid.write(writer, timeSlots);
            left.writeDirections(writer);
            right.writeDirections(writer);
            left.writeInverseFiltering(writer, tables.noiseBands);
            right.writeInverseFiltering(writer, tables.noiseBands);
            left.writeEnvelope(writer, tables, false);
            right.writeEnvelope(writer, tables, false);
            left.writeNoise(writer, tables.noiseBands, false);
            right.writeNoise(writer, tables.noiseBands, false);
        }
        if (coupled) {
            System.arraycopy(left.inverseFiltering, 0, right.inverseFiltering, 0, right.inverseFiltering.length);
        }
        left.writeHarmonics(writer, tables.highBands);
        right.writeHarmonics(writer, tables.highBands);
        writer.write(0, 1);
    }

    /**
     * Returns whether the spectrum of a frame without transient changes enough between its halves to code two
     * envelopes ({@code ixheaace_frame_splitter}).
     *
     * @param channel the channel's encoding state
     * @return {@code true} to split the frame
     */
    private boolean splitFrame(Channel channel) {
        var bands = tables.highBands;
        var border = (timeSlots + 1) >> 1;
        var lowEnergy = 1.0;
        for (var t = 0; t < timeSlots; t++) {
            for (var k = 0; k < tables.kx; k++) {
                lowEnergy += channel.slotEnergy(t, k);
            }
        }
        lowEnergy *= 2;
        var highEnergy = 1.0;
        var first = channel.bandScratch;
        var second = channel.bandScratch2;
        for (var b = 0; b < bands; b++) {
            first[b] = 1.0e6 * border;
            second[b] = 1.0e6 * (timeSlots - border);
        }
        for (var t = 0; t < timeSlots; t++) {
            for (var b = 0; b < bands; b++) {
                var energy = 0.0;
                for (var k = tables.high[b]; k < tables.high[b + 1]; k++) {
                    energy += 2 * channel.slotEnergy(t, k);
                }
                if (t < border) {
                    first[b] += energy;
                } else {
                    second[b] += energy;
                }
                highEnergy += energy;
            }
        }
        var total = 0.5 * (lowEnergy + channel.previousLowEnergy) + highEnergy;
        channel.previousLowEnergy = lowEnergy;
        if (total < 1e-18) {
            return false;
        }
        var ratio = (double) border / (timeSlots - border);
        var weight = 0.5 - (double) border / timeSlots;
        weight = 1 - 4 * weight * weight;
        var sum = 0.0;
        for (var b = 0; b < bands; b++) {
            var delta = Math.abs(StrictMath.log(second[b] / first[b] * ratio));
            sum += Math.sqrt((first[b] + second[b]) / total) * delta;
        }
        return sum * weight > splitThreshold;
    }

    /**
     * Decides the inverse filtering level of each noise band ({@code ixheaace_qmf_inverse_filtering_detector}).
     *
     * @param channel the channel's encoding state
     */
    private void estimateInverseFiltering(Channel channel) {
        var levels = speech ? SPEECH_REGION_LEVELS : channel.attack >= 0 ? TRANSIENT_REGION_LEVELS : REGION_LEVELS;
        var energy = 0.5 * (channel.estimateEnergy[0] + channel.estimateEnergy[1]);
        for (var b = 0; b < tables.noiseBands; b++) {
            var original = 0.0;
            var patched = 0.0;
            var from = noiseTable[b];
            var to = noiseTable[b + 1];
            for (var k = from; k < to; k++) {
                original += 0.5 * (channel.quota[0][k] + channel.quota[1][k]);
                if (sourceBand[k] >= 0) {
                    patched += 0.5 * (channel.quota[0][sourceBand[k]] + channel.quota[1][sourceBand[k]]);
                }
            }
            original /= to - from;
            patched /= to - from;
            var originalHistory = channel.originalTonality[b];
            var patchedHistory = channel.patchedTonality[b];
            System.arraycopy(originalHistory, 1, originalHistory, 0, 2);
            System.arraycopy(patchedHistory, 1, patchedHistory, 0, 2);
            originalHistory[2] = original;
            patchedHistory[2] = patched;
            var originalFiltered = 0.0;
            var patchedFiltered = 0.0;
            for (var i = 0; i < 3; i++) {
                originalFiltered += INVF_SMOOTHING[i] * originalHistory[i];
                patchedFiltered += INVF_SMOOTHING[i] * patchedHistory[i];
            }
            var originalDb = 3 * log2(originalFiltered + EPS);
            var patchedDb = 3 * log2(patchedFiltered + EPS);
            var energyLevel = 1.5 * log2(energy + EPS);
            var regionPatched = region(patchedDb, SBR_REGIONS, channel.previousPatchedRegion[b]);
            var regionOriginal = region(originalDb, ORIGINAL_REGIONS, channel.previousOriginalRegion[b]);
            var regionEnergy = region(energyLevel, ENERGY_REGIONS, -1);
            channel.previousPatchedRegion[b] = regionPatched;
            channel.previousOriginalRegion[b] = regionOriginal;
            var level = levels[regionPatched][regionOriginal] + ENERGY_COMPENSATION[regionEnergy];
            channel.inverseFiltering[b] = Math.max(level, 0);
        }
    }

    /**
     * Returns the region of a value among increasing borders, widening the borders around the previous region by
     * one for hysteresis.
     *
     * @param value the measured value
     * @param borders the region borders
     * @param previous the previous region, or -1 for none
     * @return the region, from 0 to {@code borders.length}
     */
    private static int region(double value, double[] borders, int previous) {
        var count = borders.length;
        var region = count;
        for (var i = 0; i < count; i++) {
            var border = borders[i];
            if (previous >= 0) {
                if (i == previous) {
                    border += 1;
                } else if (i == previous - 1) {
                    border -= 1;
                }
            }
            if (value < border) {
                region = i;
                break;
            }
        }
        return region;
    }

    /**
     * Marks the high resolution bands whose original holds a dominant sinusoid the patch does not reproduce.
     *
     * @param channel the channel's encoding state
     */
    private void detectHarmonics(Channel channel) {
        channel.harmonics = false;
        for (var b = 0; b < tables.highBands; b++) {
            var from = tables.high[b];
            var to = tables.high[b + 1];
            var peak = from;
            var best = -1.0;
            var total = 0.0;
            for (var k = from; k < to; k++) {
                var quota = 0.5 * (channel.quota[0][k] + channel.quota[1][k]);
                total += channel.bandEnergy[k];
                if (quota > best) {
                    best = quota;
                    peak = k;
                }
            }
            var source = sourceBand[peak];
            var patched = source < 0 ? 0 : 0.5 * (channel.quota[0][source] + channel.quota[1][source]);
            var dominant = channel.bandEnergy[peak] >= 0.5 * total && total > 0;
            var threshold = channel.previousHarmonic[b] ? 8 : 20;
            var flag = dominant && best > threshold && best > 8 * patched;
            channel.harmonic[b] = flag;
            channel.harmonicPeak[b] = peak;
            channel.harmonics |= flag;
        }
        System.arraycopy(channel.harmonic, 0, channel.previousHarmonic, 0, tables.highBands);
    }

    /**
     * Estimates the noise floor of each noise band and noise envelope ({@code ixheaace_sbr_noise_floor_estimate_qmf}).
     *
     * @param channel the channel's encoding state
     */
    private void estimateNoise(Channel channel) {
        var grid = channel.grid;
        var weight = speech ? 1.0 : 0.25;
        for (var e = 0; e < grid.noiseEnvelopes; e++) {
            for (var b = 0; b < tables.noiseBands; b++) {
                var from = noiseTable[b];
                var to = noiseTable[b + 1];
                var meanOriginal = 0.0;
                var meanPatched = 0.0;
                for (var k = from; k < to; k++) {
                    double original;
                    double patched;
                    var source = sourceBand[k];
                    if (grid.noiseEnvelopes == 1) {
                        original = 0.5 * (channel.quota[0][k] + channel.quota[1][k]);
                        patched = source < 0 ? 0 : 0.5 * (channel.quota[0][source] + channel.quota[1][source]);
                    } else {
                        original = channel.quota[e][k];
                        patched = source < 0 ? 0 : channel.quota[e][source];
                    }
                    if (channel.harmonics) {
                        meanOriginal = Math.max(meanOriginal, original);
                        meanPatched = Math.max(meanPatched, patched);
                    } else {
                        meanOriginal += original;
                        meanPatched += patched;
                    }
                }
                if (!channel.harmonics) {
                    meanOriginal /= to - from;
                    meanPatched /= to - from;
                }
                if (meanOriginal < 0.000976562 && meanPatched < 0.000976562) {
                    meanOriginal = 101.5936673;
                    meanPatched = 101.5936673;
                }
                meanOriginal = Math.max(meanOriginal, 1);
                meanPatched = Math.max(meanPatched, 1);
                var difference = channel.harmonics ? 1 : Math.max(1, weight * meanPatched / meanOriginal);
                if (channel.previousInverseFiltering[b] < 3) {
                    difference = 1;
                }
                channel.noiseRatio[e][b] = Math.min(difference / meanOriginal, maxNoise);
            }
        }
        for (var e = 0; e < grid.noiseEnvelopes; e++) {
            var history = channel.noiseHistory;
            for (var i = 1; i < history.length; i++) {
                System.arraycopy(history[i], 0, history[i - 1], 0, tables.noiseBands);
            }
            System.arraycopy(channel.noiseRatio[e], 0, history[history.length - 1], 0, tables.noiseBands);
            for (var b = 0; b < tables.noiseBands; b++) {
                var smoothed = 0.0;
                for (var i = 0; i < history.length; i++) {
                    smoothed += NOISE_SMOOTHING[i] * history[i][b];
                }
                channel.noiseRatio[e][b] = smoothed;
                channel.noiseLevel[e][b] = 6 - log2(smoothed);
                channel.noise[e][b] = Math.clamp((int) Math.floor(channel.noiseLevel[e][b] + 0.5), 0, 30);
            }
        }
        System.arraycopy(channel.inverseFiltering, 0, channel.previousInverseFiltering, 0, tables.noiseBands);
    }

    /**
     * Measures and quantizes the envelope of every envelope and band ({@code ixheaace_calculate_sbr_envelope}).
     *
     * @param channel the channel's encoding state
     */
    private void estimateEnvelopes(Channel channel) {
        var grid = channel.grid;
        var factor = channel.frameAmp == 0 ? 2 : 1;
        for (var l = 0; l < grid.envelopes; l++) {
            var start = grid.borders[l];
            var stop = grid.borders[l + 1];
            if (l == grid.transientEnvelope && stop - start > 1) {
                stop--;
            }
            var resolution = grid.resolution[l];
            var bands = tables.bands(resolution);
            var table = resolution == 1 ? tables.high : tables.low;
            for (var b = 0; b < bands; b++) {
                var low = table[b];
                var high = table[b + 1];
                if (b == 0 && (resolution == 1 ? high - low > 1 : high - low > 2)) {
                    low++;
                }
                double energy;
                if (harmonicIn(channel, resolution, b)) {
                    energy = 0;
                    for (var k = low; k < high; k++) {
                        var sum = 0.0;
                        for (var t = start; t < stop; t++) {
                            sum += channel.slotEnergy(t, k);
                        }
                        energy = Math.max(energy, sum);
                    }
                    energy /= stop - start;
                    var width = high - low;
                    energy *= width > 2 ? 0.398107267 : width > 1 ? 0.5 : 1;
                } else {
                    energy = 0;
                    for (var k = low; k < high; k++) {
                        for (var t = start; t < stop; t++) {
                            energy += channel.slotEnergy(t, k);
                        }
                    }
                    energy /= (double) (stop - start) * (high - low);
                }
                channel.energy[l][b] = energy;
                channel.envelope[l][b] = Math.clamp((int) Math.floor(factor * Math.max(0, log2(energy / 64 + EPS))
                        + 0.5), 0, factor == 2 ? 127 : 63);
            }
        }
    }

    /**
     * Returns whether a band of an envelope holds a band marked for a synthetic sinusoid.
     *
     * @param channel the channel's encoding state
     * @param resolution the envelope's frequency resolution
     * @param band the band in that resolution
     * @return {@code true} when the band holds such a band
     */
    private boolean harmonicIn(Channel channel, int resolution, int band) {
        if (!channel.harmonics) {
            return false;
        }
        if (resolution == 1) {
            return channel.harmonic[band];
        }
        var from = tables.low[band];
        var to = tables.low[band + 1];
        for (var b = 0; b < tables.highBands; b++) {
            if (tables.high[b] >= from && tables.high[b] < to && channel.harmonic[b]) {
                return true;
            }
        }
        return false;
    }

    /**
     * Converts the envelopes and noise floors of a channel pair into level and balance values.
     */
    private void couple() {
        var left = channels[0];
        var right = channels[1];
        var grid = left.grid;
        var amp = left.frameAmp;
        var factor = amp == 0 ? 2 : 1;
        var steps = PAN_STEPS[amp];
        var center = steps[steps.length - 1];
        for (var l = 0; l < grid.envelopes; l++) {
            var bands = tables.bands(grid.resolution[l]);
            for (var b = 0; b < bands; b++) {
                var el = left.energy[l][b];
                var er = right.energy[l][b];
                var level = (el + er) / 2;
                coupledLevel[l][b] = Math.clamp((int) Math.floor(factor * Math.max(0, log2(level / 64 + EPS)) + 0.5),
                        0, factor == 2 ? 127 : 63);
                var ratio = factor * log2((el * 2 + 1) / (er * 2 + 1));
                coupledBalance[l][b] = center + pan(ratio, steps);
            }
        }
        var noiseSteps = PAN_STEPS[1];
        for (var e = 0; e < grid.noiseEnvelopes; e++) {
            for (var b = 0; b < tables.noiseBands; b++) {
                var ql = left.noiseRatio[e][b];
                var qr = right.noiseRatio[e][b];
                coupledNoiseLevel[e][b] = Math.clamp((int) Math.floor(6 - log2((ql + qr) / 2) + 0.5), 0, 30);
                var difference = Math.clamp(right.noiseLevel[e][b] - left.noiseLevel[e][b], -30, 30);
                coupledNoiseBalance[e][b] = 12 + pan(difference, noiseSteps);
            }
        }
    }

    /**
     * Quantizes a balance to the nearest allowed step ({@code ixheaace_map_panorama}).
     *
     * @param value the balance, positive toward the first channel
     * @param steps the allowed magnitudes
     * @return the signed step
     */
    private static int pan(double value, int[] steps) {
        var magnitude = Math.abs(value);
        var best = 0;
        var error = Double.MAX_VALUE;
        for (var step : steps) {
            var e = Math.abs(magnitude - step);
            if (e < error) {
                error = e;
                best = step;
            }
        }
        return value < 0 ? -best : best;
    }

    /**
     * Returns the base-2 logarithm.
     *
     * @param value a positive value
     * @return its logarithm
     */
    private static double log2(double value) {
        return StrictMath.log(value) / StrictMath.log(2);
    }

    /**
     * The encoding state and the frame data of one channel.
     */
    private static final class Channel {
        /**
         * The first slot of the per-slot buffers, relative to the frame's first slot.
         */
        private static final int FIRST_SLOT = -2;

        /**
         * The number of time slots per frame.
         */
        private final int timeSlots;

        /**
         * The energy of each subband in each slot from {@link #FIRST_SLOT} to four slots past the frame, the mean
         * of the slot's two QMF columns.
         */
        private final float[] slots;

        /**
         * The number of slots in {@link #slots}.
         */
        private final int slotCount;

        /**
         * The energy of each subband over the frame.
         */
        final double[] bandEnergy = new double[64];

        /**
         * The per-subband thresholds of the transient detector.
         */
        private final float[] thresholds = new float[64];

        /**
         * The transient detection function of each slot of the frame.
         */
        private final float[] detection;

        /**
         * The slot of the frame's transient, or -1.
         */
        int attack = -1;

        /**
         * Whether the frame is split in two envelopes.
         */
        boolean split;

        /**
         * The frame's first slot, where the previous frame ended.
         */
        int start;

        /**
         * The low band energy of the previous frame, for the splitting test.
         */
        double previousLowEnergy;

        /**
         * Scratch band energies of the splitting test.
         */
        final double[] bandScratch = new double[64];

        /**
         * Scratch band energies of the splitting test.
         */
        final double[] bandScratch2 = new double[64];

        /**
         * The time grid of the frame.
         */
        final SbrGrid grid = new SbrGrid();

        /**
         * The amplitude resolution of the frame: 0 for 1.5 dB, 1 for 3 dB.
         */
        int frameAmp;

        /**
         * The tonality (predictable to unpredictable energy ratio) of each subband in each half frame.
         */
        final double[][] quota = new double[2][64];

        /**
         * The energy of every subband up to the stop frequency in each half frame.
         */
        final double[] estimateEnergy = new double[2];

        /**
         * The original tonality of each noise band in the last three frames.
         */
        final double[][] originalTonality = new double[SbrFrequencies.MAX_NOISE_BANDS][3];

        /**
         * The patched tonality of each noise band in the last three frames.
         */
        final double[][] patchedTonality = new double[SbrFrequencies.MAX_NOISE_BANDS][3];

        /**
         * The patched tonality region of each noise band in the previous frame.
         */
        final int[] previousPatchedRegion = new int[SbrFrequencies.MAX_NOISE_BANDS];

        /**
         * The original tonality region of each noise band in the previous frame.
         */
        final int[] previousOriginalRegion = new int[SbrFrequencies.MAX_NOISE_BANDS];

        /**
         * The inverse filtering level of each noise band.
         */
        final int[] inverseFiltering = new int[SbrFrequencies.MAX_NOISE_BANDS];

        /**
         * The inverse filtering levels of the previous frame.
         */
        final int[] previousInverseFiltering = new int[SbrFrequencies.MAX_NOISE_BANDS];

        /**
         * The unsmoothed and then smoothed noise-to-signal ratio of each noise envelope and band.
         */
        final double[][] noiseRatio = new double[2][SbrFrequencies.MAX_NOISE_BANDS];

        /**
         * The noise floor level of each noise envelope and band, {@code 6 - log2(ratio)}.
         */
        final double[][] noiseLevel = new double[2][SbrFrequencies.MAX_NOISE_BANDS];

        /**
         * The last four noise ratios of each band, oldest first.
         */
        final double[][] noiseHistory = new double[4][SbrFrequencies.MAX_NOISE_BANDS];

        /**
         * The quantized noise floors of each noise envelope and band.
         */
        final int[][] noise = new int[2][SbrFrequencies.MAX_NOISE_BANDS];

        /**
         * Whether any band gets a synthetic sinusoid.
         */
        boolean harmonics;

        /**
         * Whether each high resolution band gets a synthetic sinusoid.
         */
        final boolean[] harmonic = new boolean[64];

        /**
         * The flags of the previous frame.
         */
        final boolean[] previousHarmonic = new boolean[64];

        /**
         * The subband of the strongest tonality in each high resolution band.
         */
        final int[] harmonicPeak = new int[64];

        /**
         * The mean energy per QMF sample of each envelope and band.
         */
        final double[][] energy = new double[SbrFrameData.MAX_ENVELOPES][64];

        /**
         * The quantized envelope of each envelope and band.
         */
        final int[][] envelope = new int[SbrFrameData.MAX_ENVELOPES][64];

        /**
         * The values the decoder reconstructs from the coded envelope, per envelope and band.
         */
        final int[][] codedEnvelope = new int[SbrFrameData.MAX_ENVELOPES][64];

        /**
         * The values the decoder reconstructs from the coded noise floors.
         */
        final int[][] codedNoise = new int[2][SbrFrequencies.MAX_NOISE_BANDS];

        /**
         * Whether each envelope is coded in time.
         */
        private final boolean[] envelopeTime = new boolean[SbrFrameData.MAX_ENVELOPES];

        /**
         * Whether each noise envelope is coded in time.
         */
        private final boolean[] noiseTime = new boolean[2];

        /**
         * The last envelope the decoder holds from the previous frame.
         */
        private final int[] previousEnvelope = new int[64];

        /**
         * The frequency resolution of {@link #previousEnvelope}.
         */
        private int previousResolution;

        /**
         * The amplitude resolution of the previous frame.
         */
        private int previousAmp = -1;

        /**
         * The last noise floors the decoder holds from the previous frame.
         */
        private final int[] previousNoise = new int[SbrFrequencies.MAX_NOISE_BANDS];

        /**
         * Whether the decoder holds valid previous values for time differences.
         */
        private boolean previousValid;

        /**
         * Creates the state of a channel.
         *
         * @param timeSlots the number of time slots per frame
         */
        Channel(int timeSlots) {
            this.timeSlots = timeSlots;
            this.slotCount = timeSlots + 4 - FIRST_SLOT;
            this.slots = new float[slotCount * 64];
            this.detection = new float[timeSlots + 2];
        }

        /**
         * Returns the energy of a subband in a slot.
         *
         * @param slot the slot relative to the frame's first, from -2 to {@code timeSlots + 3}
         * @param band the subband
         * @return the mean energy of the slot's two QMF columns
         */
        float slotEnergy(int slot, int band) {
            return slots[(slot - FIRST_SLOT) * 64 + band];
        }

        /**
         * Computes the slot energies of the frame and the frame's per-subband energy.
         *
         * @param columns the channel's QMF columns
         * @param base the column aligned with the frame's first slot
         */
        void measure(SbrColumns columns, long base) {
            for (var s = 0; s < slotCount; s++) {
                var column = base + 2L * (s + FIRST_SLOT);
                for (var k = 0; k < 64; k++) {
                    slots[s * 64 + k] = 0.5f * (columns.energy(column, k) + columns.energy(column + 1, k));
                }
            }
            Arrays.fill(bandEnergy, 0);
            for (var t = 0; t < timeSlots; t++) {
                for (var k = 0; k < 64; k++) {
                    bandEnergy[k] += slotEnergy(t, k);
                }
            }
            this.columns = columns;
            this.base = base;
        }

        /**
         * The columns of the frame being encoded.
         */
        private SbrColumns columns;

        /**
         * The column aligned with the frame's first slot.
         */
        private long base;

        /**
         * Detects the first transient of the frame from the rise of the subband energies over adaptive
         * thresholds.
         *
         * @param usb the number of subbands up to the stop frequency
         */
        void detect(int usb) {
            for (var k = 0; k < usb; k++) {
                var mean = 0.0;
                for (var t = 0; t < timeSlots; t++) {
                    mean += slotEnergy(t, k);
                }
                mean /= timeSlots;
                var variance = 0.0;
                for (var t = 0; t < timeSlots; t++) {
                    var d = slotEnergy(t, k) - mean;
                    variance += d * d;
                }
                var deviation = (float) Math.sqrt(variance / (timeSlots - 1));
                thresholds[k] = Math.max(0.66f * thresholds[k] + 0.34f * deviation, TRANSIENT_FLOOR);
            }
            for (var t = 0; t < timeSlots + 2; t++) {
                var sum = 0.0f;
                for (var k = 0; k < usb; k++) {
                    var threshold = thresholds[k];
                    var before = Math.max(slotEnergy(t - 1, k), slotEnergy(t - 2, k));
                    var rise = slotEnergy(t, k) - before;
                    var riseNext = slotEnergy(t + 1, k) - before;
                    if (rise > threshold) {
                        sum += rise / threshold - 1;
                    }
                    if (riseNext > threshold) {
                        sum += riseNext / threshold - 1;
                    }
                }
                detection[t] = sum;
            }
            attack = -1;
            for (var t = 0; t < timeSlots; t++) {
                if (detection[t] > TRANSIENT_THRESHOLD && detection[t] >= detection[t + 1]) {
                    attack = t;
                    break;
                }
            }
        }

        /**
         * Computes the tonality of every subband up to the stop frequency in each half frame
         * ({@code ixheaace_calculate_tonality_quotas}).
         *
         * @param usb the number of subbands up to the stop frequency
         */
        void tonality(int usb) {
            var length = timeSlots;
            var re = columns.re();
            var im = columns.im();
            for (var e = 0; e < 2; e++) {
                var first = base + (long) e * length;
                var energySum = 0.0;
                for (var k = 0; k < usb; k++) {
                    double r00 = 0;
                    double r11 = 0;
                    double r22 = 0;
                    double r01r = 0;
                    double r01i = 0;
                    double r02r = 0;
                    double r02i = 0;
                    double r12r = 0;
                    double r12i = 0;
                    for (var j = 0; j < length; j++) {
                        var c0 = SbrColumns.offset(first + j) + k;
                        var c1 = SbrColumns.offset(first + j - 1) + k;
                        var c2 = SbrColumns.offset(first + j - 2) + k;
                        double x0r = re[c0];
                        double x0i = im[c0];
                        double x1r = re[c1];
                        double x1i = im[c1];
                        double x2r = re[c2];
                        double x2i = im[c2];
                        r00 += x0r * x0r + x0i * x0i;
                        r11 += x1r * x1r + x1i * x1i;
                        r22 += x2r * x2r + x2i * x2i;
                        r01r += x0r * x1r + x0i * x1i;
                        r01i += x0i * x1r - x0r * x1i;
                        r02r += x0r * x2r + x0i * x2i;
                        r02i += x0i * x2r - x0r * x2i;
                        r12r += x1r * x2r + x1i * x2i;
                        r12i += x1i * x2r - x1r * x2i;
                    }
                    var det = r11 * r22 - (r12r * r12r + r12i * r12i) / (1 + 1e-6);
                    double a1r;
                    double a1i;
                    if (det == 0) {
                        a1r = 0;
                        a1i = 0;
                    } else {
                        a1r = (r01r * r12r - r01i * r12i - r02r * r11) / det;
                        a1i = (r01i * r12r + r01r * r12i - r02i * r11) / det;
                    }
                    double a0r;
                    double a0i;
                    if (r11 == 0) {
                        a0r = 0;
                        a0i = 0;
                    } else {
                        a0r = -(r01r + a1r * r12r + a1i * r12i) / r11;
                        a0i = -(r01i + a1i * r12r - a1r * r12i) / r11;
                    }
                    double value = 0;
                    if (r00 > 0) {
                        var predicted = -(a0r * r01r + a0i * r01i + a1r * r02r + a1i * r02i) / r00;
                        value = predicted >= 1 ? 1e6 : Math.max(0, predicted / (1 - predicted));
                    }
                    quota[e][k] = value;
                    energySum += r00;
                }
                for (var k = usb; k < 64; k++) {
                    quota[e][k] = 0;
                }
                estimateEnergy[e] = energySum;
            }
        }

        /**
         * Chooses time or frequency differences for every envelope and noise envelope of values and records the
         * values the decoder reconstructs.
         *
         * @param values the envelope values per envelope and band
         * @param noiseValues the noise values per noise envelope and band
         * @param balance whether the values are balance values, coded at half resolution
         * @param timeAllowed whether the first envelope may refer to the previous frame
         * @param tables the frequency tables
         * @return the number of bits of the envelope and noise data and their direction flags
         */
        int code(int[][] values, int[][] noiseValues, boolean balance, boolean timeAllowed, SbrFrequencies tables) {
            var allowed = timeAllowed && previousValid && previousAmp == frameAmp;
            var bits = 0;
            var shift = balance ? 1 : 0;
            var amp = frameAmp;
            var time = amp == 0 ? (balance ? SbrHuffmanCodes.BALANCE_15_TIME : SbrHuffmanCodes.ENVELOPE_15_TIME)
                    : (balance ? SbrHuffmanCodes.BALANCE_30_TIME : SbrHuffmanCodes.ENVELOPE_30_TIME);
            var frequency = amp == 0
                    ? (balance ? SbrHuffmanCodes.BALANCE_15_FREQUENCY : SbrHuffmanCodes.ENVELOPE_15_FREQUENCY)
                    : (balance ? SbrHuffmanCodes.BALANCE_30_FREQUENCY : SbrHuffmanCodes.ENVELOPE_30_FREQUENCY);
            var startBits = (amp == 0 ? 7 : 6) - shift;
            for (var l = 0; l < grid.envelopes; l++) {
                var resolution = grid.resolution[l];
                var bands = tables.bands(resolution);
                var source = values[l];
                var target = codedEnvelope[l];
                var reference = l == 0 ? previousEnvelope : codedEnvelope[l - 1];
                var referenceResolution = l == 0 ? previousResolution : grid.resolution[l - 1];
                var timeBits = Integer.MAX_VALUE;
                if (l > 0 || allowed) {
                    timeBits = 0;
                    for (var b = 0; b < bands; b++) {
                        var delta = (source[b] - reference[mapBand(tables, b, resolution, referenceResolution)])
                                >> shift;
                        if (delta < time.min() || delta > time.max()) {
                            timeBits = Integer.MAX_VALUE;
                            break;
                        }
                        timeBits += time.length(delta);
                    }
                }
                var frequencyBits = startBits;
                var previous = source[0] >> shift;
                for (var b = 1; b < bands; b++) {
                    var symbol = source[b] >> shift;
                    var delta = Math.clamp(symbol - previous, frequency.min(), frequency.max());
                    frequencyBits += frequency.length(delta);
                    previous += delta;
                }
                var useTime = timeBits <= frequencyBits;
                envelopeTime[l] = useTime;
                if (useTime) {
                    bits += timeBits;
                    for (var b = 0; b < bands; b++) {
                        target[b] = source[b];
                    }
                } else {
                    bits += frequencyBits;
                    var symbol = Math.min(source[0] >> shift, (1 << startBits) - 1);
                    target[0] = symbol << shift;
                    for (var b = 1; b < bands; b++) {
                        var delta = Math.clamp((source[b] >> shift) - symbol, frequency.min(), frequency.max());
                        symbol += delta;
                        target[b] = symbol << shift;
                    }
                }
            }
            var noiseTimeBook = balance ? SbrHuffmanCodes.NOISE_BALANCE_TIME : SbrHuffmanCodes.NOISE_TIME;
            var noiseFrequencyBook = balance ? SbrHuffmanCodes.BALANCE_30_FREQUENCY
                    : SbrHuffmanCodes.ENVELOPE_30_FREQUENCY;
            for (var e = 0; e < grid.noiseEnvelopes; e++) {
                var source = noiseValues[e];
                var target = codedNoise[e];
                var reference = e == 0 ? previousNoise : codedNoise[e - 1];
                var timeBits = Integer.MAX_VALUE;
                if (e > 0 || allowed) {
                    timeBits = 0;
                    for (var b = 0; b < tables.noiseBands; b++) {
                        var delta = (source[b] - reference[b]) >> shift;
                        if (delta < noiseTimeBook.min() || delta > noiseTimeBook.max()) {
                            timeBits = Integer.MAX_VALUE;
                            break;
                        }
                        timeBits += noiseTimeBook.length(delta);
                    }
                }
                var frequencyBits = 5;
                var previous = source[0] >> shift;
                for (var b = 1; b < tables.noiseBands; b++) {
                    var symbol = source[b] >> shift;
                    var delta = Math.clamp(symbol - previous, noiseFrequencyBook.min(), noiseFrequencyBook.max());
                    frequencyBits += noiseFrequencyBook.length(delta);
                    previous += delta;
                }
                var useTime = timeBits <= frequencyBits;
                noiseTime[e] = useTime;
                if (useTime) {
                    bits += timeBits;
                    System.arraycopy(source, 0, target, 0, tables.noiseBands);
                } else {
                    bits += frequencyBits;
                    var symbol = Math.min(source[0] >> shift, 31);
                    target[0] = symbol << shift;
                    for (var b = 1; b < tables.noiseBands; b++) {
                        var delta = Math.clamp((source[b] >> shift) - symbol, noiseFrequencyBook.min(),
                                noiseFrequencyBook.max());
                        symbol += delta;
                        target[b] = symbol << shift;
                    }
                }
            }
            return bits + grid.envelopes + grid.noiseEnvelopes;
        }

        /**
         * Writes {@code sbr_dtdf}.
         *
         * @param writer the destination
         */
        void writeDirections(MsbBitWriter writer) {
            for (var l = 0; l < grid.envelopes; l++) {
                writer.write(envelopeTime[l] ? 1 : 0, 1);
            }
            for (var e = 0; e < grid.noiseEnvelopes; e++) {
                writer.write(noiseTime[e] ? 1 : 0, 1);
            }
        }

        /**
         * Writes {@code sbr_invf}.
         *
         * @param writer the destination
         * @param bands the number of noise bands
         */
        void writeInverseFiltering(MsbBitWriter writer, int bands) {
            for (var b = 0; b < bands; b++) {
                writer.write(inverseFiltering[b], 2);
            }
        }

        /**
         * Writes {@code sbr_envelope} from the values recorded by the last {@link #code}.
         *
         * @param writer the destination
         * @param tables the frequency tables
         * @param balance whether the values are balance values
         */
        void writeEnvelope(MsbBitWriter writer, SbrFrequencies tables, boolean balance) {
            var shift = balance ? 1 : 0;
            var amp = frameAmp;
            var time = amp == 0 ? (balance ? SbrHuffmanCodes.BALANCE_15_TIME : SbrHuffmanCodes.ENVELOPE_15_TIME)
                    : (balance ? SbrHuffmanCodes.BALANCE_30_TIME : SbrHuffmanCodes.ENVELOPE_30_TIME);
            var frequency = amp == 0
                    ? (balance ? SbrHuffmanCodes.BALANCE_15_FREQUENCY : SbrHuffmanCodes.ENVELOPE_15_FREQUENCY)
                    : (balance ? SbrHuffmanCodes.BALANCE_30_FREQUENCY : SbrHuffmanCodes.ENVELOPE_30_FREQUENCY);
            var startBits = (amp == 0 ? 7 : 6) - shift;
            for (var l = 0; l < grid.envelopes; l++) {
                var resolution = grid.resolution[l];
                var bands = tables.bands(resolution);
                var values = codedEnvelope[l];
                if (envelopeTime[l]) {
                    var reference = l == 0 ? previousEnvelope : codedEnvelope[l - 1];
                    var referenceResolution = l == 0 ? previousResolution : grid.resolution[l - 1];
                    for (var b = 0; b < bands; b++) {
                        time.write(writer, (values[b] - reference[mapBand(tables, b, resolution,
                                referenceResolution)]) >> shift);
                    }
                } else {
                    writer.write(values[0] >> shift, startBits);
                    for (var b = 1; b < bands; b++) {
                        frequency.write(writer, (values[b] - values[b - 1]) >> shift);
                    }
                }
            }
        }

        /**
         * Writes {@code sbr_noise} from the values recorded by the last {@link #code}.
         *
         * @param writer the destination
         * @param bands the number of noise bands
         * @param balance whether the values are balance values
         */
        void writeNoise(MsbBitWriter writer, int bands, boolean balance) {
            var shift = balance ? 1 : 0;
            var time = balance ? SbrHuffmanCodes.NOISE_BALANCE_TIME : SbrHuffmanCodes.NOISE_TIME;
            var frequency = balance ? SbrHuffmanCodes.BALANCE_30_FREQUENCY : SbrHuffmanCodes.ENVELOPE_30_FREQUENCY;
            for (var e = 0; e < grid.noiseEnvelopes; e++) {
                var values = codedNoise[e];
                if (noiseTime[e]) {
                    var reference = e == 0 ? previousNoise : codedNoise[e - 1];
                    for (var b = 0; b < bands; b++) {
                        time.write(writer, (values[b] - reference[b]) >> shift);
                    }
                } else {
                    writer.write(values[0] >> shift, 5);
                    for (var b = 1; b < bands; b++) {
                        frequency.write(writer, (values[b] - values[b - 1]) >> shift);
                    }
                }
            }
        }

        /**
         * Writes {@code bs_add_harmonic_flag} and the flags.
         *
         * @param writer the destination
         * @param bands the number of high resolution bands
         */
        void writeHarmonics(MsbBitWriter writer, int bands) {
            writer.write(harmonics ? 1 : 0, 1);
            if (harmonics) {
                for (var b = 0; b < bands; b++) {
                    writer.write(harmonic[b] ? 1 : 0, 1);
                }
            }
        }

        /**
         * Replaces every envelope by its mean energy over frequency and every noise floor by its mean value, so
         * that each band costs about one bit to code.
         *
         * @param tables the frequency tables
         */
        void flatten(SbrFrequencies tables) {
            var factor = frameAmp == 0 ? 2 : 1;
            for (var l = 0; l < grid.envelopes; l++) {
                var resolution = grid.resolution[l];
                var bands = tables.bands(resolution);
                var table = resolution == 1 ? tables.high : tables.low;
                var sum = 0.0;
                for (var b = 0; b < bands; b++) {
                    sum += energy[l][b] * (table[b + 1] - table[b]);
                }
                var mean = sum / (table[bands] - table[0]);
                var value = Math.clamp((int) Math.floor(factor * Math.max(0, log2(mean / 64 + EPS)) + 0.5), 0,
                        factor == 2 ? 127 : 63);
                Arrays.fill(envelope[l], 0, bands, value);
            }
            for (var e = 0; e < grid.noiseEnvelopes; e++) {
                var sum = 0;
                for (var b = 0; b < tables.noiseBands; b++) {
                    sum += noise[e][b];
                }
                Arrays.fill(noise[e], 0, tables.noiseBands, Math.round((float) sum / tables.noiseBands));
            }
        }

        /**
         * Keeps the coded frame's last envelope and noise floors as the references of the next frame, as the
         * decoder does.
         *
         * @param balance whether the channel carried balance values
         * @param tables the frequency tables
         */
        void commit(boolean balance, SbrFrequencies tables) {
            var last = grid.envelopes - 1;
            System.arraycopy(codedEnvelope[last], 0, previousEnvelope, 0, tables.bands(grid.resolution[last]));
            previousResolution = grid.resolution[last];
            System.arraycopy(codedNoise[grid.noiseEnvelopes - 1], 0, previousNoise, 0, tables.noiseBands);
            previousAmp = frameAmp;
            previousValid = true;
        }

        /**
         * Returns the band of another frequency resolution that a time difference refers to, as the decoder maps
         * it.
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
    }
}
