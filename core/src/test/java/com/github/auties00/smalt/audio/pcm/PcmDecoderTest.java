package com.github.auties00.smalt.audio.pcm;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers exact PCM conversions for every PCM codec, the G.711 expansion tables, sanitizing of
 * non-finite floats, and the decoder contract.
 */
class PcmDecoderTest {
    static AudioTrack track(Codec codec, ChannelLayout layout) {
        return AudioTrack.builder().codec(codec).timeBase(Rational.timeBaseOf(8000)).sampleRate(8000).layout(layout)
                .build();
    }

    static float[][] decode(Codec codec, ChannelLayout layout, byte[] data) {
        var result = new ArrayList<float[][]>();
        try (var decoder = new PcmDecoder(track(codec, layout))) {
            decoder.decode(new Packet(0, data, 0, 0, 0, true), buffer -> result.add(copy(buffer)));
            decoder.flush(buffer -> fail("PCM holds nothing back"));
        }
        return result.isEmpty() ? new float[layout.channels()][0] : result.getFirst();
    }

    static float[][] copy(AudioBuffer buffer) {
        var planes = new float[buffer.channels()][];
        for (var c = 0; c < planes.length; c++) {
            planes[c] = java.util.Arrays.copyOf(buffer.plane(c), buffer.frames());
        }
        return planes;
    }

    @Nested
    @DisplayName("integer formats")
    class Integers {
        @Test
        @DisplayName("maps unsigned 8-bit with an offset of 128")
        void u8() {
            var planes = decode(Codec.PCM_U8, ChannelLayout.MONO, new byte[]{0, (byte) 128, (byte) 255, 64});
            assertArrayEquals(new float[]{-1f, 0f, 127 / 128f, -0.5f}, planes[0]);
        }

        @Test
        @DisplayName("maps signed 8-bit")
        void s8() {
            var planes = decode(Codec.PCM_S8, ChannelLayout.MONO, new byte[]{-128, 0, 127, 64});
            assertArrayEquals(new float[]{-1f, 0f, 127 / 128f, 0.5f}, planes[0]);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"PCM_S64LE, true", "PCM_S64BE, false"})
        @DisplayName("maps 64-bit integers to value / 2^63, rounded once to the nearest float")
        void signed64(Codec codec, boolean littleEndian) {
            var random = new SplittableRandom(64);
            var values = new long[2 * 300];
            var buffer = ByteBuffer.allocate(values.length * 8)
                    .order(littleEndian ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            for (var i = 0; i < values.length; i++) {
                values[i] = switch (i) {
                    case 0 -> Long.MIN_VALUE;
                    case 1 -> Long.MAX_VALUE;
                    case 2 -> 0;
                    case 3 -> -1;
                    // a value halfway between two floats, which rounding through a double would push to one side
                    case 4 -> (1L << 62) + (1L << 38) + 1;
                    default -> random.nextLong() >> random.nextInt(64);
                };
                buffer.putLong(values[i]);
            }
            var planes = decode(codec, ChannelLayout.STEREO, buffer.array());
            var scale = new BigDecimal(BigInteger.ONE.shiftLeft(63));
            for (var i = 0; i < 300; i++) {
                for (var c = 0; c < 2; c++) {
                    var expected = new BigDecimal(values[i * 2 + c]).divide(scale).floatValue();
                    assertEquals(expected, planes[c][i], "frame " + i + " channel " + c);
                }
            }
            assertEquals(-1f, planes[0][0]);
            assertEquals(1f, planes[1][0]);
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"PCM_S16LE, 2, true", "PCM_S16BE, 2, false", "PCM_S24LE, 3, true", "PCM_S24BE, 3, false",
                "PCM_S32LE, 4, true", "PCM_S32BE, 4, false"})
        @DisplayName("maps n-bit integers to value / 2^(n-1), exactly, deinterleaving channels")
        void signed(Codec codec, int bytes, boolean littleEndian) {
            var random = new SplittableRandom(bytes);
            var bits = bytes * 8;
            var frames = 257;
            var values = new long[frames * 2];
            var data = new byte[values.length * bytes];
            for (var i = 0; i < values.length; i++) {
                var value = i < 4 ? new long[]{-(1L << (bits - 1)), (1L << (bits - 1)) - 1, 0, -1}[i]
                        : random.nextLong(-(1L << (bits - 1)), 1L << (bits - 1));
                values[i] = value;
                for (var b = 0; b < bytes; b++) {
                    var shift = littleEndian ? 8 * b : 8 * (bytes - 1 - b);
                    data[i * bytes + b] = (byte) (value >> shift);
                }
            }
            var planes = decode(codec, ChannelLayout.STEREO, data);
            for (var i = 0; i < frames; i++) {
                for (var c = 0; c < 2; c++) {
                    var expected = (float) ((double) values[i * 2 + c] / (1L << (bits - 1)));
                    assertEquals(expected, planes[c][i], "frame " + i + " channel " + c);
                }
            }
            assertEquals(-1f, planes[0][0]);
        }
    }

    @Nested
    @DisplayName("float formats")
    class Floats {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"PCM_F32LE", "PCM_F32BE", "PCM_F64LE", "PCM_F64BE"})
        @DisplayName("passes finite values through and sanitizes NaN and infinities")
        void floats(Codec codec) {
            var wide = codec.name().contains("64");
            var order = codec.name().endsWith("LE") ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
            double[] values = {0.25, -1.5, 1e-9, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.1};
            var buffer = ByteBuffer.allocate(values.length * (wide ? 8 : 4)).order(order);
            for (var value : values) {
                if (wide) {
                    buffer.putDouble(value);
                } else {
                    buffer.putFloat((float) value);
                }
            }
            var planes = decode(codec, ChannelLayout.MONO, buffer.array());
            assertArrayEquals(new float[]{0.25f, -1.5f, 1e-9f, 0f, 1f, -1f, 0.1f}, planes[0]);
        }
    }

    @Nested
    @DisplayName("G.711")
    class G711 {
        @ParameterizedTest(name = "A-law 0x{0} -> {1}")
        @CsvSource({"d5, 8", "55, -8", "80, 5504", "00, -5504", "2a, -32256", "aa, 32256", "d4, 24", "54, -24", "ff, 848",
                "7f, -848", "c5, 264"})
        @DisplayName("expands A-law codes to the G.711 linear values")
        void alaw(String hex, int expected) {
            assertEquals(expected, PcmDecoder.alawToLinear(Integer.parseInt(hex, 16)));
        }

        @ParameterizedTest(name = "mu-law 0x{0} -> {1}")
        @CsvSource({"ff, 0", "7f, 0", "00, -32124", "80, 32124", "fe, 8", "7e, -8", "f0, 120", "ef, 132", "70, -120",
                "01, -31100", "81, 31100"})
        @DisplayName("expands mu-law codes to the G.711 linear values")
        void mulaw(String hex, int expected) {
            assertEquals(expected, PcmDecoder.mulawToLinear(Integer.parseInt(hex, 16)));
        }

        @Test
        @DisplayName("builds odd-symmetric tables whose codes decode monotonically within each sign")
        void tables() {
            for (var code = 0; code < 128; code++) {
                assertEquals(-PcmDecoder.alawToLinear(code), PcmDecoder.alawToLinear(code | 0x80), "A-law symmetry");
                assertEquals(-PcmDecoder.mulawToLinear(code), PcmDecoder.mulawToLinear(code | 0x80), "mu-law symmetry");
            }
            var codes = new byte[256];
            for (var i = 0; i < 256; i++) {
                codes[i] = (byte) i;
            }
            var alaw = decode(Codec.PCM_ALAW, ChannelLayout.MONO, codes)[0];
            var mulaw = decode(Codec.PCM_MULAW, ChannelLayout.MONO, codes)[0];
            for (var i = 0; i < 256; i++) {
                assertEquals(PcmDecoder.alawToLinear(i) / 32768f, alaw[i]);
                assertEquals(PcmDecoder.mulawToLinear(i) / 32768f, mulaw[i]);
            }
        }
    }

    @Nested
    @DisplayName("contract")
    class Contract {
        @Test
        @DisplayName("ignores a trailing partial frame and emits nothing for a packet shorter than a frame")
        void partialFrames() {
            var planes = decode(Codec.PCM_S16LE, ChannelLayout.STEREO, new byte[]{1, 0, 2, 0, 3});
            assertEquals(1, planes[0].length);
            assertEquals(0, decode(Codec.PCM_S16LE, ChannelLayout.STEREO, new byte[]{1, 0, 2})[0].length);
        }

        @Test
        @DisplayName("reuses its buffer and grows it for larger packets")
        void buffers() {
            var blocks = new ArrayList<AudioBuffer>();
            try (var decoder = new PcmDecoder(track(Codec.PCM_S16LE, ChannelLayout.MONO))) {
                decoder.decode(new Packet(0, new byte[20], 0, 0, 0, true), blocks::add);
                decoder.decode(new Packet(0, new byte[10], 0, 0, 0, true), blocks::add);
                decoder.decode(new Packet(0, new byte[200], 0, 0, 0, true), blocks::add);
                assertEquals(8000, decoder.sampleRate());
                assertEquals(ChannelLayout.MONO, decoder.layout());
            }
            assertSame(blocks.get(0), blocks.get(1));
            assertEquals(100, blocks.get(2).frames());
        }

        @Test
        @DisplayName("requires a PCM codec, a sample rate and a layout")
        void validation() {
            assertThrows(IllegalArgumentException.class, () -> new PcmDecoder(track(Codec.MP3, ChannelLayout.MONO)));
            var noRate = AudioTrack.builder().codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(8000))
                    .layout(ChannelLayout.MONO)
                    .build();
            assertThrows(MediaException.Malformed.class, () -> new PcmDecoder(noRate));
            var noLayout = AudioTrack.builder().codec(Codec.PCM_S16LE).timeBase(Rational.timeBaseOf(8000))
                    .sampleRate(8000)
                    .build();
            assertThrows(MediaException.Malformed.class, () -> new PcmDecoder(noLayout));
        }

        @Test
        @DisplayName("decodes random packets of every size for every codec and layout without failing")
        void robustness() {
            var random = new SplittableRandom(0x9C3);
            var codecs = List.of(Codec.values()).stream().filter(Codec::isPcm).toList();
            for (var codec : codecs) {
                for (var channels : new int[]{1, 2, 3, 8, 64}) {
                    try (var decoder = new PcmDecoder(track(codec, ChannelLayout.defaultFor(channels)))) {
                        for (var i = 0; i < 20; i++) {
                            var data = new byte[random.nextInt(0, 3000)];
                            random.nextBytes(data);
                            decoder.decode(new Packet(0, data, 0, 0, 0, true), buffer -> {
                                for (var c = 0; c < buffer.channels(); c++) {
                                    for (var s = 0; s < buffer.frames(); s++) {
                                        assertTrue(Float.isFinite(buffer.plane(c)[s]));
                                    }
                                }
                            });
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("supports every PCM codec constant")
        void everyCodec() {
            var pcm = List.of(Codec.values()).stream().filter(Codec::isPcm).toList();
            assertEquals(16, pcm.size());
            for (var codec : pcm) {
                var planes = decode(codec, ChannelLayout.STEREO, new byte[64]);
                assertTrue(planes[0].length > 0, codec.name());
            }
        }
    }
}
