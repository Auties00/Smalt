package com.github.auties00.smalt.capture.internal.macos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.Arrays;
import java.util.Random;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Feeds hand-built {@code AudioBufferList}s to the process tap's frame conversion, on every system.
 */
@DisplayName("process tap frame conversion")
class TapFramesTest {
    /** Builds a list with one buffer per array; each array holds {@code channels[i]} interleaved channels. */
    private static MemorySegment list(Arena arena, float[][] buffers, int[] channels) {
        var list = arena.allocate(8 + 16L * buffers.length, 8);
        list.set(JAVA_INT, 0, buffers.length);
        for (var i = 0; i < buffers.length; i++) {
            var offset = 8 + 16L * i;
            list.set(JAVA_INT, offset, channels[i]);
            if (buffers[i] == null) {
                continue;
            }
            list.set(JAVA_INT, offset + 4, buffers[i].length * 4);
            var data = arena.allocate(Math.max(4, buffers[i].length * 4L), 4);
            MemorySegment.copy(buffers[i], 0, data, JAVA_FLOAT, 0, buffers[i].length);
            list.set(ADDRESS, offset + 8, data);
        }
        return list;
    }

    private static float[] noise(int count, long seed) {
        var random = new Random(seed);
        var samples = new float[count];
        for (var i = 0; i < count; i++) {
            samples[i] = random.nextFloat() - 0.5f;
        }
        return samples;
    }

    @Test
    @DisplayName("passes one interleaved stereo buffer through")
    void interleaved() {
        try (var arena = Arena.ofConfined()) {
            var samples = noise(960, 1);
            var frames = new TapFrames(2, 2);
            assertEquals(480, frames.convert(list(arena, new float[][]{samples}, new int[]{2})));
            assertArrayEquals(samples, Arrays.copyOf(frames.output(), 960));
        }
    }

    @Test
    @DisplayName("interleaves one buffer per channel")
    void planar() {
        try (var arena = Arena.ofConfined()) {
            var left = noise(100, 2);
            var right = noise(100, 3);
            var frames = new TapFrames(2, 2);
            assertEquals(100, frames.convert(list(arena, new float[][]{left, right}, new int[]{1, 1})));
            for (var i = 0; i < 100; i++) {
                assertEquals(left[i], frames.output()[2 * i]);
                assertEquals(right[i], frames.output()[2 * i + 1]);
            }
        }
    }

    @Test
    @DisplayName("averages stereo to mono")
    void downmix() {
        try (var arena = Arena.ofConfined()) {
            var samples = noise(200, 4);
            var frames = new TapFrames(2, 1);
            assertEquals(100, frames.convert(list(arena, new float[][]{samples}, new int[]{2})));
            for (var i = 0; i < 100; i++) {
                assertEquals(0.5f * (samples[2 * i] + samples[2 * i + 1]), frames.output()[i], 1e-6f);
            }
        }
    }

    @Test
    @DisplayName("duplicates mono to stereo")
    void upmix() {
        try (var arena = Arena.ofConfined()) {
            var samples = noise(50, 5);
            var frames = new TapFrames(1, 2);
            assertEquals(50, frames.convert(list(arena, new float[][]{samples}, new int[]{1})));
            for (var i = 0; i < 50; i++) {
                assertEquals(samples[i], frames.output()[2 * i], 1e-6f);
                assertEquals(samples[i], frames.output()[2 * i + 1], 1e-6f);
            }
        }
    }

    @ParameterizedTest
    @CsvSource({"1,1", "1,2", "2,1", "2,2", "2,6", "6,2", "8,2", "2,8", "16,2", "2,16", "32,1", "1,32", "32,32"})
    @DisplayName("converts between channel counts up to 32 without failing")
    void counts(int source, int target) {
        try (var arena = Arena.ofConfined()) {
            var frames = new TapFrames(source, target);
            assertEquals(64, frames.convert(list(arena, new float[][]{noise(64 * source, source)}, new int[]{source})));
            assertEquals(64 * target, frames.output().length);
        }
    }

    @Test
    @DisplayName("the null pointer CoreAudio may pass for a callback without input holds no audio")
    void nullList() {
        assertEquals(0, new TapFrames(2, 2).convert(MemorySegment.NULL));
    }

    @Test
    @DisplayName("a list without buffers holds no audio")
    void empty() {
        try (var arena = Arena.ofConfined()) {
            assertEquals(0, new TapFrames(2, 2).convert(list(arena, new float[0][], new int[0])));
        }
    }

    @Test
    @DisplayName("a buffer without data holds no audio")
    void nullData() {
        try (var arena = Arena.ofConfined()) {
            assertEquals(0, new TapFrames(2, 2).convert(list(arena, new float[][]{null}, new int[]{2})));
        }
    }

    @Test
    @DisplayName("a channel without data is silent next to one with data")
    void silentChannel() {
        try (var arena = Arena.ofConfined()) {
            var right = noise(10, 6);
            var frames = new TapFrames(2, 2);
            assertEquals(10, frames.convert(list(arena, new float[][]{null, right}, new int[]{1, 1})));
            for (var i = 0; i < 10; i++) {
                assertEquals(0f, frames.output()[2 * i]);
                assertEquals(right[i], frames.output()[2 * i + 1]);
            }
        }
    }

    @Test
    @DisplayName("a missing channel is silent and an extra one ignored")
    void mismatched() {
        try (var arena = Arena.ofConfined()) {
            var mono = noise(10, 7);
            var frames = new TapFrames(2, 2);
            assertEquals(10, frames.convert(list(arena, new float[][]{mono}, new int[]{1})));
            for (var i = 0; i < 10; i++) {
                assertEquals(mono[i], frames.output()[2 * i]);
                assertEquals(0f, frames.output()[2 * i + 1]);
            }
            var three = noise(30, 8);
            assertEquals(10, frames.convert(list(arena, new float[][]{three}, new int[]{3})));
            for (var i = 0; i < 10; i++) {
                assertEquals(three[3 * i], frames.output()[2 * i]);
                assertEquals(three[3 * i + 1], frames.output()[2 * i + 1]);
            }
        }
    }

    @Test
    @DisplayName("buffers of different lengths deliver the frames all of them hold")
    void shortestBuffer() {
        try (var arena = Arena.ofConfined()) {
            var frames = new TapFrames(2, 2);
            assertEquals(5, frames.convert(list(arena, new float[][]{noise(10, 9), noise(5, 10)}, new int[]{1, 1})));
        }
    }

    @Test
    @DisplayName("a corrupt buffer count stays within the guard")
    void corruptCount() {
        try (var arena = Arena.ofConfined()) {
            var list = arena.allocate(8 + 16L * 256, 8);
            list.set(JAVA_INT, 0, Integer.MAX_VALUE);
            assertEquals(0, new TapFrames(2, 2).convert(list));
            list.set(JAVA_INT, 0, -1);
            assertEquals(0, new TapFrames(2, 2).convert(list));
        }
    }

    @Test
    @DisplayName("keeps converting blocks of varying length")
    void varyingBlocks() {
        try (var arena = Arena.ofConfined()) {
            var frames = new TapFrames(2, 1);
            for (var length : new int[]{10, 512, 3, 4096, 1}) {
                var samples = noise(2 * length, length);
                assertEquals(length, frames.convert(list(arena, new float[][]{samples}, new int[]{2})));
                for (var i = 0; i < length; i++) {
                    assertEquals(0.5f * (samples[2 * i] + samples[2 * i + 1]), frames.output()[i], 1e-6f);
                }
            }
        }
    }
}
