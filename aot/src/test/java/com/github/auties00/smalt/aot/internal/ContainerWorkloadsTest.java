package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Codec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks that the PCM files the container workloads write are files of their formats that carry the samples they
 * were written with.
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
}
