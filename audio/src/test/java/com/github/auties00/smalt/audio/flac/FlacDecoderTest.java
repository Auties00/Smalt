package com.github.auties00.smalt.audio.flac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.AudioDecoderOptions;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.flac.FlacFrameBuilder.Predictor;
import com.github.auties00.smalt.audio.flac.FlacFrameBuilder.Rice;
import com.github.auties00.smalt.audio.flac.FlacFrameBuilder.Subframe;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.SplittableRandom;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Drives every syntax element of the FLAC frame format through {@link FlacDecoder} with frames
 * assembled by {@link FlacFrameBuilder}, whose samples are known exactly, and checks the error
 * paths with targeted corruptions.
 */
class FlacDecoderTest {
    static AudioTrack track(byte[] config) {
        return AudioTrack.builder().codec(Codec.FLAC).timeBase(Rational.timeBaseOf(44100)).codecConfig(config)
                .sampleRate(44100)
                .bitsPerSample(16)
                .build();
    }

    static Packet packet(byte[] frame) {
        return new Packet(0, frame, 0, 0, 0, true);
    }

    /** A smooth, noisy signal that fits {@code bits} bits (scaled to use most of the range). */
    static long[] signal(long seed, int length, int bits, double frequency) {
        var random = new SplittableRandom(seed);
        var peak = Math.scalb(1.0, bits - 1) * 0.9;
        var noise = Math.max(1, (long) (peak / 64));
        var result = new long[length];
        var max = (1L << (bits - 1)) - 1;
        var min = -(1L << (bits - 1));
        for (var i = 0; i < length; i++) {
            var value = (long) (peak * Math.sin(i * frequency)) + random.nextLong(-noise, noise + 1);
            result[i] = Math.clamp(value, min, max);
        }
        return result;
    }

    /** Decodes one frame and returns the integer samples of its channels. */
    static int[][] decode(FlacDecoder decoder, byte[] frame, List<AudioBuffer> blocks) {
        decoder.decode(packet(frame), buffer -> {
            var copy = new float[buffer.channels()][];
            for (var c = 0; c < buffer.channels(); c++) {
                copy[c] = Arrays.copyOf(buffer.plane(c), buffer.frames());
            }
            blocks.add(new AudioBuffer(buffer.layout(), buffer.sampleRate(), copy, buffer.frames()));
        });
        var result = new int[decoder.lastChannels()][];
        for (var c = 0; c < result.length; c++) {
            result[c] = Arrays.copyOf(decoder.lastSamples()[c], decoder.lastBlockSize());
        }
        return result;
    }

    static void assertSamples(long[][] expected, int[][] actual, int blockSize) {
        assertEquals(expected.length, actual.length, "channel count");
        for (var c = 0; c < expected.length; c++) {
            for (var i = 0; i < blockSize; i++) {
                if ((int) expected[c][i] != actual[c][i]) {
                    fail("channel " + c + " sample " + i + ": expected " + expected[c][i] + " got " + actual[c][i]);
                }
            }
        }
    }

    static byte[] roundTrip(int bits, int assignment, long[][] channels, int blockSize, Subframe... subframes) {
        var frame = FlacFrameBuilder.frame(false, 0, 44100, codable(bits), bits, assignment, channels, blockSize, subframes);
        var config = FlacFrameBuilder.config(16, 65535, 44100, channels.length, bits, 0);
        var decoder = new FlacDecoder(track(config));
        var blocks = new ArrayList<AudioBuffer>();
        var actual = decode(decoder, frame, blocks);
        assertSamples(channels, actual, blockSize);
        var scale = Math.scalb(1f, 1 - bits);
        var block = blocks.getFirst();
        assertEquals(blockSize, block.frames());
        for (var c = 0; c < channels.length; c++) {
            for (var i = 0; i < blockSize; i++) {
                assertEquals((int) channels[c][i] * scale, block.plane(c)[i], "float sample");
            }
        }
        return frame;
    }

    static Stream<Arguments> predictorsAndDepths() {
        var predictors = List.of(Predictor.constant(), Predictor.verbatim(), Predictor.fixed(0), Predictor.fixed(1),
                Predictor.fixed(2), Predictor.fixed(3), Predictor.fixed(4), Predictor.lpc(12, 10, 1900, -900),
                Predictor.lpc(15, 13, 16000, -12000, 4000), Predictor.lpc(4, 2, 7, -3));
        var depths = List.of(4, 8, 12, 16, 20, 24, 28, 31, 32);
        var result = new ArrayList<Arguments>();
        for (var predictor : predictors) {
            for (var bits : depths) {
                result.add(Arguments.of(predictor, bits));
            }
        }
        return result.stream();
    }

    @Nested
    @DisplayName("subframes")
    class Subframes {
        @ParameterizedTest(name = "{0} at {1} bits")
        @MethodSource("com.github.auties00.smalt.audio.flac.FlacDecoderTest#predictorsAndDepths")
        @DisplayName("decode every predictor at every sample size exactly")
        void predictors(Predictor predictor, int bits) {
            var blockSize = 1152;
            var samples = predictor.kind() == 0
                    ? new long[][]{filled(blockSize, -(1L << (bits - 1)) + 1)}
                    : new long[][]{signal(bits, blockSize, bits, 0.01)};
            roundTrip(bits, 0, samples, blockSize, new Subframe(predictor, 0, Rice.auto(bits > 16 ? 1 : 0, 2)));
        }

        @ParameterizedTest(name = "order {0}")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 16, 17, 24, 31, 32})
        @DisplayName("decode linear predictors of every order, at 16 and 24 bits")
        void lpcOrders(int order) {
            var random = new SplittableRandom(order);
            for (var bits : new int[]{16, 24}) {
                var coefficients = new int[order];
                coefficients[0] = 1 << 12;
                for (var j = 1; j < order; j++) {
                    coefficients[j] = random.nextInt(-200, 200);
                }
                var samples = new long[][]{signal(order * 31L + bits, 4096, bits, 0.003)};
                roundTrip(bits, 0, samples, 4096, new Subframe(Predictor.lpc(15, 12, coefficients), 0, Rice.auto(1, 4)));
            }
        }

        @ParameterizedTest(name = "{0} wasted bits")
        @ValueSource(ints = {1, 2, 5, 8, 15, 23})
        @DisplayName("restore wasted bits for every predictor kind")
        void wastedBits(int wasted) {
            var bits = 24;
            var blockSize = 576;
            for (var predictor : List.of(Predictor.verbatim(), Predictor.fixed(2), Predictor.lpc(10, 8, 250, -100))) {
                var raw = signal(wasted, blockSize, bits - wasted, 0.02);
                var samples = new long[blockSize];
                for (var i = 0; i < blockSize; i++) {
                    samples[i] = raw[i] << wasted;
                }
                roundTrip(bits, 0, new long[][]{samples}, blockSize, new Subframe(predictor, wasted, Rice.auto(0, 0)));
            }
            var constant = filled(blockSize, -1L << wasted);
            roundTrip(bits, 0, new long[][]{constant}, blockSize, new Subframe(Predictor.constant(), wasted, Rice.auto(0, 0)));
        }
    }

    @Nested
    @DisplayName("stereo")
    class Stereo {
        @ParameterizedTest(name = "assignment {0}")
        @ValueSource(ints = {1, 8, 9, 10})
        @DisplayName("undo every decorrelation at 8 to 32 bits, including the 33-bit side of 32-bit audio")
        void decorrelation(int assignment) {
            for (var bits : new int[]{8, 16, 24, 30, 31, 32}) {
                var blockSize = 1024;
                var left = signal(bits, blockSize, bits, 0.01);
                var right = new long[blockSize];
                for (var i = 0; i < blockSize; i++) {
                    right[i] = Math.clamp(-left[i] + (i % 7) - 3, -(1L << (bits - 1)), (1L << (bits - 1)) - 1);
                }
                for (var predictor : List.of(Predictor.verbatim(), Predictor.fixed(1), Predictor.fixed(4),
                        Predictor.lpc(14, 12, 8000, -4000))) {
                    var subframe = new Subframe(predictor, 0, Rice.auto(1, 3));
                    roundTrip(bits, assignment, new long[][]{left, right}, blockSize, subframe, subframe);
                }
                var constant = new Subframe(Predictor.constant(), 0, Rice.auto(0, 0));
                roundTrip(bits, assignment, new long[][]{filled(blockSize, (1L << (bits - 1)) - 1),
                        filled(blockSize, -(1L << (bits - 1)))}, blockSize, constant, constant);
            }
        }

        @Test
        @DisplayName("restore wasted bits on a 33-bit side channel")
        void wideWasted() {
            var blockSize = 256;
            var left = new long[blockSize];
            var right = new long[blockSize];
            for (var i = 0; i < blockSize; i++) {
                left[i] = ((long) (Math.sin(i * 0.05) * (1L << 23))) << 8;
                right[i] = -left[i];
            }
            var subframe = new Subframe(Predictor.fixed(2), 8, Rice.auto(1, 0));
            for (var assignment : new int[]{8, 9, 10}) {
                roundTrip(32, assignment, new long[][]{left, right}, blockSize, subframe, subframe);
            }
        }
    }

    @Nested
    @DisplayName("residual coding")
    class Residuals {
        @ParameterizedTest(name = "escape width {0}")
        @ValueSource(ints = {0, 1, 2, 7, 16, 24, 31})
        @DisplayName("decode escaped partitions of every width with both parameter sizes")
        void escapes(int width) {
            var blockSize = 4096;
            var random = new SplittableRandom(width);
            var samples = new long[blockSize];
            var bound = width == 0 ? 0 : 1L << (width - 1);
            for (var i = 0; i < blockSize; i++) {
                samples[i] = width == 0 ? 0 : random.nextLong(-bound, bound);
            }
            for (var method : new int[]{0, 1}) {
                roundTrip(32, 0, new long[][]{samples}, blockSize,
                        new Subframe(Predictor.fixed(0), 0, new Rice(method, 4, -1, width)));
            }
        }

        @ParameterizedTest(name = "partition order {0}")
        @ValueSource(ints = {0, 1, 2, 3, 4, 5, 6, 7, 8, 12, 15})
        @DisplayName("decode every partition order, up to one sample per partition")
        void partitionOrders(int order) {
            var blockSize = 32768;
            var samples = new long[][]{signal(order, blockSize, 16, 0.001)};
            roundTrip(16, 0, samples, blockSize, new Subframe(Predictor.fixed(1), 0, Rice.auto(0, order)));
        }

        @ParameterizedTest(name = "parameter {0}")
        @ValueSource(ints = {0, 1, 3, 7, 13, 14, 15, 20, 29, 30})
        @DisplayName("decode Rice parameters up to 14 with 4-bit and up to 30 with 5-bit coding")
        void parameters(int parameter) {
            var blockSize = 2048;
            var random = new SplittableRandom(parameter);
            var bound = Math.min(1L << 30, 1L << (parameter + 2));
            var samples = new long[blockSize];
            for (var i = 0; i < blockSize; i++) {
                samples[i] = random.nextLong(-bound, bound);
            }
            var method = parameter > 14 ? 1 : 0;
            roundTrip(32, 0, new long[][]{samples}, blockSize,
                    new Subframe(Predictor.fixed(0), 0, new Rice(method, 0, parameter, -1)));
        }

        @Test
        @DisplayName("decode long unary runs that cross the bit cache")
        void longUnary() {
            var blockSize = 64;
            var samples = new long[blockSize];
            for (var i = 0; i < blockSize; i++) {
                samples[i] = (i % 2 == 0 ? 1 : -1) * (i * 37L);
            }
            roundTrip(16, 0, new long[][]{samples}, blockSize, new Subframe(Predictor.fixed(0), 0, new Rice(0, 0, 0, -1)));
        }
    }

    @Nested
    @DisplayName("frame layout")
    class Layout {
        @ParameterizedTest(name = "block size {0}")
        @ValueSource(ints = {1, 15, 16, 17, 192, 255, 256, 257, 4608, 16384, 32768, 65535})
        @DisplayName("decode every block size coding")
        void blockSizes(int blockSize) {
            roundTrip(16, 0, new long[][]{signal(blockSize, blockSize, 16, 0.02)}, blockSize,
                    new Subframe(blockSize >= 4 ? Predictor.fixed(2) : Predictor.verbatim(), 0, Rice.auto(0, 0)));
        }

        @ParameterizedTest(name = "{0} channels")
        @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
        @DisplayName("decode 1 to 8 independent channels with the WAVE-order default layout")
        void channels(int channels) {
            var blockSize = 512;
            var samples = new long[channels][];
            var subframes = new Subframe[channels];
            for (var c = 0; c < channels; c++) {
                samples[c] = signal(c, blockSize, 16, 0.01 * (c + 1));
                subframes[c] = new Subframe(Predictor.fixed(c % 5), 0, Rice.auto(0, 1));
            }
            var frame = FlacFrameBuilder.frame(false, 0, 48000, 16, 16, channels - 1, samples, blockSize, subframes);
            var decoder = new FlacDecoder(track(FlacFrameBuilder.config(512, 512, 48000, channels, 16, 0)));
            var blocks = new ArrayList<AudioBuffer>();
            assertSamples(samples, decode(decoder, frame, blocks), blockSize);
            assertEquals(ChannelLayout.defaultFor(channels), blocks.getFirst().layout());
            assertEquals(48000, blocks.getFirst().sampleRate());
        }

        @ParameterizedTest(name = "{0} Hz")
        @ValueSource(ints = {8000, 16000, 22050, 24000, 32000, 44100, 48000, 88200, 96000, 176400, 192000, 37800,
                50001, 64000, 655350, 1000, 255000})
        @DisplayName("decode every sample rate coding")
        void sampleRates(int rate) {
            var blockSize = 192;
            var samples = new long[][]{signal(rate, blockSize, 16, 0.02)};
            var frame = FlacFrameBuilder.frame(false, 0, rate, 16, 16, 0, samples, blockSize,
                    Subframe.of(Predictor.verbatim()));
            var decoder = new FlacDecoder(track(FlacFrameBuilder.config(192, 192, rate, 1, 16, 0)));
            var blocks = new ArrayList<AudioBuffer>();
            decode(decoder, frame, blocks);
            assertEquals(rate, blocks.getFirst().sampleRate());
            assertEquals(rate, decoder.sampleRate());
        }

        @Test
        @DisplayName("take deferred sample rate and size from STREAMINFO, including sizes the header cannot code")
        void deferred() {
            for (var bits : new int[]{4, 7, 15, 17, 23, 29, 31}) {
                var blockSize = 300;
                var samples = new long[][]{signal(bits, blockSize, bits, 0.03)};
                var frame = FlacFrameBuilder.frame(false, 0, 0, 0, bits, 0, samples, blockSize,
                        new Subframe(Predictor.fixed(2), 0, Rice.auto(1, 0)));
                var decoder = new FlacDecoder(track(FlacFrameBuilder.config(300, 300, 12345, 1, bits, 0)));
                var blocks = new ArrayList<AudioBuffer>();
                assertSamples(samples, decode(decoder, frame, blocks), blockSize);
                assertEquals(12345, blocks.getFirst().sampleRate());
            }
        }

        @Test
        @DisplayName("accept the three codec configuration forms and none at all")
        void configurations() {
            var samples = new long[][]{signal(9, 256, 16, 0.02)};
            var frame = FlacFrameBuilder.frame(false, 0, 44100, 16, 16, 0, samples, 256, Subframe.of(Predictor.fixed(1)));
            var full = FlacFrameBuilder.config(256, 256, 44100, 1, 16, 0);
            var block = Arrays.copyOfRange(full, 4, full.length);
            var body = Arrays.copyOfRange(full, 8, full.length);
            for (var config : new byte[][]{full, block, body, null}) {
                var decoder = new FlacDecoder(track(config));
                assertSamples(samples, decode(decoder, frame, new ArrayList<>()), 256);
            }
            assertThrows(MediaException.Malformed.class, () -> new FlacDecoder(track(new byte[]{1, 2, 3})));
            var wrongType = full.clone();
            wrongType[4] = (byte) 0x84;
            assertThrows(MediaException.Malformed.class, () -> new FlacDecoder(track(wrongType)));
            var notFlac = AudioTrack.builder().codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48000)).sampleRate(48000)
                    .build();
            assertThrows(IllegalArgumentException.class, () -> new FlacDecoder(notFlac));
        }

        @Test
        @DisplayName("report format changes after the first frame through each block")
        void formatChanges() {
            var decoder = new FlacDecoder(track(FlacFrameBuilder.config(16, 4096, 44100, 1, 16, 0)));
            var blocks = new ArrayList<AudioBuffer>();
            var mono = new long[][]{signal(1, 1024, 16, 0.01)};
            decode(decoder, FlacFrameBuilder.frame(false, 0, 44100, 16, 16, 0, mono, 1024, Subframe.of(Predictor.fixed(2))), blocks);
            var stereo = new long[][]{signal(2, 1024, 24, 0.01), signal(3, 1024, 24, 0.02)};
            var sub = Subframe.of(Predictor.fixed(2));
            decode(decoder, FlacFrameBuilder.frame(false, 1, 96000, 24, 24, 1, stereo, 1024, sub, sub), blocks);
            assertEquals(1, blocks.get(0).channels());
            assertEquals(44100, blocks.get(0).sampleRate());
            assertEquals(2, blocks.get(1).channels());
            assertEquals(96000, blocks.get(1).sampleRate());
            assertEquals(44100, decoder.sampleRate());
            assertEquals(ChannelLayout.MONO, decoder.layout());
            assertEquals(stereo[1][5] * Math.scalb(1f, -23), blocks.get(1).plane(1)[5]);
        }
    }

    @Nested
    @DisplayName("errors")
    class Errors {
        static byte[] validFrame() {
            return FlacFrameBuilder.frame(false, 0, 44100, 16, 16, 0, new long[][]{signal(5, 256, 16, 0.02)}, 256,
                    Subframe.of(Predictor.fixed(2)));
        }

        static byte[] withCrc(byte[] frame) {
            var crc = com.github.auties00.smalt.internal.header.Crc16.crc16(0, frame, 0, frame.length - 2);
            frame[frame.length - 2] = (byte) (crc >>> 8);
            frame[frame.length - 1] = (byte) crc;
            return frame;
        }

        static FlacDecoder decoder() {
            return new FlacDecoder(track(FlacFrameBuilder.config(16, 4096, 44100, 1, 16, 0)));
        }

        @Test
        @DisplayName("reject a CRC-16 mismatch, an invalid header, truncation and an empty packet")
        void framing() {
            var frame = validFrame();
            var badCrc = frame.clone();
            badCrc[badCrc.length - 1] ^= 1;
            assertThrows(MediaException.Malformed.class, () -> decoder().decode(packet(badCrc), buffer -> {
            }));
            var badHeader = frame.clone();
            badHeader[0] = 0;
            assertThrows(MediaException.Malformed.class, () -> decoder().decode(packet(badHeader), buffer -> {
            }));
            for (var length : new int[]{0, 3, 6, 7, frame.length / 2, frame.length - 1}) {
                var truncated = Arrays.copyOf(frame, length);
                assertThrows(MediaException.Malformed.class, () -> decoder().decode(packet(truncated), buffer -> {
                }), "truncated to " + length);
                if (length > 8) {
                    var fixed = withCrc(truncated.clone());
                    assertThrows(MediaException.Malformed.class, () -> decoder().decode(packet(fixed), buffer -> {
                    }), "truncated with valid CRC to " + length);
                }
            }
        }

        @ParameterizedTest(name = "subframe type {0}")
        @ValueSource(ints = {2, 3, 4, 7, 13, 15, 16, 31})
        @DisplayName("reject reserved subframe types")
        void reservedTypes(int type) {
            var frame = validFrame();
            var headerSize = 6;
            frame[headerSize] = (byte) ((frame[headerSize] & 0x81) | type << 1);
            assertThrows(MediaException.Malformed.class, () -> decoder().decode(packet(withCrc(frame)), buffer -> {
            }));
        }

        @Test
        @DisplayName("reject a set padding bit, a reserved precision, a negative shift and a bad partitioning")
        void subframeFields() {
            var frame = validFrame();
            frame[6] |= (byte) 0x80;
            assertThrows(MediaException.Malformed.class, () -> decoder().decode(packet(withCrc(frame)), buffer -> {
            }));
            var samples = new long[][]{signal(4, 256, 16, 0.02)};
            var lpc = FlacFrameBuilder.frame(false, 0, 44100, 16, 16, 0, samples, 256,
                    Subframe.of(Predictor.lpc(12, 10, 1000, -500)));
            // subframe header (8 bits) + 2 warm-up samples (32 bits) precede the 4-bit precision field
            var precisionByte = 6 + 1 + 4;
            var reservedPrecision = lpc.clone();
            reservedPrecision[precisionByte] |= (byte) 0xF0;
            assertThrows(MediaException.Malformed.class, () -> decoder().decode(packet(withCrc(reservedPrecision)), buffer -> {
            }));
            var negativeShift = lpc.clone();
            negativeShift[precisionByte] |= 0x08;
            assertThrows(MediaException.Malformed.class, () -> decoder().decode(packet(withCrc(negativeShift)), buffer -> {
            }));
            var odd = FlacFrameBuilder.frame(false, 0, 44100, 16, 16, 0, new long[][]{signal(4, 255, 16, 0.02)}, 255,
                    new Subframe(Predictor.fixed(1), 0, Rice.auto(0, 0)));
            // the 7-byte header is followed by the subframe header byte and the 16-bit warm-up sample; byte 10
            // starts with the 2-bit method and the 4-bit partition order: order 1 cannot split 255 samples
            odd[10] |= 0x04;
            assertThrows(MediaException.Malformed.class, () -> new FlacDecoder(track(FlacFrameBuilder.config(16, 4096,
                    44100, 1, 16, 0))).decode(packet(withCrc(odd)), buffer -> {
            }));
        }

        @Test
        @DisplayName("reject an order above the block size and all-wasted samples")
        void inconsistentSubframes() {
            var three = FlacFrameBuilder.frame(false, 0, 44100, 16, 16, 0, new long[][]{signal(4, 3, 16, 0.02)}, 3,
                    Subframe.of(Predictor.verbatim()));
            // byte 7 follows the 7-byte header: swap the verbatim type for fixed order 4 in a 3-sample block
            three[7] = (byte) ((three[7] & 0x81) | 12 << 1);
            assertThrows(MediaException.Malformed.class, () -> decoder().decode(packet(withCrc(three)), buffer -> {
            }));
            var wasted = FlacFrameBuilder.frame(false, 0, 44100, 16, 16, 0, new long[][]{filled(16, 0)}, 16,
                    new Subframe(Predictor.constant(), 0, Rice.auto(0, 0)));
            // set the wasted flag of the subframe after the 7-byte header: the zero constant that follows makes
            // the unary count at least sixteen, so every one of the sixteen bits would be wasted
            var bytes = Arrays.copyOf(wasted, wasted.length + 2);
            bytes[7] |= 1;
            assertThrows(MediaException.Malformed.class, () -> decoder().decode(packet(withCrc(bytes)), buffer -> {
            }));
        }

        @Test
        @DisplayName("reject frames that disagree with STREAMINFO or defer to an absent one")
        void streamInfo() {
            var frame = validFrame();
            var stereoInfo = new FlacDecoder(track(FlacFrameBuilder.config(16, 4096, 44100, 2, 16, 0)));
            assertThrows(MediaException.Malformed.class, () -> stereoInfo.decode(packet(frame), buffer -> {
            }));
            var smallBlocks = new FlacDecoder(track(FlacFrameBuilder.config(16, 128, 44100, 1, 16, 0)));
            assertThrows(MediaException.Malformed.class, () -> smallBlocks.decode(packet(frame), buffer -> {
            }));
            var deferred = FlacFrameBuilder.frame(false, 0, 0, 16, 16, 0, new long[][]{signal(5, 256, 16, 0.02)}, 256,
                    Subframe.of(Predictor.fixed(2)));
            assertThrows(MediaException.Malformed.class, () -> new FlacDecoder(track(null)).decode(packet(deferred), buffer -> {
            }));
        }
    }

    /** Returns the sample size to write in a frame header, zero when only STREAMINFO can carry it. */
    static int codable(int bits) {
        return switch (bits) {
            case 8, 12, 16, 20, 24, 32 -> bits;
            default -> 0;
        };
    }

    static long[] filled(int length, long value) {
        var result = new long[length];
        Arrays.fill(result, value);
        return result;
    }

    @Test
    @DisplayName("flush emits nothing and closing is harmless")
    void flush() {
        var decoder = new FlacDecoder(track(null));
        decoder.flush(buffer -> fail("unexpected block"));
        decoder.close();
        assertEquals(44100, decoder.sampleRate());
    }

    @Nested
    @DisplayName("codec-independent options")
    class IndependentOptions {
        @Test
        @DisplayName("apply their limits and ignore the preferred sample rate")
        void limits() {
            var stereo = track(FlacFrameBuilder.config(4096, 4096, 44100, 2, 16, 0));
            var decoder = new FlacDecoder(stereo, AudioDecoderOptions.builder().preferredSampleRate(8000).build());
            assertEquals(2, decoder.layout().channels());
            assertEquals(44100, decoder.sampleRate());
            assertThrows(MediaException.LimitExceeded.class, () -> new FlacDecoder(stereo,
                    AudioDecoderOptions.builder().limits(MediaLimits.builder().maxChannels(1).build()).build()));
            assertThrows(MediaException.LimitExceeded.class, () -> new FlacDecoder(stereo,
                    AudioDecoderOptions.builder().limits(MediaLimits.builder().maxBytes(1024).build()).build()));
            assertThrows(NullPointerException.class, () -> new FlacDecoder(stereo, (AudioDecoderOptions) null));
        }
    }
}
