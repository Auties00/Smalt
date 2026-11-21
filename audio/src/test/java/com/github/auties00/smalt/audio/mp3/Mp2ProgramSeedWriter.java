package com.github.auties00.smalt.audio.mp3;

import com.github.auties00.smalt.testkit.fuzz.FuzzPackets;
import com.github.auties00.smalt.testkit.fuzz.FuzzSeeds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.List;
import java.util.SplittableRandom;

/**
 * Writes the committed seeds of the {@code mp2Programs} fuzz target (tools/fuzz/README.md) from synthetic streams of
 * {@link Mp2MultichannelStreams}: a second stereo program, half-rate multilingual channels selected together and
 * alone, and a stream carrying both; and of {@link Layer3MultichannelStreams} and a Layer I group, whose decoding
 * shares the target. Runs only with {@code -Dsmalt.fuzz.writeSeeds=true}.
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
        var layer3 = new Layer3MultichannelStreams.Configuration().defaults();
        layer3.lfe = true;
        layer3.dematrixed(Layer3MultichannelStreams.LS);
        write("valid-layer3-main.bin", 0, layer3Stream(layer3, 69));
        var second = new Layer3MultichannelStreams.Configuration().defaults();
        second.surround = 3;
        write("valid-layer3-second-stereo.bin", 1, layer3Stream(second, 71));
        var languages = new Layer3MultichannelStreams.Configuration().defaults();
        languages.surround = 0;
        languages.multilingual = 2;
        languages.multilingualHalfRate = true;
        var random = new SplittableRandom(73);
        var extra = new int[3][2][2][];
        for (var frame : extra) {
            for (var granule : frame) {
                granule[0] = Layer3MultichannelStreams.randomLines(random, 40);
                granule[1] = Layer3MultichannelStreams.randomLines(random, 40);
            }
        }
        languages.multilingualLines = extra;
        write("valid-layer3-multilingual.bin", 2, layer3Stream(languages, 75));
        write("valid-layer1-group.bin", 0, layer1Group());
    }

    /** Returns a three frame Layer III stream of a configuration, over random lines. */
    private static List<byte[]> layer3Stream(Layer3MultichannelStreams.Configuration configuration, long seed) {
        var random = new SplittableRandom(seed);
        var base = new int[3][2][2][];
        var channels = new int[3][2][7][];
        var lfe = new int[3][2][6];
        for (var frame = 0; frame < 3; frame++) {
            lfe[frame][0][0] = 2;
            for (var granule = 0; granule < 2; granule++) {
                base[frame][granule][0] = Layer3MultichannelStreams.randomLines(random, 40);
                base[frame][granule][1] = Layer3MultichannelStreams.randomLines(random, 40);
                for (var ch = 0; ch < 7; ch++) {
                    channels[frame][granule][ch] = Layer3MultichannelStreams.randomLines(random, 40);
                }
            }
        }
        return Layer3MultichannelStreams.build(configuration, base, channels, lfe);
    }

    /**
     * Returns three silent Layer I frames, each with empty audio data, whose ancillary data holds a {@code 3/0}
     * extension with no allocated samples.
     */
    static List<byte[]> layer1Group() {
        return layer1Group(1, 0);
    }

    /**
     * Returns three silent Layer I frames whose ancillary data holds an extension of no allocated samples.
     *
     * @param center the {@code centre} field, 1 for a center channel
     * @param surround the {@code surround} field, 0 for none or 3 for a second stereo program without a center
     */
    static List<byte[]> layer1Group(int center, int surround) {
        var status = new Layer3MultichannelStreams.Bits();
        status.write(4, 3);
        var channels = center + (surround == 3 ? 2 : 0);
        if (center != 0) {
            status.write(0, 2);
        }
        // the 27 subbands of Table B.2a that 48 kHz codes: 4 bits for 11, 3 bits for 12, 2 bits for 4
        for (var band = 0; band < 11; band++) {
            status.write(0, 4 * channels);
        }
        for (var band = 0; band < 12; band++) {
            status.write(0, 3 * channels);
        }
        for (var band = 0; band < 4; band++) {
            status.write(0, 2 * channels);
        }
        var header = new Layer3MultichannelStreams.Bits();
        header.write(0, 1);
        header.write(center, 2);
        header.write(surround, 2);
        header.write(0, 1);
        header.write(0, 1);
        header.write(0, 2);
        header.write(0, 3);
        header.write(0, 4);
        var crc = 0xFFFF;
        var headerBytes = header.toBytes();
        for (var i = 0; i < header.size; i++) {
            crc = MpegAudioCrc.update(crc, (headerBytes[i >> 3] >> (7 - (i & 7))) & 1, 1);
        }
        var statusBytes = status.toBytes();
        for (var i = 0; i < status.size; i++) {
            crc = MpegAudioCrc.update(crc, (statusBytes[i >> 3] >> (7 - (i & 7))) & 1, 1);
        }
        var extension = new Layer3MultichannelStreams.Bits();
        extension.append(header);
        extension.write(crc, 16);
        extension.append(status);
        extension.align();
        var bytes = extension.toBytes();
        var frames = new java.util.ArrayList<byte[]>();
        // 384 kbit/s at 48 kHz: 384 bytes per frame, 32 of them the empty allocation of both channels
        for (var part = 0; part < 3; part++) {
            var frame = new byte[384];
            frame[0] = (byte) 0xFF;
            frame[1] = (byte) 0xFF;
            frame[2] = (byte) 0xC4;
            var room = 384 - 4 - 32;
            var from = part * room;
            if (from < bytes.length) {
                System.arraycopy(bytes, from, frame, 36, Math.min(room, bytes.length - from));
            }
            frames.add(frame);
        }
        return frames;
    }

    private static void write(String name, int options, List<byte[]> frames) {
        var packets = frames.stream().map(data -> new FuzzPackets.Part(data, true)).toList();
        FuzzSeeds.write("com.github.auties00.smalt.audio.fuzz.AudioFuzzTest", "mp2Programs", name,
                FuzzPackets.audio(options, 48000, 2, 0, null, List.copyOf(packets)));
    }

    private static void seed(String name, int options, Mp2MultichannelStreams.Configuration configuration, long seed,
                             boolean extensionFrames) {
        var packets = Mp2MultichannelStreams.build(configuration, 3, seed, extensionFrames).stream()
                .map(data -> new FuzzPackets.Part(data, true)).toList();
        FuzzSeeds.write("com.github.auties00.smalt.audio.fuzz.AudioFuzzTest", "mp2Programs", name,
                FuzzPackets.audio(options, 48000, 2, 0, null, List.copyOf(packets)));
    }
}
