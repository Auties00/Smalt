package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.testkit.Skip;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.testkit.Conformance;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Re-runs {@link OlympusDerivation} on CC0 witness samples against Adobe DNG Converter as a
 * black-box oracle, and checks that every step selects the layout and the constants of
 * {@link OlympusDecoder}, which then reproduces the oracle exactly. The oracle files are the
 * uncompressed DNG files {@code tools/testdata/raw-oracle.py} writes into
 * {@code <conformance cache>/raw-oracle} ({@code -Dsmalt.test.rawOracle} overrides the directory);
 * the tests are skipped without them. The witnesses mix a flat dark image (E-P5), one whose first
 * photosites are zero (E-P1), images with strong detail and large residuals (E-410, OM-1, TG-7),
 * one whose first photosite escapes (E-M1) and ordinary ones (E-M10, E-P3, E-PL6).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OlympusDerivationTest {
    private static final Path ORACLE = Path.of(System.getProperty("smalt.test.rawOracle",
            Conformance.directory().resolve("raw-oracle").toString()));

    private static final List<RawSamples> WITNESSES = List.of(RawSamples.ORF_EP5, RawSamples.ORF_EP1, RawSamples.ORF_EM10,
            RawSamples.ORF_EP3, RawSamples.ORF_E410, RawSamples.ORF_OM1, RawSamples.ORF_TG7, RawSamples.ORF_EM1,
            RawSamples.ORF_EPL6);

    private List<OlympusDerivation.Sample> samples;

    @BeforeAll
    void load() {
        samples = new ArrayList<>();
        for (var witness : WITNESSES) {
            var name = witness.fileName();
            var dng = ORACLE.resolve(name.substring(0, name.length() - 4) + ".dng");
            if (!Files.isRegularFile(dng)) {
                samples = null;
                return;
            }
            samples.add(OlympusDerivation.load(witness.fetch(), dng));
        }
    }

    private List<OlympusDerivation.Sample> samples() {
        Skip.unless(samples != null, "data:raw-oracle", "no oracle files in " + ORACLE + "; run tools/testdata/raw-oracle.py");
        return samples;
    }

    private OlympusDerivation.Sample sample(RawSamples witness) {
        return samples().get(WITNESSES.indexOf(witness));
    }

    @Test
    @DisplayName("every strip starts with the same seven bytes")
    void header() {
        assertEquals(OlympusDecoder.HEADER, OlympusDerivation.header(samples()));
    }

    @Test
    @DisplayName("only most-significant-first codes with four bits after the zero run reproduce the first photosites of a row")
    void absoluteCode() {
        var codes = OlympusDerivation.absoluteCodes(samples(), OlympusDecoder.HEADER, 2);
        assertEquals(List.of(new OlympusDerivation.AbsoluteCode(true, 4)), List.copyOf(codes.keySet()));
    }

    @Test
    @DisplayName("each row restarts with the absolute code right after the previous row's codes")
    void rowRestart() {
        var code = new OlympusDerivation.AbsoluteCode(true, 4);
        for (var witness : List.of(RawSamples.ORF_EP5, RawSamples.ORF_EM10, RawSamples.ORF_EP3, RawSamples.ORF_E410)) {
            var sample = sample(witness);
            var ends = OlympusDerivation.rowEnds(sample, 2);
            assertEquals(ends[0], OlympusDerivation.rowStart(sample, OlympusDecoder.HEADER, 1, code), sample.name());
        }
    }

    @Test
    @DisplayName("on a flat image the codes settle to six bits holding the residual's low bits in two's complement at one place")
    void alignment() {
        var sample = sample(RawSamples.ORF_EP5);
        var end = OlympusDerivation.rowEnds(sample, 1)[0];
        var found = OlympusDerivation.alignments(sample, 1002, 12, end);
        assertEquals(1, found.stream().map(a -> List.of((long) a.length(), a.position() + a.lowOffset())).distinct().count(),
                found.toString());
        assertTrue(found.stream().allMatch(a -> a.length() == 6 && a.twosComplement()), found.toString());
        assertTrue(found.stream().anyMatch(a -> a.lowOffset() == 1), found.toString());
    }

    @Test
    @DisplayName("every bias update that survives the search equals -floor((3 * H - S) / 32)")
    void bias() {
        var rules = OlympusDerivation.biasRules(samples(), 400);
        assertFalse(rules.isEmpty());
        for (var rule : rules) {
            assertFalse(rule.residual(), rule.toString());
            assertEquals(0, rule.d(), rule.toString());
            assertEquals(3 * (1 << rule.k()), 32 * rule.a(), rule.toString());
            assertEquals(-(1 << rule.k()), 32 * rule.c(), rule.toString());
        }
    }

    @Test
    @DisplayName("the width of the mid field follows the one's complement magnitude of the previous coded value best")
    void widthMeasure() {
        Map<String, Integer> conflicts = OlympusDerivation.widthTable(samples(), 1500);
        var best = conflicts.entrySet().stream().min(Map.Entry.comparingByValue()).orElseThrow();
        assertEquals("coded one's complement", best.getKey(), conflicts.toString());
    }

    @Test
    @DisplayName("one width rule of the family decodes the first two rows of every witness")
    void widthRule() {
        assertEquals(List.of(OlympusDerivation.Width.FORMAT), OlympusDerivation.widthRules(samples()));
    }

    @Test
    @DisplayName("the escape follows the longest normal zero run, with the quotient in 15 - width bits and a one bit")
    void escape() {
        var escapes = OlympusDerivation.escapes(samples());
        assertTrue(escapes.contains(OlympusDerivation.Escape.FORMAT), escapes.toString());
        for (var escape : escapes) {
            assertTrue(escape.terminated());
            assertEquals(OlympusDecoder.ESCAPE + OlympusDecoder.ESCAPE_BITS, escape.zeros() + escape.base(), escape.toString());
        }
        assertEquals(OlympusDecoder.ESCAPE - 1, OlympusDerivation.longestRun(samples()));
    }

    @Test
    @DisplayName("only the edge-switching predictor reproduces every prediction of the later rows")
    void predictor() {
        var contexts = new ArrayList<OlympusDerivation.Context>();
        for (var sample : samples()) {
            contexts.addAll(OlympusDerivation.contexts(sample, 24));
        }
        var scores = OlympusDerivation.predictions(contexts);
        scores.forEach((name, score) -> assertEquals(name.equals("min or max outside, mean or gradient inside"), score == 1.0,
                name + " " + score));
        var rules = OlympusDerivation.edgeRules(contexts);
        rules.forEach((name, best) -> assertEquals(name.equals("max(|left - upLeft|, |up - upLeft|)"), best[1] == 1.0,
                name + " " + best[1]));
        assertEquals(OlympusDecoder.EDGE, (int) rules.get("max(|left - upLeft|, |up - upLeft|)")[0]);
    }

    @Test
    @DisplayName("the decoder reproduces every photosite of every witness")
    void decoding() {
        for (var sample : samples()) {
            assertEquals(0, OlympusDerivation.mismatches(sample), sample.name());
        }
    }
}
