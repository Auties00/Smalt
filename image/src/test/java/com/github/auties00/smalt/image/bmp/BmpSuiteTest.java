package com.github.auties00.smalt.image.bmp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.metadata.ImageMetadata;
import com.github.auties00.smalt.image.metadata.ImageMetadataEdit;
import com.github.auties00.smalt.image.metadata.ImageMetadataEditor;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.png.PngDecoder;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decodes BMP Suite 2.8 (public-domain images by Jason Summers, fetched as {@code bmpsuite-2.8.zip} through
 * {@code tools/testdata/vectors.tsv}) and compares every good and questionable file with the reference image its
 * {@code html/bmpsuite.html} names, checks that every bad file decodes or fails cleanly, and edits the ICC profile of
 * every good and questionable file without changing its pixels. The generator source in the archive is GPL and is
 * never read.
 */
class BmpSuiteTest {
    private static Map<String, byte[]> files;

    static synchronized Map<String, byte[]> files() {
        if (files == null) {
            files = unzip(Conformance.fetchListed("bmpsuite-2.8.zip"));
        }
        return files;
    }

    private static Map<String, byte[]> unzip(Path archive) {
        var result = new LinkedHashMap<String, byte[]>();
        try (var input = new ZipInputStream(Files.newInputStream(archive))) {
            for (var entry = input.getNextEntry(); entry != null; entry = input.getNextEntry()) {
                if (!entry.isDirectory()) {
                    var name = entry.getName();
                    result.put(name.substring(name.indexOf('/') + 1), input.readAllBytes());
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return result;
    }

    private static byte[] file(String name) {
        return assertInstanceOf(byte[].class, files().get(name), name);
    }

    private static byte[] reference(String name) {
        var data = file("html/" + name);
        var image = name.endsWith(".jpg") ? JpegDecoder.decode(MediaSource.of(data)) : PngDecoder.decode(MediaSource.of(data));
        return RasterTesting.tight(ImageOps.toRgba(image.picture()));
    }

    // file, reference image, largest difference per sample; the generator writes the samples of rgb32-111110.bmp
    // from its own image of higher precision, which rounds to the 8-bit reference within 1
    static Stream<Arguments> references() {
        return Stream.of(
                Arguments.of("g/pal1.bmp", "pal1.png", 0), Arguments.of("g/pal1wb.bmp", "pal1.png", 0),
                Arguments.of("g/pal1bg.bmp", "pal1bg.png", 0), Arguments.of("q/pal1p1.bmp", "pal1p1.png", 0),
                Arguments.of("q/pal2.bmp", "pal2.png", 0), Arguments.of("q/pal2color.bmp", "pal2color.png", 0),
                Arguments.of("g/pal4.bmp", "pal4.png", 0), Arguments.of("g/pal4gs.bmp", "pal4gs.png", 0),
                Arguments.of("g/pal4rle.bmp", "pal4.png", 0), Arguments.of("q/pal4rletrns.bmp", "pal4rletrns.png", 0),
                Arguments.of("q/pal4rlecut.bmp", "pal4rlecut.png", 0), Arguments.of("g/pal8.bmp", "pal8.png", 0),
                Arguments.of("g/pal8-0.bmp", "pal8.png", 0), Arguments.of("g/pal8gs.bmp", "pal8gs.png", 0),
                Arguments.of("g/pal8rle.bmp", "pal8.png", 0), Arguments.of("q/pal8rletrns.bmp", "pal8rletrns.png", 0),
                Arguments.of("q/pal8rlecut.bmp", "pal8rlecut.png", 0), Arguments.of("g/pal8w126.bmp", "pal8w126.png", 0),
                Arguments.of("g/pal8w125.bmp", "pal8w125.png", 0), Arguments.of("g/pal8w124.bmp", "pal8w124.png", 0),
                Arguments.of("g/pal8topdown.bmp", "pal8.png", 0), Arguments.of("q/pal8offs.bmp", "pal8.png", 0),
                Arguments.of("q/pal8oversizepal.bmp", "pal8.png", 0), Arguments.of("g/pal8nonsquare.bmp", "pal8nonsquare-e.png", 0),
                Arguments.of("g/pal8os2.bmp", "pal8.png", 0), Arguments.of("q/pal8os2-sz.bmp", "pal8.png", 0),
                Arguments.of("q/pal8os2-hs.bmp", "pal8.png", 0), Arguments.of("q/pal8os2sp.bmp", "pal8.png", 0),
                Arguments.of("q/pal8os2v2.bmp", "pal8.png", 0), Arguments.of("q/pal8os2v2-16.bmp", "pal8.png", 0),
                Arguments.of("q/pal8os2v2-sz.bmp", "pal8.png", 0), Arguments.of("q/pal8os2v2-40sz.bmp", "pal8.png", 0),
                Arguments.of("q/rgb24rle24.bmp", "pal8.png", 0), Arguments.of("q/pal1huffmsb.bmp", "pal1.png", 0),
                Arguments.of("g/pal8v4.bmp", "pal8.png", 0), Arguments.of("g/pal8v5.bmp", "pal8.png", 0),
                Arguments.of("g/rgb16.bmp", "rgb16.png", 0), Arguments.of("g/rgb16bfdef.bmp", "rgb16.png", 0),
                Arguments.of("g/rgb16-565.bmp", "rgb16-565.png", 0), Arguments.of("g/rgb16-565pal.bmp", "rgb16-565.png", 0),
                Arguments.of("q/rgb16faketrns.bmp", "rgb16.png", 0), Arguments.of("q/rgb16-231.bmp", "rgb16-231.png", 0),
                Arguments.of("q/rgb16-3103.bmp", "rgb16-3103.png", 0), Arguments.of("q/rgba16-4444.bmp", "rgba16-4444.png", 0),
                Arguments.of("q/rgba16-5551.bmp", "rgba16-5551.png", 0), Arguments.of("q/rgba16-1924.bmp", "rgba16-1924.png", 0),
                Arguments.of("g/rgb24.bmp", "rgb24.png", 0), Arguments.of("g/rgb24pal.bmp", "rgb24.png", 0),
                Arguments.of("q/rgb24largepal.bmp", "rgb24.png", 0), Arguments.of("q/rgb24prof.bmp", "rgb24.png", 0),
                Arguments.of("q/rgb24lprof.bmp", "rgb24.png", 0), Arguments.of("q/rgb24jpeg.bmp", "rgb24.jpg", 0),
                Arguments.of("q/rgb24png.bmp", "rgb24.png", 0), Arguments.of("g/rgb32.bmp", "rgb24.png", 0),
                Arguments.of("g/rgb32bfdef.bmp", "rgb24.png", 0), Arguments.of("g/rgb32bf.bmp", "rgb24.png", 0),
                Arguments.of("q/rgb32h52.bmp", "rgb24.png", 0), Arguments.of("q/rgb32-xbgr.bmp", "rgb24.png", 0),
                Arguments.of("q/rgb32fakealpha.bmp", "fakealpha.png", 0), Arguments.of("q/rgb32-111110.bmp", "rgb24.png", 1),
                Arguments.of("q/rgb32-7187.bmp", "rgb32-7187.png", 0), Arguments.of("q/rgba32-1.bmp", "rgba32.png", 0),
                Arguments.of("q/rgba32-2.bmp", "rgba32.png", 0), Arguments.of("q/rgba32-1010102.bmp", "rgba32-1010102.png", 0),
                Arguments.of("q/rgba32-81284.bmp", "rgba32-81284.png", 0), Arguments.of("q/rgba32-61754.bmp", "rgba32-61754.png", 0),
                Arguments.of("q/rgba32abf.bmp", "rgba32.png", 0), Arguments.of("q/rgba32h56.bmp", "rgba32.png", 0),
                Arguments.of("q/rgba64.bmp", "rgba32.png", 1), Arguments.of("x/ba-bm.bmp", "pal8.png", 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("references")
    @DisplayName("decodes every good and questionable file to its reference image")
    void reference(String name, String reference, int tolerance) {
        var picture = BmpDecoder.decode(MediaSource.of(file(name))).picture();
        assertVisible(reference(reference), RasterTesting.tight(picture), picture.width(), tolerance, name);
    }

    // compares alpha everywhere and color wherever either side is visible: the references store transparent black
    private static void assertVisible(byte[] expected, byte[] actual, int width, int tolerance, String what) {
        assertEquals(expected.length, actual.length, what + ": buffer length");
        for (var i = 0; i < expected.length; i += 4) {
            var visible = expected[i + 3] != 0 || actual[i + 3] != 0;
            for (var c = visible ? 0 : 3; c < 4; c++) {
                var difference = Math.abs((expected[i + c] & 0xFF) - (actual[i + c] & 0xFF));
                if (difference > tolerance) {
                    fail(what + ": pixel (" + i / 4 % width + "," + i / 4 / width + ") channel " + c + " expected "
                            + (expected[i + c] & 0xFF) + " got " + (actual[i + c] & 0xFF));
                }
            }
        }
    }

    @Test
    @DisplayName("applies the embedded profile of rgb24prof2.bmp, which swaps red and green back")
    void swappedProfile() {
        var data = file("q/rgb24prof2.bmp");
        var plain = BmpDecoder.decode(MediaSource.of(data));
        assertTrue(plain.iccProfile().isPresent());
        assertTrue(plain.colorProfile().isPresent());
        var converted = BmpDecoder.decode(MediaSource.of(data), ImageDecoderOptions.builder().convertToSrgb(true).build());
        var expected = reference("rgb24.png");
        var actual = RasterTesting.tight(converted.picture());
        var worst = 0;
        for (var i = 0; i < expected.length; i++) {
            worst = Math.max(worst, Math.abs((expected[i] & 0xFF) - (actual[i] & 0xFF)));
        }
        // LittleCMS (through Pillow 12.0) converting with the same profile also differs from the reference by 9
        assertTrue(worst <= 9, "largest difference " + worst);
        var stored = RasterTesting.tight(plain.picture());
        assertNotEquals(0, Math.abs((stored[0] & 0xFF) - (expected[0] & 0xFF)) + Math.abs((stored[1] & 0xFF) - (expected[1] & 0xFF))
                + Math.abs((stored[4 * 40] & 0xFF) - (expected[4 * 40] & 0xFF)), "the stored pixels are swapped");
    }

    @Test
    @DisplayName("ignores the linked profile of rgb24lprof.bmp and reports the calibrated space of pal8v4.bmp")
    void colorSpaces() {
        var linked = BmpDecoder.decode(MediaSource.of(file("q/rgb24lprof.bmp")));
        assertTrue(linked.iccProfile().isEmpty());
        assertTrue(linked.colorProfile().isEmpty());
        var calibrated = BmpDecoder.decode(MediaSource.of(file("g/pal8v4.bmp")));
        assertTrue(calibrated.iccProfile().isEmpty());
        var profile = calibrated.colorProfile().orElseThrow();
        assertTrue(profile.isMatrixShaper());
        var v5 = BmpDecoder.decode(MediaSource.of(file("g/pal8v5.bmp")));
        assertTrue(v5.colorProfile().isEmpty(), "an sRGB color space declares no profile");
    }

    @Test
    @DisplayName("keeps 64-bit and wide-mask pixels at 16 bits on request")
    void highBitDepth() {
        var options = ImageDecoderOptions.builder().highBitDepth(true).build();
        var wide = assertInstanceOf(Rgba16Picture.class, BmpDecoder.decode(MediaSource.of(file("q/rgba64.bmp")), options).picture());
        var narrow = (RgbaPicture) BmpDecoder.decode(MediaSource.of(file("q/rgba64.bmp"))).picture();
        for (var y = 0; y < wide.height(); y++) {
            for (var x = 0; x < wide.width() * 4; x++) {
                var high = wide.pixels()[y * wide.stride() + x] & 0xFFFF;
                var low = narrow.pixels()[y * narrow.stride() + x] & 0xFF;
                assertTrue(Math.abs((high * 255 + 32767) / 65535 - low) <= 1, "sample " + x + "," + y);
            }
        }
        var masked = assertInstanceOf(Rgba16Picture.class,
                BmpDecoder.decode(MediaSource.of(file("q/rgba32-1010102.bmp")), options).picture());
        var reduced = RasterTesting.tight(BmpDecoder.decode(MediaSource.of(file("q/rgba32-1010102.bmp"))).picture());
        for (var i = 0; i < reduced.length; i++) {
            var high = masked.pixels()[i] & 0xFFFF;
            assertTrue(Math.abs((high * 255 + 32767) / 65535 - (reduced[i] & 0xFF)) <= 1, "sample " + i);
            if (i % 4 == 3) {
                assertEquals(0, high % 21845, "2-bit alpha scales to multiples of 65535 / 3");
            }
        }
        assertInstanceOf(RgbaPicture.class, BmpDecoder.decode(MediaSource.of(file("g/rgb24.bmp")), options).picture());
    }

    static Stream<String> editable() {
        return files().keySet().stream().filter(name -> !name.startsWith("b/") && !name.startsWith("html/")
                && name.endsWith(".bmp") && !name.equals("q/pal1huffmsb.bmp") && !name.equals("q/rgb24rle24.bmp")
                && !name.startsWith("x/"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("editable")
    @DisplayName("replaces and removes the ICC profile of every good and questionable file, keeping its pixels")
    void profileEdits(String name) {
        var data = file(name);
        var pixels = RasterTesting.tight(BmpDecoder.decode(MediaSource.of(data)).picture());
        var icc = ColorProfile.displayP3().toByteArray();
        var replaced = ImageMetadataEditor.apply(data, ImageMetadataEdit.builder().colorProfile(ColorProfile.displayP3())
                .build());
        assertArrayEquals(icc, ImageMetadata.read(replaced).iccProfile().orElseThrow(), name);
        var decoded = BmpDecoder.decode(MediaSource.of(replaced));
        assertArrayEquals(pixels, RasterTesting.tight(decoded.picture()), name + ": replaced");
        assertArrayEquals(icc, decoded.iccProfile().orElseThrow(), name);
        var removed = ImageMetadataEditor.apply(replaced, ImageMetadataEdit.builder().removeIccProfile().build());
        assertTrue(ImageMetadata.read(removed).iccProfile().isEmpty(), name);
        assertEquals(replaced.length - icc.length, removed.length, name + ": the profile bytes are gone");
        assertArrayEquals(pixels, RasterTesting.tight(BmpDecoder.decode(MediaSource.of(removed)).picture()),
                name + ": removed");
    }

    @Test
    @DisplayName("refuses to edit the profile of the OS/2 Huffman, RLE24 and bitmap array files")
    void profileEditsRefused() {
        for (var name : new String[]{"q/pal1huffmsb.bmp", "q/rgb24rle24.bmp", "x/ba-bm.bmp"}) {
            assertThrows(MediaException.Unsupported.class, () -> ImageMetadataEditor.apply(file(name),
                    ImageMetadataEdit.builder().colorProfile(ColorProfile.displayP3()).build()), name);
        }
    }

    static Stream<String> bad() {
        return files().keySet().stream().filter(name -> name.startsWith("b/"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bad")
    @DisplayName("decodes every bad file or rejects it with a MediaException")
    void bad(String name) {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            for (var step = 0; step < 2; step++) {
                try {
                    if (step == 0) {
                        BmpDecoder.probe(MediaSource.of(file(name)));
                    } else {
                        Picture picture = BmpDecoder.decode(MediaSource.of(file(name))).picture();
                        assertTrue(picture.width() > 0);
                    }
                } catch (MediaException expected) {
                    // a clean rejection
                }
            }
        });
    }
}
