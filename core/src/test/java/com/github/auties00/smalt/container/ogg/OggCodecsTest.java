package com.github.auties00.smalt.container.ogg;

import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.internal.header.XiphLacing;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.audio.ProbedPacket;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the Theora, Speex and Skeleton mappings: codec identity, granule-position timestamps
 * compared with ffprobe, remuxing through {@link OggMuxer} (and Theora through
 * {@link MatroskaMuxer}) checked with ffprobe and with a second demux, key-frame seeking in Theora,
 * and the Skeleton {@code fisbone} fields, languages and granule mappings of a hand-built file.
 */
@DisplayName("Ogg Theora, Speex and Skeleton")
class OggCodecsTest {
    @TempDir
    Path directory;

    static Path theora(double seconds, int keyframeInterval) {
        return Fixtures.testVideo("ogv", 96, 64, seconds, List.of("-c:v", "libtheora", "-g",
                Integer.toString(keyframeInterval), "-q:v", "6", "-c:a", "libvorbis", "-ac", "1"));
    }

    static Path speex(int rate, List<String> options) {
        var output = new ArrayList<>(List.of("-c:a", "libspeex"));
        output.addAll(options);
        return Fixtures.tone("spx", 440, rate, 1, 1.3, output);
    }

    static Stream<Arguments> files() {
        return Stream.of(
                Arguments.of("theora + vorbis", theora(2.0, 12)),
                Arguments.of("theora long gop", theora(3.0, 45)),
                Arguments.of("speex narrowband", speex(8000, List.of())),
                Arguments.of("speex wideband 3 frames per packet", speex(16000, List.of("-frames_per_packet", "3"))),
                Arguments.of("speex ultra-wideband vbr", speex(32000, List.of("-vbr", "1"))));
    }

    static List<Packet> readAll(OggDemuxer demuxer) {
        var packets = new ArrayList<Packet>();
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            packets.add(packet);
        }
        return packets;
    }

    static List<ProbedPacket> ofTrack(List<Packet> packets, int trackId) {
        return packets.stream().filter(packet -> packet.trackId() == trackId).map(ProbedPacket::of).toList();
    }

    static String selector(Track track, int audioIndex) {
        return track instanceof VideoTrack ? "v:0" : "a:" + audioIndex;
    }

    Path write(byte[] data, String name) {
        try {
            var file = directory.resolve(name);
            Files.write(file, data);
            return file;
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
    }

    static byte[] remux(OggDemuxer demuxer) {
        var output = new ByteArrayOutputStream();
        try (var muxer = new OggMuxer(MediaSink.of(output))) {
            for (var track : demuxer.tracks()) {
                muxer.addTrack(track);
            }
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return output.toByteArray();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    @DisplayName("identifies the codec and returns exactly the packets and timestamps ffprobe reports")
    void matchesFfprobe(String name, Path file) {
        try (var demuxer = new OggDemuxer(MediaSource.of(Fixtures.read(file)))) {
            var packets = readAll(demuxer);
            var audio = 0;
            for (var track : demuxer.tracks()) {
                if (track instanceof VideoTrack video) {
                    assertEquals(Codec.THEORA, video.codec());
                    assertEquals(new Rational(1, 30), video.timeBase());
                    assertEquals(96, video.width());
                } else if (track.codec() != Codec.VORBIS) {
                    assertEquals(Codec.SPEEX, track.codec());
                }
                var expected = ProbedPacket.probe(file, selector(track, audio));
                assertEquals(expected, ofTrack(packets, track.id()), name + " track " + track.id());
                if (track instanceof AudioTrack) {
                    audio++;
                }
            }
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("files")
    @DisplayName("remuxes through OggMuxer into a file ffprobe and the demuxer read as the original")
    void remuxes(String name, Path file) {
        var bytes = Fixtures.read(file);
        List<Packet> original;
        List<Track> tracks;
        try (var demuxer = new OggDemuxer(MediaSource.of(bytes))) {
            tracks = demuxer.tracks();
            original = readAll(demuxer);
        }
        byte[] remuxed;
        try (var demuxer = new OggDemuxer(MediaSource.of(bytes))) {
            remuxed = remux(demuxer);
        }
        var path = write(remuxed, "remuxed.ogg");
        try (var demuxer = new OggDemuxer(MediaSource.of(remuxed))) {
            var again = readAll(demuxer);
            var audio = 0;
            for (var track : tracks) {
                var reread = demuxer.tracks().get(track.id());
                assertEquals(track.codec(), reread.codec());
                assertEquals(track.duration(), reread.duration(), name + " duration of track " + track.id());
                assertEquals(ofTrack(original, track.id()), ofTrack(again, track.id()), name + " track " + track.id());
                var expected = ProbedPacket.probe(file, selector(track, audio));
                assertEquals(expected, ProbedPacket.probe(path, selector(track, audio)), name + " ffprobe of track "
                        + track.id());
                if (track instanceof AudioTrack) {
                    audio++;
                }
            }
        }
        var codecs = ffprobeCodecs(path);
        assertTrue(codecs.contains("theora") || codecs.contains("speex"), codecs);
    }

    static String ffprobeCodecs(Path file) {
        return new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-show_entries", "stream=codec_name", "-of", "csv=p=0", file.toString())), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("remuxes Theora into Matroska as V_THEORA, which ffprobe reads with the same frames")
    void theoraToMatroska() {
        var file = theora(1.5, 10);
        var output = new ByteArrayOutputStream();
        List<Packet> video;
        try (var demuxer = new OggDemuxer(MediaSource.of(Fixtures.read(file)));
             var muxer = new MatroskaMuxer(MediaSink.of(output))) {
            var track = demuxer.tracks().getFirst();
            muxer.addTrack(track);
            var packets = readAll(demuxer).stream().filter(packet -> packet.trackId() == track.id()).toList();
            video = packets;
            for (var packet : packets) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        var path = write(output.toByteArray(), "theora.mkv");
        assertEquals("theora", ffprobeCodecs(path).strip());
        var probed = ProbedPacket.probe(path, "v:0");
        assertEquals(video.size(), probed.size());
        for (var index = 0; index < video.size(); index++) {
            assertEquals(ProbedPacket.crc32(video.get(index).data()), probed.get(index).crc32(), "frame " + index);
            assertEquals(Math.round(video.get(index).pts() * 1000.0 / 30), probed.get(index).pts(), "pts of frame " + index);
        }
        try (var demuxer = new com.github.auties00.smalt.container.matroska.MatroskaDemuxer(MediaSource.of(output.toByteArray()))) {
            var track = demuxer.tracks().getFirst();
            assertEquals(Codec.THEORA, track.codec());
            assertArrayEquals(video.getFirst().data(), demuxer.read().data());
        }
    }

    @Test
    @DisplayName("seeks a Theora stream to the key frame its granule positions name")
    void theoraSeek() {
        var bytes = Fixtures.read(theora(4.0, 40));
        List<Packet> linear;
        try (var demuxer = new OggDemuxer(MediaSource.of(bytes))) {
            linear = readAll(demuxer);
        }
        var video = linear.stream().filter(packet -> packet.trackId() == 0).toList();
        assertTrue(video.stream().filter(Packet::keyframe).count() >= 3, "the stream has several key frames");
        try (var demuxer = new OggDemuxer(MediaSource.of(bytes))) {
            for (var micros : new long[]{0, 400_000, 1_333_333, 1_400_000, 2_700_000, 3_950_000}) {
                demuxer.seek(micros);
                var frame = micros * 30 / 1_000_000;
                var start = -1;
                for (var index = 0; index < video.size(); index++) {
                    if (video.get(index).keyframe() && video.get(index).pts() <= frame) {
                        start = index;
                    }
                }
                Packet first = null;
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    if (packet.trackId() == 0) {
                        first = packet;
                        break;
                    }
                }
                assertTrue(first != null && first.keyframe(), "a key frame first after a seek to " + micros);
                assertEquals(video.get(start).pts(), first.pts(), "first frame after a seek to " + micros);
                assertArrayEquals(video.get(start).data(), first.data());
            }
        }
    }

    @Test
    @DisplayName("rejects tracks and packets the Theora and Speex mappings cannot write")
    void rejects() {
        var muxer = new OggMuxer(MediaSink.of(new ByteArrayOutputStream()));
        assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(VideoTrack.builder().id(0).codec(Codec.THEORA)
                .timeBase(new Rational(1, 25)).width(16).height(16).build()));
        assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(AudioTrack.builder().id(1).codec(Codec.SPEEX)
                .timeBase(new Rational(1, 8000)).sampleRate(8000).codecConfig(new byte[20]).build()));
        assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(AudioTrack.builder().id(2).codec(Codec.THEORA)
                .timeBase(new Rational(1, 25)).build()));
        assertTrue(OggMuxer.supports(Codec.THEORA));
        assertTrue(OggMuxer.supports(Codec.SPEEX));
        var theora = theora(0.5, 5);
        try (var demuxer = new OggDemuxer(MediaSource.of(Fixtures.read(theora)))) {
            var track = demuxer.tracks().getFirst();
            muxer.addTrack(track);
            assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(track.id(),
                    new byte[]{(byte) 0x80, 1, 2}, 0, 0, 1, true)));
        }
    }

    static byte[] muxVideo(VideoTrack track, List<Packet> packets) {
        var output = new ByteArrayOutputStream();
        try (var muxer = new OggMuxer(MediaSink.of(output), OggMuxerOptions.builder().maxPacketsPerPage(7).build())) {
            muxer.addTrack(track);
            packets.forEach(muxer::writePacket);
            muxer.finish();
        }
        return output.toByteArray();
    }

    @Test
    @DisplayName("numbers Theora frames from 0 before bitstream 3.2.1, writing and reading, as ffprobe does")
    void theoraLegacyGranules() {
        var file = theora(2.0, 12);
        VideoTrack track;
        List<Packet> video;
        try (var demuxer = new OggDemuxer(MediaSource.of(Fixtures.read(file)))) {
            track = (VideoTrack) demuxer.tracks().getFirst();
            video = readAll(demuxer).stream().filter(packet -> packet.trackId() == track.id()).toList();
        }
        var headers = XiphLacing.unlace(track.codecConfig().orElseThrow());
        var identification = headers[0].clone();
        assertFalse(TheoraMapping.legacyGranules(identification), "libtheora writes bitstream 3.2.1");
        identification[9] = 0;
        var legacyTrack = track.toBuilder().codecConfig(XiphLacing.lace(identification, headers[1], headers[2])).build();
        var modern = muxVideo(track, video);
        var legacy = muxVideo(legacyTrack, video);
        var shift = TheoraMapping.granuleShift(identification);
        var modernPages = OggChainedTest.pages(modern);
        var legacyPages = OggChainedTest.pages(legacy);
        assertEquals(modernPages.size(), legacyPages.size());
        var dataPages = 0;
        for (var index = 0; index < modernPages.size(); index++) {
            var granule = modernPages.get(index).granule();
            // header pages carry 0, pages on which no frame ends -1; the key frame number of the others moves by one
            var expected = granule > 0 ? granule - (1L << shift) : granule;
            dataPages += granule > 0 ? 1 : 0;
            assertEquals(expected, legacyPages.get(index).granule(), "granule of page " + index);
        }
        assertTrue(dataPages > 3, "the stream spans several data pages");
        try (var demuxer = new OggDemuxer(MediaSource.of(legacy))) {
            assertEquals(track.duration(), demuxer.tracks().getFirst().duration());
            assertEquals(video.stream().map(ProbedPacket::of).toList(), readAll(demuxer).stream().map(ProbedPacket::of).toList());
        }
        assertEquals(ProbedPacket.probe(file, "v:0"), ProbedPacket.probe(write(legacy, "legacy.ogv"), "v:0"));
    }

    @Test
    @DisplayName("rejects Theora frames whose granule position cannot name their key frame")
    void theoraGranuleRange() {
        VideoTrack track;
        try (var demuxer = new OggDemuxer(MediaSource.of(Fixtures.read(theora(0.5, 5))))) {
            track = (VideoTrack) demuxer.tracks().getFirst();
        }
        var shift = TheoraMapping.granuleShift(XiphLacing.unlace(track.codecConfig().orElseThrow())[0]);
        var key = new Packet(track.id(), new byte[]{0x00, 1}, 0, 0, 1, true);
        var inter = new Packet(track.id(), new byte[]{0x40, 1}, 0, 0, 1, false);
        var empty = new Packet(track.id(), new byte[0], 0, 0, 1, false);
        try (var muxer = new OggMuxer(MediaSink.of(new ByteArrayOutputStream()))) {
            muxer.addTrack(track);
            assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(inter));
            assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(empty));
            muxer.writePacket(key);
            for (var frame = 1; frame < 1 << shift; frame++) {
                muxer.writePacket(frame % 2 == 0 ? inter : empty);
            }
            assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(inter));
            muxer.writePacket(key);
            muxer.writePacket(inter);
            muxer.finish();
        }
    }

    /** Builds a page holding whole packets. */
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
        System.arraycopy("OggS".getBytes(StandardCharsets.US_ASCII), 0, page, 0, 4);
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

    static void putLong(byte[] data, int offset, long value) {
        for (var i = 0; i < 8; i++) {
            data[offset + i] = (byte) (value >>> (8 * i));
        }
    }

    static void putInt(byte[] data, int offset, int value) {
        for (var i = 0; i < 4; i++) {
            data[offset + i] = (byte) (value >>> (8 * i));
        }
    }

    /** Builds a Skeleton 4.0 {@code fisbone} packet (Ogg Skeleton 4 specification, byte layout). */
    static byte[] fisbone(int serial, int headers, long rateNumerator, long rateDenominator, int shift, String fields) {
        var text = fields.getBytes(StandardCharsets.UTF_8);
        var packet = new byte[52 + text.length];
        System.arraycopy("fisbone\0".getBytes(StandardCharsets.US_ASCII), 0, packet, 0, 8);
        putInt(packet, 8, 44);
        putInt(packet, 12, serial);
        putInt(packet, 16, headers);
        putLong(packet, 20, rateNumerator);
        putLong(packet, 28, rateDenominator);
        packet[48] = (byte) shift;
        System.arraycopy(text, 0, packet, 52, text.length);
        return packet;
    }

    @Test
    @DisplayName("applies Skeleton fisbone fields, languages and granule mappings to the streams they describe")
    void skeleton() {
        var vorbisFile = Fixtures.read(Fixtures.tone("ogg", 440, 48000, 1, 0.4, List.of("-c:a", "libvorbis",
                "-serial_offset", "100")));
        var vorbisSerial = (vorbisFile[14] & 0xFF) | (vorbisFile[15] & 0xFF) << 8 | (vorbisFile[16] & 0xFF) << 16
                | (vorbisFile[17] & 0xFF) << 24;
        var fishead = new byte[80];
        System.arraycopy("fishead\0".getBytes(StandardCharsets.US_ASCII), 0, fishead, 0, 8);
        fishead[8] = 4;
        putLong(fishead, 20, 1000);
        putLong(fishead, 36, 1000);
        var unknownSerial = 0x7001;
        var shiftedSerial = 0x7002;
        var output = new ByteArrayOutputStream();
        output.writeBytes(page(0x02, 0, 0x5EED, 0, List.of(fishead)));
        var firstPageSize = 27 + (vorbisFile[26] & 0xFF);
        for (var i = 0; i < (vorbisFile[26] & 0xFF); i++) {
            firstPageSize += vorbisFile[27 + i] & 0xFF;
        }
        output.writeBytes(java.util.Arrays.copyOfRange(vorbisFile, 0, firstPageSize));
        output.writeBytes(page(0x02, 0, unknownSerial, 0, List.of("KATE-ish header".getBytes(StandardCharsets.US_ASCII))));
        output.writeBytes(page(0x02, 0, shiftedSerial, 0, List.of("shifted header".getBytes(StandardCharsets.US_ASCII))));
        output.writeBytes(page(0, 0, 0x5EED, 1, List.of(
                fisbone(vorbisSerial, 3, 48000, 1, 0, "Content-Type: audio/vorbis\r\nRole: audio/main\r\nName: main\r\n"
                        + "Language: de\r\n"),
                fisbone(unknownSerial, 1, 1000, 1, 0, "Content-Type: application/x-kate\r\nName: subs\r\nLanguage: fr-CA\r\n"),
                fisbone(shiftedSerial, 1, 25, 1, 6, "Content-Type: video/x-shifted\r\n"))));
        output.writeBytes(page(0x04, 0, 0x5EED, 2, List.of()));
        output.writeBytes(java.util.Arrays.copyOfRange(vorbisFile, firstPageSize, vorbisFile.length));
        output.writeBytes(page(0, 250, unknownSerial, 1, List.of(new byte[]{1})));
        output.writeBytes(page(0, 1250, unknownSerial, 2, List.of(new byte[]{2})));
        output.writeBytes(page(0x04, 2000, unknownSerial, 3, List.of(new byte[]{3})));
        output.writeBytes(page(0, 2L << 6 | 3, shiftedSerial, 1, List.of(new byte[]{4})));
        output.writeBytes(page(0x04, 5L << 6 | 1, shiftedSerial, 2, List.of(new byte[]{5})));
        var bytes = output.toByteArray();
        try (var demuxer = new OggDemuxer(MediaSource.of(bytes))) {
            assertEquals(4, demuxer.tracks().size());
            var skeleton = assertInstanceOf(DataTrack.class, demuxer.tracks().get(0));
            assertEquals("skeleton", skeleton.handler().orElseThrow());
            assertArrayEquals(fishead, skeleton.codecConfig().orElseThrow());
            var vorbis = demuxer.tracks().get(1);
            assertEquals(Codec.VORBIS, vorbis.codec());
            assertEquals("ger", vorbis.language().orElseThrow());
            assertEquals("main", demuxer.trackMetadata(1).get("Name"));
            assertEquals("audio/main", demuxer.trackMetadata(1).get("Role"));
            var unknown = assertInstanceOf(DataTrack.class, demuxer.tracks().get(2));
            assertEquals("fre", unknown.language().orElseThrow());
            assertEquals(Rational.MILLISECONDS, unknown.timeBase());
            assertEquals("application/x-kate", demuxer.trackMetadata(2).get("Content-Type"));
            var shifted = demuxer.tracks().get(3);
            assertEquals(new Rational(1, 25), shifted.timeBase());
            assertFalse(demuxer.trackMetadata(0).containsKey("Name"));
            var packets = readAll(demuxer);
            // the first packet precedes the fisbone that gives the stream its granule mapping
            assertEquals(List.of(Packet.NO_TIMESTAMP, 250L, 1250L, 2000L), packets.stream()
                    .filter(packet -> packet.trackId() == 2).map(Packet::pts).toList());
            assertEquals(List.of(Packet.NO_TIMESTAMP, 5L, 6L), packets.stream().filter(packet -> packet.trackId() == 3)
                    .map(Packet::pts).toList());
            assertEquals(3, packets.stream().filter(packet -> packet.trackId() == 0).count(), "the fisbone packets");
        }
    }
}
