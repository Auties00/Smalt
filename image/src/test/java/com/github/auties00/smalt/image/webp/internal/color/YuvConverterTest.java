package com.github.auties00.smalt.image.webp.internal.color;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.simd.Simd;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks the WebP color conversions against libwebp: RGBA to Y'CbCr(A) with alpha-weighted
 * gamma averaging and transparent-area cleanup (against golden digests produced by libwebp 1.6.0's
 * WebPPictureImportRGBA and WebPCleanupTransparentArea on the same pseudo-random input), fancy
 * upsampling against dwebp's own YUV and RGBA outputs, and the vector kernels against the scalar
 * ones.
 */
class YuvConverterTest {
    /** The xorshift32 generator of the golden harness. */
    static final class XorShift {
        int state;

        XorShift(int seed) {
            this.state = seed;
        }

        int next() {
            state ^= state << 13;
            state ^= state >>> 17;
            state ^= state << 5;
            return state;
        }
    }

    static byte[] harnessInput(int w, int h, int seed) {
        var random = new XorShift(seed);
        var rgba = new byte[w * h * 4];
        for (var i = 0; i < w * h; i++) {
            var r = random.next();
            rgba[4 * i] = (byte) r;
            rgba[4 * i + 1] = (byte) (r >>> 8);
            rgba[4 * i + 2] = (byte) (r >>> 16);
            var a = (r >>> 24) & 3;
            rgba[4 * i + 3] = (byte) (a == 0 ? 0 : a == 1 ? 255 : random.next() & 255);
        }
        for (var y = 0; y < h && y < 16; y++) {
            for (var x = 0; x < w && x < 16; x++) {
                rgba[4 * (y * w + x) + 3] = 0;
            }
        }
        for (var y = h / 2; y < h; y++) {
            for (var x = w / 2; x < w; x++) {
                rgba[4 * (y * w + x) + 3] = (byte) 255;
            }
        }
        return rgba;
    }

    static void writePlanes(ByteArrayOutputStream out, YuvPicture picture, boolean alpha) {
        for (var y = 0; y < picture.height(); y++) {
            out.write(picture.y(), y * picture.yStride(), picture.width());
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            out.write(picture.u(), y * picture.uvStride(), picture.chromaWidth());
        }
        for (var y = 0; y < picture.chromaHeight(); y++) {
            out.write(picture.v(), y * picture.uvStride(), picture.chromaWidth());
        }
        if (alpha && picture.alpha() != null) {
            for (var y = 0; y < picture.height(); y++) {
                out.write(picture.alpha(), y * picture.alphaStride(), picture.width());
            }
        }
    }

    static String sha256(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    @ParameterizedTest(name = "{0}x{1} seed {2}")
    @CsvSource({
            "37, 29, 1, adc761ff65fec74e1a26b5f6f944b4ec0cd0057b36e38448651c044bb54c01d4",
            "64, 48, 7, 51dabeb89cbb690e62eff4a73bf89d1714992c8cc72da2c43555cafeaabc2516",
            "1, 1, 3, bc0bf427d36b990e66bbe78556690e4f4ea3ef230e620ad4486974223124faa6",
            "2, 3, 5, ade8a57f03741d1ee902738cbe36277d251c00533fc13237fe04a0664bf869e5",
            "129, 67, 11, 3329b3be3638d3c81263282b980fa008580553c2ffc3c64cac0c2da610c7f45b"
    })
    @DisplayName("converts RGBA to Y'CbCrA and cleans transparent areas exactly like libwebp")
    void rgbaToYuvGolden(int w, int h, int seed, String expected) throws Exception {
        var rgba = harnessInput(w, h, seed);
        var picture = YuvConverter.fromRgba(rgba, w * 4, w, h, true, MediaLimits.DEFAULT);
        var out = new ByteArrayOutputStream();
        writePlanes(out, picture, true);
        YuvConverter.cleanupTransparentArea(picture);
        writePlanes(out, picture, false);
        assertEquals(expected, sha256(out.toByteArray()));
    }

    @Test
    @DisplayName("treats RGBA without meaningful alpha as opaque")
    void ignoresAlpha() {
        var rgba = harnessInput(20, 10, 9);
        var picture = YuvConverter.fromRgba(rgba, 80, 20, 10, false, MediaLimits.DEFAULT);
        assertNull(picture.alpha());
    }

    @Nested
    @DisplayName("fancy upsampling")
    class Upsampling {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"61x37", "64x48", "1x1", "2x2", "3x1", "1x5", "17x2"})
        @DisplayName("reproduces dwebp's RGBA from dwebp's own Y'CbCrA")
        void matchesDwebp(String size) throws IOException {
            Oracle.assume(Oracle.Tool.CWEBP);
            Oracle.assume(Oracle.Tool.DWEBP);
            var dims = size.split("x");
            var w = Integer.parseInt(dims[0]);
            var h = Integer.parseInt(dims[1]);
            var png = Fixtures.ffmpeg("png", List.of("-f", "lavfi", "-i", "nullsrc=size=" + size + ":rate=1"),
                    List.of("-frames:v", "1", "-vf", "format=rgba,geq=r='mod(X*37+Y*11,256)':g='mod(X*5+Y*71,256)'"
                            + ":b='mod(X*X+Y*3,256)':a='mod(X*9+Y*5+40,256)'"));
            var dir = Files.createDirectories(Path.of("target", "webp-scratch"));
            var webp = dir.resolve("ups-" + size + ".webp");
            Oracle.run(Oracle.Tool.CWEBP, Duration.ofMinutes(1), List.of("-quiet", "-q", "60", png.toString(), "-o", webp.toString()));
            var yuvFile = dir.resolve("ups-" + size + ".yuv");
            var pamFile = dir.resolve("ups-" + size + ".pam");
            Oracle.run(Oracle.Tool.DWEBP, Duration.ofMinutes(1), List.of(webp.toString(), "-yuv", "-o", yuvFile.toString()));
            Oracle.run(Oracle.Tool.DWEBP, Duration.ofMinutes(1), List.of(webp.toString(), "-pam", "-o", pamFile.toString()));
            var yuv = Files.readAllBytes(yuvFile);
            var pam = Files.readAllBytes(pamFile);
            var uvw = (w + 1) / 2;
            var uvh = (h + 1) / 2;
            var y = new byte[w * h];
            var u = new byte[uvw * uvh];
            var v = new byte[uvw * uvh];
            System.arraycopy(yuv, 0, y, 0, y.length);
            System.arraycopy(yuv, y.length, u, 0, u.length);
            System.arraycopy(yuv, y.length + u.length, v, 0, v.length);
            byte[] a = null;
            if (yuv.length > y.length + 2 * u.length) {
                a = new byte[w * h];
                System.arraycopy(yuv, y.length + 2 * u.length, a, 0, a.length);
            }
            var picture = YuvPicture.builder().size(w, h).chromaFormat(ChromaFormat.YUV420)
                    .color(ColorInfo.BT601_LIMITED)
                    .luma(y, w)
                    .chroma(u, v, uvw)
                    .alpha(a, w)
                    .build();
            var rgba = new byte[w * h * 4];
            YuvConverter.toRgba(picture, rgba, 0, w * 4);
            var expected = new byte[w * h * 4];
            System.arraycopy(pam, pam.length - expected.length, expected, 0, expected.length);
            assertArrayEquals(expected, rgba);
        }
    }

    @Nested
    @DisplayName("vector kernels")
    class Kernels {
        @Test
        @DisplayName("convert Y'CbCr rows to RGBA exactly like the scalar kernel")
        void yuvToRgba() {
            Skip.unlessVector(Simd.ENABLED);
            var vector = VectorYuvDsp.create();
            var scalar = new ScalarYuvDsp();
            var random = new Random(42);
            for (var length = 0; length < 70; length++) {
                var y = new byte[length + 3];
                random.nextBytes(y);
                var u = new int[length];
                var v = new int[length];
                for (var i = 0; i < length; i++) {
                    u[i] = random.nextInt(256);
                    v[i] = random.nextInt(256);
                }
                var a = new byte[length * 4 + 8];
                var b = new byte[length * 4 + 8];
                scalar.yuvToRgbaRow(y, 3, u, v, a, 8, length);
                vector.yuvToRgbaRow(y, 3, u, v, b, 8, length);
                assertArrayEquals(a, b, "length " + length);
            }
        }

        @Test
        @DisplayName("convert RGBA rows to luma exactly like the scalar kernel")
        void rgbaToY() {
            Skip.unlessVector(Simd.ENABLED);
            var vector = VectorYuvDsp.create();
            var scalar = new ScalarYuvDsp();
            var random = new Random(43);
            for (var length = 0; length < 70; length++) {
                var rgba = new byte[length * 4 + 12];
                random.nextBytes(rgba);
                var a = new byte[length + 5];
                var b = new byte[length + 5];
                scalar.rgbaToYRow(rgba, 12, a, 5, length);
                vector.rgbaToYRow(rgba, 12, b, 5, length);
                assertArrayEquals(a, b, "length " + length);
            }
        }

        @Test
        @DisplayName("match libwebp's fixed-point formulas at the range extremes")
        void formulas() {
            assertEquals(0, ScalarYuvDsp.yuvToR(16, 128));
            assertEquals(255, ScalarYuvDsp.yuvToR(235, 128));
            assertEquals(255, ScalarYuvDsp.yuvToG(255, 0, 0));
            assertEquals(0, ScalarYuvDsp.yuvToB(0, 0));
            assertEquals(16, ScalarYuvDsp.rgbToY(0, 0, 0, 1 << 15));
            assertEquals(235, ScalarYuvDsp.rgbToY(255, 255, 255, 1 << 15));
            assertEquals(128, ScalarYuvDsp.rgbToU(4 * 128, 4 * 128, 4 * 128, 1 << 17));
        }
    }
}
