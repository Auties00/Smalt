package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.internal.codec.MpegVideoHeaders;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers MPEG-1 and MPEG-2 video in transport streams ({@code stream_type} 0x01 and 0x02): ffmpeg's streams read
 * against ffprobe (configuration, size, sample aspect ratio, frame rate, color, key frames, timestamps and payloads),
 * and hand-written streams for the pictures before the first sequence header, a sequence that changes size and PES
 * packets without timestamps.
 */
@DisplayName("MPEG-TS MPEG-1 and MPEG-2 video")
class TsMpegVideoTest {
    static Path source(String codec, String size, List<String> options) {
        var output = new ArrayList<>(List.of("-c:v", codec, "-g", "12", "-bf", "2", "-q:v", "4"));
        output.addAll(options);
        return Fixtures.ffmpeg("ts", List.of("-f", "lavfi", "-i", "testsrc2=size=" + size + ":rate=25:duration=1.2"),
                output);
    }

    @ParameterizedTest(name = "{0} {1} {2}")
    @CsvSource(delimiter = ';', value = {
            "mpeg1video; 352x288; -aspect 4:3",
            "mpeg2video; 720x576; -aspect 16:9 -flags +ildct+ilme -top 1",
            "mpeg2video; 352x288; -color_primaries bt709 -color_trc bt709 -colorspace bt709",
            "mpeg2video; 640x480; -pix_fmt yuv422p -r 30000/1001",
            "mpeg2video; 1280x720; -r 60000/1001 -aspect 16:9"})
    @DisplayName("reads ffmpeg's stream with its configuration, size, aspect, rate, key frames and timestamps")
    void ffmpegStream(String codec, String size, String options) {
        var file = source(codec, size, List.of(options.split(" ")));
        var probe = ContainerProbe.probe(file);
        var demuxed = ContainerProbe.demux(file, TsDemuxer::new);
        var track = (VideoTrack) demuxed.tracks().getFirst();
        assertEquals(codec.equals("mpeg1video") ? Codec.MPEG1_VIDEO : Codec.MPEG2_VIDEO, track.codec());
        var stream = probe.streams().getFirst();
        assertEquals(Integer.parseInt(stream.get("width")), track.width());
        assertEquals(Integer.parseInt(stream.get("height")), track.height());
        var sar = stream.get("sample_aspect_ratio").split(":");
        assertEquals(new Rational(Long.parseLong(sar[0]), Long.parseLong(sar[1])).reduce(),
                track.sampleAspectRatio().orElseThrow(), "sample aspect ratio");
        // r_frame_rate is the field rate of an interlaced stream
        var rate = stream.get("avg_frame_rate").split("/");
        assertEquals(new Rational(Long.parseLong(rate[0]), Long.parseLong(rate[1])).reduce(),
                track.frameRate().orElseThrow().reduce(), "frame rate");
        assertArrayEquals(stream.extradata(), track.codecConfig().orElseThrow(), "configuration");
        if (options.contains("bt709")) {
            var color = track.color().orElseThrow();
            assertEquals(stream.get("color_primaries"), ffprobeName(color.primaries().name()), "primaries");
            assertEquals(stream.get("color_transfer"), ffprobeName(color.transfer().name()), "transfer");
            assertEquals(stream.get("color_space"), ffprobeName(color.matrix().name()), "matrix");
        } else {
            assertTrue(track.color().isEmpty(), "no sequence display extension, no declared color");
        }
        var expected = probe.packetsOf(0);
        var actual = demuxed.packetsOf(track);
        assertEquals(expected.size(), actual.size(), "packet count");
        // Smalt's timeline starts at the first presentation time
        var start = expected.getFirst().pts();
        for (var index = 0; index < expected.size(); index++) {
            var want = expected.get(index);
            var got = actual.get(index);
            assertEquals(want.key(), got.keyframe(), "key flag of packet " + index);
            assertEquals(want.pts() - start, got.pts(), "pts of packet " + index);
            assertEquals(want.dts() - start, got.dts(), "dts of packet " + index);
            assertEquals(want.hash(), ContainerProbe.crc32(got.data()), "payload " + index);
            if (index + 1 < actual.size()) {
                assertEquals(actual.get(index + 1).dts() - got.dts(), got.duration(), "duration of packet " + index);
            }
        }
        assertTrue(actual.getFirst().keyframe());
    }

    /** Returns ffprobe's name of a color enum constant of the values ffmpeg's encoder writes. */
    static String ffprobeName(String constant) {
        var name = constant.toLowerCase(java.util.Locale.ROOT).replace("_", "");
        return name.equals("unspecified") ? "unknown" : name;
    }

    /** Returns the pictures of an elementary stream, each with the headers before it. */
    static List<byte[]> pictures(byte[] stream) {
        var starts = new ArrayList<Integer>();
        var open = false;
        var pictures = 0;
        for (var i = 0; i + 3 < stream.length; i++) {
            if (stream[i] != 0 || stream[i + 1] != 0 || stream[i + 2] != 1) {
                continue;
            }
            var code = stream[i + 3] & 0xFF;
            if (code == 0xB3 || code == 0xB8 || code == 0x00) {
                if (pictures > 0 && !open) {
                    starts.add(i);
                    pictures = 0;
                    open = true;
                }
                if (code == 0x00) {
                    pictures++;
                    open = false;
                }
            }
            i += 3;
        }
        var list = new ArrayList<byte[]>();
        var from = 0;
        for (var start : starts) {
            list.add(Arrays.copyOfRange(stream, from, start));
            from = start;
        }
        list.add(Arrays.copyOfRange(stream, from, stream.length));
        return list;
    }

    /** Encodes an MPEG-2 elementary stream of I- and P-pictures with a sequence header before every group. */
    static byte[] elementary(String size, int groups) {
        return elementary("mpeg2video", size, groups);
    }

    /** Encodes an elementary stream of groups of five I- and P-pictures, a sequence header before each. */
    static byte[] elementary(String codec, String size, int groups) {
        try {
            return Files.readAllBytes(Fixtures.ffmpeg(codec.equals("mpeg1video") ? "m1v" : "m2v", List.of("-f",
                    "lavfi", "-i", "testsrc2=size=" + size + ":rate=25:duration=" + groups * 0.2), List.of("-c:v",
                    codec, "-g", "5", "-bf", "0", "-q:v", "6", "-f", codec)));
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    static TsWriter writer() {
        return new TsWriter().pat(1, 0x1000, 0).pmt(0x1000, 1, 0, 0x100, new byte[0],
                List.of(new TsWriter.Stream(0x02, 0x100)));
    }

    @Test
    @DisplayName("drops the pictures before the first key frame with a sequence header, and takes that header")
    void leadingPictures() {
        var pictures = pictures(elementary("176x144", 2));
        assertEquals(10, pictures.size());
        var writer = writer();
        // start inside the first group: its P-pictures have no sequence header to decode with
        for (var index = 2; index < pictures.size(); index++) {
            writer.pes(0x100, 0xE0, 3600L * (index + 1), 3600L * index, pictures.get(index), index == 5);
        }
        var demuxed = TsTesting.demux(writer.toByteArray());
        var track = (VideoTrack) demuxed.tracks().getFirst();
        var packets = demuxed.packetsOf(track);
        assertEquals(5, packets.size(), "the second group only");
        assertTrue(packets.getFirst().keyframe());
        assertArrayEquals(MpegVideoHeaders.config(pictures.get(5)), track.codecConfig().orElseThrow());
        assertArrayEquals(pictures.get(5), packets.getFirst().data());
        assertEquals(176, track.width());
        assertEquals(144, track.height());
        for (var packet : packets.subList(1, packets.size())) {
            assertFalse(packet.keyframe());
            assertTrue(packet.sideData().newCodecConfig().isEmpty());
        }
    }

    @Test
    @DisplayName("hands a changed sequence header to the decoder as a new configuration at its key frame")
    void sequenceChange() {
        var first = pictures(elementary("176x144", 1));
        var second = pictures(elementary("320x240", 1));
        var writer = writer();
        var index = 0;
        for (var picture : first) {
            writer.pes(0x100, 0xE0, 3600L * (index + 1), 3600L * index, picture, index % 5 == 0);
            index++;
        }
        for (var picture : second) {
            writer.pes(0x100, 0xE0, 3600L * (index + 1), 3600L * index, picture, index % 5 == 0);
            index++;
        }
        var demuxed = TsTesting.demux(writer.toByteArray());
        var track = (VideoTrack) demuxed.tracks().getFirst();
        assertEquals(176, track.width());
        var packets = demuxed.packetsOf(track);
        assertEquals(10, packets.size());
        for (var position = 0; position < packets.size(); position++) {
            var config = packets.get(position).sideData().newCodecConfig();
            if (position == 5) {
                assertArrayEquals(MpegVideoHeaders.config(second.getFirst()), config.orElseThrow());
                assertEquals(320, MpegVideoHeaders.find(config.orElseThrow()).width());
            } else {
                assertTrue(config.isEmpty(), "no new configuration at packet " + position);
            }
        }
    }

    @Test
    @DisplayName("gives a PES packet without timestamps the previous decoding time plus its duration")
    void missingTimestamps() {
        var pictures = pictures(elementary("176x144", 1));
        var writer = writer();
        for (var index = 0; index < pictures.size(); index++) {
            if (index == 3) {
                writer.pes(0x100, 0xE0, -1, -1, pictures.get(index), false);
            } else {
                writer.pes(0x100, 0xE0, 3600L * (index + 1), 3600L * index, pictures.get(index), index == 0);
            }
        }
        var demuxed = TsTesting.demux(writer.toByteArray());
        var packets = demuxed.packetsOf(demuxed.tracks().getFirst());
        assertEquals(5, packets.size());
        var first = packets.getFirst().dts();
        var dts = packets.stream().map(packet -> packet.dts() - first).toList();
        assertEquals(List.of(0L, 3600L, 7200L, 10800L, 14400L), dts);
        assertEquals(Packet.NO_TIMESTAMP, packets.get(3).pts());
        assertEquals(3600L, packets.get(1).pts() - packets.get(0).pts());
        assertEquals(3600L, packets.get(2).duration(), "the packet before lasts until the derived time");
    }

    @Test
    @DisplayName("finds no track in a stream without a sequence header")
    void noSequenceHeader() {
        var pictures = pictures(elementary("176x144", 1));
        var writer = writer();
        for (var index = 1; index < pictures.size(); index++) {
            writer.pes(0x100, 0xE0, 3600L * (index + 1), 3600L * index, pictures.get(index), false);
        }
        assertThrows(MediaException.Malformed.class, () -> TsTesting.demux(writer.toByteArray()));
        assertNull(MpegVideoHeaders.config(pictures.get(1)));
    }

    @Test
    @DisplayName("names the codec of an MPEG-1 stream that a stream type of MPEG-2 video carries")
    void mpeg1InMpeg2StreamType() {
        var stream = elementary("mpeg1video", "176x144", 1);
        var writer = writer();
        var pictures = pictures(stream);
        for (var index = 0; index < pictures.size(); index++) {
            writer.pes(0x100, 0xE0, 3600L * (index + 1), 3600L * index, pictures.get(index), index == 0);
        }
        var track = (VideoTrack) TsTesting.demux(writer.toByteArray()).tracks().getFirst();
        assertEquals(Codec.MPEG1_VIDEO, track.codec());
        assertFalse(MpegVideoHeaders.find(track.codecConfig().orElseThrow()).mpeg2());
    }

}
