package com.github.auties00.smalt.container.wav;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.Ambisonics;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.xml.XmlReader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the ambisonic channel layout that the Audio Definition Model metadata of a BW64 file declares: the
 * {@code chna} chunk (ITU-R BS.2088), which names the audio track format or audio channel format of every track,
 * and the {@code axml} chunk (ITU-R BS.2076), whose track, stream and channel formats lead from a track format to
 * the audio channel format of type {@code HOA} that gives the track's component by its order and degree, with its
 * normalization ({@code SN3D} unless the block or its pack names {@code N3D} or {@code FuMa}).
 *
 * <p>A reference that {@code axml} does not define is taken from the common definitions of ITU-R BS.2094: track
 * format {@code AT_0004xxxx_01} carries channel format {@code AC_0004xxxx}, which is the component of ACN
 * {@code xxxx - 0x0001} with SN3D normalization, {@code xxxx - 0x0101} with N3D or {@code xxxx - 0x0201} with
 * Furse-Malham normalization, and {@code AC_00010001} and {@code AC_00010002} are the front left and right speakers.
 *
 * <p>A layout is returned only when every track of the file has one reference that resolves to an HOA component, all
 * of the same normalization and distinct, or to the front left or right speaker of a direct speakers channel, the
 * pair that accompanies a sound field as head-locked stereo; object-based and other speaker programmes give none.
 */
final class AdmLayouts {
    /**
     * The size of a {@code chna} audio identifier entry.
     */
    private static final int CHNA_ENTRY = 40;

    /**
     * The first common definition of an SN3D component, ACN 0; its order 10 set ends at {@code 0x0079}.
     */
    private static final int COMMON_SN3D = 0x0001;

    /**
     * The first common definition of an N3D component, ACN 0.
     */
    private static final int COMMON_N3D = 0x0101;

    /**
     * The first common definition of a Furse-Malham component, ACN 0; its order 3 set ends at {@code 0x0210}.
     */
    private static final int COMMON_FUMA = 0x0201;

    /**
     * The number of components the common definitions give with SN3D and N3D normalization, every component up to
     * order 10.
     */
    private static final int COMMON_COMPONENTS = 121;

    /**
     * The number of Furse-Malham components the common definitions give, every component up to order 3.
     */
    private static final int COMMON_FUMA_COMPONENTS = 16;

    /**
     * The common definition of the front left speaker.
     */
    private static final String COMMON_FRONT_LEFT = "ac_00010001";

    /**
     * The common definition of the front right speaker.
     */
    private static final String COMMON_FRONT_RIGHT = "ac_00010002";

    /**
     * The most track format, stream format and channel format references followed from one track.
     */
    private static final int MAX_HOPS = 4;

    private AdmLayouts() {
    }

    /**
     * The role of one track: an HOA component or a speaker.
     *
     * @param component the ACN, or {@code -1} for a speaker
     * @param normalization the component's normalization, or {@code null} for a speaker
     * @param speaker the speaker, or {@link Speaker#UNKNOWN} for a component
     */
    private record Role(int component, Ambisonics.Normalization normalization, Speaker speaker) {
    }

    /**
     * The formats the {@code axml} chunk defines, keyed by lower-case identifier.
     *
     * @param streams the stream format each track format references
     * @param channels the channel format each stream format references
     * @param roles the role of each channel format
     * @param packNormalizations the normalization each HOA pack format names
     */
    private record Definitions(Map<String, String> streams, Map<String, String> channels, Map<String, Role> roles,
                               Map<String, Ambisonics.Normalization> packNormalizations) {
    }

    /**
     * Returns the layout the ADM metadata of a file declares.
     *
     * @param chna the {@code chna} chunk payload, or {@code null}
     * @param axml the {@code axml} chunk payload, or {@code null}
     * @param channels the channel count of the file
     * @param little whether the {@code chna} integers are little-endian
     * @return the layout, or {@code null} when the metadata declares none this reader maps
     */
    static ChannelLayout layout(byte[] chna, byte[] axml, int channels, boolean little) {
        if (chna == null || chna.length < 4 || channels <= 0) {
            return null;
        }
        var tracks = new String[channels];
        var packs = new String[channels];
        var count = ByteArrays.u16(chna, 2, little);
        for (var index = 0; index < count && 4 + (index + 1) * CHNA_ENTRY <= chna.length; index++) {
            var at = 4 + index * CHNA_ENTRY;
            var track = ByteArrays.u16(chna, at, little);
            if (track == 0) {
                continue;
            }
            if (track > channels) {
                return null;
            }
            var reference = ascii(chna, at + 14, 14);
            if (tracks[track - 1] != null && !tracks[track - 1].equals(reference)) {
                return null;
            }
            tracks[track - 1] = reference;
            packs[track - 1] = ascii(chna, at + 28, 11);
        }
        var definitions = axml == null ? null : definitions(axml);
        var speakers = new ArrayList<Speaker>(channels);
        var components = new ArrayList<Integer>(channels);
        Ambisonics.Normalization normalization = null;
        for (var channel = 0; channel < channels; channel++) {
            if (tracks[channel] == null) {
                return null;
            }
            var role = resolve(tracks[channel], packs[channel], definitions);
            if (role == null) {
                return null;
            }
            if (role.component() < 0) {
                if (speakers.contains(role.speaker())) {
                    return null;
                }
                speakers.add(role.speaker());
                continue;
            }
            if (normalization != null && normalization != role.normalization()
                    || components.contains(role.component())) {
                return null;
            }
            normalization = role.normalization();
            components.add(role.component());
            speakers.add(Speaker.UNKNOWN);
        }
        if (normalization == null) {
            return null;
        }
        try {
            return new ChannelLayout(speakers, Optional.of(new Ambisonics(normalization, components)));
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    /**
     * Resolves the role of a track from its {@code chna} references.
     *
     * @param track the track format or channel format reference
     * @param pack the pack format reference
     * @param definitions the {@code axml} formats, or {@code null}
     * @return the role, or {@code null} when the references lead to no HOA component or front speaker
     */
    private static Role resolve(String track, String pack, Definitions definitions) {
        var id = track.toLowerCase(Locale.ROOT);
        for (var hop = 0; hop < MAX_HOPS && !id.startsWith("ac_"); hop++) {
            String next = null;
            if (definitions != null) {
                next = id.startsWith("at_") ? definitions.streams().get(id)
                        : id.startsWith("as_") ? definitions.channels().get(id) : null;
            }
            if (next == null && id.startsWith("at_") && id.length() == 14 && id.endsWith("_01")) {
                next = "ac_" + id.substring(3, 11);
            }
            if (next == null) {
                return null;
            }
            id = next;
        }
        if (!id.startsWith("ac_")) {
            return null;
        }
        var role = definitions == null ? null : definitions.roles().get(id);
        if (role == null) {
            return common(id);
        }
        if (role.component() >= 0 && role.normalization() == null) {
            var packNormalization = definitions.packNormalizations().get(pack.toLowerCase(Locale.ROOT));
            return new Role(role.component(), packNormalization != null ? packNormalization
                    : Ambisonics.Normalization.SN3D, Speaker.UNKNOWN);
        }
        return role;
    }

    /**
     * Returns the role of a channel format of the common definitions.
     *
     * @param id the lower-case channel format identifier
     * @return the role, or {@code null} for a channel format that is neither an HOA component nor a front speaker
     */
    private static Role common(String id) {
        if (id.equals(COMMON_FRONT_LEFT)) {
            return new Role(-1, null, Speaker.FRONT_LEFT);
        }
        if (id.equals(COMMON_FRONT_RIGHT)) {
            return new Role(-1, null, Speaker.FRONT_RIGHT);
        }
        if (id.length() != 11 || !id.startsWith("ac_0004")) {
            return null;
        }
        int number;
        try {
            number = Integer.parseInt(id.substring(7), 16);
        } catch (NumberFormatException invalid) {
            return null;
        }
        if (number >= COMMON_SN3D && number < COMMON_SN3D + COMMON_COMPONENTS) {
            return new Role(number - COMMON_SN3D, Ambisonics.Normalization.SN3D, Speaker.UNKNOWN);
        }
        if (number >= COMMON_N3D && number < COMMON_N3D + COMMON_COMPONENTS) {
            return new Role(number - COMMON_N3D, Ambisonics.Normalization.N3D, Speaker.UNKNOWN);
        }
        if (number >= COMMON_FUMA && number < COMMON_FUMA + COMMON_FUMA_COMPONENTS) {
            return new Role(number - COMMON_FUMA, Ambisonics.Normalization.FUMA, Speaker.UNKNOWN);
        }
        return null;
    }

    /**
     * Collects the formats an {@code axml} chunk defines.
     *
     * @param axml the chunk payload
     * @return the formats, or {@code null} when the chunk is not well-formed XML
     */
    private static Definitions definitions(byte[] axml) {
        var end = axml.length;
        while (end > 0 && axml[end - 1] == 0) {
            end--;
        }
        XmlReader.Element root;
        try {
            root = XmlReader.parse(new String(axml, 0, end, StandardCharsets.UTF_8));
        } catch (MediaException.Malformed malformed) {
            return null;
        }
        var definitions = new Definitions(new HashMap<>(), new HashMap<>(), new HashMap<>(), new HashMap<>());
        var pending = new ArrayDeque<XmlReader.Element>();
        pending.add(root);
        while (!pending.isEmpty()) {
            var element = pending.poll();
            switch (element.name()) {
                case "audioTrackFormat" -> link(definitions.streams(), element, "audioTrackFormatID",
                        "audioStreamFormatIDRef");
                case "audioStreamFormat" -> link(definitions.channels(), element, "audioStreamFormatID",
                        "audioChannelFormatIDRef");
                case "audioChannelFormat" -> channelFormat(definitions.roles(), element);
                case "audioPackFormat" -> {
                    var id = element.attribute("", "audioPackFormatID");
                    var normalization = normalization(child(element, "normalization"));
                    if (id != null && normalization != null) {
                        definitions.packNormalizations().put(id.toLowerCase(Locale.ROOT), normalization);
                    }
                }
                default -> {
                }
            }
            pending.addAll(element.elements());
        }
        return definitions;
    }

    /**
     * Records the reference a format element makes to another.
     *
     * @param links receives the reference, keyed by the element's lower-case identifier
     * @param element the format element
     * @param idAttribute the attribute holding its identifier
     * @param reference the child element holding the reference
     */
    private static void link(Map<String, String> links, XmlReader.Element element, String idAttribute,
                             String reference) {
        var id = element.attribute("", idAttribute);
        var target = child(element, reference);
        if (id != null && target != null) {
            links.put(id.toLowerCase(Locale.ROOT), target.toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Records the role of an HOA channel format, or of a direct speakers channel format of the front left or right
     * speaker.
     *
     * @param roles receives the role, keyed by the lower-case channel format identifier
     * @param element the {@code audioChannelFormat} element
     */
    private static void channelFormat(Map<String, Role> roles, XmlReader.Element element) {
        var id = element.attribute("", "audioChannelFormatID");
        if (id == null) {
            return;
        }
        var type = element.attribute("", "typeDefinition");
        var label = element.attribute("", "typeLabel");
        XmlReader.Element block = null;
        for (var child : element.elements()) {
            if (child.name().equals("audioBlockFormat")) {
                block = child;
                break;
            }
        }
        if (block == null) {
            return;
        }
        if ("HOA".equals(type) || "0004".equals(label)) {
            var order = integer(child(block, "order"));
            var degree = integer(child(block, "degree"));
            if (order == null || degree == null || order < 0 || order > Ambisonics.MAX_ORDER
                    || Math.abs(degree) > order) {
                return;
            }
            roles.put(id.toLowerCase(Locale.ROOT), new Role(order * (order + 1) + degree,
                    normalization(child(block, "normalization")), Speaker.UNKNOWN));
        } else if ("DirectSpeakers".equals(type) || "0001".equals(label)) {
            var speaker = child(block, "speakerLabel");
            if (speaker == null) {
                return;
            }
            var name = speaker.substring(speaker.lastIndexOf(':') + 1);
            if (name.equals("M+030")) {
                roles.put(id.toLowerCase(Locale.ROOT), new Role(-1, null, Speaker.FRONT_LEFT));
            } else if (name.equals("M-030")) {
                roles.put(id.toLowerCase(Locale.ROOT), new Role(-1, null, Speaker.FRONT_RIGHT));
            }
        }
    }

    /**
     * Returns the normalization an ADM {@code normalization} element names.
     *
     * @param text the element's text, or {@code null}
     * @return the normalization, or {@code null} when absent or unknown
     */
    private static Ambisonics.Normalization normalization(String text) {
        if (text == null) {
            return null;
        }
        return switch (text) {
            case "SN3D" -> Ambisonics.Normalization.SN3D;
            case "N3D" -> Ambisonics.Normalization.N3D;
            case "FuMa", "FUMA" -> Ambisonics.Normalization.FUMA;
            default -> null;
        };
    }

    /**
     * Returns the trimmed text of the first child element of a name.
     *
     * @param element the parent
     * @param name the child's local name
     * @return the text, or {@code null} when there is no such child
     */
    private static String child(XmlReader.Element element, String name) {
        for (var child : element.elements()) {
            if (child.name().equals(name)) {
                return child.text().strip();
            }
        }
        return null;
    }

    /**
     * Parses a decimal integer.
     *
     * @param text the text, or {@code null}
     * @return the value, or {@code null} when absent or not a number
     */
    private static Integer integer(String text) {
        if (text == null) {
            return null;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    /**
     * Reads a NUL-padded ASCII field.
     *
     * @param data the chunk
     * @param offset the field offset
     * @param length the field length
     * @return the field without its padding
     */
    private static String ascii(byte[] data, int offset, int length) {
        var end = offset;
        while (end < offset + length && data[end] != 0) {
            end++;
        }
        return new String(data, offset, end - offset, StandardCharsets.US_ASCII).strip();
    }
}
