package com.github.auties00.smalt.container.internal;

import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;

import java.util.Arrays;

/**
 * Remembers the presentation timestamps of the video packets flagged {@link PacketFlag#DISCARD},
 * so that the frames decoded from them, which a decoder returns with the timestamps of their
 * packets and possibly later, are recognized and dropped.
 *
 * <p>A timestamp is remembered once per discarded packet and forgotten by the first frame that
 * carries it, or by a later packet with the same timestamp that is not discarded: a hidden frame
 * that only serves as a reference (a VP8 alternate reference in a packet of its own) decodes to no
 * frame and carries the timestamp of the frame shown after it, which must not be dropped in its
 * place. Instances are not thread-safe.
 */
public final class DiscardedFrames {
    /**
     * The remembered timestamps, unordered, in the first {@link #count} slots.
     */
    private long[] times = new long[8];

    /**
     * The number of remembered timestamps.
     */
    private int count;

    /**
     * Remembers the timestamp of a packet when it is flagged {@link PacketFlag#DISCARD}, and
     * forgets one remembered occurrence of it when the packet is not.
     *
     * @param packet the packet about to be decoded
     */
    public void note(Packet packet) {
        if (packet.pts() == Packet.NO_TIMESTAMP) {
            return;
        }
        if (!packet.flags().contains(PacketFlag.DISCARD)) {
            if (count > 0) {
                drop(packet.pts());
            }
            return;
        }
        if (count == times.length) {
            times = Arrays.copyOf(times, count * 2);
        }
        times[count++] = packet.pts();
    }

    /**
     * Returns whether a decoded frame comes from a discarded packet, and forgets its timestamp if
     * so.
     *
     * @param pts the presentation timestamp of the frame
     * @return {@code true} when the frame must not be presented
     */
    public boolean drop(long pts) {
        for (var index = 0; index < count; index++) {
            if (times[index] == pts) {
                times[index] = times[--count];
                return true;
            }
        }
        return false;
    }

    /**
     * Forgets every timestamp, as after a seek.
     */
    public void clear() {
        count = 0;
    }
}
