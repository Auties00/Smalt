package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.track.TrackDisposition;

import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Encodes and decodes the descriptors of an MPEG transport stream program map entry that hold the
 * metadata of a track: the ATSC A/65 component name descriptor and the {@code audio_type} of the
 * ISO 639 language descriptor (ISO/IEC 13818-1 clause 2.6.18).
 */
public final class TsDescriptors {
    /**
     * The tag of the ISO 639 language descriptor.
     */
    public static final int LANGUAGE = 0x0A;

    /**
     * The tag of the ATSC component name descriptor.
     */
    public static final int COMPONENT_NAME = 0xA3;

    /**
     * The ISO 639-2 code of an undetermined language.
     */
    public static final String UNDETERMINED = "und";

    /**
     * The largest string one segment of a component name descriptor holds, in bytes: the 255 bytes of a
     * descriptor less the 8 of its string structure.
     */
    private static final int MAX_NAME_BYTES = 247;

    private TsDescriptors() {
        throw new AssertionError();
    }

    /**
     * Returns the {@code audio_type} that stores the dispositions of an audio track.
     *
     * @param disposition the dispositions
     * @return 1 for {@link TrackDisposition#CLEAN_EFFECTS}, 2 for {@link TrackDisposition#HEARING_IMPAIRED}, 3 for
     * {@link TrackDisposition#VISUAL_IMPAIRED}, the first of them in that order, or 0 (undefined)
     */
    public static int audioType(Set<TrackDisposition> disposition) {
        return disposition.contains(TrackDisposition.CLEAN_EFFECTS) ? 1
                : disposition.contains(TrackDisposition.HEARING_IMPAIRED) ? 2
                : disposition.contains(TrackDisposition.VISUAL_IMPAIRED) ? 3 : 0;
    }

    /**
     * Returns the dispositions an {@code audio_type} names.
     *
     * @param audioType the {@code audio_type}, or {@code -1} when the entry has none
     * @return the dispositions, empty for 0, a reserved value or none
     */
    public static Set<TrackDisposition> dispositions(int audioType) {
        return switch (audioType) {
            case 1 -> Set.of(TrackDisposition.CLEAN_EFFECTS);
            case 2 -> Set.of(TrackDisposition.HEARING_IMPAIRED);
            case 3 -> Set.of(TrackDisposition.VISUAL_IMPAIRED);
            default -> Set.of();
        };
    }

    /**
     * Returns whether a language can fill an ISO 639 language descriptor.
     *
     * @param language the language code
     * @return {@code true} for three ASCII characters
     */
    public static boolean isLanguageCode(String language) {
        return language.length() == 3 && language.chars().allMatch(c -> c > 0x20 && c < 0x7F);
    }

    /**
     * Decodes the first string of an ATSC multiple string structure (ATSC A/65 clause 6.10): the
     * segments without compression, in ISO-8859-1 (mode {@code 0x00}) or UTF-16 (mode {@code 0x3F}).
     *
     * @param data the bytes
     * @param offset the index of the structure
     * @param length the length of the structure
     * @return the string, or {@code null} when the structure holds none or it is malformed
     */
    public static String multipleString(byte[] data, int offset, int length) {
        var end = offset + length;
        if (length < 1 || (data[offset] & 0xFF) == 0 || offset + 5 > end) {
            return null;
        }
        var segments = data[offset + 4] & 0xFF;
        var position = offset + 5;
        var text = new StringBuilder();
        for (var segment = 0; segment < segments; segment++) {
            if (position + 3 > end) {
                return null;
            }
            var compression = data[position] & 0xFF;
            var mode = data[position + 1] & 0xFF;
            var count = data[position + 2] & 0xFF;
            position += 3;
            if (position + count > end) {
                return null;
            }
            if (compression == 0 && mode == 0x00) {
                text.append(new String(data, position, count, StandardCharsets.ISO_8859_1));
            } else if (compression == 0 && mode == 0x3F) {
                text.append(new String(data, position, count & ~1, StandardCharsets.UTF_16BE));
            }
            position += count;
        }
        var result = text.toString().strip();
        return result.isEmpty() ? null : result;
    }

    /**
     * Encodes an ATSC component name descriptor (ATSC A/65 clause 6.9.7) of one string in one segment,
     * ISO-8859-1 (mode {@code 0x00}) when the name holds only such characters, UTF-16 (mode {@code 0x3F})
     * otherwise, cut to the 247 bytes a segment of the descriptor holds.
     *
     * @param language the ISO 639-2 language of the string, three ASCII characters
     * @param name the track name
     * @return the descriptor
     */
    public static byte[] componentName(String language, String name) {
        var latin = StandardCharsets.ISO_8859_1.newEncoder().canEncode(name);
        var limit = latin ? MAX_NAME_BYTES : MAX_NAME_BYTES / 2;
        var end = Math.min(name.length(), limit);
        if (end < name.length() && Character.isHighSurrogate(name.charAt(end - 1))) {
            end--;
        }
        var bytes = name.substring(0, end).getBytes(latin ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_16BE);
        var out = new ByteWriter(bytes.length + 10);
        out.u8(COMPONENT_NAME);
        out.u8(8 + bytes.length);
        out.u8(1);
        out.bytes(language.getBytes(StandardCharsets.US_ASCII));
        out.u8(1);
        out.u8(0);
        out.u8(latin ? 0x00 : 0x3F);
        out.u8(bytes.length);
        out.bytes(bytes);
        return out.toByteArray();
    }
}
