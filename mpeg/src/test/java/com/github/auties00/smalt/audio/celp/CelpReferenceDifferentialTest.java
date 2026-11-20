package com.github.auties00.smalt.audio.celp;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Compares the decoder with the ISO/IEC 14496-3 reference decoder on what the ISO/IEC 14496-26 streams leave out: every
 * {@code MPE_Configuration} and {@code RPE_Configuration}, with fine rate control and silence compression, bitrate
 * and bandwidth scalable layers, alone and combined with those tools, and streams that start in a non-active period.
 * Fine rate control with a bandwidth scalable layer is left out, as the reference decoder does not implement it. The
 * streams are frames of random bits (every field has a fixed width); the fixture
 * holds the RMS and three samples of each frame of the reference decoder's output, written by
 * {@code tools/testdata/celp-oracle/celp-oracle.py}, whose frame generator {@link #frames} matches.
 *
 * <p>The tolerance is not the accuracy of ISO/IEC 11172-4, as such frames are far louder than speech and the reference
 * decoder's single precision arithmetic differs from this decoder's by more there: a peak error of {@value #PEAK_LSB}
 * LSB, half of an LSB of it the rounding of the fixture. Two cases take more: the random LSPs of {@code nb22b3s} make a
 * filter so nearly unstable that single precision changes the product of {@code 1 - k^2} by 3 %, and the signal of
 * {@code nb22e1b1sc} peaks at 1.7 times full scale, where the difference reaches 0.1 % of it for a few frames.
 */
@DisplayName("MPEG-4 CELP against the reference decoder")
class CelpReferenceDifferentialTest {
    private static final int PEAK_LSB = 16;

    private static final Tolerance TOLERANCE = Tolerance.maxAbs(PEAK_LSB / 32768.0);

    private static final Tolerance NEARLY_UNSTABLE = Tolerance.maxAbs(160 / 32768.0);

    private static final java.util.Set<String> NEARLY_UNSTABLE_CASES = java.util.Set.of("nb22b3s", "nb22e1b1sc");

    private static final int CASES = 442;

    private static final int FRAMES = 16;

    private static final int UNIT_BYTES = 140;

    private static final int[] TX_PATTERN = {1, 1, 1, 1, 1, 2, 0, 0, 3, 0, 2, 3, 1, 1, 3, 0};

    /**
     * The {@code TX_flag} of a case whose name ends in b, c or d: a stream that opens with a SID frame, one that opens
     * without SID information, and periods of one frame.
     */
    private static final int[][] TX_PATTERNS = {{2, 0, 3, 3, 2, 3, 2, 0, 0, 3, 2, 2, 3, 0, 1, 1},
            {0, 0, 3, 0, 2, 0, 0, 3, 1, 1, 1, 3, 2, 2, 2, 2}, {1, 2, 1, 3, 1, 0, 1, 2, 2, 1, 1, 0, 2, 1, 3, 3}};

    private static final int[] RPE_SUBFRAMES = {6, 4, 8, 10};

    /**
     * One case of the fixture.
     *
     * @param values per frame, the RMS and three samples, in 16-bit units
     */
    private record Case(String name, int objectType, boolean rpe, boolean wideband, boolean fineRate, boolean silence,
                        int configuration, int layers, int bandwidth, int[] values) {
        int streams() {
            return 1 + layers + (bandwidth >= 0 ? 1 : 0);
        }
    }

    @TestFactory
    @DisplayName("every configuration matches the reference decoder")
    Stream<DynamicTest> cases() throws IOException {
        var cases = new ArrayList<DynamicTest>();
        try (var reader = new BufferedReader(new InputStreamReader(CelpReferenceDifferentialTest.class
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
        var values = new int[parts.length - 9];
        for (var i = 0; i < values.length; i++) {
            values[i] = Integer.parseInt(parts[9 + i]);
        }
        return new Case(parts[0], Integer.parseInt(parts[1]), parts[2].equals("1"), parts[3].equals("1"),
                parts[4].equals("1"), parts[5].equals("1"), Integer.parseInt(parts[6]), Integer.parseInt(parts[7]),
                Integer.parseInt(parts[8]), values);
    }

    private static void check(Case test) {
        var tracks = new ArrayList<AudioTrack>();
        tracks.add(track(1, base(test)));
        for (var layer = 1; layer <= test.layers(); layer++) {
            tracks.add(track(tracks.size() + 1, layer(test, false, layer)));
        }
        if (test.bandwidth() >= 0) {
            tracks.add(track(tracks.size() + 1, layer(test, true, test.bandwidth())));
        }
        var expected = new float[test.values().length];
        var actual = new float[expected.length];
        for (var i = 0; i < expected.length; i++) {
            expected[i] = test.values()[i] / 32768f;
        }
        var position = new int[1];
        try (var decoder = new CelpDecoder(tracks, CelpDecoderOptions.builder().postFilter(false).build())) {
            for (var units : frames(test)) {
                decoder.decode(units, buffer -> {
                    var size = buffer.frames();
                    var plane = buffer.plane(0);
                    var energy = 0.0;
                    for (var i = 0; i < size; i++) {
                        energy += (double) plane[i] * plane[i];
                    }
                    actual[position[0]++] = (float) Math.sqrt(energy / size);
                    for (var k = 1; k <= 3; k++) {
                        actual[position[0]++] = plane[size * k / 4];
                    }
                });
            }
        }
        assertEquals(expected.length, position[0], "samples");
        (NEARLY_UNSTABLE_CASES.contains(test.name()) ? NEARLY_UNSTABLE : TOLERANCE).assertPcm(test.name(), expected, actual);
    }

    private static AudioTrack track(int id, byte[] config) {
        return AudioTrack.builder().id(id).codec(Codec.CELP).timeBase(new Rational(1, 16000)).codecConfig(config)
                .build();
    }

    private static byte[] base(Case test) {
        var writer = new MsbBitWriter();
        writer.write(test.objectType(), 5);
        writer.write(test.wideband() ? 8 : 11, 4);
        writer.write(1, 4);
        writer.write(1, 1);
        writer.write(test.rpe() ? 1 : 0, 1);
        writer.write(test.wideband() ? 1 : 0, 1);
        writer.write(test.fineRate() ? 1 : 0, 1);
        if (test.objectType() == 24) {
            writer.write(test.silence() ? 1 : 0, 1);
        }
        if (test.rpe()) {
            writer.write(test.configuration(), 3);
        } else {
            writer.write(test.configuration(), 5);
            writer.write(test.layers(), 2);
            writer.write(test.bandwidth() >= 0 ? 1 : 0, 1);
        }
        if (test.objectType() == 24) {
            writer.write(0, 2);
        }
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    private static byte[] layer(Case test, boolean bandwidth, int id) {
        var writer = new MsbBitWriter();
        writer.write(test.objectType(), 5);
        writer.write(bandwidth ? 8 : 11, 4);
        writer.write(1, 4);
        writer.write(0, 1);
        writer.write(bandwidth ? 1 : 0, 1);
        writer.write(id, 2);
        if (test.objectType() == 24) {
            writer.write(0, 2);
        }
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    /**
     * Generates the access units of a case, as the fixture's script does: random bytes from a 32-bit xorshift generator
     * seeded by the FNV-1a hash of the case name, with the fields that conforming streams restrict set.
     */
    private static List<List<Packet>> frames(Case test) {
        var state = 0x811C9DC5;
        for (var character : test.name().getBytes(StandardCharsets.US_ASCII)) {
            state = (state ^ character) * 0x01000193;
        }
        var random = new int[]{state == 0 ? 1 : state};
        var last = test.name().charAt(test.name().length() - 1);
        var pattern = last >= 'b' && last <= 'd' ? TX_PATTERNS[last - 'b'] : TX_PATTERN;
        var pending = false;
        var result = new ArrayList<List<Packet>>();
        for (var frame = 0; frame < FRAMES; frame++) {
            var units = new ArrayList<Packet>();
            for (var stream = 0; stream < test.streams(); stream++) {
                var data = new byte[UNIT_BYTES];
                for (var i = 0; i < data.length; i++) {
                    data[i] = (byte) next(random);
                }
                if (stream == 0) {
                    var position = 0;
                    var tx = 1;
                    if (test.silence()) {
                        tx = pattern[frame];
                        setBits(data, 0, 2, tx);
                        position = 2;
                    }
                    var lsp = true;
                    if (test.fineRate() && tx == 1) {
                        var interpolation = false;
                        var present = false;
                        if (pending) {
                            pending = false;
                        } else {
                            interpolation = (next(random) & 1) != 0;
                            present = true;
                            pending = interpolation;
                        }
                        setBits(data, position, 1, interpolation ? 1 : 0);
                        setBits(data, position + 1, 1, present ? 1 : 0);
                        position += 2;
                        lsp = present;
                    } else if (test.fineRate()) {
                        pending = false;
                    }
                    if (test.rpe() && tx == 1) {
                        var subframes = RPE_SUBFRAMES[test.configuration()];
                        if (test.objectType() == 8) {
                            position += lsp ? 46 : 0;
                            var shape = test.configuration() < 2 ? 11 : 12;
                            for (var subframe = 0; subframe < subframes; subframe++) {
                                setBits(data, position + 8 + shape, 3, 0);
                                setBits(data, position + 8 + shape + 6, 1, 0);
                                position += 8 + shape + 6 + (subframe == 0 ? 5 : 3);
                            }
                        } else {
                            position += lsp ? 15 : 0;
                            for (var subframe = 0; subframe < subframes; subframe++) {
                                setBits(data, position, 3, 0);
                                setBits(data, position + 3, 1, 0);
                                position += 3 + (subframe == 0 ? 2 : 1);
                            }
                        }
                    }
                }
                units.add(new Packet(stream + 1, data, frame, frame, 1, true));
            }
            result.add(units);
        }
        return result;
    }

    private static int next(int[] state) {
        var x = state[0];
        x ^= x << 13;
        x ^= x >>> 17;
        x ^= x << 5;
        state[0] = x;
        return x;
    }

    private static void setBits(byte[] data, int position, int width, int value) {
        for (var i = 0; i < width; i++) {
            var at = position + i;
            var mask = 0x80 >> (at & 7);
            var bit = (value >> (width - 1 - i)) & 1;
            data[at >> 3] = (byte) ((data[at >> 3] & ~mask) | (bit * mask));
        }
    }
}
