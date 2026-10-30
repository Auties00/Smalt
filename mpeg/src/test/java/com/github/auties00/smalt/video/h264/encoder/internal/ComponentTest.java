package com.github.auties00.smalt.video.h264.encoder.internal;

import com.github.auties00.smalt.video.h264.encoder.H264Profile;
import com.github.auties00.smalt.video.h264.encoder.H264RateControl;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderSpeed;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.simd.Simd;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.h264.encoder.H264Setup;
import com.github.auties00.smalt.video.h264.internal.H264Tables;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests of the bitstream building blocks: the reference index code, emulation prevention, the CAVLC
 * block coder (against the worked example of Richardson's H.264 white paper), the CABAC engine
 * (against an independent decoder written from clause 9.3.3.2), level selection and the
 * parameter set fields.
 */
class ComponentTest {
    private static String bits(MsbBitWriter writer) {
        var bytes = writer.toByteArray();
        var text = new StringBuilder();
        for (var i = 0; i < writer.bitCount(); i++) {
            text.append((bytes[i >> 3] >> (7 - (i & 7))) & 1);
        }
        return text.toString();
    }

    /** Reads bits MSB-first, returning zeros past the end. */
    private static final class BitReader {
        private final byte[] data;
        private long position;

        BitReader(byte[] data) {
            this.data = data;
        }

        int bit() {
            var index = (int) (position >> 3);
            var value = index < data.length ? (data[index] >> (7 - (position & 7))) & 1 : 0;
            position++;
            return value;
        }

        int bits(int count) {
            var value = 0;
            for (var i = 0; i < count; i++) {
                value = (value << 1) | bit();
            }
            return value;
        }

        int ue() {
            var zeros = 0;
            while (bit() == 0) {
                zeros++;
            }
            return (1 << zeros) - 1 + bits(zeros);
        }

        int se() {
            var k = ue();
            return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2);
        }
    }

    /** The arithmetic decoding engine of clause 9.3.3.2, used to check the encoder. */
    private static final class CabacDecoder {
        private final BitReader in;
        private final byte[] states;
        private int range = 510;
        private int offset;

        CabacDecoder(byte[] data, byte[] states) {
            this.in = new BitReader(data);
            this.states = states.clone();
            this.offset = in.bits(9);
        }

        int decision(int ctx) {
            var s = states[ctx] & 0xFF;
            var lps = com.github.auties00.smalt.video.h264.internal.CabacTables.LPS_RANGE[((s >> 1) << 2) | ((range >> 6) & 3)];
            range -= lps;
            int bin;
            if (offset >= range) {
                bin = 1 - (s & 1);
                offset -= range;
                range = lps;
            } else {
                bin = s & 1;
            }
            states[ctx] = CabacTables.NEXT_STATE[(s << 1) | bin];
            renormalize();
            return bin;
        }

        int bypass() {
            offset = (offset << 1) | in.bit();
            if (offset >= range) {
                offset -= range;
                return 1;
            }
            return 0;
        }

        int terminate() {
            range -= 2;
            if (offset >= range) {
                return 1;
            }
            renormalize();
            return 0;
        }

        private void renormalize() {
            while (range < 256) {
                range <<= 1;
                offset = (offset << 1) | in.bit();
            }
        }
    }

    @Nested
    @DisplayName("reference index")
    class ReferenceIndex {
        @ParameterizedTest(name = "ref {0} of {1} = \"{2}\"")
        @CsvSource({"0, 1, ''", "0, 2, 1", "1, 2, 0", "0, 3, 1", "2, 3, 011", "3, 5, 00100"})
        @DisplayName("writes te(v): nothing for one reference, one inverted bit for two, ue(v) beyond")
        void truncated(int ref, int refCount, String expected) {
            var writer = new MsbBitWriter(4);
            CavlcWriter.writeRef(writer, ref, refCount);
            assertEquals(expected, bits(writer));
        }
    }

    @Nested
    @DisplayName("NAL unit writer")
    class Nal {
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({
                "000000, 0000030003",
                "000001, 00000301",
                "000002, 00000302",
                "000003, 00000303",
                "000004, 000004",
                "0000000000, 0000030000030003",
                "11000022, 11000022",
                "11000002, 1100000302",
                "1100, 110003"
        })
        @DisplayName("inserts emulation prevention bytes")
        void escaping(String payload, String expected) {
            var unit = NalWriter.unit(3, 5, hex(payload));
            assertEquals(0x65, unit[0] & 0xFF);
            assertEquals(expected, hex(unit, 1));
        }

        @Test
        @DisplayName("prefixes each unit with its big-endian length")
        void lengthPrefix() {
            var writer = new NalWriter(8, false);
            writer.appendUnit(0, 6, hex("0000011234"), 5);
            writer.appendUnit(2, 1, hex("ff"), 1);
            assertEquals("00000007" + "06" + "00000301" + "1234" + "00000002" + "41ff", hex(writer.take(), 0));
            assertEquals(0, writer.size());
        }

        @Test
        @DisplayName("prefixes each unit, escaped ones included, with a four-byte Annex B start code")
        void startCodes() {
            var writer = new NalWriter(8, true);
            writer.appendEscapedUnit(NalWriter.unit(3, 7, hex("4200")));
            writer.appendUnit(0, 6, hex("0000011234"), 5);
            writer.appendUnit(2, 1, hex("ff"), 1);
            assertEquals("00000001" + "67420003" + "00000001" + "06" + "00000301" + "1234" + "00000001" + "41ff",
                    hex(writer.take(), 0));
        }

        private static byte[] hex(String text) {
            var out = new byte[text.length() / 2];
            for (var i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(text.substring(2 * i, 2 * i + 2), 16);
            }
            return out;
        }

        private static String hex(byte[] data, int from) {
            var text = new StringBuilder();
            for (var i = from; i < data.length; i++) {
                text.append(String.format("%02x", data[i] & 0xFF));
            }
            return text.toString();
        }
    }

    @Nested
    @DisplayName("CAVLC block coder")
    class Cavlc {
        @Test
        @DisplayName("codes the worked 4x4 example of Richardson's white paper")
        void whitePaperExample() {
            // Richardson, "H.264 / MPEG-4 Part 10 White Paper: Variable Length Coding", example 1:
            // TotalCoeff 5, TrailingOnes 3, TotalZeros 3, nC 0.
            int[] block = {
                    0, 3, -1, 0,
                    0, -1, 1, 0,
                    1, 0, 0, 0,
                    0, 0, 0, 0
            };
            var writer = new MsbBitWriter(16);
            var total = new CavlcWriter().writeBlock(writer, block, 0, H264Tables.ZIGZAG_4X4, 0, 16, 0);
            assertEquals(5, total);
            assertEquals("000010001110010111101101", bits(writer));
        }

        @Test
        @DisplayName("codes an empty block as the single coeff_token bit for nC below 2")
        void emptyBlock() {
            var writer = new MsbBitWriter(4);
            assertEquals(0, new CavlcWriter().writeBlock(writer, new int[16], 0, H264Tables.ZIGZAG_4X4, 0, 16, 1));
            assertEquals("1", bits(writer));
        }
    }

    @Nested
    @DisplayName("CABAC engine")
    class Cabac {
        @RepeatedTest(20)
        @DisplayName("decodes back to the same bins with an independent decoder")
        void roundTrip(RepetitionInfo repetition) {
            var random = new Random(repetition.getCurrentRepetition());
            var encoder = new CabacEncoder();
            encoder.initContexts(random.nextInt(4), random.nextInt(52));
            var initial = encoder.states.clone();
            var writer = new MsbBitWriter(64);
            encoder.start(writer);
            var count = 20_000;
            var kinds = new int[count];
            var values = new int[count];
            var contexts = new int[count];
            for (var i = 0; i < count; i++) {
                kinds[i] = random.nextInt(10) < 7 ? 0 : random.nextInt(3) + 1;
                contexts[i] = random.nextInt(8);
                switch (kinds[i]) {
                    case 0 -> {
                        // Skewed bins exercise both the MPS and the LPS paths.
                        values[i] = random.nextInt(10) < 8 ? contexts[i] & 1 : 1 - (contexts[i] & 1);
                        encoder.decision(contexts[i], values[i]);
                    }
                    case 1 -> {
                        values[i] = random.nextInt(2);
                        encoder.bypass(values[i]);
                    }
                    case 2 -> {
                        values[i] = random.nextInt(1 << 14);
                        encoder.bypassBits(values[i], 14);
                    }
                    default -> encoder.terminate(0);
                }
            }
            encoder.terminate(1);
            encoder.flush();
            writer.alignWithZeros();
            var decoder = new CabacDecoder(writer.toByteArray(), initial);
            for (var i = 0; i < count; i++) {
                switch (kinds[i]) {
                    case 0 -> assertEquals(values[i], decoder.decision(contexts[i]), "decision " + i);
                    case 1 -> assertEquals(values[i], decoder.bypass(), "bypass " + i);
                    case 2 -> {
                        var value = 0;
                        for (var b = 0; b < 14; b++) {
                            value = (value << 1) | decoder.bypass();
                        }
                        assertEquals(values[i], value, "bypass bits " + i);
                    }
                    default -> assertEquals(0, decoder.terminate(), "terminate " + i);
                }
            }
            assertEquals(1, decoder.terminate(), "end of slice");
        }

        @Test
        @DisplayName("restores a saved state so that a discarded trial leaves no trace")
        void saveRestore() {
            var random = new Random(3);
            var reference = new CabacEncoder();
            var trial = new CabacEncoder();
            reference.initContexts(0, 26);
            trial.initContexts(0, 26);
            var referenceOut = new MsbBitWriter(64);
            var trialOut = new MsbBitWriter(64);
            reference.start(referenceOut);
            trial.start(trialOut);
            var registers = new long[7];
            var contexts = new byte[CabacTables.CONTEXTS];
            for (var round = 0; round < 200; round++) {
                trial.save(registers, contexts);
                for (var i = 0; i < 300; i++) {
                    trial.decision(random.nextInt(460), random.nextInt(2));
                }
                trial.restore(registers, contexts);
                for (var i = 0; i < 50; i++) {
                    var ctx = random.nextInt(460);
                    var bin = random.nextInt(2);
                    reference.decision(ctx, bin);
                    trial.decision(ctx, bin);
                }
            }
            reference.terminate(1);
            reference.flush();
            trial.terminate(1);
            trial.flush();
            assertArrayEquals(referenceOut.toByteArray(), trialOut.toByteArray());
            assertEquals(reference.binCount(), trial.binCount());
        }
    }

    @Nested
    @DisplayName("reference bands")
    class ReferenceBands {
        @ParameterizedTest(name = "{0}x{1}")
        @CsvSource({"16,16", "48,32", "64,48", "32,80", "160,96"})
        @DisplayName("padding and interpolating band by band equals doing it for the whole picture")
        void bands(int width, int height) {
            var random = new Random(width * 31L + height);
            var banded = new Frame(width, height);
            var whole = new Frame(width, height);
            for (var plane = 0; plane < 3; plane++) {
                var a = plane == 0 ? banded.y : plane == 1 ? banded.u : banded.v;
                var b = plane == 0 ? whole.y : plane == 1 ? whole.u : whole.v;
                random.nextBytes(a);
                System.arraycopy(a, 0, b, 0, a.length);
            }
            padWhole(whole.y, whole.lumaOrigin, whole.lumaStride, width, height, Frame.LUMA_PAD);
            padWhole(whole.u, whole.chromaOrigin, whole.chromaStride, width / 2, height / 2, Frame.CHROMA_PAD);
            padWhole(whole.v, whole.chromaOrigin, whole.chromaStride, width / 2, height / 2, Frame.CHROMA_PAD);
            var kernels = new InterpolationKernels();
            whole.interpolate(kernels, new short[whole.lumaStride + 16]);
            banded.allocateHalfPlanes();
            var temp = new short[banded.lumaStride + 16];
            for (var mbY = 0; mbY < height / 16; mbY++) {
                banded.padBand(mbY);
                banded.interpolateBand(mbY, kernels, temp);
            }
            assertArrayEquals(whole.y, banded.y);
            assertArrayEquals(whole.u, banded.u);
            assertArrayEquals(whole.v, banded.v);
            assertArrayEquals(whole.halfH, banded.halfH);
            assertArrayEquals(whole.halfV, banded.halfV);
            assertArrayEquals(whole.halfC, banded.halfC);
        }

        private static void padWhole(byte[] plane, int origin, int stride, int w, int h, int pad) {
            for (var row = -pad; row < h + pad; row++) {
                var source = Math.clamp(row, 0, h - 1);
                for (var x = -pad; x < w + pad; x++) {
                    plane[origin + row * stride + x] = plane[origin + source * stride + Math.clamp(x, 0, w - 1)];
                }
            }
        }
    }

    @Nested
    @DisplayName("residual decimation probe")
    class DecimationProbe {
        @RepeatedTest(300)
        @DisplayName("predicts whether the full inter quantization keeps any luma level")
        void matchesQuantization(RepetitionInfo repetition) {
            var random = new Random(repetition.getCurrentRepetition());
            var kernels = new TransformKernels[] {new TransformKernels(), Simd.ENABLED ? VectorKernelsHolder.transform() : new TransformKernels()};
            var src = new byte[40 * 24];
            var pred = new byte[40 * 24];
            // small residuals around the dead zone make decimation decide most blocks
            var spread = 1 + random.nextInt(repetition.getCurrentRepetition() % 3 == 0 ? 256 : 24);
            for (var i = 0; i < src.length; i++) {
                var base = random.nextInt(256);
                src[i] = (byte) base;
                pred[i] = (byte) Math.clamp(base + random.nextInt(spread) - spread / 2, 0, 255);
            }
            var qp = random.nextInt(52);
            for (var kernel : kernels) {
                var coder = new ResidualCoder(kernel, new PixelKernels(), 2063);
                var mb = new Macroblock();
                mb.cbp = 0;
                coder.quantizeLumaInter(mb, src, 3, 40, pred, 5, 40, qp);
                assertEquals((mb.cbp & 15) != 0, coder.probeLumaInter(src, 3, 40, pred, 5, 40, qp), "qp " + qp);
            }
        }
    }

    @Nested
    @DisplayName("inter residual shortcuts")
    class InterShortcuts {
        /**
         * Kernels that never report complete masks, so the residual coder always runs the full
         * quantization.
         */
        private static final class FullQuantization extends TransformKernels {
            @Override
            boolean quantizeLumaMasks(byte[] src, int srcOffset, int srcStride, byte[] pred, int predOffset,
                                      int predStride, int qp, int[] masks, int[] signs) {
                return false;
            }

            @Override
            boolean quantizeChromaMasks(byte[] srcU, byte[] srcV, int srcOffset, int srcStride, byte[] predU,
                                        byte[] predV, int predOffset, int predStride, int qp, int[] masks, int[] dc) {
                return false;
            }
        }

        @RepeatedTest(300)
        @DisplayName("give the levels, counts and coded block pattern of the full quantization")
        void matchFullQuantization(RepetitionInfo repetition) {
            var random = new Random(1000 + repetition.getCurrentRepetition());
            var src = new byte[3][40 * 24];
            var pred = new byte[3][40 * 24];
            var spread = 1 + random.nextInt(repetition.getCurrentRepetition() % 3 == 0 ? 256 : 24);
            for (var plane = 0; plane < 3; plane++) {
                for (var i = 0; i < src[plane].length; i++) {
                    var base = random.nextInt(256);
                    src[plane][i] = (byte) base;
                    pred[plane][i] = (byte) Math.clamp(base + random.nextInt(spread) - spread / 2, 0, 255);
                }
            }
            var qp = random.nextInt(52);
            var reference = new ResidualCoder(new FullQuantization(), new PixelKernels(), 2063);
            for (var kernels : new TransformKernels[] {new TransformKernels(), Simd.ENABLED ? VectorKernelsHolder.transform() : new TransformKernels()}) {
                var coder = new ResidualCoder(kernels, new PixelKernels(), 2063);
                var expected = new Macroblock();
                var actual = new Macroblock();
                reference.quantizeLumaInter(expected, src[0], 3, 40, pred[0], 5, 40, qp);
                coder.quantizeLumaInter(actual, src[0], 3, 40, pred[0], 5, 40, qp);
                reference.quantizeChroma(expected, src[1], src[2], 3, 40, pred[1], pred[2], 5, 40, qp, false);
                coder.quantizeChroma(actual, src[1], src[2], 3, 40, pred[1], pred[2], 5, 40, qp, false);
                assertEquals(expected.cbp, actual.cbp, "qp " + qp);
                assertArrayEquals(expected.luma, actual.luma, "qp " + qp);
                assertArrayEquals(expected.lumaCount, actual.lumaCount, "qp " + qp);
                assertArrayEquals(expected.chroma, actual.chroma, "qp " + qp);
                assertArrayEquals(expected.chromaCount, actual.chromaCount, "qp " + qp);
                assertArrayEquals(expected.chromaDc, actual.chromaDc, "qp " + qp);
                assertArrayEquals(expected.chromaDcCount, actual.chromaDcCount, "qp " + qp);
            }
        }
    }

    @Nested
    @DisplayName("level selection")
    class LevelSelection {
        @ParameterizedTest(name = "{0}x{1} at {2} fps and {3} bps -> {4}")
        @CsvSource({
                "176, 144, 15, 64000, 10",
                "176, 144, 30, 0, 11",
                "352, 288, 30, 0, 13",
                "640, 480, 30, 0, 30",
                "1280, 720, 30, 0, 31",
                "1280, 720, 60, 0, 32",
                "1920, 1080, 30, 0, 40",
                "1920, 1080, 30, 30000000, 41",
                "1920, 1080, 60, 0, 42",
                "3840, 2160, 30, 0, 51"
        })
        @DisplayName("picks the lowest level of Table A-1 that fits size, rate and bitrate")
        void select(int width, int height, double fps, long bitrate, int expected) {
            var level = Levels.select(1000, false, (width + 15) / 16, (height + 15) / 16, fps, bitrate, bitrate, 1);
            assertEquals(expected, level.idc());
        }

        @Test
        @DisplayName("moves up a level when the decoded picture buffer is too small for the references")
        void references() {
            var one = Levels.select(1000, false, 80, 45, 30, 0, 0, 1);
            var many = Levels.select(1000, false, 80, 45, 30, 0, 0, 16);
            assertTrue(many.idc() > one.idc());
            assertTrue(many.maxDpbFrames(80 * 45) >= 16);
        }

        @ParameterizedTest(name = "{0} bps, {1}-bit buffer: level_idc {2}")
        @CsvSource({
                "300000000, 1000000000, 61",
                "1000000000, 600000000, 62",
                "1000000000, 1000000000, 40"
        })
        @DisplayName("past every rate limit, keeps as many of the bitrate and buffer limits as a level can")
        void overLimit(long bitrate, long bufferSize, int expected) {
            var level = Levels.select(1000, false, 120, 68, 30, bitrate, bufferSize, 1);
            assertEquals(expected, level.idc());
        }

        @ParameterizedTest(name = "{0}x{1} at {2} fps interlaced -> {3}")
        @CsvSource({
                "176, 144, 15, 21",
                "720, 576, 25, 30",
                "1920, 1088, 30, 40",
                "1920, 1088, 60, 41"
        })
        @DisplayName("keeps interlaced streams within levels 2.1 to 4.1, the levels that allow field coding")
        void interlaced(int width, int height, double fps, int expected) {
            var level = Levels.select(1000, true, (width + 15) / 16, (height + 15) / 16, fps, 0, 0, 1);
            assertEquals(expected, level.idc());
            for (var idc : new int[]{10, 20, 42, 51}) {
                assertTrue(!Levels.fits(Levels.of(idc), 1000, true, 11, 10, 0, 0, 0), "level " + idc);
            }
            assertTrue(Levels.fits(Levels.of(21), 1000, true, 11, 10, 0, 0, 0));
        }

        @ParameterizedTest(name = "{0}: level_idc {1}")
        @CsvSource({
                "CONSTRAINED_BASELINE, 41",
                "MAIN, 41",
                "HIGH, 41",
                "HIGH_10, 40",
                "HIGH_422, 40",
                "HIGH_444_PREDICTIVE, 40"
        })
        @DisplayName("scales the bitrate limits by the profile's cpbBrVclFactor of Table A-2")
        void bitrateFactor(H264Profile profile, int expected) {
            var factor = Levels.bitrateFactor(profile);
            assertEquals(switch (profile) {
                case CONSTRAINED_BASELINE, MAIN -> 1000;
                case HIGH -> 1250;
                case HIGH_10 -> 3000;
                case HIGH_422, HIGH_444_PREDICTIVE -> 4000;
            }, factor);
            // level 4.0 allows 20000 units, 25 Mbps at 1250 bits per unit, 60 Mbps at 3000
            var level = Levels.select(factor, false, 120, 68, 30, 30_000_000, 0, 1);
            assertEquals(expected, level.idc());
        }
    }

    @Nested
    @DisplayName("parameter sets")
    class ParameterSetFields {
        @Test
        @DisplayName("write the profile, level, cropping and VUI fields readable by an independent parser")
        void sequenceParameterSet() {
            var config = H264Setup.of(1918, 1078, new Rational(25, 1), new Rational(1, 25),
                    H264Profile.HIGH, 0, H264RateControl.CONSTANT_QUALITY, 0, 0, 0, 26,
                    50, H264EncoderSpeed.FAST, ColorInfo.BT709_LIMITED, 1);
            var settings = new EncoderSettings(config.resolved());
            var reader = new BitReader(ParameterSets.sps(settings));
            assertEquals(100, reader.bits(8), "profile_idc");
            reader.bits(8);
            assertEquals(settings.levelIdc(), reader.bits(8), "level_idc");
            assertEquals(0, reader.ue(), "seq_parameter_set_id");
            assertEquals(1, reader.ue(), "chroma_format_idc");
            assertEquals(0, reader.ue(), "bit_depth_luma_minus8");
            assertEquals(0, reader.ue(), "bit_depth_chroma_minus8");
            assertEquals(0, reader.bit(), "qpprime_y_zero_transform_bypass_flag");
            assertEquals(0, reader.bit(), "seq_scaling_matrix_present_flag");
            assertEquals(settings.log2MaxFrameNum() - 4, reader.ue(), "log2_max_frame_num_minus4");
            assertEquals(2, reader.ue(), "pic_order_cnt_type");
            assertEquals(settings.refs(), reader.ue(), "max_num_ref_frames");
            assertEquals(0, reader.bit(), "gaps_in_frame_num_value_allowed_flag");
            assertEquals(119, reader.ue(), "pic_width_in_mbs_minus1");
            assertEquals(67, reader.ue(), "pic_height_in_map_units_minus1");
            assertEquals(1, reader.bit(), "frame_mbs_only_flag");
            assertEquals(1, reader.bit(), "direct_8x8_inference_flag");
            assertEquals(1, reader.bit(), "frame_cropping_flag");
            assertEquals(0, reader.ue(), "frame_crop_left_offset");
            assertEquals(1, reader.ue(), "frame_crop_right_offset");
            assertEquals(0, reader.ue(), "frame_crop_top_offset");
            assertEquals(5, reader.ue(), "frame_crop_bottom_offset");
            assertEquals(1, reader.bit(), "vui_parameters_present_flag");
        }

        @Test
        @DisplayName("enable CABAC and the 8x8 transform only where the profile allows them")
        void pictureParameterSet() {
            for (var profile : H264Profile.values()) {
                var config = H264Setup.of(320, 240, new Rational(30, 1), new Rational(1, 30), profile, 0,
                        H264RateControl.CONSTANT_QUALITY, 0, 0, 0, 26, 30, H264EncoderSpeed.FAST,
                        ColorInfo.BT709_LIMITED, 1);
                var settings = new EncoderSettings(config.resolved());
                var reader = new BitReader(ParameterSets.pps(settings));
                assertEquals(0, reader.ue(), "pic_parameter_set_id");
                assertEquals(0, reader.ue(), "seq_parameter_set_id");
                assertEquals(profile == H264Profile.CONSTRAINED_BASELINE ? 0 : 1, reader.bit(),
                        "entropy_coding_mode_flag of " + profile);
                assertEquals(settings.cabac(), profile != H264Profile.CONSTRAINED_BASELINE);
                assertEquals(settings.transform8x8(), profile.profileIdc() >= 100);
            }
        }
    }
}
