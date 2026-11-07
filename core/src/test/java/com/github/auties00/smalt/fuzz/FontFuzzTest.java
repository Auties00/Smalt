package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleFont;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.subtitle.SubtitleRenderer;
import com.github.auties00.smalt.subtitle.SubtitleStyle;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;

/**
 * Fuzz target of the TrueType reader of {@link SubtitleFont}: a font that reads draws a cue of styled text with
 * every placement row, so the outlines of the glyphs it maps are flattened and filled.
 */
class FontFuzzTest {
    @FuzzTest
    void font(byte[] data) {
        var run = Fuzz.start("font");
        run.value("read", () -> SubtitleFont.of(data)).ifPresent(font -> {
            var style = SubtitleStyle.builder(font).size(0.2).build();
            run.value("render", () -> SubtitleRenderer.render(SubtitleCue.of(0, 1,
                    "#T O <b>oB</b>\n<i>C H</i> <u>z</u>"), SubtitleMarkup.SUBRIP, style, 96, 64))
                    .ifPresent(bitmap -> run.output());
        });
    }
}
