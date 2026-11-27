package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.util.Map;

/**
 * Implements the Ogg mapping of VP8 ({@code OVP80}), as libvpx's Ogg writer and ffmpeg's demuxer
 * ({@code oggparsevp8.c}) define it, so that VP8 streams are described and timestamped.
 *
 * <p>The stream opens with the stream information header ({@code OVP80}, type 1, version 1: the frame size, the
 * sample aspect ratio and the frame rate) and the comment header ({@code OVP80}, type 2, a Vorbis comment list);
 * the track has no configuration. Timestamps count frames in the time base of the inverted frame rate. The 64-bit
 * granule position holds the frame number of the page's last frame in its high 32 bits, the count of invisible
 * frames in bits 30 and 31 and the distance to the last key frame in bits 3 to 29; as in ffmpeg, a granule
 * position whose invisible count is zero names the frame after its timestamp. A frame lasts one frame when it is
 * shown and none otherwise (the {@code show_frame} bit of the frame tag), and is a key frame when its frame type
 * bit is clear.
 */
final class Vp8Mapping extends OggMapping {
    /**
     * The size of the stream information header.
     */
    private static final int INFO_SIZE = 26;

    /**
     * The frame width.
     */
    private int width;

    /**
     * The frame height.
     */
    private int height;

    /**
     * The frame rate, or {@code null} when the header declares none.
     */
    private Rational frameRate;

    /**
     * The sample aspect ratio, or {@code null} when the header leaves it unknown.
     */
    private Rational sampleAspect;

    /**
     * Returns whether a packet is a header of the mapping.
     *
     * @param packet the packet
     * @return {@code true} for an {@code OVP80} packet
     */
    static boolean isVp8Header(byte[] packet) {
        return startsWith(packet, 0, "OVP80");
    }

    @Override
    int headerCount() {
        return 2;
    }

    @Override
    boolean isHeader(int index, byte[] packet) {
        return index < headerCount() && isVp8Header(packet);
    }

    @Override
    void header(int index, byte[] packet, MediaLimits limits, Map<String, String> metadata) {
        if (packet.length < 7 || !isVp8Header(packet)) {
            throw new MediaException.Malformed("VP8 header " + (index + 1) + " is invalid");
        }
        var type = packet[5] & 0xFF;
        if (type == 0x02) {
            if (packet[6] == 0x20 && metadata != null) {
                try {
                    Annotations.readVorbisComments(packet, 7, packet.length - 7, metadata, annotations);
                } catch (MediaException.Malformed ignored) {
                    // comments are optional; a damaged comment header does not prevent demuxing
                }
            }
            return;
        }
        if (type != 0x01) {
            return;
        }
        if (packet.length < INFO_SIZE) {
            throw new MediaException.Malformed("VP8 stream information header truncated");
        }
        if (packet[6] != 1) {
            throw new MediaException.Unsupported("ogg vp8", "VP8 Ogg mapping version " + packet[6] + "." + packet[7]);
        }
        width = be16(packet, 8);
        height = be16(packet, 10);
        var aspectWidth = be24(packet, 12);
        var aspectHeight = be24(packet, 15);
        if (aspectWidth > 0 && aspectHeight > 0) {
            sampleAspect = new Rational(aspectWidth, aspectHeight).reduce();
        }
        var numerator = (long) be24(packet, 18) << 8 | (packet[21] & 0xFF);
        var denominator = (long) be24(packet, 22) << 8 | (packet[25] & 0xFF);
        if (numerator > 0 && denominator > 0) {
            frameRate = new Rational(numerator, denominator);
        }
    }

    private static int be16(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 8 | (data[offset + 1] & 0xFF);
    }

    private static int be24(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 16 | (data[offset + 1] & 0xFF) << 8 | (data[offset + 2] & 0xFF);
    }

    @Override
    Track track(int id, long duration, long skipSamples) {
        var builder = VideoTrack.builder().id(id).codec(Codec.VP8).timeBase(timeBase()).duration(duration)
                .width(width).height(height).frameRate(frameRate);
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
        return packet.length > 0 ? (packet[0] >> 4) & 1 : 0;
    }

    @Override
    long granuleToTime(long granule) {
        return (granule >>> 32) - (((granule >>> 30) & 3) == 0 ? 1 : 0);
    }

    @Override
    long keyframeTime(long granule) {
        return granuleToTime(granule) - ((granule >>> 3) & 0x07FFFFFF);
    }

    @Override
    long defaultStart() {
        return 0;
    }

    @Override
    boolean isKeyframe(byte[] packet) {
        return packet.length > 0 && (packet[0] & 0x01) == 0;
    }
}
