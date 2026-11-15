package com.github.auties00.smalt.audio.hvxc;

import com.github.auties00.smalt.audio.hvxc.internal.HvxcCore;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes the HVXC conformance streams of ISO/IEC 14496-26 and checks them as the procedures of ISO/IEC 14496-4 and
 * the HVXC conformance tools of ISO/IEC 14496-5 do: the HVXC streams {@code hv01} to {@code hv08} (fixed rate at 2
 * and 4 kbit/s, all voiced, all unvoiced, random number generators disabled, variable rate, harmonic phase
 * initialization) and the ER HVXC streams {@code er_hv01} to {@code er_hv10} with {@code epConfig} 0, 1 (every error
 * sensitivity category in a track of its own) and, where the standard has one, 2 (the error protection tool, with CRC
 * or FEC; they protect the first 218 of the 219 frames, two per EP frame, and are compared over those), including the
 * 2 + 2 kbit/s scalable stream {@code er_hv10} at both layers. Each reference condition is
 * decoded in both delay modes and with the pitch and speed change factors of the procedures, from Smalt's MP4
 * demuxer.
 *
 * <p>Every reference waveform is reproduced exactly with the {@code test_mode} it was made with (the postfilter off
 * and, as the reference decoder's conditions list, the noise addition or the time domain decoder off; the all voiced
 * references were made without the noise addition). The procedures' own criteria are applied on top: with the noise
 * addition on (only the postfilter off, the public {@link HvxcDecoderOptions#postFilter()} option), the
 * differential spectrum of the all voiced streams must stay within the bounds of its bit rate in every frame, and the
 * harmonic phase initialization stream must give a peak to RMS ratio of average 5.68 to 5.88 and deviation 0.32 to
 * 0.50 over its voiced segments.
 *
 * <p>The smoke tier decodes {@code hv01} (exact and spectral), {@code hv02}, {@code hv03}, {@code hv05} with a pitch
 * change, {@code hv07} (variable rate), {@code hv08}, {@code er_hv04_ep1}, {@code er_hv09_ep1} (variable rate 4
 * kbit/s, categories in separate tracks), {@code er_hv10_ep0} (scalable) and {@code er_hv03_ep2_crc} (the error
 * protection tool).
 */
@DisplayName("MPEG-4 HVXC conformance (ISO/IEC 14496-26)")
class HvxcConformanceTest {
    private static final Tolerance EXACT = Tolerance.exact();

    /**
     * The reference conditions: the stream family, the reference, the test mode, the delay mode (1 normal, 0 low),
     * the speed and pitch change factors and the highest layer ({@code *} for every layer).
     */
    private static final String CONDITIONS = """
            hv01 hv01ref1 5 1 1 1 *
            hv01 hv01ref2 5 1 1 1.6 *
            hv01 hv01ref3 5 1 1 0.8 *
            hv01 hv01ref4 5 1 1.5 1 *
            hv01 hv01ref5 5 1 0.75 1 *
            hv01 hv01ref6 5 0 1 1 *
            hv01 hv01ref7 5 0 1 1.6 *
            hv01 hv01ref8 5 0 1 0.8 *
            hv01 hv01ref9 5 0 1.5 1 *
            hv01 hv01ref10 5 0 0.75 1 *
            hv02 hv02ref1 5 1 1 1 *
            hv02 hv02ref2 5 0 1 1 *
            hv03 hv03ref1 5 1 1 1 *
            hv03 hv03ref2 5 0 1 1 *
            hv04 hv04ref1 5 1 1 1 *
            hv04 hv04ref2 5 0 1 1 *
            hv05 hv05ref1 7 1 1 1 *
            hv05 hv05ref2 7 1 1 1.6 *
            hv05 hv05ref3 7 1 1 0.8 *
            hv05 hv05ref4 15 1 1.5 1 *
            hv05 hv05ref5 15 1 0.75 1 *
            hv05 hv05ref6 7 0 1 1 *
            hv05 hv05ref7 7 0 1 1.6 *
            hv05 hv05ref8 7 0 1 0.8 *
            hv05 hv05ref9 15 0 1.5 1 *
            hv05 hv05ref10 15 0 0.75 1 *
            hv06 hv06ref1 7 1 1 1 *
            hv06 hv06ref2 7 0 1 1 *
            hv07 hv07ref1 15 1 1 1 *
            hv07 hv07ref2 15 0 1 1 *
            hv09 er_hv09_nd 15 1 1 1 *
            hv09 er_hv09_ld 15 0 1 1 *
            hv10 er_hv10_lay0_nd 15 1 1 1 0
            hv10 er_hv10_lay0_ld 15 0 1 1 0
            hv10 er_hv10_lay1_nd 15 1 1 1 *
            hv10 er_hv10_lay1_ld 15 0 1 1 *
            """;

    /**
     * A reference condition.
     */
    private record Condition(String family, String reference, int testMode, boolean lowDelay, float speed,
                             float pitch, int maxLayer) {
        static Condition parse(String line) {
            var parts = line.trim().split(" ");
            return new Condition(parts[0], parts[1], Integer.parseInt(parts[2]), parts[3].equals("0"),
                    Float.parseFloat(parts[4]), Float.parseFloat(parts[5]),
                    parts[6].equals("*") ? -1 : Integer.parseInt(parts[6]));
        }

        /**
         * {@return the streams of the family: the HVXC stream, the ER HVXC streams with epConfig 0 and 1, and those
         * with the error protection tool}
         */
        List<String> streams() {
            var number = Integer.parseInt(family.substring(2));
            var streams = new ArrayList<String>();
            if (number <= 8) {
                streams.add(family);
            }
            streams.add("er_" + family + "_ep0");
            streams.add("er_" + family + "_ep1");
            if (number == 3 || number == 4) {
                streams.add("er_" + family + "_ep2_crc");
                streams.add("er_" + family + "_ep2_fec");
            }
            return streams;
        }
    }

    private static List<Condition> conditions() {
        return CONDITIONS.lines().filter(line -> !line.isBlank()).map(Condition::parse).toList();
    }

    @TestFactory
    @DisplayName("the smoke streams reproduce their references")
    Stream<DynamicTest> smoke() {
        var picked = List.of("hv01 hv01ref1", "hv02 hv02ref2", "hv03 hv03ref1", "hv05 hv05ref7", "hv07 hv07ref1",
                "er_hv04_ep1 hv04ref1", "er_hv09_ep1 er_hv09_ld", "er_hv10_ep0 er_hv10_lay1_nd",
                "er_hv03_ep2_crc hv03ref1");
        var tests = new ArrayList<DynamicTest>();
        for (var pick : picked) {
            var parts = pick.split(" ");
            var condition = conditions().stream().filter(c -> c.reference().equals(parts[1])).findFirst()
                    .orElseThrow();
            tests.add(DynamicTest.dynamicTest(pick, () -> exact(parts[0], condition)));
        }
        tests.add(DynamicTest.dynamicTest("hv01 spectral hv01ref4", () -> spectral("hv01", "hv01ref4", false, false,
                1.5f, 1f)));
        tests.add(DynamicTest.dynamicTest("hv08 harmonic phase", () -> phase("hv08", false)));
        return tests.stream();
    }

    @TestFactory
    @DisplayName("every stream reproduces every reference condition exactly")
    Stream<DynamicTest> exact() {
        var tests = new ArrayList<DynamicTest>();
        for (var condition : conditions()) {
            for (var stream : condition.streams()) {
                tests.add(DynamicTest.dynamicTest(stream + " " + condition.reference(), () -> exact(stream,
                        condition)));
            }
        }
        return tests.stream();
    }

    @TestFactory
    @DisplayName("the all voiced streams with the noise addition meet the differential spectrum bounds")
    Stream<DynamicTest> spectral() {
        var tests = new ArrayList<DynamicTest>();
        for (var condition : conditions()) {
            if (!condition.family().equals("hv01") && !condition.family().equals("hv02")) {
                continue;
            }
            for (var stream : condition.streams()) {
                tests.add(DynamicTest.dynamicTest(stream + " " + condition.reference(), () -> spectral(stream,
                        condition.reference(), condition.family().equals("hv02"), condition.lowDelay(),
                        condition.speed(), condition.pitch())));
            }
        }
        return tests.stream();
    }

    @TestFactory
    @DisplayName("the harmonic phase initialization stream meets the peak to RMS bounds")
    Stream<DynamicTest> phase() {
        var tests = new ArrayList<DynamicTest>();
        for (var stream : List.of("hv08", "er_hv08_ep0", "er_hv08_ep1")) {
            for (var lowDelay : List.of(false, true)) {
                tests.add(DynamicTest.dynamicTest(stream + (lowDelay ? " low delay" : " normal delay"),
                        () -> phase(stream, lowDelay)));
            }
        }
        return tests.stream();
    }

    @Test
    @DisplayName("the spectral and phase criteria reject what they must")
    void criteriaDiscriminate() {
        var stream = HvxcTestSupport.stream("hv01");
        var reference = HvxcTestSupport.reference("hv01ref1");
        var noiseless = HvxcTestSupport.decode(stream, HvxcCore.TEST_NO_POSTFILTER | HvxcCore.TEST_NO_NOISE, false,
                1f, 1f, -1);
        assertFalse(HvxcTestSupport.spectralFailures(noiseless, reference, false).isEmpty(),
                "a decode without the noise addition fails the lower bound");
        var noisy = HvxcTestSupport.decode(stream, HvxcCore.TEST_NO_POSTFILTER, false, 1f, 1f, -1);
        assertFalse(HvxcTestSupport.spectralFailures(noisy, reference, true).isEmpty(),
                "the 2 kbit/s stream fails the bounds of 4 kbit/s");
        var zeroPhase = HvxcTestSupport.decode(HvxcTestSupport.stream("hv08"), HvxcCore.TEST_NO_POSTFILTER
                | HvxcCore.TEST_ZERO_PHASE | HvxcCore.TEST_NO_NOISE, false, 1f, 1f, -1);
        var statistics = HvxcTestSupport.peakToRms(zeroPhase);
        assertFalse(statistics[0] > 5.68 && statistics[0] < 5.88, "zero initial phases peak higher");
    }

    private static void exact(String stream, Condition condition) {
        var decoded = HvxcTestSupport.decode(HvxcTestSupport.stream(stream), condition.testMode(),
                condition.lowDelay(), condition.speed(), condition.pitch(), condition.maxLayer());
        var expected = HvxcTestSupport.reference(condition.reference());
        if (stream.contains("_ep2")) {
            // two source frames per EP frame: the last of the 219 frames of the source is not protected
            assertEquals(expected.length - 160, decoded.length, stream + " against " + condition.reference()
                    + ": samples");
            expected = Arrays.copyOf(expected, decoded.length);
        }
        assertEquals(expected.length, decoded.length, stream + " against " + condition.reference() + ": samples");
        EXACT.assertPcm(stream + " against " + condition.reference(), expected, decoded);
    }

    private static void spectral(String stream, String reference, boolean fourKbit, boolean lowDelay, float speed,
                                 float pitch) {
        var options = HvxcDecoderOptions.builder().postFilter(false).lowDelay(lowDelay).speedFactor(speed)
                .pitchFactor(pitch).build();
        var decoded = HvxcTestSupport.decode(HvxcTestSupport.stream(stream), options);
        var expected = HvxcTestSupport.reference(reference);
        assertEquals(expected.length, decoded.length, stream + " against " + reference + ": samples");
        var failures = HvxcTestSupport.spectralFailures(decoded, expected, fourKbit);
        assertTrue(failures.isEmpty(), stream + " against " + reference + ": frames outside the bounds " + failures);
    }

    private static void phase(String stream, boolean lowDelay) {
        var decoded = HvxcTestSupport.decode(HvxcTestSupport.stream(stream),
                HvxcCore.TEST_NO_POSTFILTER | HvxcCore.TEST_NO_NOISE, lowDelay, 1f, 1f, -1);
        var statistics = HvxcTestSupport.peakToRms(decoded);
        assertTrue(statistics[0] > 5.68 && statistics[0] < 5.88, stream + ": average peak to RMS " + statistics[0]);
        assertTrue(statistics[1] > 0.32 && statistics[1] < 0.50, stream + ": deviation of peak to RMS "
                + statistics[1]);
    }
}
