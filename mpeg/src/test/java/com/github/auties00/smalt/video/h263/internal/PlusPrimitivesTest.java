package com.github.auties00.smalt.video.h263.internal;

import com.github.auties00.smalt.video.mpeg4.internal.BitReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the building blocks of the annex engine on their own: the arithmetic decoder's start after zero bits, the
 * clipping of the reference inverse transform, and the bound on the pictures the pool keeps.
 */
@DisplayName("H.263 annex engine primitives")
class PlusPrimitivesTest {
    private static int[] symbols(byte[] data, int start, int count) {
        var reader = new BitReader();
        reader.load(data, 0, data.length);
        reader.seek(start);
        var decoder = new ArithmeticDecoder();
        decoder.start(reader);
        var out = new int[count];
        for (var i = 0; i < count; i++) {
            out[i] = decoder.decode(SacModels.MVD);
        }
        assertFalse(decoder.stopped());
        return out;
    }

    @Test
    @DisplayName("drops the stuffing bit after fourteen zero bits that precede the arithmetic coded data")
    void stuffingBeforeStart() {
        var payload = new byte[64];
        new SplittableRandom(7).nextBytes(payload);
        for (var i = 0; i < payload.length; i += 2) {
            payload[i] |= 0x11;
        }
        // 16 one bits, then the payload
        var plain = new byte[2 + payload.length];
        plain[0] = (byte) 0xFF;
        plain[1] = (byte) 0xFF;
        System.arraycopy(payload, 0, plain, 2, payload.length);
        // 16 zero bits, the stuffing one bit E.3 requires after 14 zeros, then the payload shifted by one bit
        var stuffed = new byte[3 + payload.length];
        stuffed[2] = (byte) 0x80;
        for (var bit = 0; bit < payload.length * 8; bit++) {
            if ((payload[bit >> 3] >> (7 - (bit & 7)) & 1) != 0) {
                var at = 16 + 1 + bit;
                stuffed[at >> 3] |= (byte) (0x80 >> (at & 7));
            }
        }
        assertArrayEquals(symbols(plain, 16, 12), symbols(stuffed, 16, 12));
    }

    @Test
    @DisplayName("starts arithmetic decoding after fewer zero bits than a stuffing run at the start of the data")
    void startNearOrigin() {
        var payload = new byte[64];
        new SplittableRandom(11).nextBytes(payload);
        for (var i = 0; i < payload.length; i += 2) {
            payload[i] |= 0x11;
        }
        // three zero bits at the start of the data do not make a stuffing run
        var shifted = new byte[1 + payload.length];
        for (var bit = 0; bit < payload.length * 8; bit++) {
            if ((payload[bit >> 3] >> (7 - (bit & 7)) & 1) != 0) {
                var at = 3 + bit;
                shifted[at >> 3] |= (byte) (0x80 >> (at & 7));
            }
        }
        var padded = new byte[1 + payload.length];
        padded[0] = (byte) 0xFF;
        System.arraycopy(payload, 0, padded, 1, payload.length);
        var expected = symbols(padded, 8, 12);
        assertArrayEquals(expected, symbols(shifted, 3, 12));
        assertArrayEquals(symbols(payload, 0, 12), expected);
    }

    private static boolean startCodeAt(byte[] data, int position) {
        var reader = new BitReader();
        reader.load(data, 0, data.length);
        reader.seek(position);
        return MacroblockParser.startCodeAt(reader);
    }

    @Test
    @DisplayName("finds a start code at the position, after zero stuffing to the byte boundary, or the end of the data")
    void startCodes() {
        // a start code at byte 1, after the byte 0xA0; a zero nibble at bit 36 that no start code follows
        var data = new byte[]{(byte) 0xA0, 0, 0, (byte) 0x80, 0x50, 0x55, 0x55, 0x55};
        assertTrue(startCodeAt(data, 8), "at the position");
        assertTrue(startCodeAt(data, 3), "after zero stuffing");
        assertFalse(startCodeAt(data, 1), "after stuffing that is not zero");
        assertFalse(startCodeAt(data, 32), "aligned without a start code");
        assertFalse(startCodeAt(data, 36), "zero stuffing without a start code after it");
        assertTrue(startCodeAt(data, 64), "at the end of the data");
        assertTrue(startCodeAt(new byte[]{(byte) 0xF0, 0}, 4), "zeros up to the end of the data");
        assertFalse(startCodeAt(new byte[]{(byte) 0xF0, 1}, 4), "a one before the end of the data");
    }

    @Test
    @DisplayName("clips the reference inverse transform to the residual range")
    void transformClips() {
        var coefficients = new short[64];
        coefficients[0] = -2048;
        coefficients[1] = -2048;
        coefficients[8] = -2048;
        var out = new int[64];
        ReferenceTransform.inverse(coefficients, 0, out);
        assertEquals(-256, Arrays.stream(out).min().orElseThrow());
        assertTrue(Arrays.stream(out).filter(value -> value == -256).count() > 1, "several clipped samples");
        assertTrue(Arrays.stream(out).max().orElseThrow() > -256, "the opposite corner is not clipped");
        assertArrayEquals(new short[64], coefficients, "the coefficients are cleared");
        coefficients[0] = 2047;
        coefficients[1] = 2047;
        coefficients[8] = 2047;
        ReferenceTransform.inverse(coefficients, 0, out);
        assertEquals(255, Arrays.stream(out).max().orElseThrow());
    }

    @Test
    @DisplayName("keeps at most eight free pictures for reuse")
    void poolBound() {
        var pool = new PicturePool();
        var first = new ArrayList<PlusPicture>();
        for (var i = 0; i < 12; i++) {
            first.add(pool.acquire(32, 32));
        }
        first.forEach(pool::release);
        var reused = Collections.newSetFromMap(new IdentityHashMap<PlusPicture, Boolean>());
        reused.addAll(first);
        var hits = 0;
        for (var i = 0; i < 12; i++) {
            var picture = pool.acquire(32, 32);
            assertEquals(1, picture.users);
            if (reused.contains(picture)) {
                hits++;
            }
        }
        assertEquals(8, hits);
        var held = pool.acquire(16, 16);
        pool.retain(held);
        pool.release(held);
        assertEquals(1, held.users, "a picture with a holder left is not recycled");
        pool.release(null);
    }
}
