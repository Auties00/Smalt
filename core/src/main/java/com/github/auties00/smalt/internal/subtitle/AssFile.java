package com.github.auties00.smalt.internal.subtitle;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses and writes SubStation Alpha ({@code .ssa}) and Advanced SubStation Alpha ({@code .ass}) scripts.
 *
 * <p>A script is a header, every line but the {@code Dialogue} lines of its {@code [Events]} section, and
 * the events those lines hold, read in the field order of the section's {@code Format} line (the version's
 * usual order when it has none). The header keeps comments, {@code Comment} events, and the sections a
 * script carries besides its info, styles and events ({@code [Fonts]}, {@code [Graphics]}, editor
 * sections) as they are. A {@code Dialogue} line with too few fields or an invalid time is dropped.
 */
public final class AssFile {
    /**
     * The largest number of events a script yields.
     */
    private static final int MAX_EVENTS = 1 << 22;

    private AssFile() {
        throw new AssertionError();
    }

    /**
     * Holds an event with its times.
     *
     * @param event the event; its read order is its position among the script's events
     * @param startMicros the start in microseconds
     * @param endMicros the end in microseconds
     */
    public record Timed(AssEvents.Event event, long startMicros, long endMicros) {
    }

    /**
     * Holds a parsed script.
     *
     * @param header the header, lines separated by line feeds and ending with one
     * @param script the parsed header
     * @param events the events in file order
     */
    public record Parsed(String header, AssScript script, List<Timed> events) {
    }

    /**
     * Returns whether text starts like a script: its first line other than blank lines and {@code ;}
     * comments is {@code [Script Info]}.
     *
     * @param text the leading text of a file, with line feeds only and without a byte order mark
     * @return {@code true} for a script
     */
    public static boolean isSignature(String text) {
        for (var line : text.split("\n", 64)) {
            var trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith(";")) {
                continue;
            }
            return trimmed.equalsIgnoreCase("[Script Info]");
        }
        return false;
    }

    /**
     * Parses a script.
     *
     * @param text the script, with line feeds only and without a byte order mark
     * @return the parsed script
     */
    public static Parsed parse(String text) {
        var lines = text.split("\n", -1);
        var header = new StringBuilder(Math.min(text.length(), 1 << 16));
        var dialogues = new ArrayList<String>();
        var inEvents = false;
        for (var index = 0; index < lines.length; index++) {
            var line = lines[index];
            var trimmed = line.strip();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                inEvents = trimmed.equalsIgnoreCase("[Events]");
            }
            var value = inEvents ? AssScript.field(line, "Dialogue") : null;
            if (value != null) {
                if (dialogues.size() < MAX_EVENTS) {
                    dialogues.add(value);
                }
                continue;
            }
            if (index == lines.length - 1 && line.isEmpty()) {
                break;
            }
            header.append(line.stripTrailing()).append('\n');
        }
        var headerText = trimTrailingBlankLines(header.toString());
        var script = AssScript.parse(headerText);
        var events = new ArrayList<Timed>(dialogues.size());
        var times = new long[2];
        for (var dialogue : dialogues) {
            var event = AssEvents.parseDialogue(dialogue, script.eventFormat(), events.size(), times);
            if (event != null) {
                events.add(new Timed(event, times[0], times[1]));
            }
        }
        return new Parsed(headerText, script, List.copyOf(events));
    }

    /**
     * Writes a script: the header up to the end of its {@code [Events]} section, the events as
     * {@code Dialogue} lines in the header's event format, then the sections after it.
     *
     * <p>A header without an {@code [Events]} section, or whose section has no {@code Format} line, gets one
     * in the usual order of its version.
     *
     * @param header the script header text
     * @param events the events in the order to write
     * @return the script, lines ending with a line feed
     */
    public static String write(String header, List<Timed> events) {
        var script = AssScript.parse(header);
        var lines = new ArrayList<>(List.of(trimTrailingBlankLines(header).split("\n", -1)));
        if (!lines.isEmpty() && lines.getLast().isEmpty()) {
            lines.removeLast();
        }
        var eventsLine = -1;
        for (var index = 0; index < lines.size(); index++) {
            if (lines.get(index).strip().equalsIgnoreCase("[Events]")) {
                eventsLine = index;
            }
        }
        var formatLine = String.join(", ", script.isSsa() ? AssScript.SSA_EVENT_FORMAT : AssScript.ASS_EVENT_FORMAT);
        if (eventsLine < 0) {
            if (!lines.isEmpty() && !lines.getLast().isBlank()) {
                lines.add("");
            }
            lines.add("[Events]");
            lines.add("Format: " + formatLine);
            eventsLine = lines.size() - 2;
        }
        var sectionEnd = lines.size();
        var hasFormat = false;
        for (var index = eventsLine + 1; index < lines.size(); index++) {
            var trimmed = lines.get(index).strip();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                sectionEnd = index;
                break;
            }
            if (AssScript.field(lines.get(index), "Format") != null) {
                hasFormat = true;
            }
        }
        if (!hasFormat) {
            lines.add(eventsLine + 1, "Format: " + formatLine);
            sectionEnd++;
        }
        var insertAt = sectionEnd;
        while (insertAt > eventsLine + 1 && lines.get(insertAt - 1).isBlank()) {
            insertAt--;
        }
        var format = AssScript.parse(String.join("\n", lines)).eventFormat();
        var out = new StringBuilder(header.length() + events.size() * 64);
        for (var index = 0; index < insertAt; index++) {
            out.append(lines.get(index)).append('\n');
        }
        for (var timed : events) {
            out.append("Dialogue: ").append(AssEvents.dialogue(timed.event(), timed.startMicros(), timed.endMicros(),
                    format)).append('\n');
        }
        if (sectionEnd < lines.size()) {
            out.append('\n');
            for (var index = sectionEnd; index < lines.size(); index++) {
                out.append(lines.get(index)).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Removes the blank lines that end a text, keeping one final line feed.
     *
     * @param text the characters to examine
     * @return the text ending with exactly one line feed, or empty
     */
    private static String trimTrailingBlankLines(String text) {
        var end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '\n' || text.charAt(end - 1) == ' ' || text.charAt(end - 1) == '\t')) {
            end--;
        }
        return end == 0 ? "" : text.substring(0, end) + "\n";
    }
}
