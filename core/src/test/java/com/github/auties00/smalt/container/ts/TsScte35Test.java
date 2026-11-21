package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.internal.header.Crc32Mpeg;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers SCTE 35 splice information (ANSI/SCTE 35 clause 9) in transport streams: sections built field by field
 * here are written by the muxer, read back by Smalt and by ffprobe, and read from streams written packet by packet
 * with sections that span packets, arrive before any timestamp or fail their CRC.
 */
@DisplayName("MPEG-TS SCTE 35")
class TsScte35Test {
    /** Writes bits most significant first. */
    private static final class Bits {
        private final List<Boolean> bits = new ArrayList<>();

        Bits put(long value, int count) {
            for (var index = count - 1; index >= 0; index--) {
                bits.add(((value >>> index) & 1) != 0);
            }
            return this;
        }

        byte[] bytes() {
            var out = new byte[bits.size() / 8];
            for (var index = 0; index < out.length * 8; index++) {
                if (bits.get(index)) {
                    out[index / 8] |= (byte) (0x80 >>> (index % 8));
                }
            }
            return out;
        }
    }

    /**
     * Returns a {@code splice_info_section} holding a {@code splice_insert} at a splice time, with a descriptor
     * loop of {@code descriptorBytes} bytes of {@code avail_descriptor}s.
     */
    static byte[] spliceInsert(long eventId, long ptsTime, long duration, int descriptorBytes) {
        var command = new Bits()
                .put(eventId, 32).put(0, 1).put(0x7F, 7)
                .put(1, 1).put(1, 1).put(1, 1).put(0, 1).put(0xF, 4)
                .put(1, 1).put(0x3F, 6).put(ptsTime, 33)
                .put(1, 1).put(0x3F, 6).put(duration, 33)
                .put(eventId & 0xFFFF, 16).put(0, 8).put(0, 8).bytes();
        var descriptors = new byte[descriptorBytes];
        for (var offset = 0; offset + 10 <= descriptorBytes; offset += 10) {
            System.arraycopy(new byte[]{0x00, 8, 'C', 'U', 'E', 'I', 0, 0, 0, (byte) offset}, 0, descriptors, offset, 10);
        }
        var body = new Bits().put(0, 8).put(0, 1).put(0, 6).put(0, 33).put(0, 8).put(0xFFF, 12)
                .put(command.length, 12).put(5, 8).bytes();
        var length = body.length + command.length + 2 + descriptors.length + 4;
        var section = new byte[3 + length];
        section[0] = (byte) 0xFC;
        section[1] = (byte) (0x30 | length >> 8);
        section[2] = (byte) length;
        var position = 3;
        System.arraycopy(body, 0, section, position, body.length);
        position += body.length;
        System.arraycopy(command, 0, section, position, command.length);
        position += command.length;
        section[position++] = (byte) (descriptors.length >> 8);
        section[position++] = (byte) descriptors.length;
        System.arraycopy(descriptors, 0, section, position, descriptors.length);
        return TsWriter.withCrc(section);
    }

    /** Returns the {@code pts_adjustment} of a section. */
    static long adjustment(byte[] section) {
        return (long) (section[4] & 1) << 32 | (section[5] & 0xFFL) << 24 | (section[6] & 0xFFL) << 16
                | (section[7] & 0xFFL) << 8 | (section[8] & 0xFFL);
    }

    /** Returns the splice time of a section built by {@link #spliceInsert}, plus its adjustment, modulo 2^33. */
    static long adjustedSpliceTime(byte[] section) {
        var time = (long) (section[20] & 1) << 32 | (section[21] & 0xFFL) << 24 | (section[22] & 0xFFL) << 16
                | (section[23] & 0xFFL) << 8 | (section[24] & 0xFFL);
        return (time + adjustment(section)) & ((1L << 33) - 1);
    }

    /** Asserts that a section equals another but for its pts_adjustment, and that its CRC holds. */
    static void assertSameButAdjustment(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length);
        assertEquals(0, Crc32Mpeg.update(Crc32Mpeg.MPEG_INIT, actual, 0, actual.length), "CRC_32");
        assertEquals(expected[4] & 0xFE, actual[4] & 0xFE, "encryption fields");
        assertArrayEquals(Arrays.copyOf(expected, 4), Arrays.copyOf(actual, 4));
        assertArrayEquals(Arrays.copyOfRange(expected, 9, expected.length - 4),
                Arrays.copyOfRange(actual, 9, actual.length - 4));
    }

    /** Returns the sections a PID carries in a stream of 188-byte packets, each starting a packet. */
    static List<byte[]> rawSections(byte[] ts, int pid) {
        var result = new ArrayList<byte[]>();
        java.io.ByteArrayOutputStream current = null;
        for (var packet : TsTesting.packets(ts, 188, 0)) {
            if (TsTesting.pid(packet) != pid) {
                continue;
            }
            if ((packet[1] & 0x40) != 0) {
                current = new java.io.ByteArrayOutputStream();
                current.write(packet, 5, 183);
            } else if (current != null) {
                current.write(packet, 4, 184);
            }
            var bytes = current == null ? new byte[0] : current.toByteArray();
            if (bytes.length >= 3 && bytes.length >= 3 + ((bytes[1] & 0x0F) << 8 | (bytes[2] & 0xFF))) {
                result.add(Arrays.copyOf(bytes, 3 + ((bytes[1] & 0x0F) << 8 | (bytes[2] & 0xFF))));
                current = null;
            }
        }
        return result;
    }

    /** Returns a stream with a second of silent AAC and three splice sections. */
    static byte[] muxed(List<byte[]> sections) {
        var audio = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(48_000))
                .codecConfig(new byte[]{0x11, (byte) 0x90}).sampleRate(48_000).layout(ChannelLayout.STEREO).build();
        var cues = DataTrack.builder().id(2).timeBase(Rational.timeBaseOf(90_000)).handler("scte35").build();
        var sink = MediaSink.memory();
        try (var muxer = new TsMuxer(sink)) {
            muxer.addTrack(audio);
            muxer.addTrack(cues);
            var next = 0;
            for (var frame = 0; frame < 100; frame++) {
                var time = frame * 1024L;
                muxer.writePacket(new Packet(1, TsMuxerTest.silentAacFrame(), time, time, 1024, true));
                if (frame % 30 == 10 && next < sections.size()) {
                    var ticks = time * 90_000 / 48_000;
                    muxer.writePacket(new Packet(2, sections.get(next++), ticks, ticks, 0, true));
                }
            }
            muxer.finish();
        }
        return sink.toByteArray();
    }

    @Test
    @DisplayName("writes splice sections that Smalt and ffprobe read back unchanged")
    void roundTrip() {
        var sections = List.of(spliceInsert(1, 180_000, 2_700_000, 0), spliceInsert(2, 900_000, 90_000, 30),
                spliceInsert(3, 8_589_934_000L, 450_000, 400));
        var ts = muxed(sections);
        var file = TsTesting.save("scte35.ts", ts);
        var demuxed = TsTesting.demux(ts);
        assertEquals(2, demuxed.tracks().size());
        var track = (DataTrack) demuxed.tracks().get(1);
        assertEquals("scte35", track.handler().orElseThrow());
        var packets = demuxed.packetsOf(track);
        assertEquals(sections.size(), packets.size());
        for (var index = 0; index < sections.size(); index++) {
            assertArrayEquals(sections.get(index), packets.get(index).data(), "section " + index);
            assertTrue(packets.get(index).keyframe());
        }
        var audio = demuxed.packetsOf(demuxed.tracks().getFirst());
        for (var index = 0; index < packets.size(); index++) {
            var expected = (index * 30 + 10) * 1024L * 90_000 / 48_000;
            assertTrue(Math.abs(packets.get(index).pts() - expected) <= 90_000 / 2,
                    "section " + index + " is stamped near the audio written with it: " + packets.get(index).pts());
        }
        assertTrue(audio.size() > 90);
        // the muxer starts the timeline at its 700 ms delay, so the splice times move with it
        var written = rawSections(ts, track.id());
        assertEquals(sections.size(), written.size());
        var probe = ContainerProbe.probe(file);
        assertEquals("scte_35", probe.streams().get(1).codecName());
        var probed = probe.packetsOf(1);
        assertEquals(sections.size(), probed.size());
        for (var index = 0; index < sections.size(); index++) {
            assertSameButAdjustment(sections.get(index), written.get(index));
            assertEquals(63_000, adjustment(written.get(index)), "pts_adjustment of written section " + index);
            assertEquals(ContainerProbe.crc32(written.get(index)), probed.get(index).hash(), "ffprobe's section " + index);
        }
    }

    @Test
    @DisplayName("moves the splice times with the muxer's timeline offset")
    void offset() {
        var section = spliceInsert(4, 90_000, 0, 0);
        var audio = AudioTrack.builder().id(1).codec(Codec.AAC).timeBase(Rational.timeBaseOf(48_000))
                .codecConfig(new byte[]{0x11, (byte) 0x90}).sampleRate(48_000).layout(ChannelLayout.STEREO).build();
        var cues = DataTrack.builder().id(2).timeBase(Rational.timeBaseOf(90_000)).handler("scte35").build();
        var sink = MediaSink.memory();
        var options = TsMuxerOptions.builder().delayMicros(2_000_000).build();
        try (var muxer = new TsMuxer(sink, options)) {
            muxer.addTrack(audio);
            muxer.addTrack(cues);
            for (var frame = 0; frame < 100; frame++) {
                var time = 48_000L + frame * 1024L;
                muxer.writePacket(new Packet(1, TsMuxerTest.silentAacFrame(), time, time, 1024, true));
                if (frame == 5) {
                    muxer.writePacket(new Packet(2, section, 90_000 + 5 * 1920, 90_000 + 5 * 1920, 0, true));
                }
            }
            muxer.finish();
        }
        var ts = sink.toByteArray();
        var written = rawSections(ts, TsMuxerOptions.defaults().firstPid() + 1).getFirst();
        assertSameButAdjustment(section, written);
        // the audio at input time 1 s is written at the 2 s delay, so the splice at 1 s points at raw 2 s
        assertEquals(180_000, adjustedSpliceTime(written));
        var demuxed = TsTesting.demux(ts);
        assertEquals(0, demuxed.packetsOf(demuxed.tracks().getFirst()).getFirst().pts());
        var read = demuxed.packetsOf(demuxed.tracks().get(1)).getFirst();
        assertSameButAdjustment(section, read.data());
        assertEquals(0, adjustedSpliceTime(read.data()), "the splice is at the first audio packet of the timeline");
    }

    @Test
    @DisplayName("declares the CUEI registration for the program and the cue identifier for the stream")
    void descriptors() {
        var ts = muxed(List.of(spliceInsert(1, 0, 0, 0)));
        var pmt = TsTesting.packets(ts, 188, 0).stream().filter(packet -> TsTesting.pid(packet) == 0x1000)
                .findFirst().orElseThrow();
        assertArrayEquals(new byte[]{0x05, 4, 'C', 'U', 'E', 'I'}, Arrays.copyOfRange(pmt, 5 + 12, 5 + 18));
        var second = 5 + 18 + 5 + ((pmt[5 + 18 + 3] & 0x0F) << 8 | (pmt[5 + 18 + 4] & 0xFF));
        assertEquals(0x86, pmt[second] & 0xFF, "stream type");
        assertArrayEquals(new byte[]{(byte) 0x8A, 1, 1}, Arrays.copyOfRange(pmt, second + 5, second + 8));
    }

    @Test
    @DisplayName("rejects packets that are not whole splice sections")
    void rejects() {
        var broken = spliceInsert(1, 0, 0, 0);
        broken[10] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> muxed(List.of(broken)));
        assertThrows(IllegalArgumentException.class, () -> muxed(List.of(Arrays.copyOf(spliceInsert(1, 0, 0, 0), 20))));
    }

    @Test
    @DisplayName("reads sections that span packets, precede every timestamp or fail their CRC")
    void foreign() {
        var early = spliceInsert(7, 90_000, 0, 0);
        var spanning = spliceInsert(8, 180_000, 0, 600);
        var broken = spliceInsert(9, 0, 0, 0);
        broken[12] ^= 0x40;
        var writer = new TsWriter().pat(1, 0x1000, 0).pmt(0x1000, 1, 0, 0x100, TsWriter.registration("CUEI"),
                List.of(new TsWriter.Stream(0x0F, 0x100), new TsWriter.Stream(0x86, 0x1F0)));
        writer.section(0x1F0, early);
        var adts = new byte[]{(byte) 0xFF, (byte) 0xF1, 0x4C, (byte) 0x80, 0x02, 0x1F, (byte) 0xFC};
        var frame = TsWriter.concat(adts, TsMuxerTest.silentAacFrame());
        frame[3] = (byte) (0x80 | (frame.length >> 11));
        frame[4] = (byte) (frame.length >> 3);
        frame[5] = (byte) ((frame.length & 7) << 5 | 0x1F);
        for (var index = 0; index < 50; index++) {
            writer.pes(0x100, 0xC0, 90_000 + index * 1920L, -1, TsWriter.concat(frame, frame), true);
            if (index == 10) {
                writer.section(0x1F0, spanning);
            }
            if (index == 20) {
                writer.section(0x1F0, broken);
            }
        }
        var demuxed = TsTesting.demux(writer.toByteArray());
        var track = demuxed.tracks().stream().filter(candidate -> candidate.id() == 0x1F0).findFirst().orElseThrow();
        var packets = demuxed.packetsOf(track);
        assertEquals(2, packets.size());
        // the audio starts at raw 90000, the origin of the timeline, so the splices at raw 90000 and 180000 land at
        // timeline times 0 and 90000
        assertSameButAdjustment(early, packets.get(0).data());
        assertEquals(0, adjustedSpliceTime(packets.get(0).data()));
        assertEquals(0, packets.get(0).pts(), "a section before every timestamp gets the first one");
        assertSameButAdjustment(spanning, packets.get(1).data());
        assertEquals(90_000, adjustedSpliceTime(packets.get(1).data()));
        assertEquals(10 * 1920, packets.get(1).pts(), "a section gets the latest decoding time");
    }
}
