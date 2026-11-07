package com.github.auties00.smalt.internal.subtitle;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses and rewrites the header of a SubStation Alpha (v4.00) or Advanced SubStation Alpha
 * (v4.00+) script: its sections, the {@code [Script Info]} fields, the styles and the event format.
 *
 * <p>The header is every line of a script but its {@code Dialogue} lines, the form a Matroska
 * {@code CodecPrivate} stores. A script is SSA when its {@code ScriptType} is {@code v4.00} or it has a
 * {@code [V4 Styles]} section and no {@code [V4+ Styles]} one; SSA styles have no underline, strikeout,
 * scaling, spacing or angle, write the alignment in the legacy numbering (1 to 3 at the bottom, 5 to 7 at
 * the top, 9 to 11 in the middle), and SSA events have a {@code Marked} field where ASS events have a
 * {@code Layer}. Unknown sections and lines are kept as they are.
 */
public final class AssScript {
    /**
     * The style fields of an ASS script, in their usual order.
     */
    public static final List<String> ASS_STYLE_FORMAT = List.of("Name", "Fontname", "Fontsize", "PrimaryColour",
            "SecondaryColour", "OutlineColour", "BackColour", "Bold", "Italic", "Underline", "StrikeOut", "ScaleX",
            "ScaleY", "Spacing", "Angle", "BorderStyle", "Outline", "Shadow", "Alignment", "MarginL", "MarginR",
            "MarginV", "Encoding");

    /**
     * The style fields of an SSA script, in their usual order.
     */
    public static final List<String> SSA_STYLE_FORMAT = List.of("Name", "Fontname", "Fontsize", "PrimaryColour",
            "SecondaryColour", "TertiaryColour", "BackColour", "Bold", "Italic", "BorderStyle", "Outline", "Shadow",
            "Alignment", "MarginL", "MarginR", "MarginV", "AlphaLevel", "Encoding");

    /**
     * The event fields of an ASS script, in their usual order.
     */
    public static final List<String> ASS_EVENT_FORMAT = List.of("Layer", "Start", "End", "Style", "Name", "MarginL",
            "MarginR", "MarginV", "Effect", "Text");

    /**
     * The event fields of an SSA script, in their usual order.
     */
    public static final List<String> SSA_EVENT_FORMAT = List.of("Marked", "Start", "End", "Style", "Name", "MarginL",
            "MarginR", "MarginV", "Effect", "Text");

    /**
     * The script width of a script that declares neither width nor height.
     */
    private static final int DEFAULT_WIDTH = 384;

    /**
     * The script height of a script that declares neither width nor height.
     */
    private static final int DEFAULT_HEIGHT = 288;

    /**
     * The sections in order.
     */
    private final List<Section> sections;

    /**
     * The fields of the {@code [Script Info]} section, by lowercase name.
     */
    private final Map<String, String> info;

    /**
     * The styles by lowercase name, in declaration order.
     */
    private final Map<String, Style> styles;

    /**
     * The event fields, lowercase, in the order of the {@code [Events]} format line.
     */
    private final List<String> eventFormat;

    /**
     * Whether the script is SSA rather than ASS.
     */
    private final boolean ssa;

    /**
     * Holds one section.
     *
     * @param name the name between the brackets, empty for the lines before the first section
     * @param lines the lines after the section line, trailing blank lines included
     */
    public record Section(String name, List<String> lines) {
        /**
         * Returns whether the section has a name, ignoring case.
         *
         * @param other the name to compare
         * @return {@code true} on a match
         */
        public boolean is(String other) {
            return name.equalsIgnoreCase(other);
        }
    }

    /**
     * Holds the fields of one style that the conversions use.
     *
     * @param name the style name
     * @param font the font family
     * @param size the font size in script pixels, or {@code NaN} when absent
     * @param primary the primary color, or {@link StyledText#NO_COLOR}
     * @param style the combination of the {@link StyledText} style bits
     * @param alignment the numpad alignment from 1 to 9
     * @param fields every field of the style line by lowercase format name
     */
    public record Style(String name, String font, double size, long primary, int style, int alignment,
                        Map<String, String> fields) {
    }

    /**
     * Constructs a parsed script.
     *
     * @param sections the script sections, in order
     * @param info the script info fields
     * @param styles the styles by lowercase name
     * @param eventFormat the event format
     * @param ssa whether the script is SSA
     */
    private AssScript(List<Section> sections, Map<String, String> info, Map<String, Style> styles,
                      List<String> eventFormat, boolean ssa) {
        this.sections = sections;
        this.info = info;
        this.styles = styles;
        this.eventFormat = eventFormat;
        this.ssa = ssa;
    }

    /**
     * Parses a script header, or a whole script whose {@code Dialogue} lines are ignored.
     *
     * @param header the header, with line feeds only
     * @return the script; an empty header gives an ASS script without styles
     */
    public static AssScript parse(String header) {
        var sections = new ArrayList<Section>();
        var lines = new ArrayList<String>();
        var name = "";
        for (var line : header.split("\n", -1)) {
            var trimmed = line.strip();
            if (trimmed.startsWith("[") && trimmed.endsWith("]") && trimmed.length() > 2) {
                sections.add(new Section(name, List.copyOf(lines)));
                lines.clear();
                name = trimmed.substring(1, trimmed.length() - 1).strip();
            } else {
                lines.add(line);
            }
        }
        sections.add(new Section(name, List.copyOf(lines)));
        if (sections.getFirst().name().isEmpty() && sections.getFirst().lines().stream().allMatch(String::isBlank)) {
            sections.removeFirst();
        }
        var info = new LinkedHashMap<String, String>();
        var styles = new LinkedHashMap<String, Style>();
        List<String> eventFormat = null;
        var hasV4 = false;
        var hasV4Plus = false;
        Section styleSection = null;
        for (var section : sections) {
            if (section.is("Script Info")) {
                for (var line : section.lines()) {
                    var colon = line.indexOf(':');
                    if (colon > 0 && !line.startsWith(";")) {
                        info.putIfAbsent(line.substring(0, colon).strip().toLowerCase(Locale.ROOT),
                                line.substring(colon + 1).strip());
                    }
                }
            } else if (section.is("V4 Styles")) {
                hasV4 = true;
                styleSection = styleSection == null ? section : styleSection;
            } else if (section.is("V4+ Styles") || section.is("V4++ Styles")) {
                hasV4Plus = true;
                styleSection = section;
            } else if (section.is("Events")) {
                for (var line : section.lines()) {
                    var value = field(line, "Format");
                    if (value != null) {
                        eventFormat = formatFields(value);
                        break;
                    }
                }
            }
        }
        var type = info.getOrDefault("scripttype", "").toLowerCase(Locale.ROOT);
        var ssa = !hasV4Plus && (hasV4 || type.equals("v4.00"));
        if (styleSection != null) {
            readStyles(styleSection, ssa, styles);
        }
        if (eventFormat == null) {
            eventFormat = lower(ssa ? SSA_EVENT_FORMAT : ASS_EVENT_FORMAT);
        }
        return new AssScript(List.copyOf(sections), info, styles, List.copyOf(eventFormat), ssa);
    }

    /**
     * Reads the styles of a style section.
     *
     * @param section the script section
     * @param ssa whether the script is SSA
     * @param styles receives the styles by lowercase name
     */
    private static void readStyles(Section section, boolean ssa, Map<String, Style> styles) {
        List<String> format = null;
        for (var line : section.lines()) {
            var value = field(line, "Format");
            if (value != null) {
                format = formatFields(value);
                continue;
            }
            value = field(line, "Style");
            if (value == null) {
                continue;
            }
            var names = format != null ? format : lower(ssa ? SSA_STYLE_FORMAT : ASS_STYLE_FORMAT);
            var parts = value.split(",", -1);
            var fields = new LinkedHashMap<String, String>();
            for (var index = 0; index < names.size() && index < parts.length; index++) {
                var part = index == names.size() - 1 ? String.join(",", List.of(parts).subList(index, parts.length))
                        : parts[index];
                fields.put(names.get(index), part.strip());
            }
            var name = fields.getOrDefault("name", "").strip();
            if (name.startsWith("*")) {
                name = name.substring(1);
            }
            var style = 0;
            if (flag(fields.get("bold"))) {
                style |= StyledText.BOLD;
            }
            if (flag(fields.get("italic"))) {
                style |= StyledText.ITALIC;
            }
            if (flag(fields.get("underline"))) {
                style |= StyledText.UNDERLINE;
            }
            if (flag(fields.get("strikeout"))) {
                style |= StyledText.STRIKEOUT;
            }
            var alignment = number(fields.get("alignment"), 2);
            alignment = ssa ? fromLegacyAlignment(alignment) : alignment >= 1 && alignment <= 9 ? alignment : 2;
            var size = number(fields.get("fontsize"), Double.NaN);
            var primary = fields.containsKey("primarycolour")
                    ? SubtitleColors.parseAss(fields.get("primarycolour"), true) : StyledText.NO_COLOR;
            styles.putIfAbsent(name.toLowerCase(Locale.ROOT), new Style(name, fields.getOrDefault("fontname", ""), size,
                    primary, style, alignment, Map.copyOf(fields)));
        }
    }

    /**
     * Returns the value of a {@code Name: value} line.
     *
     * @param line the text of one line
     * @param name the field name, matched ignoring case
     * @return the value without leading blanks, or {@code null} when the line is not that field
     */
    public static String field(String line, String name) {
        var trimmed = line.stripLeading();
        if (trimmed.length() <= name.length() || !trimmed.regionMatches(true, 0, name, 0, name.length())) {
            return null;
        }
        var rest = trimmed.substring(name.length()).stripLeading();
        return rest.startsWith(":") ? rest.substring(1).stripLeading() : null;
    }

    /**
     * Splits a format line into lowercase field names.
     *
     * @param value the value of the format line
     * @return the field names
     */
    private static List<String> formatFields(String value) {
        var result = new ArrayList<String>();
        for (var part : value.split(",")) {
            result.add(part.strip().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    /**
     * Returns the lowercase copy of field names.
     *
     * @param names the field names to lowercase
     * @return the lowercase names
     */
    private static List<String> lower(List<String> names) {
        var result = new ArrayList<String>(names.size());
        for (var name : names) {
            result.add(name.toLowerCase(Locale.ROOT));
        }
        return result;
    }

    /**
     * Returns whether a boolean style field is set: {@code -1}, {@code 1} or any other non-zero value.
     *
     * @param value the field, or {@code null}
     * @return {@code true} when set
     */
    private static boolean flag(String value) {
        if (value == null) {
            return false;
        }
        var number = number(value, 0);
        return number != 0;
    }

    /**
     * Parses an integer field.
     *
     * @param value the field, or {@code null}
     * @param fallback the value of a missing or malformed field
     * @return the value
     */
    private static int number(String value, int fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException exception) {
            var real = number(value, Double.NaN);
            return Double.isNaN(real) ? fallback : (int) real;
        }
    }

    /**
     * Parses a decimal field.
     *
     * @param value the field, or {@code null}
     * @param fallback the value of a missing or malformed field
     * @return the value
     */
    private static double number(String value, double fallback) {
        if (value == null) {
            return fallback;
        }
        try {
            var parsed = Double.parseDouble(value.strip());
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    /**
     * Converts an SSA legacy alignment to the numpad alignment.
     *
     * @param legacy the legacy alignment: 1 to 3 at the bottom, plus 4 at the top, plus 8 in the middle
     * @return the numpad alignment, {@code 2} for an invalid value
     */
    public static int fromLegacyAlignment(int legacy) {
        var column = legacy & 3;
        if (column == 0 || legacy < 1 || legacy > 11) {
            return 2;
        }
        if ((legacy & 4) != 0) {
            return column + 6;
        }
        if ((legacy & 8) != 0) {
            return column + 3;
        }
        return column;
    }

    /**
     * Converts a numpad alignment to the SSA legacy alignment.
     *
     * @param numpad the numpad alignment from 1 to 9
     * @return the legacy alignment, {@code 2} for an invalid value
     */
    public static int toLegacyAlignment(int numpad) {
        if (numpad < 1 || numpad > 9) {
            return 2;
        }
        var column = (numpad - 1) % 3 + 1;
        return numpad >= 7 ? column + 4 : numpad >= 4 ? column + 8 : column;
    }

    /**
     * {@return whether the script is SSA (v4.00) rather than ASS (v4.00+)}
     */
    public boolean isSsa() {
        return ssa;
    }

    /**
     * {@return the sections, in order}
     */
    public List<Section> sections() {
        return sections;
    }

    /**
     * Returns a {@code [Script Info]} field.
     *
     * @param name the field name, matched ignoring case
     * @return the value, or {@code null} when absent
     */
    public String info(String name) {
        return info.get(name.toLowerCase(Locale.ROOT));
    }

    /**
     * {@return the event fields in the order of the format line, lowercase}
     */
    public List<String> eventFormat() {
        return eventFormat;
    }

    /**
     * {@return the styles in declaration order}
     */
    public List<Style> styles() {
        return List.copyOf(styles.values());
    }

    /**
     * Returns a style by name, as players select it: the named style, else {@code Default}, else the
     * first style.
     *
     * @param name the style name of an event
     * @return the style, or {@code null} when the script has none
     */
    public Style style(String name) {
        var found = styles.get(name.strip().toLowerCase(Locale.ROOT));
        if (found == null && name.strip().startsWith("*")) {
            found = styles.get(name.strip().substring(1).toLowerCase(Locale.ROOT));
        }
        if (found == null) {
            found = styles.get("default");
        }
        if (found == null && !styles.isEmpty()) {
            found = styles.values().iterator().next();
        }
        return found;
    }

    /**
     * Returns the script width, the coordinate space of positions and font sizes.
     *
     * @return {@code PlayResX}, or the width derived from {@code PlayResY} or the default 384
     */
    public int width() {
        var x = positive(info("PlayResX"));
        var y = positive(info("PlayResY"));
        if (x > 0) {
            return x;
        }
        if (y > 0) {
            return y == 1024 ? 1280 : (int) Math.max(1, (long) y * 4 / 3);
        }
        return DEFAULT_WIDTH;
    }

    /**
     * Returns the script height, the coordinate space of positions and font sizes.
     *
     * @return {@code PlayResY}, or the height derived from {@code PlayResX} or the default 288
     */
    public int height() {
        var x = positive(info("PlayResX"));
        var y = positive(info("PlayResY"));
        if (y > 0) {
            return y;
        }
        if (x > 0) {
            return x == 1280 ? 1024 : (int) Math.max(1, (long) x * 3 / 4);
        }
        return DEFAULT_HEIGHT;
    }

    /**
     * Returns whether {@code \n} breaks lines in the script, as it does under wrap style 2.
     *
     * @return {@code true} when {@code WrapStyle} is 2
     */
    public boolean softBreaksAreLineBreaks() {
        return "2".equals(info("WrapStyle"));
    }

    /**
     * Parses a positive integer field.
     *
     * @param value the field, or {@code null}
     * @return the value, or {@code 0} when absent, malformed or not positive
     */
    private static int positive(String value) {
        var number = number(value, 0);
        return Math.max(0, number);
    }

    /**
     * Writes the script header with the styles and event format of another type, SSA or ASS.
     *
     * <p>Converting to ASS turns SSA styles into ASS ones (the tertiary color becomes the outline color,
     * the legacy alignment the numpad one, decimal colors hexadecimal, the alpha level is dropped and
     * the added fields get their neutral values) and the {@code ScriptType} into {@code v4.00+};
     * converting to SSA does the reverse, dropping the underline, strikeout, scaling, spacing and angle.
     * The other sections and lines are kept.
     *
     * @param header the script header text
     * @param toSsa {@code true} to write SSA, {@code false} to write ASS
     * @return the converted header, {@code header} itself when it already has that type
     */
    public static String convert(String header, boolean toSsa) {
        var script = parse(header);
        if (script.isSsa() == toSsa) {
            return header;
        }
        var out = new StringBuilder();
        var wroteStyles = false;
        var sawInfo = false;
        for (var section : script.sections()) {
            var name = section.name();
            var styleSection = section.is("V4 Styles") || section.is("V4+ Styles") || section.is("V4++ Styles");
            if (styleSection) {
                if (wroteStyles) {
                    continue;
                }
                wroteStyles = true;
                out.append(toSsa ? "[V4 Styles]" : "[V4+ Styles]").append('\n');
                var format = toSsa ? SSA_STYLE_FORMAT : ASS_STYLE_FORMAT;
                out.append("Format: ").append(String.join(", ", format)).append('\n');
                for (var style : script.styles()) {
                    out.append("Style: ").append(convertStyle(style, toSsa)).append('\n');
                }
                for (var line : section.lines()) {
                    if (line.isBlank()) {
                        out.append(line).append('\n');
                    } else if (field(line, "Format") == null && field(line, "Style") == null) {
                        out.append(line).append('\n');
                    }
                }
                continue;
            }
            if (!name.isEmpty()) {
                out.append('[').append(name).append(']').append('\n');
            }
            for (var line : section.lines()) {
                if (section.is("Script Info") && field(line, "ScriptType") != null) {
                    sawInfo = true;
                    out.append("ScriptType: ").append(toSsa ? "v4.00" : "v4.00+").append('\n');
                } else if (section.is("Events") && field(line, "Format") != null) {
                    out.append("Format: ").append(String.join(", ", toSsa ? SSA_EVENT_FORMAT : ASS_EVENT_FORMAT))
                            .append('\n');
                } else {
                    out.append(line).append('\n');
                }
            }
            if (section.is("Script Info") && !sawInfo) {
                sawInfo = true;
                insertBeforeTrailingBlanks(out, "ScriptType: " + (toSsa ? "v4.00" : "v4.00+"));
            }
        }
        var text = out.toString();
        if (!header.endsWith("\n") && text.endsWith("\n")) {
            text = text.substring(0, text.length() - 1);
        }
        return text;
    }

    /**
     * Inserts a line before the blank lines that end the text.
     *
     * @param out the text so far, ending with a line feed
     * @param line the line to insert
     */
    private static void insertBeforeTrailingBlanks(StringBuilder out, String line) {
        var end = out.length();
        while (end >= 2 && out.charAt(end - 1) == '\n' && out.charAt(end - 2) == '\n') {
            end--;
        }
        out.insert(end, line + "\n");
    }

    /**
     * Writes one style in the other type's format.
     *
     * @param style the style to rewrite
     * @param toSsa whether to write the SSA format
     * @return the style fields, comma separated
     */
    private static String convertStyle(Style style, boolean toSsa) {
        var fields = style.fields();
        var values = new ArrayList<String>();
        for (var name : toSsa ? SSA_STYLE_FORMAT : ASS_STYLE_FORMAT) {
            var key = name.toLowerCase(Locale.ROOT);
            var value = switch (key) {
                case "name" -> style.name();
                case "primarycolour", "secondarycolour", "backcolour" -> color(fields.get(key), toSsa);
                case "outlinecolour" -> color(fields.getOrDefault("tertiarycolour", fields.get("outlinecolour")), false);
                case "tertiarycolour" -> color(fields.getOrDefault("outlinecolour", fields.get("tertiarycolour")), true);
                case "alignment" -> Integer.toString(toSsa ? toLegacyAlignment(style.alignment()) : style.alignment());
                case "underline" -> (style.style() & StyledText.UNDERLINE) != 0 ? "-1" : "0";
                case "strikeout" -> (style.style() & StyledText.STRIKEOUT) != 0 ? "-1" : "0";
                case "scalex", "scaley" -> fields.getOrDefault(key, "100");
                case "alphalevel" -> fields.getOrDefault(key, "0");
                case "spacing", "angle" -> fields.getOrDefault(key, "0");
                default -> fields.getOrDefault(key, defaultField(key));
            };
            values.add(value);
        }
        return String.join(",", values);
    }

    /**
     * Returns the neutral value of a style field a script lacks.
     *
     * @param key the lowercase field name
     * @return the value
     */
    private static String defaultField(String key) {
        return switch (key) {
            case "fontname" -> "Arial";
            case "fontsize" -> "20";
            case "bold", "italic", "marginl", "marginr", "marginv", "outline", "shadow" -> "0";
            case "borderstyle", "encoding" -> "1";
            default -> "";
        };
    }

    /**
     * Rewrites a style color for the target type: SSA writes decimal {@code BBGGRR} values, ASS
     * {@code &HAABBGGRR}.
     *
     * @param value the color field, or {@code null}
     * @param toSsa whether the target is SSA
     * @return the color field
     */
    private static String color(String value, boolean toSsa) {
        if (value == null) {
            return toSsa ? "16777215" : "&H00FFFFFF";
        }
        var color = SubtitleColors.parseAss(value, true);
        if (color == StyledText.NO_COLOR) {
            return value;
        }
        if (toSsa) {
            return Long.toString((color & 0xFF) << 16 | (color >>> 8 & 0xFF) << 8 | color >>> 16 & 0xFF);
        }
        return SubtitleColors.assWithAlpha(color);
    }
}
