package com.github.auties00.smalt.audio.flac;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Describes a window function (apodization) a {@link FlacEncoder} applies to a block before its
 * linear-prediction analysis.
 *
 * <p>The encoder computes one set of predictor coefficients per window and keeps the predictor
 * whose residual codes smallest, so more windows compress better and encode slower. The functions
 * and their parameters are libFLAC's: the plain windows ({@link #hann()}, {@link #welch()}, ...),
 * {@link #tukey(double)}, the multi-window families {@link #partialTukey(int, double, double)} and
 * {@link #punchoutTukey(int, double, double)}, which each analyze {@code parts} windows over
 * sections of the block, and {@link #subdivideTukey(int, double)}, which analyzes the whole block
 * and its halves, thirds and so on up to {@code parts}, reusing one autocorrelation for the
 * complement of each section. {@link #parse(String)} reads libFLAC's {@code --apodization}
 * syntax and {@link #toString()} writes it.
 *
 * <p>Instances are immutable and thread-safe.
 */
public final class FlacApodization {
    /**
     * The window functions.
     */
    enum Kind {
        /**
         * The Bartlett (triangular, zero at the ends) window.
         */
        BARTLETT("bartlett"),
        /**
         * The Bartlett-Hann window.
         */
        BARTLETT_HANN("bartlett_hann"),
        /**
         * The Blackman window.
         */
        BLACKMAN("blackman"),
        /**
         * The four-term Blackman-Harris window with 92 dB side lobes.
         */
        BLACKMAN_HARRIS_4TERM_92DB("blackman_harris_4term_92db"),
        /**
         * The Connes window.
         */
        CONNES("connes"),
        /**
         * The flat-top window.
         */
        FLATTOP("flattop"),
        /**
         * The Gaussian window.
         */
        GAUSS("gauss"),
        /**
         * The Hamming window.
         */
        HAMMING("hamming"),
        /**
         * The Hann window.
         */
        HANN("hann"),
        /**
         * The Kaiser-Bessel window.
         */
        KAISER_BESSEL("kaiser_bessel"),
        /**
         * The Nuttall window.
         */
        NUTTALL("nuttall"),
        /**
         * The rectangular window.
         */
        RECTANGLE("rectangle"),
        /**
         * The triangular window, nonzero at the ends.
         */
        TRIANGLE("triangle"),
        /**
         * The Tukey (tapered cosine) window.
         */
        TUKEY("tukey"),
        /**
         * Tukey windows over overlapping sections of the block.
         */
        PARTIAL_TUKEY("partial_tukey"),
        /**
         * Tukey windows with overlapping sections of the block punched out.
         */
        PUNCHOUT_TUKEY("punchout_tukey"),
        /**
         * A Tukey window over the block and over every section of every subdivision.
         */
        SUBDIVIDE_TUKEY("subdivide_tukey"),
        /**
         * The Welch window.
         */
        WELCH("welch");

        /**
         * The name in libFLAC's specification syntax.
         */
        private final String specName;

        /**
         * Creates a kind.
         *
         * @param specName the name in libFLAC's specification syntax
         */
        Kind(String specName) {
            this.specName = specName;
        }
    }

    /**
     * The window functions by their name in libFLAC's specification syntax.
     */
    private static final Map<String, Kind> KINDS = Arrays.stream(Kind.values())
            .collect(Collectors.toUnmodifiableMap(kind -> kind.specName, Function.identity()));

    /**
     * The largest number of sections of a multi-window function.
     */
    private static final int MAX_PARTS = 32;

    /**
     * The window function.
     */
    private final Kind kind;

    /**
     * The tapered fraction of a Tukey family window, or the deviation of a Gaussian window.
     */
    private final double parameter;

    /**
     * The number of sections of a multi-window function, otherwise one.
     */
    private final int parts;

    /**
     * The overlap between sections of a partial or punch-out Tukey function, otherwise zero.
     */
    private final double overlap;

    /**
     * Creates a window description.
     *
     * @param kind the window function
     * @param parameter the tapered fraction or deviation
     * @param parts the number of sections
     * @param overlap the section overlap
     */
    private FlacApodization(Kind kind, double parameter, int parts, double overlap) {
        this.kind = kind;
        this.parameter = parameter;
        this.parts = parts;
        this.overlap = overlap;
    }

    /**
     * Returns the Bartlett window.
     *
     * @return the window
     */
    public static FlacApodization bartlett() {
        return new FlacApodization(Kind.BARTLETT, 0, 1, 0);
    }

    /**
     * Returns the Bartlett-Hann window.
     *
     * @return the window
     */
    public static FlacApodization bartlettHann() {
        return new FlacApodization(Kind.BARTLETT_HANN, 0, 1, 0);
    }

    /**
     * Returns the Blackman window.
     *
     * @return the window
     */
    public static FlacApodization blackman() {
        return new FlacApodization(Kind.BLACKMAN, 0, 1, 0);
    }

    /**
     * Returns the four-term Blackman-Harris window with 92 dB side lobes.
     *
     * @return the window
     */
    public static FlacApodization blackmanHarris() {
        return new FlacApodization(Kind.BLACKMAN_HARRIS_4TERM_92DB, 0, 1, 0);
    }

    /**
     * Returns the Connes window.
     *
     * @return the window
     */
    public static FlacApodization connes() {
        return new FlacApodization(Kind.CONNES, 0, 1, 0);
    }

    /**
     * Returns the flat-top window.
     *
     * @return the window
     */
    public static FlacApodization flattop() {
        return new FlacApodization(Kind.FLATTOP, 0, 1, 0);
    }

    /**
     * Returns a Gaussian window.
     *
     * @param deviation the standard deviation relative to half the block, in {@code (0, 0.5]}
     * @return the window
     * @throws IllegalArgumentException if {@code deviation} is out of range
     */
    public static FlacApodization gauss(double deviation) {
        if (!(deviation > 0 && deviation <= 0.5)) {
            throw new IllegalArgumentException("Gaussian deviation " + deviation + " is not in (0, 0.5]");
        }
        return new FlacApodization(Kind.GAUSS, deviation, 1, 0);
    }

    /**
     * Returns the Hamming window.
     *
     * @return the window
     */
    public static FlacApodization hamming() {
        return new FlacApodization(Kind.HAMMING, 0, 1, 0);
    }

    /**
     * Returns the Hann window.
     *
     * @return the window
     */
    public static FlacApodization hann() {
        return new FlacApodization(Kind.HANN, 0, 1, 0);
    }

    /**
     * Returns the Kaiser-Bessel window.
     *
     * @return the window
     */
    public static FlacApodization kaiserBessel() {
        return new FlacApodization(Kind.KAISER_BESSEL, 0, 1, 0);
    }

    /**
     * Returns the Nuttall window.
     *
     * @return the window
     */
    public static FlacApodization nuttall() {
        return new FlacApodization(Kind.NUTTALL, 0, 1, 0);
    }

    /**
     * Returns the rectangular window, which leaves the block unchanged.
     *
     * @return the window
     */
    public static FlacApodization rectangle() {
        return new FlacApodization(Kind.RECTANGLE, 0, 1, 0);
    }

    /**
     * Returns the triangular window.
     *
     * @return the window
     */
    public static FlacApodization triangle() {
        return new FlacApodization(Kind.TRIANGLE, 0, 1, 0);
    }

    /**
     * Returns the Welch window.
     *
     * @return the window
     */
    public static FlacApodization welch() {
        return new FlacApodization(Kind.WELCH, 0, 1, 0);
    }

    /**
     * Returns a Tukey window: a rectangle whose ends taper with half a Hann window.
     *
     * @param taper the tapered fraction of the block, in {@code [0, 1]}: zero is the rectangle,
     * one the Hann window
     * @return the window
     * @throws IllegalArgumentException if {@code taper} is out of range
     */
    public static FlacApodization tukey(double taper) {
        checkTaper(taper);
        return new FlacApodization(Kind.TUKEY, taper, 1, 0);
    }

    /**
     * Returns Tukey windows over {@code parts} overlapping sections of the block, zero elsewhere.
     *
     * @param parts the number of sections, from 1 to {@value #MAX_PARTS}; one gives a single
     * {@link #tukey(double)} window
     * @param overlap the fraction by which consecutive sections overlap, in {@code [0, 0.99]}
     * @param taper the tapered fraction of each section, in {@code [0, 1]}
     * @return the windows
     * @throws IllegalArgumentException if a parameter is out of range
     */
    public static FlacApodization partialTukey(int parts, double overlap, double taper) {
        checkParts(parts);
        checkOverlap(overlap);
        checkTaper(taper);
        return new FlacApodization(Kind.PARTIAL_TUKEY, taper, parts, overlap);
    }

    /**
     * Returns Tukey windows over the block with each of {@code parts} overlapping sections zeroed.
     *
     * @param parts the number of sections, from 1 to {@value #MAX_PARTS}; one gives a single
     * {@link #tukey(double)} window
     * @param overlap the fraction by which consecutive sections overlap, in {@code [0, 0.99]}
     * @param taper the tapered fraction of each remaining piece, in {@code [0, 1]}
     * @return the windows
     * @throws IllegalArgumentException if a parameter is out of range
     */
    public static FlacApodization punchoutTukey(int parts, double overlap, double taper) {
        checkParts(parts);
        checkOverlap(overlap);
        checkTaper(taper);
        return new FlacApodization(Kind.PUNCHOUT_TUKEY, taper, parts, overlap);
    }

    /**
     * Returns a Tukey window over the block and over every section when the block is split into
     * two, three and up to {@code parts} equal sections, plus the complement of each section of
     * three or more.
     *
     * @param parts the deepest subdivision, from 2 to {@value #MAX_PARTS}
     * @param taper the tapered fraction of the smallest section, in {@code [0, 1]}
     * @return the windows
     * @throws IllegalArgumentException if a parameter is out of range
     */
    public static FlacApodization subdivideTukey(int parts, double taper) {
        if (parts < 2 || parts > MAX_PARTS) {
            throw new IllegalArgumentException("subdivision " + parts + " is not in [2, " + MAX_PARTS + "]");
        }
        checkTaper(taper);
        return new FlacApodization(Kind.SUBDIVIDE_TUKEY, taper, parts, 0);
    }

    /**
     * Parses libFLAC's apodization specification: window functions separated by semicolons.
     *
     * <p>The names are those of libFLAC: {@code bartlett}, {@code bartlett_hann}, {@code blackman},
     * {@code blackman_harris_4term_92db}, {@code connes}, {@code flattop}, {@code gauss(STDDEV)},
     * {@code hamming}, {@code hann}, {@code kaiser_bessel}, {@code nuttall}, {@code rectangle},
     * {@code triangle}, {@code tukey(P)}, {@code partial_tukey(N[/OV[/P]])} (overlap 0.1 and taper
     * 0.2 by default), {@code punchout_tukey(N[/OV[/P]])} (overlap 0.2 and taper 0.2 by default),
     * {@code subdivide_tukey(N[/P])} (taper 0.5 by default) and {@code welch}.
     * {@snippet :
     * List<FlacApodization> windows = FlacApodization.parse("tukey(5e-1);partial_tukey(2);punchout_tukey(3)");
     * }
     *
     * @param specification the window functions, separated by semicolons
     * @return the windows, in order
     * @throws IllegalArgumentException if a window name is unknown or a parameter is invalid
     */
    public static List<FlacApodization> parse(String specification) {
        Objects.requireNonNull(specification, "specification");
        var result = new ArrayList<FlacApodization>();
        for (var item : specification.split(";")) {
            var text = item.strip();
            if (text.isEmpty()) {
                continue;
            }
            var open = text.indexOf('(');
            var name = open < 0 ? text : text.substring(0, open);
            String[] arguments = {};
            if (open >= 0) {
                if (!text.endsWith(")")) {
                    throw new IllegalArgumentException("unterminated apodization arguments: " + text);
                }
                arguments = text.substring(open + 1, text.length() - 1).split("/");
            }
            var kind = KINDS.get(name);
            if (kind == null) {
                throw new IllegalArgumentException("unknown apodization " + name);
            }
            result.add(switch (kind) {
                case BARTLETT -> bartlett();
                case BARTLETT_HANN -> bartlettHann();
                case BLACKMAN -> blackman();
                case BLACKMAN_HARRIS_4TERM_92DB -> blackmanHarris();
                case CONNES -> connes();
                case FLATTOP -> flattop();
                case GAUSS -> gauss(number(arguments, 0, Double.NaN));
                case HAMMING -> hamming();
                case HANN -> hann();
                case KAISER_BESSEL -> kaiserBessel();
                case NUTTALL -> nuttall();
                case RECTANGLE -> rectangle();
                case TRIANGLE -> triangle();
                case TUKEY -> tukey(number(arguments, 0, Double.NaN));
                case PARTIAL_TUKEY -> partialTukey((int) number(arguments, 0, Double.NaN),
                        number(arguments, 1, 0.1), number(arguments, 2, 0.2));
                case PUNCHOUT_TUKEY -> punchoutTukey((int) number(arguments, 0, Double.NaN),
                        number(arguments, 1, 0.2), number(arguments, 2, 0.2));
                case SUBDIVIDE_TUKEY -> subdivideTukey((int) number(arguments, 0, Double.NaN), number(arguments, 1, 0.5));
                case WELCH -> welch();
            });
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("empty apodization specification");
        }
        return List.copyOf(result);
    }

    /**
     * Reads a numeric argument of a specification.
     *
     * @param arguments the arguments between the parentheses, split at slashes
     * @param index the argument's position
     * @param fallback the value of an absent argument; NaN when the argument is required
     * @return the value
     * @throws IllegalArgumentException if the argument is required and absent, or not a number
     */
    private static double number(String[] arguments, int index, double fallback) {
        if (index >= arguments.length || arguments[index].isBlank()) {
            if (Double.isNaN(fallback)) {
                throw new IllegalArgumentException("missing apodization argument " + (index + 1));
            }
            return fallback;
        }
        try {
            return Double.parseDouble(arguments[index].strip());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("invalid apodization argument " + arguments[index], exception);
        }
    }

    /**
     * Checks a tapered fraction.
     *
     * @param taper the fraction
     * @throws IllegalArgumentException if it is not in {@code [0, 1]}
     */
    private static void checkTaper(double taper) {
        if (!(taper >= 0 && taper <= 1)) {
            throw new IllegalArgumentException("Tukey taper " + taper + " is not in [0, 1]");
        }
    }

    /**
     * Checks a section overlap.
     *
     * @param overlap the fraction by which consecutive sections overlap
     * @throws IllegalArgumentException if it is not in {@code [0, 0.99]}
     */
    private static void checkOverlap(double overlap) {
        if (!(overlap >= 0 && overlap <= 0.99)) {
            throw new IllegalArgumentException("section overlap " + overlap + " is not in [0, 0.99]");
        }
    }

    /**
     * Checks a section count.
     *
     * @param parts the count
     * @throws IllegalArgumentException if it is not in {@code [1, MAX_PARTS]}
     */
    private static void checkParts(int parts) {
        if (parts < 1 || parts > MAX_PARTS) {
            throw new IllegalArgumentException("section count " + parts + " is not in [1, " + MAX_PARTS + "]");
        }
    }

    /**
     * Returns the window function.
     *
     * @return the kind
     */
    Kind kind() {
        return kind;
    }

    /**
     * Returns the tapered fraction of a Tukey family window or the deviation of a Gaussian one.
     *
     * @return the parameter, zero for the other windows
     */
    double parameter() {
        return parameter;
    }

    /**
     * Returns the number of sections of a multi-window function.
     *
     * @return the count, one for single windows
     */
    int parts() {
        return parts;
    }

    /**
     * Returns the overlap between sections of a partial or punch-out Tukey function.
     *
     * @return the overlap, zero otherwise
     */
    double overlap() {
        return overlap;
    }

    @Override
    public boolean equals(Object object) {
        return object instanceof FlacApodization that && kind == that.kind
                && Double.compare(parameter, that.parameter) == 0 && parts == that.parts
                && Double.compare(overlap, that.overlap) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(kind, parameter, parts, overlap);
    }

    /**
     * Returns this window in libFLAC's specification syntax, which {@link #parse(String)} reads.
     *
     * @return the specification, such as {@code tukey(0.5)} or {@code partial_tukey(2/0.1/0.2)}
     */
    @Override
    public String toString() {
        return switch (kind) {
            case GAUSS, TUKEY -> kind.specName + "(" + format(parameter) + ")";
            case PARTIAL_TUKEY, PUNCHOUT_TUKEY -> kind.specName + "(" + parts + "/" + format(overlap) + "/" + format(parameter) + ")";
            case SUBDIVIDE_TUKEY -> kind.specName + "(" + parts + "/" + format(parameter) + ")";
            default -> kind.specName;
        };
    }

    /**
     * Formats a parameter without a locale-dependent decimal separator.
     *
     * @param value the parameter
     * @return its shortest decimal form, without a fractional part when it is integral
     */
    private static String format(double value) {
        var text = Double.toString(value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
