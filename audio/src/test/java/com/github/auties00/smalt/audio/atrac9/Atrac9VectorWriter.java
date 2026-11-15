package com.github.auties00.smalt.audio.atrac9;

import com.github.auties00.smalt.testkit.fuzz.FuzzPackets;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the committed ATRAC9 test vectors ({@code src/test/resources/com/github/auties00/smalt/audio/atrac9}, whose
 * expected decodes {@code tools/testdata/atrac9-oracle/make-vectors.py} computes with LibAtrac9), the I/O behavior
 * samples {@code wav/atrac9.at9} and {@code matroska/atrac9.mka} of core, the training files of smalt-aot's
 * {@code atrac9-decode} workload, and the seeds of the {@code atrac9} fuzz target, all made by
 * {@link Atrac9TestStreams}. Runs only with
 * {@code -Dsmalt.atrac9.writeVectors=true}.
 */
@EnabledIfSystemProperty(named = "smalt.atrac9.writeVectors", matches = "true",
        disabledReason = "[opt-in:atrac9-vectors] runs only with -Dsmalt.atrac9.writeVectors=true")
class Atrac9VectorWriter {
    /** A vector: its name and its stream. */
    record Vector(String name, Atrac9TestStreams.Stream stream) {
    }

    static List<Vector> vectors() {
        var vectors = new ArrayList<Vector>();
        // syntax-random streams over the rate families, the channel configurations and the superframe sizes
        vectors.add(new Vector("random-11k-mono", Atrac9TestStreams.random(0, 0, 0, 8, 101)));
        vectors.add(new Vector("random-16k-dual-mono", Atrac9TestStreams.random(2, 1, 2, 4, 102)));
        vectors.add(new Vector("random-32k-stereo", Atrac9TestStreams.random(5, 2, 2, 4, 103)));
        vectors.add(new Vector("random-48k-5.1", Atrac9TestStreams.random(7, 3, 2, 3, 104)));
        vectors.add(new Vector("random-48k-7.1", Atrac9TestStreams.random(7, 4, 0, 6, 105)));
        vectors.add(new Vector("random-44k-quad", Atrac9TestStreams.random(6, 5, 2, 3, 106)));
        vectors.add(new Vector("random-96k-stereo", Atrac9TestStreams.random(12, 2, 2, 4, 107)));
        vectors.add(new Vector("random-192k-mono", Atrac9TestStreams.random(15, 0, 2, 4, 108)));
        // encoded audio
        vectors.add(new Vector("encoded-48k-stereo",
                Atrac9TestStreams.encode(7, 2, 2, 512, Atrac9DecoderTest.signal(2, 6000, 48000, 1), true)));
        vectors.add(new Vector("encoded-24k-mono",
                Atrac9TestStreams.encode(4, 0, 0, 128, Atrac9DecoderTest.signal(1, 4000, 24000, 2), false)));
        return vectors;
    }

    static Path directory() {
        return Path.of("src", "test", "resources", "com", "github", "auties00", "smalt", "audio", "atrac9");
    }

    @Test
    @DisplayName("writes the test vectors")
    void vectorFiles() throws IOException {
        Files.createDirectories(directory());
        for (var vector : vectors()) {
            Files.write(directory().resolve(vector.name() + ".a9"), vector.stream().concatenated());
        }
    }

    @Test
    @DisplayName("writes the I/O behavior samples of core")
    void samples() throws IOException {
        var samples = Path.of("..", "core", "src", "test", "resources", "samples");
        var stream = Atrac9TestStreams.encode(4, 2, 2, 160, Atrac9DecoderTest.signal(2, 6000, 24000, 4), true);
        var config = stream.parsed();
        var at9 = Atrac9FileTest.at9(stream, config.frameSamples(), 5800);
        Files.write(samples.resolve("wav").resolve("atrac9.at9"), at9);
        var sink = com.github.auties00.smalt.io.MediaSink.memory();
        try (var demuxer = new com.github.auties00.smalt.container.wav.WavDemuxer(
                com.github.auties00.smalt.io.MediaSource.of(at9));
             var muxer = new com.github.auties00.smalt.container.matroska.MatroskaMuxer(sink)) {
            var track = demuxer.tracks().getFirst();
            muxer.addTrack(track);
            com.github.auties00.smalt.track.Packet packet;
            while ((packet = demuxer.read()) != null) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        Files.write(samples.resolve("matroska").resolve("atrac9.mka"), sink.toByteArray());
        // the training files of smalt-aot's atrac9-decode workload
        var aot = Path.of("..", "aot", "src", "main", "resources", "com", "github", "auties00", "smalt", "aot",
                "internal", "atrac9");
        Files.createDirectories(aot);
        Files.write(aot.resolve("stereo-24k.at9"), at9);
        var surround = Atrac9TestStreams.encode(6, 3, 2, 700, Atrac9DecoderTest.signal(6, 8000, 44100, 5), false);
        Files.write(aot.resolve("surround-44k.at9"), Atrac9FileTest.at9(surround, surround.parsed().frameSamples(),
                7800));
        var random = Atrac9TestStreams.random(12, 2, 2, 8, 301);
        Files.write(aot.resolve("random-96k.at9"), Atrac9FileTest.at9(random, 0,
                8L * random.parsed().superframeSamples()));
    }

    @Test
    @DisplayName("writes the seeds of the atrac9 fuzz target")
    void seeds() {
        var fuzzClass = "com.github.auties00.smalt.audio.fuzz.AudioFuzzTest";
        var seeds = List.of(
                new Vector("valid-stereo.bin", Atrac9TestStreams.random(7, 2, 2, 3, 201)),
                new Vector("valid-mono-lowrate.bin", Atrac9TestStreams.random(1, 0, 0, 6, 202)),
                new Vector("valid-surround.bin", Atrac9TestStreams.random(5, 3, 0, 3, 203)),
                new Vector("valid-highrate.bin", Atrac9TestStreams.random(13, 2, 2, 2, 204)),
                new Vector("valid-encoded.bin", Atrac9TestStreams.encode(6, 2, 2, 256,
                        Atrac9DecoderTest.signal(2, 3000, 44100, 3), true)));
        for (var seed : seeds) {
            var stream = seed.stream();
            var config = stream.parsed();
            var parts = new ArrayList<FuzzPackets.Part>();
            for (var superframe : stream.superframes()) {
                parts.add(new FuzzPackets.Part(superframe, true));
            }
            FuzzSeeds.write(fuzzClass, "atrac9", seed.name(), FuzzPackets.audio(0, config.sampleRate(),
                    config.channelCount(), 0, stream.config(), parts));
        }
    }
}
