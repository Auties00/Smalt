package com.github.auties00.smalt.image.webp.internal.lossless;

import com.github.auties00.smalt.internal.task.ParallelBands;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/**
 * Builds the match table of generated images with and without the speculative parallel search
 * and requires the same table.
 */
@DisplayName("HashChain.fill does not depend on the threading")
class HashChainTest {
    static int[] image(int width, int height, int kind, long seed) {
        var random = new Random(seed);
        var argb = new int[width * height];
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                int pixel;
                switch (kind) {
                    case 0 -> pixel = random.nextInt() | 0xFF000000;
                    case 1 -> pixel = 0xFF000000 | ((x / 9) * 0x010203 + (y / 7) * 0x040506);
                    case 2 -> pixel = 0xFF000000 | ((x * 3 + y * 5 + random.nextInt(3)) & 0xFF) * 0x010101;
                    default -> pixel = (y % 64 < 32 ? x * 0x9E3779B1 : (x % 100) * 0x1000193) | 0xFF000000;
                }
                argb[y * width + x] = pixel;
            }
        }
        if (kind == 3) {
            for (var i = width * 40; i < argb.length; i++) {
                if (random.nextInt(50) != 0) {
                    argb[i] = argb[i - width * 32];
                }
            }
        }
        return argb;
    }

    @ParameterizedTest(name = "kind {0}")
    @ValueSource(ints = {0, 1, 2, 3})
    @DisplayName("sequential and speculative searches agree")
    void sameTable(int kind) {
        try (var _ = ParallelBands.operation()) {
            var width = 700;
            var height = 400;
            var argb = image(width, height, kind, 5L + kind);
            for (var quality : new int[]{10, 60, 100}) {
                for (var lowEffort : new boolean[]{false, true}) {
                    var sequential = new HashChain(argb.length);
                    sequential.fill(quality, argb, width, height, lowEffort, false);
                    var parallel = new HashChain(argb.length);
                    parallel.fill(quality, argb, width, height, lowEffort, true);
                    assertArrayEquals(sequential.offsetLength, parallel.offsetLength, "quality " + quality);
                }
            }
        }
    }
}
