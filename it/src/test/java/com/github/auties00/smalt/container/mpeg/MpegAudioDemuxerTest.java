package com.github.auties00.smalt.container.mpeg;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.mp3.Mp3Decoder;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.testkit.container.CountingSource;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.audio.AudioPipeline;
import com.github.auties00.smalt.testkit.audio.ProbedPacket;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests {@link MpegAudioDemuxer}: packet parity with ffprobe, gapless metadata, tag skipping,
 * resynchronization after garbage, free format and format changes.
 */
class MpegAudioDemuxerTest {
    static List<String> signal(int rate, int channels, double seconds) {
        var exprs = "0.3*sin(2*PI*440*t)+0.2*sin(2*PI*3100*t)*gt(mod(t\\,0.2)\\,0.15)+0.05*(random(0)-0.5)";
        if (channels == 2) {
            exprs += "|0.3*sin(2*PI*660*t)+0.05*(random(1)-0.5)";
        }
        return List.of("-f", "lavfi", "-i", "aevalsrc=exprs=" + exprs + ":s=" + rate + ":d=" + seconds);
    }

    static Path encode(String extension, int rate, int channels, double seconds, String... codec) {
        var output = new ArrayList<>(List.of("-ar", Integer.toString(rate), "-ac", Integer.toString(channels)));
        output.addAll(List.of(codec));
        return Fixtures.ffmpeg(extension, signal(rate, channels, seconds), output);
    }

    /** A plain stream without ID3v2, Xing or ID3v1, to wrap in hand-made tags. */
    static byte[] bare(int rate, int channels) {
        return Fixtures.read(encode("mp3", rate, channels, 1.2, "-c:a", "libmp3lame", "-b:a", "128k", "-write_xing", "0",
                "-id3v2_version", "0"));
    }

    static MpegAudioDemuxer open(byte[] data) {
        return new MpegAudioDemuxer(MediaSource.of(data));
    }

    static List<Packet> packets(byte[] data) {
        try (var demuxer = open(data)) {
            return AudioPipeline.packets(demuxer, 0);
        }
    }

    static List<Long> crcs(List<Packet> packets) {
        return packets.stream().map(packet -> ProbedPacket.crc32(packet.data())).toList();
    }

    static Rational ffprobeTimeBase(Path file) {
        var text = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(1), List.of("-v", "error",
                "-select_streams", "a:0", "-show_entries", "stream=time_base", "-of", "csv=p=0", file.toString())),
                StandardCharsets.UTF_8).trim();
        var parts = text.split("/");
        return new Rational(Long.parseLong(parts[0]), Long.parseLong(parts[1]));
    }

    static Stream<Arguments> probeFixtures() {
        return Stream.of(
                Arguments.of("mp3", 44100, 2, new String[]{"-c:a", "libmp3lame", "-b:a", "128k"}),
                Arguments.of("mp3", 48000, 1, new String[]{"-c:a", "libmp3lame", "-q:a", "3"}),
                Arguments.of("mp3", 22050, 2, new String[]{"-c:a", "libmp3lame", "-b:a", "64k", "-write_xing", "0"}),
                Arguments.of("mp3", 8000, 1, new String[]{"-c:a", "libmp3lame", "-b:a", "16k"}),
                Arguments.of("mp3", 32000, 2, new String[]{"-c:a", "libmp3lame", "-abr", "1", "-b:a", "96k"}),
                Arguments.of("mp2", 48000, 2, new String[]{"-c:a", "mp2", "-b:a", "256k"}),
                Arguments.of("mp2", 24000, 1, new String[]{"-c:a", "mp2", "-b:a", "64k"}));
    }

    @ParameterizedTest(name = "{0} {1} Hz {2} ch")
    @MethodSource("probeFixtures")
    @DisplayName("packets match ffprobe in count, payload CRC, timestamp and duration")
    void matchesFfprobe(String extension, int rate, int channels, String[] codec) {
        var file = encode(extension, rate, channels, 2.3, codec);
        var expected = ProbedPacket.probe(file);
        var timeBase = ffprobeTimeBase(file);
        try (var demuxer = new MpegAudioDemuxer(MediaSource.of(Fixtures.read(file)))) {
            assertEquals(ContainerFormat.MPEG_AUDIO, demuxer.format());
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(rate, track.sampleRate());
            assertEquals(Rational.timeBaseOf(rate), track.timeBase());
            assertEquals(channels == 1 ? ChannelLayout.MONO : ChannelLayout.STEREO, track.layout().orElseThrow());
            assertEquals(extension.equals("mp3") ? Codec.MP3 : Codec.MP2, track.codec());
            var actual = AudioPipeline.packets(demuxer, 0);
            assertEquals(expected.size(), actual.size(), "packet count");
            for (var i = 0; i < actual.size(); i++) {
                var packet = actual.get(i);
                var probed = expected.get(i);
                assertEquals(probed.size(), packet.size(), "size of packet " + i);
                assertEquals(probed.crc32(), ProbedPacket.crc32(packet.data()), "payload of packet " + i);
                assertEquals(probed.pts(), track.timeBase().rescale(packet.pts(), timeBase), "pts of packet " + i);
                assertEquals(probed.duration(), track.timeBase().rescale(packet.duration(), timeBase), "duration of packet " + i);
                assertTrue(packet.keyframe());
            }
        }
    }

    @Test
    @DisplayName("a LAME tag yields the encoder delay plus 529 as skip and the exact original length")
    void gapless() {
        var file = encode("mp3", 44100, 2, 1.013, "-c:a", "libmp3lame", "-b:a", "128k");
        try (var demuxer = new MpegAudioDemuxer(MediaSource.of(Fixtures.read(file)))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(576 + 529, track.skipSamples());
            assertEquals(44673, track.duration());
            assertEquals(1_012_993, demuxer.durationMicros());
            assertEquals("Lavf61.7.100", demuxer.metadata().get("TSSE"));
        }
    }

    @Test
    @DisplayName("an Info frame without a LAME tag gives the duration but no trimming")
    void infoWithoutLame() {
        var data = Fixtures.read(encode("mp3", 44100, 2, 1.0, "-c:a", "libmp3lame", "-b:a", "128k", "-id3v2_version", "0"));
        var lame = indexOf(data, "Lavc".getBytes(StandardCharsets.US_ASCII));
        assertTrue(lame > 0 && lame < 400);
        data[lame] = 'X';
        try (var demuxer = open(data)) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(0, track.skipSamples());
            assertEquals(AudioPipeline.packets(demuxer, 0).size() * 1152L, track.duration());
        }
    }

    @Nested
    @DisplayName("tags")
    class Tags {
        @Test
        @DisplayName("a large ID3v2.4 tag with footer and false sync words is skipped whole")
        void largeId3v24() {
            var audio = bare(44100, 2);
            var picture = new byte[300_000];
            var random = new SplittableRandom(7);
            for (var i = 0; i < picture.length; i++) {
                picture[i] = (byte) random.nextInt(256);
            }
            for (var i = 0; i < picture.length - 4; i += 997) {
                picture[i] = (byte) 0xFF;
                picture[i + 1] = (byte) 0xFB;
                picture[i + 2] = (byte) 0x90;
                picture[i + 3] = (byte) 0x64;
            }
            var tag = id3v2(4, true, false, List.of(textFrame(4, "TIT2", "Title"), frame(4, "APIC", picture),
                    textFrame(4, "TPE1", "Artist")));
            var tagged = concat(tag, audio);
            assertEquals(crcs(packets(audio)), crcs(packets(tagged)));
            try (var demuxer = open(tagged)) {
                assertEquals("Title", demuxer.metadata().get("TIT2"));
                assertEquals("Artist", demuxer.metadata().get("TPE1"));
            }
        }

        @Test
        @DisplayName("ID3v2.3 with whole-tag unsynchronization and ID3v2.2 tags are skipped and decoded")
        void unsynchronizedAndOldTags() {
            var audio = bare(22050, 1);
            var title = "Caf\u00ff\u00ff title";
            var v23 = id3v2(3, false, true, List.of(textFrame(3, "TIT2", title)));
            var v22 = id3v2(2, false, false, List.of(textFrame(2, "TAL", "Album")));
            var tagged = concat(v23, v22, audio);
            assertEquals(crcs(packets(audio)), crcs(packets(tagged)));
            try (var demuxer = open(tagged)) {
                assertEquals(title, demuxer.metadata().get("TIT2"));
                assertEquals("Album", demuxer.metadata().get("TALB"));
            }
        }

        @Test
        @DisplayName("ID3v1, enhanced TAG+, APE and Lyrics3v2 tags at the end are excluded")
        void trailingTags() {
            var audio = bare(44100, 2);
            var id3v1 = new byte[128];
            id3v1[0] = 'T';
            id3v1[1] = 'A';
            id3v1[2] = 'G';
            System.arraycopy("Old title".getBytes(StandardCharsets.ISO_8859_1), 0, id3v1, 3, 9);
            System.arraycopy("Old artist".getBytes(StandardCharsets.ISO_8859_1), 0, id3v1, 33, 10);
            id3v1[126] = 7;
            var enhanced = new byte[227];
            System.arraycopy("TAG+".getBytes(StandardCharsets.ISO_8859_1), 0, enhanced, 0, 4);
            var ape = apeTag();
            var lyrics = lyrics3("[00:01]hello");
            for (var tail : List.of(concat(id3v1), concat(enhanced, id3v1), concat(ape), concat(ape, id3v1),
                    concat(lyrics, ape, id3v1))) {
                var tagged = concat(audio, tail);
                assertEquals(crcs(packets(audio)), crcs(packets(tagged)));
            }
            try (var demuxer = open(concat(audio, id3v1))) {
                assertEquals("Old title", demuxer.metadata().get("TIT2"));
                assertEquals("Old artist", demuxer.metadata().get("TPE1"));
                assertEquals("7", demuxer.metadata().get("TRCK"));
            }
        }
    }

    @Nested
    @DisplayName("resynchronization")
    class Resync {
        @Test
        @DisplayName("garbage with false sync words between frames is skipped without losing frames")
        void garbageBetweenFrames() {
            var audio = bare(44100, 2);
            var expected = packets(audio);
            var cut = offsetOfFrame(expected, 10);
            var garbage = new byte[1500];
            var random = new SplittableRandom(3);
            for (var i = 0; i < garbage.length; i++) {
                garbage[i] = (byte) random.nextInt(256);
            }
            garbage[100] = (byte) 0xFF;
            garbage[101] = (byte) 0xFB;
            garbage[102] = (byte) 0x90;
            garbage[103] = (byte) 0x64;
            var damaged = concat(slice(audio, 0, cut), garbage, slice(audio, cut, audio.length));
            assertEquals(crcs(expected), crcs(packets(damaged)));
        }

        @Test
        @DisplayName("garbage before the first frame is skipped")
        void leadingGarbage() {
            var audio = bare(32000, 1);
            var garbage = new byte[5000];
            new SplittableRandom(11).nextBytes(garbage);
            assertEquals(crcs(packets(audio)), crcs(packets(concat(garbage, audio))));
        }

        @Test
        @DisplayName("a frame with a destroyed header is dropped and the following frames are kept")
        void destroyedHeader() {
            var audio = bare(44100, 2);
            var expected = packets(audio);
            var damaged = audio.clone();
            var offset = offsetOfFrame(expected, 5);
            damaged[offset] = 0;
            damaged[offset + 1] = 0;
            var actual = packets(damaged);
            var expectedCrcs = new ArrayList<>(crcs(expected));
            expectedCrcs.remove(5);
            assertEquals(expectedCrcs, crcs(actual));
        }

        @Test
        @DisplayName("a stream that switches sample rate keeps continuous timestamps and decodes both parts")
        void formatChange() {
            var first = bare(44100, 2);
            var second = bare(48000, 2);
            var joined = concat(first, second);
            var firstCount = packets(first).size();
            var packets = packets(joined);
            assertEquals(firstCount + packets(second).size(), packets.size());
            var switchPts = firstCount * 1152L;
            assertEquals(switchPts, packets.get(firstCount).pts());
            assertEquals(Math.round(1152 * 44100 / 48000.0), packets.get(firstCount).duration());
            var rates = new ArrayList<Integer>();
            try (var demuxer = open(joined); var decoder = new Mp3Decoder((AudioTrack) demuxer.tracks().getFirst())) {
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    decoder.decode(packet, buffer -> rates.add(buffer.sampleRate()));
                }
                assertEquals(44100, decoder.sampleRate());
            }
            assertEquals(44100, rates.getFirst());
            assertEquals(48000, rates.getLast());
        }
    }

    @Test
    @DisplayName("a final frame cut short by the end of the file is still returned")
    void truncatedTail() {
        var audio = bare(44100, 2);
        var expected = packets(audio);
        var cut = slice(audio, 0, audio.length - 100);
        var actual = packets(cut);
        assertEquals(expected.size(), actual.size());
        assertEquals(expected.getLast().size() - 100, actual.getLast().size());
    }

    @Test
    @DisplayName("free-format streams are split at the detected frame size and decode like the original")
    void freeFormat() {
        var audio = bare(44100, 2);
        var free = audio.clone();
        var expected = packets(audio);
        var offset = 0;
        for (var packet : expected) {
            free[offset + 2] = (byte) (free[offset + 2] & 0x0F);
            offset += packet.size();
        }
        var actual = packets(free);
        assertEquals(expected.stream().map(Packet::size).toList(), actual.stream().map(Packet::size).toList());
        try (var original = open(audio); var modified = open(free)) {
            assertArrayEquals(AudioPipeline.decode(original, Mp3Decoder::new).interleaved(),
                    AudioPipeline.decode(modified, Mp3Decoder::new).interleaved());
        }
    }

    @Nested
    @DisplayName("durations")
    class Durations {
        @Test
        @DisplayName("without a Xing frame, a source of unknown size reports an unknown track duration")
        void unknownSize() {
            var audio = bare(44100, 2);
            var source = new SizedSource(MediaSource.of(audio), -1);
            try (var demuxer = new MpegAudioDemuxer(source)) {
                assertEquals(-1, demuxer.tracks().getFirst().duration());
                var exact = packets(audio).size() * 1152 * 1_000_000L / 44100;
                // the first read window reaches the end of this small input, so an estimate exists
                assertTrue(Math.abs(demuxer.durationMicros() - exact) < exact / 20, "estimate " + demuxer.durationMicros());
                assertEquals(crcs(packets(audio)), crcs(AudioPipeline.packets(demuxer, 0)));
            }
        }

        @Test
        @DisplayName("without a Xing frame, a source above the scan limit gets a bitrate estimate only")
        void estimate() {
            var audio = bare(44100, 2);
            var source = new SizedSource(MediaSource.of(audio), MpegAudioDemuxer.SCAN_LIMIT + 1);
            try (var demuxer = new MpegAudioDemuxer(source)) {
                assertEquals(-1, demuxer.tracks().getFirst().duration());
                var exact = packets(audio).size() * 1152 * 1_000_000L / 44100;
                assertTrue(Math.abs(demuxer.durationMicros() - exact) < exact / 20, "estimate " + demuxer.durationMicros());
            }
        }

        @Test
        @DisplayName("without a Xing frame, a small file is scanned for its exact duration")
        void scanned() {
            var audio = bare(44100, 2);
            try (var demuxer = open(audio)) {
                assertEquals(packets(audio).size() * 1152L, demuxer.tracks().getFirst().duration());
            }
        }
    }

    @Nested
    @DisplayName("single pass")
    class SinglePass {
        static List<ProbedPacket> probed(MpegAudioDemuxer demuxer) {
            return AudioPipeline.packets(demuxer, 0).stream().map(ProbedPacket::of).toList();
        }

        @Test
        @DisplayName("reads a file without a Xing frame once, keeping the duration count's packets")
        void readsOnce() {
            var audio = concat(Fixtures.read(encode("mp3", 44100, 2, 20, "-c:a", "libmp3lame", "-b:a", "128k", "-write_xing", "0")),
                    apeTag());
            var source = new CountingSource(audio);
            try (var counted = new MpegAudioDemuxer(source); var memory = open(audio)) {
                assertEquals(memory.tracks(), counted.tracks());
                assertEquals(memory.durationMicros(), counted.durationMicros());
                assertEquals(probed(memory), probed(counted));
            }
            // the trailing-tag probe reads the last 4 KiB once more, in one read
            assertTrue(source.bytes <= audio.length + 4096 + 64, source.toString());
            assertTrue(source.requests <= 3, source.toString());
        }

        @Test
        @DisplayName("continues from the first frame it did not keep when the budget runs out")
        void budget() {
            var audio = concat(bare(44100, 2), bare(32000, 1), bare(44100, 2));
            for (var maxBytes : new long[]{1, 1000, 20_000, 70_000}) {
                var limits = MediaLimits.builder().maxDimension(16_384).maxPixels(128L * 1024 * 1024)
                        .maxFrames(1_000_000).maxChannels(64).maxBytes(maxBytes).build();
                var options = DemuxerOptions.builder().limits(limits).build();
                for (var chunk : new int[]{1000, Integer.MAX_VALUE}) {
                    try (var counted = new MpegAudioDemuxer(new CountingSource(audio, chunk), options);
                         var memory = new MpegAudioDemuxer(MediaSource.of(audio), options)) {
                        assertEquals(memory.tracks(), counted.tracks());
                        assertEquals(probed(memory), probed(counted), "maxBytes " + maxBytes + ", chunk " + chunk);
                    }
                }
            }
        }

        @Test
        @DisplayName("opens a tagged file with a Xing frame in three requests: the start, the tail, and back")
        void openRequests() {
            var audio = concat(id3v2(3, false, false, List.of(textFrame(3, "TIT2", "Title"))),
                    Fixtures.read(encode("mp3", 44100, 2, 30, "-c:a", "libmp3lame", "-b:a", "128k", "-id3v2_version", "0")),
                    apeTag(), lyrics3("words"), apeTag());
            var source = new CountingSource(audio);
            try (var demuxer = new MpegAudioDemuxer(source)) {
                assertEquals(3, source.requests, source.toString());
                assertEquals("Title", demuxer.metadata().get("TIT2"));
                AudioPipeline.packets(demuxer, 0);
            }
            assertEquals(3, source.requests, source.toString());
        }
    }

    @Test
    @DisplayName("inputs without MPEG audio frames are rejected as Malformed")
    void notMpeg() {
        assertThrows(MediaException.Malformed.class, () -> open(new byte[0]));
        var noise = new byte[200_000];
        new SplittableRandom(5).nextBytes(noise);
        for (var i = 0; i < noise.length - 1; i++) {
            if ((noise[i] & 0xFF) == 0xFF) {
                noise[i + 1] = 0;
            }
        }
        assertThrows(MediaException.Malformed.class, () -> open(noise));
        var wav = Fixtures.read(Fixtures.tone("wav", 440, 44100, 2, 0.5, List.of()));
        assertThrows(MediaException.Malformed.class, () -> open(wav));
    }

    @Test
    @DisplayName("the decoded output of a whole file is finite and non-trivial")
    void decodes() {
        var audio = bare(44100, 2);
        try (var demuxer = open(audio)) {
            var decoded = AudioPipeline.decode(demuxer, Mp3Decoder::new);
            assertFalse(decoded.interleaved().length == 0);
        }
    }

    static int offsetOfFrame(List<Packet> packets, int index) {
        var offset = 0;
        for (var i = 0; i < index; i++) {
            offset += packets.get(i).size();
        }
        return offset;
    }

    static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (var i = 0; i <= data.length - pattern.length; i++) {
            for (var j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    static byte[] slice(byte[] data, int from, int to) {
        return java.util.Arrays.copyOfRange(data, from, to);
    }

    static byte[] concat(byte[]... parts) {
        var out = new ByteArrayOutputStream();
        for (var part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    static byte[] frame(int version, String id, byte[] payload) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(id.getBytes(StandardCharsets.ISO_8859_1));
        var size = payload.length;
        if (version == 2) {
            out.write(size >> 16);
            out.write(size >> 8);
            out.write(size);
        } else if (version == 3) {
            out.write(size >>> 24);
            out.write(size >> 16);
            out.write(size >> 8);
            out.write(size);
            out.write(0);
            out.write(0);
        } else {
            out.write((size >> 21) & 0x7F);
            out.write((size >> 14) & 0x7F);
            out.write((size >> 7) & 0x7F);
            out.write(size & 0x7F);
            out.write(0);
            out.write(0);
        }
        out.writeBytes(payload);
        return out.toByteArray();
    }

    static byte[] textFrame(int version, String id, String text) {
        var payload = concat(new byte[]{0}, text.getBytes(StandardCharsets.ISO_8859_1));
        return frame(version, id, payload);
    }

    static byte[] id3v2(int version, boolean footer, boolean unsynchronize, List<byte[]> frames) {
        var body = concat(frames.toArray(byte[][]::new));
        if (unsynchronize) {
            var out = new ByteArrayOutputStream();
            for (var i = 0; i < body.length; i++) {
                out.write(body[i]);
                if ((body[i] & 0xFF) == 0xFF && (i + 1 == body.length || (body[i + 1] & 0xE0) == 0xE0 || body[i + 1] == 0)) {
                    out.write(0);
                }
            }
            body = out.toByteArray();
        }
        var header = new byte[10];
        header[0] = 'I';
        header[1] = 'D';
        header[2] = '3';
        header[3] = (byte) version;
        header[5] = (byte) ((footer ? 0x10 : 0) | (unsynchronize ? 0x80 : 0));
        var size = body.length;
        header[6] = (byte) ((size >> 21) & 0x7F);
        header[7] = (byte) ((size >> 14) & 0x7F);
        header[8] = (byte) ((size >> 7) & 0x7F);
        header[9] = (byte) (size & 0x7F);
        if (!footer) {
            return concat(header, body);
        }
        var trailer = header.clone();
        trailer[0] = '3';
        trailer[1] = 'D';
        trailer[2] = 'I';
        return concat(header, body, trailer);
    }

    static byte[] apeTag() {
        var item = concat(new byte[]{5, 0, 0, 0, 0, 0, 0, 0}, "Title\0Hello".getBytes(StandardCharsets.US_ASCII));
        var size = item.length + 32;
        var header = apeBlock(size, true);
        var footer = apeBlock(size, false);
        return concat(header, item, footer);
    }

    static byte[] apeBlock(int size, boolean header) {
        var block = new byte[32];
        System.arraycopy("APETAGEX".getBytes(StandardCharsets.US_ASCII), 0, block, 0, 8);
        block[8] = (byte) 0xD0;
        block[9] = 0x07;
        block[12] = (byte) size;
        block[13] = (byte) (size >> 8);
        block[16] = 1;
        block[23] = (byte) (header ? 0xE0 : 0x80);
        return block;
    }

    static byte[] lyrics3(String lyrics) {
        var body = ("LYRICSBEGIN" + "LYR" + String.format("%05d", lyrics.length()) + lyrics).getBytes(StandardCharsets.ISO_8859_1);
        return concat(body, (String.format("%06d", body.length) + "LYRICS200").getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Reports a chosen size for a source, to simulate unknown or huge inputs. */
    record SizedSource(MediaSource delegate, long size) implements MediaSource {
        @Override
        public int read(long position, byte[] buffer, int offset, int length) {
            return delegate.read(position, buffer, offset, length);
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
