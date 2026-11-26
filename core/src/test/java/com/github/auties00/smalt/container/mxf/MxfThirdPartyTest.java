package com.github.auties00.smalt.container.mxf;

import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Reads the golden MXF files of the Federal Agencies Digital Guidelines Initiative, written by other MXF
 * implementations than ffmpeg (the AS-07 JPEG 2000 golden file and the RDD 48 uncompressed golden file, full data
 * tier), against ffprobe: the same streams and, stream by stream, the same packet sizes and payload checksums in the
 * same order.
 */
@DisplayName("MXF files of other writers")
class MxfThirdPartyTest {
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"fadgi-as07-gf-jpeg2000", "fadgi-rdd48-gf-uncompressed"})
    @DisplayName("every packet has ffprobe's size and payload checksum")
    void golden(String set) {
        var archive = Conformance.archive(set);
        Oracle.assume(Oracle.Tool.FFPROBE);
        for (var member : archive.members()) {
            assertMatches(archive.member(member), member);
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"netflix-SolLevante-IMF-AUDIO_4467fc2f-2536-44ba-b1f9-010e0ae3f6b1.mxf",
            "fate-mxf-Avid-00005.mxf", "fate-mxf-C0023S01.mxf", "fate-mxf-Meridian-Apple_ProResProxy-HDR10.mxf",
            "fate-mxf-Sony-00001.mxf", "fate-mxf-multiple_components.mxf",
            "fate-mxf-omneon_8.3.0.0_xdcam_startc_footer.mxf", "fate-mxf-opatom_essencegroup_alpha_raw.mxf",
            "fate-mxf-opatom_missing_index.mxf", "fate-mxf-track_01_v02.mxf", "fate-mxf-track_02_a01.mxf"})
    @DisplayName("every packet of an IMF track file and of FATE's MXF samples has ffprobe's size and checksum")
    void thirdParty(String name) {
        assertMatches(Conformance.vector(name), name);
    }

    private static void assertMatches(java.nio.file.Path file, String member) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        {
            try (var source = MediaSource.of(file)) {
                assertEquals(ContainerFormat.MXF, Containers.detect(source).orElseThrow());
            }
            var probe = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(10), List.of("-v", "error",
                    "-show_entries", "packet=stream_index,size,data_hash:packet_side_data=", "-show_data_hash", "CRC32", "-of",
                    "compact", file.toString())), StandardCharsets.UTF_8);
            var expected = new HashMap<Integer, List<String>>();
            for (var line : probe.split("\r?\n")) {
                if (!line.startsWith("packet|")) {
                    continue;
                }
                var stream = -1;
                var size = "";
                var hash = "";
                for (var field : line.split("\\|")) {
                    if (field.startsWith("stream_index=")) {
                        stream = Integer.parseInt(field.substring("stream_index=".length()));
                    } else if (field.startsWith("size=")) {
                        size = field.substring("size=".length());
                    } else if (field.startsWith("data_hash=")) {
                        hash = field.substring("data_hash=".length());
                    }
                }
                expected.computeIfAbsent(stream, ignored -> new ArrayList<>()).add(size + " " + hash);
            }
            // ffprobe lists the timecode and other data tracks as streams without packets; the tracks are matched
            // in order to the streams that have packets
            var streams = new ArrayList<>(new java.util.TreeSet<>(expected.keySet()));
            try (var source = MediaSource.of(file); var demuxer = new MxfDemuxer(source)) {
                var tracks = demuxer.tracks();
                assertEquals(expected.size(), tracks.size(), "stream count of " + member);
                var actual = new HashMap<Integer, List<String>>();
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    actual.computeIfAbsent(packet.trackId(), ignored -> new ArrayList<>())
                            .add(packet.size() + " " + ContainerProbe.crc32(packet.data()));
                }
                for (var index = 0; index < tracks.size(); index++) {
                    var track = tracks.get(index);
                    var ours = actual.getOrDefault(track.id(), List.of());
                    var theirs = expected.getOrDefault(streams.get(index), List.of());
                    assertFalse(ours.isEmpty(), "track " + track.id() + " of " + member + " has packets");
                    if (track.codec().isPcm()) {
                        assertEquals(sum(theirs), sum(ours), "sample bytes of track " + track.id() + " of " + member);
                        continue;
                    }
                    assertEquals(theirs, ours, "packets of track " + track.id() + " (" + track.codec() + ") of "
                            + member);
                }
                assertFalse(tracks.stream().anyMatch(track -> track.codec() == Codec.UNKNOWN_AUDIO));
            }
        }
    }

    private static long sum(List<String> packets) {
        return packets.stream().mapToLong(packet -> Long.parseLong(packet.substring(0, packet.indexOf(' ')))).sum();
    }
}
