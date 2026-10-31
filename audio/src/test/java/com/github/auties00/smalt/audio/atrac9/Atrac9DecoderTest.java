package com.github.auties00.smalt.audio.atrac9;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioDecoders;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.Speaker;
import com.github.auties00.smalt.testkit.Tolerance;
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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the ATRAC9 decoder without the reference: the configuration word, the output geometry and layout, the
 * reconstruction of audio coded by the test encoder of {@link Atrac9TestStreams} (which proves the transform, the
 * scale factors and the quantization are read as coded), the agreement of the three output forms, and the handling
 * of corrupt and truncated superframes. {@link Atrac9DifferentialTest} compares the samples with LibAtrac9.
 */
@DisplayName("ATRAC9 decoder")
class Atrac9DecoderTest {
    static AudioTrack track(byte[] config) {
        return AudioTrack.builder().id(1).codec(Codec.ATRAC9).timeBase(Rational.timeBaseOf(48000)).codecConfig(config)
                .build();
    }

    /** Decodes every superframe into one interleaved array, stopping at the first corrupt one. */
    static short[] decode(Atrac9TestStreams.Stream stream) {
        var decoder = new Atrac9Decoder(track(stream.config()));
        var channels = decoder.layout().channels();
        var out = new ArrayList<short[]>();
        for (var superframe : stream.superframes()) {
            decoder.decodeIntegers(packet(superframe), block -> {
                var samples = new short[block.frames() * channels];
                for (var c = 0; c < channels; c++) {
                    var plane = block.plane(c);
                    for (var i = 0; i < block.frames(); i++) {
                        samples[i * channels + c] = (short) plane[i];
                    }
                }
                out.add(samples);
            });
        }
        var total = out.stream().mapToInt(a -> a.length).sum();
        var all = new short[total];
        var at = 0;
        for (var part : out) {
            System.arraycopy(part, 0, all, at, part.length);
            at += part.length;
        }
        return all;
    }

    static Packet packet(byte[] data) {
        return new Packet(1, data, 0, 0, Packet.UNKNOWN_DURATION, true);
    }

    @Nested
    @DisplayName("configuration")
    class Configuration {
        static Stream<Arguments> words() {
            return Stream.of(
                    Arguments.of(0, 0, 1, 0, 11025, 1, 64, 1),
                    Arguments.of(1, 1, 100, 2, 12000, 2, 256, 400),
                    Arguments.of(2, 2, 2048, 0, 16000, 2, 128, 2048),
                    Arguments.of(4, 5, 300, 2, 24000, 4, 512, 1200),
                    Arguments.of(6, 3, 512, 2, 44100, 6, 1024, 2048),
                    Arguments.of(7, 4, 700, 0, 48000, 8, 256, 700),
                    Arguments.of(9, 2, 400, 3, 48000, 2, 512, 3200),
                    Arguments.of(12, 2, 900, 2, 96000, 2, 512, 3600),
                    Arguments.of(15, 0, 256, 1, 192000, 1, 512, 512));
        }

        @ParameterizedTest(name = "rate index {0}, channel configuration {1}")
        @MethodSource("words")
        @DisplayName("reads the rate, channels, superframe size and samples from the word")
        void word(int rateIndex, int channelConfig, int frameBytes, int superframeIndex, int rate, int channels,
                  int samples, int bytes) {
            var decoder = new Atrac9Decoder(track(Atrac9TestStreams.configWord(rateIndex, channelConfig, frameBytes,
                    superframeIndex)));
            assertEquals(rate, decoder.sampleRate());
            assertEquals(channels, decoder.layout().channels());
            assertEquals(samples, decoder.superframeSamples());
            assertEquals(bytes, decoder.superframeBytes());
        }

        @Test
        @DisplayName("takes the word from the 12 bytes of an .at9 format extension")
        void extension() {
            var word = Atrac9TestStreams.configWord(7, 2, 384, 2);
            var extension = new byte[12];
            extension[0] = 2;
            System.arraycopy(word, 0, extension, 4, 4);
            var decoder = new Atrac9Decoder(track(extension));
            assertEquals(48000, decoder.sampleRate());
            assertEquals(1536, decoder.superframeBytes());
        }

        @Test
        @DisplayName("rejects a wrong header byte, a set validation bit, an unknown channel configuration and odd sizes")
        void invalid() {
            var good = Atrac9TestStreams.configWord(7, 2, 384, 2);
            var header = good.clone();
            header[0] = (byte) 0xFF;
            var validation = good.clone();
            validation[1] |= 1;
            var channels = Atrac9TestStreams.configWord(7, 6, 384, 2);
            for (var config : List.of(header, validation, channels, new byte[3], Arrays.copyOf(good, 8))) {
                assertThrows(MediaException.Malformed.class, () -> new Atrac9Decoder(track(config)));
            }
            var none = AudioTrack.builder().id(1).codec(Codec.ATRAC9).timeBase(Rational.timeBaseOf(48000)).build();
            assertThrows(MediaException.Malformed.class, () -> new Atrac9Decoder(none));
            var opus = AudioTrack.builder().id(1).codec(Codec.OPUS).timeBase(Rational.timeBaseOf(48000)).build();
            assertThrows(IllegalArgumentException.class, () -> new Atrac9Decoder(opus));
        }

        @Test
        @DisplayName("lays the channels out in block order, or as the track declares them")
        void layout() {
            assertEquals(ChannelLayout.MONO, new Atrac9Decoder(track(Atrac9TestStreams.configWord(7, 0, 100, 0))).layout());
            assertEquals(ChannelLayout.STEREO, new Atrac9Decoder(track(Atrac9TestStreams.configWord(7, 1, 100, 0))).layout());
            assertEquals(ChannelLayout.SURROUND_5_1,
                    new Atrac9Decoder(track(Atrac9TestStreams.configWord(7, 3, 100, 0))).layout());
            assertEquals(ChannelLayout.SURROUND_7_1,
                    new Atrac9Decoder(track(Atrac9TestStreams.configWord(7, 4, 100, 0))).layout());
            var declared = new ChannelLayout(List.of(Speaker.FRONT_LEFT, Speaker.FRONT_RIGHT, Speaker.SIDE_LEFT,
                    Speaker.SIDE_RIGHT));
            var track = track(Atrac9TestStreams.configWord(7, 5, 100, 0)).toBuilder().layout(declared).build();
            assertEquals(declared, new Atrac9Decoder(track).layout());
            var mismatched = track(Atrac9TestStreams.configWord(7, 0, 100, 0)).toBuilder().layout(declared).build();
            assertEquals(ChannelLayout.MONO, new Atrac9Decoder(mismatched).layout());
        }

        @Test
        @DisplayName("is found through AudioDecoders")
        void registry() {
            assertTrue(AudioDecoders.isSupported(Codec.ATRAC9));
            assertInstanceOf(Atrac9Decoder.class, AudioDecoders.create(track(Atrac9TestStreams.configWord(7, 2, 100, 2))));
        }

        @Test
        @DisplayName("leaves ATRAC1 without a decoder")
        void atrac1() {
            assertFalse(AudioDecoders.isSupported(Codec.ATRAC1));
            var track = AudioTrack.builder().id(1).codec(Codec.ATRAC1).timeBase(Rational.timeBaseOf(44100))
                    .sampleRate(44100).layout(ChannelLayout.STEREO).build();
            assertThrows(MediaException.Unsupported.class, () -> AudioDecoders.create(track));
        }
    }

    static double[][] signal(int channels, int length, int rate, long seed) {
        var random = new Random(seed);
        var samples = new double[channels][length];
        for (var c = 0; c < channels; c++) {
            var f1 = 110.0 * (c + 1) * (1 + random.nextDouble());
            var f2 = Math.min(rate * 0.3, 1500.0 + 900 * c);
            for (var i = 0; i < length; i++) {
                var t = (double) i / rate;
                samples[c][i] = 6000 * Math.sin(2 * Math.PI * f1 * t) + 2500 * Math.sin(2 * Math.PI * f2 * t)
                        + 1200 * Math.sin(2 * Math.PI * (200 + 2000 * t) * t);
            }
        }
        return samples;
    }

    static Stream<Arguments> encodings() {
        var cases = new ArrayList<Arguments>();
        int[] rates = {0, 2, 3, 5, 6, 7, 8, 10, 12, 13, 15};
        for (var r = 0; r < rates.length; r++) {
            cases.add(Arguments.of(rates[r], r % 6, r % 2 == 0 ? 2 : 0, r % 3 == 0));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "rate index {0}, channel configuration {1}, superframe index {2}, delta scale factors {3}")
    @MethodSource("encodings")
    @DisplayName("reconstructs the audio the test encoder coded, one frame later")
    void reconstructs(int rateIndex, int channelConfig, int superframeIndex, boolean vlc) {
        var rate = Atrac9Tables.SAMPLE_RATES[rateIndex];
        var channels = Atrac9Tables.CHANNEL_COUNTS[channelConfig];
        var blocks = Atrac9Tables.CHANNEL_CONFIGS[channelConfig];
        var input = signal(channels, 6000, rate, rateIndex * 31L + channelConfig);
        var lfe = -1;
        var channel = 0;
        for (var type : blocks) {
            if (type == Atrac9Tables.LFE) {
                lfe = channel;
            }
            channel += type == Atrac9Tables.STEREO ? 2 : 1;
        }
        if (lfe >= 0) {
            // the low-frequency effects block codes its lowest four coefficients only
            for (var i = 0; i < input[lfe].length; i++) {
                input[lfe][i] = 5000 * Math.sin(2 * Math.PI * (rate / 1024.0) * i / rate);
            }
        }
        var stream = Atrac9TestStreams.encode(rateIndex, channelConfig, superframeIndex, 2048, input, vlc);
        var config = stream.parsed();
        var output = decode(stream);
        var size = config.frameSamples();
        assertEquals(stream.superframes().size() * config.superframeSamples() * channels, output.length);
        for (var c = 0; c < channels; c++) {
            var expected = new short[input[c].length - 2 * size];
            var actual = new short[expected.length];
            for (var i = 0; i < expected.length; i++) {
                expected[i] = (short) Math.round(input[c][i + size]);
                actual[i] = output[(i + 2 * size) * channels + c];
            }
            var frames = new StringBuilder();
            for (var f = 0; f < Math.min(16, expected.length / size); f++) {
                var signal = 0.0;
                var noise = 0.0;
                for (var i = f * size; i < (f + 1) * size; i++) {
                    signal += (double) expected[i] * expected[i];
                    noise += (double) (expected[i] - actual[i]) * (expected[i] - actual[i]);
                }
                frames.append(String.format(" %.1f", 10 * Math.log10(signal / Math.max(noise, 1e-9))));
            }
            // the low-frequency effects block codes four coefficients at a coarse fixed precision
            Tolerance.snr(c == lfe ? 20 : 30).assertPcm("channel " + c + " (per-frame SNR" + frames + "; gradients "
                    + Atrac9TestStreams.LAST_GRADIENTS.subList(0, Math.min(16, Atrac9TestStreams.LAST_GRADIENTS.size()))
                    + ")", expected, actual);
        }
    }

    @Test
    @DisplayName("outputs the same samples as integers, floats and doubles")
    void outputs() {
        var stream = Atrac9TestStreams.random(7, 2, 2, 6, 11);
        var integers = new Atrac9Decoder(track(stream.config()));
        var floats = new Atrac9Decoder(track(stream.config()));
        var doubles = new Atrac9Decoder(track(stream.config()));
        for (var superframe : stream.superframes()) {
            var ints = new ArrayList<int[]>();
            integers.decodeIntegers(packet(superframe), block -> {
                for (var c = 0; c < 2; c++) {
                    ints.add(Arrays.copyOf(block.plane(c), block.frames()));
                }
            });
            floats.decode(packet(superframe), block -> {
                for (var c = 0; c < 2; c++) {
                    for (var i = 0; i < block.frames(); i++) {
                        assertEquals(ints.get(c)[i] * 0x1p-15f, block.plane(c)[i]);
                    }
                }
            });
            doubles.decodeDoubles(packet(superframe), block -> {
                for (var c = 0; c < 2; c++) {
                    for (var i = 0; i < block.frames(); i++) {
                        assertEquals(ints.get(c)[i] * 0x1p-15, block.plane(c)[i]);
                    }
                }
            });
        }
    }

    @Test
    @DisplayName("conceals a lost span exactly: silence before the first packet, the repeated signal after one")
    void concealment() {
        var rate = Atrac9Tables.SAMPLE_RATES[7];
        var stream = Atrac9TestStreams.encode(7, 0, 2, 2048, signal(1, 4096, rate, 3), false);
        var decoder = new Atrac9Decoder(track(stream.config()));
        assertTrue(decoder.supportsConcealment());
        assertThrows(IllegalArgumentException.class, () -> decoder.conceal(0, block -> {
        }));
        var before = new double[2];
        assertEquals(300, decoder.conceal(300, block -> {
            before[0] += block.frames();
            for (var i = 0; i < block.frames(); i++) {
                before[1] += Math.abs(block.plane(0)[i]);
            }
        }));
        assertEquals(300, before[0]);
        assertEquals(0, before[1]);
        for (var superframe : stream.superframes().subList(0, 3)) {
            decoder.decode(packet(superframe), block -> {
            });
        }
        var after = new double[2];
        assertEquals(480, decoder.conceal(480, block -> {
            after[0] += block.frames();
            for (var i = 0; i < block.frames(); i++) {
                after[1] += Math.abs(block.plane(0)[i]);
            }
        }));
        assertEquals(480, after[0]);
        assertTrue(after[1] > 0, "the span repeats the decoded signal");
        var decoded = new int[1];
        decoder.decode(packet(stream.superframes().get(3)), block -> decoded[0] += block.frames());
        assertEquals(stream.parsed().superframeSamples(), decoded[0]);
    }

    @Test
    @DisplayName("decodes a packet of several superframes as those superframes one by one")
    void severalSuperframes() {
        var stream = Atrac9TestStreams.random(5, 2, 2, 4, 3);
        var expected = decode(stream);
        var joined = new byte[stream.superframes().size() * stream.superframes().getFirst().length];
        for (var i = 0; i < stream.superframes().size(); i++) {
            var superframe = stream.superframes().get(i);
            System.arraycopy(superframe, 0, joined, i * superframe.length, superframe.length);
        }
        var actual = decode(new Atrac9TestStreams.Stream(stream.config(), List.of(joined)));
        assertArrayEquals(expected, actual);
    }

    @Test
    @DisplayName("decodes a truncated superframe as if zeros followed")
    void truncated() {
        var stream = Atrac9TestStreams.random(7, 0, 2, 3, 5);
        var last = stream.superframes().getLast();
        var zeroed = new byte[last.length];
        var cut = Arrays.copyOf(last, last.length / 3);
        System.arraycopy(cut, 0, zeroed, 0, cut.length);
        var head = new ArrayList<>(stream.superframes().subList(0, 2));
        short[] expected;
        try {
            head.add(zeroed);
            expected = decode(new Atrac9TestStreams.Stream(stream.config(), head));
        } catch (MediaException.Malformed exception) {
            head.set(2, cut);
            assertThrows(MediaException.Malformed.class,
                    () -> decode(new Atrac9TestStreams.Stream(stream.config(), head)));
            return;
        }
        head.set(2, cut);
        assertArrayEquals(expected, decode(new Atrac9TestStreams.Stream(stream.config(), head)));
    }

    @Test
    @DisplayName("decodes every syntax-random stream without failing")
    void randomSyntax() {
        for (var seed = 0; seed < 120; seed++) {
            var rateIndex = seed % 16;
            var channelConfig = seed / 16 % 6;
            var stream = Atrac9TestStreams.random(rateIndex, channelConfig, seed % 3 == 0 ? 0 : 2, 4, seed);
            var output = decode(stream);
            assertEquals(4 * stream.parsed().superframeSamples() * stream.parsed().channelCount(), output.length,
                    "seed " + seed);
        }
    }

    @Test
    @DisplayName("raises only Malformed on corrupt superframes")
    void corrupt() {
        var random = new Random(7);
        var malformed = 0;
        for (var round = 0; round < 400; round++) {
            var stream = Atrac9TestStreams.random(round % 16, round % 6, 2, 2, round);
            var decoder = new Atrac9Decoder(track(stream.config()));
            for (var superframe : stream.superframes()) {
                var data = superframe.clone();
                if (round % 2 == 0) {
                    random.nextBytes(data);
                } else {
                    for (var flips = 0; flips < 1 + round % 8; flips++) {
                        data[random.nextInt(data.length)] ^= (byte) (1 << random.nextInt(8));
                    }
                }
                try {
                    decoder.decodeIntegers(packet(data), block -> {
                    });
                } catch (MediaException.Malformed expected) {
                    malformed++;
                } catch (RuntimeException failure) {
                    var trace = new java.io.StringWriter();
                    failure.printStackTrace(new java.io.PrintWriter(trace));
                    throw new AssertionError("round " + round + ": " + trace, failure);
                }
            }
        }
        assertTrue(malformed > 0, "no corrupt superframe was detected");
    }

    @Test
    @DisplayName("ignores an empty packet")
    void empty() {
        var decoder = new Atrac9Decoder(track(Atrac9TestStreams.configWord(7, 2, 100, 2)));
        decoder.decodeIntegers(packet(new byte[0]), block -> {
            throw new AssertionError("output for an empty packet");
        });
    }
}
