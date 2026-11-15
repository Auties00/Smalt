package com.github.auties00.smalt.property;

import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.rtp.RtpDepacketizer;
import com.github.auties00.smalt.rtp.RtpDepacketizerOptions;
import com.github.auties00.smalt.rtp.RtpFrame;
import com.github.auties00.smalt.rtp.RtpPacket;
import com.github.auties00.smalt.rtp.RtpPacketizer;
import com.github.auties00.smalt.rtp.RtpPacketizerOptions;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sends the packets of Smalt's encoders through the RTP payload formats and a network that reorders packets within
 * the depacketizer's window, rebuilds the receiving track only from what the receiver knows (the payload format and
 * the configuration the depacketizer reports), and checks that Smalt's decoders output exactly what they output for
 * the encoder's own packets.
 */
@DisplayName("RTP round trips decode like the encoder's packets")
class RtpRoundTripTest {
    static List<RtpFrame> send(Streams.Encoded stream, RtpPacketizerOptions options) {
        var packetizer = RtpPacketizer.create(stream.track(), options);
        var packets = new ArrayList<RtpPacket>();
        for (var packet : stream.packets()) {
            packets.addAll(packetizer.packetize(packet));
        }
        packets.addAll(packetizer.flush());
        var random = new Random(5);
        for (var start = 0; start < packets.size(); start += 6) {
            Collections.shuffle(packets.subList(start, Math.min(packets.size(), start + 6)), random);
        }
        var depacketizer = RtpDepacketizer.create(packetizer.format(), RtpDepacketizerOptions.builder()
                .timeBase(stream.track().timeBase()).build());
        var frames = new ArrayList<RtpFrame>();
        for (var packet : packets) {
            frames.addAll(depacketizer.push(RtpPacket.parse(packet.toByteArray())));
        }
        frames.addAll(depacketizer.flush());
        assertEquals(0, depacketizer.lostPackets());
        assertTrue(frames.stream().allMatch(RtpFrame::complete));
        lastConfig = depacketizer.codecConfig().orElse(null);
        return frames;
    }

    private static byte[] lastConfig;

    static RtpPacketizerOptions options() {
        return RtpPacketizerOptions.builder().ssrc(5).initialSequenceNumber(65_500).initialTimestamp(0xFFFF_0000L)
                .build();
    }

    static String videoDigest(VideoTrack track, List<Packet> packets) throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        try (var decoder = com.github.auties00.smalt.video.VideoDecoders.create(track,
                com.github.auties00.smalt.video.VideoDecoderOptions.defaults())) {
            java.util.function.Consumer<com.github.auties00.smalt.video.VideoFrame> sink = frame -> {
                digest.update(com.github.auties00.smalt.image.ops.ImageOps.toRgba(frame.picture()).pixels());
            };
            for (var packet : packets) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Codec.class, names = {"H264", "VP8", "VP9", "AV1"})
    @DisplayName("video: the decoded pictures of every frame match")
    void video(Codec codec) throws Exception {
        var stream = Streams.video(codec, 320, 240, 24, builder -> builder.realtime(codec != Codec.H264));
        var frames = send(stream, options().toBuilder().parameterSetsBeforeKeyFrames(false).build());
        assertEquals(stream.packets().size(), frames.size());
        var original = (VideoTrack) stream.track();
        var received = VideoTrack.builder().id(1).codec(codec).timeBase(original.timeBase()).width(original.width())
                .height(original.height()).codecConfig(lastConfig).build();
        var packets = frames.stream().map(RtpFrame::packet).toList();
        var origin = stream.packets().getFirst().pts();
        for (var index = 0; index < packets.size(); index++) {
            assertEquals(stream.packets().get(index).pts() - origin, packets.get(index).pts(), "pts of frame " + index);
            assertEquals(stream.packets().get(index).keyframe(), packets.get(index).keyframe(),
                    "key flag of frame " + index);
        }
        assertEquals(videoDigest(original, stream.packets()), videoDigest(received, packets));
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Codec.class, names = {"OPUS", "AAC", "AMR_NB", "AMR_WB"})
    @DisplayName("audio: the decoded samples match")
    void audio(Codec codec) throws Exception {
        var rate = switch (codec) {
            case AMR_NB -> 8000;
            case AMR_WB -> 16_000;
            default -> 48_000;
        };
        var layout = codec == Codec.AMR_NB || codec == Codec.AMR_WB ? ChannelLayout.MONO : ChannelLayout.STEREO;
        var stream = Streams.audio(codec, layout, rate, rate * 2, builder -> builder);
        var frames = send(stream, options().toBuilder().framesPerPacket(codec == Codec.OPUS ? 1 : 3).build());
        var original = (AudioTrack) stream.track();
        var received = original.toBuilder().codecConfig(codec == Codec.OPUS ? original.codecConfig().orElse(null)
                : lastConfig).build();
        var sent = stream.packets().stream().map(Packet::data).toList();
        var got = frames.stream().map(frame -> frame.packet().data()).toList();
        if (codec == Codec.AMR_NB || codec == Codec.AMR_WB) {
            assertEquals(HexFormat.of().formatHex(concat(sent)), HexFormat.of().formatHex(concat(got)));
        } else {
            assertEquals(sent.size(), got.size());
            var origin = stream.packets().getFirst().pts();
            for (var index = 0; index < sent.size(); index++) {
                assertEquals(HexFormat.of().formatHex(sent.get(index)), HexFormat.of().formatHex(got.get(index)),
                        "packet " + index);
                assertEquals(stream.packets().get(index).pts() - origin, frames.get(index).packet().pts());
            }
        }
        assertTrue(decodedSamples(received, frames.stream().map(RtpFrame::packet).toList()) > rate,
                "the receiver decodes the stream");
    }

    static byte[] concat(List<byte[]> parts) {
        var out = new java.io.ByteArrayOutputStream();
        parts.forEach(out::writeBytes);
        return out.toByteArray();
    }

    static long decodedSamples(AudioTrack track, List<Packet> packets) {
        var samples = new long[1];
        try (var decoder = AudioDecoders.create(track, AudioDecoderOptions.defaults())) {
            java.util.function.Consumer<com.github.auties00.smalt.audio.AudioBuffer> sink =
                    buffer -> samples[0] += buffer.frames();
            for (var packet : packets) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        return samples[0];
    }
}
