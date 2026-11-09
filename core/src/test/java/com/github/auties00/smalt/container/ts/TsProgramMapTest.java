package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.io.StreamSourceOptions;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.audio.UnsizedSource;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers program map changes: two ffmpeg recordings concatenated, the second with its streams on other PIDs,
 * an extra stream and another table version, read with the change inside the probe window and after it (found by
 * the sampling and bisection the demuxer does at open), from a stream read once, and seeked into.
 */
@DisplayName("MPEG-TS program map changes")
class TsProgramMapTest {
    /** A probe window that ends inside the first, padded recording. */
    static final long PROBE = 256 * 1024;

    /** Returns the first recording: H.264 and AAC on PIDs 0x100 and 0x101, table version 0. */
    static Path first(double seconds, boolean padded) {
        var output = new ArrayList<>(List.of("-c:v", "libx264", "-g", "25", "-bf", "0", "-c:a", "aac", "-ac", "2"));
        if (padded) {
            output.addAll(List.of("-muxrate", "4000000"));
        }
        return TsTesting.av("ts", seconds, output);
    }

    /** Returns the second recording: H.264, AAC and MP2 on PIDs from 0x200, table version 1. */
    static Path second() {
        return second(0);
    }

    /** Returns the second recording with its timestamps offset by a number of seconds. */
    static Path second(int offsetSeconds) {
        return Fixtures.ffmpeg("ts", List.of(
                        "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=30:duration=2",
                        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2",
                        "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000:duration=2"),
                List.of("-map", "0", "-map", "1", "-map", "2", "-c:v", "libx264", "-g", "25", "-bf", "0", "-c:a:0", "aac",
                        "-ac", "2", "-c:a:1", "mp2", "-mpegts_start_pid", "0x200", "-tables_version", "1",
                        "-output_ts_offset", Integer.toString(offsetSeconds)));
    }

    /** Returns the packet counts of each stream of a file, as Smalt reads the file alone. */
    static List<Integer> counts(Path file) {
        var demuxed = ContainerProbe.demux(file, TsDemuxer::new);
        return demuxed.tracks().stream().map(track -> demuxed.packetsOf(track).size()).toList();
    }

    static void assertConcatenated(ContainerProbe.Demuxed demuxed, Path first, Path second, boolean extraExposed) {
        var tracks = demuxed.tracks();
        assertEquals(extraExposed ? 3 : 2, tracks.size());
        assertEquals(0x100, tracks.get(0).id());
        assertEquals(Codec.H264, tracks.get(0).codec());
        assertEquals(0x101, tracks.get(1).id());
        assertEquals(Codec.AAC, tracks.get(1).codec());
        var a = counts(first);
        var b = counts(second);
        assertEquals(a.get(0) + b.get(0), demuxed.packetsOf(tracks.get(0)).size(), "video continues on its new PID");
        assertEquals(a.get(1) + b.get(1), demuxed.packetsOf(tracks.get(1)).size(), "audio continues on its new PID");
        if (extraExposed) {
            assertEquals(0x202, tracks.get(2).id());
            assertEquals(Codec.MP2, tracks.get(2).codec());
            assertEquals(b.get(2), demuxed.packetsOf(tracks.get(2)).size(), "the added stream");
        }
        var video = demuxed.packetsOf(tracks.get(0));
        for (var index = 1; index < video.size(); index++) {
            assertTrue(video.get(index).dts() > video.get(index - 1).dts(), "decoding times keep increasing at " + index);
        }
    }

    @Test
    @DisplayName("follows a change inside the probe window")
    void insideProbe() {
        var first = first(2, false);
        var second = second();
        var data = TsWriter.concat(Fixtures.read(first), Fixtures.read(second));
        assertConcatenated(TsTesting.demux(data), first, second, true);
    }

    @Test
    @DisplayName("finds a change after the probe window when opening")
    void afterProbe() {
        var first = first(4, true);
        var second = second();
        var data = TsWriter.concat(Fixtures.read(first), Fixtures.read(second));
        assertTrue(Fixtures.read(first).length > 4 * PROBE, "the change lies past the probe window");
        try (var source = MediaSource.of(data); var demuxer = new TsDemuxer(source,
                TsDemuxerOptions.builder().probeBytes(PROBE).build())) {
            assertConcatenated(ContainerProbe.demuxAll(demuxer), first, second, true);
        }
    }

    @Test
    @DisplayName("measures a source of unknown size with random access to find the change")
    void unsized() {
        var first = first(4, true);
        var second = second();
        var data = TsWriter.concat(Fixtures.read(first), Fixtures.read(second));
        try (var source = new UnsizedSource(data, 7000); var demuxer = new TsDemuxer(source,
                TsDemuxerOptions.builder().probeBytes(PROBE).build())) {
            assertConcatenated(ContainerProbe.demuxAll(demuxer), first, second, true);
        }
    }

    @Test
    @DisplayName("refuses a change that adds a stream to a stream read once, after the packets before it")
    void readOnce() {
        var first = first(4, true);
        var second = second();
        var data = TsWriter.concat(Fixtures.read(first), Fixtures.read(second));
        var options = StreamSourceOptions.builder().memoryBudget(1 << 20).build();
        try (var source = MediaSource.of(new ByteArrayInputStream(data), -1, options);
             var demuxer = new TsDemuxer(source, TsDemuxerOptions.builder().probeBytes(PROBE).build())) {
            assertEquals(2, demuxer.tracks().size());
            var read = new int[1];
            var failure = assertThrows(MediaException.Unsupported.class, () -> {
                while (demuxer.read() != null) {
                    read[0]++;
                }
            });
            assertTrue(failure.getMessage().contains("program map"), failure.getMessage());
            var a = counts(first);
            // the parsers still hold the last few units of the first recording when the change is refused
            assertTrue(read[0] >= a.get(0) + a.get(1) - 6, "the first recording is read: " + read[0]);
        }
        // the probed tracks still move to their new PIDs when the change adds no stream
        var aacOnly = Fixtures.ffmpeg("ts", List.of("-f", "lavfi", "-i", "testsrc2=size=160x120:rate=30:duration=1",
                        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1"),
                List.of("-c:v", "libx264", "-g", "25", "-bf", "0", "-c:a", "aac", "-ac", "2", "-mpegts_start_pid", "0x300",
                        "-tables_version", "2"));
        var moved = TsWriter.concat(Fixtures.read(first), Fixtures.read(aacOnly));
        try (var source = MediaSource.of(new ByteArrayInputStream(moved), -1);
             var demuxer = new TsDemuxer(source, TsDemuxerOptions.builder().probeBytes(PROBE).build())) {
            var demuxed = ContainerProbe.demuxAll(demuxer);
            assertEquals(2, demuxed.tracks().size());
            assertEquals(counts(first).get(0) + counts(aacOnly).get(0), demuxed.packetsOf(demuxed.tracks().get(0)).size());
            assertEquals(counts(first).get(1) + counts(aacOnly).get(1), demuxed.packetsOf(demuxed.tracks().get(1)).size());
        }
    }

    @ParameterizedTest(name = "probe window {0}")
    @ValueSource(longs = {PROBE, 8 << 20})
    @DisplayName("seeks into the second recording with its tracks mapped")
    void seek(long probe) {
        // the second recording continues the first one's timeline, which a seek needs
        var first = first(4, true);
        var second = second(4);
        var data = TsWriter.concat(Fixtures.read(first), Fixtures.read(second));
        try (var source = MediaSource.of(data); var demuxer = new TsDemuxer(source,
                TsDemuxerOptions.builder().probeBytes(probe).build())) {
            var tracks = demuxer.tracks();
            assertEquals(3, tracks.size());
            demuxer.seek(4_500_000);
            var seen = new ArrayList<Integer>();
            Packet packet;
            var read = 0;
            while ((packet = demuxer.read()) != null && read++ < 400) {
                if (!seen.contains(packet.trackId())) {
                    seen.add(packet.trackId());
                }
            }
            assertTrue(seen.containsAll(List.of(0x100, 0x101, 0x202)), "every track after the seek: " + seen);
        }
    }

    @Test
    @DisplayName("treats a stream list that changes under the same version as a change")
    void sameVersion() {
        var first = first(2, false);
        var second = Fixtures.ffmpeg("ts", List.of(
                        "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=30:duration=2",
                        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2",
                        "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000:duration=2"),
                List.of("-map", "0", "-map", "1", "-map", "2", "-c:v", "libx264", "-g", "25", "-bf", "0", "-c:a:0", "aac",
                        "-ac", "2", "-c:a:1", "mp2"));
        var data = TsWriter.concat(Fixtures.read(first), Fixtures.read(second));
        var demuxed = TsTesting.demux(data);
        assertEquals(3, demuxed.tracks().size());
        var extra = demuxed.tracks().get(2);
        assertEquals(0x102, extra.id());
        assertFalse(demuxed.packetsOf(extra).isEmpty());
        assertEquals(counts(first).get(0) + counts(second).get(0), demuxed.packetsOf(demuxed.tracks().getFirst()).size());
    }

    @Test
    @DisplayName("gives a stream that takes over a PID of another coding a track of its own")
    void reusedPid() {
        // the first recording has AAC on 0x101; the second puts MP2 there and AAC on 0x102
        var first = first(2, false);
        var second = Fixtures.ffmpeg("ts", List.of(
                        "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=30:duration=2",
                        "-f", "lavfi", "-i", "sine=frequency=660:sample_rate=48000:duration=2",
                        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2"),
                List.of("-map", "0", "-map", "1", "-map", "2", "-c:v", "libx264", "-g", "25", "-bf", "0", "-c:a:0", "mp2",
                        "-c:a:1", "aac", "-ac", "2", "-tables_version", "3"));
        var data = TsWriter.concat(Fixtures.read(first), Fixtures.read(second));
        var demuxed = TsTesting.demux(data);
        var ids = demuxed.tracks().stream().map(Track::id).toList();
        assertEquals(List.of(0x100, 0x101, 0x2000), ids);
        assertEquals(Codec.MP2, demuxed.tracks().get(2).codec());
        assertEquals(counts(first).get(1) + counts(second).get(2), demuxed.packetsOf(demuxed.tracks().get(1)).size(),
                "AAC continues on 0x102");
        assertEquals(TrackKind.AUDIO, demuxed.tracks().get(2).kind());
    }
}
