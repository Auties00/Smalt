package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.track.Packet;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Test helpers that packetize units, pass the packets through a simulated network (loss, reordering within a
 * bounded distance, duplication) and depacketize them.
 */
final class RtpChannel {
    private RtpChannel() {
        throw new AssertionError();
    }

    /** Options with every random field fixed, so packetization is reproducible. */
    static RtpPacketizerOptions.Builder fixed() {
        return RtpPacketizerOptions.builder().ssrc(0x1234_5678L).initialSequenceNumber(65_530)
                .initialTimestamp(0xFFFF_F000L);
    }

    /** One unit to packetize: payload, timestamp in clock units and key flag. */
    record Unit(byte[] data, long timestamp, boolean key) {
        Packet packet() {
            return new Packet(1, data, timestamp, timestamp, Packet.UNKNOWN_DURATION, key);
        }
    }

    /** The packets of each unit, in transmission order, with the unit each one belongs to. */
    record Sent(List<RtpPacket> packets, List<Integer> owners) {
    }

    static Sent packetize(RtpPacketizer packetizer, List<Unit> units) {
        var packets = new ArrayList<RtpPacket>();
        var owners = new ArrayList<Integer>();
        for (var index = 0; index < units.size(); index++) {
            for (var packet : packetizer.packetize(units.get(index).packet())) {
                packets.add(packet);
                owners.add(index);
            }
        }
        for (var packet : packetizer.flush()) {
            packets.add(packet);
            owners.add(units.size() - 1);
        }
        return new Sent(packets, owners);
    }

    static List<RtpFrame> depacketize(RtpDepacketizer depacketizer, List<RtpPacket> packets) {
        var frames = new ArrayList<RtpFrame>();
        for (var packet : packets) {
            frames.addAll(depacketizer.push(packet));
        }
        frames.addAll(depacketizer.flush());
        return frames;
    }

    /** Wires a packet list through serialization, so the header layout is exercised too. */
    static List<RtpPacket> overTheWire(List<RtpPacket> packets) {
        var out = new ArrayList<RtpPacket>(packets.size());
        for (var packet : packets) {
            out.add(RtpPacket.parse(packet.toByteArray()));
        }
        return out;
    }

    /**
     * Reorders packets so that none moves by more than {@code distance} places from its sent position.
     */
    static List<RtpPacket> reorder(List<RtpPacket> packets, int distance, long seed) {
        var random = new Random(seed);
        var out = new ArrayList<>(packets);
        for (var start = 0; start < out.size(); start += distance + 1) {
            var end = Math.min(out.size(), start + distance + 1);
            Collections.shuffle(out.subList(start, end), random);
        }
        return out;
    }

    /** Duplicates every {@code every}-th packet, sending the copy two places later. */
    static List<RtpPacket> duplicate(List<RtpPacket> packets, int every) {
        var out = new ArrayList<>(packets);
        for (var index = packets.size() - 1; index >= 0; index -= every) {
            out.add(Math.min(out.size(), index + 3), packets.get(index));
        }
        return out;
    }

    /** Drops the packets at the given positions. */
    static List<RtpPacket> drop(List<RtpPacket> packets, Set<Integer> positions) {
        var out = new ArrayList<RtpPacket>();
        for (var index = 0; index < packets.size(); index++) {
            if (!positions.contains(index)) {
                out.add(packets.get(index));
            }
        }
        return out;
    }

    /** Picks {@code count} distinct random positions below {@code size}. */
    static Set<Integer> randomPositions(int size, int count, long seed) {
        var random = new Random(seed);
        var positions = new HashSet<Integer>();
        while (positions.size() < Math.min(count, size)) {
            positions.add(random.nextInt(size));
        }
        return positions;
    }

    static byte[] random(Random random, int length) {
        var data = new byte[length];
        random.nextBytes(data);
        return data;
    }
}
