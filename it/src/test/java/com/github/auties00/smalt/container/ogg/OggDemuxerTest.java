package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.audio.AudioMutations;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.testkit.audio.ProbedPacket;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the Ogg demuxer: packet-exact agreement with ffprobe for the Opus, Vorbis and FLAC
 * mappings, recognition of Speex, Theora and Skeleton streams, chained and multiplexed files,
 * CRC/garbage/truncation recovery, and mutation robustness.
 */
class OggDemuxerTest {
    static Path opus(int channels, int rate, double seconds, String... options) {
        var output = new ArrayList<>(List.of("-c:a", "libopus"));
        output.addAll(List.of(options));
        return Fixtures.tone("opus", 440, rate, channels, seconds, output);
    }

    static Stream<Arguments> fixtures() {
        return Stream.of(
                Arguments.of("opus mono 48k", opus(1, 48000, 1.013)),
                Arguments.of("opus stereo 44.1k", opus(2, 44100, 2.5, "-b:a", "96k")),
                Arguments.of("opus mono 16k voip", opus(1, 16000, 3.2, "-application", "voip", "-b:a", "16k")),
                Arguments.of("opus 8k 60ms frames", opus(1, 8000, 1.7, "-frame_duration", "60")),
                Arguments.of("opus 2.5ms frames", opus(2, 48000, 0.4, "-frame_duration", "2.5")),
                Arguments.of("opus 120ms frames", opus(2, 48000, 2.0, "-frame_duration", "120")),
                Arguments.of("opus 5.1", opus(6, 48000, 1.1, "-mapping_family", "1")),
                Arguments.of("opus 7.1", opus(8, 48000, 0.7, "-mapping_family", "1")),
                Arguments.of("opus cbr", opus(2, 48000, 1.5, "-vbr", "off", "-b:a", "64k")),
                Arguments.of("opus small pages", Fixtures.tone("ogg", 440, 48000, 2, 1.0,
                        List.of("-c:a", "libopus", "-page_duration", "20000"))),
                Arguments.of("vorbis mono 44.1k", Fixtures.tone("ogg", 440, 44100, 1, 1.013, List.of("-c:a", "libvorbis"))),
                Arguments.of("vorbis stereo 48k q8", Fixtures.tone("ogg", 1000, 48000, 2, 2.2,
                        List.of("-c:a", "libvorbis", "-q:a", "8"))),
                Arguments.of("vorbis 5.1 22.05k", Fixtures.tone("ogg", 300, 22050, 6, 1.4, List.of("-c:a", "libvorbis"))),
                Arguments.of("vorbis 8k", Fixtures.tone("ogg", 300, 8000, 1, 3.0, List.of("-c:a", "libvorbis", "-q:a", "0"))),
                Arguments.of("flac mono 16-bit", Fixtures.tone("oga", 440, 44100, 1, 1.013, List.of("-c:a", "flac"))),
                Arguments.of("flac stereo 24-bit 96k", Fixtures.tone("oga", 440, 96000, 2, 0.8,
                        List.of("-c:a", "flac", "-sample_fmt", "s32"))),
                Arguments.of("flac 5.1", Fixtures.tone("oga", 440, 48000, 6, 0.6, List.of("-c:a", "flac"))));
    }

    static List<Packet> demux(Path file) {
        try (var demuxer = new OggDemuxer(MediaSource.of(Fixtures.read(file)))) {
            return AudioPipeline.packets(demuxer, 0);
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    @DisplayName("returns exactly the packets, timestamps and durations ffprobe reports")
    void matchesFfprobe(String name, Path file) {
        var expected = ProbedPacket.probe(file);
        var actual = demux(file).stream().map(ProbedPacket::of).toList();
        assertEquals(expected.size(), actual.size(), "packet count");
        for (var i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), actual.get(i), "packet " + i);
        }
    }

    @ParameterizedTest(name = "{0} channels, {1}")
    @org.junit.jupiter.params.provider.CsvSource({"1, s16", "2, s32", "6, s16"})
    @DisplayName("feeds Ogg FLAC packets to the FLAC decoder with bit-exact output")
    void flacDecodes(int channels, String format) {
        var file = Fixtures.tone("oga", 700, 44100, channels, 1.3, List.of("-c:a", "flac", "-sample_fmt", format));
        try (var demuxer = new OggDemuxer(MediaSource.of(Fixtures.read(file)))) {
            var decoded = AudioPipeline.decode(demuxer, com.github.auties00.smalt.audio.flac.FlacDecoder::new);
            assertArrayEquals(com.github.auties00.smalt.testkit.Oracle.decodeAudio(file), decoded.trimmed());
        }
    }

    @Nested
    @DisplayName("track description")
    class Description {
        @Test
        @DisplayName("describes an Opus stream with its OpusHead, pre-skip-aware duration and tags")
        void opusTrack() {
            var file = Fixtures.tone("opus", 440, 48000, 2, 1.013, List.of("-c:a", "libopus", "-metadata", "title=Hello"));
            try (var demuxer = new OggDemuxer(MediaSource.of(Fixtures.read(file)))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.OPUS, track.codec());
                assertEquals(48000, track.sampleRate());
                assertEquals(2, track.layout().orElseThrow().channels());
                var head = track.codecConfig().orElseThrow();
                assertEquals("OpusHead", new String(head, 0, 8));
                assertEquals(48624, track.duration(), "1.013 s at 48 kHz");
                assertEquals(0, track.skipSamples());
                assertEquals(1_013_000, demuxer.durationMicros());
                assertEquals("Hello", demuxer.metadata().get("title"));
            }
        }

        @Test
        @DisplayName("describes a Vorbis stream with Xiph-laced headers and Vorbis channel order")
        void vorbisTrack() {
            var file = Fixtures.tone("ogg", 440, 44100, 6, 0.5, List.of("-c:a", "libvorbis"));
            try (var demuxer = new OggDemuxer(MediaSource.of(Fixtures.read(file)))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.VORBIS, track.codec());
                assertEquals(44100, track.sampleRate());
                assertEquals(22050, track.duration());
                var layout = track.layout().orElseThrow();
                assertEquals(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_CENTER, Speaker.FRONT_RIGHT, Speaker.BACK_LEFT,
                        Speaker.BACK_RIGHT, Speaker.LOW_FREQUENCY), layout.speakers());
                var config = track.codecConfig().orElseThrow();
                assertEquals(2, config[0]);
                var first = config[1] & 0xFF;
                assertEquals(30, first, "identification header size");
                assertEquals(1, config[3]);
                assertEquals("vorbis", new String(config, 4, 6));
            }
        }

        @Test
        @DisplayName("describes a FLAC stream with fLaC and STREAMINFO as codec configuration")
        void flacTrack() {
            var file = Fixtures.tone("oga", 440, 32000, 2, 0.5, List.of("-c:a", "flac", "-sample_fmt", "s32"));
            try (var demuxer = new OggDemuxer(MediaSource.of(Fixtures.read(file)))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.FLAC, track.codec());
                assertEquals(24, track.bitsPerSample());
                assertEquals(16000, track.duration());
                var config = track.codecConfig().orElseThrow();
                assertEquals("fLaC", new String(config, 0, 4));
                assertEquals(0, config[4] & 0x7F, "STREAMINFO first");
                var lastFlag = false;
                var offset = 4;
                while (offset < config.length) {
                    lastFlag = (config[offset] & 0x80) != 0;
                    offset += 4 + ((config[offset + 1] & 0xFF) << 16 | (config[offset + 2] & 0xFF) << 8 | (config[offset + 3] & 0xFF));
                }
                assertEquals(config.length, offset, "blocks tile the configuration");
                assertTrue(lastFlag, "last block flagged");
            }
        }

        @Test
        @DisplayName("reassembles a comment header that spans several pages")
        void longTags() {
            var comment = "x".repeat(150_000);
            // the command line cannot carry 150 kB, so the comment goes through an ffmetadata file
            var metadata = Path.of("target", "ogg-long-comment.ffmeta");
            try {
                java.nio.file.Files.writeString(metadata, ";FFMETADATA1\ncomment=" + comment + "\n");
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
            var file = Fixtures.ffmpeg("opus", List.of("-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=0.5",
                    "-f", "ffmetadata", "-i", metadata.toString()), List.of("-map_metadata", "1", "-c:a", "libopus"));
            try (var demuxer = new OggDemuxer(MediaSource.of(Fixtures.read(file)))) {
                assertEquals(comment, demuxer.metadata().get("DESCRIPTION"), "ffmpeg writes the comment as DESCRIPTION");
                var packets = AudioPipeline.packets(demuxer, 0).stream().map(ProbedPacket::of).toList();
                assertEquals(ProbedPacket.probe(file), packets);
            }
        }

        @Test
        @DisplayName("recognizes Speex as an unsupported audio track")
        void speex() {
            var file = Fixtures.tone("spx", 440, 16000, 1, 0.5, List.of("-c:a", "libspeex"));
            try (var demuxer = new OggDemuxer(MediaSource.of(Fixtures.read(file)))) {
                var track = (AudioTrack) demuxer.tracks().getFirst();
                assertEquals(Codec.UNKNOWN_AUDIO, track.codec());
                assertEquals(16000, track.sampleRate());
                var packets = AudioPipeline.packets(demuxer, track.id());
                assertFalse(packets.isEmpty());
                assertEquals(ProbedPacket.probe(file).size(), packets.size());
            }
        }

        @Test
        @DisplayName("describes grouped Theora and Vorbis streams as separate tracks")
        void multiplexed() {
            var file = Fixtures.testVideo("ogv", 64, 48, 0.5, List.of("-c:v", "libtheora", "-c:a", "libvorbis", "-ac", "2"));
            try (var demuxer = new OggDemuxer(MediaSource.of(Fixtures.read(file)))) {
                assertEquals(2, demuxer.tracks().size());
                var video = (VideoTrack) demuxer.tracks().get(0);
                assertEquals(Codec.UNKNOWN_VIDEO, video.codec());
                assertEquals(64, video.width());
                assertEquals(48, video.height());
                var audio = (AudioTrack) demuxer.tracks().get(1);
                assertEquals(Codec.VORBIS, audio.codec());
                var counts = new int[2];
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    counts[packet.trackId()]++;
                }
                assertEquals(ProbedPacket.probe(file, "v:0").size(), counts[0]);
                assertEquals(ProbedPacket.probe(file, "a:0").size(), counts[1]);
            }
        }

        @Test
        @DisplayName("reports a skeleton stream as a data track")
        void skeleton() {
            var file = Fixtures.tone("ogg", 440, 48000, 1, 0.3, List.of("-c:a", "libvorbis"));
            var bytes = withSkeleton(Fixtures.read(file));
            try (var demuxer = new OggDemuxer(MediaSource.of(bytes))) {
                assertEquals(2, demuxer.tracks().size());
                var data = (DataTrack) demuxer.tracks().get(0);
                assertEquals("skeleton", data.handler().orElseThrow());
                assertEquals(Codec.VORBIS, demuxer.tracks().get(1).codec());
            }
        }
    }

    /** Prepends a minimal Skeleton BOS + EOS page pair (fishead) so the file becomes a grouped stream. */
    static byte[] withSkeleton(byte[] vorbis) {
        var fishead = new byte[64];
        System.arraycopy("fishead\0".getBytes(), 0, fishead, 0, 8);
        fishead[8] = 3;
        var output = new ByteArrayOutputStream();
        output.writeBytes(page(0x02, 0, 0x5EED, 0, List.of(fishead)));
        // every BOS page must precede the first non-BOS page, so the skeleton EOS follows the Vorbis BOS
        var firstPageSize = pageSize(vorbis, 0);
        output.writeBytes(Arrays.copyOfRange(vorbis, 0, firstPageSize));
        output.writeBytes(page(0x04, 0, 0x5EED, 1, List.of()));
        output.writeBytes(Arrays.copyOfRange(vorbis, firstPageSize, vorbis.length));
        return output.toByteArray();
    }

    static int pageSize(byte[] data, int offset) {
        var segments = data[offset + 26] & 0xFF;
        var size = 27 + segments;
        for (var i = 0; i < segments; i++) {
            size += data[offset + 27 + i] & 0xFF;
        }
        return size;
    }

    /** Builds a valid Ogg page holding whole packets. */
    static byte[] page(int flags, long granule, int serial, int sequence, List<byte[]> packets) {
        var lacing = new ByteArrayOutputStream();
        var body = new ByteArrayOutputStream();
        for (var packet : packets) {
            var length = packet.length;
            while (length >= 255) {
                lacing.write(255);
                length -= 255;
            }
            lacing.write(length);
            body.writeBytes(packet);
        }
        var laces = lacing.toByteArray();
        var page = new byte[27 + laces.length + body.size()];
        System.arraycopy("OggS".getBytes(), 0, page, 0, 4);
        page[5] = (byte) flags;
        for (var i = 0; i < 8; i++) {
            page[6 + i] = (byte) (granule >>> (8 * i));
        }
        for (var i = 0; i < 4; i++) {
            page[14 + i] = (byte) (serial >>> (8 * i));
            page[18 + i] = (byte) (sequence >>> (8 * i));
        }
        page[26] = (byte) laces.length;
        System.arraycopy(laces, 0, page, 27, laces.length);
        System.arraycopy(body.toByteArray(), 0, page, 27 + laces.length, body.size());
        var crc = new OggCrc().checksum(page, 0, page.length);
        for (var i = 0; i < 4; i++) {
            page[22 + i] = (byte) (crc >>> (8 * i));
        }
        return page;
    }

    /** Returns the offsets of every page of a well-formed file. */
    static List<Integer> pageOffsets(byte[] data) {
        var offsets = new ArrayList<Integer>();
        var offset = 0;
        while (offset < data.length) {
            offsets.add(offset);
            offset += pageSize(data, offset);
        }
        return offsets;
    }

    @Nested
    @DisplayName("damage recovery")
    class Recovery {
        @Test
        @DisplayName("skips garbage between pages and returns the same packets")
        void garbage() {
            var original = Fixtures.read(opus(2, 48000, 1.5));
            var output = new ByteArrayOutputStream();
            var random = new SplittableRandom(7);
            var offsets = pageOffsets(original);
            output.writeBytes(randomBytes(random, 1000));
            for (var i = 0; i < offsets.size(); i++) {
                var end = i + 1 < offsets.size() ? offsets.get(i + 1) : original.length;
                output.writeBytes(Arrays.copyOfRange(original, offsets.get(i), end));
                var junk = randomBytes(random, random.nextInt(300));
                // make the junk contain fake capture patterns too
                if (junk.length > 8) {
                    System.arraycopy("OggS".getBytes(), 0, junk, junk.length / 2, 4);
                }
                output.writeBytes(junk);
            }
            assertPacketsEqual(packets(original), packets(output.toByteArray()));
        }

        @Test
        @DisplayName("drops only the packets of a page whose CRC fails")
        void badCrc() {
            var original = Fixtures.read(Fixtures.tone("opus", 440, 48000, 1, 2.0,
                    List.of("-c:a", "libopus", "-page_duration", "100000")));
            var offsets = pageOffsets(original);
            var damaged = original.clone();
            var victim = offsets.get(5);
            damaged[victim + pageSize(original, victim) - 3] ^= 0x10;
            var expected = packets(original);
            var actual = packets(damaged);
            assertTrue(actual.size() < expected.size(), "the damaged page's packets are gone");
            assertTrue(actual.size() >= expected.size() - 6, "only one page (5 packets) plus a split packet is lost");
            // packets after the damage keep their original timestamps
            assertEquals(expected.getLast().pts(), actual.getLast().pts());
            assertArrayEquals(expected.getLast().data(), actual.getLast().data());
        }

        @Test
        @DisplayName("stops cleanly on a file truncated at any point")
        void truncation() {
            var original = Fixtures.read(opus(2, 48000, 0.6));
            var full = packets(original).size();
            for (var length = 0; length < original.length; length += 97) {
                var prefix = Arrays.copyOf(original, length);
                try {
                    var count = packets(prefix).size();
                    assertTrue(count <= full);
                } catch (MediaException.Malformed expected) {
                    // too short to hold the headers
                }
            }
        }

        @Test
        @DisplayName("rejects input without any Ogg page")
        void notOgg() {
            assertThrows(MediaException.Malformed.class, () -> new OggDemuxer(MediaSource.of(new byte[1000])));
            assertThrows(MediaException.Malformed.class, () -> new OggDemuxer(MediaSource.of(new byte[0])));
        }

        @Test
        @DisplayName("resists hundreds of random mutations")
        void mutations() {
            var inputs = List.of(
                    Fixtures.read(opus(1, 48000, 0.3)),
                    Fixtures.read(Fixtures.tone("ogg", 440, 44100, 2, 0.3, List.of("-c:a", "libvorbis"))),
                    Fixtures.read(Fixtures.tone("oga", 440, 44100, 2, 0.3, List.of("-c:a", "flac"))));
            var stats = AudioMutations.assertRobust(inputs, 150, 0x0995L, Duration.ofSeconds(5), bytes -> {
                try (var demuxer = new OggDemuxer(MediaSource.of(bytes))) {
                    while (demuxer.read() != null) {
                        // drain
                    }
                }
            });
            System.out.println("OggDemuxer mutation outcomes: " + stats);
        }
    }

    static byte[] randomBytes(SplittableRandom random, int length) {
        var bytes = new byte[length];
        for (var i = 0; i < length; i++) {
            bytes[i] = (byte) random.nextInt(256);
        }
        return bytes;
    }

    static List<Packet> packets(byte[] data) {
        try (var demuxer = new OggDemuxer(MediaSource.of(data))) {
            return AudioPipeline.packets(demuxer, 0);
        }
    }

    static void assertPacketsEqual(List<Packet> expected, List<Packet> actual) {
        assertEquals(expected.size(), actual.size(), "packet count");
        for (var i = 0; i < expected.size(); i++) {
            assertEquals(ProbedPacket.of(expected.get(i)), ProbedPacket.of(actual.get(i)), "packet " + i);
        }
    }

    @Nested
    @DisplayName("chained streams")
    class Chained {
        @Test
        @DisplayName("exposes each link as a track whose timestamps continue the previous link")
        void chainedOpus() {
            var first = Fixtures.read(opus(2, 48000, 1.0));
            var second = Fixtures.read(Fixtures.tone("opus", 880, 48000, 1, 0.5, List.of("-c:a", "libopus", "-serial_offset", "77")));
            var chained = new byte[first.length + second.length];
            System.arraycopy(first, 0, chained, 0, first.length);
            System.arraycopy(second, 0, chained, first.length, second.length);
            try (var demuxer = new OggDemuxer(MediaSource.of(chained))) {
                assertEquals(2, demuxer.tracks().size());
                var a = (AudioTrack) demuxer.tracks().get(0);
                var b = (AudioTrack) demuxer.tracks().get(1);
                assertEquals(48000, a.duration());
                assertEquals(24000, b.duration());
                assertEquals(1, b.layout().orElseThrow().channels());
                assertEquals(1_500_000, demuxer.durationMicros());
                var packets = new ArrayList<Packet>();
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    packets.add(packet);
                }
                var firstOfB = packets.stream().filter(p -> p.trackId() == b.id()).findFirst().orElseThrow();
                var lastOfA = packets.stream().filter(p -> p.trackId() == a.id()).reduce((x, y) -> y).orElseThrow();
                assertTrue(packets.indexOf(lastOfA) < packets.indexOf(firstOfB), "links are sequential");
                assertEquals(48000 - 312, firstOfB.pts(), "second link starts where the first ends, minus its pre-skip");
            }
        }

        @Test
        @DisplayName("demuxes chained Vorbis and FLAC links, whose time base comes from their headers, in full")
        void chainedHeaderTimeBase() {
            var parts = List.of(
                    Fixtures.read(Fixtures.tone("ogg", 440, 44100, 2, 0.6, List.of("-c:a", "libvorbis"))),
                    Fixtures.read(Fixtures.tone("oga", 550, 32000, 1, 0.5, List.of("-c:a", "flac"))),
                    Fixtures.read(Fixtures.tone("ogg", 660, 22050, 1, 0.4, List.of("-c:a", "libvorbis"))),
                    Fixtures.read(opus(1, 48000, 0.5)));
            var output = new ByteArrayOutputStream();
            parts.forEach(output::writeBytes);
            try (var demuxer = new OggDemuxer(MediaSource.of(output.toByteArray()))) {
                assertEquals(parts.size(), demuxer.tracks().size());
                var byTrack = new ArrayList<List<Packet>>();
                for (var i = 0; i < parts.size(); i++) {
                    byTrack.add(new ArrayList<>());
                }
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    byTrack.get(packet.trackId()).add(packet);
                }
                var previousEnd = 0L;
                for (var i = 0; i < parts.size(); i++) {
                    var alone = packets(parts.get(i));
                    var chained = byTrack.get(i);
                    assertEquals(alone.size(), chained.size(), "packets of link " + i);
                    var shift = chained.getFirst().pts() - alone.getFirst().pts();
                    for (var j = 0; j < alone.size(); j++) {
                        assertArrayEquals(alone.get(j).data(), chained.get(j).data());
                        assertEquals(alone.get(j).pts() + shift, chained.get(j).pts(), "link " + i + " packet " + j);
                    }
                    var timeBase = demuxer.tracks().get(i).timeBase();
                    assertEquals(previousEnd, timeBase.toMicros(shift), 1_000, "link " + i + " starts where link " + (i - 1) + " ends");
                    var last = chained.getLast();
                    previousEnd = timeBase.toMicros(last.pts() + last.duration());
                }
            }
        }

        @Test
        @DisplayName("handles a chain whose links reuse the same serial number")
        void reusedSerial() {
            var first = Fixtures.read(opus(1, 48000, 0.4));
            var chained = new byte[first.length * 2];
            System.arraycopy(first, 0, chained, 0, first.length);
            System.arraycopy(first, 0, chained, first.length, first.length);
            try (var demuxer = new OggDemuxer(MediaSource.of(chained))) {
                assertEquals(2, demuxer.tracks().size());
                var count = 0;
                while (demuxer.read() != null) {
                    count++;
                }
                assertEquals(2 * packets(first).size(), count);
            }
        }
    }

    @Nested
    @DisplayName("granule semantics")
    class Granules {
        static byte[] opusStream(long firstGranule, long lastGranule, int firstPagePackets, int lastPagePackets) {
            var head = new byte[19];
            System.arraycopy("OpusHead".getBytes(), 0, head, 0, 8);
            head[8] = 1;
            head[9] = 1;
            head[10] = (byte) (312 & 0xFF);
            head[11] = (byte) (312 >> 8);
            var tags = new byte[16];
            System.arraycopy("OpusTags".getBytes(), 0, tags, 0, 8);
            var packet = new byte[]{(byte) 0xF8, 1, 2, 3};
            var output = new ByteArrayOutputStream();
            output.writeBytes(page(0x02, 0, 42, 0, List.of(head)));
            output.writeBytes(page(0, 0, 42, 1, List.of(tags)));
            output.writeBytes(page(0, firstGranule, 42, 2, java.util.Collections.nCopies(firstPagePackets, packet)));
            output.writeBytes(page(0x04, lastGranule, 42, 3, java.util.Collections.nCopies(lastPagePackets, packet)));
            return output.toByteArray();
        }

        static AudioTrack track(byte[] data) {
            try (var demuxer = new OggDemuxer(MediaSource.of(data))) {
                return (AudioTrack) demuxer.tracks().getFirst();
            }
        }

        @Test
        @DisplayName("keeps a stream that starts after granule zero without skipping")
        void lateStart() {
            var data = opusStream(3 * 960 + 1000, 6 * 960 + 1000 - 100, 3, 3);
            var packets = packets(data);
            assertEquals(1000 - 312, packets.getFirst().pts());
            assertEquals(3 * 960 + 1000 - 312, packets.get(3).pts());
            assertEquals(860, packets.getLast().duration(), "end trimmed by 100 samples");
            assertEquals(100, packets.getLast().sideData().discardEnd(), "the trimmed samples are reported");
            assertTrue(packets.subList(0, packets.size() - 1).stream().allMatch(packet -> packet.sideData().isEmpty()));
            var track = track(data);
            assertEquals(0, track.skipSamples());
            assertEquals(6 * 960 - 100 - 312, track.duration(), "the decoder drops the pre-skip from the stream's own samples");
        }

        @Test
        @DisplayName("asks readers to skip the samples before granule zero of a stream that starts early")
        void earlyStart() {
            var data = opusStream(3 * 960 - 500, 6 * 960 - 500, 3, 3);
            var packets = packets(data);
            assertEquals(-500 - 312, packets.getFirst().pts());
            var track = track(data);
            assertEquals(500, track.skipSamples());
            assertEquals(6 * 960 - 500 - 312, track.duration());
        }

        @Test
        @DisplayName("anchors a single-page stream at granule zero because its granule carries end trimming")
        void singlePage() {
            var head = new byte[19];
            System.arraycopy("OpusHead".getBytes(), 0, head, 0, 8);
            head[8] = 1;
            head[9] = 1;
            head[10] = (byte) 312;
            head[11] = (byte) (312 >> 8);
            var tags = new byte[16];
            System.arraycopy("OpusTags".getBytes(), 0, tags, 0, 8);
            var output = new ByteArrayOutputStream();
            output.writeBytes(page(0x02, 0, 9, 0, List.of(head)));
            output.writeBytes(page(0, 0, 9, 1, List.of(tags)));
            output.writeBytes(page(0x04, 2 * 960 + 100, 9, 2, java.util.Collections.nCopies(3, new byte[]{(byte) 0xF8, 0})));
            var packets = packets(output.toByteArray());
            assertEquals(-312, packets.getFirst().pts());
            assertEquals(100, packets.getLast().duration());
            assertEquals(2 * 960 + 100 - 312, track(output.toByteArray()).duration());
        }
    }

    @Nested
    @DisplayName("large files")
    class Large {
        static byte[] bigOpus(int serial, int packets, int size) {
            var head = new byte[19];
            System.arraycopy("OpusHead".getBytes(), 0, head, 0, 8);
            head[8] = 1;
            head[9] = 2;
            head[10] = (byte) 312;
            head[11] = (byte) (312 >> 8);
            var track = AudioTrack.builder().codec(Codec.OPUS)
                    .timeBase(com.github.auties00.smalt.track.Rational.timeBaseOf(48000))
                    .codecConfig(head)
                    .sampleRate(48000)
                    .layout(com.github.auties00.smalt.audio.ChannelLayout.STEREO)
                    .build();
            var bytes = new ByteArrayOutputStream(packets * (size + 4));
            try (var muxer = new OggMuxer(MediaSink.of(bytes), OggMuxerOptions.builder().vendor("v").comments(java.util.Map.of()).maxPacketsPerPage(50).maxPageDurationMicros(1_000_000).serial(serial).build())) {
                muxer.addTrack(track);
                var data = new byte[size];
                data[0] = (byte) 0xF8;
                for (var i = 0; i < packets; i++) {
                    muxer.writePacket(new Packet(0, data, 0, 0, 960, true));
                }
                muxer.finish();
            }
            return bytes.toByteArray();
        }

        @Test
        @DisplayName("reads the duration of a file above the scan limit from its last pages")
        void tailDuration() {
            var data = bigOpus(5, 56_000, 1275);
            assertTrue(data.length > 64 << 20);
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                try (var demuxer = new OggDemuxer(MediaSource.of(data))) {
                    assertEquals(56_000L * 960 - 312, demuxer.tracks().getFirst().duration());
                    assertEquals((56_000L * 960 - 312) * 1_000_000 / 48_000, demuxer.durationMicros(), 1.0);
                }
            });
        }

        @Test
        @DisplayName("falls back to a full scan when the end of a large file belongs to a later link")
        void chainedLarge() {
            var first = bigOpus(5, 56_000, 1275);
            var second = bigOpus(6, 100, 100);
            var chained = new byte[first.length + second.length];
            System.arraycopy(first, 0, chained, 0, first.length);
            System.arraycopy(second, 0, chained, first.length, second.length);
            try (var demuxer = new OggDemuxer(MediaSource.of(chained))) {
                assertEquals(2, demuxer.tracks().size());
                assertEquals(100L * 960 - 312, demuxer.tracks().get(1).duration());
            }
        }
    }

    /** A sized source that is not in memory and counts its reads. */
    static final class CountingSource implements MediaSource {
        final byte[] data;
        long bytes;
        int reads;

        CountingSource(byte[] data) {
            this.data = data;
        }

        @Override
        public long size() {
            return data.length;
        }

        @Override
        public int read(long position, byte[] buffer, int offset, int length) {
            if (position >= data.length) {
                return length == 0 ? 0 : -1;
            }
            var count = (int) Math.min(length, data.length - position);
            System.arraycopy(data, (int) position, buffer, offset, count);
            bytes += count;
            reads++;
            return count;
        }

        @Override
        public void close() {
        }
    }

    @Nested
    @DisplayName("single pass")
    class SinglePass {
        static void assertSameDemux(byte[] data, MediaSource source) {
            try (var counted = new OggDemuxer(source);
                 var memory = new OggDemuxer(MediaSource.of(data))) {
                assertEquals(memory.tracks().size(), counted.tracks().size());
                for (var i = 0; i < memory.tracks().size(); i++) {
                    assertEquals(memory.tracks().get(i).duration(), counted.tracks().get(i).duration());
                }
                assertEquals(memory.durationMicros(), counted.durationMicros());
                assertPacketsEqual(AudioPipeline.packets(memory, 0), AudioPipeline.packets(counted, 0));
            }
        }

        @Test
        @DisplayName("reads every byte of a file once, its last pages at construction and the rest while demuxing")
        void readsOnce() {
            var data = Fixtures.read(opus(2, 48000, 60, "-b:a", "128k"));
            assertTrue(data.length > 512 * 1024);
            var source = new CountingSource(data);
            assertSameDemux(data, source);
            assertTrue(source.bytes <= data.length + 2 * OggPageReader.MAX_PAGE_SIZE, source.bytes + " bytes read for " + data.length);
        }

        @Test
        @DisplayName("reads a grouped file whose streams end far apart once, although construction scans it completely")
        void scannedOnce() {
            var data = Fixtures.read(Fixtures.ffmpeg("ogg", List.of(
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=40",
                    "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=48000:duration=1"),
                    List.of("-map", "0", "-map", "1", "-c:a", "libopus", "-b:a", "256k", "-vbr", "off")));
            assertTrue(data.length > 1024 * 1024, "the short stream ends before the last pages examined");
            var source = new CountingSource(data);
            assertSameDemux(data, source);
            assertTrue(source.bytes <= data.length + 2 * OggPageReader.MAX_PAGE_SIZE, source.bytes + " bytes read for " + data.length);
        }

        @Test
        @DisplayName("reads a small file that is not in memory with a single read")
        void smallFile() {
            var data = Fixtures.read(opus(1, 48000, 3));
            var source = new CountingSource(data);
            assertSameDemux(data, source);
            assertEquals(1, source.reads);
        }

        @Test
        @DisplayName("resists random mutations of a file read through its last pages")
        void mutations() {
            var inputs = List.of(Fixtures.read(opus(2, 48000, 20, "-b:a", "128k")));
            var stats = AudioMutations.assertRobust(inputs, 120, 0x0996L, Duration.ofSeconds(10), bytes -> {
                try (var demuxer = new OggDemuxer(new CountingSource(bytes))) {
                    while (demuxer.read() != null) {
                        // drain
                    }
                }
            });
            System.out.println("OggDemuxer file-source mutation outcomes: " + stats);
        }
    }

    @Nested
    @DisplayName("construction cache")
    class Cache {
        /** A source that counts how many times each byte is delivered. */
        static final class HitSource implements MediaSource {
            final byte[] data;
            final int[] hits;
            final int chunk;

            HitSource(byte[] data, int chunk) {
                this.data = data;
                this.hits = new int[data.length];
                this.chunk = chunk;
            }

            @Override
            public long size() {
                return data.length;
            }

            @Override
            public int read(long position, byte[] buffer, int offset, int length) {
                if (position >= data.length) {
                    return length == 0 ? 0 : -1;
                }
                var count = (int) Math.min(Math.min(length, chunk), data.length - position);
                System.arraycopy(data, (int) position, buffer, offset, count);
                for (var i = 0; i < count; i++) {
                    hits[(int) position + i]++;
                }
                return count;
            }

            @Override
            public void close() {
            }
        }

        @ParameterizedTest(name = "chunk {0}")
        @org.junit.jupiter.params.provider.ValueSource(ints = {1, 1000, 70_000, Integer.MAX_VALUE})
        @DisplayName("returns the source bytes for every read and fetches each byte of its window once until sealed")
        void sameBytes(int chunk) {
            for (var seed = 0; seed < 20; seed++) {
                var random = new SplittableRandom(seed);
                var data = randomBytes(random, 1 + random.nextInt(600_000));
                var from = random.nextInt(Math.max(1, data.length / 4));
                var capacity = random.nextInt(data.length + 1);
                var source = new HitSource(data, chunk);
                var cache = new CachingSource(source, capacity);
                cache.start(from);
                var sealAt = random.nextInt(400);
                var position = 0L;
                for (var step = 0; step < 400; step++) {
                    if (step == sealAt) {
                        for (var i = from; i < Math.min(data.length, (long) from + capacity); i++) {
                            assertTrue(source.hits[i] <= 1, "byte " + i + " fetched " + source.hits[i] + " times");
                        }
                        cache.seal();
                    }
                    position = random.nextInt(5) == 0 ? random.nextInt(data.length + 10)
                            : Math.max(0, Math.min(data.length + 5, position + random.nextInt(140_000) - 40_000));
                    var length = random.nextInt(4) == 0 ? random.nextInt(200_000) : random.nextInt(5000);
                    var target = new byte[length + 3];
                    var done = 0;
                    while (done < length) {
                        var read = cache.read(position + done, target, 3 + done, length - done);
                        if (read <= 0) {
                            break;
                        }
                        done += read;
                    }
                    var expected = (int) Math.max(0, Math.min(length, data.length - position));
                    assertEquals(expected, done, "read at " + position);
                    assertArrayEquals(Arrays.copyOfRange(data, (int) Math.min(position, data.length),
                            (int) Math.min(position, data.length) + expected), Arrays.copyOfRange(target, 3, 3 + done));
                }
            }
        }
    }

    @Test
    @DisplayName("computes the Ogg CRC exactly like a bitwise reference")
    void crc() {
        var random = new SplittableRandom(3);
        for (var length = 0; length < 300; length += 7) {
            var data = randomBytes(random, length);
            var reference = 0;
            for (var b : data) {
                reference ^= (b & 0xFF) << 24;
                for (var bit = 0; bit < 8; bit++) {
                    reference = (reference & 0x80000000) != 0 ? (reference << 1) ^ 0x04C11DB7 : reference << 1;
                }
            }
            assertEquals(reference, new OggCrc().checksum(data, 0, data.length));
        }
    }
}
