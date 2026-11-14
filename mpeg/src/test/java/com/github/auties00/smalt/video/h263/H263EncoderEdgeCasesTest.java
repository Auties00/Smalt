package com.github.auties00.smalt.video.h263;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.mpeg4.EncoderScenes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the H.263 encoder's handling of its inputs and options: the pictures it converts to the 8-bit 4:2:0 limited
 * range it codes, the size it refuses, the color it assumes for large pictures, the custom pixel aspect ratio of a
 * standard size, the rate control calls, and the options that take part in equality.
 */
@DisplayName("H.263 encoder edge cases")
class H263EncoderEdgeCasesTest {
    private static H263EncoderOptions.Builder base() {
        return H263EncoderOptions.builder().timeBase(new Rational(1, 30)).frameRate(new Rational(30, 1))
                .bitrate(300_000);
    }

    private static YuvPicture flat(ChromaFormat format, ColorInfo color, int luma) {
        var picture = YuvPicture.allocate(176, 144, format, false, color);
        Arrays.fill(picture.y(), (byte) luma);
        return picture;
    }

    private static double meanLuma(byte[] reconstruction) {
        var sum = 0L;
        for (var i = 0; i < 176 * 144; i++) {
            sum += reconstruction[i] & 0xFF;
        }
        return sum / (176.0 * 144);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({"4:2:0 limited BT.601,0,200", "4:2:2 limited BT.601,1,200", "4:2:0 limited BT.709,2,200",
            "4:2:0 full range,3,102"})
    @DisplayName("codes every picture in the 8-bit 4:2:0 limited range of BT.601")
    void convertsPictures(String name, int kind, int expected) {
        var picture = switch (kind) {
            case 0 -> flat(ChromaFormat.YUV420, ColorInfo.BT601_LIMITED, 200);
            case 1 -> flat(ChromaFormat.YUV422, ColorInfo.BT601_LIMITED, 200);
            case 2 -> flat(ChromaFormat.YUV420, ColorInfo.BT709_LIMITED, 200);
            default -> flat(ChromaFormat.YUV420, ColorInfo.JPEG, 100);
        };
        var packets = new ArrayList<Packet>();
        try (var encoder = new H263Encoder(176, 144, base().fixedQuantizer(2).build())) {
            assertNull(encoder.lastReconstruction());
            encoder.encode(new VideoFrame(picture, 0, 1, false), packets::add);
            var reconstruction = encoder.lastReconstruction();
            assertNotNull(reconstruction);
            // a flat picture keeps its level through the DC coefficient (8 times the level, in steps of 8):
            // limited 200 stays 200, full-range 100 is 101.9 in the limited range and becomes 102
            assertEquals(expected, meanLuma(reconstruction), 1.0, name);
        }
        assertEquals(1, packets.size());
        assertTrue(packets.getFirst().keyframe());
    }

    @Test
    @DisplayName("refuses a picture of another height than the encoder's")
    void refusesOtherHeight() {
        try (var encoder = new H263Encoder(176, 144, base().build())) {
            var picture = EncoderScenes.picture(176, 96, 0);
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(new VideoFrame(picture, 0, 1, false),
                    packet -> { }));
            var other = EncoderScenes.picture(128, 144, 0);
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(new VideoFrame(other, 0, 1, false),
                    packet -> { }));
        }
    }

    @Test
    @DisplayName("flushes nothing, and refuses to flush once closed")
    void flush() {
        var encoder = new H263Encoder(128, 96, base().build());
        var packets = new ArrayList<Packet>();
        encoder.flush(packets::add);
        assertTrue(packets.isEmpty());
        encoder.close();
        assertThrows(IllegalStateException.class, () -> encoder.flush(packets::add));
    }

    @Test
    @DisplayName("retargets the rate control, and refuses a bitrate that is not positive")
    void retarget() {
        var sizes = new ArrayList<Integer>();
        try (var encoder = new H263Encoder(176, 144, base().bitrate(1_000_000).noPeriodicKeyframes().build())) {
            assertThrows(IllegalArgumentException.class, () -> encoder.setTargetBitrate(0));
            assertThrows(IllegalArgumentException.class, () -> encoder.setTargetBitrate(-5));
            for (var i = 0; i < 24; i++) {
                if (i == 12) {
                    encoder.setTargetBitrate(30_000);
                }
                encoder.encode(new VideoFrame(EncoderScenes.picture(176, 144, i), i, 1, false),
                        packet -> sizes.add(packet.data().length));
            }
        }
        var high = sizes.subList(4, 12).stream().mapToInt(Integer::intValue).sum();
        var low = sizes.subList(16, 24).stream().mapToInt(Integer::intValue).sum();
        assertTrue(low < high, "after the retarget: " + low + " bytes, before: " + high);
    }

    @ParameterizedTest(name = "{0}x{1}")
    @CsvSource({"1408,1152", "1024,1152", "1056,576"})
    @DisplayName("codes pictures above the 4CIF size")
    void largePictures(int width, int height) {
        var packets = new ArrayList<Packet>();
        try (var encoder = new H263Encoder(width, height, base().build())) {
            var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
            Arrays.fill(picture.y(), (byte) 200);
            encoder.encode(new VideoFrame(picture, 0, 1, false), packets::add);
            var reconstruction = encoder.lastReconstruction();
            var sum = 0L;
            for (var i = 0; i < width * height; i++) {
                sum += reconstruction[i] & 0xFF;
            }
            assertEquals(200, sum / ((double) width * height), 1.0);
        }
        assertEquals(1, packets.size());
        assertTrue(packets.getFirst().keyframe());
    }

    @ParameterizedTest(name = "{0}:{1}")
    @CsvSource({"12,11,false", "16,11,true", "1,1,true"})
    @DisplayName("signals a pixel aspect ratio other than 12:11 of a standard size with the extended picture type")
    void standardSizeAspect(int numerator, int denominator, boolean extended) {
        try (var encoder = new H263Encoder(176, 144, base().sampleAspectRatio(new Rational(numerator, denominator))
                .build())) {
            assertEquals(extended, encoder.extendedType());
            var packets = new ArrayList<Packet>();
            encoder.encode(new VideoFrame(EncoderScenes.picture(176, 144, 0), 0, 1, false), packets::add);
            assertEquals(1, packets.size());
        }
    }

    @ParameterizedTest(name = "{0}:{1}")
    @CsvSource({"256,1", "1,256", "512,2", "255,511"})
    @DisplayName("refuses a pixel aspect ratio with a term above 255 in lowest terms")
    void largeAspectTerms(int numerator, int denominator) {
        assertThrows(IllegalArgumentException.class,
                () -> base().sampleAspectRatio(new Rational(numerator, denominator)).build());
    }

    @Test
    @DisplayName("tells apart options that differ in any setting")
    void optionsEquality() {
        var options = base().build();
        List<UnaryOperator<H263EncoderOptions.Builder>> changes = List.of(
                b -> b.frameRate(new Rational(25, 1)),
                b -> b.timeBase(new Rational(1, 90000)),
                b -> b.bitrate(123_456),
                b -> b.fixedQuantizer(7),
                b -> b.quantizers(3, 31),
                b -> b.quantizers(2, 20),
                b -> b.keyframeInterval(10),
                b -> b.searchRange(7),
                b -> b.threads(3),
                b -> b.sampleAspectRatio(new Rational(16, 11)),
                b -> b.limits(MediaLimits.builder().maxBytes(1L << 29).build()));
        for (var change : changes) {
            var other = change.apply(base()).build();
            assertNotEquals(options, other, other.toString());
            assertNotEquals(other, options, other.toString());
            assertEquals(other, other.toBuilder().build());
            assertEquals(other.hashCode(), other.toBuilder().build().hashCode());
        }
        assertNotEquals(options, (Object) "options");
        assertEquals(options, base().build());
    }
}
