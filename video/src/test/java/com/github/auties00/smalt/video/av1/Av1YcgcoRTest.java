package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.avif.AvifDecoder;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes YCgCo-Re and YCgCo-Ro streams that libaom coded (through ffmpeg, from the synthetic R'G'B' frames that
 * {@code tools/testdata/av1-ycgco-r/make-vectors.py} defines and this class recomputes) in the default 8-bit mode,
 * which must hand out opaque 8-bit RGBA: the R'G'B' of the lossless vectors quantized once to 8 bits (the source
 * itself for 10-bit YCgCo-Re), and for every vector the RGBA that {@link ImageOps#toRgba(Picture)} makes of the
 * high bit depth decode and that the AVIF decoder makes of the same payload. libavif's avifdec, a black box, decodes
 * the AVIF copy of the full-range 10-bit YCgCo-Re vector to the same pixels.
 */
@DisplayName("AV1 YCgCo-Re and YCgCo-Ro streams in the 8-bit mode")
class Av1YcgcoRTest {
    private static final int WIDTH = 48;
    private static final int HEIGHT = 40;
    private static final Duration TIMEOUT = Duration.ofMinutes(2);

    /**
     * Describes a vector: its matrix, coded depth, chroma layout, range and losslessness, as the script codes it.
     */
    record Vector(String name, MatrixCoefficients matrix, int depth, String layout, boolean full, boolean lossless,
                  int frames) {
        @Override
        public String toString() {
            return name;
        }

        int rgbDepth() {
            return matrix == MatrixCoefficients.YCGCO_RE ? depth - 2 : depth - 1;
        }

        ColorRange range() {
            return full ? ColorRange.FULL : ColorRange.LIMITED;
        }

        byte[] file(String extension) {
            var file = name + extension;
            try (var input = Av1YcgcoRTest.class.getResourceAsStream("ycgcor/" + file)) {
                if (input == null) {
                    throw new IllegalStateException("missing vector " + file);
                }
                var data = input.readAllBytes();
                assertEquals(PINS.get(file), sha256(data), file);
                return data;
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private static final List<Vector> VECTORS = List.of(
            new Vector("re10-full-444", MatrixCoefficients.YCGCO_RE, 10, "444", true, true, 3),
            new Vector("re10-limited-444", MatrixCoefficients.YCGCO_RE, 10, "444", false, true, 2),
            new Vector("ro10-full-444", MatrixCoefficients.YCGCO_RO, 10, "444", true, true, 3),
            new Vector("re12-limited-444", MatrixCoefficients.YCGCO_RE, 12, "444", false, true, 2),
            new Vector("ro12-full-444", MatrixCoefficients.YCGCO_RO, 12, "444", true, true, 2),
            new Vector("re10-full-mono", MatrixCoefficients.YCGCO_RE, 10, "mono", true, true, 2),
            new Vector("re10-full-420", MatrixCoefficients.YCGCO_RE, 10, "420", true, false, 3),
            new Vector("ro10-limited-422", MatrixCoefficients.YCGCO_RO, 10, "422", false, false, 2));

    private static final Map<String, String> PINS = pins();

    static Stream<Vector> vectors() {
        return VECTORS.stream();
    }

    static Stream<Vector> lossless() {
        return VECTORS.stream().filter(Vector::lossless);
    }

    static Stream<Vector> lossy() {
        return VECTORS.stream().filter(vector -> !vector.lossless());
    }

    static Stream<Vector> stills() {
        return VECTORS.stream().filter(vector -> vector.lossless() && vector.layout().equals("444"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lossless")
    @DisplayName("the lossless vectors decode to their R'G'B' quantized once to 8 bits")
    void losslessRgb(Vector vector) {
        var frames = decode(vector.file(".ivf"), Av1DecoderOptions.defaults());
        assertEquals(vector.frames(), frames.size());
        var max = (1 << vector.rgbDepth()) - 1;
        for (var f = 0; f < frames.size(); f++) {
            var pixels = frames.get(f);
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    var o = 4 * (y * WIDTH + x);
                    for (var c = 0; c < 3; c++) {
                        var s = sample(vector.layout().equals("mono") ? 1 : c, x, y, f, vector.rgbDepth(), true);
                        assertQuantized(pixels[o + c] & 0xFF, s, max, vector.full(),
                                vector + " frame " + f + " (" + x + ", " + y + ") channel " + c);
                        if (vector.matrix() == MatrixCoefficients.YCGCO_RE && vector.depth() == 10 && vector.full()) {
                            assertEquals(s, pixels[o + c] & 0xFF, "8-bit R'G'B' comes back unchanged");
                        }
                    }
                    assertEquals(-1, pixels[o + 3], "opaque");
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lossless")
    @DisplayName("libaom restores the transformed samples exactly in the high bit depth mode")
    void losslessSamples(Vector vector) {
        var options = Av1DecoderOptions.builder().highBitDepth(true).build();
        var f = new int[1];
        var half = 1 << (vector.depth() - 1);
        forEachFrame(vector.file(".ivf"), options, picture -> {
            var yuv = assertInstanceOf(Yuv16Picture.class, picture);
            assertEquals(vector.depth(), yuv.bitDepth());
            assertEquals(vector.matrix(), yuv.color().matrix());
            assertEquals(vector.range(), yuv.color().range());
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    var mono = vector.layout().equals("mono");
                    var r = sample(mono ? 1 : 0, x, y, f[0], vector.rgbDepth(), true);
                    var g = sample(1, x, y, f[0], vector.rgbDepth(), true);
                    var b = sample(mono ? 1 : 2, x, y, f[0], vector.rgbDepth(), true);
                    var co = r - b;
                    var t = b + (co >> 1);
                    var cg = g - t;
                    assertEquals(t + (cg >> 1), yuv.y()[y * yuv.yStride() + x] & 0xFFFF, "Y");
                    if (!mono) {
                        assertEquals(cg + half, yuv.u()[y * yuv.uvStride() + x] & 0xFFFF, "Cg");
                        assertEquals(co + half, yuv.v()[y * yuv.uvStride() + x] & 0xFFFF, "Co");
                    }
                }
            }
            f[0]++;
        });
        assertEquals(vector.frames(), f[0]);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("vectors")
    @DisplayName("every frame equals ImageOps.toRgba of the high bit depth decode, with one or several threads")
    void matchesImageOps(Vector vector) {
        var file = vector.file(".ivf");
        var expected = new ArrayList<byte[]>();
        forEachFrame(file, Av1DecoderOptions.builder().highBitDepth(true).build(),
                picture -> expected.add(RasterTesting.tight(ImageOps.toRgba(picture))));
        for (var options : List.of(Av1DecoderOptions.defaults(), Av1DecoderOptions.builder().threads(1).build(),
                Av1DecoderOptions.builder().maxFrameDelay(3).build())) {
            var actual = decode(file, options);
            assertEquals(expected.size(), actual.size());
            for (var i = 0; i < actual.size(); i++) {
                Tolerance.exact().assertWithin(vector + " frame " + i, expected.get(i), actual.get(i));
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lossy")
    @DisplayName("the lossy vectors stay close to their source")
    void lossyQuality(Vector vector) {
        var frames = decode(vector.file(".ivf"), Av1DecoderOptions.defaults());
        var max = (1 << vector.rgbDepth()) - 1;
        for (var f = 0; f < frames.size(); f++) {
            var source = new byte[WIDTH * HEIGHT * 4];
            for (var i = 0; i < WIDTH * HEIGHT; i++) {
                for (var c = 0; c < 3; c++) {
                    var s = sample(vector.layout().equals("mono") ? 1 : c, i % WIDTH, i / WIDTH, f, vector.rgbDepth(),
                            false);
                    source[4 * i + c] = (byte) quantize(s, max, vector.full());
                }
                source[4 * i + 3] = -1;
            }
            Tolerance.psnr(38).assertWithin(vector + " frame " + f, source, frames.get(f));
        }
    }

    @Test
    @DisplayName("frames share one recycled RGBA picture")
    void recycled() {
        var pictures = new ArrayList<Picture>();
        forEachFrame(VECTORS.getFirst().file(".ivf"), Av1DecoderOptions.defaults(), pictures::add);
        assertEquals(3, pictures.size());
        assertInstanceOf(RgbaPicture.class, pictures.getFirst());
        assertFalse(pictures.getFirst().hasAlpha());
        assertSame(pictures.get(0), pictures.get(1));
        assertSame(pictures.get(1), pictures.get(2));
    }

    @Test
    @DisplayName("the track's color description decides the matrix")
    void trackColor() {
        var file = VECTORS.getFirst().file(".ivf");
        var bt709 = new ColorInfo(MatrixCoefficients.BT709, ColorRange.FULL, ColorPrimaries.BT709,
                TransferCharacteristics.SRGB);
        forEachFrame(file, track -> track.toBuilder().color(bt709).build(), Av1DecoderOptions.defaults(),
                picture -> assertInstanceOf(YuvPicture.class, picture));
        var ycgcoRo = bt709.withMatrix(MatrixCoefficients.YCGCO_RO);
        var expected = new ArrayList<byte[]>();
        forEachFrame(file, track -> track.toBuilder().color(ycgcoRo).build(),
                Av1DecoderOptions.builder().highBitDepth(true).build(),
                picture -> expected.add(RasterTesting.tight(ImageOps.toRgba(picture))));
        var i = new int[1];
        forEachFrame(file, track -> track.toBuilder().color(ycgcoRo).build(), Av1DecoderOptions.defaults(),
                picture -> Tolerance.exact().assertWithin("frame " + i[0], expected.get(i[0]++),
                        RasterTesting.tight(picture)));
        assertEquals(3, i[0]);
    }

    @Test
    @DisplayName("8-bit streams stay Y'CbCr in the YCgCo-R matrices")
    void eightBitStaysYuv() {
        var file = superres("superres-fixed-8bit.ivf");
        for (var matrix : List.of(MatrixCoefficients.YCGCO_RE, MatrixCoefficients.YCGCO_RO)) {
            var count = new int[1];
            forEachFrame(file, track -> track.toBuilder().color(ycgco(matrix, ColorRange.FULL)).build(),
                    Av1DecoderOptions.defaults(), picture -> {
                        var yuv = assertInstanceOf(YuvPicture.class, picture);
                        assertEquals(matrix, yuv.color().matrix());
                        count[0]++;
                    });
            assertTrue(count[0] > 0);
        }
    }

    @Test
    @DisplayName("damaged packets fail with a media exception or still give opaque RGBA")
    void damagedPackets() {
        var file = VECTORS.getFirst().file(".ivf");
        var random = new java.util.SplittableRandom(29);
        for (var round = 0; round < 64; round++) {
            var packets = new ArrayList<com.github.auties00.smalt.track.Packet>();
            VideoTrack track;
            try (var source = MediaSource.of(file);
                 var demuxer = Containers.openDemuxer(source)) {
                track = (VideoTrack) demuxer.tracks().getFirst();
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    packets.add(packet);
                }
            }
            try (var decoder = new Av1Decoder(track, Av1DecoderOptions.defaults())) {
                for (var packet : packets) {
                    var data = packet.data().clone();
                    var cut = round % 2 == 0 ? data.length : 1 + random.nextInt(data.length);
                    data = java.util.Arrays.copyOf(data, cut);
                    for (var flips = 0; flips < 1 + random.nextInt(4); flips++) {
                        data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
                    }
                    try {
                        decoder.decode(packet.withData(data), frame -> assertOpaque(frame.picture()));
                    } catch (com.github.auties00.smalt.MediaException expected) {
                        // a damaged packet may be rejected
                    }
                }
                try {
                    decoder.flush(frame -> assertOpaque(frame.picture()));
                } catch (com.github.auties00.smalt.MediaException expected) {
                    // a damaged stream may be rejected at the end
                }
            }
        }
    }

    private static void assertOpaque(Picture picture) {
        if (picture instanceof RgbaPicture rgba) {
            assertFalse(rgba.hasAlpha());
            for (var y = 0; y < rgba.height(); y++) {
                for (var x = 0; x < rgba.width(); x++) {
                    assertEquals(-1, rgba.pixels()[y * rgba.stride() + 4 * x + 3]);
                }
            }
        }
    }

    static Stream<String> naturalStreams() {
        return Stream.of("superres-10bit.ivf", "superres-12bit.ivf", "superres-422-10bit.ivf");
    }

    // The vectors of this class are synthetic and small; these streams have natural content, other sizes, chroma
    // that varies within a row, and super-resolution, read as YCgCo-R through the track's color description
    @ParameterizedTest(name = "{0}")
    @MethodSource("naturalStreams")
    @DisplayName("high bit depth streams of natural content match ImageOps.toRgba in both matrices and ranges")
    void naturalContent(String name) {
        var file = superres(name);
        for (var matrix : List.of(MatrixCoefficients.YCGCO_RE, MatrixCoefficients.YCGCO_RO)) {
            for (var range : ColorRange.values()) {
                java.util.function.UnaryOperator<VideoTrack> edit =
                        track -> track.toBuilder().color(ycgco(matrix, range)).build();
                var expected = new ArrayList<byte[]>();
                forEachFrame(file, edit, Av1DecoderOptions.builder().highBitDepth(true).build(), picture -> {
                    assertInstanceOf(Yuv16Picture.class, picture);
                    expected.add(RasterTesting.tight(ImageOps.toRgba(picture)));
                });
                var i = new int[1];
                forEachFrame(file, edit, Av1DecoderOptions.defaults(), picture -> {
                    var rgba = assertInstanceOf(RgbaPicture.class, picture);
                    Tolerance.exact().assertWithin(name + " " + matrix + " " + range + " frame " + i[0],
                            expected.get(i[0]++), RasterTesting.tight(rgba));
                });
                assertEquals(expected.size(), i[0]);
                assertTrue(i[0] > 0);
            }
        }
    }

    @Test
    @DisplayName("one decoder follows new sequences of other sizes, depths and layouts")
    void sequenceChanges() {
        var files = List.of(VECTORS.getFirst().file(".ivf"), superres("superres-10bit.ivf"),
                superres("superres-12bit.ivf"), VECTORS.get(6).file(".ivf"), superres("superres-422-10bit.ivf"),
                VECTORS.getFirst().file(".ivf"));
        var color = ycgco(MatrixCoefficients.YCGCO_RO, ColorRange.LIMITED);
        var expected = new ArrayList<byte[]>();
        decodeAll(files, color, Av1DecoderOptions.builder().highBitDepth(true).build(),
                picture -> expected.add(RasterTesting.tight(ImageOps.toRgba(picture))));
        var actual = new ArrayList<byte[]>();
        var sizes = new java.util.HashSet<Long>();
        decodeAll(files, color, Av1DecoderOptions.defaults(), picture -> {
            var rgba = assertInstanceOf(RgbaPicture.class, picture);
            sizes.add(((long) rgba.width() << 32) | rgba.height());
            actual.add(RasterTesting.tight(rgba));
        });
        assertEquals(expected.size(), actual.size());
        assertTrue(sizes.size() > 1, "the streams have several sizes");
        for (var i = 0; i < actual.size(); i++) {
            Tolerance.exact().assertWithin("frame " + i, expected.get(i), actual.get(i));
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("stills")
    @DisplayName("the AVIF decoder decodes the same payload to the same RGBA")
    void avifDecoder(Vector vector) {
        var still = AvifDecoder.decode(MediaSource.of(vector.file(".avif"))).picture();
        var frame = decode(vector.file(".ivf"), Av1DecoderOptions.defaults()).getFirst();
        Tolerance.exact().assertWithin(vector.toString(), RasterTesting.tight(still), frame);
    }

    static Stream<Vector> avifdecStills() {
        return stills().filter(vector -> vector.matrix() == MatrixCoefficients.YCGCO_RE && vector.depth() == 10
                && vector.full());
    }

    // avifdec converts YCgCo-R to RGB only in full range, and writes PNG only from R'G'B' of the PNG's depth, which
    // at 8 bits leaves full-range 10-bit YCgCo-Re
    @ParameterizedTest(name = "{0}")
    @MethodSource("avifdecStills")
    @DisplayName("libavif's avifdec decodes the same payload to the same RGBA")
    void avifdec(Vector vector) throws IOException {
        var directory = Scratch.directory("av1-ycgco-r");
        var input = directory.resolve(vector.name() + ".avif");
        Files.write(input, vector.file(".avif"));
        var png = directory.resolve(vector.name() + ".png");
        var raw = directory.resolve(vector.name() + ".rgba");
        Files.deleteIfExists(png);
        Files.deleteIfExists(raw);
        Oracle.run(Oracle.Tool.AVIFDEC, TIMEOUT, List.of("--depth", "8", input.toString(), png.toString()));
        Oracle.run(Oracle.Tool.FFMPEG, TIMEOUT, List.of("-v", "error", "-y", "-i", png.toString(), "-f", "rawvideo",
                "-pix_fmt", "rgba", raw.toString()));
        var frame = decode(vector.file(".ivf"), Av1DecoderOptions.defaults()).getFirst();
        Tolerance.exact().assertWithin(vector.toString(), Files.readAllBytes(raw), frame);
    }

    /**
     * Requires an 8-bit value to be the R'G'B' sample's normalized signal times 255, clamped and rounded to nearest,
     * computed in exact arithmetic; at an exact tie either neighbor is accepted.
     */
    private static void assertQuantized(int actual, int sample, int max, boolean full, String what) {
        long num;
        long den;
        if (full) {
            num = 255L * sample;
            den = max;
        } else {
            var scale = (max + 1) / 256;
            num = 255L * (sample - 16L * scale);
            den = 219L * scale;
        }
        if (num <= 0) {
            assertEquals(0, actual, what);
            return;
        }
        if (num >= 255 * den) {
            assertEquals(255, actual, what);
            return;
        }
        var nearest = (2 * num + den) / (2 * den);
        if ((2 * num - den) % (2 * den) == 0) {
            assertTrue(actual == nearest || actual == nearest - 1, what + ": " + actual + " at a tie");
        } else {
            assertEquals(nearest, actual, what);
        }
    }

    /** {@return an R'G'B' sample quantized to 8 bits, ties rounded up} */
    private static int quantize(int sample, int max, boolean full) {
        long num;
        long den;
        if (full) {
            num = 255L * sample;
            den = max;
        } else {
            var scale = (max + 1) / 256;
            num = 255L * (sample - 16L * scale);
            den = 219L * scale;
        }
        return (int) Math.clamp(Math.floorDiv(2 * num + den, 2 * den), 0, 255);
    }

    /** {@return the R'G'B' sample the vector script defines for a lossless or a lossy vector} */
    private static int sample(int channel, int x, int y, int frame, int rgbDepth, boolean lossless) {
        if (!lossless) {
            return (x * (16 + 4 * channel) + y * (12 + 3 * channel) + frame * 8) * ((1 << rgbDepth) - 1)
                    / (47 * (16 + 4 * channel) + 39 * (12 + 3 * channel) + 16);
        }
        return (x * (37 + 2 * channel) + y * (23 + 5 * channel) + frame * 17 + ((x * y + 13 * channel) % 7) * 29)
                % (1 << rgbDepth);
    }

    /** {@return the frames of a stream as tight RGBA, each required to be an opaque RGBA picture} */
    private static List<byte[]> decode(byte[] file, Av1DecoderOptions options) {
        var frames = new ArrayList<byte[]>();
        forEachFrame(file, options, picture -> {
            var rgba = assertInstanceOf(RgbaPicture.class, picture);
            assertFalse(rgba.hasAlpha());
            frames.add(RasterTesting.tight(rgba));
        });
        return frames;
    }

    private static void forEachFrame(byte[] file, Av1DecoderOptions options, java.util.function.Consumer<Picture> sink) {
        forEachFrame(file, track -> track, options, sink);
    }

    private static void forEachFrame(byte[] file, java.util.function.UnaryOperator<VideoTrack> trackEdit,
                                     Av1DecoderOptions options, java.util.function.Consumer<Picture> sink) {
        try (var source = MediaSource.of(file);
             var demuxer = Containers.openDemuxer(source)) {
            var track = trackEdit.apply((VideoTrack) demuxer.tracks().getFirst());
            try (var decoder = new Av1Decoder(track, options)) {
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    decoder.decode(packet, frame -> sink.accept(frame.picture()));
                }
                decoder.flush(frame -> sink.accept(frame.picture()));
            }
        }
    }

    /** {@return a YCgCo-R color description with BT.709 primaries and the sRGB transfer} */
    private static ColorInfo ycgco(MatrixCoefficients matrix, ColorRange range) {
        return new ColorInfo(matrix, range, ColorPrimaries.BT709, TransferCharacteristics.SRGB);
    }

    /** {@return a stream of the super-resolution fixtures of this package} */
    private static byte[] superres(String name) {
        try (var input = Av1YcgcoRTest.class.getResourceAsStream("superres/" + name)) {
            if (input == null) {
                throw new IllegalStateException("missing vector " + name);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Decodes the packets of several streams, one after the other, with one decoder of the first stream's track. */
    private static void decodeAll(List<byte[]> files, ColorInfo color, Av1DecoderOptions options,
                                  java.util.function.Consumer<Picture> sink) {
        var packets = new ArrayList<com.github.auties00.smalt.track.Packet>();
        VideoTrack first = null;
        for (var file : files) {
            try (var source = MediaSource.of(file);
                 var demuxer = Containers.openDemuxer(source)) {
                if (first == null) {
                    first = (VideoTrack) demuxer.tracks().getFirst();
                }
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    packets.add(packet);
                }
            }
        }
        try (var decoder = new Av1Decoder(first.toBuilder().color(color).build(), options)) {
            for (var packet : packets) {
                decoder.decode(packet, frame -> sink.accept(frame.picture()));
            }
            decoder.flush(frame -> sink.accept(frame.picture()));
        }
    }

    private static Map<String, String> pins() {
        try (var input = Av1YcgcoRTest.class.getResourceAsStream("ycgcor/ycgcor.tsv")) {
            var pins = new java.util.HashMap<String, String>();
            for (var line : new String(input.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var columns = line.strip().split("\t");
                pins.put(columns[0], columns[2]);
            }
            return pins;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
