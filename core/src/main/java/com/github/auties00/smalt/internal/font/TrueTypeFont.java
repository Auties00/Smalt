package com.github.auties00.smalt.internal.font;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Reads the outlines and metrics of a TrueType font: the {@code glyf} outlines of an OpenType or
 * TrueType file, or of the first font of a collection.
 *
 * <p>The tables read are {@code head}, {@code maxp}, {@code hhea}, {@code hmtx}, {@code loca},
 * {@code glyf}, {@code cmap} (formats 4, 6 and 12 of the Unicode encodings, and the Windows
 * symbol encoding) and, when present, {@code post} for the underline. Hinting instructions are
 * ignored. Every offset and count is checked against the data, so a damaged font fails with
 * {@link MediaException.Malformed} when it is read or, for a glyph, when the glyph is first
 * outlined. Instances are immutable and thread-safe.
 */
public final class TrueTypeFont {
    /**
     * The deepest nesting of composite glyphs followed.
     */
    private static final int MAX_COMPOSITE_DEPTH = 8;

    /**
     * The most points a composite glyph may expand to.
     */
    private static final int MAX_POINTS = 1 << 16;

    /**
     * The most components a composite glyph may expand to, nested ones included.
     */
    private static final int MAX_COMPONENTS = 4096;

    private final byte[] data;
    private final int unitsPerEm;
    private final int ascender;
    private final int descender;
    private final int lineGap;
    private final int yMin;
    private final int yMax;
    private final int underlinePosition;
    private final int underlineThickness;
    private final int numGlyphs;
    private final int numberOfHMetrics;
    private final int hmtx;
    private final int hmtxLength;
    private final int glyf;
    private final int glyfLength;
    private final int loca;
    private final boolean longLoca;

    /**
     * The offset of the chosen {@code cmap} subtable, or {@code -1} when none is usable.
     */
    private final int cmap;

    private final int cmapFormat;

    /**
     * The end of the {@code cmap} table.
     */
    private final int cmapEnd;

    /**
     * Whether the subtable maps the Windows symbol range {@code U+F000} to {@code U+F0FF}.
     */
    private final boolean symbol;

    private TrueTypeFont(byte[] data, int unitsPerEm, int ascender, int descender, int lineGap, int yMin, int yMax,
                         int underlinePosition,
                         int underlineThickness, int numGlyphs, int numberOfHMetrics, int hmtx, int hmtxLength, int glyf,
                         int glyfLength, int loca, boolean longLoca, int cmap, int cmapFormat, int cmapEnd,
                         boolean symbol) {
        this.data = data;
        this.unitsPerEm = unitsPerEm;
        this.ascender = ascender;
        this.descender = descender;
        this.lineGap = lineGap;
        this.yMin = yMin;
        this.yMax = yMax;
        this.underlinePosition = underlinePosition;
        this.underlineThickness = underlineThickness;
        this.numGlyphs = numGlyphs;
        this.numberOfHMetrics = numberOfHMetrics;
        this.hmtx = hmtx;
        this.hmtxLength = hmtxLength;
        this.glyf = glyf;
        this.glyfLength = glyfLength;
        this.loca = loca;
        this.longLoca = longLoca;
        this.cmap = cmap;
        this.cmapFormat = cmapFormat;
        this.cmapEnd = cmapEnd;
        this.symbol = symbol;
    }

    /**
     * Reads a font.
     *
     * @param data the font file, which the font keeps; not modified
     * @return the font
     * @throws MediaException.Malformed if the file is not a TrueType font or is damaged
     * @throws MediaException.Unsupported if the font has PostScript ({@code CFF}) outlines
     */
    public static TrueTypeFont read(byte[] data) {
        var start = 0;
        var tag = u32(data, 0);
        if (tag == tag("ttcf")) {
            if (u32(data, 8) == 0) {
                throw new MediaException.Malformed("the font collection holds no font");
            }
            start = offset(data, u32(data, 12));
            tag = u32(data, start);
        }
        if (tag == tag("OTTO")) {
            throw new MediaException.Unsupported("OpenType CFF", "fonts with PostScript (CFF) outlines are not supported; "
                    + "use a font with TrueType outlines");
        }
        if (tag != 0x00010000 && tag != tag("true")) {
            throw new MediaException.Malformed("not a TrueType font");
        }
        var tables = u16(data, start + 4);
        int head = -1, maxp = -1, hhea = -1, hmtx = -1, loca = -1, glyf = -1, cmap = -1, post = -1;
        int hmtxLength = 0, locaLength = 0, glyfLength = 0, cmapLength = 0, postLength = 0, headLength = 0;
        int maxpLength = 0, hheaLength = 0;
        for (var index = 0; index < tables; index++) {
            var record = start + 12 + 16 * index;
            var name = u32(data, record);
            var offset = offset(data, u32(data, record + 8));
            var length = offset(data, u32(data, record + 12));
            if ((long) offset + length > data.length) {
                throw new MediaException.Malformed("a font table lies past the end of the file");
            }
            if (name == tag("head")) {
                head = offset;
                headLength = length;
            } else if (name == tag("maxp")) {
                maxp = offset;
                maxpLength = length;
            } else if (name == tag("hhea")) {
                hhea = offset;
                hheaLength = length;
            } else if (name == tag("hmtx")) {
                hmtx = offset;
                hmtxLength = length;
            } else if (name == tag("loca")) {
                loca = offset;
                locaLength = length;
            } else if (name == tag("glyf")) {
                glyf = offset;
                glyfLength = length;
            } else if (name == tag("cmap")) {
                cmap = offset;
                cmapLength = length;
            } else if (name == tag("post")) {
                post = offset;
                postLength = length;
            }
        }
        if (head < 0 || maxp < 0 || hhea < 0 || hmtx < 0 || loca < 0 || glyf < 0 || cmap < 0) {
            throw new MediaException.Malformed("the font lacks a head, maxp, hhea, hmtx, loca, glyf or cmap table");
        }
        if (headLength < 54 || maxpLength < 6 || hheaLength < 36) {
            throw new MediaException.Malformed("a font header table is truncated");
        }
        var unitsPerEm = u16(data, head + 18);
        if (unitsPerEm < 16 || unitsPerEm > 16384) {
            throw new MediaException.Malformed("units per em " + unitsPerEm + " is outside 16 to 16384");
        }
        var longLoca = s16(data, head + 50) != 0;
        var numGlyphs = u16(data, maxp + 4);
        var numberOfHMetrics = u16(data, hhea + 34);
        if (numGlyphs == 0 || numberOfHMetrics == 0 || numberOfHMetrics > numGlyphs) {
            throw new MediaException.Malformed("the font has no glyphs or inconsistent metrics");
        }
        if ((long) numberOfHMetrics * 4 > hmtxLength) {
            throw new MediaException.Malformed("the hmtx table is truncated");
        }
        if ((long) (numGlyphs + 1) * (longLoca ? 4 : 2) > locaLength) {
            throw new MediaException.Malformed("the loca table is truncated");
        }
        var underlinePosition = 0;
        var underlineThickness = 0;
        if (post >= 0 && postLength >= 12) {
            underlinePosition = s16(data, post + 8);
            underlineThickness = s16(data, post + 10);
        }
        if (cmapLength < 4) {
            throw new MediaException.Malformed("the cmap table is truncated");
        }
        var cmapEnd = cmap + cmapLength;
        var subtables = u16(data, cmap + 2);
        if (4L + 8L * subtables > cmapLength) {
            throw new MediaException.Malformed("the cmap table is truncated");
        }
        var best = -1;
        var bestFormat = 0;
        var bestRank = 0;
        var bestSymbol = false;
        for (var index = 0; index < subtables; index++) {
            var record = cmap + 4 + 8 * index;
            var platform = u16(data, record);
            var encoding = u16(data, record + 2);
            var offset = u32(data, record + 4);
            if (offset < 0 || offset > cmapLength - 2) {
                continue;
            }
            var subtable = cmap + (int) offset;
            var format = u16(data, subtable);
            var unicode = platform == 0 || platform == 3 && (encoding == 1 || encoding == 10);
            var isSymbol = platform == 3 && encoding == 0;
            if (!unicode && !isSymbol) {
                continue;
            }
            var rank = switch (format) {
                case 12 -> 3;
                case 4 -> 2;
                case 6 -> 1;
                default -> 0;
            };
            if (isSymbol && rank > 0) {
                rank = Math.min(rank, 1);
            }
            if (rank > bestRank) {
                best = subtable;
                bestFormat = format;
                bestRank = rank;
                bestSymbol = isSymbol;
            }
        }
        return new TrueTypeFont(data, unitsPerEm, s16(data, hhea + 4), s16(data, hhea + 6), s16(data, hhea + 8),
                s16(data, head + 38), s16(data, head + 42), underlinePosition, underlineThickness, numGlyphs, numberOfHMetrics, hmtx, hmtxLength, glyf, glyfLength,
                loca, longLoca, best, bestFormat, cmapEnd, bestSymbol);
    }

    /**
     * {@return the design units per em square, from 16 to 16384}
     */
    public int unitsPerEm() {
        return unitsPerEm;
    }

    /**
     * {@return the distance from the baseline to the top of the line, in design units}
     */
    public int ascender() {
        return ascender;
    }

    /**
     * {@return the distance from the baseline to the bottom of the line, in design units, usually
     * negative}
     */
    public int descender() {
        return descender;
    }

    /**
     * {@return the extra space between lines, in design units}
     */
    public int lineGap() {
        return lineGap;
    }

    /**
     * {@return the lowest point of every glyph, from the font header, in design units}
     */
    public int yMin() {
        return yMin;
    }

    /**
     * {@return the highest point of every glyph, from the font header, in design units}
     */
    public int yMax() {
        return yMax;
    }

    /**
     * {@return the position of the top of the underline relative to the baseline, in design units, or
     * 0 when the font gives none}
     */
    public int underlinePosition() {
        return underlinePosition;
    }

    /**
     * {@return the thickness of the underline, in design units, or 0 when the font gives none}
     */
    public int underlineThickness() {
        return underlineThickness;
    }

    /**
     * {@return the number of glyphs}
     */
    public int glyphCount() {
        return numGlyphs;
    }

    /**
     * Returns the glyph of a character.
     *
     * @param codePoint the Unicode code point
     * @return the glyph index, or 0 (the missing glyph) when the font does not map it
     */
    public int glyph(int codePoint) {
        if (cmap < 0) {
            return 0;
        }
        var glyph = lookup(codePoint);
        if (glyph == 0 && symbol && codePoint >= 0x20 && codePoint <= 0xFF) {
            glyph = lookup(0xF000 + codePoint);
        }
        return glyph < numGlyphs ? glyph : 0;
    }

    /**
     * Looks a code point up in the chosen {@code cmap} subtable.
     *
     * @param codePoint the code point
     * @return the glyph index, or 0
     */
    private int lookup(int codePoint) {
        return switch (cmapFormat) {
            case 4 -> format4(codePoint);
            case 6 -> format6(codePoint);
            case 12 -> format12(codePoint);
            default -> 0;
        };
    }

    /**
     * Looks a code point up in a segment mapping ({@code cmap} format 4).
     *
     * @param codePoint the code point
     * @return the glyph index, or 0
     */
    private int format4(int codePoint) {
        if (codePoint > 0xFFFF || cmap + 14 > cmapEnd) {
            return 0;
        }
        var segments = u16(data, cmap + 6) / 2;
        var ends = cmap + 14;
        var starts = ends + 2 * segments + 2;
        var deltas = starts + 2 * segments;
        var ranges = deltas + 2 * segments;
        if (ranges + 2 * segments > cmapEnd) {
            return 0;
        }
        var low = 0;
        var high = segments - 1;
        while (low <= high) {
            var middle = (low + high) >>> 1;
            var end = u16(data, ends + 2 * middle);
            if (end < codePoint) {
                low = middle + 1;
                continue;
            }
            var start = u16(data, starts + 2 * middle);
            if (start > codePoint) {
                if (middle == 0 || u16(data, ends + 2 * (middle - 1)) < codePoint) {
                    return 0;
                }
                high = middle - 1;
                continue;
            }
            var delta = s16(data, deltas + 2 * middle);
            var rangeOffset = u16(data, ranges + 2 * middle);
            if (rangeOffset == 0) {
                return (codePoint + delta) & 0xFFFF;
            }
            var address = ranges + 2 * middle + rangeOffset + 2 * (codePoint - start);
            if (address + 2 > cmapEnd) {
                return 0;
            }
            var glyph = u16(data, address);
            return glyph == 0 ? 0 : (glyph + delta) & 0xFFFF;
        }
        return 0;
    }

    /**
     * Looks a code point up in a trimmed table ({@code cmap} format 6).
     *
     * @param codePoint the code point
     * @return the glyph index, or 0
     */
    private int format6(int codePoint) {
        if (cmap + 10 > cmapEnd) {
            return 0;
        }
        var first = u16(data, cmap + 6);
        var count = u16(data, cmap + 8);
        var index = codePoint - first;
        if (index < 0 || index >= count || cmap + 10 + 2 * index + 2 > cmapEnd) {
            return 0;
        }
        return u16(data, cmap + 10 + 2 * index);
    }

    /**
     * Looks a code point up in a segmented coverage ({@code cmap} format 12).
     *
     * @param codePoint the code point
     * @return the glyph index, or 0
     */
    private int format12(int codePoint) {
        if (cmap + 16 > cmapEnd) {
            return 0;
        }
        var groups = u32(data, cmap + 12);
        if (groups < 0 || cmap + 16 + 12L * groups > cmapEnd) {
            return 0;
        }
        var low = 0L;
        var high = groups - 1L;
        while (low <= high) {
            var middle = (low + high) >>> 1;
            var group = cmap + 16 + 12 * (int) middle;
            var start = u32(data, group) & 0xFFFFFFFFL;
            var end = u32(data, group + 4) & 0xFFFFFFFFL;
            if (codePoint < start) {
                high = middle - 1;
            } else if (codePoint > end) {
                low = middle + 1;
            } else {
                var glyph = (u32(data, group + 8) & 0xFFFFFFFFL) + (codePoint - start);
                return glyph < numGlyphs ? (int) glyph : 0;
            }
        }
        return 0;
    }

    /**
     * Returns the advance width of a glyph.
     *
     * @param glyph the glyph index, below {@link #glyphCount()}
     * @return the advance in design units
     */
    public int advance(int glyph) {
        var index = Math.min(glyph, numberOfHMetrics - 1);
        return u16(data, hmtx + 4 * index);
    }

    /**
     * Returns the outline of a glyph, composite glyphs resolved.
     *
     * @param glyph the glyph index, below {@link #glyphCount()}
     * @return the outline in design units, y up; empty for a glyph without contours
     * @throws MediaException.Malformed if the glyph data is damaged
     */
    public GlyphOutline outline(int glyph) {
        var builder = new OutlineBuilder();
        append(glyph, builder, 1, 0, 0, 1, 0, 0, 0);
        return builder.build();
    }

    /**
     * Appends the transformed contours of a glyph.
     *
     * @param glyph the glyph index
     * @param builder the outline receiving the contours
     * @param a the x scale of x
     * @param b the y scale of x
     * @param c the x scale of y
     * @param d the y scale of y
     * @param dx the x offset
     * @param dy the y offset
     * @param depth the composite nesting depth
     */
    private void append(int glyph, OutlineBuilder builder, double a, double b, double c, double d, double dx, double dy,
                        int depth) {
        if (glyph < 0 || glyph >= numGlyphs) {
            throw new MediaException.Malformed("glyph " + glyph + " does not exist");
        }
        var start = locaOffset(glyph);
        var end = locaOffset(glyph + 1);
        if (end <= start) {
            return;
        }
        if (end > glyfLength || end - start < 10) {
            throw new MediaException.Malformed("glyph " + glyph + " lies outside the glyf table");
        }
        var offset = glyf + start;
        var limit = glyf + end;
        var contours = s16(data, offset);
        if (contours >= 0) {
            simple(offset, limit, contours, builder, a, b, c, d, dx, dy);
        } else {
            if (depth >= MAX_COMPOSITE_DEPTH) {
                throw new MediaException.Malformed("composite glyphs nest more than " + MAX_COMPOSITE_DEPTH + " deep");
            }
            composite(offset + 10, limit, builder, a, b, c, d, dx, dy, depth);
        }
    }

    /**
     * Appends the contours of a simple glyph.
     *
     * @param offset the start of the glyph
     * @param limit the end of the glyph
     * @param contours the number of contours
     * @param builder the outline receiving the contours
     * @param a the x scale of x
     * @param b the y scale of x
     * @param c the x scale of y
     * @param d the y scale of y
     * @param dx the x offset
     * @param dy the y offset
     */
    private void simple(int offset, int limit, int contours, OutlineBuilder builder, double a, double b, double c,
                        double d, double dx, double dy) {
        var position = offset + 10;
        check(position + 2L * contours + 2, limit);
        var ends = new int[contours];
        var points = 0;
        for (var index = 0; index < contours; index++) {
            ends[index] = u16(data, position + 2 * index);
            if (index > 0 && ends[index] <= ends[index - 1]) {
                throw new MediaException.Malformed("glyph contour ends are not increasing");
            }
            points = ends[index] + 1;
        }
        if (contours == 0) {
            return;
        }
        if (builder.points() + points > MAX_POINTS) {
            throw new MediaException.Malformed("the glyph has more than " + MAX_POINTS + " points");
        }
        position += 2 * contours;
        var instructions = u16(data, position);
        position += 2 + instructions;
        var flags = new byte[points];
        for (var index = 0; index < points; ) {
            check(position + 1L, limit);
            var flag = data[position++];
            flags[index++] = flag;
            if ((flag & 8) != 0) {
                check(position + 1L, limit);
                var repeat = data[position++] & 0xFF;
                if (index + repeat > points) {
                    throw new MediaException.Malformed("glyph flags repeat past the last point");
                }
                for (var count = 0; count < repeat; count++) {
                    flags[index++] = flag;
                }
            }
        }
        var xs = new int[points];
        var ys = new int[points];
        position = coordinates(flags, xs, position, limit, 2, 16);
        coordinates(flags, ys, position, limit, 4, 32);
        var first = 0;
        for (var contour = 0; contour < contours; contour++) {
            var last = ends[contour];
            builder.beginContour();
            for (var index = first; index <= last; index++) {
                var x = xs[index];
                var y = ys[index];
                builder.point(a * x + c * y + dx, b * x + d * y + dy, (flags[index] & 1) != 0);
            }
            builder.endContour();
            first = last + 1;
        }
    }

    /**
     * Reads the x or y coordinates of a simple glyph, as absolute values.
     *
     * @param flags the point flags
     * @param values receives the coordinates
     * @param position the start of the coordinates
     * @param limit the end of the glyph
     * @param shortBit the flag bit of a one-byte delta
     * @param sameBit the flag bit of a repeated value or a positive one-byte delta
     * @return the position after the coordinates
     */
    private int coordinates(byte[] flags, int[] values, int position, int limit, int shortBit, int sameBit) {
        var value = 0;
        for (var index = 0; index < flags.length; index++) {
            var flag = flags[index];
            if ((flag & shortBit) != 0) {
                check(position + 1L, limit);
                var delta = data[position++] & 0xFF;
                value += (flag & sameBit) != 0 ? delta : -delta;
            } else if ((flag & sameBit) == 0) {
                check(position + 2L, limit);
                value += s16(data, position);
                position += 2;
            }
            values[index] = value;
        }
        return position;
    }

    /**
     * Appends the components of a composite glyph.
     *
     * @param position the first component record
     * @param limit the end of the glyph
     * @param builder the outline receiving the contours
     * @param a the x scale of x
     * @param b the y scale of x
     * @param c the x scale of y
     * @param d the y scale of y
     * @param dx the x offset
     * @param dy the y offset
     * @param depth the composite nesting depth
     */
    private void composite(int position, int limit, OutlineBuilder builder, double a, double b, double c, double d,
                           double dx, double dy, int depth) {
        var base = builder.points();
        while (true) {
            check(position + 4L, limit);
            var flags = u16(data, position);
            var component = u16(data, position + 2);
            position += 4;
            double offsetX;
            double offsetY;
            var matched = (flags & 2) == 0;
            if ((flags & 1) != 0) {
                check(position + 4L, limit);
                offsetX = matched ? u16(data, position) : s16(data, position);
                offsetY = matched ? u16(data, position + 2) : s16(data, position + 2);
                position += 4;
            } else {
                check(position + 2L, limit);
                offsetX = matched ? data[position] & 0xFF : data[position];
                offsetY = matched ? data[position + 1] & 0xFF : data[position + 1];
                position += 2;
            }
            var parentPoint = (int) offsetX;
            var childPoint = (int) offsetY;
            if (matched) {
                offsetX = 0;
                offsetY = 0;
            }
            double sa = 1;
            double sb = 0;
            double sc = 0;
            double sd = 1;
            if ((flags & 8) != 0) {
                check(position + 2L, limit);
                sa = sd = f2dot14(position);
                position += 2;
            } else if ((flags & 64) != 0) {
                check(position + 4L, limit);
                sa = f2dot14(position);
                sd = f2dot14(position + 2);
                position += 4;
            } else if ((flags & 128) != 0) {
                check(position + 8L, limit);
                sa = f2dot14(position);
                sb = f2dot14(position + 2);
                sc = f2dot14(position + 4);
                sd = f2dot14(position + 6);
                position += 8;
            }
            var na = a * sa + c * sb;
            var nb = b * sa + d * sb;
            var nc = a * sc + c * sd;
            var nd = b * sc + d * sd;
            var nx = a * offsetX + c * offsetY + dx;
            var ny = b * offsetX + d * offsetY + dy;
            if (++builder.components > MAX_COMPONENTS) {
                throw new MediaException.Malformed("the composite glyph has more than " + MAX_COMPONENTS + " components");
            }
            var first = builder.points();
            append(component, builder, na, nb, nc, nd, nx, ny, depth + 1);
            if (matched) {
                builder.align(base + parentPoint, first + childPoint, first);
            }
            if ((flags & 32) == 0) {
                return;
            }
        }
    }

    /**
     * {@return a 2.14 fixed-point number}
     *
     * @param position its offset
     */
    private double f2dot14(int position) {
        return s16(data, position) / 16384.0;
    }

    /**
     * Returns the offset of a glyph in the {@code glyf} table.
     *
     * @param glyph the glyph index, up to the glyph count
     * @return the offset
     */
    private int locaOffset(int glyph) {
        if (longLoca) {
            var value = u32(data, loca + 4 * glyph);
            if (value < 0) {
                throw new MediaException.Malformed("a glyph offset is out of range");
            }
            return value;
        }
        return 2 * u16(data, loca + 2 * glyph);
    }

    /**
     * Checks that a read ends within the glyph.
     *
     * @param end the end of the read
     * @param limit the end of the glyph
     * @throws MediaException.Malformed if the read passes the end
     */
    private static void check(long end, int limit) {
        if (end > limit) {
            throw new MediaException.Malformed("glyph data is truncated");
        }
    }

    /**
     * {@return a table offset, checked to lie within the file}
     *
     * @param data the font file
     * @param value the offset as read
     */
    private static int offset(byte[] data, int value) {
        if (value < 0 || value > data.length) {
            throw new MediaException.Malformed("a font offset lies past the end of the file");
        }
        return value;
    }

    /**
     * {@return the 32-bit value of a four-character tag}
     *
     * @param name the tag
     */
    private static int tag(String name) {
        return name.charAt(0) << 24 | name.charAt(1) << 16 | name.charAt(2) << 8 | name.charAt(3);
    }

    /**
     * {@return an unsigned big-endian 16-bit value}
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @throws MediaException.Malformed if the value lies past the end
     */
    private static int u16(byte[] data, int offset) {
        if (offset < 0 || offset + 2 > data.length) {
            throw new MediaException.Malformed("the font is truncated");
        }
        return (data[offset] & 0xFF) << 8 | data[offset + 1] & 0xFF;
    }

    /**
     * {@return a signed big-endian 16-bit value}
     *
     * @param data the bytes
     * @param offset the index of the first byte
     */
    private static int s16(byte[] data, int offset) {
        return (short) u16(data, offset);
    }

    /**
     * {@return a big-endian 32-bit value}
     *
     * @param data the bytes
     * @param offset the index of the first byte
     * @throws MediaException.Malformed if the value lies past the end
     */
    private static int u32(byte[] data, int offset) {
        if (offset < 0 || offset + 4 > data.length) {
            throw new MediaException.Malformed("the font is truncated");
        }
        return (data[offset] & 0xFF) << 24 | (data[offset + 1] & 0xFF) << 16 | (data[offset + 2] & 0xFF) << 8
                | data[offset + 3] & 0xFF;
    }

    /**
     * Collects contours of quadratic segments.
     */
    private static final class OutlineBuilder {
        private double[] xs = new double[64];
        private double[] ys = new double[64];
        private boolean[] onCurve = new boolean[64];
        private int[] ends = new int[8];
        private int count;
        private int contours;
        private int contourStart;

        /**
         * The components visited so far.
         */
        private int components;

        int points() {
            return count;
        }

        void beginContour() {
            contourStart = count;
        }

        void point(double x, double y, boolean on) {
            if (count == xs.length) {
                xs = Arrays.copyOf(xs, 2 * count);
                ys = Arrays.copyOf(ys, 2 * count);
                onCurve = Arrays.copyOf(onCurve, 2 * count);
            }
            xs[count] = x;
            ys[count] = y;
            onCurve[count] = on;
            count++;
        }

        void align(int anchor, int point, int from) {
            if (anchor >= from || point >= count) {
                throw new MediaException.Malformed("a composite glyph matches a point that does not exist");
            }
            var shiftX = xs[anchor] - xs[point];
            var shiftY = ys[anchor] - ys[point];
            for (var index = from; index < count; index++) {
                xs[index] += shiftX;
                ys[index] += shiftY;
            }
        }

        void endContour() {
            if (count == contourStart) {
                return;
            }
            if (contours == ends.length) {
                ends = Arrays.copyOf(ends, 2 * contours);
            }
            ends[contours++] = count;
        }

        GlyphOutline build() {
            return new GlyphOutline(Arrays.copyOf(xs, count), Arrays.copyOf(ys, count), Arrays.copyOf(onCurve, count),
                    Arrays.copyOf(ends, contours));
        }
    }
}
