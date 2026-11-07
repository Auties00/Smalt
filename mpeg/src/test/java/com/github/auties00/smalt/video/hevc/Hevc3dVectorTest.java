package com.github.auties00.smalt.video.hevc;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * Decodes the short 3D-HEVC streams the HTM reference encoder coded from a synthetic scene
 * ({@code tools/testdata/hevc-3d/make-vectors.py}; {@code threeD/vectors.tsv} pins each stream and the
 * per-picture MD5s of HTM's output, whose decoded picture hashes HTM verified): two texture views with their
 * depth views, the dependent views coded with inter-view motion prediction (with 8x8 and 16x16 sub-block
 * partitions, and in the dependent depth view), motion vector scaling, advanced residual prediction and
 * illumination compensation. Every picture of every layer must match HTM on one and several threads, and
 * damaged copies of the streams (bit flips, truncated or dropped NAL units, damaged parameter sets with their 3D
 * extensions) decoded on every layer must fail only with {@link MediaException}.
 */
@DisplayName("HEVC 3D committed vectors (HTM-coded texture and depth views)")
class Hevc3dVectorTest {
    record Vector(String name, String sha256, Map<Integer, List<String>> pictures) {
        @Override
        public String toString() {
            return name;
        }
    }

    static byte[] resource(String name) {
        try (var input = Hevc3dVectorTest.class.getResourceAsStream("threeD/" + name)) {
            if (input == null) {
                throw new IllegalStateException("missing vector " + name);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static Stream<Vector> vectors() {
        var vectors = new TreeMap<String, Vector>();
        for (var line : new String(resource("vectors.tsv"), StandardCharsets.US_ASCII).split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            var columns = line.strip().split("\t");
            var vector = vectors.computeIfAbsent(columns[0], name -> new Vector(name, columns[5], new TreeMap<>()));
            var md5s = List.of(columns[6].split(","));
            assertEquals(Integer.parseInt(columns[4]), md5s.size(), columns[0] + " frame count");
            vector.pictures().put(Integer.parseInt(columns[1]), md5s);
        }
        return vectors.values().stream();
    }

    static Stream<Arguments> threads() {
        return vectors().flatMap(vector -> Stream.of(Arguments.of(vector, 1), Arguments.of(vector, 4)));
    }

    @ParameterizedTest(name = "{0} on {1} threads")
    @MethodSource("threads")
    @DisplayName("every picture of every layer matches the reference decoder")
    void pictures(Vector vector, int threads) throws NoSuchAlgorithmException {
        var stream = resource(vector.name() + ".hevc");
        assertEquals(vector.sha256(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(stream)),
                vector + " stream");
        var vps = HevcMultilayerConformanceTest.vps(stream);
        var sets = Hevc3dConformanceTest.outputLayerSets(vps, vector.pictures().keySet());
        assertEquals(vector.pictures().keySet(), sets.keySet(), "an output layer set outputs every layer");
        var decoded = new TreeMap<Integer, List<String>>();
        for (var ols : new java.util.TreeSet<>(sets.values())) {
            var output = Hevc3dConformanceTest.decodePictures(stream, ols, threads, null);
            for (var entry : output.entrySet()) {
                if (sets.get(entry.getKey()) == ols) {
                    decoded.put(entry.getKey(), entry.getValue());
                }
            }
        }
        assertEquals(vector.pictures(), decoded, vector + " pictures");
    }

    static Stream<Arguments> mutations() {
        return vectors().flatMap(vector -> Stream.of("bit flips", "truncation", "parameter sets", "dropped")
                .map(mutation -> Arguments.of(vector, mutation)));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("mutations")
    @DisplayName("damaged streams decoded on every layer fail only with MediaException")
    void damaged(Vector vector, String mutation) {
        var stream = resource(vector.name() + ".hevc");
        var nals = HevcTestStreams.nalUnits(stream);
        var vps = HevcMultilayerConformanceTest.vps(stream);
        var options = HevcDecoderOptions.builder().highBitDepth(true).alpha(false)
                .outputLayerSet(vps.numOutputLayerSets() - 1);
        assertTimeoutPreemptively(Duration.ofMinutes(3), () -> {
            var random = new Random(vector.name().hashCode() * 31L + mutation.hashCode());
            for (var trial = 0; trial < 16; trial++) {
                var threads = trial % 2 == 0 ? 1 : 4;
                try (var decoder = new HevcDecoder(HevcTestStreams.track(), options.threads(threads).build())) {
                    for (var nal : mutate(mutation, random, nals)) {
                        try {
                            decoder.decode(HevcTestStreams.packet(nal), frame -> {
                            });
                        } catch (MediaException expected) {
                            // a damaged NAL unit may be rejected; decoding continues with the next one
                        }
                    }
                    try {
                        decoder.flush(frame -> {
                        });
                    } catch (MediaException expected) {
                        // a picture still in progress may be reported damaged
                    }
                }
            }
        });
    }

    /**
     * Returns a damaged copy of the NAL units of a stream, each with a start code.
     */
    static List<byte[]> mutate(String mutation, Random random, List<byte[]> nals) {
        var result = new ArrayList<byte[]>();
        for (var nal : nals) {
            var type = HevcTestStreams.type(nal);
            var parameterSet = type >= 32 && type <= 34;
            var copy = nal;
            switch (mutation) {
                case "bit flips" -> {
                    if (random.nextInt(3) == 0) {
                        copy = flip(random, nal, 1 + random.nextInt(6));
                    }
                }
                case "truncation" -> {
                    if (random.nextInt(3) == 0 && nal.length > 3) {
                        copy = Arrays.copyOf(nal, 2 + random.nextInt(nal.length - 2));
                    }
                }
                case "parameter sets" -> {
                    if (parameterSet) {
                        copy = random.nextBoolean() ? flip(random, nal, 1 + random.nextInt(3))
                                : Arrays.copyOf(nal, 2 + random.nextInt(nal.length - 2));
                    }
                }
                default -> {
                    if (!parameterSet && random.nextInt(4) == 0) {
                        continue;
                    }
                }
            }
            var out = new byte[copy.length + 4];
            out[3] = 1;
            System.arraycopy(copy, 0, out, 4, copy.length);
            result.add(out);
        }
        return result;
    }

    private static byte[] flip(Random random, byte[] data, int flips) {
        var copy = data.clone();
        for (var i = 0; i < flips && copy.length > 2; i++) {
            var position = 2 + random.nextInt(copy.length - 2);
            copy[position] ^= (byte) (1 << random.nextInt(8));
        }
        return copy;
    }
}
