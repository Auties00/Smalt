package com.github.auties00.smalt.container;

import com.github.auties00.smalt.audio.AudioReader;
import com.github.auties00.smalt.audio.AudioReaderOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link Demuxer#seek(long)} on every container Smalt demuxes and the sample-accurate
 * {@link AudioReader#seek(long)} on top of it. Every expectation is derived from a linear read of
 * the same file: after a seek, each track must restart at the last packet that is a key frame
 * and starts at or before the target, and continue exactly as the linear read does; an audio read
 * after a seek must equal the linear decode from the target sample on.
 */
@DisplayName("Seeking")
class SeekTest {
    private static final double SECONDS = 6;

    private static final String VIDEO = "testsrc2=size=160x120:rate=25:duration=" + SECONDS;

    private static final String AUDIO = "aevalsrc=0.3*sin(2*PI*(200+150*t)*t)|0.25*sin(2*PI*330*t+3*sin(5*t)):s=48000:d=" + SECONDS;

    private static final String MONO = "aevalsrc=0.3*sin(2*PI*(200+150*t)*t):s=8000:d=" + SECONDS;

    private static final long[] TARGETS = {-500_000, 0, 1, 480_000, 1_333_333, 2_500_000, 4_040_000, 5_900_000,
            7_000_000};

    static Path video(String extension, List<String> options) {
        var output = new ArrayList<>(options);
        output.add("-shortest");
        return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", VIDEO, "-f", "lavfi", "-i", AUDIO), output);
    }

    static Path audio(String extension, List<String> options) {
        return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", AUDIO), options);
    }

    /** Concatenates three two-second Ogg links (Opus, Vorbis, Opus) into a chained file. */
    static Path chainedOgg() {
        var output = new java.io.ByteArrayOutputStream();
        var parts = List.of(List.of("-c:a", "libopus", "-f", "ogg", "-serial_offset", "1"),
                List.of("-c:a", "libvorbis", "-serial_offset", "2"), List.of("-c:a", "libopus", "-f", "ogg", "-serial_offset", "3"));
        for (var index = 0; index < parts.size(); index++) {
            var options = new ArrayList<>(List.of("-ss", Integer.toString(2 * index), "-t", "2"));
            options.addAll(parts.get(index));
            output.writeBytes(Fixtures.read(audio("ogg", options)));
        }
        try {
            var file = Path.of("target", "media-fixtures", "chained-seek.ogg");
            java.nio.file.Files.createDirectories(file.getParent());
            java.nio.file.Files.write(file, output.toByteArray());
            return file;
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    static Stream<Arguments> containers() {
        return Stream.of(
                Arguments.of("mp4 h264 b-frames + aac", video("mp4", List.of("-c:v", "libx264", "-g", "25", "-bf", "2",
                        "-c:a", "aac"))),
                Arguments.of("fragmented mp4", video("mp4", List.of("-c:v", "libx264", "-g", "25", "-c:a", "aac",
                        "-movflags", "frag_keyframe+empty_moov+default_base_moof"))),
                Arguments.of("mkv h264 + opus", video("mkv", List.of("-c:v", "libx264", "-g", "25", "-bf", "2",
                        "-c:a", "libopus"))),
                Arguments.of("webm vp9 + vorbis", video("webm", List.of("-c:v", "libvpx-vp9", "-g", "25", "-deadline",
                        "realtime", "-cpu-used", "8", "-c:a", "libvorbis"))),
                Arguments.of("live webm without cues", video("webm", List.of("-c:v", "libvpx", "-g", "25", "-deadline",
                        "realtime", "-c:a", "libopus", "-live", "1"))),
                Arguments.of("ivf vp9", video("ivf", List.of("-c:v", "libvpx-vp9", "-g", "25", "-deadline", "realtime",
                        "-cpu-used", "8", "-an"))),
                Arguments.of("ivf vp8", video("ivf", List.of("-c:v", "libvpx", "-g", "25", "-an"))),
                Arguments.of("mp3", audio("mp3", List.of("-c:a", "libmp3lame", "-b:a", "128k"))),
                Arguments.of("mp3 cbr without xing", audio("mp3", List.of("-c:a", "libmp3lame", "-b:a", "64k",
                        "-write_xing", "0"))),
                Arguments.of("m4a aac", audio("m4a", List.of("-c:a", "aac"))),
                Arguments.of("adts aac", audio("aac", List.of("-c:a", "aac", "-f", "adts"))),
                Arguments.of("ogg opus", audio("opus", List.of("-c:a", "libopus", "-f", "ogg"))),
                Arguments.of("ogg vorbis", audio("ogg", List.of("-c:a", "libvorbis"))),
                Arguments.of("ogg chained opus, vorbis, opus", chainedOgg()),
                Arguments.of("ogv theora + vorbis", video("ogv", List.of("-c:v", "libtheora", "-g", "25", "-c:a",
                        "libvorbis"))),
                Arguments.of("flac", audio("flac", List.of("-c:a", "flac"))),
                Arguments.of("wav", audio("wav", List.of("-c:a", "pcm_s16le"))),
                Arguments.of("aiff", audio("aiff", List.of("-c:a", "pcm_s16be"))),
                Arguments.of("mka flac", audio("mka", List.of("-c:a", "flac"))),
                Arguments.of("wv wavpack", audio("wv", List.of("-c:a", "wavpack"))),
                Arguments.of("mka wavpack", audio("mka", List.of("-c:a", "wavpack"))),
                Arguments.of("ogg speex", Fixtures.ffmpeg("spx", List.of("-f", "lavfi", "-i", MONO),
                        List.of("-c:a", "libspeex"))),
                Arguments.of("mkv ffv1 + flac", video("mkv", List.of("-c:v", "ffv1", "-level", "3", "-g", "25",
                        "-c:a", "flac"))),
                Arguments.of("amr", Fixtures.ffmpeg("amr", List.of("-f", "lavfi", "-i", MONO),
                        List.of("-c:a", "libopencore_amrnb", "-b:a", "12.2k"))),
                Arguments.of("ts h264 b-frames + aac", video("ts", List.of("-c:v", "libx264", "-g", "25", "-bf", "2",
                        "-c:a", "aac"))),
                Arguments.of("m2ts h264 + ac3", video("m2ts", List.of("-c:v", "libx264", "-g", "25", "-c:a", "ac3",
                        "-mpegts_m2ts_mode", "1"))),
                Arguments.of("ts mp2", audio("ts", List.of("-c:a", "mp2"))));
    }

    static List<Packet> readAll(Demuxer demuxer) {
        var packets = new ArrayList<Packet>();
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            packets.add(packet);
        }
        return packets;
    }

    static long time(Packet packet) {
        return packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts();
    }

    /**
     * Returns the index, in the track's linear packet list, of the packet a seek must restart the
     * track at, or the list size when the track ends at or before the target.
     */
    static int expectedStart(List<Packet> packets, Track track, long micros) {
        // the target rounds down: the packet displayed at an instant starts at or before it
        var target = java.math.BigInteger.valueOf(micros).multiply(java.math.BigInteger.valueOf(track.timeBase().denominator()))
                .divide(java.math.BigInteger.valueOf(track.timeBase().numerator() * 1_000_000L)).longValue();
        if (micros < 0) {
            target = Long.MIN_VALUE / 4;
        }
        // a packet without a duration lasts until the next one, the last one as long as the interval before it
        var end = Long.MIN_VALUE;
        var previous = Long.MIN_VALUE;
        for (var packet : packets) {
            var time = time(packet);
            if (time == Packet.NO_TIMESTAMP) {
                continue;
            }
            var interval = previous == Long.MIN_VALUE ? 1 : Math.max(1, time - previous);
            previous = Math.max(previous, time);
            end = Math.max(end, time + (packet.duration() > 0 ? packet.duration() : interval));
        }
        if (!packets.isEmpty() && end <= target) {
            return packets.size();
        }
        var start = -1;
        for (var index = 0; index < packets.size(); index++) {
            var packet = packets.get(index);
            if (packet.keyframe() && time(packet) != Packet.NO_TIMESTAMP && time(packet) <= target) {
                start = index;
            }
        }
        if (start < 0) {
            start = 0;
            while (start < packets.size() - 1 && !packets.get(start).keyframe()) {
                start++;
            }
        }
        return start;
    }

    static boolean isPcm(Track track) {
        return track.codec().name().startsWith("PCM_");
    }

    static byte[] concat(List<Packet> packets) {
        var size = 0;
        for (var packet : packets) {
            size += packet.data().length;
        }
        var result = new byte[size];
        var offset = 0;
        for (var packet : packets) {
            System.arraycopy(packet.data(), 0, result, offset, packet.data().length);
            offset += packet.data().length;
        }
        return result;
    }

    /**
     * Checks that uncompressed audio restarts at the sample frame displayed at the target and
     * continues with the linear bytes.
     */
    static void assertPcmTail(String name, List<Packet> linear, List<Packet> after, Track track, long micros) {
        var frame = linear.getFirst().data().length / linear.getFirst().duration();
        var total = linear.stream().mapToLong(Packet::duration).sum();
        var sample = Math.clamp(Math.floorDiv(Math.max(0, micros) * (track.timeBase().denominator() / track.timeBase().numerator()),
                1_000_000L), 0, total);
        var linearBytes = concat(linear);
        var tail = concat(after);
        assertArrayEquals(Arrays.copyOfRange(linearBytes, (int) (sample * frame), linearBytes.length), tail,
                name + " pcm bytes after a seek to " + micros);
        if (!after.isEmpty()) {
            assertEquals(sample, after.getFirst().pts(), name + " first pcm pts after a seek to " + micros);
        }
    }

    static Map<Integer, List<Packet>> byTrack(List<Packet> packets) {
        var result = new HashMap<Integer, List<Packet>>();
        for (var packet : packets) {
            result.computeIfAbsent(packet.trackId(), _ -> new ArrayList<>()).add(packet);
        }
        return result;
    }

    @Nested
    @DisplayName("a demuxer")
    class Demuxers {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.SeekTest#containers")
        @DisplayName("restarts every track at its last key frame at or before the target and continues as a linear read")
        void restarts(String name, Path file) {
            var bytes = Fixtures.read(file);
            List<Packet> linear;
            try (var demuxer = Containers.openDemuxer(MediaSource.of(bytes))) {
                linear = readAll(demuxer);
            }
            var linearByTrack = byTrack(linear);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(bytes))) {
                assertTrue(demuxer.isSeekable(), name + " is seekable");
                for (var micros : TARGETS) {
                    demuxer.seek(micros);
                    var after = byTrack(readAll(demuxer));
                    for (var track : demuxer.tracks()) {
                        var expected = linearByTrack.getOrDefault(track.id(), List.of());
                        if (isPcm(track)) {
                            assertPcmTail(name, expected, after.getOrDefault(track.id(), List.of()), track, micros);
                            continue;
                        }
                        var start = expectedStart(expected, track, micros);
                        var actual = after.getOrDefault(track.id(), List.of());
                        assertEquals(expected.size() - start, actual.size(),
                                name + " track " + track.id() + " packets after a seek to " + micros);
                        for (var index = 0; index < actual.size(); index++) {
                            var want = expected.get(start + index);
                            var got = actual.get(index);
                            assertArrayEquals(want.data(), got.data(),
                                    name + " track " + track.id() + " packet " + index + " after a seek to " + micros);
                            if (index > 0 || track.codec() != com.github.auties00.smalt.track.Codec.VORBIS) {
                                assertEquals(want.pts(), got.pts(), name + " track " + track.id() + " packet " + index
                                        + " pts after a seek to " + micros);
                            }
                        }
                    }
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.SeekTest#containers")
        @DisplayName("survives random seeks between partial reads without returning packets before the start")
        void fuzz(String name, Path file) {
            var bytes = Fixtures.read(file);
            List<Packet> linear;
            try (var demuxer = Containers.openDemuxer(MediaSource.of(bytes))) {
                linear = readAll(demuxer);
            }
            var linearByTrack = byTrack(linear);
            var random = new Random(name.hashCode());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(bytes))) {
                for (var round = 0; round < 40; round++) {
                    var micros = random.nextLong(-1_000_000, (long) (SECONDS * 1_000_000) + 1_000_000);
                    demuxer.seek(micros);
                    var seen = new HashMap<Integer, Integer>();
                    var reads = random.nextInt(60);
                    for (var count = 0; count < reads; count++) {
                        var packet = demuxer.read();
                        if (packet == null) {
                            break;
                        }
                        var track = demuxer.tracks().stream().filter(t -> t.id() == packet.trackId()).findFirst().orElseThrow();
                        var expected = linearByTrack.get(packet.trackId());
                        if (isPcm(track)) {
                            var frame = expected.getFirst().data().length / expected.getFirst().duration();
                            var linearBytes = concat(expected);
                            var offset = (int) (packet.pts() * frame);
                            assertArrayEquals(Arrays.copyOfRange(linearBytes, offset, offset + packet.data().length), packet.data(),
                                    name + " pcm bytes after a seek to " + micros);
                            continue;
                        }
                        var start = expectedStart(expected, track, micros);
                        var position = seen.merge(packet.trackId(), 1, Integer::sum) - 1;
                        assertTrue(start + position < expected.size(), name + " read past the end after a seek to " + micros);
                        assertArrayEquals(expected.get(start + position).data(), packet.data(),
                                name + " packet " + position + " of track " + packet.trackId() + " after a seek to " + micros);
                    }
                }
            }
        }
    }

    static Stream<Arguments> audioFiles() {
        return Stream.of(
                Arguments.of("wav", audio("wav", List.of("-c:a", "pcm_s16le")), 0.0),
                Arguments.of("flac", audio("flac", List.of("-c:a", "flac")), 0.0),
                Arguments.of("mka flac", audio("mka", List.of("-c:a", "flac")), 0.0),
                Arguments.of("wv wavpack", audio("wv", List.of("-c:a", "wavpack")), 0.0),
                Arguments.of("mka wavpack", audio("mka", List.of("-c:a", "wavpack")), 0.0),
                Arguments.of("mp3", audio("mp3", List.of("-c:a", "libmp3lame", "-b:a", "128k")), 0.0),
                Arguments.of("mp3 low bitrate", audio("mp3", List.of("-c:a", "libmp3lame", "-b:a", "32k")), 0.0),
                Arguments.of("ogg vorbis", audio("ogg", List.of("-c:a", "libvorbis")), 0.0),
                Arguments.of("m4a aac", audio("m4a", List.of("-c:a", "aac", "-aac_pns", "0")), 0.0),
                Arguments.of("adts aac", audio("aac", List.of("-c:a", "aac", "-aac_pns", "0", "-f", "adts")), 0.0),
                // perceptual noise substitution draws from a generator that runs from the start of
                // the stream, so the substituted bands differ after a seek
                Arguments.of("m4a aac with noise substitution", audio("m4a", List.of("-c:a", "aac", "-aac_pns", "1")), 0.01),
                Arguments.of("ogg opus", audio("opus", List.of("-c:a", "libopus", "-f", "ogg")), 0.0),
                Arguments.of("webm opus", audio("webm", List.of("-c:a", "libopus")), 0.0),
                Arguments.of("mp4 opus", audio("mp4", List.of("-c:a", "libopus")), 0.0),
                // millisecond Matroska timestamps do not fall on the sample boundaries of these
                // packets, so the reader decodes from the start instead of trusting them
                Arguments.of("webm vorbis", audio("webm", List.of("-c:a", "libvorbis")), 0.0),
                Arguments.of("mka aac", audio("mka", List.of("-c:a", "aac", "-aac_pns", "0")), 0.0),
                Arguments.of("ts aac", audio("ts", List.of("-c:a", "aac", "-aac_pns", "0")), 0.0),
                Arguments.of("ts mp2", audio("ts", List.of("-c:a", "mp2")), 0.0),
                Arguments.of("ts opus", audio("ts", List.of("-c:a", "libopus")), 0.0));
    }

    static float[][] decodeAll(AudioReader reader) {
        var planes = new float[reader.layout().channels()][1 << 16];
        var count = 0;
        for (var block = reader.read(); block != null; block = reader.read()) {
            if (count + block.frames() > planes[0].length) {
                for (var channel = 0; channel < planes.length; channel++) {
                    planes[channel] = Arrays.copyOf(planes[channel], Math.max(planes[channel].length * 2, count + block.frames()));
                }
            }
            for (var channel = 0; channel < planes.length; channel++) {
                System.arraycopy(block.plane(channel), 0, planes[channel], count, block.frames());
            }
            count += block.frames();
        }
        for (var channel = 0; channel < planes.length; channel++) {
            planes[channel] = Arrays.copyOf(planes[channel], count);
        }
        return planes;
    }

    @Nested
    @DisplayName("an audio reader")
    class Reader {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.SeekTest#audioFiles")
        @DisplayName("returns after a seek exactly the samples a linear decode returns from the target on")
        void sampleAccurate(String name, Path file, double tolerance) {
            assertSampleAccurate(name, Fixtures.read(file), tolerance, AudioReaderOptions.defaults());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.SeekTest#audioFiles")
        @DisplayName("returns after a seek exactly the resampled samples a linear decode returns from the target on")
        void resampled(String name, Path file, double tolerance) {
            assertSampleAccurate(name, Fixtures.read(file), tolerance, AudioReaderOptions.builder().sampleRate(44_100)
                    .build());
        }

        private static void assertSampleAccurate(String name, byte[] bytes, double tolerance, AudioReaderOptions options) {
            float[][] linear;
            int rate;
            try (var reader = AudioReader.open(MediaSource.of(bytes), options)) {
                rate = reader.sampleRate();
                linear = decodeAll(reader);
            }
            for (var micros : new long[]{0, 21, 333_333, 1_000_000, 2_718_281, 4_500_000, 5_990_000, 9_000_000}) {
                try (var reader = AudioReader.open(MediaSource.of(bytes), options)) {
                    reader.seek(micros);
                    var tail = decodeAll(reader);
                    var start = (int) Math.min(linear[0].length, Rational.MICROSECONDS.rescale(micros,
                            Rational.timeBaseOf(rate)));
                    var maxDiff = 0.0;
                    var firstDiff = -1;
                    for (var channel = 0; channel < linear.length; channel++) {
                        for (var index = 0; index < Math.min(tail[channel].length, linear[channel].length - start); index++) {
                            var diff = Math.abs(tail[channel][index] - linear[channel][start + index]);
                            if (diff > maxDiff) {
                                maxDiff = diff;
                            }
                            if (diff > tolerance && firstDiff < 0) {
                                firstDiff = index;
                            }
                        }
                    }
                    assertEquals(linear[0].length - start, tail[0].length, name + " samples after a seek to " + micros);
                    assertTrue(maxDiff <= tolerance, name + " max difference " + maxDiff + " after a seek to " + micros
                            + ", first at " + firstDiff);
                }
            }
        }
    }
}
