package com.github.auties00.smalt.container;

import com.github.auties00.smalt.container.adts.AdtsDemuxer;
import com.github.auties00.smalt.container.aiff.AiffDemuxer;
import com.github.auties00.smalt.container.amr.AmrDemuxer;
import com.github.auties00.smalt.container.ogg.OggDemuxer;
import com.github.auties00.smalt.container.wav.WavDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.testkit.audio.ProbedPacket;
import com.github.auties00.smalt.testkit.audio.UnsizedSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that every audio demuxer of this workstream returns the same packets from a source of
 * unknown length that delivers data in small chunks as from a plain byte array.
 */
class UnsizedSourceTest {
    static Stream<Arguments> demuxers() {
        return Stream.of(
                Arguments.of("wav", Fixtures.tone("wav", 440, 44100, 2, 0.5, List.of("-c:a", "pcm_s16le")),
                        (Function<MediaSource, Demuxer>) WavDemuxer::new),
                Arguments.of("aiff", Fixtures.tone("aiff", 440, 44100, 2, 0.5, List.of("-c:a", "pcm_s16be")),
                        (Function<MediaSource, Demuxer>) AiffDemuxer::new),
                Arguments.of("amr", Fixtures.tone("amr", 440, 8000, 1, 0.5, List.of("-c:a", "libopencore_amrnb")),
                        (Function<MediaSource, Demuxer>) AmrDemuxer::new),
                Arguments.of("adts", Fixtures.tone("aac", 440, 44100, 2, 0.5, List.of("-c:a", "aac", "-f", "adts")),
                        (Function<MediaSource, Demuxer>) AdtsDemuxer::new),
                Arguments.of("opus", Fixtures.tone("opus", 440, 48000, 2, 0.5, List.of("-c:a", "libopus")),
                        (Function<MediaSource, Demuxer>) OggDemuxer::new),
                Arguments.of("vorbis", Fixtures.tone("ogg", 440, 44100, 2, 0.5, List.of("-c:a", "libvorbis")),
                        (Function<MediaSource, Demuxer>) OggDemuxer::new));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("demuxers")
    @DisplayName("returns identical packets from an unsized, chunked source")
    void unsized(String name, Path file, Function<MediaSource, Demuxer> factory) {
        var bytes = Fixtures.read(file);
        List<ProbedPacket> expected;
        try (var demuxer = factory.apply(MediaSource.of(bytes))) {
            expected = AudioPipeline.packets(demuxer, demuxer.tracks().getFirst().id()).stream().map(ProbedPacket::of).toList();
        }
        for (var chunk : new int[]{1, 7, 4096}) {
            try (var demuxer = factory.apply(new UnsizedSource(bytes, chunk))) {
                var actual = AudioPipeline.packets(demuxer, demuxer.tracks().getFirst().id()).stream().map(ProbedPacket::of).toList();
                assertEquals(expected, actual, "chunk " + chunk);
            }
        }
    }
}
