package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Feeds the decoder deterministic mutations of valid streams and configurations: every decode
 * must succeed or raise a {@link MediaException}, and finish promptly.
 */
class AacDecoderRobustnessTest {
    static AudioTrack track(byte[] config) {
        return AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(48000)).codecConfig(config)
                .build();
    }

    /** Decodes every frame, requiring success or a MediaException per frame; returns the failures. */
    static int decodeTolerantly(AacDecoder decoder, List<byte[]> frames) {
        var failures = 0;
        for (var frame : frames) {
            try {
                decoder.decode(new Packet(1, frame, 0, 0, 0, true), buffer -> {
                    for (var c = 0; c < buffer.channels(); c++) {
                        for (var i = 0; i < buffer.frames(); i++) {
                            var value = buffer.plane(c)[i];
                            if (Float.isNaN(value)) {
                                fail("NaN output");
                            }
                        }
                    }
                });
            } catch (MediaException expected) {
                if (expected.getCause() != null) {
                    throw new AssertionError("the safety net caught " + expected.getCause(), expected.getCause());
                }
                failures++;
            } catch (RuntimeException unexpected) {
                throw new AssertionError("decoder leaked " + unexpected, unexpected);
            }
        }
        return failures;
    }

    static List<AacTestSupport.Stream> corpus() {
        var streams = new ArrayList<AacTestSupport.Stream>();
        streams.add(AacTestSupport.parseAdts(Fixtures.read(AacDecoderDifferentialTest.fixture("tones", 1, 48000, 1, "aac", "-b:a", "16000"))));
        streams.add(AacTestSupport.parseAdts(Fixtures.read(AacDecoderDifferentialTest.fixture("clicks", 2, 44100, 1, "aac", "-b:a", "128000"))));
        streams.add(AacTestSupport.parseAdts(Fixtures.read(AacDecoderDifferentialTest.fixture("noise", 2, 48000, 1, "aac", "-b:a", "32000", "-aac_is", "1"))));
        streams.add(AacTestSupport.parseAdts(Fixtures.read(AacDecoderDifferentialTest.fixture("tones", 6, 48000, 1, "aac", "-b:a", "256000", "-aac_pce", "1"))));
        return streams;
    }

    @Nested
    @DisplayName("mutated frames")
    class Frames {
        @ParameterizedTest(name = "stream {0}")
        @ValueSource(ints = {0, 1, 2, 3})
        @DisplayName("decode or raise MediaException for bit flips, truncations and garbage")
        void mutations(int index) {
            var stream = corpus().get(index);
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                var random = new Random(index * 7919L);
                for (var round = 0; round < 300; round++) {
                    var frames = new ArrayList<byte[]>();
                    for (var frame : stream.frames().subList(0, Math.min(12, stream.frames().size()))) {
                        var mutated = frame.clone();
                        switch (random.nextInt(5)) {
                            case 0 -> {
                                for (var flips = 1 + random.nextInt(8); flips > 0; flips--) {
                                    var position = random.nextInt(mutated.length);
                                    mutated[position] ^= (byte) (1 << random.nextInt(8));
                                }
                            }
                            case 1 -> mutated = Arrays.copyOf(mutated, random.nextInt(mutated.length + 1));
                            case 2 -> random.nextBytes(mutated);
                            case 3 -> {
                                var position = random.nextInt(mutated.length);
                                mutated[position] = (byte) random.nextInt(256);
                            }
                            default -> {
                            }
                        }
                        frames.add(mutated);
                    }
                    try {
                        decodeTolerantly(new AacDecoder(stream.track()), frames);
                    } catch (MediaException ignored) {
                        // a mutated configuration may itself be rejected
                    }
                }
            });
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"am02_48", "am07_48", "ap02_48", "as03_48", "as17_48", "er_ap10_48_ep0"})
        @DisplayName("decode or raise MediaException for mutations of AAC Main, LTP, SSR and ER LTP streams")
        void objectTypes(String name) {
            var stream = AacTestSupport.parseMp4(Fixtures.read(AacObjectTypesConformanceTest.fetch(name + ".mp4")));
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                var random = new Random(name.hashCode());
                for (var round = 0; round < 150; round++) {
                    var frames = new ArrayList<byte[]>();
                    for (var frame : stream.frames().subList(20, 32)) {
                        var mutated = frame.clone();
                        switch (random.nextInt(4)) {
                            case 0 -> {
                                for (var flips = 1 + random.nextInt(8); flips > 0; flips--) {
                                    var position = random.nextInt(mutated.length);
                                    mutated[position] ^= (byte) (1 << random.nextInt(8));
                                }
                            }
                            case 1 -> mutated = Arrays.copyOf(mutated, random.nextInt(mutated.length + 1));
                            case 2 -> random.nextBytes(mutated);
                            default -> {
                            }
                        }
                        frames.add(mutated);
                    }
                    decodeTolerantly(new AacDecoder(stream.track()), frames);
                }
            });
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"er_bs01_48_ep0", "er_bs09_48_ep0", "er_bs13_sbr_1_48"})
        @DisplayName("decode or raise MediaException for mutations of ER BSAC streams")
        void bsac(String name) {
            var stream = AacTestSupport.parseMp4(Fixtures.read(Conformance.fetchListed("mpeg4audio-" + name + ".mp4")));
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                var random = new Random(name.hashCode());
                for (var round = 0; round < 150; round++) {
                    var frames = new ArrayList<byte[]>();
                    for (var frame : stream.frames().subList(20, 32)) {
                        var mutated = frame.clone();
                        switch (random.nextInt(5)) {
                            case 0 -> {
                                for (var flips = 1 + random.nextInt(8); flips > 0; flips--) {
                                    var position = random.nextInt(mutated.length);
                                    mutated[position] ^= (byte) (1 << random.nextInt(8));
                                }
                            }
                            case 1 -> mutated = Arrays.copyOf(mutated, random.nextInt(mutated.length + 1));
                            case 2 -> random.nextBytes(mutated);
                            case 3 -> mutated = Arrays.copyOf(mutated, mutated.length + 1 + random.nextInt(64));
                            default -> {
                            }
                        }
                        frames.add(mutated);
                    }
                    decodeTolerantly(new AacDecoder(stream.track()), frames);
                }
            });
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"er_al26_48_ep0", "er_al23_48_ep0", "er_ap14_48_ep0", "er_ap27_48_ep0", "er_ad1111_48_ep0",
                "er_ad2160_48_ep0", "er_ad2170_48_ep0", "er_eld2150np_48_ep0", "er_eld2100np_32_ep0", "er_eld_sbr_e_2_48",
                "er_eld_sbr_gh_1_44", "er_eld_sbr_i_2_32"})
        @DisplayName("decode or raise MediaException for mutations of ER LC, ER LTP, LD and ELD streams")
        void errorResilient(String name) {
            // virtual codebooks, RVLC and codeword reordering read lengths and offsets from the stream, and the
            // low delay SBR payload fills the rest of the block, so mutations reach every bounds check
            var stream = AacTestSupport.parseMp4(Fixtures.read(ErrorResilientVectors.fetch(name + ".mp4")));
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                var random = new Random(name.hashCode());
                for (var round = 0; round < 150; round++) {
                    var frames = new ArrayList<byte[]>();
                    for (var frame : stream.frames().subList(40, 52)) {
                        var mutated = frame.clone();
                        switch (random.nextInt(5)) {
                            case 0 -> {
                                for (var flips = 1 + random.nextInt(8); flips > 0; flips--) {
                                    var position = random.nextInt(mutated.length);
                                    mutated[position] ^= (byte) (1 << random.nextInt(8));
                                }
                            }
                            case 1 -> mutated = Arrays.copyOf(mutated, random.nextInt(mutated.length + 1));
                            case 2 -> random.nextBytes(mutated);
                            case 3 -> mutated = Arrays.copyOf(mutated, mutated.length + 1 + random.nextInt(16));
                            default -> {
                            }
                        }
                        frames.add(mutated);
                    }
                    decodeTolerantly(new AacDecoder(stream.track()), frames);
                }
            });
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"er_al21_48_ep1", "er_al23_48_ep1", "er_ad2160np_48_ep1"})
        @DisplayName("decode or raise MediaException for mutated, dropped and reordered category instances")
        void separateCategories(String name) {
            var tracks = AacTestSupport.parseMp4Tracks(Fixtures.read(ErrorResilientVectors.fetch(name + ".mp4")));
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                var random = new Random(name.hashCode());
                var decoder = new AacDecoder(tracks.getFirst().track());
                for (var round = 0; round < 1500; round++) {
                    var block = 40 + random.nextInt(20);
                    var instances = new ArrayList<Packet>();
                    for (var track : tracks) {
                        var data = track.frames().get(block).clone();
                        switch (random.nextInt(6)) {
                            case 0 -> {
                                if (data.length > 0) {
                                    data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
                                }
                            }
                            case 1 -> data = Arrays.copyOf(data, random.nextInt(data.length + 1));
                            case 2 -> random.nextBytes(data);
                            case 3 -> {
                                continue;
                            }
                            default -> {
                            }
                        }
                        instances.add(new Packet(1, data, 0, 0, 0, true));
                    }
                    if (random.nextBoolean() && instances.size() > 1) {
                        java.util.Collections.swap(instances, random.nextInt(instances.size()),
                                random.nextInt(instances.size()));
                    }
                    try {
                        decoder.decode(instances, buffer -> {
                            for (var c = 0; c < buffer.channels(); c++) {
                                for (var i = 0; i < buffer.frames(); i++) {
                                    if (Float.isNaN(buffer.plane(c)[i])) {
                                        fail("NaN output");
                                    }
                                }
                            }
                        });
                    } catch (MediaException expected) {
                        if (expected.getCause() != null) {
                            throw new AssertionError("the safety net caught " + expected.getCause(), expected.getCause());
                        }
                    }
                }
            });
        }

        @Test
        @DisplayName("rejects an empty packet and decodes an end-only block to silence")
        void degenerate() {
            var stream = corpus().getFirst();
            var decoder = new AacDecoder(stream.track());
            assertThrows(MediaException.Malformed.class,
                    () -> decoder.decode(new Packet(1, new byte[0], 0, 0, 0, true), buffer -> fail()));
            var blocks = new ArrayList<float[][]>();
            decoder.decode(new Packet(1, new byte[]{(byte) 0xE0}, 0, 0, 0, true), buffer -> blocks.add(AacTestSupport.copy(buffer)));
            assertEquals(1, blocks.size());
            for (var value : blocks.getFirst()[0]) {
                assertEquals(0, value);
            }
        }

        @Test
        @DisplayName("reports a truncated block as malformed")
        void truncated() {
            var stream = corpus().get(1);
            var frame = stream.frames().get(5);
            var decoder = new AacDecoder(stream.track());
            assertThrows(MediaException.Malformed.class, () -> decoder.decode(
                    new Packet(1, Arrays.copyOf(frame, frame.length / 2), 0, 0, 0, true), buffer -> fail()));
        }
    }

    @Nested
    @DisplayName("mutated configurations")
    class Configurations {
        @Test
        @DisplayName("construct or raise MediaException for any bytes")
        void randomConfigurations() {
            var valid = List.of("1190", "1210", "2b118800", "131056e598", "140006040000000056e5a8", "1300058c01000108800056e598",
                    "11b0", "1188", "f8ee2000", "0000", "0988", "1988", "2190", "998900", "999100");
            var random = new Random(42);
            var frames = corpus().getFirst().frames().subList(0, 4);
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                for (var round = 0; round < 3000; round++) {
                    byte[] config;
                    if (round % 3 == 0) {
                        config = new byte[1 + random.nextInt(16)];
                        random.nextBytes(config);
                    } else {
                        config = HexFormat.of().parseHex(valid.get(random.nextInt(valid.size())));
                        for (var flips = 1 + random.nextInt(4); flips > 0; flips--) {
                            config[random.nextInt(config.length)] ^= (byte) (1 << random.nextInt(8));
                        }
                        if (random.nextBoolean()) {
                            config = Arrays.copyOf(config, 1 + random.nextInt(config.length));
                        }
                    }
                    try {
                        var decoder = new AacDecoder(track(config));
                        assertTrue(decoder.sampleRate() > 0);
                        decodeTolerantly(decoder, frames);
                    } catch (MediaException expected) {
                        if (expected.getCause() != null) {
                            throw new AssertionError("the safety net caught " + expected.getCause(), expected.getCause());
                        }
                    }
                }
            });
        }

        @Test
        @DisplayName("rejects an empty configuration and a missing one without a declared rate")
        void missing() {
            assertThrows(MediaException.Malformed.class, () -> new AacDecoder(track(new byte[0])));
            var bare = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(48000)).build();
            assertThrows(MediaException.Malformed.class, () -> new AacDecoder(bare));
        }

        @Test
        @DisplayName("assumes Low Complexity at the declared rate and layout without a configuration")
        void implicit() {
            var stream = corpus().get(1);
            var bare = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(44100)).sampleRate(44100)
                    .layout(ChannelLayout.STEREO)
                    .build();
            var decoded = AacTestSupport.decodeAll(new AacDecoder(bare), stream.frames());
            var expected = AacTestSupport.decodeAll(stream);
            assertEquals(Double.POSITIVE_INFINITY, AacTestSupport.snr(expected.planes(), decoded.planes()));
        }

        @Test
        @DisplayName("enforces the channel limit on program configurations")
        void channelLimit() {
            var stream = corpus().get(3);
            var limits = MediaLimits.builder().maxDimension(16384).maxPixels(1L << 27).maxFrames(1000).maxChannels(2)
                    .maxBytes(1L << 30).build();
            var decoder = new AacDecoder(stream.track(), AacDecoderOptions.builder().limits(limits).build());
            assertThrows(MediaException.LimitExceeded.class,
                    () -> decoder.decode(new Packet(1, stream.frames().getFirst(), 0, 0, 0, true), buffer -> fail()));
        }

        @Test
        @DisplayName("rejects a non-AAC track as a programming error")
        void wrongCodec() {
            var opus = AudioTrack.builder().id(1).codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                    .build();
            assertThrows(IllegalArgumentException.class, () -> new AacDecoder(opus));
        }
    }

    @Test
    @DisplayName("decodes an encoder round trip of every block size without leaks")
    void encoderOutput() {
        var input = TestSignals.generate(TestSignals.Kind.TRANSIENTS, 48000, 2, 1);
        var encoder = new AacEncoder(AacEncoderOptions.of(48000, ChannelLayout.STEREO, 96000));
        var frames = new ArrayList<byte[]>();
        encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 48000, input, input[0].length), p -> frames.add(p.data()));
        encoder.flush(p -> frames.add(p.data()));
        var decoder = new AacDecoder(track(encoder.codecConfig().orElseThrow()));
        assertEquals(0, decodeTolerantly(decoder, frames));
    }
}
