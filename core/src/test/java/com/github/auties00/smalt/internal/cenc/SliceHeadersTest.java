package com.github.auties00.smalt.internal.cenc;

import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.internal.codec.NalConfigs;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the slice header sizes the parser finds with the syntax elements ffmpeg's {@code trace_headers} bitstream
 * filter prints for x264 and x265 streams coded with the tools that change the slice header: B slices with weighted
 * prediction, several slices per picture, CAVLC, MBAFF, scaling matrices, 4:4:4, reference list modifications, long
 * GOPs with MMCO commands, wavefront entry points, and 10-bit range extensions.
 */
@DisplayName("slice header sizes")
class SliceHeadersTest {
    private static final Pattern FIELD = Pattern.compile("\\]\\s+(\\d+)\\s+(\\S+)\\s+([01]+) = ");

    /**
     * Returns the size in bits of each slice header ffmpeg traces, in stream order.
     */
    private static List<Long> traced(Path file, boolean hevc) {
        var output = Oracle.runMerged(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-nostats", "-v", "info",
                "-i", file.toString(), "-map", "0:v", "-c", "copy", "-bsf:v", "trace_headers", "-f", "null", "-"));
        var result = new ArrayList<Long>();
        var inSlice = false;
        var end = -1L;
        for (var line : output.split("\\R")) {
            if (!line.contains("[trace_headers")) {
                // ffmpeg's other messages (stream mapping, progress) may be printed between the lines of a header
                continue;
            }
            var matcher = FIELD.matcher(line);
            if (matcher.find()) {
                if (inSlice && !matcher.group(2).startsWith("cabac_alignment")) {
                    end = Long.parseLong(matcher.group(1)) + matcher.group(3).length();
                }
                continue;
            }
            if (inSlice) {
                result.add(end);
                inSlice = false;
            }
            if (line.contains(hevc ? "Slice Segment Header" : "Slice Header")) {
                inSlice = true;
                end = -1;
            }
        }
        if (inSlice) {
            result.add(end);
        }
        return result;
    }

    /**
     * Returns the size in bits of each slice header the parser finds, in stream order.
     */
    private static List<Long> parsed(Path file, boolean hevc) {
        var result = new ArrayList<Long>();
        try (var source = MediaSource.of(file); var demuxer = new Mp4Demuxer(source)) {
            var track = demuxer.tracks().stream().filter(candidate -> candidate.codec() == (hevc ? Codec.HEVC : Codec.H264))
                    .findFirst().orElseThrow();
            var config = track.codecConfig().orElseThrow();
            var records = hevc ? NalConfigs.parseHevc(config) : NalConfigs.parseAvc(config);
            var headers = new SliceHeaders(hevc);
            for (var set : records.parameterSets()) {
                headers.parameterSet(set, 0, set.length);
            }
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                if (packet.trackId() != track.id()) {
                    continue;
                }
                NalUnits.forEachLengthPrefixed(packet.data(), records.lengthSize(), (data, offset, length) -> {
                    if (length == 0) {
                        return;
                    }
                    if (headers.isSlice(data[offset])) {
                        result.add(headers.headerBits(data, offset, length));
                    } else {
                        headers.parameterSet(data, offset, length);
                    }
                });
            }
        }
        return result;
    }

    private static void assertSameHeaders(Path file, boolean hevc) {
        var expected = traced(file, hevc);
        var actual = parsed(file, hevc);
        assertTrue(expected.size() > 10, "ffmpeg traced " + expected.size() + " slices");
        assertEquals(expected.size(), actual.size(), "slice count");
        for (var index = 0; index < expected.size(); index++) {
            // HEVC headers end with byte_alignment(), which ffmpeg traces up to its first bit only
            var want = hevc ? (expected.get(index) + 7) / 8 * 8 : expected.get(index);
            assertEquals(want, actual.get(index), "slice header " + index + " in bits");
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "high, B slices, weighted prediction, 3 slices | -profile:v high -bf 3 -refs 4 -x264-params weightp=2:slices=3",
            "baseline CAVLC | -profile:v baseline -x264-params slices=2",
            "MBAFF | -profile:v high -flags +ildct+ilme -x264-params interlaced=1:tff=1",
            "4:4:4 with JVT scaling matrices | -profile:v high444 -pix_fmt yuv444p -x264-params cqm=jvt:bframes=2",
            "B pyramid and a long GOP | -profile:v high -bf 4 -x264-params b-pyramid=normal:keyint=600:ref=5",
            "CAVLC main with open GOP | -profile:v main -coder 0 -x264-params open-gop=1:bframes=2",
    })
    @DisplayName("finds the end of every H.264 slice header ffmpeg traces")
    void avc(String name, String options) {
        var arguments = new ArrayList<>(List.of("-c:v", "libx264"));
        arguments.addAll(List.of(options.trim().split(" ")));
        var file = Fixtures.testVideo("mp4", 320, 192, 3, arguments);
        assertSameHeaders(file, false);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "default with wavefronts | -x265-params log-level=error:wpp=1",
            "slices and weighted prediction | -x265-params log-level=error:slices=3:weightp=1:weightb=1:bframes=4",
            "no SAO and long-term references | -x265-params log-level=error:sao=0:keyint=300:ref=4:b-pyramid=1",
            "10-bit 4:4:4 range extensions | -pix_fmt yuv444p10le -x265-params log-level=error:tskip=1",
    })
    @DisplayName("finds the end of every HEVC slice segment header ffmpeg traces")
    void hevc(String name, String options) {
        var arguments = new ArrayList<>(List.of("-c:v", "libx265", "-tag:v", "hvc1"));
        arguments.addAll(List.of(options.trim().split(" ")));
        var file = Fixtures.testVideo("mp4", 320, 192, 3, arguments);
        assertSameHeaders(file, true);
    }

    @Test
    @DisplayName("takes H.264 non-IDR and IDR slices and every HEVC VCL unit as slices, not data partitions")
    void sliceTypes() {
        var avc = new SliceHeaders(false);
        for (var type = 0; type < 32; type++) {
            var expected = type == 1 || type == 5;
            assertEquals(expected, avc.isSlice((byte) (0x60 | type)), "H.264 type " + type);
        }
        var hevc = new SliceHeaders(true);
        for (var type = 0; type < 64; type++) {
            assertEquals(type < 32, hevc.isSlice((byte) (type << 1)), "HEVC type " + type);
        }
    }

    @Test
    @DisplayName("reports no header for a slice naming an unknown parameter set or cut short")
    void unknown() {
        var headers = new SliceHeaders(false);
        // an IDR slice of PPS 0 before any parameter set
        var slice = new byte[]{0x65, (byte) 0x88, (byte) 0x84, 0x00, 0x33};
        assertEquals(-1, headers.headerSize(slice, 0, slice.length));
        assertEquals(-1, headers.headerSize(slice, 0, 1));
        var hevc = new SliceHeaders(true);
        assertEquals(-1, hevc.headerSize(new byte[]{0x26, 0x01, (byte) 0xAF}, 0, 3));
    }
}
