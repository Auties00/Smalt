package com.github.auties00.smalt.io;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Demuxes a file of every container through a stream source and compares every packet with the
 * demux of the same bytes in an array. The strict runs fail any read below a released offset even
 * while its bytes are still kept, and keep a tiny memory budget so that the kept bytes spill, so a
 * demuxer that releases too early or reads the stream twice fails here. The bounded runs give a
 * memory-only budget far below the file size to the formats read front to back, which proves the
 * demuxer releases as it goes.
 */
@DisplayName("demuxing a stream source")
class StreamSourceDemuxTest {
    private static final String VIDEO = "testsrc2=size=320x240:rate=25:duration=";

    private static final String NOISE = "anoisesrc=color=pink:amplitude=0.4:sample_rate=48000:duration=";

    static Path video(String extension, double seconds, List<String> options) {
        var output = new ArrayList<>(options);
        output.add("-shortest");
        return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", VIDEO + seconds, "-f", "lavfi", "-i",
                NOISE + seconds), output);
    }

    static Path audio(String extension, double seconds, List<String> options) {
        var output = new ArrayList<>(List.of("-ac", "2"));
        output.addAll(options);
        return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", NOISE + seconds), output);
    }

    /**
     * The fixtures: a name, the file, and the memory-only budget a front-to-back demux stays
     * under, or {@code 0} for a layout that must keep its media data until its index is read.
     */
    static Stream<Arguments> containers() {
        // The video fixtures stay well above twice their budget whatever encoder version made them, since rate
        // control may undershoot.
        var x264 = List.of("-c:v", "libx264", "-preset", "ultrafast", "-b:v", "2000k", "-g", "25", "-bf", "2");
        var vp8 = List.of("-c:v", "libvpx", "-deadline", "realtime", "-cpu-used", "8", "-b:v", "2000k", "-g", "25");
        return Stream.of(
                Arguments.of("mp4 fast start", concat(x264, "-c:a", "aac", "-movflags", "+faststart"), "mp4", 30.0, 1536),
                Arguments.of("mp4 moov after mdat", concat(x264, "-c:a", "aac"), "mp4", 6.0, 0),
                Arguments.of("fragmented mp4", concat(x264, "-c:a", "aac", "-movflags",
                        "frag_keyframe+empty_moov+default_base_moof"), "mp4", 30.0, 1536),
                Arguments.of("mkv h264 + opus", concat(x264, "-c:a", "libopus"), "mkv", 30.0, 1536),
                Arguments.of("webm vp8 + vorbis", concat(vp8, "-c:a", "libvorbis"), "webm", 30.0, 1536),
                Arguments.of("ivf vp8", concat(vp8, "-an"), "ivf", 20.0, 1024),
                Arguments.of("avi h264 + mp3", concat(x264, "-c:a", "libmp3lame"), "avi", 20.0, 0),
                Arguments.of("ts h264 + aac", concat(x264, "-c:a", "aac"), "ts", 30.0, 1536),
                Arguments.of("mp3", List.of("-c:a", "libmp3lame", "-b:a", "320k"), "mp3", 60.0, 512),
                Arguments.of("adts aac", List.of("-c:a", "aac", "-b:a", "320k", "-f", "adts"), "aac", 60.0, 512),
                Arguments.of("ogg vorbis", List.of("-c:a", "libvorbis", "-q:a", "8"), "ogg", 60.0, 512),
                Arguments.of("ogg opus", List.of("-c:a", "libopus", "-b:a", "256k"), "opus", 60.0, 512),
                Arguments.of("flac", List.of("-c:a", "flac"), "flac", 15.0, 512),
                Arguments.of("wav", List.of("-c:a", "pcm_s16le"), "wav", 15.0, 512),
                Arguments.of("aiff", List.of("-c:a", "pcm_s16be"), "aiff", 15.0, 512),
                Arguments.of("ac3", List.of("-c:a", "ac3", "-b:a", "448k"), "ac3", 45.0, 512),
                Arguments.of("amr", List.of("-ac", "1", "-ar", "8000", "-c:a", "libopencore_amrnb", "-b:a", "12.2k"),
                        "amr", 20.0, 0),
                Arguments.of("mka flac", List.of("-c:a", "flac"), "mka", 15.0, 512));
    }

    static List<String> concat(List<String> head, String... tail) {
        var all = new ArrayList<>(head);
        all.addAll(List.of(tail));
        return all;
    }

    static Path fixture(String extension, List<String> options, double seconds) {
        var isVideo = !List.of("mp3", "aac", "ogg", "opus", "flac", "wav", "aiff", "ac3", "amr", "mka").contains(extension);
        return isVideo ? video(extension, seconds, options) : audio(extension, seconds, options);
    }

    /** A stream that counts the bytes it delivers. */
    static final class CountingStream extends FilterInputStream {
        long delivered;

        CountingStream(byte[] data) {
            super(new ByteArrayInputStream(data));
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            var read = super.read(buffer, offset, length);
            if (read > 0) {
                delivered += read;
            }
            return read;
        }
    }

    static List<String> packets(MediaSource source) {
        var lines = new ArrayList<String>();
        try (var demuxer = Containers.openDemuxer(source)) {
            for (Packet packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                lines.add(packet.trackId() + " " + packet.pts() + " " + packet.dts() + " " + packet.duration() + " "
                        + packet.flags() + " " + packet.data().length + " " + Arrays.hashCode(packet.data()));
            }
        }
        return lines;
    }

    @TempDir
    Path spillDirectory;

    @ParameterizedTest(name = "{0}")
    @MethodSource("containers")
    @DisplayName("strict, spilling stream source: same packets, stream read once, nothing left on disk")
    void strict(String name, List<String> options, String extension, double seconds, int boundKiB) throws IOException {
        var data = Files.readAllBytes(fixture(extension, options, seconds));
        var expected = packets(MediaSource.of(data));
        for (var known : new boolean[]{true, false}) {
            var stream = new CountingStream(data);
            var source = new StreamMediaSource(stream, null, known ? data.length : -1,
                    StreamSourceOptions.builder().memoryBudget(16 << 10).spillDirectory(spillDirectory).build(), true);
            try (source) {
                assertEquals(expected, packets(source), known ? "known length" : "unknown length");
            }
            assertTrue(stream.delivered <= data.length);
            try (var files = Files.list(spillDirectory)) {
                assertEquals(List.of(), files.toList());
            }
        }
    }

    /** The containers whose layout a stream source reads within a bounded memory budget (front to back). */
    static Stream<Arguments> boundedContainers() {
        return containers().filter(arguments -> (int) arguments.get()[4] > 0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("boundedContainers")
    @DisplayName("memory-only stream source far smaller than the file: same packets for formats read front to back")
    void bounded(String name, List<String> options, String extension, double seconds, int boundKiB) throws IOException {
        var data = Files.readAllBytes(fixture(extension, options, seconds));
        assertTrue(data.length > 2L * boundKiB * 1024, "fixture of " + data.length + " bytes is too small");
        var expected = packets(MediaSource.of(data));
        var options1 = StreamSourceOptions.memoryOnly().toBuilder().memoryBudget(boundKiB * 1024L).build();
        try (var source = new StreamMediaSource(new ByteArrayInputStream(data), null, data.length, options1, true)) {
            assertEquals(expected, packets(source));
        }
    }

    @Test
    @DisplayName("reads an MP4 whose moov follows its mdat through the stream exactly once")
    void moovAfterMdat() throws IOException {
        var data = Files.readAllBytes(video("mp4", 6.0, List.of("-c:v", "libx264", "-preset", "ultrafast", "-b:v",
                "1500k", "-g", "25", "-bf", "2", "-c:a", "aac")));
        var expected = packets(MediaSource.of(data));
        var stream = new CountingStream(data);
        var reads = new int[1];
        var counted = new FilterInputStream(stream) {
            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                reads[0]++;
                return super.read(buffer, offset, length);
            }
        };
        try (var source = MediaSource.of(counted, data.length,
                StreamSourceOptions.builder().memoryBudget(64 << 10).spillDirectory(spillDirectory).build())) {
            assertEquals(expected, packets(source));
        }
        assertEquals(data.length, stream.delivered, "every byte is taken from the stream once");
        assertTrue(reads[0] > 0);
    }
}
