package com.github.auties00.smalt.video.av1.internal;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.internal.simd.Simd;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks the vector CDF search and adaptation against the scalar loops of
 * {@link Msac#decodeSymbolAdapt} on random contexts, including the entries after the counter, which
 * they must leave untouched.
 */
@DisplayName("AV1 vector CDF adaptation")
class VectorCdfDspTest {
    @ParameterizedTest(name = "{0} entries")
    @ValueSource(ints = {1, 2, 3, 4, 7, 8, 9, 12, 15})
    @DisplayName("matches the scalar adaptation for every symbol, rate and counter")
    void update(int nSymbols) {
        Skip.unless(Simd.ENABLED && Simd.preferredBits() >= 256, "simd:narrow", "no 256-bit vectors");
        var random = new Random(31 + nSymbols);
        for (var round = 0; round < 20000; round++) {
            var expected = new char[48];
            for (var i = 0; i < expected.length; i++) {
                expected[i] = (char) random.nextInt(32769);
            }
            expected[16 + nSymbols] = (char) random.nextInt(33);
            var actual = expected.clone();
            var off = 16;
            var val = random.nextInt(nSymbols + 1);
            int count = expected[off + nSymbols];
            var rate = 4 + (count >> 4) + (nSymbols > 2 ? 1 : 0);
            var i = 0;
            for (; i < val; i++) {
                expected[off + i] += (char) ((32768 - expected[off + i]) >> rate);
            }
            for (; i < nSymbols; i++) {
                expected[off + i] -= (char) (expected[off + i] >> rate);
            }
            expected[off + nSymbols] = (char) (count + (count < 32 ? 1 : 0));
            VectorCdfDsp.update(actual, off, nSymbols, val, rate, count + (count < 32 ? 1 : 0));
            assertArrayEquals(expected, actual, "symbol " + val + " rate " + rate);
        }
    }

    @ParameterizedTest(name = "{0} entries")
    @ValueSource(ints = {1, 2, 3, 4, 7, 8, 9, 12, 15})
    @DisplayName("finds the symbol and adapts like the scalar decoder for every range and coded value")
    void symbol(int nSymbols) {
        Skip.unless(Simd.ENABLED && Simd.preferredBits() >= 256, "simd:narrow", "no 256-bit vectors");
        var random = new Random(77 + nSymbols);
        var scratch = new char[17];
        for (var round = 0; round < 20000; round++) {
            var expected = new char[48];
            for (var i = 0; i < expected.length; i++) {
                expected[i] = (char) random.nextInt(32769);
            }
            var previous = 32768;
            for (var i = 0; i < nSymbols; i++) {
                previous = random.nextInt(previous + 1);
                expected[16 + i] = (char) previous;
            }
            expected[16 + nSymbols] = (char) random.nextInt(33);
            var actual = expected.clone();
            var off = 16;
            var rng = 0x8000 + random.nextInt(0x8000);
            var r = rng >>> 8;
            var c = random.nextInt(rng);
            var adapt = random.nextBoolean();
            int u;
            var v = rng;
            var val = -1;
            do {
                val++;
                u = v;
                v = (r * (expected[off + val] >>> 6)) >>> 1;
                v += 4 * (nSymbols - val);
            } while (c < v);
            if (adapt) {
                int count = expected[off + nSymbols];
                var rate = 4 + (count >> 4) + (nSymbols > 2 ? 1 : 0);
                var i = 0;
                for (; i < val; i++) {
                    expected[off + i] += (char) ((32768 - expected[off + i]) >> rate);
                }
                for (; i < nSymbols; i++) {
                    expected[off + i] -= (char) (expected[off + i] >> rate);
                }
                expected[off + nSymbols] = (char) (count + (count < 32 ? 1 : 0));
            }
            var packed = VectorCdfDsp.symbol(actual, off, nSymbols, c, r, rng, scratch, adapt);
            assertEquals(val, (int) packed & 0xFF, "symbol");
            assertEquals(u, (int) (packed >>> 8) & 0xFFFFFF, "upper bound");
            assertEquals(v, (int) (packed >>> 32), "lower bound");
            assertArrayEquals(expected, actual, "symbol " + val);
        }
    }
}
