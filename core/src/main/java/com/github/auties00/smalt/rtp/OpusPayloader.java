package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;

/**
 * Packetizes Opus per RFC 7587: one Opus packet per RTP packet, the marker bit clear.
 */
final class OpusPayloader extends Payloader {
    OpusPayloader(int maxPayload) {
        super(maxPayload);
    }

    @Override
    void packetize(byte[] data, long timestamp, boolean key, Output out) {
        if (data.length == 0) {
            throw new MediaException.Malformed("empty Opus packet");
        }
        if (data.length > maxPayload) {
            throw new MediaException.LimitExceeded("Opus packet of " + data.length + " bytes does not fit a packet");
        }
        out.add(data.clone(), false, timestamp);
    }
}
