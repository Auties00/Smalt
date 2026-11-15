package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("MPEG-4 generic RTP payload format (RFC 3640)")
class Mpeg4GenericRtpTest {
    // AAC-LC, 48 kHz, stereo.
    static final byte[] CONFIG = HexFormat.of().parseHex("1190");

    static List<RtpChannel.Unit> stream(int count, int maxSize, long seed) {
        var random = new Random(seed);
        var units = new ArrayList<RtpChannel.Unit>();
        for (var index = 0; index < count; index++) {
            units.add(new RtpChannel.Unit(RtpChannel.random(random, 1 + random.nextInt(maxSize)), index * 1024L,
                    true));
        }
        return units;
    }

    static void assertSame(List<RtpChannel.Unit> units, List<RtpFrame> frames) {
        assertEquals(units.size(), frames.size());
        for (var index = 0; index < units.size(); index++) {
            assertArrayEquals(units.get(index).data(), frames.get(index).packet().data(), "access unit " + index);
            assertEquals(units.get(index).timestamp(), frames.get(index).packet().pts());
            assertEquals(1024, frames.get(index).packet().duration());
        }
    }

    @ParameterizedTest(name = "{0} access units per packet")
    @ValueSource(ints = {1, 3, 8})
    @DisplayName("aggregates and fragments AAC-hbr access units and restores them bit for bit")
    void hbr(int framesPerPacket) {
        var units = stream(80, 3000, framesPerPacket);
        var format = RtpPayloadFormat.aacHbr(CONFIG);
        assertEquals(48_000, format.clockRate());
        assertEquals(2, format.channels().orElseThrow());
        var sent = RtpChannel.packetize(RtpPacketizer.create(format, RtpChannel.fixed()
                .framesPerPacket(framesPerPacket).build()), units);
        for (var packet : sent.packets()) {
            assertTrue(packet.size() <= 1200);
        }
        var depacketizer = RtpDepacketizer.create(format, RtpDepacketizerOptions.defaults());
        assertArrayEquals(CONFIG, depacketizer.codecConfig().orElseThrow());
        assertSame(units, RtpChannel.depacketize(depacketizer,
                RtpChannel.overTheWire(RtpChannel.reorder(sent.packets(), 5, 3))));
    }

    @Test
    @DisplayName("writes the AU header section of RFC 3640 3.3.6")
    void hbrLayout() {
        var unit = new byte[700];
        var payload = RtpPacketizer.create(RtpPayloadFormat.aacHbr(CONFIG), RtpChannel.fixed().build())
                .packetize(new RtpChannel.Unit(unit, 0, true).packet()).getFirst();
        assertTrue(payload.marker());
        var data = payload.payload();
        assertEquals(0x0010, ((data[0] & 0xFF) << 8) | (data[1] & 0xFF), "16 bits of AU headers");
        assertEquals(700 << 3, ((data[2] & 0xFF) << 8) | (data[3] & 0xFF), "AU-size 700, AU-Index 0");
        assertEquals(4 + 700, data.length);
    }

    @Test
    @DisplayName("aggregates AAC-lbr frames and refuses one larger than 63 bytes")
    void lbr() {
        var units = stream(50, 63, 4);
        var format = RtpPayloadFormat.aacLbr(CONFIG);
        var sent = RtpChannel.packetize(RtpPacketizer.create(format, RtpChannel.fixed().framesPerPacket(5).build()),
                units);
        assertEquals(10, sent.packets().size());
        assertSame(units, RtpChannel.depacketize(RtpDepacketizer.create(format, RtpDepacketizerOptions.defaults()),
                sent.packets()));
        assertThrows(MediaException.LimitExceeded.class, () -> RtpPacketizer.create(format,
                RtpChannel.fixed().build()).packetize(new RtpChannel.Unit(new byte[64], 0, true).packet()));
    }

    @Test
    @DisplayName("drops a fragmented access unit that lost a fragment and keeps the rest")
    void fragmentLoss() {
        var units = stream(20, 4000, 6);
        var format = RtpPayloadFormat.aacHbr(CONFIG);
        var sent = RtpChannel.packetize(RtpPacketizer.create(format, RtpChannel.fixed().build()), units);
        var big = 0;
        while (sent.owners().indexOf(big) == sent.owners().lastIndexOf(big)) {
            big++;
        }
        var lost = sent.owners().indexOf(big);
        var frames = RtpChannel.depacketize(RtpDepacketizer.create(format, RtpDepacketizerOptions.defaults()),
                RtpChannel.drop(sent.packets(), Set.of(lost)));
        assertEquals(units.size() - 1, frames.size());
        for (var frame : frames) {
            var index = (int) (frame.packet().pts() / 1024);
            assertTrue(index != big);
            assertArrayEquals(units.get(index).data(), frame.packet().data());
        }
    }

    @Test
    @DisplayName("de-interleaves the pattern of RFC 3640 2.5 by AU index deltas")
    void interleaved() {
        var format = RtpPayloadFormat.aacLbr(CONFIG).toBuilder().parameter("constantDuration", "1024")
                .parameter("maxDisplacement", "5120").build();
        var random = new Random(7);
        var units = new byte[12][];
        for (var index = 0; index < units.length; index++) {
            units[index] = RtpChannel.random(random, 10 + index);
        }
        var packets = new ArrayList<RtpPacket>();
        int[][] pattern = {{0, 3, 6}, {1, 4, 7}, {2, 5, 8}, {9, 10, 11}};
        var sequence = 0;
        for (var group : pattern) {
            var writer = new MsbBitWriter(64);
            writer.write(8 * group.length, 16);
            for (var index = 0; index < group.length; index++) {
                writer.write(units[group[index]].length, 6);
                writer.write(index == 0 ? 0 : group[index] - group[index - 1] - 1, 2);
            }
            for (var index : group) {
                writer.writeBytes(units[index], 0, units[index].length);
            }
            packets.add(new RtpPacket(true, 96, sequence++, 1024L * group[0], 1, writer.toByteArray()));
        }
        var frames = RtpChannel.depacketize(RtpDepacketizer.create(format, RtpDepacketizerOptions.defaults()),
                packets);
        assertEquals(12, frames.size());
        for (var index = 0; index < 12; index++) {
            assertArrayEquals(units[index], frames.get(index).packet().data(), "access unit " + index);
            assertEquals(1024L * index, frames.get(index).packet().pts());
        }
    }

    @Test
    @DisplayName("carries CTS deltas, random access flags and an auxiliary section in the generic mode")
    void generic() {
        var format = RtpPayloadFormat.builder("mpeg4-generic", 90_000).parameter("streamType", "5")
                .parameter("mode", "generic").parameter("sizeLength", "16").parameter("indexLength", "4")
                .parameter("indexDeltaLength", "4").parameter("CTSDeltaLength", "16")
                .parameter("DTSDeltaLength", "8").parameter("randomAccessIndication", "1")
                .parameter("streamStateIndication", "3").parameter("auxiliaryDataSizeLength", "8")
                .parameter("constantDuration", "3000").build();
        var units = new ArrayList<RtpChannel.Unit>();
        var random = new Random(8);
        for (var index = 0; index < 30; index++) {
            units.add(new RtpChannel.Unit(RtpChannel.random(random, 1 + random.nextInt(2500)), index * 3000L,
                    index % 4 == 0));
        }
        var sent = RtpChannel.packetize(RtpPacketizer.create(format, RtpChannel.fixed().framesPerPacket(4)
                .build()), units);
        var frames = RtpChannel.depacketize(RtpDepacketizer.create(format, RtpDepacketizerOptions.defaults()),
                sent.packets());
        assertEquals(units.size(), frames.size());
        for (var index = 0; index < units.size(); index++) {
            assertArrayEquals(units.get(index).data(), frames.get(index).packet().data());
            assertEquals(units.get(index).key(), frames.get(index).packet().keyframe());
            assertEquals(index * 3000L, frames.get(index).packet().pts());
        }
        // A packet whose second AU places itself with a CTS delta of 4500 and skips 88 bits of auxiliary data.
        var writer = new MsbBitWriter(64);
        var first = new byte[]{1, 2, 3};
        var second = new byte[]{4, 5};
        writer.write(2 * (16 + 4 + 1 + 1 + 1 + 3) + 16, 16);
        writer.write(first.length, 16);
        writer.write(0, 4);
        writer.write(0, 1);
        writer.write(0, 1);
        writer.write(1, 1);
        writer.write(0, 3);
        writer.write(second.length, 16);
        writer.write(0, 4);
        writer.write(1, 1);
        writer.write(4500, 16);
        writer.write(0, 1);
        writer.write(0, 1);
        writer.write(0, 3);
        writer.alignWithZeros();
        writer.write(88, 8);
        writer.alignWithZeros();
        writer.writeBytes(new byte[(8 + (88) + 7) / 8 - 1], 0, (8 + (88) + 7) / 8 - 1);
        writer.writeBytes(first, 0, first.length);
        writer.writeBytes(second, 0, second.length);
        var parsed = RtpChannel.depacketize(RtpDepacketizer.create(format, RtpDepacketizerOptions.defaults()),
                List.of(new RtpPacket(true, 96, 1, 0, 1, writer.toByteArray())));
        assertEquals(2, parsed.size());
        assertArrayEquals(first, parsed.get(0).packet().data());
        assertArrayEquals(second, parsed.get(1).packet().data());
        assertEquals(4500, parsed.get(1).packet().pts());
        assertTrue(parsed.get(0).packet().keyframe());
    }

    @Test
    @DisplayName("discards payloads whose AU sizes or header lengths do not add up")
    void rejects() {
        var depacketizer = RtpDepacketizer.create(RtpPayloadFormat.aacHbr(CONFIG),
                RtpDepacketizerOptions.builder().reorderWindow(0).build());
        var frames = new ArrayList<RtpFrame>();
        frames.addAll(depacketizer.push(new RtpPacket(true, 96, 1, 0, 1, new byte[]{0x00, 0x40, 0x00, 0x08, 1})));
        frames.addAll(depacketizer.push(new RtpPacket(true, 96, 2, 1024, 1, new byte[]{0x00})));
        frames.addAll(depacketizer.push(new RtpPacket(true, 96, 3, 2048, 1, new byte[]{0x00, 0x10, 0x00, 0x08})));
        frames.addAll(depacketizer.push(new RtpPacket(true, 96, 4, 3072, 1,
                new byte[]{0x00, 0x20, 0x00, 0x08, 0x00, 0x08, 1, 2, 3})));
        assertEquals(4, depacketizer.discardedPackets());
        // A lone fragment that ends (marker set) short of its AU size is dropped without a unit.
        frames.addAll(depacketizer.push(new RtpPacket(true, 96, 5, 4096, 1, new byte[]{0x00, 0x10, 0x00, 0x18, 1,
                2})));
        frames.addAll(depacketizer.flush());
        assertTrue(frames.isEmpty());
    }

    @Test
    @DisplayName("flags an access unit without AU headers as incomplete when its first fragment is lost")
    void unsizedFragmentLoss() {
        var format = RtpPayloadFormat.fromSdp("mpeg4-generic/48000/2", "streamtype=5; mode=generic; config=1190");
        var units = new ArrayList<RtpChannel.Unit>();
        var random = new Random(8);
        for (var index = 0; index < 3; index++) {
            units.add(new RtpChannel.Unit(RtpChannel.random(random, 3000), index * 1024L, true));
        }
        var sent = RtpChannel.packetize(RtpPacketizer.create(format, RtpChannel.fixed().build()), units);
        assertEquals(List.of(0, 0, 0, 1, 1, 1, 2, 2, 2), sent.owners());
        var frames = RtpChannel.depacketize(RtpDepacketizer.create(format, RtpDepacketizerOptions.defaults()),
                RtpChannel.drop(sent.packets(), Set.of(3)));
        assertEquals(3, frames.size());
        assertArrayEquals(units.get(0).data(), frames.get(0).packet().data());
        assertTrue(frames.get(0).complete());
        assertEquals(1024, frames.get(1).packet().pts());
        assertTrue(!frames.get(1).complete(), "the unit after the loss lacks its start");
        assertArrayEquals(units.get(2).data(), frames.get(2).packet().data());
        assertTrue(frames.get(2).complete());
    }
}
