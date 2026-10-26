package com.github.auties00.smalt.internal.tags;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.SplittableRandom;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Checks the 3GPP location information box codec and its ISO 6709 text form: the exact 16.16 round trip, the
 * degree, minute and second forms, the ranges, and boxes written by ffmpeg and by hand.
 */
@DisplayName("3GPP location information")
class LocationInformationTest {
    /** The loci box ffmpeg 7.1 writes for -metadata location=+37.3349-122.0090/, header included. */
    private static final byte[] FFMPEG = HexFormat.of().parseHex(
            "000000236c6f63690000000000000000ff85fdb3002555bc0000000065617274680000");

    /** One signed number of an ISO 6709 text, its fraction in group 1. */
    private static final Pattern NUMBER = Pattern.compile("[+-]\\d+(\\.\\d+)?");

    private static int fixed(double value) {
        return (int) Math.round(value * 65536);
    }

    @Test
    @DisplayName("decodes the box ffmpeg writes to the coordinates its fixed-point values hold")
    void ffmpeg() {
        // ffmpeg stores -122.0090 as -7995981/65536, which ffprobe prints rounded to four decimals
        assertEquals("+37.3349-122.00899/", LocationInformation.coordinates(FFMPEG, 8, FFMPEG.length));
        assertArrayEquals(new int[]{2446780, -7995981, 0}, LocationInformation.parse("+37.3349-122.00899/"));
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = ';', value = {
            "+45.4642+009.1900/;45.4642;9.19;0",
            "+45.4642+009.19+120/;45.4642;9.19;120",
            "+4527.852+00911.4/;45.4642;9.19;0",
            "+452751.12+0091124/;45.464200;9.19;0",
            "-33.8688+151.2093-12.5CRSWGS_84/;-33.8688;151.2093;-12.5",
            "+00+000;0;0;0",
            "-90+180/;-90;180;0",
            "+90-180+32767/;90;-180;32767",
            " +45.4642+009.19/ ;45.4642;9.19;0"})
    @DisplayName("parses ISO 6709 decimal degrees, degrees and minutes, and degrees, minutes and seconds")
    void parses(String text, double latitude, double longitude, double altitude) {
        var parsed = LocationInformation.parse(text);
        assertEquals(fixed(latitude), parsed[0], "latitude");
        assertEquals(fixed(longitude), parsed[1], "longitude");
        assertEquals(fixed(altitude), parsed[2], "altitude");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"", "garbage", "45.4642+009.19/", "+45.4642009.19/", "+4.5+009.19/", "+45.4642+09.19/",
            "+91+000/", "+45+181/", "-90.0001+000/", "+4560+00000/", "+452760+0000000/", "+45+009+32768/",
            "+45+009-32769/", "+45+009+1e3/", "+45.+009/", "+45+009//"})
    @DisplayName("rejects text that is not ISO 6709 coordinates in range")
    void rejects(String text) {
        assertThrows(IllegalArgumentException.class, () -> LocationInformation.parse(text));
    }

    @Test
    @DisplayName("formats every fixed-point value with the fewest decimals that parse back to it")
    void roundTrip() {
        var random = new SplittableRandom(6709);
        for (var round = 0; round < 200_000; round++) {
            var latitude = random.nextInt(-90 * 65536, 90 * 65536 + 1);
            var longitude = random.nextInt(-180 * 65536, 180 * 65536 + 1);
            var altitude = random.nextInt(4) == 0 ? 0 : random.nextInt();
            var text = LocationInformation.format(latitude, longitude, altitude);
            var parsed = LocationInformation.parse(text);
            assertArrayEquals(new int[]{latitude, longitude, altitude}, parsed, text);
            var numbers = NUMBER.matcher(text);
            var values = altitude == 0 ? new int[]{latitude, longitude} : new int[]{latitude, longitude, altitude};
            for (var value : values) {
                assertEquals(true, numbers.find(), text);
                var decimals = numbers.group(1) == null ? 0 : numbers.group(1).length() - 1;
                for (var fewer = 0; fewer < decimals; fewer++) {
                    assertEquals(false, representable(value, fewer), text + " is not the shortest form");
                }
            }
        }
        assertEquals("+00+000/", LocationInformation.format(0, 0, 0));
        assertEquals("-00.5-000.25+1.5/", LocationInformation.format(-32768, -16384, 98304));
    }

    /** Returns whether a decimal of some digits converts to a 16.16 value, rounding half up in magnitude. */
    private static boolean representable(int value, int decimals) {
        var magnitude = java.math.BigDecimal.valueOf(Math.abs((long) value));
        var unit = java.math.BigDecimal.valueOf(65536);
        var scale = java.math.BigDecimal.TEN.pow(decimals);
        var low = magnitude.subtract(new java.math.BigDecimal("0.5")).multiply(scale).divide(unit);
        var high = magnitude.add(new java.math.BigDecimal("0.5")).multiply(scale).divide(unit);
        // the decimals k / 10^d converting to the value lie in [low, high), as half up rounds the lower bound up
        var first = low.setScale(0, java.math.RoundingMode.CEILING);
        return first.compareTo(high) < 0;
    }

    @Test
    @DisplayName("builds a new box of undetermined language about the earth that decodes to its coordinates")
    void newBox() {
        var box = LocationInformation.box(null, 0, 0, "+45.4642+009.19+120/");
        var buffer = ByteBuffer.wrap(box);
        assertEquals(box.length, buffer.getInt());
        assertEquals("loci", new String(box, 4, 4, StandardCharsets.US_ASCII));
        assertEquals(0, buffer.getInt(8));
        assertEquals(0x55C4, buffer.getShort(12) & 0xFFFF);
        assertEquals(0, box[14], "empty name");
        assertEquals(0, box[15], "role 0");
        assertEquals(fixed(9.19), buffer.getInt(16));
        assertEquals(fixed(45.4642), buffer.getInt(20));
        assertEquals(fixed(120), buffer.getInt(24));
        assertEquals("earth\0\0", new String(box, 28, box.length - 28, StandardCharsets.US_ASCII));
        assertEquals("+45.4642+009.19+120/", LocationInformation.coordinates(box, 8, box.length));
    }

    @Test
    @DisplayName("keeps the language, name, role, body and notes of the box it replaces")
    void template() {
        var name = "\uFEFFCasa".getBytes(StandardCharsets.UTF_16BE);
        var payload = ByteBuffer.allocate(4 + 2 + name.length + 2 + 1 + 12 + 5 + 6).putInt(0).putShort((short) 0x15C7)
                .put(name).putShort((short) 0).put((byte) 2).putInt(fixed(1)).putInt(fixed(2)).putInt(fixed(3))
                .put("mars\0".getBytes(StandardCharsets.US_ASCII)).put("note\0\0".getBytes(StandardCharsets.US_ASCII))
                .array();
        var template = Mp4Boxes.box("loci", payload);
        assertEquals("+02+001+3/", LocationInformation.coordinates(template, 8, template.length));
        var replaced = LocationInformation.box(template, 8, template.length, "-10.5+020.25/");
        assertEquals("-10.5+020.25/", LocationInformation.coordinates(replaced, 8, replaced.length));
        var expected = payload.clone();
        ByteBuffer.wrap(expected).putInt(6 + name.length + 2 + 1, fixed(20.25)).putInt(6 + name.length + 2 + 5,
                fixed(-10.5)).putInt(6 + name.length + 2 + 9, 0);
        assertArrayEquals(Mp4Boxes.box("loci", expected), replaced);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"", "00000000", "000000005c4400", "0100000055c400000000000000000000000000000000",
            "0000000055c4616263", "0000000055c40000000000000000000000000000", "0000000055c4feff0041"})
    @DisplayName("reports no coordinates for a malformed, truncated or later-version box")
    void malformed(String hex) {
        var payload = HexFormat.of().parseHex(hex);
        assertNull(LocationInformation.coordinates(payload, 0, payload.length));
    }
}
