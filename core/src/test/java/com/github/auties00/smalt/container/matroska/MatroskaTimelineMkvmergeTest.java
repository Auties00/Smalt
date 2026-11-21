package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.PacketFlag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks virtual timelines against files mkvmerge builds (black box): ordered chapters within one file, a chapter
 * that plays another file by its segment UUID (as a span and as an edition), and the files of a split with
 * {@code --link}. The packets the demuxer returns for each part are compared, data hash and timestamp, with the
 * packets ffprobe lists for the stored files, which it reads as stored since ffmpeg ignores ordered chapters, moved
 * to the part's place; the linked split is compared with mkvmerge's unsplit output of the same source. Every video
 * frame is a key frame and the chapters lie on frame boundaries, so each part starts and ends on whole video frames.
 */
@DisplayName("Matroska virtual timelines against mkvmerge")
class MatroskaTimelineMkvmergeTest {
    private static final String PROGRAM_UID = "0123456789abcdef0123456789abcdef";
    private static final String INTRO_UID = "fedcba9876543210fedcba9876543210";

    private static Path source(Path directory, String name, String pattern, int frequency, double seconds)
            throws IOException {
        var fixture = Fixtures.ffmpeg("mkv", List.of(
                "-f", "lavfi", "-i", pattern + "=size=64x48:rate=25:duration=" + seconds,
                "-f", "lavfi", "-i", "sine=frequency=" + frequency + ":sample_rate=8000:duration=" + seconds),
                List.of("-c:v", "mjpeg", "-q:v", "6", "-pix_fmt", "yuvj420p", "-c:a", "pcm_s16le", "-ac", "1"));
        var copy = directory.resolve(name);
        Files.copy(fixture, copy);
        return copy;
    }

    private static void mkvmerge(String... arguments) {
        Oracle.run("mkvmerge", Duration.ofMinutes(2), null, List.of(arguments));
    }

    private static Path chapters(Path directory, String name, String editions) throws IOException {
        var xml = directory.resolve(name);
        Files.writeString(xml, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<Chapters>\n" + editions + "</Chapters>\n");
        return xml;
    }

    private static String atom(int uid, String start, String end, String title, String extra) {
        return "<ChapterAtom><ChapterUID>" + uid + "</ChapterUID><ChapterTimeStart>" + start
                + "</ChapterTimeStart>" + (end == null ? "" : "<ChapterTimeEnd>" + end + "</ChapterTimeEnd>")
                + extra + "<ChapterDisplay><ChapterString>" + title
                + "</ChapterString><ChapterLanguage>eng</ChapterLanguage></ChapterDisplay></ChapterAtom>\n";
    }

    private static String edition(int uid, boolean isDefault, String... atoms) {
        return "<EditionEntry><EditionUID>" + uid + "</EditionUID><EditionFlagOrdered>1</EditionFlagOrdered>"
                + "<EditionFlagDefault>" + (isDefault ? 1 : 0) + "</EditionFlagDefault>\n" + String.join("", atoms)
                + "</EditionEntry>\n";
    }

    /**
     * A part of the expected timeline: the stored packets of a file between two times in milliseconds.
     */
    private record Part(ContainerProbe.Probe probe, long start, long end, long offset) {
        /**
         * Returns all the packets of a file, at their stored times.
         */
        static Part whole(ContainerProbe.Probe probe) {
            return new Part(probe, -1L << 40, 1L << 40, -1L << 40);
        }
    }

    /**
     * Returns the packets a timeline presents, per stream, as {@code pts@hash} in milliseconds: the video frames
     * presented within each part and the audio blocks that overlap it, moved to the part's place.
     */
    private static List<List<String>> expected(Part... parts) {
        var streams = List.<List<String>>of(new ArrayList<>(), new ArrayList<>());
        for (var part : parts) {
            for (var packet : part.probe().packets()) {
                var pts = packet.pts();
                var overlaps = packet.stream() == 0 ? pts >= part.start() && pts < part.end()
                        : pts < part.end() && pts + packet.duration() > part.start();
                if (overlaps) {
                    streams.get(packet.stream()).add((pts - part.start() + part.offset()) + "@" + packet.hash());
                }
            }
        }
        return streams;
    }

    private static List<List<String>> actual(MatroskaDemuxer demuxer) {
        var streams = List.<List<String>>of(new ArrayList<>(), new ArrayList<>());
        var audioRate = 8;
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            assertFalse(packet.flags().contains(PacketFlag.DISCARD), "no frame of an intra-only part is pre-roll");
            var stream = packet.trackId() - 1;
            var pts = stream == 0 ? packet.pts() : Math.floorDiv(packet.pts(), audioRate);
            streams.get(stream).add(pts + "@" + ContainerProbe.crc32(packet.data()));
        }
        return streams;
    }

    private static long durationMillis(ContainerProbe.Probe probe) {
        return Math.round(Double.parseDouble(probe.format().get("duration")) * 1000);
    }

    @Test
    @DisplayName("plays the ordered chapters of a file in their order, as its stored packets moved to their places")
    void ordered() throws IOException {
        Oracle.assume("mkvmerge");
        var directory = Scratch.directory("mkvmerge-ordered");
        var input = source(directory, "input.mkv", "testsrc", 440, 4);
        var xml = chapters(directory, "ordered.xml", edition(10, true,
                atom(1, "00:00:02.000000000", "00:00:03.000000000", "Middle", ""),
                atom(2, "00:00:00.000000000", "00:00:01.000000000", "Opening", ""),
                atom(3, "00:00:03.000000000", "00:00:03.600000000", "Close", "")));
        var output = directory.resolve("ordered.mkv");
        mkvmerge("-q", "-o", output.toString(), "--chapters", xml.toString(), input.toString());
        var probe = ContainerProbe.probe(output);
        try (var demuxer = MatroskaDemuxer.open(output, DemuxerOptions.defaults())) {
            assertTrue(demuxer.isVirtualTimeline());
            assertEquals(2_600_000, demuxer.durationMicros());
            assertEquals(List.of(0L, 1_000_000L, 2_000_000L), demuxer.chapters().stream()
                    .map(chapter -> chapter.startMicros()).toList());
            assertEquals(expected(new Part(probe, 2000, 3000, 0), new Part(probe, 0, 1000, 1000),
                    new Part(probe, 3000, 3600, 2000)), actual(demuxer));
        }
        try (var demuxer = MatroskaDemuxer.open(output, DemuxerOptions.builder().virtualTimeline(false).build())) {
            assertFalse(demuxer.isVirtualTimeline());
            assertEquals(durationMillis(probe) * 1000.0, demuxer.durationMicros(), 1000.0);
            assertEquals(expected(Part.whole(probe)), actual(demuxer));
        }
    }

    @Test
    @DisplayName("plays a span and an edition of another file named by its segment UUID, found in the directory")
    void linked() throws IOException {
        Oracle.assume("mkvmerge");
        var directory = Scratch.directory("mkvmerge-linked");
        var introInput = source(directory, "intro-input.mkv", "smptebars", 880, 2);
        var programInput = source(directory, "program-input.mkv", "testsrc", 440, 3);
        var introChapters = chapters(directory, "intro.xml", edition(77, true,
                atom(11, "00:00:01.200000000", "00:00:01.600000000", "Sting", "")));
        var intro = directory.resolve("intro.mkv");
        mkvmerge("-q", "-o", intro.toString(), "--segment-uid", "0x" + INTRO_UID, "--chapters",
                introChapters.toString(), introInput.toString());
        var link = "<ChapterSegmentUID format=\"hex\">" + INTRO_UID + "</ChapterSegmentUID>";
        var programChapters = chapters(directory, "program.xml", edition(20, true,
                atom(1, "00:00:00.000000000", "00:00:01.000000000", "Intro", link),
                atom(2, "00:00:00.000000000", "00:00:02.000000000", "Program", ""),
                atom(3, "00:00:00.000000000", null, "Sting", link
                        + "<ChapterSegmentEditionUID>77</ChapterSegmentEditionUID>")));
        var program = directory.resolve("program.mkv");
        mkvmerge("-q", "-o", program.toString(), "--segment-uid", "0x" + PROGRAM_UID, "--chapters",
                programChapters.toString(), programInput.toString());
        Files.delete(introInput);
        Files.delete(programInput);
        var introProbe = ContainerProbe.probe(intro);
        var programProbe = ContainerProbe.probe(program);
        try (var demuxer = MatroskaDemuxer.open(program, DemuxerOptions.defaults())) {
            assertTrue(demuxer.isVirtualTimeline());
            assertEquals(3_400_000, demuxer.durationMicros());
            assertEquals(expected(new Part(introProbe, 0, 1000, 0), new Part(programProbe, 0, 2000, 1000),
                    new Part(introProbe, 1200, 1600, 3000)), actual(demuxer));
        }
        try (var demuxer = new MatroskaDemuxer(com.github.auties00.smalt.io.MediaSource.of(program))) {
            // without a resolver the chapters of the other file play nothing
            assertEquals(2_000_000, demuxer.durationMicros());
        }
    }

    @Test
    @DisplayName("plays the files of a linked split as the unsplit output, from any of them")
    void hardLinked() throws IOException {
        Oracle.assume("mkvmerge");
        var directory = Scratch.directory("mkvmerge-split");
        var input = source(directory, "input.mkv", "testsrc", 440, 4);
        var whole = directory.resolve("whole.mkv");
        mkvmerge("-q", "-o", whole.toString(), input.toString());
        var parts = directory.resolve("part.mkv");
        mkvmerge("-q", "-o", parts.toString(), "--split", "timestamps:00:00:01.4,00:00:02.8", "--link",
                input.toString());
        Files.delete(input);
        var wholeProbe = ContainerProbe.probe(whole);
        Files.delete(whole);
        var expected = expected(Part.whole(wholeProbe));
        for (var name : List.of("part-001.mkv", "part-002.mkv", "part-003.mkv")) {
            var file = directory.resolve(name);
            assertTrue(Files.isRegularFile(file), name + " was not written");
            try (var demuxer = MatroskaDemuxer.open(file, DemuxerOptions.defaults())) {
                assertTrue(demuxer.isVirtualTimeline(), name);
                assertEquals(durationMillis(wholeProbe) * 1000.0, demuxer.durationMicros(), 40_000.0, name);
                assertEquals(expected, actual(demuxer), name);
            }
        }
        var middle = ContainerProbe.probe(directory.resolve("part-002.mkv"));
        assertTrue(middle.packets().getFirst().pts() >= 1000, "the parts of a linked split keep the timestamps going");
    }
}
