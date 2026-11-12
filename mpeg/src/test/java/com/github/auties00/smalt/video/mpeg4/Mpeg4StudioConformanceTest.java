package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes MPEG-4 Studio profile streams: the Simple and Core Studio conformance bitstreams of ISO/IEC 14496-4
 * ({@code vcon-stp*}) and the vectors of {@code tools/testdata/mpeg4-studio/make-vectors.py}.
 *
 * <p>The DPCM vectors code their source losslessly, so their decode must equal it exactly; they cover every chroma
 * format, precision, frame and field VOPs and the header options. The DCT vectors and the conformance I-VOPs are
 * pinned by MD5 and compared with FFmpeg (a black-box oracle) where FFmpeg decodes them: 10-bit 4:2:2 and 4:4:4
 * frame VOPs without field DCT or quant matrix extensions, which FFmpeg mishandles. The inverse DCT is not
 * normative, so those comparisons allow one unit ({@link Tolerance#offByOne()}), after replacing the samples FFmpeg
 * wraps from the largest value to 0 (it does not saturate the inverse DCT output of 10-bit Studio blocks).
 */
@DisplayName("MPEG-4 Studio profile conformance")
class Mpeg4StudioConformanceTest {
    private static final String RESOURCES = "/com/github/auties00/smalt/video/mpeg4/studio/";

    /**
     * The MD5 of every frame of the I-VOPs of the conformance streams, decoded to their precision (16-bit samples
     * little endian, Y then Cb then Cr; 8-bit samples for the 8-bit stream).
     */
    private static final Map<String, List<String>> CONFORMANCE = Map.ofEntries(
            Map.entry("vcon-stp1L1", List.of("3ad118e7622ee44752521e9f08757fd4", "73ae0c12a3de3494d7f3fd54b4341d49",
                    "c9680c25866d3e6373ce921ac80eb1b2")),
            Map.entry("vcon-stp2L1", List.of("fd6c5f029085f0e8c1f224a29888e128")),
            Map.entry("vcon-stp3L1", List.of("4c6c6464002c35dd77fc970ef879618f", "31218e282c8351cca9899c8454f0f9d2",
                    "91c99519ae45d6775c32bfb561971ca5")),
            Map.entry("vcon-stp4L1", List.of("c63c61465fcf0bdffe116832d46800e2")),
            Map.entry("vcon-stp5L1", List.of("6e79a3a376b99c2b48f7c14af454ed27", "52a188bc74cf63f6166b205e226de0d4",
                    "6d52d5ba4b2a6305348ceb2d6f8b61ca", "a31c72ff01bb9d8921308b9013989e53",
                    "1a9fb0c0dee5bac02d9b8a5acc89e2f4", "52ba9ba85ad3091d9b45ee3b6d400cd3",
                    "4b50bacbfb5d3004429df1915a239d37", "2c80ea501f04f50bfad53438f492b005",
                    "0c74cbef8b4b187537c58347c75b18ef", "873c8d36ff55c598a6d70c75fa29dfc8")),
            Map.entry("vcon-stp6L1", List.of("f72028d9f8831d6fb5305b3e7e6f335b", "c61212449fed14da092fd91f584a7489",
                    "3a88c49408af2d5674eff8c66bbaaff7")),
            Map.entry("vcon-stp7L1", List.of("4f8305dbe5276591e9e53b069107beab")),
            Map.entry("vcon-stp8L1", List.of("4f8305dbe5276591e9e53b069107beab")),
            Map.entry("vcon-stp9L1", List.of("dc455525cf9f3941c38b0b1e73e21595")),
            Map.entry("vcon-stp10L1", List.of("8deb880bafa8364b9418b9388ed64e3e", "77be3d86eeae428f7c420d3f97e8d2ff",
                    "0d371125796cdd722cab77ce83958971")),
            Map.entry("vcon-stp11L1", List.of("63821a240b7832aab488af57b959516d", "9b950b458fcb7ff3de28a11e012ea652",
                    "c52b089712b65fd7a61a55867886fd3a", "9cd6dd4afa3a31abbd497185b98deee6",
                    "8c195ef2468d5752711be33aa45cebe4", "ddddf45a1fc09c5085c85dd8e6408e98")));

    /**
     * The number of VOPs of each conformance stream, all coded.
     */
    private static final Map<String, Integer> VOPS = Map.ofEntries(Map.entry("vcon-stp1L1", 6),
            Map.entry("vcon-stp2L1", 6), Map.entry("vcon-stp3L1", 6), Map.entry("vcon-stp4L1", 6),
            Map.entry("vcon-stp5L1", 10), Map.entry("vcon-stp6L1", 6), Map.entry("vcon-stp7L1", 6),
            Map.entry("vcon-stp8L1", 6), Map.entry("vcon-stp9L1", 2), Map.entry("vcon-stp10L1", 6),
            Map.entry("vcon-stp11L1", 6));

    /**
     * The decode of a stream: the frames, the failures of the packets that raised one, and the timestamps.
     */
    record Decoded(List<Picture> frames, List<byte[]> samples, List<MediaException> failures, List<Long> timestamps) {
    }

    static Decoded decode(byte[] data, boolean highBitDepth) {
        var frames = new ArrayList<Picture>();
        var samples = new ArrayList<byte[]>();
        var failures = new ArrayList<MediaException>();
        var timestamps = new ArrayList<Long>();
        var options = VideoDecoderOptions.builder().highBitDepth(highBitDepth).build();
        try (var source = MediaSource.of(data); var stream = Mpeg4ElementaryStream.open(source);
             var decoder = new Mpeg4Decoder(stream.track(), options)) {
            Packet packet;
            while ((packet = stream.read()) != null) {
                try {
                    decoder.decode(packet, frame -> {
                        frames.add(frame.picture());
                        samples.add(planes(frame.picture()));
                        timestamps.add(frame.pts());
                    });
                } catch (MediaException exception) {
                    failures.add(exception);
                }
            }
            decoder.flush(frame -> {
                frames.add(frame.picture());
                samples.add(planes(frame.picture()));
                timestamps.add(frame.pts());
            });
        }
        return new Decoded(frames, samples, failures, timestamps);
    }

    /**
     * Returns the planes of a picture as Y, Cb and Cr samples, 16-bit little endian above 8 bits.
     */
    static byte[] planes(Picture picture) {
        var out = new ByteArrayOutputStream();
        switch (picture) {
            case Yuv16Picture deep -> {
                for (var plane = 0; plane < 3; plane++) {
                    var samples = plane == 0 ? deep.y() : plane == 1 ? deep.u() : deep.v();
                    var stride = plane == 0 ? deep.yStride() : deep.uvStride();
                    var width = plane == 0 ? deep.width() : deep.chromaWidth();
                    var height = plane == 0 ? deep.height() : deep.chromaHeight();
                    for (var row = 0; row < height; row++) {
                        for (var column = 0; column < width; column++) {
                            var value = samples[row * stride + column];
                            out.write(value & 0xFF);
                            out.write((value >>> 8) & 0xFF);
                        }
                    }
                }
            }
            case YuvPicture yuv -> {
                for (var plane = 0; plane < 3; plane++) {
                    var samples = plane == 0 ? yuv.y() : plane == 1 ? yuv.u() : yuv.v();
                    var stride = plane == 0 ? yuv.yStride() : yuv.uvStride();
                    var width = plane == 0 ? yuv.width() : yuv.chromaWidth();
                    var height = plane == 0 ? yuv.height() : yuv.chromaHeight();
                    for (var row = 0; row < height; row++) {
                        out.write(samples, row * stride, width);
                    }
                }
            }
            default -> throw new AssertionError("unexpected picture " + picture);
        }
        return out.toByteArray();
    }

    static String md5(byte[]... parts) {
        try {
            var digest = MessageDigest.getInstance("MD5");
            for (var part : parts) {
                digest.update(part);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    static byte[] resource(String name) {
        try (var in = Mpeg4StudioConformanceTest.class.getResourceAsStream(RESOURCES + name)) {
            if (in == null) {
                throw new AssertionError("missing resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * One row of the vector list written by the generator.
     */
    record Vector(String name, String mode, int width, int height, int chroma, int bits, int frames, String md5) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<Vector> vectors() {
        var rows = new ArrayList<Vector>();
        for (var line : new String(resource("vectors.tsv"), StandardCharsets.UTF_8).split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            var f = line.split("\t");
            rows.add(new Vector(f[0], f[1], Integer.parseInt(f[2]), Integer.parseInt(f[3]), Integer.parseInt(f[4]),
                    Integer.parseInt(f[5]), Integer.parseInt(f[6]), f[7]));
        }
        return rows;
    }

    static List<Vector> dpcmVectors() {
        return vectors().stream().filter(vector -> vector.mode().equals("dpcm")).toList();
    }

    static List<Vector> dctVectors() {
        return vectors().stream().filter(vector -> vector.mode().equals("dct")).toList();
    }

    /**
     * The MD5 of the decode of the DCT vectors (all frames, as {@link #planes(Picture)} lays them out).
     */
    static Map<String, String> golden() {
        var map = new TreeMap<String, String>();
        for (var line : new String(resource("golden.tsv"), StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank() && !line.startsWith("#")) {
                var f = line.split("\t");
                map.put(f[0], f[1].strip());
            }
        }
        return map;
    }

    static ChromaFormat chroma(int code) {
        return switch (code) {
            case 420 -> ChromaFormat.YUV420;
            case 422 -> ChromaFormat.YUV422;
            default -> ChromaFormat.YUV444;
        };
    }

    /**
     * Decodes a stream with FFmpeg to planar samples of a pixel format.
     */
    static byte[] ffmpeg(Path file, String pixelFormat) {
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(5), List.of("-v", "error", "-f", "m4v", "-i",
                file.toString(), "-f", "rawvideo", "-pix_fmt", pixelFormat, "-"));
    }

    /**
     * Compares 10-bit planar samples with FFmpeg's, one unit apart at most, after replacing the samples FFmpeg wraps
     * from 1023 to 0; the number of such samples must stay small.
     */
    static void assertMatchesFfmpeg(String what, byte[] ours, byte[] theirs) {
        assertEquals(theirs.length, ours.length, what + ": decoded size");
        var expected = new short[ours.length / 2];
        var actual = new short[ours.length / 2];
        var wrapped = 0;
        for (var i = 0; i < expected.length; i++) {
            var mine = (ours[2 * i] & 0xFF) | (ours[2 * i + 1] & 0xFF) << 8;
            var other = (theirs[2 * i] & 0xFF) | (theirs[2 * i + 1] & 0xFF) << 8;
            if (mine == 1023 && other == 0) {
                other = 1023;
                wrapped++;
            }
            expected[i] = (short) other;
            actual[i] = (short) mine;
        }
        assertTrue(wrapped <= expected.length / 10000, what + ": " + wrapped + " samples FFmpeg wraps to 0");
        Tolerance.offByOne().assertWithin(what + " against FFmpeg", expected, actual, 10);
    }

    @Nested
    @DisplayName("generated vectors")
    class Generated {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.mpeg4.Mpeg4StudioConformanceTest#dpcmVectors")
        @DisplayName("DPCM vectors decode to their source exactly")
        void lossless(Vector vector) {
            var decoded = decode(resource(vector.name() + ".m4v"), true);
            assertTrue(decoded.failures().isEmpty(), "failures: " + decoded.failures());
            assertEquals(vector.frames(), decoded.frames().size(), "frames");
            for (var picture : decoded.frames()) {
                assertEquals(vector.width(), picture.width(), "width");
                assertEquals(vector.height(), picture.height(), "height");
                if (vector.bits() == 8) {
                    var yuv = assertInstanceOf(YuvPicture.class, picture);
                    assertEquals(chroma(vector.chroma()), yuv.chromaFormat(), "chroma format");
                } else {
                    var deep = assertInstanceOf(Yuv16Picture.class, picture);
                    assertEquals(chroma(vector.chroma()), deep.chromaFormat(), "chroma format");
                    assertEquals(vector.bits(), deep.bitDepth(), "bit depth");
                }
            }
            assertEquals(vector.md5(), md5(decoded.samples().toArray(byte[][]::new)), "decoded samples");
            for (var i = 1; i < decoded.timestamps().size(); i++) {
                assertTrue(decoded.timestamps().get(i) > decoded.timestamps().get(i - 1), "timestamps increase: "
                        + decoded.timestamps());
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.mpeg4.Mpeg4StudioConformanceTest#dctVectors")
        @DisplayName("DCT vectors decode to their pinned samples")
        void dct(Vector vector) {
            var decoded = decode(resource(vector.name() + ".m4v"), true);
            assertTrue(decoded.failures().isEmpty(), "failures: " + decoded.failures());
            assertEquals(vector.frames(), decoded.frames().size(), "frames");
            assertEquals(golden().get(vector.name()), md5(decoded.samples().toArray(byte[][]::new)),
                    "decoded samples");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"studio-dct-422-10-interlaced, yuv422p10le", "studio-dct-444-10-progressive, yuv444p10le",
                "studio-mixed-422-10, yuv422p10le", "studio-dpcm-422-10-interlaced, yuv422p10le",
                "studio-dpcm-444-10-rgb, gbrp10le"})
        @DisplayName("10-bit frame VOP vectors decode like FFmpeg")
        void againstFfmpeg(String name, String pixelFormat, @org.junit.jupiter.api.io.TempDir Path work)
                throws IOException {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var data = resource(name + ".m4v");
            var file = work.resolve(name + ".m4v");
            Files.write(file, data);
            var ours = decode(data, true);
            var joined = new ByteArrayOutputStream();
            ours.samples().forEach(joined::writeBytes);
            assertMatchesFfmpeg(name, joined.toByteArray(), ffmpeg(file, pixelFormat));
        }

        @Test
        @DisplayName("a layer of R, G and B components is described by the identity matrix")
        void rgb() {
            var picture = decode(resource("studio-dpcm-444-10-rgb.m4v"), true).frames().getFirst();
            assertEquals(MatrixCoefficients.IDENTITY, ((Yuv16Picture) picture).color().matrix());
        }

        @Test
        @DisplayName("without high bit depth output, deep layers reduce to 8 bits as ImageOps.toEightBit does")
        void eightBitOutput() {
            var data = resource("studio-dpcm-444-12-progressive.m4v");
            var expected = new ArrayList<byte[]>();
            var options = VideoDecoderOptions.builder().highBitDepth(true).build();
            try (var source = MediaSource.of(data); var stream = Mpeg4ElementaryStream.open(source);
                 var decoder = new Mpeg4Decoder(stream.track(), options)) {
                for (var packet = stream.read(); packet != null; packet = stream.read()) {
                    decoder.decode(packet, frame -> expected.add(planes(ImageOps.toEightBit(
                            (Yuv16Picture) frame.picture()))));
                }
            }
            var reduced = decode(data, false);
            assertEquals(expected.size(), reduced.frames().size(), "frames");
            for (var i = 0; i < expected.size(); i++) {
                assertInstanceOf(YuvPicture.class, reduced.frames().get(i));
                assertArrayEquals(expected.get(i), reduced.samples().get(i), "frame " + i);
            }
        }

        @Test
        @DisplayName("a not coded VOP produces no frame and later frames keep their order")
        void notCoded() {
            var decoded = decode(resource("studio-dpcm-options.m4v"), true);
            assertEquals(2, decoded.frames().size());
        }

        @Test
        @DisplayName("a P-VOP raises the Core Studio inter VOP Unsupported and the other VOPs still decode")
        void interVop() {
            // the conformance streams declare video_object_type 15 in layers that carry P-VOPs, so the object type
            // does not decide whether a P-VOP is allowed
            var data = resource("studio-dpcm-420-8-progressive.m4v");
            var second = indexOf(data, 0xB6, indexOf(data, 0xB6, 0) + 4);
            // vop_prediction_type sits in bits 3 and 2 of the byte 14 bytes after the VOP start code
            data[second + 14] = (byte) ((data[second + 14] & ~0x0C) | 0x04);
            var decoded = decode(data, true);
            assertEquals(1, decoded.frames().size(), "frames");
            assertEquals(1, decoded.failures().size(), "failures");
            assertEquals("mpeg-4 core studio inter vops",
                    assertInstanceOf(MediaException.Unsupported.class, decoded.failures().getFirst()).format());
        }

        static int indexOf(byte[] data, int code, int from) {
            for (var i = from; i + 3 < data.length; i++) {
                if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 && (data[i + 3] & 0xFF) == code) {
                    return i;
                }
            }
            throw new AssertionError("no start code " + Integer.toHexString(code));
        }

        @Test
        @DisplayName("a truncated or corrupted stream raises only media exceptions")
        void damaged() {
            for (var name : List.of("studio-dct-422-10-tools", "studio-dpcm-options", "studio-mixed-422-10")) {
                var data = resource(name + ".m4v");
                for (var cut = 40; cut < data.length; cut += Math.max(1, data.length / 23)) {
                    var truncated = java.util.Arrays.copyOf(data, cut);
                    try {
                        decode(truncated, true);
                    } catch (MediaException expected) {
                        // a truncated header may fail the track itself
                    }
                }
                var random = new java.util.Random(name.hashCode());
                for (var round = 0; round < 40; round++) {
                    var corrupted = data.clone();
                    for (var flips = 0; flips < 4; flips++) {
                        var at = 60 + random.nextInt(corrupted.length - 60);
                        corrupted[at] ^= (byte) (1 << random.nextInt(8));
                    }
                    try {
                        decode(corrupted, true);
                    } catch (MediaException expected) {
                        // a corrupted header may fail the track itself
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("ISO/IEC 14496-4 streams")
    class Streams {
        @ParameterizedTest(name = "{0}")
        @CsvSource({"vcon-stp1L1", "vcon-stp2L1", "vcon-stp3L1", "vcon-stp4L1", "vcon-stp5L1", "vcon-stp6L1",
                "vcon-stp7L1", "vcon-stp8L1", "vcon-stp9L1", "vcon-stp10L1", "vcon-stp11L1"})
        @DisplayName("the I-VOPs decode to their pinned samples and the Core Studio P-VOPs are unsupported")
        void intra(String name) throws IOException {
            var file = Conformance.fetchListed("mpeg4visual-" + name + ".bits");
            var decoded = decode(Files.readAllBytes(file), true);
            var expected = CONFORMANCE.get(name);
            assertEquals(expected.size(), decoded.frames().size(), "frames");
            for (var i = 0; i < expected.size(); i++) {
                assertEquals(720, decoded.frames().get(i).width());
                assertEquals(480, decoded.frames().get(i).height());
                assertEquals(expected.get(i), md5(decoded.samples().get(i)), "frame " + i);
            }
            assertEquals(VOPS.get(name) - expected.size(), decoded.failures().size(), "P-VOPs");
            for (var failure : decoded.failures()) {
                var unsupported = assertInstanceOf(MediaException.Unsupported.class, failure);
                assertEquals("mpeg-4 core studio inter vops", unsupported.format());
            }
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"vcon-stp11L1, 6", "vcon-stp2L1, 1", "vcon-stp7L1, 1", "vcon-stp8L1, 1"})
        @DisplayName("the I-VOPs FFmpeg decodes match it within the inverse DCT noise")
        void againstFfmpeg(String name, int frames) throws IOException {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = Conformance.fetchListed("mpeg4visual-" + name + ".bits");
            var decoded = decode(Files.readAllBytes(file), true);
            var reference = ffmpeg(file, "yuv422p10le");
            var frame = decoded.samples().getFirst().length;
            for (var i = 0; i < frames; i++) {
                assertMatchesFfmpeg(name + " frame " + i, decoded.samples().get(i),
                        java.util.Arrays.copyOfRange(reference, i * frame, (i + 1) * frame));
            }
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"vcon-stpsh1L1", "vcon-stpsh2L1", "vcon-stpsp1L1"})
        @DisplayName("the shape and sprite streams, whose layer headers are not Studio headers, are rejected")
        void shapeAndSprite(String name) throws IOException {
            var file = Conformance.fetchListed("mpeg4visual-" + name + ".bits");
            var data = Files.readAllBytes(file);
            var error = assertThrows(MediaException.class, () -> decode(data, true));
            assertFalse(error.getMessage().isEmpty());
        }

        @Test
        @DisplayName("the FATE Studio sample decodes like FFmpeg")
        void fate() throws IOException {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = Conformance.fetch("fate-mpeg4-mpeg4_sstp_dpcm.m4v",
                    "https://fate-suite.ffmpeg.org/mpeg4/mpeg4_sstp_dpcm.m4v",
                    "c7389f15ceb3381be18ab21c932ef3cda952466bcd2ade824faf36a880ea860e");
            var decoded = decode(Files.readAllBytes(file), true);
            assertEquals(1, decoded.frames().size());
            assertEquals(CONFORMANCE.get("vcon-stp11L1").getFirst(), md5(decoded.samples().getFirst()));
            assertMatchesFfmpeg("sstp_dpcm", decoded.samples().getFirst(), ffmpeg(file, "yuv422p10le"));
        }
    }
}
