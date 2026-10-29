package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.internal.header.XiphLacing;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.Map;

/**
 * Implements the Ogg Theora mapping of the Theora I specification (sections 6.2 and A.2), so that
 * Theora streams are described, timestamped and remuxed; {@code smalt-video} decodes them.
 *
 * <p>The identification header gives the picture size, frame rate, pixel aspect ratio
 * ({@code PARN}/{@code PARD}) and the keyframe granule shift; the three headers are Xiph-laced into
 * the track's configuration. Timestamps count frames in the time base of the inverted frame rate.
 * A granule position splits into the frame number of the last key frame, in its high bits, and the
 * number of frames since that key frame, in its {@code KFGSHIFT} low bits; their sum is the number
 * of frames up to and including the last frame of the page from bitstream version 3.2.1 on, and
 * one less in earlier versions. Every data packet lasts one frame, an empty packet (a dropped frame
 * that repeats the previous one) included, and a data packet is a key frame when its frame type
 * bit is clear.
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

    /**
     * The pixel aspect ratio, or {@code null} when the header leaves it unknown.
     */
    private Rational sampleAspect;

    /**
     * The number of low granule position bits counting the frames since the last key frame.
     */
    private int granuleShift;

    /**
     * The frames a granule position sum lacks before bitstream version 3.2.1: 1 there, 0 from it on.
     */
    private int frameOffset;

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
        if (index == 1) {
            if (metadata != null) {
                try {
                    Annotations.readVorbisComments(packet, 7, packet.length - 7, metadata, annotations);
                } catch (MediaException.Malformed ignored) {
                    // comments are optional; a damaged comment header does not prevent demuxing
                }
            }
            return;
        }
        if (index != 0) {
            return;
        }
        if (packet.length < 42) {
            throw new MediaException.Malformed("Theora identification header truncated");
        }
        frameOffset = legacyGranules(packet) ? 1 : 0;
        width = be24(packet, 14);
        height = be24(packet, 17);
        var numerator = be32(packet, 22);
        var denominator = be32(packet, 26);
        if (numerator > 0 && denominator > 0) {
            frameRate = new Rational(numerator, denominator);
        }
        var aspectWidth = be24(packet, 30);
        var aspectHeight = be24(packet, 33);
        if (aspectWidth > 0 && aspectHeight > 0) {
            sampleAspect = new Rational(aspectWidth, aspectHeight).reduce();
        }
        granuleShift = granuleShift(packet);
    }

    /**
     * Returns the {@code KFGSHIFT} field of a Theora identification header.
     *
     * @param packet the identification header, at least 42 bytes
     * @return the number of low granule position bits, from 0 to 31
     */
    static int granuleShift(byte[] packet) {
        return (packet[40] & 0x03) << 3 | (packet[41] & 0xFF) >>> 5;
    }

    /**
     * Returns whether a Theora identification header precedes bitstream version 3.2.1.
     *
     * @param packet the identification header
     * @return {@code true} when granule positions number frames from 0
     */
    static boolean legacyGranules(byte[] packet) {
        var version = (packet[7] & 0xFF) << 16 | (packet[8] & 0xFF) << 8 | (packet[9] & 0xFF);
        return version < 0x030201;
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
        var builder = VideoTrack.builder().id(id).codec(Codec.THEORA).timeBase(timeBase())
                .duration(duration)
                .codecConfig(headers[2] == null ? null : XiphLacing.lace(headers))
                .width(width)
                .height(height)
                .frameRate(frameRate);
        if (sampleAspect != null) {
            builder.sampleAspectRatio(sampleAspect);
        }
        return builder.build();
    }

    @Override
    Rational timeBase() {
        return frameRate == null ? Rational.MILLISECONDS : frameRate.invert();
    }

    @Override
    boolean timestamped() {
        return frameRate != null;
    }

    @Override
    long packetDuration(byte[] packet) {
        return 1;
    }

    @Override
    long granuleToTime(long granule) {
        return (granule >>> granuleShift) + (granule & ((1L << granuleShift) - 1)) + frameOffset;
    }

    @Override
    long keyframeTime(long granule) {
        return (granule >>> granuleShift) + frameOffset - 1;
    }

    @Override
    long defaultStart() {
        return 0;
    }

    @Override
    boolean isKeyframe(byte[] packet) {
        return packet.length > 0 && (packet[0] & 0x40) == 0;
    }
}
