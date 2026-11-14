package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the DTS carriage of the transport stream muxer: the Blu-ray stream types of M2TS and the DVB DTS and DTS-HD
 * descriptors of a plain transport stream, chosen from the first access unit of an ffmpeg-encoded core, the dcadec
 * streams of the FFmpeg FATE suite and a self-made DTS Express stream, each read back by Smalt and by ffmpeg.
 */
final class TsDtsTest {
    /**
     * One extension substream frame of a DTS Express stream: a 48 kHz stereo LBR asset (coding mode 0, component
     * mask {@code 0x100}) whose LBR frame chunk is empty. Written by the scratch LBR stream writer of the DTS Express
     * work; ffmpeg 7.1 decodes it as 4096 samples of DTS Express.
     */
    private static final byte[] EXPRESS_FRAME = HexFormat.of().parseHex("6458202500034005bb8080800481403f007c00080080"
            + "002400aeca0a801921020c02000008141100f400f4860000");

    /**
     * A source stream, its expected Blu-ray stream type and the {@code asset_construction} codes (core substream
     * first, then the extension substream's asset) its DTS-HD descriptor carries, or none for a core alone.
     */
    record Source(String name, int blurayType, String profile, int[] constructions, int extendedSurround) {
        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> sources() {
        var sources = List.of(
                new Source("core", 0x82, "DTS", new int[0], 0),
                new Source("core_51_24_48_768_0", 0x82, "DTS", new int[0], 0),
                new Source("xch_61_24_48_768", 0x82, "DTS-ES", new int[0], 2),
                new Source("x96_51_24_96_1509", 0x82, "DTS 96/24", new int[0], 0),
                new Source("xbr_51_24_48_3840", 0x85, "DTS-HD HRA", new int[]{1, 6}, 0),
                new Source("xbr_xch_61_24_48_3840", 0x85, "DTS-HD HRA", new int[]{2, 7}, 0),
                new Source("xxch_71_24_48_2046", 0x85, "DTS-HD HRA", new int[]{1, 5}, 0),
                new Source("x96_xxch_71_24_96_3840", 0x85, "DTS-HD HRA", new int[]{1, 13}, 0),
                new Source("xll_51_24_48_768", 0x86, "DTS-HD MA", new int[]{1, 14}, 0),
                new Source("xll_xch_61_24_48_768", 0x86, "DTS-HD MA", new int[]{2, 15}, 0),
                new Source("xll_51_24_48_none", 0x86, "DTS-HD MA", new int[]{17}, 0),
                new Source("express", 0xA2, "DTS Express", new int[]{18}, 0));
        var arguments = new ArrayList<Arguments>();
        for (var source : sources) {
            arguments.add(Arguments.of(source, false));
            arguments.add(Arguments.of(source, true));
        }
        return arguments.stream();
    }

    /**
     * Returns the track and access units of a source.
     */
    private static Demuxed read(Source source) {
        if (source.name().equals("core")) {
            return read(Fixtures.tone("dts", 1000, 48000, 6, 1, List.of("-c:a", "dca", "-strict", "-2")));
        }
        if (!source.name().equals("express")) {
            return read(Conformance.fetchListed("dts-" + source.name() + ".dtshd"));
        }
        var stream = new ByteArrayOutputStream();
        for (var index = 0; index < 40; index++) {
            stream.writeBytes(EXPRESS_FRAME);
        }
        return read(stream.toByteArray());
    }

    /**
     * Returns the track and access units of a fixture file.
     */
    private static Demuxed read(Path fixture) {
        return read(Fixtures.read(fixture));
    }

    /**
     * Returns the track and access units of a stream.
     */
    private static Demuxed read(byte[] data) {
        try (var demuxer = Containers.openDemuxer(MediaSource.of(data))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            var packets = new ArrayList<Packet>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                packets.add(packet);
            }
            return new Demuxed(track, packets);
        }
    }

    private record Demuxed(AudioTrack track, List<Packet> packets) {
        byte[] bytes() {
            var out = new ByteArrayOutputStream();
            for (var packet : packets) {
                out.writeBytes(packet.data());
            }
            return out.toByteArray();
        }
    }

    private static byte[] mux(Demuxed source, boolean m2ts) {
        var sink = MediaSink.memory();
        try (var muxer = new TsMuxer(sink, m2ts ? TsMuxerOptions.m2ts() : TsMuxerOptions.defaults())) {
            muxer.addTrack(source.track());
            for (var packet : source.packets()) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    @ParameterizedTest(name = "{0}, m2ts {1}")
    @MethodSource("sources")
    @DisplayName("declares DTS by its first access unit and writes access units that Smalt and ffmpeg read back")
    void roundTrip(Source source, boolean m2ts) throws Exception {
        var input = read(source);
        var ts = mux(input, m2ts);
        var file = TsTesting.save("dts-" + source.name() + (m2ts ? ".m2ts" : ".ts"), ts);
        var entry = ProgramMap.read(ts, m2ts ? 192 : 188, m2ts ? 4 : 0);
        var first = input.packets().getFirst().data();
        if (m2ts) {
            assertEquals(source.blurayType(), entry.streamType(), "Blu-ray stream type");
            assertTrue(entry.programHdmv(), "the program carries the HDMV registration");
            assertEquals(0, entry.descriptors().length, "no stream descriptors in M2TS");
        } else {
            assertEquals(0x06, entry.streamType());
            assertTrue(!entry.programHdmv());
            var descriptors = entry.descriptors();
            var core = (first[0] & 0xFF) == 0x7F;
            if (core) {
                var samples = (((first[4] & 0x01) << 6 | (first[5] & 0xFC) >> 2) + 1) * 32;
                var identifier = samples == 512 ? "DTS1" : samples == 1024 ? "DTS2" : "DTS3";
                assertEquals(0x05, descriptors[0] & 0xFF, "registration descriptor first");
                assertEquals(identifier, new String(descriptors, 2, 4, java.nio.charset.StandardCharsets.US_ASCII));
                descriptors = java.util.Arrays.copyOfRange(descriptors, 6, descriptors.length);
            }
            if (source.constructions().length == 0) {
                assertDtsDescriptor(descriptors, first, source.extendedSurround());
            } else {
                assertDtsHdDescriptor(descriptors, source, input.track());
            }
        }
        var demuxed = TsTesting.demux(ts);
        var back = (AudioTrack) demuxed.tracks().getFirst();
        assertEquals(Codec.DTS, back.codec());
        assertEquals(input.track().sampleRate(), back.sampleRate());
        var out = new ByteArrayOutputStream();
        for (var packet : demuxed.packetsOf(back)) {
            out.writeBytes(packet.data());
        }
        assertArrayEquals(input.bytes(), out.toByteArray(), "Smalt reads the access units back unchanged");
        assertSinglePerPes(ts, m2ts, input);

        if (!m2ts && (first[0] & 0xFF) != 0x7F) {
            // ffmpeg reads neither the DVB DTS-HD descriptor nor the registration, so a stream without core
            // depends on its probe, which these few frames do not satisfy
            return;
        }
        Oracle.assume(Oracle.Tool.FFPROBE);
        var probe = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-select_streams", "a:0", "-show_entries", "stream=codec_name,profile", "-of", "csv=p=0",
                file.toString()))).lines().filter(line -> !line.isBlank()).findFirst().orElse("");
        assertEquals("dts," + source.profile(), probe, "ffprobe's view of the written stream");
        var copy = file.resolveSibling(file.getFileName() + ".dts");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-y", "-i", file.toString(),
                "-map", "0:a:0", "-c", "copy", "-f", "dts", copy.toString()));
        assertArrayEquals(input.bytes(), Files.readAllBytes(copy), "ffmpeg copies the access units back unchanged");
    }

    @ParameterizedTest(name = "m2ts {0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("a DTS track whose first packet comes after the program map keeps the generic declaration")
    void lateTrack(boolean m2ts) {
        var express = read(new Source("express", 0xA2, "DTS Express", new int[]{18}, 0));
        var second = express.track().toBuilder().id(2).build();
        var sink = MediaSink.memory();
        try (var muxer = new TsMuxer(sink, m2ts ? TsMuxerOptions.m2ts() : TsMuxerOptions.defaults())) {
            muxer.addTrack(express.track());
            muxer.addTrack(second);
            // more than the muxer's one second interleaving window of the first track goes out before the second
            // track's first packet, so the program map is written without it
            for (var packet : express.packets()) {
                muxer.writePacket(packet);
            }
            for (var packet : express.packets()) {
                muxer.writePacket(new Packet(2, packet.data(), packet.pts(), packet.dts(), packet.duration(),
                        packet.keyframe()));
            }
            muxer.finish();
        }
        var ts = sink.toByteArray();
        var entries = programEntries(ts, m2ts ? 192 : 188, m2ts ? 4 : 0);
        assertEquals(2, entries.size(), "two elementary streams");
        if (m2ts) {
            assertEquals(0xA2, entries.get(0)[0], "the first track is declared from its first packet");
            assertEquals(0x86, entries.get(1)[0], "the late track keeps stream type 0x86");
        } else {
            assertEquals(0x06, entries.get(0)[0]);
            assertTrue(entries.get(0)[1] > 0, "the first track carries the DTS-HD descriptor");
            assertEquals(0x06, entries.get(1)[0]);
            assertEquals(0, entries.get(1)[1], "the late track carries no DTS descriptor");
        }
        try (var demuxer = Containers.openDemuxer(MediaSource.of(ts))) {
            assertEquals(2, demuxer.tracks().size());
            for (var track : demuxer.tracks()) {
                assertEquals(Codec.DTS, track.codec(), "Smalt finds DTS on both streams");
            }
        }
    }

    @ParameterizedTest(name = "m2ts {0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @DisplayName("a late DTS-HD track with a core and an extension substream still gets one access unit per PES packet")
    void lateTrackSinglePerPes(boolean m2ts) {
        // access units of a 428-byte core frame followed by an extension substream frame: small enough that the
        // muxer would gather several of them in one PES packet if it did not know they hold two substreams
        var core = read(Fixtures.tone("dts", 1000, 48000, 2, 1, List.of("-c:a", "dca", "-strict", "-2", "-b:a",
                "320k")));
        var units = new ArrayList<Packet>();
        for (var packet : core.packets()) {
            var unit = java.util.Arrays.copyOf(packet.data(), packet.data().length + EXPRESS_FRAME.length);
            System.arraycopy(EXPRESS_FRAME, 0, unit, packet.data().length, EXPRESS_FRAME.length);
            units.add(new Packet(packet.trackId(), unit, packet.pts(), packet.dts(), packet.duration(),
                    packet.keyframe()));
        }
        var hd = new Demuxed(core.track(), units);
        assertTrue(3 * units.getFirst().data().length < 2930, "access units small enough to be gathered");
        var express = read(new Source("express", 0xA2, "DTS Express", new int[]{18}, 0));
        var late = hd.track().toBuilder().id(2).build();
        var sink = MediaSink.memory();
        try (var muxer = new TsMuxer(sink, m2ts ? TsMuxerOptions.m2ts() : TsMuxerOptions.defaults())) {
            muxer.addTrack(express.track());
            muxer.addTrack(late);
            // the first track's packets span more than the interleaving window, so the program map goes out first
            for (var packet : express.packets()) {
                muxer.writePacket(packet);
            }
            for (var packet : hd.packets()) {
                muxer.writePacket(new Packet(2, packet.data(), packet.pts(), packet.dts(), packet.duration(),
                        packet.keyframe()));
            }
            muxer.finish();
        }
        var ts = sink.toByteArray();
        var entries = programEntries(ts, m2ts ? 192 : 188, m2ts ? 4 : 0);
        assertEquals(m2ts ? 0x86 : 0x06, entries.get(1)[0], "the late track keeps the generic declaration");
        var payloads = TsWriter.pesPayloads(m2ts ? strip(ts) : ts, entries.get(1)[2]);
        assertEquals(hd.packets().size(), payloads.size(), "one access unit per PES packet");
        var out = new ByteArrayOutputStream();
        payloads.forEach(out::writeBytes);
        assertArrayEquals(hd.bytes(), out.toByteArray(), "the access units go out unchanged");
    }

    /**
     * {@return the stream type, the length of the codec descriptors (the language descriptor excluded) and the pid of
     * each elementary stream of the first program map of a written stream}
     */
    private static List<int[]> programEntries(byte[] ts, int size, int sync) {
        var pmtPid = -1;
        for (var packet : TsTesting.packets(ts, size, sync)) {
            var pid = TsTesting.pid(packet);
            if ((packet[1] & 0x40) == 0) {
                continue;
            }
            var start = 4 + ((packet[3] & 0x20) != 0 ? 1 + (packet[4] & 0xFF) : 0);
            var section = start + 1 + (packet[start] & 0xFF);
            if (pid == 0 && pmtPid < 0) {
                pmtPid = (packet[section + 10] & 0x1F) << 8 | packet[section + 11] & 0xFF;
            } else if (pid == pmtPid && (packet[section] & 0xFF) == 0x02) {
                var sectionEnd = section + 3 + ((packet[section + 1] & 0x0F) << 8 | packet[section + 2] & 0xFF) - 4;
                var programInfo = (packet[section + 10] & 0x0F) << 8 | packet[section + 11] & 0xFF;
                var entries = new ArrayList<int[]>();
                for (var es = section + 12 + programInfo; es < sectionEnd; ) {
                    var infoLength = (packet[es + 3] & 0x0F) << 8 | packet[es + 4] & 0xFF;
                    var loop = java.util.Arrays.copyOfRange(packet, es + 5, es + 5 + infoLength);
                    entries.add(new int[]{packet[es] & 0xFF, ProgramMap.codecDescriptors(loop).length,
                            (packet[es + 1] & 0x1F) << 8 | packet[es + 2] & 0xFF});
                    es += 5 + infoLength;
                }
                return entries;
            }
        }
        throw new AssertionError("no program map");
    }

    @Test
    @DisplayName("refuses a DTS track whose first packet starts with no DTS sync word")
    void rejectsGarbage() {
        var track = AudioTrack.builder().id(1).codec(Codec.DTS).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                .build();
        try (var muxer = new TsMuxer(MediaSink.memory())) {
            muxer.addTrack(track);
            assertThrows(IllegalArgumentException.class,
                    () -> muxer.writePacket(new Packet(1, new byte[64], 0, 0, 512, true)));
        }
    }

    @Test
    @DisplayName("M2TS DTS and SCTE 35 cannot share a program, where 0x86 is DTS-HD audio")
    void rejectsScte35() {
        var dts = AudioTrack.builder().id(1).codec(Codec.DTS).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                .build();
        var cue = com.github.auties00.smalt.track.DataTrack.builder().id(2).codec(Codec.DATA)
                .timeBase(Rational.timeBaseOf(90000)).handler("scte35").build();
        try (var muxer = new TsMuxer(MediaSink.memory(), TsMuxerOptions.m2ts())) {
            muxer.addTrack(dts);
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(cue));
        }
    }

    /**
     * Checks that every PES packet of a stream with several substreams starts one access unit and holds no other.
     */
    private static void assertSinglePerPes(byte[] ts, boolean m2ts, Demuxed input) {
        var first = input.packets().getFirst().data();
        var several = (first[0] & 0xFF) == 0x7F && first.length > coreSize(first);
        var pid = ProgramMap.read(ts, m2ts ? 192 : 188, m2ts ? 4 : 0).pid();
        var plain = m2ts ? strip(ts) : ts;
        var payloads = TsWriter.pesPayloads(plain, pid);
        for (var payload : payloads) {
            var sync = (payload[0] & 0xFF) << 24 | (payload[1] & 0xFF) << 16 | (payload[2] & 0xFF) << 8
                    | payload[3] & 0xFF;
            assertTrue(sync == 0x7FFE8001 || sync == 0x64582025, "a PES packet starts with a sync word");
        }
        if (several) {
            assertEquals(input.packets().size(), payloads.size(), "one access unit per PES packet");
        } else {
            assertTrue(payloads.size() <= input.packets().size());
        }
    }

    private static int coreSize(byte[] frame) {
        return (((frame[5] & 0x03) << 12) | ((frame[6] & 0xFF) << 4) | ((frame[7] & 0xF0) >> 4)) + 1;
    }

    private static byte[] strip(byte[] m2ts) {
        var out = new ByteArrayOutputStream();
        for (var offset = 0; offset + 192 <= m2ts.length; offset += 192) {
            out.write(m2ts, offset + 4, 188);
        }
        return out.toByteArray();
    }

    /**
     * Checks a DTS descriptor against the header of the first core frame.
     */
    private static void assertDtsDescriptor(byte[] descriptors, byte[] frame, int extendedSurround) {
        assertEquals(0x7B, descriptors[0] & 0xFF, "DTS descriptor tag");
        assertEquals(5, descriptors[1] & 0xFF, "DTS descriptor length");
        var fields = new Bits(descriptors, 2);
        var header = new Bits(frame, 0);
        header.skip(32 + 1 + 5 + 1);
        var nblks = header.read(7);
        var fsize = header.read(14);
        var amode = header.read(6);
        var sfreq = header.read(4);
        var rate = header.read(5);
        header.skip(10);
        var lff = header.read(2);
        assertEquals(sfreq, fields.read(4), "sample_rate_code");
        assertEquals(rate, fields.read(6), "bit_rate_code");
        assertEquals(nblks, fields.read(7), "nblks");
        assertEquals(fsize, fields.read(14), "fsize");
        assertEquals(amode, fields.read(6), "surround_mode");
        assertEquals(lff != 0 ? 1 : 0, fields.read(1), "lfe_flag");
        assertEquals(extendedSurround, fields.read(2), "extended_surround_flag");
    }

    /**
     * Checks a DTS-HD descriptor: its substream flags and, for each substream, the channel count, rate code and asset
     * construction.
     */
    private static void assertDtsHdDescriptor(byte[] descriptors, Source source, AudioTrack track) {
        assertEquals(0x7F, descriptors[0] & 0xFF, "extension descriptor tag");
        assertEquals(descriptors.length - 2, descriptors[1] & 0xFF, "descriptor length");
        assertEquals(0x0E, descriptors[2] & 0xFF, "DTS-HD descriptor tag extension");
        var core = source.constructions().length == 2;
        var flags = descriptors[3] & 0xFF;
        assertEquals((core ? 0x80 : 0) | 0x40 | 0x07, flags, "core and extension substream 0 flags");
        var position = 4;
        for (var index = 0; index < source.constructions().length; index++) {
            var length = descriptors[position] & 0xFF;
            var info = new Bits(descriptors, position + 1);
            assertEquals(0, info.read(3), "num_assets");
            var channels = info.read(5);
            var lfe = info.read(1);
            var frequency = info.read(4);
            info.skip(1 + 2);
            assertEquals(source.constructions()[index], info.read(5), "asset_construction of substream " + index);
            var variable = info.read(1);
            info.skip(3 + 13 + 2);
            var hasLfe = track.layout().orElseThrow().speakers()
                    .contains(com.github.auties00.smalt.audio.Speaker.LOW_FREQUENCY);
            assertEquals(hasLfe ? 1 : 0, lfe, "lfe_flag");
            var extension = !core || index == 1;
            if (extension) {
                assertEquals(track.layout().orElseThrow().channels(), channels, "channel_count");
                assertEquals(source.blurayType() == 0x86 ? 1 : 0, variable, "vbr_flag");
            } else {
                assertEquals(12, frequency, "core sampling_frequency 48 kHz");
            }
            assertEquals(5, length, "substream_info length with one asset");
            position += 1 + length;
        }
        assertEquals(descriptors.length, position, "no trailing bytes");
    }

    /**
     * The program map entry of the first elementary stream of a written stream.
     */
    private record ProgramMap(int pid, int streamType, byte[] descriptors, boolean programHdmv) {
        static ProgramMap read(byte[] ts, int size, int sync) {
            var pmtPid = -1;
            for (var packet : TsTesting.packets(ts, size, sync)) {
                var pid = TsTesting.pid(packet);
                if ((packet[1] & 0x40) == 0) {
                    continue;
                }
                var start = 4 + ((packet[3] & 0x20) != 0 ? 1 + (packet[4] & 0xFF) : 0);
                var section = start + 1 + (packet[start] & 0xFF);
                if (pid == 0 && pmtPid < 0) {
                    pmtPid = (packet[section + 10] & 0x1F) << 8 | packet[section + 11] & 0xFF;
                } else if (pid == pmtPid && (packet[section] & 0xFF) == 0x02) {
                    var programInfo = (packet[section + 10] & 0x0F) << 8 | packet[section + 11] & 0xFF;
                    var hdmv = false;
                    for (var d = section + 12; d < section + 12 + programInfo; d += 2 + (packet[d + 1] & 0xFF)) {
                        if ((packet[d] & 0xFF) == 0x05 && new String(packet, d + 2, 4).equals("HDMV")) {
                            hdmv = true;
                        }
                    }
                    var es = section + 12 + programInfo;
                    var type = packet[es] & 0xFF;
                    var esPid = (packet[es + 1] & 0x1F) << 8 | packet[es + 2] & 0xFF;
                    var infoLength = (packet[es + 3] & 0x0F) << 8 | packet[es + 4] & 0xFF;
                    var loop = java.util.Arrays.copyOfRange(packet, es + 5, es + 5 + infoLength);
                    return new ProgramMap(esPid, type, codecDescriptors(loop), hdmv);
                }
            }
            throw new AssertionError("no program map");
        }

        /**
         * Drops the language descriptor from a descriptor loop.
         */
        private static byte[] codecDescriptors(byte[] loop) {
            var out = new ByteArrayOutputStream();
            for (var d = 0; d < loop.length; d += 2 + (loop[d + 1] & 0xFF)) {
                if ((loop[d] & 0xFF) != 0x0A) {
                    out.write(loop, d, 2 + (loop[d + 1] & 0xFF));
                }
            }
            return out.toByteArray();
        }
    }

    /**
     * A most significant bit first reader over test bytes.
     */
    private static final class Bits {
        private final byte[] data;
        private int position;

        Bits(byte[] data, int offset) {
            this.data = data;
            this.position = offset * 8;
        }

        int read(int count) {
            var value = 0;
            for (var index = 0; index < count; index++) {
                value = value << 1 | (data[position >> 3] >> (7 - (position & 7)) & 1);
                position++;
            }
            return value;
        }

        void skip(int count) {
            position += count;
        }
    }
}
