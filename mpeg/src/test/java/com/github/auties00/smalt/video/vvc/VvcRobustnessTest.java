package com.github.auties00.smalt.video.vvc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feeds mutated VVenC streams (bit flips, truncated, dropped, duplicated and reordered NAL units, garbage NAL units,
 * corrupt parameter sets and adaptation parameter sets, corrupt length prefixes and configuration records) to the
 * decoder. Every call must return or raise a {@link MediaException} within the time limit; any other exception, a
 * hang or an out-of-memory condition fails the test. After the damage, the clean stream fed again from its first
 * IDR picture must decode exactly.
 */
@DisplayName("VVC decoder robustness")
class VvcRobustnessTest {
    private static final Duration LIMIT = Duration.ofMinutes(4);

    private static final int TRIALS = 16;

    private static final Path VECTORS = Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
            "video", "vvc");

    private static final Consumer<VideoFrame> DISCARD = frame -> {
    };

    static Stream<String> sources() {
        return Stream.of("ra-420-10bit", "ld-420-8bit", "intra-400-10bit", "tiles-420-10bit", "wpp-420-10bit");
    }

    static Stream<Arguments> mutations() {
        return sources().flatMap(source -> Stream.of("bit flips", "truncation", "dropped and duplicated", "reordered",
                "garbage", "parameter sets").map(mutation -> Arguments.of(source, mutation)));
    }

    private static byte[] bytes(String source) {
        return Fixtures.read(VECTORS.resolve(source + ".vvc"));
    }

    private static List<byte[]> decodeHostile(List<byte[]> hostile, List<byte[]> clean) {
        var frames = new ArrayList<byte[]>();
        Consumer<VideoFrame> collect = frame -> frames.add(digest(frame));
        try (var decoder = new VvcDecoder(VvcTestStreams.track())) {
            for (var nal : hostile) {
                try {
                    decoder.decode(VvcTestStreams.packet(nal), DISCARD);
                } catch (MediaException expected) {
                    // Corrupt NAL units may be rejected; decoding continues with the next one.
                }
            }
            for (var nal : clean) {
                try {
                    decoder.decode(VvcTestStreams.packet(nal), collect);
                } catch (MediaException expected) {
                    // Leftover corrupt pictures may fail before the IDR picture resets the state.
                }
            }
            try {
                decoder.flush(collect);
            } catch (MediaException expected) {
                // A picture still in progress may be reported corrupt.
            }
        }
        return frames;
    }

    private static byte[] digest(VideoFrame frame) {
        var md5 = VvcTestStreams.md5();
        VvcTestStreams.hash(md5, frame.picture());
        return md5.digest();
    }

    private static byte[] withStartCode(byte[] nal) {
        var out = new byte[nal.length + 4];
        out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }

    private static byte[] flip(Random random, byte[] data, int flips, int from) {
        var copy = data.clone();
        for (var i = 0; i < flips && copy.length > from; i++) {
            var position = from + random.nextInt(copy.length - from);
            copy[position] ^= (byte) (1 << random.nextInt(8));
        }
        return copy;
    }

    private static List<byte[]> mutate(String mutation, Random random, List<byte[]> nals) {
        var result = new ArrayList<byte[]>();
        switch (mutation) {
            case "bit flips" -> {
                for (var nal : nals) {
                    result.add(withStartCode(random.nextInt(3) == 0 ? flip(random, nal, 1 + random.nextInt(6), 2)
                            : nal));
                }
            }
            case "truncation" -> {
                for (var nal : nals) {
                    if (random.nextInt(3) == 0 && nal.length > 3) {
                        result.add(withStartCode(Arrays.copyOf(nal, 2 + random.nextInt(nal.length - 2))));
                    } else {
                        result.add(withStartCode(nal));
                    }
                }
            }
            case "dropped and duplicated" -> {
                for (var nal : nals) {
                    var roll = random.nextInt(6);
                    if (roll == 0) {
                        continue;
                    }
                    result.add(withStartCode(nal));
                    if (roll == 1) {
                        result.add(withStartCode(nal));
                    }
                }
            }
            case "reordered" -> {
                for (var nal : nals) {
                    result.add(withStartCode(nal));
                }
                for (var i = 0; i < result.size() / 3; i++) {
                    var a = random.nextInt(result.size());
                    var b = random.nextInt(result.size());
                    var t = result.get(a);
                    result.set(a, result.get(b));
                    result.set(b, t);
                }
            }
            case "garbage" -> {
                for (var nal : nals) {
                    result.add(withStartCode(nal));
                    if (random.nextInt(4) == 0) {
                        var garbage = new byte[2 + random.nextInt(64)];
                        random.nextBytes(garbage);
                        garbage[0] = 0;
                        result.add(withStartCode(garbage));
                    }
                }
            }
            default -> {
                for (var nal : nals) {
                    var type = VvcTestStreams.type(nal);
                    // the OPI, DCI, VPS, SPS, PPS and APS units
                    var parameterSet = type >= 12 && type <= 18;
                    result.add(withStartCode(parameterSet || random.nextInt(8) == 0
                            ? flip(random, nal, 1 + random.nextInt(4), 2) : nal));
                }
            }
        }
        return result;
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("mutations")
    @DisplayName("fails only with MediaException and recovers at the next IDR picture")
    void survives(String source, String mutation) {
        var bytes = bytes(source);
        var nals = VvcTestStreams.nalUnits(bytes);
        var clean = VvcTestStreams.splitAnnexB(bytes);
        var reference = decodeHostile(List.of(), clean);
        assertTrue(reference.size() >= 3);
        assertTimeoutPreemptively(LIMIT, () -> {
            var random = new Random(source.hashCode() * 31L + mutation.hashCode());
            for (var trial = 0; trial < TRIALS; trial++) {
                var frames = decodeHostile(mutate(mutation, random, nals), clean);
                assertTrue(frames.size() >= reference.size(), "trial " + trial + " lost frames of the clean stream");
                var offset = frames.size() - reference.size();
                for (var i = 0; i < reference.size(); i++) {
                    assertArrayEquals(reference.get(i), frames.get(offset + i), "trial " + trial + ": clean frame " + i);
                }
            }
        });
    }

    @Test
    @DisplayName("rejects corrupt length prefixes and configuration records with MediaException only")
    void lengthPrefixes() {
        var nals = VvcTestStreams.nalUnits(bytes("ra-420-10bit"));
        var config = VvcTestStreams.vvcC(nals.stream().filter(VvcTestStreams::isParameterSet).toList(), true);
        var units = VvcTestStreams.accessUnits(nals);
        assertTimeoutPreemptively(LIMIT, () -> {
            var random = new Random(7);
            for (var trial = 0; trial < 60; trial++) {
                var corruptConfig = random.nextInt(4) == 0 ? flip(random, config, 1 + random.nextInt(3), 0) : config;
                if (random.nextInt(8) == 0) {
                    corruptConfig = Arrays.copyOf(corruptConfig, random.nextInt(corruptConfig.length + 1));
                }
                try (var decoder = new VvcDecoder(VvcTestStreams.track(corruptConfig))) {
                    for (var unit : units) {
                        var sample = VvcTestStreams.lengthPrefixed(unit);
                        if (random.nextInt(3) == 0) {
                            sample = flip(random, sample, 1 + random.nextInt(4), 0);
                        }
                        if (random.nextInt(5) == 0) {
                            sample = Arrays.copyOf(sample, random.nextInt(sample.length + 1));
                        }
                        try {
                            decoder.decode(new Packet(1, sample, 0, 0, 0, false), DISCARD);
                        } catch (MediaException expected) {
                            // Corrupt samples are rejected.
                        }
                    }
                    try {
                        decoder.flush(DISCARD);
                    } catch (MediaException expected) {
                        // A corrupt last picture is reported.
                    }
                } catch (MediaException expected) {
                    // A corrupt configuration record is rejected at construction.
                }
            }
        });
    }

    @Test
    @DisplayName("survives random bytes, alone and after valid parameter sets, with MediaException only")
    void randomBytes() {
        var nals = VvcTestStreams.nalUnits(bytes("ld-420-8bit"));
        var sets = nals.stream().filter(VvcTestStreams::isParameterSet).map(VvcRobustnessTest::withStartCode)
                .toList();
        assertTimeoutPreemptively(LIMIT, () -> {
            var random = new Random(11);
            for (var trial = 0; trial < 200; trial++) {
                var hostile = new ArrayList<byte[]>();
                if (trial % 2 == 0) {
                    hostile.addAll(sets);
                }
                for (var i = 0; i < 4; i++) {
                    var nal = new byte[2 + random.nextInt(300)];
                    random.nextBytes(nal);
                    nal[0] = 0;
                    // a slice of a random VCL type, or any type
                    nal[1] = (byte) (random.nextBoolean() ? (random.nextInt(11) << 3) | 1 : nal[1]);
                    hostile.add(withStartCode(nal));
                }
                decodeHostile(hostile, List.of());
            }
        });
    }
}
