package com.github.auties00.smalt.video.h264.encoder.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the {@code cabac_zero_word} arithmetic of {@link CabacZeroWords} against the exact
 * inequality of ITU-T H.264 clause 7.4.2.10, its placement over a picture's slices within a size
 * limit, and the in-place append of {@link NalWriter#appendZeroWords(int, int)} against escaping
 * the padded payload from scratch.
 */
@DisplayName("H.264 encoder cabac_zero_word budget")
class CabacZeroWordsBudgetTest {
    /** The exact constraint: {@code bins <= 32 / 3 * bytes + rawBits / 32}. */
    private static boolean meets(long bins, long bytes, long rawBits) {
        return 96 * bins <= 1024 * bytes + 3 * rawBits;
    }

    @Test
    @DisplayName("the word count meets the exact constraint and is at most one word above the fewest that do")
    void wordCount() {
        var random = new Random(5);
        for (var i = 0; i < 200_000; i++) {
            var raw = 3072L * (1 + random.nextInt(8160));
            var bytes = (long) random.nextInt(2_000_000);
            var bins = (long) (random.nextDouble() * 3 * (32 * bytes / 3 + raw / 32 + 1));
            var words = CabacZeroWords.words(bins, bytes, raw);
            assertTrue(meets(bins, bytes + 3L * words, raw), bins + " bins, " + bytes + " bytes, " + words + " words");
            if (words > 1) {
                assertFalse(meets(bins, bytes + 3L * (words - 2), raw), "too many words for " + bins + " bins");
            }
        }
    }

    /** Escapes like {@link NalWriter#appendUnit}, from scratch, for comparison. */
    private static byte[] unit(boolean annexB, int header, byte[] payload) {
        var writer = new NalWriter(16, annexB);
        writer.appendUnit(header >> 5, header & 0x1F, payload, payload.length);
        return writer.take();
    }

    @ParameterizedTest(name = "Annex B {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("appending words in place gives the bytes of escaping the padded payload, and moves the later units")
    void appendInPlace(boolean annexB) {
        var random = new Random(annexB ? 1 : 2);
        for (var round = 0; round < 500; round++) {
            var count = 1 + random.nextInt(5);
            var payloads = new byte[count][];
            var writer = new NalWriter(32, annexB);
            for (var k = 0; k < count; k++) {
                var payload = new byte[1 + random.nextInt(40)];
                for (var i = 0; i < payload.length; i++) {
                    // zeros and small values exercise emulation prevention; the last byte holds a stop bit
                    payload[i] = (byte) (random.nextInt(3) == 0 ? 0 : random.nextInt(5));
                }
                payload[payload.length - 1] = (byte) (1 << random.nextInt(8));
                payloads[k] = payload;
                writer.appendUnit(3, 1, payload, payload.length);
            }
            var padded = new int[count];
            for (var step = 0; step < 3; step++) {
                var index = random.nextInt(count);
                var words = 1 + random.nextInt(4);
                writer.appendZeroWords(index, words);
                padded[index] += words;
            }
            var expected = new ByteArrayOutputStream();
            for (var k = 0; k < count; k++) {
                var payload = Arrays.copyOf(payloads[k], payloads[k].length + 2 * padded[k]);
                expected.writeBytes(unit(annexB, 0x61, payload));
            }
            for (var k = 0; k < count; k++) {
                var start = writer.unitStart(k);
                var length = writer.unitLength(k);
                var buffer = writer.buffer();
                assertEquals(0x61, buffer[start] & 0xFF, "unit " + k + " starts at its header");
                if (!annexB) {
                    var prefix = ((buffer[start - 4] & 0xFF) << 24) | ((buffer[start - 3] & 0xFF) << 16)
                            | ((buffer[start - 2] & 0xFF) << 8) | (buffer[start - 1] & 0xFF);
                    assertEquals(length, prefix, "length prefix of unit " + k);
                }
            }
            assertArrayEquals(expected.toByteArray(), writer.take(), "round " + round);
        }
    }

    /** Appends a slice unit of a size, header and escaped payload included. */
    private static void slice(NalWriter nal, int size) {
        var payload = new byte[size - 1];
        Arrays.fill(payload, (byte) 0x55);
        nal.appendUnit(3, 1, payload, payload.length);
    }

    @Test
    @DisplayName("the words fill the room of the last slice, then of the earlier ones, and never pass the limit")
    void placement() {
        var nal = new NalWriter(64, false);
        var budget = new CabacZeroWords();
        var raw = 3072L * 4;
        // 3 slices of 90, 80 and 95 bytes under a limit of 100 leave 3, 6 and 1 whole words of room
        var sizes = new int[]{90, 80, 95};
        // bins that need exactly 8 words with these bytes
        var bytes = 89L + 79 + 94;
        var bins = (bytes + 3 * 8) * 32 / 3 + raw / 32;
        while (CabacZeroWords.words(bins, bytes, raw) > 8) {
            bins--;
        }
        while (CabacZeroWords.words(bins + 1, bytes, raw) <= 8) {
            bins++;
        }
        assertEquals(8, CabacZeroWords.words(bins, bytes, raw));
        budget.startPicture(true, 100, raw);
        for (var k = 0; k < sizes.length; k++) {
            slice(nal, sizes[k]);
            budget.closed(nal, k == 0 ? bins : 0, sizes[k] - 1);
        }
        assertEquals(8, budget.finishPicture(nal));
        assertEquals(90 + 3 * 1, nal.unitLength(0), "the first slice takes what is left");
        assertEquals(80 + 3 * 6, nal.unitLength(1));
        assertEquals(95 + 3 * 1, nal.unitLength(2), "the last slice fills first");
    }

    @Test
    @DisplayName("words that the room cannot take go to the last slice; without a limit it takes them all")
    void overflowAndUnlimited() {
        var raw = 3072L;
        var bins = 10_000L;
        var nal = new NalWriter(64, true);
        var budget = new CabacZeroWords();
        budget.startPicture(true, 100, raw);
        slice(nal, 100);
        budget.closed(nal, bins, 99);
        slice(nal, 100);
        budget.closed(nal, 0, 99);
        var words = budget.finishPicture(nal);
        assertEquals(CabacZeroWords.words(bins, 198, raw), words);
        assertEquals(100, nal.unitLength(0));
        assertEquals(100 + 3 * words, nal.unitLength(1));

        nal.clear();
        budget.startPicture(true, 0, raw);
        slice(nal, 40);
        budget.closed(nal, bins, 39);
        slice(nal, 40);
        budget.closed(nal, 0, 39);
        words = budget.finishPicture(nal);
        assertEquals(40, nal.unitLength(0));
        assertEquals(40 + 3 * words, nal.unitLength(1));
        assertTrue(budget.fits(1_000_000, 0, 1_000_000), "no limit always fits");

        nal.clear();
        budget.startPicture(false, 100, raw);
        slice(nal, 40);
        budget.closed(nal, bins, 39);
        assertEquals(0, budget.finishPicture(nal), "CAVLC never gets words");
        assertEquals(40, nal.unitLength(0));
    }

    @Test
    @DisplayName("a slice fits while the room of the closed slices and its own takes the words of the picture so far")
    void fits() {
        var raw = 3072L;
        var nal = new NalWriter(64, false);
        var budget = new CabacZeroWords();
        budget.startPicture(true, 100, raw);
        slice(nal, 70);
        budget.closed(nal, 0, 69);
        // closed room 30 bytes = 10 words; the open slice bound 91 leaves 9 bytes = 3 words
        var bins = 0L;
        while (CabacZeroWords.words(bins, 69 + 80, raw) <= 13) {
            bins++;
        }
        assertTrue(budget.fits(bins - 1, 80, 91), "13 words fit in 13 words of room");
        assertFalse(budget.fits(bins, 80, 91), "14 words do not");
        assertTrue(budget.fits(bins, 80, 88), "a smaller open slice makes room for the 14th");
    }
}
