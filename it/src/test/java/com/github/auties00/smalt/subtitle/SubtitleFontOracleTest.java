package com.github.auties00.smalt.subtitle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;

import java.awt.Font;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compares Smalt's TrueType reader and rasterizer with the JDK's font rasterizer (java.awt, used as a black-box
 * oracle): the advance of each glyph and the ink each glyph covers at a size, for the synthetic test font and,
 * where the system has it (the Linux runners do), DejaVu Sans.
 */
@DisplayName("Subtitle fonts against the JDK rasterizer")
class SubtitleFontOracleTest {
    static final String TEXT = "#TOoBCHeloxAgjQ&@%";

    static byte[] testFont() {
        try (var stream = SubtitleFontOracleTest.class.getResourceAsStream(
                "/com/github/auties00/smalt/subtitle/smalt-test.ttf")) {
            return stream.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static List<byte[]> fonts() {
        var fonts = new ArrayList<byte[]>();
        fonts.add(testFont());
        for (var candidate : List.of("/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "/usr/share/fonts/TTF/DejaVuSans.ttf", "/usr/share/fonts/dejavu/DejaVuSans.ttf")) {
            var path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                try {
                    fonts.add(Files.readAllBytes(path));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
                break;
            }
        }
        return fonts;
    }

    /** The ink of one character drawn by AWT at an em size, in pixels of full coverage. */
    static double awtInk(Font font, int codePoint, int em) {
        var size = em * 4;
        var image = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_GRAY);
        var graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        var shape = font.deriveFont((float) em).createGlyphVector(graphics.getFontRenderContext(),
                Character.toString(codePoint)).getOutline(em, 2.5f * em);
        graphics.fill(shape);
        graphics.dispose();
        var sum = 0L;
        var raster = image.getRaster();
        for (var y = 0; y < size; y++) {
            for (var x = 0; x < size; x++) {
                sum += raster.getSample(x, y, 0);
            }
        }
        return sum / 255.0;
    }

    /** The ink of one character drawn by Smalt at an em size. */
    static double smaltInk(SubtitleFont font, int codePoint, int em) {
        var height = em * 10;
        var style = SubtitleStyle.builder(font).size(0.1).outline(0).build();
        var bitmap = SubtitleRenderer.render(SubtitleCue.of(0, 1, Character.toString(codePoint)), SubtitleMarkup.SUBRIP,
                style, height, height);
        if (bitmap.isEmpty()) {
            return 0;
        }
        var picture = bitmap.get().picture();
        var sum = 0L;
        for (var y = 0; y < picture.height(); y++) {
            for (var x = 0; x < picture.width(); x++) {
                sum += picture.pixels()[y * picture.stride() + 4 * x + 3] & 0xFF;
            }
        }
        return sum / 255.0;
    }

    @TestFactory
    @DisplayName("matches the advances and the ink of every glyph")
    Stream<DynamicTest> glyphs() {
        var tests = new ArrayList<DynamicTest>();
        var fonts = fonts();
        for (var index = 0; index < fonts.size(); index++) {
            var data = fonts.get(index);
            var name = index == 0 ? "synthetic" : "DejaVu Sans";
            tests.add(DynamicTest.dynamicTest(name, () -> {
                var awt = Font.createFont(Font.TRUETYPE_FONT, new ByteArrayInputStream(data));
                var smalt = SubtitleFont.of(data);
                var context = new FontRenderContext(null, true, true);
                for (var offset = 0; offset < TEXT.length(); offset++) {
                    var character = TEXT.codePointAt(offset);
                    if (!smalt.canDisplay(character)) {
                        continue;
                    }
                    var em = 64;
                    var expected = awtInk(awt, character, em);
                    var actual = smaltInk(smalt, character, em);
                    assertEquals(expected, actual, Math.max(4, expected * 0.03), name + " '" + (char) character + "' ink");
                    var advance = awt.deriveFont(1000f).createGlyphVector(context, Character.toString(character))
                            .getGlyphMetrics(0).getAdvance();
                    assertEquals(advance, advanceOf(smalt, character), 1.0, name + " '" + (char) character + "' advance");
                }
            }));
        }
        return tests.stream();
    }

    /** The advance of a character at a 1000 pixel em, from the width of two of them minus one. */
    static double advanceOf(SubtitleFont font, int character) {
        var style = SubtitleStyle.builder(font).size(0.1).outline(0).margins(0, 0.05).build();
        var one = SubtitleRenderer.render(SubtitleCue.of(0, 1, "#" + Character.toString(character) + "#"),
                SubtitleMarkup.SUBRIP, style, 1_000_000, 10_000).orElseThrow();
        var none = SubtitleRenderer.render(SubtitleCue.of(0, 1, "##"), SubtitleMarkup.SUBRIP, style, 1_000_000, 10_000)
                .orElseThrow();
        // both blocks share their padding, so the widths differ by the advance alone (rounded up to a pixel)
        return one.picture().width() - none.picture().width();
    }
}
