package com.github.auties00.smalt.audio.mp3;

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
 * Pins the MP3 encoder's bitstream: each configuration encodes a fixed signal and the SHA-256 of
 * its frames must equal a recorded digest, so a kernel rewrite that claims identical output is
 * checked byte for byte. The digests hold for the Vector API kernel and for its scalar twin alike:
 * the suite's scalar leg ({@code -Dsmalt.test.simd=false}) runs the same digests.
 */
@DisplayName("MP3 encoder golden bitstreams")
class Mp3EncoderGoldenTest {
    static Stream<Arguments> configurations() {
        return Stream.of(
                Arguments.of("defaults, stereo music", Mp3EncoderOptions.defaults(), Mp3Encoding.Shape.MUSIC, 2, 44_100,
                        "9d17aac651ef411e73182795b6289a55d56fc3b5a27825e286313b27bc9f5beb"),
                Arguments.of("VBR 2, stereo transients", Mp3EncoderOptions.ofVariableBitrate(2), Mp3Encoding.Shape.TRANSIENTS, 2, 44_100,
                        "801997423773e7b2fe9814a638253f6d65a3a6cca6ccb7a39ee88d8ae981114e"),
                Arguments.of("ABR 96 kbit/s, stereo noise", Mp3EncoderOptions.ofAverageBitrate(96_000), Mp3Encoding.Shape.NOISE, 2, 44_100,
                        "37ba948ad8dbd8883ef30fb888faaba3207018d48bd06ea169c0d425d5747756"),
                Arguments.of("real time, stereo music", Mp3EncoderOptions.forRealtime(), Mp3Encoding.Shape.MUSIC, 2, 48_000,
                        "aca120bbabf983d3b7b22073c4dfb02675b38e36d88c09b3b073ce68f9bf3dc6"),
                Arguments.of("CBR 64 kbit/s, 22.05 kHz mono sine", Mp3EncoderOptions.builder()
                        .format(22_050, ChannelLayout.MONO).bitrate(64_000).build(), Mp3Encoding.Shape.SINE, 1, 22_050,
                        "f71882e8732106c3e2cc828cec9cea1fa30ebd8c636d59f4d83a0e76c89edcc7"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("configurations")
    @DisplayName("produce exactly the recorded stream")
    void unchanged(String name, Mp3EncoderOptions options, Mp3Encoding.Shape shape, int channels, int rate,
                   String expected) {
        var samples = Mp3Encoding.signal(shape, channels, rate, rate * 3 / 2, name.length() * 7919L);
        assertEquals(expected, digest(options, samples), name);
    }

    private static String digest(Mp3EncoderOptions options, float[][] samples) {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
        for (var packet : Mp3Encoding.encode(options, samples)) {
            update(sha, packet);
        }
        return HexFormat.of().formatHex(sha.digest());
    }

    private static void update(MessageDigest sha, Packet packet) {
        sha.update(packet.data());
        sha.update(ByteBuffer.allocate(24).putLong(packet.pts()).putLong(packet.dts()).putLong(packet.duration()).array());
    }
}
