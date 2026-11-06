package com.github.auties00.smalt.image.jxl.internal;

import com.github.auties00.smalt.MediaLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the frames of the {@code jxli} index that {@link JxlAnimationReader} seeks from: only frames from which
 * no frame reads a reference written before them.
 */
class JxlAnimationReaderTest {
    static JxlAnimationReader open(String name) throws IOException {
        byte[] data;
        try (var input = JxlAnimationReaderTest.class.getResourceAsStream(
                "/com/github/auties00/smalt/image/jxl/features/" + name)) {
            data = input.readAllBytes();
        }
        var count = JxlAnimationReader.countKeyframes(JxlCodestream.open(data, MediaLimits.DEFAULT),
                MediaLimits.DEFAULT);
        return new JxlAnimationReader(JxlCodestream.open(data, MediaLimits.DEFAULT), count, MediaLimits.DEFAULT);
    }

    @Test
    @DisplayName("frames that replace the whole canvas are starting points")
    void independent() throws IOException {
        var restarts = open("indexed.jxl").restarts();
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), restarts.stream().map(restart -> restart.start().frameIndex())
                .toList());
        assertEquals(List.of(100_000L, 200_000L, 300_000L, 400_000L, 500_000L, 600_000L, 700_000L),
                restarts.stream().map(JxlAnimationReader.Restart::timestamp).toList());
    }

    @Test
    @DisplayName("cropped frames that show the frame before them are not")
    void dependent() throws IOException {
        assertTrue(open("indexed-cropped.jxl").restarts().isEmpty());
    }

    @Test
    @DisplayName("a seek to an indexed frame returns it and goes on from there")
    void seek() throws IOException {
        try (var linear = open("indexed.jxl"); var seeking = open("indexed.jxl")) {
            var frames = new java.util.ArrayList<byte[]>();
            for (var frame = linear.read(); frame != null; frame = linear.read()) {
                frames.add(((com.github.auties00.smalt.image.RgbaPicture) frame.picture()).pixels().clone());
            }
            seeking.seek(650_000);
            var frame = seeking.read();
            assertEquals(600_000, frame.timestampMicros());
            assertArrayEquals(frames.get(6), ((com.github.auties00.smalt.image.RgbaPicture) frame.picture()).pixels());
            seeking.seek(350_000);
            assertArrayEquals(frames.get(3), ((com.github.auties00.smalt.image.RgbaPicture) seeking.read().picture())
                    .pixels());
            assertArrayEquals(frames.get(4), ((com.github.auties00.smalt.image.RgbaPicture) seeking.read().picture())
                    .pixels());
        }
    }
}
