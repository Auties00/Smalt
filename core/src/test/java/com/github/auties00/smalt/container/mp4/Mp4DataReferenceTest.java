package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.DemuxAssertions;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.io.DataReference;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.io.IoVariant;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers tracks whose samples are in other files, named by the data reference box ({@code dref}) of each track:
 * reference movies made from the committed samples, whose tracks point with {@code url } or {@code alis} entries
 * at a copy of the original file next to them (so their samples lie at the same offsets there), demux to the
 * packets of the original, whole or track by track, under the I/O behaviors, and as ffprobe reads them through its
 * own alias resolution; references nobody resolves are unsupported, and the demuxer closes what the resolver opens.
 */
@DisplayName("MP4 external data references")
class Mp4DataReferenceTest {
    private static final Path SAMPLES = Path.of("src", "test", "resources", "samples");

    @TempDir
    Path work;

    private static byte[] sample(String name) {
        try {
            return Files.readAllBytes(SAMPLES.resolve(name));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static ContainerProbe.Demuxed demux(MediaSource source, DataReferenceResolver resolver) {
        try (var demuxer = new Mp4Demuxer(source, DemuxerOptions.builder().dataReferences(resolver).build())) {
            return ContainerProbe.demuxAll(demuxer);
        }
    }

    private static ContainerProbe.Demuxed demux(byte[] file) {
        try (var source = MediaSource.of(file)) {
            return demux(source, DataReferenceResolver.none());
        }
    }

    /** The timestamp offset of ours against ffprobe's: the audio skip ffprobe subtracts. */
    private static long offset(Track track) {
        if (!(track instanceof AudioTrack audio)) {
            return 0;
        }
        var timescale = audio.timeBase().denominator();
        var rate = audio.sampleRate() > 0 ? audio.sampleRate() : timescale;
        return Math.round(audio.skipSamples() * (double) timescale / rate);
    }

    private static void assertSamePackets(ContainerProbe.Demuxed expected, ContainerProbe.Demuxed actual) {
        assertEquals(expected.tracks(), actual.tracks());
        assertEquals(expected.order().size(), actual.order().size());
        assertFalse(expected.order().isEmpty());
        for (var i = 0; i < expected.order().size(); i++) {
            var want = expected.order().get(i);
            var got = actual.order().get(i);
            assertEquals(want.trackId(), got.trackId(), "packet " + i);
            assertEquals(want.dts(), got.dts(), "packet " + i);
            assertEquals(want.pts(), got.pts(), "packet " + i);
            assertEquals(want.keyframe(), got.keyframe(), "packet " + i);
            assertArrayEquals(want.data(), got.data(), "packet " + i);
        }
    }

    /** Checks that each track has the packets of the original, wherever the demuxer interleaves them. */
    private static void assertSameTracks(ContainerProbe.Demuxed expected, ContainerProbe.Demuxed actual, String what) {
        assertEquals(expected.tracks(), actual.tracks(), what);
        assertEquals(expected.order().size(), actual.order().size(), what);
        for (var track : expected.tracks()) {
            var want = expected.packetsOf(track);
            var got = actual.packetsOf(track);
            assertEquals(want.size(), got.size(), what);
            for (var i = 0; i < want.size(); i++) {
                var where = what + " track " + track.id() + " packet " + i;
                assertEquals(want.get(i).dts(), got.get(i).dts(), where);
                assertEquals(want.get(i).pts(), got.get(i).pts(), where);
                assertEquals(want.get(i).keyframe(), got.get(i).keyframe(), where);
                assertArrayEquals(want.get(i).data(), got.get(i).data(), where);
            }
        }
    }

    /** Checks that packets of tracks in different files come in decoding order. */
    private static void assertDecodingOrder(ContainerProbe.Demuxed actual, String what) {
        var previous = Double.NEGATIVE_INFINITY;
        for (var packet : actual.order()) {
            var track = actual.tracks().stream().filter(candidate -> candidate.id() == packet.trackId()).findFirst()
                    .orElseThrow();
            var time = packet.dts() * track.timeBase().toDouble();
            assertTrue(time >= previous - 1e-9, what + ": packet at " + time + " s after one at " + previous + " s");
            previous = time;
        }
    }

    /** Writes the original next to where the reference movie will be and returns the movie, without its mdat. */
    private byte[] referenceMovie(byte[] original, String siblingName, byte[]... entries) throws IOException {
        Files.write(work.resolve(siblingName), original);
        var movie = MovieBoxes.editReferences(MovieBoxes.movie(original), (track, payload) -> MovieBoxes.dref(entries));
        var file = MovieBoxes.replace(original, "moov", movie);
        return MovieBoxes.replace(file, "mdat", MovieBoxes.box("free", new byte[0]));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mp4/moov-last.mp4", "mp4/quicktime.mov", "mp4/faststart.mp4", "mp4/prores.mov"})
    @DisplayName("a movie whose tracks point at another file demuxes like that file, under every I/O behavior")
    void url(String name) throws IOException {
        var original = sample(name);
        var movie = referenceMovie(original, "media.bin", MovieBoxes.url("media.bin"));
        var expected = demux(original);
        var resolver = DataReferenceResolver.directory(work);
        for (var variant : IoVariant.values()) {
            try (var source = variant.open(movie)) {
                assertSamePackets(expected, demux(source, resolver));
            }
        }
        var failure = assertThrows(MediaException.Unsupported.class, () -> demux(movie));
        assertEquals("mp4 external data reference", failure.format());
        assertTrue(failure.getMessage().contains("media.bin"), failure.getMessage());
    }

    @Test
    @DisplayName("an alias entry resolves to its file name, and ffprobe reads the same packets through it")
    void alias() throws IOException {
        var original = sample("mp4/quicktime.mov");
        var movie = referenceMovie(original, "media.mov", MovieBoxes.alias("Disk", "media.mov"));
        var path = work.resolve("reference.mov");
        Files.write(path, movie);
        ContainerProbe.Demuxed ours;
        try (var source = MediaSource.of(path)) {
            ours = demux(source, DataReferenceResolver.directory(work));
        }
        assertSamePackets(demux(original), ours);
        // ffmpeg finds an alias's file next to the movie by cutting the movie's path at its last '/'
        var probe = ContainerProbe.probe(path.toAbsolutePath().toString().replace('\\', '/'),
                List.of("-enable_drefs", "1"));
        DemuxAssertions.assertPacketsMatch(ours, probe, Mp4DataReferenceTest::offset, true, 0, true);
    }

    @Test
    @DisplayName("a track in another file and one in the file demux like the original, in decoding order")
    void mixed() throws IOException {
        var original = sample("mp4/moov-last.mp4");
        Files.write(work.resolve("video.mp4"), original);
        var movie = MovieBoxes.editReferences(MovieBoxes.movie(original), (track, payload) -> track == 0
                ? MovieBoxes.dref(MovieBoxes.url("video.mp4")) : MovieBoxes.dref(MovieBoxes.self()));
        var file = MovieBoxes.replace(original, "moov", movie);
        // the samples of the first track are blanked in the movie itself, so only the referenced file holds them
        var parser = new MovieParser(MediaLimits.DEFAULT);
        var moov = MovieBoxes.movie(original);
        parser.parse(com.github.auties00.smalt.internal.heif.BoxCursor.of(Arrays.copyOfRange(moov, 8, moov.length),
                BoxTypes.MOOV));
        var blanked = parser.tracks.getFirst().samples;
        for (var i = 0; i < blanked.count; i++) {
            Arrays.fill(file, (int) blanked.offsets[i], (int) blanked.offsets[i] + blanked.sizes[i], (byte) 0);
        }
        var expected = demux(original);
        var resolver = DataReferenceResolver.directory(work);
        for (var variant : IoVariant.values()) {
            try (var source = variant.open(file)) {
                var ours = demux(source, resolver);
                assertSameTracks(expected, ours, variant.name());
                assertDecodingOrder(ours, variant.name());
            }
        }
        DataReferenceResolver itself = reference -> Optional.of(MediaSource.of(file));
        try (var source = MediaSource.of(file)) {
            var blankedPackets = demux(source, itself);
            assertThrows(AssertionError.class, () -> assertSameTracks(expected, blankedPackets, "itself"),
                    "the movie itself holds blanked samples of the first track");
        }
    }

    @Test
    @DisplayName("tracks in two files are read in decoding order, whatever their offsets in those files")
    void separateFiles() throws IOException {
        var original = sample("mp4/moov-last.mp4");
        // the second track's file starts with padding, so each of its offsets lies past every offset of the first
        var padding = original.length;
        var padded = new byte[padding + original.length];
        System.arraycopy(original, 0, padded, padding, original.length);
        Files.write(work.resolve("first.mp4"), original);
        Files.write(work.resolve("second.mp4"), padded);
        var shifted = MovieBoxes.shiftChunkOffsets(MovieBoxes.movie(original), 1, padding);
        var movie = MovieBoxes.editReferences(shifted, (track, payload) -> MovieBoxes.dref(
                MovieBoxes.url(track == 0 ? "first.mp4" : "second.mp4")));
        var file = MovieBoxes.replace(MovieBoxes.replace(original, "moov", movie), "mdat", MovieBoxes.box("free",
                new byte[0]));
        var expected = demux(original);
        var resolver = DataReferenceResolver.directory(work);
        for (var variant : IoVariant.values()) {
            ContainerProbe.Demuxed ours;
            try (var source = variant.open(file)) {
                ours = demux(source, resolver);
            }
            assertSameTracks(expected, ours, variant.name());
            assertDecodingOrder(ours, variant.name());
        }
    }

    /** A resolver over in-memory files that counts the sources it opens and those closed. */
    private static final class Counting implements DataReferenceResolver {
        final byte[] file;
        final List<DataReference> asked = new ArrayList<>();
        int opened;
        int closed;

        Counting(byte[] file) {
            this.file = file;
        }

        @Override
        public Optional<MediaSource> resolve(DataReference reference) {
            asked.add(reference);
            if (!reference.fileName().equals("media.bin")) {
                return Optional.empty();
            }
            opened++;
            var inner = MediaSource.of(file);
            return Optional.of(new MediaSource() {
                @Override
                public long size() {
                    return inner.size();
                }

                @Override
                public int read(long position, byte[] buffer, int offset, int length) {
                    return inner.read(position, buffer, offset, length);
                }

                @Override
                public void close() {
                    closed++;
                }
            });
        }
    }

    @Test
    @DisplayName("each reference is resolved once, and its source is closed with the demuxer or a failed construction")
    void ownership() throws IOException {
        var original = sample("mp4/moov-last.mp4");
        var movie = referenceMovie(original, "media.bin", MovieBoxes.url("media.bin"));
        var counting = new Counting(original);
        try (var demuxer = new Mp4Demuxer(MediaSource.of(movie), DemuxerOptions.builder().dataReferences(counting).build())) {
            assertEquals(2, demuxer.tracks().size());
            assertTrue(demuxer.read() != null);
            assertEquals(1, counting.opened, "two tracks naming one file open it once");
            assertEquals(0, counting.closed);
        }
        assertEquals(1, counting.closed);
        // the second track names a file the resolver does not supply: the first track's source is closed
        var broken = MovieBoxes.editReferences(MovieBoxes.movie(original), (track, payload) -> track == 0
                ? MovieBoxes.dref(MovieBoxes.url("media.bin")) : MovieBoxes.dref(MovieBoxes.url("other.bin")));
        var failing = new Counting(original);
        var file = MovieBoxes.replace(original, "moov", broken);
        var failure = assertThrows(MediaException.Unsupported.class, () -> new Mp4Demuxer(MediaSource.of(file),
                DemuxerOptions.builder().dataReferences(failing).build()));
        assertTrue(failure.getMessage().contains("other.bin"), failure.getMessage());
        assertEquals(1, failing.opened);
        assertEquals(1, failing.closed);
    }

    @Test
    @DisplayName("a sample past the end of the referenced file is malformed")
    void truncatedReference() throws IOException {
        var original = sample("mp4/moov-last.mp4");
        var movie = referenceMovie(original, "media.bin", MovieBoxes.url("media.bin"));
        Files.write(work.resolve("media.bin"), Arrays.copyOf(original, original.length / 2));
        try (var source = MediaSource.of(movie);
             var demuxer = new Mp4Demuxer(source, DemuxerOptions.builder()
                     .dataReferences(DataReferenceResolver.directory(work)).build())) {
            var failure = assertThrows(MediaException.Malformed.class, () -> {
                while (demuxer.read() != null) {
                    // read to the first sample past the end
                }
            });
            assertTrue(failure.getMessage().contains("referenced file"), failure.getMessage());
        }
    }

    @Test
    @DisplayName("the demuxers of the registry pass the resolver of their options to the MP4 demuxer")
    void registry() throws IOException {
        var original = sample("mp4/moov-last.mp4");
        var movie = referenceMovie(original, "media.bin", MovieBoxes.url("media.bin"));
        var options = DemuxerOptions.builder().dataReferences(DataReferenceResolver.directory(work)).build();
        try (var source = MediaSource.of(movie); var demuxer = Containers.openDemuxer(source, options)) {
            assertSamePackets(demux(original), ContainerProbe.demuxAll(demuxer));
        }
    }
}
