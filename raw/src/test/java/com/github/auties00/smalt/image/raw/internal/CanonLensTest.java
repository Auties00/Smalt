package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.raw.Cr3Writer;
import com.github.auties00.smalt.image.raw.DngEncoder;
import com.github.auties00.smalt.image.raw.DngEncoderOptions;
import com.github.auties00.smalt.image.raw.LensShot;
import com.github.auties00.smalt.image.raw.Lj92Encoder;
import com.github.auties00.smalt.image.raw.RawDecoder;
import com.github.auties00.smalt.image.raw.RawDecoderOptions;
import com.github.auties00.smalt.image.raw.RawFormat;
import com.github.auties00.smalt.image.raw.VendorTiffWriter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the lens correction of Canon PowerShot maker notes. The {@code canon-lens} resources hold tag {@code 0x4015}
 * as stored in CC0 raw.pixls.us samples, one for each layout (the names say the camera; see
 * {@code tools/testdata/README.md}), whose maps are compared with values worked out by hand from the tables' bytes;
 * synthetic files of {@link LensShot} go through the whole decoder; the samples are compared with their camera JPEG
 * (skipped when not cached). {@code LensCorrectionOracleTest} compares them with Adobe DNG Converter.
 */
class CanonLensTest {
    private static final int WIDTH = 1200;
    private static final int HEIGHT = 800;
    private static final double[] DISTORTION = {0.99, -0.14, 0.05, -0.01};
    private static final double[] RED = {0, 0.0015};
    private static final double[] BLUE = {0, -0.002, 0.0005};
    private static final double[] NONE = {0};

    private static byte[] resource(String name) {
        try (var stream = CanonLensTest.class.getResourceAsStream("canon-lens/" + name + ".bin")) {
            assertNotNull(stream, name);
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static double source(LensCorrection lens, int plane, double pixels) {
        return pixels * LensCorrection.evaluate(lens.distortionKnots, lens.distortion[plane], pixels / lens.radius);
    }

    /**
     * A fixture's expected source radii at an output radius, on a sensor of a given size without a crop.
     */
    private record Expected(String tag, int width, int height, double radius, double red, double green, double blue) {
    }

    private static Stream<Expected> tables() {
        // 0x8001: between the knots of 1316 (factor 7856) and 1612 (7696), at the latter
        var s95 = 1612 * 7696 / 8192.0;
        // 0x8002: 7405 interpolates the factors 7558 and 7376 in the square of the radii 1768 and 2041; the plane
        // tables hold 8253 and 8188 at 2000
        var s110 = 2000 * 7405.0 / 8192;
        // 0x8003: the first table holds 9641/256 at 1250, the plane tables 40/128 and 88/128
        var g16 = 1250 - 9641 / 256.0;
        // 0x8004: the first table holds 24052/256 at 26306/16, the plane tables 259/128 and -41/128
        var g7x = 26306 / 16.0;
        // 0x8005: a pincushion, scaled so that the crop's corner, 17468/256 pixels out, stays inside
        var g7xii = 1 / (1 + 17468 / 256.0 / (52612 / 16.0));
        // the CR3 tables hold 15216, 16714 and 16336 at half the half diagonal
        var half = Math.hypot(5472 / 2.0, 3648 / 2.0) / 2;
        var g7xiii = half * (1 - 0.8 * (1 - 15216 / 16384.0));
        var v1 = Math.hypot(5760 / 2.0, 3840 / 2.0) / 2;
        return Stream.of(
                new Expected("s95", 3648, 2736, 1612, s95, s95, s95),
                new Expected("s110", 4000, 3000, 2000, s110 * (1 + 61 / 65536.0), s110, s110 * (1 - 4 / 65536.0)),
                new Expected("g15", 4000, 3000, 2000, 2000 * (1 - 28 / 65536.0), 2000, 2000 * (1 - 14 / 65536.0)),
                new Expected("g16", 4000, 3000, 1250, g16 + 40 / 128.0, g16, g16 + 88 / 128.0),
                new Expected("g7x", 5472, 3648, g7x, g7x - 24052 / 256.0 + 259 / 128.0, g7x - 24052 / 256.0,
                        g7x - 24052 / 256.0 - 41 / 128.0),
                new Expected("g7xii", 5472, 3648, g7x, (g7x + 2291 / 256.0 - 43 / 128.0) * g7xii, (g7x + 2291 / 256.0) * g7xii,
                        (g7x + 2291 / 256.0 - 35 / 128.0) * g7xii),
                new Expected("g7xiii", 5472, 3648, half, g7xiii * (1 + 330 / 524288.0), g7xiii, g7xiii * (1 - 48 / 524288.0)),
                new Expected("v1", 5760, 3840, v1, v1 * (1 + 189 / 524288.0), v1, v1 * (1 - 59 / 524288.0)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("tables")
    @DisplayName("each layout's tables give the source radii worked out from their bytes")
    void layouts(Expected expected) {
        var lens = CanonLens.of(resource(expected.tag()), new int[]{0, 0, expected.width(), expected.height()},
                expected.width(), expected.height());
        assertNotNull(lens);
        assertEquals(expected.width() / 2.0, lens.centerX, 1e-9);
        assertEquals(expected.height() / 2.0, lens.centerY, 1e-9);
        assertEquals(Math.hypot(expected.width() / 2.0, expected.height() / 2.0), lens.radius, 1e-9);
        assertEquals(expected.red(), source(lens, 0, expected.radius()), 0.15, "red");
        assertEquals(expected.green(), source(lens, 1, expected.radius()), 0.15, "green");
        assertEquals(expected.blue(), source(lens, 2, expected.radius()), 0.15, "blue");
    }

    @Test
    @DisplayName("the distortion knots of the first layouts interpolate in the square of the radius")
    void squared() {
        var lens = CanonLens.of(resource("s95"), new int[]{0, 0, 3648, 2736}, 3648, 2736);
        assertNotNull(lens);
        // half way from the center to 931 in the square of the radius: 8021 is 8192 less 171
        var pixels = 931 / Math.sqrt(2);
        assertEquals(pixels * (8192 - 171 / 2.0) / 8192, source(lens, 1, pixels), 0.1);
    }

    @Test
    @DisplayName("the center is the default crop's")
    void center() {
        var lens = CanonLens.of(resource("g7x"), new int[]{24, 12, 5472, 3648}, 5520, 3672);
        assertNotNull(lens);
        assertEquals(24 + 2736, lens.centerX, 1e-9);
        assertEquals(12 + 1824, lens.centerY, 1e-9);
        assertEquals(Math.hypot(2760, 1836), lens.radius, 1e-9);
    }

    @ParameterizedTest
    @ValueSource(strings = {"g11", "eos-m50"})
    @DisplayName("a tag without a correction gives none: a table of zero count, the layout of the other cameras")
    void none(String name) {
        assertNull(CanonLens.of(resource(name), new int[]{0, 0, 4000, 3000}, 4000, 3000));
    }

    @Test
    @DisplayName("a CR3 table of ones gives none")
    void identity() {
        var writer = writer("Canon PowerShot G5 X Mark II");
        assertNull(CanonLens.of(LensShot.table(writer, new double[]{1}, NONE, NONE), new int[]{4, 4, WIDTH - 8, HEIGHT - 8},
                WIDTH, HEIGHT));
    }

    private static void put16(byte[] tag, int word, int value) {
        ByteBuffer.wrap(tag).order(ByteOrder.LITTLE_ENDIAN).putShort(2 * word, (short) value);
    }

    private static void put32(byte[] tag, int word, int value) {
        ByteBuffer.wrap(tag).order(ByteOrder.LITTLE_ENDIAN).putInt(4 * word, value);
    }

    private static LensCorrection of(byte[] tag) {
        return CanonLens.of(tag, new int[]{0, 0, 5472, 3648}, 5472, 3648);
    }

    @Test
    @DisplayName("damaged tables give no correction: disordered radii, factors no lens has, a wrong count")
    void damaged() {
        var s95 = resource("s95");
        put16(s95, 25, 100);
        assertNull(of(s95), "disordered radii");
        s95 = resource("s95");
        put16(s95, 32, 1);
        assertNull(of(s95), "factor of nearly zero");
        s95 = resource("s95");
        put16(s95, 17, 8);
        assertNull(of(s95), "count");
        var s110 = resource("s110");
        put16(s110, 57, 500);
        assertNull(of(s110), "disordered radii of a plane");
        var g7x = resource("g7x");
        put32(g7x, 41, 1);
        assertNull(of(g7x), "disordered radii, 32-bit words");
        g7x = resource("g7x");
        put32(g7x, 99, 12345);
        assertNull(of(g7x), "a missing plane table");
        var g7xiii = resource("g7xiii");
        put16(g7xiii, 64 + 279 + 5, 0);
        assertNull(of(g7xiii), "disordered radii, CR3");
        g7xiii = resource("g7xiii");
        put16(g7xiii, 64 + 279, 7);
        assertNull(of(g7xiii), "no first radius, CR3");
        g7xiii = resource("g7xiii");
        put16(g7xiii, 64 + 233 + 8, 60000);
        assertNull(of(g7xiii), "a plane's factor no lens has");
        assertNull(of(new byte[0]));
        assertNull(of(new byte[]{1, (byte) 0x80}));
        assertNull(of(null));
    }

    @Test
    @DisplayName("a tag cut short or with random bytes changed fails in no other way than giving no correction")
    void robust() {
        var random = new Random(1);
        for (var name : List.of("s95", "g11", "s110", "g15", "g16", "g7x", "g7xii", "g7xiii", "sx70", "v1", "eos-m50")) {
            var tag = resource(name);
            for (var length = 0; length < tag.length; length += 7) {
                var cut = java.util.Arrays.copyOf(tag, length);
                assertSound(of(cut), name);
            }
            for (var trial = 0; trial < 400; trial++) {
                var changed = tag.clone();
                for (var change = 0; change < 1 + trial % 8; change++) {
                    changed[random.nextInt(changed.length)] = (byte) random.nextInt(256);
                }
                assertSound(of(changed), name);
            }
        }
    }

    /**
     * Checks that a correction, if any, maps every radius up to the corner to a finite source radius near it, and that
     * its opcodes are finite.
     */
    private static void assertSound(LensCorrection lens, String name) {
        if (lens == null) {
            return;
        }
        for (var plane = 0; plane < 3; plane++) {
            for (var knot = 0; knot < lens.distortionKnots.length && lens.distortionKnots[knot] <= 1; knot++) {
                var factor = lens.distortion[plane][knot];
                assertTrue(factor > 0.3 && factor < 1.6, name + ": factor " + factor + " of plane " + plane);
            }
        }
        var list = lens.opcodes(5472, 3648);
        assertNotNull(list, name);
        var opcodes = ByteBuffer.wrap(list);
        assertEquals(1, opcodes.getInt(), name);
        assertEquals(1, opcodes.getInt(), name + ": WarpRectilinear");
        opcodes.position(opcodes.position() + 12);
        assertEquals(3, opcodes.getInt(), name + ": planes");
        while (opcodes.remaining() >= 8) {
            var value = opcodes.getDouble();
            assertTrue(Double.isFinite(value), name + ": opcode parameter " + value);
        }
    }

    /**
     * {@return the first word of each of the four tables of a tag of 32-bit words}
     */
    private static int[] intTables(byte[] tag) {
        var words = ByteBuffer.wrap(tag).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        var tables = new int[4];
        var found = 0;
        for (var word = 0; word + 3 < words.limit() && found < 4; word++) {
            if (words.get(word) == 16 && words.get(word + 1) == (found < 2 ? 256 : 128) && words.get(word + 2) == 0
                    && words.get(word + 3) == 0) {
                tables[found++] = word + 2;
                word += 35;
            }
        }
        assertEquals(4, found);
        return tables;
    }

    @Test
    @DisplayName("a tag of 32-bit words whose last table ends the tag is read")
    void lastTableAtEnd() {
        var g7x = resource("g7x");
        var end = intTables(g7x)[3] + 34;
        assertTrue(4 * end < g7x.length);
        var cut = java.util.Arrays.copyOf(g7x, 4 * end);
        var lens = of(cut);
        assertNotNull(lens);
        var whole = of(g7x);
        for (var plane = 0; plane < 3; plane++) {
            assertArrayEquals(whole.distortion[plane], lens.distortion[plane], 1e-12);
        }
    }

    @Test
    @DisplayName("a table that maps the pixels next to the center onto it, between the radii a crop's corner is checked at, gives none")
    void collapsedCenter() {
        var g16 = resource("g16");
        var first = intTables(g16)[0];
        put32(g16, first + 2, 16);
        put32(g16, first + 3, 256);
        assertNull(of(g16));
        g16 = resource("g16");
        var plane = intTables(g16)[2];
        put32(g16, plane + 2, 16);
        put32(g16, plane + 3, -128);
        assertNull(of(g16), "a plane");
    }

    private static Cr3Writer writer(String model) {
        var writer = new Cr3Writer();
        writer.width = WIDTH;
        writer.height = HEIGHT;
        writer.model = model;
        return writer;
    }

    private static byte[] shot(long seed, String model) {
        var writer = writer(model);
        var scene = new LensShot.Scene(seed, WIDTH, HEIGHT);
        writer.samples = LensShot.photosites(scene, writer, DISTORTION, RED, BLUE);
        writer.lensTable = LensShot.table(writer, DISTORTION, RED, BLUE);
        return writer.write();
    }

    private static RawFile parse(byte[] data) {
        try (var source = MediaSource.of(data)) {
            return RawParser.parse(source, MediaLimits.DEFAULT);
        }
    }

    @Test
    @DisplayName("a CR3 file's timed metadata tag gives the known lens within half a pixel")
    void cr3File() {
        var lens = parse(shot(1, "Canon PowerShot Synthetic")).sensor.lens;
        assertNotNull(lens);
        var corner = Math.hypot(WIDTH / 2.0, HEIGHT / 2.0);
        assertEquals(corner, lens.radius, 1e-9);
        var reach = Math.hypot((WIDTH - 8) / 2.0, (HEIGHT - 8) / 2.0);
        var largest = 0.0;
        for (var step = 1; step <= 100; step++) {
            var pixels = reach * step / 100;
            var u = pixels / corner;
            var green = pixels * LensShot.polynomial(DISTORTION, u);
            assertEquals(green, source(lens, 1, pixels), 0.5, "at " + pixels);
            assertEquals(green * (1 + LensShot.polynomial(RED, u)), source(lens, 0, pixels), 0.5, "at " + pixels);
            assertEquals(green * (1 + LensShot.polynomial(BLUE, u)), source(lens, 2, pixels), 0.5, "at " + pixels);
            largest = Math.max(largest, Math.abs(green - pixels));
        }
        assertTrue(largest > 30, "the distortion moves the corner by " + largest + " pixels");
    }

    @Test
    @DisplayName("the rendering takes the file's lens away: it correlates with the scene, the uncorrected one less")
    void rendering() {
        var data = shot(5, "Canon PowerShot Synthetic");
        var options = RawDecoderOptions.builder().embeddedPreview(false).build();
        var with = correlationWithScene(RawDecoder.decode(MediaSource.of(data), options).picture(), 5);
        var without = correlationWithScene(RawDecoder.decode(MediaSource.of(data),
                options.toBuilder().lensCorrections(false).build()).picture(), 5);
        assertTrue(with > 0.97, "correlation " + with + " with the correction");
        assertTrue(without < 0.5, "correlation " + without + " without the correction");
    }

    private static double correlationWithScene(Picture image, long seed) {
        var picture = ImageOps.toRgba(image);
        var scene = new LensShot.Scene(seed, WIDTH, HEIGHT);
        var pixels = picture.pixels();
        double sa = 0;
        double sb = 0;
        double aa = 0;
        double bb = 0;
        double ab = 0;
        var count = 0;
        for (var y = 8; y < picture.height() - 8; y += 3) {
            for (var x = 8; x < picture.width() - 8; x += 3) {
                double a = pixels[y * picture.stride() + x * 4 + 1] & 0xFF;
                var b = scene.at(x, y);
                sa += a;
                sb += b;
                aa += a * a;
                bb += b * b;
                ab += a * b;
                count++;
            }
        }
        return (ab - sa * sb / count) / Math.sqrt((aa - sa * sa / count) * (bb - sb * sb / count));
    }

    @Test
    @DisplayName("a DNG conversion carries the correction as a WarpRectilinear opcode list 3")
    void conversion() {
        var data = shot(6, "Canon PowerShot Synthetic");
        var dng = DngEncoder.convert(MediaSource.of(data), RawDecoderOptions.defaults(), DngEncoderOptions.defaults());
        var file = parse(dng);
        assertNotNull(file.sensor.opcodes3);
        var list = ByteBuffer.wrap(file.sensor.opcodes3);
        assertEquals(1, list.getInt());
        assertEquals(1, list.getInt(), "WarpRectilinear");
        var plain = DngEncoder.convert(MediaSource.of(data), RawDecoderOptions.builder().lensCorrections(false).build(),
                DngEncoderOptions.defaults());
        assertNull(parse(plain).sensor.opcodes3);
    }

    @Test
    @DisplayName("the options turn the correction off, and a half-size rendering makes none")
    void options() {
        var data = shot(7, "Canon PowerShot Synthetic");
        var options = RawDecoderOptions.builder().embeddedPreview(false).build();
        var with = ImageOps.toRgba(RawDecoder.decode(MediaSource.of(data), options).picture());
        var without = ImageOps.toRgba(RawDecoder.decode(MediaSource.of(data),
                options.toBuilder().lensCorrections(false).build()).picture());
        assertFalse(java.util.Arrays.equals(with.pixels(), without.pixels()));
        var half = options.toBuilder().minSize(WIDTH / 2 - 8, HEIGHT / 2 - 8).build();
        var halfWith = ImageOps.toRgba(RawDecoder.decode(MediaSource.of(data), half).picture());
        var halfWithout = ImageOps.toRgba(RawDecoder.decode(MediaSource.of(data),
                half.toBuilder().lensCorrections(false).build()).picture());
        assertEquals(WIDTH / 2 - 4, halfWith.width());
        assertArrayEquals(halfWith.pixels(), halfWithout.pixels());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Canon EOS R5", "Canon EOS M50", "Canon PowerShot"})
    @DisplayName("only the PowerShot compacts apply the tag")
    void models(String model) {
        var lens = parse(shot(8, model)).sensor.lens;
        assertEquals(model.contains("PowerShot"), lens != null, model);
    }

    @Test
    @DisplayName("a PowerShot file without the tag or the timed metadata gets no correction")
    void missing() {
        var writer = writer("Canon PowerShot Synthetic");
        writer.ramp();
        assertNull(parse(writer.write()).sensor.lens);
        writer.lensTable = LensShot.table(writer, DISTORTION, RED, BLUE);
        writer.colorData = false;
        assertNull(parse(writer.write()).sensor.lens);
    }

    @Test
    @DisplayName("both images of a dual pixel file take the correction")
    void dualPixel() {
        var writer = writer("Canon PowerShot Synthetic");
        writer.ramp();
        writer.lensTable = LensShot.table(writer, DISTORTION, RED, BLUE);
        writer.dualPixel = writer("Canon PowerShot Synthetic");
        writer.dualPixel.ramp();
        var file = parse(writer.write());
        assertNotNull(file.sensor.lens);
        assertNotNull(file.dualPixel.lens);
    }

    @ParameterizedTest
    @ValueSource(strings = {"cr2/valid-reduced-canon-powershot-s110-raw-4-3.cr2", "cr2/valid-reduced-canon-powershot-g16-raw-4-3.cr2",
            "cr2/valid-reduced-canon-powershot-g7-x-raw-3-2.cr2", "cr2/valid-reduced-canon-powershot-g7-x-mark-ii-raw-3-2.cr2",
            "cr3/valid-crx-powershot-lens.cr3"})
    @DisplayName("the fuzz seeds reach the tables")
    void seeds(String name) throws IOException {
        try (var stream = CanonLensTest.class.getResourceAsStream("/com/github/auties00/smalt/image/raw/fuzz/RawFuzzTestInputs/" + name)) {
            assertNotNull(stream, name);
            assertNotNull(parse(stream.readAllBytes()).sensor.lens, name);
        }
    }

    /**
     * A CR2 file of two lossless JPEG components, with tag {@code 0x4015} as the only tag of its maker note.
     */
    private static RawFile cr2(String model, byte[] tag, int[] samples) {
        var writer = new VendorTiffWriter();
        writer.make = "Canon";
        writer.model = model;
        writer.width = WIDTH;
        writer.height = HEIGHT;
        writer.bits = 14;
        writer.compression = 6;
        writer.strip = Lj92Encoder.encode(samples, WIDTH / 2, HEIGHT, 2, 14, 1, 0, 0);
        writer.rawEntries.add(writer.shorts(0xC640, 0, WIDTH, WIDTH));
        writer.makerNote = offset -> VendorTiffWriter.directory(new ArrayList<>(List.of(VendorTiffWriter.undefined(CanonLens.TAG, tag))),
                offset, writer.order());
        var data = writer.write();
        var source = MediaSource.of(data);
        var file = new RawFile(source, MediaLimits.DEFAULT, RawFormat.CR2);
        Cr2Parser.parse(TiffReader.open(source, 0, MediaLimits.DEFAULT), file);
        return file;
    }

    /**
     * The bytes of an {@code 0x8001} tag of a lens, without chromatic aberration.
     */
    private static byte[] shortTag(double corner) {
        var words = new int[33];
        words[0] = 0x8001;
        words[1] = 66;
        words[17] = 7;
        words[18] = 8192;
        words[20] = 8192;
        for (var knot = 1; knot < 7; knot++) {
            var radius = corner * Math.sqrt(knot / 6.0);
            words[19 + 2 * knot] = (int) Math.round(radius);
            words[20 + 2 * knot] = (int) Math.round(8192 * LensShot.polynomial(DISTORTION, radius / corner));
        }
        var bytes = new byte[66];
        for (var word = 0; word < 33; word++) {
            put16(bytes, word, words[word]);
        }
        return bytes;
    }

    @Test
    @DisplayName("a CR2 file's rendering takes away the lens its maker note records")
    void cr2File() {
        var geometry = writer("Canon PowerShot Synthetic");
        geometry.inset = 0;
        geometry.white = 16383;
        var scene = new LensShot.Scene(9, WIDTH, HEIGHT);
        var samples = LensShot.photosites(scene, geometry, DISTORTION, NONE, NONE);
        var tag = shortTag(Math.hypot(WIDTH / 2.0, HEIGHT / 2.0));
        var file = cr2("Canon PowerShot Synthetic", tag, samples);
        assertNotNull(file.sensor.lens);
        var options = RawDecoderOptions.builder().embeddedPreview(false).build();
        var with = correlationWithScene(Renderer.render(file, options, false), 9);
        var plain = cr2("Canon PowerShot Synthetic", tag, samples);
        var without = correlationWithScene(Renderer.render(plain,
                options.toBuilder().lensCorrections(false).build(), false), 9);
        assertTrue(with > 0.97, "correlation " + with + " with the correction");
        assertTrue(without < 0.5, "correlation " + without + " without the correction");
        assertNull(cr2("Canon EOS 5D Mark III", tag, samples).sensor.lens, "another camera");
    }

    /**
     * The samples whose scene shows little detail in the outer part of the frame, which lowers the correlation either way.
     */
    private static final List<String> LITTLE_DETAIL = List.of("rawpixls-canon-powershot-s100-raw-4-3.cr2",
            "rawpixls-canon-powershot-s110-raw-4-3.cr2");

    private static final List<String> SAMPLES = List.of(
            "rawpixls-canon-powershot-g7-x-mark-iii-3-2.cr3",
            "rawpixls-canon-powershot-g5-x-mark-ii-raw-3-2.cr3",
            "rawpixls-canon-powershot-sx70-hs-raw-3-2-3-2.cr3",
            "rawpixls-canon-powershot-g1-x-raw-4-3.cr2",
            "rawpixls-canon-powershot-g1-x-mark-ii-raw-3-2.cr2",
            "rawpixls-canon-powershot-g1-x-mark-iii-raw-3-2.cr2",
            "rawpixls-canon-powershot-g16-raw-4-3.cr2",
            "rawpixls-canon-powershot-g3-x-raw-3-2.cr2",
            "rawpixls-canon-powershot-g5-x-raw-3-2.cr2",
            "rawpixls-canon-powershot-g7-x-raw-3-2.cr2",
            "rawpixls-canon-powershot-g7-x-mark-ii-raw-3-2.cr2",
            "rawpixls-canon-powershot-g9-x-raw-3-2.cr2",
            "rawpixls-canon-powershot-g9-x-mark-ii-raw-3-2.cr2",
            "rawpixls-canon-powershot-s100-raw-4-3.cr2",
            "rawpixls-canon-powershot-s110-raw-4-3.cr2",
            "rawpixls-canon-powershot-s120-raw-4-3.cr2",
            "rawpixls-canon-powershot-s95-raw-4-3.cr2",
            "rawpixls-canon-powershot-sx60-hs-raw-4-3.cr2");

    @Test
    @DisplayName("the CC0 PowerShot samples render closer to their camera JPEG with the correction")
    void sampleFiles() throws IOException {
        var failures = new ArrayList<String>();
        var options = RawDecoderOptions.builder().embeddedPreview(false).build();
        for (var name : SAMPLES) {
            var path = Conformance.fetchListed(name);
            try (var source = MediaSource.of(FileChannel.open(path, StandardOpenOption.READ))) {
                var file = RawDecoding.open(source, MediaLimits.DEFAULT);
                assertNotNull(file.sensor.lens, name);
                var largest = file.previews.getFirst();
                for (var preview : file.previews) {
                    if (preview.width() > largest.width()) {
                        largest = preview;
                    }
                }
                var camera = ImageOps.toRgba(RawDecoding.previewPicture(file, largest, ImageDecoderOptions.builder().build()));
                var with = ImageOps.toRgba(Renderer.render(file, options, false));
                var without = ImageOps.toRgba(Renderer.render(RawDecoding.open(source, MediaLimits.DEFAULT),
                        options.toBuilder().lensCorrections(false).build(), false));
                var corrected = gridCorrelation(camera, with);
                var plain = gridCorrelation(camera, without);
                if (!(corrected > (LITTLE_DETAIL.contains(name) ? 0.5 : 0.9) && corrected > plain)) {
                    failures.add(name + ": correlation " + corrected + " with, " + plain + " without the correction");
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    private static final int GRID_COLUMNS = 96;
    private static final int GRID_ROWS = 64;

    /**
     * The correlation of the green channels averaged on a grid, for pictures of any size (the PowerShot CR2 files hold a
     * reduced JPEG), in the outer part of the frame, where a lens moves most.
     */
    private static double gridCorrelation(RgbaPicture a, RgbaPicture b) {
        var gridA = grid(a);
        var gridB = grid(b);
        double sa = 0;
        double sb = 0;
        double aa = 0;
        double bb = 0;
        double ab = 0;
        var count = 0;
        for (var row = 0; row < GRID_ROWS; row++) {
            for (var column = 0; column < GRID_COLUMNS; column++) {
                if (Math.hypot(column - GRID_COLUMNS / 2.0, row - GRID_ROWS / 2.0) < 0.6 * Math.hypot(GRID_COLUMNS / 2.0, GRID_ROWS / 2.0)) {
                    continue;
                }
                var va = gridA[row * GRID_COLUMNS + column];
                var vb = gridB[row * GRID_COLUMNS + column];
                sa += va;
                sb += vb;
                aa += va * va;
                bb += vb * vb;
                ab += va * vb;
                count++;
            }
        }
        return (ab - sa * sb / count) / Math.sqrt((aa - sa * sa / count) * (bb - sb * sb / count));
    }

    private static double[] grid(RgbaPicture picture) {
        var sums = new double[GRID_COLUMNS * GRID_ROWS];
        var counts = new int[sums.length];
        var pixels = picture.pixels();
        for (var y = 0; y < picture.height(); y++) {
            var row = y * GRID_ROWS / picture.height();
            for (var x = 0; x < picture.width(); x++) {
                var at = row * GRID_COLUMNS + x * GRID_COLUMNS / picture.width();
                sums[at] += pixels[y * picture.stride() + x * 4 + 1] & 0xFF;
                counts[at]++;
            }
        }
        for (var at = 0; at < sums.length; at++) {
            sums[at] /= counts[at];
        }
        return sums;
    }
}
