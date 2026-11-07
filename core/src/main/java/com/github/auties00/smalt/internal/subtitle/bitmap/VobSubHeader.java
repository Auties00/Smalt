package com.github.auties00.smalt.internal.subtitle.bitmap;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Holds what a DVD subpicture decoder needs from a VobSub index header: the frame size, the origin of the
 * pictures, the 16-color palette and the optional custom colors.
 *
 * @param width the frame width in pixels, or {@code 0} when the header gives none
 * @param height the frame height in pixels, or {@code 0} when the header gives none
 * @param originX the column added to every picture position
 * @param originY the row added to every picture position
 * @param palette the 16 palette colors as {@code 0xRRGGBB}, or {@code null} when the header gives none
 * @param customColors the four colors that replace a picture's own as {@code 0xRRGGBB}, or {@code null} when the
 * header does not turn them on
 * @param transparentCustom the custom colors shown transparent, bit 3 for the first color (the background) to bit 0
 * for the fourth
 */
public record VobSubHeader(int width, int height, int originX, int originY, int[] palette, int[] customColors,
                           int transparentCustom) {
    /**
     * The header of a track without one.
     */
    public static final VobSubHeader NONE = new VobSubHeader(0, 0, 0, 0, null, null, 0);

    /**
     * Parses a header.
     *
     * <p>Unknown lines and malformed values are ignored.
     *
     * @param data the header in UTF-8, or {@code null}
     * @return the header
     */
    public static VobSubHeader parse(byte[] data) {
        if (data == null) {
            return NONE;
        }
        var width = 0;
        var height = 0;
        var originX = 0;
        var originY = 0;
        int[] palette = null;
        int[] custom = null;
        var customOn = false;
        var transparent = 0;
        for (var line : new String(data, StandardCharsets.UTF_8).split("\r\n|\r|\n")) {
            var colon = line.indexOf(':');
            if (colon < 0 || line.startsWith("#")) {
                continue;
            }
            var key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            var value = line.substring(colon + 1).trim();
            switch (key) {
                case "size" -> {
                    var x = value.toLowerCase(Locale.ROOT).indexOf('x');
                    if (x > 0) {
                        width = number(value.substring(0, x));
                        height = number(value.substring(x + 1));
                    }
                }
                case "org" -> {
                    var comma = value.indexOf(',');
                    if (comma > 0) {
                        originX = Math.max(0, number(value.substring(0, comma)));
                        originY = Math.max(0, number(value.substring(comma + 1)));
                    }
                }
                case "palette" -> palette = colors(value, 16);
                case "custom colors" -> {
                    var parts = value.split(",");
                    customOn = parts[0].trim().equalsIgnoreCase("on");
                    for (var part : parts) {
                        var item = part.trim().toLowerCase(Locale.ROOT);
                        if (item.startsWith("tridx:")) {
                            transparent = binary(item.substring(6).trim());
                        }
                    }
                    var colors = value.toLowerCase(Locale.ROOT).indexOf("colors:");
                    custom = colors < 0 ? null : colors(value.substring(colors + 7), 4);
                }
                default -> {
                }
            }
        }
        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
            width = 0;
            height = 0;
        }
        return new VobSubHeader(width, height, originX, originY, palette, customOn ? custom : null, transparent);
    }

    private static int[] colors(String value, int count) {
        var parts = value.split(",");
        if (parts.length < count) {
            return null;
        }
        var colors = new int[count];
        for (var index = 0; index < count; index++) {
            var color = hex(parts[index].trim());
            if (color < 0) {
                return null;
            }
            colors[index] = color;
        }
        return colors;
    }

    private static int hex(String value) {
        if (value.isEmpty() || value.length() > 6) {
            return -1;
        }
        try {
            return Integer.parseInt(value, 16);
        } catch (NumberFormatException exception) {
            return -1;
        }
    }

    private static int binary(String value) {
        var bits = 0;
        for (var index = 0; index < Math.min(4, value.length()); index++) {
            bits = bits << 1 | (value.charAt(index) == '1' ? 1 : 0);
        }
        return bits;
    }

    private static int number(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
