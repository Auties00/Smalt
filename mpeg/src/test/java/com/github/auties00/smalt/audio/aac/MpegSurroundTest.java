package com.github.auties00.smalt.audio.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.AudioBuffer;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.audio.aac.internal.AacBitReader;
import com.github.auties00.smalt.audio.aac.internal.OutputChannelPositions;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsBinaural;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsConfig;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsDecoder;
import com.github.auties00.smalt.audio.aac.internal.mps.MpsUpmixer;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decodes AAC streams with MPEG Surround side information through {@link AacDecoder}. The resources are short AAC
 * encodes (libxaac's encoder) of the downmixes the ISO/IEC 23003-3 reference encoder made of a synthetic 5.1 signal,
 * with that encoder's spatial frames ({@code mps-*.bs}, length-prefixed blocks: the header, then one frame per block)
 * embedded in their fill elements, the plain downmix stream ({@code mps-lc5151-core}), and three synthetic streams of
 * {@code tools/testdata/mps-oracle/mpsgen.py}: frames of two AAC blocks ({@code mps-long5151.bs}), frames of half a
 * block ({@code mps-short5151.bs}), an arbitrary tree that splits the surround channels into side and rear pairs
 * ({@code mps-at71.bs}) and residual frames of 4096 QMF samples at 96 kHz ({@code mps-res96.bs}), which the tests
 * embed in the
 * fill elements of the plain downmix themselves or pass as a separate stream. The decoder's spatial decoder itself is
 * checked against the reference decoder by {@code MpegSurroundOracleTest}; this suite checks the plumbing: the
 * embedded and separate side information reach the same upmix as decoding the downmix and running the spatial decoder
 * over it, frames longer than a block are output a block late, and the QMF domain path of spectral band replication
 * agrees with that time domain upmix.
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
        var escaped = reader.read(7) == 127;
        if (escaped) {
            reader.read(16);
        }
        return MpsConfig.read(reader, 8L * (header.length - (escaped ? 3 : 1)));
    }

    /** Runs the spatial decoder over a downmix, as the decoder's time domain path does. */
    static float[][] upmix(List<byte[]> blocks, float[][] downmix, int sampleRate) {
        return upmix(blocks, downmix, sampleRate, null);
    }

    static float[][] upmix(List<byte[]> blocks, float[][] downmix, int sampleRate, MpsBinaural binaural) {
        var decoder = new MpsDecoder(config(blocks.getFirst()), sampleRate, binaural);
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

    /**
     * Splits the payload of a spatial frame into the data of {@code pieces} {@code sac_extension_data()} fragments:
     * the header block, when given, and the first part of the frame in the first, the rest of the frame spread over
     * the others. The header's {@code sacHeaderLen} is set to the length of its configuration, which the reference
     * encoder's header blocks understate by the bytes of their LFE band counts.
     */
    static List<byte[]> payloadFragments(byte[] header, byte[] frame, int pieces) {
        var fragments = new ArrayList<byte[]>();
        for (var b = 0; b < pieces; b++) {
            var from = frame.length * b / pieces;
            var to = frame.length * (b + 1) / pieces;
            var part = Arrays.copyOfRange(frame, from, to);
            if (b == 0 && header != null) {
                var fixed = header.clone();
                if ((header[0] & 0x7F) != 127) {
                    assertTrue(header.length - 1 < 127, "header of " + header.length + " bytes");
                    fixed[0] = (byte) (header[0] & 0x80 | header.length - 1);
                }
                part = Arrays.copyOf(fixed, fixed.length + part.length);
                System.arraycopy(frame, from, part, fixed.length, to - from);
            }
            fragments.add(part);
        }
        return fragments;
    }

    /**
     * Builds an AAC stream whose raw data blocks start with fill elements carrying spatial frames: frame {@code f}
     * spread over blocks {@code f * blocksPerFrame} to {@code (f + 1) * blocksPerFrame - 1}, the first frame and every
     * seventh after it starting with the header.
     */
    static AacTestSupport.Stream embed(AacTestSupport.Stream core, List<byte[]> spatial, int blocksPerFrame) {
        var header = spatial.getFirst();
        var frames = Math.min(spatial.size() - 1, core.frames().size() / blocksPerFrame);
        var blocks = new ArrayList<byte[]>();
        for (var f = 0; f < frames; f++) {
            var withHeader = f % 7 == 0;
            var parts = payloadFragments(withHeader ? header : null, spatial.get(f + 1), blocksPerFrame);
            for (var b = 0; b < blocksPerFrame; b++) {
                var part = parts.get(b);
                var count = 1 + part.length;
                assertTrue(count <= 15 + 255 - 1, "fragment of " + count + " bytes");
                var bits = new Bits().put(6, 3);
                if (count < 15) {
                    bits.put(count, 4);
                } else {
                    bits.put(15, 4).put(count - 14, 8);
                }
                bits.put(MpsUpmixer.EXT_SAC_DATA, 4).put(withHeader ? 1 : 0, 2).put(b == 0 ? 1 : 0, 1)
                        .put(b == blocksPerFrame - 1 ? 1 : 0, 1);
                for (var value : part) {
                    bits.put(value & 0xFF, 8);
                }
                for (var value : core.frames().get(f * blocksPerFrame + b)) {
                    bits.put(value & 0xFF, 8);
                }
                blocks.add(bits.bytes());
            }
        }
        return new AacTestSupport.Stream(core.config(), blocks, core.sampleRate(), core.channels());
    }

    /**
     * Decodes the blocks of a stream one by one, each of a separate stream with the access unit of its own spatial
     * frame every {@code blocksPerFrame} blocks and none (or an empty one) in between.
     */
    static List<AacTestSupport.Decoded> decodeBlocks(AacTestSupport.Stream core, byte[] surroundConfig,
                                                     List<byte[]> units, int blocksPerFrame, boolean emptyUnits) {
        var tracks = surroundConfig == null ? List.of(core.track())
                : List.of(core.track(), surroundTrack(surroundConfig));
        var decoded = new ArrayList<AacTestSupport.Decoded>();
        try (var decoder = new AacDecoder(tracks, AacDecoderOptions.defaults())) {
            for (var block = 0; block < core.frames().size(); block++) {
                var collected = new ArrayList<float[][]>();
                Consumer<AudioBuffer> output = buffer -> collected.add(AacTestSupport.copy(buffer));
                var unit = units == null || block % blocksPerFrame != 0 || block / blocksPerFrame >= units.size()
                        ? null : units.get(block / blocksPerFrame);
                if (surroundConfig != null && unit != null) {
                    decoder.decode(List.of(packet(core.frames().get(block)), packet(unit)), output);
                } else if (surroundConfig != null && emptyUnits) {
                    decoder.decode(List.of(packet(core.frames().get(block)), packet(new byte[0])), output);
                } else {
                    decoder.decode(packet(core.frames().get(block)), output);
                }
                assertEquals(1, collected.size(), "block " + block);
                decoded.add(new AacTestSupport.Decoded(collected.getFirst(), decoder.sampleRate(), decoder.layout()));
            }
        }
        return decoded;
    }

    /**
     * Asserts that blocks decoded with spatial frames of two blocks are the upmix of the downmix one block late, the
     * first block the silent downmix.
     */
    static void assertOneBlockLate(float[][] expected, List<AacTestSupport.Decoded> decoded, int[] outputOrder,
                                   ChannelLayout layout) {
        var length = 1024;
        assertEquals(ChannelLayout.MONO, decoded.getFirst().layout());
        assertArrayEquals(new float[length], decoded.getFirst().planes()[0], 0.0f, "delayed downmix");
        for (var block = 1; block < decoded.size(); block++) {
            var planes = decoded.get(block).planes();
            assertEquals(layout, decoded.get(block).layout(), "block " + block);
            assertEquals(expected.length, planes.length, "block " + block);
            for (var c = 0; c < expected.length; c++) {
                assertArrayEquals(Arrays.copyOfRange(expected[c], (block - 1) * length, block * length),
                        planes[outputOrder[c]], 0.0f, "block " + block + " channel " + c);
            }
        }
    }

    /** {@return the first blocks of a stream} */
    static AacTestSupport.Stream head(AacTestSupport.Stream stream, int blocks) {
        return new AacTestSupport.Stream(stream.config(), stream.frames().subList(0, blocks), stream.sampleRate(),
                stream.channels());
    }

    static byte[] specificConfig(List<byte[]> spatial) {
        var header = spatial.getFirst();
        return Arrays.copyOfRange(header, (header[0] & 0x7F) == 127 ? 3 : 1, header.length);
    }

    static final int[] IDENTITY_5_1 = {0, 1, 2, 3, 4, 5};

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
        @DisplayName("upmixes spatial frames fragmented over the blocks of the downmix like the embedded stream")
        void fragmentedLikeEncoderStream() {
            var spatial = blocks(resource("mps-lc5151.bs"));
            var core = stream("mps-lc5151-core");
            var embedded = embed(core, spatial, 1);
            var expected = decode(stream("mps-lc5151"), true);
            assertSameUpmix(decode(embedded, true).planes(), expected.planes());
        }

        @Test
        @DisplayName("upmixes spatial frames of two blocks one block late, the downmix included")
        void longerFrame() {
            var spatial = blocks(resource("mps-long5151.bs"));
            assertEquals(32, config(spatial.getFirst()).frameLength());
            var core = stream("mps-lc5151-core");
            var expected = upmix(spatial, decode(core, false).planes(), 48000);
            var decoded = decodeBlocks(embed(core, spatial, 2), null, null, 2, false);
            assertEquals(core.frames().size(), decoded.size());
            assertOneBlockLate(expected, decoded, IDENTITY_5_1, ChannelLayout.SURROUND_5_1);
        }

        @Test
        @DisplayName("keeps the upmix of frames of two blocks when the side information goes missing")
        void longerFrameRepeatsParameters() {
            var spatial = blocks(resource("mps-long5151.bs"));
            var core = stream("mps-lc5151-core");
            var embedded = embed(core, spatial, 2);
            var frames = new ArrayList<>(embedded.frames());
            // the side information of frames 6 and 7 (blocks 12 to 15) is cut from the stream
            for (var block = 12; block < 16; block++) {
                frames.set(block, core.frames().get(block));
            }
            var cut = new AacTestSupport.Stream(embedded.config(), frames, embedded.sampleRate(), embedded.channels());
            var decoded = decodeBlocks(cut, null, null, 2, false);
            for (var block = 1; block < decoded.size(); block++) {
                assertEquals(6, decoded.get(block).planes().length, "block " + block);
                for (var channel : decoded.get(block).planes()) {
                    for (var sample : channel) {
                        assertTrue(Float.isFinite(sample), "block " + block);
                    }
                }
            }
            var full = decodeBlocks(embedded, null, null, 2, false);
            for (var block = 1; block <= 12; block++) {
                for (var c = 0; c < 6; c++) {
                    assertArrayEquals(full.get(block).planes()[c], decoded.get(block).planes()[c], 0.0f,
                            "block " + block);
                }
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

        @ParameterizedTest(name = "empty units between frames: {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("upmixes spatial frames of two blocks one block late, each access unit with the first block")
        void longerFrame(boolean emptyUnits) {
            var spatial = blocks(resource("mps-long5151.bs"));
            var config = surroundConfig(false, MpegSurroundTest.specificConfig(spatial));
            assertEquals(32, MpsConfig.readAudioSpecificConfig(config).config().frameLength());
            var core = stream("mps-lc5151-core");
            var expected = upmix(spatial, decode(core, false).planes(), 48000);
            var decoded = decodeBlocks(core, config, spatial.subList(1, spatial.size()), 2, emptyUnits);
            assertOneBlockLate(expected, decoded, IDENTITY_5_1, ChannelLayout.SURROUND_5_1);
        }

        @Test
        @DisplayName("outputs the downmix one block late until the first independent frame of two blocks")
        void longerFrameJoins() {
            var spatial = blocks(resource("mps-long5151.bs"));
            var specific = MpegSurroundTest.specificConfig(spatial);
            var config = MpsConfig.readAudioSpecificConfig(surroundConfig(false, specific)).config();
            var core = stream("mps-lc5151-core");
            var probe = new MpsDecoder(config, 48000);
            // join at frame 3 (block 6); the upmix starts with the first independent frame from there
            var first = 3;
            while (!probe.independentFrame(new AacBitReader(spatial.get(first + 1)))) {
                first++;
            }
            assertTrue(2 * first + 1 < core.frames().size(), "no independent frame to join at");
            var joined = new AacTestSupport.Stream(core.config(), core.frames().subList(6, core.frames().size()),
                    core.sampleRate(), core.channels());
            // the downmix of a decoder that starts at block 6, without the overlap of block 5
            var downmix = decode(joined, false).planes()[0];
            var decoded = decodeBlocks(joined, surroundConfig(false, specific), spatial.subList(4, spatial.size()), 2,
                    false);
            var start = 2 * (first - 3) + 1;
            for (var block = 0; block < decoded.size(); block++) {
                var planes = decoded.get(block).planes();
                if (block < start) {
                    assertEquals(ChannelLayout.MONO, decoded.get(block).layout(), "block " + block);
                    var late = block == 0 ? new float[1024]
                            : Arrays.copyOfRange(downmix, (block - 1) * 1024, block * 1024);
                    assertArrayEquals(late, planes[0], 0.0f, "delayed downmix of block " + block);
                } else {
                    assertEquals(ChannelLayout.SURROUND_5_1, decoded.get(block).layout(), "block " + block);
                }
            }
        }

        @Test
        @DisplayName("upmixes an arbitrary tree into its loudspeaker positions in WAVE order")
        void arbitraryTree() {
            var spatial = blocks(resource("mps-at71.bs"));
            var config = surroundConfig(false, MpegSurroundTest.specificConfig(spatial));
            var parsed = MpsConfig.readAudioSpecificConfig(config).config();
            assertTrue(parsed.arbitraryTreeActive());
            assertEquals(8, parsed.outputChannels());
            assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 8, 9}, parsed.arbitraryTreePositions());
            var core = stream("mps-lc5151-core");
            var expected = upmix(spatial, decode(core, false).planes(), 48000);
            var decoded = decodeSeparate(head(core, spatial.size() - 1), config, spatial.subList(1, spatial.size()));
            // positions 4 and 5 are the side pair next to the rear pair 8 and 9
            assertEquals(ChannelLayout.SURROUND_7_1, decoded.layout());
            var order = new int[]{0, 1, 2, 3, 6, 7, 4, 5};
            for (var c = 0; c < expected.length; c++) {
                var length = expected[c].length;
                assertEquals(length, decoded.planes()[order[c]].length, "channel " + c);
                assertArrayEquals(expected[c], decoded.planes()[order[c]], 0.0f, "channel " + c);
            }
        }

        @Test
        @DisplayName("rejects an arbitrary tree that feeds a position twice or outputs too many channels")
        void malformedArbitraryTree() {
            var spatial = blocks(resource("mps-at71.bs"));
            var specific = MpegSurroundTest.specificConfig(spatial);
            var parsed = MpsConfig.readAudioSpecificConfig(surroundConfig(false, specific)).config();
            assertEquals(8, parsed.outputChannels());
            // the configuration is 6 bytes of the 5-1-5 tree, then the extension: type 2 and length 7, then the ten
            // box flags of the six outputs, two boxes of two bits and eight 5-bit positions
            assertEquals(14, specific.length);
            var base = Arrays.copyOf(specific, 7);
            assertEquals(2 << 4 | 7, base[6] & 0xFF, "extension header");
            var duplicate = new Bits();
            for (var value : base) {
                duplicate.put(value & 0xFF, 8);
            }
            duplicate.put(0, 4).put(0b100, 3).put(0b100, 3).put(0, 4);
            for (var position : new int[]{0, 1, 2, 3, 4, 8, 5, 4}) {
                duplicate.put(position, 5);
            }
            var bytes = Arrays.copyOf(duplicate.bytes(), specific.length);
            assertThrows(MediaException.Malformed.class,
                    () -> MpsConfig.readAudioSpecificConfig(surroundConfig(false, bytes)));
            var wide = new Bits();
            for (var value : Arrays.copyOf(base, 6)) {
                wide.put(value & 0xFF, 8);
            }
            // type 2 with the escaped length 15: every output channel split three levels deep, 48 outputs
            wide.put(2, 4).put(15, 4).put(0, 8);
            for (var ch = 0; ch < 6; ch++) {
                for (var flag : new int[]{1, 1, 1, 0, 0, 1, 0, 0, 1, 1, 0, 0, 1, 0, 0}) {
                    wide.put(flag, 1);
                }
            }
            var tooWide = Arrays.copyOf(wide.bytes(), 6 + 2 + 15);
            assertThrows(MediaException.Malformed.class,
                    () -> MpsConfig.readAudioSpecificConfig(surroundConfig(false, tooWide)));
        }

        @Test
        @DisplayName("upmixes the tree alone when the arbitrary tree's CLDs are non-uniformly quantized")
        void arbitraryTreeUnderQuantMode() {
            var spatial = blocks(resource("mps-at71.bs"));
            var specific = MpegSurroundTest.specificConfig(spatial);
            // bsQuantMode, bits 18 and 19 after the sampling frequency index, frame length, frequency resolution and
            // tree, becomes 1
            assertEquals(0, (specific[2] & 0xFF) >>> 4 & 3);
            specific[2] = (byte) (specific[2] | 1 << 4);
            var parsed = MpsConfig.readAudioSpecificConfig(surroundConfig(false, specific)).config();
            assertFalse(parsed.arbitraryTreeActive());
            assertEquals(6, parsed.outputChannels());
            assertEquals(0, parsed.arbitraryTreePositions().length);
            var decoded = decodeSeparate(head(stream("mps-lc5151-core"), spatial.size() - 1),
                    surroundConfig(false, specific), spatial.subList(1, spatial.size()));
            assertEquals(ChannelLayout.SURROUND_5_1, decoded.layout());
            assertEquals(6, decoded.planes().length);
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

    /**
     * Builds an AAC stream whose raw data blocks each start with the fill elements of {@code framesPerBlock} whole
     * spatial frames, the first frame and every seventh after it starting with the header.
     */
    static AacTestSupport.Stream embedFractional(AacTestSupport.Stream core, List<byte[]> spatial,
                                                 int framesPerBlock) {
        var header = spatial.getFirst();
        var count = Math.min((spatial.size() - 1) / framesPerBlock, core.frames().size());
        var blocks = new ArrayList<byte[]>();
        for (var k = 0; k < count; k++) {
            var bits = new Bits();
            for (var f = 0; f < framesPerBlock; f++) {
                var frame = k * framesPerBlock + f;
                var withHeader = frame % 7 == 0;
                var part = payloadFragments(withHeader ? header : null, spatial.get(frame + 1), 1).getFirst();
                var bytes = 1 + part.length;
                assertTrue(bytes <= 15 + 255 - 1, "frame of " + bytes + " bytes");
                bits.put(6, 3);
                if (bytes < 15) {
                    bits.put(bytes, 4);
                } else {
                    bits.put(15, 4).put(bytes - 14, 8);
                }
                bits.put(MpsUpmixer.EXT_SAC_DATA, 4).put(withHeader ? 1 : 0, 2).put(1, 1).put(1, 1);
                for (var value : part) {
                    bits.put(value & 0xFF, 8);
                }
            }
            for (var value : core.frames().get(k)) {
                bits.put(value & 0xFF, 8);
            }
            blocks.add(bits.bytes());
        }
        return new AacTestSupport.Stream(core.config(), blocks, core.sampleRate(), core.channels());
    }

    /** {@return a deterministic pseudo-random signal in {@code [-amplitude, amplitude]}} */
    static float[][] noise(int channels, int length, float amplitude, long seed) {
        var random = new java.util.Random(seed);
        var signal = new float[channels][length];
        for (var channel : signal) {
            for (var i = 0; i < length; i++) {
                channel[i] = (2 * random.nextFloat() - 1) * amplitude;
            }
        }
        return signal;
    }

    @Nested
    @DisplayName("spatial frames shorter than a block")
    class FractionalFrames {
        static final int BLOCK = 1024;

        static List<byte[]> spatial() {
            var spatial = blocks(resource("mps-short5151.bs"));
            assertEquals(8, config(spatial.getFirst()).frameLength());
            return spatial;
        }

        @Test
        @DisplayName("upmixes two embedded frames of half a block per block without delay")
        void embedded() {
            var spatial = spatial();
            var core = stream("mps-lc5151-core");
            var expected = upmix(spatial, decode(core, false).planes(), 48000);
            var embedded = embedFractional(core, spatial, 2);
            var decoded = decodeBlocks(embedded, null, null, 1, false);
            assertEquals(embedded.frames().size(), decoded.size());
            for (var block = 0; block < decoded.size(); block++) {
                assertEquals(ChannelLayout.SURROUND_5_1, decoded.get(block).layout(), "block " + block);
                for (var c = 0; c < expected.length; c++) {
                    assertArrayEquals(Arrays.copyOfRange(expected[c], block * BLOCK, (block + 1) * BLOCK),
                            decoded.get(block).planes()[c], 0.0f, "block " + block + " channel " + c);
                }
            }
        }

        @Test
        @DisplayName("upmixes the frames of a separate stream given before their block like whole frames")
        void separate() {
            var spatial = spatial();
            var config = config(spatial.getFirst());
            var downmix = noise(1, 6 * BLOCK, 0.5f, 11);
            var expected = upmix(spatial, downmix, 48000);
            var upmixer = new MpsUpmixer();
            upmixer.configure(config);
            var out = new float[6][BLOCK];
            for (var k = 0; k < 6; k++) {
                upmixer.addFrame(spatial.get(2 * k + 1), 0, spatial.get(2 * k + 1).length);
                upmixer.addFrame(new byte[0], 0, 0);
                upmixer.addFrame(spatial.get(2 * k + 2), 0, spatial.get(2 * k + 2).length);
                assertTrue(upmixer.prepare(48000, BLOCK), "block " + k);
                assertTrue(upmixer.upmixed(), "block " + k);
                upmixer.upmix(new float[][]{Arrays.copyOfRange(downmix[0], k * BLOCK, (k + 1) * BLOCK)}, out);
                for (var c = 0; c < 6; c++) {
                    assertArrayEquals(Arrays.copyOfRange(expected[c], k * BLOCK, (k + 1) * BLOCK), out[c], 0.0f,
                            "block " + k + " channel " + c);
                }
            }
        }

        @Test
        @DisplayName("upmixes four frames per QMF block of spectral band replication like whole frames")
        void qmf() {
            var spatial = spatial();
            var config = config(spatial.getFirst());
            var reference = new MpsDecoder(config, 48000);
            var slots = reference.slots();
            var bands = reference.qmfBands();
            assertEquals(64, bands);
            var block = 2048;
            var framesPerBlock = block / reference.frameLength();
            assertEquals(4, framesPerBlock);
            var blockSlots = framesPerBlock * slots;
            var count = 4;
            var real = noise(count * blockSlots, 64, 4000.0f, 21);
            var imag = noise(count * blockSlots, 64, 4000.0f, 22);
            var expected = new float[6][count * block];
            for (var frame = 0; frame < count * framesPerBlock; frame++) {
                reference.readFrame(new AacBitReader(spatial.get(frame + 1)));
                var inReal = new float[1][slots][];
                var inImag = new float[1][slots][];
                for (var ts = 0; ts < slots; ts++) {
                    inReal[0][ts] = real[frame * slots + ts].clone();
                    inImag[0][ts] = imag[frame * slots + ts].clone();
                }
                reference.decodeQmf(inReal, inImag, expected, frame * reference.frameLength());
            }
            var upmixer = new MpsUpmixer();
            upmixer.configure(config);
            var out = new float[6][block];
            for (var k = 0; k < count; k++) {
                for (var f = 0; f < framesPerBlock; f++) {
                    var frame = spatial.get(k * framesPerBlock + f + 1);
                    upmixer.addFrame(frame, 0, frame.length);
                }
                assertTrue(upmixer.prepare(48000, block), "block " + k);
                assertTrue(upmixer.acceptsQmf(), "block " + k);
                var blockReal = new float[1][blockSlots * 64];
                var blockImag = new float[1][blockSlots * 64];
                for (var ts = 0; ts < blockSlots; ts++) {
                    System.arraycopy(real[k * blockSlots + ts], 0, blockReal[0], ts * 64, 64);
                    System.arraycopy(imag[k * blockSlots + ts], 0, blockImag[0], ts * 64, 64);
                }
                upmixer.upmixQmf(blockReal, blockImag, new float[1][block], out);
                for (var c = 0; c < 6; c++) {
                    for (var i = 0; i < block; i++) {
                        assertEquals(expected[c][k * block + i] / 32768.0f, out[c][i], 0.0f,
                                "block " + k + " channel " + c + " sample " + i);
                    }
                }
            }
        }

        @Test
        @DisplayName("starts at an independent frame inside a block, the part of the block before it silent")
        void joinsInsideBlock() {
            var spatial = spatial();
            var config = config(spatial.getFirst());
            var probe = new MpsDecoder(config, 48000);
            var first = 1;
            while (first + 1 < spatial.size() - 1 && (probe.independentFrame(new AacBitReader(spatial.get(first)))
                    || !probe.independentFrame(new AacBitReader(spatial.get(first + 1))))) {
                first++;
            }
            // block 0 takes the dependent frame first - 1 and the independent frame first
            assertTrue(first + 1 < spatial.size() - 1, "no dependent frame before an independent one");
            var half = BLOCK / 2;
            var count = (spatial.size() - first) / 2;
            var downmix = noise(1, count * BLOCK, 0.5f, 31);
            var joined = new ArrayList<byte[]>();
            joined.add(spatial.getFirst());
            joined.addAll(spatial.subList(first + 1, spatial.size()));
            var tail = new float[][]{Arrays.copyOfRange(downmix[0], half, downmix[0].length)};
            var expected = upmix(joined, tail, 48000);
            var upmixer = new MpsUpmixer();
            upmixer.configure(config);
            var out = new float[6][BLOCK];
            for (var k = 0; k < count; k++) {
                for (var f = 0; f < 2; f++) {
                    var frame = spatial.get(first + 2 * k + f);
                    upmixer.addFrame(frame, 0, frame.length);
                }
                assertTrue(upmixer.prepare(48000, BLOCK), "block " + k);
                upmixer.upmix(new float[][]{Arrays.copyOfRange(downmix[0], k * BLOCK, (k + 1) * BLOCK)}, out);
                for (var c = 0; c < 6; c++) {
                    for (var i = 0; i < BLOCK; i++) {
                        var position = k * BLOCK + i - half;
                        var value = position < 0 ? 0.0f : expected[c][position];
                        assertEquals(value, out[c][i], 0.0f, "block " + k + " channel " + c + " sample " + i);
                    }
                }
            }
        }

        @Test
        @DisplayName("keeps the downmix until a block holds an independent frame")
        void waitsForIndependentFrame() {
            var spatial = spatial();
            var config = config(spatial.getFirst());
            var probe = new MpsDecoder(config, 48000);
            var dependent = 1;
            while (probe.independentFrame(new AacBitReader(spatial.get(dependent)))) {
                dependent++;
            }
            var upmixer = new MpsUpmixer();
            upmixer.configure(config);
            upmixer.addFrame(spatial.get(dependent), 0, spatial.get(dependent).length);
            assertFalse(upmixer.prepare(48000, BLOCK));
            assertFalse(upmixer.upmixed());
            assertFalse(upmixer.prepare(48000, BLOCK), "a block without frames");
        }

        @Test
        @DisplayName("upmixes the part of a block whose frame is missing with the parameters of the frame before")
        void repeatsMissingFrame() {
            var spatial = spatial();
            var config = config(spatial.getFirst());
            var downmix = noise(1, 3 * BLOCK, 0.5f, 41);
            var input = new float[1][3 * BLOCK];
            for (var i = 0; i < input[0].length; i++) {
                input[0][i] = downmix[0][i] * 32768.0f;
            }
            var reference = new MpsDecoder(config, 48000);
            var expected = new float[6][3 * BLOCK];
            var half = BLOCK / 2;
            // block 1 carries only frame 3 and block 2 only frame 6: each upmixes its first half with that frame
            // and its second half with the parameters of that frame repeated
            int[][] carried = {{1, 2}, {3}, {6}};
            for (var k = 0; k < 3; k++) {
                for (var f = 0; f < 2; f++) {
                    if (f < carried[k].length) {
                        reference.readFrame(new AacBitReader(spatial.get(carried[k][f])));
                    } else {
                        reference.repeatFrame();
                    }
                    reference.decode(input, k * BLOCK + f * half, expected, k * BLOCK + f * half);
                }
            }
            var upmixer = new MpsUpmixer();
            upmixer.configure(config);
            var out = new float[6][BLOCK];
            for (var k = 0; k < 3; k++) {
                for (var index : carried[k]) {
                    upmixer.addFrame(spatial.get(index), 0, spatial.get(index).length);
                }
                assertTrue(upmixer.prepare(48000, BLOCK), "block " + k);
                upmixer.upmix(new float[][]{Arrays.copyOfRange(downmix[0], k * BLOCK, (k + 1) * BLOCK)}, out);
                for (var c = 0; c < 6; c++) {
                    for (var i = 0; i < BLOCK; i++) {
                        assertEquals(expected[c][k * BLOCK + i] / 32768.0f, out[c][i], 0.0f,
                                "block " + k + " channel " + c + " sample " + i);
                    }
                }
            }
        }

        @Test
        @DisplayName("drops the frames beyond those of the block")
        void dropsExtraFrames() {
            var spatial = spatial();
            var config = config(spatial.getFirst());
            var downmix = noise(1, 2 * BLOCK, 0.5f, 51);
            var expected = upmix(spatial, downmix, 48000);
            var upmixer = new MpsUpmixer();
            upmixer.configure(config);
            var out = new float[6][BLOCK];
            // block 0 is given frames 1, 2 and an extra copy of frame 3, block 1 frames 3 and 4
            for (var index : new int[]{1, 2, 3}) {
                upmixer.addFrame(spatial.get(index), 0, spatial.get(index).length);
            }
            assertTrue(upmixer.prepare(48000, BLOCK));
            upmixer.upmix(new float[][]{Arrays.copyOf(downmix[0], BLOCK)}, out);
            for (var index : new int[]{3, 4}) {
                upmixer.addFrame(spatial.get(index), 0, spatial.get(index).length);
            }
            assertTrue(upmixer.prepare(48000, BLOCK));
            upmixer.upmix(new float[][]{Arrays.copyOfRange(downmix[0], BLOCK, 2 * BLOCK)}, out);
            for (var c = 0; c < 6; c++) {
                assertArrayEquals(Arrays.copyOfRange(expected[c], BLOCK, 2 * BLOCK), out[c], 0.0f, "channel " + c);
            }
        }

        @ParameterizedTest(name = "block of {0} samples")
        @ValueSource(ints = {960, 1280, 1920})
        @DisplayName("keeps the downmix when a frame neither divides the block nor lasts a whole number of blocks")
        void indivisibleBlock(int block) {
            var spatial = spatial();
            var upmixer = new MpsUpmixer();
            upmixer.configure(config(spatial.getFirst()));
            upmixer.addFrame(spatial.get(1), 0, spatial.get(1).length);
            assertFalse(upmixer.prepare(48000, block));
            assertFalse(upmixer.upmixed());
        }
    }

    @Nested
    @DisplayName("spatial frames of several blocks")
    class LongFrames {
        static final int BLOCK = 1024;
        static final int FRAME = 2 * BLOCK;

        static List<byte[]> spatial() {
            var spatial = blocks(resource("mps-long5151.bs"));
            assertEquals(32, config(spatial.getFirst()).frameLength());
            return spatial;
        }

        /** Hands one {@code sac_extension_data()} fragment to the upmixer. */
        static void fill(MpsUpmixer upmixer, int type, boolean start, boolean stop, byte[] data) {
            var bits = new Bits().put(MpsUpmixer.EXT_SAC_DATA, 4).put(type, 2).put(start ? 1 : 0, 1)
                    .put(stop ? 1 : 0, 1);
            for (var value : data) {
                bits.put(value & 0xFF, 8);
            }
            var bytes = bits.bytes();
            var reader = new AacBitReader(bytes);
            reader.read(4);
            upmixer.readExtension(reader, bytes.length);
        }

        static float[][] scaled(float[][] signal) {
            var scaled = new float[signal.length][signal[0].length];
            for (var c = 0; c < signal.length; c++) {
                for (var i = 0; i < signal[c].length; i++) {
                    scaled[c][i] = signal[c][i] * 32768.0f;
                }
            }
            return scaled;
        }

        static float[][] block(float[][] signal, int k) {
            var block = new float[signal.length][];
            for (var c = 0; c < signal.length; c++) {
                block[c] = Arrays.copyOfRange(signal[c], k * BLOCK, (k + 1) * BLOCK);
            }
            return block;
        }

        static void assertUpmix(float[][] expected, int position, float[][] out, String label) {
            for (var c = 0; c < expected.length; c++) {
                for (var i = 0; i < BLOCK; i++) {
                    assertEquals(expected[c][position + i] / 32768.0f, out[c][i], 0.0f,
                            label + " channel " + c + " sample " + i);
                }
            }
        }

        /** Decodes the blocks of a stream, then flushes, each block a list entry in the order they were emitted. */
        static List<float[][]> decodeAndFlush(AacTestSupport.Stream stream, byte[] surroundConfig, List<byte[]> units) {
            var tracks = surroundConfig == null ? List.of(stream.track())
                    : List.of(stream.track(), surroundTrack(surroundConfig));
            var collected = new ArrayList<float[][]>();
            Consumer<AudioBuffer> output = buffer -> collected.add(AacTestSupport.copy(buffer));
            try (var decoder = new AacDecoder(tracks, AacDecoderOptions.defaults())) {
                for (var block = 0; block < stream.frames().size(); block++) {
                    if (surroundConfig == null) {
                        decoder.decode(packet(stream.frames().get(block)), output);
                    } else {
                        var unit = block % 2 == 0 && block / 2 < units.size() ? units.get(block / 2) : new byte[0];
                        decoder.decode(List.of(packet(stream.frames().get(block)), packet(unit)), output);
                    }
                }
                assertEquals(stream.frames().size(), collected.size(), "one block per packet");
                decoder.flush(output);
                var flushed = collected.size();
                decoder.flush(output);
                assertEquals(flushed, collected.size(), "a second flush emits nothing");
            }
            return collected;
        }

        /**
         * Asserts blocks of a stream of {@code count} blocks and frames of two: the first silent, then the upmix one
         * block late up to the block of {@link AacDecoder#flush} that holds the last one, where a frame past
         * {@code read} frames repeats the parameters.
         */
        static void assertFlushed(List<float[][]> decoded, float[] downmix, int count, int read,
                                  List<byte[]> spatial) {
            assertEquals(count + 1, decoded.size(), "the delay comes out in the flush");
            assertArrayEquals(new float[BLOCK], decoded.getFirst()[0], 0.0f, "delayed downmix");
            var reference = new MpsDecoder(config(spatial.getFirst()), 48000);
            var frames = (count + 1) / 2;
            var input = new float[1][frames * FRAME];
            for (var i = 0; i < count * BLOCK; i++) {
                input[0][i] = downmix[i] * 32768.0f;
            }
            var expected = new float[6][frames * FRAME];
            for (var f = 0; f < frames; f++) {
                if (f < read) {
                    reference.readFrame(new AacBitReader(spatial.get(f + 1)));
                } else {
                    reference.repeatFrame();
                }
                reference.decode(input, f * FRAME, expected, f * FRAME);
            }
            for (var k = 1; k <= count; k++) {
                assertEquals(6, decoded.get(k).length, "block " + k);
                assertUpmix(expected, (k - 1) * BLOCK, decoded.get(k), "block " + k);
            }
        }

        @ParameterizedTest(name = "{0} blocks")
        @ValueSource(ints = {8, 7})
        @DisplayName("hands over the end of an embedded stream in the flush")
        void flushEmbedded(int count) {
            var spatial = spatial();
            var core = head(stream("mps-lc5151-core"), 8);
            var embedded = embed(core, spatial, 2);
            var stream = new AacTestSupport.Stream(embedded.config(), embedded.frames().subList(0, count),
                    embedded.sampleRate(), embedded.channels());
            var downmix = decode(head(core, count), false).planes()[0];
            // with seven blocks the last frame is cut after its first fragment and its parameters are the last ones
            assertFlushed(decodeAndFlush(stream, null, null), downmix, count, count / 2, spatial);
        }

        @ParameterizedTest(name = "{0} blocks")
        @ValueSource(ints = {8, 7})
        @DisplayName("hands over the end of a stream with separate side information in the flush")
        void flushSeparate(int count) {
            var spatial = spatial();
            var core = head(stream("mps-lc5151-core"), count);
            var config = surroundConfig(false, MpegSurroundTest.specificConfig(spatial));
            var downmix = decode(core, false).planes()[0];
            // an access unit comes with the first block of its frame, so the last frame is whole in both cases
            var decoded = decodeAndFlush(core, config, spatial.subList(1, spatial.size()));
            assertFlushed(decoded, downmix, count, (count + 1) / 2, spatial);
        }

        @Test
        @DisplayName("hands over the end of the delayed downmix in the flush when the upmix never started")
        void flushNotStarted() {
            var spatial = spatial();
            var core = head(stream("mps-lc5151-core"), 4);
            var downmix = decode(core, false).planes()[0];
            var config = surroundConfig(false, MpegSurroundTest.specificConfig(spatial));
            var decoded = decodeAndFlush(core, config, List.of());
            assertEquals(5, decoded.size());
            assertArrayEquals(new float[BLOCK], decoded.getFirst()[0], 0.0f);
            for (var k = 1; k < 5; k++) {
                assertEquals(1, decoded.get(k).length, "block " + k);
                assertArrayEquals(Arrays.copyOfRange(downmix, (k - 1) * BLOCK, k * BLOCK), decoded.get(k)[0], 0.0f,
                        "block " + k);
            }
        }

        @Test
        @DisplayName("flushes nothing when a spatial frame lasts one block or less")
        void flushWithoutDelay() {
            var short5151 = FractionalFrames.spatial();
            var embedded = embedFractional(head(stream("mps-lc5151-core"), 3), short5151, 2);
            assertEquals(3, decodeAndFlush(embedded, null, null).size());
            assertEquals(4, decodeAndFlush(head(stream("mps-lc5151"), 4), null, null).size());
        }

        @Test
        @DisplayName("upmixes frames of two blocks of QMF slots like whole frames, a block of samples going through time")
        void qmf() {
            var spatial = spatial();
            var config = config(spatial.getFirst());
            var reference = new MpsDecoder(config, 48000);
            assertEquals(64, reference.qmfBands());
            var slots = reference.slots();
            var blockSlots = slots / 2;
            var frames = 4;
            var real = noise(frames * slots, 64, 4000.0f, 61);
            var imag = noise(frames * slots, 64, 4000.0f, 62);
            var time = noise(1, frames * FRAME, 0.1f, 63);
            var input = scaled(time);
            var expected = new float[6][frames * FRAME];
            // the last frame has a block of samples, so it is upmixed from the samples of both its blocks
            for (var frame = 0; frame < frames; frame++) {
                reference.readFrame(new AacBitReader(spatial.get(frame + 1)));
                if (frame == frames - 1) {
                    reference.decode(input, frame * FRAME, expected, frame * FRAME);
                    continue;
                }
                var inReal = new float[1][slots][];
                var inImag = new float[1][slots][];
                for (var ts = 0; ts < slots; ts++) {
                    inReal[0][ts] = real[frame * slots + ts].clone();
                    inImag[0][ts] = imag[frame * slots + ts].clone();
                }
                reference.decodeQmf(inReal, inImag, expected, frame * FRAME);
            }
            var upmixer = new MpsUpmixer();
            upmixer.configure(config);
            var out = new float[6][BLOCK];
            var count = 2 * frames;
            for (var k = 0; k < count; k++) {
                if (k % 2 == 0) {
                    var frame = spatial.get(k / 2 + 1);
                    upmixer.addFrame(frame, 0, frame.length);
                }
                assertTrue(upmixer.prepare(48000, BLOCK), "block " + k);
                assertTrue(upmixer.acceptsQmf(), "block " + k);
                var samples = block(time, k);
                if (k == count - 1) {
                    upmixer.upmix(samples, out);
                } else {
                    var blockReal = new float[1][blockSlots * 64];
                    var blockImag = new float[1][blockSlots * 64];
                    for (var ts = 0; ts < blockSlots; ts++) {
                        System.arraycopy(real[k * blockSlots + ts], 0, blockReal[0], ts * 64, 64);
                        System.arraycopy(imag[k * blockSlots + ts], 0, blockImag[0], ts * 64, 64);
                    }
                    upmixer.upmixQmf(blockReal, blockImag, samples, out);
                }
                assertEquals(k > 0, upmixer.upmixed(), "block " + k);
                if (k == 0) {
                    assertArrayEquals(new float[BLOCK], samples[0], 0.0f, "delayed downmix");
                } else {
                    assertUpmix(expected, (k - 1) * BLOCK, out, "block " + k);
                }
            }
        }

        @Test
        @DisplayName("keeps reading frames that complete off their block, each when the next one is due")
        void offPhaseFrames() {
            var spatial = spatial();
            var downmix = noise(1, 12 * BLOCK, 0.5f, 71);
            var input = scaled(downmix);
            var reference = new MpsDecoder(config(spatial.getFirst()), 48000);
            var expected = new float[6][12 * BLOCK];
            // a block without side information moves the frames of blocks 6, 8 and 10 off the blocks they are due
            // at, 7, 9 and 11, and the frame due at block 5 is missing
            int[] parameters = {1, 2, 0, 3, 4, 5};
            for (var f = 0; f < parameters.length; f++) {
                if (parameters[f] == 0) {
                    reference.repeatFrame();
                } else {
                    reference.readFrame(new AacBitReader(spatial.get(parameters[f])));
                }
                reference.decode(input, f * FRAME, expected, f * FRAME);
            }
            var upmixer = new MpsUpmixer();
            upmixer.configure(config(spatial.getFirst()));
            int[] carried = {0, 1, 0, 2, 0, 0, 3, 0, 4, 0, 5, 0};
            var out = new float[6][BLOCK];
            for (var k = 0; k < carried.length; k++) {
                if (carried[k] != 0) {
                    fill(upmixer, 0, true, true, spatial.get(carried[k]));
                }
                assertTrue(upmixer.prepare(48000, BLOCK), "block " + k);
                upmixer.upmix(block(downmix, k), out);
                if (k > 0) {
                    assertTrue(upmixer.upmixed(), "block " + k);
                    assertUpmix(expected, (k - 1) * BLOCK, out, "block " + k);
                }
            }
        }

        @Test
        @DisplayName("fails only the block of an invalid frame of several blocks")
        void invalidLongFrame() {
            var spatial = spatial();
            var upmixer = new MpsUpmixer();
            upmixer.configure(config(spatial.getFirst()));
            var block = new float[1][BLOCK];
            var out = new float[6][BLOCK];
            for (var k = 0; k < 3; k++) {
                if (k == 1) {
                    fill(upmixer, 0, true, true, spatial.get(1));
                }
                assertTrue(upmixer.prepare(48000, BLOCK));
                upmixer.upmix(block, out);
            }
            fill(upmixer, 0, true, true, Arrays.copyOf(spatial.get(2), 1));
            assertThrows(MediaException.Malformed.class, () -> upmixer.prepare(48000, BLOCK));
            assertTrue(upmixer.prepare(48000, BLOCK), "the next block repeats the parameters");
            assertTrue(upmixer.upmixed());
            upmixer.upmix(block, out);
        }

        @Test
        @DisplayName("fails only the block of an invalid frame of part of a block")
        void invalidFractionalFrame() {
            var spatial = FractionalFrames.spatial();
            var upmixer = new MpsUpmixer();
            upmixer.configure(config(spatial.getFirst()));
            var block = new float[1][BLOCK];
            var out = new float[6][BLOCK];
            upmixer.addFrame(spatial.get(1), 0, spatial.get(1).length);
            upmixer.addFrame(spatial.get(2), 0, spatial.get(2).length);
            assertTrue(upmixer.prepare(48000, BLOCK));
            upmixer.upmix(block, out);
            var invalid = Arrays.copyOf(spatial.get(3), 1);
            upmixer.addFrame(invalid, 0, invalid.length);
            assertTrue(upmixer.prepare(48000, BLOCK));
            assertThrows(MediaException.Malformed.class, () -> upmixer.upmix(block, out));
            upmixer.addFrame(spatial.get(3), 0, spatial.get(3).length);
            upmixer.addFrame(spatial.get(4), 0, spatial.get(4).length);
            assertTrue(upmixer.prepare(48000, BLOCK));
            upmixer.upmix(block, out);
        }

        @Test
        @DisplayName("drops a frame whose fragments a loss cut, repeating the last parameters")
        void lossCutsFragments() {
            var spatial = spatial();
            var config = config(spatial.getFirst());
            var probe = new MpsDecoder(config, 48000);
            var cut = 2;
            while (!probe.independentFrame(new AacBitReader(spatial.get(cut)))) {
                cut++;
            }
            // the frame is independent, so that only dropping the partial payload keeps it from being read
            var pieces = payloadFragments(null, spatial.get(cut), 3);
            var count = 2 * cut;
            var downmix = noise(1, count * BLOCK, 0.5f, 81);
            var input = scaled(downmix);
            var reference = new MpsDecoder(config, 48000);
            var expected = new float[6][count * BLOCK];
            for (var f = 0; f < cut; f++) {
                if (f < cut - 1) {
                    reference.readFrame(new AacBitReader(spatial.get(f + 1)));
                } else {
                    reference.repeatFrame();
                }
                reference.decode(input, f * FRAME, expected, f * FRAME);
            }
            var upmixer = new MpsUpmixer();
            upmixer.configure(config);
            var out = new float[6][BLOCK];
            for (var k = 0; k < count; k++) {
                if (k == count - 2) {
                    fill(upmixer, 0, true, false, pieces.get(0));
                    upmixer.lose();
                } else if (k == count - 1) {
                    fill(upmixer, 0, false, true, pieces.get(2));
                } else if (k % 2 == 1) {
                    fill(upmixer, 0, true, true, spatial.get(k / 2 + 1));
                }
                assertTrue(upmixer.prepare(48000, BLOCK), "block " + k);
                upmixer.upmix(block(downmix, k), out);
                if (k > 0) {
                    assertUpmix(expected, (k - 1) * BLOCK, out, "block " + k);
                }
            }
        }

        @Test
        @DisplayName("keeps the parameter sets of the last frame when a frame fails after its own parameter slots")
        void failedFrameKeepsParameterSets() {
            var spatial = spatial();
            var config = config(spatial.getFirst());
            var downmix = scaled(noise(1, 2 * FRAME, 0.5f, 101));
            var reference = new MpsDecoder(config, 48000);
            var expected = new float[6][2 * FRAME];
            reference.readFrame(new AacBitReader(spatial.get(1)));
            reference.decode(downmix, 0, expected, 0);
            reference.repeatFrame();
            reference.decode(downmix, FRAME, expected, FRAME);

            var decoder = new MpsDecoder(config, 48000);
            var actual = new float[6][2 * FRAME];
            decoder.readFrame(new AacBitReader(spatial.get(1)));
            decoder.decode(downmix, 0, actual, 0);
            // differential parameter slots: one set at slot 0, then the frame ends
            var invalid = new byte[]{(byte) 0b1000_0000};
            assertThrows(MediaException.Malformed.class, () -> decoder.readFrame(new AacBitReader(invalid)));
            decoder.repeatFrame();
            decoder.decode(downmix, FRAME, actual, FRAME);
            assertSameUpmix(expected, actual);
        }

        /** Writes a frame of one parameter set that keeps every value of the last frame (data mode 1), unsmoothed. */
        static byte[] keepingFrame(int boxes, int iccBoxes, int treeBoxes) {
            var bits = new Bits().put(0, 1).put(0, 3).put(0, 1);
            for (var i = 0; i < boxes + iccBoxes; i++) {
                bits.put(1, 2);
            }
            bits.put(0, 2);
            var bytes = bits.bytes();
            if (treeBoxes == 0) {
                return bytes;
            }
            var extension = new Bits();
            for (var i = 0; i < treeBoxes; i++) {
                extension.put(1, 2);
            }
            var payload = extension.bytes();
            var frame = Arrays.copyOf(bytes, bytes.length + 1 + payload.length);
            frame[bytes.length] = (byte) payload.length;
            System.arraycopy(payload, 0, frame, bytes.length + 1, payload.length);
            return frame;
        }

        @ParameterizedTest(name = "{0}")
        @CsvSource({"mps-long5151.bs,0", "mps-at71.bs,2"})
        @DisplayName("repeats the last parameter set of a frame of several sets, like a frame holding only that set")
        void repeatHoldsLastParameterSet(String name, int treeBoxes) {
            var spatial = blocks(resource(name));
            var config = config(spatial.getFirst());
            var last = 1;
            // the frames up to the first one of several parameter sets, which bits 1 to 3 of a frame count
            while (((spatial.get(last)[0] >> 4) & 7) == 0) {
                last++;
            }
            var length = new MpsDecoder(config, 48000).frameLength();
            var input = scaled(noise(1, (last + 2) * length, 0.5f, 103));
            var repeating = new MpsDecoder(config, 48000);
            var keeping = new MpsDecoder(config, 48000);
            var expected = new float[config.outputChannels()][(last + 2) * length];
            var actual = new float[config.outputChannels()][(last + 2) * length];
            for (var f = 1; f <= last; f++) {
                repeating.readFrame(new AacBitReader(spatial.get(f)));
                keeping.readFrame(new AacBitReader(spatial.get(f)));
                repeating.decode(input, (f - 1) * length, actual, (f - 1) * length);
                keeping.decode(input, (f - 1) * length, expected, (f - 1) * length);
            }
            var frame = keepingFrame(5, 4, treeBoxes);
            for (var f = last; f < last + 2; f++) {
                repeating.repeatFrame();
                keeping.readFrame(new AacBitReader(frame));
                repeating.decode(input, f * length, actual, f * length);
                keeping.decode(input, f * length, expected, f * length);
            }
            assertSameUpmix(expected, actual);
        }

        @Test
        @DisplayName("discards the blocks the old configuration delayed even when the new one has the same frames")
        void reconfigurationDiscardsDelayedBlocks() {
            var spatial = spatial();
            var config = config(spatial.getFirst());
            var upmixer = new MpsUpmixer();
            upmixer.configure(config);
            var downmix = noise(1, 4 * BLOCK, 0.5f, 107);
            var out = new float[6][BLOCK];
            for (var k = 0; k < 2; k++) {
                assertTrue(upmixer.prepare(48000, BLOCK));
                upmixer.upmix(block(downmix, k), out);
            }
            assertEquals(1, upmixer.delayedBlocks());
            upmixer.configure(config);
            assertEquals(0, upmixer.delayedBlocks(), "a flush has nothing to hand over");
            assertTrue(upmixer.prepare(48000, BLOCK));
            var samples = block(downmix, 2);
            upmixer.upmix(samples, out);
            assertFalse(upmixer.upmixed());
            assertArrayEquals(new float[BLOCK], samples[0], 0.0f, "silent as at the start of a stream");
            assertTrue(upmixer.prepare(48000, BLOCK));
            samples = block(downmix, 3);
            upmixer.upmix(samples, out);
            assertArrayEquals(block(downmix, 2)[0], samples[0], 0.0f, "then the delayed downmix");
        }

        @Test
        @DisplayName("restarts cleanly when the configuration moves between frames of two blocks and of one")
        void reconfigures() {
            var longFrames = spatial();
            var oneBlock = blocks(resource("mps-lc5151.bs"));
            assertEquals(16, config(oneBlock.getFirst()).frameLength());
            var downmix = noise(1, 8 * BLOCK, 0.5f, 91);
            var input = scaled(downmix);
            var first = new float[6][8 * BLOCK];
            var second = new float[6][8 * BLOCK];
            var third = new float[6][8 * BLOCK];
            var longDecoder = new MpsDecoder(config(longFrames.getFirst()), 48000);
            for (var f = 0; f < 2; f++) {
                longDecoder.readFrame(new AacBitReader(longFrames.get(f + 1)));
                longDecoder.decode(input, f * FRAME, first, f * FRAME);
            }
            var blockDecoder = new MpsDecoder(config(oneBlock.getFirst()), 48000);
            for (var f = 0; f < 2; f++) {
                blockDecoder.readFrame(new AacBitReader(oneBlock.get(f + 1)));
                blockDecoder.decode(input, (4 + f) * BLOCK, second, (4 + f) * BLOCK);
            }
            var again = new MpsDecoder(config(longFrames.getFirst()), 48000);
            again.readFrame(new AacBitReader(longFrames.get(1)));
            again.decode(input, 6 * BLOCK, third, 6 * BLOCK);

            var upmixer = new MpsUpmixer();
            var out = new float[6][BLOCK];
            var parts = payloadFragments(longFrames.getFirst(), longFrames.get(1), 2);
            for (var k = 0; k < 8; k++) {
                switch (k) {
                    case 0 -> fill(upmixer, 1, true, false, parts.get(0));
                    case 1 -> fill(upmixer, 1, false, true, parts.get(1));
                    case 3 -> fill(upmixer, 0, true, true, longFrames.get(2));
                    case 4 -> fill(upmixer, 1, true, true,
                            payloadFragments(oneBlock.getFirst(), oneBlock.get(1), 1).getFirst());
                    case 5 -> fill(upmixer, 0, true, true, oneBlock.get(2));
                    case 6 -> fill(upmixer, 1, true, false, parts.get(0));
                    case 7 -> fill(upmixer, 1, false, true, parts.get(1));
                    default -> {
                    }
                }
                assertTrue(upmixer.prepare(48000, BLOCK), "block " + k);
                var samples = block(downmix, k);
                upmixer.upmix(samples, out);
                switch (k) {
                    case 0, 6 -> {
                        assertFalse(upmixer.upmixed(), "block " + k);
                        assertArrayEquals(new float[BLOCK], samples[0], 0.0f, "silent start of block " + k);
                    }
                    case 1, 2, 3 -> assertUpmix(first, (k - 1) * BLOCK, out, "block " + k);
                    case 4, 5 -> assertUpmix(second, k * BLOCK, out, "block " + k);
                    default -> assertUpmix(third, 6 * BLOCK, out, "block " + k);
                }
            }
        }

        @Test
        @DisplayName("does not read a frame held for its block under the configuration that follows it")
        void reconfigurationDropsHeldFrame() {
            var spatial = spatial();
            var oneBlock = blocks(resource("mps-lc5151.bs"));
            var upmixer = new MpsUpmixer();
            upmixer.configure(config(spatial.getFirst()));
            var block = new float[1][BLOCK];
            var out = new float[6][BLOCK];
            fill(upmixer, 0, true, true, spatial.get(1));
            assertTrue(upmixer.prepare(48000, BLOCK));
            upmixer.upmix(block, out);
            assertTrue(upmixer.upmixed());
            fill(upmixer, 0, true, true, spatial.get(1));
            assertTrue(upmixer.prepare(48000, BLOCK), "the frame waits for the next block");
            upmixer.upmix(block, out);
            // the header arrives in the first fragment of a frame that is not complete yet
            var parts = payloadFragments(oneBlock.getFirst(), oneBlock.get(1), 2);
            fill(upmixer, 1, true, false, parts.getFirst());
            assertFalse(upmixer.prepare(48000, BLOCK), "no frame of the new configuration yet");
            fill(upmixer, 1, false, true, parts.getLast());
            assertTrue(upmixer.prepare(48000, BLOCK));
            assertTrue(upmixer.upmixed());
        }

        @Test
        @DisplayName("fails every time on an invalid header, not reading frames under the configuration before it")
        void invalidHeader() {
            var specific = MpegSurroundTest.specificConfig(blocks(resource("mps-at71.bs")));
            var invalid = new Bits().put(specific.length, 8);
            for (var value : Arrays.copyOf(specific, 7)) {
                invalid.put(value & 0xFF, 8);
            }
            invalid.put(0, 4).put(0b100, 3).put(0b100, 3).put(0, 4);
            for (var position : new int[]{0, 1, 2, 3, 4, 8, 5, 4}) {
                invalid.put(position, 5);
            }
            var header = Arrays.copyOf(invalid.bytes(), specific.length + 1);
            var upmixer = new MpsUpmixer();
            assertThrows(MediaException.Malformed.class, () -> fill(upmixer, 1, true, true, header));
            assertThrows(MediaException.Malformed.class, () -> fill(upmixer, 1, true, true, header));
            assertFalse(upmixer.configured());
        }
    }

    @Nested
    @DisplayName("3D stereo downmixes")
    class ThreeDimensional {
        static final int BLOCK = 1024;

        /** The fields of a {@code SpatialSpecificConfig} around its HRTF parameters. */
        static Bits fields(int tree, int slots, int frequencyResolution, boolean matrixMode, boolean threeDimensional) {
            var bits = new Bits().put(3, 4).put(slots - 1, 7).put(frequencyResolution, 3).put(tree, 4).put(0, 2)
                    .put(0, 1).put(0, 1).put(2, 3).put(1, 3).put(0, 3).put(matrixMode ? 1 : 0, 1).put(0, 2).put(0, 2)
                    .put(threeDimensional ? 1 : 0, 1);
            if (tree == MpsConfig.TREE_525) {
                bits.put(4, 5).put(0, 1).put(1, 3);
            } else {
                bits.put(4, 5);
            }
            return bits;
        }

        /** The stereo tree's configuration of the {@code mps-3d525} and {@code mps-3d-large} frames. */
        static Bits fields(int frequencyResolution, boolean matrixMode, boolean threeDimensional) {
            return fields(MpsConfig.TREE_525, 16, frequencyResolution, matrixMode, threeDimensional);
        }

        /** Levels and phases that follow from the channel and band alone, offset to set the gain of the HRTFs. */
        record Hrtf(int frequencyResolution, int channels, boolean asymmetric, boolean phase, boolean icc, int offset) {
            static final Hrtf BASE = new Hrtf(4, 5, true, true, true, 30);

            int left(int channel, int band) {
                return offset + (7 * channel + 3 * band) % 11;
            }

            int right(int channel, int band) {
                return asymmetric ? offset + 2 + (5 * channel + 2 * band) % 9 : left(channel, band);
            }

            int phase(int channel, int band) {
                return (channel + band) % 8;
            }

            Bits write(Bits bits) {
                bits.put(0, 2).put(frequencyResolution, 3).put(channels, 4).put(asymmetric ? 1 : 0, 1);
                var bands = new int[]{0, 28, 20, 14, 10, 7, 5, 4}[frequencyResolution];
                for (var channel = 0; channel < channels; channel++) {
                    for (var band = 0; band < bands; band++) {
                        bits.put(left(channel, band), 6);
                    }
                    if (asymmetric) {
                        for (var band = 0; band < bands; band++) {
                            bits.put(right(channel, band), 6);
                        }
                    }
                    bits.put(phase ? 1 : 0, 1);
                    for (var band = 0; phase && band < bands; band++) {
                        bits.put(phase(channel, band), 6);
                    }
                    bits.put(icc ? 1 : 0, 1);
                    for (var band = 0; icc && band < bands; band++) {
                        bits.put((channel * band) % 8, 6);
                    }
                }
                return bits;
            }
        }

        static byte[] header(byte[] specific) {
            var bits = new Bits().put(0, 1);
            if (specific.length < 127) {
                bits.put(specific.length, 7);
            } else {
                bits.put(127, 7).put(specific.length - 127, 16);
            }
            for (var value : specific) {
                bits.put(value & 0xFF, 8);
            }
            return bits.bytes();
        }

        static float[][] noise(int channels, int length) {
            var random = new java.util.Random(23);
            var planes = new float[channels][length];
            for (var c = 0; c < channels; c++) {
                var state = 0.0f;
                for (var i = 0; i < length; i++) {
                    state += 0.05f * ((float) random.nextGaussian() - state);
                    planes[c][i] = 0.3f * state + 0.1f * (float) Math.sin(i * 0.02 * (c + 1));
                }
            }
            return planes;
        }

        static float[][] upmixWith(byte[] specific, String frames) {
            var spatial = blocks(resource(frames + ".bs"));
            var blocks = new ArrayList<byte[]>();
            blocks.add(header(specific));
            blocks.addAll(spatial.subList(1, spatial.size()));
            var config = MpegSurroundTest.config(blocks.getFirst());
            var length = (blocks.size() - 1) * config.frameLength() * 64;
            return upmix(blocks, noise(config.inputChannels(), length), 48000);
        }

        static float[][] upmix3d(Hrtf hrtf, String frames, int parameterResolution) {
            return upmixWith(hrtf.write(fields(parameterResolution, false, true)).bytes(), frames);
        }

        static float[][] upmix3d(Hrtf hrtf) {
            return upmix3d(hrtf, "mps-3d525", 4);
        }

        static double energy(float[][] planes) {
            var sum = 0.0;
            for (var plane : planes) {
                for (var value : plane) {
                    assertTrue(Float.isFinite(value), "non-finite sample");
                    sum += value * value;
                }
            }
            return sum;
        }

        static float[][] encodedStereo(int blocks, List<byte[]> units, byte[][] config) {
            try (var encoder = new com.github.auties00.smalt.audio.aac.AacEncoder(AacEncoderOptions.of(48000,
                    ChannelLayout.STEREO, 128000))) {
                var noise = noise(2, blocks * BLOCK);
                encoder.encode(new AudioBuffer(ChannelLayout.STEREO, 48000, noise, noise[0].length),
                        packet -> units.add(packet.data()));
                encoder.flush(packet -> units.add(packet.data()));
                config[0] = encoder.codecConfig().orElseThrow();
                return noise;
            }
        }

        static AacTestSupport.Stream stereoCore(int blocks) {
            var units = new ArrayList<byte[]>();
            var config = new byte[1][];
            encodedStereo(blocks, units, config);
            return new AacTestSupport.Stream(config[0], List.copyOf(units.subList(0, blocks)), 48000, 2);
        }

        @Test
        @DisplayName("reads the header of more than 126 bytes and the HRTF parameters of a stream")
        void reads() {
            var header = blocks(resource("mps-3d525.bs")).getFirst();
            assertEquals(127, header[0] & 0x7F);
            var config = MpegSurroundTest.config(header);
            assertEquals(2, config.inputChannels());
            assertEquals(6, config.outputChannels());
        }

        @Test
        @DisplayName("divides the upmix by the gain of the HRTFs, the inverse of what the downmix went through")
        void invertsTheGain() {
            var base = upmix3d(Hrtf.BASE);
            var louder = upmix3d(new Hrtf(4, 5, true, true, true, 50));
            assertTrue(energy(base) > 0);
            for (var c = 0; c < base.length; c++) {
                var peak = 0.0f;
                for (var value : base[c]) {
                    peak = Math.max(peak, Math.abs(value));
                }
                for (var i = 0; i < base[c].length; i++) {
                    assertEquals(base[c][i] / 10.0f, louder[c][i], peak * 1e-3f, "channel " + c + " sample " + i);
                }
            }
        }

        @Test
        @DisplayName("upmixes differently from the same stream without the 3D stereo signaling")
        void differsFromPlain() {
            var plain = upmixWith(fields(4, false, false).bytes(), "mps-3d525");
            var inverted = upmix3d(Hrtf.BASE);
            assertFalse(Arrays.deepEquals(plain, inverted));
            var difference = new float[plain.length][plain[0].length];
            for (var c = 0; c < plain.length; c++) {
                for (var i = 0; i < plain[c].length; i++) {
                    difference[c][i] = plain[c][i] - inverted[c][i];
                }
            }
            assertTrue(energy(difference) > 0.1 * energy(plain));
        }

        @Test
        @DisplayName("takes right levels equal to the left ones the same whether they are coded or implied")
        void symmetricLevels() {
            var bits = fields(4, false, true).put(0, 2).put(4, 3).put(5, 4).put(1, 1);
            var implied = new Hrtf(4, 5, false, true, true, 30);
            for (var channel = 0; channel < 5; channel++) {
                for (var pass = 0; pass < 2; pass++) {
                    for (var band = 0; band < 10; band++) {
                        bits.put(implied.left(channel, band), 6);
                    }
                }
                bits.put(1, 1);
                for (var band = 0; band < 10; band++) {
                    bits.put(implied.phase(channel, band), 6);
                }
                bits.put(0, 1);
            }
            assertArrayEquals(upmix3d(implied), upmixWith(bits.bytes(), "mps-3d525"));
        }

        @Test
        @DisplayName("takes the phases of a clear flag as zero, and ignores the channel ICCs")
        void flags() {
            var noPhase = new Hrtf(4, 5, true, false, true, 30);
            var bits = fields(4, false, true).put(0, 2).put(4, 3).put(5, 4).put(1, 1);
            for (var channel = 0; channel < 5; channel++) {
                for (var band = 0; band < 10; band++) {
                    bits.put(noPhase.left(channel, band), 6);
                }
                for (var band = 0; band < 10; band++) {
                    bits.put(noPhase.right(channel, band), 6);
                }
                bits.put(1, 1);
                for (var band = 0; band < 10; band++) {
                    bits.put(0, 6);
                }
                bits.put(0, 1);
            }
            assertArrayEquals(upmix3d(noPhase), upmixWith(bits.bytes(), "mps-3d525"));
            assertArrayEquals(upmix3d(Hrtf.BASE), upmix3d(new Hrtf(4, 5, true, true, false, 30)));
            assertFalse(Arrays.deepEquals(upmix3d(Hrtf.BASE), upmix3d(noPhase)));
        }

        @Test
        @DisplayName("applies the phases to the bands below 12 only")
        void phasedBands() {
            var flat = new Hrtf(1, 5, true, false, false, 30);
            var plain = upmix3d(flat, "mps-3d-large", 1);
            for (var phased : new boolean[]{false, true}) {
                var bits = fields(1, false, true).put(0, 2).put(1, 3).put(5, 4).put(1, 1);
                for (var channel = 0; channel < 5; channel++) {
                    for (var band = 0; band < 28; band++) {
                        bits.put(flat.left(channel, band), 6);
                    }
                    for (var band = 0; band < 28; band++) {
                        bits.put(flat.right(channel, band), 6);
                    }
                    bits.put(1, 1);
                    for (var band = 0; band < 28; band++) {
                        bits.put(band < 12 == phased ? 5 : 0, 6);
                    }
                    bits.put(0, 1);
                }
                var upmix = upmixWith(bits.bytes(), "mps-3d-large");
                if (phased) {
                    assertFalse(Arrays.deepEquals(plain, upmix));
                } else {
                    assertArrayEquals(plain, upmix);
                }
            }
        }

        @Test
        @DisplayName("uses the first five channels and the bands up to the parameter bands of larger HRTF tables")
        void largerTables() {
            assertArrayEquals(upmix3d(Hrtf.BASE), upmix3d(new Hrtf(4, 9, true, true, true, 30)));
            // the levels of the first bands of each resolution follow from the channel and the band alike
            assertArrayEquals(upmix3d(Hrtf.BASE), upmix3d(new Hrtf(1, 5, true, true, true, 30)));
        }

        @Test
        @DisplayName("gives the matrix inversion precedence over the 3D stereo inversion")
        void matrixInversionFirst() {
            var plain = upmixWith(fields(4, true, false).bytes(), "mps-3d525");
            var both = upmixWith(Hrtf.BASE.write(fields(4, true, true)).bytes(), "mps-3d525");
            assertArrayEquals(plain, both);
            assertFalse(Arrays.deepEquals(plain, upmix3d(Hrtf.BASE)));
        }

        @Test
        @DisplayName("skips the HRTF parameters of a mono tree, which it does not invert, of any size")
        void monoTree() {
            var plain = upmixWith(fields(MpsConfig.TREE_5151, 32, 1, false, false).bytes(), "mps-long5151");
            var channels = new Hrtf(7, 2, true, true, true, 30);
            assertArrayEquals(plain, upmixWith(channels.write(fields(MpsConfig.TREE_5151, 32, 1, false, true)).bytes(),
                    "mps-long5151"));
        }

        @Test
        @DisplayName("regularizes a product of rank one, from HRTFs equal at both ears, to a finite upmix")
        void rankOne() {
            var upmix = upmix3d(new Hrtf(4, 5, false, false, false, 30));
            assertTrue(energy(upmix) > 0);
        }

        @ParameterizedTest(name = "set {0}")
        @ValueSource(ints = {1, 2, 3})
        @DisplayName("rejects the HRTF sets the stream does not carry, and outputs the downmix")
        void otherSets(int set) {
            var specific = fields(4, false, true).put(set, 2).bytes();
            assertThrows(MediaException.Unsupported.class,
                    () -> MpsConfig.readAudioSpecificConfig(surroundConfig(false, specific)));
            var core = stereoCore(4);
            var frames = blocks(resource("mps-3d525.bs")).subList(1, 5);
            var decoded = decodeSeparate(core, surroundConfig(false, specific), frames);
            assertEquals(ChannelLayout.STEREO, decoded.layout());
            assertSameUpmix(decode(core, false).planes(), decoded.planes());
        }

        @ParameterizedTest(name = "resolution {0}, {1} channels")
        @CsvSource({"4, 0", "4, 4", "5, 5", "7, 5"})
        @DisplayName("rejects HRTF parameters with fewer channels or bands than a stereo tree inverts with")
        void tooFew(int resolution, int channels) {
            var specific = new Hrtf(resolution, channels, true, true, true, 30).write(fields(4, false, true)).bytes();
            assertThrows(MediaException.Unsupported.class,
                    () -> MpsConfig.readAudioSpecificConfig(surroundConfig(false, specific)));
        }

        @Test
        @DisplayName("rejects the reserved HRTF frequency resolution and truncated parameters as malformed")
        void malformed() {
            var reserved = fields(4, false, true).put(0, 2).put(0, 3).put(5, 4).put(0, 1).bytes();
            assertThrows(MediaException.Malformed.class, () -> MpegSurroundTest.config(header(reserved)));
            var whole = Hrtf.BASE.write(fields(4, false, true)).bytes();
            for (var length = whole.length - 1; length >= 8; length -= 7) {
                var cut = Arrays.copyOf(whole, length);
                assertThrows(MediaException.Malformed.class,
                        () -> MpegSurroundTest.config(header(cut)), "length " + length);
            }
        }

        @Test
        @DisplayName("keeps every upmix finite, or rejects the configuration, when the bits of its header flip")
        void flippedHeader() {
            var whole = header(Hrtf.BASE.write(fields(4, false, true)).bytes());
            var frames = blocks(resource("mps-3d525.bs")).subList(1, 3);
            var input = noise(2, 2 * BLOCK);
            for (var bit = 8 * 3; bit < 8 * whole.length; bit += 11) {
                var flipped = whole.clone();
                flipped[bit >>> 3] ^= (byte) (0x80 >>> (bit & 7));
                try {
                    var blocks = new ArrayList<byte[]>();
                    blocks.add(flipped);
                    blocks.addAll(frames);
                    var config = MpegSurroundTest.config(flipped);
                    if (config.inputChannels() == 2 && config.samplingFrequency() == 48000) {
                        energy(upmix(blocks, input, 48000));
                    }
                } catch (MediaException expected) {
                    // an invalid configuration or a frame that no longer parses under it
                }
            }
        }

        @Test
        @DisplayName("upmixes the frames of a separate stream, under a header of over 127 bytes, as the spatial decoder")
        void separateStream() {
            var spatial = blocks(resource("mps-3d525.bs"));
            var core = stereoCore(spatial.size() - 1);
            var downmix = decode(core, false);
            var decoded = decodeSeparate(core, surroundConfig(false, specificConfig(spatial)),
                    spatial.subList(1, spatial.size()));
            assertEquals(ChannelLayout.SURROUND_5_1, decoded.layout());
            assertSameUpmix(upmix(spatial, downmix.planes(), 48000), decoded.planes());
        }

        @Test
        @DisplayName("upmixes frames embedded in fill elements, under a header of over 127 bytes, as the spatial decoder")
        void embedded() {
            var spatial = blocks(resource("mps-3d525.bs"));
            var core = stereoCore(spatial.size() - 1);
            var downmix = decode(core, false);
            var muxed = embed(core, spatial, 1);
            var decoded = decode(muxed, true);
            assertEquals(ChannelLayout.SURROUND_5_1, decoded.layout());
            assertSameUpmix(upmix(spatial, downmix.planes(), 48000), decoded.planes());
        }

        @Test
        @DisplayName("reads a header too long for one fill element from the fragments it spans")
        void headerAcrossFragments() {
            var spatial = blocks(resource("mps-3d-large.bs"));
            var header = spatial.getFirst();
            assertTrue(header.length > 15 + 255 - 1, "header of " + header.length + " bytes");
            var input = noise(2, BLOCK);
            var upmixer = new MpsUpmixer();
            var frame = spatial.get(1);
            LongFrames.fill(upmixer, 1, true, false, Arrays.copyOfRange(header, 0, 200));
            var rest = Arrays.copyOf(Arrays.copyOfRange(header, 200, header.length),
                    header.length - 200 + frame.length);
            System.arraycopy(frame, 0, rest, header.length - 200, frame.length);
            LongFrames.fill(upmixer, 1, false, true, rest);
            assertTrue(upmixer.prepare(48000, BLOCK));
            assertTrue(upmixer.upmixed());
            var output = new float[6][BLOCK];
            var expected = upmix(spatial.subList(0, 2), input, 48000);
            upmixer.upmix(input, output);
            assertSameUpmix(expected, output);
        }
    }

    @Nested
    @DisplayName("the limits of the arbitrary tree")
    class ArbitraryTreeLimits {
        /** Appends an arbitrary tree extension to a configuration without extensions. */
        static byte[] withTree(byte[] specific, int[] flags, int[][] boxes, int outputs) {
            var tree = new Bits();
            for (var flag : flags) {
                tree.put(flag, 1);
            }
            for (var box : boxes) {
                tree.put(box[0], 1).put(box[1], 1);
                if (box[1] != 0) {
                    tree.put(box[2], 5);
                }
            }
            for (var position = 0; position < outputs; position++) {
                tree.put(position, 5);
            }
            var bytes = tree.bytes();
            var config = new Bits();
            for (var value : specific) {
                config.put(value & 0xFF, 8);
            }
            config.put(2, 4);
            if (bytes.length < 15) {
                config.put(bytes.length, 4);
            } else {
                config.put(15, 4).put(bytes.length - 15, 8);
            }
            for (var value : bytes) {
                config.put(value & 0xFF, 8);
            }
            return config.bytes();
        }

        /** Appends the extension frame of boxes on their default CLDs to the frames of a stream. */
        static List<byte[]> withDefaultClds(List<byte[]> frames) {
            var units = new ArrayList<byte[]>();
            for (var frame : frames) {
                // two bits per box and parameter set: at most 26 boxes of 8 sets
                var unit = Arrays.copyOf(frame, frame.length + 1 + 60);
                unit[frame.length] = 60;
                units.add(unit);
            }
            return units;
        }

        static int[] flags(int boxes, boolean deep) {
            var flags = new ArrayList<Integer>();
            if (deep) {
                for (var i = 0; i < boxes; i++) {
                    flags.add(1);
                }
                for (var i = 0; i <= boxes; i++) {
                    flags.add(0);
                }
            } else {
                for (var i = 0; i < boxes; i++) {
                    flags.add(1);
                    flags.add(0);
                }
                flags.add(0);
            }
            for (var ch = 1; ch < 6; ch++) {
                flags.add(0);
            }
            return flags.stream().mapToInt(Integer::intValue).toArray();
        }

        @ParameterizedTest(name = "nested in depth: {0}")
        @ValueSource(booleans = {false, true})
        @DisplayName("splits a channel into the 32 output channels of the extension on default CLDs")
        void thirtyTwoOutputs(boolean deep) {
            var spatial = blocks(resource("mps-long5151.bs"));
            var specific = MpegSurroundTest.specificConfig(spatial);
            assertEquals(6, specific.length);
            var boxes = 26;
            var defaults = new int[boxes][3];
            var tree = surroundConfig(false, withTree(specific, flags(boxes, deep), defaults, 32));
            var parsed = MpsConfig.readAudioSpecificConfig(tree).config();
            assertTrue(parsed.arbitraryTreeActive());
            assertEquals(32, parsed.outputChannels());
            var core = head(stream("mps-lc5151-core"), 12);
            var frames = spatial.subList(1, 7);
            var base = decodeBlocks(core, surroundConfig(false, specific), frames, 2, false);
            var split = decodeBlocks(core, tree, withDefaultClds(frames), 2, false);
            var positions = new int[32];
            for (var p = 0; p < 32; p++) {
                positions[p] = p;
            }
            var speakers = OutputChannelPositions.speakers(positions);
            var order = OutputChannelPositions.waveOrder(speakers);
            // each box halves the power of what goes through it, so the gain of a leaf follows from its depth
            var gains = new double[32];
            for (var leaf = 0; leaf <= boxes; leaf++) {
                var depth = deep ? (leaf == 0 ? boxes : boxes + 1 - leaf) : Math.min(leaf + 1, boxes);
                gains[leaf] = StrictMath.pow(0.5, depth / 2.0);
            }
            for (var leaf = boxes + 1; leaf < 32; leaf++) {
                gains[leaf] = 1;
            }
            // the gains ramp up over the first frame and the synthesis bank holds its samples for some time after
            for (var block = 5; block < split.size(); block++) {
                var planes = split.get(block).planes();
                assertEquals(32, planes.length, "block " + block);
                assertEquals(32, split.get(block).layout().channels(), "block " + block);
                var reference = base.get(block).planes();
                for (var leaf = 0; leaf < 32; leaf++) {
                    var source = reference[leaf <= boxes ? 0 : leaf - boxes];
                    for (var i = 0; i < source.length; i++) {
                        assertEquals(gains[leaf] * source[i], planes[order[leaf]][i], 2e-4,
                                "block " + block + " leaf " + leaf + " sample " + i);
                    }
                }
            }
        }

        @Test
        @DisplayName("passes the channel through both outputs of a low frequency box of no bands")
        void lowFrequencyBoxOfNoBands() {
            var spatial = blocks(resource("mps-long5151.bs"));
            var specific = MpegSurroundTest.specificConfig(spatial);
            var tree = surroundConfig(false, withTree(specific, new int[]{1, 0, 0, 0, 0, 0, 0, 0},
                    new int[][]{{0, 1, 0}}, 7));
            var core = head(stream("mps-lc5151-core"), 8);
            var frames = spatial.subList(1, 5);
            var base = decodeBlocks(core, surroundConfig(false, specific), frames, 2, false);
            var split = decodeBlocks(core, tree, withDefaultClds(frames), 2, false);
            var order = OutputChannelPositions.waveOrder(
                    OutputChannelPositions.speakers(new int[]{0, 1, 2, 3, 4, 5, 6}));
            for (var block = 1; block < split.size(); block++) {
                var planes = split.get(block).planes();
                var reference = base.get(block).planes();
                for (var i = 0; i < reference[0].length; i++) {
                    assertEquals(reference[0][i], planes[order[0]][i], 2e-4, "first output, sample " + i);
                    assertEquals(reference[0][i], planes[order[1]][i], 2e-4, "second output, sample " + i);
                    assertEquals(reference[5][i], planes[order[6]][i], 2e-4, "last channel, sample " + i);
                }
            }
        }

        @ParameterizedTest(name = "{0}")
        @ValueSource(strings = {"mps-at71.bs", "mps-long5151.bs", "mps-short5151.bs", "mps-res96.bs"})
        @DisplayName("fails on damaged side information only with a media exception")
        void damagedSideInformation(String name) {
            var spatial = blocks(resource(name));
            var random = new java.util.Random(name.hashCode());
            for (var round = 0; round < 150; round++) {
                var header = spatial.getFirst().clone();
                for (var flip = random.nextInt(3); flip >= 0; flip--) {
                    var bit = 8 + random.nextInt(8 * (header.length - 1));
                    header[bit >> 3] ^= (byte) (1 << (bit & 7));
                }
                try {
                    run(config(header), spatial, random, 0);
                } catch (MediaException _) {
                    // what the test allows
                }
            }
            for (var length = 1; length < spatial.getFirst().length; length++) {
                try {
                    config(Arrays.copyOf(spatial.getFirst(), length));
                } catch (MediaException _) {
                    // what the test allows
                }
            }
            var config = config(spatial.getFirst());
            for (var round = 0; round < 300; round++) {
                run(config, spatial, random, 1 + random.nextInt(4));
            }
        }

        /** Upmixes a few blocks with frames that take {@code flips} damaged bits, whose failures are allowed. */
        void run(MpsConfig config, List<byte[]> spatial, java.util.Random random, int flips) {
            var upmixer = new MpsUpmixer();
            upmixer.configure(config);
            var block = new float[config.inputChannels()][1024];
            var out = new float[config.outputChannels()][1024];
            for (var k = 0; k < 4; k++) {
                var frame = spatial.get(1 + k % (spatial.size() - 1)).clone();
                for (var flip = 0; flip < flips; flip++) {
                    var bit = random.nextInt(8 * frame.length);
                    frame[bit >> 3] ^= (byte) (1 << (bit & 7));
                }
                try {
                    upmixer.addFrame(frame, 0, frame.length);
                    if (upmixer.prepare(config.samplingFrequency(), 1024)) {
                        upmixer.upmix(block, out);
                    }
                } catch (MediaException _) {
                    // what the test allows
                }
            }
        }
    }

    @Nested
    @DisplayName("residual frames of more than 2048 QMF samples")
    class LargeResidualFrames {
        @Test
        @DisplayName("upmixes residual frames of 32 slots at 128 QMF subbands, which alone reach the output of a silent "
                + "downmix")
        void residualAlone() {
            var spatial = blocks(resource("mps-res96.bs"));
            var config = config(spatial.getFirst());
            assertEquals(96000, config.samplingFrequency());
            var decoder = new MpsDecoder(config, 96000);
            assertEquals(128, decoder.qmfBands());
            assertEquals(4096, decoder.frameLength());
            var frames = spatial.size() - 1;
            var upmix = upmix(spatial, new float[1][frames * decoder.frameLength()], 96000);
            assertEquals(6, upmix.length);
            var energy = 0.0;
            for (var channel : upmix) {
                for (var value : channel) {
                    assertTrue(Float.isFinite(value));
                    energy += value * value;
                }
            }
            assertTrue(energy > 0.0, "the residual signals take no part in the upmix");
        }

        @Test
        @DisplayName("upmixes them in AAC blocks of the downmix, a frame spanning four blocks")
        void inBlocks() {
            var spatial = blocks(resource("mps-res96.bs"));
            var upmixer = new MpsUpmixer();
            upmixer.configure(config(spatial.getFirst()));
            var block = new float[1][1024];
            var out = new float[6][1024];
            var upmixed = 0;
            var energy = 0.0;
            for (var k = 0; k < 4 * (spatial.size() - 1); k++) {
                if (k % 4 == 0) {
                    var frame = spatial.get(1 + k / 4);
                    upmixer.addFrame(frame, 0, frame.length);
                }
                if (upmixer.prepare(96000, 1024)) {
                    upmixer.upmix(block, out);
                    if (upmixer.upmixed()) {
                        upmixed++;
                        for (var channel : out) {
                            for (var value : channel) {
                                energy += value * value;
                            }
                        }
                    }
                }
            }
            assertTrue(upmixed > 0, "the upmixer outputs the downmix");
            assertTrue(energy > 0.0, "the residual signals take no part in the upmix");
        }
    }
}
