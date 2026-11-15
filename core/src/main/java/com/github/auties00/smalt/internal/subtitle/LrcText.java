package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses and writes LRC ({@code .lrc}) lyrics files.
 *
 * <p>An LRC file is a sequence of lines, each starting with one or more time tags {@code [mm:ss.xx]} (the
 * fraction optional, a colon accepted for the full stop, the minutes any number of digits) followed by
 * the text shown from that time; a line may also be an ID tag such as {@code [ti:Title]}. The text may
 * carry the word times of the enhanced format, {@code <mm:ss.xx>}, which become WebVTT timestamp tags.
 * Lines with the same time form one cue, their texts on separate lines in file order; a cue lasts until
 * the next time, the last one until the {@code length} tag when it ends later, or five seconds. A line
 * with a time and no text ends the cue before it. The {@code offset} tag, in milliseconds, moves every
 * time earlier when positive. The {@code ti}, {@code ar} and {@code al} tags are the {@code title},
 * {@code artist} and {@code album}; the other ID tags keep their names.
 */
public final class LrcText {
    /**
     * The length of the last cue, in microseconds, when no {@code length} tag ends it.
     */
    private static final long LAST_CUE = 5_000_000;

    /**
     * The largest {@code offset} tag kept, in milliseconds, so that the shift in microseconds cannot overflow.
     */
    private static final long MAX_OFFSET = 1_000_000_000_000L;

    /**
     * The largest number of cues a file yields.
     */
    private static final int MAX_CUES = 1 << 22;

    private LrcText() {
        throw new AssertionError();
    }

    /**
     * Holds a parsed file.
     *
     * @param tags the ID tags by name, the three common ones renamed
     * @param cues the cues in start order, in WebVTT markup
     */
    public record Parsed(Map<String, String> tags, List<SubtitleCue> cues) {
    }

    /**
     * Returns whether text starts like an LRC file: its first non-blank line starts with a time tag or an
     * ID tag, and a time tag starts one of its first lines.
     *
     * @param text the leading text of a file, with line feeds only
     * @return {@code true} for an LRC file
     */
    public static boolean isSignature(String text) {
        var lines = text.split("\n", 64);
        var first = true;
        for (var index = 0; index < lines.length && index < 63; index++) {
            var line = lines[index].strip();
            if (line.isEmpty()) {
                continue;
            }
            if (first && !line.startsWith("[")) {
                return false;
            }
            first = false;
            if (timeTag(line, 0, new int[1]) >= 0) {
                return true;
            }
            if (idTag(line) == null) {
                return false;
            }
        }
        return false;
    }

    /**
     * Parses the text of an LRC file.
     *
     * @param text the file text, with line feeds only
     * @return the parsed file
     */
    public static Parsed parse(String text) {
        var tags = new LinkedHashMap<String, String>();
        var entries = new ArrayList<long[]>();
        var texts = new ArrayList<String>();
        var offset = 0L;
        var length = -1L;
        for (var raw : text.split("\n")) {
            if (entries.size() >= MAX_CUES) {
                break;
            }
            var line = raw.strip();
            if (!line.startsWith("[")) {
                continue;
            }
            var end = new int[1];
            var position = 0;
            var times = new ArrayList<Long>();
            while (position < line.length() && line.charAt(position) == '[') {
                var time = timeTag(line, position, end);
                if (time < 0) {
                    break;
                }
                times.add(time);
                position = end[0];
            }
            if (times.isEmpty()) {
                var tag = idTag(line);
                if (tag != null) {
                    var name = tag[0].toLowerCase(Locale.ROOT);
                    switch (name) {
                        case "offset" -> {
                            try {
                                offset = Math.clamp(Long.parseLong(tag[1].strip().replace("+", "")), -MAX_OFFSET, MAX_OFFSET);
                            } catch (NumberFormatException ignored) {
                                // a malformed offset is ignored
                            }
                        }
                        case "length" -> {
                            var parsed = CueTimes.parse(tag[1].strip(), 0, end);
                            length = parsed >= 0 ? parsed : length;
                        }
                        default -> {
                        }
                    }
                    tags.putIfAbsent(switch (name) {
                        case "ti" -> "title";
                        case "ar" -> "artist";
                        case "al" -> "album";
                        default -> name;
                    }, tag[1].strip());
                }
                continue;
            }
            var content = line.substring(position).strip();
            for (var time : times) {
                entries.add(new long[]{time, texts.size()});
            }
            texts.add(content);
        }
        var shift = offset * 1000;
        var order = new ArrayList<>(entries);
        order.sort(Comparator.comparingLong(entry -> entry[0]));
        var cues = new ArrayList<SubtitleCue>();
        var index = 0;
        while (index < order.size()) {
            var time = order.get(index)[0];
            var lines = new ArrayList<String>();
            while (index < order.size() && order.get(index)[0] == time) {
                var content = texts.get((int) order.get(index)[1]);
                if (!content.isEmpty()) {
                    lines.add(content);
                }
                index++;
            }
            var next = index < order.size() ? order.get(index)[0] : Math.max(time + LAST_CUE, length);
            if (lines.isEmpty()) {
                continue;
            }
            var start = Math.max(0, time - shift);
            var end = Math.max(start, next - shift);
            var payload = new StringBuilder();
            for (var line : lines) {
                if (!payload.isEmpty()) {
                    payload.append('\n');
                }
                payload.append(webVtt(line, shift));
            }
            cues.add(SubtitleCue.of(start, end, payload.toString()));
        }
        return new Parsed(Map.copyOf(tags), List.copyOf(cues));
    }

    /**
     * Returns the ID tag a line holds.
     *
     * @param line the line, stripped
     * @return the name and value, or {@code null} when the line is not an ID tag
     */
    private static String[] idTag(String line) {
        var close = line.indexOf(']');
        var colon = line.indexOf(':');
        if (!line.startsWith("[") || close < 0 || colon < 0 || colon > close) {
            return null;
        }
        var name = line.substring(1, colon).strip();
        if (name.isEmpty() || !name.chars().allMatch(Character::isLetter)) {
            return null;
        }
        return new String[]{name, line.substring(colon + 1, close)};
    }

    /**
     * Parses a time tag at a position.
     *
     * @param line the text of one line
     * @param position the index of the opening bracket
     * @param end receives the index after the closing bracket
     * @return the time in microseconds, or {@code -1} when no time tag starts there
     */
    private static long timeTag(String line, int position, int[] end) {
        if (position >= line.length() || line.charAt(position) != '[') {
            return -1;
        }
        var close = line.indexOf(']', position + 1);
        if (close < 0) {
            return -1;
        }
        var time = time(line.substring(position + 1, close));
        if (time < 0) {
            return -1;
        }
        end[0] = close + 1;
        return time;
    }

    /**
     * Parses a time, {@code mm:ss}, {@code mm:ss.xx} or {@code mm:ss:xx}.
     *
     * @param value the time between its brackets
     * @return the time in microseconds, or {@code -1} when it is not a time
     */
    static long time(String value) {
        var text = value.strip();
        var colon = text.indexOf(':');
        if (colon <= 0 || colon > 9) {
            return -1;
        }
        var rest = text.substring(colon + 1);
        var second = rest.indexOf(':');
        if (second >= 0) {
            rest = rest.substring(0, second) + "." + rest.substring(second + 1);
        }
        for (var index = 0; index < colon; index++) {
            if (!CueTimes.isDigit(text.charAt(index))) {
                return -1;
            }
        }
        var dot = rest.indexOf('.');
        var seconds = dot < 0 ? rest : rest.substring(0, dot);
        var fraction = dot < 0 ? "" : rest.substring(dot + 1);
        if (seconds.isEmpty() || seconds.length() > 2 || !allDigits(seconds) || !allDigits(fraction)
                || fraction.length() > 6 || dot >= 0 && fraction.isEmpty()) {
            return -1;
        }
        var minutes = Long.parseLong(text.substring(0, colon));
        var whole = Long.parseLong(seconds);
        if (whole > 59) {
            return -1;
        }
        var micros = 0L;
        var scale = 100_000L;
        for (var index = 0; index < fraction.length(); index++) {
            micros += (fraction.charAt(index) - '0') * scale;
            scale /= 10;
        }
        return (minutes * 60 + whole) * 1_000_000 + micros;
    }

    /**
     * Returns whether a text is made of ASCII digits only.
     *
     * @param text the text, possibly empty
     * @return {@code true} when every character is a digit
     */
    private static boolean allDigits(String text) {
        for (var index = 0; index < text.length(); index++) {
            if (!CueTimes.isDigit(text.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Converts the text of an LRC line into a WebVTT payload: word times become timestamp tags and the
     * characters WebVTT reserves are escaped.
     *
     * @param line the text after the time tags
     * @param shift the offset to subtract from word times, in microseconds
     * @return the payload
     */
    private static String webVtt(String line, long shift) {
        var builder = new StringBuilder(line.length() + 16);
        for (var index = 0; index < line.length(); index++) {
            var character = line.charAt(index);
            if (character == '<') {
                var close = line.indexOf('>', index + 1);
                if (close > 0) {
                    var time = time(line.substring(index + 1, close));
                    if (time >= 0) {
                        builder.append('<').append(CueTimes.format(Math.max(0, time - shift), '.')).append('>');
                        index = close;
                        continue;
                    }
                }
                builder.append("&lt;");
            } else if (character == '>') {
                builder.append("&gt;");
            } else if (character == '&') {
                builder.append("&amp;");
            } else {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    /**
     * Writes cues as an LRC file.
     *
     * <p>Each line of a cue is one LRC line at the cue start, its WebVTT timestamp tags written as word
     * times and its other markup dropped; a cue that ends before the next one starts is followed by an empty
     * line at its end. Times are rounded to the centisecond.
     *
     * @param tags the ID tags to write first, by name
     * @param cues the cues in start order, in WebVTT markup
     * @return the file text, with line feeds
     */
    public static String write(Map<String, String> tags, List<SubtitleCue> cues) {
        var out = new StringBuilder();
        for (var entry : tags.entrySet()) {
            out.append('[').append(entry.getKey()).append(':').append(entry.getValue().replace(']', ')').replace('\n', ' '))
                    .append("]\n");
        }
        for (var index = 0; index < cues.size(); index++) {
            var cue = cues.get(index);
            var text = lrcText(cue);
            for (var line : text.split("\n", -1)) {
                if (!line.isBlank()) {
                    out.append('[').append(format(cue.startMicros())).append(']').append(line.strip()).append('\n');
                }
            }
            var nextStart = index + 1 < cues.size() ? cues.get(index + 1).startMicros() : Long.MAX_VALUE;
            if (cue.endMicros() < nextStart && Math.floorDiv(cue.endMicros() + 5000, 10000)
                    != Math.floorDiv(nextStart + 5000, 10000)) {
                out.append('[').append(format(cue.endMicros())).append("]\n");
            }
        }
        return out.toString();
    }

    /**
     * Returns the text of a cue as LRC writes it: plain text with word times.
     *
     * @param cue the cue, in WebVTT markup
     * @return the text, lines separated by line feeds
     */
    private static String lrcText(SubtitleCue cue) {
        var styled = Markup.parse(cue.text(), SubtitleMarkup.WEBVTT, Markup.Context.of(cue));
        var builder = new StringBuilder();
        var last = -1L;
        for (var run : styled.runs()) {
            if (run.karaoke() > 0 && run.karaoke() != last) {
                builder.append('<').append(format(cue.startMicros() + run.karaoke())).append('>');
                last = run.karaoke();
            }
            builder.append(run.text());
        }
        return builder.toString();
    }

    /**
     * Formats a time as {@code mm:ss.xx}, rounding to the centisecond.
     *
     * @param micros the time in microseconds; negative values format as zero
     * @return the time
     */
    static String format(long micros) {
        var centis = Math.max(0, Math.floorDiv(micros + 5000, 10000));
        return String.format(Locale.ROOT, "%02d:%02d.%02d", centis / 6000, centis / 100 % 60, centis % 100);
    }
}
