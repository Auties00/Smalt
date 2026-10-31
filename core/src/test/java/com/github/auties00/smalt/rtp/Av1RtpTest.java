/*
 * The vectors of the Webrtc class come from modules/rtp_rtcp/source/video_rtp_depacketizer_av1_unittest.cc,
 * Copyright (c) 2019 The WebRTC project authors; BSD 3-Clause License.
 */
package com.github.auties00.smalt.rtp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AV1 RTP payload format (AOMedia)")
class Av1RtpTest {
    static void obu(ByteArrayOutputStream out, int type, int extension, byte[] payload) {
        out.write((type << 3) | (extension >= 0 ? 0x04 : 0) | 0x02);
        if (extension >= 0) {
            out.write(extension);
        }
        var size = (long) payload.length;
        do {
            var low = (int) (size & 0x7F);
            size >>>= 7;
            out.write(size != 0 ? low | 0x80 : low);
        } while (size != 0);
        out.writeBytes(payload);
    }

    /** A temporal unit with a temporal delimiter; {@code expected} receives it without one. */
    static byte[] temporalUnit(boolean key, int frameSize, Random random, ByteArrayOutputStream expected) {
        var withoutDelimiter = new ByteArrayOutputStream();
        if (key) {
            obu(withoutDelimiter, 1, -1, RtpChannel.random(random, 11));
        }
        var frame = RtpChannel.random(random, frameSize);
        frame[0] = (byte) (key ? 0x10 : 0x30);
        obu(withoutDelimiter, 6, -1, frame);
        if (random.nextInt(3) == 0) {
            obu(withoutDelimiter, 5, -1, RtpChannel.random(random, 4));
        }
        expected.writeBytes(withoutDelimiter.toByteArray());
        var out = new ByteArrayOutputStream();
        obu(out, 2, -1, new byte[0]);
        out.writeBytes(withoutDelimiter.toByteArray());
        return out.toByteArray();
    }

    record Stream(List<RtpChannel.Unit> units, List<byte[]> expected) {
    }

    static Stream stream(long seed) {
        var random = new Random(seed);
        var units = new ArrayList<RtpChannel.Unit>();
        var expected = new ArrayList<byte[]>();
        for (var index = 0; index < 24; index++) {
            var key = index % 12 == 0;
            var out = new ByteArrayOutputStream();
            var size = key ? 7000 + random.nextInt(2000) : 1 + random.nextInt(index % 5 == 0 ? 4000 : 300);
            units.add(new RtpChannel.Unit(temporalUnit(key, size, random, out), index * 3000L, key));
            expected.add(out.toByteArray());
        }
        return new Stream(units, expected);
    }

    static RtpDepacketizer depacketizer() {
        return RtpDepacketizer.create(RtpPayloadFormat.av1(), RtpDepacketizerOptions.defaults());
    }

    @ParameterizedTest(name = "packets of at most {0} bytes")
    @ValueSource(ints = {64, 300, 1200, 9000})
    @DisplayName("reassembles every temporal unit without its temporal delimiter")
    void roundTrip(int maxPacketSize) {
        var stream = stream(maxPacketSize);
        var packetizer = RtpPacketizer.create(RtpPayloadFormat.av1(),
                RtpChannel.fixed().maxPacketSize(maxPacketSize).build());
        var sent = RtpChannel.packetize(packetizer, stream.units());
        for (var index = 0; index < sent.packets().size(); index++) {
            var packet = sent.packets().get(index);
            assertTrue(packet.size() <= maxPacketSize, "packet of " + packet.size() + " bytes");
            var owner = sent.owners().get(index);
            var first = index == 0 || !sent.owners().get(index - 1).equals(owner);
            var aggregation = packet.payload()[0] & 0xFF;
            assertEquals(first && stream.units().get(owner).key(), (aggregation & 0x08) != 0, "N bit");
            if (first) {
                assertEquals(0, aggregation & 0x80, "a temporal unit starts with a whole OBU");
            }
        }
        var frames = RtpChannel.depacketize(depacketizer(), RtpChannel.overTheWire(sent.packets()));
        assertEquals(stream.units().size(), frames.size());
        for (var index = 0; index < frames.size(); index++) {
            assertArrayEquals(stream.expected().get(index), frames.get(index).packet().data(), "unit " + index);
            assertEquals(stream.units().get(index).key(), frames.get(index).packet().keyframe());
            assertTrue(frames.get(index).complete());
        }
    }

    @Test
    @DisplayName("keeps OBUs of different layers in different packets")
    void layers() {
        var out = new ByteArrayOutputStream();
        obu(out, 1, -1, new byte[]{1, 2, 3});
        obu(out, 6, 0b000_00_000, new byte[]{0x10, 1});
        obu(out, 6, 0b000_01_000, new byte[]{0x30, 2});
        obu(out, 6, 0b000_01_000, new byte[]{0x30, 3});
        var packets = RtpPacketizer.create(RtpPayloadFormat.av1(), RtpChannel.fixed().build())
                .packetize(new RtpChannel.Unit(out.toByteArray(), 0, true).packet());
        assertEquals(2, packets.size());
        assertEquals(0b0010_1000, packets.getFirst().payload()[0] & 0xFF, "W=2 and N on the base layer packet");
        assertEquals(0b0010_0000, packets.getLast().payload()[0] & 0xFF);
        assertFalse(packets.getFirst().marker());
        assertTrue(packets.getLast().marker());
        var frames = RtpChannel.depacketize(depacketizer(), packets);
        assertArrayEquals(out.toByteArray(), frames.getFirst().packet().data());
    }

    @Test
    @DisplayName("drops the broken OBU and flags only the temporal units a loss can touch")
    void loss() {
        var stream = stream(5);
        var sent = RtpChannel.packetize(RtpPacketizer.create(RtpPayloadFormat.av1(), RtpChannel.fixed().build()),
                stream.units());
        var lost = sent.owners().indexOf(0) + 3;
        var frames = RtpChannel.depacketize(depacketizer(),
                RtpChannel.reorder(RtpChannel.drop(sent.packets(), Set.of(lost)), 3, 2));
        assertEquals(stream.units().size(), frames.size());
        assertFalse(frames.getFirst().complete());
        for (var index = 1; index < frames.size(); index++) {
            assertTrue(frames.get(index).complete(), "unit " + index);
            assertArrayEquals(stream.expected().get(index), frames.get(index).packet().data());
        }
    }

    @Nested
    @DisplayName("libwebrtc vectors")
    class Webrtc {
        private static byte[] assemble(byte[]... payloads) {
            var packets = new ArrayList<RtpPacket>();
            for (var index = 0; index < payloads.length; index++) {
                packets.add(new RtpPacket(index == payloads.length - 1, 96, index, 0, 1, payloads[index]));
            }
            var frames = RtpChannel.depacketize(depacketizer(), packets);
            assertEquals(1, frames.size());
            assertTrue(frames.getFirst().complete());
            return frames.getFirst().packet().data();
        }

        private static byte[] bytes(int... values) {
            var out = new byte[values.length];
            for (var index = 0; index < values.length; index++) {
                out[index] = (byte) values[index];
            }
            return out;
        }

        @Test
        @DisplayName("sets the OBU size field whether or not the sender wrote one")
        void sizeFields() {
            assertArrayEquals(bytes(0b0_0110_010, 3, 20, 30, 40), assemble(bytes(0b00_01_0000, 0b0_0110_000, 20,
                    30, 40)));
            assertArrayEquals(bytes(0b0_0110_010, 3, 20, 30, 40), assemble(bytes(0b00_01_0000, 0b0_0110_010, 3,
                    20, 30, 40)));
            assertArrayEquals(bytes(0b0_0110_110, 0b010_01_000, 3, 20, 30, 40), assemble(bytes(0b00_01_0000,
                    0b0_0110_100, 0b010_01_000, 20, 30, 40)));
            assertArrayEquals(bytes(0b0_0110_110, 0b010_01_000, 3, 20, 30, 40), assemble(bytes(0b00_01_0000,
                    0b0_0110_110, 0b010_01_000, 3, 20, 30, 40)));
        }

        @Test
        @DisplayName("joins OBUs aggregated and fragmented across packets")
        void aggregation() {
            assertArrayEquals(bytes(0b0_0001_010, 1, 10, 0b0_0110_010, 1, 20),
                    assemble(bytes(0b00_10_0000, 2, 0b0_0001_000, 10, 0b0_0110_000, 20)));
            assertArrayEquals(bytes(0b0_0110_010, 3, 20, 30, 40),
                    assemble(bytes(0b01_01_0000, 0b0_0110_000, 20, 30), bytes(0b10_01_0000, 40)));
            assertArrayEquals(bytes(0b0_0001_010, 1, 10, 0b0_0110_010, 3, 20, 30, 40),
                    assemble(bytes(0b01_10_0000, 2, 0b0_0001_000, 10, 0b0_0110_000, 20, 30),
                            bytes(0b10_01_0000, 40)));
            assertArrayEquals(bytes(0b0_0001_010, 1, 10, 0b0_0101_010, 1, 20, 0b0_0101_110, 0b001_10_000, 2, 20,
                            30, 0b0_0110_110, 0b001_10_000, 6, 40, 50, 60, 70, 80, 90),
                    assemble(bytes(0b01_00_0000, 2, 0b0_0001_000, 10, 2, 0b0_0101_000, 20, 4, 0b0_0101_100,
                            0b001_10_000, 20, 30, 5, 0b0_0110_100, 0b001_10_000, 40, 50, 60),
                            bytes(0b10_01_0000, 70, 80, 90)));
            assertArrayEquals(bytes(0b0_0110_010, 8, 11, 12, 13, 14, 15, 16, 17, 18),
                    assemble(bytes(0b01_01_0000, 0b0_0110_000, 11, 12), bytes(0b11_01_0000, 13, 14),
                            bytes(0b11_01_0000, 15, 16, 17), bytes(0b10_01_0000, 18)));
            assertArrayEquals(bytes(0b0_0011_010, 2, 11, 12, 0b0_0100_010, 7, 21, 22, 23, 24, 25, 26, 27,
                            0b0_0111_010, 2, 11, 12, 0b0_0100_010, 6, 31, 32, 33, 34, 35, 36),
                    assemble(bytes(0b01_10_0000, 3, 0b0_0011_000, 11, 12, 0b0_0100_000, 21, 22, 23),
                            bytes(0b10_01_0000, 24, 25, 26, 27),
                            bytes(0b01_10_0000, 3, 0b0_0111_000, 11, 12, 0b0_0100_000, 31, 32),
                            bytes(0b10_01_0000, 33, 34, 35, 36)));
        }

        @Test
        @DisplayName("handles two-byte element sizes and packets holding only an aggregation header")
        void edges() {
            var big = new byte[4 + 127];
            big[1] = (byte) 0x80;
            big[2] = 0x01;
            big[3] = 0b0_0110_000;
            big[4 + 42] = 0x42;
            var frame = assemble(big);
            assertEquals(2 + 127, frame.length);
            assertEquals(127, frame[1]);
            assertEquals(0x42, frame[2 + 42]);
            var first = new byte[3 + 32];
            first[0] = 0b01_00_0000;
            first[1] = 33;
            first[2] = 0b0_0110_000;
            first[3 + 10] = 0x10;
            var second = new byte[2 + 96];
            second[0] = (byte) 0b10_00_0000;
            second[1] = 96;
            second[2 + 20] = 0x20;
            frame = assemble(first, second);
            assertEquals(3 + 128, frame.length);
            assertEquals(0x80, frame[1] & 0xFF);
            assertEquals(0x01, frame[2]);
            assertEquals(0x10, frame[3 + 10]);
            assertEquals(0x20, frame[3 + 32 + 20]);
            assertArrayEquals(bytes(0b0_0110_010, 3, 10, 20, 30),
                    assemble(bytes(0b01_01_0000), bytes(0b10_01_0000, 0b0_0110_000, 10, 20, 30)));
            assertArrayEquals(bytes(0b0_0110_010, 3, 10, 20, 30),
                    assemble(bytes(0b01_01_0000, 0b0_0110_000, 10, 20, 30), bytes(0b10_01_0000)));
        }

        @Test
        @DisplayName("treats the N bit as a key frame and rejects N with Z")
        void newSequence() {
            var depacketizer = depacketizer();
            var frames = RtpChannel.depacketize(depacketizer,
                    List.of(new RtpPacket(true, 96, 1, 0, 1, bytes(0b00_01_1000, 0b0_0110_000, 0x30))));
            assertTrue(frames.getFirst().packet().keyframe());
            var rejected = depacketizer();
            assertTrue(RtpChannel.depacketize(rejected,
                    List.of(new RtpPacket(true, 96, 1, 0, 1, bytes(0b10_01_1000, 0b0_0001_000)))).isEmpty());
            assertEquals(1, rejected.discardedPackets());
        }
    }
}
