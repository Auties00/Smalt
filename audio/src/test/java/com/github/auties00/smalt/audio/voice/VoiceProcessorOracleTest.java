package com.github.auties00.smalt.audio.voice;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scalar voice processor against the bit-exact reference, webrtc-audio-processing 2.1 built for
 * WebAssembly and run by Chicory ({@link WasmApmOracle}), on the scenario suite of
 * {@link VoiceScenarios}: far-end only at 20, 100, 250 and 500 ms, double talk, an echo-path
 * change, +-100 ppm clock drift, noise only, silence, clipping, digital silence between talk
 * spurts and a talker whose level steps. Every echo canceller, noise suppression level and gain
 * controller mode is covered, alone and in the voice call combinations. Without flushing tiny
 * values, every output sample must equal the reference's, its SHA-256 included, and so must the
 * recommended microphone level of the analog gain controller; with flushing, as the public
 * constructor configures it, every sample must be within 1e-10 of full scale of the reference's.
 * The SHA-256 of the reference output must equal the golden digest in {@code oracle-goldens.txt},
 * which pins the oracle and the signal generator; {@link OracleGoldens} regenerates the file. The
 * mobile echo canceller gets a 40 ms stream delay before every frame, and the analog gain
 * controller the level it recommended after the previous frame, starting from 128.
 */
@DisplayName("Voice processor against the WebRTC APM oracle")
class VoiceProcessorOracleTest {
    static final int SECONDS = 15;
    static final int MOBILE_DELAY_MS = 40;
    static final int INITIAL_ANALOG_LEVEL = 128;
    private static final Map<String, String> GOLDENS = loadGoldens();
    private static List<VoiceScenarios.Scenario> suite;

    private static synchronized List<VoiceScenarios.Scenario> suite() {
        if (suite == null) {
            var scenarios = new ArrayList<>(VoiceScenarios.suite(SECONDS));
            scenarios.add(VoiceScenarios.levelChanges(SECONDS));
            suite = List.copyOf(scenarios);
        }
        return suite;
    }

    static VoiceScenarios.Scenario scenarioOf(String name) {
        return suite().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    /** One verified case: its test group, the options and the scenario name. */
    record Case(String group, VoiceProcessorOptions options, String scenario) {
    }

    private static final List<String> SUITE_NAMES = List.of("far-end 20 ms", "far-end 100 ms", "far-end 250 ms",
            "far-end 500 ms", "double talk", "echo-path change", "drift +100 ppm", "drift -100 ppm", "noise only",
            "silence", "clipping", "render pauses");

    private static final VoiceProcessorOptions NOTHING = VoiceProcessorOptions.defaults()
            .toBuilder().echoCancellation(EchoCancellation.OFF).noiseSuppression(NoiseSuppression.OFF).build();

    /** Every case of the suite, in a stable order. */
    static List<Case> cases() {
        var cases = new ArrayList<Case>();
        for (var name : SUITE_NAMES) {
            cases.add(new Case("voip", VoiceProcessorOptions.forVoip(), name));
        }
        for (var level : List.of(NoiseSuppression.LOW, NoiseSuppression.MODERATE, NoiseSuppression.HIGH,
                NoiseSuppression.VERY_HIGH)) {
            for (var name : List.of("noise only", "double talk")) {
                cases.add(new Case("ns", NOTHING.toBuilder().noiseSuppression(level).build(), name));
            }
        }
        for (var name : List.of("far-end 250 ms", "echo-path change", "clipping")) {
            cases.add(new Case("aec", NOTHING.toBuilder().echoCancellation(EchoCancellation.STANDARD).build(), name));
        }
        var voipAgc = VoiceProcessorOptions.forVoip().toBuilder().gainControl(GainControl.ADAPTIVE_DIGITAL).build();
        for (var name : SUITE_NAMES) {
            cases.add(new Case("voip+agc", voipAgc, name));
        }
        cases.add(new Case("voip+agc", voipAgc, "level changes"));
        for (var mode : List.of(GainControl.ADAPTIVE_DIGITAL, GainControl.FIXED_DIGITAL, GainControl.ADAPTIVE_ANALOG)) {
            for (var tuning : List.of(new int[]{3, 9, 1}, new int[]{12, 25, 0}, new int[]{0, 0, 1})) {
                var options = NOTHING.toBuilder().gainControl(mode).targetLevelDbfs(tuning[0])
                        .compressionGainDb(tuning[1]).limiter(tuning[2] == 1).build();
                for (var name : List.of("level changes", "double talk", "clipping", "silence")) {
                    cases.add(new Case("agc", options, name));
                }
            }
        }
        var mobile = VoiceProcessorOptions.forVoip().toBuilder().echoCancellation(EchoCancellation.MOBILE).build();
        for (var name : SUITE_NAMES) {
            cases.add(new Case("aecm", mobile, name));
        }
        for (var name : List.of("far-end 100 ms", "double talk", "clipping")) {
            cases.add(new Case("aecm", NOTHING.toBuilder().echoCancellation(EchoCancellation.MOBILE).build(), name));
            cases.add(new Case("aecm", mobile.toBuilder().gainControl(GainControl.ADAPTIVE_DIGITAL).build(), name));
        }
        return cases;
    }

    private static Stream<Arguments> group(String group) {
        return cases().stream().filter(c -> c.group().equals(group))
                .map(c -> Arguments.of(describe(c.options()), c.scenario()));
    }

    static Stream<Arguments> voip() {
        return group("voip");
    }

    static Stream<Arguments> noiseSuppression() {
        return group("ns");
    }

    static Stream<Arguments> echoCancellation() {
        return group("aec");
    }

    static Stream<Arguments> voipWithGainControl() {
        return group("voip+agc");
    }

    static Stream<Arguments> gainControl() {
        return group("agc");
    }

    static Stream<Arguments> mobileEchoCancellation() {
        return group("aecm");
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("voip")
    @DisplayName("voice call options (high-pass, AEC3, noise suppression HIGH) are bit-exact")
    void voipOptions(String options, String name) {
        verify("voip", options, name);
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("noiseSuppression")
    @DisplayName("noise suppression alone is bit-exact at every level")
    void noiseSuppressionOnly(String options, String name) {
        verify("ns", options, name);
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("echoCancellation")
    @DisplayName("echo cancellation alone is bit-exact")
    void echoCancellationOnly(String options, String name) {
        verify("aec", options, name);
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("voipWithGainControl")
    @DisplayName("voice call options with the adaptive digital gain controller are bit-exact")
    void voipWithGainControl(String options, String name) {
        verify("voip+agc", options, name);
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("gainControl")
    @DisplayName("the gain controller alone is bit-exact in every mode and tuning")
    void gainControlOnly(String options, String name) {
        verify("agc", options, name);
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("mobileEchoCancellation")
    @DisplayName("the mobile echo canceller is bit-exact alone, with noise suppression and with gain control")
    void mobileEchoCancellationCases(String options, String name) {
        verify("aecm", options, name);
    }

    private static void verify(String group, String description, String name) {
        var match = cases().stream()
                .filter(c -> c.group().equals(group) && c.scenario().equals(name) && describe(c.options()).equals(description))
                .findFirst().orElseThrow();
        var scenario = scenarioOf(name);
        var result = run(match.options(), scenario);
        assertEquals(0.0, result.maxDifference(), () -> "max |java - oracle| = " + result.maxDifference()
                + " (identical samples " + result.identical() + " of " + result.samples() + ")");
        assertEquals(result.oracleDigest(), result.javaDigest(), "digest of the Java output");
        assertEquals(0, result.levelMismatches(), "frames whose recommended analog level differs");
        assertTrue(result.flushedMaxDifference() <= 1e-10,
                () -> "max |java - oracle| with flushing = " + result.flushedMaxDifference());
        var key = key(match.options(), scenario);
        assertEquals(GOLDENS.get(key), result.oracleDigest(), "golden digest of the oracle output for " + key);
    }

    record Result(double maxDifference, long identical, long samples, String javaDigest, String oracleDigest,
                  double flushedMaxDifference, int levelMismatches) {
    }

    static String describe(VoiceProcessorOptions options) {
        var echo = switch (options.echoCancellation()) {
            case OFF -> "noaec";
            case STANDARD -> "aec3";
            case MOBILE -> "aecm-" + options.mobileEchoRoute() + (options.comfortNoise() ? "-cng" : "");
        };
        var description = echo + "|" + options.noiseSuppression();
        if (options.gainControl() != GainControl.OFF) {
            description += "|agc-" + options.gainControl() + "-" + options.targetLevelDbfs() + "-"
                    + options.compressionGainDb() + "-" + (options.limiter() ? "limiter" : "nolimiter");
        }
        return description;
    }

    static String key(VoiceProcessorOptions options, VoiceScenarios.Scenario scenario) {
        return describe(options) + "|" + scenario.name() + "|" + scenario.frames();
    }

    static Result run(VoiceProcessorOptions options, VoiceScenarios.Scenario scenario) {
        var mobile = options.echoCancellation() == EchoCancellation.MOBILE;
        var analog = options.gainControl() == GainControl.ADAPTIVE_ANALOG;
        var delay = mobile ? MOBILE_DELAY_MS : 0;
        var java = new VoiceProcessor(options, false, false);
        java.setStreamDelayMs(delay);
        var flushed = new VoiceProcessor(options, false, true);
        flushed.setStreamDelayMs(delay);
        var flushedMaxDifference = 0.0;
        var javaDigest = sha256();
        var oracleDigest = sha256();
        var maxDifference = 0.0;
        long identical = 0;
        var levelMismatches = 0;
        try (var oracle = WasmApmOracle.of(options)) {
            oracle.setStreamDelayMs(delay);
            var level = INITIAL_ANALOG_LEVEL;
            var flushedLevel = INITIAL_ANALOG_LEVEL;
            var render = new float[VoiceScenarios.FRAME];
            var javaFrame = new float[VoiceScenarios.FRAME];
            var oracleFrame = new float[VoiceScenarios.FRAME];
            var flushedFrame = new float[VoiceScenarios.FRAME];
            var bytes = new byte[4 * VoiceScenarios.FRAME];
            for (var f = 0; f < scenario.frames(); f++) {
                for (var i = 0; i < VoiceScenarios.FRAME; i++) {
                    render[i] = scenario.render()[f * VoiceScenarios.FRAME + i] / 32768f;
                    javaFrame[i] = scenario.capture()[f * VoiceScenarios.FRAME + i] / 32768f;
                    oracleFrame[i] = javaFrame[i];
                    flushedFrame[i] = javaFrame[i];
                }
                java.processRender(render, 0);
                flushed.processRender(render, 0);
                oracle.processRender(render, 0);
                if (mobile) {
                    oracle.setStreamDelayMs(delay);
                }
                if (analog) {
                    java.setAnalogLevel(level);
                    flushed.setAnalogLevel(flushedLevel);
                    oracle.setAnalogLevel(level);
                }
                java.processCapture(javaFrame, 0, javaFrame, 0);
                flushed.processCapture(flushedFrame, 0, flushedFrame, 0);
                oracle.processCapture(oracleFrame, 0);
                if (analog) {
                    level = oracle.recommendedAnalogLevel();
                    if (java.recommendedAnalogLevel() != level) {
                        levelMismatches++;
                    }
                    flushedLevel = flushed.recommendedAnalogLevel();
                }
                for (var i = 0; i < VoiceScenarios.FRAME; i++) {
                    maxDifference = Math.max(maxDifference, Math.abs((double) javaFrame[i] - oracleFrame[i]));
                    flushedMaxDifference = Math.max(flushedMaxDifference, Math.abs((double) flushedFrame[i] - oracleFrame[i]));
                    if (Float.floatToRawIntBits(javaFrame[i]) == Float.floatToRawIntBits(oracleFrame[i])) {
                        identical++;
                    }
                }
                javaDigest.update(littleEndian(javaFrame, bytes));
                oracleDigest.update(littleEndian(oracleFrame, bytes));
            }
        }
        return new Result(maxDifference, identical, (long) scenario.frames() * VoiceScenarios.FRAME,
                HexFormat.of().formatHex(javaDigest.digest()), HexFormat.of().formatHex(oracleDigest.digest()),
                flushedMaxDifference, levelMismatches);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] littleEndian(float[] frame, byte[] bytes) {
        for (var i = 0; i < frame.length; i++) {
            var bits = Float.floatToRawIntBits(frame[i]);
            bytes[4 * i] = (byte) bits;
            bytes[4 * i + 1] = (byte) (bits >>> 8);
            bytes[4 * i + 2] = (byte) (bits >>> 16);
            bytes[4 * i + 3] = (byte) (bits >>> 24);
        }
        return bytes;
    }

    private static Map<String, String> loadGoldens() {
        var goldens = new LinkedHashMap<String, String>();
        try (var in = VoiceProcessorOracleTest.class.getResourceAsStream("oracle-goldens.txt")) {
            if (in == null) {
                return goldens;
            }
            var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var separator = line.lastIndexOf(' ');
                goldens.put(line.substring(0, separator), line.substring(separator + 1));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return goldens;
    }
}
