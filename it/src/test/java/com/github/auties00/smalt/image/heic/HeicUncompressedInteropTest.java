package com.github.auties00.smalt.image.heic;

import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.UnciWriter;
import com.github.auties00.smalt.testkit.UnciWriter.Component;
import com.github.auties00.smalt.testkit.UnciWriter.Image;
import com.github.auties00.smalt.testkit.UnciWriter.Layout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static com.github.auties00.smalt.testkit.UnciWriter.A;
import static com.github.auties00.smalt.testkit.UnciWriter.B;
import static com.github.auties00.smalt.testkit.UnciWriter.CB;
import static com.github.auties00.smalt.testkit.UnciWriter.CR;
import static com.github.auties00.smalt.testkit.UnciWriter.G;
import static com.github.auties00.smalt.testkit.UnciWriter.MONO;
import static com.github.auties00.smalt.testkit.UnciWriter.R;
import static com.github.auties00.smalt.testkit.UnciWriter.Y;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Compares {@link HeicDecoder}'s decode of uncompressed HEIF items ({@code unci}, ISO/IEC 23001-17) that the test
 * kit's {@link UnciWriter} lays out with the PNG files libheif's {@code heif-dec} decoded the same files to, which are
 * committed fixtures (with their provenance in {@code fixtures.tsv}) because the package managers' libheif builds
 * leave out the uncompressed codec: RGB and monochrome items sample for sample, deeper ones within one 8-bit step
 * (the readers narrow them differently), and Y'CbCr ones within a PSNR bound, since each reader converts to RGB with
 * its own arithmetic. The test fails when the writer's output no longer matches the file libheif decoded. The samples
 * are the layouts libheif 1.23.7 decodes; it rejects aligned components in pixel interleaving, row interleaving of
 * 4:2:0, multi-Y interleaving, blocks above 8 bytes, palettes, signed, floating-point and wider integer components,
 * CMYK and filter arrays, which core's {@code HeifUncompressedFormatsTest} checks against exact references instead.
 */
@DisplayName("uncompressed HEIF items read by libheif")
class HeicUncompressedInteropTest {
    /** The readers narrow deeper samples to 8 bits with their own rounding. */
    private static final Tolerance NARROWED = Tolerance.offByOne();

    /** The readers' Y'CbCr to RGB conversion differs in rounding and chroma upsampling. */
    private static final Tolerance CONVERTED = Tolerance.psnr(35);

    private static final Path DIRECTORY = Scratch.directory("heic-unci-interop");

    /** The resource directory of the PNG files libheif decoded the samples to. */
    private static final String FIXTURES = "/com/github/auties00/smalt/image/heic/unci-libheif/";

    /** The provenance header of {@code fixtures.tsv}. */
    private static final String HEADER = """
            # PNG files libheif's heif-dec decoded the HeicUncompressedInteropTest samples to, written by the test with
            # -Dsmalt.test.libheifUnci=<heif-dec>. heif-dec is libheif 1.23.7 (LGPL, run as a black box; tarball
            # https://github.com/strukturag/libheif/releases/download/v1.23.7/libheif-1.23.7.tar.gz, SHA-256
            # f7483e818503ea6224c8863e4a56ac02c7089021a98472c811c12aa19faf2475) built with MSYS2 UCRT64 GCC and CMake:
            # -DWITH_UNCOMPRESSED_CODEC=ON, every other codec off, -DBUILD_SHARED_LIBS=OFF, target heif-dec.
            # sample, SHA-256 of the HEIF file, SHA-256 of the PNG file
            """;

    /** The layouts both readers decode. */
    enum Sample {
        /** 8-bit RGB, pixel interleaving. */
        RGB_PIXEL(Layout.of(0, 1), List.of(Component.of(R, 8), Component.of(G, 8), Component.of(B, 8)), null),
        /** 8-bit RGBA, component interleaving with rows padded to 16 bytes. */
        RGBA_PLANAR(Layout.of(0, 0).rowAlign(16), List.of(Component.of(R, 8), Component.of(G, 8), Component.of(B, 8),
                Component.of(A, 8)), null),
        /** 8-bit RGB, row interleaving. */
        RGB_ROWS(Layout.of(0, 3), List.of(Component.of(R, 8), Component.of(G, 8), Component.of(B, 8)), null),
        /** 8-bit gray. */
        GRAY(Layout.of(0, 0), List.of(Component.of(MONO, 8)), null),
        /** 16-bit big-endian gray. */
        GRAY_16(Layout.of(0, 0), List.of(Component.of(MONO, 16)), null),
        /** 8-bit RGB in 2x2 tiles. */
        RGB_TILES(Layout.of(0, 1).tiles(2, 2, 0), List.of(Component.of(R, 8), Component.of(G, 8), Component.of(B, 8)),
                null),
        /** 8-bit Y'CbCr 4:2:0, component interleaving. */
        YCBCR_420(Layout.of(2, 0), List.of(Component.of(Y, 8), Component.of(CB, 8), Component.of(CR, 8)), null),
        /** 8-bit Y'CbCr 4:2:2, component interleaving. */
        YCBCR_422(Layout.of(1, 0), List.of(Component.of(Y, 8), Component.of(CB, 8), Component.of(CR, 8)), null),
        /** 8-bit Y'CbCr 4:4:4, pixel interleaving. */
        YCBCR_444(Layout.of(0, 1), List.of(Component.of(Y, 8), Component.of(CB, 8), Component.of(CR, 8)), null),
        /** 8-bit RGB compressed as one deflate stream of stored blocks. */
        RGB_DEFLATE(Layout.of(0, 1), List.of(Component.of(R, 8), Component.of(G, 8), Component.of(B, 8)), "defl"),
        /** 8-bit RGB compressed as one zlib stream of stored blocks. */
        RGB_ZLIB(Layout.of(0, 1), List.of(Component.of(R, 8), Component.of(G, 8), Component.of(B, 8)), "zlib"),
        /** 8-bit RGBA compressed in units of rows indexed by icef. */
        RGBA_ROW_UNITS(Layout.of(0, 1), List.of(Component.of(R, 8), Component.of(G, 8), Component.of(B, 8),
                Component.of(A, 8)), "rows"),
        /** 8-bit RGB compressed as one Brotli stream (stored meta-blocks). */
        RGB_BROTLI(Layout.of(0, 1), List.of(Component.of(R, 8), Component.of(G, 8), Component.of(B, 8)), "brot");

        final Layout layout;
        final List<Component> components;
        final String compression;

        Sample(Layout layout, List<Component> components, String compression) {
            this.layout = layout;
            this.components = components;
            this.compression = compression;
        }

        static final int WIDTH = 48;
        static final int HEIGHT = 32;

        Image image() {
            var planes = UnciWriter.planes();
            for (var component : components) {
                var chroma = UnciWriter.isChroma(component.type());
                var w = chroma ? (WIDTH + layout.sx() - 1) / layout.sx() : WIDTH;
                var h = chroma ? (HEIGHT + layout.sy() - 1) / layout.sy() : HEIGHT;
                var max = (1 << component.depth()) - 1;
                var values = new BigInteger[w * h];
                for (var y = 0; y < h; y++) {
                    for (var x = 0; x < w; x++) {
                        var v = switch (component.type()) {
                            case R, Y, MONO -> (x * max + (w - 1) / 2) / Math.max(1, w - 1);
                            case G, CB -> (y * max + (h - 1) / 2) / Math.max(1, h - 1);
                            case B, CR -> max / 2 + (int) (max / 3.0 * StrictMath.sin((x + y) / 7.0));
                            default -> max - x * max / Math.max(1, w - 1) / 2;
                        };
                        values[y * w + x] = BigInteger.valueOf(v);
                    }
                }
                planes.put(component.type(), values);
            }
            return new Image(WIDTH, HEIGHT, components, layout, planes);
        }

        byte[] file() {
            var image = image();
            var definition = UnciWriter.definition(components);
            var properties = new ArrayList<byte[]>();
            properties.add(UnciWriter.uncC(components, layout, definition));
            properties.add(UnciWriter.cmpd(definition));
            var data = UnciWriter.data(image);
            if ("brot".equals(compression)) {
                properties.add(UnciWriter.cmpC("brot", 0));
                data = UnciWriter.storedBrotli(data);
            } else if (compression != null && !compression.equals("rows")) {
                properties.add(UnciWriter.cmpC(compression, 0));
                data = UnciWriter.storedDeflate(data, compression.equals("zlib"));
            } else if (compression != null) {
                var row = data.length / HEIGHT;
                var payload = new ByteArrayOutputStream();
                var offsets = new long[HEIGHT];
                var sizes = new long[HEIGHT];
                for (var y = 0; y < HEIGHT; y++) {
                    var unit = UnciWriter.storedDeflate(java.util.Arrays.copyOfRange(data, y * row, (y + 1) * row), true);
                    offsets[y] = payload.size();
                    sizes[y] = unit.length;
                    payload.writeBytes(unit);
                }
                properties.add(UnciWriter.cmpC("zlib", 3));
                properties.add(UnciWriter.icef(3, 3, offsets, sizes));
                data = payload.toByteArray();
            }
            return UnciWriter.file(WIDTH, HEIGHT, data, properties.toArray(byte[][]::new));
        }

        boolean ycbcr() {
            return components.stream().anyMatch(c -> c.type() == Y);
        }

        boolean deep() {
            return components.stream().anyMatch(c -> c.depth() > 8);
        }
    }

    /** Returns the tight 8-bit RGBA samples of a picture. */
    private static byte[] rgbaOf(Picture picture) {
        var rgba = ImageOps.toRgba(picture);
        var out = new byte[rgba.width() * rgba.height() * 4];
        for (var y = 0; y < rgba.height(); y++) {
            System.arraycopy(rgba.pixels(), y * rgba.stride(), out, y * rgba.width() * 4, rgba.width() * 4);
        }
        return out;
    }

    /** The fixture rows: the SHA-256 of each sample's file and of the PNG libheif decoded it to. */
    private static Map<String, String[]> fixtures() {
        var rows = new TreeMap<String, String[]>();
        try (var in = HeicUncompressedInteropTest.class.getResourceAsStream(FIXTURES + "fixtures.tsv")) {
            if (in == null) {
                return rows;
            }
            for (var line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    var fields = line.strip().split("\t");
                    rows.put(fields[0], fields);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return rows;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /**
     * Decodes a sample with the libheif {@code heif-dec} that {@code smalt.test.libheifUnci} names and writes the PNG
     * into the module's test resources, with its row in {@code fixtures.tsv}.
     */
    private static void regenerate(Sample sample, byte[] file, String decoder) throws IOException {
        var name = sample.name().toLowerCase(Locale.ROOT);
        var input = Files.write(DIRECTORY.resolve(name + ".heif"), file);
        var directory = Path.of("src", "test", "resources").resolve(FIXTURES.substring(1));
        Files.createDirectories(directory);
        var png = directory.resolve(name + ".png");
        var result = Oracle.execute(Path.of(decoder), Duration.ofMinutes(1), null, List.of(input.toString(),
                png.toString()));
        assertEquals(0, result.exitCode(), result.stderr());
        var rows = new TreeMap<String, String[]>();
        var tsv = directory.resolve("fixtures.tsv");
        if (Files.isRegularFile(tsv)) {
            for (var line : Files.readAllLines(tsv)) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    var fields = line.split("\t");
                    rows.put(fields[0], fields);
                }
            }
        }
        rows.put(name, new String[]{name, sha256(file), sha256(Files.readAllBytes(png))});
        var out = new StringBuilder(HEADER);
        for (var row : rows.values()) {
            out.append(String.join("\t", row)).append('\n');
        }
        Files.writeString(tsv, out);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Sample.class)
    @DisplayName("libheif's heif-dec decoded the item to Smalt's pixels")
    void libheif(Sample sample) throws IOException {
        var file = sample.file();
        var name = sample.name().toLowerCase(Locale.ROOT);
        var decoder = System.getProperty("smalt.test.libheifUnci", "");
        if (!decoder.isBlank()) {
            regenerate(sample, file, decoder);
            return;
        }
        var row = fixtures().get(name);
        assertNotNull(row, "no libheif fixture for " + name + ": generate it with -Dsmalt.test.libheifUnci");
        assertEquals(row[1], sha256(file), "the writer's output changed since libheif decoded " + name
                + ": regenerate the fixture with -Dsmalt.test.libheifUnci");
        byte[] png;
        try (var in = HeicUncompressedInteropTest.class.getResourceAsStream(FIXTURES + name + ".png")) {
            assertNotNull(in, name + ".png");
            png = in.readAllBytes();
        }
        assertEquals(row[2], sha256(png), name + ".png");
        var reference = Images.decode(MediaSource.of(png)).picture();
        var actual = HeicDecoder.decode(MediaSource.of(file)).picture();
        assertEquals(actual.width(), reference.width(), "width");
        assertEquals(actual.height(), reference.height(), "height");
        var tolerance = sample.ycbcr() ? CONVERTED : sample.deep() ? NARROWED : Tolerance.exact();
        tolerance.assertWithin(sample.name(), rgbaOf(reference), rgbaOf(actual));
    }
}
