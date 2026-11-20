package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.PacketSideData;

/**
 * Reassembles VP8 frames from RFC 7741 payloads: a frame starts at the packet whose {@code S} bit is set with
 * partition index 0 and ends at the marker bit.
 */
final class Vp8Assembler extends Assembler {
    private final ByteSink frame;
    private boolean open;
    private boolean complete;
    private boolean key;
    private long frameTimestamp;

    Vp8Assembler(Output out, long limit) {
        super(out, limit);
        this.frame = new ByteSink(limit);
    }

    /**
     * Returns the size of the payload descriptor of a payload.
     *
     * @throws MediaException.Malformed if the descriptor is truncated or leaves no data
     */
    static int descriptorSize(byte[] payload) {
        if (payload.length == 0) {
            throw new MediaException.Malformed("empty VP8 payload");
        }
        var position = 1;
        if ((payload[0] & 0x80) != 0) {
            if (payload.length < 2) {
                throw new MediaException.Malformed("VP8 payload descriptor is truncated");
            }
            var extension = payload[1] & 0xFF;
            position = 2;
            if ((extension & 0x80) != 0) {
                if (position >= payload.length) {
                    throw new MediaException.Malformed("VP8 picture identifier is truncated");
                }
                position += (payload[position] & 0x80) != 0 ? 2 : 1;
            }
            if ((extension & 0x40) != 0) {
                position++;
            }
            if ((extension & 0x30) != 0) {
                position++;
            }
        }
        if (position >= payload.length) {
            throw new MediaException.Malformed("VP8 payload holds no frame data");
        }
        return position;
    }

    @Override
    void accept(RtpPacket packet, long timestamp, boolean gap) {
        var payload = packet.payload();
        var descriptor = descriptorSize(payload);
        var start = (payload[0] & 0x10) != 0 && (payload[0] & 0x07) == 0;
        if (open && (start || timestamp != frameTimestamp)) {
            complete = false;
            finish();
        }
        if (gap && open) {
            complete = false;
        }
        if (start) {
            open = true;
            complete = true;
            key = (payload[descriptor] & 0x01) == 0;
            frameTimestamp = timestamp;
            frame.clear();
        } else if (!open) {
            return;
        }
        frame.write(payload, descriptor, payload.length - descriptor);
        if (packet.marker()) {
            finish();
        }
    }

    private void finish() {
        open = false;
        out.add(frame.toByteArray(), frameTimestamp, key, complete, -1, PacketSideData.NONE);
    }

    @Override
    void flush() {
        if (open) {
            complete = false;
            finish();
        }
    }
}
