package com.github.auties00.smalt.transcode;

import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleFont;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.subtitle.SubtitleStyle;
import com.github.auties00.smalt.testkit.BuilderVariants;
import com.github.auties00.smalt.testkit.ValueContract;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the value semantics of the transcode option values that are not built by a builder of their own.
 */
@DisplayName("transcode option values")
class TranscodeValuesTest {
    @Test
    @DisplayName("fades compare direction, start, duration and curve")
    void fades() {
        ValueContract.check(() -> Fade.in(100, 2000), List.of(
                Fade.out(100, 2000),
                Fade.in(101, 2000),
                Fade.in(100, 2001),
                Fade.in(100, 2000).withCurve(FadeCurve.CUBIC)));
        assertTrue(Fade.out(500).endsWithOutput());
        assertEquals(OptionalLong.empty(), Fade.out(500).startMicros());
        assertTrue(Fade.out(500).toString().contains("at the end"));
        assertFalse(Fade.in(500).endsWithOutput());
        assertEquals(0.5, Fade.out(1000).gain(9500, 10_000), 1e-9);
        assertEquals(0.25, Fade.in(1000, 1000).gain(1250, -1), 1e-9);
        assertThrows(IllegalArgumentException.class, () -> Fade.out(1000).gain(5, -1));
        assertThrows(IllegalArgumentException.class, () -> Fade.in(0));
        assertThrows(IllegalArgumentException.class, () -> Fade.in(-1, 10));
    }

    @Test
    @DisplayName("subtitle burn-ins compare style, track, cues and markup")
    void burnIns() throws IOException {
        byte[] font;
        try (var stream = TranscodeValuesTest.class.getResourceAsStream("/com/github/auties00/smalt/subtitle/smalt-test.ttf")) {
            font = stream.readAllBytes();
        }
        var style = SubtitleStyle.of(SubtitleFont.of(font));
        var other = style.toBuilder().size(style.size() / 2).build();
        var cues = List.of(SubtitleCue.of(0, 1000, "a"));
        ValueContract.check(() -> SubtitleBurnIn.builder(style).trackId(2).build(), List.of(
                SubtitleBurnIn.builder(other).trackId(2).build(),
                SubtitleBurnIn.builder(style).trackId(3).build(),
                SubtitleBurnIn.builder(style).cues(cues, SubtitleMarkup.SUBRIP).build()));
        ValueContract.check(() -> SubtitleBurnIn.builder(style).cues(cues, SubtitleMarkup.SUBRIP).build(), List.of(
                SubtitleBurnIn.builder(style).cues(cues, SubtitleMarkup.WEBVTT).build(),
                SubtitleBurnIn.builder(style).cues(List.of(SubtitleCue.of(0, 1000, "b")),
                        SubtitleMarkup.SUBRIP).build()));
    }

    @Test
    @DisplayName("silence trims compare every setting")
    void silenceTrims() {
        ValueContract.check(SilenceTrim::defaults,
                BuilderVariants.of(() -> SilenceTrim.defaults().toBuilder(), SilenceTrim.Builder::build, 2));
    }
}
