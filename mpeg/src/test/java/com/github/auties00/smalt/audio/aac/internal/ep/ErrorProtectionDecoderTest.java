package com.github.auties00.smalt.audio.aac.internal.ep;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.aac.internal.ep.EpConfigWriter.Set;
import com.github.auties00.smalt.audio.aac.internal.ep.EpConfigWriter.Spec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round trips frames of every part of the error protection tool through {@link EpFrameEncoder} and
 * {@link ErrorProtectionDecoder}: the interleaving types and switches, SRCPC with and without termination at every
 * rate, CRCs of every length, Reed-Solomon codes alone and concatenated, optional classes, reordered output, in-band
 * escapes, the basic and the extended header protection and concatenated frames. Damaged frames must be corrected
 * where the codes allow it and reported as not intact otherwise, never failing the decoder.
 */
class ErrorProtectionDecoderTest {
    /** A named configuration with the in-band rate and CRC codes its escaped classes use. */
    record Case(String name, byte[] config, int[] rateCodes, int[] crcCodes) {
        @Override
        public String toString() {
            return name;
        }
    }

    private static Case of(String name, List<Set> sets, int interleave, int concatenated, int headerRate,
                           int headerCrc) {
        return new Case(name, EpConfigWriter.write(sets, interleave, true, concatenated, headerRate, headerCrc),
                new int[]{3, 5, 1, 7, 2, 4, 6, 0}, new int[]{2, 4, 1, 6, 3, 5, 7, 0});
    }

    static Stream<Case> cases() {
        var cases = new ArrayList<Case>();
        // the ER AAC LC layout of er_al10: a Reed-Solomon class without parity, an optional TNS class, the spectrum
        cases.add(of("crc only, no interleaving", EpConfigWriter.sets(new Set(List.of(Spec.reedSolomon(10, 1, 0, 1),
                Spec.srcpc(10, 0, 2).asOptional(), Spec.reedSolomon(0, 1, 0, 0)), null)), 0, 1, -1, 0));
        for (var rate = 0; rate <= 24; rate += 4) {
            cases.add(of("srcpc 8/" + (8 + rate) + " class-wise", EpConfigWriter.sets(new Set(List.of(
                    Spec.srcpc(10, rate, 15).interleaved(2), Spec.srcpc(10, 0, 16).asOptional(),
                    Spec.srcpc(0, 0, 0).interleaved(1)), null)), 2, 1, -1, 0));
        }
        cases.add(of("terminated srcpc, intra-frame", EpConfigWriter.sets(new Set(List.of(
                Spec.srcpc(8, 11, 9).terminated(), Spec.srcpc(10, 10, 8).terminated(), Spec.srcpc(10, 0, 7).asOptional(),
                Spec.srcpc(0, 3, 5).terminated()), null)), 1, 1, -1, 0));
        cases.add(of("concatenated Reed-Solomon, reordered", EpConfigWriter.sets(new Set(List.of(
                Spec.reedSolomon(8, 2, 9, 9).interleaved(1), Spec.reedSolomon(10, 2, 9, 8).interleaved(1),
                Spec.srcpc(10, 0, 7).asOptional(), Spec.srcpc(14, 0, 0).interleaved(3),
                Spec.reedSolomon(10, 1, 9, 6).interleaved(1), Spec.srcpc(10, 0, 5).asOptional(),
                Spec.srcpc(0, 0, 0).interleaved(1)), new int[]{0, 1, 4, 2, 5, 3, 6})), 2, 1, -1, 0));
        cases.add(of("extended header protection", EpConfigWriter.sets(new Set(List.of(Spec.srcpc(10, 0, 3),
                Spec.reedSolomon(10, 1, 0, 0).interleaved(1), Spec.srcpc(10, 0, 4).asOptional(),
                Spec.reedSolomon(14, 1, 0, 0)), null)), 2, 1, 24, 6));
        cases.add(of("in-band rates and crcs, two sets", EpConfigWriter.sets(
                new Set(List.of(Spec.srcpc(9, 8, 6).escaped(true, true), Spec.srcpc(11, 0, 0).escaped(false, true),
                        Spec.srcpc(0, 0, 0).escaped(true, false)), null),
                new Set(List.of(Spec.fixed(20, 0, 16, 6), Spec.srcpc(0, 4, 3)), null)), 0, 1, -1, 0));
        cases.add(of("mode 2 with every switch", EpConfigWriter.sets(new Set(List.of(
                Spec.srcpc(9, 16, 6).interleaved(2), Spec.srcpc(9, 8, 6).interleaved(1), Spec.srcpc(9, 0, 6),
                Spec.srcpc(9, 0, 0).interleaved(3), Spec.srcpc(0, 2, 4).interleaved(1)), null)), 2, 1, -1, 0));
        cases.add(of("three concatenated frames", EpConfigWriter.sets(new Set(List.of(
                Spec.fixed(13, 0, 12, 8).concatenated(), Spec.fixed(29, 0, 4, 6), Spec.fixed(64, 1, 3, 10),
                Spec.fixed(40, 0, 0, 0)), null)), 1, 3, -1, 0));
        cases.add(of("Reed-Solomon into the class until the end", EpConfigWriter.sets(new Set(List.of(
                Spec.srcpc(9, 8, 6).terminated(), Spec.reedSolomon(10, 2, 4, 8),
                Spec.reedSolomon(0, 1, 4, 6)), null)), 0, 1, -1, 0));
        cases.add(of("Reed-Solomon without parity into the class until the end, interleaved",
                EpConfigWriter.sets(new Set(List.of(Spec.srcpc(10, 0, 5).asOptional().interleaved(2),
                        Spec.reedSolomon(10, 2, 0, 8).interleaved(1), Spec.reedSolomon(10, 2, 0, 0).interleaved(1),
                        Spec.reedSolomon(0, 1, 0, 6).interleaved(1)), null)), 2, 1, -1, 0));
        cases.add(of("Reed-Solomon of several parts into the class until the end", EpConfigWriter.sets(new Set(
                List.of(Spec.fixed(2000, 2, 3, 9), Spec.reedSolomon(0, 1, 3, 4)), null)), 1, 1, -1, 0));
        // the layout of er_ac311 layer 1: an absent optional class of fec_type 1 ends a continuing Reed-Solomon code
        cases.add(of("Reed-Solomon ended by an absent optional class", EpConfigWriter.sets(new Set(List.of(
                Spec.reedSolomon(10, 2, 3, 8), Spec.reedSolomon(10, 1, 3, 6).asOptional(),
                Spec.srcpc(0, 5, 9)), null)), 0, 1, -1, 0));
        // the layout of er_ac211 layer 1: an absent optional class of fec_type 2 passes the code on
        cases.add(of("Reed-Solomon continued past an absent optional class", EpConfigWriter.sets(new Set(List.of(
                Spec.reedSolomon(10, 2, 5, 7), Spec.reedSolomon(10, 2, 5, 6).asOptional(),
                Spec.reedSolomon(10, 1, 5, 5), Spec.srcpc(0, 8, 4)), null)), 0, 1, -1, 0));
        // the enhancement layers of er_ce06: a pre-defined set without classes
        cases.add(of("a pre-defined set without classes", EpConfigWriter.sets(new Set(List.of(), null),
                new Set(List.of(Spec.srcpc(8, 8, 5), Spec.srcpc(0, 0, 0)), null)), 0, 1, -1, 0));
        cases.add(of("many optional classes", EpConfigWriter.sets(new Set(List.of(
                Spec.srcpc(8, 8, 8), Spec.srcpc(8, 0, 6).asOptional(), Spec.srcpc(8, 4, 6).asOptional(),
                Spec.srcpc(8, 0, 0).asOptional(), Spec.srcpc(0, 0, 0)), null)), 0, 1, -1, 0));
        return cases.stream();
    }

    /** Draws random classes for a pre-defined set, honoring fixed lengths and length field widths. */
    private static byte[][][] classes(ErrorProtectionConfig config, long choice, Random random) {
        var specs = config.classes(config.originalSet(choice));
        var frames = config.concatenatedFrames();
        var out = new byte[frames][specs.length][];
        var order = new int[specs.length];
        var count = config.frameOrder(choice, order);
        var present = new boolean[specs.length];
        for (var j = 0; j < count; j++) {
            present[order[j]] = true;
        }
        for (var c = 0; c < specs.length; c++) {
            var spec = specs[c];
            int length;
            if (!present[c]) {
                length = 0;
            } else if (!spec.lengthEscape()) {
                length = spec.length();
            } else if (spec.lengthBits() == 0) {
                length = random.nextInt(4) == 0 ? 0 : 1 + random.nextInt(700);
            } else {
                length = random.nextInt(Math.min(1 << spec.lengthBits(), 400));
            }
            if (spec.fecType() != 0 && length % 8 != 0 && random.nextBoolean()) {
                length -= length % 8;
            }
            for (var f = 0; f < frames; f++) {
                out[f][c] = new byte[length];
                for (var i = 0; i < length; i++) {
                    out[f][c][i] = (byte) random.nextInt(2);
                }
            }
        }
        return out;
    }

    private static void assertDecoded(ErrorProtectionDecoder decoder, byte[][][] classes, long choice, String what) {
        assertEquals(choice, decoder.choice(), what);
        for (var f = 0; f < classes.length; f++) {
            assertTrue(decoder.intact(f), what + " frame " + f + " not intact");
            for (var c = 0; c < classes[f].length; c++) {
                assertEquals(classes[f][c].length, decoder.classBits(f, c), what + " class " + c + " length");
                var bits = EpFrameEncoder.unpack(decoder.classData(f, c), classes[f][c].length);
                assertArrayEquals(classes[f][c], bits, what + " frame " + f + " class " + c);
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("decodes every pre-defined set of random frames back to their classes")
    void roundTrip(Case test) {
        var config = EpConfigWriter.parse(test.config());
        var decoder = new ErrorProtectionDecoder(config, MediaLimits.defaults());
        var random = new Random(test.name().hashCode());
        for (var trial = 0; trial < 60; trial++) {
            var choice = random.nextLong(config.setCount());
            var classes = classes(config, choice, random);
            var frame = EpFrameEncoder.encode(config, choice, classes, test.rateCodes(), test.crcCodes());
            decoder.decode(frame, 0, frame.length);
            assertDecoded(decoder, classes, choice, test + " trial " + trial);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("reports damaged frames as not intact or corrects them, and never fails")
    void damage(Case test) {
        var config = EpConfigWriter.parse(test.config());
        var decoder = new ErrorProtectionDecoder(config, MediaLimits.defaults());
        var random = new Random(test.name().hashCode() + 1);
        for (var trial = 0; trial < 60; trial++) {
            var choice = random.nextLong(config.setCount());
            var classes = classes(config, choice, random);
            var frame = EpFrameEncoder.encode(config, choice, classes, test.rateCodes(), test.crcCodes());
            var damaged = frame.clone();
            for (var e = 0; e < 1 + random.nextInt(6); e++) {
                var bit = random.nextInt(damaged.length * 8);
                damaged[bit >>> 3] ^= (byte) (0x80 >>> (bit & 7));
            }
            assertDoesNotThrow(() -> decoder.decode(damaged, 0, damaged.length), test + " trial " + trial);
            var truncated = Arrays.copyOf(frame, random.nextInt(frame.length + 1));
            assertDoesNotThrow(() -> decoder.decode(truncated, 0, truncated.length), test + " trial " + trial);
            if (truncated.length < frame.length && truncated.length > 0) {
                for (var f = 0; f < config.concatenatedFrames(); f++) {
                    if (decoder.intact(f)) {
                        // a shorter frame may still be consistent only if every class still checks
                        assertTrue(decoder.classCount() > 0);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("forward error correction")
    class Correction {
        private static void flip(byte[] frame, int bit) {
            frame[bit >>> 3] ^= (byte) (0x80 >>> (bit & 7));
        }

        @Test
        @DisplayName("corrects single bit errors anywhere in a frame whose every part is protected")
        void everyPosition() {
            // header: BCH(31,16) for the choice-free attributes; classes: SRCPC 8/32 with a CRC each, terminated
            var config = EpConfigWriter.parse(EpConfigWriter.write(EpConfigWriter.sets(new Set(List.of(
                    Spec.srcpc(8, 24, 8).terminated(), Spec.srcpc(0, 24, 10).terminated()), null)), 0, true, 1, -1, 0));
            var decoder = new ErrorProtectionDecoder(config, MediaLimits.defaults());
            var random = new Random(7);
            var classes = classes(config, 0, random);
            classes[0][1] = Arrays.copyOf(classes[0][1], Math.max(8, classes[0][1].length));
            var frame = EpFrameEncoder.encode(config, 0, classes, null, null);
            // the stuffing bits at the end carry no information
            var protectedBits = frame.length * 8 - stuffing(config, frame);
            for (var bit = 0; bit < protectedBits; bit++) {
                var damaged = frame.clone();
                flip(damaged, bit);
                decoder.decode(damaged, 0, damaged.length);
                assertDecoded(decoder, classes, 0, "error at bit " + bit);
            }
        }

        private static int stuffing(ErrorProtectionConfig config, byte[] frame) {
            var decoder = new ErrorProtectionDecoder(config, MediaLimits.defaults());
            decoder.decode(frame, 0, frame.length);
            // the attributes are the 8-bit length of class 0 and the stuffing count; both classes run at 8/32 with
            // their CRC and four tail steps
            var header = EpFrameEncoder.codedHeaderLength(config, 8 + 3);
            var coded = 4 * (decoder.classBits(0, 0) + 8 + 4) + 4 * (decoder.classBits(0, 1) + 10 + 4);
            return frame.length * 8 - header - coded;
        }

        @Test
        @DisplayName("corrects as many byte errors as a Reed-Solomon class allows and no more")
        void reedSolomon() {
            var config = EpConfigWriter.parse(EpConfigWriter.write(EpConfigWriter.sets(new Set(List.of(
                    Spec.fixed(400, 1, 4, 8), Spec.fixed(64, 0, 0, 0)), null)), 0, true, 1, -1, 0));
            var decoder = new ErrorProtectionDecoder(config, MediaLimits.defaults());
            var random = new Random(9);
            var classes = classes(config, 0, random);
            var frame = EpFrameEncoder.encode(config, 0, classes, null, null);
            // one set and fixed lengths: the only attribute is the stuffing count, 3 bits under BCH(7,4)
            var start = 6;
            for (var errors = 1; errors <= 6; errors++) {
                var damaged = frame.clone();
                for (var e = 0; e < errors; e++) {
                    // one bit in each of the first bytes of the class
                    flip(damaged, start + 8 * e + random.nextInt(8));
                }
                decoder.decode(damaged, 0, damaged.length);
                if (errors <= 4) {
                    assertDecoded(decoder, classes, 0, errors + " byte errors");
                } else {
                    assertFalse(decoder.intact(0), errors + " byte errors must be detected");
                }
            }
        }

        @Test
        @DisplayName("corrects byte errors in a Reed-Solomon code that continues into the class until the end")
        void reedSolomonUntilTheEnd() {
            var config = EpConfigWriter.parse(EpConfigWriter.write(EpConfigWriter.sets(new Set(List.of(
                    Spec.fixed(400, 2, 4, 8), Spec.reedSolomon(0, 1, 4, 6)), null)), 0, true, 1, -1, 0));
            var decoder = new ErrorProtectionDecoder(config, MediaLimits.defaults());
            var random = new Random(13);
            for (var length : new int[]{0, 1, 77, 600}) {
                var classes = classes(config, 0, random);
                classes[0][1] = Arrays.copyOf(classes[0][1], length);
                var frame = EpFrameEncoder.encode(config, 0, classes, null, null);
                decoder.decode(frame, 0, frame.length);
                assertDecoded(decoder, classes, 0, "length " + length);
                // the stuffing count is the only attribute, 3 bits under BCH(7,4); the group starts after it
                var start = 7;
                for (var errors = 1; errors <= 4; errors++) {
                    var damaged = frame.clone();
                    for (var e = 0; e < errors; e++) {
                        // one bit in each of the bytes from the start of the group and from the class until the end
                        var symbol = e % 2 == 0 || length < 8 * 3 ? e : 51 + e;
                        flip(damaged, start + 8 * symbol + random.nextInt(8));
                    }
                    decoder.decode(damaged, 0, damaged.length);
                    assertDecoded(decoder, classes, 0, "length " + length + ", " + errors + " byte errors");
                }
            }
        }

        @Test
        @DisplayName("corrects a corrupted choice of pre-defined set through the majority code")
        void choice() {
            var config = EpConfigWriter.parse(EpConfigWriter.write(EpConfigWriter.sets(new Set(List.of(
                    Spec.srcpc(10, 8, 8), Spec.srcpc(10, 0, 6).asOptional(), Spec.srcpc(0, 0, 0)), null)), 0, true, 1, -1,
                    0));
            var decoder = new ErrorProtectionDecoder(config, MediaLimits.defaults());
            var random = new Random(11);
            for (var choice = 0; choice < 2; choice++) {
                var classes = classes(config, choice, random);
                var frame = EpFrameEncoder.encode(config, choice, classes, null, null);
                for (var bit = 0; bit < 3; bit++) {
                    var damaged = frame.clone();
                    flip(damaged, bit);
                    decoder.decode(damaged, 0, damaged.length);
                    assertDecoded(decoder, classes, choice, "choice " + choice + " error at " + bit);
                }
            }
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {
        @Test
        @DisplayName("expands optional classes from all present to all absent")
        void expansion() {
            var config = EpConfigWriter.parse(EpConfigWriter.write(EpConfigWriter.sets(
                    new Set(List.of(Spec.srcpc(4, 0, 0), Spec.srcpc(4, 0, 0).asOptional(), Spec.srcpc(4, 0, 0),
                            Spec.srcpc(4, 0, 0).asOptional(), Spec.srcpc(0, 0, 0)), null),
                    new Set(List.of(Spec.srcpc(4, 0, 0).asOptional(), Spec.srcpc(0, 0, 0)), null)), 0, true, 1, -1, 0));
            // Table 1.58: four sets from the first, two from the second
            assertEquals(6, config.setCount());
            assertEquals(3, config.choiceBits());
            var order = new int[5];
            assertEquals(5, config.frameOrder(0, order));
            assertEquals(4, config.frameOrder(1, order));
            assertArrayEquals(new int[]{0, 2, 3, 4}, Arrays.copyOf(order, 4));
            assertEquals(4, config.frameOrder(2, order));
            assertArrayEquals(new int[]{0, 1, 2, 4}, Arrays.copyOf(order, 4));
            assertEquals(3, config.frameOrder(3, order));
            assertEquals(2, config.frameOrder(4, order));
            assertEquals(1, config.frameOrder(5, order));
            assertEquals(1, config.originalSet(5));
        }

        @Test
        @DisplayName("rejects reserved values and inconsistent sets as malformed")
        void malformed() {
            var plain = List.of(Spec.srcpc(4, 0, 0), Spec.srcpc(0, 0, 0));
            assertThrows(MediaException.Malformed.class, () -> EpConfigWriter.parse(EpConfigWriter.write(List.of(),
                    0, true, 1, -1, 0)));
            assertThrows(MediaException.Malformed.class, () -> EpConfigWriter.parse(EpConfigWriter.write(
                    EpConfigWriter.sets(new Set(plain, null)), 3, true, 1, -1, 0)));
            assertThrows(MediaException.Malformed.class, () -> EpConfigWriter.parse(EpConfigWriter.write(
                    EpConfigWriter.sets(new Set(plain, null)), 0, true, 0, -1, 0)));
            assertThrows(MediaException.Malformed.class, () -> EpConfigWriter.parse(EpConfigWriter.write(
                    EpConfigWriter.sets(new Set(List.of(Spec.srcpc(0, 0, 0), Spec.srcpc(4, 0, 0)), null)), 0, true, 1,
                    -1, 0)));
            assertThrows(MediaException.Malformed.class, () -> EpConfigWriter.parse(EpConfigWriter.write(
                    EpConfigWriter.sets(new Set(plain, new int[]{1, 1})), 0, true, 1, -1, 0)));
            assertThrows(MediaException.Malformed.class, () -> EpConfigWriter.parse(EpConfigWriter.write(
                    EpConfigWriter.sets(new Set(List.of(Spec.srcpc(4, 25, 0), Spec.srcpc(0, 0, 0)), null)), 0, true, 1,
                    -1, 0)));
            assertThrows(MediaException.Malformed.class, () -> EpConfigWriter.parse(EpConfigWriter.write(
                    EpConfigWriter.sets(new Set(List.of(Spec.srcpc(4, 0, 19), Spec.srcpc(0, 0, 0)), null)), 0, true, 1,
                    -1, 0)));
            assertThrows(MediaException.Malformed.class, () -> EpConfigWriter.parse(EpConfigWriter.write(
                    EpConfigWriter.sets(new Set(List.of(Spec.reedSolomon(4, 1, 1, 0).interleaved(2),
                            Spec.srcpc(0, 0, 0)), null)), 2, true, 1, -1, 0)));
            assertThrows(MediaException.Malformed.class, () -> EpConfigWriter.parse(EpConfigWriter.write(
                    EpConfigWriter.sets(new Set(plain, null)), 0, true, 2, -1, 0)));
        }
    }
}
