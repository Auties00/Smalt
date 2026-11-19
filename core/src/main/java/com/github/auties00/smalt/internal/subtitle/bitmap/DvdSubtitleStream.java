package com.github.auties00.smalt.internal.subtitle.bitmap;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.subtitle.BitmapRegion;

import java.util.Arrays;
import java.util.List;

/**
 * Decodes DVD subpicture units: their run-length coded fields and display control sequences.
 *
 * <p>Each start command (forced or not) shows the picture as the control sequences set it up to that point, a
 * later change of colors, contrast or area while it is shown shows it again, and a stop command fixes the deadline
 * of what is shown. Delays count in units of 1024 ticks of the 90 kHz clock from the packet's time. Without a
 * palette in the header, palette entry {@code n} is the gray {@code 17 * n}; custom colors, when the header turns
 * them on, replace the four colors of every picture.
 *
 * <p>A color and contrast change command ({@code CHG_COLCON}, {@code 07}) gives bands of screen lines, each split at
 * starting screen columns into areas with their own four colors and contrasts: an area runs from its column to the
 * next area's column or to the right edge, and the colors and contrasts the commands {@code 03} and {@code 04} set
 * apply outside every area. A later {@code 07} command replaces the areas of an earlier one.
 */
public final class DvdSubtitleStream implements BitmapStream {
    /**
     * The most control sequences read from one unit.
     */
    private static final int MAX_SEQUENCES = 256;

    /**
     * The most distinct color and contrast sets of one picture, the set of commands {@code 03} and {@code 04}
     * included; areas beyond them keep that set.
     */
    private static final int MAX_COLOR_SETS = 64;

    /**
     * The line control word that ends the parameters of a {@code CHG_COLCON} command.
     */
    private static final int COLCON_END = 0x0FFFFFFF;

    private final MediaLimits limits;
    private final VobSubHeader header;
    private final int[] palette;

    /**
     * Constructs a decoder.
     *
     * @param limits the bounds of the pictures
     * @param header the index header of the track
     */
    public DvdSubtitleStream(MediaLimits limits, VobSubHeader header) {
        this.limits = limits;
        this.header = header;
        this.palette = new int[16];
        for (var index = 0; index < 16; index++) {
            palette[index] = header.palette() != null ? header.palette()[index] : index * 0x111111;
        }
    }

    @Override
    public void reset() {
    }

    @Override
    public void decode(byte[] data, long micros, Sink sink) {
        if (data.length < 4) {
            return;
        }
        var size = Math.min(u16(data, 0), data.length);
        var sequence = u16(data, 2);
        var colors = new int[]{0, 1, 2, 3};
        var contrast = new int[]{0, 15, 15, 15};
        var area = new int[]{0, -1, 0, -1};
        var offsets = new int[]{-1, -1};
        var colcon = new int[]{-1, -1};
        var shown = false;
        var forced = false;
        long showMicros = 0;
        Screen screen = null;
        for (var count = 0; count < MAX_SEQUENCES && sequence + 4 <= size; count++) {
            var delay = u16(data, sequence);
            var next = u16(data, sequence + 2);
            var position = sequence + 4;
            var start = false;
            var stop = false;
            var changed = false;
            loop:
            while (position < size) {
                var command = data[position++] & 0xFF;
                switch (command) {
                    case 0x00 -> {
                        start = true;
                        forced = true;
                    }
                    case 0x01 -> start = true;
                    case 0x02 -> stop = true;
                    case 0x03 -> {
                        if (position + 2 > size) {
                            break loop;
                        }
                        colors[3] = (data[position] >> 4) & 0xF;
                        colors[2] = data[position] & 0xF;
                        colors[1] = (data[position + 1] >> 4) & 0xF;
                        colors[0] = data[position + 1] & 0xF;
                        position += 2;
                        changed = true;
                    }
                    case 0x04 -> {
                        if (position + 2 > size) {
                            break loop;
                        }
                        contrast[3] = (data[position] >> 4) & 0xF;
                        contrast[2] = data[position] & 0xF;
                        contrast[1] = (data[position + 1] >> 4) & 0xF;
                        contrast[0] = data[position + 1] & 0xF;
                        position += 2;
                        changed = true;
                    }
                    case 0x05 -> {
                        if (position + 6 > size) {
                            break loop;
                        }
                        area[0] = (data[position] & 0xFF) << 4 | (data[position + 1] & 0xF0) >> 4;
                        area[1] = (data[position + 1] & 0x0F) << 8 | (data[position + 2] & 0xFF);
                        area[2] = (data[position + 3] & 0xFF) << 4 | (data[position + 4] & 0xF0) >> 4;
                        area[3] = (data[position + 4] & 0x0F) << 8 | (data[position + 5] & 0xFF);
                        position += 6;
                        changed = true;
                    }
                    case 0x06 -> {
                        if (position + 4 > size) {
                            break loop;
                        }
                        offsets[0] = u16(data, position);
                        offsets[1] = u16(data, position + 2);
                        position += 4;
                        changed = true;
                    }
                    case 0x07 -> {
                        if (position + 2 > size) {
                            break loop;
                        }
                        var length = u16(data, position);
                        if (length < 2) {
                            break loop;
                        }
                        colcon[0] = position + 2;
                        colcon[1] = Math.min(position + length, size);
                        position += length;
                        changed = true;
                    }
                    default -> {
                        break loop;
                    }
                }
            }
            var at = micros + (delay * 102_400L + 4) / 9;
            if (start || (shown && changed)) {
                if (screen != null) {
                    sink.show(showMicros, screen, at);
                }
                screen = render(data, size, colors, contrast, area, offsets, colcon, forced);
                showMicros = at;
                shown = true;
            }
            if (stop && shown) {
                if (screen != null) {
                    sink.show(showMicros, screen, at);
                }
                screen = null;
                shown = false;
            }
            if (next == sequence || next < sequence) {
                break;
            }
            sequence = next;
        }
        if (screen != null) {
            sink.show(showMicros, screen, NO_DEADLINE);
        }
    }

    /**
     * Renders the picture the control sequences describe, or returns {@code null} when it shows nothing.
     */
    private Screen render(byte[] data, int size, int[] colors, int[] contrast, int[] area, int[] offsets,
                          int[] colcon, boolean forced) {
        var width = area[1] - area[0] + 1;
        var height = area[3] - area[2] + 1;
        if (width <= 0 || height <= 0 || offsets[0] < 4 || offsets[1] < 4) {
            return null;
        }
        LimitChecks.checkPicture(limits, width, height);
        var indices = new byte[width * height];
        field(data, size, offsets[0], indices, width, height, 0);
        field(data, size, offsets[1], indices, width, height, 1);
        int[] argb;
        if (colcon[0] >= 0) {
            argb = new int[MAX_COLOR_SETS * 4];
            colorSet(colors, contrast, argb, 0);
            colorAreas(data, colcon[0], colcon[1], colors, contrast, area, indices, width, height, argb);
        } else {
            argb = new int[4];
            colorSet(colors, contrast, argb, 0);
        }
        if (!Palettes.visible(indices, indices.length, argb)) {
            return null;
        }
        var canvasWidth = header.width() > 0 ? header.width() : 720;
        var canvasHeight = header.height() > 0 ? header.height() : area[3] >= 480 ? 576 : 480;
        LimitChecks.checkPicture(limits, canvasWidth, canvasHeight);
        var region = new BitmapRegion(header.originX() + area[0], header.originY() + area[2],
                Palettes.paint(indices, width, height, argb));
        return new Screen(canvasWidth, canvasHeight, List.of(region), forced);
    }

    /**
     * Converts four colors and contrasts into RGBA, or the header's custom colors when it turns them on.
     *
     * @param colors the palette entries of pixel values 0 to 3
     * @param contrast the contrasts of pixel values 0 to 3, 0 (transparent) to 15 (opaque)
     * @param argb receives the colors as {@code 0xRRGGBBAA}
     * @param at the index of the color of pixel value 0 in {@code argb}
     */
    private void colorSet(int[] colors, int[] contrast, int[] argb, int at) {
        for (var index = 0; index < 4; index++) {
            if (header.customColors() != null) {
                var transparent = (header.transparentCustom() >> (3 - index) & 1) != 0;
                argb[at + index] = header.customColors()[index] << 8 | (transparent ? 0 : 255);
            } else {
                argb[at + index] = palette[colors[index]] << 8 | contrast[index] * 17;
            }
        }
    }

    /**
     * Applies the areas of a {@code CHG_COLCON} command: each pixel inside an area takes the index of the area's
     * color set in its upper six bits, and the set's colors fill the matching four entries of {@code argb}.
     *
     * @param data the subpicture unit
     * @param from the index of the first line control word
     * @param to the end of the command's parameters
     * @param colors the palette entries the command {@code 03} set, for the pixels outside every area
     * @param contrast the contrasts the command {@code 04} set, for the pixels outside every area
     * @param area the left, right, top and bottom screen coordinates of the picture
     * @param indices the pixel values of the picture, 0 to 3, row by row
     * @param width the width of the picture
     * @param height the height of the picture
     * @param argb receives the colors of every color set, four entries per set, set 0 already filled
     */
    private void colorAreas(byte[] data, int from, int to, int[] colors, int[] contrast, int[] area, byte[] indices,
                            int width, int height, int[] argb) {
        var keys = new int[MAX_COLOR_SETS];
        keys[0] = word(colors) << 16 | word(contrast);
        var sets = 1;
        var areaColors = new int[4];
        var areaContrast = new int[4];
        var position = from;
        while (position + 4 <= to) {
            var control = u16(data, position) << 16 | u16(data, position + 2);
            if (control == COLCON_END) {
                break;
            }
            var firstLine = (control >> 16) & 0xFFF;
            var count = (control >> 12) & 0xF;
            var lastLine = control & 0xFFF;
            position += 4;
            if (position + count * 6 > to) {
                break;
            }
            var top = Math.max(firstLine - area[2], 0);
            var bottom = Math.min(lastLine - area[2], height - 1);
            for (var entry = 0; entry < count; entry++) {
                var at = position + entry * 6;
                var left = Math.max((u16(data, at) & 0xFFF) - area[0], 0);
                var right = entry + 1 < count ? Math.min((u16(data, at + 6) & 0xFFF) - area[0], width) : width;
                if (top > bottom || left >= right) {
                    continue;
                }
                var key = u16(data, at + 2) << 16 | u16(data, at + 4);
                var set = 0;
                while (set < sets && keys[set] != key) {
                    set++;
                }
                if (set == sets) {
                    if (sets == MAX_COLOR_SETS) {
                        continue;
                    }
                    keys[sets++] = key;
                    unpack(key >>> 16, areaColors);
                    unpack(key & 0xFFFF, areaContrast);
                    colorSet(areaColors, areaContrast, argb, set * 4);
                }
                for (var y = top; y <= bottom; y++) {
                    var row = y * width;
                    for (var x = left; x < right; x++) {
                        indices[row + x] = (byte) (set << 2 | (indices[row + x] & 3));
                    }
                }
            }
            position += count * 6;
        }
    }

    /**
     * Packs four nibbles as the commands {@code 03} and {@code 04} write them, pixel value 3 first.
     *
     * @param values the values of pixel values 0 to 3
     * @return the 16-bit word
     */
    private static int word(int[] values) {
        return values[3] << 12 | values[2] << 8 | values[1] << 4 | values[0];
    }

    /**
     * Unpacks four nibbles written as the commands {@code 03} and {@code 04} write them, pixel value 3 first.
     *
     * @param word the 16-bit word
     * @param values receives the values of pixel values 0 to 3
     */
    private static void unpack(int word, int[] values) {
        values[3] = (word >> 12) & 0xF;
        values[2] = (word >> 8) & 0xF;
        values[1] = (word >> 4) & 0xF;
        values[0] = word & 0xF;
    }

    /**
     * Decodes the lines of one field.
     */
    private static void field(byte[] data, int size, int offset, byte[] indices, int width, int height, int first) {
        var nibble = offset * 2L;
        var end = size * 2L;
        for (var y = first; y < height; y += 2) {
            var x = 0;
            while (x < width && nibble < end) {
                var value = nibble(data, nibble++);
                if (value < 0x4) {
                    value = value << 4 | nibble(data, nibble++);
                    if (value < 0x10) {
                        value = value << 4 | nibble(data, nibble++);
                        if (value < 0x40) {
                            value = value << 4 | nibble(data, nibble++);
                        }
                    }
                }
                var run = value >> 2;
                var color = value & 3;
                var to = run == 0 ? width : Math.min(width, x + run);
                if (color != 0) {
                    Arrays.fill(indices, y * width + x, y * width + to, (byte) color);
                }
                x = to;
            }
            nibble = (nibble + 1) & ~1L;
        }
    }

    private static int nibble(byte[] data, long index) {
        var at = (int) (index >> 1);
        if (at >= data.length) {
            return 0;
        }
        return (index & 1) == 0 ? (data[at] >> 4) & 0xF : data[at] & 0xF;
    }

    /**
     * Returns the delay of the first stop command of a subpicture unit.
     *
     * @param data the subpicture unit
     * @return the delay in 90 kHz ticks, or {@code -1} when the unit has no stop command
     */
    public static long stopTicks(byte[] data) {
        if (data.length < 4) {
            return -1;
        }
        var size = Math.min(u16(data, 0), data.length);
        var sequence = u16(data, 2);
        for (var count = 0; count < MAX_SEQUENCES && sequence + 4 <= size; count++) {
            var delay = u16(data, sequence);
            var next = u16(data, sequence + 2);
            var position = sequence + 4;
            while (position < size) {
                var command = data[position++] & 0xFF;
                var parameters = switch (command) {
                    case 0x00, 0x01 -> 0;
                    case 0x02 -> -2;
                    case 0x03, 0x04 -> 2;
                    case 0x05 -> 6;
                    case 0x06 -> 4;
                    case 0x07 -> position + 2 <= size && u16(data, position) >= 2 ? u16(data, position) : -1;
                    default -> -1;
                };
                if (parameters == -2) {
                    return delay * 1024L;
                }
                if (parameters < 0) {
                    break;
                }
                position += parameters;
            }
            if (next <= sequence) {
                break;
            }
            sequence = next;
        }
        return -1;
    }

    private static int u16(byte[] data, int offset) {
        return (data[offset] & 0xFF) << 8 | (data[offset + 1] & 0xFF);
    }
}
