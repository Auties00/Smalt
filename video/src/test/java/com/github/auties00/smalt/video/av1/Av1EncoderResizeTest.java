package com.github.auties00.smalt.video.av1;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.internal.encoder.Av1EncTesting;
import com.github.auties00.smalt.video.vp9.internal.Vp9TestVideo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers real-time changes of picture size: sizes within the sequence header's continue the stream
 * with inter frames that predict from scaled references and signal their size, sizes a reference
 * cannot predict start a key frame without a new sequence header, and larger sizes a new sequence
 * header. The stream must decode with the module's decoder close to each segment's source and with
 * dav1d exactly as the module's decoder.
 */
class Av1EncoderResizeTest {
    private static final Rational TIME_BASE = new Rational(1, 90_000);

    private static final int SEGMENT = 6;

    /** A size of the stream and whether its first frame is a key frame. */
    record Segment(int width, int height, boolean key) {
    }

    @Test
    @DisplayName("continues the stream across sizes within the sequence header's, decoding to dav1d's output")
    void resizes() throws IOException {
        var segments = List.of(new Segment(160, 96, true), new Segment(120, 72, false), new Segment(160, 96, false),
                new Segment(80, 48, false), new Segment(56, 32, true), new Segment(144, 88, false),
                new Segment(192, 112, true));
        var options = Av1EncoderOptions.forRealtime().toBuilder().bitrate(300_000).dropFrames(false)
                .timeBase(TIME_BASE).build();
        var packets = new ArrayList<Packet>();
        var sources = new ArrayList<Picture>();
        var configs = new ArrayList<byte[]>();
        var total = segments.size() * SEGMENT;
        try (var encoder = new Av1Encoder(segments.getFirst().width(), segments.getFirst().height(), options)) {
            var index = 0;
            for (var segment : segments) {
                encoder.reconfigure(segment.width(), segment.height());
                var video = Vp9TestVideo.synthesize(segment.width(), segment.height(), total);
                for (var i = 0; i < SEGMENT; i++, index++) {
                    var picture = video.get(index);
                    sources.add(picture);
                    var packet = encoder.encodeFrame(new VideoFrame(picture, index * 3000L, 3000, false))
                            .orElseThrow();
                    assertEquals(i == 0 && segment.key(), packet.keyframe(),
                            "frame " + index + " of " + segment + (i == 0 ? ", the first" : ""));
                    packets.add(packet);
                }
                configs.add(encoder.codecConfig().orElseThrow());
            }
        }
        for (var i = 1; i < configs.size() - 1; i++) {
            assertArrayEquals(configs.getFirst(), configs.get(i), "sequence header after segment " + i);
        }
        assertFalse(java.util.Arrays.equals(configs.getFirst(), configs.getLast()),
                "a size beyond the sequence header's takes a new one");
        var decoded = decode(segments.getFirst(), configs.getFirst(), packets);
        assertEquals(sources.size(), decoded.size(), "decoded frames");
        for (var i = 0; i < sources.size(); i++) {
            var source = (YuvPicture) sources.get(i);
            var picture = (YuvPicture) decoded.get(i);
            assertEquals(source.width(), picture.width(), "frame " + i + " width");
            assertEquals(source.height(), picture.height(), "frame " + i + " height");
            var psnr = Av1EncoderPictureFormatTest.psnr(source, picture, 8);
            assertTrue(psnr > 26, "frame " + i + " psnr " + psnr);
        }
        if (Oracle.find(Oracle.Tool.FFMPEG).isEmpty()) {
            return;
        }
        var units = new ArrayList<byte[]>();
        for (var p : packets) {
            units.add(p.data());
        }
        var file = Scratch.directory("av1-resize").resolve("resize.ivf");
        Files.write(file, Av1EncTesting.ivf(segments.getFirst().width(), segments.getFirst().height(), units));
        // without autoscale, ffmpeg passes every frame at its own size
        var reference = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-c:v",
                "libdav1d", "-i", file.toString(), "-map", "0:v:0", "-noautoscale", "-f", "rawvideo", "-pix_fmt",
                "yuv420p", "-"));
        var ours = new ByteArrayOutputStream();
        for (var p : decoded) {
            ours.writeBytes(Av1EncoderPictureFormatTest.raw(p));
        }
        assertArrayEquals(reference, ours.toByteArray(), "dav1d output");
    }

    static List<Picture> decode(Segment first, byte[] config, List<Packet> packets) {
        var track = com.github.auties00.smalt.track.VideoTrack.builder().id(1)
                .codec(com.github.auties00.smalt.track.Codec.AV1).timeBase(TIME_BASE).codecConfig(config)
                .width(first.width()).height(first.height()).build();
        var out = new ArrayList<Picture>();
        try (var decoder = new Av1Decoder(track, Av1DecoderOptions.builder().build())) {
            for (var p : packets) {
                decoder.decode(p, frame -> {
                    var yuv = (YuvPicture) frame.picture();
                    out.add(yuv.toBuilder().luma(yuv.y().clone(), yuv.yStride())
                            .chroma(yuv.u().clone(), yuv.v().clone(), yuv.uvStride()).build());
                });
            }
        }
        return out;
    }
}
