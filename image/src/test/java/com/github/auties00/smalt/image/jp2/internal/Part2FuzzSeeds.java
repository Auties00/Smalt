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
 * arbitrary kernel, decomposition and region of interest, and with multiple component and non-linear
 * transformations, written by {@link Part2Writer}, and the JPX and Part 2 behavior samples. Runs only with
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

        var samples = Path.of("src", "test", "resources", "samples", "jp2");
        for (var target : List.of("jp2", "jp2Channels")) {
            FuzzSeeds.write(FUZZ, target, "valid-part2-kernel.j2k", arbitrary);
            FuzzSeeds.write(FUZZ, target, "valid-part2-transforms.j2k", multiple);
            FuzzSeeds.write(FUZZ, target, "valid-mct.j2k", Fixtures.read(samples.resolve("mct.j2k")));
            FuzzSeeds.write(FUZZ, target, "valid-layers.jpx",
                    Fixtures.read(samples.resolve("layers.jpx")));
        }
    }
}
