package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.Random;

import static com.github.auties00.smalt.image.jp2.internal.JpxBuilder.box;
import static com.github.auties00.smalt.image.jp2.internal.JpxBuilder.flst;
import static com.github.auties00.smalt.image.jp2.internal.JpxBuilder.ftyp;
import static com.github.auties00.smalt.image.jp2.internal.JpxBuilder.signature;

/**
 * Writes the seeds of the JPEG 2000 fuzz targets (tools/fuzz/README.md) for HTJ2K code-blocks of 62 to 74 magnitude
 * bit-planes, written by {@link HtRoiWriter}, and for codestream fragments in other files, a JPX file whose fragment
 * table names itself through its data reference box as the {@code jp2Fragments} target resolves every reference.
 * Runs only with {@code -Dsmalt.fuzz.writeSeeds=true}.
 */
@EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
        disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
@DisplayName("JPEG 2000 deep HT and fragment fuzz seeds")
class Jp2EdgeFuzzSeeds {
    private static final String FUZZ = "com.github.auties00.smalt.image.fuzz.ImageFuzzTest";

    @Test
    @DisplayName("writes the deep HTJ2K and fragment seeds of the JPEG 2000 targets")
    void seeds() {
        var random = new Random(74);
        var width = 24;
        var height = 20;
        var samples = new long[width * height];
        for (var i = 0; i < samples.length; i++) {
            var magnitude = random.nextLong() >>> 31;
            samples[i] = random.nextBoolean() ? -magnitude : magnitude;
        }
        var coefficients = samples.clone();
        Part2Forward.decompose(coefficients, width, 0, 0, 0, 0, width, height, Part2Forward.Tree.dyadic(1), null);
        var writer = new HtRoiWriter(width, height);
        writer.levels = 1;
        writer.codeBlock = 3;
        writer.shift = 37;
        writer.plane = 2;
        writer.refine = true;
        var deep = writer.write(coefficients, index -> index % width >= width / 2);

        var stream = JpxFileTest.codestream(12, 10, 8, Jp2Part2Test.smooth(random, 12, 10, 0, 255));
        var a = java.util.Arrays.copyOfRange(stream, 0, 60);
        var b = java.util.Arrays.copyOfRange(stream, 60, stream.length);
        var layout = new JpxBuilder.Layout();
        layout.add(signature()).add(ftyp()).add(JpxFileTest.header(12, 10, 1, 17));
        layout.add(box("ftbl", flst(new long[]{0, 0, 0}, new long[]{0, 0, 0})));
        layout.media(b);
        layout.media(a);
        layout.add(JpxFileTest.Fragments.dtbl("self.jpx"));
        var offsets = layout.offsets();
        layout.boxes.set(3, box("ftbl", flst(new long[]{offsets[1], a.length, 1},
                new long[]{offsets[0], b.length, 0})));
        var fragments = layout.build();

        for (var target : List.of("jp2", "jp2Channels")) {
            FuzzSeeds.write(FUZZ, target, "valid-ht-roi-74.j2k", deep);
        }
        FuzzSeeds.write(FUZZ, "jp2Fragments", "valid-fragments-self.jpx", fragments);
    }
}
