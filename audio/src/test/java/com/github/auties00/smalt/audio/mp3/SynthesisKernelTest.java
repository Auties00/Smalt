package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Checks that the Vector API synthesis kernel is bit-identical to the scalar reference, kernel
 * by kernel on random input and end to end on real streams.
 */
class SynthesisKernelTest {
    @BeforeEach
    void requireVectorModule() {
        assumeTrue(ModuleLayer.boot().findModule("jdk.incubator.vector").isPresent(), "Vector API not available");
    }

    private static float[] random(SplittableRandom random, int length, float scale) {
        var values = new float[length];
        for (var i = 0; i < length; i++) {
            values[i] = (float) ((random.nextDouble() * 2 - 1) * scale);
        }
        return values;
    }

    @ParameterizedTest(name = "{0} slots")
    @ValueSource(ints = {12, 18})
    @DisplayName("the DCT stage is bit-identical on random input")
    void dct(int slots) {
        var random = new SplittableRandom(slots);
        for (var round = 0; round < 200; round++) {
            var input = random(random, 2 * 576, round % 2 == 0 ? 1 : 30000);
            var scalar = input.clone();
            var vector = input.clone();
            new ScalarSynthesisKernel().dct(scalar, 576 * (round % 2), slots);
            VectorSynthesisKernel.create().dct(vector, 576 * (round % 2), slots);
            assertArrayEquals(scalar, vector);
        }
    }

    @ParameterizedTest(name = "stereo {0}, wide {1}")
    @org.junit.jupiter.params.provider.CsvSource({"true, true", "true, false", "false, true", "false, false"})
    @DisplayName("the window stage is bit-identical on random input, mono and stereo, four and eight lanes")
    void window(boolean stereo, boolean wide) {
        var random = new SplittableRandom(stereo ? 1 : 2);
        var scalarKernel = new ScalarSynthesisKernel();
        var vectorKernel = VectorSynthesisKernel.create(wide);
        for (var round = 0; round < 200; round++) {
            var grbuf = random(random, 2 * 576, 20000);
            var lins = random(random, 33 * 64, 20000);
            var scalarLins = lins.clone();
            var vectorLins = lins.clone();
            var scalarLeft = new float[1152];
            var scalarRight = stereo ? new float[1152] : scalarLeft;
            var vectorLeft = new float[1152];
            var vectorRight = stereo ? new float[1152] : vectorLeft;
            var slot = 2 * random.nextInt(9);
            var right = stereo ? 576 + slot : slot;
            scalarKernel.window(grbuf, slot, right, scalarLeft, scalarRight, 32 * slot, scalarLins, slot * 64);
            vectorKernel.window(grbuf, slot, right, vectorLeft, vectorRight, 32 * slot, vectorLins, slot * 64);
            assertArrayEquals(scalarLeft, vectorLeft);
            assertArrayEquals(scalarRight, vectorRight);
            assertArrayEquals(scalarLins, vectorLins);
        }
    }

    @ParameterizedTest(name = "{0} bands")
    @ValueSource(ints = {2, 8, 13, 30, 32})
    @DisplayName("the long-block IMDCT is bit-identical on random input and windows")
    void imdct36(int bands) {
        var random = new SplittableRandom(bands);
        var scalarKernel = new ScalarSynthesisKernel();
        var vectorKernel = VectorSynthesisKernel.create(true);
        for (var round = 0; round < 200; round++) {
            var grbuf = random(random, 576, round % 2 == 0 ? 1 : 30000);
            var overlap = random(random, 288, 1);
            var window = Mp3Tables.MDCT_WINDOW[round % 2];
            var start = 32 - bands;
            var scalarGr = grbuf.clone();
            var vectorGr = grbuf.clone();
            var scalarOverlap = overlap.clone();
            var vectorOverlap = overlap.clone();
            scalarKernel.imdct36(scalarGr, 18 * start, scalarOverlap, 9 * start, window, bands);
            vectorKernel.imdct36(vectorGr, 18 * start, vectorOverlap, 9 * start, window, bands);
            assertArrayEquals(scalarGr, vectorGr);
            assertArrayEquals(scalarOverlap, vectorOverlap);
        }
    }

    @Test
    @DisplayName("whole streams decode bit-identically with either kernel")
    void wholeStreams() {
        for (var file : new java.nio.file.Path[]{Mp3Fixtures.lame(44100, 2, 1.5, "-b:a", "256k"),
                Mp3Fixtures.lame(22050, 1, 1.5, "-q:a", "4"),
                Mp3Fixtures.encode("mp2", 48000, 2, 1.0, java.util.List.of("-c:a", "mp2", "-b:a", "192k"))}) {
            var data = Fixtures.read(file);
            float[] scalar;
            float[] vector;
            try (var demuxer = new MpegAudioDemuxer(MediaSource.of(data))) {
                scalar = AudioPipeline.decode(demuxer, track -> new Mp3Decoder(track, new ScalarSynthesisKernel())).interleaved();
            }
            try (var demuxer = new MpegAudioDemuxer(MediaSource.of(data))) {
                vector = AudioPipeline.decode(demuxer, track -> new Mp3Decoder(track, VectorSynthesisKernel.create())).interleaved();
            }
            assertArrayEquals(scalar, vector);
        }
    }
}
