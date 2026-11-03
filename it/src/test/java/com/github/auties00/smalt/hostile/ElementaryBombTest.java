package com.github.auties00.smalt.hostile;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.RawPixelFormat;
import com.github.auties00.smalt.video.RawVideoFormat;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoDecoders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static com.github.auties00.smalt.hostile.Hostile.LIMITS;
import static com.github.auties00.smalt.hostile.Hostile.assertRejected;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Elementary streams and uncompressed video whose headers declare a picture over the limits: H.264 and HEVC Annex B
 * streams, AV1 OBU streams in both formats and YUV4MPEG2 files must be rejected with
 * {@link MediaException.LimitExceeded} when the demuxer reads their sequence header, and a raw video track over the
 * limits when its decoder is created, before a picture or frame buffer is allocated.
 */
@DisplayName("Elementary streams declaring pictures over the limits")
class ElementaryBombTest {
    private static byte[] stream(String kind, int width, int height) {
        return switch (kind) {
            case "h264" -> ForgedHeaders.h264AnnexB(width, height);
            case "hevc" -> annexB(ForgedHeaders.hevc(width, height));
            case "av1" -> ForgedHeaders.av1TemporalUnit(width, height);
            case "av1-annexb" -> av1AnnexB(ForgedHeaders.av1TemporalUnit(width, height));
            case "y4m" -> ("YUV4MPEG2 W" + width + " H" + height + " F25:1 C420jpeg\nFRAME\n")
                    .getBytes(StandardCharsets.US_ASCII);
            default -> throw new IllegalArgumentException(kind);
        };
    }

    /** Rewrites an HEVC record and access unit of length-prefixed units as an Annex B stream. */
    private static byte[] annexB(ForgedHeaders.Stream stream) {
        var out = new ByteArrayOutputStream();
        var config = stream.config();
        var arrays = config[22] & 0xFF;
        var position = 23;
        for (var array = 0; array < arrays; array++) {
            var count = (config[position + 1] & 0xFF) << 8 | (config[position + 2] & 0xFF);
            position += 3;
            for (var unit = 0; unit < count; unit++) {
                var length = (config[position] & 0xFF) << 8 | (config[position + 1] & 0xFF);
                out.writeBytes(new byte[]{0, 0, 0, 1});
                out.write(config, position + 2, length);
                position += 2 + length;
            }
        }
        var packet = stream.packet();
        out.writeBytes(new byte[]{0, 0, 0, 1});
        out.write(packet, 4, packet.length - 4);
        return out.toByteArray();
    }

    /** Rewrites a low-overhead temporal unit in Annex B: one frame unit, every OBU after its length. */
    private static byte[] av1AnnexB(byte[] unit) {
        var frame = new ByteArrayOutputStream();
        var position = 0;
        while (position < unit.length) {
            var header = unit[position] & 0xFF;
            var size = 0;
            var shift = 0;
            var at = position + 1;
            while (true) {
                var b = unit[at++] & 0xFF;
                size |= (b & 0x7F) << shift;
                shift += 7;
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            leb128(frame, 1 + size);
            frame.write(header & ~0x02);
            frame.write(unit, at, size);
            position = at + size;
        }
        var temporal = new ByteArrayOutputStream();
        leb128(temporal, frame.size());
        temporal.writeBytes(frame.toByteArray());
        var out = new ByteArrayOutputStream();
        leb128(out, temporal.size());
        out.writeBytes(temporal.toByteArray());
        return out.toByteArray();
    }

    private static void leb128(ByteArrayOutputStream out, int value) {
        do {
            var b = value & 0x7F;
            value >>>= 7;
            out.write(value != 0 ? b | 0x80 : b);
        } while (value != 0);
    }

    @ParameterizedTest(name = "{0} of {1}x{2}")
    @CsvSource({"h264, 5008, 16", "h264, 3008, 3008", "hevc, 5008, 16", "hevc, 3008, 3008", "av1, 5008, 16",
            "av1, 3008, 3008", "av1-annexb, 5008, 16", "y4m, 5008, 16", "y4m, 3008, 3008"})
    @DisplayName("is rejected by the demuxer with LimitExceeded")
    void demuxer(String kind, int width, int height) {
        var data = stream(kind, width, height);
        var small = stream(kind, 64, 48);
        var format = Containers.detect(MediaSource.of(small)).orElseThrow();
        assertEquals(format, Containers.detect(MediaSource.of(data)).orElseThrow(), kind + " is recognized");
        assertRejected(kind + " " + width + "x" + height, MediaException.LimitExceeded.class, () -> {
            try (var demuxer = Containers.openDemuxer(MediaSource.of(data),
                    DemuxerOptions.builder().limits(LIMITS).build())) {
                while (demuxer.read() != null) {
                    // drains
                }
            }
        });
        if (format != ContainerFormat.Y4M) {
            try (var demuxer = Containers.openDemuxer(MediaSource.of(small),
                    DemuxerOptions.builder().limits(LIMITS).build())) {
                assertEquals(64, ((VideoTrack) demuxer.tracks().getFirst()).width(), kind + " within the limits");
            }
        }
    }

    @Test
    @DisplayName("a raw video track over the limits is rejected when its decoder is created")
    void rawVideo() {
        var track = VideoTrack.builder().id(1).codec(Codec.RAW_VIDEO).timeBase(new Rational(1, 25)).width(5008)
                .height(16).codecConfig(RawVideoFormat.of(RawPixelFormat.YUV444P, 16).toCodecConfig()).build();
        assertRejected("raw video 5008x16", MediaException.LimitExceeded.class, () -> {
            try (var decoder = VideoDecoders.create(track, VideoDecoderOptions.builder().limits(LIMITS).build())) {
                decoder.decode(new Packet(1, new byte[16], 0, 0, 1, true), frame -> {
                });
            }
        });
    }
}
