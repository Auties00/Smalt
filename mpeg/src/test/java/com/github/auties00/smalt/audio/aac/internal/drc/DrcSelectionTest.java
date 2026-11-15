package com.github.auties00.smalt.audio.aac.internal.drc;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;

import static com.github.auties00.smalt.audio.aac.internal.drc.DrcModel.*;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the DRC selection process of ISO/IEC 23003-4 clause 6.3 and Annex E on hand-built configurations: target
 * configuration requests and downmixes, fading, ducking and dependent DRC sets, the band count and complexity limits
 * of the decoder, equalizer and loudness equalizer selection, loudness normalization with every measurement method,
 * system and preprocessing request, album mode, the dynamic range and DRC characteristic requests, the target
 * loudness ranges of DRC sets, and every inconsistent configuration or request, which must be refused as malformed.
 */
@DisplayName("MPEG-D DRC selection process")
class DrcSelectionTest {
    private static final float EPSILON = 1e-4f;

    /**
     * Builds a configuration of a stereo base layout whose selected-location coefficients hold a one band gain set of
     * characteristic 3 (index 0) and a four band gain set of characteristic 7 (index 1).
     */
    private static final class Fixture {
        final UniDrcConfig config = new UniDrcConfig();
        final LoudnessInfoSet loudness = new LoudnessInfoSet();
        final DrcSelection.Params params = new DrcSelection.Params();
        DrcSelection selection;

        Fixture() {
            config.sampleRate = 48000;
            config.channelLayout.baseChannelCount = 2;
            config.drcCoefficientsUniDrcCount = 1;
            var coefficients = config.drcCoefficientsUniDrc[0];
            coefficients.drcLocation = LOCATION_SELECTED;
            coefficients.gainSetCount = 2;
            coefficients.gainSetParams[0].bandCount = 1;
            coefficients.gainSetParams[0].gainParams[0].drcCharacteristic = 3;
            coefficients.gainSetParams[1].bandCount = 4;
            for (var b = 0; b < 4; b++) {
                coefficients.gainSetParams[1].gainParams[b].drcCharacteristic = 7;
            }
        }

        DrcInstructionsUniDrc set(int id, int effect, int... downmixIds) {
            var instructions = config.drcInstructionsUniDrc[config.drcInstructionsUniDrcCount++];
            instructions.clear();
            instructions.drcSetId = id;
            instructions.drcSetEffect = effect;
            instructions.drcLocation = LOCATION_SELECTED;
            var ids = downmixIds.length == 0 ? new int[]{ID_FOR_BASE_LAYOUT} : downmixIds;
            instructions.downmixIdCount = ids.length;
            System.arraycopy(ids, 0, instructions.downmixId, 0, ids.length);
            instructions.nDrcChannelGroups = 1;
            instructions.gainSetIndexForChannelGroup[0] = 0;
            return instructions;
        }

        DownmixInstructions downmix(int id, int channels, int layout, float... coefficients) {
            var downmix = config.downmixInstructions[config.downmixInstructionsCount++];
            downmix.downmixId = id;
            downmix.targetChannelCount = channels;
            downmix.targetLayout = layout;
            downmix.downmixCoefficientsPresent = coefficients.length > 0 ? 1 : 0;
            System.arraycopy(coefficients, 0, downmix.downmixCoefficient, 0, coefficients.length);
            return downmix;
        }

        EqInstructions eq(int id, int purpose, int[] downmixIds, int[] drcSetIds) {
            var ext = config.uniDrcConfigExt;
            var eq = ext.eqInstructions[ext.eqInstructionsCount++];
            eq.eqSetId = id;
            eq.eqSetPurpose = purpose;
            eq.downmixIdCount = downmixIds.length;
            System.arraycopy(downmixIds, 0, eq.downmixId, 0, downmixIds.length);
            eq.drcSetIdCount = drcSetIds.length;
            System.arraycopy(drcSetIds, 0, eq.drcSetId, 0, drcSetIds.length);
            return eq;
        }

        LoudnessInfo info(int drcSetId, int downmixId) {
            return info(loudness.loudnessInfo, loudness.loudnessInfoCount++, drcSetId, downmixId);
        }

        LoudnessInfo album(int drcSetId, int downmixId) {
            return info(loudness.loudnessInfoAlbum, loudness.loudnessInfoAlbumCount++, drcSetId, downmixId);
        }

        private static LoudnessInfo info(LoudnessInfo[] infos, int index, int drcSetId, int downmixId) {
            var info = infos[index];
            info.drcSetId = drcSetId;
            info.downmixId = downmixId;
            return info;
        }

        DrcSelection.Output run() {
            return run(DrcSelection.SUBBAND_DOMAIN_MODE_OFF);
        }

        DrcSelection.Output run(int subbandDomainMode) {
            DrcParser.generateDrcInstructionsForDrcOff(config);
            selection = new DrcSelection(params, subbandDomainMode);
            selection.process(config, loudness);
            return selection.output;
        }

        void requestEffects(int... effects) {
            params.dynamicRangeControlOn = 1;
            params.numDrcFeatureRequests = 1;
            params.drcFeatureRequestType[0] = DrcSelection.MATCH_EFFECT_TYPE;
            params.numDrcEffectTypeRequests[0] = effects.length;
            params.numDrcEffectTypeRequestsDesired[0] = 1;
            System.arraycopy(effects, 0, params.drcEffectTypeRequest[0], 0, effects.length);
        }

        void requestFeature(int type) {
            params.dynamicRangeControlOn = 1;
            params.numDrcFeatureRequests = 1;
            params.drcFeatureRequestType[0] = type;
        }
    }

    private static void measure(LoudnessInfo info, int method, float value, int system) {
        var measure = info.loudnessMeasure[info.measurementCount++];
        measure.methodDefinition = method;
        measure.methodValue = value;
        measure.measurementSystem = system;
    }

    private static int[] selected(DrcSelection.Output output) {
        var ids = Arrays.copyOf(output.selectedDrcSetIds, output.numSelectedDrcSets);
        Arrays.sort(ids);
        return ids;
    }

    private static void assertMalformed(Fixture fixture, String message) {
        var exception = assertThrows(MediaException.Malformed.class, fixture::run);
        assertTrue(exception.getMessage().contains(message), exception.getMessage());
    }

    @Test
    @DisplayName("keeps the base layout of the configuration across selections")
    void reselection() {
        var fixture = new Fixture();
        fixture.config.channelLayout.layoutSignalingPresent = 1;
        fixture.config.channelLayout.definedLayout = 2;
        var output = fixture.run();
        assertEquals(0, output.numSelectedDrcSets);
        assertEquals(2, fixture.params.baseChannelCount);
        assertEquals(2, fixture.params.baseLayout);
        fixture.selection.process(fixture.config, fixture.loudness);
        assertEquals(2, fixture.selection.output.baseChannelCount);
        assertEquals(2, fixture.selection.output.targetChannelCount);
        assertEquals(0, fixture.selection.output.numSelectedDrcSets);
    }

    @Test
    @DisplayName("maps a target layout request to its downmix, the downmix matrix and the downmix peak")
    void layoutRequest() {
        var fixture = new Fixture();
        fixture.downmix(1, 1, 1, 1.0f, 1.0f);
        fixture.params.targetConfigRequestType = 1;
        fixture.params.targetLayoutRequested = 1;
        var output = fixture.run();
        assertEquals(1, output.activeDownmixId);
        assertEquals(1, output.targetChannelCount);
        assertEquals(1, output.targetLayout);
        assertEquals(1, output.downmixMatrixPresent);
        assertEquals(1.0f, output.downmixMatrix[0][0]);
        assertEquals(1.0f, output.downmixMatrix[1][0]);
        // two channels summed at unit gain peak 6.02 dB above the base layout, which the gain removes
        var peak = (float) (20 * Math.log10(2));
        assertEquals(-peak, output.loudnessNormalizationGainDb, EPSILON);
        assertEquals(0.0f, output.outputPeakLevelDb, EPSILON);
    }

    @Test
    @DisplayName("takes the downmix peak from the base layout loudness information")
    void downmixPeakFromBaseLayout() {
        var fixture = new Fixture();
        fixture.downmix(1, 1, 1, 1.0f, 1.0f);
        var info = fixture.info(0, ID_FOR_BASE_LAYOUT);
        info.truePeakLevelPresent = 1;
        info.truePeakLevel = -10;
        fixture.params.targetConfigRequestType = 2;
        fixture.params.targetChannelCountRequested = 1;
        var output = fixture.run();
        assertEquals(1, output.activeDownmixId);
        assertEquals(-10 + 20 * Math.log10(2), output.outputPeakLevelDb, EPSILON);
        assertEquals(0.0f, output.loudnessNormalizationGainDb, EPSILON);
    }

    @ParameterizedTest(name = "{0} channels")
    @CsvSource({"2,0,2", "1,1,1", "6,0,2"})
    @DisplayName("maps a channel count request to the base layout or a downmix")
    void channelCountRequest(int requested, int downmixId, int channels) {
        var fixture = new Fixture();
        fixture.downmix(1, 1, 3);
        fixture.params.targetConfigRequestType = 2;
        fixture.params.targetChannelCountRequested = requested;
        var output = fixture.run();
        assertEquals(downmixId, output.activeDownmixId);
        assertEquals(channels, output.targetChannelCount);
        assertEquals(0, output.downmixMatrixPresent);
    }

    @Test
    @DisplayName("requests at most fifteen downmixes of the requested layout")
    void downmixRequestLimit() {
        var fixture = new Fixture();
        for (var id = 1; id <= 16; id++) {
            fixture.downmix(id, 1, 1);
        }
        fixture.params.targetConfigRequestType = 1;
        fixture.params.targetLayoutRequested = 1;
        var output = fixture.run();
        assertEquals(DrcSelection.MAX_NUM_DOWNMIX_ID_REQUESTS, fixture.params.numDownmixIdRequests);
        assertEquals(1, output.activeDownmixId);
        assertEquals(1, fixture.params.targetChannelCountPrelim);
    }

    @Test
    @DisplayName("refuses a reserved target configuration request")
    void reservedTargetRequest() {
        var fixture = new Fixture();
        fixture.params.targetConfigRequestType = 3;
        assertMalformed(fixture, "target configuration request");
    }

    @Test
    @DisplayName("refuses a requested downmix the configuration does not define")
    void undefinedDownmix() {
        var fixture = new Fixture();
        fixture.set(1, EFFECT_BIT_NIGHT, ID_FOR_ANY_DOWNMIX);
        fixture.params.numDownmixIdRequests = 1;
        fixture.params.downmixIdRequested[0] = 5;
        fixture.params.dynamicRangeControlOn = 1;
        assertMalformed(fixture, "downmix 5 is not defined");
    }

    @Test
    @DisplayName("adds the fading set to the selected set")
    void fading() {
        var fixture = new Fixture();
        fixture.set(1, EFFECT_BIT_NIGHT);
        fixture.set(2, EFFECT_BIT_FADE, ID_FOR_ANY_DOWNMIX);
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        var output = fixture.run();
        assertArrayEquals(new int[]{1, 2}, selected(output));
        assertEquals(0, output.selectedDownmixIds[0]);
    }

    @Test
    @DisplayName("ignores the fading set in album mode")
    void fadingInAlbumMode() {
        var fixture = new Fixture();
        fixture.set(1, EFFECT_BIT_NIGHT);
        fixture.set(2, EFFECT_BIT_FADE, ID_FOR_ANY_DOWNMIX);
        fixture.album(1, ID_FOR_BASE_LAYOUT);
        fixture.params.albumMode = 1;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        assertArrayEquals(new int[]{1}, selected(fixture.run()));
    }

    @Test
    @DisplayName("refuses a fading set restricted to a downmix")
    void fadingForOneDownmix() {
        var fixture = new Fixture();
        fixture.set(2, EFFECT_BIT_FADE, ID_FOR_BASE_LAYOUT);
        assertMalformed(fixture, "fading set");
    }

    @Test
    @DisplayName("replaces the fading set with a ducking set, which applies before any downmix")
    void ducking() {
        var fixture = new Fixture();
        fixture.set(2, EFFECT_BIT_FADE, ID_FOR_ANY_DOWNMIX);
        var duck = fixture.set(3, EFFECT_BIT_DUCK_SELF, 5, ID_FOR_BASE_LAYOUT);
        duck.drcApplyToDownmix = 1;
        var output = fixture.run();
        assertArrayEquals(new int[]{3}, selected(output));
        assertEquals(0, output.selectedDownmixIds[0]);
    }

    @Test
    @DisplayName("selects a set together with the set it depends on")
    void dependentSet() {
        var fixture = new Fixture();
        var base = fixture.set(1, EFFECT_BIT_GENERAL_COMPR);
        base.noIndependentUse = 1;
        var dependent = fixture.set(2, EFFECT_BIT_NOISY);
        dependent.dependsOnDrcSetPresent = 1;
        dependent.dependsOnDrcSet = 1;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_GENERAL_COMPR);
        var output = fixture.run();
        assertArrayEquals(new int[]{1, 2}, selected(output));
    }

    @Test
    @DisplayName("ignores the clipping set a set depends on when matching the DRC characteristic")
    void dependentClippingSet() {
        var fixture = new Fixture();
        var clipping = fixture.set(5, EFFECT_BIT_CLIPPING);
        clipping.noIndependentUse = 1;
        clipping.gainSetIndexForChannelGroup[0] = 1;
        var set = fixture.set(1, EFFECT_BIT_NIGHT);
        set.dependsOnDrcSetPresent = 1;
        set.dependsOnDrcSet = 5;
        var other = fixture.set(2, EFFECT_BIT_NIGHT);
        other.gainSetIndexForChannelGroup[0] = 1;
        fixture.requestFeature(DrcSelection.MATCH_DRC_CHARACTERISTIC);
        fixture.params.drcCharacteristicRequest[0] = 3;
        assertArrayEquals(new int[]{1, 5}, selected(fixture.run()));
    }

    @Test
    @DisplayName("refuses a set depending on an undefined set")
    void undefinedDependency() {
        var fixture = new Fixture();
        var set = fixture.set(2, EFFECT_BIT_NOISY);
        set.dependsOnDrcSetPresent = 1;
        set.dependsOnDrcSet = 7;
        assertMalformed(fixture, "set 7 is not defined");
    }

    @Test
    @DisplayName("refuses a set depending on a set the effect match cannot find")
    void missingDependency() {
        var fixture = new Fixture();
        var set = fixture.set(2, EFFECT_BIT_NOISY);
        set.dependsOnDrcSetPresent = 1;
        set.dependsOnDrcSet = 0;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        assertMalformed(fixture, "depends on an undefined set");
    }

    @Test
    @DisplayName("refuses a chain of dependent sets")
    void dependencyChain() {
        var fixture = new Fixture();
        fixture.set(3, EFFECT_BIT_LIMITED).noIndependentUse = 1;
        var middle = fixture.set(1, EFFECT_BIT_GENERAL_COMPR);
        middle.dependsOnDrcSetPresent = 1;
        middle.dependsOnDrcSet = 3;
        middle.noIndependentUse = 1;
        var top = fixture.set(2, EFFECT_BIT_NOISY);
        top.dependsOnDrcSetPresent = 1;
        top.dependsOnDrcSet = 1;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        assertMalformed(fixture, "depends on another set");
    }

    @ParameterizedTest(name = "{0} bands supported")
    @CsvSource({"4,3", "2,2"})
    @DisplayName("permits only the sets whose gain sets have the bands the decoder supports")
    void bandLimit(int supported, int expected) {
        var fixture = new Fixture();
        fixture.set(2, EFFECT_BIT_NIGHT);
        fixture.set(3, EFFECT_BIT_NIGHT).gainSetIndexForChannelGroup[0] = 1;
        fixture.params.numBandsSupported = supported;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        assertArrayEquals(new int[]{expected}, selected(fixture.run()));
    }

    @ParameterizedTest(name = "{0} bands supported")
    @CsvSource({"4,4", "2,2"})
    @DisplayName("counts the bands of the set a set depends on")
    void dependentBandLimit(int supported, int expected) {
        var fixture = new Fixture();
        var wide = fixture.set(1, EFFECT_BIT_GENERAL_COMPR);
        wide.noIndependentUse = 1;
        wide.gainSetIndexForChannelGroup[0] = 1;
        fixture.set(2, EFFECT_BIT_NIGHT);
        var dependent = fixture.set(4, EFFECT_BIT_NIGHT);
        dependent.dependsOnDrcSetPresent = 1;
        dependent.dependsOnDrcSet = 1;
        fixture.params.numBandsSupported = supported;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        var ids = selected(fixture.run());
        assertEquals(expected, ids[ids.length - 1]);
    }

    @Test
    @DisplayName("gives up when the second channel group of a dependent set has more bands than supported")
    void dependentBandLimitAfterSelection() {
        var fixture = new Fixture();
        var wide = fixture.set(1, EFFECT_BIT_GENERAL_COMPR);
        wide.noIndependentUse = 1;
        wide.nDrcChannelGroups = 2;
        wide.gainSetIndexForChannelGroup[1] = 1;
        fixture.set(2, EFFECT_BIT_NIGHT);
        var dependent = fixture.set(4, EFFECT_BIT_NIGHT);
        dependent.dependsOnDrcSetPresent = 1;
        dependent.dependsOnDrcSet = 1;
        fixture.params.numBandsSupported = 2;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        // the complexity check after the selection bars the first listed set, the dependent one, which is never
        // selected alone, so every repetition selects the same sets until the repetition limit
        assertMalformed(fixture, "does not converge");
    }

    @ParameterizedTest(name = "{0} Hz")
    @CsvSource({"48000,3", "1536000,2"})
    @DisplayName("permits only the sets whose complexity the decoder supports at the sample rate")
    void complexityLimit(int sampleRate, int expected) {
        var fixture = new Fixture();
        fixture.config.sampleRate = sampleRate;
        fixture.set(2, EFFECT_BIT_NIGHT);
        fixture.set(3, EFFECT_BIT_NIGHT).drcSetComplexityLevel = 15;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        assertArrayEquals(new int[]{expected}, selected(fixture.run()));
    }

    /**
     * Builds a set of complexity 2^14 that with its equalizer of complexity 2^15 exceeds the decoder at 768 kHz,
     * though each fits alone.
     */
    private static Fixture costlyEqualizer(boolean requiresEq) {
        var fixture = new Fixture();
        fixture.config.sampleRate = 48000 * 16;
        var set = fixture.set(1, EFFECT_BIT_NIGHT);
        set.drcSetComplexityLevel = 14;
        set.requiresEq = requiresEq ? 1 : 0;
        var eq = fixture.eq(1, 1, new int[]{ID_FOR_BASE_LAYOUT}, new int[]{ID_FOR_ANY_DRC});
        eq.eqSetComplexityLevel = 15;
        eq.tdFilterCascadePresent = 1;
        fixture.params.eqSetPurposeRequest = 1;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        return fixture;
    }

    @Test
    @DisplayName("drops a set that requires an equalizer the decoder cannot run with it")
    void complexityDropsSet() {
        var output = costlyEqualizer(true).run();
        assertEquals(0, output.numSelectedDrcSets);
        assertEquals(1, output.selectedEqSetIds[0]);
    }

    @Test
    @DisplayName("keeps a set that runs without its equalizer when both exceed the decoder")
    void complexityKeepsSet() {
        var output = costlyEqualizer(false).run();
        assertArrayEquals(new int[]{1}, selected(output));
    }

    @Test
    @DisplayName("selects an equalizer with the equalizer it depends on, and one applied to the downmix")
    void dependentEqualizer() {
        var fixture = new Fixture();
        var downmixEq = fixture.eq(1, 1, new int[]{ID_FOR_ANY_DOWNMIX}, new int[]{ID_FOR_ANY_DRC});
        downmixEq.noIndependentEqUse = 1;
        downmixEq.eqApplyToDownmix = 1;
        var eq = fixture.eq(2, 1, new int[]{ID_FOR_BASE_LAYOUT}, new int[]{ID_FOR_ANY_DRC});
        eq.dependsOnEqSetPresent = 1;
        eq.dependsOnEqSet = 1;
        fixture.downmix(1, 1, 1);
        var other = fixture.eq(3, 2, new int[]{1}, new int[]{ID_FOR_ANY_DRC});
        other.eqApplyToDownmix = 1;
        var output = fixture.run();
        assertArrayEquals(new int[]{2, 1}, output.selectedEqSetIds);
    }

    @Test
    @DisplayName("matches equalizers to the selected set and the requested purpose")
    void equalizerWithSet() {
        var fixture = new Fixture();
        fixture.set(1, EFFECT_BIT_NIGHT);
        var downmixEq = fixture.eq(1, 1, new int[]{ID_FOR_ANY_DOWNMIX}, new int[]{ID_FOR_ANY_DRC});
        downmixEq.noIndependentEqUse = 1;
        downmixEq.eqApplyToDownmix = 1;
        var eq = fixture.eq(2, 2, new int[]{ID_FOR_BASE_LAYOUT}, new int[]{1});
        eq.dependsOnEqSetPresent = 1;
        eq.dependsOnEqSet = 1;
        fixture.eq(3, 1, new int[]{4}, new int[]{9});
        fixture.params.eqSetPurposeRequest = 2;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        var output = fixture.run();
        assertArrayEquals(new int[]{1}, selected(output));
        assertArrayEquals(new int[]{2, 1}, output.selectedEqSetIds);
    }

    @Test
    @DisplayName("falls back to the default equalizer purpose when no equalizer has the requested one")
    void equalizerPurposeFallback() {
        var fixture = new Fixture();
        fixture.set(1, EFFECT_BIT_NIGHT);
        fixture.eq(2, 1, new int[]{ID_FOR_BASE_LAYOUT}, new int[]{1});
        fixture.params.eqSetPurposeRequest = 8;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        var output = fixture.run();
        assertArrayEquals(new int[]{1}, selected(output));
        assertEquals(2, output.selectedEqSetIds[0]);
    }

    @Test
    @DisplayName("refuses an equalizer depending on an undefined equalizer")
    void undefinedEqualizerDependency() {
        var fixture = new Fixture();
        var eq = fixture.eq(2, 1, new int[]{ID_FOR_BASE_LAYOUT}, new int[]{ID_FOR_ANY_DRC});
        eq.dependsOnEqSetPresent = 1;
        eq.dependsOnEqSet = 9;
        assertMalformed(fixture, "equalizer set 9 is not defined");
    }

    @Test
    @DisplayName("selects the loudness equalizer matching the downmix, the DRC set and the equalizer")
    void loudnessEqualizer() {
        var fixture = new Fixture();
        var ext = fixture.config.uniDrcConfigExt;
        ext.loudEqInstructionsCount = 3;
        var elsewhere = ext.loudEqInstructions[0];
        elsewhere.loudEqSetId = 1;
        elsewhere.drcLocation = 2;
        var otherDownmix = ext.loudEqInstructions[1];
        otherDownmix.loudEqSetId = 2;
        otherDownmix.drcLocation = LOCATION_SELECTED;
        otherDownmix.downmixIdCount = 1;
        otherDownmix.downmixId[0] = 5;
        var matching = ext.loudEqInstructions[2];
        matching.loudEqSetId = 3;
        matching.drcLocation = LOCATION_SELECTED;
        matching.downmixIdCount = 1;
        matching.downmixId[0] = ID_FOR_ANY_DOWNMIX;
        matching.drcSetIdCount = 2;
        matching.drcSetId[0] = 7;
        matching.drcSetId[1] = ID_FOR_ANY_DRC;
        matching.eqSetIdCount = 2;
        matching.eqSetId[0] = 5;
        matching.eqSetId[1] = ID_FOR_ANY_EQ;
        fixture.params.loudnessEqRequest = 1;
        assertEquals(3, fixture.run().selectedLoudEqId);
        assertEquals(-1, DrcSelection.findLoudEqInstructionsIndexForId(fixture.config, 0));
        assertEquals(2, DrcSelection.findLoudEqInstructionsIndexForId(fixture.config, 3));
        assertThrows(MediaException.Malformed.class,
                () -> DrcSelection.findLoudEqInstructionsIndexForId(fixture.config, 9));
    }

    /**
     * Builds the loudness information of the base layout without DRC: a true peak of -20 dB, a program loudness of
     * -20 LKFS in BS.1770-4 and an anchor loudness of -30 LKFS in EBU R 128.
     */
    private static Fixture normalized() {
        var fixture = new Fixture();
        var info = fixture.info(0, ID_FOR_BASE_LAYOUT);
        info.truePeakLevelPresent = 1;
        info.truePeakLevel = -20;
        measure(info, METHOD_DEFINITION_PROGRAM_LOUDNESS, -20, MEASUREMENT_SYSTEM_BS_1770_4);
        measure(info, METHOD_DEFINITION_ANCHOR_LOUDNESS, -30, MEASUREMENT_SYSTEM_EBU_R_128);
        fixture.params.loudnessNormalizationOn = 1;
        return fixture;
    }

    @ParameterizedTest(name = "method {0}")
    @CsvSource({"0,-4", "1,-4", "2,6"})
    @DisplayName("normalizes the program or the anchor loudness to the target loudness")
    void normalization(int method, float gain) {
        var fixture = normalized();
        fixture.params.loudnessMeasurementMethod = method;
        var output = fixture.run();
        assertEquals(gain, output.loudnessNormalizationGainDb, EPSILON);
        assertEquals(-20 + gain, output.outputPeakLevelDb, EPSILON);
        assertEquals(-24, output.outputLoudness, EPSILON);
        assertEquals(85, output.mixingLevel, EPSILON);
    }

    @Test
    @DisplayName("uses the other loudness method when the requested one is missing")
    void normalizationMethodFallback() {
        var fixture = new Fixture();
        var info = fixture.info(0, ID_FOR_BASE_LAYOUT);
        measure(info, METHOD_DEFINITION_PROGRAM_LOUDNESS, -21, MEASUREMENT_SYSTEM_BS_1770_4);
        fixture.params.loudnessNormalizationOn = 1;
        fixture.params.loudnessMeasurementMethod = 2;
        assertEquals(-3, fixture.run().loudnessNormalizationGainDb, EPSILON);
    }

    @Test
    @DisplayName("limits and modifies the normalization gain as requested")
    void normalizationGainLimits() {
        var fixture = normalized();
        fixture.params.loudnessMeasurementMethod = 2;
        fixture.params.loudnessNormalizationGainDbMax = 2;
        fixture.params.loudnessNormalizationGainModificationDb = 1.5f;
        assertEquals(3.5f, fixture.run().loudnessNormalizationGainDb, EPSILON);
    }

    @ParameterizedTest(name = "system {0}")
    @CsvSource({"0,-4", "1,-4", "2,6", "3,-4", "4,-4", "5,-4", "6,-4", "7,-4", "8,6"})
    @DisplayName("prefers the measurement system the playback system requests")
    void measurementSystem(int system, float gain) {
        var fixture = new Fixture();
        var info = fixture.info(0, ID_FOR_BASE_LAYOUT);
        measure(info, METHOD_DEFINITION_PROGRAM_LOUDNESS, -20, MEASUREMENT_SYSTEM_BS_1770_4);
        measure(info, METHOD_DEFINITION_PROGRAM_LOUDNESS, -30, MEASUREMENT_SYSTEM_USER);
        fixture.params.loudnessNormalizationOn = 1;
        fixture.params.loudnessMeasurementSystem = system;
        fixture.params.outputPeakLevelMax = 10;
        assertEquals(gain, fixture.run().loudnessNormalizationGainDb, EPSILON);
    }

    @ParameterizedTest(name = "cut-off {0} Hz, pre-processed loudness {1}")
    @CsvSource({"500,-23,-1", "260,-23,-2.5", "10,-23,-4", "2000,-23,-1", "500,NaN,-2"})
    @DisplayName("adjusts the loudness for the high-pass pre-processing of the playback device")
    void preprocessing(float cutoff, float preprocessed, float gain) {
        var fixture = new Fixture();
        var info = fixture.info(0, ID_FOR_BASE_LAYOUT);
        measure(info, METHOD_DEFINITION_PROGRAM_LOUDNESS, -20, MEASUREMENT_SYSTEM_BS_1770_4);
        if (!Float.isNaN(preprocessed)) {
            measure(info, METHOD_DEFINITION_PROGRAM_LOUDNESS, preprocessed,
                    MEASUREMENT_SYSTEM_BS_1770_4_PRE_PROCESSING);
        }
        fixture.params.loudnessNormalizationOn = 1;
        fixture.params.loudnessMeasurementPreProc = 2;
        fixture.params.deviceCutOffFrequency = (int) cutoff;
        assertEquals(gain, fixture.run().loudnessNormalizationGainDb, EPSILON);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"method,3,0,0", "system,0,9,0", "preprocessing,0,0,3"})
    @DisplayName("refuses a reserved loudness measurement request")
    void reservedLoudnessRequest(String name, int method, int system, int preprocessing) {
        var fixture = normalized();
        fixture.params.loudnessMeasurementMethod = method;
        fixture.params.loudnessMeasurementSystem = system;
        fixture.params.loudnessMeasurementPreProc = preprocessing;
        assertMalformed(fixture, "request is invalid");
    }

    @Test
    @DisplayName("normalizes the album loudness in album mode")
    void albumLoudness() {
        var fixture = normalized();
        var album = fixture.album(0, ID_FOR_BASE_LAYOUT);
        measure(album, METHOD_DEFINITION_PROGRAM_LOUDNESS, -18, MEASUREMENT_SYSTEM_BS_1770_4);
        measure(album, METHOD_DEFINITION_MIXING_LEVEL, 80, MEASUREMENT_SYSTEM_UNKNOWN_OTHER);
        fixture.params.albumMode = 1;
        var output = fixture.run();
        assertEquals(-6, output.loudnessNormalizationGainDb, EPSILON);
        assertEquals(80, output.mixingLevel, EPSILON);
    }

    @Test
    @DisplayName("takes the sample peak when no true peak is given")
    void samplePeak() {
        var fixture = new Fixture();
        var info = fixture.info(0, ID_FOR_BASE_LAYOUT);
        info.samplePeakLevelPresent = 1;
        info.samplePeakLevel = -10;
        measure(info, METHOD_DEFINITION_PROGRAM_LOUDNESS, -20, MEASUREMENT_SYSTEM_BS_1770_4);
        fixture.params.loudnessNormalizationOn = 1;
        var output = fixture.run();
        assertEquals(-14, output.outputPeakLevelDb, EPSILON);
    }

    @Test
    @DisplayName("takes the peak of a DRC set from its limiter peak target")
    void limiterPeak() {
        var fixture = normalized();
        fixture.loudness.loudnessInfo[0].truePeakLevelPresent = 0;
        var set = fixture.set(1, EFFECT_BIT_NIGHT);
        set.limiterPeakTargetPresent = 1;
        set.limiterPeakTarget = -3;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        var output = fixture.run();
        assertArrayEquals(new int[]{1}, selected(output));
        assertEquals(-7, output.outputPeakLevelDb, EPSILON);
    }

    @Test
    @DisplayName("restricts the selection to sets with album loudness in album mode")
    void albumRestriction() {
        var fixture = new Fixture();
        fixture.set(1, EFFECT_BIT_NIGHT);
        fixture.set(2, EFFECT_BIT_NIGHT);
        fixture.album(1, ID_FOR_BASE_LAYOUT);
        fixture.params.albumMode = 1;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        assertArrayEquals(new int[]{1}, selected(fixture.run()));
        var without = new Fixture();
        without.set(1, EFFECT_BIT_NIGHT);
        without.set(2, EFFECT_BIT_NIGHT);
        without.params.albumMode = 1;
        without.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        assertArrayEquals(new int[]{2}, selected(without.run()));
    }

    @Test
    @DisplayName("selects no compression when DRC is on without a request")
    void noRequest() {
        var fixture = new Fixture();
        fixture.set(1, EFFECT_BIT_NIGHT);
        fixture.params.dynamicRangeControlOn = 1;
        assertEquals(0, fixture.run().numSelectedDrcSets);
    }

    @Test
    @DisplayName("falls back to the requested effect after the desired one")
    void effectFallback() {
        var fixture = new Fixture();
        fixture.set(1, EFFECT_BIT_LIMITED);
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT, DrcSelection.EFFECT_TYPE_REQUESTED_LIMITED);
        assertArrayEquals(new int[]{1}, selected(fixture.run()));
    }

    @Test
    @DisplayName("selects no compression on an explicit request for none")
    void noneRequested() {
        var fixture = new Fixture();
        fixture.set(1, EFFECT_BIT_NIGHT);
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NONE);
        assertEquals(0, fixture.run().numSelectedDrcSets);
    }

    @Test
    @DisplayName("refuses an effect request combining none with other effects")
    void noneWithOthers() {
        var fixture = new Fixture();
        fixture.set(1, EFFECT_BIT_NIGHT);
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NONE, DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        fixture.params.numDrcEffectTypeRequestsDesired[0] = 2;
        assertMalformed(fixture, "combines no effect");
    }

    @Test
    @DisplayName("refuses a reserved effect request")
    void reservedEffect() {
        var fixture = new Fixture();
        fixture.set(1, EFFECT_BIT_NIGHT);
        fixture.requestEffects(9);
        assertMalformed(fixture, "effect request 9");
    }

    @Test
    @DisplayName("refuses a reserved feature request")
    void reservedFeature() {
        var fixture = new Fixture();
        fixture.set(1, EFFECT_BIT_NIGHT);
        fixture.requestFeature(3);
        assertMalformed(fixture, "feature request");
    }

    /**
     * Builds two night sets whose peak to average loudness ratios are 8 dB (set 1) and 15 dB (set 2) in the track
     * information and the reverse in the album information.
     */
    private static Fixture dynamicRange() {
        var fixture = new Fixture();
        fixture.set(1, EFFECT_BIT_NIGHT);
        fixture.set(2, EFFECT_BIT_NIGHT);
        float[][] ratios = {{8, 15}, {15, 8}};
        for (var album = 0; album < 2; album++) {
            for (var set = 1; set <= 2; set++) {
                var info = album == 0 ? fixture.info(set, ID_FOR_BASE_LAYOUT) : fixture.album(set, ID_FOR_BASE_LAYOUT);
                measure(info, METHOD_DEFINITION_PROGRAM_LOUDNESS, -20, MEASUREMENT_SYSTEM_BS_1770_4);
                measure(info, METHOD_DEFINITION_PROGRAM_LOUDNESS, -40, MEASUREMENT_SYSTEM_UNKNOWN_OTHER);
                var peak = -20 + ratios[album][set - 1];
                measure(info, METHOD_DEFINITION_SHORT_TERM_LOUDNESS_MAX, peak, MEASUREMENT_SYSTEM_EBU_R_128);
                measure(info, METHOD_DEFINITION_MOMENTARY_LOUDNESS_MAX, peak, MEASUREMENT_SYSTEM_EBU_R_128);
                measure(info, METHOD_DEFINITION_MAX_OF_LOUDNESS_RANGE, peak, MEASUREMENT_SYSTEM_EBU_R_128);
                measure(info, METHOD_DEFINITION_SHORT_TERM_LOUDNESS_MAX, 0, MEASUREMENT_SYSTEM_UNKNOWN_OTHER);
            }
        }
        fixture.requestFeature(DrcSelection.MATCH_DYNAMIC_RANGE);
        return fixture;
    }

    @ParameterizedTest(name = "measurement {0}, album {1}, range {2}: {3} to {4}")
    @CsvSource({"0,0,0,10,0,1", "1,0,0,14,0,2", "2,0,1,7,9,1", "0,0,1,12,20,2", "0,1,0,10,0,2", "0,0,1,30,40,0"})
    @DisplayName("selects the set whose peak to average loudness ratio matches the request")
    void dynamicRangeRequest(int measurement, int album, int range, float value, float max, int expected) {
        var fixture = dynamicRange();
        fixture.params.albumMode = album;
        fixture.params.dynamicRangeMeasurementRequestType[0] = measurement;
        fixture.params.dynamicRangeRequestedIsRange[0] = range;
        if (range == 1) {
            fixture.params.dynamicRangeRequestValueMin[0] = value;
            fixture.params.dynamicRangeRequestValueMax[0] = max;
        } else {
            fixture.params.dynamicRangeRequestValue[0] = value;
        }
        // without a match the request keeps every candidate, and the final selection prefers the one with the
        // fewest effects, no compression
        assertArrayEquals(expected == 0 ? new int[0] : new int[]{expected}, selected(fixture.run()));
    }

    @Test
    @DisplayName("refuses a reserved dynamic range measurement request")
    void reservedDynamicRange() {
        var fixture = dynamicRange();
        fixture.params.dynamicRangeMeasurementRequestType[0] = 3;
        assertMalformed(fixture, "dynamic range request");
    }

    @ParameterizedTest(name = "characteristic {0}")
    @CsvSource({"3,1", "7,2", "1,1", "11,2"})
    @DisplayName("selects the set whose gain characteristics match the requested characteristic")
    void characteristicRequest(int characteristic, int expected) {
        var fixture = new Fixture();
        fixture.set(1, EFFECT_BIT_NIGHT);
        fixture.set(2, EFFECT_BIT_NIGHT).gainSetIndexForChannelGroup[0] = 1;
        fixture.requestFeature(DrcSelection.MATCH_DRC_CHARACTERISTIC);
        fixture.params.drcCharacteristicRequest[0] = characteristic;
        assertArrayEquals(new int[]{expected}, selected(fixture.run()));
    }

    @ParameterizedTest(name = "characteristic {0}")
    @ValueSource(ints = {0, 12})
    @DisplayName("refuses a reserved characteristic request")
    void reservedCharacteristic(int characteristic) {
        var fixture = new Fixture();
        fixture.set(1, EFFECT_BIT_NIGHT);
        fixture.requestFeature(DrcSelection.MATCH_DRC_CHARACTERISTIC);
        fixture.params.drcCharacteristicRequest[0] = characteristic;
        assertMalformed(fixture, "characteristic request");
    }

    @Test
    @DisplayName("refuses a characteristic request without coefficients at the selected location")
    void characteristicWithoutSelectedCoefficients() {
        var fixture = new Fixture();
        fixture.config.drcCoefficientsUniDrc[0].drcLocation = 2;
        fixture.set(1, EFFECT_BIT_NIGHT).drcLocation = 2;
        fixture.requestFeature(DrcSelection.MATCH_DRC_CHARACTERISTIC);
        fixture.params.drcCharacteristicRequest[0] = 3;
        assertMalformed(fixture, "no coefficients at the selected location");
    }

    @Test
    @DisplayName("ignores a characteristic request without coefficients, which leaves no compression preferred")
    void characteristicWithoutCoefficients() {
        var fixture = new Fixture();
        fixture.config.drcCoefficientsUniDrcCount = 0;
        fixture.set(1, EFFECT_BIT_NIGHT);
        fixture.set(2, EFFECT_BIT_NIGHT);
        fixture.requestFeature(DrcSelection.MATCH_DRC_CHARACTERISTIC);
        fixture.params.drcCharacteristicRequest[0] = 3;
        assertArrayEquals(new int[0], selected(fixture.run()));
    }

    @Test
    @DisplayName("prefers the set with the lowest upper target loudness covering the target")
    void targetLoudness() {
        var fixture = new Fixture();
        int[][] ranges = {{1, -20, -40}, {2, -10, -20}, {3, -15, -40}};
        for (var range : ranges) {
            var set = fixture.set(range[0], EFFECT_BIT_NIGHT);
            set.drcSetTargetLoudnessPresent = 1;
            set.drcSetTargetLoudnessValueUpper = range[1];
            set.drcSetTargetLoudnessValueLower = range[2];
        }
        fixture.params.loudnessNormalizationOn = 1;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        var output = fixture.run();
        assertArrayEquals(new int[]{1}, selected(output));
        assertEquals(-4, output.outputPeakLevelDb, EPSILON);
    }

    @Test
    @DisplayName("prefers a set without a target loudness over one whose range covers the target")
    void targetLoudnessAgainstPlainSet() {
        var fixture = new Fixture();
        var ranged = fixture.set(1, EFFECT_BIT_NIGHT);
        ranged.drcSetTargetLoudnessPresent = 1;
        ranged.drcSetTargetLoudnessValueUpper = -20;
        ranged.drcSetTargetLoudnessValueLower = -40;
        fixture.set(2, EFFECT_BIT_NIGHT);
        fixture.params.loudnessNormalizationOn = 1;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        assertArrayEquals(new int[]{2}, selected(fixture.run()));
    }

    @Test
    @DisplayName("matches every target loudness range when loudness normalization is off")
    void targetLoudnessWithoutNormalization() {
        var fixture = new Fixture();
        for (var id = 1; id <= 2; id++) {
            var set = fixture.set(id, EFFECT_BIT_NIGHT);
            set.drcSetTargetLoudnessPresent = 1;
            set.drcSetTargetLoudnessValueUpper = -10 * id;
            set.drcSetTargetLoudnessValueLower = -60;
        }
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        var output = fixture.run();
        assertArrayEquals(new int[]{2}, selected(output));
        assertEquals(0, output.outputPeakLevelDb, EPSILON);
    }

    @Test
    @DisplayName("refuses more than 32 selection candidates")
    void tooManyCandidates() {
        var fixture = new Fixture();
        for (var id = 1; id <= 33; id++) {
            fixture.set(id, EFFECT_BIT_NIGHT);
        }
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        assertMalformed(fixture, "too many selection candidates");
    }

    @Test
    @DisplayName("costs equalizers and downmix coefficients of the subband domain, which ignores the band limit")
    void subbandDomain() {
        var output = costlyEqualizer(true).run(1);
        assertArrayEquals(new int[]{1}, selected(output), "the subband equalizer fits beside the set");
        assertEquals(1, output.selectedEqSetIds[0]);
        var bands = new Fixture();
        bands.set(2, EFFECT_BIT_NIGHT);
        bands.set(3, EFFECT_BIT_NIGHT).gainSetIndexForChannelGroup[0] = 1;
        bands.params.numBandsSupported = 2;
        bands.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        assertArrayEquals(new int[]{3}, selected(bands.run(1)));
        var downmix = new Fixture();
        downmix.downmix(1, 1, 1, 0.5f, 0.5f);
        downmix.params.targetConfigRequestType = 1;
        downmix.params.targetLayoutRequested = 1;
        assertEquals(1, downmix.run(1).activeDownmixId);
    }

    @Test
    @DisplayName("counts the channels of dependent sets after a first selection")
    void dependentChannelsOnReselection() {
        var fixture = new Fixture();
        var base = fixture.set(1, EFFECT_BIT_GENERAL_COMPR);
        base.noIndependentUse = 1;
        base.drcApplyToDownmix = 1;
        var dependent = fixture.set(2, EFFECT_BIT_NOISY);
        dependent.dependsOnDrcSetPresent = 1;
        dependent.dependsOnDrcSet = 1;
        var other = fixture.set(4, EFFECT_BIT_GENERAL_COMPR);
        other.noIndependentUse = 1;
        var limited = fixture.set(3, EFFECT_BIT_LIMITED);
        limited.dependsOnDrcSetPresent = 1;
        limited.dependsOnDrcSet = 4;
        limited.drcApplyToDownmix = 1;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NOISY);
        assertArrayEquals(new int[]{1, 2}, selected(fixture.run()));
        fixture.selection.process(fixture.config, fixture.loudness);
        assertEquals(2, fixture.selection.output.targetChannelCount);
        assertArrayEquals(new int[]{1, 2}, selected(fixture.selection.output));
    }

    /**
     * Builds the base layout loudness information of a program loudness of -40 LKFS, which loudness normalization
     * raises by 16 dB, with a true peak of -10 dB; the requests ask for a night set.
     */
    private static Fixture loud() {
        var fixture = new Fixture();
        var info = fixture.info(0, ID_FOR_BASE_LAYOUT);
        info.truePeakLevelPresent = 1;
        info.truePeakLevel = -10;
        measure(info, METHOD_DEFINITION_PROGRAM_LOUDNESS, -40, MEASUREMENT_SYSTEM_BS_1770_4);
        fixture.params.loudnessNormalizationOn = 1;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        return fixture;
    }

    @Test
    @DisplayName("lowers the normalization gain of the candidates with the lowest peak when every one would clip")
    void everyCandidateClips() {
        var fixture = loud();
        var set = fixture.set(1, EFFECT_BIT_NIGHT);
        set.limiterPeakTargetPresent = 1;
        set.limiterPeakTarget = -10.5f;
        fixture.set(2, EFFECT_BIT_NIGHT);
        var output = fixture.run();
        assertArrayEquals(new int[]{1}, selected(output));
        assertEquals(10.5f, output.loudnessNormalizationGainDb, EPSILON);
        assertEquals(0, output.outputPeakLevelDb, EPSILON);
    }

    @Test
    @DisplayName("keeps a clipping set whose target loudness range covers the target and whose peak is explicit")
    void explicitPeakWithTargetLoudness() {
        var fixture = loud();
        var set = fixture.set(3, EFFECT_BIT_NIGHT);
        set.limiterPeakTargetPresent = 1;
        set.limiterPeakTarget = -3;
        set.drcSetTargetLoudnessPresent = 1;
        set.drcSetTargetLoudnessValueUpper = -20;
        set.drcSetTargetLoudnessValueLower = -40;
        fixture.set(2, EFFECT_BIT_NIGHT);
        var output = fixture.run();
        assertArrayEquals(new int[]{3}, selected(output));
        assertEquals(16, output.loudnessNormalizationGainDb, EPSILON);
        assertEquals(13, output.outputPeakLevelDb, EPSILON);
    }

    @Test
    @DisplayName("takes the limiter peak of a set that requires an equalizer for every equalizer it may run with")
    void limiterPeakWithEqualizers() {
        var fixture = new Fixture();
        fixture.config.sampleRate = 48000 * 32;
        var set = fixture.set(1, EFFECT_BIT_NIGHT);
        set.requiresEq = 1;
        set.limiterPeakTargetPresent = 1;
        set.limiterPeakTarget = -6;
        fixture.eq(1, 1, new int[]{ID_FOR_BASE_LAYOUT}, new int[]{1});
        fixture.eq(2, 1, new int[]{ID_FOR_ANY_DOWNMIX}, new int[]{ID_FOR_ANY_DRC});
        fixture.eq(3, 1, new int[]{ID_FOR_BASE_LAYOUT}, new int[]{5});
        fixture.eq(4, 1, new int[]{7}, new int[]{1});
        var costly = fixture.eq(5, 1, new int[]{ID_FOR_BASE_LAYOUT}, new int[]{ID_FOR_ANY_DRC});
        costly.eqSetComplexityLevel = 15;
        costly.tdFilterCascadePresent = 1;
        fixture.params.eqSetPurposeRequest = 1;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        var output = fixture.run();
        assertArrayEquals(new int[]{1}, selected(output));
        assertEquals(1, output.selectedEqSetIds[0]);
    }

    @Test
    @DisplayName("skips a set requiring an equalizer when the loudness information names one the decoder lacks")
    void requiredEqualizerMissing() {
        var fixture = new Fixture();
        var info = fixture.info(0, ID_FOR_BASE_LAYOUT);
        info.eqSetId = 9;
        measure(info, METHOD_DEFINITION_PROGRAM_LOUDNESS, -20, MEASUREMENT_SYSTEM_BS_1770_4);
        fixture.params.loudnessNormalizationOn = 1;
        fixture.set(1, EFFECT_BIT_NIGHT).requiresEq = 1;
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        assertEquals(0, fixture.run().numSelectedDrcSets);
    }

    @Test
    @DisplayName("adds the last ducking set to a selection under DRC")
    void duckingUnderDrc() {
        var fixture = new Fixture();
        fixture.set(1, EFFECT_BIT_NIGHT);
        fixture.set(2, EFFECT_BIT_DUCK_OTHER);
        fixture.set(3, EFFECT_BIT_DUCK_SELF);
        fixture.requestEffects(DrcSelection.EFFECT_TYPE_REQUESTED_NIGHT);
        assertArrayEquals(new int[]{1, 3}, selected(fixture.run()));
    }

    @ParameterizedTest(name = "album downmix {0}")
    @CsvSource({"0,-3", "5,-1"})
    @DisplayName("adjusts the album loudness for high-pass pre-processing, or the track loudness without album data")
    void albumPreprocessing(int albumDownmix, float gain) {
        var fixture = new Fixture();
        var album = fixture.album(0, albumDownmix);
        measure(album, METHOD_DEFINITION_PROGRAM_LOUDNESS, -18, MEASUREMENT_SYSTEM_BS_1770_4);
        measure(album, METHOD_DEFINITION_PROGRAM_LOUDNESS, -21, MEASUREMENT_SYSTEM_BS_1770_4_PRE_PROCESSING);
        var track = fixture.info(0, ID_FOR_BASE_LAYOUT);
        measure(track, METHOD_DEFINITION_PROGRAM_LOUDNESS, -20, MEASUREMENT_SYSTEM_BS_1770_4);
        measure(track, METHOD_DEFINITION_PROGRAM_LOUDNESS, -23, MEASUREMENT_SYSTEM_BS_1770_4_PRE_PROCESSING);
        fixture.params.albumMode = 1;
        fixture.params.loudnessNormalizationOn = 1;
        fixture.params.loudnessMeasurementPreProc = 2;
        assertEquals(gain, fixture.run().loudnessNormalizationGainDb, EPSILON);
    }

    @Test
    @DisplayName("falls back to loudness information for any downmix, ignoring information without an overall loudness")
    void anyDownmixLoudness() {
        var fixture = new Fixture();
        var partial = fixture.info(0, ID_FOR_BASE_LAYOUT);
        measure(partial, METHOD_DEFINITION_MIXING_LEVEL, 70, MEASUREMENT_SYSTEM_UNKNOWN_OTHER);
        var any = fixture.info(0, ID_FOR_ANY_DOWNMIX);
        measure(any, METHOD_DEFINITION_PROGRAM_LOUDNESS, -22, MEASUREMENT_SYSTEM_BS_1770_4);
        measure(any, METHOD_DEFINITION_ANCHOR_LOUDNESS, -26, MEASUREMENT_SYSTEM_USER);
        measure(any, METHOD_DEFINITION_ANCHOR_LOUDNESS, -30, MEASUREMENT_SYSTEM_EBU_R_128);
        measure(any, METHOD_DEFINITION_MIXING_LEVEL, 80, MEASUREMENT_SYSTEM_UNKNOWN_OTHER);
        fixture.params.loudnessNormalizationOn = 1;
        fixture.params.loudnessMeasurementMethod = 2;
        fixture.params.outputPeakLevelMax = 10;
        var output = fixture.run();
        // the default measurement system ranks the user measurement above EBU R 128
        assertEquals(-24 + 26, output.loudnessNormalizationGainDb, EPSILON);
        assertEquals(80, output.mixingLevel, EPSILON);
    }
}
