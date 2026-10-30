package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.ac3.Ac3Demuxer;
import com.github.auties00.smalt.container.subtitle.VobSubDemuxer;
import com.github.auties00.smalt.internal.nal.NalUnits;
import com.github.auties00.smalt.internal.subtitle.bitmap.DvdSubtitleStream;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Packets;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers reading MPEG program streams: ffmpeg's VOB, DVD, MPEG-1 system and H.264 streams read against ffprobe
 * (codec, packet count, payloads, key frames and timestamps) and decoded linear PCM against ffmpeg's decoder, and
 * hand-written streams for the program stream map, private stream 1 without substreams, the MPEG-1 packet header,
 * scrambled and damaged packets, extended stream ids, streams that start late, access units split across or packed
 * into PES packets, field pictures, DTS-HD extension substreams, linear PCM sample groups cut by PES boundaries and
 * subpicture units spanning packs.
 */
@DisplayName("MPEG-PS demuxing")
class PsDemuxerTest {
    static final Path SAMPLES = Path.of("src", "test", "resources", "samples", "ps");

    /** Everything one demuxer read, with its origin. */
    record Read(List<Track> tracks, List<Packet> packets, long origin, long durationMicros) {
        List<Packet> of(int trackId) {
            return packets.stream().filter(packet -> packet.trackId() == trackId).toList();
        }

        Track track(int trackId) {
            return tracks.stream().filter(track -> track.id() == trackId).findFirst()
                    .orElseThrow(() -> new AssertionError("no track " + Integer.toHexString(trackId) + " in " + tracks));
        }
    }

    static Read read(byte[] data) {
        try (var source = MediaSource.of(data); var demuxer = new PsDemuxer(source)) {
            return new Read(demuxer.tracks(), Packets.readAll(demuxer), demuxer.originTicks(), demuxer.durationMicros());
        }
    }

    static Read read(Path file) {
        return read(Fixtures.read(file));
    }

    static Codec codecOf(String ffmpegName) {
        return switch (ffmpegName) {
            case "mpeg2video" -> Codec.MPEG2_VIDEO;
            case "mpeg1video" -> Codec.MPEG1_VIDEO;
            case "mpeg4" -> Codec.MPEG4_PART2;
            case "h264" -> Codec.H264;
            case "hevc" -> Codec.HEVC;
            case "mp2" -> Codec.MP2;
            case "mp3" -> Codec.MP3;
            case "aac" -> Codec.AAC;
            case "ac3" -> Codec.AC3;
            case "eac3" -> Codec.EAC3;
            case "dts" -> Codec.DTS;
            case "dvd_subtitle" -> Codec.DVD_SUBTITLE;
            default -> throw new AssertionError("unexpected ffprobe codec " + ffmpegName);
        };
    }

    /** Returns the track identifier of an ffprobe stream id: a stream id above 0x100, a substream id below. */
    static int trackIdOf(String ffprobeId) {
        var id = Integer.decode(ffprobeId);
        return id >= 0x100 ? id - 0x100 : PsScanner.SUBSTREAM + id;
    }

    /** Returns the NAL units of an Annex B access unit or a length-prefixed packet, without access unit delimiters. */
    static List<String> nalUnits(byte[] data, boolean annexB) {
        var units = new ArrayList<String>();
        NalUnits.Sink collect = (bytes, offset, length) -> {
            if (length > 0 && (bytes[offset] & 0x1F) != 9) {
                units.add(java.util.HexFormat.of().formatHex(bytes, offset, offset + length));
            }
        };
        if (annexB) {
            NalUnits.forEachAnnexB(data, collect);
        } else {
            NalUnits.forEachLengthPrefixed(data, 4, collect);
        }
        return units;
    }

    /**
     * Compares a file's demuxed packets with ffprobe's: every stream but DVD navigation packets as a track of the
     * same codec, the same number of packets with the same payloads, the key flags of video, and every timestamp
     * ffprobe reports, within a 90 kHz tick for video and subpictures and a sample for audio.
     */
    static void assertMatchesProbe(Path file) {
        var probe = ContainerProbe.probe(file);
        var read = read(file);
        var streams = probe.streams().stream().filter(stream -> !stream.codecName().equals("dvd_nav_packet")).toList();
        assertEquals(streams.size(), read.tracks().size(), "tracks of " + file + ": " + read.tracks());
        for (var stream : streams) {
            var track = read.track(trackIdOf(stream.get("id")));
            var what = file.getFileName() + " stream " + stream.get("id");
            if (stream.codecName().equals("pcm_dvd")) {
                // ffmpeg's packets keep the substream header; the samples are compared decoded
                assertTrue(track.codec() == Codec.PCM_S16BE || track.codec() == Codec.PCM_S24BE, what + " codec");
                var raw = track.codec() == Codec.PCM_S16BE ? "s16be" : "s24be";
                assertArrayEquals(ffmpegDecode(file, raw, "0:" + stream.index()), concat(read.of(track.id())),
                        what + " samples");
                continue;
            }
            var codec = codecOf(stream.codecName());
            assertEquals(codec, track.codec(), what + " codec");
            var expected = probe.packetsOf(stream.index());
            var actual = read.of(track.id());
            List<byte[]> data = codec == Codec.H264 ? ContainerProbe.packetData(file) : null;
            assertEquals(expected.size(), actual.size(), what + " packet count");
            var index = 0;
            var all = probe.packets();
            for (var packetIndex = 0; packetIndex < all.size(); packetIndex++) {
                var want = all.get(packetIndex);
                if (want.stream() != stream.index()) {
                    continue;
                }
                var got = actual.get(index);
                if (codec == Codec.H264) {
                    assertEquals(nalUnits(data.get(packetIndex), true), nalUnits(got.data(), false),
                            what + " NAL units of packet " + index);
                } else {
                    assertEquals(want.hash(), ContainerProbe.crc32(got.data()), what + " payload of packet " + index);
                }
                if (track.kind() == TrackKind.VIDEO) {
                    assertEquals(want.key(), got.keyframe(), what + " key flag of packet " + index);
                } else {
                    assertTrue(got.keyframe(), what + " packet " + index + " is a key frame");
                }
                if (want.pts() != Packet.NO_TIMESTAMP) {
                    var expectedMicros = (want.pts() - read.origin()) * 100 / 9;
                    var gotMicros = track.timeBase().toMicros(got.pts());
                    var tolerance = track instanceof AudioTrack audio ? 1_000_000L / audio.sampleRate() + 12 : 12;
                    assertTrue(Math.abs(expectedMicros - gotMicros) <= tolerance, what + " pts of packet " + index
                            + ": " + gotMicros + " us, ffprobe " + expectedMicros + " us");
                }
                index++;
            }
        }
    }

    static byte[] ffmpegDecode(Path file, String format, String stream) {
        return Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), List.of("-v", "error", "-i", file.toString(),
                "-map", stream, "-f", format, "-"));
    }

    static byte[] concat(List<Packet> packets) {
        var out = new ByteArrayOutputStream();
        packets.forEach(packet -> out.writeBytes(packet.data()));
        return out.toByteArray();
    }

    @Nested
    @DisplayName("ffmpeg's streams")
    class Samples {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"mpeg2-mp2.vob", "mpeg1.mpg", "dvd.vob", "dts.vob", "h264.mpg"})
        @DisplayName("read as ffprobe reads them")
        void probe(String name) {
            assertMatchesProbe(SAMPLES.resolve(name));
        }

        @Test
        @DisplayName("describe the DVD-Video object: MPEG-2 video, AC-3 and the two subpicture streams")
        void dvd() {
            var read = read(SAMPLES.resolve("dvd.vob"));
            assertEquals(java.util.Set.of(0xE0, 0x180, 0x120, 0x121),
                    read.tracks().stream().map(Track::id).collect(java.util.stream.Collectors.toSet()));
            var video = (VideoTrack) read.track(0xE0);
            assertEquals(160, video.width());
            assertEquals(120, video.height());
            var audio = (AudioTrack) read.track(0x180);
            assertEquals(48_000, audio.sampleRate());
            assertEquals(1, audio.layout().orElseThrow().channels());
            for (var id : List.of(0x120, 0x121)) {
                var subtitles = read.of(id);
                assertFalse(subtitles.isEmpty(), "subpicture stream " + id);
                for (var unit : subtitles) {
                    assertEquals(DvdSubtitleStream.stopTicks(unit.data()), unit.duration(), "unit duration");
                    assertEquals((unit.data()[0] & 0xFF) << 8 | (unit.data()[1] & 0xFF), unit.size(), "unit size");
                }
            }
            assertTrue(read.durationMicros() >= 2_900_000 && read.durationMicros() <= 3_200_000,
                    "duration " + read.durationMicros());
        }

        @Test
        @DisplayName("carry the subpicture units of the VobSub file they were made from")
        void subpicturesMatchVobSub() {
            var subs = Path.of("src", "test", "resources", "samples", "subtitle", "vobsub.sub");
            List<Packet> units;
            try (var source = MediaSource.of(subs); var demuxer = new VobSubDemuxer(source)) {
                units = Packets.readAll(demuxer);
            }
            var read = read(SAMPLES.resolve("dvd.vob"));
            for (var stream = 0; stream < 2; stream++) {
                var id = stream + 1;
                var expected = units.stream().filter(packet -> packet.trackId() == id).map(Packet::data).toList();
                var actual = read.of(0x120 + stream).stream().map(Packet::data).toList();
                assertEquals(expected.size(), actual.size(), "units of stream " + stream);
                for (var index = 0; index < expected.size(); index++) {
                    assertArrayEquals(expected.get(index), actual.get(index), "unit " + index + " of stream " + stream);
                }
            }
        }

        @Test
        @DisplayName("are recognized as program streams, a VobSub .sub file still as VobSub")
        void detection() throws IOException {
            try (var files = Files.list(SAMPLES)) {
                for (var file : files.toList()) {
                    try (var source = MediaSource.of(file)) {
                        assertEquals(ContainerFormat.MPEG_PS, Containers.detect(source).orElseThrow(), file.toString());
                    }
                }
            }
            try (var source = MediaSource.of(Path.of("src", "test", "resources", "samples", "subtitle", "vobsub.sub"))) {
                assertEquals(ContainerFormat.VOBSUB, Containers.detect(source).orElseThrow());
            }
            assertFalse(PsDemuxer.matches(new byte[]{0, 0, 1, (byte) 0xB3, 0, 0, 0, 0, 0, 0, 0, 0}), "a sequence header");
            assertFalse(PsDemuxer.matches(new byte[]{0, 0, 1, (byte) 0xBA, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}),
                    "a pack start code without its marker bits");
            assertTrue(PsDemuxer.matches(Arrays.copyOf(new PsWriter().pack(0).toByteArray(), 14)));
        }

        @Test
        @DisplayName("seek to the key frames at or before the target")
        void seek() {
            try (var source = MediaSource.of(SAMPLES.resolve("dvd.vob")); var demuxer = new PsDemuxer(source)) {
                assertTrue(demuxer.isSeekable());
                demuxer.seek(1_500_000);
                var video = demuxer.tracks().getFirst();
                Packet first = null;
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    if (packet.trackId() == video.id()) {
                        first = packet;
                        break;
                    }
                }
                assertTrue(first != null && first.keyframe(), "a key frame first");
                var micros = video.timeBase().toMicros(first.pts());
                assertTrue(micros <= 1_500_000 && micros > 1_500_000 - 600_000, "key frame at " + micros + " us");
            }
        }
    }

    @Nested
    @DisplayName("linear PCM")
    class Lpcm {
        @Test
        @DisplayName("decodes the 16-bit samples of ffmpeg's VOB as ffmpeg decodes them")
        void sample() {
            var file = SAMPLES.resolve("lpcm.vob");
            var read = read(file);
            var track = (AudioTrack) read.track(0x1A0);
            assertEquals(Codec.PCM_S16BE, track.codec());
            assertEquals(16, track.bitsPerSample());
            assertEquals(2, track.layout().orElseThrow().channels());
            assertArrayEquals(ffmpegDecode(file, "s16be", "0:a:0"), concat(read.of(0x1A0)));
        }

        @ParameterizedTest(name = "{0} channels, {1}")
        @CsvSource({"2, s16, s16be", "6, s16, s16be", "1, s32, s24be", "2, s32, s24be", "6, s32, s24be"})
        @DisplayName("decodes ffmpeg's linear PCM as ffmpeg decodes it")
        void ffmpeg(int channels, String sampleFormat, String raw) {
            var file = Fixtures.ffmpeg("vob", List.of("-f", "lavfi", "-i",
                    "sine=frequency=440:sample_rate=48000:duration=0.5"), List.of("-c:a", "pcm_dvd", "-ac",
                    Integer.toString(channels), "-sample_fmt", sampleFormat, "-f", "dvd"));
            var read = read(file);
            var track = (AudioTrack) read.track(0x1A0);
            assertEquals(channels, track.layout().orElseThrow().channels());
            assertEquals(raw.equals("s16be") ? Codec.PCM_S16BE : Codec.PCM_S24BE, track.codec());
            assertArrayEquals(ffmpegDecode(file, raw, "0:a:0"), concat(read.of(0x1A0)));
        }

        @ParameterizedTest(name = "{0} bits, {1} channels")
        @CsvSource({"16, 2", "20, 2", "24, 2", "20, 3", "24, 5"})
        @DisplayName("joins the sample groups that PES packets cut, stamping the sample the pointer locates")
        void carriedGroups(int bits, int channels) {
            var groupFrames = DvdLpcmParser.groupFrames(bits, channels);
            var group = DvdLpcmParser.groupBytes(bits, channels);
            var groups = 400;
            var random = new Random(bits * 10L + channels);
            var stored = new byte[groups * group];
            random.nextBytes(stored);
            var writer = new PsWriter();
            var code = (bits == 16 ? 0 : bits == 20 ? 1 : 2) << 6 | (channels - 1);
            var position = 0;
            var frame = 0L;
            var cuts = new int[]{1001, 999, 1500, 7, 2000, stored.length};
            for (var cut : cuts) {
                var end = Math.min(stored.length, position + cut);
                if (position >= end) {
                    break;
                }
                // the pointer counts from its last byte: 4 is the first byte of the samples
                var lead = (group - position % group) % group;
                var pointer = lead < end - position ? 4 + lead : 0;
                var firstFrame = (long) (position + lead) / group * groupFrames;
                var pts = pointer > 0 ? 90_000 + firstFrame * 90_000 / 48_000 : -1;
                var payload = PsWriter.prefixed(Arrays.copyOfRange(stored, position, end), 0xA0, 1, pointer >> 8,
                        pointer & 0xFF, 0, code, 0x80);
                writer.pack(80_000 + frame).pes(0xBD, pts, -1, payload);
                frame++;
                position = end;
            }
            var read = read(writer.end().toByteArray());
            var track = (AudioTrack) read.track(0x1A0);
            assertEquals(bits, track.bitsPerSample());
            var output = new byte[groups * groupFrames * channels * (bits == 16 ? 2 : 3)];
            DvdLpcmParser.convert(stored, 0, groups, bits, channels, output, 0);
            var packets = read.of(0x1A0);
            assertArrayEquals(output, concat(packets), "samples");
            var next = packets.getFirst().pts();
            for (var packet : packets) {
                assertEquals(next, packet.pts(), "packets follow each other");
                next = packet.pts() + packet.duration();
            }
            assertEquals(0, packets.getFirst().pts(), "the first sample is the origin");
        }

        @Test
        @DisplayName("places 20-bit low nibbles and 24-bit low bytes after the high words of the group")
        void groupLayout() {
            // two frames of two channels: high words L0 R0 L1 R1, then the low bits in the same order
            var group = new byte[]{0x11, 0x12, 0x21, 0x22, 0x31, 0x32, 0x41, 0x42, (byte) 0xAB, (byte) 0xCD};
            var output = new byte[12];
            DvdLpcmParser.convert(group, 0, 1, 20, 2, output, 0);
            assertArrayEquals(new byte[]{0x11, 0x12, (byte) 0xA0, 0x21, 0x22, (byte) 0xB0, 0x31, 0x32, (byte) 0xC0,
                    0x41, 0x42, (byte) 0xD0}, output);
            var wide = new byte[]{0x11, 0x12, 0x21, 0x22, 0x31, 0x32, 0x41, 0x42, 1, 2, 3, 4};
            DvdLpcmParser.convert(wide, 0, 1, 24, 2, output, 0);
            assertArrayEquals(new byte[]{0x11, 0x12, 1, 0x21, 0x22, 2, 0x31, 0x32, 3, 0x41, 0x42, 4}, output);
        }
    }

    @Nested
    @DisplayName("hand-written streams")
    class Synthetic {
        static byte[] h264() {
            return Fixtures.read(Fixtures.ffmpeg("h264", List.of("-f", "lavfi", "-i",
                    "testsrc2=size=96x64:rate=25:duration=0.6"), List.of("-c:v", "libx264", "-preset", "ultrafast",
                    "-g", "5", "-bf", "0", "-x264-params", "threads=1:aud=1")));
        }

        /** Writes an elementary stream as PES packets of a stream id with a timestamp on every packet. */
        static PsWriter chunked(PsWriter writer, int streamId, byte[] stream, int size, long step) {
            for (int offset = 0, index = 0; offset < stream.length; offset += size, index++) {
                writer.pack(index * step).pes(streamId, 90_000 + index * step, -1,
                        Arrays.copyOfRange(stream, offset, Math.min(stream.length, offset + size)));
            }
            return writer;
        }

        @ParameterizedTest(name = "CRC from the length field: {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("take the coding and language of a stream from the program stream map")
        void map(boolean crcFromLength) {
            Oracle.assumeEncoder("libx264");
            var writer = new PsWriter().pack(0).map(List.of(new PsWriter.MapEntry(0x1B, 0xE0, "fra")), crcFromLength,
                    false);
            var read = read(chunked(writer, 0xE0, h264(), 1800, 3600).end().toByteArray());
            var track = read.track(0xE0);
            assertEquals(Codec.H264, track.codec());
            assertEquals("fra", track.language().orElseThrow());
            assertFalse(read.of(0xE0).isEmpty());
        }

        @Test
        @DisplayName("ignore a program stream map whose CRC fails, recognizing the stream from its content")
        void corruptMap() {
            Oracle.assumeEncoder("libx264");
            var writer = new PsWriter().pack(0).map(List.of(new PsWriter.MapEntry(0x1B, 0xE0, "fra")), false, true);
            var read = read(chunked(writer, 0xE0, h264(), 1800, 3600).end().toByteArray());
            var track = read.track(0xE0);
            assertEquals(Codec.H264, track.codec(), "sniffed");
            assertTrue(track.language().isEmpty(), "the map was not read");
        }

        @Test
        @DisplayName("read AC-3 in private stream 1 without substream headers")
        void rawPrivateStream() {
            var ac3 = Fixtures.read(Fixtures.ffmpeg("ac3", List.of("-f", "lavfi", "-i",
                    "sine=frequency=440:sample_rate=48000:duration=0.4"), List.of("-c:a", "ac3", "-b:a", "96k")));
            List<Packet> frames;
            try (var source = MediaSource.of(ac3); var demuxer = new Ac3Demuxer(source)) {
                frames = Packets.readAll(demuxer);
            }
            var read = read(chunked(new PsWriter(), 0xBD, ac3, 1000, 1800).end().toByteArray());
            assertEquals(Codec.AC3, read.track(0xBD).codec());
            var packets = read.of(0xBD);
            assertEquals(frames.size(), packets.size());
            for (var index = 0; index < frames.size(); index++) {
                assertArrayEquals(frames.get(index).data(), packets.get(index).data(), "frame " + index);
            }
        }

        @Test
        @DisplayName("read MPEG-1 packet headers with stuffing, buffer sizes and both timestamp forms")
        void mpeg1Headers() {
            var mp2 = Fixtures.read(Fixtures.ffmpeg("mp2", List.of("-f", "lavfi", "-i",
                    "sine=frequency=440:sample_rate=48000:duration=0.3"), List.of("-c:a", "mp2", "-b:a", "128k")));
            var writer = new PsWriter(true);
            var size = 384;
            for (int offset = 0, index = 0; offset < mp2.length; offset += size, index++) {
                var payload = Arrays.copyOfRange(mp2, offset, Math.min(mp2.length, offset + size));
                var header = new ByteArrayOutputStream();
                header.writeBytes(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});
                if (index == 0) {
                    header.writeBytes(new byte[]{0x40, 0x20});
                }
                var pts = 90_000L + index * 2160L;
                if (index % 3 == 1) {
                    header.writeBytes(TsWriter.timestamp(3, pts));
                    header.writeBytes(TsWriter.timestamp(1, pts));
                } else if (index % 3 == 0) {
                    header.writeBytes(TsWriter.timestamp(2, pts));
                } else {
                    header.write(0x0F);
                }
                writer.pack(index * 2160L).raw(0xC0, header.toByteArray(), payload);
            }
            var read = read(writer.end().toByteArray());
            assertEquals(Codec.MP2, read.track(0xC0).codec());
            assertArrayEquals(mp2, concat(read.of(0xC0)));
        }

        @ParameterizedTest(name = "decoy {0}")
        @ValueSource(ints = {0x0040, 0x1F00})
        @DisplayName("recognize MPEG audio after a LOAS-like byte pair in the tail of a cut frame")
        void sniffAudio(int decoyLength) {
            var mp2 = Fixtures.read(Fixtures.ffmpeg("mp2", List.of("-f", "lavfi", "-i",
                    "sine=frequency=440:sample_rate=48000:duration=0.3"), List.of("-c:a", "mp2", "-b:a", "128k")));
            // the tail of a frame cut by the start of the file: a LOAS sync word whose length points inside the
            // payload (at a byte that is no sync word) or past it
            var tail = new byte[100];
            tail[1] = 0x56;
            tail[2] = (byte) (0xE0 | decoyLength >> 8);
            tail[3] = (byte) decoyLength;
            var stream = new byte[tail.length + mp2.length];
            System.arraycopy(tail, 0, stream, 0, tail.length);
            System.arraycopy(mp2, 0, stream, tail.length, mp2.length);
            var read = read(chunked(new PsWriter(), 0xC0, stream, 1000, 1800).end().toByteArray());
            assertEquals(Codec.MP2, read.track(0xC0).codec());
            assertArrayEquals(mp2, concat(read.of(0xC0)));
        }

        @Test
        @DisplayName("continue the timeline across the 33-bit wrap, measure the duration and seek past the wrap")
        void wrap() {
            var mp2 = Fixtures.read(Fixtures.ffmpeg("mp2", List.of("-f", "lavfi", "-i",
                    "sine=frequency=440:sample_rate=48000:duration=2"), List.of("-c:a", "mp2", "-b:a", "128k")));
            // 128 kbit/s at 48 kHz makes every frame 384 bytes and 1152 samples (2160 ticks): one frame per PES
            var start = (1L << 33) - 90_000;
            var writer = new PsWriter();
            var frames = 0;
            for (var offset = 0; offset + 384 <= mp2.length; offset += 384, frames++) {
                var pts = start + frames * 2160L;
                writer.pack(pts - 9000).pes(0xC0, pts, -1, Arrays.copyOfRange(mp2, offset, offset + 384));
            }
            var data = writer.end().toByteArray();
            var read = read(data);
            assertEquals(start, read.origin(), "the origin is the first time, before the wrap");
            var packets = read.of(0xC0);
            assertEquals(frames, packets.size());
            for (var index = 0; index < packets.size(); index++) {
                assertEquals(index * 1152L, packets.get(index).pts(), "pts of frame " + index);
            }
            assertEquals(frames * 24_000L, read.durationMicros(), "duration");
            try (var source = MediaSource.of(data); var demuxer = new PsDemuxer(source)) {
                demuxer.seek(1_500_000);
                var first = demuxer.read();
                var micros = demuxer.tracks().getFirst().timeBase().toMicros(first.pts());
                assertTrue(micros <= 1_500_000 && micros > 1_500_000 - 24_000, "frame at " + micros + " us");
            }
        }

        @Test
        @DisplayName("continue the timeline of program streams joined end to end that each restart their clock")
        void concatenated() {
            var mp2 = Fixtures.read(Fixtures.ffmpeg("mp2", List.of("-f", "lavfi", "-i",
                    "sine=frequency=440:sample_rate=48000:duration=1"), List.of("-c:a", "mp2", "-b:a", "128k")));
            // one frame of 384 bytes and 2160 ticks per PES packet; both parts start at the same clock, as two
            // recordings joined with cat
            var writer = new PsWriter();
            var frames = mp2.length / 384;
            var expected = new ByteArrayOutputStream();
            for (var part = 0; part < 2; part++) {
                for (var frame = 0; frame < frames; frame++) {
                    var pts = 90_000 + frame * 2160L;
                    var payload = Arrays.copyOfRange(mp2, frame * 384, frame * 384 + 384);
                    writer.pack(pts - 9000).pes(0xC0, pts, -1, payload);
                    expected.writeBytes(payload);
                }
                writer.end();
            }
            var read = read(writer.toByteArray());
            var packets = read.of(0xC0);
            assertEquals(2 * frames, packets.size(), "the frames of both parts");
            assertArrayEquals(expected.toByteArray(), concat(packets));
            for (var index = 1; index < packets.size(); index++) {
                assertTrue(packets.get(index).pts() > packets.get(index - 1).pts(),
                        "pts of frame " + index + " after the previous one");
            }
            assertEquals(1152L * (frames - 1), packets.get(frames - 1).pts(), "the first part keeps its times");
            assertTrue(packets.get(frames).pts() >= packets.get(frames - 1).pts() + 1152,
                    "the second part follows the first");
            for (var index = frames + 1; index < packets.size(); index++) {
                assertEquals(1152L, packets.get(index).pts() - packets.get(index - 1).pts(),
                        "the second part keeps its spacing at frame " + index);
            }
        }

        @Test
        @DisplayName("drop scrambled PES packets and flag the packet after them")
        void scrambled() {
            var ac3 = Fixtures.read(Fixtures.ffmpeg("ac3", List.of("-f", "lavfi", "-i",
                    "sine=frequency=440:sample_rate=48000:duration=0.4"), List.of("-c:a", "ac3", "-b:a", "96k")));
            var writer = new PsWriter();
            var frame = 384;
            for (var index = 0; index * frame < ac3.length; index++) {
                var payload = PsWriter.prefixed(Arrays.copyOfRange(ac3, index * frame, (index + 1) * frame), 0x80, 1,
                        0, 1);
                writer.pack(index * 2880L);
                if (index == 3) {
                    writer.mpeg2(0xBD, 0x91, 0x80, TsWriter.timestamp(2, 90_000 + index * 2880L), payload);
                } else {
                    writer.pes(0xBD, 90_000 + index * 2880L, -1, payload);
                }
            }
            var packets = read(writer.end().toByteArray()).of(0x180);
            assertEquals(ac3.length / frame - 1, packets.size(), "one frame dropped");
            var corrupt = packets.stream().filter(packet -> packet.flags().contains(PacketFlag.CORRUPT)).count();
            assertEquals(1, corrupt, "the frame after the gap");
        }

        @Test
        @DisplayName("resynchronize on the next pack after garbage, flagging what follows it")
        void garbage() {
            var file = Fixtures.read(SAMPLES.resolve("mpeg2-mp2.vob"));
            var clean = read(file);
            var damaged = new ByteArrayOutputStream();
            var cut = 2048 * 6;
            damaged.write(file, 0, cut + 100);
            var noise = new byte[3000];
            new Random(5).nextBytes(noise);
            damaged.writeBytes(noise);
            damaged.write(file, cut + 2048 * 2, file.length - cut - 2048 * 2);
            var read = read(damaged.toByteArray());
            assertEquals(clean.tracks().size(), read.tracks().size());
            assertTrue(read.packets().size() < clean.packets().size(), "the damaged packs lose packets");
            assertTrue(read.packets().stream().anyMatch(packet -> packet.flags().contains(PacketFlag.CORRUPT)),
                    "the packets after the garbage are flagged");
            var lastClean = clean.of(0xC0).getLast();
            var lastRead = read.of(0xC0).getLast();
            assertArrayEquals(lastClean.data(), lastRead.data(), "the stream continues after the garbage");
        }

        @ParameterizedTest(name = "cut at {0}")
        @ValueSource(ints = {20, 2048, 2049, 5000, 9000, 20_011, 33_333})
        @DisplayName("read a stream cut at any byte up to the cut")
        void truncated(int length) {
            var file = Fixtures.read(SAMPLES.resolve("dvd.vob"));
            var cut = Arrays.copyOf(file, length);
            try {
                var read = read(cut);
                for (var packet : read.packets()) {
                    assertTrue(packet.size() > 0);
                }
            } catch (MediaException.Malformed expected) {
                assertTrue(length < 32_768, "only a stream cut before any packet fails: " + expected);
            }
        }

        @Test
        @DisplayName("expose an extended stream id as a data track")
        void extended() {
            // PES_extension_flag, then PES_extension_flag_2 with a stream_id_extension of 0x55
            var fields = new byte[]{0x01, (byte) 0x81, 0x55};
            var writer = new PsWriter().pack(0);
            var header = PsWriter.prefixed(fields, 0x81, 0x01, fields.length);
            writer.raw(0xFD, header, new byte[]{1, 2, 3, 4});
            var read = read(writer.end().toByteArray());
            var track = read.track(PsScanner.EXTENDED + 0x55);
            assertInstanceOf(DataTrack.class, track);
            assertArrayEquals(new byte[]{1, 2, 3, 4}, read.of(track.id()).getFirst().data());
        }

        @Test
        @DisplayName("find a subpicture stream that starts after the probe window")
        void lateStream() {
            var spu = subpictureUnit(600);
            var writer = new PsWriter();
            var mp2 = Fixtures.read(Fixtures.ffmpeg("mp2", List.of("-f", "lavfi", "-i",
                    "sine=frequency=440:sample_rate=48000:duration=0.3"), List.of("-c:a", "mp2", "-b:a", "128k")));
            writer.pack(0).pes(0xC0, 90_000, -1, mp2);
            var units = 0;
            for (var pack = 0; pack < 6000; pack++) {
                writer.pack(1000 + pack);
                if (pack >= 4800 && pack % 16 == 0) {
                    writer.pes(0xBD, 100_000 + pack * 90L, -1, PsWriter.prefixed(spu, 0x20));
                    units++;
                } else {
                    writer.padding(2034);
                }
            }
            var data = writer.end().toByteArray();
            assertTrue(data.length > 10 << 20, "past the probe window");
            var read = read(data);
            var track = read.track(0x120);
            assertEquals(Codec.DVD_SUBTITLE, track.codec());
            assertEquals(units, read.of(0x120).size());
        }

        @Test
        @DisplayName("reassemble a subpicture unit that spans PES packets")
        void subpictureSpansPacks() {
            var spu = subpictureUnit(5000);
            var writer = new PsWriter();
            writer.pack(0).pes(0xBD, 90_000, -1, PsWriter.prefixed(Arrays.copyOfRange(spu, 0, 2000), 0x21));
            writer.pack(100).pes(0xBD, -1, -1, PsWriter.prefixed(Arrays.copyOfRange(spu, 2000, 4000), 0x21));
            writer.pack(200).pes(0xBD, -1, -1, PsWriter.prefixed(Arrays.copyOfRange(spu, 4000, 5000), 0x21));
            var read = read(writer.end().toByteArray());
            var packets = read.of(0x121);
            assertEquals(1, packets.size());
            assertArrayEquals(spu, packets.getFirst().data());
            assertEquals(2 * 1024L, packets.getFirst().duration(), "the stop command's delay");
        }

        /** Returns a subpicture unit of a size: a start command, then a stop command after 2 * 1024 ticks. */
        static byte[] subpictureUnit(int size) {
            var unit = new byte[size];
            var control = size - 12;
            unit[0] = (byte) (size >> 8);
            unit[1] = (byte) size;
            unit[2] = (byte) (control >> 8);
            unit[3] = (byte) control;
            // the first sequence starts the display at once, the second, its own successor, stops it 2 units later
            var second = control + 6;
            unit[control + 2] = (byte) (second >> 8);
            unit[control + 3] = (byte) second;
            unit[control + 4] = 0x01;
            unit[control + 5] = (byte) 0xFF;
            unit[second + 1] = 2;
            unit[second + 2] = (byte) (second >> 8);
            unit[second + 3] = (byte) second;
            unit[second + 4] = 0x02;
            unit[second + 5] = (byte) 0xFF;
            return unit;
        }

        @Test
        @DisplayName("join a DTS frame and the DTS-HD extension substream after it")
        void dtsExtension() {
            var cores = read(SAMPLES.resolve("dts.vob")).of(0x188);
            var extension = new byte[64];
            extension[0] = 0x64;
            extension[1] = 0x58;
            extension[2] = 0x20;
            extension[3] = 0x25;
            // UserDefinedBits 0, nExtSSIndex 0, bHeaderSizeType 0, nuExtSSHeaderSize 15, nuExtSSFsize 63
            var bits = (15L << 16 | 63L) << 13;
            for (var index = 0; index < 6; index++) {
                extension[4 + index] = (byte) (bits >>> (40 - 8 * index));
            }
            var writer = new PsWriter();
            var stream = new ByteArrayOutputStream();
            for (var core : cores) {
                stream.writeBytes(core.data());
                stream.writeBytes(extension);
            }
            var bytes = stream.toByteArray();
            for (int offset = 0, index = 0; offset < bytes.length; offset += 2000, index++) {
                writer.pack(index * 900L).pes(0xBD, index == 0 ? 90_000 : -1, -1,
                        PsWriter.prefixed(Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + 2000)),
                                0x89, index == 0 ? 1 : 0, 0, index == 0 ? 1 : 0));
            }
            var packets = read(writer.end().toByteArray()).of(0x189);
            assertEquals(cores.size(), packets.size());
            for (var index = 0; index < cores.size(); index++) {
                var core = cores.get(index).data();
                var packet = packets.get(index).data();
                assertEquals(core.length + extension.length, packet.length, "frame " + index);
                assertArrayEquals(core, Arrays.copyOf(packet, core.length));
            }
        }
    }

    @Nested
    @DisplayName("video access units")
    class AccessUnits {
        /** Returns the pictures of an ffmpeg MPEG-2 stream in a transport stream, with their timestamps. */
        static List<Packet> pictures(int bFrames) {
            var file = Fixtures.ffmpeg("ts", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=1.2"),
                    List.of("-c:v", "mpeg2video", "-g", "6", "-bf", Integer.toString(bFrames), "-q:v", "6"));
            return ContainerProbe.demux(file, TsDemuxer::new).order();
        }

        /**
         * Writes pictures as one elementary stream cut into PES packets of a size, each with the timestamps of the
         * first picture that starts in it, or none.
         */
        static byte[] program(List<Packet> pictures, int size, boolean decodingTimes) {
            var stream = new ByteArrayOutputStream();
            var starts = new ArrayList<Integer>();
            for (var picture : pictures) {
                // the timestamps of a PES packet belong to the first picture start code that begins in it
                starts.add(stream.size() + pictureStart(picture.data()));
                stream.writeBytes(picture.data());
            }
            var bytes = stream.toByteArray();
            var writer = new PsWriter();
            var next = 0;
            for (int offset = 0, index = 0; offset < bytes.length; offset += size, index++) {
                var end = Math.min(bytes.length, offset + size);
                Packet first = null;
                while (next < starts.size() && starts.get(next) < end) {
                    if (first == null && starts.get(next) >= offset) {
                        first = pictures.get(next);
                    }
                    next++;
                }
                var pts = first == null ? -1 : 90_000 + first.pts();
                var dts = first == null || !decodingTimes || first.dts() == first.pts() ? -1 : 90_000 + first.dts();
                writer.pack(index * 100L).pes(0xE0, pts, dts, Arrays.copyOfRange(bytes, offset, end));
            }
            return writer.end().toByteArray();
        }

        /** Returns the index of the first picture start code of an access unit. */
        static int pictureStart(byte[] unit) {
            for (var index = 0; index + 3 < unit.length; index++) {
                if (unit[index] == 0 && unit[index + 1] == 0 && unit[index + 2] == 1 && unit[index + 3] == 0) {
                    return index;
                }
            }
            throw new AssertionError("no picture start code");
        }

        @ParameterizedTest(name = "{0} B-frames, PES of {1} bytes")
        @CsvSource({"0, 700", "0, 2000", "2, 700", "2, 2000", "2, 6000"})
        @DisplayName("cut one packet per picture and stamp the pictures their PES packets left unstamped")
        void cut(int bFrames, int size) {
            var pictures = pictures(bFrames);
            var read = read(program(pictures, size, true));
            var packets = read.of(0xE0);
            assertEquals(pictures.size(), packets.size());
            var origin = pictures.stream().mapToLong(Packet::pts).min().orElseThrow();
            for (var index = 0; index < pictures.size(); index++) {
                var want = pictures.get(index);
                var got = packets.get(index);
                assertArrayEquals(want.data(), got.data(), "picture " + index);
                assertEquals(want.keyframe(), got.keyframe(), "key flag of picture " + index);
                assertEquals(want.pts() - origin, got.pts(), "pts of picture " + index);
                assertEquals(want.dts() - origin, got.dts(), "dts of picture " + index);
            }
        }

        @Test
        @DisplayName("keep the two field pictures of a frame in one packet")
        void fieldPictures() {
            var sequence = TsMpegVideoTest.pictures(TsMpegVideoTest.elementary("176x144", 1)).getFirst();
            var headers = com.github.auties00.smalt.internal.codec.MpegVideoHeaders.config(sequence);
            var group = new byte[]{0, 0, 1, (byte) 0xB8, 0, 8, 0, 0};
            var stream = new ByteArrayOutputStream();
            stream.writeBytes(headers);
            stream.writeBytes(group);
            // I top field, I bottom field (temporal reference 0), then a P frame picture (temporal reference 1)
            stream.writeBytes(field(0, 1, 1));
            stream.writeBytes(field(0, 1, 2));
            stream.writeBytes(field(1, 2, 3));
            stream.writeBytes(field(2, 2, 1));
            stream.writeBytes(field(2, 2, 2));
            var bytes = stream.toByteArray();
            var writer = new PsWriter();
            writer.pack(0).pes(0xE0, 90_000, -1, bytes);
            var read = read(writer.end().toByteArray());
            var packets = read.of(0xE0);
            assertEquals(3, packets.size(), "two field pairs and a frame");
            assertTrue(packets.getFirst().keyframe());
            assertEquals(headers.length + group.length + 2 * field(0, 1, 1).length, packets.getFirst().size());
            assertEquals(0, packets.getFirst().pts());
            assertEquals(3600, packets.get(1).dts(), "a field pair lasts a frame");
            assertEquals(7200, packets.get(2).dts());
        }

        /** Returns a picture header, a picture coding extension and a slice of one field or frame. */
        static byte[] field(int temporalReference, int type, int structure) {
            return new byte[]{0, 0, 1, 0, (byte) (temporalReference >> 2),
                    (byte) ((temporalReference & 3) << 6 | type << 3), (byte) 0xFF, (byte) 0xF8,
                    0, 0, 1, (byte) 0xB5, (byte) 0x81, 0x11, (byte) (0xF0 | structure), (byte) 0x80, (byte) 0x80,
                    0, 0, 1, 1, 0x12, 0x34, 0x56};
        }

        @Test
        @DisplayName("cut MPEG-4 Part 2 video into video object planes whatever the packetization")
        void mpeg4() {
            var file = Fixtures.ffmpeg("ts", List.of("-f", "lavfi", "-i", "testsrc2=size=176x144:rate=25:duration=1"),
                    List.of("-c:v", "mpeg4", "-g", "5", "-bf", "0", "-q:v", "6"));
            var frames = ContainerProbe.demux(file, TsDemuxer::new).order();
            var headers = frames.getFirst();
            var stream = new ByteArrayOutputStream();
            frames.forEach(frame -> stream.writeBytes(frame.data()));
            var bytes = stream.toByteArray();
            var writer = new PsWriter().pack(0).map(List.of(new PsWriter.MapEntry(0x10, 0xE0, null)), false, false);
            for (int offset = 0, index = 0; offset < bytes.length; offset += 1500, index++) {
                writer.pack(index * 100L).pes(0xE0, index == 0 ? 90_000 : -1, -1,
                        Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + 1500)));
            }
            var packets = read(writer.end().toByteArray()).of(0xE0);
            assertEquals(frames.size(), packets.size());
            assertTrue(packets.getFirst().keyframe());
            for (var index = 0; index < frames.size(); index++) {
                assertArrayEquals(frames.get(index).data(), packets.get(index).data(), "frame " + index);
            }
            assertTrue(headers.keyframe());
        }
    }

    @Test
    @DisplayName("rejects an input without a pack header")
    void noPack() {
        var random = new byte[100_000];
        new Random(1).nextBytes(random);
        assertThrows(MediaException.Malformed.class, () -> read(random));
        assertThrows(MediaException.Malformed.class, () -> read(new PsWriter().pack(0).end().toByteArray()),
                "no elementary stream");
    }

    @Test
    @DisplayName("skips leading garbage before the first pack")
    void leadingGarbage() {
        var file = Fixtures.read(SAMPLES.resolve("mpeg1.mpg"));
        var garbage = new byte[5000];
        new Random(2).nextBytes(garbage);
        for (var index = 0; index + 4 <= garbage.length; index++) {
            if (garbage[index] == 0 && garbage[index + 1] == 0) {
                garbage[index] = 1;
            }
        }
        var shifted = new byte[garbage.length + file.length];
        System.arraycopy(garbage, 0, shifted, 0, garbage.length);
        System.arraycopy(file, 0, shifted, garbage.length, file.length);
        var clean = read(file);
        var read = read(shifted);
        assertEquals(clean.packets().size(), read.packets().size());
        for (var index = 0; index < clean.packets().size(); index++) {
            assertArrayEquals(clean.packets().get(index).data(), read.packets().get(index).data());
        }
    }

    static byte[] readAll(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
