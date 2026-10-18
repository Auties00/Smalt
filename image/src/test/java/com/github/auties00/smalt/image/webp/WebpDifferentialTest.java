package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Quality;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Compares this module with the libwebp tools and ffmpeg on files they produce: cwebp lossless
 * and near-lossless variants against dwebp, metadata inserted by webpmux, gif2webp conversions
 * against ffmpeg's GIF decode, img2webp animations against their source frames, and a real-world
 * animation against libwebp's WebPAnimDecoder output. Lossy variants need VP8 pixels and are
 * skipped until the VP8 workstream is merged.
 */
class WebpDifferentialTest {
    static final int W = 64;
    static final int H = 48;

    /** Extracts every frame with webpmux and checks that this decoder matches dwebp on each. */
    static void assertFramesDecodeLikeDwebp(Path animation, int frameCount) {
        for (var i = 1; i <= frameCount; i++) {
            var frame = WebpTestSupport.scratchPath("fr" + WebpTestSupport.SCRATCH_COUNTER.incrementAndGet() + ".webp");
            WebpTestSupport.runTool("webpmux", List.of("-get", "frame", Integer.toString(i), animation.toString(), "-o", frame.toString()));
            var ours = WebpTestSupport.withVp8(() -> WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(WebpTestSupport.readFile(frame))));
            assertArrayEquals(WebpTestSupport.dwebpRgba(frame), ours, "frame " + i);
        }
    }

    /** Returns the PSNR over RGBA with the color of pixels transparent in the source ignored. */
    static double visiblePsnr(byte[] expected, byte[] actual) {
        var a = expected.clone();
        var b = actual.clone();
        for (var i = 0; i < a.length; i += 4) {
            if (a[i + 3] == 0) {
                a[i] = a[i + 1] = a[i + 2] = 0;
                b[i] = b[i + 1] = b[i + 2] = 0;
            }
        }
        return Quality.psnr(a, b);
    }

    static List<byte[]> split(byte[] frames, int frameSize) {
        var list = new ArrayList<byte[]>();
        for (var i = 0; i + frameSize <= frames.length; i += frameSize) {
            list.add(Arrays.copyOfRange(frames, i, i + frameSize));
        }
        return list;
    }

    @Nested
    @DisplayName("cwebp variants")
    class Cwebp {
        @ParameterizedTest(name = "cwebp {0}")
        @ValueSource(strings = {"-lossless -z 0", "-lossless -z 3", "-lossless -z 6", "-lossless -z 9",
                "-lossless -exact -m 1", "-lossless -m 6 -q 100 -exact", "-near_lossless 0", "-near_lossless 40",
                "-near_lossless 60", "-near_lossless 80", "-lossless -alpha_filter none", "-lossless -q 0 -m 0"})
        @DisplayName("decode exactly like dwebp")
        void lossless(String flags) {
            Oracle.assume(Oracle.Tool.CWEBP);
            for (var source : List.of(WebpLosslessEncoderTest.Source.ALPHA, WebpLosslessEncoderTest.Source.OPAQUE)) {
                var out = WebpTestSupport.scratchPath("diff-" + Integer.toHexString(flags.hashCode()) + "-" + source + ".webp");
                var arguments = new ArrayList<>(List.of("-quiet"));
                arguments.addAll(List.of(flags.split(" ")));
                arguments.addAll(List.of(source.png().toString(), "-o", out.toString()));
                Oracle.run(Oracle.Tool.CWEBP, Duration.ofMinutes(2), arguments);
                var ours = WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(WebpTestSupport.readFile(out)));
                assertArrayEquals(WebpTestSupport.dwebpRgba(out), ours, flags + " " + source);
            }
        }

        @ParameterizedTest(name = "cwebp -q {0}")
        @ValueSource(ints = {10, 75, 100})
        @DisplayName("decode lossy files with alpha close to dwebp (needs VP8)")
        void lossy(int quality) {
            Oracle.assume(Oracle.Tool.CWEBP);
            var source = WebpLosslessEncoderTest.Source.ALPHA;
            var out = WebpTestSupport.scratchPath("diff-lossy-q" + quality + ".webp");
            Oracle.run(Oracle.Tool.CWEBP, Duration.ofMinutes(1), List.of("-quiet", "-q", Integer.toString(quality),
                    source.png().toString(), "-o", out.toString()));
            var file = WebpTestSupport.readFile(out);
            var ours = WebpTestSupport.withVp8(() -> WebpTestSupport.tightRgba(WebpTestSupport.decodeRgba(file)));
            assertArrayEquals(WebpTestSupport.dwebpRgba(out), ours);
        }
    }

    @Nested
    @DisplayName("webpmux metadata")
    class Metadata {
        @Test
        @DisplayName("exposes ICC, EXIF and XMP inserted by webpmux and reads the EXIF orientation")
        void metadata() {
            var rgba = new byte[W * H * 4];
            Arrays.fill(rgba, (byte) 0x7F);
            var still = WebpEncoder.encode(RgbaPicture.of(W, H, W * 4, rgba, false), WebpEncoderOptions.lossless());
            var source = WebpTestSupport.scratch("meta-src.webp", still);
            var icc = "icc-bytes".getBytes(StandardCharsets.US_ASCII);
            var exif = new ByteArrayOutputStream();
            exif.writeBytes("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1));
            exif.writeBytes(new byte[]{'I', 'I', 42, 0, 8, 0, 0, 0, 1, 0, 0x12, 0x01, 3, 0, 1, 0, 0, 0, 8, 0, 0, 0, 0, 0, 0, 0});
            var iccPath = WebpTestSupport.scratch("meta.icc", icc);
            var exifPath = WebpTestSupport.scratch("meta.exif", exif.toByteArray());
            var xmpPath = WebpTestSupport.scratch("meta.xmp", "<x/>".getBytes(StandardCharsets.US_ASCII));
            var step1 = WebpTestSupport.scratchPath("meta-1.webp");
            var step2 = WebpTestSupport.scratchPath("meta-2.webp");
            var step3 = WebpTestSupport.scratchPath("meta-3.webp");
            WebpTestSupport.runTool("webpmux", List.of("-set", "icc", iccPath.toString(), source.toString(), "-o", step1.toString()));
            WebpTestSupport.runTool("webpmux", List.of("-set", "exif", exifPath.toString(), step1.toString(), "-o", step2.toString()));
            WebpTestSupport.runTool("webpmux", List.of("-set", "xmp", xmpPath.toString(), step2.toString(), "-o", step3.toString()));
            var file = WebpTestSupport.readFile(step3);
            var info = WebpDecoder.probe(MediaSource.of(file));
            assertEquals(Orientation.ROTATE_270, info.orientation());
            var decoded = WebpDecoder.decode(MediaSource.of(file), ImageDecoderOptions.defaults());
            assertArrayEquals(icc, decoded.iccProfile().orElseThrow());
            var tiff = Arrays.copyOfRange(exif.toByteArray(), 6, exif.size());
            assertArrayEquals(tiff, decoded.exif().orElseThrow(), "the Exif marker is stripped");
            assertArrayEquals(exif.toByteArray(), WebpContainer.exif(file)
                    .orElseThrow(), "WebpContainer returns the raw payload");
            assertEquals(Orientation.ROTATE_270, decoded.orientation());
        }
    }

    @Nested
    @DisplayName("gif2webp")
    class Gif2webp {
        static Path gif() {
            return Fixtures.ffmpeg("gif", List.of("-f", "lavfi", "-i", "testsrc2=size=" + W + "x" + H + ":rate=5:duration=1.2"),
                    List.of("-vf", "split[a][b];[a]palettegen[p];[b][p]paletteuse"));
        }

        @ParameterizedTest(name = "gif2webp {0}")
        @ValueSource(strings = {"", "-min_size", "-kmin 2 -kmax 3", "-m 6"})
        @DisplayName("lossless conversions composite to ffmpeg's GIF frames")
        void lossless(String flags) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var gif = gif();
            var out = WebpTestSupport.scratchPath("gif2webp" + flags.replace(' ', '_') + ".webp");
            var arguments = new ArrayList<String>();
            if (!flags.isEmpty()) {
                arguments.addAll(List.of(flags.split(" ")));
            }
            arguments.addAll(List.of("-quiet", gif.toString(), "-o", out.toString()));
            WebpTestSupport.runTool("gif2webp", arguments);
            var expected = split(Oracle.decodeVideo(gif, "rgba", 100), W * H * 4);
            var frames = WebpAnimationTest.decodeAll(WebpTestSupport.readFile(out));
            assertEquals(expected.size(), frames.size());
            for (var i = 0; i < frames.size(); i++) {
                WebpAnimationTest.assertFrameEquals(expected.get(i), frames.get(i), "frame " + i);
            }
        }

        @ParameterizedTest(name = "gif2webp {0}")
        @ValueSource(strings = {"-lossy", "-mixed"})
        @DisplayName("lossy and mixed conversions composite close to ffmpeg's GIF frames (needs VP8)")
        void lossy(String flags) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var gif = gif();
            var out = WebpTestSupport.scratchPath("gif2webp" + flags + ".webp");
            WebpTestSupport.runTool("gif2webp", List.of(flags, "-q", "90", "-quiet", gif.toString(), "-o", out.toString()));
            var expected = split(Oracle.decodeVideo(gif, "rgba", 100), W * H * 4);
            var frames = WebpTestSupport.withVp8(() -> WebpAnimationTest.decodeAll(WebpTestSupport.readFile(out)));
            assertEquals(expected.size(), frames.size());
            assertFramesDecodeLikeDwebp(out, frames.size());
            for (var i = 0; i < frames.size(); i++) {
                var psnr = visiblePsnr(expected.get(i), frames.get(i));
                assertTrue(psnr > 20, "frame " + i + ": " + psnr + " dB");
            }
        }
    }

    @Nested
    @DisplayName("img2webp")
    class Img2webp {
        static List<Path> pngFrames(int count) {
            var list = new ArrayList<Path>();
            for (var i = 0; i < count; i++) {
                list.add(Fixtures.ffmpeg("png", List.of("-f", "lavfi", "-i", "testsrc2=size=" + W + "x" + H + ":rate=5"),
                        List.of("-vf", "select=eq(n\\," + (i * 2) + "),format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)'"
                                + ":a='if(lt(X," + (8 * i) + "),0,255)'", "-frames:v", "1")));
            }
            return list;
        }

        @ParameterizedTest(name = "img2webp {0}")
        @ValueSource(strings = {";-lossless", "-min_size;-lossless", "-kmin 1 -kmax 2;-lossless", ";-lossless -exact -m 6"})
        @DisplayName("lossless animations composite to their source frames")
        void lossless(String flags) {
            var pngs = pngFrames(4);
            var out = WebpTestSupport.scratchPath("img2webp" + flags.replace(' ', '_').replace(';', '_') + ".webp");
            var parts = flags.split(";");
            var arguments = new ArrayList<String>();
            if (!parts[0].isEmpty()) {
                arguments.addAll(List.of(parts[0].split(" ")));
            }
            arguments.addAll(List.of("-loop", "2", "-d", "60"));
            arguments.addAll(List.of(parts[1].split(" ")));
            for (var png : pngs) {
                arguments.add(png.toString());
            }
            arguments.addAll(List.of("-o", out.toString()));
            WebpTestSupport.runTool("img2webp", arguments);
            var file = WebpTestSupport.readFile(out);
            var frames = WebpAnimationTest.decodeAll(file);
            assertEquals(pngs.size(), frames.size());
            for (var i = 0; i < frames.size(); i++) {
                WebpAnimationTest.assertFrameEquals(Oracle.decodeVideo(pngs.get(i), "rgba", 1), frames.get(i), "frame " + i);
            }
            try (var reader = WebpDecoder.openAnimation(MediaSource.of(file), ImageDecoderOptions.defaults())) {
                assertEquals(2, reader.loopCount());
            }
        }

        @Test
        @DisplayName("lossy animations composite close to their source frames (needs VP8)")
        void lossy() {
            var pngs = pngFrames(3);
            var out = WebpTestSupport.scratchPath("img2webp-lossy.webp");
            var arguments = new ArrayList<>(List.of("-lossy", "-q", "90"));
            for (var png : pngs) {
                arguments.add(png.toString());
            }
            arguments.addAll(List.of("-o", out.toString()));
            WebpTestSupport.runTool("img2webp", arguments);
            var frames = WebpTestSupport.withVp8(() -> WebpAnimationTest.decodeAll(WebpTestSupport.readFile(out)));
            assertEquals(pngs.size(), frames.size());
            assertFramesDecodeLikeDwebp(out, frames.size());
            for (var i = 0; i < frames.size(); i++) {
                var psnr = visiblePsnr(Oracle.decodeVideo(pngs.get(i), "rgba", 1), frames.get(i));
                assertTrue(psnr > 20, "frame " + i + ": " + psnr + " dB");
            }
        }
    }

    @Nested
    @DisplayName("real-world animation")
    class RealWorld {
        @Test
        @DisplayName("gstatic animated/1.webp composites to libwebp WebPAnimDecoder's frames (needs VP8)")
        void gstatic() throws Exception {
            var path = Conformance.fetch("gstatic-animated-1.webp", "https://www.gstatic.com/webp/animated/1.webp",
                    "8afc28fef9bba14b5eecbbc6bb8b4379dc96b6ebc3e0845607ced41b7dda337c");
            var file = WebpTestSupport.readFile(path);
            var info = WebpDecoder.probe(MediaSource.of(file));
            assertEquals(300, info.width());
            assertEquals(225, info.height());
            assertEquals(100, info.frameCount());
            var frames = WebpTestSupport.withVp8(() -> WebpAnimationTest.decodeAll(file));
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (var frame : frames) {
                digest.update(frame);
            }
            // SHA-256 of the 100 RGBA canvases written by WebPAnimDecoder (libwebp 1.6.0, MODE_RGBA)
            assertEquals("0fae1d1d9a8856a37a54a10dff9ce9802688a024ba08a74f1277e99b4c648299",
                    java.util.HexFormat.of().formatHex(digest.digest()));
        }
    }

    @Test
    @DisplayName("webpinfo finds no error in this module's still and animated output")
    void webpinfo() {
        var rgba = new byte[W * H * 4];
        new java.util.Random(1).nextBytes(rgba);
        var still = WebpEncoder.encode(RgbaPicture.of(W, H, W * 4, rgba, true), WebpEncoderOptions.lossless()
                .toBuilder().extendedFormat(true).build());
        var encoder = new WebpAnimationEncoder(W, H, WebpAnimationEncoderOptions.lossless());
        encoder.addFrame(RgbaPicture.of(W, H, W * 4, rgba, true), 40);
        var second = rgba.clone();
        Arrays.fill(second, 0, W * 4 * 10, (byte) 0);
        encoder.addFrame(RgbaPicture.of(W, H, W * 4, second, true), 40);
        var animation = encoder.finish();
        for (var file : List.of(still, animation)) {
            var path = WebpTestSupport.scratch("webpinfo-" + file.length + ".webp", file);
            var output = WebpTestSupport.runTool("webpinfo", List.of(path.toString()));
            assertTrue(output.contains("No error detected"), output);
        }
    }
}
