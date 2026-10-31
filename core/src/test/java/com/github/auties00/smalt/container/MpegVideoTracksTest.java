package com.github.auties00.smalt.container;

import com.github.auties00.smalt.internal.codec.MpegVideoHeaders;
import com.github.auties00.smalt.internal.codec.VideoConfigs;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads MPEG-1 and MPEG-2 video tracks of MP4 ({@code mp4v} with object types 0x6A and 0x61), QuickTime
 * ({@code m1v }, {@code m2v1} and the HDV, XDCAM and Avid sample entry types), Matroska ({@code V_MPEG1},
 * {@code V_MPEG2}) and AVI ({@code mpg1}, {@code mpg2}) files that ffmpeg wrote, against ffprobe: the codec, the
 * size, the packets and their key frames, the sequence header the first key frame carries and what
 * {@link VideoConfigs#describe} and the prober (which reads that key frame) make of it.
 */
@DisplayName("MPEG-1 and MPEG-2 video tracks of MP4, QuickTime, Matroska and AVI")
class MpegVideoTracksTest {
    @ParameterizedTest(name = "{0} in {1} {2}")
    @CsvSource(delimiter = ';', value = {
            "mpeg1video; mp4; ", "mpeg2video; mp4; -aspect 16:9", "mpeg1video; mov; ", "mpeg2video; mov; ",
            "mpeg2video; mov; -tag:v xd5c", "mpeg2video; mov; -tag:v hdv2", "mpeg2video; mov; -tag:v AVmp",
            "mpeg1video; mkv; ", "mpeg2video; mkv; -pix_fmt yuv422p", "mpeg1video; avi; ",
            "mpeg2video; avi; -aspect 4:3"})
    @DisplayName("reads the track with its configuration, size, aspect and packets")
    void track(String codec, String container, String options) {
        var output = new ArrayList<>(List.of("-c:v", codec, "-g", "6", "-bf", "2", "-q:v", "5"));
        if (options != null) {
            output.addAll(List.of(options.split(" ")));
        }
        var file = Fixtures.ffmpeg(container, List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=0.6"),
                output);
        var probe = ContainerProbe.probe(file);
        var demuxed = ContainerProbe.demux(file, Containers::openDemuxer);
        var track = (VideoTrack) demuxed.tracks().getFirst();
        var expectedCodec = codec.equals("mpeg1video") ? Codec.MPEG1_VIDEO : Codec.MPEG2_VIDEO;
        assertEquals(expectedCodec, track.codec());
        var stream = probe.streams().getFirst();
        assertEquals(176, track.width());
        assertEquals(144, track.height());
        var packets = demuxed.packetsOf(track);
        // ffmpeg stores no decoder configuration for MPEG video in these containers: the headers are in band
        var headers = MpegVideoHeaders.config(packets.getFirst().data());
        assertArrayEquals(stream.extradata(), headers, "the sequence header of the first key frame");
        track.codecConfig().ifPresent(config -> assertArrayEquals(headers, config, "configuration"));
        var sequence = MpegVideoHeaders.find(headers);
        assertEquals(expectedCodec == Codec.MPEG2_VIDEO, sequence.mpeg2());
        var info = VideoConfigs.describe(track.codec(), headers);
        assertEquals(176, info.width());
        assertEquals(144, info.height());
        assertEquals(new Rational(25, 1), info.frameRate());
        var sar = stream.get("sample_aspect_ratio").split(":");
        var expectedSar = new Rational(Long.parseLong(sar[0]), Long.parseLong(sar[1])).reduce();
        assertEquals(expectedSar, info.sampleAspect(), "sample aspect ratio of the sequence header");
        if (expectedCodec == Codec.MPEG2_VIDEO) {
            assertEquals(stream.get("profile"), info.profile());
        }
        var probed = Media.probe(MediaSource.of(file)).video().orElseThrow();
        assertEquals(expectedCodec, probed.codec());
        assertEquals(expectedSar, probed.sampleAspectRatio().orElseThrow(), "probed sample aspect ratio");
        assertEquals(new Rational(25, 1), probed.frameRate().orElseThrow().reduce(), "probed frame rate");
        assertEquals(expectedCodec == Codec.MPEG2_VIDEO ? stream.get("profile") : null,
                probed.profile().orElse(null), "probed profile");
        var expected = probe.packetsOf(0);
        var actual = packets;
        assertEquals(expected.size(), actual.size(), "packet count");
        for (var index = 0; index < expected.size(); index++) {
            assertEquals(expected.get(index).key(), actual.get(index).keyframe(), "key flag of packet " + index);
            assertEquals(expected.get(index).hash(), ContainerProbe.crc32(actual.get(index).data()),
                    "payload " + index);
        }
        assertTrue(actual.getFirst().keyframe());
    }
}
