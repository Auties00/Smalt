package com.github.auties00.smalt.audio.atrac9;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Compares the ATRAC9 decoder with LibAtrac9 ({@code tools/testdata/atrac9-oracle}'s {@code smalt-at9dec}, built
 * from the library's C sources without fused multiply-adds), sample for sample: on syntax-random streams of every
 * sampling rate, channel configuration and superframe size, which code every element of the bitstream with legal
 * values; on audio coded by the test encoder; and on corrupt streams, where both decoders must decode the same
 * superframes before stopping at the same one, unless the decoder stops earlier at a superframe whose parameters the
 * reference has no defined result for.
 */
@DisplayName("ATRAC9 decoder against LibAtrac9")
class Atrac9DifferentialTest {
    private static final Tolerance TOLERANCE = Tolerance.exact();

    /** The reference's decode: the samples, and the index of the superframe it failed at, or -1. */
    record Reference(short[] samples, int failedAt) {
    }

    static Reference reference(Atrac9TestStreams.Stream stream, String name) {
        Oracle.assume(Oracle.Tool.AT9DEC);
        var directory = Scratch.directory("atrac9-oracle");
        var input = directory.resolve(name + ".a9");
        var output = directory.resolve(name + ".pcm");
        try {
            Files.write(input, stream.concatenated());
            var status = new String(Oracle.run(Oracle.Tool.AT9DEC, Duration.ofMinutes(2),
                    List.of(input.toString(), output.toString())), StandardCharsets.US_ASCII).trim();
            var bytes = Files.readAllBytes(output);
            var samples = new short[bytes.length / 2];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
            Files.deleteIfExists(input);
            Files.deleteIfExists(output);
            var failedAt = status.startsWith("error ") ? Integer.parseInt(status.split(" ")[1]) : -1;
            if (failedAt < 0 && !status.startsWith("ok ")) {
                fail(name + ": unexpected reference status " + status);
            }
            return new Reference(samples, failedAt);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** The result of the decoder: the samples of the superframes before the first corrupt one, and its index. */
    record Decode(short[] samples, int failedAt, MediaException.Malformed failure) {
    }

    static Decode decode(Atrac9TestStreams.Stream stream) {
        var decoder = new Atrac9Decoder(Atrac9DecoderTest.track(stream.config()));
        var channels = decoder.layout().channels();
        var samples = new java.io.ByteArrayOutputStream();
        for (var i = 0; i < stream.superframes().size(); i++) {
            var superframe = new java.io.ByteArrayOutputStream();
            try {
                decoder.decodeIntegers(Atrac9DecoderTest.packet(stream.superframes().get(i)), block -> {
                    for (var t = 0; t < block.frames(); t++) {
                        for (var c = 0; c < channels; c++) {
                            var value = block.plane(c)[t];
                            superframe.write(value);
                            superframe.write(value >> 8);
                        }
                    }
                });
            } catch (MediaException.Malformed failure) {
                return new Decode(shorts(samples.toByteArray()), i, failure);
            }
            samples.writeBytes(superframe.toByteArray());
        }
        return new Decode(shorts(samples.toByteArray()), -1, null);
    }

    private static short[] shorts(byte[] bytes) {
        var samples = new short[bytes.length / 2];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
        return samples;
    }

    static void assertMatches(Atrac9TestStreams.Stream stream, String name) {
        var expected = reference(stream, name);
        var actual = decode(stream);
        if (actual.failedAt() >= 0 && (expected.failedAt() < 0 || actual.failedAt() < expected.failedAt())) {
            // the decoder rejects what the reference reads with undefined behavior
            var message = actual.failure().getMessage();
            assertTrue(message.contains("fine precision") || message.contains("band extension above")
                    || message.contains("outside its table") || message.contains("gradient units"),
                    name + ": rejected superframe " + actual.failedAt() + " that the reference decodes: " + message);
            var samples = actual.samples().length;
            var prefix = java.util.Arrays.copyOf(expected.samples(), samples);
            TOLERANCE.assertPcm(name, prefix, actual.samples());
            return;
        }
        assertEquals(expected.failedAt(), actual.failedAt(), name + ": first corrupt superframe");
        TOLERANCE.assertPcm(name, expected.samples(), actual.samples());
    }

    static Stream<Arguments> configurations() {
        var cases = new ArrayList<Arguments>();
        for (var rate = 0; rate < 16; rate++) {
            for (var channels = 0; channels < 6; channels++) {
                cases.add(Arguments.of(rate, channels, (rate + channels) % 3 == 0 ? 0 : 2));
            }
        }
        cases.add(Arguments.of(7, 2, 1));
        cases.add(Arguments.of(5, 3, 3));
        return cases.stream();
    }

    @ParameterizedTest(name = "rate index {0}, channel configuration {1}, superframe index {2}")
    @MethodSource("configurations")
    @DisplayName("decodes syntax-random streams as the reference does")
    void randomSyntax(int rateIndex, int channelConfig, int superframeIndex) {
        for (var seed = 0; seed < 4; seed++) {
            var stream = Atrac9TestStreams.random(rateIndex, channelConfig, superframeIndex, 24,
                    rateIndex * 1000L + channelConfig * 10L + seed);
            var name = "random-" + rateIndex + "-" + channelConfig + "-" + superframeIndex + "-" + seed;
            var reference = reference(stream, name);
            assertEquals(-1, reference.failedAt(), name + ": the reference rejects a legal stream");
            assertMatches(stream, name);
        }
    }

    static Stream<Arguments> encodings() {
        return Atrac9DecoderTest.encodings();
    }

    @ParameterizedTest(name = "rate index {0}, channel configuration {1}, superframe index {2}, delta scale factors {3}")
    @MethodSource("encodings")
    @DisplayName("decodes encoded audio as the reference does")
    void encoded(int rateIndex, int channelConfig, int superframeIndex, boolean vlc) {
        var rate = Atrac9Tables.SAMPLE_RATES[rateIndex];
        var input = Atrac9DecoderTest.signal(Atrac9Tables.CHANNEL_COUNTS[channelConfig], 8000, rate, rateIndex);
        var stream = Atrac9TestStreams.encode(rateIndex, channelConfig, superframeIndex, 1024, input, vlc);
        assertMatches(stream, "encoded-" + rateIndex + "-" + channelConfig + "-" + superframeIndex + "-" + vlc);
    }

    @Test
    @DisplayName("stops at the same corrupt superframe as the reference")
    void corrupt() {
        var random = new Random(41);
        for (var round = 0; round < 160; round++) {
            var stream = Atrac9TestStreams.random(round % 16, round % 6, round % 4 == 0 ? 0 : 2, 6, 5000 + round);
            var superframes = new ArrayList<byte[]>();
            for (var superframe : stream.superframes()) {
                var data = superframe.clone();
                if (random.nextInt(3) == 0) {
                    for (var flips = 0; flips < 1 + random.nextInt(6); flips++) {
                        data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
                    }
                }
                superframes.add(data);
            }
            assertMatches(new Atrac9TestStreams.Stream(stream.config(), superframes), "corrupt-" + round);
        }
    }
}
