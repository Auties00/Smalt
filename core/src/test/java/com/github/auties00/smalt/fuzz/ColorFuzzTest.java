package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.color.ColorTransformOptions;
import com.github.auties00.smalt.image.color.ProfileClass;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzRun;

import java.util.List;

/**
 * Fuzz target of the ICC profile parser and of the color transforms built from a parsed profile: to and from sRGB
 * for a device profile, alone for a device link, between two sRGB profiles for an abstract profile, and alone and
 * to sRGB for a named color profile, whose colors it also lists. Building a transform evaluates the whole pipeline
 * over a grid, so each input builds at most two: the last byte of the input picks the rendering intent and the
 * sample depth, which mutations of that byte explore.
 */
class ColorFuzzTest {
    /** The pixels each transform converts. */
    private static final int PIXELS = 256;

    @FuzzTest
    void icc(byte[] data) {
        var run = Fuzz.start("icc");
        var parsed = run.value("parse", () -> ColorProfile.parse(data));
        if (parsed.isEmpty()) {
            return;
        }
        var profile = parsed.get();
        profile.colorSpace();
        profile.connectionSpace();
        profile.deviceClass();
        profile.version();
        profile.renderingIntent();
        profile.description();
        profile.copyright();
        profile.isMatrixShaper();
        profile.colorInfo();
        profile.isHighDynamicRange();
        profile.isSrgb();
        run.value("named colors", profile::namedColors).ifPresent(colors -> {
            for (var color : colors) {
                profile.namedColor(color.rootName());
                color.connectionSpaceValues();
                color.deviceValues();
            }
        });
        ColorProfile.parse(profile.toByteArray());
        var selector = data[data.length - 1] & 0xff;
        var options = switch (selector % 3) {
            case 0 -> ColorTransformOptions.defaults();
            case 1 -> ColorTransformOptions.relativeColorimetric().toBuilder().blackPointCompensation(true).build();
            default -> ColorTransformOptions.absoluteColorimetric();
        };
        var wide = (selector / 3 & 1) != 0;
        var srgb = ColorProfile.srgb();
        switch (profile.deviceClass()) {
            case DEVICE_LINK -> run.value("alone", () -> ColorTransform.create(List.of(profile), options))
                    .ifPresent(transform -> convert(run, transform, wide));
            case ABSTRACT -> run.value("between sRGB", () -> ColorTransform.create(List.of(srgb, profile, srgb), options))
                    .ifPresent(transform -> convert(run, transform, wide));
            case NAMED_COLOR -> {
                run.value("alone", () -> ColorTransform.create(List.of(profile), options))
                        .ifPresent(transform -> convert(run, transform, wide));
                run.value("to sRGB", () -> ColorTransform.create(List.of(profile, srgb), options))
                        .ifPresent(transform -> convert(run, transform, !wide));
            }
            default -> {
                run.value("to sRGB", () -> ColorTransform.create(profile, srgb, options))
                        .ifPresent(transform -> convert(run, transform, wide));
                if (profile.deviceClass() != ProfileClass.INPUT) {
                    run.value("from sRGB", () -> ColorTransform.create(srgb, profile, options))
                            .ifPresent(transform -> convert(run, transform, !wide));
                }
            }
        }
    }

    private static void convert(FuzzRun run, ColorTransform transform, boolean wide) {
        var in = transform.inputChannels();
        var out = transform.outputChannels();
        if (wide) {
            var shorts = new short[PIXELS * in];
            for (var index = 0; index < shorts.length; index++) {
                shorts[index] = (short) (index * 1793 / in);
            }
            transform.transform(shorts, 0, in, new short[PIXELS * out], 0, out, PIXELS);
        } else {
            var bytes = new byte[PIXELS * in];
            for (var index = 0; index < bytes.length; index++) {
                bytes[index] = (byte) (index * 7 / in);
            }
            transform.transform(bytes, 0, in, new byte[PIXELS * out], 0, out, PIXELS);
        }
        run.output();
    }
}
