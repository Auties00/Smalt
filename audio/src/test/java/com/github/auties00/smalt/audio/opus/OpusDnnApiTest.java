package com.github.auties00.smalt.audio.opus;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the public surface of the Opus 1.5 neural extensions: the decoder complexity and DRED duration settings,
 * the DRED holder and its error cases, the padding extensions kept by packet merging and splitting, robustness
 * against corrupt redundancy, and the quality the deep PLC and DRED gain over the classic concealment on bursts of
 * lost speech.
 */
class OpusDnnApiTest {
    private static List<byte[]> dredPackets() {
        return OpusDnnGoldenTest.packets("e_dred48");
    }

    private static byte[] firstDredPacket(int after) {
        var packets = dredPackets();
        for (var i = after; i < packets.size(); i++) {
            var packet = packets.get(i);
            if (OpusPackets.hasDred(packet, 0, packet.length)) {
                return packet;
            }
        }
        throw new AssertionError("no DRED packet");
    }

    @Nested
    @DisplayName("options")
    class Options {
        @Test
        @DisplayName("default to complexity 0 and no DRED, and round-trip through their builders")
        void defaults() {
            assertEquals(0, OpusDecoderOptions.defaults().complexity());
            assertEquals(0, OpusEncoderOptions.defaults().dredDurationMillis());
            var decoder = OpusDecoderOptions.builder().complexity(7).build();
            assertEquals(7, decoder.complexity());
            assertEquals(decoder, decoder.toBuilder().build());
            assertNotEquals(decoder, OpusDecoderOptions.defaults());
            assertTrue(decoder.toString().contains("complexity=7"));
            var encoder = OpusEncoderOptions.forVoip(16000, 1).toBuilder().dredDurationMillis(500).build();
            assertEquals(500, encoder.dredDurationMillis());
            assertEquals(encoder, encoder.toBuilder().build());
            assertEquals(0, encoder.toBuilder().noDred().build().dredDurationMillis());
        }

        @Test
        @DisplayName("reject complexities and DRED durations out of range")
        void ranges() {
            assertThrows(IllegalArgumentException.class, () -> OpusDecoderOptions.builder().complexity(-1).build());
            assertThrows(IllegalArgumentException.class, () -> OpusDecoderOptions.builder().complexity(11).build());
            assertThrows(IllegalArgumentException.class, () -> OpusEncoderOptions.builder().dredDurationMillis(15)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> OpusEncoderOptions.builder().dredDurationMillis(1050)
                    .build());
            assertThrows(IllegalArgumentException.class, () -> OpusEncoderOptions.builder()
                    .format(48000, ChannelLayout.SURROUND_5_1).dredDurationMillis(100).build());
            var encoder = new OpusEncoder(OpusEncoderOptions.forVoip(48000, 1));
            assertThrows(IllegalArgumentException.class, () -> encoder.setDredDurationMillis(7));
            encoder.setDredDurationMillis(200);
            assertEquals(200, encoder.dredDurationMillis());
            encoder.setDredDurationMillis(0);
            assertEquals(0, encoder.dredDurationMillis());
        }
    }

    @Nested
    @DisplayName("DRED")
    class Redundancy {
        @Test
        @DisplayName("finds no redundancy in a packet without the extension")
        void none() {
            var packet = OpusDnnGoldenTest.packets("e_plain16").get(20);
            assertFalse(OpusPackets.hasDred(packet, 0, packet.length));
            var dred = new OpusDred();
            assertEquals(0, dred.parse(packet, 0, packet.length, 48000, 48000));
            assertTrue(dred.isEmpty());
            assertEquals(0, dred.availableSamples());
            assertEquals(0, dred.endSamples());
        }

        @Test
        @DisplayName("parses the redundancy of a packet and caps it to the span asked for")
        void parse() {
            var packet = firstDredPacket(100);
            var dred = new OpusDred();
            var full = dred.parse(packet, 0, packet.length, 48000, 48000);
            assertTrue(full > 0, "available " + full);
            assertFalse(dred.isEmpty());
            assertEquals(48000, dred.sampleRate());
            // A maximum far beyond what DRED can hold gives all of it, without overflowing.
            var all = dred.parse(packet, 0, packet.length, Integer.MAX_VALUE, 48000);
            assertTrue(all >= full, "all " + all + ", 1 s " + full);
            var shorter = dred.parse(packet, 0, packet.length, 960, 48000);
            assertTrue(shorter > 0 && shorter <= full, shorter + " of " + full);
            var at16k = dred.parse(packet, 0, packet.length, 16000, 16000);
            assertEquals(16000, dred.sampleRate());
            assertTrue(at16k > 0);
            dred.clear();
            assertTrue(dred.isEmpty());
            assertEquals(0, dred.availableSamples());
        }

        @Test
        @DisplayName("rejects empty, invalid and out-of-range arguments")
        void rejects() {
            var dred = new OpusDred();
            var packet = firstDredPacket(0);
            assertThrows(IllegalArgumentException.class, () -> dred.parse(packet, 0, 0, 960, 48000));
            assertThrows(IllegalArgumentException.class, () -> dred.parse(packet, 0, packet.length, -1, 48000));
            assertThrows(IllegalArgumentException.class, () -> dred.parse(packet, 0, packet.length, 960, 44100));
            assertThrows(IndexOutOfBoundsException.class, () -> dred.parse(packet, 1, packet.length, 960, 48000));
            var broken = new byte[]{(byte) 0xFF, (byte) 0x7F};
            assertThrows(MediaException.Malformed.class, () -> dred.parse(broken, 0, broken.length, 960, 48000));
            assertTrue(dred.isEmpty());
        }

        @Test
        @DisplayName("needs a decoder of complexity 5 or more that decodes one stream")
        void decoderState() {
            var dred = new OpusDred();
            var packet = firstDredPacket(100);
            dred.parse(packet, 0, packet.length, 48000, 48000);
            var pcm = new float[960];
            var classic = new OpusDecoder(OpusDecoderOptions.of(48000, 1));
            assertThrows(IllegalStateException.class, () -> classic.recover(dred, 960, pcm, 0, 960));
            var deep = new OpusDecoder(OpusDecoderOptions.of(48000, 1).toBuilder().complexity(5).build());
            assertThrows(IllegalArgumentException.class, () -> deep.recover(dred, 960, pcm, 0, 100));
            assertThrows(IndexOutOfBoundsException.class, () -> deep.recover(dred, 960, new float[959], 0, 960));
            var at16k = new OpusDecoder(OpusDecoderOptions.of(16000, 1).toBuilder().complexity(5).build());
            assertThrows(IllegalArgumentException.class, () -> at16k.recover(dred, 960, pcm, 0, 320));
            var head = new OpusEncoder(OpusEncoderOptions.builder().format(48000, ChannelLayout.SURROUND_5_1)
                    .build()).codecConfig().orElseThrow();
            var multistream = new OpusDecoder(OpusProjectionGoldenTest.track(head, 48000),
                    OpusDecoderOptions.builder().complexity(5).build());
            assertThrows(IllegalStateException.class, () -> multistream.recover(dred, 960, new float[960 * 6], 0,
                    960));
            deep.close();
            assertThrows(IllegalStateException.class, () -> deep.recover(dred, 960, pcm, 0, 960));
        }

        @Test
        @DisplayName("a reset decoder keeps its complexity and decodes like a new one")
        void resetKeepsNeuralMode() {
            var packets = dredPackets();
            var options = OpusDecoderOptions.of(48000, 1).toBuilder().complexity(7).build();
            var used = new OpusDecoder(options);
            var pcm = new float[5760];
            var dred = new OpusDred();
            for (var i = 0; i < 120; i++) {
                if (i % 7 == 3) {
                    used.conceal(pcm, 0, 960);
                } else if (i % 7 == 4) {
                    dred.parse(packets.get(i), 0, packets.get(i).length, 960, 48000);
                    used.recover(dred, 960, pcm, 0, 960);
                }
                used.decode(packets.get(i), 0, packets.get(i).length, pcm, 0, 5760);
            }
            used.reset();
            var fresh = new OpusDecoder(options);
            var classic = new OpusDecoder(OpusDecoderOptions.of(48000, 1));
            var expected = new float[5760];
            for (var i = 120; i < 220; i++) {
                var packet = packets.get(i);
                assertEquals(fresh.decode(packet, 0, packet.length, expected, 0, 5760),
                        used.decode(packet, 0, packet.length, pcm, 0, 5760));
                classic.decode(packet, 0, packet.length, new float[5760], 0, 5760);
                assertArrayEquals(expected, pcm, "packet " + i + " after reset");
            }
            // The vocoder's recurrent state outlives a reset, as in libopus, so concealment is only checked to stay
            // neural: it differs from the classic concealment, and DRED recovery is still accepted.
            var classicConcealed = new float[960];
            classic.conceal(classicConcealed, 0, 960);
            used.conceal(pcm, 0, 960);
            assertFalse(Arrays.equals(classicConcealed, Arrays.copyOf(pcm, 960)));
            assertEquals(960, used.recover(new OpusDred(), 960, pcm, 0, 960));
        }

        @Test
        @DisplayName("conceals with the deep PLC alone when the holder is empty")
        void emptyHolder() {
            var packets = dredPackets();
            var a = new OpusDecoder(OpusDecoderOptions.of(48000, 1).toBuilder().complexity(5).build());
            var b = new OpusDecoder(OpusDecoderOptions.of(48000, 1).toBuilder().complexity(5).build());
            var pcm = new float[960];
            for (var i = 0; i < 50; i++) {
                a.decode(packets.get(i), 0, packets.get(i).length, pcm, 0, 960);
                b.decode(packets.get(i), 0, packets.get(i).length, pcm, 0, 960);
            }
            var expected = new float[960];
            var actual = new float[960];
            assertEquals(960, a.conceal(expected, 0, 960));
            assertEquals(960, b.recover(new OpusDred(), 960, actual, 0, 960));
            assertArrayEquals(expected, actual);
        }

        @Test
        @DisplayName("survives corrupted redundancy with finite output")
        void corrupt() {
            var random = new Random(42);
            var source = firstDredPacket(200);
            var decoder = new OpusDecoder(OpusDecoderOptions.of(48000, 1).toBuilder().complexity(5).build());
            var pcm = new float[960];
            var dred = new OpusDred();
            for (var trial = 0; trial < 200; trial++) {
                var packet = source.clone();
                var flips = 1 + random.nextInt(4);
                for (var k = 0; k < flips; k++) {
                    var at = packet.length - 1 - random.nextInt(Math.min(40, packet.length - 1));
                    packet[at] ^= (byte) (1 + random.nextInt(255));
                }
                var cut = random.nextBoolean() ? packet.length : 1 + random.nextInt(packet.length);
                try {
                    var available = dred.parse(packet, 0, cut, 48000, 48000);
                    assertTrue(available >= 0);
                } catch (MediaException.Malformed expected) {
                    assertTrue(dred.isEmpty());
                }
                decoder.recover(dred, 960, pcm, 0, 960);
                for (var sample : pcm) {
                    assertTrue(Float.isFinite(sample));
                }
            }
        }
    }

    @Nested
    @DisplayName("packet extensions")
    class Extensions {
        @Test
        @DisplayName("survive merging and splitting, renumbered to their frames")
        void mergeSplit() {
            var packets = dredPackets();
            var index = 0;
            for (var i = 101; i < packets.size(); i++) {
                if (OpusPackets.hasDred(packets.get(i), 0, packets.get(i).length)
                        && (packets.get(i - 1)[0] & 0xFC) == (packets.get(i)[0] & 0xFC)) {
                    index = i;
                    break;
                }
            }
            var plain = packets.get(index - 1);
            var dredPacket = packets.get(index);
            var merged = OpusPackets.merge(List.of(OpusPackets.split(plain, 0, plain.length).getFirst(),
                    dredPacket));
            assertTrue(OpusPackets.hasDred(merged, 0, merged.length));
            var single = new OpusDred().parse(dredPacket, 0, dredPacket.length, 48000, 48000);
            var shifted = new OpusDred().parse(merged, 0, merged.length, 48000, 48000);
            // The DRED of the second frame covers 20 ms less before the start of the merged packet.
            assertEquals(single - 960, shifted);
            var split = OpusPackets.split(merged, 0, merged.length);
            assertEquals(2, split.size());
            // Each frame gets back exactly its own extensions, so the split restores both packets byte for byte.
            assertArrayEquals(plain, split.get(0));
            assertArrayEquals(dredPacket, split.get(1));
            assertTrue(OpusPackets.hasDred(split.get(1), 0, split.get(1).length));
            assertEquals(single, new OpusDred().parse(split.get(1), 0, split.get(1).length, 48000, 48000));
        }
    }

    @Nested
    @DisplayName("quality")
    class Quality {
        /** Drops bursts of five 20 ms packets every second, from the second second on. */
        private static boolean lost(int index) {
            return index >= 50 && index % 50 < 5;
        }

        private static float[] decode(List<byte[]> packets, int complexity, int mode) {
            var decoder = new OpusDecoder(OpusDecoderOptions.of(48000, 1).toBuilder().complexity(complexity).build());
            var dred = new OpusDred();
            var out = new ArrayList<float[]>();
            var pcm = new float[5760];
            var lostCount = 0;
            for (var i = 0; i < packets.size(); i++) {
                var packet = packets.get(i);
                if (mode > 0 && lost(i)) {
                    lostCount++;
                    continue;
                }
                if (lostCount > 0) {
                    var available = mode == 2 ? dred.parse(packet, 0, packet.length, lostCount * 960, 48000) : 0;
                    for (var fr = 0; fr < lostCount; fr++) {
                        if (available > 0) {
                            decoder.recover(dred, (lostCount - fr) * 960, pcm, 0, 960);
                        } else {
                            decoder.conceal(pcm, 0, 960);
                        }
                        out.add(Arrays.copyOf(pcm, 960));
                    }
                    lostCount = 0;
                }
                var n = decoder.decode(packet, 0, packet.length, pcm, 0, 5760);
                out.add(Arrays.copyOf(pcm, n));
            }
            var total = out.stream().mapToInt(a -> a.length).sum();
            var all = new float[total];
            var pos = 0;
            for (var a : out) {
                System.arraycopy(a, 0, all, pos, a.length);
                pos += a.length;
            }
            return all;
        }

        private static float[] lostSpans(float[] signal, int packets) {
            var spans = new ArrayList<float[]>();
            for (var i = 0; i < packets; i++) {
                if (lost(i) && (i + 1) * 960 <= signal.length) {
                    spans.add(Arrays.copyOfRange(signal, i * 960, (i + 1) * 960));
                }
            }
            var out = new float[spans.size() * 960];
            for (var k = 0; k < spans.size(); k++) {
                System.arraycopy(spans.get(k), 0, out, k * 960, 960);
            }
            return out;
        }

        @Test
        @DisplayName("rebuilds bursts of lost speech closer to the lossless decode than the classic concealment")
        void dredBeatsClassicConcealment() {
            var packets = dredPackets();
            var reference = decode(packets, 0, 0);
            var classic = decode(packets, 0, 1);
            var deep = decode(packets, 5, 1);
            var dred = decode(packets, 5, 2);
            var ref = lostSpans(reference, packets.size());
            var classicDistance = AudioMetrics.logSpectralDistance(ref, lostSpans(classic, packets.size()), 48000,
                    8000);
            var deepDistance = AudioMetrics.logSpectralDistance(ref, lostSpans(deep, packets.size()), 48000, 8000);
            var dredDistance = AudioMetrics.logSpectralDistance(ref, lostSpans(dred, packets.size()), 48000, 8000);
            var classicSnr = AudioMetrics.segmentalSnr(ref, lostSpans(classic, packets.size()), 48000);
            var dredSnr = AudioMetrics.segmentalSnr(ref, lostSpans(dred, packets.size()), 48000);
            var summary = "log-spectral distance classic " + classicDistance + ", deep PLC " + deepDistance
                    + ", DRED " + dredDistance + "; segmental SNR classic " + classicSnr + ", DRED " + dredSnr;
            System.out.println(summary);
            assertTrue(dredDistance < classicDistance, summary);
            assertTrue(deepDistance < classicDistance, summary);
            assertTrue(dredDistance < deepDistance, summary);
        }
    }
}
