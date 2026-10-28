package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.PcmPackets;
import com.github.auties00.smalt.container.adts.AdtsDemuxer;
import com.github.auties00.smalt.container.ac3.Ac3Demuxer;
import com.github.auties00.smalt.container.mp4.Mp4Demuxer;
import com.github.auties00.smalt.container.subtitle.VobSubDemuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.testkit.Packets;
import com.github.auties00.smalt.testkit.validate.PsValidator;
import com.github.auties00.smalt.testkit.validate.Validators;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers writing MPEG program streams: every codec muxed then demuxed back packet for packet with the default, DVD,
 * MPEG-1, fixed-size and large-pack options, each output checked by the test kit's program stream validator with its
 * pacing checks, read by ffprobe with the same payloads and decoded by ffmpeg (linear PCM sample for sample), the
 * DVD-Video navigation packs and their addresses, the program stream map, streaming sinks, codec configuration
 * changes, and the rejected tracks, packets and options.
 */
@DisplayName("MPEG-PS muxing")
class PsMuxerTest {
    /** The tracks and packets of a source, in file order. */
    record Source(List<Track> tracks, List<Packet> packets) {
    }

    static Source source(Path file, Function<MediaSource, com.github.auties00.smalt.container.Demuxer> factory) {
        try (var input = MediaSource.of(file); var demuxer = factory.apply(input)) {
            return new Source(demuxer.tracks(), Packets.readAll(demuxer));
        }
    }

    static Source sample(String name) {
        return source(PsDemuxerTest.SAMPLES.resolve(name), PsDemuxer::new);
    }

    static byte[] mux(Source source, PsMuxerOptions options) {
        var sink = MediaSink.memory();
        try (var muxer = new PsMuxer(sink, options)) {
            source.tracks().forEach(muxer::addTrack);
            source.packets().forEach(muxer::writePacket);
            muxer.finish();
        }
        return sink.toByteArray();
    }

    /** Returns a source's packets ordered by decoding time, as an application writes them. */
    static Source interleaved(Source source) {
        var tracks = new HashMap<Integer, Track>();
        source.tracks().forEach(track -> tracks.put(track.id(), track));
        var packets = new ArrayList<>(source.packets());
        packets.sort(Comparator.comparingLong(packet -> tracks.get(packet.trackId()).timeBase()
                .toMicros(packet.dts() != Packet.NO_TIMESTAMP ? packet.dts() : packet.pts())));
        return new Source(source.tracks(), packets);
    }

    /**
     * Asserts that a demuxed file carries the source's packets: per track of the same codec, the same payloads, key
     * flags and presentation times relative to the source's earliest time, within a 90 kHz tick or a sample.
     */
    static void assertCarries(Source source, PsDemuxerTest.Read read, String what) {
        assertEquals(source.tracks().size(), read.tracks().size(), what + " tracks " + read.tracks());
        var sourceOrigin = Long.MAX_VALUE;
        for (var packet : source.packets()) {
            var track = source.tracks().stream().filter(candidate -> candidate.id() == packet.trackId()).findFirst()
                    .orElseThrow();
            sourceOrigin = Math.min(sourceOrigin, track.timeBase().toMicros(packet.pts()));
        }
        var readOrigin = Long.MAX_VALUE;
        for (var packet : read.packets()) {
            readOrigin = Math.min(readOrigin, read.track(packet.trackId()).timeBase().toMicros(packet.pts()));
        }
        for (var index = 0; index < source.tracks().size(); index++) {
            var original = source.tracks().get(index);
            var demuxed = read.tracks().stream().filter(track -> track.codec() == original.codec()
                            && read.tracks().stream().filter(other -> other.codec() == original.codec()).toList()
                            .indexOf(track) == sameCodecIndex(source.tracks(), original)).findFirst()
                    .orElseThrow(() -> new AssertionError(what + " lost the " + original.codec() + " track"));
            var expected = source.packets().stream().filter(packet -> packet.trackId() == original.id()).toList();
            var actual = read.of(demuxed.id());
            var name = what + " " + original.codec() + " track " + original.id();
            assertEquals(expected.size(), actual.size(), name + " packet count");
            var tolerance = demuxed instanceof AudioTrack audio ? 1_000_000L / audio.sampleRate() + 12 : 12;
            for (var packetIndex = 0; packetIndex < expected.size(); packetIndex++) {
                var want = expected.get(packetIndex);
                var got = actual.get(packetIndex);
                assertArrayEquals(want.data(), got.data(), name + " payload of packet " + packetIndex);
                assertEquals(want.keyframe(), got.keyframe(), name + " key flag of packet " + packetIndex);
                var wantMicros = original.timeBase().toMicros(want.pts()) - sourceOrigin;
                var gotMicros = demuxed.timeBase().toMicros(got.pts()) - readOrigin;
                assertTrue(Math.abs(wantMicros - gotMicros) <= tolerance, name + " pts of packet " + packetIndex
                        + ": " + gotMicros + " us, written " + wantMicros + " us");
            }
        }
    }

    private static int sameCodecIndex(List<Track> tracks, Track track) {
        return tracks.stream().filter(other -> other.codec() == track.codec()).toList().indexOf(track);
    }

    static PsValidator.Summary validated(String name, byte[] data) {
        return Validators.assertValidPs(name, data, true);
    }

    static Stream<Arguments> roundTrips() {
        var defaults = PsMuxerOptions.defaults();
        var dvd = PsMuxerOptions.forDvd();
        var mpeg1 = PsMuxerOptions.builder().syntax(PsMuxerOptions.Syntax.MPEG1).fixedPackSize(true).packBytes(2324)
                .build();
        var large = PsMuxerOptions.builder().packBytes(16_384).muxRate(20_000_000).build();
        var small = PsMuxerOptions.builder().packBytes(512).fixedPackSize(true).delayMicros(0).build();
        return Stream.of(
                Arguments.of("mpeg2-mp2.vob", "defaults", defaults),
                Arguments.of("mpeg2-mp2.vob", "dvd", dvd),
                Arguments.of("mpeg2-mp2.vob", "mpeg1", mpeg1),
                Arguments.of("mpeg2-mp2.vob", "small", small),
                Arguments.of("mpeg1.mpg", "defaults", defaults),
                Arguments.of("mpeg1.mpg", "mpeg1", mpeg1),
                Arguments.of("dvd.vob", "defaults", defaults),
                Arguments.of("dvd.vob", "dvd", dvd),
                Arguments.of("dvd.vob", "large", large),
                Arguments.of("dts.vob", "dvd", dvd),
                Arguments.of("dts.vob", "small", small),
                Arguments.of("lpcm24.vob", "dvd", dvd),
                Arguments.of("lpcm24.vob", "defaults", defaults),
                Arguments.of("h264.mpg", "defaults", defaults),
                Arguments.of("h264.mpg", "large", large));
    }

    @ParameterizedTest(name = "{0} with the {1} options")
    @MethodSource("roundTrips")
    @DisplayName("returns the packets it was given, in a valid stream")
    void roundTrip(String name, String label, PsMuxerOptions options) {
        var source = interleaved(sample(name));
        var data = mux(source, options);
        var summary = validated(name + "-" + label + ".mpg", data);
        assertEquals(options.syntax() == PsMuxerOptions.Syntax.MPEG1, summary.mpeg1(), "syntax");
        assertEquals(options.muxRate(), summary.muxRate(), "mux rate");
        if (options.fixedPackSize()) {
            assertEquals(java.util.Set.of(options.packBytes()), summary.packSizes(), "pack sizes");
            assertEquals(0, data.length % options.packBytes());
        } else {
            assertTrue(summary.packSizes().stream().allMatch(size -> size <= options.packBytes()), "pack sizes");
            assertTrue(summary.endCode(), "program end code");
        }
        assertEquals(options.navigationPacks(), summary.navigationPacks() > 0, "navigation packs");
        assertCarries(source, PsDemuxerTest.read(data), name + " " + label);
    }

    @ParameterizedTest(name = "{0} with the {1} options")
    @MethodSource("roundTrips")
    @DisplayName("writes streams ffprobe reads with the same payloads")
    void ffprobe(String name, String label, PsMuxerOptions options) {
        var source = interleaved(sample(name));
        var data = mux(source, options);
        var file = MuxSupport.save("ps-" + name + "-" + label + ".mpg", data);
        PsDemuxerTest.assertMatchesProbe(file);
        Oracle.assertFfmpegDecodes(file);
    }

    static Source tone(Codec codec, int rate, ChannelLayout layout, int bits) {
        var pcm = PcmPackets.tone(codec, rate, layout, rate / 2);
        var track = pcm.track();
        if (bits != track.bitsPerSample()) {
            track = AudioTrack.builder().id(track.id()).codec(codec).timeBase(track.timeBase()).sampleRate(rate)
                    .layout(layout).bitsPerSample(bits).build();
        }
        var packets = new ArrayList<Packet>();
        for (var packet : pcm.packets()) {
            var data = packet.data().clone();
            if (bits == 20) {
                // 20-bit samples in three bytes keep their low four bits zero
                for (var index = codec == Codec.PCM_S24LE ? 0 : 2; index < data.length; index += 3) {
                    data[index] &= (byte) 0xF0;
                }
            }
            packets.add(packet.withData(data));
        }
        return new Source(List.of(track), packets);
    }

    /** Returns 16-bit or 24-bit samples in big-endian order. */
    static byte[] bigEndian(Source source, Codec codec) {
        var out = new ByteArrayOutputStream();
        var width = codec == Codec.PCM_S16LE || codec == Codec.PCM_S16BE ? 2 : 3;
        for (var packet : source.packets()) {
            var data = packet.data();
            for (var index = 0; index < data.length; index += width) {
                for (var at = 0; at < width; at++) {
                    var little = codec == Codec.PCM_S16LE || codec == Codec.PCM_S24LE;
                    out.write(data[index + (little ? width - 1 - at : at)]);
                }
            }
        }
        return out.toByteArray();
    }

    @Nested
    @DisplayName("linear PCM")
    class Lpcm {
        @ParameterizedTest(name = "{0}, {1} Hz, {2} channels, {3} bits")
        @CsvSource({"PCM_S16LE, 48000, 2, 16", "PCM_S16BE, 96000, 1, 16", "PCM_S24LE, 48000, 2, 24",
                "PCM_S24BE, 48000, 6, 24", "PCM_S24BE, 48000, 2, 20", "PCM_S24LE, 96000, 4, 20",
                "PCM_S16LE, 44100, 2, 16", "PCM_S24BE, 32000, 3, 24"})
        @DisplayName("writes DVD linear PCM that reads back and that ffmpeg decodes to the same samples")
        void identity(Codec codec, int rate, int channels, int bits) {
            var layout = ChannelLayout.defaultFor(channels);
            var source = tone(codec, rate, layout, bits);
            var dvd = rate == 48_000 || rate == 96_000;
            var options = dvd ? PsMuxerOptions.forDvd() : PsMuxerOptions.defaults();
            var data = mux(source, options);
            validated("lpcm-" + codec + "-" + rate + "-" + channels + "-" + bits + ".vob", data);
            var read = PsDemuxerTest.read(data);
            var track = (AudioTrack) read.track(0x1A0);
            assertEquals(rate, track.sampleRate());
            assertEquals(channels, track.layout().orElseThrow().channels());
            assertEquals(bits, track.bitsPerSample());
            var expected = bigEndian(source, codec);
            assertArrayEquals(expected, PsDemuxerTest.concat(read.of(0x1A0)), "samples read back");
            var file = MuxSupport.save("lpcm-" + codec + "-" + rate + "-" + channels + "-" + bits + ".vob", data);
            var decoded = PsDemuxerTest.ffmpegDecode(file, bits == 16 ? "s16be" : "s24be", "0:a:0");
            assertArrayEquals(expected, decoded, "samples ffmpeg decodes");
        }

        @Test
        @DisplayName("points every PES packet at the first audio frame that starts in it")
        void frameHeaders() {
            var source = tone(Codec.PCM_S16BE, 48_000, ChannelLayout.STEREO, 16);
            var data = mux(source, PsMuxerOptions.forDvd());
            var frames = 0L;
            var bytesBefore = 0L;
            for (var at = 0; at + 4 <= data.length; at += 2048) {
                var position = at + 14;
                while (position + 6 < at + 2048) {
                    var id = data[position + 3] & 0xFF;
                    var length = (data[position + 4] & 0xFF) << 8 | (data[position + 5] & 0xFF);
                    if (id == 0xBD) {
                        var payload = position + 9 + (data[position + 8] & 0xFF);
                        assertEquals(0xA0, data[payload] & 0xFF);
                        var count = data[payload + 1] & 0xFF;
                        var pointer = (data[payload + 2] & 0xFF) << 8 | (data[payload + 3] & 0xFF);
                        var samples = position + 6 + length - (payload + 7);
                        var first = Math.ceilDiv(bytesBefore / 4, 80) * 80;
                        var end = (bytesBefore + samples) / 4;
                        var expectedCount = first < end ? (end - 1 - first) / 80 + 1 : 0;
                        assertEquals(expectedCount, count, "frames starting in the packet at " + position);
                        assertEquals(count == 0 ? 0 : 4 + (first - bytesBefore / 4) * 4, pointer, "pointer");
                        frames += count;
                        bytesBefore += samples;
                    }
                    position += 6 + length;
                }
            }
            assertEquals(Math.ceilDiv(24_000L, 80), frames, "every frame of 1/600 s is counted once");
        }
    }

    @Nested
    @DisplayName("codecs from other containers")
    class Codecs {
        @Test
        @DisplayName("carry AAC in ADTS frames, declared by the program stream map")
        void aac() {
            var source = source(Fixtures.ffmpeg("aac", List.of("-f", "lavfi", "-i",
                    "sine=frequency=440:sample_rate=48000:duration=0.6"), List.of("-c:a", "aac", "-b:a", "96k")),
                    AdtsDemuxer::new);
            var data = mux(source, PsMuxerOptions.defaults());
            var summary = validated("aac.mpg", data);
            assertEquals(Map.of(0xC0, 0x0F), summary.mapTypes(), "the map declares AAC");
            assertCarries(source, PsDemuxerTest.read(data), "aac");
            var file = MuxSupport.save("aac.mpg", data);
            assertEquals("aac", ContainerProbe.probe(file).streams().getFirst().codecName());
        }

        @Test
        @DisplayName("carry E-AC-3 in the HD DVD substreams")
        void eac3() {
            var source = source(Fixtures.ffmpeg("eac3", List.of("-f", "lavfi", "-i",
                    "sine=frequency=440:sample_rate=48000:duration=0.6"), List.of("-c:a", "eac3", "-b:a", "96k")),
                    Ac3Demuxer::new);
            var data = mux(source, PsMuxerOptions.defaults());
            validated("eac3.mpg", data);
            var read = PsDemuxerTest.read(data);
            assertEquals(Codec.EAC3, read.track(0x1C0).codec());
            assertCarries(source, read, "eac3");
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"mpeg4, MPEG4_PART2, 0x10", "libx265, HEVC, 0x24", "libx264, H264, 0x1B"})
        @DisplayName("carry MPEG-4 Part 2, HEVC and H.264 from MP4, declared by the program stream map")
        void mp4Video(String encoder, Codec codec, String type) {
            Oracle.assumeEncoder(encoder);
            var options = new ArrayList<>(List.of("-c:v", encoder, "-g", "6", "-bf", "2"));
            if (encoder.equals("libx265")) {
                options.addAll(List.of("-x265-params", "pools=1:frame-threads=1:log-level=error"));
            }
            var source = source(Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i",
                    "testsrc2=size=160x120:rate=25:duration=1", "-f", "lavfi", "-i",
                    "sine=frequency=440:sample_rate=48000:duration=1"), withAudio(options)), Mp4Demuxer::new);
            source = interleaved(source);
            var data = mux(source, PsMuxerOptions.builder().programStreamMap(true).build());
            var summary = validated(encoder + ".mpg", data);
            assertEquals(Integer.decode(type), summary.mapTypes().get(0xE0), "stream type");
            var read = PsDemuxerTest.read(data);
            assertEquals(codec, read.track(0xE0).codec());
            var video = source.tracks().stream().filter(track -> track.kind() == TrackKind.VIDEO).findFirst()
                    .orElseThrow();
            var written = source.packets().stream().filter(packet -> packet.trackId() == video.id()).toList();
            var packets = read.of(0xE0);
            assertEquals(written.size(), packets.size(), "access units");
            for (var index = 0; index < written.size(); index++) {
                assertEquals(written.get(index).keyframe(), packets.get(index).keyframe(), "key flag " + index);
            }
            var file = MuxSupport.save(encoder + ".mpg", data);
            Oracle.assertFfmpegDecodes(file);
            var probe = ContainerProbe.probe(file);
            var stream = probe.streams().stream().filter(candidate -> candidate.get("codec_type").equals("video"))
                    .findFirst().orElseThrow();
            assertEquals(written.size(), probe.packetsOf(stream.index()).size(), "ffprobe access units");
        }

        private static List<String> withAudio(List<String> video) {
            var options = new ArrayList<>(video);
            options.addAll(List.of("-c:a", "aac", "-b:a", "64k"));
            return options;
        }

        @Test
        @DisplayName("carry the subpictures of a VobSub file in a DVD-Video object")
        void subpictures() {
            var subtitles = source(Path.of("src", "test", "resources", "samples", "subtitle", "vobsub.sub"),
                    VobSubDemuxer::new);
            var video = sample("mpeg2-mp2.vob");
            var tracks = new ArrayList<Track>(video.tracks());
            tracks.addAll(subtitles.tracks());
            var packets = new ArrayList<>(video.packets());
            packets.addAll(subtitles.packets());
            var source = interleaved(new Source(tracks, packets));
            var data = mux(source, PsMuxerOptions.forDvd());
            validated("subpictures.vob", data);
            var read = PsDemuxerTest.read(data);
            assertCarries(source, read, "subpictures");
            for (var id : List.of(0x120, 0x121)) {
                for (var unit : read.of(id)) {
                    assertNotEquals(Packet.UNKNOWN_DURATION, unit.duration(), "duration from the stop command");
                }
            }
            var file = MuxSupport.save("subpictures.vob", data);
            var probe = ContainerProbe.probe(file);
            assertEquals(2, probe.streams().stream().filter(stream -> stream.codecName().equals("dvd_subtitle")).count());
        }
    }

    @Nested
    @DisplayName("DVD-Video objects")
    class Dvd {
        @Test
        @DisplayName("open every video object unit with a navigation pack that addresses it")
        void navigation() {
            var source = interleaved(sample("dvd.vob"));
            var data = mux(source, PsMuxerOptions.forDvd());
            assertEquals(0, data.length % 2048);
            var videoId = source.tracks().stream().filter(track -> track.kind() == TrackKind.VIDEO).findFirst()
                    .orElseThrow().id();
            var keys = source.packets().stream().filter(packet -> packet.trackId() == videoId && packet.keyframe())
                    .count();
            var navigation = new ArrayList<Integer>();
            for (var block = 0; block < data.length / 2048; block++) {
                var at = block * 2048;
                if ((data[at + 14 + 3] & 0xFF) == 0xBB && (data[at + 38 + 3] & 0xFF) == 0xBF) {
                    navigation.add(block);
                }
            }
            assertEquals(keys, navigation.size(), "one navigation pack per video key frame");
            assertEquals(0, navigation.getFirst(), "the first pack");
            var previousElapsed = -1;
            for (var index = 0; index < navigation.size(); index++) {
                var block = navigation.get(index);
                var at = block * 2048;
                var pci = at + 14 + 24 + 7;
                var dsi = at + 14 + 24 + 986 + 7;
                assertEquals(980, (data[at + 14 + 24 + 4] & 0xFF) << 8 | (data[at + 14 + 24 + 5] & 0xFF));
                assertEquals((long) block, u32(data, pci), "nv_pck_lbn of the presentation control information");
                assertEquals((long) block, u32(data, dsi + 4), "nv_pck_lbn of the data search information");
                var end = index + 1 < navigation.size() ? navigation.get(index + 1) : data.length / 2048;
                assertEquals(end - block - 1, u32(data, dsi + 8), "vobu_ea");
                var first = u32(data, dsi + 12);
                assertTrue(first > 0 && first < end - block, "vobu_1stref_ea " + first);
                assertEquals((data[at + 14 + 3] & 0xFF), 0xBB);
                var scr = scrOf(data, at) / 300;
                assertEquals(scr & 0xFFFFFFFFL, u32(data, dsi), "nv_pck_scr");
                var startPtm = u32(data, pci + 12);
                var endPtm = u32(data, pci + 16);
                assertTrue(endPtm > startPtm, "vobu_e_ptm after vobu_s_ptm");
                var elapsed = bcd(data[dsi + 28]) * 3600 + bcd(data[dsi + 29]) * 60 + bcd(data[dsi + 30]);
                assertTrue(elapsed >= previousElapsed, "c_eltm grows");
                previousElapsed = elapsed;
                if (index > 0) {
                    // a unit after the first opens at a video key frame
                    assertEquals(0xE0, data[(block + 1) * 2048 + 14 + 3] & 0xFF, "video after the navigation pack");
                }
            }
            var file = MuxSupport.save("navigation.vob", data);
            var probe = ContainerProbe.probe(file);
            assertTrue(probe.streams().stream().anyMatch(stream -> stream.codecName().equals("dvd_nav_packet")));
        }

        @Test
        @DisplayName("accept only the codecs and track counts of DVD-Video")
        void restrictions() {
            var options = PsMuxerOptions.forDvd();
            var h264 = VideoTrack.builder().id(1).codec(Codec.H264).timeBase(Rational.timeBaseOf(90_000)).width(16)
                    .height(16).codecConfig(new byte[]{1, 66, 0, 10, (byte) 0xFF, (byte) 0xE0, 0}).build();
            assertFalse(PsMuxer.supports(h264, options));
            assertThrows(IllegalArgumentException.class, () -> new PsMuxer(MediaSink.memory(), options).addTrack(h264));
            var pcm44 = AudioTrack.builder().id(2).codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(44_100))
                    .sampleRate(44_100).layout(ChannelLayout.STEREO).build();
            assertFalse(PsMuxer.supports(pcm44, options));
            assertTrue(PsMuxer.supports(pcm44, PsMuxerOptions.defaults()));
            var mpeg2 = VideoTrack.builder().id(3).codec(Codec.MPEG2_VIDEO).timeBase(Rational.timeBaseOf(90_000))
                    .width(16).height(16).build();
            var muxer = new PsMuxer(MediaSink.memory(), options);
            muxer.addTrack(mpeg2);
            assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(VideoTrack.builder().id(4)
                    .codec(Codec.MPEG2_VIDEO).timeBase(Rational.timeBaseOf(90_000)).width(16).height(16).build()));
            var mpeg1 = PsMuxerOptions.builder().syntax(PsMuxerOptions.Syntax.MPEG1).build();
            var ac3 = AudioTrack.builder().id(5).codec(Codec.AC3).timeBase(Rational.timeBaseOf(48_000))
                    .sampleRate(48_000).layout(ChannelLayout.STEREO).build();
            assertFalse(PsMuxer.supports(ac3, mpeg1));
            assertTrue(PsMuxer.supports(ac3, options));
        }

        private static long u32(byte[] data, int at) {
            return (data[at] & 0xFFL) << 24 | (data[at + 1] & 0xFFL) << 16 | (data[at + 2] & 0xFFL) << 8
                    | data[at + 3] & 0xFFL;
        }

        private static int bcd(byte value) {
            return (value >> 4 & 0x0F) * 10 + (value & 0x0F);
        }

        private static long scrOf(byte[] data, int at) {
            var b4 = data[at + 4] & 0xFF;
            var base = ((long) (b4 & 0x38) << 27) | ((long) (b4 & 0x03) << 28) | ((data[at + 5] & 0xFFL) << 20)
                    | ((long) (data[at + 6] & 0xF8) << 12) | ((long) (data[at + 6] & 0x03) << 13)
                    | ((data[at + 7] & 0xFFL) << 5) | ((data[at + 8] & 0xFFL) >> 3);
            var extension = (data[at + 8] & 0x03) << 7 | (data[at + 9] & 0xFF) >> 1;
            return base * 300 + extension;
        }
    }

    @Nested
    @DisplayName("program stream map")
    class StreamMap {
        @Test
        @DisplayName("is written only when a stream needs it, or when asked, with the languages of the tracks")
        void written() {
            var source = sample("mpeg2-mp2.vob");
            assertTrue(validated("no-map.mpg", mux(source, PsMuxerOptions.defaults())).mapTypes().isEmpty());
            var tracks = source.tracks().stream().map(track -> track instanceof VideoTrack video
                    ? video.toBuilder().language("deu").build() : track).toList();
            var named = new Source(tracks, source.packets());
            var data = mux(named, PsMuxerOptions.builder().programStreamMap(true).build());
            var summary = validated("map.mpg", data);
            assertEquals(Map.of(0xE0, 0x02, 0xC0, 0x03), summary.mapTypes());
            assertEquals("deu", PsDemuxerTest.read(data).track(0xE0).language().orElseThrow());
        }
    }

    @Test
    @DisplayName("writes the same bytes to a stream as to memory")
    void streaming() {
        var source = interleaved(sample("dvd.vob"));
        for (var options : List.of(PsMuxerOptions.defaults(), PsMuxerOptions.forDvd())) {
            var memory = MuxSupport.toMemory(source.tracks(), source.packets(), sink -> new PsMuxer(sink, options));
            var stream = MuxSupport.toStream(source.tracks(), source.packets(), sink -> new PsMuxer(sink, options));
            assertArrayEquals(memory, stream);
        }
    }

    @Test
    @DisplayName("is the muxer Containers opens for program streams")
    void containers() {
        try (var muxer = Containers.openMuxer(com.github.auties00.smalt.container.ContainerFormat.MPEG_PS,
                MediaSink.memory())) {
            assertTrue(muxer instanceof PsMuxer);
        }
        assertTrue(Containers.isMuxable(com.github.auties00.smalt.container.ContainerFormat.MPEG_PS, Codec.DTS));
        assertFalse(Containers.isMuxable(com.github.auties00.smalt.container.ContainerFormat.MPEG_PS, Codec.OPUS));
    }

    @Test
    @DisplayName("replaces the headers key frames get when the configuration changes")
    void configChange() {
        var source = sample("mpeg2-mp2.vob");
        var video = (VideoTrack) source.tracks().getFirst();
        var first = com.github.auties00.smalt.internal.codec.MpegVideoHeaders.find(video.codecConfig().orElseThrow());
        assertTrue(first != null);
        var keys = source.packets().stream().filter(packet -> packet.trackId() == video.id() && packet.keyframe())
                .toList();
        // strip the in-band sequence headers, so that the muxer adds the configuration's
        var stripped = new ArrayList<Packet>();
        for (var packet : source.packets()) {
            if (packet.trackId() == video.id() && packet.keyframe()) {
                var data = packet.data();
                var picture = 0;
                while (picture + 3 < data.length && !(data[picture] == 0 && data[picture + 1] == 0
                        && data[picture + 2] == 1 && (data[picture + 3] == 0 || (data[picture + 3] & 0xFF) == 0xB8))) {
                    picture++;
                }
                stripped.add(packet.withData(java.util.Arrays.copyOfRange(data, picture, data.length)));
            } else {
                stripped.add(packet);
            }
        }
        var data = mux(new Source(source.tracks(), stripped), PsMuxerOptions.defaults());
        var read = PsDemuxerTest.read(data);
        var packets = read.of(0xE0);
        var config = video.codecConfig().orElseThrow();
        assertEquals(keys.size(), packets.stream().filter(Packet::keyframe).count());
        for (var packet : packets) {
            if (packet.keyframe()) {
                assertArrayEquals(config, java.util.Arrays.copyOf(packet.data(), config.length), "headers");
            }
        }
        var ac3 = AudioTrack.builder().id(9).codec(Codec.AC3).timeBase(Rational.timeBaseOf(48_000)).sampleRate(48_000)
                .layout(ChannelLayout.STEREO).build();
        var muxer = new PsMuxer(MediaSink.memory());
        muxer.addTrack(ac3);
        assertThrows(MediaException.Unsupported.class, () -> muxer.writePacket(new Packet(9, new byte[8], 0, 0, 1536,
                java.util.Set.of(com.github.auties00.smalt.track.PacketFlag.KEY),
                PacketSideData.builder().newCodecConfig(new byte[]{1}).build())));
    }

    @Test
    @DisplayName("rejects tracks and packets it cannot write")
    void rejects() {
        var muxer = new PsMuxer(MediaSink.memory());
        assertThrows(IllegalStateException.class, () -> muxer.writePacket(new Packet(1, new byte[1], 0, 0, 1,
                java.util.Set.of(), PacketSideData.NONE)), "no track");
        var aac = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(48_000)).sampleRate(48_000)
                .layout(ChannelLayout.STEREO).build();
        assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(aac), "AAC without its configuration");
        var opus = AudioTrack.builder().id(2).codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48_000)).sampleRate(48_000)
                .layout(ChannelLayout.STEREO).build();
        assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(opus), "Opus");
        var pcm = AudioTrack.builder().id(3).codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(48_000))
                .sampleRate(48_000).layout(ChannelLayout.STEREO).build();
        muxer.addTrack(pcm);
        assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(pcm), "a used id");
        assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(3, new byte[3], 0, 0, 1,
                java.util.Set.of(), PacketSideData.NONE)), "a partial sample frame");
        assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(7, new byte[4], 0, 0, 1,
                java.util.Set.of(), PacketSideData.NONE)), "an undeclared track");
        assertThrows(IllegalArgumentException.class, () -> muxer.writePacket(new Packet(3, new byte[4],
                Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 1, java.util.Set.of(), PacketSideData.NONE)), "no time");
        muxer.writePacket(new Packet(3, new byte[4], 0, 0, 1, java.util.Set.of(), PacketSideData.NONE));
        assertThrows(IllegalStateException.class, () -> muxer.addTrack(AudioTrack.builder().id(4).codec(Codec.MP2)
                .timeBase(Rational.timeBaseOf(48_000)).sampleRate(48_000).layout(ChannelLayout.STEREO).build()));
        var many = new PsMuxer(MediaSink.memory());
        for (var index = 0; index < 8; index++) {
            many.addTrack(AudioTrack.builder().id(10 + index).codec(Codec.AC3).timeBase(Rational.timeBaseOf(48_000))
                    .sampleRate(48_000).layout(ChannelLayout.STEREO).build());
        }
        assertThrows(IllegalArgumentException.class, () -> many.addTrack(AudioTrack.builder().id(30).codec(Codec.AC3)
                .timeBase(Rational.timeBaseOf(48_000)).sampleRate(48_000).layout(ChannelLayout.STEREO).build()),
                "a ninth AC-3 stream");
        muxer.finish();
        assertThrows(IllegalStateException.class, muxer::finish);
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("hold their presets and round trip through their builder")
        void presets() {
            var defaults = PsMuxerOptions.defaults();
            assertEquals(PsMuxerOptions.Syntax.MPEG2, defaults.syntax());
            assertEquals(2048, defaults.packBytes());
            assertFalse(defaults.fixedPackSize());
            assertEquals(10_080_000, defaults.muxRate());
            assertEquals(500_000, defaults.delayMicros());
            assertFalse(defaults.navigationPacks());
            assertFalse(defaults.programStreamMap());
            var dvd = PsMuxerOptions.forDvd();
            assertTrue(dvd.fixedPackSize());
            assertTrue(dvd.navigationPacks());
            assertEquals(dvd, dvd.toBuilder().build());
            assertEquals(dvd.hashCode(), dvd.toBuilder().build().hashCode());
            assertNotEquals(defaults, dvd);
            assertTrue(dvd.toString().contains("navigationPacks=true"));
            var custom = defaults.toBuilder().syntax(PsMuxerOptions.Syntax.MPEG1).packBytes(2324).fixedPackSize(true)
                    .muxRate(1_411_200).delayMicros(0).build();
            assertEquals(custom, custom.toBuilder().build());
            assertEquals(2324, custom.packBytes());
            assertEquals(1_411_200, custom.muxRate());
        }

        @Test
        @DisplayName("reject values out of range and navigation packs without DVD packs")
        void validation() {
            var builder = PsMuxerOptions.builder();
            assertThrows(IllegalArgumentException.class, () -> builder.packBytes(255).build());
            assertThrows(IllegalArgumentException.class, () -> PsMuxerOptions.builder().packBytes(65_537).build());
            assertThrows(IllegalArgumentException.class, () -> PsMuxerOptions.builder().muxRate(1000).build());
            assertThrows(IllegalArgumentException.class, () -> PsMuxerOptions.builder().muxRate(0).build());
            assertThrows(IllegalArgumentException.class, () -> PsMuxerOptions.builder().delayMicros(-1).build());
            assertThrows(IllegalArgumentException.class, () -> PsMuxerOptions.builder().delayMicros(10_000_001).build());
            assertThrows(IllegalArgumentException.class, () -> PsMuxerOptions.builder().navigationPacks(true).build());
            assertThrows(IllegalArgumentException.class, () -> PsMuxerOptions.forDvd().toBuilder()
                    .syntax(PsMuxerOptions.Syntax.MPEG1).build());
            assertThrows(IllegalArgumentException.class, () -> PsMuxerOptions.forDvd().toBuilder().packBytes(4096)
                    .build());
            assertThrows(NullPointerException.class, () -> PsMuxerOptions.builder().syntax(null));
            assertThrows(IllegalArgumentException.class, () -> PsMuxerOptions.forDvd().toBuilder().programStreamMap(true)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> PsMuxerOptions.builder()
                    .syntax(PsMuxerOptions.Syntax.MPEG1).programStreamMap(true).build());
            assertTrue(PsMuxerOptions.builder().programStreamMap(true).build().programStreamMap());
        }
    }

}
