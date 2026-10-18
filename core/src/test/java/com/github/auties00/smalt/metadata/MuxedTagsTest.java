package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.adts.AdtsMuxer;
import com.github.auties00.smalt.container.aiff.AiffMuxer;
import com.github.auties00.smalt.container.ivf.IvfMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.ogg.OggMuxer;
import com.github.auties00.smalt.container.raw.RawMuxer;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks that every muxer that stores tags writes the tags, pictures and chapters set through
 * {@link Muxer#setTags(MediaTags)} so that {@link TagEditor} and ffprobe read them back, and that
 * the output still decodes cleanly.
 */
@DisplayName("Muxer tags")
class MuxedTagsTest {
    /** A muxer under test, the input it remuxes and the output extension ffprobe needs. */
    record Case(String name, Path input, Function<MediaSink, Muxer> factory, String extension, boolean ffprobeChapters) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Path untagged(String extension, List<String> options) {
        var output = new ArrayList<>(List.of("-map_metadata", "-1"));
        output.addAll(options);
        return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", TagFixtures.AUDIO), output);
    }

    static Stream<Arguments> muxers() {
        return Stream.of(
                new Case("mp4", untagged("m4a", List.of("-c:a", "aac")), Mp4Muxer::new, "m4a", true),
                new Case("fragmented mp4", untagged("m4a", List.of("-c:a", "aac")), FragmentedMp4Muxer::new, "mp4", true),
                new Case("matroska", untagged("mka", List.of("-c:a", "libopus")), MatroskaMuxer::new, "mka", true),
                new Case("ogg", untagged("opus", List.of("-c:a", "libopus")), OggMuxer::new, "opus", true),
                new Case("ogg vorbis", untagged("ogg", List.of("-c:a", "libvorbis")), OggMuxer::new, "ogg", true),
                new Case("wav", untagged("wav", List.of("-c:a", "pcm_s16le")), WavMuxer::new, "wav", false),
                new Case("aiff", untagged("aiff", List.of("-c:a", "pcm_s16be")), AiffMuxer::new, "aiff", false),
                new Case("raw mp3", untagged("mp3", List.of("-c:a", "libmp3lame", "-write_xing", "0")), RawMuxer::new, "mp3", true),
                new Case("raw flac", untagged("flac", List.of("-c:a", "flac")), RawMuxer::new, "flac", false),
                new Case("adts", untagged("aac", List.of("-c:a", "aac", "-f", "adts")), AdtsMuxer::new, "aac", false)
        ).map(Arguments::of);
    }

    static byte[] remux(Path input, Function<MediaSink, Muxer> factory, MediaTags tags) {
        var sink = MediaSink.memory();
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Fixtures.read(input)));
             var muxer = factory.apply(sink)) {
            for (var track : demuxer.tracks()) {
                muxer.addTrack(track);
            }
            assertTrue(muxer.supportsTags());
            muxer.setTags(tags);
            for (Packet packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("muxers")
    @DisplayName("writes tags, pictures and chapters that TagEditor and ffprobe read back")
    void writesTags(Case muxer) throws IOException {
        var sample = TagFixtures.sample();
        var output = remux(muxer.input(), muxer.factory(), sample);
        var read = TagEditor.read(MediaSource.of(output));
        assertEquals(sample.get(StandardTag.TITLE), read.get(StandardTag.TITLE));
        assertEquals(sample.getAll(StandardTag.ARTIST), read.getAll(StandardTag.ARTIST));
        assertEquals(sample.get(StandardTag.ALBUM), read.get(StandardTag.ALBUM));
        assertEquals(sample.get(StandardTag.TRACK), read.get(StandardTag.TRACK));
        assertEquals(sample.get("REPLAYGAIN_TRACK_GAIN"), read.get("REPLAYGAIN_TRACK_GAIN"));
        assertEquals(1, read.pictures().size());
        assertArrayEquals(sample.pictures().getFirst().data(), read.pictures().getFirst().data());
        assertEquals(sample.chapters().stream().map(Chapter::startMicros).toList(),
                read.chapters().stream().map(Chapter::startMicros).toList());
        var file = Files.createDirectories(Path.of("target", "media-fixtures"))
                .resolve("muxed-tags-" + muxer.name().replace(' ', '-') + "." + muxer.extension());
        Files.write(file, output);
        Oracle.assume(Oracle.Tool.FFMPEG);
        Oracle.assertDecodesCleanly(file);
        var probe = TagFixtures.ffprobeTags(file);
        assertEquals(sample.get(StandardTag.TITLE).orElseThrow(), probe.get("title"), probe.toString());
        assertEquals(sample.get(StandardTag.ALBUM).orElseThrow(), probe.get("album"), probe.toString());
        if (muxer.ffprobeChapters()) {
            assertEquals(sample.chapters().stream().map(Chapter::title).toList(),
                    TagFixtures.ffprobeChapters(file).stream().map(Chapter::title).toList());
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("muxers")
    @DisplayName("rejects tags after the first packet")
    void lateTags(Case muxer) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Fixtures.read(muxer.input())));
             var output = muxer.factory().apply(MediaSink.memory())) {
            for (var track : demuxer.tracks()) {
                output.addTrack(track);
            }
            output.writePacket(demuxer.read());
            assertThrows(IllegalStateException.class, () -> output.setTags(TagFixtures.sample()));
        }
    }

    @Test
    @DisplayName("a muxer without a tag form rejects tags")
    void unsupported() {
        try (var muxer = new IvfMuxer(MediaSink.memory())) {
            assertFalse(muxer.supportsTags());
            assertThrows(MediaException.Unsupported.class, () -> muxer.setTags(MediaTags.empty()));
        }
    }

    @Test
    @DisplayName("a raw stream without a tag form rejects tags")
    void rawUnsupported() {
        var input = untagged("wav", List.of("-c:a", "pcm_s16le"));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(Fixtures.read(input)));
             var muxer = new RawMuxer(MediaSink.memory())) {
            Track track = demuxer.tracks().getFirst();
            muxer.addTrack(track);
            assertFalse(muxer.supportsTags());
            assertThrows(MediaException.Unsupported.class, () -> muxer.setTags(MediaTags.empty()));
        }
    }
}
