package com.github.auties00.smalt.container.mxf;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerFormat;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.Timecode;
import com.github.auties00.smalt.internal.codec.NalConfigs;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers reading MXF files: ffmpeg's OP1a files of VC-3, ProRes, AVC long GOP, AVC-Intra class 50 and MPEG-2 long GOP
 * essence with PCM, and its OP-Atom files of one track, read against ffprobe (codec, packet count, payloads, key
 * frames and presentation timestamps; H.264 by its NAL units), the header metadata (tracks, descriptors, timecode,
 * tags), recognition after a run-in, the AVC-Intra headerless parameter sets of every RP 2027 class and raster, and
 * damaged files.
 */
@DisplayName("MXF demuxing")
class MxfDemuxerTest {
    static final Path SAMPLES = Path.of("src", "test", "resources", "samples", "mxf");

    static ContainerProbe.Demuxed read(byte[] data) {
        try (var demuxer = new MxfDemuxer(MediaSource.of(data))) {
            return ContainerProbe.demuxAll(demuxer);
        }
    }

    static ContainerProbe.Demuxed read(String sample) {
        return read(Fixtures.read(SAMPLES.resolve(sample)));
    }

    /** {@return the NAL units of an Annex B access unit, without trailing zero bytes} */
    static List<String> annexBUnits(byte[] data) {
        var out = new ArrayList<String>();
        var start = -1;
        for (var at = 0; at + 3 <= data.length; at++) {
            if (data[at] == 0 && data[at + 1] == 0 && data[at + 2] == 1) {
                if (start >= 0) {
                    out.add(unit(data, start, at));
                }
                start = at + 3;
                at += 2;
            }
        }
        if (start >= 0) {
            out.add(unit(data, start, data.length));
        }
        out.removeIf(String::isEmpty);
        return out;
    }

    private static String unit(byte[] data, int start, int end) {
        while (end > start && data[end - 1] == 0) {
            end--;
        }
        return HexFormat.of().formatHex(data, start, end);
    }

    /** {@return the NAL units of a length-prefixed access unit} */
    static List<String> lengthPrefixedUnits(byte[] data) {
        var out = new ArrayList<String>();
        for (var at = 0; at + 4 <= data.length; ) {
            var length = (data[at] & 0xFF) << 24 | (data[at + 1] & 0xFF) << 16 | (data[at + 2] & 0xFF) << 8
                    | data[at + 3] & 0xFF;
            out.add(unit(data, at + 4, at + 4 + length));
            at += 4 + length;
        }
        out.removeIf(String::isEmpty);
        return out;
    }

    /**
     * Asserts that Smalt's packets of every track carry ffprobe's payloads, key frame flags and presentation
     * timestamps, packet for packet.
     */
    /** {@return the rank of each packet's presentation timestamp among the packets, in decoding order} */
    static int[] displayOrder(List<Packet> packets) {
        var ranks = new Integer[packets.size()];
        for (var index = 0; index < ranks.length; index++) {
            ranks[index] = index;
        }
        Arrays.sort(ranks, (first, second) -> Long.compare(packets.get(first).pts(), packets.get(second).pts()));
        var out = new int[ranks.length];
        for (var rank = 0; rank < ranks.length; rank++) {
            out[ranks[rank]] = rank;
        }
        return out;
    }

    static void assertMatchesFfprobe(Path file, ContainerProbe.Demuxed demuxed) {
        assertMatchesFfprobe(file, demuxed, true);
    }

    /**
     * Asserts that Smalt's packets of every track carry ffprobe's payloads and key frame flags, and, when asked, its
     * presentation timestamps, packet for packet.
     */
    static void assertMatchesFfprobe(Path file, ContainerProbe.Demuxed demuxed, boolean timestamps) {
        var probe = ContainerProbe.probe(file);
        var data = ContainerProbe.packetData(file);
        assertEquals(probe.streams().size(), demuxed.tracks().size(), "stream count");
        for (var index = 0; index < demuxed.tracks().size(); index++) {
            var track = demuxed.tracks().get(index);
            var ours = demuxed.packetsOf(track);
            var theirs = new ArrayList<Integer>();
            for (var at = 0; at < probe.packets().size(); at++) {
                if (probe.packets().get(at).stream() == index) {
                    theirs.add(at);
                }
            }
            if (track.codec().isPcm()) {
                var expected = new java.io.ByteArrayOutputStream();
                theirs.forEach(at -> expected.writeBytes(data.get(at)));
                var actual = new java.io.ByteArrayOutputStream();
                ours.forEach(packet -> actual.writeBytes(packet.data()));
                assertArrayEquals(expected.toByteArray(), actual.toByteArray(), "samples of stream " + index);
                continue;
            }
            assertEquals(theirs.size(), ours.size(), "packet count of stream " + index);
            for (var at = 0; at < ours.size(); at++) {
                var packet = ours.get(at);
                var probed = probe.packets().get(theirs.get(at));
                var payload = data.get(theirs.get(at));
                if (track.codec() == Codec.H264) {
                    assertEquals(annexBUnits(payload), lengthPrefixedUnits(packet.data()),
                            "NAL units of packet " + at + " of stream " + index);
                } else {
                    assertEquals(probed.hash(), ContainerProbe.crc32(packet.data()),
                            "payload of packet " + at + " of stream " + index);
                }
                if (track instanceof VideoTrack) {
                    assertEquals(probed.key(), packet.keyframe(), "key flag of packet " + at);
                    // AVC without temporal offsets has no presentation timestamps, where ffprobe repeats the decoding ones
                    if (timestamps && probed.pts() != Packet.NO_TIMESTAMP && packet.pts() != Packet.NO_TIMESTAMP) {
                        var rate = probe.streams().get(index);
                        var expected = new Rational(rate.timeBaseNumerator(), rate.timeBaseDenominator())
                                .rescale(probed.pts(), track.timeBase());
                        assertEquals(expected, packet.pts(), "pts of packet " + at + " of stream " + index);
                    }
                }
            }
        }
    }

    @Nested
    @DisplayName("ffmpeg's files")
    class Samples {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"op1a-dnxhr.mxf", "op1a-prores.mxf", "op1a-h264.mxf", "op1a-mpeg2.mxf",
                "op1a-avci50.mxf", "opatom-dnxhr.mxf", "opatom-pcm.mxf"})
        @DisplayName("every packet matches ffprobe's: payload, key flag, presentation timestamp")
        void ffprobe(String sample) {
            Oracle.assume(Oracle.Tool.FFPROBE);
            // ffmpeg's AVC sample has no temporal offsets, and ffprobe then reports its decoding order as
            // presentation order; Smalt's order counts are checked in h264()
            assertMatchesFfprobe(SAMPLES.resolve(sample), read(sample), !sample.equals("op1a-h264.mxf"));
        }

        @Test
        @DisplayName("VC-3 and AES3 PCM: tracks from the descriptors and the frame header")
        void dnxhr() {
            var demuxed = read("op1a-dnxhr.mxf");
            assertEquals(2, demuxed.tracks().size());
            var video = assertInstanceOf(VideoTrack.class, demuxed.tracks().get(0));
            assertEquals(Codec.DNXHD, video.codec());
            assertEquals(256, video.width());
            assertEquals(128, video.height());
            assertEquals(new Rational(25, 1), video.frameRate().orElseThrow());
            assertEquals(new Rational(1, 25), video.timeBase());
            assertArrayEquals("AVdh".getBytes(), video.codecConfig().orElseThrow());
            assertEquals(6, demuxed.packetsOf(video).size());
            var audio = assertInstanceOf(AudioTrack.class, demuxed.tracks().get(1));
            assertEquals(Codec.PCM_S24LE, audio.codec());
            assertEquals(48000, audio.sampleRate());
            assertEquals(2, audio.layout().orElseThrow().channels());
            var samples = 0L;
            var expectedPts = 0L;
            for (var packet : demuxed.packetsOf(audio)) {
                assertEquals(expectedPts, packet.pts());
                assertEquals(1920, packet.duration());
                expectedPts += packet.duration();
                samples += packet.size() / 6;
            }
            assertEquals(11520, samples);
            assertEquals(240_000, demuxed.durationMicros());
        }

        @Test
        @DisplayName("ProRes: the sample entry type of the picture coding label's profile")
        void prores() {
            var video = assertInstanceOf(VideoTrack.class, read("op1a-prores.mxf").tracks().getFirst());
            assertEquals(Codec.PRORES, video.codec());
            assertArrayEquals("apcn".getBytes(), video.codecConfig().orElseThrow());
            assertEquals(64, video.width());
            assertEquals(48, video.height());
        }

        @Test
        @DisplayName("AVC long GOP: avcC from the first access unit, display order from the index")
        void h264() {
            var demuxed = read("op1a-h264.mxf");
            var video = assertInstanceOf(VideoTrack.class, demuxed.tracks().getFirst());
            assertEquals(Codec.H264, video.codec());
            assertEquals(160, video.width());
            assertEquals(120, video.height());
            var config = NalConfigs.parseAvc(video.codecConfig().orElseThrow());
            assertNotNull(config);
            assertEquals(4, config.lengthSize());
            var packets = demuxed.packetsOf(video);
            assertEquals(20, packets.size());
            // ffmpeg flags the B pictures in the index but writes no temporal offsets for AVC: the presentation order
            // comes from the order counts, as the Annex B demuxer finds it in the same pictures
            var pts = packets.stream().map(Packet::pts).sorted().toList();
            for (var index = 0; index < pts.size(); index++) {
                assertEquals(index, pts.get(index), "presentation timestamps are a permutation of the frames");
            }
            assertTrue(packets.stream().anyMatch(packet -> packet.pts() != packet.dts()), "B-frames are reordered");
            for (var index = 0; index < packets.size(); index++) {
                assertTrue(packets.get(index).dts() <= packets.get(index).pts(), "decoding precedes presentation");
                if (index > 0) {
                    assertEquals(packets.get(index - 1).dts() + 1, packets.get(index).dts());
                }
            }
            if (Oracle.find(Oracle.Tool.FFMPEG).isPresent()) {
                var annexB = Oracle.run(Oracle.Tool.FFMPEG, java.time.Duration.ofMinutes(1), List.of("-v", "error",
                        "-i", SAMPLES.resolve("op1a-h264.mxf").toString(), "-map", "0:v", "-c", "copy", "-f", "h264",
                        "-"));
                try (var elementary = new com.github.auties00.smalt.container.raw.AnnexBDemuxer(
                        MediaSource.of(annexB))) {
                    var reference = com.github.auties00.smalt.testkit.Packets.readAll(elementary);
                    assertEquals(reference.size(), packets.size());
                    var base = reference.stream().mapToLong(Packet::pts).min().orElseThrow();
                    var step = reference.size() > 1 ? reference.stream().mapToLong(Packet::duration).max().orElse(1)
                            : 1;
                    for (var index = 0; index < packets.size(); index++) {
                        assertEquals((reference.get(index).pts() - base) / step, packets.get(index).pts(),
                                "display position of picture " + index);
                    }
                }
            }
            assertTrue(packets.getFirst().keyframe());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"op1a-h264.mxf", "op1a-prores.mxf", "bmx-op1a-prores.mxf"})
        @DisplayName("remuxed to MP4 by Mp4Remuxer: every track and packet payload, key flag and display order kept")
        void remux(String sample) {
            var source = read(sample);
            var sink = com.github.auties00.smalt.io.MediaSink.memory();
            var options = com.github.auties00.smalt.container.mp4.RemuxOptions.builder().allAudioTracks();
            if (source.tracks().getFirst().codec() == Codec.PRORES) {
                options.brand(com.github.auties00.smalt.container.mp4.Mp4MuxerOptions.Brand.QUICKTIME);
            }
            com.github.auties00.smalt.container.mp4.Mp4Remuxer.remux(
                    MediaSource.of(Fixtures.read(SAMPLES.resolve(sample))), sink, options.build());
            try (var mp4 = Containers.openDemuxer(MediaSource.of(sink.toByteArray()))) {
                assertEquals(ContainerFormat.MP4, mp4.format());
                var remuxed = ContainerProbe.demuxAll(mp4);
                assertEquals(source.tracks().size(), remuxed.tracks().size(), "track count");
                for (var index = 0; index < source.tracks().size(); index++) {
                    var want = source.tracks().get(index);
                    var got = remuxed.tracks().get(index);
                    assertEquals(want.codec(), got.codec());
                    var wanted = source.packetsOf(want);
                    var read = remuxed.packetsOf(got);
                    if (want.codec().isPcm()) {
                        var expected = new java.io.ByteArrayOutputStream();
                        wanted.forEach(packet -> expected.writeBytes(packet.data()));
                        var actual = new java.io.ByteArrayOutputStream();
                        read.forEach(packet -> actual.writeBytes(packet.data()));
                        assertArrayEquals(expected.toByteArray(), actual.toByteArray(), "samples of track " + index);
                        continue;
                    }
                    assertEquals(wanted.size(), read.size(), "packet count of track " + index);
                    var wantedOrder = displayOrder(wanted);
                    var readOrder = displayOrder(read);
                    for (var at = 0; at < wanted.size(); at++) {
                        assertArrayEquals(wanted.get(at).data(), read.get(at).data(), "payload " + at);
                        assertEquals(wanted.get(at).keyframe(), read.get(at).keyframe(), "key flag " + at);
                        assertEquals(wantedOrder[at], readOrder[at], "display position of packet " + at);
                    }
                }
            }
        }

        @Test
        @DisplayName("MPEG-2 long GOP: one picture per packet, display order from the index")
        void mpeg2() {
            var demuxed = read("op1a-mpeg2.mxf");
            var video = assertInstanceOf(VideoTrack.class, demuxed.tracks().getFirst());
            assertEquals(Codec.MPEG2_VIDEO, video.codec());
            var packets = demuxed.packetsOf(video);
            var pts = packets.stream().map(Packet::pts).sorted().toList();
            for (var index = 0; index < pts.size(); index++) {
                assertEquals(index, pts.get(index));
            }
            assertEquals(4, packets.stream().filter(Packet::keyframe).count(), "one I picture every 6 frames");
        }

        @Test
        @DisplayName("AVC-Intra class 50: in-band parameter sets equal those of the class's raster")
        void avcIntra() {
            var demuxed = read("op1a-avci50.mxf");
            var video = assertInstanceOf(VideoTrack.class, demuxed.tracks().getFirst());
            assertEquals(Codec.H264, video.codec());
            assertEquals(960, video.width());
            assertEquals(720, video.height());
            var config = NalConfigs.parseAvc(video.codecConfig().orElseThrow());
            var sets = AvcIntra.parameterSets(50, 5);
            assertArrayEquals(sets[0], config.parameterSets().get(0));
            assertArrayEquals(sets[1], config.parameterSets().get(1));
        }

        @Test
        @DisplayName("OP-Atom: one track, the operational pattern in the tags")
        void opAtom() {
            var picture = read("opatom-dnxhr.mxf");
            assertEquals(1, picture.tracks().size());
            assertEquals("OPAtom", picture.metadata().get("operational_pattern"));
            assertEquals(3, picture.packetsOf(picture.tracks().getFirst()).size());
            var sound = read("opatom-pcm.mxf");
            var audio = assertInstanceOf(AudioTrack.class, sound.tracks().getFirst());
            assertEquals(Codec.PCM_S24LE, audio.codec());
            assertEquals(11520, sound.packetsOf(audio).stream().mapToLong(Packet::duration).sum());
        }

        @Test
        @DisplayName("the header metadata: timecode, operational pattern and the identification of the writer")
        void metadata() {
            try (var demuxer = new MxfDemuxer(MediaSource.of(Fixtures.read(SAMPLES.resolve("op1a-dnxhr.mxf"))))) {
                assertEquals(new Timecode(0, 0, 0, 0, 25, false), demuxer.timecode().orElseThrow());
                assertEquals("OP1a", demuxer.metadata().get("operational_pattern"));
                assertTrue(demuxer.metadata().containsKey("product_name"));
                assertEquals(ContainerFormat.MXF, demuxer.format());
                assertTrue(demuxer.isSeekable());
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"op1a-dnxhr.mxf", "op1a-h264.mxf", "opatom-pcm.mxf"})
        @DisplayName("recognized by content, and after a run-in of up to 64 KiB")
        void runIn(String sample) {
            var data = Fixtures.read(SAMPLES.resolve(sample));
            assertEquals(ContainerFormat.MXF, Containers.detect(MediaSource.of(data)).orElseThrow());
            for (var size : new int[]{1, 4000, 5000, 65_000}) {
                var withRunIn = new byte[size + data.length];
                Arrays.fill(withRunIn, 0, size, (byte) 0x5A);
                System.arraycopy(data, 0, withRunIn, size, data.length);
                assertEquals(ContainerFormat.MXF, Containers.detect(MediaSource.of(withRunIn)).orElseThrow(),
                        "run-in of " + size);
                var expected = read(data);
                var actual = read(withRunIn);
                assertEquals(expected.tracks(), actual.tracks());
                MuxSupport.assertSamePayloads(expected, actual, ids(expected));
            }
        }
    }

    @Nested
    @DisplayName("AVC-Intra headerless parameter sets")
    class Headerless {
        @ParameterizedTest(name = "class {0}, {1}x{2} at {3} {4}")
        @CsvSource({
                "50, 1440, 1080, 30000/1001, i, 0", "50, 1440, 1080, 25, i, 1", "50, 1440, 1080, 30000/1001, p, 2",
                "50, 1440, 1080, 25, p, 3", "50, 960, 720, 60000/1001, p, 4", "50, 960, 720, 50, p, 5",
                "100, 1920, 1080, 30000/1001, i, 0", "100, 1920, 1080, 25, i, 1", "100, 1920, 1080, 30000/1001, p, 2",
                "100, 1920, 1080, 25, p, 3", "100, 1280, 720, 60000/1001, p, 4", "100, 1280, 720, 50, p, 5",
                "200, 1920, 1080, 30000/1001, i, 0", "200, 1920, 1080, 25, i, 1", "200, 1920, 1080, 30000/1001, p, 2",
                "200, 1920, 1080, 25, p, 3", "200, 1280, 720, 60000/1001, p, 4", "200, 1280, 720, 50, p, 5"})
        @DisplayName("each class and raster: the parameter sets decode to the class's picture, profile and timing")
        void tables(int cls, int width, int height, String rate, String scan, int raster) {
            var parts = rate.split("/");
            var frameRate = new Rational(Long.parseLong(parts[0]), parts.length > 1 ? Long.parseLong(parts[1]) : 1);
            var label = AvcIntra.codingLabel(cls, raster);
            assertEquals(cls, AvcIntra.classOf(label));
            assertEquals(raster, AvcIntra.rasterOf(label, 0, false, null));
            assertEquals(raster, AvcIntra.rasterOf(new byte[16], height, scan.equals("i"), frameRate));
            var sets = AvcIntra.parameterSets(cls, raster);
            var info = com.github.auties00.smalt.internal.codec.VideoConfigs.avcSps(sets[0]);
            assertEquals(width, info.width());
            assertEquals(height, info.height());
            assertEquals(cls == 50 ? 110 : 122, sets[0][1] & 0xFF, "profile_idc");
            assertEquals(0x10, sets[0][2] & 0x10, "constraint_set3_flag marks the intra profile");
            var level = sets[0][3] & 0xFF;
            var expectedLevel = cls == 50 ? (raster >= 4 ? 32 : 40) : 41;
            assertEquals(expectedLevel, level, "the level of RP 2027 Tables 5.1 to 5.3");
            assertEquals(frameRate, info.frameRate().reduce(), "time_scale / num_units_in_tick");
            assertEquals(8, sets[1][0] & 0x1F, "a picture parameter set");
        }

        @Test
        @DisplayName("a headerless stream decodes as its in-band original does (ffmpeg)")
        void decodesLikeOriginal() {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var original = Fixtures.read(SAMPLES.resolve("op1a-avci50.mxf"));
            var headerless = MxfMuxerTest.remux(original, MxfMuxerOptions.builder().avcIntraClass(50)
                    .avcIntraParameterSets(false).materialPackageUmid(new byte[32]).build());
            var demuxed = read(headerless);
            var video = demuxed.tracks().getFirst();
            for (var packet : demuxed.packetsOf(video)) {
                for (var unit : lengthPrefixedUnits(packet.data())) {
                    var type = Integer.parseInt(unit.substring(0, 2), 16) & 0x1F;
                    assertFalse(type == 7 || type == 8, "no parameter set in band");
                }
            }
            var sets = NalConfigs.parseAvc(video.codecConfig().orElseThrow()).parameterSets();
            assertArrayEquals(AvcIntra.parameterSets(50, 5)[0], sets.get(0));
            var expected = Oracle.decodeVideo(MuxSupport.save("avci-inband.mxf", original), "yuv420p10le", 0);
            var actual = Oracle.decodeVideo(MuxSupport.save("avci-headerless.mxf", headerless), "yuv420p10le", 0);
            assertArrayEquals(expected, actual);
        }

        // The Panasonic AVC-Intra parameter sets that BBC bmx 1.6 (BSD-3-Clause) embeds for P2 files without them
        // (src/apps/ps_avci_header_data.h), an encoder independent of the one the tables were recorded from; class
        // 50 and 100 at 25 and 50 Hz; their 720p25 sets are compared with those of 720p50 (raster 5 of
        // AvcIntra.rasterOf), whose decoding fields are the same
        @ParameterizedTest(name = "class {0}, raster {1}")
        @CsvSource({
                "100, 1, 677a1029b6d420223319c6632321011198ce33191821033a46656a6524ade91232141a2634ada441822301502b1a"
                        + "24694830402e111208c68c0441284c34f01e0113f2e03c602020280000030008000003019420, 68ce3348d0",
                "100, 3, 677a1029b6d420223319c6632321011198ce331918210256b93d7d7e4fe33f11f19e08b88c5443c0780227e2701e"
                        + "30101014000003000400000300ca10, 68ce3348d0",
                "100, 5, 677a1029b6d4202a331dc762a1084054663b8ec542021025642c89e885e4214b90830695d106469720c8d7430811"
                        + "c21e4c910f014016ec078c0404050000030001000003003284, 68ce311211",
                "100, 5, 677a1029b6d4202a331dc762a1084054663b8ec542021025642c89e885e4214b90830695d106469720c8d7430811"
                        + "c21e4c910f014016ec078c0404050000030001000003006484, 68ce311211",
                "50, 1, 676e1028a6d42032330c7118886210191986388c44302102564e6e61873e734d980c03069c0b73e6c0b518630d39e"
                        + "05b02d4c6191a798c323424f0168113f7ff8001800271808080a000000300200000065080, 68ee311211",
                "50, 3, 676e1028a6d42032330c7118886210191986388c44302102564e6f37cdf9bf816bf37cde6e6cd33c05a0227e5ffc0"
                        + "00c00138c0404050000030001000003003284, 68ee311211",
                "50, 5, 676e1020a6d42032330c7118886210191986388c44302102564e6f37cdf9bf816bf37cde6e6cd33c0f016effc000c"
                        + "00138c04040500000030010000003032840, 68ee311211",
                "50, 5, 676e1020a6d42032330c7118886210191986388c44302102564e6f37cdf9bf816bf37cde6e6cd33c0f016effc000c"
                        + "00138c040405000000300100000064840, 68ee311211"})
        @DisplayName("the decoding fields of each set equal those another AVC-Intra encoder writes (bmx's tables)")
        void bmxTables(int cls, int raster, String sps, String pps) {
            var sets = AvcIntra.parameterSets(cls, raster);
            assertEquals(decodingFields(HexFormat.of().parseHex(sps)), decodingFields(sets[0]),
                    "every sequence parameter set field before the VUI, the scaling lists included");
            assertArrayEquals(HexFormat.of().parseHex(pps), sets[1], "the picture parameter set");
        }

        /**
         * {@return the fields of a High-profile sequence parameter set that decoding depends on, in order: everything
         * before {@code vui_parameters_present_flag}, each scaling list expanded to its values}
         */
        private static List<Integer> decodingFields(byte[] nal) {
            var rbsp = com.github.auties00.smalt.internal.nal.NalUnits.unescape(nal, 1, nal.length - 1);
            var reader = new com.github.auties00.smalt.internal.bits.MsbBitReader(rbsp);
            var fields = new ArrayList<Integer>();
            var profile = reader.read(8);
            fields.add(profile);
            fields.add(reader.read(8));
            fields.add(reader.read(8));
            fields.add(reader.readUe());
            assertTrue(profile == 110 || profile == 122, "a High 10 or High 4:2:2 profile");
            var chroma = reader.readUe();
            fields.add(chroma);
            fields.add(reader.readUe());
            fields.add(reader.readUe());
            fields.add(reader.read(1));
            var scaling = reader.read(1);
            fields.add(scaling);
            if (scaling == 1) {
                for (var list = 0; list < 8; list++) {
                    var present = reader.read(1);
                    fields.add(present);
                    if (present == 0) {
                        continue;
                    }
                    var last = 8;
                    var next = 8;
                    for (var index = 0; index < (list < 6 ? 16 : 64); index++) {
                        if (next != 0) {
                            next = (last + reader.readSe() + 256) % 256;
                        }
                        last = next == 0 ? last : next;
                        fields.add(last);
                    }
                }
            }
            fields.add(reader.readUe());
            var order = reader.readUe();
            fields.add(order);
            if (order == 0) {
                fields.add(reader.readUe());
            }
            fields.add(reader.readUe());
            fields.add(reader.read(1));
            fields.add(reader.readUe());
            fields.add(reader.readUe());
            var frameOnly = reader.read(1);
            fields.add(frameOnly);
            if (frameOnly == 0) {
                fields.add(reader.read(1));
            }
            fields.add(reader.read(1));
            var cropping = reader.read(1);
            fields.add(cropping);
            if (cropping == 1) {
                for (var edge = 0; edge < 4; edge++) {
                    fields.add(reader.readUe());
                }
            }
            return fields;
        }
    }

    @Nested
    @DisplayName("bmx's files")
    class Bmx {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"bmx-op1a-avci50-headerless.mxf", "bmx-op1a-vc3.mxf", "bmx-op1a-prores.mxf"})
        @DisplayName("every packet matches ffprobe's: payload, key flag, presentation timestamp")
        void ffprobe(String sample) {
            Oracle.assume(Oracle.Tool.FFPROBE);
            assertMatchesFfprobe(SAMPLES.resolve(sample), read(sample));
        }

        @Test
        @DisplayName("headerless AVC-Intra 50 720p50: the class's sets decode the picture as its in-band original")
        void avcIntraHeaderless() {
            var demuxed = read("bmx-op1a-avci50-headerless.mxf");
            var video = assertInstanceOf(VideoTrack.class, demuxed.tracks().getFirst());
            assertEquals(Codec.H264, video.codec());
            // bmx stores the 1280x720 display raster in the descriptor; the coded picture is 960 wide
            assertEquals(960, video.width());
            assertEquals(720, video.height());
            var config = NalConfigs.parseAvc(video.codecConfig().orElseThrow());
            assertArrayEquals(AvcIntra.parameterSets(50, 5)[0], config.parameterSets().get(0));
            assertArrayEquals(AvcIntra.parameterSets(50, 5)[1], config.parameterSets().get(1));
            var packets = demuxed.packetsOf(video);
            assertEquals(1, packets.size());
            var annexB = new java.io.ByteArrayOutputStream();
            for (var set : config.parameterSets()) {
                annexB.writeBytes(new byte[]{0, 0, 0, 1});
                annexB.writeBytes(set);
            }
            for (var unit : lengthPrefixedUnits(packets.getFirst().data())) {
                var type = Integer.parseInt(unit.substring(0, 2), 16) & 0x1F;
                assertFalse(type == 7 || type == 8, "no parameter set in band");
                annexB.writeBytes(new byte[]{0, 0, 0, 1});
                annexB.writeBytes(HexFormat.of().parseHex(unit));
            }
            Oracle.assume(Oracle.Tool.FFMPEG);
            var decoded = Oracle.decodeVideo(MuxSupport.save("bmx-avci.h264", annexB.toByteArray()), List.of("-f",
                    "h264"), "yuv420p10le", 0);
            // SHA-256 of ffmpeg's decoding of the x264 stream bmx wrapped, before its parameter sets were stripped
            assertEquals("766c12fb2b3ecf1ee28d22bc440b861d147c638b6508a9cc8e0b93b1ea720e38", sha256(decoded));
        }

        @Test
        @DisplayName("DNxHD 1080p CID 1253: the track of the VC-3 coding label and the frame header")
        void vc3() {
            var demuxed = read("bmx-op1a-vc3.mxf");
            assertEquals(2, demuxed.tracks().size());
            var video = assertInstanceOf(VideoTrack.class, demuxed.tracks().get(0));
            assertEquals(Codec.DNXHD, video.codec());
            assertEquals(1920, video.width());
            assertEquals(1080, video.height());
            assertArrayEquals("AVdn".getBytes(), video.codecConfig().orElseThrow());
            var packets = demuxed.packetsOf(video);
            assertEquals(1, packets.size());
            assertEquals(188_416, packets.getFirst().size(), "the CID 1253 frame size");
            var audio = assertInstanceOf(AudioTrack.class, demuxed.tracks().get(1));
            assertEquals(Codec.PCM_S16LE, audio.codec());
            assertEquals(1920, demuxed.packetsOf(audio).stream().mapToInt(Packet::size).sum() / 2);
        }

        @Test
        @DisplayName("ProRes 422 with stereo PCM as two mono tracks")
        void prores() {
            var demuxed = read("bmx-op1a-prores.mxf");
            assertEquals(3, demuxed.tracks().size());
            var video = assertInstanceOf(VideoTrack.class, demuxed.tracks().getFirst());
            assertEquals(Codec.PRORES, video.codec());
            assertArrayEquals("apcn".getBytes(), video.codecConfig().orElseThrow());
            assertEquals(5, demuxed.packetsOf(video).size());
            for (var index = 1; index < 3; index++) {
                var audio = assertInstanceOf(AudioTrack.class, demuxed.tracks().get(index));
                assertEquals(Codec.PCM_S24LE, audio.codec());
                assertEquals(1, audio.layout().orElseThrow().channels());
                assertEquals(9600, demuxed.packetsOf(audio).stream().mapToInt(Packet::size).sum() / 3);
            }
        }

        @Test
        @DisplayName("clip wrapped PCM: the samples are those ffmpeg reads, cut in timed packets")
        void clipPcm() {
            var demuxed = read("bmx-op1a-clip-pcm.mxf");
            var audio = assertInstanceOf(AudioTrack.class, demuxed.tracks().getFirst());
            assertEquals(Codec.PCM_S24LE, audio.codec());
            assertEquals(2, audio.layout().orElseThrow().channels());
            var pcm = new java.io.ByteArrayOutputStream();
            var expectedPts = 0L;
            for (var packet : demuxed.packetsOf(audio)) {
                assertEquals(expectedPts, packet.pts());
                assertEquals(packet.size() / 6, packet.duration());
                assertTrue(packet.duration() <= 4800, "at most 100 ms per packet");
                expectedPts += packet.duration();
                pcm.writeBytes(packet.data());
            }
            assertEquals(9600, expectedPts);
            Oracle.assume(Oracle.Tool.FFMPEG);
            var reference = Oracle.run(Oracle.Tool.FFMPEG, java.time.Duration.ofMinutes(1), List.of("-v", "error",
                    "-i", SAMPLES.resolve("bmx-op1a-clip-pcm.mxf").toString(), "-f", "s24le", "-"));
            assertArrayEquals(reference, pcm.toByteArray());
        }
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new AssertionError(exception);
        }
    }

    @Nested
    @DisplayName("damaged files")
    class Damaged {
        @Test
        @DisplayName("no header partition pack: not an MXF file")
        void notMxf() {
            assertThrows(MediaException.Malformed.class, () -> new MxfDemuxer(MediaSource.of(new byte[4096])));
        }

        @Test
        @DisplayName("a length of more than eight bytes is malformed")
        void length() {
            var data = Fixtures.read(SAMPLES.resolve("op1a-prores.mxf"));
            data[16] = (byte) 0x89;
            assertThrows(MediaException.Malformed.class, () -> new MxfDemuxer(MediaSource.of(data)));
        }

        @Test
        @DisplayName("header metadata without its primer pack is malformed")
        void primer() {
            var data = Fixtures.read(SAMPLES.resolve("op1a-prores.mxf"));
            for (var at = 0; at + 16 < data.length; at++) {
                if (data[at] == 0x06 && data[at + 1] == 0x0E && data[at + 4] == 0x02 && data[at + 5] == 0x05
                        && data[at + 13] == 0x05 && data[at + 14] == 0x01) {
                    data[at + 13] = 0x7F;
                }
            }
            assertThrows(MediaException.class, () -> read(data));
        }

        @Test
        @DisplayName("truncated within the essence: every whole element before the cut is read")
        void truncated() {
            var data = Fixtures.read(SAMPLES.resolve("op1a-dnxhr.mxf"));
            var full = read(data);
            var video = full.tracks().getFirst();
            var cut = Arrays.copyOf(data, data.length / 2);
            var partial = read(cut);
            var frames = partial.packetsOf(partial.tracks().getFirst());
            assertTrue(!frames.isEmpty() && frames.size() < full.packetsOf(video).size());
            for (var index = 0; index < frames.size(); index++) {
                assertArrayEquals(full.packetsOf(video).get(index).data(), frames.get(index).data());
            }
        }

        @Test
        @DisplayName("a stream read once, front to back, gives the packets of a random-access read")
        void forwardOnly() {
            var data = Fixtures.read(SAMPLES.resolve("op1a-h264.mxf"));
            var expected = read(data);
            try (var demuxer = new MxfDemuxer(MediaSource.of(new java.io.ByteArrayInputStream(data), data.length))) {
                var actual = ContainerProbe.demuxAll(demuxer);
                assertEquals(expected.tracks(), actual.tracks());
                MuxSupport.assertSamePayloads(expected, actual, ids(expected));
            }
        }
    }

    /** {@return the identity map of the track ids of a demuxed file} */
    static java.util.Map<Integer, Integer> ids(ContainerProbe.Demuxed demuxed) {
        var map = new java.util.HashMap<Integer, Integer>();
        for (var track : demuxed.tracks()) {
            map.put(track.id(), track.id());
        }
        return map;
    }
}
