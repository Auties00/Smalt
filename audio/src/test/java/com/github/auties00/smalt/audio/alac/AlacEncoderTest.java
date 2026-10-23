package com.github.auties00.smalt.audio.alac;

import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioEncoderOptions;
import com.github.auties00.smalt.audio.AudioEncoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.audio.alac.AlacSignals.Shape;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers {@link AlacEncoder}: lossless round trips through {@link AlacDecoder} for every sample size, channel count,
 * signal shape, length and frame length, the frame structure (partial and escaped elements), the configuration and
 * magic cookie, the channel assignment of layouts, float quantization, and the API contract.
 */
@DisplayName("ALAC encoder")
class AlacEncoderTest {
    static List<Packet> encode(AlacEncoder encoder, int[][] samples) {
        var packets = new ArrayList<Packet>();
        encoder.encode(samples, 0, samples[0].length, packets::add);
        encoder.flush(packets::add);
        return packets;
    }

    static AlacDecoder decoder(AlacEncoder encoder) {
        var track = AudioTrack.builder().codec(Codec.ALAC).timeBase(Rational.timeBaseOf(encoder.sampleRate()))
                .codecConfig(encoder.codecConfig().orElseThrow()).sampleRate(encoder.sampleRate()).build();
        return new AlacDecoder(track);
    }

    /** Decodes packets to integer planes, one per input channel, matched to the decoder's planes by speaker. */
    static int[][] decode(AlacEncoder encoder, List<Packet> packets, List<Speaker> speakers) {
        var decoder = decoder(encoder);
        var channels = speakers.size();
        var total = 0;
        for (var packet : packets) {
            total += (int) packet.duration();
        }
        var out = new int[channels][total];
        var position = 0;
        for (var packet : packets) {
            decoder.decode(packet, buffer -> {
            });
            var frames = decoder.lastFrames();
            assertEquals(packet.duration(), frames, "decoded frames of a packet");
            for (var c = 0; c < channels; c++) {
                var plane = decoder.layout().indexOf(speakers.get(c));
                System.arraycopy(decoder.lastSamples()[plane], 0, out[c], position, frames);
            }
            position += frames;
        }
        return out;
    }

    static Stream<Arguments> roundTrips() {
        var arguments = new ArrayList<Arguments>();
        var seed = 0L;
        var lengths = new int[]{1, 7, 31, 287, 4096, 4097, 3 * 4096 + 1000};
        for (var bits : new int[]{16, 20, 24, 32}) {
            for (var channels = 1; channels <= 8; channels++) {
                for (var shape : Shape.values()) {
                    var frames = lengths[(int) (seed % lengths.length)];
                    var fast = seed % 3 == 0;
                    arguments.add(Arguments.of(bits, channels, shape, frames, fast, seed));
                    seed++;
                }
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0}-bit {1} ch {2} {3} frames fast={4}")
    @MethodSource("roundTrips")
    @DisplayName("decodes to every input sample")
    void roundTrip(int bits, int channels, Shape shape, int frames, boolean fast, long seed) {
        var layout = AlacSignals.bitstreamLayout(channels);
        var samples = AlacSignals.samples(shape, channels, frames, bits, seed);
        var encoder = new AlacEncoder(AlacEncoderOptions.builder().format(44100, layout).bitsPerSample(bits)
                .fast(fast).build());
        var packets = encode(encoder, samples);
        assertEquals((frames + 4095) / 4096, packets.size(), "packet count");
        var decoded = decode(encoder, packets, layout.speakers());
        for (var c = 0; c < channels; c++) {
            assertArrayEquals(samples[c], decoded[c], "channel " + c);
        }
    }

    @ParameterizedTest(name = "frame length {0}")
    @ValueSource(ints = {16, 17, 100, 352, 1000, 4096, 8192, 65536})
    @DisplayName("decodes to every input sample at any frame length")
    void frameLengths(int frameLength) {
        for (var channels : new int[]{1, 2, 6}) {
            for (var bits : new int[]{16, 24, 32}) {
                var layout = AlacSignals.bitstreamLayout(channels);
                var frames = frameLength * 2 + frameLength / 3 + 5;
                var samples = AlacSignals.samples(Shape.MUSIC, channels, frames, bits, frameLength + bits);
                var encoder = new AlacEncoder(AlacEncoderOptions.builder().format(48000, layout).bitsPerSample(bits)
                        .frameLength(frameLength).build());
                var packets = encode(encoder, samples);
                assertEquals(3, packets.size());
                var decoded = decode(encoder, packets, layout.speakers());
                for (var c = 0; c < channels; c++) {
                    assertArrayEquals(samples[c], decoded[c], frameLength + " " + bits + "-bit channel " + c);
                }
            }
        }
    }

    /** Reads the element header fields after the tag of the first element: the partial, shift and escape flags. */
    static int firstElementFlags(byte[] packet) {
        // tag (3) and instance (4), twelve reserved bits, then the four flag bits
        var bits = (packet[0] & 0xFF) << 24 | (packet[1] & 0xFF) << 16 | (packet[2] & 0xFF) << 8 | packet[3] & 0xFF;
        return bits >>> (32 - 23) & 0xF;
    }

    @Nested
    @DisplayName("frame structure")
    class Structure {
        @Test
        @DisplayName("timestamps count samples from zero and the last packet is a partial frame")
        void timing() {
            var samples = AlacSignals.samples(Shape.MUSIC, 2, 10_000, 16, 3);
            var encoder = new AlacEncoder();
            var packets = encode(encoder, samples);
            assertEquals(3, packets.size());
            for (var index = 0; index < 3; index++) {
                var packet = packets.get(index);
                assertEquals(4096L * index, packet.pts());
                assertEquals(packet.pts(), packet.dts());
                assertTrue(packet.keyframe());
                assertEquals(index < 2 ? 4096 : 10_000 - 8192, packet.duration());
                var flags = firstElementFlags(packet.data());
                assertEquals(index < 2 ? 0 : 8, flags & 8, "partial flag of packet " + index);
            }
            assertEquals(10_000, encoder.samplesEncoded());
            var last = packets.get(2).data();
            var count = ByteBuffer.wrap(Arrays.copyOfRange(last, 2, 7)).getInt(0) << 7 | (last[6] & 0xFF) >>> 1;
            assertEquals(10_000 - 8192, count, "partial frame sample count");
        }

        @Test
        @DisplayName("full-scale noise is sent as escaped elements of the raw size")
        void escape() {
            for (var bits : new int[]{16, 20, 24, 32}) {
                for (var channels = 1; channels <= 2; channels++) {
                    for (var fast : new boolean[]{false, true}) {
                        var samples = AlacSignals.samples(Shape.NOISE, channels, 4096, bits, bits);
                        var encoder = new AlacEncoder(AlacEncoderOptions.builder()
                                .format(44100, AlacSignals.bitstreamLayout(channels)).bitsPerSample(bits).fast(fast)
                                .build());
                        var packets = encode(encoder, samples);
                        var data = packets.getFirst().data();
                        assertEquals(1, firstElementFlags(data), bits + "-bit " + channels + " ch escape flag");
                        // tag, instance and header (23 bits), the samples, the end tag, rounded up to bytes
                        var expected = (23 + 4096L * bits * channels + 3 + 7) / 8;
                        assertEquals(expected, data.length, bits + "-bit " + channels + " ch escaped size");
                        var decoded = decode(encoder, packets, AlacSignals.bitstreamLayout(channels).speakers());
                        for (var c = 0; c < channels; c++) {
                            assertArrayEquals(samples[c], decoded[c]);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("noise after a silent prefix, which the searches underestimate, is rewritten as escaped elements")
        void lateNoise() {
            for (var bits : new int[]{16, 20, 24, 32}) {
                for (var channels = 1; channels <= 2; channels++) {
                    // the searches sample the first eighth of the frame, which is silent here, so the element is
                    // written compressed, found larger than its escaped form, taken back and escaped
                    var samples = AlacSignals.samples(Shape.NOISE, channels, 4096, bits, bits + channels);
                    for (var c = 0; c < channels; c++) {
                        Arrays.fill(samples[c], 0, 512, 0);
                    }
                    var encoder = new AlacEncoder(AlacEncoderOptions.builder()
                            .format(44100, AlacSignals.bitstreamLayout(channels)).bitsPerSample(bits).build());
                    var packets = encode(encoder, samples);
                    var data = packets.getFirst().data();
                    assertEquals(1, firstElementFlags(data), bits + "-bit " + channels + " ch escape flag");
                    assertEquals((23 + 4096L * bits * channels + 3 + 7) / 8, data.length,
                            bits + "-bit " + channels + " ch escaped size");
                    var decoded = decode(encoder, packets, AlacSignals.bitstreamLayout(channels).speakers());
                    for (var c = 0; c < channels; c++) {
                        assertArrayEquals(samples[c], decoded[c], bits + "-bit " + channels + " ch channel " + c);
                    }
                }
            }
        }

        @Test
        @DisplayName("24- and 32-bit compressed elements shift off one and two low bytes")
        void shiftedBytes() {
            for (var bits : new int[]{16, 20, 24, 32}) {
                var encoder = new AlacEncoder(AlacEncoderOptions.builder().bitsPerSample(bits).build());
                var packets = encode(encoder, AlacSignals.samples(Shape.MUSIC, 2, 4096, bits, 5));
                var flags = firstElementFlags(packets.getFirst().data());
                assertEquals(0, flags & 1, "not escaped");
                assertEquals(bits == 32 ? 2 : bits == 24 ? 1 : 0, flags >> 1 & 3, bits + "-bit shifted bytes");
            }
        }

        @Test
        @DisplayName("no packet is larger than its escaped form")
        void bounded() {
            for (var shape : Shape.values()) {
                for (var channels = 1; channels <= 8; channels++) {
                    for (var bits : new int[]{16, 20, 24, 32}) {
                        var encoder = new AlacEncoder(AlacEncoderOptions.builder()
                                .format(44100, AlacSignals.bitstreamLayout(channels)).bitsPerSample(bits).build());
                        var packets = encode(encoder, AlacSignals.samples(shape, channels, 5000, bits, channels));
                        for (var packet : packets) {
                            var elements = new int[]{1, 1, 2, 3, 3, 4, 5, 5}[channels - 1];
                            var partial = packet.duration() != 4096 ? 32 : 0;
                            var escaped = (elements * (23 + partial) + packet.duration() * bits * channels + 3 + 7) / 8;
                            assertTrue(packet.size() <= escaped, shape + " " + channels + " ch " + bits + "-bit: "
                                    + packet.size() + " > " + escaped);
                        }
                    }
                }
            }
        }

        @Test
        @DisplayName("fast coding decodes losslessly and the search is never larger on music")
        void fastAndSearch() {
            var samples = AlacSignals.samples(Shape.MUSIC, 2, 40_000, 16, 11);
            var search = encode(new AlacEncoder(), samples);
            var fast = new AlacEncoder(AlacEncoderOptions.forSpeed());
            var quick = encode(fast, samples);
            var decoded = decode(fast, quick, ChannelLayout.STEREO.speakers());
            assertArrayEquals(samples[0], decoded[0]);
            assertArrayEquals(samples[1], decoded[1]);
            assertTrue(search.stream().mapToInt(Packet::size).sum() <= quick.stream().mapToInt(Packet::size).sum());
            assertFalse(Arrays.equals(search.getFirst().data(), quick.getFirst().data()));
        }

        @Test
        @DisplayName("the packets do not depend on how the input is split into calls")
        void chunking() {
            var samples = AlacSignals.samples(Shape.MUSIC, 3, 20_000, 24, 13);
            var whole = encode(new AlacEncoder(AlacEncoderOptions.of(48000, AlacSignals.bitstreamLayout(3), 24)),
                    samples);
            var random = new SplittableRandom(17);
            var encoder = new AlacEncoder(AlacEncoderOptions.of(48000, AlacSignals.bitstreamLayout(3), 24));
            var pieces = new ArrayList<Packet>();
            var interleaved = AlacSignals.interleave(samples);
            for (var offset = 0; offset < 20_000; ) {
                var count = Math.min(20_000 - offset, random.nextInt(0, 5000));
                if (random.nextBoolean()) {
                    encoder.encodeInterleaved(interleaved, offset * 3, count, pieces::add);
                } else {
                    encoder.encode(samples, offset, count, pieces::add);
                }
                offset += count;
            }
            encoder.flush(pieces::add);
            assertEquals(whole.size(), pieces.size());
            for (var index = 0; index < whole.size(); index++) {
                assertArrayEquals(whole.get(index).data(), pieces.get(index).data(), "packet " + index);
                assertEquals(whole.get(index).pts(), pieces.get(index).pts());
            }
        }

        @Test
        @DisplayName("an empty stream produces no packets")
        void empty() {
            var encoder = new AlacEncoder();
            var packets = new ArrayList<Packet>();
            encoder.flush(packets::add);
            assertEquals(List.of(), packets);
            var config = encoder.codecConfig().orElseThrow();
            assertEquals(0, ByteBuffer.wrap(config).getInt(12));
            assertEquals(0, ByteBuffer.wrap(config).getInt(16));
        }
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {
        @Test
        @DisplayName("the ALACSpecificConfig declares the format, and after the stream the largest packet and bitrate")
        void config() {
            var encoder = new AlacEncoder(AlacEncoderOptions.builder().format(96000, ChannelLayout.STEREO)
                    .bitsPerSample(24).frameLength(1024).build());
            var initial = ByteBuffer.wrap(encoder.codecConfig().orElseThrow());
            assertEquals(24, initial.capacity());
            assertEquals(1024, initial.getInt(0));
            assertEquals(0, initial.get(4));
            assertEquals(24, initial.get(5));
            assertEquals(40, initial.get(6));
            assertEquals(10, initial.get(7));
            assertEquals(14, initial.get(8));
            assertEquals(2, initial.get(9));
            assertEquals(255, initial.getShort(10));
            assertEquals(0, initial.getInt(12));
            assertEquals(0, initial.getInt(16));
            assertEquals(96000, initial.getInt(20));
            var packets = encode(encoder, AlacSignals.samples(Shape.MUSIC, 2, 96000, 24, 1));
            var last = ByteBuffer.wrap(encoder.codecConfig().orElseThrow());
            var max = packets.stream().mapToInt(Packet::size).max().orElseThrow();
            var bytes = packets.stream().mapToLong(Packet::size).sum();
            assertEquals(max, last.getInt(12));
            assertEquals(Math.round(bytes * 8.0), last.getInt(16) & 0xFFFFFFFFL, "one second of audio");
            assertArrayEquals(Arrays.copyOf(initial.array(), 12), Arrays.copyOf(last.array(), 12));
            assertEquals(96000, last.getInt(20));
            assertEquals(1024, encoder.frameSize());
            assertEquals(0, encoder.delay());
            assertEquals(Codec.ALAC, encoder.codec());
        }

        @Test
        @DisplayName("the magic cookie adds the channel layout information for more than two channels")
        void magicCookie() {
            int[] tags = {100 << 16 | 1, 101 << 16 | 2, 113 << 16 | 3, 116 << 16 | 4, 120 << 16 | 5, 124 << 16 | 6,
                    142 << 16 | 7, 127 << 16 | 8};
            for (var channels = 1; channels <= 8; channels++) {
                var encoder = new AlacEncoder(AlacEncoderOptions.builder()
                        .layout(AlacSignals.bitstreamLayout(channels)).build());
                var cookie = encoder.magicCookie();
                var config = encoder.codecConfig().orElseThrow();
                assertArrayEquals(config, Arrays.copyOf(cookie, 24));
                if (channels <= 2) {
                    assertEquals(24, cookie.length);
                    continue;
                }
                assertEquals(48, cookie.length);
                var info = ByteBuffer.wrap(cookie, 24, 24).slice();
                assertEquals(24, info.getInt(0));
                assertEquals("chan", new String(cookie, 28, 4, java.nio.charset.StandardCharsets.US_ASCII));
                assertEquals(0, info.getInt(8));
                assertEquals(tags[channels - 1], info.getInt(12));
                assertEquals(0, info.getInt(16));
                assertEquals(0, info.getInt(20));
                // the decoder accepts the whole cookie as a configuration
                var track = AudioTrack.builder().codec(Codec.ALAC).timeBase(Rational.timeBaseOf(44100))
                        .codecConfig(cookie).sampleRate(44100).build();
                assertEquals(channels, new AlacDecoder(track).layout().channels());
            }
        }
    }

    @Nested
    @DisplayName("channel layouts")
    class Layouts {
        static Stream<Arguments> layouts() {
            return Stream.of(
                    Arguments.of(ChannelLayout.SURROUND_5_1, ChannelLayout.SURROUND_5_1.speakers()),
                    Arguments.of(new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT,
                                    Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT)),
                            ChannelLayout.SURROUND_5_1.speakers()),
                    Arguments.of(ChannelLayout.defaultFor(7), List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT,
                            Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY, Speaker.BACK_CENTER, Speaker.BACK_LEFT,
                            Speaker.BACK_RIGHT)),
                    Arguments.of(ChannelLayout.SURROUND_7_1, List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT,
                            Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY, Speaker.BACK_LEFT, Speaker.BACK_RIGHT,
                            Speaker.FRONT_LEFT_OF_CENTER, Speaker.FRONT_RIGHT_OF_CENTER)),
                    Arguments.of(ChannelLayout.defaultFor(4), List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT,
                            Speaker.FRONT_CENTER, Speaker.BACK_CENTER)),
                    Arguments.of(new ChannelLayout(Collections.nCopies(3, Speaker.UNKNOWN)),
                            List.of(AlacDecoder.BITSTREAM_ORDER[2])),
                    Arguments.of(ChannelLayout.MONO, ChannelLayout.MONO.speakers()),
                    Arguments.of(new ChannelLayout(List.of(Speaker.FRONT_RIGHT, Speaker.FRONT_LEFT)),
                            List.of(Speaker.FRONT_RIGHT, Speaker.FRONT_LEFT)));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("layouts")
        @DisplayName("an input channel decodes as its speaker, or as the free speaker it takes in input order")
        void assignment(ChannelLayout layout, List<Speaker> decodedAs) {
            var channels = layout.channels();
            var samples = new int[channels][3000];
            for (var c = 0; c < channels; c++) {
                // a distinct constant offset identifies each input channel
                var plane = AlacSignals.samples(Shape.MUSIC, 1, 3000, 16, c)[0];
                for (var i = 0; i < 3000; i++) {
                    samples[c][i] = plane[i] / 2 + 1000 * (c + 1);
                }
            }
            var encoder = new AlacEncoder(AlacEncoderOptions.builder().layout(layout).build());
            assertEquals(layout, encoder.layout());
            var packets = encode(encoder, samples);
            var decoded = decode(encoder, packets, decodedAs);
            for (var c = 0; c < channels; c++) {
                assertArrayEquals(samples[c], decoded[c], "input channel " + c + " as " + decodedAs.get(c));
            }
        }

        @Test
        @DisplayName("planar, interleaved and float input of a reordered layout give the same packets")
        void reorderedInputs() {
            var layout = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.FRONT_CENTER,
                    Speaker.LOW_FREQUENCY, Speaker.SIDE_LEFT, Speaker.SIDE_RIGHT, Speaker.BACK_CENTER));
            var channels = layout.channels();
            var frames = 9000;
            var samples = AlacSignals.samples(Shape.MUSIC, channels, frames, 24, 23);
            for (var c = 0; c < channels; c++) {
                for (var i = 0; i < frames; i++) {
                    samples[c][i] = samples[c][i] / 2 + 10_000 * (c + 1);
                }
            }
            var options = AlacEncoderOptions.of(48000, layout, 24);
            var planar = encode(new AlacEncoder(options), samples);
            var interleaved = new ArrayList<Packet>();
            var byFrame = new AlacEncoder(options);
            byFrame.encodeInterleaved(AlacSignals.interleave(samples), 0, frames, interleaved::add);
            byFrame.flush(interleaved::add);
            var floats = new float[channels][frames];
            for (var c = 0; c < channels; c++) {
                for (var i = 0; i < frames; i++) {
                    floats[c][i] = samples[c][i] * Math.scalb(1f, -23);
                }
            }
            var quantized = new ArrayList<Packet>();
            var fromFloat = new AlacEncoder(options);
            fromFloat.encode(new AudioBuffer(layout, 48000, floats, frames), quantized::add);
            fromFloat.flush(quantized::add);
            assertEquals(planar.size(), interleaved.size());
            assertEquals(planar.size(), quantized.size());
            for (var index = 0; index < planar.size(); index++) {
                assertArrayEquals(planar.get(index).data(), interleaved.get(index).data(), "interleaved " + index);
                assertArrayEquals(planar.get(index).data(), quantized.get(index).data(), "float " + index);
            }
            // the side surrounds, absent from ALAC's 7-channel arrangement, take its free back surrounds in input order
            var decoded = decode(byFrame, interleaved, List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT,
                    Speaker.FRONT_CENTER, Speaker.LOW_FREQUENCY, Speaker.BACK_LEFT, Speaker.BACK_RIGHT,
                    Speaker.BACK_CENTER));
            for (var c = 0; c < channels; c++) {
                assertArrayEquals(samples[c], decoded[c], "input channel " + c);
            }
        }
    }

    @Nested
    @DisplayName("float input")
    class FloatInput {
        @ParameterizedTest(name = "{0}-bit")
        @ValueSource(ints = {16, 20, 24})
        @DisplayName("the decoder's float output encodes back to the same integers")
        void inverseOfDecoder(int bits) {
            var samples = AlacSignals.samples(Shape.MUSIC, 2, 9000, bits, bits);
            samples[0][0] = -(1 << (bits - 1));
            samples[1][0] = (1 << (bits - 1)) - 1;
            var scale = Math.scalb(1f, 1 - bits);
            var planes = new float[2][9000];
            for (var c = 0; c < 2; c++) {
                for (var i = 0; i < 9000; i++) {
                    planes[c][i] = samples[c][i] * scale;
                }
            }
            var encoder = new AlacEncoder(AlacEncoderOptions.builder().bitsPerSample(bits).build());
            var packets = new ArrayList<Packet>();
            encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 44100, planes, 9000), packets::add);
            encoder.flush(packets::add);
            var decoded = decode(encoder, packets, ChannelLayout.STEREO.speakers());
            assertArrayEquals(samples[0], decoded[0]);
            assertArrayEquals(samples[1], decoded[1]);
        }

        @ParameterizedTest(name = "{0}-bit")
        @ValueSource(ints = {16, 20, 24, 32})
        @DisplayName("samples outside the full scale clip, NaN becomes zero and ties round up")
        void quantization(int bits) {
            var max = bits == 32 ? Integer.MAX_VALUE : (1 << (bits - 1)) - 1;
            var min = bits == 32 ? Integer.MIN_VALUE : -(1 << (bits - 1));
            float[] input = {1.5f, -2f, Float.NaN, 1f, -1f, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.5f,
                    Math.scalb(1.5f, 1 - Math.min(bits, 24)), Math.scalb(-1.5f, 1 - Math.min(bits, 24))};
            // 1.5 steps of the 24-bit grid: a tie at up to 24 bits, rounded toward positive infinity, exact at 32
            int[] expected = {max, min, 0, max, min, max, min, 1 << (bits - 2), bits == 32 ? 384 : 2,
                    bits == 32 ? -384 : -1};
            var encoder = new AlacEncoder(AlacEncoderOptions.builder().layout(ChannelLayout.MONO).bitsPerSample(bits)
                    .build());
            var packets = new ArrayList<Packet>();
            encoder.encode(new AudioBuffer(ChannelLayout.MONO, 44100, new float[][]{input}, input.length),
                    packets::add);
            encoder.flush(packets::add);
            assertArrayEquals(expected, decode(encoder, packets, ChannelLayout.MONO.speakers())[0]);
        }
    }

    @Nested
    @DisplayName("contract")
    class Contract {
        @Test
        @DisplayName("input of another format, out-of-range samples and input after the flush are rejected")
        void rejected() {
            var encoder = new AlacEncoder();
            var mono = new AudioBuffer(ChannelLayout.MONO, 44100, 16);
            var otherRate = new AudioBuffer(ChannelLayout.STEREO, 48000, 16);
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(mono, packet -> {
            }));
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(otherRate, packet -> {
            }));
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(new int[][]{{0}}, 0, 1, packet -> {
            }));
            assertThrows(IllegalArgumentException.class, () -> encoder.encode(new int[][]{{0}, {40000}}, 0, 1,
                    packet -> {
                    }));
            assertThrows(IllegalArgumentException.class, () -> encoder.encodeInterleaved(new int[]{0, -40000}, 0, 1,
                    packet -> {
                    }));
            assertThrows(IndexOutOfBoundsException.class, () -> encoder.encode(new int[][]{{0}, {0}}, 0, 2, packet -> {
            }));
            assertThrows(IndexOutOfBoundsException.class, () -> encoder.encodeInterleaved(new int[3], 0, 2, packet -> {
            }));
            assertThrows(NullPointerException.class, () -> encoder.encode(new int[][]{{0}, null}, 0, 1, packet -> {
            }));
            var packets = new ArrayList<Packet>();
            encoder.encode(new int[][]{{1, 2}, {3, 4}}, 0, 2, packets::add);
            encoder.flush(packets::add);
            encoder.flush(packets::add);
            assertEquals(1, packets.size());
            assertThrows(IllegalStateException.class, () -> encoder.encode(new int[][]{{0}, {0}}, 0, 1, packet -> {
            }));
            var stereo = new AudioBuffer(ChannelLayout.STEREO, 44100, 16);
            assertThrows(IllegalStateException.class, () -> encoder.encode(stereo, packet -> {
            }));
            var wide = new AlacEncoder(AlacEncoderOptions.builder().bitsPerSample(32).build());
            wide.encode(new int[][]{{Integer.MIN_VALUE}, {Integer.MAX_VALUE}}, 0, 1, packet -> {
            });
        }

        @Test
        @DisplayName("the options validate their ranges and round-trip through their builder")
        void options() {
            var defaults = AlacEncoderOptions.defaults();
            assertEquals(44100, defaults.sampleRate());
            assertEquals(ChannelLayout.STEREO, defaults.layout());
            assertEquals(16, defaults.bitsPerSample());
            assertEquals(AlacEncoderOptions.DEFAULT_FRAME_LENGTH, defaults.frameLength());
            assertFalse(defaults.fast());
            assertTrue(AlacEncoderOptions.forSpeed().fast());
            var custom = AlacEncoderOptions.builder().format(8000, ChannelLayout.MONO).bitsPerSample(20)
                    .frameLength(16).fast(true).build();
            assertEquals(custom, custom.toBuilder().build());
            assertEquals(custom.hashCode(), custom.toBuilder().build().hashCode());
            assertEquals(AlacEncoderOptions.of(8000, ChannelLayout.MONO, 20), custom.toBuilder().frameLength(4096)
                    .fast(false).build());
            for (var bits : new int[]{0, 8, 12, 17, 21, 25, 31, 33}) {
                var builder = AlacEncoderOptions.builder().bitsPerSample(bits);
                assertThrows(IllegalArgumentException.class, builder::build);
            }
            for (var length : new int[]{0, 15, 65537}) {
                var builder = AlacEncoderOptions.builder().frameLength(length);
                assertThrows(IllegalArgumentException.class, builder::build);
            }
            assertThrows(IllegalArgumentException.class, () -> AlacEncoderOptions.builder().sampleRate(0).build());
            assertThrows(IllegalArgumentException.class, () -> AlacEncoderOptions.builder()
                    .layout(ChannelLayout.defaultFor(9)).build());
            assertThrows(NullPointerException.class, () -> AlacEncoderOptions.builder().layout(null));
        }

        @Test
        @DisplayName("the registry creates the encoder, rounding the sample size up to one ALAC codes")
        void registry() {
            assertTrue(AudioEncoders.isSupported(Codec.ALAC));
            int[][] sizes = {{1, 16}, {8, 16}, {16, 16}, {17, 20}, {20, 20}, {21, 24}, {24, 24}, {25, 32}, {32, 32}};
            for (var size : sizes) {
                var options = AudioEncoderOptions.builder().format(48000, ChannelLayout.SURROUND_5_1)
                        .bitsPerSample(size[0]).build();
                try (var encoder = AudioEncoders.create(Codec.ALAC, options)) {
                    var alac = assertInstanceOf(AlacEncoder.class, encoder);
                    assertEquals(size[1], alac.options().bitsPerSample(), "requested " + size[0]);
                    assertEquals(48000, alac.sampleRate());
                    assertEquals(ChannelLayout.SURROUND_5_1, alac.layout());
                    assertFalse(alac.options().fast());
                }
            }
            var realtime = AudioEncoderOptions.forRealtime(44100, ChannelLayout.STEREO);
            try (var encoder = AudioEncoders.create(Codec.ALAC, realtime)) {
                var alac = assertInstanceOf(AlacEncoder.class, encoder);
                assertTrue(alac.options().fast());
                assertEquals(16, alac.options().bitsPerSample());
            }
            assertThrows(IllegalArgumentException.class, () -> AudioEncoders.create(Codec.ALAC,
                    AudioEncoderOptions.of(48000, ChannelLayout.defaultFor(10))));
        }
    }
}
