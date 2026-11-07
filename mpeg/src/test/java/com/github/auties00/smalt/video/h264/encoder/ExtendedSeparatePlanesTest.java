package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.FieldOrder;
import com.github.auties00.smalt.video.h264.H264StreamInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static com.github.auties00.smalt.video.h264.encoder.ExtendedFormatsTest.assertSame;
import static com.github.auties00.smalt.video.h264.encoder.ExtendedFormatsTest.decodeFfmpeg;
import static com.github.auties00.smalt.video.h264.encoder.ExtendedFormatsTest.decodeSmalt;
import static com.github.auties00.smalt.video.h264.encoder.ExtendedFormatsTest.frames;
import static com.github.auties00.smalt.video.h264.encoder.ExtendedFormatsTest.planes;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the separately coded color planes of the extended engine ({@code separate_colour_plane_flag}) in every bit
 * depth, entropy coder, field structure, B pictures, lossless coding and slice layout. ffmpeg does not decode such
 * streams, so each stream is also split into the three monochrome streams its planes are (ITU-T H.264 8: each plane
 * decodes as a monochrome picture): the sequence parameter set rewritten to {@code chroma_format_idc} 0 and every
 * {@code colour_plane_id} traded for two more {@code frame_num} bits, and ffmpeg decodes those. With the deblocking
 * filter disabled (a test hook) the encoder's reconstruction must equal what Smalt decodes from the stream and what
 * ffmpeg decodes from the split streams, sample for sample; with the filter enabled the two decoders must agree.
 */
@DisplayName("H264Encoder separate color planes")
class ExtendedSeparatePlanesTest {
    static H264EncoderOptions.Builder options(int depth) {
        return ExtendedFormatsTest.options(H264Profile.HIGH_444_PREDICTIVE, ChromaFormat.YUV444, depth)
                .separateColorPlanes(true);
    }

    static Stream<Arguments> configurations() {
        return Stream.of(
                Arguments.of("8-bit CABAC", options(8), false),
                Arguments.of("8-bit CAVLC", options(8).cavlc(true), false),
                Arguments.of("10-bit CABAC B pictures", options(10).lookahead(3).bFrames(2).keyframeInterval(12),
                        false),
                Arguments.of("12-bit CAVLC slices", options(12).cavlc(true).slices(3), false),
                Arguments.of("14-bit CABAC size-limited slices", options(14).maxSliceBytes(200), false),
                Arguments.of("8-bit CABAC macroblock pairs", options(8).fieldOrder(FieldOrder.TOP_FIRST)
                        .fieldCoding(H264FieldCoding.MBAFF), true),
                Arguments.of("10-bit CAVLC field pictures", options(10).cavlc(true).fieldOrder(FieldOrder.BOTTOM_FIRST)
                        .fieldCoding(H264FieldCoding.FIELD_PICTURES), true),
                Arguments.of("8-bit CABAC field pictures with B pictures", options(8).fieldOrder(FieldOrder.TOP_FIRST)
                        .fieldCoding(H264FieldCoding.FIELD_PICTURES).lookahead(3).bFrames(2).keyframeInterval(12), true),
                Arguments.of("8-bit CABAC macroblock pairs with B pictures", options(8)
                        .fieldOrder(FieldOrder.TOP_FIRST).fieldCoding(H264FieldCoding.MBAFF).lookahead(3).bFrames(2)
                        .keyframeInterval(12), true),
                Arguments.of("8-bit lossless CABAC", options(8).lossless(true), false),
                Arguments.of("10-bit lossless CAVLC macroblock pairs", options(10).lossless(true).cavlc(true)
                        .fieldOrder(FieldOrder.TOP_FIRST).fieldCoding(H264FieldCoding.MBAFF), true),
                Arguments.of("8-bit CABAC temporal layers", options(8).temporalLayers(3).keyframeInterval(16), false),
                Arguments.of("8-bit CABAC intra refresh", options(8).intraRefresh(3).noPeriodicKeyframes(), false));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurations")
    @DisplayName("reconstructs, without the deblocking filter, exactly what Smalt and ffmpeg decode")
    void bitExact(String name, H264EncoderOptions.Builder builder, boolean interlaced) {
        var options = builder.build();
        var depth = options.bitDepth();
        var pictures = frames(64, 48, ChromaFormat.YUV444, depth, 8, 7, interlaced);
        var run = ExtendedBPicturesTest.run(64, 48, options, pictures, false);
        assertStructure(run.packets(), 8);
        var smalt = decodeSmalt(run.packets());
        assertSame(run.encoderRecon(), smalt, name + " Smalt");
        assertSame(run.decoded(), smalt, name + " internal decoder");
        assertPlanesMatchFfmpeg(name, run.stream(), smalt, depth, 64, 48);
        if (options.lossless()) {
            for (var i = 0; i < pictures.size(); i++) {
                var source = planes(pictures.get(i));
                for (var c = 0; c < 3; c++) {
                    assertArrayEquals(source[c], smalt.get(i)[c], name + ": lossless frame " + i + ", plane " + c);
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurations")
    @DisplayName("decodes, with the deblocking filter, to the same pictures in Smalt and ffmpeg")
    void deblocked(String name, H264EncoderOptions.Builder builder, boolean interlaced) {
        var options = builder.build();
        var depth = options.bitDepth();
        var pictures = frames(64, 48, ChromaFormat.YUV444, depth, 6, 11, interlaced);
        var run = ExtendedBPicturesTest.run(64, 48, options, pictures, true);
        var smalt = decodeSmalt(run.packets());
        assertSame(run.decoded(), smalt, name + " internal decoder");
        assertPlanesMatchFfmpeg(name + "-deblocked", run.stream(), smalt, depth, 64, 48);
        for (var i = 0; i < pictures.size(); i++) {
            var source = planes(pictures.get(i));
            for (var c = 0; c < 3; c++) {
                assertTrue(psnr(source[c], smalt.get(i)[c], depth) > 30,
                        name + ": frame " + i + ", plane " + c + " stays close to the source");
            }
        }
    }

    @Nested
    @DisplayName("stream")
    class StreamTests {
        @Test
        @DisplayName("signals separate_colour_plane_flag and the stream info reports separate planes")
        void signaled() {
            var options = options(10).build();
            var run = ExtendedBPicturesTest.run(32, 32, options, frames(32, 32, ChromaFormat.YUV444, 10, 2, 3, false),
                    true);
            var sps = Split.parameterSet(run.stream());
            assertEquals(3, sps.chromaFormatIdc(), "chroma_format_idc");
            assertTrue(sps.separate(), "separate_colour_plane_flag");
            var info = H264StreamInfo.probe(run.stream());
            assertTrue(info.separateColorPlanes());
            assertEquals(ChromaFormat.YUV444, info.chromaFormat());
            assertEquals(10, info.bitDepthChroma());
            assertTrue(info.decoderSupported());
        }

        @Test
        @DisplayName("codes the planes together without the option, so the flag is clear")
        void together() {
            var options = ExtendedFormatsTest.options(H264Profile.HIGH_444_PREDICTIVE, ChromaFormat.YUV444, 8).build();
            var run = ExtendedBPicturesTest.run(32, 32, options, frames(32, 32, ChromaFormat.YUV444, 8, 2, 3, false),
                    true);
            assertFalse(Split.parameterSet(run.stream()).separate());
        }

        @Test
        @DisplayName("codes planes with independent motion in fewer bytes than the planes coded together")
        void independentMotion() {
            var pictures = independentlyMoving(64, 64, 10);
            var separate = ExtendedBPicturesTest.run(64, 64, options(8).quality(26).keyframeInterval(10).build(),
                    pictures, true);
            var together = ExtendedBPicturesTest.run(64, 64, ExtendedFormatsTest.options(
                            H264Profile.HIGH_444_PREDICTIVE, ChromaFormat.YUV444, 8).quality(26).keyframeInterval(10)
                    .build(), pictures, true);
            var separateBytes = separate.stream().length;
            var togetherBytes = together.stream().length;
            assertTrue(separateBytes < togetherBytes, "separate " + separateBytes + " bytes, together "
                    + togetherBytes + " bytes");
            var smalt = decodeSmalt(separate.packets());
            for (var i = 0; i < pictures.size(); i++) {
                var source = planes(pictures.get(i));
                for (var c = 0; c < 3; c++) {
                    assertTrue(psnr(source[c], smalt.get(i)[c], 8) > 30, "frame " + i + ", plane " + c);
                }
            }
        }

        @Test
        @DisplayName("predicts each plane from the same plane of its reference")
        void perPlaneReference() {
            var pictures = independentlyMoving(64, 64, 4);
            var run = ExtendedBPicturesTest.run(64, 64, options(8).quality(20).keyframeInterval(4).build(), pictures,
                    false);
            var smalt = decodeSmalt(run.packets());
            assertSame(run.encoderRecon(), smalt, "independent motion");
            assertPlanesMatchFfmpeg("independent", run.stream(), smalt, 8, 64, 64);
            assertTrue(run.types()[4] > 0, "P_L0_16x16 macroblocks were coded");
        }
    }

    @Nested
    @DisplayName("options")
    class OptionsTests {
        @Test
        @DisplayName("are off by default and survive toBuilder, equals and hashCode")
        void roundTrip() {
            var defaults = H264EncoderOptions.defaults();
            assertFalse(defaults.separateColorPlanes());
            var options = options(8).build();
            assertTrue(options.separateColorPlanes());
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertNotEquals(options, options.toBuilder().separateColorPlanes(false).build());
            assertTrue(options.toString().contains("separateColorPlanes=true"));
        }

        @Test
        @DisplayName("reject every chroma format but 4:4:4")
        void needs444() {
            for (var format : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.MONOCHROME)) {
                var builder = ExtendedFormatsTest.options(H264Profile.HIGH_444_PREDICTIVE, format, 8)
                        .separateColorPlanes(true);
                assertThrows(IllegalArgumentException.class, builder::build, format.toString());
            }
        }
    }

    /**
     * Frames whose three planes carry textures moving in different directions: Y to the right, Cb to the left,
     * Cr down.
     */
    static List<Picture> independentlyMoving(int width, int height, int count) {
        var texture = new int[128 * 128];
        var random = new Random(5);
        for (var y = 0; y < 128; y++) {
            for (var x = 0; x < 128; x++) {
                texture[y * 128 + x] = (int) Math.clamp(128 + 60 * Math.sin(x * 0.31) * Math.cos(y * 0.23)
                        + 30 * Math.sin((x + 2 * y) * 0.11) + random.nextGaussian() * 3, 0, 255);
            }
        }
        var list = new ArrayList<Picture>();
        for (var f = 0; f < count; f++) {
            var picture = Yuv16Picture.allocate(width, height, ChromaFormat.YUV444, 8, false, ColorInfo.BT709_LIMITED);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    picture.y()[y * picture.yStride() + x] = (short) texture[(y + 7) * 128 + x + 2 * f + 9];
                    picture.u()[y * picture.uvStride() + x] = (short) texture[(y + 31) * 128 + x + 40 - 3 * f];
                    picture.v()[y * picture.uvStride() + x] = (short) texture[(y + 2 * f + 3) * 128 + x + 21];
                }
            }
            list.add(picture);
        }
        return list;
    }

    static double psnr(short[] expected, short[] actual, int depth) {
        double error = 0;
        for (var i = 0; i < expected.length; i++) {
            var d = expected[i] - actual[i];
            error += (double) d * d;
        }
        var max = (1 << depth) - 1;
        var mse = error / expected.length;
        return mse == 0 ? 99 : 10 * Math.log10((double) max * max / mse);
    }

    /** Checks that every access unit holds the slices of plane 0, then plane 1, then plane 2. */
    static void assertStructure(List<Packet> packets, int frames) {
        assertEquals(frames, packets.size(), "one packet per frame");
        for (var p = 0; p < packets.size(); p++) {
            var planes = Split.slicePlanes(packets.get(p).data());
            assertFalse(planes.isEmpty(), "packet " + p + " has slices");
            var previous = 0;
            var seen = new boolean[3];
            for (var plane : planes) {
                seen[plane] = true;
                // a field pair restarts the order for its second field
                assertTrue(plane >= previous || plane == 0, "packet " + p + ": plane order " + planes);
                previous = plane;
            }
            assertTrue(seen[0] && seen[1] && seen[2], "packet " + p + " codes every plane: " + planes);
        }
    }

    /** Decodes the three monochrome streams of a separate-plane stream with ffmpeg and compares each plane. */
    static void assertPlanesMatchFfmpeg(String name, byte[] stream, List<short[][]> expected, int depth, int width,
                                        int height) {
        var tag = name.replaceAll("[^A-Za-z0-9]+", "-");
        for (var c = 0; c < 3; c++) {
            var mono = Split.plane(stream, c);
            var decoded = decodeFfmpeg("separate-" + tag + "-plane" + c, mono, ChromaFormat.MONOCHROME, depth,
                    width, height);
            assertEquals(expected.size(), decoded.size(), name + ": ffmpeg frame count of plane " + c);
            for (var i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i)[c], decoded.get(i)[0], name + ": ffmpeg frame " + i + ", plane " + c);
            }
        }
    }

    /**
     * Splits an Annex B stream with separately coded color planes into the monochrome stream of each plane, and
     * reads the fields the tests check. The sequence parameter set becomes {@code chroma_format_idc} 0 with
     * {@code log2_max_frame_num_minus4} two larger, and each slice of the plane trades its 2-bit
     * {@code colour_plane_id} for a {@code frame_num} 2 bits wider holding the same value, so every slice keeps its
     * length and the byte alignment of its CABAC data and {@code I_PCM} samples. The streams the tests split are
     * shorter than the {@code MaxFrameNum} of the encoder, so the wider {@code frame_num} never wraps differently.
     */
    static final class Split {
        /** The fields of a sequence parameter set the tests read. */
        record Sps(int chromaFormatIdc, boolean separate, int log2MaxFrameNum) {
        }

        static Sps parameterSet(byte[] stream) {
            for (var nal : units(stream)) {
                if ((nal[0] & 0x1F) == 7) {
                    return parseSps(rbsp(nal), null);
                }
            }
            throw new AssertionError("no sequence parameter set");
        }

        /** Returns the {@code colour_plane_id} of each slice of an access unit, in order. */
        static List<Integer> slicePlanes(byte[] accessUnit) {
            var planes = new ArrayList<Integer>();
            for (var nal : units(accessUnit)) {
                var type = nal[0] & 0x1F;
                if (type == 1 || type == 5) {
                    var reader = new Reader(rbsp(nal), 8);
                    reader.ue();
                    reader.ue();
                    reader.ue();
                    planes.add(reader.bits(2));
                }
            }
            return planes;
        }

        /** Returns the monochrome Annex B stream of one color plane. */
        static byte[] plane(byte[] stream, int plane) {
            var out = new ByteArrayOutputStream();
            Sps sps = null;
            for (var nal : units(stream)) {
                var type = nal[0] & 0x1F;
                var payload = rbsp(nal);
                byte[] rewritten;
                if (type == 7) {
                    var writer = new Writer();
                    sps = parseSps(payload, writer);
                    assertTrue(sps.separate(), "separate_colour_plane_flag");
                    rewritten = writer.bytes();
                } else if (type == 1 || type == 5) {
                    var reader = new Reader(payload, 8);
                    reader.ue();
                    reader.ue();
                    reader.ue();
                    var planeAt = reader.position;
                    if (reader.bits(2) != plane) {
                        continue;
                    }
                    var frameNum = reader.bits(sps.log2MaxFrameNum());
                    var writer = new Writer();
                    writer.copy(payload, 0, planeAt);
                    writer.bits(frameNum, sps.log2MaxFrameNum() + 2);
                    writer.copy(payload, reader.position, payload.length * 8);
                    rewritten = writer.raw();
                    assertEquals(payload.length, rewritten.length, "slice length");
                } else {
                    rewritten = payload;
                }
                out.writeBytes(new byte[]{0, 0, 0, 1});
                out.writeBytes(escape(rewritten));
            }
            return out.toByteArray();
        }

        /** Parses a sequence parameter set; with a writer, writes the monochrome one of the planes. */
        private static Sps parseSps(byte[] payload, Writer writer) {
            var reader = new Reader(payload, 8);
            var profile = reader.bits(8);
            reader.bits(16);
            reader.ue();
            var chromaAt = reader.position;
            var chroma = 1;
            var separate = false;
            var high = profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44;
            if (high) {
                chroma = reader.ue();
                if (chroma == 3) {
                    separate = reader.bits(1) == 1;
                }
            }
            var afterChroma = reader.position;
            if (high) {
                reader.ue();
                reader.ue();
                reader.bits(1);
                assertEquals(0, reader.bits(1), "seq_scaling_matrix_present_flag");
            }
            var frameNumAt = reader.position;
            var log2MaxFrameNum = reader.ue() + 4;
            var afterFrameNum = reader.position;
            if (writer != null) {
                writer.copy(payload, 0, chromaAt);
                writer.ue(0);
                writer.copy(payload, afterChroma, frameNumAt);
                writer.ue(log2MaxFrameNum - 4 + 2);
                writer.copy(payload, afterFrameNum, lastOne(payload));
            }
            return new Sps(chroma, separate, log2MaxFrameNum);
        }

        /** Returns the bit position of the {@code rbsp_stop_one_bit}. */
        private static int lastOne(byte[] payload) {
            for (var i = payload.length - 1; i >= 0; i--) {
                if (payload[i] != 0) {
                    return i * 8 + 7 - Integer.numberOfTrailingZeros(payload[i] & 0xFF);
                }
            }
            throw new AssertionError("no stop bit");
        }

        /** Returns the NAL units of an Annex B stream, emulation prevention bytes still in place. */
        static List<byte[]> units(byte[] stream) {
            var starts = new ArrayList<Integer>();
            for (var i = 0; i + 2 < stream.length; i++) {
                if (stream[i] == 0 && stream[i + 1] == 0 && stream[i + 2] == 1) {
                    starts.add(i + 3);
                    i += 2;
                }
            }
            var units = new ArrayList<byte[]>();
            for (var k = 0; k < starts.size(); k++) {
                var start = starts.get(k);
                var end = k + 1 < starts.size() ? starts.get(k + 1) - 3 : stream.length;
                while (end > start && stream[end - 1] == 0 && k + 1 < starts.size()) {
                    end--;
                }
                var unit = new byte[end - start];
                System.arraycopy(stream, start, unit, 0, unit.length);
                units.add(unit);
            }
            return units;
        }

        /** Removes the emulation prevention bytes of a NAL unit. */
        static byte[] rbsp(byte[] nal) {
            var out = new ByteArrayOutputStream();
            var zeros = 0;
            for (var b : nal) {
                if (zeros >= 2 && b == 3) {
                    zeros = 0;
                    continue;
                }
                out.write(b);
                zeros = b == 0 ? zeros + 1 : 0;
            }
            return out.toByteArray();
        }

        /** Inserts the emulation prevention bytes of a NAL unit. */
        static byte[] escape(byte[] rbsp) {
            var out = new ByteArrayOutputStream();
            var zeros = 0;
            for (var b : rbsp) {
                if (zeros >= 2 && (b & 0xFF) <= 3) {
                    out.write(3);
                    zeros = 0;
                }
                out.write(b);
                zeros = b == 0 ? zeros + 1 : 0;
            }
            return out.toByteArray();
        }

        /** Reads bits most significant first. */
        static final class Reader {
            final byte[] data;

            int position;

            Reader(byte[] data, int position) {
                this.data = data;
                this.position = position;
            }

            int bits(int count) {
                var value = 0;
                for (var i = 0; i < count; i++) {
                    var index = position >> 3;
                    var bit = index < data.length ? (data[index] >> (7 - (position & 7))) & 1 : 0;
                    value = (value << 1) | bit;
                    position++;
                }
                return value;
            }

            int ue() {
                var leading = 0;
                while (bits(1) == 0) {
                    leading++;
                    assertTrue(leading < 32, "Exp-Golomb code");
                }
                return (1 << leading) - 1 + bits(leading);
            }

            int se() {
                var code = ue();
                return (code & 1) != 0 ? (code + 1) / 2 : -(code / 2);
            }
        }

        /** Writes bits most significant first. */
        static final class Writer {
            private final ByteArrayOutputStream out = new ByteArrayOutputStream();

            private int current;

            int count;

            void bit(int bit) {
                current = (current << 1) | bit;
                if ((++count & 7) == 0) {
                    out.write(current);
                    current = 0;
                }
            }

            void bits(int value, int width) {
                for (var i = width - 1; i >= 0; i--) {
                    bit((value >> i) & 1);
                }
            }

            void ue(int value) {
                var code = value + 1;
                var length = 32 - Integer.numberOfLeadingZeros(code);
                bits(0, length - 1);
                bits(code, length);
            }

            /** Copies the bits {@code [from, to)} of a payload. */
            void copy(byte[] data, int from, int to) {
                for (var position = from; position < to; position++) {
                    bit((data[position >> 3] >> (7 - (position & 7))) & 1);
                }
            }

            /** Returns the bytes written, which end on a byte boundary. */
            byte[] raw() {
                assertEquals(0, count & 7, "byte aligned");
                return out.toByteArray();
            }

            /** Appends the RBSP trailing bits and returns the bytes. */
            byte[] bytes() {
                bit(1);
                while ((count & 7) != 0) {
                    bit(0);
                }
                return out.toByteArray();
            }
        }
    }
}
