package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.track.Codec;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.IntPredicate;

/**
 * Converts the markup of subtitle text between the SubRip, WebVTT and ASS conventions, through
 * {@link StyledText}.
 *
 * <p>What each convention carries:
 * <ul>
 *   <li>SubRip: {@code <b>}, {@code <i>}, {@code <u>} and {@code <s>} tags, {@code <font>} tags with
 *       {@code color}, {@code face} and {@code size} attributes, and ASS override blocks
 *       ({@code {\an8}}, {@code {\pos(x,y)}} and the other overrides ASS reads); written with the tags
 *       and an {@code {\anN}} placement.</li>
 *   <li>WebVTT: {@code <b>}, {@code <i>} and {@code <u>} tags, the default color classes ({@code <c.yellow>},
 *       {@code <c.bg_black>}), the first voice span as the speaker, timestamp tags as karaoke times, and
 *       character references; the placement lives in the cue settings ({@link #webVttSettings(StyledText)}).
 *       Fonts, sizes, strikeout and colors without a default class are not written.</li>
 *   <li>ASS: the override blocks {@code \b}, {@code \i}, <code>&#92;u</code>, {@code \s}, {@code \c} and {@code \1c},
 *       {@code \alpha} and {@code \1a}, {@code \fn}, {@code \fs}, {@code \an} and {@code \a}, {@code \pos} and
 *       the start of {@code \move}, the karaoke tags {@code \k}, {@code \K}, {@code \kf} and {@code \ko}, and
 *       {@code \r}; {@code \N}, {@code \n} and {@code \h}. Other overrides are dropped. The speaker is the
 *       event's {@code Name} field, carried in the cue settings.</li>
 * </ul>
 * Positions and font sizes are scaled between an ASS script's resolution and the frame. Converting text
 * to its own convention returns it unchanged.
 */
public final class Markup {
    /**
     * The tag names of the style bits, in bit order.
     */
    private static final String[] TAGS = {"b", "i", "u", "s"};

    /**
     * The ASS override tags, longest first, so that a tag is matched before its prefixes.
     */
    private static final String[] ASS_TAGS = {"iclip", "alpha", "xbord", "ybord", "xshad", "yshad", "fscx", "fscy",
            "fade", "move", "blur", "bord", "clip", "shad", "fad", "fax", "fay", "frx", "fry", "frz", "fsp", "org",
            "pbo", "pos", "1c", "2c", "3c", "4c", "1a", "2a", "3a", "4a", "an", "be", "fe", "fn", "fr", "fs", "kf", "ko",
            "a", "b", "c", "i", "k", "K", "p", "q", "r", "s", "t", "u"};

    private Markup() {
        throw new AssertionError();
    }

    /**
     * Holds what a conversion knows beyond the text: the cue times, which place karaoke times, and the ASS
     * script, which defines the styles, positions and font sizes of ASS text.
     *
     * @param startMicros the cue start in microseconds, or {@code -1} when unknown
     * @param endMicros the cue end in microseconds, or {@code -1} when unknown
     * @param script the ASS script of the track, or {@code null}
     * @param assStyle the style of the ASS event, empty for the script's default
     */
    public record Context(long startMicros, long endMicros, AssScript script, String assStyle) {
        /**
         * The context of text converted on its own.
         */
        public static final Context NONE = new Context(-1, -1, null, "");

        /**
         * Returns the context of a cue.
         *
         * @param cue the subtitle cue
         * @return the context with the cue times and no script
         */
        public static Context of(SubtitleCue cue) {
            return new Context(cue.startMicros(), cue.endMicros(), null, "");
        }

        /**
         * Returns a copy with an ASS script and style.
         *
         * @param script the script, or {@code null}
         * @param style the event style
         * @return the copy
         */
        public Context withScript(AssScript script, String style) {
            return new Context(startMicros, endMicros, script, style);
        }

        /**
         * {@return the width of the ASS coordinate space}
         */
        int width() {
            return script == null ? 384 : script.width();
        }

        /**
         * {@return the height of the ASS coordinate space}
         */
        int height() {
            return script == null ? 288 : script.height();
        }
    }

    /**
     * Returns the markup convention of the cue text a codec's packets decode to.
     *
     * @param codec the subtitle codec
     * @return {@link SubtitleMarkup#SUBRIP} for SubRip, 3GPP timed text and TTML,
     * {@link SubtitleMarkup#WEBVTT} and {@link SubtitleMarkup#ASS} for WebVTT and ASS
     * @throws IllegalArgumentException if the codec is not a subtitle codec
     */
    public static SubtitleMarkup of(Codec codec) {
        return switch (codec) {
            case SUBRIP, MOV_TEXT, TTML -> SubtitleMarkup.SUBRIP;
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
        return write(parse(text, from, Context.NONE), to, Context.NONE);
    }

    /**
     * Returns the text without any markup.
     *
     * @param text the cue text in the {@code from} convention
     * @param from the convention of {@code text}
     * @return the characters shown, lines separated by line feeds
     */
    public static String plain(String text, SubtitleMarkup from) {
        return parse(text, from, Context.NONE).plain();
    }

    /**
     * Parses text into styled runs.
     *
     * @param text the cue text to parse
     * @param markup the convention
     * @param context the cue times and ASS script
     * @return the styled text; the placement is the one the text itself sets
     */
    public static StyledText parse(String text, SubtitleMarkup markup, Context context) {
        return switch (markup) {
            case WEBVTT -> parseWebVtt(text, context);
            case ASS -> parseAss(text, context);
            default -> parseSubRip(text, context);
        };
    }

    /**
     * Parses a cue: its text and the placement and speaker its settings carry.
     *
     * @param cue the subtitle cue
     * @param markup the convention of the cue text
     * @param context the cue times and ASS script
     * @return the styled text
     */
    public static StyledText parseCue(SubtitleCue cue, SubtitleMarkup markup, Context context) {
        if (markup == SubtitleMarkup.ASS) {
            var event = AssEvents.withFields(-1, cue.settings(), cue.text());
            var styled = parseAss(cue.text(), context.withScript(context.script(), event.style()));
            return event.name().isBlank() ? styled : styled.withVoice(event.name().strip());
        }
        var styled = parse(cue.text(), markup, context);
        if (markup == SubtitleMarkup.WEBVTT && !cue.settings().isEmpty()) {
            styled = withWebVttSettings(styled, cue.settings());
        }
        return styled;
    }

    /**
     * Writes a cue in a convention: its text, and the settings that carry its placement (WebVTT) or speaker
     * (ASS).
     *
     * @param styled the styled text
     * @param startMicros the cue start in microseconds
     * @param endMicros the cue end in microseconds
     * @param markup the convention
     * @param context the ASS script of the target track, or a context without one
     * @return the cue
     */
    public static SubtitleCue toCue(StyledText styled, long startMicros, long endMicros, SubtitleMarkup markup,
                                    Context context) {
        var timed = new Context(startMicros, endMicros, context.script(), context.assStyle());
        var text = write(styled, markup, timed);
        return switch (markup) {
            case WEBVTT -> new SubtitleCue(startMicros, endMicros, text, "", webVttSettings(styled));
            case ASS -> new SubtitleCue(startMicros, endMicros, text, "", styled.voice().isEmpty() ? ""
                    : "0,Default," + assName(styled.voice()) + ",0,0,0,");
            default -> SubtitleCue.of(startMicros, endMicros, text);
        };
    }

    /**
     * Returns a speaker as an ASS {@code Name} field can hold it.
     *
     * @param voice the speaker
     * @return the name without commas or line breaks
     */
    private static String assName(String voice) {
        return voice.replace(',', ' ').replace('\n', ' ').strip();
    }

    /**
     * Writes styled runs in a markup convention.
     *
     * @param styled the styled text
     * @param markup the convention
     * @param context the cue times and the ASS script of the target
     * @return the text
     */
    public static String write(StyledText styled, SubtitleMarkup markup, Context context) {
        return switch (markup) {
            case WEBVTT -> writeWebVtt(styled, context);
            case ASS -> writeAss(styled, context);
            default -> writeSubRip(styled);
        };
    }

    /**
     * Returns the {@code {\anN}} alignment of SubRip text.
     *
     * @param text the SubRip text
     * @return the numpad alignment from 1 to 9 of the first {@code {\anN}} override, or {@code 0}
     * when there is none
     */
    public static int subRipAlignment(String text) {
        return parseSubRip(text, Context.NONE).alignment();
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
     * Returns the WebVTT cue settings of a placement: the alignment's {@code line} and {@code align}, or for
     * an anchored cue a {@code line} and a {@code position} in percent anchored as the alignment says.
     *
     * @param styled the styled text
     * @return the settings, empty for the default bottom-center placement
     */
    public static String webVttSettings(StyledText styled) {
        if (!styled.hasPosition()) {
            return webVttSettings(styled.alignment());
        }
        var alignment = styled.alignment() == 0 ? 2 : styled.alignment();
        var column = (alignment - 1) % 3;
        var row = (alignment - 1) / 3;
        var lineAlign = row == 0 ? "end" : row == 1 ? "center" : "start";
        var positionAlign = column == 0 ? "line-left" : column == 1 ? "center" : "line-right";
        var settings = "line:" + percent(styled.y()) + "," + lineAlign + " position:" + percent(styled.x()) + ","
                + positionAlign;
        return column == 0 ? settings + " align:left" : column == 2 ? settings + " align:right" : settings;
    }

    /**
     * Applies the placement of WebVTT cue settings to styled text that has none.
     *
     * @param styled the styled text
     * @param settings the cue settings list
     * @return the styled text with the placement of the settings, or {@code styled} when it has its own or
     * the settings set none
     */
    public static StyledText withWebVttSettings(StyledText styled, String settings) {
        if (styled.alignment() != 0 || styled.hasPosition()) {
            return styled;
        }
        var column = -1;
        var row = -1;
        var linePercent = Double.NaN;
        var lineAlign = "";
        var positionPercent = Double.NaN;
        var positionColumn = -1;
        for (var setting : settings.strip().split("[ \t]+")) {
            var colon = setting.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            var name = setting.substring(0, colon);
            var value = setting.substring(colon + 1);
            var comma = value.indexOf(',');
            var alignValue = comma < 0 ? "" : value.substring(comma + 1);
            var number = comma < 0 ? value : value.substring(0, comma);
            switch (name) {
                case "align" -> column = switch (value) {
                    case "start", "left" -> 0;
                    case "end", "right" -> 2;
                    case "center", "middle" -> 1;
                    default -> column;
                };
                case "line" -> {
                    lineAlign = alignValue;
                    if (number.endsWith("%")) {
                        linePercent = percentValue(number);
                        if (!Double.isNaN(linePercent)) {
                            row = linePercent < 100.0 / 3 ? 2 : linePercent < 200.0 / 3 ? 1 : 0;
                        }
                    } else {
                        try {
                            row = Integer.parseInt(number) >= 0 ? 2 : 0;
                        } catch (NumberFormatException exception) {
                            row = -1;
                        }
                    }
                }
                case "position" -> {
                    positionPercent = number.endsWith("%") ? percentValue(number) : Double.NaN;
                    positionColumn = switch (alignValue) {
                        case "line-left" -> 0;
                        case "center" -> 1;
                        case "line-right" -> 2;
                        default -> -1;
                    };
                }
                default -> {
                }
            }
        }
        if (!Double.isNaN(linePercent) && !Double.isNaN(positionPercent)) {
            var anchorRow = switch (lineAlign) {
                case "center" -> 1;
                case "end" -> 0;
                default -> 2;
            };
            var anchorColumn = positionColumn >= 0 ? positionColumn : column >= 0 ? column : 1;
            return styled.withPlacement(anchorRow * 3 + anchorColumn + 1, positionPercent / 100, linePercent / 100);
        }
        if (row < 0 && column < 0) {
            return styled;
        }
        var alignment = (row < 0 ? 0 : row) * 3 + (column < 0 ? 1 : column) + 1;
        return alignment == 2 ? styled : styled.withPlacement(alignment, Double.NaN, Double.NaN);
    }

    /**
     * Parses a percentage.
     *
     * @param value the value with its percent sign
     * @return the number, or {@code NaN} when it is not a percentage from 0 to 100
     */
    private static double percentValue(String value) {
        try {
            var number = Double.parseDouble(value.substring(0, value.length() - 1));
            return number >= 0 && number <= 100 ? number : Double.NaN;
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }

    /**
     * Formats a fraction as a percentage with at most two decimals.
     *
     * @param fraction the fraction, clamped to 0 to 1
     * @return the percentage with its sign
     */
    private static String percent(double fraction) {
        var value = Math.round(Math.clamp(fraction, 0, 1) * 10000) / 100.0;
        var text = value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
        return text + "%";
    }

    /**
     * Parses SubRip text.
     *
     * @param text the SubRip cue text
     * @param context the cue times, for karaoke overrides
     * @return the styled text
     */
    private static StyledText parseSubRip(String text, Context context) {
        var builder = new StyledText.Builder();
        var depth = new int[TAGS.length];
        var fonts = new ArrayDeque<StyledText.Run>();
        var ass = new AssState(builder, Context.NONE, StyledText.Run.of("", 0));
        var length = text.length();
        for (var index = 0; index < length; index++) {
            var character = text.charAt(index);
            if (character == '<') {
                var close = text.indexOf('>', index + 1);
                if (close > 0) {
                    var tag = text.substring(index + 1, close).strip();
                    var closing = tag.startsWith("/");
                    var body = closing ? tag.substring(1).strip() : tag;
                    var name = tagName(body).toLowerCase(Locale.ROOT);
                    var bit = styleBit(name);
                    if (bit >= 0) {
                        depth[bit] = closing ? Math.max(0, depth[bit] - 1) : depth[bit] + 1;
                        builder.style(styleOf(depth));
                        index = close;
                        continue;
                    }
                    if (name.equals("font")) {
                        if (closing) {
                            if (!fonts.isEmpty()) {
                                var previous = fonts.pop();
                                var current = builder.format();
                                builder.format(new StyledText.Run("", current.style(), previous.color(),
                                        current.background(), previous.font(), previous.size(), current.karaoke()));
                            }
                        } else {
                            var current = builder.format();
                            fonts.push(current);
                            builder.format(fontTag(body.substring(name.length()), current));
                        }
                        index = close;
                        continue;
                    }
                }
            } else if (character == '{' && index + 1 < length && text.charAt(index + 1) == '\\') {
                var close = text.indexOf('}', index + 2);
                if (close > 0) {
                    ass.overrides(text.substring(index + 1, close));
                    for (var bit = 0; bit < TAGS.length; bit++) {
                        depth[bit] = (builder.format().style() & 1 << bit) != 0 ? Math.max(1, depth[bit]) : 0;
                    }
                    index = close;
                    continue;
                }
            }
            builder.append(character);
        }
        return builder.build();
    }

    /**
     * Returns the format a {@code <font>} tag opens.
     *
     * @param attributes the tag text after its name
     * @param current the format before the tag
     * @return the format inside the tag
     */
    private static StyledText.Run fontTag(String attributes, StyledText.Run current) {
        var color = current.color();
        var font = current.font();
        var size = current.size();
        for (var attribute : attributes(attributes)) {
            switch (attribute[0]) {
                case "color" -> {
                    var parsed = SubtitleColors.parseCss(attribute[1]);
                    if (parsed != StyledText.NO_COLOR) {
                        color = parsed;
                    }
                }
                case "face" -> font = attribute[1].strip();
                case "size" -> {
                    try {
                        var parsed = Double.parseDouble(attribute[1].strip());
                        if (parsed > 0 && parsed < 10000) {
                            size = parsed;
                        }
                    } catch (NumberFormatException ignored) {
                        // a size that is not a number is not applied
                    }
                }
                default -> {
                }
            }
        }
        return new StyledText.Run("", current.style(), color, current.background(), font, size, current.karaoke());
    }

    /**
     * Splits the attributes of a tag.
     *
     * @param text the tag text after its name
     * @return the lowercase names and their values, quotes removed
     */
    private static List<String[]> attributes(String text) {
        var result = new ArrayList<String[]>();
        var index = 0;
        var length = text.length();
        while (index < length) {
            while (index < length && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
            var start = index;
            while (index < length && text.charAt(index) != '=' && !Character.isWhitespace(text.charAt(index))) {
                index++;
            }
            var name = text.substring(start, index).toLowerCase(Locale.ROOT);
            while (index < length && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
            if (index >= length || text.charAt(index) != '=') {
                if (!name.isEmpty()) {
                    result.add(new String[]{name, ""});
                }
                continue;
            }
            index++;
            while (index < length && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
            String value;
            if (index < length && (text.charAt(index) == '"' || text.charAt(index) == '\'')) {
                var quote = text.charAt(index);
                var end = text.indexOf(quote, index + 1);
                if (end < 0) {
                    end = length;
                }
                value = text.substring(index + 1, end);
                index = Math.min(length, end + 1);
            } else {
                var end = index;
                while (end < length && !Character.isWhitespace(text.charAt(end))) {
                    end++;
                }
                value = text.substring(index, end);
                index = end;
            }
            if (!name.isEmpty()) {
                result.add(new String[]{name, value});
            }
        }
        return result;
    }

    /**
     * Parses a WebVTT cue payload.
     *
     * @param text the payload
     * @param context the cue times, which place timestamp tags
     * @return the styled text
     */
    private static StyledText parseWebVtt(String text, Context context) {
        var builder = new StyledText.Builder();
        var depth = new int[TAGS.length];
        var colors = new ArrayDeque<StyledText.Run>();
        var length = text.length();
        for (var index = 0; index < length; index++) {
            var character = text.charAt(index);
            if (character == '<') {
                var close = text.indexOf('>', index + 1);
                if (close < 0) {
                    close = length;
                }
                var tag = text.substring(index + 1, close).strip();
                index = close;
                if (!tag.isEmpty() && CueTimes.isDigit(tag.charAt(0))) {
                    var end = new int[1];
                    var time = CueTimes.parse(tag, 0, end);
                    if (time >= 0 && context.startMicros() >= 0 && time >= context.startMicros()) {
                        builder.karaoke(time - context.startMicros());
                    }
                    continue;
                }
                var closing = tag.startsWith("/");
                var body = closing ? tag.substring(1).strip() : tag;
                var name = tagName(body).toLowerCase(Locale.ROOT);
                var bit = styleBit(name);
                if (bit == 3) {
                    bit = -1;
                }
                if (closing) {
                    if (bit >= 0) {
                        depth[bit] = Math.max(0, depth[bit] - 1);
                        builder.style(styleOf(depth));
                    } else if (name.equals("c") && !colors.isEmpty()) {
                        var previous = colors.pop();
                        var current = builder.format();
                        builder.format(new StyledText.Run("", current.style(), previous.color(), previous.background(),
                                current.font(), current.size(), current.karaoke()));
                    }
                    continue;
                }
                if (bit >= 0) {
                    depth[bit]++;
                    builder.style(styleOf(depth));
                } else if (name.equals("v")) {
                    var space = indexOfBlank(body);
                    if (space > 0) {
                        builder.voice(body.substring(space + 1).strip());
                    }
                }
                if (name.equals("c")) {
                    var current = builder.format();
                    colors.push(current);
                    var color = current.color();
                    var background = current.background();
                    var classes = body.substring(name.length());
                    var space = indexOfBlank(classes);
                    if (space >= 0) {
                        classes = classes.substring(0, space);
                    }
                    for (var className : classes.split("\\.")) {
                        if (className.startsWith("bg_")) {
                            var parsed = SubtitleColors.webVttClass(className.substring(3));
                            background = parsed != StyledText.NO_COLOR ? parsed : background;
                        } else {
                            var parsed = SubtitleColors.webVttClass(className);
                            color = parsed != StyledText.NO_COLOR ? parsed : color;
                        }
                    }
                    builder.format(new StyledText.Run("", current.style(), color, background, current.font(),
                            current.size(), current.karaoke()));
                }
            } else if (character == '&') {
                var semicolon = text.indexOf(';', index + 1);
                var decoded = semicolon > 0 && semicolon - index <= 10 ? characterReference(text.substring(index + 1, semicolon)) : -1;
                if (decoded >= 0) {
                    builder.appendCodePoint(decoded);
                    index = semicolon;
                } else {
                    builder.append(character);
                }
            } else {
                builder.append(character);
            }
        }
        return builder.build();
    }

    /**
     * Returns the index of the first space, tab or line feed.
     *
     * @param text the text to search
     * @return the index, or {@code -1}
     */
    private static int indexOfBlank(String text) {
        for (var index = 0; index < text.length(); index++) {
            var character = text.charAt(index);
            if (character == ' ' || character == '\t' || character == '\n') {
                return index;
            }
        }
        return -1;
    }

    /**
     * Parses the text of an ASS event.
     *
     * @param text the event text
     * @param context the ASS script, its event style and the cue times
     * @return the styled text, with the style's alignment when the text sets none
     */
    private static StyledText parseAss(String text, Context context) {
        var builder = new StyledText.Builder();
        var style = context.script() == null ? null : context.script().style(context.assStyle());
        var base = baseOf(style);
        builder.format(base);
        var state = new AssState(builder, context, base);
        var softBreak = context.script() != null && context.script().softBreaksAreLineBreaks() ? '\n' : ' ';
        var length = text.length();
        for (var index = 0; index < length; index++) {
            var character = text.charAt(index);
            if (character == '{') {
                var close = text.indexOf('}', index + 1);
                if (close > 0) {
                    state.overrides(text.substring(index + 1, close));
                    index = close;
                    continue;
                }
            } else if (character == '\\' && index + 1 < length) {
                var next = text.charAt(index + 1);
                if (next == 'N') {
                    builder.append('\n');
                    index++;
                    continue;
                }
                if (next == 'n') {
                    builder.append(softBreak);
                    index++;
                    continue;
                }
                if (next == 'h') {
                    builder.append('\u00A0');
                    index++;
                    continue;
                }
                if (next == '{' || next == '}') {
                    builder.append(next);
                    index++;
                    continue;
                }
            }
            builder.append(character);
        }
        var styled = builder.build();
        if (styled.alignment() == 0 && style != null && style.alignment() != 2) {
            styled = styled.withPlacement(style.alignment(), styled.x(), styled.y());
        }
        return styled;
    }

    /**
     * Returns the format an ASS style gives its events: its bold, italic, underline and strikeout, and its
     * primary color unless it is opaque white; its font and size are the look of the whole track and are not
     * carried.
     *
     * @param style the style, or {@code null}
     * @return the format
     */
    private static StyledText.Run baseOf(AssScript.Style style) {
        if (style == null) {
            return StyledText.Run.of("", 0);
        }
        var color = style.primary() == 0xFFFFFFFFL ? StyledText.NO_COLOR : style.primary();
        return new StyledText.Run("", style.style(), color, StyledText.NO_COLOR, "", Double.NaN, StyledText.NO_KARAOKE);
    }

    /**
     * Applies ASS override blocks to a builder.
     */
    private static final class AssState {
        /**
         * The receiver of the runs.
         */
        private final StyledText.Builder builder;

        /**
         * The script, the event style and the cue times.
         */
        private final Context context;

        /**
         * The format of the event style.
         */
        private final StyledText.Run base;

        /**
         * The start of the next karaoke syllable, in microseconds from the cue start.
         */
        private long syllable;

        /**
         * Constructs the state of one event.
         *
         * @param builder the receiver of the runs
         * @param context the script, the event style and the cue times
         * @param base the format of the event style
         */
        AssState(StyledText.Builder builder, Context context, StyledText.Run base) {
            this.builder = builder;
            this.context = context;
            this.base = base;
        }

        /**
         * Applies the overrides of one block.
         *
         * @param block the block content, between the braces
         */
        void overrides(String block) {
            var length = block.length();
            var index = block.indexOf('\\');
            while (index >= 0 && index < length) {
                index++;
                var tag = matchTag(block, index);
                if (tag == null) {
                    index = block.indexOf('\\', index);
                    continue;
                }
                index += tag.length();
                String argument;
                if (index < length && block.charAt(index) == '(') {
                    var end = closingParenthesis(block, index);
                    argument = block.substring(index + 1, Math.min(end, length));
                    index = end + 1;
                } else {
                    var end = block.indexOf('\\', index);
                    if (end < 0) {
                        end = length;
                    }
                    argument = block.substring(index, end);
                    index = end;
                }
                apply(tag, argument.strip());
                if (index < length && block.charAt(index) != '\\') {
                    index = block.indexOf('\\', index);
                }
            }
        }

        /**
         * Applies one override.
         *
         * @param tag the tag name
         * @param argument the argument, without the parentheses
         */
        private void apply(String tag, String argument) {
            var current = builder.format();
            switch (tag) {
                case "b" -> setBit(StyledText.BOLD, argument, value -> value == 1 || value >= 700);
                case "i" -> setBit(StyledText.ITALIC, argument, value -> value == 1);
                case "u" -> setBit(StyledText.UNDERLINE, argument, value -> value == 1);
                case "s" -> setBit(StyledText.STRIKEOUT, argument, value -> value == 1);
                case "c", "1c" -> {
                    var color = argument.isEmpty() ? base.color() : SubtitleColors.parseAss(argument, false);
                    if (argument.isEmpty() || color != StyledText.NO_COLOR) {
                        if (!argument.isEmpty()) {
                            var alpha = current.color() != StyledText.NO_COLOR ? current.color() & 0xFF000000L
                                    : 0xFF000000L;
                            color = color & 0xFFFFFF | alpha;
                        }
                        format(current.style(), color, current.font(), current.size());
                    }
                }
                case "alpha", "1a" -> {
                    var alpha = SubtitleColors.parseAss(argument, false);
                    if (alpha != StyledText.NO_COLOR) {
                        var opacity = alpha >>> 16 & 0xFF;
                        var color = current.color() != StyledText.NO_COLOR ? current.color()
                                : base.color() != StyledText.NO_COLOR ? base.color() : 0xFFFFFFFFL;
                        format(current.style(), (0xFF - opacity) << 24 | color & 0xFFFFFF, current.font(), current.size());
                    }
                }
                case "fn" -> format(current.style(), current.color(), argument.isEmpty() ? base.font() : argument,
                        current.size());
                case "fs" -> {
                    var size = number(argument);
                    if (argument.startsWith("+") || argument.startsWith("-")) {
                        if (!Double.isNaN(size) && !Double.isNaN(current.size())) {
                            var scaled = current.size() * (1 + size / 10);
                            format(current.style(), current.color(), current.font(), scaled > 0 && scaled < 100000
                                    ? scaled : base.size());
                        }
                    } else if (argument.isEmpty() || size == 0) {
                        format(current.style(), current.color(), current.font(), base.size());
                    } else if (size > 0 && size < 100000) {
                        format(current.style(), current.color(), current.font(),
                                size * StyledText.REFERENCE_HEIGHT / context.height());
                    }
                }
                case "an" -> {
                    var value = (int) number(argument);
                    builder.alignment(value);
                }
                case "a" -> {
                    var value = (int) number(argument);
                    if (value >= 1 && value <= 11) {
                        builder.alignment(AssScript.fromLegacyAlignment(value));
                    }
                }
                case "pos", "move" -> {
                    var parts = argument.split(",");
                    if (parts.length >= 2) {
                        var x = number(parts[0]);
                        var y = number(parts[1]);
                        if (!Double.isNaN(x) && !Double.isNaN(y)) {
                            builder.position(x / context.width(), y / context.height());
                        }
                    }
                }
                case "k", "K", "kf", "ko" -> {
                    var centis = number(argument);
                    builder.karaoke(syllable);
                    if (centis > 0 && centis < 1e9) {
                        syllable += (long) (centis * 10000);
                    }
                }
                case "r" -> {
                    var reset = argument.isEmpty() || context.script() == null ? base
                            : baseOf(context.script().style(argument));
                    builder.format(new StyledText.Run("", reset.style(), reset.color(), reset.background(), reset.font(),
                            reset.size(), current.karaoke()));
                }
                default -> {
                }
            }
        }

        /**
         * Sets or clears a style bit from an override argument.
         *
         * @param bit the style bit
         * @param argument the argument: empty for the style's value, otherwise a number
         * @param on whether a number turns the bit on
         */
        private void setBit(int bit, String argument, IntPredicate on) {
            var current = builder.format();
            boolean set;
            if (argument.isEmpty()) {
                set = (base.style() & bit) != 0;
            } else {
                if (!argument.chars().allMatch(CueTimes::isDigitCode)) {
                    return;
                }
                var value = argument.length() > 6 ? 0 : Integer.parseInt(argument);
                set = on.test(value);
            }
            builder.style(set ? current.style() | bit : current.style() & ~bit);
        }

        /**
         * Sets the color, font and size of the next characters.
         *
         * @param style the style bits
         * @param color the color as {@code 0xAARRGGBB}
         * @param font the font family
         * @param size the font size
         */
        private void format(int style, long color, String font, double size) {
            var current = builder.format();
            builder.format(new StyledText.Run("", style, color, current.background(), font, size, current.karaoke()));
        }
    }

    /**
     * Returns the ASS override tag that starts at a position.
     *
     * @param block the override block
     * @param index the index after the backslash
     * @return the tag name, or {@code null} when none starts there
     */
    private static String matchTag(String block, int index) {
        for (var tag : ASS_TAGS) {
            if (block.startsWith(tag, index)) {
                return tag;
            }
        }
        return null;
    }

    /**
     * Returns the index of the parenthesis that closes the one at a position.
     *
     * @param block the text
     * @param open the index of the opening parenthesis
     * @return the index of the closing parenthesis, or the text length when it is not closed
     */
    private static int closingParenthesis(String block, int open) {
        var depth = 0;
        for (var index = open; index < block.length(); index++) {
            var character = block.charAt(index);
            if (character == '(') {
                depth++;
            } else if (character == ')' && --depth == 0) {
                return index;
            }
        }
        return block.length();
    }

    /**
     * Parses a decimal number, ignoring a leading plus sign.
     *
     * @param text the number
     * @return the value, or {@code NaN} when it is not a number
     */
    private static double number(String text) {
        var value = text.strip();
        if (value.startsWith("+")) {
            value = value.substring(1);
        }
        if (value.isEmpty() || value.length() > 32) {
            return Double.NaN;
        }
        try {
            var parsed = Double.parseDouble(value);
            return Double.isFinite(parsed) ? parsed : Double.NaN;
        } catch (NumberFormatException exception) {
            return Double.NaN;
        }
    }

    /**
     * Writes SubRip text: an {@code {\anN}} placement, then {@code <font>} and style tags.
     *
     * @param styled the styled text
     * @return the text
     */
    private static String writeSubRip(StyledText styled) {
        var builder = new StringBuilder();
        if (styled.alignment() != 0 && styled.alignment() != 2) {
            builder.append("{\\an").append(styled.alignment()).append('}');
        }
        var open = new ArrayList<String>();
        var font = "";
        var currentStyle = 0;
        for (var run : styled.runs()) {
            var attributes = fontAttributes(run);
            if (!attributes.equals(font)) {
                closeTags(builder, open);
                if (!font.isEmpty()) {
                    builder.append("</font>");
                }
                if (!attributes.isEmpty()) {
                    builder.append("<font").append(attributes).append('>');
                }
                font = attributes;
                currentStyle = -1;
            }
            if (run.style() != currentStyle) {
                closeTags(builder, open);
                for (var bit = 0; bit < TAGS.length; bit++) {
                    if ((run.style() & 1 << bit) != 0) {
                        builder.append('<').append(TAGS[bit]).append('>');
                        open.add(TAGS[bit]);
                    }
                }
                currentStyle = run.style();
            }
            builder.append(run.text());
        }
        closeTags(builder, open);
        if (!font.isEmpty()) {
            builder.append("</font>");
        }
        return builder.toString();
    }

    /**
     * Returns the attributes of the {@code <font>} tag of a run.
     *
     * @param run the styled run
     * @return the {@code color}, {@code face} and {@code size} attributes, each with a leading space, empty
     * when the run sets none
     */
    private static String fontAttributes(StyledText.Run run) {
        var attributes = new StringBuilder();
        if (run.color() != StyledText.NO_COLOR) {
            attributes.append(" color=\"").append(SubtitleColors.rgb(run.color())).append('"');
        }
        if (!run.font().isEmpty()) {
            attributes.append(" face=\"").append(run.font().replace('"', '\'')).append('"');
        }
        if (!Double.isNaN(run.size())) {
            attributes.append(" size=\"").append(Math.max(1, Math.round(run.size()))).append('"');
        }
        return attributes.toString();
    }

    /**
     * Closes open tags, innermost first.
     *
     * @param builder the destination
     * @param open the open tags, cleared
     */
    private static void closeTags(StringBuilder builder, List<String> open) {
        for (var index = open.size() - 1; index >= 0; index--) {
            builder.append("</").append(open.get(index)).append('>');
        }
        open.clear();
    }

    /**
     * Writes a WebVTT cue payload: the speaker as a voice span, the default color classes, style tags and
     * karaoke timestamps.
     *
     * @param styled the styled text
     * @param context the cue times, which place the karaoke timestamps
     * @return the payload
     */
    private static String writeWebVtt(StyledText styled, Context context) {
        var builder = new StringBuilder();
        if (!styled.voice().isEmpty()) {
            builder.append("<v ").append(styled.voice().replace('>', ' ').replace('<', ' ').replace('&', ' ')).append('>');
        }
        var open = new ArrayList<String>();
        StyledText.Run current = null;
        var lastTime = context.startMicros();
        for (var run : styled.runs()) {
            if (run.karaoke() > 0 && context.startMicros() >= 0) {
                var time = context.startMicros() + run.karaoke();
                if (time > lastTime && (context.endMicros() < 0 || time < context.endMicros())) {
                    closeTags(builder, open);
                    current = null;
                    builder.append('<').append(CueTimes.format(time, '.')).append('>');
                    lastTime = time;
                }
            }
            if (current == null || !sameWebVttFormat(current, run)) {
                closeTags(builder, open);
                var color = SubtitleColors.webVttClassOf(run.color());
                var background = SubtitleColors.webVttClassOf(run.background());
                if (color != null || background != null) {
                    builder.append("<c");
                    if (color != null) {
                        builder.append('.').append(color);
                    }
                    if (background != null) {
                        builder.append(".bg_").append(background);
                    }
                    builder.append('>');
                    open.add("c");
                }
                for (var bit = 0; bit < 3; bit++) {
                    if ((run.style() & 1 << bit) != 0) {
                        builder.append('<').append(TAGS[bit]).append('>');
                        open.add(TAGS[bit]);
                    }
                }
                current = run;
            }
            escapeWebVtt(builder, run.text());
        }
        closeTags(builder, open);
        if (!styled.voice().isEmpty()) {
            builder.append("</v>");
        }
        return builder.toString();
    }

    /**
     * Returns whether two runs look the same in WebVTT.
     *
     * @param a the first run
     * @param b the second run
     * @return {@code true} when their bold, italic, underline and default color classes match
     */
    private static boolean sameWebVttFormat(StyledText.Run a, StyledText.Run b) {
        return (a.style() & 7) == (b.style() & 7)
                && Objects.equals(SubtitleColors.webVttClassOf(a.color()), SubtitleColors.webVttClassOf(b.color()))
                && Objects.equals(SubtitleColors.webVttClassOf(a.background()),
                SubtitleColors.webVttClassOf(b.background()));
    }

    /**
     * Writes ASS event text: a placement block, then the overrides that change the format between runs and
     * the karaoke syllables.
     *
     * @param styled the styled text
     * @param context the cue times, for the length of the last syllable, and the target script
     * @return the text
     */
    private static String writeAss(StyledText styled, Context context) {
        var builder = new StringBuilder();
        var placement = new StringBuilder();
        if (styled.alignment() != 0 && (styled.alignment() != 2 || styled.hasPosition())) {
            placement.append("\\an").append(styled.alignment());
        }
        if (styled.hasPosition()) {
            placement.append("\\pos(").append(coordinate(styled.x() * context.width())).append(',')
                    .append(coordinate(styled.y() * context.height())).append(')');
        }
        var runs = styled.runs();
        var karaoke = styled.hasKaraoke();
        var syllables = karaoke ? syllableStarts(runs) : null;
        if (karaoke && !runs.isEmpty() && syllables[0] > 0) {
            placement.append("\\k").append(centis(syllables[0]));
        }
        if (!placement.isEmpty()) {
            builder.append('{').append(placement).append('}');
        }
        var current = StyledText.Run.of("", 0);
        for (var index = 0; index < runs.size(); index++) {
            var run = runs.get(index);
            var tags = new StringBuilder();
            var reset = current.color() != StyledText.NO_COLOR && run.color() == StyledText.NO_COLOR
                    || !current.font().isEmpty() && run.font().isEmpty()
                    || !Double.isNaN(current.size()) && Double.isNaN(run.size());
            var from = reset ? StyledText.Run.of("", 0) : current;
            if (reset) {
                tags.append("\\r");
            }
            for (var bit = 0; bit < TAGS.length; bit++) {
                var mask = 1 << bit;
                if ((from.style() & mask) != (run.style() & mask)) {
                    tags.append('\\').append(TAGS[bit]).append((run.style() & mask) != 0 ? '1' : '0');
                }
            }
            if (run.color() != from.color() && run.color() != StyledText.NO_COLOR) {
                if ((run.color() & 0xFFFFFF) != (from.color() & 0xFFFFFF) || from.color() == StyledText.NO_COLOR) {
                    tags.append("\\c").append(SubtitleColors.ass(run.color()));
                }
                var alpha = run.color() >>> 24;
                var previousAlpha = from.color() == StyledText.NO_COLOR ? 0xFF : from.color() >>> 24;
                if (alpha != previousAlpha) {
                    tags.append("\\1a").append(SubtitleColors.assAlpha(run.color()));
                }
            }
            if (!run.font().equals(from.font()) && !run.font().isEmpty()) {
                tags.append("\\fn").append(run.font().replace('\\', ' ').replace('}', ' ').replace('{', ' '));
            }
            if (Double.compare(run.size(), from.size()) != 0 && !Double.isNaN(run.size())) {
                tags.append("\\fs").append(Math.max(1, Math.round(run.size() * context.height() / StyledText.REFERENCE_HEIGHT)));
            }
            if (karaoke && (index == 0 || syllables[index] != syllables[index - 1])) {
                var next = context.endMicros() >= 0 && context.startMicros() >= 0
                        ? context.endMicros() - context.startMicros() : syllables[index];
                for (var later = index + 1; later < runs.size(); later++) {
                    if (syllables[later] != syllables[index]) {
                        next = syllables[later];
                        break;
                    }
                }
                tags.append("\\k").append(centis(Math.max(0, next - syllables[index])));
            }
            if (!tags.isEmpty()) {
                builder.append('{').append(tags).append('}');
            }
            escapeAss(builder, run.text());
            current = run;
        }
        return builder.toString();
    }

    /**
     * Returns the karaoke time of each run, a run without one continuing the syllable before it.
     *
     * @param runs the styled runs
     * @return the syllable start of each run in microseconds from the cue start
     */
    private static long[] syllableStarts(List<StyledText.Run> runs) {
        var starts = new long[runs.size()];
        var last = 0L;
        for (var index = 0; index < starts.length; index++) {
            var karaoke = runs.get(index).karaoke();
            if (karaoke >= 0) {
                last = Math.max(last, karaoke);
            }
            starts[index] = last;
        }
        return starts;
    }

    /**
     * Converts microseconds to centiseconds, rounding to the nearest.
     *
     * @param micros the duration
     * @return the centiseconds
     */
    private static long centis(long micros) {
        return Math.floorDiv(micros + 5000, 10000);
    }

    /**
     * Formats an ASS coordinate with at most two decimals.
     *
     * @param value the coordinate
     * @return the text
     */
    private static String coordinate(double value) {
        var rounded = Math.round(value * 100) / 100.0;
        return rounded == Math.rint(rounded) ? Long.toString((long) rounded) : Double.toString(rounded);
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
     * Returns the style bit index of a tag name.
     *
     * @param name the lowercase tag name
     * @return the bit index, or {@code -1} when the tag is not a style tag
     */
    private static int styleBit(String name) {
        return switch (name) {
            case "b" -> 0;
            case "i" -> 1;
            case "u" -> 2;
            case "s" -> 3;
            default -> -1;
        };
    }

    /**
     * Returns the style bits of nesting depths.
     *
     * @param depth the nesting depth of each style tag
     * @return the style
     */
    private static int styleOf(int[] depth) {
        var style = 0;
        for (var index = 0; index < depth.length; index++) {
            if (depth[index] > 0) {
                style |= 1 << index;
            }
        }
        return style;
    }

    /**
     * Decodes a character reference.
     *
     * @param name the reference between the ampersand and the semicolon
     * @return the code point, or {@code -1} when the reference is not recognized
     */
    static int characterReference(String name) {
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
     * Appends text with line feeds as {@code \N}, no-break spaces as {@code \h} and braces escaped.
     *
     * @param builder the destination
     * @param text the characters to append
     */
    private static void escapeAss(StringBuilder builder, String text) {
        for (var index = 0; index < text.length(); index++) {
            var character = text.charAt(index);
            switch (character) {
                case '\n' -> builder.append("\\N");
                case '\u00A0' -> builder.append("\\h");
                case '{' -> builder.append("\\{");
                case '}' -> builder.append("\\}");
                default -> builder.append(character);
            }
        }
    }
}
