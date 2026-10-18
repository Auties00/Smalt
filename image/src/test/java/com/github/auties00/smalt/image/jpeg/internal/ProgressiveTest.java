package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoder;
import com.github.auties00.smalt.image.jpeg.JpegEncoderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Re-encodes decoded coefficients as progressive streams with many scan scripts (spectral
 * selection, successive approximation, both, restart intervals, per-component orders) using the
 * test-side port of libjpeg's progressive encoder, and checks that decoding gives back exactly the
 * same coefficients and pixels; also checks that the port reproduces IJG's testimgp.jpg byte for
 * byte, and that truncated progressive streams decode what they hold.
 */
class ProgressiveTest {
    private static byte[] ppmToJpeg(byte[] ppm, ChromaFormat format) {
        var parts = new String(ppm, 0, 20, java.nio.charset.StandardCharsets.US_ASCII).split("\\s+");
        var width = Integer.parseInt(parts[1]);
        var height = Integer.parseInt(parts[2]);
        var offset = ppm.length - width * height * 3;
        var picture = RgbaPicture.allocate(width, height, false);
        for (var i = 0; i < width * height; i++) {
            picture.pixels()[4 * i] = ppm[offset + 3 * i];
            picture.pixels()[4 * i + 1] = ppm[offset + 3 * i + 1];
            picture.pixels()[4 * i + 2] = ppm[offset + 3 * i + 2];
            picture.pixels()[4 * i + 3] = (byte) 255;
        }
        return JpegEncoder.encode(picture, JpegEncoderOptions.builder().quality(75).chromaFormat(format)
                .optimizeHuffman(false).build());
    }

    private static byte[] vector(String name, String url, String sha256) {
        try {
            return Files.readAllBytes(Conformance.fetch(name, url, sha256));
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static byte[] testorigPpm() {
        return vector("ljt-testorig.ppm", "https://raw.githubusercontent.com/libjpeg-turbo/libjpeg-turbo/3.1.2/testimages/testorig.ppm",
                "4afe49cb62ba87be1a958d7fd29b822a2ba1a0e966d1136f616ee5353691a002");
    }

    private static JpegCoefficients coefficients(byte[] jpeg) {
        return new JpegReader(jpeg, 0, jpeg.length, MediaLimits.DEFAULT).decodeCoefficients();
    }

    /**
     * Compares the coefficients of the blocks inside each component; blocks that only pad the MCU
     * grid are not coded by non-interleaved scans, so they may legitimately differ.
     */
    private static void assertSameCoefficients(JpegCoefficients expected, JpegCoefficients actual) {
        for (var c = 0; c < expected.frame().components.length; c++) {
            var component = expected.frame().components[c];
            for (var by = 0; by < component.blocksHigh; by++) {
                for (var bx = 0; bx < component.blocksWide; bx++) {
                    var base = (by * component.paddedBlocksWide + bx) * 64;
                    assertArrayEquals(java.util.Arrays.copyOfRange(expected.coefficients(c), base, base + 64),
                            java.util.Arrays.copyOfRange(actual.coefficients(c), base, base + 64),
                            "component " + c + " block " + bx + "," + by);
                }
            }
        }
    }

    static Stream<Arguments> scripts() {
        var cases = new ArrayList<Arguments>();
        for (var format : List.of(ChromaFormat.YUV420, ChromaFormat.YUV422, ChromaFormat.YUV444, ChromaFormat.MONOCHROME)) {
            var count = format.hasChroma() ? 3 : 1;
            cases.add(Arguments.of(format, "libjpeg default", TestJpegWriter.simpleProgression(count, format.hasChroma()), 0));
            cases.add(Arguments.of(format, "libjpeg default with restarts", TestJpegWriter.simpleProgression(count, format.hasChroma()), 7));
            var spectral = new ArrayList<TestJpegWriter.Scan>();
            spectral.add(new TestJpegWriter.Scan(all(count), 0, 0, 0, 0));
            for (var c = count - 1; c >= 0; c--) {
                spectral.add(TestJpegWriter.Scan.of(1, 5, 0, 0, c));
                spectral.add(TestJpegWriter.Scan.of(6, 20, 0, 0, c));
                spectral.add(TestJpegWriter.Scan.of(21, 63, 0, 0, c));
            }
            cases.add(Arguments.of(format, "spectral selection only", spectral, 0));
            var approximation = new ArrayList<TestJpegWriter.Scan>();
            approximation.add(new TestJpegWriter.Scan(all(count), 0, 0, 0, 3));
            for (var c = 0; c < count; c++) {
                approximation.add(TestJpegWriter.Scan.of(1, 63, 0, 4, c));
            }
            for (var al = 2; al >= 0; al--) {
                approximation.add(new TestJpegWriter.Scan(all(count), 0, 0, al + 1, al));
            }
            for (var al = 3; al >= 0; al--) {
                for (var c = 0; c < count; c++) {
                    approximation.add(TestJpegWriter.Scan.of(1, 63, al + 1, al, c));
                }
            }
            cases.add(Arguments.of(format, "successive approximation, four refinements", approximation, 5));
            var separate = new ArrayList<TestJpegWriter.Scan>();
            for (var c = count - 1; c >= 0; c--) {
                separate.add(TestJpegWriter.Scan.of(0, 0, 0, 1, c));
            }
            for (var c = 0; c < count; c++) {
                separate.add(TestJpegWriter.Scan.of(1, 1, 0, 0, c));
                separate.add(TestJpegWriter.Scan.of(2, 63, 0, 1, c));
                separate.add(TestJpegWriter.Scan.of(0, 0, 1, 0, c));
                separate.add(TestJpegWriter.Scan.of(2, 63, 1, 0, c));
            }
            cases.add(Arguments.of(format, "non-interleaved DC in reverse order, mixed bands", separate, 0));
        }
        return cases.stream();
    }

    private static int[] all(int count) {
        var components = new int[count];
        for (var i = 0; i < count; i++) {
            components[i] = i;
        }
        return components;
    }

    @ParameterizedTest(name = "{0} {1} restart={3}")
    @MethodSource("scripts")
    @DisplayName("decodes re-encoded progressive streams to the baseline coefficients and pixels")
    void roundTrip(ChromaFormat format, String name, List<TestJpegWriter.Scan> script, int restart) {
        var baseline = ppmToJpeg(testorigPpm(), format);
        var original = coefficients(baseline);
        var writer = TestJpegWriter.of(original);
        writer.restartInterval = restart;
        var progressive = writer.progressive(script);
        assertSameCoefficients(original, coefficients(progressive));
        var expected = (YuvPicture) JpegDecoder.decode(MediaSource.of(baseline), ImageDecoderOptions.defaults()).picture();
        var actual = (YuvPicture) JpegDecoder.decode(MediaSource.of(progressive), ImageDecoderOptions.defaults()).picture();
        assertArrayEquals(planar(expected), planar(actual));
        for (var scale : new int[]{2, 4, 8}) {
            var options = ImageDecoderOptions.ofMinimumSize(expected.width() / scale, expected.height() / scale);
            var reducedBaseline = (YuvPicture) JpegDecoder.decode(MediaSource.of(baseline), options).picture();
            var reducedProgressive = (YuvPicture) JpegDecoder.decode(MediaSource.of(progressive), options).picture();
            assertArrayEquals(planar(reducedBaseline), planar(reducedProgressive), "scale 1/" + scale);
        }
    }

    @Test
    @DisplayName("reproduces IJG's testimgp.jpg (cjpeg -dct int -progressive -optimize) byte for byte")
    void testimgp() {
        var ppm = vector("ijg6b-testimg.ppm", "https://raw.githubusercontent.com/libjpeg-turbo/libjpeg-turbo/jpeg-6b/testimg.ppm",
                "b64a47d529e289e5570648db9f0385d9b240448724cdde7716fa0139b121d491");
        var expected = vector("ijg6b-testimgp.jpg", "https://raw.githubusercontent.com/libjpeg-turbo/libjpeg-turbo/jpeg-6b/testimgp.jpg",
                "e9486e697b7082a324fe2812310303e13b0ddf67b073eacb4b9d8a53e50b7ea7");
        var writer = TestJpegWriter.of(coefficients(ppmToJpeg(ppm, ChromaFormat.YUV420)));
        var actual = writer.progressive(TestJpegWriter.simpleProgression(3, true));
        try {
            java.nio.file.Files.write(java.nio.file.Path.of("target/testimgp-ours.jpg"), actual);
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
        assertArrayEquals(expected, actual);
    }

    @Test
    @DisplayName("decodes a progressive stream cut after each scan to the coefficients of its first scans")
    void incompleteScans() {
        var original = coefficients(ppmToJpeg(testorigPpm(), ChromaFormat.YUV420));
        var script = TestJpegWriter.simpleProgression(3, true);
        var full = TestJpegWriter.of(original).progressive(script);
        var sosPositions = new ArrayList<Integer>();
        for (var i = 2; i + 1 < full.length; i++) {
            if (full[i] == (byte) 0xFF && full[i + 1] == (byte) 0xDA) {
                sosPositions.add(i);
            }
        }
        assertEquals(script.size(), sosPositions.size());
        for (var k = 1; k < script.size(); k++) {
            var cut = sosPositions.get(k);
            var truncated = java.util.Arrays.copyOf(full, cut);
            var expected = coefficients(TestJpegWriter.of(original).progressive(script.subList(0, k)));
            assertSameCoefficients(expected, coefficients(truncated));
            assertDoesNotThrow(() -> JpegDecoder.decode(MediaSource.of(truncated), ImageDecoderOptions.defaults()));
        }
    }

    @Test
    @DisplayName("decodes progressive streams truncated at any byte without failing once data is present")
    void truncatedAnywhere() {
        var full = TestJpegWriter.of(coefficients(ppmToJpeg(testorigPpm(), ChromaFormat.YUV420)))
                .progressive(TestJpegWriter.simpleProgression(3, true));
        var firstData = scanEnd(full, 2) + 16;
        var random = new Random(3);
        for (var i = 0; i < 200; i++) {
            var cut = firstData + random.nextInt(full.length - firstData);
            var truncated = java.util.Arrays.copyOf(full, cut);
            assertTimeoutPreemptively(Duration.ofSeconds(5),
                    () -> JpegDecoder.decode(MediaSource.of(truncated), ImageDecoderOptions.defaults()));
        }
        var headersOnly = java.util.Arrays.copyOf(full, scanEnd(full, 2));
        assertThrows(MediaException.Malformed.class, () -> JpegDecoder.decode(MediaSource.of(headersOnly), ImageDecoderOptions.defaults()));
    }

    /**
     * Returns the index of the first SOS header byte after {@code from}, which ends the headers of
     * that scan.
     */
    private static int scanEnd(byte[] jpeg, int from) {
        for (var i = from; i + 1 < jpeg.length; i++) {
            if (jpeg[i] == (byte) 0xFF && jpeg[i + 1] == (byte) 0xDA) {
                var length = ((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF);
                return i + 2 + length;
            }
        }
        return jpeg.length;
    }

    private static byte[] planar(YuvPicture picture) {
        var out = new java.io.ByteArrayOutputStream();
        for (var y = 0; y < picture.height(); y++) {
            out.write(picture.y(), y * picture.yStride(), picture.width());
        }
        if (picture.chromaFormat().hasChroma()) {
            for (var plane : new byte[][]{picture.u(), picture.v()}) {
                for (var y = 0; y < picture.chromaHeight(); y++) {
                    out.write(plane, y * picture.uvStride(), picture.chromaWidth());
                }
            }
        }
        return out.toByteArray();
    }
}
