package com.github.auties00.smalt.image.raw;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Describes the repeating arrangement of color filters over a sensor: a 2x2 Bayer pattern, the
 * 6x6 Fujifilm X-Trans pattern, or any other rectangle of colors that tiles the image.
 *
 * <p>The color of the photosite at row {@code r} and column {@code c} of the image the pattern
 * describes is {@code color(r % height(), c % width())}. Instances are immutable and thread-safe.
 */
public final class CfaPattern {
    /**
     * The largest repeat dimension accepted.
     */
    private static final int MAX_SIZE = 16;

    private final int width;

    private final int height;

    private final CfaColor[] colors;

    private CfaPattern(int width, int height, CfaColor[] colors) {
        this.width = width;
        this.height = height;
        this.colors = colors;
    }

    /**
     * Returns a pattern of a given size.
     *
     * @param width the repeat width, from 1 to 16
     * @param height the repeat height, from 1 to 16
     * @param colors the {@code width * height} colors, row by row
     * @return the pattern
     * @throws NullPointerException if an element of {@code colors} is {@code null}
     * @throws IllegalArgumentException if a dimension is out of range or the color count does not
     * match it
     */
    public static CfaPattern of(int width, int height, List<CfaColor> colors) {
        Objects.requireNonNull(colors, "colors");
        if (width < 1 || height < 1 || width > MAX_SIZE || height > MAX_SIZE) {
            throw new IllegalArgumentException("invalid CFA repeat size " + width + "x" + height);
        }
        if (colors.size() != width * height) {
            throw new IllegalArgumentException(colors.size() + " colors for a " + width + "x" + height + " pattern");
        }
        var array = new CfaColor[colors.size()];
        for (var index = 0; index < array.length; index++) {
            array[index] = Objects.requireNonNull(colors.get(index), "color");
        }
        return new CfaPattern(width, height, array);
    }

    /**
     * Returns the 2x2 Bayer pattern a name spells, such as {@code "RGGB"}: the colors of the top
     * left, top right, bottom left and bottom right photosites.
     *
     * @param name the four letters, each {@code R}, {@code G}, {@code B}, {@code C}, {@code M},
     * {@code Y} or {@code W}
     * @return the pattern
     * @throws IllegalArgumentException if the name is not four valid letters
     */
    public static CfaPattern bayer(String name) {
        Objects.requireNonNull(name, "name");
        if (name.length() != 4) {
            throw new IllegalArgumentException("a Bayer pattern has four colors: " + name);
        }
        var colors = new CfaColor[4];
        for (var index = 0; index < 4; index++) {
            var letter = "RGBCMYW".indexOf(Character.toUpperCase(name.charAt(index)));
            if (letter < 0) {
                throw new IllegalArgumentException("invalid CFA color letter in " + name);
            }
            colors[index] = CfaColor.of(letter);
        }
        return new CfaPattern(2, 2, colors);
    }

    /**
     * {@return the repeat width: the number of columns after which the pattern repeats}
     */
    public int width() {
        return width;
    }

    /**
     * {@return the repeat height: the number of rows after which the pattern repeats}
     */
    public int height() {
        return height;
    }

    /**
     * Returns the color of a photosite.
     *
     * @param row the row in the image, non-negative
     * @param column the column in the image, non-negative
     * @return the color of the filter over it
     * @throws IllegalArgumentException if a coordinate is negative
     */
    public CfaColor color(int row, int column) {
        if (row < 0 || column < 0) {
            throw new IllegalArgumentException("negative photosite " + row + ", " + column);
        }
        return colors[(row % height) * width + column % width];
    }

    /**
     * {@return the colors of one repeat of the pattern, row by row; unmodifiable}
     */
    public List<CfaColor> colors() {
        return List.of(colors);
    }

    /**
     * {@return whether the pattern is a 2x2 Bayer arrangement of red, green and blue with two greens
     * on one diagonal}
     */
    public boolean isBayer() {
        if (width != 2 || height != 2) {
            return false;
        }
        return colors[0] == colors[3] ? colors[0] == CfaColor.GREEN && isRedBlue(colors[1], colors[2])
                : colors[1] == CfaColor.GREEN && colors[2] == CfaColor.GREEN && isRedBlue(colors[0], colors[3]);
    }

    /**
     * {@return whether two colors are red and blue, in either order}
     *
     * @param first a color
     * @param second another color
     */
    private static boolean isRedBlue(CfaColor first, CfaColor second) {
        return (first == CfaColor.RED && second == CfaColor.BLUE) || (first == CfaColor.BLUE && second == CfaColor.RED);
    }

    /**
     * {@return whether every filter of the pattern is red, green or blue}
     */
    public boolean isRgb() {
        for (var color : colors) {
            if (color != CfaColor.RED && color != CfaColor.GREEN && color != CfaColor.BLUE) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the pattern of the image that starts at a given photosite of this one, as cropping
     * shifts it.
     *
     * @param top the first row kept
     * @param left the first column kept
     * @return the shifted pattern
     * @throws IllegalArgumentException if a coordinate is negative
     */
    public CfaPattern shifted(int top, int left) {
        if (top < 0 || left < 0) {
            throw new IllegalArgumentException("negative shift " + top + ", " + left);
        }
        var shifted = new CfaColor[colors.length];
        for (var row = 0; row < height; row++) {
            for (var column = 0; column < width; column++) {
                shifted[row * width + column] = color(row + top, column + left);
            }
        }
        return new CfaPattern(width, height, shifted);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CfaPattern that && width == that.width && height == that.height
                && Arrays.equals(colors, that.colors);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * width + height) + Arrays.hashCode(colors);
    }

    /**
     * {@return the letters of the pattern, row by row, with rows separated by {@code /} when the
     * pattern is not 2x2, such as {@code "RGGB"}}
     */
    @Override
    public String toString() {
        var text = new StringBuilder();
        for (var index = 0; index < colors.length; index++) {
            if (index > 0 && index % width == 0 && !(width == 2 && height == 2)) {
                text.append('/');
            }
            text.append(colors[index].letter());
        }
        return text.toString();
    }
}
