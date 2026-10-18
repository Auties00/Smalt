package com.github.auties00.smalt.audio.voice.internal.aecm;

import com.github.auties00.smalt.audio.voice.WasmAecmOracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.function.IntUnaryOperator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The mobile echo canceller against the bit-exact reference, the legacy {@code WebRtcAecm_*} API of
 * webrtc-audio-processing 2.1 built for WebAssembly ({@link WasmAecmOracle}). Both are driven
 * frame by frame with the same far-end and near-end frames and delays, and every output sample
 * and every return code must be identical, over the echo-cancellation scenarios of the voice
 * processor tests, every echo mode, comfort noise on and off, constant and changing delays,
 * clamped delays, a clean near-end signal, in-place processing, far-end bursts and far-end
 * starvation.
 */
@DisplayName("Mobile echo canceller against the WebRTC AECM oracle")
class MobileEchoCancellerOracleTest {
    private static final int SECONDS = 10;
    private static final int FRAME = MobileEchoCanceller.FRAME_SAMPLES;
    private static List<WasmAecmOracle.Stream> suite;

    private static synchronized List<WasmAecmOracle.Stream> suite() {
        if (suite == null) {
            suite = WasmAecmOracle.scenarios(SECONDS);
        }
        return suite;
    }

    private static WasmAecmOracle.Stream scenario(String name) {
        return suite().stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow();
    }

    /**
     * How a run feeds the canceller: the delay reported for each capture frame, the number of
     * far-end frames buffered before each capture frame, whether a clean near-end signal (the
     * noisy one halved) is passed, and whether the output overwrites the noisy input.
     */
    private record Drive(IntUnaryOperator delay, IntUnaryOperator renders, boolean clean, boolean inPlace) {
        static Drive constant(int delayMs) {
            return new Drive(frame -> delayMs, frame -> 1, false, true);
        }
    }

    private static void verify(int echoMode, boolean comfortNoise, WasmAecmOracle.Stream stream, Drive drive) {
        var java = new MobileEchoCanceller(echoMode, comfortNoise);
        try (var oracle = new WasmAecmOracle(echoMode, comfortNoise)) {
            var render = stream.render();
            var renderFrames = render.length / FRAME;
            var renderCursor = 0;
            var noisy = new short[FRAME];
            var clean = new short[FRAME];
            var javaOut = new short[FRAME];
            var oracleOut = new short[FRAME];
            for (var f = 0; f < stream.frames(); f++) {
                var renders = drive.renders().applyAsInt(f);
                for (var r = 0; r < renders; r++) {
                    var offset = (renderCursor++ % renderFrames) * FRAME;
                    var expected = oracle.bufferFarend(render, offset);
                    var actual = java.bufferFarend(render, offset);
                    if (expected != actual) {
                        fail("bufferFarend returned " + actual + " instead of " + expected + " before frame " + f);
                    }
                }
                System.arraycopy(stream.capture(), f * FRAME, noisy, 0, FRAME);
                for (var i = 0; i < FRAME; i++) {
                    clean[i] = (short) (noisy[i] >> 1);
                }
                var delay = drive.delay().applyAsInt(f);
                var cleanInput = drive.clean() ? clean : null;
                var expected = oracle.process(noisy, 0, cleanInput, 0, oracleOut, 0, delay, drive.inPlace());
                int actual;
                if (drive.inPlace()) {
                    System.arraycopy(noisy, 0, javaOut, 0, FRAME);
                    actual = java.process(javaOut, 0, cleanInput, 0, javaOut, 0, delay);
                } else {
                    actual = java.process(noisy, 0, cleanInput, 0, javaOut, 0, delay);
                }
                if (expected != actual) {
                    fail("process returned " + actual + " instead of " + expected + " at frame " + f);
                }
                if (!Arrays.equals(javaOut, oracleOut)) {
                    var first = IntStream.range(0, FRAME).filter(i -> javaOut[i] != oracleOut[i]).findFirst().orElseThrow();
                    fail("frame " + f + " differs at sample " + first + ": java " + javaOut[first] + ", oracle "
                            + oracleOut[first]);
                }
            }
        }
    }

    static Stream<Arguments> scenarios() {
        return suite().stream().map(s -> Arguments.of(s.name()));
    }

    static Stream<Arguments> modes() {
        return IntStream.rangeClosed(0, 4).boxed()
                .flatMap(mode -> Stream.of(false, true).map(cng -> Arguments.of(mode, cng)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scenarios")
    @DisplayName("every scenario is bit-exact at the speakerphone mode with a 120 ms delay")
    void everyScenario(String name) {
        verify(3, false, scenario(name), Drive.constant(120));
    }

    @ParameterizedTest(name = "mode {0}, comfort noise {1}")
    @MethodSource("modes")
    @DisplayName("every echo mode is bit-exact with and without comfort noise in double talk")
    void everyMode(int mode, boolean comfortNoise) {
        verify(mode, comfortNoise, scenario("double talk"), Drive.constant(50));
    }

    @Nested
    @DisplayName("delay reports")
    class Delays {
        @ParameterizedTest(name = "{0} ms")
        @ValueSource(ints = {0, 50, 120})
        @DisplayName("a constant delay is bit-exact")
        void constant(int delayMs) {
            verify(3, true, scenario("far-end 100 ms"), Drive.constant(delayMs));
        }

        @Test
        @DisplayName("a delay that jumps and jitters mid-stream is bit-exact")
        void changing() {
            verify(3, true, scenario("echo-path change"),
                    new Drive(frame -> frame < 300 ? 40 : 180 + (frame % 7) * 3, frame -> 1, false, true));
        }

        @Test
        @DisplayName("delays outside [0, 500] ms are clamped with the same warning")
        void clamped() {
            verify(3, false, scenario("far-end 250 ms"),
                    new Drive(frame -> frame % 50 < 25 ? -20 : 640, frame -> 1, false, true));
        }
    }

    @Nested
    @DisplayName("near-end inputs")
    class NearEnd {
        @Test
        @DisplayName("a separate clean near-end signal is bit-exact")
        void clean() {
            verify(3, true, scenario("double talk"), new Drive(frame -> 60, frame -> 1, true, false));
        }

        @Test
        @DisplayName("a clean near-end signal processed in place is bit-exact")
        void cleanInPlace() {
            verify(1, false, scenario("far-end 20 ms"), new Drive(frame -> 60, frame -> 1, true, true));
        }

        @Test
        @DisplayName("an output separate from the noisy input is bit-exact")
        void separateOutput() {
            verify(4, true, scenario("clipping"), new Drive(frame -> 80, frame -> 1, false, false));
        }
    }

    @Nested
    @DisplayName("far-end pacing")
    class Pacing {
        @Test
        @DisplayName("bursts of far-end frames between near-end frames are bit-exact")
        void bursts() {
            var pattern = new int[]{0, 2, 1, 1, 3, 0, 0, 1, 2, 0};
            verify(3, true, scenario("far-end 100 ms"),
                    new Drive(frame -> 100, frame -> pattern[frame % pattern.length], false, true));
        }

        @Test
        @DisplayName("far-end starvation and recovery are bit-exact")
        void starvation() {
            verify(2, true, scenario("render pauses"),
                    new Drive(frame -> 70, frame -> frame >= 200 && frame < 400 ? 0 : frame >= 400 && frame < 420 ? 4 : 1,
                            false, true));
        }

        @Test
        @DisplayName("an overfull far-end queue is bit-exact")
        void overfull() {
            verify(3, false, scenario("double talk"), new Drive(frame -> 30, frame -> frame < 60 ? 3 : 1, false, true));
        }
    }

    @Test
    @DisplayName("an echo mode outside [0, 4] is rejected")
    void invalidMode() {
        assertThrows(IllegalArgumentException.class, () -> new MobileEchoCanceller(5, false));
        var canceller = new MobileEchoCanceller(3, false);
        assertEquals(MobileEchoCanceller.BAD_PARAMETER_ERROR, canceller.setConfig(-1, true));
    }
}
