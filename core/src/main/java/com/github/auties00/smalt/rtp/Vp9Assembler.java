package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.track.PacketSideData;

import java.util.Arrays;

/**
 * Reassembles VP9 pictures from RFC 9628 payloads in the flexible and non-flexible modes.
 *
 * <p>A frame runs from the packet with the {@code B} bit to the packet with the {@code E} bit. A picture collects
 * the frames of one timestamp and is emitted at the marker bit once its last frame is shown, so that a hidden frame
 * sent with the timestamp of the next shown frame joins it as a superframe; a timestamp change emits whatever the
 * picture holds.
 */
final class Vp9Assembler extends Assembler {
    /** The most frames a superframe index can describe. */
    private static final int MAX_FRAMES = 8;

    private final ByteSink picture;
    private final int[] frameSizes = new int[MAX_FRAMES];
    private int frames;
    private boolean pictureOpen;
    private boolean pictureComplete;
    private long pictureTimestamp;
    private boolean frameOpen;
    private int frameStart;

    Vp9Assembler(Output out, long limit) {
        super(out, limit);
        this.picture = new ByteSink(limit);
    }

    /**
     * Returns the size of the payload descriptor of a payload.
     *
     * @throws MediaException.Malformed if the descriptor is truncated or leaves no data
     */
    static int descriptorSize(byte[] payload) {
        if (payload.length == 0) {
            throw new MediaException.Malformed("empty VP9 payload");
        }
        var flags = payload[0] & 0xFF;
        var flexible = (flags & 0x10) != 0 && (flags & 0x80) != 0;
        var position = 1;
        if ((flags & 0x80) != 0) {
            position += position < payload.length && (payload[position] & 0x80) != 0 ? 2 : 1;
        }
        if ((flags & 0x20) != 0) {
            position += flexible ? 1 : 2;
        }
        if (flexible && (flags & 0x40) != 0) {
            for (var index = 0; ; index++) {
                if (position >= payload.length || index == 3) {
                    throw new MediaException.Malformed("VP9 reference indices are truncated or more than three");
                }
                if ((payload[position++] & 0x01) == 0) {
                    break;
                }
            }
        }
        if ((flags & 0x02) != 0) {
            if (position >= payload.length) {
                throw new MediaException.Malformed("VP9 scalability structure is truncated");
            }
            var structure = payload[position++] & 0xFF;
            if ((structure & 0x10) != 0) {
                position += 4 * ((structure >>> 5) + 1);
            }
            if ((structure & 0x08) != 0) {
                if (position >= payload.length) {
                    throw new MediaException.Malformed("VP9 picture group is truncated");
                }
                var count = payload[position++] & 0xFF;
                for (var index = 0; index < count; index++) {
                    if (position >= payload.length) {
                        throw new MediaException.Malformed("VP9 picture group is truncated");
                    }
                    position += 1 + ((payload[position] >>> 2) & 0x03);
                }
            }
        }
        if (position >= payload.length) {
            throw new MediaException.Malformed("VP9 payload holds no frame data");
        }
        return position;
    }

    /**
     * Returns the spatial layer of a payload whose descriptor has been checked, or {@code -1} when it carries no
     * layer indices.
     */
    static int spatialId(byte[] payload) {
        var flags = payload[0] & 0xFF;
        if ((flags & 0x20) == 0) {
            return -1;
        }
        var position = 1;
        if ((flags & 0x80) != 0) {
            position += (payload[position] & 0x80) != 0 ? 2 : 1;
        }
        return (payload[position] >>> 1) & 0x07;
    }

    @Override
    void accept(RtpPacket packet, long timestamp, boolean gap) {
        var payload = packet.payload();
        var descriptor = descriptorSize(payload);
        var begin = (payload[0] & 0x08) != 0;
        var end = (payload[0] & 0x04) != 0;
        if (gap && pictureOpen) {
            pictureComplete = false;
            if (frameOpen) {
                dropFrame();
            }
        }
        if (pictureOpen && timestamp != pictureTimestamp) {
            if (frameOpen) {
                dropFrame();
            }
            finish();
        }
        if (begin) {
            if (frameOpen) {
                dropFrame();
                pictureComplete = false;
            }
            if (!pictureOpen) {
                pictureOpen = true;
                pictureComplete = !gap || spatialId(payload) <= 0;
                pictureTimestamp = timestamp;
                picture.clear();
                frames = 0;
            }
            frameOpen = true;
            frameStart = picture.size();
        } else if (!frameOpen) {
            if (pictureOpen) {
                pictureComplete = false;
            }
            return;
        }
        picture.write(payload, descriptor, payload.length - descriptor);
        if (end) {
            frameOpen = false;
            if (frames == MAX_FRAMES) {
                picture.truncate(frameStart);
                pictureComplete = false;
            } else {
                frameSizes[frames++] = picture.size() - frameStart;
            }
        }
        if (packet.marker() && !frameOpen && frames > 0) {
            var last = picture.size() - frameSizes[frames - 1];
            var header = Vp9Frames.header(picture.array(), last, frameSizes[frames - 1]);
            if (header == null || header.shown()) {
                finish();
            }
        }
    }

    private void dropFrame() {
        picture.truncate(frameStart);
        frameOpen = false;
        pictureComplete = false;
    }

    private void finish() {
        pictureOpen = false;
        if (frames == 0) {
            return;
        }
        var first = Vp9Frames.header(picture.array(), 0, frameSizes[0]);
        var key = first != null && first.key();
        byte[] data;
        if (frames == 1) {
            data = picture.toByteArray();
        } else {
            var largest = 0;
            for (var index = 0; index < frames; index++) {
                largest = Math.max(largest, frameSizes[index]);
            }
            var magnitude = largest < 0x100 ? 1 : largest < 0x10000 ? 2 : largest < 0x1000000 ? 3 : 4;
            var marker = 0xC0 | ((magnitude - 1) << 3) | (frames - 1);
            var indexSize = 2 + magnitude * frames;
            data = Arrays.copyOf(picture.array(), picture.size() + indexSize);
            var position = picture.size();
            data[position++] = (byte) marker;
            for (var index = 0; index < frames; index++) {
                for (var shift = 0; shift < magnitude; shift++) {
                    data[position++] = (byte) (frameSizes[index] >>> (8 * shift));
                }
            }
            data[position] = (byte) marker;
        }
        frames = 0;
        out.add(data, pictureTimestamp, key, pictureComplete, -1, PacketSideData.NONE);
    }

    @Override
    void flush() {
        if (frameOpen) {
            dropFrame();
        }
        if (pictureOpen) {
            finish();
        }
    }
}
