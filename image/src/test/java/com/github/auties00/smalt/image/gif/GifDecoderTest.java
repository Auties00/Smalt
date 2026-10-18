package com.github.auties00.smalt.image.gif;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.ImageFormat;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
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

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers GIF decoding: ffmpeg-written animations compared frame by frame with ffmpeg's decoder,
 * and hand-built files (via a test-only writer) for LZW edge cases, interlacing, transparency,
 * every disposal method, clipping, zero sizes, damaged data, extensions and limits, where the
 * expected canvas is computed independently from the browser rules.
 */
class GifDecoderTest {
    private static final ImageDecoderOptions OPTIONS = ImageDecoderOptions.defaults();
    private static final int[] PALETTE = {0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF, 0x000000, 0x808080, 0x123456, 0xABCDEF};

    record Frame(byte[] pixels, int duration) {
        int pixel(int width, int x, int y) {
            var i = (y * width + x) * 4;
            return (pixels[i] & 0xFF) | (pixels[i + 1] & 0xFF) << 8 | (pixels[i + 2] & 0xFF) << 16 | (pixels[i + 3] & 0xFF) << 24;
        }
    }

    static List<Frame> frames(byte[] data) {
        try (var reader = GifDecoder.openAnimation(MediaSource.of(data), OPTIONS)) {
            return frames(reader);
        }
    }

    static List<Frame> frames(AnimationReader reader) {
        var list = new ArrayList<Frame>();
        for (var current = reader.read(); current != null; current = reader.read()) {
            list.add(new Frame(RasterTesting.tight(current.picture()), current.durationMillis()));
        }
        return list;
    }

    /** Returns the packed little-endian RGBA of a PALETTE entry. */
    static int rgba(int index) {
        var color = PALETTE[index];
        return (color >>> 16) | (color & 0xFF00) | (color & 0xFF) << 16 | 0xFF000000;
    }

    static byte[] pattern(int width, int height, int colors, long seed) {
        var random = new Random(seed);
        var indices = new byte[width * height];
        for (var i = 0; i < indices.length; i++) {
            indices[i] = (byte) ((i / 3 + random.nextInt(2)) % colors);
        }
        return indices;
    }

    /** Independent model of the browser rendering rules for hand-built files. */
    static final class Model {
        final int width;
        final int height;
        final int[] canvas;
        int[] saved;
        int[] previousRect;
        int previousDisposal;

        Model(int width, int height) {
            this.width = width;
            this.height = height;
            this.canvas = new int[width * height];
        }

        void draw(int left, int top, int w, int h, byte[] indices, int[] colors, int transparent, int disposal, int decoded) {
            if (previousRect != null) {
                for (var y = previousRect[1]; y < Math.min(height, previousRect[1] + previousRect[3]); y++) {
                    for (var x = previousRect[0]; x < Math.min(width, previousRect[0] + previousRect[2]); x++) {
                        if (previousDisposal == 2) {
                            canvas[y * width + x] = 0;
                        } else if (previousDisposal == 3 || previousDisposal == 4) {
                            canvas[y * width + x] = saved[y * width + x];
                        }
                    }
                }
            }
            saved = canvas.clone();
            for (var i = 0; i < decoded; i++) {
                var x = left + i % w;
                var y = top + i / w;
                var index = indices[i] & 0xFF;
                if (x < width && y < height && index != transparent && index < colors.length) {
                    var color = colors[index];
                    canvas[y * width + x] = (color >>> 16) | (color & 0xFF00) | (color & 0xFF) << 16 | 0xFF000000;
                }
            }
            previousRect = new int[]{left, top, w, h};
            previousDisposal = disposal;
        }

        byte[] bytes() {
            var out = new byte[width * height * 4];
            for (var i = 0; i < canvas.length; i++) {
                out[i * 4] = (byte) canvas[i];
                out[i * 4 + 1] = (byte) (canvas[i] >>> 8);
                out[i * 4 + 2] = (byte) (canvas[i] >>> 16);
                out[i * 4 + 3] = (byte) (canvas[i] >>> 24);
            }
            return out;
        }
    }

    @Nested
    @DisplayName("ffmpeg-written animations")
    class FfmpegWritten {
        static Stream<Arguments> variants() {
            var alpha = "format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='if(gt(X,20+N*3),255,0)'";
            return Stream.of(
                    Arguments.of("diff palette, bayer", "split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer", "0"),
                    Arguments.of("single palette, no dither", "split[a][b];[a]palettegen[p];[b][p]paletteuse=dither=none", "-1"),
                    Arguments.of("transparency", alpha + ",split[a][b];[a]palettegen=reserve_transparent=1[p];[b][p]paletteuse=alpha_threshold=128", "3"),
                    Arguments.of("new palette per frame", "split[a][b];[a]palettegen=stats_mode=single[p];[b][p]paletteuse=new=1", "0"),
                    Arguments.of("odd size", "crop=97:61:1:1,split[a][b];[a]palettegen=max_colors=17[p];[b][p]paletteuse", "5"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("variants")
        @DisplayName("matches ffmpeg frame by frame")
        void matches(String name, String filter, String loop) {
            var file = Fixtures.ffmpeg("gif", List.of("-f", "lavfi", "-i", "testsrc2=size=120x68:rate=10:duration=2"),
                    List.of("-filter_complex", filter, "-loop", loop));
            var data = Fixtures.read(file);
            var info = GifDecoder.probe(MediaSource.of(data));
            var expected = RasterTesting.ffmpegFrames(file, "rgba");
            try (var reader = GifDecoder.openAnimation(MediaSource.of(data), OPTIONS)) {
                var frames = frames(reader);
                var frameSize = reader.width() * reader.height() * 4;
                assertEquals(expected.length / frameSize, frames.size(), "frame count");
                assertEquals(info.frameCount(), frames.size());
                var repeats = Integer.parseInt(loop);
                assertEquals(repeats < 0 ? 1 : repeats == 0 ? 0 : repeats + 1, reader.loopCount());
                for (var i = 0; i < frames.size(); i++) {
                    // ffmpeg paints fully transparent pixels white, browsers (and this decoder) leave
                    // them transparent black: alpha is compared everywhere, color where visible
                    RasterTesting.assertSameVisible(Arrays.copyOfRange(expected, i * frameSize, (i + 1) * frameSize),
                            frames.get(i).pixels(), reader.width(), name + " frame " + i);
                    assertEquals(100, frames.get(i).duration());
                }
            }
        }
    }

    @Nested
    @DisplayName("LZW")
    class Lzw {
        @ParameterizedTest(name = "minimum code size {0}")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
        @DisplayName("decodes every minimum code size, growing codes to 12 bits and clearing when full")
        void codeSizes(int minimumCodeSize) {
            var colors = 1 << minimumCodeSize;
            var palette = new int[Math.max(2, colors)];
            for (var i = 0; i < palette.length; i++) {
                palette[i] = i * 0x010101;
            }
            var indices = new byte[200 * 150];
            var random = new Random(minimumCodeSize);
            for (var i = 0; i < indices.length; i++) {
                indices[i] = (byte) random.nextInt(colors);
            }
            for (var defer : new boolean[]{false, true}) {
                var data = GifFiles.create(200, 150, palette).image(0, 0, 200, 150, null, false, indices, minimumCodeSize, defer)
                        .trailer().bytes();
                var picture = (RgbaPicture) GifDecoder.decode(MediaSource.of(data), OPTIONS).picture();
                var pixels = picture.pixels();
                for (var i = 0; i < indices.length; i++) {
                    assertEquals(indices[i], pixels[i * 4], "pixel " + i + " defer " + defer);
                }
                assertFalse(picture.hasAlpha());
                if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                    var path = RasterTesting.write("lzw-" + minimumCodeSize + "-" + defer + ".gif", data);
                    RasterTesting.assertSameVisible(RasterTesting.ffmpegFrames(path, "rgba"), pixels, 200, "ffmpeg");
                }
            }
        }

        @Test
        @DisplayName("keeps decoding 12-bit codes after the table fills without a clear code")
        void deferredClear() {
            var indices = new byte[512 * 512];
            new Random(9).nextBytes(indices);
            var palette = new int[256];
            for (var i = 0; i < 256; i++) {
                palette[i] = i << 8;
            }
            var data = GifFiles.create(512, 512, palette).image(0, 0, 512, 512, null, false, indices, 8, true).trailer().bytes();
            var pixels = ((RgbaPicture) GifDecoder.decode(MediaSource.of(data), OPTIONS).picture()).pixels();
            for (var i = 0; i < indices.length; i++) {
                assertEquals(indices[i], pixels[i * 4 + 1], "pixel " + i);
            }
        }

        @Test
        @DisplayName("stops an image at a code past the table, keeping the pixels decoded so far")
        void invalidCode() {
            var lzw = new GifFiles.BitWriter().write(4, 3).write(1, 3).write(2, 3).write(3, 3).write(7, 3).write(1, 3).write(5, 3).bytes();
            var data = GifFiles.create(4, 2, new int[]{0xFF0000, 0x00FF00, 0x0000FF, 0xFFFFFF})
                    .imageData(0, 0, 4, 2, null, false, 2, lzw)
                    .image(0, 1, 4, 1, null, false, new byte[]{3, 3, 3, 3}, 2, false).trailer().bytes();
            var frames = frames(data);
            assertEquals(2, frames.size());
            var first = frames.getFirst();
            assertEquals(0xFF00FF00, first.pixel(4, 0, 0));
            assertEquals(0xFFFF0000, first.pixel(4, 1, 0));
            assertEquals(0xFFFFFFFF, first.pixel(4, 2, 0));
            assertEquals(0, first.pixel(4, 3, 0), "not decoded");
            assertEquals(0, first.pixel(4, 0, 1), "not decoded");
            assertEquals(0xFFFFFFFF, frames.get(1).pixel(4, 3, 1));
            var still = (RgbaPicture) GifDecoder.decode(MediaSource.of(data), OPTIONS).picture();
            assertTrue(still.hasAlpha());
        }

        @Test
        @DisplayName("handles the KwKwK case where a code refers to the entry being defined")
        void kwkwk() {
            var indices = new byte[]{1, 1, 1, 1, 1, 1, 1, 1, 1, 1};
            var data = GifFiles.create(10, 1, PALETTE).image(0, 0, 10, 1, null, false, indices, 3, false).trailer().bytes();
            var frame = frames(data).getFirst();
            for (var x = 0; x < 10; x++) {
                assertEquals(rgba(1), frame.pixel(10, x, 0));
            }
        }

        @ParameterizedTest(name = "minimum code size {0}")
        @ValueSource(ints = {0, 9, 12, 255})
        @DisplayName("draws nothing for an image with an unusable minimum code size")
        void unusableCodeSize(int size) {
            var data = GifFiles.create(2, 2, PALETTE).imageData(0, 0, 2, 2, null, false, size, new byte[]{1, 2, 3})
                    .image(0, 0, 2, 2, null, false, new byte[]{1, 1, 1, 1}, 3, false).trailer().bytes();
            var frames = frames(data);
            assertEquals(2, frames.size());
            assertEquals(0, frames.getFirst().pixel(2, 0, 0));
            assertEquals(rgba(1), frames.get(1).pixel(2, 1, 1));
        }

        @Test
        @DisplayName("ignores data after the image's pixels and stops at the end code")
        void excessData() {
            var lzw = GifFiles.lzw(new byte[]{1, 2, 3, 4, 5, 6, 7, 0, 1, 2}, 3, false);
            var data = GifFiles.create(3, 2, PALETTE).imageData(0, 0, 3, 2, null, false, 3, lzw).trailer().bytes();
            var frame = frames(data).getFirst();
            assertEquals(rgba(6), frame.pixel(3, 2, 1));
        }
    }

    @Nested
    @DisplayName("rendering")
    class Rendering {
        @ParameterizedTest(name = "alternating threads: {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("composites a large animation read ahead in groups exactly like the rendering model")
        void readAhead(boolean alternating) throws Exception {
            var width = 400;
            var height = 300;
            var random = new Random(77);
            var files = GifFiles.create(width, height, PALETTE).loop("NETSCAPE2.0", 0);
            var model = new Model(width, height);
            var expected = new ArrayList<byte[]>();
            var frames = 29;
            for (var i = 0; i < frames; i++) {
                var w = i == 0 ? width : 40 + random.nextInt(width - 40);
                var h = i == 0 ? height : 30 + random.nextInt(height - 30);
                var left = i == 0 ? 0 : random.nextInt(width - w + 1);
                var top = i == 0 ? 0 : random.nextInt(height - h + 1);
                var disposal = random.nextInt(4);
                var transparent = random.nextBoolean() ? random.nextInt(8) : -1;
                var indices = pattern(w, h, 8, 200 + i);
                files.control(disposal, i, transparent).image(left, top, w, h, null, false, indices, 3, i % 5 == 0);
                model.draw(left, top, w, h, indices, PALETTE, transparent, disposal, indices.length);
                expected.add(model.bytes());
            }
            var data = files.trailer().bytes();
            var actual = new ArrayList<byte[]>();
            var pool = java.util.concurrent.Executors.newSingleThreadExecutor();
            try (var reader = GifDecoder.openAnimation(MediaSource.of(data), OPTIONS)) {
                for (var n = 0; ; n++) {
                    var frame = alternating && (n & 1) == 1 ? pool.submit(reader::read).get() : reader.read();
                    if (frame == null) {
                        break;
                    }
                    actual.add(RasterTesting.tight(frame.picture()));
                    assertEquals(n, frame.durationMillis() / 10);
                }
            } finally {
                pool.shutdown();
            }
            assertEquals(frames, actual.size());
            for (var i = 0; i < frames; i++) {
                RasterTesting.assertSameRgba(expected.get(i), actual.get(i), width, "frame " + i);
            }
        }

        @Test
        @DisplayName("places interlaced rows for every height from 1 to 20")
        void interlaced() {
            for (var height = 1; height <= 20; height++) {
                var indices = pattern(7, height, 8, height);
                var data = GifFiles.create(7, height, PALETTE).image(0, 0, 7, height, null, true, indices, 3, false).trailer().bytes();
                var model = new Model(7, height);
                model.draw(0, 0, 7, height, indices, PALETTE, -1, 0, indices.length);
                var picture = (RgbaPicture) GifDecoder.decode(MediaSource.of(data), OPTIONS).picture();
                RasterTesting.assertSameRgba(model.bytes(), RasterTesting.tight(picture), 7, "height " + height);
                if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                    var path = RasterTesting.write("interlaced-" + height + ".gif", data);
                    RasterTesting.assertSameVisible(RasterTesting.ffmpegFrames(path, "rgba"), model.bytes(), 7, "ffmpeg height " + height);
                }
            }
        }

        @Test
        @DisplayName("applies every disposal method, transparency and local color tables like browsers")
        void disposal() {
            var local = new int[]{0x111111, 0x222222, 0x333333, 0x444444};
            var files = GifFiles.create(12, 10, PALETTE).loop("NETSCAPE2.0", 0);
            var model = new Model(12, 10);
            int[][] rects = {{0, 0, 12, 10}, {2, 2, 5, 4}, {4, 3, 6, 6}, {1, 1, 3, 3}, {6, 5, 6, 5}, {0, 0, 4, 4}, {8, 0, 4, 10}};
            int[] disposals = {1, 2, 3, 0, 4, 2, 1};
            int[] transparent = {-1, 3, 1, -1, 0, 2, -1};
            var expected = new ArrayList<byte[]>();
            for (var i = 0; i < rects.length; i++) {
                var rect = rects[i];
                var colors = i % 2 == 1 ? local : PALETTE;
                var indices = pattern(rect[2], rect[3], colors.length, 100 + i);
                files.control(disposals[i], i * 3, transparent[i])
                        .image(rect[0], rect[1], rect[2], rect[3], i % 2 == 1 ? local : null, i == 4, indices,
                                colors.length == 4 ? 2 : 3, false);
                model.draw(rect[0], rect[1], rect[2], rect[3], indices, colors, transparent[i], disposals[i], indices.length);
                expected.add(model.bytes());
            }
            var data = files.trailer().bytes();
            var frames = frames(data);
            assertEquals(rects.length, frames.size());
            for (var i = 0; i < frames.size(); i++) {
                RasterTesting.assertSameRgba(expected.get(i), frames.get(i).pixels(), 12, "frame " + i);
                assertEquals(i * 30, frames.get(i).duration());
            }
            var info = GifDecoder.probe(MediaSource.of(data));
            assertTrue(info.animated());
            assertTrue(info.hasAlpha());
            assertEquals(7, info.frameCount());
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var ffmpeg = RasterTesting.ffmpegFrames(RasterTesting.write("disposal.gif", data), "rgba");
                var size = 12 * 10 * 4;
                // frame 4 uses disposal method 4, which browsers read as restore-to-previous and ffmpeg
                // ignores, so ffmpeg's frames agree with the browser rules only up to frame 4
                for (var i = 0; i <= 4; i++) {
                    RasterTesting.assertSameVisible(Arrays.copyOfRange(ffmpeg, i * size, (i + 1) * size), frames.get(i).pixels(), 12,
                            "ffmpeg frame " + i);
                }
            }
        }

        @Test
        @DisplayName("clips images that extend past the logical screen")
        void clipping() {
            var indices = pattern(10, 10, 8, 3);
            var data = GifFiles.create(6, 5, PALETTE).image(3, 2, 10, 10, null, false, indices, 3, false)
                    .control(2, 0, -1).image(100, 100, 4, 4, null, false, pattern(4, 4, 8, 4), 3, false).trailer().bytes();
            var model = new Model(6, 5);
            model.draw(3, 2, 10, 10, indices, PALETTE, -1, 0, indices.length);
            var frames = frames(data);
            RasterTesting.assertSameRgba(model.bytes(), frames.getFirst().pixels(), 6, "clipped");
            RasterTesting.assertSameRgba(model.bytes(), frames.get(1).pixels(), 6, "outside");
        }

        @Test
        @DisplayName("sizes a zero logical screen from the first image's extent")
        void zeroScreen() {
            var indices = pattern(5, 4, 8, 5);
            var data = GifFiles.create(0, 0, PALETTE).image(2, 1, 5, 4, null, false, indices, 3, false)
                    .image(0, 0, 9, 9, null, false, pattern(9, 9, 8, 6), 3, false).trailer().bytes();
            var info = GifDecoder.probe(MediaSource.of(data));
            assertEquals(7, info.width());
            assertEquals(5, info.height());
            var image = GifDecoder.decode(MediaSource.of(data), OPTIONS);
            assertEquals(7, image.picture().width());
            assertTrue(image.picture().hasAlpha());
            try (var reader = GifDecoder.openAnimation(MediaSource.of(data), OPTIONS)) {
                assertEquals(7, reader.width());
                assertEquals(2, frames(reader).size());
            }
            var empty = GifFiles.create(0, 0, PALETTE).image(0, 0, 0, 3, null, false, new byte[0], 3, false).trailer().bytes();
            assertThrows(MediaException.Malformed.class, () -> GifDecoder.decode(MediaSource.of(empty), OPTIONS));
        }

        @Test
        @DisplayName("counts zero-size images as frames that draw nothing")
        void zeroSizeImage() {
            var data = GifFiles.create(3, 3, PALETTE).image(0, 0, 3, 3, null, false, pattern(3, 3, 8, 1), 3, false)
                    .control(0, 7, -1).image(1, 1, 0, 0, null, false, new byte[0], 3, false)
                    .control(0, 9, -1).image(1, 1, 5, 0, null, false, new byte[0], 3, false).trailer().bytes();
            var frames = frames(data);
            assertEquals(3, frames.size());
            assertArrayEquals(frames.getFirst().pixels(), frames.get(2).pixels());
            assertEquals(70, frames.get(1).duration());
            assertEquals(90, frames.get(2).duration());
        }

        @Test
        @DisplayName("leaves indices past the color table and images without a table undrawn")
        void missingColors() {
            var data = GifFiles.create(4, 1, new int[]{0xFF0000, 0x00FF00})
                    .image(0, 0, 4, 1, null, false, new byte[]{0, 1, 2, 3}, 2, false).trailer().bytes();
            var frame = frames(data).getFirst();
            assertEquals(0xFF0000FF, frame.pixel(4, 0, 0));
            assertEquals(0, frame.pixel(4, 2, 0));
            var none = GifFiles.create(2, 1, null).image(0, 0, 2, 1, null, false, new byte[]{0, 1}, 2, false).trailer().bytes();
            assertEquals(0, frames(none).getFirst().pixel(2, 1, 0));
        }

        @Test
        @DisplayName("uses the last of several graphic controls before an image")
        void lastControlWins() {
            var data = GifFiles.create(2, 1, PALETTE).control(0, 5, 0).control(0, 8, -1)
                    .image(0, 0, 2, 1, null, false, new byte[]{0, 1}, 3, false).trailer().bytes();
            var frame = frames(data).getFirst();
            assertEquals(80, frame.duration());
            assertEquals(rgba(0), frame.pixel(2, 0, 0));
        }
    }

    @Nested
    @DisplayName("structure")
    class Structure {
        @Test
        @DisplayName("turns the NETSCAPE2.0 or ANIMEXTS1.0 repeat count into plays and defaults to one play")
        void loops() {
            var image = pattern(2, 2, 8, 1);
            for (var application : List.of("NETSCAPE2.0", "ANIMEXTS1.0")) {
                var data = GifFiles.create(2, 2, PALETTE).loop(application, 7).image(0, 0, 2, 2, null, false, image, 3, false)
                        .trailer().bytes();
                try (var reader = GifDecoder.openAnimation(MediaSource.of(data), OPTIONS)) {
                    assertEquals(8, reader.loopCount());
                }
            }
            var plain = GifFiles.create(2, 2, PALETTE).image(0, 0, 2, 2, null, false, image, 3, false).trailer().bytes();
            try (var reader = GifDecoder.openAnimation(MediaSource.of(plain), OPTIONS)) {
                assertEquals(1, reader.loopCount());
                assertEquals(1, reader.frameCount());
                assertNotNull(reader.read());
                assertNull(reader.read());
            }
        }

        @Test
        @DisplayName("skips comment, plain text and unknown application extensions")
        void extensions() {
            var data = GifFiles.create(2, 1, PALETTE).comment("x".repeat(600)).plainText().loop("XMPDataXMP ", 1)
                    .image(0, 0, 2, 1, null, false, new byte[]{2, 3}, 3, false).trailer().bytes();
            try (var reader = GifDecoder.openAnimation(MediaSource.of(data), OPTIONS)) {
                assertEquals(1, reader.loopCount());
                var frame = frames(reader).getFirst();
                assertEquals(rgba(3), frame.pixel(2, 1, 0));
            }
        }

        @Test
        @DisplayName("decodes GIF87a")
        void gif87a() {
            var data = GifFiles.create(2, 1, PALETTE).image(0, 0, 2, 1, null, false, new byte[]{4, 5}, 3, false).trailer().bytes();
            data[4] = '7';
            assertEquals(rgba(5), frames(data).getFirst().pixel(2, 1, 0));
            assertEquals(ImageFormat.GIF, GifDecoder.decode(MediaSource.of(data), OPTIONS).format());
        }

        @Test
        @DisplayName("renders a truncated last image partially and tolerates a missing trailer or stray bytes")
        void damage() {
            var indices = pattern(40, 40, 8, 2);
            var full = GifFiles.create(40, 40, PALETTE).image(0, 0, 40, 40, null, false, pattern(40, 40, 8, 1), 3, false)
                    .control(1, 0, -1).image(0, 0, 40, 40, null, false, indices, 3, false).trailer().bytes();
            var cut = Arrays.copyOf(full, full.length - 200);
            var frames = frames(cut);
            assertEquals(2, frames.size());
            assertEquals(2, GifDecoder.probe(MediaSource.of(cut)).frameCount());
            assertTrue(GifDecoder.probe(MediaSource.of(cut)).hasAlpha());
            var first = frames(full);
            assertEquals(first.get(1).pixel(40, 0, 0), frames.get(1).pixel(40, 0, 0));
            assertEquals(first.getFirst().pixel(40, 39, 39), frames.get(1).pixel(40, 39, 39), "undecoded rows keep the canvas");
            var noTrailer = Arrays.copyOf(full, full.length - 1);
            assertEquals(2, frames(noTrailer).size());
            var stray = RasterTesting.concat(Arrays.copyOf(full, full.length - 1), new byte[]{0x42, 0x2C, 0, 0});
            assertEquals(2, frames(stray).size());
        }

        @Test
        @DisplayName("rejects inputs without a valid header or without any image")
        void invalid() {
            assertThrows(MediaException.Malformed.class, () -> GifDecoder.decode(MediaSource.of(new byte[0]), OPTIONS));
            assertThrows(MediaException.Malformed.class, () -> GifDecoder.probe(MediaSource.of("GIF89".getBytes())));
            assertThrows(MediaException.Malformed.class, () -> GifDecoder.decode(MediaSource.of("GIF88a1234567".getBytes()), OPTIONS));
            var header = GifFiles.create(4, 4, PALETTE).trailer().bytes();
            assertThrows(MediaException.Malformed.class, () -> GifDecoder.decode(MediaSource.of(header), OPTIONS));
            assertThrows(MediaException.Malformed.class, () -> GifDecoder.openAnimation(MediaSource.of(header), OPTIONS));
            var truncatedTable = Arrays.copyOf(header, 20);
            assertThrows(MediaException.Malformed.class, () -> GifDecoder.probe(MediaSource.of(truncatedTable)));
        }

        @Test
        @DisplayName("rejects truncation before the first image's data and renders it after")
        void truncation() {
            var full = GifFiles.create(9, 7, PALETTE).loop("NETSCAPE2.0", 0).control(0, 3, 2)
                    .image(1, 1, 8, 6, null, true, pattern(8, 6, 8, 3), 3, false).trailer().bytes();
            var dataStart = full.length - GifFiles.lzw(GifFiles.interlace(pattern(8, 6, 8, 3), 8, 6), 3, false).length - 3;
            for (var length = 0; length <= full.length; length++) {
                var prefix = Arrays.copyOf(full, length);
                if (length < dataStart) {
                    assertThrows(MediaException.Malformed.class, () -> GifDecoder.decode(MediaSource.of(prefix), OPTIONS), "prefix " + length);
                } else {
                    GifDecoder.decode(MediaSource.of(prefix), OPTIONS);
                    assertEquals(1, frames(prefix).size(), "prefix " + length);
                }
            }
        }
    }

    @Nested
    @DisplayName("limits")
    class Limits {
        @Test
        @DisplayName("checks the canvas, image and frame count against the limits")
        void limits() {
            var small = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxDimension(64).maxPixels(4096)
                    .maxFrames(2).maxChannels(2).maxBytes(1 << 20).build()).build();
            var big = GifFiles.create(100, 10, PALETTE).image(0, 0, 1, 1, null, false, new byte[1], 3, false).trailer().bytes();
            assertThrows(MediaException.LimitExceeded.class, () -> GifDecoder.decode(MediaSource.of(big), small));
            var frames = GifFiles.create(4, 4, PALETTE);
            for (var i = 0; i < 3; i++) {
                frames.image(0, 0, 1, 1, null, false, new byte[1], 3, false);
            }
            var three = frames.trailer().bytes();
            assertThrows(MediaException.LimitExceeded.class, () -> GifDecoder.openAnimation(MediaSource.of(three), small));
            var tiny = ImageDecoderOptions.builder().limits(MediaLimits.builder().maxDimension(64).maxPixels(4096)
                    .maxFrames(10).maxChannels(2).maxBytes(1000).build()).build();
            var hugeImage = GifFiles.create(4, 4, PALETTE).image(0, 0, 60000, 60000, null, false, new byte[1], 3, false).trailer().bytes();
            assertThrows(MediaException.LimitExceeded.class, () -> GifDecoder.decode(MediaSource.of(hugeImage), tiny));
        }
    }

    @Test
    @DisplayName("survives a deterministic mutation run: every variant decodes or raises a MediaException")
    void mutations() {
        var seeds = new ArrayList<byte[]>();
        seeds.add(GifFiles.create(20, 16, PALETTE).loop("NETSCAPE2.0", 0).control(2, 4, 1)
                .image(0, 0, 20, 16, null, true, pattern(20, 16, 8, 1), 3, false)
                .control(3, 4, -1).image(3, 3, 9, 9, new int[]{1, 2, 3, 4}, false, pattern(9, 9, 4, 2), 2, false).trailer().bytes());
        var random = new byte[64 * 64];
        new Random(4).nextBytes(random);
        var palette = new int[256];
        seeds.add(GifFiles.create(64, 64, palette).image(0, 0, 64, 64, null, false, random, 8, true).trailer().bytes());
        seeds.add(GifFiles.create(0, 0, PALETTE).image(5, 5, 6, 6, null, false, pattern(6, 6, 8, 7), 3, false).trailer().bytes());
        var stats = assertTimeoutPreemptively(Duration.ofSeconds(RasterTesting.mutationSeconds()), () -> RasterTesting.mutate(seeds, RasterTesting.mutationsPerSeed(200), 0x61F, source -> {
            GifDecoder.probe(source);
            GifDecoder.decode(source, OPTIONS);
            try (var reader = GifDecoder.openAnimation(source, OPTIONS)) {
                for (var current = reader.read(); current != null; current = reader.read()) {
                    current.picture();
                }
            }
        }));
        assertEquals(seeds.size() * RasterTesting.mutationsPerSeed(200), stats.variants());
        System.out.println("GIF mutations: " + stats);
    }
}
