package com.github.auties00.smalt.audio;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.pcm.PcmDecoder;
import com.github.auties00.smalt.audio.pcm.PcmEncoder;
import com.github.auties00.smalt.audio.pcm.PcmEncoderOptions;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.container.wav.WavMuxer;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.PacketFlag;
import com.github.auties00.smalt.track.PacketSideData;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.transcode.AudioTranscodeOptions;
import com.github.auties00.smalt.transcode.OutputContainer;
import com.github.auties00.smalt.transcode.TranscodeOptions;
import com.github.auties00.smalt.transcode.Transcoder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the exact sample outputs: the integer and double buffers, the PCM decoder's integer and double outputs and
 * the PCM encoder's integer and double inputs against independently computed values, the integer and double modes of
 * {@link AudioStreamDecoder} and {@link AudioReader} (trimming, seeking and argument checks) and the transcodes that
 * keep PCM integers and 64-bit floats bit for bit.
 */
@DisplayName("exact sample output")
class ExactSampleOutputTest {
    private static final int RATE = 48_000;

    /** The bits of the integer output of an integer PCM codec, 0 for the others. */
    static int integerBits(Codec codec) {
        return switch (codec) {
            case PCM_U8, PCM_S8 -> 8;
            case PCM_ALAW, PCM_MULAW, PCM_S16LE, PCM_S16BE -> 16;
            case PCM_S24LE, PCM_S24BE -> 24;
            case PCM_S32LE, PCM_S32BE -> 32;
            default -> 0;
        };
    }

    /** Reads one coded integer sample independently of the decoder. */
    static long reference(Codec codec, byte[] data, int position) {
        var le = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        var be = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return switch (codec) {
            case PCM_U8 -> (data[position] & 0xFF) - 128;
            case PCM_S8 -> data[position];
            case PCM_S16LE -> le.getShort(position);
            case PCM_S16BE -> be.getShort(position);
            case PCM_S24LE -> (data[position] & 0xFF) | (data[position + 1] & 0xFF) << 8 | data[position + 2] << 16;
            case PCM_S24BE -> data[position] << 16 | (data[position + 1] & 0xFF) << 8 | (data[position + 2] & 0xFF);
            case PCM_S32LE -> le.getInt(position);
            case PCM_S32BE -> be.getInt(position);
            case PCM_S64LE -> le.getLong(position);
            case PCM_S64BE -> be.getLong(position);
            default -> throw new IllegalArgumentException(codec.toString());
        };
    }

    static AudioTrack pcmTrack(Codec codec, ChannelLayout layout) {
        return AudioTrack.builder().id(0).codec(codec).timeBase(Rational.timeBaseOf(RATE)).sampleRate(RATE)
                .layout(layout).bitsPerSample(codec.pcmBitsPerSample().orElseThrow()).build();
    }

    static byte[] randomBytes(int size, long seed) {
        var bytes = new byte[size];
        new SplittableRandom(seed).nextBytes(bytes);
        return bytes;
    }

    static int[][] uniform(int channels, int bits, int frames, long seed) {
        var random = new SplittableRandom(seed);
        var out = new int[channels][frames];
        for (var c = 0; c < channels; c++) {
            for (var i = 0; i < frames; i++) {
                out[c][i] = (int) (random.nextLong(1L << bits) - (1L << (bits - 1)));
            }
        }
        return out;
    }

    @Nested
    @DisplayName("buffers")
    class Buffers {
        @Test
        @DisplayName("an integer buffer checks its format and copies its valid samples")
        void integers() {
            assertThrows(IllegalArgumentException.class, () -> new IntegerAudioBuffer(ChannelLayout.MONO, RATE, 0, 4));
            assertThrows(IllegalArgumentException.class, () -> new IntegerAudioBuffer(ChannelLayout.MONO, RATE, 33, 4));
            assertThrows(IllegalArgumentException.class, () -> new IntegerAudioBuffer(ChannelLayout.MONO, 0, 16, 4));
            assertThrows(IllegalArgumentException.class, () -> new IntegerAudioBuffer(ChannelLayout.MONO, RATE, 16, -1));
            assertThrows(IllegalArgumentException.class,
                    () -> new IntegerAudioBuffer(ChannelLayout.STEREO, RATE, 16, new int[][]{new int[4]}, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> new IntegerAudioBuffer(ChannelLayout.STEREO, RATE, 16, new int[][]{new int[4], new int[3]}, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> new IntegerAudioBuffer(ChannelLayout.MONO, RATE, 16, new int[][]{new int[4]}, 5));
            var planes = new int[][]{{1, -2, Integer.MAX_VALUE, Integer.MIN_VALUE, 9}};
            var buffer = new IntegerAudioBuffer(ChannelLayout.MONO, RATE, 32, planes, 4);
            assertEquals(5, buffer.capacity());
            assertEquals(32, buffer.bitsPerSample());
            var copy = buffer.copy();
            assertNotSame(planes[0], copy.plane(0));
            assertArrayEquals(new int[]{1, -2, Integer.MAX_VALUE, Integer.MIN_VALUE}, copy.plane(0));
            assertThrows(IllegalArgumentException.class, () -> buffer.copy(5));
            assertThrows(IllegalArgumentException.class, () -> buffer.setFrames(6));
            var floats = buffer.toFloat();
            assertEquals(4, floats.frames());
            assertEquals((float) (Integer.MAX_VALUE * 0x1p-31), floats.plane(0)[2]);
            assertEquals(-1f, floats.plane(0)[3]);
            assertEquals(1_000_000L * 4 / RATE, buffer.durationMicros());
        }

        @Test
        @DisplayName("a double buffer checks its format, copies its valid samples and rounds to floats")
        void doubles() {
            assertThrows(IllegalArgumentException.class, () -> new DoubleAudioBuffer(ChannelLayout.MONO, 0, 4));
            assertThrows(IllegalArgumentException.class, () -> new DoubleAudioBuffer(ChannelLayout.MONO, RATE, -1));
            assertThrows(IllegalArgumentException.class,
                    () -> new DoubleAudioBuffer(ChannelLayout.STEREO, RATE, new double[][]{new double[2]}, 0));
            var value = 1.0 / 3;
            var buffer = new DoubleAudioBuffer(ChannelLayout.MONO, RATE, new double[][]{{value, -0.5, 0.25}}, 2);
            var copy = buffer.copy();
            assertArrayEquals(new double[]{value, -0.5}, copy.plane(0));
            assertEquals((float) value, buffer.toFloat().plane(0)[0]);
            assertNotEquals((double) (float) value, value);
        }
    }

    @Nested
    @DisplayName("PCM decoder")
    class PcmDecoding {
        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"PCM_U8", "PCM_S8", "PCM_S16LE", "PCM_S16BE", "PCM_S24LE",
                "PCM_S24BE", "PCM_S32LE", "PCM_S32BE"})
        @DisplayName("integer formats decode to their integers, and their floats and doubles scale them")
        void integers(Codec codec) {
            var layout = ChannelLayout.STEREO;
            var bytes = codec.pcmBitsPerSample().orElseThrow() / 8;
            var data = randomBytes(997 * 2 * bytes, codec.ordinal());
            var packet = new Packet(0, data, 0, 0, 997, true);
            try (var decoder = new PcmDecoder(pcmTrack(codec, layout))) {
                var bits = integerBits(codec);
                assertTrue(decoder.supportsIntegerOutput());
                assertTrue(decoder.supportsDoubleOutput());
                assertEquals(bits, decoder.bitsPerSample());
                var integers = new IntegerAudioBuffer[1];
                var doubles = new DoubleAudioBuffer[1];
                var floats = new AudioBuffer[1];
                decoder.decodeIntegers(packet, block -> integers[0] = block.copy());
                decoder.decodeDoubles(packet, block -> doubles[0] = block.copy());
                decoder.decode(packet, block -> floats[0] = block.copy());
                assertEquals(997, integers[0].frames());
                assertEquals(bits, integers[0].bitsPerSample());
                var scale = Math.scalb(1.0, 1 - bits);
                for (var c = 0; c < 2; c++) {
                    for (var i = 0; i < 997; i++) {
                        var expected = reference(codec, data, (2 * i + c) * bytes);
                        assertEquals(expected, integers[0].plane(c)[i], "sample " + i);
                        assertEquals(expected * scale, doubles[0].plane(c)[i]);
                        assertEquals((float) (expected * scale), floats[0].plane(c)[i]);
                    }
                }
                decoder.flushIntegers(block -> {
                    throw new AssertionError("PCM buffers nothing");
                });
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"PCM_ALAW", "PCM_MULAW"})
        @DisplayName("G.711 codes decode to their 16-bit linear values")
        void g711(Codec codec) {
            var data = new byte[256];
            for (var i = 0; i < 256; i++) {
                data[i] = (byte) i;
            }
            try (var decoder = new PcmDecoder(pcmTrack(codec, ChannelLayout.MONO))) {
                var integers = new int[256];
                var floats = new float[256];
                decoder.decodeIntegers(new Packet(0, data, 0, 0, 256, true),
                        block -> System.arraycopy(block.plane(0), 0, integers, 0, 256));
                decoder.decode(new Packet(0, data, 0, 0, 256, true),
                        block -> System.arraycopy(block.plane(0), 0, floats, 0, 256));
                for (var i = 0; i < 256; i++) {
                    assertEquals(integers[i] * 0x1p-15f, floats[i], "code " + i);
                }
                if (codec == Codec.PCM_ALAW) {
                    assertEquals(8, integers[0xD5]);
                    assertEquals(-8, integers[0x55]);
                    assertEquals(32256, integers[0xAA]);
                    assertEquals(-32256, integers[0x2A]);
                } else {
                    assertEquals(0, integers[0xFF]);
                    assertEquals(-32124, integers[0x00]);
                    assertEquals(32124, integers[0x80]);
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"PCM_S64LE", "PCM_S64BE", "PCM_F32LE", "PCM_F32BE", "PCM_F64LE",
                "PCM_F64BE"})
        @DisplayName("64-bit integers and floats have no integer output")
        void noIntegers(Codec codec) {
            try (var decoder = new PcmDecoder(pcmTrack(codec, ChannelLayout.MONO))) {
                assertFalse(decoder.supportsIntegerOutput());
                assertEquals(0, decoder.bitsPerSample());
                assertThrows(UnsupportedOperationException.class,
                        () -> decoder.decodeIntegers(new Packet(0, new byte[8], 0, 0, 1, true), block -> {
                        }));
                assertThrows(UnsupportedOperationException.class, () -> decoder.flushIntegers(block -> {
                }));
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"PCM_F64LE", "PCM_F64BE"})
        @DisplayName("64-bit floats decode to doubles bit for bit, with non-finite values replaced")
        void doubles(Codec codec) {
            var random = new SplittableRandom(7);
            var values = new double[2000];
            for (var i = 0; i < values.length; i++) {
                values[i] = switch (i % 8) {
                    case 0 -> Double.longBitsToDouble(random.nextLong() & 0x000F_FFFF_FFFF_FFFFL);
                    case 1 -> -0.0;
                    case 2 -> random.nextDouble() * 8 - 4;
                    default -> random.nextDouble() * 2 - 1;
                };
            }
            values[3] = Double.NaN;
            values[4] = Double.POSITIVE_INFINITY;
            values[5] = Double.NEGATIVE_INFINITY;
            var data = ByteBuffer.allocate(values.length * 8)
                    .order(codec == Codec.PCM_F64LE ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);
            for (var value : values) {
                data.putDouble(value);
            }
            try (var decoder = new PcmDecoder(pcmTrack(codec, ChannelLayout.MONO))) {
                var decoded = new double[values.length];
                decoder.decodeDoubles(new Packet(0, data.array(), 0, 0, values.length, true),
                        block -> System.arraycopy(block.plane(0), 0, decoded, 0, values.length));
                for (var i = 0; i < values.length; i++) {
                    var expected = switch (i) {
                        case 3 -> 0.0;
                        case 4 -> 1.0;
                        case 5 -> -1.0;
                        default -> values[i];
                    };
                    assertEquals(Double.doubleToRawLongBits(expected), Double.doubleToRawLongBits(decoded[i]),
                            "sample " + i);
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"PCM_S64LE", "PCM_S64BE", "PCM_F32LE", "PCM_F32BE"})
        @DisplayName("64-bit integers and 32-bit floats decode to their double values")
        void wideDoubles(Codec codec) {
            var bytes = codec.pcmBitsPerSample().orElseThrow() / 8;
            var data = randomBytes(500 * bytes, 11);
            if (bytes == 4) {
                // keep the floats finite
                for (var i = 0; i < 500; i++) {
                    data[i * 4 + (codec == Codec.PCM_F32LE ? 3 : 0)] &= (byte) 0xBF;
                }
            }
            try (var decoder = new PcmDecoder(pcmTrack(codec, ChannelLayout.MONO))) {
                var decoded = new double[500];
                decoder.decodeDoubles(new Packet(0, data, 0, 0, 500, true),
                        block -> System.arraycopy(block.plane(0), 0, decoded, 0, 500));
                var order = codec.name().endsWith("LE") ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
                var buffer = ByteBuffer.wrap(data).order(order);
                for (var i = 0; i < 500; i++) {
                    var expected = bytes == 8 ? buffer.getLong(i * 8) * 0x1p-63 : (double) buffer.getFloat(i * 4);
                    assertEquals(expected, decoded[i], "sample " + i);
                }
            }
        }
    }

    @Nested
    @DisplayName("PCM encoder")
    class PcmEncoding {
        private static byte[] encodeIntegers(Codec codec, int[][] planes, int bits) {
            var layout = ChannelLayout.defaultFor(planes.length);
            var encoder = new PcmEncoder(PcmEncoderOptions.of(codec, RATE, layout));
            assertTrue(encoder.supportsIntegerInput());
            var packets = new ArrayList<Packet>();
            encoder.encode(new IntegerAudioBuffer(layout, RATE, bits, planes, planes[0].length), packets::add);
            encoder.flush(packets::add);
            var out = new java.io.ByteArrayOutputStream();
            packets.forEach(packet -> out.writeBytes(packet.data()));
            return out.toByteArray();
        }

        private static byte[] encodeDoubles(Codec codec, double[] samples) {
            var encoder = new PcmEncoder(PcmEncoderOptions.of(codec, RATE, ChannelLayout.MONO));
            assertTrue(encoder.supportsDoubleInput());
            var packets = new ArrayList<Packet>();
            encoder.encode(new DoubleAudioBuffer(ChannelLayout.MONO, RATE, new double[][]{samples}, samples.length),
                    packets::add);
            encoder.flush(packets::add);
            var out = new java.io.ByteArrayOutputStream();
            packets.forEach(packet -> out.writeBytes(packet.data()));
            return out.toByteArray();
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"PCM_U8", "PCM_S8", "PCM_S16LE", "PCM_S16BE", "PCM_S24LE",
                "PCM_S24BE", "PCM_S32LE", "PCM_S32BE"})
        @DisplayName("integers of the format's size encode to codes that decode back to them")
        void roundTrip(Codec codec) {
            var bits = integerBits(codec);
            var samples = uniform(2, bits, 3000, bits);
            var data = encodeIntegers(codec, samples, bits);
            try (var decoder = new PcmDecoder(pcmTrack(codec, ChannelLayout.STEREO))) {
                var decoded = new int[2][];
                decoder.decodeIntegers(new Packet(0, data, 0, 0, 3000, true), block -> {
                    decoded[0] = Arrays.copyOf(block.plane(0), 3000);
                    decoded[1] = Arrays.copyOf(block.plane(1), 3000);
                });
                assertArrayEquals(samples[0], decoded[0]);
                assertArrayEquals(samples[1], decoded[1]);
            }
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"PCM_ALAW", "PCM_MULAW"})
        @DisplayName("G.711 linear values encode back to their codes")
        void g711(Codec codec) {
            var codes = new byte[256];
            for (var i = 0; i < 256; i++) {
                codes[i] = (byte) i;
            }
            var linear = new int[1][256];
            try (var decoder = new PcmDecoder(pcmTrack(codec, ChannelLayout.MONO))) {
                decoder.decodeIntegers(new Packet(0, codes, 0, 0, 256, true),
                        block -> System.arraycopy(block.plane(0), 0, linear[0], 0, 256));
            }
            var encoded = encodeIntegers(codec, linear, 16);
            for (var i = 0; i < 256; i++) {
                var expected = codec == Codec.PCM_MULAW && i == 0x7F ? 0xFF : i;
                assertEquals(expected, encoded[i] & 0xFF, "code " + i);
            }
        }

        @Test
        @DisplayName("narrower integers are shifted left, wider ones rounded to nearest, ties up, and clamped")
        void rescale() {
            var narrow = encodeIntegers(Codec.PCM_S64LE, new int[][]{{-1, 1, Integer.MIN_VALUE}}, 32);
            var longs = ByteBuffer.wrap(narrow).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(-1L << 32, longs.getLong(0));
            assertEquals(1L << 32, longs.getLong(8));
            assertEquals(Long.MIN_VALUE, longs.getLong(16));
            var wide = encodeIntegers(Codec.PCM_S16LE, new int[][]{{0x7FFF_FFFF, 0x7FFF_8000, 0x7FFF_7FFF, 0x8000,
                    0x7FFF, -0x8000, -0x8001, Integer.MIN_VALUE}}, 32);
            var shorts = ByteBuffer.wrap(wide).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(32767, shorts.getShort(0));
            assertEquals(32767, shorts.getShort(2));
            assertEquals(32767, shorts.getShort(4));
            assertEquals(1, shorts.getShort(6));
            assertEquals(0, shorts.getShort(8));
            assertEquals(0, shorts.getShort(10));
            assertEquals(-1, shorts.getShort(12));
            assertEquals(-32768, shorts.getShort(14));
            var bytes8 = encodeIntegers(Codec.PCM_U8, new int[][]{{-8, 7}}, 4);
            assertEquals(128 - 128, bytes8[0] & 0xFF);
            assertEquals(128 + 112, bytes8[1] & 0xFF);
        }

        @ParameterizedTest(name = "{0} bits")
        @ValueSource(ints = {16, 24, 32})
        @DisplayName("integers encode to 64-bit floats exactly and to 32-bit floats rounded")
        void toFloats(int bits) {
            var samples = uniform(1, bits, 2000, bits + 50L);
            var f64 = ByteBuffer.wrap(encodeIntegers(Codec.PCM_F64LE, samples, bits)).order(ByteOrder.LITTLE_ENDIAN);
            var f32 = ByteBuffer.wrap(encodeIntegers(Codec.PCM_F32BE, samples, bits)).order(ByteOrder.BIG_ENDIAN);
            var scale = Math.scalb(1.0, 1 - bits);
            for (var i = 0; i < 2000; i++) {
                assertEquals(samples[0][i] * scale, f64.getDouble(i * 8));
                assertEquals((float) (samples[0][i] * scale), f32.getFloat(i * 4));
            }
        }

        @Test
        @DisplayName("doubles encode to 64-bit floats unchanged, clipped, and to integers at double precision")
        void doubles() {
            var random = new SplittableRandom(3);
            var samples = new double[1000];
            for (var i = 0; i < samples.length; i++) {
                samples[i] = random.nextDouble() * 2 - 1;
            }
            samples[0] = 1.5;
            samples[1] = Double.NaN;
            samples[2] = -1;
            var f64 = ByteBuffer.wrap(encodeDoubles(Codec.PCM_F64BE, samples)).order(ByteOrder.BIG_ENDIAN);
            assertEquals(1.0, f64.getDouble(0));
            assertEquals(0.0, f64.getDouble(8));
            for (var i = 2; i < samples.length; i++) {
                assertEquals(Double.doubleToRawLongBits(samples[i]), Double.doubleToRawLongBits(f64.getDouble(i * 8)));
            }
            var s32 = ByteBuffer.wrap(encodeDoubles(Codec.PCM_S32LE, samples)).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(Integer.MAX_VALUE, s32.getInt(0));
            assertEquals(0, s32.getInt(4));
            assertEquals(Integer.MIN_VALUE, s32.getInt(8));
            for (var i = 3; i < samples.length; i++) {
                assertEquals((int) Math.clamp(Math.round(samples[i] * 0x1p31), Integer.MIN_VALUE, Integer.MAX_VALUE),
                        s32.getInt(i * 4));
            }
        }
    }

    @Nested
    @DisplayName("stream decoder")
    class StreamDecoding {
        /** A 24-bit PCM track of uniform samples in packets of 1000 frames, primed and trimmed by the container. */
        private record Stream(AudioTrack track, List<Packet> packets, int[][] samples) {
        }

        private static Stream stream(Codec codec, int frames) {
            var bits = codec.pcmBitsPerSample().orElseThrow();
            var samples = uniform(2, bits, frames, frames);
            var data = encodeInterleaved(codec, samples);
            var packets = new ArrayList<Packet>();
            var frameBytes = 2 * bits / 8;
            for (var start = 0; start < frames; start += 1000) {
                var count = Math.min(1000, frames - start);
                var packet = new Packet(0, Arrays.copyOfRange(data, start * frameBytes, (start + count) * frameBytes),
                        start, start, count, true);
                if (start == 3000) {
                    packet = packet.withFlags(Set.of(PacketFlag.KEY, PacketFlag.DISCARD));
                } else if (start == 5000) {
                    packet = packet.withSideData(PacketSideData.builder().discard(250, 0).build());
                }
                packets.add(packet);
            }
            var track = pcmTrack(codec, ChannelLayout.STEREO).toBuilder().skipSamples(123).duration(frames - 77).build();
            return new Stream(track, packets, samples);
        }

        private static byte[] encodeInterleaved(Codec codec, int[][] samples) {
            var encoder = new PcmEncoder(PcmEncoderOptions.of(codec, RATE, ChannelLayout.STEREO).toBuilder()
                    .framesPerPacket(1 << 20).build());
            var out = new java.io.ByteArrayOutputStream();
            encoder.encode(new IntegerAudioBuffer(ChannelLayout.STEREO, RATE, codec.pcmBitsPerSample().orElseThrow(),
                    samples, samples[0].length), packet -> out.writeBytes(packet.data()));
            encoder.flush(packet -> out.writeBytes(packet.data()));
            return out.toByteArray();
        }

        /** The samples a player outputs: priming, the discarded packet, the discard start and the end trim removed. */
        private static int[][] kept(Stream stream) {
            var frames = stream.samples()[0].length;
            var out = new int[2][];
            for (var c = 0; c < 2; c++) {
                var list = new ArrayList<Integer>();
                for (var i = 0; i < frames; i++) {
                    if ((i >= 3000 && i < 4000) || (i >= 5000 && i < 5250)) {
                        continue;
                    }
                    list.add(stream.samples()[c][i]);
                }
                var trimmed = list.subList(123, list.size());
                out[c] = trimmed.stream().mapToInt(Integer::intValue).toArray();
            }
            return out;
        }

        private static int[][] readIntegers(AudioStreamDecoder decoder, List<Packet> packets) {
            var collected = new int[2][0];
            var planes = new int[2][333];
            for (var packet : packets) {
                decoder.decode(packet);
                collected = drain(decoder, planes, collected);
            }
            decoder.finish();
            return drain(decoder, planes, collected);
        }

        private static int[][] drain(AudioStreamDecoder decoder, int[][] planes, int[][] collected) {
            int frames;
            while ((frames = decoder.readIntegers(planes, 0, planes[0].length)) > 0) {
                for (var c = 0; c < 2; c++) {
                    var grown = Arrays.copyOf(collected[c], collected[c].length + frames);
                    System.arraycopy(planes[c], 0, grown, collected[c].length, frames);
                    collected[c] = grown;
                }
            }
            return collected;
        }

        @ParameterizedTest(name = "{0}")
        @EnumSource(value = Codec.class, names = {"PCM_S24LE", "PCM_S32BE"})
        @DisplayName("integer output trims as float output does and keeps every bit")
        void integers(Codec codec) {
            var stream = stream(codec, 9000);
            var options = AudioStreamDecoderOptions.builder().sampleFormat(SampleFormat.INTEGER).build();
            var expected = kept(stream);
            // the declared duration counts the output after the priming
            var length = (int) Math.min(expected[0].length, stream.track().duration());
            try (var decoder = AudioStreamDecoder.create(stream.track(), options)) {
                assertEquals(SampleFormat.INTEGER, decoder.sampleFormat());
                assertEquals(codec.pcmBitsPerSample().orElseThrow(), decoder.bitsPerSample());
                var decoded = readIntegers(decoder, stream.packets());
                assertTrue(decoder.isDrained());
                for (var c = 0; c < 2; c++) {
                    assertArrayEquals(Arrays.copyOf(expected[c], length), decoded[c], "channel " + c);
                }
            }
            try (var floats = AudioStreamDecoder.create(stream.track())) {
                var planes = new float[2][20_000];
                var total = 0;
                for (var packet : stream.packets()) {
                    floats.decode(packet);
                    total += floats.read(planes, total, 20_000 - total);
                }
                floats.finish();
                total += floats.read(planes, total, 20_000 - total);
                assertEquals(length, total, "float output has the same length");
                var scale = Math.scalb(1.0, 1 - codec.pcmBitsPerSample().orElseThrow());
                for (var i = 0; i < total; i++) {
                    assertEquals((float) (expected[0][i] * scale), planes[0][i]);
                }
            }
        }

        @Test
        @DisplayName("double output trims as float output does and scales every integer exactly")
        void doubles() {
            var stream = stream(Codec.PCM_S32LE, 9000);
            var expected = kept(stream);
            var length = (int) Math.min(expected[0].length, stream.track().duration());
            var options = AudioStreamDecoderOptions.builder().sampleFormat(SampleFormat.DOUBLE).build();
            try (var decoder = AudioStreamDecoder.create(stream.track(), options)) {
                assertEquals(0, decoder.bitsPerSample());
                var planes = new double[2][20_000];
                var total = 0;
                for (var packet : stream.packets()) {
                    decoder.decode(packet);
                    total += decoder.readDoubles(planes, total, 20_000 - total);
                }
                decoder.finish();
                total += decoder.readDoubles(planes, total, 20_000 - total);
                assertEquals(length, total);
                for (var c = 0; c < 2; c++) {
                    for (var i = 0; i < total; i++) {
                        assertEquals(expected[c][i] * 0x1p-31, planes[c][i]);
                    }
                }
            }
        }

        @Test
        @DisplayName("exact formats reject conversions, codecs without integers and reads of another form")
        void rejects() {
            var track = pcmTrack(Codec.PCM_S16LE, ChannelLayout.STEREO);
            var integer = AudioStreamDecoderOptions.builder().sampleFormat(SampleFormat.INTEGER);
            assertThrows(IllegalArgumentException.class,
                    () -> AudioStreamDecoder.create(track, integer.sampleRate(44_100).build()));
            assertThrows(IllegalArgumentException.class, () -> AudioStreamDecoder.create(track,
                    integer.sourceSampleRate().layout(ChannelLayout.MONO).build()));
            assertThrows(IllegalArgumentException.class, () -> AudioStreamDecoder.create(track,
                    AudioStreamDecoderOptions.builder().sampleFormat(SampleFormat.DOUBLE).sampleRate(44_100).build()));
            assertThrows(MediaException.Unsupported.class, () -> AudioStreamDecoder.create(
                    pcmTrack(Codec.PCM_F32LE, ChannelLayout.STEREO), integer.sourceLayout().build()));
            try (var decoder = AudioStreamDecoder.create(track, integer.sourceLayout().build())) {
                assertThrows(IllegalStateException.class, () -> decoder.read(new float[2][4], 0, 4));
                assertThrows(IllegalStateException.class, () -> decoder.readDoubles(new double[2][4], 0, 4));
                assertThrows(IllegalArgumentException.class, () -> decoder.readIntegers(new int[1][4], 0, 4));
                assertThrows(IllegalArgumentException.class, () -> decoder.readIntegers(new int[2][4], 0, -1));
                decoder.decode(new Packet(0, new byte[40], 0, 0, 10, true));
                decoder.finish();
                assertThrows(IndexOutOfBoundsException.class, () -> decoder.readIntegers(new int[2][4], 0, 10));
                assertEquals(10, decoder.readIntegers(new int[2][10], 0, 10));
            }
            try (var decoder = AudioStreamDecoder.create(track)) {
                assertThrows(IllegalStateException.class, () -> decoder.readIntegers(new int[2][4], 0, 4));
            }
        }
    }

    @Nested
    @DisplayName("reader and transcoder")
    class Files {
        private static byte[] wav(Codec codec, List<Packet> packets) {
            var sink = MediaSink.memory();
            try (var muxer = new WavMuxer(sink)) {
                muxer.addTrack(pcmTrack(codec, ChannelLayout.STEREO));
                packets.forEach(muxer::writePacket);
                muxer.finish();
            }
            return sink.toByteArray();
        }

        private static List<Packet> s32Packets(int[][] samples) {
            var packets = new ArrayList<Packet>();
            var encoder = new PcmEncoder(PcmEncoderOptions.of(Codec.PCM_S32LE, RATE, ChannelLayout.STEREO).toBuilder()
                    .framesPerPacket(4096).build());
            encoder.encode(new IntegerAudioBuffer(ChannelLayout.STEREO, RATE, 32, samples, samples[0].length), packets::add);
            encoder.flush(packets::add);
            return packets;
        }

        private static int[][] readAll(AudioReader reader) {
            var out = new int[2][0];
            IntegerAudioBuffer block;
            while ((block = reader.readIntegers()) != null) {
                for (var c = 0; c < 2; c++) {
                    var grown = Arrays.copyOf(out[c], out[c].length + block.frames());
                    System.arraycopy(block.plane(c), 0, grown, out[c].length, block.frames());
                    out[c] = grown;
                }
            }
            return out;
        }

        @Test
        @DisplayName("a 32-bit WAV reads back bit for bit, after a seek too, while the float reader rounds it")
        void reader() {
            var samples = uniform(2, 32, 30_000, 9);
            var file = wav(Codec.PCM_S32LE, s32Packets(samples));
            var options = AudioReaderOptions.builder().sampleFormat(SampleFormat.INTEGER).blockFrames(1500).build();
            try (var reader = AudioReader.open(MediaSource.of(file), options)) {
                assertEquals(SampleFormat.INTEGER, reader.sampleFormat());
                assertEquals(32, reader.bitsPerSample());
                assertThrows(IllegalStateException.class, reader::read);
                assertThrows(IllegalStateException.class, reader::readDoubles);
                var decoded = readAll(reader);
                assertArrayEquals(samples[0], decoded[0]);
                assertArrayEquals(samples[1], decoded[1]);
                assertNull(reader.readIntegers());
                reader.seek(250_000);
                var block = reader.readIntegers();
                assertEquals(samples[0][12_000], block.plane(0)[0]);
                assertArrayEquals(Arrays.copyOfRange(samples[1], 12_000, 12_000 + block.frames()),
                        Arrays.copyOf(block.plane(1), block.frames()));
            }
            try (var reader = AudioReader.open(MediaSource.of(file))) {
                var block = reader.read();
                var rounded = false;
                for (var i = 0; i < block.frames(); i++) {
                    rounded |= (double) block.plane(0)[i] != samples[0][i] * 0x1p-31;
                }
                assertTrue(rounded, "the float output rounds 32-bit samples");
            }
        }

        @Test
        @DisplayName("a 64-bit float WAV reads back bit for bit through the double reader")
        void doubleReader() {
            var random = new SplittableRandom(13);
            var values = new double[2][10_000];
            for (var c = 0; c < 2; c++) {
                for (var i = 0; i < values[c].length; i++) {
                    values[c][i] = random.nextDouble() * 2 - 1;
                }
            }
            var encoder = new PcmEncoder(PcmEncoderOptions.of(Codec.PCM_F64LE, RATE, ChannelLayout.STEREO));
            var packets = new ArrayList<Packet>();
            encoder.encode(new DoubleAudioBuffer(ChannelLayout.STEREO, RATE, values, values[0].length), packets::add);
            encoder.flush(packets::add);
            var file = wav(Codec.PCM_F64LE, packets);
            var options = AudioReaderOptions.builder().sampleFormat(SampleFormat.DOUBLE).build();
            try (var reader = AudioReader.open(MediaSource.of(file), options)) {
                assertEquals(0, reader.bitsPerSample());
                var position = 0;
                DoubleAudioBuffer block;
                while ((block = reader.readDoubles()) != null) {
                    for (var c = 0; c < 2; c++) {
                        for (var i = 0; i < block.frames(); i++) {
                            assertEquals(values[c][position + i], block.plane(c)[i]);
                        }
                    }
                    position += block.frames();
                }
                assertEquals(values[0].length, position);
            }
            assertThrows(MediaException.Unsupported.class, () -> AudioReader.open(MediaSource.of(file),
                    AudioReaderOptions.builder().sampleFormat(SampleFormat.INTEGER).build()));
        }

        private static byte[] transcode(byte[] input, OutputContainer container, AudioTranscodeOptions audio,
                                        long start, long end) {
            var sink = MediaSink.memory();
            var options = TranscodeOptions.of(container).toBuilder().audio(audio);
            if (end > 0) {
                options.range(start, end);
            }
            Transcoder.transcode(MediaSource.of(input), sink, options.build());
            return sink.toByteArray();
        }

        private static byte[] pcmData(byte[] file) {
            try (var demuxer = Containers.openDemuxer(MediaSource.of(file))) {
                var out = new java.io.ByteArrayOutputStream();
                Packet packet;
                while ((packet = demuxer.read()) != null) {
                    out.writeBytes(packet.data());
                }
                return out.toByteArray();
            }
        }

        @Test
        @DisplayName("a trimmed 32-bit WAV re-encodes to 32-bit AIFF bit for bit")
        void integerTranscode() {
            var samples = uniform(2, 32, 48_000, 21);
            var file = wav(Codec.PCM_S32LE, s32Packets(samples));
            var aiff = transcode(file, OutputContainer.AIFF, AudioTranscodeOptions.encode(Codec.PCM_S32BE), 100_000,
                    600_000);
            var data = ByteBuffer.wrap(pcmData(aiff)).order(ByteOrder.BIG_ENDIAN);
            assertEquals(24_000 * 8, data.remaining());
            for (var i = 0; i < 24_000; i++) {
                assertEquals(samples[0][4800 + i], data.getInt(i * 8), "left " + i);
                assertEquals(samples[1][4800 + i], data.getInt(i * 8 + 4), "right " + i);
            }
        }

        @Test
        @DisplayName("a 64-bit float WAV re-encodes bit for bit, and a gain takes the float path")
        void doubleTranscode() {
            var random = new SplittableRandom(17);
            var values = new double[2][20_000];
            for (var c = 0; c < 2; c++) {
                for (var i = 0; i < values[c].length; i++) {
                    values[c][i] = random.nextDouble() - 0.5;
                }
            }
            var encoder = new PcmEncoder(PcmEncoderOptions.of(Codec.PCM_F64LE, RATE, ChannelLayout.STEREO));
            var packets = new ArrayList<Packet>();
            encoder.encode(new DoubleAudioBuffer(ChannelLayout.STEREO, RATE, values, values[0].length), packets::add);
            encoder.flush(packets::add);
            var file = wav(Codec.PCM_F64LE, packets);
            var exact = ByteBuffer.wrap(pcmData(transcode(file, OutputContainer.WAV,
                    AudioTranscodeOptions.encode(Codec.PCM_F64LE), 0, -1))).order(ByteOrder.LITTLE_ENDIAN);
            assertEquals(values[0].length * 16, exact.remaining());
            for (var i = 0; i < values[0].length; i++) {
                assertEquals(values[0][i], exact.getDouble(i * 16));
                assertEquals(values[1][i], exact.getDouble(i * 16 + 8));
            }
            var gained = ByteBuffer.wrap(pcmData(transcode(file, OutputContainer.WAV,
                    AudioTranscodeOptions.encode(Codec.PCM_F64LE).toBuilder().gainDb(-0.0001).build(), 0, -1)))
                    .order(ByteOrder.LITTLE_ENDIAN);
            assertEquals((double) (float) ((float) values[0][7] * (float) StrictMath.pow(10, -0.0001 / 20)),
                    gained.getDouble(7 * 16));
        }
    }
}
