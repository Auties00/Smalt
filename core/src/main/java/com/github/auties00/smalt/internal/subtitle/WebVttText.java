package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.subtitle.SubtitleCue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Parses and writes WebVTT ({@code .vtt}) files.
 *
 * <p>A WebVTT file starts with a {@code WEBVTT} line, optionally followed by text and header
 * lines, then holds blocks separated by blank lines: cues (an optional identifier line, a timing
 * line {@code 00:01.000 --> 00:02.000} with the optional settings list after it, and the payload
 * lines), comments ({@code NOTE}), and before the first cue {@code STYLE} and {@code REGION}
 * blocks. The header of a document is the {@code WEBVTT} block and every block before the first
 * cue, the form a Matroska {@code CodecPrivate} and an MP4 {@code vttC} box store. Comments and
 * style blocks after the first cue are dropped, as are blocks the format does not define. Cues are
 * returned in start order, a stable sort keeping the file order of cues that start together.
 */
public final class WebVttText {
    /**
     * The header of a document that has nothing but the signature.
     */
    public static final String DEFAULT_HEADER = "WEBVTT";

    /**
     * The arrow between the start and end timestamps.
     */
    private static final String ARROW = "-->";

    private WebVttText() {
        throw new AssertionError();
    }

    /**
     * Holds a parsed WebVTT document.
     *
     * @param header the header: the {@code WEBVTT} block and the blocks before the first cue,
     * separated by blank lines
     * @param cues the cues in start order, their text the cue payloads
     */
    public record Document(String header, List<SubtitleCue> cues) {
    }

    /**
     * Returns whether text starts with the WebVTT signature.
     *
     * @param text the text, without a byte order mark
     * @return {@code true} when the first line is {@code WEBVTT}, optionally followed by a space or
     * tab and more text
     */
    public static boolean isSignature(String text) {
        if (!text.startsWith(DEFAULT_HEADER)) {
            return false;
        }
        if (text.length() == DEFAULT_HEADER.length()) {
            return true;
        }
        var next = text.charAt(DEFAULT_HEADER.length());
        return next == ' ' || next == '\t' || next == '\n' || next == '\r';
    }

    /**
     * Parses the text of a WebVTT file with the WebVTT parser algorithm (W3C WebVTT, 6.1): the text is
     * preprocessed (NUL becomes U+FFFD, CR LF and CR become LF), the lines after the signature line up to the first
     * blank line are header text, and each later block is a cue when its first line, or its second after an
     * identifier, is a valid timing line; a block with an arrow on a later line ends before that line. Timestamps
     * follow the algorithm's syntax exactly (two-digit minutes and seconds, three-digit milliseconds, hours of two or
     * more digits, here of at most nine). The header of the document is the signature block and the {@code STYLE},
     * {@code REGION} and {@code NOTE} blocks before the first cue.
     *
     * @param text the file text, without a byte order mark
     * @return the document
     * @throws MediaException.Malformed if the text does not start with the WebVTT signature
     */
    public static Document parse(String text) {
        var input = preprocess(text);
        if (!input.startsWith(DEFAULT_HEADER) || input.length() > DEFAULT_HEADER.length()
                && " \t\n".indexOf(input.charAt(DEFAULT_HEADER.length())) < 0) {
            throw new MediaException.Malformed("not a WebVTT file: the first line is not WEBVTT");
        }
        var parser = new Parser(input);
        parser.position = lineEnd(input, 0);
        var header = new StringBuilder(input.substring(0, parser.position));
        var cues = new ArrayList<SubtitleCue>();
        if (parser.position < input.length()) {
            parser.position++;
            if (parser.position < input.length() && input.charAt(parser.position) != '\n') {
                var block = parser.block(true);
                if (!block.text().isEmpty()) {
                    header.append('\n').append(block.text());
                }
            } else if (parser.position < input.length()) {
                parser.position++;
            }
            parser.skipLineFeeds();
            while (parser.position < input.length()) {
                var block = parser.block(false);
                if (block.cue() != null) {
                    cues.add(block.cue());
                } else if (!parser.seenCue && block.header()) {
                    appendBlock(header, block.text());
                }
                parser.skipLineFeeds();
            }
        }
        cues.sort(Comparator.comparingLong(SubtitleCue::startMicros));
        return new Document(header.toString(), List.copyOf(cues));
    }

    /**
     * Replaces NUL with U+FFFD and every CR LF pair and lone CR with LF.
     */
    private static String preprocess(String text) {
        if (text.indexOf('\r') < 0 && text.indexOf('\0') < 0) {
            return text;
        }
        var result = new StringBuilder(text.length());
        for (var index = 0; index < text.length(); index++) {
            var character = text.charAt(index);
            if (character == '\r') {
                result.append('\n');
                if (index + 1 < text.length() && text.charAt(index + 1) == '\n') {
                    index++;
                }
            } else {
                result.append(character == '\0' ? '�' : character);
            }
        }
        return result.toString();
    }

    /**
     * Returns the index of the line feed ending the line at a position, or the length of the text.
     */
    private static int lineEnd(String text, int position) {
        var end = text.indexOf('\n', position);
        return end < 0 ? text.length() : end;
    }

    /**
     * A block the parser collected: a cue, or the text of another block and whether it belongs in the header.
     *
     * @param cue the cue, or {@code null}
     * @param text the lines of a block that is not a cue
     * @param header whether a block that is not a cue is a {@code STYLE}, {@code REGION} or {@code NOTE} block
     */
    private record Block(SubtitleCue cue, String text, boolean header) {
    }

    /**
     * The state of the WebVTT parser algorithm over a preprocessed text.
     */
    private static final class Parser {
        private final String input;

        private int position;

        private boolean seenCue;

        private Parser(String input) {
            this.input = input;
        }

        /**
         * Advances past a run of line feeds.
         */
        private void skipLineFeeds() {
            while (position < input.length() && input.charAt(position) == '\n') {
                position++;
            }
        }

        /**
         * Collects a WebVTT block (W3C WebVTT, 6.1, "collect a WebVTT block").
         *
         * @param inHeader whether the block is the one right after the signature line
         */
        private Block block(boolean inHeader) {
            var lineCount = 0;
            var previous = position;
            var buffer = new StringBuilder();
            var seenArrow = false;
            SubtitleCue cue = null;
            String first = null;
            while (true) {
                var end = lineEnd(input, position);
                var line = input.substring(position, end);
                position = end;
                lineCount++;
                var seenEnd = position >= input.length();
                if (!seenEnd) {
                    position++;
                }
                if (line.contains(ARROW)) {
                    if (!inHeader && (lineCount == 1 || lineCount == 2 && !seenArrow)) {
                        seenArrow = true;
                        previous = position;
                        cue = timedCue(line, buffer.toString());
                        if (cue != null) {
                            buffer.setLength(0);
                            seenCue = true;
                        }
                    } else {
                        position = previous;
                        break;
                    }
                } else if (line.isEmpty()) {
                    break;
                } else {
                    if (first == null && cue == null) {
                        first = line;
                    }
                    if (!buffer.isEmpty()) {
                        buffer.append('\n');
                    }
                    buffer.append(line);
                    previous = position;
                }
                if (seenEnd) {
                    break;
                }
            }
            if (cue != null) {
                return new Block(new SubtitleCue(cue.startMicros(), cue.endMicros(), buffer.toString(),
                        cue.identifier(), cue.settings()), null, false);
            }
            return new Block(null, buffer.toString(), first != null && isHeaderBlock(first));
        }

        /**
         * Builds the cue of a timing line, its text still empty.
         *
         * @return the cue, or {@code null} when the timing line is not valid
         */
        private static SubtitleCue timedCue(String line, String identifier) {
            var times = new long[2];
            var settings = new String[1];
            if (!parseTiming(line, times, settings)) {
                return null;
            }
            return new SubtitleCue(times[0], Math.max(times[0], times[1]), "", identifier, settings[0]);
        }
    }

    /**
     * Appends a block to the header, separated from the previous one by a blank line.
     *
     * @param header the header being built
     * @param block the block text
     */
    private static void appendBlock(StringBuilder header, String block) {
        if (!header.isEmpty()) {
            header.append("\n\n");
        }
        header.append(block);
    }

    /**
     * Returns whether a block that is not a cue belongs in the header.
     *
     * @param first the first line of the block
     * @return {@code true} for {@code STYLE}, {@code REGION} and {@code NOTE} blocks
     */
    private static boolean isHeaderBlock(String first) {
        return startsWithKeyword(first, "STYLE") || startsWithKeyword(first, "REGION") || startsWithKeyword(first, "NOTE");
    }

    /**
     * Returns whether a line starts with a keyword followed by the end of the line or a blank.
     *
     * @param line a line of the file
     * @param keyword the keyword, in upper case
     * @return {@code true} when the line starts with the keyword
     */
    private static boolean startsWithKeyword(String line, String keyword) {
        return line.startsWith(keyword) && (line.length() == keyword.length() || line.charAt(keyword.length()) == ' '
                || line.charAt(keyword.length()) == '\t');
    }

    /**
     * Parses a cue timing line (W3C WebVTT, 6.3, "collect WebVTT cue timings and settings").
     *
     * @param line a line of the file
     * @param times a two-element array receiving the start and end in microseconds
     * @param settings a one-element array receiving the settings list, empty when absent
     * @return {@code true} when the line is a valid timing line
     */
    static boolean parseTiming(String line, long[] times, String[] settings) {
        var at = new int[]{skipWhitespace(line, 0)};
        var start = timestamp(line, at);
        if (start < 0) {
            return false;
        }
        at[0] = skipWhitespace(line, at[0]);
        if (!line.startsWith(ARROW, at[0])) {
            return false;
        }
        at[0] = skipWhitespace(line, at[0] + ARROW.length());
        var finish = timestamp(line, at);
        if (finish < 0) {
            return false;
        }
        times[0] = start;
        times[1] = finish;
        settings[0] = normalizeSettings(line.substring(at[0]));
        return true;
    }

    /**
     * Returns the index after the ASCII whitespace (tab, line feed, form feed, carriage return, space) at a
     * position.
     */
    private static int skipWhitespace(String line, int position) {
        while (position < line.length() && isWhitespace(line.charAt(position))) {
            position++;
        }
        return position;
    }

    private static boolean isWhitespace(char character) {
        return character == ' ' || character == '\t' || character == '\n' || character == '\f' || character == '\r';
    }

    /**
     * Collects a WebVTT timestamp (W3C WebVTT, 6.3, "collect a WebVTT timestamp").
     *
     * @param line the text
     * @param at a one-element array holding the position, advanced past the timestamp
     * @return the timestamp in microseconds, or {@code -1} when the text there is not a timestamp
     */
    private static long timestamp(String line, int[] at) {
        var count = new int[1];
        var value1 = digits(line, at, count);
        if (value1 < 0) {
            return -1;
        }
        var hours = count[0] != 2 || value1 > 59;
        if (at[0] >= line.length() || line.charAt(at[0]) != ':') {
            return -1;
        }
        at[0]++;
        var value2 = digits(line, at, count);
        if (value2 < 0 || count[0] != 2) {
            return -1;
        }
        long value3;
        if (hours || at[0] < line.length() && line.charAt(at[0]) == ':') {
            if (at[0] >= line.length() || line.charAt(at[0]) != ':') {
                return -1;
            }
            at[0]++;
            value3 = digits(line, at, count);
            if (value3 < 0 || count[0] != 2) {
                return -1;
            }
        } else {
            value3 = value2;
            value2 = value1;
            value1 = 0;
        }
        if (at[0] >= line.length() || line.charAt(at[0]) != '.') {
            return -1;
        }
        at[0]++;
        var value4 = digits(line, at, count);
        if (value4 < 0 || count[0] != 3 || value2 > 59 || value3 > 59) {
            return -1;
        }
        return ((value1 * 60 + value2) * 60 + value3) * 1_000_000 + value4 * 1_000;
    }

    /**
     * Collects a run of ASCII digits.
     *
     * @param line the text
     * @param at a one-element array holding the position, advanced past the digits
     * @param count a one-element array receiving the number of digits
     * @return the value, or {@code -1} when no digit is there or more than nine are
     */
    private static long digits(String line, int[] at, int[] count) {
        var start = at[0];
        long value = 0;
        while (at[0] < line.length() && CueTimes.isDigit(line.charAt(at[0]))) {
            if (at[0] - start == 9) {
                return -1;
            }
            value = value * 10 + (line.charAt(at[0]) - '0');
            at[0]++;
        }
        count[0] = at[0] - start;
        return count[0] == 0 ? -1 : value;
    }

    /**
     * Collapses the blanks of a settings list to single spaces.
     *
     * @param settings the text after the end timestamp
     * @return the settings separated by one space, empty when there are none
     */
    public static String normalizeSettings(String settings) {
        var parts = settings.strip().split("[ \t\f]+");
        return parts.length == 1 && parts[0].isEmpty() ? "" : String.join(" ", parts);
    }

    /**
     * Returns the header to write: the given one when it starts with the signature, otherwise the
     * default one.
     *
     * @param header the header, or {@code null}
     * @return the header without trailing blank lines
     */
    public static String header(String header) {
        if (header == null) {
            return DEFAULT_HEADER;
        }
        var normalized = TextFiles.normalizeLineBreaks(header).strip();
        return isSignature(normalized) ? normalized : DEFAULT_HEADER;
    }

    /**
     * Formats one cue as it appears in a WebVTT file, with its trailing blank line.
     *
     * <p>Blank payload lines, which would end the cue early, are dropped, and an arrow in the
     * payload or the identifier is escaped so that it cannot be read as a timing line.
     *
     * @param cue the cue, whose text is a WebVTT cue payload
     * @return the cue block
     */
    public static String format(SubtitleCue cue) {
        var builder = new StringBuilder(cue.text().length() + 64);
        var identifier = cue.identifier().replace("\n", " ").replace(ARROW, "--&gt;").strip();
        if (!identifier.isEmpty()) {
            builder.append(identifier).append('\n');
        }
        builder.append(CueTimes.format(cue.startMicros(), '.')).append(" --> ").append(CueTimes.format(cue.endMicros(), '.'));
        var settings = normalizeSettings(cue.settings().replace('\n', ' '));
        if (!settings.isEmpty()) {
            builder.append(' ').append(settings);
        }
        builder.append('\n');
        var payload = SubRipText.withoutBlankLines(cue.text()).replace(ARROW, "--&gt;");
        builder.append(payload);
        if (!payload.isEmpty()) {
            builder.append('\n');
        }
        return builder.append('\n').toString();
    }
}
