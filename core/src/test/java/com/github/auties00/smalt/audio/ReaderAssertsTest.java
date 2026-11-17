package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.testkit.container.ReaderAsserts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.MultipleFailuresError;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the reader behavior harness itself over the PCM decoders of this artifact: it passes a WAVE and an
 * AIFF file under every source behavior, writes a per-block dump, and fails when the dump differs.
 */
@DisplayName("the reader behavior harness")
class ReaderAssertsTest {
    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples");

    static String failureText(Throwable failure) {
        var text = new StringBuilder(String.valueOf(failure.getMessage()));
        if (failure instanceof MultipleFailuresError multiple) {
            for (var each : multiple.getFailures()) {
                text.append('\n').append(each.getMessage());
            }
        }
        return text.toString();
    }

    @Test
    @DisplayName("writes the per-block dump, passes it unchanged and fails on the first differing block")
    void golden(@TempDir Path directory) throws Exception {
        var previous = System.getProperty("smalt.test.dumps");
        var update = System.getProperty("smalt.test.updateDumps");
        System.setProperty("smalt.test.dumps", directory.toString());
        try {
            var data = Files.readAllBytes(SAMPLES.resolve("wav/s16.wav"));
            System.setProperty("smalt.test.updateDumps", "true");
            ReaderAsserts.audio("s16", data).run();
            var file = directory.resolve("s16.audio.dump");
            var text = Files.readString(file);
            assertTrue(text.contains("format WAV\n") && text.contains("track 0 PCM_S16LE rate=22050 layout=")
                    && text.contains("\n0 frames=4096 crc16="), text);
            System.clearProperty("smalt.test.updateDumps");
            ReaderAsserts.audio("s16", data).run();
            var changed = text.replaceFirst("\n1 frames=4096 crc16=[0-9a-f]{8}", "\n1 frames=4096 crc16=00000000");
            Files.writeString(file, changed);
            var failure = assertThrows(AssertionError.class, () -> ReaderAsserts.audio("s16", data).run());
            assertTrue(failureText(failure).contains("expected <1 frames=4096 crc16=00000000>"), failureText(failure));
        } finally {
            restore("smalt.test.dumps", previous);
            restore("smalt.test.updateDumps", update);
        }
    }

    @Test
    @DisplayName("passes PCM in AIFF and 32-bit float WAVE under every source behavior")
    void pcm() throws Exception {
        for (var sample : new String[]{"aiff/s24.aiff", "wav/f32.wav"}) {
            ReaderAsserts.audio(sample, Files.readAllBytes(SAMPLES.resolve(sample))).noDump().maxFaultReads(1_000)
                    .run();
        }
    }

    private static void restore(String key, String value) {
        if (value == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, value);
        }
    }
}
