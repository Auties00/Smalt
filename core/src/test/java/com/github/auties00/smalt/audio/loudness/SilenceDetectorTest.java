package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks silence detection on synthesized tone and silence sequences: range boundaries to the
 * sample, the minimum duration, the threshold, multichannel rules, streaming and the end of the
 * stream.
 */
@DisplayName("SilenceDetector")
class SilenceDetectorTest {
    private static final int RATE = 48_000;

    private static List<SilenceRange> detect(float[][] planes, SilenceDetectorOptions options) {
        var detector = new SilenceDetector(RATE, options);
        detector.accept(planes, 0, planes[0].length);
        return detector.finish();
    }

    @Test
    @DisplayName("finds silences to the sample and keeps the trailing one open until the end")
    void ranges() {
        var planes = Signals.tones(RATE, 2, 1, -20, 2.5, Double.NaN, 1, -20, 0.5, Double.NaN, 1, -20, 3, Double.NaN);
        var ranges = detect(planes, SilenceDetectorOptions.defaults());
        assertEquals(2, ranges.size());
        // a sine starts at a zero crossing, so the silence includes the tone's last sample when it is zero
        assertEquals(RATE, ranges.get(0).startFrame(), 1);
        assertEquals(3.5 * RATE, ranges.get(0).endFrame(), 1);
        assertEquals(6 * RATE, ranges.get(1).startFrame(), 1);
        assertEquals(9 * RATE, ranges.get(1).endFrame());
        assertEquals(2_500_000, ranges.get(0).durationMicros(), 100);
    }

    @Test
    @DisplayName("ignores silences shorter than the minimum and counts quiet noise as silence")
    void thresholdAndDuration() {
        var planes = Signals.tones(RATE, 1, 1, -20, 1, Double.NaN, 1, -20);
        var random = new Random(1);
        for (var index = RATE; index < 2 * RATE; index++) {
            planes[0][index] = (float) (random.nextGaussian() * 0.0001);
        }
        assertTrue(detect(planes, SilenceDetectorOptions.defaults()).isEmpty());
        var short_ = detect(planes, SilenceDetectorOptions.of(-60, 500_000));
        assertEquals(1, short_.size());
        assertTrue(detect(planes, SilenceDetectorOptions.of(-90, 500_000)).isEmpty());
    }

    @Test
    @DisplayName("needs every channel quiet")
    void channels() {
        var planes = Signals.tones(RATE, 2, 3, Double.NaN);
        planes[1] = Signals.tones(RATE, 1, 3, -30)[0];
        assertTrue(detect(planes, SilenceDetectorOptions.defaults()).isEmpty());
    }

    @Test
    @DisplayName("gives the same ranges whatever the block sizes")
    void streaming() {
        var planes = Signals.tones(RATE, 2, 1, -20, 2.5, Double.NaN, 1, -20, 2.2, Double.NaN, 0.3, -10);
        var whole = detect(planes, SilenceDetectorOptions.defaults());
        var detector = new SilenceDetector(RATE);
        var random = new Random(3);
        var offset = 0;
        while (offset < planes[0].length) {
            var count = Math.min(planes[0].length - offset, 1 + random.nextInt(5000));
            var block = new float[2][count];
            System.arraycopy(planes[0], offset, block[0], 0, count);
            System.arraycopy(planes[1], offset, block[1], 0, count);
            detector.accept(new AudioBuffer(ChannelLayout.STEREO, RATE, block, count));
            offset += count;
        }
        assertEquals(whole, detector.finish());
        assertEquals(2, whole.size());
    }

    @Test
    @DisplayName("reports the state and refuses samples after the end")
    void state() {
        var detector = new SilenceDetector(RATE);
        detector.accept(new float[][]{new float[100]}, 0, 100);
        assertTrue(detector.isSilent());
        assertTrue(detector.ranges().isEmpty());
        assertTrue(detector.finish().isEmpty());
        assertThrows(IllegalStateException.class, () -> detector.accept(new float[][]{new float[1]}, 0, 1));
        var loud = new SilenceDetector(RATE);
        loud.accept(new float[][]{{0.5f}}, 0, 1);
        assertFalse(loud.isSilent());
    }

    @Test
    @DisplayName("detects the silences of a WAV file")
    void file() {
        var planes = Signals.tones(RATE, 2, 1, -20, 3, Double.NaN, 1, -20);
        var ranges = Loudness.detectSilence(MediaSource.of(Signals.wav(planes, RATE)), SilenceDetectorOptions.defaults());
        assertEquals(1, ranges.size());
        assertEquals(1_000_000, ranges.getFirst().startMicros(), 100);
        assertEquals(4_000_000, ranges.getFirst().endMicros(), 100);
    }

    @Test
    @DisplayName("validates its options")
    void options() {
        assertThrows(IllegalArgumentException.class, () -> SilenceDetectorOptions.of(3, 1000));
        assertThrows(IllegalArgumentException.class, () -> SilenceDetectorOptions.of(-60, 0));
        var options = SilenceDetectorOptions.builder().thresholdDb(-50).minDurationMicros(10).build();
        assertEquals(options, options.toBuilder().build());
        assertEquals(-50, options.thresholdDb());
        assertEquals(10, options.minDurationMicros());
        assertThrows(IllegalArgumentException.class, () -> new SilenceRange(5, 4, RATE));
    }
}
