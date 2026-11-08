package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.av1.internal.encoder.Av1EncTesting;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the screen content tools of the predicted usages: palette blocks in key and inter frames
 * and whole-sample motion, on a synthetic page of text that scrolls. Every stream is decoded by the
 * module's decoder and by dav1d through ffmpeg, whose output must equal the module's exactly; the
 * tools must code the page smaller or better than without them, and detection must leave camera
 * content coded as without the tools.
 */
class Av1EncoderScreenContentTest {
    private static final Rational TIME_BASE = new Rational(1, 90_000);

    private static final int WIDTH = 128;

    private static final int HEIGHT = 96;

    private static final int FRAMES = 10;

    /** A white page of dark glyph lines under a blue title bar, scrolled up two rows per frame. */
    static List<YuvPicture> page(int frames) {
        var pageHeight = HEIGHT + 2 * frames + 16;
        var luma = new byte[WIDTH * pageHeight];
        java.util.Arrays.fill(luma, (byte) 235);
        var random = new SplittableRandom(11);
        for (var line = 18; line + 9 < pageHeight; line += 12) {
            var x = 4 + random.nextInt(4);
            while (x + 8 < WIDTH - 4) {
                var glyphWidth = 4 + random.nextInt(3);
                for (var gy = 0; gy < 7; gy++) {
                    for (var gx = 0; gx < glyphWidth; gx++) {
                        if (random.nextInt(3) != 0) {
                            luma[(line + gy) * WIDTH + x + gx] = (byte) (random.nextInt(4) == 0 ? 90 : 16);
                        }
                    }
                }
                x += glyphWidth + 1 + (random.nextInt(6) == 0 ? 4 : 0);
            }
        }
        var out = new ArrayList<YuvPicture>();
        for (var f = 0; f < frames; f++) {
            var picture = YuvPicture.allocate(WIDTH, HEIGHT, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
            var scroll = 2 * f;
            for (var y = 0; y < HEIGHT; y++) {
                for (var x = 0; x < WIDTH; x++) {
                    picture.y()[y * picture.yStride() + x] = y < 12 ? (byte) 41 : luma[(y + scroll) * WIDTH + x];
                }
            }
            for (var y = 0; y < picture.chromaHeight(); y++) {
                for (var x = 0; x < picture.chromaWidth(); x++) {
                    var bar = y < 6;
                    picture.u()[y * picture.uvStride() + x] = (byte) (bar ? 240 : 128);
                    picture.v()[y * picture.uvStride() + x] = (byte) (bar ? 110 : 128);
                }
            }
            out.add(picture);
        }
        return out;
    }

    static Av1EncoderOptions options(Av1EncoderUsage usage, Av1ScreenContent mode) {
        var base = usage == Av1EncoderUsage.REALTIME
                ? Av1EncoderOptions.forRealtime().toBuilder().bitrate(300_000).dropFrames(false)
                : Av1EncoderOptions.ofQuality(60).toBuilder().speed(Av1EncoderSpeed.FAST).lookahead(8);
        return base.timeBase(TIME_BASE).screenContent(mode).build();
    }

    record Result(List<Packet> packets, List<Picture> decoded) {
        long bits() {
            var total = 0L;
            for (var p : packets) {
                total += p.data().length * 8L;
            }
            return total;
        }

        double psnr(List<? extends Picture> sources, int depth) {
            var total = 0.0;
            for (var i = 0; i < sources.size(); i++) {
                total += Av1EncoderPictureFormatTest.psnr(sources.get(i), decoded.get(i), depth);
            }
            return total / sources.size();
        }
    }

    static Result run(List<? extends Picture> sources, Av1EncoderOptions options) {
        try (var encoder = new Av1Encoder(WIDTH, HEIGHT, options)) {
            var packets = Av1EncoderPictureFormatTest.encode(encoder, new ArrayList<Picture>(sources));
            return new Result(packets, Av1EncoderPictureFormatTest.decode(encoder, packets));
        }
    }

    static Stream<Arguments> streams() {
        return Stream.of(
                Arguments.of(Av1EncoderUsage.REALTIME, Av1ScreenContent.ALWAYS, ChromaFormat.YUV420, 8),
                Arguments.of(Av1EncoderUsage.REALTIME, Av1ScreenContent.ALWAYS, ChromaFormat.YUV444, 10),
                Arguments.of(Av1EncoderUsage.GOOD_QUALITY, Av1ScreenContent.ALWAYS, ChromaFormat.YUV420, 8),
                Arguments.of(Av1EncoderUsage.GOOD_QUALITY, Av1ScreenContent.DETECT, ChromaFormat.YUV420, 8),
                Arguments.of(Av1EncoderUsage.GOOD_QUALITY, Av1ScreenContent.DETECT, ChromaFormat.MONOCHROME, 12),
                Arguments.of(Av1EncoderUsage.GOOD_QUALITY, Av1ScreenContent.ALWAYS, ChromaFormat.YUV422, 10));
    }

    @ParameterizedTest(name = "{0} {1} {2} {3}-bit")
    @MethodSource("streams")
    @DisplayName("codes screen content that decodes to dav1d's output exactly")
    void codes(Av1EncoderUsage usage, Av1ScreenContent mode, ChromaFormat format, int depth) throws IOException {
        var sources = new ArrayList<Picture>();
        for (var p : page(FRAMES)) {
            sources.add(Av1EncoderPictureFormatTest.convert(p, format, depth));
        }
        var options = options(usage, mode).toBuilder().pictureFormat(format, depth).build();
        var result = run(sources, options);
        assertEquals(FRAMES, result.decoded().size(), "decoded frames");
        for (var i = 0; i < FRAMES; i++) {
            var psnr = Av1EncoderPictureFormatTest.psnr(sources.get(i), result.decoded().get(i), depth);
            assertTrue(psnr > 28, "frame " + i + " psnr " + psnr);
        }
        if (Oracle.find(Oracle.Tool.FFMPEG).isEmpty()) {
            return;
        }
        var units = new ArrayList<byte[]>();
        for (var p : result.packets()) {
            units.add(p.data());
        }
        var file = Scratch.directory("av1-screen").resolve(usage + "-" + mode + "-" + format + "-" + depth + ".ivf");
        Files.write(file, Av1EncTesting.ivf(WIDTH, HEIGHT, units));
        var reference = Oracle.decodeVideo(file, List.of("-c:v", "libdav1d"),
                Av1EncoderPictureFormatTest.pixelFormat(format, depth), 0);
        var ours = new ByteArrayOutputStream();
        for (var p : result.decoded()) {
            ours.writeBytes(Av1EncoderPictureFormatTest.raw(p));
        }
        assertArrayEquals(reference, ours.toByteArray(), "dav1d output");
    }

    @Test
    @DisplayName("codes the page smaller at a constant quality with the tools than without them")
    void goodQualityGain() {
        var sources = page(FRAMES);
        var with = run(sources, options(Av1EncoderUsage.GOOD_QUALITY, Av1ScreenContent.DETECT));
        var without = run(sources, options(Av1EncoderUsage.GOOD_QUALITY, Av1ScreenContent.NEVER));
        var psnrWith = with.psnr(sources, 8);
        var psnrWithout = without.psnr(sources, 8);
        assertTrue(with.bits() < without.bits() * 0.95 && psnrWith > psnrWithout - 0.3,
                "with the tools " + with.bits() + " bits at " + psnrWith + " dB, without " + without.bits()
                        + " bits at " + psnrWithout + " dB");
    }

    @Test
    @DisplayName("codes the page better at a real-time bitrate with the tools than without them")
    void realtimeGain() {
        var sources = page(FRAMES);
        var with = run(sources, options(Av1EncoderUsage.REALTIME, Av1ScreenContent.ALWAYS));
        var without = run(sources, options(Av1EncoderUsage.REALTIME, Av1ScreenContent.NEVER));
        var psnrWith = with.psnr(sources, 8);
        var psnrWithout = without.psnr(sources, 8);
        // the rate control spends about the same bits either way, so the gain shows as quality
        assertTrue(psnrWith > psnrWithout + 0.5 || with.bits() < without.bits() * 0.9 && psnrWith > psnrWithout,
                "with the tools " + with.bits() + " bits at " + psnrWith + " dB, without " + without.bits()
                        + " bits at " + psnrWithout + " dB");
    }

    @Test
    @DisplayName("detection codes camera content as without the tools")
    void cameraUnchanged() {
        var sources = Vp9TestVideo.synthesize(WIDTH, HEIGHT, FRAMES);
        var detect = run(sources, options(Av1EncoderUsage.GOOD_QUALITY, Av1ScreenContent.DETECT));
        var never = run(sources, options(Av1EncoderUsage.GOOD_QUALITY, Av1ScreenContent.NEVER));
        for (var i = 0; i < FRAMES; i++) {
            assertArrayEquals(Av1EncoderPictureFormatTest.raw(never.decoded().get(i)),
                    Av1EncoderPictureFormatTest.raw(detect.decoded().get(i)), "frame " + i);
        }
        // one flag per frame header, which may round some frames up by a byte
        assertTrue(detect.bits() - never.bits() <= 8L * FRAMES, detect.bits() + " against " + never.bits());
        var realtime = run(sources, options(Av1EncoderUsage.REALTIME, Av1ScreenContent.DETECT));
        var realtimeNever = run(sources, options(Av1EncoderUsage.REALTIME, Av1ScreenContent.NEVER));
        assertEquals(realtimeNever.bits(), realtime.bits(), "real-time detection codes nothing differently");
    }

    @Test
    @DisplayName("keeps the screen content setting of a running predicted encoder")
    void reconfigure() {
        var realtime = options(Av1EncoderUsage.REALTIME, Av1ScreenContent.ALWAYS);
        try (var encoder = new Av1Encoder(WIDTH, HEIGHT, realtime)) {
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.reconfigure(realtime.toBuilder().screenContent(Av1ScreenContent.NEVER).build()));
            assertDoesNotThrow(() -> encoder.reconfigure(realtime.toBuilder().bitrate(200_000).build()));
        }
        var intra = Av1EncoderOptions.forIntraOnly(60).toBuilder().timeBase(TIME_BASE).build();
        try (var encoder = new Av1Encoder(WIDTH, HEIGHT, intra)) {
            assertDoesNotThrow(() -> encoder.reconfigure(intra.toBuilder().screenContent(Av1ScreenContent.ALWAYS)
                    .build()));
        }
        assertEquals(Av1ScreenContent.DETECT, Av1EncoderOptions.defaults().screenContent());
        assertThrows(NullPointerException.class, () -> Av1EncoderOptions.builder().screenContent(null));
    }
}
