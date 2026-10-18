package com.github.auties00.smalt.internal.subtitle;

/**
 * Parses and formats the cue timestamps of SubRip and WebVTT files.
 *
 * <p>A timestamp is {@code [hours:]minutes:seconds} followed by a comma or a full stop and a
 * fraction of a second. Parsing is lenient the way players are: hours may have any number of
 * digits or be absent, minutes and seconds one or two digits, and the fraction any number of
 * digits (read as a decimal fraction, so {@code ,5} is half a second). Formatting always writes
 * two-digit hours (more when needed), minutes and seconds and three-digit milliseconds, rounding
 * microseconds to the nearest millisecond.
 */
public final class CueTimes {
    private CueTimes() {
        throw new AssertionError();
    }

    /**
     * Parses a timestamp at a position of a line.
     *
     * @param line the text holding the timestamp
     * @param position the index of the timestamp's first character, after any leading blanks
     * @param end a one-element array receiving the index after the timestamp
     * @return the timestamp in microseconds, or {@code -1} when no timestamp starts there
     */
    public static long parse(String line, int position, int[] end) {
        var fields = new long[3];
        var count = 0;
        var index = position;
        var length = line.length();
        while (true) {
            var start = index;
            long value = 0;
            while (index < length && isDigit(line.charAt(index)) && index - start < 12) {
                value = value * 10 + (line.charAt(index) - '0');
                index++;
            }
            if (index == start || count == 3) {
                return -1;
            }
            fields[count++] = value;
            if (index < length && line.charAt(index) == ':') {
                index++;
                continue;
            }
            break;
        }
        if (count < 2) {
            return -1;
        }
        long fraction = 0;
        long scale = 1_000_000;
        if (index < length && (line.charAt(index) == ',' || line.charAt(index) == '.')) {
            index++;
            var digits = index;
            while (index < length && isDigit(line.charAt(index))) {
                if (scale > 1) {
                    scale /= 10;
                    fraction += (line.charAt(index) - '0') * scale;
                }
                index++;
            }
            if (index == digits) {
                return -1;
            }
        }
        long hours = count == 3 ? fields[0] : 0;
        var minutes = fields[count - 2];
        var seconds = fields[count - 1];
        if (minutes > 59 && count == 3 || seconds > 59 || hours > 1_000_000) {
            return -1;
        }
        end[0] = index;
        return ((hours * 60 + minutes) * 60 + seconds) * 1_000_000 + fraction;
    }

    /**
     * Returns whether a character is an ASCII digit.
     *
     * @param character the character to test
     * @return {@code true} for {@code 0} to {@code 9}
     */
    static boolean isDigit(char character) {
        return character >= '0' && character <= '9';
    }

    /**
     * Returns whether a character code is an ASCII digit.
     *
     * @param code the character code
     * @return {@code true} for {@code 0} to {@code 9}
     */
    static boolean isDigitCode(int code) {
        return code >= '0' && code <= '9';
    }

    /**
     * Formats a timestamp.
     *
     * @param micros the timestamp in microseconds; negative values format as zero
     * @param separator the character before the milliseconds: a comma for SubRip, a full stop for
     * WebVTT
     * @return the timestamp, for example {@code 01:02:03,456}
     */
    public static String format(long micros, char separator) {
        var millis = Math.max(0, Math.floorDiv(micros + 500, 1000));
        var hours = millis / 3_600_000;
        var minutes = millis / 60_000 % 60;
        var seconds = millis / 1000 % 60;
        var fraction = millis % 1000;
        var builder = new StringBuilder(13);
        if (hours < 10) {
            builder.append('0');
        }
        builder.append(hours).append(':');
        appendTwo(builder, minutes);
        builder.append(':');
        appendTwo(builder, seconds);
        builder.append(separator);
        if (fraction < 100) {
            builder.append('0');
        }
        if (fraction < 10) {
            builder.append('0');
        }
        return builder.append(fraction).toString();
    }

    /**
     * Appends a value below 100 with two digits.
     *
     * @param builder the destination
     * @param value the value, from 0 to 99
     */
    private static void appendTwo(StringBuilder builder, long value) {
        if (value < 10) {
            builder.append('0');
        }
        builder.append(value);
    }
}
