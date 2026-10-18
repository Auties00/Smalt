package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.video.h264.H264BitWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of the bitstream layer: bit reading and Exp-Golomb codes, NAL unit escaping,
 * parameter set and configuration record parsing, scaling list fall-back, and CABAC context
 * initialization, each checked against values derived by hand from ITU-T H.264.
 */
@DisplayName("H.264 bitstream syntax")
class H264SyntaxTest {
    private static H264BitReader reader(byte[] bytes) {
        var reader = new H264BitReader();
        reader.init(Arrays.copyOf(bytes, bytes.length + H264BitReader.PADDING), 0, bytes.length);
        return reader;
    }

    /** Unescapes a NAL unit built by {@link H264BitWriter#nal} and returns its payload. */
    private static byte[] payload(byte[] nal) {
        var out = new byte[nal.length + H264BitReader.PADDING];
        var size = NalUnits.unescape(nal, 1, nal.length - 1, out);
        return Arrays.copyOf(out, size + H264BitReader.PADDING);
    }

    @Nested
    @DisplayName("bit reader")
    class Reader {
        @Test
        @DisplayName("reads fixed-length fields of every width in sequence")
        void fixedFields() {
            var random = new Random(1);
            var writer = new H264BitWriter();
            var widths = new int[2000];
            var values = new long[2000];
            for (var i = 0; i < widths.length; i++) {
                widths[i] = random.nextInt(33);
                values[i] = widths[i] == 0 ? 0 : random.nextLong() & ((1L << widths[i]) - 1);
                writer.bits(values[i], widths[i]);
            }
            var reader = reader(writer.bytes());
            for (var i = 0; i < widths.length; i++) {
                assertEquals(values[i], reader.readBits(widths[i]) & 0xFFFFFFFFL, "field " + i);
            }
            assertFalse(reader.overrun());
        }

        @Test
        @DisplayName("decodes ue(v), se(v) and te(v) codes")
        void expGolomb() {
            var random = new Random(2);
            var unsigned = new long[3000];
            var signed = new long[3000];
            var writer = new H264BitWriter();
            for (var i = 0; i < unsigned.length; i++) {
                unsigned[i] = switch (i % 4) {
                    case 0 -> random.nextInt(8);
                    case 1 -> random.nextInt(1 << 16);
                    case 2 -> random.nextInt(Integer.MAX_VALUE);
                    default -> i < 10 ? Integer.MAX_VALUE : random.nextInt(300);
                };
                signed[i] = random.nextInt(1 << 20) - (1 << 19);
                writer.ue(unsigned[i]).se(signed[i]);
            }
            writer.bit(0).ue(5);
            var reader = reader(writer.bytes());
            for (var i = 0; i < unsigned.length; i++) {
                assertEquals(unsigned[i], reader.readUe(), "ue " + i);
                assertEquals(signed[i], reader.readSe(), "se " + i);
            }
            assertEquals(1, reader.readTe(1), "te(v) with range 1 is the inverted bit");
            assertEquals(5, reader.readTe(3));
        }

        @Test
        @DisplayName("rejects Exp-Golomb codes longer than 32 bits")
        void overlongCode() {
            var bytes = new byte[8];
            bytes[5] = 1;
            assertThrows(MediaException.Malformed.class, () -> reader(bytes).readUe());
            var writer = new H264BitWriter().ue(0xFFFFFFFEL);
            assertThrows(MediaException.Malformed.class, () -> reader(writer.bytes()).readUe());
        }

        @Test
        @DisplayName("reports overrun past the payload instead of reading out of bounds")
        void overrun() {
            var reader = reader(new byte[]{(byte) 0xFF});
            for (var i = 0; i < 64; i++) {
                reader.readBits(32);
            }
            assertTrue(reader.overrun());
        }

        @Test
        @DisplayName("finds the rbsp_stop_one_bit")
        void stopBit() {
            assertEquals(0, NalUnits.stopBitPosition(new byte[]{(byte) 0x80}, 0, 1));
            assertEquals(9, NalUnits.stopBitPosition(new byte[]{0x12, 0x40, 0}, 0, 3));
            assertEquals(24, NalUnits.stopBitPosition(new byte[3], 0, 3));
        }
    }

    @Nested
    @DisplayName("NAL units")
    class Nal {
        @Test
        @DisplayName("removes emulation prevention bytes")
        void unescape() {
            assertEquals(List.of(0, 0, 1), unescaped(0, 0, 3, 1));
            assertEquals(List.of(0, 0, 3, 0, 0, 0), unescaped(0, 0, 3, 3, 0, 0, 3, 0));
            assertEquals(List.of(0, 0), unescaped(0, 0, 3));
            assertEquals(List.of(0, 1, 3, 0, 3), unescaped(0, 1, 3, 0, 3));
        }

        private List<Integer> unescaped(int... bytes) {
            var source = new byte[bytes.length];
            for (var i = 0; i < bytes.length; i++) {
                source[i] = (byte) bytes[i];
            }
            var out = new byte[bytes.length + H264BitReader.PADDING];
            var size = NalUnits.unescape(source, 0, source.length, out);
            var result = new java.util.ArrayList<Integer>();
            for (var i = 0; i < size; i++) {
                result.add(out[i] & 0xFF);
            }
            return result;
        }

        @Test
        @DisplayName("unescaping inverts escaping for arbitrary payloads")
        void roundTrip() {
            var random = new Random(3);
            for (var trial = 0; trial < 500; trial++) {
                var rbsp = new byte[random.nextInt(200)];
                for (var i = 0; i < rbsp.length; i++) {
                    rbsp[i] = (byte) (random.nextInt(4) == 0 ? random.nextInt(4) : random.nextInt(256));
                }
                var nal = H264BitWriter.nal(0, 1, rbsp);
                assertEquals(-1, NalUnits.findStartCode(nal, 1, nal.length), "escaped payload holds a start code");
                assertArrayEquals(rbsp, Arrays.copyOf(payload(nal), rbsp.length));
            }
        }

        @Test
        @DisplayName("locates start codes and recognizes Annex B streams")
        void startCodes() {
            var stream = new byte[]{0, 0, 0, 1, 0x67, 0, 0, 1, 0x68, 5};
            assertEquals(1, NalUnits.findStartCode(stream, 0, stream.length));
            assertEquals(5, NalUnits.findStartCode(stream, 4, stream.length));
            assertEquals(-1, NalUnits.findStartCode(stream, 6, stream.length));
            assertTrue(NalUnits.looksLikeAnnexB(stream));
            assertFalse(NalUnits.looksLikeAnnexB(new byte[]{0, 0, 0, 5, 0x65, 1, 2, 3, 4}));
        }
    }

    @Nested
    @DisplayName("parameter sets")
    class ParameterSets {
        private static byte[] vuiSps() {
            var writer = new H264BitWriter();
            writer.bits(100, 8).bits(0, 8).bits(31, 8).ue(3);
            writer.ue(1).ue(0).ue(0).bit(0).bit(0);
            writer.ue(4).ue(0).ue(2).ue(4).bit(0).ue(79).ue(44).bit(1).bit(1);
            writer.bit(1).ue(0).ue(0).ue(0).ue(4);
            writer.bit(1);
            writer.bit(0);
            writer.bit(0);
            writer.bit(1).bits(5, 3).bit(1).bit(1).bits(1, 8).bits(1, 8).bits(1, 8);
            writer.bit(0);
            writer.bit(1).bits(1001, 32).bits(60000, 32).bit(1);
            writer.bit(0).bit(0).bit(0);
            writer.bit(1).bit(1).ue(2).ue(1).ue(16).ue(16).ue(2).ue(4);
            return writer.trailing();
        }

        @Test
        @DisplayName("parses dimensions, cropping, picture order and VUI fields")
        void sps() {
            var rbsp = vuiSps();
            var sps = H264Sps.parse(Arrays.copyOf(rbsp, rbsp.length + H264BitReader.PADDING), 0, rbsp.length);
            assertEquals(100, sps.profileIdc);
            assertEquals(31, sps.levelIdc);
            assertEquals(3, sps.id);
            assertEquals(8, sps.log2MaxFrameNum);
            assertEquals(0, sps.pocType);
            assertEquals(6, sps.log2MaxPocLsb);
            assertEquals(4, sps.maxNumRefFrames);
            assertEquals(1280, sps.codedWidth());
            assertEquals(720, sps.codedHeight());
            assertEquals(1280, sps.croppedWidth());
            assertEquals(712, sps.croppedHeight());
            assertTrue(sps.videoFullRange);
            assertEquals(ColorRange.FULL, sps.colorInfo().range());
            assertEquals(MatrixCoefficients.BT709, sps.colorInfo().matrix());
            assertEquals(ColorPrimaries.BT709, sps.colorInfo().primaries());
            assertTrue(sps.timingInfoPresent);
            assertEquals(1001, sps.numUnitsInTick);
            assertEquals(60000, sps.timeScale);
            assertEquals(2, sps.maxNumReorderFrames);
            assertEquals(2, sps.reorderDepth());
        }

        @Test
        @DisplayName("rejects out-of-range fields")
        void invalidSps() {
            assertMalformedSps(new H264BitWriter().bits(66, 8).bits(0, 8).bits(30, 8).ue(32));
            assertMalformedSps(new H264BitWriter().bits(100, 8).bits(0, 8).bits(30, 8).ue(0).ue(4));
            assertMalformedSps(new H264BitWriter().bits(66, 8).bits(0, 8).bits(30, 8).ue(0).ue(13));
            assertMalformedSps(new H264BitWriter().bits(66, 8).bits(0, 8).bits(30, 8).ue(0).ue(0).ue(3));
            assertMalformedSps(new H264BitWriter().bits(66, 8).bits(0, 8).bits(30, 8).ue(0).ue(0).ue(2).ue(17));
            assertMalformedSps(new H264BitWriter().bits(66, 8).bits(0, 8).bits(30, 8).ue(0).ue(0).ue(2).ue(1).bit(0).ue(Integer.MAX_VALUE - 1));
            assertMalformedSps(new H264BitWriter().bits(66, 8));
        }

        private void assertMalformedSps(H264BitWriter writer) {
            var rbsp = writer.trailing();
            assertThrows(MediaException.Malformed.class,
                    () -> H264Sps.parse(Arrays.copyOf(rbsp, rbsp.length + H264BitReader.PADDING), 0, rbsp.length));
        }

        @Test
        @DisplayName("parses a PPS against its SPS and rejects a mismatched one")
        void pps() {
            var spsPayload = payload(H264BitWriter.sps(100, 4, 3, 1));
            var sps = H264Sps.parse(spsPayload, 0, spsPayload.length - H264BitReader.PADDING);
            var ppsPayload = payload(H264BitWriter.pps());
            var pps = H264Pps.parse(ppsPayload, 0, ppsPayload.length - H264BitReader.PADDING, sps);
            assertEquals(0, pps.id);
            assertFalse(pps.cabac);
            assertEquals(1, pps.numSliceGroups);
            var bad = new H264BitWriter().ue(0).ue(0).bit(0).bit(0).ue(9).trailing();
            assertThrows(MediaException.Malformed.class,
                    () -> H264Pps.parse(Arrays.copyOf(bad, bad.length + H264BitReader.PADDING), 0, bad.length, sps));
        }

        @Test
        @DisplayName("parses an avcC record and rejects length size 3 and truncation")
        void avcC() {
            var sps = H264BitWriter.sps(100, 4, 3, 1);
            var pps = H264BitWriter.pps();
            var record = new java.io.ByteArrayOutputStream();
            record.writeBytes(new byte[]{1, 100, 0, 40, (byte) 0xFD, (byte) 0xE1, 0, (byte) sps.length});
            record.writeBytes(sps);
            record.writeBytes(new byte[]{1, 0, (byte) pps.length});
            record.writeBytes(pps);
            var parsed = NalRecords.parseAvcStrict(record.toByteArray());
            assertEquals(2, parsed.lengthSize());
            assertEquals(2, parsed.parameterSets().size());
            assertArrayEquals(sps, parsed.parameterSets().get(0));
            var three = record.toByteArray();
            three[4] = (byte) 0xFE;
            assertThrows(MediaException.Malformed.class, () -> NalRecords.parseAvcStrict(three));
            var truncated = Arrays.copyOf(record.toByteArray(), 12);
            assertThrows(MediaException.Malformed.class, () -> NalRecords.parseAvcStrict(truncated));
        }
    }

    @Nested
    @DisplayName("scaling lists")
    class ScalingLists {
        private static final int[] DEFAULT_4X4_INTRA = {6, 13, 13, 20, 20, 20, 28, 28, 28, 28, 32, 32, 32, 37, 37, 42};
        private static final int[] DEFAULT_4X4_INTER = {10, 14, 14, 20, 20, 20, 24, 24, 24, 24, 27, 27, 27, 30, 30, 34};

        private static int[][] parse(H264BitWriter writer, int count, int[][] fallback4, int[][] fallback8, int[][] out8) {
            var out4 = new int[6][16];
            H264ScalingLists.parse(reader(writer.bytes()), count, fallback4, fallback8, out4, out8);
            return out4;
        }

        private static int[] raster(int[] zigzag) {
            var raster = new int[16];
            for (var i = 0; i < 16; i++) {
                raster[H264Tables.ZIGZAG_4X4[i]] = zigzag[i];
            }
            return raster;
        }

        @Test
        @DisplayName("absent SPS lists fall back to the defaults and the previous list (rule A)")
        void fallbackA() {
            var writer = new H264BitWriter();
            for (var i = 0; i < 8; i++) {
                writer.bit(0);
            }
            var out8 = new int[6][64];
            var out4 = parse(writer, 8, null, null, out8);
            assertArrayEquals(raster(DEFAULT_4X4_INTRA), out4[0]);
            assertArrayEquals(out4[0], out4[1]);
            assertArrayEquals(out4[0], out4[2]);
            assertArrayEquals(raster(DEFAULT_4X4_INTER), out4[3]);
            assertArrayEquals(out4[3], out4[5]);
            assertEquals(6, out8[0][0]);
            assertEquals(9, out8[1][0]);
        }

        @Test
        @DisplayName("absent PPS lists fall back to the SPS lists (rule B)")
        void fallbackB() {
            var writer = new H264BitWriter();
            writer.bit(1).se(8);
            for (var j = 1; j < 16; j++) {
                writer.se(0);
            }
            for (var i = 1; i < 6; i++) {
                writer.bit(0);
            }
            var sps4 = new int[6][16];
            for (var i = 0; i < 6; i++) {
                Arrays.fill(sps4[i], 40 + i);
            }
            var sps8 = new int[6][64];
            var out4 = parse(writer, 6, sps4, sps8, new int[6][64]);
            assertArrayEquals(filled(16), out4[0]);
            assertArrayEquals(filled(16), out4[2]);
            assertArrayEquals(sps4[3], out4[3]);
            assertArrayEquals(sps4[3], out4[4]);
        }

        @Test
        @DisplayName("a zero first scale selects the default list and a zero later scale repeats the last one")
        void explicitLists() {
            var writer = new H264BitWriter();
            writer.bit(1).se(-8);
            writer.bit(1).se(2).se(3).se(-13);
            for (var i = 2; i < 6; i++) {
                writer.bit(0);
            }
            var out4 = parse(writer, 6, null, null, new int[6][64]);
            assertArrayEquals(raster(DEFAULT_4X4_INTRA), out4[0]);
            var expected = new int[16];
            Arrays.fill(expected, 13);
            expected[0] = 10;
            assertArrayEquals(raster(expected), out4[1]);
        }

        private static int[] filled(int value) {
            var result = new int[16];
            Arrays.fill(result, value);
            return result;
        }
    }

    @Nested
    @DisplayName("CABAC")
    class Cabac {
        @ParameterizedTest(name = "slice QP {0}")
        @ValueSource(ints = {0, 12, 26, 39, 51})
        @DisplayName("initializes contexts from Tables 9-12 to 9-33")
        void initialization(int qp) {
            var states = new byte[CabacTables.CONTEXT_COUNT];
            CabacTables.initContexts(states, 0, qp);
            assertEquals(state(20, -15, qp), states[0]);
            assertEquals(state(2, 54, qp), states[1]);
            assertEquals(state(3, 74, qp), states[2]);
            assertEquals(state(0, 41, qp), states[60]);
            CabacTables.initContexts(states, 1, qp);
            assertEquals(state(23, 33, qp), states[11]);
            assertEquals(state(0, 41, qp), states[60]);
            CabacTables.initContexts(states, 3, qp);
            assertEquals(state(29, 16, qp), states[11]);
        }

        private static byte state(int m, int n, int qp) {
            var pre = Math.clamp(((m * qp) >> 4) + n, 1, 126);
            return (byte) (pre <= 63 ? (63 - pre) << 1 : ((pre - 64) << 1) | 1);
        }

        @Test
        @DisplayName("state transition tables follow Table 9-45")
        void transitions() {
            assertEquals(2 * 62, CabacTables.NEXT_STATE_MPS[2 * 62] & 0xFF);
            assertEquals(2 * 1, CabacTables.NEXT_STATE_MPS[0] & 0xFF);
            assertEquals(1, CabacTables.NEXT_STATE_LPS[0] & 0xFF, "state 0 with MPS 0 flips the MPS");
            assertEquals(2 * 38, CabacTables.NEXT_STATE_LPS[2 * 62] & 0xFF);
        }
    }
}
