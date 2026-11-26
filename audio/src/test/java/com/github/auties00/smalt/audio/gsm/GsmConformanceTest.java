package com.github.auties00.smalt.audio.gsm;

import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Runs the test sequences of ETSI EN 300 961 (GSM 06.10) through the RPE-LTP coder, from its home state: each
 * {@code .inp} sequence (16-bit little-endian samples) must code to the parameters of its {@code .cod} sequence
 * (76 16-bit words per frame), and each {@code .cod} sequence must decode to its {@code .out} samples. The sequences
 * of the first disk have no homing frames; those of the second and third test the codec homing of clause 4.
 */
class GsmConformanceTest {
    /** The test sequences check the parameters and samples bit for bit. */
    private static final Tolerance TOLERANCE = Tolerance.exact();

    private static Map<String, byte[]> files;

    private static synchronized Map<String, byte[]> files() {
        if (files != null) {
            return files;
        }
        try {
            var outer = Files.readAllBytes(Conformance.vector("gsm-en300961-v080101.zip"));
            var map = new HashMap<String, byte[]>();
            try (var zip = new ZipInputStream(new ByteArrayInputStream(outer))) {
                for (var disk = zip.getNextEntry(); disk != null; disk = zip.getNextEntry()) {
                    if (!disk.getName().matches("DISK[123]\\.ZIP")) {
                        continue;
                    }
                    try (var inner = new ZipInputStream(new ByteArrayInputStream(zip.readAllBytes()))) {
                        for (var entry = inner.getNextEntry(); entry != null; entry = inner.getNextEntry()) {
                            var name = entry.getName();
                            map.put(name.substring(name.lastIndexOf('/') + 1), inner.readAllBytes());
                        }
                    }
                }
            }
            files = map;
            return map;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static short[] words(String name) {
        var data = files().get(name);
        assertNotNull(data, "sequence " + name);
        var out = new short[data.length / 2];
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(out);
        return out;
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Seq01", "Seq02", "Seq03", "Seq04", "Seq01h", "Seq02h", "Seq03h", "Seq04h", "Seq06h"})
    @DisplayName("codes the input sequences to the reference parameters")
    void encoder(String sequence) {
        var input = words(sequence + ".inp");
        var reference = words(sequence + ".cod");
        var frames = input.length / Gsm0610.SAMPLES;
        assertEquals(frames * Gsm0610.PARAMETERS, reference.length, sequence + " frame count");
        var coder = new Gsm0610();
        var samples = new int[Gsm0610.SAMPLES];
        var params = new int[Gsm0610.PARAMETERS];
        var coded = new short[reference.length];
        for (var f = 0; f < frames; f++) {
            for (var i = 0; i < Gsm0610.SAMPLES; i++) {
                samples[i] = input[f * Gsm0610.SAMPLES + i];
            }
            coder.encode(samples, 0, params);
            for (var i = 0; i < Gsm0610.PARAMETERS; i++) {
                coded[f * Gsm0610.PARAMETERS + i] = (short) params[i];
            }
        }
        TOLERANCE.assertPcm("GSM 06.10 " + sequence + " parameters", reference, coded);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Seq01", "Seq02", "Seq03", "Seq04", "Seq05", "Seq01h", "Seq02h", "Seq03h", "Seq04h",
            "Seq05h", "Homing01"})
    @DisplayName("decodes the reference parameters to the reference output")
    void decoder(String sequence) {
        var parameters = words(sequence + ".cod");
        var reference = words(sequence + ".out");
        var frames = parameters.length / Gsm0610.PARAMETERS;
        assertEquals(frames * Gsm0610.SAMPLES, reference.length, sequence + " frame count");
        var decoder = new Gsm0610();
        var params = new int[Gsm0610.PARAMETERS];
        var samples = new int[Gsm0610.SAMPLES];
        var decoded = new short[reference.length];
        for (var f = 0; f < frames; f++) {
            for (var i = 0; i < Gsm0610.PARAMETERS; i++) {
                params[i] = parameters[f * Gsm0610.PARAMETERS + i];
            }
            decoder.decode(params, samples, 0);
            for (var i = 0; i < Gsm0610.SAMPLES; i++) {
                decoded[f * Gsm0610.SAMPLES + i] = (short) samples[i];
            }
        }
        TOLERANCE.assertPcm("GSM 06.10 " + sequence + " output", reference, decoded);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"Seq01", "Seq03"})
    @DisplayName("packs and unpacks the reference parameters losslessly in both byte layouts")
    void packing(String sequence) {
        var parameters = words(sequence + ".cod");
        var frames = parameters.length / Gsm0610.PARAMETERS & ~1;
        var params = new int[Gsm0610.PARAMETERS];
        var back = new int[Gsm0610.PARAMETERS];
        var frame = new byte[GsmFrames.FRAME_BYTES];
        var block = new byte[GsmFrames.BLOCK_BYTES];
        var second = new int[Gsm0610.PARAMETERS];
        for (var f = 0; f < frames; f += 2) {
            for (var i = 0; i < Gsm0610.PARAMETERS; i++) {
                params[i] = parameters[f * Gsm0610.PARAMETERS + i];
                second[i] = parameters[(f + 1) * Gsm0610.PARAMETERS + i];
            }
            GsmFrames.pack(params, frame, 0);
            assertEquals(true, GsmFrames.unpack(frame, 0, back));
            TOLERANCE.assertWithin(sequence + " RFC 3551 frame " + f, params, back);
            GsmFrames.packMs(params, block, 0, false);
            GsmFrames.packMs(second, block, 0, true);
            GsmFrames.unpackMs(block, 0, false, back);
            TOLERANCE.assertWithin(sequence + " Microsoft block " + f + " first frame", params, back);
            GsmFrames.unpackMs(block, 0, true, back);
            TOLERANCE.assertWithin(sequence + " Microsoft block " + f + " second frame", second, back);
        }
    }

    /** Parses an operand or result of {@code add_test.dta}: {@code -} and {@code +} are the word limits. */
    private static long stlValue(String token) {
        return switch (token) {
            case "-" -> Short.MIN_VALUE;
            case "+" -> Short.MAX_VALUE;
            case "--" -> Integer.MIN_VALUE;
            case "++" -> Integer.MAX_VALUE;
            default -> {
                var negative = token.startsWith("-");
                var digits = negative || token.startsWith("+") ? token.substring(1) : token;
                var magnitude = digits.startsWith("0x") || digits.startsWith("0X")
                        ? Long.parseLong(digits.substring(2), 16) : Long.parseLong(digits);
                yield negative ? -magnitude : magnitude;
            }
        };
    }

    @org.junit.jupiter.api.Test
    @DisplayName("the basic arithmetic of the RPE-LTP filters meets the Software Tool Library's add_test.dta")
    void basicArithmetic() throws IOException {
        // add_test.dta of the ITU-T STL RPE-LTP (cbacae9, Degener and Bormann's libgsm), as add_test.c runs it: each
        // line names an operation, its operands and the expected result, words truncated to 16 bits. The coder has
        // no 32-bit multiply, subtract or shifts of its own (it writes them inline), so the L_mult, L_sub, L_<< and
        // L_>> lines have no counterpart; GSM_MULT_R is the macro form, defined for operands not both MIN_WORD
        var lines = Files.readAllLines(Conformance.vector("rpeltp-stl-add_test.dta"), java.nio.charset.StandardCharsets.US_ASCII);
        var checked = 0;
        for (var line : lines) {
            if (line.isBlank() || line.startsWith(";") || line.startsWith("'") || line.startsWith("\"")
                    || line.startsWith("#") || !line.contains("=")) {
                continue;
            }
            var sides = line.split("=", 2);
            var tokens = sides[0].trim().split("[\\s(),]+");
            var operation = tokens[0];
            var a = tokens.length > 1 ? stlValue(tokens[1]) : 0;
            var b = tokens.length > 2 ? stlValue(tokens[2]) : 0;
            var expected = stlValue(sides[1].trim().split("\\s+")[0]);
            short wa = (short) a;
            short wb = (short) b;
            Long actual = switch (operation) {
                case "add" -> (long) Gsm0610.add(wa, wb);
                case "sub" -> (long) Gsm0610.sub(wa, wb);
                case "mult" -> (long) Gsm0610.multGuarded(wa, wb);
                case "mult_r" -> wa == Short.MIN_VALUE && wb == Short.MIN_VALUE ? null : (long) Gsm0610.multR(wa, wb);
                case "abs" -> (long) Gsm0610.abs(wa);
                case "div" -> (long) Gsm0610.div(wa, wb);
                case "norm" -> (long) (short) Gsm0610.norm((int) a);
                case "<<" -> (long) Gsm0610.asl(wa, wb);
                case ">>" -> (long) Gsm0610.asr(wa, wb);
                case "L_add" -> Gsm0610.lAdd((int) a, (int) b);
                case "L_mult", "L_sub", "L_<<", "L_>>" -> null;
                default -> throw new AssertionError("unknown operation in " + line);
            };
            if (actual == null) {
                continue;
            }
            var want = operation.startsWith("L_") ? (long) (int) expected : (long) (short) expected;
            assertEquals(want, actual, line.trim());
            checked++;
        }
        assertEquals(188, checked, "lines checked");
    }
}
