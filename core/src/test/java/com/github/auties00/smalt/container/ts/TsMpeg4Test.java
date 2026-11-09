package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers MPEG-4 Part 2 video in transport streams: ffmpeg's streams read against ffprobe, and the muxer's streams
 * (headers repeated at key frames) decoding in ffmpeg exactly like their MP4 source, and private streams that only
 * resemble it.
 */
@DisplayName("MPEG-TS MPEG-4 Part 2")
class TsMpeg4Test {
    /** Returns an ffmpeg-encoded MPEG-4 Part 2 file with B-frames and a non-square pixel aspect. */
    static Path source(String extension) {
        return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=2"),
                List.of("-c:v", "mpeg4", "-g", "12", "-bf", "2", "-q:v", "4", "-aspect", "16:9"));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"ts", "m2ts"})
    @DisplayName("reads ffmpeg's stream with its size, aspect, configuration and key frames")
    void ffmpegStream(String extension) {
        // ffmpeg's M2TS declares the stream as private data (0x06) without a descriptor
        var file = extension.equals("ts") ? source("ts") : Fixtures.ffmpeg("m2ts", List.of("-f", "lavfi", "-i",
                "testsrc2=size=176x144:rate=25:duration=2"), List.of("-c:v", "mpeg4", "-g", "12", "-bf", "2", "-q:v", "4",
                "-aspect", "16:9", "-f", "mpegts", "-mpegts_m2ts_mode", "1"));
        var probe = ContainerProbe.probe(file);
        var demuxed = ContainerProbe.demux(file, TsDemuxer::new);
        var track = (VideoTrack) demuxed.tracks().getFirst();
        assertEquals(Codec.MPEG4_PART2, track.codec());
        var stream = probe.streams().getFirst();
        assertEquals(Integer.parseInt(stream.get("width")), track.width());
        assertEquals(Integer.parseInt(stream.get("height")), track.height());
        var sar = stream.get("sample_aspect_ratio").split(":");
        assertEquals(new Rational(Long.parseLong(sar[0]), Long.parseLong(sar[1])).reduce(),
                track.sampleAspectRatio().orElseThrow());
        assertArrayEquals(stream.extradata(), track.codecConfig().orElseThrow());
        var expected = probe.packetsOf(0);
        var actual = demuxed.packetsOf(track);
        assertEquals(expected.size(), actual.size());
        var start = expected.getFirst().pts();
        for (var index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index).key(), actual.get(index).keyframe(), "key flag of packet " + index);
            assertEquals(expected.get(index).pts() - start, actual.get(index).pts(), "pts of packet " + index);
            assertEquals(expected.get(index).hash(), ContainerProbe.crc32(actual.get(index).data()), "payload " + index);
        }
    }

    @Test
    @DisplayName("writes MP4's frames with the headers before key frames, decoding exactly like the source")
    void roundTrip() {
        var input = source("mp4");
        var original = ContainerProbe.demux(input, Containers::openDemuxer);
        var ts = TsMuxerTest.remux(input, TsMuxer::new);
        var file = TsTesting.save("mpeg4.ts", ts);
        assertArrayEquals(Oracle.decodeVideo(input, "yuv420p", 100), Oracle.decodeVideo(file, "yuv420p", 100));
        var demuxed = TsTesting.demux(ts);
        var track = (VideoTrack) demuxed.tracks().getFirst();
        var config = original.tracks().getFirst().codecConfig().orElseThrow();
        assertArrayEquals(config, track.codecConfig().orElseThrow());
        assertEquals(176, track.width());
        assertEquals(144, track.height());
        var expected = original.packetsOf(original.tracks().getFirst());
        var actual = demuxed.packetsOf(track);
        assertEquals(expected.size(), actual.size());
        for (var index = 0; index < expected.size(); index++) {
            var got = actual.get(index).data();
            var want = expected.get(index).data();
            if (expected.get(index).keyframe()) {
                assertArrayEquals(config, Arrays.copyOf(got, config.length), "headers before key frame " + index);
                got = Arrays.copyOfRange(got, config.length, got.length);
            }
            assertArrayEquals(want, got, "frame " + index);
            assertEquals(expected.get(index).keyframe(), actual.get(index).keyframe());
        }
        assertTrue(actual.getFirst().keyframe());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"h264", "hevc", "mpeg2"})
    @DisplayName("leaves private video whose start codes look like MPEG-4 Part 2 headers a private data track")
    void privateLookalikes(String kind) {
        // the first start code of each payload takes a value of an MPEG-4 Part 2 header, but no 00 00 01 B6 follows
        var payload = switch (kind) {
            case "h264" -> new byte[]{0, 0, 1, 0x09, (byte) 0xF0, 0, 0, 1, 0x25, (byte) 0x88, (byte) 0x84, 0x21};
            case "hevc" -> new byte[]{0, 0, 1, 0x02, 0x01, (byte) 0xD0, 0, 0, 1, 0x26, 0x01, (byte) 0xAF};
            // an MPEG-2 sequence header, then a picture start code
            default -> new byte[]{0, 0, 1, (byte) 0xB3, 0x0B, 0x00, (byte) 0x90, 0x13, 0, 0, 1, 0x00, 0x00, 0x0F};
        };
        var writer = new TsWriter().pat(1, 0x1000, 0).pmt(0x1000, 1, 0, 0x100, new byte[0],
                List.of(new TsWriter.Stream(0x06, 0x100)));
        for (var index = 0; index < 5; index++) {
            writer.pes(0x100, 0xE0, 3600L * index, -1, payload, index == 0);
        }
        var demuxed = TsTesting.demux(writer.toByteArray());
        var track = (DataTrack) demuxed.tracks().getFirst();
        assertEquals("private", track.handler().orElseThrow());
        var packets = demuxed.packetsOf(track);
        assertEquals(5, packets.size());
        assertArrayEquals(payload, packets.getFirst().data());
    }
}
