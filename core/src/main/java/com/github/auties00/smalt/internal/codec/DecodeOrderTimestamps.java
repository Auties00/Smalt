package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;

import java.util.Arrays;

/**
 * Restores the presentation timestamps of the frames of a stream whose packets carry decoding-order
 * timestamps, as AVI stores them.
 *
 * <p>A container without reordering information stamps every packet with its position in decoding
 * order, presentation and decoding timestamp alike, so the frames a decoder of a reordering codec
 * (H.264, HEVC, MPEG-4 Part 2) returns in presentation order carry timestamps out of order. Since the
 * frames come out in presentation order and the packets' timestamps are the frames' times, each frame
 * takes the smallest timestamp of the packets fed and not yet given to a frame. The restoration is
 * active while every packet of the stream has equal presentation and decoding timestamps, which a
 * container that stores reordering never gives a reordered stream; on a stream without reordering it
 * changes nothing. Instances are not thread-safe.
 */
public final class DecodeOrderTimestamps {
    /**
     * The pending timestamps, sorted, in {@code pending[0..count)}.
     */
    private long[] pending = new long[16];

    /**
     * The number of pending timestamps.
     */
    private int count;

    /**
     * Whether the timestamps are being restored.
     */
    private boolean active = true;

    /**
     * Returns the restoration of a stream of a codec.
     *
     * @param codec the codec of the stream
     * @return the restoration, or {@code null} when the codec does not reorder frames
     */
    public static DecodeOrderTimestamps of(Codec codec) {
        return codec == Codec.H264 || codec == Codec.HEVC || codec == Codec.MPEG4_PART2
                ? new DecodeOrderTimestamps() : null;
    }

    private DecodeOrderTimestamps() {
    }

    /**
     * Records a packet fed to the decoder; a packet whose timestamps differ ends the restoration.
     *
     * @param packet the packet, before it is decoded
     */
    public void note(Packet packet) {
        if (!active) {
            return;
        }
        if (packet.pts() == Packet.NO_TIMESTAMP || packet.pts() != packet.dts()) {
            active = false;
            count = 0;
            return;
        }
        if (count == pending.length) {
            pending = Arrays.copyOf(pending, count * 2);
        }
        var at = count;
        while (at > 0 && pending[at - 1] > packet.pts()) {
            pending[at] = pending[at - 1];
            at--;
        }
        pending[at] = packet.pts();
        count++;
    }

    /**
     * Returns the presentation timestamp of a frame the decoder returned.
     *
     * @param pts the timestamp the decoder gave the frame
     * @return the restored timestamp, or {@code pts} when nothing is restored
     */
    public long presentation(long pts) {
        if (!active || count == 0 || pts == Packet.NO_TIMESTAMP) {
            return pts;
        }
        var restored = pending[0];
        System.arraycopy(pending, 1, pending, 0, --count);
        return restored;
    }

    /**
     * Forgets the timestamp of a packet the decoder rejected, which gives no frame.
     *
     * @param pts the presentation timestamp of the packet
     */
    public void discard(long pts) {
        if (!active) {
            return;
        }
        for (var index = 0; index < count; index++) {
            if (pending[index] == pts) {
                System.arraycopy(pending, index + 1, pending, index, --count - index);
                return;
            }
        }
    }

    /**
     * Forgets the pending timestamps, when the decoder is replaced after a seek.
     */
    public void clear() {
        count = 0;
    }
}
