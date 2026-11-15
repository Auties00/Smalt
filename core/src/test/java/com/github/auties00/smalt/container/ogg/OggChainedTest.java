package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.AttachedPicture;
import com.github.auties00.smalt.container.Chapter;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.metadata.MediaTags;
import com.github.auties00.smalt.metadata.TagEditor;
import com.github.auties00.smalt.metadata.TagWriteOptions;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.audio.AudioMutations;
import com.github.auties00.smalt.testkit.audio.UnsizedSource;
import com.github.auties00.smalt.testkit.container.CountingSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers chained Ogg files built by concatenating {@link OggMuxer} outputs: the per-link
 * description, seeking into every link (by a full scan for a small file and by bisection for one
 * above the scan limit) compared with a linear read, seeking sources of unknown size, tag edits of
 * every link, and robustness against damaged chains. Every expectation comes from a linear read or
 * from each link demuxed alone.
 */
@DisplayName("chained Ogg")
class OggChainedTest {
    static final Rational OPUS = Rational.timeBaseOf(48_000);

    /** Builds the packets of a synthetic Opus link: each packet has a TOC byte and random payload. */
    static List<Packet> opusPackets(int count, int toc, int payload, long seed) {
        var random = new SplittableRandom(seed);
        var samples = (toc & 0xF8) == 0x18 ? 2880 : 960;
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < count; index++) {
            var data = new byte[payload];
            random.nextBytes(data);
            data[0] = (byte) toc;
            packets.add(new Packet(0, data, (long) index * samples, (long) index * samples, samples, true));
        }
        return packets;
    }

    static AudioTrack opusTrack(int channels) {
        return AudioTrack.builder().id(0).codec(Codec.OPUS).timeBase(OPUS).sampleRate(48_000)
                .layout(ChannelLayout.defaultFor(channels)).skipSamples(312).build();
    }

    static byte[] mux(int serial, Track track, List<Packet> packets, MediaTags tags) {
        var bytes = new ByteArrayOutputStream();
        try (var muxer = new OggMuxer(MediaSink.of(bytes), OggMuxerOptions.builder().serial(serial).build())) {
            muxer.addTrack(track);
            if (tags != null) {
                muxer.setTags(tags);
            }
            for (var packet : packets) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return bytes.toByteArray();
    }

    static byte[] concat(List<byte[]> parts) {
        var output = new ByteArrayOutputStream();
        parts.forEach(output::writeBytes);
        return output.toByteArray();
    }

    static List<Packet> readAll(OggDemuxer demuxer) {
        var packets = new ArrayList<Packet>();
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            packets.add(packet);
        }
        return packets;
    }

    static List<Packet> linear(byte[] file) {
        try (var demuxer = new OggDemuxer(MediaSource.of(file))) {
            return readAll(demuxer);
        }
    }

    static Map<Integer, List<Packet>> byTrack(List<Packet> packets) {
        var result = new HashMap<Integer, List<Packet>>();
        for (var packet : packets) {
            result.computeIfAbsent(packet.trackId(), _ -> new ArrayList<>()).add(packet);
        }
        return result;
    }

    /**
     * Returns the index of the packet a seek must restart a track at: its last key frame starting
     * at or before the target, the first packet for a target before it, or the list size when the
     * track ends at or before the target.
     */
    static int expectedStart(List<Packet> packets, Track track, long micros) {
        var target = BigInteger.valueOf(micros).multiply(BigInteger.valueOf(track.timeBase().denominator()))
                .divide(BigInteger.valueOf(track.timeBase().numerator() * 1_000_000L)).longValue();
        if (micros < 0) {
            target = Long.MIN_VALUE / 4;
        }
        var end = Long.MIN_VALUE;
        for (var packet : packets) {
            end = Math.max(end, packet.pts() + Math.max(1, packet.duration()));
        }
        if (!packets.isEmpty() && end <= target) {
            return packets.size();
        }
        var start = -1;
        for (var index = 0; index < packets.size(); index++) {
            if (packets.get(index).keyframe() && packets.get(index).pts() <= target) {
                start = index;
            }
        }
        if (start < 0) {
            start = 0;
            while (start < packets.size() - 1 && !packets.get(start).keyframe()) {
                start++;
            }
        }
        return start;
    }

    /**
     * Checks that after a seek every track restarts as {@link #expectedStart} says and continues as
     * the linear read, comparing at most {@code limit} packets per track.
     */
    static void assertSeek(String name, OggDemuxer demuxer, Map<Integer, List<Packet>> linear, long micros, int limit) {
        demuxer.seek(micros);
        var seen = new HashMap<Integer, Integer>();
        var after = new HashMap<Integer, List<Packet>>();
        var total = 0;
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            var count = seen.merge(packet.trackId(), 1, Integer::sum);
            if (count <= limit) {
                after.computeIfAbsent(packet.trackId(), _ -> new ArrayList<>()).add(packet);
            }
            total++;
            if (limit != Integer.MAX_VALUE && total > limit * demuxer.tracks().size()) {
                break;
            }
        }
        for (var track : demuxer.tracks()) {
            var expected = linear.getOrDefault(track.id(), List.of());
            var start = expectedStart(expected, track, micros);
            var actual = after.getOrDefault(track.id(), List.of());
            var wanted = Math.min(limit, expected.size() - start);
            if (limit == Integer.MAX_VALUE) {
                assertEquals(expected.size() - start, actual.size(), name + " track " + track.id() + " after " + micros);
            } else if (track.id() == lastTrackBefore(demuxer, micros)) {
                assertEquals(wanted, actual.size(), name + " track " + track.id() + " after " + micros);
            }
            for (var index = 0; index < actual.size(); index++) {
                var want = expected.get(start + index);
                var got = actual.get(index);
                assertArrayEquals(want.data(), got.data(), name + " track " + track.id() + " packet " + index
                        + " after " + micros);
                if (index > 0 || track.codec() != Codec.VORBIS) {
                    assertEquals(want.pts(), got.pts(), name + " track " + track.id() + " pts " + index + " after "
                            + micros);
                    assertEquals(want.duration(), got.duration(), name + " track " + track.id() + " duration "
                            + index + " after " + micros);
                } else {
                    // the first Vorbis packet ends exactly where it does in a linear read and never starts later
                    assertTrue(got.pts() <= want.pts(), name + " first Vorbis pts after " + micros);
                    assertEquals(want.pts() + want.duration(), got.pts() + got.duration(), name
                            + " first Vorbis end after " + micros);
                }
            }
        }
    }

    /** Returns the track of the link that plays at a time, the one a seek starts with. */
    static int lastTrackBefore(OggDemuxer demuxer, long micros) {
        var id = demuxer.links().getFirst().trackIds().getFirst();
        for (var link : demuxer.links()) {
            if (link.startMicros() <= micros) {
                id = link.trackIds().getFirst();
            }
        }
        return id;
    }

    /** Reads how far the demuxer's parser has read, through reflection on its private field. */
    static long parserPosition(OggDemuxer demuxer) {
        try {
            var field = OggDemuxer.class.getDeclaredField("parser");
            field.setAccessible(true);
            return ((OggParser) field.get(demuxer)).position();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    static MediaTags tags(String title, String... extra) {
        var builder = MediaTags.builder().set("TITLE", title);
        for (var index = 0; index + 1 < extra.length; index += 2) {
            builder.add(extra[index], extra[index + 1]);
        }
        return builder.build();
    }

    static AttachedPicture picture(String description) {
        var png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 0, 0, 0, 13, 'I', 'H', 'D', 'R', 0, 0, 0, 1,
                0, 0, 0, 1, 8, 2, 0, 0, 0};
        return AttachedPicture.of(AttachedPicture.Type.FRONT_COVER, "image/png", description, png);
    }

    /** Re-muxes the Vorbis packets of an ffmpeg file with another serial number and tags. */
    static byte[] vorbisLink(int serial, MediaTags tags, double seconds, int frequency) {
        var file = Fixtures.read(Fixtures.tone("ogg", frequency, 48_000, 1, seconds, List.of("-c:a", "libvorbis")));
        try (var demuxer = new OggDemuxer(MediaSource.of(file))) {
            var track = demuxer.tracks().getFirst();
            return mux(serial, track, readAll(demuxer), tags);
        }
    }

    /** The links of the small chain, and the chain itself. */
    record Chain(List<byte[]> links, byte[] file) {
    }

    static Chain smallChain() {
        var first = mux(10, opusTrack(2), opusPackets(50, 0xFC, 120, 1), tags("A").toBuilder()
                .chapters(List.of(Chapter.of(0, 500_000, "intro"), Chapter.of(500_000, 1_000_000, null))).build());
        var second = vorbisLink(20, tags("B").toBuilder().addPicture(picture("cover b")).build(), 1.0, 440);
        var third = mux(30, opusTrack(1), opusPackets(25, 0xF8, 80, 2), tags("C", "ARTIST", "x").toBuilder()
                .chapters(List.of(Chapter.of(100_000, 100_000, null))).build());
        var links = List.of(first, second, third);
        return new Chain(links, concat(links));
    }

    @Nested
    @DisplayName("description")
    class Description {
        @Test
        @DisplayName("describes every link with its own start, duration, tracks, tags, chapters and pictures")
        void links() {
            var chain = smallChain();
            try (var demuxer = new OggDemuxer(MediaSource.of(chain.file()))) {
                var links = demuxer.links();
                assertEquals(3, links.size());
                var expectedStart = 0L;
                for (var index = 0; index < 3; index++) {
                    long duration;
                    try (var alone = new OggDemuxer(MediaSource.of(chain.links().get(index)))) {
                        duration = alone.durationMicros();
                    }
                    assertEquals(expectedStart, links.get(index).startMicros(), "start of link " + index);
                    assertEquals(duration, links.get(index).durationMicros(), "duration of link " + index);
                    assertEquals(List.of(index), links.get(index).trackIds());
                    expectedStart += duration;
                }
                assertEquals(993_500, links.get(1).startMicros(), "50 packets of 960 samples less a pre-skip of 312");
                assertEquals(expectedStart, demuxer.durationMicros());
                assertEquals("A", links.get(0).metadata().get("TITLE"));
                assertEquals("B", links.get(1).metadata().get("TITLE"));
                assertEquals("C", links.get(2).metadata().get("TITLE"));
                assertEquals("x", links.get(2).metadata().get("ARTIST"));
                assertFalse(links.get(0).metadata().containsKey("CHAPTER000"), "chapter fields are not tags");
                assertEquals("A", demuxer.metadata().get("TITLE"));
                assertEquals("B", demuxer.trackMetadata(1).get("TITLE"));
                assertEquals("C", demuxer.trackMetadata(2).get("TITLE"));
                assertEquals(Map.of(), demuxer.trackMetadata(7));
                assertEquals(List.of(Chapter.of(0, 500_000, "intro"), Chapter.of(500_000, 993_500, null)),
                        links.get(0).chapters());
                var third = links.get(2);
                assertEquals(List.of(Chapter.of(third.startMicros() + 100_000, third.startMicros() + third.durationMicros(),
                        null)), third.chapters());
                var all = new ArrayList<>(links.get(0).chapters());
                all.addAll(third.chapters());
                assertEquals(all, demuxer.chapters());
                assertEquals(List.of(), links.get(0).pictures());
                assertEquals(List.of(picture("cover b")), links.get(1).pictures());
                assertEquals(List.of(), demuxer.attachedPictures());
            }
        }

        @Test
        @DisplayName("reports the PICTURE blocks of an Ogg FLAC link as that link's pictures")
        void flacPictures() {
            var flac = Fixtures.read(Fixtures.tone("oga", 440, 44_100, 1, 0.5, List.of("-c:a", "flac")));
            var cover = picture("flac cover");
            var tagged = TagEditor.write(flac, tags("F").toBuilder().addPicture(cover).build(), TagWriteOptions.defaults());
            var pageText = new String(tagged, StandardCharsets.ISO_8859_1);
            assertFalse(pageText.contains("METADATA_BLOCK_PICTURE"), "the picture is a PICTURE block, not a comment");
            var opus = mux(77, opusTrack(1), opusPackets(20, 0xF8, 40, 9), tags("O"));
            try (var demuxer = new OggDemuxer(MediaSource.of(concat(List.of(tagged, opus))))) {
                assertEquals(2, demuxer.links().size());
                assertEquals(Codec.FLAC, demuxer.tracks().getFirst().codec());
                assertEquals(List.of(cover), demuxer.attachedPictures());
                assertEquals(List.of(cover), demuxer.links().getFirst().pictures());
                assertEquals(List.of(), demuxer.links().get(1).pictures());
                assertEquals("F", demuxer.metadata().get("TITLE"));
            }
        }

        @Test
        @DisplayName("returns every link's packets as the link demuxed alone, shifted to where the link starts")
        void packets() {
            var chain = smallChain();
            var all = byTrack(linear(chain.file()));
            try (var demuxer = new OggDemuxer(MediaSource.of(chain.file()))) {
                for (var index = 0; index < 3; index++) {
                    var alone = linear(chain.links().get(index));
                    var chained = all.get(index);
                    var track = demuxer.tracks().get(index);
                    var shift = Rational.MICROSECONDS.rescale(demuxer.links().get(index).startMicros(), track.timeBase());
                    assertEquals(alone.size(), chained.size(), "packets of link " + index);
                    for (var packet = 0; packet < alone.size(); packet++) {
                        assertArrayEquals(alone.get(packet).data(), chained.get(packet).data());
                        assertEquals(alone.get(packet).pts() + shift, chained.get(packet).pts(), "link " + index
                                + " packet " + packet);
                    }
                }
            }
        }
    }

    static final long[] TARGETS = {-400_000, 0, 1, 333_333, 999_999, 1_000_000, 1_250_000, 1_999_000, 2_000_000,
            2_012_345, 2_300_000, 2_499_999, 2_600_000, 9_000_000};

    @Nested
    @DisplayName("seeking")
    class Seeking {
        @Test
        @DisplayName("restarts at the last packet at or before the target in every link of a scanned chain")
        void smallChain() {
            var file = OggChainedTest.smallChain().file();
            var linear = byTrack(linear(file));
            try (var demuxer = new OggDemuxer(MediaSource.of(file))) {
                assertTrue(demuxer.isSeekable());
                for (var micros : TARGETS) {
                    assertSeek("small chain", demuxer, linear, micros, Integer.MAX_VALUE);
                }
            }
        }

        @Test
        @DisplayName("survives seeks between partial reads")
        void partialReads() {
            var file = OggChainedTest.smallChain().file();
            var linear = byTrack(linear(file));
            var random = new SplittableRandom(7);
            try (var demuxer = new OggDemuxer(MediaSource.of(file))) {
                for (var round = 0; round < 60; round++) {
                    var micros = random.nextLong(-500_000, 3_000_000);
                    demuxer.seek(micros);
                    for (var count = random.nextInt(40); count > 0; count--) {
                        if (demuxer.read() == null) {
                            break;
                        }
                    }
                }
                for (var micros : TARGETS) {
                    assertSeek("small chain after partial reads", demuxer, linear, micros, Integer.MAX_VALUE);
                }
            }
        }

        @Test
        @DisplayName("past the end of the shorter stream of a grouped link, neither returns it nor holds the rest")
        void groupedShorterStream() {
            var output = new ByteArrayOutputStream();
            var shortPackets = opusPackets(50, 0xF8, 200, 11);
            var longPackets = opusPackets(1000, 0xF8, 200, 12);
            try (var muxer = new OggMuxer(MediaSink.of(output))) {
                muxer.addTrack(opusTrack(1));
                muxer.addTrack(opusTrack(1).toBuilder().id(1).build());
                for (var index = 0; index < longPackets.size(); index++) {
                    if (index < shortPackets.size()) {
                        muxer.writePacket(shortPackets.get(index));
                    }
                    var packet = longPackets.get(index);
                    muxer.writePacket(new Packet(1, packet.data(), packet.pts(), packet.dts(), packet.duration(), true));
                }
                muxer.finish();
            }
            var file = output.toByteArray();
            var linear = byTrack(linear(file));
            try (var demuxer = new OggDemuxer(MediaSource.of(file))) {
                assertEquals(1, demuxer.links().size());
                assertEquals(2, demuxer.tracks().size());
                demuxer.seek(10_000_000);
                assertEquals(1, demuxer.read().trackId());
                var position = parserPosition(demuxer);
                assertTrue(position < file.length * 3L / 4, "parsed " + position + " of " + file.length + " bytes");
                assertSeek("grouped", demuxer, linear, 10_000_000, Integer.MAX_VALUE);
                assertSeek("grouped", demuxer, linear, 500_000, Integer.MAX_VALUE);
            }
        }

        @Test
        @DisplayName("seeks a chain whose links share a serial number")
        void reusedSerial() {
            var link = mux(5, opusTrack(1), opusPackets(40, 0xF8, 60, 3), tags("same"));
            var file = concat(List.of(link, link, link));
            var linear = byTrack(linear(file));
            try (var demuxer = new OggDemuxer(MediaSource.of(file))) {
                assertEquals(3, demuxer.links().size());
                assertEquals(2_380_500, demuxer.durationMicros());
                for (var micros : new long[]{0, 500_000, 793_500, 1_700_000, 2_380_499}) {
                    assertSeek("reused serial", demuxer, linear, micros, Integer.MAX_VALUE);
                }
            }
        }
    }

    /** A chain above the 64 MiB scan limit, built once. */
    static final class Large {
        static final byte[][] LINKS;

        static final byte[] FILE;

        static {
            var links = new byte[3][];
            var counts = new int[]{2000, 1500, 1000};
            for (var index = 0; index < 3; index++) {
                links[index] = mux(100 + index, opusTrack(1), opusPackets(counts[index], 0x18, 18_000 - 1000 * index,
                        index), tags("large " + index));
            }
            LINKS = links;
            FILE = concat(Arrays.asList(links));
        }
    }

    @Nested
    @DisplayName("a chain above the scan limit")
    class AboveScanLimit {
        @Test
        @DisplayName("finds every link by bisection, reading a small part of the file")
        void bisection() {
            assertTrue(Large.FILE.length > 64 << 20, "the chain exceeds the scan limit");
            var source = new CountingSource(Large.FILE);
            try (var demuxer = new OggDemuxer(source)) {
                assertTrue(source.bytes < 8L << 20, "construction read " + source.bytes + " bytes");
                assertEquals(3, demuxer.links().size());
                // every link holds its packets of 2880 samples less the pre-skip of 312
                assertEquals(List.of(0L, 119_993_500L, 209_987_000L),
                        demuxer.links().stream().map(OggLink::startMicros).toList());
                assertEquals(269_980_500, demuxer.durationMicros());
                for (var index = 0; index < 3; index++) {
                    assertEquals("large " + index, demuxer.links().get(index).metadata().get("TITLE"));
                    assertEquals(new int[]{2000, 1500, 1000}[index] * 2880L - 312, demuxer.tracks().get(index).duration());
                }
            }
        }

        @Test
        @DisplayName("finds a short link of an unknown codec, whose data starts on its first page, by bisection")
        void opaqueLink() {
            var opaque = concat(List.of(OggCodecsTest.page(0x02, 0, 0x4242, 0, List.of("unknown codec".getBytes(
                    StandardCharsets.US_ASCII))), OggCodecsTest.page(0x04, 5, 0x4242, 1, List.of(new byte[]{9}))));
            var file = concat(List.of(Large.LINKS[0], Large.LINKS[1], opaque, Large.LINKS[2]));
            assertTrue(file.length > 64 << 20, "the chain exceeds the scan limit");
            var demuxer = assertTimeoutPreemptively(Duration.ofMinutes(1), () -> new OggDemuxer(MediaSource.of(file)));
            try (demuxer) {
                assertEquals(4, demuxer.links().size());
                assertEquals(List.of(2), demuxer.links().get(2).trackIds());
                assertEquals(-1, demuxer.links().get(2).durationMicros());
                assertEquals(Codec.OPUS, demuxer.tracks().get(3).codec());
                assertEquals("large 2", demuxer.links().get(3).metadata().get("TITLE"));
                assertEquals(demuxer.links().get(2).startMicros(), demuxer.links().get(3).startMicros());
                assertEquals(List.of(0L, 119_993_500L, 209_987_000L, 209_987_000L),
                        demuxer.links().stream().map(OggLink::startMicros).toList());
            }
        }

        @Test
        @DisplayName("seeks into every link exactly as a linear read continues")
        void seek() {
            var linear = byTrack(linear(Large.FILE));
            try (var demuxer = new OggDemuxer(MediaSource.of(Large.FILE))) {
                for (var micros : new long[]{-1, 0, 59_999_999, 119_990_000, 119_993_500, 150_123_456, 209_986_999,
                        209_987_000, 269_000_000, 269_980_499, 300_000_000}) {
                    assertSeek("large chain", demuxer, linear, micros, 40);
                }
            }
        }

        @Test
        @DisplayName("keeps bisection, seeking and tag edits of a damaged large chain to MediaException")
        void damaged() {
            var file = Large.FILE.clone();
            var random = new SplittableRandom(21);
            for (var round = 0; round < 16; round++) {
                // damage the bytes around the link boundaries, the file end and random places
                var anchors = new long[]{Large.LINKS[0].length, Large.LINKS[0].length + Large.LINKS[1].length,
                        file.length - 1, random.nextLong(file.length)};
                var damaged = new int[64];
                var saved = new byte[damaged.length];
                for (var index = 0; index < damaged.length; index++) {
                    var anchor = anchors[index % anchors.length];
                    damaged[index] = (int) Math.clamp(anchor + random.nextInt(-70_000, 70_000), 0, file.length - 1);
                    saved[index] = file[damaged[index]];
                    file[damaged[index]] = (byte) random.nextInt(256);
                }
                try (var demuxer = new OggDemuxer(MediaSource.of(file))) {
                    demuxer.links();
                    for (var seek = 0; seek < 4; seek++) {
                        demuxer.seek(random.nextLong(-1_000_000, 300_000_000));
                        for (var count = 0; count < 50 && demuxer.read() != null; count++) {
                            // the packets of a damaged file are only read, not checked
                        }
                    }
                } catch (MediaException expected) {
                    // a damaged file may be rejected, but only with a MediaException
                }
                try {
                    TagEditor.write(file, tags("damaged"), TagWriteOptions.defaults());
                } catch (MediaException expected) {
                    // a damaged file may be rejected, but only with a MediaException
                }
                for (var index = damaged.length - 1; index >= 0; index--) {
                    file[damaged[index]] = saved[index];
                }
            }
        }

        @Test
        @DisplayName("edits the comment header of every link and keeps the audio pages")
        void tagEdit() {
            var edited = TagEditor.write(Large.FILE, tags("large 0", "ALBUM", "all"), TagWriteOptions.defaults());
            try (var demuxer = new OggDemuxer(MediaSource.of(edited))) {
                for (var index = 0; index < 3; index++) {
                    var metadata = demuxer.links().get(index).metadata();
                    assertEquals("all", metadata.get("ALBUM"), "album of link " + index);
                    assertEquals("large " + index, metadata.get("TITLE"), "title of link " + index);
                }
            }
            assertAudioPagesKept(Large.FILE, edited);
        }
    }

    /** One page, as the tag edit check compares it. */
    record Page(int flags, long granule, int serial, long sequence, byte[] body) {
    }

    static List<Page> pages(byte[] data) {
        var pages = new ArrayList<Page>();
        var offset = 0;
        while (offset + 27 <= data.length) {
            var segments = data[offset + 26] & 0xFF;
            var size = 0;
            for (var i = 0; i < segments; i++) {
                size += data[offset + 27 + i] & 0xFF;
            }
            var granule = 0L;
            for (var i = 7; i >= 0; i--) {
                granule = granule << 8 | (data[offset + 6 + i] & 0xFF);
            }
            var serial = 0;
            var sequence = 0L;
            for (var i = 3; i >= 0; i--) {
                serial = serial << 8 | (data[offset + 14 + i] & 0xFF);
                sequence = sequence << 8 | (data[offset + 18 + i] & 0xFF);
            }
            var total = 27 + segments + size;
            pages.add(new Page(data[offset + 5] & 0xFF, granule, serial, sequence,
                    Arrays.copyOfRange(data, offset + 27 + segments, offset + total)));
            offset += total;
        }
        assertEquals(data.length, offset, "the file is a sequence of pages");
        return pages;
    }

    /**
     * Checks that the data pages (granule position above zero) of two files are identical apart
     * from their sequence numbers, which stay consecutive within each stream.
     */
    static void assertAudioPagesKept(byte[] original, byte[] edited) {
        var before = pages(original).stream().filter(page -> page.granule() > 0).toList();
        var after = pages(edited).stream().filter(page -> page.granule() > 0).toList();
        assertEquals(before.size(), after.size(), "data pages");
        var shifts = new HashMap<Integer, Long>();
        for (var index = 0; index < before.size(); index++) {
            var a = before.get(index);
            var b = after.get(index);
            assertEquals(a.flags(), b.flags(), "flags of data page " + index);
            assertEquals(a.granule(), b.granule(), "granule of data page " + index);
            assertEquals(a.serial(), b.serial(), "serial of data page " + index);
            assertArrayEquals(a.body(), b.body(), "body of data page " + index);
            var shift = b.sequence() - a.sequence();
            assertEquals(shift, shifts.computeIfAbsent(a.serial(), _ -> shift), "sequence shift of stream " + a.serial());
        }
    }

    @Nested
    @DisplayName("tag edits")
    class TagEdits {
        @Test
        @DisplayName("carries the changes an edit makes to the first link into every later link")
        void carries() {
            var chain = smallChain();
            var current = TagEditor.read(MediaSource.of(chain.file()));
            assertEquals("A", current.get("TITLE").orElseThrow());
            var edit = current.toBuilder().set("ALBUM", "album").remove("TITLE").build();
            var edited = TagEditor.write(chain.file(), edit, TagWriteOptions.defaults());
            try (var demuxer = new OggDemuxer(MediaSource.of(edited))) {
                var links = demuxer.links();
                for (var index = 0; index < 3; index++) {
                    assertEquals("album", links.get(index).metadata().get("ALBUM"), "album of link " + index);
                    assertFalse(links.get(index).metadata().containsKey("TITLE"), "title of link " + index);
                }
                assertEquals("x", links.get(2).metadata().get("ARTIST"), "a field the edit leaves alone is kept");
                assertEquals(List.of(picture("cover b")), links.get(1).pictures(), "pictures the edit leaves alone are kept");
                assertEquals(1, links.get(2).chapters().size(), "chapters the edit leaves alone are kept");
            }
            assertAudioPagesKept(chain.file(), edited);
            var packets = linear(chain.file());
            var editedPackets = linear(edited);
            assertEquals(packets.size(), editedPackets.size());
            for (var index = 0; index < packets.size(); index++) {
                assertArrayEquals(packets.get(index).data(), editedPackets.get(index).data());
                assertEquals(packets.get(index).pts(), editedPackets.get(index).pts());
            }
        }

        @Test
        @DisplayName("replaces the pictures and chapters of every link when the edit changes them")
        void replacesPicturesAndChapters() {
            var chain = smallChain();
            var edit = TagEditor.read(MediaSource.of(chain.file())).toBuilder().pictures(List.of(picture("new")))
                    .chapters(List.of(Chapter.of(0, 200_000, "only"))).build();
            var edited = TagEditor.write(chain.file(), edit, TagWriteOptions.defaults());
            try (var demuxer = new OggDemuxer(MediaSource.of(edited))) {
                for (var link : demuxer.links()) {
                    assertEquals(List.of(picture("new")), link.pictures());
                    assertEquals("only", link.chapters().getFirst().title().orElseThrow());
                    assertEquals(link.startMicros(), link.chapters().getFirst().startMicros());
                }
            }
            assertAudioPagesKept(chain.file(), edited);
        }

        @Test
        @DisplayName("edits a chain in place when the padded comment headers keep their length")
        void inPlace() {
            var first = mux(10, opusTrack(2), opusPackets(30, 0xFC, 50, 4), tags("a long title to shrink"));
            var second = mux(11, opusTrack(2), opusPackets(30, 0xFC, 50, 5), tags("another long title"));
            var file = concat(List.of(first, second));
            var edited = TagEditor.write(file, tags("short"), TagWriteOptions.defaults());
            assertEquals(file.length, edited.length);
            try (var demuxer = new OggDemuxer(MediaSource.of(edited))) {
                assertEquals("short", demuxer.links().get(0).metadata().get("TITLE"));
                assertEquals("short", demuxer.links().get(1).metadata().get("TITLE"));
            }
            assertAudioPagesKept(file, edited);
        }
    }

    @Nested
    @DisplayName("sources of unknown size")
    class Unsized {
        @Test
        @DisplayName("are measured, then described and seeked like a sized source")
        void randomAccess() {
            var file = mux(9, opusTrack(2), opusPackets(150, 0xFC, 90, 8), tags("u"));
            var linear = byTrack(linear(file));
            long duration;
            try (var sized = new OggDemuxer(MediaSource.of(file))) {
                duration = sized.durationMicros();
            }
            try (var demuxer = new OggDemuxer(new UnsizedSource(file, 4096))) {
                assertTrue(duration > 0);
                assertEquals(duration, demuxer.durationMicros());
                assertTrue(demuxer.isSeekable());
                for (var micros : new long[]{1_000_000, 2_500_000, 300_000, 0, 2_999_000, 4_000_000, -5}) {
                    assertSeek("unsized", demuxer, linear, micros, Integer.MAX_VALUE);
                }
                demuxer.seek(500_000);
                for (var count = 0; count < 10; count++) {
                    demuxer.read();
                }
                assertSeek("unsized after partial read", demuxer, linear, 1_200_000, Integer.MAX_VALUE);
            }
        }

        @Test
        @DisplayName("a stream read once seeks only forward")
        void forwardOnly() {
            var file = mux(9, opusTrack(2), opusPackets(150, 0xFC, 90, 8), tags("u"));
            var linear = byTrack(linear(file));
            try (var demuxer = new OggDemuxer(MediaSource.of(new ByteArrayInputStream(file), -1))) {
                assertFalse(demuxer.isSeekable());
                demuxer.seek(1_000_000);
                var first = demuxer.read();
                var expected = linear.get(0).get(expectedStart(linear.get(0), demuxer.tracks().getFirst(), 1_000_000));
                assertEquals(expected.pts(), first.pts());
                assertArrayEquals(expected.data(), first.data());
                assertThrows(MediaException.Unsupported.class, () -> demuxer.seek(200_000));
                assertSeek("forward only", demuxer, linear, 2_000_000, Integer.MAX_VALUE);
            }
        }

        @Test
        @DisplayName("a stream of known size read once describes every link and seeks forward across them")
        void forwardOnlyChain() {
            // three links of 12 seconds each
            var file = concat(List.of(mux(1, opusTrack(1), opusPackets(600, 0xF8, 1000, 1), tags("a")),
                    mux(2, opusTrack(1), opusPackets(600, 0xF8, 1000, 2), tags("b")),
                    mux(3, opusTrack(1), opusPackets(600, 0xF8, 1000, 3), tags("c"))));
            var linear = byTrack(linear(file));
            try (var source = MediaSource.of(new ByteArrayInputStream(file), file.length);
                 var demuxer = new OggDemuxer(source)) {
                assertFalse(demuxer.isSeekable());
                assertEquals(3, demuxer.links().size());
                assertEquals("c", demuxer.links().get(2).metadata().get("TITLE"));
                assertSeek("forward-only chain", demuxer, linear, 13_000_000, 30);
                // the first link's packets end before the target, yet the seek does not hold packets to the end
                var position = parserPosition(demuxer);
                assertTrue(position < file.length * 3L / 4, "parsed " + position + " of " + file.length + " bytes");
                assertSeek("forward-only chain", demuxer, linear, 30_000_000, Integer.MAX_VALUE);
                assertThrows(MediaException.Unsupported.class, () -> demuxer.seek(1_000_000));
            }
        }

        @Test
        @DisplayName("are measured, so every link of a chain is described and read")
        void chainedUnsized() {
            var chain = smallChain();
            try (var sized = new OggDemuxer(MediaSource.of(chain.file()));
                 var demuxer = new OggDemuxer(new UnsizedSource(chain.file(), 1000))) {
                assertEquals(chain.links().size(), demuxer.links().size());
                for (var link = 0; link < chain.links().size(); link++) {
                    assertEquals(sized.links().get(link).durationMicros(), demuxer.links().get(link).durationMicros());
                }
                assertEquals(sized.tracks().size(), demuxer.tracks().size());
                var expected = linear(chain.file());
                var packets = readAll(demuxer);
                assertEquals(expected.size(), packets.size());
                for (var index = 0; index < expected.size(); index++) {
                    assertEquals(expected.get(index).pts(), packets.get(index).pts());
                    assertArrayEquals(expected.get(index).data(), packets.get(index).data());
                }
            }
        }
    }

    @Test
    @DisplayName("resists random mutations of a chain while describing, seeking and reading it")
    void mutations() {
        var file = smallChain().file();
        for (var variant : AudioMutations.variants(file, 11, 150)) {
            try (var demuxer = new OggDemuxer(MediaSource.of(variant))) {
                demuxer.links();
                var random = new SplittableRandom(variant.length);
                for (var round = 0; round < 3; round++) {
                    if (demuxer.isSeekable()) {
                        demuxer.seek(random.nextLong(-100_000, 3_000_000));
                    }
                    for (var count = 0; count < 200 && demuxer.read() != null; count++) {
                        // the packets of a damaged file are only read, not checked
                    }
                }
            } catch (MediaException expected) {
                // a damaged file may be rejected, but only with a MediaException
            }
        }
    }

    @Test
    @DisplayName("keeps tag edits of a damaged chain to MediaException")
    void tagEditMutations() {
        var file = smallChain().file();
        for (var variant : AudioMutations.variants(file, 12, 80)) {
            try {
                var edited = TagEditor.write(variant, tags("z"), TagWriteOptions.defaults());
                assertNotEquals(0, edited.length);
            } catch (MediaException expected) {
                // a damaged file may be rejected, but only with a MediaException
            }
        }
        assertTrue(new String(TagEditor.write(file, tags("z"), TagWriteOptions.defaults()), StandardCharsets.ISO_8859_1)
                .contains("TITLE=z"), "the undamaged chain is edited");
    }
}
