package com.github.auties00.smalt.rtp;

/**
 * Splits the coded units of one payload format into RTP payloads; {@link RtpPacketizer} adds the headers.
 *
 * <p>Instances are not thread-safe.
 */
abstract class Payloader {
    /**
     * Receives the payloads a payloader produces, in transmission order.
     */
    interface Output {
        /**
         * Adds one payload with a header extension.
         *
         * @param payload the payload, owned by the receiver
         * @param marker the marker bit
         * @param timestamp the RTP timestamp, not yet reduced modulo {@code 2^32}
         * @param extension the header extension, or {@code null} for none
         */
        void add(byte[] payload, boolean marker, long timestamp, RtpHeaderExtension extension);

        /**
         * Adds one payload without a header extension.
         *
         * @param payload the payload, owned by the receiver
         * @param marker the marker bit
         * @param timestamp the RTP timestamp, not yet reduced modulo {@code 2^32}
         */
        default void add(byte[] payload, boolean marker, long timestamp) {
            add(payload, marker, timestamp, null);
        }
    }

    /** The largest payload size in bytes. */
    final int maxPayload;

    Payloader(int maxPayload) {
        this.maxPayload = maxPayload;
    }

    /**
     * Packetizes one coded unit.
     *
     * @param data the unit in the codec's canonical packet form
     * @param timestamp the RTP timestamp of the unit
     * @param key whether the unit is a random access point
     * @param out the receiver of the payloads
     */
    abstract void packetize(byte[] data, long timestamp, boolean key, Output out);

    /**
     * Sends whatever the payloader holds back for aggregation.
     *
     * @param out the receiver of the payloads
     */
    void flush(Output out) {
    }

    /**
     * Returns the sizes of the fewest fragments of at most {@code room} bytes that split {@code length} bytes as
     * evenly as possible, the first fragments taking the remainder.
     */
    static int[] balancedSizes(int length, int room) {
        var count = Math.max(1, (length + room - 1) / room);
        var sizes = new int[count];
        var base = length / count;
        var extra = length % count;
        for (var index = 0; index < count; index++) {
            sizes[index] = base + (index < extra ? 1 : 0);
        }
        return sizes;
    }
}
