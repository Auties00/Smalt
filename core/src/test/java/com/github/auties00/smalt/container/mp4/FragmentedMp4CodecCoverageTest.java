package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import com.github.auties00.smalt.track.TrackKind;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the codecs {@link FragmentedMp4Muxer} writes besides the common ones (PCM, ProRes, DNxHD,
 * VP8, MPEG-4 Part 2, H.263, Motion-JPEG, MP2 and the text subtitle tracks) and its hierarchical
 * single segment index. Fixtures are encoded by ffmpeg, demuxed by Smalt and muxed into fragments;
 * Smalt and ffprobe must read the same packets back, and ffmpeg must decode the same audio.
 */
@DisplayName("FragmentedMp4Muxer codec coverage")
class FragmentedMp4CodecCoverageTest {
    record Case(String name, Supplier<Path> fixture, Codec codec, String probeCodec) {
        @Override
        public String toString() {
            return name;
        }
    }

    static List<String> video(int width, int height) {
        return Mp4Fixtures.video(width, height, 1.2, 25);
    }

    static List<Case> cases() {
        var tone = Mp4Fixtures.audio(48000, 1.2);
        return List.of(
                new Case("pcm_s24le", () -> Fixtures.ffmpeg("wav", tone, List.of("-c:a", "pcm_s24le", "-ac", "2")),
                        Codec.PCM_S24LE, "pcm_s24le"),
                new Case("pcm_f32be", () -> Fixtures.ffmpeg("mov", tone, List.of("-c:a", "pcm_f32be")),
                        Codec.PCM_F32BE, "pcm_f32be"),
                new Case("mp2", () -> Fixtures.ffmpeg("mp4", tone, List.of("-c:a", "mp2")), Codec.MP2, "mp3"),
                new Case("prores", () -> Fixtures.ffmpeg("mov", video(320, 240), List.of("-c:v", "prores_ks", "-profile:v", "3")),
                        Codec.PRORES, "prores"),
                new Case("dnxhr", () -> Fixtures.ffmpeg("mov", video(320, 240), List.of("-c:v", "dnxhd", "-profile:v", "dnxhr_lb",
                        "-pix_fmt", "yuv422p")), Codec.DNXHD, "dnxhd"),
                new Case("vp8", () -> Fixtures.ffmpeg("webm", video(176, 144), List.of("-c:v", "libvpx", "-b:v", "300k", "-g", "10",
                        "-auto-alt-ref", "0")), Codec.VP8, "vp8"),
                new Case("mpeg4", () -> Fixtures.ffmpeg("mp4", video(176, 144), List.of("-c:v", "mpeg4", "-bf", "2", "-g", "10")),
                        Codec.MPEG4_PART2, "mpeg4"),
                new Case("h263", () -> Fixtures.ffmpeg("3gp", video(176, 144), List.of("-c:v", "h263", "-g", "10")),
                        Codec.H263, "h263"),
                new Case("mjpeg", () -> Fixtures.ffmpeg("mov", video(160, 120), List.of("-c:v", "mjpeg")),
                        Codec.MJPEG, "mjpeg"),
                new Case("jpeg2000", () -> Fixtures.ffmpeg("mov", video(160, 120), List.of("-c:v", "jpeg2000",
                        "-pix_fmt", "yuv420p")), Codec.JPEG2000, "jpeg2000"));
    }

    static byte[] concatenate(List<Packet> packets) {
        var out = new ByteArrayOutputStream();
        packets.forEach(packet -> out.writeBytes(packet.data()));
        return out.toByteArray();
    }

    static String decodedMd5(Path file, String map) {
        // ffmpeg applies no edit list to a fragmented file, so both sides decode every sample
        var arguments = new ArrayList<>(List.of("-v", "error"));
        var name = file.getFileName().toString();
        if (name.endsWith(".mp4") || name.endsWith(".mov")) {
            arguments.addAll(List.of("-ignore_editlist", "1"));
        }
        arguments.addAll(List.of("-i", file.toString(), "-map", map, "-c:a", "pcm_f64le", "-f", "md5", "-"));
        var output = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(2), arguments);
        return new String(output, StandardCharsets.US_ASCII).trim();
    }

    static Track only(ContainerProbe.Demuxed demuxed, Codec codec) {
        return demuxed.tracks().stream().filter(track -> track.codec() == codec).findFirst().orElseThrow();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    @DisplayName("fragments the packets so that Smalt, ffprobe and ffmpeg read them back unchanged")
    void roundTrip(Case fixture) {
        Oracle.assume(Oracle.Tool.FFMPEG);
        var input = fixture.fixture().get();
        var source = MuxSupport.demux(Fixtures.read(input));
        var sourceTrack = only(source, fixture.codec());
        var output = MuxSupport.toMemory(List.of(sourceTrack), source.packetsOf(sourceTrack),
                sink -> new FragmentedMp4Muxer(sink, FragmentedMp4MuxerOptions.builder().fragmentMicros(300_000).build()));
        var moofs = FragmentedMp4MuxerTest.boxes(output).stream().filter(box -> box.type().equals("moof")).count();
        assertTrue(moofs >= 3, "several fragments, got " + moofs);
        var result = MuxSupport.demux(output);
        var track = only(result, fixture.codec());
        var before = source.packetsOf(sourceTrack);
        var after = result.packetsOf(track);
        var path = MuxSupport.save("fragmented-" + fixture.name() + ".mp4", output);
        if (fixture.codec().isPcm()) {
            assertArrayEquals(concatenate(before), concatenate(after), "PCM samples");
            assertEquals(((AudioTrack) sourceTrack).sampleRate(), ((AudioTrack) track).sampleRate());
        } else {
            assertEquals(before.size(), after.size(), "packet count");
            for (var index = 0; index < before.size(); index++) {
                var expected = before.get(index).data();
                if (fixture.codec() == Codec.PRORES && !Mp4Muxer.hasProResPrefix(expected)) {
                    expected = Mp4Muxer.withProResPrefix(expected);
                }
                assertArrayEquals(expected, after.get(index).data(), "packet " + index);
                assertEquals(before.get(index).keyframe(), after.get(index).keyframe(), "keyframe " + index);
                assertEquals(sourceTrack.timeBase().rescale(before.get(index).pts(), Rational.timeBaseOf(1_000_000)),
                        track.timeBase().rescale(after.get(index).pts(), Rational.timeBaseOf(1_000_000)), 1000, "pts " + index);
            }
        }
        var probe = ContainerProbe.probe(path);
        assertEquals(fixture.probeCodec(), probe.streams().getFirst().codecName(), "ffprobe codec");
        if (fixture.codec().kind() == TrackKind.AUDIO) {
            assertEquals(decodedMd5(input, "0:a:0"), decodedMd5(path, "0:a:0"), "ffmpeg decode");
        } else {
            var original = ContainerProbe.probe(input);
            var stream = original.streams().stream().filter(candidate -> "video".equals(candidate.get("codec_type")))
                    .findFirst().orElseThrow();
            var hashes = original.packetsOf(stream.index()).stream().map(ContainerProbe.ProbePacket::hash).toList();
            assertEquals(hashes, probe.packetsOf(0).stream().map(ContainerProbe.ProbePacket::hash).toList(),
                    "ffprobe packet payloads");
        }
    }

    @Nested
    @DisplayName("text subtitle tracks")
    class Text {
        static Path subtitled(String extension, String codec) {
            try {
                var directory = Files.createDirectories(Path.of("target", "media-fixtures"));
                var srt = directory.resolve("fragment-cues.srt");
                Files.writeString(srt, """
                        1
                        00:00:00,200 --> 00:00:00,900
                        First cue

                        2
                        00:00:00,700 --> 00:00:01,600
                        Overlapping cue

                        3
                        00:00:02,100 --> 00:00:02,800
                        After a gap
                        """, StandardCharsets.UTF_8);
                var inputs = new ArrayList<>(Mp4Fixtures.video(160, 120, 3, 25));
                inputs.addAll(List.of("-i", srt.toString()));
                return Fixtures.ffmpeg(extension, inputs, List.of("-map", "0:v", "-map", "1:s", "-c:v", "libx264", "-g", "25",
                        "-c:s", codec));
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.github.auties00.smalt.container.mp4.FragmentedMp4CodecCoverageTest#textCodecs")
        @DisplayName("writes the same text samples as the progressive muxer, which ffprobe reads alike")
        void sameSamples(String ffmpegCodec, Codec codec, String probeCodec) {
            Oracle.assume(Oracle.Tool.FFMPEG);
            var source = MuxSupport.demux(Fixtures.read(subtitled(codec == Codec.MOV_TEXT ? "mp4" : "mkv", ffmpegCodec)));
            var tracks = source.tracks();
            assertEquals(codec, only(source, codec).codec());
            var packets = source.order();
            var fragmented = MuxSupport.toMemory(tracks, packets,
                    sink -> new FragmentedMp4Muxer(sink, FragmentedMp4MuxerOptions.builder().fragmentMicros(500_000).build()));
            var progressive = MuxSupport.toMemory(tracks, packets, sink -> new Mp4Muxer(sink));
            var fromFragments = MuxSupport.demux(fragmented);
            var fromMovie = MuxSupport.demux(progressive);
            var textA = only(fromFragments, codec);
            var textB = only(fromMovie, codec);
            var samplesA = fromFragments.packetsOf(textA);
            var samplesB = fromMovie.packetsOf(textB);
            assertEquals(samplesB.size(), samplesA.size(), "text samples");
            for (var index = 0; index < samplesA.size(); index++) {
                assertArrayEquals(samplesB.get(index).data(), samplesA.get(index).data(), "sample " + index);
                assertEquals(textB.timeBase().rescale(samplesB.get(index).pts(), Rational.timeBaseOf(1000)),
                        textA.timeBase().rescale(samplesA.get(index).pts(), Rational.timeBaseOf(1000)), "start " + index);
                assertEquals(textB.timeBase().rescale(samplesB.get(index).duration(), Rational.timeBaseOf(1000)),
                        textA.timeBase().rescale(samplesA.get(index).duration(), Rational.timeBaseOf(1000)), "duration " + index);
            }
            var fragmentedPath = MuxSupport.save("fragmented-" + probeCodec + ".mp4", fragmented);
            var progressivePath = MuxSupport.save("progressive-" + probeCodec + ".mp4", progressive);
            // ffmpeg reports no duration for the text samples of a fragment and has no WebVTT-in-MP4 reader,
            // so ffprobe is compared on the sample entry, times, sizes and payloads
            var probe = ContainerProbe.probe(fragmentedPath);
            var reference = ContainerProbe.probe(progressivePath);
            var stream = probe.streams().stream().filter(candidate -> probeCodec.equals(candidate.get("codec_tag_string")))
                    .findFirst().orElseThrow();
            var expected = reference.packetsOf(stream.index());
            var actual = probe.packetsOf(stream.index());
            assertEquals(expected.size(), actual.size(), "ffprobe text samples");
            for (var index = 0; index < expected.size(); index++) {
                assertEquals(expected.get(index).hash(), actual.get(index).hash(), "ffprobe sample " + index);
                assertEquals(expected.get(index).pts() * reference.streams().get(stream.index()).timeBaseNumerator()
                                * probe.streams().get(stream.index()).timeBaseDenominator(),
                        actual.get(index).pts() * probe.streams().get(stream.index()).timeBaseNumerator()
                                * reference.streams().get(stream.index()).timeBaseDenominator(), "ffprobe start " + index);
            }
            if (codec == Codec.MOV_TEXT) {
                assertEquals("mov_text", stream.codecName());
            }
            assertTrue(new String(fragmented, StandardCharsets.ISO_8859_1).contains("Overlapping cue"));
        }
    }

    static List<org.junit.jupiter.params.provider.Arguments> textCodecs() {
        return List.of(org.junit.jupiter.params.provider.Arguments.of("mov_text", Codec.MOV_TEXT, "tx3g"),
                org.junit.jupiter.params.provider.Arguments.of("webvtt", Codec.WEBVTT, "wvtt"));
    }

    @Nested
    @DisplayName("hierarchical segment index")
    class Hierarchical {
        static final int FRAGMENTS = 70_000;

        static int u32(byte[] data, long offset) {
            var at = (int) offset;
            return (data[at] & 0xFF) << 24 | (data[at + 1] & 0xFF) << 16 | (data[at + 2] & 0xFF) << 8 | data[at + 3] & 0xFF;
        }

        static long u64(byte[] data, long offset) {
            return (u32(data, offset) & 0xFFFFFFFFL) << 32 | u32(data, offset + 4) & 0xFFFFFFFFL;
        }

        static String type(byte[] data, long offset) {
            return new String(data, (int) offset + 4, 4, StandardCharsets.US_ASCII);
        }

        /** Reads a sidx and returns its references as (type, size, duration, sap) rows. */
        static List<long[]> references(byte[] data, long offset) {
            assertEquals("sidx", type(data, offset));
            var count = u32(data, offset + 36) & 0xFFFF;
            var rows = new ArrayList<long[]>();
            for (var index = 0; index < count; index++) {
                var at = offset + 40 + 12L * index;
                var first = u32(data, at) & 0xFFFFFFFFL;
                rows.add(new long[]{first >>> 31, first & 0x7FFFFFFFL, u32(data, at + 4) & 0xFFFFFFFFL,
                        (u32(data, at + 8) & 0xFFFFFFFFL) >>> 31});
            }
            return rows;
        }

        @Test
        @DisplayName("indexes 70000 fragments with a two-level sidx that Smalt and ffprobe follow")
        void seventyThousandFragments() {
            var track = AudioTrack.builder().id(1).codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(8000)).sampleRate(8000)
                    .layout(ChannelLayout.MONO).build();
            var random = new Random(70_000);
            var packets = new ArrayList<Packet>(FRAGMENTS);
            for (var index = 0; index < FRAGMENTS; index++) {
                var data = new byte[160];
                random.nextBytes(data);
                packets.add(new Packet(1, data, 80L * index, 80L * index, 80, true));
            }
            var options = FragmentedMp4MuxerOptions.builder().fragmentMicros(1)
                    .segmentIndex(FragmentedMp4MuxerOptions.SegmentIndex.SINGLE).randomAccessIndex(true).build();
            var sink = MediaSink.memory();
            FragmentedMp4Muxer.IndexLayout layout;
            try (var muxer = new FragmentedMp4Muxer(sink, options)) {
                muxer.addTrack(track);
                packets.forEach(muxer::writePacket);
                muxer.finish();
                layout = muxer.indexLayout().orElseThrow();
            }
            var data = sink.toByteArray();
            assertEquals(FRAGMENTS, layout.subsegments().size());
            var root = references(data, layout.indexOffset());
            assertEquals(2, root.size(), "two groups");
            var child = layout.indexOffset() + layout.indexSize();
            var leaf = 0;
            var earliest = 0L;
            for (var group : root) {
                assertEquals(1, group[0], "the top sidx references sidx boxes");
                assertEquals(1, group[3], "every fragment of the group starts with a stream access point");
                assertEquals(earliest, u64(data, child + 20), "group earliest presentation time");
                var start = child;
                var rows = references(data, child);
                var position = child + (u32(data, child) & 0xFFFFFFFFL);
                for (var row : rows) {
                    assertEquals(0, row[0], "groups reference fragments");
                    assertEquals(80, row[2], "fragment duration");
                    assertEquals(1, row[3], "starts with a stream access point");
                    var subsegment = layout.subsegments().get(leaf++);
                    assertEquals(position, subsegment.offset(), "subsegment offset");
                    assertEquals("moof", type(data, position));
                    assertEquals(row[1], subsegment.size());
                    position += row[1];
                    earliest += 80;
                }
                assertEquals(group[1], position - start, "group size covers its sidx and fragments");
                assertEquals(80L * rows.size(), group[2], "group duration");
                child = position;
            }
            assertEquals(FRAGMENTS, leaf);
            assertEquals(65_535, references(data, layout.indexOffset() + layout.indexSize()).size());
            assertEquals("mfra", type(data, child));
            var demuxed = MuxSupport.demux(data);
            var read = demuxed.packetsOf(demuxed.tracks().getFirst());
            assertEquals(FRAGMENTS, read.size());
            for (var index = 0; index < FRAGMENTS; index += 997) {
                assertArrayEquals(packets.get(index).data(), read.get(index).data(), "packet " + index);
                assertEquals(80L * index, read.get(index).pts(), "pts " + index);
            }
            var tfra = child + 8;
            assertEquals("tfra", type(data, tfra));
            var entries = u32(data, tfra + 20);
            assertEquals(FRAGMENTS, entries);
            for (var index = 0; index < entries; index += 1009) {
                var moof = u64(data, tfra + 24 + 19L * index + 8);
                assertEquals(layout.subsegments().get(index).offset(), moof, "tfra entry " + index);
            }
            Oracle.assume(Oracle.Tool.FFPROBE);
            // ffmpeg does not implement sidx references to sidx boxes and refuses the file, so ffprobe reads a copy
            // whose sidx boxes are turned into free boxes of the same size: the fragments and the mfra stay in place
            var hidden = data.clone();
            hideIndexes(hidden, layout.indexOffset());
            var path = MuxSupport.save("fragmented-70000.mp4", hidden);
            var count = new String(Oracle.run(Oracle.Tool.FFPROBE, Duration.ofMinutes(2), List.of("-v", "error",
                    "-count_packets", "-show_entries", "stream=nb_read_packets", "-of", "csv=p=0", path.toString())),
                    StandardCharsets.US_ASCII).trim();
            assertEquals(Integer.toString(FRAGMENTS), count, "ffprobe reads every fragment");
            assertEquals(decodedMd5(path, "0:a:0"), decodedMd5(MuxSupport.save("fragmented-70000-flat.mp4",
                    MuxSupport.toMemory(List.of(track), packets, Mp4Muxer::new)), "0:a:0"), "ffmpeg decode");
        }

        static void hideIndexes(byte[] data, long offset) {
            var position = offset;
            while (position + 8 <= data.length) {
                if (type(data, position).equals("sidx")) {
                    System.arraycopy("free".getBytes(StandardCharsets.US_ASCII), 0, data, (int) position + 4, 4);
                }
                position += u32(data, position) & 0xFFFFFFFFL;
            }
        }

        @Test
        @DisplayName("splits groups so that every reference fits its 31-bit size and 32-bit duration")
        void groupLimits() {
            var level = new FragmentedMp4Muxer.IndexLevel(10, false);
            for (var index = 0; index < 10; index++) {
                level.sizes[index] = 800_000_000L;
                level.durations[index] = 1_000_000_000L;
                level.earliest[index] = 1_000_000_000L * index;
                level.sap[index] = index != 3;
            }
            var groups = level.group();
            // three 800 MB fragments exceed 2^31 - 1 bytes, so groups hold two
            assertEquals(5, groups.count);
            for (var group = 0; group < groups.count; group++) {
                assertEquals(2, groups.childCount[group]);
                assertEquals(2 * group, groups.firstChild[group]);
                assertEquals(FragmentedMp4Muxer.IndexLevel.boxSize(2) + 1_600_000_000L, groups.sizes[group]);
                assertEquals(2_000_000_000L, groups.durations[group]);
                assertEquals(2_000_000_000L * group, groups.earliest[group]);
                // a reference to a sidx starts with a SAP only when every subsegment it covers does
                assertEquals(group != 1, groups.sap[group], "group " + group + " starts with a SAP");
            }
            var long_ = new FragmentedMp4Muxer.IndexLevel(5, false);
            for (var index = 0; index < 5; index++) {
                long_.sizes[index] = 1000;
                long_.durations[index] = 3_000_000_000L;
            }
            // two durations of 3e9 ticks exceed 2^32 - 1, so each group holds one fragment
            assertEquals(5, long_.group().count);
            var huge = new FragmentedMp4Muxer.IndexLevel(1, false);
            huge.sizes[0] = 0x7FFFFFFFL;
            assertThrows(MediaException.Unsupported.class, huge::group);
        }

        @Test
        @DisplayName("builds as many levels as the references need, and fails when a level cannot shrink")
        void treeLevels() {
            var leaves = new FragmentedMp4Muxer.IndexLevel(FRAGMENTS, false);
            for (var index = 0; index < FRAGMENTS; index++) {
                leaves.sizes[index] = 1000;
                leaves.durations[index] = 1000;
                leaves.sap[index] = true;
            }
            var levels = FragmentedMp4Muxer.IndexLevel.tree(leaves);
            assertEquals(2, levels.size());
            assertEquals(2, levels.getLast().count);
            var few = new FragmentedMp4Muxer.IndexLevel(65_535, false);
            assertEquals(1, FragmentedMp4Muxer.IndexLevel.tree(few).size(), "65535 fragments need one sidx");
            // fragments of more than 2^31 ticks cannot be paired, so grouping them never reduces the count
            var tooLong = new FragmentedMp4Muxer.IndexLevel(FRAGMENTS, false);
            Arrays.fill(tooLong.durations, 2_200_000_000L);
            Arrays.fill(tooLong.sizes, 1000);
            assertThrows(MediaException.Unsupported.class, () -> FragmentedMp4Muxer.IndexLevel.tree(tooLong));
        }
    }
}
