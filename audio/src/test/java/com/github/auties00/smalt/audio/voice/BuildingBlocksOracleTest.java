package com.github.auties00.smalt.audio.voice;

import com.github.auties00.smalt.audio.voice.internal.PushSincResampler;
import com.github.auties00.smalt.audio.voice.internal.SplittingFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The resampler and the band splitting filter of the voice processor against the same building
 * blocks of the reference ({@link WasmApmOracle.Resampler}, {@link WasmApmOracle.Splitter}), sample
 * for sample, on speech, full-scale noise beyond the 16-bit range and silence.
 */
@DisplayName("Voice processing building blocks against the WebRTC oracle")
class BuildingBlocksOracleTest {
    private static float[] signal(int length, int rate, long seed) {
        var speech = RateScenarios.speech(length, rate, seed, 120, 1.0);
        var random = new SplittableRandom(seed);
        var out = new float[length];
        for (var i = 0; i < length; i++) {
            var section = (i * 4) / length;
            out[i] = switch (section) {
                case 0 -> (float) (speech[i] * 0.5);
                case 1 -> (float) ((random.nextDouble() * 2 - 1) * 1.3);
                case 2 -> 0.f;
                default -> (float) (speech[i] * 0.05 + 1e-6 * (random.nextDouble() - 0.5));
            };
        }
        return out;
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({"80, 160", "160, 80", "160, 480", "480, 160", "441, 480"})
    @DisplayName("the push sinc resampler equals the reference sample for sample")
    void resampler(int from, int to) {
        var input = signal(from * 300, from * 100, 17 + from);
        var java = new PushSincResampler(from, to);
        var out = new float[to];
        var expected = new float[to];
        var frame = new float[from];
        try (var oracle = new WasmApmOracle.Resampler(from, to)) {
            for (var f = 0; f < 300; f++) {
                System.arraycopy(input, f * from, frame, 0, from);
                java.resample(frame, 0, out, 0);
                oracle.resample(frame, expected);
                for (var i = 0; i < to; i++) {
                    var index = i;
                    var frameIndex = f;
                    assertEquals(Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(out[i]),
                            () -> "frame " + frameIndex + " sample " + index + ": " + expected[index] + " vs "
                                    + out[index]);
                }
            }
        }
    }

    @ParameterizedTest(name = "{0} bands")
    @ValueSource(ints = {2, 3})
    @DisplayName("band analysis and synthesis equal the reference sample for sample")
    void splitter(int bands) {
        var frames = 160 * bands;
        var input = signal(frames * 300, 16000 * bands, 5 + bands);
        var java = new SplittingFilter(1, bands);
        var split = new float[bands][160];
        var merged = new float[frames];
        var expectedSplit = new float[frames];
        var expectedMerged = new float[frames];
        var frame = new float[frames];
        var flatSplit = new float[frames];
        try (var oracle = new WasmApmOracle.Splitter(bands)) {
            for (var f = 0; f < 300; f++) {
                for (var i = 0; i < frames; i++) {
                    frame[i] = input[f * frames + i] * 32768.f;
                }
                java.analysis(0, frame, split);
                oracle.analysis(frame, expectedSplit);
                for (var b = 0; b < bands; b++) {
                    for (var i = 0; i < 160; i++) {
                        assertEquals(Float.floatToRawIntBits(expectedSplit[b * 160 + i]),
                                Float.floatToRawIntBits(split[b][i]), "analysis frame " + f);
                        flatSplit[b * 160 + i] = split[b][i] * (b == 0 ? 0.5f : 1.25f);
                        split[b][i] = flatSplit[b * 160 + i];
                    }
                }
                java.synthesis(0, split, merged);
                oracle.synthesis(flatSplit, expectedMerged);
                for (var i = 0; i < frames; i++) {
                    assertEquals(Float.floatToRawIntBits(expectedMerged[i]), Float.floatToRawIntBits(merged[i]),
                            "synthesis frame " + f + " sample " + i);
                }
            }
        }
    }

    @Test
    @DisplayName("render frames before the first capture frame are processed as the reference processes them")
    void earlyRender() {
        var options = VoiceProcessorOptions.forVoip().toBuilder().gainControl(GainControl.ADAPTIVE_DIGITAL).build();
        var streams = RateScenarios.farEnd(4, 16000, 1, 1, false);
        var java = new VoiceProcessor(options, false, false);
        var noise = new float[160];
        Arrays.fill(noise, 0.04f);
        try (var oracle = WasmApmOracle.of(options)) {
            for (var i = 0; i < 7; i++) {
                java.processRender(noise, 0);
                oracle.processRender(noise, 0);
            }
            var render = new float[160];
            var javaFrame = new float[160];
            var oracleFrame = new float[160];
            for (var f = 0; f < streams.frames(); f++) {
                for (var i = 0; i < 160; i++) {
                    render[i] = streams.render()[f * 160 + i] / 32768f;
                    javaFrame[i] = streams.capture()[f * 160 + i] / 32768f;
                    oracleFrame[i] = javaFrame[i];
                }
                java.processRender(render, 0);
                oracle.processRender(render, 0);
                java.processCapture(javaFrame, 0, javaFrame, 0);
                oracle.processCapture(oracleFrame, 0);
                for (var i = 0; i < 160; i++) {
                    assertEquals(Float.floatToRawIntBits(oracleFrame[i]), Float.floatToRawIntBits(javaFrame[i]),
                            "frame " + f + " sample " + i);
                }
            }
        }
    }
}
