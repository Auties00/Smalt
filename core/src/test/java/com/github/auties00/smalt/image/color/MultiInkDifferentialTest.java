package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks multi-ink ({@code nCLR}) profiles of 2 to 15 inks against Little CMS 2.17, through the profiles and
 * reference outputs that {@code tools/testdata/icc-multi-ink/smalt-lcmsinks.c} writes into {@code inks/}: 8-bit
 * transforms within one code value of Little CMS's, the linked pipeline within one 16-bit unit of Little CMS's
 * unoptimized one, 16-bit transforms at least as close to that pipeline as Little CMS's own 16-bit transforms (in
 * root mean square or in maximum error), and floating-point transforms within {@code 1e-4} of Little CMS's (in
 * units of 100 for L*a*b* outputs); and the colorant tables as Little CMS reads them.
 */
class MultiInkDifferentialTest {
    static byte[] resource(String name) {
        try (InputStream in = MultiInkDifferentialTest.class.getResourceAsStream("inks/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("missing resource " + name);
            }
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static ColorProfile profile(String name) {
        return ColorProfile.parse(resource(name + ".icc"));
    }

    static List<String[]> table(String name) {
        var rows = new ArrayList<String[]>();
        for (var line : new String(resource(name), StandardCharsets.UTF_8).split("\r?\n")) {
            if (!line.isEmpty()) {
                rows.add(line.split("\t", -1));
            }
        }
        return rows;
    }

    static long lcg(long state) {
        return state * 6364136223846793005L + 1442695040888963407L;
    }

    static int levels8(int channels) {
        return switch (channels) {
            case 2 -> 17;
            case 3, 4 -> 9;
            case 5, 6 -> 4;
            case 7, 8 -> 3;
            default -> 0;
        };
    }

    static int levels16(int channels) {
        return switch (channels) {
            case 2, 3 -> 9;
            case 4 -> 5;
            case 5, 6 -> 3;
            case 7, 8 -> 2;
            default -> 0;
        };
    }

    static int levelsFloat(int channels) {
        return switch (channels) {
            case 2 -> 17;
            case 3, 4 -> 6;
            case 5 -> 4;
            case 6 -> 3;
            case 7, 8 -> 2;
            default -> 0;
        };
    }

    /**
     * Returns the lattice of the input sets as fractions of full scale, the first channel slowest, or the corners
     * (all zero, each channel alone at full scale, all at full scale) when {@code levels} is zero.
     */
    static double[][] lattice(int channels, int levels) {
        if (levels == 0) {
            var out = new double[channels + 2][channels];
            for (var c = 0; c < channels; c++) {
                out[c + 1][c] = 1;
                out[channels + 1][c] = 1;
            }
            return out;
        }
        var total = 1;
        for (var c = 0; c < channels; c++) {
            total *= levels;
        }
        var out = new double[total][channels];
        for (var index = 0; index < total; index++) {
            var rest = index;
            for (var c = channels - 1; c >= 0; c--) {
                out[index][c] = (double) (rest % levels) / (levels - 1);
                rest /= levels;
            }
        }
        return out;
    }

    static byte[] inputs8(int channels) {
        var grid = lattice(channels, levels8(channels));
        var out = new byte[(grid.length + 1024) * channels];
        for (var p = 0; p < grid.length; p++) {
            for (var c = 0; c < channels; c++) {
                out[p * channels + c] = (byte) Math.floor(grid[p][c] * 255 + 0.5);
            }
        }
        var state = 0x9E3779B97F4A7C15L;
        for (var i = grid.length * channels; i < out.length; i++) {
            state = lcg(state);
            out[i] = (byte) (state >>> 56);
        }
        return out;
    }

    static short[] inputs16(int channels) {
        var grid = lattice(channels, levels16(channels));
        var out = new short[(grid.length + 1024) * channels];
        for (var p = 0; p < grid.length; p++) {
            for (var c = 0; c < channels; c++) {
                out[p * channels + c] = (short) Math.floor(grid[p][c] * 65535 + 0.5);
            }
        }
        var state = 0xD1B54A32D192ED03L;
        for (var i = grid.length * channels; i < out.length; i++) {
            state = lcg(state);
            out[i] = (short) (state >>> 48);
        }
        return out;
    }

    static float[] inputsFloat(int channels) {
        var grid = lattice(channels, levelsFloat(channels));
        var out = new float[(grid.length + 256) * channels];
        for (var p = 0; p < grid.length; p++) {
            for (var c = 0; c < channels; c++) {
                out[p * channels + c] = (float) grid[p][c];
            }
        }
        var state = 0x2545F4914F6CDD1DL;
        for (var i = grid.length * channels; i < out.length; i++) {
            state = lcg(state);
            var u = (double) (state >>> 40) / 16777216.0;
            out[i] = (float) (u * 1.4 - 0.2);
        }
        return out;
    }

    static int[] words(byte[] data) {
        var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        var out = new int[data.length / 2];
        for (var i = 0; i < out.length; i++) {
            out[i] = buffer.getShort() & 0xFFFF;
        }
        return out;
    }

    static float[] floats(byte[] data) {
        var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        var out = new float[data.length / 4];
        for (var i = 0; i < out.length; i++) {
            out[i] = buffer.getFloat();
        }
        return out;
    }

    static List<ColorProfile> chain(String names) {
        var chain = new ArrayList<ColorProfile>();
        for (var name : names.split(",")) {
            chain.add(profile(name));
        }
        return chain;
    }

    static Stream<Arguments> cases() {
        return table("cases.tsv").stream().map(row -> Arguments.of(row[0], row[1], row[2], row[3], row[4]));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("transforms match Little CMS at every depth")
    void matchesLittleCms(String name, String profiles, String intent, String bpc, String input) {
        var transform = ColorTransform.create(chain(profiles), LcmsOracleTest.options(intent, bpc));
        var channels = Integer.parseInt(input);
        assertEquals(channels, transform.inputChannels());
        var outputs = transform.outputChannels();

        var in8 = inputs8(channels);
        var actual8 = new byte[in8.length / channels * outputs];
        transform.transform(in8, 0, channels, actual8, 0, outputs, in8.length / channels);
        Tolerance.offByOne().assertWithin(name + " 8-bit", resource(name + ".8"), actual8);

        var in16 = inputs16(channels);
        var pixels = in16.length / channels;
        var optimized16 = words(resource(name + ".16"));
        var expected16 = words(resource(name + ".x16"));
        var actual16 = new short[pixels * outputs];
        transform.transform(in16, 0, channels, actual16, 0, outputs, pixels);
        assertEquals(expected16.length, actual16.length, name + ": 16-bit output length");
        var max16 = 0;
        var squares = 0.0;
        var squaresOptimized = 0.0;
        var maxOptimized = 0;
        for (var i = 0; i < actual16.length; i++) {
            var diff = Math.abs((actual16[i] & 0xFFFF) - expected16[i]);
            max16 = Math.max(max16, diff);
            squares += (double) diff * diff;
            var optimized = optimized16[i] - expected16[i];
            squaresOptimized += (double) optimized * optimized;
            maxOptimized = Math.max(maxOptimized, Math.abs(optimized));
        }
        var rms = Math.sqrt(squares / actual16.length);
        var rmsOptimized = Math.sqrt(squaresOptimized / actual16.length);
        var pipeline = transform.pipeline();
        var in = new int[channels];
        var out = new int[outputs];
        var evaluated = new int[pixels * outputs];
        for (var p = 0; p < pixels; p++) {
            for (var c = 0; c < channels; c++) {
                in[c] = in16[p * channels + c] & 0xFFFF;
            }
            pipeline.evaluate16(in, out);
            System.arraycopy(out, 0, evaluated, p * outputs, outputs);
        }
        Tolerance.offByOne().assertWithin(name + " pipeline", expected16, evaluated);

        var inFloat = inputsFloat(channels);
        var expectedFloat = floats(resource(name + ".f32"));
        var actualFloat = new float[inFloat.length / channels * outputs];
        transform.transform(inFloat, 0, channels, actualFloat, 0, outputs, inFloat.length / channels);
        assertEquals(expectedFloat.length, actualFloat.length, name + ": float output length");
        var maxFloat = 0.0;
        var worstFloat = -1;
        for (var i = 0; i < actualFloat.length; i++) {
            // L*a*b* outputs are in units of 100, device and XYZ outputs in units of 1
            var scale = transform.target().colorSpace() == ProfileColorSpace.LAB
                    && transform.target().deviceClass() != ProfileClass.OUTPUT ? 100.0 : 1.0;
            var diff = Math.abs(actualFloat[i] - expectedFloat[i]) / scale;
            if (diff > maxFloat) {
                maxFloat = diff;
                worstFloat = i;
            }
        }
        System.out.printf("inks %-24s 16-bit max %5d rms %8.2f, Little CMS max %5d rms %8.2f; float max %.2e%n",
                name, max16, rms, maxOptimized, rmsOptimized, maxFloat);
        assertTrue(rms <= rmsOptimized + 0.5 || max16 <= maxOptimized + 1, name + ": 16-bit rms error " + rms
                + " and max error " + max16 + ", Little CMS " + rmsOptimized + " and " + maxOptimized);
        assertTrue(maxFloat <= 1e-4, name + ": float max difference " + maxFloat + " at sample " + worstFloat
                + ": " + (worstFloat < 0 ? "" : actualFloat[worstFloat] + " instead of " + expectedFloat[worstFloat]));
    }

    @Test
    @DisplayName("the fixtures are the ones the generator's manifest pins")
    void fixtures() throws IOException, NoSuchAlgorithmException {
        var manifest = Files.readAllLines(Path.of("..", "tools", "testdata", "icc-multi-ink", "data.sha256"));
        assertEquals(118, manifest.size());
        for (var line : manifest) {
            var parts = line.split(" [ *]");
            var digest = MessageDigest.getInstance("SHA-256").digest(resource(parts[1]));
            assertEquals(parts[0], HexFormat.of().formatHex(digest), parts[1]);
        }
    }

    @Test
    @DisplayName("the gamut check of a proof on twelve or fifteen inks checks each color instead of a table")
    void gamutCheckOfManyInks() {
        var srgb = profile("lcms-srgb");
        var alarm = new int[]{0xFFFF, 0, 0xFFFF};
        var proofing = ProofingOptions.builder().gamutCheck(true).alarm(alarm).build();
        var options = ColorTransformOptions.defaults();
        for (var ink : List.of("ink12", "ink15")) {
            // the 7-point table of the check would hold 7^12 or 7^15 nodes, beyond any Java array
            var checked = ColorTransform.createProofing(srgb, profile(ink), srgb, options, proofing);
            var unchecked = ColorTransform.createProofing(srgb, profile(ink), srgb, options,
                    ProofingOptions.defaults());
            var in8 = inputs8(3);
            var pixels = in8.length / 3;
            var wide = new short[in8.length];
            for (var i = 0; i < in8.length; i++) {
                wide[i] = (short) ((in8[i] & 0xFF) * 257);
            }
            var marks = LcmsProofingDifferentialTest.marks(checked, wide, pixels);
            var a = new byte[in8.length];
            var b = new byte[in8.length];
            checked.transform(in8, 0, 3, a, 0, 3, pixels);
            unchecked.transform(in8, 0, 3, b, 0, 3, pixels);
            var outside = 0;
            for (var p = 0; p < pixels; p++) {
                outside += marks[p] ? 1 : 0;
                for (var c = 0; c < 3; c++) {
                    var expected = marks[p] ? ColorMath.wordToByte(alarm[c]) : b[p * 3 + c] & 0xFF;
                    assertEquals(expected, a[p * 3 + c] & 0xFF, ink + " pixel " + p);
                }
            }
            assertTrue(outside > 0 && outside < pixels, ink + ": " + outside + " of " + pixels + " colors marked");
            // white paper takes no ink, so it is inside the gamut
            var white = LcmsProofingDifferentialTest.marks(checked, new short[]{-1, -1, -1}, 1);
            assertTrue(!white[0], ink + ": white marked out of gamut");
        }
    }

    @Test
    @DisplayName("multi-ink profiles report their space, channel count and colorants as Little CMS reads them")
    void colorants() {
        var rows = table("colorants.tsv");
        assertEquals(67, rows.size());
        for (var row : rows) {
            var profile = profile(row[0]);
            var list = row[1].equals("clot") ? profile.outputColorants() : profile.colorants();
            var colorant = list.get(Integer.parseInt(row[2]));
            assertEquals(row[3], colorant.name(), row[0]);
            var words = row[4].split(",");
            var values = colorant.connectionSpaceValues();
            if (colorant.connectionSpace() == ProfileColorSpace.XYZ) {
                for (var c = 0; c < 3; c++) {
                    assertEquals(Integer.parseInt(words[c]) / 32768.0, values[c], 1e-12, row[0]);
                }
            } else {
                assertEquals(ProfileColorSpace.LAB, colorant.connectionSpace());
                assertEquals(Integer.parseInt(words[0]) * 100.0 / 65535.0, values[0], 1e-12, row[0]);
                assertEquals(Integer.parseInt(words[1]) * 255.0 / 65535.0 - 128.0, values[1], 1e-12, row[0]);
                assertEquals(Integer.parseInt(words[2]) * 255.0 / 65535.0 - 128.0, values[2], 1e-12, row[0]);
            }
        }
        for (var name : List.of("ink2", "ink3-lut8", "ink5", "ink6-xyz", "ink7", "ink8", "ink12", "ink15")) {
            var profile = profile(name);
            var inks = Integer.parseInt(name.substring(3).split("-")[0]);
            assertEquals(ProfileColorSpace.INK, profile.colorSpace(), name);
            assertEquals(inks, profile.channels(), name);
            assertEquals(ProfileClass.OUTPUT, profile.deviceClass(), name);
            assertEquals(name.equals("ink3-lut8") ? 0 : inks, profile.colorants().size(), name);
            assertTrue(profile.outputColorants().isEmpty(), name);
        }
        assertEquals(ProfileColorSpace.XYZ, profile("ink6-xyz").colorants().getFirst().connectionSpace());
        var five = profile("ink5").colorants();
        assertEquals(List.of("Cyan", "Magenta", "Yellow", "Black", "Orange"),
                five.stream().map(Colorant::name).toList());
        for (var colorant : five) {
            assertTrue(colorant.name().equals("Black") || colorant.connectionSpaceValues()[0]
                    > five.get(3).connectionSpaceValues()[0], colorant.toString());
        }
        assertEquals(List.of("Black", "Cyan", "Magenta", "Yellow", "Orange", "Green", "Violet", "Red"),
                profile("ink8").colorants().stream().map(Colorant::name).toList());
        var link = profile("link-ink5-ink7");
        assertEquals(ProfileClass.DEVICE_LINK, link.deviceClass());
        assertEquals(ProfileColorSpace.INK, link.colorSpace());
        assertEquals(ProfileColorSpace.INK, link.connectionSpace());
        assertEquals(5, link.colorants().size());
        assertEquals(7, link.outputColorants().size());
        assertEquals("Violet", link.outputColorants().get(6).name());
    }

    @Test
    @DisplayName("transforms report the spaces and colorants of their source and target channels")
    void transformColorants() {
        var ink8 = profile("ink8");
        var srgb = ColorProfile.srgb();
        var toSrgb = ColorTransform.create(ink8, srgb);
        assertEquals(ProfileColorSpace.INK, toSrgb.inputColorSpace());
        assertEquals(ProfileColorSpace.RGB, toSrgb.outputColorSpace());
        assertEquals(ink8.colorants(), toSrgb.inputColorants());
        assertEquals("Black", toSrgb.inputColorants().getFirst().name());
        assertTrue(toSrgb.outputColorants().isEmpty());
        var fromSrgb = ColorTransform.create(srgb, ink8);
        assertEquals(ProfileColorSpace.INK, fromSrgb.outputColorSpace());
        assertEquals(ink8.colorants(), fromSrgb.outputColorants());
        assertTrue(fromSrgb.inputColorants().isEmpty());
        var toLab = ColorTransform.create(List.of(ink8));
        assertEquals(ProfileColorSpace.LAB, toLab.outputColorSpace());
        assertTrue(toLab.outputColorants().isEmpty());
        var link = ColorTransform.create(List.of(profile("link-ink5-ink7")));
        assertEquals(5, link.inputChannels());
        assertEquals(7, link.outputChannels());
        assertEquals(ProfileColorSpace.INK, link.outputColorSpace());
        assertEquals(profile("link-ink5-ink7").outputColorants(), link.outputColorants());
        assertEquals(profile("ink5").colorants().stream().map(Colorant::name).toList(),
                link.inputColorants().stream().map(Colorant::name).toList());
        var chain = ColorTransform.create(List.of(profile("link-ink5-ink7"), profile("ink7"), srgb));
        assertEquals(ProfileColorSpace.RGB, chain.outputColorSpace());
        assertTrue(chain.outputColorants().isEmpty());
    }

    @Test
    @DisplayName("an ink linearization is an nCLR device link of as many channels as curves")
    void inkLinearization() {
        var curves = new ArrayList<ProfileCurve>();
        for (var i = 0; i < 7; i++) {
            curves.add(ProfileCurve.gamma(1.0 + i * 0.1));
        }
        var link = ColorProfile.linearization(ProfileColorSpace.INK, curves);
        assertEquals(ProfileColorSpace.INK, link.colorSpace());
        assertEquals(7, link.channels());
        assertEquals("7CLR", new String(link.toByteArray(), 16, 4, StandardCharsets.US_ASCII));
        var transform = ColorTransform.create(List.of(ColorProfile.parse(link.toByteArray())));
        var in = new float[]{0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f};
        var out = new float[7];
        transform.transform(in, 0, 7, out, 0, 7, 1);
        for (var i = 0; i < 7; i++) {
            assertEquals(Math.pow(0.5, 1.0 + i * 0.1), out[i], 2e-4, "channel " + i);
        }
        var fifteen = ColorProfile.linearization(ProfileColorSpace.INK, Collections.nCopies(15,
                ProfileCurve.gamma(1.0)));
        assertEquals(15, fifteen.channels());
        assertEquals("FCLR", new String(fifteen.toByteArray(), 16, 4, StandardCharsets.US_ASCII));
        assertThrows(IllegalArgumentException.class, () -> ColorProfile.linearization(ProfileColorSpace.INK,
                List.of(ProfileCurve.gamma(1.0))));
        assertThrows(IllegalArgumentException.class, () -> ColorProfile.linearization(ProfileColorSpace.INK,
                Collections.nCopies(16, ProfileCurve.gamma(1.0))));
    }

    @Test
    @DisplayName("damaged colorant tables are malformed")
    void damagedColorants() {
        var bytes = resource("ink5.icc");
        var at = tagOffset(bytes, "clrt");
        var many = bytes.clone();
        many[at + 11] = 16;
        assertThrows(MediaException.Malformed.class, () -> ColorProfile.parse(many).colorants());
        var truncated = bytes.clone();
        truncated[at + 11] = 9;
        assertThrows(MediaException.Malformed.class, () -> ColorProfile.parse(truncated).colorants());
        var typed = bytes.clone();
        typed[at] = 'x';
        assertThrows(MediaException.Malformed.class, () -> ColorProfile.parse(typed).colorants());
        // a transform does not read the table until asked for its colorants
        var transform = ColorTransform.create(ColorProfile.parse(typed), ColorProfile.srgb());
        assertEquals(5, transform.inputChannels());
        assertThrows(MediaException.Malformed.class, transform::inputColorants);
        // a blank name, and a name filling the 32 bytes of its field without a terminator
        var named = bytes.clone();
        named[at + 12] = 0;
        for (var i = 0; i < 32; i++) {
            named[at + 12 + 38 + i] = (byte) ('A' + i % 26);
        }
        var colorants = ColorProfile.parse(named).colorants();
        assertEquals("", colorants.get(0).name());
        assertEquals(32, colorants.get(1).name().length());
        assertArrayEquals(profile("ink5").colorants().get(2).connectionSpaceValues(),
                colorants.get(2).connectionSpaceValues());
    }

    @Test
    @DisplayName("device links built from ink transforms carry the colorants of both ends")
    void deviceLinkColorants() {
        var ink5 = profile("ink5");
        var ink7 = profile("ink7");
        var link = ColorProfile.parse(ColorProfile.deviceLink(ColorTransform.create(ink5, ink7)).toByteArray());
        assertEquals(ProfileColorSpace.INK, link.colorSpace());
        assertEquals(ProfileColorSpace.INK, link.connectionSpace());
        assertColorants(ink5.colorants(), link.colorants());
        assertColorants(ink7.colorants(), link.outputColorants());
        var transform = ColorTransform.create(List.of(link));
        assertEquals(7, transform.outputChannels());
        assertEquals(link.outputColorants(), transform.outputColorants());

        // XYZ colorants are written as L*a*b*, as device links hold them
        var xyz = profile("ink6-xyz");
        var fromXyz = ColorProfile.deviceLink(ColorTransform.create(xyz, ColorProfile.srgb()));
        var expected = new ArrayList<Colorant>();
        for (var colorant : xyz.colorants()) {
            expected.add(new Colorant(colorant.name(), ProfileColorSpace.LAB,
                    ColorMath.xyzToLab(colorant.connectionSpaceValues())));
        }
        assertColorants(expected, fromXyz.colorants());
        assertTrue(fromXyz.outputColorants().isEmpty());
        assertTrue(ColorProfile.deviceLink(ColorTransform.create(ColorProfile.srgb(), ColorProfile.displayP3()))
                .colorants().isEmpty());
    }

    /**
     * Checks colorants written as 16-bit L*a*b* against the originals, within half a step of the encoding.
     */
    static void assertColorants(List<Colorant> expected, List<Colorant> actual) {
        assertEquals(expected.size(), actual.size());
        for (var i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).name(), actual.get(i).name());
            assertEquals(ProfileColorSpace.LAB, actual.get(i).connectionSpace());
            var want = expected.get(i).connectionSpaceValues();
            var got = actual.get(i).connectionSpaceValues();
            assertEquals(want[0], got[0], 50.0 / 65535 + 1e-9, expected.get(i).name());
            assertEquals(want[1], got[1], 127.5 / 65535 + 1e-9, expected.get(i).name());
            assertEquals(want[2], got[2], 127.5 / 65535 + 1e-9, expected.get(i).name());
        }
    }

    static int tagOffset(byte[] profile, String signature) {
        var buffer = ByteBuffer.wrap(profile);
        var count = buffer.getInt(128);
        for (var i = 0; i < count; i++) {
            var entry = 132 + i * 12;
            if (new String(profile, entry, 4, StandardCharsets.US_ASCII).equals(signature)) {
                return buffer.getInt(entry + 4);
            }
        }
        throw new IllegalArgumentException("no tag " + signature);
    }
}
