package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.testkit.image.RasterTesting;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers APNG decoding: Philip Taylor's APNG test suite (61 pinned images with documented
 * expected renderings), ffmpeg-generated animations compared frame by frame against ffmpeg's
 * compositing decoder, and hand-built animations for blending, disposal, delays and structural
 * errors.
 */
class ApngTest {
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFFFF0000;
    private static final int YELLOW = 0xFF00FFFF;

    private static byte[] philip(String id) {
        try {
            return Files.readAllBytes(Conformance.vector("apng-philip-" + id + ".png"));
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    /** One decoded frame: packed canvas copy and duration. */
    record Frame(byte[] pixels, int duration) {
        int pixel(int width, int x, int y) {
            var i = (y * width + x) * 4;
            return (pixels[i] & 0xFF) | (pixels[i + 1] & 0xFF) << 8 | (pixels[i + 2] & 0xFF) << 16 | (pixels[i + 3] & 0xFF) << 24;
        }
    }

    static List<Frame> frames(AnimationReader reader) {
        var frames = new ArrayList<Frame>();
        for (var current = reader.read(); current != null; current = reader.read()) {
            frames.add(new Frame(RasterTesting.tight(current.picture()), current.durationMillis()));
        }
        return frames;
    }

    static List<Frame> frames(byte[] data) {
        try (var reader = PngDecoder.openAnimation(MediaSource.of(data), ImageDecoderOptions.defaults())) {
            return frames(reader);
        }
    }

    private static void assertSolid(Frame frame, int width, int height, int color, String what) {
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                assertEquals(Integer.toHexString(color), Integer.toHexString(frame.pixel(width, x, y)), what + " at " + x + "," + y);
            }
        }
    }

    private static void assertTransparent(Frame frame, String what) {
        for (var i = 3; i < frame.pixels().length; i += 4) {
            assertEquals(0, frame.pixels()[i], what + ": alpha of pixel " + i / 4);
        }
    }

    @Nested
    @DisplayName("Philip Taylor's APNG test suite")
    class PhilipSuite {
        static Stream<String> solidGreen() {
            return Stream.of("000", "001", "002", "003", "004", "005", "006", "007", "009", "010", "011", "013", "016",
                    "017", "020", "021", "024", "036", "037");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("solidGreen")
        @DisplayName("ends on solid green")
        void green(String id) {
            var frames = frames(philip(id));
            assertSolid(frames.getLast(), 128, 64, GREEN, id);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("transparent")
        @DisplayName("ends fully transparent")
        void transparent(String id) {
            assertTransparent(frames(philip(id)).getLast(), id);
        }

        static Stream<String> transparent() {
            return Stream.of("008", "012", "014", "018");
        }

        @Test
        @DisplayName("015 disposes a region to background inside a blue frame")
        void backgroundInRegion() {
            var last = frames(philip("015")).getLast();
            for (var y = 0; y < 64; y++) {
                for (var x = 0; x < 128; x++) {
                    var inside = x >= 32 && x < 96 && y >= 16 && y < 48;
                    var pixel = last.pixel(128, x, y);
                    if (inside) {
                        assertEquals(0, pixel >>> 24, "inner pixel " + x + "," + y);
                    } else {
                        assertEquals(Integer.toHexString(BLUE), Integer.toHexString(pixel), "outer pixel " + x + "," + y);
                    }
                }
            }
        }

        @Test
        @DisplayName("019 keeps a nearly transparent green written with BLEND_OP_SOURCE")
        void nearlyTransparent() {
            var pixel = frames(philip("019")).getLast().pixel(128, 5, 5);
            assertEquals(0x00FF00, pixel & 0xFFFFFF);
            assertTrue((pixel >>> 24) > 0 && (pixel >>> 24) <= 3, "alpha " + (pixel >>> 24));
        }

        @Test
        @DisplayName("022 and 023 blend half-transparent frames OVER opaque ones (gAMA is ignored)")
        void blending() {
            var green = frames(philip("022")).getLast().pixel(128, 64, 32);
            assertEquals(0xFF000000, green & 0xFFFF00FF);
            assertTrue(((green >>> 8) & 0xFF) >= 225 && ((green >>> 8) & 0xFF) <= 235, Integer.toHexString(green));
            // 023 is "nearly black" only under its gAMA of 1/2200, which this decoder does not apply
            var red = frames(philip("023")).getLast().pixel(128, 64, 32);
            assertEquals(0xFF000000, red & 0xFFFFFF00);
            assertTrue((red & 0xFF) >= 225 && (red & 0xFF) <= 235, Integer.toHexString(red));
        }

        static Stream<Arguments> delays() {
            return Stream.of(
                    Arguments.of("025", new int[]{500, 1000, 500, 1000}),
                    Arguments.of("026", new int[]{500, 1000}),
                    Arguments.of("027", new int[]{500, 1000}),
                    Arguments.of("028", new int[]{500, 1000}),
                    Arguments.of("029", new int[]{0, 0, 500, 1000}));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("delays")
        @DisplayName("converts delay fractions to milliseconds")
        void delay(String id, int[] expected) {
            var frames = frames(philip(id));
            assertArrayEquals(expected, frames.stream().mapToInt(Frame::duration).toArray());
            assertSolid(frames.getLast(), 128, 64, YELLOW, id);
        }

        @ParameterizedTest(name = "{0} plays {1} times")
        @org.junit.jupiter.params.provider.CsvSource({"030, 0", "031, 1", "032, 2"})
        @DisplayName("reports num_plays as the loop count")
        void plays(String id, int plays) {
            try (var reader = PngDecoder.openAnimation(MediaSource.of(philip(id)), ImageDecoderOptions.defaults())) {
                assertEquals(plays, reader.loopCount());
                assertEquals(2, reader.frameCount());
                var frames = frames(reader);
                assertSolid(frames.getLast(), 128, 64, BLUE, id);
            }
        }

        @Test
        @DisplayName("decodes other depths and color types with blending")
        void depths() {
            assertSolid(frames(philip("033")).getLast(), 128, 64, 0xFF800000, "033 16-bit color");
            var gray = frames(philip("034")).getLast();
            assertEquals(0xFF808080, gray.pixel(128, 0, 0));
            assertEquals(0xFFFFFFFF, gray.pixel(128, 64, 32));
            assertSolid(frames(philip("035")).getLast(), 128, 64, 0xFF808080, "035 gray alpha");
            assertSolid(frames(philip("038")).getLast(), 128, 64, 0xFF800000, "038 1-bit palette with alpha");
        }

        static Stream<String> invalid() {
            return Stream.of("039", "040", "041", "042", "043", "044", "045", "046", "047", "048", "049", "050", "051",
                    "052", "053", "054", "055", "056", "057", "058", "059", "060");
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("invalid")
        @DisplayName("shows the green default image or raises an error, never an invalid frame")
        void invalidImages(String id) {
            var data = philip(id);
            try {
                var still = (RgbaPicture) PngDecoder.decode(MediaSource.of(data), ImageDecoderOptions.defaults()).picture();
                assertSolid(new Frame(RasterTesting.tight(still), 0), 128, 64, GREEN, id + " still");
            } catch (MediaException.Malformed expected) {
                // 045 has no IDAT and 058 has a half-height IDAT: an error is the accepted outcome
                assertTrue(id.equals("045") || id.equals("058"), id + ": " + expected.getMessage());
            }
            try (var reader = PngDecoder.openAnimation(MediaSource.of(data), ImageDecoderOptions.defaults())) {
                for (var current = reader.read(); current != null; current = reader.read()) {
                    assertSolid(new Frame(RasterTesting.tight(current.picture()), 0), 128, 64, GREEN, id + " frame");
                }
            } catch (MediaException.Malformed expected) {
                // an error is an accepted outcome for every invalid image
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("comparable")
        @DisplayName("matches ffmpeg's compositing decoder frame by frame")
        void againstFfmpeg(String id) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var data = philip(id);
            var expected = RasterTesting.ffmpegFrames(RasterTesting.write("apng-" + id + ".png", data), "rgba");
            var frames = frames(data);
            var frameSize = 128 * 64 * 4;
            assertEquals(expected.length / frameSize, frames.size(), "frame count");
            for (var i = 0; i < frames.size(); i++) {
                RasterTesting.assertSameRgba(java.util.Arrays.copyOfRange(expected, i * frameSize, (i + 1) * frameSize),
                        frames.get(i).pixels(), 128, id + " frame " + i);
            }
        }

        static Stream<String> comparable() {
            // 033 is omitted because ffmpeg 7.1 yields only one frame for that 16-bit APNG, and 024
            // because ffmpeg rejects an fcTL before acTL, which the suite marks valid
            return Stream.of("000", "001", "002", "003", "004", "005", "006", "007", "008", "009", "010", "011", "012",
                    "013", "014", "015", "016", "017", "018", "019", "020", "021", "022", "023", "025", "026",
                    "027", "028", "029", "030", "031", "032", "034", "035", "036", "037", "038");
        }
    }

    @Nested
    @DisplayName("ffmpeg-generated animations")
    class Generated {
        static Stream<Arguments> variants() {
            var alpha = "format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='mod(X*3+Y*5+N*40,256)'";
            return Stream.of(
                    Arguments.of("rgb24", "format=rgb24", "none", false),
                    Arguments.of("rgba", alpha, "mixed", false),
                    Arguments.of("rgba-paeth", alpha, "paeth", false),
                    Arguments.of("pal8", "split[a][b];[a]palettegen=reserve_transparent=1[p];[b][p]paletteuse", "none", false),
                    Arguments.of("gray", "format=gray", "sub", false),
                    Arguments.of("ya8", "format=ya8", "avg", false),
                    Arguments.of("rgba64be", alpha + ",format=rgba64be", "mixed", true),
                    Arguments.of("gray16be", "format=gray16be", "up", true));
        }

        @ParameterizedTest(name = "{0} pred={2}")
        @MethodSource("variants")
        @DisplayName("matches ffmpeg frame by frame")
        void matches(String name, String filter, String pred, boolean wide) {
            var file = Fixtures.ffmpeg("apng", List.of("-f", "lavfi", "-i", "testsrc2=size=68x46:rate=10:duration=1.5"),
                    List.of("-vf", "format=rgb24,crop=67:45:0:0," + filter, "-pred", pred, "-plays", "0", "-f", "apng"));
            var data = Fixtures.read(file);
            var expected = wide ? RasterTesting.ffmpegReduced16(file) : RasterTesting.ffmpegFrames(file, "rgba");
            try (var reader = PngDecoder.openAnimation(MediaSource.of(data), ImageDecoderOptions.defaults())) {
                assertEquals(0, reader.loopCount());
                var frames = frames(reader);
                var frameSize = 67 * 45 * 4;
                assertEquals(expected.length / frameSize, frames.size(), "frame count");
                assertEquals(15, frames.size());
                for (var i = 0; i < frames.size(); i++) {
                    RasterTesting.assertSameRgba(java.util.Arrays.copyOfRange(expected, i * frameSize, (i + 1) * frameSize),
                            frames.get(i).pixels(), 67, name + " frame " + i);
                    assertEquals(100, frames.get(i).duration());
                }
            }
            var info = PngDecoder.probe(MediaSource.of(data));
            assertTrue(info.animated());
            assertEquals(15, info.frameCount());
        }
    }

    @Nested
    @DisplayName("hand-built animations")
    class HandBuilt {
        private static byte[] solid(int width, int height, int r, int g, int b, int a) {
            var pixels = new byte[width * height * 4];
            for (var i = 0; i < pixels.length; i += 4) {
                pixels[i] = (byte) r;
                pixels[i + 1] = (byte) g;
                pixels[i + 2] = (byte) b;
                pixels[i + 3] = (byte) a;
            }
            return PngFiles.zlib(PngFiles.unfiltered(pixels, width * 4));
        }

        @Test
        @DisplayName("blends partial alpha OVER partial alpha with the documented rounding")
        void overFormula() {
            var data = PngFiles.create().ihdr(4, 4, 8, 6, 0).actl(2, 0)
                    .fctl(0, 4, 4, 0, 0, 1, 10, 0, 0).idat(solid(4, 4, 200, 100, 50, 100))
                    .fctl(1, 2, 2, 1, 1, 1, 10, 0, 1).fdat(2, solid(2, 2, 10, 220, 90, 150))
                    .iend().bytes();
            var frames = frames(data);
            assertEquals(2, frames.size());
            // sa = 150, da = 100: alpha = 150 + round(105 * 100 / 255) = 191,
            // color = floor((src * 150 + floor(dst * 100 * 105 / 255)) / 191)
            var weight = 100 * 105;
            var alpha = 150 + Math.round(weight / 255f);
            var expected = ((10 * 150 + 200 * weight / 255) / alpha) | ((220 * 150 + 100 * weight / 255) / alpha) << 8
                    | ((90 * 150 + 50 * weight / 255) / alpha) << 16 | alpha << 24;
            assertEquals(Integer.toHexString(expected), Integer.toHexString(frames.get(1).pixel(4, 1, 1)));
            assertEquals(Integer.toHexString(0x643264C8), Integer.toHexString(frames.get(1).pixel(4, 0, 0)));
            assertEquals(100, frames.get(0).duration());
        }

        @Test
        @DisplayName("restores the region of a PREVIOUS frame and clears a BACKGROUND one")
        void disposal() {
            var data = PngFiles.create().ihdr(4, 4, 8, 6, 0).actl(4, 0)
                    .fctl(0, 4, 4, 0, 0, 0, 0, 0, 0).idat(solid(4, 4, 255, 0, 0, 255))
                    .fctl(1, 2, 2, 2, 2, 0, 0, 2, 0).fdat(2, solid(2, 2, 0, 255, 0, 255))
                    .fctl(3, 2, 2, 0, 0, 0, 0, 1, 0).fdat(4, solid(2, 2, 0, 0, 255, 255))
                    .fctl(5, 1, 1, 3, 0, 0, 0, 0, 0).fdat(6, solid(1, 1, 9, 9, 9, 255))
                    .iend().bytes();
            var frames = frames(data);
            assertEquals(0xFF00FF00, frames.get(1).pixel(4, 3, 3));
            assertEquals(0xFF0000FF, frames.get(2).pixel(4, 3, 3), "restored after PREVIOUS");
            assertEquals(0xFFFF0000, frames.get(2).pixel(4, 0, 0));
            assertEquals(0, frames.get(3).pixel(4, 0, 0), "cleared after BACKGROUND");
            assertEquals(0xFF090909, frames.get(3).pixel(4, 3, 0));
            assertEquals(0xFF0000FF, frames.get(3).pixel(4, 2, 2));
        }

        @Test
        @DisplayName("rejects a frame extending past the canvas before showing any frame")
        void regionOutsideCanvas() {
            var data = PngFiles.create().ihdr(4, 4, 8, 6, 0).actl(2, 0)
                    .fctl(0, 4, 4, 0, 0, 0, 0, 0, 0).idat(solid(4, 4, 255, 0, 0, 255))
                    .fctl(1, 3, 3, 2, 2, 0, 0, 0, 0).fdat(2, solid(3, 3, 0, 255, 0, 255))
                    .iend().bytes();
            assertThrows(MediaException.Malformed.class,
                    () -> PngDecoder.openAnimation(MediaSource.of(data), ImageDecoderOptions.defaults()));
        }

        @Test
        @DisplayName("rejects corrupt frame data before exposing that frame")
        void corruptFrame() {
            var bad = solid(2, 2, 0, 255, 0, 255);
            bad[bad.length / 2] ^= 0x55;
            var data = PngFiles.create().ihdr(2, 2, 8, 6, 0).actl(2, 0)
                    .fctl(0, 2, 2, 0, 0, 0, 0, 0, 0).idat(solid(2, 2, 255, 0, 0, 255))
                    .fctl(1, 2, 2, 0, 0, 0, 0, 0, 0).fdat(2, bad)
                    .iend().bytes();
            try (var reader = PngDecoder.openAnimation(MediaSource.of(data), ImageDecoderOptions.defaults())) {
                assertNotNull(reader.read());
                assertThrows(MediaException.Malformed.class, reader::read);
                assertNull(reader.read());
            }
        }

        @Test
        @DisplayName("checks the frame limit against acTL before decoding")
        void frameLimit() {
            var data = PngFiles.create().ihdr(1, 1, 8, 6, 0).actl(3, 0)
                    .fctl(0, 1, 1, 0, 0, 0, 0, 0, 0).idat(solid(1, 1, 1, 2, 3, 4))
                    .fctl(1, 1, 1, 0, 0, 0, 0, 0, 0).fdat(2, solid(1, 1, 1, 2, 3, 4))
                    .fctl(3, 1, 1, 0, 0, 0, 0, 0, 0).fdat(4, solid(1, 1, 1, 2, 3, 4))
                    .iend().bytes();
            var limits = com.github.auties00.smalt.MediaLimits.builder().maxDimension(100).maxPixels(10_000)
                    .maxFrames(2).maxChannels(8).maxBytes(1 << 20).build();
            assertThrows(MediaException.LimitExceeded.class,
                    () -> PngDecoder.openAnimation(MediaSource.of(data), ImageDecoderOptions.builder().limits(limits)
                            .build()));
            assertEquals(3, frames(data).size());
        }

        @Test
        @DisplayName("tolerates a missing IEND after the last frame")
        void missingIend() {
            var full = PngFiles.create().ihdr(2, 2, 8, 6, 0).actl(2, 0)
                    .fctl(0, 2, 2, 0, 0, 0, 0, 0, 0).idat(solid(2, 2, 255, 0, 0, 255))
                    .fctl(1, 2, 2, 0, 0, 0, 0, 0, 0).fdat(2, solid(2, 2, 0, 255, 0, 255))
                    .bytes();
            assertEquals(2, frames(full).size());
        }

        @Test
        @DisplayName("rejects truncation anywhere inside the APNG chunks")
        void truncation() {
            var full = PngFiles.create().ihdr(2, 2, 8, 6, 0).actl(2, 0)
                    .fctl(0, 2, 2, 0, 0, 0, 0, 0, 0).idat(solid(2, 2, 255, 0, 0, 255))
                    .fctl(1, 2, 2, 0, 0, 0, 0, 0, 0).fdat(2, solid(2, 2, 0, 255, 0, 255))
                    .iend().bytes();
            var end = full.length - 12;
            for (var length = 0; length < end; length++) {
                var prefix = java.util.Arrays.copyOf(full, length);
                assertThrows(MediaException.Malformed.class, () -> frames(prefix), "prefix " + length);
            }
            assertEquals(2, frames(java.util.Arrays.copyOf(full, end)).size());
        }
    }
}
