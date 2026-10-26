package com.github.auties00.smalt.container;

import com.github.auties00.smalt.Media;
import com.github.auties00.smalt.container.matroska.MatroskaMuxer;
import com.github.auties00.smalt.container.matroska.MatroskaMuxerOptions;
import com.github.auties00.smalt.container.mp4.FragmentedMp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.container.mp4.Mp4Remuxer;
import com.github.auties00.smalt.container.ts.TsMuxer;
import com.github.auties00.smalt.image.ChromaSiting;
import com.github.auties00.smalt.internal.codec.DolbyVisionConfigs;
import com.github.auties00.smalt.internal.nal.DolbyVisionRpu;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.DolbyVisionRpus;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DolbyVision;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers Dolby Vision signaling through the containers: the configuration the MP4 ({@code dvcC}, {@code dvvC},
 * {@code dvwC} and the {@code dvh1}, {@code dva1}, {@code dav1} sample entries), Matroska
 * ({@code BlockAdditionMapping}) and MPEG-TS (DOVI descriptor) demuxers report for the samples of
 * {@code make-dolby-vision-samples.py}, its survival with the in-band RPUs through every Smalt muxer and chains of
 * them, ffprobe reading the configuration Smalt writes, ffmpeg's copies read back, the probe report, and the
 * H.264 and AV1 bindings.
 */
@DisplayName("Dolby Vision signaling")
class DolbyVisionContainerTest {
    private static final DolbyVision P84 = DolbyVision.of(8, 1, 4);

    private static final DolbyVision P5 = DolbyVision.of(5, 1, 0);

    private static byte[] sample(String name) {
        try (var in = DolbyVisionContainerTest.class.getResourceAsStream("/samples/" + name)) {
            return in.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static VideoTrack video(ContainerProbe.Demuxed demuxed) {
        return demuxed.tracks().stream().filter(VideoTrack.class::isInstance).map(VideoTrack.class::cast)
                .findFirst().orElseThrow();
    }

    private static List<byte[]> rpus(ContainerProbe.Demuxed demuxed) {
        var result = new ArrayList<byte[]>();
        for (var packet : demuxed.packetsOf(video(demuxed))) {
            var nals = DolbyVisionRpus.rpuNals(packet.data());
            assertEquals(1, nals.size(), "RPU NAL units of packet " + packet.dts());
            result.add(nals.getFirst());
        }
        return result;
    }

    private static void assertSameRpus(List<byte[]> expected, List<byte[]> actual, String what) {
        assertEquals(expected.size(), actual.size(), what + ": packets");
        for (var index = 0; index < expected.size(); index++) {
            assertArrayEquals(expected.get(index), actual.get(index), what + ": RPU of packet " + index);
        }
    }

    /** Returns the DOVI configuration ffprobe reports for the first video stream, as "profile.level.compatibility". */
    private static String probedConfiguration(Path file) {
        var json = HdrFixtures.ffprobe(file, "-show_streams", "-select_streams", "v:0");
        assertTrue(json.contains("\"side_data_type\": \"DOVI configuration record\""), json);
        assertEquals(1, field(json, "rpu_present_flag"), json);
        assertEquals(0, field(json, "el_present_flag"), json);
        assertEquals(1, field(json, "bl_present_flag"), json);
        return field(json, "dv_profile") + "." + field(json, "dv_level") + "."
                + field(json, "dv_bl_signal_compatibility_id");
    }

    private static int field(String json, String key) {
        var matcher = Pattern.compile("\"" + key + "\": (\\d+)").matcher(json);
        assertTrue(matcher.find(), "no " + key + " in " + json);
        return Integer.parseInt(matcher.group(1));
    }

    private static String fourCc(byte[] file) {
        var stsd = HdrFixtures.boxPayload(file, "stsd");
        return new String(stsd, 12, 4, StandardCharsets.US_ASCII);
    }

    private static List<Track> withDolbyVision(List<Track> tracks, DolbyVision configuration) {
        return tracks.stream().map(track -> track instanceof VideoTrack video
                ? (Track) video.toBuilder().dolbyVision(configuration).build() : track).toList();
    }

    @Nested
    @DisplayName("samples")
    class Samples {
        static Stream<Arguments> samples() {
            return Stream.of(Arguments.of("mp4/dolby-vision-p84.mp4", P84, 8),
                    Arguments.of("mp4/dolby-vision-p5.mp4", P5, 5),
                    Arguments.of("matroska/dolby-vision-p84.mkv", P84, 8),
                    Arguments.of("ts/dolby-vision-p84.ts", P84, 8));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("samples")
        @DisplayName("report the base codec, the configuration and one RPU per packet, and probe the configuration")
        void read(String name, DolbyVision expected, int rpuProfile) {
            var data = sample(name);
            var demuxed = MuxSupport.demux(data);
            var track = video(demuxed);
            assertEquals(Codec.HEVC, track.codec());
            assertEquals(expected, track.dolbyVision().orElseThrow());
            var rpus = rpus(demuxed);
            assertEquals(6, rpus.size());
            for (var rpu : rpus) {
                assertEquals(rpuProfile, DolbyVisionRpu.parseHevcNal(rpu, 0, rpu.length).profile());
            }
            var probed = Media.probe(MediaSource.of(data)).videoStreams().getFirst();
            assertEquals(expected, probed.dolbyVision().orElseThrow());
            assertEquals(Codec.HEVC, probed.codec());
            assertTrue(probed.toString().contains("dolbyVision="), probed.toString());
        }

        @Test
        @DisplayName("read the same RPUs from the MP4 sample and ffmpeg's Matroska and MPEG-TS copies of it")
        void sameRpus() {
            var source = rpus(MuxSupport.demux(sample("mp4/dolby-vision-p84.mp4")));
            assertSameRpus(source, rpus(MuxSupport.demux(sample("matroska/dolby-vision-p84.mkv"))), "matroska");
            assertSameRpus(source, rpus(MuxSupport.demux(sample("ts/dolby-vision-p84.ts"))), "ts");
        }
    }

    @Nested
    @DisplayName("muxers")
    class Muxers {
        static Stream<Arguments> muxers() {
            return Stream.of(
                    Arguments.of("mp4", (Function<MediaSink, Muxer>) Mp4Muxer::new),
                    Arguments.of("fmp4", (Function<MediaSink, Muxer>) FragmentedMp4Muxer::new),
                    Arguments.of("mkv", (Function<MediaSink, Muxer>) sink -> new MatroskaMuxer(sink,
                            MatroskaMuxerOptions.matroska())),
                    Arguments.of("ts", (Function<MediaSink, Muxer>) TsMuxer::new));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("muxers")
        @DisplayName("keep the configuration and the RPUs, which Smalt and ffprobe read back")
        void keep(String extension, Function<MediaSink, Muxer> factory) {
            for (var name : List.of("mp4/dolby-vision-p84.mp4", "mp4/dolby-vision-p5.mp4")) {
                var source = MuxSupport.demux(sample(name));
                var configuration = video(source).dolbyVision().orElseThrow();
                var output = MuxSupport.toMemory(source.tracks(), source.order(), factory);
                var result = MuxSupport.demux(output);
                assertEquals(configuration, video(result).dolbyVision().orElseThrow(), name);
                assertSameRpus(rpus(source), rpus(result), name + " to " + extension);
                var file = MuxSupport.save("dolby-vision-" + configuration.profile() + "." + extension, output);
                assertEquals(configuration.profile() + "." + configuration.level() + "."
                        + configuration.baseLayerCompatibility(), probedConfiguration(file));
            }
        }

        @Test
        @DisplayName("store a backward compatible stream in hvc1 with dvvC and profile 5 in dvh1 with dvcC")
        void sampleEntries() {
            var p84 = MuxSupport.demux(sample("mp4/dolby-vision-p84.mp4"));
            var compatible = MuxSupport.toMemory(p84.tracks(), p84.order(), Mp4Muxer::new);
            assertEquals("hvc1", fourCc(compatible));
            assertArrayEquals(DolbyVisionConfigs.record(P84), HdrFixtures.boxPayload(compatible, "dvvC"));
            assertNull(HdrFixtures.boxPayload(compatible, "dvcC"));
            var p5 = MuxSupport.demux(sample("mp4/dolby-vision-p5.mp4"));
            var incompatible = MuxSupport.toMemory(p5.tracks(), p5.order(), Mp4Muxer::new);
            assertEquals("dvh1", fourCc(incompatible));
            assertArrayEquals(DolbyVisionConfigs.record(P5), HdrFixtures.boxPayload(incompatible, "dvcC"));
            assertNull(HdrFixtures.boxPayload(incompatible, "dvvC"));
            var plain = MuxSupport.toMemory(p5.tracks().stream().map(track -> track instanceof VideoTrack video
                    ? (Track) video.toBuilder().noDolbyVision().build() : track).toList(), p5.order(), Mp4Muxer::new);
            assertEquals("hvc1", fourCc(plain));
            assertNull(HdrFixtures.boxPayload(plain, "dvcC"));
            assertFalse(video(MuxSupport.demux(plain)).dolbyVision().isPresent());
        }

        @Test
        @DisplayName("keep the configuration and the RPUs through MP4, Matroska, MPEG-TS and back to MP4")
        void chain() {
            for (var name : List.of("mp4/dolby-vision-p84.mp4", "mp4/dolby-vision-p5.mp4")) {
                var source = MuxSupport.demux(sample(name));
                var expected = video(source).dolbyVision().orElseThrow();
                var mkv = MuxSupport.demux(MuxSupport.toMemory(source.tracks(), source.order(),
                        sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.matroska())));
                var ts = MuxSupport.demux(MuxSupport.toMemory(mkv.tracks(), mkv.order(), TsMuxer::new));
                var mp4 = MuxSupport.toMemory(ts.tracks(), ts.order(), Mp4Muxer::new);
                var back = MuxSupport.demux(mp4);
                assertEquals(expected, video(back).dolbyVision().orElseThrow(), name);
                assertSameRpus(rpus(source), rpus(back), name);
                var sink = MediaSink.memory();
                Mp4Remuxer.remux(MediaSource.of(MuxSupport.toMemory(mkv.tracks(), mkv.order(),
                        output -> new MatroskaMuxer(output, MatroskaMuxerOptions.matroska()))), sink);
                assertEquals(expected, video(MuxSupport.demux(sink.toByteArray())).dolbyVision().orElseThrow());
            }
        }

        @Test
        @DisplayName("name the PID of the base layer in the MPEG-TS descriptor of an enhancement layer track")
        void dependencyPid() {
            var source = MuxSupport.demux(sample("mp4/dolby-vision-p84.mp4"));
            var base = video(source);
            var layer = new DolbyVision(1, 0, 7, 6, true, true, false, 6, 0);
            var enhancement = base.toBuilder().id(base.id() + 1).dolbyVision(layer).build();
            var packets = new ArrayList<com.github.auties00.smalt.track.Packet>();
            for (var packet : source.packetsOf(base)) {
                packets.add(packet);
                packets.add(packet.withTrackId(enhancement.id()));
            }
            // the descriptor of a profile 7 level 6 enhancement layer: tag, length 7, version 1.0, 0x0E36
            var head = new byte[]{(byte) 0xB0, 7, 1, 0, 0x0E, 0x36};
            for (var declared : List.of(base.toBuilder().noDolbyVision().build(), base)) {
                var ts = MuxSupport.toMemory(List.of(declared, enhancement), packets, TsMuxer::new);
                var basePid = MuxSupport.demux(ts).tracks().getFirst().id();
                assertEquals(basePid, dependencyOf(ts, head), "base " + declared.dolbyVision());
            }
            // an enhancement layer declared before its base layer names no PID
            var reversed = MuxSupport.toMemory(List.of(enhancement, base.toBuilder().noDolbyVision().build()), packets,
                    TsMuxer::new);
            assertEquals(DolbyVisionConfigs.NO_PID, dependencyOf(reversed, head));
            var demuxed = MuxSupport.demux(reversed);
            assertEquals(layer, ((VideoTrack) demuxed.tracks().getFirst()).dolbyVision().orElseThrow());
        }

        private static int dependencyOf(byte[] ts, byte[] head) {
            for (var index = 0; index + head.length + 2 <= ts.length; index++) {
                if (java.util.Arrays.equals(ts, index, index + head.length, head, 0, head.length)) {
                    return (ts[index + head.length] & 0xFF) << 5 | (ts[index + head.length + 1] & 0xFF) >>> 3;
                }
            }
            throw new AssertionError("no DOVI descriptor of the enhancement layer");
        }

        @Test
        @DisplayName("write the Dolby Vision mapping next to the HDR10+ one of a PQ track, and none in WebM")
        void mappings() {
            var source = MuxSupport.demux(sample("mp4/dolby-vision-p84.mp4"));
            var tracks = HdrFixtures.withColor(source.tracks(), HdrFixtures.hdr10(ChromaSiting.TOP_LEFT));
            tracks = withDolbyVision(tracks, DolbyVision.of(8, 1, 1));
            var mkv = MuxSupport.toMemory(tracks, HdrFixtures.withHdr10Plus(source.order(), packet -> true),
                    sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.matroska()));
            var demuxed = MuxSupport.demux(mkv);
            assertEquals(DolbyVision.of(8, 1, 1), video(demuxed).dolbyVision().orElseThrow());
            assertTrue(demuxed.packetsOf(video(demuxed)).getFirst().sideData().hdr10Plus().isPresent());

            var av1 = MuxSupport.demux(Fixtures.read(Fixtures.ffmpeg("webm", List.of("-f", "lavfi", "-i",
                    "testsrc2=size=96x64:rate=10:duration=0.3"), List.of("-c:v", "libaom-av1", "-cpu-used", "8",
                    "-usage", "realtime"))));
            var webm = MuxSupport.toMemory(withDolbyVision(av1.tracks(), DolbyVision.of(10, 1, 2)), av1.order(),
                    sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.webm()));
            assertFalse(video(MuxSupport.demux(webm)).dolbyVision().isPresent());
        }
    }

    @Nested
    @DisplayName("ffmpeg as the other side")
    class Oracle {
        @Test
        @DisplayName("Smalt reads the configuration of ffmpeg's MP4 and Matroska copies of Smalt's files")
        void copies() {
            for (var name : List.of("mp4/dolby-vision-p84.mp4", "mp4/dolby-vision-p5.mp4")) {
                var source = MuxSupport.demux(sample(name));
                var expected = video(source).dolbyVision().orElseThrow();
                var mkv = MuxSupport.save("dolby-vision-oracle-" + expected.profile() + ".mkv",
                        MuxSupport.toMemory(source.tracks(), source.order(),
                                sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.matroska())));
                // ffmpeg writes dvcC and dvvC only with -strict unofficial
                var mp4 = HdrFixtures.ffmpegCopy(mkv, "dolby-vision-oracle-" + expected.profile() + ".mp4",
                        "-strict", "unofficial");
                var mp4Demuxed = MuxSupport.demux(Fixtures.read(mp4));
                assertEquals(expected, video(mp4Demuxed).dolbyVision().orElseThrow(), name);
                assertSameRpus(rpus(source), rpus(mp4Demuxed), name + " through ffmpeg MP4");
                var mkvCopy = HdrFixtures.ffmpegCopy(mp4, "dolby-vision-oracle-copy-" + expected.profile() + ".mkv");
                assertEquals(expected, video(MuxSupport.demux(Fixtures.read(mkvCopy))).dolbyVision().orElseThrow(),
                        name);
            }
        }
    }

    @Nested
    @DisplayName("dovi_tool's mkvmerge sample")
    class Mkvmerge {
        /** Returns the escaped RPUs of a dovi_tool extraction: each one after a four-byte start code. */
        private static List<byte[]> extracted(byte[] file) {
            var result = new ArrayList<byte[]>();
            var start = -1;
            for (var index = 0; index + 4 <= file.length; index++) {
                if (file[index] == 0 && file[index + 1] == 0 && file[index + 2] == 0 && file[index + 3] == 1) {
                    if (start >= 0) {
                        result.add(java.util.Arrays.copyOfRange(file, start, index));
                    }
                    start = index + 4;
                    index += 3;
                }
            }
            result.add(java.util.Arrays.copyOfRange(file, start, file.length));
            return result;
        }

        @Test
        @DisplayName("reads the profile 8.1 configuration and the RPUs dovi_tool extracts, and keeps both in a remux")
        void read() {
            var mkv = Fixtures.read(com.github.auties00.smalt.testkit.Conformance.fetchListed("dovi-tool-regular.mkv"));
            var expected = Fixtures.read(com.github.auties00.smalt.testkit.Conformance.fetchListed(
                    "dovi-tool-regular_rpu.bin"));
            var demuxed = MuxSupport.demux(mkv);
            var track = video(demuxed);
            assertEquals(Codec.HEVC, track.codec());
            assertEquals(new DolbyVision(1, 0, 8, 1, true, false, true, 1, 0), track.dolbyVision().orElseThrow());
            var rpus = rpus(demuxed);
            var reference = extracted(expected);
            assertEquals(259, reference.size());
            assertEquals(reference.size(), rpus.size());
            // dovi_tool extracts the RPUs in presentation order
            var packets = demuxed.packetsOf(track);
            var presentation = java.util.stream.IntStream.range(0, packets.size()).boxed()
                    .sorted(java.util.Comparator.comparingLong(index -> packets.get(index).pts()))
                    .map(rpus::get).toList();
            for (var index = 0; index < presentation.size(); index++) {
                var nal = presentation.get(index);
                assertArrayEquals(reference.get(index), java.util.Arrays.copyOfRange(nal, 2, nal.length),
                        "RPU " + index);
                assertEquals(8, DolbyVisionRpu.parseHevcNal(nal, 0, nal.length).profile(), "RPU " + index);
            }
            var muxers = List.<Function<MediaSink, Muxer>>of(Mp4Muxer::new, TsMuxer::new);
            var extensions = List.of("mp4", "ts");
            for (var index = 0; index < muxers.size(); index++) {
                var output = MuxSupport.toMemory(demuxed.tracks(), demuxed.order(), muxers.get(index));
                var back = MuxSupport.demux(output);
                assertEquals(track.dolbyVision(), video(back).dolbyVision(), extensions.get(index));
                assertSameRpus(rpus, rpus(back), "remux to " + extensions.get(index));
                var file = MuxSupport.save("dolby-vision-mkvmerge." + extensions.get(index), output);
                assertEquals("8.1.1", probedConfiguration(file));
            }
        }
    }

    @Nested
    @DisplayName("H.264 and AV1")
    class OtherCodecs {
        static Stream<Arguments> cases() {
            return Stream.of(
                    Arguments.of("H.264 9.2", List.of("-c:v", "libx264", "-preset", "ultrafast"), DolbyVision.of(9, 2, 2),
                            "avc1", "dvvC"),
                    Arguments.of("H.264 without a compatible base layer", List.of("-c:v", "libx264", "-preset",
                            "ultrafast"), DolbyVision.of(9, 2, 0), "dva1", "dvvC"),
                    Arguments.of("AV1 10.1", List.of("-c:v", "libaom-av1", "-cpu-used", "8", "-usage", "realtime"),
                            DolbyVision.of(10, 3, 1), "av01", "dvvC"),
                    Arguments.of("AV1 10.0", List.of("-c:v", "libaom-av1", "-cpu-used", "8", "-usage", "realtime"),
                            DolbyVision.of(10, 3, 0), "dav1", "dvvC"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("cases")
        @DisplayName("store the configuration in the sample entry, Matroska and MPEG-TS, read back as the base codec")
        void bindings(String name, List<String> options, DolbyVision configuration, String entry, String box) {
            var source = MuxSupport.demux(Fixtures.read(Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i",
                    "testsrc2=size=96x64:rate=10:duration=0.3"), options)));
            var codec = video(source).codec();
            var tracks = withDolbyVision(source.tracks(), configuration);
            var mp4 = MuxSupport.toMemory(tracks, source.order(), Mp4Muxer::new);
            assertEquals(entry, fourCc(mp4));
            assertArrayEquals(DolbyVisionConfigs.record(configuration), HdrFixtures.boxPayload(mp4, box));
            var back = video(MuxSupport.demux(mp4));
            assertEquals(codec, back.codec());
            assertEquals(configuration, back.dolbyVision().orElseThrow());
            var file = MuxSupport.save("dolby-vision-" + entry + ".mp4", mp4);
            assertEquals(configuration.profile() + "." + configuration.level() + "."
                    + configuration.baseLayerCompatibility(), probedConfiguration(file));
            for (var factory : List.<Function<MediaSink, Muxer>>of(
                    sink -> new MatroskaMuxer(sink, MatroskaMuxerOptions.matroska()), TsMuxer::new)) {
                var copied = video(MuxSupport.demux(MuxSupport.toMemory(tracks, source.order(), factory)));
                assertEquals(codec, copied.codec());
                assertEquals(configuration, copied.dolbyVision().orElseThrow());
            }
        }
    }

    @Nested
    @DisplayName("robustness")
    class Robustness {
        @ParameterizedTest(name = "{0} -> {1}")
        @CsvSource({"dvvC, dvwC, true", "dvvC, dvcC, true", "dvvC, dvvX, false"})
        @DisplayName("read a configuration box of any of the three types and ignore other boxes")
        void boxTypes(String from, String to, boolean configured) {
            var data = sample("mp4/dolby-vision-p84.mp4").clone();
            var text = new String(data, StandardCharsets.ISO_8859_1);
            var index = text.indexOf(from);
            assertTrue(index > 0);
            var replacement = to.getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(replacement, 0, data, index, 4);
            var track = video(MuxSupport.demux(data));
            assertEquals(Codec.HEVC, track.codec());
            assertEquals(configured, track.dolbyVision().isPresent());
            if (configured) {
                assertEquals(P84, track.dolbyVision().get());
            }
        }

        @Test
        @DisplayName("ignore a configuration record cut to four bytes")
        void shortRecord() {
            var data = sample("mp4/dolby-vision-p84.mp4").clone();
            var index = new String(data, StandardCharsets.ISO_8859_1).indexOf("dvvC");
            // shrink the box to 12 bytes and turn the rest of its payload into a free box
            data[index - 1] = 12;
            data[index + 8] = 0;
            data[index + 9] = 0;
            data[index + 10] = 0;
            data[index + 11] = 20;
            System.arraycopy("free".getBytes(StandardCharsets.US_ASCII), 0, data, index + 12, 4);
            var track = video(MuxSupport.demux(data));
            assertEquals(Codec.HEVC, track.codec());
            assertFalse(track.dolbyVision().isPresent());
        }
    }
}
