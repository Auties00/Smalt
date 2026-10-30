package com.github.auties00.smalt.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.io.MediaSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks how cues are drawn with the synthetic font of {@code tools/testdata/fonts/make-test-font.py}, whose
 * {@code #} is the em square: the size and position of the text for every placement and override, wrapping,
 * the outline, bold, italic and underline, and the inputs that draw nothing.
 */
@DisplayName("Subtitle rendering")
class SubtitleRendererTest {
    static SubtitleFont font() {
        try (var stream = SubtitleRendererTest.class.getResourceAsStream("smalt-test.ttf")) {
            return SubtitleFont.of(stream.readAllBytes());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** A 20-pixel em on a 400x200 picture, no outline, so the text block is exactly the glyphs plus padding. */
    static SubtitleStyle plain() {
        return SubtitleStyle.builder(font()).size(0.1).outline(0).build();
    }

    static SubtitleBitmap render(String text, SubtitleStyle style) {
        return SubtitleRenderer.render(SubtitleCue.of(0, 1000, text), SubtitleMarkup.SUBRIP, style, 400, 200)
                .orElseThrow();
    }

    /** The bounds of the opaque white pixels of a bitmap, in picture coordinates: {left, top, right, bottom}. */
    static int[] textBounds(SubtitleBitmap bitmap, int minimumAlpha) {
        var picture = bitmap.picture();
        int left = Integer.MAX_VALUE, top = Integer.MAX_VALUE, right = -1, bottom = -1;
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                if ((picture.pixels()[y * picture.stride() + 4 * x + 3] & 0xFF) >= minimumAlpha) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x + 1);
                    bottom = Math.max(bottom, y + 1);
                }
            }
        }
        return new int[]{left + bitmap.x(), top + bitmap.y(), right + bitmap.x(), bottom + bitmap.y()};
    }

    static int covered(RgbaPicture picture) {
        var count = 0;
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                if ((picture.pixels()[y * picture.stride() + 4 * x + 3] & 0xFF) > 127) {
                    count++;
                }
            }
        }
        return count;
    }

    @Test
    @DisplayName("draws em squares at the em size, centered, with the bottom margin below the line")
    void square() {
        var bitmap = render("###", plain());
        var bounds = textBounds(bitmap, 255);
        assertEquals(60, bounds[2] - bounds[0], "three 20-pixel squares side by side");
        assertEquals(20, bounds[3] - bounds[1]);
        assertEquals(400, bounds[0] + bounds[2], "centered horizontally");
        // the line is ascender 800 plus descender 200 high (20 pixels), its baseline 16 pixels below its top,
        // and the line box ends 10 pixels (5% of 200) above the bottom
        assertEquals(200 - 10 - 4, bounds[3]);
    }

    @ParameterizedTest(name = "alignment {0}")
    @CsvSource({"1, 20, 166", "2, 170, 166", "3, 320, 166", "4, 20, 86", "5, 170, 86", "6, 320, 86",
            "7, 20, 6", "8, 170, 6", "9, 320, 6"})
    @DisplayName("places the text at each keypad position within the margins")
    void alignments(int alignment, int left, int top) {
        var style = plain().toBuilder().alignment(alignment).build();
        var bounds = textBounds(render("###", style), 255);
        assertEquals(left, bounds[0], "left");
        assertEquals(top, bounds[1], "top");
    }

    @Test
    @DisplayName("follows the {\\an8} override of SubRip text and the align and line settings of WebVTT")
    void overrides() {
        var top = textBounds(render("{\\an8}###", plain()), 255);
        assertEquals(170, top[0]);
        assertEquals(6, top[1]);
        var cue = new SubtitleCue(0, 1000, "###", "", "align:start line:50%");
        var webVtt = SubtitleRenderer.render(cue, SubtitleMarkup.WEBVTT, plain(), 400, 200).orElseThrow();
        var bounds = textBounds(webVtt, 255);
        assertEquals(20, bounds[0]);
        // the line box starts at half the height; the square rises 4 pixels above its 16-pixel ascent
        assertEquals(100 + 16 - 20, bounds[1]);
        var numbered = new SubtitleCue(0, 1000, "###", "", "line:2");
        assertEquals(2 * 20 - 4, textBounds(SubtitleRenderer.render(numbered, SubtitleMarkup.WEBVTT, plain(), 400, 200)
                .orElseThrow(), 255)[1]);
        var fromBottom = new SubtitleCue(0, 1000, "###", "", "line:-1");
        assertEquals(200 - 4, textBounds(SubtitleRenderer.render(fromBottom, SubtitleMarkup.WEBVTT, plain(), 400,
                200).orElseThrow(), 255)[3]);
    }

    @Test
    @DisplayName("wraps at spaces to fit between the margins and breaks at line feeds")
    void wrapping() {
        // 360 pixels fit between the margins: 18 squares, so each "######" word (120 px) and space (10 px)
        // lets two words a line
        var bounds = textBounds(render("###### ###### ###### ######", plain()), 255);
        assertEquals(250, bounds[2] - bounds[0], "two words and a space");
        assertEquals(20 * 2, bounds[3] - bounds[1], "two lines of 20 pixels");
        var lines = textBounds(render("#\n#\n#", plain()), 255);
        assertEquals(20 * 3, lines[3] - lines[1]);
        var single = textBounds(render("#".repeat(30), plain()), 255);
        assertEquals(360, single[2] - single[0], "a word longer than the line breaks within it");
    }

    @Test
    @DisplayName("surrounds the text with the outline color")
    void outline() {
        var style = SubtitleStyle.builder(font()).size(0.1).outline(0.1).color(0xFFFFFFFF)
                .outlineColor(0xFF0000FF).build();
        var picture = render("#", style).picture();
        var row = picture.height() / 2;
        var white = -1;
        for (var x = 0; x < picture.width() && white < 0; x++) {
            if ((picture.pixels()[row * picture.stride() + 4 * x] & 0xFF) == 255) {
                white = x;
            }
        }
        assertTrue(white >= 3, "the square starts after its outline: " + white);
        // the outline is 2 pixels (0.1 em) wide: opaque blue left of the edge, transparent past it
        for (var x = white - 2; x < white; x++) {
            var index = row * picture.stride() + 4 * x;
            assertEquals(0, picture.pixels()[index] & 0xFF, "red at " + x);
            assertEquals(255, picture.pixels()[index + 2] & 0xFF, "blue at " + x);
            assertEquals(255, picture.pixels()[index + 3] & 0xFF, "alpha at " + x);
        }
        assertEquals(0, picture.pixels()[row * picture.stride() + 4 * (white - 3) + 3] & 0xFF);
        var inside = row * picture.stride() + 4 * (white + 10);
        assertEquals(255, picture.pixels()[inside] & 0xFF, "white text over the outline");
        assertEquals(255, picture.pixels()[inside + 1] & 0xFF);
        assertEquals(255, picture.pixels()[inside + 2] & 0xFF);
    }

    @Test
    @DisplayName("thickens bold text, slants italic text and underlines underlined text")
    void styles() {
        var regular = render("#", plain());
        var bold = render("<b>#</b>", plain());
        assertTrue(covered(bold.picture()) > covered(regular.picture()) + 40, "bold covers more");
        var italic = textBounds(render("<i>#</i>", plain()), 128);
        var upright = textBounds(regular, 128);
        assertTrue(italic[2] - italic[0] >= upright[2] - upright[0] + 3, "the slant widens the square");
        var underlined = textBounds(render("<u>#</u>", plain()), 128);
        assertTrue(underlined[3] >= upright[3] + 2, "the underline lies below the baseline");
        var ass = SubtitleRenderer.render(SubtitleCue.of(0, 1, "{\\b1}#"), SubtitleMarkup.ASS, plain(), 400, 200)
                .orElseThrow();
        assertEquals(covered(bold.picture()), covered(ass.picture()), "the ASS bold override draws the same");
    }

    @Test
    @DisplayName("draws nothing for empty and blank text, and the same bitmap every time")
    void blank() {
        assertTrue(SubtitleRenderer.render(SubtitleCue.of(0, 1, ""), SubtitleMarkup.SUBRIP, plain(), 64, 48).isEmpty());
        assertTrue(SubtitleRenderer.render(SubtitleCue.of(0, 1, "   \n "), SubtitleMarkup.SUBRIP, plain(), 64, 48)
                .isEmpty());
        assertTrue(SubtitleRenderer.render(SubtitleCue.of(0, 1, "<i></i>"), SubtitleMarkup.SUBRIP, plain(), 64, 48)
                .isEmpty());
        var first = render("#T O<b>o</b>", plain());
        var second = render("#T O<b>o</b>", plain());
        assertArrayEquals(first.picture().pixels(), second.picture().pixels());
        assertEquals(first.x(), second.x());
        assertFalse(Arrays.equals(first.picture().pixels(), new byte[first.picture().pixels().length]));
        assertThrows(IllegalArgumentException.class, () -> SubtitleRenderer.render(SubtitleCue.of(0, 1, "#"),
                SubtitleMarkup.SUBRIP, plain(), 0, 48));
    }

    @Test
    @DisplayName("reads fonts from sources and validates the style")
    void fontsAndStyles() throws IOException {
        byte[] data;
        try (var stream = SubtitleRendererTest.class.getResourceAsStream("smalt-test.ttf")) {
            data = stream.readAllBytes();
        }
        var font = SubtitleFont.read(MediaSource.of(data));
        assertEquals(9, font.glyphCount());
        assertTrue(font.canDisplay('#'));
        assertFalse(font.canDisplay(0xE9));
        assertThrows(MediaException.Malformed.class, () -> SubtitleFont.of(new byte[12]));
        assertThrows(IllegalArgumentException.class, () -> SubtitleStyle.builder(font).size(0).build());
        assertThrows(IllegalArgumentException.class, () -> SubtitleStyle.builder(font).alignment(10).build());
        assertThrows(IllegalArgumentException.class, () -> SubtitleStyle.builder(font).margins(0.5, 0).build());
        var style = SubtitleStyle.builder(font).size(0.2).color(0x80FF0000).build();
        assertEquals(style, style.toBuilder().build());
    }
}
