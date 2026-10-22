package com.github.auties00.smalt.video.h264;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.VideoFrame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the Extended profile tools: slice data partitioning (partitions A, B and C reassembled by
 * {@code slice_id} and {@code redundant_pic_cnt}), SP slices with and without
 * {@code sp_for_switch_flag}, and SI slices with SI macroblocks. The streams under {@code extended/},
 * made by the JM reference software or by hand (see {@code extended/streams.tsv} for how each was
 * made and where the expected hashes in {@code extended/frames.tsv} come from), must decode
 * bit-exactly however their
 * NAL units are packetized, interleaved or decoded in parallel; hand-built streams check the
 * handling of lost, corrupt, repeated and redundant partitions. The SP conformance streams with
 * their reference YUV are checked by {@link H264ConformanceTest}.
 */
@DisplayName("H.264 Extended profile")
class H264ExtendedProfileTest {
    private static final Map<String, List<String>> GOLDEN = loadGolden();

    private static final Consumer<VideoFrame> NONE = frame -> {
    };

    private static Map<String, List<String>> loadGolden() {
        try (var input = H264ExtendedProfileTest.class.getResourceAsStream("extended/frames.tsv")) {
            assertNotNull(input, "extended/frames.tsv");
            var result = new LinkedHashMap<String, List<String>>();
            for (var line : new String(input.readAllBytes(), StandardCharsets.US_ASCII).split("\n")) {
                var parts = line.strip().split("\t");
                if (parts.length != 3) {
                    continue;
                }
                var frames = result.computeIfAbsent(parts[0], key -> new ArrayList<>());
                assertEquals(frames.size(), Integer.parseInt(parts[1]), "frame index in " + parts[0]);
                frames.add(parts[2]);
            }
            return result;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static Stream<String> streams() {
        return GOLDEN.keySet().stream();
    }

    /** The streams without B pictures, whose frames a low-delay decoder outputs as they are decoded. */
    static Stream<String> lowDelayStreams() {
        return Stream.of("dp-cip", "sp", "sp-qp", "sp-mbaff-dp", "sp-coefficients", "sp-switch", "si", "si-model",
                "si-model-cip");
    }

    static byte[] resource(String name) {
        try (var input = H264ExtendedProfileTest.class.getResourceAsStream("extended/" + name + ".264")) {
            assertNotNull(input, name);
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static List<List<byte[]>> units(String name) {
        return H264TestStreams.accessUnits(H264TestStreams.nalUnits(resource(name)));
    }

    static String md5(byte[] samples) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(samples));
        } catch (NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    static Packet packet(byte[] data) {
        return new Packet(1, data, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, false);
    }

    /** Decodes packets and returns the hash of every frame. */
    static List<String> hashes(List<Packet> packets, H264DecoderOptions options) {
        var result = new ArrayList<String>();
        Consumer<VideoFrame> sink = frame -> result.add(md5(H264TestStreams.serialize((YuvPicture) frame.picture())));
        try (var decoder = new H264Decoder(H264ConformanceTest.track(), options)) {
            for (var packet : packets) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
        }
        return result;
    }

    /** A frame's hash and integrity as a {@link H264Decoder.FrameConsumer} reports them. */
    record Reported(String hash, boolean damaged) {
    }

    /** Decodes packets reporting integrity and returns every frame. */
    static List<Reported> reported(List<Packet> packets, boolean[] keyframeNeeded) {
        var result = new ArrayList<Reported>();
        H264Decoder.FrameConsumer sink = (frame, damaged) -> result.add(
                new Reported(md5(H264TestStreams.serialize((YuvPicture) frame.picture())), damaged));
        try (var decoder = new H264Decoder(H264ConformanceTest.track(), H264DecoderOptions.builder().threads(1).build())) {
            for (var packet : packets) {
                decoder.decode(packet, sink);
            }
            decoder.flush(sink);
            if (keyframeNeeded != null) {
                keyframeNeeded[0] = decoder.keyframeNeeded();
            }
        }
        return result;
    }

    static List<Packet> annexBUnits(List<List<byte[]>> units) {
        var result = new ArrayList<Packet>();
        for (var unit : units) {
            result.add(packet(H264TestStreams.annexB(unit)));
        }
        return result;
    }

    static int type(byte[] nal) {
        return nal[0] & 0x1F;
    }

    static void assertGolden(String name, List<String> actual) {
        var expected = GOLDEN.get(name);
        assertEquals(expected.size(), actual.size(), "frame count of " + name);
        for (var i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i), name + ": frame " + i);
        }
    }

    @Nested
    @DisplayName("test streams")
    class TestStreams {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264ExtendedProfileTest#streams")
        @DisplayName("decode bit-exactly, one access unit per packet")
        void bitExact(String name) {
            assertGolden(name, hashes(annexBUnits(units(name)), H264DecoderOptions.builder().threads(1).build()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264ExtendedProfileTest#streams")
        @DisplayName("decode bit-exactly with four frame threads")
        void threaded(String name) {
            assertGolden(name, hashes(annexBUnits(units(name)), H264DecoderOptions.builder().threads(4).build()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264ExtendedProfileTest#streams")
        @DisplayName("decode bit-exactly with every NAL unit in a packet of its own")
        void perNal(String name) {
            var packets = new ArrayList<Packet>();
            for (var nal : H264TestStreams.nalUnits(resource(name))) {
                packets.add(packet(H264TestStreams.annexB(List.of(nal))));
            }
            assertGolden(name, hashes(packets, H264DecoderOptions.builder().threads(1).build()));
            assertGolden(name, hashes(packets, H264DecoderOptions.builder().threads(3).build()));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264ExtendedProfileTest#streams")
        @DisplayName("decode bit-exactly from length-prefixed samples with an avcC record")
        void lengthPrefixed(String name) {
            var units = units(name);
            var all = new ArrayList<byte[]>();
            units.forEach(all::addAll);
            var config = H264TestStreams.avcC(all, 4, true);
            var packets = new ArrayList<Packet>();
            for (var unit : units) {
                var vcl = unit.stream().filter(nal -> type(nal) != 7 && type(nal) != 8).toList();
                if (!vcl.isEmpty()) {
                    packets.add(packet(H264TestStreams.lengthPrefixed(vcl, 4)));
                }
            }
            var result = new ArrayList<String>();
            Consumer<VideoFrame> sink = frame -> result.add(md5(H264TestStreams.serialize((YuvPicture) frame.picture())));
            try (var decoder = new H264Decoder(H264TestStreams.track(Optional.of(config), Optional.empty()),
                    H264DecoderOptions.builder().threads(2).build())) {
                for (var packet : packets) {
                    decoder.decode(packet, sink);
                }
                decoder.flush(sink);
            }
            assertGolden(name, result);
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264ExtendedProfileTest#lowDelayStreams")
        @DisplayName("decode bit-exactly and intact in low-delay mode, every picture finished with its packet")
        void lowDelay(String name) {
            // without bitstream_restriction an Extended sequence may reorder, so output waits for the
            // reordering limit, while every picture is still finished before its decode call returns
            var expected = GOLDEN.get(name);
            var frames = new ArrayList<Reported>();
            H264Decoder.FrameConsumer sink = (frame, damaged) -> frames.add(
                    new Reported(md5(H264TestStreams.serialize((YuvPicture) frame.picture())), damaged));
            try (var decoder = new H264Decoder(H264ConformanceTest.track(), H264DecoderOptions.forRealtime())) {
                for (var unit : units(name)) {
                    decoder.decode(packet(H264TestStreams.annexB(unit)), sink);
                }
                decoder.flush(sink);
            }
            assertEquals(expected, frames.stream().map(Reported::hash).toList(), name);
            assertTrue(frames.stream().noneMatch(Reported::damaged), name + ": every frame intact");
        }
    }

    @Nested
    @DisplayName("slice data partitions")
    class Partitions {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"dp-slices", "dp-field", "sp-dp-chroma"})
        @DisplayName("may interleave across the slices of a picture: every A, then every B, then every C")
        void interleaved(String name) {
            var reordered = new ArrayList<List<byte[]>>();
            for (var unit : units(name)) {
                var order = new ArrayList<byte[]>();
                for (var wanted : new int[]{-1, 2, 3, 4}) {
                    for (var nal : unit) {
                        var t = type(nal);
                        if (wanted == -1 ? t < 2 || t > 4 : t == wanted) {
                            order.add(nal);
                        }
                    }
                }
                reordered.add(order);
            }
            assertGolden(name, hashes(annexBUnits(reordered), H264DecoderOptions.builder().threads(1).build()));
            assertGolden(name, hashes(annexBUnits(reordered), H264DecoderOptions.builder().threads(4).build()));
        }

        @Test
        @DisplayName("are ignored when repeated")
        void repeated() {
            var doubled = new ArrayList<List<byte[]>>();
            for (var unit : units("dp-slices")) {
                var copy = new ArrayList<byte[]>();
                for (var nal : unit) {
                    copy.add(nal);
                    if (type(nal) == 3 || type(nal) == 4) {
                        copy.add(nal);
                    }
                }
                doubled.add(copy);
            }
            var frames = reported(annexBUnits(doubled), null);
            assertEquals(GOLDEN.get("dp-slices"), frames.stream().map(Reported::hash).toList());
            assertTrue(frames.stream().noneMatch(Reported::damaged));
        }

        /** Returns the index of the first access unit after the first holding partitions of the given type. */
        private static int unitWith(List<List<byte[]>> units, int partition) {
            for (var i = 1; i < units.size(); i++) {
                if (units.get(i).stream().anyMatch(nal -> type(nal) == partition)) {
                    return i;
                }
            }
            throw new AssertionError("no access unit with partition " + partition);
        }

        /**
         * Decodes a stream whose access unit {@code lost} lost NAL units and checks that the frames
         * decoded before it are intact and exact, the frame of that unit is damaged and that a key
         * frame is needed.
         */
        private void assertDamagedFrom(List<List<byte[]>> units, int lost) {
            var keyframeNeeded = new boolean[1];
            var frames = reported(annexBUnits(units), keyframeNeeded);
            var expected = GOLDEN.get("dp-cip");
            assertEquals(expected.size(), frames.size(), "frame count");
            for (var i = 0; i < lost; i++) {
                assertEquals(expected.get(i), frames.get(i).hash(), "frame " + i + " before the loss");
                assertFalse(frames.get(i).damaged(), "frame " + i + " before the loss");
            }
            assertTrue(frames.get(lost).damaged(), "the frame that lost data");
            assertTrue(keyframeNeeded[0]);
        }

        @Test
        @DisplayName("lost C partitions leave their macroblocks without residual and damage the picture")
        void lostC() {
            var units = new ArrayList<>(units("dp-cip"));
            var lost = unitWith(units, 4);
            units.set(lost, units.get(lost).stream().filter(nal -> type(nal) != 4).toList());
            assertDamagedFrom(units, lost);
        }

        @Test
        @DisplayName("lost B partitions leave their macroblocks without residual and damage the picture")
        void lostB() {
            var units = new ArrayList<>(units("dp-cip"));
            var lost = unitWith(units, 3);
            units.set(lost, units.get(lost).stream().filter(nal -> type(nal) != 3).toList());
            assertDamagedFrom(units, lost);
        }

        @Test
        @DisplayName("partitions B and C whose partition A was lost damage the picture")
        void lostA() {
            var units = new ArrayList<>(units("dp-cip"));
            var lost = unitWith(units, 3);
            var unit = new ArrayList<>(units.get(lost));
            var firstA = 0;
            while (type(unit.get(firstA)) != 2 || unit.stream().noneMatch(nal -> type(nal) == 3)) {
                firstA++;
            }
            unit.remove(firstA);
            units.set(lost, unit);
            assertDamagedFrom(units, lost);
        }

        @Test
        @DisplayName("a truncated partition C fails its packet and damages the picture")
        void truncatedC() {
            var units = new ArrayList<>(units("dp-cip"));
            var lost = unitWith(units, 4);
            var unit = new ArrayList<byte[]>();
            var cut = false;
            for (var nal : units.get(lost)) {
                if (type(nal) == 4 && !cut && nal.length > 8) {
                    unit.add(Arrays.copyOf(nal, nal.length / 2));
                    cut = true;
                } else {
                    unit.add(nal);
                }
            }
            units.set(lost, unit);
            assertDamagedFrom(units, lost);
            var packets = annexBUnits(units);
            try (var decoder = new H264Decoder(H264ConformanceTest.track(), H264DecoderOptions.builder().threads(1).build())) {
                for (var i = 0; i < packets.size(); i++) {
                    if (i == lost) {
                        var packet = packets.get(i);
                        assertThrows(MediaException.Malformed.class, () -> decoder.decode(packet, NONE));
                    } else {
                        decoder.decode(packets.get(i), NONE);
                    }
                }
            }
        }

        @Test
        @DisplayName("with CABAC are rejected as Unsupported")
        void cabac() {
            var sps = H264BitWriter.nal(3, 7, new H264BitWriter().bits(88, 8).bits(0, 8).bits(30, 8).ue(0).ue(0).ue(2)
                    .ue(1).bit(0).ue(1).ue(1).bit(1).bit(1).bit(0).bit(0).trailing());
            var pps = H264BitWriter.nal(3, 8, new H264BitWriter().ue(0).ue(0).bit(1).bit(0).ue(0).ue(0).ue(0).bit(0)
                    .bits(0, 2).se(0).se(0).se(0).bit(1).bit(0).bit(0).trailing());
            var partition = H264BitWriter.nal(2, 2, new H264BitWriter().ue(0).ue(0).ue(0).trailing());
            var failure = assertThrows(MediaException.Unsupported.class,
                    () -> hashes(List.of(packet(H264TestStreams.annexB(List.of(sps, pps, partition)))),
                            H264DecoderOptions.builder().threads(1).build()));
            assertEquals("h264 cabac data partitioning", failure.format());
        }
    }

    /**
     * A 32x32 Extended profile stream built by hand: an IDR picture of four I_PCM macroblocks, then
     * a P picture coded as partitions whose second and third macroblocks are I_PCM, with samples
     * in partition B, and whose first and last are P_Skip. The picture parameter set signals
     * {@code redundant_pic_cnt}, so redundant copies of the partitions can follow.
     */
    @Nested
    @DisplayName("hand-built partitioned P picture with I_PCM samples in partition B")
    class Redundant {
        private static final int SIZE = 32;

        private static byte[] sps() {
            return H264BitWriter.nal(3, 7, new H264BitWriter().bits(88, 8).bits(0, 8).bits(30, 8).ue(0).ue(0).ue(2)
                    .ue(1).bit(0).ue(1).ue(1).bit(1).bit(1).bit(0).bit(0).trailing());
        }

        private static byte[] pps() {
            return H264BitWriter.nal(3, 8, new H264BitWriter().ue(0).ue(0).bit(0).bit(0).ue(0).ue(0).ue(0).bit(0)
                    .bits(0, 2).se(0).se(0).se(0).bit(1).bit(0).bit(1).trailing());
        }

        /** The samples of one I_PCM macroblock: 256 luma then 64 Cb and 64 Cr, a pattern of the seed. */
        private static byte[] samples(int seed) {
            var result = new byte[384];
            for (var i = 0; i < 256; i++) {
                result[i] = (byte) (16 + (seed * 37 + (i % 16) * 3 + (i / 16) * 5) % 200);
            }
            for (var i = 0; i < 128; i++) {
                result[256 + i] = (byte) (32 + (seed * 53 + i * 7) % 180);
            }
            return result;
        }

        private static byte[] idr() {
            var writer = new H264BitWriter().ue(0).ue(7).ue(0).bits(0, 4).ue(0).ue(0).bit(0).bit(0).se(0).ue(1);
            for (var mb = 0; mb < 4; mb++) {
                writer.ue(25);
                while (!writer.aligned()) {
                    writer.bit(0);
                }
                for (var b : samples(mb)) {
                    writer.bits(b & 0xFF, 8);
                }
            }
            return H264BitWriter.nal(3, 5, writer.trailing());
        }

        /** Partition A of the P picture: P_Skip, I_PCM, I_PCM, P_Skip. */
        private static byte[] partitionA(int redundant) {
            var writer = new H264BitWriter().ue(0).ue(5).ue(0).bits(1, 4).ue(redundant).bit(0).bit(0).bit(0).se(0)
                    .ue(1).ue(0);
            writer.ue(1).ue(30).ue(0).ue(30).ue(1);
            return H264BitWriter.nal(2, 2, writer.trailing());
        }

        /** Partition B of the P picture holding the samples of its two I_PCM macroblocks. */
        private static byte[] partitionB(int redundant, int seed, int keep) {
            var writer = new H264BitWriter().ue(0).ue(redundant);
            while (!writer.aligned()) {
                writer.bit(0);
            }
            var data = new byte[768];
            System.arraycopy(samples(seed), 0, data, 0, 384);
            System.arraycopy(samples(seed + 1), 0, data, 384, 384);
            for (var i = 0; i < keep; i++) {
                writer.bits(data[i] & 0xFF, 8);
            }
            return H264BitWriter.nal(2, 3, keep == data.length ? writer.trailing() : writer.bytes());
        }

        /** Returns a 32x32 frame as planar 4:2:0 with macroblock {@code mb} holding I_PCM samples. */
        private static void put(byte[] frame, int mb, byte[] samples) {
            var mx = (mb % 2) * 16;
            var my = (mb / 2) * 16;
            for (var y = 0; y < 16; y++) {
                System.arraycopy(samples, y * 16, frame, (my + y) * SIZE + mx, 16);
            }
            for (var c = 0; c < 2; c++) {
                var base = SIZE * SIZE + c * 256;
                for (var y = 0; y < 8; y++) {
                    System.arraycopy(samples, 256 + c * 64 + y * 8, frame, base + (my / 2 + y) * 16 + mx / 2, 8);
                }
            }
        }

        private static byte[] gray() {
            var samples = new byte[384];
            Arrays.fill(samples, (byte) 128);
            return samples;
        }

        private static byte[] expectedIdr() {
            var frame = new byte[SIZE * SIZE * 3 / 2];
            for (var mb = 0; mb < 4; mb++) {
                put(frame, mb, samples(mb));
            }
            return frame;
        }

        private static byte[] expectedP(byte[] second, byte[] third) {
            var frame = expectedIdr();
            put(frame, 1, second);
            put(frame, 2, third);
            return frame;
        }

        /** Decodes the IDR access unit then the given P access unit, reporting integrity. */
        private static List<byte[]> decode(List<byte[]> pUnit, List<Boolean> damage, boolean[] failed) {
            var frames = new ArrayList<byte[]>();
            H264Decoder.FrameConsumer sink = (frame, damaged) -> {
                frames.add(H264TestStreams.serialize((YuvPicture) frame.picture()));
                damage.add(damaged);
            };
            try (var decoder = new H264Decoder(H264ConformanceTest.track(), H264DecoderOptions.builder().threads(1).build())) {
                decoder.decode(packet(H264TestStreams.annexB(List.of(sps(), pps(), idr()))), sink);
                decoder.decode(packet(H264TestStreams.annexB(pUnit)), sink);
                decoder.flush(sink);
            }
            if (failed != null) {
                failed[0] = false;
                try (var decoder = new H264Decoder(H264ConformanceTest.track(), H264DecoderOptions.builder().threads(1).build())) {
                    decoder.decode(packet(H264TestStreams.annexB(List.of(sps(), pps(), idr()))), NONE);
                    decoder.decode(packet(H264TestStreams.annexB(pUnit)), NONE);
                    decoder.flush(NONE);
                } catch (MediaException.Malformed expected) {
                    failed[0] = true;
                }
            }
            return frames;
        }

        @Test
        @DisplayName("reads I_PCM samples from partition B")
        void primary() {
            var damage = new ArrayList<Boolean>();
            var frames = decode(List.of(partitionA(0), partitionB(0, 10, 768)), damage, null);
            assertEquals(2, frames.size());
            assertArrayEquals(expectedIdr(), frames.get(0));
            assertArrayEquals(expectedP(samples(10), samples(11)), frames.get(1));
            assertEquals(List.of(false, false), damage);
        }

        @ParameterizedTest(name = "threads {0}")
        @ValueSource(ints = {1, 3})
        @DisplayName("drops the partitions of a redundant coded picture")
        void redundant(int threads) {
            var unit = List.of(partitionA(0), partitionB(0, 10, 768), partitionA(1), partitionB(1, 90, 768));
            var frames = new ArrayList<byte[]>();
            try (var decoder = new H264Decoder(H264ConformanceTest.track(), H264DecoderOptions.builder().threads(threads).build())) {
                Consumer<VideoFrame> sink = frame -> frames.add(H264TestStreams.serialize((YuvPicture) frame.picture()));
                decoder.decode(packet(H264TestStreams.annexB(List.of(sps(), pps(), idr()))), sink);
                decoder.decode(packet(H264TestStreams.annexB(unit)), sink);
                decoder.flush(sink);
            }
            assertEquals(2, frames.size());
            assertArrayEquals(expectedP(samples(10), samples(11)), frames.get(1));
        }

        @Test
        @DisplayName("does not take partition B from a redundant picture when the primary one is lost")
        void redundantOnly() {
            var damage = new ArrayList<Boolean>();
            var frames = decode(List.of(partitionA(0), partitionA(1), partitionB(1, 90, 768)), damage, null);
            assertEquals(2, frames.size());
            assertArrayEquals(expectedP(gray(), gray()), frames.get(1));
            assertEquals(List.of(false, true), damage);
        }

        @Test
        @DisplayName("damages the picture for a partition B naming another slice")
        void foreignSlice() {
            var damage = new ArrayList<Boolean>();
            var other = H264BitWriter.nal(2, 3, new H264BitWriter().ue(1).ue(0).trailing());
            var frames = decode(List.of(partitionA(0), partitionB(0, 10, 768), other), damage, null);
            assertArrayEquals(expectedP(samples(10), samples(11)), frames.get(1));
            assertEquals(List.of(false, true), damage);
        }

        @Test
        @DisplayName("keeps the samples a truncated partition B holds and fills the rest with gray")
        void truncated() {
            var damage = new ArrayList<Boolean>();
            var failed = new boolean[1];
            var frames = decode(List.of(partitionA(0), partitionB(0, 10, 384 + 100)), damage, failed);
            assertArrayEquals(expectedP(samples(10), gray()), frames.get(1));
            assertEquals(List.of(false, true), damage);
            assertTrue(failed[0], "the packet fails with Malformed");
        }

        @Test
        @DisplayName("decodes the same picture when partition B arrives in a later packet")
        void separatePackets() {
            var frames = new ArrayList<byte[]>();
            try (var decoder = new H264Decoder(H264ConformanceTest.track(), H264DecoderOptions.builder().threads(1).build())) {
                Consumer<VideoFrame> sink = frame -> frames.add(H264TestStreams.serialize((YuvPicture) frame.picture()));
                decoder.decode(packet(H264TestStreams.annexB(List.of(sps(), pps(), idr()))), sink);
                decoder.decode(packet(H264TestStreams.annexB(List.of(partitionA(0)))), sink);
                decoder.decode(packet(H264TestStreams.annexB(List.of(partitionB(0, 10, 768)))), sink);
                decoder.flush(sink);
            }
            assertArrayEquals(expectedP(samples(10), samples(11)), frames.get(1));
            assertNotEquals(md5(expectedIdr()), md5(frames.get(1)));
        }
    }

    static Stream<H264ConformanceTest.Vector> spConformance() {
        return H264ConformanceTest.vectors().stream()
                .filter(vector -> vector.name().equals("SP1_BT_A") || vector.name().equals("sp2_bt_b"));
    }

    @Nested
    @DisplayName("SP and SI slices")
    class Switching {
        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.video.h264.H264ExtendedProfileTest#spConformance")
        @DisplayName("of the SP conformance streams decode bit-exactly against their reference YUV on one and three "
                + "threads")
        void conformance(H264ConformanceTest.Vector vector) throws IOException {
            H264ConformanceTest.check(vector, 1);
            H264ConformanceTest.check(vector, 3);
        }

        @ParameterizedTest(name = "slice_type {0}")
        @ValueSource(ints = {3, 4, 8, 9})
        @DisplayName("are rejected as malformed outside 8-bit 4:2:0 video")
        void outsideExtended(int sliceType) {
            // a High 4:2:2 sequence (chroma_format_idc 2), which SP and SI slices are not defined for
            var sps = H264BitWriter.nal(3, 7, new H264BitWriter().bits(122, 8).bits(0, 8).bits(30, 8).ue(0).ue(2).ue(0)
                    .ue(0).bit(0).bit(0).ue(0).ue(2).ue(1).bit(0).ue(1).ue(1).bit(1).bit(1).bit(0).bit(0).trailing());
            var header = new H264BitWriter().ue(0).ue(sliceType).ue(0).bits(0, 4);
            if (sliceType % 5 == 3) {
                header.bit(0).bit(0);
            }
            header.bit(0).se(0);
            if (sliceType % 5 == 3) {
                header.bit(0);
            }
            header.se(0).ue(1).ue(0);
            var slice = H264BitWriter.nal(3, 1, header.trailing());
            assertThrows(MediaException.Malformed.class, () -> hashes(
                    List.of(packet(H264TestStreams.annexB(List.of(sps, H264BitWriter.pps(), slice)))),
                    H264DecoderOptions.builder().threads(1).build()));
        }

        @Test
        @DisplayName("are reported as decodable by the stream information of an Extended sequence")
        void streamInfo() {
            for (var name : GOLDEN.keySet()) {
                var nals = H264TestStreams.nalUnits(resource(name));
                var sps = nals.stream().filter(nal -> type(nal) == 7).findFirst().orElseThrow();
                var info = H264StreamInfo.probe(sps);
                assertEquals(88, info.profileIdc(), name);
                assertTrue(info.decoderSupported(), name);
            }
        }
    }
}
