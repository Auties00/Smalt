package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.header.XiphLacing;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.Map;
import java.util.Optional;

/**
 * Recognizes Ogg Theora streams so they can be reported precisely even though this module does
 * not decode Theora.
 *
 * <p>The identification header gives the picture size and frame rate; the three headers are
 * Xiph-laced into the track's configuration. The track uses {@link Codec#UNKNOWN_VIDEO}, its
 * packets carry no timestamps, and a data packet is a keyframe when its frame type bit is clear.
 */
final class TheoraMapping extends OggMapping {
    /**
     * The three header packets.
     */
    private final byte[][] headers = new byte[3][];

    /**
     * The visible picture width.
     */
    private int width;

    /**
     * The visible picture height.
     */
    private int height;

    /**
     * The frame rate, or {@code null} when the header declares none.
     */
    private Rational frameRate;

    @Override
    int headerCount() {
        return 3;
    }

    @Override
    void header(int index, byte[] packet, MediaLimits limits, Map<String, String> metadata) {
        if (packet.length < 7 || (packet[0] & 0xFF) != 0x80 + index || !startsWith(packet, 1, "theora")) {
            throw new MediaException.Malformed("Theora header " + (index + 1) + " is invalid");
        }
        headers[index] = packet;
        if (index != 0) {
            return;
        }
        if (packet.length < 42) {
            throw new MediaException.Malformed("Theora identification header truncated");
        }
        width = be24(packet, 14);
        height = be24(packet, 17);
        var numerator = be32(packet, 22);
        var denominator = be32(packet, 26);
        if (numerator > 0 && denominator > 0) {
            frameRate = new Rational(numerator, denominator);
        }
    }

    /**
     * Reads an unsigned big-endian 24-bit value.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @return the value
     */
    private static int be24(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 16 | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF);
    }

    /**
     * Reads an unsigned big-endian 32-bit value.
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @return the value
     */
    private static long be32(byte[] data, int offset) {
        return (long) be24(data, offset) << 8 | (data[offset + 3] & 0xFF);
    }

    @Override
    Track track(int id, long duration, long skipSamples) {
        var config = headers[2] == null ? Optional.<byte[]>empty() : Optional.of(XiphLacing.lace(headers));
        return VideoTrack.builder().id(id).codec(Codec.UNKNOWN_VIDEO).timeBase(timeBase())
                .codecConfig(config.orElse(null))
                .width(width)
                .height(height)
                .frameRate(frameRate)
                .build();
    }

    @Override
    Rational timeBase() {
        return frameRate == null ? Rational.MILLISECONDS : frameRate.invert();
    }

    @Override
    boolean isKeyframe(byte[] packet) {
        return packet.length > 0 && (packet[0] & 0x40) == 0;
    }
}
