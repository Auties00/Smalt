package com.github.auties00.smalt.internal.nal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.DolbyVisionRpus;
import com.github.auties00.smalt.testkit.DolbyVisionRpus.Kind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the Dolby Vision RPU parser: the profile and enhancement layer kind it derives from RPUs of every profile
 * (written by the test kit and taken from dovi_tool's test assets, whose fields dovi_tool 2.3.4's {@code info}
 * reported), the display management data and extension block levels, and its rejection of damaged RPUs.
 */
@DisplayName("Dolby Vision RPU parser")
class DolbyVisionRpuTest {
    @Nested
    @DisplayName("synthetic RPUs")
    class Synthetic {
        @ParameterizedTest(name = "{0}")
        @EnumSource(Kind.class)
        @DisplayName("derive the profile and the enhancement layer from the header and the NLQ parameters")
        void profiles(Kind kind) {
            var rpu = DolbyVisionRpu.parse(DolbyVisionRpus.of(kind).rpu(), 0, DolbyVisionRpus.of(kind).rpu().length);
            var expectedProfile = switch (kind) {
                case PROFILE_4 -> 4;
                case PROFILE_5 -> 5;
                case PROFILE_7_MEL, PROFILE_7_FEL -> 7;
                case PROFILE_8 -> 8;
            };
            var expectedLayer = switch (kind) {
                case PROFILE_4, PROFILE_7_MEL -> DolbyVisionRpu.EnhancementLayer.MINIMAL;
                case PROFILE_7_FEL -> DolbyVisionRpu.EnhancementLayer.FULL;
                case PROFILE_5, PROFILE_8 -> DolbyVisionRpu.EnhancementLayer.NONE;
            };
            assertEquals(expectedProfile, rpu.profile());
            assertEquals(expectedLayer, rpu.enhancementLayer());
            assertEquals(kind == Kind.PROFILE_5, rpu.baseLayerFullRange());
            assertEquals(10, rpu.baseLayerBitDepth());
            assertEquals(10, rpu.enhancementLayerBitDepth());
            assertEquals(kind == Kind.PROFILE_4 ? 14 : 12, rpu.vdrBitDepth());
            assertEquals(18, rpu.rpuFormat());
            var dm = rpu.displayManagement();
            assertEquals(65535, dm.signalEotf());
            assertEquals(12, dm.signalBitDepth());
            assertEquals(1, dm.signalFullRange());
            assertEquals(62, dm.sourceMinPq());
            assertEquals(3079, dm.sourceMaxPq());
            assertEquals(List.of(1, 6), dm.cmV29Levels());
            assertNull(dm.cmV40Levels());
        }

        @Test
        @DisplayName("read MMR chroma pieces, compressed display management data and CM v4.0 blocks")
        void variants() {
            var data = DolbyVisionRpus.of(Kind.PROFILE_8).chromaMmr().compressedDm().cmV40().rpu();
            var rpu = DolbyVisionRpu.parse(data, 0, data.length);
            assertEquals(8, rpu.profile());
            var dm = rpu.displayManagement();
            assertTrue(dm.compressed());
            assertEquals(0, dm.sourceMaxPq());
            assertEquals(List.of(1, 6), dm.cmV29Levels());
            assertEquals(List.of(254), dm.cmV40Levels());
        }

        @Test
        @DisplayName("read fixed-point coefficients, whose zero NLQ is a full enhancement layer as libdovi reports it")
        void fixedPoint() {
            for (var kind : Kind.values()) {
                var data = DolbyVisionRpus.of(kind).fixedPoint().chromaMmr().rpu();
                var rpu = DolbyVisionRpu.parse(data, 0, data.length);
                var integer = DolbyVisionRpus.of(kind).chromaMmr().rpu();
                assertEquals(DolbyVisionRpu.parse(integer, 0, integer.length).profile(), rpu.profile(), kind.name());
                // libdovi's MEL test asks for vdr_in_max_int 1, which fixed-point coefficients leave at 0
                var expected = kind == Kind.PROFILE_5 || kind == Kind.PROFILE_8 ? DolbyVisionRpu.EnhancementLayer.NONE
                        : DolbyVisionRpu.EnhancementLayer.FULL;
                assertEquals(expected, rpu.enhancementLayer(), kind.name());
                assertEquals(List.of(1, 6), rpu.displayManagement().cmV29Levels(), kind.name());
            }
        }

        @Test
        @DisplayName("reports an RPU that reuses the previous mapping as having no enhancement layer of its own")
        void reuse() {
            var data = DolbyVisionRpus.of(Kind.PROFILE_7_FEL).reusePrevious().rpu();
            var rpu = DolbyVisionRpu.parse(data, 0, data.length);
            assertTrue(rpu.previousReused());
            assertEquals(7, rpu.profile());
            assertEquals(DolbyVisionRpu.EnhancementLayer.NONE, rpu.enhancementLayer());
        }

        @Test
        @DisplayName("parses the escaped RPU of an HEVC NAL unit of type 62 like the unescaped RPU")
        void hevcNal() {
            for (var frame = 0; frame < 32; frame++) {
                var writer = DolbyVisionRpus.of(Kind.PROFILE_5).chromaMmr().frame(frame);
                var raw = writer.rpu();
                var nal = writer.hevcNal();
                assertEquals(DolbyVisionRpu.parse(raw, 0, raw.length), DolbyVisionRpu.parseHevcNal(nal, 0, nal.length));
                var padded = new byte[nal.length + 7];
                System.arraycopy(nal, 0, padded, 3, nal.length);
                assertEquals(5, DolbyVisionRpu.tryParseHevcNal(padded, 3, nal.length).profile());
            }
        }

        @Test
        @DisplayName("ignores trailing zero bytes after the terminator")
        void trailingZeros() {
            var data = Arrays.copyOf(DolbyVisionRpus.of(Kind.PROFILE_8).rpu(), DolbyVisionRpus.of(Kind.PROFILE_8).rpu().length + 5);
            assertEquals(8, DolbyVisionRpu.parse(data, 0, data.length).profile());
        }
    }

    @Nested
    @DisplayName("damaged RPUs")
    class Damaged {
        private static byte[] valid() {
            return DolbyVisionRpus.of(Kind.PROFILE_7_FEL).chromaMmr().cmV40().rpu();
        }

        @Test
        @DisplayName("reject every truncation with Malformed")
        void truncations() {
            var data = valid();
            for (var length = 0; length < data.length; length++) {
                var cut = length;
                assertThrows(MediaException.Malformed.class, () -> DolbyVisionRpu.parse(data, 0, cut), "length " + cut);
            }
        }

        @Test
        @DisplayName("reject every single bit flip through the CRC")
        void bitFlips() {
            var data = valid();
            for (var bit = 8; bit < (data.length - 1) * 8; bit++) {
                var copy = data.clone();
                copy[bit >> 3] ^= (byte) (0x80 >>> (bit & 7));
                assertThrows(MediaException.Malformed.class, () -> DolbyVisionRpu.parse(copy, 0, copy.length), "bit " + bit);
            }
        }

        @Test
        @DisplayName("reject a wrong prefix, a wrong terminator and NAL units of other types")
        void framing() {
            var data = valid();
            var prefix = data.clone();
            prefix[0] = 0x18;
            assertThrows(MediaException.Malformed.class, () -> DolbyVisionRpu.parse(prefix, 0, prefix.length));
            var terminator = data.clone();
            terminator[terminator.length - 1] = (byte) 0x81;
            assertThrows(MediaException.Malformed.class, () -> DolbyVisionRpu.parse(terminator, 0, terminator.length));
            var nal = DolbyVisionRpus.of(Kind.PROFILE_8).hevcNal();
            nal[0] = 0x4E;
            assertNull(DolbyVisionRpu.tryParseHevcNal(nal, 0, nal.length));
            assertThrows(MediaException.Malformed.class, () -> DolbyVisionRpu.parseHevcNal(nal, 0, nal.length));
        }

        @Test
        @DisplayName("reject random corruptions with a recomputed CRC only with Malformed")
        void corruptions() {
            var random = new SplittableRandom(62);
            var valid = valid();
            var accepted = 0;
            for (var round = 0; round < 4000; round++) {
                var copy = valid.clone();
                var flips = 1 + random.nextInt(6);
                for (var flip = 0; flip < flips; flip++) {
                    var bit = 8 + random.nextInt((copy.length - 5) * 8 - 8);
                    copy[bit >> 3] ^= (byte) (0x80 >>> (bit & 7));
                }
                var crc = DolbyVisionRpus.crc32(copy, 1, copy.length - 5);
                copy[copy.length - 5] = (byte) (crc >>> 24);
                copy[copy.length - 4] = (byte) (crc >>> 16);
                copy[copy.length - 3] = (byte) (crc >>> 8);
                copy[copy.length - 2] = (byte) crc;
                try {
                    assertNotNull(DolbyVisionRpu.parse(copy, 0, copy.length));
                    accepted++;
                } catch (MediaException.Malformed expected) {
                    // the only failure a damaged RPU may raise
                }
            }
            assertTrue(accepted > 0 && accepted < 4000, "accepted " + accepted);
        }
    }

    @Nested
    @DisplayName("dovi_tool test RPUs")
    class Vectors {
        static Stream<Arguments> vectors() {
            return Stream.of(
                    Arguments.of("profile4.bin", 4, "FULL", false, 14, 62, 3697, List.of(1, 2, 4), null),
                    Arguments.of("profile5.bin", 5, "NONE", true, 12, 62, 3696, List.of(1, 2, 4, 5, 6), null),
                    Arguments.of("profile5-02.bin", 5, "NONE", true, 12, 7, 3079, List.of(1, 2, 4, 5, 6), null),
                    Arguments.of("profile8.bin", 8, "NONE", false, 12, 62, 3696, List.of(1, 2, 4, 5, 6), null),
                    Arguments.of("profile84.bin", 8, "NONE", false, 12, 62, 3696, List.of(1, 2, 4, 5, 6), null),
                    Arguments.of("fel_orig.bin", 7, "FULL", false, 12, 7, 3079, List.of(1, 2, 4, 5, 6), null),
                    Arguments.of("mel_orig.bin", 7, "MINIMAL", false, 12, 7, 3079, List.of(1, 4, 6), null),
                    Arguments.of("profile20_apple.bin", 5, "NONE", true, 12, 7, 3079, List.of(1),
                            List.of(3, 8, 9, 10, 254, 11)),
                    Arguments.of("cmv40_full_rpu.bin", 8, "NONE", false, 12, 7, 3079, List.of(1, 2, 4, 5, 6),
                            List.of(3, 8, 9, 10, 11, 254)),
                    Arguments.of("trailing_bytes_rpu.bin", 7, "FULL", false, 12, 62, 3696, List.of(1, 2, 4, 5, 6), null),
                    Arguments.of("data_before_crc32.bin", 7, "FULL", false, 12, 7, 3079, List.of(1, 2, 2, 2, 4, 5, 6),
                            List.of(3, 9, 254)),
                    Arguments.of("level253-ext-metadata.bin", 5, "NONE", true, 12, 7, 3079,
                            List.of(1, 2, 2, 2, 4, 5, 6), List.of(8, 8, 8, 9, 10, 254, 11, 253)),
                    Arguments.of("unordered_l8_blocks.bin", 8, "NONE", false, 12, 62, 3079, List.of(1, 4),
                            List.of(3, 8, 8, 9, 10, 10, 11, 254)),
                    Arguments.of("empty_dmv1_blocks.bin", 5, "NONE", true, 12, 62, 2081, List.of(), null));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("vectors")
        @DisplayName("match the fields dovi_tool reports")
        void vector(String name, int profile, String layer, boolean fullRange, int signalDepth, int minPq, int maxPq,
                    List<Integer> v29, List<Integer> v40) throws IOException {
            var file = Files.readAllBytes(Conformance.fetchListed("dovi-tool-" + name));
            // dovi_tool stores each RPU after a four-byte start code, with its emulation prevention bytes
            assertEquals(1, file[3]);
            var payload = NalUnits.unescape(file, 4, file.length - 4);
            var rpu = DolbyVisionRpu.parse(payload, 0, payload.length);
            assertEquals(profile, rpu.profile());
            assertEquals(DolbyVisionRpu.EnhancementLayer.valueOf(layer), rpu.enhancementLayer());
            assertEquals(fullRange, rpu.baseLayerFullRange());
            assertFalse(rpu.previousReused());
            var dm = rpu.displayManagement();
            assertFalse(dm.compressed());
            assertEquals(signalDepth, dm.signalBitDepth());
            assertEquals(minPq, dm.sourceMinPq());
            assertEquals(maxPq, dm.sourceMaxPq());
            assertEquals(v29, dm.cmV29Levels());
            assertEquals(v40, dm.cmV40Levels());
        }
    }
}
