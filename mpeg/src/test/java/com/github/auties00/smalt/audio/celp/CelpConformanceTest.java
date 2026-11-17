package com.github.auties00.smalt.audio.celp;

import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.Tolerance.Iso11172Accuracy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decodes the CELP conformance streams of ISO/IEC 14496-26 and compares them with their reference waveforms: the CELP
 * streams {@code ce00} to {@code ce14} (object type 8: the multi-pulse coder at 8 and 16 kHz, the regular pulse coder,
 * fine rate control, the bitrate scalable layers of {@code ce02}, {@code ce03} and {@code ce06} and the bandwidth
 * scalable layers of {@code ce05} and {@code ce06}) and the ER CELP streams {@code er_ce00} to {@code er_ce18} (object
 * type 24, with silence compression in all but {@code er_ce02} and {@code er_ce13}), each with {@code epConfig} 0, 1
 * (every error sensitivity category in a track of its own) and, where the standard has one, 3 (the error protection
 * tool). A layered stream is decoded at the layer of each of its {@code _layN} references through
 * {@link CelpDecoderOptions#maxLayer()}, from Smalt's MP4 demuxer, without the post-processor, whose output the
 * references leave out.
 *
 * <p>The criterion is that of ISO/IEC 14496-26 for audio decoders, which ISO/IEC 11172-4 names full accuracy: an RMS
 * difference below {@code 2^-15 / sqrt(12)} and no difference above {@code 2^-14} of full scale, on the unrounded
 * output clamped to the 16-bit range.
 *
 * <p>The smoke tier decodes {@code ce00} (multi-pulse at 8 kHz), {@code ce07} (regular pulse), {@code er_ce00_ep1}
 * (silence compression, categories in separate tracks), {@code er_ce02_ep3} (the error protection tool) and
 * {@code er_ce15_ep0} (regular pulse with eight subframes and silence compression).
 */
@DisplayName("MPEG-4 CELP conformance (ISO/IEC 14496-26)")
class CelpConformanceTest {
    /**
     * The references of the CELP streams: the stream, then each reference with the highest layer decoded, or
     * {@code *} for every layer.
     */
    private static final String CELP = """
            ce00 ce00_lay0:*
            ce01 ce01_lay0:*
            ce02 ce02_lay0:0 ce02_lay1:1 ce02_lay2:2 ce02_lay3:3
            ce03 ce03_lay0:0 ce03_lay1:1 ce03_lay2:2
            ce04 ce04_lay0:*
            ce05 ce05_lay0:0 ce05_lay1:1
            ce06 ce06_lay0:0 ce06_lay1:1 ce06_lay2:2
            ce07 ce07:*
            ce08 ce08:*
            ce09 ce09:*
            ce10 ce10:*
            ce11 ce11:*
            ce12 ce12:*
            ce13 ce13:*
            ce14 ce14:*
            """;

    /**
     * The ER CELP streams that also have an {@code epConfig} 3 version.
     */
    private static final Set<Integer> PROTECTED = Set.of(0, 2, 6, 9, 13, 15, 18);

    private static final Tolerance FULL_ACCURACY = Tolerance.iso11172(Iso11172Accuracy.FULL);

    @TestFactory
    @DisplayName("the smoke streams match their references")
    Stream<DynamicTest> smoke() {
        return Stream.of(
                DynamicTest.dynamicTest("ce00", () -> check("ce00", "ce00_lay0", -1, FULL_ACCURACY)),
                DynamicTest.dynamicTest("ce07", () -> check("ce07", "ce07", -1, FULL_ACCURACY)),
                DynamicTest.dynamicTest("er_ce00_ep1", () -> check("er_ce00_ep1", "er_ce00", -1, FULL_ACCURACY)),
                DynamicTest.dynamicTest("er_ce02_ep3", () -> check("er_ce02_ep3", "er_ce02", -1, FULL_ACCURACY)),
                DynamicTest.dynamicTest("er_ce15_ep0", () -> check("er_ce15_ep0", "er_ce15", -1, FULL_ACCURACY)));
    }

    @TestFactory
    @DisplayName("every CELP stream matches its references at every layer")
    Stream<DynamicTest> celp() {
        var tests = new ArrayList<DynamicTest>();
        for (var line : CELP.lines().map(String::trim).filter(line -> !line.isEmpty()).toList()) {
            var parts = line.split(" ");
            for (var i = 1; i < parts.length; i++) {
                var reference = parts[i].split(":");
                var layer = reference[1].equals("*") ? -1 : Integer.parseInt(reference[1]);
                tests.add(DynamicTest.dynamicTest(parts[0] + " " + reference[0],
                        () -> check(parts[0], reference[0], layer, FULL_ACCURACY)));
            }
        }
        return tests.stream();
    }

    @TestFactory
    @DisplayName("every ER CELP stream matches its references with each epConfig")
    Stream<DynamicTest> errorResilient() {
        var tests = new ArrayList<DynamicTest>();
        for (var number = 0; number <= 18; number++) {
            var id = String.format("er_ce%02d", number);
            var configs = new ArrayList<>(List.of(0, 1));
            if (PROTECTED.contains(number)) {
                configs.add(3);
            }
            for (var ep : configs) {
                var stream = id + "_ep" + ep;
                if (number == 6) {
                    for (var layer = 0; layer <= 3; layer++) {
                        var top = layer;
                        tests.add(DynamicTest.dynamicTest(stream + " layer " + layer, () -> check(stream,
                                id + "_lay" + top, top, FULL_ACCURACY)));
                    }
                } else {
                    tests.add(DynamicTest.dynamicTest(stream, () -> check(stream, id, -1, FULL_ACCURACY)));
                }
            }
        }
        return tests.stream();
    }

    /**
     * Decodes a stream up to a layer and compares it with a reference.
     *
     * @param stream the stream name
     * @param reference the reference name
     * @param layer the highest layer decoded, or -1 for every layer
     * @param tolerance the accuracy the stream must reach
     */
    private static void check(String stream, String reference, int layer, Tolerance tolerance) {
        var options = CelpDecoderOptions.builder().postFilter(false);
        if (layer >= 0) {
            options.maxLayer(layer);
        }
        var decoded = CelpTestSupport.decode(CelpTestSupport.stream(stream), options.build());
        var expected = CelpTestSupport.reference(reference);
        assertEquals(expected.length, decoded.length, stream + " against " + reference + ": samples");
        var clamped = Arrays.copyOf(decoded, decoded.length);
        for (var i = 0; i < clamped.length; i++) {
            clamped[i] = Math.clamp(clamped[i], -1f, 32767f / 32768);
        }
        tolerance.assertPcm(stream + " against " + reference, expected, clamped);
    }
}
