package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.internal.io.ByteArrays;
import com.github.auties00.smalt.internal.io.ByteWriter;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * Reads and writes the 3GPP location information box ({@code loci}, 3GPP TS 26.244 clause 8.10) and the ISO 6709
 * coordinates it is exchanged as.
 *
 * <p>The box holds a full box header, a packed ISO 639-2/T language, a place name, a role, the longitude, latitude
 * and altitude as signed 16.16 fixed-point numbers (degrees, degrees and meters), the astronomical body and
 * additional notes; its strings end with a NUL and are UTF-8, or UTF-16 after a byte order mark. The coordinates are
 * written as {@code +DD.D+DDD.D+A.A/}: the latitude with at least two integer digits, the longitude with at least
 * three, then the altitude unless it is zero, each signed and with the fewest decimals that convert back to the same
 * fixed-point value.
 *
 * <p>This class is thread-safe.
 */
public final class LocationInformation {
    /**
     * The box type.
     */
    static final String TYPE = "loci";

    /**
     * The packed ISO 639-2/T code {@code und} (undetermined), the language of a new box.
     */
    private static final int UNDETERMINED = 0x55C4;

    /**
     * The one fixed-point unit, {@code 2^16}.
     */
    private static final BigDecimal UNIT = BigDecimal.valueOf(65536);

    /**
     * The decimal digits needed for every 16.16 value to convert back exactly.
     */
    private static final int MAX_DECIMALS = 5;

    /**
     * The ISO 6709 coordinates: sign, digits and fraction of the latitude ({@code DD}, {@code DDMM} or
     * {@code DDMMSS}), of the longitude ({@code DDD}, {@code DDDMM} or {@code DDDMMSS}) and of the optional
     * altitude, then an optional coordinate reference system and the terminating {@code /}.
     */
    private static final Pattern ISO_6709 = Pattern.compile("([+-])(\\d{2}|\\d{4}|\\d{6})(\\.\\d+)?"
            + "([+-])(\\d{3}|\\d{5}|\\d{7})(\\.\\d+)?(?:([+-])(\\d+)(\\.\\d+)?)?(?:CRS[A-Za-z0-9_]*)?/?");

    /**
     * Prevents instantiation.
     */
    private LocationInformation() {
        throw new AssertionError();
    }

    /**
     * Decodes the coordinates of a box.
     *
     * @param data the bytes holding the box
     * @param payload the index of the payload, after the box header
     * @param end the end of the box
     * @return the ISO 6709 coordinates, or {@code null} when the box is malformed or of an unknown version
     */
    public static String coordinates(byte[] data, int payload, int end) {
        var fields = Fields.parse(data, payload, end);
        return fields == null ? null : format(fields.latitude, fields.longitude, fields.altitude);
    }

    /**
     * Builds a box holding other coordinates.
     *
     * @param template the bytes holding the box whose language, name, role, body and notes are kept, or {@code null}
     * for a box of undetermined language without name or notes, about the earth, of role 0 (the shooting location)
     * @param payload the index of the template's payload
     * @param end the end of the template
     * @param coordinates the ISO 6709 coordinates
     * @return the box, header included
     * @throws IllegalArgumentException if the coordinates are not ISO 6709 coordinates in range
     */
    static byte[] box(byte[] template, int payload, int end, String coordinates) {
        var fixed = parse(coordinates);
        var fields = template == null ? null : Fields.parse(template, payload, end);
        var out = new ByteWriter(64);
        out.u32be(0);
        if (fields == null) {
            out.u16be(UNDETERMINED);
            out.u8(0);
            out.u8(0);
        } else {
            out.bytes(template, payload + 4, fields.roleAt - payload - 4 + 1);
        }
        out.u32be(fixed[1] & 0xFFFFFFFFL);
        out.u32be(fixed[0] & 0xFFFFFFFFL);
        out.u32be(fixed[2] & 0xFFFFFFFFL);
        if (fields == null) {
            out.bytes("earth".getBytes(StandardCharsets.US_ASCII));
            out.u8(0);
            out.u8(0);
        } else {
            out.bytes(template, fields.roleAt + 13, end - fields.roleAt - 13);
        }
        return Mp4Boxes.box(TYPE, out.toByteArray());
    }

    /**
     * Formats coordinates.
     *
     * @param latitude the latitude in 16.16 fixed-point degrees
     * @param longitude the longitude in 16.16 fixed-point degrees
     * @param altitude the altitude in 16.16 fixed-point meters
     * @return the ISO 6709 text
     */
    static String format(int latitude, int longitude, int altitude) {
        var text = number(latitude, 2) + number(longitude, 3);
        return (altitude == 0 ? text : text + number(altitude, 1)) + "/";
    }

    /**
     * Formats one signed fixed-point number with the fewest decimals that convert back to it.
     *
     * @param fixed the 16.16 value
     * @param digits the fewest integer digits
     * @return the text, with its sign
     */
    private static String number(int fixed, int digits) {
        var magnitude = Math.abs((long) fixed);
        var exact = BigDecimal.valueOf(magnitude).divide(UNIT);
        var chosen = exact;
        for (var decimals = 0; decimals <= MAX_DECIMALS; decimals++) {
            var candidate = exact.setScale(decimals, RoundingMode.HALF_EVEN);
            if (toFixed(candidate) == magnitude) {
                chosen = candidate;
                break;
            }
        }
        var plain = chosen.toPlainString();
        var point = plain.indexOf('.');
        var integer = point < 0 ? plain.length() : point;
        return (fixed < 0 ? "-" : "+") + "0".repeat(Math.max(0, digits - integer)) + plain;
    }

    /**
     * Parses ISO 6709 coordinates.
     *
     * @param text the coordinates
     * @return the latitude, longitude and altitude in 16.16 fixed point, the altitude zero when absent
     * @throws IllegalArgumentException if the text is not ISO 6709 coordinates, or a value is out of range
     */
    static int[] parse(String text) {
        var matcher = ISO_6709.matcher(text.strip());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("\"" + text + "\" is not ISO 6709 coordinates");
        }
        var latitude = angle(matcher.group(1), matcher.group(2), matcher.group(3), 2);
        var longitude = angle(matcher.group(4), matcher.group(5), matcher.group(6), 3);
        var altitude = matcher.group(7) == null ? BigDecimal.ZERO
                : signed(matcher.group(7), new BigDecimal(matcher.group(8) + (matcher.group(9) == null ? ""
                : matcher.group(9))));
        if (latitude.abs().compareTo(BigDecimal.valueOf(90)) > 0
                || longitude.abs().compareTo(BigDecimal.valueOf(180)) > 0) {
            throw new IllegalArgumentException("the coordinates \"" + text + "\" are out of range");
        }
        var fixedAltitude = signedFixed(altitude);
        if (fixedAltitude < Integer.MIN_VALUE || fixedAltitude > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("the altitude of \"" + text + "\" does not fit 16.16 fixed point");
        }
        return new int[]{(int) signedFixed(latitude), (int) signedFixed(longitude), (int) fixedAltitude};
    }

    /**
     * Converts an ISO 6709 angle to degrees.
     *
     * @param sign the sign, {@code +} or {@code -}
     * @param digits the digits: degrees, then minutes and seconds when present
     * @param fraction the fraction of the last unit, with its point, or {@code null}
     * @param degreeDigits the digits of the degrees, 2 for a latitude and 3 for a longitude
     * @return the signed degrees
     * @throws IllegalArgumentException if the minutes or seconds are 60 or more
     */
    private static BigDecimal angle(String sign, String digits, String fraction, int degreeDigits) {
        var units = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO};
        var count = 1 + (digits.length() - degreeDigits) / 2;
        units[0] = new BigDecimal(digits.substring(0, degreeDigits));
        for (var unit = 1; unit < count; unit++) {
            var start = degreeDigits + 2 * (unit - 1);
            units[unit] = new BigDecimal(digits.substring(start, start + 2));
        }
        if (fraction != null) {
            units[count - 1] = units[count - 1].add(new BigDecimal("0" + fraction));
        }
        for (var unit = 1; unit < count; unit++) {
            if (units[unit].compareTo(BigDecimal.valueOf(60)) >= 0) {
                throw new IllegalArgumentException("ISO 6709 minutes and seconds are below 60");
            }
        }
        var degrees = units[0].add(units[1].divide(BigDecimal.valueOf(60), 20, RoundingMode.HALF_EVEN))
                .add(units[2].divide(BigDecimal.valueOf(3600), 20, RoundingMode.HALF_EVEN));
        return signed(sign, degrees);
    }

    /**
     * Applies a sign.
     *
     * @param sign {@code +} or {@code -}
     * @param magnitude the unsigned value
     * @return the signed value
     */
    private static BigDecimal signed(String sign, BigDecimal magnitude) {
        return sign.equals("-") ? magnitude.negate() : magnitude;
    }

    /**
     * Converts a value to 16.16 fixed point, rounding half away from zero.
     *
     * @param value the value to convert
     * @return the fixed-point value, which may not fit 32 bits
     */
    private static long signedFixed(BigDecimal value) {
        var magnitude = value.abs();
        if (magnitude.compareTo(BigDecimal.valueOf(1L << 20)) > 0) {
            return value.signum() < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
        }
        var fixed = toFixed(magnitude);
        return value.signum() < 0 ? -fixed : fixed;
    }

    /**
     * Converts a non-negative value to 16.16 fixed point, rounding half up.
     *
     * @param magnitude the value, at most {@code 2^20}
     * @return the fixed-point value
     */
    private static long toFixed(BigDecimal magnitude) {
        return magnitude.multiply(UNIT).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /**
     * The fields of a box this class reads.
     *
     * @param latitude the latitude, 16.16 fixed-point degrees
     * @param longitude the longitude, 16.16 fixed-point degrees
     * @param altitude the altitude, 16.16 fixed-point meters
     * @param roleAt the index of the role byte
     */
    private record Fields(int latitude, int longitude, int altitude, int roleAt) {
        /**
         * Parses a box.
         *
         * <p>The astronomical body and the notes after the coordinates are not checked, so a box that cuts or omits
         * them still gives its coordinates.
         *
         * @param data the bytes holding the box
         * @param payload the index of the payload
         * @param end the end of the box
         * @return the fields, or {@code null} when the box is not version 0, its place name is not terminated or its
         * role and coordinates are cut
         */
        static Fields parse(byte[] data, int payload, int end) {
            if (end - payload < 6 || data[payload] != 0) {
                return null;
            }
            var nameEnd = stringEnd(data, payload + 6, end);
            if (nameEnd < 0 || end - nameEnd < 13) {
                return null;
            }
            return new Fields(ByteArrays.i32be(data, nameEnd + 5), ByteArrays.i32be(data, nameEnd + 1),
                    ByteArrays.i32be(data, nameEnd + 9), nameEnd);
        }

        /**
         * Finds the end of a NUL-terminated string, UTF-16 after a byte order mark.
         *
         * @param data the bytes
         * @param start the index of the string
         * @param end the end of the box
         * @return the index after the terminator, or {@code -1} when the string is not terminated
         */
        private static int stringEnd(byte[] data, int start, int end) {
            var wide = end - start >= 2 && ((data[start] & 0xFF) == 0xFE && (data[start + 1] & 0xFF) == 0xFF
                    || (data[start] & 0xFF) == 0xFF && (data[start + 1] & 0xFF) == 0xFE);
            if (wide) {
                for (var at = start + 2; at + 1 < end; at += 2) {
                    if (data[at] == 0 && data[at + 1] == 0) {
                        return at + 2;
                    }
                }
                return -1;
            }
            for (var at = start; at < end; at++) {
                if (data[at] == 0) {
                    return at + 1;
                }
            }
            return -1;
        }
    }
}
