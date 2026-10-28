package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.avi.AviMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4MuxerOptions;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.mpeg4.internal.BitReader;
import com.github.auties00.smalt.video.mpeg4.internal.VideoObjectLayer;
import com.github.auties00.smalt.MediaLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.time.Duration;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Encodes synthetic scenes with the MPEG-4 Part 2 encoder across the tools of the Simple and Advanced Simple
 * profiles, and checks the encoder's reconstruction against Smalt's decode of the stream (sample for sample), the
 * stream against ffmpeg's decode and the ISO/IEC 14496-5 reference decoder (within the inverse DCT noise floor),
 * the container round trips, the rate control and the options.
 */
@DisplayName("MPEG-4 Part 2 encoder")
class Mpeg4EncoderTest {
    /** One time base unit per frame: the VOP time increment resolution is the frame rate. */
    static final Rational TIME_BASE = new Rational(1, 25);

    record Case(String name, int width, int height, int frames, Mpeg4EncoderOptions options, boolean still) {
        Case(String name, int width, int height, int frames, Mpeg4EncoderOptions options) {
            this(name, width, height, frames, options, false);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Mpeg4EncoderOptions.Builder base() {
        return Mpeg4EncoderOptions.builder().timeBase(TIME_BASE).bitrate(600_000);
    }

    static Mpeg4EncoderOptions.Builder advanced() {
        return base().profile(Mpeg4Profile.ADVANCED_SIMPLE);
    }

    static Stream<Case> cases() {
        var matrix = new int[64];
        var inter = new int[64];
        for (var i = 0; i < 64; i++) {
            matrix[i] = 8 + (i % 8) * 3 + (i / 8) * 2;
            inter[i] = 16 + (i % 8) + (i / 8);
        }
        return Stream.of(
                new Case("simple profile", 176, 144, 12, base().build()),
                new Case("one vector, no AC prediction", 176, 144, 8, base().fourMotionVectors(false)
                        .acPrediction(false).build()),
                new Case("video packets", 176, 144, 8, base().videoPacketSize(120).build()),
                new Case("data partitioning", 176, 144, 8, base().videoPacketSize(150).dataPartitioning(true).build()),
                new Case("reversible codes", 176, 144, 8, base().videoPacketSize(150).dataPartitioning(true)
                        .reversibleVlc(true).fixedQuantizer(3).build()),
                new Case("partitioned VOP without packets", 128, 96, 6, base().dataPartitioning(true)
                        .reversibleVlc(true).build()),
                new Case("partitioned with B-VOPs", 176, 144, 8, advanced().bFrames(2).videoPacketSize(200)
                        .dataPartitioning(true).reversibleVlc(true).build()),
                new Case("finest quantizer", 96, 64, 5, base().fixedQuantizer(1).build()),
                new Case("coarsest quantizer", 96, 64, 5, base().fixedQuantizer(31).build()),
                new Case("one B-VOP", 176, 144, 10, advanced().bFrames(1).build()),
                new Case("two B-VOPs", 176, 144, 11, advanced().bFrames(2).build()),
                new Case("B-VOPs in video packets", 176, 144, 8, advanced().bFrames(2).videoPacketSize(100).build()),
                new Case("B-VOPs in one video packet each", 176, 144, 8, advanced().bFrames(2).videoPacketSize(60000)
                        .build()),
                new Case("quarter sample", 176, 144, 10, advanced().bFrames(2).quarterSample(true).build()),
                new Case("MPEG quantization", 176, 144, 8, advanced().bFrames(1).mpegQuantization(true).build()),
                new Case("downloaded matrices", 176, 144, 8, advanced().bFrames(1).mpegQuantization(true)
                        .intraMatrix(matrix).interMatrix(inter).build()),
                new Case("odd size", 202, 118, 9, advanced().bFrames(2).build()),
                new Case("one macroblock", 16, 16, 6, advanced().bFrames(1).build()),
                new Case("partial macroblock", 9, 7, 6, base().build()),
                new Case("wide search", 176, 144, 6, base().searchRange(64).fixedQuantizer(6).build()),
                new Case("short key frame interval", 128, 96, 14, advanced().bFrames(2).keyframeInterval(5).build()),
                // The background stands still, so P-VOPs skip most macroblocks and the B-VOPs between them leave
                // those macroblocks uncoded, which a B-VOP video packet may not start at.
                new Case("still background", 176, 144, 8, base().build(), true),
                new Case("still background, partitioned", 176, 144, 8, base().videoPacketSize(150)
                        .dataPartitioning(true).build(), true),
                new Case("still background, B-VOPs in video packets", 176, 144, 10, advanced().bFrames(2)
                        .videoPacketSize(100).build(), true),
                // The VOP times cross whole seconds, so modulo_time_base counts seconds for I-, P- and B-VOPs.
                new Case("B-VOPs across seconds", 176, 144, 16, advanced().bFrames(2).timeBase(new Rational(1, 5))
                        .frameRate(new Rational(5, 1)).build()),
                new Case("NTSC clock across a second", 128, 96, 34, advanced().bFrames(2)
                        .timeBase(new Rational(1001, 30000)).frameRate(new Rational(30000, 1001)).build())
        );
    }

    static EncoderScenes.Encoded encode(Case c) {
        try (var encoder = new Mpeg4Encoder(c.width(), c.height(), c.options())) {
            return EncoderScenes.encode(encoder, c.frames(), 1, c.still(), Mpeg4Encoder::lastReconstruction);
        }
    }

    static VideoTrack track(Case c, byte[] config) {
        return VideoTrack.builder().id(1).codec(Codec.MPEG4_PART2).timeBase(c.options().timeBase()).width(c.width())
                .height(c.height()).codecConfig(config).build();
    }

    static Path write(String name, byte[] data) {
        try {
            var file = Scratch.directory("mpeg4-encoder").resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static int vopType(byte[] data) {
        for (var i = 0; i + 4 < data.length; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1 && (data[i + 3] & 0xFF) == 0xB6) {
                return (data[i + 4] & 0xFF) >> 6;
            }
        }
        throw new AssertionError("no VOP start code");
    }

    static String fileName(Case c, String extension) {
        return c.name().replaceAll("[^a-z0-9]+", "-") + "." + extension;
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("reconstructs every picture exactly as Smalt's decoder decodes the stream")
    void reconstruction(Case c) {
        var encoded = encode(c);
        assertEquals(c.frames(), encoded.packets().size(), "one packet per picture");
        var decoded = EncoderScenes.decode(track(c, encoded.config()), encoded.packets(), Mpeg4Decoder::new);
        assertEquals(c.frames(), decoded.size(), "decoded pictures");
        for (var i = 0; i < decoded.size(); i++) {
            var frame = decoded.get(i);
            assertEquals(i, frame.pts(), "presentation order");
            assertArrayEquals(encoded.reconstructions().get(frame.pts()), frame.samples(), c + ": picture " + i);
        }
        var first = encoded.packets().getFirst();
        assertTrue(first.keyframe(), "first packet is a key frame");
        var config = encoded.config();
        assertArrayEquals(config, Arrays.copyOf(first.data(), config.length),
                "the first packet starts with the headers");
        var previous = Long.MIN_VALUE;
        for (var packet : encoded.packets()) {
            assertTrue(packet.dts() <= packet.pts(), "dts " + packet.dts() + " after pts " + packet.pts());
            assertTrue(packet.dts() > previous, "decoding timestamps increase");
            previous = packet.dts();
            assertEquals(vopType(packet.data()) == 2, packet.flags().contains(PacketFlag.DISCARDABLE),
                    "B-VOPs and only they are discardable");
            assertEquals(vopType(packet.data()) == 0, packet.keyframe(), "I-VOPs and only they are key frames");
        }
    }

    @Test
    @DisplayName("the reconstruction tracks the source: PSNR rises as the quantizer falls")
    void quality() {
        var width = 176;
        var height = 144;
        var source = EncoderScenes.source(width, height, 8);
        var previous = 0.0;
        for (var quantizer : new int[]{24, 12, 6, 3}) {
            var c = new Case("q" + quantizer, width, height, 8, advanced().bFrames(1).fixedQuantizer(quantizer)
                    .build());
            var encoded = encode(c);
            var decoded = EncoderScenes.decode(track(c, encoded.config()), encoded.packets(), Mpeg4Decoder::new);
            var psnr = Tolerance.Difference.of(source, EncoderScenes.concat(decoded.stream().map(
                    EncoderScenes.Decoded::samples).toList())).psnr();
            assertTrue(psnr > previous + 1, "quantizer " + quantizer + " gives " + psnr + " dB after " + previous);
            previous = psnr;
        }
        assertTrue(previous > 38, "quantizer 3 gives " + previous + " dB");
    }

    @Test
    @DisplayName("holds the target bitrate within 15 percent")
    void bitrate() {
        for (var target : new long[]{150_000, 400_000}) {
            var c = new Case("rate", 176, 144, 75, advanced().bFrames(2).bitrate(target).keyframeInterval(25).build());
            var encoded = encode(c);
            var bits = 0L;
            for (var packet : encoded.packets()) {
                bits += packet.data().length * 8L;
            }
            var rate = bits * 25.0 / c.frames();
            assertTrue(Math.abs(rate - target) < 0.15 * target, "target " + target + " coded at " + rate);
        }
    }

    @Test
    @DisplayName("codes the same stream on one thread and on four")
    void threads() {
        var one = encode(new Case("one", 352, 288, 6, advanced().bFrames(2).threads(1).build()));
        var four = encode(new Case("four", 352, 288, 6, advanced().bFrames(2).threads(4).build()));
        assertArrayEquals(one.elementary(), four.elementary());
    }

    @Test
    @DisplayName("codes an I-VOP at a key frame request, from which decoding starts")
    void keyframeRequest() {
        var options = advanced().bFrames(2).noPeriodicKeyframes().build();
        var c = new Case("request", 128, 96, 12, options);
        var packets = new ArrayList<Packet>();
        byte[] config;
        try (var encoder = new Mpeg4Encoder(128, 96, options)) {
            config = encoder.codecConfig().orElseThrow();
            for (var i = 0; i < 12; i++) {
                if (i == 7) {
                    encoder.requestKeyframe();
                }
                encoder.encode(new VideoFrame(EncoderScenes.picture(128, 96, i), i, 1, false), packets::add);
            }
            encoder.flush(packets::add);
        }
        var keys = packets.stream().filter(Packet::keyframe).map(Packet::pts).toList();
        assertEquals(List.of(0L, 7L), keys);
        var full = EncoderScenes.decode(track(c, config), packets, Mpeg4Decoder::new);
        var from = packets.indexOf(packets.stream().filter(p -> p.pts() == 7).findFirst().orElseThrow());
        var tail = EncoderScenes.decode(track(c, config), packets.subList(from, packets.size()), Mpeg4Decoder::new);
        assertEquals(5, tail.size(), "decoding from the key frame gives every later picture");
        for (var frame : tail) {
            var match = full.stream().filter(f -> f.pts() == frame.pts()).findFirst().orElseThrow();
            assertArrayEquals(match.samples(), frame.samples(), "picture " + frame.pts());
        }
    }

    @Test
    @DisplayName("writes headers the decoder's parser reads back")
    void headers() {
        var options = advanced().quarterSample(true).mpegQuantization(true).bFrames(1).build();
        try (var encoder = new Mpeg4Encoder(320, 240, options)) {
            var config = encoder.codecConfig().orElseThrow();
            var vol = -1;
            for (var i = 0; i + 4 < config.length; i++) {
                if (config[i] == 0 && config[i + 1] == 0 && config[i + 2] == 1 && (config[i + 3] & 0xF0) == 0x20) {
                    vol = i + 4;
                }
            }
            assertTrue(vol > 0, "video object layer start code");
            assertEquals(0xF0, config[4] & 0xF0, "an Advanced Simple profile level");
            var reader = new BitReader();
            reader.load(config, vol, config.length - vol);
            var layer = VideoObjectLayer.parse(reader, config[4] & 0xFF, 1, MediaLimits.DEFAULT, null);
            assertEquals(320, layer.width);
            assertEquals(240, layer.height);
            assertEquals(17, layer.objectType);
            assertTrue(layer.quarterSample);
            assertTrue(layer.mpegQuant);
            assertFalse(layer.lowDelay);
            assertEquals(25, layer.timeResolution);
            assertFalse(layer.objectTools);
        }
    }

    @Test
    @DisplayName("is found through the codec registry")
    void registry() {
        var options = VideoEncoderOptions.of(176, 144, new Rational(25, 1), 400_000);
        try (var encoder = VideoEncoders.create(Codec.MPEG4_PART2, options)) {
            assertEquals(Codec.MPEG4_PART2, encoder.codec());
            assertEquals(new Rational(1, 25), encoder.timeBase());
            var packets = new ArrayList<Packet>();
            for (var i = 0; i < 6; i++) {
                encoder.encode(new VideoFrame(EncoderScenes.picture(176, 144, i), i, 1, false), packets::add);
            }
            encoder.flush(packets::add);
            assertEquals(6, packets.size());
            var c = new Case("registry", 176, 144, 6, base().build());
            var decoded = EncoderScenes.decode(track(c, encoder.codecConfig().orElseThrow()), packets,
                    Mpeg4Decoder::new);
            assertEquals(6, decoded.size());
        }
        var realtime = VideoEncoderOptions.forRealtime(176, 144, new Rational(30, 1), 300_000);
        try (var encoder = VideoEncoders.create(Codec.MPEG4_PART2, realtime)) {
            assertEquals(Mpeg4Profile.SIMPLE, ((Mpeg4Encoder) encoder).options().profile());
        }
    }

    @Test
    @DisplayName("rejects invalid options and sizes")
    void rejects() {
        assertThrows(IllegalArgumentException.class, () -> base().bFrames(1).build());
        assertThrows(IllegalArgumentException.class, () -> base().quarterSample(true).build());
        assertThrows(IllegalArgumentException.class, () -> base().mpegQuantization(true).build());
        assertThrows(IllegalArgumentException.class, () -> advanced().bFrames(5).build());
        assertThrows(IllegalArgumentException.class, () -> base().fixedQuantizer(0).build());
        assertThrows(IllegalArgumentException.class, () -> base().quantizers(10, 5).build());
        assertThrows(IllegalArgumentException.class, () -> base().videoPacketSize(8).build());
        assertThrows(IllegalArgumentException.class, () -> base().reversibleVlc(true).build());
        assertThrows(IllegalArgumentException.class, () -> base().intraMatrix(new int[63]).build());
        assertThrows(IllegalArgumentException.class, () -> base().intraMatrix(new int[64]).build());
        assertThrows(IllegalArgumentException.class, () -> new Mpeg4Encoder(0, 16));
        assertThrows(IllegalArgumentException.class, () -> new Mpeg4Encoder(8192, 16));
        try (var encoder = new Mpeg4Encoder(32, 32)) {
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(
                    new VideoFrame(EncoderScenes.picture(16, 16, 0), 0, 1, false), packet -> { }));
            encoder.encode(new VideoFrame(EncoderScenes.picture(32, 32, 0), 0, 1, false), packet -> { });
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(
                    new VideoFrame(EncoderScenes.picture(32, 32, 1), 0, 1, false), packet -> { }));
            assertThrows(IllegalArgumentException.class, () -> encoder.setTargetBitrate(0));
        }
        try (var encoder = new Mpeg4Encoder(32, 32, base().fixedQuantizer(4).build())) {
            assertThrows(IllegalStateException.class, () -> encoder.setTargetBitrate(100_000));
        }
        var options = advanced().bFrames(2).build();
        assertEquals(options, options.toBuilder().build());
        assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
    }

    @Test
    @DisplayName("skips the macroblocks of an unchanged picture and leaves B-VOP macroblocks over them uncoded")
    void unchangedPicture() {
        var options = advanced().bFrames(2).fixedQuantizer(4).build();
        var picture = EncoderScenes.picture(176, 144, 0);
        var packets = new ArrayList<Packet>();
        try (var encoder = new Mpeg4Encoder(176, 144, options)) {
            for (var i = 0; i < 10; i++) {
                encoder.encode(new VideoFrame(picture, i, 1, false), packets::add);
            }
            encoder.flush(packets::add);
            var decoded = EncoderScenes.decode(track(new Case("unchanged", 176, 144, 10, options),
                    encoder.codecConfig().orElseThrow()), packets, Mpeg4Decoder::new);
            assertEquals(10, decoded.size());
        }
        // Decoding order I0 P3 B1 B2 P6 B4 B5 P9 B7 B8; P3 may still refine the I-VOP's reconstruction. Of the 99
        // macroblocks a skipped P-VOP one costs its not_coded bit, a B-VOP one over it nothing.
        assertEquals(List.of(0L, 3L, 1L, 2L, 6L, 4L, 5L, 9L, 7L, 8L), packets.stream().map(Packet::pts).toList());
        for (var packet : packets.subList(4, packets.size())) {
            var type = vopType(packet.data());
            if (type == 1) {
                assertTrue(packet.data().length <= 28, "P-VOP " + packet.pts() + " of " + packet.data().length
                        + " bytes");
            } else if (type == 2) {
                assertTrue(packet.data().length <= 12, "B-VOP " + packet.pts() + " of " + packet.data().length
                        + " bytes");
            }
        }
    }

    @Test
    @DisplayName("takes the VOP time resolution from the frame rate under the default 90 kHz time base")
    void defaultTimeBase() {
        var options = Mpeg4EncoderOptions.builder().profile(Mpeg4Profile.ADVANCED_SIMPLE).bFrames(2)
                .frameRate(new Rational(30000, 1001)).build();
        var c = new Case("90 kHz", 128, 96, 40, options);
        EncoderScenes.Encoded encoded;
        try (var encoder = new Mpeg4Encoder(128, 96, options)) {
            encoded = EncoderScenes.encode(encoder, c.frames(), 3003, false, Mpeg4Encoder::lastReconstruction);
        }
        var config = encoded.config();
        var reader = new BitReader();
        var vol = 0;
        for (var i = 0; i + 4 < config.length; i++) {
            if (config[i] == 0 && config[i + 1] == 0 && config[i + 2] == 1 && (config[i + 3] & 0xF0) == 0x20) {
                vol = i + 4;
            }
        }
        reader.load(config, vol, config.length - vol);
        assertEquals(30000, VideoObjectLayer.parse(reader, config[4] & 0xFF, 1, MediaLimits.DEFAULT, null)
                .timeResolution);
        var decoded = EncoderScenes.decode(track(c, config), encoded.packets(), Mpeg4Decoder::new);
        assertEquals(c.frames(), decoded.size());
        for (var i = 0; i < decoded.size(); i++) {
            assertEquals(i * 3003L, decoded.get(i).pts(), "presentation order");
            assertArrayEquals(encoded.reconstructions().get(i * 3003L), decoded.get(i).samples(), "picture " + i);
        }
        var sink = MediaSink.memory();
        try (var muxer = Containers.openMuxer(com.github.auties00.smalt.container.ContainerFormat.MPEG4_VIDEO,
                sink)) {
            var track = track(c, config);
            muxer.addTrack(track);
            for (var packet : encoded.packets()) {
                muxer.writePacket(packet.withTrackId(track.id()));
            }
            muxer.finish();
        }
        // The raw stream carries only the VOP times: they give every picture its time, 1001/30000 s apart.
        var times = new ArrayList<Long>();
        try (var source = MediaSource.of(sink.toByteArray()); var demuxer = Containers.openDemuxer(source)) {
            var read = demuxer.tracks().getFirst();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                times.add(read.timeBase().rescale(packet.pts(), new Rational(1, 30000)));
            }
        }
        times.sort(null);
        for (var i = 0; i < times.size(); i++) {
            assertEquals(times.getFirst() + i * 1001L, times.get(i), "VOP time of picture " + i + ": " + times);
        }
    }

    @Test
    @DisplayName("follows a new target bitrate")
    void retarget() {
        var packets = new ArrayList<Packet>();
        try (var encoder = new Mpeg4Encoder(176, 144, base().bitrate(400_000).keyframeInterval(25).build())) {
            for (var i = 0; i < 125; i++) {
                if (i == 50) {
                    encoder.setTargetBitrate(100_000);
                }
                encoder.encode(new VideoFrame(EncoderScenes.picture(176, 144, i), i, 1, false), packets::add);
            }
        }
        var before = packets.subList(0, 50).stream().mapToLong(p -> p.data().length * 8L).sum() * 25.0 / 50;
        var after = packets.subList(75, 125).stream().mapToLong(p -> p.data().length * 8L).sum() * 25.0 / 50;
        assertTrue(Math.abs(before - 400_000) < 0.2 * 400_000, "coded at " + before + " before the change");
        assertTrue(Math.abs(after - 100_000) < 0.2 * 100_000, "coded at " + after + " after the change");
    }

    @Test
    @DisplayName("converts pictures of another representation before coding them")
    void convertsInput() {
        var picture = EncoderScenes.picture(64, 48, 3);
        var fullRange = com.github.auties00.smalt.image.YuvPicture.builder().size(64, 48)
                .chromaFormat(com.github.auties00.smalt.image.ChromaFormat.YUV420)
                .color(com.github.auties00.smalt.image.ColorInfo.JPEG).luma(picture.y(), picture.yStride())
                .chroma(picture.u(), picture.v(), picture.uvStride()).build();
        var rgba = new byte[64 * 48 * 4];
        for (var i = 0; i < 64 * 48; i++) {
            rgba[4 * i] = (byte) (i * 7);
            rgba[4 * i + 1] = (byte) (i / 64 * 5);
            rgba[4 * i + 2] = (byte) (255 - i % 64 * 3);
            rgba[4 * i + 3] = (byte) 255;
        }
        var rgb = com.github.auties00.smalt.image.RgbaPicture.of(64, 48, 64 * 4, rgba, false);
        for (var input : List.<com.github.auties00.smalt.image.Picture>of(fullRange, rgb)) {
            var converted = com.github.auties00.smalt.image.ops.YuvConverter.create(
                    com.github.auties00.smalt.image.ChromaFormat.YUV420,
                    com.github.auties00.smalt.image.ColorInfo.BT601_LIMITED).convert(input);
            byte[] direct;
            byte[] expected;
            try (var encoder = new Mpeg4Encoder(64, 48, base().fixedQuantizer(3).build())) {
                var out = new ArrayList<Packet>();
                encoder.encode(new VideoFrame(input, 0, 1, false), out::add);
                direct = out.getFirst().data();
            }
            try (var encoder = new Mpeg4Encoder(64, 48, base().fixedQuantizer(3).build())) {
                var out = new ArrayList<Packet>();
                encoder.encode(new VideoFrame(converted, 0, 1, false), out::add);
                expected = out.getFirst().data();
            }
            assertArrayEquals(expected, direct, input.getClass().getSimpleName() + " input");
        }
    }

    @Test
    @DisplayName("refuses work once closed")
    void closed() {
        var encoder = new Mpeg4Encoder(32, 32);
        encoder.close();
        assertThrows(IllegalStateException.class, () -> encoder.encode(new VideoFrame(EncoderScenes.picture(32, 32,
                0), 0, 1, false), packet -> { }));
        assertThrows(IllegalStateException.class, () -> encoder.flush(packet -> { }));
        assertThrows(IllegalStateException.class, () -> encoder.setTargetBitrate(100_000));
    }

    @Nested
    @DisplayName("against ffmpeg and the reference decoder")
    class Oracles {
        // The largest sample differences the inverse DCTs leave: Smalt's IEEE 1180 transform, ffmpeg's integer one
        // and the reference decoder's differ by a level in a few samples, which the predictions carry along
        // (measured: at most 3 against ffmpeg and 4 against the reference decoder, quarter samples included).
        static final int FFMPEG_MAX_DIFFERENCE = 3;
        static final int REFERENCE_MAX_DIFFERENCE = 4;
        // With partial macroblocks the references differ beyond the VOP boundary: Smalt and ffmpeg keep the decoded
        // samples there, the reference decoder pads from the boundary (see the README), so predictions reaching
        // past it differ by the quantization error of those samples (measured: 5 against ffmpeg, 10 against the
        // reference decoder at 57.7 dB).
        static final int FFMPEG_PARTIAL_MAX_DIFFERENCE = 6;
        static final int REFERENCE_PARTIAL_MAX_DIFFERENCE = 12;

        static boolean partialMacroblocks(Case c) {
            return c.width() % 16 != 0 || c.height() % 16 != 0;
        }

        static Stream<Case> oracleCases() {
            return cases().filter(c -> c.width() >= 16 && c.height() >= 16);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("oracleCases")
        @DisplayName("ffmpeg decodes the stream within the inverse DCT noise floor of Smalt's decode")
        void ffmpeg(Case c) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var encoded = encode(c);
            var file = write(fileName(c, "m4v"), encoded.elementary());
            var integer = Mpeg4TestStreams.ffmpeg(file, "int");
            var simple = Mpeg4TestStreams.ffmpeg(file, "simple");
            var decoded = EncoderScenes.concat(EncoderScenes.decode(track(c, encoded.config()), encoded.packets(),
                    Mpeg4Decoder::new).stream().map(EncoderScenes.Decoded::samples).toList());
            assertEquals(decoded.length, integer.length, "ffmpeg's decoded size");
            var bound = partialMacroblocks(c) ? FFMPEG_PARTIAL_MAX_DIFFERENCE : FFMPEG_MAX_DIFFERENCE;
            Mpeg4DifferentialTest.idctNoiseFloor(integer, simple).and(Tolerance.maxAbs(bound)).assertWithin(c
                    + " against ffmpeg -idct int" + EncoderScenes.differences(integer, decoded, c.width(), c.height(),
                    bound), integer, decoded);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("oracleCases")
        @DisplayName("the ISO/IEC 14496-5 reference decoder decodes the stream within the inverse DCT drift of"
                + " Smalt's decode")
        void reference(Case c) {
            var encoded = encode(c);
            var file = write(fileName(c, "bits"), encoded.elementary());
            var reference = Mpeg4TestStreams.reference(file, c.width(), c.height(), file.getParent());
            var decoded = EncoderScenes.concat(EncoderScenes.decode(track(c, encoded.config()), encoded.packets(),
                    Mpeg4Decoder::new).stream().map(EncoderScenes.Decoded::samples).toList());
            assertEquals(decoded.length, reference.length, "the reference decoder's decoded size");
            var bound = partialMacroblocks(c) ? REFERENCE_PARTIAL_MAX_DIFFERENCE : REFERENCE_MAX_DIFFERENCE;
            Tolerance.psnr(55).and(Tolerance.maxAbs(bound)).assertWithin(c + " against the reference decoder"
                    + EncoderScenes.differences(reference, decoded, c.width(), c.height(), bound), reference,
                    decoded);
        }
    }

    @Nested
    @DisplayName("in containers")
    class InContainers {
        static Stream<String> containers() {
            return Stream.of("mp4", "3gp", "avi", "mkv", "m4v");
        }

        static Muxer muxer(String container, MediaSink sink) {
            return switch (container) {
                case "mp4" -> new Mp4Muxer(sink);
                case "3gp" -> new Mp4Muxer(sink, Mp4MuxerOptions.builder().brand(Mp4MuxerOptions.Brand.THREE_GPP)
                        .build());
                case "avi" -> new AviMuxer(sink);
                case "mkv" -> new MatroskaMuxer(sink);
                default -> Containers.openMuxer(com.github.auties00.smalt.container.ContainerFormat.MPEG4_VIDEO,
                        sink);
            };
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("containers")
        @DisplayName("muxes the stream, demuxes the same packets and decodes the same pictures")
        void roundTrip(String container) {
            var c = new Case("container", 176, 144, 10, advanced().bFrames(2).build());
            var encoded = encode(c);
            var track = track(c, encoded.config());
            var sink = MediaSink.memory();
            try (var muxer = muxer(container, sink)) {
                muxer.addTrack(track);
                for (var packet : encoded.packets()) {
                    muxer.writePacket(new Packet(track.id(), packet.data(), packet.pts(), packet.dts(),
                            packet.duration(), packet.flags(), packet.sideData()));
                }
                muxer.finish();
            }
            var bytes = sink.toByteArray();
            var demuxed = new ArrayList<Packet>();
            VideoTrack read;
            try (var source = MediaSource.of(bytes); var demuxer = Containers.openDemuxer(source)) {
                read = (VideoTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.MPEG4_PART2, read.codec());
                if (!container.equals("m4v")) {
                    assertEquals(176, read.width());
                    assertEquals(144, read.height());
                }
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    demuxed.add(packet);
                }
            }
            assertEquals(encoded.packets().size(), demuxed.size(), "packets");
            for (var i = 0; i < demuxed.size(); i++) {
                var expected = encoded.packets().get(i).data();
                var actual = demuxed.get(i).data();
                var trimmed = actual.length > expected.length ? Arrays.copyOfRange(actual, actual.length
                        - expected.length, actual.length) : actual;
                assertArrayEquals(expected, trimmed, container + " packet " + i);
            }
            var decoded = EncoderScenes.decode(read, demuxed, Mpeg4Decoder::new);
            assertEquals(c.frames(), decoded.size());
            for (var i = 0; i < decoded.size(); i++) {
                assertArrayEquals(encoded.reconstructions().get((long) i), decoded.get(i).samples(),
                        container + " picture " + i);
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var file = write("round-trip." + container, bytes);
                Oracle.assertDecodesCleanly(file);
            }
        }
    }
}
