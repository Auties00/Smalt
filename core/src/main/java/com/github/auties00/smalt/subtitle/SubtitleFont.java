package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.font.TrueTypeFont;
import com.github.auties00.smalt.internal.limits.Guards;
import com.github.auties00.smalt.io.MediaSource;

import java.util.Arrays;
import java.util.Objects;

/**
 * Holds a font that {@link SubtitleRenderer} draws cue text with.
 *
 * <p>The font is a TrueType or OpenType file with TrueType ({@code glyf}) outlines, or the first font
 * of a TrueType collection; fonts with PostScript ({@code CFF}) outlines are not supported. Characters
 * the font does not map are drawn with its missing glyph. Hinting is not applied, and kerning and
 * ligatures are not formed.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class SubtitleFont {
    /**
     * The largest font file read, in bytes.
     */
    private static final long MAX_SIZE = 64L << 20;

    private final TrueTypeFont font;

    private SubtitleFont(TrueTypeFont font) {
        this.font = font;
    }

    /**
     * Reads a font from its file contents.
     *
     * @param data the font file; copied
     * @return the font
     * @throws MediaException.Malformed if the data is not a TrueType font or its tables are damaged
     * @throws MediaException.Unsupported if the font has PostScript ({@code CFF}) outlines
     */
    public static SubtitleFont of(byte[] data) {
        Objects.requireNonNull(data, "data");
        var copy = Arrays.copyOf(data, data.length);
        return new SubtitleFont(Guards.malformed("font", () -> TrueTypeFont.read(copy)));
    }

    /**
     * Reads a font from a source, which is not closed.
     *
     * @param source the font file
     * @return the font
     * @throws MediaException.Malformed if the source is not a TrueType font or its tables are damaged
     * @throws MediaException.Unsupported if the font has PostScript ({@code CFF}) outlines
     * @throws MediaException.LimitExceeded if the source is longer than 64 MiB or of unknown length
     * @throws MediaException.Io if reading the source fails
     */
    public static SubtitleFont read(MediaSource source) {
        Objects.requireNonNull(source, "source");
        var size = source.size();
        if (size < 0 || size > MAX_SIZE) {
            throw new MediaException.LimitExceeded("a font must have a known length of at most " + MAX_SIZE + " bytes");
        }
        var data = new byte[(int) size];
        source.readFully(0, data, 0, data.length);
        return new SubtitleFont(Guards.malformed("font", () -> TrueTypeFont.read(data)));
    }

    /**
     * {@return the number of glyphs of the font}
     */
    public int glyphCount() {
        return font.glyphCount();
    }

    /**
     * Returns whether the font has a glyph for a character.
     *
     * @param codePoint the Unicode code point
     * @return {@code true} when the font maps the character to a glyph other than the missing glyph
     */
    public boolean canDisplay(int codePoint) {
        return font.glyph(codePoint) != 0;
    }

    /**
     * {@return the parsed font}
     */
    TrueTypeFont font() {
        return font;
    }
}
