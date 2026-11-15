package com.github.auties00.smalt.internal.asf;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.tags.Id3v2Tag;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Maps the descriptive objects of an ASF header to tags, pictures and chapters, and encodes the
 * {@code WM/Picture} attribute.
 *
 * <p>The file tags are the Content Description fields, then the Extended Content Description descriptors, then the
 * records of the Metadata and Metadata Library Objects that describe the whole file (stream 0); a name and value
 * already read are not repeated. Attribute names become the {@link StandardTag} keys their
 * {@linkplain StandardTag#asfName() ASF names} denote, and other names are kept. Numbers become decimal text and
 * Booleans {@code 1} or {@code 0}; byte arrays other than pictures are not tags. {@code WM/Track}, the zero-based
 * track number of old files, gives the track when no {@code WM/TrackNumber} is present. An ID3v2 tag stored in an
 * {@code ID3} byte array attribute, as some encoders keep the tag of the MP3 file they converted, gives the keys and
 * the pictures the ASF attributes lack; a corrupt one is ignored.
 */
public final class AsfTags {
    /**
     * The name of the picture attribute.
     */
    public static final String PICTURE = "WM/Picture";

    /**
     * The name of the attribute holding an ID3v2 tag.
     */
    public static final String ID3 = "ID3";

    /**
     * The name of the zero-based track number attribute.
     */
    private static final String ZERO_BASED_TRACK = "WM/Track";

    /**
     * The constants by upper-case ASF attribute name.
     */
    private static final Map<String, StandardTag> BY_ASF_NAME = byAsfName();

    private AsfTags() {
        throw new AssertionError();
    }

    /**
     * Builds the table of constants by ASF attribute name.
     *
     * @return the table
     */
    private static Map<String, StandardTag> byAsfName() {
        var result = new HashMap<String, StandardTag>();
        for (var tag : StandardTag.values()) {
            result.putIfAbsent(tag.asfName().toUpperCase(Locale.ROOT), tag);
        }
        result.put("WM/TOOLNAME", StandardTag.ENCODER);
        return Map.copyOf(result);
    }

    /**
     * Returns the tag key of an ASF attribute name.
     *
     * @param name the attribute name
     * @return the normalized key of the standard tag the name denotes, or the name
     */
    public static String key(String name) {
        var tag = BY_ASF_NAME.get(name.toUpperCase(Locale.ROOT));
        return tag != null ? tag.key() : name;
    }

    /**
     * Returns whether an attribute is one the file tags are read from: a text, number or Boolean attribute of the
     * whole file, or a picture.
     *
     * @param attribute the descriptor or record
     * @return {@code true} when {@link #read(AsfHeader, MediaTags.Builder, MediaLimits)} takes it
     */
    public static boolean isTag(AsfHeader.Attribute attribute) {
        if (attribute.stream() != 0) {
            return false;
        }
        if (attribute.name().equals(PICTURE) || attribute.name().equals(ID3)) {
            return attribute.type() == AsfHeader.BYTES;
        }
        return attribute.type() != AsfHeader.BYTES && attribute.type() != AsfHeader.GUID;
    }

    /**
     * Adds the file tags and pictures of a header.
     *
     * @param header the parsed header
     * @param builder receives the entries and pictures
     * @param limits the resource bounds of an embedded ID3v2 tag
     */
    public static void read(AsfHeader header, MediaTags.Builder builder, MediaLimits limits) {
        var seen = new HashSet<String>();
        var pictures = new ArrayList<AttachedPicture>();
        var hasTrackNumber = false;
        String zeroBasedTrack = null;
        var id3 = new ArrayList<byte[]>();
        var groups = List.of(header.contentDescription(), header.extendedContent(), header.metadata(),
                header.metadataLibrary());
        for (var group : groups) {
            for (var attribute : group) {
                if (!isTag(attribute)) {
                    continue;
                }
                if (attribute.name().equals(ID3)) {
                    id3.add(attribute.value());
                    continue;
                }
                if (attribute.name().equals(PICTURE)) {
                    var picture = picture(attribute.value());
                    if (picture != null && !pictures.contains(picture)) {
                        pictures.add(picture);
                        builder.addPicture(picture);
                    }
                    continue;
                }
                var text = AsfHeader.text(attribute);
                if (text == null || text.isEmpty()) {
                    continue;
                }
                if (attribute.name().equalsIgnoreCase(ZERO_BASED_TRACK)) {
                    zeroBasedTrack = zeroBasedTrack == null ? text : zeroBasedTrack;
                    continue;
                }
                var key = key(attribute.name());
                if (key.equals(StandardTag.TRACK.key())) {
                    hasTrackNumber = true;
                }
                if (seen.add(key.toLowerCase(Locale.ROOT) + '\0' + text)) {
                    builder.add(key, text);
                }
            }
        }
        var keys = new HashSet<String>();
        for (var entry : builder.build().entries()) {
            keys.add(entry.key().toLowerCase(Locale.ROOT));
        }
        for (var tag : id3) {
            Id3v2Tag.Content content;
            try {
                content = Id3v2Tag.parse(tag, limits).decode();
            } catch (MediaException.Malformed exception) {
                continue;
            }
            for (var entry : content.entries()) {
                if (!keys.contains(entry.key().toLowerCase(Locale.ROOT))) {
                    builder.add(entry.key(), entry.value());
                    hasTrackNumber |= entry.key().equals(StandardTag.TRACK.key());
                }
            }
            if (pictures.isEmpty()) {
                for (var picture : content.pictures()) {
                    pictures.add(picture);
                    builder.addPicture(picture);
                }
            }
        }
        if (!hasTrackNumber && zeroBasedTrack != null) {
            try {
                builder.add(StandardTag.TRACK, Long.toString(Long.parseLong(zeroBasedTrack) + 1));
            } catch (NumberFormatException exception) {
                builder.add(ZERO_BASED_TRACK, zeroBasedTrack);
            }
        }
    }

    /**
     * Decodes a {@code WM/Picture} value: a picture type byte, the data length, the NUL-terminated UTF-16LE media
     * type and description, and the image bytes.
     *
     * @param value the attribute value
     * @return the picture, or {@code null} when the value is malformed or holds no image
     */
    public static AttachedPicture picture(byte[] value) {
        if (value.length < 5) {
            return null;
        }
        var type = value[0] & 0xFF;
        var length = ByteArrays.u32le(value, 1);
        var position = 5;
        var mediaEnd = terminator(value, position);
        if (mediaEnd < 0) {
            return null;
        }
        var mediaType = new String(value, position, mediaEnd - position, StandardCharsets.UTF_16LE);
        position = mediaEnd + 2;
        var descriptionEnd = terminator(value, position);
        if (descriptionEnd < 0) {
            return null;
        }
        var description = new String(value, position, descriptionEnd - position, StandardCharsets.UTF_16LE);
        position = descriptionEnd + 2;
        if (length == 0 || length > value.length - position) {
            return null;
        }
        var data = Arrays.copyOfRange(value, position, position + (int) length);
        return AttachedPicture.of(AttachedPicture.Type.of(type), mediaType, description, data);
    }

    /**
     * Encodes a picture as a {@code WM/Picture} value.
     *
     * @param picture the picture to store
     * @return the value bytes
     */
    public static byte[] picture(AttachedPicture picture) {
        var data = picture.data();
        var out = new ByteWriter(data.length + 64);
        out.u8(picture.type().code());
        out.u32le(data.length);
        out.bytes(nulTerminated(picture.mediaType()));
        out.bytes(nulTerminated(picture.description()));
        out.bytes(data);
        return out.toByteArray();
    }

    /**
     * Encodes text as NUL-terminated UTF-16LE.
     *
     * @param text the string to encode
     * @return the bytes, the two-byte terminator included
     */
    public static byte[] nulTerminated(String text) {
        var bytes = text.getBytes(StandardCharsets.UTF_16LE);
        return Arrays.copyOf(bytes, bytes.length + 2);
    }

    /**
     * Returns the index of the UTF-16 NUL character at or after an even offset.
     *
     * @param value the bytes
     * @param from the index of the first character
     * @return the index of the terminator, or {@code -1} when there is none
     */
    private static int terminator(byte[] value, int from) {
        for (var position = from; position + 1 < value.length; position += 2) {
            if (value[position] == 0 && value[position + 1] == 0) {
                return position;
            }
        }
        return -1;
    }

    /**
     * Returns the chapters of the markers: each starts at its marker's presentation time and lasts until the next
     * marker or the end of the presentation.
     *
     * @param header the parsed header
     * @param durationMicros the presentation duration, or {@code -1} when unknown
     * @return the chapters, in time order
     */
    public static List<Chapter> chapters(AsfHeader header, long durationMicros) {
        var markers = new ArrayList<>(header.markers());
        markers.sort((a, b) -> Long.compare(a.time(), b.time()));
        var preroll = header.properties().preroll() * 1000;
        var chapters = new ArrayList<Chapter>(markers.size());
        for (var index = 0; index < markers.size(); index++) {
            var marker = markers.get(index);
            var start = Math.max(0, marker.time() / 10 - preroll);
            long end;
            if (index + 1 < markers.size()) {
                end = Math.max(start, markers.get(index + 1).time() / 10 - preroll);
            } else {
                end = Math.max(start, durationMicros);
            }
            var title = marker.name().isEmpty() ? Optional.<String>empty() : Optional.of(marker.name());
            chapters.add(new Chapter(start, end, title));
        }
        return List.copyOf(chapters);
    }
}
