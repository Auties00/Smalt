package com.github.auties00.smalt.internal.subtitle;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds a cue text as styled runs, with the placement and the speaker of the cue: the form every
 * subtitle markup converts through.
 *
 * <p>A run carries the bold, italic, underline and strikeout styles, a text and a background color, a
 * font family, a font size and a karaoke time. Font sizes are in pixels of a frame
 * {@value #REFERENCE_HEIGHT} pixels high, the script height of an ASS script without one. The
 * placement is a numpad alignment (1 to 9, 2 being bottom center) and an optional anchor position,
 * as fractions of the frame width and height. The karaoke time of a run is when its highlight
 * starts, in microseconds from the start of the cue.
 *
 * @param runs the runs, adjacent runs always differing in format
 * @param alignment the numpad alignment from 1 to 9, or {@code 0} when the cue does not set one
 * @param x the horizontal position of the anchor, from 0 to 1, or {@code NaN} when the cue has none
 * @param y the vertical position of the anchor, from 0 to 1, or {@code NaN} when the cue has none
 * @param voice the speaker, empty when none
 */
public record StyledText(List<Run> runs, int alignment, double x, double y, String voice) {
    /**
     * The bold style bit.
     */
    public static final int BOLD = 1;

    /**
     * The italic style bit.
     */
    public static final int ITALIC = 2;

    /**
     * The underline style bit.
     */
    public static final int UNDERLINE = 4;

    /**
     * The strikeout style bit.
     */
    public static final int STRIKEOUT = 8;

    /**
     * The color of a run that does not set one.
     */
    public static final long NO_COLOR = -1;

    /**
     * The karaoke time of a run outside karaoke.
     */
    public static final long NO_KARAOKE = -1;

    /**
     * The frame height font sizes are measured against.
     */
    public static final int REFERENCE_HEIGHT = 288;

    /**
     * The empty text.
     */
    public static final StyledText EMPTY = new StyledText(List.of(), 0, Double.NaN, Double.NaN, "");

    /**
     * Holds a run of text in one format.
     *
     * @param text the characters, unescaped, lines separated by line feeds
     * @param style the combination of {@link #BOLD}, {@link #ITALIC}, {@link #UNDERLINE} and {@link #STRIKEOUT}
     * @param color the text color as {@code 0xAARRGGBB}, or {@link #NO_COLOR}
     * @param background the background color as {@code 0xAARRGGBB}, or {@link #NO_COLOR}
     * @param font the font family, empty when the run does not set one
     * @param size the font size in reference pixels, or {@code NaN} when the run does not set one
     * @param karaoke the start of the highlight in microseconds from the cue start, or {@link #NO_KARAOKE}
     */
    public record Run(String text, int style, long color, long background, String font, double size, long karaoke) {
        /**
         * Returns a plain run.
         *
         * @param text the characters
         * @param style the style bits
         * @return the run without colors, font, size or karaoke time
         */
        public static Run of(String text, int style) {
            return new Run(text, style, NO_COLOR, NO_COLOR, "", Double.NaN, NO_KARAOKE);
        }

        /**
         * Returns whether another run has the same format.
         *
         * @param other the run to compare
         * @return {@code true} when everything but the text is equal
         */
        public boolean sameFormat(Run other) {
            return style == other.style && color == other.color && background == other.background
                    && font.equals(other.font) && Double.compare(size, other.size) == 0 && karaoke == other.karaoke;
        }

        /**
         * Returns a copy with other text.
         *
         * @param text the characters of the copy
         * @return the copy
         */
        public Run withText(String text) {
            return new Run(text, style, color, background, font, size, karaoke);
        }
    }

    /**
     * Returns the characters shown.
     *
     * @return the text of every run
     */
    public String plain() {
        var builder = new StringBuilder();
        for (var run : runs) {
            builder.append(run.text());
        }
        return builder.toString();
    }

    /**
     * Returns whether the anchor position is set.
     *
     * @return {@code true} when {@link #x()} and {@link #y()} are numbers
     */
    public boolean hasPosition() {
        return !Double.isNaN(x) && !Double.isNaN(y);
    }

    /**
     * Returns whether a run has a karaoke time.
     *
     * @return {@code true} when a run is part of a karaoke
     */
    public boolean hasKaraoke() {
        for (var run : runs) {
            if (run.karaoke() >= 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns a copy with other runs.
     *
     * @param runs the runs of the copy
     * @return the copy
     */
    public StyledText withRuns(List<Run> runs) {
        return new StyledText(runs, alignment, x, y, voice);
    }

    /**
     * Returns a copy with another placement.
     *
     * @param alignment the numpad alignment, or {@code 0}
     * @param x the horizontal anchor position, or {@code NaN}
     * @param y the vertical anchor position, or {@code NaN}
     * @return the copy
     */
    public StyledText withPlacement(int alignment, double x, double y) {
        return new StyledText(runs, alignment, x, y, voice);
    }

    /**
     * Returns a copy with another speaker.
     *
     * @param voice the speaker, empty for none
     * @return the copy
     */
    public StyledText withVoice(String voice) {
        return new StyledText(runs, alignment, x, y, voice);
    }

    /**
     * Collects characters into runs, merging adjacent runs of one format.
     *
     * <p>Instances are not thread-safe.
     */
    public static final class Builder {
        /**
         * The runs completed so far.
         */
        private final List<Run> runs = new ArrayList<>();

        /**
         * The characters of the current run.
         */
        private final StringBuilder text = new StringBuilder();

        /**
         * The format of the current run, its text empty.
         */
        private Run format = Run.of("", 0);

        /**
         * The numpad alignment, or {@code 0}.
         */
        private int alignment;

        /**
         * The anchor position, or {@code NaN}.
         */
        private double x = Double.NaN;

        /**
         * The anchor position, or {@code NaN}.
         */
        private double y = Double.NaN;

        /**
         * The speaker, empty when none.
         */
        private String voice = "";

        /**
         * {@return the format of the next characters, with empty text}
         */
        public Run format() {
            return format;
        }

        /**
         * Sets the format of the next characters.
         *
         * @param next the format; its text is ignored
         */
        public void format(Run next) {
            if (!next.sameFormat(format)) {
                flush();
                format = next.withText("");
            }
        }

        /**
         * Sets the style bits of the next characters, keeping the rest of the format.
         *
         * @param style the style bits
         */
        public void style(int style) {
            format(new Run("", style, format.color(), format.background(), format.font(), format.size(), format.karaoke()));
        }

        /**
         * Sets the karaoke time of the next characters, giving the characters before the first karaoke
         * time of the cue the time zero.
         *
         * @param karaoke the start of the highlight in microseconds from the cue start
         */
        public void karaoke(long karaoke) {
            if (format.karaoke() < 0) {
                flush();
                for (var index = 0; index < runs.size(); index++) {
                    var run = runs.get(index);
                    if (run.karaoke() < 0) {
                        runs.set(index, new Run(run.text(), run.style(), run.color(), run.background(), run.font(),
                                run.size(), 0));
                    }
                }
                merge();
            }
            format(new Run("", format.style(), format.color(), format.background(), format.font(), format.size(), karaoke));
        }

        /**
         * Appends one character.
         *
         * @param character the character to append
         */
        public void append(char character) {
            text.append(character);
        }

        /**
         * Appends characters.
         *
         * @param characters the characters to append
         */
        public void append(String characters) {
            text.append(characters);
        }

        /**
         * Appends one code point.
         *
         * @param codePoint the code point
         */
        public void appendCodePoint(int codePoint) {
            text.appendCodePoint(codePoint);
        }

        /**
         * Sets the alignment, keeping the first one set.
         *
         * @param alignment the numpad alignment from 1 to 9
         */
        public void alignment(int alignment) {
            if (this.alignment == 0 && alignment >= 1 && alignment <= 9) {
                this.alignment = alignment;
            }
        }

        /**
         * Sets the anchor position, keeping the first one set.
         *
         * @param x the horizontal position, as a fraction of the frame width
         * @param y the vertical position, as a fraction of the frame height
         */
        public void position(double x, double y) {
            if (Double.isNaN(this.x) && Double.isFinite(x) && Double.isFinite(y)) {
                this.x = x;
                this.y = y;
            }
        }

        /**
         * Sets the speaker, keeping the first one set.
         *
         * @param voice the speaker
         */
        public void voice(String voice) {
            if (this.voice.isEmpty()) {
                this.voice = voice.strip();
            }
        }

        /**
         * Completes the current run, when it has text.
         */
        private void flush() {
            if (text.isEmpty()) {
                return;
            }
            var run = format.withText(text.toString());
            text.setLength(0);
            if (!runs.isEmpty() && runs.getLast().sameFormat(run)) {
                var last = runs.removeLast();
                runs.add(last.withText(last.text() + run.text()));
            } else {
                runs.add(run);
            }
        }

        /**
         * Merges adjacent runs of one format.
         */
        private void merge() {
            for (var index = runs.size() - 1; index > 0; index--) {
                var previous = runs.get(index - 1);
                var run = runs.get(index);
                if (previous.sameFormat(run)) {
                    runs.set(index - 1, previous.withText(previous.text() + run.text()));
                    runs.remove(index);
                }
            }
        }

        /**
         * Completes the text.
         *
         * @return the styled text
         */
        public StyledText build() {
            flush();
            return new StyledText(List.copyOf(runs), alignment, x, y, voice);
        }
    }
}
