package com.github.auties00.smalt.audio.vorbis;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic mutation tests: corrupted header configurations and corrupted audio packets
 * (random byte flips, truncation, garbage extension, huge length fields) must either decode or
 * raise a {@link MediaException}, never another exception, and must finish in bounded time.
 */
class VorbisRobustnessTest {
    private static final AudioDecoderOptions OPTIONS = AudioDecoderOptions.builder().limits(MediaLimits.builder()
            .maxDimension(16_384).maxPixels(1 << 20).maxFrames(1 << 20).maxChannels(64).maxBytes(64L << 20).build())
                    .build();

    /** A valid stream: its laced configuration and some audio packets. */
    record Sample(String name, byte[] config, List<byte[]> packets) {
    }

    private static List<Sample> samples() throws IOException {
        var samples = new ArrayList<Sample>();
        var random = new Random(77);
        for (var channels : new int[]{1, 2}) {
            var stream = VorbisStreamBuilder.minimal(channels);
            var packets = new ArrayList<byte[]>();
            for (var i = 0; i < 8; i++) {
                packets.add(VorbisReferenceTest.randomPacket(random, stream, i));
            }
            samples.add(new Sample("minimal-" + channels, stream.config(), packets));
        }
        for (var seed = 0; seed < 4; seed++) {
            var stream = VorbisReferenceTest.randomStream(new Random(seed * 31L + 5));
            var packets = new ArrayList<byte[]>();
            for (var i = 0; i < 8; i++) {
                packets.add(VorbisReferenceTest.randomPacket(random, stream, i));
            }
            samples.add(new Sample("random-" + seed, stream.config(), packets));
        }
        String[][] vectors = {
                {"48k-mono", "f51459d9bdd04ca3ec6f6732b8a01efcdc83e5c6fc79c7d5347527bfd4948e9e"},
                {"lsp-test", "ad1b07b68576ae2c85178475ef3607e1a96c09e96296cae83ee7a9726f8311eb"},
                {"test-short", "183510552403021fb90ce43796fcc88e16c8bb4ae5d0d72a316b8e51afc395fa"},
                {"rc3-test", "edd984e84c7c2a59af7801f3e8d2db11a6619134fd8d0274d1289e70bf60cde7"}};
        for (var vector : vectors) {
            try {
                var file = Conformance.fetch("vorbis-" + vector[0] + ".ogg",
                        "https://people.xiph.org/~xiphmont/test-vectors/vorbis/" + vector[0] + ".ogg", vector[1]);
                var link = OggVorbis.read(Files.readAllBytes(file)).getFirst();
                var audio = link.audio();
                samples.add(new Sample(vector[0], link.codecConfig(), new ArrayList<>(audio.subList(0, Math.min(12, audio.size())))));
            } catch (org.opentest4j.TestAbortedException offline) {
                // the vector is unavailable offline; the synthetic samples still run
            }
        }
        return samples;
    }

    private static void decodeAll(byte[] config, List<byte[]> packets) {
        VorbisDecoder decoder;
        try {
            decoder = new VorbisDecoder(VorbisStreamBuilder.track(config), OPTIONS);
        } catch (MediaException expected) {
            assertValidated(expected);
            return;
        }
        for (var data : packets) {
            try {
                decoder.decode(new Packet(0, data, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, true), buffer -> {
                    for (var c = 0; c < buffer.channels(); c++) {
                        assertTrue(buffer.frames() <= buffer.plane(c).length);
                    }
                });
            } catch (MediaException expected) {
                // a rejected packet leaves the decoder usable for the next one
                assertValidated(expected);
            }
        }
    }

    /** Fails when the rejection came from the last-resort translation of an unexpected exception. */
    private static void assertValidated(MediaException exception) {
        if (exception.getCause() != null) {
            fail("unvalidated input reached " + exception.getCause(), exception.getCause());
        }
    }

    private static byte[] mutate(Random random, byte[] original) {
        var data = original.clone();
        switch (random.nextInt(6)) {
            case 0 -> {
                for (int i = 0, n = 1 + random.nextInt(4); i < n && data.length > 0; i++) {
                    data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
                }
            }
            case 1 -> {
                for (int i = 0, n = 1 + random.nextInt(8); i < n && data.length > 0; i++) {
                    data[random.nextInt(data.length)] = (byte) random.nextInt(256);
                }
            }
            case 2 -> data = Arrays.copyOf(data, data.length == 0 ? 0 : random.nextInt(data.length));
            case 3 -> {
                // a 24-bit length or count field forced to a huge value
                if (data.length > 3) {
                    var at = random.nextInt(data.length - 3);
                    data[at] = data[at + 1] = data[at + 2] = (byte) 0xFF;
                }
            }
            case 4 -> {
                var extended = Arrays.copyOf(data, data.length + 1 + random.nextInt(64));
                for (var i = data.length; i < extended.length; i++) {
                    extended[i] = (byte) random.nextInt(256);
                }
                data = extended;
            }
            default -> {
                if (data.length > 1) {
                    var from = random.nextInt(data.length);
                    var to = random.nextInt(data.length);
                    var length = Math.min(1 + random.nextInt(16), Math.min(data.length - from, data.length - to));
                    System.arraycopy(original, from, data, to, length);
                }
            }
        }
        return data;
    }

    @Test
    @DisplayName("survives 600 corrupted header configurations")
    void headers() throws IOException {
        var samples = samples();
        var random = new Random(1234);
        assertTimeoutPreemptively(Duration.ofMinutes(3), () -> {
            for (var i = 0; i < 600; i++) {
                var sample = samples.get(i % samples.size());
                var config = mutate(random, sample.config());
                try {
                    decodeAll(config, sample.packets());
                } catch (RuntimeException | Error failure) {
                    fail("mutation " + i + " of " + sample.name() + " escaped with " + failure, failure);
                }
            }
        });
    }

    @Test
    @DisplayName("survives 600 corrupted packet sequences")
    void packets() throws IOException {
        var samples = samples();
        var random = new Random(4321);
        assertTimeoutPreemptively(Duration.ofMinutes(3), () -> {
            for (var i = 0; i < 600; i++) {
                var sample = samples.get(i % samples.size());
                var packets = new ArrayList<byte[]>();
                for (var packet : sample.packets()) {
                    packets.add(random.nextInt(3) == 0 ? mutate(random, packet) : packet);
                }
                if (random.nextInt(4) == 0) {
                    var garbage = new byte[random.nextInt(300)];
                    random.nextBytes(garbage);
                    packets.add(random.nextInt(packets.size() + 1), garbage);
                }
                try {
                    decodeAll(sample.config(), packets);
                } catch (RuntimeException | Error failure) {
                    fail("mutation " + i + " of " + sample.name() + " escaped with " + failure, failure);
                }
            }
        });
    }

    @Test
    @DisplayName("decodes fully random packets from a real stream without escaping exceptions")
    void randomPackets() throws IOException {
        var samples = samples();
        var random = new Random(99);
        assertTimeoutPreemptively(Duration.ofMinutes(2), () -> {
            for (var sample : samples) {
                var packets = new ArrayList<byte[]>();
                for (var i = 0; i < 60; i++) {
                    var packet = new byte[random.nextInt(2000)];
                    random.nextBytes(packet);
                    if (packet.length > 0) {
                        packet[0] &= (byte) 0xFE;
                    }
                    packets.add(packet);
                }
                decodeAll(sample.config(), packets);
            }
        });
    }
}
