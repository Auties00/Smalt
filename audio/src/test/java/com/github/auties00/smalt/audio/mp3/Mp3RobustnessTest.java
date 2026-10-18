package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.mpeg.MpegAudioDemuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.audio.AudioMutations;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Feeds deterministic mutations of valid MPEG audio files through the demuxer and decoder, and
 * mutated packets straight into the decoder: every run must end normally or with a
 * {@link MediaException}, in bounded time, and decoded samples must stay finite.
 */
class Mp3RobustnessTest {
    private static List<byte[]> seeds() {
        var seeds = new ArrayList<byte[]>();
        seeds.add(Fixtures.read(Mp3Fixtures.lame(44100, 2, 0.4, "-b:a", "192k")));
        seeds.add(Fixtures.read(Mp3Fixtures.lame(22050, 1, 0.5, "-q:a", "2")));
        seeds.add(Fixtures.read(Mp3Fixtures.lame(8000, 2, 0.6, "-b:a", "24k", "-write_xing", "0")));
        seeds.add(Fixtures.read(Mp3Fixtures.encode("mp2", 32000, 2, 0.4, List.of("-c:a", "mp2", "-b:a", "128k"))));
        seeds.add(Mp3Vectors.read(Mp3Vectors.fetch("l1-fl4.bit")));
        seeds.add(java.util.Arrays.copyOf(Mp3Vectors.read(Mp3Vectors.fetch("l3-he_free.bit")), 6000));
        return seeds;
    }

    private static void decodeAll(byte[] data) {
        try (var demuxer = new MpegAudioDemuxer(MediaSource.of(data))) {
            var decoded = AudioPipeline.decode(demuxer, Mp3Decoder::new);
            for (var sample : decoded.interleaved()) {
                if (!Float.isFinite(sample)) {
                    fail("non-finite sample");
                }
            }
        }
    }

    @Test
    @DisplayName("mutated files demux and decode or fail with a MediaException")
    void mutatedFiles() {
        var outcomes = AudioMutations.assertRobust(seeds(), 200, 20260928L, Duration.ofSeconds(10),
                Mp3RobustnessTest::decodeAll);
        System.out.println("MP3 file mutation outcomes: " + outcomes);
        assertTrue(outcomes.getOrDefault("ok", 0) > 0);
    }

    @Test
    @DisplayName("packets with corrupted payloads decode or fail with a MediaException")
    void mutatedPackets() {
        var outcomes = new TreeMap<String, Integer>();
        var random = new SplittableRandom(99);
        for (var seed : seeds()) {
            List<Packet> packets;
            com.github.auties00.smalt.track.AudioTrack track;
            try (var demuxer = new MpegAudioDemuxer(MediaSource.of(seed))) {
                track = AudioPipeline.firstAudio(demuxer);
                packets = AudioPipeline.packets(demuxer, 0);
            }
            for (var round = 0; round < 40; round++) {
                var mutated = new ArrayList<Packet>();
                for (var packet : packets) {
                    var data = packet.data().clone();
                    if (random.nextInt(3) == 0 && data.length > 4) {
                        var flips = 1 + random.nextInt(24);
                        for (var i = 0; i < flips; i++) {
                            // keep the sync word so the payload, not the framing, is what gets fuzzed
                            var index = 2 + random.nextInt(data.length - 2);
                            data[index] ^= (byte) (1 << random.nextInt(8));
                        }
                    }
                    if (random.nextInt(20) == 0) {
                        data = java.util.Arrays.copyOf(data, random.nextInt(data.length + 1));
                    }
                    mutated.add(new Packet(packet.trackId(), data, packet.pts(), packet.dts(), packet.duration(), true));
                }
                var finalTrack = track;
                String outcome = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                    try (var decoder = new Mp3Decoder(finalTrack)) {
                        for (var packet : mutated) {
                            try {
                                decoder.decode(packet, buffer -> {
                                    for (var c = 0; c < buffer.channels(); c++) {
                                        for (var i = 0; i < buffer.frames(); i++) {
                                            if (!Float.isFinite(buffer.plane(c)[i])) {
                                                fail("non-finite sample");
                                            }
                                        }
                                    }
                                });
                            } catch (MediaException.Malformed rejected) {
                                // a packet without a valid header; decoding continues with the next
                            }
                        }
                        return "ok";
                    } catch (MediaException exception) {
                        return exception.getClass().getSimpleName();
                    }
                });
                outcomes.merge(outcome, 1, Integer::sum);
            }
        }
        System.out.println("MP3 packet mutation outcomes: " + outcomes);
    }
}
