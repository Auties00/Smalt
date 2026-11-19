package com.github.auties00.smalt.video.dv;

import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the DV encoder options (defaults, presets, setters, resets and validation) and the properties of each
 * {@link DvProfile}.
 */
@DisplayName("DV encoder options and profiles")
class DvEncoderOptionsTest {
    @Test
    @DisplayName("defaults() is 525/60 DV at 4:3 with adaptive DCT on the shared pool")
    void defaults() {
        var options = DvEncoderOptions.defaults();
        assertEquals(DvProfile.DV_525, options.profile());
        assertFalse(options.wide());
        assertTrue(options.adaptiveDct());
        assertEquals(Optional.empty(), options.timeBase());
        assertEquals(0, options.threads());
        assertEquals(options, DvEncoderOptions.builder().build());
        assertEquals(options, options.toBuilder().build());
        assertEquals(options.hashCode(), options.toBuilder().build().hashCode());
    }

    @Test
    @DisplayName("the setters set their value, toBuilder() keeps every value and the resets clear them")
    void setters() {
        var options = DvEncoderOptions.of(DvProfile.DVCPRO50_625).toBuilder().wide(true).adaptiveDct(false)
                .timeBase(new Rational(1, 90000)).threads(3).build();
        assertEquals(DvProfile.DVCPRO50_625, options.profile());
        assertTrue(options.wide());
        assertFalse(options.adaptiveDct());
        assertEquals(Optional.of(new Rational(1, 90000)), options.timeBase());
        assertEquals(3, options.threads());
        assertEquals(options, options.toBuilder().build());
        assertNotEquals(options, DvEncoderOptions.defaults());
        assertEquals(Optional.empty(), options.toBuilder().automaticTimeBase().build().timeBase());
        assertTrue(options.toString().contains("DVCPRO50_625"));
    }

    @Test
    @DisplayName("build() rejects a negative thread count and a non-positive time base, setters reject null")
    void validation() {
        assertThrows(IllegalArgumentException.class, () -> DvEncoderOptions.builder().threads(-1).build());
        assertThrows(IllegalArgumentException.class, () -> DvEncoderOptions.builder()
                .timeBase(new Rational(0, 1)).build());
        assertThrows(NullPointerException.class, () -> DvEncoderOptions.builder().profile(null));
        assertThrows(NullPointerException.class, () -> DvEncoderOptions.builder().timeBase(null));
    }

    @ParameterizedTest
    @EnumSource(DvProfile.class)
    @DisplayName("each profile names its picture size, frame rate, frame size, scan and aspect ratios")
    void profiles(DvProfile profile) {
        var fifty = profile.name().endsWith("625") || profile.name().endsWith("50");
        var hd = profile.name().startsWith("DVCPRO_HD");
        var bytes = (fifty ? 12 : 10) * 12000 * (hd ? (profile.height() == 720 ? 2 : 4)
                : profile.name().startsWith("DVCPRO50") ? 2 : 1);
        assertEquals(bytes, profile.frameSize());
        assertEquals(profile.height() != 720, profile.interlaced());
        var rate = profile.frameRate();
        if (profile.height() == 720) {
            assertEquals(fifty ? new Rational(50, 1) : new Rational(60000, 1001), rate);
        } else {
            assertEquals(fifty ? new Rational(25, 1) : new Rational(30000, 1001), rate);
        }
        if (hd) {
            assertEquals(profile.sampleAspectRatio(false), profile.sampleAspectRatio(true));
            var sar = profile.sampleAspectRatio(true);
            // DVCPRO HD displays at 16:9: 1920 by 1080 or 1280 by 720
            assertEquals(16 * profile.height() * sar.denominator(), 9 * profile.width() * sar.numerator());
        } else {
            assertNotEquals(profile.sampleAspectRatio(false), profile.sampleAspectRatio(true));
            assertEquals(720, profile.width());
        }
        assertEquals(profile, DvProfile.of(profile.dif()));
    }
}
