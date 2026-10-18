package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.internal.Annotations;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.util.Map;

/**
 * Interprets the packets of one Ogg logical stream according to its codec's Ogg mapping.
 *
 * <p>A mapping is chosen from the first packet of the stream. It consumes the stream's header
 * packets, describes the stream as a {@link Track}, and, for codecs whose granule positions this
 * demuxer understands, converts granule positions to timestamps and computes packet durations.
 * Instances are stateful (a Vorbis packet's duration depends on the previous packet) and belong
 * to one stream.
 */
abstract sealed class OggMapping permits OpusMapping, VorbisMapping, FlacMapping, SpeexMapping, TheoraMapping, OpaqueMapping {
    /**
     * Receives the chapters and pictures of the stream's comment header, or {@code null} when
     * they are not collected (streams after the first link).
     */
    Annotations annotations;

    /**
     * Forgets the state carried from packet to packet, after the demuxer moved to another page.
     *
     * <p>The default implementation holds no such state and does nothing.
     */
    void reset() {
    }

    /**
     * Chooses the mapping of a stream from its first packet.
     *
     * @param first the first packet of the stream
     * @return a new mapping, an {@link OpaqueMapping} when the codec is not recognized
     */
    static OggMapping detect(byte[] first) {
        if (startsWith(first, 0, "OpusHead")) {
            return new OpusMapping();
        }
        if (first.length >= 7 && first[0] == 0x01 && startsWith(first, 1, "vorbis")) {
            return new VorbisMapping();
        }
        if (first.length >= 5 && first[0] == 0x7F && startsWith(first, 1, "FLAC")) {
            return new FlacMapping();
        }
        if (startsWith(first, 0, "Speex   ")) {
            return new SpeexMapping();
        }
        if (first.length >= 7 && (first[0] & 0xFF) == 0x80 && startsWith(first, 1, "theora")) {
            return new TheoraMapping();
        }
        if (startsWith(first, 0, "fishead\0")) {
            return new OpaqueMapping("skeleton");
        }
        return new OpaqueMapping("unknown");
    }

    /**
     * Returns whether {@code data} holds the ASCII characters of {@code magic} at an offset.
     *
     * @param data the bytes
     * @param offset the index where the magic must start
     * @param magic the expected characters
     * @return {@code true} on a match
     */
    static boolean startsWith(byte[] data, int offset, String magic) {
        if (data.length - offset < magic.length()) {
            return false;
        }
        for (var i = 0; i < magic.length(); i++) {
            if (data[offset + i] != (byte) magic.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns whether a packet is the header packet with the given index.
     *
     * <p>The default accepts exactly the first {@link #headerCount()} packets.
     *
     * @param index the index of the packet in the stream
     * @param packet a packet of the stream, header or data
     * @return {@code true} when the packet is consumed by {@link #header(int, byte[], MediaLimits, Map)}
     */
    boolean isHeader(int index, byte[] packet) {
        return index < headerCount();
    }

    /**
     * Returns the number of header packets that open the stream; the count may grow once the
     * first header has been read.
     *
     * @return the header count
     */
    abstract int headerCount();

    /**
     * Consumes one header packet.
     *
     * @param index the index of the header, from zero
     * @param packet the header packet
     * @param limits the resource bounds
     * @param metadata receives comment tags, or {@code null} when they are not collected
     * @throws com.github.auties00.smalt.MediaException.Malformed if the header is invalid
     */
    abstract void header(int index, byte[] packet, MediaLimits limits, Map<String, String> metadata);

    /**
     * Describes the stream once its headers are consumed.
     *
     * @param id the track identifier
     * @param duration the duration in time-base units, or {@code -1}
     * @param skipSamples the leading samples readers discard
     * @return the track
     */
    abstract Track track(int id, long duration, long skipSamples);

    /**
     * Returns the time base of the stream's timestamps.
     *
     * @return the time base
     */
    abstract Rational timeBase();

    /**
     * Returns whether this mapping assigns timestamps and durations to packets.
     *
     * @return {@code false} by default
     */
    boolean timestamped() {
        return false;
    }

    /**
     * Returns the duration of a data packet and updates any inter-packet state.
     *
     * @param packet the data packet
     * @return the duration in time-base units, zero when unknown
     */
    long packetDuration(byte[] packet) {
        return 0;
    }

    /**
     * Converts a granule position to the timestamp of the end of the last packet completed on
     * its page.
     *
     * @param granule the granule position, not {@code -1}
     * @return the timestamp in time-base units
     */
    long granuleToTime(long granule) {
        return granule;
    }

    /**
     * Returns the timestamp of the first data packet of a stream that starts at granule zero.
     *
     * <p>The default is the time of granule zero; mappings whose first packet decodes to no output
     * start earlier.
     *
     * @return the timestamp in time-base units
     */
    long defaultStart() {
        return granuleToTime(0);
    }

    /**
     * Returns the number of leading samples the codec's decoder drops by itself, such as Opus
     * pre-skip or the output-less first Vorbis packet.
     *
     * @return the decoder delay in time-base units
     */
    long decoderDelay() {
        return 0;
    }

    /**
     * Returns whether a data packet starts a point where decoding can begin.
     *
     * @param packet the data packet
     * @return {@code true} by default
     */
    boolean isKeyframe(byte[] packet) {
        return true;
    }
}
