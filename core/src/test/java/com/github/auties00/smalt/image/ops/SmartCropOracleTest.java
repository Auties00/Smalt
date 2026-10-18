package com.github.auties00.smalt.image.ops;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compares the attention analysis with smartcrop.js 2.0.5 on the synthetic pictures of
 * {@code tools/testdata/smartcrop-oracle.js}, which runs smartcrop.js without prescaling so both
 * sides analyze the same pixels; the chosen position must be identical.
 */
@DisplayName("smartcrop.js oracle")
class SmartCropOracleTest {
    private static final String ROOT = "/com/github/auties00/smalt/image/ops/smartcrop/";

    record Case(String name, int width, int height, int cropWidth, int cropHeight, int x, int y) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Case> cases() {
        var cases = new ArrayList<Case>();
        for (var line : new String(resource("cases.tsv"), StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var f = line.split("\t");
            cases.add(new Case(f[0], Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                    Integer.parseInt(f[4]), Integer.parseInt(f[5]), Integer.parseInt(f[6])));
        }
        return cases;
    }

    private static byte[] resource(String name) {
        try (var stream = SmartCropOracleTest.class.getResourceAsStream(ROOT + name)) {
            if (stream == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("chooses the same crop as smartcrop.js")
    void matches(Case test) {
        var pixels = resource(test.name() + ".bin");
        assertEquals(4 * test.width() * test.height(), pixels.length);
        var position = SmartCrop.attention(pixels, test.width(), test.height(), test.cropWidth(), test.cropHeight());
        assertArrayEquals(new int[]{test.x(), test.y()}, position);
    }
}
