package com.github.auties00.smalt.audio.process;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Arrays;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the time stretcher by its definition: the output length is the input length over the tempo, the dominant
 * frequency of a tone is multiplied by the pitch and nothing else, the energy stays at that frequency, the channels
 * move together, and the output does not depend on the block sizes.
 */
@DisplayName("Time stretcher")
class TimeStretcherTest {
    static final int RATE = 48_000;

    static float[] tone(double frequency, int frames) {
        var samples = new float[frames];
        for (var index = 0; index < frames; index++) {
            samples[index] = (float) (0.5 * Math.sin(2 * Math.PI * frequency * index / RATE));
        }
        return samples;
    }

    /**
     * Runs a stretcher over planes, written in blocks of the given sizes (cycled), and returns every output frame.
     */
    static float[][] stretch(TimeStretcher stretcher, float[][] input, int[] blocks) {
        var channels = input.length;
        var frames = input[0].length;
        var output = new float[channels][(int) stretcher.outputFrames(frames) + 1];
        var written = 0;
        var read = 0;
        var block = 0;
        var chunk = new float[channels][];
        while (written < frames) {
            var count = Math.min(blocks[block++ % blocks.length], frames - written);
            for (var channel = 0; channel < channels; channel++) {
                chunk[channel] = Arrays.copyOfRange(input[channel], written, written + count);
            }
            stretcher.write(chunk, 0, count);
            written += count;
            read += drain(stretcher, output, read);
        }
        stretcher.finish();
        read += drain(stretcher, output, read);
        assertEquals(0, stretcher.read(output, 0, 0));
        var result = new float[channels][];
        for (var channel = 0; channel < channels; channel++) {
            result[channel] = Arrays.copyOf(output[channel], read);
        }
        return result;
    }

    static int drain(TimeStretcher stretcher, float[][] output, int offset) {
        var total = 0;
        int count;
        while ((count = stretcher.read(output, offset + total, Math.min(1000, output[0].length - offset - total))) > 0) {
            total += count;
        }
        return total;
    }

    /**
     * Returns the power of a signal at a frequency (Goertzel).
     */
    static double power(float[] samples, int from, int to, double frequency) {
        var coefficient = 2 * Math.cos(2 * Math.PI * frequency / RATE);
        double previous = 0, before = 0;
        for (var index = from; index < to; index++) {
            var current = samples[index] + coefficient * previous - before;
            before = previous;
            previous = current;
        }
        return previous * previous + before * before - coefficient * previous * before;
    }

    /**
     * Returns the frequency, to the hertz, with the most power between two bounds.
     */
    static double peak(float[] samples, double low, double high) {
        var from = samples.length / 4;
        var to = samples.length - samples.length / 4;
        var best = low;
        var bestPower = -1.0;
        for (var frequency = low; frequency <= high; frequency += 1) {
            var power = power(samples, from, to, frequency);
            if (power > bestPower) {
                bestPower = power;
                best = frequency;
            }
        }
        return best;
    }

    /**
     * Returns the share of a signal's energy within a band around a frequency, measured with a Hann-windowed DFT.
     */
    static double share(float[] samples, double frequency, double band) {
        var from = samples.length / 4;
        var length = samples.length / 2;
        var windowed = new double[length];
        for (var index = 0; index < length; index++) {
            windowed[index] = samples[from + index] * (0.5 - 0.5 * Math.cos(2 * Math.PI * index / length));
        }
        var total = 0.0;
        var inside = 0.0;
        var step = (double) RATE / length;
        for (var bin = 1; bin < length / 2; bin += 1) {
            var bandFrequency = bin * step;
            if (bandFrequency > 4 * frequency && bandFrequency > 4_000) {
                break;
            }
            double re = 0, im = 0;
            for (var index = 0; index < length; index += 4) {
                var angle = 2 * Math.PI * bin * index / length;
                re += windowed[index] * Math.cos(angle);
                im -= windowed[index] * Math.sin(angle);
            }
            var power = re * re + im * im;
            total += power;
            if (Math.abs(bandFrequency - frequency) <= band) {
                inside += power;
            }
        }
        return inside / total;
    }

    @ParameterizedTest(name = "tempo {0}, pitch {1}")
    @CsvSource({"1.5, 1", "0.75, 1", "2, 1", "0.5, 1", "1, 2", "1, 0.8", "1.5, 1.5", "1.25, 0.9"})
    @DisplayName("divides the length by the tempo and multiplies the frequency by the pitch")
    void definition(double tempo, double pitch) {
        var frames = 3 * RATE;
        var input = new float[][]{tone(440, frames)};
        var output = stretch(new TimeStretcher(1, RATE, tempo, pitch), input, new int[]{4096});
        assertEquals(Math.round(frames / tempo), output[0].length);
        var expected = 440 * pitch;
        assertEquals(expected, peak(output[0], expected - 100, expected + 100), 2.0);
        assertTrue(share(output[0], expected, 15) > 0.97, "the energy stays at the tone");
    }

    @Test
    @DisplayName("passes the input through unchanged at tempo and pitch 1")
    void identity() {
        var input = new float[][]{tone(440, 10_000), tone(1_000, 10_000)};
        var output = stretch(new TimeStretcher(2, RATE, 1, 1), input, new int[]{777});
        assertArrayEquals(input[0], output[0]);
        assertArrayEquals(input[1], output[1]);
    }

    @Test
    @DisplayName("moves every channel by the same offsets, so their ratio is kept exactly")
    void channels() {
        var left = tone(330, 2 * RATE);
        var right = new float[left.length];
        for (var index = 0; index < left.length; index++) {
            right[index] = 0.5f * left[index];
        }
        var output = stretch(new TimeStretcher(2, RATE, 1.3, 1), new float[][]{left, right}, new int[]{4096});
        for (var index = 0; index < output[0].length; index++) {
            assertEquals(0.5f * output[0][index], output[1][index], "frame " + index);
        }
    }

    @ParameterizedTest(name = "tempo {0}, pitch {1}")
    @CsvSource({"1.5, 1", "0.6, 1", "1, 1.25", "2.5, 0.7"})
    @DisplayName("produces the same output whatever the block sizes")
    void blocks(double tempo, double pitch) {
        var random = new SplittableRandom(3);
        var noise = new float[RATE];
        for (var index = 0; index < noise.length; index++) {
            noise[index] = (float) (random.nextDouble() - 0.5);
        }
        var input = new float[][]{noise};
        var whole = stretch(new TimeStretcher(1, RATE, tempo, pitch), input, new int[]{noise.length});
        var sizes = new int[64];
        for (var index = 0; index < sizes.length; index++) {
            sizes[index] = 1 + random.nextInt(3000);
        }
        var split = stretch(new TimeStretcher(1, RATE, tempo, pitch), input, sizes);
        assertArrayEquals(whole[0], split[0]);
    }

    @Test
    @DisplayName("keeps a short input's length exact and rejects factors out of range")
    void edges() {
        var output = stretch(new TimeStretcher(1, RATE, 3, 1), new float[][]{tone(440, 100)}, new int[]{100});
        assertEquals(33, output[0].length);
        var empty = new TimeStretcher(1, RATE, 2, 1);
        empty.finish();
        assertEquals(0, empty.available());
        assertThrows(IllegalStateException.class, () -> empty.write(new float[][]{new float[1]}, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new TimeStretcher(1, RATE, 0.05, 1));
        assertThrows(IllegalArgumentException.class, () -> new TimeStretcher(1, RATE, 1, 11));
        assertThrows(IllegalArgumentException.class, () -> new TimeStretcher(0, RATE, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new TimeStretcher(1, RATE, 1, 1).write(new float[2][1], 0,
                1));
    }
}
