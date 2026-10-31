package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.track.PacketSideData;

/**
 * Takes each RFC 7587 payload as one Opus packet, whose duration its table of contents gives.
 */
final class OpusAssembler extends Assembler {
    OpusAssembler(Output out, long limit) {
        super(out, limit);
    }

    @Override
    void accept(RtpPacket packet, long timestamp, boolean gap) {
        var payload = packet.payload();
        if (payload.length == 0) {
            throw new MediaException.Malformed("empty Opus payload");
        }
        if (payload.length > limit) {
            throw new MediaException.Malformed("Opus payload of " + payload.length + " bytes exceeds the limit");
        }
        var samples = AudioConfigs.opusPacketSamples(payload);
        if (samples == 0) {
            throw new MediaException.Malformed("Opus packet with an invalid table of contents");
        }
        out.add(payload.clone(), timestamp, true, true, samples, PacketSideData.NONE);
    }

    @Override
    void flush() {
    }
}
