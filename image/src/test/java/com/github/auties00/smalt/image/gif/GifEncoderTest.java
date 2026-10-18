package com.github.auties00.smalt.image.gif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageEncoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.Images;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.Dithering;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.QuantizeMethod;
import com.github.auties00.smalt.image.png.PngDecoder;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the GIF encoders: exact round trips through Smalt's GIF decoder for palette-exact input
 * (stills and animations, with transparency, disposal and interlacing), bounded error for
 * quantized and dithered input, ffmpeg's decoder as an independent oracle (it must decode every
 * file without a warning and show the same pixels as Smalt's), the datastream structure (color
 * tables, looping extension, delays), and edge cases.
 */
class GifEncoderTest {
    record Frame(byte[] pixels, int durationMillis) {
    }

    static RgbaPicture picture(int width, int height, int[] colors, boolean alpha) {
        var picture = RgbaPicture.allocate(width, height, alpha);
        for (var i = 0; i < colors.length; i++) {
            var y = i / width;
            var x = i % width;
            var offset = y * picture.stride() + x * 4;
            var color = colors[i];
            picture.pixels()[offset] = (byte) color;
            picture.pixels()[offset + 1] = (byte) (color >>> 8);
            picture.pixels()[offset + 2] = (byte) (color >>> 16);
            picture.pixels()[offset + 3] = (byte) (color >>> 24);
        }
        return picture;
    }

    /** Returns the tight RGBA a GIF decoder shows for a picture: alpha below 128 is transparent black. */
    static byte[] binary(RgbaPicture picture) {
        var tight = RasterTesting.tight(picture);
        for (var i = 0; i < tight.length; i += 4) {
            if (picture.hasAlpha() && (tight[i + 3] & 0xFF) < 128) {
                Arrays.fill(tight, i, i + 4, (byte) 0);
            } else {
                tight[i + 3] = (byte) 0xFF;
            }
        }
        return tight;
    }

    static List<Frame> frames(byte[] gif) {
        try (var reader = GifDecoder.openAnimation(MediaSource.of(gif), ImageDecoderOptions.defaults())) {
            var list = new ArrayList<Frame>();
            for (var frame = reader.read(); frame != null; frame = reader.read()) {
                list.add(new Frame(RasterTesting.tight(frame.picture()), frame.durationMillis()));
            }
            return list;
        }
    }

    static int loopCount(byte[] gif) {
        try (var reader = GifDecoder.openAnimation(MediaSource.of(gif), ImageDecoderOptions.defaults())) {
            return reader.loopCount();
        }
    }

    static int[] palettePattern(int width, int height, int colors, boolean transparent, long seed) {
        var random = new Random(seed);
        var palette = new int[colors];
        for (var i = 0; i < colors; i++) {
            palette[i] = random.nextInt(1 << 24) | 0xFF00_0000;
        }
        if (transparent) {
            palette[0] = 0;
        }
        var pixels = new int[width * height];
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = palette[(i / 5 + random.nextInt(3)) % colors];
        }
        return pixels;
    }

    static RgbaPicture natural(int width, int height) {
        var png = Fixtures.testImage("png", width, height, List.of("-pix_fmt", "rgb24"));
        var picture = PngDecoder.decode(MediaSource.of(Fixtures.read(png))).picture();
        return picture instanceof RgbaPicture rgba ? rgba : ImageOps.toRgba(picture);
    }

    static List<RgbaPicture> video(int width, int height, int count) {
        var raw = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-f", "lavfi", "-i",
                "testsrc2=size=" + width + "x" + height + ":rate=15", "-frames:v", Integer.toString(count),
                "-f", "rawvideo", "-pix_fmt", "rgba", "-"));
        var frames = new ArrayList<RgbaPicture>();
        var size = width * height * 4;
        for (var i = 0; i < count; i++) {
            var picture = RgbaPicture.allocate(width, height, false);
            for (var y = 0; y < height; y++) {
                System.arraycopy(raw, i * size + y * width * 4, picture.pixels(), y * picture.stride(), width * 4);
            }
            frames.add(picture);
        }
        return frames;
    }

    static void assertFfmpegAgrees(byte[] gif, List<Frame> smalt, int width, String name) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = RasterTesting.write(name + ".gif", gif);
        Oracle.assertDecodesCleanly(file);
        var raw = RasterTesting.ffmpegFrames(file, "rgba");
        var size = smalt.getFirst().pixels().length;
        assertEquals(smalt.size() * size, raw.length, name + ": ffmpeg frame count");
        for (var i = 0; i < smalt.size(); i++) {
            RasterTesting.assertSameVisible(smalt.get(i).pixels(), Arrays.copyOfRange(raw, i * size, (i + 1) * size),
                    width, name + " frame " + i + " (ffmpeg vs Smalt)");
        }
    }

    /** Returns the number of entries of the global color table, or 0 when there is none. */
    static int globalTableSize(byte[] gif) {
        var packed = gif[10] & 0xFF;
        return (packed & 0x80) == 0 ? 0 : 2 << (packed & 7);
    }

    /** Counts image descriptors that carry a local color table, walking the block structure. */
    static int localTables(byte[] gif) {
        var position = 13 + 3 * globalTableSize(gif);
        var count = 0;
        while (gif[position] != 0x3B) {
            if (gif[position] == 0x21) {
                position += 2;
                while (gif[position] != 0) {
                    position += 1 + (gif[position] & 0xFF);
                }
                position++;
            } else {
                var packed = gif[position + 9] & 0xFF;
                position += 10;
                if ((packed & 0x80) != 0) {
                    count++;
                    position += 3 * (2 << (packed & 7));
                }
                position++;
                while (gif[position] != 0) {
                    position += 1 + (gif[position] & 0xFF);
                }
                position++;
            }
        }
        return count;
    }

    @Nested
    @DisplayName("still images")
    class Stills {
        @ParameterizedTest(name = "{0}x{1}, {2} colors, transparency {3}, interlaced {4}")
        @CsvSource({"1, 1, 1, false, false", "1, 1, 2, true, false", "7, 3, 2, false, true", "64, 48, 16, false, false",
                "64, 48, 255, true, false", "101, 77, 256, false, false", "33, 200, 200, false, true",
                "300, 9, 3, true, true", "2, 2, 4, false, true"})
        @DisplayName("palette-exact pictures round-trip exactly")
        void exact(int width, int height, int colors, boolean transparent, boolean interlaced) {
            var source = picture(width, height, palettePattern(width, height, colors, transparent, width * 7L + colors),
                    transparent);
            var gif = GifEncoder.encode(source, GifEncoderOptions.builder().interlaced(interlaced).build());
            var frames = frames(gif);
            assertEquals(1, frames.size());
            RasterTesting.assertSameVisible(binary(source), frames.getFirst().pixels(), width, "decoded");
            assertEquals(0, localTables(gif));
            assertFfmpegAgrees(gif, frames, width, "exact-" + width + "x" + height + "-" + colors);
        }

        @Test
        @DisplayName("lossless options accept exact pictures and reject what a GIF cannot hold")
        void lossless() {
            var exact = picture(16, 16, palettePattern(16, 16, 256, false, 3), false);
            RasterTesting.assertSameRgba(binary(exact), frames(GifEncoder.encode(exact, GifEncoderOptions.lossless()))
                    .getFirst().pixels(), 16, "lossless");
            var ramp = new int[32 * 32];
            for (var i = 0; i < ramp.length; i++) {
                ramp[i] = 0xFF00_0000 | i * 7;
            }
            var many = picture(32, 32, ramp, false);
            assertThrows(MediaException.Unsupported.class, () -> GifEncoder.encode(many, GifEncoderOptions.lossless()));
            var translucent = picture(2, 1, new int[]{0x80FF_0000, 0xFF00_FF00}, true);
            assertThrows(MediaException.Unsupported.class,
                    () -> GifEncoder.encode(translucent, GifEncoderOptions.lossless()));
        }

        static Stream<Arguments> quantizations() {
            var list = new ArrayList<Arguments>();
            for (var quantizer : QuantizeMethod.values()) {
                for (var dithering : Dithering.values()) {
                    list.add(Arguments.of(quantizer, dithering));
                }
            }
            return list.stream();
        }

        @ParameterizedTest(name = "{0}, {1}")
        @MethodSource("quantizations")
        @DisplayName("natural pictures quantize within an error bound and decode identically in ffmpeg")
        void quantized(QuantizeMethod quantizer, Dithering dithering) {
            var source = natural(320, 240);
            var gif = GifEncoder.encode(source, GifEncoderOptions.builder().method(quantizer).dithering(dithering)
                    .build());
            var frames = frames(gif);
            var psnr = Quality.psnr(binary(source), frames.getFirst().pixels());
            assertTrue(psnr > (dithering == Dithering.NONE ? 30 : 27), quantizer + " " + dithering + ": " + psnr + " dB");
            assertEquals(256, globalTableSize(gif));
            assertFfmpegAgrees(gif, frames, 320, "quantized-" + quantizer + "-" + dithering);
        }

        @ParameterizedTest(name = "{0} colors")
        @ValueSource(ints = {2, 3, 16, 64, 255})
        @DisplayName("the palette never exceeds the requested size")
        void maxColors(int colors) {
            var gif = GifEncoder.encode(natural(96, 64), GifEncoderOptions.builder().maxColors(colors).build());
            var table = globalTableSize(gif);
            assertTrue(table >= colors / 2 && table <= Math.max(2, Integer.highestOneBit(colors - 1) << 1),
                    "table of " + table + " for " + colors);
            var distinct = Arrays.stream(toColors(frames(gif).getFirst().pixels())).distinct().count();
            assertTrue(distinct <= colors, distinct + " colors");
        }

        @Test
        @DisplayName("planar pictures are converted before encoding")
        void planar() {
            var yuv = YuvPicture.allocate(4, 4, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
            var frames = frames(GifEncoder.encode(yuv));
            assertEquals(1, frames.size());
            assertEquals(64, frames.getFirst().pixels().length);
        }

        @Test
        @DisplayName("fully transparent and single-color pictures encode")
        void degenerate() {
            var empty = picture(5, 5, new int[25], true);
            RasterTesting.assertSameVisible(binary(empty), frames(GifEncoder.encode(empty)).getFirst().pixels(), 5, "empty");
            var flat = new int[40 * 30];
            Arrays.fill(flat, 0xFF12_3456);
            var single = picture(40, 30, flat, false);
            RasterTesting.assertSameRgba(binary(single), frames(GifEncoder.encode(single)).getFirst().pixels(), 40, "flat");
        }
    }

    static int[] toColors(byte[] tight) {
        var colors = new int[tight.length / 4];
        for (var i = 0; i < colors.length; i++) {
            colors[i] = (tight[i * 4] & 0xFF) | (tight[i * 4 + 1] & 0xFF) << 8 | (tight[i * 4 + 2] & 0xFF) << 16
                    | (tight[i * 4 + 3] & 0xFF) << 24;
        }
        return colors;
    }

    @Nested
    @DisplayName("animations")
    class Animations {
        /** A sprite moving over a static background, with frames that turn painted pixels transparent. */
        static List<RgbaPicture> sprites(int width, int height, int count, boolean holes) {
            var background = palettePattern(width, height, 12, false, 99);
            var frames = new ArrayList<RgbaPicture>();
            for (var i = 0; i < count; i++) {
                var pixels = background.clone();
                for (var y = 0; y < 6; y++) {
                    for (var x = 0; x < 6; x++) {
                        var px = (i * 3 + x) % width;
                        var py = (i * 2 + y) % height;
                        pixels[py * width + px] = 0xFF00_00FF + (i % 3) * 0x100;
                    }
                }
                if (holes && i % 2 == 1) {
                    for (var y = 0; y < height / 3; y++) {
                        Arrays.fill(pixels, y * width, y * width + width / 2, 0);
                    }
                }
                frames.add(picture(width, height, pixels, holes));
            }
            return frames;
        }

        @ParameterizedTest(name = "holes {0}, palette {1}")
        @CsvSource({"false, AUTO", "true, AUTO", "true, LOCAL", "false, LOCAL"})
        @DisplayName("palette-exact animations round-trip frame by frame, delays and loops included")
        void exact(boolean holes, GifEncoderOptions.PaletteMode mode) {
            var source = sprites(48, 32, 12, holes);
            var encoder = new GifAnimationEncoder(48, 32, GifEncoderOptions.builder().paletteMode(mode).loopCount(3)
                    .build());
            for (var i = 0; i < source.size(); i++) {
                encoder.addFrame(source.get(i), 5 + i);
            }
            var gif = encoder.finish();
            var frames = frames(gif);
            assertEquals(source.size(), frames.size());
            for (var i = 0; i < source.size(); i++) {
                RasterTesting.assertSameVisible(binary(source.get(i)), frames.get(i).pixels(), 48, "frame " + i);
                assertEquals((5 + i) * 10, frames.get(i).durationMillis(), "delay of frame " + i);
            }
            assertEquals(3, loopCount(gif));
            assertFfmpegAgrees(gif, frames, 48, "anim-" + holes + "-" + mode);
        }

        @Test
        @DisplayName("identical frames merge their delays, up to the 16-bit limit")
        void merges() {
            var a = picture(4, 4, palettePattern(4, 4, 3, false, 1), false);
            var b = picture(4, 4, palettePattern(4, 4, 3, false, 2), false);
            var encoder = new GifAnimationEncoder(4, 4);
            encoder.addFrame(a, 10);
            encoder.addFrame(a, 20);
            encoder.addFrame(b, 7);
            encoder.addFrame(b, 65_000);
            encoder.addFrame(b, 600);
            var frames = frames(encoder.finish());
            assertEquals(List.of(300, 650_070, 6000), frames.stream().map(Frame::durationMillis).toList());
            RasterTesting.assertSameRgba(frames.get(1).pixels(), frames.get(2).pixels(), 4, "padding frame");
        }

        @ParameterizedTest(name = "{0} plays")
        @ValueSource(ints = {0, 1, 2, 10, 65_536})
        @DisplayName("the loop count round-trips")
        void loops(int plays) {
            var encoder = new GifAnimationEncoder(2, 2, GifEncoderOptions.builder().loopCount(plays).build());
            encoder.addFrame(picture(2, 2, new int[]{0xFF00_0000, 0xFFFF_FFFF, 0xFF00_0000, 0xFFFF_FFFF}, false), 10);
            encoder.addFrame(picture(2, 2, new int[]{0xFFFF_FFFF, 0xFF00_0000, 0xFF00_0000, 0xFFFF_FFFF}, false), 10);
            assertEquals(plays, loopCount(encoder.finish()));
        }

        @Test
        @DisplayName("an animation of one distinct frame is a still image")
        void collapses() {
            var frame = picture(3, 3, palettePattern(3, 3, 2, false, 5), false);
            var encoder = new GifAnimationEncoder(3, 3);
            encoder.addFrame(frame, 10);
            encoder.addFrame(frame, 10);
            var gif = encoder.finish();
            assertArrayEquals(GifEncoder.encode(frame), gif);
        }

        @Test
        @DisplayName("all-transparent frames and frames that clear everything round-trip")
        void transparentFrames() {
            var opaque = picture(6, 6, palettePattern(6, 6, 5, false, 6), true);
            var clear = picture(6, 6, new int[36], true);
            var encoder = new GifAnimationEncoder(6, 6);
            encoder.addFrame(clear, 1);
            encoder.addFrame(opaque, 2);
            encoder.addFrame(clear, 3);
            encoder.addFrame(opaque, 4);
            var gif = encoder.finish();
            var frames = frames(gif);
            assertEquals(4, frames.size());
            for (var i = 0; i < 4; i++) {
                RasterTesting.assertSameVisible(binary(i % 2 == 0 ? clear : opaque), frames.get(i).pixels(), 6, "frame " + i);
            }
            assertFfmpegAgrees(gif, frames, 6, "transparent-frames");
        }

        @Test
        @DisplayName("natural video quantizes within an error bound and decodes identically in ffmpeg")
        void video() {
            var source = GifEncoderTest.video(160, 120, 20);
            var encoder = new GifAnimationEncoder(160, 120);
            for (var frame : source) {
                encoder.addFrame(frame, 7);
            }
            var gif = encoder.finish();
            var frames = frames(gif);
            assertEquals(source.size(), frames.size());
            for (var i = 0; i < source.size(); i++) {
                var psnr = Quality.psnr(binary(source.get(i)), frames.get(i).pixels());
                assertTrue(psnr > 26, "frame " + i + ": " + psnr + " dB");
            }
            assertFfmpegAgrees(gif, frames, 160, "video");
        }

        @Test
        @DisplayName("tens of thousands of tiny frames encode and decode")
        void manyFrames() {
            assertTimeoutPreemptively(Duration.ofMinutes(2), () -> {
                var black = picture(1, 1, new int[]{0xFF00_0000}, false);
                var white = picture(1, 1, new int[]{0xFFFF_FFFF}, false);
                var encoder = new GifAnimationEncoder(1, 1);
                for (var i = 0; i < 70_000; i++) {
                    encoder.addFrame(i % 2 == 0 ? black : white, 1);
                }
                var gif = encoder.finish();
                try (var reader = GifDecoder.openAnimation(MediaSource.of(gif),
                        ImageDecoderOptions.builder().limits(com.github.auties00.smalt.MediaLimits.DEFAULT).build())) {
                    var count = 0;
                    while (reader.read() != null) {
                        count++;
                    }
                    assertEquals(70_000, count);
                }
            });
        }

        @Test
        @DisplayName("misuse is rejected")
        void misuse() {
            var encoder = new GifAnimationEncoder(2, 2);
            assertThrows(IllegalStateException.class, encoder::finish);
            assertThrows(IllegalArgumentException.class, () -> encoder.addFrame(RgbaPicture.allocate(3, 2, false), 1));
            assertThrows(IllegalArgumentException.class, () -> encoder.addFrame(RgbaPicture.allocate(2, 2, false),
                    65_536));
            assertThrows(IllegalArgumentException.class, () -> encoder.addFrame(RgbaPicture.allocate(2, 2, false), -1));
            encoder.addFrame(RgbaPicture.allocate(2, 2, false), 1);
            encoder.finish();
            assertThrows(IllegalStateException.class, () -> encoder.addFrame(RgbaPicture.allocate(2, 2, false), 1));
            assertThrows(IllegalStateException.class, encoder::finish);
            assertThrows(IllegalArgumentException.class, () -> new GifAnimationEncoder(0, 1));
            assertThrows(MediaException.LimitExceeded.class, () -> new GifAnimationEncoder(65_536, 1));
            // 65535 x 65535 fits the GIF fields but not the encoder's int-indexed canvas buffers
            assertThrows(MediaException.LimitExceeded.class, () -> new GifAnimationEncoder(65_535, 65_535));
        }

        @Test
        @DisplayName("palette modes decide where color tables go")
        void paletteModes() {
            var source = GifEncoderTest.video(64, 48, 8);
            var locals = new java.util.EnumMap<GifEncoderOptions.PaletteMode, Integer>(GifEncoderOptions.PaletteMode.class);
            for (var mode : GifEncoderOptions.PaletteMode.values()) {
                var encoder = new GifAnimationEncoder(64, 48, GifEncoderOptions.builder().paletteMode(mode)
                        .method(QuantizeMethod.MEDIAN_CUT).build());
                for (var frame : source) {
                    encoder.addFrame(frame, 10);
                }
                var gif = encoder.finish();
                locals.put(mode, localTables(gif));
                assertFfmpegAgrees(gif, frames(gif), 64, "modes-" + mode);
            }
            assertEquals(0, locals.get(GifEncoderOptions.PaletteMode.GLOBAL), locals.toString());
            assertTrue(locals.get(GifEncoderOptions.PaletteMode.LOCAL) >= locals.get(GifEncoderOptions.PaletteMode.AUTO),
                    locals.toString());
        }

        @Test
        @DisplayName("the global palette mode codes new colors with the first frame's palette")
        void globalMode() {
            var source = sprites(48, 32, 6, true);
            var encoder = new GifAnimationEncoder(48, 32, GifEncoderOptions.defaults()
                    .toBuilder().paletteMode(GifEncoderOptions.PaletteMode.GLOBAL).build());
            for (var frame : source) {
                encoder.addFrame(frame, 10);
            }
            var gif = encoder.finish();
            assertEquals(0, localTables(gif));
            var frames = frames(gif);
            for (var i = 0; i < source.size(); i++) {
                assertTrue(Quality.psnr(binary(source.get(i)), frames.get(i).pixels()) > 30, "frame " + i);
            }
        }
    }

    @Nested
    @DisplayName("registry and options")
    class Registry {
        @Test
        @DisplayName("Images encodes GIF with the default options, quality limiting the palette")
        void encodes() {
            var source = natural(64, 48);
            assertTrue(Images.isEncodable(ImageFormat.GIF));
            assertArrayEquals(GifEncoder.encode(source), Images.encode(source, ImageFormat.GIF));
            var small = Images.encode(source, ImageFormat.GIF, ImageEncoderOptions.ofQuality(10));
            assertTrue(globalTableSize(small) <= 32, globalTableSize(small) + " entries");
            var ramp = new int[32 * 32];
            for (var i = 0; i < ramp.length; i++) {
                ramp[i] = 0xFF00_0000 | i * 7;
            }
            assertThrows(MediaException.Unsupported.class,
                    () -> Images.encode(picture(32, 32, ramp, false), ImageFormat.GIF, ImageEncoderOptions.lossless()));
        }

        @Test
        @DisplayName("the animation writer rounds end times, so delays never drift")
        void writer() {
            var sink = MediaSink.memory();
            try (var writer = Images.openAnimationWriter(ImageFormat.GIF, 2, 1,
                    ImageEncoderOptions.builder().loopCount(5).build(), sink)) {
                for (var i = 0; i < 9; i++) {
                    writer.write(picture(2, 1, new int[]{0xFF00_0000 | i * 20, 0xFFFF_FFFF}, false), 33_333);
                }
                writer.finish();
            }
            var gif = sink.toByteArray();
            assertEquals(List.of(30, 40, 30, 30, 40, 30, 30, 40, 30),
                    frames(gif).stream().map(Frame::durationMillis).toList());
            assertEquals(5, loopCount(gif));
        }

        @Test
        @DisplayName("the animation writer rejects what GIF cannot store")
        void rejects() {
            assertTrue(Images.isAnimationEncodable(ImageFormat.GIF));
            assertThrows(IllegalArgumentException.class, () -> Images.openAnimationWriter(ImageFormat.GIF, 2, 2,
                    ImageEncoderOptions.builder().loopCount(65_537).build(), MediaSink.memory()));
            assertThrows(MediaException.LimitExceeded.class, () -> Images.openAnimationWriter(ImageFormat.GIF, 70_000, 2,
                    MediaSink.memory()));
            try (var writer = Images.openAnimationWriter(ImageFormat.GIF, 2, 2, MediaSink.memory())) {
                assertThrows(IllegalArgumentException.class, () -> writer.write(RgbaPicture.allocate(2, 2, false),
                        656_000_000));
                assertThrows(IllegalArgumentException.class, () -> writer.write(RgbaPicture.allocate(2, 2, false), -1));
                assertThrows(IllegalStateException.class, writer::finish);
            }
        }

        @Test
        @DisplayName("options are immutable values with validated fields")
        void options() {
            var options = GifEncoderOptions.builder().method(QuantizeMethod.NEUQUANT).sampleFactor(1)
                    .maxColors(17).dithering(Dithering.ORDERED).alphaThreshold(0)
                    .paletteMode(GifEncoderOptions.PaletteMode.LOCAL).interlaced(true).loopCount(9).lossless(false)
                    .build();
            assertEquals(options, options.toBuilder().build());
            assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
            assertEquals(options, GifEncoderOptions.builder().method(QuantizeMethod.NEUQUANT)
                    .sampleFactor(1).maxColors(17).dithering(Dithering.ORDERED)
                    .alphaThreshold(0).paletteMode(GifEncoderOptions.PaletteMode.LOCAL).interlaced(true)
                    .loopCount(9).build());
            assertTrue(GifEncoderOptions.lossless().isLossless());
            assertTrue(options.toString().contains("maxColors=17"));
            assertThrows(IllegalArgumentException.class, () -> GifEncoderOptions.builder().maxColors(1).build());
            assertThrows(IllegalArgumentException.class, () -> GifEncoderOptions.builder().maxColors(257).build());
            assertThrows(IllegalArgumentException.class, () -> GifEncoderOptions.builder().sampleFactor(31).build());
            assertThrows(IllegalArgumentException.class, () -> GifEncoderOptions.builder().alphaThreshold(256).build());
            assertThrows(IllegalArgumentException.class, () -> GifEncoderOptions.builder().loopCount(65_537).build());
            assertThrows(NullPointerException.class, () -> GifEncoderOptions.builder().dithering(null).build());
        }

        @Test
        @DisplayName("an alpha threshold of 0 makes every pixel opaque")
        void threshold() {
            var source = picture(2, 1, new int[]{0x0100_00FF, 0x0000_FF00}, true);
            var shown = frames(GifEncoder.encode(source, GifEncoderOptions.builder().alphaThreshold(0).build()))
                    .getFirst().pixels();
            assertArrayEquals(new byte[]{(byte) 0xFF, 0, 0, (byte) 0xFF, 0, (byte) 0xFF, 0, (byte) 0xFF}, shown);
        }
    }
}
