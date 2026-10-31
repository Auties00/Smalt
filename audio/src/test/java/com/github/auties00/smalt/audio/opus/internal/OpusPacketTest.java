package com.github.auties00.smalt.audio.opus.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.Random;

import static com.github.auties00.smalt.audio.opus.internal.OpusDefines.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers Opus packet framing (RFC 6716 section 3): the table of contents, the four frame-count
 * codes, padding, self-delimited framing, every rejection rule, the repacketizer, and the soft
 * clipper.
 */
class OpusPacketTest {
    private static byte[] bytes(int... values) {
        var out = new byte[values.length];
        for (var i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    @Nested
    @DisplayName("table of contents")
    class Toc {
        @ParameterizedTest(name = "config {0}")
        @CsvSource({
                "0,1000,1101,480", "1,1000,1101,960", "2,1000,1101,1920", "3,1000,1101,2880",
                "4,1000,1102,480", "8,1000,1103,480", "11,1000,1103,2880",
                "12,1001,1104,480", "13,1001,1104,960", "14,1001,1105,480", "15,1001,1105,960",
                "16,1002,1101,120", "17,1002,1101,240", "18,1002,1101,480", "19,1002,1101,960",
                "20,1002,1103,120", "24,1002,1104,120", "28,1002,1105,120", "31,1002,1105,960"})
        @DisplayName("maps every configuration to its mode, bandwidth and 48 kHz frame size")
        void configurations(int config, int mode, int bandwidth, int frameSize) {
            var toc = config << 3;
            assertEquals(mode, OpusPacket.getMode(toc));
            assertEquals(bandwidth, OpusPacket.getBandwidth(toc));
            assertEquals(frameSize, OpusPacket.getSamplesPerFrame(toc, 48000));
            assertEquals(frameSize / 6, OpusPacket.getSamplesPerFrame(toc, 8000));
            assertEquals(1, OpusPacket.getNbChannels(toc));
            assertEquals(2, OpusPacket.getNbChannels(toc | 4));
        }
    }

    @Nested
    @DisplayName("frame parsing")
    class Parsing {
        @Test
        @DisplayName("code 0 holds one frame spanning the payload")
        void code0() {
            var layout = new OpusPacket.Layout();
            var packet = bytes(0x08, 1, 2, 3);
            assertEquals(1, OpusPacket.parseImpl(packet, 0, packet.length, false, layout));
            assertEquals(1, layout.frames[0]);
            assertEquals(3, layout.size[0]);
            assertEquals(4, layout.packetOffset);
        }

        @Test
        @DisplayName("code 1 splits an even payload in two and rejects an odd one")
        void code1() {
            var layout = new OpusPacket.Layout();
            var packet = bytes(0x09, 1, 2, 3, 4);
            assertEquals(2, OpusPacket.parseImpl(packet, 0, packet.length, false, layout));
            assertEquals(2, layout.size[0]);
            assertEquals(2, layout.size[1]);
            assertEquals(3, layout.frames[1]);
            assertEquals(OPUS_INVALID_PACKET, OpusPacket.parseImpl(packet, 0, 4, false, layout));
        }

        @Test
        @DisplayName("code 2 reads the first frame length, including the two-byte form")
        void code2() {
            var layout = new OpusPacket.Layout();
            var packet = new byte[1 + 2 + 300 + 10];
            packet[0] = 0x0A;
            var n = OpusPacket.encodeSize(300, packet, 1);
            assertEquals(2, n);
            assertEquals(2, OpusPacket.parseImpl(packet, 0, packet.length, false, layout));
            assertEquals(300, layout.size[0]);
            assertEquals(10, layout.size[1]);
            var truncated = bytes(0x0A, 5, 1, 2);
            assertEquals(OPUS_INVALID_PACKET, OpusPacket.parseImpl(truncated, 0, truncated.length, false, layout));
        }

        @Test
        @DisplayName("code 3 CBR with padding splits the payload evenly after the padding")
        void code3CbrPadding() {
            var layout = new OpusPacket.Layout();
            var packet = new byte[2 + 2 + 6 + 298];
            packet[0] = 0x0B;
            packet[1] = (byte) (0x40 | 3);
            packet[2] = (byte) 255;
            packet[3] = 44;
            assertEquals(3, OpusPacket.parseImpl(packet, 0, packet.length, false, layout));
            assertEquals(2, layout.size[0]);
            assertEquals(2, layout.size[2]);
            assertEquals(298, layout.paddingLen);
        }

        @Test
        @DisplayName("code 3 VBR reads every length but the last")
        void code3Vbr() {
            var layout = new OpusPacket.Layout();
            var packet = bytes(0x0B, 0x80 | 3, 1, 2, 9, 8, 8, 7, 7, 7);
            assertEquals(3, OpusPacket.parseImpl(packet, 0, packet.length, false, layout));
            assertArrayEquals(new int[]{1, 2, 3}, java.util.Arrays.copyOf(layout.size, 3));
            assertEquals(4, layout.frames[0]);
            assertEquals(5, layout.frames[1]);
            assertEquals(7, layout.frames[2]);
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"empty", "count zero", "over 120 ms", "padding overflow", "padding truncated",
                "vbr length overflow", "cbr uneven", "last frame too long", "code 3 without count"})
        @DisplayName("rejects every malformed framing")
        void rejects(String kind) {
            var layout = new OpusPacket.Layout();
            var packet = switch (kind) {
                case "empty" -> new byte[0];
                case "count zero" -> bytes(0x0B, 0);
                case "over 120 ms" -> bytes(0x1B, 7, 0, 0, 0, 0, 0, 0, 0);
                case "padding overflow" -> bytes(0x0B, 0x41, 200, 1, 2);
                case "padding truncated" -> bytes(0x0B, 0x41, 255);
                case "vbr length overflow" -> bytes(0x0B, 0x82, 250, 1, 2);
                case "cbr uneven" -> bytes(0x0B, 2, 1, 2, 3);
                case "last frame too long" -> new byte[1277];
                default -> bytes(0x0B);
            };
            assertTrue(OpusPacket.parseImpl(packet, 0, packet.length, false, layout) < 0, kind);
        }

        @Test
        @DisplayName("parses self-delimited framing and reports the consumed length")
        void selfDelimited() {
            var layout = new OpusPacket.Layout();
            var packet = bytes(0x08, 3, 1, 2, 3, 0x08, 9);
            assertEquals(1, OpusPacket.parseImpl(packet, 0, packet.length, true, layout));
            assertEquals(3, layout.size[0]);
            assertEquals(5, layout.packetOffset);
        }

        @Test
        @DisplayName("never reports frames outside the packet for random bytes")
        void randomBytes() {
            var random = new Random(7);
            var layout = new OpusPacket.Layout();
            for (var n = 0; n < 20000; n++) {
                var packet = new byte[random.nextInt(64)];
                random.nextBytes(packet);
                var count = OpusPacket.parseImpl(packet, 0, packet.length, random.nextBoolean(), layout);
                for (var i = 0; i < count; i++) {
                    assertTrue(layout.frames[i] >= 0 && layout.frames[i] + layout.size[i] <= packet.length);
                }
            }
        }
    }

    @Nested
    @DisplayName("repacketizer")
    class Repacketizing {
        @ParameterizedTest(name = "{0} frames, vbr {1}")
        @CsvSource({"2,false", "2,true", "3,false", "3,true", "6,true"})
        @DisplayName("merges single-frame packets and parses back to the same frames")
        void merge(int count, boolean vbr) {
            var random = new Random(count);
            var rp = new Repacketizer();
            rp.init();
            var frames = new byte[count][];
            for (var i = 0; i < count; i++) {
                var size = vbr ? 1 + random.nextInt(300) : 40;
                frames[i] = new byte[size + 1];
                frames[i][0] = (byte) 0xF8;
                for (var j = 1; j <= size; j++) {
                    frames[i][j] = (byte) random.nextInt();
                }
                assertEquals(OPUS_OK, rp.cat(frames[i], 0, frames[i].length));
            }
            var out = new byte[4000];
            var length = rp.outRange(0, count, out, 0, out.length, false);
            assertTrue(length > 0);
            var layout = new OpusPacket.Layout();
            assertEquals(count, OpusPacket.parseImpl(out, 0, length, false, layout));
            for (var i = 0; i < count; i++) {
                assertArrayEquals(java.util.Arrays.copyOfRange(frames[i], 1, frames[i].length),
                        java.util.Arrays.copyOfRange(out, layout.frames[i], layout.frames[i] + layout.size[i]));
            }
        }

        @Test
        @DisplayName("pads a packet to an exact size that still parses to the same frame")
        void pad() {
            var packet = new byte[200];
            packet[0] = (byte) 0x78;
            for (var i = 1; i < 30; i++) {
                packet[i] = (byte) i;
            }
            var rp = new Repacketizer();
            assertEquals(OPUS_OK, rp.pad(packet, 0, 30, 200));
            var layout = new OpusPacket.Layout();
            assertEquals(1, OpusPacket.parseImpl(packet, 0, 200, false, layout));
            assertEquals(29, layout.size[0]);
            assertEquals(1, packet[layout.frames[0]]);
        }

        @Test
        @DisplayName("pads again with the same instance after it collected other frames")
        void padReused() {
            var rp = new Repacketizer();
            rp.init();
            assertEquals(OPUS_OK, rp.cat(bytes(0xF8, 7, 7, 7), 0, 4));
            var small = new byte[40];
            small[0] = (byte) 0x78;
            small[1] = 5;
            assertEquals(OPUS_OK, rp.pad(small, 0, 2, 40));
            var large = new byte[900];
            large[0] = (byte) 0x78;
            for (var i = 1; i < 500; i++) {
                large[i] = (byte) (i * 3);
            }
            var expected = Arrays.copyOf(large, 500);
            assertEquals(OPUS_OK, rp.pad(large, 0, 500, 900));
            var layout = new OpusPacket.Layout();
            assertEquals(1, OpusPacket.parseImpl(small, 0, 40, false, layout));
            assertEquals(1, layout.size[0]);
            assertEquals(5, small[layout.frames[0]]);
            assertEquals(1, OpusPacket.parseImpl(large, 0, 900, false, layout));
            assertEquals(499, layout.size[0]);
            assertArrayEquals(Arrays.copyOfRange(expected, 1, 500),
                    Arrays.copyOfRange(large, layout.frames[0], layout.frames[0] + 499));
            assertEquals(OPUS_BAD_ARG, rp.pad(large, 0, 901, 900));
            assertEquals(OPUS_BAD_ARG, rp.pad(large, 0, 0, 900));
        }

        @Test
        @DisplayName("refuses to merge packets with different configurations or beyond 120 ms")
        void refuses() {
            var rp = new Repacketizer();
            rp.init();
            assertEquals(OPUS_OK, rp.cat(bytes(0xF8, 1), 0, 2));
            assertEquals(OPUS_INVALID_PACKET, rp.cat(bytes(0xF0, 1), 0, 2));
            rp.init();
            for (var i = 0; i < 6; i++) {
                assertEquals(OPUS_OK, rp.cat(bytes(0x08, 1), 0, 2));
            }
            assertEquals(OPUS_INVALID_PACKET, rp.cat(bytes(0x08, 1), 0, 2));
        }
    }

    @Nested
    @DisplayName("soft clipping")
    class SoftClip {
        @Test
        @DisplayName("keeps a loud signal inside [-1, 1] and leaves a quiet one untouched")
        void clips() {
            var x = new float[960];
            var quiet = new float[960];
            for (var i = 0; i < x.length; i++) {
                x[i] = (float) (1.8 * Math.sin(i * 0.05));
                quiet[i] = (float) (0.5 * Math.sin(i * 0.05));
            }
            var original = quiet.clone();
            var mem = new float[2];
            OpusPacket.softClip(x, 0, 480, 2, mem);
            for (var v : x) {
                assertTrue(v >= -1.0f && v <= 1.0f, "sample " + v);
            }
            OpusPacket.softClip(quiet, 0, 480, 2, new float[2]);
            assertArrayEquals(original, quiet);
        }
    }

    @Test
    @DisplayName("counts packet samples and rejects packets over 120 ms")
    void nbSamples() {
        assertEquals(960, OpusPacket.getNbSamples(bytes(0xF8), 0, 1, 48000));
        assertEquals(5760, OpusPacket.getNbSamples(bytes(0x1B, 2), 0, 2, 48000));
        assertEquals(OPUS_INVALID_PACKET, OpusPacket.getNbSamples(bytes(0x1B, 3), 0, 2, 48000));
        assertEquals(OPUS_BAD_ARG, OpusPacket.getNbFrames(new byte[0], 0, 0));
    }
}
