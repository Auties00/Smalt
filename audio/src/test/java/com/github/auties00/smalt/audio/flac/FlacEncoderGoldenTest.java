package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the FLAC encoder's bitstream: each configuration encodes a fixed signal and the SHA-256 of
 * its packets and final STREAMINFO must equal a recorded digest, so a kernel rewrite that claims
 * identical output is checked byte for byte. The digests hold for the Vector API kernel and for its
 * scalar twin alike: the suite's scalar leg ({@code -Dsmalt.test.simd=false}) runs the same
 * digests.
 */
@DisplayName("FLAC encoder golden bitstreams")
class FlacEncoderGoldenTest {
    static Stream<Arguments> configurations() {
        return Stream.of(
                Arguments.of("level 0, 16-bit stereo music", FlacEncoderOptions.ofCompressionLevel(0),
                        FlacEncoding.Shape.MUSIC, 2, 16,
                        "658792778794c0e18329ac52b8ee218d7a8e55ac49535f1003b681b78d540649"),
                Arguments.of("level 5, 16-bit stereo music", FlacEncoderOptions.ofCompressionLevel(5),
                        FlacEncoding.Shape.MUSIC, 2, 16,
                        "7b402677bb08f53c45cbc3e2f42feb2e7b08fd14ea53504399f5f9170e052bfe"),
                Arguments.of("level 8, 16-bit stereo music", FlacEncoderOptions.ofCompressionLevel(8),
                        FlacEncoding.Shape.MUSIC, 2, 16,
                        "2e5fff55bdf64d2cd6ccdf231f5344314920ea8645c7c1b85bb92f1936069ca6"),
                Arguments.of("level 8, 24-bit stereo music", FlacEncoderOptions.ofCompressionLevel(8).toBuilder()
                        .bitsPerSample(24).build(),
                        FlacEncoding.Shape.MUSIC, 2, 24,
                        "fd8ae2d0d3b92eed3a301548716ac7fe651af1d8610904366f3ad6956f14bc95"),
                Arguments.of("level 5, 20-bit stereo noise", FlacEncoderOptions.ofCompressionLevel(5).toBuilder()
                        .bitsPerSample(20).build(),
                        FlacEncoding.Shape.NOISE, 2, 20,
                        "77d257cbf944e24d865e2c4574a18c1fb5d3ea73bd2615ec953963af19829d33"),
                Arguments.of("level 5, 32-bit stereo extremes", FlacEncoderOptions.ofCompressionLevel(5).toBuilder()
                        .bitsPerSample(32).build(),
                        FlacEncoding.Shape.EXTREMES, 2, 32,
                        "d73bcd770840d6002af3abbdff2c6c4da2b3e8ee9a5834771fc0503dc708712a"),
                Arguments.of("smallest, exhaustive, order 32, 24-bit stereo music", FlacEncoderOptions.forSize()
                        .toBuilder().bitsPerSample(24).exhaustiveModelSearch(true).precisionSearch(true)
                        .maxLpcOrder(32).partitionOrders(0, 8).build(), FlacEncoding.Shape.MUSIC, 2, 24,
                        "20fc6b54b983ff401d894c3cc5c6e558e519222621e61441e8e94c55318b6229"),
                Arguments.of("level 5, 8-bit mono square", FlacEncoderOptions.ofCompressionLevel(5)
                        .toBuilder().format(22_050, ChannelLayout.MONO).bitsPerSample(8).build(),
                        FlacEncoding.Shape.SQUARE, 1, 8,
                        "70cc8055893d471c33df3bde9827aa7a6c78d51754fe631d1588f56971959445"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurations")
    @DisplayName("produce exactly the recorded stream")
    void unchanged(String name, FlacEncoderOptions options, FlacEncoding.Shape shape, int channels, int bits,
                   String expected) {
        var samples = FlacEncoding.signal(shape, channels, bits, 50_000, name.length() * 7919L);
        assertEquals(expected, digest(options, samples), name);
    }

    private static String digest(FlacEncoderOptions options, int[][] samples) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        var encoded = FlacEncoding.encode(options, samples);
        for (var packet : encoded.packets()) {
            update(sha, packet);
        }
        sha.update(encoded.config());
        return HexFormat.of().formatHex(sha.digest());
    }

    private static void update(MessageDigest sha, Packet packet) {
        sha.update(packet.data());
        sha.update(ByteBuffer.allocate(24).putLong(packet.pts()).putLong(packet.dts()).putLong(packet.duration()).array());
    }
}
