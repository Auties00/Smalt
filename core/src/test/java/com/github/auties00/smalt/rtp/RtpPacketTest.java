package com.github.auties00.smalt.rtp;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("RtpPacket")
class RtpPacketTest {
    private static byte[] hex(String text) {
        return HexFormat.of().parseHex(text.replace(" ", ""));
    }

    @Nested
    @DisplayName("header layout")
    class Layout {
        @Test
        @DisplayName("parses every fixed header field")
        void fixedHeader() {
            var packet = RtpPacket.parse(hex("80e0 1234 deadbeef 01020304 aabb"));
            assertTrue(packet.marker());
            assertEquals(96, packet.payloadType());
            assertEquals(0x1234, packet.sequenceNumber());
            assertEquals(0xDEADBEEFL, packet.timestamp());
            assertEquals(0x01020304L, packet.ssrc());
            assertArrayEquals(hex("aabb"), packet.payload());
            assertNull(packet.extension());
            assertEquals(List.of(), packet.csrcs());
        }

        @Test
        @DisplayName("writes the RFC 3550 layout byte for byte")
        void writes() {
            var packet = new RtpPacket(true, 111, 65535, 0xFFFF_FFFFL, 7, List.of(1L, 0xFFFF_FFFFL),
                    new RtpHeaderExtension(0xBEDE, hex("10ff0000")), hex("0102"));
            assertArrayEquals(hex("92ef ffff ffffffff 00000007 00000001 ffffffff bede0001 10ff0000 0102"),
                    packet.toByteArray());
            assertEquals(packet.size(), packet.toByteArray().length);
            var back = RtpPacket.parse(packet.toByteArray());
            assertEquals(packet.csrcs(), back.csrcs());
            assertEquals(packet.extension(), back.extension());
            assertArrayEquals(packet.payload(), back.payload());
            assertEquals(packet.timestamp(), back.timestamp());
        }

        @Test
        @DisplayName("strips the padding the P bit announces")
        void padding() {
            var packet = RtpPacket.parse(hex("a060 0001 00000010 00000020 aabbcc 000003"));
            assertArrayEquals(hex("aabbcc"), packet.payload());
            assertFalse(packet.marker());
        }

        @Test
        @DisplayName("reads a range of a larger array")
        void range() {
            var data = hex("ffff 8060 0001 00000010 00000020 aa ffff");
            assertArrayEquals(hex("aa"), RtpPacket.parse(data, 2, 13).payload());
        }
    }

    @Nested
    @DisplayName("malformed input")
    class Malformed {
        @Test
        @DisplayName("rejects short, wrong-version, overlong padding and truncated parts")
        void rejects() {
            assertThrows(MediaException.Malformed.class, () -> RtpPacket.parse(hex("8060 0001 00000010 000000")));
            assertThrows(MediaException.Malformed.class, () -> RtpPacket.parse(hex("4060 0001 00000010 00000020")));
            assertThrows(MediaException.Malformed.class,
                    () -> RtpPacket.parse(hex("a060 0001 00000010 00000020 aa 05")));
            assertThrows(MediaException.Malformed.class,
                    () -> RtpPacket.parse(hex("a060 0001 00000010 00000020 aa 00")));
            assertThrows(MediaException.Malformed.class,
                    () -> RtpPacket.parse(hex("8260 0001 00000010 00000020 00000001")));
            assertThrows(MediaException.Malformed.class,
                    () -> RtpPacket.parse(hex("9060 0001 00000010 00000020 bede0002 00000000")));
            assertThrows(MediaException.Malformed.class,
                    () -> RtpPacket.parse(hex("9060 0001 00000010 00000020 bede")));
        }

        @Test
        @DisplayName("validates the record fields")
        void fields() {
            assertThrows(IllegalArgumentException.class, () -> new RtpPacket(false, 128, 0, 0, 0, new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> new RtpPacket(false, 0, 65536, 0, 0, new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> new RtpPacket(false, 0, 0, 1L << 32, 0, new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> new RtpPacket(false, 0, 0, 0, -1, new byte[0]));
            assertThrows(IllegalArgumentException.class, () -> new RtpHeaderExtension(0, new byte[3]));
        }
    }

    @Nested
    @DisplayName("RFC 8285 header extension elements")
    class Elements {
        @Test
        @DisplayName("reads the one-byte form of RFC 8285 section 4.2, skipping padding and stopping at ID 15")
        void oneByte() {
            var extension = new RtpHeaderExtension(0xBEDE, hex("10aa 00 21bbcc 00 f0 3100 0000"));
            var elements = extension.elements();
            assertEquals(2, elements.size());
            assertArrayEquals(hex("aa"), elements.get(1));
            assertArrayEquals(hex("bbcc"), elements.get(2));
            assertTrue(extension.element(3).isEmpty());
        }

        @Test
        @DisplayName("reads the two-byte form, zero-length elements included")
        void twoByte() {
            var extension = new RtpHeaderExtension(0x1003, hex("0102aabb 1000 0000"));
            assertArrayEquals(hex("aabb"), extension.element(1).orElseThrow());
            assertArrayEquals(new byte[0], extension.element(16).orElseThrow());
        }

        @Test
        @DisplayName("builds the smallest form and reads it back")
        void build() {
            var one = new LinkedHashMap<Integer, byte[]>();
            one.put(1, hex("01"));
            one.put(14, hex("0102030405060708090a0b0c0d0e0f10"));
            var compact = RtpHeaderExtension.of(one);
            assertEquals(RtpHeaderExtension.ONE_BYTE_PROFILE, compact.profile());
            assertEquals(0, compact.data().length % 4);
            assertArrayEquals(one.get(14), compact.element(14).orElseThrow());
            var two = RtpHeaderExtension.of(Map.of(200, hex("0102")));
            assertEquals(RtpHeaderExtension.TWO_BYTE_PROFILE, two.profile());
            assertArrayEquals(hex("c8020102"), two.data());
            assertThrows(IllegalArgumentException.class, () -> RtpHeaderExtension.of(Map.of()));
            assertThrows(IllegalArgumentException.class, () -> RtpHeaderExtension.of(Map.of(0, hex("01"))));
        }

        @Test
        @DisplayName("rejects elements running past the data and profiles without elements")
        void malformed() {
            assertThrows(MediaException.Malformed.class,
                    () -> new RtpHeaderExtension(0xBEDE, hex("13aabb00")).elements());
            assertThrows(IllegalStateException.class, () -> new RtpHeaderExtension(0x1234, new byte[4]).elements());
            assertTrue(new RtpHeaderExtension(0x1234, new byte[4]).element(1).isEmpty());
        }
    }
}
