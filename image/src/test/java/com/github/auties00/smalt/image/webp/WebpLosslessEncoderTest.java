package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.webp.internal.color.YuvConverter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Round-trips the lossless encoder through this module's decoder and dwebp, checks that its
 * output is byte-identical to cwebp -lossless over a matrix of images, methods, qualities and
 * the exact flag, and covers Y'CbCr input, transparent-pixel handling, the container flavors and
 * the dimension limit.
 */
class WebpLosslessEncoderTest {
    /** The fixtures of the byte-identity matrix, each exercising a different coding path. */
    enum Source {
        /** Many colors, a gradient alpha and fully transparent columns over colored pixels. */
        ALPHA(128, 96, "testsrc2=size=128x96:rate=1", "format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)'"
                + ":a='if(lt(X,20),0,clip(Y*3,0,255))'"),
        /** 70 colors: the palette transform. */
        PALETTE(100, 70, "nullsrc=size=100x70:rate=1", "format=rgba,geq=r='mod(floor(X/10)*50,256)'"
                + ":g='mod(floor(Y/10)*70,256)':b='128':a='255'"),
        /** 2 colors: pixel bundling at 8 pixels per byte. */
        BILEVEL(61, 33, "nullsrc=size=61x33:rate=1", "format=rgba,geq=r='if(mod(X+Y,3),255,0)':g='r(X,Y)':b='r(X,Y)':a='255'"),
        /** An opaque natural-ish image: predictors, cross-color, color cache. */
        OPAQUE(160, 120, "testsrc2=size=160x120:rate=1", "format=rgba");

        final int width;
        final int height;
        final String source;
        final String filter;

        Source(int width, int height, String source, String filter) {
            this.width = width;
            this.height = height;
            this.source = source;
            this.filter = filter;
        }

        Path png() {
            return Fixtures.ffmpeg("png", List.of("-f", "lavfi", "-i", source), List.of("-frames:v", "1", "-vf", filter));
        }

        RgbaPicture picture() {
            return RgbaPicture.of(width, height, width * 4, Oracle.decodeVideo(png(), "rgba", 1), true);
        }
    }

    static RgbaPicture testsrc(int width, int height) {
        var png = Fixtures.testImage("png", width, height, List.of("-pix_fmt", "rgba"));
        var rgba = Oracle.decodeVideo(png, "rgba", 1);
        return RgbaPicture.of(width, height, width * 4, rgba, true);
    }

    static RgbaPicture random(int width, int height, long seed, boolean alpha) {
        var rgba = new byte[width * height * 4];
        new Random(seed).nextBytes(rgba);
        if (!alpha) {
            for (var i = 3; i < rgba.length; i += 4) {
                rgba[i] = (byte) 0xFF;
            }
        }
        return RgbaPicture.of(width, height, width * 4, rgba, alpha);
    }

    @ParameterizedTest(name = "method {0}")
    @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6})
    @DisplayName("round-trips testsrc2 exactly at every method, through this decoder, dwebp and FFmpeg")
    void roundTrip(int method) {
        var picture = testsrc(160, 120);
        var file = WebpEncoder.encode(picture, WebpEncoderOptions.lossless().toBuilder().method(method).build());
        WebpStructure.validate(file);
        var decoded = WebpTestSupport.decodeRgba(file);
        assertArrayEquals(WebpTestSupport.tightRgba(picture), WebpTestSupport.tightRgba(decoded));
        var path = WebpTestSupport.scratch("ll-m" + method + ".webp", file);
        assertArrayEquals(WebpTestSupport.tightRgba(picture), WebpTestSupport.dwebpRgba(path));
        Oracle.assertDecodesCleanly(path);
        assertArrayEquals(WebpTestSupport.tightRgba(picture), Oracle.decodeVideo(path, "rgba", 1), "FFmpeg's own VP8L decoder");
    }

    @ParameterizedTest(name = "{0}x{1}")
    @MethodSource("randomSizes")
    @DisplayName("round-trips random RGBA exactly, including 1-pixel sides")
    void randomRoundTrip(int width, int height) {
        var picture = random(width, height, width * 1000L + height, true);
        var file = WebpEncoder.encode(picture, WebpEncoderOptions.lossless().toBuilder().exact(true).build());
        assertArrayEquals(WebpTestSupport.tightRgba(picture), WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(file)));
    }

    static Stream<Arguments> randomSizes() {
        return Stream.of(Arguments.of(1, 1), Arguments.of(1, 17), Arguments.of(17, 1), Arguments.of(37, 23), Arguments.of(3, 300));
    }

    static Stream<Arguments> cwebpMatrix() {
        var list = new ArrayList<Arguments>();
        for (var source : Source.values()) {
            for (var method = 0; method <= 6; method++) {
                for (var quality : List.of(0, 50, 100)) {
                    list.add(Arguments.of(source, method, quality, false));
                }
            }
        }
        for (var method = 0; method <= 6; method++) {
            list.add(Arguments.of(Source.ALPHA, method, 75, true));
        }
        return list.stream();
    }

    @ParameterizedTest(name = "{0} -m {1} -q {2} exact={3}")
    @MethodSource("cwebpMatrix")
    @DisplayName("writes the same bytes as cwebp -lossless")
    void identicalToCwebp(Source source, int method, int quality, boolean exact) {
        Oracle.assume(Oracle.Tool.CWEBP);
        var png = source.png();
        var out = WebpTestSupport.scratchPath("cwebp-ll-" + source + "-m" + method + "-q" + quality + (exact ? "-exact" : "") + ".webp");
        var arguments = new ArrayList<>(List.of("-quiet", "-lossless", "-m", Integer.toString(method), "-q", Integer.toString(quality)));
        if (exact) {
            arguments.add("-exact");
        }
        arguments.addAll(List.of(png.toString(), "-o", out.toString()));
        Oracle.run(Oracle.Tool.CWEBP, Duration.ofMinutes(2), arguments);
        var options = WebpEncoderOptions.lossless().toBuilder().quality(quality).method(method).exact(exact).build();
        var ours = WebpEncoder.encode(source.picture(), options);
        assertArrayEquals(WebpTestSupport.readFile(out), ours);
    }

    @ParameterizedTest(name = "method {0}")
    @ValueSource(ints = {0, 2, 4, 6})
    @DisplayName("stays within 10% of cwebp -lossless at equal method on a 320x240 frame")
    void sizeVsCwebp(int method) {
        Oracle.assume(Oracle.Tool.CWEBP);
        var png = Fixtures.testImage("png", 320, 240, List.of("-pix_fmt", "rgba"));
        var picture = RgbaPicture.of(320, 240, 320 * 4, Oracle.decodeVideo(png, "rgba", 1), true);
        var ours = WebpEncoder.encode(picture, WebpEncoderOptions.lossless().toBuilder().method(method).build());
        var out = WebpTestSupport.scratchPath("cwebp-m" + method + ".webp");
        Oracle.run(Oracle.Tool.CWEBP, Duration.ofMinutes(1), List.of("-quiet", "-lossless", "-m", Integer.toString(method),
                png.toString(), "-o", out.toString()));
        var theirs = WebpTestSupport.readFile(out);
        assertTrue(ours.length <= theirs.length * 1.10, "ours " + ours.length + " vs cwebp " + theirs.length);
    }

    @Nested
    @DisplayName("transparency")
    class Transparency {
        @Test
        @DisplayName("keeps the color under transparent pixels only when exact")
        void exact() {
            var picture = Source.ALPHA.picture();
            var source = WebpTestSupport.tightRgba(picture);
            var exact = WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(
                    WebpEncoder.encode(picture, WebpEncoderOptions.lossless().toBuilder().exact(true).build())));
            assertArrayEquals(source, exact);
            var cleaned = WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(
                    WebpEncoder.encode(picture, WebpEncoderOptions.lossless())));
            for (var i = 0; i < source.length; i += 4) {
                if (source[i + 3] == 0) {
                    assertEquals(0, cleaned[i] | cleaned[i + 1] | cleaned[i + 2] | cleaned[i + 3], "transparent pixel " + i / 4);
                } else {
                    for (var c = 0; c < 4; c++) {
                        assertEquals(source[i + c], cleaned[i + c], "visible pixel " + i / 4);
                    }
                }
            }
        }

        @Test
        @DisplayName("encodes an RGBA picture without hasAlpha as opaque and clears the VP8L alpha hint")
        void opaque() {
            var picture = random(20, 10, 5, true);
            var opaque = RgbaPicture.of(20, 10, 80, picture.pixels(), false);
            var file = WebpEncoder.encode(opaque, WebpEncoderOptions.lossless());
            assertEquals(0, (file[20 + 4] >> 4) & 1, "alpha_is_used bit");
            var decoded = WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(file));
            for (var i = 0; i < decoded.length; i += 4) {
                assertEquals((byte) 0xFF, decoded[i + 3]);
                assertEquals(picture.pixels()[i], decoded[i]);
            }
            assertFalse(WebpDecoder.probe(MediaSource.of(file)).hasAlpha());
        }
    }

    @Nested
    @DisplayName("Y'CbCr input")
    class YuvInput {
        @Test
        @DisplayName("codes the fancy-upsampled RGBA of a limited-range BT.601 4:2:0 picture")
        void bt601() {
            var picture = YuvPicture.allocate(33, 19, ChromaFormat.YUV420, true, ColorInfo.BT601_LIMITED);
            var random = new Random(3);
            random.nextBytes(picture.y());
            random.nextBytes(picture.u());
            random.nextBytes(picture.v());
            random.nextBytes(picture.alpha());
            var file = WebpEncoder.encode(picture, WebpEncoderOptions.lossless().toBuilder().exact(true).build());
            var expected = new byte[33 * 19 * 4];
            YuvConverter.toRgba(picture, expected, 0, 33 * 4);
            assertArrayEquals(expected, WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(file)));
        }

        static Stream<YuvPicture> otherLayouts() {
            var random = new Random(5);
            var pictures = List.of(YuvPicture.allocate(8, 8, ChromaFormat.YUV444, false, ColorInfo.BT601_LIMITED),
                    YuvPicture.allocate(9, 7, ChromaFormat.YUV420, true, ColorInfo.BT709_LIMITED),
                    YuvPicture.allocate(8, 8, ChromaFormat.YUV420, false, ColorInfo.JPEG));
            for (var picture : pictures) {
                random.nextBytes(picture.y());
                random.nextBytes(picture.u());
                random.nextBytes(picture.v());
                if (picture.alpha() != null) {
                    random.nextBytes(picture.alpha());
                }
            }
            return pictures.stream();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("otherLayouts")
        @DisplayName("codes other chroma layouts and color spaces as their RGBA conversion")
        void otherLayout(YuvPicture picture) {
            var file = WebpEncoder.encode(picture, WebpEncoderOptions.lossless().toBuilder().exact(true).build());
            var expected = ImageOps.toRgba(picture);
            assertArrayEquals(WebpTestSupport.tightRgba(expected), WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(file)));
        }
    }

    @Nested
    @DisplayName("container")
    class Container {
        @Test
        @DisplayName("writes a simple VP8L file by default")
        void simple() {
            var file = WebpEncoder.encode(random(9, 7, 1, true), WebpEncoderOptions.lossless());
            var chunks = WebpStructure.validate(file);
            assertEquals(List.of("VP8L"), chunks.stream().map(WebpStructure.RawChunk::tag).toList());
        }

        @ParameterizedTest(name = "alpha={0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("writes VP8X with the alpha flag matching the image when the extended format is requested")
        void extended(boolean alpha) {
            var file = WebpEncoder.encode(random(9, 7, 1, alpha), WebpEncoderOptions.lossless().toBuilder()
                    .extendedFormat(true).build());
            var chunks = WebpStructure.validate(file);
            assertEquals(List.of("VP8X", "VP8L"), chunks.stream().map(WebpStructure.RawChunk::tag).toList());
            assertEquals(alpha, (file[20] & 0x10) != 0, "VP8X alpha flag");
            var info = WebpDecoder.probe(MediaSource.of(file));
            assertEquals(9, info.width());
            assertEquals(7, info.height());
            assertEquals(alpha, info.hasAlpha());
            assertEquals(9, WebpDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults()).picture().width());
        }

        @Test
        @DisplayName("is described consistently by webpmux -info")
        void webpmux() {
            var file = WebpEncoder.encode(random(9, 7, 1, true), WebpEncoderOptions.lossless().toBuilder()
                    .extendedFormat(true).build());
            var path = WebpTestSupport.scratch("ll-extended.webp", file);
            var info = WebpTestSupport.runTool("webpmux", List.of("-info", path.toString()));
            assertTrue(info.contains("Canvas size: 9 x 7"), info);
            assertTrue(info.contains("Features present: transparency"), info);
        }
    }

    @Nested
    @DisplayName("limits")
    class Limits {
        @Test
        @DisplayName("accepts 16383-pixel sides")
        void maximum() {
            var picture = RgbaPicture.of(16383, 1, 16383 * 4, new byte[16383 * 4], true);
            var file = WebpEncoder.encode(picture, WebpEncoderOptions.lossless().toBuilder().method(0).build());
            assertEquals(16383, WebpDecoder.probe(MediaSource.of(file)).width());
        }

        @Test
        @DisplayName("rejects 16384-pixel sides")
        void tooLarge() {
            var wide = RgbaPicture.of(16384, 1, 16384 * 4, new byte[16384 * 4], false);
            assertThrows(MediaException.LimitExceeded.class, () -> WebpEncoder.encode(wide, WebpEncoderOptions.lossless()));
            var tall = RgbaPicture.of(1, 16384, 4, new byte[16384 * 4], false);
            assertThrows(MediaException.LimitExceeded.class, () -> WebpEncoder.encode(tall, WebpEncoderOptions.lossless()));
        }

        @Test
        @DisplayName("validates the options")
        void options() {
            assertThrows(IllegalArgumentException.class, () -> WebpEncoderOptions.lossy(101));
            assertThrows(IllegalArgumentException.class, () -> WebpEncoderOptions.lossy(-1));
            assertThrows(IllegalArgumentException.class, () -> WebpEncoderOptions.lossless().toBuilder().method(7)
                    .build());
            assertThrows(NullPointerException.class, () -> WebpEncoderOptions.lossless().toBuilder().alphaFilter(null)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> WebpEncoderOptions.lossless().toBuilder()
                    .alphaQuality(-1).build());
            assertThrows(IllegalArgumentException.class, () -> WebpEncoderOptions.lossless().toBuilder().targetSize(-1)
                    .build());
            assertThrows(NullPointerException.class, () -> WebpEncoder.encode(null, WebpEncoderOptions.lossless()));
        }
    }
}
