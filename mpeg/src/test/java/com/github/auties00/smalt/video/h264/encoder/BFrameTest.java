package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.H264Decoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers B pictures and look-ahead: the stream decodes to exactly the encoder's reconstruction
 * of every picture, packets come out in decoding order with consistent timestamps, and the
 * options reject combinations the profile or the delay cannot honor.
 */
class BFrameTest {
    /** Encodes pictures through the core, capturing the reconstruction of every coded picture by display index. */
    private static EncoderHarness.Encoded encode(H264Setup setup, int frames, int threads) {
        var pictures = EncoderHarness.sequence(setup.width(), setup.height(), frames, 7L * setup.width(), 2.0);
        var packets = new ArrayList<Packet>();
        var byDisplay = new TreeMap<Long, byte[]>();
        try (var encoder = setup.withThreads(threads).encoder()) {
            var core = encoder.core();
            core.setReconstructUnreferenced(true);
            long pts = 0;
            for (var picture : pictures) {
                var packet = core.encode(picture, pts++, 0);
                if (packet != null) {
                    packets.add(packet);
                    byDisplay.put(core.lastDisplayIndex(), core.lastReconstructionI420());
                }
            }
            for (var packet = core.flush(); packet != null; packet = core.flush()) {
                packets.add(packet);
                byDisplay.put(core.lastDisplayIndex(), core.lastReconstructionI420());
            }
            return new EncoderHarness.Encoded(setup, packets, new ArrayList<>(byDisplay.values()),
                    core.sequenceParameterSet(), core.pictureParameterSet(), core.configurationRecord(),
                    core.typeCounts());
        }
    }

    private static H264Setup setup(int width, int height, H264Profile profile, H264EncoderSpeed speed, H264RateControl rc,
                                   int lookahead, int bFrames) {
        var base = EncoderHarness.config(width, height, profile, speed, rc, Math.max(20_000, width * height * 30 / 8), 26, 1);
        return new H264Setup(width, height, base.options().toBuilder().lookahead(lookahead).bFrames(bFrames).build());
    }

    static Stream<Arguments> streams() {
        var arguments = new ArrayList<Arguments>();
        for (var profile : List.of(H264Profile.MAIN, H264Profile.HIGH)) {
            for (var speed : H264EncoderSpeed.values()) {
                for (var bFrames : List.of(1, 2, 3, 4, 5, 7)) {
                    arguments.add(Arguments.of(profile, speed, 176, 144, 22, bFrames, 1, H264RateControl.CONSTANT_QUALITY));
                }
                arguments.add(Arguments.of(profile, speed, 176, 144, 22, 2, 4, H264RateControl.AVERAGE_BITRATE));
            }
            arguments.add(Arguments.of(profile, H264EncoderSpeed.BALANCED, 33, 17, 14, 2, 1, H264RateControl.CONSTANT_QUALITY));
            arguments.add(Arguments.of(profile, H264EncoderSpeed.BALANCED, 18, 18, 9, 3, 2, H264RateControl.AVERAGE_BITRATE));
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0} {1} {2}x{3} frames={4} bFrames={5} threads={6} {7}")
    @MethodSource("streams")
    @DisplayName("ffmpeg decodes B pictures to exactly the encoder's reconstruction")
    void bitExact(H264Profile profile, H264EncoderSpeed speed, int width, int height, int frames, int bFrames, int threads,
                  H264RateControl rc) {
        var encoded = encode(setup(width, height, profile, speed, rc, bFrames + 6, bFrames), frames, threads);
        var name = "bframes-" + profile + "-" + speed + "-" + width + "x" + height + "-b" + bFrames + "-t" + threads + "-" + rc;
        assertEquals(frames, encoded.packets().size());
        assertEquals(frames, encoded.reconstructions().size());
        assertTrue(encoded.typeCounts()[MbTypes.B_SKIP] + encoded.typeCounts()[MbTypes.B_L0]
                + encoded.typeCounts()[MbTypes.B_L1] + encoded.typeCounts()[MbTypes.B_BI]
                + encoded.typeCounts()[MbTypes.B_DIRECT] > 0, "the stream contains B macroblocks");
        EncoderHarness.assertBitExact(name, encoded);
    }

    @ParameterizedTest(name = "{0} frames")
    @org.junit.jupiter.params.provider.ValueSource(ints = {1, 2, 3, 4, 5, 6, 9})
    @DisplayName("streams shorter than the look-ahead decode to exactly the encoder's reconstruction")
    void shortStreams(int frames) {
        var encoded = encode(setup(48, 32, H264Profile.MAIN, H264EncoderSpeed.BALANCED, H264RateControl.CONSTANT_QUALITY,
                10, 3), frames, 1);
        assertEquals(frames, encoded.packets().size());
        EncoderHarness.assertBitExact("bframes-short-" + frames, encoded);
    }

    @ParameterizedTest(name = "{0} {1} threads={2}")
    @MethodSource("lookaheadOnlyStreams")
    @DisplayName("a look-ahead without B pictures decodes to exactly the encoder's reconstruction, with either entropy coder")
    void lookaheadOnly(H264Profile profile, H264EncoderSpeed speed, int threads) {
        var encoded = encode(setup(176, 144, profile, speed, H264RateControl.AVERAGE_BITRATE, 12, 0), 26, threads);
        assertEquals(26, encoded.packets().size());
        EncoderHarness.assertBitExact("lookahead-" + profile + "-" + speed + "-t" + threads, encoded);
    }

    static Stream<Arguments> lookaheadOnlyStreams() {
        var arguments = new ArrayList<Arguments>();
        for (var profile : H264Profile.values()) {
            for (var speed : List.of(H264EncoderSpeed.FASTEST, H264EncoderSpeed.BALANCED)) {
                arguments.add(Arguments.of(profile, speed, 1));
                arguments.add(Arguments.of(profile, speed, 3));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0} {1} bFrames={2}")
    @MethodSource("decoderStreams")
    @DisplayName("Smalt's decoder outputs the B pictures in presentation order, equal to the encoder's reconstruction")
    void smaltDecoder(H264Profile profile, H264EncoderSpeed speed, int bFrames) {
        var width = 96;
        var height = 64;
        var frames = 20;
        var encoded = encode(setup(width, height, profile, speed, H264RateControl.CONSTANT_QUALITY, bFrames + 6, bFrames),
                frames, 1);
        var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 30)).width(width).height(height)
                .codecConfig(encoded.avcC()).build();
        var decoded = new ArrayList<byte[]>();
        try (var decoder = new H264Decoder(track)) {
            for (var packet : encoded.packets()) {
                decoder.decode(packet, frame -> decoded.add(i420((YuvPicture) frame.picture())));
            }
            decoder.flush(frame -> decoded.add(i420((YuvPicture) frame.picture())));
        }
        assertEquals(frames, decoded.size());
        for (var i = 0; i < frames; i++) {
            assertArrayEquals(encoded.reconstructions().get(i), decoded.get(i), "picture " + i);
        }
    }

    static Stream<Arguments> decoderStreams() {
        var arguments = new ArrayList<Arguments>();
        for (var profile : List.of(H264Profile.MAIN, H264Profile.HIGH)) {
            for (var speed : List.of(H264EncoderSpeed.FAST, H264EncoderSpeed.BALANCED, H264EncoderSpeed.SLOW)) {
                arguments.add(Arguments.of(profile, speed, 2));
                arguments.add(Arguments.of(profile, speed, 3));
                arguments.add(Arguments.of(profile, speed, 5));
                arguments.add(Arguments.of(profile, speed, 7));
            }
        }
        return arguments.stream();
    }

    private static byte[] i420(YuvPicture picture) {
        var w = picture.width();
        var h = picture.height();
        var samples = new byte[w * h * 3 / 2];
        for (var y = 0; y < h; y++) {
            System.arraycopy(picture.y(), y * picture.yStride(), samples, y * w, w);
        }
        for (var y = 0; y < h / 2; y++) {
            System.arraycopy(picture.u(), y * picture.uvStride(), samples, w * h + y * w / 2, w / 2);
            System.arraycopy(picture.v(), y * picture.uvStride(), samples, w * h * 5 / 4 + y * w / 2, w / 2);
        }
        return samples;
    }

    /** The macroblock type indices of the writer's type counts. */
    private static final class MbTypes {
        static final int B_DIRECT = 10;
        static final int B_SKIP = 11;
        static final int B_L0 = 12;
        static final int B_L1 = 13;
        static final int B_BI = 14;
    }

    @Nested
    @DisplayName("packets")
    class Packets {
        @Test
        @DisplayName("come out in decoding order with every presentation timestamp once and decoding timestamps that never decrease")
        void timestamps() {
            var setup = setup(96, 64, H264Profile.MAIN, H264EncoderSpeed.BALANCED, H264RateControl.CONSTANT_QUALITY, 8, 3);
            var pictures = EncoderHarness.sequence(96, 64, 40, 3, 2.0);
            var packets = new ArrayList<Packet>();
            try (var encoder = setup.encoder()) {
                long pts = 0;
                for (var picture : pictures) {
                    encoder.encode(new VideoFrame(picture, pts, 1, false), packets::add);
                    pts++;
                }
                assertTrue(packets.size() < pictures.size(), "the look-ahead holds the last pictures");
                encoder.flush(packets::add);
            }
            assertEquals(pictures.size(), packets.size());
            var seen = new HashSet<Long>();
            var previous = Long.MIN_VALUE;
            var previousPts = Long.MIN_VALUE;
            var reordered = false;
            for (var packet : packets) {
                assertTrue(seen.add(packet.pts()), "presentation timestamp " + packet.pts() + " appears once");
                assertTrue(packet.dts() <= packet.pts(), "decoding timestamp does not follow presentation");
                assertTrue(packet.dts() > previous, "decoding timestamps increase");
                previous = packet.dts();
                reordered |= packet.pts() < previousPts;
                previousPts = packet.pts();
            }
            assertTrue(reordered, "B pictures reorder the packets");
            for (long i = 0; i < pictures.size(); i++) {
                assertTrue(seen.contains(i), "picture " + i + " has a packet");
            }
            assertTrue(packets.getFirst().keyframe());
        }

        @Test
        @DisplayName("a key frame request ends the group before the requested picture")
        void keyframeRequest() {
            var setup = setup(96, 64, H264Profile.MAIN, H264EncoderSpeed.BALANCED, H264RateControl.CONSTANT_QUALITY, 8, 3);
            var pictures = EncoderHarness.sequence(96, 64, 30, 5, 2.0);
            var packets = new ArrayList<Packet>();
            try (var encoder = setup.encoder()) {
                long pts = 0;
                for (var picture : pictures) {
                    if (pts == 13) {
                        encoder.requestKeyframe();
                    }
                    encoder.encode(new VideoFrame(picture, pts++, 1, false), packets::add);
                }
                encoder.flush(packets::add);
            }
            var key = packets.stream().filter(Packet::keyframe).map(Packet::pts).toList();
            assertTrue(key.contains(13L), "picture 13 is a key frame, got " + key);
            var coded = packets.stream().map(Packet::pts).toList();
            for (var packet : packets) {
                if (packet.pts() < 13) {
                    assertTrue(coded.indexOf(packet.pts()) < coded.indexOf(13L), "pictures before the key frame precede it");
                }
            }
        }
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("the look-ahead and the B frames stay in range, and unusable combinations code no B pictures")
        void validation() {
            var file = H264EncoderOptions.forFile();
            assertThrows(IllegalArgumentException.class, () -> file.toBuilder().bFrames(9).build());
            assertThrows(IllegalArgumentException.class, () -> file.toBuilder().bFrames(-1).build());
            assertThrows(IllegalArgumentException.class, () -> file.toBuilder().lookahead(121).build());
            assertThrows(IllegalArgumentException.class, () -> file.toBuilder().lookahead(-1).build());
            assertFalse(H264EncoderOptions.forRealtime().lookahead() > 0);
            assertEquals(0, H264EncoderOptions.forRealtime().bFrames());
            assertEquals(0, file.toBuilder().lookahead(0).build().lookahead());
            for (var options : List.of(file.toBuilder().lookahead(0).build(), file.toBuilder().lookahead(1).build(), file.toBuilder().profile(H264Profile.CONSTRAINED_BASELINE).build())) {
                var packets = new ArrayList<Packet>();
                try (var encoder = new H264Encoder(64, 48, options)) {
                    for (var i = 0; i < 6; i++) {
                        encoder.encode(new VideoFrame(EncoderHarness.synthetic(64, 48, i, 1, 1.0), i, 1, false), packets::add);
                    }
                    encoder.flush(packets::add);
                }
                assertEquals(6, packets.size());
                assertTrue(packets.stream().allMatch(packet -> packet.dts() == packet.pts()), "no reordering: " + options);
            }
        }

        @Test
        @DisplayName("a real-time encoder with a look-ahead configured still emits a packet per frame")
        void realtimeIgnoresLookahead() {
            var options = H264EncoderOptions.forRealtime();
            var picture = EncoderHarness.synthetic(64, 48, 0, 1, 1.0);
            try (var encoder = new H264Encoder(64, 48, options)) {
                var packets = new ArrayList<Packet>();
                encoder.encode(new VideoFrame(picture, 0, 0, false), packets::add);
                assertEquals(1, packets.size());
            }
        }
    }
}
