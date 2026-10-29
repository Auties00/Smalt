package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.SubtitleCue;
import com.github.auties00.smalt.subtitle.SubtitleEncoder;
import com.github.auties00.smalt.subtitle.SubtitleMarkup;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests TTML subtitles ({@link Codec#TTML}) in MP4 {@code stpp} tracks: demuxing ffmpeg's files,
 * progressive and fragmented muxing, the empty documents written in gaps, the configuration
 * checks, {@link Mp4Remuxer} copies, the edit list of a subtitle track that ends before the movie and
 * robustness against corrupt sample entries. ffprobe is the
 * black-box reader: it must report the same sample entry body (as extradata), times and payloads.
 */
@DisplayName("TTML in MP4")
class Mp4TtmlTest {
    static final Rational MILLIS = Rational.timeBaseOf(1000);

    static final byte[] EMPTY = ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            + "<tt xmlns=\"http://www.w3.org/ns/ttml\" xml:lang=\"\"/>\n").getBytes(StandardCharsets.UTF_8);

    static final byte[] DEFAULT_CONFIG = "http://www.w3.org/ns/ttml\0\0\0".getBytes(StandardCharsets.US_ASCII);

    static byte[] document(String text, long beginMillis, long endMillis) {
        return ("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tt xmlns=\"http://www.w3.org/ns/ttml\" xml:lang=\"en\">"
                + "<body><div><p begin=\"" + beginMillis + "ms\" end=\"" + endMillis + "ms\">" + text
                + "</p></div></body></tt>\n").getBytes(StandardCharsets.UTF_8);
    }

    static DataTrack ttmlTrack(int id, byte[] config) {
        var builder = DataTrack.builder().id(id).codec(Codec.TTML).timeBase(MILLIS);
        if (config != null) {
            builder.codecConfig(config);
        }
        return builder.build();
    }

    static Packet packet(int trackId, byte[] data, long start, long duration) {
        return new Packet(trackId, data, start, start, duration, true);
    }

    static Path srt() {
        try {
            var directory = Files.createDirectories(Path.of("target", "media-fixtures"));
            var srt = directory.resolve("ttml-cues.srt");
            Files.writeString(srt, """
                    1
                    00:00:00,400 --> 00:00:01,200
                    Hello <i>world</i>

                    2
                    00:00:01,500 --> 00:00:02,500
                    Second & line
                    third
                    """, StandardCharsets.UTF_8);
            return srt;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** An MP4 file written by ffmpeg holding its TTML encoding of {@link #srt()}, after the given media. */
    static Path ffmpegTtml(List<String> media, List<String> codecs) {
        var inputs = new ArrayList<>(media);
        inputs.addAll(List.of("-i", srt().toString()));
        var outputs = new ArrayList<String>();
        var subtitleInput = media.stream().filter("-i"::equals).count();
        for (var index = 0; index < subtitleInput; index++) {
            outputs.addAll(List.of("-map", index + ""));
        }
        outputs.addAll(List.of("-map", subtitleInput + ":s"));
        outputs.addAll(codecs);
        outputs.addAll(List.of("-c:s", "ttml"));
        return Fixtures.ffmpeg("mp4", inputs, outputs);
    }

    static Track only(ContainerProbe.Demuxed demuxed, Codec codec) {
        return demuxed.tracks().stream().filter(track -> track.codec() == codec).findFirst().orElseThrow();
    }

    static ContainerProbe.ProbeStream stpp(ContainerProbe.Probe probe) {
        return probe.streams().stream().filter(stream -> "stpp".equals(stream.get("codec_tag_string")))
                .findFirst().orElseThrow();
    }

    /** Returns the payload of the first box at a path of box types, descending into {@code stsd}. */
    static byte[] box(byte[] data, String... path) {
        var buffer = ByteBuffer.wrap(data);
        var start = 0;
        var end = data.length;
        for (var type : path) {
            var found = false;
            var offset = start;
            while (!found && offset + 8 <= end) {
                var size = buffer.getInt(offset) & 0xFFFFFFFFL;
                var name = new String(data, offset + 4, 4, StandardCharsets.ISO_8859_1);
                var header = 8;
                if (size == 1) {
                    size = buffer.getLong(offset + 8);
                    header = 16;
                } else if (size == 0) {
                    size = end - offset;
                }
                if (name.equals(type)) {
                    start = offset + header;
                    end = (int) (offset + size);
                    found = true;
                    if (type.equals("stsd")) {
                        start += 8;
                    }
                } else {
                    offset += (int) size;
                }
            }
            if (!found) {
                return null;
            }
        }
        return Arrays.copyOfRange(data, start, end);
    }

    /** Returns the handler type and media header box of every track, as {@code "hdlr/mhd"}. */
    static List<String> handlers(byte[] file) {
        var moov = box(file, "moov");
        assertNotNull(moov);
        var result = new ArrayList<String>();
        var buffer = ByteBuffer.wrap(moov);
        for (var offset = 0; offset + 8 <= moov.length; offset += buffer.getInt(offset)) {
            if (new String(moov, offset + 4, 4, StandardCharsets.ISO_8859_1).equals("trak")) {
                var trak = Arrays.copyOfRange(moov, offset, offset + buffer.getInt(offset));
                var hdlr = box(trak, "trak", "mdia", "hdlr");
                var minf = box(trak, "trak", "mdia", "minf");
                assertNotNull(hdlr);
                assertNotNull(minf);
                result.add(new String(hdlr, 8, 4, StandardCharsets.ISO_8859_1) + "/"
                        + new String(minf, 4, 4, StandardCharsets.ISO_8859_1));
            }
        }
        return result;
    }

    static byte[] stppBody(byte[] file) {
        var moov = box(file, "moov");
        assertNotNull(moov);
        var buffer = ByteBuffer.wrap(moov);
        for (var offset = 0; offset + 8 <= moov.length; offset += buffer.getInt(offset)) {
            var trak = Arrays.copyOfRange(moov, offset, offset + buffer.getInt(offset));
            var entry = box(trak, "trak", "mdia", "minf", "stbl", "stsd", "stpp");
            if (entry != null) {
                return Arrays.copyOfRange(entry, 8, entry.length);
            }
        }
        throw new AssertionError("no stpp sample entry");
    }

    static void assertSamePackets(List<Packet> expected, Track expectedTrack, List<Packet> actual, Track actualTrack) {
        assertEquals(expected.size(), actual.size(), "packet count");
        for (var index = 0; index < expected.size(); index++) {
            assertArrayEquals(expected.get(index).data(), actual.get(index).data(), "document " + index);
            assertEquals(expectedTrack.timeBase().rescale(expected.get(index).pts(), Rational.MICROSECONDS),
                    actualTrack.timeBase().rescale(actual.get(index).pts(), Rational.MICROSECONDS), "start " + index);
            assertEquals(expectedTrack.timeBase().rescale(expected.get(index).duration(), Rational.MICROSECONDS),
                    actualTrack.timeBase().rescale(actual.get(index).duration(), Rational.MICROSECONDS), "duration " + index);
            assertTrue(actual.get(index).keyframe(), "sync sample " + index);
        }
    }

    @Nested
    @DisplayName("ffmpeg's stpp files")
    class FfmpegFiles {
        @Test
        @DisplayName("demux as TTML tracks whose configuration and documents ffprobe reports alike")
        void demux() {
            var file = ffmpegTtml(List.of(), List.of());
            var bytes = Fixtures.read(file);
            var demuxed = MuxSupport.demux(bytes);
            var track = assertInstanceOf(DataTrack.class, only(demuxed, Codec.TTML));
            assertEquals(TrackKind.DATA, track.kind());
            assertFalse(Codec.TTML.isSubtitle());
            var config = track.codecConfig().orElseThrow();
            assertArrayEquals(stppBody(bytes), config, "configuration is the stpp body after its eight bytes");
            var probe = ContainerProbe.probe(file);
            var stream = stpp(probe);
            assertEquals("ttml", stream.codecName());
            assertArrayEquals(stream.extradata(), config, "ffprobe extradata");
            var packets = demuxed.packetsOf(track);
            var probed = probe.packetsOf(stream.index());
            assertEquals(probed.size(), packets.size());
            for (var index = 0; index < packets.size(); index++) {
                assertEquals(probed.get(index).hash(), ContainerProbe.crc32(packets.get(index).data()), "payload " + index);
                assertEquals(probed.get(index).pts() * stream.timeBaseNumerator() * 1_000_000 / stream.timeBaseDenominator(),
                        track.timeBase().rescale(packets.get(index).pts(), Rational.MICROSECONDS), "start " + index);
            }
            var text = new String(packets.getFirst().data(), StandardCharsets.UTF_8);
            assertTrue(text.startsWith("<?xml") && text.contains("Hello world") && text.contains("Second &amp; line"), text);
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Mp4MuxerOptions.Brand.class, names = {"MP4", "QUICKTIME"})
        @DisplayName("remux through Mp4Muxer into stpp tracks that Smalt, ffprobe and ffmpeg read unchanged")
        void progressive(Mp4MuxerOptions.Brand brand) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var file = ffmpegTtml(Mp4Fixtures.video(160, 120, 3, 25), List.of("-c:v", "libx264", "-g", "25"));
            var source = MuxSupport.demux(Fixtures.read(file));
            var output = MuxSupport.toMemory(source.tracks(), source.order(),
                    sink -> new Mp4Muxer(sink, Mp4MuxerOptions.builder().brand(brand).build()));
            var sourceTrack = only(source, Codec.TTML);
            var result = MuxSupport.demux(output);
            var track = only(result, Codec.TTML);
            assertArrayEquals(sourceTrack.codecConfig().orElseThrow(), track.codecConfig().orElseThrow());
            assertSamePackets(source.packetsOf(sourceTrack), sourceTrack, result.packetsOf(track), track);
            assertArrayEquals(stppBody(Fixtures.read(file)), stppBody(output));
            assertEquals(List.of("vide/vmhd", "subt/sthd"), handlers(output));
            // the content hash in the name keeps ffmpeg's cached copy below in step with the output
            var path = MuxSupport.save("ttml-" + brand + "-" + ContainerProbe.crc32(output).replace(':', '-') + ".mp4", output);
            var reference = ContainerProbe.probe(file);
            var probe = ContainerProbe.probe(path);
            var expected = reference.packetsOf(stpp(reference).index());
            var actual = probe.packetsOf(stpp(probe).index());
            assertEquals("ttml", stpp(probe).codecName());
            assertArrayEquals(stpp(reference).extradata(), stpp(probe).extradata(), "ffprobe extradata");
            assertEquals(expected.stream().map(ContainerProbe.ProbePacket::hash).toList(),
                    actual.stream().map(ContainerProbe.ProbePacket::hash).toList(), "ffprobe payloads");
            assertEquals(expected.stream().map(ContainerProbe.ProbePacket::pts).toList(),
                    actual.stream().map(ContainerProbe.ProbePacket::pts).toList(), "ffprobe starts");
            // without the subtitle track's own edit, ffmpeg would stretch its last sample to the end of the movie
            assertEquals(expected.stream().map(ContainerProbe.ProbePacket::duration).toList(),
                    actual.stream().map(ContainerProbe.ProbePacket::duration).toList(), "ffprobe durations");
            // ffmpeg copies Smalt's stpp track; Smalt reads the copy back unchanged
            var copied = Mp4Fixtures.copy(path, "mp4", List.of(), List.of());
            var again = MuxSupport.demux(Fixtures.read(copied));
            var copiedTrack = only(again, Codec.TTML);
            // ffmpeg recomputes the btrt box after the three strings
            assertArrayEquals(Arrays.copyOf(sourceTrack.codecConfig().orElseThrow(), DEFAULT_CONFIG.length),
                    Arrays.copyOf(copiedTrack.codecConfig().orElseThrow(), DEFAULT_CONFIG.length));
            assertArrayEquals(DEFAULT_CONFIG, Arrays.copyOf(sourceTrack.codecConfig().orElseThrow(), DEFAULT_CONFIG.length));
            assertSamePackets(source.packetsOf(sourceTrack), sourceTrack, again.packetsOf(copiedTrack), copiedTrack);
        }
    }

    @Nested
    @DisplayName("samples")
    class Samples {
        static List<Packet> documents() {
            return List.of(packet(1, document("one", 400, 1200), 400, 800),
                    packet(1, document("two", 1200, 1900), 1200, 700),
                    packet(1, document("three", 2300, 2900), 2300, 600));
        }

        static List<Packet> expected() {
            var documents = documents();
            return List.of(packet(1, EMPTY, 0, 400), documents.get(0), documents.get(1), packet(1, EMPTY, 1900, 400),
                    documents.get(2));
        }

        static List<Function<MediaSink, com.github.auties00.smalt.container.Muxer>> muxers() {
            return List.of(Mp4Muxer::new, sink -> new Mp4Muxer(sink, Mp4MuxerOptions.builder().fastStart(false).build()),
                    sink -> new FragmentedMp4Muxer(sink, FragmentedMp4MuxerOptions.builder().fragmentMicros(500_000).build()));
        }

        @ParameterizedTest
        @MethodSource("muxers")
        @DisplayName("store one document per sample and an empty document in every gap, the track start included")
        void gaps(Function<MediaSink, com.github.auties00.smalt.container.Muxer> factory) {
            var track = ttmlTrack(1, null);
            var output = MuxSupport.toMemory(List.of(track), documents(), factory);
            var result = MuxSupport.demux(output);
            var written = only(result, Codec.TTML);
            assertArrayEquals(DEFAULT_CONFIG, written.codecConfig().orElseThrow());
            assertArrayEquals(DEFAULT_CONFIG, stppBody(output));
            assertSamePackets(expected(), track, result.packetsOf(written), written);
        }

        @Test
        @DisplayName("give a packet without a duration the time until the next one, and drop the last such packet")
        void openDurations() {
            var track = ttmlTrack(1, null);
            var packets = List.of(packet(1, document("a", 0, 500), 0, Packet.UNKNOWN_DURATION),
                    packet(1, document("b", 1000, 1500), 1000, 500),
                    packet(1, document("c", 1500, 1600), 1500, Packet.UNKNOWN_DURATION),
                    packet(1, document("d", 1700, 1800), 1700, 100),
                    packet(1, document("e", 2000, 2100), 2000, Packet.UNKNOWN_DURATION));
            var result = MuxSupport.demux(MuxSupport.toMemory(List.of(track), packets, Mp4Muxer::new));
            var written = only(result, Codec.TTML);
            var expected = List.of(packet(1, packets.get(0).data(), 0, 1000), packets.get(1),
                    packet(1, packets.get(2).data(), 1500, 200), packets.get(3));
            assertSamePackets(expected, track, result.packetsOf(written), written);
        }

        @ParameterizedTest
        @MethodSource("muxers")
        @DisplayName("reject overlapping documents")
        void overlap(Function<MediaSink, com.github.auties00.smalt.container.Muxer> factory) {
            try (var muxer = factory.apply(MediaSink.memory())) {
                muxer.addTrack(ttmlTrack(1, null));
                muxer.writePacket(packet(1, document("a", 0, 1000), 0, 1000));
                var error = assertThrows(IllegalArgumentException.class,
                        () -> muxer.writePacket(packet(1, document("b", 900, 1500), 900, 600)));
                assertTrue(error.getMessage().contains("overlap"), error.getMessage());
            }
        }

        @ParameterizedTest
        @MethodSource("muxers")
        @DisplayName("reject a document that starts before the track")
        void beforeStart(Function<MediaSink, com.github.auties00.smalt.container.Muxer> factory) {
            try (var muxer = factory.apply(MediaSink.memory())) {
                muxer.addTrack(ttmlTrack(1, null));
                var error = assertThrows(IllegalArgumentException.class,
                        () -> muxer.writePacket(packet(1, document("a", 0, 1000), -100, 1000)));
                assertTrue(error.getMessage().contains("before the start of the track"), error.getMessage());
            }
        }

        @Test
        @DisplayName("fragment video and TTML so that Smalt and ffprobe read the progressive file's samples")
        void fragmented() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var video = MuxSupport.demux(Fixtures.read(Fixtures.ffmpeg("mp4", Mp4Fixtures.video(160, 120, 3, 25),
                    List.of("-c:v", "libx264", "-g", "10", "-bf", "0"))));
            var videoTrack = ((VideoTrack) only(video, Codec.H264)).toBuilder().id(1).build();
            var text = ttmlTrack(2, null);
            var packets = new ArrayList<Packet>();
            for (var source : video.packetsOf(only(video, Codec.H264))) {
                packets.add(source.withTrackId(1));
            }
            for (var document : documents()) {
                packets.add(document.withTrackId(2));
            }
            packets.sort(Comparator.comparingLong(packet -> (packet.trackId() == 1 ? videoTrack.timeBase() : MILLIS)
                    .rescale(packet.dts(), Rational.MICROSECONDS)));
            var tracks = List.<Track>of(videoTrack, text);
            var fragmented = MuxSupport.toMemory(tracks, packets,
                    sink -> new FragmentedMp4Muxer(sink, FragmentedMp4MuxerOptions.builder().fragmentMicros(500_000).build()));
            var progressive = MuxSupport.toMemory(tracks, packets, Mp4Muxer::new);
            var trafs = 0;
            for (var moof : FragmentedMp4MuxerTest.boxes(fragmented)) {
                if (moof.type().equals("moof")) {
                    var payload = Arrays.copyOfRange(fragmented, moof.offset(), moof.offset() + moof.size());
                    var tfhd = new String(payload, StandardCharsets.ISO_8859_1);
                    for (var at = tfhd.indexOf("tfhd"); at >= 0; at = tfhd.indexOf("tfhd", at + 4)) {
                        if (ByteBuffer.wrap(payload).getInt(at + 8) == 2) {
                            trafs++;
                        }
                    }
                }
            }
            assertTrue(trafs >= 3, "TTML samples in several fragments, got " + trafs);
            var expected = new ArrayList<Packet>();
            for (var sample : expected()) {
                expected.add(sample.withTrackId(2));
            }
            for (var output : List.of(fragmented, progressive)) {
                var result = MuxSupport.demux(output);
                var written = only(result, Codec.TTML);
                assertSamePackets(expected, text, result.packetsOf(written), written);
                assertEquals(video.packetsOf(only(video, Codec.H264)).size(), result.packetsOf(only(result, Codec.H264)).size());
            }
            for (var entry : List.of(MuxSupport.save("ttml-fragmented.mp4", fragmented),
                    MuxSupport.save("ttml-progressive.mp4", progressive))) {
                var probe = ContainerProbe.probe(entry);
                var stream = stpp(probe);
                assertEquals("ttml", stream.codecName());
                assertArrayEquals(DEFAULT_CONFIG, stream.extradata());
                var probed = probe.packetsOf(stream.index());
                assertEquals(expected.stream().map(sample -> ContainerProbe.crc32(sample.data())).toList(),
                        probed.stream().map(ContainerProbe.ProbePacket::hash).toList(), "ffprobe payloads of " + entry);
                assertEquals(expected.stream().map(sample -> sample.pts() * 1000).toList(),
                        probed.stream().map(sample -> sample.pts() * stream.timeBaseNumerator() * 1_000_000
                                / stream.timeBaseDenominator()).toList(), "ffprobe starts of " + entry);
                if (entry.getFileName().toString().contains("progressive")) {
                    // ffmpeg reports no duration for fragment text samples
                    assertEquals(expected.stream().map(sample -> sample.duration() * 1000).toList(),
                            probed.stream().map(sample -> sample.duration()
                                    * stream.timeBaseNumerator() * 1_000_000 / stream.timeBaseDenominator()).toList(),
                            "ffprobe durations");
                }
            }
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {
        static List<byte[]> malformed() {
            return List.of("http://www.w3.org/ns/ttml".getBytes(StandardCharsets.US_ASCII),
                    "http://www.w3.org/ns/ttml\0\0".getBytes(StandardCharsets.US_ASCII),
                    "\0\0\0".getBytes(StandardCharsets.US_ASCII),
                    new byte[]{'a', (byte) 0xC3, 0, 0, 0},
                    "http://www.w3.org/ns/ttml\0\0\0\0\0\0".getBytes(StandardCharsets.US_ASCII),
                    concat(DEFAULT_CONFIG, new byte[]{0, 0, 0, 20, 'b', 't', 'r', 't', 0, 0, 0, 0}),
                    concat(DEFAULT_CONFIG, new byte[]{0, 0, 0, 4, 'f', 'r', 'e', 'e'}));
        }

        static byte[] concat(byte[] first, byte[] second) {
            var out = new ByteArrayOutputStream();
            out.writeBytes(first);
            out.writeBytes(second);
            return out.toByteArray();
        }

        @ParameterizedTest
        @MethodSource("malformed")
        @DisplayName("reject a malformed stpp body")
        void rejects(byte[] config) {
            for (var muxer : List.of(new Mp4Muxer(MediaSink.memory()), new FragmentedMp4Muxer(MediaSink.memory()))) {
                try (muxer) {
                    assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(ttmlTrack(1, config)));
                }
            }
        }

        @Test
        @DisplayName("keep the namespaces, schema location, MIME types and child boxes of the configuration")
        void keeps() {
            var mime = "application/ttml+xml;codecs=im1t".getBytes(StandardCharsets.US_ASCII);
            var mimeBox = new byte[12 + mime.length + 1];
            ByteBuffer.wrap(mimeBox).putInt(mimeBox.length).put("mime".getBytes(StandardCharsets.US_ASCII)).putInt(0)
                    .put(mime);
            var config = concat(("http://www.w3.org/ns/ttml http://www.w3.org/ns/ttml/profile/imsc1/text\0"
                    + "http://example.org/ttml.xsd\0image/png\0").getBytes(StandardCharsets.UTF_8), mimeBox);
            var track = ttmlTrack(1, config);
            var packets = List.of(packet(1, document("x", 0, 500), 0, 500));
            for (var factory : Samples.muxers()) {
                var output = MuxSupport.toMemory(List.of(track), packets, factory);
                assertArrayEquals(config, stppBody(output));
                var result = MuxSupport.demux(output);
                assertArrayEquals(config, only(result, Codec.TTML).codecConfig().orElseThrow());
            }
        }

        @Test
        @DisplayName("follow the brands: MP4 and QuickTime carry TTML, M4A does not")
        void brands() {
            assertTrue(Mp4Muxer.supports(Mp4MuxerOptions.Brand.MP4, Codec.TTML));
            assertTrue(Mp4Muxer.supports(Mp4MuxerOptions.Brand.QUICKTIME, Codec.TTML));
            assertFalse(Mp4Muxer.supports(Mp4MuxerOptions.Brand.M4A, Codec.TTML));
            assertTrue(FragmentedMp4Muxer.supports(Codec.TTML));
            assertTrue(Containers.isMuxable(ContainerFormat.MP4, Codec.TTML));
            try (var muxer = new Mp4Muxer(MediaSink.memory(), Mp4MuxerOptions.builder().brand(Mp4MuxerOptions.Brand.M4A).build())) {
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(ttmlTrack(1, null)));
            }
        }

        @Test
        @DisplayName("reject a configuration change, which the sample entry fixes")
        void configChange() {
            try (var muxer = new Mp4Muxer(MediaSink.memory())) {
                muxer.addTrack(ttmlTrack(1, null));
                var changed = packet(1, document("x", 0, 10), 0, 10).withSideData(PacketSideData.builder()
                        .newCodecConfig("http://www.w3.org/ns/ttml http://example.org/x\0\0\0".getBytes(StandardCharsets.US_ASCII))
                        .build());
                assertThrows(MediaException.Unsupported.class, () -> muxer.writePacket(changed));
            }
        }
    }

    @Nested
    @DisplayName("Mp4Remuxer")
    class Remux {
        @Test
        @DisplayName("copies an stpp track with the subtitle tracks, from the sample index")
        void planned() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var input = ffmpegTtml(Mp4Fixtures.both(160, 120, 3, 48000), List.of("-c:v", "libx264", "-g", "25", "-c:a", "aac"));
            assertCopied(input, "ttml-planned");
        }

        @Test
        @DisplayName("copies an stpp track through the spool when another track needs its frames")
        void spooled() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var input = ffmpegTtml(Mp4Fixtures.both(160, 120, 3, 48000), List.of("-c:v", "libx264", "-g", "25", "-c:a", "ac3"));
            assertCopied(input, "ttml-spooled");
        }

        @Test
        @DisplayName("leaves the stpp track out unless subtitles are selected")
        void notSelected() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var input = ffmpegTtml(Mp4Fixtures.both(160, 120, 3, 48000), List.of("-c:v", "libx264", "-g", "25", "-c:a", "aac"));
            var output = Mp4RemuxerTest.remux(input, "ttml-none", RemuxOptions.video());
            var after = ContainerProbe.demux(output.file(), Mp4Demuxer::new);
            assertTrue(after.tracks().stream().noneMatch(track -> track.codec() == Codec.TTML));
        }

        static void assertCopied(Path input, String name) {
            var output = Mp4RemuxerTest.remux(input, name, RemuxOptions.video().toBuilder().allSubtitleTracks().build());
            var before = ContainerProbe.demux(input, Mp4Demuxer::new);
            var after = ContainerProbe.demux(output.file(), Mp4Demuxer::new);
            assertEquals(3, after.tracks().size());
            var source = only(before, Codec.TTML);
            var copy = only(after, Codec.TTML);
            assertArrayEquals(source.codecConfig().orElseThrow(), copy.codecConfig().orElseThrow());
            assertSamePackets(before.packetsOf(source), source, after.packetsOf(copy), copy);
            var probe = ContainerProbe.probe(output.file());
            var reference = ContainerProbe.probe(input);
            assertEquals(reference.packetsOf(stpp(reference).index()).stream().map(ContainerProbe.ProbePacket::hash).toList(),
                    probe.packetsOf(stpp(probe).index()).stream().map(ContainerProbe.ProbePacket::hash).toList());
        }
    }

    @Nested
    @DisplayName("subtitle edit lists")
    class EditLists {
        static final AudioTrack AUDIO = AudioTrack.builder().id(1).codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(8000))
                .sampleRate(8000).layout(ChannelLayout.MONO).build();

        /** Returns three seconds of PCM and one subtitle shown from 0.4 s to {@code endMillis}, in file order. */
        static List<Packet> packets(Codec codec, Track text, long endMillis) {
            var packets = new ArrayList<Packet>();
            for (var second = 0; second < 3; second++) {
                packets.add(new Packet(1, new byte[16000], 8000L * second, 8000L * second, 8000, true));
            }
            if (codec == Codec.TTML) {
                packets.add(1, packet(2, document("a", 400, endMillis), 400, endMillis - 400));
            } else {
                var cue = new SubtitleEncoder(text).encode(SubtitleCue.of(400_000, endMillis * 1000, "a"), SubtitleMarkup.SUBRIP);
                packets.add(1, cue);
            }
            return packets;
        }

        static Track text(Codec codec) {
            return codec == Codec.TTML ? ttmlTrack(2, null) : SubtitleEncoder.track(codec, 2);
        }

        /** Returns each track's edit list as {@code [segment_duration, media_time]} pairs, or null without one. */
        static List<long[][]> edits(byte[] file) {
            var moov = box(file, "moov");
            assertNotNull(moov);
            var result = new ArrayList<long[][]>();
            var buffer = ByteBuffer.wrap(moov);
            for (var offset = 0; offset + 8 <= moov.length; offset += buffer.getInt(offset)) {
                if (!new String(moov, offset + 4, 4, StandardCharsets.ISO_8859_1).equals("trak")) {
                    continue;
                }
                var trak = Arrays.copyOfRange(moov, offset, offset + buffer.getInt(offset));
                var elst = box(trak, "trak", "edts", "elst");
                if (elst == null) {
                    result.add(null);
                    continue;
                }
                var entries = ByteBuffer.wrap(elst);
                var version = elst[0];
                var count = entries.getInt(4);
                var rows = new long[count][];
                for (var entry = 0; entry < count; entry++) {
                    rows[entry] = version == 1
                            ? new long[]{entries.getLong(8 + 20 * entry), entries.getLong(16 + 20 * entry)}
                            : new long[]{entries.getInt(8 + 12 * entry) & 0xFFFFFFFFL, entries.getInt(12 + 12 * entry)};
                }
                result.add(rows);
            }
            return result;
        }

        static long movieTimescale(byte[] file) {
            var mvhd = box(file, "moov", "mvhd");
            assertNotNull(mvhd);
            return ByteBuffer.wrap(mvhd).getInt(mvhd[0] == 1 ? 20 : 12) & 0xFFFFFFFFL;
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"MOV_TEXT", "WEBVTT", "TTML"})
        @DisplayName("bound a subtitle track that ends before the movie to its own length")
        void bounded(Codec codec) {
            var text = text(codec);
            var output = MuxSupport.toMemory(List.of(AUDIO, text), packets(codec, text, 1200), Mp4Muxer::new);
            var edits = edits(output);
            assertEquals(2, edits.size());
            assertEquals(null, edits.get(0), "the audio track keeps no edit list");
            var subtitle = edits.get(1);
            assertNotNull(subtitle, "the subtitle track gets an edit list");
            assertEquals(1, subtitle.length);
            assertEquals(movieTimescale(output) * 1200 / 1000, subtitle[0][0], "the edit covers the track");
            assertEquals(0, subtitle[0][1], "the edit starts at the first sample");
            var result = MuxSupport.demux(output);
            var packets = result.packetsOf(result.tracks().get(1));
            assertEquals(1_200_000, result.tracks().get(1).timeBase().rescale(packets.getLast().pts() + packets.getLast().duration(),
                    Rational.MICROSECONDS));
            if (codec != Codec.WEBVTT && Oracle.find(Oracle.Tool.FFPROBE).isPresent()) {
                var probe = ContainerProbe.probe(MuxSupport.save("subtitle-edit-" + codec + ".mp4", output));
                var stream = probe.streams().get(1);
                var last = probe.packetsOf(stream.index()).getLast();
                // without the edit, ffmpeg stretches the last sample to the end of the movie (3 s)
                assertEquals(1_200_000, (last.pts() + last.duration()) * stream.timeBaseNumerator() * 1_000_000
                        / stream.timeBaseDenominator());
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"MOV_TEXT", "WEBVTT", "TTML"})
        @DisplayName("write no edit list for a subtitle track that lasts as long as the movie or longer")
        void unbounded(Codec codec) {
            var text = text(codec);
            var output = MuxSupport.toMemory(List.of(AUDIO, text), packets(codec, text, 3600), Mp4Muxer::new);
            var edits = edits(output);
            assertEquals(null, edits.get(0));
            assertEquals(null, edits.get(1));
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        static byte[] file() {
            return MuxSupport.toMemory(List.of(ttmlTrack(1, null)), Samples.documents(), Mp4Muxer::new);
        }

        @Test
        @DisplayName("reads corrupt stpp entries as TTML only when well formed, so that they mux again")
        void corruptEntries() {
            var original = file();
            var at = new String(original, StandardCharsets.ISO_8859_1).indexOf("stpp");
            var size = ByteBuffer.wrap(original).getInt(at - 4);
            var random = new Random(0x5770);
            var ttml = 0;
            var data = 0;
            for (var round = 0; round < 400; round++) {
                var corrupt = original.clone();
                for (var flips = 1 + random.nextInt(3); flips > 0; flips--) {
                    var position = at + 12 + random.nextInt(size - 12);
                    corrupt[position] = switch (random.nextInt(3)) {
                        case 0 -> 0;
                        case 1 -> (byte) 0xFF;
                        default -> (byte) random.nextInt(256);
                    };
                }
                ContainerProbe.Demuxed demuxed;
                try {
                    demuxed = MuxSupport.demux(corrupt);
                } catch (MediaException expected) {
                    continue;
                }
                var track = demuxed.tracks().getFirst();
                if (track.codec() == Codec.TTML) {
                    ttml++;
                    var again = MuxSupport.demux(MuxSupport.toMemory(demuxed.tracks(), demuxed.order(), Mp4Muxer::new));
                    assertArrayEquals(track.codecConfig().orElseThrow(), again.tracks().getFirst().codecConfig().orElseThrow());
                } else {
                    assertEquals(Codec.DATA, track.codec());
                    data++;
                }
            }
            assertTrue(ttml > 0 && data > 0, "both outcomes reached: " + ttml + " TTML, " + data + " data");
        }

        @Test
        @DisplayName("fails truncated files with MediaException only")
        void truncated() {
            var original = file();
            for (var length = 0; length < original.length; length += 7) {
                var truncated = Arrays.copyOf(original, length);
                try (var demuxer = new Mp4Demuxer(MediaSource.of(truncated))) {
                    while (demuxer.read() != null) {
                        assertNotNull(demuxer.tracks());
                    }
                } catch (MediaException expected) {
                    // a corrupt file may be refused
                }
            }
        }
    }
}
