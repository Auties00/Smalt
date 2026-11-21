package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.font.Rasterizer;
import com.github.auties00.smalt.internal.font.TrueTypeFont;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.internal.subtitle.Markup;
import com.github.auties00.smalt.internal.subtitle.StyledText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Draws subtitle cues as pictures to place over video.
 *
 * <p>The cue text is drawn with the {@link SubtitleStyle}: its {@code <b>}, {@code <i>} and
 * {@code <u>} styles (or the ASS overrides of the same styles) give bold (the glyphs thickened by
 * 3% of the em size), italic (slanted by 0.2) and underlined text; other markup is not drawn. Lines
 * break at line feeds and wrap at spaces, or within a word that alone is too long, to fit between
 * the margins. The lines are aligned to the left, the center or the right of the text block as the
 * placement column says, and every line is surrounded by the outline.
 *
 * <p>The block is placed as {@link SubtitleStyle#alignment()} says, unless the cue places itself:
 * a SubRip or ASS {@code {\anN}} override sets the keypad position, and a WebVTT cue's
 * {@code align} setting ({@code start} or {@code left}, {@code center} or {@code middle},
 * {@code end} or {@code right}) the column and its {@code line} setting the row: a percentage puts
 * the top of the text at that fraction of the height, a line number {@code n} at {@code n} line
 * heights from the top, a negative one counts from the bottom.
 *
 * <p>The output depends only on the arguments. This class is thread-safe.
 */
public final class SubtitleRenderer {
    /**
     * The slant of italic text.
     */
    private static final double ITALIC_SHEAR = 0.2;

    /**
     * The thickening of bold text, relative to the em size.
     */
    private static final double BOLD_RADIUS = 0.03;

    /**
     * The thickness of an underline relative to the em size, when the font gives none.
     */
    private static final double UNDERLINE_THICKNESS = 0.05;

    /**
     * The largest distance between a curve and the lines that approximate it, in pixels.
     */
    private static final double TOLERANCE = 0.1;

    /**
     * Prevents instantiation.
     */
    private SubtitleRenderer() {
        throw new AssertionError();
    }

    /**
     * Draws a cue for a picture of a size.
     *
     * @param cue the cue, whose timing is ignored
     * @param markup the markup of the cue text
     * @param style the font, size, colors and placement
     * @param width the width of the picture the cue is placed on, in pixels, positive
     * @param height the height of the picture the cue is placed on, in pixels, positive
     * @return the drawn cue, or empty when its text shows nothing (no characters, or only spaces)
     * @throws IllegalArgumentException if the width or the height is not positive
     * @throws com.github.auties00.smalt.MediaException.Malformed if a glyph of the font is damaged
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the drawing runs out of memory
     */
    public static Optional<SubtitleBitmap> render(SubtitleCue cue, SubtitleMarkup markup, SubtitleStyle style,
                                                  int width, int height) {
        Objects.requireNonNull(cue, "cue");
        Objects.requireNonNull(markup, "markup");
        Objects.requireNonNull(style, "style");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("picture size " + width + "x" + height + " is not positive");
        }
        return Guards.malformed("subtitle cue", () -> draw(cue, markup, style, width, height));
    }

    /**
     * Draws a cue, as {@link #render} describes, with valid arguments.
     *
     * @param cue the cue to draw
     * @param markup the markup of the cue text
     * @param style the font, size, colors and placement
     * @param width the picture width
     * @param height the picture height
     * @return the drawn cue, or empty
     */
    private static Optional<SubtitleBitmap> draw(SubtitleCue cue, SubtitleMarkup markup, SubtitleStyle style, int width,
                                                 int height) {
        var font = style.font().font();
        var em = style.size() * height;
        var scale = em / font.unitsPerEm();
        var bold = Math.max(0.5, BOLD_RADIUS * em);
        var placement = new Placement(style.alignment());
        if (markup == SubtitleMarkup.WEBVTT) {
            placement.webVtt(cue.settings());
        } else {
            var alignment = Markup.subRipAlignment(cue.text());
            if (alignment != 0) {
                placement.column = (alignment - 1) % 3;
                placement.row = (alignment - 1) / 3;
            }
        }
        var runs = Markup.parse(cue.text(), markup, Markup.Context.of(cue)).runs();
        var lines = layout(runs, font, scale, bold, width * (1 - 2 * style.marginX()));
        var visible = false;
        var widest = 0.0;
        for (var line : lines) {
            widest = Math.max(widest, line.width);
            for (var index = 0; index < line.count; index++) {
                visible |= !font.outline(line.glyphs[index]).isEmpty() || (line.styles[index] & StyledText.UNDERLINE) != 0;
            }
        }
        if (!visible) {
            return Optional.empty();
        }
        var outline = style.outline() * em;
        var ascent = font.ascender() * scale;
        var descent = -font.descender() * scale;
        var natural = (font.ascender() - font.descender() + font.lineGap()) * scale;
        var lineHeight = (natural > 0 ? natural : 1.2 * em) * style.lineSpacing();
        var padY = (int) Math.ceil(outline + bold + 1);
        var padTop = padY + (int) Math.ceil(Math.clamp((font.yMax() - font.ascender()) * scale, 0, 2 * em));
        var padBottom = padY + (int) Math.ceil(Math.clamp((font.descender() - font.yMin()) * scale, 0, 2 * em));
        var reach = Math.min(3 * em, Math.max(Math.max(ascent, descent), Math.max(font.yMax(), -font.yMin()) * scale));
        var padX = (int) Math.ceil(outline + bold + 1 + ITALIC_SHEAR * reach);
        var linesHeight = lines.size() * lineHeight;
        // text past four picture sizes is clipped by the target anyway, so the block is bounded
        var blockWidth = (int) Math.ceil(Math.min(widest, 4.0 * width)) + 2 * padX;
        var blockHeight = (int) Math.ceil(Math.min(linesHeight, 4.0 * height)) + padTop + padBottom;
        var regular = new Rasterizer(blockWidth, blockHeight);
        var thick = new Rasterizer(blockWidth, blockHeight);
        var anyBold = false;
        var underlineThickness = Math.max(1, font.underlineThickness() > 0 ? font.underlineThickness() * scale
                : UNDERLINE_THICKNESS * em);
        var underlineOffset = font.underlinePosition() != 0 ? -font.underlinePosition() * scale : 0.1 * em;
        for (var row = 0; row < lines.size(); row++) {
            var line = lines.get(row);
            var top = padTop + row * lineHeight;
            var baseline = top + (lineHeight - (ascent + descent)) / 2 + ascent;
            var left = padX + switch (placement.column) {
                case 0 -> 0.0;
                case 2 -> widest - line.width;
                default -> (widest - line.width) / 2;
            };
            for (var index = 0; index < line.count; index++) {
                var glyphStyle = line.styles[index];
                var target = (glyphStyle & StyledText.BOLD) != 0 ? thick : regular;
                anyBold |= target == thick;
                var x = left + line.positions[index];
                var shear = (glyphStyle & StyledText.ITALIC) != 0 ? ITALIC_SHEAR : 0;
                font.outline(line.glyphs[index]).flatten(scale, shear, x, baseline, TOLERANCE, target);
                if ((glyphStyle & StyledText.UNDERLINE) != 0) {
                    rectangle(target, x, baseline + underlineOffset, line.advances[index], underlineThickness);
                }
            }
        }
        var mask = regular.fill();
        if (anyBold) {
            var thickened = dilate(thick.fill(), blockWidth, blockHeight, bold);
            for (var index = 0; index < mask.length; index++) {
                mask[index] = (byte) Math.max(mask[index] & 0xFF, thickened[index] & 0xFF);
            }
        }
        var border = outline > 0 ? dilate(mask, blockWidth, blockHeight, outline) : null;
        var picture = compose(mask, border, blockWidth, blockHeight, style.color(), style.outlineColor());
        var marginX = style.marginX() * width;
        var marginY = style.marginY() * height;
        var x = switch (placement.column) {
            case 0 -> marginX - padX;
            case 2 -> width - marginX - blockWidth + padX;
            default -> (width - blockWidth) / 2.0;
        };
        double y;
        if (placement.linePercent >= 0) {
            y = placement.linePercent / 100 * height - padTop;
        } else if (placement.lineNumber != Integer.MIN_VALUE) {
            y = placement.lineNumber >= 0 ? placement.lineNumber * lineHeight - padTop
                    : height + (placement.lineNumber + 1) * lineHeight - linesHeight - padTop;
        } else {
            y = switch (placement.row) {
                case 0 -> height - marginY - linesHeight - padTop;
                case 2 -> marginY - padTop;
                default -> (height - linesHeight) / 2.0 - padTop;
            };
        }
        return Optional.of(new SubtitleBitmap(picture, (int) Math.round(x), (int) Math.round(y)));
    }

    /**
     * Breaks styled text into lines of positioned glyphs.
     *
     * @param spans the styled runs
     * @param font the font the glyphs come from
     * @param scale the factor from design units to pixels
     * @param bold the thickening of bold glyphs, in pixels
     * @param maxWidth the widest a line may be, in pixels
     * @return the lines, at least one
     */
    private static List<Line> layout(List<StyledText.Run> spans, TrueTypeFont font, double scale, double bold,
                                     double maxWidth) {
        var lines = new ArrayList<Line>();
        var line = new Line();
        for (var span : spans) {
            var text = span.text();
            for (var offset = 0; offset < text.length(); ) {
                var codePoint = text.codePointAt(offset);
                offset += Character.charCount(codePoint);
                if (codePoint == '\n') {
                    lines.add(line.trimmed());
                    line = new Line();
                    continue;
                }
                if (codePoint == '\r' || codePoint == '\t') {
                    codePoint = ' ';
                }
                if (Character.getType(codePoint) == Character.CONTROL) {
                    continue;
                }
                var glyph = font.glyph(codePoint);
                var advance = font.advance(glyph) * scale + ((span.style() & StyledText.BOLD) != 0 ? 2 * bold : 0);
                if (codePoint == ' ' && line.count == 0) {
                    continue;
                }
                if (codePoint != ' ' && line.width + advance > maxWidth && line.count > 0) {
                    var rest = line.wrap();
                    lines.add(line.trimmed());
                    line = rest;
                }
                line.add(glyph, span.style(), advance, codePoint == ' ');
            }
        }
        lines.add(line.trimmed());
        return lines;
    }

    /**
     * Adds a rectangle to a rasterizer.
     *
     * @param target the rasterizer
     * @param x the left edge
     * @param y the top edge
     * @param width the width in pixels
     * @param height the height in pixels
     */
    private static void rectangle(Rasterizer target, double x, double y, double width, double height) {
        target.line(x, y, x + width, y);
        target.line(x + width, y, x + width, y + height);
        target.line(x + width, y + height, x, y + height);
        target.line(x, y + height, x, y);
    }

    /**
     * Grows a coverage mask by a radius: a pixel {@code d} pixels from the nearest pixel at least half
     * covered (center to center) takes the coverage {@code radius + 1 - d}, clamped to 0 to 1, or its own
     * when that is more; the cost does not depend on the radius.
     *
     * @param mask the coverage
     * @param width the mask width
     * @param height the mask height
     * @param radius the radius in pixels, positive
     * @return the grown coverage
     */
    private static byte[] dilate(byte[] mask, int width, int height, double radius) {
        var infinite = (double) width * width + (double) height * height + 1;
        var distances = new double[mask.length];
        for (var index = 0; index < mask.length; index++) {
            distances[index] = (mask[index] & 0xFF) >= 128 ? 0 : infinite;
        }
        var size = Math.max(width, height);
        var line = new double[size];
        var result = new double[size];
        var vertices = new int[size];
        var bounds = new double[size + 1];
        for (var x = 0; x < width; x++) {
            for (var y = 0; y < height; y++) {
                line[y] = distances[y * width + x];
            }
            transform(line, height, result, vertices, bounds, infinite);
            for (var y = 0; y < height; y++) {
                distances[y * width + x] = result[y];
            }
        }
        for (var y = 0; y < height; y++) {
            System.arraycopy(distances, y * width, line, 0, width);
            transform(line, width, result, vertices, bounds, infinite);
            System.arraycopy(result, 0, distances, y * width, width);
        }
        var out = new byte[mask.length];
        for (var index = 0; index < mask.length; index++) {
            var weight = Math.clamp(radius + 1 - Math.sqrt(distances[index]), 0, 1);
            out[index] = (byte) Math.max(mask[index] & 0xFF, (int) Math.round(weight * 255));
        }
        return out;
    }

    /**
     * Computes the one-dimensional squared Euclidean distance transform of a sampled function, the lower
     * envelope of the parabolas rooted at its samples (Felzenszwalb and Huttenlocher, "Distance
     * Transforms of Sampled Functions", 2012).
     *
     * @param f the squared distances along the line
     * @param n the number of samples
     * @param out receives the transformed squared distances
     * @param vertices scratch for the parabola roots, at least {@code n} long
     * @param bounds scratch for the envelope boundaries, at least {@code n + 1} long
     * @param infinite the value of an unreached sample
     */
    private static void transform(double[] f, int n, double[] out, int[] vertices, double[] bounds, double infinite) {
        var k = -1;
        for (var q = 0; q < n; q++) {
            if (f[q] >= infinite) {
                continue;
            }
            if (k < 0) {
                k = 0;
                vertices[0] = q;
                bounds[0] = Double.NEGATIVE_INFINITY;
                bounds[1] = Double.POSITIVE_INFINITY;
                continue;
            }
            var s = ((f[q] + (double) q * q) - (f[vertices[k]] + (double) vertices[k] * vertices[k]))
                    / (2.0 * q - 2.0 * vertices[k]);
            while (s <= bounds[k]) {
                k--;
                s = ((f[q] + (double) q * q) - (f[vertices[k]] + (double) vertices[k] * vertices[k]))
                        / (2.0 * q - 2.0 * vertices[k]);
            }
            k++;
            vertices[k] = q;
            bounds[k] = s;
            bounds[k + 1] = Double.POSITIVE_INFINITY;
        }
        if (k < 0) {
            for (var q = 0; q < n; q++) {
                out[q] = infinite;
            }
            return;
        }
        var j = 0;
        for (var q = 0; q < n; q++) {
            while (bounds[j + 1] < q) {
                j++;
            }
            var d = q - vertices[j];
            out[q] = (double) d * d + f[vertices[j]];
        }
    }

    /**
     * Paints the text color over the outline color through their coverage.
     *
     * @param text the coverage of the text
     * @param border the coverage of the outline, or {@code null} for none
     * @param width the width in pixels
     * @param height the height in pixels
     * @param color the text color as {@code 0xAARRGGBB}
     * @param outlineColor the outline color as {@code 0xAARRGGBB}
     * @return the picture, with straight alpha
     */
    private static RgbaPicture compose(byte[] text, byte[] border, int width, int height, int color, int outlineColor) {
        var picture = RgbaPicture.allocate(width, height, true);
        var pixels = picture.pixels();
        var stride = picture.stride();
        var textAlpha = color >>> 24;
        var borderAlpha = outlineColor >>> 24;
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var t = (text[y * width + x] & 0xFF) * textAlpha / 255.0 / 255.0;
                var b = border == null ? 0 : (border[y * width + x] & 0xFF) * borderAlpha / 255.0 / 255.0;
                var alpha = t + b * (1 - t);
                var index = y * stride + 4 * x;
                if (alpha <= 0) {
                    pixels[index] = 0;
                    pixels[index + 1] = 0;
                    pixels[index + 2] = 0;
                    pixels[index + 3] = 0;
                    continue;
                }
                var under = b * (1 - t);
                for (var channel = 0; channel < 3; channel++) {
                    var shift = 16 - 8 * channel;
                    var value = (((color >>> shift) & 0xFF) * t + ((outlineColor >>> shift) & 0xFF) * under) / alpha;
                    pixels[index + channel] = (byte) Math.round(value);
                }
                pixels[index + 3] = (byte) Math.round(alpha * 255);
            }
        }
        return picture;
    }

    /**
     * Holds the glyphs of one line.
     */
    private static final class Line {
        private int[] glyphs = new int[16];
        private int[] styles = new int[16];
        private double[] positions = new double[16];
        private double[] advances = new double[16];
        private boolean[] spaces = new boolean[16];
        private int count;
        private double width;

        void add(int glyph, int style, double advance, boolean space) {
            if (count == glyphs.length) {
                glyphs = Arrays.copyOf(glyphs, 2 * count);
                styles = Arrays.copyOf(styles, 2 * count);
                positions = Arrays.copyOf(positions, 2 * count);
                advances = Arrays.copyOf(advances, 2 * count);
                spaces = Arrays.copyOf(spaces, 2 * count);
            }
            glyphs[count] = glyph;
            styles[count] = style;
            positions[count] = width;
            advances[count] = advance;
            spaces[count] = space;
            count++;
            width += advance;
        }

        /**
         * Moves the glyphs after the last space into a new line, or none when the line has no space.
         *
         * @return the new line
         */
        Line wrap() {
            var rest = new Line();
            var space = count - 1;
            while (space >= 0 && !spaces[space]) {
                space--;
            }
            if (space < 0) {
                return rest;
            }
            for (var index = space + 1; index < count; index++) {
                rest.add(glyphs[index], styles[index], advances[index], false);
            }
            count = space;
            width = space == 0 ? 0 : positions[space];
            return rest;
        }

        /**
         * Drops trailing spaces from the width.
         *
         * @return this line
         */
        Line trimmed() {
            while (count > 0 && spaces[count - 1]) {
                count--;
                width = positions[count];
            }
            return this;
        }
    }

    /**
     * Holds where a cue goes.
     */
    private static final class Placement {
        /**
         * The column: 0 left, 1 center, 2 right.
         */
        private int column;

        /**
         * The row: 0 bottom, 1 middle, 2 top.
         */
        private int row;

        /**
         * The WebVTT line as a percentage, or {@code -1}.
         */
        private double linePercent = -1;

        /**
         * The WebVTT line number, or {@link Integer#MIN_VALUE}.
         */
        private int lineNumber = Integer.MIN_VALUE;

        Placement(int alignment) {
            this.column = (alignment - 1) % 3;
            this.row = (alignment - 1) / 3;
        }

        /**
         * Applies the {@code line} and {@code align} settings of a WebVTT cue.
         *
         * @param settings the cue settings list
         */
        void webVtt(String settings) {
            for (var setting : settings.trim().split("\\s+")) {
                var colon = setting.indexOf(':');
                if (colon <= 0) {
                    continue;
                }
                var name = setting.substring(0, colon);
                var value = setting.substring(colon + 1);
                if (name.equals("align")) {
                    switch (value) {
                        case "start", "left" -> column = 0;
                        case "center", "middle" -> column = 1;
                        case "end", "right" -> column = 2;
                        default -> {
                        }
                    }
                } else if (name.equals("line")) {
                    var comma = value.indexOf(',');
                    var number = comma >= 0 ? value.substring(0, comma) : value;
                    try {
                        if (number.endsWith("%")) {
                            var percent = Double.parseDouble(number.substring(0, number.length() - 1));
                            if (percent >= 0 && percent <= 100) {
                                linePercent = percent;
                            }
                        } else {
                            lineNumber = Integer.parseInt(number);
                        }
                    } catch (NumberFormatException ignored) {
                        // a malformed setting is ignored, as WebVTT parsers ignore it
                    }
                }
            }
        }
    }
}
