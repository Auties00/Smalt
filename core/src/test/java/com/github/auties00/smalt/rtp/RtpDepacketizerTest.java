package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the payload-independent part of depacketization (sequence order, loss accounting, timestamp extension,
 * stream changes) through the Opus format, whose packets map one to one onto units, and the Opus format itself.
 */
@DisplayName("RtpDepacketizer")
class RtpDepacketizerTest {
    /** An Opus packet of one 20 ms CELT frame whose second byte carries a marker. */
    static byte[] opus(int marker) {
        return new byte[]{(byte) 0xF8, (byte) marker, 1, 2};
    }

    static RtpPacket packet(int sequence, long timestamp, int marker) {
        return new RtpPacket(false, 111, sequence & 0xFFFF, timestamp & 0xFFFF_FFFFL, 42, opus(marker));
    }

    static RtpDepacketizer opusDepacketizer(int window) {
        return RtpDepacketizer.create(RtpPayloadFormat.opus(true),
                RtpDepacketizerOptions.builder().reorderWindow(window).build());
    }

    static List<Integer> markers(List<RtpFrame> frames) {
        return frames.stream().map(frame -> frame.packet().data()[1] & 0xFF).toList();
    }

    @Nested
    @DisplayName("sequence order")
    class Order {
        @Test
        @DisplayName("restores order across the 16-bit sequence wrap and extends timestamps across the 32-bit wrap")
        void wraps() {
            var depacketizer = opusDepacketizer(4);
            var frames = new ArrayList<RtpFrame>();
            long timestamp = 0xFFFF_FC00L;
            int[] order = {0, 2, 1, 3, 5, 4, 6, 7};
            for (var index : order) {
                frames.addAll(depacketizer.push(packet(65_533 + index, timestamp + 960L * index, index)));
            }
            frames.addAll(depacketizer.flush());
            assertEquals(List.of(0, 1, 2, 3, 4, 5, 6, 7), markers(frames));
            for (var index = 0; index < 8; index++) {
                assertEquals(960L * index, frames.get(index).packet().pts());
                assertEquals(0xFFFF_FC00L + 960L * index, frames.get(index).rtpTimestamp());
                assertEquals(960, frames.get(index).packet().duration());
            }
            assertEquals(0, depacketizer.lostPackets());
        }

        @Test
        @DisplayName("holds output while a packet is missing and declares it lost once the window passes")
        void window() {
            var depacketizer = opusDepacketizer(2);
            assertTrue(depacketizer.push(packet(12, 1920, 2)).isEmpty(), "the stream start is held");
            assertTrue(depacketizer.push(packet(10, 0, 0)).isEmpty());
            assertEquals(List.of(0), markers(depacketizer.push(packet(13, 2880, 3))), "10 arrived late but starts");
            assertEquals(List.of(2, 3, 4), markers(depacketizer.push(packet(14, 3840, 4))));
            assertEquals(1, depacketizer.lostPackets());
            assertTrue(depacketizer.push(packet(11, 960, 1)).isEmpty());
            assertEquals(1, depacketizer.latePackets());
        }

        @Test
        @DisplayName("releases at once with a zero window and counts duplicates")
        void zeroWindow() {
            var depacketizer = opusDepacketizer(0);
            assertEquals(1, depacketizer.push(packet(1, 0, 0)).size());
            assertEquals(1, depacketizer.push(packet(3, 1920, 2)).size());
            assertEquals(1, depacketizer.lostPackets());
            assertTrue(depacketizer.push(packet(3, 1920, 2)).isEmpty());
            assertEquals(1, depacketizer.latePackets() + depacketizer.duplicatePackets());
        }

        @Test
        @DisplayName("follows a sequence number jump once two consecutive packets confirm it")
        void jump() {
            var depacketizer = opusDepacketizer(4);
            depacketizer.push(packet(100, 0, 0));
            assertTrue(depacketizer.push(packet(40_000, 960, 1)).isEmpty());
            var frames = new ArrayList<>(depacketizer.push(packet(40_001, 1920, 2)));
            frames.addAll(depacketizer.push(packet(40_002, 2880, 3)));
            frames.addAll(depacketizer.flush());
            assertEquals(List.of(0, 1, 2, 3), markers(frames));
            assertTrue(depacketizer.push(packet(5_000, 3840, 9)).isEmpty(), "a lone stray packet is ignored");
            assertEquals(List.of(4), markers(depacketizer.push(packet(40_003, 3840, 4))));
        }

        @Test
        @DisplayName("restarts on a new synchronization source")
        void newSource() {
            var depacketizer = opusDepacketizer(4);
            depacketizer.push(packet(1, 0, 0));
            depacketizer.push(packet(3, 1920, 2));
            var frames = new ArrayList<>(depacketizer.push(new RtpPacket(false, 111, 900, 77_000, 43, opus(7))));
            assertEquals(List.of(0, 2), markers(frames));
            frames.addAll(depacketizer.flush());
            assertEquals(List.of(0, 2, 7), markers(frames));
            assertEquals(0, frames.get(2).packet().pts(), "a new source starts its own timeline");
            assertEquals(1, depacketizer.lostPackets());
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"AMR/8000/1", "mpeg4-generic/48000/2"})
        @DisplayName("keeps every audio unit of a new source whose timestamps are below the old source's")
        void newSourceEarlierTimestamps(String rtpmap) {
            var format = rtpmap.startsWith("AMR") ? RtpPayloadFormat.amr(false, true)
                    : RtpPayloadFormat.aacHbr(Mpeg4GenericRtpTest.CONFIG);
            var random = new Random(5);
            var units = new ArrayList<RtpChannel.Unit>();
            for (var index = 0; index < 10; index++) {
                var data = rtpmap.startsWith("AMR") ? AmrRtpTest.frame(false, 7, random)
                        : RtpChannel.random(random, 100);
                units.add(new RtpChannel.Unit(data, index * 1024L, true));
            }
            var packets = new ArrayList<RtpPacket>();
            packets.addAll(RtpChannel.packetize(RtpPacketizer.create(format, RtpPacketizerOptions.builder()
                    .ssrc(1).initialSequenceNumber(100).initialTimestamp(0xF000_0000L).build()), units).packets());
            packets.addAll(RtpChannel.packetize(RtpPacketizer.create(format, RtpPacketizerOptions.builder()
                    .ssrc(2).initialSequenceNumber(5000).initialTimestamp(1000).build()), units).packets());
            var frames = RtpChannel.depacketize(RtpDepacketizer.create(format, RtpDepacketizerOptions.defaults()),
                    packets);
            assertEquals(2 * units.size(), frames.size());
            for (var index = 0; index < frames.size(); index++) {
                assertArrayEquals(units.get(index % units.size()).data(), frames.get(index).packet().data());
            }
        }
    }

    @Nested
    @DisplayName("Opus (RFC 7587)")
    class Opus {
        @Test
        @DisplayName("sends each Opus packet in one RTP packet and converts timestamps both ways")
        void roundTrip() {
            var random = new Random(1);
            var units = new ArrayList<RtpChannel.Unit>();
            for (var index = 0; index < 50; index++) {
                var data = RtpChannel.random(random, 3 + random.nextInt(400));
                data[0] = (byte) ((data[0] & ~3) | 0x00);
                units.add(new RtpChannel.Unit(data, index * 20L, true));
            }
            var packetizer = RtpPacketizer.create(RtpPayloadFormat.opus(false), RtpChannel.fixed()
                    .timeBase(Rational.MILLISECONDS).payloadType(111).build());
            var sent = RtpChannel.packetize(packetizer, units);
            assertEquals(units.size(), sent.packets().size());
            for (var index = 0; index < units.size(); index++) {
                var packet = sent.packets().get(index);
                assertArrayEquals(units.get(index).data(), packet.payload());
                assertEquals((0xFFFF_F000L + 960L * index) & 0xFFFF_FFFFL, packet.timestamp());
                assertEquals(111, packet.payloadType());
                assertTrue(!packet.marker());
            }
            var frames = RtpChannel.depacketize(RtpDepacketizer.create(RtpPayloadFormat.opus(false),
                    RtpDepacketizerOptions.builder().timeBase(Rational.MILLISECONDS).trackId(7).build()),
                    sent.packets());
            for (var index = 0; index < units.size(); index++) {
                var packet = frames.get(index).packet();
                assertArrayEquals(units.get(index).data(), packet.data());
                assertEquals(index * 20L, packet.pts());
                assertEquals(7, packet.trackId());
                assertTrue(packet.keyframe());
            }
        }

        @Test
        @DisplayName("refuses an Opus packet larger than a packet and discards an empty one")
        void limits() {
            assertThrows(MediaException.LimitExceeded.class, () -> RtpPacketizer.create(RtpPayloadFormat.opus(false),
                    RtpChannel.fixed().build()).packetize(new RtpChannel.Unit(new byte[1300], 0, true).packet()));
            var depacketizer = opusDepacketizer(0);
            assertTrue(depacketizer.push(new RtpPacket(false, 111, 1, 0, 1, new byte[0])).isEmpty());
            assertTrue(depacketizer.push(new RtpPacket(false, 111, 2, 0, 1, new byte[]{3})).isEmpty());
            assertEquals(2, depacketizer.discardedPackets());
        }
    }

    @Test
    @DisplayName("bounds a reassembled unit by the size limit")
    void limit() {
        var depacketizer = RtpDepacketizer.create(RtpPayloadFormat.vp8(), RtpDepacketizerOptions.builder()
                .limits(MediaLimits.builder().maxBytes(2000).build()).build());
        var big = new byte[3000];
        var packets = RtpPacketizer.create(RtpPayloadFormat.vp8(), RtpChannel.fixed().build())
                .packetize(new RtpChannel.Unit(big, 0, true).packet());
        assertTrue(RtpChannel.depacketize(depacketizer, packets).stream().noneMatch(RtpFrame::complete));
        assertTrue(depacketizer.discardedPackets() > 0);
    }

    @Test
    @DisplayName("rejects formats it does not implement")
    void unsupported() {
        var format = RtpPayloadFormat.builder("PCMU", 8000).build();
        assertThrows(MediaException.Unsupported.class,
                () -> RtpDepacketizer.create(format, RtpDepacketizerOptions.defaults()));
        assertThrows(MediaException.Unsupported.class,
                () -> RtpPacketizer.create(format, RtpPacketizerOptions.defaults()));
    }
}
