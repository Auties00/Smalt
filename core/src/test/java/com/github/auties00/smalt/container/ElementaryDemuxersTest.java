package com.github.auties00.smalt.container;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.raw.AnnexBDemuxer;
import com.github.auties00.smalt.container.raw.ElementaryStreamOptions;
import com.github.auties00.smalt.container.raw.Mpeg4VideoDemuxer;
import com.github.auties00.smalt.container.raw.ObuDemuxer;
import com.github.auties00.smalt.container.raw.PcmDemuxer;
import com.github.auties00.smalt.container.raw.PcmDemuxerOptions;
import com.github.auties00.smalt.container.raw.RawMuxer;
import com.github.auties00.smalt.container.raw.RawMuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.TrackKind;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the demuxers of elementary streams ({@link AnnexBDemuxer}, {@link ObuDemuxer}, {@link Mpeg4VideoDemuxer},
 * {@link PcmDemuxer}): round trips through {@link RawMuxer} against the MP4 packets they came from, recognition by
 * {@link Containers}, ffprobe's packet count and key frames, seeking against a linear read, and truncated and
 * damaged input.
 */
@DisplayName("Elementary-stream demuxers")
class ElementaryDemuxersTest {
    private static final List<String> X264 = List.of("-c:v", "libx264", "-preset", "fast", "-bf", "3", "-b_strategy",
            "0", "-g", "15", "-x264-params", "b-pyramid=normal:threads=1", "-an");

    private static final List<String> X265 = List.of("-c:v", "libx265", "-x265-params",
            "log-level=none:keyint=15:min-keyint=15:bframes=3:b-pyramid=1:pools=1:frame-threads=1", "-an");

    private static final List<String> AOM = List.of("-c:v", "libaom-av1", "-cpu-used", "8", "-usage", "good",
            "-g", "12", "-lag-in-frames", "8", "-an");

    static ContainerProbe.Demuxed video(byte[] file) {
        var demuxed = MuxSupport.demux(file);
        var track = demuxed.tracks().stream().filter(candidate -> candidate.kind() == TrackKind.VIDEO).findFirst()
                .orElseThrow();
        return new ContainerProbe.Demuxed(List.of(track), Map.of(track.id(), demuxed.packetsOf(track)),
                demuxed.packetsOf(track), demuxed.metadata(), demuxed.durationMicros());
    }

    static List<Packet> readAll(Demuxer demuxer) {
        var packets = new ArrayList<Packet>();
        for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
            packets.add(packet);
        }
        return packets;
    }

    /**
     * Splits an access unit of four-byte length-prefixed NAL units, leaving out parameter sets and delimiters.
     */
    static List<byte[]> slicesAndSei(byte[] data, boolean hevc) {
        var units = new ArrayList<byte[]>();
        var position = 0;
        while (position + 4 <= data.length) {
            var length = (data[position] & 0xFF) << 24 | (data[position + 1] & 0xFF) << 16
                    | (data[position + 2] & 0xFF) << 8 | (data[position + 3] & 0xFF);
            var unit = Arrays.copyOfRange(data, position + 4, position + 4 + length);
            position += 4 + length;
            var type = hevc ? (unit[0] >> 1) & 0x3F : unit[0] & 0x1F;
            // parameter sets, delimiters and, for HEVC, the SEI messages an hvcC record may hold, which RawMuxer
            // writes before key frames with the parameter sets
            var parameterSet = hevc ? type >= 32 && type <= 35 || type == 39 : type == 7 || type == 8 || type == 9;
            if (!parameterSet) {
                units.add(unit);
            }
        }
        assertEquals(data.length, position, "length-prefixed units fill the access unit");
        return units;
    }

    /**
     * Returns the indices of packets in presentation order.
     */
    static int[] presentationOrder(List<Packet> packets) {
        return IntStream.range(0, packets.size()).boxed()
                .sorted(Comparator.comparingLong((Integer index) -> packets.get(index).pts()).thenComparing(index -> index))
                .mapToInt(Integer::intValue).toArray();
    }

    static byte[] annexB(ContainerProbe.Demuxed source) {
        return MuxSupport.toStream(source.tracks(), source.order(), RawMuxer::new);
    }

    @Nested
    @DisplayName("H.264 and HEVC Annex B")
    class AnnexB {
        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"h264", "hevc"})
        @DisplayName("reads back the access units, key frames and presentation order of the MP4 packets RawMuxer wrote")
        void roundTrip(String codec) {
            var hevc = codec.equals("hevc");
            var source = video(Fixtures.read(Fixtures.testVideo("mp4", 96, 64, 2, hevc ? X265 : X264)));
            var expected = source.order();
            var stream = annexB(source);
            assertEquals(hevc ? ContainerFormat.HEVC : ContainerFormat.H264,
                    Containers.detect(MediaSource.of(stream)).orElseThrow());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(stream))) {
                assertEquals(hevc ? ContainerFormat.HEVC : ContainerFormat.H264, demuxer.format());
                var track = (VideoTrack) demuxer.tracks().getFirst();
                var original = (VideoTrack) source.tracks().getFirst();
                assertEquals(original.codec(), track.codec());
                assertEquals(96, track.width());
                assertEquals(64, track.height());
                assertEquals(new Rational(30, 1), track.frameRate().orElseThrow());
                assertEquals(new Rational(1, 60), track.timeBase());
                var actual = readAll(demuxer);
                assertEquals(expected.size(), actual.size(), "access units");
                for (var i = 0; i < actual.size(); i++) {
                    var want = slicesAndSei(expected.get(i).data(), hevc);
                    var got = slicesAndSei(actual.get(i).data(), hevc);
                    assertEquals(want.size(), got.size(), "NAL units of access unit " + i);
                    for (var unit = 0; unit < want.size(); unit++) {
                        assertArrayEquals(want.get(unit), got.get(unit), "NAL unit " + unit + " of access unit " + i);
                    }
                    assertEquals(expected.get(i).keyframe(), actual.get(i).keyframe(), "key flag of " + i);
                    assertEquals(2, actual.get(i).duration());
                    assertTrue(actual.get(i).pts() >= actual.get(i).dts(), "pts at or after dts at " + i);
                    if (i > 0) {
                        assertTrue(actual.get(i).dts() > actual.get(i - 1).dts(), "increasing dts at " + i);
                    }
                }
                assertArrayEquals(presentationOrder(expected), presentationOrder(actual), "presentation order");
                var times = actual.stream().mapToLong(Packet::pts).sorted().toArray();
                for (var i = 0; i < times.length; i++) {
                    assertEquals(2L * i, times[i], "presentation times are consecutive frames");
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"h264", "hevc"})
        @DisplayName("finds the access units and key frames ffprobe finds in an ffmpeg-written stream")
        void ffprobe(String codec) {
            var hevc = codec.equals("hevc");
            var options = new ArrayList<>(hevc ? X265 : X264);
            options.addAll(List.of("-f", hevc ? "hevc" : "h264"));
            var file = Fixtures.testVideo(hevc ? "h265" : "h264", 128, 96, 2, options);
            var probe = ContainerProbe.probe(file);
            var ours = ContainerProbe.demux(file, AnnexBDemuxer::new);
            var expected = probe.packets();
            var actual = ours.order();
            assertEquals(expected.size(), actual.size(), "packets");
            for (var i = 0; i < actual.size(); i++) {
                assertEquals(expected.get(i).key(), actual.get(i).keyframe(), "key flag of " + i);
            }
        }

        @Test
        @DisplayName("takes the frame rate from the options, else the VUI")
        void frameRate() {
            var source = video(Fixtures.read(Fixtures.testVideo("mp4", 96, 64, 1, X264)));
            var stream = annexB(source);
            var options = ElementaryStreamOptions.builder().frameRate(new Rational(50, 1)).build();
            try (var demuxer = new AnnexBDemuxer(MediaSource.of(stream), options)) {
                var track = (VideoTrack) demuxer.tracks().getFirst();
                assertEquals(new Rational(1, 100), track.timeBase());
                assertEquals(new Rational(50, 1), track.frameRate().orElseThrow());
            }
            var fixed = ElementaryStreamOptions.builder().frameRate(new Rational(30000, 1001)).build();
            try (var demuxer = new AnnexBDemuxer(MediaSource.of(stream), fixed)) {
                assertEquals(new Rational(1001, 60000), demuxer.tracks().getFirst().timeBase());
            }
            assertTrue(ElementaryStreamOptions.defaults().frameRate().isEmpty());
            assertThrows(IllegalArgumentException.class,
                    () -> ElementaryStreamOptions.builder().frameRate(new Rational(0, 1)));
            assertTrue(fixed.toBuilder().automaticFrameRate().build().frameRate().isEmpty());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"h264", "hevc"})
        @DisplayName("seeks to the last key frame at or before a time, then reads what a linear read reads from there")
        void seek(String codec) {
            var hevc = codec.equals("hevc");
            var source = video(Fixtures.read(Fixtures.testVideo("mp4", 96, 64, 2, hevc ? X265 : X264)));
            var stream = annexB(source);
            List<Packet> linear;
            try (var demuxer = new AnnexBDemuxer(MediaSource.of(stream))) {
                linear = readAll(demuxer);
            }
            try (var demuxer = new AnnexBDemuxer(MediaSource.of(stream))) {
                assertTrue(demuxer.isSeekable());
                for (var seconds : new double[]{1.25, 0.0, 0.6, 1.9, 5.0, -1.0}) {
                    demuxer.seek((long) (seconds * 1_000_000));
                    var after = readAll(demuxer);
                    var target = (long) Math.floor(seconds * 60);
                    if (seconds >= 2) {
                        assertTrue(after.isEmpty(), "nothing after the end");
                        continue;
                    }
                    var expectedStart = -1;
                    for (var i = 0; i < linear.size(); i++) {
                        var packet = linear.get(i);
                        if (packet.keyframe() && packet.pts() <= Math.max(0, target)
                                && (expectedStart < 0 || packet.pts() > linear.get(expectedStart).pts())) {
                            expectedStart = i;
                        }
                    }
                    var expected = linear.subList(expectedStart, linear.size());
                    assertEquals(expected.size(), after.size(), "packets after a seek to " + seconds);
                    for (var i = 0; i < after.size(); i++) {
                        var context = "packet " + i + " after a seek to " + seconds + " (linear " + (expectedStart + i)
                                + "): expected pts " + expected.stream().map(Packet::pts).toList() + ", got "
                                + after.stream().map(Packet::pts).toList();
                        assertArrayEquals(expected.get(i).data(), after.get(i).data(), context);
                        assertEquals(expected.get(i).pts(), after.get(i).pts(), context);
                        assertEquals(expected.get(i).dts(), after.get(i).dts(), context);
                    }
                }
                assertEquals(2_000_000, demuxer.durationMicros());
            }
        }

        @Test
        @DisplayName("is not taken for MPEG-2 video, MPEG-PS, MPEG-4 Part 2 or random bytes")
        void detection() {
            var mpeg2 = Fixtures.read(Fixtures.testVideo("m2v", 96, 64, 1, List.of("-c:v", "mpeg2video", "-an",
                    "-f", "mpeg2video")));
            assertTrue(Containers.detect(MediaSource.of(mpeg2)).isEmpty(), "MPEG-2 video");
            assertTrue(AnnexBDemuxer.detect(mpeg2).isEmpty());
            var ps = Fixtures.read(Fixtures.testVideo("mpg", 96, 64, 1, List.of("-c:v", "mpeg2video", "-an",
                    "-f", "mpeg")));
            assertTrue(AnnexBDemuxer.detect(ps).isEmpty(), "MPEG program stream");
            var m4v = Fixtures.read(Fixtures.testVideo("m4v", 96, 64, 1, List.of("-c:v", "mpeg4", "-an",
                    "-f", "m4v")));
            assertTrue(AnnexBDemuxer.detect(m4v).isEmpty(), "MPEG-4 Part 2");
            assertEquals(ContainerFormat.MPEG4_VIDEO, Containers.detect(MediaSource.of(m4v)).orElseThrow());
            var random = new Random(7);
            for (var i = 0; i < 200; i++) {
                var bytes = new byte[64 + random.nextInt(4000)];
                random.nextBytes(bytes);
                bytes[0] = 0;
                bytes[1] = 0;
                bytes[2] = 0;
                bytes[3] = 1;
                assertTrue(AnnexBDemuxer.detect(bytes).isEmpty(), "random bytes after a start code");
            }
            assertThrows(MediaException.Malformed.class, () -> new AnnexBDemuxer(MediaSource.of(mpeg2)));
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"h264", "hevc"})
        @DisplayName("reads a truncated or damaged stream to its end without failing outside MediaException")
        void damaged(String codec) {
            var hevc = codec.equals("hevc");
            var stream = annexB(video(Fixtures.read(Fixtures.testVideo("mp4", 96, 64, 1, hevc ? X265 : X264))));
            List<Packet> full;
            try (var demuxer = new AnnexBDemuxer(MediaSource.of(stream))) {
                full = readAll(demuxer);
            }
            var random = new Random(11);
            for (var trial = 0; trial < 60; trial++) {
                var cut = 200 + random.nextInt(stream.length - 200);
                var truncated = Arrays.copyOf(stream, cut);
                try (var demuxer = new AnnexBDemuxer(MediaSource.of(truncated))) {
                    var packets = readAll(demuxer);
                    assertTrue(packets.size() <= full.size());
                    for (var i = 0; i + 1 < packets.size(); i++) {
                        assertArrayEquals(full.get(i).data(), packets.get(i).data(), "packet " + i + " of a cut at " + cut);
                    }
                } catch (MediaException expected) {
                    // a cut before the first parameter sets leaves no stream to read
                }
                var damaged = stream.clone();
                for (var flip = 0; flip < 20; flip++) {
                    damaged[random.nextInt(damaged.length)] ^= (byte) (1 << random.nextInt(8));
                }
                try (var demuxer = new AnnexBDemuxer(MediaSource.of(damaged))) {
                    readAll(demuxer);
                    demuxer.seek(500_000);
                    readAll(demuxer);
                } catch (MediaException expected) {
                    // a damaged stream may be rejected, but only as a MediaException
                }
            }
        }

        @Test
        @DisplayName("opens a stream without key frames without reading it whole first")
        void noKeyFrames() {
            var stream = annexB(video(Fixtures.read(Fixtures.testVideo("mp4", 96, 64, 1, X264))));
            var starts = new ArrayList<Integer>();
            for (var i = 0; i + 3 <= stream.length; i++) {
                if (stream[i] == 0 && stream[i + 1] == 0 && stream[i + 2] == 1) {
                    starts.add(i + 3);
                }
            }
            var parameterSets = new ByteArrayOutputStream();
            var slices = new ByteArrayOutputStream();
            for (var n = 0; n < starts.size(); n++) {
                var from = starts.get(n);
                var to = n + 1 < starts.size() ? starts.get(n + 1) - 3 : stream.length;
                while (to > from && stream[to - 1] == 0) {
                    to--;
                }
                var type = stream[from] & 0x1F;
                var target = type == 7 || type == 8 ? parameterSets : type == 1 ? slices : null;
                if (target != null && (target == slices || parameterSets.size() < 64)) {
                    target.write(new byte[]{0, 0, 0, 1}, 0, 4);
                    target.write(stream, from, to - from);
                }
            }
            var noKeys = new ByteArrayOutputStream();
            noKeys.writeBytes(parameterSets.toByteArray());
            while (noKeys.size() < 4 << 20) {
                noKeys.writeBytes(slices.toByteArray());
            }
            var bytes = noKeys.toByteArray();
            var furthest = new long[1];
            var backing = MediaSource.of(bytes);
            var source = new MediaSource() {
                @Override
                public long size() {
                    return backing.size();
                }

                @Override
                public int read(long position, byte[] buffer, int offset, int length) {
                    var read = backing.read(position, buffer, offset, length);
                    furthest[0] = Math.max(furthest[0], position + Math.max(0, read));
                    return read;
                }

                @Override
                public void close() {
                }
            };
            try (var demuxer = new AnnexBDemuxer(source)) {
                assertTrue(furthest[0] < bytes.length / 2, "opening read " + furthest[0] + " of " + bytes.length
                        + " bytes");
                var packet = demuxer.read();
                assertFalse(packet.keyframe());
                var count = 1;
                while (demuxer.read() != null) {
                    count++;
                }
                assertTrue(count > 1000, count + " packets");
            }
        }
    }

    @Nested
    @DisplayName("AV1 OBU streams")
    class Obu {
        /**
         * Rewrites a low-overhead OBU stream in the length-delimited format of Annex B: a frame unit per frame
         * header or frame OBU with the tile groups after it and the OBUs since the previous frame's (a sequence
         * header or metadata OBU after a frame starts the next unit, OBUs after the last frame join its unit), every
         * OBU without its size field.
         */
        static byte[] toAnnexB(List<byte[]> temporalUnits) {
            var out = new ByteArrayOutputStream();
            for (var unit : temporalUnits) {
                var frames = new ArrayList<ByteArrayOutputStream>();
                var pending = new ByteArrayOutputStream();
                writeObu(pending, new byte[]{0x10});
                var framed = false;
                var position = 0;
                while (position < unit.length) {
                    var header = unit[position] & 0xFF;
                    var headerBytes = (header & 0x04) != 0 ? 2 : 1;
                    var size = 0;
                    var shift = 0;
                    var at = position + headerBytes;
                    while (true) {
                        var b = unit[at++] & 0xFF;
                        size |= (b & 0x7F) << shift;
                        shift += 7;
                        if ((b & 0x80) == 0) {
                            break;
                        }
                    }
                    var obu = new byte[headerBytes + size];
                    obu[0] = (byte) (header & ~0x02);
                    if (headerBytes == 2) {
                        obu[1] = unit[position + 1];
                    }
                    System.arraycopy(unit, at, obu, headerBytes, size);
                    var type = (header >> 3) & 0x0F;
                    var frame = type == 3 || type == 6;
                    if (framed && (frame || type == 1 || type == 5)) {
                        frames.add(pending);
                        pending = new ByteArrayOutputStream();
                        framed = false;
                    }
                    framed |= frame;
                    writeObu(pending, obu);
                    position = at + size;
                }
                if (pending.size() > 0) {
                    if (!framed && !frames.isEmpty()) {
                        frames.getLast().writeBytes(pending.toByteArray());
                    } else {
                        frames.add(pending);
                    }
                }
                var temporal = new ByteArrayOutputStream();
                for (var frame : frames) {
                    leb128(temporal, frame.size());
                    temporal.writeBytes(frame.toByteArray());
                }
                leb128(out, temporal.size());
                out.writeBytes(temporal.toByteArray());
            }
            return out.toByteArray();
        }

        private static void writeObu(ByteArrayOutputStream out, byte[] obu) {
            leb128(out, obu.length);
            out.writeBytes(obu);
        }

        private static void leb128(ByteArrayOutputStream out, int value) {
            do {
                var b = value & 0x7F;
                value >>>= 7;
                out.write(value != 0 ? b | 0x80 : b);
            } while (value != 0);
        }

        @Test
        @DisplayName("writes Annex B frame units that keep each frame header with its tile groups")
        void annexBFrameUnits() {
            // frame header, two tile groups, metadata, a second frame header that shows an existing frame, padding
            var unit = new byte[]{0x1A, 2, 0x10, 0x00, 0x22, 1, 0x55, 0x22, 1, 0x66, 0x2A, 1, 0x01, 0x1A, 1,
                    (byte) 0x80, 0x7A, 1, 0x00};
            var track = VideoTrack.builder().id(1).codec(Codec.AV1).timeBase(new Rational(1, 25)).width(64)
                    .height(64).build();
            var sink = MediaSink.memory();
            var muxer = new RawMuxer(sink, RawMuxerOptions.builder().av1AnnexB(true).build());
            muxer.addTrack(track);
            muxer.writePacket(new Packet(1, unit, 0, 0, 1, false));
            muxer.finish();
            var expected = toAnnexB(List.of(unit));
            assertArrayEquals(expected, sink.toByteArray());
            // temporal unit size, then frame unit 1: delimiter, frame header and both tile groups
            var written = sink.toByteArray();
            assertEquals(written.length - 1, written[0]);
            assertEquals(2 + 4 + 3 + 3, written[1], "the first frame unit holds the tile groups");
            assertEquals(3 + 3 + 3, written[1 + 1 + 12], "the second frame unit holds the metadata, the second frame"
                    + " header and the padding");
            assertEquals(1 + 1 + 12 + 1 + 9, written.length);
        }

        /** Removes the temporal delimiters of a temporal unit, as an MP4 sample carries none. */
        static byte[] withoutDelimiters(byte[] unit) {
            var out = new ByteArrayOutputStream();
            var position = 0;
            while (position < unit.length) {
                var header = unit[position] & 0xFF;
                var at = position + ((header & 0x04) != 0 ? 2 : 1);
                var size = 0;
                var shift = 0;
                while (true) {
                    var b = unit[at++] & 0xFF;
                    size |= (b & 0x7F) << shift;
                    shift += 7;
                    if ((b & 0x80) == 0) {
                        break;
                    }
                }
                if (((header >> 3) & 0x0F) != 2) {
                    out.write(unit, position, at + size - position);
                }
                position = at + size;
            }
            return out.toByteArray();
        }

        @Test
        @DisplayName("reads back the temporal units and key frames of the MP4 packets RawMuxer wrote, in both formats")
        void roundTrip() {
            var source = video(Fixtures.read(Fixtures.testVideo("mp4", 96, 64, 2, AOM)));
            var expected = source.order();
            var lowOverhead = annexB(source);
            var annexB = toAnnexB(expected.stream().map(packet -> packet.data()).toList());
            var written = MuxSupport.toStream(source.tracks(), source.order(),
                    sink -> Containers.openMuxer(ContainerFormat.AV1_ANNEX_B, sink));
            for (var stream : List.of(lowOverhead, annexB, written)) {
                var format = stream == lowOverhead ? ContainerFormat.AV1 : ContainerFormat.AV1_ANNEX_B;
                assertEquals(format, Containers.detect(MediaSource.of(stream)).orElseThrow());
                try (var demuxer = Containers.openDemuxer(MediaSource.of(stream))) {
                    assertEquals(format, demuxer.format());
                    var track = (VideoTrack) demuxer.tracks().getFirst();
                    assertEquals(Codec.AV1, track.codec());
                    assertEquals(96, track.width());
                    assertEquals(64, track.height());
                    assertArrayEquals(source.tracks().getFirst().codecConfig().orElseThrow(),
                            track.codecConfig().orElseThrow(), "av1C");
                    var actual = readAll(demuxer);
                    assertEquals(expected.size(), actual.size(), "temporal units of " + format);
                    for (var i = 0; i < actual.size(); i++) {
                        assertArrayEquals(withoutDelimiters(expected.get(i).data()), actual.get(i).data(),
                                "temporal unit " + i + " of " + format);
                        assertEquals(expected.get(i).keyframe(), actual.get(i).keyframe(), "key flag of " + i);
                        assertEquals(i, actual.get(i).pts());
                        assertEquals(i, actual.get(i).dts());
                    }
                }
            }
        }

        @Test
        @DisplayName("finds the temporal units and key frames ffprobe finds in an ffmpeg-written OBU stream")
        void ffprobe() {
            var options = new ArrayList<>(AOM);
            options.addAll(List.of("-f", "obu"));
            var file = Fixtures.testVideo("obu", 96, 64, 2, options);
            var probe = ContainerProbe.probe(file);
            var ours = ContainerProbe.demux(file, ObuDemuxer::new);
            assertEquals(probe.packets().size(), ours.order().size());
            for (var i = 0; i < ours.order().size(); i++) {
                assertEquals(probe.packets().get(i).key(), ours.order().get(i).keyframe(), "key flag of " + i);
            }
        }

        @Test
        @DisplayName("seeks to key frames and handles truncated and damaged streams")
        void seekAndDamage() {
            var source = video(Fixtures.read(Fixtures.testVideo("mp4", 96, 64, 2, AOM)));
            for (var stream : List.of(annexB(source), toAnnexB(source.order().stream().map(Packet::data).toList()))) {
                List<Packet> linear;
                try (var demuxer = new ObuDemuxer(MediaSource.of(stream))) {
                    linear = readAll(demuxer);
                }
                try (var demuxer = new ObuDemuxer(MediaSource.of(stream))) {
                    // the stream signals no timing, so the demuxer times it at 25 frames per second
                    var rate = demuxer.tracks().getFirst().timeBase().invert().toDouble();
                    assertEquals(25, rate);
                    for (var seconds : new double[]{1.5, 0.1, 0.9, 3.0}) {
                        demuxer.seek((long) (seconds * 1_000_000));
                        var after = readAll(demuxer);
                        if (seconds * rate >= linear.size()) {
                            assertTrue(after.isEmpty());
                            continue;
                        }
                        var target = (long) Math.floor(seconds * rate);
                        var start = 0;
                        for (var i = 0; i < linear.size(); i++) {
                            if (linear.get(i).keyframe() && linear.get(i).pts() <= target) {
                                start = i;
                            }
                        }
                        assertEquals(linear.size() - start, after.size(), "packets after a seek to " + seconds);
                        assertEquals(linear.get(start).pts(), after.getFirst().pts());
                        assertTrue(after.getFirst().keyframe());
                        assertArrayEquals(linear.get(start).data(), after.getFirst().data());
                    }
                    assertEquals(linear.size() * 40_000L, demuxer.durationMicros());
                }
                var random = new Random(3);
                for (var trial = 0; trial < 40; trial++) {
                    var cut = Arrays.copyOf(stream, 100 + random.nextInt(stream.length - 100));
                    var damaged = stream.clone();
                    damaged[random.nextInt(damaged.length)] ^= (byte) (1 << random.nextInt(8));
                    for (var input : List.of(cut, damaged)) {
                        try (var demuxer = new ObuDemuxer(MediaSource.of(input))) {
                            readAll(demuxer);
                            demuxer.seek(1_000_000);
                            readAll(demuxer);
                        } catch (MediaException expected) {
                            // rejected, but only as a MediaException
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("is not taken for IVF, Annex B H.264 or random bytes")
        void detection() {
            var random = new Random(5);
            for (var i = 0; i < 300; i++) {
                var bytes = new byte[16 + random.nextInt(2000)];
                random.nextBytes(bytes);
                assertTrue(ObuDemuxer.detect(bytes).isEmpty(), "random bytes");
                bytes[0] = 0x12;
                bytes[1] = 0x00;
                assertTrue(ObuDemuxer.detect(bytes).isEmpty(), "random bytes after a temporal delimiter");
            }
            var ivf = Fixtures.read(Fixtures.testVideo("ivf", 96, 64, 1, AOM));
            assertEquals(ContainerFormat.IVF, Containers.detect(MediaSource.of(ivf)).orElseThrow());
        }
    }

    @Nested
    @DisplayName("MPEG-4 Part 2 and H.263 streams")
    class Mpeg4 {
        @Test
        @DisplayName("are recognized and opened through Containers, packets equal to the MP4 samples")
        void containers() {
            var mp4File = Fixtures.testVideo("mp4", 96, 64, 1, List.of("-c:v", "mpeg4", "-bf", "2", "-g", "10",
                    "-an"));
            // the same pictures copied into an elementary stream by ffmpeg's m4v muxer, with the configuration
            // headers before every key picture
            var m4v = Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(1), List.of("-v", "error", "-i",
                    mp4File.toString(), "-c", "copy", "-bsf:v", "dump_extra", "-f", "m4v", "-"));
            var mp4 = video(Fixtures.read(mp4File));
            try (var demuxer = Containers.openDemuxer(MediaSource.of(m4v))) {
                assertEquals(ContainerFormat.MPEG4_VIDEO, demuxer.format());
                assertEquals(Codec.MPEG4_PART2, demuxer.tracks().getFirst().codec());
                var packets = readAll(demuxer);
                assertEquals(mp4.order().size(), packets.size());
                for (var i = 0; i < packets.size(); i++) {
                    // the key pictures of the m4v file follow the configuration headers the MP4 file stores apart,
                    // and ffmpeg's m4v muxer ends some pictures with one stuffing byte the MP4 samples lack
                    var ours = packets.get(i).data();
                    var sample = mp4.order().get(i).data();
                    var from = indexOf(ours, sample);
                    assertTrue(from >= 0 && ours.length - from - sample.length <= 1
                            && Arrays.equals(ours, from, from + sample.length, sample, 0, sample.length), "picture " + i);
                    assertEquals(mp4.order().get(i).keyframe(), packets.get(i).keyframe(), "key flag of " + i);
                }
                assertTrue(demuxer.isSeekable());
            }
            var written = MuxSupport.toStream(mp4.tracks(), mp4.order(),
                    sink -> Containers.openMuxer(ContainerFormat.MPEG4_VIDEO, sink));
            try (var demuxer = new Mpeg4VideoDemuxer(MediaSource.of(written))) {
                var packets = readAll(demuxer);
                assertEquals(mp4.order().size(), packets.size());
                for (var i = 0; i < packets.size(); i++) {
                    assertTrue(endsWith(packets.get(i).data(), mp4.order().get(i).data()), "written picture " + i);
                    assertEquals(mp4.order().get(i).keyframe(), packets.get(i).keyframe(), "written key flag of " + i);
                }
                // the headers before the first picture: the MP4 configuration, then the first group of VOP header
                var config = mp4.tracks().getFirst().codecConfig().orElseThrow();
                var headers = demuxer.tracks().getFirst().codecConfig().orElseThrow();
                assertArrayEquals(config, Arrays.copyOf(headers, config.length), "configuration headers");
            }
            var h263 = Fixtures.read(Fixtures.testVideo("263", 176, 144, 1, List.of("-c:v", "h263", "-g", "10",
                    "-an", "-f", "h263")));
            assertEquals(ContainerFormat.H263, Containers.detect(MediaSource.of(h263)).orElseThrow());
            try (var demuxer = Containers.openDemuxer(MediaSource.of(h263))) {
                assertEquals(ContainerFormat.H263, demuxer.format());
                assertEquals(Codec.H263, demuxer.tracks().getFirst().codec());
                var packets = readAll(demuxer);
                assertEquals(30, packets.size());
                assertTrue(packets.getFirst().keyframe());
            }
            assertTrue(Containers.isMuxable(ContainerFormat.H263, Codec.H263));
            assertFalse(Containers.isMuxable(ContainerFormat.H263, Codec.MPEG4_PART2));
        }
    }

    @Nested
    @DisplayName("raw PCM")
    class Pcm {
        @Test
        @DisplayName("reads the samples RawMuxer wrote with the caller's format, seeking to the sample")
        void roundTrip() {
            var wav = MuxSupport.demux(Fixtures.read(Fixtures.tone("wav", 440, 22_050, 2, 1,
                    List.of("-c:a", "pcm_s24le"))));
            var raw = MuxSupport.toStream(wav.tracks(), wav.order(), sink -> Containers.openMuxer(ContainerFormat.PCM, sink));
            var expected = new ByteArrayOutputStream();
            wav.order().forEach(packet -> expected.writeBytes(packet.data()));
            assertArrayEquals(expected.toByteArray(), raw);
            assertTrue(Containers.isMuxable(ContainerFormat.PCM, Codec.PCM_F32BE));
            assertFalse(Containers.isMuxable(ContainerFormat.PCM, Codec.AAC));
            var options = PcmDemuxerOptions.builder().codec(Codec.PCM_S24LE).sampleRate(22_050)
                    .layout(ChannelLayout.STEREO).packetFrames(1000).build();
            try (var demuxer = new PcmDemuxer(MediaSource.of(raw), options)) {
                assertEquals(ContainerFormat.PCM, demuxer.format());
                assertEquals(1_000_000, demuxer.durationMicros());
                var packets = readAll(demuxer);
                assertEquals(23, packets.size());
                assertEquals(50, packets.getLast().duration());
                var joined = new ByteArrayOutputStream();
                packets.forEach(packet -> joined.writeBytes(packet.data()));
                assertArrayEquals(raw, joined.toByteArray());
                for (var i = 0; i < packets.size(); i++) {
                    assertEquals(1000L * i, packets.get(i).pts());
                }
                demuxer.seek(500_000);
                var after = demuxer.read();
                assertEquals(11_025, after.pts());
                assertArrayEquals(Arrays.copyOfRange(raw, 11_025 * 6, 11_025 * 6 + 6000), after.data());
                demuxer.seek(2_000_000);
                assertEquals(null, demuxer.read());
            }
            var odd = Arrays.copyOf(raw, raw.length - 4);
            try (var demuxer = new PcmDemuxer(MediaSource.of(odd), options)) {
                var total = readAll(demuxer).stream().mapToLong(Packet::duration).sum();
                assertEquals(22_049, total, "a trailing partial frame is dropped");
            }
            assertThrows(MediaException.Unsupported.class, () -> Containers.openDemuxer(MediaSource.of(raw)));
            assertThrows(IllegalArgumentException.class, () -> PcmDemuxerOptions.builder().codec(Codec.AAC));
            assertThrows(IllegalArgumentException.class, () -> PcmDemuxerOptions.builder().sampleRate(0));
            assertThrows(IllegalArgumentException.class, () -> PcmDemuxerOptions.builder().packetFrames(0));
            assertEquals(PcmDemuxerOptions.of(Codec.PCM_S16LE, 48_000, ChannelLayout.STEREO), PcmDemuxerOptions.defaults());
        }

        @Test
        @DisplayName("decodes to the samples ffmpeg decodes from the same raw file")
        void ffmpeg() {
            var file = Fixtures.tone("raw", 330, 16_000, 1, 0.5, List.of("-c:a", "pcm_s16be", "-f", "s16be"));
            var options = PcmDemuxerOptions.of(Codec.PCM_S16BE, 16_000, ChannelLayout.MONO);
            var samples = Oracle.decodeAudio(file, List.of("-f", "s16be", "-ar", "16000", "-ac", "1"));
            try (var demuxer = new PcmDemuxer(MediaSource.of(Fixtures.read(file)), options);
                 var decoder = com.github.auties00.smalt.audio.AudioDecoders.create(
                         (com.github.auties00.smalt.track.AudioTrack) demuxer.tracks().getFirst())) {
                var decoded = new ArrayList<Float>();
                for (var packet : readAll(demuxer)) {
                    decoder.decode(packet, buffer -> {
                        for (var i = 0; i < buffer.frames(); i++) {
                            decoded.add(buffer.planes()[0][i]);
                        }
                    });
                }
                assertEquals(samples.length, decoded.size());
                for (var i = 0; i < samples.length; i++) {
                    assertEquals(samples[i], decoded.get(i), 0f, "sample " + i);
                }
            }
        }
    }

    static int indexOf(byte[] data, byte[] part) {
        for (var i = 0; i + part.length <= data.length; i++) {
            if (Arrays.equals(data, i, i + part.length, part, 0, part.length)) {
                return i;
            }
        }
        return -1;
    }

    static boolean endsWith(byte[] data, byte[] suffix) {
        return data.length >= suffix.length
                && Arrays.equals(data, data.length - suffix.length, data.length, suffix, 0, suffix.length);
    }

    @Test
    @DisplayName("recognizes nothing elementary in text that starts like a start code")
    void textIsNotVideo() {
        var text = "\u0000\u0000\u0001 hello world".getBytes(StandardCharsets.ISO_8859_1);
        assertNotEquals(ContainerFormat.H264, Containers.detect(MediaSource.of(text)).orElse(null));
        assertTrue(Mpeg4VideoDemuxer.detect(text).isEmpty());
        Function<byte[], Boolean> any = bytes -> AnnexBDemuxer.detect(bytes).isPresent()
                || ObuDemuxer.detect(bytes).isPresent() || Mpeg4VideoDemuxer.detect(bytes).isPresent();
        assertFalse(any.apply(new byte[0]));
        assertFalse(any.apply(new byte[]{0, 0, 1}));
        assertFalse(any.apply(new byte[]{0, 0, 0, 1, 0x67}));
    }
}
