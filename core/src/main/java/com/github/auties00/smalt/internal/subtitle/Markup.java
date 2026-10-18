package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.track.Codec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Converts the markup of subtitle text between the SubRip, WebVTT and ASS conventions.
 *
 * <p>Conversion goes through styled runs: text with a combination of bold, italic and underline.
 * Those three styles survive every conversion; the rest of a format's markup has no counterpart
 * in the others and is dropped: SubRip {@code <font>} tags and {@code {\an8}} positions, WebVTT
 * classes, voices, languages, ruby and timestamp tags, and every ASS override other than
 * {@code \b}, {@code \i}, <code>&#92;u</code> and {@code \r}. Characters a format escapes are unescaped
 * when parsing (WebVTT character references, the ASS {@code \N} and {@code \h}) and escaped when
 * writing. Converting text to its own format returns it unchanged.
 */
public final class Markup {
    /**
     * The bold style bit.
     */
    public static final int BOLD = 1;

    /**
     * The italic style bit.
     */
    public static final int ITALIC = 2;

    /**
     * The underline style bit.
     */
    public static final int UNDERLINE = 4;

    /**
     * The tag names of the style bits, in bit order.
     */
    private static final String[] TAGS = {"b", "i", "u"};

    private Markup() {
        throw new AssertionError();
    }

    /**
     * Holds a run of text in one style.
     *
     * @param text the characters, unescaped
     * @param style the combination of {@link #BOLD}, {@link #ITALIC} and {@link #UNDERLINE}
     */
    public record Span(String text, int style) {
    }

    /**
     * Returns the markup convention of the cue text a codec's packets decode to.
     *
     * @param codec the subtitle codec
     * @return {@link SubtitleMarkup#SUBRIP} for SubRip and 3GPP timed text,
     * {@link SubtitleMarkup#WEBVTT} and {@link SubtitleMarkup#ASS} for WebVTT and ASS
     * @throws IllegalArgumentException if the codec is not a subtitle codec
     */
    public static SubtitleMarkup of(Codec codec) {
        return switch (codec) {
            case SUBRIP, MOV_TEXT -> SubtitleMarkup.SUBRIP;
            case WEBVTT -> SubtitleMarkup.WEBVTT;
            case ASS -> SubtitleMarkup.ASS;
            default -> throw new IllegalArgumentException(codec.displayName() + " is not a subtitle codec");
        };
    }

    /**
     * Converts text from one markup convention to another.
     *
     * @param text the cue text in the {@code from} convention
     * @param from the convention of {@code text}
     * @param to the convention of the result
     * @return the converted text, {@code text} itself when both conventions are the same
     */
    public static String convert(String text, SubtitleMarkup from, SubtitleMarkup to) {
        if (from == to) {
            return text;
        }
        return write(parse(text, from), to);
    }

    /**
     * Returns the text without any markup.
     *
     * @param text the cue text in the {@code from} convention
     * @param from the convention of {@code text}
     * @return the characters shown, lines separated by line feeds
     */
    public static String plain(String text, SubtitleMarkup from) {
        var builder = new StringBuilder(text.length());
        for (var span : parse(text, from)) {
            builder.append(span.text());
        }
        return builder.toString();
    }

    /**
     * Parses text into styled runs.
     *
     * @param text the cue text to parse
     * @param markup the convention
     * @return the runs, adjacent runs always differing in style
     */
    public static List<Span> parse(String text, SubtitleMarkup markup) {
        var parser = new Parser();
        switch (markup) {
            case WEBVTT -> parseWebVtt(text, parser);
            case ASS -> parseAss(text, parser);
            default -> parseSubRip(text, parser);
        }
        return parser.finish();
    }

    /**
     * Writes styled runs in a markup convention.
     *
     * @param spans the runs
     * @param markup the convention
     * @return the text
     */
    public static String write(List<Span> spans, SubtitleMarkup markup) {
        var builder = new StringBuilder();
        var open = new ArrayList<Integer>();
        var current = 0;
        for (var span : spans) {
            if (span.style() != current) {
                if (markup == SubtitleMarkup.ASS) {
                    writeAssStyle(builder, current, span.style());
                } else {
                    for (var index = open.size() - 1; index >= 0; index--) {
                        builder.append("</").append(TAGS[open.get(index)]).append('>');
                    }
                    open.clear();
                    for (var bit = 0; bit < TAGS.length; bit++) {
                        if ((span.style() & (1 << bit)) != 0) {
                            builder.append('<').append(TAGS[bit]).append('>');
                            open.add(bit);
                        }
                    }
                }
                current = span.style();
            }
            switch (markup) {
                case WEBVTT -> escapeWebVtt(builder, span.text());
                case ASS -> escapeAss(builder, span.text());
                default -> builder.append(span.text());
            }
        }
        if (markup == SubtitleMarkup.ASS) {
            if (current != 0) {
                writeAssStyle(builder, current, 0);
            }
        } else {
            for (var index = open.size() - 1; index >= 0; index--) {
                builder.append("</").append(TAGS[open.get(index)]).append('>');
            }
        }
        return builder.toString();
    }

    /**
     * Returns the {@code {\anN}} alignment of SubRip text.
     *
     * @param text the SubRip text
     * @return the numpad alignment from 1 to 9 of the first {@code {\anN}} override, or {@code 0}
     * when there is none
     */
    public static int subRipAlignment(String text) {
        var index = text.indexOf("{\\an");
        if (index < 0 || index + 5 >= text.length()) {
            return 0;
        }
        var digit = text.charAt(index + 4);
        return digit >= '1' && digit <= '9' && text.charAt(index + 5) == '}' ? digit - '0' : 0;
    }

    /**
     * Returns the WebVTT cue settings that place a cue like a SubRip numpad alignment.
     *
     * @param alignment the alignment from 1 to 9, or {@code 0}
     * @return the settings, empty for the default bottom-center placement
     */
    public static String webVttSettings(int alignment) {
        if (alignment < 1 || alignment > 9 || alignment == 2) {
            return "";
        }
        var line = alignment >= 7 ? "line:0" : alignment >= 4 ? "line:50%" : "";
        var column = (alignment - 1) % 3;
        var align = column == 0 ? "align:left" : column == 2 ? "align:right" : "";
        return (line + " " + align).strip();
    }

    /**
     * Parses SubRip text: {@code <b>}, {@code <i>} and {@code <u>} tags style the text,
     * {@code <font>} tags and {@code {\...}} override blocks are dropped, and everything else is
     * literal.
     *
     * @param text the SubRip cue text
     * @param parser the receiver of the runs
     */
    private static void parseSubRip(String text, Parser parser) {
        var length = text.length();
        for (var index = 0; index < length; index++) {
            var character = text.charAt(index);
            if (character == '<') {
                var close = text.indexOf('>', index + 1);
                if (close > 0) {
                    var tag = text.substring(index + 1, close).strip().toLowerCase(Locale.ROOT);
                    var closing = tag.startsWith("/");
                    var name = tagName(closing ? tag.substring(1) : tag);
                    var bit = styleBit(name);
                    if (bit >= 0) {
                        parser.toggle(bit, !closing);
                        index = close;
                        continue;
                    }
                    if (name.equals("font")) {
                        index = close;
                        continue;
                    }
                }
            } else if (character == '{' && index + 1 < length && text.charAt(index + 1) == '\\') {
                var close = text.indexOf('}', index + 2);
                if (close > 0) {
                    index = close;
                    continue;
                }
            }
            parser.append(character);
        }
    }

    /**
     * Parses a WebVTT cue payload: {@code <b>}, {@code <i>} and {@code <u>} tags (with or without
     * classes) style the text, every other tag is dropped, and character references are
     * unescaped.
     *
     * @param text the payload
     * @param parser the receiver of the runs
     */
    private static void parseWebVtt(String text, Parser parser) {
        var length = text.length();
        for (var index = 0; index < length; index++) {
            var character = text.charAt(index);
            if (character == '<') {
                var close = text.indexOf('>', index + 1);
                if (close < 0) {
                    close = length;
                }
                var tag = text.substring(index + 1, close).strip().toLowerCase(Locale.ROOT);
                var closing = tag.startsWith("/");
                var bit = styleBit(tagName(closing ? tag.substring(1) : tag));
                if (bit >= 0) {
                    parser.toggle(bit, !closing);
                }
                index = close;
            } else if (character == '&') {
                var semicolon = text.indexOf(';', index + 1);
                var decoded = semicolon > 0 && semicolon - index <= 10 ? characterReference(text.substring(index + 1, semicolon)) : -1;
                if (decoded >= 0) {
                    parser.appendCodePoint(decoded);
                    index = semicolon;
                } else {
                    parser.append(character);
                }
            } else {
                parser.append(character);
            }
        }
    }

    /**
     * Parses the text of an ASS event: override blocks set the bold, italic and underline styles
     * ({@code \b}, {@code \i}, <code>&#92;u</code>, {@code \r}) and are otherwise dropped, {@code \N} and
     * {@code \n} become line feeds and {@code \h} a no-break space.
     *
     * @param text the event text
     * @param parser the receiver of the runs
     */
    private static void parseAss(String text, Parser parser) {
        var length = text.length();
        for (var index = 0; index < length; index++) {
            var character = text.charAt(index);
            if (character == '{') {
                var close = text.indexOf('}', index + 1);
                if (close > 0) {
                    parseOverrides(text.substring(index + 1, close), parser);
                    index = close;
                    continue;
                }
            } else if (character == '\\' && index + 1 < length) {
                var next = text.charAt(index + 1);
                if (next == 'N' || next == 'n') {
                    parser.append('\n');
                    index++;
                    continue;
                }
                if (next == 'h') {
                    parser.append('\u00A0');
                    index++;
                    continue;
                }
                if (next == '{' || next == '}') {
                    parser.append(next);
                    index++;
                    continue;
                }
            }
            parser.append(character);
        }
    }

    /**
     * Applies the style overrides of one ASS override block.
     *
     * @param block the block content, between the braces
     * @param parser the receiver of the runs
     */
    private static void parseOverrides(String block, Parser parser) {
        var parts = block.split("\\\\");
        for (var part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (part.equals("r") || part.startsWith("r") && !part.startsWith("rnd")) {
                for (var bit = 0; bit < TAGS.length; bit++) {
                    parser.toggle(bit, false);
                }
                continue;
            }
            var name = part.charAt(0);
            var bit = name == 'b' ? 0 : name == 'i' ? 1 : name == 'u' ? 2 : -1;
            if (bit < 0 || part.length() < 2) {
                continue;
            }
            var value = part.substring(1).strip();
            if (!value.chars().allMatch(CueTimes::isDigitCode)) {
                continue;
            }
            parser.toggle(bit, !value.isEmpty() && !value.equals("0"));
        }
    }

    /**
     * Returns the name of a tag without its classes, annotation or attributes.
     *
     * @param tag the tag content after the angle bracket and the optional slash
     * @return the name
     */
    private static String tagName(String tag) {
        var end = 0;
        while (end < tag.length() && Character.isLetterOrDigit(tag.charAt(end))) {
            end++;
        }
        return tag.substring(0, end);
    }

    /**
     * Returns the style bit of a tag name.
     *
     * @param name the lowercase tag name
     * @return the bit index, or {@code -1} when the tag is not a style tag
     */
    private static int styleBit(String name) {
        return switch (name) {
            case "b" -> 0;
            case "i" -> 1;
            case "u" -> 2;
            default -> -1;
        };
    }

    /**
     * Decodes a character reference.
     *
     * @param name the reference between the ampersand and the semicolon
     * @return the code point, or {@code -1} when the reference is not recognized
     */
    private static int characterReference(String name) {
        switch (name) {
            case "amp":
                return '&';
            case "lt":
                return '<';
            case "gt":
                return '>';
            case "nbsp":
                return 0xA0;
            case "lrm":
                return 0x200E;
            case "rlm":
                return 0x200F;
            case "quot":
                return '"';
            case "apos":
                return '\'';
            default:
                break;
        }
        if (name.length() < 2 || name.charAt(0) != '#') {
            return -1;
        }
        try {
            var value = name.charAt(1) == 'x' || name.charAt(1) == 'X'
                    ? Integer.parseInt(name.substring(2), 16) : Integer.parseInt(name.substring(1));
            return Character.isValidCodePoint(value) && value != 0 ? value : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    /**
     * Appends text with the characters WebVTT reserves escaped.
     *
     * @param builder the destination
     * @param text the characters to append
     */
    private static void escapeWebVtt(StringBuilder builder, String text) {
        for (var index = 0; index < text.length(); index++) {
            var character = text.charAt(index);
            switch (character) {
                case '&' -> builder.append("&amp;");
                case '<' -> builder.append("&lt;");
                case '>' -> builder.append("&gt;");
                default -> builder.append(character);
            }
        }
    }

    /**
     * Appends text with line feeds as {@code \N} and braces escaped, as ASS writes them.
     *
     * @param builder the destination
     * @param text the characters to append
     */
    private static void escapeAss(StringBuilder builder, String text) {
        for (var index = 0; index < text.length(); index++) {
            var character = text.charAt(index);
            switch (character) {
                case '\n' -> builder.append("\\N");
                case '{' -> builder.append("\\{");
                case '}' -> builder.append("\\}");
                default -> builder.append(character);
            }
        }
    }

    /**
     * Appends the ASS override block that changes one style combination into another.
     *
     * @param builder the destination
     * @param from the current style
     * @param to the new style
     */
    private static void writeAssStyle(StringBuilder builder, int from, int to) {
        builder.append('{');
        for (var bit = 0; bit < TAGS.length; bit++) {
            var mask = 1 << bit;
            if ((from & mask) != (to & mask)) {
                builder.append('\\').append(TAGS[bit]).append((to & mask) != 0 ? '1' : '0');
            }
        }
        builder.append('}');
    }

    /**
     * Collects characters into styled runs, tracking nested style tags with counters.
     */
    private static final class Parser {
        /**
         * The runs completed so far.
         */
        private final List<Span> spans = new ArrayList<>();

        /**
         * The characters of the current run.
         */
        private final StringBuilder text = new StringBuilder();

        /**
         * The nesting depth of each style tag.
         */
        private final int[] depth = new int[TAGS.length];

        /**
         * The style of the current run.
         */
        private int style;

        /**
         * Opens or closes a style.
         *
         * @param bit the style bit index
         * @param open {@code true} to open the style, {@code false} to close it
         */
        void toggle(int bit, boolean open) {
            depth[bit] = open ? depth[bit] + 1 : Math.max(0, depth[bit] - 1);
            var next = 0;
            for (var index = 0; index < depth.length; index++) {
                if (depth[index] > 0) {
                    next |= 1 << index;
                }
            }
            if (next != style) {
                flush();
                style = next;
            }
        }

        /**
         * Appends one character to the current run.
         *
         * @param character the character to append
         */
        void append(char character) {
            text.append(character);
        }

        /**
         * Appends one code point to the current run.
         *
         * @param codePoint the code point
         */
        void appendCodePoint(int codePoint) {
            text.appendCodePoint(codePoint);
        }

        /**
         * Completes the current run, when it has text.
         */
        private void flush() {
            if (text.isEmpty()) {
                return;
            }
            if (!spans.isEmpty() && spans.getLast().style() == style) {
                var last = spans.removeLast();
                spans.add(new Span(last.text() + text, style));
            } else {
                spans.add(new Span(text.toString(), style));
            }
            text.setLength(0);
        }

        /**
         * Completes the last run and returns every run.
         *
         * @return the runs
         */
        List<Span> finish() {
            flush();
            return spans;
        }
    }
}
