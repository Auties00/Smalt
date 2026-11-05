package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Re-runs {@link PanasonicDerivation} on CC0 witness samples against Adobe DNG Converter as a
 * black-box oracle, and checks that every step selects the layout and the constants of
 * {@link PanasonicDecoder}: version 4 (DMC-LX3, DMC-GH1, DMC-GF6, DC-G9) for the block format, the
 * code rules, the missing-photosite markers and the converter's repair, and version 5 (DC-GH5S)
 * with version 4 for the chunk layout the two share. The oracle files are the uncompressed DNG
 * files {@code tools/testdata/raw-oracle.py} writes into {@code <conformance cache>/raw-oracle}
 * ({@code -Dsmalt.test.rawOracle} overrides the directory); the tests are skipped without them.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PanasonicDerivationTest {
    private static final Path ORACLE = Path.of(System.getProperty("smalt.test.rawOracle",
            Conformance.directory().resolve("raw-oracle").toString()));

    private static final List<RawSamples> VERSION_4 = List.of(RawSamples.RW2_LX3, RawSamples.RW2_GH1, RawSamples.RW2_GF6,
            RawSamples.RW2_G9);

    private List<PanasonicDerivation.Sample> version4;

    private PanasonicDerivation.Sample version5;

    @BeforeAll
    void load() throws IOException {
        var loaded = new ArrayList<PanasonicDerivation.Sample>();
        for (var witness : VERSION_4) {
            var sample = load(witness);
            if (sample == null) {
                return;
            }
            loaded.add(sample);
        }
        version5 = load(RawSamples.RW2_GH5S);
        if (version5 != null) {
            version4 = loaded;
        }
    }

    private static PanasonicDerivation.Sample load(RawSamples witness) throws IOException {
        var name = witness.fileName();
        var dng = ORACLE.resolve(name.substring(0, name.lastIndexOf('.')) + ".dng");
        return Files.isRegularFile(dng) ? PanasonicDerivation.load(witness.fetch(), dng) : null;
    }

    private List<PanasonicDerivation.Sample> version4() {
        Skip.unless(version4 != null, "data:raw-oracle", "no oracle files in " + ORACLE + "; run tools/testdata/raw-oracle.py");
        return version4;
    }

    @Test
    @DisplayName("whole 16-byte blocks of 14 (version 4) or 9 (version 5) photosites fill the data")
    void rate() {
        for (var sample : version4()) {
            assertEquals(PanasonicDecoder.group(4, 12), PanasonicDerivation.photositesPerBlock(sample), sample.name());
        }
        assertEquals(PanasonicDecoder.group(5, 14), PanasonicDerivation.photositesPerBlock(version5));
    }

    @Test
    @DisplayName("only 16384-byte chunks rotated by 0x1FF8 bytes put the oracle's photosites at the start of every block")
    void chunkLayout() {
        var samples = new ArrayList<>(version4());
        samples.add(version5);
        for (var sample : samples) {
            var layouts = PanasonicDerivation.chunkLayout(sample, 3);
            var best = layouts.getFirst();
            assertEquals(PanasonicDecoder.CHUNK, best.chunk(), sample.name());
            assertEquals(PanasonicDecoder.CHUNK - PanasonicDecoder.SPLIT, best.rotation(), sample.name());
            assertEquals(sample.version() == 4 ? PanasonicDerivation.Order.REVERSED_MSB_FIRST : PanasonicDerivation.Order.LSB_FIRST,
                    best.order(), sample.name());
            assertEquals(1.0, best.score(), sample.name());
            assertTrue(layouts.get(1).score() < 0.6, sample.name() + " " + layouts.get(1));
        }
    }

    @Test
    @DisplayName("four groups of a 2-bit code and three 8-bit fields fit the blocks far better than any other split")
    void blockLayout() {
        for (var sample : version4()) {
            var splits = PanasonicDerivation.blockLayouts(sample, 64);
            var best = splits.getFirst();
            assertEquals(List.of(4, 2, 8), List.of(best.groups(), best.codeBits(), best.fieldBits()), sample.name());
            assertTrue(best.score() - splits.get(1).score() > 0.2, sample.name() + " " + splits.subList(0, 2));
        }
    }

    @Test
    @DisplayName("each code follows the shifted absolute-or-difference rule the decoder uses, on every witness")
    void codeRules() {
        for (var sample : version4()) {
            var rules = PanasonicDerivation.codeRules(PanasonicDerivation.fields(sample, sample.oracle().height));
            for (var code = 0; code < 3; code++) {
                assertTrue(rules.get(code).contains(new PanasonicDerivation.Rule(128 << code, code, true, code))
                        || (code == 0 && rules.get(code).contains(new PanasonicDerivation.Rule(0, 0, true, 0))),
                        sample.name() + " code " + code + " " + rules.get(code));
            }
            assertTrue(rules.get(3).stream().allMatch(rule -> rule.threshold() > 4095 && rule.absShift() == 4 && rule.keepLow()),
                    sample.name() + " " + rules.get(3));
            assertTrue(!rules.get(3).isEmpty(), sample.name());
        }
        var low = PanasonicDerivation.codeRules(PanasonicDerivation.fields(version4().getFirst(), version4().getFirst().oracle().height));
        assertTrue(low.get(0).stream().allMatch(rule -> rule.threshold() == 128 && rule.absShift() == 0 && rule.relShift() == 0),
                low.get(0).toString());
    }

    @Test
    @DisplayName("every field of 0 repeats the photosite two to the left")
    void zeroFields() {
        for (var sample : version4()) {
            var counts = PanasonicDerivation.zeroFields(PanasonicDerivation.fields(sample, sample.oracle().height));
            assertTrue(counts[0] > 0, sample.name());
            assertEquals(counts[0], counts[1], sample.name());
        }
    }

    @Test
    @DisplayName("blocks with a missing photosite follow the marker reading, not the plain one")
    void markers() {
        for (var sample : version4()) {
            var counts = PanasonicDerivation.markerBlocks(sample);
            assertTrue(counts[0] > 0, sample.name());
            assertEquals(counts[0], counts[2], sample.name());
            assertTrue(counts[1] < counts[0], sample.name());
        }
    }

    @Test
    @DisplayName("the converter fills missing photosites with the rounded mean of their color's diagonal (green) or two-away neighbors")
    void repairs() {
        for (var sample : version4()) {
            var scores = PanasonicDerivation.repairs(sample);
            var zeros = scores.get("zero photosites");
            assertTrue(zeros > 0, sample.name());
            scores.forEach((name, hits) -> {
                if (!name.equals("zero photosites")) {
                    assertEquals(name.equals("diagonal for green, two away otherwise, rounded"), hits.equals(zeros),
                            sample.name() + " " + name + " " + hits + " of " + zeros);
                }
            });
        }
    }
}
