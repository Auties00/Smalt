package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;

/**
 * Packetizes VP8 frames per RFC 7741: one partition-agnostic run of fragments of nearly equal size, each behind a
 * payload descriptor that carries the picture identifier when it has a length.
 */
final class Vp8Payloader extends Payloader {
    private final int pictureIdBits;
    private int pictureId;

    Vp8Payloader(int maxPayload, int pictureIdBits, int initialPictureId) {
        super(maxPayload);
        this.pictureIdBits = pictureIdBits;
        this.pictureId = pictureIdBits == 0 ? 0 : initialPictureId & ((1 << pictureIdBits) - 1);
    }

    @Override
    void packetize(byte[] data, long timestamp, boolean key, Output out) {
        if (data.length == 0) {
            throw new MediaException.Malformed("empty VP8 frame");
        }
        var descriptor = pictureIdBits == 0 ? 1 : pictureIdBits == 7 ? 3 : 4;
        var sizes = balancedSizes(data.length, maxPayload - descriptor);
        var position = 0;
        for (var index = 0; index < sizes.length; index++) {
            var payload = new byte[descriptor + sizes[index]];
            payload[0] = (byte) ((pictureIdBits != 0 ? 0x80 : 0) | (index == 0 ? 0x10 : 0));
            if (pictureIdBits != 0) {
                payload[1] = (byte) 0x80;
                if (pictureIdBits == 7) {
                    payload[2] = (byte) pictureId;
                } else {
                    payload[2] = (byte) (0x80 | (pictureId >>> 8));
                    payload[3] = (byte) pictureId;
                }
            }
            System.arraycopy(data, position, payload, descriptor, sizes[index]);
            position += sizes[index];
            out.add(payload, index == sizes.length - 1, timestamp);
        }
        if (pictureIdBits != 0) {
            pictureId = (pictureId + 1) & ((1 << pictureIdBits) - 1);
        }
    }
}
