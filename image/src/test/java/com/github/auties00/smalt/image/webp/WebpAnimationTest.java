package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.AnimationReader;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers animation decoding (compositing of blended, disposed and offset frames) and the
 * animation encoder: frame-exact lossless round trips, duration merging of identical frames, key
 * frames, and a structural validation of the emitted RIFF/ANIM/ANMF layout.
 */
class WebpAnimationTest {
    static final int W = 64;
    static final int H = 48;

    /** Returns the raw RGBA frames of a lavfi source, as ffmpeg renders them. */
    static List<byte[]> sourceFrames(String lavfi, int frames) {
        var raw = Fixtures.ffmpeg("rgba", List.of("-f", "lavfi", "-i", lavfi), List.of("-frames:v", Integer.toString(frames), "-f", "rawvideo", "-pix_fmt", "rgba"));
        var bytes = Fixtures.read(raw);
        var frameSize = W * H * 4;
        var list = new ArrayList<byte[]>();
        for (var i = 0; i + frameSize <= bytes.length; i += frameSize) {
            list.add(Arrays.copyOfRange(bytes, i, i + frameSize));
        }
        return list;
    }

    static List<byte[]> decodeAll(byte[] file) {
        var frames = new ArrayList<byte[]>();
        try (var reader = WebpDecoder.openAnimation(MediaSource.of(file), ImageDecoderOptions.defaults())) {
            for (var current = reader.read(); current != null; current = reader.read()) {
                frames.add(WebpTestSupport.tightRgba((RgbaPicture) current.picture()));
            }
        }
        return frames;
    }

    static void assertFrameEquals(byte[] expected, byte[] actual, String message) {
        // Transparent pixels may carry any color: compare only alpha where alpha is 0.
        assertEquals(expected.length, actual.length, message);
        for (var i = 0; i < expected.length; i += 4) {
            if (expected[i + 3] == 0 && actual[i + 3] == 0) {
                continue;
            }
            if (expected[i] != actual[i] || expected[i + 1] != actual[i + 1] || expected[i + 2] != actual[i + 2]
                    || expected[i + 3] != actual[i + 3]) {
                fail(message + ": pixel " + (i / 4) + " differs");
            }
        }
    }

    @Nested
    @DisplayName("decoder")
    class Decoder {
        @Test
        @DisplayName("composites an ffmpeg libwebp_anim lossless animation back to its source frames")
        void ffmpegLossless() {
            var source = sourceFrames("testsrc2=size=64x48:rate=5,format=rgba", 10);
            var file = Fixtures.read(Fixtures.ffmpeg("webp", List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=5,format=rgba"),
                    List.of("-frames:v", "10", "-c:v", "libwebp_anim", "-lossless", "1", "-loop", "0")));
            var frames = decodeAll(file);
            assertEquals(source.size(), frames.size());
            for (var i = 0; i < frames.size(); i++) {
                assertFrameEquals(source.get(i), frames.get(i), "frame " + i);
            }
            var info = WebpDecoder.probe(MediaSource.of(file));
            assertTrue(info.animated());
            assertEquals(10, info.frameCount());
            assertTrue(WebpContainer.isAnimated(file));
        }

        @Test
        @DisplayName("composites an animation with transparency, blending and disposal")
        void alphaAnimation() {
            var lavfi = "testsrc2=size=64x48:rate=5,format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='if(lt(mod(X+N*7,32),16),255,if(lt(Y,24),0,128))'";
            var source = sourceFrames(lavfi, 8);
            var file = Fixtures.read(Fixtures.ffmpeg("webp", List.of("-f", "lavfi", "-i", lavfi),
                    List.of("-frames:v", "8", "-c:v", "libwebp_anim", "-lossless", "1", "-loop", "2")));
            var frames = decodeAll(file);
            assertEquals(source.size(), frames.size());
            for (var i = 0; i < frames.size(); i++) {
                assertFrameEquals(source.get(i), frames.get(i), "frame " + i);
            }
            try (var reader = WebpDecoder.openAnimation(MediaSource.of(file), ImageDecoderOptions.defaults())) {
                assertEquals(2, reader.loopCount());
            }
        }

        @Test
        @DisplayName("decode() returns the first composited frame of an animation")
        void firstFrame() {
            var source = sourceFrames("testsrc2=size=64x48:rate=5,format=rgba", 3);
            var file = Fixtures.read(Fixtures.ffmpeg("webp", List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=5,format=rgba"),
                    List.of("-frames:v", "3", "-c:v", "libwebp_anim", "-lossless", "1", "-loop", "0")));
            var decoded = WebpDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults());
            assertFrameEquals(source.getFirst(), WebpTestSupport.tightRgba((RgbaPicture) decoded.picture()), "first frame");
        }
    }

    @Nested
    @DisplayName("encoder")
    class Encoder {
        @ParameterizedTest(name = "method {0}")
        @ValueSource(ints = {0, 4, 6})
        @DisplayName("round-trips lossless frames exactly")
        void losslessRoundTrip(int method) {
            var source = sourceFrames("testsrc2=size=64x48:rate=5,format=rgba", 12);
            var encoder = new WebpAnimationEncoder(W, H, WebpAnimationEncoderOptions.lossless().toBuilder()
                    .method(method).build());
            for (var frame : source) {
                encoder.addFrame(RgbaPicture.of(W, H, W * 4, frame, true), 100);
            }
            var file = encoder.finish();
            WebpStructure.validate(file);
            var frames = decodeAll(file);
            assertEquals(source.size(), frames.size());
            for (var i = 0; i < frames.size(); i++) {
                assertFrameEquals(source.get(i), frames.get(i), "frame " + i);
            }
        }

        static Stream<Arguments> img2webpCases() {
            var lossless = WebpAnimationEncoderOptions.lossless();
            var lossy = WebpAnimationEncoderOptions.lossy(60);
            return Stream.of(
                    Arguments.of("-kmin 2 -kmax 3;-lossless", lossless.toBuilder().keyframes(2, 3).build(), false),
                    Arguments.of("-min_size;-lossless", lossless.toBuilder().minimize(true).build(), false),
                    Arguments.of(";-lossless -m 6", lossless.toBuilder().method(6).build(), false),
                    Arguments.of(";-lossless -m 0", lossless.toBuilder().method(0).build(), false),
                    Arguments.of("-kmin 2 -kmax 3;-lossy -q 60", lossy.toBuilder().keyframes(2, 3).build(), true),
                    Arguments.of("-min_size;-lossy -q 60", lossy.toBuilder().minimize(true).build(), true),
                    Arguments.of("-mixed -kmin 0 -kmax 1;", lossless.toBuilder().allowMixed(true).keyframes(0, 1)
                            .build(), true));
        }

        @ParameterizedTest(name = "img2webp {0}")
        @MethodSource("img2webpCases")
        @DisplayName("writes the same bytes as img2webp (libwebp's WebPAnimEncoder) at equal settings")
        void identicalToImg2webp(String flags, WebpAnimationEncoderOptions options, boolean lossy) {
            var pngs = new ArrayList<Path>();
            var frames = new ArrayList<byte[]>();
            for (var i = 0; i < 8; i++) {
                var png = Fixtures.ffmpeg("png", List.of("-f", "lavfi", "-i", "testsrc2=size=" + W + "x" + H + ":rate=5"),
                        List.of("-vf", "select=eq(n\\," + (i * 2) + "),format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)'"
                                + ":a='if(lt(X," + (8 * i) + "),0,255)'", "-frames:v", "1"));
                pngs.add(png);
                frames.add(Oracle.decodeVideo(png, "rgba", 1));
            }
            var parts = flags.split(";", -1);
            var arguments = new ArrayList<String>();
            if (!parts[0].isEmpty()) {
                arguments.addAll(List.of(parts[0].split(" ")));
            }
            arguments.addAll(List.of("-loop", "2", "-d", "60"));
            if (!parts[1].isEmpty()) {
                arguments.addAll(List.of(parts[1].split(" ")));
            }
            for (var png : pngs) {
                arguments.add(png.toString());
            }
            var out = WebpTestSupport.scratchPath("i2w-" + Integer.toHexString(flags.hashCode()) + ".webp");
            arguments.addAll(List.of("-o", out.toString()));
            WebpTestSupport.runTool("img2webp", arguments);
            java.util.function.Supplier<byte[]> encode = () -> {
                var encoder = new WebpAnimationEncoder(W, H, options.toBuilder().loopCount(2).build());
                for (var frame : frames) {
                    encoder.addFrame(RgbaPicture.of(W, H, W * 4, frame, true), 60);
                }
                return encoder.finish();
            };
            var ours = lossy ? WebpTestSupport.withVp8(encode) : encode.get();
            assertArrayEquals(WebpTestSupport.readFile(out), ours);
        }

        @Test
        @DisplayName("merges identical frames into the previous frame's duration")
        void mergesDuplicates() {
            var source = sourceFrames("testsrc2=size=64x48:rate=5,format=rgba", 3);
            var encoder = new WebpAnimationEncoder(W, H, WebpAnimationEncoderOptions.lossless());
            encoder.addFrame(RgbaPicture.of(W, H, W * 4, source.get(0), true), 40);
            encoder.addFrame(RgbaPicture.of(W, H, W * 4, source.get(0).clone(), true), 60);
            encoder.addFrame(RgbaPicture.of(W, H, W * 4, source.get(1), true), 50);
            var file = encoder.finish();
            try (var reader = WebpDecoder.openAnimation(MediaSource.of(file), ImageDecoderOptions.defaults())) {
                assertEquals(2, reader.frameCount());
                var first = reader.read();
                assertEquals(100, first.durationMillis());
                assertEquals(0, first.timestampMicros());
                var second = reader.read();
                assertEquals(50, second.durationMillis());
                assertEquals(100_000, second.timestampMicros());
                assertNull(reader.read());
            }
        }

        @Test
        @DisplayName("keeps zero-duration frames")
        void zeroDuration() {
            var source = sourceFrames("testsrc2=size=64x48:rate=5,format=rgba", 2);
            var encoder = new WebpAnimationEncoder(W, H, WebpAnimationEncoderOptions.lossless());
            encoder.addFrame(RgbaPicture.of(W, H, W * 4, source.get(0), true), 0);
            encoder.addFrame(RgbaPicture.of(W, H, W * 4, source.get(1), true), 0);
            var file = encoder.finish();
            try (var reader = WebpDecoder.openAnimation(MediaSource.of(file), ImageDecoderOptions.defaults())) {
                assertEquals(0, reader.read().durationMicros());
                assertEquals(0, reader.read().durationMicros());
            }
        }

        @Test
        @DisplayName("writes a single full frame as a still image, as libwebp does")
        void singleFrame() {
            var source = sourceFrames("testsrc2=size=64x48:rate=5,format=rgba", 1);
            var encoder = new WebpAnimationEncoder(W, H, WebpAnimationEncoderOptions.lossless());
            encoder.addFrame(RgbaPicture.of(W, H, W * 4, source.getFirst(), true), 100);
            var file = encoder.finish();
            assertFalse(WebpContainer.isAnimated(file));
            assertFrameEquals(source.getFirst(), WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(file)), "still");
        }

        @Test
        @DisplayName("is accepted by webpmux")
        void webpmux() {
            var source = sourceFrames("testsrc2=size=64x48:rate=5,format=rgba", 6);
            var encoder = new WebpAnimationEncoder(W, H, WebpAnimationEncoderOptions.lossless().toBuilder()
                    .loopCount(3).build());
            for (var frame : source) {
                encoder.addFrame(RgbaPicture.of(W, H, W * 4, frame, true), 80);
            }
            var path = WebpTestSupport.scratch("anim-webpmux.webp", encoder.finish());
            var info = WebpTestSupport.runTool("webpmux", List.of("-info", path.toString()));
            assertTrue(info.contains("Number of frames: 6"), info);
            assertTrue(info.contains("Loop Count : 3"), info);
        }

        @Test
        @DisplayName("lossy frames decode close to their source (needs VP8)")
        void lossy() {
            var source = sourceFrames("testsrc2=size=64x48:rate=5,format=rgba", 4);
            var file = WebpTestSupport.withVp8(() -> {
                var encoder = new WebpAnimationEncoder(W, H, WebpAnimationEncoderOptions.lossy(90));
                for (var frame : source) {
                    encoder.addFrame(RgbaPicture.of(W, H, W * 4, frame, true), 100);
                }
                return encoder.finish();
            });
            WebpStructure.validate(file);
            var frames = decodeAll(file);
            assertEquals(source.size(), frames.size());
            for (var i = 0; i < frames.size(); i++) {
                assertTrue(com.github.auties00.smalt.testkit.Quality.psnr(source.get(i), frames.get(i)) > 20, "frame " + i);
            }
        }

        @Test
        @DisplayName("rejects frames of the wrong size and durations out of range")
        void validation() {
            var encoder = new WebpAnimationEncoder(W, H, WebpAnimationEncoderOptions.lossless());
            assertThrows(IllegalArgumentException.class, () -> encoder.addFrame(RgbaPicture.allocate(W + 1, H, true),
                    10));
            assertThrows(IllegalArgumentException.class, () -> encoder.addFrame(RgbaPicture.allocate(W, H, true), -1));
            assertThrows(IllegalArgumentException.class, () -> encoder.addFrame(RgbaPicture.allocate(W, H, true),
                    1 << 24));
            assertThrows(IllegalStateException.class, encoder::finish);
        }
    }

    @Nested
    @DisplayName("loop count (total plays, 0 = forever)")
    class LoopCount {
        @ParameterizedTest(name = "{0} plays")
        @ValueSource(ints = {0, 1, 2, 3, 65535})
        @DisplayName("is written unchanged to ANIM and read back unchanged")
        void roundTrip(int plays) {
            var source = sourceFrames("testsrc2=size=64x48:rate=5,format=rgba", 2);
            var encoder = new WebpAnimationEncoder(W, H, WebpAnimationEncoderOptions.lossless().toBuilder()
                    .loopCount(plays).build());
            for (var frame : source) {
                encoder.addFrame(RgbaPicture.of(W, H, W * 4, frame, true), 50);
            }
            var file = encoder.finish();
            var anim = WebpContainer.parse(file).stream().filter(chunk -> chunk.fourCc().equals("ANIM")).findFirst().orElseThrow();
            assertEquals(plays, WebpStructure.le16(file, anim.payloadOffset() + 4), "ANIM loop count field");
            try (var reader = WebpDecoder.openAnimation(MediaSource.of(file), ImageDecoderOptions.defaults())) {
                assertEquals(plays, reader.loopCount());
            }
            var info = WebpTestSupport.runTool("webpmux", List.of("-info", WebpTestSupport.scratch("loop-" + plays + ".webp", file).toString()));
            assertTrue(info.contains("Loop Count : " + plays), info);
        }

        @Test
        @DisplayName("is 1 for a still image")
        void still() {
            var source = sourceFrames("testsrc2=size=64x48:rate=5,format=rgba", 1);
            var file = WebpEncoder.encode(RgbaPicture.of(W, H, W * 4, source.getFirst(), true),
                    WebpEncoderOptions.lossless());
            try (var reader = WebpDecoder.openAnimation(MediaSource.of(file), ImageDecoderOptions.defaults())) {
                assertEquals(1, reader.loopCount());
            }
        }
    }
}
