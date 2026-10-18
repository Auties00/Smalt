package com.github.auties00.smalt.audio.opus.internal.celt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the range encoder and decoder: randomized round trips mixing every symbol kind, the
 * agreement of the bit accounting on both sides, the final range, and decoding of arbitrary bytes.
 */
class RangeCoderTest {
    private static final short[] ICDF = {200, 150, 60, 20, 0};

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    @DisplayName("round-trips random symbols of every kind with identical tell() on both sides")
    void roundTrip(int seed) {
        var random = new Random(seed);
        var count = 2000;
        var kinds = new int[count];
        var values = new int[count];
        var params = new int[count];
        var tells = new int[count];
        var fracs = new int[count];
        var buffer = new byte[20000];
        var encoder = new RangeEncoder(buffer, 0, buffer.length);
        for (var i = 0; i < count; i++) {
            kinds[i] = random.nextInt(5);
            switch (kinds[i]) {
                case 0 -> {
                    params[i] = 1 + random.nextInt(15);
                    values[i] = random.nextInt(1 << params[i]) == 0 ? 1 : 0;
                    encoder.encBitLogp(values[i], params[i]);
                }
                case 1 -> {
                    values[i] = random.nextInt(5);
                    encoder.encIcdf(values[i], ICDF, 8);
                }
                case 2 -> {
                    params[i] = 2 + random.nextInt(random.nextBoolean() ? 300 : Integer.MAX_VALUE - 2);
                    values[i] = random.nextInt(params[i]);
                    encoder.encUint(values[i], params[i]);
                }
                case 3 -> {
                    params[i] = 1 + random.nextInt(25);
                    values[i] = random.nextInt(1 << params[i]);
                    encoder.encBits(values[i], params[i]);
                }
                default -> {
                    params[i] = 1 + random.nextInt(1000);
                    values[i] = random.nextInt(params[i]);
                    encoder.encode(values[i], values[i] + 1, params[i]);
                }
            }
            tells[i] = encoder.tell();
            fracs[i] = encoder.tellFrac();
        }
        encoder.encDone();
        assertEquals(0, encoder.getError());
        var finalRange = encoder.finalRange();
        var decoder = new RangeDecoder(buffer, 0, buffer.length);
        for (var i = 0; i < count; i++) {
            var decoded = switch (kinds[i]) {
                case 0 -> decoder.decBitLogp(params[i]);
                case 1 -> decoder.decIcdf(ICDF, 8);
                case 2 -> decoder.decUint(params[i]);
                case 3 -> decoder.decBits(params[i]);
                default -> {
                    var s = decoder.decode(params[i]);
                    decoder.decUpdate(s, s + 1, params[i]);
                    yield s;
                }
            };
            assertEquals(values[i], decoded, "symbol " + i + " of kind " + kinds[i]);
            assertEquals(tells[i], decoder.tell(), "tell after symbol " + i);
            assertEquals(fracs[i], decoder.tellFrac(), "tellFrac after symbol " + i);
        }
        assertEquals(finalRange, decoder.finalRange());
        assertEquals(0, decoder.getError());
    }

    @Test
    @DisplayName("reports one bit used by a fresh coder, like the reference")
    void freshTell() {
        assertEquals(1, new RangeEncoder(new byte[4], 0, 4).tell());
        assertEquals(1, new RangeDecoder(new byte[4], 0, 4).tell());
        assertEquals(8, new RangeDecoder(new byte[0], 0, 0).tellFrac());
    }

    @Test
    @DisplayName("decodes arbitrary bytes, including an empty buffer, without failing")
    void arbitraryInput() {
        var random = new Random(42);
        for (var n = 0; n < 200; n++) {
            var data = new byte[random.nextInt(40)];
            random.nextBytes(data);
            var decoder = new RangeDecoder(data, 0, data.length);
            for (var i = 0; i < 300; i++) {
                assertTrue(decoder.decIcdf(ICDF, 8) < 5);
                var v = decoder.decUint(1000);
                assertTrue(v >= 0 && v < 1000);
                decoder.decBits(13);
            }
        }
    }

    @Test
    @DisplayName("patches initial bits and shrinks the buffer while keeping trailing raw bits")
    void patchAndShrink() {
        var buffer = new byte[64];
        var encoder = new RangeEncoder(buffer, 0, buffer.length);
        encoder.encBitLogp(0, 1);
        encoder.encBitLogp(1, 1);
        for (var i = 0; i < 20; i++) {
            encoder.encUint(i, 37);
        }
        encoder.encBits(0x155, 9);
        encoder.encPatchInitialBits(1, 1);
        encoder.encShrink(40);
        encoder.encDone();
        assertEquals(0, encoder.getError());
        var decoder = new RangeDecoder(buffer, 0, 40);
        assertEquals(1, decoder.decBitLogp(1));
        assertEquals(1, decoder.decBitLogp(1));
        for (var i = 0; i < 20; i++) {
            assertEquals(i, decoder.decUint(37));
        }
        assertEquals(0x155, decoder.decBits(9));
    }

    @Test
    @DisplayName("sets the error flag when the output buffer is too small")
    void overflow() {
        var buffer = new byte[3];
        var encoder = new RangeEncoder(buffer, 0, buffer.length);
        for (var i = 0; i < 100; i++) {
            encoder.encUint(i, 1000);
        }
        encoder.encDone();
        assertNotEquals(0, encoder.getError());
    }
}
