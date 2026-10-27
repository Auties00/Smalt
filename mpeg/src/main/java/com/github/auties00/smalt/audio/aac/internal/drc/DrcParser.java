/* Ports the ISO/IEC 23003-4 reference software (uniDrcParser.c without the ISOBMFF and MPEG-H syntax,
   uniDrcBitstreamDecoder.c, and the code tables of uniDrcTables.c). Copyright Apple Inc., Fraunhofer IIS and ISO/IEC
   2014; ISO/IEC reference software licence (use in products conforming to ISO/IEC 23003-4). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.drc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;

import static com.github.auties00.smalt.audio.aac.internal.drc.DrcModel.*;

/**
 * Parses the MPEG-D DRC syntax: {@code uniDrcConfig()}, {@code loudnessInfoSet()}, {@code uniDrcGain()} and the
 * {@code uniDrc()} wrapper (ISO/IEC 23003-4 clause 7), with the extensions of Amendment 1.
 *
 * <p>Instances keep the parameters the stream's audio implies; they are not thread-safe.
 */
final class DrcParser {
    final DrcParamsBsDec drcParams = new DrcParamsBsDec();
    final Tables tablesDefault = new Tables();
    private final int[] timeBufPrevFrame = new int[NODE_COUNT_MAX];
    private final int[] timeBufCurFrame = new int[NODE_COUNT_MAX];
    private int packetlossFrameCount;

    /**
     * Constructs a parser ({@code initUniDrcBitstreamDec}).
     *
     * @param audioSampleRate the audio sampling rate
     * @param audioFrameSize the number of samples per audio frame, which is the DRC frame size
     * @param delayMode {@link DrcModel#DELAY_MODE_REGULAR_DELAY} or {@link DrcModel#DELAY_MODE_LOW_DELAY}
     * @throws MediaException.Malformed if the parameters are out of range
     */
    DrcParser(int audioSampleRate, int audioFrameSize, int delayMode) {
        if (audioFrameSize < 1 || audioFrameSize > AUDIO_CODEC_FRAME_SIZE_MAX) {
            throw new MediaException.Malformed("MPEG-D DRC frame size " + audioFrameSize + " is out of range");
        }
        if (audioSampleRate < 1000) {
            throw new MediaException.Malformed("MPEG-D DRC does not support sampling rates below 1000 Hz");
        }
        drcParams.sampleRateDefault = audioSampleRate;
        drcParams.drcFrameSize = audioFrameSize;
        if (drcParams.drcFrameSize < 0.001f * audioSampleRate) {
            throw new MediaException.Malformed("MPEG-D DRC frame size must be at least 1 ms");
        }
        drcParams.deltaTminDefault = deltaTmin(audioSampleRate);
        if (drcParams.deltaTminDefault > drcParams.drcFrameSize) {
            throw new MediaException.Malformed("MPEG-D DRC time interval exceeds the frame size");
        }
        drcParams.nGainValuesMaxDefault = drcParams.drcFrameSize / drcParams.deltaTminDefault;
        drcParams.delayMode = delayMode;
        drcParams.lfeChannelMapCount = -1;
        generateDeltaTimeCodeTable(drcParams.nGainValuesMaxDefault, tablesDefault);
    }

    /**
     * {@return the default time resolution of the gain nodes at a sampling rate} ({@code getDeltaTmin})
     *
     * @param sampleRate the sampling rate, at least 1000
     */
    static int deltaTmin(int sampleRate) {
        var lowerBound = (int) (0.5f + 0.0005f * sampleRate);
        var result = 1;
        while (result <= lowerBound) {
            result <<= 1;
        }
        return result;
    }

    static int bits(AacBitReader reader, int count) {
        if (count == 0) {
            return 0;
        }
        if (reader.bitsLeft() < count) {
            throw new MediaException.Malformed("MPEG-D DRC payload is truncated");
        }
        return reader.read(count);
    }

    private static void skipBits(AacBitReader reader, long count) {
        if (reader.bitsLeft() < count) {
            throw new MediaException.Malformed("MPEG-D DRC payload is truncated");
        }
        reader.skip(count);
    }

    /**
     * Builds the delta time code table of a gain set ({@code generateDeltaTimeCodeTable}).
     */
    static void generateDeltaTimeCodeTable(int nGainValuesMax, Tables table) {
        var z = 1;
        while ((1 << z) < 2 * nGainValuesMax) {
            z++;
        }
        table.size[0] = -1;
        table.code[0] = -1;
        table.value[0] = -1;
        table.size[1] = 2;
        table.code[1] = 0x0;
        table.value[1] = 1;
        for (var n = 0; n < 4; n++) {
            table.size[n + 2] = 4;
            table.code[n + 2] = 0x4 + n;
            table.value[n + 2] = n + 2;
        }
        for (var n = 0; n < 8; n++) {
            table.size[n + 6] = 5;
            table.code[n + 6] = 0x10 + n;
            table.value[n + 6] = n + 6;
        }
        var k = 2 * nGainValuesMax - 14 + 1;
        for (var n = 0; n < k && n + 14 < N_DELTA_TIME_CODE_TABLE_ENTRIES_MAX; n++) {
            table.size[n + 14] = 2 + z;
            table.code[n + 14] = (0x3 << z) + n;
            table.value[n + 14] = n + 14;
        }
    }

    private static void decDuckingScaling(AacBitReader reader, DuckingModifiers out) {
        if (bits(reader, 1) == 0) {
            out.duckingScalingPresent = 0;
            out.duckingScaling = 1.0f;
        } else {
            out.duckingScalingPresent = 1;
            var bs = bits(reader, 4);
            var sigma = bs >> 3;
            var mu = bs & 0x7;
            if (sigma == 0) {
                out.duckingScaling = 1.0f + 0.125f * (1.0f + mu);
            } else {
                out.duckingScaling = 1.0f - 0.125f * (1.0f + mu);
            }
        }
    }

    private static void decGainModifiers(AacBitReader reader, int version, int bandCount, GainModifiers gm) {
        if (version > 0) {
            for (var b = 0; b < bandCount; b++) {
                gm.targetCharacteristicLeftPresent[b] = bits(reader, 1);
                if (gm.targetCharacteristicLeftPresent[b] != 0) {
                    gm.targetCharacteristicLeftIndex[b] = bits(reader, 4);
                }
                gm.targetCharacteristicRightPresent[b] = bits(reader, 1);
                if (gm.targetCharacteristicRightPresent[b] != 0) {
                    gm.targetCharacteristicRightIndex[b] = bits(reader, 4);
                }
                gm.gainScalingPresent[b] = bits(reader, 1);
                if (gm.gainScalingPresent[b] != 0) {
                    gm.attenuationScaling[b] = bits(reader, 4) * 0.125f;
                    gm.amplificationScaling[b] = bits(reader, 4) * 0.125f;
                }
                gm.gainOffsetPresent[b] = bits(reader, 1);
                if (gm.gainOffsetPresent[b] != 0) {
                    var sign = bits(reader, 1);
                    var offset = (1 + bits(reader, 5)) * 0.25f;
                    gm.gainOffset[b] = sign != 0 ? -offset : offset;
                }
            }
            if (bandCount == 1) {
                gm.shapeFilterPresent = bits(reader, 1);
                if (gm.shapeFilterPresent != 0) {
                    gm.shapeFilterIndex = bits(reader, 4);
                }
            }
        } else {
            var attenuationScaling = 1.0f;
            var amplificationScaling = 1.0f;
            var gainOffset = 0.0f;
            var gainScalingPresent = bits(reader, 1);
            if (gainScalingPresent != 0) {
                attenuationScaling = bits(reader, 4) * 0.125f;
                amplificationScaling = bits(reader, 4) * 0.125f;
            }
            var gainOffsetPresent = bits(reader, 1);
            if (gainOffsetPresent != 0) {
                var sign = bits(reader, 1);
                gainOffset = (1 + bits(reader, 5)) * 0.25f;
                if (sign != 0) {
                    gainOffset = -gainOffset;
                }
            }
            for (var b = 0; b < bandCount; b++) {
                gm.targetCharacteristicLeftPresent[b] = 0;
                gm.targetCharacteristicRightPresent[b] = 0;
                gm.gainScalingPresent[b] = gainScalingPresent;
                gm.attenuationScaling[b] = attenuationScaling;
                gm.amplificationScaling[b] = amplificationScaling;
                gm.gainOffsetPresent[b] = gainOffsetPresent;
                gm.gainOffset[b] = gainOffset;
            }
            gm.shapeFilterPresent = 0;
        }
    }

    private static float decMethodValue(AacBitReader reader, int methodDefinition) {
        return switch (methodDefinition) {
            case METHOD_DEFINITION_UNKNOWN_OTHER, METHOD_DEFINITION_PROGRAM_LOUDNESS, METHOD_DEFINITION_ANCHOR_LOUDNESS,
                 METHOD_DEFINITION_MAX_OF_LOUDNESS_RANGE, METHOD_DEFINITION_MOMENTARY_LOUDNESS_MAX,
                 METHOD_DEFINITION_SHORT_TERM_LOUDNESS_MAX -> -57.75f + bits(reader, 8) * 0.25f;
            case METHOD_DEFINITION_LOUDNESS_RANGE -> {
                var tmp = bits(reader, 8);
                if (tmp == 0) {
                    yield 0.0f;
                } else if (tmp <= 128) {
                    yield tmp * 0.25f;
                } else if (tmp <= 204) {
                    yield 0.5f * tmp - 32.0f;
                } else {
                    yield tmp - 134.0f;
                }
            }
            case METHOD_DEFINITION_MIXING_LEVEL -> bits(reader, 5) + 80.0f;
            case METHOD_DEFINITION_ROOM_TYPE -> (float) bits(reader, 2);
            case METHOD_DEFINITION_SHORT_TERM_LOUDNESS -> -116.f + bits(reader, 8) * 0.5f;
            default -> throw new MediaException.Malformed("MPEG-D DRC loudness method definition " + methodDefinition
                    + " is reserved");
        };
    }

    private static void parseLoudnessInfo(AacBitReader reader, int version, LoudnessInfo info) {
        info.drcSetId = bits(reader, 6);
        info.eqSetId = version >= 1 ? bits(reader, 6) : 0;
        info.downmixId = bits(reader, 7);
        info.samplePeakLevelPresent = bits(reader, 1);
        if (info.samplePeakLevelPresent != 0) {
            var bs = bits(reader, 12);
            if (bs == 0) {
                info.samplePeakLevelPresent = 0;
                info.samplePeakLevel = 0.0f;
            } else {
                info.samplePeakLevel = 20.0f - bs * 0.03125f;
            }
        }
        info.truePeakLevelPresent = bits(reader, 1);
        if (info.truePeakLevelPresent != 0) {
            var bs = bits(reader, 12);
            if (bs == 0) {
                info.truePeakLevelPresent = 0;
                info.truePeakLevel = 0.0f;
            } else {
                info.truePeakLevel = 20.0f - bs * 0.03125f;
            }
            info.truePeakLevelMeasurementSystem = bits(reader, 4);
            info.truePeakLevelReliability = bits(reader, 2);
        }
        info.measurementCount = bits(reader, 4);
        for (var i = 0; i < info.measurementCount; i++) {
            var measure = info.loudnessMeasure[i];
            measure.methodDefinition = bits(reader, 4);
            measure.methodValue = decMethodValue(reader, measure.methodDefinition);
            measure.measurementSystem = bits(reader, 4);
            measure.reliability = bits(reader, 2);
        }
    }

    private static void parseLoudnessInfoSetExtEq(AacBitReader reader, LoudnessInfoSet set) {
        var albumCount = bits(reader, 6);
        var count = bits(reader, 6);
        var offset = set.loudnessInfoAlbumCount;
        checkCount(offset + albumCount, LOUDNESS_INFO_COUNT_MAX, "loudness information");
        set.loudnessInfoAlbumCount += albumCount;
        for (var i = 0; i < albumCount; i++) {
            parseLoudnessInfo(reader, 1, set.loudnessInfoAlbum[i + offset]);
        }
        offset = set.loudnessInfoCount;
        checkCount(offset + count, LOUDNESS_INFO_COUNT_MAX, "loudness information");
        set.loudnessInfoCount += count;
        for (var i = 0; i < count; i++) {
            parseLoudnessInfo(reader, 1, set.loudnessInfo[i + offset]);
        }
    }

    private static void parseLoudnessInfoSetExtension(AacBitReader reader, LoudnessInfoSet set) {
        var k = 0;
        set.loudnessInfoSetExtType[k] = bits(reader, 4);
        while (set.loudnessInfoSetExtType[k] != UNIDRCLOUDEXT_TERM) {
            var extSizeBits = bits(reader, 4) + 4;
            var bitSize = bits(reader, extSizeBits) + 1;
            set.extBitSize[k] = bitSize;
            if (set.loudnessInfoSetExtType[k] == UNIDRCLOUDEXT_EQ) {
                parseLoudnessInfoSetExtEq(reader, set);
            } else {
                skipBits(reader, bitSize);
            }
            k = Math.min(k + 1, set.loudnessInfoSetExtType.length - 1);
            set.loudnessInfoSetExtType[k] = bits(reader, 4);
        }
    }

    private static void checkCount(int count, int max, String what) {
        if (count > max) {
            throw new MediaException.Malformed("MPEG-D DRC declares more " + what + " entries than supported");
        }
    }

    /**
     * Selects the coefficients of a DRC location, preferring the version 1 syntax ({@code selectDrcCoefficients}).
     *
     * @return the coefficients, or {@code null} for parametric DRC only
     */
    static DrcCoefficientsUniDrc selectDrcCoefficients(UniDrcConfig config, int location) {
        var cV1 = -1;
        var cV0 = -1;
        for (var n = 0; n < config.drcCoefficientsUniDrcCount; n++) {
            if (config.drcCoefficientsUniDrc[n].drcLocation == location) {
                if (config.drcCoefficientsUniDrc[n].version == 0) {
                    cV0 = n;
                } else {
                    cV1 = n;
                }
            }
        }
        if (cV1 >= 0) {
            return config.drcCoefficientsUniDrc[cV1];
        }
        if (cV0 >= 0) {
            return config.drcCoefficientsUniDrc[cV0];
        }
        return null;
    }

    private void generateDrcInstructionsDerivedData(UniDrcConfig config, DrcInstructionsUniDrc instructions) {
        var n = 0;
        for (; n < config.drcCoefficientsUniDrcCount; n++) {
            if (config.drcCoefficientsUniDrc[n].drcLocation == instructions.drcLocation) {
                break;
            }
        }
        if (n == config.drcCoefficientsUniDrcCount && config.drcCoefficientsUniDrcCount > 0) {
            throw new MediaException.Malformed("MPEG-D DRC instructions refer to a location without coefficients");
        }
        var coefficients = config.drcCoefficientsUniDrc[Math.min(n, DRC_COEFF_COUNT_MAX - 1)];
        DrcCoefficientsParametricDrc parametric = null;
        if (config.uniDrcConfigExtPresent != 0 && config.uniDrcConfigExt.parametricDrcPresent != 0
                && config.uniDrcConfigExt.drcCoefficientsParametricDrc.drcLocation == instructions.drcLocation) {
            parametric = config.uniDrcConfigExt.drcCoefficientsParametricDrc;
        }
        for (var g = 0; g < instructions.nDrcChannelGroups; g++) {
            var seq = instructions.gainSetIndexForChannelGroup[g];
            if (seq != -1 && (config.drcCoefficientsUniDrcCount == 0 || seq >= coefficients.gainSetCount)) {
                instructions.channelGroupIsParametricDrc[g] = 1;
                if (config.drcCoefficientsUniDrcCount != 0) {
                    seq = seq - coefficients.gainSetCount;
                }
                instructions.gainSetIndexForChannelGroupParametricDrc[g] = seq;
                if (parametric == null || seq >= parametric.parametricDrcGainSetCount) {
                    throw new MediaException.Malformed("MPEG-D DRC refers to a missing parametric DRC gain set");
                }
                instructions.gainInterpolationTypeForChannelGroup[g] = 1;
                instructions.timeDeltaMinForChannelGroup[g] = parametric.parametricDrcFrameSize;
                instructions.timeAlignmentForChannelGroup[g] = 0;
            } else {
                instructions.channelGroupIsParametricDrc[g] = 0;
            }
            if (instructions.channelGroupIsParametricDrc[g] == 0) {
                if (seq < 0 || seq >= coefficients.gainSetCount) {
                    throw new MediaException.Malformed("MPEG-D DRC refers to a missing gain set");
                }
                var set = coefficients.gainSetParams[seq];
                instructions.gainInterpolationTypeForChannelGroup[g] = set.gainInterpolationType;
                instructions.timeDeltaMinForChannelGroup[g] = set.timeDeltaMinPresent != 0 ? set.timeDeltaMin
                        : drcParams.deltaTminDefault;
                instructions.timeAlignmentForChannelGroup[g] = set.timeAlignment;
            }
        }
        if ((instructions.drcSetEffect & (EFFECT_BIT_DUCK_OTHER | EFFECT_BIT_DUCK_SELF)) != 0) {
            instructions.gainElementCount = instructions.nDrcChannelGroups;
        } else {
            var count = 0;
            for (var g = 0; g < instructions.nDrcChannelGroups; g++) {
                if (instructions.channelGroupIsParametricDrc[g] == 1) {
                    count++;
                    instructions.bandCountForChannelGroup[g] = 1;
                } else {
                    var seq = instructions.gainSetIndexForChannelGroup[g];
                    var bandCount = coefficients.gainSetParams[seq].bandCount;
                    instructions.bandCountForChannelGroup[g] = bandCount;
                    count += bandCount;
                }
            }
            instructions.gainElementCount = count;
        }
    }

    /**
     * Appends the pseudo instructions of the DRC off configuration of the base layout and of each downmix
     * ({@code generateDrcInstructionsForDrcOff}).
     */
    static void generateDrcInstructionsForDrcOff(UniDrcConfig config) {
        var s = -1;
        var k = config.drcInstructionsUniDrcCount;
        var nMixes = config.downmixInstructionsCount + 1;
        checkCount(k + nMixes, DRC_INSTRUCTIONS_COUNT_MAX, "DRC instructions");
        var instructions = config.drcInstructionsUniDrc[k];
        instructions.clear();
        instructions.drcSetId = s;
        s--;
        instructions.downmixId[0] = ID_FOR_BASE_LAYOUT;
        instructions.downmixIdCount = 1;
        for (var i = 1; i < nMixes; i++) {
            instructions = config.drcInstructionsUniDrc[k + i];
            instructions.clear();
            instructions.drcSetId = s;
            s--;
            instructions.downmixId[0] = config.downmixInstructions[i - 1].downmixId;
            instructions.downmixIdCount = 1;
        }
        config.drcInstructionsCountPlus = config.drcInstructionsUniDrcCount + nMixes;
    }

    private static void parseDrcInstructionsBasic(AacBitReader reader, DrcInstructionsBasic instructions) {
        instructions.drcSetId = bits(reader, 6);
        instructions.drcLocation = bits(reader, 4);
        instructions.downmixId[0] = bits(reader, 7);
        instructions.downmixIdCount = 1;
        if (bits(reader, 1) != 0) {
            var count = bits(reader, 3);
            for (var i = 0; i < count; i++) {
                instructions.downmixId[i + 1] = bits(reader, 7);
            }
            instructions.downmixIdCount = 1 + count;
        }
        instructions.drcSetEffect = bits(reader, 16);
        if ((instructions.drcSetEffect & (EFFECT_BIT_DUCK_OTHER | EFFECT_BIT_DUCK_SELF)) == 0) {
            instructions.limiterPeakTargetPresent = bits(reader, 1);
            if (instructions.limiterPeakTargetPresent != 0) {
                instructions.limiterPeakTarget = -bits(reader, 8) * 0.125f;
            }
        }
        instructions.drcSetTargetLoudnessPresent = bits(reader, 1);
        instructions.drcSetTargetLoudnessValueUpper = 0;
        instructions.drcSetTargetLoudnessValueLower = -63;
        if (instructions.drcSetTargetLoudnessPresent == 1) {
            instructions.drcSetTargetLoudnessValueUpper = bits(reader, 6) - 63;
            instructions.drcSetTargetLoudnessValueLowerPresent = bits(reader, 1);
            if (instructions.drcSetTargetLoudnessValueLowerPresent == 1) {
                instructions.drcSetTargetLoudnessValueLower = bits(reader, 6) - 63;
            }
        }
    }

    private void parseDrcInstructionsUniDrc(AacBitReader reader, int version, UniDrcConfig config,
                                            DrcInstructionsUniDrc instructions) {
        instructions.drcSetId = bits(reader, 6);
        instructions.drcSetComplexityLevel = version == 0 ? DRC_COMPLEXITY_LEVEL_MAX : bits(reader, 4);
        instructions.drcLocation = bits(reader, 4);
        var downmixIdPresent = 1;
        if (version >= 1) {
            downmixIdPresent = bits(reader, 1);
        }
        if (downmixIdPresent == 1) {
            instructions.downmixId[0] = bits(reader, 7);
            if (version >= 1) {
                instructions.drcApplyToDownmix = bits(reader, 1);
            }
            if (version == 0) {
                instructions.drcApplyToDownmix = instructions.downmixId[0] == 0 ? 0 : 1;
            }
            if (bits(reader, 1) != 0) {
                var count = bits(reader, 3);
                for (var i = 0; i < count; i++) {
                    instructions.downmixId[i + 1] = bits(reader, 7);
                }
                instructions.downmixIdCount = 1 + count;
            } else {
                instructions.downmixIdCount = 1;
            }
        } else {
            instructions.downmixId[0] = 0;
            instructions.downmixIdCount = 1;
        }
        instructions.drcSetEffect = bits(reader, 16);
        if ((instructions.drcSetEffect & (EFFECT_BIT_DUCK_OTHER | EFFECT_BIT_DUCK_SELF)) == 0) {
            instructions.limiterPeakTargetPresent = bits(reader, 1);
            if (instructions.limiterPeakTargetPresent != 0) {
                instructions.limiterPeakTarget = -bits(reader, 8) * 0.125f;
            }
        }
        instructions.drcSetTargetLoudnessPresent = bits(reader, 1);
        instructions.drcSetTargetLoudnessValueUpper = 0;
        instructions.drcSetTargetLoudnessValueLower = -63;
        if (instructions.drcSetTargetLoudnessPresent == 1) {
            instructions.drcSetTargetLoudnessValueUpper = bits(reader, 6) - 63;
            instructions.drcSetTargetLoudnessValueLowerPresent = bits(reader, 1);
            if (instructions.drcSetTargetLoudnessValueLowerPresent == 1) {
                instructions.drcSetTargetLoudnessValueLower = bits(reader, 6) - 63;
            }
        }
        instructions.dependsOnDrcSetPresent = bits(reader, 1);
        instructions.noIndependentUse = 0;
        if (instructions.dependsOnDrcSetPresent != 0) {
            instructions.dependsOnDrcSet = bits(reader, 6);
        } else {
            instructions.noIndependentUse = bits(reader, 1);
        }
        instructions.requiresEq = version == 0 ? 0 : bits(reader, 1);
        var coefficients = selectDrcCoefficients(config, instructions.drcLocation);
        var drcChannelCount = config.channelLayout.baseChannelCount;
        var uniqueIndex = new int[CHANNEL_COUNT_MAX];
        var uniqueScaling = new float[CHANNEL_COUNT_MAX];
        java.util.Arrays.fill(uniqueIndex, -10);
        java.util.Arrays.fill(uniqueScaling, -10.0f);
        if ((instructions.drcSetEffect & (EFFECT_BIT_DUCK_OTHER | EFFECT_BIT_DUCK_SELF)) != 0) {
            var c = 0;
            while (c < drcChannelCount) {
                instructions.gainSetIndex[c] = bits(reader, 6) - 1;
                decDuckingScaling(reader, instructions.duckingModifiersForChannel[c]);
                c++;
                if (bits(reader, 1) == 1) {
                    var repeat = bits(reader, 5) + 1;
                    if (c + repeat > CHANNEL_COUNT_MAX) {
                        throw new MediaException.Malformed("MPEG-D DRC repeats parameters past the channel count");
                    }
                    for (var k = 0; k < repeat; k++) {
                        instructions.gainSetIndex[c] = instructions.gainSetIndex[c - 1];
                        instructions.duckingModifiersForChannel[c].duckingScalingPresent =
                                instructions.duckingModifiersForChannel[c - 1].duckingScalingPresent;
                        instructions.duckingModifiersForChannel[c].duckingScaling =
                                instructions.duckingModifiersForChannel[c - 1].duckingScaling;
                        c++;
                    }
                }
            }
            if (c > drcChannelCount) {
                throw new MediaException.Malformed("MPEG-D DRC repeats parameters past the channel count");
            }
            var duckingSequence = -1;
            var g = 0;
            if ((instructions.drcSetEffect & EFFECT_BIT_DUCK_OTHER) != 0) {
                for (c = 0; c < drcChannelCount; c++) {
                    var match = false;
                    var idx = instructions.gainSetIndex[c];
                    var factor = instructions.duckingModifiersForChannel[c].duckingScaling;
                    if (idx < 0) {
                        for (var n = 0; n < g; n++) {
                            if (uniqueScaling[n] == factor) {
                                match = true;
                                instructions.channelGroupForChannel[c] = n;
                                break;
                            }
                        }
                        if (!match) {
                            uniqueIndex[g] = idx;
                            uniqueScaling[g] = factor;
                            instructions.channelGroupForChannel[c] = g;
                            g++;
                        }
                    } else {
                        if (duckingSequence > 0 && duckingSequence != idx) {
                            throw new MediaException.Malformed("MPEG-D DRC ducking has more than one gain sequence");
                        }
                        duckingSequence = idx;
                        instructions.channelGroupForChannel[c] = -1;
                    }
                }
                instructions.nDrcChannelGroups = g;
                if (duckingSequence == -1) {
                    throw new MediaException.Malformed("MPEG-D DRC ducking has no ducking gain sequence");
                }
            } else {
                for (c = 0; c < drcChannelCount; c++) {
                    var match = false;
                    var idx = instructions.gainSetIndex[c];
                    var factor = instructions.duckingModifiersForChannel[c].duckingScaling;
                    if (idx >= 0) {
                        for (var n = 0; n < g; n++) {
                            if (uniqueIndex[n] == idx && uniqueScaling[n] == factor) {
                                match = true;
                                instructions.channelGroupForChannel[c] = n;
                                break;
                            }
                        }
                        if (!match) {
                            uniqueIndex[g] = idx;
                            uniqueScaling[g] = factor;
                            instructions.channelGroupForChannel[c] = g;
                            g++;
                        }
                    } else {
                        instructions.channelGroupForChannel[c] = -1;
                    }
                }
                instructions.nDrcChannelGroups = g;
            }
            checkCount(instructions.nDrcChannelGroups, CHANNEL_GROUP_COUNT_MAX, "channel groups");
            for (g = 0; g < instructions.nDrcChannelGroups; g++) {
                var set = (instructions.drcSetEffect & EFFECT_BIT_DUCK_OTHER) != 0 ? duckingSequence : uniqueIndex[g];
                instructions.gainSetIndexForChannelGroup[g] = set;
                instructions.duckingModifiersForChannelGroup[g].duckingScaling = uniqueScaling[g];
                instructions.duckingModifiersForChannelGroup[g].duckingScalingPresent = uniqueScaling[g] != 1.0f ? 1
                        : 0;
                instructions.bandCountForChannelGroup[g] = 1;
            }
        } else {
            if ((version == 0 || instructions.drcApplyToDownmix != 0) && instructions.downmixId[0] != 0
                    && instructions.downmixId[0] != ID_FOR_ANY_DOWNMIX && instructions.downmixIdCount == 1) {
                var i = 0;
                for (; i < config.downmixInstructionsCount; i++) {
                    if (instructions.downmixId[0] == config.downmixInstructions[i].downmixId) {
                        break;
                    }
                }
                if (i == config.downmixInstructionsCount) {
                    throw new MediaException.Malformed("MPEG-D DRC refers to missing downmix instructions");
                }
                drcChannelCount = config.downmixInstructions[i].targetChannelCount;
            } else if ((version == 0 || instructions.drcApplyToDownmix != 0)
                    && (instructions.downmixId[0] == ID_FOR_ANY_DOWNMIX || instructions.downmixIdCount > 1)) {
                drcChannelCount = 1;
            }
            var c = 0;
            while (c < drcChannelCount) {
                var bsGainSetIndex = bits(reader, 6);
                instructions.gainSetIndex[c] = bsGainSetIndex - 1;
                c++;
                if (bits(reader, 1) == 1) {
                    var repeat = bits(reader, 5) + 1;
                    if (c + repeat > CHANNEL_COUNT_MAX) {
                        throw new MediaException.Malformed("MPEG-D DRC repeats parameters past the channel count");
                    }
                    for (var k = 0; k < repeat; k++) {
                        instructions.gainSetIndex[c] = bsGainSetIndex - 1;
                        c++;
                    }
                }
            }
            if (c > drcChannelCount) {
                throw new MediaException.Malformed("MPEG-D DRC repeats parameters past the channel count");
            }
            var g = 0;
            if (instructions.downmixId[0] == ID_FOR_ANY_DOWNMIX || instructions.downmixIdCount > 1) {
                var idx = instructions.gainSetIndex[0];
                if (idx >= 0) {
                    uniqueIndex[0] = idx;
                    g = 1;
                }
            } else {
                for (c = 0; c < drcChannelCount; c++) {
                    var idx = instructions.gainSetIndex[c];
                    var match = false;
                    if (idx >= 0) {
                        for (var n = 0; n < g; n++) {
                            if (uniqueIndex[n] == idx) {
                                match = true;
                                instructions.channelGroupForChannel[c] = n;
                                break;
                            }
                        }
                        if (!match) {
                            uniqueIndex[g] = idx;
                            instructions.channelGroupForChannel[c] = g;
                            g++;
                        }
                    } else {
                        instructions.channelGroupForChannel[c] = -1;
                    }
                }
            }
            checkCount(g, CHANNEL_GROUP_COUNT_MAX, "channel groups");
            instructions.nDrcChannelGroups = g;
            for (g = 0; g < instructions.nDrcChannelGroups; g++) {
                var set = uniqueIndex[g];
                instructions.gainSetIndexForChannelGroup[g] = set;
                var bandCount = coefficients != null && set < coefficients.gainSetCount
                        ? coefficients.gainSetParams[set].bandCount : 1;
                decGainModifiers(reader, version, bandCount, instructions.gainModifiersForChannelGroup[g]);
            }
        }
    }

    private static void parseGainSetParamsCharacteristics(AacBitReader reader, int version, GainParams params) {
        if (version == 0) {
            params.drcCharacteristic = bits(reader, 7);
            if (params.drcCharacteristic > 0) {
                params.drcCharacteristicPresent = 1;
                params.drcCharacteristicFormatIsCICP = 1;
            } else {
                params.drcCharacteristicPresent = 0;
            }
        } else {
            params.drcCharacteristicPresent = bits(reader, 1);
            if (params.drcCharacteristicPresent != 0) {
                params.drcCharacteristicFormatIsCICP = bits(reader, 1);
                if (params.drcCharacteristicFormatIsCICP != 0) {
                    params.drcCharacteristic = bits(reader, 7);
                } else {
                    params.drcCharacteristicLeftIndex = bits(reader, 4);
                    params.drcCharacteristicRightIndex = bits(reader, 4);
                }
            }
        }
    }

    private static int parseGainSetParams(AacBitReader reader, int version, int gainSequenceIndex,
                                          GainSetParams set) {
        set.gainCodingProfile = bits(reader, 2);
        set.gainInterpolationType = bits(reader, 1);
        set.fullFrame = bits(reader, 1);
        set.timeAlignment = bits(reader, 1);
        set.timeDeltaMinPresent = bits(reader, 1);
        if (set.timeDeltaMinPresent != 0) {
            set.timeDeltaMin = bits(reader, 11) + 1;
        }
        if (set.gainCodingProfile == GAIN_CODING_PROFILE_CONSTANT) {
            set.bandCount = 1;
            gainSequenceIndex++;
            set.gainParams[0].gainSequenceIndex = gainSequenceIndex;
        } else {
            set.bandCount = bits(reader, 4);
            if (set.bandCount > BAND_COUNT_MAX) {
                throw new MediaException.Malformed("MPEG-D DRC gain set has more bands than supported");
            }
            if (set.bandCount > 1) {
                set.drcBandType = bits(reader, 1);
            }
            for (var i = 0; i < set.bandCount; i++) {
                if (version == 0) {
                    gainSequenceIndex++;
                } else {
                    if (bits(reader, 1) != 0) {
                        gainSequenceIndex = bits(reader, 6);
                    } else {
                        gainSequenceIndex++;
                    }
                }
                if (gainSequenceIndex >= SEQUENCE_COUNT_MAX) {
                    throw new MediaException.Malformed("MPEG-D DRC declares more gain sequences than supported");
                }
                set.gainParams[i].gainSequenceIndex = gainSequenceIndex;
                parseGainSetParamsCharacteristics(reader, version, set.gainParams[i]);
            }
            for (var i = 1; i < set.bandCount; i++) {
                if (set.drcBandType != 0) {
                    set.gainParams[i].crossoverFreqIndex = bits(reader, 4);
                } else {
                    set.gainParams[i].startSubBandIndex = bits(reader, 10);
                }
            }
        }
        return gainSequenceIndex;
    }

    private static void parseSplitDrcCharacteristic(AacBitReader reader, int side, SplitDrcCharacteristic out) {
        out.characteristicFormat = bits(reader, 1);
        if (out.characteristicFormat == 0) {
            var bsGain = bits(reader, 6);
            out.gain = side == LEFT_SIDE ? bsGain : -bsGain;
            out.ioRatio = 0.05f + 0.15f * bits(reader, 4);
            var bsExp = bits(reader, 4);
            out.exp = bsExp < 15 ? 1.0f + 2.0f * bsExp : 1000.0f;
            out.flipSign = bits(reader, 1);
        } else {
            out.characteristicNodeCount = bits(reader, 2) + 1;
            out.nodeLevel[0] = DRC_INPUT_LOUDNESS_TARGET;
            out.nodeGain[0] = 0.0f;
            for (var i = 1; i <= out.characteristicNodeCount; i++) {
                var delta = bits(reader, 5);
                if (side == LEFT_SIDE) {
                    out.nodeLevel[i] = out.nodeLevel[i - 1] - (1.0f + delta);
                } else {
                    out.nodeLevel[i] = out.nodeLevel[i - 1] + (1.0f + delta);
                }
                out.nodeGain[i] = 0.5f * bits(reader, 8) - 64.0f;
            }
        }
    }

    private static void parseShapeFilterParams(AacBitReader reader, ShapeFilterParams params) {
        params.cornerFreqIndex = bits(reader, 3);
        params.filterStrengthIndex = bits(reader, 2);
    }

    private void initGainSetTables(GainSetParams set) {
        if (set.timeDeltaMinPresent != 0) {
            if (set.timeDeltaMin > drcParams.drcFrameSize) {
                throw new MediaException.Malformed("MPEG-D DRC time interval exceeds the frame size");
            }
            set.nGainValuesMax = drcParams.drcFrameSize / set.timeDeltaMin;
            set.tables = new Tables();
            generateDeltaTimeCodeTable(set.nGainValuesMax, set.tables);
        }
    }

    private void parseDrcCoefficientsUniDrc(AacBitReader reader, int version, DrcCoefficientsUniDrc coefficients) {
        var gainSequenceIndex = -1;
        coefficients.version = version;
        if (version == 0) {
            var gainSequenceCount = 0;
            coefficients.drcLocation = bits(reader, 4);
            coefficients.drcFrameSizePresent = bits(reader, 1);
            if (coefficients.drcFrameSizePresent == 1) {
                coefficients.drcFrameSize = bits(reader, 15) + 1;
            }
            coefficients.drcCharacteristicLeftPresent = 0;
            coefficients.drcCharacteristicRightPresent = 0;
            coefficients.shapeFiltersPresent = 0;
            coefficients.gainSetCount = bits(reader, 6);
            checkCount(coefficients.gainSetCount, GAIN_SET_COUNT_MAX, "gain sets");
            coefficients.gainSetCountPlus = coefficients.gainSetCount;
            for (var i = 0; i < coefficients.gainSetCount; i++) {
                gainSequenceIndex = parseGainSetParams(reader, version, gainSequenceIndex,
                        coefficients.gainSetParams[i]);
                initGainSetTables(coefficients.gainSetParams[i]);
                gainSequenceCount += coefficients.gainSetParams[i].bandCount;
            }
            coefficients.gainSequenceCount = gainSequenceCount;
        } else {
            java.util.Arrays.fill(coefficients.gainSetParamsIndexForGainSequence, -1);
            coefficients.drcLocation = bits(reader, 4);
            coefficients.drcFrameSizePresent = bits(reader, 1);
            if (coefficients.drcFrameSizePresent == 1) {
                coefficients.drcFrameSize = bits(reader, 15) + 1;
            }
            coefficients.drcCharacteristicLeftPresent = bits(reader, 1);
            if (coefficients.drcCharacteristicLeftPresent == 1) {
                coefficients.characteristicLeftCount = bits(reader, 4);
                checkCount(coefficients.characteristicLeftCount + 1, SPLIT_CHARACTERISTIC_COUNT_MAX + 8,
                        "characteristics");
                for (var i = 1; i <= coefficients.characteristicLeftCount; i++) {
                    parseSplitDrcCharacteristic(reader, LEFT_SIDE, characteristic(coefficients.splitCharacteristicLeft,
                            i));
                }
            }
            coefficients.drcCharacteristicRightPresent = bits(reader, 1);
            if (coefficients.drcCharacteristicRightPresent == 1) {
                coefficients.characteristicRightCount = bits(reader, 4);
                for (var i = 1; i <= coefficients.characteristicRightCount; i++) {
                    parseSplitDrcCharacteristic(reader, RIGHT_SIDE,
                            characteristic(coefficients.splitCharacteristicRight, i));
                }
            }
            coefficients.shapeFiltersPresent = bits(reader, 1);
            if (coefficients.shapeFiltersPresent == 1) {
                coefficients.shapeFilterCount = bits(reader, 4);
                for (var i = 1; i <= coefficients.shapeFilterCount; i++) {
                    var block = coefficients.shapeFilterBlockParams[Math.min(i, SHAPE_FILTER_COUNT_MAX)];
                    block.lfCutFilterPresent = bits(reader, 1);
                    if (block.lfCutFilterPresent == 1) {
                        parseShapeFilterParams(reader, block.lfCutParams);
                    }
                    block.lfBoostFilterPresent = bits(reader, 1);
                    if (block.lfBoostFilterPresent == 1) {
                        parseShapeFilterParams(reader, block.lfBoostParams);
                    }
                    block.hfCutFilterPresent = bits(reader, 1);
                    if (block.hfCutFilterPresent == 1) {
                        parseShapeFilterParams(reader, block.hfCutParams);
                    }
                    block.hfBoostFilterPresent = bits(reader, 1);
                    if (block.hfBoostFilterPresent == 1) {
                        parseShapeFilterParams(reader, block.hfBoostParams);
                    }
                }
            }
            coefficients.gainSequenceCount = bits(reader, 6);
            coefficients.gainSetCount = bits(reader, 6);
            checkCount(coefficients.gainSetCount, GAIN_SET_COUNT_MAX, "gain sets");
            checkCount(coefficients.gainSequenceCount, SEQUENCE_COUNT_MAX, "gain sequences");
            coefficients.gainSetCountPlus = coefficients.gainSetCount;
            for (var i = 0; i < coefficients.gainSetCount; i++) {
                gainSequenceIndex = parseGainSetParams(reader, version, gainSequenceIndex,
                        coefficients.gainSetParams[i]);
                initGainSetTables(coefficients.gainSetParams[i]);
            }
        }
        for (var i = 0; i < coefficients.gainSetCount; i++) {
            var set = coefficients.gainSetParams[i];
            for (var b = 0; b < set.bandCount; b++) {
                coefficients.gainSetParamsIndexForGainSequence[set.gainParams[b].gainSequenceIndex] = i;
            }
        }
    }

    private static SplitDrcCharacteristic characteristic(SplitDrcCharacteristic[] array, int index) {
        if (index >= array.length) {
            throw new MediaException.Malformed("MPEG-D DRC declares more characteristics than supported");
        }
        return array[index];
    }

    private void parseDownmixInstructions(AacBitReader reader, int version, ChannelLayout layout,
                                          DownmixInstructions instructions) {
        instructions.downmixId = bits(reader, 7);
        instructions.targetChannelCount = bits(reader, 7);
        instructions.targetLayout = bits(reader, 8);
        instructions.downmixCoefficientsPresent = bits(reader, 1);
        if (instructions.downmixCoefficientsPresent != 0) {
            if (instructions.targetChannelCount * layout.baseChannelCount > DOWNMIX_COEFF_COUNT_MAX) {
                throw new MediaException.Malformed("MPEG-D DRC downmix has more coefficients than supported");
            }
            if (version == 0) {
                var k = 0;
                for (var i = 0; i < instructions.targetChannelCount; i++) {
                    for (var j = 0; j < layout.baseChannelCount; j++) {
                        var bs = bits(reader, 4);
                        var db = drcParams.lfeChannelMap[j] != 0 ? DrcTables.DOWNMIX_COEFF_LFE[bs]
                                : DrcTables.DOWNMIX_COEFF[bs];
                        instructions.downmixCoefficient[k] = (float) StrictMath.pow(10.0f, 0.05f * db);
                        k++;
                    }
                }
            } else {
                var bsDownmixOffset = bits(reader, 4);
                var k = 0;
                for (var i = 0; i < instructions.targetChannelCount; i++) {
                    for (var j = 0; j < layout.baseChannelCount; j++) {
                        var bs = bits(reader, 5);
                        if (bs >= DrcTables.DOWNMIX_COEFF_V1.length) {
                            throw new MediaException.Malformed("MPEG-D DRC downmix coefficient " + bs
                                    + " is reserved");
                        }
                        instructions.downmixCoefficient[k] = DrcTables.DOWNMIX_COEFF_V1[bs];
                        k++;
                    }
                }
                var count = instructions.targetChannelCount * layout.baseChannelCount;
                float downmixOffset;
                switch (bsDownmixOffset) {
                    case 0 -> downmixOffset = 0.0f;
                    case 1 -> {
                        var a = (float) (20.0f * StrictMath.log10((float) instructions.targetChannelCount
                                / (float) layout.baseChannelCount));
                        downmixOffset = (float) (0.5f * StrictMath.floor(0.5f + a));
                    }
                    case 2 -> {
                        var a = (float) (20.0f * StrictMath.log10((float) instructions.targetChannelCount
                                / (float) layout.baseChannelCount));
                        downmixOffset = (float) (0.5f * StrictMath.floor(0.5f + 2.0f * a));
                    }
                    case 3 -> {
                        var sum = 0.0f;
                        for (k = 0; k < count; k++) {
                            sum += (float) StrictMath.pow(10.0f, 0.1f * instructions.downmixCoefficient[k]);
                        }
                        var b = (float) (10.0f * StrictMath.log10(sum));
                        downmixOffset = (float) (0.5f * StrictMath.floor(0.5f + 2.0f * b));
                    }
                    default -> throw new MediaException.Malformed("MPEG-D DRC downmix offset " + bsDownmixOffset
                            + " is reserved");
                }
                for (k = 0; k < count; k++) {
                    instructions.downmixCoefficient[k] = (float) StrictMath.pow(10.0f,
                            0.05f * (instructions.downmixCoefficient[k] + downmixOffset));
                }
            }
        }
    }

    private void parseChannelLayout(AacBitReader reader, ChannelLayout layout) {
        layout.baseChannelCount = bits(reader, 7);
        if (drcParams.lfeChannelMapCount != -1 && layout.baseChannelCount != drcParams.lfeChannelMapCount) {
            throw new MediaException.Malformed("MPEG-D DRC channel count does not match the low frequency map");
        }
        layout.layoutSignalingPresent = bits(reader, 1);
        if (layout.layoutSignalingPresent != 0) {
            layout.definedLayout = bits(reader, 8);
            if (layout.definedLayout == 0) {
                for (var i = 0; i < layout.baseChannelCount; i++) {
                    layout.speakerPosition[i] = bits(reader, 7);
                    drcParams.lfeChannelMap[i] = layout.speakerPosition[i] == 3 || layout.speakerPosition[i] == 26
                            ? 1 : 0;
                }
            }
        }
    }

    private static void initializeDrcCurve(int drcCharacteristic, ParametricDrcTypeFeedForward ff) {
        var level = ff.nodeLevel;
        var gain = ff.nodeGain;
        switch (drcCharacteristic) {
            case 7 -> {
                ff.nodeCount = 5;
                set(level, gain, -22, 6, -10, 0, 10, 0, 20, -5, 40, -24);
            }
            case 8 -> {
                ff.nodeCount = 5;
                set(level, gain, -12, 6, 0, 0, 5, 0, 15, -5, 35, -24);
            }
            case 9 -> {
                ff.nodeCount = 4;
                set(level, gain, -34, 12, -10, 0, 10, 0, 40, -15);
            }
            case 10 -> {
                ff.nodeCount = 5;
                set(level, gain, -24, 12, 0, 0, 5, 0, 15, -5, 35, -24);
            }
            case 11 -> {
                ff.nodeCount = 5;
                set(level, gain, -19, 15, 0, 0, 5, 0, 15, -5, 35, -24);
            }
            default -> ff.disableParamtricDrc = 1;
        }
    }

    private static void set(int[] level, int[] gain, int... pairs) {
        for (var i = 0; i < pairs.length / 2; i++) {
            level[i] = pairs[2 * i];
            gain[i] = pairs[2 * i + 1];
        }
    }

    private static void initializeGainSmooth(int drcCharacteristic, ParametricDrcTypeFeedForward ff) {
        ff.gainSmoothAttackTimeSlow = 100;
        ff.gainSmoothReleaseTimeSlow = switch (drcCharacteristic) {
            case 10 -> 10000;
            case 11 -> 1000;
            default -> 3000;
        };
        ff.gainSmoothTimeFastPresent = 1;
        ff.gainSmoothAttackTimeFast = 10;
        ff.gainSmoothReleaseTimeFast = drcCharacteristic == 11 ? 200 : 1000;
        ff.gainSmoothThresholdPresent = 1;
        ff.gainSmoothAttackThreshold = drcCharacteristic == 11 ? 10 : 15;
        ff.gainSmoothReleaseThreshold = drcCharacteristic == 11 ? 10 : 20;
        ff.gainSmoothHoldOffCountPresent = 1;
        ff.gainSmoothHoldOff = 10;
    }

    private static void parseParametricDrcTypeFeedForward(AacBitReader reader, int frameSize,
                                                          ParametricDrcTypeFeedForward ff) {
        ff.disableParamtricDrc = 0;
        ff.levelEstimKWeightingType = bits(reader, 2);
        ff.levelEstimIntegrationTimePresent = bits(reader, 1);
        if (ff.levelEstimIntegrationTimePresent != 0) {
            ff.levelEstimIntegrationTime = (bits(reader, 6) + 1) * frameSize;
        } else {
            ff.levelEstimIntegrationTime = frameSize;
        }
        ff.drcCurveDefinitionType = bits(reader, 1);
        if (ff.drcCurveDefinitionType == 0) {
            ff.drcCharacteristic = bits(reader, 7);
            initializeDrcCurve(ff.drcCharacteristic, ff);
        } else {
            ff.drcCharacteristic = 0;
            ff.nodeCount = bits(reader, 3) + 2;
            for (var i = 0; i < ff.nodeCount; i++) {
                if (i == 0) {
                    ff.nodeLevel[0] = -11 - bits(reader, 6);
                } else {
                    ff.nodeLevel[i] = ff.nodeLevel[i - 1] + 1 + bits(reader, 5);
                }
                ff.nodeGain[i] = bits(reader, 6) - 39;
            }
        }
        initializeGainSmooth(ff.drcCharacteristic, ff);
        ff.drcGainSmoothParametersPresent = bits(reader, 1);
        if (ff.drcGainSmoothParametersPresent != 0) {
            ff.gainSmoothAttackTimeSlow = bits(reader, 8) * 5;
            ff.gainSmoothReleaseTimeSlow = bits(reader, 8) * 40;
            ff.gainSmoothTimeFastPresent = bits(reader, 1);
            if (ff.gainSmoothTimeFastPresent != 0) {
                ff.gainSmoothAttackTimeFast = bits(reader, 8) * 5;
                ff.gainSmoothReleaseTimeFast = bits(reader, 8) * 20;
                ff.gainSmoothThresholdPresent = bits(reader, 1);
                if (ff.gainSmoothThresholdPresent != 0) {
                    ff.gainSmoothAttackThreshold = bits(reader, 5);
                    if (ff.gainSmoothAttackThreshold == 31) {
                        ff.gainSmoothAttackThreshold = 1000;
                    }
                    ff.gainSmoothReleaseThreshold = bits(reader, 5);
                    if (ff.gainSmoothReleaseThreshold == 31) {
                        ff.gainSmoothReleaseThreshold = 1000;
                    }
                }
            } else {
                ff.gainSmoothAttackTimeFast = ff.gainSmoothAttackTimeSlow;
                ff.gainSmoothReleaseTimeFast = ff.gainSmoothReleaseTimeSlow;
            }
            ff.gainSmoothHoldOffCountPresent = bits(reader, 1);
            if (ff.gainSmoothHoldOffCountPresent != 0) {
                ff.gainSmoothHoldOff = bits(reader, 7);
            }
        }
    }

    private static void parseParametricDrcTypeLim(AacBitReader reader, ParametricDrcTypeLim lim) {
        lim.disableParamtricDrc = 0;
        lim.parametricLimThresholdPresent = bits(reader, 1);
        if (lim.parametricLimThresholdPresent != 0) {
            lim.parametricLimThreshold = -bits(reader, 8) * 0.125f;
        } else {
            lim.parametricLimThreshold = PARAM_DRC_TYPE_LIM_THRESHOLD_DEFAULT;
        }
        lim.parametricLimReleasePresent = bits(reader, 1);
        if (lim.parametricLimReleasePresent != 0) {
            lim.parametricLimRelease = bits(reader, 8) * 10;
        } else {
            lim.parametricLimRelease = PARAM_DRC_TYPE_LIM_RELEASE_DEFAULT;
        }
        lim.parametricLimAttack = PARAM_DRC_TYPE_LIM_ATTACK_DEFAULT;
        lim.drcCharacteristic = 0;
    }

    private static void parseParametricDrcInstructions(AacBitReader reader, int frameSize,
                                                       ParametricDrcInstructions instructions) {
        instructions.drcCharacteristic = 0;
        instructions.disableParamtricDrc = 0;
        instructions.parametricDrcId = bits(reader, 4);
        instructions.parametricDrcLookAheadPresent = bits(reader, 1);
        instructions.parametricDrcLookAhead = instructions.parametricDrcLookAheadPresent != 0 ? bits(reader, 7) : 0;
        instructions.parametricDrcPresetIdPresent = bits(reader, 1);
        if (instructions.parametricDrcPresetIdPresent != 0) {
            instructions.parametricDrcPresetId = bits(reader, 7);
            if (instructions.parametricDrcPresetId <= 4) {
                instructions.drcCharacteristic = instructions.parametricDrcPresetId + 7;
                instructions.parametricDrcType = PARAM_DRC_TYPE_FF;
                var ff = instructions.parametricDrcTypeFeedForward;
                ff.levelEstimKWeightingType = 2;
                ff.levelEstimIntegrationTime = frameSize;
                initializeDrcCurve(instructions.drcCharacteristic, ff);
                initializeGainSmooth(instructions.drcCharacteristic, ff);
            } else {
                instructions.disableParamtricDrc = 1;
            }
        } else {
            instructions.parametricDrcType = bits(reader, 3);
            if (instructions.parametricDrcType == PARAM_DRC_TYPE_FF) {
                parseParametricDrcTypeFeedForward(reader, frameSize, instructions.parametricDrcTypeFeedForward);
                instructions.disableParamtricDrc = instructions.parametricDrcTypeFeedForward.disableParamtricDrc;
                instructions.drcCharacteristic = instructions.parametricDrcTypeFeedForward.drcCharacteristic;
            } else if (instructions.parametricDrcType == PARAM_DRC_TYPE_LIM) {
                parseParametricDrcTypeLim(reader, instructions.parametricDrcTypeLim);
                instructions.disableParamtricDrc = instructions.parametricDrcTypeLim.disableParamtricDrc;
                instructions.drcCharacteristic = instructions.parametricDrcTypeLim.drcCharacteristic;
                if (instructions.parametricDrcLookAheadPresent != 0) {
                    instructions.parametricDrcTypeLim.parametricLimAttack = instructions.parametricDrcLookAhead;
                }
            } else {
                var lenSizeBits = bits(reader, 3) + 4;
                instructions.lenBitSize = bits(reader, lenSizeBits) + 1;
                instructions.disableParamtricDrc = 1;
                skipBits(reader, instructions.lenBitSize);
            }
        }
    }

    private static void parseParametricDrcGainSetParams(AacBitReader reader, UniDrcConfig config,
                                                        ParametricDrcGainSetParams params) {
        params.parametricDrcId = bits(reader, 4);
        params.sideChainConfigType = bits(reader, 3);
        if (params.sideChainConfigType != 0) {
            params.downmixId = bits(reader, 7);
            params.levelEstimChannelWeightFormat = bits(reader, 1);
            if (params.downmixId == ID_FOR_BASE_LAYOUT) {
                params.channelCountFromDownmixId = config.channelLayout.baseChannelCount;
            } else if (params.downmixId == ID_FOR_ANY_DOWNMIX) {
                params.channelCountFromDownmixId = 1;
            } else {
                var i = 0;
                for (; i < config.downmixInstructionsCount; i++) {
                    if (params.downmixId == config.downmixInstructions[i].downmixId) {
                        break;
                    }
                }
                if (i == config.downmixInstructionsCount) {
                    throw new MediaException.Malformed("MPEG-D DRC refers to missing downmix instructions");
                }
                params.channelCountFromDownmixId = config.downmixInstructions[i].targetChannelCount;
            }
            for (var i = 0; i < params.channelCountFromDownmixId; i++) {
                if (params.levelEstimChannelWeightFormat == 0) {
                    params.levelEstimChannelWeight[i] = (float) bits(reader, 1);
                } else {
                    params.levelEstimChannelWeight[i] = (float) StrictMath.pow(10.0f,
                            0.05f * DrcTables.CHANNEL_WEIGHT[bits(reader, 4)]);
                }
            }
        } else {
            params.downmixId = 0;
            params.channelCountFromDownmixId = 0;
        }
        params.drcInputLoudnessPresent = bits(reader, 1);
        if (params.drcInputLoudnessPresent != 0) {
            params.drcInputLoudness = -57.75f + bits(reader, 8) * 0.25f;
        }
    }

    private static void parseDrcCoefficientsParametricDrc(AacBitReader reader, UniDrcConfig config,
                                                          DrcCoefficientsParametricDrc coefficients) {
        coefficients.drcLocation = bits(reader, 4);
        coefficients.parametricDrcFrameSizeFormat = bits(reader, 1);
        if (coefficients.parametricDrcFrameSizeFormat == 1) {
            coefficients.parametricDrcFrameSize = bits(reader, 15) + 1;
        } else {
            coefficients.parametricDrcFrameSize = 1 << bits(reader, 4);
        }
        coefficients.parametricDrcDelayMaxPresent = bits(reader, 1);
        if (coefficients.parametricDrcDelayMaxPresent != 0) {
            var mu = bits(reader, 5);
            var nu = bits(reader, 3);
            coefficients.parametricDrcDelayMax = 16 * mu * (1 << nu);
        }
        coefficients.resetParametricDrc = bits(reader, 1);
        coefficients.parametricDrcGainSetCount = bits(reader, 6);
        checkCount(coefficients.parametricDrcGainSetCount, SEQUENCE_COUNT_MAX, "parametric DRC gain sets");
        for (var i = 0; i < coefficients.parametricDrcGainSetCount; i++) {
            parseParametricDrcGainSetParams(reader, config, coefficients.parametricDrcGainSetParams[i]);
        }
    }

    private static void generateVirtualGainSetsForParametricDrc(UniDrcConfig config) {
        var parametric = config.uniDrcConfigExt.drcCoefficientsParametricDrc;
        var cV1 = -1;
        var cV0 = -1;
        for (var i = 0; i < config.drcCoefficientsUniDrcCount; i++) {
            if (config.drcCoefficientsUniDrc[i].drcLocation == parametric.drcLocation) {
                if (config.drcCoefficientsUniDrc[i].version == 0) {
                    cV0 = i;
                } else {
                    cV1 = i;
                }
            }
        }
        DrcCoefficientsUniDrc coefficients;
        if (cV1 >= 0) {
            coefficients = config.drcCoefficientsUniDrc[cV1];
        } else if (cV0 >= 0) {
            coefficients = config.drcCoefficientsUniDrc[cV0];
        } else {
            checkCount(config.drcCoefficientsUniDrcCount + 1, DRC_COEFF_COUNT_MAX, "DRC coefficients");
            coefficients = config.drcCoefficientsUniDrc[config.drcCoefficientsUniDrcCount];
            coefficients.version = 1;
            coefficients.drcLocation = parametric.drcLocation;
            coefficients.drcFrameSizePresent = 0;
            coefficients.gainSetCount = 0;
            coefficients.gainSetCountPlus = 0;
            coefficients.drcCharacteristicLeftPresent = 0;
            coefficients.drcCharacteristicRightPresent = 0;
            coefficients.shapeFiltersPresent = 0;
            coefficients.gainSequenceCount = 0;
            config.drcCoefficientsUniDrcCount += 1;
        }
        coefficients.gainSetCountPlus = coefficients.gainSetCount + parametric.parametricDrcGainSetCount;
        checkCount(coefficients.gainSetCountPlus, GAIN_SET_COUNT_MAX, "gain sets");
        for (var i = coefficients.gainSetCount; i < coefficients.gainSetCountPlus; i++) {
            coefficients.gainSetParams[i].bandCount = 1;
            var id = parametric.parametricDrcGainSetParams[i - coefficients.gainSetCount].parametricDrcId;
            var j = 0;
            for (; j < config.uniDrcConfigExt.parametricDrcInstructionsCount; j++) {
                if (id == config.uniDrcConfigExt.parametricDrcInstructions[j].parametricDrcId) {
                    break;
                }
            }
            if (j == config.uniDrcConfigExt.parametricDrcInstructionsCount) {
                throw new MediaException.Malformed("MPEG-D DRC refers to missing parametric DRC instructions");
            }
            var instructions = config.uniDrcConfigExt.parametricDrcInstructions[j];
            var characteristic = 0;
            if (instructions.parametricDrcPresetIdPresent != 0) {
                characteristic = instructions.drcCharacteristic;
            } else if (instructions.parametricDrcType == PARAM_DRC_TYPE_FF) {
                if (instructions.parametricDrcTypeFeedForward.drcCurveDefinitionType == 0) {
                    characteristic = instructions.parametricDrcTypeFeedForward.drcCharacteristic;
                }
            }
            var params = coefficients.gainSetParams[i].gainParams[0];
            if (characteristic != 0) {
                params.drcCharacteristicPresent = 1;
                params.drcCharacteristicFormatIsCICP = 1;
                params.drcCharacteristic = characteristic;
            } else {
                params.drcCharacteristicPresent = 0;
                params.drcCharacteristicFormatIsCICP = 0;
                params.drcCharacteristic = 0;
            }
        }
    }

    private static void parseFilterBlock(AacBitReader reader, FilterBlock block) {
        block.filterElementCount = bits(reader, 6);
        checkCount(block.filterElementCount, FILTER_ELEMENT_COUNT_MAX, "filter elements");
        for (var k = 0; k < block.filterElementCount; k++) {
            var element = block.filterElement[k];
            element.filterElementIndex = bits(reader, 6);
            element.filterElementGainPresent = bits(reader, 1);
            if (element.filterElementGainPresent != 0) {
                element.filterElementGain = bits(reader, 10) * 0.125f - 96.0f;
            }
        }
    }

    private static void parseUniqueTdFilterElement(AacBitReader reader, UniqueTdFilterElement element) {
        element.eqFilterFormat = bits(reader, 1);
        if (element.eqFilterFormat == 0) {
            element.realZeroRadiusOneCount = 2 * bits(reader, 3);
            element.realZeroCount = bits(reader, 6);
            element.genericZeroCount = bits(reader, 6);
            element.realPoleCount = bits(reader, 4);
            element.complexPoleCount = bits(reader, 4);
            for (var m = 0; m < element.realZeroRadiusOneCount; m++) {
                element.zeroSign[m] = bits(reader, 1);
            }
            for (var m = 0; m < element.realZeroCount; m++) {
                var radius = bits(reader, 7);
                var sign = bits(reader, 1);
                var tmp = 1.0f - DrcTables.ZERO_POLE_RADIUS[radius];
                element.realZeroRadius[m] = sign == 1 ? -tmp : tmp;
            }
            for (var m = 0; m < element.genericZeroCount; m++) {
                element.genericZeroRadius[m] = 1.0f - DrcTables.ZERO_POLE_RADIUS[bits(reader, 7)];
                element.genericZeroAngle[m] = DrcTables.ZERO_POLE_ANGLE[bits(reader, 7)];
            }
            for (var m = 0; m < element.realPoleCount; m++) {
                var radius = bits(reader, 7);
                var sign = bits(reader, 1);
                var tmp = 1.0f - DrcTables.ZERO_POLE_RADIUS[radius];
                element.realPoleRadius[m] = sign == 1 ? -tmp : tmp;
            }
            for (var m = 0; m < element.complexPoleCount; m++) {
                element.complexPoleRadius[m] = 1.0f - DrcTables.ZERO_POLE_RADIUS[bits(reader, 7)];
                element.complexPoleAngle[m] = DrcTables.ZERO_POLE_ANGLE[bits(reader, 7)];
            }
        } else {
            element.firFilterOrder = bits(reader, 7);
            element.firSymmetry = bits(reader, 1);
            for (var m = 0; m < element.firFilterOrder / 2 + 1; m++) {
                var sign = bits(reader, 1);
                var tmp = (float) StrictMath.pow(10.0f, -0.05f * bits(reader, 10) * 0.0625f);
                element.firCoefficient[m] = sign != 0 ? -tmp : tmp;
            }
        }
    }

    private static float decodeGainInitialCode(AacBitReader reader) {
        return switch (bits(reader, 2)) {
            case 0 -> 0.5f * bits(reader, 5) - 8.0f;
            case 1 -> {
                var v = bits(reader, 4);
                yield v < 8 ? v - 16.0f : (float) v;
            }
            case 2 -> {
                var v = bits(reader, 4);
                yield v < 8 ? 2.0f * v - 32.0f : 2.0f * v;
            }
            default -> 4.0f * bits(reader, 3) - 64.0f;
        };
    }

    private static void parseEqCoefficients(AacBitReader reader, EqCoefficients eq) {
        eq.eqDelayMaxPresent = bits(reader, 1);
        if (eq.eqDelayMaxPresent != 0) {
            var mu = bits(reader, 5);
            var nu = bits(reader, 3);
            eq.eqDelayMax = 16 * mu * (1 << nu);
        }
        eq.uniqueFilterBlockCount = bits(reader, 6);
        checkCount(eq.uniqueFilterBlockCount, FILTER_BLOCK_COUNT_MAX, "filter blocks");
        for (var j = 0; j < eq.uniqueFilterBlockCount; j++) {
            parseFilterBlock(reader, eq.filterBlock[j]);
        }
        eq.uniqueTdFilterElementCount = bits(reader, 6);
        checkCount(eq.uniqueTdFilterElementCount, FILTER_ELEMENT_COUNT_MAX, "filter elements");
        for (var j = 0; j < eq.uniqueTdFilterElementCount; j++) {
            parseUniqueTdFilterElement(reader, eq.uniqueTdFilterElement[j]);
        }
        eq.uniqueEqSubbandGainsCount = bits(reader, 6);
        checkCount(eq.uniqueEqSubbandGainsCount, UNIQUE_SUBBAND_GAIN_COUNT_MAX, "subband gains");
        if (eq.uniqueEqSubbandGainsCount > 0) {
            eq.eqSubbandGainRepresentation = bits(reader, 1);
            eq.eqSubbandGainFormat = bits(reader, 4);
            eq.eqSubbandGainCount = switch (eq.eqSubbandGainFormat) {
                case GAINFORMAT_QMF32 -> 32;
                case GAINFORMAT_QMFHYBRID39 -> 39;
                case GAINFORMAT_QMF64 -> 64;
                case GAINFORMAT_QMFHYBRID71 -> 71;
                case GAINFORMAT_QMF128 -> 128;
                case GAINFORMAT_QMFHYBRID135 -> 135;
                default -> bits(reader, 8) + 1;
            };
            checkCount(eq.eqSubbandGainCount, EQ_SUBBAND_GAIN_COUNT_MAX, "subband gains");
            for (var k = 0; k < eq.uniqueEqSubbandGainsCount; k++) {
                if (eq.eqSubbandGainRepresentation == 1) {
                    var spline = eq.eqSubbandGainSpline[k];
                    spline.nEqNodes = bits(reader, 5) + 2;
                    for (var n = 0; n < spline.nEqNodes; n++) {
                        spline.eqSlope[n] = bits(reader, 1) == 1 ? 0.0f : DrcTables.EQ_SLOPE[bits(reader, 4)];
                    }
                    for (var n = 1; n < spline.nEqNodes; n++) {
                        spline.eqFreqDelta[n] = bits(reader, 4) + 1;
                    }
                    spline.eqGainInitial = decodeGainInitialCode(reader);
                    for (var n = 1; n < spline.nEqNodes; n++) {
                        spline.eqGainDelta[n] = DrcTables.EQ_GAIN_DELTA[bits(reader, 5)];
                    }
                } else {
                    var vector = eq.eqSubbandGainVector[k];
                    for (var m = 0; m < eq.eqSubbandGainCount; m++) {
                        var sign = bits(reader, 1);
                        var gain = bits(reader, 8);
                        vector.eqSubbandGain[m] = (sign != 0 ? -gain : gain) * 0.125f;
                    }
                }
            }
        }
    }

    private static void parseTdFilterCascade(AacBitReader reader, EqInstructions eq, TdFilterCascade cascade) {
        for (var i = 0; i < eq.eqChannelGroupCount; i++) {
            cascade.eqCascadeGainPresent[i] = bits(reader, 1);
            if (cascade.eqCascadeGainPresent[i] != 0) {
                cascade.eqCascadeGain[i] = 0.125f * bits(reader, 10) - 96.0f;
            } else {
                cascade.eqCascadeGain[i] = 0.0f;
            }
            var refs = cascade.filterBlockRefs[i];
            refs.filterBlockCount = bits(reader, 4);
            checkCount(refs.filterBlockCount, EQ_FILTER_BLOCK_COUNT_MAX, "filter block references");
            for (var k = 0; k < refs.filterBlockCount; k++) {
                refs.filterBlockIndex[k] = bits(reader, 7);
            }
        }
        cascade.eqPhaseAlignmentPresent = bits(reader, 1);
        for (var i = 0; i < eq.eqChannelGroupCount; i++) {
            for (var k = i + 1; k < eq.eqChannelGroupCount; k++) {
                cascade.eqPhaseAlignment[i][k] = cascade.eqPhaseAlignmentPresent != 0 ? bits(reader, 1) : 1;
            }
        }
    }

    private static void parseEqInstructions(AacBitReader reader, UniDrcConfig config, EqInstructions eq) {
        var additionalDownmixIdCount = 0;
        eq.eqSetId = bits(reader, 6);
        eq.eqSetComplexityLevel = bits(reader, 4);
        var downmixIdPresent = bits(reader, 1);
        if (downmixIdPresent != 0) {
            eq.downmixId[0] = bits(reader, 7);
            eq.eqApplyToDownmix = bits(reader, 1);
            if (bits(reader, 1) != 0) {
                additionalDownmixIdCount = bits(reader, 7);
                checkCount(additionalDownmixIdCount + 1, DOWNMIX_ID_COUNT_MAX, "downmix identifiers");
                for (var i = 0; i < additionalDownmixIdCount; i++) {
                    eq.downmixId[1 + i] = bits(reader, 7);
                }
            }
        } else {
            eq.downmixId[0] = 0;
        }
        eq.downmixIdCount = 1 + additionalDownmixIdCount;
        eq.drcSetId[0] = bits(reader, 6);
        var additionalDrcSetIdCount = 0;
        if (bits(reader, 1) != 0) {
            additionalDrcSetIdCount = bits(reader, 6);
            checkCount(additionalDrcSetIdCount + 1, DRC_SET_ID_COUNT_MAX, "DRC set identifiers");
            for (var i = 0; i < additionalDrcSetIdCount; i++) {
                eq.drcSetId[1 + i] = bits(reader, 6);
            }
        }
        eq.drcSetIdCount = 1 + additionalDrcSetIdCount;
        eq.eqSetPurpose = bits(reader, 16);
        eq.dependsOnEqSetPresent = bits(reader, 1);
        if (eq.dependsOnEqSetPresent != 0) {
            eq.dependsOnEqSet = bits(reader, 6);
        } else {
            eq.noIndependentEqUse = bits(reader, 1);
        }
        var channelCount = config.channelLayout.baseChannelCount;
        eq.eqChannelCount = channelCount;
        if (downmixIdPresent == 1 && eq.eqApplyToDownmix == 1 && eq.downmixId[0] != 0
                && eq.downmixId[0] != ID_FOR_ANY_DOWNMIX && eq.downmixIdCount == 1) {
            var i = 0;
            for (; i < config.downmixInstructionsCount; i++) {
                if (eq.downmixId[0] == config.downmixInstructions[i].downmixId) {
                    break;
                }
            }
            if (i == config.downmixInstructionsCount) {
                throw new MediaException.Malformed("MPEG-D DRC refers to missing downmix instructions");
            }
            channelCount = config.downmixInstructions[i].targetChannelCount;
            eq.eqChannelCount = channelCount;
        } else if (eq.downmixId[0] == ID_FOR_ANY_DOWNMIX || eq.downmixIdCount > 1) {
            channelCount = 1;
        }
        eq.eqChannelGroupCount = 0;
        for (var i = 0; i < channelCount; i++) {
            var newGroup = true;
            eq.eqChannelGroupForChannel[i] = bits(reader, 7);
            for (var k = 0; k < i; k++) {
                if (eq.eqChannelGroupForChannel[i] == eq.eqChannelGroupForChannel[k]) {
                    newGroup = false;
                }
            }
            if (newGroup) {
                eq.eqChannelGroupCount += 1;
            }
        }
        checkCount(eq.eqChannelGroupCount, EQ_CHANNEL_GROUP_COUNT_MAX, "equalizer channel groups");
        eq.tdFilterCascadePresent = bits(reader, 1);
        if (eq.tdFilterCascadePresent != 0) {
            parseTdFilterCascade(reader, eq, eq.tdFilterCascade);
        }
        eq.subbandGainsPresent = bits(reader, 1);
        if (eq.subbandGainsPresent != 0) {
            for (var i = 0; i < eq.eqChannelGroupCount; i++) {
                eq.subbandGainsIndex[i] = bits(reader, 6);
            }
        }
        eq.eqTransitionDurationPresent = bits(reader, 1);
        if (eq.eqTransitionDurationPresent != 0) {
            eq.eqTransitionDuration = (float) (0.001f * StrictMath.pow(2.0f, 2.0f + bits(reader, 5) * 0.0625f));
        }
    }

    private static int idList(AacBitReader reader, int[] ids, int bitsPerId, int countBits, int max) {
        var count = 0;
        ids[0] = bits(reader, bitsPerId);
        if (bits(reader, 1) != 0) {
            count = bits(reader, countBits);
            checkCount(count + 1, max, "identifiers");
            for (var i = 0; i < count; i++) {
                ids[i + 1] = bits(reader, bitsPerId);
            }
        }
        return 1 + count;
    }

    private static void parseLoudEqInstructions(AacBitReader reader, LoudEqInstructions loudEq) {
        loudEq.loudEqSetId = bits(reader, 4);
        loudEq.drcLocation = bits(reader, 4);
        if (bits(reader, 1) != 0) {
            loudEq.downmixIdCount = idList(reader, loudEq.downmixId, 7, 7, DOWNMIX_ID_COUNT_MAX);
        } else {
            loudEq.downmixId[0] = 0;
            loudEq.downmixIdCount = 1;
        }
        if (bits(reader, 1) != 0) {
            loudEq.drcSetIdCount = idList(reader, loudEq.drcSetId, 6, 6, DRC_SET_ID_COUNT_MAX);
        } else {
            loudEq.drcSetId[0] = 0;
            loudEq.drcSetIdCount = 1;
        }
        if (bits(reader, 1) != 0) {
            loudEq.eqSetIdCount = idList(reader, loudEq.eqSetId, 6, 6, EQ_SET_ID_COUNT_MAX);
        } else {
            loudEq.eqSetId[0] = 0;
            loudEq.eqSetIdCount = 1;
        }
        loudEq.loudnessAfterDrc = bits(reader, 1);
        loudEq.loudnessAfterEq = bits(reader, 1);
        loudEq.loudEqGainSequenceCount = bits(reader, 6);
        checkCount(loudEq.loudEqGainSequenceCount, LOUD_EQ_GAIN_SEQUENCE_COUNT_MAX, "loudness EQ gain sequences");
        for (var i = 0; i < loudEq.loudEqGainSequenceCount; i++) {
            loudEq.gainSequenceIndex[i] = bits(reader, 6);
            loudEq.drcCharacteristicFormatIsCICP[i] = bits(reader, 1);
            if (loudEq.drcCharacteristicFormatIsCICP[i] != 0) {
                loudEq.drcCharacteristic[i] = bits(reader, 7);
            } else {
                loudEq.drcCharacteristicLeftIndex[i] = bits(reader, 4);
                loudEq.drcCharacteristicRightIndex[i] = bits(reader, 4);
            }
            loudEq.frequencyRangeIndex[i] = bits(reader, 6);
            loudEq.loudEqScaling[i] = (float) StrictMath.pow(2.0f, -0.5f * bits(reader, 3));
            loudEq.loudEqOffset[i] = 1.5f * bits(reader, 5) - 16.0f;
        }
    }

    private void parseDrcExtensionV1(AacBitReader reader, UniDrcConfig config, UniDrcConfigExt ext) {
        var version = 1;
        if (bits(reader, 1) == 1) {
            var count = bits(reader, 7);
            checkCount(config.downmixInstructionsCount + count, DOWNMIX_INSTRUCTION_COUNT_MAX, "downmix instructions");
            for (var i = 0; i < count; i++) {
                parseDownmixInstructions(reader, version, config.channelLayout,
                        config.downmixInstructions[i + config.downmixInstructionsCount]);
            }
            config.downmixInstructionsCount += count;
        }
        if (bits(reader, 1) == 1) {
            var count = bits(reader, 3);
            checkCount(config.drcCoefficientsUniDrcCount + count, DRC_COEFF_COUNT_MAX, "DRC coefficients");
            for (var i = 0; i < count; i++) {
                parseDrcCoefficientsUniDrc(reader, version,
                        config.drcCoefficientsUniDrc[i + config.drcCoefficientsUniDrcCount]);
            }
            config.drcCoefficientsUniDrcCount += count;
            var instructionsCount = bits(reader, 6);
            checkCount(config.drcInstructionsUniDrcCount + instructionsCount + DOWNMIX_INSTRUCTION_COUNT_MAX + 1,
                    DRC_INSTRUCTIONS_COUNT_MAX, "DRC instructions");
            for (var i = 0; i < instructionsCount; i++) {
                parseDrcInstructionsUniDrc(reader, version, config,
                        config.drcInstructionsUniDrc[i + config.drcInstructionsUniDrcCount]);
            }
            config.drcInstructionsUniDrcCount += instructionsCount;
        }
        ext.loudEqInstructionsPresent = bits(reader, 1);
        if (ext.loudEqInstructionsPresent == 1) {
            ext.loudEqInstructionsCount = bits(reader, 4);
            checkCount(ext.loudEqInstructionsCount, LOUD_EQ_INSTRUCTIONS_COUNT_MAX, "loudness EQ instructions");
            for (var i = 0; i < ext.loudEqInstructionsCount; i++) {
                parseLoudEqInstructions(reader, ext.loudEqInstructions[i]);
            }
        } else {
            ext.loudEqInstructionsCount = 0;
        }
        ext.eqPresent = bits(reader, 1);
        if (ext.eqPresent == 1) {
            parseEqCoefficients(reader, ext.eqCoefficients);
            ext.eqInstructionsCount = bits(reader, 4);
            checkCount(ext.eqInstructionsCount, EQ_INSTRUCTIONS_COUNT_MAX, "EQ instructions");
            for (var i = 0; i < ext.eqInstructionsCount; i++) {
                parseEqInstructions(reader, config, ext.eqInstructions[i]);
            }
        }
    }

    private void parseUniDrcConfigExtension(AacBitReader reader, UniDrcConfig config, UniDrcConfigExt ext) {
        var k = 0;
        ext.uniDrcConfigExtType[k] = bits(reader, 4);
        while (ext.uniDrcConfigExtType[k] != UNIDRCCONFEXT_TERM) {
            var extSizeBits = bits(reader, 4) + 4;
            ext.extBitSize[k] = bits(reader, extSizeBits) + 1;
            switch (ext.uniDrcConfigExtType[k]) {
                case UNIDRCCONFEXT_PARAM_DRC -> {
                    ext.parametricDrcPresent = 1;
                    parseDrcCoefficientsParametricDrc(reader, config, ext.drcCoefficientsParametricDrc);
                    ext.parametricDrcInstructionsCount = bits(reader, 4);
                    checkCount(ext.parametricDrcInstructionsCount, PARAM_DRC_INSTRUCTIONS_COUNT_MAX,
                            "parametric DRC instructions");
                    for (var i = 0; i < ext.parametricDrcInstructionsCount; i++) {
                        parseParametricDrcInstructions(reader,
                                ext.drcCoefficientsParametricDrc.parametricDrcFrameSize,
                                ext.parametricDrcInstructions[i]);
                    }
                }
                case UNIDRCCONFEXT_V1 -> {
                    ext.drcExtensionV1Present = 1;
                    parseDrcExtensionV1(reader, config, ext);
                }
                default -> skipBits(reader, ext.extBitSize[k]);
            }
            k = Math.min(k + 1, ext.uniDrcConfigExtType.length - 1);
            ext.uniDrcConfigExtType[k] = bits(reader, 4);
        }
    }

    /**
     * Parses {@code uniDrcConfig()} into a configuration ({@code parseUniDrcConfig}).
     *
     * @param reader the bit source
     * @param config receives the configuration
     * @throws MediaException.Malformed if the configuration is invalid
     */
    void parseUniDrcConfig(AacBitReader reader, UniDrcConfig config) {
        var version = 0;
        config.sampleRatePresent = bits(reader, 1);
        if (config.sampleRatePresent == 1) {
            config.sampleRate = bits(reader, 18) + 1000;
        } else {
            config.sampleRate = drcParams.sampleRateDefault;
        }
        config.downmixInstructionsCount = bits(reader, 7);
        checkCount(config.downmixInstructionsCount, DOWNMIX_INSTRUCTION_COUNT_MAX, "downmix instructions");
        config.drcDescriptionBasicPresent = bits(reader, 1);
        if (config.drcDescriptionBasicPresent == 1) {
            config.drcCoefficientsBasicCount = bits(reader, 3);
            config.drcInstructionsBasicCount = bits(reader, 4);
        } else {
            config.drcCoefficientsBasicCount = 0;
            config.drcInstructionsBasicCount = 0;
        }
        config.drcCoefficientsUniDrcCount = bits(reader, 3);
        config.drcInstructionsUniDrcCount = bits(reader, 6);
        checkCount(config.drcInstructionsUniDrcCount + DOWNMIX_INSTRUCTION_COUNT_MAX + 1, DRC_INSTRUCTIONS_COUNT_MAX,
                "DRC instructions");
        parseChannelLayout(reader, config.channelLayout);
        for (var i = 0; i < config.downmixInstructionsCount; i++) {
            parseDownmixInstructions(reader, version, config.channelLayout, config.downmixInstructions[i]);
        }
        for (var i = 0; i < config.drcCoefficientsBasicCount; i++) {
            config.drcCoefficientsBasic[i].drcLocation = bits(reader, 4);
            config.drcCoefficientsBasic[i].drcCharacteristic = bits(reader, 7);
        }
        for (var i = 0; i < config.drcInstructionsBasicCount; i++) {
            parseDrcInstructionsBasic(reader, config.drcInstructionsBasic[i]);
        }
        for (var i = 0; i < config.drcCoefficientsUniDrcCount; i++) {
            parseDrcCoefficientsUniDrc(reader, version, config.drcCoefficientsUniDrc[i]);
        }
        for (var i = 0; i < config.drcInstructionsUniDrcCount; i++) {
            parseDrcInstructionsUniDrc(reader, version, config, config.drcInstructionsUniDrc[i]);
        }
        config.uniDrcConfigExtPresent = bits(reader, 1);
        if (config.uniDrcConfigExtPresent == 1) {
            parseUniDrcConfigExtension(reader, config, config.uniDrcConfigExt);
        }
        if (config.uniDrcConfigExt.parametricDrcPresent != 0) {
            generateVirtualGainSetsForParametricDrc(config);
        }
        for (var i = 0; i < config.drcInstructionsUniDrcCount; i++) {
            generateDrcInstructionsDerivedData(config, config.drcInstructionsUniDrc[i]);
        }
        generateDrcInstructionsForDrcOff(config);
    }

    /**
     * Parses {@code loudnessInfoSet()} into a fresh set ({@code parseLoudnessInfoSet}).
     *
     * @param reader the bit source
     * @param set receives the loudness information; its counts must be 0
     * @throws MediaException.Malformed if the loudness information is invalid
     */
    void parseLoudnessInfoSet(AacBitReader reader, LoudnessInfoSet set) {
        var albumCount = bits(reader, 6);
        var count = bits(reader, 6);
        checkCount(set.loudnessInfoAlbumCount + albumCount, LOUDNESS_INFO_COUNT_MAX, "loudness information");
        checkCount(set.loudnessInfoCount + count, LOUDNESS_INFO_COUNT_MAX, "loudness information");
        var offset = set.loudnessInfoAlbumCount;
        set.loudnessInfoAlbumCount += albumCount;
        for (var i = 0; i < albumCount; i++) {
            parseLoudnessInfo(reader, 0, set.loudnessInfoAlbum[i + offset]);
        }
        offset = set.loudnessInfoCount;
        set.loudnessInfoCount += count;
        for (var i = 0; i < count; i++) {
            parseLoudnessInfo(reader, 0, set.loudnessInfo[i + offset]);
        }
        set.loudnessInfoSetExtPresent = bits(reader, 1);
        if (set.loudnessInfoSetExtPresent == 1) {
            parseLoudnessInfoSetExtension(reader, set);
        }
    }

    /**
     * Parses the {@code uniDrc()} wrapper of an in-band configuration ({@code processUniDrcBitstreamDec_uniDrc}),
     * which carries an optional {@code uniDrcConfig()} and a {@code loudnessInfoSet()}.
     *
     * @param reader the bit source
     * @param config receives a new configuration
     * @param set receives new loudness information
     * @return whether the wrapper carried a configuration or loudness information
     * @throws MediaException.Malformed if the payload is invalid
     */
    boolean parseUniDrc(AacBitReader reader, UniDrcConfig config, LoudnessInfoSet set) {
        if (reader.bitsLeft() <= 0 || bits(reader, 1) == 0) {
            return false;
        }
        if (bits(reader, 1) != 0) {
            parseUniDrcConfig(reader, config);
        } else {
            generateDrcInstructionsForDrcOff(config);
            config.channelLayout.baseChannelCount = -1;
        }
        set.loudnessInfoAlbumCount = 0;
        set.loudnessInfoCount = 0;
        parseLoudnessInfoSet(reader, set);
        return true;
    }

    private static float decInitialGain(AacBitReader reader, int gainCodingProfile) {
        return switch (gainCodingProfile) {
            case GAIN_CODING_PROFILE_REGULAR -> {
                var sign = bits(reader, 1);
                var gain = bits(reader, 8) * 0.125f;
                yield sign != 0 ? -gain : gain;
            }
            case GAIN_CODING_PROFILE_FADING -> bits(reader, 1) == 0 ? 0.0f : -(bits(reader, 10) + 1) * 0.125f;
            case GAIN_CODING_PROFILE_CLIPPING -> bits(reader, 1) == 0 ? 0.0f : -(bits(reader, 8) + 1) * 0.125f;
            default -> 0.0f;
        };
    }

    private static void decGains(AacBitReader reader, int nNodes, int gainCodingProfile, Node[] node) {
        node[0].gainDb = decInitialGain(reader, gainCodingProfile);
        int[] sizes;
        int[] codes;
        float[] values;
        if (gainCodingProfile == GAIN_CODING_PROFILE_CLIPPING) {
            sizes = DrcTables.DELTA_GAIN_CLIPPING_SIZE;
            codes = DrcTables.DELTA_GAIN_CLIPPING_CODE;
            values = DrcTables.DELTA_GAIN_CLIPPING_VALUE;
        } else {
            sizes = DrcTables.DELTA_GAIN_SIZE;
            codes = DrcTables.DELTA_GAIN_CODE;
            values = DrcTables.DELTA_GAIN_VALUE;
        }
        for (var k = 1; k < nNodes; k++) {
            var index = huffman(reader, sizes, codes, 0);
            if (index < 0) {
                throw new MediaException.Malformed("MPEG-D DRC gain delta code is invalid");
            }
            node[k].gainDb = node[k - 1].gainDb + values[index];
        }
    }

    /**
     * Decodes a prefix code by reading bits one by one against a table sorted by code size.
     *
     * @return the index of the matching entry, or -1
     */
    private static int huffman(AacBitReader reader, int[] sizes, int[] codes, int first) {
        var bitsRead = 0;
        var code = 0;
        var e = first;
        while (e < sizes.length) {
            while (bitsRead < sizes[e]) {
                code = (code << 1) + bits(reader, 1);
                bitsRead++;
            }
            while (e < sizes.length && bitsRead == sizes[e]) {
                if (code == codes[e]) {
                    return e;
                }
                e++;
            }
        }
        return -1;
    }

    private static int huffman(AacBitReader reader, Tables table) {
        var bitsRead = 0;
        var code = 0;
        var e = 1;
        while (e < N_DELTA_TIME_CODE_TABLE_ENTRIES_MAX && table.size[e] > 0) {
            while (bitsRead < table.size[e]) {
                code = (code << 1) + bits(reader, 1);
                bitsRead++;
            }
            while (e < N_DELTA_TIME_CODE_TABLE_ENTRIES_MAX && bitsRead == table.size[e]) {
                if (code == table.code[e]) {
                    return table.value[e];
                }
                e++;
            }
        }
        throw new MediaException.Malformed("MPEG-D DRC time delta code is invalid");
    }

    private static int decSlopes(AacBitReader reader, int gainInterpolationType, Node[] node) {
        var k = 0;
        var endMarker = 0;
        while (endMarker != 1) {
            k++;
            if (k > NODE_COUNT_MAX) {
                throw new MediaException.Malformed("MPEG-D DRC gain sequence has too many nodes");
            }
            endMarker = bits(reader, 1);
        }
        var nNodes = k;
        if (gainInterpolationType == GAIN_INTERPOLATION_TYPE_SPLINE) {
            for (k = 0; k < nNodes; k++) {
                var index = huffman(reader, DrcTables.SLOPE_SIZE, DrcTables.SLOPE_CODE, 0);
                if (index < 0) {
                    throw new MediaException.Malformed("MPEG-D DRC slope code is invalid");
                }
                node[k].slope = DrcTables.SLOPE_VALUE[DrcTables.SLOPE_INDEX[index]];
            }
        } else {
            for (k = 0; k < nNodes; k++) {
                node[k].slope = 0.0f;
            }
        }
        return nNodes;
    }

    private static void decTimes(AacBitReader reader, Tables table, int nNodes, int deltaTmin, int drcFrameSize,
                                 int fullFrame, int timeOffset, Node[] node) {
        var timeOffs = timeOffset;
        var frameEndFlag = fullFrame == 0 ? bits(reader, 1) : 1;
        if (frameEndFlag == 1) {
            var nodeResFlag = false;
            var k = 0;
            for (; k < nNodes - 1; k++) {
                var timeDelta = huffman(reader, table);
                var nodeTimeTmp = timeOffs + timeDelta * deltaTmin;
                if (nodeTimeTmp > drcFrameSize + timeOffset) {
                    if (!nodeResFlag) {
                        node[k].time = drcFrameSize + timeOffset;
                        nodeResFlag = true;
                    }
                    node[k + 1].time = nodeTimeTmp;
                } else {
                    node[k].time = nodeTimeTmp;
                }
                timeOffs = nodeTimeTmp;
            }
            if (!nodeResFlag) {
                node[k].time = drcFrameSize + timeOffset;
            }
        } else {
            for (var k = 0; k < nNodes; k++) {
                var timeDelta = huffman(reader, table);
                node[k].time = timeOffs + timeDelta * deltaTmin;
                timeOffs = node[k].time;
            }
        }
    }

    private void parseSplineNodes(AacBitReader reader, GainSetParams set, SplineNodes nodes) {
        int timeOffset;
        if (set.timeAlignment == 0) {
            timeOffset = -1;
        } else if (set.timeDeltaMinPresent != 0) {
            timeOffset = -set.timeDeltaMin + (set.timeDeltaMin - 1) / 2;
        } else {
            timeOffset = -drcParams.deltaTminDefault + (drcParams.deltaTminDefault - 1) / 2;
        }
        if (reader == null) {
            var gainDbPrev = nodes.node[Math.max(0, nodes.nNodes - 1)].gainDb;
            nodes.drcGainCodingMode = 0;
            nodes.nNodes = 1;
            nodes.node[0].gainDb = gainDbPrev < 0 ? gainDbPrev : 0.f;
            nodes.node[0].slope = 0.0f;
            nodes.node[0].time = drcParams.drcFrameSize + timeOffset;
            return;
        }
        nodes.drcGainCodingMode = bits(reader, 1);
        if (nodes.drcGainCodingMode == 0) {
            nodes.nNodes = 1;
            nodes.node[0].gainDb = decInitialGain(reader, set.gainCodingProfile);
            nodes.node[0].slope = 0.0f;
            nodes.node[0].time = drcParams.drcFrameSize + timeOffset;
        } else {
            nodes.nNodes = decSlopes(reader, set.gainInterpolationType, nodes.node);
            if (set.timeDeltaMinPresent != 0) {
                decTimes(reader, set.tables, nodes.nNodes, set.timeDeltaMin, drcParams.drcFrameSize, set.fullFrame,
                        timeOffset, nodes.node);
            } else {
                decTimes(reader, tablesDefault, nodes.nNodes, drcParams.deltaTminDefault, drcParams.drcFrameSize,
                        set.fullFrame, timeOffset, nodes.node);
            }
            decGains(reader, nodes.nNodes, set.gainCodingProfile, nodes.node);
        }
    }

    private void parseDrcGainSequence(AacBitReader reader, GainSetParams set, SplineNodes nodes) {
        if (drcParams.delayMode == DELAY_MODE_LOW_DELAY && set.fullFrame == 0) {
            throw new MediaException.Malformed("MPEG-D DRC low delay mode needs full frame gain sequences");
        }
        parseSplineNodes(reader, set, nodes);
        var nNodesNodeRes = 0;
        var nNodesCur = 0;
        for (var k = 0; k < nodes.nNodes; k++) {
            if (nodes.node[k].time >= drcParams.drcFrameSize) {
                timeBufPrevFrame[nNodesNodeRes++] = nodes.node[k].time;
            } else {
                timeBufCurFrame[nNodesCur++] = nodes.node[k].time;
            }
        }
        var k = 0;
        for (; k < nNodesNodeRes; k++) {
            nodes.node[k].time = timeBufPrevFrame[k] - 2 * drcParams.drcFrameSize;
        }
        for (var m = 0; m < nNodesCur; m++, k++) {
            nodes.node[k].time = timeBufCurFrame[m];
        }
    }

    /**
     * Parses {@code uniDrcGain()} ({@code parseUniDrcGain}), or repeats the previous gains when the frame's payload
     * is missing.
     *
     * @param reader the bit source, or {@code null} when the frame carries no gain payload
     * @param config the configuration
     * @param gain receives the gain sequences
     * @throws MediaException.Malformed if the payload is invalid
     */
    void parseUniDrcGain(AacBitReader reader, UniDrcConfig config, UniDrcGain gain) {
        var coefficients = config.drcCoefficientsUniDrc[0];
        var gainSequenceCount = config.drcCoefficientsUniDrcCount > 0 ? coefficients.gainSequenceCount : 0;
        for (var seq = 0; seq < gainSequenceCount; seq++) {
            var index = coefficients.gainSetParamsIndexForGainSequence[seq];
            if (index < 0 || index >= coefficients.gainSetCount) {
                throw new MediaException.Malformed("MPEG-D DRC gain sequence " + seq + " has no gain set");
            }
            var set = coefficients.gainSetParams[index];
            if (set.gainCodingProfile == GAIN_CODING_PROFILE_CONSTANT) {
                var nodes = gain.drcGainSequence[seq];
                nodes.nNodes = 1;
                nodes.node[0].slope = 0.0f;
                nodes.node[0].time = drcParams.drcFrameSize - 1;
                nodes.node[0].gainDb = 0.0f;
            } else {
                parseDrcGainSequence(reader, set, gain.drcGainSequence[seq]);
            }
        }
        if (reader == null) {
            packetlossFrameCount++;
            if (packetlossFrameCount * (float) drcParams.drcFrameSize / config.sampleRate > MAXPACKETLOSSTIME) {
                config.applyDrc = 0;
            }
        } else {
            gain.uniDrcGainExtPresent = bits(reader, 1);
            if (gain.uniDrcGainExtPresent == 1) {
                var k = 0;
                gain.uniDrcGainExtType[k] = bits(reader, 4);
                while (gain.uniDrcGainExtType[k] != UNIDRCGAINEXT_TERM) {
                    var extSizeBits = bits(reader, 3) + 4;
                    gain.extBitSize[k] = bits(reader, extSizeBits) + 1;
                    skipBits(reader, gain.extBitSize[k]);
                    k = Math.min(k + 1, gain.uniDrcGainExtType.length - 1);
                    gain.uniDrcGainExtType[k] = bits(reader, 4);
                }
            }
            packetlossFrameCount = 0;
            config.applyDrc = 1;
        }
    }
}
