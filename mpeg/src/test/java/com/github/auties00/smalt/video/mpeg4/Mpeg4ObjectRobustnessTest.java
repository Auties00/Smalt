package com.github.auties00.smalt.video.mpeg4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import com.github.auties00.smalt.video.VideoDecoderOptions;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Feeds corrupted and truncated copies of the MPEG-4 object tool streams ({@code object/golden.tsv}) and of the
 * two-layer scalable streams ({@code object/scalable.tsv}) to {@link Mpeg4Decoder}: bit flips, random bytes over a
 * span and truncation, each round seeded from the stream name so a failure reproduces. Every packet and the flush must
 * either decode or fail with a {@link MediaException}, and decoding must go on after a failure.
 */
@DisplayName("MPEG-4 object tools and scalable objects under corrupt input")
class Mpeg4ObjectRobustnessTest {
    private static final int ROUNDS = 24;

    private static final VideoDecoderOptions OPTIONS = VideoDecoderOptions.builder()
            .limits(MediaLimits.builder().maxPixels(4096L * 4096).maxBytes(256L << 20).build())
            .highBitDepth(true)
            .threads(1)
            .build();

    static Stream<String> single() {
        return names("object/golden.tsv");
    }

    static Stream<String> layered() {
        return names("object/scalable.tsv");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("single")
    @DisplayName("reports corrupt and truncated single layers only as media exceptions")
    void singleLayer(String name) {
        var original = Mpeg4ObjectConformanceTest.resource("object/" + name + ".m4v");
        var random = new SplittableRandom(name.hashCode());
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (var round = 0; round < ROUNDS; round++) {
                var data = corrupt(original, random);
                var packets = new ArrayList<Packet>();
                var track = split(data, 1, packets);
                if (track == null) {
                    continue;
                }
                var sized = random.nextBoolean() ? track.toBuilder().width(176).height(144).build() : track;
                Mpeg4Decoder decoder;
                try {
                    decoder = new Mpeg4Decoder(sized, OPTIONS);
                } catch (MediaException expected) {
                    assertNotNull(expected.getMessage());
                    continue;
                }
                try (decoder) {
                    feed(decoder, packets);
                }
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("layered")
    @DisplayName("reports corrupt and truncated layers of scalable objects only as media exceptions")
    void scalable(String name) {
        var base = Mpeg4ObjectConformanceTest.resource("object/" + name + ".m4v");
        var enhancement = Mpeg4ObjectConformanceTest.resource("object/" + name + "-e.m4v");
        var random = new SplittableRandom(name.hashCode());
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (var round = 0; round < ROUNDS; round++) {
                var corruptBase = random.nextInt(3) != 0;
                var baseData = corruptBase ? corrupt(base, random) : base;
                var enhancementData = !corruptBase || random.nextBoolean() ? corrupt(enhancement, random) : enhancement;
                var basePackets = new ArrayList<Packet>();
                var enhancementPackets = new ArrayList<Packet>();
                var baseTrack = split(baseData, 1, basePackets);
                var enhancementTrack = split(enhancementData, 2, enhancementPackets);
                if (baseTrack == null || enhancementTrack == null) {
                    continue;
                }
                Mpeg4Decoder decoder;
                try {
                    decoder = new Mpeg4Decoder(baseTrack, enhancementTrack, OPTIONS);
                } catch (MediaException expected) {
                    assertNotNull(expected.getMessage());
                    continue;
                }
                var interleaved = new ArrayList<Packet>();
                var run = 1 + random.nextInt(4);
                for (int b = 0, e = 0; b < basePackets.size() || e < enhancementPackets.size(); ) {
                    for (var i = 0; i < run && b < basePackets.size(); i++) {
                        interleaved.add(basePackets.get(b++));
                    }
                    for (var i = 0; i < run && e < enhancementPackets.size(); i++) {
                        interleaved.add(enhancementPackets.get(e++));
                    }
                }
                try (decoder) {
                    feed(decoder, interleaved);
                }
            }
        });
    }

    private static void feed(Mpeg4Decoder decoder, List<Packet> packets) {
        Consumer<VideoFrame> sink = frame -> assertNotNull(frame.picture());
        for (var packet : packets) {
            try {
                decoder.decode(packet, sink);
            } catch (MediaException expected) {
                assertNotNull(expected.getMessage());
            }
        }
        try {
            decoder.flush(sink);
        } catch (MediaException expected) {
            assertNotNull(expected.getMessage());
        }
    }

    /**
     * Splits a stream into packets of a track id; returns {@code null} when the corrupt stream does not split.
     */
    private static VideoTrack split(byte[] data, int id, List<Packet> packets) {
        try (var source = MediaSource.of(data); var stream = Mpeg4ElementaryStream.open(source)) {
            var track = stream.track().toBuilder().id(id).build();
            Packet packet;
            while ((packet = stream.read()) != null) {
                packets.add(packet.withTrackId(id));
            }
            return track;
        } catch (MediaException expected) {
            return null;
        }
    }

    private static byte[] corrupt(byte[] original, SplittableRandom random) {
        var data = original.clone();
        var length = data.length;
        switch (random.nextInt(3)) {
            case 0 -> {
                for (var i = 0; i < 1 + random.nextInt(8); i++) {
                    data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
                }
            }
            case 1 -> length = random.nextInt(data.length);
            default -> {
                var at = random.nextInt(data.length);
                var span = Math.min(data.length - at, 1 + random.nextInt(64));
                for (var i = 0; i < span; i++) {
                    data[at + i] = (byte) random.nextInt(256);
                }
            }
        }
        return Arrays.copyOf(data, length);
    }

    private static Stream<String> names(String table) {
        var text = new String(Mpeg4ObjectConformanceTest.resource(table), StandardCharsets.UTF_8);
        return text.lines().filter(line -> !line.isBlank() && !line.startsWith("#"))
                .map(line -> line.strip().split("\t")[0]);
    }
}
