package com.github.auties00.smalt.image.behavior;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Dumps;
import com.github.auties00.smalt.testkit.image.ImageDecoderAsserts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.opentest4j.MultipleFailuresError;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the image decoder behavior harness itself: it passes the real decoders, and an extra decode that
 * breaks the source contract in one way fails the behavior it breaks.
 */
@DisplayName("the image decoder behavior harness")
class ImageDecoderAssertsTest {
    private static final Path SAMPLE = Path.of("src", "test", "resources", "samples", "gif", "animated.gif");

    /**
     * The ways the extra decode can be broken.
     */
    enum Bug {
        /** Assumes every read delivers what it asks for. */
        ASSUMES_FULL_READS,
        /** Fails when the source does not report its size. */
        NEEDS_SIZE,
        /** Turns a read failure into a placeholder result. */
        SWALLOWS_IO,
        /** Reports a read failure as a corrupt file. */
        IO_AS_MALFORMED,
        /** Closes its source. */
        CLOSES_SOURCE,
        /** Loops forever on a short read. */
        LOOPS_ON_SHORT_READ
    }

    /**
     * Returns the CRC of the first bytes of the source, read the way the bug dictates.
     */
    static Function<MediaSource, String> decoder(Bug bug) {
        return source -> {
            var head = new byte[256];
            switch (bug) {
                case ASSUMES_FULL_READS -> source.read(0, head, 0, head.length);
                case NEEDS_SIZE -> {
                    if (source.size() < 0) {
                        throw new MediaException.Malformed("no size");
                    }
                    source.readFully(0, head, 0, head.length);
                }
                case SWALLOWS_IO -> {
                    try {
                        source.readFully(0, head, 0, head.length);
                    } catch (MediaException.Io failure) {
                        return "unreadable";
                    }
                }
                case IO_AS_MALFORMED -> {
                    try {
                        source.readFully(0, head, 0, head.length);
                    } catch (MediaException.Io failure) {
                        throw new MediaException.Malformed("corrupt", failure);
                    }
                }
                case CLOSES_SOURCE -> {
                    source.readFully(0, head, 0, head.length);
                    source.close();
                }
                case LOOPS_ON_SHORT_READ -> {
                    while (!Thread.currentThread().isInterrupted()
                            && source.read(0, head, 0, head.length) != head.length) {
                        Thread.onSpinWait();
                    }
                }
            }
            return "head " + Dumps.crc32(head);
        };
    }

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
    @DisplayName("passes the real decoders with a correct extra decode")
    void correct() throws IOException {
        ImageDecoderAsserts.of("self-test", Files.readAllBytes(SAMPLE)).noDump().maxFaultReads(10_000)
                .decoder("head", source -> {
                    var head = new byte[256];
                    source.readFully(0, head, 0, head.length);
                    return "head " + Dumps.crc32(head);
                })
                .run();
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Bug.class)
    @DisplayName("fails every deliberately broken extra decode, naming the broken behavior")
    void broken(Bug bug) throws IOException {
        var data = Files.readAllBytes(SAMPLE);
        var harness = ImageDecoderAsserts.of("self-test", data).noDump().maxFaultReads(10_000)
                .timeout(Duration.ofSeconds(bug == Bug.LOOPS_ON_SHORT_READ ? 2 : 60))
                .decoder("head", decoder(bug));
        var text = failureText(assertThrows(AssertionError.class, harness::run));
        var expected = switch (bug) {
            case ASSUMES_FULL_READS -> "ONE_BYTE: result differs";
            case NEEDS_SIZE -> "UNKNOWN_LENGTH";
            case SWALLOWS_IO -> "the fault was swallowed";
            case IO_AS_MALFORMED -> "expected MediaException.Io caused by the injected fault";
            case CLOSES_SOURCE -> "decoding closed the source";
            case LOOPS_ON_SHORT_READ -> "no result within";
        };
        assertTrue(text.contains(expected), () -> bug + ": expected a failure mentioning <" + expected + "> in\n" + text);
    }

    @Test
    @DisplayName("compares the flow with the golden dump and names the first differing line")
    void golden(@TempDir Path directory) throws Exception {
        var previous = System.getProperty("smalt.test.dumps");
        var update = System.getProperty("smalt.test.updateDumps");
        System.setProperty("smalt.test.dumps", directory.toString());
        try {
            var data = Files.readAllBytes(SAMPLE);
            System.setProperty("smalt.test.updateDumps", "true");
            ImageDecoderAsserts.of("gif", data).maxFaultReads(2).run();
            var file = directory.resolve("gif.dump");
            var text = Files.readString(file);
            assertTrue(text.contains("format GIF\n") && text.contains("animation 48x32 loops=0 frames=5\n")
                    && text.contains("frame 4 ts=800000 dur=200000 48x32 rgba8"), text);
            System.clearProperty("smalt.test.updateDumps");
            ImageDecoderAsserts.of("gif", data).maxFaultReads(2).run();
            Files.writeString(file, text.replace("frame 4 ts=800000", "frame 4 ts=700000"));
            var failure = assertThrows(AssertionError.class, () -> ImageDecoderAsserts.of("gif", data)
                    .maxFaultReads(2).run());
            assertTrue(failureText(failure).contains("expected <frame 4 ts=700000"), failureText(failure));
        } finally {
            restore("smalt.test.dumps", previous);
            restore("smalt.test.updateDumps", update);
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
