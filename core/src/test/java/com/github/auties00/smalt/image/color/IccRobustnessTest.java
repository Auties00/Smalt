package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Feeds damaged profiles to the parser and the linker: whatever the damage, only
 * {@link MediaException} may escape, and a transform that does get built must convert pixels
 * without failing.
 */
class IccRobustnessTest {
    private static void exercise(byte[] data) {
        ColorProfile profile;
        try {
            profile = ColorProfile.parse(data);
        } catch (MediaException expected) {
            return;
        }
        profile.description();
        profile.copyright();
        profile.isSrgb();
        profile.isMatrixShaper();
        for (var target : new ColorProfile[]{ColorProfile.srgb(), profile}) {
            ColorTransform transform;
            try {
                transform = ColorTransform.create(profile, target, ColorTransformOptions.relativeColorimetric());
            } catch (MediaException expected) {
                continue;
            }
            var in = new byte[transform.inputChannels() * 64];
            new SplittableRandom(data.length).nextBytes(in);
            var out = new byte[transform.outputChannels() * 64];
            transform.transform(in, 0, transform.inputChannels(), out, 0, transform.outputChannels(), 64);
            var in16 = new short[in.length];
            var out16 = new short[out.length];
            transform.transform(in16, 0, transform.inputChannels(), out16, 0, transform.outputChannels(), 64);
        }
    }

    private static void mutate(SplittableRandom random, byte[] data) {
        switch (random.nextInt(5)) {
            case 0 -> data[random.nextInt(data.length)] = (byte) random.nextInt(256);
            case 1 -> {
                var at = 128 + random.nextInt(Math.max(1, Math.min(data.length - 132, 400)));
                data[at] = (byte) random.nextInt(256);
            }
            case 2 -> {
                var at = random.nextInt(Math.max(1, data.length - 4));
                data[at] = (byte) 0xFF;
                data[at + 1] = (byte) 0xFF;
            }
            case 3 -> {
                var at = random.nextInt(data.length);
                data[at] = 0;
            }
            default -> {
                var at = 132 + 12 * random.nextInt(8);
                if (at + 12 < data.length) {
                    data[at + 4 + random.nextInt(8)] = (byte) random.nextInt(256);
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"compact-icc-sRGB-v2-micro.icc", "compact-icc-DisplayP3-v4.icc", "compact-icc-sGrey-v4.icc",
            "skia-icc-color.org-sRGB_ICC_v4_Appearance.icc", "skia-icc-mobile-Display_P3_LUT.icc",
            "skia-icc-srgb_lab_pcs.icc", "skia-icc-misc-MartiMaria_browsertest_A2B.icc", "skia-icc-color.org-sRGB2014.icc"})
    @DisplayName("mutated profiles fail only with MediaException")
    void mutations(String name) {
        var original = TestProfiles.bytes(name);
        var random = new SplittableRandom(name.hashCode());
        for (var round = 0; round < 120; round++) {
            var data = original.clone();
            var edits = 1 + random.nextInt(4);
            for (var i = 0; i < edits; i++) {
                mutate(random, data);
            }
            if (random.nextInt(6) == 0) {
                data = java.util.Arrays.copyOf(data, random.nextInt(data.length));
            }
            try {
                exercise(data);
            } catch (MediaException expected) {
                // reported as documented
            } catch (RuntimeException | StackOverflowError unexpected) {
                fail(name + " round " + round + ": " + unexpected, unexpected);
            }
        }
    }

    @Test
    @DisplayName("truncated data and a wrong signature are rejected")
    void truncated() {
        var data = ColorProfile.srgb().toByteArray();
        assertThrows(MediaException.class, () -> ColorProfile.parse(java.util.Arrays.copyOf(data, 100)));
        var corrupted = data.clone();
        corrupted[36] = 'x';
        assertThrows(MediaException.Malformed.class, () -> ColorProfile.parse(corrupted));
    }
}
