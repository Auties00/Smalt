/* Ports the data structures of the ISO/IEC 23003-4 reference software (uniDrc.h, uniDrcCommon.h,
   uniDrcTables.h). Copyright Apple Inc., Fraunhofer IIS and ISO/IEC 2014; ISO/IEC reference software licence (use
   in products conforming to ISO/IEC 23003-4). Modified for Smalt. */
package com.github.auties00.smalt.audio.aac.internal.drc;

/**
 * Holds the structures that reflect the MPEG-D DRC bitstream syntax ({@code uniDrcConfig()}, {@code loudnessInfoSet()}
 * and {@code uniDrcGain()}, ISO/IEC 23003-4 clause 7), with the version 1 syntax of Amendment 1, and the data derived
 * from them.
 *
 * <p>Fields keep the names of the syntax elements. Instances are mutable and not thread-safe.
 */
final class DrcModel {
    static final int SPEAKER_POS_COUNT_MAX = 128;
    static final int DOWNMIX_COEFF_COUNT_MAX = 32 * 32;
    static final int CHANNEL_COUNT_MAX = 128;
    static final int BAND_COUNT_MAX = 8;
    static final int SEQUENCE_COUNT_MAX = 24;
    static final int GAIN_SET_COUNT_MAX = SEQUENCE_COUNT_MAX;
    static final int MEASUREMENT_COUNT_MAX = 16;
    static final int DOWNMIX_INSTRUCTION_COUNT_MAX = 16;
    static final int DRC_COEFF_COUNT_MAX = 8;
    static final int DRC_INSTRUCTIONS_COUNT_MAX = DOWNMIX_INSTRUCTION_COUNT_MAX + 20;
    static final int LOUDNESS_INFO_COUNT_MAX = DOWNMIX_INSTRUCTION_COUNT_MAX + 20;
    static final int AUDIO_CODEC_FRAME_SIZE_MAX = 4096;
    static final int DRC_CODEC_FRAME_SIZE_MAX = AUDIO_CODEC_FRAME_SIZE_MAX / 8;
    static final int NODE_COUNT_MAX = DRC_CODEC_FRAME_SIZE_MAX;
    static final int CHANNEL_GROUP_COUNT_MAX = SEQUENCE_COUNT_MAX;
    static final int SUB_DRC_COUNT = 4;
    static final int SEL_DRC_COUNT = 3;
    static final int DOWNMIX_ID_COUNT_MAX = 8;
    static final int MAX_SIGNAL_DELAY = 4500;
    static final int DRC_BAND_COUNT_MAX = BAND_COUNT_MAX;
    static final int SPLIT_CHARACTERISTIC_COUNT_MAX = 8;
    static final int SHAPE_FILTER_COUNT_MAX = 8;
    static final int DRC_SET_ID_COUNT_MAX = 16;
    static final int EQ_SET_ID_COUNT_MAX = 8;
    static final int LOUD_EQ_GAIN_SEQUENCE_COUNT_MAX = 4;
    static final int FILTER_ELEMENT_COUNT_MAX = 16;
    static final int REAL_ZERO_RADIUS_ONE_COUNT_MAX = 14;
    static final int REAL_ZERO_COUNT_MAX = 64;
    static final int COMPLEX_ZERO_COUNT_MAX = 64;
    static final int REAL_POLE_COUNT_MAX = 16;
    static final int COMPLEX_POLE_COUNT_MAX = 16;
    static final int FIR_ORDER_MAX = 128;
    static final int EQ_NODE_COUNT_MAX = 33;
    static final int EQ_SUBBAND_GAIN_COUNT_MAX = 135;
    static final int UNIQUE_SUBBAND_GAIN_COUNT_MAX = 16;
    static final int FILTER_BLOCK_COUNT_MAX = 16;
    static final int EQ_CHANNEL_GROUP_COUNT_MAX = 4;
    static final int EQ_FILTER_BLOCK_COUNT_MAX = 4;
    static final int LOUD_EQ_INSTRUCTIONS_COUNT_MAX = 8;
    static final int EQ_INSTRUCTIONS_COUNT_MAX = 8;
    static final int SELECTED_EQ_SET_COUNT_MAX = 2;
    static final int SUB_EQ_COUNT = 2;
    static final int EXT_COUNT_MAX = 2;
    static final int PARAM_DRC_INSTRUCTIONS_COUNT_MAX = 8;
    static final int PARAM_DRC_INSTANCE_COUNT_MAX = 8;
    static final int PARAM_DRC_TYPE_FF = 0;
    static final int PARAM_DRC_TYPE_FF_NODE_COUNT_MAX = 9;
    static final int PARAM_DRC_TYPE_FF_LEVEL_ESTIM_FRAME_COUNT_MAX = 64;
    static final int PARAM_DRC_TYPE_LIM = 1;
    static final float PARAM_DRC_TYPE_LIM_THRESHOLD_DEFAULT = -1.f;
    static final int PARAM_DRC_TYPE_LIM_ATTACK_DEFAULT = 5;
    static final int PARAM_DRC_TYPE_LIM_RELEASE_DEFAULT = 50;
    static final int SPLIT_CHARACTERISTIC_NODE_COUNT_MAX = 4;
    static final int N_DELTA_TIME_CODE_TABLE_ENTRIES_MAX = 512 + 14;

    static final float UNDEFINED_LOUDNESS_VALUE = 1000.0f;
    static final int ID_FOR_BASE_LAYOUT = 0x0;
    static final int ID_FOR_ANY_DOWNMIX = 0x7F;
    static final int ID_FOR_NO_DRC = 0x0;
    static final int ID_FOR_ANY_DRC = 0x3F;
    static final int ID_FOR_ANY_EQ = 0x3F;
    static final int LOCATION_SELECTED = 0x1;

    static final int UNIDRCCONFEXT_TERM = 0x0;
    static final int UNIDRCCONFEXT_PARAM_DRC = 0x1;
    static final int UNIDRCCONFEXT_V1 = 0x2;
    static final int UNIDRCLOUDEXT_TERM = 0x0;
    static final int UNIDRCLOUDEXT_EQ = 0x1;
    static final int UNIDRCGAINEXT_TERM = 0x0;

    static final int METHOD_DEFINITION_UNKNOWN_OTHER = 0;
    static final int METHOD_DEFINITION_PROGRAM_LOUDNESS = 1;
    static final int METHOD_DEFINITION_ANCHOR_LOUDNESS = 2;
    static final int METHOD_DEFINITION_MAX_OF_LOUDNESS_RANGE = 3;
    static final int METHOD_DEFINITION_MOMENTARY_LOUDNESS_MAX = 4;
    static final int METHOD_DEFINITION_SHORT_TERM_LOUDNESS_MAX = 5;
    static final int METHOD_DEFINITION_LOUDNESS_RANGE = 6;
    static final int METHOD_DEFINITION_MIXING_LEVEL = 7;
    static final int METHOD_DEFINITION_ROOM_TYPE = 8;
    static final int METHOD_DEFINITION_SHORT_TERM_LOUDNESS = 9;

    static final int MEASUREMENT_SYSTEM_UNKNOWN_OTHER = 0;
    static final int MEASUREMENT_SYSTEM_EBU_R_128 = 1;
    static final int MEASUREMENT_SYSTEM_BS_1770_4 = 2;
    static final int MEASUREMENT_SYSTEM_BS_1770_4_PRE_PROCESSING = 3;
    static final int MEASUREMENT_SYSTEM_USER = 4;
    static final int MEASUREMENT_SYSTEM_EXPERT_PANEL = 5;
    static final int MEASUREMENT_SYSTEM_BS_1771_1 = 6;
    static final int MEASUREMENT_SYSTEM_RESERVED_A = 7;
    static final int MEASUREMENT_SYSTEM_RESERVED_B = 8;
    static final int MEASUREMENT_SYSTEM_RESERVED_C = 9;
    static final int MEASUREMENT_SYSTEM_RESERVED_D = 10;
    static final int MEASUREMENT_SYSTEM_RESERVED_E = 11;

    static final int RELIABILITY_UKNOWN = 0;
    static final int RELIABILITY_UNVERIFIED = 1;
    static final int RELIABILITY_CEILING = 2;
    static final int RELIABILITY_ACCURATE = 3;

    static final int EFFECT_BIT_COUNT = 12;
    static final int EFFECT_BIT_NONE = -1;
    static final int EFFECT_BIT_NIGHT = 0x0001;
    static final int EFFECT_BIT_NOISY = 0x0002;
    static final int EFFECT_BIT_LIMITED = 0x0004;
    static final int EFFECT_BIT_LOWLEVEL = 0x0008;
    static final int EFFECT_BIT_DIALOG = 0x0010;
    static final int EFFECT_BIT_GENERAL_COMPR = 0x0020;
    static final int EFFECT_BIT_EXPAND = 0x0040;
    static final int EFFECT_BIT_ARTISTIC = 0x0080;
    static final int EFFECT_BIT_CLIPPING = 0x0100;
    static final int EFFECT_BIT_FADE = 0x0200;
    static final int EFFECT_BIT_DUCK_OTHER = 0x0400;
    static final int EFFECT_BIT_DUCK_SELF = 0x0800;

    static final int GAIN_CODING_PROFILE_REGULAR = 0;
    static final int GAIN_CODING_PROFILE_FADING = 1;
    static final int GAIN_CODING_PROFILE_CLIPPING = 2;
    static final int GAIN_CODING_PROFILE_CONSTANT = 3;
    static final int GAIN_INTERPOLATION_TYPE_SPLINE = 0;
    static final int GAIN_INTERPOLATION_TYPE_LINEAR = 1;

    static final int DRC_COMPLEXITY_LEVEL_MAX = 0xF;
    static final int EQ_COMPLEXITY_LEVEL_MAX = 0xF;
    static final int LEFT_SIDE = 0;
    static final int RIGHT_SIDE = 1;
    static final int CHARACTERISTIC_SIGMOID = 0;
    static final int CHARACTERISTIC_NODES = 1;
    static final int CHARACTERISTIC_PASS_THRU = 2;
    static final int GAINFORMAT_QMF32 = 0x1;
    static final int GAINFORMAT_QMFHYBRID39 = 0x2;
    static final int GAINFORMAT_QMF64 = 0x3;
    static final int GAINFORMAT_QMFHYBRID71 = 0x4;
    static final int GAINFORMAT_QMF128 = 0x5;
    static final int GAINFORMAT_QMFHYBRID135 = 0x6;
    static final int GAINFORMAT_UNIFORM = 0x7;
    static final float DRC_INPUT_LOUDNESS_TARGET = -31.0f;
    static final int SHAPE_FILTER_TYPE_OFF = 0;
    static final int SHAPE_FILTER_TYPE_LF_CUT = 1;
    static final int SHAPE_FILTER_TYPE_LF_BOOST = 2;
    static final int SHAPE_FILTER_TYPE_HF_CUT = 3;
    static final int SHAPE_FILTER_TYPE_HF_BOOST = 4;
    static final float SHAPE_FILTER_DRC_GAIN_MAX_MINUS_ONE = 1583.8931924611f;
    static final float SLOPE_FACTOR_DB_TO_LINEAR = 0.1151f;
    static final float MAXPACKETLOSSTIME = 2.5f;

    static final int DELAY_MODE_REGULAR_DELAY = 0;
    static final int DELAY_MODE_LOW_DELAY = 1;

    /**
     * Holds the {@code ParametricDrcTypeFeedForward} structure.
     */
    static final class ParametricDrcTypeFeedForward {
        int levelEstimKWeightingType;
        int levelEstimIntegrationTimePresent;
        int levelEstimIntegrationTime;
        int drcCurveDefinitionType;
        int drcCharacteristic;
        int nodeCount;
        final int[] nodeLevel = new int[PARAM_DRC_TYPE_FF_NODE_COUNT_MAX];
        final int[] nodeGain = new int[PARAM_DRC_TYPE_FF_NODE_COUNT_MAX];
        int drcGainSmoothParametersPresent;
        int gainSmoothAttackTimeSlow;
        int gainSmoothReleaseTimeSlow;
        int gainSmoothTimeFastPresent;
        int gainSmoothAttackTimeFast;
        int gainSmoothReleaseTimeFast;
        int gainSmoothThresholdPresent;
        int gainSmoothAttackThreshold;
        int gainSmoothReleaseThreshold;
        int gainSmoothHoldOffCountPresent;
        int gainSmoothHoldOff;
        int disableParamtricDrc;
    }

    /**
     * Holds the {@code ParametricDrcTypeLim} structure.
     */
    static final class ParametricDrcTypeLim {
        int parametricLimThresholdPresent;
        float parametricLimThreshold;
        int parametricLimAttack;
        int parametricLimReleasePresent;
        int parametricLimRelease;
        int drcCharacteristic;
        int disableParamtricDrc;
    }

    /**
     * Holds the {@code parametricDrcInstructions()} structure.
     */
    static final class ParametricDrcInstructions {
        int parametricDrcId;
        int parametricDrcLookAheadPresent;
        int parametricDrcLookAhead;
        int parametricDrcPresetIdPresent;
        int parametricDrcPresetId;
        int parametricDrcType;
        int lenBitSize;
        final ParametricDrcTypeFeedForward parametricDrcTypeFeedForward = new ParametricDrcTypeFeedForward();
        final ParametricDrcTypeLim parametricDrcTypeLim = new ParametricDrcTypeLim();
        int drcCharacteristic;
        int disableParamtricDrc;
    }

    /**
     * Holds the {@code parametricDrcGainSetParams()} structure.
     */
    static final class ParametricDrcGainSetParams {
        int parametricDrcId;
        int sideChainConfigType;
        int downmixId;
        int levelEstimChannelWeightFormat;
        final float[] levelEstimChannelWeight = new float[CHANNEL_COUNT_MAX];
        int drcInputLoudnessPresent;
        float drcInputLoudness;
        int channelCountFromDownmixId;
    }

    /**
     * Holds the {@code drcCoefficientsParametricDrc()} structure.
     */
    static final class DrcCoefficientsParametricDrc {
        int drcLocation;
        int parametricDrcFrameSizeFormat;
        int parametricDrcFrameSize;
        int parametricDrcDelayMaxPresent;
        int parametricDrcDelayMax;
        int resetParametricDrc;
        int parametricDrcGainSetCount;
        final ParametricDrcGainSetParams[] parametricDrcGainSetParams = array(SEQUENCE_COUNT_MAX,
                ParametricDrcGainSetParams.class, ParametricDrcGainSetParams::new);
    }

    /**
     * Holds the {@code channelLayout()} structure.
     */
    static final class ChannelLayout {
        int baseChannelCount;
        int layoutSignalingPresent;
        int definedLayout;
        final int[] speakerPosition = new int[SPEAKER_POS_COUNT_MAX];
    }

    /**
     * Holds the {@code downmixInstructions()} structure.
     */
    static final class DownmixInstructions {
        int downmixId;
        int targetChannelCount;
        int targetLayout;
        int downmixCoefficientsPresent;
        final float[] downmixCoefficient = new float[DOWNMIX_COEFF_COUNT_MAX];
    }

    /**
     * Holds the parameters of one band of a gain set.
     */
    static final class GainParams {
        int gainSequenceIndex;
        int drcCharacteristic;
        int drcCharacteristicPresent;
        int drcCharacteristicFormatIsCICP;
        int drcCharacteristicLeftIndex;
        int drcCharacteristicRightIndex;
        int crossoverFreqIndex;
        int startSubBandIndex;
    }

    /**
     * Holds the ducking scaling of a channel or a channel group.
     */
    static final class DuckingModifiers {
        int duckingScalingPresent;
        float duckingScaling;
        float duckingScalingQuantized;

        void copyFrom(DuckingModifiers other) {
            duckingScalingPresent = other.duckingScalingPresent;
            duckingScaling = other.duckingScaling;
            duckingScalingQuantized = other.duckingScalingQuantized;
        }
    }

    /**
     * Holds the gain modifiers of a channel group.
     */
    static final class GainModifiers {
        final int[] targetCharacteristicLeftPresent = new int[DRC_BAND_COUNT_MAX];
        final int[] targetCharacteristicLeftIndex = new int[DRC_BAND_COUNT_MAX];
        final int[] targetCharacteristicRightPresent = new int[DRC_BAND_COUNT_MAX];
        final int[] targetCharacteristicRightIndex = new int[DRC_BAND_COUNT_MAX];
        int shapeFilterPresent;
        int shapeFilterIndex;
        final int[] gainScalingPresent = new int[BAND_COUNT_MAX];
        final float[] attenuationScaling = new float[BAND_COUNT_MAX];
        final float[] amplificationScaling = new float[BAND_COUNT_MAX];
        final int[] gainOffsetPresent = new int[BAND_COUNT_MAX];
        final float[] gainOffset = new float[BAND_COUNT_MAX];
    }

    /**
     * Holds the delta time code table of a gain set ({@code Tables}).
     */
    static final class Tables {
        final int[] size = new int[N_DELTA_TIME_CODE_TABLE_ENTRIES_MAX];
        final int[] code = new int[N_DELTA_TIME_CODE_TABLE_ENTRIES_MAX];
        final int[] value = new int[N_DELTA_TIME_CODE_TABLE_ENTRIES_MAX];
    }

    /**
     * Holds the {@code gainSetParams()} structure.
     */
    static final class GainSetParams {
        int gainCodingProfile;
        int gainInterpolationType;
        int fullFrame;
        int timeAlignment;
        int timeDeltaMinPresent;
        int timeDeltaMin;
        int bandCount;
        int drcBandType;
        final GainParams[] gainParams = array(BAND_COUNT_MAX, GainParams.class, GainParams::new);
        int nGainValuesMax;
        Tables tables;
    }

    /**
     * Holds the parameters of one side of a split DRC characteristic.
     */
    static final class SplitDrcCharacteristic {
        int characteristicFormat;
        float ioRatio;
        float gain;
        float exp;
        int flipSign;
        int characteristicNodeCount;
        final float[] nodeLevel = new float[SPLIT_CHARACTERISTIC_NODE_COUNT_MAX + 1];
        final float[] nodeGain = new float[SPLIT_CHARACTERISTIC_NODE_COUNT_MAX + 1];
    }

    /**
     * Holds the corner frequency and the strength of a shape filter.
     */
    static final class ShapeFilterParams {
        int cornerFreqIndex;
        int filterStrengthIndex;
    }

    /**
     * Holds the shape filters of a shape filter block.
     */
    static final class ShapeFilterBlockParams {
        int lfCutFilterPresent;
        final ShapeFilterParams lfCutParams = new ShapeFilterParams();
        int lfBoostFilterPresent;
        final ShapeFilterParams lfBoostParams = new ShapeFilterParams();
        int hfCutFilterPresent;
        final ShapeFilterParams hfCutParams = new ShapeFilterParams();
        int hfBoostFilterPresent;
        final ShapeFilterParams hfBoostParams = new ShapeFilterParams();
    }

    /**
     * Holds the {@code drcCoefficientsBasic()} structure.
     */
    static final class DrcCoefficientsBasic {
        int drcLocation;
        int drcCharacteristic;
    }

    /**
     * Holds the {@code drcCoefficientsUniDrc()} structure.
     */
    static final class DrcCoefficientsUniDrc {
        int version;
        int drcLocation;
        int drcFrameSizePresent;
        int drcFrameSize;
        int gainSetCount;
        final GainSetParams[] gainSetParams = array(GAIN_SET_COUNT_MAX, GainSetParams.class, GainSetParams::new);
        int drcCharacteristicLeftPresent;
        int characteristicLeftCount;
        final SplitDrcCharacteristic[] splitCharacteristicLeft = array(SPLIT_CHARACTERISTIC_COUNT_MAX,
                SplitDrcCharacteristic.class, SplitDrcCharacteristic::new);
        int drcCharacteristicRightPresent;
        int characteristicRightCount;
        final SplitDrcCharacteristic[] splitCharacteristicRight = array(SPLIT_CHARACTERISTIC_COUNT_MAX,
                SplitDrcCharacteristic.class, SplitDrcCharacteristic::new);
        int shapeFiltersPresent;
        int shapeFilterCount;
        final ShapeFilterBlockParams[] shapeFilterBlockParams = array(SHAPE_FILTER_COUNT_MAX + 1,
                ShapeFilterBlockParams.class, ShapeFilterBlockParams::new);
        int gainSequenceCount;
        final int[] gainSetParamsIndexForGainSequence = new int[SEQUENCE_COUNT_MAX];
        int gainSetCountPlus;
    }

    /**
     * Holds the {@code drcInstructionsBasic()} structure.
     */
    static final class DrcInstructionsBasic {
        int drcSetId;
        int drcLocation;
        int downmixIdCount;
        final int[] downmixId = new int[DOWNMIX_ID_COUNT_MAX];
        int drcSetEffect;
        int limiterPeakTargetPresent;
        float limiterPeakTarget;
        int drcSetTargetLoudnessPresent;
        int drcSetTargetLoudnessValueUpper;
        int drcSetTargetLoudnessValueLowerPresent;
        int drcSetTargetLoudnessValueLower;
    }

    /**
     * Holds the {@code drcInstructionsUniDrc()} structure and the data derived from it.
     */
    static final class DrcInstructionsUniDrc {
        int drcSetId;
        int drcSetComplexityLevel;
        int requiresEq;
        int drcApplyToDownmix;
        int drcLocation;
        int downmixIdCount;
        final int[] downmixId = new int[DOWNMIX_ID_COUNT_MAX];
        int dependsOnDrcSetPresent;
        int dependsOnDrcSet;
        int noIndependentUse;
        int drcSetEffect;
        final int[] gainSetIndex = new int[CHANNEL_COUNT_MAX];
        final GainModifiers[] gainModifiersForChannelGroup = array(CHANNEL_GROUP_COUNT_MAX, GainModifiers.class,
                GainModifiers::new);
        final DuckingModifiers[] duckingModifiersForChannel = array(CHANNEL_COUNT_MAX, DuckingModifiers.class,
                DuckingModifiers::new);
        int limiterPeakTargetPresent;
        float limiterPeakTarget;
        int drcSetTargetLoudnessPresent;
        int drcSetTargetLoudnessValueUpper;
        int drcSetTargetLoudnessValueLowerPresent;
        int drcSetTargetLoudnessValueLower;
        int audioChannelCount;
        int nDrcChannelGroups;
        final int[] gainSetIndexForChannelGroup = new int[CHANNEL_GROUP_COUNT_MAX];
        final int[] bandCountForChannelGroup = new int[CHANNEL_GROUP_COUNT_MAX];
        final int[] gainInterpolationTypeForChannelGroup = new int[CHANNEL_GROUP_COUNT_MAX];
        final int[] timeDeltaMinForChannelGroup = new int[CHANNEL_GROUP_COUNT_MAX];
        final int[] timeAlignmentForChannelGroup = new int[CHANNEL_GROUP_COUNT_MAX];
        final DuckingModifiers[] duckingModifiersForChannelGroup = array(CHANNEL_GROUP_COUNT_MAX,
                DuckingModifiers.class, DuckingModifiers::new);
        final int[] channelGroupForChannel = new int[CHANNEL_COUNT_MAX];
        final int[] nChannelsPerChannelGroup = new int[CHANNEL_GROUP_COUNT_MAX];
        int gainElementCount;
        int multibandAudioSignalCount;
        final int[] channelGroupIsParametricDrc = new int[CHANNEL_GROUP_COUNT_MAX];
        final int[] gainSetIndexForChannelGroupParametricDrc = new int[CHANNEL_GROUP_COUNT_MAX];
        final int[] parametricDrcLookAheadSamples = new int[CHANNEL_GROUP_COUNT_MAX];
        int parametricDrcLookAheadSamplesMax;

        /**
         * Resets every field to zero ({@code memset}).
         */
        void clear() {
            drcSetId = 0;
            drcSetComplexityLevel = 0;
            requiresEq = 0;
            drcApplyToDownmix = 0;
            drcLocation = 0;
            downmixIdCount = 0;
            java.util.Arrays.fill(downmixId, 0);
            dependsOnDrcSetPresent = 0;
            dependsOnDrcSet = 0;
            noIndependentUse = 0;
            drcSetEffect = 0;
            java.util.Arrays.fill(gainSetIndex, 0);
            for (var i = 0; i < gainModifiersForChannelGroup.length; i++) {
                gainModifiersForChannelGroup[i] = new GainModifiers();
            }
            for (var i = 0; i < duckingModifiersForChannel.length; i++) {
                duckingModifiersForChannel[i] = new DuckingModifiers();
            }
            limiterPeakTargetPresent = 0;
            limiterPeakTarget = 0;
            drcSetTargetLoudnessPresent = 0;
            drcSetTargetLoudnessValueUpper = 0;
            drcSetTargetLoudnessValueLowerPresent = 0;
            drcSetTargetLoudnessValueLower = 0;
            audioChannelCount = 0;
            nDrcChannelGroups = 0;
            java.util.Arrays.fill(gainSetIndexForChannelGroup, 0);
            java.util.Arrays.fill(bandCountForChannelGroup, 0);
            java.util.Arrays.fill(gainInterpolationTypeForChannelGroup, 0);
            java.util.Arrays.fill(timeDeltaMinForChannelGroup, 0);
            java.util.Arrays.fill(timeAlignmentForChannelGroup, 0);
            for (var i = 0; i < duckingModifiersForChannelGroup.length; i++) {
                duckingModifiersForChannelGroup[i] = new DuckingModifiers();
            }
            java.util.Arrays.fill(channelGroupForChannel, 0);
            java.util.Arrays.fill(nChannelsPerChannelGroup, 0);
            gainElementCount = 0;
            multibandAudioSignalCount = 0;
            java.util.Arrays.fill(channelGroupIsParametricDrc, 0);
            java.util.Arrays.fill(gainSetIndexForChannelGroupParametricDrc, 0);
            java.util.Arrays.fill(parametricDrcLookAheadSamples, 0);
            parametricDrcLookAheadSamplesMax = 0;
        }
    }

    /**
     * Holds one {@code loudnessMeasure()}.
     */
    static final class LoudnessMeasure {
        int methodDefinition;
        float methodValue;
        int measurementSystem;
        int reliability;
    }

    /**
     * Holds the {@code loudnessInfo()} structure.
     */
    static final class LoudnessInfo {
        int drcSetId;
        int eqSetId;
        int downmixId;
        int samplePeakLevelPresent;
        float samplePeakLevel;
        int truePeakLevelPresent;
        float truePeakLevel;
        int truePeakLevelMeasurementSystem;
        int truePeakLevelReliability;
        int measurementCount;
        final LoudnessMeasure[] loudnessMeasure = array(MEASUREMENT_COUNT_MAX, LoudnessMeasure.class,
                LoudnessMeasure::new);
    }

    /**
     * Holds the {@code loudEqInstructions()} structure.
     */
    static final class LoudEqInstructions {
        int loudEqSetId;
        int drcLocation;
        int downmixIdCount;
        final int[] downmixId = new int[DOWNMIX_ID_COUNT_MAX];
        int drcSetIdCount;
        final int[] drcSetId = new int[DRC_SET_ID_COUNT_MAX];
        int eqSetIdCount;
        final int[] eqSetId = new int[EQ_SET_ID_COUNT_MAX];
        int loudnessAfterDrc;
        int loudnessAfterEq;
        int loudEqGainSequenceCount;
        final int[] gainSequenceIndex = new int[LOUD_EQ_GAIN_SEQUENCE_COUNT_MAX];
        final int[] drcCharacteristicFormatIsCICP = new int[LOUD_EQ_GAIN_SEQUENCE_COUNT_MAX];
        final int[] drcCharacteristic = new int[LOUD_EQ_GAIN_SEQUENCE_COUNT_MAX];
        final int[] drcCharacteristicLeftIndex = new int[LOUD_EQ_GAIN_SEQUENCE_COUNT_MAX];
        final int[] drcCharacteristicRightIndex = new int[LOUD_EQ_GAIN_SEQUENCE_COUNT_MAX];
        final int[] frequencyRangeIndex = new int[LOUD_EQ_GAIN_SEQUENCE_COUNT_MAX];
        final float[] loudEqScaling = new float[LOUD_EQ_GAIN_SEQUENCE_COUNT_MAX];
        final float[] loudEqOffset = new float[LOUD_EQ_GAIN_SEQUENCE_COUNT_MAX];
    }

    /**
     * Holds one element of an equalizer filter block.
     */
    static final class FilterElement {
        int filterElementIndex;
        int filterElementGainPresent;
        float filterElementGain;
    }

    /**
     * Holds the {@code filterBlock()} structure.
     */
    static final class FilterBlock {
        int filterElementCount;
        final FilterElement[] filterElement = array(FILTER_ELEMENT_COUNT_MAX, FilterElement.class,
                FilterElement::new);
    }

    /**
     * Holds the {@code uniqueTdFilterElement()} structure.
     */
    static final class UniqueTdFilterElement {
        int eqFilterFormat;
        int realZeroRadiusOneCount;
        int realZeroCount;
        int genericZeroCount;
        int realPoleCount;
        int complexPoleCount;
        final int[] zeroSign = new int[REAL_ZERO_RADIUS_ONE_COUNT_MAX];
        final float[] realZeroRadius = new float[REAL_ZERO_COUNT_MAX];
        final float[] genericZeroRadius = new float[COMPLEX_ZERO_COUNT_MAX];
        final float[] genericZeroAngle = new float[COMPLEX_ZERO_COUNT_MAX];
        final float[] realPoleRadius = new float[REAL_POLE_COUNT_MAX];
        final float[] complexPoleRadius = new float[COMPLEX_POLE_COUNT_MAX];
        final float[] complexPoleAngle = new float[COMPLEX_POLE_COUNT_MAX];
        int firFilterOrder;
        int firSymmetry;
        final float[] firCoefficient = new float[FIR_ORDER_MAX / 2 + 1];
    }

    /**
     * Holds the {@code eqSubbandGainSpline()} structure.
     */
    static final class EqSubbandGainSpline {
        int nEqNodes;
        final float[] eqSlope = new float[EQ_NODE_COUNT_MAX];
        final int[] eqFreqDelta = new int[EQ_NODE_COUNT_MAX];
        float eqGainInitial;
        final float[] eqGainDelta = new float[EQ_NODE_COUNT_MAX];
    }

    /**
     * Holds the {@code eqSubbandGainVector()} structure.
     */
    static final class EqSubbandGainVector {
        final float[] eqSubbandGain = new float[EQ_SUBBAND_GAIN_COUNT_MAX];
    }

    /**
     * Holds the {@code eqCoefficients()} structure.
     */
    static final class EqCoefficients {
        int eqDelayMaxPresent;
        int eqDelayMax;
        int uniqueFilterBlockCount;
        final FilterBlock[] filterBlock = array(FILTER_BLOCK_COUNT_MAX, FilterBlock.class, FilterBlock::new);
        int uniqueTdFilterElementCount;
        final UniqueTdFilterElement[] uniqueTdFilterElement = array(FILTER_ELEMENT_COUNT_MAX,
                UniqueTdFilterElement.class, UniqueTdFilterElement::new);
        int uniqueEqSubbandGainsCount;
        int eqSubbandGainRepresentation;
        int eqSubbandGainFormat;
        int eqSubbandGainCount;
        final EqSubbandGainSpline[] eqSubbandGainSpline = array(UNIQUE_SUBBAND_GAIN_COUNT_MAX,
                EqSubbandGainSpline.class, EqSubbandGainSpline::new);
        final EqSubbandGainVector[] eqSubbandGainVector = array(UNIQUE_SUBBAND_GAIN_COUNT_MAX,
                EqSubbandGainVector.class, EqSubbandGainVector::new);
    }

    /**
     * Holds the filter block references of an equalizer channel group.
     */
    static final class FilterBlockRefs {
        int filterBlockCount;
        final int[] filterBlockIndex = new int[EQ_FILTER_BLOCK_COUNT_MAX];
    }

    /**
     * Holds the {@code tdFilterCascade()} structure.
     */
    static final class TdFilterCascade {
        final int[] eqCascadeGainPresent = new int[EQ_CHANNEL_GROUP_COUNT_MAX];
        final float[] eqCascadeGain = new float[EQ_CHANNEL_GROUP_COUNT_MAX];
        final FilterBlockRefs[] filterBlockRefs = array(EQ_CHANNEL_GROUP_COUNT_MAX, FilterBlockRefs.class,
                FilterBlockRefs::new);
        int eqPhaseAlignmentPresent;
        final int[][] eqPhaseAlignment = new int[EQ_CHANNEL_GROUP_COUNT_MAX][EQ_CHANNEL_GROUP_COUNT_MAX];
    }

    /**
     * Holds the {@code eqInstructions()} structure.
     */
    static final class EqInstructions {
        int eqSetId;
        int eqSetComplexityLevel;
        int downmixIdCount;
        final int[] downmixId = new int[DOWNMIX_ID_COUNT_MAX];
        int eqApplyToDownmix;
        int drcSetIdCount;
        final int[] drcSetId = new int[DRC_SET_ID_COUNT_MAX];
        int eqSetPurpose;
        int dependsOnEqSetPresent;
        int dependsOnEqSet;
        int noIndependentEqUse;
        int eqChannelCount;
        int eqChannelGroupCount;
        final int[] eqChannelGroupForChannel = new int[CHANNEL_COUNT_MAX];
        int tdFilterCascadePresent;
        final TdFilterCascade tdFilterCascade = new TdFilterCascade();
        int subbandGainsPresent;
        final int[] subbandGainsIndex = new int[EQ_CHANNEL_GROUP_COUNT_MAX];
        int eqTransitionDurationPresent;
        float eqTransitionDuration;
    }

    /**
     * Holds the {@code uniDrcConfigExtension()} structure.
     */
    static final class UniDrcConfigExt {
        final int[] uniDrcConfigExtType = new int[EXT_COUNT_MAX + 64];
        final int[] extBitSize = new int[EXT_COUNT_MAX + 64];
        int parametricDrcPresent;
        final DrcCoefficientsParametricDrc drcCoefficientsParametricDrc = new DrcCoefficientsParametricDrc();
        int parametricDrcInstructionsCount;
        final ParametricDrcInstructions[] parametricDrcInstructions = array(PARAM_DRC_INSTRUCTIONS_COUNT_MAX,
                ParametricDrcInstructions.class, ParametricDrcInstructions::new);
        int drcExtensionV1Present;
        int loudEqInstructionsPresent;
        int loudEqInstructionsCount;
        final LoudEqInstructions[] loudEqInstructions = array(LOUD_EQ_INSTRUCTIONS_COUNT_MAX,
                LoudEqInstructions.class, LoudEqInstructions::new);
        int eqPresent;
        final EqCoefficients eqCoefficients = new EqCoefficients();
        int eqInstructionsCount;
        final EqInstructions[] eqInstructions = array(EQ_INSTRUCTIONS_COUNT_MAX, EqInstructions.class,
                EqInstructions::new);
    }

    /**
     * Holds the {@code uniDrcConfig()} structure.
     */
    static final class UniDrcConfig {
        int sampleRatePresent;
        int sampleRate;
        int downmixInstructionsCount;
        int drcCoefficientsUniDrcCount;
        int drcInstructionsUniDrcCount;
        int drcInstructionsCountPlus;
        int drcDescriptionBasicPresent;
        int drcCoefficientsBasicCount;
        int drcInstructionsBasicCount;
        int uniDrcConfigExtPresent;
        int applyDrc;
        final UniDrcConfigExt uniDrcConfigExt = new UniDrcConfigExt();
        final DrcCoefficientsBasic[] drcCoefficientsBasic = array(DRC_COEFF_COUNT_MAX, DrcCoefficientsBasic.class,
                DrcCoefficientsBasic::new);
        final DrcInstructionsBasic[] drcInstructionsBasic = array(DRC_INSTRUCTIONS_COUNT_MAX,
                DrcInstructionsBasic.class, DrcInstructionsBasic::new);
        final DrcCoefficientsUniDrc[] drcCoefficientsUniDrc = array(DRC_COEFF_COUNT_MAX, DrcCoefficientsUniDrc.class,
                DrcCoefficientsUniDrc::new);
        final DrcInstructionsUniDrc[] drcInstructionsUniDrc = array(DRC_INSTRUCTIONS_COUNT_MAX,
                DrcInstructionsUniDrc.class, DrcInstructionsUniDrc::new);
        final ChannelLayout channelLayout = new ChannelLayout();
        final DownmixInstructions[] downmixInstructions = array(DOWNMIX_INSTRUCTION_COUNT_MAX,
                DownmixInstructions.class, DownmixInstructions::new);
    }

    /**
     * Holds the {@code loudnessInfoSet()} structure.
     */
    static final class LoudnessInfoSet {
        int loudnessInfoAlbumCount;
        int loudnessInfoCount;
        int loudnessInfoSetExtPresent;
        final LoudnessInfo[] loudnessInfoAlbum = array(LOUDNESS_INFO_COUNT_MAX, LoudnessInfo.class,
                LoudnessInfo::new);
        final LoudnessInfo[] loudnessInfo = array(LOUDNESS_INFO_COUNT_MAX, LoudnessInfo.class, LoudnessInfo::new);
        final int[] loudnessInfoSetExtType = new int[EXT_COUNT_MAX + 64];
        final int[] extBitSize = new int[EXT_COUNT_MAX + 64];
    }

    /**
     * Holds one node of a gain spline.
     */
    static final class Node {
        float gainDb;
        float slope;
        int time;
    }

    /**
     * Holds the {@code splineNodes()} structure.
     */
    static final class SplineNodes {
        int drcGainCodingMode;
        int nNodes;
        final Node[] node = array(NODE_COUNT_MAX, Node.class, Node::new);
    }

    /**
     * Holds the {@code uniDrcGain()} structure.
     */
    static final class UniDrcGain {
        int nDrcGainSequences;
        final SplineNodes[] drcGainSequence = array(SEQUENCE_COUNT_MAX, SplineNodes.class, SplineNodes::new);
        int uniDrcGainExtPresent;
        final int[] uniDrcGainExtType = new int[EXT_COUNT_MAX + 64];
        final int[] extBitSize = new int[EXT_COUNT_MAX + 64];
    }

    /**
     * Holds the parameters that the bitstream decoder derives from the audio stream ({@code DrcParamsBsDec}).
     */
    static final class DrcParamsBsDec {
        int deltaTminDefault;
        int sampleRateDefault;
        int drcFrameSize;
        int nGainValuesMaxDefault;
        int delayMode;
        int lfeChannelMapCount;
        final int[] lfeChannelMap = new int[CHANNEL_COUNT_MAX];
    }

    @SuppressWarnings("unchecked")
    static <T> T[] array(int length, Class<T> type, java.util.function.Supplier<T> factory) {
        var out = (T[]) java.lang.reflect.Array.newInstance(type, length);
        for (var i = 0; i < length; i++) {
            out[i] = factory.get();
        }
        return out;
    }

    /**
     * Prevents instantiation.
     */
    private DrcModel() {
        throw new AssertionError();
    }
}
