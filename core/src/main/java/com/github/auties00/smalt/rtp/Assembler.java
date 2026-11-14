package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.track.PacketSideData;

import java.util.Optional;

/**
 * Reassembles the RTP payloads of one payload format into coded units; {@link RtpDepacketizer} orders the packets
 * and converts the timestamps.
 *
 * <p>An assembler may throw {@link com.github.auties00.smalt.MediaException.Malformed} from
 * {@link #accept(RtpPacket, long, boolean)}; the depacketizer then drops the packet and treats it as lost.
 * Instances are not thread-safe.
 */
abstract class Assembler {
    /**
     * Receives the units an assembler completes, in decoding order.
     */
    interface Output {
        /**
         * Adds one unit.
         *
         * @param data the unit in the codec's canonical packet form, owned by the receiver
         * @param timestamp the extended RTP timestamp of the unit
         * @param key whether the unit is a random access point
         * @param complete whether no part of the unit can be missing
         * @param duration the duration in RTP clock units, or a negative value when unknown
         * @param sideData the side data, {@link PacketSideData#NONE} for none
         */
        void add(byte[] data, long timestamp, boolean key, boolean complete, long duration, PacketSideData sideData);
    }

    /** The receiver of the completed units. */
    final Output out;

    /** The size limit of one unit. */
    final long limit;

    Assembler(Output out, long limit) {
        this.out = out;
        this.limit = limit;
    }

    /**
     * Takes the next packet in sequence order.
     *
     * @param packet the next packet in sequence order
     * @param timestamp the packet's extended RTP timestamp
     * @param gap whether packets were lost or dropped between the previous accepted packet and this one
     */
    abstract void accept(RtpPacket packet, long timestamp, boolean gap);

    /**
     * Emits every unit still held, complete or not, and forgets the stream state that depends on continuity.
     */
    abstract void flush();

    /**
     * {@return the out-of-band codec configuration known so far}
     */
    Optional<byte[]> codecConfig() {
        return Optional.empty();
    }

    /**
     * {@return whether output decoding timestamps equal presentation timestamps}
     */
    boolean decodeOrderIsPresentationOrder() {
        return true;
    }
}
