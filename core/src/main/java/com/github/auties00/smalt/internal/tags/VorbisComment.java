package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Reads and writes Vorbis comments, the tag format of Ogg Vorbis, Opus, Speex and FLAC.
 *
 * <p>A comment block is a little-endian length-prefixed vendor string, a field count, and that
 * many length-prefixed {@code NAME=value} UTF-8 fields. Field names are case-insensitive ASCII.
 * Beyond plain text, the fields carry pictures ({@code METADATA_BLOCK_PICTURE}, a base64 FLAC
 * picture block), chapters ({@code CHAPTER001=00:00:00.000} with {@code CHAPTER001NAME}), and
 * track and disc totals ({@code TRACKTOTAL}/{@code TOTALTRACKS}, {@code DISCTOTAL}/{@code
 * TOTALDISCS}), which {@link #decode(List, long)} folds into the {@code n/total} values of
 * {@link StandardTag#TRACK} and {@link StandardTag#DISC}.
 */
public final class VorbisComment {
    /**
     * The field that holds a base64 FLAC picture block.
     */
    public static final String PICTURE = "METADATA_BLOCK_PICTURE";

    /**
     * The vendor string written when a file has none.
     */
    public static final String DEFAULT_VENDOR = "Smalt";

    /**
     * The largest number of chapters a comment block may number.
     */
    private static final int MAX_CHAPTERS = 999;

    /**
     * Matches a chapter clock: hours, minutes, seconds and an optional fraction of a second.
     */
    private static final Pattern CLOCK = Pattern.compile("(\\d{1,6}):(\\d{1,2}):(\\d{1,2})(?:\\.(\\d{1,9}))?");
    private VorbisComment() {
        throw new AssertionError();
    }

    /**
     * Parses a comment block.
     *
     * @param data the bytes
     * @param offset the index of the vendor length
     * @param length the number of bytes of the block available
     * @return the parsed block
     * @throws MediaException.Malformed if a length runs past the block
     */
    public static Parsed parse(byte[] data, int offset, int length) {
        var end = offset + length;
        var position = offset;
        var vendorLength = lengthAt(data, position, end);
        position += 4;
        if (vendorLength > end - position) {
            throw new MediaException.Malformed("Vorbis comment vendor string of " + vendorLength + " bytes exceeds the block");
        }
        var vendor = new String(data, position, (int) vendorLength, StandardCharsets.UTF_8);
        position += (int) vendorLength;
        var count = lengthAt(data, position, end);
        position += 4;
        if (count > (end - position) / 4) {
            throw new MediaException.Malformed("Vorbis comment count " + count + " exceeds the block");
        }
        var fields = new ArrayList<String[]>((int) count);
        for (var i = 0L; i < count; i++) {
            var fieldLength = lengthAt(data, position, end);
            position += 4;
            if (fieldLength > end - position) {
                throw new MediaException.Malformed("Vorbis comment of " + fieldLength + " bytes exceeds the block");
            }
            var field = new String(data, position, (int) fieldLength, StandardCharsets.UTF_8);
            position += (int) fieldLength;
            var separator = field.indexOf('=');
            if (separator > 0) {
                fields.add(new String[]{field.substring(0, separator), field.substring(separator + 1)});
            }
        }
        return new Parsed(vendor, fields, position);
    }

    /**
     * Decodes the fields of a block into tags, pictures and chapters.
     *
     * @param fields the {@code NAME}/{@code value} pairs in order
     * @param durationMicros the duration of the media, which ends the last chapter, or {@code -1} when unknown
     * @return the tags
     */
    public static MediaTags decode(List<String[]> fields, long durationMicros) {
        var builder = MediaTags.builder();
        var chapters = new TreeMap<Integer, String[]>();
        String trackTotal = null;
        String discTotal = null;
        for (var field : fields) {
            var name = field[0];
            var value = field[1];
            var upper = name.toUpperCase(Locale.ROOT);
            if (upper.equals(PICTURE)) {
                var picture = decodePicture(value);
                if (picture != null) {
                    builder.addPicture(picture);
                }
                continue;
            }
            if (chapterField(upper, value, chapters)) {
                continue;
            }
            switch (upper) {
                case "TRACKTOTAL", "TOTALTRACKS" -> {
                    if (trackTotal == null) {
                        trackTotal = value.trim();
                    }
                    continue;
                }
                case "DISCTOTAL", "TOTALDISCS" -> {
                    if (discTotal == null) {
                        discTotal = value.trim();
                    }
                    continue;
                }
                default -> {
                }
            }
            if (value.isEmpty()) {
                continue;
            }
            try {
                builder.add(name, value);
            } catch (IllegalArgumentException ignored) {
                // a field whose name no format can store is dropped
            }
        }
        var tags = builder.build();
        var result = tags.toBuilder();
        foldTotal(tags, result, StandardTag.TRACK, trackTotal, "TRACKTOTAL");
        foldTotal(tags, result, StandardTag.DISC, discTotal, "DISCTOTAL");
        result.chapters(chapters(chapters, durationMicros));
        return result.build();
    }

    /**
     * Folds a total into the {@code n/total} value of a number tag.
     *
     * @param tags the decoded tags
     * @param result the builder to update
     * @param tag {@link StandardTag#TRACK} or {@link StandardTag#DISC}
     * @param total the total, or {@code null}
     * @param rawName the field name kept when there is no number to fold the total into
     */
    private static void foldTotal(MediaTags tags, MediaTags.Builder result, StandardTag tag, String total, String rawName) {
        if (total == null || total.isEmpty()) {
            return;
        }
        var number = tags.get(tag);
        if (number.isPresent() && !number.get().contains("/")) {
            result.set(tag, number.get().trim() + "/" + total);
        } else if (number.isEmpty()) {
            result.add(rawName, total);
        }
    }

    /**
     * Records a {@code CHAPTERxxx} or {@code CHAPTERxxxNAME} field, numbered with one to four
     * digits.
     *
     * <p>Once the map holds the bound on chapter fields, fields of new chapter numbers are
     * consumed without being recorded.
     *
     * @param upper the field name in upper case
     * @param value the field value
     * @param chapters receives the field, keyed by chapter number: the start clock at index 0 and the title at index 1
     * @return {@code true} when the field is a chapter field
     */
    public static boolean chapterField(String upper, String value, TreeMap<Integer, String[]> chapters) {
        if (!upper.startsWith("CHAPTER") || upper.length() < 10) {
            return false;
        }
        var digits = 7;
        while (digits < upper.length() && Character.isDigit(upper.charAt(digits))) {
            digits++;
        }
        if (digits == 7 || digits - 7 > 4) {
            return false;
        }
        var suffix = upper.substring(digits);
        if (!suffix.isEmpty() && !suffix.equals("NAME")) {
            return false;
        }
        var number = Integer.parseInt(upper.substring(7, digits));
        if (chapters.size() >= MAX_CHAPTERS * 4 && !chapters.containsKey(number)) {
            return true;
        }
        chapters.computeIfAbsent(number, _ -> new String[2])[suffix.isEmpty() ? 0 : 1] = value;
        return true;
    }

    /**
     * Turns chapter fields into chapters ending where the next starts.
     *
     * @param fields the chapter fields keyed by number
     * @param durationMicros the media duration, or {@code -1}
     * @return the chapters in start order
     */
    private static List<Chapter> chapters(TreeMap<Integer, String[]> fields, long durationMicros) {
        var starts = new ArrayList<long[]>();
        var titles = new ArrayList<String>();
        for (var entry : fields.values()) {
            var start = parseClock(entry[0]);
            if (start >= 0) {
                starts.add(new long[]{start});
                titles.add(entry[1]);
            }
        }
        var order = new ArrayList<Integer>();
        for (var i = 0; i < starts.size(); i++) {
            order.add(i);
        }
        order.sort((a, b) -> Long.compare(starts.get(a)[0], starts.get(b)[0]));
        var result = new ArrayList<Chapter>(order.size());
        for (var k = 0; k < order.size(); k++) {
            var start = starts.get(order.get(k))[0];
            var end = k + 1 < order.size() ? starts.get(order.get(k + 1))[0] : Math.max(start, durationMicros);
            var title = titles.get(order.get(k));
            result.add(Chapter.of(start, end, title == null || title.isEmpty() ? null : title));
        }
        return result;
    }

    /**
     * Parses a {@code HH:MM:SS.sss} chapter clock.
     *
     * <p>The hours are 1 to 6 digits, the minutes 1 or 2 digits below 60, and the seconds 1 or 2
     * digits below 60 with up to nine fraction digits; surrounding white space is ignored. The
     * fraction is rounded half up to whole microseconds.
     *
     * @param clock the clock, or {@code null}
     * @return the time in microseconds, or {@code -1} when the clock is {@code null} or malformed
     */
    public static long parseClock(String clock) {
        if (clock == null) {
            return -1;
        }
        var matcher = CLOCK.matcher(clock.trim());
        if (!matcher.matches()) {
            return -1;
        }
        var minutes = Integer.parseInt(matcher.group(2));
        var seconds = Integer.parseInt(matcher.group(3));
        if (minutes > 59 || seconds > 59) {
            return -1;
        }
        var fraction = matcher.group(4);
        var nanos = fraction == null ? 0 : Long.parseLong((fraction + "00000000").substring(0, 9));
        return (Long.parseLong(matcher.group(1)) * 3600 + minutes * 60L + seconds) * 1_000_000L + (nanos + 500) / 1000;
    }

    /**
     * Formats a chapter clock with millisecond precision.
     *
     * @param micros the time in microseconds
     * @return the clock, such as {@code 00:01:02.500}
     */
    static String formatClock(long micros) {
        var millis = (micros + 500) / 1000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d.%03d", millis / 3_600_000, millis / 60_000 % 60,
                millis / 1000 % 60, millis % 1000);
    }

    /**
     * Decodes a {@code METADATA_BLOCK_PICTURE} value.
     *
     * @param value the base64 text
     * @return the picture, or {@code null} when malformed
     */
    private static AttachedPicture decodePicture(String value) {
        try {
            var block = Base64.getMimeDecoder().decode(value);
            return FlacPicture.decode(block, 0, block.length);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    /**
     * Encodes a comment block, without the framing bit Vorbis appends.
     *
     * <p>Standard tags take their Vorbis field names, {@code n/total} track and disc values are
     * split into the number and {@code TRACKTOTAL}/{@code DISCTOTAL}, other keys are written in
     * upper case, and keys with characters outside the field name alphabet ({@code 0x20} to
     * {@code 0x7D}) are dropped. Chapters become {@code CHAPTERxxx} fields; pictures become
     * {@code METADATA_BLOCK_PICTURE} fields when {@code inlinePictures} is set and are left to the
     * caller otherwise.
     *
     * @param vendor the vendor string
     * @param tags the tags, chapters and pictures to store
     * @param inlinePictures whether pictures are written as fields
     * @return the block
     */
    public static byte[] encode(String vendor, MediaTags tags, boolean inlinePictures) {
        var fields = new ArrayList<String>();
        var hasTrackTotal = tags.contains("TRACKTOTAL") || tags.contains("TOTALTRACKS");
        var hasDiscTotal = tags.contains("DISCTOTAL") || tags.contains("TOTALDISCS");
        for (var entry : tags.entries()) {
            var standard = StandardTag.fromName(entry.key());
            String name;
            var value = entry.value();
            if (standard.isPresent()) {
                name = standard.get().vorbisField();
                if ((standard.get() == StandardTag.TRACK && !hasTrackTotal) || (standard.get() == StandardTag.DISC && !hasDiscTotal)) {
                    var slash = value.indexOf('/');
                    if (slash >= 0) {
                        fields.add(name + "=" + value.substring(0, slash).trim());
                        var total = value.substring(slash + 1).trim();
                        if (!total.isEmpty()) {
                            fields.add((standard.get() == StandardTag.TRACK ? "TRACKTOTAL" : "DISCTOTAL") + "=" + total);
                        }
                        continue;
                    }
                }
            } else {
                name = entry.key().toUpperCase(Locale.ROOT);
                if (!validName(name)) {
                    continue;
                }
            }
            fields.add(name + "=" + value);
        }
        var chapters = tags.chapters();
        for (var index = 0; index < Math.min(MAX_CHAPTERS, chapters.size()); index++) {
            var chapter = chapters.get(index);
            var prefix = String.format(Locale.ROOT, "CHAPTER%03d", index + 1);
            fields.add(prefix + "=" + formatClock(chapter.startMicros()));
            if (chapter.title().isPresent()) {
                fields.add(prefix + "NAME=" + chapter.title().get());
            }
        }
        if (inlinePictures) {
            for (var picture : tags.pictures()) {
                fields.add(PICTURE + "=" + Base64.getEncoder().encodeToString(FlacPicture.encode(picture)));
            }
        }
        var vendorBytes = vendor.getBytes(StandardCharsets.UTF_8);
        var out = new ByteWriter(64 + vendorBytes.length);
        out.u32le(vendorBytes.length);
        out.bytes(vendorBytes);
        out.u32le(fields.size());
        for (var field : fields) {
            var bytes = field.getBytes(StandardCharsets.UTF_8);
            out.u32le(bytes.length);
            out.bytes(bytes);
        }
        return out.toByteArray();
    }

    /**
     * Returns whether a field name uses only the characters the specification allows.
     *
     * @param name the field name, in upper case
     * @return {@code true} for printable ASCII from {@code 0x20} to {@code 0x7D} without {@code '='}
     */
    private static boolean validName(String name) {
        for (var i = 0; i < name.length(); i++) {
            var c = name.charAt(i);
            if (c < 0x20 || c > 0x7D || c == '=') {
                return false;
            }
        }
        return !name.isEmpty();
    }

    /**
     * Reads a little-endian 32-bit length.
     *
     * @param data the bytes
     * @param position the index of the length
     * @param end the end of the block
     * @return the length
     * @throws MediaException.Malformed if the length field runs past the block
     */
    private static long lengthAt(byte[] data, int position, int end) {
        if (end - position < 4) {
            throw new MediaException.Malformed("Vorbis comment block truncated at a length field");
        }
        return ByteArrays.u32le(data, position);
    }

    /**
     * Holds a parsed comment block.
     *
     * @param vendor the vendor string
     * @param fields the {@code NAME}/{@code value} pairs in order
     * @param end the index of the first byte after the block
     */
    public record Parsed(String vendor, List<String[]> fields, int end) {
    }
}
