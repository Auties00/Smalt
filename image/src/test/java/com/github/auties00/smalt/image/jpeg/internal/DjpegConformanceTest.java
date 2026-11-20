package com.github.auties00.smalt.image.jpeg.internal;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.JpegDecoder;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
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
 * Decodes libjpeg-turbo 3.1.3 {@code cjpeg} outputs of testorig.ppm crops (baseline, progressive,
 * arithmetic and progressive arithmetic; 4:2:0, 4:2:2, 4:4:4, 4:4:0, 4:1:1 and gray; with and
 * without restart markers) and compares against the MD5 of {@code djpeg -dct int -scale 1/N}
 * (djpeg-md5.csv), through {@link DjpegEmulator} at every scale and through the public decoder
 * plus libjpeg upsampling at full size.
 */
class DjpegConformanceTest {
    private static final String BASE = "/com/github/auties00/smalt/image/jpeg/";

    static List<Arguments> cases() {
        var cases = new ArrayList<Arguments>();
        try (var reader = new BufferedReader(new InputStreamReader(
                DjpegConformanceTest.class.getResourceAsStream(BASE + "djpeg-md5.csv"), StandardCharsets.US_ASCII))) {
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
        try (var input = DjpegConformanceTest.class.getResourceAsStream(BASE + "cjpeg/" + file)) {
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

    @ParameterizedTest(name = "{0} scale 1/{4}")
    @MethodSource("cases")
    @DisplayName("matches djpeg -dct int bit for bit")
    void matchesDjpeg(String file, String layout, String mode, int restart, int scaleShift, String md5) {
        assertEquals(md5, md5(DjpegEmulator.decode(resource(file), scaleShift)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("the public decoder output upsampled like libjpeg matches djpeg at full size")
    void publicDecoder(String file, String layout, String mode, int restart, int scaleShift, String md5) {
        if (scaleShift != 0) {
            return;
        }
        var picture = (YuvPicture) JpegDecoder.decode(MediaSource.of(resource(file)), ImageDecoderOptions.defaults()).picture();
        var expected = switch (layout) {
            case "YUV420" -> ChromaFormat.YUV420;
            case "YUV422" -> ChromaFormat.YUV422;
            case "YUV440" -> ChromaFormat.YUV440;
            case "MONOCHROME" -> ChromaFormat.MONOCHROME;
            default -> ChromaFormat.YUV444;
        };
        assertEquals(expected, picture.chromaFormat());
        var out = new ByteArrayOutputStream();
        var width = picture.width();
        var height = picture.height();
        if (expected == ChromaFormat.MONOCHROME) {
            out.writeBytes(("P5\n" + width + " " + height + "\n255\n").getBytes(StandardCharsets.US_ASCII));
            for (var y = 0; y < height; y++) {
                out.write(picture.y(), y * picture.yStride(), width);
            }
        } else {
            out.writeBytes(("P6\n" + width + " " + height + "\n255\n").getBytes(StandardCharsets.US_ASCII));
            var hMax = 1 << expected.shiftX();
            var vMax = 1 << expected.shiftY();
            var u = Upsampler.upsample(picture.u(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(),
                    1, 1, hMax, vMax, width, height, true);
            var v = Upsampler.upsample(picture.v(), picture.uvStride(), picture.chromaWidth(), picture.chromaHeight(),
                    1, 1, hMax, vMax, width, height, true);
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var rgb = JpegOutput.yccToRgb(picture.y()[y * picture.yStride() + x] & 0xFF, u[y * width + x] & 0xFF,
                            v[y * width + x] & 0xFF);
                    out.write(rgb >>> 16);
                    out.write((rgb >>> 8) & 0xFF);
                    out.write(rgb & 0xFF);
                }
            }
        }
        assertEquals(md5, md5(out.toByteArray()));
        if (expected.hasChroma()) {
            // ImageOps reconstructs the chroma as libjpeg does, the 4:4:0 rows with its h1v2 fancy upsampling
            var rgba = com.github.auties00.smalt.image.ops.ImageOps.toRgba(picture);
            var rgb = new ByteArrayOutputStream();
            rgb.writeBytes(("P6\n" + width + " " + height + "\n255\n").getBytes(StandardCharsets.US_ASCII));
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    rgb.write(rgba.pixels(), y * rgba.stride() + 4 * x, 3);
                }
            }
            assertEquals(md5, md5(rgb.toByteArray()), "ImageOps.toRgba against djpeg");
        }
    }
}
