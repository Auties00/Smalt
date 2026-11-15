package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.mp4.Mp4Muxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers AV1 in transport streams as the AOM "Carriage of AV1 in MPEG-2 TS" specifies it: the muxer's start-code
 * format and descriptors, the demuxer on streams of the muxer and on streams written packet by packet in the
 * layouts the specification allows (OBUs without size fields, temporal units split over access units, temporal
 * delimiters), and decoding through ffmpeg after a round trip. ffmpeg 7.1 writes and reads AV1 in TS only as
 * opaque private data, so it serves as an oracle through MP4.
 */
@DisplayName("MPEG-TS AV1")
class TsAv1Test {
    /** Returns an ffmpeg-encoded AV1 MP4 with key frames every ten frames. */
    static Path av1Mp4() {
        Oracle.assumeEncoder("libaom-av1");
        return Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i", "testsrc2=size=96x64:rate=25:duration=2"),
                List.of("-c:v", "libaom-av1", "-cpu-used", "8", "-g", "10", "-b:v", "200k", "-pix_fmt", "yuv420p"));
    }

    /** Returns the OBUs of a temporal unit as hex strings, without temporal delimiters and sequence headers. */
    static List<String> frameObus(byte[] unit) {
        var result = new ArrayList<String>();
        for (var offset = 0; offset < unit.length; ) {
            var header = unit[offset] & 0xFF;
            var position = offset + ((header & 0x04) != 0 ? 2 : 1);
            var size = 0L;
            for (var shift = 0; ; shift += 7) {
                var b = unit[position++] & 0xFF;
                size |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            var end = position + (int) size;
            var type = (header >> 3) & 0x0F;
            if (type != 1 && type != 2) {
                result.add(ContainerProbe.crc32(Arrays.copyOfRange(unit, offset, end)));
            }
            offset = end;
        }
        return result;
    }

    /** Muxes every packet of a demuxer into an MP4 held in memory. */
    static byte[] toMp4(byte[] ts) {
        try (var source = MediaSource.of(ts); var demuxer = new TsDemuxer(source)) {
            var sink = MediaSink.memory();
            try (var muxer = new Mp4Muxer(sink)) {
                demuxer.tracks().forEach(muxer::addTrack);
                for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                    muxer.writePacket(packet);
                }
                muxer.finish();
            }
            return sink.toByteArray();
        }
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {
        @Test
        @DisplayName("returns the MP4's track, timestamps, key frames and OBUs")
        void packets() {
            var input = av1Mp4();
            var original = ContainerProbe.demux(input, Containers::openDemuxer);
            var ts = TsMuxerTest.remux(input, TsMuxer::new);
            TsTesting.save("av1.ts", ts);
            var demuxed = TsTesting.demux(ts);
            assertEquals(1, demuxed.tracks().size());
            var track = (VideoTrack) demuxed.tracks().getFirst();
            var source = (VideoTrack) original.tracks().getFirst();
            assertEquals(Codec.AV1, track.codec());
            assertEquals(source.width(), track.width());
            assertEquals(source.height(), track.height());
            var config = track.codecConfig().orElseThrow();
            var sourceConfig = source.codecConfig().orElseThrow();
            assertArrayEquals(Arrays.copyOf(sourceConfig, 3), Arrays.copyOf(config, 3), "av1C profile, level and format");
            var expected = original.packetsOf(source);
            var actual = demuxed.packetsOf(track);
            assertEquals(expected.size(), actual.size(), "packet count");
            for (var index = 0; index < expected.size(); index++) {
                var want = expected.get(index);
                var got = actual.get(index);
                var wantPts = source.timeBase().rescale(want.pts(), Rational.timeBaseOf(90_000));
                var wantDts = source.timeBase().rescale(want.dts(), Rational.timeBaseOf(90_000));
                var shift = source.timeBase().rescale(expected.getFirst().dts(), Rational.timeBaseOf(90_000));
                assertEquals(wantPts - shift, got.pts(), "pts of packet " + index);
                assertEquals(wantDts - shift, got.dts(), "dts of packet " + index);
                assertEquals(want.keyframe(), got.keyframe(), "key flag of packet " + index);
                assertEquals(frameObus(want.data()), frameObus(got.data()), "OBUs of packet " + index);
                if (got.keyframe()) {
                    assertTrue(containsSequenceHeader(got.data()), "sequence header of key packet " + index);
                }
            }
        }

        @Test
        @DisplayName("decodes in ffmpeg exactly like the source after TS and back")
        void decodesAlike() {
            var input = av1Mp4();
            var ts = TsMuxerTest.remux(input, TsMuxer::new);
            var mp4 = TsTesting.save("av1-back.mp4", toMp4(ts));
            assertArrayEquals(Oracle.decodeVideo(input, "yuv420p", 100), Oracle.decodeVideo(mp4, "yuv420p", 100));
        }

        @Test
        @DisplayName("writes the AV01 registration first and the AV1 video descriptor from av1C")
        void descriptors() {
            var input = av1Mp4();
            var ts = TsMuxerTest.remux(input, TsMuxer::new);
            var config = ContainerProbe.demux(input, Containers::openDemuxer).tracks().getFirst().codecConfig().orElseThrow();
            var pmt = TsTesting.packets(ts, 188, 0).stream().filter(packet -> TsTesting.pid(packet) == 0x1000)
                    .findFirst().orElseThrow();
            var descriptors = Arrays.copyOfRange(pmt, 5 + 17, 5 + 17 + 12);
            assertEquals(0x06, pmt[5 + 12] & 0xFF, "stream type");
            assertArrayEquals(new byte[]{0x05, 4, 'A', 'V', '0', '1', (byte) 0x80, 4, config[0], config[1], config[2]},
                    Arrays.copyOf(descriptors, 11));
            assertEquals(3, (descriptors[11] & 0xFF) >> 6, "hdr_wcg_idc of video without a color description");
            assertEquals(config[3] & 0x1F, descriptors[11] & 0x1F, "initial presentation delay");
        }

        @Test
        @DisplayName("takes a last OBU without a size field and flags each key frame's first packet for priority")
        void sizelessLastObu() {
            var input = av1Mp4();
            var original = ContainerProbe.demux(input, Containers::openDemuxer);
            var source = original.tracks().getFirst();
            var sink = MediaSink.memory();
            try (var muxer = new TsMuxer(sink)) {
                muxer.addTrack(source);
                for (var packet : original.packetsOf(source)) {
                    muxer.writePacket(packet.withData(withoutLastSize(packet.data())));
                }
                muxer.finish();
            }
            var ts = sink.toByteArray();
            var demuxed = TsTesting.demux(ts);
            var expected = original.packetsOf(source);
            var actual = demuxed.packetsOf(demuxed.tracks().getFirst());
            assertEquals(expected.size(), actual.size());
            for (var index = 0; index < expected.size(); index++) {
                assertEquals(frameObus(expected.get(index).data()), frameObus(actual.get(index).data()), "OBUs of " + index);
            }
            var keys = 0;
            for (var packet : TsTesting.packets(ts, 188, 0)) {
                if (TsTesting.pid(packet) != 0x100 || (packet[3] & 0x20) == 0 || (packet[4] & 0xFF) == 0) {
                    continue;
                }
                var randomAccess = (packet[5] & 0x40) != 0;
                assertEquals(randomAccess, (packet[5] & 0x20) != 0, "elementary_stream_priority_indicator");
                keys += randomAccess ? 1 : 0;
            }
            assertEquals(expected.stream().filter(Packet::keyframe).count(), keys);
        }

        @Test
        @DisplayName("carries a new sequence header from a configuration change and announces it when read")
        void configurationChange() {
            var first = ContainerProbe.demux(av1Mp4(), Containers::openDemuxer);
            var second = ContainerProbe.demux(Fixtures.ffmpeg("mp4", List.of("-f", "lavfi", "-i",
                    "testsrc2=size=128x96:rate=25:duration=1"), List.of("-c:v", "libaom-av1", "-cpu-used", "8", "-g", "10",
                    "-b:v", "200k", "-pix_fmt", "yuv420p")), Containers::openDemuxer);
            var track = first.tracks().getFirst();
            var other = second.tracks().getFirst();
            var newConfig = other.codecConfig().orElseThrow();
            var firstPackets = first.packetsOf(track);
            var secondPackets = second.packetsOf(other);
            var last = firstPackets.getLast();
            var offset = last.pts() + last.duration();
            var sink = MediaSink.memory();
            try (var muxer = new TsMuxer(sink)) {
                muxer.addTrack(track);
                firstPackets.forEach(muxer::writePacket);
                for (var index = 0; index < secondPackets.size(); index++) {
                    var packet = secondPackets.get(index);
                    var pts = offset + other.timeBase().rescale(packet.pts(), track.timeBase());
                    var dts = offset + other.timeBase().rescale(packet.dts(), track.timeBase());
                    var moved = packet.withTrackId(track.id()).withTiming(pts, dts, packet.duration());
                    if (index == 0) {
                        moved = moved.withSideData(PacketSideData.builder().newCodecConfig(newConfig).build());
                    }
                    muxer.writePacket(moved);
                }
                muxer.finish();
            }
            var demuxed = TsTesting.demux(sink.toByteArray());
            var packets = demuxed.packetsOf(demuxed.tracks().getFirst());
            assertEquals(firstPackets.size() + secondPackets.size(), packets.size());
            var announced = new ArrayList<Integer>();
            for (var index = 0; index < packets.size(); index++) {
                var config = packets.get(index).sideData().newCodecConfig();
                if (config.isPresent()) {
                    announced.add(index);
                    assertArrayEquals(Arrays.copyOfRange(newConfig, 4, newConfig.length),
                            Arrays.copyOfRange(config.get(), 4, config.get().length), "the new sequence header");
                }
            }
            assertEquals(List.of(firstPackets.size()), announced);
            assertTrue(containsSequenceHeader(packets.get(firstPackets.size()).data()));
            for (var index = 0; index < secondPackets.size(); index++) {
                assertEquals(frameObus(secondPackets.get(index).data()),
                        frameObus(packets.get(firstPackets.size() + index).data()), "OBUs of " + index);
            }
        }

        @Test
        @DisplayName("frames every OBU with a start code and never emulates one")
        void startCodes() {
            var ts = TsMuxerTest.remux(av1Mp4(), TsMuxer::new);
            var payloads = TsWriter.pesPayloads(ts, 0x100);
            assertFalse(payloads.isEmpty());
            for (var payload : payloads) {
                assertArrayEquals(new byte[]{0, 0, 1, 0x12, 0}, Arrays.copyOf(payload, 5), "temporal delimiter first");
                assertNoEmulation(payload);
            }
        }
    }

    /** Returns a temporal unit whose last OBU has its size field removed, as ISOBMFF allows. */
    static byte[] withoutLastSize(byte[] unit) {
        var lastStart = 0;
        var lastPayload = 0;
        for (var offset = 0; offset < unit.length; ) {
            var header = unit[offset] & 0xFF;
            var position = offset + ((header & 0x04) != 0 ? 2 : 1);
            var size = 0L;
            for (var shift = 0; ; shift += 7) {
                var b = unit[position++] & 0xFF;
                size |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            lastStart = offset;
            lastPayload = position;
            offset = position + (int) size;
        }
        var headerSize = (unit[lastStart] & 0x04) != 0 ? 2 : 1;
        var out = new ByteArrayOutputStream();
        out.write(unit, 0, lastStart);
        out.write(unit[lastStart] & ~0x02);
        if (headerSize == 2) {
            out.write(unit[lastStart + 1]);
        }
        out.write(unit, lastPayload, unit.length - lastPayload);
        return out.toByteArray();
    }

    /** Fails when a tsOBU of a start-code payload holds a forbidden three-byte sequence (section 3.2). */
    static void assertNoEmulation(byte[] payload) {
        var starts = new ArrayList<Integer>();
        for (var index = 0; index + 2 < payload.length; index++) {
            if (payload[index] == 0 && payload[index + 1] == 0 && payload[index + 2] == 1) {
                starts.add(index);
                index += 2;
            }
        }
        assertEquals(0, starts.getFirst(), "the payload starts with a start code");
        starts.add(payload.length);
        for (var unit = 0; unit + 1 < starts.size(); unit++) {
            var from = starts.get(unit) + 3;
            var to = starts.get(unit + 1);
            for (var index = from; index + 2 < to; index++) {
                if (payload[index] != 0 || payload[index + 1] != 0) {
                    continue;
                }
                var third = payload[index + 2] & 0xFF;
                assertTrue(third >= 3, "00 00 0" + third + " inside the tsOBU at " + from);
                if (third == 3) {
                    assertTrue(index + 3 >= to || (payload[index + 3] & 0xFF) <= 3, "00 00 03 followed by a byte above 3");
                }
            }
        }
    }

    static boolean containsSequenceHeader(byte[] unit) {
        for (var offset = 0; offset < unit.length; ) {
            var header = unit[offset] & 0xFF;
            if (((header >> 3) & 0x0F) == 1) {
                return true;
            }
            var position = offset + ((header & 0x04) != 0 ? 2 : 1);
            var size = 0L;
            for (var shift = 0; ; shift += 7) {
                var b = unit[position++] & 0xFF;
                size |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) {
                    break;
                }
            }
            offset = position + (int) size;
        }
        return false;
    }

    @Nested
    @DisplayName("emulation prevention")
    class Emulation {
        @Test
        @DisplayName("returns OBUs full of zero runs byte for byte")
        void zeroRuns() {
            var input = av1Mp4();
            var original = ContainerProbe.demux(input, Containers::openDemuxer);
            var source = (VideoTrack) original.tracks().getFirst();
            var random = new Random(302);
            var padding = new byte[600];
            for (var index = 0; index < padding.length; index++) {
                // runs of zeros followed by every small value, the sequences start codes are made of
                padding[index] = random.nextInt(3) == 0 ? (byte) random.nextInt(5) : 0;
            }
            var sink = MediaSink.memory();
            var written = new ArrayList<byte[]>();
            try (var muxer = new TsMuxer(sink)) {
                muxer.addTrack(source);
                for (var packet : original.packetsOf(source)) {
                    var data = withPadding(packet.data(), padding);
                    written.add(data);
                    muxer.writePacket(packet.withData(data));
                }
                muxer.finish();
            }
            var ts = sink.toByteArray();
            TsWriter.pesPayloads(ts, 0x100).forEach(TsAv1Test::assertNoEmulation);
            var demuxed = TsTesting.demux(ts);
            var packets = demuxed.packetsOf(demuxed.tracks().getFirst());
            assertEquals(written.size(), packets.size());
            for (var index = 0; index < written.size(); index++) {
                assertEquals(frameObus(written.get(index)), frameObus(packets.get(index).data()), "packet " + index);
            }
        }

        /** Appends a padding OBU to a temporal unit. */
        private static byte[] withPadding(byte[] unit, byte[] padding) {
            var out = new ByteArrayOutputStream();
            out.writeBytes(unit);
            out.write(15 << 3 | 0x02);
            var size = padding.length;
            while (size > 0x7F) {
                out.write(size & 0x7F | 0x80);
                size >>>= 7;
            }
            out.write(size);
            out.writeBytes(padding);
            return out.toByteArray();
        }
    }

    @Nested
    @DisplayName("streams written by other muxers")
    class Foreign {
        private static final int PID = 0x101;

        /** Returns the sequence header OBU of the test stream, with its size field. */
        private static byte[] sequenceHeader() {
            var config = ContainerProbe.demux(av1Mp4(), Containers::openDemuxer).tracks().getFirst().codecConfig().orElseThrow();
            return Arrays.copyOfRange(config, 4, config.length);
        }

        /** Returns a start-code tsOBU without a size field from an OBU type and payload. */
        private static byte[] tsObu(int type, int... payload) {
            var out = new ByteArrayOutputStream();
            out.writeBytes(new byte[]{0, 0, 1, (byte) (type << 3)});
            var zeros = 0;
            for (var value : payload) {
                if (zeros >= 2 && (value & 0xFF) <= 3) {
                    out.write(3);
                    zeros = 0;
                }
                out.write(value);
                zeros = value == 0 ? zeros + 1 : 0;
            }
            return out.toByteArray();
        }

        /** Returns a start-code tsOBU of an OBU that has a size field, with emulation prevention. */
        private static byte[] tsObu(byte[] obu) {
            var header = obu[0] & 0xFF;
            var payload = new int[obu.length - 1];
            for (var index = 1; index < obu.length; index++) {
                payload[index - 1] = obu[index] & 0xFF;
            }
            var unit = tsObu(header >> 3, payload);
            unit[3] = (byte) header;
            return unit;
        }

        private static byte[] program() {
            var descriptor = TsWriter.concat(TsWriter.registration("AV01"), new byte[]{(byte) 0x80, 4, (byte) 0x81, 0, 0x0C, 0});
            return new TsWriter().pat(1, 0x1000, 0)
                    .pmt(0x1000, 1, 0, PID, new byte[0], List.of(new TsWriter.Stream(0x06, PID, descriptor)))
                    .toByteArray();
        }

        @Test
        @DisplayName("groups access units into temporal units at their shown frame and adds size fields")
        void accessUnits() {
            var sequence = sequenceHeader();
            var writer = new TsWriter();
            var start = 900_000L;
            // a shown key frame; a hidden frame, then the frame that shows it; a shown inter frame
            writer.pes(PID, 0xBD, start, -1, TsWriter.concat(tsObu(sequence), tsObu(6, 0x10, 1, 2, 0, 0, 0, 7)), true);
            writer.pes(PID, 0xBD, start + 7200, start + 3600, tsObu(6, 0x20, 9, 9), false);
            writer.pes(PID, 0xBD, start + 3600, start + 3600 + 1, tsObu(3, 0x80), false);
            writer.pes(PID, 0xBD, start + 7200, -1, tsObu(6, 0x30, 5), false);
            var ts = TsWriter.concat(program(), writer.toByteArray());
            var demuxed = TsTesting.demux(ts);
            var track = (VideoTrack) demuxed.tracks().getFirst();
            assertEquals(Codec.AV1, track.codec());
            assertArrayEquals(sequence, Arrays.copyOfRange(track.codecConfig().orElseThrow(), 4, track.codecConfig().orElseThrow().length));
            var packets = demuxed.packetsOf(track);
            assertEquals(3, packets.size());
            assertArrayEquals(TsWriter.concat(sequence, new byte[]{0x32, 7, 0x10, 1, 2, 0, 0, 0, 7}), packets.get(0).data());
            assertTrue(packets.get(0).keyframe());
            assertArrayEquals(new byte[]{0x32, 3, 0x20, 9, 9, 0x1A, 1, (byte) 0x80}, packets.get(1).data());
            assertFalse(packets.get(1).keyframe());
            assertEquals(3600, packets.get(1).pts(), "the shown frame's time");
            assertEquals(3600, packets.get(1).dts(), "the first access unit's decoding time");
            assertArrayEquals(new byte[]{0x32, 2, 0x30, 5}, packets.get(2).data());
            assertEquals(7200, packets.get(2).pts());
        }

        @Test
        @DisplayName("delimits temporal units by their temporal delimiters when present")
        void delimiters() {
            var sequence = sequenceHeader();
            var writer = new TsWriter();
            var delimiter = tsObu(2);
            // two shown frames of two spatial layers in one temporal unit, split over two PES packets
            writer.pes(PID, 0xBD, 0, -1, TsWriter.concat(delimiter, tsObu(sequence), tsObu(6, 0x10, 1)), true);
            writer.pes(PID, 0xBD, 0, -1, tsObu(6, 0x10, 2), false);
            writer.pes(PID, 0xBD, 3600, -1, TsWriter.concat(delimiter, tsObu(6, 0x30, 3)), false);
            writer.pes(PID, 0xBD, 7200, -1, TsWriter.concat(delimiter, tsObu(6, 0x30, 4)), false);
            var demuxed = TsTesting.demux(TsWriter.concat(program(), writer.toByteArray()));
            var packets = demuxed.packetsOf(demuxed.tracks().getFirst());
            assertEquals(3, packets.size());
            assertArrayEquals(TsWriter.concat(sequence, new byte[]{0x32, 2, 0x10, 1, 0x32, 2, 0x10, 2}), packets.get(0).data());
            assertEquals(List.of(0L, 3600L, 7200L), packets.stream().map(Packet::pts).toList());
            assertEquals(3600, packets.get(0).duration());
        }

        @Test
        @DisplayName("drops what precedes the first key frame and survives garbage between start codes")
        void robustness() {
            var sequence = sequenceHeader();
            var writer = new TsWriter();
            writer.pes(PID, 0xBD, 0, -1, tsObu(6, 0x30, 1), false);
            writer.pes(PID, 0xBD, 3600, -1, TsWriter.concat(new byte[]{0x55, 0x66}, tsObu(sequence), tsObu(6, 0x10, 2)), true);
            writer.pes(PID, 0xBD, 7200, -1, TsWriter.concat(tsObu(6, 0x30, 3), new byte[]{0, 0, 1, (byte) 0x80}), false);
            writer.pes(PID, 0xBD, 10800, -1, tsObu(6, 0x30, 4), false);
            var demuxed = TsTesting.demux(TsWriter.concat(program(), writer.toByteArray()));
            var packets = demuxed.packetsOf(demuxed.tracks().getFirst());
            assertEquals(3, packets.size());
            assertTrue(packets.getFirst().keyframe());
            assertEquals(0, packets.getFirst().pts(), "the first key frame starts the timeline");
            assertArrayEquals(new byte[]{0x32, 2, 0x30, 3}, packets.get(1).data(), "a forbidden OBU is skipped");
        }
    }
}
