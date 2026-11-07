package com.github.auties00.smalt.internal.subtitle;

import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.track.Rational;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Parses and writes MicroDVD ({@code .sub}) subtitle files.
 *
 * <p>A MicroDVD file holds one cue per line, {@code {start}{end}text}, the times in frames and the lines of
 * the text separated by {@code |}; an empty end lasts until the next cue. A first cue at frame 0 or 1
 * whose text is a number gives the frame rate ({@code {1}{1}23.976}, the rates 23.976, 29.97 and 59.94
 * read as the NTSC rates 24000/1001, 30000/1001 and 60000/1001). Control codes at the start of a line
 * style that line when lowercase and every following line of the cue when uppercase: {@code {y:b,i,u,s}}
 * the styles, {@code {c:$BBGGRR}} the color, {@code {f:name}} the font and {@code {s:size}} the font size;
 * they become SubRip markup, and other codes ({@code {P:...}}, {@code {H:...}}, {@code {o:...}}) are dropped.
 */
public final class MicroDvdText {
    /**
     * The frame rate of a file that declares none.
     */
    public static final Rational DEFAULT_RATE = new Rational(24000, 1001);

    /**
     * The largest number of cues a file yields.
     */
    private static final int MAX_CUES = 1 << 22;

    /**
     * The smallest frame rate a file may declare, in frames per second.
     */
    private static final BigDecimal MIN_RATE = new BigDecimal("0.001");

    private MicroDvdText() {
        throw new AssertionError();
    }

    /**
     * Holds a parsed file.
     *
     * @param frameRate the frame rate the file declares, or {@code null}
     * @param cues the cues in start order, in SubRip markup
     */
    public record Parsed(Rational frameRate, List<SubtitleCue> cues) {
    }

    /**
     * Returns whether text starts like a MicroDVD file: its first non-blank line starts with
     * {@code {digits}{digits}} or {@code {digits}{}}.
     *
     * @param text the leading text of a file, with line feeds only
     * @return {@code true} for a MicroDVD file
     */
    public static boolean isSignature(String text) {
        for (var line : text.split("\n", 64)) {
            if (line.isBlank()) {
                continue;
            }
            return frames(line.strip(), new long[2], new int[1]);
        }
        return false;
    }

    /**
     * Parses the frame numbers that start a line.
     *
     * @param line the line, stripped
     * @param frames receives the start frame and the end frame, {@code -1} for an empty end
     * @param end receives the index after the second closing brace
     * @return {@code true} when the line starts with two frame fields
     */
    private static boolean frames(String line, long[] frames, int[] end) {
        var position = 0;
        for (var field = 0; field < 2; field++) {
            if (position >= line.length() || line.charAt(position) != '{') {
                return false;
            }
            var close = line.indexOf('}', position + 1);
            if (close < 0 || close - position > 16) {
                return false;
            }
            var digits = line.substring(position + 1, close).strip();
            if (digits.isEmpty()) {
                if (field == 0) {
                    return false;
                }
                frames[field] = -1;
            } else {
                for (var index = 0; index < digits.length(); index++) {
                    if (!CueTimes.isDigit(digits.charAt(index))) {
                        return false;
                    }
                }
                frames[field] = Long.parseLong(digits);
            }
            position = close + 1;
        }
        end[0] = position;
        return true;
    }

    /**
     * Parses the text of a MicroDVD file.
     *
     * @param text the file text, with line feeds only
     * @param frameRate the frame rate to use, or {@code null} for the declared one or {@link #DEFAULT_RATE}
     * @return the parsed file
     */
    public static Parsed parse(String text, Rational frameRate) {
        var frames = new long[2];
        var end = new int[1];
        var raw = new ArrayList<Object[]>();
        Rational declared = null;
        var first = true;
        for (var line : text.split("\n")) {
            if (raw.size() >= MAX_CUES) {
                break;
            }
            var stripped = line.strip();
            if (stripped.isEmpty() || !frames(stripped, frames, end)) {
                continue;
            }
            var body = stripped.substring(end[0]);
            if (first && frames[0] <= 1 && (frames[1] <= 1) && declared == null) {
                first = false;
                var rate = rate(body.strip());
                if (rate != null) {
                    declared = rate;
                    continue;
                }
            }
            first = false;
            raw.add(new Object[]{frames[0], frames[1], body});
        }
        var rate = frameRate != null ? frameRate : declared != null ? declared : DEFAULT_RATE;
        raw.sort(Comparator.comparingLong(entry -> (long) entry[0]));
        var cues = new ArrayList<SubtitleCue>(raw.size());
        for (var index = 0; index < raw.size(); index++) {
            var start = (long) raw.get(index)[0];
            var stop = (long) raw.get(index)[1];
            if (stop < 0) {
                stop = index + 1 < raw.size() ? (long) raw.get(index + 1)[0] : start + Math.round(rate.numerator() * 4.0
                        / rate.denominator());
            }
            var subrip = subRip((String) raw.get(index)[2]);
            if (Markup.plain(subrip, SubtitleMarkup.SUBRIP).isBlank()) {
                continue;
            }
            var startMicros = micros(start, rate);
            cues.add(SubtitleCue.of(startMicros, Math.max(startMicros, micros(stop, rate)), subrip));
        }
        return new Parsed(declared, List.copyOf(cues));
    }

    /**
     * Parses a declared frame rate.
     *
     * @param text the text of the first cue
     * @return the rate, or {@code null} when the text is not a number from 0.001 to 1000
     */
    static Rational rate(String text) {
        if (text.isEmpty() || text.length() > 16) {
            return null;
        }
        BigDecimal value;
        try {
            value = new BigDecimal(text);
        } catch (NumberFormatException exception) {
            return null;
        }
        if (value.compareTo(MIN_RATE) < 0 || value.compareTo(BigDecimal.valueOf(1000)) > 0) {
            return null;
        }
        for (var base : new int[]{24, 30, 60, 48, 120}) {
            var ntsc = BigDecimal.valueOf(base * 1000L).divide(BigDecimal.valueOf(1001), 3, RoundingMode.HALF_UP);
            if (value.subtract(ntsc).abs().compareTo(new BigDecimal("0.0015")) <= 0
                    || value.compareTo(ntsc.setScale(2, RoundingMode.HALF_UP)) == 0) {
                return new Rational(base * 1000L, 1001);
            }
        }
        var scaled = value.stripTrailingZeros();
        var scale = Math.max(0, scaled.scale());
        var denominator = BigDecimal.TEN.pow(scale);
        return new Rational(scaled.multiply(denominator).longValueExact(), denominator.longValueExact()).reduce();
    }

    /**
     * Converts a frame number to microseconds.
     *
     * @param frame the frame number
     * @param rate the frame rate
     * @return the time
     */
    private static long micros(long frame, Rational rate) {
        return Math.round(frame * 1_000_000.0 * rate.denominator() / rate.numerator());
    }

    /**
     * Converts the text of a MicroDVD cue to SubRip markup.
     *
     * @param body the text after the frame fields
     * @return the SubRip text
     */
    static String subRip(String body) {
        var builder = new StringBuilder(body.length() + 16);
        var global = new String[4];
        var lines = body.split("\\|", -1);
        for (var index = 0; index < lines.length; index++) {
            var line = lines[index];
            var local = global.clone();
            var position = 0;
            while (position < line.length() && line.charAt(position) == '{') {
                var close = line.indexOf('}', position + 1);
                if (close < 0) {
                    break;
                }
                var code = line.substring(position + 1, close);
                var colon = code.indexOf(':');
                if (colon != 1) {
                    break;
                }
                var name = code.charAt(0);
                var value = code.substring(2).strip();
                var slot = switch (Character.toLowerCase(name)) {
                    case 'y' -> 0;
                    case 'c' -> 1;
                    case 'f' -> 2;
                    case 's' -> 3;
                    default -> -1;
                };
                if (slot >= 0) {
                    local[slot] = value;
                    if (Character.isUpperCase(name)) {
                        global[slot] = value;
                    }
                }
                position = close + 1;
            }
            if (index > 0) {
                builder.append('\n');
            }
            builder.append(open(local)).append(line.substring(position)).append(close(local));
        }
        return builder.toString();
    }

    /**
     * Returns the SubRip tags that open the styles of control code values.
     *
     * @param codes the values of the {@code y}, {@code c}, {@code f} and {@code s} codes, {@code null} when
     * absent
     * @return the opening tags
     */
    private static String open(String[] codes) {
        var builder = new StringBuilder();
        var attributes = new StringBuilder();
        if (codes[1] != null) {
            var color = SubtitleColors.parseAss(codes[1].replace("$", ""), false);
            if (color != StyledText.NO_COLOR) {
                attributes.append(" color=\"").append(SubtitleColors.rgb(color)).append('"');
            }
        }
        if (codes[2] != null && !codes[2].isEmpty()) {
            attributes.append(" face=\"").append(codes[2].replace('"', '\'')).append('"');
        }
        if (codes[3] != null && codes[3].chars().allMatch(CueTimes::isDigitCode) && !codes[3].isEmpty()
                && codes[3].length() < 5) {
            attributes.append(" size=\"").append(Integer.parseInt(codes[3])).append('"');
        }
        if (!attributes.isEmpty()) {
            builder.append("<font").append(attributes).append('>');
        }
        if (codes[0] != null) {
            for (var tag : styles(codes[0])) {
                builder.append('<').append(tag).append('>');
            }
        }
        return builder.toString();
    }

    /**
     * Returns the SubRip tags that close the styles of control code values.
     *
     * @param codes the values of the {@code y}, {@code c}, {@code f} and {@code s} codes
     * @return the closing tags
     */
    private static String close(String[] codes) {
        var builder = new StringBuilder();
        if (codes[0] != null) {
            var tags = styles(codes[0]);
            for (var index = tags.size() - 1; index >= 0; index--) {
                builder.append("</").append(tags.get(index)).append('>');
            }
        }
        var open = open(new String[]{null, codes[1], codes[2], codes[3]});
        if (!open.isEmpty()) {
            builder.append("</font>");
        }
        return builder.toString();
    }

    /**
     * Returns the style tags a {@code y} code value names.
     *
     * @param value the value, letters separated by commas
     * @return the tags among {@code b}, {@code i}, {@code u} and {@code s}, in that order
     */
    private static List<String> styles(String value) {
        var lower = value.toLowerCase(Locale.ROOT);
        var result = new ArrayList<String>();
        for (var tag : new String[]{"b", "i", "u", "s"}) {
            for (var part : lower.split(",")) {
                if (part.strip().equals(tag)) {
                    result.add(tag);
                    break;
                }
            }
        }
        return result;
    }

    /**
     * Writes cues as a MicroDVD file.
     *
     * <p>The file starts with the frame rate cue, the rate with three decimals; each cue is one line, its
     * times rounded to the nearest frame of the rate that cue declares ({@link #declarable(Rational)}), so
     * that a reader counting frames at the declared rate finds them where they were. A line's control codes
     * carry the styles, color, font and size its whole text shares; styles that cover only part of a line,
     * and placements, are not written.
     *
     * @param cues the cues in start order, in SubRip markup
     * @param rate the frame rate, from 0.001 to 1000 frames per second
     * @return the file text, with line feeds
     * @throws IllegalArgumentException if the rate is outside the range a file can declare
     */
    public static String write(List<SubtitleCue> cues, Rational rate) {
        var declared = declarable(rate);
        if (declared == null) {
            throw new IllegalArgumentException("a MicroDVD file cannot declare the frame rate " + rate);
        }
        rate = declared;
        var out = new StringBuilder();
        out.append("{1}{1}").append(rateText(rate)).append('\n');
        for (var cue : cues) {
            var start = frame(cue.startMicros(), rate);
            var end = Math.max(start, frame(cue.endMicros(), rate));
            out.append('{').append(start).append("}{").append(end).append('}');
            var styled = Markup.parse(cue.text(), SubtitleMarkup.SUBRIP, Markup.Context.NONE);
            var lines = splitLines(styled.runs());
            for (var index = 0; index < lines.size(); index++) {
                if (index > 0) {
                    out.append('|');
                }
                out.append(line(lines.get(index)));
            }
            out.append('\n');
        }
        return out.toString();
    }

    /**
     * Returns the frame rate a file declaring a rate reads back.
     *
     * @param rate the frame rate
     * @return the rate the declaration of {@code rate} gives, the rate itself for the NTSC rates and for rates
     * with at most three decimals, or {@code null} when it is outside 0.001 to 1000 frames per second
     */
    public static Rational declarable(Rational rate) {
        if (rate.numerator() <= 0 || rate.denominator() <= 0) {
            return null;
        }
        return rate(rateText(rate));
    }

    /**
     * Formats a frame rate as a decimal number, the NTSC rates with three decimals.
     *
     * @param rate the frame rate
     * @return the text
     */
    static String rateText(Rational rate) {
        var value = BigDecimal.valueOf(rate.numerator()).divide(BigDecimal.valueOf(rate.denominator()), 3,
                RoundingMode.HALF_UP);
        return value.toPlainString();
    }

    /**
     * Converts microseconds to the nearest frame.
     *
     * @param micros the time
     * @param rate the frame rate
     * @return the frame number, at least 0
     */
    private static long frame(long micros, Rational rate) {
        return Math.max(0, Math.round(micros / 1_000_000.0 * rate.numerator() / rate.denominator()));
    }

    /**
     * Splits runs at line feeds.
     *
     * @param runs the styled runs
     * @return the runs of each line
     */
    private static List<List<StyledText.Run>> splitLines(List<StyledText.Run> runs) {
        var lines = new ArrayList<List<StyledText.Run>>();
        var current = new ArrayList<StyledText.Run>();
        for (var run : runs) {
            var parts = run.text().split("\n", -1);
            for (var index = 0; index < parts.length; index++) {
                if (index > 0) {
                    lines.add(current);
                    current = new ArrayList<>();
                }
                if (!parts[index].isEmpty()) {
                    current.add(run.withText(parts[index]));
                }
            }
        }
        lines.add(current);
        return lines;
    }

    /**
     * Writes one line with the control codes its runs share.
     *
     * @param runs the runs of the line
     * @return the line
     */
    private static String line(List<StyledText.Run> runs) {
        var builder = new StringBuilder();
        var text = new StringBuilder();
        var style = -1;
        long color = StyledText.NO_COLOR;
        String font = null;
        var size = Double.NaN;
        var first = true;
        for (var run : runs) {
            text.append(run.text().replace('|', '/'));
            if (run.text().isBlank()) {
                continue;
            }
            style = first ? run.style() : style & run.style();
            if (first) {
                color = run.color();
                font = run.font();
                size = run.size();
            } else {
                color = color == run.color() ? color : StyledText.NO_COLOR;
                font = run.font().equals(font) ? font : "";
                size = Double.compare(size, run.size()) == 0 ? size : Double.NaN;
            }
            first = false;
        }
        if (style > 0) {
            var codes = new ArrayList<String>();
            for (var bit = 0; bit < 4; bit++) {
                if ((style & 1 << bit) != 0) {
                    codes.add(new String[]{"b", "i", "u", "s"}[bit]);
                }
            }
            builder.append("{y:").append(String.join(",", codes)).append('}');
        }
        if (color != StyledText.NO_COLOR) {
            builder.append(String.format(Locale.ROOT, "{c:$%02X%02X%02X}", color & 0xFF, color >>> 8 & 0xFF,
                    color >>> 16 & 0xFF));
        }
        if (font != null && !font.isEmpty()) {
            builder.append("{f:").append(font.replace('}', ' ').replace('|', ' ')).append('}');
        }
        if (!Double.isNaN(size)) {
            builder.append("{s:").append(Math.max(1, Math.round(size))).append('}');
        }
        return builder.append(text).toString();
    }
}
