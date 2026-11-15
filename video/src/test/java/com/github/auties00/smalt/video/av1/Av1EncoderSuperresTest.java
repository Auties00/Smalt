package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers super-resolution in the predicted usages: the frames the inter encoder codes take the
 * reduced width their headers signal, the decoder upscales them to the picture width, and the
 * stream decodes with the module's decoder close to the source and with dav1d exactly.
 */
class Av1EncoderSuperresTest {
    private static final Rational TIME_BASE = new Rational(1, 90_000);

    private static final int WIDTH = 128;

    private static final int HEIGHT = 72;

    private static final int FRAMES = 12;

    static Av1EncoderOptions options(Av1EncoderUsage usage, int denominator) {
        var base = usage == Av1EncoderUsage.REALTIME
                ? Av1EncoderOptions.forRealtime().toBuilder().bitrate(500_000).dropFrames(false)
                : Av1EncoderOptions.ofQuality(60).toBuilder().speed(Av1EncoderSpeed.FAST).lookahead(8);
        return base.timeBase(TIME_BASE).superResolution(denominator).build();
    }

    static Stream<Arguments> streams() {
        return Stream.of(
                Arguments.of(Av1EncoderUsage.REALTIME, 16, ChromaFormat.YUV420, 8),
                Arguments.of(Av1EncoderUsage.REALTIME, 11, ChromaFormat.YUV444, 10),
                Arguments.of(Av1EncoderUsage.GOOD_QUALITY, 12, ChromaFormat.YUV420, 8),
                Arguments.of(Av1EncoderUsage.GOOD_QUALITY, 9, ChromaFormat.YUV422, 12),
                Arguments.of(Av1EncoderUsage.GOOD_QUALITY, 16, ChromaFormat.MONOCHROME, 8));
    }

    @ParameterizedTest(name = "{0} 8/{1} {2} {3}-bit")
    @MethodSource("streams")
    @DisplayName("codes the predicted frames at the reduced width, decoding to dav1d's output exactly")
    void codes(Av1EncoderUsage usage, int denominator, ChromaFormat format, int depth) throws IOException {
        var sources = new ArrayList<Picture>();
        for (var p : Vp9TestVideo.synthesize(WIDTH, HEIGHT, FRAMES)) {
            sources.add(Av1EncoderPictureFormatTest.convert(p, format, depth));
        }
        var options = options(usage, denominator).toBuilder().pictureFormat(format, depth).build();
        List<Packet> packets;
        List<Picture> decoded;
        byte[] config;
        try (var encoder = new Av1Encoder(WIDTH, HEIGHT, options)) {
            packets = Av1EncoderPictureFormatTest.encode(encoder, sources);
            config = encoder.codecConfig().orElseThrow();
            decoded = Av1EncoderPictureFormatTest.decode(encoder, packets);
        }
        assertEquals(FRAMES, decoded.size(), "decoded frames");
        for (var i = 0; i < FRAMES; i++) {
            var psnr = Av1EncoderPictureFormatTest.psnr(sources.get(i), decoded.get(i), depth);
            assertTrue(psnr > 25, "frame " + i + " psnr " + psnr);
        }
        var seq = Av1EncoderTimingTest.sequence(config);
        assertTrue(seq.superRes, "the sequence enables super-resolution");
        var coded = Math.max((WIDTH * 8 + denominator / 2) / denominator, 16);
        var superres = 0;
        for (var h : Av1EncoderTimingTest.frameHeaders(seq, packets)) {
            if (h.showExistingFrame) {
                continue;
            }
            assertEquals(WIDTH, h.upscaledWidth, "upscaled width");
            if (h.frameType == FrameHeader.KEY && usage == Av1EncoderUsage.GOOD_QUALITY) {
                assertEquals(WIDTH, h.codedWidth, "good-quality key frames keep the picture width");
                continue;
            }
            assertTrue(h.superResEnabled, "frame " + superres + " uses super-resolution");
            assertEquals(denominator, h.superResDenominator);
            assertEquals(coded, h.codedWidth, "coded width");
            superres++;
        }
        assertTrue(superres > 0, "super-resolution frames");
        if (Oracle.find(Oracle.Tool.FFMPEG).isEmpty()) {
            return;
        }
        var units = new ArrayList<byte[]>();
        for (var p : packets) {
            units.add(p.data());
        }
        var file = Scratch.directory("av1-superres").resolve(usage + "-" + denominator + "-" + format + "-" + depth
                + ".ivf");
        Files.write(file, Av1EncTesting.ivf(WIDTH, HEIGHT, units));
        var reference = Oracle.decodeVideo(file, List.of("-c:v", "libdav1d"),
                Av1EncoderPictureFormatTest.pixelFormat(format, depth), 0);
        var ours = new ByteArrayOutputStream();
        for (var p : decoded) {
            ours.writeBytes(Av1EncoderPictureFormatTest.raw(p));
        }
        assertArrayEquals(reference, ours.toByteArray(), "dav1d output");
    }

    @Test
    @DisplayName("keeps super-resolution across a real-time change of picture size")
    void resize() {
        // at 8/10 the coded widths 102 and 77 keep the references within twice the new coded width
        var options = options(Av1EncoderUsage.REALTIME, 10);
        var packets = new ArrayList<Packet>();
        var large = Vp9TestVideo.synthesize(WIDTH, HEIGHT, 8);
        var small = Vp9TestVideo.synthesize(96, 56, 8);
        try (var encoder = new Av1Encoder(WIDTH, HEIGHT, options)) {
            for (var i = 0; i < 4; i++) {
                packets.add(encoder.encodeFrame(new VideoFrame(large.get(i), i * 3000L, 3000, false)).orElseThrow());
            }
            encoder.reconfigure(96, 56);
            for (var i = 4; i < 8; i++) {
                var packet = encoder.encodeFrame(new VideoFrame(small.get(i), i * 3000L, 3000, false)).orElseThrow();
                assertFalse(packet.keyframe(), "frame " + i + " continues the stream");
                packets.add(packet);
            }
            var decoded = Av1EncoderPictureFormatTest.decode(encoder, packets);
            assertEquals(8, decoded.size());
            for (var i = 4; i < 8; i++) {
                var psnr = Av1EncoderPictureFormatTest.psnr(small.get(i), decoded.get(i), 8);
                assertTrue(psnr > 25, "frame " + i + " psnr " + psnr);
            }
        }
    }

    @Test
    @DisplayName("rejects super-resolution outside the predicted usages and its changes")
    void rejects() {
        assertThrows(IllegalArgumentException.class,
                () -> Av1EncoderOptions.forIntraOnly(50).toBuilder().superResolution(12).build());
        assertThrows(IllegalArgumentException.class,
                () -> Av1EncoderOptions.forRealtime().toBuilder().superResolution(8).build());
        assertThrows(IllegalArgumentException.class,
                () -> Av1EncoderOptions.forRealtime().toBuilder().superResolution(17).build());
        var options = options(Av1EncoderUsage.REALTIME, 12);
        try (var encoder = new Av1Encoder(WIDTH, HEIGHT, options)) {
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.reconfigure(options.toBuilder().noSuperResolution().build()));
        }
        assertTrue(Av1EncoderOptions.defaults().superResolution().isEmpty());
    }
}
