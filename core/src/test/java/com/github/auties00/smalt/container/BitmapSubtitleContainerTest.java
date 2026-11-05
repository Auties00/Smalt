package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.subtitle.SupDemuxer;
import com.github.auties00.smalt.container.subtitle.SupMuxer;
import com.github.auties00.smalt.container.subtitle.VobSubDemuxer;
import com.github.auties00.smalt.container.subtitle.VobSubMuxer;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.subtitle.BitmapSubtitleDecoder;
import com.github.auties00.smalt.subtitle.CaptionService;
import com.github.auties00.smalt.subtitle.SubtitleDecoder;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.subtitle.BitmapStreams;
import com.github.auties00.smalt.testkit.subtitle.CaptionScript;
import com.github.auties00.smalt.testkit.subtitle.TeletextWriter;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carries bitmap subtitle and caption tracks through every container that can hold them: {@code .sup} and VobSub
 * files, MPEG transport streams, Matroska and MP4. Each round trip must give back the packets and the configuration
 * written, ffprobe must read the files Smalt writes as the codecs they are, and the decoders must show the same
 * cues before and after.
 */
@DisplayName("bitmap subtitle and caption containers")
class BitmapSubtitleContainerTest {
    private static final Rational TICKS = Rational.timeBaseOf(90_000);
    private static final byte[] VOBSUB_HEADER = ("# VobSub index file, v7 (do not modify this line!)\nsize: 720x480\n"
            + "palette: 000000, ffffff, 808080, ff0000, 00ff00, 0000ff, ffff00, ff00ff, 00ffff, 404040, c0c0c0, "
            + "800000, 008000, 000080, 808000, 800080\n").getBytes(StandardCharsets.UTF_8);
    private static final byte[] DVB_SERVICE = {'e', 'n', 'g', 0x10, 0, 1, 0, 1};

    static byte[] join(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static DataTrack track(int id, Codec codec, byte[] config, String language) {
        return DataTrack.builder().id(id).codec(codec).timeBase(TICKS).codecConfig(config).language(language).build();
    }

    static List<Packet> pgs(int id) {
        var indices = BitmapStreams.indices(64, 20, (x, y) -> (x / 8 + y / 4) % 3);
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < 4; index++) {
            var shown = index % 2 == 0;
            var data = shown ? join(BitmapStreams.pcs(1920, 1080, index, 0x80, false, 0,
                            new int[]{1, 0, 100 + index, 900, index == 2 ? 0x40 : 0}),
                    BitmapStreams.wds(new int[]{0, 100 + index, 900, 64, 20}),
                    BitmapStreams.pds(0, 0, new int[]{1, 235, 128, 128, 255}, new int[]{2, 81, 90, 240, 200}),
                    join(BitmapStreams.ods(1, 64, 20, indices, 100000).toArray(new byte[0][])),
                    BitmapStreams.pgsEnd())
                    : join(BitmapStreams.pcs(1920, 1080, index, 0, false, 0), BitmapStreams.wds(), BitmapStreams.pgsEnd());
            var pts = 90_000L * (index + 1);
            packets.add(new Packet(id, data, pts, pts, Packet.UNKNOWN_DURATION, shown));
        }
        return packets;
    }

    static List<Packet> dvb(int id) {
        var top = new BitmapStreams.DvbPixels().string(4).run4(40, 1).run4(20, 2).end4().endLine().bytes();
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < 3; index++) {
            var shown = index != 1;
            var data = shown ? join(BitmapStreams.dvbPage(1, 10, 2, new int[]{0, 50, 400 + index}),
                    BitmapStreams.dvbRegion(1, 0, true, 60, 4, 2, 0, 0, new int[]{3, 0, 0}),
                    BitmapStreams.dvbObject(1, 3, top, new byte[0], false), BitmapStreams.dvbEnd(1))
                    : join(BitmapStreams.dvbPage(1, 10, 2), BitmapStreams.dvbEnd(1));
            var pts = 90_000L * (index + 1);
            packets.add(new Packet(id, data, pts, pts, Packet.UNKNOWN_DURATION, true));
        }
        return packets;
    }

    static List<Packet> dvd(int id) {
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < 3; index++) {
            var indices = BitmapStreams.indices(48 + index * 100, 10, (x, y) -> (x * 3 + y) % 4);
            var data = BitmapStreams.spu(48 + index * 100, 10, indices, 30, 400, 0, 40, new int[]{3, 2, 1, 0},
                    new int[]{15, 15, 15, 0}, index == 1);
            var pts = 90_000L * (index + 1);
            packets.add(new Packet(id, data, pts, pts, 40 * 1024, true));
        }
        return packets;
    }

    static List<Packet> teletext(int id) {
        var packets = new ArrayList<Packet>();
        for (var index = 0; index < 3; index++) {
            var writer = new TeletextWriter().header(8, 0x88, true, true, false, 0);
            if (index < 2) {
                writer.row(8, 22, TeletextWriter.codes(new int[]{0x0B, 0x0B}, "line " + index, 0x0A, 0x0A));
            }
            var pts = 90_000L * (index + 1);
            packets.add(new Packet(id, writer.packet(), pts, pts, Packet.UNKNOWN_DURATION, true));
        }
        return packets;
    }

    static List<Packet> captions(int id) {
        var frames = new CaptionScript().command(1, 1, 0x20).preamble(2, 1, 15, 0).text(4, 1, "caption")
                .command(10, 1, 0x2F).command(40, 1, 0x2C)
                .service(3, 1, join(new byte[]{(byte) 0x98, 0x38, 0x4A, 0, 0, 0x1F, 0}, "dtvcc".getBytes()))
                .service(40, 1, new byte[]{(byte) 0x8C, 0x01}).build(50, 10);
        var packets = new ArrayList<Packet>();
        for (var frame = 0; frame < frames.length; frame++) {
            packets.add(new Packet(id, frames[frame], frame * 3003L, frame * 3003L, 3003, true));
        }
        return packets;
    }

    static ContainerProbe.Demuxed roundTrip(List<Track> tracks, List<Packet> packets,
                                            Function<MediaSink, Muxer> muxer) {
        var data = MuxSupport.toMemory(tracks, packets, muxer);
        return MuxSupport.demux(data);
    }

    static void assertSamePackets(List<Packet> written, List<Packet> read, Rational writtenBase, Rational readBase) {
        assertEquals(written.size(), read.size(), "packet count");
        var writtenStart = writtenBase.toMicros(written.getFirst().pts());
        var readStart = readBase.toMicros(read.getFirst().pts());
        for (var index = 0; index < written.size(); index++) {
            assertArrayEquals(written.get(index).data(), read.get(index).data(), "payload " + index);
            assertEquals(writtenBase.toMicros(written.get(index).pts()) - writtenStart,
                    readBase.toMicros(read.get(index).pts()) - readStart, 1000, "time of packet " + index);
        }
    }

    @Nested
    @DisplayName(".sup files")
    class Sup {
        @Test
        @DisplayName("write display sets that read back as they were and that ffprobe reads segment by segment")
        void roundTrip() {
            var track = track(1, Codec.PGS, null, null);
            var packets = pgs(1);
            var data = MuxSupport.toMemory(List.of(track), packets, SupMuxer::new);
            try (var demuxer = Containers.openDemuxer(MediaSource.of(data))) {
                assertInstanceOf(SupDemuxer.class, demuxer);
                assertEquals(ContainerFormat.SUP, demuxer.format());
                var read = ContainerProbe.demuxAll(demuxer);
                assertEquals(Codec.PGS, read.tracks().getFirst().codec());
                assertSamePackets(packets, read.packetsOf(read.tracks().getFirst()), TICKS, TICKS);
                for (var index = 0; index < packets.size(); index++) {
                    assertEquals(packets.get(index).keyframe(), read.packetsOf(read.tracks().getFirst()).get(index)
                            .keyframe(), "an epoch start is a key packet");
                }
                demuxer.seek(2_500_000);
                assertEquals(90_000, demuxer.read().pts(), "a seek lands on the last epoch start before it");
                demuxer.seek(3_000_000);
                assertEquals(270_000, demuxer.read().pts());
            }
            Oracle.assume(Oracle.Tool.FFPROBE);
            var probe = ContainerProbe.probe(MuxSupport.save("pgs.sup", data));
            assertEquals("hdmv_pgs_subtitle", probe.streams().getFirst().codecName());
            var segments = 0;
            for (var packet : packets) {
                for (var position = 0; position < packet.size(); position += 3 + ((packet.data()[position + 1] & 0xFF)
                        << 8 | (packet.data()[position + 2] & 0xFF))) {
                    segments++;
                }
            }
            assertEquals(segments, probe.packets().size());
        }

        @Test
        @DisplayName("rejects a file that does not start with a PG segment")
        void malformed() {
            assertFalse(SupDemuxer.matches(new byte[]{'P', 'G', 0, 0, 0, 0, 0, 0, 0, 0, 0x42, 0, 0}));
            assertThrows(MediaException.Malformed.class, () -> new SupDemuxer(MediaSource.of("PX".repeat(10)
                    .getBytes(StandardCharsets.US_ASCII))));
            try (var muxer = new SupMuxer(MediaSink.memory())) {
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(track(1, Codec.DVB_SUBTITLE, null,
                        null)));
            }
        }
    }

    @Nested
    @DisplayName("VobSub files")
    class VobSub {
        @Test
        @DisplayName("write the pair that reads back with its header, languages, durations and packets")
        void roundTrip() {
            var english = track(1, Codec.DVD_SUBTITLE, VOBSUB_HEADER, "eng");
            var german = track(2, Codec.DVD_SUBTITLE, VOBSUB_HEADER, "ger");
            var packets = new ArrayList<Packet>();
            var first = dvd(1);
            var second = dvd(2);
            for (var index = 0; index < 3; index++) {
                packets.add(first.get(index));
                packets.add(second.get(index));
            }
            var idx = MediaSink.memory();
            var sub = MediaSink.memory();
            try (var muxer = new VobSubMuxer(idx, sub)) {
                muxer.addTrack(english);
                muxer.addTrack(german);
                packets.forEach(muxer::writePacket);
                muxer.finish();
            }
            var index = new String(idx.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(index.contains("id: en, index: 0") && index.contains("id: de, index: 1"), index);
            try (var demuxer = new VobSubDemuxer(MediaSource.of(idx.toByteArray()), MediaSource.of(sub.toByteArray()),
                    DemuxerOptions.defaults())) {
                var read = ContainerProbe.demuxAll(demuxer);
                assertEquals(2, read.tracks().size());
                assertEquals("eng", read.tracks().get(0).language().orElseThrow());
                assertEquals("ger", read.tracks().get(1).language().orElseThrow());
                assertTrue(new String(read.tracks().getFirst().codecConfig().orElseThrow(), StandardCharsets.UTF_8)
                        .contains("palette: 000000, ffffff"));
                assertSamePackets(first, read.packetsOf(read.tracks().get(0)), TICKS, TICKS);
                assertSamePackets(second, read.packetsOf(read.tracks().get(1)), TICKS, TICKS);
                assertEquals(40 * 1024, read.packetsOf(read.tracks().getFirst()).getFirst().duration(),
                        "a unit lasts until its stop command");
            }
            try (var demuxer = Containers.openDemuxer(MediaSource.of(sub.toByteArray()))) {
                assertInstanceOf(VobSubDemuxer.class, demuxer);
                assertTrue(demuxer.tracks().getFirst().codecConfig().isEmpty(), "the program stream holds no palette");
            }
            assertThrows(MediaException.Unsupported.class, () -> Containers.openDemuxer(MediaSource.of(idx.toByteArray())));
            Oracle.assume(Oracle.Tool.FFPROBE);
            var directory = com.github.auties00.smalt.testkit.Scratch.directory("vobsub");
            try {
                java.nio.file.Files.write(directory.resolve("pair.idx"), idx.toByteArray());
                java.nio.file.Files.write(directory.resolve("pair.sub"), sub.toByteArray());
            } catch (java.io.IOException exception) {
                throw new java.io.UncheckedIOException(exception);
            }
            var probe = ContainerProbe.probe(directory.resolve("pair.idx"));
            assertEquals(2, probe.streams().size());
            assertEquals("dvd_subtitle", probe.streams().getFirst().codecName());
            assertEquals(3, probe.packetsOf(0).size());
            for (var packet = 0; packet < 3; packet++) {
                assertEquals(ContainerProbe.crc32(first.get(packet).data()), probe.packetsOf(0).get(packet).hash());
            }
        }
    }

    @Nested
    @DisplayName("MPEG transport streams")
    class TransportStream {
        @Test
        @DisplayName("carry PGS, DVB subtitles and teletext with their descriptors, as ffprobe reads them")
        void roundTrip() {
            var pgs = track(1, Codec.PGS, null, null);
            var dvb = track(2, Codec.DVB_SUBTITLE, DVB_SERVICE, "eng");
            var teletext = track(3, Codec.DVB_TELETEXT, new byte[]{'g', 'e', 'r', 0x02 << 3, (byte) 0x88}, "ger");
            var packets = new ArrayList<Packet>();
            packets.addAll(pgs(1));
            packets.addAll(dvb(2));
            packets.addAll(teletext(3));
            packets.sort(java.util.Comparator.comparingLong(Packet::pts));
            var data = MuxSupport.toMemory(List.of(pgs, dvb, teletext), packets, TsMuxer::new);
            var read = MuxSupport.demux(data);
            assertEquals(List.of(Codec.PGS, Codec.DVB_SUBTITLE, Codec.DVB_TELETEXT),
                    read.tracks().stream().map(Track::codec).toList());
            assertArrayEquals(DVB_SERVICE, read.tracks().get(1).codecConfig().orElseThrow());
            assertArrayEquals(teletext.codecConfig().orElseThrow(), read.tracks().get(2).codecConfig().orElseThrow());
            var readTeletext = read.packetsOf(read.tracks().get(2));
            for (var track = 0; track < 2; track++) {
                var written = track == 0 ? pgs(1) : dvb(2);
                assertSamePackets(written, read.packetsOf(read.tracks().get(track)), TICKS,
                        read.tracks().get(track).timeBase());
            }
            var writtenTeletext = teletext(3);
            assertEquals(3, readTeletext.size());
            for (var index = 0; index < 3; index++) {
                var payload = readTeletext.get(index).data();
                assertEquals(0, (payload.length + 45) % 184, "a teletext PES packet fills whole transport packets");
                assertArrayEquals(writtenTeletext.get(index).data(),
                        java.util.Arrays.copyOf(payload, writtenTeletext.get(index).size()));
            }
            Oracle.assume(Oracle.Tool.FFPROBE);
            var probe = ContainerProbe.probe(MuxSupport.save("subtitles.ts", data));
            assertEquals(List.of("hdmv_pgs_subtitle", "dvb_subtitle", "dvb_teletext"),
                    probe.streams().stream().map(ContainerProbe.ProbeStream::codecName).toList());
            assertEquals("0001000110", HexFormat.of().formatHex(probe.streams().get(1).extradata()));
            var dvbPackets = probe.packetsOf(1);
            var written = dvb(2);
            for (var index = 0; index < written.size(); index++) {
                assertEquals(ContainerProbe.crc32(written.get(index).data()), dvbPackets.get(index).hash(),
                        "ffmpeg reads the same segments");
            }
        }
    }

    @Nested
    @DisplayName("Matroska")
    class Matroska {
        @Test
        @DisplayName("carries PGS, DVB and VobSub tracks with their codec private data")
        void roundTrip() {
            var tracks = List.<Track>of(track(1, Codec.PGS, null, null), track(2, Codec.DVB_SUBTITLE, DVB_SERVICE, "eng"),
                    track(3, Codec.DVD_SUBTITLE, VOBSUB_HEADER, "fre"));
            var packets = new ArrayList<Packet>();
            packets.addAll(pgs(1));
            packets.addAll(dvb(2));
            packets.addAll(dvd(3));
            packets.sort(java.util.Comparator.comparingLong(Packet::pts));
            var data = MuxSupport.toMemory(tracks, packets, MatroskaMuxer::new);
            var read = MuxSupport.demux(data);
            assertEquals(List.of(Codec.PGS, Codec.DVB_SUBTITLE, Codec.DVD_SUBTITLE),
                    read.tracks().stream().map(Track::codec).toList());
            assertArrayEquals(DVB_SERVICE, read.tracks().get(1).codecConfig().orElseThrow());
            assertArrayEquals(VOBSUB_HEADER, read.tracks().get(2).codecConfig().orElseThrow());
            assertSamePackets(pgs(1), read.packetsOf(read.tracks().get(0)), TICKS, read.tracks().get(0).timeBase());
            assertSamePackets(dvb(2), read.packetsOf(read.tracks().get(1)), TICKS, read.tracks().get(1).timeBase());
            assertSamePackets(dvd(3), read.packetsOf(read.tracks().get(2)), TICKS, read.tracks().get(2).timeBase());
            Oracle.assume(Oracle.Tool.FFPROBE);
            var probe = ContainerProbe.probe(MuxSupport.save("bitmaps.mkv", data));
            assertEquals(List.of("hdmv_pgs_subtitle", "dvb_subtitle", "dvd_subtitle"),
                    probe.streams().stream().map(ContainerProbe.ProbeStream::codecName).toList());
            assertEquals("0001000110", HexFormat.of().formatHex(probe.streams().get(1).extradata()));
        }
    }

    @Nested
    @DisplayName("MP4")
    class Mp4 {
        @Test
        @DisplayName("carries VobSub as mp4s with its palette and captions as QuickTime c608 and c708 tracks")
        void roundTrip() {
            var tracks = List.<Track>of(track(1, Codec.DVD_SUBTITLE, VOBSUB_HEADER, "eng"),
                    track(2, Codec.CEA_608, null, null), track(3, Codec.CEA_708, null, null));
            var packets = new ArrayList<Packet>();
            packets.addAll(dvd(1));
            var cc = captions(2);
            for (var packet : cc) {
                var field1Only = new byte[6];
                System.arraycopy(packet.data(), 0, field1Only, 0, 6);
                packets.add(packet.withData(field1Only));
            }
            for (var packet : captions(3)) {
                packets.add(packet.withTrackId(3));
            }
            packets.sort(java.util.Comparator.comparingLong(Packet::pts));
            var data = MuxSupport.toMemory(tracks, packets, Mp4Muxer::new);
            var read = MuxSupport.demux(data);
            assertEquals(List.of(Codec.DVD_SUBTITLE, Codec.CEA_608, Codec.CEA_708),
                    read.tracks().stream().map(Track::codec).toList());
            var header = new String(read.tracks().getFirst().codecConfig().orElseThrow(), StandardCharsets.UTF_8);
            assertTrue(header.contains("size: 720x480"), header);
            assertPalettesClose(new String(VOBSUB_HEADER, StandardCharsets.UTF_8), header);
            assertSamePackets(dvd(1), read.packetsOf(read.tracks().getFirst()), TICKS, read.tracks().getFirst().timeBase());
            var captions608 = read.packetsOf(read.tracks().get(1));
            assertEquals(cc.size(), captions608.size());
            for (var index = 0; index < cc.size(); index++) {
                assertArrayEquals(java.util.Arrays.copyOf(cc.get(index).data(), 6), captions608.get(index).data());
            }
            var captions708 = read.packetsOf(read.tracks().get(2));
            for (var index = 0; index < cc.size(); index++) {
                assertArrayEquals(cc.get(index).data(), captions708.get(index).data());
            }
            var decoder = new SubtitleDecoder(read.tracks().get(2), CaptionService.cea708(1));
            var cues = new ArrayList<com.github.auties00.smalt.subtitle.SubtitleCue>();
            for (var packet : captions708) {
                cues.addAll(decoder.decodeAll(packet));
            }
            assertEquals("dtvcc", cues.getFirst().text());
            Oracle.assume(Oracle.Tool.FFPROBE);
            var probe = ContainerProbe.probe(MuxSupport.save("bitmaps.mp4", data));
            assertEquals("dvd_subtitle", probe.streams().getFirst().codecName());
            assertEquals("eia_608", probe.streams().get(1).codecName());
        }

        @Test
        @DisplayName("refuses PGS and DVB subtitles, which have no MP4 sample entry")
        void unsupported() {
            assertFalse(Mp4Muxer.supports(com.github.auties00.smalt.container.mp4.Mp4MuxerOptions.Brand.MP4, Codec.PGS));
            assertFalse(Mp4Muxer.supports(com.github.auties00.smalt.container.mp4.Mp4MuxerOptions.Brand.MP4,
                    Codec.DVB_SUBTITLE));
            assertTrue(Mp4Muxer.supports(com.github.auties00.smalt.container.mp4.Mp4MuxerOptions.Brand.QUICKTIME,
                    Codec.CEA_608));
        }
    }

    /**
     * Asserts that the palettes of two VobSub headers agree within the rounding of a Y'CrCb round trip.
     */
    static void assertPalettesClose(String expected, String actual) {
        var a = palette(expected);
        var b = palette(actual);
        for (var index = 0; index < 16; index++) {
            for (var shift = 0; shift <= 16; shift += 8) {
                var difference = Math.abs((a[index] >> shift & 0xFF) - (b[index] >> shift & 0xFF));
                assertTrue(difference <= 2, "palette entry " + index + ": " + Integer.toHexString(a[index]) + " and "
                        + Integer.toHexString(b[index]));
            }
        }
    }

    static int[] palette(String header) {
        for (var line : header.split("\n")) {
            if (line.startsWith("palette:")) {
                var parts = line.substring(8).split(",");
                var colors = new int[16];
                for (var index = 0; index < 16; index++) {
                    colors[index] = Integer.parseInt(parts[index].trim(), 16);
                }
                return colors;
            }
        }
        throw new AssertionError("no palette in " + header);
    }

    /**
     * Returns cues with times counted from the first cue's start, for containers that move the timeline.
     */
    static List<com.github.auties00.smalt.subtitle.BitmapCue> relative(List<com.github.auties00.smalt.subtitle.BitmapCue> cues) {
        var start = cues.getFirst().startMicros();
        return cues.stream().map(cue -> cue.withTiming((cue.startMicros() - start) / 1000,
                (cue.endMicros() - start) / 1000)).toList();
    }

    @Test
    @DisplayName("decode to the same cues whatever container carried them")
    void sameCues() {
        var pgs = track(1, Codec.PGS, null, null);
        var direct = decodeAll(pgs, pgs(1));
        var viaMkv = MuxSupport.demux(MuxSupport.toMemory(List.of(pgs), pgs(1), MatroskaMuxer::new));
        assertEquals(relative(direct), relative(decodeAll(viaMkv.tracks().getFirst(),
                viaMkv.packetsOf(viaMkv.tracks().getFirst()))));
        var viaTs = MuxSupport.demux(MuxSupport.toMemory(List.of(pgs), pgs(1), TsMuxer::new));
        assertEquals(relative(direct), relative(decodeAll(viaTs.tracks().getFirst(),
                viaTs.packetsOf(viaTs.tracks().getFirst()))));
        var viaSup = MuxSupport.demux(MuxSupport.toMemory(List.of(pgs), pgs(1), SupMuxer::new));
        assertEquals(relative(direct), relative(decodeAll(viaSup.tracks().getFirst(),
                viaSup.packetsOf(viaSup.tracks().getFirst()))));
        assertEquals(2, direct.size());
        assertTrue(direct.get(1).forced());
    }

    static List<com.github.auties00.smalt.subtitle.BitmapCue> decodeAll(Track track, List<Packet> packets) {
        var decoder = new BitmapSubtitleDecoder(track);
        var cues = new ArrayList<com.github.auties00.smalt.subtitle.BitmapCue>();
        for (var packet : packets) {
            cues.addAll(decoder.decode(packet));
        }
        cues.addAll(decoder.flush());
        return cues;
    }
}
