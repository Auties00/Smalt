package com.github.auties00.smalt.audio.hvxc;

import com.github.auties00.smalt.audio.hvxc.internal.HvxcCore;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compares the HVXC core with the ISO/IEC 14496-3 reference decoder on the paths the ISO/IEC 14496-26 streams leave
 * out: the random phases and noise of normal operation, the 3.7 kbit/s mode, variable rate frames at 2 and 4 kbit/s
 * with background noise frames, the scalable mode with frames that lack the enhancement layer, the error
 * concealment that class CRC failures drive, and speed and pitch change in every mode. The frames are random bits
 * (every field has a fixed width), except that a variable rate frame of background noise only follows unvoiced
 * frames, as encoders write it; the fixture holds the RMS and three samples of each 160-sample block of the
 * reference decoder's output, written by {@code tools/testdata/hvxc-oracle/hvxc-oracle.py}, whose frame generator
 * {@link #frames} matches.
 *
 * <p>The tolerance is one LSB on the samples and the RMS values, room for a last-place difference of the math
 * library; the decoder matches the reference exactly on every case.
 */
@DisplayName("MPEG-4 HVXC against the reference decoder")
class HvxcReferenceDifferentialTest {
    private static final Tolerance TOLERANCE = Tolerance.maxAbs(1.0);

    private static final int CASES = 54;

    private static final int FRAMES = 30;

    /**
     * One case of the fixture.
     *
     * @param values per 160-sample block, the RMS and three samples, in 16-bit units
     */
    private record Case(String name, boolean variable, int rateMode, boolean extension, boolean scalable,
                        boolean protection, boolean lowDelay, int testMode, float speed, float pitch, int enhancement,
                        double[] values) {
    }

    @TestFactory
    @DisplayName("every mode matches the reference decoder")
    Stream<DynamicTest> cases() throws IOException {
        var cases = new ArrayList<DynamicTest>();
        try (var reader = new BufferedReader(new InputStreamReader(HvxcReferenceDifferentialTest.class
                .getResourceAsStream("reference-decoder.txt"), StandardCharsets.US_ASCII))) {
            for (var line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.startsWith("#")) {
                    var parsed = parse(line);
                    cases.add(DynamicTest.dynamicTest(parsed.name(), () -> check(parsed)));
                }
            }
        }
        assertEquals(CASES, cases.size());
        return cases.stream();
    }

    private static Case parse(String line) {
        var parts = line.split(" ");
        var values = new double[parts.length - 11];
        for (var i = 0; i < values.length; i++) {
            values[i] = Double.parseDouble(parts[11 + i]);
        }
        return new Case(parts[0], parts[1].equals("1"), Integer.parseInt(parts[2]), parts[3].equals("1"),
                parts[4].equals("1"), parts[5].equals("1"), parts[6].equals("0"), Integer.parseInt(parts[7]),
                Float.parseFloat(parts[8]), Float.parseFloat(parts[9]), Integer.parseInt(parts[10]), values);
    }

    private static void check(Case test) {
        var core = new HvxcCore(test.variable(), test.rateMode(), test.extension(), test.scalable(), test.protection(),
                test.lowDelay(), test.testMode(), test.speed(), test.pitch());
        var samples = new float[core.maxSamples()];
        var actual = new ArrayList<Double>();
        var errors = new boolean[5];
        var random = new int[]{seed(test.name())};
        var previous = 0;
        for (var frame = 0; frame < FRAMES; frame++) {
            var data = new byte[10];
            for (var i = 0; i < 10; i++) {
                data[i] = (byte) next(random);
            }
            var extra = next(random);
            if (test.variable()) {
                var vuv = (data[0] & 0xFF) >>> 6;
                if (vuv == 1 && previous != 0) {
                    data[0] &= 0x3F;
                    vuv = 0;
                }
                if (vuv != 1) {
                    previous = vuv;
                }
            }
            var enhanced = test.enhancement() == 1 || (test.enhancement() == 2 && (extra & 1) != 0);
            var crc = test.protection() && Integer.remainderUnsigned(extra >>> 4, 4) == 0 ? (extra >>> 8) & 0x1F : 0;
            for (var c = 0; c < errors.length; c++) {
                errors[c] = ((crc >>> c) & 1) != 0;
            }
            var count = core.decode(data, enhanced ? HvxcCore.RATE_4K : test.rateMode(), errors, samples, 0);
            for (var block = 0; block < count; block += 160) {
                var energy = 0.0;
                for (var i = 0; i < 160; i++) {
                    energy += (double) samples[block + i] * samples[block + i];
                }
                actual.add(Math.round(Math.sqrt(energy / 160) * 100) / 100.0);
                for (var k = 1; k <= 3; k++) {
                    actual.add((double) samples[block + 160 * k / 4]);
                }
            }
        }
        assertEquals(test.values().length, actual.size(), "values");
        var expected = new float[test.values().length];
        var measured = new float[expected.length];
        for (var i = 0; i < expected.length; i++) {
            expected[i] = (float) test.values()[i];
            measured[i] = actual.get(i).floatValue();
        }
        TOLERANCE.assertPcm(test.name(), expected, measured);
    }

    /**
     * Seeds the 32-bit xorshift generator of the fixture's script with the FNV-1a hash of a case name.
     */
    private static int seed(String name) {
        var state = 0x811C9DC5;
        for (var character : name.getBytes(StandardCharsets.US_ASCII)) {
            state = (state ^ character) * 0x01000193;
        }
        return state == 0 ? 1 : state;
    }

    private static int next(int[] random) {
        var x = random[0];
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        random[0] = x;
        return x;
    }
}
