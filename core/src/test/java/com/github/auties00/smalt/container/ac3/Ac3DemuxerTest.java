package com.github.auties00.smalt.container.ac3;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
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

import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the raw AC-3 and E-AC-3 demuxer on streams ffmpeg's encoders write: packets equal to
 * ffprobe's in size and content, the declared track, detection, chunked reading, and mutation
 * robustness.
 */
class Ac3DemuxerTest {
    static List<Packet> packets(byte[] data) {
        try (var demuxer = new Ac3Demuxer(MediaSource.of(data))) {
            return AudioPipeline.packets(demuxer, demuxer.tracks().getFirst().id());
        }
    }

    static List<List<Long>> sizesAndHashes(List<ProbedPacket> packets) {
        return packets.stream().map(packet -> List.of((long) packet.size(), packet.crc32())).toList();
    }

    static Path fixture(String extension, int sampleRate, int channels, String bitrate) {
        return Fixtures.tone(extension, 440, sampleRate, channels, 1.0, List.of("-c:a", extension, "-b:a", bitrate));
    }

    @ParameterizedTest(name = "{0} {1} Hz {2} channels {3}")
    @CsvSource({
            "ac3, 48000, 1, 96k",
            "ac3, 44100, 2, 192k",
            "ac3, 32000, 6, 448k",
            "eac3, 48000, 2, 96k",
            "eac3, 48000, 6, 384k"})
    @DisplayName("splits ffmpeg's streams into the packets ffprobe reports")
    void matchesFfprobe(String extension, int sampleRate, int channels, String bitrate) {
        var file = fixture(extension, sampleRate, channels, bitrate);
        var bytes = Fixtures.read(file);
        assertEquals(ContainerFormat.AC3, Containers.detect(MediaSource.of(bytes)).orElseThrow());
        try (var demuxer = new Ac3Demuxer(MediaSource.of(bytes))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(extension.equals("ac3") ? Codec.AC3 : Codec.EAC3, track.codec());
            assertEquals(sampleRate, track.sampleRate());
            assertEquals(channels, track.layout().orElseThrow().channels());
            var packets = AudioPipeline.packets(demuxer, track.id());
            assertEquals(packets.size() * 1536L, track.duration());
            assertEquals(sizesAndHashes(ProbedPacket.probe(file)),
                    sizesAndHashes(packets.stream().map(ProbedPacket::of).toList()));
            for (var index = 0; index < packets.size(); index++) {
                assertEquals(index * 1536L, packets.get(index).pts());
            }
        }
    }

    @Test
    @DisplayName("reads a source delivered in small chunks like one in memory")
    void chunked() {
        var data = Fixtures.read(fixture("ac3", 48000, 2, "128k"));
        for (var chunk : new int[]{1, 7, 1000}) {
            try (var counted = new Ac3Demuxer(new CountingSource(data, chunk));
                 var memory = new Ac3Demuxer(MediaSource.of(data))) {
                assertEquals(memory.tracks(), counted.tracks());
                assertEquals(AudioPipeline.packets(memory, 0).stream().map(ProbedPacket::of).toList(),
                        AudioPipeline.packets(counted, 0).stream().map(ProbedPacket::of).toList(), "chunk " + chunk);
            }
        }
    }

    @Test
    @DisplayName("rejects input without two consecutive sync frames")
    void rejects() {
        var data = Fixtures.read(fixture("ac3", 48000, 2, "128k"));
        assertFalse(Ac3Demuxer.matches(Arrays.copyOf(new byte[]{0x0B, 0x77, 0, 0, 0x7F, 0x40}, 600)));
        assertThrows(MediaException.Malformed.class, () -> new Ac3Demuxer(MediaSource.of(new byte[1000])));
        var options = DemuxerOptions.builder().limits(MediaLimits.builder().maxChannels(1).build()).build();
        assertThrows(MediaException.class, () -> new Ac3Demuxer(MediaSource.of(data), options));
    }

    @Test
    @DisplayName("resists hundreds of random mutations")
    void mutations() {
        var inputs = List.of(
                Fixtures.read(fixture("ac3", 48000, 2, "128k")),
                Fixtures.read(fixture("eac3", 48000, 6, "256k")));
        var stats = AudioMutations.assertRobust(inputs, 200, 0xAC3L, Duration.ofSeconds(5), Ac3DemuxerTest::packets);
        System.out.println("Ac3Demuxer mutation outcomes: " + stats);
    }
}
