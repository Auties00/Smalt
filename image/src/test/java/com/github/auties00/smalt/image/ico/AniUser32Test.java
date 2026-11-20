package com.github.auties00.smalt.image.ico;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationFrame;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers animated cursors against Windows user32 (fixtures and findings in {@code tools/testdata/ani-raw}): the frames
 * it draws, and the raw frames it refuses and {@link AniDecoder} reports as unsupported.
 */
@DisplayName("ANI against user32")
class AniUser32Test {
    private static byte[] resource(String name) {
        try (var in = AniUser32Test.class.getResourceAsStream("user32/" + name)) {
            if (in == null) {
                throw new AssertionError("no fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    record Row(String file, String smalt) {
        @Override
        public String toString() {
            return file;
        }
    }

    static Stream<Row> rows() {
        return new String(resource("expected.tsv"), StandardCharsets.US_ASCII).lines()
                .filter(line -> !line.startsWith("#"))
                .map(line -> line.split("\t"))
                .map(fields -> {
                    assertEquals("rejected", fields[1], fields[0]);
                    return new Row(fields[0], fields[2]);
                });
    }

    @Test
    @DisplayName("the fixtures match their pinned hashes")
    void pinned() throws NoSuchAlgorithmException {
        var lines = new String(resource("SHA256SUMS"), StandardCharsets.US_ASCII).lines().toList();
        assertTrue(lines.size() > 40);
        for (var line : lines) {
            var fields = line.split(" {2}");
            assertEquals(fields[0], HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(resource(fields[1]))),
                    fields[1]);
        }
    }

    @Test
    @DisplayName("decodes a file user32 loads to the pixels DrawIconEx draws")
    void control() {
        var frames = new ArrayList<byte[]>();
        try (var reader = AniDecoder.openAnimation(MediaSource.of(resource("control.ani")))) {
            for (AnimationFrame frame = reader.read(); frame != null && frames.size() < 2; frame = reader.read()) {
                frames.add(RasterTesting.tight(frame.picture()));
            }
        }
        assertEquals(2, frames.size());
        for (var step = 0; step < 2; step++) {
            var drawn = resource("control-" + step + ".bgra");
            var pixels = frames.get(step);
            assertEquals(drawn.length, pixels.length);
            var expected = new byte[drawn.length];
            for (var i = 0; i < drawn.length; i += 4) {
                expected[i] = drawn[i + 2];
                expected[i + 1] = drawn[i + 1];
                expected[i + 2] = drawn[i];
                // The opaque frames read back as opaque; DrawIconEx leaves its own value in a DIB's alpha byte.
                expected[i + 3] = pixels[i + 3];
                assertEquals((byte) 255, pixels[i + 3]);
            }
            assertArrayEquals(expected, pixels, "step " + step);
        }
    }

    @ParameterizedTest
    @MethodSource("rows")
    @DisplayName("treats every file user32 refuses as recorded")
    void refused(Row row) {
        var file = resource(row.file());
        switch (row.smalt()) {
            case "unsupported" -> {
                assertEquals("ani", assertThrows(MediaException.Unsupported.class,
                        () -> AniDecoder.header(MediaSource.of(file))).format());
                assertThrows(MediaException.Unsupported.class, () -> Images.decode(MediaSource.of(file)));
            }
            case "malformed" -> assertThrows(MediaException.Malformed.class, () -> AniDecoder.decode(MediaSource.of(file)));
            // Icon frames under a clear flag: unambiguous, so read although user32 is stricter.
            case "icon-frames" -> assertEquals(2, AniDecoder.header(MediaSource.of(file)).frameCount());
            default -> throw new AssertionError(row.smalt());
        }
    }
}
