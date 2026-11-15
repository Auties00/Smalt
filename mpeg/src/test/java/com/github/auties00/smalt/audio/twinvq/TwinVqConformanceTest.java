package com.github.auties00.smalt.audio.twinvq;

import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.Tolerance.Iso11172Accuracy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Decodes the TwinVQ conformance streams of ISO/IEC 14496-26 and compares them with their reference waveforms: the
 * TwinVQ streams {@code tv00} to {@code tv27} (object type 7, mono and stereo at 8 to 48 kHz, frames of 1024 and 960
 * samples, up to seven scalable layers), and the ER TwinVQ streams {@code er_tv01} and {@code er_tv02} (object type
 * 21), each with {@code epConfig} 0, 1 (every error sensitivity category in a track of its own) and 2 (the error
 * protection tool). A layered stream is decoded at the layer of each of its {@code _layN} references through
 * {@link TwinVqDecoderOptions#maxLayer()}, from Smalt's MP4 demuxer.
 *
 * <p>The criterion is that of ISO/IEC 14496-26 for audio decoders, which ISO/IEC 11172-4 names full accuracy: an RMS
 * difference below {@code 2^-15 / sqrt(12)} and no difference above {@code 2^-14} of full scale, on the unrounded
 * output clamped to the 16-bit range.
 *
 * <p>The smoke tier decodes {@code tv00} (mono at 8 kHz), {@code tv24} at both layers (scalable) and
 * {@code er_tv01_ep1} (stereo, categories in separate tracks).
 */
@DisplayName("MPEG-4 TwinVQ conformance (ISO/IEC 14496-26)")
class TwinVqConformanceTest {
    private static final Tolerance FULL_ACCURACY = Tolerance.iso11172(Iso11172Accuracy.FULL);

    /**
     * The streams with a single reference.
     */
    private static final String[] SINGLE = {"tv00", "tv01", "tv02", "tv03", "tv04", "tv05", "tv06", "tv07", "tv20",
            "tv21", "tv22", "tv23"};

    /**
     * The layered streams, each with the number of its references.
     */
    private static final Object[][] LAYERED = {{"tv11", 2}, {"tv14", 3}, {"tv15", 3}, {"tv16", 8}, {"tv24", 2},
            {"tv25", 3}, {"tv26", 2}, {"tv27", 3}};

    @TestFactory
    @DisplayName("the smoke streams match their references")
    Stream<DynamicTest> smoke() {
        return Stream.of(
                DynamicTest.dynamicTest("tv00", () -> check("tv00", "tv00", -1)),
                DynamicTest.dynamicTest("tv24 layer 0", () -> check("tv24", "tv24_lay0", 0)),
                DynamicTest.dynamicTest("tv24 layer 1", () -> check("tv24", "tv24_lay1", 1)),
                DynamicTest.dynamicTest("er_tv01_ep1", () -> check("er_tv01_ep1", "er_tv01", -1)));
    }

    @TestFactory
    @DisplayName("every TwinVQ stream matches its references at every layer")
    Stream<DynamicTest> twinVq() {
        var tests = new ArrayList<DynamicTest>();
        for (var stream : SINGLE) {
            tests.add(DynamicTest.dynamicTest(stream, () -> check(stream, stream, -1)));
        }
        for (var entry : LAYERED) {
            var stream = (String) entry[0];
            for (var layer = 0; layer < (int) entry[1]; layer++) {
                var top = layer;
                tests.add(DynamicTest.dynamicTest(stream + " layer " + layer,
                        () -> check(stream, stream + "_lay" + top, top)));
            }
        }
        return tests.stream();
    }

    @TestFactory
    @DisplayName("every ER TwinVQ stream matches its references with each epConfig")
    Stream<DynamicTest> errorResilient() {
        var tests = new ArrayList<DynamicTest>();
        for (var ep = 0; ep <= 2; ep++) {
            var first = "er_tv01_ep" + ep;
            tests.add(DynamicTest.dynamicTest(first, () -> check(first, "er_tv01", -1)));
            var second = "er_tv02_ep" + ep;
            for (var layer = 0; layer < 3; layer++) {
                var top = layer;
                tests.add(DynamicTest.dynamicTest(second + " layer " + layer,
                        () -> check(second, "er_tv02_lay" + top, top)));
            }
        }
        return tests.stream();
    }

    /**
     * Decodes a stream up to a layer and compares it with a reference.
     *
     * @param name the stream name
     * @param reference the reference name
     * @param layer the highest layer decoded, or -1 for every layer
     */
    private static void check(String name, String reference, int layer) {
        var options = TwinVqDecoderOptions.builder();
        if (layer >= 0) {
            options.maxLayer(layer);
        }
        var stream = TwinVqTestSupport.stream(name);
        float[][] decoded;
        try (var decoder = new TwinVqDecoder(stream.tracks(), options.build())) {
            decoded = TwinVqTestSupport.decode(decoder, stream);
        }
        var expected = TwinVqTestSupport.reference(reference);
        assertEquals(expected.length, decoded.length, name + " against " + reference + ": channels");
        for (var channel = 0; channel < expected.length; channel++) {
            assertEquals(expected[channel].length, decoded[channel].length,
                    name + " against " + reference + ": samples");
            var clamped = Arrays.copyOf(decoded[channel], decoded[channel].length);
            for (var i = 0; i < clamped.length; i++) {
                clamped[i] = Math.clamp(clamped[i], -1f, 32767f / 32768);
            }
            FULL_ACCURACY.assertPcm(name + " against " + reference + " channel " + channel, expected[channel], clamped);
        }
    }
}
