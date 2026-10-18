package com.github.auties00.smalt.container;

import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shared helpers of the muxer tests: demuxing bytes, muxing a demuxed input into memory, a
 * stream or a file, and validating the output with ffmpeg.
 */
public final class MuxSupport {
    private static final Path DIRECTORY = Path.of("target", "mux-output");

    private MuxSupport() {
        throw new AssertionError();
    }

    /** Demuxes every packet of an in-memory file. */
    public static ContainerProbe.Demuxed demux(byte[] file) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            return ContainerProbe.demuxAll(demuxer);
        }
    }

    /** Muxes the tracks and packets, in file order, into a sink. */
    public static void mux(List<Track> tracks, List<Packet> packets, Muxer muxer) {
        try (muxer) {
            tracks.forEach(muxer::addTrack);
            packets.forEach(muxer::writePacket);
            muxer.finish();
        }
    }

    /** Muxes into memory. */
    public static byte[] toMemory(List<Track> tracks, List<Packet> packets, Function<MediaSink, Muxer> factory) {
        var sink = MediaSink.memory();
        mux(tracks, packets, factory.apply(sink));
        return sink.toByteArray();
    }

    /** Muxes into a non-seekable stream. */
    public static byte[] toStream(List<Track> tracks, List<Packet> packets, Function<MediaSink, Muxer> factory) {
        var stream = new ByteArrayOutputStream();
        try (var sink = MediaSink.of(stream)) {
            mux(tracks, packets, factory.apply(sink));
        }
        return stream.toByteArray();
    }

    /** Writes bytes to a file under {@code target/mux-output} for the external tools. */
    public static Path save(String name, byte[] data) {
        try {
            Files.createDirectories(DIRECTORY);
            var file = DIRECTORY.resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Asserts that ffmpeg decodes every stream of the output without an error. */
    public static void assertFfmpegClean(String name, byte[] data) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        Oracle.assertDecodesCleanly(save(name, data));
    }

    /**
     * Asserts that the packets of each track of {@code actual} carry the same payloads and key
     * frame flags, in the same order, as the matching track of {@code expected}.
     */
    public static void assertSamePayloads(ContainerProbe.Demuxed expected, ContainerProbe.Demuxed actual,
                                          Map<Integer, Integer> trackMap) {
        for (var entry : trackMap.entrySet()) {
            var source = expected.packets().get(entry.getKey());
            var target = actual.packets().get(entry.getValue());
            assertEquals(source.size(), target.size(), "packet count of track " + entry.getKey());
            for (var index = 0; index < source.size(); index++) {
                assertArrayEquals(source.get(index).data(), target.get(index).data(), "payload " + index + " of track " + entry.getKey());
            }
        }
    }

    /**
     * Asserts that two lists of presentation timestamps, converted to microseconds, agree within
     * a tolerance after removing a constant offset.
     */
    public static void assertTimesClose(List<Long> expectedMicros, List<Long> actualMicros, long toleranceMicros) {
        assertEquals(expectedMicros.size(), actualMicros.size());
        if (expectedMicros.isEmpty()) {
            return;
        }
        var offset = actualMicros.getFirst() - expectedMicros.getFirst();
        for (var index = 0; index < expectedMicros.size(); index++) {
            var difference = Math.abs(actualMicros.get(index) - offset - expectedMicros.get(index));
            assertTrue(difference <= toleranceMicros, "timestamp " + index + " differs by " + difference + " us");
        }
    }

    /** Returns the presentation timestamps of a track's packets in microseconds. */
    public static List<Long> ptsMicros(ContainerProbe.Demuxed demuxed, Track track) {
        return demuxed.packetsOf(track).stream()
                .map(packet -> track.timeBase().toMicros(packet.pts() != Packet.NO_TIMESTAMP ? packet.pts() : packet.dts()))
                .toList();
    }
}
