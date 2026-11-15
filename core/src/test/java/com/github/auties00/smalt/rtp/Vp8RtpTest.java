/*
 * The descriptor vectors of the Webrtc class come from modules/rtp_rtcp/source/video_rtp_depacketizer_vp8_unittest.cc,
 * Copyright (c) 2019 The WebRTC project authors; BSD 3-Clause License.
 */
package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("VP8 RTP payload format (RFC 7741)")
class Vp8RtpTest {
    static List<RtpChannel.Unit> stream(long seed) {
        var random = new Random(seed);
        var units = new ArrayList<RtpChannel.Unit>();
        for (var index = 0; index < 30; index++) {
            var key = index % 10 == 0;
            var frame = RtpChannel.random(random, key ? 6000 + random.nextInt(2000) : 1 + random.nextInt(2500));
            frame[0] = (byte) (key ? frame[0] & ~1 : frame[0] | 1);
            units.add(new RtpChannel.Unit(frame, index * 3000L, key));
        }
        return units;
    }

    static RtpDepacketizer depacketizer() {
        return RtpDepacketizer.create(RtpPayloadFormat.vp8(), RtpDepacketizerOptions.defaults());
    }

    @ParameterizedTest(name = "{0}-bit picture identifiers")
    @ValueSource(ints = {0, 7, 15})
    @DisplayName("reassembles every frame bit for bit and numbers the pictures")
    void roundTrip(int bits) {
        var units = stream(bits);
        var packetizer = RtpPacketizer.create(RtpPayloadFormat.vp8(),
                RtpChannel.fixed().pictureIdBits(bits).initialSequenceNumber(0x7FF0).build());
        var sent = RtpChannel.packetize(packetizer, units);
        var previousOwner = -1;
        for (var index = 0; index < sent.packets().size(); index++) {
            var packet = sent.packets().get(index);
            var payload = packet.payload();
            var owner = sent.owners().get(index);
            assertEquals(owner != previousOwner, (payload[0] & 0x10) != 0, "S bit on the first packet only");
            assertEquals(bits != 0, (payload[0] & 0x80) != 0);
            if (bits == 15) {
                assertEquals(0x80, payload[1] & 0xFF);
                assertEquals((0x7FF0 + owner) & 0x7FFF, ((payload[2] & 0x7F) << 8) | (payload[3] & 0xFF));
            } else if (bits == 7) {
                assertEquals((0x7FF0 + owner) & 0x7F, payload[2] & 0xFF);
            }
            var last = index == sent.packets().size() - 1 || sent.owners().get(index + 1) != owner;
            assertEquals(last, packet.marker());
            assertTrue(packet.size() <= 1200);
            previousOwner = owner;
        }
        var frames = RtpChannel.depacketize(depacketizer(), RtpChannel.overTheWire(sent.packets()));
        assertEquals(units.size(), frames.size());
        for (var index = 0; index < units.size(); index++) {
            assertArrayEquals(units.get(index).data(), frames.get(index).packet().data());
            assertEquals(units.get(index).key(), frames.get(index).packet().keyframe());
            assertTrue(frames.get(index).complete());
            assertEquals(index * 3000L, frames.get(index).packet().dts());
        }
    }

    @Test
    @DisplayName("loses only the frames whose packets were lost, even when a whole frame vanishes")
    void loss() {
        var units = stream(5);
        var sent = RtpChannel.packetize(RtpPacketizer.create(RtpPayloadFormat.vp8(), RtpChannel.fixed().build()),
                units);
        var middle = sent.owners().indexOf(0) + 2;
        var single = 1;
        while (sent.owners().indexOf(single) != sent.owners().lastIndexOf(single)) {
            single++;
        }
        var lone = sent.owners().indexOf(single);
        var frames = RtpChannel.depacketize(depacketizer(),
                RtpChannel.reorder(RtpChannel.drop(sent.packets(), Set.of(middle, lone)), 5, 1));
        assertEquals(units.size() - 1, frames.size());
        assertFalse(frames.getFirst().complete());
        for (var index = 1; index < frames.size(); index++) {
            var unit = units.get(index < single ? index : index + 1);
            assertTrue(frames.get(index).complete(), "frame " + index);
            assertArrayEquals(unit.data(), frames.get(index).packet().data());
        }
    }

    @Nested
    @DisplayName("libwebrtc payload descriptor vectors")
    class Webrtc {
        private static int size(int... bytes) {
            var payload = new byte[Math.max(bytes.length + 1, 10)];
            for (var index = 0; index < bytes.length; index++) {
                payload[index] = (byte) bytes[index];
            }
            return Vp8Assembler.descriptorSize(payload);
        }

        @Test
        @DisplayName("measures every descriptor form")
        void descriptors() {
            assertEquals(1, size(0b0001_0100, 0x01));
            assertEquals(3, size(0b1010_0000, 0b1000_0000, 17));
            assertEquals(4, size(0b1010_0000, 0b1000_0000, 0x80 | 0x12, 0x34));
            assertEquals(3, size(0b1000_0000, 0b0100_0000, 17));
            assertEquals(3, size(0b1000_0000, 0b0010_0000, 0b1000_0000));
            assertEquals(3, size(0b1000_0000, 0b0001_0000, 17));
            assertEquals(6, size(0b1010_0110, 0b1111_0000, 0x80 | 0x12, 0x34, 42, 0b0111_0001));
        }

        @Test
        @DisplayName("rejects the TooShortHeader descriptor")
        void tooShort() {
            assertThrows(MediaException.Malformed.class,
                    () -> Vp8Assembler.descriptorSize(new byte[]{(byte) 0x80, (byte) 0xF0, (byte) 0x91, 17}));
        }
    }
}
