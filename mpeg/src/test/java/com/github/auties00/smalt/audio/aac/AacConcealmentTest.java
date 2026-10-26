package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.Tolerance;
import com.github.auties00.smalt.testkit.audio.PacketLoss;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks {@link AacDecoder#conceal(int, java.util.function.Consumer)}, the error concealment of a lost raw data
 * block, on every object type the decoder supports: Low Complexity streams of Smalt's encoder, AAC Main and LTP
 * streams of ffmpeg's encoder, and the ISO/IEC 14496-26 conformance streams of AAC SSR, ER AAC LC, AAC-LD, AAC-ELD,
 * HE-AAC and HE-AAC v2. Lost blocks
 * keep the output length, repeat the last spectrum with random signs at a falling gain without clicks, and the
 * decode converges back to the reference after the loss: exactly for Low Complexity, once the predictors and the
 * extension's envelopes refresh for the others. A black-box comparison decodes a Low Complexity stream with its lost
 * blocks removed through ffmpeg: two blocks after each loss both decoders agree within the decoder comparison's
 * tolerance, so the concealment leaves no trace in the decoder's state.
 */
class AacConcealmentTest {
    /** Two blocks after a loss both float decoders synthesize the same blocks, as in the decoder comparison. */
    private static final Tolerance AFTER_RECOVERY = Tolerance.snr(80);

    static List<Packet> packets(AacTestSupport.Stream stream, int frameLength) {
        var packets = new ArrayList<Packet>();
        for (var i = 0; i < stream.frames().size(); i++) {
            packets.add(new Packet(1, stream.frames().get(i), (long) i * frameLength, (long) i * frameLength,
                    frameLength, true));
        }
        return packets;
    }

    static PacketLoss.Encoded encode(int rate, int channels, int bitrate, double seconds) {
        try (var encoder = new AacEncoder(AacEncoderOptions.of(rate, ChannelLayout.defaultFor(channels), bitrate))) {
            return PacketLoss.encode(encoder, PacketLoss.signal(rate, channels, seconds));
        }
    }

    @Nested
    @DisplayName("Low Complexity streams of Smalt's encoder")
    class LowComplexity {
        @ParameterizedTest(name = "{0} Hz {1} channels {2} bit/s")
        @CsvSource({"48000, 2, 128000", "44100, 1, 64000", "16000, 1, 24000", "48000, 6, 320000"})
        @DisplayName("conceal bursts and a random trace keeping the length, without clicks, and recover exactly")
        void traces(int rate, int channels, int bitrate) {
            var encoded = encode(rate, channels, bitrate, 4);
            var packets = encoded.packets();
            var bounds = new PacketLoss.Bounds(true, 2.5, 6, 512, 10, 1, 120);
            for (var lost : List.of(PacketLoss.bursts(20, 1, 50, 2, 90, 7),
                    PacketLoss.random(rate + channels, packets.size(), 10, 2, 3, 3))) {
                PacketLoss.assertConceals(() -> new AacDecoder(encoded.track()), packets, lost, bounds);
            }
        }

        @ParameterizedTest(name = "{0} Hz {1} channels")
        @CsvSource({"48000, 2, 128000", "32000, 1, 48000"})
        @DisplayName("agree with ffmpeg decoding the stream without its lost blocks, two blocks after each loss")
        void againstFfmpeg(int rate, int channels, int bitrate) throws IOException {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var encoded = encode(rate, channels, bitrate, 3);
            var packets = encoded.packets();
            Set<Integer> lost = PacketLoss.bursts(20, 1, 45, 3, 80, 2);
            var reference = PacketLoss.decode(new AacDecoder(encoded.track()), packets);
            var lossy = PacketLoss.decode(new AacDecoder(encoded.track()), packets, lost, reference);
            var kept = new ArrayList<byte[]>();
            for (var i = 0; i < packets.size(); i++) {
                if (!lost.contains(i)) {
                    kept.add(packets.get(i).data());
                }
            }
            var file = Scratch.directory("aac-concealment").resolve("loss-" + rate + "-" + channels + ".aac");
            Files.write(file, AacTestSupport.writeAdts(encoded.track().codecConfig().orElseThrow(), kept));
            var ffmpeg = PacketLoss.deinterleave(Oracle.decodeAudio(file), channels);
            var position = 0;
            var compared = 0;
            for (var i = 0; i < packets.size(); i++) {
                if (lost.contains(i)) {
                    continue;
                }
                if (i >= 2 && !lost.contains(i - 1) && !lost.contains(i - 2) && position + 1024 <= ffmpeg[0].length) {
                    for (var c = 0; c < channels; c++) {
                        var ours = Arrays.copyOfRange(lossy.planes()[c], lossy.starts()[i], lossy.starts()[i] + 1024);
                        var theirs = Arrays.copyOfRange(ffmpeg[c], position, position + 1024);
                        AFTER_RECOVERY.assertPcm("block " + i + " channel " + c, theirs, ours);
                    }
                    compared++;
                }
                position += 1024;
            }
            assertTrue(compared > packets.size() / 2, "compared " + compared + " blocks");
        }

        @Test
        @DisplayName("round the span to whole blocks, conceal with silence before the first, and fade to silence")
        void units() {
            var encoded = encode(48000, 2, 96000, 1);
            var decoder = new AacDecoder(encoded.track());
            var handed = new int[1];
            assertEquals(1024, decoder.conceal(100, buffer -> {
                handed[0] += buffer.frames();
                for (var i = 0; i < buffer.frames(); i++) {
                    assertEquals(0, buffer.plane(1)[i]);
                }
            }));
            assertEquals(1024, handed[0]);
            for (var i = 0; i < 20; i++) {
                decoder.decode(encoded.packets().get(i), buffer -> { });
            }
            var energies = new ArrayList<Double>();
            assertEquals(8 * 1024, decoder.conceal(8 * 1024 + 300, buffer -> energies.add(
                    PacketLoss.energy(new float[][]{buffer.plane(0), buffer.plane(1)}, 0, buffer.frames()))));
            assertEquals(8, energies.size());
            assertTrue(energies.get(0) > energies.get(2) && energies.get(2) > energies.get(3),
                    "the concealment fades: " + energies);
            assertEquals(0, energies.get(6), "the sixth block is silent: " + energies);
            assertEquals(0, energies.get(7));
            assertThrows(IllegalArgumentException.class, () -> decoder.conceal(-1, buffer -> { }));
            assertTrue(decoder.supportsConcealment());
        }
    }

    @Nested
    @DisplayName("AAC Main and LTP streams of ffmpeg's encoder")
    class Prediction {
        @ParameterizedTest(name = "{0}")
        @CsvSource({"aac_main, -aac_pred, 40, 10", "aac_ltp, -aac_ltp, 10, 15"})
        @DisplayName("conceal blocks, the predictors continuing from the concealed signal")
        void traces(String profile, String option, int recoveryPackets, double recoverySnr) {
            var file = AacDecoderDifferentialTest.fixture("chirp", 2, 48000, 4, "aac", "-strict", "-2", "-profile:a",
                    profile, option, "1", "-aac_is", "0", "-b:a", "96000");
            ConformanceStreams.check(AacTestSupport.parseAdts(Fixtures.read(file)), recoveryPackets, recoverySnr);
        }
    }

    @Nested
    @DisplayName("ISO/IEC 14496-26 conformance streams")
    class ConformanceStreams {
        static void check(AacTestSupport.Stream stream, int recoveryPackets, double recoverySnr) {
            var track = stream.track();
            int frameLength;
            try (var probe = new AacDecoder(track)) {
                probe.decode(new Packet(1, stream.frames().getFirst(), 0, 0, 0, true), buffer -> { });
                frameLength = probe.frameLength();
            }
            var packets = packets(stream, frameLength);
            var bounds = new PacketLoss.Bounds(true, 3.0, 6, frameLength / 2, 12, recoveryPackets, recoverySnr);
            var count = packets.size();
            for (var lost : List.of(PacketLoss.bursts(count / 5, 1, count / 2, 3),
                    PacketLoss.random(count, count, 8, 2, 4, recoveryPackets + 2))) {
                PacketLoss.assertConceals(() -> new AacDecoder(track), packets, lost, bounds);
            }
        }

        @Test
        @DisplayName("conceal AAC SSR blocks")
        void scalableSampleRate() {
            check(AacTestSupport.parseMp4(Fixtures.read(AacObjectTypesConformanceTest.fetch("as00_48.mp4"))), 4, 60);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"er_al10_48_ep0.mp4, 1, 100", "er_ad1110np_48_ep0.mp4, 2, 60", "er_eld1040np_48_ep0.mp4, 4, 60",
                "er_eld_sbr_e_2_48.mp4, 12, 6"})
        @DisplayName("conceal error resilient, low delay and enhanced low delay blocks")
        void errorResilient(String name, int recoveryPackets, double recoverySnr) {
            check(AacTestSupport.parseMp4(Fixtures.read(ErrorResilientVectors.fetch(name))), recoveryPackets,
                    recoverySnr);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"al_sbr_e_48_2, 12, 6", "al_sbr_cm_48_5.1, 12, 6", "al_sbr_ps_00, 12, 3"})
        @DisplayName("conceal HE-AAC and HE-AAC v2 blocks, the high band repeating its last envelope")
        void spectralBandReplication(String name, int recoveryPackets, double recoverySnr) {
            check(AacTestSupport.parseMp4(Fixtures.read(HeAacConformanceTest.fetch(name))), recoveryPackets,
                    recoverySnr);
        }
    }
}
