package com.github.auties00.smalt.container;

import com.github.auties00.smalt.internal.codec.MpegVideoHeaders;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.transcode.OutputContainer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Remuxes ffmpeg's MPEG-1 and MPEG-2 video into every container that carries it (MP4, QuickTime, fragmented MP4,
 * Matroska, MPEG-TS, AVI) and checks that the container validators accept the file, that ffmpeg decodes it to the
 * frames of the source, and that Smalt reads back the codec, the sequence header and every packet; plus the sequence
 * header that the transport stream muxer puts before a key frame that carries none.
 */
@DisplayName("MPEG-1 and MPEG-2 video muxing")
class MpegVideoMuxingTest {
    static Path source(String codec, List<String> options) {
        var output = new ArrayList<>(List.of("-c:v", codec, "-g", "6", "-bf", "2", "-q:v", "5"));
        output.addAll(options);
        // MP4, whose packets carry decoding timestamps, which the AVI muxer places frames by
        return Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=0.8"),
                output);
    }

    static byte[] mux(OutputContainer container, Track track, List<Packet> packets) {
        var sink = MediaSink.memory();
        try (var muxer = Containers.openMuxer(container, sink)) {
            muxer.addTrack(track);
            packets.forEach(muxer::writePacket);
            muxer.finish();
        }
        return MuxSupport.validated(container.name().toLowerCase(java.util.Locale.ROOT), sink.toByteArray());
    }

    /**
     * Decodes a file with ffmpeg, every decoded frame once, without the frames its constant rate output repeats to
     * fill gaps in the timestamps of a container.
     */
    static byte[] decode(Path file, String pixelFormat) {
        return Oracle.run(Oracle.Tool.FFMPEG, java.time.Duration.ofMinutes(2), List.of("-v", "error", "-i",
                file.toString(), "-map", "0:v:0", "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt",
                pixelFormat, "-"));
    }

    /** Returns the track with the sequence header of its first key frame as its configuration. */
    static VideoTrack configured(VideoTrack track, List<Packet> packets) {
        return track.toBuilder().codecConfig(MpegVideoHeaders.config(packets.getFirst().data())).build();
    }

    @ParameterizedTest(name = "{0} into {1}, configuration {2}")
    @CsvSource({"mpeg2video, MP4, true", "mpeg2video, MOV, true", "mpeg2video, FRAGMENTED_MP4, true",
            "mpeg2video, MATROSKA, true", "mpeg2video, MPEG_TS, true", "mpeg2video, AVI, true",
            "mpeg1video, MP4, true", "mpeg1video, MATROSKA, true", "mpeg1video, MPEG_TS, true",
            "mpeg1video, AVI, true", "mpeg2video, MP4, false", "mpeg2video, MATROSKA, false",
            "mpeg2video, MPEG_TS, false", "mpeg2video, AVI, false"})
    @DisplayName("writes a file ffmpeg decodes to the source's frames and Smalt reads back packet for packet")
    void remux(String codec, OutputContainer container, boolean withConfig) {
        // the interlaced coding flags and bitexact in one -flags:v, which a later -flags:v would replace
        var file = source(codec, codec.equals("mpeg2video") ? List.of("-flags:v", "+ildct+ilme", "-top", "1",
                "-pix_fmt", "yuv422p") : List.of());
        var original = ContainerProbe.demux(file, Containers::openDemuxer);
        var demuxedTrack = (VideoTrack) original.tracks().getFirst();
        var headers = MpegVideoHeaders.config(original.packetsOf(demuxedTrack).getFirst().data());
        var track = withConfig ? configured(demuxedTrack, original.packetsOf(demuxedTrack))
                : demuxedTrack.toBuilder().codecConfig(null).build();
        assertTrue(container.supports(track.codec()), container + " carries " + track.codec());
        var data = mux(container, track, original.packetsOf(demuxedTrack));
        var written = MuxSupport.save("mpeg-video-" + codec + "." + container.extension(), data);
        var pixelFormat = codec.equals("mpeg2video") ? "yuv422p" : "yuv420p";
        assertArrayEquals(decode(file, pixelFormat), decode(written, pixelFormat), "ffmpeg's decode of the remuxed file");
        var demuxed = MuxSupport.demux(data);
        var back = (VideoTrack) demuxed.tracks().getFirst();
        assertEquals(track.codec(), back.codec());
        assertEquals(176, back.width());
        assertEquals(144, back.height());
        if (withConfig || container == OutputContainer.MP4 || container == OutputContainer.MPEG_TS) {
            // the MP4 muxer takes the configuration from the first frame, the transport stream demuxer too
            assertArrayEquals(headers, back.codecConfig().orElseThrow(), "configuration");
        } else {
            assertTrue(back.codecConfig().isEmpty(), "no configuration");
        }
        var expected = original.packetsOf(demuxedTrack);
        var actual = demuxed.packetsOf(back);
        assertEquals(expected.size(), actual.size(), "packet count");
        for (var index = 0; index < expected.size(); index++) {
            assertArrayEquals(expected.get(index).data(), actual.get(index).data(), "payload " + index);
            assertEquals(expected.get(index).keyframe(), actual.get(index).keyframe(), "key flag " + index);
        }
    }

    @Test
    @DisplayName("puts the track's sequence header before transport stream key frames that carry none")
    void transportStreamHeaders() {
        var original = ContainerProbe.demux(source("mpeg2video", List.of()), Containers::openDemuxer);
        var demuxedTrack = (VideoTrack) original.tracks().getFirst();
        var track = configured(demuxedTrack, original.packetsOf(demuxedTrack));
        var config = track.codecConfig().orElseThrow();
        var stripped = new ArrayList<Packet>();
        for (var packet : original.packetsOf(demuxedTrack)) {
            var data = packet.data();
            if (packet.keyframe()) {
                assertArrayEquals(config, Arrays.copyOf(data, config.length), "ffmpeg's key frames carry the header");
                data = Arrays.copyOfRange(data, config.length, data.length);
                assertTrue(!MpegVideoHeaders.startsWithSequenceHeader(data));
            }
            stripped.add(packet.withData(data));
        }
        var demuxed = MuxSupport.demux(mux(OutputContainer.MPEG_TS, track, stripped));
        var back = demuxed.packetsOf(demuxed.tracks().getFirst());
        assertEquals(stripped.size(), back.size());
        for (var index = 0; index < back.size(); index++) {
            var expected = original.packetsOf(demuxedTrack).get(index).data();
            assertArrayEquals(expected, back.get(index).data(), "packet " + index);
        }
        try (var demuxer = Containers.openDemuxer(MediaSource.of(mux(OutputContainer.MATROSKA, track, stripped)))) {
            assertEquals(Codec.MPEG2_VIDEO, demuxer.tracks().getFirst().codec());
        }
    }
}
