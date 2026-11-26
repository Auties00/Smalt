package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.github.auties00.smalt.testkit.Conformance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"skia-icc-fuzz-a2b_too_many_input_channels.icc",
            "skia-icc-fuzz-a2b_too_many_input_channels2.icc", "skia-icc-fuzz-b2a_no_clut.icc",
            "skia-icc-fuzz-b2a_too_few_output_channels.icc", "skia-icc-fuzz-clut_overflow.icc",
            "skia-icc-fuzz-curv_size_overflow.icc", "skia-icc-fuzz-direct_fit_negative_a.icc",
            "skia-icc-fuzz-direct_fit_not_invertible.icc", "skia-icc-fuzz-fit_pq.icc", "skia-icc-fuzz-inf_a.icc",
            "skia-icc-fuzz-infinite_roundtrip.icc", "skia-icc-fuzz-inverse_tf_adb_negative.icc",
            "skia-icc-fuzz-inverse_tf_huge_g.icc", "skia-icc-fuzz-inverse_tf_not_invertible.icc",
            "skia-icc-fuzz-large_g.icc", "skia-icc-fuzz-last_tag_too_small.icc", "skia-icc-fuzz-mangled_trc_tags.icc",
            "skia-icc-fuzz-named_tag_too_small.icc", "skia-icc-fuzz-nan_s.icc", "skia-icc-fuzz-negative_a_plus_b.icc",
            "skia-icc-fuzz-negative_a_when_inverted.icc", "skia-icc-fuzz-negative_g_para.icc",
            "skia-icc-fuzz-one_d_clut.icc", "skia-icc-fuzz-polytf_big_float_to_int_cast.icc",
            "skia-icc-fuzz-polytf_nan_after_update.icc", "skia-icc-fuzz-truncated_curv_tag.icc",
            "skia-icc-fuzz-zero_a.icc", "skia-icc-fuzz-zero_g.icc", "iccdev-calcUnderStack_if.icc",
            "iccdev-v5dspobs-cat8lab.icc", "iccdev-poc-1581-gbd-zero-pcs-channels.icc"})
    @DisplayName("hostile profiles of Skia's fuzz corpus and iccDEV fail only with MediaException, in bounded time")
    void hostile(String name) {
        byte[] data;
        try {
            data = Files.readAllBytes(Conformance.vector(name));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            try {
                exercise(data);
            } catch (MediaException expected) {
                // reported as documented
            } catch (RuntimeException | StackOverflowError unexpected) {
                fail(name + ": " + unexpected, unexpected);
            }
        });
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
