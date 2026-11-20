package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feeds mutated streams (bit flips, truncated, dropped, duplicated and reordered NAL units, garbage
 * NAL units, corrupt parameter sets and corrupt length prefixes) to the decoder, including streams of
 * the range extension formats and coding tools, of the screen content coding tools and of separate
 * color planes. Every call must return or raise a {@link MediaException} within the time limit; any
 * other exception, a hang or an out-of-memory condition fails the test. After the damage, the clean stream fed again from its
 * first IDR picture must decode exactly.
 */
@DisplayName("HEVC decoder robustness")
class HevcRobustnessTest {
    private static final Duration LIMIT = Duration.ofMinutes(3);

    private static final Consumer<VideoFrame> DISCARD = frame -> {
    };

    record Source(String name, String pixelFormat, String params) {
        @Override
        public String toString() {
            return name;
        }

        byte[] bytes() {
            return Fixtures.read(Fixtures.ffmpeg("hevc", List.of("-f", "lavfi", "-i", "testsrc2=size=128x96:rate=25"),
                    List.of("-frames:v", "12", "-pix_fmt", pixelFormat, "-c:v", "libx265", "-x265-params",
                            "log-level=error:keyint=6:" + params, "-f", "hevc")));
        }
    }

    static Stream<Source> sources() {
        return Stream.of(
                new Source("random access with B pyramids", "yuv420p", "bframes=4:ref=3:weightb=1"),
                new Source("slices and wavefronts", "yuv420p", "slices=3:wpp=1:bframes=0"),
                new Source("transform skip and AMP", "yuv420p", "tskip=1:amp=1:rect=1:ctu=32"),
                new Source("4:2:2 10-bit with transform skip", "yuv422p10le", "bframes=2:tskip=1:weightp=1"),
                new Source("4:4:4 with lossless coding units", "yuv444p", "cu-lossless=1:tskip=1:amp=1")
        );
    }

    static Stream<Arguments> mutations() {
        return sources().flatMap(source -> Stream.of(
                Arguments.of(source, "bit flips"),
                Arguments.of(source, "truncation"),
                Arguments.of(source, "dropped and duplicated"),
                Arguments.of(source, "reordered"),
                Arguments.of(source, "garbage"),
                Arguments.of(source, "parameter sets")));
    }

    private static List<byte[]> decodeHostile(List<byte[]> hostile, List<byte[]> clean) {
        var frames = new ArrayList<byte[]>();
        Consumer<VideoFrame> collect = frame -> frames.add(HevcTestStreams.raw(frame.picture()));
        try (var decoder = new HevcDecoder(HevcTestStreams.track())) {
            for (var nal : hostile) {
                try {
                    decoder.decode(HevcTestStreams.packet(nal), DISCARD);
                } catch (MediaException expected) {
                    // Corrupt NAL units may be rejected; decoding continues with the next one.
                }
            }
            for (var nal : clean) {
                try {
                    decoder.decode(HevcTestStreams.packet(nal), collect);
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

    private static byte[] withStartCode(byte[] nal) {
        var out = new byte[nal.length + 4];
        out[3] = 1;
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }

    private static byte[] flip(Random random, byte[] data, int flips) {
        var copy = data.clone();
        for (var i = 0; i < flips && copy.length > 4; i++) {
            var position = 4 + random.nextInt(copy.length - 4);
            copy[position] ^= (byte) (1 << random.nextInt(8));
        }
        return copy;
    }

    private static List<byte[]> mutate(String mutation, Random random, List<byte[]> nals) {
        var result = new ArrayList<byte[]>();
        switch (mutation) {
            case "bit flips" -> {
                for (var nal : nals) {
                    result.add(random.nextInt(3) == 0 ? withStartCode(flip(random, nal, 1 + random.nextInt(6))) : withStartCode(nal));
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
                        var garbage = new byte[1 + random.nextInt(64)];
                        random.nextBytes(garbage);
                        garbage[0] = (byte) (garbage[0] & 0x7E);
                        result.add(withStartCode(garbage));
                    }
                }
            }
            default -> {
                for (var nal : nals) {
                    var type = HevcTestStreams.type(nal);
                    var parameterSet = type >= 32 && type <= 34;
                    result.add(withStartCode(parameterSet || random.nextInt(8) == 0
                            ? flip(random, nal, 1 + random.nextInt(4)) : nal));
                }
            }
        }
        return result;
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("mutations")
    @DisplayName("fails only with MediaException and recovers at the next IDR picture")
    void survives(Source source, String mutation) {
        var bytes = source.bytes();
        var nals = HevcTestStreams.nalUnits(bytes);
        var clean = HevcTestStreams.splitAnnexB(bytes);
        var reference = decodeHostile(List.of(), clean);
        assertTrue(reference.size() >= 12);
        assertTimeoutPreemptively(LIMIT, () -> {
            var random = new Random(source.name().hashCode() * 31L + mutation.hashCode());
            for (var trial = 0; trial < 40; trial++) {
                var frames = decodeHostile(mutate(mutation, random, nals), clean);
                assertTrue(frames.size() >= reference.size(), "trial " + trial + " lost frames of the clean stream");
                var offset = frames.size() - reference.size();
                for (var i = 0; i < reference.size(); i++) {
                    assertArrayEquals(reference.get(i), frames.get(offset + i), "trial " + trial + ": clean frame " + i);
                }
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("rangeExtensionVectors")
    @DisplayName("range extension coding tools fail only with MediaException")
    void rangeExtensionTools(HevcRextConformanceTest.Vector vector) throws Exception {
        var bytes = HevcRextConformanceTest.bitstream(vector);
        var nals = HevcTestStreams.nalUnits(bytes);
        assertTimeoutPreemptively(LIMIT, () -> {
            var random = new Random(vector.name().hashCode());
            for (var trial = 0; trial < 12; trial++) {
                var mutation = trial % 2 == 0 ? "bit flips" : "truncation";
                decodeHostile(mutate(mutation, random, nals), List.of());
            }
        });
    }

    static Stream<HevcRextConformanceTest.Vector> rangeExtensionVectors() {
        return HevcRextConformanceTest.vectors().stream().filter(vector -> vector.name().matches(
                "CCP_8bit_RExt_QCOM_1|PERSIST_RPARAM_A_RExt_Sony_3|EXTPREC_HIGHTHROUGHPUT_444_16_INTRA_8BIT_RExt_Sony_1"
                        + "|TSCTX_8bit_RExt_SHARP_1|ADJUST_IPRED_ANGLE_A_RExt_Mitsubishi_2|Bitdepth_A_RExt_Sony_1"
                        + "|GENERAL_16b_400_RExt_Sony_1|WPP_AND_TILE_AND_CABAC_BYPASS_ALIGN_1_HIGH_TP_444_14BIT_RExt_Apple_2"));
    }

    static Stream<Arguments> screenContentAndPlaneStreams() throws java.io.IOException {
        var conformance = HevcSccConformanceTest.vectors().stream()
                .map(vector -> Arguments.of(vector.name(), (Callable<byte[]>) () -> HevcSccConformanceTest.bitstream(vector)));
        var coded = Stream.concat(codedScreenContent(),
                        Stream.of("planes/planes-ld-8.bit", "planes/planes-ra-10.bit", "planes/planes-intra-8.bit"))
                .map(path -> Arguments.of(path, (Callable<byte[]>) () -> resource(path)));
        return Stream.concat(conformance, coded);
    }

    /**
     * Returns the paths of the HM-SCM streams of {@link HevcSccConformanceTest}, which all start with an IDR picture.
     */
    static Stream<String> codedScreenContent() throws java.io.IOException {
        return HevcSccConformanceTest.coded()
                .map(arguments -> "scc/" + ((HevcSccConformanceTest.Coded) arguments.get()[0]).name() + ".hevc")
                .distinct();
    }

    private static byte[] resource(String path) throws java.io.IOException {
        try (var input = HevcRobustnessTest.class.getResourceAsStream(path)) {
            return input.readAllBytes();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("codedScreenContent")
    @DisplayName("screen content coding streams recover at the next IDR picture after damage")
    void screenContentRecovery(String path) throws Exception {
        var bytes = resource(path);
        var nals = HevcTestStreams.nalUnits(bytes);
        var clean = HevcTestStreams.splitAnnexB(bytes);
        var reference = decodeHostile(List.of(), clean);
        assertTrue(!reference.isEmpty(), path + " decodes no frame");
        var mutations = List.of("bit flips", "truncation", "dropped and duplicated", "reordered", "garbage",
                "parameter sets");
        assertTimeoutPreemptively(LIMIT, () -> {
            var random = new Random(path.hashCode());
            for (var trial = 0; trial < 12; trial++) {
                var frames = decodeHostile(mutate(mutations.get(trial % mutations.size()), random, nals), clean);
                assertTrue(frames.size() >= reference.size(), "trial " + trial + " lost frames of the clean stream");
                var offset = frames.size() - reference.size();
                for (var i = 0; i < reference.size(); i++) {
                    assertArrayEquals(reference.get(i), frames.get(offset + i), "trial " + trial + ": clean frame " + i);
                }
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("screenContentAndPlaneStreams")
    @DisplayName("screen content coding tools and separate color planes fail only with MediaException")
    void screenContentAndPlanes(String name, Callable<byte[]> stream) throws Exception {
        var nals = HevcTestStreams.nalUnits(stream.call());
        var mutations = List.of("bit flips", "truncation", "dropped and duplicated", "reordered", "garbage",
                "parameter sets");
        assertTimeoutPreemptively(LIMIT, () -> {
            var random = new Random(name.hashCode());
            for (var trial = 0; trial < 18; trial++) {
                decodeHostile(mutate(mutations.get(trial % mutations.size()), random, nals), List.of());
            }
        });
    }

    @Test
    @DisplayName("rejects corrupt length prefixes and configuration records with MediaException only")
    void lengthPrefixes() {
        var bytes = sources().findFirst().orElseThrow().bytes();
        var nals = HevcTestStreams.nalUnits(bytes);
        var config = HevcTestStreams.hvcC(nals.stream().filter(nal -> HevcTestStreams.type(nal) >= 32 && HevcTestStreams.type(nal) <= 34).toList());
        var units = HevcTestStreams.accessUnits(nals);
        assertTimeoutPreemptively(LIMIT, () -> {
            var random = new Random(7);
            for (var trial = 0; trial < 200; trial++) {
                var corruptConfig = random.nextInt(4) == 0 ? flip(random, config, 1 + random.nextInt(3)) : config;
                try (var decoder = new HevcDecoder(HevcTestStreams.track(corruptConfig))) {
                    for (var unit : units) {
                        var sample = HevcTestStreams.lengthPrefixed(unit);
                        if (random.nextInt(3) == 0) {
                            sample = flip(random, sample, 1 + random.nextInt(4));
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
}
