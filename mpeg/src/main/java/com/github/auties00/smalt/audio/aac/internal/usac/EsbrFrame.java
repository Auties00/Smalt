/* Ports the ISO/IEC 23003-3:2019 reference software (ct_envextr.c: usac_sbrGetSingleChannelElement,
   usac_sbrGetSingleChannelElement_for_pvc, usac_sbrGetChannelPairElement, sbrGetDirectionControlData,
   sbrGetNoiseFloorData, sbrGetEnvelope, sbrGetAdditionalData, extractFrameInfo, extractFrameInfo_for_pvc;
   ct_envdec.c). Copyright Coding Technologies, Sony Corporation and ISO/IEC 2002-2008; ISO/IEC reference
   software licence (use in products conforming to ISO/IEC 23003). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.usac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.sbr.SbrHuffman;

import java.util.Arrays;

/**
 * Holds the parsed SBR data of one channel of a USAC element and the state its decoding carries from frame to
 * frame ({@code sbr_single_channel_element()} and {@code sbr_channel_pair_element()} of ISO/IEC 23003-3
 * subclause 5.3.4, with the envelope decoding of ISO/IEC 14496-3 subclause 4.6.18.3).
 *
 * <p>Instances are mutable and not thread-safe.
 */
final class EsbrFrame {
    static final int FIXFIX = 0;
    static final int FIXVAR = 1;
    static final int VARFIX = 2;
    static final int VARVAR = 3;

    static final int COUPLING_OFF = 0;
    static final int COUPLING_LEVEL = 1;
    static final int COUPLING_BALANCE = 2;

    static final int FRAME_INFO_LENGTH = 35;
    static final int MAX_ENVELOPES = 16;
    static final int MAX_FREQUENCY_COEFFICIENTS = 58;
    static final int MAX_NOISE_VALUES = 10;
    static final float NOISE_FLOOR_OFFSET = 6.0f;

    /**
     * The value of {@code bs_sin_pos} that marks an absent position.
     */
    static final int NO_SINE_POSITION = 31;

    /**
     * The current header.
     */
    final EsbrHeader header = new EsbrHeader();

    /**
     * The number of SBR time slots per frame.
     */
    final int timeSlots = 16;

    /**
     * The number of QMF slots per SBR time slot, 2 or 4.
     */
    final int rate;

    int frameClass;

    /**
     * The frame info vector: envelope count, time borders, frequency resolutions, transient envelope, noise
     * envelope count and noise borders.
     */
    final int[] frameInfo = new int[FRAME_INFO_LENGTH];

    /**
     * The frame info vector of predictive vector coding.
     */
    final int[] pvcFrameInfo = new int[FRAME_INFO_LENGTH];

    /**
     * The frame info vector of the previous frame.
     */
    final int[] previousFrameInfo = new int[FRAME_INFO_LENGTH];

    final int[] scaleFactorBands = new int[2];
    int noiseBands;
    int offset;
    int ampResolution;
    int noiseEnvelopes;
    int scaleFactors;
    int noiseFactors;
    int previousEnvelopeIsShort;

    final int[] envelopeDomain = new int[MAX_ENVELOPES];
    final int[] noiseDomain = new int[MAX_ENVELOPES];
    final int[] inverseFiltering = new int[MAX_NOISE_VALUES];
    final int[] previousInverseFiltering = new int[MAX_NOISE_VALUES];

    int coupling;

    /**
     * The envelope values, raw after parsing and energies after decoding.
     */
    final float[] envelope = new float[MAX_ENVELOPES * MAX_FREQUENCY_COEFFICIENTS];

    /**
     * The noise floor values, raw after parsing and levels after decoding.
     */
    final float[] noiseLevel = new float[MAX_NOISE_VALUES];

    final int[] addHarmonics = new int[MAX_ENVELOPES * MAX_FREQUENCY_COEFFICIENTS];

    final int[] interTesGamma = new int[MAX_ENVELOPES];

    int patchingMode;
    int oversampling;
    int pitchInBins;

    int sinePosition;
    int sineStartNext;
    int sineLengthNext;
    int sineStartCurrent;
    int sineLengthCurrent;
    int previousVarlenEnvelope;

    boolean independent;

    private final float[] previousEnergy = new float[MAX_FREQUENCY_COEFFICIENTS];
    private final float[] previousNoise = new float[MAX_NOISE_VALUES];

    /**
     * The noise floor of the last noise envelope, kept for predictive vector coding.
     */
    final float[] lastNoise = new float[MAX_NOISE_VALUES];

    private final int[] borders = new int[MAX_ENVELOPES + 1];
    private final int[] noiseBorders = new int[3];
    private final int[] resolutions = new int[MAX_ENVELOPES + 1];
    private final int[] relative = new int[MAX_ENVELOPES];
    private final int[] relative0 = new int[MAX_ENVELOPES];
    private final int[] relative1 = new int[MAX_ENVELOPES];
    private final int[] relativeLead = new int[MAX_ENVELOPES];
    private final int[] relativeTrail = new int[MAX_ENVELOPES];
    private final int[] pvcBorders = new int[MAX_ENVELOPES + 1];
    private final int[] pvcNoiseBorders = new int[3];

    /**
     * Constructs the state of a channel.
     *
     * @param rate the number of QMF slots per SBR time slot, 2 or 4
     */
    EsbrFrame(int rate) {
        this.rate = rate;
        this.previousEnvelopeIsShort = -1;
    }

    /**
     * Reads the patching side information of the harmonic transposer.
     *
     * @param reader the bit source
     */
    void readPatching(AacBitReader reader) {
        patchingMode = reader.read(1);
        if (patchingMode == 0) {
            oversampling = reader.read(1);
            pitchInBins = reader.readFlag() ? reader.read(7) : 0;
        } else {
            oversampling = 0;
            pitchInBins = 0;
        }
    }

    /**
     * Copies the patching side information of another channel.
     *
     * @param other the source
     */
    void copyPatching(EsbrFrame other) {
        patchingMode = other.patchingMode;
        oversampling = other.oversampling;
        pitchInBins = other.pitchInBins;
    }

    /**
     * Reads {@code sbr_single_channel_element()} without predictive vector coding.
     *
     * @param reader the bit source
     * @param harmonic whether the element uses the harmonic transposer
     * @param interTes whether the element uses inter-subband temporal envelope shaping
     */
    void readSingle(AacBitReader reader, boolean harmonic, boolean interTes) {
        if (harmonic) {
            readPatching(reader);
        }
        readGrid(reader);
        readDirections(reader);
        readInverseFiltering(reader);
        readEnvelope(reader, interTes);
        readNoise(reader);
        Arrays.fill(addHarmonics, 0, scaleFactorBands[EsbrFrequencies.HIGH], 0);
        readAdditional(reader, 0);
        coupling = COUPLING_OFF;
    }

    /**
     * Reads {@code sbr_single_channel_element()} with predictive vector coding.
     *
     * @param reader the bit source
     * @param harmonic whether the element uses the harmonic transposer
     * @param pvc the predictive vector coding state of the element
     * @param previousOriginal whether the previous frame did not use predictive vector coding
     */
    void readSinglePvc(AacBitReader reader, boolean harmonic, EsbrPvc pvc, boolean previousOriginal) {
        if (harmonic) {
            readPatching(reader);
        }
        var noisePosition = reader.read(4);
        pvc.currentVarlen = pvc.nextVarlen;
        pvc.nextVarlen = reader.readFlag() ? reader.read(2) + 1 : 0;
        readPvcGrid(noisePosition, previousOriginal, pvc.nextVarlen);
        noiseEnvelopes = frameInfo[1 + 2 * frameInfo[0] + 2];
        noiseDomain[0] = independent ? 0 : reader.read(1);
        for (var i = 1; i < noiseEnvelopes; i++) {
            noiseDomain[i] = reader.read(1);
        }
        readInverseFiltering(reader);
        pvc.read(reader, header.pvcMode, independent);
        readNoise(reader);
        Arrays.fill(addHarmonics, 0, scaleFactorBands[EsbrFrequencies.HIGH], 0);
        readAdditional(reader, pvc.nextVarlen);
        coupling = COUPLING_OFF;
    }

    /**
     * Reads {@code sbr_channel_pair_element()}.
     *
     * @param left the first channel
     * @param right the second channel
     * @param reader the bit source
     * @param harmonic whether the element uses the harmonic transposer
     * @param interTes whether the element uses inter-subband temporal envelope shaping
     */
    static void readPair(EsbrFrame left, EsbrFrame right, AacBitReader reader, boolean harmonic, boolean interTes) {
        var coupled = reader.readFlag();
        if (coupled) {
            if (harmonic) {
                left.readPatching(reader);
                right.copyPatching(left);
            }
            left.coupling = COUPLING_LEVEL;
            right.coupling = COUPLING_BALANCE;
        } else {
            if (harmonic) {
                left.readPatching(reader);
                right.readPatching(reader);
            }
            left.coupling = COUPLING_OFF;
            right.coupling = COUPLING_OFF;
        }
        left.readGrid(reader);
        if (coupled) {
            System.arraycopy(left.frameInfo, 0, right.frameInfo, 0, FRAME_INFO_LENGTH);
            right.noiseEnvelopes = left.noiseEnvelopes;
            right.frameClass = left.frameClass;
            right.ampResolution = left.ampResolution;
            left.readDirections(reader);
            right.readDirections(reader);
            for (var i = 0; i < left.noiseBands; i++) {
                left.previousInverseFiltering[i] = left.inverseFiltering[i];
                right.previousInverseFiltering[i] = right.inverseFiltering[i];
                left.inverseFiltering[i] = reader.read(2);
                right.inverseFiltering[i] = left.inverseFiltering[i];
            }
            left.readEnvelope(reader, interTes);
            left.readNoise(reader);
            right.readEnvelope(reader, interTes);
            right.readNoise(reader);
        } else {
            right.readGrid(reader);
            left.readDirections(reader);
            right.readDirections(reader);
            left.readInverseFiltering(reader);
            right.readInverseFiltering(reader);
            left.readEnvelope(reader, interTes);
            right.readEnvelope(reader, interTes);
            left.readNoise(reader);
            right.readNoise(reader);
        }
        Arrays.fill(left.addHarmonics, 0, left.scaleFactorBands[EsbrFrequencies.HIGH], 0);
        Arrays.fill(right.addHarmonics, 0, right.scaleFactorBands[EsbrFrequencies.HIGH], 0);
        left.readAdditional(reader, 0);
        right.readAdditional(reader, 0);
    }

    private void readInverseFiltering(AacBitReader reader) {
        for (var i = 0; i < noiseBands; i++) {
            previousInverseFiltering[i] = inverseFiltering[i];
            inverseFiltering[i] = reader.read(2);
        }
    }

    private void readDirections(AacBitReader reader) {
        noiseEnvelopes = frameInfo[0] > 1 ? 2 : 1;
        envelopeDomain[0] = independent ? 0 : reader.read(1);
        for (var i = 1; i < frameInfo[0]; i++) {
            envelopeDomain[i] = reader.read(1);
        }
        noiseDomain[0] = independent ? 0 : reader.read(1);
        for (var i = 1; i < noiseEnvelopes; i++) {
            noiseDomain[i] = reader.read(1);
        }
    }

    private void readNoise(AacBitReader reader) {
        byte[] timeBook;
        byte[] frequencyBook;
        int shift;
        if (coupling == COUPLING_BALANCE) {
            timeBook = SbrHuffman.NOISE_BALANCE_TIME;
            frequencyBook = SbrHuffman.BALANCE_30_FREQUENCY;
            shift = 1;
        } else {
            timeBook = SbrHuffman.NOISE_TIME;
            frequencyBook = SbrHuffman.ENVELOPE_30_FREQUENCY;
            shift = 0;
        }
        var bandsCount = noiseBands;
        noiseFactors = frameInfo[frameInfo[0] * 2 + 3] * bandsCount;
        for (var i = 0; i < noiseEnvelopes; i++) {
            if (noiseDomain[i] == 0) {
                noiseLevel[i * bandsCount] = coupling == COUPLING_BALANCE ? reader.read(5) << shift : reader.read(5);
                for (var j = 1; j < bandsCount; j++) {
                    noiseLevel[i * bandsCount + j] = SbrHuffman.decode(frequencyBook, reader) << shift;
                }
            } else {
                for (var j = 0; j < bandsCount; j++) {
                    noiseLevel[i * bandsCount + j] = SbrHuffman.decode(timeBook, reader) << shift;
                }
            }
        }
    }

    private void readEnvelope(AacBitReader reader, boolean interTes) {
        scaleFactors = 0;
        int amp;
        if (frameClass == FIXFIX && frameInfo[0] == 1) {
            amp = ampResolution = 0;
        } else {
            amp = ampResolution = header.ampResolution;
        }
        int startBits;
        int startBitsBalance;
        if (amp == 1) {
            startBits = 6;
            startBitsBalance = 5;
        } else {
            startBits = 7;
            startBitsBalance = 6;
        }
        var envelopes = frameInfo[0];
        byte[] timeBook;
        byte[] frequencyBook;
        int shift;
        if (coupling == COUPLING_BALANCE) {
            shift = 1;
            timeBook = amp == 0 ? SbrHuffman.BALANCE_15_TIME : SbrHuffman.BALANCE_30_TIME;
            frequencyBook = amp == 0 ? SbrHuffman.BALANCE_15_FREQUENCY : SbrHuffman.BALANCE_30_FREQUENCY;
        } else {
            shift = 0;
            timeBook = amp == 0 ? SbrHuffman.ENVELOPE_15_TIME : SbrHuffman.ENVELOPE_30_TIME;
            frequencyBook = amp == 0 ? SbrHuffman.ENVELOPE_15_FREQUENCY : SbrHuffman.ENVELOPE_30_FREQUENCY;
        }
        var position = 0;
        for (var j = 0; j < envelopes; j++) {
            var count = scaleFactorBands[frameInfo[envelopes + 2 + j]];
            scaleFactors += count;
            if (envelopeDomain[j] == 0) {
                envelope[position] = coupling == COUPLING_BALANCE ? reader.read(startBitsBalance) << shift
                        : reader.read(startBits);
            }
            for (var i = 1 - envelopeDomain[j]; i < count; i++) {
                var delta = SbrHuffman.decode(envelopeDomain[j] == 0 ? frequencyBook : timeBook, reader);
                envelope[position + i] = delta << shift;
            }
            position += count;
            interTesGamma[j] = 0;
            if (interTes && reader.readFlag()) {
                interTesGamma[j] = reader.read(2);
            }
        }
    }

    private void readAdditional(AacBitReader reader, int nextVarlen) {
        var present = reader.readFlag();
        sineStartCurrent = sineStartNext;
        sineLengthCurrent = sineLengthNext;
        sineStartNext = 0;
        sineLengthNext = 0;
        if (!present) {
            return;
        }
        var bands = scaleFactorBands[EsbrFrequencies.HIGH];
        for (var i = 0; i < bands; i++) {
            addHarmonics[i] = reader.read(1);
        }
        if (header.pvcMode == 0) {
            return;
        }
        sinePosition = NO_SINE_POSITION;
        if (reader.readFlag()) {
            sinePosition = reader.read(5);
        }
        if (nextVarlen > 0) {
            sineLengthNext = nextVarlen;
            if (sinePosition > 16 && sinePosition != 31 && nextVarlen + 16 != sinePosition) {
                sineStartNext = sinePosition - 16;
            }
        }
    }

    private void readGrid(AacBitReader reader) {
        var envelopes = 0;
        var variable = 0;
        var variable0 = 0;
        var variable1 = 0;
        var pointer = 0;
        var relativeCount = 0;
        var relativeCount0 = 0;
        var relativeCount1 = 0;
        frameClass = reader.read(2);
        switch (frameClass) {
            case FIXFIX -> {
                envelopes = 1 << reader.read(2);
                resolutions[0] = reader.read(1);
                for (var env = 1; env < envelopes; env++) {
                    resolutions[env] = resolutions[0];
                }
            }
            case FIXVAR, VARFIX -> {
                variable = reader.read(2);
                relativeCount = reader.read(2);
                envelopes = relativeCount + 1;
                for (var k = 0; k < envelopes - 1; k++) {
                    relative[k] = 2 * reader.read(2) + 2;
                }
                pointer = reader.read(pointerBits(envelopes));
                for (var env = 0; env < envelopes; env++) {
                    var resolution = reader.read(1);
                    if (frameClass == FIXVAR) {
                        resolutions[envelopes - 1 - env] = resolution;
                    } else {
                        resolutions[env] = resolution;
                    }
                }
            }
            default -> {
                variable0 = reader.read(2);
                variable1 = reader.read(2);
                relativeCount0 = reader.read(2);
                relativeCount1 = reader.read(2);
                envelopes = relativeCount0 + relativeCount1 + 1;
                for (var k = 0; k < relativeCount0; k++) {
                    relative0[k] = 2 * reader.read(2) + 2;
                }
                for (var k = 0; k < relativeCount1; k++) {
                    relative1[k] = 2 * reader.read(2) + 2;
                }
                pointer = reader.read(pointerBits(envelopes));
                for (var env = 0; env < envelopes; env++) {
                    resolutions[env] = reader.read(1);
                }
            }
        }
        int leadBorder;
        int trailBorder;
        int leadCount;
        int trailCount;
        switch (frameClass) {
            case FIXFIX -> {
                leadBorder = 0;
                trailBorder = timeSlots;
                leadCount = envelopes - 1;
                trailCount = 0;
            }
            case FIXVAR -> {
                leadBorder = 0;
                trailBorder = variable + timeSlots;
                leadCount = 0;
                trailCount = relativeCount;
            }
            case VARFIX -> {
                leadBorder = variable;
                trailBorder = timeSlots;
                leadCount = relativeCount;
                trailCount = 0;
            }
            default -> {
                leadBorder = variable0;
                trailBorder = variable1 + timeSlots;
                leadCount = relativeCount0;
                trailCount = relativeCount1;
            }
        }
        for (var k = 0; k < leadCount; k++) {
            relativeLead[k] = switch (frameClass) {
                case FIXFIX -> (int) ((float) timeSlots / envelopes + 0.5f);
                case VARFIX -> relative[k];
                default -> relative0[k];
            };
        }
        for (var k = 0; k < trailCount; k++) {
            relativeTrail[k] = frameClass == FIXVAR ? relative[k] : relative1[k];
        }
        borders[0] = leadBorder;
        borders[envelopes] = trailBorder;
        for (var env = 1; env <= leadCount; env++) {
            borders[env] = leadBorder;
            for (var k = 0; k <= env - 1; k++) {
                borders[env] += relativeLead[k];
            }
        }
        for (var env = leadCount + 1; env < envelopes; env++) {
            borders[env] = trailBorder;
            for (var k = 0; k <= envelopes - env - 1; k++) {
                borders[env] -= relativeTrail[k];
            }
        }
        for (var env = 0; env < envelopes; env++) {
            if (borders[env + 1] <= borders[env]) {
                throw new MediaException.Malformed("SBR grid has non-increasing time borders");
            }
        }
        int middle;
        if (frameClass == FIXFIX) {
            middle = envelopes / 2;
        } else if (frameClass == VARFIX) {
            middle = pointer == 0 ? 1 : pointer == 1 ? envelopes - 1 : pointer - 1;
        } else {
            middle = pointer <= 1 ? envelopes - 1 : envelopes + 1 - pointer;
        }
        if (middle < 0 || middle > envelopes) {
            throw new MediaException.Malformed("SBR grid pointer is out of range");
        }
        int noiseCount;
        noiseBorders[0] = borders[0];
        if (envelopes > 1) {
            noiseBorders[1] = borders[middle];
            noiseBorders[2] = borders[envelopes];
            noiseCount = 2;
        } else {
            noiseBorders[1] = borders[envelopes];
            noiseCount = 1;
        }
        int transientEnvelope;
        if (frameClass == FIXFIX) {
            transientEnvelope = -1;
        } else if (frameClass == VARFIX) {
            transientEnvelope = pointer <= 1 ? -1 : pointer - 1;
        } else {
            transientEnvelope = pointer == 0 ? -1 : envelopes + 1 - pointer;
        }
        store(frameInfo, envelopes, borders, resolutions, transientEnvelope, noiseCount, noiseBorders);
    }

    private static int pointerBits(int envelopes) {
        return 32 - Integer.numberOfLeadingZeros(envelopes);
    }

    private void readPvcGrid(int noisePosition, boolean previousOriginal, int varlen) {
        borders[0] = frameInfo[0] > 0 ? frameInfo[1 + frameInfo[0]] - 16 : 0;
        pvcBorders[0] = 0;
        resolutions[0] = 0;
        int envelopes;
        if (noisePosition == 0) {
            borders[1] = 16 + varlen;
            pvcBorders[1] = 16;
            envelopes = 1;
        } else {
            borders[1] = noisePosition;
            pvcBorders[1] = noisePosition;
            borders[2] = 16 + varlen;
            pvcBorders[2] = 16;
            resolutions[1] = 0;
            envelopes = 2;
        }
        for (var i = 0; i <= envelopes; i++) {
            noiseBorders[i] = borders[i];
            pvcNoiseBorders[i] = pvcBorders[i];
        }
        if (previousOriginal) {
            pvcBorders[0] = borders[0];
            pvcNoiseBorders[0] = borders[0];
        }
        for (var env = 0; env < envelopes; env++) {
            if (borders[env + 1] <= borders[env] || pvcBorders[env + 1] < pvcBorders[env]) {
                throw new MediaException.Malformed("SBR predictive vector coding grid has non-increasing borders");
            }
        }
        store(pvcFrameInfo, envelopes, pvcBorders, resolutions, -1, envelopes, pvcNoiseBorders);
        store(frameInfo, envelopes, borders, resolutions, -1, envelopes, noiseBorders);
    }

    private static void store(int[] info, int envelopes, int[] borders, int[] resolutions, int transientEnvelope,
                              int noiseCount, int[] noiseBorders) {
        info[0] = envelopes;
        System.arraycopy(borders, 0, info, 1, envelopes + 1);
        System.arraycopy(resolutions, 0, info, 1 + envelopes + 1, envelopes);
        info[1 + envelopes + 1 + envelopes] = transientEnvelope;
        info[1 + envelopes + 1 + envelopes + 1] = noiseCount;
        System.arraycopy(noiseBorders, 0, info, 1 + envelopes + 1 + envelopes + 1 + 1, noiseCount + 1);
    }

    /**
     * Turns the raw envelope and noise floor values into energies and levels ({@code decodeSbrData}).
     */
    void decode() {
        decodeEnvelope();
        decodeNoise();
        if (coupling == COUPLING_OFF) {
            var ca = 1.0f / (2.0f - ampResolution);
            for (var i = 0; i < scaleFactors; i++) {
                envelope[i] = (float) (StrictMath.pow(2, envelope[i] * ca) * 64);
            }
            for (var i = 0; i < noiseFactors; i++) {
                noiseLevel[i] = NOISE_FLOOR_OFFSET - noiseLevel[i];
                noiseLevel[i] = (float) StrictMath.pow(2.0f, noiseLevel[i]);
            }
        }
    }

    /**
     * Turns the raw noise floor values of a predictive vector coding frame into levels
     * ({@code decodeSbrData_for_pvc}).
     *
     * @return the number of noise floor bands
     */
    int decodePvc() {
        decodeNoise();
        if (coupling == COUPLING_OFF) {
            for (var i = 0; i < noiseFactors; i++) {
                noiseLevel[i] = NOISE_FLOOR_OFFSET - noiseLevel[i];
                noiseLevel[i] = (float) StrictMath.pow(2.0f, noiseLevel[i]);
            }
            for (var i = 0; i < noiseBands; i++) {
                lastNoise[i] = NOISE_FLOOR_OFFSET - lastNoise[i];
                lastNoise[i] = (float) StrictMath.pow(2.0f, lastNoise[i]);
            }
        }
        return noiseBands;
    }

    /**
     * Turns the level and balance values of a coupled pair into the energies of both channels
     * ({@code sbr_envelope_unmapping}).
     *
     * @param left the level channel
     * @param right the balance channel
     */
    static void unmap(EsbrFrame left, EsbrFrame right) {
        var amp = right.ampResolution;
        var panOffset = amp == 0 ? 24.0f : 12.0f;
        var a = 2.0f - amp;
        for (var i = 0; i < left.scaleFactors; i++) {
            var l = left.envelope[i];
            var r = right.envelope[i];
            left.envelope[i] = (float) (64 * (StrictMath.pow(2, l / a + 1) / (1 + StrictMath.pow(2, (panOffset - r) / a))));
            right.envelope[i] = (float) (64 * (StrictMath.pow(2, l / a + 1) / (1 + StrictMath.pow(2, (r - panOffset) / a))));
        }
        for (var i = 0; i < left.noiseFactors; i++) {
            var l = left.noiseLevel[i];
            var r = right.noiseLevel[i];
            left.noiseLevel[i] = (float) (StrictMath.pow(2, NOISE_FLOOR_OFFSET - l + 1) / (1 + StrictMath.pow(2, 12.0f - r)));
            right.noiseLevel[i] = (float) (StrictMath.pow(2, NOISE_FLOOR_OFFSET - l + 1) / (1 + StrictMath.pow(2, r - 12.0f)));
        }
    }

    private void decodeEnvelope() {
        var position = 0;
        var envelopes = frameInfo[0];
        for (var i = 0; i < envelopes; i++) {
            var resolution = frameInfo[envelopes + i + 2];
            var count = scaleFactorBands[resolution];
            if (envelopeDomain[i] == 0) {
                mapLowRes(envelope[position], 0, resolution);
                position++;
                for (var band = 1; band < count; band++) {
                    envelope[position] = envelope[position] + envelope[position - 1];
                    mapLowRes(envelope[position], band, resolution);
                    position++;
                }
            } else {
                for (var band = 0; band < count; band++) {
                    envelope[position] = envelope[position] + previousEnergy[lowToHigh(band, resolution)];
                    mapLowRes(envelope[position], band, resolution);
                    position++;
                }
            }
        }
    }

    private int lowToHigh(int index, int resolution) {
        if (resolution != EsbrFrequencies.LOW) {
            return index;
        }
        if (offset >= 0) {
            return index < offset ? index : 2 * index - offset;
        }
        var o = -offset;
        return index < o ? 3 * index : 2 * index + o;
    }

    private void mapLowRes(float value, int index, int resolution) {
        if (resolution != EsbrFrequencies.LOW) {
            previousEnergy[index] = value;
            return;
        }
        if (offset >= 0) {
            if (index < offset) {
                previousEnergy[index] = value;
            } else {
                previousEnergy[2 * index - offset] = value;
                previousEnergy[2 * index + 1 - offset] = value;
            }
        } else {
            var o = -offset;
            if (index < o) {
                previousEnergy[3 * index] = value;
                previousEnergy[3 * index + 1] = value;
                previousEnergy[3 * index + 2] = value;
            } else {
                previousEnergy[2 * index + o] = value;
                previousEnergy[2 * index + 1 + o] = value;
            }
        }
    }

    private void decodeNoise() {
        var envelopes = frameInfo[frameInfo[0] * 2 + 3];
        var position = 0;
        for (var env = 0; env < envelopes; env++) {
            var last = env == envelopes - 1;
            if (noiseDomain[env] == 0) {
                previousNoise[0] = noiseLevel[position];
                if (last) {
                    lastNoise[0] = noiseLevel[position];
                }
                position++;
                for (var i = 1; i < noiseBands; i++) {
                    noiseLevel[position] += noiseLevel[position - 1];
                    previousNoise[i] = noiseLevel[position];
                    if (last) {
                        lastNoise[i] = noiseLevel[position];
                    }
                    position++;
                }
            } else {
                for (var i = 0; i < noiseBands; i++) {
                    noiseLevel[position] += previousNoise[i];
                    previousNoise[i] = noiseLevel[position];
                    if (last) {
                        lastNoise[i] = noiseLevel[position];
                    }
                    position++;
                }
            }
        }
    }
}
