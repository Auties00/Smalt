package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;

/**
 * Packetizes VP9 frames per RFC 9628 in the non-flexible mode with one spatial layer.
 *
 * <p>A superframe is split into its frames; each frame is its own picture with its own picture identifier, all
 * with the timestamp of the input unit. The first packet of a key frame carries the scalability structure with the
 * frame size when the stream has picture identifiers.
 */
final class Vp9Payloader extends Payloader {
    private final int pictureIdBits;
    private int pictureId;

    Vp9Payloader(int maxPayload, int pictureIdBits, int initialPictureId) {
        super(maxPayload);
        this.pictureIdBits = pictureIdBits;
        this.pictureId = pictureIdBits == 0 ? 0 : initialPictureId & ((1 << pictureIdBits) - 1);
    }

    @Override
    void packetize(byte[] data, long timestamp, boolean key, Output out) {
        if (data.length == 0) {
            throw new MediaException.Malformed("empty VP9 frame");
        }
        var sizes = Vp9Frames.superframeSizes(data);
        if (sizes == null) {
            packetizeFrame(data, 0, data.length, timestamp, out);
            return;
        }
        var position = 0;
        for (var size : sizes) {
            if (size > 0) {
                packetizeFrame(data, position, size, timestamp, out);
            }
            position += size;
        }
    }

    private void packetizeFrame(byte[] data, int offset, int length, long timestamp, Output out) {
        var header = Vp9Frames.header(data, offset, length);
        var key = header != null && header.key();
        var predicted = header == null || !(header.key() || header.intraOnly());
        var structure = key && pictureIdBits != 0;
        var sized = structure && header.width() > 0;
        var common = 1 + (pictureIdBits == 0 ? 0 : pictureIdBits == 7 ? 1 : 2);
        var firstExtra = structure ? 1 + (sized ? 4 : 0) : 0;
        var firstRoom = maxPayload - common - firstExtra;
        var room = maxPayload - common;
        var position = offset;
        var end = offset + length;
        var firstSize = Math.min(firstRoom, length);
        var rest = balancedSizes(length - firstSize, room);
        var packets = 1 + (length > firstSize ? rest.length : 0);
        for (var index = 0; index < packets; index++) {
            var size = index == 0 ? firstSize : rest[index - 1];
            var extra = index == 0 ? firstExtra : 0;
            var payload = new byte[common + extra + size];
            var last = index == packets - 1;
            payload[0] = (byte) ((pictureIdBits != 0 ? 0x80 : 0) | (predicted ? 0x40 : 0) | (index == 0 ? 0x08 : 0)
                    | (last ? 0x04 : 0) | (extra > 0 ? 0x02 : 0));
            var at = 1;
            if (pictureIdBits == 7) {
                payload[at++] = (byte) pictureId;
            } else if (pictureIdBits == 15) {
                payload[at++] = (byte) (0x80 | (pictureId >>> 8));
                payload[at++] = (byte) pictureId;
            }
            if (extra > 0) {
                payload[at++] = (byte) (sized ? 0x10 : 0);
                if (sized) {
                    payload[at++] = (byte) (header.width() >>> 8);
                    payload[at++] = (byte) header.width();
                    payload[at++] = (byte) (header.height() >>> 8);
                    payload[at++] = (byte) header.height();
                }
            }
            System.arraycopy(data, position, payload, at, size);
            position += size;
            out.add(payload, last, timestamp);
        }
        if (position != end) {
            throw new IllegalStateException("VP9 fragments do not cover the frame");
        }
        if (pictureIdBits != 0) {
            pictureId = (pictureId + 1) & ((1 << pictureIdBits) - 1);
        }
    }
}
