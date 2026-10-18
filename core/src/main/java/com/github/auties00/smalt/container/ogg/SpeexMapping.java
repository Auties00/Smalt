package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.Map;

/**
 * Recognizes Ogg Speex streams so they can be reported precisely even though this module does
 * not decode Speex.
 *
 * <p>The 80-byte {@code "Speex   "} header gives the sampling rate and channel count; it is
 * followed by a comment header and the number of extra headers the header declares. The track
 * uses {@link Codec#UNKNOWN_AUDIO} with the identification header as its configuration, and its
 * packets carry no timestamps.
 */
final class SpeexMapping extends OggMapping {
    /**
     * The largest number of extra headers accepted, which bounds a hostile header count.
     */
    private static final int MAX_EXTRA_HEADERS = 64;

    /**
     * The identification header.
     */
    private byte[] head;

    /**
     * The sampling rate.
     */
    private int sampleRate = 1;

    /**
     * The channel count.
     */
    private int channels = 1;

    /**
     * The number of extra headers after the comment header.
     */
    private int extraHeaders;

    @Override
    int headerCount() {
        return 2 + extraHeaders;
    }

    @Override
    void header(int index, byte[] packet, MediaLimits limits, Map<String, String> metadata) {
        if (index == 0) {
            if (packet.length < 80) {
                throw new MediaException.Malformed("Speex header truncated");
            }
            head = packet;
            sampleRate = ByteArrays.i32le(packet, 36);
            channels = ByteArrays.i32le(packet, 48);
            extraHeaders = ByteArrays.i32le(packet, 68);
            if (sampleRate <= 0 || extraHeaders < 0 || extraHeaders > MAX_EXTRA_HEADERS) {
                throw new MediaException.Malformed("invalid Speex header");
            }
            LimitChecks.checkChannels(limits, channels);
            return;
        }
        if (index == 1 && metadata != null) {
            try {
                Annotations.readVorbisComments(packet, 0, packet.length, metadata, annotations);
            } catch (MediaException.Malformed ignored) {
                // comments are optional
            }
        }
    }

    @Override
    Track track(int id, long duration, long skipSamples) {
        return AudioTrack.builder().id(id).codec(Codec.UNKNOWN_AUDIO).timeBase(Rational.timeBaseOf(sampleRate))
                .codecConfig(head)
                .sampleRate(sampleRate)
                .layout(ChannelLayout.defaultFor(channels))
                .build();
    }

    @Override
    Rational timeBase() {
        return Rational.timeBaseOf(sampleRate);
    }
}
