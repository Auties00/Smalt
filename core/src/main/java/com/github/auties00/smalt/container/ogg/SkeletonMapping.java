package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Implements the Ogg Skeleton mapping, versions 3 and 4.
 *
 * <p>The {@code fishead} identification packet is the only header and becomes the configuration
 * of a {@link DataTrack} whose handler is {@code "skeleton"}. Every later packet ({@code fisbone},
 * and the version 4 {@code index}) is returned untouched, without timestamps; a {@code fisbone}
 * packet, which describes one other logical stream of the link, is also parsed so that the parser
 * can give that stream its message header fields and, for a stream of an unknown codec, the
 * granule rate and shift.
 */
final class SkeletonMapping extends OggMapping {
    /**
     * The smallest {@code fishead} packet, version 3.
     */
    private static final int FISHEAD_SIZE = 64;

    /**
     * The offset of the message header fields of a {@code fisbone} packet whose offset field
     * says nothing else.
     */
    private static final int FISBONE_FIELDS = 52;

    /**
     * The largest number of message header fields kept from one {@code fisbone} packet.
     */
    private static final int MAX_FIELDS = 64;

    /**
     * The {@code fishead} packet.
     */
    private byte[] head;

    /**
     * The {@code fisbone} packets parsed and not yet taken by {@link #poll()}.
     */
    private final ArrayDeque<Fisbone> parsed = new ArrayDeque<>();

    /**
     * Holds what a {@code fisbone} packet says about one logical stream.
     *
     * @param serial the serial number of the described stream
     * @param headerPackets the number of header packets of that stream
     * @param granuleRate the granules per second, or {@code null} when the rate is not positive
     * @param granuleShift the number of low granule position bits counting packets since the
     *                     last key frame, from 0 to 63
     * @param fields the message header fields, such as {@code Content-Type} or {@code Language},
     *               by name in packet order; unmodifiable
     */
    record Fisbone(int serial, long headerPackets, Rational granuleRate, int granuleShift, Map<String, String> fields) {
    }

    @Override
    int headerCount() {
        return 1;
    }

    @Override
    void header(int index, byte[] packet, MediaLimits limits, Map<String, String> metadata) {
        if (packet.length < FISHEAD_SIZE) {
            throw new MediaException.Malformed("Ogg Skeleton fishead truncated");
        }
        head = packet;
    }

    @Override
    boolean observesData() {
        return true;
    }

    @Override
    void observe(byte[] packet) {
        // TODO: use the keypoints of the version 4 index packets to seek streams without bisecting
        if (packet.length < FISBONE_FIELDS || !startsWith(packet, 0, "fisbone\0")) {
            return;
        }
        var start = 8 + ByteArrays.u32le(packet, 8);
        if (start < FISBONE_FIELDS || start > packet.length) {
            start = FISBONE_FIELDS;
        }
        var numerator = ByteArrays.i64le(packet, 20);
        var denominator = ByteArrays.i64le(packet, 28);
        var rate = numerator > 0 && denominator > 0 ? new Rational(numerator, denominator).reduce() : null;
        var fields = new LinkedHashMap<String, String>();
        var text = new String(packet, (int) start, packet.length - (int) start, StandardCharsets.UTF_8);
        for (var line : text.split("\r\n|\n")) {
            var colon = line.indexOf(':');
            if (colon <= 0 || fields.size() >= MAX_FIELDS) {
                continue;
            }
            var name = line.substring(0, colon).strip();
            var value = line.substring(colon + 1).strip();
            if (!name.isEmpty() && !value.isEmpty()) {
                fields.merge(name, value, (previous, next) -> previous + "; " + next);
            }
        }
        parsed.add(new Fisbone(ByteArrays.i32le(packet, 12), ByteArrays.u32le(packet, 16), rate,
                Math.min(63, packet[48] & 0xFF), Collections.unmodifiableMap(fields)));
    }

    /**
     * Takes the next {@code fisbone} description parsed since the last call.
     *
     * @return the description, or {@code null} when there is none
     */
    Fisbone poll() {
        return parsed.poll();
    }

    @Override
    Track track(int id, long duration, long skipSamples) {
        return DataTrack.builder().id(id).timeBase(timeBase()).handler("skeleton").codecConfig(head).build();
    }

    @Override
    Rational timeBase() {
        return Rational.MILLISECONDS;
    }
}
