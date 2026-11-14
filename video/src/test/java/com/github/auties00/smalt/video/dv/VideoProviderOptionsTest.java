package com.github.auties00.smalt.video.dv;

import com.github.auties00.smalt.container.dv.DvFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoEncoderOptions;
import com.github.auties00.smalt.video.VideoEncoders;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Creates the DV and DNxHD encoders of this artifact through the codec-independent options: every picture size DV
 * codes and every DV format named explicitly, the sizes it refuses, the display aspect, and DNxHD at each depth.
 */
@DisplayName("video encoder providers")
class VideoProviderOptionsTest {
    private static final Rational NTSC = new Rational(30000, 1001);

    private static VideoEncoderOptions.Builder options(int width, int height, Rational rate) {
        return VideoEncoderOptions.builder().size(width, height).frameRate(rate).bitrate(25_000_000);
    }

    @ParameterizedTest(name = "{0}x{1} at {2}/{3}")
    @CsvSource({"720,480,30000,1001", "720,576,25,1", "1280,1080,30000,1001", "1440,1080,25,1", "960,720,50,1",
            "960,720,60000,1001"})
    @DisplayName("picks the DV profile of each picture size")
    void dvSizes(int width, int height, long numerator, long denominator) {
        var rate = new Rational(numerator, denominator);
        try (var encoder = VideoEncoders.create(Codec.DV, options(width, height, rate).build())) {
            assertNotNull(encoder);
        }
        try (var encoder = VideoEncoders.create(Codec.DV, options(width, height, rate)
                .sampleAspectRatio(new Rational(40, 33)).build())) {
            assertNotNull(encoder);
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(DvFormat.class)
    @DisplayName("codes every DV format named explicitly, at its own size only")
    void dvFormats(DvFormat format) {
        var rate = format.frameRate();
        assertDoesNotThrow(() -> VideoEncoders.create(Codec.DV, options(format.width(), format.height(), rate)
                .dvFormat(format).build()).close());
        assertThrows(IllegalArgumentException.class, () -> VideoEncoders.create(Codec.DV,
                options(format.width() + 16, format.height(), rate).dvFormat(format).build()));
    }

    @Test
    @DisplayName("refuses picture sizes DV does not code")
    void dvRefused() {
        assertThrows(IllegalArgumentException.class, () -> VideoEncoders.create(Codec.DV, options(640, 480, NTSC)
                .build()));
        assertThrows(IllegalArgumentException.class, () -> VideoEncoders.create(Codec.DV, options(720, 400, NTSC)
                .build()));
    }

    @ParameterizedTest(name = "{0} bits")
    @CsvSource({"8", "10", "12"})
    @DisplayName("creates DNxHR encoders at each depth, with and without a color description")
    void dnxhd(int depth) {
        assertDoesNotThrow(() -> VideoEncoders.create(Codec.DNXHD, options(1280, 720, NTSC).bitDepth(depth).build())
                .close());
        assertDoesNotThrow(() -> VideoEncoders.create(Codec.DNXHD, options(1280, 720, NTSC).bitDepth(depth)
                .color(ColorInfo.BT709_LIMITED).build()).close());
    }
}
