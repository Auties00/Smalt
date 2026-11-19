package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.h264.H264BitWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks the CAVLC parsing of 4:2:2 chroma DC blocks ({@code nC == -2}, {@code maxNumCoeff == 8})
 * against blocks written with an encoder that spells out the codes of ITU-T H.264 Table 9-5 (column
 * {@code nC == -2}), Table 9-9 (b) and Table 9-10 as bit strings copied from the Recommendation.
 */
@DisplayName("H.264 CAVLC 4:2:2 chroma DC blocks")
class H264Chroma422CavlcTest {
    /**
     * The {@code coeff_token} codes of Table 9-5 for {@code nC == -2}, indexed
     * {@code [trailingOnes][totalCoeff]}, {@code null} where no code exists.
     */
    private static final String[][] COEFF_TOKEN = {
            {"1", "0001111", "0001110", "000000111", "000000110", "0000000111", "00000000111", "000000000111",
                    "0000000000111"},
            {null, "01", "0001101", "0001100", "000000101", "0000000110", "00000000110", "000000000110",
                    "000000000101"},
            {null, null, "001", "0001011", "0001010", "000000100", "0000000101", "00000000101", "000000000100"},
            {null, null, null, "00001", "000001", "0001001", "0001000", "0000000100", "00000000100"}
    };

    /**
     * The {@code total_zeros} codes of Table 9-9 (b), indexed {@code [tzVlcIndex - 1][total_zeros]}.
     */
    private static final String[][] TOTAL_ZEROS = {
            {"1", "010", "011", "0010", "0011", "0001", "00001", "00000"},
            {"000", "01", "001", "100", "101", "110", "111"},
            {"000", "001", "01", "10", "110", "111"},
            {"110", "00", "01", "10", "111"},
            {"00", "01", "10", "11"},
            {"00", "01", "1"},
            {"0", "1"}
    };

    /**
     * The {@code run_before} codes of Table 9-10, indexed {@code [min(zerosLeft, 7) - 1][run_before]}.
     */
    private static final String[][] RUN_BEFORE = {
            {"1", "0"},
            {"1", "01", "00"},
            {"11", "10", "01", "00"},
            {"11", "10", "01", "001", "000"},
            {"11", "10", "011", "010", "001", "000"},
            {"11", "000", "001", "011", "010", "101", "100"},
            {"111", "110", "101", "100", "011", "010", "001", "0001", "00001", "000001", "0000001", "00000001",
                    "000000001", "0000000001", "00000000001"}
    };

    /**
     * Writes one {@code residual_block_cavlc()} of 8 coefficients (ITU-T H.264 7.3.5.3.2 and 9.2).
     */
    private static void write(H264BitWriter writer, int[] coefficients) {
        var total = 0;
        var last = -1;
        for (var i = 0; i < coefficients.length; i++) {
            if (coefficients[i] != 0) {
                total++;
                last = i;
            }
        }
        var levels = new int[total];
        var runs = new int[total];
        var k = 0;
        var run = 0;
        for (var i = last; i >= 0; i--) {
            if (coefficients[i] == 0) {
                run++;
                continue;
            }
            if (k > 0) {
                runs[k - 1] = run;
            }
            levels[k++] = coefficients[i];
            run = 0;
        }
        if (total > 0) {
            runs[total - 1] = run;
        }
        var trailingOnes = 0;
        while (trailingOnes < Math.min(3, total) && Math.abs(levels[trailingOnes]) == 1) {
            trailingOnes++;
        }
        bits(writer, COEFF_TOKEN[trailingOnes][total]);
        if (total == 0) {
            return;
        }
        for (var i = 0; i < trailingOnes; i++) {
            writer.bit(levels[i] < 0 ? 1 : 0);
        }
        var suffixLength = total > 10 && trailingOnes < 3 ? 1 : 0;
        for (var i = trailingOnes; i < total; i++) {
            var level = levels[i];
            var levelCode = level > 0 ? 2 * level - 2 : -2 * level - 1;
            if (i == trailingOnes && trailingOnes < 3) {
                levelCode -= 2;
            }
            if (suffixLength == 0) {
                if (levelCode < 14) {
                    prefix(writer, levelCode);
                } else if (levelCode < 30) {
                    prefix(writer, 14);
                    writer.bits(levelCode - 14, 4);
                } else {
                    prefix(writer, 15);
                    writer.bits(levelCode - 30, 12);
                }
            } else if (levelCode < (15 << suffixLength)) {
                prefix(writer, levelCode >> suffixLength);
                writer.bits(levelCode & ((1 << suffixLength) - 1), suffixLength);
            } else {
                prefix(writer, 15);
                writer.bits(levelCode - (15 << suffixLength), 12);
            }
            if (suffixLength == 0) {
                suffixLength = 1;
            }
            if (Math.abs(level) > (3 << (suffixLength - 1)) && suffixLength < 6) {
                suffixLength++;
            }
        }
        var zerosLeft = last + 1 - total;
        if (total < coefficients.length) {
            bits(writer, TOTAL_ZEROS[total - 1][zerosLeft]);
        }
        for (var i = 0; i < total - 1 && zerosLeft > 0; i++) {
            bits(writer, RUN_BEFORE[Math.min(zerosLeft, 7) - 1][runs[i]]);
            zerosLeft -= runs[i];
        }
    }

    private static void prefix(H264BitWriter writer, int zeros) {
        for (var i = 0; i < zeros; i++) {
            writer.bit(0);
        }
        writer.bit(1);
    }

    private static void bits(H264BitWriter writer, String code) {
        for (var i = 0; i < code.length(); i++) {
            writer.bit(code.charAt(i) - '0');
        }
    }

    private static H264BitReader reader(byte[] payload) {
        var reader = new H264BitReader();
        reader.init(Arrays.copyOf(payload, payload.length + H264BitReader.PADDING), 0, payload.length);
        return reader;
    }

    private static int[] decode(CavlcReader cavlc, H264BitReader reader) {
        var coefficients = new int[8];
        cavlc.residualBlock(reader, -2, 8, coefficients, 0, H264Tables.CHROMA_DC_SCAN, 0, 1, 0, null, 0);
        return coefficients;
    }

    @Test
    @DisplayName("decodes every coeff_token, total_zeros and run_before combination of 8-coefficient blocks")
    void everyPattern() {
        var cavlc = new CavlcReader();
        var random = new Random(422);
        var blocks = 0;
        for (var mask = 0; mask < 256; mask++) {
            for (var variant = 0; variant < 6; variant++) {
                var coefficients = new int[8];
                for (var i = 0; i < 8; i++) {
                    if ((mask & (1 << i)) == 0) {
                        continue;
                    }
                    var magnitude = switch (variant) {
                        case 0 -> 1;
                        case 1 -> 1 + random.nextInt(2);
                        case 2 -> 1 + random.nextInt(8);
                        case 3 -> 1 + random.nextInt(40);
                        case 4 -> 1 + random.nextInt(2000);
                        default -> random.nextInt(4) == 0 ? 1 + random.nextInt(2000) : 1;
                    };
                    coefficients[i] = random.nextBoolean() ? magnitude : -magnitude;
                }
                var writer = new H264BitWriter();
                write(writer, coefficients);
                writer.bits(0b1011, 4);
                var reader = reader(writer.trailing());
                assertArrayEquals(coefficients, decode(cavlc, reader), "mask " + mask + " variant " + variant);
                assertEquals(0b1011, reader.readBits(4), "bits after the block of mask " + mask);
                blocks++;
            }
        }
        assertEquals(256 * 6, blocks);
    }

    @Test
    @DisplayName("decodes consecutive blocks without losing bit alignment")
    void consecutive() {
        var cavlc = new CavlcReader();
        var random = new Random(7);
        var expected = new int[64][];
        var writer = new H264BitWriter();
        for (var b = 0; b < expected.length; b++) {
            var coefficients = new int[8];
            for (var i = 0; i < 8; i++) {
                if (random.nextInt(3) == 0) {
                    coefficients[i] = random.nextInt(21) - 10;
                }
            }
            expected[b] = coefficients;
            write(writer, coefficients);
        }
        var reader = reader(writer.trailing());
        for (var b = 0; b < expected.length; b++) {
            assertArrayEquals(expected[b], decode(cavlc, reader), "block " + b);
        }
    }

    @Test
    @DisplayName("rejects bit patterns that start no 4:2:2 chroma DC coeff_token")
    void invalidToken() {
        var cavlc = new CavlcReader();
        // no code of the nC == -2 column starts with more than ten zero bits
        var reader = reader(new byte[]{0, 0, 0x07, (byte) 0xFF});
        assertThrows(MediaException.Malformed.class, () -> decode(cavlc, reader));
    }
}
