package com.github.auties00.smalt.audio.aac.internal.ep;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the codes of the MPEG-4 audio error protection tool one by one: the CRCs against published check values,
 * the header block codes with every correctable error pattern, the Reed-Solomon codes and the SRCPC Viterbi decoder
 * against random errors within and beyond their capability, and the recursive interleaver against the closed form
 * of ISO/IEC 14496-3 subclause 1.8.4.8.1.
 */
class ErrorProtectionCodesTest {
    private static byte[] bitsOf(byte[] bytes) {
        return EpFrameEncoder.unpack(bytes, bytes.length * 8);
    }

    @Nested
    @DisplayName("CRC")
    class Crc {
        @Test
        @DisplayName("computes the CRC-16/XMODEM check value and the zero-start form of the 32-bit polynomial")
        void checkValues() {
            var data = bitsOf("123456789".getBytes(StandardCharsets.US_ASCII));
            // CRC-16/XMODEM is x^16+x^12+x^5+1 unreflected from a zero register: 0x31C3; the 32-bit polynomial of
            // Table 1.56 from a zero register, unreflected and not inverted, gives 0x89A1897F
            assertEquals(0x31C3, EpCrc.remainder(data, 0, data.length, 16));
            assertEquals(0x89A1897FL, EpCrc.remainder(data, 0, data.length, 32));
        }

        @Test
        @DisplayName("sends the inverted check, lowest order first, and detects every single bit error")
        void transmitted() {
            var random = new Random(1);
            for (var length : new int[]{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 24, 32}) {
                var data = new byte[40 + length];
                for (var i = 0; i < 40; i++) {
                    data[i] = (byte) random.nextInt(2);
                }
                EpCrc.write(data, 0, 40, length, data, 40);
                var remainder = EpCrc.remainder(data, 0, 40, length);
                for (var i = 0; i < length; i++) {
                    assertEquals(((remainder >>> i) & 1) ^ 1, data[40 + i], "bit " + i + " of CRC" + length);
                }
                assertTrue(EpCrc.matches(data, 0, 40, length));
                for (var i = 0; i < data.length; i++) {
                    data[i] ^= 1;
                    assertFalse(EpCrc.matches(data, 0, 40, length), "CRC" + length + " misses an error at " + i);
                    data[i] ^= 1;
                }
            }
            assertEquals(-1, EpCrc.lengthOf(19));
            assertEquals(32, EpCrc.lengthOf(18));
            assertEquals(24, EpCrc.lengthOf(17));
        }
    }

    @Nested
    @DisplayName("header block codes")
    class Header {
        private static int bound(HeaderCode code) {
            return switch (code) {
                case BCH_7_4 -> 4;
                case BCH_15_7 -> 7;
                case GOLAY_23_12 -> 12;
                case BCH_31_16 -> 16;
            };
        }

        private static int correctable(HeaderCode code) {
            return switch (code) {
                case BCH_7_4 -> 1;
                case BCH_15_7 -> 2;
                default -> 3;
            };
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(HeaderCode.class)
        @DisplayName("corrects every error pattern of up to t bits of every shortened length")
        void correctsEveryPattern(HeaderCode code) {
            var random = new Random(code.ordinal());
            var lowest = switch (code) {
                case BCH_7_4 -> 3;
                case BCH_15_7 -> 5;
                case GOLAY_23_12 -> 8;
                case BCH_31_16 -> 13;
            };
            for (var bits = lowest; bits <= bound(code); bits++) {
                assertEquals(code, HeaderCode.forBits(bits));
                var length = bits + code.parityBits();
                var word = new byte[length];
                for (var i = 0; i < bits; i++) {
                    word[i] = (byte) random.nextInt(2);
                }
                code.encode(word, 0, bits, word, bits);
                var clean = word.clone();
                assertTrue(code.decode(word, 0, bits));
                assertArrayEquals(clean, word);
                var t = correctable(code);
                for (var a = 0; a < length; a++) {
                    for (var b = t >= 2 ? a + 1 : length; b <= length; b++) {
                        for (var c = t >= 3 && b < length ? b + 1 : length; c <= length; c++) {
                            var damaged = clean.clone();
                            damaged[a] ^= 1;
                            if (b < length) {
                                damaged[b] ^= 1;
                            }
                            if (c < length) {
                                damaged[c] ^= 1;
                            }
                            assertTrue(code.decode(damaged, 0, bits), code + " " + bits + " bits, errors " + a + " " + b
                                    + " " + c);
                            assertArrayEquals(clean, damaged, code + " " + bits + " bits, errors " + a + " " + b + " "
                                    + c);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("matches the generator polynomials of ISO/IEC 14496-3 subclause 1.8.4.3")
        void generators() {
            // the generator itself, shifted into the information bits, leaves a zero syndrome: a single information
            // bit followed by the generator's low terms
            for (var code : HeaderCode.values()) {
                var bits = bound(code);
                var info = new byte[bits];
                info[bits - 1] = 1;
                var parity = new byte[code.parityBits()];
                code.encode(info, 0, bits, parity, 0);
                var expected = switch (code) {
                    case BCH_7_4 -> 0b011;
                    case BCH_15_7 -> 0b11010001;
                    case GOLAY_23_12 -> 0b01011100011;
                    case BCH_31_16 -> 0b000111110101111;
                };
                var actual = 0;
                for (var bit : parity) {
                    actual = (actual << 1) | bit;
                }
                assertEquals(expected, actual, code.toString());
            }
        }
    }

    @Nested
    @DisplayName("Reed-Solomon")
    class Rs {
        @ParameterizedTest(name = "k = {0}")
        @ValueSource(ints = {1, 2, 3, 8, 9, 20, 40, 127})
        @DisplayName("corrects up to k symbol errors anywhere in a shortened codeword and reports more")
        void corrects(int k) {
            var random = new Random(k);
            var decoder = new ReedSolomon();
            for (var trial = 0; trial < 40; trial++) {
                var count = 1 + random.nextInt(255 - 2 * k);
                var word = new int[count + 2 * k];
                for (var i = 0; i < count; i++) {
                    word[i] = random.nextInt(256);
                }
                ReedSolomon.encode(word, 0, count, k);
                var clean = word.clone();
                assertEquals(0, decoder.decode(word, 0, word.length, k));
                var errors = 1 + random.nextInt(k);
                var damaged = clean.clone();
                for (var e = 0; e < errors; e++) {
                    int position;
                    do {
                        position = random.nextInt(word.length);
                    } while (damaged[position] != clean[position]);
                    damaged[position] ^= 1 + random.nextInt(255);
                }
                assertEquals(errors, decoder.decode(damaged, 0, damaged.length, k), "trial " + trial);
                assertArrayEquals(clean, damaged, "trial " + trial);
                if (word.length > 2 * k + 2) {
                    var heavy = clean.clone();
                    for (var e = 0; e < Math.min(word.length, 2 * k + 2); e++) {
                        heavy[e] ^= 1 + random.nextInt(255);
                    }
                    var copy = heavy.clone();
                    var result = decoder.decode(heavy, 0, heavy.length, k);
                    if (result < 0) {
                        assertArrayEquals(copy, heavy, "an uncorrectable word is left alone");
                    }
                    assertFalse(result >= 0 && Arrays.equals(heavy, clean), "beyond capability, trial " + trial);
                }
            }
        }
    }

    @Nested
    @DisplayName("SRCPC")
    class Convolutional {
        private static void encode(byte[] info, int rate, boolean terminate, byte[] received, byte[] tails) {
            var state = 0;
            var steps = info.length + (terminate ? 4 : 0);
            for (var t = 0; t < steps; t++) {
                var next = Srcpc.step(state, t < info.length ? info[t] : -1);
                tails[t] = (byte) (t < info.length ? 0 : 1);
                for (var j = 0; j < 4; j++) {
                    received[t * 4 + j] = j == 0 || Srcpc.sent(rate, j - 1, t) ? (byte) ((next >>> (3 - j)) & 1)
                            : -1;
                }
                state = next >>> 4;
            }
            if (terminate) {
                assertEquals(0, state);
            }
        }

        @ParameterizedTest(name = "rate 8/{0}")
        @ValueSource(ints = {12, 14, 16, 20, 24, 32})
        @DisplayName("corrects isolated errors from rate 8/12 on")
        void viterbi(int denominator) {
            var rate = denominator - 8;
            var random = new Random(rate);
            var decoder = new Srcpc();
            for (var terminate : new boolean[]{false, true}) {
                var info = new byte[300];
                for (var i = 0; i < info.length; i++) {
                    info[i] = (byte) random.nextInt(2);
                }
                var steps = info.length + (terminate ? 4 : 0);
                var received = new byte[steps * 4];
                var tails = new byte[steps];
                encode(info, rate, terminate, received, tails);
                var decoded = new byte[steps];
                decoder.decode(received, steps, tails, terminate, decoded);
                assertArrayEquals(info, Arrays.copyOf(decoded, info.length));
                // one systematic error every 40 steps, far apart for a memory of four
                for (var t = 5; t < info.length - 5; t += 40) {
                    received[t * 4] ^= 1;
                }
                decoder.decode(received, steps, tails, terminate, decoded);
                assertArrayEquals(info, Arrays.copyOf(decoded, info.length), "rate 8/" + denominator);
            }
        }

        @Test
        @DisplayName("decodes clean sequences at every rate, punctured or not")
        void clean() {
            var random = new Random(12);
            var decoder = new Srcpc();
            for (var rate = 0; rate <= Srcpc.MAX_RATE; rate++) {
                var info = new byte[200];
                for (var i = 0; i < info.length; i++) {
                    info[i] = (byte) random.nextInt(2);
                }
                var received = new byte[204 * 4];
                var tails = new byte[204];
                encode(info, rate, true, received, tails);
                var decoded = new byte[204];
                decoder.decode(received, 204, tails, true, decoded);
                assertArrayEquals(info, Arrays.copyOf(decoded, info.length), "rate index " + rate);
            }
        }

        @Test
        @DisplayName("punctures with Table 1.61 and keeps every systematic bit")
        void puncturing() {
            assertEquals(1, Srcpc.width(0, 3));
            assertEquals(2, Srcpc.width(8, 3));
            assertEquals(4, Srcpc.width(24, 5));
            var total = 0;
            for (var t = 0; t < 8; t++) {
                total += Srcpc.width(4, t);
            }
            // 8/12: four parity bits per period of eight
            assertEquals(12, total);
            assertTrue(Srcpc.sent(1, 0, 0));
            assertFalse(Srcpc.sent(1, 0, 1));
        }
    }

    @Nested
    @DisplayName("recursive interleaver")
    class Interleaving {
        @Test
        @DisplayName("places X as k = (i mod W) D + min(i mod W, d) + i / W and fills Y column by column")
        void closedForm() {
            var random = new Random(3);
            for (var trial = 0; trial < 300; trial++) {
                var width = 1 + random.nextInt(40);
                var xLength = 1 + random.nextInt(200);
                var yLength = 1 + random.nextInt(300);
                var total = xLength + yLength;
                var x = new byte[xLength];
                var y = new byte[yLength];
                for (var i = 0; i < xLength; i++) {
                    x[i] = (byte) (i % 2);
                }
                var out = new byte[total];
                var marked = new byte[xLength];
                Arrays.fill(marked, (byte) 1);
                Interleaver.join(marked, 0, xLength, y, 0, yLength, width, out, 0);
                var rows = total / width;
                var longColumns = total - rows * width;
                for (var i = 0; i < xLength; i++) {
                    var k = (i % width) * rows + Math.min(i % width, longColumns) + i / width;
                    assertEquals(1, out[k], "trial " + trial + " bit " + i);
                }
                for (var i = 0; i < yLength; i++) {
                    y[i] = (byte) random.nextInt(2);
                }
                Interleaver.join(x, 0, xLength, y, 0, yLength, width, out, 0);
                var xBack = new byte[xLength];
                var yBack = new byte[yLength];
                Interleaver.split(out, 0, total, xLength, width, xBack, 0, yBack, 0);
                assertArrayEquals(x, xBack);
                assertArrayEquals(y, yBack);
            }
        }

        @Test
        @DisplayName("writes a Reed-Solomon class one byte per column and skips its padding cells")
        void bytewise() {
            var random = new Random(4);
            for (var trial = 0; trial < 200; trial++) {
                var information = 1 + random.nextInt(400);
                var parity = 8 * 2 * (1 + random.nextInt(5));
                var yLength = 1 + random.nextInt(500);
                var x = new byte[information + parity];
                var y = new byte[yLength];
                for (var i = 0; i < x.length; i++) {
                    x[i] = (byte) random.nextInt(2);
                }
                for (var i = 0; i < y.length; i++) {
                    y[i] = (byte) random.nextInt(2);
                }
                var out = new byte[x.length + yLength];
                Interleaver.joinBytes(x, 0, information, parity, y, 0, yLength, out, 0);
                var xBack = new byte[x.length];
                var yBack = new byte[yLength];
                Interleaver.splitBytes(out, 0, out.length, information, parity, xBack, 0, yBack, 0);
                assertArrayEquals(x, xBack);
                assertArrayEquals(y, yBack);
            }
            // two information bytes and two parity bytes over eight Y bits: 4 columns of 10 rows minus 0 padding
            var x = new byte[32];
            Arrays.fill(x, (byte) 1);
            var out = new byte[40];
            Interleaver.joinBytes(x, 0, 16, 16, new byte[8], 0, 8, out, 0);
            for (var column = 0; column < 4; column++) {
                for (var row = 0; row < 10; row++) {
                    assertEquals(row < 8 ? 1 : 0, out[column * 10 + row], "column " + column + " row " + row);
                }
            }
        }
    }
}
