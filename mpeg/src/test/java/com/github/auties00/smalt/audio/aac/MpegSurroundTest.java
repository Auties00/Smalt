package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsConfig;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsDecoder;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes AAC streams with MPEG Surround side information through {@link AacDecoder}. The resources are short AAC
 * encodes (libxaac's encoder) of the downmixes the ISO/IEC 23003-3 reference encoder made of a synthetic 5.1 signal,
 * with that encoder's spatial frames ({@code mps-*.bs}, length-prefixed blocks: the header, then one frame per block)
 * embedded in their fill elements, and the plain downmix stream ({@code mps-lc5151-core}). The decoder's spatial
 * decoder itself is checked against the reference decoder by {@code MpsOracleTest}; this suite checks the plumbing:
 * the embedded and separate side information reach the same upmix as decoding the downmix and running the spatial
 * decoder over it, and the QMF domain path of spectral band replication agrees with that time domain upmix.
 */
class MpegSurroundTest {
    static byte[] resource(String name) {
        try (var input = MpegSurroundTest.class.getResourceAsStream(name)) {
            if (input == null) {
                throw new IllegalStateException("missing resource " + name);
            }
            return input.readAllBytes();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static AacTestSupport.Stream stream(String name) {
        return AacTestSupport.parseRaw(resource(name + ".raw"),
                new String(resource(name + ".meta"), StandardCharsets.US_ASCII));
    }

    static List<byte[]> blocks(byte[] data) {
        var buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        var blocks = new ArrayList<byte[]>();
        var position = 0;
        while (position + 4 <= data.length) {
            var length = buffer.getInt(position);
            blocks.add(Arrays.copyOfRange(data, position + 4, position + 4 + length));
            position += 4 + length;
        }
        return blocks;
    }

    /** Reads the {@code SpatialSpecificConfig} of a header block (sacTimeAlignFlag, sacHeaderLen, config). */
    static MpsConfig config(byte[] header) {
        var reader = new AacBitReader(header);
        reader.readFlag();
        reader.read(7);
        return MpsConfig.read(reader, 8L * (header.length - 1));
    }

    /** Runs the spatial decoder over a downmix, as the decoder's time domain path does. */
    static float[][] upmix(List<byte[]> blocks, float[][] downmix, int sampleRate) {
        var decoder = new MpsDecoder(config(blocks.getFirst()), sampleRate);
        var frameLength = decoder.frameLength();
        var frames = Math.min(blocks.size() - 1, downmix[0].length / frameLength);
        var input = new float[decoder.inputChannels()][frames * frameLength];
        for (var c = 0; c < input.length; c++) {
            for (var i = 0; i < input[c].length; i++) {
                input[c][i] = downmix[c][i] * 32768.0f;
            }
        }
        var output = new float[decoder.outputChannels()][frames * frameLength];
        for (var frame = 0; frame < frames; frame++) {
            decoder.readFrame(new AacBitReader(blocks.get(frame + 1)));
            decoder.decode(input, frame * frameLength, output, frame * frameLength);
        }
        for (var channel : output) {
            for (var i = 0; i < channel.length; i++) {
                channel[i] /= 32768.0f;
            }
        }
        return output;
    }

    static AacTestSupport.Decoded decode(AacTestSupport.Stream stream, boolean surround) {
        try (var decoder = new AacDecoder(stream.track(), AacDecoderOptions.builder().mpegSurround(surround).build())) {
            return AacTestSupport.decodeAll(decoder, stream.frames());
        }
    }

    static final class Bits {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int accumulator;
        private int count;

        Bits put(int value, int bits) {
            for (var i = bits - 1; i >= 0; i--) {
                accumulator = accumulator << 1 | value >>> i & 1;
                if (++count == 8) {
                    out.write(accumulator);
                    accumulator = 0;
                    count = 0;
                }
            }
            return this;
        }

        byte[] bytes() {
            while (count != 0) {
                put(0, 1);
            }
            return out.toByteArray();
        }
    }

    /** Builds the {@code AudioSpecificConfig} of an MPEG Surround elementary stream around a configuration. */
    static byte[] surroundConfig(boolean embedded, byte[] specificConfig) {
        var bits = new Bits().put(MpsConfig.AOT_MPEG_SURROUND, 5).put(3, 4).put(0, 4).put(embedded ? 1 : 0, 1);
        for (var value : specificConfig) {
            bits.put(value & 0xFF, 8);
        }
        return bits.bytes();
    }

    static AudioTrack surroundTrack(byte[] config) {
        return AudioTrack.builder().id(2).codec(Codec.AAC).timeBase(Rational.timeBaseOf(48000)).codecConfig(config)
                .sampleRate(48000).build();
    }

    static Packet packet(byte[] data) {
        return new Packet(1, data, Packet.NO_TIMESTAMP, Packet.NO_TIMESTAMP, 0, true);
    }

    /** Decodes a downmix stream with the spatial frames of a separate elementary stream, block by block. */
    static AacTestSupport.Decoded decodeSeparate(AacTestSupport.Stream core, byte[] surroundConfig, List<byte[]> units) {
        var tracks = List.of(core.track(), surroundTrack(surroundConfig));
        try (var decoder = new AacDecoder(tracks, AacDecoderOptions.defaults())) {
            var collected = new ArrayList<float[][]>();
            var layout = new ChannelLayout[1];
            for (var block = 0; block < core.frames().size(); block++) {
                decoder.decode(List.of(packet(core.frames().get(block)), packet(units.get(block))), buffer -> {
                    layout[0] = buffer.layout();
                    collected.add(AacTestSupport.copy(buffer));
                });
            }
            var channels = collected.getFirst().length;
            var total = collected.stream().mapToInt(block -> block[0].length).sum();
            var planes = new float[channels][total];
            var position = 0;
            for (var block : collected) {
                for (var c = 0; c < channels; c++) {
                    System.arraycopy(block[c], 0, planes[c], position, block[c].length);
                }
                position += block[0].length;
            }
            return new AacTestSupport.Decoded(planes, decoder.sampleRate(), layout[0]);
        }
    }

    static void assertSameUpmix(float[][] expected, float[][] actual) {
        assertEquals(expected.length, actual.length, "channels");
        for (var c = 0; c < expected.length; c++) {
            var length = expected[c].length;
            assertTrue(actual[c].length >= length, "channel " + c + " length");
            assertArrayEquals(expected[c], Arrays.copyOf(actual[c], length), 0.0f, "channel " + c);
        }
    }

    @Nested
    @DisplayName("embedded side information")
    class Embedded {
        @Test
        @DisplayName("upmixes a mono Low Complexity downmix to 5.1 exactly as the spatial decoder over the downmix")
        void lowComplexity() {
            var muxed = stream("mps-lc5151");
            var surround = decode(muxed, true);
            var downmix = decode(muxed, false);
            assertEquals(ChannelLayout.MONO, downmix.layout());
            assertEquals(ChannelLayout.SURROUND_5_1, surround.layout());
            assertSameUpmix(upmix(blocks(resource("mps-lc5151.bs")), downmix.planes(), 48000), surround.planes());
        }

        @Test
        @DisplayName("decodes the same downmix as the plain stream when MPEG Surround is off")
        void downmixMatchesPlainStream() {
            var plain = decode(stream("mps-lc5151-core"), true);
            var downmix = decode(stream("mps-lc5151"), false);
            assertEquals(ChannelLayout.MONO, plain.layout());
            assertArrayEquals(plain.planes()[0], downmix.planes()[0], 0.0f);
        }

        @Test
        @DisplayName("reports the downmix layout until the first configuration, then the upmix layout")
        void layoutFollowsConfiguration() {
            var muxed = stream("mps-lc5151");
            try (var decoder = new AacDecoder(muxed.track())) {
                assertEquals(ChannelLayout.MONO, decoder.layout());
                decoder.decode(packet(muxed.frames().getFirst()), buffer -> {
                });
                assertEquals(ChannelLayout.SURROUND_5_1, decoder.layout());
            }
        }

        // The streams repeat one spatial frame, so that both upmixes see the same parameters whatever the offset of
        // their slots: the QMF path leads by the 577 samples of the synthesis and analysis filterbanks it skips and
        // agrees with the time path to the aliasing of that filterbank pair.
        @ParameterizedTest(name = "{0}")
        @CsvSource({"mps-he5151-static, 1, 45", "mps-he525-static, 2, 38"})
        @DisplayName("upmixes a spectral band replication downmix in the QMF domain like the time domain upmix")
        void spectralBandReplication(String name, int downmixChannels, double minimumSnr) {
            var muxed = stream(name);
            var surround = decode(muxed, true);
            assertEquals(48000, surround.sampleRate());
            assertEquals(ChannelLayout.SURROUND_5_1, surround.layout());
            var downmix = decode(muxed, false).planes();
            var expected = upmix(blocks(resource(name + ".bs")), Arrays.copyOf(downmix, downmixChannels), 48000);
            var lag = 577;
            var start = 4 * 2048;
            for (var c = 0; c < expected.length; c++) {
                double signal = 0;
                double noise = 0;
                for (var i = start; i + lag < expected[c].length; i++) {
                    var reference = expected[c][i + lag];
                    var difference = reference - surround.planes()[c][i];
                    signal += reference * reference;
                    noise += difference * difference;
                }
                var snr = 10 * Math.log10(signal / noise);
                assertTrue(snr >= minimumSnr, name + " channel " + c + ": " + snr + " dB");
            }
        }

        @Test
        @DisplayName("keeps a mono spectral band replication core mono under a mono MPEG Surround downmix")
        void monoCoreStaysMono() {
            var muxed = stream("mps-he5151-static");
            assertEquals(ChannelLayout.STEREO, decode(muxed, false).layout());
            assertEquals(6, decode(muxed, true).planes().length);
        }
    }

    @Nested
    @DisplayName("separate elementary stream")
    class Separate {
        static byte[] specificConfig() {
            var header = blocks(resource("mps-lc5151.bs")).getFirst();
            return Arrays.copyOfRange(header, 1, header.length);
        }

        static List<byte[]> frames() {
            var blocks = blocks(resource("mps-lc5151.bs"));
            return blocks.subList(1, blocks.size());
        }

        @Test
        @DisplayName("parses the object type 30 configuration")
        void configuration() {
            var stream = MpsConfig.readAudioSpecificConfig(surroundConfig(false, specificConfig()));
            assertFalse(stream.embedded());
            assertEquals(MpsConfig.TREE_5151, stream.config().treeConfig());
            assertEquals(16, stream.config().frameLength());
            assertEquals(1, stream.config().inputChannels());
            assertEquals(6, stream.config().outputChannels());
            assertTrue(MpsConfig.embeddedPayload(surroundConfig(true, specificConfig())));
        }

        @Test
        @DisplayName("upmixes the downmix with the frames of the separate stream like the embedded frames")
        void matchesEmbedded() {
            var separate = decodeSeparate(stream("mps-lc5151-core"), surroundConfig(false, specificConfig()),
                    frames());
            assertEquals(ChannelLayout.SURROUND_5_1, separate.layout());
            assertSameUpmix(decode(stream("mps-lc5151"), true).planes(), separate.planes());
        }

        @Test
        @DisplayName("outputs the downmix when the configuration uses an unsupported tree")
        void unsupportedTree() {
            var reserved = new Bits().put(3, 4).put(15, 7).put(1, 3).put(7, 4).put(0, 32).bytes();
            assertThrows(MediaException.Unsupported.class,
                    () -> MpsConfig.readAudioSpecificConfig(surroundConfig(false, reserved)));
            var core = stream("mps-lc5151-core");
            var decoded = decodeSeparate(core, surroundConfig(false, reserved), frames());
            assertEquals(ChannelLayout.MONO, decoded.layout());
            assertArrayEquals(decode(core, false).planes()[0], decoded.planes()[0], 0.0f);
        }

        @Test
        @DisplayName("outputs and reports the downmix until the first independent frame when it joins a stream")
        void joinsAtIndependentFrame() {
            var core = stream("mps-lc5151-core");
            var units = frames();
            var full = decodeSeparate(core, surroundConfig(false, specificConfig()), units).planes();
            var tracks = List.of(core.track(), surroundTrack(surroundConfig(false, specificConfig())));
            var frameLength = 1024;
            var joined = new float[6][full[0].length];
            try (var decoder = new AacDecoder(tracks, AacDecoderOptions.defaults())) {
                // the reference encoder sets bsIndependencyFlag on every tenth frame, from the first
                for (var block = 3; block < core.frames().size(); block++) {
                    var channels = new int[1];
                    var position = block * frameLength;
                    decoder.decode(List.of(packet(core.frames().get(block)), packet(units.get(block))), buffer -> {
                        channels[0] = buffer.channels();
                        for (var c = 0; c < buffer.channels(); c++) {
                            System.arraycopy(buffer.plane(c), 0, joined[c], position, buffer.frames());
                        }
                    });
                    var upmixed = block >= 10;
                    assertEquals(upmixed ? 6 : 1, channels[0], "block " + block);
                    assertEquals(upmixed ? ChannelLayout.SURROUND_5_1 : ChannelLayout.MONO, decoder.layout(),
                            "block " + block);
                }
            }
            // once the filterbank and decorrelator states settle, the upmix is the one of a decode from the start
            for (var c = 0; c < 6; c++) {
                double signal = 0;
                double noise = 0;
                for (var i = 30 * frameLength; i < 40 * frameLength; i++) {
                    signal += (double) full[c][i] * full[c][i];
                    noise += (double) (full[c][i] - joined[c][i]) * (full[c][i] - joined[c][i]);
                }
                assertTrue(noise == 0 || 10 * Math.log10(signal / noise) >= 120, "channel " + c);
            }
        }

        @Test
        @DisplayName("outputs and reports the downmix when a spatial frame is longer than a block")
        void longerFrame() {
            var specific = specificConfig();
            // bsFrameLength, the 7 bits after the 4-bit sampling frequency index, becomes 31: 32 slots, two blocks
            specific[0] = (byte) (specific[0] & 0xF0 | 31 >> 3);
            specific[1] = (byte) (specific[1] & 0x1F | (31 & 7) << 5);
            assertEquals(32, MpsConfig.readAudioSpecificConfig(surroundConfig(false, specific)).config().frameLength());
            var core = stream("mps-lc5151-core");
            var tracks = List.of(core.track(), surroundTrack(surroundConfig(false, specific)));
            var units = frames();
            try (var decoder = new AacDecoder(tracks, AacDecoderOptions.defaults())) {
                for (var block = 0; block < 4; block++) {
                    var channels = new int[1];
                    decoder.decode(List.of(packet(core.frames().get(block)), packet(units.get(block))),
                            buffer -> channels[0] = buffer.channels());
                    assertEquals(1, channels[0]);
                    assertEquals(ChannelLayout.MONO, decoder.layout());
                }
            }
        }

        @Test
        @DisplayName("rejects a block without the access unit of its spatial frame")
        void missingFrame() {
            var core = stream("mps-lc5151-core");
            var tracks = List.of(core.track(), surroundTrack(surroundConfig(false, specificConfig())));
            try (var decoder = new AacDecoder(tracks, AacDecoderOptions.defaults())) {
                assertThrows(MediaException.Malformed.class,
                        () -> decoder.decode(List.of(packet(core.frames().getFirst())), buffer -> {
                        }));
            }
        }

        @Test
        @DisplayName("ignores the separate stream when MPEG Surround is off")
        void off() {
            var core = stream("mps-lc5151-core");
            var tracks = List.of(core.track(), surroundTrack(surroundConfig(false, specificConfig())));
            var options = AacDecoderOptions.builder().mpegSurround(false).build();
            try (var decoder = new AacDecoder(tracks, options)) {
                assertEquals(ChannelLayout.MONO, decoder.layout());
                assertThrows(IllegalStateException.class,
                        () -> decoder.decode(List.of(packet(core.frames().getFirst())), buffer -> {
                        }));
            }
        }
    }
}
