package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.track.Packet;

import java.util.Objects;

/**
 * Holds one unit an {@link RtpDepacketizer} reassembled: a video access unit or temporal unit, or one audio frame
 * or frame-block, as a {@link Packet} in the codec's canonical form.
 *
 * <p>Instances are immutable and thread-safe, provided the packet's payload is not modified.
 *
 * @param packet the reassembled unit
 * @param rtpTimestamp the RTP timestamp of the unit, extended to 64 bits so that it keeps increasing across the
 * 32-bit wrap
 * @param complete whether every RTP packet that could have carried part of the unit arrived and parsed; a unit
 * that is not complete may lack NAL units, OBUs, partitions or frames and should be handed to a decoder only if it
 * conceals damage
 */
public record RtpFrame(Packet packet, long rtpTimestamp, boolean complete) {
    /**
     * Validates the fields.
     */
    public RtpFrame {
        Objects.requireNonNull(packet, "packet");
    }
}
