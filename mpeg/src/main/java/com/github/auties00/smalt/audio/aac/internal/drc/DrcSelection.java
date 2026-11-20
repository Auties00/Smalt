/* Ports the ISO/IEC 23003-4 reference software selection process (uniDrcSelectionProcess.c,
   uniDrcSelectionProcess_init.c, uniDrcSelectionProcess_drcSetSelection.c, uniDrcSelectionProcess_loudnessControl.c,
   without the MPEG-H syntax) and the host defaults of uniDrcHostParams.c. Copyright Apple Inc., Fraunhofer IIS and
   ISO/IEC 2014; ISO/IEC reference software licence (use in products conforming to ISO/IEC 23003-4). Modified for
   Smalt. */
package com.github.auties00.smalt.audio.aac.internal.drc;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.aac.internal.drc.DrcModel.*;

/**
 * Selects the DRC sets, the equalizer sets, the downmix and the loudness normalization gain that a decoder applies
 * (ISO/IEC 23003-4 clause 6.3 and Annex E).
 *
 * <p>Instances are mutable and not thread-safe.
 */
final class DrcSelection {
    static final int EFFECT_TYPE_REQUESTED_NONE = 0;
    static final int EFFECT_TYPE_REQUESTED_NIGHT = 1;
    static final int EFFECT_TYPE_REQUESTED_NOISY = 2;
    static final int EFFECT_TYPE_REQUESTED_LIMITED = 3;
    static final int EFFECT_TYPE_REQUESTED_LOWLEVEL = 4;
    static final int EFFECT_TYPE_REQUESTED_DIALOG = 5;
    static final int EFFECT_TYPE_REQUESTED_GENERAL_COMPR = 6;
    static final int EFFECT_TYPE_REQUESTED_EXPAND = 7;
    static final int EFFECT_TYPE_REQUESTED_ARTISTIC = 8;
    static final int MATCH_EFFECT_TYPE = 0;
    static final int MATCH_DYNAMIC_RANGE = 1;
    static final int MATCH_DRC_CHARACTERISTIC = 2;
    static final int MAX_NUM_DOWNMIX_ID_REQUESTS = 15;
    static final int MAX_NUM_DRC_FEATURE_REQUESTS = 7;
    static final int MAX_NUM_DRC_EFFECT_TYPE_REQUESTS = 15;
    static final int LOUDNESS_DEVIATION_MAX_DEFAULT = 63;
    static final float LOUDNESS_NORMALIZATION_GAIN_MAX_DEFAULT = 1000;
    static final int SUBBAND_DOMAIN_MODE_OFF = 0;
    private static final int SHORT_TERM_LOUDNESS_TO_AVG = 0;
    private static final int MOMENTARY_LOUDNESS_TO_AVG = 1;
    private static final int TOP_OF_LOUDNESS_RANGE_TO_AVG = 2;
    private static final int SELECTION_FLAG_DRC_TARGET_LOUDNESS_MATCH = 1;
    private static final int SELECTION_FLAG_EXPLICIT_PEAK_INFO_PRESENT = 1 << 1;
    private static final int SELECTION_CANDIDATE_COUNT_MAX = 32;
    private static final int CANDIDATE_CAPACITY = 1024;
    private static final int LOUD_EQ_REQUEST_OFF = 0;
    private static final int EQ_PURPOSE_EQ_OFF = 0;
    private static final int EQ_PURPOSE_DEFAULT = 1;
    private static final float COMPLEXITY_LEVEL_SUPPORTED_TOTAL = 20.0f;
    private static final float MIXING_LEVEL_DEFAULT = 85.0f;
    private static final int ID_TABLE_SIZE = 64;
    private static final int PEAK_TABLE_SIZE = 256;

    private static final int[] EFFECT_TYPES_REQUESTABLE = {EFFECT_BIT_NIGHT, EFFECT_BIT_NOISY, EFFECT_BIT_LIMITED,
            EFFECT_BIT_LOWLEVEL, EFFECT_BIT_DIALOG, EFFECT_BIT_GENERAL_COMPR, EFFECT_BIT_EXPAND, EFFECT_BIT_ARTISTIC};

    private static final int[][] DRC_CHARACTERISTIC_ORDER_DEFAULT = {{1, 2, -1}, {2, 3, 1}, {3, 4, 2}, {4, 5, 3},
            {5, 6, 4}, {6, 5, -1}, {7, 9, -1}, {8, 10, -1}, {9, 7, -1}, {10, 8, -1}, {11, 10, 9}};

    /**
     * Holds the requests of the playback system ({@code UniDrcSelProcParams}).
     */
    static final class Params {
        int baseChannelCount;
        int baseLayout;
        int targetConfigRequestType;
        int numDownmixIdRequests;
        final int[] downmixIdRequested = new int[MAX_NUM_DOWNMIX_ID_REQUESTS];
        int targetLayoutRequested;
        int targetChannelCountRequested;
        int targetChannelCountPrelim;
        int loudnessNormalizationOn;
        float targetLoudness;
        int albumMode;
        int peakLimiterPresent;
        int loudnessDeviationMax;
        int loudnessMeasurementMethod;
        int loudnessMeasurementSystem;
        int loudnessMeasurementPreProc;
        int deviceCutOffFrequency;
        float loudnessNormalizationGainDbMax;
        float loudnessNormalizationGainModificationDb;
        float outputPeakLevelMax;
        int numBandsSupported;
        int dynamicRangeControlOn;
        int numDrcFeatureRequests;
        final int[] drcFeatureRequestType = new int[MAX_NUM_DRC_FEATURE_REQUESTS];
        final int[] numDrcEffectTypeRequests = new int[MAX_NUM_DRC_FEATURE_REQUESTS];
        final int[] numDrcEffectTypeRequestsDesired = new int[MAX_NUM_DRC_FEATURE_REQUESTS];
        final int[][] drcEffectTypeRequest = new int[MAX_NUM_DRC_FEATURE_REQUESTS][MAX_NUM_DRC_EFFECT_TYPE_REQUESTS];
        final int[] dynamicRangeMeasurementRequestType = new int[MAX_NUM_DRC_FEATURE_REQUESTS];
        final int[] dynamicRangeRequestedIsRange = new int[MAX_NUM_DRC_FEATURE_REQUESTS];
        final float[] dynamicRangeRequestValue = new float[MAX_NUM_DRC_FEATURE_REQUESTS];
        final float[] dynamicRangeRequestValueMin = new float[MAX_NUM_DRC_FEATURE_REQUESTS];
        final float[] dynamicRangeRequestValueMax = new float[MAX_NUM_DRC_FEATURE_REQUESTS];
        final int[] drcCharacteristicRequest = new int[MAX_NUM_DRC_FEATURE_REQUESTS];
        int loudnessEqRequest;
        float sensitivity;
        float playbackGain;
        int eqSetPurposeRequest;
        float boost;
        float compress;
        int drcCharacteristicTarget;

        /**
         * Constructs the default requests of a playback system ({@code setDefaultParams_selectionProcess}).
         */
        Params() {
            baseChannelCount = -1;
            baseLayout = -1;
            loudnessNormalizationOn = 0;
            targetLoudness = -24.0f;
            loudnessDeviationMax = LOUDNESS_DEVIATION_MAX_DEFAULT;
            deviceCutOffFrequency = 500;
            loudnessNormalizationGainDbMax = LOUDNESS_NORMALIZATION_GAIN_MAX_DEFAULT;
            numBandsSupported = 4;
            boost = 1.0f;
            compress = 1.0f;
            sensitivity = 90.0f;
            playbackGain = -15.0f;
        }

        /**
         * Requests effect types from a packed list of nibbles, the first one desired and the others fall-backs
         * ({@code setDrcEffectTypeRequest} of the DRC tool decoder).
         *
         * @param packed the requests, the first in the least significant nibble
         */
        void requestEffects(long packed) {
            dynamicRangeControlOn = 1;
            numDrcFeatureRequests = 1;
            drcFeatureRequestType[0] = MATCH_EFFECT_TYPE;
            numDrcEffectTypeRequests[0] = 0;
            numDrcEffectTypeRequestsDesired[0] = 1;
            var tmp = packed;
            for (var i = 0; i < 8; i++) {
                var requested = (int) (tmp & 15);
                if (i == 0 || requested != 0) {
                    numDrcEffectTypeRequests[0]++;
                    drcEffectTypeRequest[0][i] = requested;
                } else {
                    break;
                }
                tmp >>>= 4;
            }
        }
    }

    /**
     * Holds the result of the selection ({@code UniDrcSelProcOutput}).
     */
    static final class Output {
        float outputPeakLevelDb;
        float loudnessNormalizationGainDb;
        float outputLoudness;
        final int[] selectedDrcSetIds = new int[SUB_DRC_COUNT];
        final int[] selectedDownmixIds = new int[SUB_DRC_COUNT];
        int numSelectedDrcSets;
        int activeDownmixId;
        int baseChannelCount;
        int targetChannelCount;
        int targetLayout;
        int downmixMatrixPresent;
        float[][] downmixMatrix = new float[0][0];
        float boost;
        float compress;
        int drcCharacteristicTarget;
        float mixingLevel;
        final int[] selectedEqSetIds = new int[SELECTED_EQ_SET_COUNT_MAX];
        int selectedLoudEqId;

        void clear() {
            outputPeakLevelDb = 0;
            loudnessNormalizationGainDb = 0;
            outputLoudness = 0;
            Arrays.fill(selectedDrcSetIds, 0);
            Arrays.fill(selectedDownmixIds, 0);
            numSelectedDrcSets = 0;
            activeDownmixId = 0;
            baseChannelCount = 0;
            targetChannelCount = 0;
            targetLayout = 0;
            downmixMatrixPresent = 0;
            for (var row : downmixMatrix) {
                Arrays.fill(row, 0);
            }
            boost = 0;
            compress = 0;
            drcCharacteristicTarget = 0;
            mixingLevel = 0;
            Arrays.fill(selectedEqSetIds, 0);
            selectedLoudEqId = 0;
        }
    }

    private static final class Candidate {
        int drcInstructionsIndex;
        int downmixIdRequestIndex;
        int eqSetId;
        float outputPeakLevel;
        float loudnessNormalizationGainDbAdjusted;
        float outputLoudness;
        float mixingLevel;
        int selectionFlags;

        void copyFrom(Candidate other) {
            drcInstructionsIndex = other.drcInstructionsIndex;
            downmixIdRequestIndex = other.downmixIdRequestIndex;
            eqSetId = other.eqSetId;
            outputPeakLevel = other.outputPeakLevel;
            loudnessNormalizationGainDbAdjusted = other.loudnessNormalizationGainDbAdjusted;
            outputLoudness = other.outputLoudness;
            mixingLevel = other.mixingLevel;
            selectionFlags = other.selectionFlags;
        }
    }

    final Params params;
    final Output output = new Output();
    private final int subbandDomainMode;
    private UniDrcConfig config;
    private LoudnessInfoSet loudnessInfoSet;
    private int drcInstructionsIndexSelected = -1;
    private final int[] subDrc = {-1, -1, -1, -1};
    private final int[] drcSetIdIsPermitted = new int[ID_TABLE_SIZE];
    private final int[] eqSetIdIsPermitted = new int[ID_TABLE_SIZE];
    private int eqInstructionsIndexSelected = -1;
    private final int[] subEq = {-1, -1};
    private int loudEqInstructionsIndexSelected = -1;
    private final Candidate[] candidates = array(CANDIDATE_CAPACITY, Candidate.class, Candidate::new);
    private final Candidate[] candidatesStep2 = array(CANDIDATE_CAPACITY, Candidate.class, Candidate::new);
    private final int[] noCompressionEqId = new int[PEAK_TABLE_SIZE];
    private final int[] eqSetIdForLoudness = new int[PEAK_TABLE_SIZE];
    private final float[] loudnessNormalizationGainDb = new float[PEAK_TABLE_SIZE];
    private final float[] loudness = new float[PEAK_TABLE_SIZE];
    private final int[] eqSetIdForPeak = new int[PEAK_TABLE_SIZE];
    private final float[] signalPeakLevel = new float[PEAK_TABLE_SIZE];
    private final int[] explicitPeakInformationPresent = new int[PEAK_TABLE_SIZE];
    private final LoudnessInfo[] matchingLoudnessInfo = new LoudnessInfo[PEAK_TABLE_SIZE];
    private final int[] selected = new int[CANDIDATE_CAPACITY];
    private int noCompressionEqCount;
    private int loudnessInfoCount;
    private int peakInfoCount;

    /**
     * Constructs a selection process with the requests of the playback system ({@code initUniDrcSelectionProcess}).
     *
     * @param params the requests; the selection keeps and updates them
     * @param subbandDomainMode the domain of the DRC gain application, {@link #SUBBAND_DOMAIN_MODE_OFF} for the time
     * domain
     */
    DrcSelection(Params params, int subbandDomainMode) {
        this.params = params;
        this.subbandDomainMode = subbandDomainMode;
        params.loudnessNormalizationGainDbMax = Math.max(0.0f, params.loudnessNormalizationGainDbMax);
        params.loudnessDeviationMax = Math.max(0, params.loudnessDeviationMax);
        drcSetIdIsPermitted[0] = 1;
        eqSetIdIsPermitted[0] = 1;
        output.outputLoudness = UNDEFINED_LOUDNESS_VALUE;
    }

    /**
     * Runs the selection for a configuration and its loudness information ({@code processUniDrcSelectionProcess}).
     *
     * @param config the configuration
     * @param loudnessInfoSet the loudness information
     * @throws MediaException.Malformed if the metadata admits no selection
     */
    void process(UniDrcConfig config, LoudnessInfoSet loudnessInfoSet) {
        this.config = config;
        this.loudnessInfoSet = loudnessInfoSet;
        if (params.baseChannelCount != config.channelLayout.baseChannelCount) {
            params.baseChannelCount = config.channelLayout.baseChannelCount;
        }
        if (config.channelLayout.layoutSignalingPresent == 1 && params.baseLayout != config.channelLayout.definedLayout) {
            params.baseLayout = config.channelLayout.definedLayout;
        }
        if (params.targetConfigRequestType != 0 || params.numDownmixIdRequests == 0) {
            mapTargetConfigRequestToDownmixId();
        }
        var baseCount = Math.max(0, config.channelLayout.baseChannelCount);
        var maxTarget = baseCount;
        for (var n = 0; n < config.downmixInstructionsCount; n++) {
            maxTarget = Math.max(maxTarget, config.downmixInstructions[n].targetChannelCount);
        }
        if (output.downmixMatrix.length < baseCount || (baseCount > 0 && output.downmixMatrix[0].length < maxTarget)) {
            output.downmixMatrix = new float[baseCount][maxTarget];
        }
        manageDrcComplexity();
        manageEqComplexity();
        var repeatSelection = true;
        var loopCount = 0;
        while (repeatSelection) {
            var choice = selectDrcSet();
            getSelectedDrcSet(choice[0]);
            getDependentDrcSet();
            getFadingDrcSet();
            getDuckingDrcSet();
            subEq[0] = -1;
            subEq[1] = -1;
            getSelectedEqSet(choice[1]);
            getDependentEqSet();
            getSelectedLoudEqSet(choice[2]);
            var activeDrcSetIndex = 0;
            for (var i = SUB_DRC_COUNT - 1; i >= 0; i--) {
                var index = subDrc[i];
                if (index >= 0) {
                    var instructions = config.drcInstructionsUniDrc[index];
                    if (instructions.drcSetId > 0) {
                        if (activeDrcSetIndex >= SUB_DRC_COUNT) {
                            throw new MediaException.Malformed("MPEG-D DRC selects too many DRC sets");
                        }
                        output.selectedDrcSetIds[activeDrcSetIndex] = instructions.drcSetId;
                        if (i == 3 && (instructions.drcSetEffect & (EFFECT_BIT_DUCK_SELF | EFFECT_BIT_DUCK_OTHER)) != 0) {
                            output.selectedDownmixIds[activeDrcSetIndex] = 0;
                        } else if (instructions.drcApplyToDownmix == 1) {
                            output.selectedDownmixIds[activeDrcSetIndex] = instructions.downmixId[0];
                        } else {
                            output.selectedDownmixIds[activeDrcSetIndex] = 0;
                        }
                        activeDrcSetIndex++;
                    }
                }
            }
            if (activeDrcSetIndex > 3) {
                throw new MediaException.Malformed("MPEG-D DRC selects too many DRC sets");
            }
            output.numSelectedDrcSets = activeDrcSetIndex;
            selectDownmixMatrix();
            repeatSelection = manageComplexity();
            if (++loopCount > 100) {
                throw new MediaException.Malformed("MPEG-D DRC selection does not converge");
            }
        }
        output.boost = params.boost;
        output.compress = params.compress;
        output.drcCharacteristicTarget = params.drcCharacteristicTarget;
        output.loudnessNormalizationGainDb += params.loudnessNormalizationGainModificationDb;
        for (var i = 0; i < 2; i++) {
            output.selectedEqSetIds[i] = subEq[i] >= 0 ? config.uniDrcConfigExt.eqInstructions[subEq[i]].eqSetId : 0;
        }
        output.selectedLoudEqId = loudEqInstructionsIndexSelected >= 0
                ? config.uniDrcConfigExt.loudEqInstructions[loudEqInstructionsIndexSelected].loudEqSetId : 0;
    }

    private void mapTargetConfigRequestToDownmixId() {
        var targetChannelCountPrelim = params.baseChannelCount;
        params.numDownmixIdRequests = 0;
        switch (params.targetConfigRequestType) {
            case 0 -> {
                params.downmixIdRequested[0] = 0;
                params.numDownmixIdRequests = 1;
            }
            case 1, 2 -> {
                var byLayout = params.targetConfigRequestType == 1;
                if (byLayout ? params.targetLayoutRequested == params.baseLayout
                        : params.targetChannelCountRequested == params.baseChannelCount) {
                    params.downmixIdRequested[0] = 0;
                    params.numDownmixIdRequests = 1;
                }
                if (params.numDownmixIdRequests == 0) {
                    for (var i = 0; i < config.downmixInstructionsCount; i++) {
                        var downmix = config.downmixInstructions[i];
                        if (byLayout ? params.targetLayoutRequested == downmix.targetLayout
                                : params.targetChannelCountRequested == downmix.targetChannelCount) {
                            if (params.numDownmixIdRequests < MAX_NUM_DOWNMIX_ID_REQUESTS) {
                                params.downmixIdRequested[params.numDownmixIdRequests++] = downmix.downmixId;
                            }
                            targetChannelCountPrelim = downmix.targetChannelCount;
                        }
                    }
                }
                if (params.numDownmixIdRequests == 0) {
                    params.downmixIdRequested[0] = 0;
                    params.numDownmixIdRequests = 1;
                }
            }
            default -> throw new MediaException.Malformed("MPEG-D DRC target configuration request is invalid");
        }
        params.targetChannelCountPrelim = targetChannelCountPrelim;
    }

    private void selectDownmixMatrix() {
        output.baseChannelCount = config.channelLayout.baseChannelCount;
        output.targetChannelCount = config.channelLayout.baseChannelCount;
        output.targetLayout = -1;
        output.downmixMatrixPresent = 0;
        if (output.activeDownmixId != 0) {
            for (var n = 0; n < config.downmixInstructionsCount; n++) {
                var downmix = config.downmixInstructions[n];
                if (output.activeDownmixId == downmix.downmixId) {
                    output.targetChannelCount = downmix.targetChannelCount;
                    output.targetLayout = downmix.targetLayout;
                    if (downmix.downmixCoefficientsPresent != 0) {
                        for (var i = 0; i < output.baseChannelCount; i++) {
                            for (var j = 0; j < output.targetChannelCount; j++) {
                                output.downmixMatrix[i][j] = downmix.downmixCoefficient[i + j * output.baseChannelCount];
                            }
                        }
                        output.downmixMatrixPresent = 1;
                    }
                    break;
                }
            }
        }
    }

    private int permitted(int[] table, int id) {
        return id >= 0 && id < table.length ? table[id] : 0;
    }

    private static void validateDrcFeatureRequest(Params params) {
        for (var i = 0; i < params.numDrcFeatureRequests; i++) {
            switch (params.drcFeatureRequestType[i]) {
                case MATCH_EFFECT_TYPE -> {
                    for (var j = 0; j < params.numDrcEffectTypeRequestsDesired[i]; j++) {
                        if (params.drcEffectTypeRequest[i][j] == EFFECT_TYPE_REQUESTED_NONE
                                && params.numDrcEffectTypeRequestsDesired[i] > 1) {
                            throw new MediaException.Malformed("MPEG-D DRC request combines no effect with others");
                        }
                    }
                }
                case MATCH_DYNAMIC_RANGE, MATCH_DRC_CHARACTERISTIC -> {
                }
                default -> throw new MediaException.Malformed("MPEG-D DRC feature request is invalid");
            }
        }
    }

    private DrcInstructionsUniDrc findDrcInstructionsUniDrcForId(int drcSetId) {
        for (var i = 0; i < config.drcInstructionsUniDrcCount; i++) {
            if (config.drcInstructionsUniDrc[i].drcSetId == drcSetId) {
                return config.drcInstructionsUniDrc[i];
            }
        }
        throw new MediaException.Malformed("MPEG-D DRC set " + drcSetId + " is not defined");
    }

    private EqInstructions findEqInstructionsForId(int eqSetId) {
        for (var i = 0; i < config.uniDrcConfigExt.eqInstructionsCount; i++) {
            if (config.uniDrcConfigExt.eqInstructions[i].eqSetId == eqSetId) {
                return config.uniDrcConfigExt.eqInstructions[i];
            }
        }
        throw new MediaException.Malformed("MPEG-D DRC equalizer set " + eqSetId + " is not defined");
    }

    private DownmixInstructions findDownmixForId(int downmixId) {
        for (var i = 0; i < config.downmixInstructionsCount; i++) {
            if (config.downmixInstructions[i].downmixId == downmixId) {
                return config.downmixInstructions[i];
            }
        }
        throw new MediaException.Malformed("MPEG-D DRC downmix " + downmixId + " is not defined");
    }

    /**
     * {@return the index of the loudness equalizer instructions of an identifier, or -1 for 0}
     * ({@code findLoudEqInstructionsIndexForId})
     */
    static int findLoudEqInstructionsIndexForId(UniDrcConfig config, int loudEqSetId) {
        if (loudEqSetId <= 0) {
            return -1;
        }
        for (var i = 0; i < config.uniDrcConfigExt.loudEqInstructionsCount; i++) {
            if (config.uniDrcConfigExt.loudEqInstructions[i].loudEqSetId == loudEqSetId) {
                return i;
            }
        }
        throw new MediaException.Malformed("MPEG-D DRC loudness equalizer " + loudEqSetId + " is not defined");
    }

    private static int mapRequestedEffectToBitIndex(int effectTypeRequested) {
        return switch (effectTypeRequested) {
            case EFFECT_TYPE_REQUESTED_NONE -> EFFECT_BIT_NONE;
            case EFFECT_TYPE_REQUESTED_NIGHT -> EFFECT_BIT_NIGHT;
            case EFFECT_TYPE_REQUESTED_NOISY -> EFFECT_BIT_NOISY;
            case EFFECT_TYPE_REQUESTED_LIMITED -> EFFECT_BIT_LIMITED;
            case EFFECT_TYPE_REQUESTED_LOWLEVEL -> EFFECT_BIT_LOWLEVEL;
            case EFFECT_TYPE_REQUESTED_DIALOG -> EFFECT_BIT_DIALOG;
            case EFFECT_TYPE_REQUESTED_GENERAL_COMPR -> EFFECT_BIT_GENERAL_COMPR;
            case EFFECT_TYPE_REQUESTED_EXPAND -> EFFECT_BIT_EXPAND;
            case EFFECT_TYPE_REQUESTED_ARTISTIC -> EFFECT_BIT_ARTISTIC;
            default -> throw new MediaException.Malformed("MPEG-D DRC effect request " + effectTypeRequested
                    + " is invalid");
        };
    }

    private void getFadingDrcSet() {
        subDrc[2] = -1;
        if (params.albumMode == 0) {
            for (var n = 0; n < config.drcInstructionsUniDrcCount; n++) {
                var instructions = config.drcInstructionsUniDrc[n];
                if ((instructions.drcSetEffect & EFFECT_BIT_FADE) != 0) {
                    if (instructions.downmixId[0] != ID_FOR_ANY_DOWNMIX) {
                        throw new MediaException.Malformed("MPEG-D DRC fading set must apply to any downmix");
                    }
                    subDrc[2] = n;
                }
            }
        }
    }

    private void getDuckingDrcSet() {
        var downmixIdRequested = output.activeDownmixId;
        subDrc[3] = -1;
        var index = -1;
        for (var pass = 0; pass < 2 && index == -1; pass++) {
            var target = pass == 0 ? downmixIdRequested : ID_FOR_BASE_LAYOUT;
            for (var n = 0; n < config.drcInstructionsUniDrcCount; n++) {
                var instructions = config.drcInstructionsUniDrc[n];
                if ((instructions.drcSetEffect & (EFFECT_BIT_DUCK_OTHER | EFFECT_BIT_DUCK_SELF)) != 0) {
                    for (var k = 0; k < instructions.downmixIdCount; k++) {
                        if (instructions.downmixId[k] == target) {
                            index = n;
                        }
                    }
                }
            }
        }
        if (index > -1) {
            subDrc[2] = -1;
            subDrc[3] = index;
        }
    }

    private void getSelectedDrcSet(int drcSetIdSelected) {
        var n = 0;
        for (; n < config.drcInstructionsCountPlus; n++) {
            if (config.drcInstructionsUniDrc[n].drcSetId == drcSetIdSelected) {
                break;
            }
        }
        if (n == config.drcInstructionsCountPlus) {
            throw new MediaException.Malformed("MPEG-D DRC selected set " + drcSetIdSelected + " is not defined");
        }
        drcInstructionsIndexSelected = n;
        subDrc[0] = n;
    }

    private void getDependentDrcSet() {
        var instructions = config.drcInstructionsUniDrc[drcInstructionsIndexSelected];
        if (instructions.dependsOnDrcSetPresent == 1) {
            var n = 0;
            for (; n < config.drcInstructionsCountPlus; n++) {
                if (config.drcInstructionsUniDrc[n].drcSetId == instructions.dependsOnDrcSet) {
                    break;
                }
            }
            if (n == config.drcInstructionsCountPlus) {
                throw new MediaException.Malformed("MPEG-D DRC set depends on an undefined set");
            }
            subDrc[1] = n;
        } else {
            subDrc[1] = -1;
        }
    }

    private DrcInstructionsUniDrc getDrcInstructionsDependent(DrcInstructionsUniDrc instructions) {
        for (var j = 0; j < config.drcInstructionsUniDrcCount; j++) {
            var dependent = config.drcInstructionsUniDrc[j];
            if (dependent.drcSetId == instructions.dependsOnDrcSet) {
                if (dependent.dependsOnDrcSetPresent == 1) {
                    throw new MediaException.Malformed("MPEG-D DRC dependent set depends on another set");
                }
                return dependent;
            }
        }
        throw new MediaException.Malformed("MPEG-D DRC set depends on an undefined set");
    }

    private void getSelectedEqSet(int eqSetIdSelected) {
        eqInstructionsIndexSelected = -1;
        if (eqSetIdSelected > 0) {
            var ext = config.uniDrcConfigExt;
            var n = 0;
            for (; n < ext.eqInstructionsCount; n++) {
                if (ext.eqInstructions[n].eqSetId == eqSetIdSelected) {
                    break;
                }
            }
            if (n == ext.eqInstructionsCount) {
                throw new MediaException.Malformed("MPEG-D DRC selected equalizer is not defined");
            }
            eqInstructionsIndexSelected = n;
            subEq[ext.eqInstructions[n].eqApplyToDownmix == 1 ? 1 : 0] = n;
        }
    }

    private void getDependentEqSet() {
        if (eqInstructionsIndexSelected >= 0) {
            var ext = config.uniDrcConfigExt;
            var eq = ext.eqInstructions[eqInstructionsIndexSelected];
            if (eq.dependsOnEqSetPresent == 1) {
                var n = 0;
                for (; n < ext.eqInstructionsCount; n++) {
                    if (ext.eqInstructions[n].eqSetId == eq.dependsOnEqSet) {
                        break;
                    }
                }
                if (n == ext.eqInstructionsCount) {
                    throw new MediaException.Malformed("MPEG-D DRC equalizer depends on an undefined set");
                }
                subEq[ext.eqInstructions[n].eqApplyToDownmix == 1 ? 1 : 0] = n;
            }
        }
    }

    private void getSelectedLoudEqSet(int loudEqSetIdSelected) {
        loudEqInstructionsIndexSelected = -1;
        if (loudEqSetIdSelected > 0) {
            var ext = config.uniDrcConfigExt;
            var n = 0;
            for (; n < ext.loudEqInstructionsCount; n++) {
                if (ext.loudEqInstructions[n].loudEqSetId == loudEqSetIdSelected) {
                    break;
                }
            }
            if (n == ext.loudEqInstructionsCount) {
                throw new MediaException.Malformed("MPEG-D DRC selected loudness equalizer is not defined");
            }
            loudEqInstructionsIndexSelected = n;
        }
    }

    private int selectLoudEq(int downmixIdRequested, int drcSetIdRequested, int eqSetIdRequested) {
        var selectedId = 0;
        if (params.loudnessEqRequest != LOUD_EQ_REQUEST_OFF) {
            var ext = config.uniDrcConfigExt;
            for (var i = 0; i < ext.loudEqInstructionsCount; i++) {
                var loudEq = ext.loudEqInstructions[i];
                if (loudEq.drcLocation != LOCATION_SELECTED) {
                    continue;
                }
                for (var d = 0; d < loudEq.downmixIdCount; d++) {
                    if (loudEq.downmixId[d] != downmixIdRequested && loudEq.downmixId[d] != ID_FOR_ANY_DOWNMIX) {
                        continue;
                    }
                    for (var c = 0; c < loudEq.drcSetIdCount; c++) {
                        if (loudEq.drcSetId[c] != drcSetIdRequested && loudEq.drcSetId[c] != ID_FOR_ANY_DRC) {
                            continue;
                        }
                        for (var e = 0; e < loudEq.eqSetIdCount; e++) {
                            if (loudEq.eqSetId[e] == eqSetIdRequested || loudEq.eqSetId[e] == ID_FOR_ANY_EQ) {
                                selectedId = loudEq.loudEqSetId;
                            }
                        }
                    }
                }
            }
        }
        return selectedId;
    }

    private int matchEqSet(int downmixId, int drcSetId, int[] matchingIndex) {
        var count = 0;
        var ext = config.uniDrcConfigExt;
        for (var i = 0; i < ext.eqInstructionsCount; i++) {
            var eq = ext.eqInstructions[i];
            if (eq.dependsOnEqSetPresent == 0 && eq.noIndependentEqUse == 1) {
                continue;
            }
            if (permitted(eqSetIdIsPermitted, eq.eqSetId) == 0) {
                continue;
            }
            for (var k = 0; k < eq.downmixIdCount; k++) {
                if (eq.downmixId[k] == ID_FOR_ANY_DOWNMIX || downmixId == eq.downmixId[k]) {
                    for (var n = 0; n < eq.drcSetIdCount; n++) {
                        if (eq.drcSetId[n] == ID_FOR_ANY_DRC || drcSetId == eq.drcSetId[n]) {
                            if (count < matchingIndex.length) {
                                matchingIndex[count++] = i;
                            }
                        }
                    }
                }
            }
        }
        return count;
    }

    private boolean matchEqSetPurpose(int eqSetIdRequested, int eqSetPurposeRequested) {
        var found = false;
        var ext = config.uniDrcConfigExt;
        for (var i = 0; i < ext.eqInstructionsCount; i++) {
            var eq = ext.eqInstructions[i];
            if (permitted(eqSetIdIsPermitted, eq.eqSetId) == 0) {
                continue;
            }
            if (eq.eqSetId == eqSetIdRequested && (eq.eqSetPurpose & eqSetPurposeRequested) != 0) {
                found = true;
            }
        }
        return found;
    }

    private void findEqSetsForNoCompression(int downmixIdRequested) {
        var k = 0;
        var ext = config.uniDrcConfigExt;
        for (var i = 0; i < ext.eqInstructionsCount; i++) {
            var eq = ext.eqInstructions[i];
            for (var d = 0; d < eq.downmixIdCount; d++) {
                if (downmixIdRequested == eq.downmixId[d]) {
                    for (var c = 0; c < eq.drcSetIdCount; c++) {
                        if ((eq.drcSetId[c] == ID_FOR_ANY_DRC || eq.drcSetId[c] == 0) && k < PEAK_TABLE_SIZE - 1) {
                            noCompressionEqId[k++] = eq.eqSetId;
                        }
                    }
                }
            }
        }
        noCompressionEqCount = k;
    }

    private int selectDrcsWithoutCompressionEffects(int count) {
        var k = 0;
        for (var i = 0; i < count; i++) {
            var instructions = config.drcInstructionsUniDrc[candidates[i].drcInstructionsIndex];
            var match = true;
            for (var effect : EFFECT_TYPES_REQUESTABLE) {
                if ((instructions.drcSetEffect & effect) != 0) {
                    match = false;
                }
            }
            if (match) {
                candidatesStep2[k++].copyFrom(candidates[i]);
            }
        }
        if (k > 0) {
            for (var i = 0; i < k; i++) {
                candidates[i].copyFrom(candidatesStep2[i]);
            }
            return k;
        }
        return -1;
    }

    private int matchEffectTypeAttempt(int effectTypeRequested, int count) {
        var effectBitIndex = mapRequestedEffectToBitIndex(effectTypeRequested);
        if (effectBitIndex == EFFECT_BIT_NONE) {
            return selectDrcsWithoutCompressionEffects(count);
        }
        var k = 0;
        for (var i = 0; i < count; i++) {
            var instructions = config.drcInstructionsUniDrc[candidates[i].drcInstructionsIndex];
            var hit = (instructions.drcSetEffect & effectBitIndex) != 0;
            if (instructions.dependsOnDrcSetPresent == 1) {
                hit |= (getDrcInstructionsDependent(instructions).drcSetEffect & effectBitIndex) != 0;
            }
            if (hit) {
                candidatesStep2[k++].copyFrom(candidates[i]);
            }
        }
        if (k > 0) {
            for (var i = 0; i < k; i++) {
                candidates[i].copyFrom(candidatesStep2[i]);
            }
            return k;
        }
        return -1;
    }

    private int matchEffectTypes(int totalCount, int desiredCount, int[] effectTypeRequested, int count) {
        var matchFound = false;
        var desiredFound = false;
        var k = 0;
        while (k < desiredCount) {
            var result = matchEffectTypeAttempt(effectTypeRequested[k], count);
            matchFound = result >= 0;
            if (matchFound) {
                count = result;
                desiredFound = true;
            }
            k++;
        }
        if (!desiredFound) {
            while (k < totalCount && !matchFound) {
                var result = matchEffectTypeAttempt(effectTypeRequested[k], count);
                matchFound = result >= 0;
                if (matchFound) {
                    count = result;
                }
                k++;
            }
        }
        return count;
    }

    private int matchDynamicRange(int measurementType, int requestedIsRange, float requested, float minRequested,
                                  float maxRequested, int count) {
        var deviationMin = 1000.0f;
        var k = 0;
        for (var i = 0; i < count; i++) {
            var instructions = config.drcInstructionsUniDrc[candidates[i].drcInstructionsIndex];
            var value = getLoudnessPeakToAverageValue(instructions,
                    params.downmixIdRequested[candidates[i].downmixIdRequestIndex], measurementType);
            if (!Float.isNaN(value)) {
                if (requestedIsRange == 1) {
                    if (value >= minRequested && value <= maxRequested) {
                        selected[k++] = i;
                    }
                } else {
                    var deviation = Math.abs(requested - value);
                    if (deviationMin >= deviation) {
                        if (deviationMin > deviation) {
                            deviationMin = deviation;
                            k = 0;
                        }
                        selected[k++] = i;
                    }
                }
            }
        }
        if (k > 0) {
            for (var i = 0; i < k; i++) {
                candidates[i].copyFrom(candidates[selected[i]]);
            }
            return k;
        }
        return count;
    }

    private int matchDrcCharacteristicAttempt(int drcCharacteristicRequest, int count) {
        if (drcCharacteristicRequest < 1) {
            throw new MediaException.Malformed("MPEG-D DRC characteristic request is invalid");
        }
        int request1;
        int request2;
        int request3;
        if (drcCharacteristicRequest < 12) {
            request1 = DRC_CHARACTERISTIC_ORDER_DEFAULT[drcCharacteristicRequest - 1][0];
            request2 = DRC_CHARACTERISTIC_ORDER_DEFAULT[drcCharacteristicRequest - 1][1];
            request3 = DRC_CHARACTERISTIC_ORDER_DEFAULT[drcCharacteristicRequest - 1][2];
        } else {
            request1 = drcCharacteristicRequest;
            request2 = -1;
            request3 = -1;
        }
        DrcCoefficientsUniDrc coefficients = null;
        if (config.drcCoefficientsUniDrcCount != 0) {
            for (var i = 0; i < config.drcCoefficientsUniDrcCount; i++) {
                if (config.drcCoefficientsUniDrc[i].drcLocation == LOCATION_SELECTED) {
                    coefficients = config.drcCoefficientsUniDrc[i];
                    break;
                }
            }
            if (coefficients == null) {
                throw new MediaException.Malformed("MPEG-D DRC has no coefficients at the selected location");
            }
        }
        if (coefficients == null) {
            return -1;
        }
        var n = 0;
        for (var i = 0; i < count; i++) {
            var refCount = 0;
            var matchCount = 0.0f;
            var instructions = config.drcInstructionsUniDrc[candidates[i].drcInstructionsIndex];
            for (var pass = 0; pass < 2; pass++) {
                if (pass == 1) {
                    if (instructions.dependsOnDrcSetPresent != 1) {
                        break;
                    }
                    instructions = findDrcInstructionsUniDrcForId(instructions.dependsOnDrcSet);
                    if ((instructions.drcSetEffect & (EFFECT_BIT_FADE | EFFECT_BIT_DUCK_OTHER | EFFECT_BIT_DUCK_SELF)) != 0
                            || instructions.drcSetEffect == EFFECT_BIT_CLIPPING) {
                        break;
                    }
                }
                for (var k = 0; k < instructions.nDrcChannelGroups; k++) {
                    var set = coefficients.gainSetParams[instructions.gainSetIndexForChannelGroup[k]];
                    for (var b = 0; b < set.bandCount; b++) {
                        refCount++;
                        var characteristic = set.gainParams[b].drcCharacteristic;
                        if (characteristic == request1) {
                            matchCount += 1.0f;
                        } else if (characteristic == request2) {
                            matchCount += 0.75f;
                        } else if (characteristic == request3) {
                            matchCount += 0.5f;
                        }
                    }
                }
            }
            if (refCount > 0 && matchCount > 0.5f * refCount) {
                candidates[n++].copyFrom(candidates[i]);
            }
        }
        return n > 0 ? n : -1;
    }

    private int matchDrcCharacteristic(int drcCharacteristicRequested, int count) {
        if (drcCharacteristicRequested < 1 || drcCharacteristicRequested > DRC_CHARACTERISTIC_ORDER_DEFAULT.length) {
            throw new MediaException.Malformed("MPEG-D DRC characteristic request is invalid");
        }
        var order = DRC_CHARACTERISTIC_ORDER_DEFAULT[drcCharacteristicRequested - 1];
        var matchFound = false;
        var k = 0;
        while (k < order.length && !matchFound && order[k] > 0) {
            var result = matchDrcCharacteristicAttempt(order[k], count);
            if (result >= 0) {
                count = result;
                matchFound = true;
            }
            k++;
        }
        return count;
    }

    private float complexitySupportedTotal() {
        return (float) StrictMath.pow(2.0f, COMPLEXITY_LEVEL_SUPPORTED_TOTAL);
    }

    private void manageDrcComplexity() {
        var coefficients = DrcParser.selectDrcCoefficients(config, LOCATION_SELECTED);
        var supported = complexitySupportedTotal();
        for (var i = 0; i < config.drcInstructionsUniDrcCount; i++) {
            var instructions = config.drcInstructionsUniDrc[i];
            if (instructions.noIndependentUse != 0) {
                continue;
            }
            var numBandsTooLarge = false;
            var channelCount = drcChannelCount(instructions.drcApplyToDownmix);
            if (subbandDomainMode == SUBBAND_DOMAIN_MODE_OFF && coefficients != null) {
                for (var j = 0; j < instructions.nDrcChannelGroups; j++) {
                    var set = coefficients.gainSetParams[instructions.gainSetIndexForChannelGroup[j]];
                    if (set.bandCount > params.numBandsSupported) {
                        numBandsTooLarge = true;
                    }
                }
            }
            float complexity = channelCount * (1 << instructions.drcSetComplexityLevel);
            if (instructions.dependsOnDrcSet > 0) {
                var dependent = findDrcInstructionsUniDrcForId(instructions.dependsOnDrcSet);
                if (output.targetChannelCount == 0) {
                    channelCount = instructions.drcApplyToDownmix == 1 ? params.targetChannelCountPrelim
                            : params.baseChannelCount;
                } else {
                    channelCount = dependent.drcApplyToDownmix == 1 ? output.targetChannelCount
                            : output.baseChannelCount;
                }
                if (subbandDomainMode == SUBBAND_DOMAIN_MODE_OFF && coefficients != null) {
                    for (var j = 0; j < instructions.nDrcChannelGroups; j++) {
                        var set = coefficients.gainSetParams[dependent.gainSetIndexForChannelGroup[j]];
                        if (set.bandCount > params.numBandsSupported) {
                            numBandsTooLarge = true;
                        }
                    }
                }
                complexity += channelCount * (1 << dependent.drcSetComplexityLevel);
            }
            complexity = (float) (complexity * (config.sampleRate / 48000.0));
            if (complexity <= supported && !numBandsTooLarge && instructions.drcSetId >= 0
                    && instructions.drcSetId < ID_TABLE_SIZE) {
                drcSetIdIsPermitted[instructions.drcSetId] = 1;
            }
        }
    }

    private int drcChannelCount(int drcApplyToDownmix) {
        if (output.targetChannelCount == 0) {
            return drcApplyToDownmix == 1 ? params.targetChannelCountPrelim : params.baseChannelCount;
        }
        return drcApplyToDownmix == 1 ? output.targetChannelCount : output.baseChannelCount;
    }

    private int eqChannelCount(EqInstructions eq, EqInstructions downmixSource) {
        var count = params.baseChannelCount;
        if (eq.eqApplyToDownmix == 1) {
            if (eq.downmixId[0] == ID_FOR_ANY_DOWNMIX) {
                count = params.targetChannelCountPrelim;
            } else {
                for (var k = 0; k < config.downmixInstructionsCount; k++) {
                    for (var m = 0; m < downmixSource.downmixIdCount; m++) {
                        if (config.downmixInstructions[k].downmixId == eq.downmixId[m]
                                && count > config.downmixInstructions[k].targetChannelCount) {
                            count = config.downmixInstructions[k].targetChannelCount;
                        }
                    }
                }
            }
        }
        return count;
    }

    private void manageEqComplexity() {
        var supported = complexitySupportedTotal();
        var ext = config.uniDrcConfigExt;
        for (var n = 0; n < ext.eqInstructionsCount; n++) {
            var eq = ext.eqInstructions[n];
            var complexityPrimary = eqComplexity(eq.eqSetComplexityLevel, eq);
            float total = eqChannelCount(eq, eq) * complexityPrimary;
            if (eq.dependsOnEqSetPresent > 0) {
                var dependent = findEqInstructionsForId(eq.dependsOnEqSet);
                var complexityDependent = eqComplexity(dependent.eqSetComplexityLevel, eq);
                total += eqChannelCount(dependent, eq) * complexityDependent;
            }
            if (eq.eqSetId >= 0 && eq.eqSetId < ID_TABLE_SIZE) {
                eqSetIdIsPermitted[eq.eqSetId] = 0;
            }
            total = (float) (total * (config.sampleRate / 48000.0));
            if (total <= supported && eq.eqSetId >= 0 && eq.eqSetId < ID_TABLE_SIZE) {
                eqSetIdIsPermitted[eq.eqSetId] = 1;
            }
        }
    }

    private int eqComplexity(int level, EqInstructions cascadeSource) {
        var complexity = 1 << level;
        if (subbandDomainMode == SUBBAND_DOMAIN_MODE_OFF) {
            if (cascadeSource.tdFilterCascadePresent == 0) {
                complexity = 0;
            }
        } else if (cascadeSource.tdFilterCascadePresent == 1) {
            complexity = 2;
        }
        return complexity;
    }

    private boolean manageComplexity() {
        var coefficients = DrcParser.selectDrcCoefficients(config, LOCATION_SELECTED);
        var supported = complexitySupportedTotal();
        var freqNorm = config.sampleRate / 48000.0f;
        var numBandsTooLarge = false;
        var complexityDrcTotal = 0.0f;
        var complexityEqTotal = 0.0f;
        DrcInstructionsUniDrc instructions = null;
        for (var p = 0; p < 4; p++) {
            if (output.selectedDrcSetIds[p] <= 0) {
                continue;
            }
            instructions = findDrcInstructionsUniDrcForId(output.selectedDrcSetIds[p]);
            var channelCount = instructions.drcApplyToDownmix == 1 ? output.targetChannelCount : output.baseChannelCount;
            if (subbandDomainMode == SUBBAND_DOMAIN_MODE_OFF && coefficients != null) {
                for (var j = 0; j < instructions.nDrcChannelGroups; j++) {
                    var set = coefficients.gainSetParams[instructions.gainSetIndexForChannelGroup[j]];
                    if (set.bandCount > params.numBandsSupported) {
                        if (p < 2) {
                            numBandsTooLarge = true;
                        } else {
                            setPermitted(instructions.drcSetId, 0);
                            output.selectedDrcSetIds[p] = 0;
                        }
                    }
                }
            }
            complexityDrcTotal += channelCount * (1 << instructions.drcSetComplexityLevel);
        }
        if (output.activeDownmixId > 0) {
            var complexityPerCoeff = subbandDomainMode == SUBBAND_DOMAIN_MODE_OFF ? 1.0f : 2.0f;
            var downmix = findDownmixForId(output.activeDownmixId);
            if (downmix.downmixCoefficientsPresent == 1) {
                for (var i = 0; i < output.baseChannelCount; i++) {
                    for (var j = 0; j < output.targetChannelCount; j++) {
                        if (output.downmixMatrix[i][j] != 0.0f) {
                            complexityDrcTotal += complexityPerCoeff;
                        }
                    }
                }
            }
        }
        for (var p = 0; p < 2; p++) {
            if (subEq[p] >= 0) {
                var eq = config.uniDrcConfigExt.eqInstructions[subEq[p]];
                var channelCount = p == 0 ? output.baseChannelCount : output.targetChannelCount;
                float complexityEq = 1 << eq.eqSetComplexityLevel;
                if (subbandDomainMode == SUBBAND_DOMAIN_MODE_OFF) {
                    if (eq.tdFilterCascadePresent == 0) {
                        complexityEq = 0.0f;
                    }
                } else if (eq.tdFilterCascadePresent == 1) {
                    complexityEq = 2.5f;
                }
                complexityEqTotal += channelCount * complexityEq;
            }
        }
        complexityDrcTotal *= freqNorm;
        complexityEqTotal *= freqNorm;
        boolean repeat;
        if (numBandsTooLarge) {
            if (output.selectedDrcSetIds[0] > 0) {
                setPermitted(findDrcInstructionsUniDrcForId(output.selectedDrcSetIds[0]).drcSetId, 0);
            }
            repeat = true;
        } else if (complexityDrcTotal + complexityEqTotal <= supported) {
            repeat = false;
        } else {
            var drcRequiresEq = false;
            for (var p = 0; p < 2; p++) {
                if (output.selectedDrcSetIds[p] > 0) {
                    instructions = findDrcInstructionsUniDrcForId(output.selectedDrcSetIds[p]);
                    if (instructions.requiresEq == 1) {
                        drcRequiresEq = true;
                    }
                }
            }
            if (!drcRequiresEq && complexityDrcTotal <= supported) {
                subEq[0] = 0;
                subEq[1] = 0;
                repeat = false;
            } else {
                if (output.selectedDrcSetIds[0] > 0) {
                    setPermitted(findDrcInstructionsUniDrcForId(output.selectedDrcSetIds[0]).drcSetId, 0);
                } else {
                    for (var p = 2; p < 4; p++) {
                        if (instructions != null) {
                            setPermitted(instructions.drcSetId, 0);
                        }
                        output.selectedDrcSetIds[p] = 0;
                    }
                }
                repeat = true;
            }
        }
        if (repeat) {
            output.clear();
        }
        return repeat;
    }

    private void setPermitted(int drcSetId, int value) {
        if (drcSetId >= 0 && drcSetId < ID_TABLE_SIZE) {
            drcSetIdIsPermitted[drcSetId] = value;
        }
    }

    private Candidate candidate(int k) {
        if (k >= CANDIDATE_CAPACITY) {
            throw new MediaException.Malformed("MPEG-D DRC has too many selection candidates");
        }
        return candidates[k];
    }

    private int drcSetPreselection(boolean restrictToDrcWithAlbumLoudness) {
        var outputPeakLevelMax = params.outputPeakLevelMax;
        var loudnessDeviationMax = params.loudnessDeviationMax;
        var outputPeakLevelMin = 1000.0f;
        Arrays.fill(explicitPeakInformationPresent, 0);
        Arrays.fill(signalPeakLevel, 0);
        Arrays.fill(eqSetIdForPeak, 0);
        var k = 0;
        for (var d = 0; d < params.numDownmixIdRequests; d++) {
            var downmixIdRequested = params.downmixIdRequested[d];
            findEqSetsForNoCompression(downmixIdRequested);
            for (var i = 0; i < config.drcInstructionsCountPlus; i++) {
                var instructions = config.drcInstructionsUniDrc[i];
                var downmixIdMatch = false;
                for (var j = 0; j < instructions.downmixIdCount; j++) {
                    if (instructions.downmixId[j] == downmixIdRequested
                            || (instructions.downmixId[j] == ID_FOR_BASE_LAYOUT && instructions.drcSetId > 0)
                            || instructions.downmixId[j] == ID_FOR_ANY_DOWNMIX) {
                        downmixIdMatch = true;
                    }
                }
                if (!downmixIdMatch) {
                    continue;
                }
                if (params.dynamicRangeControlOn == 1) {
                    var effect = instructions.drcSetEffect;
                    if (effect == EFFECT_BIT_FADE || effect == EFFECT_BIT_DUCK_OTHER || effect == EFFECT_BIT_DUCK_SELF
                            || (effect == 0 && instructions.drcSetId >= 0)
                            || (instructions.dependsOnDrcSetPresent == 0 && instructions.noIndependentUse != 0)) {
                        continue;
                    }
                    var drcIsPermitted = instructions.drcSetId > 0
                            ? permitted(drcSetIdIsPermitted, instructions.drcSetId) : 1;
                    if (drcIsPermitted != 1) {
                        continue;
                    }
                    initLoudnessControl(downmixIdRequested, instructions.drcSetId);
                    getSignalPeakLevel(instructions, downmixIdRequested);
                    for (var l = 0; l < loudnessInfoCount; l++) {
                        var candidate = candidate(k);
                        candidate.loudnessNormalizationGainDbAdjusted = Math.min(loudnessNormalizationGainDb[l],
                                params.loudnessNormalizationGainDbMax);
                        candidate.outputLoudness = loudness[l] != UNDEFINED_LOUDNESS_VALUE
                                ? loudness[l] + candidate.loudnessNormalizationGainDbAdjusted : UNDEFINED_LOUDNESS_VALUE;
                        var p = 0;
                        var matchFound = false;
                        for (; p < peakInfoCount; p++) {
                            if (eqSetIdForPeak[p] == eqSetIdForLoudness[l]
                                    && permitted(eqSetIdIsPermitted, eqSetIdForPeak[p]) == 1) {
                                matchFound = true;
                                break;
                            }
                        }
                        candidate.outputPeakLevel = matchFound
                                ? signalPeakLevel[p] + candidate.loudnessNormalizationGainDbAdjusted
                                : candidate.loudnessNormalizationGainDbAdjusted;
                        if (instructions.requiresEq == 1 && permitted(eqSetIdIsPermitted, eqSetIdForLoudness[l]) == 0) {
                            continue;
                        }
                        candidate.drcInstructionsIndex = i;
                        candidate.downmixIdRequestIndex = d;
                        candidate.eqSetId = eqSetIdForLoudness[l];
                        candidate.selectionFlags = explicitPeakInformationPresent[p] == 1
                                ? SELECTION_FLAG_EXPLICIT_PEAK_INFO_PRESENT : 0;
                        candidate.mixingLevel = getMixingLevel(downmixIdRequested, instructions.drcSetId,
                                eqSetIdForLoudness[l]);
                        if (instructions.drcSetTargetLoudnessPresent != 0
                                && ((params.loudnessNormalizationOn != 0
                                && instructions.drcSetTargetLoudnessValueUpper >= params.targetLoudness
                                && instructions.drcSetTargetLoudnessValueLower < params.targetLoudness)
                                || params.loudnessNormalizationOn == 0)) {
                            candidate.selectionFlags |= SELECTION_FLAG_DRC_TARGET_LOUDNESS_MATCH;
                            if (explicitPeakInformationPresent[p] == 0) {
                                candidate.outputPeakLevel = params.loudnessNormalizationOn != 0
                                        ? params.targetLoudness - instructions.drcSetTargetLoudnessValueUpper : 0.0f;
                            }
                        }
                        if ((candidate.selectionFlags & (SELECTION_FLAG_DRC_TARGET_LOUDNESS_MATCH
                                | SELECTION_FLAG_EXPLICIT_PEAK_INFO_PRESENT)) != 0
                                || instructions.drcSetTargetLoudnessPresent == 0) {
                            k++;
                        }
                    }
                } else if (instructions.drcSetId < 0) {
                    initLoudnessControl(downmixIdRequested, instructions.drcSetId);
                    getSignalPeakLevel(instructions, downmixIdRequested);
                    for (var l = 0; l < loudnessInfoCount; l++) {
                        var p = 0;
                        var matchFound = false;
                        for (; p < peakInfoCount; p++) {
                            if (eqSetIdForPeak[p] == eqSetIdForLoudness[l]
                                    && permitted(eqSetIdIsPermitted, eqSetIdForPeak[p]) == 1) {
                                matchFound = true;
                                break;
                            }
                        }
                        if (!matchFound) {
                            continue;
                        }
                        var candidate = candidate(k);
                        var adjustment = Math.max(0.0f, signalPeakLevel[p] + loudnessNormalizationGainDb[l]
                                - params.outputPeakLevelMax);
                        adjustment = Math.min(adjustment, Math.max(0.0f, loudnessDeviationMax));
                        candidate.loudnessNormalizationGainDbAdjusted = Math.min(loudnessNormalizationGainDb[l]
                                - adjustment, params.loudnessNormalizationGainDbMax);
                        candidate.outputPeakLevel = signalPeakLevel[p] + candidate.loudnessNormalizationGainDbAdjusted;
                        candidate.outputLoudness = loudness[l] != UNDEFINED_LOUDNESS_VALUE
                                ? loudness[l] + candidate.loudnessNormalizationGainDbAdjusted : UNDEFINED_LOUDNESS_VALUE;
                        candidate.drcInstructionsIndex = i;
                        candidate.downmixIdRequestIndex = d;
                        candidate.eqSetId = eqSetIdForLoudness[l];
                        candidate.selectionFlags = explicitPeakInformationPresent[p] == 1
                                ? SELECTION_FLAG_EXPLICIT_PEAK_INFO_PRESENT : 0;
                        candidate.mixingLevel = getMixingLevel(downmixIdRequested, instructions.drcSetId,
                                eqSetIdForLoudness[l]);
                        k++;
                    }
                }
            }
        }
        var count = k;
        if (count > SELECTION_CANDIDATE_COUNT_MAX) {
            throw new MediaException.Malformed("MPEG-D DRC has too many selection candidates");
        }
        if (count > 0 && params.dynamicRangeControlOn == 1) {
            var n = 0;
            var matching = new int[64];
            for (k = 0; k < count; k++) {
                var instructions = config.drcInstructionsUniDrc[candidates[k].drcInstructionsIndex];
                if (params.eqSetPurposeRequest != EQ_PURPOSE_EQ_OFF) {
                    var matchingCount = matchEqSet(params.downmixIdRequested[candidates[k].downmixIdRequestIndex],
                            instructions.drcSetId, matching);
                    for (var j = 0; j < matchingCount; j++) {
                        step2(n).copyFrom(candidates[k]);
                        candidatesStep2[n++].eqSetId = config.uniDrcConfigExt.eqInstructions[matching[j]].eqSetId;
                    }
                }
                if (instructions.requiresEq == 0) {
                    step2(n).copyFrom(candidates[k]);
                    candidatesStep2[n++].eqSetId = 0;
                }
            }
            for (k = 0; k < n; k++) {
                candidates[k].copyFrom(candidatesStep2[k]);
            }
            count = n;
            n = 0;
            for (k = 0; k < count; k++) {
                var flags = candidates[k].selectionFlags;
                if ((flags & SELECTION_FLAG_DRC_TARGET_LOUDNESS_MATCH) != 0
                        && (flags & SELECTION_FLAG_EXPLICIT_PEAK_INFO_PRESENT) == 0) {
                    candidatesStep2[n++].copyFrom(candidates[k]);
                } else {
                    if (candidates[k].outputPeakLevel <= outputPeakLevelMax) {
                        candidatesStep2[n++].copyFrom(candidates[k]);
                    }
                    if (candidates[k].outputPeakLevel < outputPeakLevelMin) {
                        outputPeakLevelMin = candidates[k].outputPeakLevel;
                    }
                }
            }
            var step2Count = n;
            if (step2Count == 0) {
                n = 0;
                for (k = 0; k < count; k++) {
                    var flags = candidates[k].selectionFlags;
                    if ((flags & SELECTION_FLAG_DRC_TARGET_LOUDNESS_MATCH) != 0
                            && (flags & SELECTION_FLAG_EXPLICIT_PEAK_INFO_PRESENT) != 0) {
                        candidatesStep2[n++].copyFrom(candidates[k]);
                    }
                }
                step2Count = n;
            }
            if (step2Count == 0) {
                n = 0;
                for (k = 0; k < count; k++) {
                    if (candidatesStep2[k].outputPeakLevel < outputPeakLevelMin + 1.0f) {
                        var target = candidatesStep2[n];
                        target.copyFrom(candidates[k]);
                        var adjustment = Math.max(0.0f, target.outputPeakLevel - outputPeakLevelMax);
                        adjustment = Math.min(adjustment, Math.max(0.0f, loudnessDeviationMax));
                        target.loudnessNormalizationGainDbAdjusted -= adjustment;
                        target.outputPeakLevel -= adjustment;
                        target.outputLoudness -= adjustment;
                        n++;
                    }
                }
                step2Count = n;
            }
            for (n = 0; n < step2Count; n++) {
                candidates[n].copyFrom(candidatesStep2[n]);
            }
            count = step2Count;
        }
        if (restrictToDrcWithAlbumLoudness) {
            var j = 0;
            for (k = 0; k < count; k++) {
                var requested = Math.max(0, config.drcInstructionsUniDrc[candidates[k].drcInstructionsIndex].drcSetId);
                for (var n = 0; n < loudnessInfoSet.loudnessInfoAlbumCount; n++) {
                    if (requested == loudnessInfoSet.loudnessInfoAlbum[n].drcSetId) {
                        candidates[j++].copyFrom(candidates[k]);
                        break;
                    }
                }
            }
            count = j;
        }
        return count;
    }

    private Candidate step2(int n) {
        if (n >= CANDIDATE_CAPACITY) {
            throw new MediaException.Malformed("MPEG-D DRC has too many selection candidates");
        }
        return candidatesStep2[n];
    }

    private int drcSetFinalSelection(int count) {
        if (count <= 0) {
            throw new MediaException.Malformed("MPEG-D DRC has no selection candidate");
        }
        if (params.eqSetPurposeRequest > 0) {
            var eqPurposeRequested = params.eqSetPurposeRequest;
            var k = 0;
            var loopCount = 0;
            while (k == 0 && loopCount < 2) {
                for (var i = 0; i < count; i++) {
                    if (matchEqSetPurpose(candidates[i].eqSetId, eqPurposeRequested)) {
                        candidatesStep2[k++].copyFrom(candidates[i]);
                    }
                }
                eqPurposeRequested = EQ_PURPOSE_DEFAULT;
                loopCount++;
            }
            if (k > 0) {
                for (var i = 0; i < k; i++) {
                    candidates[i].copyFrom(candidatesStep2[i]);
                }
                count = k;
            }
        }
        var outputLevelMin = 10000.0f;
        var k = 0;
        for (var i = 0; i < count; i++) {
            if (outputLevelMin >= candidates[i].outputPeakLevel) {
                if (outputLevelMin > candidates[i].outputPeakLevel) {
                    outputLevelMin = candidates[i].outputPeakLevel;
                    k = 0;
                }
                candidatesStep2[k++].copyFrom(candidates[i]);
            }
        }
        var step2Count = k;
        if (outputLevelMin <= 0.0f) {
            k = 0;
            for (var i = 0; i < count; i++) {
                if (candidates[i].outputPeakLevel <= 0.0f) {
                    candidatesStep2[k++].copyFrom(candidates[i]);
                }
            }
            step2Count = k;
            k = 0;
            for (var i = 0; i < step2Count; i++) {
                var instructions = config.drcInstructionsUniDrc[candidatesStep2[i].drcInstructionsIndex];
                for (var n = 0; n < instructions.downmixIdCount; n++) {
                    var id = instructions.downmixId[n];
                    if (id != ID_FOR_BASE_LAYOUT && id != ID_FOR_ANY_DOWNMIX
                            && params.downmixIdRequested[candidatesStep2[i].downmixIdRequestIndex] == id) {
                        candidatesStep2[k++].copyFrom(candidatesStep2[i]);
                    }
                }
            }
            if (k > 0) {
                step2Count = k;
            }
            var effectCountMin = 100;
            k = 0;
            for (var i = 0; i < step2Count; i++) {
                var instructions = config.drcInstructionsUniDrc[candidatesStep2[i].drcInstructionsIndex];
                var dependent = instructions.dependsOnDrcSetPresent == 1 ? getDrcInstructionsDependent(instructions)
                        : null;
                var effectCount = 0;
                for (var effect : EFFECT_TYPES_REQUESTABLE) {
                    if (effect != EFFECT_BIT_GENERAL_COMPR && ((instructions.drcSetEffect & effect) != 0
                            || (dependent != null && (dependent.drcSetEffect & effect) != 0))) {
                        effectCount++;
                    }
                }
                if (effectCountMin >= effectCount) {
                    if (effectCountMin > effectCount) {
                        effectCountMin = effectCount;
                        k = 0;
                    }
                    candidatesStep2[k++].copyFrom(candidatesStep2[i]);
                }
            }
            step2Count = k;
            k = 0;
            for (var i = 0; i < step2Count; i++) {
                if ((candidatesStep2[i].selectionFlags & SELECTION_FLAG_DRC_TARGET_LOUDNESS_MATCH) != 0) {
                    k++;
                }
            }
            if (k != 0 && k != step2Count) {
                k = 0;
                for (var i = 0; i < step2Count; i++) {
                    if ((candidatesStep2[i].selectionFlags & SELECTION_FLAG_DRC_TARGET_LOUDNESS_MATCH) == 0) {
                        candidatesStep2[k++].copyFrom(candidatesStep2[i]);
                    }
                }
                step2Count = k;
            } else if (k == step2Count) {
                step2Count = minimizeTargetLoudnessUpper(step2Count);
            }
            k = 0;
            for (var i = 0; i < step2Count; i++) {
                if (targetLoudnessMatches(config.drcInstructionsUniDrc[candidatesStep2[i].drcInstructionsIndex])) {
                    k++;
                }
            }
            if (k != 0 && k != step2Count) {
                k = 0;
                for (var i = 0; i < step2Count; i++) {
                    if (targetLoudnessMatches(config.drcInstructionsUniDrc[candidatesStep2[i].drcInstructionsIndex])) {
                        candidatesStep2[k++].copyFrom(candidatesStep2[i]);
                    }
                }
                step2Count = minimizeTargetLoudnessUpper(k);
            } else if (k == step2Count) {
                step2Count = minimizeTargetLoudnessUpper(step2Count);
            }
            k = 0;
            var outputLevelMax = -1000.0f;
            for (var i = 0; i < step2Count; i++) {
                var level = candidatesStep2[i].outputPeakLevel;
                if (level <= 0.0f && outputLevelMax <= level) {
                    if (outputLevelMax < level) {
                        outputLevelMax = level;
                        k = 0;
                    }
                    candidatesStep2[k++].copyFrom(candidatesStep2[i]);
                    outputLevelMax = level;
                }
            }
            step2Count = k;
        }
        var drcSetIdMax = -1000;
        for (var i = 0; i < step2Count; i++) {
            var instructions = config.drcInstructionsUniDrc[candidatesStep2[i].drcInstructionsIndex];
            if (drcSetIdMax < instructions.drcSetId) {
                drcSetIdMax = instructions.drcSetId;
                candidatesStep2[0].copyFrom(candidatesStep2[i]);
            }
        }
        candidates[0].copyFrom(candidatesStep2[0]);
        return 1;
    }

    private boolean targetLoudnessMatches(DrcInstructionsUniDrc instructions) {
        return instructions.drcSetTargetLoudnessPresent != 0 && params.loudnessNormalizationOn != 0
                && instructions.drcSetTargetLoudnessValueUpper >= params.targetLoudness
                && instructions.drcSetTargetLoudnessValueLower < params.targetLoudness;
    }

    private int minimizeTargetLoudnessUpper(int step2Count) {
        var upperMin = 100;
        var k = 0;
        for (var i = 0; i < step2Count; i++) {
            var instructions = config.drcInstructionsUniDrc[candidatesStep2[i].drcInstructionsIndex];
            if (instructions.drcSetTargetLoudnessPresent != 1) {
                throw new MediaException.Malformed("MPEG-D DRC target loudness selection is inconsistent");
            }
            if (upperMin >= instructions.drcSetTargetLoudnessValueUpper) {
                if (upperMin > instructions.drcSetTargetLoudnessValueUpper) {
                    upperMin = instructions.drcSetTargetLoudnessValueUpper;
                    k = 0;
                }
                candidatesStep2[k++].copyFrom(candidatesStep2[i]);
            }
        }
        return k;
    }

    private int[] selectDrcSet() {
        var restrictToDrcWithAlbumLoudness = params.albumMode == 1;
        var count = 0;
        while (count == 0) {
            count = drcSetPreselection(restrictToDrcWithAlbumLoudness);
            if (count == 0) {
                if (restrictToDrcWithAlbumLoudness) {
                    restrictToDrcWithAlbumLoudness = false;
                    continue;
                }
                throw new MediaException.Malformed("MPEG-D DRC has no selection candidate");
            }
            validateDrcFeatureRequest(params);
            if (params.dynamicRangeControlOn == 1) {
                if (params.numDrcFeatureRequests > 0) {
                    for (var i = 0; i < params.numDrcFeatureRequests; i++) {
                        count = switch (params.drcFeatureRequestType[i]) {
                            case MATCH_EFFECT_TYPE -> matchEffectTypes(params.numDrcEffectTypeRequests[i],
                                    params.numDrcEffectTypeRequestsDesired[i], params.drcEffectTypeRequest[i], count);
                            case MATCH_DYNAMIC_RANGE -> matchDynamicRange(params.dynamicRangeMeasurementRequestType[i],
                                    params.dynamicRangeRequestedIsRange[i], params.dynamicRangeRequestValue[i],
                                    params.dynamicRangeRequestValueMin[i], params.dynamicRangeRequestValueMax[i],
                                    count);
                            case MATCH_DRC_CHARACTERISTIC -> matchDrcCharacteristic(params.drcCharacteristicRequest[i],
                                    count);
                            default -> throw new MediaException.Malformed("MPEG-D DRC feature request is invalid");
                        };
                    }
                } else {
                    var result = selectDrcsWithoutCompressionEffects(count);
                    if (result >= 0) {
                        count = result;
                    } else {
                        count = matchEffectTypes(5, 1, new int[]{EFFECT_TYPE_REQUESTED_GENERAL_COMPR,
                                EFFECT_TYPE_REQUESTED_NIGHT, EFFECT_TYPE_REQUESTED_NOISY, EFFECT_TYPE_REQUESTED_LIMITED,
                                EFFECT_TYPE_REQUESTED_LOWLEVEL}, count);
                    }
                }
                count = drcSetFinalSelection(count);
            }
            if (count == 0) {
                if (restrictToDrcWithAlbumLoudness) {
                    restrictToDrcWithAlbumLoudness = false;
                } else {
                    throw new MediaException.Malformed("MPEG-D DRC has no selection candidate");
                }
            }
        }
        var first = candidates[0];
        var drcSetIdSelected = config.drcInstructionsUniDrc[first.drcInstructionsIndex].drcSetId;
        var eqSetIdSelected = first.eqSetId;
        var loudEqIdSelected = selectLoudEq(params.downmixIdRequested[first.downmixIdRequestIndex], drcSetIdSelected,
                eqSetIdSelected);
        output.loudnessNormalizationGainDb = first.loudnessNormalizationGainDbAdjusted;
        output.outputPeakLevelDb = first.outputPeakLevel;
        output.outputLoudness = first.outputLoudness;
        output.activeDownmixId = params.downmixIdRequested[first.downmixIdRequestIndex];
        output.mixingLevel = first.mixingLevel;
        return new int[]{drcSetIdSelected, eqSetIdSelected, loudEqIdSelected};
    }

    private LoudnessInfo[] loudnessInfos() {
        return params.albumMode == 1 ? loudnessInfoSet.loudnessInfoAlbum : loudnessInfoSet.loudnessInfo;
    }

    private int loudnessInfoCountForMode() {
        return params.albumMode == 1 ? loudnessInfoSet.loudnessInfoAlbumCount : loudnessInfoSet.loudnessInfoCount;
    }

    private int addPeak(int peakCount, int eqSetId, float level, int explicit) {
        if (peakCount < PEAK_TABLE_SIZE) {
            eqSetIdForPeak[peakCount] = eqSetId;
            signalPeakLevel[peakCount] = level;
            explicitPeakInformationPresent[peakCount] = explicit;
        }
        return Math.min(peakCount + 1, PEAK_TABLE_SIZE - 1);
    }

    private int addPeaksFromLoudnessInfo(int peakCount, int drcSetId, int downmixId, float offset, int explicit,
                                         boolean[] matchFound) {
        var infos = loudnessInfos();
        var count = loudnessInfoCountForMode();
        for (var n = 0; n < count; n++) {
            var info = infos[n];
            if (drcSetId == info.drcSetId && downmixId == info.downmixId) {
                if (info.truePeakLevelPresent != 0) {
                    peakCount = addPeak(peakCount, info.eqSetId, info.truePeakLevel + offset, explicit);
                    matchFound[0] = true;
                }
                if (!matchFound[0] && info.samplePeakLevelPresent != 0) {
                    peakCount = addPeak(peakCount, info.eqSetId, info.samplePeakLevel + offset, explicit);
                    matchFound[0] = true;
                }
            }
        }
        return peakCount;
    }

    private int addLimiterPeaks(int peakCount, DrcInstructionsUniDrc instructions, int loudnessDrcSetIdRequested,
                                int downmixIdRequested, boolean[] matchFound) {
        if (instructions.limiterPeakTargetPresent == 0) {
            return peakCount;
        }
        if (instructions.requiresEq == 1) {
            var ext = config.uniDrcConfigExt;
            for (var d = 0; d < ext.eqInstructionsCount; d++) {
                var eq = ext.eqInstructions[d];
                for (var c = 0; c < eq.drcSetIdCount; c++) {
                    if (eq.drcSetId[c] == loudnessDrcSetIdRequested || eq.drcSetId[c] == ID_FOR_ANY_DRC) {
                        for (var i = 0; i < eq.downmixIdCount; i++) {
                            if (eq.downmixId[i] == downmixIdRequested || eq.downmixId[i] == ID_FOR_ANY_DOWNMIX) {
                                peakCount = addPeak(peakCount, eq.eqSetId, instructions.limiterPeakTarget, 1);
                                matchFound[0] = true;
                            }
                        }
                    }
                }
            }
        } else {
            peakCount = addPeak(peakCount, 0, instructions.limiterPeakTarget, 1);
            matchFound[0] = true;
        }
        return peakCount;
    }

    private void getSignalPeakLevel(DrcInstructionsUniDrc instructions, int downmixIdRequested) {
        var drcSetIdRequested = instructions.drcSetId;
        var peakCount = 0;
        eqSetIdForPeak[0] = 0;
        signalPeakLevel[0] = 0.0f;
        explicitPeakInformationPresent[0] = 0;
        var k = 0;
        if (drcSetIdRequested < 0) {
            for (k = 0; k < noCompressionEqCount; k++) {
                eqSetIdForPeak[k] = noCompressionEqId[k];
                signalPeakLevel[k] = 0.0f;
                explicitPeakInformationPresent[k] = 0;
            }
        }
        eqSetIdForPeak[k] = 0;
        signalPeakLevel[k] = 0.0f;
        explicitPeakInformationPresent[k] = 0;
        var prelimCount = k + 1;
        var loudnessDrcSetIdRequested = Math.max(0, drcSetIdRequested);
        var matchFound = new boolean[1];
        peakCount = addPeaksFromLoudnessInfo(peakCount, loudnessDrcSetIdRequested, downmixIdRequested, 0, 1, matchFound);
        if (!matchFound[0]) {
            peakCount = addPeaksFromLoudnessInfo(peakCount, ID_FOR_ANY_DRC, downmixIdRequested, 0, 1, matchFound);
        }
        if (!matchFound[0]) {
            if (downmixIdRequested == instructions.downmixId[0] || instructions.downmixId[0] == ID_FOR_ANY_DOWNMIX) {
                peakCount = addLimiterPeaks(peakCount, instructions, loudnessDrcSetIdRequested, downmixIdRequested,
                        matchFound);
            }
            if (!matchFound[0]) {
                for (var i = 1; i < instructions.downmixIdCount; i++) {
                    if (downmixIdRequested == instructions.downmixId[i]) {
                        peakCount = addLimiterPeaks(peakCount, instructions, loudnessDrcSetIdRequested,
                                downmixIdRequested, matchFound);
                    }
                }
            }
        }
        if (!matchFound[0] && downmixIdRequested != ID_FOR_BASE_LAYOUT) {
            var signalPeakLevelTmp = 0.0f;
            for (var i = 0; i < config.downmixInstructionsCount; i++) {
                var downmix = config.downmixInstructions[i];
                if (downmix.downmixId == downmixIdRequested) {
                    if (downmix.downmixCoefficientsPresent != 0) {
                        var baseChannelCount = config.channelLayout.baseChannelCount;
                        var maxSum = 0.0f;
                        for (var c = 0; c < downmix.targetChannelCount; c++) {
                            var sum = 0.0f;
                            for (var d = 0; d < baseChannelCount; d++) {
                                sum += downmix.downmixCoefficient[c * baseChannelCount + d];
                            }
                            if (maxSum < sum) {
                                maxSum = sum;
                            }
                        }
                        signalPeakLevelTmp = 20.0f * (float) StrictMath.log10(maxSum);
                    }
                    break;
                }
            }
            peakCount = addPeaksFromLoudnessInfo(peakCount, loudnessDrcSetIdRequested, ID_FOR_BASE_LAYOUT,
                    signalPeakLevelTmp, 0, matchFound);
            if (!matchFound[0]) {
                peakCount = addPeaksFromLoudnessInfo(peakCount, ID_FOR_ANY_DRC, ID_FOR_BASE_LAYOUT, signalPeakLevelTmp,
                        0, matchFound);
            }
            if (!matchFound[0]) {
                for (var n = 0; n < config.drcInstructionsCountPlus; n++) {
                    var other = config.drcInstructionsUniDrc[n];
                    if (loudnessDrcSetIdRequested == other.drcSetId && other.downmixIdCount > 0) {
                        if (other.downmixId[0] == ID_FOR_BASE_LAYOUT && other.limiterPeakTargetPresent != 0) {
                            peakCount = addPeak(peakCount, -1, other.limiterPeakTarget + signalPeakLevelTmp, 0);
                            matchFound[0] = true;
                        }
                    }
                }
            }
            if (!matchFound[0]) {
                signalPeakLevel[0] += signalPeakLevelTmp;
            }
        }
        peakInfoCount = peakCount > 0 ? peakCount : prelimCount;
    }

    private float getLoudnessPeakToAverageValue(DrcInstructionsUniDrc instructions, int downmixIdRequested,
                                                int measurementType) {
        var drcSetId = Math.max(0, instructions.drcSetId);
        var value = Float.NaN;
        if (params.albumMode == 1) {
            for (var n = 0; n < loudnessInfoSet.loudnessInfoAlbumCount; n++) {
                var info = loudnessInfoSet.loudnessInfoAlbum[n];
                if (drcSetId == info.drcSetId && downmixIdRequested == info.downmixId) {
                    value = extractLoudnessPeakToAverageValue(info, measurementType, value);
                }
            }
        }
        if (Float.isNaN(value)) {
            for (var n = 0; n < loudnessInfoSet.loudnessInfoCount; n++) {
                var info = loudnessInfoSet.loudnessInfo[n];
                if (drcSetId == info.drcSetId && downmixIdRequested == info.downmixId) {
                    value = extractLoudnessPeakToAverageValue(info, measurementType, value);
                }
            }
        }
        return value;
    }

    private static float extractLoudnessPeakToAverageValue(LoudnessInfo info, int measurementType, float previous) {
        int[] systemBonusProgramLoudness = {0, 0, 1, 0, 0, 0, 0, 2, 3, 4, 0, 0};
        int[] systemBonusPeakLoudness = {0, 7, 0, 0, 0, 0, 6, 5, 4, 3, 2, 1};
        var programLoudnessPresent = false;
        var peakLoudnessPresent = false;
        var matchMeasureProgramLoudness = 0;
        var matchMeasurePeakLoudness = 0;
        var programLoudness = 0.0f;
        var peakLoudness = 0.0f;
        for (var k = 0; k < info.measurementCount; k++) {
            var measure = info.loudnessMeasure[k];
            var system = Math.min(measure.measurementSystem, systemBonusProgramLoudness.length - 1);
            if (measure.methodDefinition == METHOD_DEFINITION_PROGRAM_LOUDNESS
                    && matchMeasureProgramLoudness < systemBonusProgramLoudness[system]) {
                programLoudness = measure.methodValue;
                programLoudnessPresent = true;
                matchMeasureProgramLoudness = systemBonusProgramLoudness[system];
            }
            var method = switch (measurementType) {
                case SHORT_TERM_LOUDNESS_TO_AVG -> METHOD_DEFINITION_SHORT_TERM_LOUDNESS_MAX;
                case MOMENTARY_LOUDNESS_TO_AVG -> METHOD_DEFINITION_MOMENTARY_LOUDNESS_MAX;
                case TOP_OF_LOUDNESS_RANGE_TO_AVG -> METHOD_DEFINITION_MAX_OF_LOUDNESS_RANGE;
                default -> throw new MediaException.Malformed("MPEG-D DRC dynamic range request is invalid");
            };
            if (measure.methodDefinition == method && matchMeasurePeakLoudness < systemBonusPeakLoudness[system]) {
                peakLoudness = measure.methodValue;
                peakLoudnessPresent = true;
                matchMeasurePeakLoudness = systemBonusPeakLoudness[system];
            }
        }
        return programLoudnessPresent && peakLoudnessPresent ? peakLoudness - programLoudness : previous;
    }

    private static boolean overallLoudnessPresent(LoudnessInfo info) {
        var present = false;
        for (var m = 0; m < info.measurementCount; m++) {
            var method = info.loudnessMeasure[m].methodDefinition;
            if (method == METHOD_DEFINITION_PROGRAM_LOUDNESS || method == METHOD_DEFINITION_ANCHOR_LOUDNESS) {
                present = true;
            }
        }
        return present;
    }

    private float getMixingLevel(int downmixIdRequested, int drcSetIdRequested, int eqSetIdRequested) {
        var mixingLevel = MIXING_LEVEL_DEFAULT;
        var loudnessDrcSetIdRequested = Math.max(0, drcSetIdRequested);
        var infos = loudnessInfos();
        var count = loudnessInfoCountForMode();
        for (var n = 0; n < count; n++) {
            var info = infos[n];
            if ((downmixIdRequested == info.downmixId || info.downmixId == ID_FOR_ANY_DOWNMIX)
                    && loudnessDrcSetIdRequested == info.drcSetId && eqSetIdRequested == info.eqSetId) {
                for (var k = 0; k < info.measurementCount; k++) {
                    if (info.loudnessMeasure[k].methodDefinition == METHOD_DEFINITION_MIXING_LEVEL) {
                        mixingLevel = info.loudnessMeasure[k].methodValue;
                        break;
                    }
                }
            }
        }
        return mixingLevel;
    }

    private int matchAndCheckLoudnessInfo(int count, LoudnessInfo[] infos, int downmixId, int drcSetId, int found) {
        for (var n = 0; n < count; n++) {
            var info = infos[n];
            if (downmixId == info.downmixId && drcSetId == info.drcSetId && overallLoudnessPresent(info)
                    && found < PEAK_TABLE_SIZE) {
                matchingLoudnessInfo[found++] = info;
            }
        }
        return found;
    }

    private int matchAndCheckLoudnessPayloads(int count, LoudnessInfo[] infos, int downmixId, int drcSetId) {
        int[][] fallbacks = {{downmixId, drcSetId}, {ID_FOR_ANY_DOWNMIX, drcSetId}, {downmixId, ID_FOR_ANY_DRC},
                {downmixId, ID_FOR_NO_DRC}, {ID_FOR_ANY_DOWNMIX, ID_FOR_ANY_DRC}, {ID_FOR_ANY_DOWNMIX, ID_FOR_NO_DRC},
                {ID_FOR_BASE_LAYOUT, drcSetId}, {ID_FOR_BASE_LAYOUT, ID_FOR_ANY_DRC},
                {ID_FOR_BASE_LAYOUT, ID_FOR_NO_DRC}};
        for (var fallback : fallbacks) {
            var found = matchAndCheckLoudnessInfo(count, infos, fallback[0], fallback[1], 0);
            if (found > 0) {
                return found;
            }
        }
        return 0;
    }

    private int findInfoWithOverallLoudness(int downmixIdRequested, int drcSetIdRequested) {
        var loudnessDrcSetIdRequested = drcSetIdRequested < 0 ? ID_FOR_NO_DRC : drcSetIdRequested;
        var found = 0;
        if (params.albumMode == 1) {
            found = matchAndCheckLoudnessPayloads(loudnessInfoSet.loudnessInfoAlbumCount,
                    loudnessInfoSet.loudnessInfoAlbum, downmixIdRequested, loudnessDrcSetIdRequested);
        }
        if (found == 0) {
            found = matchAndCheckLoudnessPayloads(loudnessInfoSet.loudnessInfoCount, loudnessInfoSet.loudnessInfo,
                    downmixIdRequested, loudnessDrcSetIdRequested);
        }
        return found;
    }

    private static float getHighPassLoudnessAdjustment(LoudnessInfo info) {
        var adjust = Float.NaN;
        for (var m = 0; m < info.measurementCount; m++) {
            if (info.loudnessMeasure[m].measurementSystem == MEASUREMENT_SYSTEM_BS_1770_4_PRE_PROCESSING) {
                for (var k = 0; k < info.measurementCount; k++) {
                    if (info.loudnessMeasure[k].measurementSystem == MEASUREMENT_SYSTEM_BS_1770_4
                            && info.loudnessMeasure[m].methodDefinition == info.loudnessMeasure[k].methodDefinition) {
                        adjust = info.loudnessMeasure[m].methodValue - info.loudnessMeasure[k].methodValue;
                    }
                }
            }
        }
        return adjust;
    }

    private float findHighPassLoudnessAdjustment(int downmixIdRequested, int drcSetIdRequested, float deviceCutoffFreq) {
        var loudnessDrcSetIdRequested = Math.max(0, drcSetIdRequested);
        var adjust = Float.NaN;
        if (params.albumMode == 1) {
            for (var n = 0; n < loudnessInfoSet.loudnessInfoAlbumCount; n++) {
                var info = loudnessInfoSet.loudnessInfoAlbum[n];
                if ((downmixIdRequested == info.downmixId || info.downmixId == ID_FOR_ANY_DOWNMIX)
                        && loudnessDrcSetIdRequested == info.drcSetId) {
                    adjust = keepAdjust(adjust, getHighPassLoudnessAdjustment(loudnessInfoSet.loudnessInfo[n]));
                }
            }
        }
        if (Float.isNaN(adjust)) {
            for (var n = 0; n < loudnessInfoSet.loudnessInfoCount; n++) {
                var info = loudnessInfoSet.loudnessInfo[n];
                if ((downmixIdRequested == info.downmixId || info.downmixId == ID_FOR_ANY_DOWNMIX)
                        && loudnessDrcSetIdRequested == info.drcSetId) {
                    adjust = keepAdjust(adjust, getHighPassLoudnessAdjustment(info));
                }
            }
        }
        for (var pass = 0; pass < 2 && Float.isNaN(adjust); pass++) {
            var drcSetId = pass == 0 ? loudnessDrcSetIdRequested : 0;
            for (var n = 0; n < loudnessInfoSet.loudnessInfoCount; n++) {
                var info = loudnessInfoSet.loudnessInfo[n];
                if (info.downmixId == ID_FOR_BASE_LAYOUT && drcSetId == info.drcSetId) {
                    adjust = keepAdjust(adjust, getHighPassLoudnessAdjustment(info));
                }
            }
        }
        if (Float.isNaN(adjust)) {
            return Float.NaN;
        }
        return adjust * (Math.max(20.0f, Math.min(500.0f, deviceCutoffFreq)) - 20.0f) / (500.0f - 20.0f);
    }

    private static float keepAdjust(float previous, float next) {
        return Float.isNaN(next) ? previous : next;
    }

    private void initLoudnessControl(int downmixIdRequested, int drcSetIdRequested) {
        var k = 0;
        if (drcSetIdRequested < 0) {
            for (k = 0; k < noCompressionEqCount; k++) {
                eqSetIdForLoudness[k] = noCompressionEqId[k];
                loudness[k] = UNDEFINED_LOUDNESS_VALUE;
                loudnessNormalizationGainDb[k] = 0.0f;
            }
        }
        eqSetIdForLoudness[k] = 0;
        loudness[k] = UNDEFINED_LOUDNESS_VALUE;
        loudnessNormalizationGainDb[k] = 0.0f;
        var prelimCount = k + 1;
        var infoCount = 0;
        if (params.loudnessNormalizationOn == 1) {
            infoCount = findInfoWithOverallLoudness(downmixIdRequested, drcSetIdRequested);
            if (infoCount > 0) {
                int requestedMethodDefinition;
                int otherMethodDefinition;
                switch (params.loudnessMeasurementMethod) {
                    case 0, 1 -> {
                        requestedMethodDefinition = METHOD_DEFINITION_PROGRAM_LOUDNESS;
                        otherMethodDefinition = METHOD_DEFINITION_ANCHOR_LOUDNESS;
                    }
                    case 2 -> {
                        requestedMethodDefinition = METHOD_DEFINITION_ANCHOR_LOUDNESS;
                        otherMethodDefinition = METHOD_DEFINITION_PROGRAM_LOUDNESS;
                    }
                    default -> throw new MediaException.Malformed("MPEG-D DRC loudness method request is invalid");
                }
                var systemBonus = switch (params.loudnessMeasurementSystem) {
                    case 0, 1 -> new int[]{0, 0, 8, 0, 1, 3, 0, 5, 6, 7, 4, 2};
                    case 2 -> new int[]{0, 0, 1, 0, 8, 5, 0, 2, 3, 4, 6, 7};
                    case 3 -> new int[]{0, 0, 3, 0, 1, 8, 0, 4, 5, 6, 7, 2};
                    case 4 -> new int[]{0, 0, 5, 0, 1, 3, 0, 8, 6, 7, 4, 2};
                    case 5 -> new int[]{0, 0, 5, 0, 1, 3, 0, 6, 8, 7, 4, 2};
                    case 6 -> new int[]{0, 0, 5, 0, 1, 3, 0, 6, 7, 8, 4, 2};
                    case 7 -> new int[]{0, 0, 3, 0, 1, 7, 0, 4, 5, 6, 8, 2};
                    case 8 -> new int[]{0, 0, 1, 0, 7, 5, 0, 2, 3, 4, 6, 8};
                    default -> throw new MediaException.Malformed("MPEG-D DRC loudness system request is invalid");
                };
                var requestedPreprocessing = switch (params.loudnessMeasurementPreProc) {
                    case 0, 1 -> false;
                    case 2 -> true;
                    default -> throw new MediaException.Malformed("MPEG-D DRC preprocessing request is invalid");
                };
                var methodValue = 0.0f;
                for (k = 0; k < infoCount; k++) {
                    var info = matchingLoudnessInfo[k];
                    var matchMeasure = -1;
                    for (var n = 0; n < info.measurementCount; n++) {
                        var measure = info.loudnessMeasure[n];
                        var bonus = systemBonus[Math.min(measure.measurementSystem, systemBonus.length - 1)];
                        if (matchMeasure < bonus && requestedMethodDefinition == measure.methodDefinition) {
                            methodValue = measure.methodValue;
                            matchMeasure = bonus;
                        }
                    }
                    if (matchMeasure == -1) {
                        for (var n = 0; n < info.measurementCount; n++) {
                            var measure = info.loudnessMeasure[n];
                            var bonus = systemBonus[Math.min(measure.measurementSystem, systemBonus.length - 1)];
                            if (matchMeasure < bonus && otherMethodDefinition == measure.methodDefinition) {
                                methodValue = measure.methodValue;
                                matchMeasure = bonus;
                            }
                        }
                    }
                    if (requestedPreprocessing) {
                        var adjust = findHighPassLoudnessAdjustment(downmixIdRequested, drcSetIdRequested,
                                params.deviceCutOffFrequency);
                        methodValue += Float.isNaN(adjust) ? -2.0f : adjust;
                    }
                    eqSetIdForLoudness[k] = info.eqSetId;
                    loudnessNormalizationGainDb[k] = params.targetLoudness - methodValue;
                    loudness[k] = methodValue;
                }
            }
        }
        loudnessInfoCount = infoCount > 0 ? infoCount : prelimCount;
    }
}
