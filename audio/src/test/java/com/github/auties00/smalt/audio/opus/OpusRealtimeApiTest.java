package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the real-time Opus API without external tools: argument validation, the 16-bit entry points against
 * the float ones, live controls, resets, the decoder's capacity and recovery-duration semantics, and the packet
 * inspection and repacketizing helpers. The signal is the first seconds of the {@link OpusCallGoldenTest} speech.
 */
class OpusRealtimeApiTest {
    private static final int RATE = 16000;
    private static final int FRAME_60 = 960;

    private static OpusEncoder callEncoder(int loss) {
        var encoder = new OpusEncoder(OpusEncoderOptions.forVoip(RATE, 1));
        encoder.setBitrate(25000);
        encoder.setInbandFec(true);
        encoder.setPacketLossPercent(loss);
        encoder.setDtx(true);
        encoder.setSignal(OpusSignal.VOICE);
        encoder.setLsbDepth(16);
        encoder.setMaxBandwidth(OpusBandwidth.WIDEBAND);
        return encoder;
    }

    private static List<byte[]> encodeSpeech(OpusEncoder encoder, int frames, int frameSize) {
        var speech = OpusCallGoldenTest.input("speech16k");
        var packets = new ArrayList<byte[]>();
        var packet = new byte[1500];
        for (var i = 0; i < frames; i++) {
            var length = encoder.encode(speech, i * frameSize, frameSize, packet, 0, packet.length);
            packets.add(Arrays.copyOf(packet, length));
        }
        return packets;
    }

    @Nested
    @DisplayName("encoder")
    class Encoder {
        @ParameterizedTest
        @CsvSource({"44100, 1", "16000, 256", "16000, 0"})
        @DisplayName("rejects unsupported formats")
        void rejectsFormats(int rate, int channels) {
            assertThrows(IllegalArgumentException.class,
                    () -> new OpusEncoder(OpusEncoderOptions.forVoip(rate, channels)));
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -320, 100, 330, 2000, 2240})
        @DisplayName("rejects frame sizes that are not Opus durations")
        void rejectsFrameSizes(int frameSize) {
            var encoder = callEncoder(0);
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new short[4000], 0, frameSize, new byte[1500], 0, 1500));
        }

        @Test
        @DisplayName("rejects frames and packet spaces outside their arrays")
        void rejectsBounds() {
            var encoder = callEncoder(0);
            assertThrows(IndexOutOfBoundsException.class,
                    () -> encoder.encode(new short[900], 0, FRAME_60, new byte[1500], 0, 1500));
            assertThrows(IndexOutOfBoundsException.class,
                    () -> encoder.encode(new short[FRAME_60], 0, FRAME_60, new byte[100], 0, 1500));
            assertThrows(IllegalArgumentException.class,
                    () -> encoder.encode(new short[1600], 0, 1600, new byte[1], 0, 1));
        }

        @Test
        @DisplayName("rejects out-of-range controls like libopus")
        void rejectsControls() {
            var encoder = callEncoder(0);
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> encoder.setComplexity(11)),
                    () -> assertThrows(IllegalArgumentException.class, () -> encoder.setComplexity(-1)),
                    () -> assertThrows(IllegalArgumentException.class, () -> encoder.setPacketLossPercent(101)),
                    () -> assertThrows(IllegalArgumentException.class, () -> encoder.setLsbDepth(7)),
                    () -> assertThrows(IllegalArgumentException.class, () -> encoder.setBitrate(0)),
                    () -> assertThrows(IllegalArgumentException.class, () -> encoder.setForcedChannels(2)),
                    () -> assertThrows(NullPointerException.class, () -> encoder.setSignal(null)));
        }

        @Test
        @DisplayName("reports the bitrate it targets")
        void bitrate() {
            var encoder = new OpusEncoder(OpusEncoderOptions.forVoip(RATE, 1));
            // OPUS_AUTO before the first frame resolves at 2.5 ms: 60 * 400 + 16000
            assertEquals(40000, encoder.bitrate());
            encoder.setBitrate(100);
            assertEquals(500, encoder.bitrate());
            encoder.setBitrate(25000);
            assertEquals(25000, encoder.bitrate());
            encoder.setAutomaticBitrate();
            encoder.encode(new short[FRAME_60], 0, FRAME_60, new byte[1500], 0, 1500);
            assertEquals(60 * 16000 / FRAME_60 + 16000, encoder.bitrate());
        }

        @Test
        @DisplayName("codes 16-bit input exactly like the scaled float input")
        void int16MatchesFloat() {
            var speech = OpusCallGoldenTest.input("speech16k");
            var a = callEncoder(10);
            var b = callEncoder(10);
            var packetA = new byte[1500];
            var packetB = new byte[1500];
            var floats = new float[FRAME_60];
            for (var i = 0; i < 60; i++) {
                if (i == 20) {
                    a.setComplexity(3);
                    b.setComplexity(3);
                }
                for (var k = 0; k < FRAME_60; k++) {
                    floats[k] = (1.0f / 32768) * speech[i * FRAME_60 + k];
                }
                var lengthA = a.encode(speech, i * FRAME_60, FRAME_60, packetA, 0, packetA.length);
                var lengthB = b.encode(floats, 0, FRAME_60, packetB, 0, packetB.length);
                assertArrayEquals(Arrays.copyOf(packetB, lengthB), Arrays.copyOf(packetA, lengthA), "packet " + i);
            }
            assertEquals(3, a.complexity());
        }

        @Test
        @DisplayName("codes the first packet after a reset like a new encoder")
        void reset() {
            var warm = callEncoder(10);
            encodeSpeech(warm, 30, FRAME_60);
            warm.reset();
            var fresh = callEncoder(10);
            var speech = OpusCallGoldenTest.input("speech16k");
            var a = new byte[1500];
            var b = new byte[1500];
            var lengthA = warm.encode(speech, 30 * FRAME_60, FRAME_60, a, 0, a.length);
            var lengthB = fresh.encode(speech, 30 * FRAME_60, FRAME_60, b, 0, b.length);
            assertArrayEquals(Arrays.copyOf(b, lengthB), Arrays.copyOf(a, lengthA));
        }

        @Test
        @DisplayName("emits mostly one-byte packets for digital silence under DTX")
        void dtx() {
            var encoder = callEncoder(0);
            var packet = new byte[1500];
            var lengths = new ArrayList<Integer>();
            for (var i = 0; i < 20; i++) {
                lengths.add(encoder.encode(new short[FRAME_60], 0, FRAME_60, packet, 0, packet.length));
            }
            // a comfort-noise update interrupts the one-byte packets every 400 ms
            assertTrue(lengths.stream().skip(3).filter(length -> length == 1).count() >= 12, lengths.toString());
        }
    }

    @Nested
    @DisplayName("decoder")
    class Decoder {
        @Test
        @DisplayName("decodes a packet whole within its capacity and rejects a shorter capacity")
        void capacity() {
            var packets = encodeSpeech(callEncoder(0), 5, FRAME_60);
            var decoder = new OpusDecoder(OpusDecoderOptions.of(RATE, 1));
            var out = new short[5760];
            var first = packets.getFirst();
            assertEquals(FRAME_60, decoder.decode(first, 0, first.length, out, 0, 5760));
            assertEquals(FRAME_60, decoder.lastPacketDuration());
            var second = packets.get(1);
            assertThrows(IllegalArgumentException.class, () -> decoder.decode(second, 0, second.length, out, 0, 320));
        }

        @Test
        @DisplayName("rebuilds a lost 60 ms packet from the next packet's copy only at the lost packet's duration")
        void fecDuration() {
            var packets = encodeSpeech(callEncoder(20), 12, FRAME_60);
            var lostIndex = 8;
            var next = packets.get(lostIndex + 1);
            assertTrue(OpusPackets.hasInbandFec(next, 0, next.length));
            var fec = decodeWithGap(packets, lostIndex, (decoder, out) -> decoder.recover(next, 0, next.length, out, 0, FRAME_60));
            var plc = decodeWithGap(packets, lostIndex, (decoder, out) -> decoder.conceal(out, 0, FRAME_60));
            var shortFec = decodeWithGap(packets, lostIndex, (decoder, out) -> {
                var produced = 0;
                for (var k = 0; k < 3; k++) {
                    produced += decoder.recover(next, 0, next.length, out, produced, 320);
                }
                return produced;
            });
            var shortPlc = decodeWithGap(packets, lostIndex, (decoder, out) -> {
                var produced = 0;
                for (var k = 0; k < 3; k++) {
                    produced += decoder.conceal(out, produced, 320);
                }
                return produced;
            });
            assertFalse(Arrays.equals(fec, plc), "FEC at the packet duration must use the copy");
            assertArrayEquals(shortPlc, shortFec, "FEC shorter than the packet's frame must fall back to concealment");
        }

        @Test
        @DisplayName("recover rebuilds the lost packet and leaves the next packet to decode whole")
        void decodeAndRecover() {
            var packets = encodeSpeech(callEncoder(20), 12, FRAME_60);
            var lostIndex = 8;
            var next = packets.get(lostIndex + 1);
            assertTrue(OpusPackets.hasInbandFec(next, 0, next.length));
            var decoder = new OpusDecoder(OpusDecoderOptions.of(RATE, 1));
            var out = new float[FRAME_60];
            for (var i = 0; i < packets.size(); i++) {
                var p = packets.get(i);
                if (i == lostIndex) {
                    assertEquals(FRAME_60, decoder.recover(next, 0, next.length, out, 0, FRAME_60));
                } else {
                    assertEquals(FRAME_60, decoder.decode(p, 0, p.length, out, 0, FRAME_60), "packet " + i);
                }
            }
            var shorts = new short[FRAME_60];
            var first = packets.getFirst();
            assertEquals(FRAME_60, new OpusDecoder(OpusDecoderOptions.of(RATE, 1)).decode(first, 0, first.length, shorts, 0, FRAME_60));
        }

        @Test
        @DisplayName("decode and recover need a packet")
        void decodeAndRecoverNeedAPacket() {
            var decoder = new OpusDecoder(OpusDecoderOptions.of(RATE, 1));
            var shorts = new short[FRAME_60];
            var floats = new float[FRAME_60];
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> decoder.decode(new byte[4], 1, 0, shorts, 0, FRAME_60)),
                    () -> assertThrows(IllegalArgumentException.class, () -> decoder.decode(new byte[4], 1, 0, floats, 0, FRAME_60)),
                    () -> assertThrows(IllegalArgumentException.class, () -> decoder.recover(new byte[4], 1, 0, shorts, 0, FRAME_60)),
                    () -> assertThrows(IllegalArgumentException.class, () -> decoder.recover(new byte[4], 1, 0, floats, 0, FRAME_60)),
                    () -> assertThrows(NullPointerException.class, () -> decoder.decode(null, 0, 1, shorts, 0, FRAME_60)),
                    () -> assertThrows(NullPointerException.class, () -> decoder.recover(null, 0, 1, floats, 0, FRAME_60)),
                    () -> assertThrows(IndexOutOfBoundsException.class, () -> decoder.decode(new byte[2], 1, 4, shorts, 0, FRAME_60)),
                    () -> assertThrows(IndexOutOfBoundsException.class, () -> decoder.recover(new byte[2], 1, 4, shorts, 0, FRAME_60)));
        }

        private interface Recovery {
            int recover(OpusDecoder decoder, short[] out);
        }

        private short[] decodeWithGap(List<byte[]> packets, int lostIndex, Recovery recovery) {
            var decoder = new OpusDecoder(OpusDecoderOptions.of(RATE, 1));
            var out = new short[5760];
            for (var i = 0; i < lostIndex; i++) {
                var p = packets.get(i);
                decoder.decode(p, 0, p.length, out, 0, out.length);
            }
            var produced = recovery.recover(decoder, out);
            assertEquals(FRAME_60, produced);
            return Arrays.copyOf(out, produced);
        }

        @Test
        @DisplayName("conceals silence before any packet and after a reset")
        void concealAfterReset() {
            var packets = encodeSpeech(callEncoder(0), 4, FRAME_60);
            var decoder = new OpusDecoder(OpusDecoderOptions.of(RATE, 1));
            var out = new short[FRAME_60];
            assertEquals(FRAME_60, decoder.conceal(out, 0, FRAME_60));
            assertArrayEquals(new short[FRAME_60], out);
            for (var p : packets) {
                decoder.decode(p, 0, p.length, out, 0, FRAME_60);
            }
            decoder.reset();
            Arrays.fill(out, (short) 7);
            decoder.conceal(out, 0, FRAME_60);
            assertArrayEquals(new short[FRAME_60], out);
        }

        @Test
        @DisplayName("rejects bad sizes, spans and packets")
        void rejects() {
            var decoder = new OpusDecoder(OpusDecoderOptions.of(RATE, 1));
            var out = new short[FRAME_60];
            assertAll(
                    () -> assertThrows(IllegalArgumentException.class, () -> decoder.conceal(out, 0, 100)),
                    () -> assertThrows(IllegalArgumentException.class, () -> decoder.conceal(out, 0, 0)),
                    () -> assertThrows(IndexOutOfBoundsException.class, () -> decoder.conceal(out, 0, 1920)),
                    () -> assertThrows(IndexOutOfBoundsException.class, () -> decoder.decode(new byte[2], 1, 4, out, 0, FRAME_60)),
                    // a code 3 packet without its frame count byte
                    () -> assertThrows(MediaException.Malformed.class, () -> decoder.decode(new byte[]{0x0B}, 0, 1, out, 0, FRAME_60)),
                    () -> assertThrows(IllegalArgumentException.class, () -> new OpusDecoder(OpusDecoderOptions.of(44100, 1))));
        }

        @Test
        @DisplayName("decodes the same samples through the 16-bit and the float entry points")
        void int16MatchesFloat() {
            var packets = encodeSpeech(callEncoder(0), 20, FRAME_60);
            var a = new OpusDecoder(OpusDecoderOptions.of(RATE, 1));
            var b = new OpusDecoder(OpusDecoderOptions.of(RATE, 1));
            var shorts = new short[FRAME_60];
            var floats = new float[FRAME_60];
            for (var p : packets) {
                a.decode(p, 0, p.length, shorts, 0, FRAME_60);
                b.decode(p, 0, p.length, floats, 0, FRAME_60);
                for (var i = 0; i < FRAME_60; i++) {
                    var expected = (int) Math.max(-32768, Math.min(32767, Math.rint(floats[i] * 32768.0f)));
                    assertTrue(Math.abs(expected - shorts[i]) <= 1, "sample " + i);
                }
            }
        }
    }

    @Nested
    @DisplayName("packets")
    class Packets {
        @Test
        @DisplayName("reads the table of contents")
        void toc() {
            // config 11 (SILK WB 60 ms), mono, code 0; then config 31 (CELT FB 20 ms), stereo, code 3 with 3 frames
            var silk = new byte[]{(byte) (11 << 3), 0x55, 0x66};
            var celt = new byte[]{(byte) ((31 << 3) | 0x4 | 0x3), 0x03, 1, 2, 3};
            assertAll(
                    () -> assertEquals(OpusBandwidth.WIDEBAND, OpusPackets.bandwidth(silk, 0)),
                    () -> assertEquals(1, OpusPackets.channels(silk, 0)),
                    () -> assertEquals(960, OpusPackets.samplesPerFrame(silk, 0, RATE)),
                    () -> assertEquals(1, OpusPackets.frames(silk, 0, silk.length)),
                    () -> assertEquals(960, OpusPackets.samples(silk, 0, silk.length, RATE)),
                    () -> assertEquals(OpusBandwidth.FULLBAND, OpusPackets.bandwidth(celt, 0)),
                    () -> assertEquals(2, OpusPackets.channels(celt, 0)),
                    () -> assertEquals(3, OpusPackets.frames(celt, 0, celt.length)),
                    () -> assertEquals(2880, OpusPackets.samples(celt, 0, celt.length, 48000)),
                    () -> assertFalse(OpusPackets.hasInbandFec(celt, 0, celt.length)),
                    () -> assertThrows(MediaException.Malformed.class, () -> OpusPackets.frames(new byte[]{0x03}, 0, 1)),
                    () -> assertThrows(IllegalArgumentException.class, () -> OpusPackets.samples(silk, 0, 0, RATE)));
        }

        @Test
        @DisplayName("hasInbandFec finds the forward error correction data of lossy SILK packets")
        void hasInbandFec() {
            var lossy = encodeSpeech(callEncoder(20), 10, FRAME_60);
            assertTrue(lossy.stream().skip(2).anyMatch(p -> OpusPackets.hasInbandFec(p, 0, p.length)));
            assertThrows(IllegalArgumentException.class, () -> OpusPackets.hasInbandFec(new byte[1], 0, 0));
        }

        @Test
        @DisplayName("finds the in-band copy only once the encoder expects loss")
        void lbrr() {
            var quiet = encodeSpeech(callEncoder(0), 10, FRAME_60);
            var lossy = encodeSpeech(callEncoder(20), 10, FRAME_60);
            assertTrue(quiet.stream().noneMatch(p -> OpusPackets.hasInbandFec(p, 0, p.length)));
            assertTrue(lossy.stream().skip(2).anyMatch(p -> OpusPackets.hasInbandFec(p, 0, p.length)));
        }

        @Test
        @DisplayName("merges 20 ms packets into one and splits them back")
        void mergeAndSplit() {
            var packets = encodeSpeech(callEncoder(0), 3, 320);
            var merged = OpusPackets.merge(packets);
            assertEquals(3, OpusPackets.frames(merged, 0, merged.length));
            assertEquals(FRAME_60, OpusPackets.samples(merged, 0, merged.length, RATE));
            var split = OpusPackets.split(merged, 0, merged.length);
            assertEquals(3, split.size());
            for (var i = 0; i < 3; i++) {
                assertArrayEquals(packets.get(i), split.get(i), "frame " + i);
            }
            var single = OpusPackets.merge(List.of(packets.getFirst()));
            assertArrayEquals(packets.getFirst(), single);
            assertThrows(IllegalArgumentException.class, () -> OpusPackets.merge(List.of()));
            var celt = new byte[]{(byte) (31 << 3), 1, 2};
            assertThrows(MediaException.Malformed.class, () -> OpusPackets.merge(List.of(packets.getFirst(), celt)));
        }
    }
}
