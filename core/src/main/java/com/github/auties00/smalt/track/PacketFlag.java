package com.github.auties00.smalt.track;

/**
 * Enumerates the properties a container or encoder can attach to a {@link Packet}.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum PacketFlag {
    /**
     * Decoding can start at this packet: it holds a key frame (a random access point) of a video
     * stream, or an audio packet that needs no earlier packet. {@link Packet#keyframe()} reports
     * this flag.
     */
    KEY,

    /**
     * No other packet depends on this one, so a player short of time may drop it without
     * affecting the frames that follow (the Matroska {@code SimpleBlock} discardable flag, a
     * non-reference H.264 picture).
     */
    DISCARDABLE,

    /**
     * The packet must be decoded, because later packets depend on it, but its output must not be
     * presented (the Matroska invisible flag, the pre-roll before a seek target, the decode-only
     * pre-roll of an MP4 edit list segment).
     */
    DISCARD,

    /**
     * The container reported the packet as damaged; it may still be decodable.
     */
    CORRUPT
}
