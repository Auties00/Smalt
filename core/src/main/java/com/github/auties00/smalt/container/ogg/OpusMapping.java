package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.internal.header.OpusHeader;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.Collections;
import java.util.Map;

/**
 * Implements the Ogg Opus mapping of RFC 7845.
 *
 * <p>The stream opens with an {@code OpusHead} identification header, which becomes the track's
 * codec configuration, and an {@code OpusTags} comment header. Granule positions count 48 kHz
 * samples including the pre-skip, so a packet's timestamp is its granule-derived start minus the
 * pre-skip: the first packet of a stream that starts at granule zero is stamped
 * {@code -preSkip}, and the decoder's own discarding of the pre-skip lands the first output
 * sample at timestamp zero. Packet durations come from the TOC byte (RFC 6716 section 3.1).
 */
final class OpusMapping extends OggMapping {
    /**
     * The Opus time base, one 48 kHz sample.
     */
    private static final Rational TIME_BASE = Rational.timeBaseOf(48_000);

    /**
     * The identification header.
     */
    private byte[] head;

    /**
     * The channel count.
     */
    private int channels;

    /**
     * The number of samples to discard at the start of decoding.
     */
    private int preSkip;

    /**
     * The channel mapping family.
     */
    private int family;

    @Override
    int headerCount() {
        return 2;
    }

    @Override
    void header(int index, byte[] packet, MediaLimits limits, Map<String, String> metadata) {
        if (index == 0) {
            var header = OpusHeader.parse(packet, limits);
            head = packet;
            channels = header.channels();
            preSkip = header.preSkip();
            family = header.family();
            return;
        }
        if (!startsWith(packet, 0, "OpusTags")) {
            throw new MediaException.Malformed("Ogg Opus stream without an OpusTags header");
        }
        if (metadata != null) {
            try {
                Annotations.readVorbisComments(packet, 8, packet.length - 8, metadata, annotations);
            } catch (MediaException.Malformed ignored) {
                // comments are optional; a damaged comment header does not prevent decoding
            }
        }
    }

    @Override
    Track track(int id, long duration, long skipSamples) {
        return AudioTrack.builder().id(id).codec(Codec.OPUS).timeBase(TIME_BASE).duration(duration).codecConfig(head)
                .sampleRate(48_000)
                .layout(layout(channels, family))
                .skipSamples(skipSamples)
                .build();
    }

    /**
     * Returns the speaker layout of an Opus channel mapping.
     *
     * @param channels the channel count
     * @param family the channel mapping family
     * @return Vorbis channel order for families 0 and 1 up to eight channels, the ambisonic layout for the
     * ambisonic channel counts of families 2 and 3, unknown positions otherwise
     */
    static ChannelLayout layout(int channels, int family) {
        if (family <= 1 && channels <= 8) {
            return VorbisMapping.layout(channels);
        }
        var ambisonic = OpusHeader.ambisonicLayout(channels, family);
        if (ambisonic != null) {
            return ambisonic;
        }
        return new ChannelLayout(Collections.nCopies(channels, Speaker.UNKNOWN));
    }

    @Override
    Rational timeBase() {
        return TIME_BASE;
    }

    @Override
    boolean timestamped() {
        return true;
    }

    @Override
    long packetDuration(byte[] packet) {
        return AudioConfigs.opusPacketSamples(packet);
    }

    @Override
    long granuleToTime(long granule) {
        return granule - preSkip;
    }

    @Override
    long decoderDelay() {
        return preSkip;
    }
}
