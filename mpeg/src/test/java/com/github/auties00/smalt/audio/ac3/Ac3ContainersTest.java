package com.github.auties00.smalt.audio.ac3;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.Muxer;
import com.github.auties00.smalt.container.ac3.Ac3Demuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Carries AC-3 and E-AC-3 through every container that stores them: raw {@code .ac3} and
 * {@code .ec3} streams, MP4 ({@code ac-3}/{@code dac3}, {@code ec-3}/{@code dec3}), Matroska and
 * MPEG-TS. Smalt's own output, from both encoders, is read back by Smalt and checked by ffmpeg;
 * ffmpeg's remux of the pinned 7.1 Atmos stream is read by Smalt.
 */
class Ac3ContainersTest {
    @TempDir
    Path directory;

    private Path write(String name, byte[] data) {
        try {
            var path = directory.resolve(name);
            Files.write(path, data);
            return path;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static byte[] read(Path path) {
        return Ac3TestSupport.read(path);
    }

    static AudioTrack track(Codec codec, int sampleRate, ChannelLayout layout) {
        return AudioTrack.builder().id(1).codec(codec).timeBase(Rational.timeBaseOf(sampleRate)).sampleRate(sampleRate)
                .layout(layout).build();
    }

    static List<Packet> encoded(ChannelLayout layout) {
        var packets = Ac3EncoderTest.encode(Ac3EncoderOptions.of(48000, layout).toBuilder().bitrate(384000).build(),
                Ac3EncoderTest.signal(layout, 48000, 1));
        var tracked = new ArrayList<Packet>();
        for (var packet : packets) {
            tracked.add(new Packet(1, packet.data(), packet.pts(), packet.dts(), packet.duration(), true));
        }
        return tracked;
    }

    static List<Packet> enhanced(ChannelLayout layout, int blocks) {
        var packets = Eac3EncoderTest.encode(Eac3EncoderOptions.of(48000, layout).toBuilder().blocksPerFrame(blocks)
                .bitrate(blocks == 6 ? 384000 : 768000).build(), Ac3EncoderTest.signal(layout, 48000, 1));
        var tracked = new ArrayList<Packet>();
        for (var packet : packets) {
            tracked.add(new Packet(1, packet.data(), packet.pts(), packet.dts(), packet.duration(), true));
        }
        return tracked;
    }

    static List<Packet> units(byte[] stream) {
        var packets = new ArrayList<Packet>();
        var timestamp = 0L;
        for (var unit : Ac3TestSupport.units(stream)) {
            var samples = Ac3TestSupport.samples(unit);
            packets.add(new Packet(1, unit, timestamp, timestamp, samples, true));
            timestamp += samples;
        }
        return packets;
    }

    static byte[] mux(AudioTrack track, List<Packet> packets, Function<MediaSink, Muxer> factory) {
        var sink = MediaSink.memory();
        try (var muxer = factory.apply(sink)) {
            muxer.addTrack(track);
            for (var packet : packets) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    static List<Packet> readAll(MediaSource source) {
        var packets = new ArrayList<Packet>();
        try (var demuxer = Containers.openDemuxer(source)) {
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
        }
        return packets;
    }

    static AudioTrack firstTrack(MediaSource source) {
        try (var demuxer = Containers.openDemuxer(source)) {
            return (AudioTrack) demuxer.tracks().getFirst();
        }
    }

    static void assertSamePayloads(List<Packet> expected, List<Packet> actual) {
        assertEquals(expected.size(), actual.size());
        for (var index = 0; index < expected.size(); index++) {
            assertArrayEquals(expected.get(index).data(), actual.get(index).data(), "packet " + index);
        }
    }

    /** Returns num_dep_sub of the first independent substream of the dec3 box of an MP4 file (ETSI TS 102 366 F.6). */
    static int dependentSubstreams(Path file) {
        try {
            var bytes = Files.readAllBytes(file);
            for (var i = 4; i + 9 < bytes.length; i++) {
                if (bytes[i] == 'd' && bytes[i + 1] == 'e' && bytes[i + 2] == 'c' && bytes[i + 3] == '3') {
                    // data_rate(13) num_ind_sub(3), fscod(2) bsid(5) reserved(1) asvc(1) bsmod(3) acmod(3)
                    // lfeon(1), reserved(3) num_dep_sub(4) ...
                    return (bytes[i + 8] >> 1) & 0xF;
                }
            }
            throw new AssertionError("no dec3 box in " + file);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @Nested
    @DisplayName("raw elementary streams")
    class Raw {
        @Test
        @DisplayName("write an .ac3 file that is detected, demuxed back and decoded by ffmpeg")
        void ac3() {
            var layout = ChannelLayout.SURROUND_5_1;
            var packets = encoded(layout);
            var file = mux(track(Codec.AC3, 48000, layout), packets, sink -> Containers.openMuxer(ContainerFormat.AC3, sink));
            assertEquals(Optional.of(ContainerFormat.AC3), Containers.detect(MediaSource.of(file)));
            var track = firstTrack(MediaSource.of(file));
            assertEquals(Codec.AC3, track.codec());
            assertEquals(48000, track.sampleRate());
            assertEquals(6, track.layout().orElseThrow().channels());
            assertEquals(packets.size() * 1536L, track.duration());
            assertSamePayloads(packets, readAll(MediaSource.of(file)));
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var path = write("out.ac3", file);
                Oracle.assertDecodesCleanly(path);
                var probe = Oracle.probe(path);
                assertTrue(probe.contains("\"ac3\"") && probe.contains("\"channels\": 6"), probe);
            }
        }

        @ParameterizedTest(name = "{0} blocks per frame")
        @ValueSource(ints = {1, 6})
        @DisplayName("write an .ec3 file from the E-AC-3 encoder that is detected, demuxed back and decoded by ffmpeg")
        void encodedEac3(int blocks) {
            var layout = ChannelLayout.SURROUND_5_1;
            var packets = enhanced(layout, blocks);
            var file = mux(track(Codec.EAC3, 48000, layout), packets, sink -> Containers.openMuxer(ContainerFormat.AC3, sink));
            assertEquals(Optional.of(ContainerFormat.AC3), Containers.detect(MediaSource.of(file)));
            var track = firstTrack(MediaSource.of(file));
            assertEquals(Codec.EAC3, track.codec());
            assertEquals(48000, track.sampleRate());
            assertEquals(6, track.layout().orElseThrow().channels());
            assertEquals(packets.size() * 256L * blocks, track.duration());
            assertSamePayloads(packets, readAll(MediaSource.of(file)));
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var path = write("out.ec3", file);
                Oracle.assertDecodesCleanly(path);
                var probe = Oracle.probe(path);
                assertTrue(probe.contains("\"eac3\"") && probe.contains("\"channels\": 6"), probe);
            }
        }

        @Test
        @DisplayName("demux the 7.1 E-AC-3 stream into access units of its core and dependent substream")
        void eac3() {
            var stream = read(Ac3TestSupport.SAMPLES.get(7).fetch());
            var source = MediaSource.of(stream);
            assertEquals(Optional.of(ContainerFormat.AC3), Containers.detect(source));
            var track = firstTrack(MediaSource.of(stream));
            assertEquals(Codec.EAC3, track.codec());
            assertEquals(ChannelLayout.SURROUND_7_1, track.layout().orElseThrow());
            var expected = units(stream);
            var packets = readAll(MediaSource.of(stream));
            assertSamePayloads(expected, packets);
            assertEquals(expected.getLast().pts(), packets.getLast().pts());
        }

        @Test
        @DisplayName("skip garbage before the first frame and between frames")
        void resynchronizes() {
            var stream = read(Ac3TestSupport.SAMPLES.get(0).fetch());
            var units = Ac3TestSupport.units(stream);
            var damaged = new java.io.ByteArrayOutputStream();
            damaged.writeBytes(new byte[]{1, 2, 3, 0x0B, 0x77, 9, 9});
            for (var i = 0; i < units.size(); i++) {
                damaged.writeBytes(units.get(i));
                if (i == 3) {
                    damaged.writeBytes(new byte[]{0x0B, 0x77, 0, 0, 0});
                }
            }
            var packets = new ArrayList<Packet>();
            try (var demuxer = new Ac3Demuxer(MediaSource.of(damaged.toByteArray()))) {
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    packets.add(packet);
                }
            }
            assertEquals(units.size(), packets.size());
            assertArrayEquals(units.get(4), packets.get(4).data());
        }

        @ParameterizedTest(name = "to {0} us")
        @ValueSource(longs = {0, 500_000, 1_234_567, 3_000_000})
        @DisplayName("seek to the access unit holding the target")
        void seek(long micros) {
            var stream = read(Ac3TestSupport.SAMPLES.get(0).fetch());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(stream))) {
                demuxer.seek(micros);
                var packet = demuxer.read();
                var unit = micros * 48000 / 1_000_000 / 1536;
                assertEquals(unit * 1536, packet.pts());
                assertArrayEquals(Ac3TestSupport.units(stream).get((int) unit), packet.data());
            }
        }

        @Test
        @DisplayName("probe the Atmos flag, dialogue level, layout and bit rate")
        void probe() {
            var info = Media.probe(MediaSource.of(read(Ac3TestSupport.SAMPLES.get(7).fetch())));
            assertEquals(ContainerFormat.AC3, info.container().orElseThrow());
            var audio = info.audioStreams().getFirst();
            assertEquals(Codec.EAC3, audio.codec());
            assertEquals("Dolby Digital Plus + Dolby Atmos", audio.profile().orElseThrow());
            assertEquals(8, audio.channels());
            var level = audio.dialogueLevel().orElseThrow();
            assertTrue(level >= -31 && level <= -1, "dialogue level " + level);
            assertTrue(audio.bitrate().orElseThrow() > 0);
            var plain = Media.probe(MediaSource.of(read(Ac3TestSupport.SAMPLES.get(1).fetch()))).audioStreams().getFirst();
            assertTrue(plain.profile().isEmpty());
            assertEquals(6, plain.channels());
            assertEquals(448000, plain.bitrate().orElseThrow());
        }
    }

    @Nested
    @DisplayName("MP4")
    class Mp4 {
        @ParameterizedTest(name = "fragmented={0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("write ac-3 with a dac3 box describing the stream")
        void ac3(boolean fragmented) {
            var layout = ChannelLayout.SURROUND_5_1;
            var packets = encoded(layout);
            Function<MediaSink, Muxer> factory = fragmented ? FragmentedMp4Muxer::new : Mp4Muxer::new;
            var file = mux(track(Codec.AC3, 48000, layout), packets, factory);
            var track = firstTrack(MediaSource.of(file));
            assertEquals(Codec.AC3, track.codec());
            assertEquals(6, track.layout().orElseThrow().channels());
            assertTrue(track.layout().orElseThrow().speakers().contains(com.github.auties00.smalt.audio.Speaker.LOW_FREQUENCY));
            assertSamePayloads(packets, readAll(MediaSource.of(file)));
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var path = write("out.mp4", file);
                Oracle.assertDecodesCleanly(path);
                var probe = Oracle.probe(path);
                assertTrue(probe.contains("\"ac3\"") && probe.contains("\"channels\": 6"), probe);
            }
        }

        @ParameterizedTest(name = "fragmented={0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("write the E-AC-3 encoder's frames as ec-3 with a dec3 box describing them")
        void encodedEac3(boolean fragmented) {
            var layout = ChannelLayout.SURROUND_5_1;
            var packets = enhanced(layout, 6);
            Function<MediaSink, Muxer> factory = fragmented ? FragmentedMp4Muxer::new : Mp4Muxer::new;
            var file = mux(track(Codec.EAC3, 48000, layout), packets, factory);
            var track = firstTrack(MediaSource.of(file));
            assertEquals(Codec.EAC3, track.codec());
            assertEquals(ChannelLayout.SURROUND_5_1.channels(), track.layout().orElseThrow().channels());
            assertSamePayloads(packets, readAll(MediaSource.of(file)));
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var path = write("out.mp4", file);
                Oracle.assertDecodesCleanly(path);
                var probe = Oracle.probe(path);
                assertTrue(probe.contains("\"eac3\"") && probe.contains("\"channels\": 6"), probe);
                assertTrue(probe.contains("\"bit_rate\": \"38"), probe);
            }
        }

        @ParameterizedTest(name = "fragmented={0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("write the E-AC-3 encoder's 7.1 access units as ec-3 with a dec3 box listing the dependent substream")
        void encoded71(boolean fragmented) {
            var layout = ChannelLayout.SURROUND_7_1;
            var packets = new ArrayList<Packet>();
            for (var packet : Eac3EncoderTest.encode(Eac3EncoderOptions.of(48000, layout),
                    Ac3EncoderTest.signal(layout, 48000, 1))) {
                packets.add(new Packet(1, packet.data(), packet.pts(), packet.dts(), packet.duration(), true));
            }
            Function<MediaSink, Muxer> factory = fragmented ? FragmentedMp4Muxer::new : Mp4Muxer::new;
            var file = mux(track(Codec.EAC3, 48000, layout), packets, factory);
            var track = firstTrack(MediaSource.of(file));
            assertEquals(Codec.EAC3, track.codec());
            assertEquals(layout, track.layout().orElseThrow());
            assertSamePayloads(packets, readAll(MediaSource.of(file)));
            Oracle.assume(Oracle.Tool.FFMPEG);
            var path = write("out71.mp4", file);
            Oracle.assertDecodesCleanly(path);
            var probe = Oracle.probe(path);
            assertTrue(probe.contains("\"channels\": 8") && probe.contains("\"channel_layout\": \"7.1\""), probe);
        }

        @Test
        @DisplayName("write ec-3 with a dec3 box carrying the dependent substream and the Atmos extension")
        void eac3() {
            var stream = read(Ac3TestSupport.SAMPLES.get(7).fetch());
            var packets = units(stream);
            var file = mux(track(Codec.EAC3, 48000, ChannelLayout.SURROUND_7_1), packets, Mp4Muxer::new);
            var track = firstTrack(MediaSource.of(file));
            assertEquals(Codec.EAC3, track.codec());
            assertEquals(ChannelLayout.SURROUND_7_1, track.layout().orElseThrow());
            assertSamePayloads(packets, readAll(MediaSource.of(file)));
            Oracle.assume(Oracle.Tool.FFMPEG);
            var path = write("out.mp4", file);
            var probe = Oracle.probe(path);
            assertTrue(probe.contains("\"eac3\"") && probe.contains("\"channels\": 8"), probe);
            assertTrue(probe.contains("Dolby Atmos"), probe);
        }

        @Test
        @DisplayName("read the stream ffmpeg remuxes, as its dec3 box describes it, and decode all of its channels")
        void foreign() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var input = Ac3TestSupport.SAMPLES.get(7).fetch();
            var output = directory.resolve("remuxed.mp4");
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-y", "-i", input.toString(), "-c", "copy",
                    output.toString()));
            // ffmpeg 7.1.1 and earlier declare num_dep_sub = 0 for an AC-3 core with an E-AC-3
            // dependent substream, so the box describes the 5.1 core; 7.1.2 declares the dependent
            // substream and its channels. Either way the frames decode to 7.1.
            var track = firstTrack(MediaSource.of(output));
            assertEquals(Codec.EAC3, track.codec());
            assertEquals(dependentSubstreams(output) == 0 ? 6 : 8, track.layout().orElseThrow().channels());
            var packets = readAll(MediaSource.of(output));
            assertSamePayloads(units(read(input)), packets);
            try (var decoder = AudioDecoders.create(track)) {
                var channels = new int[1];
                decoder.decode(packets.getFirst(), buffer -> channels[0] = buffer.channels());
                assertEquals(8, channels[0]);
                assertEquals(8, decoder.layout().channels());
            }
        }
    }

    @Nested
    @DisplayName("Matroska and MPEG-TS")
    class Others {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"mkv", "ts"})
        @DisplayName("carry AC-3 that Smalt reads back and ffmpeg decodes")
        void ac3(String extension) {
            var layout = ChannelLayout.STEREO;
            var packets = encoded(layout);
            Function<MediaSink, Muxer> factory = extension.equals("mkv") ? MatroskaMuxer::new : TsMuxer::new;
            var file = mux(track(Codec.AC3, 48000, layout), packets, factory);
            var track = firstTrack(MediaSource.of(file));
            assertEquals(Codec.AC3, track.codec());
            assertSamePayloads(packets, readAll(MediaSource.of(file)));
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                Oracle.assertDecodesCleanly(write("out." + extension, file));
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"mkv", "ts"})
        @DisplayName("carry the E-AC-3 encoder's frames that Smalt reads back and ffmpeg decodes")
        void encodedEac3(String extension) {
            var layout = ChannelLayout.STEREO;
            var packets = enhanced(layout, 3);
            Function<MediaSink, Muxer> factory = extension.equals("mkv") ? MatroskaMuxer::new : TsMuxer::new;
            var file = mux(track(Codec.EAC3, 48000, layout), packets, factory);
            var track = firstTrack(MediaSource.of(file));
            assertEquals(Codec.EAC3, track.codec());
            var read = readAll(MediaSource.of(file));
            var expected = new java.io.ByteArrayOutputStream();
            packets.forEach(packet -> expected.writeBytes(packet.data()));
            var joined = new java.io.ByteArrayOutputStream();
            read.forEach(packet -> joined.writeBytes(packet.data()));
            assertArrayEquals(expected.toByteArray(), joined.toByteArray());
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                Oracle.assertDecodesCleanly(write("out." + extension, file));
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"mkv", "ts"})
        @DisplayName("carry the 7.1 E-AC-3 access units unchanged")
        void eac3(String extension) {
            var stream = read(Ac3TestSupport.SAMPLES.get(7).fetch());
            var packets = units(stream);
            Function<MediaSink, Muxer> factory = extension.equals("mkv") ? MatroskaMuxer::new : TsMuxer::new;
            var file = mux(track(Codec.EAC3, 48000, ChannelLayout.SURROUND_7_1), packets, factory);
            assertEquals(Codec.EAC3, firstTrack(MediaSource.of(file)).codec());
            var read = readAll(MediaSource.of(file));
            var joined = new java.io.ByteArrayOutputStream();
            read.forEach(packet -> joined.writeBytes(packet.data()));
            assertArrayEquals(stream, joined.toByteArray());
        }
    }
}
