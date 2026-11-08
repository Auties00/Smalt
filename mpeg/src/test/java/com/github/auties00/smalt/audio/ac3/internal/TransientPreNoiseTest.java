package com.github.auties00.smalt.audio.ac3.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the frame-by-frame transient pre-noise processing against {@link PreNoiseReference}, the
 * one-shot transcription of the pseudo-code applied to the whole signal at once. Without holdback
 * every sample from the frame where the synthesis buffer is complete on must match; with holdback,
 * whose late corrections the harness writes back into the samples before each frame as a caller
 * holding its output back does, every sample must. {@code Eac3MediaFoundationTest} compares the
 * processing with Dolby's decoder itself.
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
     * Runs the processor over the signal frame by frame, with the data in frame {@code frame}; with
     * holdback, writes the late corrections into the samples before each frame.
     */
    private static float[] framewise(float[] pcm, int samples, int frame, int location, int length,
                                     boolean holdback) {
        var out = pcm.clone();
        var processor = new TransientPreNoise(1, holdback);
        for (var index = 0; index * samples < out.length; index++) {
            processor.process(0, out, index * samples, samples, index == frame ? location : -1, length);
            writeLate(processor, out, index * samples);
        }
        return out;
    }

    private static void writeLate(TransientPreNoise processor, float[] out, int frameStart) {
        var from = processor.lateStart(0);
        for (var i = from; i < TransientPreNoise.HOLDBACK; i++) {
            out[frameStart - TransientPreNoise.HOLDBACK + i] = processor.late(0)[i];
        }
    }

    @Test
    @DisplayName("cross-fade windows are Dolby's")
    void windows() {
        for (var n = 0; n < 256; n++) {
            assertEquals((float) PreNoiseReference.window(n), TransientPreNoise.FADE_OUT[n]);
            assertEquals((float) (1 - PreNoiseReference.window(n)), TransientPreNoise.FADE_IN[n]);
        }
        for (var k = 0; k < 128; k++) {
            assertEquals((float) PreNoiseReference.window(2 * k + 1), TransientPreNoise.TAIL_BUFFER[k]);
            assertEquals((float) (1 - PreNoiseReference.window(2 * k)), TransientPreNoise.TAIL_DECODED[k]);
        }
    }

    @ParameterizedTest(name = "data at {0} + {1}, length {2}, frames of {3}")
    @CsvSource({
            "4, 1024, 0, 1536",
            "4, 1100, 255, 1536",
            "4, 1279, 100, 1536",
            "4, 1700, 40, 1536",
            "4, 1800, 100, 1536",
            "4, 2000, 200, 1536",
            "4, 2559, 30, 1536",
            "8, 1024, 50, 768",
            "8, 1100, 255, 768",
            "8, 2500, 255, 768",
            "8, 2000, 255, 512",
            "20, 1300, 10, 256",
            "20, 1700, 255, 256",
            "20, 2559, 255, 256"})
    @DisplayName("match the pseudo-code from the frame where the synthesis buffer is complete on")
    void matchesPseudoCode(int frame, int location, int length, int samples) {
        var pcm = signal(64 * FRAME, location * 31L + length);
        var frameStart = frame * samples;
        var expected = PreNoiseReference.apply(pcm, frameStart, location, length);
        var actual = framewise(pcm, samples, frame, location, length, false);
        // samples output before the synthesis buffer is complete stay as decoded
        var ready = (PreNoiseReference.bufferEnd(location) - 1) / samples * samples;
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

    @ParameterizedTest(name = "data at {0} + {1}, length {2}, frames of {3}")
    @CsvSource({
            "4, 1024, 0, 1536",
            "4, 1100, 255, 1536",
            "4, 1279, 100, 1536",
            "4, 1700, 40, 1536",
            "4, 1800, 100, 1536",
            "4, 1800, 255, 1536",
            "4, 2000, 1, 1536",
            "4, 2559, 30, 1536",
            "8, 1024, 50, 768",
            "8, 1100, 255, 768",
            "8, 2000, 200, 768",
            "8, 2500, 255, 768",
            "8, 2000, 255, 512",
            "20, 1300, 10, 256",
            "20, 1700, 255, 256",
            "20, 2559, 255, 256",
            "0, 1024, 255, 256",
            "0, 2559, 255, 1536"})
    @DisplayName("match the pseudo-code on every sample with holdback")
    void holdbackMatchesPseudoCode(int frame, int location, int length, int samples) {
        var pcm = signal(64 * FRAME, location * 17L + length);
        var frameStart = frame * samples;
        var expected = PreNoiseReference.apply(pcm, frameStart, location, length);
        assertArrayEquals(expected, framewise(pcm, samples, frame, location, length, true));
        // the samples a decoder without holdback has already output when the buffer is complete
        var ready = frameStart + (PreNoiseReference.bufferEnd(location) - 1) / samples * samples;
        var early = frameStart + PreNoiseReference.correctionStart(location, length) < ready;
        var plain = framewise(pcm, samples, frame, location, length, false);
        assertEquals(early, !java.util.Arrays.equals(expected, plain),
                "the samples before the frame completing the buffer are corrected only with holdback");
    }

    @ParameterizedTest(name = "location {0}")
    @CsvSource({"0", "800", "1020", "1023", "2560", "3000", "4092"})
    @DisplayName("ignore locations a decoder does not apply")
    void ignored(int location) {
        var pcm = signal(8 * FRAME, location);
        assertArrayEquals(pcm, framewise(pcm, FRAME, 3, location, 100, false));
        assertArrayEquals(pcm, framewise(pcm, FRAME, 3, location, 100, true));
    }

    @ParameterizedTest(name = "holdback {0}")
    @CsvSource({"false", "true"})
    @DisplayName("apply several pending corrections of one channel in frames of one block")
    void overlapping(boolean holdback) {
        var pcm = signal(64 * 256, 11);
        var out = pcm.clone();
        var expected = pcm.clone();
        var processor = new TransientPreNoise(1, holdback);
        // data in frames 10 to 12, all pending in frame 12; without time scaling length each
        // correction starts in the frame that completes its buffer, and each buffer reads samples the
        // corrections started before it have already corrected
        var locations = new int[]{1024, 1900, 2500};
        for (var index = 0; index < 64; index++) {
            var data = index >= 10 && index < 13 ? locations[index - 10] : -1;
            processor.process(0, out, index * 256, 256, data, 0);
            writeLate(processor, out, index * 256);
        }
        for (var index = 10; index < 13; index++) {
            expected = PreNoiseReference.apply(expected, index * 256, locations[index - 10], 0);
        }
        assertArrayEquals(expected, out);
    }

    @Test
    @DisplayName("apply overlapping corrections that reach the samples before their frames with holdback")
    void overlappingLate() {
        var pcm = signal(64 * 256, 13);
        var out = pcm.clone();
        var expected = pcm.clone();
        var processor = new TransientPreNoise(1, true);
        // two corrections three frames apart, each reaching up to 511 samples before the frame that
        // completes its buffer; the second buffer starts after the first transient, so the two are
        // independent and the one-shot reference applies them in order
        int[][] data = {{10, 1300, 255}, {17, 1300, 255}};
        for (var index = 0; index < 64; index++) {
            var location = -1;
            var length = 0;
            for (var entry : data) {
                if (entry[0] == index) {
                    location = entry[1];
                    length = entry[2];
                }
            }
            processor.process(0, out, index * 256, 256, location, length);
            writeLate(processor, out, index * 256);
        }
        for (var entry : data) {
            expected = PreNoiseReference.apply(expected, entry[0] * 256, entry[1], entry[2]);
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
            assertEquals(FrameDecoder.HOLDBACK, decoder.lateStart(0));
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
        var processor = new TransientPreNoise(1, false);
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

    @ParameterizedTest(name = "holdback {0}")
    @CsvSource({"false", "true"})
    @DisplayName("leave the signal unchanged without data and after a reset")
    void untouched(boolean holdback) {
        var pcm = signal(8 * FRAME, 7);
        assertArrayEquals(pcm, framewise(pcm, FRAME, -1, 0, 0, holdback));
        var processor = new TransientPreNoise(2, holdback);
        var out = pcm.clone();
        processor.process(0, out, 0, FRAME, 2000, 10);
        processor.reset();
        var copy = out.clone();
        processor.process(0, out, FRAME, FRAME, -1, 0);
        assertArrayEquals(copy, out);
        assertEquals(TransientPreNoise.HOLDBACK, processor.lateStart(0));
        assertEquals(2, processor.channels());
        assertEquals(holdback, processor.holdback());
    }

    @Test
    @DisplayName("report late corrections only with holdback and only for the frame processed last")
    void lateReports() {
        var pcm = signal(16 * FRAME, 5);
        var processor = new TransientPreNoise(1, true);
        var reported = 0;
        for (var index = 0; index < 16; index++) {
            // 4 * 450 + 256 = 2056: the buffer ends at 1792, completed in the next frame, and the
            // correction starts at 1792 - 100 - 256 = 1436, 100 samples before that frame
            processor.process(0, pcm, index * FRAME, FRAME, index == 3 ? 1800 : -1, 100);
            if (processor.lateStart(0) < TransientPreNoise.HOLDBACK) {
                assertEquals(4, index);
                assertEquals(TransientPreNoise.HOLDBACK - 100, processor.lateStart(0));
                reported++;
            }
        }
        assertEquals(1, reported);
        var plain = new TransientPreNoise(1, false);
        for (var index = 0; index < 16; index++) {
            plain.process(0, pcm, index * FRAME, FRAME, index == 3 ? 1800 : -1, 100);
            assertFalse(plain.lateStart(0) < TransientPreNoise.HOLDBACK);
        }
    }
}
