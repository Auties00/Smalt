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
     * Parses the text of a WebVTT file.
     *
     * @param text the file text, with line feeds only and without a byte order mark
     * @return the document
     * @throws MediaException.Malformed if the text does not start with the WebVTT signature
     */
    public static Document parse(String text) {
        if (!isSignature(text)) {
            throw new MediaException.Malformed("not a WebVTT file: the first line is not WEBVTT");
        }
        var lines = text.split("\n", -1);
        var header = new StringBuilder();
        var cues = new ArrayList<SubtitleCue>();
        var index = 0;
        var block = new ArrayList<String>();
        index = readBlock(lines, index, block);
        appendBlock(header, block);
        var times = new long[2];
        var settings = new String[1];
        while (true) {
            while (index < lines.length && lines[index].isBlank()) {
                index++;
            }
            if (index >= lines.length) {
                break;
            }
            index = readBlock(lines, index, block);
            String identifier;
            int timing;
            if (block.getFirst().contains(ARROW)) {
                identifier = "";
                timing = 0;
            } else if (block.size() > 1 && block.get(1).contains(ARROW)) {
                identifier = block.getFirst().strip();
                timing = 1;
            } else {
                if (cues.isEmpty() && isHeaderBlock(block.getFirst())) {
                    appendBlock(header, block);
                }
                continue;
            }
            if (!parseTiming(block.get(timing), times, settings)) {
                continue;
            }
            var payload = new StringBuilder();
            for (var line = timing + 1; line < block.size(); line++) {
                if (line > timing + 1) {
                    payload.append('\n');
                }
                payload.append(block.get(line));
            }
            cues.add(new SubtitleCue(times[0], Math.max(times[0], times[1]), payload.toString(), identifier,
                    settings[0]));
        }
        cues.sort(Comparator.comparingLong(SubtitleCue::startMicros));
        return new Document(header.toString(), List.copyOf(cues));
    }

    /**
     * Reads the lines of one block, up to the next blank line.
     *
     * @param lines the lines of the file
     * @param index the index of the block's first line
     * @param block the list receiving the lines; cleared first
     * @return the index after the block
     */
    private static int readBlock(String[] lines, int index, List<String> block) {
        block.clear();
        while (index < lines.length && !lines[index].isBlank()) {
            block.add(lines[index].stripTrailing());
            index++;
        }
        return index;
    }

    /**
     * Appends a block to the header, separated from the previous one by a blank line.
     *
     * @param header the header being built
     * @param block the block lines
     */
    private static void appendBlock(StringBuilder header, List<String> block) {
        if (!header.isEmpty()) {
            header.append("\n\n");
        }
        header.append(String.join("\n", block));
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
     * Parses a cue timing line.
     *
     * @param line a line of the file
     * @param times a two-element array receiving the start and end in microseconds
     * @param settings a one-element array receiving the settings list, empty when absent
     * @return {@code true} when the line is a valid timing line
     */
    static boolean parseTiming(String line, long[] times, String[] settings) {
        var arrow = line.indexOf(ARROW);
        var end = new int[1];
        var start = CueTimes.parse(line, SubRipText.skipBlanks(line, 0), end);
        if (start < 0 || SubRipText.skipBlanks(line, end[0]) != arrow) {
            return false;
        }
        var finish = CueTimes.parse(line, SubRipText.skipBlanks(line, arrow + ARROW.length()), end);
        if (finish < 0) {
            return false;
        }
        times[0] = start;
        times[1] = finish;
        settings[0] = normalizeSettings(line.substring(end[0]));
        return true;
    }

    /**
     * Collapses the blanks of a settings list to single spaces.
     *
     * @param settings the text after the end timestamp
     * @return the settings separated by one space, empty when there are none
     */
    public static String normalizeSettings(String settings) {
        var parts = settings.strip().split("[ \t]+");
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
