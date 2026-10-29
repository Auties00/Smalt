package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Scratch;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests of {@link Mp4Muxer}: files demuxed by {@link Mp4Demuxer} and muxed again must
 * probe identically to the original under ffprobe (same packets, timestamps, flags, edit list
 * effects and display matrix) and decode cleanly.
 */
@DisplayName("Mp4Muxer")
class Mp4MuxerTest {
    static final Path OUTPUT = Scratch.directory("mp4-muxer-out");

    record RemuxCase(String name, java.util.function.Supplier<Path> file) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<RemuxCase> remuxCases() {
        var cases = new ArrayList<RemuxCase>();
        cases.add(new RemuxCase("h264 b-pyramid + aac", () -> Mp4Fixtures.h264Aac(true, List.of(), "mp4")));
        cases.add(new RemuxCase("h264 no b-frames + aac", () -> Mp4Fixtures.h264Aac(false, List.of(), "mp4")));
        cases.add(new RemuxCase("h264 negative cts", () -> Mp4Fixtures.h264Aac(true, List.of("-movflags", "+negative_cts_offsets"), "mp4")));
        cases.add(new RemuxCase("hevc + aac", () -> Fixtures.ffmpeg("mp4", Mp4Fixtures.both(160, 120, 1.5, 44100),
                List.of("-c:v", "libx265", "-x265-params", "log-level=error:bframes=4", "-tag:v", "hvc1", "-c:a", "aac"))));
        cases.add(new RemuxCase("opus", () -> Fixtures.ffmpeg("mp4", Mp4Fixtures.audio(48000, 1.2), List.of("-c:a", "libopus", "-ac", "2"))));
        cases.add(new RemuxCase("mp3", () -> Fixtures.ffmpeg("mp4", Mp4Fixtures.audio(44100, 1.5), List.of("-c:a", "libmp3lame"))));
        cases.add(new RemuxCase("alac", () -> Fixtures.ffmpeg("m4a", Mp4Fixtures.audio(44100, 1.2), List.of("-c:a", "alac"))));
        cases.add(new RemuxCase("flac", () -> Fixtures.ffmpeg("mp4", Mp4Fixtures.audio(44100, 1.2), List.of("-c:a", "flac"))));
        cases.add(new RemuxCase("aac m4a", () -> Fixtures.ffmpeg("m4a", Mp4Fixtures.audio(44100, 1.2), List.of("-c:a", "aac", "-ac", "2"))));
        cases.add(new RemuxCase("rotation 90", () -> Mp4Fixtures.copy(Mp4Fixtures.h264Aac(false, List.of(), "mp4"), "mp4",
                List.of("-display_rotation", "90"), List.of())));
        cases.add(new RemuxCase("rotation 270 + hflip", () -> Mp4Fixtures.copy(Mp4Fixtures.h264Aac(false, List.of(), "mp4"), "mp4",
                List.of("-display_rotation", "270", "-display_hflip"), List.of())));
        cases.add(new RemuxCase("edit list from -ss copy", () -> Mp4Fixtures.copy(Mp4Fixtures.h264Aac(true, List.of(), "mp4"), "mp4",
                List.of("-ss", "0.7"), List.of())));
        cases.add(new RemuxCase("two audio tracks", () -> Fixtures.ffmpeg("mp4", List.of(
                        "-f", "lavfi", "-i", "testsrc2=size=160x120:rate=30:duration=1",
                        "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1",
                        "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=44100:duration=1"),
                List.of("-map", "0", "-map", "1", "-map", "2", "-c:v", "libx264", "-c:a", "aac"))));
        return cases;
    }

    static Path output(String name) {
        try {
            Files.createDirectories(OUTPUT);
            return OUTPUT.resolve(name.replaceAll("[^A-Za-z0-9]+", "-") + ".mp4");
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /** Demuxes a file and muxes every track into a new file. */
    static Path remux(Path input, String name, Mp4MuxerOptions options, Consumer<Mp4Muxer> configure) {
        var target = output(name);
        try (var source = MediaSource.of(input); var demuxer = new Mp4Demuxer(source);
             var channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                     StandardOpenOption.READ, StandardOpenOption.TRUNCATE_EXISTING);
             var muxer = new Mp4Muxer(MediaSink.of(channel), options)) {
            configure.accept(muxer);
            for (var track : demuxer.tracks()) {
                muxer.addTrack(track);
            }
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return target;
    }

    /** Asserts that two files probe to the same packets and stream timing. */
    static void assertSameProbe(Path expected, Path actual) {
        var want = ContainerProbe.probe(expected);
        var got = ContainerProbe.probe(actual);
        assertEquals(want.streams().size(), got.streams().size(), "stream count");
        for (var index = 0; index < want.streams().size(); index++) {
            var a = want.streams().get(index);
            var b = got.streams().get(index);
            for (var key : List.of("codec_name", "time_base", "start_pts", "duration_ts", "sample_rate", "channels",
                    "width", "height", "extradata_hash", "profile")) {
                assertEquals(a.get(key), b.get(key), "stream " + index + " " + key);
            }
            assertEquals(a.rotation(), b.rotation(), "stream " + index + " rotation");
            var packetsA = want.packetsOf(index);
            var packetsB = got.packetsOf(index);
            assertEquals(packetsA.size(), packetsB.size(), "stream " + index + " packet count");
            for (var position = 0; position < packetsA.size(); position++) {
                var x = packetsA.get(position);
                var y = packetsB.get(position);
                var where = "stream " + index + " packet " + position;
                assertEquals(x.hash(), y.hash(), where + " hash");
                assertEquals(x.key(), y.key(), where + " key");
                assertEquals(x.discard(), y.discard(), where + " discard");
                assertEquals(x.pts(), y.pts(), where + " pts");
                assertEquals(x.dts(), y.dts(), where + " dts");
                assertEquals(x.duration(), y.duration(), where + " duration");
                assertEquals(x.size(), y.size(), where + " size");
            }
        }
    }

    static boolean hasBox(Path file, String type) {
        var data = Fixtures.read(file);
        var needle = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (var index = 0; index + 4 <= data.length; index++) {
            for (var offset = 0; offset < 4; offset++) {
                if (data[index + offset] != needle[offset]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    static boolean isFastStart(Path file) {
        try (var source = MediaSource.of(file)) {
            return TopLevelScan.scan(source, true).isFastStart();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("remuxCases")
    @DisplayName("fast-start remux probes identically and decodes cleanly")
    void fastStart(RemuxCase fixture) {
        var input = fixture.file().get();
        var output = remux(input, fixture.name() + "-faststart", Mp4MuxerOptions.mp4(), muxer -> {
        });
        assertSameProbe(input, output);
        Oracle.assertDecodesCleanly(output);
        assertTrue(isFastStart(output), "moov precedes mdat");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("remuxCases")
    @DisplayName("progressive remux probes identically with moov after mdat")
    void progressive(RemuxCase fixture) {
        var input = fixture.file().get();
        var output = remux(input, fixture.name() + "-progressive", Mp4MuxerOptions.mp4().toBuilder().fastStart(false)
                .build(), muxer -> {
        });
        assertSameProbe(input, output);
        assertFalse(isFastStart(output), "moov follows mdat");
    }

    @Nested
    @DisplayName("layout")
    class Layout {
        @Test
        @DisplayName("writes 64-bit chunk offsets and mdat size when forced")
        void co64() {
            var input = Mp4Fixtures.h264Aac(true, List.of(), "mp4");
            for (var fast : List.of(true, false)) {
                var output = remux(input, "co64-" + fast, Mp4MuxerOptions.mp4().toBuilder().fastStart(fast)
                        .build(), Mp4Muxer::forceLargeOffsets);
                assertTrue(hasBox(output, "co64"));
                assertFalse(hasBox(output, "stco"));
                assertSameProbe(input, output);
                Oracle.assertDecodesCleanly(output);
            }
        }

        @Test
        @DisplayName("declares the M4A brand for audio files")
        void m4aBrand() {
            var input = Fixtures.ffmpeg("m4a", Mp4Fixtures.audio(44100, 1.2), List.of("-c:a", "aac", "-ac", "2"));
            var output = remux(input, "m4a-brand", Mp4MuxerOptions.m4a(), muxer -> {
            });
            var probe = ContainerProbe.probe(output);
            assertEquals("M4A ", probe.format().get("tag:major_brand"));
            assertEquals("mov,mp4,m4a,3gp,3g2,mj2", probe.format().get("format_name"));
            assertSameProbe(input, output);
        }

        @Test
        @DisplayName("keeps small interleaved chunks")
        void interleaving() {
            var input = Mp4Fixtures.h264Aac(true, List.of(), "mp4");
            var output = remux(input, "interleave-100ms", Mp4MuxerOptions.mp4().toBuilder().interleaveMicros(100_000)
                    .build(), muxer -> {
            });
            assertSameProbe(input, output);
            var order = ContainerProbe.demux(output, source -> new Mp4Demuxer(source)).order();
            var switches = 0;
            for (var index = 1; index < order.size(); index++) {
                if (order.get(index).trackId() != order.get(index - 1).trackId()) {
                    switches++;
                }
            }
            assertTrue(switches >= 30, "tracks alternate every 100 ms: " + switches);
        }

        @Test
        @DisplayName("spills the fast-start spool to a temporary file past the memory threshold")
        void spill() throws IOException {
            var input = Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=640x480:rate=30:duration=2"),
                    List.of("-vf", "noise=alls=100:allf=t", "-c:v", "libx264", "-preset", "ultrafast", "-qp", "0"));
            assertTrue(Files.size(input) > MdatSpool.MEMORY_LIMIT, "fixture exceeds the in-memory spool");
            var directory = Files.createDirectories(OUTPUT.resolve("spool"));
            var output = remux(input, "spill", Mp4MuxerOptions.mp4().toBuilder().temporaryDirectory(directory)
                    .build(), muxer -> {
            });
            assertSameProbe(input, output);
            try (var files = Files.list(directory)) {
                assertEquals(0, files.count(), "the spool file is deleted");
            }
        }

        @Test
        @DisplayName("writes an empty but valid file without packets")
        void empty() throws IOException {
            var target = output("empty");
            try (var channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING); var muxer = new Mp4Muxer(MediaSink.of(channel),
                            Mp4MuxerOptions.m4a())) {
                muxer.addTrack(aacTrack(1, 48000, 2, -1, 0));
                muxer.finish();
            }
            try (var source = MediaSource.of(target); var demuxer = new Mp4Demuxer(source)) {
                assertEquals(1, demuxer.tracks().size());
                assertEquals(null, demuxer.read());
            }
        }
    }

    @Nested
    @DisplayName("contract")
    class Contract {
        @Test
        @DisplayName("rejects codecs, brands and orders it cannot write")
        void rejects() throws IOException {
            var target = output("rejects");
            try (var channel = FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING); var muxer = new Mp4Muxer(MediaSink.of(channel),
                            Mp4MuxerOptions.m4a())) {
                var video = com.github.auties00.smalt.track.VideoTrack.builder().id(1).codec(Codec.H264)
                        .timeBase(new Rational(1, 90000))
                        .codecConfig(new byte[]{1, 66, 0, 30, -1, -31, 0, 0, 1, 0, 0})
                        .width(16)
                        .height(16)
                        .build();
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(video));
                var vorbis = AudioTrack.builder().id(2).codec(Codec.VORBIS).timeBase(new Rational(1, 48000))
                        .codecConfig(new byte[3])
                        .sampleRate(48000)
                        .layout(ChannelLayout.STEREO)
                        .build();
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(vorbis));
                var noConfig = AudioTrack.builder().id(3).codec(Codec.AAC).timeBase(new Rational(1, 48000))
                        .sampleRate(48000)
                        .layout(ChannelLayout.STEREO)
                        .build();
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(noConfig));
                muxer.addTrack(aacTrack(1, 48000, 2, -1, 0));
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(aacTrack(1, 48000, 2, -1, 0)));
                assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(9, new byte[1], 0, 0, 0, true)));
                muxer.writePacket(new Packet(1, new byte[4], 1024, 1024, 1024, true));
                assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(1, new byte[4], 0, 0, 1024, true)));
                assertThrows(IllegalStateException.class, () -> muxer.addTrack(aacTrack(5, 44100, 1, -1, 0)));
                muxer.finish();
                assertThrows(IllegalStateException.class, muxer::finish);
                assertThrows(IllegalStateException.class, () -> muxer.writePacket(new Packet(1, new byte[4], 2048, 2048, 1024, true)));
            }
        }
    }

    @Nested
    @DisplayName("3GP brand")
    class ThreeGpp {
        @Test
        @DisplayName("carries the codecs of 3GPP TS 26.244 only")
        void codecs() {
            var brand = Mp4MuxerOptions.Brand.THREE_GPP;
            for (var codec : List.of(Codec.H263, Codec.MPEG4_PART2, Codec.H264, Codec.AMR_NB, Codec.AMR_WB, Codec.AAC,
                    Codec.MOV_TEXT)) {
                assertTrue(Mp4Muxer.supports(brand, codec), codec.displayName());
            }
            for (var codec : List.of(Codec.OPUS, Codec.MP3, Codec.FLAC, Codec.AC3, Codec.VP9, Codec.AV1, Codec.HEVC,
                    Codec.PCM_S16LE, Codec.PRORES)) {
                assertFalse(Mp4Muxer.supports(brand, codec), codec.displayName());
            }
            var opus = AudioTrack.builder().id(1).codec(Codec.OPUS).timeBase(new Rational(1, 48000))
                    .codecConfig(new byte[19]).sampleRate(48000).layout(ChannelLayout.STEREO).build();
            try (var muxer = new Mp4Muxer(MediaSink.memory(), Mp4MuxerOptions.threeGpp())) {
                var rejection = assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(opus));
                assertTrue(rejection.getMessage().contains("3GP"), rejection.getMessage());
            }
        }

        @Test
        @DisplayName("writes the 3gp6 file type, compatible with 3gp5, 3gp4 and isom, which the demuxer reads back")
        void fileType() throws IOException {
            assertEquals(Mp4MuxerOptions.Brand.THREE_GPP, Mp4MuxerOptions.threeGpp().brand());
            assertEquals(Mp4MuxerOptions.threeGpp(), Mp4MuxerOptions.builder().brand(Mp4MuxerOptions.Brand.THREE_GPP)
                    .build());
            var sink = MediaSink.memory();
            try (var muxer = new Mp4Muxer(sink, Mp4MuxerOptions.threeGpp())) {
                muxer.addTrack(aacTrack(1, 48000, 2, -1, 0));
                muxer.writePacket(new Packet(1, new byte[4], 0, 0, 1024, true));
                muxer.writePacket(new Packet(1, new byte[4], 1024, 1024, 1024, true));
                muxer.finish();
            }
            var bytes = sink.toByteArray();
            var ftyp = new String(bytes, 4, 28, java.nio.charset.StandardCharsets.ISO_8859_1);
            assertEquals("ftyp3gp6\0\0\2\0" + "3gp63gp53gp4isom", ftyp);
            assertEquals(32, java.nio.ByteBuffer.wrap(bytes, 0, 4).getInt(), "ftyp box size");
            try (var source = MediaSource.of(bytes); var demuxer = com.github.auties00.smalt.container.Containers
                    .openDemuxer(source)) {
                assertEquals(Codec.AAC, demuxer.tracks().getFirst().codec());
                var count = 0;
                while (demuxer.read() != null) {
                    count++;
                }
                assertEquals(2, count);
            }
        }
    }

    static AudioTrack aacTrack(int id, int sampleRate, int channels, long duration, long skip) {
        return AudioTrack.builder().id(id).codec(Codec.AAC).timeBase(new Rational(1, sampleRate)).duration(duration)
                .codecConfig(audioSpecificConfig(sampleRate, channels))
                .sampleRate(sampleRate)
                .layout(ChannelLayout.defaultFor(channels))
                .skipSamples(skip)
                .build();
    }

    /** Builds an AAC-LC AudioSpecificConfig for a rate of the standard table. */
    static byte[] audioSpecificConfig(int sampleRate, int channels) {
        var index = java.util.Arrays.stream(Mp4SyntheticMuxTest.RATES).boxed().toList().indexOf(sampleRate);
        return new byte[]{(byte) ((2 << 3) | (index >> 1)), (byte) (((index & 1) << 7) | (channels << 3))};
    }
}
