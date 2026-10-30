package com.github.auties00.smalt.audio.voice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scalar voice processor against the bit-exact reference ({@link WasmApmOracle}) at 8, 32 and 48
 * kHz and with several capture and render channels, on the scenarios of {@link RateScenarios}: every
 * stage that runs on bands (noise suppression, the legacy gain controller in each mode, both echo
 * cancellers), the level controller and the managed analog gain controller. As in
 * {@link VoiceProcessorOracleTest}, every sample must equal the reference's without flushing and be
 * within 1e-10 of it with flushing, the recommended microphone volume must match, and the reference
 * output must match the golden digest of {@code oracle-goldens-multirate.txt}.
 */
@DisplayName("Voice processor against the WebRTC APM oracle at every rate and channel layout")
class MultirateOracleTest {
    static final int SECONDS = 6;
    static final int MANAGED_SECONDS = 15;
    private static final Map<String, String> GOLDENS = OracleHarness.loadGoldens("oracle-goldens-multirate.txt");

    private static final VoiceProcessorOptions NOTHING = VoiceProcessorOptions.defaults().toBuilder()
            .echoCancellation(EchoCancellation.OFF).noiseSuppression(NoiseSuppression.OFF).build();

    /** One verified case: its test group, the options and the scenario. */
    record Case(String group, VoiceProcessorOptions options, Supplier<RateScenarios.Streams> streams, String name) {
    }

    private static Case of(String group, VoiceProcessorOptions options, String name,
                           Supplier<RateScenarios.Streams> streams) {
        return new Case(group, options, streams, name);
    }

    private static VoiceProcessorOptions layout(VoiceProcessorOptions base, int rate, int capture, int render) {
        return base.toBuilder().sampleRate(rate).captureChannels(capture).renderChannels(render).build();
    }

    /** Every case, in a stable order. */
    static List<Case> cases() {
        var cases = new ArrayList<Case>();
        for (var rate : new int[]{8000, 32000, 48000}) {
            cases.add(of("passthrough", layout(NOTHING, rate, 1, 1), "double talk",
                    () -> RateScenarios.doubleTalk(SECONDS, rate, 1, 1, false)));
            for (var level : List.of(NoiseSuppression.LOW, NoiseSuppression.VERY_HIGH)) {
                var ns = NOTHING.toBuilder().noiseSuppression(level).build();
                cases.add(of("ns", layout(ns, rate, 1, 1), "noise only",
                        () -> RateScenarios.noiseOnly(SECONDS, rate, 1, 1)));
                cases.add(of("ns", layout(ns, rate, 2, 1), "double talk",
                        () -> RateScenarios.doubleTalk(SECONDS, rate, 1, 2, false)));
            }
            for (var mode : List.of(GainControl.ADAPTIVE_DIGITAL, GainControl.FIXED_DIGITAL,
                    GainControl.ADAPTIVE_ANALOG)) {
                var agc = NOTHING.toBuilder().gainControl(mode).targetLevelDbfs(6).compressionGainDb(12).build();
                cases.add(of("agc", layout(agc, rate, 1, 1), "level changes",
                        () -> RateScenarios.levelChanges(SECONDS, rate, 1, 1)));
                cases.add(of("agc", layout(agc, rate, 2, 2), "double talk",
                        () -> RateScenarios.doubleTalk(SECONDS, rate, 2, 2, true)));
            }
            var mobile = VoiceProcessorOptions.forVoip().toBuilder().echoCancellation(EchoCancellation.MOBILE).build();
            cases.add(of("aecm", layout(mobile, rate, 1, 1), "far-end",
                    () -> RateScenarios.farEnd(SECONDS, rate, 1, 1, false)));
            cases.add(of("aecm", layout(mobile, rate, 2, 2), "double talk",
                    () -> RateScenarios.doubleTalk(SECONDS, rate, 2, 2, true)));
            cases.add(of("aecm", layout(mobile.toBuilder().multichannelRender(true).build(), rate, 2, 2), "far-end",
                    () -> RateScenarios.farEnd(SECONDS, rate, 2, 2, true)));
            cases.add(of("aecm", layout(mobile.toBuilder().gainControl(GainControl.ADAPTIVE_DIGITAL).build(), rate, 1, 1),
                    "double talk", () -> RateScenarios.doubleTalk(SECONDS, rate, 1, 1, false)));
        }
        var voip = VoiceProcessorOptions.forVoip();
        for (var rate : new int[]{8000, 32000, 48000}) {
            cases.add(of("aec3", layout(voip, rate, 1, 1), "far-end",
                    () -> RateScenarios.farEnd(SECONDS, rate, 1, 1, false)));
            cases.add(of("aec3", layout(voip, rate, 1, 1), "double talk",
                    () -> RateScenarios.doubleTalk(SECONDS, rate, 1, 1, false)));
        }
        cases.add(of("aec3", layout(voip.toBuilder().gainControl(GainControl.ADAPTIVE_DIGITAL).build(), 48000, 1, 1),
                "double talk", () -> RateScenarios.doubleTalk(SECONDS, 48000, 1, 1, false)));
        cases.add(of("aec3", layout(voip, 16000, 2, 1), "double talk",
                () -> RateScenarios.doubleTalk(SECONDS, 16000, 1, 2, false)));
        cases.add(of("aec3", layout(voip.toBuilder().multichannelCapture(true).build(), 16000, 2, 1), "double talk",
                () -> RateScenarios.doubleTalk(SECONDS, 16000, 1, 2, false)));
        cases.add(of("aec3", layout(voip, 32000, 1, 2), "far-end",
                () -> RateScenarios.farEnd(SECONDS, 32000, 2, 1, true)));
        cases.add(of("aec3", layout(voip.toBuilder().multichannelRender(true).build(), 16000, 1, 2), "far-end",
                () -> RateScenarios.farEnd(SECONDS, 16000, 2, 1, true)));
        cases.add(of("aec3", layout(voip.toBuilder().multichannelRender(true).build(), 16000, 1, 2), "double talk",
                () -> RateScenarios.doubleTalk(SECONDS, 16000, 2, 1, false)));
        cases.add(of("aec3", layout(voip.toBuilder().multichannelRender(true).multichannelCapture(true).build(), 48000,
                2, 2), "far-end", () -> RateScenarios.farEnd(SECONDS, 48000, 2, 2, true)));
        cases.add(of("ns", layout(NOTHING.toBuilder().noiseSuppression(NoiseSuppression.HIGH).build(), 16000, 3, 1),
                "noise only", () -> RateScenarios.noiseOnly(SECONDS, 16000, 1, 3)));
        cases.add(of("agc", layout(NOTHING.toBuilder().gainControl(GainControl.ADAPTIVE_ANALOG).build(), 16000, 2, 1),
                "clipping", () -> RateScenarios.clipping(SECONDS, 16000, 1, 2)));
        var adaptive = NOTHING.toBuilder().levelControl(LevelControlOptions.defaults()).build();
        var fixed = NOTHING.toBuilder().levelControl(LevelControlOptions.builder().adaptiveDigital(false)
                .fixedGainDb(12).build()).build();
        var tuned = NOTHING.toBuilder().levelControl(LevelControlOptions.builder().fixedGainDb(3).headroomDb(2)
                .maxGainDb(30).initialGainDb(8).maxGainChangeDbPerSecond(12).maxOutputNoiseLevelDbfs(-60)
                .build()).build();
        var volume = NOTHING.toBuilder().levelControl(LevelControlOptions.builder().inputVolumeController(true)
                .build()).build();
        var volumeOnly = NOTHING.toBuilder().levelControl(LevelControlOptions.builder().adaptiveDigital(false)
                .inputVolumeController(true).build()).build();
        cases.add(of("agc2", layout(fixed, 16000, 1, 1), "level changes",
                () -> RateScenarios.levelChanges(SECONDS, 16000, 1, 1)));
        cases.add(of("agc2", layout(fixed, 48000, 2, 1), "clipping",
                () -> RateScenarios.clipping(SECONDS, 48000, 1, 2)));
        for (var rate : new int[]{8000, 16000, 32000, 48000}) {
            cases.add(of("agc2", layout(adaptive, rate, 1, 1), "level changes",
                    () -> RateScenarios.levelChanges(SECONDS, rate, 1, 1)));
            cases.add(of("agc2", layout(volume, rate, 1, 1), "level changes",
                    () -> RateScenarios.levelChanges(SECONDS, rate, 1, 1)));
        }
        cases.add(of("agc2", layout(adaptive, 48000, 2, 2), "double talk",
                () -> RateScenarios.doubleTalk(SECONDS, 48000, 2, 2, true)));
        cases.add(of("agc2", layout(adaptive, 16000, 1, 1), "noise only",
                () -> RateScenarios.noiseOnly(SECONDS, 16000, 1, 1)));
        cases.add(of("agc2", layout(tuned, 32000, 1, 1), "level changes",
                () -> RateScenarios.levelChanges(SECONDS, 32000, 1, 1)));
        cases.add(of("agc2", layout(volume, 16000, 2, 1), "clipping",
                () -> RateScenarios.clipping(SECONDS, 16000, 1, 2)));
        cases.add(of("agc2", layout(volumeOnly, 48000, 2, 1), "double talk",
                () -> RateScenarios.doubleTalk(SECONDS, 48000, 1, 2, false)));
        cases.add(of("agc2", layout(voip.toBuilder().levelControl(LevelControlOptions.builder()
                .inputVolumeController(true).build()).build(), 48000, 1, 1), "double talk",
                () -> RateScenarios.doubleTalk(SECONDS, 48000, 1, 1, false)));
        cases.add(of("agc2", layout(voip.toBuilder().levelControl(LevelControlOptions.defaults())
                .multichannelCapture(true).build(), 32000, 2, 2), "double talk",
                () -> RateScenarios.doubleTalk(SECONDS, 32000, 2, 2, true)));
        var managed = NOTHING.toBuilder().gainControl(GainControl.MANAGED_ANALOG).build();
        for (var rate : new int[]{8000, 16000, 32000, 48000}) {
            cases.add(of("agc1", layout(managed, rate, 1, 1), "level changes",
                    () -> RateScenarios.levelChanges(MANAGED_SECONDS, rate, 1, 1)));
        }
        cases.add(of("agc1", layout(managed, 16000, 1, 1), "double talk",
                () -> RateScenarios.doubleTalk(MANAGED_SECONDS, 16000, 1, 1, false)));
        cases.add(of("agc1", layout(managed, 16000, 2, 1), "clipping",
                () -> RateScenarios.clipping(MANAGED_SECONDS, 16000, 1, 2)));
        cases.add(of("agc1", layout(managed.toBuilder().analogGainController(AnalogGainControllerOptions.builder()
                .digitalAdaptive(false).build()).build(), 16000, 1, 1), "level changes",
                () -> RateScenarios.levelChanges(MANAGED_SECONDS, 16000, 1, 1)));
        for (var prediction : List.of(ClippingPrediction.CLIPPING_EVENT, ClippingPrediction.ADAPTIVE_STEP_PEAK,
                ClippingPrediction.FIXED_STEP_PEAK)) {
            var predicting = managed.toBuilder().analogGainController(AnalogGainControllerOptions.builder()
                    .clippingPrediction(prediction).build()).build();
            cases.add(of("agc1", layout(predicting, 16000, 1, 1), "clipping",
                    () -> RateScenarios.clipping(MANAGED_SECONDS, 16000, 1, 1)));
        }
        cases.add(of("agc1", layout(managed.toBuilder().analogGainController(AnalogGainControllerOptions.builder()
                        .clippedLevelMin(100).clippedLevelStep(30).clippedRatioThreshold(0.05f).clippedWaitFrames(50)
                        .clippingPrediction(ClippingPrediction.ADAPTIVE_STEP_PEAK).predictedStep(false).build())
                .build(), 32000, 2, 1), "clipping", () -> RateScenarios.clipping(MANAGED_SECONDS, 32000, 1, 2)));
        cases.add(of("agc1", layout(voip.toBuilder().gainControl(GainControl.MANAGED_ANALOG).build(), 48000, 2, 2),
                "double talk", () -> RateScenarios.doubleTalk(MANAGED_SECONDS, 48000, 2, 2, true)));
        return cases;
    }

    private static Stream<Arguments> group(String group) {
        var all = cases();
        var arguments = new ArrayList<Arguments>();
        for (var i = 0; i < all.size(); i++) {
            var c = all.get(i);
            if (c.group().equals(group)) {
                arguments.add(Arguments.of(i, OracleHarness.describe(c.options()), c.name()));
            }
        }
        return arguments.stream();
    }

    static Stream<Arguments> passthrough() {
        return group("passthrough");
    }

    static Stream<Arguments> noiseSuppression() {
        return group("ns");
    }

    static Stream<Arguments> gainControl() {
        return group("agc");
    }

    static Stream<Arguments> levelControl() {
        return group("agc2");
    }

    static Stream<Arguments> managedAnalog() {
        return group("agc1");
    }

    @ParameterizedTest(name = "{1}, {2}")
    @MethodSource("managedAnalog")
    @DisplayName("the managed analog gain controller is bit-exact with and without clipping prediction")
    void managedAnalogCases(int index, String options, String name) {
        verify(index);
    }

    @ParameterizedTest(name = "{1}, {2}")
    @MethodSource("levelControl")
    @DisplayName("the level controller and its input volume controller are bit-exact at every rate")
    void levelControlCases(int index, String options, String name) {
        verify(index);
    }

    static Stream<Arguments> mobileEchoCancellation() {
        return group("aecm");
    }

    static Stream<Arguments> standardEchoCancellation() {
        return group("aec3");
    }

    @ParameterizedTest(name = "{1}, {2}")
    @MethodSource("standardEchoCancellation")
    @DisplayName("the standard echo canceller is bit-exact on every band and channel layout")
    void standardEchoCancellationCases(int index, String options, String name) {
        verify(index);
    }

    @ParameterizedTest(name = "{1}, {2}")
    @MethodSource("passthrough")
    @DisplayName("resampling and band splitting alone are bit-exact")
    void passthroughCases(int index, String options, String name) {
        verify(index);
    }

    @ParameterizedTest(name = "{1}, {2}")
    @MethodSource("noiseSuppression")
    @DisplayName("noise suppression is bit-exact on every band and channel")
    void noiseSuppressionCases(int index, String options, String name) {
        verify(index);
    }

    @ParameterizedTest(name = "{1}, {2}")
    @MethodSource("gainControl")
    @DisplayName("the legacy gain controller is bit-exact on every band and channel in every mode")
    void gainControlCases(int index, String options, String name) {
        verify(index);
    }

    @ParameterizedTest(name = "{1}, {2}")
    @MethodSource("mobileEchoCancellation")
    @DisplayName("the mobile echo canceller is bit-exact on every band and channel pair")
    void mobileEchoCancellationCases(int index, String options, String name) {
        verify(index);
    }

    static void verify(int index) {
        var c = cases().get(index);
        var streams = c.streams().get();
        var result = OracleHarness.run(c.options(), streams);
        assertEquals(0.0, result.maxDifference(), () -> "max |java - oracle| = " + result.maxDifference()
                + " (identical samples " + result.identical() + " of " + result.samples() + ", first mismatch in frame "
                + result.firstMismatchFrame() + ")");
        assertEquals(result.oracleDigest(), result.javaDigest(), "digest of the Java output");
        assertEquals(0, result.levelMismatches(), "frames whose recommended analog level differs");
        assertTrue(result.flushedMaxDifference() <= 1e-10,
                () -> "max |java - oracle| with flushing = " + result.flushedMaxDifference());
        var key = OracleHarness.key(c.options(), streams);
        assertEquals(GOLDENS.get(key), result.oracleDigest(), "golden digest of the oracle output for " + key);
    }
}
