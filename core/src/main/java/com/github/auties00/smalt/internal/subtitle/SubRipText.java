package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.subtitle.SubtitleCue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Parses and writes SubRip ({@code .srt}) files.
 *
 * <p>A SubRip file is a sequence of cues, each a decimal counter line, a timing line
 * ({@code 00:00:01,000 --> 00:00:02,500}, optionally followed by the {@code X1: Y1:} position
 * extension), and one or more text lines, separated by blank lines. The parser follows players
 * rather than the letter of the format: it finds cues by their timing lines, so a missing or
 * wrong counter, a missing blank line between cues and blank lines inside a cue's text are all
 * tolerated; a line of digits directly above a timing line is taken for the next cue's counter
 * and dropped from the previous cue's text. Lines before the first timing line are ignored. Cues
 * are returned in start order, a stable sort keeping the file order of cues that start together;
 * a cue whose end precedes its start ends when it starts.
 */
public final class SubRipText {
    /**
     * The arrow between the start and end timestamps.
     */
    private static final String ARROW = "-->";

    private SubRipText() {
        throw new AssertionError();
    }

    /**
     * Parses the text of a SubRip file.
     *
     * @param text the file text, with line feeds only
     * @return the cues in start order
     */
    public static List<SubtitleCue> parse(String text) {
        var lines = text.split("\n", -1);
        var cues = new ArrayList<SubtitleCue>();
        var times = new long[2];
        var index = 0;
        while (index < lines.length && !parseTiming(lines[index], times)) {
            index++;
        }
        while (index < lines.length) {
            var start = times[0];
            var end = Math.max(times[0], times[1]);
            var body = new ArrayList<String>();
            index++;
            var nextStart = 0L;
            var nextEnd = 0L;
            var found = false;
            while (index < lines.length) {
                if (parseTiming(lines[index], times)) {
                    nextStart = times[0];
                    nextEnd = times[1];
                    found = true;
                    break;
                }
                body.add(lines[index]);
                index++;
            }
            if (found && !body.isEmpty() && isCounter(body.getLast())) {
                body.removeLast();
            }
            cues.add(SubtitleCue.of(start, end, join(body)));
            times[0] = nextStart;
            times[1] = nextEnd;
        }
        cues.sort(Comparator.comparingLong(SubtitleCue::startMicros));
        return cues;
    }

    /**
     * Returns whether a line is a SubRip timing line.
     *
     * @param line a line of the file
     * @param times a two-element array receiving the start and end in microseconds
     * @return {@code true} when the line is a timing line
     */
    public static boolean isTimingLine(String line, long[] times) {
        return parseTiming(line, times);
    }

    /**
     * Parses a timing line.
     *
     * @param line a line of the file
     * @param times a two-element array receiving the start and end in microseconds
     * @return {@code true} when the line is a timing line
     */
    static boolean parseTiming(String line, long[] times) {
        var arrow = line.indexOf(ARROW);
        if (arrow < 0) {
            return false;
        }
        var position = skipBlanks(line, 0);
        var end = new int[1];
        var start = CueTimes.parse(line, position, end);
        if (start < 0 || skipBlanks(line, end[0]) != arrow) {
            return false;
        }
        var finish = CueTimes.parse(line, skipBlanks(line, arrow + ARROW.length()), end);
        if (finish < 0) {
            return false;
        }
        times[0] = start;
        times[1] = finish;
        return true;
    }

    /**
     * Returns the index of the first character at or after a position that is not a space or a
     * tab.
     *
     * @param line the text to scan
     * @param position the index to start at
     * @return the index
     */
    static int skipBlanks(String line, int position) {
        while (position < line.length() && (line.charAt(position) == ' ' || line.charAt(position) == '\t')) {
            position++;
        }
        return position;
    }

    /**
     * Returns whether a line holds only a cue counter.
     *
     * @param line a line of the file
     * @return {@code true} when the trimmed line is a non-empty run of digits
     */
    private static boolean isCounter(String line) {
        var trimmed = line.strip();
        if (trimmed.isEmpty()) {
            return false;
        }
        for (var index = 0; index < trimmed.length(); index++) {
            if (!CueTimes.isDigit(trimmed.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Joins the text lines of a cue, without the blank lines and trailing blanks around it.
     *
     * @param body the lines
     * @return the text
     */
    private static String join(List<String> body) {
        var first = 0;
        var last = body.size();
        while (first < last && body.get(first).isBlank()) {
            first++;
        }
        while (last > first && body.get(last - 1).isBlank()) {
            last--;
        }
        var builder = new StringBuilder();
        for (var index = first; index < last; index++) {
            if (index > first) {
                builder.append('\n');
            }
            builder.append(body.get(index).stripTrailing());
        }
        return builder.toString();
    }

    /**
     * Formats one cue as it appears in a SubRip file, with its trailing blank line.
     *
     * @param counter the cue counter, starting at 1
     * @param cue the cue, whose text is SubRip text
     * @return the cue block
     */
    public static String format(long counter, SubtitleCue cue) {
        var builder = new StringBuilder(cue.text().length() + 48);
        builder.append(counter).append('\n')
                .append(CueTimes.format(cue.startMicros(), ',')).append(" --> ").append(CueTimes.format(cue.endMicros(), ','))
                .append('\n');
        var text = withoutBlankLines(cue.text());
        builder.append(text);
        if (!text.isEmpty()) {
            builder.append('\n');
        }
        return builder.append('\n').toString();
    }

    /**
     * Removes the blank lines of a cue text, which would end the cue early in a SubRip file.
     *
     * @param text the cue text
     * @return the text without blank lines
     */
    static String withoutBlankLines(String text) {
        if (!text.contains("\n")) {
            return text.isBlank() ? "" : text;
        }
        var builder = new StringBuilder(text.length());
        for (var line : text.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }
            if (!builder.isEmpty()) {
                builder.append('\n');
            }
            builder.append(line);
        }
        return builder.toString();
    }
}
