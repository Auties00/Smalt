package com.github.auties00.smalt.container.amr;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.testkit.container.CountingSource;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.audio.AudioMutations;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.testkit.audio.ProbedPacket;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the AMR storage-format demuxer: every AMR-NB and AMR-WB mode from the reference
 * encoders (packets equal to ffprobe's), comfort-noise frames, hand-built frames of every type,
 * malformed input, truncation and mutation robustness; {@link AmrMultichannelTest} covers the
 * multichannel files.
 */
class AmrDemuxerTest {
    static List<Packet> packets(byte[] data) {
        try (var demuxer = new AmrDemuxer(MediaSource.of(data))) {
            return AudioPipeline.packets(demuxer, 0);
        }
    }

    static void assertMatchesFfprobe(java.nio.file.Path file, Codec codec, int rate) {
        var bytes = Fixtures.read(file);
        try (var demuxer = new AmrDemuxer(MediaSource.of(bytes))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(codec, track.codec());
            assertEquals(rate, track.sampleRate());
            var packets = AudioPipeline.packets(demuxer, 0);
            assertEquals((long) packets.size() * (rate / 50), track.duration());
            assertEquals(ProbedPacket.probe(file), packets.stream().map(ProbedPacket::of).toList());
        }
    }

    @ParameterizedTest(name = "{0} bps")
    @ValueSource(ints = {4750, 5150, 5900, 6700, 7400, 7950, 10200, 12200})
    @DisplayName("splits every AMR-NB mode like ffprobe")
    void narrowband(int bitrate) {
        var file = Fixtures.tone("amr", 440, 8000, 1, 0.83, List.of("-c:a", "libopencore_amrnb", "-b:a", Integer.toString(bitrate)));
        assertMatchesFfprobe(file, Codec.AMR_NB, 8000);
    }

    @ParameterizedTest(name = "{0} bps")
    @ValueSource(ints = {6600, 8850, 12650, 14250, 15850, 18250, 19850, 23050, 23850})
    @DisplayName("splits every AMR-WB mode like ffprobe")
    void wideband(int bitrate) {
        var file = Fixtures.tone("amr", 440, 16000, 1, 0.83, List.of("-c:a", "libvo_amrwbenc", "-b:a", Integer.toString(bitrate)));
        assertMatchesFfprobe(file, Codec.AMR_WB, 16000);
    }

    @Test
    @DisplayName("keeps comfort-noise and no-data frames of a DTX stream")
    void dtx() {
        var file = Fixtures.ffmpeg("amr", List.of("-f", "lavfi", "-i", "anullsrc=r=8000:cl=mono", "-t", "1.2"),
                List.of("-c:a", "libopencore_amrnb", "-b:a", "12200", "-dtx", "1"));
        assertMatchesFfprobe(file, Codec.AMR_NB, 8000);
        var types = packets(Fixtures.read(file)).stream().map(packet -> (packet.data()[0] >> 3) & 0x0F).distinct().toList();
        assertTrue(types.contains(8) || types.contains(15), "DTX produces SID or NO_DATA frames: " + types);
    }

    @ParameterizedTest(name = "NB type {0} -> {1} payload bytes")
    @CsvSource({"0, 12", "1, 13", "2, 15", "3, 17", "4, 19", "5, 20", "6, 26", "7, 31", "8, 5", "9, 6", "10, 5", "11, 5",
            "12, 0", "13, 0", "14, 0", "15, 0"})
    @DisplayName("sizes hand-built AMR-NB frames from TS 26.101")
    void narrowbandSizes(int type, int payload) {
        var file = new ByteArrayOutputStream();
        file.writeBytes("#!AMR\n".getBytes(StandardCharsets.US_ASCII));
        for (var i = 0; i < 3; i++) {
            file.write(type << 3 | 0x04);
            file.writeBytes(new byte[payload]);
        }
        var packets = packets(file.toByteArray());
        assertEquals(3, packets.size());
        for (var i = 0; i < 3; i++) {
            assertEquals(1 + payload, packets.get(i).size());
            assertEquals(160L * i, packets.get(i).pts());
            assertEquals(160, packets.get(i).duration());
        }
    }

    @ParameterizedTest(name = "WB type {0} -> {1} payload bytes")
    @CsvSource({"0, 17", "1, 23", "2, 32", "3, 36", "4, 40", "5, 46", "6, 50", "7, 58", "8, 60", "9, 5", "14, 0", "15, 0"})
    @DisplayName("sizes hand-built AMR-WB frames from TS 26.201")
    void widebandSizes(int type, int payload) {
        var file = new ByteArrayOutputStream();
        file.writeBytes("#!AMR-WB\n".getBytes(StandardCharsets.US_ASCII));
        file.write(type << 3 | 0x04);
        file.writeBytes(new byte[payload]);
        var packets = packets(file.toByteArray());
        assertEquals(1, packets.size());
        assertEquals(1 + payload, packets.getFirst().size());
        assertEquals(320, packets.getFirst().duration());
    }

    @Test
    @DisplayName("drops a final frame cut short by the end of the file")
    void truncated() {
        var file = Fixtures.read(Fixtures.tone("amr", 440, 8000, 1, 0.2, List.of("-c:a", "libopencore_amrnb")));
        var full = packets(file).size();
        var cut = java.util.Arrays.copyOf(file, file.length - 5);
        assertEquals(full - 1, packets(cut).size());
        try (var demuxer = new AmrDemuxer(MediaSource.of(cut))) {
            assertEquals((full - 1) * 160L, demuxer.tracks().getFirst().duration());
        }
    }

    @Test
    @DisplayName("rejects input without an AMR magic as malformed")
    void rejects() {
        assertThrows(MediaException.Malformed.class, () -> new AmrDemuxer(MediaSource.of("#!AMX\n".getBytes())));
        assertThrows(MediaException.Malformed.class, () -> new AmrDemuxer(MediaSource.of(new byte[0])));
        assertEquals(0, packets("#!AMR\n".getBytes(StandardCharsets.US_ASCII)).size());
    }

    @Test
    @DisplayName("reads a source that is not in memory once, keeping the duration walk's packets")
    void readsOnce() {
        var data = Fixtures.read(Fixtures.tone("amr", 440, 8000, 1, 30, List.of("-c:a", "libopencore_amrnb", "-b:a", "12200")));
        var source = new CountingSource(data, 1000);
        try (var counted = new AmrDemuxer(source);
             var memory = new AmrDemuxer(MediaSource.of(data))) {
            assertEquals(memory.tracks(), counted.tracks());
            var opened = source.bytes;
            assertEquals(probed(memory), probed(counted));
            assertEquals(opened, source.bytes, "the demux reads nothing after construction");
        }
        // the ID3v2 header probe, the magic and the block of the trailing tags are read once by themselves before
        // the walk
        assertTrue(source.bytes <= data.length + 10 + 19 + 4096, source.toString());
        assertEquals(1, source.requests, source.toString());
    }

    @ParameterizedTest(name = "maxBytes {0}")
    @ValueSource(longs = {1, 500, 3000, 70_000})
    @DisplayName("continues from the first frame it did not keep when the budget runs out")
    void budget(long maxBytes) {
        var limits = MediaLimits.builder().maxDimension(16_384).maxPixels(128L * 1024 * 1024).maxFrames(1_000_000)
                .maxChannels(64).maxBytes(maxBytes).build();
        var options = DemuxerOptions.builder().limits(limits).build();
        var full = Fixtures.read(Fixtures.tone("amr", 440, 8000, 1, 3, List.of("-c:a", "libopencore_amrnb")));
        for (var data : List.of(full, java.util.Arrays.copyOf(full, full.length - 5))) {
            for (var chunk : new int[]{7, 1000, Integer.MAX_VALUE}) {
                try (var counted = new AmrDemuxer(new CountingSource(data, chunk), options);
                     var memory = new AmrDemuxer(MediaSource.of(data), options)) {
                    assertEquals(memory.tracks(), counted.tracks());
                    assertEquals(probed(memory), probed(counted), "chunk " + chunk);
                }
            }
        }
    }

    static List<ProbedPacket> probed(AmrDemuxer demuxer) {
        return AudioPipeline.packets(demuxer, 0).stream().map(ProbedPacket::of).toList();
    }

    @Test
    @DisplayName("resists hundreds of random mutations")
    void mutations() {
        var inputs = List.of(
                Fixtures.read(Fixtures.tone("amr", 440, 8000, 1, 0.3, List.of("-c:a", "libopencore_amrnb"))),
                Fixtures.read(Fixtures.tone("amr", 440, 16000, 1, 0.3, List.of("-c:a", "libvo_amrwbenc"))));
        var stats = AudioMutations.assertRobust(inputs, 200, 0xA3AL, Duration.ofSeconds(5), bytes -> packets(bytes));
        System.out.println("AmrDemuxer mutation outcomes: " + stats);
    }
}
