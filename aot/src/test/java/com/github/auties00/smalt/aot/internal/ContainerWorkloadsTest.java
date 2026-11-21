package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.Subtitles;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the PCM and SUP files the container workloads write are files of their formats that carry the samples
 * and subtitles they were written with, which also checks the PGS writer of the workloads against the SUP demuxer and
 * the bitmap subtitle decoder.
 */
@DisplayName("files of the container training workloads")
class ContainerWorkloadsTest {
    @ParameterizedTest(name = "{0}")
    @EnumSource(value = ContainerFormat.class, names = {"W64", "AIFF", "AU", "CAF"})
    @DisplayName("a PCM file is detected as its format and demuxes to the samples it was written with")
    void pcm(ContainerFormat format) {
        var bigEndian = format != ContainerFormat.W64;
        var tracks = ContainerWorkloads.pcm(bigEndian ? Codec.PCM_S16BE : Codec.PCM_S16LE, bigEndian);
        var file = ContainerWorkloads.write(format, tracks);
        assertEquals(format, Containers.detect(MediaSource.of(file)).orElseThrow());
        var written = new ByteArrayOutputStream();
        tracks.packets().forEach(packet -> written.writeBytes(packet.data()));
        var read = ContainerWorkloads.read(file);
        assertEquals(1, read.tracks().size());
        var demuxed = new ByteArrayOutputStream();
        read.packets().forEach(packet -> demuxed.writeBytes(packet.data()));
        var expected = written.toByteArray();
        var actual = demuxed.toByteArray();
        if (read.tracks().getFirst().codec() != tracks.tracks().getFirst().codec()) {
            // a format of the other byte order reads the samples back swapped
            for (var index = 0; index + 1 < actual.length; index += 2) {
                var swap = actual[index];
                actual[index] = actual[index + 1];
                actual[index + 1] = swap;
            }
        }
        assertArrayEquals(expected, actual);
    }

    @Test
    @DisplayName("the PGS subtitles of the SUP file read back as cues of their bitmaps' alpha and placement")
    void pgs() {
        var tracks = new ContainerWorkloads.Tracks(List.of(SyntheticPgs.track()), SyntheticPgs.displaySets(5, 320, 48));
        var file = ContainerWorkloads.write(ContainerFormat.SUP, tracks);
        assertEquals(ContainerFormat.SUP, Containers.detect(MediaSource.of(file)).orElseThrow());
        var cues = Subtitles.readBitmaps(MediaSource.of(file));
        assertEquals(5, cues.size());
        for (var index = 0; index < cues.size(); index++) {
            var cue = cues.get(index);
            assertEquals(index * 2_000_000L, cue.startMicros());
            assertEquals(index * 2_000_000L + 1_500_000L, cue.endMicros());
            assertEquals(1920, cue.canvasWidth());
            assertEquals(1, cue.regions().size());
            var region = cue.regions().getFirst();
            assertEquals((1920 - 320) / 2, region.x());
            assertEquals(1080 - 48 - 60, region.y());
            var picture = region.picture();
            assertEquals(320, picture.width());
            assertEquals(48, picture.height());
            for (var y = 0; y < 48; y++) {
                for (var x = 0; x < 320; x++) {
                    var border = x < 4 || y < 2 || x >= 316 || y >= 46;
                    var block = Math.abs(x - (index * 37) % 320) < 12;
                    // the palette's alphas: transparent border, opaque white and black, translucent yellow
                    var alpha = border ? 0 : block || (y / 6) % 2 == 0 ? 255 : 192;
                    assertEquals(alpha, picture.pixels()[y * picture.stride() + x * 4 + 3] & 0xFF,
                            "cue " + index + " at " + x + "," + y);
                }
            }
        }
    }
}
