package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.h264.encoder.H264Profile;
import com.github.auties00.smalt.video.h264.encoder.H264RateControl;
import com.github.auties00.smalt.video.h264.encoder.H264EncoderSpeed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks what ffprobe reads from the produced streams: profile, level, cropped size, color
 * description, frame timing and the absence of B-frames, plus the decoder configuration record.
 */
class StreamPropertiesTest {
    private static String field(String json, String name) {
        var matcher = Pattern.compile("\"" + name + "\"\\s*:\\s*\"?([^\",\\n]*)\"?").matcher(json);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String probe(EncoderHarness.Encoded encoded, String name) {
        var file = EncoderHarness.write(name + ".264", encoded.annexB());
        return new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error", "-print_format",
                "json", "-show_streams", "-show_frames", "-f", "h264", file.toString())));
    }

    @ParameterizedTest(name = "{0} {1}x{2} at {3} fps")
    @CsvSource({
            "CONSTRAINED_BASELINE, 176, 144, 15, Constrained Baseline, 10",
            "MAIN, 1280, 720, 30, Main, 31",
            "HIGH, 1920, 1080, 30, High, 40",
            "MAIN, 33, 17, 30, Main, 10",
            "HIGH, 640, 480, 60, High, 31"
    })
    @DisplayName("ffprobe reports the profile, the automatically chosen level, the cropped size and no B-frames")
    void profileAndLevel(H264Profile profile, int width, int height, int fps, String profileName, int level) {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var config = H264Setup.of(width, height, new Rational(fps, 1), new Rational(1, fps), profile, 0,
                H264RateControl.CONSTANT_QUALITY, 0, 0, 0, 30, 30, H264EncoderSpeed.FASTEST, ColorInfo.BT709_LIMITED, 1);
        var encoded = EncoderHarness.encode(config, EncoderHarness.sequence(width, height, 3, 1, 1.0));
        var json = probe(encoded, "probe-" + profile + "-" + width + "x" + height);
        assertEquals(profileName, field(json, "profile"));
        assertEquals(Integer.toString(level), field(json, "level"));
        assertEquals(Integer.toString((width + 1) & ~1), field(json, "width"));
        assertEquals(Integer.toString((height + 1) & ~1), field(json, "height"));
        assertEquals("0", field(json, "has_b_frames"));
        assertEquals("yuv420p", field(json, "pix_fmt"));
        assertFalse(json.contains("\"pict_type\": \"B\""), "no B pictures");
        assertTrue(json.contains("\"pict_type\": \"I\""));
    }

    @Test
    @DisplayName("the color description and frame rate are signaled in the VUI")
    void colorAndTiming() {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var config = H264Setup.of(160, 96, new Rational(30000, 1001), new Rational(1001, 30000), H264Profile.MAIN,
                0, H264RateControl.CONSTANT_QUALITY, 0, 0, 0, 30, 30, H264EncoderSpeed.FAST, ColorInfo.BT601_LIMITED, 1);
        var encoded = EncoderHarness.encode(config, EncoderHarness.sequence(160, 96, 2, 1, 1.0));
        var json = probe(encoded, "probe-color");
        assertEquals("smpte170m", field(json, "color_space"));
        assertEquals("smpte170m", field(json, "color_transfer"));
        assertEquals("smpte170m", field(json, "color_primaries"));
        assertEquals("tv", field(json, "color_range"));
        var headers = EncoderHarness.traceHeaders(EncoderHarness.write("probe-color.264", encoded.annexB()));
        assertEquals(1001L, headers.get("num_units_in_tick"));
        assertEquals(60000L, headers.get("time_scale"));
        assertEquals(0L, headers.get("fixed_frame_rate_flag"));
        assertEquals(0L, headers.get("max_num_reorder_frames"));
        assertEquals((long) new com.github.auties00.smalt.video.h264.encoder.internal.EncoderSettings(config.resolved()).refs(),
                headers.get("max_dec_frame_buffering"));
        assertEquals(2L, headers.get("pic_order_cnt_type"));
        assertEquals(6L, headers.get("matrix_coefficients"));
        var full = H264Setup.of(160, 96, new Rational(25, 1), new Rational(1, 25), H264Profile.HIGH, 0,
                H264RateControl.CONSTANT_QUALITY, 0, 0, 0, 30, 30, H264EncoderSpeed.FAST, ColorInfo.JPEG, 1);
        var fullJson = probe(EncoderHarness.encode(full, EncoderHarness.sequence(160, 96, 2, 1, 1.0)), "probe-full");
        assertEquals("pc", field(fullJson, "color_range"));
        assertEquals("bt709", field(fullJson, "color_primaries"));
    }

    @Test
    @DisplayName("an explicit level is written as given, level 1b included")
    void explicitLevel() {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var config = H264Setup.of(176, 144, new Rational(15, 1), new Rational(1, 15), H264Profile.HIGH, 41,
                H264RateControl.CONSTANT_QUALITY, 0, 0, 0, 30, 30, H264EncoderSpeed.FAST, ColorInfo.BT709_LIMITED, 1);
        var json = probe(EncoderHarness.encode(config, EncoderHarness.sequence(176, 144, 2, 1, 1.0)), "probe-41");
        assertEquals("41", field(json, "level"));
        var baseline1b = H264Setup.of(128, 96, new Rational(15, 1), new Rational(1, 15),
                H264Profile.CONSTRAINED_BASELINE, 9, H264RateControl.CONSTANT_QUALITY, 0, 0, 0, 30, 30, H264EncoderSpeed.FAST,
                ColorInfo.BT709_LIMITED, 1);
        var encoded = EncoderHarness.encode(baseline1b, EncoderHarness.sequence(128, 96, 2, 1, 1.0));
        var avcC = encoded.avcC();
        assertEquals(66, avcC[1] & 0xFF);
        assertEquals(0xD0, avcC[2] & 0xF0, "constraint_set0, set1 and set3 signal Constrained Baseline level 1b");
        assertEquals(11, avcC[3] & 0xFF);
        EncoderHarness.assertBitExact("level-1b", encoded);
    }

    @Test
    @DisplayName("the AVC decoder configuration record carries the parameter sets with 4-byte lengths")
    void configurationRecord() {
        var config = H264Setup.file(320, 240, new Rational(30, 1), 500_000);
        var encoded = EncoderHarness.encode(config, EncoderHarness.sequence(320, 240, 1, 1, 1.0));
        var record = encoded.avcC();
        assertEquals(1, record[0]);
        assertEquals(77, record[1] & 0xFF);
        assertEquals(0xFF, record[4] & 0xFF, "lengthSizeMinusOne = 3");
        assertEquals(0xE1, record[5] & 0xFF, "one SPS");
        var spsLength = ((record[6] & 0xFF) << 8) | (record[7] & 0xFF);
        assertEquals(encoded.sps().length, spsLength);
        assertEquals(0x67, record[8] & 0xFF, "SPS NAL header");
        var ppsCountIndex = 8 + spsLength;
        assertEquals(1, record[ppsCountIndex]);
        var ppsLength = ((record[ppsCountIndex + 1] & 0xFF) << 8) | (record[ppsCountIndex + 2] & 0xFF);
        assertEquals(encoded.pps().length, ppsLength);
        assertEquals(record.length, ppsCountIndex + 3 + ppsLength, "no trailing extension for Main");
        var packet = encoded.packets().getFirst().data();
        var nalLength = ((packet[0] & 0xFF) << 24) | ((packet[1] & 0xFF) << 16) | ((packet[2] & 0xFF) << 8)
                | (packet[3] & 0xFF);
        assertEquals(packet.length, 4 + nalLength, "one IDR slice NAL unit with a 4-byte length");
        assertEquals(0x65, packet[4] & 0xFF, "IDR slice with nal_ref_idc 3");
    }
}
