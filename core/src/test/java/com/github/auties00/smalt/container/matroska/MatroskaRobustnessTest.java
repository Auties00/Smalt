package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.container.MemoryChannel;
import com.github.auties00.smalt.container.Mutations;
import com.github.auties00.smalt.container.mp4.Mp4Remuxer;
import com.github.auties00.smalt.container.mp4.RemuxOptions;
import com.github.auties00.smalt.internal.tags.EbmlIds;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Fixtures;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.github.auties00.smalt.container.matroska.EbmlWriter.binary;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.block;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.file;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.master;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.uint;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mutation tests of {@link MatroskaDemuxer} and of remuxing Matroska: several hundred corrupted
 * variants of valid files must each demux successfully or fail with a
 * {@link com.github.auties00.smalt.MediaException}, within a time bound.
 */
@DisplayName("Matroska robustness")
class MatroskaRobustnessTest {
    static final Set<Long> MASTERS = Set.of((long) EbmlIds.SEGMENT, (long) EbmlIds.CLUSTER, (long) EbmlIds.TRACKS,
            (long) EbmlIds.TRACK_ENTRY, (long) EbmlIds.INFO, (long) EbmlIds.BLOCK_GROUP, (long) EbmlIds.TAGS,
            (long) EbmlIds.TAG, (long) EbmlIds.SIMPLE_TAG, (long) EbmlIds.SEEK_HEAD, (long) EbmlIds.SEEK,
            (long) EbmlIds.VIDEO, (long) EbmlIds.AUDIO, (long) EbmlIds.CONTENT_ENCODINGS, (long) EbmlIds.CONTENT_ENCODING,
            (long) EbmlIds.CONTENT_COMPRESSION, (long) EbmlIds.EBML);

    static List<byte[]> inputs() {
        var inputs = new ArrayList<byte[]>();
        inputs.add(Fixtures.read(MatroskaDemuxerTest.h264Aac("mkv", true)));
        inputs.add(Fixtures.read(Fixtures.ffmpeg("webm", MatroskaDemuxerTest.both(0.6),
                List.of("-c:v", "libvpx-vp9", "-b:v", "150k", "-deadline", "realtime", "-c:a", "libopus"))));
        inputs.add(Fixtures.read(Fixtures.ffmpeg("mkv", MatroskaDemuxerTest.audio(44100, 0.5), List.of("-c:a", "flac"))));
        inputs.add(Fixtures.read(Fixtures.ffmpeg("mkv", MatroskaDemuxerTest.both(0.5), List.of("-c:v", "mpeg4", "-c:a", "libmp3lame",
                "-metadata", "title=robust"))));
        var frames = MatroskaDemuxerTest.frames(320, 600, 17, 255);
        inputs.add(file("matroska", true, master(EbmlIds.INFO, uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000)),
                MatroskaDemuxerTest.pcmTrack(1, 20_000_000, master(EbmlIds.CONTENT_ENCODINGS, master(EbmlIds.CONTENT_ENCODING,
                        master(EbmlIds.CONTENT_COMPRESSION, uint(EbmlIds.CONTENT_COMP_ALGO, 3),
                                binary(EbmlIds.CONTENT_COMP_SETTINGS, new byte[]{1, 2}))))),
                new EbmlWriter.Master(EbmlIds.CLUSTER, true, List.of(uint(EbmlIds.TIMESTAMP, 0),
                        binary(EbmlIds.SIMPLE_BLOCK, block(1, 0, 0x82, EbmlWriter.xiphLace(frames))),
                        binary(EbmlIds.SIMPLE_BLOCK, block(1, 80, 0x86, EbmlWriter.ebmlLace(frames))),
                        master(EbmlIds.BLOCK_GROUP, binary(EbmlIds.BLOCK, block(1, 160, 0x04, EbmlWriter.fixedLace(
                                MatroskaDemuxerTest.frames(40, 40, 40)))), uint(EbmlIds.BLOCK_DURATION, 60))))));
        return inputs;
    }

    /** Returns the {offset, length} of every element size field in a file. */
    static List<int[]> sizeFields(byte[] data) {
        var fields = new ArrayList<int[]>();
        walk(data, 0, data.length, fields, 0);
        return fields;
    }

    static void walk(byte[] data, int start, int end, List<int[]> fields, int depth) {
        var position = start;
        while (position < end && depth < 10) {
            var first = data[position] & 0xFF;
            var idLength = Integer.numberOfLeadingZeros(first) - 23;
            if (first == 0 || idLength > 4 || position + idLength >= end) {
                return;
            }
            var id = 0L;
            for (var index = 0; index < idLength; index++) {
                id = (id << 8) | (data[position + index] & 0xFF);
            }
            var sizeStart = position + idLength;
            var lead = data[sizeStart] & 0xFF;
            if (lead == 0) {
                return;
            }
            var sizeLength = Integer.numberOfLeadingZeros(lead) - 23;
            if (sizeStart + sizeLength > end) {
                return;
            }
            var size = (long) (lead & (0xFF >> sizeLength));
            var unknown = size == (0xFF >> sizeLength);
            for (var index = 1; index < sizeLength; index++) {
                var next = data[sizeStart + index] & 0xFF;
                unknown &= next == 0xFF;
                size = (size << 8) | next;
            }
            fields.add(new int[]{sizeStart, sizeLength});
            var dataStart = sizeStart + sizeLength;
            var dataEnd = unknown ? end : dataStart + size;
            if (dataEnd > end || dataEnd < dataStart) {
                return;
            }
            if (MASTERS.contains(id)) {
                walk(data, dataStart, (int) dataEnd, fields, depth + 1);
            }
            position = (int) dataEnd;
        }
    }

    @Test
    @DisplayName("demuxes and remuxes mutated files without hanging or leaking unchecked exceptions")
    void mutations() {
        var seed = 0x5EED_1001L;
        var total = new java.util.TreeMap<String, Integer>();
        for (var input : inputs()) {
            var variants = Mutations.variants(input, sizeFields(input), seed++, 80, true);
            var outcomes = Mutations.run(variants, Duration.ofSeconds(20), source -> {
                try (var demuxer = new MatroskaDemuxer(source)) {
                    demuxer.metadata();
                    while (demuxer.read() != null) {
                        // drain every packet
                    }
                }
                Mp4Remuxer.remux(source, MediaSink.of(new MemoryChannel()), RemuxOptions.video());
            });
            outcomes.forEach((key, value) -> total.merge(key, value, Integer::sum));
        }
        System.out.println("Matroska mutation outcomes: " + total);
        assertTrue(total.values().stream().mapToInt(Integer::intValue).sum() >= 400);
    }
}
