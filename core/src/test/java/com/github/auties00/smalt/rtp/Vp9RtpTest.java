/*
 * The descriptor vectors of the Webrtc class come from modules/rtp_rtcp/source/video_rtp_depacketizer_vp9_unittest.cc,
 * Copyright (c) 2019 The WebRTC project authors; BSD 3-Clause License.
 */
package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("VP9 RTP payload format (RFC 9628)")
class Vp9RtpTest {
    /** A profile 0 key frame of the given size: frame marker, sync code, BT.601 studio range, then noise. */
    static byte[] keyFrame(int width, int height, int length, Random random) {
        var writer = new MsbBitWriter(16);
        writer.write(0b10, 2);
        writer.write(0, 2);
        writer.write(0, 1);
        writer.write(0, 1);
        writer.write(1, 1);
        writer.write(0, 1);
        writer.write(0x498342, 24);
        writer.write(1, 3);
        writer.write(0, 1);
        writer.write(width - 1, 16);
        writer.write(height - 1, 16);
        writer.alignWithZeros();
        var header = writer.toByteArray();
        var frame = RtpChannel.random(random, length);
        System.arraycopy(header, 0, frame, 0, header.length);
        frame[frame.length - 1] = 0;
        return frame;
    }

    /** An inter frame, shown or hidden. */
    static byte[] interFrame(boolean shown, int length, Random random) {
        var frame = RtpChannel.random(random, length);
        frame[0] = (byte) (shown ? 0x86 : 0x84);
        if (length > 1) {
            // A last byte of 110xxxxx could read as a superframe index.
            frame[length - 1] = 0;
        }
        return frame;
    }

    static byte[] superframe(byte[]... frames) {
        var out = new ByteArrayOutputStream();
        var largest = 0;
        for (var frame : frames) {
            out.writeBytes(frame);
            largest = Math.max(largest, frame.length);
        }
        var magnitude = largest < 0x100 ? 1 : 2;
        var marker = 0xC0 | ((magnitude - 1) << 3) | (frames.length - 1);
        out.write(marker);
        for (var frame : frames) {
            for (var index = 0; index < magnitude; index++) {
                out.write(frame.length >>> (8 * index));
            }
        }
        out.write(marker);
        return out.toByteArray();
    }

    static List<RtpChannel.Unit> stream(long seed) {
        var random = new Random(seed);
        var units = new ArrayList<RtpChannel.Unit>();
        for (var index = 0; index < 20; index++) {
            byte[] data;
            if (index % 10 == 0) {
                data = keyFrame(320, 180, 5000 + random.nextInt(1000), random);
            } else if (index % 4 == 3) {
                data = superframe(interFrame(false, 1500 + random.nextInt(1000), random),
                        interFrame(true, 2 + random.nextInt(200), random));
            } else {
                data = interFrame(true, 1 + random.nextInt(2000), random);
            }
            units.add(new RtpChannel.Unit(data, index * 3000L, index % 10 == 0));
        }
        return units;
    }

    static RtpDepacketizer depacketizer() {
        return RtpDepacketizer.create(RtpPayloadFormat.vp9(), RtpDepacketizerOptions.defaults());
    }

    @Test
    @DisplayName("splits superframes into pictures and joins them back bit for bit")
    void roundTrip() {
        var units = stream(1);
        var sent = RtpChannel.packetize(RtpPacketizer.create(RtpPayloadFormat.vp9(), RtpChannel.fixed().build()),
                units);
        var frames = RtpChannel.depacketize(depacketizer(), RtpChannel.overTheWire(sent.packets()));
        assertEquals(units.size(), frames.size());
        for (var index = 0; index < units.size(); index++) {
            assertArrayEquals(units.get(index).data(), frames.get(index).packet().data(), "unit " + index);
            assertEquals(units.get(index).key(), frames.get(index).packet().keyframe());
            assertTrue(frames.get(index).complete());
        }
    }

    @Test
    @DisplayName("writes the descriptor bits, picture identifiers and the key frame scalability structure")
    void descriptor() {
        var random = new Random(2);
        var packetizer = RtpPacketizer.create(RtpPayloadFormat.vp9(),
                RtpChannel.fixed().initialSequenceNumber(0x7FFF).build());
        var key = packetizer.packetize(new RtpChannel.Unit(keyFrame(1280, 720, 3000, random), 0, true).packet());
        var first = key.getFirst().payload();
        assertEquals(0x80 | 0x08 | 0x02, first[0] & 0xFF, "I, B and V on the first packet of a key frame");
        assertEquals(0xFF, first[1] & 0xFF);
        assertEquals(0xFF, first[2] & 0xFF);
        assertEquals(0x10, first[3] & 0xFF, "one spatial layer with its resolution");
        assertEquals(1280, ((first[4] & 0xFF) << 8) | (first[5] & 0xFF));
        assertEquals(720, ((first[6] & 0xFF) << 8) | (first[7] & 0xFF));
        assertEquals(0x84, key.getLast().payload()[0] & 0xFF, "I and E on the last packet");
        var split = packetizer.packetize(new RtpChannel.Unit(superframe(interFrame(false, 100, random),
                interFrame(true, 50, random)), 3000, false).packet());
        assertEquals(2, split.size());
        assertEquals(0x80 | 0x40 | 0x08 | 0x04, split.getFirst().payload()[0] & 0xFF);
        assertEquals(0x80, split.getFirst().payload()[1] & 0xFF, "picture identifiers wrap after 0x7FFF");
        assertEquals(0x00, split.getFirst().payload()[2] & 0xFF);
        assertEquals(0x01, split.getLast().payload()[2] & 0xFF, "the hidden frame is its own picture");
        assertTrue(split.getFirst().marker() && split.getLast().marker());
        assertEquals(split.getFirst().timestamp(), split.getLast().timestamp());
        var plain = RtpPacketizer.create(RtpPayloadFormat.vp9(), RtpChannel.fixed().pictureIdBits(0).build())
                .packetize(new RtpChannel.Unit(keyFrame(64, 64, 100, random), 0, true).packet());
        assertEquals(0x0C, plain.getFirst().payload()[0] & 0xFF, "no identifier and no scalability structure");
    }

    @Test
    @DisplayName("flags the pictures a loss touches and keeps the others intact")
    void loss() {
        var units = stream(3);
        var sent = RtpChannel.packetize(RtpPacketizer.create(RtpPayloadFormat.vp9(), RtpChannel.fixed().build()),
                units);
        var lost = sent.owners().indexOf(3) + 1;
        var frames = RtpChannel.depacketize(depacketizer(), RtpChannel.drop(sent.packets(), Set.of(lost)));
        for (var frame : frames) {
            var index = (int) (frame.packet().pts() / 3000);
            if (index == 3) {
                assertFalse(frame.complete());
            } else {
                assertTrue(frame.complete(), "unit " + index);
                assertArrayEquals(units.get(index).data(), frame.packet().data());
            }
        }
    }

    @Nested
    @DisplayName("libwebrtc payload descriptor vectors")
    class Webrtc {
        private static int size(int... bytes) {
            var payload = new byte[Math.max(bytes.length + 1, 23)];
            for (var index = 0; index < bytes.length; index++) {
                payload[index] = (byte) bytes[index];
            }
            return Vp9Assembler.descriptorSize(payload);
        }

        @Test
        @DisplayName("measures every descriptor form")
        void descriptors() {
            assertEquals(1, size(0x0C));
            assertEquals(2, size(0x80, 0x7F));
            assertEquals(3, size(0x80, 0x80 | 0x7F, 0xFF));
            assertEquals(3, size(0x20, (2 << 5) | (1 << 4) | (3 << 1) | 1, 117));
            // libwebrtc reads one layer octet here because it honors F without I; RFC 9628 says to ignore F then.
            assertEquals(3, size(0x38, (2 << 5) | (1 << 4) | (3 << 1) | 1));
            assertEquals(3, size(0xB8, 0x05, (2 << 5) | (1 << 4) | (3 << 1) | 1));
            assertEquals(6, size(0xD8, 0x80, 0x10, (3 << 1) | 1, (33 << 1) | 1, 120 << 1));
            assertEquals(6, size(0x0A, (1 << 5) | (1 << 3), 2, 1 << 4, (2 << 5) | (1 << 2), 33));
            assertEquals(10, size(0x0A, (1 << 5) | (1 << 4), 0x01, 0x40, 0x00, 0xF0, 0x02, 0x80, 0x01, 0xE0));
            assertEquals(3, Vp9Assembler.spatialId(new byte[]{0x20, (2 << 5) | (1 << 4) | (3 << 1) | 1, 117, 0}));
        }

        @Test
        @DisplayName("ignores the F bit without a picture identifier, as RFC 9628 requires")
        void flexibleWithoutPictureId() {
            assertEquals(1, size(0x58, 3 << 1));
        }

        @Test
        @DisplayName("rejects more than three reference indices and payloads without frame data")
        void rejects() {
            assertThrows(MediaException.Malformed.class,
                    () -> Vp9Assembler.descriptorSize(new byte[]{(byte) 0xD8, 0x7F, 7, 7, 7, 6, 0}));
            assertThrows(MediaException.Malformed.class, () -> Vp9Assembler.descriptorSize(new byte[0]));
            assertThrows(MediaException.Malformed.class, () -> Vp9Assembler.descriptorSize(new byte[]{0}));
        }
    }
}
