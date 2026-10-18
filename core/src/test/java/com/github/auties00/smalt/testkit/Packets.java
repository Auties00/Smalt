package com.github.auties00.smalt.testkit;

import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.track.Packet;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads the packets of a demuxer for tests that compare whole streams.
 */
public final class Packets {
    private Packets() {
        throw new AssertionError();
    }

    /**
     * Returns every remaining packet of a demuxer, of every track, in demuxing order.
     */
    public static List<Packet> readAll(Demuxer demuxer) {
        var packets = new ArrayList<Packet>();
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            packets.add(packet);
        }
        return packets;
    }

    /**
     * Returns every remaining packet of one track of a demuxer, in demuxing order; the packets of
     * the other tracks are read and dropped.
     */
    public static List<Packet> readAll(Demuxer demuxer, int trackId) {
        var packets = new ArrayList<Packet>();
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            if (packet.trackId() == trackId) {
                packets.add(packet);
            }
        }
        return packets;
    }
}
