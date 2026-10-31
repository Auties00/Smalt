package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.audio.ChannelLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the single-pass normalizer on the EBU Tech 3341 programs and on noise: the output's integrated
 * loudness lands within 0.5 LU of the target and its true peak under the ceiling, the stream keeps its
 * length and timing whatever the call split, silence is not raised, and the gain limit holds.
 */
@DisplayName("LoudnessNormalizer")
class LoudnessNormalizerTest {
    private static final int RATE = 48_000;

    static float[][] normalize(LoudnessNormalizer normalizer, float[][] input, int... chunks) {
        var channels = input.length;
        var frames = input[0].length;
        var output = new float[channels][frames + normalizer.delay() + 8192];
        var written = 0;
        var consumed = 0;
        var index = 0;
        while (consumed < frames) {
            var count = Math.min(chunks[index++ % chunks.length], frames - consumed);
            var bound = normalizer.maxOutputFrames(count);
            var produced = normalizer.process(input, consumed, count, output, written);
            assertTrue(produced <= bound, "process wrote more than maxOutputFrames");
            written += produced;
            consumed += count;
        }
        var bound = normalizer.maxOutputFrames(0);
        var flushed = normalizer.flush(output, written);
        assertTrue(flushed <= bound, "flush wrote more than maxOutputFrames(0)");
        written += flushed;
        assertEquals(frames, written, "the stream keeps its length");
        var result = new float[channels][];
        for (var c = 0; c < channels; c++) {
            result[c] = Arrays.copyOf(output[c], frames);
        }
        return result;
    }

    private static float[][] pinkishNoise(int seconds, double rmsDb, long seed) {
        var random = new SplittableRandom(seed);
        var planes = new float[2][RATE * seconds];
        var amplitude = Math.pow(10, rmsDb / 20);
        for (var plane : planes) {
            var state = 0.0;
            for (var i = 0; i < plane.length; i++) {
                // a one-pole low-pass tilts white noise towards the spectrum of music
                state = 0.9 * state + 0.1 * random.nextGaussian();
                plane[i] = (float) (amplitude * state / 0.229);
            }
        }
        return planes;
    }

    static Stream<Arguments> programs() {
        return Stream.of(
                Arguments.of("case 1: -23 dBFS sine", Signals.tones(RATE, 2, 20, -23)),
                Arguments.of("case 2: -33 dBFS sine", Signals.tones(RATE, 2, 20, -33)),
                Arguments.of("case 3: -36/-23/-36 dBFS", Signals.tones(RATE, 2, 10, -36, 60, -23, 10, -36)),
                Arguments.of("case 5: -26/-20/-26 dBFS", Signals.tones(RATE, 2, 20, -26, 20.1, -20, 20, -26)),
                Arguments.of("noise at -40 dBFS RMS", pinkishNoise(30, -40, 1)),
                Arguments.of("noise from -30 to -15 dBFS RMS", Signals.concat(java.util.List.of(
                        pinkishNoise(15, -30, 2), pinkishNoise(15, -15, 3)))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("programs")
    @DisplayName("reaches the target within 0.5 LU under the true-peak ceiling")
    void target(String name, float[][] program) {
        for (var normalization : new LoudnessNormalization[]{LoudnessNormalization.ebuR128(),
                LoudnessNormalization.forStreaming(), LoudnessNormalization.forPodcast()}) {
            var output = normalize(new LoudnessNormalizer(ChannelLayout.STEREO, RATE, normalization), program, 4096);
            var stats = Signals.measure(output, RATE, ChannelLayout.STEREO).stats();
            assertEquals(normalization.targetLufs(), stats.integrated(), 0.5, name + " to " + normalization);
            assertTrue(stats.truePeakDb() <= normalization.truePeakCeilingDb().orElseThrow() + 1e-4,
                    name + ": true peak " + stats.truePeakDb());
        }
    }

    @Test
    @DisplayName("is bit-identical however the input is split, and reproduces a stream after flush")
    void chunking() {
        var program = pinkishNoise(8, -30, 4);
        var normalizer = new LoudnessNormalizer(ChannelLayout.STEREO, RATE, LoudnessNormalization.forStreaming());
        var whole = normalize(normalizer, program, program[0].length);
        var split = normalize(normalizer, program, 1, 4799, 333, 10_000);
        assertArrayEquals(whole[0], split[0]);
        assertArrayEquals(whole[1], split[1]);
    }

    @Test
    @DisplayName("has zero net delay: the output is the input times a slowly moving gain")
    void timing() {
        var program = Signals.tones(RATE, 2, 10, -30);
        var output = normalize(new LoudnessNormalizer(ChannelLayout.STEREO, RATE,
                LoudnessNormalization.builder().targetLufs(-20).noTruePeakCeiling().build()), program, 4096);
        for (var i = RATE; i < program[0].length - RATE; i += 37) {
            if (Math.abs(program[0][i]) > 1e-3) {
                assertEquals(Math.pow(10, 10 / 20.0), output[0][i] / program[0][i], 0.01, "sample " + i);
            }
        }
    }

    @Test
    @DisplayName("does not raise silence or pauses, and respects the gain limit")
    void gatesAndLimit() {
        var silence = new float[2][RATE * 5];
        var output = normalize(new LoudnessNormalizer(ChannelLayout.STEREO, RATE, LoudnessNormalization.ebuR128()),
                silence, 4096);
        assertArrayEquals(silence[0], output[0]);
        var program = Signals.tones(RATE, 2, 10, -20, 10, -60, 10, -20);
        var normalizer = new LoudnessNormalizer(ChannelLayout.STEREO, RATE, LoudnessNormalization.ebuR128());
        var gated = normalize(normalizer, program, 4096);
        var pause = Signals.measure(new float[][]{Arrays.copyOfRange(gated[0], RATE * 13, RATE * 17),
                Arrays.copyOfRange(gated[1], RATE * 13, RATE * 17)}, RATE, ChannelLayout.STEREO);
        // the -60 dBFS pause would come out at the target if the gain followed it
        assertTrue(pause.integrated() < -50, "pause at " + pause.integrated());
        var limited = LoudnessNormalization.builder().targetLufs(-14).maxGainDb(6).noTruePeakCeiling().build();
        var quiet = normalize(new LoudnessNormalizer(ChannelLayout.STEREO, RATE, limited),
                Signals.tones(RATE, 2, 10, -40), 4096);
        assertEquals(-34, Signals.measure(quiet, RATE, ChannelLayout.STEREO).integrated(), 0.2);
    }

    @Test
    @DisplayName("treats NaN and infinite samples as silence and keeps following the loudness")
    void nonFinite() {
        var program = Signals.tones(RATE, 2, 20, -33);
        program[0][RATE * 2] = Float.NaN;
        program[1][RATE * 3] = Float.POSITIVE_INFINITY;
        var normalization = LoudnessNormalization.ebuR128();
        var output = normalize(new LoudnessNormalizer(ChannelLayout.STEREO, RATE, normalization), program, 4096);
        assertEquals(0f, output[0][RATE * 2]);
        assertEquals(0f, output[1][RATE * 3]);
        for (var plane : output) {
            for (var value : plane) {
                assertTrue(Float.isFinite(value));
            }
        }
        assertEquals(normalization.targetLufs(), Signals.measure(output, RATE, ChannelLayout.STEREO).integrated(), 0.5);
    }

    @Test
    @DisplayName("reports its gain and rejects bad buffers")
    void accessors() {
        var normalizer = new LoudnessNormalizer(ChannelLayout.STEREO, RATE, LoudnessNormalization.ebuR128());
        assertEquals(0, normalizer.gainDb());
        normalize(normalizer, Signals.tones(RATE, 2, 5, -33), 4096);
        assertEquals(LoudnessNormalization.ebuR128(), normalizer.normalization());
        assertEquals(2, normalizer.channels());
        assertEquals(RATE, normalizer.sampleRate());
        assertTrue(normalizer.delay() > RATE * 3 / 2);
        assertThrows(IllegalArgumentException.class, () -> normalizer.process(new float[1][10], 0, 10,
                new float[2][normalizer.delay() + 10], 0));
        assertThrows(IllegalArgumentException.class, () -> normalizer.process(new float[2][10], 0, 10,
                new float[2][5], 0));
        assertThrows(IllegalArgumentException.class, () -> normalizer.maxOutputFrames(-1));
        assertThrows(IllegalArgumentException.class, () -> new LoudnessNormalizer(ChannelLayout.STEREO, 4000,
                LoudnessNormalization.ebuR128()));
    }
}
