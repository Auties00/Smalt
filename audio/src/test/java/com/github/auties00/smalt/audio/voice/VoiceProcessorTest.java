package com.github.auties00.smalt.audio.voice;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public behavior of {@link VoiceProcessor}: options and argument validation, the
 * equivalence of the float and 16-bit entry points, render bursts,
 * echo cancellation, noise suppression and gain control quality on the scenario suite (with and
 * without the Vector API kernels), hostile sample values, and zero steady-state allocation with
 * every stage enabled.
 */
@DisplayName("Voice processor")
class VoiceProcessorTest {
    private static final int SECONDS = 20;

    /** The stage combinations the robustness and allocation tests run. */
    static final Map<String, VoiceProcessorOptions> STAGES = Map.of(
            "voip", VoiceProcessorOptions.forVoip(),
            "voip with gain control", VoiceProcessorOptions.forVoip().toBuilder()
                    .gainControl(GainControl.ADAPTIVE_DIGITAL).build(),
            "mobile with gain control", VoiceProcessorOptions.forVoip().toBuilder()
                    .echoCancellation(EchoCancellation.MOBILE)
                    .comfortNoise(true).gainControl(GainControl.ADAPTIVE_ANALOG).build(),
            "48 kHz stereo voip with level control", VoiceProcessorOptions.forVoip().toBuilder().sampleRate(48000)
                    .captureChannels(2).renderChannels(2).multichannelCapture(true).multichannelRender(true)
                    .levelControl(LevelControlOptions.builder().inputVolumeController(true).build()).build(),
            "32 kHz voip with managed analog gain control", VoiceProcessorOptions.forVoip().toBuilder()
                    .sampleRate(32000).gainControl(GainControl.MANAGED_ANALOG)
                    .analogGainController(AnalogGainControllerOptions.builder()
                            .clippingPrediction(ClippingPrediction.ADAPTIVE_STEP_PEAK).build()).build(),
            "8 kHz stereo mobile", VoiceProcessorOptions.forVoip().toBuilder().sampleRate(8000).captureChannels(2)
                    .echoCancellation(EchoCancellation.MOBILE).build(),
            "16 kHz level control at its largest gains", VoiceProcessorOptions.forVoip().toBuilder()
                    .echoCancellation(EchoCancellation.OFF).noiseSuppression(NoiseSuppression.OFF)
                    .levelControl(LevelControlOptions.builder().fixedGainDb(49.9f).headroomDb(0).maxGainDb(100)
                            .initialGainDb(100).maxGainChangeDbPerSecond(1e6f).maxOutputNoiseLevelDbfs(0).build())
                    .build());

    static Stream<Arguments> volumeControllers() {
        var voip = VoiceProcessorOptions.forVoip();
        return Stream.of(
                Arguments.of("managed analog", voip.toBuilder().gainControl(GainControl.MANAGED_ANALOG).build()),
                Arguments.of("input volume controller", voip.toBuilder().sampleRate(48000).captureChannels(2)
                        .levelControl(LevelControlOptions.builder().inputVolumeController(true).build()).build()));
    }

    /** Runs interleaved streams through a processor, feeding the recommended volume back, and returns the output. */
    static short[] run(VoiceProcessor processor, RateScenarios.Streams streams, int initialLevel, int[] finalLevel) {
        var n = streams.frameSamples();
        var render = new short[n * streams.renderChannels()];
        var capture = new short[n * streams.captureChannels()];
        var out = new short[streams.capture().length];
        var level = initialLevel;
        for (var f = 0; f < streams.frames(); f++) {
            System.arraycopy(streams.render(), f * render.length, render, 0, render.length);
            System.arraycopy(streams.capture(), f * capture.length, capture, 0, capture.length);
            processor.processRender(render, 0);
            processor.setAnalogLevel(level);
            processor.processCapture(capture, 0, capture, 0);
            level = processor.recommendedAnalogLevel();
            System.arraycopy(capture, 0, out, f * capture.length, capture.length);
        }
        if (finalLevel != null) {
            finalLevel[0] = level;
        }
        return out;
    }

    static Stream<Arguments> stageCombinations() {
        return STAGES.keySet().stream().sorted()
                .flatMap(name -> Stream.of(Arguments.of(name, false), Arguments.of(name, true)));
    }

    /** Runs a scenario through a processor on the 16-bit entry points and returns the output in [-1, 1). */
    static double[] process(VoiceProcessor processor, VoiceScenarios.Scenario scenario) {
        return process(processor, scenario, 0);
    }

    /** Runs a scenario with the given stream delay and returns the output in [-1, 1). */
    static double[] process(VoiceProcessor processor, VoiceScenarios.Scenario scenario, int delayMs) {
        processor.setStreamDelayMs(delayMs);
        var out = new double[scenario.capture().length];
        var render = new short[VoiceScenarios.FRAME];
        var capture = new short[VoiceScenarios.FRAME];
        for (var f = 0; f < scenario.frames(); f++) {
            System.arraycopy(scenario.render(), f * VoiceScenarios.FRAME, render, 0, VoiceScenarios.FRAME);
            System.arraycopy(scenario.capture(), f * VoiceScenarios.FRAME, capture, 0, VoiceScenarios.FRAME);
            processor.processRender(render, 0);
            processor.processCapture(capture, 0, capture, 0);
            for (var i = 0; i < VoiceScenarios.FRAME; i++) {
                out[f * VoiceScenarios.FRAME + i] = capture[i] / 32768.0;
            }
        }
        return out;
    }

    /** The RMS level, in dB, of a range of samples in [-1, 1). */
    static double levelDb(double[] samples, int from, int to) {
        double energy = 0;
        for (var i = from; i < to; i++) {
            energy += samples[i] * samples[i];
        }
        return 10 * Math.log10(Math.max(energy / (to - from), 1e-20));
    }

    /** The RMS level, in dB relative to full scale, of a range of 16-bit samples. */
    static double levelDb(short[] samples, int from, int to) {
        double energy = 0;
        for (var i = from; i < to; i++) {
            var v = samples[i] / 32768.0;
            energy += v * v;
        }
        return 10 * Math.log10(Math.max(energy / (to - from), 1e-20));
    }

    /** The median ERLE over half-second windows after 5 s where the echo is active and the near end silent. */
    static double medianErle(VoiceScenarios.Scenario scenario, double[] out) {
        var window = 8000;
        var values = new ArrayList<Double>();
        for (var a = 5 * VoiceScenarios.RATE; a + window <= out.length; a += window) {
            double echo = 0;
            double near = 0;
            double residual = 0;
            for (var i = a; i < a + window; i++) {
                echo += scenario.echo()[i] * scenario.echo()[i];
                if (scenario.near() != null) {
                    near += scenario.near()[i] * scenario.near()[i];
                }
                residual += out[i] * out[i];
            }
            if (echo / window < 1e-5 || near / window > 1e-6) {
                continue;
            }
            values.add(10 * Math.log10(echo / Math.max(residual, 1e-12)));
        }
        var sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return sorted.length == 0 ? Double.NaN : sorted[sorted.length / 2];
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("forVoip and defaults are 16 kHz with the standard echo canceller, noise suppression HIGH and no gain control")
        void forVoip() {
            var options = VoiceProcessorOptions.forVoip();
            assertEquals(options, VoiceProcessorOptions.defaults());
            assertEquals(16000, options.sampleRate());
            assertEquals(EchoCancellation.STANDARD, options.echoCancellation());
            assertEquals(NoiseSuppression.HIGH, options.noiseSuppression());
            assertEquals(GainControl.OFF, options.gainControl());
            assertEquals(3, options.targetLevelDbfs());
            assertEquals(9, options.compressionGainDb());
            assertTrue(options.limiter());
            assertEquals(MobileEchoRoute.SPEAKERPHONE, options.mobileEchoRoute());
            assertFalse(options.comfortNoise());
            assertEquals(160, new VoiceProcessor(options).frameSamples());
        }

        @Test
        @DisplayName("withers change one setting and leave the receiver untouched")
        void withers() {
            var base = VoiceProcessorOptions.forVoip();
            var changed = base.toBuilder().gainControl(GainControl.FIXED_DIGITAL).targetLevelDbfs(12)
                    .compressionGainDb(30).limiter(false).echoCancellation(EchoCancellation.MOBILE)
                    .mobileEchoRoute(MobileEchoRoute.EARPIECE).comfortNoise(true)
                    .noiseSuppression(NoiseSuppression.LOW).build();
            assertEquals(GainControl.OFF, base.gainControl());
            assertEquals(GainControl.FIXED_DIGITAL, changed.gainControl());
            assertEquals(12, changed.targetLevelDbfs());
            assertEquals(30, changed.compressionGainDb());
            assertFalse(changed.limiter());
            assertEquals(EchoCancellation.MOBILE, changed.echoCancellation());
            assertEquals(MobileEchoRoute.EARPIECE, changed.mobileEchoRoute());
            assertTrue(changed.comfortNoise());
            assertEquals(NoiseSuppression.LOW, changed.noiseSuppression());
            assertEquals(changed, changed.toBuilder().build());
            assertEquals(changed.hashCode(), changed.toBuilder().build().hashCode());
            assertNotEquals(base, changed);
        }

        @ParameterizedTest(name = "target {0} dBFS, compression {1} dB")
        @CsvSource({"-1, 9", "32, 9", "3, -1", "3, 91"})
        @DisplayName("rejects gain control settings out of range")
        void gainControlRanges(int target, int compression) {
            assertThrows(IllegalArgumentException.class,
                    () -> VoiceProcessorOptions.forVoip().toBuilder().targetLevelDbfs(target)
                            .compressionGainDb(compression).build());
        }

        @Test
        @DisplayName("rejects null settings and null options")
        void nulls() {
            var options = VoiceProcessorOptions.forVoip();
            assertThrows(NullPointerException.class, () -> options.toBuilder().noiseSuppression(null).build());
            assertThrows(NullPointerException.class, () -> options.toBuilder().echoCancellation(null).build());
            assertThrows(NullPointerException.class, () -> options.toBuilder().gainControl(null).build());
            assertThrows(NullPointerException.class, () -> options.toBuilder().mobileEchoRoute(null).build());
            assertThrows(NullPointerException.class, () -> new VoiceProcessor(null));
        }

        @Test
        @DisplayName("rejects microphone levels outside [0, 255] and recommends the reported level without the analog controller")
        void analogLevel() {
            var processor = new VoiceProcessor(VoiceProcessorOptions.forVoip().toBuilder()
                    .gainControl(GainControl.ADAPTIVE_DIGITAL).build());
            assertThrows(IllegalArgumentException.class, () -> processor.setAnalogLevel(-1));
            assertThrows(IllegalArgumentException.class, () -> processor.setAnalogLevel(256));
            assertEquals(255, processor.recommendedAnalogLevel());
            processor.setAnalogLevel(77);
            assertEquals(77, processor.recommendedAnalogLevel());
        }

        @Test
        @DisplayName("the analog gain controller rejects capture frames until a microphone level is reported")
        void analogLevelRequired() {
            var processor = new VoiceProcessor(VoiceProcessorOptions.forVoip().toBuilder()
                    .gainControl(GainControl.ADAPTIVE_ANALOG).build());
            var frame = new short[processor.frameSamples()];
            assertEquals(255, processor.recommendedAnalogLevel());
            assertThrows(IllegalStateException.class, () -> processor.processCapture(frame, 0, frame, 0));
            assertThrows(IllegalStateException.class,
                    () -> processor.processCapture(new float[frame.length], 0, new float[frame.length], 0));
            processor.setAnalogLevel(100);
            processor.processCapture(frame, 0, frame, 0);
            assertTrue(processor.recommendedAnalogLevel() > 0, "recommended level after the first frame");
        }

        @ParameterizedTest(name = "{0} Hz")
        @ValueSource(ints = {8000, 16000, 32000, 48000})
        @DisplayName("frames hold 10 ms per channel at every rate")
        void rates(int rate) {
            var options = VoiceProcessorOptions.forVoip().toBuilder().sampleRate(rate).captureChannels(2)
                    .renderChannels(3).build();
            assertEquals(rate, options.sampleRate());
            assertEquals(rate / 100, new VoiceProcessor(options).frameSamples());
        }

        @ParameterizedTest(name = "rate {0}, capture {1}, render {2}")
        @CsvSource({"44100, 1, 1", "0, 1, 1", "96000, 1, 1", "16000, 0, 1", "16000, 9, 1", "16000, 1, 0",
                "16000, 1, 9"})
        @DisplayName("rejects unsupported rates and channel counts")
        void layoutRanges(int rate, int capture, int render) {
            assertThrows(IllegalArgumentException.class, () -> VoiceProcessorOptions.forVoip().toBuilder()
                    .sampleRate(rate).captureChannels(capture).renderChannels(render).build());
        }

        @Test
        @DisplayName("the level controller is off by default, set by levelControl and cleared by noLevelControl")
        void levelControl() {
            var options = VoiceProcessorOptions.forVoip();
            assertTrue(options.levelControl().isEmpty());
            var level = LevelControlOptions.builder().fixedGainDb(6).adaptiveDigital(false).build();
            var with = options.toBuilder().levelControl(level).build();
            assertEquals(level, with.levelControl().orElseThrow());
            assertEquals(options, with.toBuilder().noLevelControl().build());
            assertThrows(NullPointerException.class, () -> options.toBuilder().levelControl(null));
            assertThrows(IllegalArgumentException.class, () -> options.toBuilder().gainControl(GainControl.ADAPTIVE_DIGITAL)
                    .levelControl(LevelControlOptions.builder().inputVolumeController(true).build()).build());
        }

        @Test
        @DisplayName("level controller and analog gain controller settings reject values out of range")
        void controllerRanges() {
            assertThrows(IllegalArgumentException.class, () -> LevelControlOptions.builder().fixedGainDb(-1).build());
            assertThrows(IllegalArgumentException.class, () -> LevelControlOptions.builder().fixedGainDb(50).build());
            assertThrows(IllegalArgumentException.class, () -> LevelControlOptions.builder().headroomDb(-1).build());
            assertThrows(IllegalArgumentException.class,
                    () -> LevelControlOptions.builder().maxGainChangeDbPerSecond(0).build());
            for (var invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
                assertThrows(IllegalArgumentException.class,
                        () -> LevelControlOptions.builder().headroomDb(invalid).build());
                assertThrows(IllegalArgumentException.class,
                        () -> LevelControlOptions.builder().maxGainDb(invalid).build());
                assertThrows(IllegalArgumentException.class,
                        () -> LevelControlOptions.builder().initialGainDb(invalid).build());
                assertThrows(IllegalArgumentException.class,
                        () -> LevelControlOptions.builder().maxGainChangeDbPerSecond(invalid).build());
                assertThrows(IllegalArgumentException.class,
                        () -> LevelControlOptions.builder().maxOutputNoiseLevelDbfs(invalid).build());
            }
            assertThrows(IllegalArgumentException.class, () -> LevelControlOptions.builder().maxGainDb(100.5f).build());
            assertThrows(IllegalArgumentException.class,
                    () -> LevelControlOptions.builder().initialGainDb(101).build());
            assertEquals(100, LevelControlOptions.builder().maxGainDb(100).initialGainDb(100).build().initialGainDb());
            assertThrows(IllegalArgumentException.class,
                    () -> AnalogGainControllerOptions.builder().predictionWindowFrames(1001).build());
            assertThrows(IllegalArgumentException.class,
                    () -> AnalogGainControllerOptions.builder().referenceWindowFrames(Integer.MAX_VALUE).build());
            assertThrows(IllegalArgumentException.class,
                    () -> AnalogGainControllerOptions.builder().referenceWindowDelayFrames(1001).build());
            var longest = AnalogGainControllerOptions.builder().predictionWindowFrames(1000).referenceWindowFrames(1000)
                    .referenceWindowDelayFrames(1000).clippingPrediction(ClippingPrediction.ADAPTIVE_STEP_PEAK).build();
            var processor = new VoiceProcessor(VoiceProcessorOptions.forVoip().toBuilder()
                    .gainControl(GainControl.MANAGED_ANALOG).analogGainController(longest).build());
            processor.setAnalogLevel(128);
            var frame = new short[processor.frameSamples()];
            for (var f = 0; f < 2100; f++) {
                Arrays.fill(frame, (short) (f % 2 == 0 ? 30000 : -30000));
                processor.processCapture(frame, 0, frame, 0);
            }
            assertThrows(IllegalArgumentException.class,
                    () -> AnalogGainControllerOptions.builder().clippedLevelMin(255).build());
            assertThrows(IllegalArgumentException.class,
                    () -> AnalogGainControllerOptions.builder().clippedLevelStep(0).build());
            assertThrows(IllegalArgumentException.class,
                    () -> AnalogGainControllerOptions.builder().clippedRatioThreshold(1).build());
            assertThrows(IllegalArgumentException.class,
                    () -> AnalogGainControllerOptions.builder().clippedWaitFrames(0).build());
            assertThrows(IllegalArgumentException.class, () -> AnalogGainControllerOptions.builder()
                    .predictionWindowFrames(6).referenceWindowFrames(1).referenceWindowDelayFrames(5).build());
            assertThrows(NullPointerException.class,
                    () -> AnalogGainControllerOptions.builder().clippingPrediction(null));
            var analog = AnalogGainControllerOptions.builder().clippedLevelMin(90).digitalAdaptive(false)
                    .clippingPrediction(ClippingPrediction.FIXED_STEP_PEAK).build();
            assertEquals(analog, analog.toBuilder().build());
            assertEquals(analog, VoiceProcessorOptions.forVoip().toBuilder().analogGainController(analog).build()
                    .analogGainController());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.voice.VoiceProcessorTest#volumeControllers")
        @DisplayName("every volume controller rejects capture frames until a microphone level is reported")
        void volumeLevelRequired(String name, VoiceProcessorOptions options) {
            var processor = new VoiceProcessor(options);
            var frame = new short[processor.frameSamples() * options.captureChannels()];
            assertThrows(IllegalStateException.class, () -> processor.processCapture(frame, 0, frame, 0));
            processor.setAnalogLevel(100);
            processor.processCapture(frame, 0, frame, 0);
        }
    }

    @Nested
    @DisplayName("frames")
    class Frames {
        @Test
        @DisplayName("reject arrays too short for a frame at the offset")
        void bounds() {
            var processor = new VoiceProcessor(VoiceProcessorOptions.forVoip());
            assertThrows(IndexOutOfBoundsException.class, () -> processor.processRender(new float[159], 0));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> processor.processCapture(new float[200], 41, new float[200], 41));
            assertThrows(IndexOutOfBoundsException.class, () -> processor.processRender(new short[160], -1));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> processor.processCapture(new short[160], 0, new short[100], 0));
            assertThrows(NullPointerException.class, () -> processor.processCapture((float[]) null, 0, null, 0));
        }

        @Test
        @DisplayName("pass the capture through unchanged with every stage disabled")
        void passThrough() {
            var processor = new VoiceProcessor(VoiceProcessorOptions.forVoip().toBuilder()
                    .echoCancellation(EchoCancellation.OFF)
                    .noiseSuppression(NoiseSuppression.OFF).build());
            var random = new SplittableRandom(1);
            for (var f = 0; f < 50; f++) {
                var frame = new short[160];
                for (var i = 0; i < 160; i++) {
                    frame[i] = (short) random.nextInt(Short.MIN_VALUE, Short.MAX_VALUE + 1);
                }
                var out = new short[160];
                processor.processRender(frame, 0);
                processor.processCapture(frame, 0, out, 0);
                assertArrayEquals(frame, out);
            }
        }

        @Test
        @DisplayName("the 16-bit entry points equal the float ones with rounding")
        void shortEqualsFloat() {
            var scenario = VoiceScenarios.doubleTalk(8, 60);
            var shorts = new VoiceProcessor(VoiceProcessorOptions.forVoip(), false, true);
            var floats = new VoiceProcessor(VoiceProcessorOptions.forVoip(), false, true);
            shorts.setStreamDelayMs(0);
            floats.setStreamDelayMs(0);
            var render = new short[160];
            var capture = new short[160];
            var renderF = new float[160];
            var captureF = new float[160];
            for (var f = 0; f < scenario.frames(); f++) {
                for (var i = 0; i < 160; i++) {
                    render[i] = scenario.render()[f * 160 + i];
                    capture[i] = scenario.capture()[f * 160 + i];
                    renderF[i] = render[i] / 32768f;
                    captureF[i] = capture[i] / 32768f;
                }
                shorts.processRender(render, 0);
                floats.processRender(renderF, 0);
                shorts.processCapture(capture, 0, capture, 0);
                floats.processCapture(captureF, 0, captureF, 0);
                for (var i = 0; i < 160; i++) {
                    var expected = (short) Math.clamp(Math.round(captureF[i] * 32768f), Short.MIN_VALUE, Short.MAX_VALUE);
                    assertEquals(expected, capture[i], "frame " + f + " sample " + i);
                }
            }
        }

        @Test
        @DisplayName("the float entry point into another array equals the in-place one and leaves its input untouched")
        void floatIntoOutput() {
            var scenario = VoiceScenarios.doubleTalk(4, 60);
            var inPlace = new VoiceProcessor(VoiceProcessorOptions.forVoip(), false, true);
            var separate = new VoiceProcessor(VoiceProcessorOptions.forVoip(), false, true);
            inPlace.setStreamDelayMs(0);
            separate.setStreamDelayMs(0);
            var render = new float[160];
            var frame = new float[160];
            var input = new float[170];
            var output = new float[180];
            for (var f = 0; f < scenario.frames(); f++) {
                for (var i = 0; i < 160; i++) {
                    render[i] = scenario.render()[f * 160 + i] / 32768f;
                    frame[i] = scenario.capture()[f * 160 + i] / 32768f;
                    input[7 + i] = frame[i];
                }
                var original = input.clone();
                inPlace.processRender(render, 0);
                separate.processRender(render, 0);
                inPlace.processCapture(frame, 0, frame, 0);
                separate.processCapture(input, 7, output, 13);
                assertArrayEquals(original, input, "frame " + f);
                assertArrayEquals(frame, Arrays.copyOfRange(output, 13, 173), "frame " + f);
            }
            assertThrows(IndexOutOfBoundsException.class,
                    () -> separate.processCapture(new float[160], 0, new float[159], 0));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> separate.processCapture(new float[160], 1, new float[160], 0));
        }

        @Test
        @DisplayName("clamp the stream delay to [0, 500] ms")
        void streamDelay() {
            var scenario = VoiceScenarios.farEndOnly(4, 100);
            assertArrayEquals(process(new VoiceProcessor(VoiceProcessorOptions.forVoip(), false, true), scenario, 500),
                    process(new VoiceProcessor(VoiceProcessorOptions.forVoip(), false, true), scenario, 10_000));
            assertArrayEquals(process(new VoiceProcessor(VoiceProcessorOptions.forVoip(), false, true), scenario, 0),
                    process(new VoiceProcessor(VoiceProcessorOptions.forVoip(), false, true), scenario, -5));
        }
    }

    @Nested
    @DisplayName("quality")
    class Quality {
        @ParameterizedTest(name = "vectorize={0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("cancels at least 40 dB of echo at every delay, across a path change and under drift")
        void echoReturnLossEnhancement(boolean vectorize) {
            Skip.unless(!vectorize || Simd.ENABLED, "simd:disabled", "Vector API not enabled");
            for (var scenario : VoiceScenarios.suite(SECONDS)) {
                if (scenario.name().equals("noise only") || scenario.name().equals("silence")) {
                    continue;
                }
                var erle = medianErle(scenario, process(new VoiceProcessor(VoiceProcessorOptions.forVoip(), vectorize, true), scenario));
                assertTrue(erle >= 40, () -> scenario.name() + ": median ERLE " + erle + " dB");
            }
        }

        @Test
        @DisplayName("the vector kernels cancel the echo within 1 dB of the scalar ones")
        void vectorMatchesScalar() {
            Skip.unlessVector(Simd.ENABLED);
            for (var delay : new int[]{20, 250, 500}) {
                var scenario = VoiceScenarios.farEndOnly(SECONDS, delay);
                var scalar = medianErle(scenario, process(new VoiceProcessor(VoiceProcessorOptions.forVoip(), false, true), scenario));
                var vector = medianErle(scenario, process(new VoiceProcessor(VoiceProcessorOptions.forVoip(), true, true), scenario));
                assertEquals(scalar, vector, 1.0, "far-end " + delay + " ms");
            }
        }

        @Test
        @DisplayName("the adaptive digital gain controller raises a quiet talker by at least 4 dB and keeps a loud one unclipped")
        void adaptiveDigitalGainControl() {
            var scenario = VoiceScenarios.levelChanges(SECONDS);
            var options = VoiceProcessorOptions.forVoip().toBuilder().echoCancellation(EchoCancellation.OFF)
                    .noiseSuppression(NoiseSuppression.OFF).gainControl(GainControl.ADAPTIVE_DIGITAL).build();
            var out = process(new VoiceProcessor(options), scenario);
            var third = scenario.capture().length / 3;
            var quietGain = levelDb(out, third / 2, third) - levelDb(scenario.capture(), third / 2, third);
            assertTrue(quietGain >= 4, "gain on the quiet talker " + quietGain + " dB");
            var loudPeak = 0.0;
            for (var i = third; i < 2 * third; i++) {
                loudPeak = Math.max(loudPeak, Math.abs(out[i]));
            }
            assertTrue(loudPeak < 32767 / 32768.0, "loud talker peak " + loudPeak);
        }

        @ParameterizedTest(name = "{0} dB")
        @ValueSource(ints = {0, 9, 20})
        @DisplayName("the fixed digital gain controller applies its compression gain to a quiet talker within 1 dB")
        void fixedDigitalGainControl(int compressionGainDb) {
            var scenario = VoiceScenarios.levelChanges(SECONDS);
            var options = VoiceProcessorOptions.forVoip().toBuilder().echoCancellation(EchoCancellation.OFF)
                    .noiseSuppression(NoiseSuppression.OFF).gainControl(GainControl.FIXED_DIGITAL)
                    .compressionGainDb(compressionGainDb).build();
            var out = process(new VoiceProcessor(options), scenario);
            var third = scenario.capture().length / 3;
            var gain = levelDb(out, third / 2, third) - levelDb(scenario.capture(), third / 2, third);
            assertEquals(compressionGainDb, gain, 1.0, "gain on the quiet talker");
        }

        @Test
        @DisplayName("the analog gain controller recommends a higher microphone level for a quiet talker")
        void adaptiveAnalogGainControl() {
            var scenario = VoiceScenarios.levelChanges(SECONDS);
            var processor = new VoiceProcessor(VoiceProcessorOptions.forVoip().toBuilder()
                    .gainControl(GainControl.ADAPTIVE_ANALOG).build());
            var level = 100;
            var render = new short[VoiceScenarios.FRAME];
            var capture = new short[VoiceScenarios.FRAME];
            var quietFrames = scenario.frames() / 3;
            for (var f = 0; f < quietFrames; f++) {
                System.arraycopy(scenario.capture(), f * VoiceScenarios.FRAME, capture, 0, VoiceScenarios.FRAME);
                processor.processRender(render, 0);
                processor.setAnalogLevel(level);
                processor.processCapture(capture, 0, capture, 0);
                level = processor.recommendedAnalogLevel();
            }
            assertTrue(level > 100, "recommended level after the quiet talker " + level);
        }

        @ParameterizedTest(name = "{0} Hz, {1} capture x {2} render, multichannel {3}")
        @CsvSource({"8000, 1, 1, false", "32000, 1, 1, false", "48000, 1, 1, false", "48000, 2, 2, true",
                "32000, 1, 2, false", "16000, 2, 2, true"})
        @DisplayName("the standard echo canceller cancels the echo at every rate and channel layout")
        void echoReturnLossEnhancementLayouts(int rate, int captureChannels, int renderChannels, boolean multichannel) {
            var streams = RateScenarios.farEnd(SECONDS, rate, renderChannels, captureChannels, true);
            var options = VoiceProcessorOptions.forVoip().toBuilder().sampleRate(rate).captureChannels(captureChannels)
                    .renderChannels(renderChannels).multichannelCapture(multichannel).multichannelRender(multichannel)
                    .build();
            var out = run(new VoiceProcessor(options, false, true), streams, 128, null);
            var window = rate / 2 * captureChannels;
            var values = new ArrayList<Double>();
            for (var a = 5 * rate * captureChannels; a + window <= out.length; a += window) {
                var in = levelDb(streams.capture(), a, a + window);
                if (in < -50) {
                    continue;
                }
                values.add(in - levelDb(out, a, a + window));
            }
            var sorted = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            var erle = sorted[sorted.length / 2];
            assertTrue(erle >= 25, () -> "median ERLE " + erle + " dB over " + Arrays.toString(sorted));
        }

        @Test
        @DisplayName("the level controller raises a quiet talker by at least 6 dB at 48 kHz")
        void levelControl() {
            var streams = RateScenarios.levelChanges(SECONDS, 48000, 1, 1);
            var options = VoiceProcessorOptions.forVoip().toBuilder().sampleRate(48000)
                    .echoCancellation(EchoCancellation.OFF).noiseSuppression(NoiseSuppression.OFF)
                    .levelControl(LevelControlOptions.defaults()).build();
            var out = run(new VoiceProcessor(options), streams, 128, null);
            var third = out.length / 3;
            var gain = levelDb(out, third / 2, third) - levelDb(streams.capture(), third / 2, third);
            assertTrue(gain >= 6, "gain on the quiet talker " + gain + " dB");
        }

        @Test
        @DisplayName("the managed analog gain controller recommends a higher microphone level for a quiet talker")
        void managedAnalogGainControl() {
            var streams = RateScenarios.levelChanges(15, 16000, 1, 1);
            var options = VoiceProcessorOptions.forVoip().toBuilder().gainControl(GainControl.MANAGED_ANALOG).build();
            var quiet = new RateScenarios.Streams(streams.name(), streams.rate(), 1, 1,
                    Arrays.copyOf(streams.render(), streams.render().length / 3),
                    Arrays.copyOf(streams.capture(), streams.capture().length / 3));
            var level = new int[1];
            run(new VoiceProcessor(options), quiet, 100, level);
            assertTrue(level[0] > 100, "recommended level after the quiet talker " + level[0]);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.audio.voice.VoiceProcessorTest#volumeControllers")
        @DisplayName("every volume controller lowers the microphone level when the capture clips")
        void clippingLowersLevel(String name, VoiceProcessorOptions options) {
            var streams = RateScenarios.clipping(SECONDS, options.sampleRate(), 1, options.captureChannels());
            var level = new int[1];
            run(new VoiceProcessor(options), streams, 200, level);
            assertTrue(level[0] < 200, "recommended level after clipping " + level[0]);
        }

        @Test
        @DisplayName("the mobile echo canceller removes at least 10 dB of echo with the delay reported")
        void mobileEchoCancellation() {
            var scenario = VoiceScenarios.farEndOnly(SECONDS, 100);
            var options = VoiceProcessorOptions.forVoip().toBuilder().echoCancellation(EchoCancellation.MOBILE).build();
            var erle = medianErle(scenario, process(new VoiceProcessor(options), scenario, 100));
            assertTrue(erle >= 10, "median ERLE " + erle + " dB");
        }

        @Test
        @DisplayName("keeps the near-end talker during double talk")
        void doubleTalk() {
            var scenario = VoiceScenarios.doubleTalk(SECONDS, 60);
            var out = process(new VoiceProcessor(VoiceProcessorOptions.forVoip()), scenario);
            var from = scenario.nearFrom() + 2 * VoiceScenarios.RATE;
            var near = scenario.near();
            var lag = 0;
            var best = Double.NEGATIVE_INFINITY;
            for (var candidate = 0; candidate <= 400; candidate++) {
                double cross = 0;
                for (var i = from; i < from + 3 * VoiceScenarios.RATE; i++) {
                    cross += near[i] * out[i + candidate];
                }
                if (cross > best) {
                    best = cross;
                    lag = candidate;
                }
            }
            double cross = 0;
            double nearEnergy = 0;
            double outEnergy = 0;
            for (var i = from; i < out.length - lag; i++) {
                cross += near[i] * out[i + lag];
                nearEnergy += near[i] * near[i];
                outEnergy += out[i + lag] * out[i + lag];
            }
            var correlation = cross / Math.sqrt(nearEnergy * outEnergy);
            var level = 10 * Math.log10(outEnergy / nearEnergy);
            assertTrue(correlation > 0.8, "correlation with the near-end talker " + correlation);
            assertTrue(level > -4, "near-end level change " + level + " dB");
        }

        @Test
        @DisplayName("attenuates stationary noise by at least 10 dB")
        void noiseSuppression() {
            var scenario = VoiceScenarios.noiseOnly(SECONDS);
            var out = process(new VoiceProcessor(VoiceProcessorOptions.forVoip()), scenario);
            double in = 0;
            double residual = 0;
            for (var i = 5 * VoiceScenarios.RATE; i < out.length; i++) {
                in += scenario.noise()[i] * scenario.noise()[i];
                residual += out[i] * out[i];
            }
            var attenuation = 10 * Math.log10(in / residual);
            assertTrue(attenuation >= 10, "noise attenuation " + attenuation + " dB");
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        @ParameterizedTest(name = "{0}, vectorize={1}")
        @MethodSource("com.github.auties00.smalt.audio.voice.VoiceProcessorTest#stageCombinations")
        @DisplayName("NaN, infinite and full-scale samples keep the output finite and in range")
        void hostileSamples(String name, boolean vectorize) {
            Skip.unless(!vectorize || Simd.ENABLED, "simd:disabled", "Vector API not enabled");
            var options = STAGES.get(name);
            var processor = new VoiceProcessor(options, vectorize, true);
            processor.setAnalogLevel(128);
            var random = new SplittableRandom(5);
            var render = new float[processor.frameSamples() * options.renderChannels()];
            var capture = new float[processor.frameSamples() * options.captureChannels()];
            for (var f = 0; f < 3000; f++) {
                for (var i = 0; i < Math.max(render.length, capture.length); i++) {
                    var kind = (f / 100) % 6;
                    var value = switch (kind) {
                        case 0 -> Float.NaN;
                        case 1 -> random.nextBoolean() ? Float.POSITIVE_INFINITY : Float.NEGATIVE_INFINITY;
                        case 2 -> (i / 8) % 2 == 0 ? 1.f : -1.f;
                        case 3 -> Float.MIN_VALUE * random.nextInt(100);
                        default -> (float) (random.nextDouble() * 2 - 1);
                    };
                    if (i < render.length) {
                        render[i] = value;
                    }
                    if (i < capture.length) {
                        capture[i] = kind == 0 ? Float.NaN : kind < 4 ? value : (float) (random.nextDouble() * 4 - 2);
                    }
                }
                processor.processRender(render, 0);
                processor.processCapture(capture, 0, capture, 0);
                for (var i = 0; i < capture.length; i++) {
                    var v = capture[i];
                    assertTrue(Float.isFinite(v) && v >= -1.f && v <= 1.f, "frame " + f + " sample " + i + ": " + v);
                }
            }
        }

        @Test
        @DisplayName("tolerates bursts of render and capture frames")
        void bursts() {
            var processor = new VoiceProcessor(VoiceProcessorOptions.forVoip());
            var frame = new float[160];
            var random = new SplittableRandom(6);
            for (var round = 0; round < 50; round++) {
                for (var i = 0; i < round % 7 * 30; i++) {
                    for (var k = 0; k < 160; k++) {
                        frame[k] = (float) random.nextGaussian() * 0.1f;
                    }
                    processor.processRender(frame, 0);
                }
                for (var i = 0; i < round % 5 * 20; i++) {
                    for (var k = 0; k < 160; k++) {
                        frame[k] = (float) random.nextGaussian() * 0.1f;
                    }
                    processor.processCapture(frame, 0, frame, 0);
                    for (var k = 0; k < 160; k++) {
                        assertTrue(Float.isFinite(frame[k]));
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("allocation")
    class Allocation {
        @ParameterizedTest(name = "{0}, vectorize={1}")
        @MethodSource("com.github.auties00.smalt.audio.voice.VoiceProcessorTest#stageCombinations")
        @DisplayName("allocates nothing per frame in steady state")
        void steadyState(String name, boolean vectorize) throws Exception {
            Skip.unless(!vectorize || Simd.ENABLED, "simd:disabled", "Vector API not enabled");
            Object threads;
            Method allocated;
            try {
                threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null);
                allocated = Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes");
            } catch (ReflectiveOperationException | RuntimeException exception) {
                throw Skip.now("env:allocation-counter", "thread allocation counter unavailable: " + exception);
            }
            var options = STAGES.get(name);
            var streams = RateScenarios.doubleTalk(SECONDS, options.sampleRate(), options.renderChannels(),
                    options.captureChannels(), true);
            var processor = new VoiceProcessor(options, vectorize, true);
            processor.setAnalogLevel(128);
            processor.setStreamDelayMs(0);
            var render = new short[processor.frameSamples() * options.renderChannels()];
            var capture = new short[processor.frameSamples() * options.captureChannels()];
            // The reflective counter boxes its result: measure that overhead with nothing in between. Once the
            // counter's call is compiled the box may be elided, so a pass can come out below the overhead; a
            // per-frame allocation would add at least 16 bytes per frame
            var overhead = Long.MAX_VALUE;
            for (var i = 0; i < 10; i++) {
                var before = (long) allocated.invoke(threads);
                overhead = Math.min(overhead, (long) allocated.invoke(threads) - before);
            }
            long bytes = 0;
            for (var pass = 0; pass < 6; pass++) {
                var before = (long) allocated.invoke(threads);
                for (var f = 0; f < streams.frames(); f++) {
                    System.arraycopy(streams.render(), f * render.length, render, 0, render.length);
                    System.arraycopy(streams.capture(), f * capture.length, capture, 0, capture.length);
                    processor.processRender(render, 0);
                    processor.processCapture(capture, 0, capture, 0);
                }
                bytes = (long) allocated.invoke(threads) - before - overhead;
                if (pass >= 3 && bytes <= 0) {
                    break;
                }
            }
            var allocatedBytes = bytes;
            assertTrue(allocatedBytes <= 0, () -> allocatedBytes + " bytes allocated over " + streams.frames()
                    + " frames");
        }
    }
}
