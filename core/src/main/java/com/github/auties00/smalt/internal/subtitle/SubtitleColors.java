package com.github.auties00.smalt.internal.subtitle;

import java.util.Locale;
import java.util.Map;

/**
 * Parses and writes the color notations of subtitle formats; colors are {@code 0xAARRGGBB} values and
 * {@link StyledText#NO_COLOR} stands for none.
 */
public final class SubtitleColors {
    /**
     * The named colors of HTML and TTML, by lowercase name.
     */
    private static final Map<String, Long> NAMED = Map.ofEntries(
            Map.entry("transparent", 0x00000000L),
            Map.entry("black", 0xFF000000L),
            Map.entry("silver", 0xFFC0C0C0L),
            Map.entry("gray", 0xFF808080L),
            Map.entry("grey", 0xFF808080L),
            Map.entry("white", 0xFFFFFFFFL),
            Map.entry("maroon", 0xFF800000L),
            Map.entry("red", 0xFFFF0000L),
            Map.entry("purple", 0xFF800080L),
            Map.entry("fuchsia", 0xFFFF00FFL),
            Map.entry("magenta", 0xFFFF00FFL),
            Map.entry("green", 0xFF008000L),
            Map.entry("lime", 0xFF00FF00L),
            Map.entry("olive", 0xFF808000L),
            Map.entry("yellow", 0xFFFFFF00L),
            Map.entry("navy", 0xFF000080L),
            Map.entry("blue", 0xFF0000FFL),
            Map.entry("teal", 0xFF008080L),
            Map.entry("aqua", 0xFF00FFFFL),
            Map.entry("cyan", 0xFF00FFFFL),
            Map.entry("orange", 0xFFFFA500L));

    /**
     * The WebVTT default color classes, in the order their names are tried when writing.
     */
    private static final String[] WEBVTT_CLASSES = {"white", "lime", "cyan", "red", "yellow", "magenta", "blue", "black"};

    private SubtitleColors() {
        throw new AssertionError();
    }

    /**
     * Parses a CSS-style color: {@code #rgb}, {@code #rrggbb}, {@code #rrggbbaa}, {@code rgb(r,g,b)},
     * {@code rgba(r,g,b,a)} (alpha from 0 to 255) or a named color.
     *
     * @param value the color text
     * @return the color, or {@link StyledText#NO_COLOR} when the text is not a color
     */
    public static long parseCss(String value) {
        var text = value.strip().toLowerCase(Locale.ROOT);
        if (text.startsWith("#")) {
            var hex = text.substring(1);
            if (!isHex(hex)) {
                return StyledText.NO_COLOR;
            }
            return switch (hex.length()) {
                case 3 -> 0xFF000000L | expand(hex.charAt(0)) << 16 | expand(hex.charAt(1)) << 8 | expand(hex.charAt(2));
                case 6 -> 0xFF000000L | Long.parseLong(hex, 16);
                case 8 -> {
                    var rgba = Long.parseLong(hex, 16);
                    yield (rgba & 0xFF) << 24 | rgba >>> 8;
                }
                default -> StyledText.NO_COLOR;
            };
        }
        if (text.startsWith("rgb(") || text.startsWith("rgba(")) {
            var open = text.indexOf('(');
            var close = text.indexOf(')', open);
            if (close < 0) {
                return StyledText.NO_COLOR;
            }
            var parts = text.substring(open + 1, close).split(",");
            var alpha = text.startsWith("rgba(");
            if (parts.length != (alpha ? 4 : 3)) {
                return StyledText.NO_COLOR;
            }
            var color = alpha ? 0L : 0xFF000000L;
            for (var index = 0; index < parts.length; index++) {
                var component = component(parts[index]);
                if (component < 0) {
                    return StyledText.NO_COLOR;
                }
                color |= index == 3 ? (long) component << 24 : (long) component << (16 - 8 * index);
            }
            return color;
        }
        if (isHex(text) && text.length() == 6) {
            return 0xFF000000L | Long.parseLong(text, 16);
        }
        return NAMED.getOrDefault(text, StyledText.NO_COLOR);
    }

    /**
     * Writes a color as {@code #rrggbb}, or {@code #rrggbbaa} when it is not opaque.
     *
     * @param color the color as {@code 0xAARRGGBB}
     * @return the text
     */
    public static String css(long color) {
        var rgb = String.format(Locale.ROOT, "#%06x", color & 0xFFFFFF);
        var alpha = color >>> 24 & 0xFF;
        return alpha == 0xFF ? rgb : rgb + String.format(Locale.ROOT, "%02x", alpha);
    }

    /**
     * Writes a color as {@code #rrggbb}, without its alpha.
     *
     * @param color the color as {@code 0xAARRGGBB}
     * @return the text
     */
    public static String rgb(long color) {
        return String.format(Locale.ROOT, "#%06x", color & 0xFFFFFF);
    }

    /**
     * Returns the color of a WebVTT default color class.
     *
     * @param name the class name, without the {@code bg_} prefix
     * @return the opaque color, or {@link StyledText#NO_COLOR} for any other class
     */
    public static long webVttClass(String name) {
        for (var candidate : WEBVTT_CLASSES) {
            if (candidate.equals(name)) {
                return NAMED.get(name);
            }
        }
        return StyledText.NO_COLOR;
    }

    /**
     * Returns the WebVTT default color class of a color.
     *
     * @param color the color as {@code 0xAARRGGBB}
     * @return the class name, or {@code null} when no default class has the color
     */
    public static String webVttClassOf(long color) {
        if (color == StyledText.NO_COLOR || (color >>> 24 & 0xFF) != 0xFF) {
            return null;
        }
        for (var candidate : WEBVTT_CLASSES) {
            if (NAMED.get(candidate) == color) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * Parses an ASS color: {@code &HBBGGRR&}, {@code &HAABBGGRR&} (alpha 0 opaque), a hexadecimal
     * value without the {@code &H} prefix after an override tag, or a decimal value as SSA styles write it.
     *
     * @param value the color text
     * @param decimal whether a value without {@code &H} is decimal (style fields) rather than hexadecimal
     * @return the color as {@code 0xAARRGGBB}, or {@link StyledText#NO_COLOR} when the text is not a color
     */
    public static long parseAss(String value, boolean decimal) {
        var text = value.strip();
        var hex = false;
        if (text.startsWith("&H") || text.startsWith("&h")) {
            text = text.substring(2);
            hex = true;
        } else if (text.startsWith("H") || text.startsWith("h")) {
            text = text.substring(1);
            hex = true;
        }
        while (text.endsWith("&")) {
            text = text.substring(0, text.length() - 1);
        }
        if (text.isEmpty()) {
            return StyledText.NO_COLOR;
        }
        long abgr;
        try {
            if (hex || !decimal) {
                if (!isHex(text) || text.length() > 8) {
                    return StyledText.NO_COLOR;
                }
                abgr = Long.parseLong(text, 16);
            } else {
                abgr = Long.parseLong(text) & 0xFFFFFFFFL;
            }
        } catch (NumberFormatException exception) {
            return StyledText.NO_COLOR;
        }
        var alpha = 0xFF - (abgr >>> 24 & 0xFF);
        return alpha << 24 | (abgr & 0xFF) << 16 | (abgr >>> 8 & 0xFF) << 8 | abgr >>> 16 & 0xFF;
    }

    /**
     * Writes the color part of an ASS color, {@code &HBBGGRR&}.
     *
     * @param color the color as {@code 0xAARRGGBB}
     * @return the text
     */
    public static String ass(long color) {
        return String.format(Locale.ROOT, "&H%02X%02X%02X&", color & 0xFF, color >>> 8 & 0xFF, color >>> 16 & 0xFF);
    }

    /**
     * Writes an ASS style color with its alpha, {@code &HAABBGGRR}.
     *
     * @param color the color as {@code 0xAARRGGBB}
     * @return the text
     */
    public static String assWithAlpha(long color) {
        return String.format(Locale.ROOT, "&H%02X%02X%02X%02X", 0xFF - (color >>> 24 & 0xFF), color & 0xFF,
                color >>> 8 & 0xFF, color >>> 16 & 0xFF);
    }

    /**
     * Writes the alpha of an ASS color, {@code &HAA&} with 0 opaque.
     *
     * @param color the color as {@code 0xAARRGGBB}
     * @return the text
     */
    public static String assAlpha(long color) {
        return String.format(Locale.ROOT, "&H%02X&", 0xFF - (color >>> 24 & 0xFF));
    }

    /**
     * Returns whether text is a run of hexadecimal digits.
     *
     * @param text the characters to examine
     * @return {@code true} when it is non-empty and every character is a hexadecimal digit
     */
    private static boolean isHex(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (var index = 0; index < text.length(); index++) {
            if (Character.digit(text.charAt(index), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * Expands one hexadecimal digit of a short color to its byte.
     *
     * @param digit the hexadecimal digit
     * @return the byte, {@code 0x11} times the digit
     */
    private static long expand(char digit) {
        return Character.digit(digit, 16) * 0x11L;
    }

    /**
     * Parses one component of an {@code rgb()} color.
     *
     * @param text the component text
     * @return the value from 0 to 255, or {@code -1} when it is not a number in range
     */
    private static int component(String text) {
        try {
            var value = Integer.parseInt(text.strip());
            return value >= 0 && value <= 255 ? value : -1;
        } catch (NumberFormatException exception) {
            return -1;
        }
    }
}
