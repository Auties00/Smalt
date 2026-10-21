package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Rgba16Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.Yuv16Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Decodes the libjpeg-turbo 3.1.3 {@code cjpeg -precision 12} outputs of cjpeg12/ (baseline,
 * progressive, arithmetic and progressive arithmetic; 4:2:0, 4:2:2, 4:4:4, 4:4:0, 4:1:1, gray and
 * RGB; with and without restart markers; crops of testorig12 and a q100 synthetic pattern that
 * reaches the largest coefficient magnitudes) and compares with the MD5 of
 * {@code djpeg -dct int -scale 1/N} (djpeg12-md5.csv): through {@link DjpegEmulator} at every
 * scale, and through the public decoder with {@code highBitDepth} plus libjpeg's 12-bit upsampling
 * and color conversion wherever djpeg scales every component alike.
 */
class Djpeg12ConformanceTest {
    private static final String BASE = "/com/github/auties00/smalt/image/jpeg/";

    static List<Arguments> cases() {
        var cases = new ArrayList<Arguments>();
        try (var reader = new BufferedReader(new InputStreamReader(
                Djpeg12ConformanceTest.class.getResourceAsStream(BASE + "djpeg12-md5.csv"), StandardCharsets.US_ASCII))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                var f = line.split(",");
                var denominator = Integer.parseInt(f[6].substring(2));
                cases.add(Arguments.of(f[0], f[3], f[4], Integer.parseInt(f[5]), Integer.numberOfTrailingZeros(denominator), f[7]));
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return cases;
    }

    static byte[] resource(String file) {
        try (var input = Djpeg12ConformanceTest.class.getResourceAsStream(BASE + "cjpeg12/" + file)) {
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static String md5(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    static ImageDecoderOptions options(int scaleShift, int width, int height, boolean highBitDepth) {
        var builder = ImageDecoderOptions.builder().highBitDepth(highBitDepth);
        if (scaleShift != 0) {
            builder.minSize(JpegFrame.ceilDiv(width, 1 << scaleShift), JpegFrame.ceilDiv(height, 1 << scaleShift));
        }
        return builder.build();
    }

    @ParameterizedTest(name = "{0} scale 1/{4}")
    @MethodSource("cases")
    @DisplayName("the coefficients transformed and converted like libjpeg match djpeg -dct int bit for bit")
    void matchesDjpeg(String file, String layout, String mode, int restart, int scaleShift, String md5) {
        assertEquals(md5, md5(DjpegEmulator.decode(resource(file), scaleShift)));
    }

    @ParameterizedTest(name = "{0} scale 1/{4}")
    @MethodSource("cases")
    @DisplayName("the public decoder's 16-bit output matches djpeg where it scales every component alike")
    void publicDecoder(String file, String layout, String mode, int restart, int scaleShift, String md5) {
        var data = resource(file);
        var reader = new JpegReader(data, 0, data.length, MediaLimits.DEFAULT);
        var frame = reader.readHeaders();
        var uniform = switch (layout) {
            case "MONOCHROME", "YUV444", "RGB" -> true;
            default -> false;
        };
        if (scaleShift != 0 && !uniform) {
            return;
        }
        var image = JpegDecoder.decode(MediaSource.of(data), options(scaleShift, frame.width(), frame.height(), true));
        assertEquals(frame.width(), image.sourceWidth());
        if (layout.equals("RGB")) {
            var picture = assertInstanceOf(Rgba16Picture.class, image.picture());
            var width = picture.width();
            var height = picture.height();
            var samples = new int[width * height * 3];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    for (var c = 0; c < 3; c++) {
                        var value = picture.pixels()[y * picture.stride() + 4 * x + c] & 0xFFFF;
                        // the 16-bit samples are round(v * 65535 / 4095), which divides back exactly
                        var twelve = (int) Math.round(value * 4095.0 / 65535.0);
                        assertEquals(value, (int) Math.round(twelve * 65535.0 / 4095.0));
                        samples[3 * (y * width + x) + c] = twelve;
                    }
                    assertEquals(0xFFFF, picture.pixels()[y * picture.stride() + 4 * x + 3] & 0xFFFF);
                }
            }
            assertEquals(md5, md5(Djpeg12Support.pnm(width, height, 3, samples)));
            return;
        }
        var picture = assertInstanceOf(Yuv16Picture.class, image.picture());
        assertEquals(12, picture.bitDepth());
        assertEquals(ColorInfo.JPEG, picture.color());
        var expected = switch (layout) {
            case "YUV420" -> ChromaFormat.YUV420;
            case "YUV422" -> ChromaFormat.YUV422;
            case "MONOCHROME" -> ChromaFormat.MONOCHROME;
            default -> ChromaFormat.YUV444;
        };
        assertEquals(expected, picture.chromaFormat());
        if (layout.equals("YUV440") || layout.equals("YUV411")) {
            // upsampled to 4:4:4 by the decoder, which is what libjpeg's upsampler computes
            assertEquals(md5, md5(Djpeg12Support.djpegOutput(picture, true)));
            return;
        }
        assertEquals(md5, md5(Djpeg12Support.djpegOutput(picture, scaleShift < 3)));
    }

    @ParameterizedTest(name = "{0} scale 1/{4}")
    @MethodSource("cases")
    @DisplayName("the 8-bit decode holds the 12-bit samples rounded to 8 bits")
    void eightBit(String file, String layout, String mode, int restart, int scaleShift, String md5) {
        if (restart != 0) {
            return;
        }
        var data = resource(file);
        var wideReader = new JpegReader(data, 0, data.length, MediaLimits.DEFAULT);
        var wide = wideReader.decodeWidePlanes(scaleShift);
        var narrow = new JpegReader(data, 0, data.length, MediaLimits.DEFAULT).decodePlanes(scaleShift);
        assertTrue(wide.wide());
        assertFalse(narrow.wide());
        for (var c = 0; c < wide.frame().components().size(); c++) {
            var w = wide.componentWidth(c);
            var h = wide.componentHeight(c);
            for (var y = 0; y < h; y++) {
                for (var x = 0; x < w; x++) {
                    var v = wide.widePlane(c)[y * wide.stride(c) + x];
                    assertTrue(v >= 0 && v <= 4095);
                    assertEquals((int) Math.round(v * 255.0 / 4095.0), narrow.plane(c)[y * narrow.stride(c) + x] & 0xFF);
                }
            }
        }
        var image = JpegDecoder.decode(MediaSource.of(data), options(scaleShift, wide.frame().width(),
                wide.frame().height(), false));
        switch (layout) {
            case "RGB" -> assertInstanceOf(RgbaPicture.class, image.picture());
            default -> {
                var picture = assertInstanceOf(YuvPicture.class, image.picture());
                if (!layout.equals("YUV440") && !layout.equals("YUV411")) {
                    for (var y = 0; y < picture.height(); y++) {
                        for (var x = 0; x < picture.width(); x++) {
                            assertEquals(narrow.plane(0)[y * narrow.stride(0) + x], picture.y()[y * picture.yStride() + x]);
                        }
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("components no scan codes decode to mid-gray at both depths")
    void unscannedComponents() {
        var planes = new byte[][]{JpegStreamTest.plane(24, 16, 1), JpegStreamTest.plane(24, 16, 2), JpegStreamTest.plane(24, 16, 3)};
        var writer = TestJpegWriter.fromPlanes(24, 16, new int[]{1, 2, 3}, new int[]{1, 1, 1}, new int[]{1, 1, 1}, planes, 90);
        writer.precision = 12;
        writer.sofMarker = 0xC1;
        var jpeg = writer.sequential(new int[]{0});
        var wide = new JpegReader(jpeg, 0, jpeg.length, MediaLimits.DEFAULT).decodeWidePlanes(0);
        var narrow = new JpegReader(jpeg, 0, jpeg.length, MediaLimits.DEFAULT).decodePlanes(0);
        for (var c = 1; c < 3; c++) {
            for (var y = 0; y < 16; y++) {
                for (var x = 0; x < 24; x++) {
                    assertEquals(2048, wide.widePlane(c)[y * wide.stride(c) + x]);
                    assertEquals(128, narrow.plane(c)[y * narrow.stride(c) + x] & 0xFF);
                }
            }
        }
    }

    @Test
    @DisplayName("wide planes are only for 12-bit frames, and each kind of plane has its own accessor")
    void planeKinds() {
        var eight = TestJpegWriter.fromPlanes(8, 8, new int[]{1}, new int[]{1}, new int[]{1},
                new byte[][]{JpegStreamTest.plane(8, 8, 0)}, 90).sequential(new int[]{0});
        assertThrows(IllegalStateException.class, () -> new JpegReader(eight, 0, eight.length, MediaLimits.DEFAULT)
                .decodeWidePlanes(0));
        var narrow = new JpegReader(eight, 0, eight.length, MediaLimits.DEFAULT).decodePlanes(0);
        assertThrows(IllegalStateException.class, () -> narrow.widePlane(0));
        var data = resource("33x65-yuv420-baseline.jpg");
        var wide = new JpegReader(data, 0, data.length, MediaLimits.DEFAULT).decodeWidePlanes(1);
        assertThrows(IllegalStateException.class, () -> wide.plane(0));
    }

    @Test
    @DisplayName("checks the 12-bit buffers against the allocation limit at both depths")
    void limits() {
        var data = resource("227x149-yuv444-baseline.jpg");
        // an 8-bit plane of this 232x152 padded component takes 35264 bytes, its 12-bit coefficients and planes twice that
        var tight = MediaLimits.builder().maxDimension(4096).maxPixels(1 << 20).maxFrames(1).maxChannels(4)
                .maxBytes(50_000).build();
        for (var wide : new boolean[]{false, true}) {
            var options = ImageDecoderOptions.builder().limits(tight).highBitDepth(wide).build();
            assertThrows(MediaException.LimitExceeded.class, () -> JpegDecoder.decode(MediaSource.of(data), options));
        }
        assertThrows(MediaException.LimitExceeded.class, () -> new JpegReader(data, 0, data.length, tight).decodeWidePlanes(0));
        var mjpeg = new com.github.auties00.smalt.image.jpeg.MjpegDecoder(
                com.github.auties00.smalt.video.VideoDecoderOptions.builder().limits(tight).highBitDepth(true).build());
        assertThrows(MediaException.LimitExceeded.class, () -> mjpeg.decodeFrame(data, 0, data.length));
    }

    @Test
    @DisplayName("the q100 synthetic pattern reaches 12-bit magnitude categories beyond the 8-bit range")
    void largeCoefficients() {
        var data = resource("64x48-yuv444-baseline-q100.jpg");
        var coefficients = new JpegReader(data, 0, data.length, MediaLimits.DEFAULT).decodeCoefficients();
        var largest = 0;
        for (var value : coefficients.coefficients(0)) {
            largest = Math.max(largest, Math.abs(value));
        }
        // 8-bit JPEG coefficients stay below 2^11; this one needs magnitude category 13 or more
        assertTrue(largest >= 1 << 12, "largest coefficient " + largest);
    }
}
