package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.internal.check.Checks;

import java.util.Objects;

/**
 * Holds the settings of {@link SubtitleRenderer}: the font, size, colors and placement of drawn
 * cues. Instances are immutable and thread-safe; create them with {@link #builder(SubtitleFont)} or
 * a preset such as {@link #of(SubtitleFont)}.
 *
 * <p>Sizes and margins are fractions of the picture they are drawn for, so the same style suits
 * every picture size.
 */
public final class SubtitleStyle {
    private final SubtitleFont font;
    private final double size;
    private final int color;
    private final int outlineColor;
    private final double outline;
    private final double marginX;
    private final double marginY;
    private final double lineSpacing;
    private final int alignment;

    private SubtitleStyle(Builder builder) {
        this.font = builder.font;
        this.size = builder.size;
        this.color = builder.color;
        this.outlineColor = builder.outlineColor;
        this.outline = builder.outline;
        this.marginX = builder.marginX;
        this.marginY = builder.marginY;
        this.lineSpacing = builder.lineSpacing;
        this.alignment = builder.alignment;
    }

    /**
     * Returns the default style with a font: white text with a black outline, centered at the bottom.
     *
     * @param font the font the text is drawn with
     * @return the style
     */
    public static SubtitleStyle of(SubtitleFont font) {
        return builder(font).build();
    }

    /**
     * Returns a builder initialized with {@link #of(SubtitleFont)}.
     *
     * @param font the font the text is drawn with
     * @return the builder
     */
    public static Builder builder(SubtitleFont font) {
        return new Builder(Objects.requireNonNull(font, "font"));
    }

    /**
     * Returns a builder initialized with this style.
     *
     * @return the builder
     */
    public Builder toBuilder() {
        return new Builder(this);
    }

    /**
     * {@return the font}
     */
    public SubtitleFont font() {
        return font;
    }

    /**
     * {@return the em size of the text relative to the picture height, greater than 0 and at most 1;
     * 0.055 by default}
     */
    public double size() {
        return size;
    }

    /**
     * {@return the color of the text as an unsigned {@code 0xAARRGGBB} sRGB value; opaque white
     * ({@code 0xFFFFFFFF}) by default}
     */
    public int color() {
        return color;
    }

    /**
     * {@return the color of the outline around the text as an unsigned {@code 0xAARRGGBB} sRGB value;
     * opaque black ({@code 0xFF000000}) by default}
     */
    public int outlineColor() {
        return outlineColor;
    }

    /**
     * {@return the width of the outline relative to the em size, from 0 (no outline) to 0.5; 0.07 by
     * default}
     */
    public double outline() {
        return outline;
    }

    /**
     * {@return the distance between the text and the left and right picture edges, relative to the
     * picture width, from 0 to 0.45; 0.05 by default}
     *
     * <p>Lines longer than the width between the margins wrap at spaces.
     */
    public double marginX() {
        return marginX;
    }

    /**
     * {@return the distance between the text and the top or bottom picture edge it is aligned to,
     * relative to the picture height, from 0 to 0.45; 0.05 by default}
     */
    public double marginY() {
        return marginY;
    }

    /**
     * {@return the factor applied to the line height the font gives (ascender, descender and line gap),
     * from 0.5 to 4; 1 by default}
     */
    public double lineSpacing() {
        return lineSpacing;
    }

    /**
     * {@return where cues are placed, as a numeric keypad position: 1 to 3 at the bottom, 4 to 6 in the
     * middle, 7 to 9 at the top, from left to right; 2 (bottom center) by default}
     *
     * <p>A cue that places itself overrides it: the {@code {\anN}} override of SubRip and ASS text, and
     * the {@code line} and {@code align} settings of a WebVTT cue.
     */
    public int alignment() {
        return alignment;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SubtitleStyle that && font == that.font && size == that.size && color == that.color
                && outlineColor == that.outlineColor && outline == that.outline && marginX == that.marginX
                && marginY == that.marginY && lineSpacing == that.lineSpacing && alignment == that.alignment;
    }

    @Override
    public int hashCode() {
        return Objects.hash(System.identityHashCode(font), size, color, outlineColor, outline, marginX, marginY,
                lineSpacing, alignment);
    }

    @Override
    public String toString() {
        return "SubtitleStyle[size=" + size + ", color=0x" + Integer.toHexString(color) + ", outlineColor=0x"
                + Integer.toHexString(outlineColor) + ", outline=" + outline + ", marginX=" + marginX + ", marginY="
                + marginY + ", lineSpacing=" + lineSpacing + ", alignment=" + alignment + "]";
    }

    /**
     * Builds {@link SubtitleStyle}.
     *
     * <p>Setters store their value; {@link #build()} checks the ranges. Instances are not
     * thread-safe.
     */
    public static final class Builder {
        private SubtitleFont font;
        private double size = 0.055;
        private int color = 0xFFFFFFFF;
        private int outlineColor = 0xFF000000;
        private double outline = 0.07;
        private double marginX = 0.05;
        private double marginY = 0.05;
        private double lineSpacing = 1;
        private int alignment = 2;

        private Builder(SubtitleFont font) {
            this.font = font;
        }

        private Builder(SubtitleStyle style) {
            this.font = style.font;
            this.size = style.size;
            this.color = style.color;
            this.outlineColor = style.outlineColor;
            this.outline = style.outline;
            this.marginX = style.marginX;
            this.marginY = style.marginY;
            this.lineSpacing = style.lineSpacing;
            this.alignment = style.alignment;
        }

        /**
         * Sets the font; see {@link SubtitleStyle#font()}.
         *
         * @param font the value
         * @return this builder
         */
        public Builder font(SubtitleFont font) {
            this.font = Objects.requireNonNull(font, "font");
            return this;
        }

        /**
         * Sets the em size of the text; see {@link SubtitleStyle#size()}.
         *
         * @param size the value
         * @return this builder
         */
        public Builder size(double size) {
            this.size = size;
            return this;
        }

        /**
         * Sets the color of the text; see {@link SubtitleStyle#color()}.
         *
         * @param argb the value
         * @return this builder
         */
        public Builder color(int argb) {
            this.color = argb;
            return this;
        }

        /**
         * Sets the color of the outline; see {@link SubtitleStyle#outlineColor()}.
         *
         * @param argb the value
         * @return this builder
         */
        public Builder outlineColor(int argb) {
            this.outlineColor = argb;
            return this;
        }

        /**
         * Sets the width of the outline; see {@link SubtitleStyle#outline()}.
         *
         * @param outline the value
         * @return this builder
         */
        public Builder outline(double outline) {
            this.outline = outline;
            return this;
        }

        /**
         * Sets the margins; see {@link SubtitleStyle#marginX()} and {@link SubtitleStyle#marginY()}.
         *
         * @param marginX the horizontal margin
         * @param marginY the vertical margin
         * @return this builder
         */
        public Builder margins(double marginX, double marginY) {
            this.marginX = marginX;
            this.marginY = marginY;
            return this;
        }

        /**
         * Sets the line spacing; see {@link SubtitleStyle#lineSpacing()}.
         *
         * @param lineSpacing the value
         * @return this builder
         */
        public Builder lineSpacing(double lineSpacing) {
            this.lineSpacing = lineSpacing;
            return this;
        }

        /**
         * Sets the placement of cues; see {@link SubtitleStyle#alignment()}.
         *
         * @param alignment the value
         * @return this builder
         */
        public Builder alignment(int alignment) {
            this.alignment = alignment;
            return this;
        }

        /**
         * Builds the style.
         *
         * @return the style
         * @throws IllegalArgumentException if the size is not greater than 0 and at most 1, the outline is
         * outside 0 to 0.5, a margin is outside 0 to 0.45, the line spacing is outside 0.5 to 4, or the
         * alignment is outside 1 to 9
         */
        public SubtitleStyle build() {
            if (!(size > 0 && size <= 1)) {
                throw new IllegalArgumentException("size " + size + " is not in (0, 1]");
            }
            Checks.inRange("outline", outline, 0, 0.5);
            Checks.inRange("horizontal margin", marginX, 0, 0.45);
            Checks.inRange("vertical margin", marginY, 0, 0.45);
            Checks.inRange("line spacing", lineSpacing, 0.5, 4);
            Checks.inRange("alignment", alignment, 1, 9);
            return new SubtitleStyle(this);
        }
    }
}
