package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Skip;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares Smalt's MPEG Surround decoder with the decoder of the ISO/IEC 23003-3 reference software
 * (mp4spatialdec, the full ISO/IEC 23003-1 decoder), whose decodes {@code tools/testdata/mps-oracle.py} writes into
 * {@code <conformance cache>/mps-oracle} ({@code -Dsmalt.test.mpsOracle} overrides the directory); MPEG Surround
 * conformance streams are not public. The streams are synthetic ones covering every tree, quantization and TTT
 * mode, temporal shaping, arbitrary downmix and residual coding at 24, 48 and 96 kHz, and the reference encoder's
 * streams of a 5.1 signal. Without those files the comparisons are skipped.
 *
 * <p>Both decoders compute the same single precision operations in the same order, so the upmixes agree bit for bit,
 * except for the residual signals, which the reference software dequantizes in double precision: those agree to a few
 * thousandths of a 16-bit step.
 */
class MpegSurroundOracleTest {
    static Stream<String> cases() {
        var synthetic = Stream.of("t5151", "t5152", "t5151q2", "t525cpc", "t525cld", "t525dual", "t525inv",
                "t525mode4", "t7271", "t7272", "t7571", "t7572", "stp5151", "stp525", "stp7271", "ges5152", "ges525",
                "ges7572", "rate24", "rate96", "slots16", "arbdmx5151", "arbdmx525", "res5151", "res525", "res525cpc0",
                "res7272", "res7571", "res5152x2", "arbres5151", "arbres525", "slots18res", "v_q1", "v_q2", "v_oneicc",
                "v_fr3", "v_fr2", "v_gains", "v_q1b", "v_5152");
        var encoded = Stream.of("5151", "5152", "525").flatMap(tree -> Stream.of("16", "32")
                .map(slots -> "enc" + tree + "_" + slots));
        return Stream.concat(synthetic, encoded);
    }

    static Path directory() {
        return Path.of(System.getProperty("smalt.test.mpsOracle",
                Conformance.directory().resolve("mps-oracle").toString()));
    }

    static byte[] read(Path file) {
        Skip.unless(Files.isRegularFile(file), "data:mps-oracle", "no oracle file " + file
                + " (tools/testdata/mps-oracle.py)");
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("upmixes the downmix like the reference decoder")
    void matchesReference(String name) {
        var directory = directory();
        var reference = AacTestSupport.readWav(read(directory.resolve(name + "_ref.wav")));
        var downmix = AacTestSupport.readWav(read(directory.resolve(name + "_dmx.wav")));
        var blocks = MpegSurroundTest.blocks(read(directory.resolve(name + ".bs")));
        var rate = MpegSurroundTest.config(blocks.getFirst()).samplingFrequency();
        var upmix = MpegSurroundTest.upmix(blocks, downmix, rate);
        assertEquals(reference.length, upmix.length, name + " channels");
        for (var c = 0; c < reference.length; c++) {
            assertEquals(reference[c].length, upmix[c].length, name + " length");
            double peak = 0;
            for (var i = 0; i < reference[c].length; i++) {
                peak = Math.max(peak, Math.abs((double) reference[c][i] - upmix[c][i]) * 32768);
            }
            assertTrue(peak <= 0.01, name + " channel " + c + ": peak difference " + peak + " of a 16-bit step");
        }
    }
}
