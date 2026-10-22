package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoder;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.h263.H263Decoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feeds the MPEG-4 Part 2 and H.263 decoders corrupted packets (byte flips, truncation, zeroed and
 * shuffled ranges, packets dropped or swapped) and requires every failure to be a
 * {@link MediaException}, within a time bound, with the decoder still usable for the next packet.
 */
@DisplayName("MPEG-4 Part 2 and H.263 decoders on corrupted input")
class Mpeg4RobustnessTest {
    record Sample(String name, String container, String codec, String options, boolean h263) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Sample> samples() {
        return Stream.of(
                new Sample("advanced simple", "mp4", "mpeg4", "-bf 2 -flags +qpel+mv4 -mpeg_quant 1", false),
                new Sample("data partitioning", "mp4", "mpeg4", "-bf 0 -ps 300 -data_partitioning 1", false),
                new Sample("interlaced", "mp4", "mpeg4", "-bf 1 -flags +ildct+ilme", false),
                new Sample("xvid global motion", "mp4", "libxvid", "-bf 1 -gmc 1 -flags +qpel", false),
                new Sample("H.263 baseline", "3gp", "h263", "-flags +mv4 -obmc 1", true),
                new Sample("H.263+", "mkv", "h263p", "-flags +aic+loop+mv4 -umv 1 -aiv 1 -structured_slices 1", true),
                new Sample("H.263+ overlapped slices", "mkv", "h263p",
                        "-flags +mv4 -obmc 1 -umv 1 -structured_slices 1 -ps 200", true));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("only throws MediaException for corrupted packets")
    void corrupted(Sample stream) {
        var output = new ArrayList<>(List.of("-t", "0.8", "-c:v", stream.codec(), "-q:v", "6"));
        output.addAll(List.of(stream.options().split(" ")));
        var file = Fixtures.ffmpeg(stream.container(), List.of("-f", "lavfi", "-i",
                "testsrc2=size=176x144:rate=25"), output);
        VideoTrack track;
        var packets = new ArrayList<Packet>();
        try (var source = MediaSource.of(file); var demuxer = Containers.openDemuxer(source)) {
            track = (VideoTrack) demuxer.tracks().getFirst();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
        }
        Function<VideoTrack, VideoDecoder> factory = stream.h263() ? H263Decoder::new : Mpeg4Decoder::new;
        var random = new Random(stream.name().hashCode());
        var failures = 0;
        for (var round = 0; round < 60; round++) {
            var mutated = mutate(packets, random, round);
            failures += assertTimeoutPreemptively(Duration.ofSeconds(20), () -> decodeAll(factory, track, mutated),
                    () -> stream.name() + " round timed out");
        }
        assertTrue(failures < 60 * packets.size(), "some packets decode");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("samples")
    @DisplayName("only throws MediaException for corrupted packets with pictures decoded concurrently")
    void corruptedWithThreads(Sample stream) {
        if (stream.h263()) {
            return;
        }
        var output = new ArrayList<>(List.of("-t", "0.8", "-c:v", stream.codec(), "-q:v", "6"));
        output.addAll(List.of(stream.options().split(" ")));
        var file = Fixtures.ffmpeg(stream.container(), List.of("-f", "lavfi", "-i",
                "testsrc2=size=176x144:rate=25"), output);
        VideoTrack track;
        var packets = new ArrayList<Packet>();
        try (var source = MediaSource.of(file); var demuxer = Containers.openDemuxer(source)) {
            track = (VideoTrack) demuxer.tracks().getFirst();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
        }
        Function<VideoTrack, VideoDecoder> factory = t -> new Mpeg4Decoder(t, VideoDecoderOptions.builder().threads(4).build());
        var random = new Random(stream.name().hashCode() + 1);
        for (var round = 0; round < 60; round++) {
            var mutated = mutate(packets, random, round);
            assertTimeoutPreemptively(Duration.ofSeconds(20), () -> decodeAll(factory, track, mutated),
                    () -> stream.name() + " round timed out");
        }
    }

    static int decodeAll(Function<VideoTrack, VideoDecoder> factory, VideoTrack track, List<Packet> packets) {
        var failures = 0;
        try (var decoder = factory.apply(track)) {
            for (var packet : packets) {
                try {
                    decoder.decode(packet, frame -> {
                    });
                } catch (MediaException exception) {
                    failures++;
                }
            }
            try {
                decoder.flush(frame -> {
                });
            } catch (MediaException exception) {
                failures++;
            }
        }
        return failures;
    }

    static List<Packet> mutate(List<Packet> packets, Random random, int round) {
        var result = new ArrayList<>(packets);
        var count = 1 + random.nextInt(4);
        for (var n = 0; n < count; n++) {
            var index = random.nextInt(result.size());
            var original = result.get(index);
            var data = original.data().clone();
            if (data.length == 0) {
                continue;
            }
            switch ((round + n) % 6) {
                case 0 -> {
                    for (var i = 0; i < 1 + random.nextInt(16); i++) {
                        data[random.nextInt(data.length)] ^= (byte) (1 + random.nextInt(255));
                    }
                }
                case 1 -> data = Arrays.copyOf(data, random.nextInt(data.length));
                case 2 -> {
                    var start = random.nextInt(data.length);
                    Arrays.fill(data, start, Math.min(data.length, start + 1 + random.nextInt(64)), (byte) 0);
                }
                case 3 -> {
                    var start = random.nextInt(data.length);
                    Arrays.fill(data, start, Math.min(data.length, start + 1 + random.nextInt(64)), (byte) 0xFF);
                }
                case 4 -> {
                    result.remove(index);
                    continue;
                }
                default -> {
                    var other = random.nextInt(result.size());
                    result.set(index, result.get(other));
                    result.set(other, original);
                    continue;
                }
            }
            result.set(index, new Packet(original.trackId(), data, original.pts(), original.dts(), original.duration(),
                    original.keyframe()));
        }
        return result;
    }
}
