package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks the decoding of H.264 streams with 4:4:4 chroma ({@code chroma_format_idc} 3), coded with
 * the color planes together or separately ({@code separate_colour_plane_flag}), and of lossless
 * streams ({@code qpprime_y_zero_transform_bypass_flag}) of every chroma format. The ITU-T
 * Professional_profiles conformance streams, which come without a reference YUV, and the streams of
 * {@code chroma444/} are compared frame by frame, as the MD5 of their planar samples (8-bit, or
 * 16-bit little-endian for deeper streams), with the hashes in {@code chroma444/frames.tsv}: the
 * output of the JM reference decoder, with which FFmpeg agrees on every stream it decodes, or for
 * the separate-plane streams merged from monochrome JM streams the stacked reconstructions of those
 * (see {@code chroma444/streams.tsv}). x264 streams are compared with FFmpeg at test time, and
 * x264's lossless streams with their source.
 */
@DisplayName("H.264 4:4:4 and lossless decoding")
class H264Chroma444Test {
    private static final String BASE = "https://www.itu.int/wftp3/av-arch/jvt-site/draft_conformance/Professional_profiles/";

    private static final Map<String, List<String>> GOLDEN = loadGolden();

    private static Map<String, List<String>> loadGolden() {
        try (var input = H264Chroma444Test.class.getResourceAsStream("chroma444/frames.tsv")) {
            assertNotNull(input, "chroma444/frames.tsv");
            var result = new LinkedHashMap<String, List<String>>();
            for (var line : new String(input.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                var parts = line.strip().split("\t");
                if (parts.length != 3 || line.startsWith("#")) {
                    continue;
                }
                var frames = result.computeIfAbsent(parts[0], key -> new ArrayList<>());
                assertEquals(frames.size(), Integer.parseInt(parts[1]), "frame index in " + parts[0]);
                frames.add(parts[2]);
            }
            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * An ITU-T H.264.1 Professional_profiles 4:4:4 conformance bitstream: a raw {@code .bits} file,
     * or an entry of a {@code .zip} archive.
     */
    record Vector(String name, String file, String sha256, String entry) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Vector> conformance() {
        return Stream.of(
                // separate color planes, 14 bits, 1920x1080, CAVLC 4:4:4 Intra, High 4:4:4 Intra and Predictive
                new Vector("PPCV444I4_Mitsubishi_A", "PPCV444I4_Mitsubishi_A.zip",
                        "efb23464b231f0b875a9b04f06ac6622a1a162d7d4bd08690fb3b05073d49f26", "PPCV444I4_Mitsubishi_A.264"),
                new Vector("PPCV444I5_Mitsubishi_A", "PPCV444I5_Mitsubishi_A.zip",
                        "5c47f46f6d69c306a1a11909f10e4e9a4bf0398aee4bc217337196fb37e45e57", "PPCV444I5_Mitsubishi_A.264"),
                new Vector("PPCV444I6_Mitsubishi_A", "PPCV444I6_Mitsubishi_A.zip",
                        "891b5835a73c6f5befe4f7c2a78ee757658c478d39237cee0da65dace505409a", "PPCV444I6_Mitsubishi_A.264"),
                new Vector("PPH444I4_Mitsubishi_A", "PPH444I4_Mitsubishi_A.zip",
                        "2d80e731478e99b53d7d4cf45894c66017cbddc99a7f8b0126768f474b9019a2", "PPH444I4_Mitsubishi_A.264"),
                new Vector("PPH444I5_Mitsubishi_A", "PPH444I5_Mitsubishi_A.zip",
                        "c55c784af7014b45c6b6ad96c7542dd3b143c27c375dc1ff342e0d505d20b436", "PPH444I5_Mitsubishi_A.264"),
                new Vector("PPH444I6_Mitsubishi_A", "PPH444I6_Mitsubishi_A.zip",
                        "fb3454b6a33ba918383765ac12d0cfe91325bb2780ed4fa7c7d0e7cb6bfd6661", "PPH444I6_Mitsubishi_A.264"),
                new Vector("PPH444P6_Mitsubishi_A", "PPH444P6_Mitsubishi_A.zip",
                        "9bf0e858a95025db7f6ee3062b732ad405855860ff50d5ee7accaebda0245d14", "PPH444P6_Mitsubishi_A.264"),
                new Vector("PPH444P7_Mitsubishi_A", "PPH444P7_Mitsubishi_A.zip",
                        "a1b66b70ebbfdd5937486836439fb91d94b952364c9b373d3a072f8442db18fa", "PPH444P7_Mitsubishi_A.264"),
                new Vector("PPH444P8_Mitsubishi_A", "PPH444P8_Mitsubishi_A.zip",
                        "6ff783ccd0edbbf22ea88820c77b31d66fd36b7c60c42ddf9728d6bda418e86b", "PPH444P8_Mitsubishi_A.264"),
                new Vector("PPH444P9_Mitsubishi_A", "PPH444P9_Mitsubishi_A.zip",
                        "54044fa3d999f12cc1535fd0edc671f7edb251942785ad09638781b40430a1b1", "PPH444P9_Mitsubishi_A.264"),
                // separate color planes, lossless, 14 bits, 176x144
                new Vector("PPCV444I7_SejongUniv_A", "PPCV444I7_SejongUniv_A.zip",
                        "f80dfb9191f13c903d1b4b12d0ada1a3b0dd840d730bd7dfd8e9977446587586", "PPCV444I-7.264"),
                new Vector("PPH444I7_SejongUniv_A", "PPH444I7_SejongUniv_A.zip",
                        "681c4c144f7018e60e9649841747a7bee4e1510180e5aedd5cc9e03adcb9bf43", "PPH444I-7.264"),
                new Vector("PPH444P10_SejongUniv_A", "PPH444P10_SejongUniv_A.zip",
                        "ad7b0f3c1130267ea28fc4dfdd70c7f4e38b33476ce1d0672f1a301ced9c1d4f", "PPH444P-10.264"),
                // color planes coded together, 14 bits, 1920x1080, 118 to 120 frames
                new Vector("PPCV444I1_2008", "PPCV444I1_2008.bits",
                        "e4ac11cf201734e403435fd03af1f499a6c1a5769a151f38f9e8b8518d390f68", null),
                new Vector("PPCV444I2_2008", "PPCV444I2_2008.bits",
                        "95a6bc8c82c958c4e0af79cfd7775a9194aab26270cfaddbd3dbb11a8645288f", null),
                new Vector("PPCV444I3_2008", "PPCV444I3_2008.bits",
                        "e45845ce2886ba5bd4961d3809cb22a1a308481f5688d949ed158a76e54ae705", null),
                new Vector("PPH444I1_2008", "PPH444I1_2008.bits",
                        "25c73da54f8f66900b08607b5f437476ff06b21472f80f417f6381ff25a3df72", null),
                new Vector("PPH444I2_2008", "PPH444I2_2008.bits",
                        "1be5a6dc3629df641ac3ecdd2c4eb29612d5cf9155ae56c664f128e22f84980a", null),
                new Vector("PPH444I3_2008", "PPH444I3_2008.bits",
                        "08d597d744e57a4b7fbfad5f27b2a9b4e61b92815b77e27013cad6a08eb7d282", null),
                new Vector("PPH444P1_2008", "PPH444P1_2008.bits",
                        "3738831430d47468db55b852b9074372173ec62b1f4550b571d91d00b4dd09f7", null),
                new Vector("PPH444P2_2008", "PPH444P2_2008.bits",
                        "52e88fe0c3b6017c8ffa3dd2357110fd6c835486deabadfbc4b4301e4131a35f", null),
                new Vector("PPH444P3_2008", "PPH444P3_2008.bits",
                        "37cd73a73de777441a281774485adf229297732eb9ee3bb755a21e3d14f2902b", null),
                new Vector("PPH444P4_2008", "PPH444P4_2008.bits",
                        "3f356ccc3ce0e087026c2dae3dda8cc2a83e7595a2493922638539019b560259", null),
                new Vector("PPH444P5_2008", "PPH444P5_2008.bits",
                        "8dac60e7b49cfaa42a79a9e78e9959cf13006f0b91b03de1202c396b134f39c9", null));
    }

    static Stream<Vector> threadedConformance() {
        return conformance().filter(vector -> vector.name().matches(
                "PPH444P8_Mitsubishi_A|PPH444P9_Mitsubishi_A|PPH444P10_SejongUniv_A|PPH444P2_2008|PPCV444I3_2008"));
    }

    static byte[] bitstream(Vector vector) {
        var file = Conformance.fetch("h264-Professional_profiles-" + vector.file(), BASE + vector.file(),
                vector.sha256());
        try {
            if (vector.entry() == null) {
                return Files.readAllBytes(file);
            }
            try (var zip = new ZipFile(file.toFile())) {
                return zip.getInputStream(zip.getEntry(vector.entry())).readAllBytes();
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * The streams stored with the tests (see {@code chroma444/streams.tsv} for how each was made):
     * 4:4:4 coded together (c), separate color planes (m), lossless (l, q) and x264 (x).
     */
    static Stream<String> encoded() {
        return Stream.of("c8-cabac-pcm", "c8-cabac-pcm-left", "c10-cavlc-pcm", "c10-8-cabac", "c8-12-cavlc", "c12-cavlc-scaling",
                "c8-cabac-scaling-pps", "c14-paff", "c10-fields-temporal", "c8-cabac-cip-slices", "c8-cavlc-intra",
                "m8-cavlc-sep", "m10-cabac-mbaff-pcm-sep", "m12-fields-wp-sep", "m8-cabac-slices-sep",
                "m14-cavlc-fields-sep", "m8-cavlc-scaling-sep", "m8-lossless-mbaff-sep", "l8-444-cavlc", "l10-444-cabac-fields",
                "l8-420-cabac", "l10-420-cavlc", "l10-422-cabac", "l8-422-cavlc-fields", "l8-444-pcm",
                "q8-444-mixed-lossless", "x8-mbaff-wp", "x10-mbaff-cabac-wp", "x8-lossless-mbaff", "x8-cavlc-8x8-wp",
                "x8-rgb-lossless", "x8-rgb", "x10-mbaff-deblock", "x8-fade-wp");
    }

    static byte[] resource(String name) {
        try (var input = H264Chroma444Test.class.getResourceAsStream("chroma444/" + name + ".264")) {
            assertNotNull(input, name);
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static void assertGolden(String name, List<String> actual) {
        var expected = GOLDEN.get(name);
        assertNotNull(expected, "no golden hashes for " + name);
        for (var i = 0; i < Math.min(expected.size(), actual.size()); i++) {
            if (!expected.get(i).equals(actual.get(i))) {
                fail(name + ": frame " + i + " differs from the reference decoders");
            }
        }
        assertEquals(expected.size(), actual.size(), "frame count of " + name);
    }

    private static H264DecoderOptions wide(int threads) {
        return H264DecoderOptions.builder().highBitDepth(true).threads(threads).build();
    }

    private static ChromaFormat format(Picture picture) {
        return picture instanceof Yuv16Picture deep ? deep.chromaFormat() : ((YuvPicture) picture).chromaFormat();
    }

    static byte[] serialize(Picture picture) {
        if (picture instanceof Yuv16Picture deep) {
            return H264HighBitDepthTest.serialize(deep);
        }
        return H264TestStreams.serialize((YuvPicture) picture);
    }

    @Nested
    @DisplayName("ITU-T conformance bitstreams")
    class ConformanceStreams {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264Chroma444Test#conformance")
        @DisplayName("decodes every frame bit-exactly")
        void bitExact(Vector vector) {
            assertGolden(vector.name(), H264HighBitDepthTest.decodeHashes(bitstream(vector), wide(1)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264Chroma444Test#threadedConformance")
        @DisplayName("decodes bit-exactly with four frame threads")
        void threaded(Vector vector) {
            assertGolden(vector.name(), H264HighBitDepthTest.decodeHashes(bitstream(vector), wide(4)));
        }
    }

    @Nested
    @DisplayName("JM and x264 streams")
    class EncodedStreams {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264Chroma444Test#encoded")
        @DisplayName("decodes every frame bit-exactly")
        void bitExact(String name) {
            assertGolden(name, H264HighBitDepthTest.decodeHashes(resource(name), wide(1)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264Chroma444Test#encoded")
        @DisplayName("decodes bit-exactly with four frame threads")
        void threaded(String name) {
            assertGolden(name, H264HighBitDepthTest.decodeHashes(resource(name), wide(4)));
        }
    }

    /**
     * The lossless x264 settings: chroma formats, depths, entropy coders, transforms, frame types
     * and interlacing, with {@code libx264rgb} coding GBR planes as 4:4:4.
     */
    static Stream<Arguments> losslessSettings() {
        return Stream.of(
                Arguments.of("444-cabac-bframes", "yuv444p", "libx264", "qp=0:bframes=2:8x8dct=1"),
                Arguments.of("444-cavlc", "yuv444p", "libx264", "qp=0:cabac=0:bframes=1:8x8dct=0"),
                Arguments.of("444-mbaff", "yuv444p", "libx264", "qp=0:interlaced=1:bframes=1"),
                Arguments.of("444-10-cabac", "yuv444p10le", "libx264", "qp=0:bframes=2"),
                Arguments.of("420-cabac", "yuv420p", "libx264", "qp=0:bframes=2"),
                Arguments.of("420-cavlc-mbaff", "yuv420p", "libx264", "qp=0:cabac=0:interlaced=1:bframes=1"),
                Arguments.of("422-cabac", "yuv422p", "libx264", "qp=0:bframes=1"),
                Arguments.of("420-10-cavlc", "yuv420p10le", "libx264", "qp=0:cabac=0"),
                Arguments.of("gbr-cabac", "gbrp", "libx264rgb", "qp=0:bframes=1"),
                Arguments.of("gbr-cavlc-intra", "gbrp", "libx264rgb", "qp=0:cabac=0:keyint=1"));
    }

    @Nested
    @DisplayName("lossless x264 streams")
    class Lossless {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264Chroma444Test#losslessSettings")
        @DisplayName("decode to their source exactly, with one and four threads")
        void equalsSource(String name, String pixelFormat, String encoder, String parameters) {
            var input = List.of("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=25,noise=alls=20:allf=t+u");
            var sourceFile = Fixtures.ffmpeg("yuv", input, List.of("-frames:v", "6", "-pix_fmt", pixelFormat,
                    "-f", "rawvideo"));
            var source = Fixtures.read(sourceFile);
            // the encoder reads the source file, so a libx264rgb that takes packed RGB only (FFmpeg 6) gets the
            // same samples through a lossless reordering
            var stream = Fixtures.ffmpeg("h264", List.of("-f", "rawvideo", "-pix_fmt", pixelFormat, "-s", "96x64",
                    "-r", "25", "-i", sourceFile.toString()), List.of("-c:v", encoder, "-x264-params", parameters,
                    "-f", "h264"));
            var info = H264StreamInfo.probe(Fixtures.read(stream));
            assertTrue(info.transformBypass(), "the stream allows transform-bypass macroblocks");
            assertTrue(info.decoderSupported());
            for (var threads : new int[]{1, 4}) {
                var pictures = H264HighBitDepthTest.decodePictures(stream, wide(threads));
                assertEquals(6, pictures.size(), name + " frames with " + threads + " threads");
                var decoded = new ByteArrayOutputStream();
                for (var picture : pictures) {
                    decoded.writeBytes(serialize(picture));
                }
                if (!Arrays.equals(source, decoded.toByteArray())) {
                    fail(name + ": the decoded frames differ from the source with " + threads + " threads");
                }
            }
        }
    }

    /**
     * The x264 settings of the 4:4:4 differential tests, each at 8 and at 10 bits: entropy coders,
     * transforms, frame types, weighted prediction, interlacing, scaling matrices, chroma offsets,
     * deblocking offsets and slices.
     */
    static Stream<Arguments> x264Settings() {
        var settings = List.of(
                List.of("cavlc-p", "cabac=0:bframes=0:qp=24:8x8dct=0"),
                List.of("cavlc-8x8", "cabac=0:bframes=2:qp=22:8x8dct=1"),
                List.of("cabac-b-pyramid", "bframes=3:b-pyramid=normal:ref=4:qp=20"),
                List.of("weightp", "bframes=0:weightp=2:qp=22:ref=3"),
                List.of("weightb-temporal", "bframes=2:weightb=1:direct=temporal:qp=26"),
                List.of("low-qp", "bframes=2:qp=3:chroma-qp-offset=-6"),
                List.of("high-qp", "bframes=2:qp=46:chroma-qp-offset=6"),
                List.of("cqm-jvt", "bframes=2:cqm=jvt:qp=18:8x8dct=1"),
                List.of("deblock-slices", "bframes=1:slices=4:deblock=-3,2:qp=30"),
                List.of("mbaff-cabac", "interlaced=1:bframes=2:qp=22"),
                List.of("mbaff-cavlc", "interlaced=1:cabac=0:bframes=1:qp=26:weightp=1"),
                List.of("intra-refresh", "bframes=0:intra-refresh=1:keyint=6:qp=24"),
                List.of("partitions-all", "bframes=2:partitions=all:subme=9:direct=auto:weightb=1:ref=3:qp=18"),
                List.of("constrained-intra", "bframes=0:constrained-intra=1:keyint=4:qp=20"));
        var arguments = new ArrayList<Arguments>();
        for (var format : List.of("yuv444p", "yuv444p10le")) {
            for (var setting : settings) {
                arguments.add(Arguments.of(setting.get(0) + "-" + format, format, setting.get(1)));
            }
        }
        return arguments.stream();
    }

    static Stream<Arguments> croppedSettings() {
        return Stream.of(
                Arguments.of("cropped-cabac", "yuv444p", "bframes=2:qp=20"),
                Arguments.of("cropped-mbaff", "yuv444p", "interlaced=1:cabac=0:bframes=1:qp=22"),
                Arguments.of("cropped-cabac-10", "yuv444p10le", "bframes=2:qp=20"),
                Arguments.of("cropped-mbaff-10", "yuv444p10le", "interlaced=1:bframes=1:qp=22"));
    }

    static Stream<String> chroma444Formats() {
        return Stream.of("yuv444p", "yuv444p10le");
    }

    /**
     * Returns the frames of a reference decode by FFmpeg as planar data: 8-bit samples, or 16-bit
     * little-endian samples for a 10-bit format.
     */
    static List<byte[]> oracleFrames(Path stream, String pixelFormat, int width, int height) {
        var raw = Oracle.decodeVideo(stream, List.of("-f", "h264"), pixelFormat, 0);
        var bytes = pixelFormat.endsWith("le") ? 2 : 1;
        var size = bytes * 3 * width * height;
        var frames = new ArrayList<byte[]>();
        for (var at = 0; at + size <= raw.length; at += size) {
            frames.add(Arrays.copyOfRange(raw, at, at + size));
        }
        return frames;
    }

    @Nested
    @DisplayName("x264 streams against FFmpeg")
    class Differential {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264Chroma444Test#x264Settings")
        @DisplayName("decodes every frame as FFmpeg does, with one and four threads")
        void matchesFfmpeg(String name, String pixelFormat, String parameters) {
            assertMatchesFfmpeg(name, pixelFormat, parameters, 192, 128);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264Chroma444Test#croppedSettings")
        @DisplayName("decodes cropped pictures as FFmpeg does, with one and four threads")
        void croppedMatchesFfmpeg(String name, String pixelFormat, String parameters) {
            assertMatchesFfmpeg(name, pixelFormat, parameters, 202, 116);
        }

        private static void assertMatchesFfmpeg(String name, String pixelFormat, String parameters, int width,
                                                int height) {
            var stream = H264HighBitDepthTest.x264(pixelFormat, parameters, width, height);
            var expected = oracleFrames(stream, pixelFormat, width, height);
            assertEquals(12, expected.size(), "frames FFmpeg decoded");
            var depth = pixelFormat.endsWith("10le") ? 10 : 8;
            for (var threads : new int[]{1, 4}) {
                var actual = H264HighBitDepthTest.decodePictures(stream, wide(threads));
                assertEquals(expected.size(), actual.size(), "frame count with " + threads + " threads");
                for (var i = 0; i < actual.size(); i++) {
                    var picture = actual.get(i);
                    assertEquals(depth == 10 ? Yuv16Picture.class : YuvPicture.class, picture.getClass());
                    assertEquals(ChromaFormat.YUV444, format(picture));
                    assertEquals(width, picture.width());
                    assertEquals(height, picture.height());
                    if (!Arrays.equals(expected.get(i), serialize(picture))) {
                        fail(name + ": frame " + i + " differs from FFmpeg with " + threads + " threads");
                    }
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264Chroma444Test#chroma444Formats")
        @DisplayName("crops on every side, at odd rows and columns, the frames FFmpeg decodes uncropped")
        void croppedEverySide(String pixelFormat) {
            var uncropped = Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=25"),
                    List.of("-frames:v", "6", "-pix_fmt", pixelFormat, "-c:v", "libx264", "-bf", "2", "-f", "h264"));
            // the same slices under a sequence parameter set that crops; the 4:4:4 crop unit is 1 luma column
            // and 1 luma row of a progressive frame, for chroma as for luma
            var stream = Fixtures.ffmpeg("h264", List.of("-i", uncropped.toAbsolutePath().toString()),
                    List.of("-c", "copy", "-bsf:v", "h264_metadata=crop_left=3:crop_right=4:crop_top=3:crop_bottom=5",
                            "-f", "h264"));
            var bytes = pixelFormat.endsWith("le") ? 2 : 1;
            var expected = new ArrayList<byte[]>();
            for (var frame : oracleFrames(uncropped, pixelFormat, 96, 64)) {
                var cropped = new ByteArrayOutputStream();
                for (var plane = 0; plane < 3; plane++) {
                    var base = bytes * plane * 96 * 64;
                    for (var y = 3; y < 59; y++) {
                        cropped.write(frame, base + bytes * (y * 96 + 3), bytes * 89);
                    }
                }
                expected.add(cropped.toByteArray());
            }
            assertEquals(6, expected.size(), "frames FFmpeg decoded");
            for (var threads : new int[]{1, 4}) {
                var actual = H264HighBitDepthTest.decodePictures(stream, wide(threads));
                assertEquals(expected.size(), actual.size(), "frame count with " + threads + " threads");
                for (var i = 0; i < actual.size(); i++) {
                    assertEquals(89, actual.get(i).width());
                    assertEquals(56, actual.get(i).height());
                    if (!Arrays.equals(expected.get(i), serialize(actual.get(i)))) {
                        fail(pixelFormat + ": frame " + i + " differs from FFmpeg with " + threads + " threads");
                    }
                }
            }
        }

        @Test
        @DisplayName("decodes libx264rgb GBR streams as FFmpeg does, the planes in G, B, R order with the identity matrix")
        void rgbMatchesFfmpeg() {
            var input = List.of("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=25,noise=alls=8:allf=t");
            var stream = Fixtures.ffmpeg("h264", input, List.of("-frames:v", "8", "-pix_fmt", "gbrp", "-c:v",
                    "libx264rgb", "-x264-params", "qp=24:bframes=2:weightp=2:8x8dct=1", "-f", "h264"));
            var expected = oracleFrames(stream, "gbrp", 96, 64);
            assertEquals(8, expected.size(), "frames FFmpeg decoded");
            var actual = H264HighBitDepthTest.decodePictures(stream, H264DecoderOptions.defaults());
            assertEquals(8, actual.size());
            for (var i = 0; i < actual.size(); i++) {
                assertArrayEquals(expected.get(i), serialize(actual.get(i)), "frame " + i);
                assertEquals(MatrixCoefficients.IDENTITY, ((YuvPicture) actual.get(i)).color().matrix());
            }
        }

        @Test
        @DisplayName("rounds 10-bit 4:4:4 samples to 8 bits by default as min((v + 2) >> 2, 255)")
        void roundsToEightBits() {
            var stream = H264HighBitDepthTest.x264("yuv444p10le", "bframes=2:qp=12");
            var deep = H264HighBitDepthTest.decodePictures(stream, wide(1));
            var narrow = H264HighBitDepthTest.decodePictures(stream, H264DecoderOptions.defaults());
            assertEquals(12, narrow.size());
            for (var i = 0; i < narrow.size(); i++) {
                var wide = (Yuv16Picture) deep.get(i);
                var shallow = (YuvPicture) narrow.get(i);
                assertEquals(ChromaFormat.YUV444, shallow.chromaFormat());
                assertEquals(shallow.width(), shallow.chromaWidth());
                assertEquals(shallow.height(), shallow.chromaHeight());
                var serialized = H264HighBitDepthTest.serialize(wide);
                var expected = new byte[serialized.length / 2];
                for (var k = 0; k < expected.length; k++) {
                    var sample = (serialized[2 * k] & 0xFF) | (serialized[2 * k + 1] & 0xFF) << 8;
                    expected[k] = (byte) Math.min((sample + 2) >> 2, 255);
                }
                assertArrayEquals(expected, H264TestStreams.serialize(shallow), "frame " + i);
            }
        }
    }

    @Nested
    @DisplayName("output format and stream properties")
    class Output {
        @Test
        @DisplayName("hands out 4:4:4 pictures with full-size chroma planes, for planes coded together or apart")
        void pictureFormat() {
            for (var name : List.of("c8-cabac-pcm", "c10-8-cabac", "m8-cavlc-sep", "m12-fields-wp-sep")) {
                var pictures = H264HighBitDepthTest.decodePictures(resource(name), wide(1));
                assertFalse(pictures.isEmpty(), name);
                for (var picture : pictures) {
                    assertEquals(ChromaFormat.YUV444, format(picture), name);
                    var chromaWidth = picture instanceof Yuv16Picture deep ? deep.chromaWidth()
                            : ((YuvPicture) picture).chromaWidth();
                    var chromaHeight = picture instanceof Yuv16Picture deep ? deep.chromaHeight()
                            : ((YuvPicture) picture).chromaHeight();
                    assertEquals(picture.width(), chromaWidth, name);
                    assertEquals(picture.height(), chromaHeight, name);
                }
            }
            // the 10/8-bit stream is handed out at 10 bits with its chroma shifted up, the 12-bit one at 12
            assertEquals(10, ((Yuv16Picture) H264HighBitDepthTest.decodePictures(resource("c10-8-cabac"), wide(1))
                    .getFirst()).bitDepth());
            assertEquals(12, ((Yuv16Picture) H264HighBitDepthTest.decodePictures(resource("m12-fields-wp-sep"),
                    wide(1)).getFirst()).bitDepth());
        }

        @Test
        @DisplayName("reports the chroma format, the color plane coding, the transform bypass and decoder support")
        void streamInfo() {
            var together = H264StreamInfo.probe(resource("c10-8-cabac"));
            assertEquals(ChromaFormat.YUV444, together.chromaFormat());
            assertEquals(244, together.profileIdc());
            assertEquals(10, together.bitDepthLuma());
            assertEquals(8, together.bitDepthChroma());
            assertFalse(together.separateColorPlanes());
            assertFalse(together.transformBypass());
            assertTrue(together.decoderSupported());
            var apart = H264StreamInfo.probe(resource("m12-fields-wp-sep"));
            assertEquals(ChromaFormat.YUV444, apart.chromaFormat());
            assertTrue(apart.separateColorPlanes());
            assertEquals(12, apart.bitDepthLuma());
            assertEquals(12, apart.bitDepthChroma());
            assertTrue(apart.decoderSupported());
            assertNotEquals(together, apart);
            var lossless = H264StreamInfo.probe(resource("l10-422-cabac"));
            assertEquals(ChromaFormat.YUV422, lossless.chromaFormat());
            assertTrue(lossless.transformBypass());
            assertTrue(lossless.decoderSupported());
            var intra = H264StreamInfo.probe(resource("c8-cavlc-intra"));
            assertEquals(java.util.Optional.of("High 4:4:4 Intra"), intra.profileName());
        }

        @Test
        @DisplayName("follows a stream that switches between 4:2:0, 4:4:4 and separately coded planes at IDR pictures")
        void formatSwitch() {
            var input = List.of("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=25");
            var yuv420 = Fixtures.read(Fixtures.ffmpeg("h264", input, List.of("-frames:v", "6", "-pix_fmt", "yuv420p",
                    "-c:v", "libx264", "-bf", "2", "-f", "h264")));
            var yuv444 = Fixtures.read(Fixtures.ffmpeg("h264", input, List.of("-frames:v", "6", "-pix_fmt", "yuv444p",
                    "-c:v", "libx264", "-bf", "2", "-f", "h264")));
            var separate = resource("m8-cavlc-sep");
            var lossless = resource("l8-420-cabac");
            var parts = List.of(yuv420, yuv444, separate, yuv444, lossless, separate);
            var joined = new ByteArrayOutputStream();
            parts.forEach(joined::writeBytes);
            for (var threads : new int[]{1, 3}) {
                var options = H264DecoderOptions.builder().threads(threads).build();
                var expected = new ArrayList<Picture>();
                for (var part : parts) {
                    expected.addAll(H264HighBitDepthTest.decodePictures(part, options));
                }
                var actual = H264HighBitDepthTest.decodePictures(joined.toByteArray(), options);
                assertEquals(expected.size(), actual.size());
                for (var i = 0; i < actual.size(); i++) {
                    assertEquals(format(expected.get(i)), format(actual.get(i)), "chroma format of frame " + i);
                    assertEquals(H264HighBitDepthTest.md5(expected.get(i)), H264HighBitDepthTest.md5(actual.get(i)),
                            "frame " + i + " with " + threads + " threads");
                }
            }
        }
    }

    @Nested
    @DisplayName("damage")
    class Damage {
        /**
         * Returns the {@code colour_plane_id} of a slice NAL unit of a separate-plane stream, read
         * after {@code first_mb_in_slice}, {@code slice_type} and {@code pic_parameter_set_id}.
         */
        private static int colorPlane(byte[] nal) {
            var reader = new BitReader(nal, 1);
            reader.ue();
            reader.ue();
            reader.ue();
            return reader.bits(2);
        }

        @Test
        @DisplayName("conceals the missing color plane of a picture from the previous picture and flags the damage")
        void missingPlane() {
            var nals = H264TestStreams.nalUnits(resource("m8-cavlc-sep"));
            // the second picture in decoding order, a P picture, loses its Cr slices
            var pictures = 0;
            var damaged = new ArrayList<byte[]>();
            for (var nal : nals) {
                var type = nal[0] & 0x1F;
                if (type == 1 || type == 5) {
                    if ((nal[1] & 0x80) != 0 && colorPlane(nal) == 0) {
                        pictures++;
                    }
                    if (pictures == 2 && colorPlane(nal) == 2) {
                        continue;
                    }
                }
                damaged.add(nal);
            }
            assertEquals(nals.size() - 1, damaged.size(), "one Cr slice dropped");
            for (var threads : new int[]{1, 4}) {
                var complete = H264HighBitDepthTest.decodeChecked(withStartCodes(nals), wide(threads));
                var concealed = H264HighBitDepthTest.decodeChecked(withStartCodes(damaged), wide(threads));
                assertEquals(complete.size(), concealed.size());
                // in output order the I picture comes first and the damaged P picture third (I B P B)
                assertEquals(Boolean.FALSE, concealed.get(0).getValue());
                assertEquals(Boolean.TRUE, concealed.get(2).getValue());
                assertEquals(Boolean.TRUE, concealed.get(1).getValue(), "the B picture predicts from it");
                var first = (YuvPicture) complete.get(0).getKey();
                var whole = (YuvPicture) complete.get(2).getKey();
                var lost = (YuvPicture) concealed.get(2).getKey();
                for (var y = 0; y < lost.height(); y++) {
                    for (var x = 0; x < lost.width(); x++) {
                        var at = y * lost.yStride() + x;
                        var chroma = y * lost.uvStride() + x;
                        assertEquals(whole.y()[at], lost.y()[at], "Y at " + x + "," + y);
                        assertEquals(whole.u()[chroma], lost.u()[chroma], "Cb at " + x + "," + y);
                        assertEquals(first.v()[y * first.uvStride() + x], lost.v()[chroma], "Cr at " + x + "," + y);
                    }
                }
                assertFalse(Arrays.equals(H264TestStreams.serialize(whole), H264TestStreams.serialize(lost)));
            }
        }

        @Test
        @DisplayName("rejects colour_plane_id 3 as malformed and conceals the slice's plane like a lost one")
        void colorPlaneOutOfRange() {
            var nals = H264TestStreams.nalUnits(resource("m8-cavlc-sep"));
            var pictures = 0;
            var dropped = new ArrayList<byte[]>();
            var corrupted = new ArrayList<byte[]>();
            for (var nal : nals) {
                var type = nal[0] & 0x1F;
                if (type == 1 || type == 5) {
                    if ((nal[1] & 0x80) != 0 && colorPlane(nal) == 0) {
                        pictures++;
                    }
                    if (pictures == 2 && colorPlane(nal) == 2) {
                        var reader = new BitReader(nal, 1);
                        reader.ue();
                        reader.ue();
                        reader.ue();
                        var copy = nal.clone();
                        copy[reader.position >> 3] |= (byte) (0x80 >>> (reader.position & 7));
                        copy[(reader.position + 1) >> 3] |= (byte) (0x80 >>> ((reader.position + 1) & 7));
                        assertEquals(3, colorPlane(copy));
                        corrupted.add(copy);
                        continue;
                    }
                }
                dropped.add(nal);
                corrupted.add(nal);
            }
            for (var threads : new int[]{1, 4}) {
                var expected = H264HighBitDepthTest.decodeChecked(withStartCodes(dropped), wide(threads));
                // the plain decode reports the corrupt slice's packet as malformed
                var failures = 0;
                try (var decoder = new H264Decoder(H264ConformanceTest.track(), wide(threads))) {
                    for (var nal : withStartCodes(corrupted)) {
                        try {
                            decoder.decode(new Packet(1, nal, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false),
                                    _ -> { });
                        } catch (MediaException.Malformed failure) {
                            assertTrue(failure.getMessage().contains("colour_plane_id"), failure.getMessage());
                            failures++;
                        }
                    }
                }
                assertEquals(1, failures, "malformed packets with " + threads + " threads");
                // the checked decode turns it into damage and conceals the plane
                var frames = new ArrayList<Map.Entry<String, Boolean>>();
                H264Decoder.FrameConsumer sink = (frame, damaged) -> frames.add(
                        Map.entry(H264HighBitDepthTest.md5(frame.picture()), damaged));
                try (var decoder = new H264Decoder(H264ConformanceTest.track(), wide(threads))) {
                    for (var nal : withStartCodes(corrupted)) {
                        decoder.decode(new Packet(1, nal, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false), sink);
                    }
                    decoder.flush(sink);
                }
                assertEquals(expected.size(), frames.size());
                for (var i = 0; i < frames.size(); i++) {
                    assertEquals(expected.get(i).getValue(), frames.get(i).getValue(), "damage of frame " + i);
                    assertEquals(H264HighBitDepthTest.md5(expected.get(i).getKey()), frames.get(i).getKey(),
                            "frame " + i);
                }
                assertEquals(Boolean.TRUE, frames.get(2).getValue(), "the picture missing its Cr plane");
            }
        }

        private static List<byte[]> withStartCodes(List<byte[]> nals) {
            var result = new ArrayList<byte[]>();
            for (var nal : nals) {
                var copy = new byte[nal.length + 4];
                copy[3] = 1;
                System.arraycopy(nal, 0, copy, 4, nal.length);
                result.add(copy);
            }
            return result;
        }

        @Test
        @DisplayName("a slice repeated within a color plane of a picture is dropped, with one and four threads")
        void repeatedPlaneSlice() {
            var nals = H264TestStreams.nalUnits(resource("m8-cabac-slices-sep"));
            var repeated = new ArrayList<byte[]>();
            var duplicated = false;
            for (var nal : nals) {
                repeated.add(nal);
                var type = nal[0] & 0x1F;
                if (!duplicated && (type == 1 || type == 5) && (nal[1] & 0x80) == 0 && colorPlane(nal) == 1) {
                    // the second slice of the Cb plane of the first picture, sent twice
                    repeated.add(nal);
                    duplicated = true;
                }
            }
            assertTrue(duplicated);
            for (var threads : new int[]{1, 4}) {
                var expected = H264HighBitDepthTest.decodeChecked(withStartCodes(nals), wide(threads));
                var actual = H264HighBitDepthTest.decodeChecked(withStartCodes(repeated), wide(threads));
                assertEquals(expected.size(), actual.size(), "frames with " + threads + " threads");
                for (var i = 0; i < actual.size(); i++) {
                    assertEquals(H264HighBitDepthTest.md5(expected.get(i).getKey()),
                            H264HighBitDepthTest.md5(actual.get(i).getKey()), "frame " + i);
                }
            }
        }
    }

    @Nested
    @DisplayName("slice order of separately coded color planes")
    class PlaneOrder {
        static Stream<Arguments> orders() {
            var arguments = new ArrayList<Arguments>();
            for (var name : List.of("m8-cabac-slices-sep", "m12-fields-wp-sep", "m10-cabac-mbaff-pcm-sep")) {
                arguments.add(Arguments.of(name, "Cr, Cb, Y"));
                arguments.add(Arguments.of(name, "interleaved"));
            }
            return arguments.stream();
        }

        @ParameterizedTest(name = "{0} {1}")
        @MethodSource("orders")
        @DisplayName("decodes the slices of the three planes in any order, interleaved or not, with one and four threads")
        void anyOrder(String name, String order) {
            var nals = H264TestStreams.nalUnits(resource(name));
            var reordered = new ArrayList<byte[]>();
            var picture = new ArrayList<byte[]>();
            var planes = 0;
            for (var nal : nals) {
                var type = nal[0] & 0x1F;
                var slice = type == 1 || type == 5;
                if (!slice || ((nal[1] & 0x80) != 0 && Damage.colorPlane(nal) == 0)) {
                    planes += reorder(picture, order, reordered);
                }
                (slice ? picture : reordered).add(nal);
            }
            planes += reorder(picture, order, reordered);
            assertTrue(planes > 0, "pictures with slices of several planes");
            assertEquals(nals.size(), reordered.size());
            var moved = 0;
            for (var i = 0; i < nals.size(); i++) {
                moved += nals.get(i) == reordered.get(i) ? 0 : 1;
            }
            assertTrue(moved > planes, "slices moved: " + moved);
            for (var threads : new int[]{1, 4}) {
                var expected = H264HighBitDepthTest.decodeChecked(Damage.withStartCodes(nals), wide(threads));
                var actual = H264HighBitDepthTest.decodeChecked(Damage.withStartCodes(reordered), wide(threads));
                assertEquals(expected.size(), actual.size(), "frames with " + threads + " threads");
                for (var i = 0; i < actual.size(); i++) {
                    assertEquals(Boolean.FALSE, actual.get(i).getValue(), "frame " + i + " damaged");
                    assertEquals(H264HighBitDepthTest.md5(expected.get(i).getKey()),
                            H264HighBitDepthTest.md5(actual.get(i).getKey()), "frame " + i + " with " + threads
                                    + " threads");
                }
            }
        }

        /**
         * Moves the slices of one picture, held in coded order, to the output in another order:
         * the Cr slices, then the Cb, then the Y slices, or one slice of each plane in turn, every
         * plane's slices keeping their order.
         *
         * @return 1 when the picture had slices, 0 otherwise
         */
        private static int reorder(List<byte[]> picture, String order, List<byte[]> output) {
            if (picture.isEmpty()) {
                return 0;
            }
            var byPlane = List.<List<byte[]>>of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
            for (var nal : picture) {
                byPlane.get(Damage.colorPlane(nal)).add(nal);
            }
            picture.clear();
            if (order.equals("interleaved")) {
                var count = Math.max(byPlane.get(0).size(), Math.max(byPlane.get(1).size(), byPlane.get(2).size()));
                for (var i = 0; i < count; i++) {
                    for (var plane : List.of(1, 0, 2)) {
                        if (i < byPlane.get(plane).size()) {
                            output.add(byPlane.get(plane).get(i));
                        }
                    }
                }
            } else {
                output.addAll(byPlane.get(2));
                output.addAll(byPlane.get(1));
                output.addAll(byPlane.get(0));
            }
            return 1;
        }
    }

    /**
     * Reads Exp-Golomb and fixed-length fields of an unescaped NAL unit payload.
     */
    private static final class BitReader {
        private final byte[] data;
        private int position;

        BitReader(byte[] data, int offset) {
            this.data = data;
            this.position = offset * 8;
        }

        int bits(int count) {
            var value = 0;
            for (var i = 0; i < count; i++) {
                value = (value << 1) | ((data[position >> 3] >> (7 - (position & 7))) & 1);
                position++;
            }
            return value;
        }

        int ue() {
            var zeros = 0;
            while (bits(1) == 0) {
                zeros++;
            }
            return (1 << zeros) - 1 + bits(zeros);
        }
    }
}
