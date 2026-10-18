package com.github.auties00.smalt.audio.amr;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the public AMR codecs: bit-exactness against ffmpeg's builds of the ported reference
 * code (opencore-amr in both directions, vo-amrwbenc's output decoded; Smalt's AMR-WB encoder
 * follows TS 26.173 where vo-amrwbenc departs from it, so its frames are compared by quality),
 * every mode with and without
 * discontinuous transmission, the round-trip quality per mode, bad-frame handling, hostile
 * packets, and the options and registry mapping.
 */
class AmrCodecTest {
    static Stream<Arguments> modes() {
        var arguments = new ArrayList<Arguments>();
        for (var mode : AmrMode.values()) {
            arguments.add(Arguments.of(mode, false));
            arguments.add(Arguments.of(mode, true));
        }
        return arguments.stream();
    }

    static Stream<Arguments> narrowbandModes() {
        return modes().filter(arguments -> !((AmrMode) arguments.get()[0]).isWideband());
    }

    static Stream<Arguments> widebandModes() {
        return modes().filter(arguments -> ((AmrMode) arguments.get()[0]).isWideband());
    }

    @Nested
    @DisplayName("against the C reference")
    class Differential {
        @TempDir
        Path directory;

        @ParameterizedTest(name = "{0} dtx={1}")
        @MethodSource("com.github.auties00.smalt.audio.amr.AmrCodecTest#narrowbandModes")
        @DisplayName("encodes AMR-NB to the same frames as libopencore_amrnb")
        void encoder(AmrMode mode, boolean dtx) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var pcm = AmrTestSupport.signal(mode.sampleRate());
            var expected = AmrTestSupport.frames(AmrTestSupport.ffmpegEncode(mode, dtx, pcm, directory));
            var actual = AmrTestSupport.encode(AmrEncoderOptions.of(mode).toBuilder().dtx(dtx).build(), pcm);
            var frames = pcm.length / AmrTestSupport.frameSamples(mode.codec());
            assertTrue(expected.size() >= frames && actual.size() >= frames);
            for (var frame = 0; frame < frames; frame++) {
                assertArrayEquals(expected.get(frame).data(), actual.get(frame).data(), "frame " + frame);
            }
        }

        @ParameterizedTest(name = "{0} dtx={1}")
        @MethodSource("com.github.auties00.smalt.audio.amr.AmrCodecTest#widebandModes")
        @DisplayName("encodes AMR-WB at least as well as libvo_amrwbenc, whose arithmetic departs from TS 26.173")
        void widebandEncoder(AmrMode mode, boolean dtx) {
            // the TS 26.174 sequences (AmrConformanceTest) are the normative check; libvo_amrwbenc
            // wraps where TS 26.173 saturates and lacks the 6.60/8.85 pitch clipping and the
            // impulse response scaling of the codebook search, so frames may differ
            Oracle.assume(Oracle.Tool.FFMPEG);
            var pcm = AmrTestSupport.signal(mode.sampleRate());
            var reference = AmrTestSupport.decode(mode.codec(), AmrTestSupport.frames(AmrTestSupport.ffmpegEncode(mode, dtx, pcm, directory)));
            var actual = AmrTestSupport.decode(mode.codec(), AmrTestSupport.encode(AmrEncoderOptions.of(mode).toBuilder().dtx(dtx).build(), pcm));
            var delay = new AmrEncoder(AmrEncoderOptions.of(mode)).delay();
            var rate = mode.sampleRate();
            var referenceSnr = AmrTestSupport.spectralSnr(pcm, reference, delay, rate / 4, rate);
            var actualSnr = AmrTestSupport.spectralSnr(pcm, actual, delay, rate / 4, rate);
            assertTrue(actualSnr >= referenceSnr - 0.5, mode + ": " + actualSnr + " dB against libvo_amrwbenc's " + referenceSnr + " dB");
        }

        @ParameterizedTest(name = "{0} dtx={1}")
        @MethodSource("com.github.auties00.smalt.audio.amr.AmrCodecTest#modes")
        @DisplayName("decodes the reference encoder's output to the reference decoder's samples")
        void decoder(AmrMode mode, boolean dtx) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var pcm = AmrTestSupport.signal(mode.sampleRate());
            var file = AmrTestSupport.ffmpegEncode(mode, dtx, pcm, directory);
            var expected = AmrTestSupport.ffmpegDecode(mode.codec(), file, directory);
            var actual = AmrTestSupport.decode(mode.codec(), AmrTestSupport.frames(file));
            assertArrayEquals(expected, actual);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"AMR_NB", "AMR_WB"})
        @DisplayName("decodes a stream whose mode changes on every frame")
        void modeSwitching(Codec codec) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var pcm = AmrTestSupport.signal(AmrTestSupport.rate(codec));
            var modes = Stream.of(AmrMode.values()).filter(mode -> mode.codec() == codec).toList();
            var packets = new ArrayList<Packet>();
            try (var encoder = new AmrEncoder(AmrEncoderOptions.of(modes.getFirst()))) {
                var samples = AmrTestSupport.frameSamples(codec);
                var frame = new byte[61];
                for (var index = 0; index + samples <= pcm.length; index += samples) {
                    encoder.setMode(modes.get((index / samples) % modes.size()));
                    var size = encoder.encodeFrame(pcm, index, frame, 0);
                    packets.add(new Packet(0, java.util.Arrays.copyOf(frame, size), index, index, samples, true));
                }
            }
            var file = AmrTestSupport.storage(codec, packets);
            assertArrayEquals(AmrTestSupport.ffmpegDecode(codec, file, directory), AmrTestSupport.decode(codec, packets));
        }
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                "NB_4_75, 17", "NB_5_15, 18", "NB_5_90, 21", "NB_6_70, 22", "NB_7_40, 21.5", "NB_7_95, 22", "NB_10_2, 23.5",
                "NB_12_2, 23.5", "WB_6_60, 16.5", "WB_8_85, 20", "WB_12_65, 26", "WB_14_25, 26", "WB_15_85, 26",
                "WB_18_25, 26.5", "WB_19_85, 27", "WB_23_05, 27", "WB_23_85, 27.5"})
        @DisplayName("keeps the spectrum of voiced sound above a per-mode spectral SNR")
        void quality(AmrMode mode, double minimum) {
            var pcm = AmrTestSupport.signal(mode.sampleRate());
            var packets = AmrTestSupport.encode(AmrEncoderOptions.of(mode), pcm);
            var decoded = AmrTestSupport.decode(mode.codec(), packets);
            var delay = new AmrEncoder(AmrEncoderOptions.of(mode)).delay();
            var rate = mode.sampleRate();
            var snr = AmrTestSupport.spectralSnr(pcm, decoded, delay, rate / 4, rate);
            assertTrue(snr >= minimum, mode + " SNR " + snr);
            assertEquals(pcm.length / AmrTestSupport.frameSamples(mode.codec()) + 1, packets.size(),
                    "frames, the flush covering the delay included");
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"AMR_NB", "AMR_WB"})
        @DisplayName("aligns the decoded output with the input after the reported delay")
        void delay(Codec codec) {
            var mode = AmrMode.highest(codec);
            var rate = mode.sampleRate();
            var pcm = new short[rate * 2];
            var random = new java.util.Random(1);
            for (var i = rate / 2; i < rate * 3 / 2; i++) {
                pcm[i] = (short) Math.round(random.nextGaussian() * 2500);
            }
            var decoded = AmrTestSupport.decode(codec, AmrTestSupport.encode(AmrEncoderOptions.of(mode), pcm));
            var reported = new AmrEncoder(AmrEncoderOptions.of(mode)).delay();
            var best = 0;
            var bestCorrelation = Double.NEGATIVE_INFINITY;
            for (var lag = 0; lag < 200; lag++) {
                double correlation = 0;
                for (var i = rate / 2; i < rate * 3 / 2; i++) {
                    correlation += (double) pcm[i] * decoded[i + lag];
                }
                if (correlation > bestCorrelation) {
                    bestCorrelation = correlation;
                    best = lag;
                }
            }
            assertEquals(reported, best, 2);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"AMR_NB", "AMR_WB"})
        @DisplayName("replaces background noise with comfort noise frames under DTX")
        void dtx(Codec codec) {
            var mode = AmrMode.highest(codec);
            var pcm = AmrTestSupport.signal(mode.sampleRate());
            var packets = AmrTestSupport.encode(AmrEncoderOptions.of(mode).toBuilder().dtx(true).build(), pcm);
            var sid = codec == Codec.AMR_WB ? 9 : 8;
            var types = packets.stream().map(packet -> (packet.data()[0] >> 3) & 0x0F).toList();
            assertTrue(types.contains(sid), "comfort noise frames");
            assertTrue(types.contains(15), "no-data frames");
            assertTrue(types.subList(0, 25).contains(mode.frameType()), "speech frames");
            for (var packet : packets) {
                var type = (packet.data()[0] >> 3) & 0x0F;
                assertEquals(type == 15 ? 1 : type == sid ? 6 : mode.frameBytes(), packet.size());
                assertEquals(0x04, packet.data()[0] & 0x04, "quality bit");
            }
            var decoded = AmrTestSupport.decode(codec, packets);
            assertEquals(packets.size() * AmrTestSupport.frameSamples(codec), decoded.length);
        }
    }

    @Nested
    @DisplayName("frame handling")
    class Frames {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"AMR_NB", "AMR_WB"})
        @DisplayName("decodes several frames packed in one packet like separate packets")
        void packed(Codec codec) {
            var packets = AmrTestSupport.encode(AmrEncoderOptions.of(AmrMode.highest(codec)), AmrTestSupport.signal(AmrTestSupport.rate(codec)));
            var separate = AmrTestSupport.decode(codec, packets);
            var joined = new ArrayList<Packet>();
            for (var index = 0; index < packets.size(); index += 5) {
                var group = packets.subList(index, Math.min(index + 5, packets.size()));
                var data = new java.io.ByteArrayOutputStream();
                group.forEach(packet -> data.writeBytes(packet.data()));
                joined.add(new Packet(0, data.toByteArray(), index, index, group.size(), true));
            }
            assertArrayEquals(separate, AmrTestSupport.decode(codec, joined));
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"AMR_NB", "AMR_WB"})
        @DisplayName("conceals damaged, truncated, corrupt-flagged and missing frames without breaking the timeline")
        void concealment(Codec codec) {
            var packets = new ArrayList<>(AmrTestSupport.encode(AmrEncoderOptions.of(AmrMode.highest(codec)),
                    AmrTestSupport.signal(AmrTestSupport.rate(codec))));
            var clean = AmrTestSupport.decode(codec, packets);
            var damaged = packets.get(10).data().clone();
            damaged[0] &= (byte) ~0x04;
            packets.set(10, packets.get(10).withData(damaged));
            packets.set(11, packets.get(11).withData(java.util.Arrays.copyOf(packets.get(11).data(), 7)));
            packets.set(12, packets.get(12).withFlags(Set.of(PacketFlag.KEY, PacketFlag.CORRUPT)));
            packets.set(13, packets.get(13).withData(new byte[0]));
            packets.set(14, packets.get(14).withData(new byte[]{0x7C}));
            var concealed = AmrTestSupport.decode(codec, packets);
            assertEquals(clean.length, concealed.length);
            var samples = AmrTestSupport.frameSamples(codec);
            assertArrayEquals(java.util.Arrays.copyOf(clean, 10 * samples), java.util.Arrays.copyOf(concealed, 10 * samples));
            var energy = 0.0;
            for (var i = 10 * samples; i < 15 * samples; i++) {
                energy += (double) concealed[i] * concealed[i];
            }
            assertTrue(energy > 0, "concealment extrapolates the preceding speech");
        }

        @Test
        @DisplayName("treats the comfort noise frames of the other codecs and reserved types as no data")
        void foreignTypes() {
            var packets = new ArrayList<>(AmrTestSupport.encode(AmrEncoderOptions.narrowband(), AmrTestSupport.signal(8000)));
            for (var type = 9; type <= 14; type++) {
                var size = type == 9 ? 6 : type <= 11 ? 5 : 0;
                var data = new byte[1 + size];
                data[0] = (byte) (type << 3 | 0x04);
                packets.set(20 + type, packets.get(20 + type).withData(data));
            }
            assertEquals(packets.size() * 160, AmrTestSupport.decode(Codec.AMR_NB, packets).length);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"AMR_NB", "AMR_WB"})
        @DisplayName("survives hostile packets, raising nothing but MediaException")
        void hostile(Codec codec) {
            var random = new Random(4867);
            var clean = AmrTestSupport.encode(AmrEncoderOptions.of(AmrMode.highest(codec)), AmrTestSupport.signal(AmrTestSupport.rate(codec)));
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                for (var round = 0; round < 40; round++) {
                    try (var decoder = new AmrDecoder(AmrTestSupport.track(codec))) {
                        for (var index = 0; index < 150; index++) {
                            byte[] data;
                            if (random.nextInt(3) == 0) {
                                data = new byte[random.nextInt(80)];
                                random.nextBytes(data);
                            } else {
                                data = clean.get(random.nextInt(clean.size())).data().clone();
                                for (var flips = random.nextInt(8); flips > 0; flips--) {
                                    data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
                                }
                            }
                            try {
                                decoder.decode(new Packet(0, data, index, index, 1, true), buffer -> {
                                    for (var i = 0; i < buffer.frames(); i++) {
                                        var sample = buffer.plane(0)[i];
                                        if (!(sample >= -1f && sample <= 1f)) {
                                            throw new AssertionError("sample out of range: " + sample);
                                        }
                                    }
                                });
                            } catch (RuntimeException exception) {
                                assertInstanceOf(MediaException.class, exception);
                            }
                        }
                    }
                }
            });
        }
    }

    @Nested
    @DisplayName("encoder stress")
    class Stress {
        @ParameterizedTest(name = "{0} dtx={1}")
        @CsvSource({"AMR_NB, false", "AMR_NB, true", "AMR_WB, false", "AMR_WB, true"})
        @DisplayName("codes full-scale noise, square waves, homing patterns and per-frame mode changes into valid frames")
        void extremes(Codec codec, boolean dtx) {
            var random = new Random(26_190);
            var modes = Stream.of(AmrMode.values()).filter(mode -> mode.codec() == codec).toList();
            var samples = AmrTestSupport.frameSamples(codec);
            var frame = new byte[61];
            var pcm = new short[samples];
            assertTimeoutPreemptively(Duration.ofSeconds(60), () -> {
                try (var encoder = new AmrEncoder(AmrEncoderOptions.of(modes.getFirst()).toBuilder().dtx(dtx).build())) {
                    for (var index = 0; index < 3000; index++) {
                        var kind = random.nextInt(5);
                        for (var i = 0; i < samples; i++) {
                            pcm[i] = switch (kind) {
                                case 0 -> (short) random.nextInt(65536);
                                case 1 -> (short) ((i / (1 + index % 23)) % 2 == 0 ? 32767 : -32768);
                                case 2 -> (short) 8;
                                case 3 -> (short) 0;
                                default -> (short) Math.round(random.nextGaussian() * 30);
                            };
                        }
                        var mode = modes.get(random.nextInt(modes.size()));
                        encoder.setMode(mode);
                        var size = encoder.encodeFrame(pcm, 0, frame, 0);
                        var type = (frame[0] >> 3) & 0x0F;
                        var sid = codec == Codec.AMR_WB ? 9 : 8;
                        assertEquals(type == 15 ? 1 : type == sid ? 6 : mode.frameBytes(), size, "frame " + index);
                        assertTrue(type == mode.frameType() || dtx && (type == sid || type == 15), "frame type " + type);
                    }
                }
            });
        }
    }

    @Nested
    @DisplayName("API")
    class Api {
        @ParameterizedTest(name = "{0} {1} -> {2}")
        @CsvSource({"AMR_NB, 1, NB_4_75", "AMR_NB, 6000, NB_5_90", "AMR_NB, 7675, NB_7_40", "AMR_NB, 12200, NB_12_2",
                "AMR_NB, 64000, NB_12_2", "AMR_WB, 6000, WB_6_60", "AMR_WB, 13000, WB_12_65", "AMR_WB, 23450, WB_23_05",
                "AMR_WB, 128000, WB_23_85"})
        @DisplayName("maps a bitrate to the closest mode, the lower one on a tie")
        void closest(Codec codec, int bitrate, AmrMode expected) {
            assertEquals(expected, AmrMode.closest(codec, bitrate));
        }

        @Test
        @DisplayName("describes every mode by its frame type, bits and storage size")
        void modes() {
            assertEquals(AmrMode.NB_7_95, AmrMode.ofFrameType(Codec.AMR_NB, 5));
            assertEquals(AmrMode.WB_23_85, AmrMode.ofFrameType(Codec.AMR_WB, 8));
            assertThrows(IllegalArgumentException.class, () -> AmrMode.ofFrameType(Codec.AMR_NB, 8));
            assertThrows(IllegalArgumentException.class, () -> AmrMode.closest(Codec.OPUS, 8000));
            assertEquals(32, AmrMode.NB_12_2.frameBytes());
            assertEquals(61, AmrMode.WB_23_85.frameBytes());
            assertEquals(13, AmrMode.NB_4_75.frameBytes());
        }

        @Test
        @DisplayName("builds immutable options with a builder")
        void options() {
            var options = AmrEncoderOptions.defaults();
            assertEquals(AmrMode.NB_12_2, options.mode());
            assertFalse(options.dtx());
            assertEquals(8000, options.sampleRate());
            var wide = AmrEncoderOptions.wideband().toBuilder().dtx(true).build();
            assertEquals(Codec.AMR_WB, wide.codec());
            assertTrue(wide.dtx());
            assertEquals(wide, wide.toBuilder().build());
            assertEquals(AmrMode.WB_12_65, AmrEncoderOptions.ofBitrate(Codec.AMR_WB, 12650).mode());
        }

        @Test
        @DisplayName("rejects input of another format and modes of the other codec")
        void rejections() {
            try (var encoder = new AmrEncoder(AmrEncoderOptions.narrowband())) {
                assertThrows(IllegalArgumentException.class,
                        () -> encoder.encode(new AudioBuffer(ChannelLayout.MONO, 16000, 10), packet -> { }));
                assertThrows(IllegalArgumentException.class,
                        () -> encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 8000, 10), packet -> { }));
                assertThrows(IllegalArgumentException.class, () -> encoder.setMode(AmrMode.WB_6_60));
                encoder.flush(packet -> { });
                assertThrows(IllegalStateException.class,
                        () -> encoder.encode(new AudioBuffer(ChannelLayout.MONO, 8000, 10), packet -> { }));
            }
            assertThrows(IllegalArgumentException.class, () -> new AmrDecoder(AmrTestSupport.track(Codec.AAC)));
        }

        @Test
        @DisplayName("timestamps packets in samples and flushes nothing for empty input")
        void timestamps() {
            var packets = AmrTestSupport.encode(AmrEncoderOptions.narrowband(), new short[800]);
            for (var index = 0; index < packets.size(); index++) {
                assertEquals(index * 160L, packets.get(index).pts());
                assertEquals(160, packets.get(index).duration());
            }
            assertEquals(6, packets.size());
            assertEquals(0, AmrTestSupport.encode(AmrEncoderOptions.narrowband(), new short[0]).size());
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"AMR_NB", "AMR_WB"})
        @DisplayName("is reachable through the registries, which pick the codec's own format")
        void registries(Codec codec) {
            assertTrue(AudioDecoders.isSupported(codec));
            assertTrue(AudioEncoders.isSupported(codec));
            try (var encoder = AudioEncoders.create(codec, AudioEncoderOptions.of(44100, ChannelLayout.STEREO).toBuilder().bitrate(7000).build())) {
                assertEquals(AmrTestSupport.rate(codec), encoder.sampleRate());
                assertEquals(ChannelLayout.MONO, encoder.layout());
                assertEquals(codec == Codec.AMR_WB ? AmrMode.WB_6_60 : AmrMode.NB_6_70, ((AmrEncoder) encoder).mode());
            }
            try (var encoder = AudioEncoders.create(codec, AudioEncoderOptions.defaults())) {
                assertEquals(AmrMode.highest(codec), ((AmrEncoder) encoder).mode());
            }
            try (var decoder = AudioDecoders.create(AmrTestSupport.track(codec))) {
                assertEquals(AmrTestSupport.rate(codec), decoder.sampleRate());
                assertEquals(ChannelLayout.MONO, decoder.layout());
            }
        }
    }
}
