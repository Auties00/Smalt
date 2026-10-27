package com.github.auties00.smalt.container.matroska;

import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.container.ChapterEdition;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.internal.tags.EbmlIds;
import com.github.auties00.smalt.internal.tags.MatroskaSegmentProbe;
import com.github.auties00.smalt.io.DataReference;
import com.github.auties00.smalt.io.DataReferenceResolver;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.testkit.container.DemuxerAsserts;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.auties00.smalt.container.matroska.EbmlWriter.binary;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.floating;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.master;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.string;
import static com.github.auties00.smalt.container.matroska.EbmlWriter.uint;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the virtual timelines of Matroska files (RFC 9559 sections 17 and 20): ordered editions, linked segments
 * (medium linking by duration and by edition) and hard-linked chains, on hand-built files whose every frame names
 * its segment, track and stored timestamp in its first bytes, so that each returned packet can be traced to the
 * frame it comes from. Video frames last 40 ms with a key frame every 400 ms; audio is 8 kHz 16-bit PCM in blocks
 * of 40 ms.
 */
@DisplayName("Matroska virtual timelines")
class MatroskaTimelineTest {
    private static final int VIDEO = 1;
    private static final int AUDIO = 2;
    private static final int SLOT = 40;
    private static final int SAMPLES = 320;

    record Frame(int track, long pts, boolean key, byte[] payload) {
    }

    record Origin(int track, int segment, long pts) {
    }

    static byte[] payload(int track, int segment, long pts, int size) {
        var data = new byte[size];
        data[0] = (byte) track;
        data[1] = (byte) segment;
        for (var index = 0; index < 8; index++) {
            data[2 + index] = (byte) (pts >>> (56 - 8 * index));
        }
        return data;
    }

    static Origin origin(Packet packet) {
        var data = packet.data();
        var pts = 0L;
        for (var index = 0; index < 8; index++) {
            pts = (pts << 8) | (data[2 + index] & 0xFF);
        }
        return new Origin(data[0], data[1], pts);
    }

    static List<Frame> av(int segment, long from, long to, int keyEvery) {
        var frames = new ArrayList<Frame>();
        for (var pts = from; pts < to; pts += SLOT) {
            frames.add(new Frame(VIDEO, pts, pts % keyEvery == 0, payload(VIDEO, segment, pts, 16)));
            frames.add(new Frame(AUDIO, pts, true, payload(AUDIO, segment, pts, SAMPLES * 2)));
        }
        return frames;
    }

    static List<Frame> av(int segment, long from, long to) {
        return av(segment, from, to, 400);
    }

    static byte[] uuid(int value) {
        var uuid = new byte[16];
        Arrays.fill(uuid, (byte) value);
        uuid[15] = (byte) 0xA5;
        return uuid;
    }

    static String hex(byte[] uuid) {
        return HexFormat.of().formatHex(uuid);
    }

    static EbmlWriter.Node video(int number, String codecId, byte[] codecPrivate) {
        var children = new ArrayList<EbmlWriter.Node>(List.of(uint(EbmlIds.TRACK_NUMBER, number),
                uint(EbmlIds.TRACK_UID, number), uint(EbmlIds.TRACK_TYPE, 1), string(EbmlIds.CODEC_ID, codecId),
                uint(EbmlIds.DEFAULT_DURATION, 40_000_000L),
                master(EbmlIds.VIDEO, uint(EbmlIds.PIXEL_WIDTH, 16), uint(EbmlIds.PIXEL_HEIGHT, 16))));
        if (codecPrivate != null) {
            children.add(binary(EbmlIds.CODEC_PRIVATE, codecPrivate));
        }
        return master(EbmlIds.TRACK_ENTRY, children);
    }

    static EbmlWriter.Node audio(int number) {
        return master(EbmlIds.TRACK_ENTRY, uint(EbmlIds.TRACK_NUMBER, number), uint(EbmlIds.TRACK_UID, number),
                uint(EbmlIds.TRACK_TYPE, 2), string(EbmlIds.CODEC_ID, "A_PCM/INT/LIT"),
                master(EbmlIds.AUDIO, floating(EbmlIds.SAMPLING_FREQUENCY, 8000), uint(EbmlIds.CHANNELS, 1),
                        uint(EbmlIds.BIT_DEPTH, 16)));
    }

    static EbmlWriter.Node codedAudio(String codecId, int rate, long defaultDuration, byte[] codecPrivate) {
        var children = new ArrayList<EbmlWriter.Node>(List.of(uint(EbmlIds.TRACK_NUMBER, AUDIO),
                uint(EbmlIds.TRACK_UID, AUDIO), uint(EbmlIds.TRACK_TYPE, 2), string(EbmlIds.CODEC_ID, codecId),
                uint(EbmlIds.DEFAULT_DURATION, defaultDuration),
                master(EbmlIds.AUDIO, floating(EbmlIds.SAMPLING_FREQUENCY, rate), uint(EbmlIds.CHANNELS, 1))));
        if (codecPrivate != null) {
            children.add(binary(EbmlIds.CODEC_PRIVATE, codecPrivate));
        }
        return master(EbmlIds.TRACK_ENTRY, children);
    }

    static byte[] opusHead() {
        var head = new byte[19];
        System.arraycopy("OpusHead".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, head, 0, 8);
        head[8] = 1;
        head[9] = 1;
        // 48000 Hz input rate, little-endian, after a pre-skip of zero
        head[12] = (byte) 0x80;
        head[13] = (byte) 0xBB;
        return head;
    }

    static EbmlWriter.Node tracks(EbmlWriter.Node... entries) {
        return master(EbmlIds.TRACKS, entries);
    }

    static EbmlWriter.Node avTracks() {
        return tracks(video(VIDEO, "V_VP8", null), audio(AUDIO));
    }

    static EbmlWriter.Node info(long durationMs, byte[] uuid, EbmlWriter.Node... extra) {
        var children = new ArrayList<EbmlWriter.Node>(List.of(uint(EbmlIds.TIMESTAMP_SCALE, 1_000_000),
                floating(EbmlIds.DURATION, durationMs)));
        if (uuid != null) {
            children.add(binary(EbmlIds.SEGMENT_UUID, uuid));
        }
        children.addAll(List.of(extra));
        return master(EbmlIds.INFO, children);
    }

    static EbmlWriter.Node prev(byte[] uuid) {
        return binary(EbmlIds.PREV_UUID, uuid);
    }

    static EbmlWriter.Node next(byte[] uuid) {
        return binary(EbmlIds.NEXT_UUID, uuid);
    }

    static List<EbmlWriter.Node> clusters(List<Frame> frames) {
        var clusters = new ArrayList<EbmlWriter.Node>();
        var blocks = new ArrayList<EbmlWriter.Node>();
        var clusterTime = Long.MIN_VALUE;
        for (var frame : frames) {
            if (clusterTime == Long.MIN_VALUE || frame.pts() - clusterTime >= 1000) {
                if (!blocks.isEmpty()) {
                    clusters.add(master(EbmlIds.CLUSTER, blocks));
                }
                blocks = new ArrayList<>();
                clusterTime = frame.pts();
                blocks.add(uint(EbmlIds.TIMESTAMP, clusterTime));
            }
            blocks.add(binary(EbmlIds.SIMPLE_BLOCK, EbmlWriter.block(frame.track(), (int) (frame.pts() - clusterTime),
                    frame.key() ? 0x80 : 0, frame.payload())));
        }
        if (!blocks.isEmpty()) {
            clusters.add(master(EbmlIds.CLUSTER, blocks));
        }
        return clusters;
    }

    static byte[] file(EbmlWriter.Node info, EbmlWriter.Node tracks, EbmlWriter.Node chapters, List<Frame> frames) {
        var children = new ArrayList<EbmlWriter.Node>(List.of(info, tracks));
        if (chapters != null) {
            children.add(chapters);
        }
        children.addAll(clusters(frames));
        return EbmlWriter.file("matroska", false, children.toArray(EbmlWriter.Node[]::new));
    }

    static EbmlWriter.Node chapters(EbmlWriter.Node... editions) {
        return master(EbmlIds.CHAPTERS, editions);
    }

    static EbmlWriter.Node edition(long uid, boolean ordered, boolean isDefault, EbmlWriter.Node... atoms) {
        var children = new ArrayList<EbmlWriter.Node>();
        if (uid != 0) {
            children.add(uint(EbmlIds.EDITION_UID, uid));
        }
        if (ordered) {
            children.add(uint(EbmlIds.EDITION_FLAG_ORDERED, 1));
        }
        if (isDefault) {
            children.add(uint(EbmlIds.EDITION_FLAG_DEFAULT, 1));
        }
        children.addAll(List.of(atoms));
        return master(EbmlIds.EDITION_ENTRY, children);
    }

    static EbmlWriter.Node ordered(EbmlWriter.Node... atoms) {
        return edition(1, true, true, atoms);
    }

    private static long chapterUid = 1;

    static EbmlWriter.Node atom(long startMs, long endMs, String title, EbmlWriter.Node... extra) {
        var children = new ArrayList<EbmlWriter.Node>(List.of(uint(EbmlIds.CHAPTER_UID, chapterUid++),
                uint(EbmlIds.CHAPTER_TIME_START, startMs * 1_000_000)));
        if (endMs >= 0) {
            children.add(uint(EbmlIds.CHAPTER_TIME_END, endMs * 1_000_000));
        }
        if (title != null) {
            children.add(master(EbmlIds.CHAPTER_DISPLAY, string(EbmlIds.CHAP_STRING, title)));
        }
        children.addAll(List.of(extra));
        return master(EbmlIds.CHAPTER_ATOM, children);
    }

    static EbmlWriter.Node link(byte[] uuid) {
        return binary(EbmlIds.CHAPTER_SEGMENT_UUID, uuid);
    }

    static EbmlWriter.Node linkEdition(long uid) {
        return uint(EbmlIds.CHAPTER_SEGMENT_EDITION_UID, uid);
    }

    static Chapter chapter(long startMs, long endMs, String title, Chapter... children) {
        return new Chapter(startMs * 1000, endMs * 1000, Optional.of(title), List.of(new Chapter.Title(title, "eng")),
                List.of(children));
    }

    /**
     * A part of an expected timeline whose bounds lie on frame boundaries.
     */
    record Span(int segment, long start, long end, long offset) {
    }

    /**
     * The packets a timeline of aligned spans presents: for every 40 ms slot of every span, the video then the audio
     * frame, moved to the span's place.
     */
    static List<String> expected(Span... spans) {
        var lines = new ArrayList<String>();
        for (var span : spans) {
            for (var pts = span.start(); pts < span.end(); pts += SLOT) {
                var at = pts - span.start() + span.offset();
                lines.add("v" + span.segment() + "@" + pts + "->" + at);
                lines.add("a" + span.segment() + "@" + pts + "->" + at * 8);
            }
        }
        return lines;
    }

    static String line(Packet packet) {
        var origin = origin(packet);
        return (packet.trackId() == VIDEO ? "v" : "a") + origin.segment() + "@" + origin.pts() + "->" + packet.pts();
    }

    static List<Packet> packets(MatroskaDemuxer demuxer) {
        var packets = new ArrayList<Packet>();
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            packets.add(packet);
        }
        return packets;
    }

    static List<String> lines(List<Packet> packets) {
        return packets.stream().map(MatroskaTimelineTest::line).toList();
    }

    static MatroskaDemuxer open(byte[] data) {
        return new MatroskaDemuxer(MediaSource.of(data));
    }

    static MatroskaDemuxer open(byte[] data, DemuxerOptions options) {
        return new MatroskaDemuxer(MediaSource.of(data), options);
    }

    static void assertMonotonicDts(List<Packet> packets) {
        var last = new HashMap<Integer, Long>();
        for (var packet : packets) {
            if (packet.dts() == Packet.NO_TIMESTAMP) {
                continue;
            }
            var previous = last.put(packet.trackId(), packet.dts());
            assertTrue(previous == null || previous <= packet.dts(), "decoding timestamps decrease at " + packet);
        }
    }

    /**
     * Supplies linked segments from memory and records what it is asked and what it opened.
     */
    static final class Library implements DataReferenceResolver {
        final Map<String, byte[]> segments = new HashMap<>();
        final Map<String, byte[]> successors = new HashMap<>();
        final List<DataReference> asked = new ArrayList<>();
        final List<TrackedSource> opened = new ArrayList<>();

        Library add(byte[] uuid, byte[] file) {
            segments.put(hex(uuid), file);
            return this;
        }

        Library successor(byte[] previous, byte[] file) {
            successors.put(hex(previous), file);
            return this;
        }

        @Override
        public Optional<MediaSource> resolve(DataReference reference) {
            asked.add(reference);
            var file = switch (reference.kind()) {
                case MATROSKA_SEGMENT -> segments.get(reference.location());
                case MATROSKA_NEXT_SEGMENT -> successors.get(reference.location());
                default -> null;
            };
            if (file == null) {
                return Optional.empty();
            }
            var source = new TrackedSource(file);
            opened.add(source);
            return Optional.of(source);
        }

        DemuxerOptions options() {
            return DemuxerOptions.builder().dataReferences(this).build();
        }
    }

    /**
     * An in-memory source that records whether it was closed.
     */
    static final class TrackedSource implements MediaSource {
        private final MediaSource delegate;
        boolean closed;

        TrackedSource(byte[] data) {
            this.delegate = MediaSource.of(data);
        }

        @Override
        public long size() {
            return delegate.size();
        }

        @Override
        public int read(long position, byte[] buffer, int offset, int length) {
            return delegate.read(position, buffer, offset, length);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    static byte[] reordered() {
        return file(info(4000, uuid(1)), avTracks(), chapters(ordered(atom(2000, 3000, "B"), atom(0, 1000, "A"))),
                av(0, 0, 4000));
    }

    @Nested
    @DisplayName("ordered editions")
    class Ordered {
        @Test
        @DisplayName("plays the chapters of the default ordered edition in their stored order")
        void reorders() {
            try (var demuxer = open(reordered())) {
                assertTrue(demuxer.isVirtualTimeline());
                assertEquals(2_000_000, demuxer.durationMicros());
                var video = (VideoTrack) demuxer.tracks().getFirst();
                var audio = (AudioTrack) demuxer.tracks().get(1);
                assertEquals(2000, video.duration());
                assertEquals(16000, audio.duration());
                assertEquals(List.of(new ChapterEdition(List.of(chapter(0, 1000, "B"), chapter(1000, 2000, "A")), true,
                        false, List.of())), demuxer.chapterEditions());
                assertEquals(List.of(chapter(0, 1000, "B"), chapter(1000, 2000, "A")), demuxer.chapters());
                var packets = packets(demuxer);
                assertEquals(expected(new Span(0, 2000, 3000, 0), new Span(0, 0, 1000, 1000)), lines(packets));
                for (var packet : packets) {
                    assertFalse(packet.flags().contains(PacketFlag.DISCARD), packet.toString());
                    assertEquals(packet.pts(), packet.dts());
                }
            }
        }

        @Test
        @DisplayName("presents the segment as stored without the virtual timeline option")
        void stored() {
            try (var demuxer = open(reordered(), DemuxerOptions.builder().virtualTimeline(false).build())) {
                assertFalse(demuxer.isVirtualTimeline());
                assertEquals(4_000_000, demuxer.durationMicros());
                assertEquals(expected(new Span(0, 0, 4000, 0)), lines(packets(demuxer)));
                var edition = demuxer.chapterEditions().getFirst();
                assertTrue(edition.ordered());
                assertEquals(List.of(Chapter.of(2_000_000, 3_000_000, "B").withTitles(List.of(new Chapter.Title("B",
                        "eng"))), Chapter.of(0, 1_000_000, "A").withTitles(List.of(new Chapter.Title("A", "eng")))),
                        edition.chapters());
            }
        }

        @Test
        @DisplayName("reads a source that is read once, front to back, as it reads the whole file")
        void stream() {
            var data = reordered();
            List<String> expected;
            try (var demuxer = open(data)) {
                expected = lines(packets(demuxer));
            }
            try (var source = MediaSource.of(new ByteArrayInputStream(data), data.length);
                 var demuxer = new MatroskaDemuxer(source)) {
                assertTrue(demuxer.isVirtualTimeline());
                assertEquals(expected, lines(packets(demuxer)));
            }
        }

        @Test
        @DisplayName("plays the edition the options name, which becomes the default, and the default one past the last")
        void editionOption() {
            var data = file(info(4000, uuid(1)), avTracks(), chapters(edition(1, false, true, atom(0, 1000, "x")),
                    edition(2, true, false, atom(2000, 2400, "y"))), av(0, 0, 4000));
            try (var demuxer = open(data)) {
                assertFalse(demuxer.isVirtualTimeline());
                assertEquals(2, demuxer.chapterEditions().size());
                assertEquals(4_000_000, demuxer.durationMicros());
            }
            try (var demuxer = open(data, DemuxerOptions.builder().edition(1).build())) {
                assertTrue(demuxer.isVirtualTimeline());
                assertEquals(400_000, demuxer.durationMicros());
                assertEquals(List.of(new ChapterEdition(List.of(chapter(0, 400, "y")), true, false, List.of())),
                        demuxer.chapterEditions());
                assertEquals(expected(new Span(0, 2000, 2400, 0)), lines(packets(demuxer)));
            }
            try (var demuxer = open(data, DemuxerOptions.builder().edition(5).build())) {
                assertFalse(demuxer.isVirtualTimeline());
                assertTrue(demuxer.chapterEditions().getFirst().defaultEdition());
            }
            try (var demuxer = open(data, DemuxerOptions.builder().edition(1).virtualTimeline(false).build())) {
                assertFalse(demuxer.isVirtualTimeline());
                var editions = demuxer.chapterEditions();
                assertFalse(editions.getFirst().defaultEdition());
                assertTrue(editions.get(1).defaultEdition());
                assertTrue(editions.get(1).ordered());
                assertEquals(2_000_000, demuxer.chapters().getFirst().startMicros());
            }
            assertThrows(IllegalArgumentException.class, () -> DemuxerOptions.builder().edition(-1));
            assertEquals(java.util.OptionalInt.empty(), DemuxerOptions.builder().edition(3).defaultEdition().build()
                    .edition());
        }

        @Test
        @DisplayName("plays nested chapters instead of their parent, hidden chapters, and to the end without an end")
        void structure() {
            var parent = atom(0, 9999, "parent", atom(800, 1200, "P1"), atom(0, 400, "P2"));
            var hidden = atom(2000, 2400, "hidden", uint(EbmlIds.CHAPTER_FLAG_HIDDEN, 1));
            var disabled = atom(3000, 3400, "disabled", uint(EbmlIds.CHAPTER_FLAG_ENABLED, 0));
            var open = atom(3600, -1, "open");
            var backwards = atom(3000, 2000, "backwards");
            var empty = atom(100, 100, "empty");
            var data = file(info(4000, uuid(1)), avTracks(), chapters(ordered(parent, hidden, disabled, open,
                    backwards, empty)), av(0, 0, 4000));
            try (var demuxer = open(data)) {
                assertEquals(1_600_000, demuxer.durationMicros());
                assertEquals(List.of(chapter(0, 800, "parent", chapter(0, 400, "P1"), chapter(400, 800, "P2")),
                        chapter(1200, 1600, "open"), chapter(1600, 1600, "backwards"), chapter(1600, 1600, "empty")),
                        demuxer.chapterEditions().getFirst().chapters());
                assertEquals(expected(new Span(0, 800, 1200, 0), new Span(0, 0, 400, 400),
                        new Span(0, 2000, 2400, 800), new Span(0, 3600, 4000, 1200)), lines(packets(demuxer)));
            }
        }

        @Test
        @DisplayName("cuts the frames straddling a part's bounds and decodes the frames before it without showing them")
        void straddles() {
            var data = file(info(4000, uuid(1)), avTracks(), chapters(ordered(atom(2000, 2400, "first"),
                    atom(610, 1010, "second"))), av(0, 0, 4000));
            try (var demuxer = open(data)) {
                assertEquals(800_000, demuxer.durationMicros());
                var packets = packets(demuxer);
                assertMonotonicDts(packets);
                var second = packets.subList(packets.indexOf(packets.stream().filter(packet -> origin(packet).pts()
                        == 400).findFirst().orElseThrow()), packets.size());
                // the video key frame at 400 ms and the frames up to 560 ms only rebuild the decoder
                for (var index = 0; index < 5; index++) {
                    var packet = second.get(index);
                    assertEquals(VIDEO, packet.trackId());
                    assertEquals(400 + index * SLOT, origin(packet).pts());
                    assertTrue(packet.flags().contains(PacketFlag.DISCARD), packet.toString());
                    assertTrue(packet.pts() < 0, packet.toString());
                    assertEquals(400, packet.dts(), "the pre-roll decodes at the start of the part");
                }
                assertTrue(second.getFirst().keyframe());
                var cutVideo = second.get(5);
                assertEquals(new Origin(VIDEO, 0, 600), origin(cutVideo));
                assertEquals(400, cutVideo.pts());
                assertEquals(30, cutVideo.duration());
                var cutAudio = second.get(6);
                assertEquals(new Origin(AUDIO, 0, 600), origin(cutAudio));
                assertEquals(3120, cutAudio.pts());
                assertEquals(80, cutAudio.sideData().discardStart());
                assertEquals(SAMPLES, cutAudio.duration());
                var lastVideo = second.get(second.size() - 2);
                assertEquals(new Origin(VIDEO, 0, 1000), origin(lastVideo));
                assertEquals(790, lastVideo.pts());
                assertEquals(10, lastVideo.duration());
                var lastAudio = second.getLast();
                assertEquals(new Origin(AUDIO, 0, 1000), origin(lastAudio));
                assertEquals(6320, lastAudio.pts());
                assertEquals(240, lastAudio.sideData().discardEnd());
                assertEquals(80, lastAudio.duration());
                var shown = new HashSet<Long>();
                for (var packet : packets) {
                    if (packet.trackId() == VIDEO && !packet.flags().contains(PacketFlag.DISCARD)) {
                        assertTrue(shown.add(packet.pts()), "two presented frames at " + packet.pts());
                        assertTrue(packet.pts() >= 0 && packet.pts() < 800, packet.toString());
                    }
                }
                assertEquals(10 + 11, shown.size());
            }
        }

        @Test
        @DisplayName("keeps the frames past a part's end that earlier-presented frames of a reordered track reference")
        void reorderedEnd() {
            var frames = new ArrayList<Frame>();
            frames.add(new Frame(VIDEO, 0, true, payload(VIDEO, 0, 0, 16)));
            for (var anchor = 120L; anchor < 2000; anchor += 120) {
                frames.add(new Frame(VIDEO, anchor, anchor == 960, payload(VIDEO, 0, anchor, 16)));
                frames.add(new Frame(VIDEO, anchor - 80, false, payload(VIDEO, 0, anchor - 80, 16)));
                frames.add(new Frame(VIDEO, anchor - 40, false, payload(VIDEO, 0, anchor - 40, 16)));
            }
            var data = file(info(2000, uuid(1)), tracks(video(VIDEO, "V_MPEG4/ISO/AVC", new byte[]{1, 2, 3})),
                    chapters(ordered(atom(0, 200, "part"))), frames);
            try (var demuxer = open(data)) {
                var packets = packets(demuxer);
                assertEquals(List.of(0L, 120L, 40L, 80L, 240L, 160L),
                        packets.stream().map(packet -> origin(packet).pts()).toList());
                var referenced = packets.get(4);
                assertTrue(referenced.flags().contains(PacketFlag.DISCARD));
                assertTrue(referenced.pts() < 0);
                for (var packet : packets) {
                    assertEquals(Packet.NO_TIMESTAMP, packet.dts());
                    if (packet != referenced) {
                        assertFalse(packet.flags().contains(PacketFlag.DISCARD));
                        assertEquals(origin(packet).pts(), packet.pts());
                    }
                }
                assertEquals(200_000, demuxer.durationMicros());
            }
        }

        @Test
        @DisplayName("seeks on the timeline to the key frame at or before the time within the part that plays it")
        void seek() {
            try (var demuxer = open(reordered())) {
                demuxer.seek(1_500_000);
                var first = demuxer.read();
                assertEquals(new Origin(VIDEO, 0, 400), origin(first));
                assertEquals(1400, first.pts());
                var audio = demuxer.read();
                while (audio.trackId() != AUDIO) {
                    audio = demuxer.read();
                }
                // the audio block at 480 ms is the last one starting at or before 500 ms
                assertEquals(new Origin(AUDIO, 0, 480), origin(audio));
                assertEquals(11840, audio.pts());
                demuxer.seek(500_000);
                var restart = demuxer.read();
                assertEquals(new Origin(VIDEO, 0, 2400), origin(restart));
                assertEquals(400, restart.pts());
                demuxer.seek(2_000_000);
                assertNull(demuxer.read());
                demuxer.seek(-5);
                assertEquals(expected(new Span(0, 2000, 3000, 0), new Span(0, 0, 1000, 1000)), lines(packets(demuxer)));
            }
        }

        @Test
        @DisplayName("passes the I/O behavior harness over ordered, nested and straddling parts")
        void harness() {
            DemuxerAsserts.of("matroska/ordered-reordered", reordered()).noDump().run();
            var straddling = file(info(4000, uuid(1)), avTracks(), chapters(ordered(atom(2000, 2400, "first"),
                    atom(600, 1010, "second"), atom(0, 400, "third", atom(3000, 3200, "inner")))), av(0, 0, 4000));
            DemuxerAsserts.of("matroska/ordered-straddling", straddling).noDump().run();
        }

        @Test
        @DisplayName("bounds the reading of an edition of many short chapters between seeks")
        void readBudget() {
            var atoms = new ArrayList<EbmlWriter.Node>();
            for (var index = 0; index < 2000; index++) {
                atoms.add(atom(3200, 3240, null));
            }
            var data = file(info(4000, uuid(1)), avTracks(), chapters(ordered(atoms.toArray(EbmlWriter.Node[]::new))),
                    av(0, 0, 4000));
            try (var demuxer = open(data)) {
                assertEquals(80_000_000, demuxer.durationMicros());
                assertThrows(com.github.auties00.smalt.MediaException.LimitExceeded.class, () -> packets(demuxer));
                demuxer.seek(0);
                var first = demuxer.read();
                assertEquals(new Origin(VIDEO, 0, 3200), origin(first));
            }
        }

        @Test
        @DisplayName("reports the visible chapters nested in a hidden chapter in its place")
        void hiddenParent() {
            var parent = atom(0, -1, "hidden", uint(EbmlIds.CHAPTER_FLAG_HIDDEN, 1), atom(800, 1200, "shown"),
                    atom(0, 400, "secret", uint(EbmlIds.CHAPTER_FLAG_HIDDEN, 1)));
            var data = file(info(4000, uuid(1)), avTracks(), chapters(ordered(atom(2000, 2400, "x"), parent)),
                    av(0, 0, 4000));
            try (var demuxer = open(data)) {
                assertEquals(1_200_000, demuxer.durationMicros());
                assertEquals(List.of(chapter(0, 400, "x"), chapter(400, 800, "shown")), demuxer.chapters());
                assertEquals(expected(new Span(0, 2000, 2400, 0), new Span(0, 800, 1200, 400),
                        new Span(0, 0, 400, 800)), lines(packets(demuxer)));
            }
        }

        @Test
        @DisplayName("decodes the audio frame an MP2 track needs before each part without presenting it")
        void audioPreroll() {
            var tracks = tracks(video(VIDEO, "V_VP8", null), codedAudio("A_MPEG/L2", 8000, 40_000_000L, null));
            var data = file(info(4000, uuid(1)), tracks, chapters(ordered(atom(2000, 2400, "first"),
                    atom(610, 1010, "second"))), av(0, 0, 4000));
            try (var demuxer = open(data)) {
                var packets = packets(demuxer);
                assertMonotonicDts(packets);
                var audio = packets.stream().filter(packet -> packet.trackId() == AUDIO).toList();
                var discarded = audio.stream().filter(packet -> packet.flags().contains(PacketFlag.DISCARD)).toList();
                assertEquals(List.of(new Origin(AUDIO, 0, 1960), new Origin(AUDIO, 0, 560)),
                        discarded.stream().map(MatroskaTimelineTest::origin).toList());
                // a pre-roll frame keeps its time on the timeline and comes right before the first frame it serves
                assertEquals(-320, discarded.getFirst().pts());
                assertEquals(new Origin(AUDIO, 0, 2000), origin(audio.get(audio.indexOf(discarded.getFirst()) + 1)));
                assertEquals(2800, discarded.getLast().pts());
                var straddling = audio.get(audio.indexOf(discarded.getLast()) + 1);
                assertEquals(new Origin(AUDIO, 0, 600), origin(straddling));
                assertEquals(80, straddling.sideData().discardStart());
                assertEquals(10 + 11 + 2, audio.size());
                var video = packets.stream().filter(packet -> packet.trackId() == VIDEO
                        && !packet.flags().contains(PacketFlag.DISCARD)).count();
                assertEquals(10 + 11, video);
                // a seek before the timeline gives the packets of a read from the start, pre-roll included
                demuxer.seek(-5);
                assertEquals(lines(packets), lines(packets(demuxer)));
                // a seek to the start of a part restarts the audio at the frame straddling it
                demuxer.seek(400_000);
                var restart = packets(demuxer).stream().filter(packet -> packet.trackId() == AUDIO).findFirst()
                        .orElseThrow();
                assertEquals(line(straddling), line(restart));
                assertEquals(80, restart.sideData().discardStart());
            }
            DemuxerAsserts.of("matroska/ordered-preroll", data).noDump().run();
        }

        @Test
        @DisplayName("decodes the MP3 frames whose bytes the first frame's main data may start in")
        void mp3Preroll() {
            var frames = new ArrayList<Frame>();
            for (var pts = 0L; pts < 2000; pts += SLOT) {
                frames.add(new Frame(VIDEO, pts, pts % 400 == 0, payload(VIDEO, 0, pts, 16)));
                // 104-byte frames hold 66 bytes of main data each, so 511 bytes of reservoir span 8 frames
                frames.add(new Frame(AUDIO, pts, true, payload(AUDIO, 0, pts, 104)));
            }
            var tracks = tracks(video(VIDEO, "V_VP8", null), codedAudio("A_MPEG/L3", 8000, 40_000_000L, null));
            var data = file(info(2000, uuid(1)), tracks, chapters(ordered(atom(1000, 1400, "part"))), frames);
            try (var demuxer = open(data)) {
                var audio = packets(demuxer).stream().filter(packet -> packet.trackId() == AUDIO).toList();
                var discarded = audio.stream().filter(packet -> packet.flags().contains(PacketFlag.DISCARD))
                        .map(packet -> origin(packet).pts()).toList();
                var expected = new ArrayList<Long>();
                for (var pts = 1000 - 9 * SLOT; pts < 1000; pts += SLOT) {
                    expected.add((long) pts);
                }
                assertEquals(expected, discarded);
                assertEquals(9 + 10, audio.size());
            }
        }

        @Test
        @DisplayName("presents no laced frame without timestamp that follows a frame before a part")
        void lacedBefore() {
            var blocks = new ArrayList<EbmlWriter.Node>(List.of(uint(EbmlIds.TIMESTAMP, 0)));
            for (var pts = 0; pts < 2000; pts += SLOT) {
                blocks.add(binary(EbmlIds.SIMPLE_BLOCK, EbmlWriter.block(VIDEO, pts, pts % 400 == 0 ? 0x80 : 0,
                        payload(VIDEO, 0, pts, 16))));
                if (pts % 80 == 0) {
                    // a fixed-size lace of two frames, the second without timestamp since the track declares no
                    // frame duration
                    var lace = new java.io.ByteArrayOutputStream();
                    lace.write(1);
                    lace.writeBytes(payload(AUDIO, 0, pts, 16));
                    lace.writeBytes(payload(AUDIO, 0, pts + SLOT, 16));
                    blocks.add(binary(EbmlIds.SIMPLE_BLOCK, EbmlWriter.block(AUDIO, pts, 0x80 | 0x04,
                            lace.toByteArray())));
                }
            }
            var ac3 = master(EbmlIds.TRACK_ENTRY, uint(EbmlIds.TRACK_NUMBER, AUDIO), uint(EbmlIds.TRACK_UID, AUDIO),
                    uint(EbmlIds.TRACK_TYPE, 2), string(EbmlIds.CODEC_ID, "A_AC3"), master(EbmlIds.AUDIO,
                            floating(EbmlIds.SAMPLING_FREQUENCY, 48000), uint(EbmlIds.CHANNELS, 2)));
            var data = EbmlWriter.file("matroska", false, info(2000, uuid(1)), tracks(video(VIDEO, "V_VP8", null),
                    ac3), chapters(ordered(atom(400, 800, "part"))), master(EbmlIds.CLUSTER, blocks));
            try (var demuxer = open(data)) {
                var audio = packets(demuxer).stream().filter(packet -> packet.trackId() == AUDIO).toList();
                var shown = audio.stream().filter(packet -> !packet.flags().contains(PacketFlag.DISCARD))
                        .map(packet -> origin(packet).pts()).toList();
                var expected = new ArrayList<Long>();
                for (var pts = 400L; pts < 800; pts += SLOT) {
                    expected.add(pts);
                }
                assertEquals(expected, shown);
                assertEquals(new Origin(AUDIO, 0, 320), origin(audio.getFirst()));
                assertTrue(audio.getFirst().flags().contains(PacketFlag.DISCARD));
            }
        }

        @Test
        @DisplayName("decodes the 80 ms of Opus before a part without presenting them")
        void opusPreroll() {
            var frames = new ArrayList<Frame>();
            for (var pts = 0L; pts < 2000; pts += 20) {
                if (pts % SLOT == 0) {
                    frames.add(new Frame(VIDEO, pts, pts % 400 == 0, payload(VIDEO, 0, pts, 16)));
                }
                // the first payload byte, the track number 2, is the TOC of two 10 ms frames
                frames.add(new Frame(AUDIO, pts, true, payload(AUDIO, 0, pts, 40)));
            }
            var tracks = tracks(video(VIDEO, "V_VP8", null), codedAudio("A_OPUS", 48000, 20_000_000L, opusHead()));
            var data = file(info(2000, uuid(1)), tracks, chapters(ordered(atom(1000, 1400, "part"))), frames);
            try (var demuxer = open(data)) {
                var audio = packets(demuxer).stream().filter(packet -> packet.trackId() == AUDIO).toList();
                assertEquals(4 + 20, audio.size());
                for (var index = 0; index < audio.size(); index++) {
                    var packet = audio.get(index);
                    assertEquals(920 + index * 20L, origin(packet).pts());
                    assertEquals(index < 4, packet.flags().contains(PacketFlag.DISCARD), packet.toString());
                    assertEquals((origin(packet).pts() - 1000) * 48, packet.pts());
                }
            }
            DemuxerAsserts.of("matroska/opus-preroll", data).noDump().run();
        }

        @Test
        @DisplayName("presents the segment as stored when no chapter of the ordered edition can play")
        void nothingPlays() {
            var data = file(info(4000, uuid(1)), avTracks(), chapters(ordered(atom(0, 1000, "gone", link(uuid(9))))),
                    av(0, 0, 4000));
            var library = new Library();
            try (var demuxer = open(data, library.options())) {
                assertFalse(demuxer.isVirtualTimeline());
                assertEquals(4_000_000, demuxer.durationMicros());
                assertTrue(demuxer.chapterEditions().getFirst().ordered());
                assertEquals(expected(new Span(0, 0, 4000, 0)), lines(packets(demuxer)));
            }
            assertEquals(List.of(new DataReference(DataReference.Kind.MATROSKA_SEGMENT, hex(uuid(9)), "")),
                    library.asked);
        }
    }

    @Nested
    @DisplayName("linked segments")
    class Linked {
        private static final byte[] MAIN = uuid(1);
        private static final byte[] INTRO = uuid(2);
        private static final byte[] ENDING = uuid(3);
        private static final byte[] MISSING = uuid(9);

        static byte[] main() {
            return file(info(3000, MAIN), avTracks(), chapters(edition(1, true, true,
                    atom(0, 800, "intro", link(INTRO)),
                    atom(0, 2000, "main"),
                    atom(0, -1, "ending", link(ENDING), linkEdition(7)),
                    atom(0, 1000, "missing", link(MISSING)),
                    atom(0, -1, "outro", link(INTRO), linkEdition(9)))), av(0, 0, 3000));
        }

        static byte[] intro() {
            return file(info(1200, INTRO), avTracks(), chapters(edition(9, true, false,
                    atom(400, 1200, "own"), atom(0, -1, "back", link(MAIN), linkEdition(1)))), av(1, 0, 1200));
        }

        static byte[] ending() {
            return file(info(2000, ENDING), avTracks(), chapters(edition(7, true, false, atom(1200, 1600, "late"),
                    atom(0, 400, "early"))), av(2, 0, 2000));
        }

        static Library library() {
            return new Library().add(INTRO, intro()).add(ENDING, ending());
        }

        @Test
        @DisplayName("plays spans and editions of other segments, skipping missing segments and cyclic editions")
        void medium() {
            var library = library();
            try (var demuxer = open(main(), library.options())) {
                assertTrue(demuxer.isVirtualTimeline());
                assertEquals(4_400_000, demuxer.durationMicros());
                assertEquals(List.of(chapter(0, 800, "intro"), chapter(800, 2800, "main"), chapter(2800, 3600, "ending"),
                        chapter(3600, 3600, "missing"), chapter(3600, 4400, "outro")),
                        demuxer.chapters());
                var packets = packets(demuxer);
                assertEquals(expected(new Span(1, 0, 800, 0), new Span(0, 0, 2000, 800), new Span(2, 1200, 1600, 2800),
                        new Span(2, 0, 400, 3200), new Span(1, 400, 1200, 3600)), lines(packets));
                assertMonotonicDts(packets);
                assertEquals(List.of(new DataReference(DataReference.Kind.MATROSKA_SEGMENT, hex(INTRO), ""),
                        new DataReference(DataReference.Kind.MATROSKA_SEGMENT, hex(ENDING), ""),
                        new DataReference(DataReference.Kind.MATROSKA_SEGMENT, hex(MISSING), "")), library.asked);
                assertEquals(2, library.opened.size());
                assertFalse(library.opened.getFirst().closed);
            }
            assertTrue(library.opened.stream().allMatch(source -> source.closed), "linked sources left open");
        }

        @Test
        @DisplayName("ignores a source whose segment is not the one referenced, and plays nothing of a missing one")
        void wrongSegment() {
            var library = new Library().add(INTRO, ending()).add(ENDING, ending());
            try (var demuxer = open(main(), library.options())) {
                assertEquals(expected(new Span(0, 0, 2000, 0), new Span(2, 1200, 1600, 2000), new Span(2, 0, 400, 2400)),
                        lines(packets(demuxer)));
                assertEquals(2_800_000, demuxer.durationMicros());
            }
            assertTrue(library.opened.getFirst().closed, "the mismatching source is closed at once");
            try (var demuxer = open(main())) {
                assertEquals(expected(new Span(0, 0, 2000, 0)), lines(packets(demuxer)));
            }
        }

        @Test
        @DisplayName("follows linked editions eight deep, no deeper, and plays nothing of an edition a segment lacks")
        void depth() {
            for (var depth : List.of(8, 9)) {
                var library = new Library();
                for (var level = 1; level <= depth; level++) {
                    var chapter = level < depth ? atom(0, -1, "level " + level, link(uuid(10 + level + 1)),
                            linkEdition(level + 1)) : atom(400, 800, "leaf");
                    library.add(uuid(10 + level), file(info(1000, uuid(10 + level)), avTracks(),
                            chapters(edition(level, true, true, chapter)), av(level, 0, 1000)));
                }
                var main = file(info(1000, MAIN), avTracks(), chapters(ordered(atom(0, -1, "deep", link(uuid(11)),
                        linkEdition(1)), atom(0, 200, "lacking", link(uuid(11)), linkEdition(99)),
                        atom(0, 200, "short uuid", binary(EbmlIds.CHAPTER_SEGMENT_UUID, new byte[8])))),
                        av(0, 0, 1000));
                try (var demuxer = open(main, library.options())) {
                    // a ChapterSegmentUUID that is not 16 bytes long names no other segment
                    assertEquals(depth == 8 ? expected(new Span(8, 400, 800, 0), new Span(0, 0, 200, 400))
                            : expected(new Span(0, 0, 200, 0)), lines(packets(demuxer)), "depth " + depth);
                }
            }
        }

        @Test
        @DisplayName("bounds the expansion of editions that link each other's editions many times over")
        void exponential() {
            // every chapter of edition k of one segment plays edition k + 1 of the other, 64 times over, so that a
            // full expansion would visit 64^8 chapters, none of which plays anything
            var first = uuid(21);
            var second = uuid(22);
            var library = new Library();
            for (var which : List.of(first, second)) {
                var other = which == first ? second : first;
                var editions = new ArrayList<EbmlWriter.Node>();
                for (var level = 1; level <= 8; level++) {
                    var atoms = new ArrayList<EbmlWriter.Node>();
                    for (var count = 0; count < 64; count++) {
                        atoms.add(level < 8 ? atom(0, -1, null, link(other), linkEdition(level + 1))
                                : atom(0, 0, null));
                    }
                    editions.add(edition(level, true, level == 1, atoms.toArray(EbmlWriter.Node[]::new)));
                }
                library.add(which, file(info(1000, which), avTracks(),
                        chapters(editions.toArray(EbmlWriter.Node[]::new)), av(1, 0, 1000)));
            }
            var main = file(info(1000, MAIN), avTracks(), chapters(ordered(atom(0, -1, "linked", link(first),
                    linkEdition(1)))), av(0, 0, 1000));
            assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
                try (var demuxer = open(main, library.options())) {
                    assertFalse(demuxer.isVirtualTimeline());
                    assertEquals(1_000_000, demuxer.durationMicros());
                }
            });
        }

        @Test
        @DisplayName("seeks into the parts of other segments")
        void seek() {
            try (var demuxer = open(main(), library().options())) {
                demuxer.seek(3_300_000);
                var first = demuxer.read();
                assertEquals(new Origin(VIDEO, 2, 0), origin(first));
                assertEquals(3200, first.pts());
                demuxer.seek(4_000_000);
                var late = demuxer.read();
                assertEquals(new Origin(VIDEO, 1, 800), origin(late));
                assertEquals(4000, late.pts());
            }
        }

        @Test
        @DisplayName("passes the I/O behavior harness with linked segments")
        void harness() {
            DemuxerAsserts.of("matroska/medium-linked", main())
                    .factory(source -> new MatroskaDemuxer(source, library().options())).noDump().run();
        }

        @Test
        @DisplayName("feeds the tracks of a linked segment to the tracks of the same kind and codec")
        void trackMapping() {
            var swapped = tracks(audio(1), video(2, "V_VP8", null), master(EbmlIds.TRACK_ENTRY,
                    uint(EbmlIds.TRACK_NUMBER, 3), uint(EbmlIds.TRACK_UID, 3), uint(EbmlIds.TRACK_TYPE, 0x11),
                    string(EbmlIds.CODEC_ID, "S_TEXT/UTF8")));
            var frames = new ArrayList<Frame>();
            for (var pts = 0L; pts < 400; pts += SLOT) {
                frames.add(new Frame(2, pts, pts == 0, payload(VIDEO, 1, pts, 16)));
                frames.add(new Frame(1, pts, true, payload(AUDIO, 1, pts, SAMPLES * 2)));
                frames.add(new Frame(3, pts, true, payload(3, 1, pts, 12)));
            }
            var linked = file(info(400, INTRO), swapped, null, frames);
            var data = file(info(1000, MAIN), avTracks(), chapters(ordered(atom(0, 400, "linked", link(INTRO)),
                    atom(0, 400, "own"))), av(0, 0, 1000));
            try (var demuxer = open(data, new Library().add(INTRO, linked).options())) {
                assertEquals(expected(new Span(1, 0, 400, 0), new Span(0, 0, 400, 400)), lines(packets(demuxer)));
            }
        }

        @Test
        @DisplayName("signals the codec configuration of each segment where the parts change segment")
        void configuration() {
            var mainData = file(info(800, MAIN), tracks(video(VIDEO, "V_MPEG4/ISO/AVC", new byte[]{1})),
                    chapters(ordered(atom(0, 400, "a", link(INTRO)), atom(0, 400, "b"), atom(400, 800, "c",
                            link(INTRO)))), videoOnly(0, 800));
            var linked = file(info(800, INTRO), tracks(video(VIDEO, "V_MPEG4/ISO/AVC", new byte[]{2})), null,
                    videoOnly(1, 800));
            try (var demuxer = open(mainData, new Library().add(INTRO, linked).options())) {
                var packets = packets(demuxer);
                assertEquals(30, packets.size());
                for (var index = 0; index < packets.size(); index++) {
                    var config = packets.get(index).sideData().newCodecConfig();
                    switch (index) {
                        case 0, 20 -> assertArrayEquals(new byte[]{2}, config.orElseThrow());
                        case 10 -> assertArrayEquals(new byte[]{1}, config.orElseThrow());
                        default -> assertTrue(config.isEmpty(), "packet " + index);
                    }
                }
                demuxer.seek(900_000);
                var first = demuxer.read();
                assertEquals(new Origin(VIDEO, 1, 480), origin(first));
                assertArrayEquals(new byte[]{2}, first.sideData().newCodecConfig().orElseThrow(),
                        "a decoder recreated after a seek uses the track's configuration");
            }
        }

        static List<Frame> videoOnly(int segment, long to) {
            var frames = new ArrayList<Frame>();
            for (var pts = 0L; pts < to; pts += SLOT) {
                frames.add(new Frame(VIDEO, pts, true, payload(VIDEO, segment, pts, 16)));
            }
            return frames;
        }

        static List<Frame> audioOnly(int segment, long to) {
            var frames = new ArrayList<Frame>();
            for (var pts = 0L; pts < to; pts += SLOT) {
                frames.add(new Frame(1, pts, true, payload(AUDIO, segment, pts, SAMPLES * 2)));
            }
            return frames;
        }

        static byte[] pcm(List<Frame> frames, long from, long to) {
            var out = new java.io.ByteArrayOutputStream();
            for (var frame : frames) {
                if (frame.pts() >= from && frame.pts() < to) {
                    out.writeBytes(frame.payload());
                }
            }
            return out.toByteArray();
        }

        @Test
        @DisplayName("transcodes a file on disk along its timeline, linked segments of its directory included")
        void transcode() throws IOException {
            var directory = Scratch.directory("matroska-transcode");
            var introFrames = audioOnly(1, 800);
            var programFrames = audioOnly(0, 1200);
            Files.write(directory.resolve("intro.mka"), file(info(800, INTRO), tracks(audio(1)), null, introFrames));
            var program = directory.resolve("program.mka");
            Files.write(program, file(info(1200, MAIN), tracks(audio(1)), chapters(ordered(
                    atom(0, 400, "intro", link(INTRO)), atom(800, 1200, "late"), atom(0, 400, "early"))),
                    programFrames));
            var expected = new java.io.ByteArrayOutputStream();
            expected.writeBytes(pcm(introFrames, 0, 400));
            expected.writeBytes(pcm(programFrames, 800, 1200));
            expected.writeBytes(pcm(programFrames, 0, 400));
            var wav = directory.resolve("program.wav");
            com.github.auties00.smalt.transcode.Transcoder.transcode(program, wav);
            var samples = new java.io.ByteArrayOutputStream();
            try (var demuxer = com.github.auties00.smalt.container.Containers.openDemuxer(MediaSource.of(wav))) {
                assertEquals(1_200_000, demuxer.durationMicros(), 1000.0);
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    samples.writeBytes(packet.data());
                }
            }
            assertArrayEquals(expected.toByteArray(), samples.toByteArray());
            var mka = directory.resolve("copy.mka");
            com.github.auties00.smalt.transcode.Transcoder.transcode(program, mka,
                    com.github.auties00.smalt.transcode.TranscodeOptions.remux(
                            com.github.auties00.smalt.transcode.OutputContainer.MATROSKA));
            try (var demuxer = open(Files.readAllBytes(mka), DemuxerOptions.builder().virtualTimeline(false).build())) {
                assertEquals(1_200_000, demuxer.durationMicros(), 1000.0);
                var edition = demuxer.chapterEditions().getFirst();
                assertFalse(edition.ordered(), "the copy plays its chapters as stored");
                assertEquals(List.of(0L, 400_000L, 800_000L), edition.chapters().stream()
                        .map(Chapter::startMicros).toList());
                var copied = new java.io.ByteArrayOutputStream();
                for (var packet : packets(demuxer)) {
                    copied.writeBytes(packet.data());
                }
                assertArrayEquals(expected.toByteArray(), copied.toByteArray());
            }
        }

        @Test
        @DisplayName("opens linked segments from the directory of a file on disk")
        void directory() throws IOException {
            var directory = Scratch.directory("matroska-linked");
            Files.write(directory.resolve("main.mkv"), main());
            Files.write(directory.resolve("b-intro.mkv"), intro());
            Files.write(directory.resolve("c-ending.mka"), ending());
            Files.write(directory.resolve("a-not-matroska.mkv"), new byte[]{1, 2, 3, 4, 5});
            Files.write(directory.resolve("ending-copy.txt"), ending());
            try (var demuxer = MatroskaDemuxer.open(directory.resolve("main.mkv"))) {
                assertEquals(4_400_000, demuxer.durationMicros());
                assertEquals(expected(new Span(1, 0, 800, 0), new Span(0, 0, 2000, 800), new Span(2, 1200, 1600, 2800),
                        new Span(2, 0, 400, 3200), new Span(1, 400, 1200, 3600)), lines(packets(demuxer)));
            }
            assertEquals("intro.mkv", new DataReference(DataReference.Kind.MATROSKA_SEGMENT, hex(INTRO),
                    "D:\\anime\\intro.mkv").fileName());
            assertEquals("intro.mkv", new DataReference(DataReference.Kind.MATROSKA_NEXT_SEGMENT, hex(INTRO),
                    "../intro.mkv").fileName());
            var resolver = DataReferenceResolver.directory(directory);
            try (var found = resolver.resolve(new DataReference(DataReference.Kind.MATROSKA_SEGMENT, hex(ENDING),
                    "main.mkv")).orElseThrow()) {
                assertEquals(ending().length, found.size(), "a file name hint naming another segment is ignored");
            }
            assertTrue(resolver.resolve(new DataReference(DataReference.Kind.MATROSKA_SEGMENT, hex(MISSING), ""))
                    .isEmpty());
            // no file names the main segment as the one before it
            assertTrue(resolver.resolve(new DataReference(DataReference.Kind.MATROSKA_NEXT_SEGMENT, hex(MAIN), ""))
                    .isEmpty());
            // deleting the files proves the demuxer closed them
            try (var listing = Files.list(directory)) {
                for (var path : listing.toList()) {
                    Files.delete(path);
                }
            }
        }
    }

    @Nested
    @DisplayName("hard links")
    class Hard {
        private static final byte[] FIRST = uuid(1);
        private static final byte[] MIDDLE = uuid(2);
        private static final byte[] LAST = uuid(3);

        static byte[] segment(int index, byte[] uuid, EbmlWriter.Node chapters, EbmlWriter.Node... links) {
            return file(info(1000, uuid, links), avTracks(), chapters, av(index, index * 1000L, index * 1000L + 1000));
        }

        @Test
        @DisplayName("plays the chain from its first segment through the segments the links name")
        void chain() {
            var first = segment(0, FIRST, chapters(edition(1, false, true, atom(0, 1000, "first"))), next(MIDDLE));
            var middle = segment(1, MIDDLE, null, prev(FIRST), next(LAST),
                    string(EbmlIds.PREV_FILENAME, "first.mkv"));
            var last = segment(2, LAST, chapters(edition(1, false, true, atom(2000, 3000, "last"))), prev(MIDDLE));
            var library = new Library().add(FIRST, first).add(LAST, last);
            try (var demuxer = open(middle, library.options())) {
                assertTrue(demuxer.isVirtualTimeline());
                assertEquals(3_000_000, demuxer.durationMicros());
                var packets = packets(demuxer);
                assertEquals(expected(new Span(0, 0, 1000, 0), new Span(1, 1000, 2000, 1000),
                        new Span(2, 2000, 3000, 2000)), lines(packets));
                assertMonotonicDts(packets);
                assertEquals(List.of(chapter(0, 1000, "first"), chapter(2000, 3000, "last")), demuxer.chapters());
                demuxer.seek(2_500_000);
                var resumed = demuxer.read();
                assertEquals(new Origin(VIDEO, 2, 2400), origin(resumed));
                assertEquals(2400, resumed.pts());
                // the middle segment starts between key frames, so a time before its first one needs the first segment
                demuxer.seek(1_100_000);
                var continued = demuxer.read();
                assertEquals(new Origin(VIDEO, 0, 800), origin(continued));
                assertEquals(800, continued.pts());
            }
            assertEquals(List.of(new DataReference(DataReference.Kind.MATROSKA_SEGMENT, hex(FIRST), "first.mkv"),
                    new DataReference(DataReference.Kind.MATROSKA_SEGMENT, hex(LAST), ""),
                    new DataReference(DataReference.Kind.MATROSKA_NEXT_SEGMENT, hex(LAST), "")), library.asked);
            DemuxerAsserts.of("matroska/hard-linked", middle).factory(source -> new MatroskaDemuxer(source,
                    new Library().add(FIRST, first).add(LAST, last).options())).noDump().run();
        }

        @Test
        @DisplayName("plays a chain from a source read once whose first key frame lies past the read buffer")
        void stream() {
            var first = large(0, FIRST, next(MIDDLE));
            var middle = large(1, MIDDLE, prev(FIRST), next(LAST));
            var last = large(2, LAST, prev(MIDDLE));
            var spans = expected(new Span(0, 0, 1000, 0), new Span(1, 1000, 2000, 1000), new Span(2, 2000, 3000, 2000));
            try (var source = MediaSource.of(new ByteArrayInputStream(middle), middle.length);
                 var demuxer = new MatroskaDemuxer(source, new Library().add(FIRST, first).add(LAST, last).options())) {
                assertTrue(demuxer.isVirtualTimeline());
                assertEquals(spans, lines(packets(demuxer)));
            }
        }

        // video frames of 96 KiB, so that the frames before the first key frame of the middle segment (at 1200 ms)
        // outgrow the demuxer's read buffer
        static byte[] large(int index, byte[] uuid, EbmlWriter.Node... links) {
            var frames = new ArrayList<Frame>();
            for (var pts = index * 1000L; pts < index * 1000L + 1000; pts += SLOT) {
                frames.add(new Frame(VIDEO, pts, pts % 400 == 0, payload(VIDEO, index, pts, 96 << 10)));
                frames.add(new Frame(AUDIO, pts, true, payload(AUDIO, index, pts, SAMPLES * 2)));
            }
            return file(info(1000, uuid, links), avTracks(), null, frames);
        }

        @Test
        @DisplayName("follows segments that name only their predecessor, and presents an unlinked segment alone")
        void previousOnly() {
            var first = segment(0, FIRST, null);
            var middle = segment(1, MIDDLE, null, prev(FIRST));
            var last = segment(2, LAST, null, prev(MIDDLE));
            var library = new Library().add(FIRST, first).successor(MIDDLE, last);
            try (var demuxer = open(middle, library.options())) {
                assertEquals(expected(new Span(0, 0, 1000, 0), new Span(1, 1000, 2000, 1000),
                        new Span(2, 2000, 3000, 2000)), lines(packets(demuxer)));
            }
            var unlinked = new Library().successor(FIRST, middle);
            try (var demuxer = open(first, unlinked.options())) {
                assertFalse(demuxer.isVirtualTimeline());
                assertEquals(expected(new Span(0, 0, 1000, 0)), lines(packets(demuxer)));
            }
            assertTrue(unlinked.asked.isEmpty());
        }

        @Test
        @DisplayName("lets a NextUUID take precedence, and stops at a cycle")
        void precedence() {
            var other = uuid(7);
            var stranger = segment(0, other, null, next(uuid(8)));
            var middle = segment(1, MIDDLE, null, prev(other), next(FIRST));
            var cyclic = segment(2, FIRST, null, next(MIDDLE));
            var library = new Library().add(other, stranger).add(FIRST, cyclic);
            try (var demuxer = open(middle, library.options())) {
                assertEquals(expected(new Span(1, 1000, 2000, 1000), new Span(2, 2000, 3000, 2000)),
                        lines(packets(demuxer)));
            }
        }

        @Test
        @DisplayName("lets an ordered edition supersede the hard links")
        void superseded() {
            var middle = segment(1, MIDDLE, chapters(ordered(atom(1200, 1600, "part"))), prev(FIRST), next(LAST));
            var library = new Library().add(FIRST, segment(0, FIRST, null)).add(LAST, segment(2, LAST, null));
            try (var demuxer = open(middle, library.options())) {
                assertEquals(expected(new Span(1, 1200, 1600, 0)), lines(packets(demuxer)));
            }
            assertTrue(library.asked.isEmpty());
        }

        @Test
        @DisplayName("presents a segment whose links resolve to nothing as stored")
        void unresolved() {
            var middle = segment(1, MIDDLE, null, prev(FIRST), next(LAST));
            try (var demuxer = open(middle)) {
                assertFalse(demuxer.isVirtualTimeline());
                assertEquals(expected(new Span(1, 1000, 2000, 1000)), lines(packets(demuxer)));
            }
        }
    }

    @Nested
    @DisplayName("seeking")
    class Seeking {
        /**
         * Builds a file whose clusters start at the video key frames, as muxers write them, with audio blocks of
         * 100 ms that start 50 ms after each tenth of a second, so that the block straddling a key frame lies in the
         * cluster before it.
         */
        static byte[] keyClusters(EbmlWriter.Node chapters) {
            return keyClusters(chapters, 400);
        }

        static byte[] keyClusters(EbmlWriter.Node chapters, int keyEvery) {
            var frames = new ArrayList<Frame>();
            var audio = 50L;
            for (var pts = 0L; pts < 2000; pts += SLOT) {
                frames.add(new Frame(VIDEO, pts, pts % keyEvery == 0, payload(VIDEO, 0, pts, 16)));
                if (audio < pts + SLOT) {
                    frames.add(new Frame(AUDIO, audio, true, payload(AUDIO, 0, audio, 1600)));
                    audio += 100;
                }
            }
            var clusters = new ArrayList<EbmlWriter.Node>();
            var blocks = new ArrayList<EbmlWriter.Node>();
            var clusterTime = 0L;
            for (var frame : frames) {
                if (frame.track() == VIDEO && frame.key()) {
                    if (!blocks.isEmpty()) {
                        clusters.add(master(EbmlIds.CLUSTER, blocks));
                    }
                    clusterTime = frame.pts();
                    blocks = new ArrayList<>(List.of(uint(EbmlIds.TIMESTAMP, clusterTime)));
                }
                blocks.add(binary(EbmlIds.SIMPLE_BLOCK, EbmlWriter.block(frame.track(), (int) (frame.pts()
                        - clusterTime), frame.key() ? 0x80 : 0, frame.payload())));
            }
            clusters.add(master(EbmlIds.CLUSTER, blocks));
            var children = new ArrayList<EbmlWriter.Node>(List.of(info(2000, uuid(1)), avTracks()));
            if (chapters != null) {
                children.add(chapters);
            }
            children.addAll(clusters);
            return EbmlWriter.file("matroska", false, children.toArray(EbmlWriter.Node[]::new));
        }

        static Packet firstAudio(MatroskaDemuxer demuxer) {
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                if (packet.trackId() == AUDIO) {
                    return packet;
                }
            }
            throw new AssertionError("no audio");
        }

        @Test
        @DisplayName("restarts the audio at the block straddling a key frame, stored in the cluster before it")
        void straddlingAudio() {
            try (var demuxer = open(keyClusters(null))) {
                demuxer.seek(420_000);
                var first = demuxer.read();
                var second = demuxer.read();
                assertEquals(new Origin(AUDIO, 0, 350), origin(first));
                assertEquals(new Origin(VIDEO, 0, 400), origin(second));
            }
            DemuxerAsserts.of("matroska/key-clusters", keyClusters(null)).noDump().run();
        }

        @Test
        @DisplayName("restarts the audio at its straddling block when every video frame starts a cluster")
        void clusterPerFrame() {
            try (var demuxer = open(keyClusters(null, SLOT))) {
                demuxer.seek(420_000);
                var packets = new ArrayList<Origin>();
                for (var count = 0; count < 3; count++) {
                    packets.add(origin(demuxer.read()));
                }
                // the block at 350 ms lies in the cluster of the frame at 320 ms, three clusters before the target's
                assertEquals(List.of(new Origin(AUDIO, 0, 350), new Origin(VIDEO, 0, 400), new Origin(VIDEO, 0, 440)),
                        packets);
            }
            DemuxerAsserts.of("matroska/cluster-per-frame", keyClusters(null, SLOT)).noDump().run();
        }

        @Test
        @DisplayName("starts a part at the audio block straddling its start, stored before the key frame's cluster")
        void partStart() {
            var data = keyClusters(chapters(ordered(atom(420, 800, "part"))));
            try (var demuxer = open(data)) {
                var audio = firstAudio(demuxer);
                assertEquals(new Origin(AUDIO, 0, 350), origin(audio));
                assertEquals(-560, audio.pts());
                assertEquals(560, audio.sideData().discardStart());
            }
            DemuxerAsserts.of("matroska/key-clusters-part", data).noDump().run();
        }
    }

    @Nested
    @DisplayName("segment probe")
    class Probe {
        @Test
        @DisplayName("reads the SegmentUUID and PrevUUID of the first segment and nothing of other inputs")
        void identifiers() {
            var data = file(info(1000, uuid(4), prev(uuid(5))), avTracks(), null, av(0, 0, 400));
            var identifiers = MatroskaSegmentProbe.probe(MediaSource.of(data));
            assertNotNull(identifiers);
            assertEquals(hex(uuid(4)), identifiers.segmentUuid());
            assertEquals(hex(uuid(5)), identifiers.prevUuid());
            assertNull(MatroskaSegmentProbe.probe(MediaSource.of(new byte[0])));
            assertNull(MatroskaSegmentProbe.probe(MediaSource.of(Arrays.copyOf(data, 30))));
            assertNull(MatroskaSegmentProbe.probe(MediaSource.of(new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3,
                    (byte) 0xFF})));
            var clustersFirst = EbmlWriter.file("matroska", false, clusters(av(0, 0, 80)).getFirst(),
                    info(80, uuid(4)), avTracks());
            assertNull(MatroskaSegmentProbe.probe(MediaSource.of(clustersFirst)));
            var noUuid = MatroskaSegmentProbe.probe(MediaSource.of(file(info(1000, null), avTracks(), null,
                    av(0, 0, 80))));
            assertNotNull(noUuid);
            assertNull(noUuid.segmentUuid());
        }
    }
}
