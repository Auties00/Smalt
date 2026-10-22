package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
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
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feeds mutated streams (bit flips, truncation, corrupt length fields, dropped, duplicated and
 * reordered packets, garbage NAL units and corrupt parameter sets) to the decoder with one and
 * several threads. Every call must return or raise a {@link MediaException} within the time
 * limit; any other exception, a hang or an out-of-memory condition fails the test. After the
 * damage, the clean stream fed again from its first IDR picture must decode exactly.
 */
@DisplayName("H.264 decoder robustness")
class H264RobustnessTest {
    private static final Duration LIMIT = Duration.ofMinutes(2);

    record Source(String name, List<String> options, String resource) {
        Source(String name, List<String> options) {
            this(name, options, null);
        }

        @Override
        public String toString() {
            return name;
        }

        byte[] bytes() {
            if (resource != null) {
                if (resource.startsWith("chroma422/")) {
                    return H264Chroma422Test.resource(resource.substring("chroma422/".length()));
                }
                if (resource.startsWith("chroma444/")) {
                    return H264Chroma444Test.resource(resource.substring("chroma444/".length()));
                }
                if (resource.startsWith("extended/")) {
                    return H264ExtendedProfileTest.resource(resource.substring("extended/".length()));
                }
                return H264HighBitDepthTest.resource(resource);
            }
            var output = new ArrayList<String>(List.of("-frames:v", "16", "-c:v", "libx264"));
            output.addAll(options);
            output.addAll(List.of("-f", "h264"));
            return Fixtures.read(Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i", "testsrc2=size=128x96:rate=25"), output));
        }

        List<List<byte[]>> units() {
            return H264TestStreams.accessUnits(H264TestStreams.nalUnits(bytes()));
        }
    }

    static Stream<Source> sources() {
        return Stream.of(
                new Source("baseline cavlc slices", List.of("-profile:v", "baseline", "-x264-params", "keyint=8:slices=3")),
                new Source("high cabac bframes", List.of("-profile:v", "high", "-bf", "3", "-refs", "3", "-x264-params",
                        "keyint=8:weightp=2:b-pyramid=normal:direct=temporal:8x8dct=1")),
                new Source("main cabac spatial", List.of("-profile:v", "main", "-bf", "2", "-refs", "4", "-x264-params",
                        "keyint=8:direct=spatial")),
                new Source("high cabac mbaff bframes", List.of("-profile:v", "high", "-bf", "3", "-refs", "3", "-x264-params",
                        "keyint=8:interlaced=1:tff=1:direct=temporal:weightb=1:8x8dct=1")),
                new Source("main cavlc mbaff spatial", List.of("-profile:v", "main", "-bf", "2", "-x264-params",
                        "keyint=8:interlaced=1:bff=1:cabac=0:direct=spatial:slices=2")),
                new Source("high 10 cabac bframes", List.of("-pix_fmt", "yuv420p10le", "-bf", "3", "-refs", "3",
                        "-x264-params", "keyint=8:weightp=2:b-pyramid=normal:direct=temporal:8x8dct=1:qp=12")),
                new Source("high 10 cavlc mbaff slices", List.of("-pix_fmt", "yuv420p10le", "-bf", "2", "-x264-params",
                        "keyint=8:interlaced=1:tff=1:cabac=0:slices=3:qp=4")),
                new Source("high 4:2:2 cabac bframes", List.of("-pix_fmt", "yuv422p", "-bf", "3", "-refs", "3",
                        "-x264-params", "keyint=8:weightp=2:b-pyramid=normal:direct=temporal:8x8dct=1")),
                new Source("high 4:2:2 10 cavlc mbaff slices", List.of("-pix_fmt", "yuv422p10le", "-bf", "2",
                        "-x264-params", "keyint=8:interlaced=1:tff=1:cabac=0:slices=3:qp=4")),
                // JM streams holding I_PCM macroblocks of 12 and 14 bits in CABAC, CAVLC, CAVLC MBAFF and CABAC MBAFF
                // slices
                new Source("jm 8/12-bit cabac pcm", List.of(), "hd8-12-spatial"),
                new Source("jm 12-bit cabac weighted", List.of(), "hd12-cabac-wp"),
                new Source("jm 14-bit cavlc pcm", List.of(), "hd14-cavlc-implicit"),
                new Source("jm 14-bit cavlc mbaff pcm", List.of(), "hd14-mbaff-slices"),
                new Source("jm 14-bit cabac mbaff pcm", List.of(), "hd14-cabac-mbaff-pcm"),
                // JM 4:2:2 streams holding I_PCM macroblocks of 8, 10 and 14 bits in frame and field macroblocks
                new Source("jm 4:2:2 cabac mbaff pcm", List.of(), "chroma422/c8-cabac-mbaff-pcm"),
                new Source("jm 4:2:2 cavlc mbaff constrained intra", List.of(), "chroma422/c8-cavlc-mbaff-cip"),
                new Source("jm 4:2:2 10-bit cabac pcm", List.of(), "chroma422/c10-cabac-pcm"),
                new Source("jm 4:2:2 14-bit cavlc mbaff slices", List.of(), "chroma422/c14-cavlc-mbaff-slices"),
                new Source("high 4:4:4 cabac bframes", List.of("-pix_fmt", "yuv444p", "-bf", "3", "-refs", "3",
                        "-x264-params", "keyint=8:weightp=2:b-pyramid=normal:direct=temporal:8x8dct=1")),
                new Source("high 4:4:4 lossless cavlc mbaff", List.of("-pix_fmt", "yuv444p", "-bf", "2",
                        "-x264-params", "keyint=8:qp=0:interlaced=1:tff=1:cabac=0:slices=2")),
                new Source("high 4:4:4 lossless 4:2:0 cabac", List.of("-pix_fmt", "yuv420p", "-bf", "2",
                        "-x264-params", "keyint=8:qp=0:8x8dct=1")),
                // JM 4:4:4 streams with I_PCM macroblocks, separately coded color planes and lossless macroblocks
                new Source("jm 4:4:4 cabac pcm", List.of(), "chroma444/c8-cabac-pcm"),
                new Source("jm 4:4:4 10-bit cavlc pcm", List.of(), "chroma444/c10-cavlc-pcm"),
                new Source("jm separate planes 10-bit cabac mbaff pcm", List.of(), "chroma444/m10-cabac-mbaff-pcm-sep"),
                new Source("jm separate planes 14-bit cavlc fields", List.of(), "chroma444/m14-cavlc-fields-sep"),
                new Source("jm lossless 4:2:2 10-bit cabac", List.of(), "chroma444/l10-422-cabac"),
                new Source("jm lossless 4:4:4 cavlc pcm", List.of(), "chroma444/l8-444-pcm"),
                // JM Extended profile streams: data partitions, SP, switching SP and SI slices
                new Source("jm extended partitions slices", List.of(), "extended/dp-slices"),
                new Source("jm extended partitions pcm", List.of(), "extended/dp-pcm"),
                new Source("jm extended partitions mbaff", List.of(), "extended/dp-mbaff"),
                new Source("jm extended sp partitions", List.of(), "extended/sp-dp-chroma"),
                new Source("jm extended switching sp", List.of(), "extended/sp-switch"),
                new Source("hand-built extended si", List.of(), "extended/si-model")
        );
    }

    static Stream<Arguments> configurations() {
        return sources().flatMap(source -> Stream.of(Arguments.of(source, 1), Arguments.of(source, 3)));
    }

    private static final Consumer<VideoFrame> DISCARD = frame -> {
    };

    /** Decodes packets, tolerating {@link MediaException} only, then optionally the clean stream. */
    private static List<byte[]> decodeHostile(VideoTrack track, List<Packet> packets, List<Packet> clean, int threads) {
        var frames = new ArrayList<byte[]>();
        Consumer<VideoFrame> collect = frame -> frames.add(H264TestStreams.serialize((com.github.auties00.smalt.image.YuvPicture) frame.picture()));
        H264Decoder decoder;
        try {
            decoder = new H264Decoder(track, H264DecoderOptions.builder().threads(threads).build());
        } catch (MediaException expected) {
            return frames;
        }
        try (decoder) {
            for (var packet : packets) {
                try {
                    decoder.decode(packet, DISCARD);
                } catch (MediaException expected) {
                    // Corrupt packets may be rejected; decoding continues with the next one.
                }
            }
            for (var packet : clean) {
                try {
                    decoder.decode(packet, collect);
                } catch (MediaException expected) {
                    // Leftover corrupt state may fail a packet before the IDR resets it.
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

    private static List<Packet> annexB(List<List<byte[]>> units) {
        var payloads = new ArrayList<byte[]>();
        for (var unit : units) {
            payloads.add(H264TestStreams.annexB(unit));
        }
        return H264TestStreams.packets(payloads);
    }

    private static void runTrials(Source source, int threads, int trials, long seed,
                                  java.util.function.BiFunction<Random, List<List<byte[]>>, List<Packet>> mutation) {
        var units = source.units();
        var clean = annexB(units);
        var track = H264TestStreams.track(Optional.empty(), Optional.empty());
        var reference = H264TestStreams.decode(track, clean, 1);
        assertTimeoutPreemptively(LIMIT, () -> {
            var random = new Random(seed);
            for (var trial = 0; trial < trials; trial++) {
                var frames = decodeHostile(track, mutation.apply(random, units), clean, threads);
                assertTrue(frames.size() >= reference.size(), "trial " + trial + " lost frames of the clean stream");
                var offset = frames.size() - reference.size();
                for (var i = 0; i < reference.size(); i++) {
                    assertArrayEquals(reference.get(i).samples(), frames.get(offset + i),
                            "trial " + trial + ": clean frame " + i + " after recovery");
                }
            }
        });
    }

    private static byte[] flip(Random random, byte[] data, int flips) {
        var copy = data.clone();
        for (var i = 0; i < flips && copy.length > 0; i++) {
            var position = random.nextInt(copy.length);
            copy[position] ^= (byte) (1 << random.nextInt(8));
        }
        return copy;
    }

    @ParameterizedTest(name = "{0}, {1} threads")
    @MethodSource("configurations")
    @DisplayName("survives random bit flips in slices")
    void bitFlips(Source source, int threads) {
        runTrials(source, threads, 120, 1, (random, units) -> {
            var packets = new ArrayList<Packet>();
            for (var unit : units) {
                var mutated = new ArrayList<byte[]>();
                for (var nal : unit) {
                    mutated.add(random.nextInt(4) == 0 ? flip(random, nal, 1 + random.nextInt(8)) : nal);
                }
                packets.add(new Packet(1, H264TestStreams.annexB(mutated), Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false));
            }
            return packets;
        });
    }

    @ParameterizedTest(name = "{0}, {1} threads")
    @MethodSource("configurations")
    @DisplayName("survives corrupt parameter sets and NAL headers")
    void parameterSets(Source source, int threads) {
        runTrials(source, threads, 150, 2, (random, units) -> {
            var packets = new ArrayList<Packet>();
            for (var unit : units) {
                var mutated = new ArrayList<byte[]>();
                for (var nal : unit) {
                    var type = nal[0] & 0x1F;
                    if (type == 7 || type == 8 || type == 6) {
                        mutated.add(flip(random, nal, 1 + random.nextInt(3)));
                    } else if (random.nextInt(10) == 0) {
                        var copy = nal.clone();
                        copy[0] = (byte) random.nextInt(256);
                        mutated.add(copy);
                    } else {
                        mutated.add(nal);
                    }
                }
                packets.add(new Packet(1, H264TestStreams.annexB(mutated), Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false));
            }
            return packets;
        });
    }

    @ParameterizedTest(name = "{0}, {1} threads")
    @MethodSource("configurations")
    @DisplayName("survives truncated packets")
    void truncation(Source source, int threads) {
        runTrials(source, threads, 80, 3, (random, units) -> {
            var packets = new ArrayList<Packet>();
            for (var packet : annexB(units)) {
                var data = packet.data();
                var cut = random.nextInt(3) == 0 ? Arrays.copyOf(data, random.nextInt(data.length + 1)) : data;
                packets.add(new Packet(1, cut, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false));
            }
            return packets;
        });
    }

    @ParameterizedTest(name = "{0}, {1} threads")
    @MethodSource("configurations")
    @DisplayName("survives dropped, duplicated and reordered packets")
    void packetOrder(Source source, int threads) {
        runTrials(source, threads, 80, 4, (random, units) -> {
            var packets = new ArrayList<>(annexB(units));
            for (var i = 0; i < 4 && packets.size() > 1; i++) {
                switch (random.nextInt(3)) {
                    case 0 -> packets.remove(random.nextInt(packets.size()));
                    case 1 -> packets.add(random.nextInt(packets.size()), packets.get(random.nextInt(packets.size())));
                    default -> {
                        var a = random.nextInt(packets.size());
                        var b = random.nextInt(packets.size());
                        var swap = packets.get(a);
                        packets.set(a, packets.get(b));
                        packets.set(b, swap);
                    }
                }
            }
            return packets;
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    @DisplayName("decodes a repeated picture as a new picture with several threads, as on one thread")
    void repeatedPicture(Source source) {
        // Each picture is sent twice in a row: the copy repeats the slice headers of the picture before it, so only
        // the repeated first macroblock makes it a new picture, which the decoder must see whatever its threads.
        var packets = new ArrayList<Packet>();
        for (var packet : annexB(source.units())) {
            packets.add(packet);
            packets.add(packet);
        }
        var single = H264TestStreams.decode(H264TestStreams.track(Optional.empty(), Optional.empty()), packets, 1);
        var threaded = H264TestStreams.decode(H264TestStreams.track(Optional.empty(), Optional.empty()), packets, 3);
        assertEquals(single.size(), threaded.size(), "pictures decoded");
        for (var i = 0; i < single.size(); i++) {
            assertArrayEquals(single.get(i).samples(), threaded.get(i).samples(), "picture " + i);
        }
    }

    @ParameterizedTest(name = "{0}, {1} threads")
    @MethodSource("configurations")
    @DisplayName("survives garbage NAL units between valid ones")
    void garbage(Source source, int threads) {
        runTrials(source, threads, 80, 5, (random, units) -> {
            var packets = new ArrayList<Packet>();
            for (var unit : units) {
                var mutated = new ArrayList<byte[]>(unit);
                if (random.nextInt(3) == 0) {
                    var junk = new byte[1 + random.nextInt(400)];
                    random.nextBytes(junk);
                    junk[0] = (byte) ((random.nextInt(4) << 5) | (1 + random.nextInt(23)));
                    mutated.add(random.nextInt(mutated.size() + 1), junk);
                }
                packets.add(new Packet(1, H264TestStreams.annexB(mutated), Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false));
            }
            return packets;
        });
    }

    @ParameterizedTest(name = "{0}, {1} threads")
    @MethodSource("configurations")
    @DisplayName("survives corrupt length prefixes and configuration records")
    void lengthPrefixes(Source source, int threads) {
        var units = source.units();
        var nals = units.stream().flatMap(List::stream).toList();
        assertTimeoutPreemptively(LIMIT, () -> {
            var random = new Random(6);
            for (var trial = 0; trial < 100; trial++) {
                var record = H264TestStreams.avcC(nals, 4, true);
                if (random.nextInt(3) == 0) {
                    record = flip(random, record, 1 + random.nextInt(4));
                }
                var packets = new ArrayList<Packet>();
                for (var unit : units) {
                    var data = H264TestStreams.lengthPrefixed(unit, 4);
                    if (random.nextInt(4) == 0) {
                        var position = random.nextInt(Math.max(1, data.length - 4));
                        data[position] = (byte) random.nextInt(256);
                    }
                    packets.add(new Packet(1, data, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false));
                }
                decodeHostile(H264TestStreams.track(Optional.of(record), Optional.empty()), packets, List.of(), threads);
            }
        });
    }

    static Stream<H264ConformanceTest.Vector> conformanceVectors() {
        var names = List.of("FM1_FT_E", "FM2_SVA_C", "MR4_TANDBERG_C", "FRExt_MMCO4_Sony_B", "CAPM3_Sony_D", "HCBP2_HHI_A",
                "CVSEFDFT3_Sony_E", "MR6_BT_B", "FI1_Sony_E", "CAMASL3_Sony_B", "CVMAQP3_Sony_D", "MR9_BT_B",
                "CVMP_MOT_FLD_L30_B", "SP1_BT_A");
        return H264ConformanceTest.vectors().stream().filter(vector -> names.contains(vector.name()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conformanceVectors")
    @DisplayName("survives bit flips and dropped NAL units in conformance streams")
    void conformanceMutations(H264ConformanceTest.Vector vector) throws java.io.IOException {
        var nals = H264TestStreams.splitAnnexB(H264ConformanceTest.bitstream(vector));
        var track = H264TestStreams.track(Optional.empty(), Optional.empty());
        assertTimeoutPreemptively(LIMIT, () -> {
            var random = new Random(vector.name().hashCode());
            for (var trial = 0; trial < 12; trial++) {
                var packets = new ArrayList<Packet>();
                for (var nal : nals) {
                    var roll = random.nextInt(100);
                    if (roll < 2) {
                        continue;
                    }
                    var data = roll < 6 ? flip(random, nal, 1 + random.nextInt(6)) : nal;
                    packets.add(new Packet(1, data, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false));
                }
                decodeHostile(track, packets, List.of(), 1 + 2 * (trial & 1));
            }
        });
    }

    @Test
    @DisplayName("random bytes as configuration record or packets never escape as runtime errors")
    void randomInput() {
        assertTimeoutPreemptively(LIMIT, () -> {
            var random = new Random(7);
            for (var trial = 0; trial < 300; trial++) {
                var config = new byte[random.nextInt(64)];
                random.nextBytes(config);
                if (config.length > 0 && random.nextBoolean()) {
                    config[0] = 1;
                }
                var packets = new ArrayList<Packet>();
                for (var i = 0; i < 4; i++) {
                    var data = new byte[random.nextInt(2000)];
                    random.nextBytes(data);
                    if (data.length > 4 && random.nextBoolean()) {
                        data[0] = 0;
                        data[1] = 0;
                        data[2] = 1;
                    }
                    packets.add(new Packet(1, data, random.nextLong(), Packet.NO_TIMESTAMP, 0, false));
                }
                var track = H264TestStreams.track(config.length == 0 ? Optional.empty() : Optional.of(config), Optional.empty());
                decodeHostile(track, packets, List.of(), 1 + trial % 3);
            }
        });
    }

    @Test
    @DisplayName("probing mutated and random input raises only MediaException")
    void probe() {
        var nals = sources().toList().get(1).units().stream().flatMap(List::stream).toList();
        var sps = nals.stream().filter(nal -> (nal[0] & 0x1F) == 7).findFirst().orElseThrow();
        var record = H264TestStreams.avcC(nals, 4, true);
        assertTimeoutPreemptively(LIMIT, () -> {
            var random = new Random(8);
            for (var trial = 0; trial < 3000; trial++) {
                byte[] input = switch (trial % 4) {
                    case 0 -> flip(random, sps, 1 + random.nextInt(4));
                    case 1 -> flip(random, record, 1 + random.nextInt(4));
                    case 2 -> Arrays.copyOf(record, random.nextInt(record.length + 1));
                    default -> {
                        var bytes = new byte[random.nextInt(64)];
                        random.nextBytes(bytes);
                        yield bytes;
                    }
                };
                try {
                    H264StreamInfo.probe(input);
                } catch (MediaException expected) {
                    // Corrupt parameter sets are reported as media errors.
                }
            }
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sources")
    @DisplayName("repeated access units and slices decode with frame threads as on the calling thread")
    void repeatedUnits(Source source) {
        var units = source.units();
        // the first access unit twice in a row (an IDR whose header repeats the previous picture's), then the
        // stream with the second slice of every picture that has several repeated after it
        var mutated = new ArrayList<List<byte[]>>();
        mutated.add(units.getFirst());
        for (var unit : units) {
            var copy = new ArrayList<byte[]>(unit);
            var slices = new ArrayList<Integer>();
            for (var i = 0; i < copy.size(); i++) {
                var type = copy.get(i)[0] & 0x1F;
                if (type == 1 || type == 5) {
                    slices.add(i);
                }
            }
            if (slices.size() > 1) {
                copy.add(slices.get(1) + 1, copy.get(slices.get(1)));
            }
            mutated.add(copy);
        }
        var packets = annexB(mutated);
        var track = H264TestStreams.track(Optional.empty(), Optional.empty());
        var expected = H264TestStreams.decode(track, packets, 1);
        assertTrue(expected.size() > units.size(), "the repeated IDR is a picture of its own");
        for (var threads : new int[]{2, 4}) {
            var actual = H264TestStreams.decode(track, packets, threads);
            assertEquals(expected.size(), actual.size(), "frames with " + threads + " threads");
            for (var i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i).samples(), actual.get(i).samples(),
                        "frame " + i + " with " + threads + " threads");
            }
        }
    }

    @Test
    @DisplayName("slices without parameter sets are rejected as malformed")
    void missingParameterSets() {
        var units = sources().findFirst().orElseThrow().units();
        var slices = units.stream().flatMap(List::stream).filter(nal -> (nal[0] & 0x1F) == 5 || (nal[0] & 0x1F) == 1).toList();
        var track = H264TestStreams.track(Optional.empty(), Optional.empty());
        try (var decoder = new H264Decoder(track)) {
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(new Packet(1, H264TestStreams.annexB(List.of(slices.getFirst())),
                    0, Packet.NO_TIMESTAMP, 0, true), DISCARD));
            assertDoesNotThrow(() -> decoder.flush(DISCARD));
        }
    }
}
