package com.github.auties00.smalt.video.av1;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * The film grain an {@link Av1Encoder} signals over time: a list of entries, each giving the grain
 * of the frames presented in its time range, or no grain.
 *
 * <p>A frame takes the grain of the first entry whose range holds its presentation time; a frame
 * no entry covers has no grain. The text form ({@link #parse(String)}, {@link #format()}) is the
 * grain table format of the AOMedia reference encoder: a {@code filmgrn1} line, then one entry per
 * {@code E} line with its start and end time in units of 100 nanoseconds, whether it adds grain,
 * the random seed and whether parameters follow, and, when they do, the parameter lines
 * {@code p}, {@code sY}, {@code sCb}, {@code sCr}, {@code cY}, {@code cCb} and {@code cCr}:
 * {@snippet :
 * filmgrn1
 * E 0 9223372036854775807 1 7391 1
 *     p 3 7 0 11 0 1 128 192 256 128 192 256
 *     sY 6  0 20 40 28 94 30 144 28 196 26 255 24
 *     sCb 0
 *     sCr 0
 *     cY -2 4 -3 2 ...
 *     cCb 0 0 0 ...
 *     cCr 0 0 0 ...
 * }
 * An entry without parameters repeats those of the entry before it.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class Av1FilmGrainTable {
    /**
     * The first line of the text form.
     */
    private static final String MAGIC = "filmgrn1";

    /**
     * The nanoseconds of one time unit of the text form.
     */
    private static final long NANOS_PER_UNIT = 100;

    /**
     * One time range of a table.
     *
     * @param start the presentation time from which the entry applies, not negative
     * @param end the presentation time from which it no longer applies, after {@code start}
     * @param grain the grain of the frames presented in the range, or empty for none
     */
    public record Entry(Duration start, Duration end, Optional<Av1FilmGrain> grain) {
        /**
         * Validates the components.
         *
         * @throws IllegalArgumentException if {@code start} is negative or {@code end} is not after
         * it
         */
        public Entry {
            Objects.requireNonNull(start, "start");
            Objects.requireNonNull(end, "end");
            Objects.requireNonNull(grain, "grain");
            if (start.isNegative() || end.compareTo(start) <= 0) {
                throw new IllegalArgumentException("invalid entry range " + start + " to " + end);
            }
        }
    }

    private final List<Entry> entries;

    private Av1FilmGrainTable(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /**
     * Returns a table of entries.
     *
     * @param entries the entries, in lookup order
     * @return the table
     */
    public static Av1FilmGrainTable of(List<Entry> entries) {
        return new Av1FilmGrainTable(entries);
    }

    /**
     * Returns a table that gives every frame the same grain.
     *
     * @param grain the film grain of every frame
     * @return the table
     */
    public static Av1FilmGrainTable constant(Av1FilmGrain grain) {
        Objects.requireNonNull(grain, "grain");
        return new Av1FilmGrainTable(List.of(new Entry(Duration.ZERO, Duration.ofNanos(Long.MAX_VALUE),
                Optional.of(grain))));
    }

    /**
     * {@return the entries, in lookup order}
     */
    public List<Entry> entries() {
        return entries;
    }

    /**
     * Returns the grain of a frame presented at a time.
     *
     * @param time the presentation time
     * @return the grain of the first entry covering the time, or empty when there is none or the
     * entry adds no grain
     */
    public Optional<Av1FilmGrain> lookup(Duration time) {
        Objects.requireNonNull(time, "time");
        for (var entry : entries) {
            if (time.compareTo(entry.start()) >= 0 && time.compareTo(entry.end()) < 0) {
                return entry.grain();
            }
        }
        return Optional.empty();
    }

    /**
     * Parses a table from its text form.
     *
     * @param text the text, starting with {@code filmgrn1}
     * @return the table
     * @throws IllegalArgumentException if the text is not a well-formed table or a parameter is
     * out of range
     */
    public static Av1FilmGrainTable parse(String text) {
        var tokens = new Tokens(text);
        if (!MAGIC.equals(tokens.next())) {
            throw new IllegalArgumentException("not a film grain table: the first word is not " + MAGIC);
        }
        var out = new ArrayList<Entry>();
        Av1FilmGrain previous = null;
        while (tokens.hasNext()) {
            tokens.expect("E");
            var start = tokens.nextLong();
            var end = tokens.nextLong();
            var apply = tokens.nextInt() != 0;
            var seed = tokens.nextInt();
            var update = tokens.nextInt() != 0;
            Av1FilmGrain grain;
            if (update) {
                grain = parameters(tokens, seed);
            } else if (previous != null) {
                grain = previous.toBuilder().randomSeed(seed).build();
            } else if (apply) {
                throw new IllegalArgumentException("the first entry adding grain has no parameters");
            } else {
                grain = null;
            }
            if (grain != null) {
                previous = grain;
            }
            if (start < 0 || start > Long.MAX_VALUE / NANOS_PER_UNIT) {
                throw new IllegalArgumentException("invalid film grain table start time " + start);
            }
            out.add(new Entry(Duration.ofNanos(start * NANOS_PER_UNIT), Duration.ofNanos(saturatedNanos(end)),
                    apply ? Optional.of(grain) : Optional.empty()));
        }
        return new Av1FilmGrainTable(out);
    }

    /**
     * Converts a time of the text form to nanoseconds, saturating at the largest duration.
     *
     * @param units the time in 100-nanosecond units
     * @return the nanoseconds
     */
    private static long saturatedNanos(long units) {
        return units > Long.MAX_VALUE / NANOS_PER_UNIT ? Long.MAX_VALUE : units * NANOS_PER_UNIT;
    }

    /**
     * Reads the parameter lines of an entry.
     *
     * @param tokens the reader, after the entry line
     * @param seed the entry's random seed
     * @return the parameters
     */
    private static Av1FilmGrain parameters(Tokens tokens, int seed) {
        tokens.expect("p");
        var lag = tokens.nextInt();
        var arShift = tokens.nextInt();
        var grainScaleShift = tokens.nextInt();
        var scalingShift = tokens.nextInt();
        var fromLuma = tokens.nextInt() != 0;
        var overlap = tokens.nextInt() != 0;
        var cbMult = tokens.nextInt();
        var cbLumaMult = tokens.nextInt();
        var cbOffset = tokens.nextInt();
        var crMult = tokens.nextInt();
        var crLumaMult = tokens.nextInt();
        var crOffset = tokens.nextInt();
        tokens.expect("sY");
        var luma = points(tokens);
        tokens.expect("sCb");
        var cb = points(tokens);
        tokens.expect("sCr");
        var cr = points(tokens);
        if (lag < 0 || lag > 3) {
            throw new IllegalArgumentException("ar coefficient lag " + lag + " is not in [0, 3]");
        }
        var n = 2 * lag * (lag + 1);
        tokens.expect("cY");
        var cy = coefficients(tokens, n);
        tokens.expect("cCb");
        var ccb = coefficients(tokens, n + 1);
        tokens.expect("cCr");
        var ccr = coefficients(tokens, n + 1);
        return Av1FilmGrain.builder().randomSeed(seed).lumaPoints(luma).chromaPoints(cb, cr)
                .chromaScalingFromLuma(fromLuma).scalingShift(scalingShift).arCoefficients(lag, arShift, cy, ccb, ccr)
                .grainScaleShift(grainScaleShift).cbCombination(cbMult, cbLumaMult, cbOffset)
                .crCombination(crMult, crLumaMult, crOffset).overlap(overlap).build();
    }

    /**
     * Reads a count and that many scaling points.
     *
     * @param tokens the reader
     * @return the points
     */
    private static List<Av1FilmGrain.ScalingPoint> points(Tokens tokens) {
        var count = tokens.nextInt();
        if (count < 0 || count > 14) {
            throw new IllegalArgumentException("invalid scaling point count " + count);
        }
        var out = new ArrayList<Av1FilmGrain.ScalingPoint>(count);
        for (var i = 0; i < count; i++) {
            out.add(new Av1FilmGrain.ScalingPoint(tokens.nextInt(), tokens.nextInt()));
        }
        return out;
    }

    /**
     * Reads autoregressive coefficients.
     *
     * @param tokens the reader
     * @param count the number of coefficients
     * @return the coefficients
     */
    private static int[] coefficients(Tokens tokens, int count) {
        var out = new int[count];
        for (var i = 0; i < count; i++) {
            out[i] = tokens.nextInt();
        }
        return out;
    }

    /**
     * Returns the text form of the table; {@link #parse(String)} reads it back to an equal table
     * when every time is a whole number of 100-nanosecond units or the open end, a time of
     * {@link Long#MAX_VALUE} nanoseconds or more, and no grain
     * {@linkplain Av1FilmGrain#clipToRestrictedRange() clips to the restricted range}, which the
     * text form does not hold.
     *
     * @return the text
     */
    public String format() {
        var out = new StringBuilder(MAGIC).append('\n');
        for (var entry : entries) {
            var grain = entry.grain();
            out.append("E ").append(units(entry.start())).append(' ').append(units(entry.end())).append(' ')
                    .append(grain.isPresent() ? 1 : 0)
                    .append(' ').append(grain.map(Av1FilmGrain::randomSeed).orElse(0)).append(' ')
                    .append(grain.isPresent() ? 1 : 0).append('\n');
            if (grain.isEmpty()) {
                continue;
            }
            var g = grain.get();
            out.append("\tp ").append(g.arCoefficientLag()).append(' ').append(g.arCoefficientShift()).append(' ')
                    .append(g.grainScaleShift()).append(' ').append(g.scalingShift()).append(' ')
                    .append(g.chromaScalingFromLuma() ? 1 : 0).append(' ').append(g.overlap() ? 1 : 0).append(' ')
                    .append(g.cbMultiplier()).append(' ').append(g.cbLumaMultiplier()).append(' ')
                    .append(g.cbOffset()).append(' ').append(g.crMultiplier()).append(' ')
                    .append(g.crLumaMultiplier()).append(' ').append(g.crOffset()).append('\n');
            appendPoints(out.append("\tsY ").append(g.lumaPoints().size()).append(' '), g.lumaPoints());
            appendPoints(out.append("\n\tsCb ").append(g.cbPoints().size()), g.cbPoints());
            appendPoints(out.append("\n\tsCr ").append(g.crPoints().size()), g.crPoints());
            appendCoefficients(out.append("\n\tcY"), g.lumaArCoefficients());
            appendCoefficients(out.append("\n\tcCb"), g.cbArCoefficients());
            appendCoefficients(out.append("\n\tcCr"), g.crArCoefficients());
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * Converts a time to the units of the text form; the longest time a duration of nanoseconds
     * holds, and any longer, becomes the largest value, the open end of the text form.
     *
     * @param time the time, not negative
     * @return the time in 100-nanosecond units
     */
    private static long units(Duration time) {
        if (time.compareTo(Duration.ofNanos(Long.MAX_VALUE)) >= 0) {
            return Long.MAX_VALUE;
        }
        return time.toNanos() / NANOS_PER_UNIT;
    }

    /**
     * Appends scaling points, each preceded by a space.
     *
     * @param out the text
     * @param points the scaling points to append
     */
    private static void appendPoints(StringBuilder out, List<Av1FilmGrain.ScalingPoint> points) {
        for (var p : points) {
            out.append(' ').append(p.value()).append(' ').append(p.scaling());
        }
    }

    /**
     * Appends coefficients, each preceded by a space.
     *
     * @param out the text
     * @param coefficients the autoregressive coefficients to append
     */
    private static void appendCoefficients(StringBuilder out, int[] coefficients) {
        for (var c : coefficients) {
            out.append(' ').append(c);
        }
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Av1FilmGrainTable that && entries.equals(that.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return "Av1FilmGrainTable" + entries;
    }

    /**
     * Splits the text form into words.
     */
    private static final class Tokens {
        /**
         * The text.
         */
        private final String text;

        /**
         * The index of the next character.
         */
        private int position;

        /**
         * Creates a reader of a text.
         *
         * @param text the whole table text
         */
        Tokens(String text) {
            this.text = Objects.requireNonNull(text, "text");
        }

        /**
         * Skips white space and returns whether a word follows.
         *
         * @return {@code true} when a word follows
         */
        boolean hasNext() {
            while (position < text.length() && Character.isWhitespace(text.charAt(position))) {
                position++;
            }
            return position < text.length();
        }

        /**
         * Returns the next word.
         *
         * @return the word
         * @throws IllegalArgumentException at the end of the text
         */
        String next() {
            if (!hasNext()) {
                throw new IllegalArgumentException("the film grain table ends early");
            }
            var start = position;
            while (position < text.length() && !Character.isWhitespace(text.charAt(position))) {
                position++;
            }
            return text.substring(start, position);
        }

        /**
         * Reads a given word.
         *
         * @param word the word expected next
         * @throws IllegalArgumentException if another word follows
         */
        void expect(String word) {
            var found = next();
            if (!word.equals(found)) {
                throw new IllegalArgumentException("expected " + word + " in the film grain table, found " + found);
            }
        }

        /**
         * Reads a decimal integer.
         *
         * @return the value
         * @throws IllegalArgumentException if the word is not an integer
         */
        int nextInt() {
            var word = next();
            try {
                return Integer.parseInt(word);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("expected an integer in the film grain table, found " + word);
            }
        }

        /**
         * Reads a decimal long integer.
         *
         * @return the value
         * @throws IllegalArgumentException if the word is not an integer
         */
        long nextLong() {
            var word = next();
            try {
                return Long.parseLong(word);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("expected an integer in the film grain table, found " + word);
            }
        }
    }
}
