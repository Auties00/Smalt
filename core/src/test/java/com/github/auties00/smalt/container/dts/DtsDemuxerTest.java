package com.github.auties00.smalt.container.dts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Demuxer;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Conformance;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the raw DTS and DTS-HD file demuxer, and the DTS mappings of MPEG-TS, MP4 and Matroska, against the frames
 * ffmpeg's DTS encoder writes and the dcadec streams of the FFmpeg FATE suite.
 */
final class DtsDemuxerTest {
    private static Path encoded(String name, String format, List<String> extra) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var file = Scratch.directory("dts-demuxer").resolve(name);
        var arguments = new ArrayList<>(List.of("-v", "error", "-y", "-f", "lavfi", "-i",
                "sine=frequency=1000:sample_rate=48000:duration=0.5", "-ac", "6", "-c:a", "dca", "-strict", "-2"));
        arguments.addAll(extra);
        arguments.addAll(List.of("-f", format, file.toString()));
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), arguments);
        return file;
    }

    private static List<Packet> packets(Demuxer demuxer) {
        var packets = new ArrayList<Packet>();
        Packet packet;
        while ((packet = demuxer.read()) != null) {
            packets.add(packet);
        }
        return packets;
    }

    private static byte[] concatenate(List<Packet> packets) {
        var size = 0;
        for (var packet : packets) {
            size += packet.data().length;
        }
        var out = new byte[size];
        var position = 0;
        for (var packet : packets) {
            System.arraycopy(packet.data(), 0, out, position, packet.data().length);
            position += packet.data().length;
        }
        return out;
    }

    @Test
    @DisplayName("a raw stream splits into its core frames, timed in core samples")
    void rawStream() throws IOException {
        var file = encoded("tone.dts", "dts", List.of());
        var bytes = Files.readAllBytes(file);
        try (var demuxer = new DtsDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.DTS, track.codec());
            assertEquals(48000, track.sampleRate());
            assertEquals(ChannelLayout.SURROUND_5_1.channels(), track.layout().orElseThrow().channels());
            var packets = packets(demuxer);
            assertArrayEquals(bytes, concatenate(packets), "the packets hold the whole stream");
            for (var i = 0; i < packets.size(); i++) {
                assertEquals(512L * i, packets.get(i).pts());
                assertEquals(0x7FFE8001, readInt(packets.get(i).data()));
            }
            assertEquals(512L * packets.size(), track.duration());
        }
    }

    @Test
    @DisplayName("the STRMDATA chunk of a DTS-HD file bounds the frames, whatever chunks follow it")
    void chunkBounds() throws IOException {
        var raw = Files.readAllBytes(encoded("chunked.dts", "dts", List.of()));
        var out = new java.io.ByteArrayOutputStream();
        writeChunk(out, "DTSHDHDR", new byte[16]);
        writeChunk(out, "STRMDATA", raw);
        writeChunk(out, "BUILDVER", "trailing".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        var file = Scratch.directory("dts-demuxer").resolve("chunked.dtshd");
        Files.write(file, out.toByteArray());
        try (var demuxer = new DtsDemuxer(MediaSource.of(file))) {
            assertArrayEquals(raw, concatenate(packets(demuxer)));
        }
    }

    private static void writeChunk(java.io.ByteArrayOutputStream out, String id, byte[] payload) {
        out.writeBytes(id.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        for (var shift = 56; shift >= 0; shift -= 8) {
            out.write((int) ((long) payload.length >>> shift));
        }
        out.writeBytes(payload);
    }

    @Test
    @DisplayName("a core frame carrying X96 is declared at twice the core rate")
    void x96Rate() {
        var file = Conformance.fetchListed("dts-x96_51_24_96_1509.dtshd");
        try (var demuxer = new DtsDemuxer(MediaSource.of(file))) {
            assertEquals(96000, ((AudioTrack) demuxer.tracks().getFirst()).sampleRate());
        }
    }

    @Test
    @DisplayName("Containers recognizes raw DTS and DTS-HD files")
    void detection() {
        var file = encoded("detect.dts", "dts", List.of());
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            assertEquals(ContainerFormat.DTS, demuxer.format());
        }
        var hd = Conformance.fetchListed("dts-xll_51_24_48_none.dtshd");
        try (var demuxer = Containers.openDemuxer(MediaSource.of(hd))) {
            assertEquals(ContainerFormat.DTS, demuxer.format());
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"core_51_24_48_768_0", "xll_51_24_48_768", "xll_51_24_48_none", "x96_51_24_96_1509",
            "xxch_71_24_48_2046"})
    @DisplayName("DTS-HD files split into access units as ffprobe counts them")
    void dtshd(String name) throws IOException {
        Oracle.assume(Oracle.Tool.FFPROBE);
        var file = Conformance.fetchListed("dts-" + name + ".dtshd");
        var probe = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-select_streams", "a:0", "-show_entries", "packet=size", "-of", "csv=p=0", file.toString())));
        var sizes = probe.lines().filter(line -> !line.isBlank()).mapToInt(Integer::parseInt).toArray();
        try (var demuxer = new DtsDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.DTS, track.codec());
            var packets = packets(demuxer);
            assertArrayEquals(sizes, packets.stream().mapToInt(packet -> packet.data().length).toArray());
        }
    }

    @Test
    @DisplayName("a stream without core is timed by its extension substream frame duration")
    void withoutCore() {
        var file = Conformance.fetchListed("dts-xll_51_24_48_none.dtshd");
        try (var demuxer = new DtsDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(48000, track.sampleRate());
            assertEquals(6, track.layout().orElseThrow().channels());
            var packets = packets(demuxer);
            assertTrue(packets.size() > 1);
            assertEquals(0x64582025, readInt(packets.getFirst().data()));
            assertEquals(packets.get(1).pts() - packets.getFirst().pts(), packets.getFirst().duration());
        }
    }

    @Test
    @DisplayName("seeking lands on the access unit holding the target")
    void seek() {
        var file = encoded("seek.dts", "dts", List.of());
        try (var demuxer = new DtsDemuxer(MediaSource.of(file))) {
            assertTrue(demuxer.isSeekable());
            demuxer.seek(200_000);
            var packet = demuxer.read();
            assertEquals(9216, packet.pts());
        }
    }

    @Test
    @DisplayName("bytes that start no DTS frame are rejected")
    void rejectsGarbage() {
        var garbage = new byte[4096];
        Arrays.fill(garbage, (byte) 0x55);
        assertThrows(MediaException.Malformed.class, () -> new DtsDemuxer(MediaSource.of(garbage)));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"m2ts", "mp4", "mkv"})
    @DisplayName("MPEG-TS (Blu-ray), MP4 and Matroska carry the frames of a raw stream unchanged")
    void containers(String extension) throws IOException {
        var raw = encoded("source-" + extension + ".dts", "dts", List.of());
        var file = Scratch.directory("dts-demuxer").resolve("wrapped." + extension);
        var arguments = new ArrayList<>(List.of("-v", "error", "-y", "-i", raw.toString(), "-c", "copy"));
        if (extension.equals("m2ts")) {
            arguments.addAll(List.of("-f", "mpegts", "-mpegts_m2ts_mode", "1"));
        }
        if (extension.equals("mp4")) {
            arguments.addAll(List.of("-strict", "-2"));
        }
        arguments.add(file.toString());
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), arguments);
        var expected = Files.readAllBytes(raw);
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().stream().filter(t -> t instanceof AudioTrack).findFirst()
                    .orElseThrow();
            assertEquals(Codec.DTS, track.codec());
            assertEquals(48000, track.sampleRate());
            var packets = new ArrayList<Packet>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                if (packet.trackId() == track.id()) {
                    packets.add(packet);
                }
            }
            assertArrayEquals(expected, concatenate(packets));
        }
    }

    @ParameterizedTest(name = "{0} in {1}")
    @CsvSource({"xll_51_24_48_768, m2ts", "xll_51_24_48_768, mp4", "xxch_71_24_48_2046, m2ts",
            "xxch_71_24_48_2046, mp4", "xll_51_24_48_none, mkv"})
    @DisplayName("MPEG-TS, MP4 and Matroska carry DTS-HD access units unchanged")
    void hdContainers(String name, String extension) throws IOException {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var raw = Conformance.fetchListed("dts-" + name + ".dtshd");
        var file = Scratch.directory("dts-demuxer").resolve(name + "." + extension);
        var arguments = new ArrayList<>(List.of("-v", "error", "-y", "-i", raw.toString(), "-c", "copy"));
        if (extension.equals("m2ts")) {
            arguments.addAll(List.of("-f", "mpegts", "-mpegts_m2ts_mode", "1"));
        }
        if (extension.equals("mp4")) {
            arguments.addAll(List.of("-strict", "-2"));
        }
        arguments.add(file.toString());
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), arguments);
        List<Packet> expected;
        try (var demuxer = new DtsDemuxer(MediaSource.of(raw))) {
            expected = packets(demuxer);
        }
        try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
            var track = (AudioTrack) demuxer.tracks().stream().filter(t -> t instanceof AudioTrack).findFirst()
                    .orElseThrow();
            assertEquals(Codec.DTS, track.codec());
            var packets = new ArrayList<Packet>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                if (packet.trackId() == track.id()) {
                    packets.add(packet);
                }
            }
            assertArrayEquals(concatenate(expected), concatenate(packets));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"little16", "big14", "little14"})
    @DisplayName("a raw stream in another form of DTS audio CDs demuxes to the frames of the 16-bit big-endian form")
    void repacked(String form) throws IOException {
        var raw = encoded("source-repacked.dts", "dts", List.of());
        List<Packet> expected;
        try (var demuxer = new DtsDemuxer(MediaSource.of(raw))) {
            expected = packets(demuxer);
        }
        var stored = new java.io.ByteArrayOutputStream();
        for (var packet : expected) {
            stored.writeBytes(switch (form) {
                case "little16" -> swap(packet.data());
                case "big14" -> pack14(packet.data(), false);
                default -> pack14(packet.data(), true);
            });
        }
        var bytes = stored.toByteArray();
        assertTrue(DtsDemuxer.matches(Arrays.copyOf(bytes, 4096)));
        assertEquals(ContainerFormat.DTS, Containers.detect(MediaSource.of(bytes)).orElseThrow());
        try (var demuxer = Containers.openDemuxer(MediaSource.of(bytes))) {
            assertEquals(ContainerFormat.DTS, demuxer.format());
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.DTS, track.codec());
            assertEquals(48000, track.sampleRate());
            var packets = packets(demuxer);
            assertEquals(expected.size(), packets.size());
            for (var i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i).data(), packets.get(i).data(), "packet " + i);
                assertEquals(expected.get(i).pts(), packets.get(i).pts(), "timestamp of packet " + i);
            }
        }
    }

    private static byte[] swap(byte[] plain) {
        var out = plain.clone();
        for (var i = 0; i + 1 < out.length; i += 2) {
            out[i] = plain[i + 1];
            out[i + 1] = plain[i];
        }
        return out;
    }

    /**
     * Packs a frame into 14 bits per 16-bit word, the two top bits copying bit 13.
     */
    private static byte[] pack14(byte[] plain, boolean little) {
        var words = (plain.length * 8 + 13) / 14;
        var out = new byte[words * 2];
        long accumulator = 0;
        var bits = 0;
        var input = 0;
        for (var w = 0; w < words; w++) {
            while (bits < 14) {
                accumulator = accumulator << 8 | (input < plain.length ? plain[input] & 0xFF : 0);
                input++;
                bits += 8;
            }
            bits -= 14;
            var value = (int) (accumulator >>> bits) & 0x3FFF;
            if ((value & 0x2000) != 0) {
                value |= 0xC000;
            }
            out[2 * w + (little ? 1 : 0)] = (byte) (value >>> 8);
            out[2 * w + (little ? 0 : 1)] = (byte) value;
        }
        return out;
    }

    @ParameterizedTest(name = "stream type {0}, descriptors [{1}]")
    @CsvSource({"82, ''", "06, ''", "06, 7B00", "06, 7F010E"})
    @DisplayName("MPEG-TS without the HDMV registration carries DTS as ffmpeg and DVB declare it")
    void transportStreamDeclarations(String type, String descriptors) throws IOException {
        var raw = encoded("source-ts.dts", "dts", List.of());
        var plain = Scratch.directory("dts-demuxer").resolve("plain.ts");
        Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-y", "-i", raw.toString(), "-c",
                "copy", "-f", "mpegts", plain.toString()));
        var stream = retype(Files.readAllBytes(plain), HexFormat.fromHexDigits(type),
                HexFormat.of().parseHex(descriptors));
        try (var demuxer = Containers.openDemuxer(MediaSource.of(stream))) {
            var track = (AudioTrack) demuxer.tracks().stream().filter(t -> t instanceof AudioTrack).findFirst()
                    .orElseThrow();
            assertEquals(Codec.DTS, track.codec());
            assertEquals(48000, track.sampleRate());
            var packets = new ArrayList<Packet>();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                if (packet.trackId() == track.id()) {
                    packets.add(packet);
                }
            }
            assertArrayEquals(Files.readAllBytes(raw), concatenate(packets));
        }
    }

    /**
     * Rewrites the program map of an ffmpeg transport stream (PID {@code 0x1000}, one elementary stream) to declare
     * the stream with another stream type and descriptor loop.
     */
    private static byte[] retype(byte[] ts, int streamType, byte[] descriptors) {
        var out = ts.clone();
        var rewritten = 0;
        for (var p = 0; p + 188 <= out.length; p += 188) {
            var pid = (out[p + 1] & 0x1F) << 8 | out[p + 2] & 0xFF;
            if (pid != 0x1000 || (out[p + 1] & 0x40) == 0 || (out[p + 3] & 0x30) != 0x10) {
                continue;
            }
            var s = p + 5 + (out[p + 4] & 0xFF);
            var programInfo = (out[s + 10] & 0x0F) << 8 | out[s + 11] & 0xFF;
            var es = s + 12 + programInfo;
            var section = new java.io.ByteArrayOutputStream();
            section.write(out, s, es - s);
            section.write(streamType);
            section.write(out[es + 1]);
            section.write(out[es + 2]);
            section.write(0xF0 | descriptors.length >> 8);
            section.write(descriptors.length & 0xFF);
            section.writeBytes(descriptors);
            var body = section.toByteArray();
            var length = body.length - 3 + 4;
            body[1] = (byte) (body[1] & 0xF0 | length >> 8);
            body[2] = (byte) length;
            var crc = com.github.auties00.smalt.internal.header.Crc32Mpeg.update(
                    com.github.auties00.smalt.internal.header.Crc32Mpeg.MPEG_INIT, body, 0, body.length);
            assertTrue(s + body.length + 4 <= p + 188, "the program map fits its packet");
            Arrays.fill(out, s, p + 188, (byte) 0xFF);
            System.arraycopy(body, 0, out, s, body.length);
            out[s + body.length] = (byte) (crc >>> 24);
            out[s + body.length + 1] = (byte) (crc >>> 16);
            out[s + body.length + 2] = (byte) (crc >>> 8);
            out[s + body.length + 3] = (byte) crc;
            rewritten++;
        }
        assertTrue(rewritten > 0, "the stream has a program map");
        return out;
    }

    private static int readInt(byte[] data) {
        return (data[0] & 0xFF) << 24 | (data[1] & 0xFF) << 16 | (data[2] & 0xFF) << 8 | data[3] & 0xFF;
    }
}
