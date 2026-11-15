package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compares the presented packets of multi-segment edit lists with ffprobe's on an H.264 clip that
 * ffmpeg encodes with B-frames and a fixed key frame interval: the samples each edit presents,
 * identified by their CRC32, and their presentation timestamps. The edits start at key frames and
 * play at normal speed, the cases ffmpeg's demuxer handles exactly; dwell edits, empty edits after
 * a media edit and edits starting between key frames are covered by {@link Mp4EditListTest}.
 */
@DisplayName("MP4 multi-segment edit lists against ffprobe")
class Mp4EditListOracleTest {
    @TempDir
    static Path directory;

    static byte[] clip;

    @BeforeAll
    static void encode() throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var output = directory.resolve("clip.mp4");
        // moov after mdat, so that rewriting the edit list moves no sample
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-f", "lavfi", "-i",
                "testsrc=size=64x48:rate=10", "-t", "3", "-c:v", "libx264", "-g", "5", "-keyint_min", "5",
                "-sc_threshold", "0", "-bf", "2", "-threads", "1", "-pix_fmt", "yuv420p", "-video_track_timescale", "1000",
                "-y", output.toString()));
        clip = Files.readAllBytes(output);
    }

    /** Replaces the edit list of the first track of a file whose moov is its last box. */
    static byte[] withEdits(byte[] file, long... triples) {
        var moovStart = -1;
        for (var position = 0; position + 8 <= file.length; ) {
            var size = readU32(file, position);
            if (file[position + 4] == 'm' && file[position + 5] == 'o' && file[position + 6] == 'o' && file[position + 7] == 'v') {
                moovStart = position;
            }
            position += (int) size;
        }
        var moov = new ByteArrayOutputStream();
        var firstTrack = true;
        for (var position = moovStart + 8; position < file.length; ) {
            var size = (int) readU32(file, position);
            var type = new String(file, position + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            if (type.equals("trak") && firstTrack) {
                firstTrack = false;
                var trak = new ByteArrayOutputStream();
                for (var child = position + 8; child < position + size; ) {
                    var childSize = (int) readU32(file, child);
                    var childType = new String(file, child + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
                    if (!childType.equals("edts")) {
                        trak.write(file, child, childSize);
                    }
                    if (childType.equals("tkhd")) {
                        trak.writeBytes(Mp4EditListTest.elst(triples));
                    }
                    child += childSize;
                }
                moov.writeBytes(Mp4Builder.box("trak", trak.toByteArray()));
            } else {
                moov.write(file, position, size);
            }
            position += size;
        }
        return Mp4Builder.concat(java.util.Arrays.copyOf(file, moovStart), Mp4Builder.box("moov", moov.toByteArray()));
    }

    static long readU32(byte[] data, int position) {
        return (data[position] & 0xFFL) << 24 | (data[position + 1] & 0xFF) << 16 | (data[position + 2] & 0xFF) << 8
                | data[position + 3] & 0xFF;
    }

    @ParameterizedTest
    @ValueSource(strings = {"500:700:1,1000:200:1,500:2200:1", "1000:1200:1,1500:200:1", "500:200:1,500:200:1,500:1700:1",
            "300:-1:1,1000:700:1,500:1200:1"})
    @DisplayName("presents the samples and timestamps ffprobe reports")
    void matchesFfprobe(String edits) throws IOException {
        var values = new ArrayList<Long>();
        for (var edit : edits.split(",")) {
            for (var field : edit.split(":")) {
                values.add(Long.parseLong(field));
            }
        }
        var data = withEdits(clip, values.stream().mapToLong(Long::longValue).toArray());
        var file = directory.resolve("edited-" + edits.replace(':', '_').replace(',', '-') + ".mp4");
        Files.write(file, data);
        var expected = new ArrayList<String>();
        for (var packet : ContainerProbe.probe(file).packetsOf(0)) {
            if (!packet.discard()) {
                expected.add(packet.hash() + "@" + packet.pts());
            }
        }
        var actual = new ArrayList<String>();
        try (var source = MediaSource.of(data); var demuxer = new Mp4Demuxer(source)) {
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                if (!packet.flags().contains(PacketFlag.DISCARD)) {
                    actual.add(ContainerProbe.crc32(packet.data()) + "@" + packet.pts());
                }
            }
        }
        assertTrue(expected.size() > 10, "ffprobe reports " + expected.size() + " presented packets");
        assertEquals(expected.stream().sorted().toList(), actual.stream().sorted().toList());
    }
}
