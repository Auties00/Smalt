package com.github.auties00.smalt.video.hevc.encoder.internal;

import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoderOptions;
import com.github.auties00.smalt.video.hevc.encoder.HevcEncoderSpeed;
import com.github.auties00.smalt.video.hevc.encoder.HevcLevel;
import com.github.auties00.smalt.video.hevc.encoder.HevcProfile;
import com.github.auties00.smalt.video.hevc.encoder.HevcRateControl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Derives the coding settings of the HEVC encoder from its options: the level and tier chosen for a size, rate and
 * bitrate or checked when named, the reference count of each speed, the B-picture pyramid and its reorder depth,
 * and the bitrates and buffer of each rate control.
 */
@DisplayName("HEVC encoder settings")
class EncoderSettingsTest {
    private static HevcEncoderOptions.Builder options() {
        return HevcEncoderOptions.defaults().toBuilder().frameRate(new Rational(30, 1)).bitrate(2_000_000)
                .rateControl(HevcRateControl.AVERAGE_BITRATE);
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(HevcEncoderSpeed.class)
    @DisplayName("keeps more references at slower speeds")
    void speeds(HevcEncoderSpeed speed) {
        var settings = EncoderSettings.of(640, 360, options().speed(speed).bFrames(0).build());
        var expected = switch (speed) {
            case FASTEST, FAST -> 1;
            case BALANCED -> 2;
            case SLOW -> 3;
        };
        assertEquals(expected, settings.references);
    }

    @ParameterizedTest(name = "{0} B-pictures")
    @CsvSource({"0,0", "1,1", "3,2", "7,3"})
    @DisplayName("reorders as deep as the B-picture pyramid")
    void pyramid(int bFrames, int reorder) {
        var settings = EncoderSettings.of(640, 360, options().bFrames(bFrames).build());
        assertEquals(bFrames, settings.bFrames);
        assertEquals(reorder, settings.maxNumReorder);
        assertEquals(0, EncoderSettings.of(640, 360, options().bFrames(bFrames).realtime(true).build()).bFrames);
    }

    @Test
    @DisplayName("codes still pictures and quality-driven streams without a bitrate or references")
    void quality() {
        var still = EncoderSettings.of(640, 360, options().profile(HevcProfile.MAIN_STILL_PICTURE).build());
        assertEquals(0, still.references);
        assertEquals(0, still.bitrate);
        var constant = EncoderSettings.of(640, 360, options().rateControl(HevcRateControl.CONSTANT_QUALITY).build());
        assertEquals(0, constant.maxBitrate);
        assertEquals(0, constant.bufferBits);
        var lossless = EncoderSettings.of(640, 360, options().lossless(true).build());
        assertEquals(0, lossless.bitrate);
    }

    @Test
    @DisplayName("bounds an average bitrate stream at twice its rate and a constant one at its rate")
    void bitrates() {
        var average = EncoderSettings.of(640, 360, options().build());
        assertEquals(4_000_000, average.maxBitrate);
        var constant = EncoderSettings.of(640, 360, options().rateControl(HevcRateControl.CONSTANT_BITRATE).build());
        assertEquals(2_000_000, constant.maxBitrate);
        var capped = EncoderSettings.of(640, 360, options().maxBitrate(3_000_000).bufferSize(Duration.ofMillis(250))
                .build());
        assertEquals(3_000_000, capped.maxBitrate);
        assertTrue(capped.bufferBits < average.bufferBits);
    }

    @Test
    @DisplayName("chooses the lowest level and tier that hold the stream, and checks a named level")
    void levels() {
        var small = EncoderSettings.of(176, 144, options().bitrate(100_000).frameRate(new Rational(15, 1)).build());
        var large = EncoderSettings.of(3840, 2160, options().bitrate(20_000_000).build());
        assertTrue(small.levelIdc < large.levelIdc);
        assertFalse(small.highTier);
        var high = EncoderSettings.of(1920, 1080, options().bitrate(60_000_000).build());
        assertTrue(high.highTier || high.levelIdc > 123, "a bitrate beyond the main tier of level 4.1");
        var huge = EncoderSettings.of(1920, 1080, options().bitrate(2_000_000_000).build());
        assertEquals(HevcLevel.LEVEL_6_2.levelIdc(), huge.levelIdc);
        assertTrue(huge.highTier);
        var named = EncoderSettings.of(1920, 1080, options().level(HevcLevel.LEVEL_5_1).bitrate(200_000_000).build());
        assertEquals(HevcLevel.LEVEL_5_1.levelIdc(), named.levelIdc);
        assertTrue(named.highTier);
        assertThrows(IllegalArgumentException.class, () -> EncoderSettings.of(1920, 1080, options()
                .level(HevcLevel.LEVEL_2).build()));
    }

    @Test
    @DisplayName("refuses sizes outside every level")
    void sizes() {
        assertThrows(IllegalArgumentException.class, () -> EncoderSettings.of(0, 360, options().build()));
        assertThrows(IllegalArgumentException.class, () -> EncoderSettings.of(640, -1, options().build()));
        assertThrows(IllegalArgumentException.class, () -> EncoderSettings.of(100_000, 360, options().build()));
        assertThrows(IllegalArgumentException.class, () -> EncoderSettings.of(16384, 16384, options()
                .limits(com.github.auties00.smalt.MediaLimits.builder().maxPixels(1L << 40).maxDimension(1 << 16)
                        .build()).build()));
    }
}
