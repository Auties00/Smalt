/*
 * The libwebrtc vectors (StapAKey, FuA, EmptyStapARejected, the truncation cases) come from
 * modules/rtp_rtcp/source/video_rtp_depacketizer_h264_unittest.cc, Copyright (c) 2014 The WebRTC project authors;
 * BSD 3-Clause License.
 */
package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("H.264 RTP payload format (RFC 6184)")
class H264RtpTest {
    // Parameter sets of a 64x48 baseline stream made with ffmpeg's libx264 (testsrc, -profile:v baseline).
    static final byte[] SPS = HexFormat.of().parseHex("6742c00ad9047b011000000300100000030140f1226480");
    static final byte[] PPS = HexFormat.of().parseHex("68cb83cb20");

    static byte[] nal(int header, int length, Random random) {
        var data = RtpChannel.random(random, length);
        data[0] = (byte) header;
        return data;
    }

    static byte[] lengthPrefixed(List<byte[]> nals) {
        var out = new ByteArrayOutputStream();
        for (var nal : nals) {
            out.write(nal.length >>> 24);
            out.write(nal.length >>> 16);
            out.write(nal.length >>> 8);
            out.write(nal.length);
            out.writeBytes(nal);
        }
        return out.toByteArray();
    }

    /** A stream of 24 access units: SPS, PPS, SEI and an IDR first, then P slices of varied sizes, two per AU. */
    static List<RtpChannel.Unit> stream(long seed) {
        var random = new Random(seed);
        var units = new ArrayList<RtpChannel.Unit>();
        for (var index = 0; index < 24; index++) {
            var nals = new ArrayList<byte[]>();
            var key = index % 12 == 0;
            if (key) {
                nals.add(SPS);
                nals.add(PPS);
                nals.add(nal(0x06, 20, random));
                nals.add(nal(0x65, 4000 + random.nextInt(3000), random));
            } else {
                nals.add(nal(0x41, 1 + random.nextInt(index % 3 == 0 ? 3000 : 400), random));
                nals.add(nal(0x01, 2 + random.nextInt(60), random));
            }
            units.add(new RtpChannel.Unit(lengthPrefixed(nals), index * 3000L, key));
        }
        return units;
    }

    static RtpDepacketizer depacketizer(RtpPayloadFormat format) {
        return RtpDepacketizer.create(format, RtpDepacketizerOptions.defaults());
    }

    @Nested
    @DisplayName("round trips")
    class RoundTrips {
        @ParameterizedTest(name = "packetization mode {0}")
        @ValueSource(ints = {1, 2})
        @DisplayName("reassembles every access unit bit for bit")
        void exact(int mode) {
            var units = stream(mode);
            var format = RtpPayloadFormat.h264(mode).toBuilder().parameter("sprop-interleaving-depth", "0").build();
            if (mode != 2) {
                format = format.toBuilder().removeParameter("sprop-interleaving-depth").build();
            }
            var packetizer = RtpPacketizer.create(format, RtpChannel.fixed().build());
            var sent = RtpChannel.packetize(packetizer, units);
            for (var packet : sent.packets()) {
                assertTrue(packet.size() <= RtpPacketizerOptions.DEFAULT_MAX_PACKET_SIZE, "packet too large");
                var type = packet.payload()[0] & 0x1F;
                if (mode == 1) {
                    assertTrue(type <= 24 || type == 28, "type " + type + " in mode 1");
                } else {
                    assertTrue(type == 25 || type == 28 || type == 29, "type " + type + " in mode 2");
                }
            }
            var frames = RtpChannel.depacketize(depacketizer(format), RtpChannel.overTheWire(sent.packets()));
            assertEquals(units.size(), frames.size());
            for (var index = 0; index < units.size(); index++) {
                var frame = frames.get(index);
                assertArrayEquals(units.get(index).data(), frame.packet().data(), "access unit " + index);
                assertTrue(frame.complete());
                assertEquals(units.get(index).key(), frame.packet().keyframe());
                assertEquals(index * 3000L, frame.packet().pts());
                assertEquals(0xFFFF_F000L + index * 3000L, frame.rtpTimestamp());
            }
        }

        @Test
        @DisplayName("aggregates small NAL units into STAP-A and fragments large ones into FU-A")
        void packetShapes() {
            var units = stream(7);
            var packetizer = RtpPacketizer.create(RtpPayloadFormat.h264(1), RtpChannel.fixed().build());
            var first = packetizer.packetize(units.getFirst().packet());
            assertEquals(24, first.getFirst().payload()[0] & 0x1F, "SPS, PPS and SEI share a STAP-A");
            assertEquals(28, first.get(1).payload()[0] & 0x1F);
            assertEquals(0x85, first.get(1).payload()[1] & 0xFF, "first FU-A of an IDR: start bit and type 5");
            assertEquals(0x45, first.getLast().payload()[1] & 0xFF, "last FU-A: end bit and type 5");
            for (var index = 0; index < first.size(); index++) {
                assertEquals(index == first.size() - 1, first.get(index).marker());
                assertEquals(0xFFFF_F000L, first.get(index).timestamp());
                assertEquals((65_530 + index) & 0xFFFF, first.get(index).sequenceNumber());
            }
            var sizes = first.subList(1, first.size()).stream().mapToInt(RtpPacket::size).toArray();
            for (var size : sizes) {
                assertTrue(Math.abs(size - sizes[0]) <= 1, "FU-A fragments are balanced");
            }
        }

        @Test
        @DisplayName("sends one NAL unit per packet in mode 0 and rejects a NAL unit larger than a packet")
        void singleNal() {
            var nals = List.of(SPS, PPS, nal(0x65, 900, new Random(3)));
            var packets = RtpPacketizer.create(RtpPayloadFormat.h264(0), RtpChannel.fixed().build())
                    .packetize(new RtpChannel.Unit(lengthPrefixed(nals), 0, true).packet());
            assertEquals(3, packets.size());
            for (var index = 0; index < 3; index++) {
                assertArrayEquals(nals.get(index), packets.get(index).payload());
            }
            var big = new RtpChannel.Unit(lengthPrefixed(List.of(SPS, nal(0x65, 2000, new Random(4)))), 0, true);
            var packetizer = RtpPacketizer.create(RtpPayloadFormat.h264(0), RtpChannel.fixed().build());
            assertThrows(MediaException.LimitExceeded.class, () -> packetizer.packetize(big.packet()));
            assertEquals(65_530, packetizer.nextSequenceNumber(), "a failed unit leaves no sequence gap");
        }

        @ParameterizedTest(name = "NAL unit of the payload size minus {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4})
        @DisplayName("sends a NAL unit too large for a STAP-A but not for a packet as a single NAL unit packet")
        void nearlyFullNal(int shortBy) {
            var room = RtpPacketizerOptions.DEFAULT_MAX_PACKET_SIZE - RtpPacket.HEADER_SIZE;
            var nals = List.of(nal(0x65, room - shortBy, new Random(shortBy)), nal(0x01, 10, new Random(9)),
                    nal(0x01, room - shortBy, new Random(shortBy + 10)));
            var unit = new RtpChannel.Unit(lengthPrefixed(nals), 0, true);
            var packets = assertTimeoutPreemptively(java.time.Duration.ofSeconds(10),
                    () -> RtpPacketizer.create(RtpPayloadFormat.h264(1), RtpChannel.fixed().build())
                            .packetize(unit.packet()));
            assertEquals(3, packets.size());
            for (var packet : packets) {
                assertTrue(packet.payload().length <= room);
            }
            assertArrayEquals(nals.getFirst(), packets.getFirst().payload());
            var frames = RtpChannel.depacketize(depacketizer(RtpPayloadFormat.h264(1)), packets);
            assertEquals(1, frames.size());
            assertArrayEquals(unit.data(), frames.getFirst().packet().data());
        }

        @Test
        @DisplayName("reads Annex B input and repeats the sprop parameter sets before an IDR picture without them")
        void annexBAndParameterSets() {
            var track = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(new Rational(1, 90_000)).width(64)
                    .height(48).codecConfig(com.github.auties00.smalt.internal.codec.NalConfigs.avcConfig(
                            List.of(SPS), List.of(PPS))).build();
            var format = RtpPayloadFormat.forTrack(track);
            assertEquals("42c00a", format.parameter("profile-level-id").orElseThrow());
            assertEquals("Z0LACtkEewEQAAADABAAAAMBQPEiZIA=,aMuDyyA=",
                    format.parameter("sprop-parameter-sets").orElseThrow());
            var idr = nal(0x65, 3000, new Random(5));
            var annexB = new ByteArrayOutputStream();
            annexB.writeBytes(new byte[]{0, 0, 0, 1});
            annexB.writeBytes(idr);
            var packetizer = RtpPacketizer.create(format, RtpChannel.fixed().build());
            var packets = packetizer.packetize(new RtpChannel.Unit(annexB.toByteArray(), 0, true).packet());
            var depacketizer = depacketizer(format);
            var frames = RtpChannel.depacketize(depacketizer, packets);
            assertEquals(1, frames.size());
            assertArrayEquals(lengthPrefixed(List.of(SPS, PPS, idr)), frames.getFirst().packet().data());
            assertTrue(frames.getFirst().packet().sideData().newCodecConfig().isEmpty(),
                    "the in-band sets equal the sprop sets");
            var config = NalRecords.parseAvcStrict(depacketizer.codecConfig().orElseThrow());
            assertArrayEquals(SPS, config.parameterSets().get(0));
            assertArrayEquals(PPS, config.parameterSets().get(1));
            var plain = RtpPacketizer.create(format, RtpChannel.fixed().parameterSetsBeforeKeyFrames(false).build())
                    .packetize(new RtpChannel.Unit(annexB.toByteArray(), 0, true).packet());
            assertArrayEquals(lengthPrefixed(List.of(idr)),
                    RtpChannel.depacketize(depacketizer(format), plain).getFirst().packet().data());
        }

        @Test
        @DisplayName("reports new in-band parameter sets as a new codec configuration")
        void newParameterSets() {
            var depacketizer = depacketizer(RtpPayloadFormat.h264(1));
            assertTrue(depacketizer.codecConfig().isEmpty());
            var packets = RtpPacketizer.create(RtpPayloadFormat.h264(1), RtpChannel.fixed().build())
                    .packetize(new RtpChannel.Unit(lengthPrefixed(List.of(SPS, PPS, nal(0x65, 50, new Random(1)))),
                            0, true).packet());
            var frames = RtpChannel.depacketize(depacketizer, packets);
            var config = frames.getFirst().packet().sideData().newCodecConfig().orElseThrow();
            assertArrayEquals(depacketizer.codecConfig().orElseThrow(), config);
            assertEquals(2, NalRecords.parseAvcStrict(config).parameterSets().size());
        }
    }

    @Nested
    @DisplayName("network damage")
    class Damage {
        @Test
        @DisplayName("restores the access units through reordering within the window and duplicates")
        void reorderAndDuplicates() {
            var units = stream(11);
            var format = RtpPayloadFormat.h264(1);
            var sent = RtpChannel.packetize(RtpPacketizer.create(format, RtpChannel.fixed().build()), units);
            var shuffled = RtpChannel.duplicate(RtpChannel.reorder(sent.packets(), 8, 99), 5);
            var depacketizer = depacketizer(format);
            var frames = RtpChannel.depacketize(depacketizer, shuffled);
            assertEquals(units.size(), frames.size());
            for (var index = 0; index < units.size(); index++) {
                assertArrayEquals(units.get(index).data(), frames.get(index).packet().data());
                assertTrue(frames.get(index).complete());
            }
            assertTrue(depacketizer.duplicatePackets() + depacketizer.latePackets() > 0);
            assertEquals(0, depacketizer.lostPackets());
        }

        @Test
        @DisplayName("flags exactly the access units a loss can touch and drops the broken NAL unit")
        void loss() {
            var units = stream(13);
            var format = RtpPayloadFormat.h264(1);
            var sent = RtpChannel.packetize(RtpPacketizer.create(format, RtpChannel.fixed().build()), units);
            // The second FU-A of the first IDR: a middle fragment of access unit 0.
            var lost = 2;
            assertEquals(0, sent.owners().get(lost));
            var depacketizer = depacketizer(format);
            var frames = RtpChannel.depacketize(depacketizer, RtpChannel.drop(sent.packets(), Set.of(lost)));
            assertEquals(units.size(), frames.size());
            assertFalse(frames.getFirst().complete());
            assertEquals(1, depacketizer.lostPackets());
            var expected = lengthPrefixed(List.of(SPS, PPS));
            var got = frames.getFirst().packet().data();
            assertArrayEquals(expected, java.util.Arrays.copyOf(got, expected.length));
            for (var index = 1; index < units.size(); index++) {
                assertTrue(frames.get(index).complete(), "access unit " + index);
                assertArrayEquals(units.get(index).data(), frames.get(index).packet().data());
            }
        }

        @Test
        @DisplayName("marks both access units around a lost final packet as incomplete")
        void lostMarker() {
            var units = stream(17);
            var format = RtpPayloadFormat.h264(1);
            var sent = RtpChannel.packetize(RtpPacketizer.create(format, RtpChannel.fixed().build()), units);
            var lost = sent.owners().lastIndexOf(4);
            var frames = RtpChannel.depacketize(depacketizer(format), RtpChannel.drop(sent.packets(), Set.of(lost)));
            var seen = new java.util.HashSet<Long>();
            for (var frame : frames) {
                var index = frame.packet().pts() / 3000;
                seen.add(index);
                assertEquals(index != 4 && index != 5, frame.complete(), "access unit " + index);
            }
            assertTrue(seen.contains(5L));
        }

        @Test
        @DisplayName("survives random loss with every surviving complete unit intact")
        void randomLoss() {
            var units = stream(19);
            var format = RtpPayloadFormat.h264(1);
            var sent = RtpChannel.packetize(RtpPacketizer.create(format, RtpChannel.fixed().build()), units);
            var drops = RtpChannel.randomPositions(sent.packets().size(), sent.packets().size() / 10, 5);
            var frames = RtpChannel.depacketize(depacketizer(format),
                    RtpChannel.reorder(RtpChannel.drop(sent.packets(), drops), 4, 6));
            var byTimestamp = new java.util.HashMap<Long, byte[]>();
            for (var unit : units) {
                byTimestamp.put(unit.timestamp(), unit.data());
            }
            var complete = 0;
            for (var frame : frames) {
                if (frame.complete()) {
                    complete++;
                    assertArrayEquals(byTimestamp.get(frame.packet().pts()), frame.packet().data());
                }
            }
            assertTrue(complete > 0);
        }
    }

    @Nested
    @DisplayName("libwebrtc vectors")
    class Webrtc {
        static final byte[] STAP_A_KEY = {
                24, 0x00, 0x18, 0x07, 0x7A, 0x00, 0x1F, (byte) 0xBC, (byte) 0xD9, 0x40, 0x50, 0x05, (byte) 0xBA, 0x10,
                0x00, 0x00, 0x03, 0x00, (byte) 0xC0, 0x00, 0x00, 0x03, 0x2A, (byte) 0xE0, (byte) 0xF1, (byte) 0x83,
                0x25, 0x00, 0x0D, 0x08, 0x69, (byte) 0xFC, 0x0, 0x0, 0x3, 0x0, 0x7, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xF6, 0x40, 0x00, 0x0B, 0x05, (byte) 0x85, (byte) 0xB8, 0x0, 0x4, 0x0, 0x0, 0x13,
                (byte) 0x93, 0x12, 0x0};

        @Test
        @DisplayName("splits the StapAKey payload into SPS, PPS and IDR and flags the key frame")
        void stapAKey() {
            var depacketizer = depacketizer(RtpPayloadFormat.h264(1));
            var frames = RtpChannel.depacketize(depacketizer,
                    List.of(new RtpPacket(true, 96, 1, 0, 1, STAP_A_KEY)));
            assertEquals(1, frames.size());
            var data = frames.getFirst().packet().data();
            var expected = lengthPrefixed(List.of(java.util.Arrays.copyOfRange(STAP_A_KEY, 3, 27),
                    java.util.Arrays.copyOfRange(STAP_A_KEY, 29, 42), java.util.Arrays.copyOfRange(STAP_A_KEY, 44,
                            55)));
            assertArrayEquals(expected, data);
            assertTrue(frames.getFirst().packet().keyframe());
        }

        @Test
        @DisplayName("joins the FuA fragments into one IDR NAL unit")
        void fuA() {
            var packets = List.of(
                    new RtpPacket(false, 96, 1, 0, 1, new byte[]{28, (byte) 0x85, (byte) 0x85, (byte) 0xB8, 0x00,
                            0x04, 0x00, 0x00, 0x13, (byte) 0x93, 0x12, 0x00}),
                    new RtpPacket(false, 96, 2, 0, 1, new byte[]{28, 0x05, 0x02}),
                    new RtpPacket(true, 96, 3, 0, 1, new byte[]{28, 0x45, 0x03}));
            var frames = RtpChannel.depacketize(depacketizer(RtpPayloadFormat.h264(1)), packets);
            assertArrayEquals(lengthPrefixed(List.of(new byte[]{0x05, (byte) 0x85, (byte) 0xB8, 0x00, 0x04, 0x00,
                    0x00, 0x13, (byte) 0x93, 0x12, 0x00, 0x02, 0x03})), frames.getFirst().packet().data());
            assertTrue(frames.getFirst().complete());
        }

        @Test
        @DisplayName("discards empty, truncated and reserved aggregation payloads")
        void rejected() {
            byte[][] bad = {
                    {24, 0x00, 0x00},
                    {24, 0x00, 0x00, 0x00, 0x04, 0x05, (byte) 0xFF, 0x00, 0x11},
                    {24, 0x00, 0x03, 0x05, (byte) 0xFF, 0x00, 0x00, 0x00, 0x00, 0x04, 0x05, (byte) 0xFF, 0x00, 0x11},
                    {24, 0x00, 0x03, 0x05, (byte) 0xFF, 0x00, 0x00, 0x00},
                    {(byte) 0x9c},
                    {(byte) 0xd8, 0x27},
                    {0x58, (byte) 0xCB, (byte) 0xED, (byte) 0xDF},
                    {0x38, 0x27, 0x27},
                    {},
                    {30, 1, 2}};
            var depacketizer = depacketizer(RtpPayloadFormat.h264(1));
            var sequence = 0;
            var frames = new ArrayList<RtpFrame>();
            for (var payload : bad) {
                frames.addAll(depacketizer.push(new RtpPacket(true, 96, sequence++, 0, 1, payload)));
            }
            frames.addAll(depacketizer.flush());
            assertEquals(bad.length, depacketizer.discardedPackets());
            assertTrue(frames.isEmpty());
        }
    }

    @Nested
    @DisplayName("interleaved mode")
    class Interleaved {
        private static byte[] stapB(int don, byte[]... nals) {
            var out = new ByteArrayOutputStream();
            out.write(25);
            out.write(don >>> 8);
            out.write(don);
            for (var nal : nals) {
                out.write(nal.length >>> 8);
                out.write(nal.length);
                out.writeBytes(nal);
            }
            return out.toByteArray();
        }

        @Test
        @DisplayName("puts NAL units sent out of decoding order back by decoding order number")
        void deinterleaves() {
            var random = new Random(23);
            var a = nal(0x65, 30, random);
            var b = nal(0x41, 30, random);
            var c = nal(0x41, 30, random);
            var d = nal(0x41, 30, random);
            var format = RtpPayloadFormat.h264(2).toBuilder().parameter("sprop-interleaving-depth", "2").build();
            // Transmission order a, c, b, d with DONs 0, 2, 1, 3 and the timestamps of their access units.
            var packets = List.of(
                    new RtpPacket(true, 96, 10, 0, 1, stapB(0xFFFF, a)),
                    new RtpPacket(true, 96, 11, 6000, 1, stapB(1, c)),
                    new RtpPacket(true, 96, 12, 3000, 1, stapB(0, b)),
                    new RtpPacket(true, 96, 13, 9000, 1, stapB(2, d)));
            var frames = RtpChannel.depacketize(depacketizer(format), packets);
            assertEquals(4, frames.size());
            assertArrayEquals(lengthPrefixed(List.of(a)), frames.get(0).packet().data());
            assertArrayEquals(lengthPrefixed(List.of(b)), frames.get(1).packet().data());
            assertArrayEquals(lengthPrefixed(List.of(c)), frames.get(2).packet().data());
            assertArrayEquals(lengthPrefixed(List.of(d)), frames.get(3).packet().data());
            assertEquals(List.of(0L, 3000L, 6000L, 9000L), frames.stream().map(f -> f.packet().pts()).toList());
            assertTrue(frames.stream().allMatch(RtpFrame::complete));
        }

        @Test
        @DisplayName("reads MTAP16 and MTAP24 units with their DOND and timestamp offsets")
        void mtap() {
            var random = new Random(29);
            var first = nal(0x65, 12, random);
            var second = nal(0x41, 9, random);
            var mtap16 = new ByteArrayOutputStream();
            mtap16.writeBytes(new byte[]{26, 0x00, 0x05});
            mtap16.writeBytes(new byte[]{0, 12, 0, 0x00, 0x00});
            mtap16.writeBytes(first);
            mtap16.writeBytes(new byte[]{0, 9, 1, 0x0B, (byte) 0xB8});
            mtap16.writeBytes(second);
            var mtap24 = new ByteArrayOutputStream();
            mtap24.writeBytes(new byte[]{27, 0x00, 0x07});
            mtap24.writeBytes(new byte[]{0, 9, 0, 0x00, 0x00, 0x00});
            mtap24.writeBytes(second);
            var format = RtpPayloadFormat.h264(2).toBuilder().parameter("sprop-interleaving-depth", "1").build();
            var frames = RtpChannel.depacketize(depacketizer(format), List.of(
                    new RtpPacket(true, 96, 1, 0, 1, mtap16.toByteArray()),
                    new RtpPacket(true, 96, 2, 6000, 1, mtap24.toByteArray())));
            assertEquals(3, frames.size());
            assertArrayEquals(lengthPrefixed(List.of(first)), frames.get(0).packet().data());
            assertEquals(0, frames.get(0).packet().pts());
            assertEquals(3000, frames.get(1).packet().pts());
            assertEquals(6000, frames.get(2).packet().pts());
            assertTrue(frames.stream().allMatch(RtpFrame::complete), "DONs 5, 6 and 7 are consecutive");
        }
    }
}
