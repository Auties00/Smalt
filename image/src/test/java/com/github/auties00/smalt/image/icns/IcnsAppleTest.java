package com.github.auties00.smalt.image.icns;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.png.PngDecoder;
import com.github.auties00.smalt.image.png.PngEncoder;
import com.github.auties00.smalt.image.png.PngEncoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the ICNS codec against macOS as a black box: {@code sips} converts every element type Smalt writes to PNG,
 * {@code iconutil} builds icon families from icon sets of PNG files for Smalt to read, and turns Smalt's families back
 * into icon sets. Core Graphics premultiplies alpha, so the color of a partly transparent pixel may differ by the
 * rounding of that round trip; opaque pixels and every alpha value compare exactly.
 */
@DisplayName("ICNS against macOS")
class IcnsAppleTest {
    private Path directory;

    private Path sips;

    private Path iconutil;

    @BeforeEach
    void requireMac(TestInfo info) throws IOException {
        var os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Skip.unless(os.startsWith("mac"), "platform:macos", "sips and iconutil are part of macOS");
        sips = Oracle.find("sips").orElseThrow(() -> new AssertionError("macOS without sips"));
        iconutil = Oracle.find("iconutil").orElseThrow(() -> new AssertionError("macOS without iconutil"));
        directory = Files.createDirectories(Path.of("target", "media-fixtures", "icns-apple",
                info.getTestMethod().orElseThrow().getName() + info.getDisplayName().hashCode()));
    }

    static Stream<Arguments> elements() {
        var arguments = new ArrayList<Arguments>();
        for (var encoding : IcnsEncoderOptions.Encoding.values()) {
            for (var size : new int[]{16, 32, 48, 128, 256}) {
                arguments.add(Arguments.of(encoding, size));
            }
        }
        return arguments.stream();
    }

    private static void assertClose(byte[] expected, byte[] actual, int width, String what) {
        assertClose(expected, actual, width, what, false);
    }

    private static void assertClose(byte[] expected, byte[] actual, int width, String what, boolean opaqueColor) {
        assertEquals(expected.length, actual.length, what + ": buffer length");
        for (var i = 0; i < expected.length; i += 4) {
            var alpha = expected[i + 3] & 0xFF;
            assertEquals(alpha, actual[i + 3] & 0xFF, what + ": alpha of pixel " + i / 4 % width + "," + i / 4 / width);
            if (alpha == 0 || (opaqueColor && alpha != 255)) {
                continue;
            }
            // unpremultiplying a premultiplied 8-bit value is off by up to 255 / alpha
            var tolerance = alpha == 255 ? 0 : (255 + alpha - 1) / alpha;
            for (var c = 0; c < 3; c++) {
                var difference = Math.abs((expected[i + c] & 0xFF) - (actual[i + c] & 0xFF));
                assertTrue(difference <= tolerance, what + ": channel " + c + " of pixel " + i / 4 % width + ","
                        + i / 4 / width + " differs by " + difference);
            }
        }
    }

    private void run(Path executable, List<String> arguments) {
        var result = Oracle.execute(executable, Duration.ofMinutes(2), null, arguments);
        assertEquals(0, result.exitCode(), () -> executable + " " + arguments + ": " + new String(result.stderr()));
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("elements")
    @DisplayName("sips reads every element type Smalt writes")
    void sipsReads(IcnsEncoderOptions.Encoding encoding, int size) throws IOException {
        var file = directory.resolve(encoding + "-" + size + ".icns");
        Files.write(file, IcnsEncoder.encode(IcnsCodecTest.picture(size, size),
                IcnsEncoderOptions.builder().encoding(encoding).build()));
        var png = directory.resolve(encoding + "-" + size + ".png");
        run(sips, List.of("-s", "format", "png", file.toString(), "--out", png.toString()));
        var decoded = RasterTesting.tight(PngDecoder.decode(MediaSource.of(Files.readAllBytes(png)),
                ImageDecoderOptions.defaults()).picture());
        assertClose(IcnsCodecTest.pattern(size, size), decoded, size, "sips " + encoding + " " + size);
    }

    @Test
    @DisplayName("Smalt reads the icon families iconutil writes")
    void iconutilWrites() throws IOException {
        var set = Files.createDirectories(directory.resolve("app.iconset"));
        var sizes = new ArrayList<Integer>();
        for (var point : new int[]{16, 32, 128, 256, 512}) {
            for (var scale = 1; scale <= 2; scale++) {
                var size = point * scale;
                var name = "icon_" + point + "x" + point + (scale == 2 ? "@2x" : "") + ".png";
                Files.write(set.resolve(name), PngEncoder.encode(IcnsCodecTest.picture(size, size),
                        PngEncoderOptions.defaults()));
                sizes.add(size);
            }
        }
        var icns = directory.resolve("app.icns");
        run(iconutil, List.of("-c", "icns", set.toString(), "-o", icns.toString()));
        var file = Files.readAllBytes(icns);
        var entries = IcnsDecoder.entries(MediaSource.of(file));
        assertFalse(entries.isEmpty());
        for (var entry : entries) {
            assertTrue(sizes.contains(entry.width()), entry.toString());
            var decoded = RasterTesting.tight(IcnsDecoder.decode(MediaSource.of(file), entry.index(),
                    ImageDecoderOptions.defaults()).picture());
            assertClose(IcnsCodecTest.pattern(entry.width(), entry.width()), decoded, entry.width(),
                    "iconutil " + entry.type());
        }
        assertEquals(1024, IcnsDecoder.probe(MediaSource.of(file)).width());
    }

    @Test
    @DisplayName("iconutil turns Smalt's icon families back into the pictures written")
    void iconutilReads() throws IOException {
        for (var encoding : IcnsEncoderOptions.Encoding.values()) {
            var pictures = new ArrayList<com.github.auties00.smalt.image.RgbaPicture>();
            for (var size : new int[]{16, 32, 64, 128, 256, 512, 1024}) {
                pictures.add(IcnsCodecTest.picture(size, size));
            }
            var icns = directory.resolve(encoding + ".icns");
            Files.write(icns, IcnsEncoder.encode(pictures, IcnsEncoderOptions.builder().encoding(encoding).build()));
            var set = directory.resolve(encoding + ".iconset");
            run(iconutil, List.of("-c", "iconset", icns.toString(), "-o", set.toString()));
            try (var files = Files.list(set)) {
                var written = files.toList();
                assertFalse(written.isEmpty(), encoding.toString());
                for (var png : written) {
                    var image = PngDecoder.decode(MediaSource.of(Files.readAllBytes(png)),
                            ImageDecoderOptions.defaults());
                    var size = image.picture().width();
                    // iconutil exports the modern elements only and scales the others' sizes from them; sips
                    // reads the legacy ones (icp4, icp5, is32, il32, it32) in sipsReads
                    var legacy = switch (encoding) {
                        case AUTO -> false;
                        case PNG -> size <= 32;
                        case RLE -> size <= 32 || size == 128;
                    };
                    if (legacy) {
                        continue;
                    }
                    // iconutil exports partly transparent pixels of the ARGB elements with colors other than those
                    // sips reads from the same elements: only their alpha is compared
                    assertClose(IcnsCodecTest.pattern(size, size), RasterTesting.tight(image.picture()), size,
                            "iconutil " + encoding + " " + png.getFileName(),
                            encoding == IcnsEncoderOptions.Encoding.AUTO && size <= 32);
                }
            }
        }
    }
}
