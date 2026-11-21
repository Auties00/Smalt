package com.github.auties00.smalt.audio.alac;

import com.github.auties00.smalt.audio.alac.AlacSignals.Shape;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compares {@link AlacEncoder} with Apple's reference encoder ({@code tools/testdata/alac-oracle}'s
 * {@code smalt-alacenc}, built from the library's sources): for the same samples, frame length and fast setting, every
 * packet must be identical byte for byte, and so must the magic cookie except for the fields the reference leaves
 * unset (the average bitrate) or writes in its host's byte order (the channel layout tag). The cases cover every
 * sample size, channel count and signal shape, streams of one to several frames with a partial last frame (the
 * shortest of which make the reference's searches read buffer entries left by earlier elements and frames), fast
 * coding, and several frame lengths.
 */
@DisplayName("ALAC encoder against Apple's reference encoder")
class AlacEncoderOracleTest {
    /** The reference's output: the magic cookie and the packets. */
    record Reference(byte[] cookie, List<byte[]> packets) {
    }

    static Reference reference(int[][] samples, int bits, int rate, int frameLength, boolean fast, String name) {
        var directory = Scratch.directory("alac-oracle");
        var input = directory.resolve(name + ".raw");
        var output = directory.resolve(name + ".out");
        var interleaved = AlacSignals.interleave(samples);
        var bytes = ByteBuffer.allocate(interleaved.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        bytes.asIntBuffer().put(interleaved);
        try {
            Files.write(input, bytes.array());
            Oracle.run(Oracle.Tool.ALACENC, Duration.ofMinutes(2), List.of(input.toString(),
                    Integer.toString(samples.length), Integer.toString(bits), Integer.toString(rate),
                    Integer.toString(frameLength), fast ? "1" : "0", output.toString()));
            var data = ByteBuffer.wrap(Files.readAllBytes(output));
            var cookie = new byte[data.getInt()];
            data.get(cookie);
            var packets = new ArrayList<byte[]>();
            while (data.hasRemaining()) {
                var packet = new byte[data.getInt()];
                data.get(packet);
                packets.add(packet);
            }
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
            return new Reference(cookie, packets);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static void assertMatches(int[][] samples, int bits, int rate, int frameLength, boolean fast, String name) {
        Oracle.assume(Oracle.Tool.ALACENC);
        var channels = samples.length;
        var encoder = new AlacEncoder(AlacEncoderOptions.builder().format(rate, AlacSignals.bitstreamLayout(channels))
                .bitsPerSample(bits).frameLength(frameLength).fast(fast).build());
        var packets = AlacEncoderTest.encode(encoder, samples);
        var expected = reference(samples, bits, rate, frameLength, fast, name.replaceAll("[^A-Za-z0-9-]", "_"));
        assertEquals(expected.packets().size(), packets.size(), name + ": packet count");
        for (var index = 0; index < packets.size(); index++) {
            var actual = packets.get(index).data();
            var wanted = expected.packets().get(index);
            if (!Arrays.equals(wanted, actual)) {
                var at = Arrays.mismatch(wanted, actual);
                fail(name + ": packet " + index + " of " + actual.length + " bytes (reference " + wanted.length
                        + ") differs at byte " + at + ": reference "
                        + HexFormat.of().formatHex(wanted, at, Math.min(wanted.length, at + 16)) + ", Smalt "
                        + HexFormat.of().formatHex(actual, at, Math.min(actual.length, at + 16)));
            }
        }
        var cookie = encoder.magicCookie();
        var reference = expected.cookie().clone();
        assertEquals(reference.length, cookie.length, name + ": cookie size");
        // the reference leaves the average bitrate zero and copies the layout tag in the host's (little-endian)
        // byte order
        System.arraycopy(cookie, 16, reference, 16, 4);
        if (channels > 2) {
            var tag = ByteBuffer.wrap(reference, 36, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            ByteBuffer.wrap(reference, 36, 4).putInt(tag);
        }
        assertArrayEquals(reference, cookie, name + ": magic cookie");
    }

    static Stream<Arguments> cases() {
        var arguments = new ArrayList<Arguments>();
        var lengths = new int[]{1, 7, 31, 287, 4096, 4097, 3 * 4096 + 1000, 20_000};
        var seed = 0L;
        for (var bits : new int[]{16, 20, 24, 32}) {
            for (var channels = 1; channels <= 8; channels++) {
                for (var shape : Shape.values()) {
                    var frames = lengths[(int) (seed % lengths.length)];
                    arguments.add(Arguments.of(bits, channels, shape, frames, false, seed));
                    if (channels == 2) {
                        arguments.add(Arguments.of(bits, channels, shape, frames, true, seed));
                    }
                    seed++;
                }
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0}-bit {1} ch {2} {3} frames fast={4}")
    @MethodSource("cases")
    @DisplayName("writes the reference encoder's packets")
    void identical(int bits, int channels, Shape shape, int frames, boolean fast, long seed) {
        var samples = AlacSignals.samples(shape, channels, frames, bits, seed);
        var name = bits + "-" + channels + "-" + shape + "-" + frames + "-" + fast;
        assertMatches(samples, bits, 44100, 4096, fast, name);
    }

    static Stream<Arguments> frameLengths() {
        return Stream.of(16, 17, 100, 352, 1000, 8192, 65536).map(Arguments::of);
    }

    @ParameterizedTest(name = "frame length {0}")
    @MethodSource("frameLengths")
    @DisplayName("writes the reference encoder's packets at other frame lengths")
    void frameLength(int frameLength) {
        var shapes = new Shape[]{Shape.MUSIC, Shape.CORRELATED, Shape.IMPULSES};
        for (var channels : new int[]{1, 2, 5}) {
            for (var bits : new int[]{16, 24, 32}) {
                for (var fast : new boolean[]{false, true}) {
                    var shape = shapes[(channels + bits) % shapes.length];
                    var samples = AlacSignals.samples(shape, channels, frameLength * 3 + frameLength / 2 + 3, bits,
                            frameLength + channels);
                    assertMatches(samples, bits, 48000, frameLength, fast,
                            "length-" + frameLength + "-" + channels + "-" + bits + "-" + fast);
                }
            }
        }
    }

    @Test
    @DisplayName("writes the reference encoder's packets when a compressed element is taken back and escaped")
    void lateNoise() {
        for (var bits : new int[]{16, 20, 24, 32}) {
            for (var channels = 1; channels <= 3; channels++) {
                for (var fast : new boolean[]{false, true}) {
                    // music, then frames whose first eighth is silent and the rest noise, which the searches
                    // underestimate, then music again
                    var samples = AlacSignals.samples(Shape.MUSIC, channels, 5 * 4096 + 77, bits, bits + channels);
                    var noise = AlacSignals.samples(Shape.NOISE, channels, 2 * 4096, bits, bits * channels);
                    for (var c = 0; c < channels; c++) {
                        System.arraycopy(noise[c], 0, samples[c], 4096, 2 * 4096);
                        Arrays.fill(samples[c], 4096, 4096 + 512, 0);
                        Arrays.fill(samples[c], 2 * 4096, 2 * 4096 + 512, 0);
                    }
                    assertMatches(samples, bits, 44100, 4096, fast, "late-noise-" + bits + "-" + channels + "-" + fast);
                }
            }
        }
    }

    @Test
    @DisplayName("writes the reference encoder's packets over a long stream whose state carries across frames")
    void longStream() {
        var music = AlacSignals.samples(Shape.MUSIC, 2, 441_000, 16, 99);
        // a silent gap, a noise burst and a quiet stretch inside the music
        for (var c = 0; c < 2; c++) {
            Arrays.fill(music[c], 100_000, 140_000, 0);
            var noise = AlacSignals.samples(Shape.NOISE, 1, 20_000, 16, c)[0];
            System.arraycopy(noise, 0, music[c], 200_000, noise.length);
            var quiet = AlacSignals.samples(Shape.QUIET_NOISE, 1, 30_000, 16, c)[0];
            System.arraycopy(quiet, 0, music[c], 300_000, quiet.length);
        }
        assertMatches(music, 16, 44100, 4096, false, "long-stereo");
        var surround = AlacSignals.samples(Shape.MUSIC, 6, 96_000, 24, 7);
        assertMatches(surround, 24, 48000, 4096, false, "long-5.1");
    }
}
