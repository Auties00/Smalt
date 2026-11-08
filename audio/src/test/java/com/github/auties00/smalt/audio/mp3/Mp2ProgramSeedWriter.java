package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.testkit.fuzz.FuzzPackets;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;

/**
 * Writes the committed seeds of the {@code mp2Programs} fuzz target (tools/fuzz/README.md) from synthetic streams of
 * {@link Mp2MultichannelStreams}: a second stereo program, half-rate multilingual channels selected together and
 * alone, and a stream carrying both. Runs only with {@code -Dsmalt.fuzz.writeSeeds=true}.
 */
@EnabledIfSystemProperty(named = "smalt.fuzz.writeSeeds", matches = "true",
        disabledReason = "[opt-in:fuzz-seeds] runs only with -Dsmalt.fuzz.writeSeeds=true")
class Mp2ProgramSeedWriter {
    @Test
    @DisplayName("writes the seeds of the MPEG-2 program selection target")
    void seeds() {
        seed("valid-second-stereo.bin", 1, new Mp2MultichannelStreams.Configuration(1, 3, false, 0), 61, false);
        seed("valid-multilingual-half-rate.bin", 2,
                new Mp2MultichannelStreams.Configuration(1, 2, false, 0, 3, true, false), 63, true);
        seed("valid-multilingual-one.bin", 3 | 1 << 2,
                new Mp2MultichannelStreams.Configuration(0, 0, false, 3, 2, false, false), 65, true);
        seed("valid-both-main.bin", 0, new Mp2MultichannelStreams.Configuration(1, 3, false, 0, 5, false, false), 67,
                true);
    }

    private static void seed(String name, int options, Mp2MultichannelStreams.Configuration configuration, long seed,
                             boolean extensionFrames) {
        var packets = Mp2MultichannelStreams.build(configuration, 3, seed, extensionFrames).stream()
                .map(data -> new FuzzPackets.Part(data, true)).toList();
        FuzzSeeds.write("com.github.auties00.smalt.audio.fuzz.AudioFuzzTest", "mp2Programs", name,
                FuzzPackets.audio(options, 48000, 2, 0, null, List.copyOf(packets)));
    }
}
