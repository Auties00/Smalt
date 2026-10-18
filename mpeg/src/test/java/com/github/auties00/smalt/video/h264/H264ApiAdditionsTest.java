package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.h264.encoder.H264Encoder;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderOptions;
import com.github.auties00.smalt.video.h264.encoder.H264Level;
import com.github.auties00.smalt.video.h264.encoder.H264Profile;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the integrity-reporting decoder entry points {@code decode(Packet, FrameConsumer)} and
 * {@code decodeIncomplete}, and the typed {@link H264StreamInfo} accessors.
 */
@DisplayName("H.264 decoder API additions")
class H264ApiAdditionsTest {
    private static final int SIZE = 32;

    private static final VideoTrack TRACK = VideoTrack.builder().id(1).codec(Codec.H264)
            .timeBase(new Rational(1, 90_000)).width(SIZE).height(SIZE).build();

    private static YuvPicture picture(int frame) {
        var picture = YuvPicture.allocate(SIZE, SIZE, ChromaFormat.YUV420, false, ColorInfo.BT601_LIMITED);
        for (var row = 0; row < SIZE; row++) {
            for (var col = 0; col < SIZE; col++) {
                picture.y()[row * picture.yStride() + col] = (byte) (40 + ((col * 5 + row * 3 + frame * 11) & 127));
            }
        }
        Arrays.fill(picture.u(), (byte) 128);
        Arrays.fill(picture.v(), (byte) 128);
        return picture;
    }

    private static List<Packet> encode(int frames) {
        var packets = new ArrayList<Packet>();
        try (var encoder = new H264Encoder(SIZE, SIZE, H264EncoderOptions.forRealtime())) {
            for (var i = 0; i < frames; i++) {
                encoder.encode(new VideoFrame(picture(i), i * 3000L, 3000, false), packets::add);
            }
            encoder.flush(packets::add);
        }
        return packets;
    }

    private record Output(byte[] samples, boolean damaged) {
        @Override
        public boolean equals(Object other) {
            return other instanceof Output that && Arrays.equals(samples, that.samples) && damaged == that.damaged;
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(samples) * 31 + Boolean.hashCode(damaged);
        }
    }

    private interface Feed {
        void feed(H264Decoder decoder, Packet packet, List<Output> outputs);
    }

    private static List<Output> run(List<Packet> packets, Feed feed) {
        var outputs = new ArrayList<Output>();
        try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.forRealtime())) {
            for (var packet : packets) {
                feed.feed(decoder, packet, outputs);
            }
        }
        return outputs;
    }

    private static H264Decoder.FrameConsumer collect(List<Output> outputs) {
        return (frame, damaged) -> outputs.add(new Output(
                H264TestStreams.serialize((YuvPicture) frame.picture()), damaged));
    }

    private static List<Output> plain(List<Packet> packets) {
        return run(packets, (decoder, packet, out) -> decoder.decode(packet, (Consumer<VideoFrame>) frame -> out.add(
                new Output(H264TestStreams.serialize((YuvPicture) frame.picture()), false))));
    }

    @Nested
    @DisplayName("decode and decodeIncomplete")
    class Decoding {
        @Test
        @DisplayName("decode(Packet, FrameConsumer) outputs the frames of decode(Packet, Consumer), intact")
        void whole() {
            var packets = encode(4);
            var expected = plain(packets);
            var actual = run(packets, (decoder, packet, out) -> decoder.decode(packet, collect(out)));
            assertEquals(4, actual.size());
            assertEquals(expected, actual);
            assertTrue(actual.stream().noneMatch(Output::damaged));
        }

        @Test
        @DisplayName("decodeIncomplete decodes the same samples and flags the picture damaged")
        void incomplete() {
            var packets = encode(3);
            var expected = plain(packets);
            var actual = run(packets, (decoder, packet, out) -> decoder.decodeIncomplete(packet, collect(out)));
            assertEquals(expected.stream().map(output -> Arrays.toString(output.samples())).toList(),
                    actual.stream().map(output -> Arrays.toString(output.samples())).toList());
            assertFalse(actual.isEmpty());
            assertTrue(actual.getFirst().damaged());
            try (var decoder = new H264Decoder(TRACK, H264DecoderOptions.forRealtime())) {
                decoder.decodeIncomplete(packets.getFirst(), (frame, damaged) -> { });
                assertTrue(decoder.keyframeNeeded());
            }
        }
    }

    @Nested
    @DisplayName("H264StreamInfo")
    class StreamInfo {
        private static byte[] sps(int profileIdc, int constraintFlags, int levelIdc) {
            var writer = new H264BitWriter();
            writer.bits(profileIdc, 8).bits(constraintFlags, 8).bits(levelIdc, 8).ue(0);
            switch (profileIdc) {
                case 100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135 ->
                        writer.ue(1).ue(0).ue(0).bit(0).bit(0);
                default -> {
                }
            }
            writer.ue(0).ue(2).ue(1).bit(0).ue(1).ue(1).bit(1).bit(1).bit(0).bit(0);
            return H264BitWriter.nal(3, 7, writer.trailing());
        }

        @ParameterizedTest(name = "profile_idc {0}, flags {1} -> {2}")
        @CsvSource({
                "66, 0x00, Baseline",
                "66, 0x40, Constrained Baseline",
                "66, 0xC0, Constrained Baseline",
                "77, 0x00, Main",
                "77, 0x80, Main",
                "88, 0x00, Extended",
                "100, 0x00, High",
                "100, 0x08, Progressive High",
                "100, 0x0C, Constrained High",
                "110, 0x00, High 10",
                "110, 0x10, High 10 Intra",
                "110, 0x08, Progressive High 10",
                "122, 0x00, High 4:2:2",
                "122, 0x10, High 4:2:2 Intra",
                "244, 0x00, High 4:4:4 Predictive",
                "244, 0x10, High 4:4:4 Intra",
                "44, 0x10, CAVLC 4:4:4 Intra",
                "83, 0x00, Scalable Baseline",
                "83, 0x04, Scalable Constrained Baseline",
                "86, 0x00, Scalable High",
                "86, 0x04, Scalable Constrained High",
                "86, 0x10, Scalable High Intra",
                "118, 0x00, Multiview High",
                "128, 0x00, Stereo High"
        })
        @DisplayName("names the profile from profile_idc and the constraint flags")
        void profileName(int profileIdc, String flags, String name) {
            var info = H264StreamInfo.probe(sps(profileIdc, Integer.decode(flags), 30));
            assertEquals(Optional.of(name), info.profileName());
        }

        @Test
        @DisplayName("gives no name to a profile_idc the standard does not define")
        void unknownProfile() {
            assertEquals(Optional.empty(), H264StreamInfo.probe(sps(1, 0, 30)).profileName());
        }

        @ParameterizedTest(name = "profile_idc {0}, flags {1} -> {2}")
        @CsvSource({
                "66, 0x40, true",
                "66, 0xC0, true",
                "66, 0x80, false",
                "77, 0x80, true",
                "77, 0x40, false",
                "88, 0xC0, true",
                "88, 0x80, false",
                "100, 0xC0, false"
        })
        @DisplayName("isConstrainedBaseline follows the RFC 6184 profile and flag combinations")
        void constrainedBaseline(int profileIdc, String flags, boolean expected) {
            assertEquals(expected, H264StreamInfo.probe(sps(profileIdc, Integer.decode(flags), 30))
                    .isConstrainedBaseline());
        }

        @ParameterizedTest(name = "profile_idc {0}, flags {1}, level_idc {2} -> {3}")
        @CsvSource({
                "66, 0x00, 10, LEVEL_1",
                "66, 0x10, 11, LEVEL_1B",
                "77, 0x10, 11, LEVEL_1B",
                "88, 0x10, 11, LEVEL_1B",
                "66, 0x00, 11, LEVEL_1_1",
                "100, 0x00, 9, LEVEL_1B",
                "100, 0x10, 11, LEVEL_1_1",
                "100, 0x00, 31, LEVEL_3_1",
                "100, 0x00, 62, LEVEL_6_2"
        })
        @DisplayName("resolves level_idc to a level, with both encodings of level 1b")
        void level(int profileIdc, String flags, int levelIdc, H264Level expected) {
            var info = H264StreamInfo.probe(sps(profileIdc, Integer.decode(flags), levelIdc));
            assertEquals(levelIdc, info.levelIdc());
            assertEquals(Optional.of(expected), info.level());
        }

        @Test
        @DisplayName("reports no level for a level_idc outside Annex A")
        void unknownLevel() {
            assertEquals(Optional.empty(), H264StreamInfo.probe(sps(100, 0, 33)).level());
        }

        @Test
        @DisplayName("describes the encoder's own streams consistently with its options")
        void encoderStreams() {
            var realtime = H264StreamInfo.probe(encode(1).getFirst().data());
            assertTrue(realtime.isConstrainedBaseline());
            assertEquals(Optional.of("Constrained Baseline"), realtime.profileName());
            var options = H264EncoderOptions.builder().profile(H264Profile.HIGH).level(H264Level.LEVEL_3_1).build();
            try (var encoder = new H264Encoder(SIZE, SIZE, options)) {
                var info = H264StreamInfo.probe(encoder.codecConfig().orElseThrow());
                assertEquals(Optional.of(H264Level.LEVEL_3_1), info.level());
                assertEquals(Optional.of("High"), info.profileName());
                assertFalse(info.isConstrainedBaseline());
            }
        }
    }
}
