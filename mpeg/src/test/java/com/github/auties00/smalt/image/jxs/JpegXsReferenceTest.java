package com.github.auties00.smalt.image.jxs;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.jxs.internal.JxsLayout;
import com.github.auties00.smalt.image.jxs.internal.JxsWeights;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Runs the reference software of ISO/IEC 21122-5 ({@code smalt-jxsdec} and {@code smalt-jxsenc}, built by
 * {@code tools/testdata/jxs-oracle}) both ways: the reference decoder must reconstruct Smalt's codestreams exactly as
 * Smalt's decoder does, and Smalt's decoder must reconstruct the reference encoder's codestreams exactly as the
 * reference decoder does, over the coding tools the conformance suite leaves out or covers thinly (separate signs,
 * both run modes, both quantizers, narrow precincts, raw bit-plane counts, the extended nonlinearity, suppressed
 * decompositions, Bayer patterns and exponents, every profile).
 */
@DisplayName("JPEG XS against the ISO/IEC 21122-5 reference software")
class JpegXsReferenceTest {
    private static final Tolerance TOLERANCE = Tolerance.exact();
    private static final Duration TIMEOUT = Duration.ofMinutes(2);
    private static final MediaLimits LIMITS = MediaLimits.DEFAULT;

    /**
     * Decodes a codestream with the reference decoder and returns its planes.
     */
    private static List<JpegXsConformanceTest.Plane> reference(Path directory, byte[] codestream) throws IOException {
        var input = directory.resolve("input.jxc");
        Files.write(input, codestream);
        var output = directory.resolve("output.pgx");
        Oracle.run("smalt-jxsdec", TIMEOUT, null, List.of(input.toString(), output.toString()));
        var planes = new ArrayList<JpegXsConformanceTest.Plane>();
        for (var line : Files.readAllLines(output, StandardCharsets.ISO_8859_1)) {
            var raw = line.strip();
            if (!raw.isEmpty()) {
                // the listing names the planes as the decoder was given the output, relative to the working directory
                var plane = directory.resolve(Path.of(raw).getFileName());
                var name = plane.getFileName().toString();
                var header = Files.readString(plane.resolveSibling(name.substring(0, name.length() - 4) + ".h"),
                        StandardCharsets.ISO_8859_1);
                planes.add(JpegXsConformanceTest.plane(header, Files.readAllBytes(plane)));
            }
        }
        return planes;
    }

    private static void assertSame(String what, List<JpegXsConformanceTest.Plane> reference, JpegXsImage image) {
        if (image.cfaPattern().isPresent()) {
            assertEquals(1, reference.size(), what + ": reference planes");
            TOLERANCE.assertWithin(what + " mosaic", reference.getFirst().samples(), image.mosaic().orElseThrow(),
                    reference.getFirst().depth());
            return;
        }
        assertEquals(reference.size(), image.components().size(), what + ": components");
        for (var c = 0; c < reference.size(); c++) {
            TOLERANCE.assertWithin(what + " component " + c, reference.get(c).samples(),
                    image.components().get(c).samples(), reference.get(c).depth());
        }
    }

    /**
     * Writes components as the PGX input of the reference encoder: one big-endian raw file and one header per
     * component, listed by the PGX file.
     */
    private static Path pgx(Path directory, List<JpegXsImage.Component> components) throws IOException {
        var listing = new StringBuilder();
        for (var c = 0; c < components.size(); c++) {
            var component = components.get(c);
            var samples = component.samples();
            var wide = component.precision() > 8;
            var raw = new byte[samples.length * (wide ? 2 : 1)];
            for (var i = 0; i < samples.length; i++) {
                if (wide) {
                    raw[2 * i] = (byte) (samples[i] >> 8);
                    raw[2 * i + 1] = (byte) samples[i];
                } else {
                    raw[i] = (byte) samples[i];
                }
            }
            Files.write(directory.resolve("in_" + c + ".raw"), raw);
            Files.writeString(directory.resolve("in_" + c + ".h"), "PG ML +" + component.precision() + " "
                    + component.width() + " " + component.height() + "\n", StandardCharsets.ISO_8859_1);
            listing.append("in_").append(c).append(".raw\n");
        }
        var file = directory.resolve("in.pgx");
        Files.writeString(file, listing, StandardCharsets.ISO_8859_1);
        return file;
    }

    @Nested
    @DisplayName("the reference decoder reconstructs Smalt's codestreams")
    class SmaltCodestreams {
        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "gray 8-bit, Cw 2|97|64|8|11|3|-1|2|-1",
                "4:2:2 10-bit|256|64|10|11-21-21|4|-1|0|-1",
                "4:2:0 12-bit|200|96|12|11-22-22|3|2|0|-1",
                "4:4:4 12-bit|180|50|12|11-11-11|6|1|0|-1",
                "4:2:2:4 8-bit|128|40|8|11-21-21-11|5|-1|0|-1",
                "four 4:4:4 components lossless|64|48|14|11-11-11-11|0|-1|0|-1",
                "4:4:4 16-bit lossless|90|40|16|11-11-11|0|-1|0|-1",
                "4:2:2 8-bit, horizontal only|300|20|8|11-21-21|3|0|0|3",
        })
        @DisplayName("component images")
        void components(String name, int width, int height, int precision, String sampling, double bpp, int nly,
                        int cw, int nlx) throws IOException {
            var factors = sampling.split("-");
            var sx = new int[factors.length];
            var sy = new int[factors.length];
            for (var c = 0; c < factors.length; c++) {
                sx[c] = factors[c].charAt(0) - '0';
                sy[c] = factors[c].charAt(1) - '0';
            }
            var image = JxsSamples.image(width, height, precision, sx, sy, name.hashCode());
            var builder = (bpp == 0 ? JpegXsEncoderOptions.forLossless() : JpegXsEncoderOptions.ofBitsPerPixel(bpp))
                    .toBuilder().fileFormat(false).precinctWidth(cw);
            if (nly >= 0) {
                builder.verticalLevels(nly);
            }
            if (nlx > 0) {
                builder.horizontalLevels(nlx);
            }
            var data = JpegXsEncoder.encode(image, builder.build());
            var directory = Scratch.directory("jxs-reference-" + name.replaceAll("[^a-z0-9]", ""));
            var decoded = JpegXsDecoder.decodeImage(MediaSource.of(data), LIMITS);
            assertSame(name, reference(directory, data), decoded);
        }

        @ParameterizedTest
        @EnumSource(JpegXsImage.CfaPattern.class)
        @DisplayName("Bayer mosaics")
        void bayer(JpegXsImage.CfaPattern pattern) throws IOException {
            var image = JxsSamples.bayer(160, 100, 12, pattern, pattern.ordinal() + 40);
            var data = JpegXsEncoder.encode(image, JpegXsEncoderOptions.ofBitsPerPixel(10).toBuilder()
                    .fileFormat(false).build());
            var directory = Scratch.directory("jxs-reference-bayer-" + pattern);
            assertSame(pattern.name(), reference(directory, data), JpegXsDecoder.decodeImage(MediaSource.of(data),
                    LIMITS));
        }
    }

    private static String join(int[] values) {
        var text = new StringBuilder();
        for (var value : values) {
            text.append(text.isEmpty() ? "" : ",").append(value);
        }
        return text.toString();
    }

    @Nested
    @DisplayName("Smalt reconstructs the reference encoder's codestreams")
    class ReferenceCodestreams {
        @ParameterizedTest(name = "{0}")
        @CsvSource(delimiter = '|', value = {
                "main 422 separate signs|11-21-21|10|p=main422.10;rate=3;fs=1|",
                "main 422 zero prediction runs|11-21-21|10|p=main422.10;rate=3;rm=0|",
                "light 444 deadzone|11-11-11|12|p=light444.12;rate=4;qpih=0|",
                "high 444 RCT|11-11-11|12|p=high444.12;rate=5;cpih=1|",
                "main 420|11-22-22|12|p=main420.12;rate=2|",
                "high 420|11-22-22|8|p=high420.12;rate=2.5|",
                "high 4444|11-11-11-11|12|p=high4444.12;rate=6|5,2,0",
                "chigh 444|11-11-11|12|p=chigh444.12;rate=4|",
                "light subline|11-21-21|10|p=light-subline422.10;rate=4;cw=2|",
                "raw per packet|11-21-21|10|p=unrestricted;rate=8;bw=20;fq=8;rl=1;lh=1|",
                "suppressed decomposition|11-11-11-11|10|p=unrestricted;rate=8;bw=20;fq=8;sd=1|5,2,1",
                "quadratic nonlinearity|11-11-11|12|p=unrestricted;rate=6;bw=18;fq=6;nlt=quadratic,0,400|",
                "extended nonlinearity|11-11-11|12|p=unrestricted;rate=6;bw=18;fq=6;nlt=extended,20000,40000|",
                "mls 12|11-11-11|12|p=mls.12|",
                "gray|11|12|p=main444.12;rate=2|5,1,0",
        })
        @DisplayName("component images")
        void components(String name, String sampling, int precision, String configuration, String decomposition)
                throws IOException {
            var factors = sampling.split("-");
            var sx = new int[factors.length];
            var sy = new int[factors.length];
            for (var c = 0; c < factors.length; c++) {
                sx[c] = factors[c].charAt(0) - '0';
                sy[c] = factors[c].charAt(1) - '0';
            }
            var image = JxsSamples.image(384, 96, precision, sx, sy, name.hashCode());
            if (decomposition != null) {
                // the reference encoder has no built-in weights for this layout: Smalt's default ones
                var levels = decomposition.split(",");
                var layout = new JxsLayout(sx.length, 384, 96, Integer.parseInt(levels[0]),
                        Integer.parseInt(levels[1]), Integer.parseInt(levels[2]), sx, sy, 0, 1, false);
                var weights = JxsWeights.of(layout);
                configuration += ";gains=" + join(weights[0]) + ";priorities=" + join(weights[1]);
            }
            check(name, image.components(), configuration);
        }

        @ParameterizedTest(name = "{0} {1}")
        @CsvSource({
                "lightbayer, RGGB, 3", "mainbayer, BGGR, 3", "highbayer, GRBG, 3", "mainbayer, GBGR, 3",
                "mainbayer, RGGB, '3,0,1,2'", "lightbayer, GRBG, '3,3,2,1'"
        })
        @DisplayName("Bayer mosaics (the reference encoder names the GBRG pattern GBGR)")
        void bayer(String profile, String cfa, String transform) throws IOException {
            var mosaic = JxsSamples.plane(256, 128, 12, profile.hashCode() + cfa.hashCode());
            var component = new JpegXsImage.Component(256, 128, 12, 1, 1, 0, 0, mosaic);
            // the reference encoder has no built-in weights for Bayer data: those of conformance codestreams 211, 213
            // and 215, which use the default decomposition of each profile
            var weights = switch (profile) {
                case "lightbayer" -> "gains=4,3,3,3,2,2,2,1,1,2,1,1,1,0,0,1,0,0,0;"
                        + "priorities=18,17,16,13,12,11,5,4,3,14,10,9,6,1,0,15,8,7,2";
                case "mainbayer" -> "gains=4,3,3,3,2,2,2,2,2,2,1,1,1,1,1,1,0,0,1,0,0,0,0,0,0;"
                        + "priorities=20,17,16,12,11,10,0,24,23,15,9,8,1,22,21,14,6,4,13,5,3,7,19,18,2";
                default -> "gains=4,3,3,3,3,3,3,2,2,2,2,2,2,1,1,2,1,1,1,0,0,1,0,0,1,0,0,0,0,0,0;"
                        + "priorities=9,8,7,0,30,29,20,19,18,1,28,27,24,16,14,23,15,13,17,11,10,22,6,4,21,5,3,12,26,"
                        + "25,2";
            };
            var configuration = "p=" + profile + ";rate=8;cfa=" + cfa + ";" + weights
                    + (transform.contains(",") ? ";cpih=" + transform : "");
            check(profile + " " + cfa, List.of(component), configuration);
        }

        private void check(String name, List<JpegXsImage.Component> components, String configuration)
                throws IOException {
            var directory = Scratch.directory("jxs-reference-encoder-" + name.replaceAll("[^a-z0-9]", ""));
            var input = pgx(directory, components);
            var codestream = directory.resolve("reference.jxc");
            Oracle.run("smalt-jxsenc", TIMEOUT, null, List.of("-q", "-c", configuration, input.toString(),
                    codestream.toString()));
            var data = Files.readAllBytes(codestream);
            var decoded = JpegXsDecoder.decodeImage(MediaSource.of(data), LIMITS);
            assertSame(name, reference(directory, data), decoded);
        }
    }
}
