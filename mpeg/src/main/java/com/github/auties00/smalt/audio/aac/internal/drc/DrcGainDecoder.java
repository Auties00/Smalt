/* Ports the time domain part of the ISO/IEC 23003-4 reference software gain decoder (uniDrcGainDecoder.c,
   uniDrcDec.c, uniDrcGainDec.c and uniDrcProcessAudio.c, without the subband domain and the loudness equalizer).
   Copyright Apple Inc., Fraunhofer IIS and ISO/IEC 2014; ISO/IEC reference software licence (use in products
   conforming to ISO/IEC 23003-4). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.drc;

import com.github.auties00.smalt.MediaException;

import static com.github.auties00.smalt.audio.aac.internal.drc.DrcModel.*;

/**
 * Decodes the DRC gains of the selected DRC sets and applies them to the audio in the time domain, with the
 * equalizer, the multi-band filter banks, the shape filters and the parametric DRC of the selected sets
 * (ISO/IEC 23003-4 clause 6.4).
 *
 * <p>The decoder delays the audio by the look-ahead of its parametric DRC sets. Instances are mutable and not
 * thread-safe.
 */
final class DrcGainDecoder {
    private static final int GAIN_BUFFER_LENGTH = 2 * AUDIO_CODEC_FRAME_SIZE_MAX + MAX_SIGNAL_DELAY;

    private static final float[][][] DOLBY_NODES_LEFT = {
            {{-41.0f, 0.0f}, {-53.0f, 6.0f}},
            {{-43.0f, 6.0f}, {-44.0f, 6.0f}},
            {{-41.0f, 0.0f}, {-65.0f, 12.0f}},
            {{-55.0f, 12.0f}, {-56.0f, 12.0f}},
            {{-50.0f, 15.0f}, {-51.0f, 15.0f}}};

    private static final float[][][] DOLBY_NODES_RIGHT = {
            {{-21.0f, 0.0f}, {-11.0f, -5.0f}, {9.0f, -24.0f}, {19.0f, -34.0f}},
            {{-26.0f, 0.0f}, {-16.0f, -5.0f}, {4.0f, -24.0f}, {14.0f, -34.0f}},
            {{-21.0f, 0.0f}, {9.0f, -15.0f}, {19.0f, -25.0f}, {29.0f, -35.0f}},
            {{-26.0f, 0.0f}, {-16.0f, -5.0f}, {4.0f, -24.0f}, {14.0f, -34.0f}},
            {{-26.0f, 0.0f}, {-16.0f, -5.0f}, {4.0f, -24.0f}, {14.0f, -34.0f}}};

    /**
     * Holds the gains of one gain element ({@code BufferForInterpolation}).
     */
    private static final class GainBuffer {
        float nodeGainDb;
        float nodeSlope;
        int nodeTime;
        float previousGainDb;
        float previousSlope;
        int previousTime = -1;
        final float[] lpcmGains = new float[GAIN_BUFFER_LENGTH];

        GainBuffer() {
            java.util.Arrays.fill(lpcmGains, 1.0f);
        }
    }

    /**
     * Holds the parameters of the gain interpolation of one band ({@code InterpolationParams}).
     */
    private static final class Interpolation {
        int gainInterpolationType;
        boolean drcEffectAllowsGainModification;
        boolean drcEffectIsDucking;
        boolean drcEffectIsClipping;
        DuckingModifiers duckingModifiers;
        GainModifiers gainModifiers;
        int drcCharacteristicPresent;
        SplitDrcCharacteristic splitSourceCharacteristicLeft;
        SplitDrcCharacteristic splitSourceCharacteristicRight;
        SplitDrcCharacteristic splitTargetCharacteristicLeft;
        SplitDrcCharacteristic splitTargetCharacteristicRight;
        int limiterPeakTargetPresent;
        float limiterPeakTarget;
        float loudnessNormalizationGainDb;
        int deltaTmin;
        float compress;
        float boost;
    }

    private final int audioSampleRate;
    private final int drcFrameSize;
    private final int delayMode;
    private final int gainDelaySamples;
    private int parametricDrcDelay;
    private int eqDelay;
    private int audioDelaySamples;
    private int drcSetCounter;
    private int multiBandSelDrcIndex = -1;
    private final int[] selDrcInstructionsIndex = {-1, -1, -1};
    private final int[] selDrcCoefficientsIndex = {-1, -1, -1};
    private final GainBuffer[][] gainBuffers = new GainBuffer[SEL_DRC_COUNT][];
    private final DrcParametric parametric;
    private final DrcShapeFilters[] shapeFilters = new DrcShapeFilters[CHANNEL_GROUP_COUNT_MAX];
    private DrcFilterBanks filterBanks;
    private DrcEqualizer equalizer;
    private int audioChannelCount;
    private float[][] delayed;
    private float[][] bands = new float[0][];
    private final Interpolation interpolation = new Interpolation();
    private final SplitDrcCharacteristic cicpLeft = new SplitDrcCharacteristic();
    private final SplitDrcCharacteristic cicpRight = new SplitDrcCharacteristic();
    private final int[] gainIndexForGroup = new int[CHANNEL_GROUP_COUNT_MAX];
    private final int[] signalIndexForChannel = new int[CHANNEL_COUNT_MAX];

    /**
     * Constructs a gain decoder ({@code drcDecInit}).
     *
     * @param audioFrameSize the number of samples per frame, which is the DRC frame size
     * @param audioSampleRate the sampling rate
     * @param gainDelaySamples the delay of the audio relative to the gains, from 0 to 4500
     * @param delayMode {@link DrcModel#DELAY_MODE_REGULAR_DELAY} or {@link DrcModel#DELAY_MODE_LOW_DELAY}
     * @throws MediaException.Malformed if the parameters are out of range
     */
    DrcGainDecoder(int audioFrameSize, int audioSampleRate, int gainDelaySamples, int delayMode) {
        if (audioFrameSize < 1 || audioFrameSize > AUDIO_CODEC_FRAME_SIZE_MAX || audioSampleRate < 1000
                || audioFrameSize < 0.001f * audioSampleRate
                || DrcParser.deltaTmin(audioSampleRate) > audioFrameSize) {
            throw new MediaException.Malformed("MPEG-D DRC frame size " + audioFrameSize + " is out of range");
        }
        if (gainDelaySamples < 0 || gainDelaySamples > MAX_SIGNAL_DELAY) {
            throw new MediaException.Malformed("MPEG-D DRC gain delay is out of range");
        }
        this.drcFrameSize = audioFrameSize;
        this.audioSampleRate = audioSampleRate;
        this.gainDelaySamples = gainDelaySamples;
        this.delayMode = delayMode;
        this.parametric = new DrcParametric(audioFrameSize, audioSampleRate);
        for (var g = 0; g < shapeFilters.length; g++) {
            shapeFilters[g] = new DrcShapeFilters();
        }
    }

    /**
     * {@return the delay of the parametric DRC sets in samples}
     */
    int parametricDrcDelay() {
        return parametricDrcDelay;
    }

    /**
     * {@return the delay of the equalizer in samples}
     */
    int eqDelay() {
        return eqDelay;
    }

    /**
     * Configures the decoder for the selected DRC sets and equalizer ({@code drcDecInitAfterConfig}).
     *
     * @param audioChannelCount the number of channels the decoder processes
     * @param drcSetIds the identifiers of the DRC sets
     * @param downmixIds the downmix identifiers the DRC sets apply to
     * @param numSets the number of DRC sets
     * @param eqSetId the identifier of the equalizer set, 0 for none
     * @param config the configuration
     * @param loudnessInfoSet the loudness information
     * @throws MediaException.Malformed if the configuration does not match the selection
     */
    void initAfterConfig(int audioChannelCount, int[] drcSetIds, int[] downmixIds, int numSets, int eqSetId,
                         UniDrcConfig config, LoudnessInfoSet loudnessInfoSet) {
        for (var k = 0; k < numSets; k++) {
            initSelectedDrcSet(config, audioChannelCount, drcSetIds[k], downmixIds[k]);
        }
        this.audioChannelCount = audioChannelCount;
        audioDelaySamples = parametricDrcDelay;
        if (config.uniDrcConfigExt.parametricDrcPresent != 0) {
            parametric.initAfterConfig(config, loudnessInfoSet);
        }
        for (var n = 0; n < SEL_DRC_COUNT; n++) {
            if (selDrcInstructionsIndex[n] >= 0) {
                var count = config.drcInstructionsUniDrc[selDrcInstructionsIndex[n]].gainElementCount;
                var buffers = new GainBuffer[count];
                for (var i = 0; i < count; i++) {
                    buffers[i] = new GainBuffer();
                }
                gainBuffers[n] = buffers;
            }
        }
        var maxSignals = 0;
        for (var ix = 0; ix < drcSetCounter; ix++) {
            maxSignals = Math.max(maxSignals,
                    config.drcInstructionsUniDrc[selDrcInstructionsIndex[ix]].multibandAudioSignalCount);
        }
        bands = new float[maxSignals][drcFrameSize];
        if (audioDelaySamples > 0) {
            delayed = new float[audioChannelCount][drcFrameSize + audioDelaySamples];
        }
        if (eqSetId > 0) {
            var ext = config.uniDrcConfigExt;
            var k = 0;
            for (; k < ext.eqInstructionsCount; k++) {
                if (ext.eqInstructions[k].eqSetId == eqSetId) {
                    break;
                }
            }
            if (k == ext.eqInstructionsCount) {
                throw new MediaException.Malformed("MPEG-D DRC selected equalizer is not defined");
            }
            equalizer = new DrcEqualizer(ext.eqCoefficients, ext.eqInstructions[k]);
            eqDelay = equalizer.delay();
        } else {
            equalizer = null;
        }
    }

    private void initSelectedDrcSet(UniDrcConfig config, int audioChannelCount, int drcSetIdSelected,
                                    int downmixIdSelected) {
        parametric.parametricDrcInstanceCount = 0;
        if (config.drcCoefficientsUniDrcCount != 0 && config.drcCoefficientsUniDrc[0].drcFrameSizePresent != 0
                && config.drcCoefficientsUniDrc[0].drcFrameSize != drcFrameSize) {
            throw new MediaException.Malformed("MPEG-D DRC frame size does not match the audio frame size");
        }
        var n = 0;
        for (; n < config.drcInstructionsCountPlus; n++) {
            if (config.drcInstructionsUniDrc[n].drcSetId == drcSetIdSelected) {
                break;
            }
        }
        if (n == config.drcInstructionsCountPlus) {
            throw new MediaException.Malformed("MPEG-D DRC selected set " + drcSetIdSelected + " is not defined");
        }
        var instructionsIndex = n;
        var instructions = config.drcInstructionsUniDrc[instructionsIndex];
        int channelCount;
        if (downmixIdSelected == ID_FOR_BASE_LAYOUT) {
            channelCount = config.channelLayout.baseChannelCount;
        } else if (downmixIdSelected == ID_FOR_ANY_DOWNMIX) {
            channelCount = audioChannelCount;
        } else {
            n = 0;
            for (; n < config.downmixInstructionsCount; n++) {
                if (config.downmixInstructions[n].downmixId == downmixIdSelected) {
                    break;
                }
            }
            if (n == config.downmixInstructionsCount) {
                throw new MediaException.Malformed("MPEG-D DRC selected downmix is not defined");
            }
            channelCount = config.downmixInstructions[n].targetChannelCount;
        }
        if (channelCount < 0 || channelCount > CHANNEL_COUNT_MAX) {
            throw new MediaException.Malformed("MPEG-D DRC channel count is out of range");
        }
        instructions.audioChannelCount = channelCount;
        DrcCoefficientsUniDrc coefficients = null;
        var coefficientsIndex = -1;
        if (instructions.drcSetId <= 0) {
            coefficientsIndex = 0;
        } else {
            for (var c = 0; c < config.drcCoefficientsUniDrcCount; c++) {
                if (config.drcCoefficientsUniDrc[c].drcLocation == LOCATION_SELECTED) {
                    coefficientsIndex = c;
                }
            }
            coefficients = DrcParser.selectDrcCoefficients(config, LOCATION_SELECTED);
        }
        if (drcSetCounter >= SEL_DRC_COUNT) {
            throw new MediaException.Malformed("MPEG-D DRC selects too many DRC sets");
        }
        selDrcInstructionsIndex[drcSetCounter] = instructionsIndex;
        selDrcCoefficientsIndex[drcSetCounter] = coefficientsIndex;
        if (instructions.downmixId[0] == ID_FOR_ANY_DOWNMIX || instructions.downmixIdCount > 1) {
            var group = instructions.gainSetIndex[0] >= 0 ? 0 : -1;
            for (var c = 0; c < instructions.audioChannelCount; c++) {
                instructions.channelGroupForChannel[c] = group;
            }
        }
        for (var g = 0; g < instructions.nDrcChannelGroups; g++) {
            instructions.nChannelsPerChannelGroup[g] = 0;
            for (var c = 0; c < instructions.audioChannelCount; c++) {
                if (instructions.channelGroupForChannel[c] == g) {
                    instructions.nChannelsPerChannelGroup[g]++;
                }
            }
        }
        if ((instructions.drcSetEffect & (EFFECT_BIT_DUCK_OTHER | EFFECT_BIT_DUCK_SELF)) != 0) {
            instructions.multibandAudioSignalCount = instructions.audioChannelCount;
        } else {
            instructions.multibandAudioSignalCount = 0;
            for (var c = 0; c < instructions.audioChannelCount; c++) {
                var g = instructions.channelGroupForChannel[c];
                instructions.multibandAudioSignalCount += g < 0 ? 1 : instructions.bandCountForChannelGroup[g];
            }
        }
        var multiband = false;
        for (var g = 0; g < instructions.nDrcChannelGroups; g++) {
            if (g == 0) {
                instructions.parametricDrcLookAheadSamplesMax = 0;
            }
            if (instructions.channelGroupIsParametricDrc[g] == 0) {
                if (instructions.bandCountForChannelGroup[g] > 1) {
                    if (multiBandSelDrcIndex != -1) {
                        throw new MediaException.Malformed("MPEG-D DRC selects more than one multi-band set");
                    }
                    multiband = true;
                }
            } else {
                var gainSetIndex = instructions.gainSetIndexForChannelGroupParametricDrc[g];
                var parametricDrcId = config.uniDrcConfigExt.drcCoefficientsParametricDrc
                        .parametricDrcGainSetParams[gainSetIndex].parametricDrcId;
                var i = 0;
                for (; i < config.uniDrcConfigExt.parametricDrcInstructionsCount; i++) {
                    if (config.uniDrcConfigExt.parametricDrcInstructions[i].parametricDrcId == parametricDrcId) {
                        break;
                    }
                }
                if (i == config.uniDrcConfigExt.parametricDrcInstructionsCount) {
                    throw new MediaException.Malformed("MPEG-D DRC parametric DRC is not defined");
                }
                var parametricInstructions = config.uniDrcConfigExt.parametricDrcInstructions[i];
                var instance = parametric.addInstance();
                parametric.parametricDrcIndex[instance] = i;
                parametric.gainSetIndex[instance] = gainSetIndex;
                if (instructions.drcApplyToDownmix == 0) {
                    parametric.downmixIdFromDrcInstructions[instance] = ID_FOR_BASE_LAYOUT;
                } else if (instructions.downmixIdCount > 1) {
                    parametric.downmixIdFromDrcInstructions[instance] = ID_FOR_ANY_DOWNMIX;
                } else {
                    parametric.downmixIdFromDrcInstructions[instance] = instructions.downmixId[0];
                }
                parametric.audioChannelCount = channelCount;
                for (var c = 0; c < channelCount; c++) {
                    parametric.channelMap[instance][c] = instructions.channelGroupForChannel[c] == g ? 1 : 0;
                }
                int lookAhead;
                if (parametricInstructions.parametricDrcLookAheadPresent != 0) {
                    lookAhead = (int) ((float) parametricInstructions.parametricDrcLookAhead
                            * (float) audioSampleRate * 0.001f);
                } else if (parametricInstructions.parametricDrcType == PARAM_DRC_TYPE_FF) {
                    lookAhead = (int) (10.0f * (float) audioSampleRate * 0.001f);
                } else if (parametricInstructions.parametricDrcType == PARAM_DRC_TYPE_LIM) {
                    lookAhead = (int) (5.0f * (float) audioSampleRate * 0.001f);
                } else {
                    throw new MediaException.Malformed("MPEG-D DRC parametric DRC type is reserved");
                }
                instructions.parametricDrcLookAheadSamples[g] = lookAhead;
                instructions.parametricDrcLookAheadSamplesMax = Math.max(instructions.parametricDrcLookAheadSamplesMax,
                        lookAhead);
                multiband = false;
            }
        }
        parametricDrcDelay += instructions.parametricDrcLookAheadSamplesMax;
        if (parametricDrcDelay > MAX_SIGNAL_DELAY) {
            throw new MediaException.Malformed("MPEG-D DRC parametric look-ahead is too long");
        }
        if (multiband) {
            multiBandSelDrcIndex = drcSetCounter;
            filterBanks = new DrcFilterBanks(coefficients, instructions);
        } else {
            for (var g = 0; g < instructions.nDrcChannelGroups; g++) {
                var modifiers = instructions.gainModifiersForChannelGroup[g];
                if (modifiers.shapeFilterPresent == 1 && coefficients != null) {
                    if (modifiers.shapeFilterIndex < 0
                            || modifiers.shapeFilterIndex >= coefficients.shapeFilterBlockParams.length) {
                        throw new MediaException.Malformed("MPEG-D DRC shape filter index is out of range");
                    }
                    shapeFilters[g].init(coefficients.shapeFilterBlockParams[modifiers.shapeFilterIndex]);
                } else {
                    shapeFilters[g].disable();
                }
            }
        }
        drcSetCounter++;
    }

    /**
     * Applies the selected DRC sets to a frame in place ({@code drcProcessTime}).
     *
     * @param audio the channels, at least {@link #initAfterConfig the configured} count, each of at least the
     * frame size
     * @param config the configuration
     * @param gain the gains of the frame
     * @param loudnessNormalizationGainDb the loudness normalization gain, which the limiter of a clipping
     * prevention set considers
     * @param boost the scale of the gain boosts
     * @param compress the scale of the gain attenuations
     * @throws MediaException.Malformed if the gains cannot be interpolated
     */
    void process(float[][] audio, UniDrcConfig config, UniDrcGain gain, float loudnessNormalizationGainDb, float boost,
                 float compress) {
        if (equalizer != null) {
            for (var c = 0; c < equalizer.audioChannelCount(); c++) {
                equalizer.process(c, audio[c], drcFrameSize);
            }
        }
        float[][] input;
        int inputBase;
        if (audioDelaySamples != 0) {
            for (var c = 0; c < audioChannelCount; c++) {
                System.arraycopy(audio[c], 0, delayed[c], audioDelaySamples, drcFrameSize);
            }
            input = delayed;
            inputBase = audioDelaySamples;
        } else {
            input = audio;
            inputBase = 0;
        }
        if (config.applyDrc != 0) {
            for (var sel = 0; sel < drcSetCounter; sel++) {
                getDrcGain(config, gain, compress, boost, loudnessNormalizationGainDb, sel, input, inputBase);
            }
            var delayedInput = audioDelaySamples != 0 ? delayed : audio;
            if (drcSetCounter == 0) {
                if (audioDelaySamples != 0) {
                    for (var c = 0; c < audioChannelCount; c++) {
                        System.arraycopy(delayed[c], 0, audio[c], 0, drcFrameSize);
                    }
                }
            } else {
                for (var sel = 0; sel < drcSetCounter; sel++) {
                    var instructions = config.drcInstructionsUniDrc[selDrcInstructionsIndex[sel]];
                    DrcFilterBanks.apply(multiBandSelDrcIndex == sel ? filterBanks : null, instructions, drcFrameSize,
                            delayedInput, bands);
                    applyGains(instructions, sel);
                    addDrcBandAudio(instructions, audio);
                }
            }
        }
        if (audioDelaySamples != 0) {
            for (var c = 0; c < audioChannelCount; c++) {
                System.arraycopy(delayed[c], drcFrameSize, delayed[c], 0, audioDelaySamples);
            }
        }
    }

    private void applyGains(DrcInstructionsUniDrc instructions, int sel) {
        if (instructions.drcSetId <= 0) {
            return;
        }
        var offset = delayMode == DELAY_MODE_LOW_DELAY ? drcFrameSize : 0;
        gainIndexForGroup[0] = 0;
        for (var g = 0; g < instructions.nDrcChannelGroups - 1; g++) {
            gainIndexForGroup[g + 1] = gainIndexForGroup[g] + instructions.bandCountForChannelGroup[g];
        }
        signalIndexForChannel[0] = 0;
        for (var c = 0; c < instructions.audioChannelCount - 1; c++) {
            var g = instructions.channelGroupForChannel[c];
            signalIndexForChannel[c + 1] = signalIndexForChannel[c] + (g >= 0 ? instructions.bandCountForChannelGroup[g]
                    : 1);
        }
        var buffers = gainBuffers[sel];
        for (var g = 0; g < instructions.nDrcChannelGroups; g++) {
            var shape = shapeFilters[g];
            for (var b = 0; b < instructions.bandCountForChannelGroup[g]; b++) {
                var element = gainIndexForGroup[g] + b;
                if (element >= buffers.length) {
                    throw new MediaException.Malformed("MPEG-D DRC set has more bands than gain elements");
                }
                var lpcmGains = buffers[element].lpcmGains;
                int base;
                if (instructions.channelGroupIsParametricDrc[g] == 0) {
                    base = MAX_SIGNAL_DELAY - gainDelaySamples - audioDelaySamples + offset;
                } else {
                    base = MAX_SIGNAL_DELAY + instructions.parametricDrcLookAheadSamples[g] - audioDelaySamples
                            + drcFrameSize;
                }
                var iEnd = 0;
                var iStart = 0;
                while (iEnd < drcFrameSize) {
                    if (shape.present()) {
                        var drcGainLast = shape.drcGainLast();
                        var gainThr = 0.0001f * drcGainLast;
                        while (iEnd < drcFrameSize && Math.abs(lpcmGains[base + iEnd] - drcGainLast) <= gainThr) {
                            iEnd++;
                        }
                    } else {
                        iEnd = drcFrameSize;
                    }
                    for (var c = 0; c < instructions.audioChannelCount; c++) {
                        if (g == instructions.channelGroupForChannel[c]) {
                            var signal = bands[signalIndexForChannel[c] + b];
                            var signalIndex = signalIndexForChannel[c] + b;
                            for (var i = iStart; i < iEnd; i++) {
                                var gainValue = lpcmGains[base + i];
                                signal[i] = shape.process(signalIndex, signal[i]) * gainValue;
                            }
                        }
                    }
                    if (iEnd < drcFrameSize && shape.present()) {
                        shape.adapt(lpcmGains[base + iEnd]);
                    }
                    iStart = iEnd;
                }
            }
        }
    }

    private void addDrcBandAudio(DrcInstructionsUniDrc instructions, float[][] audio) {
        if (instructions.drcSetId > 0) {
            var signalIndex = 0;
            for (var c = 0; c < instructions.audioChannelCount; c++) {
                var g = instructions.channelGroupForChannel[c];
                var out = audio[c];
                if (g >= 0) {
                    var bandCount = instructions.bandCountForChannelGroup[g];
                    for (var i = 0; i < drcFrameSize; i++) {
                        var sum = 0.0f;
                        for (var b = 0; b < bandCount; b++) {
                            sum += bands[signalIndex + b][i];
                        }
                        out[i] = sum;
                    }
                    signalIndex += bandCount;
                } else {
                    System.arraycopy(bands[signalIndex], 0, out, 0, drcFrameSize);
                    signalIndex++;
                }
            }
        } else {
            for (var c = 0; c < instructions.audioChannelCount; c++) {
                System.arraycopy(bands[c], 0, audio[c], 0, drcFrameSize);
            }
        }
    }

    private void getDrcGain(UniDrcConfig config, UniDrcGain gain, float compress, float boost,
                            float loudnessNormalizationGainDb, int sel, float[][] input, int inputBase) {
        var instructionsIndex = selDrcInstructionsIndex[sel];
        if (instructionsIndex < 0) {
            return;
        }
        var instructions = config.drcInstructionsUniDrc[instructionsIndex];
        var drcSetEffect = instructions.drcSetEffect;
        var coefficientsIndex = selDrcCoefficientsIndex[sel];
        if (coefficientsIndex < 0) {
            throw new MediaException.Malformed("MPEG-D DRC set has no coefficients");
        }
        var coefficients = config.drcCoefficientsUniDrc[coefficientsIndex];
        var params = interpolation;
        params.loudnessNormalizationGainDb = loudnessNormalizationGainDb;
        params.compress = compress;
        params.boost = boost;
        params.limiterPeakTargetPresent = instructions.limiterPeakTargetPresent;
        params.limiterPeakTarget = instructions.limiterPeakTarget;
        params.drcEffectAllowsGainModification = (drcSetEffect & (EFFECT_BIT_DUCK_OTHER | EFFECT_BIT_DUCK_SELF)) == 0
                && drcSetEffect != EFFECT_BIT_FADE && drcSetEffect != EFFECT_BIT_CLIPPING;
        params.drcEffectIsDucking = (drcSetEffect & (EFFECT_BIT_DUCK_OTHER | EFFECT_BIT_DUCK_SELF)) != 0;
        params.drcEffectIsClipping = drcSetEffect == EFFECT_BIT_CLIPPING;
        var buffers = gainBuffers[sel];
        for (var buffer : buffers) {
            buffer.previousGainDb = buffer.nodeGainDb;
            buffer.previousSlope = buffer.nodeSlope;
            buffer.previousTime = buffer.nodeTime - drcFrameSize;
            System.arraycopy(buffer.lpcmGains, drcFrameSize, buffer.lpcmGains, 0, drcFrameSize + MAX_SIGNAL_DELAY);
        }
        var gainElementIndex = 0;
        var parametricInstanceIndex = 0;
        for (var g = 0; g < instructions.nDrcChannelGroups; g++) {
            params.gainInterpolationType = instructions.gainInterpolationTypeForChannelGroup[g];
            params.deltaTmin = instructions.timeDeltaMinForChannelGroup[g];
            params.duckingModifiers = instructions.duckingModifiersForChannelGroup[g];
            params.gainModifiers = instructions.gainModifiersForChannelGroup[g];
            if (instructions.channelGroupIsParametricDrc[g] == 0) {
                var set = coefficients.gainSetParams[instructions.gainSetIndexForChannelGroup[g]];
                var nDrcBands = instructions.bandCountForChannelGroup[g];
                for (var b = 0; b < nDrcBands; b++) {
                    var gainParams = set.gainParams[b];
                    params.drcCharacteristicPresent = gainParams.drcCharacteristicPresent;
                    if (gainParams.drcCharacteristicFormatIsCICP == 1) {
                        convertCicpToSplitCharacteristic(gainParams.drcCharacteristic, cicpLeft, cicpRight);
                        params.splitSourceCharacteristicLeft = cicpLeft;
                        params.splitSourceCharacteristicRight = cicpRight;
                    } else {
                        params.splitSourceCharacteristicLeft = characteristic(coefficients.splitCharacteristicLeft,
                                gainParams.drcCharacteristicLeftIndex);
                        params.splitSourceCharacteristicRight = characteristic(coefficients.splitCharacteristicRight,
                                gainParams.drcCharacteristicRightIndex);
                    }
                    params.splitTargetCharacteristicLeft = characteristic(coefficients.splitCharacteristicLeft,
                            params.gainModifiers.targetCharacteristicLeftIndex[b]);
                    params.splitTargetCharacteristicRight = characteristic(coefficients.splitCharacteristicRight,
                            params.gainModifiers.targetCharacteristicRightIndex[b]);
                    if (gainElementIndex >= buffers.length) {
                        throw new MediaException.Malformed("MPEG-D DRC set has more bands than gain elements");
                    }
                    concatenateSegments(b, gain.drcGainSequence[gainParams.gainSequenceIndex],
                            buffers[gainElementIndex]);
                    gainElementIndex++;
                }
            } else {
                if (gainElementIndex >= buffers.length) {
                    throw new MediaException.Malformed("MPEG-D DRC set has more bands than gain elements");
                }
                var instance = parametric.instances[parametricInstanceIndex];
                if (instance.parametricDrcType == PARAM_DRC_TYPE_LIM) {
                    DrcParametric.processLimiter(input, inputBase, loudnessNormalizationGainDb, instance,
                            buffers[gainElementIndex].lpcmGains, MAX_SIGNAL_DELAY + drcFrameSize);
                } else {
                    parametric.processInstance(input, inputBase, instance);
                    concatenateSegments(0, instance.splineNodes, buffers[gainElementIndex]);
                }
                gainElementIndex++;
                parametricInstanceIndex++;
            }
        }
    }

    private static SplitDrcCharacteristic characteristic(SplitDrcCharacteristic[] characteristics, int index) {
        if (index < 0 || index >= characteristics.length) {
            throw new MediaException.Malformed("MPEG-D DRC characteristic index is out of range");
        }
        return characteristics[index];
    }

    private static void convertCicpToSplitCharacteristic(int cicp, SplitDrcCharacteristic left,
                                                         SplitDrcCharacteristic right) {
        if (cicp > 0 && cicp < 7) {
            var row = DrcTables.CICP_SIGMOID[cicp - 1];
            left.characteristicFormat = 0;
            left.ioRatio = row[0];
            left.gain = 32.0f;
            left.exp = row[1];
            left.flipSign = 0;
            right.characteristicFormat = 0;
            right.ioRatio = row[0];
            right.gain = -32.0f;
            right.exp = row[2];
            right.flipSign = 0;
        } else if (cicp > 6 && cicp < 12) {
            var dolbyIndex = cicp - 7;
            left.characteristicFormat = 1;
            left.characteristicNodeCount = 2;
            left.nodeLevel[0] = -31.0f;
            left.nodeGain[0] = 0.0f;
            for (var i = 0; i < 2; i++) {
                left.nodeLevel[i + 1] = DOLBY_NODES_LEFT[dolbyIndex][i][0];
                left.nodeGain[i + 1] = DOLBY_NODES_LEFT[dolbyIndex][i][1];
            }
            right.characteristicFormat = 1;
            right.characteristicNodeCount = 4;
            right.nodeLevel[0] = -31.0f;
            right.nodeGain[0] = 0.0f;
            for (var i = 0; i < 4; i++) {
                right.nodeLevel[i + 1] = DOLBY_NODES_RIGHT[dolbyIndex][i][0];
                right.nodeGain[i + 1] = DOLBY_NODES_RIGHT[dolbyIndex][i][1];
            }
        }
    }

    private void concatenateSegments(int drcBand, SplineNodes nodes, GainBuffer buffer) {
        var timePrev = buffer.previousTime;
        var gainDbPrev = buffer.previousGainDb;
        var slopePrev = buffer.previousSlope;
        var gainDb = 0.0f;
        var slope = 0.0f;
        for (var n = 0; n < nodes.nNodes; n++) {
            var node = nodes.node[n];
            var duration = node.time - timePrev;
            gainDb = node.gainDb;
            slope = node.slope;
            var offset = MAX_SIGNAL_DELAY + drcFrameSize + timePrev;
            if (offset < 0 || offset + duration >= GAIN_BUFFER_LENGTH) {
                throw new MediaException.Malformed("MPEG-D DRC gain node time is out of range");
            }
            interpolateDrcGain(drcBand, duration, gainDbPrev, gainDb, slopePrev, slope, buffer.lpcmGains, offset);
            timePrev = node.time;
            gainDbPrev = gainDb;
            slopePrev = slope;
        }
        buffer.nodeGainDb = gainDb;
        buffer.nodeSlope = slope;
        buffer.nodeTime = timePrev;
    }

    private static float clampLow(float value) {
        return 0.0f > value ? 0.0f : value;
    }

    private void interpolateDrcGain(int drcBand, int tGainStep, float gain0, float gain1, float slope0, float slope1,
                                    float[] result, int r) {
        if (tGainStep <= 0) {
            throw new MediaException.Malformed("MPEG-D DRC gain nodes are not increasing in time");
        }
        toLinear(drcBand, gain0, slope0);
        var gainLeft = linearGain;
        var slopeLeft = linearSlope;
        toLinear(drcBand, gain1, slope1);
        var gainRight = linearGain;
        var slopeRight = linearSlope;
        if (interpolation.gainInterpolationType == GAIN_INTERPOLATION_TYPE_SPLINE) {
            slopeLeft = slopeLeft / (float) interpolation.deltaTmin;
            slopeRight = slopeRight / (float) interpolation.deltaTmin;
            var useCubic = true;
            if (Math.abs(slopeLeft) > Math.abs(slopeRight)) {
                var tConnectFloat = 2.0f * (gainRight - gainLeft - slopeRight * tGainStep) / (slopeLeft - slopeRight);
                var tConnect = (int) (0.5f + tConnectFloat);
                if (tConnect >= 0 && tConnect < tGainStep) {
                    useCubic = false;
                    result[r] = gainLeft;
                    result[r + tGainStep] = gainRight;
                    var a = (slopeRight - slopeLeft) / (tConnectFloat + tConnectFloat);
                    var n = 1;
                    for (; n < tConnect; n++) {
                        var t = (float) n;
                        result[r + n] = clampLow((a * t + slopeLeft) * t + gainLeft);
                    }
                    for (; n < tGainStep; n++) {
                        var t = (float) (n - tGainStep);
                        result[r + n] = slopeRight * t + gainRight;
                    }
                }
            } else if (Math.abs(slopeLeft) < Math.abs(slopeRight)) {
                var tConnectFloat = 2.0f * (gainLeft - gainRight + slopeLeft * tGainStep) / (slopeLeft - slopeRight);
                tConnectFloat = tGainStep - tConnectFloat;
                var tConnect = (int) (0.5f + tConnectFloat);
                if (tConnect >= 0 && tConnect < tGainStep) {
                    useCubic = false;
                    result[r] = gainLeft;
                    result[r + tGainStep] = gainRight;
                    var n = 1;
                    for (; n < tConnect; n++) {
                        var t = (float) n;
                        result[r + n] = slopeLeft * t + gainLeft;
                    }
                    var a = (slopeRight - slopeLeft) / (2.0f * (tGainStep - tConnectFloat));
                    var b = -slopeRight;
                    for (; n < tGainStep; n++) {
                        var t = (float) (tGainStep - n);
                        result[r + n] = clampLow((a * t + b) * t + gainRight);
                    }
                }
            }
            if (useCubic) {
                var tGainStepInv = 1.0f / (float) tGainStep;
                var tGainStepInv2 = tGainStepInv * tGainStepInv;
                var k1 = (gainRight - gainLeft) * tGainStepInv2;
                var k2 = slopeRight + slopeLeft;
                var a = tGainStepInv * (tGainStepInv * k2 - 2.0f * k1);
                var b = 3.0f * k1 - tGainStepInv * (k2 + slopeLeft);
                result[r] = gainLeft;
                result[r + tGainStep] = gainRight;
                for (var n = 1; n < tGainStep; n++) {
                    var t = (float) n;
                    result[r + n] = clampLow((((a * t + b) * t + slopeLeft) * t) + gainLeft);
                }
            }
        } else {
            var a = (gainRight - gainLeft) / (float) tGainStep;
            result[r] = gainLeft;
            result[r + tGainStep] = gainRight;
            for (var n = 1; n < tGainStep; n++) {
                var t = (float) n;
                result[r + n] = a * t + gainLeft;
            }
        }
    }

    private float linearGain;
    private float linearSlope;

    private void toLinear(int drcBand, float gainDbIn, float slopeDb) {
        var params = interpolation;
        var gainDb = gainDbIn;
        var gainRatio = 1.0f;
        var modifiers = params.gainModifiers;
        if (params.drcEffectAllowsGainModification) {
            if (params.drcCharacteristicPresent != 0) {
                var source = params.splitSourceCharacteristicLeft;
                var slopeIsNegative = 0;
                if (source.characteristicFormat == 0) {
                    slopeIsNegative = 1 - source.flipSign;
                } else {
                    for (var k = 1; k <= source.characteristicNodeCount; k++) {
                        if (source.nodeGain[k] > 0.0f) {
                            slopeIsNegative = 1;
                        }
                    }
                }
                if (gainDb == 0.0f) {
                    if ((modifiers.targetCharacteristicLeftPresent[drcBand] == 1
                            && params.splitTargetCharacteristicLeft.characteristicFormat == CHARACTERISTIC_PASS_THRU)
                            || (modifiers.targetCharacteristicRightPresent[drcBand] == 1
                            && params.splitTargetCharacteristicRight.characteristicFormat == CHARACTERISTIC_PASS_THRU)) {
                        gainDb = DRC_INPUT_LOUDNESS_TARGET;
                    }
                } else if ((gainDb > 0.0f && slopeIsNegative == 1) || (gainDb < 0.0f && slopeIsNegative == 0)) {
                    if (modifiers.targetCharacteristicLeftPresent[drcBand] == 1) {
                        gainRatio = mapGain(source, params.splitTargetCharacteristicLeft, gainDb) / gainDb;
                    }
                } else if (modifiers.targetCharacteristicRightPresent[drcBand] == 1) {
                    source = params.splitSourceCharacteristicRight;
                    gainRatio = mapGain(source, params.splitTargetCharacteristicRight, gainDb) / gainDb;
                }
            }
            gainRatio *= gainDb < 0.0f ? params.compress : params.boost;
        }
        if (modifiers.gainScalingPresent[drcBand] == 1) {
            gainRatio *= gainDb < 0.0 ? modifiers.attenuationScaling[drcBand] : modifiers.amplificationScaling[drcBand];
        }
        if (params.duckingModifiers.duckingScalingPresent == 1 && params.drcEffectIsDucking) {
            gainRatio *= params.duckingModifiers.duckingScaling;
        }
        var gainLin = (float) StrictMath.pow(2.0, gainRatio * gainDb / 6.0f);
        var slopeLin = SLOPE_FACTOR_DB_TO_LINEAR * gainRatio * gainLin * slopeDb;
        if (modifiers.gainOffsetPresent[drcBand] == 1) {
            gainLin *= (float) StrictMath.pow(2.0, modifiers.gainOffset[drcBand] / 6.0f);
        }
        if (params.limiterPeakTargetPresent == 1 && params.drcEffectIsClipping) {
            gainLin *= (float) StrictMath.pow(2.0, Math.max(0.0, -params.limiterPeakTarget
                    - params.loudnessNormalizationGainDb) / 6.0);
            if (gainLin >= 1.0) {
                gainLin = 1.0f;
                slopeLin = 0.0f;
            }
        }
        linearGain = gainLin;
        linearSlope = slopeLin;
    }

    private static float mapGain(SplitDrcCharacteristic source, SplitDrcCharacteristic target, float gainInDb) {
        float inLevel = switch (source.characteristicFormat) {
            case CHARACTERISTIC_SIGMOID -> sigmoidInverse(source, gainInDb);
            case CHARACTERISTIC_NODES -> nodesInverse(source, gainInDb);
            case CHARACTERISTIC_PASS_THRU -> gainInDb;
            default -> throw new MediaException.Malformed("MPEG-D DRC characteristic format is invalid");
        };
        return switch (target.characteristicFormat) {
            case CHARACTERISTIC_SIGMOID -> sigmoid(target, inLevel);
            case CHARACTERISTIC_NODES -> inLevel < DRC_INPUT_LOUDNESS_TARGET ? nodesLeft(target, inLevel)
                    : nodesRight(target, inLevel);
            case CHARACTERISTIC_PASS_THRU -> inLevel;
            default -> 0.0f;
        };
    }

    private static float sigmoid(SplitDrcCharacteristic characteristic, float inLevelDb) {
        var tmp = (DRC_INPUT_LOUDNESS_TARGET - inLevelDb) * characteristic.ioRatio;
        float out;
        if (characteristic.exp < 1000.0f) {
            var x = tmp / characteristic.gain;
            if (x < 0.0f) {
                throw new MediaException.Malformed("MPEG-D DRC sigmoid characteristic is out of range");
            }
            out = (float) (tmp / StrictMath.pow(1.0f + StrictMath.pow(x, characteristic.exp),
                    1.0f / characteristic.exp));
        } else {
            out = tmp;
        }
        return characteristic.flipSign == 1 ? -out : out;
    }

    private static float sigmoidInverse(SplitDrcCharacteristic characteristic, float gainDb) {
        var tmp = characteristic.flipSign == 1 ? -gainDb : gainDb;
        if (characteristic.exp < 1000.0f) {
            var x = tmp / characteristic.gain;
            if (x < 0.0f) {
                throw new MediaException.Malformed("MPEG-D DRC sigmoid characteristic is out of range");
            }
            tmp = (float) (tmp / StrictMath.pow(1.0f - StrictMath.pow(x, characteristic.exp),
                    1.0f / characteristic.exp));
        }
        if (characteristic.ioRatio == 0.0f) {
            throw new MediaException.Malformed("MPEG-D DRC sigmoid characteristic has no ratio");
        }
        return DRC_INPUT_LOUDNESS_TARGET - tmp / characteristic.ioRatio;
    }

    private static float nodesLeft(SplitDrcCharacteristic characteristic, float inLevelDb) {
        if (inLevelDb > DRC_INPUT_LOUDNESS_TARGET) {
            throw new MediaException.Malformed("MPEG-D DRC characteristic level is out of range");
        }
        var level = characteristic.nodeLevel;
        var gain = characteristic.nodeGain;
        for (var n = 1; n <= characteristic.characteristicNodeCount; n++) {
            if (inLevelDb <= level[n - 1] && inLevelDb > level[n]) {
                var w = (level[n] - inLevelDb) / (level[n] - level[n - 1]);
                return (float) (w * gain[n - 1] + (1.0 - w) * gain[n]);
            }
        }
        return gain[characteristic.characteristicNodeCount];
    }

    private static float nodesRight(SplitDrcCharacteristic characteristic, float inLevelDb) {
        if (inLevelDb < DRC_INPUT_LOUDNESS_TARGET) {
            throw new MediaException.Malformed("MPEG-D DRC characteristic level is out of range");
        }
        var level = characteristic.nodeLevel;
        var gain = characteristic.nodeGain;
        for (var n = 1; n <= characteristic.characteristicNodeCount; n++) {
            if (inLevelDb >= level[n - 1] && inLevelDb < level[n]) {
                var w = (level[n] - inLevelDb) / (level[n] - level[n - 1]);
                return (float) (w * gain[n - 1] + (1.0 - w) * gain[n]);
            }
        }
        return gain[characteristic.characteristicNodeCount];
    }

    private static float nodesInverse(SplitDrcCharacteristic characteristic, float gainDb) {
        var level = characteristic.nodeLevel;
        var gain = characteristic.nodeGain;
        var nodeCount = characteristic.characteristicNodeCount;
        var gainIsNegative = false;
        for (var k = 1; k <= nodeCount; k++) {
            if (gain[k] < 0.0f) {
                gainIsNegative = true;
            }
        }
        if (gainIsNegative) {
            if (gainDb <= gain[nodeCount]) {
                return level[nodeCount];
            }
            if (gainDb >= 0.0f) {
                return DRC_INPUT_LOUDNESS_TARGET;
            }
            for (var n = 1; n <= nodeCount; n++) {
                if (gainDb <= gain[n - 1] && gainDb > gain[n]) {
                    var gainDelta = gain[n] - gain[n - 1];
                    if (gainDelta == 0.0f) {
                        return level[n - 1];
                    }
                    var w = (gain[n] - gainDb) / gainDelta;
                    return (float) (w * level[n - 1] + (1.0 - w) * level[n]);
                }
            }
            return level[nodeCount];
        }
        if (gainDb >= gain[nodeCount]) {
            return level[nodeCount];
        }
        if (gainDb <= 0.0f) {
            return DRC_INPUT_LOUDNESS_TARGET;
        }
        for (var n = 1; n <= nodeCount; n++) {
            if (gainDb >= gain[n - 1] && gainDb < gain[n]) {
                var gainDelta = gain[n] - gain[n - 1];
                if (gainDelta == 0.0f) {
                    return level[n - 1];
                }
                var w = (gain[n] - gainDb) / (gain[n] - gain[n - 1]);
                return (float) (w * level[n - 1] + (1.0 - w) * level[n]);
            }
        }
        return level[nodeCount];
    }
}
