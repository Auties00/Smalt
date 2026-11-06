package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Path;
import java.util.List;
import java.util.Random;

import static com.github.auties00.smalt.image.jp2.internal.Part2Forward.HORIZONTAL;
import static com.github.auties00.smalt.image.jp2.internal.Part2Forward.JOINT;
import static com.github.auties00.smalt.image.jp2.internal.Part2Forward.Tree;
import static com.github.auties00.smalt.image.jp2.internal.Part2Forward.VERTICAL;

/**
 * Writes the ISO/IEC 15444-2 and JPX seeds of the JPEG 2000 fuzz targets (tools/fuzz/README.md): codestreams with an
 * arbitrary kernel, decomposition and region of interest, with multiple component and non-linear
 * transformations, the Part 2 quantizers, visual masking and the single sample overlap transformations, written by {@link Part2Writer}, and the JPX and Part 2 behavior samples. Runs only with
 * {@code -Dsmalt.fuzz.writeSeeds=true}.
 */
@EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
        disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
@DisplayName("JPEG 2000 Part 2 fuzz seeds")
class Part2FuzzSeeds {
    private static final String FUZZ = "com.github.auties00.smalt.image.fuzz.ImageFuzzTest";

    @Test
    @DisplayName("writes the Part 2 and JPX seeds of the JPEG 2000 targets")
    void seeds() {
        var random = new Random(7);
        var kernel = new Part2Writer(40, 32, 1, 8, false);
        kernel.rsiz |= Part2Segments.RSIZ_ATK | Part2Segments.RSIZ_ADS | Part2Segments.RSIZ_ROI;
        kernel.levels = 3;
        kernel.wavelet = 2;
        kernel.decompositionByte = 0x81;
        var atk = Part2Forward.THREE_STEP.atk(2, 1);
        kernel.kernel = LiftingKernel.parse(atk, 4, atk.length).kernel();
        kernel.decomposition = new Decomposition(new int[]{1, 2, 3}, new int[]{3, 1},
                new int[]{3, 2, 0, 3, 0, 0, 2, 0, 3});
        kernel.mainSegments.add(atk);
        kernel.mainSegments.add(Part2Segments.dfs(1, 1, 2, 3));
        kernel.tileSegments.add(Part2Segments.ads(1, new int[]{3, 1}, new int[]{3, 2, 0, 3, 0, 0, 2, 0, 3}));
        kernel.tileSegments.add(Part2Segments.cod(0, 0x81, 4, 2));
        var tree = Tree.of(JOINT, Tree.of(HORIZONTAL, Tree.of(VERTICAL), Tree.LEAF_NODE),
                Tree.of(HORIZONTAL, Tree.of(VERTICAL), Tree.LEAF_NODE), Tree.of(VERTICAL),
                Tree.of(VERTICAL, Tree.LEAF_NODE, Tree.of(HORIZONTAL)));
        var plane = Jp2Part2Test.smooth(random, 40, 32, 0, 255);
        var arbitrary = kernel.write(Jp2Part2Test.forward(kernel, new long[][]{plane}, new long[]{128}, tree,
                Part2Forward.THREE_STEP));

        var transforms = new Part2Writer(24, 16, 3, 10, true);
        transforms.rsiz |= Part2Segments.RSIZ_MCT | Part2Segments.RSIZ_NLT;
        transforms.mct = 6;
        transforms.levels = 2;
        transforms.mainSegments.add(Part2Segments.cbdShared(3, 8, false));
        transforms.mainSegments.add(Part2Segments.mct(1, 1, 2, 1, 0, 1.402, 1, -0.344, -0.714, 1, 1.772, 0));
        transforms.mainSegments.add(Part2Segments.mct(2, 2, 0, 128, 128, 128));
        transforms.mainSegments.add(Part2Segments.mcc(1, Part2Segments.Collection.array(1, false, 1, 2,
                new int[]{0, 1, 2}, new int[]{0, 1, 2})));
        transforms.mainSegments.add(Part2Segments.mcc(2, Part2Segments.Collection.wavelet(1, 0, 1, 1,
                new int[]{0, 1, 2}, new int[]{0, 1, 2})));
        transforms.mainSegments.add(Part2Segments.mco(2, 1));
        transforms.mainSegments.add(Part2Segments.nltGamma(65535, 8, false, 2.2, 4.5, 0.018, 1.099, 0.099));
        var planes = new long[][]{Jp2Part2Test.image(random, 384, -200, 200), Jp2Part2Test.image(random, 384, -50, 50),
                Jp2Part2Test.image(random, 384, -50, 50)};
        var multiple = transforms.write(Jp2Part2Test.forward(transforms, planes, new long[3], Tree.dyadic(2), null));

        var deadzone = new Part2Writer(32, 24, 1, 8, false);
        deadzone.rsiz |= 0x0002;
        deadzone.wavelet = 0;
        deadzone.levels = 2;
        deadzone.quantizationStyle = Quantization.DEADZONE_EXPOUNDED;
        deadzone.bandDeadzones = new int[]{-8192, 9830, 9830, 16384, -16384, 0, 32767};
        deadzone.exponent = 10;
        deadzone.guardBits = 2;
        var deadzoned = deadzone.write(new long[][]{Jp2Part2Test.image(random, 32 * 24, -60, 60)});

        var trellis = new Part2Writer(32, 24, 1, 8, false);
        trellis.rsiz |= 0x0004;
        trellis.wavelet = 0;
        trellis.levels = 2;
        trellis.quantizationStyle = Quantization.TRELLIS_DERIVED;
        trellis.exponent = 10;
        var trellised = trellis.write(new float[][]{new float[32 * 24]},
                (values, indices, w, h, stepSize, resolution, orientation, precinct) -> {
                    var scaled = new double[values.length];
                    for (var i = 0; i < scaled.length; i++) {
                        scaled[i] = Math.sin(i * 0.37 + resolution) * 40;
                    }
                    var q = new long[values.length];
                    Part2Tcq.quantize(scaled, w, h, q, new double[values.length]);
                    for (var i = 0; i < q.length; i++) {
                        indices[i] = (int) q[i];
                    }
                });

        var precincts = new Part2Writer(32, 32, 2, 8, false);
        precincts.rsiz |= Codestream.RSIZ_PRECINCT_QUANTIZATION;
        precincts.wavelet = 0;
        precincts.levels = 1;
        precincts.quantizationStyle = Quantization.SCALAR_EXPOUNDED;
        precincts.exponent = 10;
        precincts.precinctSizes = new int[]{3, 4};
        precincts.mainSegments.add(Jp2Part2QuantizationTest.qpd(false, 1, 1, 2 << 5 | 1, new byte[]{0x60, 0x10}));
        precincts.mainSegments.add(Jp2Part2QuantizationTest.qpc(1, true, 0, 2, 2 << 5 | 2, new byte[]{0x58, 0x00}));
        precincts.tileSegments.add(Jp2Part2QuantizationTest.qpd(false, 1, 3, 3 << 5 | 5,
                new byte[]{0x20, 0x00, 0x60, 0x00, 0x20, 0x00, 0x60, 0x00, 0x20, 0x00, 0x60, 0x00}));
        var overrides = new java.util.HashMap<Long, Quantization>();
        overrides.put(PrecinctQuantizations.key(1, 1), Quantization.parse(new byte[]{0x41, 0x60, 0x10}, 0, 3));
        overrides.put(PrecinctQuantizations.key(1, 3), Quantization.parse(new byte[]{0x65, 0x20, 0x00, 0x60, 0x00,
                0x20, 0x00, 0x60, 0x00, 0x20, 0x00, 0x60, 0x00}, 0, 13));
        var component1 = new java.util.HashMap<>(overrides);
        component1.put(PrecinctQuantizations.key(0, 2), Quantization.parse(new byte[]{0x42, 0x58, 0x00}, 0, 3));
        precincts.precinctQuantizations = new PrecinctQuantizations[]{
                new PrecinctQuantizations(overrides, java.util.Map.of(), java.util.Map.of(), java.util.Map.of(), false),
                new PrecinctQuantizations(component1, java.util.Map.of(), java.util.Map.of(), java.util.Map.of(),
                        false)};
        var precinctDependent = precincts.write(new long[][]{Jp2Part2Test.image(random, 1024, -40, 40),
                Jp2Part2Test.image(random, 1024, -40, 40)});

        var masking = new Part2Writer(32, 24, 1, 8, false);
        masking.rsiz |= 0x0008;
        masking.wavelet = 0;
        masking.levels = 2;
        masking.quantizationStyle = Quantization.SCALAR_EXPOUNDED;
        masking.exponent = 10;
        masking.codeBlock = 3;
        masking.mainSegments.add(Jp2Part2OverlapMaskingTest.vms(65535, 1, false, 3, 9, 90, 26));
        masking.tileSegments.add(Jp2Part2OverlapMaskingTest.vms(0, 0, true, 2, 4, 128, 64));
        var noise = new float[32 * 24];
        for (var i = 0; i < noise.length; i++) {
            noise[i] = (float) (random.nextGaussian() * 30);
        }
        var masked = masking.write(new float[][]{noise});

        var overlap = new Part2Writer(37, 29, 1, 8, false);
        overlap.rsiz |= Codestream.RSIZ_SSO;
        overlap.overlap = Jp2Part2OverlapMaskingTest.sso(4, 3);
        overlap.x0 = 3;
        overlap.y0 = 1;
        overlap.levels = 3;
        var overlapPlane = Jp2Part2Test.smooth(random, 37, 29, 0, 255);
        for (var i = 0; i < overlapPlane.length; i++) {
            overlapPlane[i] -= 128;
        }
        Part2Forward.decomposeOverlapped(overlapPlane, 37, 0, 0, 3, 1, 40, 30, Tree.dyadic(3), Part2Forward.LE_GALL,
                4, 3, 0, 0);
        var overlapped = overlap.write(new long[][]{overlapPlane});

        var tiles = new Part2Writer(40, 32, 1, 8, false);
        tiles.rsiz |= Codestream.RSIZ_SSO;
        tiles.overlap = ComponentCoding.OVERLAP_TILES | ComponentCoding.OVERLAP_HORIZONTAL;
        tiles.tileWidth = 16;
        tiles.tileHeight = 16;
        tiles.levels = 2;
        var tilePlane = Jp2Part2Test.smooth(random, 40, 32, 0, 255);
        var tileOverlap = tiles.writeTiles((tile, bounds) -> {
            var width = (int) (bounds[2] - bounds[0]);
            var height = (int) (bounds[3] - bounds[1]);
            var data = new long[width * height];
            for (var y = 0; y < height; y++) {
                for (var x = 0; x < width; x++) {
                    data[y * width + x] = tilePlane[(int) ((bounds[1] + y) * 40 + bounds[0] + x)] - 128;
                }
            }
            Part2Forward.decompose(data, width, 0, 0, bounds[0], bounds[1], bounds[2], bounds[3], Tree.dyadic(2),
                    null);
            return new long[][]{data};
        }, 10);

        var samples = Path.of("src", "test", "resources", "samples", "jp2");
        for (var target : List.of("jp2", "jp2Channels")) {
            FuzzSeeds.write(FUZZ, target, "valid-part2-kernel.j2k", arbitrary);
            FuzzSeeds.write(FUZZ, target, "valid-part2-transforms.j2k", multiple);
            FuzzSeeds.write(FUZZ, target, "valid-part2-deadzone.j2k", deadzoned);
            FuzzSeeds.write(FUZZ, target, "valid-part2-trellis.j2k", trellised);
            FuzzSeeds.write(FUZZ, target, "valid-part2-precincts.j2k", precinctDependent);
            FuzzSeeds.write(FUZZ, target, "valid-part2-masking.j2k", masked);
            FuzzSeeds.write(FUZZ, target, "valid-part2-overlap.j2k", overlapped);
            FuzzSeeds.write(FUZZ, target, "valid-part2-tile-overlap.j2k", tileOverlap);
            FuzzSeeds.write(FUZZ, target, "valid-part2-derived-one-direction.j2k",
                    Fixtures.read(Path.of("src", "test", "resources", "com", "github", "auties00", "smalt",
                            "image", "jp2", "kakadu-e5", "mixed.j2c")));
            FuzzSeeds.write(FUZZ, target, "valid-mct.j2k", Fixtures.read(samples.resolve("mct.j2k")));
            FuzzSeeds.write(FUZZ, target, "valid-layers.jpx",
                    Fixtures.read(samples.resolve("layers.jpx")));
        }
    }
}
