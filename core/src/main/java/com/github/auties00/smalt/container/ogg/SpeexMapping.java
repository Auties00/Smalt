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
 * Implements the Ogg Speex mapping, so that Speex streams are described, timestamped and remuxed
 * even though this module does not decode Speex.
 *
 * <p>The 80-byte {@code "Speex   "} header gives the sampling rate, channel count, frame size and
 * frames per packet; it is followed by a comment header (a Vorbis comment block without framing
 * bit) and the number of extra headers the header declares. The track's configuration is the
 * identification header. Granule positions count samples at the sampling rate, and every data
 * packet lasts its frames per packet times the frame size; an encoder that subtracts its look-ahead
 * from the granule positions therefore starts the stream before zero, and the track's
 * {@code skipSamples} asks readers to drop the look-ahead.
 */
final class SpeexMapping extends OggMapping {
    /**
     * The largest number of extra headers accepted, which bounds a hostile header count.
     */
    private static final int MAX_EXTRA_HEADERS = 64;

    /**
     * The largest frame size accepted, which bounds a hostile header; Speex frames hold at most 640
     * samples (ultra-wideband).
     */
    private static final int MAX_FRAME_SIZE = 1 << 16;

    /**
     * The largest number of frames per packet accepted.
     */
    private static final int MAX_FRAMES_PER_PACKET = 1 << 8;

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
     * The number of samples of every data packet.
     */
    private int packetSamples;

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
            var frameSize = ByteArrays.i32le(packet, 56);
            var framesPerPacket = ByteArrays.i32le(packet, 64);
            extraHeaders = ByteArrays.i32le(packet, 68);
            if (sampleRate <= 0 || extraHeaders < 0 || extraHeaders > MAX_EXTRA_HEADERS || frameSize < 0
                    || frameSize > MAX_FRAME_SIZE || framesPerPacket < 0 || framesPerPacket > MAX_FRAMES_PER_PACKET) {
                throw new MediaException.Malformed("invalid Speex header");
            }
            LimitChecks.checkChannels(limits, channels);
            packetSamples = frameSize * Math.max(1, framesPerPacket);
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
        return AudioTrack.builder().id(id).codec(Codec.SPEEX).timeBase(timeBase())
                .duration(duration)
                .codecConfig(head)
                .sampleRate(sampleRate)
                .layout(ChannelLayout.defaultFor(channels))
                .skipSamples(skipSamples)
                .build();
    }

    @Override
    Rational timeBase() {
        return Rational.timeBaseOf(sampleRate);
    }

    @Override
    boolean timestamped() {
        return packetSamples > 0;
    }

    @Override
    long packetDuration(byte[] packet) {
        return packetSamples;
    }
}
