package com.github.auties00.smalt.container.subtitle;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.DemuxerOptions;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.testkit.subtitle.BitmapStreams;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.DataTrack;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads hand-built VobSub program streams: MPEG-1 and MPEG-2 pack and PES syntax, subpicture units split over
 * several PES packets or cut short, and the index's header, languages and time offset.
 */
@DisplayName("VobSub demuxer")
class VobSubDemuxerTest {
    private static final String SIGNATURE = "# VobSub index file, v7 (do not modify this line!)\n";

    /**
     * Builds an MPEG program stream.
     */
    static final class Stream {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Stream mpeg2Pack(int stuffing) {
            out.writeBytes(new byte[]{0, 0, 1, (byte) 0xBA, 0x44, 0, 4, 0, 4, 1, 1, (byte) 0x89, (byte) 0xC3,
                    (byte) (0xF8 | stuffing)});
            for (var index = 0; index < stuffing; index++) {
                out.write(0xFF);
            }
            return this;
        }

        Stream mpeg1Pack() {
            out.writeBytes(new byte[]{0, 0, 1, (byte) 0xBA, 0x21, 0, 1, 0, 1, (byte) 0x80, 0, 1});
            return this;
        }

        /**
         * Writes an MPEG-2 private stream 1 PES packet.
         *
         * @param pts the timestamp in 90 kHz ticks, or -1 for none
         */
        Stream mpeg2(int substream, long pts, byte[] payload) {
            var header = new ByteArrayOutputStream();
            header.write(0x81);
            header.write(pts >= 0 ? 0x80 : 0);
            header.write(pts >= 0 ? 5 : 0);
            if (pts >= 0) {
                header.writeBytes(timestamp(0x21, pts));
            }
            return pes(0xBD, header.toByteArray(), substream, payload);
        }

        /**
         * Writes an MPEG-1 private stream 1 PES packet.
         *
         * @param mode 0 for no timestamp, 1 for a PTS, 2 for a PTS and DTS
         */
        Stream mpeg1(int substream, int stuffing, boolean std, int mode, long pts, byte[] payload) {
            var header = new ByteArrayOutputStream();
            for (var index = 0; index < stuffing; index++) {
                header.write(0xFF);
            }
            if (std) {
                header.writeBytes(new byte[]{0x40, 0x20});
            }
            switch (mode) {
                case 1 -> header.writeBytes(timestamp(0x21, pts));
                case 2 -> {
                    header.writeBytes(timestamp(0x31, pts));
                    header.writeBytes(timestamp(0x11, pts));
                }
                default -> header.write(0x0F);
            }
            return pes(0xBD, header.toByteArray(), substream, payload);
        }

        Stream pes(int code, byte[] header, int substream, byte[] payload) {
            var length = header.length + (substream >= 0 ? 1 : 0) + payload.length;
            out.writeBytes(new byte[]{0, 0, 1, (byte) code, (byte) (length >> 8), (byte) length});
            out.writeBytes(header);
            if (substream >= 0) {
                out.write(substream);
            }
            out.writeBytes(payload);
            return this;
        }

        Stream raw(int... bytes) {
            for (var value : bytes) {
                out.write(value);
            }
            return this;
        }

        Stream raw(byte[] bytes) {
            out.writeBytes(bytes);
            return this;
        }

        byte[] bytes() {
            return out.toByteArray();
        }

        private static byte[] timestamp(int marker, long time) {
            return new byte[]{(byte) (marker | (time >> 29 & 0x0E)), (byte) (time >> 22),
                    (byte) ((time >> 14 & 0xFE) | 1), (byte) (time >> 7), (byte) ((time << 1 & 0xFE) | 1)};
        }
    }

    static byte[] unit(int stop) {
        var indices = BitmapStreams.indices(16, 4, (x, y) -> (x + y) % 4);
        return BitmapStreams.spu(16, 4, indices, 10, 20, 0, stop, new int[]{3, 2, 1, 0}, new int[]{15, 15, 15, 0},
                false);
    }

    static ContainerProbe.Demuxed demux(byte[] idx, byte[] sub) {
        try (var demuxer = new VobSubDemuxer(idx == null ? null : MediaSource.of(idx), MediaSource.of(sub),
                DemuxerOptions.defaults())) {
            return ContainerProbe.demuxAll(demuxer);
        }
    }

    @Nested
    @DisplayName("program stream syntax")
    class Syntax {
        @Test
        @DisplayName("reads MPEG-1 PES packets with stuffing, an STD buffer and PTS or PTS and DTS")
        void mpeg1() {
            var first = unit(40);
            var second = unit(-1);
            var sub = new Stream().mpeg1Pack().mpeg1(0x20, 3, true, 1, 90_000, first)
                    .mpeg1Pack().mpeg1(0x21, 0, false, 2, 180_000, second).bytes();
            assertTrue(VobSubDemuxer.matches(sub));
            var read = demux(null, sub);
            assertEquals(2, read.tracks().size());
            var a = read.packetsOf(read.tracks().get(0));
            var b = read.packetsOf(read.tracks().get(1));
            assertArrayEquals(first, a.getFirst().data());
            assertEquals(90_000, a.getFirst().pts());
            assertEquals(40 * 1024, a.getFirst().duration());
            assertEquals(180_000, b.getFirst().pts());
            assertEquals(Packet.UNKNOWN_DURATION, b.getFirst().duration(), "a unit without a stop command");
            assertEquals(1, read.tracks().get(0).id());
            assertEquals(2, read.tracks().get(1).id());
        }

        @Test
        @DisplayName("joins a unit split over several PES packets, the later ones without a timestamp")
        void split() {
            var data = unit(20);
            var half = data.length / 2;
            var sub = new Stream().mpeg2Pack(2).mpeg2(0x20, 45_000, Arrays.copyOf(data, half))
                    .mpeg2Pack(0).mpeg2(0x20, -1, Arrays.copyOfRange(data, half, data.length))
                    .mpeg2Pack(0).mpeg1(0x20, 0, false, 0, 0, Arrays.copyOf(data, 1))
                    .mpeg2Pack(0).mpeg1(0x20, 0, false, 0, 0, Arrays.copyOfRange(data, 1, data.length)).bytes();
            var read = demux(null, sub);
            var packets = read.packetsOf(read.tracks().getFirst());
            assertEquals(2, packets.size());
            assertArrayEquals(data, packets.get(0).data());
            assertArrayEquals(data, packets.get(1).data());
            assertEquals(45_000, packets.get(0).pts());
            assertEquals(45_000, packets.get(1).pts(), "a unit without a timestamp takes the previous one's");
        }

        @Test
        @DisplayName("drops a unit restarted by a new timestamp, one declaring less than four bytes, and other streams")
        void dropped() {
            var data = unit(20);
            var sub = new Stream().mpeg2Pack(0)
                    .mpeg2(0x20, 1000, Arrays.copyOf(data, 6))
                    .mpeg2(0x20, 2000, data)
                    .mpeg2(0x20, 3000, new byte[]{0, 2, 0})
                    .mpeg2(0x80, 3000, new byte[]{1, 2, 3, 4})
                    .pes(0xE0, new byte[]{(byte) 0x81, 0, 0}, -1, new byte[]{1, 2})
                    .pes(0xBE, new byte[0], -1, new byte[4])
                    .raw(0, 0, 1, 0xB9)
                    .raw(1, 2, 3)
                    .bytes();
            var read = demux(null, sub);
            assertEquals(1, read.tracks().size());
            var packets = read.packetsOf(read.tracks().getFirst());
            assertEquals(1, packets.size());
            assertEquals(2000, packets.getFirst().pts());
        }

        @Test
        @DisplayName("stops at a truncated pack header or PES packet")
        void truncated() {
            var data = unit(20);
            var whole = new Stream().mpeg2Pack(0).mpeg2(0x20, 0, data).bytes();
            for (var length = 4; length < whole.length; length += 3) {
                var cut = Arrays.copyOf(whole, length);
                var read = demux(null, cut);
                assertTrue(read.tracks().size() <= 1);
                if (!read.tracks().isEmpty()) {
                    assertTrue(read.packetsOf(read.tracks().getFirst()).isEmpty(), "length " + length);
                }
            }
            assertEquals(1, demux(null, whole).tracks().size());
            var mpeg1 = new Stream().mpeg1Pack().mpeg1(0x20, 2, true, 2, 0, data).bytes();
            for (var length = 13; length < 30; length++) {
                demux(null, Arrays.copyOf(mpeg1, length));
            }
        }

        @Test
        @DisplayName("refuses input that is not a program stream")
        void notProgramStream() {
            assertThrows(MediaException.Malformed.class, () -> demux(null, new byte[]{1, 2, 3, 4, 5, 6}));
            assertThrows(MediaException.Malformed.class, () -> demux(null, new byte[2]));
            assertThrows(MediaException.Malformed.class, () -> demux(null, new byte[]{0, 0, 1, (byte) 0xBB, 0, 0}));
        }
    }

    @Nested
    @DisplayName("signatures")
    class Signatures {
        @Test
        @DisplayName("match only a pack followed by a subpicture PES packet")
        void sub() {
            var data = unit(20);
            assertTrue(VobSubDemuxer.matches(new Stream().mpeg2Pack(0).mpeg2(0x20, 0, data).bytes()));
            assertTrue(VobSubDemuxer.matches(new Stream().mpeg1Pack().mpeg1(0x3F, 1, true, 1, 0, data).bytes()));
            assertFalse(VobSubDemuxer.matches(new byte[13]));
            assertFalse(VobSubDemuxer.matches(new Stream().mpeg2Pack(0).mpeg2(0x80, 0, data).bytes()), "AC-3");
            assertFalse(VobSubDemuxer.matches(new Stream().mpeg2Pack(0).pes(0xE0, new byte[]{(byte) 0x81, 0, 0}, 0x20,
                    data).bytes()), "video");
            var noPack = new Stream().mpeg2(0x20, 0, data).raw(new byte[14]).bytes();
            assertFalse(VobSubDemuxer.matches(noPack));
            var wrongCode = new Stream().mpeg2Pack(0).mpeg2(0x20, 0, data).bytes();
            for (var index : new int[]{0, 1, 2}) {
                var copy = wrongCode.clone();
                copy[index] = 7;
                assertFalse(VobSubDemuxer.matches(copy), "byte " + index);
            }
            var shortPack = Arrays.copyOf(new Stream().mpeg2Pack(7).bytes(), 16);
            assertFalse(VobSubDemuxer.matches(shortPack));
            var afterPack = new Stream().mpeg2Pack(0).raw(0, 0, 1, 0xBD, 0, 0, 0x81).bytes();
            assertFalse(VobSubDemuxer.matches(Arrays.copyOf(afterPack, 22)), "the PES header is cut");
            for (var index = 14; index < 18; index++) {
                var copy = new Stream().mpeg2Pack(0).mpeg2(0x20, 0, data).bytes();
                copy[index] = 9;
                assertFalse(VobSubDemuxer.matches(copy), "byte " + index);
            }
        }

        @Test
        @DisplayName("match an index with or without a byte order mark")
        void index() {
            var text = SIGNATURE.getBytes(StandardCharsets.UTF_8);
            assertTrue(VobSubDemuxer.matchesIndex(text));
            var bom = new byte[text.length + 3];
            bom[0] = (byte) 0xEF;
            bom[1] = (byte) 0xBB;
            bom[2] = (byte) 0xBF;
            System.arraycopy(text, 0, bom, 3, text.length);
            assertTrue(VobSubDemuxer.matchesIndex(bom));
            assertFalse(VobSubDemuxer.matchesIndex(new byte[]{(byte) 0xEF, (byte) 0xBB}));
            assertFalse(VobSubDemuxer.matchesIndex(new byte[]{(byte) 0xEF, 0, 0, '#'}));
            assertFalse(VobSubDemuxer.matchesIndex(new byte[]{(byte) 0xEF, (byte) 0xBB, 0, '#'}));
            assertFalse(VobSubDemuxer.matchesIndex("size: 720x480".getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Nested
    @DisplayName("index")
    class Index {
        private final byte[] sub = new Stream().mpeg2Pack(0).mpeg2(0x20, 90_000, unit(20))
                .mpeg2(0x22, 90_000, unit(20)).bytes();

        private ContainerProbe.Demuxed withIndex(String body) {
            return demux((SIGNATURE + body).getBytes(StandardCharsets.UTF_8), sub);
        }

        @Test
        @DisplayName("shifts timestamps by a time offset in milliseconds or hh:mm:ss:ms, either sign")
        void offsets() {
            assertEquals(90_000 + 1500 * 90, first(withIndex("time offset: 1500\n")));
            assertEquals(90_000 - 500 * 90, first(withIndex("time offset: -500\n")));
            assertEquals(90_000 + (61_000 + 7) * 90L, first(withIndex("time offset: 00:01:01:007\n")));
            assertEquals(90_000, first(withIndex("time offset: soon\n")), "an unreadable offset is ignored");
            assertEquals(90_000, first(withIndex("time offset: 1:2:x:4\n")));
            assertEquals(90_000, first(withIndex("id: en, index: 0\ntime offset: 900\n")),
                    "an offset after the first stream is not part of the header");
        }

        @Test
        @DisplayName("gives each stream the language of its id line and every track the header")
        void languages() {
            var read = withIndex("size: 720x480\nid: en, index: 0\ntimestamp: 00:00:01:000, filepos: 000000000\n"
                    + "id: --, index: 1\nid: de, index: 2\nid: fr, index: 40\nid: it, index: x\nid: es\n"
                    + "delay: 0\n");
            assertEquals(2, read.tracks().size());
            assertEquals("eng", read.tracks().get(0).language().orElseThrow());
            assertEquals("ger", read.tracks().get(1).language().orElseThrow());
            var header = new String(read.tracks().getFirst().codecConfig().orElseThrow(), StandardCharsets.UTF_8);
            assertTrue(header.contains("size: 720x480"), header);
            assertFalse(header.contains("id:"), header);
        }

        @Test
        @DisplayName("declares the index's streams when the program stream has none")
        void tracksFromIndex() {
            var read = demux((SIGNATURE + "id: en, index: 0\nid: , index: 3\n").getBytes(StandardCharsets.UTF_8),
                    new Stream().mpeg2Pack(0).bytes());
            assertEquals(2, read.tracks().size());
            assertEquals(1, read.tracks().get(0).id());
            assertEquals(4, read.tracks().get(1).id());
            assertTrue(read.tracks().get(1).language().isEmpty());
            assertEquals(Codec.DVD_SUBTITLE, read.tracks().get(1).codec());
        }

        @Test
        @DisplayName("refuses an index without its signature")
        void malformed() {
            assertThrows(MediaException.Malformed.class,
                    () -> demux("size: 720x480\n".getBytes(StandardCharsets.UTF_8), sub));
        }

        private static long first(ContainerProbe.Demuxed read) {
            return read.packetsOf(read.tracks().getFirst()).getFirst().pts();
        }
    }

    @Nested
    @DisplayName("muxer")
    class Muxer {
        private static final Rational TICKS = Rational.timeBaseOf(90_000);

        @Test
        @DisplayName("writes a unit larger than a pack over several packs, stamped from its DTS when it has no PTS")
        void largeUnit() {
            var indices = BitmapStreams.indices(720, 60, (x, y) -> (x * 7 + y * 3) % 4);
            var data = BitmapStreams.spu(720, 60, indices, 0, 400, 0, 50, new int[]{3, 2, 1, 0},
                    new int[]{15, 15, 15, 0}, false);
            assertTrue(data.length > 4096, "the unit spans several packs");
            var idx = MediaSink.memory();
            var sub = MediaSink.memory();
            try (var muxer = new VobSubMuxer(idx, sub)) {
                muxer.addTrack(DataTrack.builder().id(5).codec(Codec.DVD_SUBTITLE).timeBase(TICKS).language("und")
                        .build());
                muxer.addTrack(DataTrack.builder().id(6).codec(Codec.DVD_SUBTITLE).timeBase(TICKS).language("zzz")
                        .build());
                muxer.writePacket(new Packet(5, data, Packet.NO_TIMESTAMP, 9000, 1000, true));
                muxer.writePacket(new Packet(6, unit(10), Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 1000, true));
                muxer.finish();
            }
            var index = new String(idx.toByteArray(), StandardCharsets.UTF_8);
            assertTrue(index.startsWith("# VobSub index file"), index);
            assertTrue(index.contains("id: --, index: 0") && index.contains("id: --, index: 1"), index);
            assertTrue(index.contains("timestamp: 00:00:00:100"), index);
            assertEquals(0, sub.toByteArray().length % 2048);
            var read = demux(idx.toByteArray(), sub.toByteArray());
            var packets = read.packetsOf(read.tracks().getFirst());
            assertArrayEquals(data, packets.getFirst().data());
            assertEquals(9000, packets.getFirst().pts());
        }

        @Test
        @DisplayName("holds at most 32 subpicture streams")
        void streamLimit() {
            try (var muxer = new VobSubMuxer(MediaSink.memory(), MediaSink.memory())) {
                for (var id = 1; id <= 32; id++) {
                    muxer.addTrack(DataTrack.builder().id(id).codec(Codec.DVD_SUBTITLE).timeBase(TICKS).build());
                }
                assertThrows(IllegalArgumentException.class, () -> muxer.addTrack(
                        DataTrack.builder().id(33).codec(Codec.DVD_SUBTITLE).timeBase(TICKS).build()));
            }
            assertThrows(NullPointerException.class, () -> VobSubMuxer.supports(null));
            assertTrue(VobSubMuxer.supports(Codec.DVD_SUBTITLE));
            assertFalse(VobSubMuxer.supports(Codec.PGS));
        }
    }
}
