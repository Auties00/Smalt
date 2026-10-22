package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Checks the decoding of H.264 streams with 4:2:2 chroma ({@code chroma_format_idc} 2). The ITU-T
 * conformance streams without a reference YUV and the JM-encoded streams of {@code chroma422/} are
 * compared frame by frame, as the MD5 of their planar samples (8-bit, or 16-bit little-endian for
 * deeper streams), with the output of two independent reference decoders (FFmpeg and the JM
 * reference software), which agree on every frame listed in {@code chroma422/frames.tsv}; x264
 * streams are compared with FFmpeg at test time. The conformance streams that come with a reference
 * YUV are checked by {@link H264ConformanceTest}.
 */
@DisplayName("H.264 4:2:2 decoding")
class H264Chroma422Test {
    private static final String BASE = "https://www.itu.int/wftp3/av-arch/jvt-site/draft_conformance/";

    private static final Map<String, List<String>> GOLDEN = loadGolden();

    private static Map<String, List<String>> loadGolden() {
        try (var input = H264Chroma422Test.class.getResourceAsStream("chroma422/frames.tsv")) {
            assertNotNull(input, "chroma422/frames.tsv");
            var result = new LinkedHashMap<String, List<String>>();
            for (var line : new String(input.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                var parts = line.strip().split("\t");
                if (parts.length != 3) {
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
     * An ITU-T H.264.1 4:2:2 conformance bitstream without a reference YUV in its archive.
     */
    record Vector(String name, String sha256, String entry) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Vector> conformance() {
        return Stream.of(
                new Vector("Hi422FR1_SONY_A", "00160408cfcb21e3636d47d1f17833cf1ebe7a4b8dc2c8fadef897f509fc1d81",
                        "Hi422FR1_SONY_A.jsv"),
                new Vector("Hi422FR2_SONY_A", "8425a371fd15fad5c7b49db82100cd174886b3d524285b478bd3b988293e85b9",
                        "Hi422FR2_SONY_A.jsv"),
                new Vector("Hi422FR3_SONY_A", "797c2e759d9a0dc5bc10c03f942d7b7b481170d03ff0feeaab21d1f1a5a26c3c",
                        "Hi422FR3_SONY_A/Hi422FR3_SONY_A.jsv"),
                new Vector("Hi422FR4_SONY_A", "9924d9f90332ce83602047526e3283ac22a7dcfa39c33d46ba9d40267967f91a",
                        "Hi422FR4_SONY_A.jsv"),
                new Vector("Hi422FR6_SONY_A", "a2497e863383544c702404dff7bb208ecab6619f5281d25a09986f6043a7c9e3",
                        "Hi422FR6_SONY_A.jsv"),
                new Vector("Hi422FR7_SONY_A", "2020b3843df72faebfe9d08e6fd058cb16d88ef4e8a092ca992094d92f806ac0",
                        "Hi422FR7_SONY_A.jsv"),
                new Vector("Hi422FR8_SONY_A", "679f4fb8d70274621b12c103818d88bbf5bcdd6d1d52b94e5990642bc34c9d0f",
                        "Hi422FR8_SONY_A/Hi422FR8_SONY_A.jsv"),
                new Vector("Hi422FR9_SONY_A", "25affc58032d9e011db6f5e8996ead1b73e83a4cef3ca01b710f0ee3eb974968",
                        "Hi422FR9_SONY_A.jsv"),
                new Vector("Hi422FREXT16_SONY_A", "f736272b9e004aae95e55204979b1128959dd8e604d6d1817bff7950f942efcf",
                        "Hi422FREXT16_SONY_A.264"),
                new Vector("Hi422FREXT17_SONY_A", "d35bc82a321dbc66d1d80c540f6ad12b5b1f7d5334f92e9c240d050fa555fd2b",
                        "Hi422FREXT17_SONY_A.264"),
                new Vector("Hi422FREXT18_SONY_A", "9c8c604384afea8ffc3a1b73c852a6f9558c3854ddb72bf8cbdf469aeb5a9368",
                        "Hi422FREXT18_SONY_A.264"),
                new Vector("Hi422FREXT19_SONY_A", "92b16c31c1180a83ee9767991823ae156db0799729c25b58460729b8038ad3e2",
                        "Hi422FREXT19_SONY_A.264"));
    }

    static byte[] bitstream(Vector vector) {
        var archive = Conformance.fetch("h264-FRExt-" + vector.name() + ".zip",
                BASE + "FRExt/" + vector.name() + ".zip", vector.sha256());
        try (var zip = new ZipFile(archive.toFile())) {
            return zip.getInputStream(zip.getEntry(vector.entry())).readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * The streams encoded with the JM reference encoder and stored with the tests (see
     * {@code chroma422/streams.tsv} for how each was made).
     */
    static Stream<String> encoded() {
        return Stream.of("c8-cavlc", "c8-cabac-mbaff-pcm", "c8-cavlc-mbaff-cip", "c8-cavlc-scaling-implicit",
                "c10-paff-wp", "c10-fields-temporal", "c10-fields", "c10-8-cabac", "c10-cabac-pcm", "c12-cabac-wp",
                "c14-cavlc-mbaff-slices");
    }

    static byte[] resource(String name) {
        try (var input = H264Chroma422Test.class.getResourceAsStream("chroma422/" + name + ".264")) {
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

    @Nested
    @DisplayName("ITU-T conformance bitstreams")
    class ConformanceStreams {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264Chroma422Test#conformance")
        @DisplayName("decodes every frame bit-exactly")
        void bitExact(Vector vector) {
            assertGolden(vector.name(), H264HighBitDepthTest.decodeHashes(bitstream(vector), wide(1)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264Chroma422Test#conformance")
        @DisplayName("decodes bit-exactly with four frame threads")
        void threaded(Vector vector) {
            assertGolden(vector.name(), H264HighBitDepthTest.decodeHashes(bitstream(vector), wide(4)));
        }
    }

    @Nested
    @DisplayName("JM-encoded streams")
    class EncodedStreams {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264Chroma422Test#encoded")
        @DisplayName("decodes every frame bit-exactly")
        void bitExact(String name) {
            assertGolden(name, H264HighBitDepthTest.decodeHashes(resource(name), wide(1)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264Chroma422Test#encoded")
        @DisplayName("decodes bit-exactly with four frame threads")
        void threaded(String name) {
            assertGolden(name, H264HighBitDepthTest.decodeHashes(resource(name), wide(4)));
        }
    }

    /**
     * The x264 settings of the differential tests, each at 8 and at 10 bits: entropy coders,
     * frame types, weighted prediction, interlacing, scaling matrices, chroma offsets, deblocking
     * offsets and slices.
     */
    static Stream<Arguments> x264Settings() {
        var settings = List.of(
                List.of("cavlc-p", "cabac=0:bframes=0:qp=24:8x8dct=0"),
                List.of("cabac-b-pyramid", "bframes=3:b-pyramid=normal:ref=4:qp=20"),
                List.of("weightp", "bframes=0:weightp=2:qp=22:ref=3"),
                List.of("weightb-temporal", "bframes=2:weightb=1:direct=temporal:qp=26"),
                List.of("low-qp", "bframes=2:qp=3:chroma-qp-offset=-6"),
                List.of("high-qp", "bframes=2:qp=46:chroma-qp-offset=6"),
                List.of("cqm-jvt", "bframes=2:cqm=jvt:qp=18:8x8dct=1"),
                List.of("deblock-slices", "bframes=1:slices=4:deblock=-3,2:qp=30"),
                List.of("mbaff-cabac", "interlaced=1:bframes=2:qp=22"),
                List.of("mbaff-cavlc", "interlaced=1:cabac=0:bframes=1:qp=26:weightp=1"),
                List.of("fake-interlaced", "fake-interlaced=1:bframes=1:qp=24"),
                List.of("intra-refresh", "bframes=0:intra-refresh=1:keyint=6:qp=24"),
                List.of("partitions-all", "bframes=2:partitions=all:subme=9:direct=auto:weightb=1:ref=3:qp=18"),
                List.of("constrained-intra", "bframes=0:constrained-intra=1:keyint=4:qp=20"));
        var arguments = new ArrayList<Arguments>();
        for (var format : List.of("yuv422p", "yuv422p10le")) {
            for (var setting : settings) {
                arguments.add(Arguments.of(setting.get(0) + "-" + format, format, setting.get(1)));
            }
        }
        return arguments.stream();
    }

    /**
     * The x264 settings of the differential test at a picture size that is not a multiple of 16,
     * so the stream signals frame cropping.
     */
    static Stream<Arguments> croppedSettings() {
        return Stream.of(
                Arguments.of("cropped-cabac", "yuv422p", "bframes=2:qp=20"),
                Arguments.of("cropped-mbaff", "yuv422p", "interlaced=1:cabac=0:bframes=1:qp=22"),
                Arguments.of("cropped-cabac-10", "yuv422p10le", "bframes=2:qp=20"),
                Arguments.of("cropped-mbaff-10", "yuv422p10le", "interlaced=1:bframes=1:qp=22"));
    }

    static Stream<String> chroma422Formats() {
        return Stream.of("yuv422p", "yuv422p10le");
    }

    /**
     * Returns the frames of a reference decode by FFmpeg as planar data: 8-bit samples, or 16-bit
     * little-endian samples for a 10-bit format.
     */
    static List<byte[]> oracleFrames(Path stream, String pixelFormat, int width, int height) {
        var raw = Oracle.decodeVideo(stream, List.of("-f", "h264"), pixelFormat, 0);
        var bytes = pixelFormat.endsWith("le") ? 2 : 1;
        var size = bytes * (width * height + 2 * ((width + 1) / 2) * height);
        var frames = new ArrayList<byte[]>();
        for (var at = 0; at + size <= raw.length; at += size) {
            frames.add(Arrays.copyOfRange(raw, at, at + size));
        }
        return frames;
    }

    static byte[] serialize(Picture picture) {
        if (picture instanceof Yuv16Picture wide) {
            return H264HighBitDepthTest.serialize(wide);
        }
        return H264TestStreams.serialize((YuvPicture) picture);
    }

    @Nested
    @DisplayName("x264 streams against FFmpeg")
    class Differential {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264Chroma422Test#x264Settings")
        @DisplayName("decodes every frame as FFmpeg does, with one and four threads")
        void matchesFfmpeg(String name, String pixelFormat, String parameters) {
            assertMatchesFfmpeg(name, pixelFormat, parameters, 192, 128);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264Chroma422Test#croppedSettings")
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
                    assertEquals(width, picture.width());
                    assertEquals(height, picture.height());
                    if (!Arrays.equals(expected.get(i), serialize(picture))) {
                        fail(name + ": frame " + i + " differs from FFmpeg with " + threads + " threads");
                    }
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264Chroma422Test#chroma422Formats")
        @DisplayName("crops on every side, at an odd top row, the frames FFmpeg decodes uncropped")
        void croppedEverySide(String pixelFormat) {
            var uncropped = Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=25"),
                    List.of("-frames:v", "6", "-pix_fmt", pixelFormat, "-c:v", "libx264", "-bf", "2", "-f", "h264"));
            // the same slices under a sequence parameter set that crops; the 4:2:2 crop unit is 2 luma columns
            // and 1 luma row of a progressive frame
            var stream = Fixtures.ffmpeg("h264", List.of("-i", uncropped.toAbsolutePath().toString()),
                    List.of("-c", "copy", "-bsf:v", "h264_metadata=crop_left=2:crop_right=4:crop_top=3:crop_bottom=5",
                            "-f", "h264"));
            var bytes = pixelFormat.endsWith("le") ? 2 : 1;
            var expected = new ArrayList<byte[]>();
            for (var frame : oracleFrames(uncropped, pixelFormat, 96, 64)) {
                var cropped = new ByteArrayOutputStream();
                for (var y = 3; y < 59; y++) {
                    cropped.write(frame, bytes * (y * 96 + 2), bytes * 90);
                }
                for (var plane = 0; plane < 2; plane++) {
                    var base = bytes * (96 * 64 + plane * 48 * 64);
                    for (var y = 3; y < 59; y++) {
                        cropped.write(frame, base + bytes * (y * 48 + 1), bytes * 45);
                    }
                }
                expected.add(cropped.toByteArray());
            }
            assertEquals(6, expected.size(), "frames FFmpeg decoded");
            for (var threads : new int[]{1, 4}) {
                var actual = H264HighBitDepthTest.decodePictures(stream, wide(threads));
                assertEquals(expected.size(), actual.size(), "frame count with " + threads + " threads");
                for (var i = 0; i < actual.size(); i++) {
                    assertEquals(90, actual.get(i).width());
                    assertEquals(56, actual.get(i).height());
                    if (!Arrays.equals(expected.get(i), serialize(actual.get(i)))) {
                        fail(pixelFormat + ": frame " + i + " differs from FFmpeg with " + threads + " threads");
                    }
                }
            }
        }

        @Test
        @DisplayName("rounds 10-bit 4:2:2 samples to 8 bits by default as min((v + 2) >> 2, 255)")
        void roundsToEightBits() {
            var stream = H264HighBitDepthTest.x264("yuv422p10le", "bframes=2:qp=12");
            var deep = H264HighBitDepthTest.decodePictures(stream, wide(1));
            var narrow = H264HighBitDepthTest.decodePictures(stream, H264DecoderOptions.defaults());
            assertEquals(12, narrow.size());
            for (var i = 0; i < narrow.size(); i++) {
                var wide = (Yuv16Picture) deep.get(i);
                var shallow = (YuvPicture) narrow.get(i);
                assertEquals(ChromaFormat.YUV422, shallow.chromaFormat());
                assertEquals(shallow.height(), shallow.chromaHeight());
                var expected = new ByteArrayOutputStream();
                for (var sample : planes(wide)) {
                    expected.write(Math.min((sample + 2) >> 2, 255));
                }
                assertTrue(Arrays.equals(expected.toByteArray(), H264TestStreams.serialize(shallow)), "frame " + i);
            }
        }

        private static int[] planes(Yuv16Picture picture) {
            var serialized = H264HighBitDepthTest.serialize(picture);
            var samples = new int[serialized.length / 2];
            for (var i = 0; i < samples.length; i++) {
                samples[i] = (serialized[2 * i] & 0xFF) | (serialized[2 * i + 1] & 0xFF) << 8;
            }
            return samples;
        }
    }

    @Nested
    @DisplayName("output format and stream properties")
    class Output {
        @Test
        @DisplayName("hands out 4:2:2 pictures with full-height chroma planes")
        void pictureFormat() {
            for (var name : List.of("c8-cavlc", "c10-paff-wp")) {
                for (var picture : H264HighBitDepthTest.decodePictures(resource(name), wide(1))) {
                    var format = picture instanceof Yuv16Picture deep ? deep.chromaFormat()
                            : ((YuvPicture) picture).chromaFormat();
                    var chromaWidth = picture instanceof Yuv16Picture deep ? deep.chromaWidth()
                            : ((YuvPicture) picture).chromaWidth();
                    var chromaHeight = picture instanceof Yuv16Picture deep ? deep.chromaHeight()
                            : ((YuvPicture) picture).chromaHeight();
                    assertEquals(ChromaFormat.YUV422, format, name);
                    assertEquals(picture.width() / 2, chromaWidth, name);
                    assertEquals(picture.height(), chromaHeight, name);
                }
            }
        }

        @Test
        @DisplayName("reports the High 4:2:2 profile, the chroma format and decoder support")
        void streamInfo() {
            var info = H264StreamInfo.probe(resource("c10-paff-wp"));
            assertEquals(ChromaFormat.YUV422, info.chromaFormat());
            assertEquals(122, info.profileIdc());
            assertEquals(10, info.bitDepthLuma());
            assertTrue(info.decoderSupported());
            assertEquals(ChromaFormat.YUV422, H264StreamInfo.probe(resource("c12-cabac-wp")).chromaFormat());
            assertEquals(244, H264StreamInfo.probe(resource("c12-cabac-wp")).profileIdc());
        }

        @Test
        @DisplayName("follows a stream that switches between 4:2:0 and 4:2:2 at IDR pictures")
        void formatSwitch() {
            var input = List.of("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=25");
            var yuv420 = Fixtures.read(Fixtures.ffmpeg("h264", input, List.of("-frames:v", "6", "-pix_fmt", "yuv420p",
                    "-c:v", "libx264", "-bf", "2", "-f", "h264")));
            var yuv422 = Fixtures.read(Fixtures.ffmpeg("h264", input, List.of("-frames:v", "6", "-pix_fmt", "yuv422p",
                    "-c:v", "libx264", "-bf", "2", "-f", "h264")));
            var joined = new ByteArrayOutputStream();
            joined.writeBytes(yuv420);
            joined.writeBytes(yuv422);
            joined.writeBytes(yuv420);
            for (var threads : new int[]{1, 3}) {
                var options = wide(threads);
                var expected = new ArrayList<Picture>();
                expected.addAll(H264HighBitDepthTest.decodePictures(yuv420, options));
                expected.addAll(H264HighBitDepthTest.decodePictures(yuv422, options));
                expected.addAll(H264HighBitDepthTest.decodePictures(yuv420, options));
                var actual = H264HighBitDepthTest.decodePictures(joined.toByteArray(), options);
                assertEquals(18, actual.size());
                for (var i = 0; i < actual.size(); i++) {
                    assertEquals(((YuvPicture) expected.get(i)).chromaFormat(),
                            ((YuvPicture) actual.get(i)).chromaFormat(), "chroma format of frame " + i);
                    assertEquals(H264HighBitDepthTest.md5(expected.get(i)), H264HighBitDepthTest.md5(actual.get(i)),
                            "frame " + i + " with " + threads + " threads");
                }
            }
        }
    }

    @Nested
    @DisplayName("damage")
    class Damage {
        @Test
        @DisplayName("conceals a lost slice of the first picture with mid-gray in the full-height chroma rows")
        void concealedSlice() {
            var nals = H264TestStreams.splitAnnexB(resource("c14-cavlc-mbaff-slices"));
            var lastSliceOfFirstPicture = -1;
            for (var i = 0; i < nals.size(); i++) {
                var type = nals.get(i)[nals.get(i)[2] == 1 ? 3 : 4] & 0x1F;
                if (type == 5) {
                    lastSliceOfFirstPicture = i;
                }
            }
            var damagedNals = new ArrayList<>(nals.subList(0, lastSliceOfFirstPicture));
            var frames = H264HighBitDepthTest.decodeChecked(damagedNals, wide(1));
            assertEquals(1, frames.size());
            assertEquals(Boolean.TRUE, frames.getFirst().getValue());
            var picture = (Yuv16Picture) frames.getFirst().getKey();
            assertEquals(14, picture.bitDepth());
            // the last slice of the 64x64 picture covers its two bottom-right macroblock pairs
            for (var y = 32; y < 64; y++) {
                for (var x = 32; x < 64; x++) {
                    assertEquals(8192, picture.y()[y * picture.yStride() + x], "luma " + x + "," + y);
                }
                for (var x = 16; x < 32; x++) {
                    assertEquals(8192, picture.u()[y * picture.uvStride() + x], "cb " + x + "," + y);
                    assertEquals(8192, picture.v()[y * picture.uvStride() + x], "cr " + x + "," + y);
                }
            }
            assertNotEquals(8192, picture.u()[31 * picture.uvStride() + 16], "decoded chroma above the slice");
            assertNotEquals(8192, picture.y()[0], "decoded area");
        }

        static Stream<Arguments> lostSlices() {
            return Stream.of(
                    // x264 codes each of the 4 macroblock rows as a slice; the last one is the bottom row
                    Arguments.of("8-bit progressive", "x264", 0, 48),
                    // the last slice of the 64x64 MBAFF picture covers its two bottom-right macroblock pairs
                    Arguments.of("14-bit MBAFF", "c14-cavlc-mbaff-slices", 32, 32));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("lostSlices")
        @DisplayName("conceals a lost slice of a P picture from the previous frame in the full-height chroma rows")
        void concealedFromPrevious(String name, String source, int lostX, int lostY) {
            var stream = source.equals("x264")
                    ? Fixtures.read(H264HighBitDepthTest.x264("yuv422p", "bframes=0:slices=4:qp=24", 96, 64))
                    : resource(source);
            var nals = H264TestStreams.splitAnnexB(stream);
            // the stream up to the second picture in decoding order, without the last slice of that picture
            var pictures = 0;
            var lastSlice = -1;
            for (var i = 0; i < nals.size(); i++) {
                var nal = nals.get(i);
                var header = nal[2] == 1 ? 3 : 4;
                var type = nal[header] & 0x1F;
                if (type != 1 && type != 5) {
                    continue;
                }
                if (firstMacroblock(nal, header + 1) == 0 && ++pictures == 3) {
                    break;
                }
                lastSlice = i;
            }
            var damagedNals = new ArrayList<>(nals.subList(0, lastSlice));
            for (var threads : new int[]{1, 4}) {
                var complete = H264HighBitDepthTest.decodeChecked(nals.subList(0, lastSlice + 1), wide(threads))
                        .stream().map(Map.Entry::getKey).toList();
                assertEquals(2, complete.size());
                var frames = H264HighBitDepthTest.decodeChecked(damagedNals, wide(threads));
                assertEquals(2, frames.size(), name + " frames with " + threads + " threads");
                assertEquals(Boolean.FALSE, frames.get(0).getValue());
                assertEquals(Boolean.TRUE, frames.get(1).getValue());
                var previous = frames.get(0).getKey();
                var concealed = frames.get(1).getKey();
                assertEquals(H264HighBitDepthTest.md5(complete.getFirst()), H264HighBitDepthTest.md5(previous));
                var width = concealed.width();
                var height = concealed.height();
                // the deblocking filter can change up to 6 luma and 2 chroma rows below the lost area's top edge
                // (field filtering of MBAFF frames), 3 luma and 1 chroma columns right of its left edge, and as
                // many rows above it
                var copied = false;
                for (var plane = 0; plane < 3; plane++) {
                    var shift = plane == 0 ? 0 : 1;
                    var top = lostY + (plane == 0 ? 6 : 2);
                    var left = lostX == 0 ? 0 : (lostX >> shift) + (plane == 0 ? 3 : 1);
                    for (var y = 0; y < height; y++) {
                        for (var x = 0; x < width >> shift; x++) {
                            var actual = sample(concealed, plane, x, y);
                            if (y >= top && x >= left) {
                                assertEquals(sample(previous, plane, x, y), actual,
                                        name + " plane " + plane + " at " + x + "," + y + ", " + threads + " threads");
                                copied |= sample(complete.get(1), plane, x, y) != actual;
                            } else if (y < lostY - (plane == 0 ? 6 : 2)) {
                                assertEquals(sample(complete.get(1), plane, x, y), actual,
                                        name + " decoded plane " + plane + " at " + x + "," + y);
                            }
                        }
                    }
                }
                assertTrue(copied, "the lost area differs from the previous frame in the complete decode");
            }
        }

        private static int firstMacroblock(byte[] nal, int offset) {
            var bit = offset * 8;
            var zeros = 0;
            while (((nal[bit >> 3] >> (7 - (bit & 7))) & 1) == 0) {
                zeros++;
                bit++;
            }
            bit++;
            var value = 0;
            for (var i = 0; i < zeros; i++, bit++) {
                value = (value << 1) | ((nal[bit >> 3] >> (7 - (bit & 7))) & 1);
            }
            return (1 << zeros) - 1 + value;
        }

        private static int sample(Picture picture, int plane, int x, int y) {
            if (picture instanceof Yuv16Picture wide) {
                return switch (plane) {
                    case 0 -> wide.y()[y * wide.yStride() + x];
                    case 1 -> wide.u()[y * wide.uvStride() + x];
                    default -> wide.v()[y * wide.uvStride() + x];
                };
            }
            var narrow = (YuvPicture) picture;
            return switch (plane) {
                case 0 -> narrow.y()[y * narrow.yStride() + x] & 0xFF;
                case 1 -> narrow.u()[y * narrow.uvStride() + x] & 0xFF;
                default -> narrow.v()[y * narrow.uvStride() + x] & 0xFF;
            };
        }

        @Test
        @DisplayName("fills the field a lone field picture lacks with mid-gray in every chroma row of that field")
        void missingField() {
            var nals = H264TestStreams.splitAnnexB(resource("c10-fields"));
            var complete = H264HighBitDepthTest.decodeChecked(nals, wide(1));
            var lone = H264HighBitDepthTest.decodeChecked(nals.subList(0, nals.size() - 1), wide(1));
            assertEquals(3, complete.size());
            assertEquals(3, lone.size());
            var full = (Yuv16Picture) complete.get(2).getKey();
            var half = (Yuv16Picture) lone.get(2).getKey();
            assertEquals(full.height(), full.chromaHeight());
            for (var y = 0; y < full.chromaHeight(); y++) {
                for (var x = 0; x < full.chromaWidth(); x++) {
                    var expected = (y & 1) == 0 ? full.u()[y * full.uvStride() + x] : 512;
                    assertEquals(expected, half.u()[y * half.uvStride() + x], "cb " + x + "," + y);
                    expected = (y & 1) == 0 ? full.v()[y * full.uvStride() + x] : 512;
                    assertEquals(expected, half.v()[y * half.uvStride() + x], "cr " + x + "," + y);
                }
            }
        }
    }
}
