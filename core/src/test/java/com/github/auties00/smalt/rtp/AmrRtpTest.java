package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.internal.codec.AmrConfigs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AMR and AMR-WB RTP payload format (RFC 4867)")
class AmrRtpTest {
    private static final int[] NB_BITS = {95, 103, 118, 134, 148, 159, 204, 244, 39};
    private static final int[] WB_BITS = {132, 177, 253, 285, 317, 365, 397, 461, 477, 40};

    /** A storage-format frame of a type, with random speech bits and zero padding. */
    static byte[] frame(boolean wideband, int type, Random random) {
        if (type == 15) {
            return new byte[]{(byte) ((15 << 3) | 0x04)};
        }
        var bits = (wideband ? WB_BITS : NB_BITS)[type];
        var frame = new byte[1 + (bits + 7) / 8];
        assertEquals(frame.length - 1, AmrConfigs.payloadSize(wideband, type));
        random.nextBytes(frame);
        frame[0] = (byte) ((type << 3) | 0x04);
        var spare = frame.length * 8 - 8 - bits;
        frame[frame.length - 1] &= (byte) (0xFF << spare);
        return frame;
    }

    /** Frame-blocks cycling through every frame type, NO_DATA and SID included. */
    static List<RtpChannel.Unit> stream(boolean wideband, int channels, long seed) {
        var random = new Random(seed);
        var types = wideband ? 10 : 9;
        var units = new ArrayList<RtpChannel.Unit>();
        for (var block = 0; block < 48; block++) {
            var out = new ByteArrayOutputStream();
            for (var channel = 0; channel < channels; channel++) {
                var type = (block + channel) % (types + 1);
                out.writeBytes(frame(wideband, type == types ? 15 : type, random));
            }
            units.add(new RtpChannel.Unit(out.toByteArray(), block * (wideband ? 320L : 160L), true));
        }
        return units;
    }

    static Stream<Arguments> modes() {
        return Stream.of(
                Arguments.of("bandwidth-efficient", false, 1, "", 1, 1),
                Arguments.of("bandwidth-efficient, 3 frames per packet, stereo", false, 2, "", 3, 1),
                Arguments.of("octet-aligned", false, 1, "octet-align=1", 1, 1),
                Arguments.of("octet-aligned with CRCs", true, 1, "octet-align=1; crc=1", 4, 1),
                Arguments.of("robust sorting, stereo", false, 2, "octet-align=1; robust-sorting=1", 3, 1),
                Arguments.of("interleaving, 2 blocks per packet, length 3", true, 1, "octet-align=1; interleaving=6",
                        2, 3),
                Arguments.of("everything, 4 channels", true, 4,
                        "octet-align=1; crc=1; robust-sorting=1; interleaving=8", 2, 4));
    }

    static RtpPayloadFormat format(boolean wideband, int channels, String fmtp) {
        return RtpPayloadFormat.fromSdp((wideband ? "AMR-WB/16000/" : "AMR/8000/") + channels, fmtp);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modes")
    @DisplayName("carries every frame-block bit for bit and in order")
    void roundTrip(String name, boolean wideband, int channels, String fmtp, int frames, int interleaving) {
        var units = stream(wideband, channels, name.hashCode());
        var format = format(wideband, channels, fmtp);
        var packetizer = RtpPacketizer.create(format, RtpChannel.fixed().framesPerPacket(frames)
                .interleavingLength(interleaving).build());
        var sent = RtpChannel.packetize(packetizer, units);
        assertTrue(sent.packets().getFirst().marker());
        assertTrue(sent.packets().stream().skip(1).noneMatch(RtpPacket::marker));
        var output = RtpChannel.depacketize(RtpDepacketizer.create(format, RtpDepacketizerOptions.defaults()),
                RtpChannel.overTheWire(RtpChannel.reorder(sent.packets(), 3, 9)));
        assertEquals(units.size(), output.size());
        for (var index = 0; index < units.size(); index++) {
            assertArrayEquals(units.get(index).data(), output.get(index).packet().data(), "block " + index);
            assertEquals(units.get(index).timestamp(), output.get(index).packet().pts());
            assertEquals(wideband ? 320 : 160, output.get(index).packet().duration());
        }
    }

    @Nested
    @DisplayName("payload layout")
    class Layout {
        @Test
        @DisplayName("packs the bandwidth-efficient header, table of contents and bits as RFC 4867 4.3.4 orders them")
        void bandwidthEfficient() {
            var frame = frame(false, 7, new Random(1));
            var packets = RtpPacketizer.create(RtpPayloadFormat.amr(false, false), RtpChannel.fixed().build())
                    .packetize(new RtpChannel.Unit(frame, 0, true).packet());
            var payload = packets.getFirst().payload();
            assertEquals(0xF3, payload[0] & 0xFF, "CMR 15, F 0 and the first three bits of FT 7");
            assertEquals(0xC0 | ((frame[1] & 0xFF) >>> 2), payload[1] & 0xFF, "the last FT bit, Q, then d(0) to d(5)");
            assertEquals((4 + 6 + 244 + 7) / 8, payload.length);
        }

        @Test
        @DisplayName("reads the octet-aligned example of RFC 4867 4.4.5.1")
        void octetAlignedExample() {
            var random = new Random(2);
            var first = frame(false, 5, random);
            var second = frame(false, 5, random);
            var payload = new ByteArrayOutputStream();
            payload.write(6 << 4);
            payload.write(0x80 | (5 << 3) | 0x04);
            payload.write((5 << 3) | 0x04);
            payload.write(first, 1, first.length - 1);
            payload.write(second, 1, second.length - 1);
            var output = RtpChannel.depacketize(RtpDepacketizer.create(RtpPayloadFormat.amr(false, true),
                    RtpDepacketizerOptions.defaults()), List.of(new RtpPacket(true, 97, 1, 800, 1,
                    payload.toByteArray())));
            assertEquals(2, output.size());
            assertArrayEquals(first, output.get(0).packet().data());
            assertArrayEquals(second, output.get(1).packet().data());
            assertEquals(160, output.get(1).packet().pts());
        }

        @Test
        @DisplayName("lays out the two-channel CRC, interleaving and robust sorting example of RFC 4867 4.4.5.2")
        void robustExample() {
            var random = new Random(3);
            var blocks = new ArrayList<RtpChannel.Unit>();
            var frames = new ArrayList<byte[]>();
            for (var block = 0; block < 4; block++) {
                var left = frame(false, 5, random);
                var right = frame(false, 5, random);
                frames.add(left);
                frames.add(right);
                var data = new byte[left.length + right.length];
                System.arraycopy(left, 0, data, 0, left.length);
                System.arraycopy(right, 0, data, left.length, right.length);
                blocks.add(new RtpChannel.Unit(data, block * 160L, true));
            }
            var format = format(false, 2, "crc=1; robust-sorting=1; interleaving=4");
            var sent = RtpChannel.packetize(RtpPacketizer.create(format, RtpChannel.fixed().framesPerPacket(2)
                    .interleavingLength(2).build()), blocks);
            var payload = sent.packets().getFirst().payload();
            assertEquals(0xF0, payload[0] & 0xFF);
            assertEquals(0x10, payload[1] & 0xFF, "ILL 1, ILP 0");
            assertEquals(0x80 | (5 << 3) | 0x04, payload[2] & 0xFF);
            assertEquals((5 << 3) | 0x04, payload[5] & 0xFF, "the last entry, 3R, has F clear");
            assertEquals(AmrFormat.crc(frames.get(0), 1, 75), payload[6] & 0xFF, "CRC1L");
            assertEquals(AmrFormat.crc(frames.get(5), 1, 75), payload[9] & 0xFF, "CRC3R");
            assertEquals(frames.get(0)[1], payload[10], "f1L(0..7)");
            assertEquals(frames.get(1)[1], payload[11], "f1R(0..7)");
            assertEquals(frames.get(4)[1], payload[12], "f3L(0..7)");
            assertEquals(frames.get(5)[1], payload[13], "f3R(0..7)");
            assertEquals(frames.get(0)[2], payload[14], "f1L(8..15)");
            assertEquals(1 + 1 + 4 + 4 + 4 * 20, payload.length);
            assertEquals(0, sent.packets().getFirst().timestamp() - 0xFFFF_F000L);
            assertEquals(160, sent.packets().get(1).timestamp() - 0xFFFF_F000L, "ILP 1 starts at block 2");
        }
    }

    @Nested
    @DisplayName("damage")
    class Damage {
        @Test
        @DisplayName("clears the quality bit when a class A bit is damaged, not when a class B bit is")
        void crc() {
            var frame = frame(false, 7, new Random(4));
            var format = format(false, 1, "crc=1");
            var payload = RtpPacketizer.create(format, RtpChannel.fixed().build())
                    .packetize(new RtpChannel.Unit(frame, 0, true).packet()).getFirst().payload();
            var classA = payload.clone();
            classA[3 + 80 / 8] ^= (byte) (0x80 >>> (80 % 8));
            var classB = payload.clone();
            classB[3 + 81 / 8] ^= (byte) (0x80 >>> (81 % 8));
            var depacketizer = RtpDepacketizer.create(format, RtpDepacketizerOptions.builder().reorderWindow(0)
                    .build());
            var damagedA = depacketizer.push(new RtpPacket(false, 97, 1, 0, 1, classA)).getFirst().packet().data();
            var damagedB = depacketizer.push(new RtpPacket(false, 97, 2, 160, 1, classB)).getFirst().packet().data();
            assertEquals(0, damagedA[0] & 0x04, "Q cleared");
            assertEquals(0x04, damagedB[0] & 0x04, "Q kept");
        }

        @Test
        @DisplayName("keeps the surviving blocks of an interleaved stream in order when a packet is lost")
        void interleavedLoss() {
            var units = stream(false, 1, 5);
            var format = format(false, 1, "octet-align=1; interleaving=8");
            var sent = RtpChannel.packetize(RtpPacketizer.create(format, RtpChannel.fixed().framesPerPacket(2)
                    .interleavingLength(4).build()), units);
            var output = RtpChannel.depacketize(RtpDepacketizer.create(format, RtpDepacketizerOptions.defaults()),
                    RtpChannel.drop(sent.packets(), Set.of(5)));
            assertEquals(units.size() - 2, output.size());
            var previous = -1L;
            for (var frame : output) {
                assertTrue(frame.packet().pts() > previous);
                previous = frame.packet().pts();
                assertArrayEquals(units.get((int) (frame.packet().pts() / 160)).data(), frame.packet().data());
            }
        }

        @Test
        @DisplayName("discards forbidden frame types, length mismatches and bad interleaving indices")
        void rejects() {
            var immediate = RtpDepacketizerOptions.builder().reorderWindow(0).build();
            var depacketizer = RtpDepacketizer.create(format(false, 1, "octet-align=1"), immediate);
            depacketizer.push(new RtpPacket(false, 97, 1, 0, 1, new byte[]{(byte) 0xF0, 9 << 3}));
            depacketizer.push(new RtpPacket(false, 97, 2, 160, 1, new byte[]{(byte) 0xF0, 7 << 3, 1, 2}));
            depacketizer.push(new RtpPacket(false, 97, 3, 320, 1, new byte[]{(byte) 0xF0, (byte) 0x80}));
            assertEquals(3, depacketizer.discardedPackets());
            var interleaved = RtpDepacketizer.create(format(false, 1, "interleaving=4"), immediate);
            interleaved.push(new RtpPacket(false, 97, 1, 0, 1, new byte[]{(byte) 0xF0, 0x12, (byte) 0x7C}));
            assertEquals(1, interleaved.discardedPackets());
        }

        @Test
        @DisplayName("emits a block sent twice only once")
        void redundancy() {
            var units = stream(true, 1, 6);
            var packets = List.of(new RtpPacket(false, 97, 1, 0, 1, concat(units.get(0), units.get(1))),
                    new RtpPacket(false, 97, 2, 320, 1, concat(units.get(1), units.get(2))));
            var output = RtpChannel.depacketize(RtpDepacketizer.create(RtpPayloadFormat.amr(true, true),
                    RtpDepacketizerOptions.defaults()), packets);
            assertEquals(3, output.size());
            for (var index = 0; index < 3; index++) {
                assertArrayEquals(units.get(index).data(), output.get(index).packet().data());
            }
        }

        private static byte[] concat(RtpChannel.Unit a, RtpChannel.Unit b) {
            var out = new ByteArrayOutputStream();
            out.write(0xF0);
            out.write(0x80 | (a.data()[0] & 0x7C));
            out.write(b.data()[0] & 0x7C);
            out.write(a.data(), 1, a.data().length - 1);
            out.write(b.data(), 1, b.data().length - 1);
            return out.toByteArray();
        }

        @Test
        @DisplayName("rejects an interleaving group larger than the signaled one")
        void group() {
            assertThrows(IllegalArgumentException.class, () -> RtpPacketizer.create(format(false, 1,
                    "interleaving=4"), RtpChannel.fixed().framesPerPacket(3).interleavingLength(2).build()));
        }
    }
}
