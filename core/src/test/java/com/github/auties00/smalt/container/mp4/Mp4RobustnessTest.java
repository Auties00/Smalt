package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.container.MemoryChannel;
import com.github.auties00.smalt.container.Mutations;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mutation tests of {@link Mp4Demuxer} and {@link Mp4Remuxer}: several hundred corrupted variants
 * of valid files must each demux (and remux) successfully or fail with a
 * {@link com.github.auties00.smalt.MediaException}, within a time bound.
 */
@DisplayName("ISO base media robustness")
class Mp4RobustnessTest {
    static final Set<String> CONTAINERS = Set.of("moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex", "moof", "traf",
            "udta", "meta", "ilst", "stsd", "avc1", "hvc1", "mp4a", "Opus", "alac", "fLaC", "wave", "samr", "s263");

    static List<byte[]> inputs() {
        var inputs = new ArrayList<byte[]>();
        inputs.add(Fixtures.read(Mp4Fixtures.h264Aac(true, List.of(), "mp4")));
        inputs.add(Fixtures.read(Mp4Fixtures.copy(Mp4Fixtures.h264Aac(true, List.of(), "mp4"), "mp4", List.of(),
                List.of("-movflags", "frag_keyframe+empty_moov+default_base_moof"))));
        inputs.add(Fixtures.read(Fixtures.ffmpeg("mp4", Mp4Fixtures.audio(48000, 0.5), List.of("-c:a", "libopus"))));
        inputs.add(Fixtures.read(Fixtures.ffmpeg("mov", Mp4Fixtures.audio(22050, 0.3), List.of("-c:a", "pcm_s24le", "-ac", "2"))));
        inputs.add(Fixtures.read(Fixtures.ffmpeg("3gp", Mp4Fixtures.both(176, 144, 0.5, 8000),
                List.of("-c:v", "h263", "-c:a", "libopencore_amrnb", "-ar", "8000", "-ac", "1"))));
        inputs.add(Fixtures.read(Mp4Fixtures.copy(Mp4Fixtures.h264Aac(false, List.of(), "mp4"), "mp4", List.of(),
                List.of("-metadata", "title=robust", "-movflags", "use_metadata_tags+faststart"))));
        return inputs;
    }

    /** Returns the {offset, length} of every box size field in a file. */
    static List<int[]> sizeFields(byte[] data) {
        var fields = new ArrayList<int[]>();
        walk(data, 0, data.length, fields, 0);
        return fields;
    }

    static void walk(byte[] data, int start, int end, List<int[]> fields, int depth) {
        var position = start;
        while (position + 8 <= end && depth < 12) {
            var size = ((long) (data[position] & 0xFF) << 24) | ((data[position + 1] & 0xFF) << 16)
                    | ((data[position + 2] & 0xFF) << 8) | (data[position + 3] & 0xFF);
            var type = new String(data, position + 4, 4, StandardCharsets.ISO_8859_1);
            if (size < 8 || position + size > end) {
                return;
            }
            fields.add(new int[]{position, 4});
            if (CONTAINERS.contains(type)) {
                var header = switch (type) {
                    case "meta" -> 12;
                    case "stsd" -> 16;
                    case "avc1", "hvc1", "s263" -> 8 + 78;
                    case "mp4a", "Opus", "alac", "fLaC", "samr" -> 8 + 28;
                    default -> 8;
                };
                walk(data, position + header, (int) (position + size), fields, depth + 1);
            }
            position += (int) size;
        }
    }

    @Test
    @DisplayName("demuxes and remuxes mutated files without hanging or leaking unchecked exceptions")
    void mutations() {
        var seed = 0x5EED_0001L;
        var total = new java.util.TreeMap<String, Integer>();
        for (var input : inputs()) {
            var variants = Mutations.variants(input, sizeFields(input), seed++, 70, false);
            var outcomes = Mutations.run(variants, Duration.ofSeconds(20), source -> {
                try (var demuxer = new Mp4Demuxer(source)) {
                    demuxer.metadata();
                    while (demuxer.read() != null) {
                        // drain every packet
                    }
                }
                Mp4Remuxer.remux(source, MediaSink.of(new MemoryChannel()), RemuxOptions.video());
            });
            outcomes.forEach((key, value) -> total.merge(key, value, Integer::sum));
        }
        System.out.println("MP4 mutation outcomes: " + total);
        assertTrue(total.values().stream().mapToInt(Integer::intValue).sum() >= 400);
    }
}
