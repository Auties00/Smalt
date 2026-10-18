package com.github.auties00.smalt.audio.ac3.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the frame-by-frame transient pre-noise processing against a one-shot transcription of
 * the pseudo-code of ATSC A/52 Annex E section 3.7.2 applied to the whole signal at once: every
 * sample of the frame holding the data and of the frames after it must match, wherever the
 * synthesis buffer and the corrected samples fall relative to the frame boundaries.
 */
class TransientPreNoiseTest {
    private static final int FRAME = 1536;

    private static float[] signal(int length, long seed) {
        var random = new Random(seed);
        var samples = new float[length];
        for (var i = 0; i < length; i++) {
            samples[i] = (float) (random.nextGaussian() * 0.1);
        }
        return samples;
    }

    /**
     * Applies the pseudo-code to the whole signal: the transient lies {@code location} samples
     * after {@code frameStart}.
     */
    private static float[] reference(float[] pcm, int frameStart, int location, int length) {
        var out = pcm.clone();
        var blockStart = (location / 256 - 1) * 256;
        var preNoise = location - blockStart;
        var total = preNoise + length + TransientPreNoise.TC1;
        var position = frameStart + location;
        var synthesis = new float[2 * TransientPreNoise.TC1 + preNoise];
        for (var n = 0; n < synthesis.length; n++) {
            synthesis[n] = pcm[position - (2 * TransientPreNoise.TC1 + 2 * preNoise) + n];
        }
        var start = position - total;
        for (var n = 0; n < total; n++) {
            var index = start + n;
            if (n < TransientPreNoise.TC1) {
                var fadeOut = (float) (0.5 * (1 + Math.cos(Math.PI * (n + 0.5) / TransientPreNoise.TC1)));
                out[index] = pcm[index] * fadeOut + synthesis[n] * (1 - fadeOut);
            } else if (n < total - TransientPreNoise.TC2) {
                out[index] = synthesis[n];
            } else {
                var k = n - (total - TransientPreNoise.TC2);
                var fadeOut = (float) (0.5 * (1 + Math.cos(Math.PI * (k + 0.5) / TransientPreNoise.TC2)));
                out[index] = pcm[index] * (1 - fadeOut) + synthesis[n] * fadeOut;
            }
        }
        return out;
    }

    /**
     * Runs the processor over the signal frame by frame, with the data in frame {@code frame}.
     */
    private static float[] framewise(float[] pcm, int samples, int frame, int location, int length) {
        var out = pcm.clone();
        var processor = new TransientPreNoise(1);
        for (var index = 0; index * samples < out.length; index++) {
            processor.process(0, out, index * samples, samples, index == frame ? location : -1, length);
        }
        return out;
    }

    @ParameterizedTest(name = "transient at {0} + {1}, length {2}, frames of {3}")
    @CsvSource({
            "4, 1100, 0, 1536",
            "4, 1300, 255, 1536",
            "4, 1535, 100, 1536",
            "4, 1700, 40, 1536",
            "4, 2900, 200, 1536",
            "4, 3500, 30, 1536",
            "8, 900, 50, 768",
            "20, 600, 10, 256",
            "20, 1000, 255, 256"})
    @DisplayName("match the pseudo-code from the frame where the synthesis buffer is complete on")
    void matchesPseudoCode(int frame, int location, int length, int samples) {
        var pcm = signal(64 * FRAME, location * 31L + length);
        var frameStart = frame * samples;
        var expected = reference(pcm, frameStart, location, length);
        var actual = framewise(pcm, samples, frame, location, length);
        // samples output before the synthesis buffer is complete stay as decoded
        var blockStart = (location / 256 - 1) * 256;
        var ready = blockStart <= samples ? 0 : (blockStart - 1) / samples * samples;
        for (var i = 0; i < frameStart + ready; i++) {
            expected[i] = pcm[i];
        }
        assertArrayEquals(expected, actual);
    }

    /**
     * Encodes a stereo signal with tones and bursts to E-AC-3 and decodes it frame by frame,
     * replacing frame {@code forged} with a copy carrying transient data for channel 0.
     */
    private static float[][] decode(int frames, int forged, int location, int length) {
        var encoder = new FrameEncoder(2, false, 48000, 192000, -31, true, true, true, 6, EncoderTools.NONE, false,
                Ac3Kernels.scalar());
        var decoder = new FrameDecoder(Ac3Kernels.scalar());
        var input = new float[2][FRAME];
        var output = new float[2][frames * FRAME];
        var gains = new float[6][2];
        for (var row : gains) {
            java.util.Arrays.fill(row, 1);
        }
        for (var f = 0; f < frames; f++) {
            for (var i = 0; i < FRAME; i++) {
                var t = (f * FRAME + i) / 48000.0;
                input[0][i] = (float) (0.3 * Math.sin(2 * Math.PI * 330 * t) + ((f * FRAME + i) % 4000 < 80 ? 0.4 : 0));
                input[1][i] = (float) (0.25 * Math.sin(2 * Math.PI * 1250 * t));
            }
            var frame = encoder.encode(input, 0);
            if (f == forged) {
                frame = TransientForging.forge(frame, 0, location, length);
            }
            decoder.parse(frame, 0, frame.length, false, true);
            assertEquals(true, decoder.valid());
            decoder.reconstruct();
            decoder.synthesize(gains, 1, new float[][]{output[0], output[1]}, f * FRAME);
        }
        return output;
    }

    @ParameterizedTest(name = "transprocloc {0} in frame {1}")
    @CsvSource({"300, 3, 120", "200, 4, 0", "430, 5, 255"})
    @DisplayName("apply the data an E-AC-3 frame carries to the decoded channel")
    void decoder(int location, int frame, int length) {
        var plain = decode(10, -1, 0, 0);
        var forged = decode(10, frame, location, length);
        var expected = plain[0].clone();
        var processor = new TransientPreNoise(1);
        for (var f = 0; f < 10; f++) {
            processor.process(0, expected, f * FRAME, FRAME, f == frame ? 4 * location : -1, length);
        }
        assertArrayEquals(expected, forged[0]);
        assertArrayEquals(plain[1], forged[1]);
        var changed = 0;
        for (var i = 0; i < expected.length; i++) {
            changed += expected[i] != plain[0][i] ? 1 : 0;
        }
        assertEquals(true, changed > 400, "corrected samples " + changed);
    }

    @Test
    @DisplayName("leave the signal unchanged without data and after a reset")
    void untouched() {
        var pcm = signal(8 * FRAME, 7);
        assertArrayEquals(pcm, framewise(pcm, FRAME, -1, 0, 0));
        var processor = new TransientPreNoise(2);
        var out = pcm.clone();
        processor.process(0, out, 0, FRAME, 1700, 10);
        processor.reset();
        var copy = out.clone();
        processor.process(0, out, FRAME, FRAME, -1, 0);
        assertArrayEquals(copy, out);
        assertEquals(2, processor.channels());
    }
}
