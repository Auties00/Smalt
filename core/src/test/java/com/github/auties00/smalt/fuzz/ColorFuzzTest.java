package com.github.auties00.smalt.fuzz;

import com.code_intelligence.jazzer.junit.FuzzTest;
import com.github.auties00.smalt.image.color.BlackPreservation;
import com.github.auties00.smalt.image.color.ColorProfile;
import com.github.auties00.smalt.image.color.ColorTransform;
import com.github.auties00.smalt.image.color.ColorTransformOptions;
import com.github.auties00.smalt.image.color.ProfileClass;
import com.github.auties00.smalt.image.color.ProfileColorSpace;
import com.github.auties00.smalt.image.color.ProofingOptions;
import com.github.auties00.smalt.image.color.RenderingIntent;
import com.github.auties00.smalt.testkit.fuzz.Fuzz;
import com.github.auties00.smalt.testkit.fuzz.FuzzRun;

import java.util.List;

/**
 * Fuzz target of the ICC profile parser and of the color transforms built from a parsed profile: to and from sRGB
 * for a device profile, alone for a device link, between two sRGB profiles for an abstract profile, and alone and
 * to sRGB for a named color profile, whose colors it also lists; a device profile is also the proofing profile of an
 * sRGB to sRGB soft proof, and a CMYK one the target of a black-preserving CMYK transform. Building a transform
 * evaluates the whole pipeline over a grid, so each input builds at most four: the last byte of the input picks the
 * rendering intent and the sample depth (8-bit, 16-bit or floating point), which mutations of that byte explore. The
 * profile's description is also replaced and the result parsed again.
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
        run.value("retag", () -> ColorProfile.parse(profile.withDescription("fuzz").withCopyright("fuzz")
                .toByteArray()));
        var selector = data[data.length - 1] & 0xff;
        var options = switch (selector % 3) {
            case 0 -> ColorTransformOptions.defaults();
            case 1 -> ColorTransformOptions.relativeColorimetric().toBuilder().blackPointCompensation(true).build();
            default -> ColorTransformOptions.absoluteColorimetric();
        };
        var depth = selector / 3 % 3;
        var srgb = ColorProfile.srgb();
        switch (profile.deviceClass()) {
            case DEVICE_LINK -> run.value("alone", () -> ColorTransform.create(List.of(profile), options))
                    .ifPresent(transform -> convert(run, transform, depth));
            case ABSTRACT -> run.value("between sRGB", () -> ColorTransform.create(List.of(srgb, profile, srgb), options))
                    .ifPresent(transform -> convert(run, transform, depth));
            case NAMED_COLOR -> {
                run.value("alone", () -> ColorTransform.create(List.of(profile), options))
                        .ifPresent(transform -> convert(run, transform, depth));
                run.value("to sRGB", () -> ColorTransform.create(List.of(profile, srgb), options))
                        .ifPresent(transform -> convert(run, transform, (depth + 1) % 3));
            }
            default -> {
                run.value("to sRGB", () -> ColorTransform.create(profile, srgb, options))
                        .ifPresent(transform -> convert(run, transform, depth));
                if (profile.deviceClass() != ProfileClass.INPUT) {
                    run.value("from sRGB", () -> ColorTransform.create(srgb, profile, options))
                            .ifPresent(transform -> convert(run, transform, (depth + 1) % 3));
                }
                if (profile.deviceClass() != ProfileClass.INPUT && !profile.isHighDynamicRange()) {
                    var proofing = ProofingOptions.builder().gamutCheck((selector & 0x80) != 0).build();
                    run.value("proof", () -> ColorTransform.createProofing(srgb, profile, srgb, options, proofing))
                            .ifPresent(transform -> convert(run, transform, (depth + 2) % 3));
                }
                if (profile.colorSpace() == ProfileColorSpace.CMYK && profile.deviceClass() == ProfileClass.OUTPUT) {
                    var preserving = ColorTransformOptions.builder().intent(RenderingIntent.RELATIVE_COLORIMETRIC)
                            .blackPreservation(BlackPreservation.K_ONLY).build();
                    run.value("black only", () -> ColorTransform.create(List.of(profile, profile), preserving))
                            .ifPresent(transform -> convert(run, transform, depth));
                }
            }
        }
    }

    private static void convert(FuzzRun run, ColorTransform transform, int depth) {
        var in = transform.inputChannels();
        var out = transform.outputChannels();
        if (depth == 2) {
            var floats = new float[PIXELS * in];
            for (var index = 0; index < floats.length; index++) {
                floats[index] = (index * 1793 / in % 65536) / 50000.0f - 0.1f;
            }
            transform.transform(floats, 0, in, new float[PIXELS * out], 0, out, PIXELS);
        } else if (depth == 1) {
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
