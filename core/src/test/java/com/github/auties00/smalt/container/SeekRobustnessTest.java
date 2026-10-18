package com.github.auties00.smalt.container;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seeks and probes damaged files of every container, which must fail only with a
 * {@code MediaException} and never hang, and checks the estimated seek of MPEG audio and ADTS
 * streams too large to index.
 */
@DisplayName("Seeking damaged and large inputs")
class SeekRobustnessTest {
    static Stream<Arguments> inputs() {
        var video = List.of("-f", "lavfi", "-i", "testsrc2=size=64x48:rate=25:duration=2", "-f", "lavfi", "-i",
                "sine=d=2");
        return Stream.of(
                Arguments.of("mp4", Fixtures.ffmpeg("mp4", video, List.of("-c:v", "libx264", "-g", "10", "-c:a", "aac", "-shortest"))),
                Arguments.of("mkv", Fixtures.ffmpeg("mkv", video, List.of("-c:v", "libx264", "-g", "10", "-c:a", "libopus", "-shortest"))),
                Arguments.of("live webm", Fixtures.ffmpeg("webm", video, List.of("-c:v", "libvpx", "-g", "10", "-c:a", "libopus",
                        "-live", "1", "-shortest"))),
                Arguments.of("ogg", Fixtures.tone("ogg", 440, 48_000, 2, 2, List.of("-c:a", "libvorbis"))),
                Arguments.of("flac", Fixtures.tone("flac", 440, 48_000, 2, 2, List.of("-c:a", "flac"))),
                Arguments.of("mp3", Fixtures.tone("mp3", 440, 48_000, 2, 2, List.of("-c:a", "libmp3lame"))),
                Arguments.of("aac", Fixtures.tone("aac", 440, 48_000, 2, 2, List.of("-c:a", "aac", "-f", "adts"))),
                Arguments.of("wav", Fixtures.tone("wav", 440, 8_000, 1, 2, List.of("-c:a", "pcm_s16le"))),
                Arguments.of("amr", Fixtures.tone("amr", 440, 8_000, 1, 2, List.of("-c:a", "libopencore_amrnb"))),
                Arguments.of("ivf", Fixtures.ffmpeg("ivf", video, List.of("-c:v", "libvpx-vp9", "-g", "10", "-an"))));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inputs")
    @DisplayName("fails cleanly on damaged inputs while seeking and probing")
    void damaged(String name, Path file) {
        var input = Fixtures.read(file);
        var variants = Mutations.variants(input, List.of(), name.hashCode(), 60, name.contains("mkv") || name.contains("webm"));
        var outcomes = Mutations.run(variants, Duration.ofSeconds(20), source -> {
            Media.probe(source);
            try (var demuxer = Containers.openDemuxer(source)) {
                var random = new Random(variants.size());
                for (var round = 0; round < 12; round++) {
                    if (demuxer.isSeekable()) {
                        demuxer.seek(random.nextLong(-500_000, 3_000_000));
                    }
                    for (var count = random.nextInt(20); count > 0 && demuxer.read() != null; count--) {
                        // read a few packets after every seek
                    }
                }
            }
        });
        assertTrue(outcomes.containsKey("ok"), name + " " + outcomes);
    }

    /** Repeats the frames of a constant-bitrate stream until it exceeds the indexed size. */
    static byte[] repeat(byte[] frames, int minimum) {
        var output = new ByteArrayOutputStream(minimum + frames.length);
        while (output.size() < minimum) {
            output.writeBytes(frames);
        }
        return output.toByteArray();
    }

    @Test
    @DisplayName("estimates the seek position of an MPEG audio stream too large to index")
    void largeMp3() {
        var file = Fixtures.tone("mp3", 440, 44_100, 1, 10, List.of("-c:a", "libmp3lame", "-b:a", "128k", "-write_xing", "0",
                "-id3v2_version", "0"));
        var stream = repeat(Fixtures.read(file), 70 << 20);
        try (var demuxer = Containers.openDemuxer(MediaSource.of(stream))) {
            assertTrue(demuxer.isSeekable());
            for (var micros : new long[]{60_000_000, 1_234_000_000, 3_600_000_000L}) {
                demuxer.seek(micros);
                var packet = demuxer.read();
                var seconds = packet.pts() / 44_100.0;
                assertEquals(0, packet.pts() % 1152, "frame aligned");
                assertEquals(micros / 1e6, seconds, micros / 1e6 * 0.01 + 0.1, "estimate near " + micros);
            }
            demuxer.seek(0);
            assertEquals(0, demuxer.read().pts());
        }
    }

    @Test
    @DisplayName("estimates the seek position of an ADTS stream too large to index")
    void largeAdts() {
        var file = Fixtures.tone("aac", 440, 44_100, 1, 10, List.of("-c:a", "aac", "-b:a", "128k", "-f", "adts"));
        var stream = repeat(Fixtures.read(file), 70 << 20);
        try (var demuxer = Containers.openDemuxer(MediaSource.of(stream))) {
            demuxer.seek(1_000_000_000);
            var packet = demuxer.read();
            assertEquals(0, packet.pts() % 1024, "frame aligned");
            assertEquals(1000, packet.pts() / 44_100.0, 15, "estimate near 1000 s");
            var next = demuxer.read();
            assertEquals(packet.pts() + packet.duration(), next.pts());
            assertTrue(next.pts() != Packet.NO_TIMESTAMP);
        }
    }
}
