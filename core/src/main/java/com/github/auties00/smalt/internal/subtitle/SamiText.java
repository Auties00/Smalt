package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Parses and writes SAMI ({@code .smi}, Microsoft Synchronized Accessible Media Interchange) files.
 *
 * <p>A SAMI file is HTML-like: a {@code STYLE} block in its head declares the language classes
 * ({@code .ENCC { Name: English; lang: en-US; }}), and its body is a sequence of {@code SYNC Start=ms}
 * elements, each showing until the next one, holding a {@code P} per language class. Tag and attribute
 * names are matched ignoring case, and unclosed {@code SYNC} and {@code P} elements end where the next one
 * starts. Each class becomes one track of cues: the {@code P} text with {@code br} line breaks, whitespace
 * collapsed and HTML character references decoded, its {@code b}, {@code i}, {@code u}, {@code s} and
 * {@code font} tags turned into SubRip markup and other tags dropped. A {@code P} that shows nothing
 * ({@code &nbsp;}) ends the previous cue; the last cue, which no later {@code SYNC} ends, lasts four
 * seconds.
 */
public final class SamiText {
    /**
     * The length of a last cue that no later {@code SYNC} ends, in microseconds.
     */
    private static final long LAST_CUE = 4_000_000;

    /**
     * The largest number of cues a file yields.
     */
    private static final int MAX_CUES = 1 << 22;

    /**
     * The HTML tags of the style bits, in bit order.
     */
    private static final String[] TAGS = {"b", "i", "u", "s"};

    private SamiText() {
        throw new AssertionError();
    }

    /**
     * Holds one language class and its cues.
     *
     * @param name the class name, empty for paragraphs without a class
     * @param language the {@code lang} of the class, empty when it has none
     * @param title the {@code Name} of the class, empty when it has none
     * @param cues the cues in start order, in SubRip markup
     */
    public record Track(String name, String language, String title, List<SubtitleCue> cues) {
    }

    /**
     * Holds a parsed file.
     *
     * @param title the {@code TITLE} of the head, empty when none
     * @param tracks the language classes in the order of the style block, then of first use
     */
    public record Parsed(String title, List<Track> tracks) {
    }

    /**
     * Returns whether text starts like a SAMI file: its first tag is {@code SAMI}.
     *
     * @param text the leading text of a file, without a byte order mark
     * @return {@code true} for a SAMI file
     */
    public static boolean isSignature(String text) {
        var index = 0;
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }
        return text.regionMatches(true, index, "<SAMI", 0, 5)
                && (text.length() == index + 5 || !Character.isLetterOrDigit(text.charAt(index + 5)));
    }

    /**
     * Lowercases the ASCII letters of a text, keeping its length so that indexes found in the copy apply to
     * the text (a full case mapping changes the length of some characters, such as a dotted capital I).
     *
     * @param text the characters to lowercase
     * @return the copy with {@code A} to {@code Z} lowercased
     */
    static String asciiLowerCase(String text) {
        char[] characters = null;
        for (var index = 0; index < text.length(); index++) {
            var character = text.charAt(index);
            if (character >= 'A' && character <= 'Z') {
                if (characters == null) {
                    characters = text.toCharArray();
                }
                characters[index] = (char) (character + ('a' - 'A'));
            }
        }
        return characters == null ? text : new String(characters);
    }

    /**
     * Returns the encoding a {@code META} tag of the head declares.
     *
     * @param data the file bytes
     * @return the charset, or {@code null} when none is declared or the runtime lacks it
     */
    public static Charset declaredCharset(byte[] data) {
        var head = new String(data, 0, Math.min(data.length, 8192), StandardCharsets.ISO_8859_1).toLowerCase(Locale.ROOT);
        var at = head.indexOf("charset=");
        if (at < 0) {
            return null;
        }
        var end = at + 8;
        while (end < head.length() && (Character.isLetterOrDigit(head.charAt(end)) || head.charAt(end) == '-'
                || head.charAt(end) == '_')) {
            end++;
        }
        try {
            return Charset.forName(head.substring(at + 8, end));
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Parses the text of a SAMI file.
     *
     * @param text the file text, with line feeds only
     * @return the parsed file
     */
    public static Parsed parse(String text) {
        var lower = asciiLowerCase(text);
        var classes = new LinkedHashMap<String, String[]>();
        var styleStart = lower.indexOf("<style");
        if (styleStart >= 0) {
            var styleEnd = lower.indexOf("</style", styleStart);
            readClasses(text.substring(styleStart, styleEnd < 0 ? text.length() : styleEnd), classes);
        }
        var title = "";
        var titleStart = lower.indexOf("<title");
        if (titleStart >= 0) {
            var open = lower.indexOf('>', titleStart);
            var close = lower.indexOf("</title", titleStart);
            if (open > 0 && close > open) {
                title = Markup.plain(html(text.substring(open + 1, close)), SubtitleMarkup.SUBRIP).strip();
            }
        }
        var syncs = new ArrayList<long[]>();
        var syncText = new ArrayList<String>();
        var position = 0;
        var bodyClose = lower.indexOf("</body");
        while (syncs.size() < MAX_CUES) {
            var start = lower.indexOf("<sync", position);
            if (start < 0) {
                break;
            }
            var tagEnd = lower.indexOf('>', start);
            if (tagEnd < 0) {
                break;
            }
            var time = attributeNumber(text.substring(start + 5, tagEnd), "start");
            var next = lower.indexOf("<sync", tagEnd);
            var bodyEnd = bodyClose >= tagEnd ? bodyClose : -1;
            var end = next < 0 ? (bodyEnd < 0 ? text.length() : bodyEnd) : bodyEnd >= 0 && bodyEnd < next ? bodyEnd : next;
            position = tagEnd + 1;
            if (time < 0) {
                continue;
            }
            syncs.add(new long[]{time * 1000});
            syncText.add(text.substring(tagEnd + 1, end));
        }
        var cuesByClass = new LinkedHashMap<String, List<SubtitleCue>>();
        var names = new LinkedHashMap<String, String>();
        for (var entry : classes.entrySet()) {
            cuesByClass.put(entry.getKey(), new ArrayList<>());
            names.put(entry.getKey(), entry.getValue()[0]);
        }
        var order = new ArrayList<Integer>();
        for (var index = 0; index < syncs.size(); index++) {
            order.add(index);
        }
        order.sort((a, b) -> Long.compare(syncs.get(a)[0], syncs.get(b)[0]));
        var nextStart = new long[order.size()];
        for (var rank = order.size() - 1; rank >= 0; rank--) {
            var start = syncs.get(order.get(rank))[0];
            var following = rank + 1 < order.size() ? syncs.get(order.get(rank + 1))[0] : -1;
            nextStart[rank] = following > start ? following : rank + 1 < order.size() ? nextStart[rank + 1] : -1;
        }
        for (var rank = 0; rank < order.size(); rank++) {
            var index = order.get(rank);
            var start = syncs.get(index)[0];
            var end = nextStart[rank] > start ? nextStart[rank] : start + LAST_CUE;
            for (var paragraph : paragraphs(syncText.get(index))) {
                var key = paragraph[0].toLowerCase(Locale.ROOT);
                var content = paragraph[1];
                var list = cuesByClass.computeIfAbsent(key, ignored -> new ArrayList<>());
                names.putIfAbsent(key, paragraph[0]);
                if (Markup.plain(content, SubtitleMarkup.SUBRIP).replace('\u00A0', ' ').isBlank()) {
                    continue;
                }
                list.add(SubtitleCue.of(start, end, content));
            }
        }
        var tracks = new ArrayList<Track>();
        for (var entry : cuesByClass.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            var info = classes.get(entry.getKey());
            tracks.add(new Track(names.get(entry.getKey()), info == null ? "" : info[1], info == null ? "" : info[2],
                    List.copyOf(entry.getValue())));
        }
        if (tracks.isEmpty()) {
            var first = classes.values().stream().findFirst().orElse(new String[]{"", "", ""});
            tracks.add(new Track(first[0], first[1], first[2], List.of()));
        }
        return new Parsed(title, List.copyOf(tracks));
    }

    /**
     * Reads the language classes of a style block.
     *
     * @param style the style block
     * @param classes receives the name, language and title of each class by lowercase name
     */
    private static void readClasses(String style, Map<String, String[]> classes) {
        var index = style.indexOf('>') + 1;
        while (true) {
            var open = style.indexOf('{', index);
            var close = open < 0 ? -1 : style.indexOf('}', open);
            if (open < 0 || close < 0) {
                return;
            }
            var selector = style.substring(index, open).replace("<!--", "").replace("-->", "").strip();
            index = close + 1;
            if (!selector.startsWith(".")) {
                continue;
            }
            var name = selector.substring(1).strip();
            if (name.isEmpty() || !name.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '_' || c == '-')) {
                continue;
            }
            var language = "";
            var title = "";
            for (var declaration : style.substring(open + 1, close).split(";")) {
                var colon = declaration.indexOf(':');
                if (colon < 0) {
                    continue;
                }
                var property = declaration.substring(0, colon).strip().toLowerCase(Locale.ROOT);
                var value = declaration.substring(colon + 1).strip();
                if (property.equals("lang")) {
                    language = value;
                } else if (property.equals("name")) {
                    title = value;
                }
            }
            classes.putIfAbsent(name.toLowerCase(Locale.ROOT), new String[]{name, language, title});
        }
    }

    /**
     * Splits the content of a {@code SYNC} element into its paragraphs.
     *
     * @param content the content after the {@code SYNC} tag
     * @return the class name (empty without one) and the SubRip text of each paragraph
     */
    private static List<String[]> paragraphs(String content) {
        var result = new ArrayList<String[]>();
        var lower = asciiLowerCase(content);
        var position = 0;
        while (true) {
            var start = lower.indexOf("<p", position);
            while (start >= 0 && start + 2 < lower.length() && Character.isLetterOrDigit(lower.charAt(start + 2))) {
                start = lower.indexOf("<p", start + 2);
            }
            if (start < 0) {
                break;
            }
            var tagEnd = lower.indexOf('>', start);
            if (tagEnd < 0) {
                break;
            }
            var name = attribute(content.substring(start + 2, tagEnd), "class");
            var next = lower.indexOf("<p", tagEnd);
            while (next >= 0 && next + 2 < lower.length() && Character.isLetterOrDigit(lower.charAt(next + 2))) {
                next = lower.indexOf("<p", next + 2);
            }
            var close = lower.indexOf("</p", tagEnd);
            var end = next < 0 ? content.length() : next;
            if (close >= 0 && close < end) {
                end = close;
            }
            result.add(new String[]{name == null ? "" : name, html(content.substring(tagEnd + 1, end))});
            position = end;
        }
        if (result.isEmpty() && !content.isBlank()) {
            result.add(new String[]{"", html(content)});
        }
        return result;
    }

    /**
     * Converts the HTML content of a paragraph into SubRip text.
     *
     * @param html the content
     * @return the text, lines separated by line feeds
     */
    static String html(String html) {
        var builder = new StringBuilder(html.length());
        var length = html.length();
        var space = false;
        for (var index = 0; index < length; index++) {
            var character = html.charAt(index);
            if (character == '<') {
                var close = html.indexOf('>', index + 1);
                if (close < 0) {
                    break;
                }
                var tag = html.substring(index + 1, close).strip();
                index = close;
                var closing = tag.startsWith("/");
                var body = closing ? tag.substring(1).strip() : tag;
                var nameEnd = 0;
                while (nameEnd < body.length() && Character.isLetterOrDigit(body.charAt(nameEnd))) {
                    nameEnd++;
                }
                var name = body.substring(0, nameEnd).toLowerCase(Locale.ROOT);
                switch (name) {
                    case "br" -> {
                        trimTrailingSpace(builder);
                        builder.append('\n');
                        space = false;
                    }
                    case "b", "i", "u", "s" -> {
                        if (space) {
                            builder.append(' ');
                            space = false;
                        }
                        builder.append('<').append(closing ? "/" : "").append(name).append('>');
                    }
                    case "font" -> {
                        if (space) {
                            builder.append(' ');
                            space = false;
                        }
                        builder.append(closing ? "</font>" : "<font" + body.substring(nameEnd) + ">");
                    }
                    default -> {
                    }
                }
                continue;
            }
            if (character == '&') {
                var semicolon = html.indexOf(';', index + 1);
                var decoded = semicolon > 0 && semicolon - index <= 10
                        ? Markup.characterReference(html.substring(index + 1, semicolon).toLowerCase(Locale.ROOT)) : -1;
                if (decoded >= 0) {
                    if (space) {
                        builder.append(' ');
                        space = false;
                    }
                    builder.appendCodePoint(decoded);
                    index = semicolon;
                    continue;
                }
            }
            if (Character.isWhitespace(character)) {
                space = builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n';
                continue;
            }
            if (space) {
                builder.append(' ');
                space = false;
            }
            builder.append(character);
        }
        trimTrailingSpace(builder);
        return builder.toString().strip();
    }

    /**
     * Removes the spaces that end a builder.
     *
     * @param builder the text
     */
    private static void trimTrailingSpace(StringBuilder builder) {
        while (!builder.isEmpty() && builder.charAt(builder.length() - 1) == ' ') {
            builder.setLength(builder.length() - 1);
        }
    }

    /**
     * Returns an attribute of a tag.
     *
     * @param attributes the tag text after its name
     * @param name the lowercase attribute name
     * @return the value without quotes, or {@code null}
     */
    private static String attribute(String attributes, String name) {
        var lower = asciiLowerCase(attributes);
        var index = 0;
        while (true) {
            var at = lower.indexOf(name, index);
            if (at < 0) {
                return null;
            }
            index = at + name.length();
            if (at > 0 && Character.isLetterOrDigit(lower.charAt(at - 1))) {
                continue;
            }
            var position = at + name.length();
            while (position < lower.length() && Character.isWhitespace(lower.charAt(position))) {
                position++;
            }
            if (position >= lower.length() || lower.charAt(position) != '=') {
                continue;
            }
            position++;
            while (position < lower.length() && Character.isWhitespace(lower.charAt(position))) {
                position++;
            }
            if (position < attributes.length() && (attributes.charAt(position) == '"' || attributes.charAt(position) == '\'')) {
                var quote = attributes.charAt(position);
                var end = attributes.indexOf(quote, position + 1);
                return attributes.substring(position + 1, end < 0 ? attributes.length() : end);
            }
            var end = position;
            while (end < attributes.length() && !Character.isWhitespace(attributes.charAt(end))) {
                end++;
            }
            return attributes.substring(position, end);
        }
    }

    /**
     * Returns an integer attribute of a tag.
     *
     * @param attributes the tag text after its name
     * @param name the lowercase attribute name
     * @return the value, or {@code -1} when absent or not a non-negative integer
     */
    private static long attributeNumber(String attributes, String name) {
        var value = attribute(attributes, name);
        if (value == null) {
            return -1;
        }
        var digits = value.strip();
        var end = 0;
        while (end < digits.length() && CueTimes.isDigit(digits.charAt(end)) && end < 15) {
            end++;
        }
        return end == 0 ? -1 : Long.parseLong(digits.substring(0, end));
    }

    /**
     * Writes cues as a SAMI file.
     *
     * @param tracks the cues of each track, in SubRip markup, with the class name and language of each track
     * @return the file text, with line feeds
     */
    public static String write(List<Track> tracks) {
        var out = new StringBuilder();
        out.append("<SAMI>\n<HEAD>\n<META http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">\n");
        out.append("<STYLE TYPE=\"text/css\">\n<!--\n");
        out.append("P { margin-left: 8pt; margin-right: 8pt; margin-bottom: 2pt; margin-top: 2pt; text-align: center;"
                + " font-size: 20pt; font-family: Arial, sans-serif; font-weight: normal; color: white; }\n");
        for (var track : tracks) {
            out.append('.').append(track.name()).append(" { Name: ")
                    .append(track.title().isEmpty() ? track.name() : track.title().replace(';', ' ').replace('}', ' '));
            if (!track.language().isEmpty()) {
                out.append("; lang: ").append(track.language());
            }
            out.append("; SAMIType: CC; }\n");
        }
        out.append("-->\n</STYLE>\n</HEAD>\n<BODY>\n");
        var times = new TreeSet<Long>();
        for (var track : tracks) {
            for (var cue : track.cues()) {
                times.add(Math.max(0, cue.startMicros()));
                times.add(Math.max(0, cue.endMicros()));
            }
        }
        for (var time : times) {
            out.append("<SYNC Start=").append(Math.floorDiv(time + 500, 1000)).append('>');
            for (var track : tracks) {
                var shown = new StringBuilder();
                for (var cue : track.cues()) {
                    if (cue.startMicros() <= time && time < cue.endMicros()) {
                        if (!shown.isEmpty()) {
                            shown.append("<br>");
                        }
                        shown.append(htmlOf(cue));
                    }
                }
                out.append("<P Class=").append(track.name()).append('>')
                        .append(shown.isEmpty() ? "&nbsp;" : shown).append('\n');
            }
        }
        out.append("</BODY>\n</SAMI>\n");
        return out.toString();
    }

    /**
     * Writes the text of a cue as SAMI HTML.
     *
     * @param cue the cue, in SubRip markup
     * @return the HTML
     */
    private static String htmlOf(SubtitleCue cue) {
        var styled = Markup.parse(cue.text(), SubtitleMarkup.SUBRIP, Markup.Context.NONE);
        var builder = new StringBuilder();
        for (var run : styled.runs()) {
            var close = new ArrayList<String>();
            var font = new StringBuilder();
            if (run.color() != StyledText.NO_COLOR) {
                font.append(" color=\"").append(SubtitleColors.rgb(run.color())).append('"');
            }
            if (!run.font().isEmpty()) {
                font.append(" face=\"").append(run.font().replace('"', '\'')).append('"');
            }
            if (!font.isEmpty()) {
                builder.append("<font").append(font).append('>');
                close.add("font");
            }
            for (var bit = 0; bit < 4; bit++) {
                if ((run.style() & 1 << bit) != 0) {
                    var tag = TAGS[bit];
                    builder.append('<').append(tag).append('>');
                    close.add(tag);
                }
            }
            var lines = run.text().split("\n", -1);
            for (var index = 0; index < lines.length; index++) {
                if (index > 0) {
                    builder.append("<br>");
                }
                escape(builder, lines[index]);
            }
            for (var index = close.size() - 1; index >= 0; index--) {
                builder.append("</").append(close.get(index)).append('>');
            }
        }
        return builder.toString();
    }

    /**
     * Appends text with the characters HTML reserves escaped and runs of spaces kept.
     *
     * @param builder the destination
     * @param text the characters to escape
     */
    private static void escape(StringBuilder builder, String text) {
        for (var index = 0; index < text.length(); index++) {
            var character = text.charAt(index);
            switch (character) {
                case '&' -> builder.append("&amp;");
                case '<' -> builder.append("&lt;");
                case '>' -> builder.append("&gt;");
                case '\u00A0' -> builder.append("&nbsp;");
                case ' ' -> builder.append(index > 0 && text.charAt(index - 1) == ' ' ? "&nbsp;" : " ");
                default -> builder.append(character);
            }
        }
    }
}
