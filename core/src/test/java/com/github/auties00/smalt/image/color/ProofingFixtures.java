package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.image.Chromaticity;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the Little CMS reference outputs of {@code tools/testdata/icc-proofing/smalt-lcmsproof.c} from
 * {@code proofing/}, builds the input sets that program documents, and resolves its profile names: the pinned
 * third-party profiles, the advanced test profiles, and, for the profiles Little CMS creates itself, the profile Smalt
 * builds with the same parameters.
 */
final class ProofingFixtures {
    private ProofingFixtures() {
    }

    // writes the Smalt profiles that tools/testdata/icc-proofing/build.sh hands to Little CMS
    public static void main(String[] arguments) throws IOException {
        var directory = java.nio.file.Path.of(arguments[0]);
        java.nio.file.Files.createDirectories(directory);
        for (var name : SMALT_PROFILES) {
            java.nio.file.Files.write(directory.resolve(name + ".icc"), profile(name).toByteArray());
        }
    }

    static byte[] resource(String name) {
        try (InputStream in = ProofingFixtures.class.getResourceAsStream("proofing/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing resource proofing/" + name);
            }
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static boolean hasResource(String name) {
        return ProofingFixtures.class.getResource("proofing/" + name) != null;
    }

    static List<String[]> table(String name) {
        var rows = new ArrayList<String[]>();
        for (var line : new String(resource(name), StandardCharsets.UTF_8).split("\r?\n")) {
            if (!line.isEmpty() && !line.startsWith("#")) {
                rows.add(line.split("\t", -1));
            }
        }
        return rows;
    }

    static int[] words(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var out = new int[bytes.length / 2];
        for (var i = 0; i < out.length; i++) {
            out[i] = buffer.getShort() & 0xFFFF;
        }
        return out;
    }

    static float[] floats(byte[] bytes) {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        var out = new float[bytes.length / 4];
        for (var i = 0; i < out.length; i++) {
            out[i] = buffer.getFloat();
        }
        return out;
    }

    static long lcg(long state) {
        return state * 6364136223846793005L + 1442695040888963407L;
    }

    static int power(int base, int exponent) {
        var total = 1;
        for (var i = 0; i < exponent; i++) {
            total *= base;
        }
        return total;
    }

    static int levels8(int channels) {
        return channels == 3 ? 17 : channels == 4 ? 9 : 4;
    }

    static int levels16(int channels) {
        return channels == 1 ? 257 : channels == 3 ? 9 : channels == 4 ? 5 : 3;
    }

    static int levelsFloat(int channels) {
        return channels == 1 ? 101 : channels == 3 ? 9 : channels == 4 ? 6 : 4;
    }

    static byte[] inputs8(int channels) {
        if (channels == 1) {
            var out = new byte[256];
            for (var i = 0; i < 256; i++) {
                out[i] = (byte) i;
            }
            return out;
        }
        var levels = levels8(channels);
        var lattice = power(levels, channels);
        var out = new byte[(lattice + 2048) * channels];
        for (var index = 0; index < lattice; index++) {
            var rest = index;
            for (var c = channels - 1; c >= 0; c--) {
                out[index * channels + c] = (byte) (((rest % levels) * 255 + (levels - 1) / 2) / (levels - 1));
                rest /= levels;
            }
        }
        var state = 0x9E3779B97F4A7C15L;
        for (var i = lattice * channels; i < out.length; i++) {
            state = lcg(state);
            out[i] = (byte) (state >>> 56);
        }
        return out;
    }

    static short[] inputs16(int channels) {
        var levels = levels16(channels);
        var lattice = power(levels, channels);
        var out = new short[(lattice + 1024) * channels];
        for (var index = 0; index < lattice; index++) {
            var rest = index;
            for (var c = channels - 1; c >= 0; c--) {
                out[index * channels + c] = (short) (((rest % levels) * 65535 + (levels - 1) / 2) / (levels - 1));
                rest /= levels;
            }
        }
        var state = 0xD1B54A32D192ED03L;
        for (var i = lattice * channels; i < out.length; i++) {
            state = lcg(state);
            out[i] = (short) (state >>> 48);
        }
        return out;
    }

    static double[] range(ProfileColorSpace space, int channel) {
        if (space == ProfileColorSpace.LAB) {
            return channel == 0 ? new double[]{0, 100} : new double[]{-128, 127};
        }
        return new double[]{0, 1};
    }

    static float[] inputsFloat(ProfileColorSpace space, int channels) {
        var levels = levelsFloat(channels);
        var lattice = power(levels, channels);
        var out = new float[(lattice + 256) * channels];
        for (var index = 0; index < lattice; index++) {
            var rest = index;
            for (var c = channels - 1; c >= 0; c--) {
                var r = range(space, c);
                out[index * channels + c] = (float) (r[0] + (r[1] - r[0]) * (rest % levels) / (levels - 1));
                rest /= levels;
            }
        }
        var state = 0x2545F4914F6CDD1DL;
        for (var i = lattice * channels; i < out.length; i++) {
            state = lcg(state);
            var u = (double) (state >>> 40) / 16777216.0;
            var r = range(space, i % channels);
            out[i] = (float) (r[0] + (r[1] - r[0]) * (u * 1.4 - 0.2));
        }
        return out;
    }

    private static final Map<String, ColorProfile> PROFILES = new LinkedHashMap<>();

    static synchronized ColorProfile profile(String name) {
        // resolve may load other profiles, so it cannot run inside computeIfAbsent
        var profile = PROFILES.get(name);
        if (profile == null) {
            profile = resolve(name);
            PROFILES.put(name, profile);
        }
        return profile;
    }

    static List<ColorProfile> profiles(String names) {
        var list = new ArrayList<ColorProfile>();
        for (var name : names.split(",")) {
            list.add(profile(name));
        }
        return list;
    }

    private static ColorProfile resolve(String name) {
        return switch (name) {
            case "srgb" -> TestProfiles.profile("compact-icc-sRGB-v4.icc");
            case "p3" -> TestProfiles.profile("compact-icc-DisplayP3-v4.icc");
            case "prophoto" -> TestProfiles.profile("compact-icc-ProPhoto-v4.icc");
            case "sgrey" -> TestProfiles.profile("compact-icc-sGrey-v4.icc");
            case "fogra" -> TestProfiles.profile("skia-icc-misc-Coated_FOGRA39_CMYK.icc");
            case "swop" -> TestProfiles.profile("skia-icc-misc-US_Web_Coated_SWOP_CMYK.icc");
            case "japan" -> TestProfiles.profile("skia-icc-misc-Japan_Color_2001_Coated.icc");
            case "mpe-rgb", "mpe-cmyk", "named-cmyk" -> AdvancedProfilesTest.profile(name);
            case "lcms-lab4", "smalt-lab" -> ColorProfile.lab();
            case "lcms-xyz", "smalt-xyz" -> ColorProfile.xyz();
            case "lcms-ink280", "smalt-ink280" -> ColorProfile.inkLimiting(280);
            case "lcms-bchsw", "smalt-bchsw" -> ColorProfile.labAdjustment(bchsw());
            case "lcms-lincmyk", "smalt-lincmyk" -> ColorProfile.linearization(ProfileColorSpace.CMYK, linearCurves());
            case "lcms-rgbparam", "smalt-rgbparam" -> rgbParametric();
            case "lcms-graytab", "smalt-graytab" -> ColorProfile.gray(d50(), ProfileCurve.sampled(table()));
            case "smalt-link-sgrey-srgb" -> ColorProfile.deviceLink(ColorTransform.create(profile("sgrey"),
                    profile("srgb")));
            case "smalt-link-named" -> ColorProfile.deviceLink(ColorTransform.create(List.of(profile("named-cmyk"),
                    profile("srgb"))));
            case "smalt-retag-srgb" -> profile("srgb").withDescription("Smalt retagged sRGB")
                    .withCopyright("No copyright, test profile");
            default -> throw new IllegalArgumentException("unknown profile " + name);
        };
    }

    static final List<String> SMALT_PROFILES = List.of("smalt-rgbparam", "smalt-graytab", "smalt-lab", "smalt-xyz",
            "smalt-ink280", "smalt-bchsw", "smalt-lincmyk", "smalt-link-sgrey-srgb", "smalt-link-named",
            "smalt-retag-srgb");

    static LabAdjustments bchsw() {
        return LabAdjustments.builder().brightness(5).contrast(1.1).hue(15).saturation(1.2).whiteBalance(5000, 6500)
                .gridPoints(17).build();
    }

    static float[] table() {
        int[] samples = {0, 1200, 4000, 9000, 16000, 25000, 34000, 44000, 53000, 61000, 65535};
        var out = new float[samples.length];
        for (var i = 0; i < samples.length; i++) {
            out[i] = (float) (samples[i] / 65535.0);
        }
        return out;
    }

    static List<ProfileCurve> linearCurves() {
        return List.of(ProfileCurve.gamma(1.8), ProfileCurve.gamma(0.7), ProfileCurve.parametric(2, 2.0, 0.9, 0.1, 0.02),
                ProfileCurve.sampled(table()));
    }

    static Chromaticity d50() {
        var sum = ColorMath.D50_X + ColorMath.D50_Y + ColorMath.D50_Z;
        return new Chromaticity(ColorMath.D50_X / sum, ColorMath.D50_Y / sum);
    }

    static ColorProfile rgbParametric() {
        return ColorProfile.rgb(new Chromaticity(0.68, 0.32), new Chromaticity(0.265, 0.69),
                new Chromaticity(0.15, 0.06), new Chromaticity(0.3127, 0.3290),
                ProfileCurve.parametric(4, 2.6, 0.95, 0.05, 0.08, 0.06, 0.01, 0.0),
                ProfileCurve.parametric(2, 2.0, 0.9, 0.1, 0.02), ProfileCurve.gamma(2.2));
    }

    static RenderingIntent intent(char letter) {
        return switch (letter) {
            case 'P' -> RenderingIntent.PERCEPTUAL;
            case 'R' -> RenderingIntent.RELATIVE_COLORIMETRIC;
            case 'S' -> RenderingIntent.SATURATION;
            default -> RenderingIntent.ABSOLUTE_COLORIMETRIC;
        };
    }

    static ColorTransformOptions options(String intent, String bpc) {
        var builder = ColorTransformOptions.builder().blackPointCompensation(bpc.equals("1"));
        if (intent.length() == 2) {
            builder.intent(intent(intent.charAt(1)))
                    .blackPreservation(intent.charAt(0) == 'K' ? BlackPreservation.K_ONLY : BlackPreservation.K_PLANE);
        } else {
            builder.intent(intent(intent.charAt(0)));
        }
        return builder.build();
    }

    static ProofingOptions proofing(String intent, String gamut, String alarm) {
        var parts = alarm.split(",");
        var values = new int[parts.length];
        for (var i = 0; i < parts.length; i++) {
            values[i] = Integer.parseInt(parts[i], 16);
        }
        return ProofingOptions.builder().intent(intent(intent.charAt(0))).gamutCheck(gamut.equals("1")).alarm(values)
                .build();
    }

    static ColorTransform transform(String[] row) {
        var options = options(row[3], row[4]);
        if (row[1].equals("proof")) {
            var list = profiles(row[2]);
            return ColorTransform.createProofing(list.get(0), list.get(1), list.get(2), options,
                    proofing(row[5], row[6], row[7]));
        }
        return ColorTransform.create(profiles(row[2]), options);
    }
}
