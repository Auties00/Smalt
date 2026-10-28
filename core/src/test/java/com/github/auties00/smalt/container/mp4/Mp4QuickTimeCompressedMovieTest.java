package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.DemuxAssertions;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.StandardTag;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.testkit.container.DemuxerAsserts;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the QuickTime compressed movie ({@code moov} holding a {@code cmov} atom: {@code dcom} {@code zlib} and
 * {@code cmvd}, the movie atom zlib-compressed, its offsets counting in the file as it is): the compressed variants
 * of the committed samples, with the movie after the media data or padded in place before it, demux to the golden
 * dumps of the originals under every I/O behavior and as ffprobe reads them; their tags are read and written;
 * damaged, nested and oversized compressed movies are rejected.
 */
@DisplayName("QuickTime compressed movies")
class Mp4QuickTimeCompressedMovieTest {
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

    /** Returns the file with its movie compressed, padded with a free box when the media data follows it. */
    static byte[] compressed(byte[] file) {
        var moov = MovieBoxes.movie(file);
        var cmov = MovieBoxes.cmov(moov, "zlib");
        var last = MovieBoxes.topLevel(file).getLast().type().equals("moov");
        return MovieBoxes.replace(file, "moov", last ? cmov : MovieBoxes.padded(cmov, moov.length));
    }

    private static ContainerProbe.Demuxed demux(byte[] file) {
        try (var source = MediaSource.of(file); var demuxer = new Mp4Demuxer(source)) {
            return ContainerProbe.demuxAll(demuxer);
        }
    }

    private static long offset(Track track) {
        if (!(track instanceof AudioTrack audio)) {
            return 0;
        }
        var timescale = audio.timeBase().denominator();
        var rate = audio.sampleRate() > 0 ? audio.sampleRate() : timescale;
        return Math.round(audio.skipSamples() * (double) timescale / rate);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mp4/quicktime.mov", "mp4/prores.mov", "mp4/dnxhr.mov", "mp4/moov-last.mp4",
            "mp4/faststart.mp4"})
    @DisplayName("a compressed movie dumps like its original under every I/O behavior")
    void behaviors(String name) {
        var file = compressed(sample(name));
        assertTrue(MovieBoxes.movie(file).length < MovieBoxes.movie(sample(name)).length, "the movie is compressed");
        DemuxerAsserts.of(name, file).run();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"mp4/quicktime.mov", "mp4/faststart.mp4"})
    @DisplayName("a compressed movie demuxes as ffprobe reads it")
    void ffprobe(String name) throws IOException {
        var file = compressed(sample(name));
        var path = work.resolve(Path.of(name).getFileName());
        Files.write(path, file);
        var ours = demux(file);
        assertEquals(demux(sample(name)).order().size(), ours.order().size());
        DemuxAssertions.assertPacketsMatch(ours, ContainerProbe.probe(path), Mp4QuickTimeCompressedMovieTest::offset,
                true, 0, true);
    }

    @Test
    @DisplayName("tags of a compressed movie are read, and written with the movie stored uncompressed")
    void tags() {
        var original = sample("mp4/quicktime.mov");
        var tagged = TagEditor.write(original, MediaTags.builder().set(StandardTag.TITLE, "compressed").build(),
                TagWriteOptions.builder().build());
        var file = compressed(tagged);
        assertEquals(TagEditor.read(MediaSource.of(tagged)), TagEditor.read(MediaSource.of(file)));
        var written = TagEditor.write(file, MediaTags.builder().set(StandardTag.TITLE, "rewritten").build(),
                TagWriteOptions.builder().build());
        assertEquals("rewritten", TagEditor.read(MediaSource.of(written)).get(StandardTag.TITLE).orElseThrow());
        assertFalse(new String(MovieBoxes.movie(written), java.nio.charset.StandardCharsets.ISO_8859_1).contains("cmov"),
                "the rewritten movie is stored uncompressed");
        var expected = demux(original);
        var actual = demux(written);
        assertEquals(expected.order().size(), actual.order().size());
        for (var i = 0; i < expected.order().size(); i++) {
            assertArrayEquals(expected.order().get(i).data(), actual.order().get(i).data(), "packet " + i);
        }
        var fastStart = compressed(sample("mp4/faststart.mp4"));
        var grown = TagEditor.write(fastStart, MediaTags.builder().set(StandardTag.TITLE, "x".repeat(2000)).build(),
                TagWriteOptions.builder().build());
        var before = demux(sample("mp4/faststart.mp4"));
        var after = demux(grown);
        for (var i = 0; i < before.order().size(); i++) {
            assertArrayEquals(before.order().get(i).data(), after.order().get(i).data(), "moved packet " + i);
        }
    }

    @Test
    @DisplayName("an algorithm other than zlib is unsupported")
    void algorithm() {
        var file = sample("mp4/quicktime.mov");
        var other = MovieBoxes.replace(file, "moov", MovieBoxes.cmov(MovieBoxes.movie(file), "adec"));
        var failure = assertThrows(MediaException.Unsupported.class, () -> demux(other));
        assertEquals("quicktime compressed movie", failure.format());
    }

    @Test
    @DisplayName("a damaged, short, nested, incomplete or oversized compressed movie is rejected")
    void damaged() {
        var file = sample("mp4/quicktime.mov");
        var moov = MovieBoxes.movie(file);
        var cmov = MovieBoxes.cmov(moov, "zlib");
        // the cmvd payload starts after moov (8), cmov (8), dcom (12) and the cmvd header (8)
        var data = 8 + 8 + 12 + 8;
        var corrupt = cmov.clone();
        for (var i = data + 6; i < data + 40; i++) {
            corrupt[i] ^= 0x5A;
        }
        assertThrows(MediaException.Malformed.class, () -> demux(MovieBoxes.replace(file, "moov", corrupt)));
        var shortened = cmov.clone();
        ByteBuffer.wrap(shortened).putInt(data, moov.length + 100);
        var failure = assertThrows(MediaException.Malformed.class, () -> demux(MovieBoxes.replace(file, "moov", shortened)));
        assertTrue(failure.getMessage().contains("cmvd declares"), failure.getMessage());
        var nested = MovieBoxes.cmov(cmov, "zlib");
        assertThrows(MediaException.Malformed.class, () -> demux(MovieBoxes.replace(file, "moov", nested)));
        var noData = MovieBoxes.box("moov", MovieBoxes.box("cmov", MovieBoxes.box("dcom", "zlib".getBytes())));
        assertThrows(MediaException.Malformed.class, () -> demux(MovieBoxes.replace(file, "moov", noData)));
        var notMovie = MovieBoxes.cmov(MovieBoxes.box("free", new byte[32]), "zlib");
        assertThrows(MediaException.Malformed.class, () -> demux(MovieBoxes.replace(file, "moov", notMovie)));
        var limits = MediaLimits.builder().maxBytes(moov.length - 1).build();
        var options = DemuxerOptions.builder().limits(limits).build();
        var compressedFile = MovieBoxes.replace(file, "moov", cmov);
        assertThrows(MediaException.LimitExceeded.class, () -> new Mp4Demuxer(MediaSource.of(compressedFile), options));
        for (var cut = data; cut < cmov.length; cut += Math.max(1, (cmov.length - data) / 23)) {
            var truncated = MovieBoxes.box("moov", MovieBoxes.box("cmov",
                    Arrays.copyOfRange(cmov, 16, cut)));
            assertThrows(MediaException.class, () -> demux(MovieBoxes.replace(file, "moov", truncated)), "cut " + cut);
        }
    }
}
