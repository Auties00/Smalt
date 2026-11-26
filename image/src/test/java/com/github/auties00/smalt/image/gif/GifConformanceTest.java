package com.github.auties00.smalt.image.gif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Runs Robert Ancell's pygif GIF decoder test suite ({@code gif-pygif-*}, CC-BY-SA 4.0): every test of its
 * {@code TESTS} list is a GIF with a {@code .conf} file giving the expected canvas size, loop count, frames (as 8-bit
 * RGBA rasters), frame delays, ICC profile and XMP packet, which this test checks as the suite's own runner
 * ({@code run-test-suite}) does, and more strictly where Smalt's API allows.
 *
 * <ul>
 *   <li>A test whose {@code frames} list is empty ({@code zero-size}, {@code invalid-code}, {@code max-size},
 *       {@code plain-text} ...) has no expected rendering: the runner passes it whatever the decoder does. Smalt
 *       must then decode it or reject it with a {@link MediaException}, in bounded time.</li>
 *   <li>Pixels compare exactly, a pixel transparent in both rasters matching whatever its color, as the runner
 *       compares them.</li>
 *   <li>The suite renders the images that follow one graphic control extension into one frame (the runner
 *       compares only the canvas after the last image), while {@link GifDecoder#openAnimation} yields one frame
 *       per image, as browsers do. The suite's frames must therefore be, in order, a subsequence of Smalt's frames
 *       that ends with Smalt's last frame; when both have as many frames, every frame and delay must match.</li>
 *   <li>The loop count is the stored {@code NETSCAPE2.0} repeat count ({@code infinite} for 0, 0 when there is
 *       none), which {@link com.github.auties00.smalt.image.AnimationReader#loopCount()} normalizes to plays: 0
 *       for forever, {@code n + 1} otherwise. {@code gif87a-animation} is the one test the runner skips (its
 *       {@code infinite} loop count is a heuristic, not stored), so only its pixels are compared.</li>
 *   <li>Delays are hundredths of a second; the frames of a {@code force-animation} file carry none.</li>
 *   <li>{@code max-width} and {@code max-height} are 65535 pixels wide or high, beyond the default
 *       {@link MediaLimits} side, so every file is decoded with that limit raised.</li>
 *   <li>The comment, background and GIF version fields have no counterpart in Smalt's API.</li>
 * </ul>
 */
@DisplayName("GIF pygif conformance suite")
class GifConformanceTest {
    private static final String PREFIX = "gif-pygif-";

    static List<String> tests() {
        return text(PREFIX + "TESTS").lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    }

    private static String text(String name) {
        try {
            return Files.readString(Conformance.vector(name), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] bytes(String name) {
        try {
            return Files.readAllBytes(Conformance.vector(PREFIX + name));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Parses an INI-style {@code .conf} file into its sections. */
    static Map<String, Map<String, String>> parse(String conf) {
        var sections = new LinkedHashMap<String, Map<String, String>>();
        Map<String, String> current = null;
        for (var raw : conf.lines().toList()) {
            var line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                current = new LinkedHashMap<>();
                sections.put(line.substring(1, line.length() - 1), current);
                continue;
            }
            var equals = line.indexOf('=');
            assertTrue(equals > 0 && current != null, "malformed .conf line: " + line);
            current.put(line.substring(0, equals).strip(), line.substring(equals + 1).strip());
        }
        return sections;
    }

    @Test
    @DisplayName("lists every test of the suite")
    void listsSuite() {
        var tests = tests();
        assertEquals(84, tests.size());
        for (var test : tests) {
            assertTrue(Conformance.listed(PREFIX + test + ".conf"), test + ".conf is hosted");
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tests")
    @DisplayName("decodes as the suite expects")
    void conforms(String test) {
        var conf = parse(text(PREFIX + test + ".conf"));
        var config = conf.get("config");
        var data = bytes(config.get("input"));
        var options = ImageDecoderOptions.builder().limits(MediaLimits.defaults().toBuilder()
                .maxDimension(65_535).build()).build();
        var frameNames = config.get("frames").isEmpty() ? List.<String>of()
                : List.of(config.get("frames").split(","));
        if (frameNames.isEmpty()) {
            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                try (var reader = GifDecoder.openAnimation(MediaSource.of(data), ImageDecoderOptions.defaults())) {
                    while (reader.read() != null) {
                        // no expected rendering: only a clean decode or a media exception
                    }
                } catch (MediaException allowed) {
                    // the suite expects nothing of this file
                }
                try {
                    GifDecoder.decode(MediaSource.of(data));
                } catch (MediaException allowed) {
                    // the suite expects nothing of this file
                }
            }, test);
            return;
        }
        var width = Integer.parseInt(config.get("width"));
        var height = Integer.parseInt(config.get("height"));
        var forced = "yes".equals(config.get("force-animation"));
        var frames = new ArrayList<byte[]>();
        var delays = new ArrayList<Integer>();
        assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
            try (var reader = GifDecoder.openAnimation(MediaSource.of(data), options)) {
                assertEquals(width, reader.width(), "canvas width");
                assertEquals(height, reader.height(), "canvas height");
                if (!test.equals("gif87a-animation")) {
                    var loop = config.get("loop-count");
                    assertEquals(loop.equals("infinite") ? 0 : Integer.parseInt(loop) + 1, reader.loopCount(),
                            "plays for loop-count " + loop);
                }
                for (var frame = reader.read(); frame != null; frame = reader.read()) {
                    assertEquals(width, frame.picture().width());
                    assertEquals(height, frame.picture().height());
                    frames.add(RasterTesting.tight(frame.picture()));
                    delays.add(frame.durationMillis());
                }
            }
        });
        var expected = frameNames.stream().map(name -> bytes(conf.get(name).get("pixels"))).toList();
        assertTrue(frames.size() >= expected.size(), "at least " + expected.size() + " frames, got " + frames.size());
        assertSameFrame(expected.getLast(), frames.getLast(), width, test + ": the last frame");
        if (frames.size() == expected.size()) {
            for (var index = 0; index < expected.size(); index++) {
                assertSameFrame(expected.get(index), frames.get(index), width, test + " " + frameNames.get(index));
                var delay = conf.get(frameNames.get(index)).get("delay");
                if (!forced && delay != null) {
                    assertEquals(Integer.parseInt(delay) * 10, delays.get(index), "delay of " + frameNames.get(index));
                }
            }
        } else {
            var next = 0;
            for (var frame : frames) {
                if (next < expected.size() && same(expected.get(next), frame)) {
                    next++;
                }
            }
            assertEquals(expected.size(), next, test + ": the suite's frames are a subsequence of Smalt's");
        }
        var still = GifDecoder.decode(MediaSource.of(data), options);
        assertEquals(width, still.picture().width());
        assertEquals(height, still.picture().height());
        var profile = config.get("color-profile");
        if (profile != null && bytes(profile).length > 0) {
            assertArrayEquals(bytes(profile), still.iccProfile().orElseThrow(), "ICC profile");
        } else {
            assertTrue(still.iccProfile().map(icc -> icc.length == 0).orElse(true), "no ICC profile");
        }
        var xmp = config.get("xmp-data");
        if (xmp != null && bytes(xmp).length > 0) {
            assertArrayEquals(bytes(xmp), still.xmp().orElseThrow(), "XMP packet");
        } else {
            assertTrue(still.xmp().map(packet -> packet.length == 0).orElse(true), "no XMP packet");
        }
    }

    /** Compares two RGBA rasters as the suite's runner does: pixels transparent in both match. */
    private static boolean same(byte[] expected, byte[] actual) {
        if (expected.length != actual.length) {
            return false;
        }
        for (var index = 0; index < expected.length; index += 4) {
            if (expected[index + 3] == 0 && actual[index + 3] == 0) {
                continue;
            }
            if (!Arrays.equals(expected, index, index + 4, actual, index, index + 4)) {
                return false;
            }
        }
        return true;
    }

    private static void assertSameFrame(byte[] expected, byte[] actual, int width, String what) {
        if (!same(expected, actual)) {
            RasterTesting.assertSameRgba(expected, actual, width, what);
            fail(what + " differs");
        }
    }
}
