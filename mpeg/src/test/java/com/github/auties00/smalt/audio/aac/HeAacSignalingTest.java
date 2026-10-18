package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Covers how the AAC decoder chooses the output of HE-AAC streams from the configuration, the
 * rate the track declares and the first packet, and its tolerance of corrupt extension data.
 */
class HeAacSignalingTest {
    static byte[] bits(int... fields) {
        var writer = new MsbBitWriter();
        for (var i = 0; i < fields.length; i += 2) {
            writer.write(fields[i], fields[i + 1]);
        }
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    static AudioTrack track(byte[] config, int declaredRate) {
        return AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(24000)).codecConfig(config)
                .sampleRate(declaredRate).build();
    }

    static AacTestSupport.Stream stream(String name) {
        return AacTestSupport.parseMp4(Fixtures.read(HeAacConformanceTest.fetch(name)));
    }

    @Nested
    @DisplayName("from the configuration alone")
    class Configuration {
        @ParameterizedTest(name = "{0}")
        @CsvSource({
                // object type 5, core 24 kHz stereo, extension 48 kHz
                "explicit dual rate, 05, 6, 3, 2, 48000, 2048, 2, true",
                // object type 5 with the extension at the core rate
                "explicit downsampled, 05, 6, 6, 2, 24000, 1024, 2, true",
                // object type 5 on a 64 kHz core: 128 kHz output is beyond 96 kHz
                "high core rate, 05, 2, 0, 2, 64000, 1024, 2, true",
                // object type 5 on a mono core: parametric stereo may follow
                "mono core, 05, 6, 3, 1, 48000, 2048, 2, true",
                // object type 29 on a mono core
                "explicit parametric stereo, 1d, 6, 3, 1, 48000, 2048, 2, true"
        })
        @DisplayName("decide explicit signaling before the first packet")
        void explicit(String what, String objectType, int coreIndex, int extensionIndex, int channels, int rate,
                      int frameLength, int outputChannels, boolean active) {
            var config = bits(Integer.parseInt(objectType, 16), 5, coreIndex, 4, channels, 4, extensionIndex, 4,
                    2, 5, 0, 3);
            var decoder = new AacDecoder(track(config, 0));
            assertEquals(rate, decoder.sampleRate(), what);
            assertEquals(frameLength, decoder.frameLength(), what);
            assertEquals(outputChannels, decoder.layout().channels(), what);
            assertEquals(active, decoder.decodesSpectralBandReplication(), what);
        }

        @Test
        @DisplayName("decodes the core only when the sync extension signals that SBR is absent")
        void signaledAbsence() {
            var config = bits(2, 5, 6, 4, 2, 4, 0, 3, 0x2B7, 11, 5, 5, 0, 1);
            var decoder = new AacDecoder(track(config, 48000));
            assertEquals(24000, decoder.sampleRate());
            assertFalse(decoder.decodesSpectralBandReplication());
            assertFalse(decoder.spectralBandReplicationSignaled());
        }

        @Test
        @DisplayName("keeps a mono core mono when the sync extension signals that PS is absent")
        void parametricStereoAbsent() {
            var absent = bits(2, 5, 6, 4, 1, 4, 0, 3, 0x2B7, 11, 5, 5, 1, 1, 3, 4, 0x548, 11, 0, 1);
            var decoder = new AacDecoder(track(absent, 0));
            assertEquals(48000, decoder.sampleRate());
            assertEquals(ChannelLayout.MONO, decoder.layout());
            assertFalse(decoder.parametricStereoSignaled());
            var present = bits(2, 5, 6, 4, 1, 4, 0, 3, 0x2B7, 11, 5, 5, 1, 1, 3, 4, 0x548, 11, 1, 1);
            assertEquals(ChannelLayout.STEREO, new AacDecoder(track(present, 0)).layout());
        }

        @Test
        @DisplayName("decodes implicit signaling at dual rate when the track declares twice the core rate")
        void declaredDualRate() {
            var decoder = new AacDecoder(track(bits(2, 5, 6, 4, 2, 4, 0, 3), 48000));
            assertEquals(48000, decoder.sampleRate());
            assertEquals(2048, decoder.frameLength());
            assertTrue(decoder.decodesSpectralBandReplication());
        }

        @Test
        @DisplayName("turns every extension off when the options disable it")
        void disabled() {
            var options = AacDecoderOptions.defaults().toBuilder().spectralBandReplication(false).build();
            var explicit = new AacDecoder(track(bits(0x1d, 5, 6, 4, 1, 4, 3, 4, 2, 5, 0, 3), 0), options);
            assertEquals(24000, explicit.sampleRate());
            assertEquals(ChannelLayout.MONO, explicit.layout());
            assertFalse(explicit.decodesSpectralBandReplication());
            var implicit = new AacDecoder(track(bits(2, 5, 6, 4, 2, 4, 0, 3), 48000), options);
            assertEquals(24000, implicit.sampleRate());
            assertEquals(1024, implicit.frameLength());
        }
    }

    @Nested
    @DisplayName("from the first packet")
    class FirstPacket {
        @ParameterizedTest(name = "declared {0} Hz")
        @CsvSource({"0, 48000, 2048", "24000, 24000, 1024", "48000, 48000, 2048"})
        @DisplayName("decodes implicit signaling at the rate the track leaves room for")
        void implicit(int declared, int rate, int frameLength) {
            var stream = stream("al_sbr_sig_48_2_sig0");
            var decoder = new AacDecoder(AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(24000))
                    .codecConfig(stream.config()).sampleRate(declared).build());
            var decoded = AacTestSupport.decodeAll(decoder, stream.frames().subList(0, 8));
            assertEquals(rate, decoded.sampleRate());
            assertEquals(rate, decoder.sampleRate());
            assertEquals(frameLength, decoder.frameLength());
            assertEquals(8L * frameLength, decoded.frames());
            assertTrue(decoder.decodesSpectralBandReplication());
        }

        @Test
        @DisplayName("leaves a Low Complexity stream that declares no rate at its core rate")
        void lowComplexity() {
            var stream = AacTestSupport.parseAdts(Fixtures.read(AacDecoderDifferentialTest.fixture("tones", 2, 24000, 1,
                    "aac", "-b:a", "64000")));
            var decoder = new AacDecoder(AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(24000))
                    .codecConfig(stream.config()).sampleRate(0).build());
            var decoded = AacTestSupport.decodeAll(decoder, stream.frames());
            assertEquals(24000, decoded.sampleRate());
            assertFalse(decoder.decodesSpectralBandReplication());
            assertArrayEquals(AacTestSupport.decodeAll(stream).planes()[0], decoded.planes()[0]);
        }

        @Test
        @DisplayName("decodes implicit, backward-compatible and hierarchical signaling of one stream alike")
        void signalingVariants() {
            var implicit = AacTestSupport.decodeAll(new AacDecoder(HeAacConformanceTest.detectingTrack(stream("al_sbr_sig_48_2_sig0"))),
                    stream("al_sbr_sig_48_2_sig0").frames());
            for (var name : new String[]{"al_sbr_sig_48_2_sig1", "al_sbr_sig_48_2_sig2"}) {
                var other = stream(name);
                var decoded = AacTestSupport.decodeAll(new AacDecoder(HeAacConformanceTest.detectingTrack(other)), other.frames());
                assertEquals(implicit.sampleRate(), decoded.sampleRate(), name);
                assertTrue(AacTestSupport.snr(implicit.planes(), decoded.planes()) > 100, name);
            }
        }
    }

    @Nested
    @DisplayName("corrupt extension data")
    class Robustness {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"al_sbr_cm_48_2", "al_sbr_ps_01", "al960_sbr_ps_00", "al_sbr_sr_48_2_fsaac48"})
        @DisplayName("decodes or raises MediaException for bit flips, truncations and garbage")
        void mutations(String name) {
            var stream = stream(name);
            assertTimeoutPreemptively(Duration.ofSeconds(120), () -> {
                var random = new Random(name.hashCode());
                for (var round = 0; round < 200; round++) {
                    var frames = new ArrayList<byte[]>();
                    var start = random.nextInt(Math.max(1, stream.frames().size() - 16));
                    for (var frame : stream.frames().subList(start, Math.min(start + 16, stream.frames().size()))) {
                        var mutated = frame.clone();
                        switch (random.nextInt(5)) {
                            case 0 -> {
                                for (var flips = 1 + random.nextInt(8); flips > 0; flips--) {
                                    var position = random.nextInt(mutated.length);
                                    mutated[position] ^= (byte) (1 << random.nextInt(8));
                                }
                            }
                            case 1 -> mutated = Arrays.copyOf(mutated, random.nextInt(mutated.length + 1));
                            case 2 -> {
                                // garbage in the second half, where the fill elements usually are
                                for (var i = mutated.length / 2; i < mutated.length; i++) {
                                    mutated[i] = (byte) random.nextInt(256);
                                }
                            }
                            case 3 -> {
                                var position = mutated.length / 2 + random.nextInt(mutated.length - mutated.length / 2);
                                mutated[position] = (byte) random.nextInt(256);
                            }
                            default -> {
                            }
                        }
                        frames.add(mutated);
                    }
                    var decoder = new AacDecoder(HeAacConformanceTest.detectingTrack(stream));
                    for (var frame : frames) {
                        try {
                            decoder.decode(new Packet(1, frame, 0, 0, 0, true), buffer -> {
                                for (var c = 0; c < buffer.channels(); c++) {
                                    for (var i = 0; i < buffer.frames(); i++) {
                                        if (!Float.isFinite(buffer.plane(c)[i])) {
                                            fail("non-finite output");
                                        }
                                    }
                                }
                            });
                        } catch (MediaException expected) {
                            if (expected.getCause() != null) {
                                throw new AssertionError("the safety net caught " + expected.getCause(), expected.getCause());
                            }
                        } catch (RuntimeException unexpected) {
                            throw new AssertionError("decoder leaked " + unexpected, unexpected);
                        }
                    }
                }
            });
        }

        @Test
        @DisplayName("returns to the exact clean output once valid data follows corrupt frames")
        void recovery() {
            var stream = stream("al_sbr_ps_01");
            var clean = AacTestSupport.decodeAll(new AacDecoder(HeAacConformanceTest.detectingTrack(stream)), stream.frames());
            var decoder = new AacDecoder(HeAacConformanceTest.detectingTrack(stream));
            var collected = new ArrayList<float[][]>();
            for (var index = 0; index < stream.frames().size(); index++) {
                var frame = stream.frames().get(index);
                if (index >= 40 && index < 44) {
                    frame = frame.clone();
                    for (var i = frame.length * 3 / 4; i < frame.length; i++) {
                        frame[i] ^= (byte) 0x5A;
                    }
                }
                try {
                    decoder.decode(new Packet(1, frame, 0, 0, 0, true), buffer -> collected.add(AacTestSupport.copy(buffer)));
                } catch (MediaException ignored) {
                    // a damaged core block may be rejected: the clean output lines up again below
                    collected.add(null);
                }
            }
            var length = decoder.frameLength();
            var damaged = false;
            for (var index = 40; index < 46; index++) {
                var block = collected.get(index);
                damaged |= block == null
                        || !Arrays.equals(block[0], Arrays.copyOfRange(clean.planes()[0], index * length, (index + 1) * length));
            }
            assertTrue(damaged, "the corruption reaches the output");
            // the delay lines of the filterbanks and of parametric stereo span a few frames
            for (var index = collected.size() - 60; index < collected.size(); index++) {
                for (var c = 0; c < 2; c++) {
                    assertArrayEquals(Arrays.copyOfRange(clean.planes()[c], index * length, (index + 1) * length),
                            collected.get(index)[c], "frame " + index + " channel " + c);
                }
            }
        }
    }
}
