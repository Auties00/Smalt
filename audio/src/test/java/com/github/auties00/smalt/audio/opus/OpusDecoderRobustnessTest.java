package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Feeds the decoder deterministic mutations of valid streams (bit flips, truncations, rewritten
 * table-of-contents and frame-count bytes, padding and length corruption, inserted empty
 * packets) and checks that every packet either decodes or raises a {@link MediaException}, that
 * decoded samples stay finite, and that each stream finishes within a time bound.
 */
class OpusDecoderRobustnessTest {
    /** A stream to mutate. */
    record Stream(byte[] head, List<byte[]> packets) {
    }

    static AudioTrack track(byte[] head) {
        return AudioTrack.builder().id(1).codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48000)).codecConfig(head)
                .sampleRate(48000)
                .build();
    }

    static List<Stream> sources() {
        var sources = new ArrayList<Stream>();
        var directory = OpusVectors.directory();
        for (var vector : new int[]{1, 2, 5, 8, 10, 12}) {
            var packets = OpusVectors.readBit(directory.resolve(String.format("testvector%02d.bit", vector)));
            var list = new ArrayList<byte[]>();
            for (var i = 0; i < Math.min(packets.size(), 150); i++) {
                list.add(packets.get(i).data());
            }
            sources.add(new Stream(OpusHead.family0(2, 0, 48000).toBytes(), list));
        }
        if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
            var file = Fixtures.ffmpeg("opus", OpusDecoderDifferentialTest.source(6, 1, "tones"),
                    List.of("-ac", "6", "-c:a", "libopus", "-b:a", "192k", "-mapping_family", "1"));
            var stream = OggOpusIO.read(Fixtures.read(file));
            sources.add(new Stream(stream.head(), stream.packets()));
        }
        return sources;
    }

    static byte[] mutate(byte[] packet, Random random) {
        var copy = packet.clone();
        switch (random.nextInt(7)) {
            case 0 -> {
                for (var flips = 1 + random.nextInt(8); flips > 0 && copy.length > 0; flips--) {
                    copy[random.nextInt(copy.length)] ^= (byte) (1 << random.nextInt(8));
                }
            }
            case 1 -> copy = java.util.Arrays.copyOf(copy, copy.length == 0 ? 0 : random.nextInt(copy.length));
            case 2 -> {
                if (copy.length > 0) {
                    copy[0] = (byte) random.nextInt(256);
                }
            }
            case 3 -> {
                if (copy.length > 1) {
                    copy[0] |= 3;
                    copy[1] = (byte) random.nextInt(256);
                }
            }
            case 4 -> {
                if (copy.length > 2) {
                    copy[0] |= 3;
                    copy[1] = (byte) (0x40 | (copy[1] & 0xBF));
                    copy[2] = (byte) 255;
                }
            }
            case 5 -> copy = new byte[0];
            default -> {
                copy = java.util.Arrays.copyOf(copy, copy.length + 1 + random.nextInt(40));
                for (var i = packet.length; i < copy.length; i++) {
                    copy[i] = (byte) random.nextInt(256);
                }
            }
        }
        return copy;
    }

    @ParameterizedTest(name = "seed {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
    @DisplayName("decodes or rejects every mutated packet with a MediaException, quickly")
    void mutations(int seed) {
        var random = new Random(seed);
        var sources = sources();
        assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
            for (var variant = 0; variant < 30; variant++) {
                var source = sources.get(random.nextInt(sources.size()));
                var rate = new int[]{8000, 12000, 16000, 24000, 48000}[random.nextInt(5)];
                try (var decoder = new OpusDecoder(track(source.head()), OpusDecoderOptions.defaults().toBuilder()
                        .sampleRate(rate).build())) {
                    for (var packet : source.packets()) {
                        var data = random.nextInt(4) == 0 ? mutate(packet, random) : packet;
                        try {
                            decoder.decode(new Packet(1, data, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, true), buffer -> {
                                for (var c = 0; c < buffer.channels(); c++) {
                                    var plane = buffer.plane(c);
                                    for (var i = 0; i < buffer.frames(); i++) {
                                        assertTrue(Float.isFinite(plane[i]), "non-finite sample");
                                    }
                                }
                            });
                        } catch (MediaException expected) {
                            assertNotNull(expected.getMessage());
                        }
                    }
                }
            }
        });
    }

    @Test
    @DisplayName("conceals an empty packet for the previous packet's duration, as libopus does")
    void emptyPacketRunsPlc() {
        var packets = OpusVectors.readBit(OpusVectors.directory().resolve("testvector02.bit"));
        try (var decoder = new OpusDecoder(track(OpusHead.family0(1, 0, 48000).toBytes()))) {
            var lengths = new ArrayList<Integer>();
            for (var i = 0; i < 20; i++) {
                decoder.decode(new Packet(1, packets.get(i).data(), 0, 0, 0, true), buffer -> lengths.add(buffer.frames()));
            }
            var previous = lengths.getLast();
            var concealed = new int[1];
            decoder.decode(new Packet(1, new byte[0], 0, 0, 0, true), buffer -> concealed[0] = buffer.frames());
            assertEquals(previous, concealed[0]);
            var explicit = new int[1];
            decoder.decode(new Packet(1, new byte[0], 0, 0, 480, true), buffer -> explicit[0] = buffer.frames());
            assertEquals(480, explicit[0]);
        }
    }

    @Test
    @DisplayName("rejects a corrupt packet with MediaException.Malformed rather than a runtime exception")
    void malformed() {
        try (var decoder = new OpusDecoder(track(OpusHead.family0(2, 0, 48000).toBytes()))) {
            var error = assertThrows(MediaException.Malformed.class,
                    () -> decoder.decode(new Packet(1, new byte[]{0x0B, 0}, 0, 0, 0, true), buffer -> {
                    }));
            assertTrue(error.getMessage().contains("Opus"));
        }
    }

    @Test
    @DisplayName("rethrows the output consumer's own exception unchanged")
    void consumerFailure() {
        try (var decoder = new OpusDecoder(track(OpusHead.family0(2, 0, 48000).toBytes()))) {
            var thrown = new IllegalArgumentException("consumer");
            var error = assertThrows(IllegalArgumentException.class,
                    () -> decoder.decode(new Packet(1, new byte[0], 0, 0, 960, true), buffer -> {
                        throw thrown;
                    }));
            assertSame(thrown, error);
        }
    }
}
