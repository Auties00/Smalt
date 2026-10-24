package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts the descriptive chunks of WAV and AIFF files to and from tags and chapters.
 *
 * <p>WAV: the Broadcast Wave {@code bext} chunk (EBU Tech 3285 version 2) becomes the keys {@code description},
 * {@code originator}, {@code originator_reference}, {@code origination_date}, {@code origination_time},
 * {@code time_reference} (in samples since midnight), {@code umid} ({@code 0x} and the hexadecimal digits of its 32
 * bytes, or of all 64 when the last 32 are not zero), {@code loudness_value}, {@code loudness_range},
 * {@code max_true_peak_level}, {@code max_momentary_loudness} and {@code max_short_term_loudness} (in LUFS, LU or
 * dBTP with two decimals, version 2 only, and only when set) and {@code coding_history}; the {@code iXML} and
 * {@code axml} chunks the keys {@code ixml} and {@code axml}, holding the XML text; the {@code chna} chunk (ITU-R
 * BS.2076 and BS.2088) the key {@code chna}, one line per audio identifier holding its track index, UID, track
 * format reference and pack format reference separated by spaces; the {@code smpl} chunk the keys
 * {@code smpl:manufacturer}, {@code smpl:product}, {@code smpl:sample_period} (in nanoseconds),
 * {@code smpl:unity_note}, {@code smpl:pitch_fraction}, {@code smpl:smpte_format}, {@code smpl:smpte_offset}
 * ({@code HH:MM:SS:FF}) and one {@code smpl:loop} per loop (its cue point identifier, type, start, end, fraction
 * and play count separated by spaces). The cue points of the {@code cue } chunk become chapters, titled by the
 * {@code labl} entries of the {@code LIST} chunk of form {@code adtl} and ended by its {@code ltxt} entries (or at
 * the next cue point); a cue point's position is its sample offset in the {@code data} chunk, its play order
 * position for a cue point in another chunk.
 *
 * <p>AIFF: the markers of the {@code MARK} chunk become chapters titled by their names, except the markers the
 * loops of the {@code INST} chunk use; the {@code INST} chunk becomes the keys {@code inst:base_note},
 * {@code inst:detune}, {@code inst:low_note}, {@code inst:high_note}, {@code inst:low_velocity},
 * {@code inst:high_velocity}, {@code inst:gain} (in decibels) and {@code inst:sustain_loop} and
 * {@code inst:release_loop} (its play mode, 0 none, 1 forward, 2 forward and backward, then the sample positions of
 * its two markers, separated by spaces); the texts of the {@code COMT} chunk become {@code comment} values.
 *
 * <p>Writing reverses each mapping; the {@code COMT} texts are not written back, the {@code comment} values going
 * to the AIFF {@code ANNO} chunks instead.
 */
public final class RiffMetadataChunks {
    /**
     * The size of the fixed fields of a {@code bext} chunk, before the coding history.
     */
    private static final int BEXT_FIXED = 602;

    /**
     * The loudness value of a {@code bext} chunk that is not set.
     */
    private static final int LOUDNESS_UNSET = 0x7FFF;

    /**
     * The {@code bext} keys of the text fields, with their sizes.
     */
    private static final String[] BEXT_TEXT = {"description", "originator", "originator_reference",
            "origination_date", "origination_time"};

    private static final int[] BEXT_TEXT_SIZES = {256, 32, 32, 10, 8};

    /**
     * The {@code bext} keys of the version 2 loudness fields, in field order.
     */
    private static final String[] LOUDNESS = {"loudness_value", "loudness_range", "max_true_peak_level",
            "max_momentary_loudness", "max_short_term_loudness"};

    private static final String TIME_REFERENCE = "time_reference";
    private static final String UMID = "umid";
    private static final String CODING_HISTORY = "coding_history";
    private static final String IXML = "ixml";
    private static final String AXML = "axml";
    private static final String CHNA = "chna";

    /**
     * The {@code smpl} keys of the fixed fields, in field order.
     */
    private static final String[] SMPL = {"smpl:manufacturer", "smpl:product", "smpl:sample_period",
            "smpl:unity_note", "smpl:pitch_fraction", "smpl:smpte_format", "smpl:smpte_offset"};

    private static final String SMPL_LOOP = "smpl:loop";

    /**
     * The {@code INST} keys of the byte fields, in field order, then the gain.
     */
    private static final String[] INST = {"inst:base_note", "inst:detune", "inst:low_note", "inst:high_note",
            "inst:low_velocity", "inst:high_velocity", "inst:gain"};

    private static final String SUSTAIN_LOOP = "inst:sustain_loop";
    private static final String RELEASE_LOOP = "inst:release_loop";

    /**
     * The largest number of cue points, markers, loops or comments read from one chunk.
     */
    private static final int MAX_ENTRIES = 4096;

    private RiffMetadataChunks() {
        throw new AssertionError();
    }

    /**
     * Returns whether a key belongs to a chunk this class writes, so that it goes to no text chunk or ID3v2 tag.
     *
     * @param key the normalized key
     * @param aiff whether the file is AIFF
     * @return {@code true} for the keys of the {@code bext}, {@code iXML}, {@code axml}, {@code chna} and
     * {@code smpl} chunks of a WAV file and of the {@code INST} chunk of an AIFF file
     */
    public static boolean isOwned(String key, boolean aiff) {
        var lower = key.toLowerCase(Locale.ROOT);
        if (aiff) {
            return lower.startsWith("inst:") && (List.of(INST).contains(lower) || lower.equals(SUSTAIN_LOOP)
                    || lower.equals(RELEASE_LOOP));
        }
        return List.of(BEXT_TEXT).contains(lower) || List.of(LOUDNESS).contains(lower) || lower.equals(TIME_REFERENCE)
                || lower.equals(UMID) || lower.equals(CODING_HISTORY) || lower.equals(IXML) || lower.equals(AXML)
                || lower.equals(CHNA) || List.of(SMPL).contains(lower) || lower.equals(SMPL_LOOP);
    }

    /**
     * Returns tags without the keys {@link #isOwned} claims.
     *
     * @param tags the tags to filter
     * @param aiff whether the file is AIFF
     * @return the remaining tags
     */
    public static MediaTags withoutOwned(MediaTags tags, boolean aiff) {
        var builder = tags.toBuilder();
        for (var key : tags.keys()) {
            if (isOwned(key, aiff)) {
                builder.remove(key);
            }
        }
        return builder.build();
    }

    /**
     * Decodes a {@code bext} chunk.
     *
     * @param data the chunk payload
     * @param little whether the integers are little-endian (RIFF) rather than big-endian (RIFX)
     * @param out receives the entries
     */
    public static void decodeBext(byte[] data, boolean little, List<MediaTags.Entry> out) {
        if (data.length < BEXT_FIXED) {
            return;
        }
        var offset = 0;
        for (var index = 0; index < BEXT_TEXT.length; index++) {
            add(out, BEXT_TEXT[index], RiffTagFile.text(java.util.Arrays.copyOfRange(data, offset,
                    offset + BEXT_TEXT_SIZES[index])));
            offset += BEXT_TEXT_SIZES[index];
        }
        var low = ByteArrays.u32(data, 338, little);
        var high = ByteArrays.u32(data, 342, little);
        var reference = high << 32 | low;
        if (reference != 0) {
            add(out, TIME_REFERENCE, Long.toUnsignedString(reference));
        }
        var version = ByteArrays.u16(data, 346, little);
        if (version >= 1) {
            var extended = false;
            var any = false;
            for (var index = 348; index < 348 + 64; index++) {
                any |= data[index] != 0;
                extended |= index >= 380 && data[index] != 0;
            }
            if (any) {
                var umid = new StringBuilder("0x");
                for (var index = 348; index < 348 + (extended ? 64 : 32); index++) {
                    umid.append(Character.toUpperCase(Character.forDigit((data[index] >> 4) & 0xF, 16)));
                    umid.append(Character.toUpperCase(Character.forDigit(data[index] & 0xF, 16)));
                }
                add(out, UMID, umid.toString());
            }
        }
        if (version >= 2) {
            for (var index = 0; index < LOUDNESS.length; index++) {
                var value = (short) ByteArrays.u16(data, 412 + 2 * index, little);
                if (value != LOUDNESS_UNSET) {
                    add(out, LOUDNESS[index], hundredths(value));
                }
            }
        }
        if (data.length > BEXT_FIXED) {
            add(out, CODING_HISTORY, RiffTagFile.text(java.util.Arrays.copyOfRange(data, BEXT_FIXED, data.length)));
        }
    }

    /**
     * Encodes the {@code bext} chunk payload of the tags.
     *
     * <p>Text fields are written in UTF-8, cut at a character boundary to their size; the version is 2 with a
     * loudness value, otherwise 1 with a UMID, otherwise 0.
     *
     * @param tags the tags holding the {@code bext} keys
     * @param little whether the integers are little-endian
     * @return the payload, or {@code null} when the tags hold no {@code bext} key
     */
    public static byte[] encodeBext(MediaTags tags, boolean little) {
        var any = false;
        for (var key : BEXT_TEXT) {
            any |= tags.contains(key);
        }
        for (var key : LOUDNESS) {
            any |= tags.contains(key);
        }
        any |= tags.contains(TIME_REFERENCE) || tags.contains(UMID) || tags.contains(CODING_HISTORY);
        if (!any) {
            return null;
        }
        var history = tags.get(CODING_HISTORY).map(text -> text.getBytes(StandardCharsets.UTF_8))
                .orElse(new byte[0]);
        var data = new byte[BEXT_FIXED + history.length];
        var offset = 0;
        for (var index = 0; index < BEXT_TEXT.length; index++) {
            var text = cut(tags.get(BEXT_TEXT[index]).orElse(""), BEXT_TEXT_SIZES[index]);
            System.arraycopy(text, 0, data, offset, text.length);
            offset += BEXT_TEXT_SIZES[index];
        }
        var reference = parseUnsigned(tags.get(TIME_REFERENCE).orElse("0"));
        ByteArrays.put32(data, 338, reference & 0xFFFFFFFFL, little);
        ByteArrays.put32(data, 342, reference >>> 32, little);
        var loudness = false;
        for (var key : LOUDNESS) {
            loudness |= tags.contains(key);
        }
        var umid = tags.get(UMID).map(RiffMetadataChunks::hex).orElse(null);
        ByteArrays.put16(data, 346, loudness ? 2 : umid != null ? 1 : 0, little);
        if (umid != null) {
            System.arraycopy(umid, 0, data, 348, Math.min(64, umid.length));
        }
        if (loudness) {
            for (var index = 0; index < LOUDNESS.length; index++) {
                var value = tags.get(LOUDNESS[index]).map(RiffMetadataChunks::fromHundredths).orElse(LOUDNESS_UNSET);
                ByteArrays.put16(data, 412 + 2 * index, value, little);
            }
        }
        System.arraycopy(history, 0, data, BEXT_FIXED, history.length);
        return data;
    }

    /**
     * Decodes an XML chunk ({@code iXML} or {@code axml}).
     *
     * @param id the chunk identifier
     * @param data the chunk payload
     * @param out receives the entry
     */
    public static void decodeXml(String id, byte[] data, List<MediaTags.Entry> out) {
        add(out, id.equals("iXML") ? IXML : AXML, RiffTagFile.text(data));
    }

    /**
     * Decodes a {@code chna} chunk.
     *
     * @param data the chunk payload
     * @param little whether the integers are little-endian
     * @param out receives the entry
     */
    public static void decodeChna(byte[] data, boolean little, List<MediaTags.Entry> out) {
        if (data.length < 4) {
            return;
        }
        var count = Math.min(ByteArrays.u16(data, 2, little), MAX_ENTRIES);
        var lines = new StringBuilder();
        for (var index = 0; index < count && 4 + (index + 1) * 40 <= data.length; index++) {
            var at = 4 + index * 40;
            var track = ByteArrays.u16(data, at, little);
            var uid = ascii(data, at + 2, 12);
            var trackRef = ascii(data, at + 14, 14);
            var packRef = ascii(data, at + 28, 11);
            if (track == 0 && uid.isEmpty()) {
                continue;
            }
            if (!lines.isEmpty()) {
                lines.append('\n');
            }
            lines.append(track).append(' ').append(uid).append(' ').append(trackRef).append(' ').append(packRef);
        }
        add(out, CHNA, lines.toString());
    }

    /**
     * Encodes the {@code chna} chunk payload of its tag.
     *
     * @param text the tag value, one line per audio identifier
     * @param little whether the integers are little-endian
     * @return the payload
     * @throws IllegalArgumentException if a line does not hold a track index and three references
     */
    public static byte[] encodeChna(String text, boolean little) {
        var lines = text.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
        var data = new byte[4 + lines.size() * 40];
        var tracks = new HashSet<Integer>();
        for (var index = 0; index < lines.size(); index++) {
            var fields = lines.get(index).split("\\s+");
            if (fields.length != 4) {
                throw new IllegalArgumentException("chna line \"" + lines.get(index) + "\" does not hold four fields");
            }
            int track;
            try {
                track = Integer.parseInt(fields[0]);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("chna track index " + fields[0] + " is not a number", exception);
            }
            if (track < 1 || track > 0xFFFF) {
                throw new IllegalArgumentException("chna track index " + track + " is out of range");
            }
            tracks.add(track);
            var at = 4 + index * 40;
            ByteArrays.put16(data, at, track, little);
            putAscii(data, at + 2, 12, fields[1]);
            putAscii(data, at + 14, 14, fields[2]);
            putAscii(data, at + 28, 11, fields[3]);
        }
        ByteArrays.put16(data, 0, tracks.size(), little);
        ByteArrays.put16(data, 2, lines.size(), little);
        return data;
    }

    /**
     * Decodes a {@code smpl} chunk.
     *
     * @param data the chunk payload
     * @param little whether the integers are little-endian
     * @param out receives the entries
     */
    public static void decodeSmpl(byte[] data, boolean little, List<MediaTags.Entry> out) {
        if (data.length < 36) {
            return;
        }
        for (var index = 0; index < SMPL.length; index++) {
            var value = ByteArrays.u32(data, 4 * index, little);
            if (index == 6) {
                var hours = (int) data[24 + (little ? 3 : 0)];
                add(out, SMPL[index], String.format(Locale.ROOT, "%s%02d:%02d:%02d:%02d", hours < 0 ? "-" : "",
                        Math.abs(hours), data[24 + (little ? 2 : 1)] & 0xFF, data[24 + (little ? 1 : 2)] & 0xFF,
                        data[24 + (little ? 0 : 3)] & 0xFF));
            } else {
                add(out, SMPL[index], Long.toString(value));
            }
        }
        var loops = Math.min(ByteArrays.u32(data, 28, little), MAX_ENTRIES);
        for (var index = 0; index < loops && 36 + (index + 1) * 24L <= data.length; index++) {
            var at = 36 + index * 24;
            var fields = new StringBuilder();
            for (var field = 0; field < 6; field++) {
                if (field > 0) {
                    fields.append(' ');
                }
                fields.append(ByteArrays.u32(data, at + 4 * field, little));
            }
            add(out, SMPL_LOOP, fields.toString());
        }
    }

    /**
     * Encodes the {@code smpl} chunk payload of the tags; a missing fixed field is 0.
     *
     * @param tags the tags holding the {@code smpl} keys
     * @param little whether the integers are little-endian
     * @return the payload, or {@code null} when the tags hold no {@code smpl} key
     * @throws IllegalArgumentException if a value is not a number or a loop does not hold six numbers
     */
    public static byte[] encodeSmpl(MediaTags tags, boolean little) {
        var any = tags.contains(SMPL_LOOP);
        for (var key : SMPL) {
            any |= tags.contains(key);
        }
        if (!any) {
            return null;
        }
        var loops = tags.getAll(SMPL_LOOP);
        var data = new byte[36 + loops.size() * 24];
        for (var index = 0; index < SMPL.length; index++) {
            var value = tags.get(SMPL[index]).orElse(null);
            if (value == null) {
                continue;
            }
            if (index == 6) {
                var parts = value.split(":");
                if (parts.length != 4) {
                    throw new IllegalArgumentException("smpl SMPTE offset " + value + " is not HH:MM:SS:FF");
                }
                var bytes = new byte[4];
                for (var part = 0; part < 4; part++) {
                    bytes[part] = (byte) parseInt(parts[part]);
                }
                for (var part = 0; part < 4; part++) {
                    data[24 + (little ? 3 - part : part)] = bytes[part];
                }
            } else {
                ByteArrays.put32(data, 4 * index, parseUnsigned(value) & 0xFFFFFFFFL, little);
            }
        }
        ByteArrays.put32(data, 28, loops.size(), little);
        for (var index = 0; index < loops.size(); index++) {
            var fields = loops.get(index).strip().split("\\s+");
            if (fields.length != 6) {
                throw new IllegalArgumentException("smpl loop \"" + loops.get(index) + "\" does not hold six numbers");
            }
            for (var field = 0; field < 6; field++) {
                ByteArrays.put32(data, 36 + index * 24 + 4 * field, parseUnsigned(fields[field]) & 0xFFFFFFFFL, little);
            }
        }
        return data;
    }

    /**
     * Decodes the chapters of a {@code cue } chunk and its {@code adtl} list.
     *
     * @param cue the {@code cue } chunk payload
     * @param adtl the payload of the {@code LIST} chunk of form {@code adtl}, starting with {@code adtl}, or
     * {@code null}
     * @param little whether the integers are little-endian
     * @param sampleRate the sampling rate, in hertz
     * @param durationMicros the duration that ends the last chapter without a length, or {@code -1}
     * @return the chapters in start order
     */
    public static List<Chapter> decodeCue(byte[] cue, byte[] adtl, boolean little, int sampleRate,
                                          long durationMicros) {
        if (cue.length < 4 || sampleRate <= 0) {
            return List.of();
        }
        var count = Math.min(ByteArrays.u32(cue, 0, little), MAX_ENTRIES);
        var points = new LinkedHashMap<Long, Long>();
        for (var index = 0; index < count && 4 + (index + 1) * 24L <= cue.length; index++) {
            var at = 4 + index * 24;
            var id = ByteArrays.u32(cue, at, little);
            var position = ByteArrays.u32(cue, at + 4, little);
            var chunk = new String(cue, at + 8, 4, StandardCharsets.ISO_8859_1);
            var offset = ByteArrays.u32(cue, at + 20, little);
            points.putIfAbsent(id, chunk.equals("data") ? offset : position);
        }
        var labels = new HashMap<Long, String>();
        var lengths = new HashMap<Long, Long>();
        if (adtl != null) {
            var position = 4;
            while (adtl.length - position >= 8) {
                var id = new String(adtl, position, 4, StandardCharsets.ISO_8859_1);
                var size = ByteArrays.u32(adtl, position + 4, little);
                position += 8;
                if (size > adtl.length - position) {
                    break;
                }
                var body = java.util.Arrays.copyOfRange(adtl, position, position + (int) size);
                position += (int) (size + (size & 1));
                if (body.length < 4) {
                    continue;
                }
                var cueId = ByteArrays.u32(body, 0, little);
                if (id.equals("labl")) {
                    var text = RiffTagFile.text(java.util.Arrays.copyOfRange(body, 4, body.length));
                    if (!text.isEmpty()) {
                        labels.putIfAbsent(cueId, text);
                    }
                } else if (id.equals("ltxt") && body.length >= 8) {
                    lengths.putIfAbsent(cueId, ByteArrays.u32(body, 4, little));
                }
            }
        }
        var ids = new ArrayList<>(points.keySet());
        ids.sort(Comparator.comparingLong(points::get));
        var chapters = new ArrayList<Chapter>(ids.size());
        for (var index = 0; index < ids.size(); index++) {
            var id = ids.get(index);
            var start = micros(points.get(id), sampleRate);
            long end;
            if (lengths.containsKey(id)) {
                end = micros(points.get(id) + lengths.get(id), sampleRate);
            } else if (index + 1 < ids.size()) {
                end = micros(points.get(ids.get(index + 1)), sampleRate);
            } else {
                end = Math.max(start, durationMicros);
            }
            chapters.add(Chapter.of(start, Math.max(start, end), labels.get(id)));
        }
        return chapters;
    }

    /**
     * Encodes the {@code cue } chunk and the {@code LIST adtl} chunk of chapters: cue points numbered from 1 in
     * the {@code data} chunk, a {@code labl} entry for each titled chapter and an {@code ltxt} region entry giving
     * each chapter's length.
     *
     * @param chapters the chapters, in start order
     * @param little whether the integers are little-endian
     * @param sampleRate the sampling rate, in hertz
     * @return the two complete chunks, or an empty list without chapters or rate
     */
    public static List<byte[]> encodeCue(List<Chapter> chapters, boolean little, int sampleRate) {
        if (chapters.isEmpty() || sampleRate <= 0) {
            return List.of();
        }
        var cue = new ByteWriter(4 + chapters.size() * 24);
        u32(cue, chapters.size(), little);
        var adtl = new ByteWriter(64 + chapters.size() * 48);
        adtl.fourCc("adtl");
        for (var index = 0; index < chapters.size(); index++) {
            var chapter = chapters.get(index);
            var start = Math.min(samples(chapter.startMicros(), sampleRate), 0xFFFFFFFFL);
            var length = Math.min(samples(chapter.endMicros(), sampleRate) - start, 0xFFFFFFFFL);
            var id = index + 1L;
            u32(cue, id, little);
            u32(cue, start, little);
            cue.fourCc("data");
            u32(cue, 0, little);
            u32(cue, 0, little);
            u32(cue, start, little);
            if (chapter.title().isPresent()) {
                var text = chapter.title().get().getBytes(StandardCharsets.UTF_8);
                adtl.fourCc("labl");
                u32(adtl, 4 + text.length + 1, little);
                u32(adtl, id, little);
                adtl.bytes(text);
                adtl.u8(0);
                if (((text.length + 1) & 1) != 0) {
                    adtl.u8(0);
                }
            }
            adtl.fourCc("ltxt");
            u32(adtl, 20, little);
            u32(adtl, id, little);
            u32(adtl, Math.max(0, length), little);
            adtl.fourCc("rgn ");
            adtl.zeros(8);
        }
        return List.of(RiffTagFile.chunk(!little, "cue ", cue.toByteArray()),
                RiffTagFile.chunk(!little, "LIST", adtl.toByteArray()));
    }

    /**
     * Decodes the markers of a {@code MARK} chunk.
     *
     * @param data the chunk payload
     * @return the sample position of each marker by identifier, in chunk order, with the names in a second map
     */
    public static Markers decodeMarkers(byte[] data) {
        var positions = new LinkedHashMap<Integer, Long>();
        var names = new HashMap<Integer, String>();
        if (data.length >= 2) {
            var count = Math.min(ByteArrays.u16be(data, 0), MAX_ENTRIES);
            var position = 2;
            for (var index = 0; index < count && data.length - position >= 7; index++) {
                var id = (short) ByteArrays.u16be(data, position);
                var sample = ByteArrays.u32be(data, position + 2);
                var length = data[position + 6] & 0xFF;
                if (position + 7 + length > data.length) {
                    break;
                }
                var name = new String(data, position + 7, length, StandardCharsets.ISO_8859_1).strip();
                position += 7 + length + ((1 + length) & 1);
                positions.putIfAbsent((int) id, sample);
                if (!name.isEmpty()) {
                    names.putIfAbsent((int) id, name);
                }
            }
        }
        return new Markers(positions, names);
    }

    /**
     * Decodes an {@code INST} chunk.
     *
     * @param data the chunk payload
     * @param markers the markers its loops refer to
     * @param out receives the entries
     * @param loopMarkers receives the identifiers of the markers the loops use
     */
    public static void decodeInst(byte[] data, Markers markers, List<MediaTags.Entry> out, Set<Integer> loopMarkers) {
        if (data.length < 20) {
            return;
        }
        add(out, INST[0], Integer.toString(data[0] & 0xFF));
        for (var index = 1; index < 6; index++) {
            add(out, INST[index], Integer.toString(index == 1 ? data[index] : data[index] & 0xFF));
        }
        add(out, INST[6], Integer.toString((short) ByteArrays.u16be(data, 6)));
        loop(data, 8, markers, SUSTAIN_LOOP, out, loopMarkers);
        loop(data, 14, markers, RELEASE_LOOP, out, loopMarkers);
    }

    private static void loop(byte[] data, int offset, Markers markers, String key, List<MediaTags.Entry> out,
                             Set<Integer> loopMarkers) {
        var mode = (short) ByteArrays.u16be(data, offset);
        var begin = (int) (short) ByteArrays.u16be(data, offset + 2);
        var end = (int) (short) ByteArrays.u16be(data, offset + 4);
        var from = markers.positions().get(begin);
        var to = markers.positions().get(end);
        if (mode == 0 || from == null || to == null) {
            return;
        }
        loopMarkers.add(begin);
        loopMarkers.add(end);
        add(out, key, mode + " " + from + " " + to);
    }

    /**
     * Decodes the texts of a {@code COMT} chunk.
     *
     * @param data the chunk payload
     * @param out receives one {@code comment} entry per text
     */
    public static void decodeComments(byte[] data, List<MediaTags.Entry> out) {
        if (data.length < 2) {
            return;
        }
        var count = Math.min(ByteArrays.u16be(data, 0), MAX_ENTRIES);
        var position = 2;
        for (var index = 0; index < count && data.length - position >= 8; index++) {
            var length = ByteArrays.u16be(data, position + 6);
            if (position + 8 + length > data.length) {
                break;
            }
            add(out, StandardTag.COMMENT.key(), RiffTagFile.text(java.util.Arrays.copyOfRange(data, position + 8,
                    position + 8 + length)));
            position += 8 + length + (length & 1);
        }
    }

    /**
     * Returns the chapters of the markers that no loop uses.
     *
     * @param markers the markers of the {@code MARK} chunk
     * @param loopMarkers the identifiers of the markers the loops use
     * @param sampleRate the sampling rate, in hertz
     * @param durationMicros the duration that ends the last chapter, or {@code -1}
     * @return the chapters in start order, each ending where the next starts
     */
    public static List<Chapter> markerChapters(Markers markers, Set<Integer> loopMarkers, double sampleRate,
                                               long durationMicros) {
        if (sampleRate <= 0) {
            return List.of();
        }
        var ids = new ArrayList<Integer>();
        for (var id : markers.positions().keySet()) {
            if (!loopMarkers.contains(id)) {
                ids.add(id);
            }
        }
        ids.sort(Comparator.comparingLong(markers.positions()::get));
        var chapters = new ArrayList<Chapter>(ids.size());
        for (var index = 0; index < ids.size(); index++) {
            var start = Math.round(markers.positions().get(ids.get(index)) * 1e6 / sampleRate);
            var end = index + 1 < ids.size() ? Math.round(markers.positions().get(ids.get(index + 1)) * 1e6 / sampleRate)
                    : Math.max(start, durationMicros);
            chapters.add(Chapter.of(start, Math.max(start, end), markers.names().get(ids.get(index))));
        }
        return chapters;
    }

    /**
     * Encodes the {@code MARK} and {@code INST} chunks: one marker per chapter, named by its title (cut to 255
     * ISO-8859-1 bytes), numbered from 1, then the markers of the loops of the {@code INST} keys.
     *
     * @param chapters the chapters, in start order
     * @param tags the tags holding the {@code INST} keys
     * @param sampleRate the sampling rate, in hertz
     * @return the complete chunks, possibly empty
     * @throws IllegalArgumentException if an {@code INST} value is not a number or a loop does not hold three
     * numbers
     */
    public static List<byte[]> encodeMarkers(List<Chapter> chapters, MediaTags tags, double sampleRate) {
        var markers = new ArrayList<long[]>();
        var names = new ArrayList<String>();
        if (sampleRate > 0) {
            for (var chapter : chapters) {
                markers.add(new long[]{markers.size() + 1, Math.min(0xFFFFFFFFL,
                        Math.round(chapter.startMicros() * sampleRate / 1e6))});
                names.add(chapter.title().orElse(""));
            }
        }
        var any = false;
        for (var key : INST) {
            any |= tags.contains(key);
        }
        any |= tags.contains(SUSTAIN_LOOP) || tags.contains(RELEASE_LOOP);
        byte[] inst = null;
        if (any) {
            inst = new byte[20];
            for (var index = 0; index < 6; index++) {
                inst[index] = (byte) parseInt(tags.get(INST[index]).orElse(index == 0 ? "60" : index == 3 ? "127"
                        : index == 5 ? "127" : index == 4 ? "1" : "0"));
            }
            ByteArrays.put16be(inst, 6, parseInt(tags.get(INST[6]).orElse("0")));
            var loops = new String[]{SUSTAIN_LOOP, RELEASE_LOOP};
            for (var index = 0; index < 2; index++) {
                var value = tags.get(loops[index]).orElse(null);
                if (value == null) {
                    continue;
                }
                var fields = value.strip().split("\\s+");
                if (fields.length != 3) {
                    throw new IllegalArgumentException("AIFF loop \"" + value + "\" does not hold three numbers");
                }
                var at = 8 + 6 * index;
                ByteArrays.put16be(inst, at, parseInt(fields[0]));
                for (var end = 0; end < 2; end++) {
                    var id = markers.size() + 1;
                    markers.add(new long[]{id, parseUnsigned(fields[1 + end]) & 0xFFFFFFFFL});
                    names.add(end == 0 ? "beg loop" : "end loop");
                    ByteArrays.put16be(inst, at + 2 + 2 * end, id);
                }
            }
        }
        var result = new ArrayList<byte[]>();
        if (!markers.isEmpty()) {
            var mark = new ByteWriter(2 + markers.size() * 16);
            mark.u16be(markers.size());
            for (var index = 0; index < markers.size(); index++) {
                mark.u16be((int) markers.get(index)[0]);
                mark.u32be(markers.get(index)[1]);
                var name = names.get(index).getBytes(StandardCharsets.ISO_8859_1);
                var length = Math.min(255, name.length);
                mark.u8(length);
                mark.bytes(name, 0, length);
                if (((1 + length) & 1) != 0) {
                    mark.u8(0);
                }
            }
            result.add(RiffTagFile.chunk(true, "MARK", mark.toByteArray()));
        }
        if (inst != null) {
            result.add(RiffTagFile.chunk(true, "INST", inst));
        }
        return result;
    }

    /**
     * Encodes the WAV chunks of the tags and chapters: {@code bext}, {@code iXML}, {@code axml}, {@code chna},
     * {@code smpl}, then {@code cue } and {@code LIST adtl}.
     *
     * @param tags the tags and chapters to store
     * @param little whether the integers are little-endian
     * @param sampleRate the sampling rate, in hertz, or 0 when unknown, which writes no cue points
     * @return the complete chunks
     * @throws IllegalArgumentException if a {@code chna} or {@code smpl} value is malformed
     */
    public static List<byte[]> waveChunks(MediaTags tags, boolean little, int sampleRate) {
        var result = new ArrayList<byte[]>();
        var bext = encodeBext(tags, little);
        if (bext != null) {
            result.add(RiffTagFile.chunk(!little, "bext", bext));
        }
        tags.get(IXML).ifPresent(xml -> result.add(RiffTagFile.chunk(!little, "iXML",
                xml.getBytes(StandardCharsets.UTF_8))));
        tags.get(AXML).ifPresent(xml -> result.add(RiffTagFile.chunk(!little, "axml",
                xml.getBytes(StandardCharsets.UTF_8))));
        tags.get(CHNA).ifPresent(text -> result.add(RiffTagFile.chunk(!little, "chna", encodeChna(text, little))));
        var smpl = encodeSmpl(tags, little);
        if (smpl != null) {
            result.add(RiffTagFile.chunk(!little, "smpl", smpl));
        }
        result.addAll(encodeCue(tags.chapters(), little, sampleRate));
        return result;
    }

    private static void add(List<MediaTags.Entry> out, String key, String value) {
        if (!value.isBlank()) {
            out.add(new MediaTags.Entry(key, value.strip().replace("\0", "")));
        }
    }

    private static String hundredths(int value) {
        var sign = value < 0 ? "-" : "";
        var magnitude = Math.abs(value);
        return sign + magnitude / 100 + "." + (magnitude % 100 < 10 ? "0" : "") + magnitude % 100;
    }

    private static int fromHundredths(String text) {
        try {
            var value = Math.round(Double.parseDouble(text.strip()) * 100);
            if (value < Short.MIN_VALUE || value >= LOUDNESS_UNSET) {
                throw new IllegalArgumentException("loudness " + text + " is out of range");
            }
            return (int) value;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("loudness " + text + " is not a number", exception);
        }
    }

    private static byte[] hex(String text) {
        var digits = text.strip();
        if (digits.startsWith("0x") || digits.startsWith("0X")) {
            digits = digits.substring(2);
        }
        if (digits.length() % 2 != 0 || digits.length() > 128) {
            throw new IllegalArgumentException("UMID " + text + " is not up to 64 hexadecimal bytes");
        }
        var bytes = new byte[digits.length() / 2];
        for (var index = 0; index < bytes.length; index++) {
            var high = Character.digit(digits.charAt(2 * index), 16);
            var low = Character.digit(digits.charAt(2 * index + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalArgumentException("UMID " + text + " is not hexadecimal");
            }
            bytes[index] = (byte) (high << 4 | low);
        }
        return bytes;
    }

    private static byte[] cut(String text, int size) {
        var bytes = text.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= size) {
            return bytes;
        }
        var end = size;
        while (end > 0 && (bytes[end] & 0xC0) == 0x80) {
            end--;
        }
        return java.util.Arrays.copyOf(bytes, end);
    }

    private static long parseUnsigned(String text) {
        try {
            return Long.parseUnsignedLong(text.strip());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(text + " is not an unsigned number", exception);
        }
    }

    private static int parseInt(String text) {
        try {
            return Integer.parseInt(text.strip());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(text + " is not a number", exception);
        }
    }

    private static String ascii(byte[] data, int offset, int length) {
        var end = offset;
        while (end < offset + length && data[end] != 0) {
            end++;
        }
        return new String(data, offset, end - offset, StandardCharsets.ISO_8859_1).strip();
    }

    private static void putAscii(byte[] data, int offset, int length, String text) {
        var bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        if (bytes.length > length) {
            throw new IllegalArgumentException("chna field " + text + " is longer than " + length + " characters");
        }
        System.arraycopy(bytes, 0, data, offset, bytes.length);
    }

    private static void u32(ByteWriter out, long value, boolean little) {
        if (little) {
            out.u32le(value);
        } else {
            out.u32be(value);
        }
    }

    private static long micros(long samples, int sampleRate) {
        return samples * 1_000_000L / sampleRate;
    }

    private static long samples(long micros, int sampleRate) {
        return Math.multiplyHigh(micros, sampleRate) == 0 && micros * sampleRate >= 0
                ? (micros * sampleRate + 500_000) / 1_000_000 : Math.round(micros / 1e6 * sampleRate);
    }

    /**
     * The markers of a {@code MARK} chunk.
     *
     * @param positions the sample position of each marker, by identifier, in chunk order
     * @param names the name of each named marker, by identifier
     */
    public record Markers(Map<Integer, Long> positions, Map<Integer, String> names) {
    }
}
