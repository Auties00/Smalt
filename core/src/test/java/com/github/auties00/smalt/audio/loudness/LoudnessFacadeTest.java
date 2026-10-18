package com.github.auties00.smalt.audio.loudness;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the whole-file helpers added to {@link Loudness}: the silence detection overloads and the
 * {@link Path} overloads, which must give the results of the {@link MediaSource} forms and close
 * the file.
 */
@DisplayName("Loudness file helpers")
class LoudnessFacadeTest {
    private static final int RATE = 48_000;

    private static byte[] toneSilenceTone() {
        return Signals.wav(Signals.tones(RATE, 2, 1, -20, 3, Double.NaN, 1, -20), RATE);
    }

    @Test
    @DisplayName("detectSilence finds the same silences with and without default options")
    void detectSilence() {
        var wav = toneSilenceTone();
        var expected = Loudness.detectSilence(MediaSource.of(wav), SilenceDetectorOptions.defaults());
        assertEquals(1, expected.size());
        assertEquals(expected, Loudness.detectSilence(MediaSource.of(wav)));
        assertEquals(expected, Loudness.detectSilence(MediaSource.of(wav), SilenceDetectorOptions.defaults()));
        var strict = SilenceDetectorOptions.of(-60, 5_000_000);
        assertEquals(0, Loudness.detectSilence(MediaSource.of(wav), strict).size());
        assertThrows(UnsupportedOperationException.class,
                () -> Loudness.detectSilence(MediaSource.of(wav)).add(expected.getFirst()));
    }

    @Test
    @DisplayName("the Path overloads match the MediaSource forms and close the file")
    void paths(@TempDir Path directory) throws IOException {
        var wav = toneSilenceTone();
        var file = Files.write(directory.resolve("tone.wav"), wav);
        var stats = Loudness.measure(MediaSource.of(wav));
        assertEquals(stats, Loudness.measure(file));
        var options = LoudnessMeterOptions.loudnessOnly();
        assertEquals(Loudness.measure(MediaSource.of(wav), options), Loudness.measure(file, options));
        var normalization = LoudnessNormalization.forStreaming();
        assertEquals(Loudness.normalizationGainDb(MediaSource.of(wav), normalization),
                Loudness.normalizationGainDb(file, normalization));
        assertEquals(Loudness.detectSilence(MediaSource.of(wav)), Loudness.detectSilence(file));
        var strict = SilenceDetectorOptions.of(-60, 5_000_000);
        assertEquals(0, Loudness.detectSilence(file, strict).size());
        // deleting succeeds on every OS only if every call closed the file
        Files.delete(file);
        assertThrows(MediaException.Io.class, () -> Loudness.measure(file));
        assertThrows(MediaException.Io.class, () -> Loudness.detectSilence(file));
    }

    @Test
    @DisplayName("the Path overloads reject null arguments before opening the file")
    void nulls(@TempDir Path directory) {
        var missing = directory.resolve("missing.wav");
        assertThrows(NullPointerException.class, () -> Loudness.measure(missing, null));
        assertThrows(NullPointerException.class, () -> Loudness.normalizationGainDb(missing, null));
        assertThrows(NullPointerException.class, () -> Loudness.detectSilence(missing, null));
    }
}
