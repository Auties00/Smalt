package com.github.auties00.smalt.audio.aac.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
 * Decodes reversible variable length scalefactors written by an encoder of the ISO/IEC 14496-3 codeword
 * tables, clean and with corrupt codewords, and checks the values each concealment strategy produces.
 */
class ReversibleScalefactorsTest {
    /**
     * The scalefactor codewords {code, length} of the coded differences -7 to 7.
     */
    private static final int[][] CODES = {
            {65, 7}, {257, 9}, {129, 8}, {33, 6}, {17, 5}, {9, 4}, {5, 3}, {0, 1}, {7, 3}, {27, 5}, {51, 6},
            {107, 7}, {195, 8}, {427, 9}, {99, 7}
    };

    /**
     * The escape codewords {code, length} of the escapes 0 to 26.
     */
    private static final int[][] ESCAPES = {
            {2, 2}, {0, 2}, {6, 3}, {2, 3}, {14, 4}, {31, 5}, {15, 5}, {13, 5}, {61, 6}, {29, 6}, {25, 6},
            {24, 6}, {120, 7}, {56, 7}, {242, 8}, {114, 8}, {486, 9}, {230, 9}, {974, 10}, {463, 10}, {1950, 11},
            {1951, 11}, {925, 11}, {1848, 12}, {7399, 14}, {3698, 13}, {14797, 15}
    };

    /**
     * A palindromic 9-bit pattern that no codeword starts, read in either direction.
     */
    private static final int[] INVALID = {1, 1, 0, 0, 0, 0, 0, 1, 1};

    private static final int GLOBAL_GAIN = 100;

    private static final int SCALEFACTOR = 1;

    /** Bit writer, most significant bit first. */
    static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int count;
        private int length;

        void write(int value, int bits) {
            for (var i = bits - 1; i >= 0; i--) {
                bit(value >>> i & 1);
            }
        }

        void bit(int bit) {
            current = current << 1 | bit;
            length++;
            if (++count == 8) {
                out.write(current);
                current = 0;
                count = 0;
            }
        }

        void append(Bits other) {
            var bytes = other.bytes();
            for (var i = 0; i < other.length; i++) {
                bit(bytes[i >>> 3] >>> (7 - (i & 7)) & 1);
            }
        }

        int length() {
            return length;
        }

        byte[] bytes() {
            var copy = new ByteArrayOutputStream();
            copy.writeBytes(out.toByteArray());
            if (count > 0) {
                copy.write(current << (8 - count));
            }
            copy.writeBytes(new byte[4]);
            return copy.toByteArray();
        }
    }

    /** A coded stream: the side information followed by the codewords and the escapes. */
    record Coded(byte[] data, int[] types, int[] values) {
    }

    /**
     * Encodes band values; {@code corrupt[p]} replaces the codeword of band {@code p} with
     * {@link #INVALID} when 1, or with the codeword of the difference plus {@code corrupt[p] - 1} otherwise
     * when non-zero.
     */
    static Coded encode(int[] types, int[] values, int[] corrupt, boolean concealment) {
        var codewords = new Bits();
        var escapes = new Bits();
        var scalefactor = GLOBAL_GAIN;
        var position = 0;
        var noiseEnergy = 0;
        var firstNoise = -1;
        var lastScalefactor = GLOBAL_GAIN;
        var intensity = false;
        var noise = false;
        for (var p = 0; p < types.length; p++) {
            var type = types[p];
            if (type == Codebooks.ZERO_HCB) {
                continue;
            }
            int difference;
            if (type == Codebooks.NOISE_HCB) {
                noise = true;
                if (firstNoise < 0) {
                    firstNoise = values[p] - (GLOBAL_GAIN - 90 - 256);
                    noiseEnergy = values[p];
                    continue;
                }
                difference = values[p] - noiseEnergy;
                noiseEnergy = values[p];
            } else if (type == Codebooks.INTENSITY_HCB) {
                intensity = true;
                difference = values[p] - position;
                position = values[p];
            } else {
                difference = values[p] - scalefactor;
                scalefactor = values[p];
                lastScalefactor = scalefactor;
            }
            var flaw = corrupt == null ? 0 : corrupt[p];
            if (flaw == 1) {
                for (var bit : INVALID) {
                    codewords.bit(bit);
                }
            } else {
                difference(codewords, escapes, flaw == 0 ? difference : difference + flaw - 1);
            }
        }
        if (intensity) {
            difference(codewords, escapes, position);
        }
        var side = new Bits();
        side.bit(concealment ? 1 : 0);
        side.write(lastScalefactor, 8);
        side.write(codewords.length() + (noise ? 9 : 0), 9);
        if (noise) {
            side.write(firstNoise, 9);
        }
        side.bit(escapes.length() > 0 ? 1 : 0);
        if (escapes.length() > 0) {
            side.write(escapes.length(), 8);
        }
        if (noise) {
            side.write(noiseEnergy - (lastScalefactor - 90 - 256), 9);
        }
        side.append(codewords);
        side.append(escapes);
        return new Coded(side.bytes(), types, values);
    }

    static void difference(Bits codewords, Bits escapes, int difference) {
        if (difference <= -7 || difference >= 7) {
            var code = CODES[difference < 0 ? 0 : 14];
            codewords.write(code[0], code[1]);
            var escape = ESCAPES[Math.abs(difference) - 7];
            escapes.write(escape[0], escape[1]);
            return;
        }
        var code = CODES[difference + 7];
        codewords.write(code[0], code[1]);
    }

    /** Decodes a coded stream with a decoder that may hold a previous frame. */
    static int[] decode(ReversibleScalefactors decoder, Coded coded) {
        var reader = new AacBitReader(coded.data());
        var types = new int[8 * ChannelStream.MAX_BANDS];
        System.arraycopy(coded.types(), 0, types, 0, coded.types().length);
        var noise = Arrays.stream(coded.types()).anyMatch(type -> type == Codebooks.NOISE_HCB);
        decoder.readSideInfo(reader, false, noise);
        var values = new int[8 * ChannelStream.MAX_BANDS];
        decoder.decode(reader, GLOBAL_GAIN, types, values, 1, coded.types().length);
        return Arrays.copyOf(values, coded.types().length);
    }

    static int[] scalefactorTypes(int bands) {
        var types = new int[bands];
        Arrays.fill(types, SCALEFACTOR);
        return types;
    }

    static final int[] VALUES = {100, 104, 97, 120, 118, 119, 90, 92, 92, 99};

    @Nested
    @DisplayName("without errors")
    class Clean {
        @Test
        @DisplayName("decode scalefactors with escapes exactly, without concealment")
        void scalefactors() {
            var decoder = new ReversibleScalefactors();
            assertArrayEquals(VALUES, decode(decoder, encode(scalefactorTypes(10), VALUES, null, true)));
            assertFalse(decoder.concealed());
        }

        @Test
        @DisplayName("decode noise energies, intensity positions and zero bands exactly")
        void mixed() {
            int[] types = {SCALEFACTOR, Codebooks.NOISE_HCB, Codebooks.ZERO_HCB, Codebooks.NOISE_HCB, SCALEFACTOR,
                    Codebooks.INTENSITY_HCB, Codebooks.INTENSITY_HCB, SCALEFACTOR};
            int[] values = {101, -20, 0, -9, 95, 3, -12, 96};
            var decoder = new ReversibleScalefactors();
            assertArrayEquals(values, decode(decoder, encode(types, values, null, true)));
            assertFalse(decoder.concealed());
        }
    }

    @Nested
    @DisplayName("with corrupt codewords")
    class Corrupt {
        @Test
        @DisplayName("recover every value when one codeword fails to decode in both directions")
        void oneInvalid() {
            var corrupt = new int[10];
            corrupt[5] = 1;
            var decoder = new ReversibleScalefactors();
            assertArrayEquals(VALUES, decode(decoder, encode(scalefactorTypes(10), VALUES, corrupt, true)));
            assertTrue(decoder.concealed());
        }

        @Test
        @DisplayName("fill the bands between two invalid codewords with the quieter of the nearest decoded values")
        void gap() {
            var corrupt = new int[10];
            corrupt[3] = 1;
            corrupt[7] = 1;
            var decoder = new ReversibleScalefactors();
            var decoded = decode(decoder, encode(scalefactorTypes(10), VALUES, corrupt, true));
            // forward stops at band 3 (bands 0 to 2 decoded), backward at band 7 (bands 7 to 9 decoded)
            var fill = Math.min(VALUES[2], VALUES[7]);
            assertArrayEquals(new int[]{100, 104, 97, fill, fill, fill, fill, 92, 92, 99}, decoded);
            assertTrue(decoder.concealed());
        }

        @Test
        @DisplayName("fill the bands between two invalid codewords from the previous frame when it decoded cleanly")
        void gapFromPrevious() {
            int[] previous = {98, 101, 99, 110, 111, 112, 95, 94, 93, 97};
            var corrupt = new int[10];
            corrupt[3] = 1;
            corrupt[7] = 1;
            var decoder = new ReversibleScalefactors();
            decode(decoder, encode(scalefactorTypes(10), previous, null, true));
            var decoded = decode(decoder, encode(scalefactorTypes(10), VALUES, corrupt, true));
            assertArrayEquals(new int[]{100, 104, 97, 110, 111, 112, 95, 92, 92, 99}, decoded);
        }

        @Test
        @DisplayName("ignore the previous frame when sf_concealment forbids it")
        void previousForbidden() {
            int[] previous = {98, 101, 99, 110, 111, 112, 95, 94, 93, 97};
            var corrupt = new int[10];
            corrupt[3] = 1;
            corrupt[7] = 1;
            var decoder = new ReversibleScalefactors();
            decode(decoder, encode(scalefactorTypes(10), previous, null, true));
            var decoded = decode(decoder, encode(scalefactorTypes(10), VALUES, corrupt, false));
            var fill = Math.min(VALUES[2], VALUES[7]);
            assertArrayEquals(new int[]{100, 104, 97, fill, fill, fill, fill, 92, 92, 99}, decoded);
        }

        @Test
        @DisplayName("take the direction whose values sum lower when a wrong but valid codeword shifts every later value")
        void statistical() {
            var corrupt = new int[10];
            corrupt[5] = 1 - 2;
            var decoder = new ReversibleScalefactors();
            var decoded = decode(decoder, encode(scalefactorTypes(10), VALUES, corrupt, true));
            // forward: bands 5 to 9 two lower; backward: bands 0 to 4 two higher; forward sums lower
            var expected = VALUES.clone();
            for (var p = 5; p < 10; p++) {
                expected[p] -= 2;
            }
            assertArrayEquals(expected, decoded);
            assertTrue(decoder.concealed());
        }

        @Test
        @DisplayName("take the quietest of both directions and the previous frame when a valid codeword is wrong")
        void predictive() {
            var corrupt = new int[10];
            corrupt[5] = 1 + 2;
            var decoder = new ReversibleScalefactors();
            decode(decoder, encode(scalefactorTypes(10), VALUES, null, true));
            var decoded = decode(decoder, encode(scalefactorTypes(10), VALUES, corrupt, true));
            // forward: bands 5 to 9 two higher; backward: bands 0 to 4 two lower; the previous frame is exact
            var expected = VALUES.clone();
            for (var p = 0; p < 5; p++) {
                expected[p] -= 2;
            }
            assertArrayEquals(expected, decoded);
        }

        @Test
        @DisplayName("conceal noise and intensity bands with their own kind, the larger intensity position being quieter")
        void kinds() {
            int[] types = {SCALEFACTOR, Codebooks.NOISE_HCB, Codebooks.NOISE_HCB, Codebooks.INTENSITY_HCB,
                    Codebooks.INTENSITY_HCB, SCALEFACTOR, Codebooks.NOISE_HCB, Codebooks.INTENSITY_HCB, SCALEFACTOR};
            int[] values = {101, -20, -14, 4, 10, 95, -30, 2, 96};
            var corrupt = new int[types.length];
            corrupt[2] = 1;
            corrupt[7] = 1;
            var decoder = new ReversibleScalefactors();
            var decoded = decode(decoder, encode(types, values, corrupt, true));
            // forward decodes bands 0 and 1, backward bands 7 and 8 (band 7's value precedes its codeword)
            assertArrayEquals(new int[]{101, -20, Math.min(-20, -30), Math.max(0, 2), Math.max(0, 2), Math.min(101, 96),
                    Math.min(-20, -30), 2, 96}, decoded);
        }
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4})
    @DisplayName("decode random payloads without failing, every value in range")
    void fuzz(int seed) {
        var random = new Random(seed);
        var decoder = new ReversibleScalefactors();
        for (var round = 0; round < 2000; round++) {
            var bands = 1 + random.nextInt(49);
            var types = new int[8 * ChannelStream.MAX_BANDS];
            for (var p = 0; p < bands; p++) {
                types[p] = switch (random.nextInt(6)) {
                    case 0 -> Codebooks.ZERO_HCB;
                    case 1 -> Codebooks.NOISE_HCB;
                    case 2 -> Codebooks.INTENSITY_HCB;
                    default -> 1 + random.nextInt(11);
                };
            }
            var data = new byte[64 + random.nextInt(64)];
            random.nextBytes(data);
            var reader = new AacBitReader(data);
            var noise = Arrays.stream(types, 0, bands).anyMatch(type -> type == Codebooks.NOISE_HCB);
            decoder.readSideInfo(reader, random.nextBoolean(), noise);
            var values = new int[8 * ChannelStream.MAX_BANDS];
            decoder.decode(reader, random.nextInt(256), types, values, 1, bands);
            for (var p = 0; p < bands; p++) {
                var value = values[p];
                switch (types[p]) {
                    case Codebooks.ZERO_HCB -> assertEquals(0, value);
                    case Codebooks.NOISE_HCB -> assertTrue(value >= Dequantizer.MIN_EXPONENT
                            && value <= Dequantizer.MAX_EXPONENT);
                    case Codebooks.INTENSITY_HCB -> assertTrue(-value >= Dequantizer.MIN_EXPONENT
                            && -value <= Dequantizer.MAX_EXPONENT);
                    default -> assertTrue(value >= 0 && value <= 255, "scalefactor " + value);
                }
            }
        }
    }
}
