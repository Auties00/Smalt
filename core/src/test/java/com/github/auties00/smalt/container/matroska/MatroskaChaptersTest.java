package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Attachment;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ChapterEdition;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.internal.tags.Ebml;
import com.github.auties00.smalt.internal.tags.EbmlIds;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static com.github.auties00.smalt.container.matroska.EbmlWriter.binary;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.file;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.master;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.string;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.uint;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers Matroska chapter editions (nested chapters, titles in several languages, default and ordered editions)
 * and attachments that are not pictures, written by {@link MatroskaMuxer} and the tag editor and read by
 * {@link MatroskaDemuxer}, against hand-built elements, ffprobe and, where installed, mkvmerge and mkvextract.
 */
@DisplayName("Matroska chapter editions and attachments")
class MatroskaChaptersTest {
    private static final byte[] FONT = bytes(3000, 7);

    private static byte[] bytes(int length, long seed) {
        var data = new byte[length];
        new Random(seed).nextBytes(data);
        return data;
    }

    private static ContainerProbe.Demuxed audio() {
        return MuxSupport.demux(Fixtures.read(Fixtures.tone("mka", 440, 48000, 1, 4, List.of("-c:a", "flac"))));
    }

    private static List<ChapterEdition> editions() {
        var nested = List.of(Chapter.of(0, 500_000, "Opening titles"),
                new Chapter(500_000, 1_000_000, Optional.of("Cold open"), List.of(new Chapter.Title("Cold open", "eng"),
                        new Chapter.Title("Prologue", "fre")), List.of(Chapter.of(600_000, 700_000, "Deep"))));
        var theatrical = new ChapterEdition(List.of(
                new Chapter(0, 1_000_000, Optional.of("Intro"), List.of(new Chapter.Title("Intro", "eng"),
                        new Chapter.Title("Introduction", "fre"), new Chapter.Title("Introdução", "pt-BR")), nested),
                Chapter.of(1_000_000, 4_000_000, "Main"),
                Chapter.of(4_000_000, 4_000_000, null)), false, false,
                List.of(new Chapter.Title("Theatrical", "en"), new Chapter.Title("Cinéma", "fr")));
        var director = new ChapterEdition(List.of(Chapter.of(2_000_000, 3_000_000, "B"),
                Chapter.of(0, 2_000_000, "A")), true, true, List.of());
        return List.of(theatrical, director);
    }

    private static MediaTags tags() {
        return MediaTags.builder().add("title", "editions").editions(editions())
                .addAttachment(Attachment.of("DejaVuSans.ttf", "font/ttf", "subtitle font", FONT))
                .addAttachment(Attachment.of("notes.txt", "text/plain", "", "notes\n".getBytes(StandardCharsets.UTF_8)))
                .addPicture(AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "image/png", "",
                        Fixtures.read(Fixtures.testImage("png", 16, 16, List.of()))))
                .build();
    }

    private static byte[] mux(MediaTags tags) {
        var source = audio();
        var sink = MediaSink.memory();
        try (var muxer = new MatroskaMuxer(sink)) {
            source.tracks().forEach(muxer::addTrack);
            muxer.setTags(tags);
            source.order().forEach(muxer::writePacket);
            muxer.finish();
        }
        return MuxSupport.validated("editions", sink.toByteArray());
    }

    private static Path save(String name, byte[] data) {
        return MuxSupport.save(name, data);
    }

    private static String run(String tool, String... arguments) {
        Oracle.assume(tool);
        return new String(Oracle.run(tool, Duration.ofMinutes(2), null, List.of(arguments)), StandardCharsets.UTF_8);
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {
        @Test
        @DisplayName("reads back every edition, nested chapter, localized title and attachment the muxer writes")
        void muxer() {
            var output = mux(tags());
            try (var demuxer = new MatroskaDemuxer(MediaSource.of(output))) {
                assertEquals(MediaTags.builder().editions(editions()).build().editions(), demuxer.chapterEditions());
                // the default edition is the second, flagged; it is ordered, so its stored order is kept
                assertEquals(List.of(Chapter.of(0, 2_000_000, "A"), Chapter.of(2_000_000, 3_000_000, "B")),
                        demuxer.chapters());
                assertEquals(List.of(Attachment.of("DejaVuSans.ttf", "font/ttf", "subtitle font", FONT),
                        Attachment.of("notes.txt", "text/plain", "", "notes\n".getBytes(StandardCharsets.UTF_8))),
                        demuxer.attachments());
                assertEquals(1, demuxer.attachedPictures().size());
                assertEquals(AttachedPicture.Type.FRONT_COVER, demuxer.attachedPictures().getFirst().type());
            }
            assertEquals(tags().toBuilder().remove("title").build().editions(), TagEditor.read(MediaSource.of(output))
                    .editions());
        }

        @Test
        @DisplayName("writes ISO 639-2 codes as ChapLanguage and other tags as ChapLanguageBCP47")
        void languages() {
            var output = mux(tags());
            var text = new String(output, StandardCharsets.ISO_8859_1);
            assertTrue(text.contains("pt-BR"));
            var bcp47 = EbmlWriter.encodeId(EbmlIds.CHAP_LANGUAGE_BCP47);
            var marker = new String(bcp47, StandardCharsets.ISO_8859_1) + (char) (0x80 | 5) + "pt-BR";
            assertTrue(text.contains(marker), "pt-BR is a ChapLanguageBCP47");
            var legacy = new String(EbmlWriter.encodeId(EbmlIds.CHAP_LANGUAGE), StandardCharsets.ISO_8859_1)
                    + (char) (0x80 | 3) + "fre";
            assertTrue(text.contains(legacy), "fre is a ChapLanguage");
        }

        @Test
        @DisplayName("edits editions and attachments in place with the tag editor")
        void tagEditor() {
            var original = mux(MediaTags.builder().add("title", "x").chapters(List.of(Chapter.of(0, 1_000_000, "one")))
                    .build());
            var edited = TagEditor.write(original, tags(), TagWriteOptions.defaults());
            var read = TagEditor.read(MediaSource.of(edited));
            assertEquals(tags().editions(), read.editions());
            assertEquals(tags().attachments(), read.attachments());
            try (var demuxer = new MatroskaDemuxer(MediaSource.of(edited))) {
                assertEquals(tags().attachments(), demuxer.attachments());
            }
            var stripped = TagEditor.write(edited, read.toBuilder().attachments(List.of()).chapters(List.of()).build(),
                    TagWriteOptions.defaults());
            var none = TagEditor.read(MediaSource.of(stripped));
            assertTrue(none.attachments().isEmpty());
            assertTrue(none.editions().isEmpty());
            assertEquals(1, none.pictures().size());
        }

        private static long docTypeVersion(byte[] file) {
            var header = Ebml.header(file, 0, file.length);
            var children = Ebml.children(file, header.length(), header.length() + (int) header.size());
            return Ebml.unsigned(file, Ebml.find(children, EbmlIds.DOC_TYPE_VERSION));
        }

        @Test
        @DisplayName("declares DocTypeVersion 5 for edition names, in the muxer and after an edit in place or a rewrite")
        void docTypeVersion() {
            var unnamed = List.of(new ChapterEdition(List.of(Chapter.of(0, 1_000_000, "x".repeat(300))), true, false,
                    List.of()));
            var original = mux(MediaTags.builder().add("title", "x").editions(unnamed).build());
            assertEquals(4, docTypeVersion(original));
            assertEquals(5, docTypeVersion(mux(tags())));
            var rewritten = TagEditor.write(original, tags(), TagWriteOptions.defaults());
            assertEquals(5, docTypeVersion(rewritten));
            assertEquals(tags().editions(), TagEditor.read(MediaSource.of(rewritten)).editions());
            // a shorter Chapters element fits the old one's space, so the edit moves nothing
            var named = List.of(new ChapterEdition(List.of(Chapter.of(0, 1_000_000, "y")), true, false,
                    List.of(new Chapter.Title("Short", "en"))));
            var inPlace = TagEditor.write(original, MediaTags.builder().add("title", "x").editions(named).build(),
                    TagWriteOptions.defaults());
            assertEquals(original.length, inPlace.length);
            assertEquals(5, docTypeVersion(inPlace));
            assertEquals(named, TagEditor.read(MediaSource.of(inPlace)).editions());
            MuxSupport.validated("editions-in-place", inPlace);
            // an edit without edition names keeps the version
            var kept = TagEditor.write(original, MediaTags.builder().add("title", "z").editions(unnamed).build(),
                    TagWriteOptions.defaults());
            assertEquals(4, docTypeVersion(kept));
        }
    }

    @Nested
    @DisplayName("WebM")
    class Webm {
        private static MediaTags theatricalDefault() {
            var theatrical = editions().getFirst();
            var director = editions().get(1);
            return tags().toBuilder().editions(List.of(new ChapterEdition(theatrical.chapters(), true, false,
                    theatrical.names()), new ChapterEdition(director.chapters(), false, true, List.of()))).build();
        }

        private static byte[] muxWebm(MediaTags tags) {
            var source = MuxSupport.demux(Fixtures.read(Fixtures.tone("webm", 440, 48000, 1, 4,
                    List.of("-c:a", "libopus"))));
            var sink = MediaSink.memory();
            try (var muxer = new MatroskaMuxer(sink, MatroskaMuxerOptions.webm())) {
                source.tracks().forEach(muxer::addTrack);
                muxer.setTags(tags);
                source.order().forEach(muxer::writePacket);
                muxer.finish();
            }
            return MuxSupport.validated("webm-editions", sink.toByteArray());
        }

        private static void assertWebmChapters(byte[] file) {
            var text = new String(file, StandardCharsets.ISO_8859_1);
            var at = text.indexOf(new String(EbmlWriter.encodeId(EbmlIds.CHAPTERS), StandardCharsets.ISO_8859_1));
            assertTrue(at > 0, "no Chapters element");
            var header = Ebml.header(file, at, file.length);
            var chapters = text.substring(at, at + header.length() + (int) header.size());
            for (var id : new long[]{EbmlIds.EDITION_DISPLAY, EbmlIds.EDITION_FLAG_DEFAULT, EbmlIds.EDITION_FLAG_ORDERED,
                    EbmlIds.CHAP_LANGUAGE_BCP47, EbmlIds.EDITION_UID}) {
                assertFalse(chapters.contains(new String(EbmlWriter.encodeId(id), StandardCharsets.ISO_8859_1)),
                        "the WebM Chapters hold element " + Long.toHexString(id));
            }
            assertEquals(1, chapters.split(java.util.regex.Pattern.quote(new String(
                    EbmlWriter.encodeId(EbmlIds.EDITION_ENTRY), StandardCharsets.ISO_8859_1)), -1).length - 1,
                    "one EditionEntry");
            assertFalse(text.contains("DejaVuSans"), "WebM holds an attachment that is not a picture");
            try (var demuxer = new MatroskaDemuxer(MediaSource.of(file))) {
                var editions = demuxer.chapterEditions();
                assertEquals(1, editions.size());
                var intro = editions.getFirst().chapters().getFirst();
                // the pt-BR title has no ChapLanguage form
                assertEquals(List.of(new Chapter.Title("Intro", "eng"), new Chapter.Title("Introduction", "fre")),
                        intro.titles());
                assertEquals(2, intro.children().size());
                assertEquals(3, editions.getFirst().chapters().size());
                assertTrue(demuxer.attachments().isEmpty());
            }
        }

        @Test
        @DisplayName("writes the default edition alone, without the elements WebM lacks, from the muxer")
        void muxer() {
            assertWebmChapters(muxWebm(theatricalDefault()));
        }

        @Test
        @DisplayName("writes the default edition alone, without the elements WebM lacks, from the tag editor")
        void tagEditor() {
            var original = muxWebm(MediaTags.builder().add("title", "x").build());
            var edited = MuxSupport.validated("webm-edited", TagEditor.write(original, theatricalDefault(),
                    TagWriteOptions.defaults()));
            assertWebmChapters(edited);
            assertTrue(TagEditor.read(MediaSource.of(edited)).attachments().isEmpty());
        }
    }

    @Nested
    @DisplayName("parsing")
    class Parsing {
        private static byte[] chapters(EbmlWriter.Node... editions) {
            return file("matroska", false, MatroskaDemuxerTest.pcmTrack(1, 0), master(EbmlIds.CHAPTERS, editions));
        }

        private static EbmlWriter.Node atom(long start, String title, EbmlWriter.Node... extra) {
            var children = new ArrayList<EbmlWriter.Node>(List.of(uint(EbmlIds.CHAPTER_UID, start + 1),
                    uint(EbmlIds.CHAPTER_TIME_START, start * 1000)));
            if (title != null) {
                children.add(master(EbmlIds.CHAPTER_DISPLAY, string(EbmlIds.CHAP_STRING, title)));
            }
            children.addAll(List.of(extra));
            return master(EbmlIds.CHAPTER_ATOM, children);
        }

        private static List<ChapterEdition> editionsOf(byte[] file) {
            try (var demuxer = new MatroskaDemuxer(MediaSource.of(file))) {
                return demuxer.chapterEditions();
            }
        }

        @Test
        @DisplayName("skips hidden editions and chapters, and defaults a display without language to eng")
        void hidden() {
            var file = chapters(
                    master(EbmlIds.EDITION_ENTRY, uint(EbmlIds.EDITION_FLAG_HIDDEN, 1), atom(0, "secret")),
                    master(EbmlIds.EDITION_ENTRY, atom(0, "a"), atom(1000, "hidden",
                            uint(EbmlIds.CHAPTER_FLAG_HIDDEN, 1)), atom(2000, null)));
            var editions = editionsOf(file);
            assertEquals(1, editions.size());
            var chapters = editions.getFirst().chapters();
            assertEquals(2, chapters.size());
            assertEquals(List.of(new Chapter.Title("a", "eng")), chapters.getFirst().titles());
            assertEquals(2000, chapters.getFirst().endMicros());
            assertEquals(Optional.empty(), chapters.get(1).title());
        }

        @Test
        @DisplayName("gives a display one title per language, ChapLanguageBCP47 over ChapLanguage")
        void displays() {
            var display = master(EbmlIds.CHAPTER_DISPLAY, string(EbmlIds.CHAP_STRING, "Hello"),
                    string(EbmlIds.CHAP_LANGUAGE, "eng"), string(EbmlIds.CHAP_LANGUAGE, "ger"));
            var tagged = master(EbmlIds.CHAPTER_DISPLAY, string(EbmlIds.CHAP_STRING, "Bonjour"),
                    string(EbmlIds.CHAP_LANGUAGE, "fre"), string(EbmlIds.CHAP_LANGUAGE_BCP47, "fr-CA"));
            var file = chapters(master(EbmlIds.EDITION_ENTRY, master(EbmlIds.CHAPTER_ATOM,
                    uint(EbmlIds.CHAPTER_TIME_START, 0), display, tagged)));
            var titles = editionsOf(file).getFirst().chapters().getFirst().titles();
            assertEquals(List.of(new Chapter.Title("Hello", "eng"), new Chapter.Title("Hello", "ger"),
                    new Chapter.Title("Bonjour", "fr-CA")), titles);
        }

        @Test
        @DisplayName("ends nested chapters at the next sibling or the parent, and bounds the nesting depth")
        void nesting() {
            EbmlWriter.Node deepest = atom(19, "level 20");
            for (var level = 19; level >= 1; level--) {
                deepest = atom(level - 1, "level " + level, deepest);
            }
            var parent = master(EbmlIds.CHAPTER_ATOM, uint(EbmlIds.CHAPTER_TIME_START, 0),
                    uint(EbmlIds.CHAPTER_TIME_END, 9_000_000), atom(1000, "first"), atom(5000, "second"));
            var file = chapters(master(EbmlIds.EDITION_ENTRY, parent), master(EbmlIds.EDITION_ENTRY, deepest));
            var editions = editionsOf(file);
            var children = editions.getFirst().chapters().getFirst().children();
            assertEquals(List.of(Chapter.of(1000, 5000, "first").withTitles(List.of(new Chapter.Title("first", "eng"))),
                    Chapter.of(5000, 9000, "second").withTitles(List.of(new Chapter.Title("second", "eng")))), children);
            var depth = 0;
            var chapter = editions.get(1).chapters().getFirst();
            while (!chapter.children().isEmpty()) {
                chapter = chapter.children().getFirst();
                depth++;
            }
            assertEquals(15, depth, "nesting stops after 16 levels");
        }

        @Test
        @DisplayName("keeps a damaged Chapters element from failing the demuxer")
        void damaged() {
            var data = file("matroska", false, MatroskaDemuxerTest.pcmTrack(1, 0),
                    master(EbmlIds.CHAPTERS, binary(EbmlIds.EDITION_ENTRY, new byte[]{(byte) 0xB6, (byte) 0x85, 1})));
            assertTrue(editionsOf(data).isEmpty());
        }
    }

    @Nested
    @DisplayName("oracles")
    class Oracles {
        @Test
        @DisplayName("ffprobe reports the chapters of a single edition and the attachments as attachment streams")
        void ffprobe() {
            var output = mux(MediaTags.builder().chapters(List.of(Chapter.of(0, 1_500_000, "first"),
                    Chapter.of(1_500_000, 4_000_000, "second")))
                    .addAttachment(Attachment.of("DejaVuSans.ttf", "font/ttf", "", FONT)).build());
            var file = save("chapters-single.mkv", output);
            var probe = run("ffprobe", "-v", "error", "-show_chapters", "-show_streams", "-of", "json", file.toString());
            assertTrue(probe.contains("\"title\": \"first\"") && probe.contains("\"title\": \"second\""), probe);
            assertTrue(probe.contains("\"end_time\": \"1.500000\""), probe);
            assertTrue(probe.contains("\"codec_type\": \"attachment\""), probe);
            assertTrue(probe.contains("\"filename\": \"DejaVuSans.ttf\""), probe);
            assertTrue(probe.contains("\"mimetype\": \"font/ttf\""), probe);
        }

        @Test
        @DisplayName("reads the editions, nested chapters, languages and font attachment of a file mkvmerge built")
        void mkvmerge() throws IOException {
            Oracle.assume("mkvmerge");
            var directory = Files.createTempDirectory("mkvmerge-chapters");
            var input = directory.resolve("input.mka");
            Files.write(input, Fixtures.read(Fixtures.tone("mka", 440, 48000, 1, 4, List.of("-c:a", "flac"))));
            var font = directory.resolve("DejaVuSans.ttf");
            Files.write(font, FONT);
            var xml = directory.resolve("chapters.xml");
            Files.writeString(xml, """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <Chapters>
                      <EditionEntry>
                        <EditionUID>101</EditionUID>
                        <ChapterAtom>
                          <ChapterUID>1</ChapterUID>
                          <ChapterTimeStart>00:00:00.000000000</ChapterTimeStart>
                          <ChapterTimeEnd>00:00:02.000000000</ChapterTimeEnd>
                          <ChapterDisplay><ChapterString>Intro</ChapterString><ChapterLanguage>eng</ChapterLanguage></ChapterDisplay>
                          <ChapterDisplay><ChapterString>Introduction</ChapterString><ChapterLanguage>fre</ChapterLanguage></ChapterDisplay>
                          <ChapterAtom>
                            <ChapterUID>2</ChapterUID>
                            <ChapterTimeStart>00:00:00.500000000</ChapterTimeStart>
                            <ChapterTimeEnd>00:00:01.000000000</ChapterTimeEnd>
                            <ChapterDisplay><ChapterString>Inner</ChapterString><ChapterLanguage>eng</ChapterLanguage></ChapterDisplay>
                          </ChapterAtom>
                        </ChapterAtom>
                        <ChapterAtom>
                          <ChapterUID>3</ChapterUID>
                          <ChapterTimeStart>00:00:02.000000000</ChapterTimeStart>
                          <ChapterTimeEnd>00:00:04.000000000</ChapterTimeEnd>
                          <ChapterDisplay><ChapterString>Rest</ChapterString><ChapterLanguage>eng</ChapterLanguage></ChapterDisplay>
                        </ChapterAtom>
                      </EditionEntry>
                      <EditionEntry>
                        <EditionUID>102</EditionUID>
                        <EditionFlagDefault>1</EditionFlagDefault>
                        <EditionFlagOrdered>1</EditionFlagOrdered>
                        <ChapterAtom>
                          <ChapterUID>4</ChapterUID>
                          <ChapterTimeStart>00:00:01.000000000</ChapterTimeStart>
                          <ChapterTimeEnd>00:00:03.000000000</ChapterTimeEnd>
                          <ChapterDisplay><ChapterString>Cut</ChapterString><ChapterLanguage>eng</ChapterLanguage></ChapterDisplay>
                        </ChapterAtom>
                      </EditionEntry>
                    </Chapters>
                    """);
            var output = directory.resolve("output.mka");
            run("mkvmerge", "-q", "-o", output.toString(), "--chapters", xml.toString(), "--attachment-mime-type",
                    "font/ttf", "--attachment-description", "font", "--attach-file", font.toString(), input.toString());
            try (var demuxer = new MatroskaDemuxer(MediaSource.of(output))) {
                var editions = demuxer.chapterEditions();
                assertEquals(2, editions.size());
                assertTrue(editions.get(1).defaultEdition() && editions.get(1).ordered());
                var intro = editions.getFirst().chapters().getFirst();
                assertEquals(0, intro.startMicros());
                assertEquals(2_000_000, intro.endMicros());
                assertEquals(2, intro.titles().size());
                assertEquals("Intro", intro.titles().get(0).text());
                assertTrue(List.of("eng", "en").contains(intro.titles().get(0).language()), intro.titles().toString());
                assertEquals("Introduction", intro.titles().get(1).text());
                assertTrue(List.of("fre", "fr").contains(intro.titles().get(1).language()), intro.titles().toString());
                assertEquals(List.of(500_000L, 1_000_000L), List.of(intro.children().getFirst().startMicros(),
                        intro.children().getFirst().endMicros()));
                assertEquals(Optional.of("Inner"), intro.children().getFirst().title());
                assertEquals(List.of(Chapter.of(1_000_000, 3_000_000, "Cut").withTitles(
                        editions.get(1).chapters().getFirst().titles())), demuxer.chapters());
                var attached = demuxer.attachments().getFirst();
                assertEquals("DejaVuSans.ttf", attached.fileName());
                assertEquals("font/ttf", attached.mediaType());
                assertEquals("font", attached.description());
                assertArrayEquals(FONT, attached.data());
            }
        }

        @Test
        @DisplayName("writes editions that mkvextract exports as the same chapters, and attachments it extracts")
        void mkvextract() throws IOException {
            Oracle.assume("mkvextract");
            var file = save("editions.mka", mux(tags()));
            var exported = Files.createTempDirectory("mkvextract-chapters").resolve("chapters.xml");
            run("mkvextract", file.toString(), "chapters", exported.toString());
            var xml = Files.readString(exported);
            assertEquals(2, xml.split("<EditionEntry>", -1).length - 1, xml);
            for (var expected : List.of("<ChapterString>Introdução</ChapterString>", "<ChapterString>Deep</ChapterString>",
                    "<EditionFlagOrdered>1</EditionFlagOrdered>", "<EditionFlagDefault>1</EditionFlagDefault>",
                    "<ChapterTimeStart>00:00:00.600000000</ChapterTimeStart>",
                    "<ChapterTimeEnd>00:00:04.000000000</ChapterTimeEnd>")) {
                assertTrue(xml.contains(expected), expected + " in " + xml);
            }
            var directory = Files.createTempDirectory("mkvextract-attachments");
            var extracted = directory.resolve("font.ttf");
            run("mkvextract", file.toString(), "attachments", "2:" + extracted);
            try {
                assertArrayEquals(FONT, Files.readAllBytes(extracted));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }
}
