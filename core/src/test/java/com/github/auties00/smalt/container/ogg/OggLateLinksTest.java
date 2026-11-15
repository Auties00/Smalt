package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.io.StreamSourceOptions;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.audio.ProbedPacket;
import com.github.auties00.smalt.testkit.container.DemuxDump;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;
import java.util.function.Supplier;

import static com.github.auties00.smalt.container.ogg.OggChainedTest.concat;
import static com.github.auties00.smalt.container.ogg.OggChainedTest.mux;
import static com.github.auties00.smalt.container.ogg.OggChainedTest.opusPackets;
import static com.github.auties00.smalt.container.ogg.OggChainedTest.opusTrack;
import static com.github.auties00.smalt.container.ogg.OggChainedTest.readAll;
import static com.github.auties00.smalt.container.ogg.OggChainedTest.tags;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the links of a chained Ogg stream that construction cannot describe (a stream read once whose size is
 * unknown or does not fit its memory budget), which {@link OggDemuxer#read()} describes as new tracks: the track list
 * grows only by appending, every packet's track is listed when it is returned, and packets, tracks, links, tags and
 * chapters match a read of the same bytes from memory, where construction describes every link; seeks by reading
 * into and across such links match a seek of the memory read. The ffmpeg-made chains are also checked link by link
 * against ffprobe on each link alone.
 */
@DisplayName("Ogg links found while reading")
class OggLateLinksTest {
    /** Opens a stream read once whose length is unknown. */
    static MediaSource unsized(byte[] file) {
        return MediaSource.of(new ByteArrayInputStream(file), -1);
    }

    /** Opens a stream read once of known length whose memory budget is far below its length. */
    static MediaSource overBudget(byte[] file) {
        return MediaSource.of(new ByteArrayInputStream(file), file.length,
                StreamSourceOptions.builder().memoryOnly(false).memoryBudget(64 * 1024).build());
    }

    /** What a read through a demuxer saw. */
    record Read(List<Track> opening, List<Packet> packets, List<Track> tracks, List<OggLink> links,
                Map<Integer, Map<String, String>> trackTags, List<Chapter> chapters, long durationMicros) {
    }

    /**
     * Reads every packet, checking after each one that its track is listed and that the list only grew by appending.
     */
    static Read read(Supplier<MediaSource> source) {
        try (var input = source.get(); var demuxer = new OggDemuxer(input)) {
            var opening = demuxer.tracks();
            var listed = opening;
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                var current = demuxer.tracks();
                assertTrue(current.size() >= listed.size(), "the track list shrank");
                assertEquals(listed, current.subList(0, listed.size()), "a listed track changed");
                var id = packet.trackId();
                assertTrue(current.stream().anyMatch(track -> track.id() == id), "packet of unlisted track " + id);
                listed = current;
                packets.add(packet);
            }
            var tags = new HashMap<Integer, Map<String, String>>();
            for (var track : demuxer.tracks()) {
                tags.put(track.id(), demuxer.trackMetadata(track.id()));
            }
            return new Read(opening, packets, demuxer.tracks(), demuxer.links(), tags, demuxer.chapters(),
                    demuxer.durationMicros());
        }
    }

    /** Returns a track without its duration, which only a read that knows the size of the stream reports. */
    static Track withoutDuration(Track track) {
        return track instanceof AudioTrack audio ? audio.toBuilder().duration(-1).build() : track;
    }

    /**
     * Checks that a read of a stream whose later links are found while reading matches the read from memory, in which
     * construction describes every link.
     */
    static void assertMatchesMemory(byte[] file, Read late) {
        var reference = read(() -> MediaSource.of(file));
        assertTrue(reference.links().size() > 1, "the reference is not chained");
        assertEquals(reference.links().getFirst().trackIds().size(), late.opening().size(),
                "only the first link is described at construction");
        assertEquals(DemuxDump.lines(reference.packets()), DemuxDump.lines(late.packets()));
        for (var index = 0; index < reference.packets().size(); index++) {
            assertEquals(ProbedPacket.crc32(reference.packets().get(index).data()),
                    ProbedPacket.crc32(late.packets().get(index).data()));
        }
        assertEquals(reference.tracks().size(), late.tracks().size());
        for (var index = 0; index < reference.tracks().size(); index++) {
            assertEquals(withoutDuration(reference.tracks().get(index)), withoutDuration(late.tracks().get(index)));
            assertEquals(-1, late.tracks().get(index).duration(), "a link of a stream of unknown end has no duration");
        }
        assertEquals(reference.trackTags(), late.trackTags());
        assertEquals(reference.links().size(), late.links().size());
        for (var index = 0; index < reference.links().size(); index++) {
            var expected = reference.links().get(index);
            var actual = late.links().get(index);
            assertEquals(expected.startMicros(), actual.startMicros(), "start of link " + index);
            assertEquals(expected.trackIds(), actual.trackIds());
            assertEquals(expected.metadata(), actual.metadata());
            assertEquals(expected.pictures().size(), actual.pictures().size());
            assertEquals(-1, actual.durationMicros());
            assertEquals(expected.chapters().stream().map(Chapter::startMicros).toList(),
                    actual.chapters().stream().map(Chapter::startMicros).toList());
        }
        assertEquals(-1, late.durationMicros());
    }

    /** Three Opus links of two, one and two channels, the last grouping two streams. */
    static byte[] opusChain() {
        var first = firstLink();
        var second = mux(51, opusTrack(1), opusPackets(80, 0xF8, 70, 2), tags("two"));
        var third = groupedLink(61);
        return concat(List.of(first, second, third));
    }

    /** The first link of {@link #opusChain()}: two-channel Opus with two chapters. */
    static byte[] firstLink() {
        return mux(41, opusTrack(2), opusPackets(120, 0xFC, 90, 1), tags("one").toBuilder()
                .chapters(List.of(Chapter.of(0, 1_000_000, "a"), Chapter.of(1_000_000, 2_400_000, "b"))).build());
    }

    /** A link of two Opus streams of different packet durations, interleaved in one group. */
    static byte[] groupedLink(int serial) {
        var output = new ByteArrayOutputStream();
        try (var muxer = new OggMuxer(MediaSink.of(output), OggMuxerOptions.builder().serial(serial).build())) {
            var left = opusTrack(1);
            var right = opusTrack(2).toBuilder().id(1).build();
            muxer.addTrack(left);
            muxer.addTrack(right);
            muxer.setTags(tags("three", "ARTIST", "grouped"));
            var a = opusPackets(60, 0xF8, 50, 3);
            var b = opusPackets(30, 0xFC, 60, 4);
            var i = 0;
            var j = 0;
            while (i < a.size() || j < b.size()) {
                if (j >= b.size() || i < a.size() && a.get(i).pts() <= b.get(j).pts()) {
                    muxer.writePacket(a.get(i++));
                } else {
                    var packet = b.get(j++);
                    muxer.writePacket(packet.withTrackId(1));
                }
            }
            muxer.finish();
        }
        return output.toByteArray();
    }

    @Nested
    @DisplayName("a stream of unknown size read once")
    class Unsized {
        @Test
        @DisplayName("describes every later link while reading, as a read from memory describes it")
        void opus() {
            var file = opusChain();
            var late = read(() -> unsized(file));
            assertEquals(1, late.opening().size());
            assertEquals(4, late.tracks().size());
            assertEquals(3, late.links().size());
            assertEquals(List.of(2, 3), late.links().get(2).trackIds());
            // both streams of the grouped link carry the comment, which the link's tags join
            assertEquals("grouped; grouped", late.links().get(2).metadata().get("ARTIST"));
            assertMatchesMemory(file, late);
        }

        @Test
        @DisplayName("continues a link's timeline where the previous link ends")
        void timeline() {
            var file = opusChain();
            var late = read(() -> unsized(file));
            var ends = new HashMap<Integer, Long>();
            var starts = new HashMap<Integer, Long>();
            for (var packet : late.packets()) {
                var micros = OggChainedTest.OPUS.toMicros(packet.pts());
                starts.putIfAbsent(packet.trackId(), micros);
                ends.merge(packet.trackId(), OggChainedTest.OPUS.toMicros(packet.pts() + packet.duration()), Math::max);
            }
            // 120 packets of 20 ms, then 80 of 20 ms, less the Opus pre-skip of each link
            var reference = read(() -> MediaSource.of(file));
            for (var link = 1; link < 3; link++) {
                assertEquals(reference.links().get(link).startMicros(), late.links().get(link).startMicros());
            }
            assertTrue(Math.abs(late.links().get(1).startMicros() - 2_400_000) <= 2 * 6_500);
            assertTrue(Math.abs(late.links().get(2).startMicros() - 4_000_000) <= 4 * 6_500);
            // the first packet of each later link starts the Opus pre-skip of opusTrack (312 samples) before the
            // link, whose start is the first sample played; as in the memory read, the shift is in the timestamps
            // and the track itself skips nothing
            for (var trackId = 1; trackId <= 2; trackId++) {
                assertEquals(0, ((AudioTrack) late.tracks().get(trackId)).skipSamples());
                assertEquals(late.links().get(trackId).startMicros(),
                        starts.get(trackId) + OggChainedTest.OPUS.toMicros(312));
            }
            assertTrue(ends.get(0) <= late.links().get(1).startMicros());
        }

        @Test
        @DisplayName("describes the later links of a stream whose length is known but exceeds its memory budget")
        void overBudgetChain() {
            var parts = new ArrayList<byte[]>();
            for (var link = 0; link < 3; link++) {
                parts.add(mux(70 + link, opusTrack(1), opusPackets(400, 0xF8, 900, 10 + link), tags("x" + link)));
            }
            var file = concat(parts);
            var late = read(() -> overBudget(file));
            assertEquals(1, late.opening().size());
            assertMatchesMemory(file, late);
        }
    }

    @Nested
    @DisplayName("malformed later links")
    class Malformed {
        /** Reads every packet a demuxer gives, tolerating a media failure, with the checks of {@link #read}. */
        static List<Packet> tolerant(Supplier<MediaSource> source) {
            try {
                return read(source).packets();
            } catch (MediaException exception) {
                return null;
            }
        }

        @Test
        @DisplayName("reads a chain cut anywhere in its later links as the memory read of the same bytes")
        void truncated() {
            var file = opusChain();
            var second = firstLink().length;
            var checked = 0;
            // every cut inside the first pages of the second link, where its headers are, then coarser steps
            for (var cut = second + 1; cut < file.length; cut += cut < second + 400 ? 7 : 997) {
                var bytes = Arrays.copyOf(file, cut);
                var late = tolerant(() -> unsized(bytes));
                var reference = tolerant(() -> MediaSource.of(bytes));
                if (late != null && reference != null) {
                    assertEquals(DemuxDump.lines(reference), DemuxDump.lines(late), "cut at " + cut);
                    checked++;
                }
            }
            assertTrue(checked > 10, "too few cuts read cleanly: " + checked);
        }

        @Test
        @DisplayName("keeps the track list growing only by appending when later links are corrupted")
        void corrupted() {
            var file = opusChain();
            var random = new SplittableRandom(17);
            for (var round = 0; round < 60; round++) {
                var bytes = file.clone();
                var start = file.length / 3;
                for (var flip = 0; flip < 1 + round % 6; flip++) {
                    var at = start + random.nextInt(file.length - start);
                    bytes[at] ^= (byte) (1 + random.nextInt(255));
                }
                // read checks the invariants after every packet; only a media failure may end the read early
                tolerant(() -> unsized(bytes));
            }
        }
    }

    @Nested
    @DisplayName("seeking by reading")
    class Seeking {
        /** The packets of every track after a seek, as the memory read gives them. */
        static Map<Integer, List<String>> afterSeek(Supplier<MediaSource> source, long[] targets) {
            try (var input = source.get(); var demuxer = new OggDemuxer(input)) {
                for (var index = 0; index < targets.length - 1; index++) {
                    demuxer.seek(targets[index]);
                    for (var count = 0; count < 5; count++) {
                        demuxer.read();
                    }
                }
                demuxer.seek(targets[targets.length - 1]);
                var result = new HashMap<Integer, List<String>>();
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    result.computeIfAbsent(packet.trackId(), _ -> new ArrayList<>()).addAll(DemuxDump.lines(List.of(packet)));
                }
                return result;
            }
        }

        @ParameterizedTest(name = "to {0} us")
        @ValueSource(longs = {-1, 0, 500_000, 2_399_999, 2_400_000, 2_410_000, 3_000_000, 3_999_999, 4_000_000,
                4_500_000, 5_150_000, 60_000_000})
        @DisplayName("lands in a later link as a seek of the memory read does")
        void intoLaterLinks(long target) {
            var file = opusChain();
            var expected = afterSeek(() -> MediaSource.of(file), new long[]{target});
            assertEquals(expected, afterSeek(() -> unsized(file), new long[]{target}));
            assertEquals(target < 5_200_000, !expected.isEmpty());
        }

        @Test
        @DisplayName("seeks forward several times across links found while reading")
        void forwardSequence() {
            var file = opusChain();
            var targets = new long[]{1_000_000, 2_500_000, 4_300_000};
            var expected = afterSeek(() -> MediaSource.of(file), targets);
            assertEquals(expected, afterSeek(() -> unsized(file), targets));
            assertFalse(expected.isEmpty());
        }

        @Test
        @DisplayName("drops every track of the links before the target link")
        void dropsEarlierLinks() {
            var file = opusChain();
            var packets = afterSeek(() -> unsized(file), new long[]{4_300_000});
            assertEquals(new HashSet<>(List.of(2, 3)), packets.keySet());
        }
    }

    @Nested
    @DisplayName("chains of real codecs")
    class Codecs {
        /** Encodes one link of a chain with ffmpeg, its streams numbered from a distinct serial. */
        static Path link(String codec, int serial, int rate, int channels, double seconds, int frequency) {
            return Fixtures.tone("ogg", frequency, rate, channels, seconds, List.of("-c:a", codec,
                    "-serial_offset", Integer.toString(serial)));
        }

        static List<Path> links() {
            return List.of(link("libvorbis", 100, 44_100, 2, 1.3, 440), link("libopus", 200, 48_000, 1, 1.1, 660),
                    link("flac", 300, 32_000, 2, 0.9, 880));
        }

        @Test
        @DisplayName("reads a Vorbis, Opus and FLAC chain made by ffmpeg exactly as from memory")
        void ffmpegChain() {
            var parts = links();
            var bytes = new ArrayList<byte[]>();
            parts.forEach(part -> bytes.add(Fixtures.read(part)));
            var file = concat(bytes);
            var late = read(() -> unsized(file));
            assertEquals(3, late.tracks().size());
            assertMatchesMemory(file, late);
            assertMatchesProbe(parts, late);
        }

        @Test
        @DisplayName("reads the same chain remuxed by the Ogg muxer exactly as from memory")
        void smaltChain() {
            var parts = links();
            var bytes = new ArrayList<byte[]>();
            for (var index = 0; index < parts.size(); index++) {
                try (var demuxer = new OggDemuxer(MediaSource.of(Fixtures.read(parts.get(index))))) {
                    bytes.add(mux(1000 + index, demuxer.tracks().getFirst(), readAll(demuxer), tags("s" + index)));
                }
            }
            var file = concat(bytes);
            var late = read(() -> unsized(file));
            assertMatchesMemory(file, late);
        }

        /**
         * Checks every link against ffprobe on that link alone: the same packet payloads and durations, and
         * timestamps shifted by where the link starts.
         */
        static void assertMatchesProbe(List<Path> parts, Read late) {
            for (var index = 0; index < parts.size(); index++) {
                var track = late.tracks().get(index);
                var shift = Rational.MICROSECONDS.rescale(late.links().get(index).startMicros(), track.timeBase());
                var ours = new ArrayList<ProbedPacket>();
                for (var packet : late.packets()) {
                    if (packet.trackId() == track.id()) {
                        var pts = packet.pts() == Packet.NO_TIMESTAMP ? ProbedPacket.NONE : packet.pts() - shift;
                        ours.add(new ProbedPacket(pts, packet.duration(), packet.size(), ProbedPacket.crc32(packet.data())));
                    }
                }
                assertEquals(ProbedPacket.probe(parts.get(index)), ours, "link " + index + " (" + track.codec() + ")");
            }
        }
    }
}
