package com.github.auties00.smalt.container;

import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.image.Chromaticity;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.ContentLightLevel;
import com.github.auties00.smalt.image.MasteringDisplay;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared HDR fixtures of the muxer tests: an HDR10 color description with the BT.2020 mastering
 * display of SMPTE ST 2086 examples, synthetic SMPTE ST 2094-40 (HDR10+) ITU-T T.35 messages, and
 * ffprobe helpers that read the side data ffmpeg reports.
 */
public final class HdrFixtures {
    /** A P3-D65 display in BT.2020 content, 1000 cd/m2 peak, 0.0001 cd/m2 black. */
    public static final MasteringDisplay DISPLAY = new MasteringDisplay(new Chromaticity(0.68, 0.32),
            new Chromaticity(0.265, 0.69), new Chromaticity(0.15, 0.06), new Chromaticity(0.3127, 0.329), 1000, 0.0001);

    /** MaxCLL 1000, MaxFALL 400. */
    public static final ContentLightLevel LIGHT = new ContentLightLevel(1000, 400);

    /** The {@code mdcv} payload of {@link #DISPLAY}: G, B, R, white point in 0.00002, then luminances in 0.0001. */
    public static final byte[] MDCV = bytes(u16(13250), u16(34500), u16(7500), u16(3000), u16(34000), u16(16000),
            u16(15635), u16(16450), u32(10_000_000), u32(1));

    /** The {@code clli} payload of {@link #LIGHT}. */
    public static final byte[] CLLI = bytes(u16(1000), u16(400));

    private HdrFixtures() {
        throw new AssertionError();
    }

    /** Returns BT.2020 PQ limited-range color with {@link #DISPLAY}, {@link #LIGHT} and a siting. */
    public static ColorInfo hdr10(ChromaSiting siting) {
        return new ColorInfo(MatrixCoefficients.BT2020_NCL, ColorRange.LIMITED, ColorPrimaries.BT2020,
                TransferCharacteristics.PQ).withChromaSiting(siting).withMasteringDisplay(DISPLAY).withContentLightLevel(LIGHT);
    }

    /** Returns the tracks with the color of every video track replaced. */
    public static List<Track> withColor(List<Track> tracks, ColorInfo color) {
        var result = new ArrayList<Track>();
        for (var track : tracks) {
            result.add(track instanceof VideoTrack video ? video.toBuilder().color(color).build() : track);
        }
        return result;
    }

    /**
     * Returns the packets with an HDR10+ message on those that pass the filter, the message of the
     * n-th such packet being {@link #hdr10Plus(int)} of n.
     */
    public static List<Packet> withHdr10Plus(List<Packet> packets, java.util.function.Predicate<Packet> filter) {
        var result = new ArrayList<Packet>();
        var count = 0;
        for (var packet : packets) {
            if (filter.test(packet)) {
                var side = packet.sideData().toBuilder().hdr10Plus(hdr10Plus(count++)).build();
                result.add(packet.withSideData(side));
            } else {
                result.add(packet);
            }
        }
        return result;
    }

    /** Returns {@code maxscl} red of the message {@link #hdr10Plus(int)} builds for a frame. */
    public static int maxsclRed(int frame) {
        return 1000 + 37 * frame;
    }

    /**
     * Builds an HDR10+ ITU-T T.35 message (SMPTE ST 2094-40 application 4, version 1): one
     * window, a 400 cd/m2 targeted display, nine maxRGB percentiles, a tone-mapping Bezier curve of
     * nine anchors, no saturation mapping; {@code maxscl} varies with the frame so that each
     * packet's message is distinct.
     */
    public static byte[] hdr10Plus(int frame) {
        var bits = new BitWriter();
        bits.put(0xB5, 8);
        bits.put(0x003C, 16);
        bits.put(0x0001, 16);
        bits.put(4, 8);
        bits.put(1, 8);
        bits.put(1, 2); // num_windows
        bits.put(400, 27); // targeted_system_display_maximum_luminance
        bits.put(0, 1); // targeted_system_display_actual_peak_luminance_flag
        bits.put(maxsclRed(frame), 17);
        bits.put(900, 17);
        bits.put(800, 17);
        bits.put(250 + frame, 17); // average_maxrgb
        int[] percentages = {1, 5, 10, 25, 50, 75, 90, 95, 99};
        bits.put(percentages.length, 4);
        for (var index = 0; index < percentages.length; index++) {
            bits.put(percentages[index], 7);
            bits.put(10 + 100 * index, 17);
        }
        bits.put(3, 10); // fraction_bright_pixels
        bits.put(0, 1); // mastering_display_actual_peak_luminance_flag
        bits.put(1, 1); // tone_mapping_flag
        bits.put(1000, 12); // knee_point_x
        bits.put(1200, 12); // knee_point_y
        bits.put(9, 4);
        for (var index = 0; index < 9; index++) {
            bits.put(100 * (index + 1), 10);
        }
        bits.put(0, 1); // color_saturation_mapping_flag
        return bits.toByteArray();
    }

    /** Runs ffprobe with JSON output on a file. */
    public static String ffprobe(Path file, String... arguments) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var list = new ArrayList<>(List.of("-v", "error", "-print_format", "json"));
        list.addAll(List.of(arguments));
        list.add(file.toString());
        return new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(2), list), StandardCharsets.UTF_8);
    }

    /** Copies a file through ffmpeg ({@code -c copy}) into another container. */
    public static Path ffmpegCopy(Path input, String name, String... outputArguments) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var output = input.resolveSibling(name);
        var list = new ArrayList<>(List.of("-v", "error", "-y", "-i", input.toString(), "-map", "0", "-c", "copy"));
        list.addAll(List.of(outputArguments));
        list.add(output.toString());
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), list);
        return output;
    }

    /**
     * Asserts that an ffprobe {@code -show_streams} report carries {@link #DISPLAY} and
     * {@link #LIGHT} as stream side data, within the 0.00002 and 0.0001 units of {@code mdcv}.
     */
    public static void assertProbedHdr10(String json) {
        assertTrue(json.contains("\"side_data_type\": \"Mastering display metadata\""), json);
        assertTrue(json.contains("\"side_data_type\": \"Content light level metadata\""), json);
        assertEquals(DISPLAY.red().x(), rational(json, "red_x"), 1e-5);
        assertEquals(DISPLAY.red().y(), rational(json, "red_y"), 1e-5);
        assertEquals(DISPLAY.green().x(), rational(json, "green_x"), 1e-5);
        assertEquals(DISPLAY.green().y(), rational(json, "green_y"), 1e-5);
        assertEquals(DISPLAY.blue().x(), rational(json, "blue_x"), 1e-5);
        assertEquals(DISPLAY.blue().y(), rational(json, "blue_y"), 1e-5);
        assertEquals(DISPLAY.whitePoint().x(), rational(json, "white_point_x"), 1e-5);
        assertEquals(DISPLAY.whitePoint().y(), rational(json, "white_point_y"), 1e-5);
        assertEquals(DISPLAY.maxLuminance(), rational(json, "max_luminance"), 1e-4);
        assertEquals(DISPLAY.minLuminance(), rational(json, "min_luminance"), 1e-5);
        assertEquals(LIGHT.maxContentLightLevel(), (int) rational(json, "max_content"));
        assertEquals(LIGHT.maxFrameAverageLightLevel(), (int) rational(json, "max_average"));
    }

    /** Returns the first JSON value of a key as a number, reading {@code "a/b"} as a rational. */
    public static double rational(String json, String key) {
        var matcher = Pattern.compile("\"" + Pattern.quote(key) + "\": \"?(-?\\d+)(?:/(\\d+))?\"?").matcher(json);
        assertTrue(matcher.find(), "no " + key + " in " + json);
        var numerator = Double.parseDouble(matcher.group(1));
        return matcher.group(2) == null ? numerator : numerator / Double.parseDouble(matcher.group(2));
    }

    /** Asserts that two color descriptions agree, chromaticities and luminances within a tolerance. */
    public static void assertColorClose(ColorInfo expected, ColorInfo actual, double tolerance) {
        assertEquals(expected.matrix(), actual.matrix());
        assertEquals(expected.range(), actual.range());
        assertEquals(expected.primaries(), actual.primaries());
        assertEquals(expected.transfer(), actual.transfer());
        assertEquals(expected.contentLightLevel(), actual.contentLightLevel());
        assertEquals(expected.masteringDisplay().isPresent(), actual.masteringDisplay().isPresent());
        if (expected.masteringDisplay().isPresent()) {
            var want = expected.masteringDisplay().get();
            var got = actual.masteringDisplay().get();
            for (var pair : List.of(new Chromaticity[]{want.red(), got.red()}, new Chromaticity[]{want.green(), got.green()},
                    new Chromaticity[]{want.blue(), got.blue()}, new Chromaticity[]{want.whitePoint(), got.whitePoint()})) {
                assertEquals(pair[0].x(), pair[1].x(), tolerance);
                assertEquals(pair[0].y(), pair[1].y(), tolerance);
            }
            assertEquals(want.maxLuminance(), got.maxLuminance(), tolerance);
            assertEquals(want.minLuminance(), got.minLuminance(), tolerance);
        }
    }

    /** Returns the payload of the first box of a type in an ISO base media file, or {@code null}. */
    public static byte[] boxPayload(byte[] file, String type) {
        var name = type.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (var index = 4; index + 4 <= file.length; index++) {
            for (var k = 0; k < 4; k++) {
                if (file[index + k] != name[k]) {
                    continue outer;
                }
            }
            var size = ((file[index - 4] & 0xFF) << 24) | ((file[index - 3] & 0xFF) << 16) | ((file[index - 2] & 0xFF) << 8)
                    | (file[index - 1] & 0xFF);
            if (size >= 8 && index - 4 + size <= file.length) {
                return java.util.Arrays.copyOfRange(file, index + 4, index - 4 + size);
            }
        }
        return null;
    }

    /** Returns how many boxes of a type an ISO base media file holds, by a scan of its bytes. */
    public static int countBoxes(byte[] file, String type) {
        var name = type.getBytes(StandardCharsets.US_ASCII);
        var count = 0;
        for (var index = 4; index + 4 <= file.length; index++) {
            if (file[index] == name[0] && file[index + 1] == name[1] && file[index + 2] == name[2] && file[index + 3] == name[3]) {
                count++;
            }
        }
        return count;
    }

    private static byte[] u16(int value) {
        return new byte[]{(byte) (value >> 8), (byte) value};
    }

    private static byte[] u32(long value) {
        return new byte[]{(byte) (value >> 24), (byte) (value >> 16), (byte) (value >> 8), (byte) value};
    }

    private static byte[] bytes(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    /** Returns side data carrying only an HDR10+ message. */
    public static PacketSideData hdr10PlusSideData(int frame) {
        return PacketSideData.builder().hdr10Plus(hdr10Plus(frame)).build();
    }

    /** A most-significant-bit-first bit writer, zero-padded to a byte. */
    private static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int filled;

        void put(long value, int bits) {
            for (var bit = bits - 1; bit >= 0; bit--) {
                current = (current << 1) | (int) ((value >>> bit) & 1);
                if (++filled == 8) {
                    out.write(current);
                    current = 0;
                    filled = 0;
                }
            }
        }

        byte[] toByteArray() {
            if (filled > 0) {
                out.write(current << (8 - filled));
                current = 0;
                filled = 0;
            }
            return out.toByteArray();
        }
    }
}
