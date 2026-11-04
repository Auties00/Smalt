package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.webp.WebpEncoderOptions.Preset;
import com.github.auties00.smalt.image.webp.internal.color.YuvConverter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import com.github.auties00.smalt.video.vp8.internal.BoolDecoder;
import com.github.auties00.smalt.video.vp8.internal.Vp8;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the lossy tuning, sharp and dithered conversion, automatic filtering, PSNR target and
 * near-lossless modes of the still encoder: every mode writes the same bytes as cwebp 1.6.0 run
 * with the equivalent flags on the same PNG, and the modes keep the properties they promise (the
 * near-lossless error bound, the PSNR convergence, the partition layout).
 */
@DisplayName("WebP encoder modes")
class WebpEncoderModesTest {
    /**
     * The fixtures: an opaque natural-ish frame, one with alpha, a palette image, a large frame, and noise with flat
     * blocks, for which the lossless encoder codes without a predictor (the near-lossless preprocessing path).
     */
    enum Image {
        NOISE(160, 120) {
            @Override
            RgbaPicture picture() {
                return noise(width, height, false);
            }

            @Override
            Path file() {
                return png("modes-noise", picture());
            }
        },
        NOISE_ALPHA(128, 96) {
            @Override
            RgbaPicture picture() {
                return noise(width, height, true);
            }

            @Override
            Path file() {
                return png("modes-noise-alpha", picture());
            }
        },
        OPAQUE(WebpLosslessEncoderTest.Source.OPAQUE.width, WebpLosslessEncoderTest.Source.OPAQUE.height) {
            @Override
            Path file() {
                return WebpLosslessEncoderTest.Source.OPAQUE.png();
            }
        },
        ALPHA(WebpLosslessEncoderTest.Source.ALPHA.width, WebpLosslessEncoderTest.Source.ALPHA.height) {
            @Override
            Path file() {
                return WebpLosslessEncoderTest.Source.ALPHA.png();
            }
        },
        PALETTE(WebpLosslessEncoderTest.Source.PALETTE.width, WebpLosslessEncoderTest.Source.PALETTE.height) {
            @Override
            Path file() {
                return WebpLosslessEncoderTest.Source.PALETTE.png();
            }
        },
        // 1200 macroblocks: the multi-threaded token loop and the parallel loop filter search
        LARGE(640, 480) {
            @Override
            Path file() {
                return Fixtures.ffmpeg("png", List.of("-f", "lavfi", "-i", "testsrc2=size=640x480:rate=1"),
                        List.of("-frames:v", "1", "-vf", "format=rgba"));
            }
        };

        final int width;
        final int height;

        Image(int width, int height) {
            this.width = width;
            this.height = height;
        }

        abstract Path file();

        RgbaPicture picture() {
            return RgbaPicture.of(width, height, width * 4, Oracle.decodeVideo(file(), "rgba", 1), true);
        }
    }

    /** Writes a picture as a PNG for cwebp, which reads PAM files only where it is built without WIC. */
    static Path png(String name, RgbaPicture picture) {
        return WebpTestSupport.scratch(name + ".png", com.github.auties00.smalt.image.png.PngEncoder.encode(picture));
    }

    /**
     * Returns uniform noise in which every third 8x8 block is flat in one of four colors; with alpha, the noise
     * pixels are fully transparent, opaque or translucent in equal parts.
     */
    static RgbaPicture noise(int width, int height, boolean alpha) {
        var random = new java.util.Random(width * 1000L + height);
        var rgba = new byte[width * height * 4];
        random.nextBytes(rgba);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var p = 4 * (y * width + x);
                var block = (x >> 3) + 7 * (y >> 3);
                if (block % 3 == 0) {
                    var color = 0x40 * (block % 4);
                    rgba[p] = (byte) color;
                    rgba[p + 1] = (byte) (255 - color);
                    rgba[p + 2] = (byte) (color + 0x20);
                    rgba[p + 3] = (byte) 255;
                } else if (!alpha) {
                    rgba[p + 3] = (byte) 255;
                } else {
                    var kind = (rgba[p + 3] & 0xFF) % 3;
                    if (kind == 0) {
                        rgba[p + 3] = 0;
                    } else if (kind == 1) {
                        rgba[p + 3] = (byte) 255;
                    }
                }
            }
        }
        return RgbaPicture.of(width, height, width * 4, rgba, alpha);
    }

    /**
     * Returns whether a simple-container VP8L file codes its pixels without a predictor or a palette, that is with no
     * transform but subtract-green.
     */
    static boolean withoutPredictor(byte[] file) {
        assertEquals('L', file[15], "a VP8L file");
        var bit = 0;
        while (true) {
            if (vp8lBit(file, bit++) == 0) {
                return true;
            }
            var type = vp8lBit(file, bit++) | vp8lBit(file, bit++) << 1;
            if (type != 2) {
                return false;
            }
        }
    }

    /** Returns a bit of the VP8L bitstream after its 5-byte header, least significant bit first. */
    static int vp8lBit(byte[] file, int index) {
        return (file[25 + (index >> 3)] >> (index & 7)) & 1;
    }

    static byte[] cwebp(Path input, String flags) {
        Oracle.assume(Oracle.Tool.CWEBP);
        var out = WebpTestSupport.scratchPath("modes-" + WebpTestSupport.SCRATCH_COUNTER.incrementAndGet() + ".webp");
        var arguments = new ArrayList<>(List.of("-quiet"));
        arguments.addAll(List.of(flags.split(" ")));
        arguments.addAll(List.of(input.toString(), "-o", out.toString()));
        Oracle.run(Oracle.Tool.CWEBP, Duration.ofMinutes(5), arguments);
        return WebpTestSupport.readFile(out);
    }

    static void assertSameAsCwebp(Image image, String flags, WebpEncoderOptions options) {
        assertSameAsCwebp(image.toString(), image.file(), image.picture(), flags, options);
    }

    static void assertSameAsCwebp(String name, Path input, RgbaPicture picture, String flags,
                                  WebpEncoderOptions options) {
        var expected = cwebp(input, flags);
        var actual = WebpEncoder.encode(picture, options);
        var length = Math.min(expected.length, actual.length);
        for (var i = 0; i < length; i++) {
            if (expected[i] != actual[i]) {
                fail(name + " '" + flags + "': first difference at byte " + i + " of " + expected.length
                        + " (cwebp) / " + actual.length + " (ours)");
            }
        }
        assertEquals(expected.length, actual.length, name + " '" + flags + "': file length");
    }

    static Arguments lossy(String flags, UnaryOperator<WebpEncoderOptions.Builder> options) {
        return Arguments.of(flags, options.apply(WebpEncoderOptions.builder()).build());
    }

    static Arguments preset(String flags, Preset preset, UnaryOperator<WebpEncoderOptions.Builder> options) {
        return Arguments.of(flags, options.apply(WebpEncoderOptions.forPreset(preset).toBuilder()).build());
    }

    static Stream<Arguments> lossyModes() {
        return Stream.of(
                lossy("-q 75", b -> b),
                lossy("-q 30 -m 6", b -> b.quality(30).method(6)),
                preset("-preset default", Preset.DEFAULT, b -> b),
                preset("-preset picture", Preset.PICTURE, b -> b),
                preset("-preset photo", Preset.PHOTO, b -> b),
                preset("-preset photo -q 10", Preset.PHOTO, b -> b.quality(10)),
                preset("-preset photo -q 100 -m 2", Preset.PHOTO, b -> b.quality(100).method(2)),
                preset("-preset photo -q 60 -m 0", Preset.PHOTO, b -> b.quality(60).method(0)),
                preset("-preset drawing -q 60", Preset.DRAWING, b -> b.quality(60)),
                preset("-preset icon", Preset.ICON, b -> b),
                preset("-preset text -q 90", Preset.TEXT, b -> b.quality(90)),
                preset("-preset photo -sharp_yuv", Preset.PHOTO, b -> b.sharpYuv(true)),
                preset("-preset text -af -m 5", Preset.TEXT, b -> b.autoFilter(true).method(5)),
                lossy("-sharp_yuv", b -> b.sharpYuv(true)),
                lossy("-sharp_yuv -q 95 -m 6", b -> b.sharpYuv(true).quality(95).method(6)),
                lossy("-sharp_yuv -q 20 -m 0", b -> b.sharpYuv(true).quality(20).method(0)),
                lossy("-sns 0", b -> b.snsStrength(0)),
                lossy("-sns 100 -m 5", b -> b.snsStrength(100).method(5)),
                lossy("-f 0", b -> b.filterStrength(0)),
                lossy("-f 100 -sharpness 7", b -> b.filterStrength(100).filterSharpness(7)),
                lossy("-f 40 -sharpness 3 -m 1", b -> b.filterStrength(40).filterSharpness(3).method(1)),
                lossy("-nostrong", b -> b.simpleFilter(true)),
                lossy("-nostrong -f 80 -m 1", b -> b.simpleFilter(true).filterStrength(80).method(1)),
                lossy("-af", b -> b.autoFilter(true)),
                lossy("-af -m 2", b -> b.autoFilter(true).method(2)),
                lossy("-af -m 0 -q 50", b -> b.autoFilter(true).method(0).quality(50)),
                lossy("-af -m 6 -q 90", b -> b.autoFilter(true).method(6).quality(90)),
                lossy("-af -nostrong", b -> b.autoFilter(true).simpleFilter(true)),
                lossy("-af -f 0", b -> b.autoFilter(true).filterStrength(0)),
                lossy("-af -segments 1", b -> b.autoFilter(true).segments(1)),
                lossy("-af -sharpness 5 -q 40", b -> b.autoFilter(true).filterSharpness(5).quality(40)),
                lossy("-segments 1", b -> b.segments(1)),
                lossy("-segments 2 -m 3", b -> b.segments(2).method(3)),
                lossy("-segments 3 -m 0", b -> b.segments(3).method(0)),
                lossy("-pass 3", b -> b.passes(3)),
                lossy("-pass 2 -m 2", b -> b.passes(2).method(2)),
                lossy("-pass 10 -m 6", b -> b.passes(10).method(6)),
                lossy("-psnr 30", b -> b.targetPsnr(30)),
                lossy("-psnr 36 -m 2", b -> b.targetPsnr(36).method(2)),
                lossy("-psnr 33 -m 0", b -> b.targetPsnr(33).method(0)),
                lossy("-psnr 42 -m 6", b -> b.targetPsnr(42).method(6)),
                lossy("-psnr 45 -pass 3", b -> b.targetPsnr(45).passes(3)),
                lossy("-psnr 38 -q 90 -af", b -> b.targetPsnr(38).quality(90).autoFilter(true)));
    }

    @Nested
    @DisplayName("lossy modes")
    class Lossy {
        @ParameterizedTest(name = "cwebp {0}")
        @MethodSource("com.github.auties00.smalt.image.webp.WebpEncoderModesTest#lossyModes")
        @DisplayName("write cwebp's bytes for an opaque picture")
        void opaque(String flags, WebpEncoderOptions options) {
            assertSameAsCwebp(Image.OPAQUE, flags, options);
        }

        @ParameterizedTest(name = "cwebp {0}")
        @MethodSource("com.github.auties00.smalt.image.webp.WebpEncoderModesTest#lossyModes")
        @DisplayName("write cwebp's bytes, VP8X and ALPH included, for a picture with alpha")
        void alpha(String flags, WebpEncoderOptions options) {
            assertSameAsCwebp(Image.ALPHA, flags, options);
        }

        static Stream<Arguments> largeModes() {
            return Stream.of(
                    lossy("-af", b -> b.autoFilter(true)),
                    lossy("-af -m 6", b -> b.autoFilter(true).method(6)),
                    lossy("-af -m 1", b -> b.autoFilter(true).method(1)),
                    lossy("-af -nostrong -q 30", b -> b.autoFilter(true).simpleFilter(true).quality(30)),
                    lossy("-psnr 40", b -> b.targetPsnr(40)),
                    lossy("-pass 4", b -> b.passes(4)),
                    lossy("-sharp_yuv", b -> b.sharpYuv(true)),
                    preset("-preset photo", Preset.PHOTO, b -> b));
        }

        @ParameterizedTest(name = "cwebp {0}")
        @MethodSource("largeModes")
        @DisplayName("write cwebp's bytes on a picture large enough for the multi-threaded loops")
        void large(String flags, WebpEncoderOptions options) {
            assertSameAsCwebp(Image.LARGE, flags, options);
        }

        static Stream<Arguments> partitionCases() {
            var list = new ArrayList<Arguments>();
            for (var method = 0; method <= 6; method++) {
                for (var partitions : new int[]{2, 4, 8}) {
                    list.add(Arguments.of(method, partitions));
                }
            }
            return list.stream();
        }

        // cwebp has no flag for the partition count, so the partitioned files are checked against the
        // single-partition file, which cwebp reproduces, and against dwebp
        @ParameterizedTest(name = "method {0}, {1} partitions")
        @MethodSource("partitionCases")
        @DisplayName("spread the rows over the token partitions, decoding to the single-partition pixels in Smalt and dwebp")
        void partitions(int method, int partitions) {
            var picture = Image.LARGE.picture();
            var options = WebpEncoderOptions.builder().method(method).autoFilter(method == 2).build();
            var one = WebpEncoder.encode(picture, options);
            var split = WebpEncoder.encode(picture, options.toBuilder().tokenPartitions(partitions).build());
            assertFalse(java.util.Arrays.equals(one, split));
            assertEquals(Integer.numberOfTrailingZeros(partitions), partitionField(split), "the frame header's count");
            assertEquals(0, partitionField(one));
            var pixels = WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(one));
            assertArrayEquals(pixels, WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(split)));
            var path = WebpTestSupport.scratch("modes-parts-m" + method + "-" + partitions + ".webp", split);
            assertArrayEquals(pixels, WebpTestSupport.dwebpRgba(path));
        }

        @Test
        @DisplayName("ignore sharp conversion and dithering for pictures already in the lossy layout")
        void yuvInput() {
            var yuv = YuvConverter.fromRgba(WebpTestSupport.tightRgba(Image.OPAQUE.picture()), Image.OPAQUE.width * 4,
                    Image.OPAQUE.width, Image.OPAQUE.height, false, MediaLimits.DEFAULT);
            var plain = WebpEncoder.encode(yuv, WebpEncoderOptions.defaults());
            assertArrayEquals(plain, WebpEncoder.encode(yuv, WebpEncoderOptions.builder().sharpYuv(true).build()));
            assertArrayEquals(plain, WebpEncoder.encode(yuv, WebpEncoderOptions.builder().dithering(true).build()));
        }

        @Test
        @DisplayName("change the bytes for every lossy knob, so none of them is ignored")
        void knobsMatter() {
            var picture = Image.OPAQUE.picture();
            var base = WebpEncoder.encode(picture, WebpEncoderOptions.defaults());
            var variants = List.of(
                    WebpEncoderOptions.builder().sharpYuv(true).build(),
                    WebpEncoderOptions.builder().dithering(true).build(),
                    WebpEncoderOptions.builder().snsStrength(90).build(),
                    WebpEncoderOptions.builder().filterStrength(10).build(),
                    WebpEncoderOptions.builder().filterSharpness(6).build(),
                    WebpEncoderOptions.builder().simpleFilter(true).build(),
                    WebpEncoderOptions.builder().autoFilter(true).build(),
                    WebpEncoderOptions.builder().segments(1).build(),
                    WebpEncoderOptions.builder().tokenPartitions(2).build(),
                    WebpEncoderOptions.builder().targetPsnr(30).build());
            for (var variant : variants) {
                assertFalse(java.util.Arrays.equals(base, WebpEncoder.encode(picture, variant)), variant.toString());
            }
        }

        /** Returns the log2 partition count of a simple-container lossy file, parsed from the first partition. */
        static int partitionField(byte[] file) {
            var frame = java.util.Arrays.copyOfRange(file, 20, file.length);
            var firstSize = ((frame[0] & 0xFF) | (frame[1] & 0xFF) << 8 | (frame[2] & 0xFF) << 16) >>> 5;
            var bits = new BoolDecoder();
            bits.init(frame, 10, firstSize);
            bits.readBit();
            bits.readBit();
            if (bits.readBit() == 1) {
                var updateMap = bits.readBit();
                if (bits.readBit() == 1) {
                    bits.readBit();
                    for (var s = 0; s < 4; s++) {
                        if (bits.readBit() == 1) {
                            bits.readLiteral(8);
                        }
                    }
                    for (var s = 0; s < 4; s++) {
                        if (bits.readBit() == 1) {
                            bits.readLiteral(7);
                        }
                    }
                }
                if (updateMap == 1) {
                    for (var s = 0; s < 3; s++) {
                        if (bits.readBit() == 1) {
                            bits.readLiteral(8);
                        }
                    }
                }
            }
            bits.readBit();
            bits.readLiteral(6);
            bits.readLiteral(3);
            if (bits.readBit() == 1 && bits.readBit() == 1) {
                for (var i = 0; i < 8; i++) {
                    if (bits.readBit() == 1) {
                        bits.readLiteral(7);
                    }
                }
            }
            return bits.readLiteral(2);
        }
    }

    @Nested
    @DisplayName("target PSNR")
    class TargetPsnr {
        static double yuvPsnr(YuvPicture source, byte[] file) {
            var frame = java.util.Arrays.copyOfRange(file, 20, file.length);
            var decoded = Vp8.decodeKeyFrame(frame, 0, frame.length, MediaLimits.DEFAULT);
            var expected = planes(source);
            var actual = planes(decoded);
            return Quality.psnr(expected, actual);
        }

        static byte[] planes(YuvPicture picture) {
            var w = picture.width();
            var h = picture.height();
            var cw = (w + 1) / 2;
            var ch = (h + 1) / 2;
            var out = new byte[w * h + 2 * cw * ch];
            for (var y = 0; y < h; y++) {
                System.arraycopy(picture.y(), y * picture.yStride(), out, y * w, w);
            }
            for (var y = 0; y < ch; y++) {
                System.arraycopy(picture.u(), y * picture.uvStride(), out, w * h + y * cw, cw);
                System.arraycopy(picture.v(), y * picture.uvStride(), out, w * h + cw * ch + y * cw, cw);
            }
            return out;
        }

        @ParameterizedTest(name = "{0} dB, method {1}")
        @MethodSource("targets")
        @DisplayName("converges to the target, measured on the unfiltered Y'CbCr")
        void converges(int target, int method) {
            var picture = Image.OPAQUE.picture();
            var source = YuvConverter.fromRgba(WebpTestSupport.tightRgba(picture), picture.width() * 4, picture.width(),
                    picture.height(), false, MediaLimits.DEFAULT);
            var options = WebpEncoderOptions.builder().method(method).filterStrength(0).targetPsnr(target).build();
            var psnr = yuvPsnr(source, WebpEncoder.encode(source, options));
            assertEquals(target, psnr, 1.0, "PSNR " + psnr + " for target " + target);
        }

        static Stream<Arguments> targets() {
            var list = new ArrayList<Arguments>();
            for (var target : new int[]{30, 34, 38, 42}) {
                for (var method : new int[]{0, 2, 4, 6}) {
                    list.add(Arguments.of(target, method));
                }
            }
            return list.stream();
        }

        @Test
        @DisplayName("grows the file as the target rises")
        void monotonic() {
            var picture = Image.OPAQUE.picture();
            var previous = 0;
            for (var target : new int[]{30, 34, 38, 42, 46}) {
                var file = WebpEncoder.encode(picture, WebpEncoderOptions.builder().targetPsnr(target).build());
                assertTrue(file.length > previous, target + " dB: " + file.length + " <= " + previous);
                previous = file.length;
            }
        }

        @Test
        @DisplayName("passes without a target refine the probabilities and keep the quality")
        void passesWithoutTarget() {
            var picture = Image.OPAQUE.picture();
            var one = WebpEncoder.encode(picture, WebpEncoderOptions.defaults());
            var three = WebpEncoder.encode(picture, WebpEncoderOptions.builder().passes(3).build());
            assertEquals(one.length, three.length, 0.05 * one.length);
        }
    }

    @Nested
    @DisplayName("near-lossless")
    class NearLossless {
        static Stream<Arguments> nearLosslessModes() {
            var list = new ArrayList<Arguments>();
            for (var image : new Image[]{Image.OPAQUE, Image.ALPHA, Image.PALETTE, Image.NOISE, Image.NOISE_ALPHA}) {
                for (var level : new int[]{0, 20, 40, 60, 80, 99}) {
                    list.add(Arguments.of(image, "-near_lossless " + level,
                            WebpEncoderOptions.lossless().toBuilder().nearLossless(level).build()));
                }
                list.add(Arguments.of(image, "-near_lossless 40 -m 6 -q 100",
                        WebpEncoderOptions.lossless().toBuilder().nearLossless(40).method(6).quality(100).build()));
                list.add(Arguments.of(image, "-near_lossless 60 -exact",
                        WebpEncoderOptions.lossless().toBuilder().nearLossless(60).exact(true).build()));
                list.add(Arguments.of(image, "-near_lossless 50 -m 0",
                        WebpEncoderOptions.lossless().toBuilder().nearLossless(50).method(0).build()));
                list.add(Arguments.of(image, "-near_lossless 20 -m 1",
                        WebpEncoderOptions.lossless().toBuilder().nearLossless(20).method(1).build()));
                list.add(Arguments.of(image, "-near_lossless 10 -m 5 -q 80",
                        WebpEncoderOptions.lossless().toBuilder().nearLossless(10).method(5).quality(80).build()));
            }
            list.add(Arguments.of(Image.LARGE, "-near_lossless 30",
                    WebpEncoderOptions.lossless().toBuilder().nearLossless(30).build()));
            return list.stream();
        }

        @ParameterizedTest(name = "{0} cwebp {1}")
        @MethodSource("nearLosslessModes")
        @DisplayName("writes cwebp's bytes")
        void sameAsCwebp(Image image, String flags, WebpEncoderOptions options) {
            assertSameAsCwebp(image, flags, options);
        }

        @ParameterizedTest(name = "{0} level {1}")
        @MethodSource("preprocessingCases")
        @DisplayName("codes noise without a predictor, so the pixel preprocessing runs, and changes the pixels")
        void preprocessingPath(Image image, int level) {
            var picture = image.picture();
            for (var exact : new boolean[]{false, true}) {
                var options = WebpEncoderOptions.lossless().toBuilder().exact(exact).nearLossless(level).build();
                var file = WebpEncoder.encode(picture, options);
                assertTrue(withoutPredictor(file), image + " level " + level + " exact " + exact);
                assertFalse(java.util.Arrays.equals(WebpTestSupport.tightRgba(picture),
                        WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(file))));
            }
        }

        static Stream<Arguments> preprocessingCases() {
            var list = new ArrayList<Arguments>();
            for (var image : new Image[]{Image.NOISE, Image.NOISE_ALPHA}) {
                for (var level : new int[]{0, 40, 80, 99}) {
                    list.add(Arguments.of(image, level));
                }
            }
            return list.stream();
        }

        static Stream<Arguments> boundCases() {
            var list = new ArrayList<Arguments>();
            for (var image : new Image[]{Image.ALPHA, Image.NOISE_ALPHA}) {
                for (var level : new int[]{0, 20, 40, 60, 80, 99}) {
                    list.add(Arguments.of(image, level));
                }
            }
            return list.stream();
        }

        // Not exact, so the predictor residuals are quantized too; the color of fully transparent pixels is then free
        @ParameterizedTest(name = "{0} level {1}")
        @MethodSource("boundCases")
        @DisplayName("bounds every channel's change by the level's step and keeps the border and binary alpha")
        void errorBound(Image image, int level) {
            var picture = image.picture();
            var source = WebpTestSupport.tightRgba(picture);
            var limit = 1 << (5 - level / 20);
            var width = picture.width();
            var height = picture.height();
            for (var method : new int[]{0, 1, 4, 6}) {
                var file = WebpEncoder.encode(picture, WebpEncoderOptions.lossless().toBuilder().method(method)
                        .nearLossless(level).build());
                var decoded = WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(file));
                var changed = 0;
                for (var y = 0; y < height; y++) {
                    for (var x = 0; x < width; x++) {
                        var p = 4 * (y * width + x);
                        var alpha = source[p + 3] & 0xFF;
                        if (alpha == 0) {
                            assertEquals(0, decoded[p + 3], "transparent pixel " + x + "," + y);
                            continue;
                        }
                        var border = x == 0 || y == 0 || x == width - 1 || y == height - 1;
                        // a translucent pixel quantized to alpha 0 is cleaned like any transparent pixel
                        var channels = decoded[p + 3] == 0 ? new int[]{3} : new int[]{0, 1, 2, 3};
                        for (var c : channels) {
                            var diff = Math.abs((source[p + c] & 0xFF) - (decoded[p + c] & 0xFF));
                            if (border) {
                                assertEquals(0, diff, "border pixel " + x + "," + y + " method " + method);
                            }
                            assertTrue(diff < limit, "pixel " + x + "," + y + " channel " + c + " differs by " + diff
                                    + " at method " + method);
                            changed += diff;
                        }
                        if (alpha == 255) {
                            assertEquals(alpha, decoded[p + 3] & 0xFF, "binary alpha at " + x + "," + y);
                        }
                    }
                }
                // method 0 predicts with exact residuals and the predictor stops the preprocessing
                if (method != 0) {
                    assertTrue(changed > 0, "no pixel changed at method " + method);
                }
            }
        }

        @Test
        @DisplayName("level 100 is plain lossless coding and level 0 shrinks a noisy picture")
        void levels() {
            var picture = Image.OPAQUE.picture();
            var lossless = WebpEncoder.encode(picture, WebpEncoderOptions.lossless());
            assertArrayEquals(lossless, WebpEncoder.encode(picture, WebpEncoderOptions.lossless().toBuilder()
                    .nearLossless(100).build()));
            var noisy = WebpLosslessEncoderTest.random(200, 150, 4, false);
            var exact = WebpEncoder.encode(noisy, WebpEncoderOptions.lossless());
            var strongest = WebpEncoder.encode(noisy, WebpEncoderOptions.lossless().toBuilder().nearLossless(0).build());
            assertTrue(strongest.length < exact.length * 0.8, strongest.length + " vs " + exact.length);
        }

        @Test
        @DisplayName("lossy coding ignores the near-lossless level")
        void lossyIgnores() {
            var picture = Image.OPAQUE.picture();
            assertArrayEquals(WebpEncoder.encode(picture, WebpEncoderOptions.defaults()),
                    WebpEncoder.encode(picture, WebpEncoderOptions.builder().nearLossless(20).build()));
        }
    }

    @Nested
    @DisplayName("conversion")
    class Conversion {
        /** A pattern of saturated color edges, where averaged chroma bleeds. */
        static RgbaPicture edges() {
            var width = 96;
            var height = 64;
            var rgba = new byte[width * height * 4];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    var p = 4 * (y * width + x);
                    var stripe = ((x / 3) + (y / 5)) % 3;
                    rgba[p] = (byte) (stripe == 0 ? 255 : 0);
                    rgba[p + 1] = (byte) (stripe == 1 ? 255 : 0);
                    rgba[p + 2] = (byte) (stripe == 2 ? 255 : 30);
                    rgba[p + 3] = (byte) 255;
                }
            }
            return RgbaPicture.of(width, height, width * 4, rgba, false);
        }

        @Test
        @DisplayName("sharp conversion reconstructs saturated edges more faithfully than averaging")
        void sharpIsSharper() {
            var picture = edges();
            var source = WebpTestSupport.tightRgba(picture);
            var options = WebpEncoderOptions.builder().quality(100).method(4).build();
            var plain = WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(WebpEncoder.encode(picture, options)));
            var sharp = WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(WebpEncoder.encode(picture,
                    options.toBuilder().sharpYuv(true).build())));
            var plainPsnr = Quality.psnr(source, plain);
            var sharpPsnr = Quality.psnr(source, sharp);
            assertTrue(sharpPsnr > plainPsnr, "sharp " + sharpPsnr + " dB vs plain " + plainPsnr + " dB");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(Preset.class)
        @DisplayName("the presets are deterministic and decode cleanly through dwebp")
        void presetsDecode(Preset preset) {
            var picture = Image.ALPHA.picture();
            var options = WebpEncoderOptions.forPreset(preset);
            var file = WebpEncoder.encode(picture, options);
            assertArrayEquals(file, WebpEncoder.encode(picture, options));
            var path = WebpTestSupport.scratch("modes-preset-" + preset + ".webp", file);
            assertArrayEquals(WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(file)), WebpTestSupport.dwebpRgba(path));
            assertNotNull(WebpDecoder.probe(MediaSource.of(file)));
        }
    }

    @Nested
    @DisplayName("tiny and odd sizes")
    class TinySizes {
        static Stream<Arguments> tinyCases() {
            var modes = List.of(
                    lossy("-af -m 2", b -> b.autoFilter(true).method(2)),
                    lossy("-af -nostrong -m 4", b -> b.autoFilter(true).simpleFilter(true)),
                    lossy("-psnr 40 -m 0", b -> b.targetPsnr(40).method(0)),
                    lossy("-psnr 44 -m 6", b -> b.targetPsnr(44).method(6)),
                    lossy("-pass 3 -m 5", b -> b.passes(3).method(5)),
                    lossy("-sharp_yuv", b -> b.sharpYuv(true)),
                    preset("-preset photo -q 30", Preset.PHOTO, b -> b.quality(30)),
                    preset("-preset text -segments 1", Preset.TEXT, b -> b.segments(1)),
                    Arguments.of("-near_lossless 0", WebpEncoderOptions.lossless().toBuilder().nearLossless(0).build()),
                    Arguments.of("-near_lossless 20 -m 6 -q 100",
                            WebpEncoderOptions.lossless().toBuilder().nearLossless(20).method(6).quality(100).build()));
            var list = new ArrayList<Arguments>();
            for (var size : new int[][]{{1, 1}, {2, 3}, {17, 1}, {1, 17}, {5, 4}, {33, 5}, {70, 3}}) {
                for (var mode : modes) {
                    list.add(Arguments.of(size[0], size[1], mode.get()[0], mode.get()[1]));
                }
            }
            return list.stream();
        }

        @ParameterizedTest(name = "{0}x{1} cwebp {2}")
        @MethodSource("tinyCases")
        @DisplayName("write cwebp's bytes for random pictures of one to a few macroblocks")
        void sameAsCwebp(int width, int height, String flags, WebpEncoderOptions options) {
            var picture = WebpLosslessEncoderTest.random(width, height, width * 100L + height, true);
            var input = png("modes-tiny-" + width + "x" + height, picture);
            assertSameAsCwebp(width + "x" + height, input, picture, flags, options);
            var file = WebpEncoder.encode(picture, options);
            var decoded = WebpTestSupport.decodeRgba(file);
            assertEquals(width, decoded.width());
            assertEquals(height, decoded.height());
        }
    }

    @Test
    @DisplayName("tiny pictures fall back to the plain conversion under sharp conversion")
    void tinySharp() {
        var rgba = new byte[3 * 7 * 4];
        new java.util.Random(5).nextBytes(rgba);
        var picture = RgbaPicture.of(3, 7, 12, rgba, false);
        assertArrayEquals(WebpEncoder.encode(picture, WebpEncoderOptions.defaults()),
                WebpEncoder.encode(picture, WebpEncoderOptions.builder().sharpYuv(true).build()));
    }
}
