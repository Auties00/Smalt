package com.github.auties00.smalt.audio.voice.internal.agc;

import com.github.auties00.smalt.audio.voice.RateScenarios;
import com.github.auties00.smalt.audio.voice.WasmApmOracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The voice activity detector of the analog gain controller (the standalone GMM detector, the iSAC
 * pitch analysis and the pitch-based detector) against the reference, bit for bit, on the levels
 * and probabilities of every 30 ms chunk of several 16 kHz scenarios.
 */
@DisplayName("Analog gain controller voice detector against the WebRTC oracle")
class VoiceDetectorOracleTest {
    static Stream<Arguments> scenarios() {
        return Stream.of(RateScenarios.levelChanges(20, 16000, 1, 1), RateScenarios.doubleTalk(20, 16000, 1, 1, false),
                        RateScenarios.noiseOnly(10, 16000, 1, 1), RateScenarios.clipping(10, 16000, 1, 1))
                .map(streams -> Arguments.of(streams.name(), streams));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    @DisplayName("reports the same levels and voice probabilities")
    void matches(String name, RateScenarios.Streams streams) {
        var java = new VoiceDetector();
        var reference = new double[7];
        var frame = new short[160];
        try (var oracle = new WasmApmOracle.AgcVad()) {
            for (var f = 0; f < streams.frames(); f++) {
                System.arraycopy(streams.capture(), f * 160, frame, 0, 160);
                java.process(frame);
                var frames = oracle.process(frame, reference);
                var index = f;
                assertEquals(frames, java.frames(), () -> "results of frame " + index);
                for (var i = 0; i < frames; i++) {
                    var result = i;
                    assertEquals(Double.doubleToRawLongBits(reference[i]), Double.doubleToRawLongBits(java.rms(i)),
                            () -> "rms " + result + " of frame " + index);
                    assertEquals(Double.doubleToRawLongBits(reference[3 + i]),
                            Double.doubleToRawLongBits(java.probability(i)),
                            () -> "probability " + result + " of frame " + index + ": expected " + reference[3 + result]
                                    + " but was " + java.probability(result));
                }
                assertEquals((float) reference[6], java.lastVoiceProbability(), () -> "last probability of frame "
                        + index);
            }
        }
    }
}
