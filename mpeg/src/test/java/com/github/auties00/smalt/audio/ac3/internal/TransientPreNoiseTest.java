package com.github.auties00.smalt.audio.ac3.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the frame-by-frame transient pre-noise processing against a one-shot transcription of
 * the pseudo-code of ETSI TS 102 366 clause E.2.7.2 applied to the whole signal at once, with the
 * conventions measured on Dolby's decoder: the transient lies 256 samples after the location the
 * frame carries, only locations from 1024 to 2559 are applied, and the cross-fade windows are
 * {@code w(n) = (1 + cos(pi * (n + 1) / 257)) / 2} and its complement over the first 256 samples and
 * the same windows at every other sample over the last 128. Every sample from the frame where the
 * synthesis buffer is complete on must match. {@code Eac3DolbyDecoderTest} compares the processing
 * with Dolby's decoder itself.
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

    private static double window(int n) {
        return 0.5 * (1 + Math.cos(Math.PI * (n + 1) / 257));
    }

    /**
     * Applies the pseudo-code to the whole signal for data {@code location} samples after
     * {@code frameStart}.
     */
    private static float[] reference(float[] pcm, int frameStart, int location, int length) {
        var out = pcm.clone();
        if (location < 1024 || location >= 2560) {
            return out;
        }
        var transloc = location + 256;
        var blockStart = (transloc / 256 - 1) * 256;
        var pnlen = transloc - blockStart;
        var total = pnlen + length + 256;
        var position = frameStart + transloc;
        var synthesis = new float[2 * 256 + pnlen];
        for (var n = 0; n < synthesis.length; n++) {
            synthesis[n] = pcm[position - (2 * 256 + 2 * pnlen) + n];
        }
        var start = position - total;
        for (var n = 0; n < total; n++) {
            var index = start + n;
            if (n < 256) {
                out[index] = (float) (pcm[index] * (float) window(n) + synthesis[n] * (float) (1 - window(n)));
            } else if (n < total - 128) {
                out[index] = synthesis[n];
            } else {
                var k = n - (total - 128);
                out[index] = (float) (pcm[index] * (float) (1 - window(2 * k)) + synthesis[n] * (float) window(2 * k + 1));
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

    @Test
    @DisplayName("cross-fade windows are Dolby's")
    void windows() {
        for (var n = 0; n < 256; n++) {
            assertEquals((float) window(n), TransientPreNoise.FADE_OUT[n]);
            assertEquals((float) (1 - window(n)), TransientPreNoise.FADE_IN[n]);
        }
        for (var k = 0; k < 128; k++) {
            assertEquals((float) window(2 * k + 1), TransientPreNoise.TAIL_BUFFER[k]);
            assertEquals((float) (1 - window(2 * k)), TransientPreNoise.TAIL_DECODED[k]);
        }
    }

    @ParameterizedTest(name = "data at {0} + {1}, length {2}, frames of {3}")
    @CsvSource({
            "4, 1024, 0, 1536",
            "4, 1100, 255, 1536",
            "4, 1279, 100, 1536",
            "4, 1700, 40, 1536",
            "4, 2000, 200, 1536",
            "4, 2559, 30, 1536",
            "8, 1024, 50, 768",
            "8, 2500, 255, 768",
            "20, 1300, 10, 256",
            "20, 2559, 255, 256"})
    @DisplayName("match the pseudo-code from the frame where the synthesis buffer is complete on")
    void matchesPseudoCode(int frame, int location, int length, int samples) {
        var pcm = signal(64 * FRAME, location * 31L + length);
        var frameStart = frame * samples;
        var expected = reference(pcm, frameStart, location, length);
        var actual = framewise(pcm, samples, frame, location, length);
        // samples output before the synthesis buffer is complete stay as decoded
        var transloc = location + 256;
        var bufferEnd = transloc - (transloc - (transloc / 256 - 1) * 256);
        var ready = (bufferEnd - 1) / samples * samples;
        for (var i = 0; i < frameStart + ready; i++) {
            expected[i] = pcm[i];
        }
        assertArrayEquals(expected, actual);
        var changed = 0;
        for (var i = 0; i < pcm.length; i++) {
            changed += pcm[i] != actual[i] ? 1 : 0;
        }
        assertTrue(changed > 200, "corrected samples " + changed);
    }

    @ParameterizedTest(name = "location {0}")
    @CsvSource({"0", "800", "1020", "1023", "2560", "3000", "4092"})
    @DisplayName("ignore locations a decoder does not apply")
    void ignored(int location) {
        var pcm = signal(8 * FRAME, location);
        assertArrayEquals(pcm, framewise(pcm, FRAME, 3, location, 100));
    }

    @Test
    @DisplayName("apply several pending corrections of one channel in frames of one block")
    void overlapping() {
        var pcm = signal(64 * 256, 11);
        var out = pcm.clone();
        var expected = pcm.clone();
        var processor = new TransientPreNoise(1);
        // data in frames 10 to 12, all pending in frame 12; without time scaling length each
        // correction starts in the frame that completes its buffer, and each buffer reads samples the
        // corrections started before it have already corrected
        var locations = new int[]{1024, 1900, 2500};
        for (var index = 0; index < 64; index++) {
            var data = index >= 10 && index < 13 ? locations[index - 10] : -1;
            processor.process(0, out, index * 256, 256, data, 0);
        }
        for (var index = 10; index < 13; index++) {
            expected = reference(expected, index * 256, locations[index - 10], 0);
        }
        assertArrayEquals(expected, out);
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
    @CsvSource({"300, 3, 120", "256, 4, 0", "430, 5, 255", "639, 4, 7"})
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
        processor.process(0, out, 0, FRAME, 2000, 10);
        processor.reset();
        var copy = out.clone();
        processor.process(0, out, FRAME, FRAME, -1, 0);
        assertArrayEquals(copy, out);
        assertEquals(2, processor.channels());
    }
}
