package com.github.auties00.smalt.metadata;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.raw.RawMuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Edits the ID3v2, APE and ID3v1 tags of raw AC-3, E-AC-3 and AMR streams and writes the tags of their raw
 * muxer, with ffprobe as the independent reader and the packets of Smalt's demuxers and ffmpeg's packet hashes
 * proving the audio unchanged.
 */
@DisplayName("Tags of raw AC-3, E-AC-3 and AMR streams")
class RawStreamTagsTest {
    /** A raw stream under test. */
    record Case(String name, String extension, List<String> options, String encoder, ContainerFormat format) {
        Path file() {
            if (encoder != null) {
                Oracle.assumeEncoder(encoder);
            }
            return Fixtures.ffmpeg(extension, List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=2"),
                    options);
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> streams() {
        return Stream.of(
                new Case("ac3", "ac3", List.of("-c:a", "ac3", "-b:a", "96k"), null, ContainerFormat.AC3),
                new Case("eac3", "eac3", List.of("-c:a", "eac3", "-b:a", "96k"), null, ContainerFormat.AC3),
                new Case("amr-nb", "amr", List.of("-ar", "8000", "-ac", "1", "-c:a", "libopencore_amrnb", "-b:a", "12.2k"),
                        "libopencore_amrnb", ContainerFormat.AMR),
                new Case("amr-wb", "awb", List.of("-ar", "16000", "-ac", "1", "-c:a", "libvo_amrwbenc", "-b:a", "23.85k",
                        "-f", "amr"), "libvo_amrwbenc", ContainerFormat.AMR)
        ).map(Arguments::of);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("writes ID3v2, APE and ID3v1 tags around the stream and reads them back like ffprobe")
    void roundTrip(Case stream, @TempDir Path directory) throws Exception {
        var file = TagFixtures.copy(stream.file(), directory);
        var original = Files.readAllBytes(file);
        var before = MuxSupport.demux(original);
        var hash = TagFixtures.packetHash(file);
        var sample = TagFixtures.sample();
        TagEditor.write(file, sample, TagWriteOptions.builder().apeMode(TagWriteOptions.ApeMode.ALWAYS)
                .id3v1Mode(TagWriteOptions.Id3v1Mode.ALWAYS).build());
        var tagged = Files.readAllBytes(file);
        assertEquals(stream.format(), Containers.detect(MediaSource.of(tagged)).orElseThrow());
        assertTrue(new String(tagged, 0, 3, java.nio.charset.StandardCharsets.ISO_8859_1).equals("ID3"));
        assertTrue(TagVocabularyTest.indexOf(tagged, "APETAGEX".getBytes(java.nio.charset.StandardCharsets.US_ASCII)) > 0);
        assertEquals('T', tagged[tagged.length - 128]);
        var read = TagEditor.read(file);
        assertEquals(sample.get(StandardTag.TITLE), read.get(StandardTag.TITLE));
        assertEquals(sample.getAll(StandardTag.ARTIST), read.getAll(StandardTag.ARTIST));
        assertEquals(sample.get(StandardTag.REPLAYGAIN_TRACK_GAIN), read.get(StandardTag.REPLAYGAIN_TRACK_GAIN));
        assertEquals(1, read.pictures().size());
        assertArrayEquals(sample.pictures().getFirst().data(), read.pictures().getFirst().data());
        TagEditorTest.assertChapters(sample.chapters(), read.chapters());
        var after = MuxSupport.demux(tagged);
        assertEquals(before.tracks(), after.tracks());
        MuxSupport.assertSamePayloads(before, after, java.util.Map.of(before.tracks().getFirst().id(),
                after.tracks().getFirst().id()));
        var probed = Media.probe(MediaSource.of(tagged));
        assertEquals("Edited Title \u00E8\u4E2D", probed.tags().get("title"));
        assertEquals(sample.chapters().size(), probed.chapters().size());
        assertEquals(1, probed.attachedPictures().size());
        assertEquals("Edited Title \u00E8\u4E2D", TagFixtures.ffprobeTags(file).get("title"));
        // ffmpeg's raw demuxers read the trailing APE and ID3v1 tags as audio, so its hashes compare the leading tag only
        TagEditor.write(file, read, TagWriteOptions.builder().apeMode(TagWriteOptions.ApeMode.REMOVE)
                .id3v1Mode(TagWriteOptions.Id3v1Mode.REMOVE).build());
        assertEquals(hash, TagFixtures.packetHash(file), "packets changed");
        Oracle.assertDecodesCleanly(file);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("edits in place once the leading tag is padded, and removes every tag")
    void inPlace(Case stream, @TempDir Path directory) throws Exception {
        var file = TagFixtures.copy(stream.file(), directory);
        var original = Files.readAllBytes(file);
        TagEditor.write(file, TagFixtures.sample());
        assertEquals(TagWriteResult.IN_PLACE, TagEditor.write(file, TagFixtures.sample().toBuilder()
                .set(StandardTag.TITLE, "Short").build()));
        assertEquals("Short", TagEditor.read(file).get(StandardTag.TITLE).orElseThrow());
        TagEditor.write(file, MediaTags.empty(), TagWriteOptions.builder().id3v1Mode(TagWriteOptions.Id3v1Mode.REMOVE)
                .apeMode(TagWriteOptions.ApeMode.REMOVE).inPlace(false).build());
        assertArrayEquals(original, Files.readAllBytes(file), "removing every tag restores the stream");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("writes a leading ID3v2.4 tag from the raw muxer that the demuxer, ffprobe and TagEditor read")
    void muxer(Case stream) {
        var source = MuxSupport.demux(Fixtures.read(stream.file()));
        var tags = MediaTags.builder().set(StandardTag.TITLE, "Muxed").set(StandardTag.BPM, "90").build();
        var output = MuxSupport.toMemory(source.tracks(), source.order(), sink -> {
            var muxer = new RawMuxer(sink);
            return new TaggingMuxer(muxer, tags);
        });
        assertTrue(Arrays.equals(output, 0, 3, new byte[]{'I', 'D', '3'}, 0, 3));
        var read = TagEditor.read(MediaSource.of(output));
        assertEquals("Muxed", read.get(StandardTag.TITLE).orElseThrow());
        assertEquals("90", read.get(StandardTag.BPM).orElseThrow());
        var demuxed = MuxSupport.demux(output);
        assertEquals("Muxed", Media.probe(MediaSource.of(output)).tags().get("title"));
        MuxSupport.assertSamePayloads(source, demuxed, java.util.Map.of(source.tracks().getFirst().id(),
                demuxed.tracks().getFirst().id()));
        assertEquals("Muxed", TagFixtures.ffprobeTags(MuxSupport.save("raw-tags." + stream.extension(), output))
                .get("title"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("streams")
    @DisplayName("rejects corrupt tags with a MediaException")
    void corrupt(Case stream) {
        var original = Fixtures.read(stream.file());
        var tagged = TagEditor.write(original, TagFixtures.sample(), TagWriteOptions.defaults());
        for (var cut = 3; cut < Math.min(tagged.length, 4096); cut += 97) {
            var truncated = Arrays.copyOf(tagged, cut);
            try {
                TagEditor.read(MediaSource.of(truncated));
            } catch (MediaException expected) {
                // a cut tag is either read partially or rejected
            }
        }
    }

    /** A muxer that sets tags after declaring the tracks, as the raw muxer requires. */
    private static final class TaggingMuxer implements com.github.auties00.smalt.container.Muxer {
        private final RawMuxer muxer;
        private final MediaTags tags;

        TaggingMuxer(RawMuxer muxer, MediaTags tags) {
            this.muxer = muxer;
            this.tags = tags;
        }

        @Override
        public void addTrack(com.github.auties00.smalt.track.Track track) {
            muxer.addTrack(track);
            assertTrue(muxer.supportsTags());
            muxer.setTags(tags);
        }

        @Override
        public void writePacket(com.github.auties00.smalt.track.Packet packet) {
            muxer.writePacket(packet);
        }

        @Override
        public void finish() {
            muxer.finish();
        }

        @Override
        public void close() {
            muxer.close();
        }
    }
}
