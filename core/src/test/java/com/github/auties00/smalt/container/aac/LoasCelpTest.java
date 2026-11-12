package com.github.auties00.smalt.container.aac;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.audio.ChannelLayout;
import com.github.auties00.smalt.container.MuxSupport;
import com.github.auties00.smalt.container.internal.Latm;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.internal.codec.AudioConfigs;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.Track;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers CELP in LOAS/LATM (ISO/IEC 14496-3 clause 1.7): streams written bit by bit with each CELP and HVXC frame
 * length type (Tables 1.46 to 1.48), whose payloads of the first stream the demuxer must return bit exact (the last
 * byte padded with zero bits) on a CELP track with the frame duration of the configuration; ER CELP with the error
 * protection tool, whose {@code ErrorProtectionSpecificConfig} the track's configuration keeps bit exact; and the
 * CELP tracks {@link LoasMuxer} writes, read back by the demuxer.
 */
@DisplayName("LOAS with CELP")
class LoasCelpTest {
    /**
     * Writes the {@code AudioSpecificConfig} of a CELP base layer at 8 kHz with the multi-pulse coder;
     * {@code MPE_Configuration} 0 codes 320 samples per frame.
     */
    private static void celpBase(MsbBitWriter writer, int objectType, int configuration) {
        writer.write(objectType, 5);
        writer.write(11, 4);
        writer.write(1, 4);
        writer.write(1, 1);
        writer.write(0, 1);
        writer.write(0, 1);
        writer.write(0, objectType == 24 ? 2 : 1);
        writer.write(configuration, 5);
        writer.write(0, 2);
        writer.write(0, 1);
        if (objectType == 24) {
            writer.write(0, 2);
        }
    }

    /**
     * Writes the {@code AudioSpecificConfig} of the first CELP bitrate scalable layer.
     */
    private static void celpEnhancement(MsbBitWriter writer) {
        writer.write(8, 5);
        writer.write(11, 4);
        writer.write(1, 4);
        writer.write(0, 1);
        writer.write(0, 1);
        writer.write(1, 2);
    }

    /**
     * One stream of a test multiplex.
     *
     * @param frameLengthType {@code frameLengthType}
     * @param tableIndex the frame length table index, or the {@code frameLength} of type 1
     * @param lengths the payload length in bits per {@code MuxSlotLengthCoded}, all equal for a fixed length
     */
    private record Layer(int frameLengthType, int tableIndex, int[] lengths) {
    }

    /**
     * Writes an {@code AudioSyncStream} frame holding one {@code AudioMuxElement} with its configuration.
     *
     * @param objectType the object type of the CELP base layer, 8 or 24
     * @param layers the streams: a CELP base layer, then CELP bitrate scalable layers
     * @param payloads the payload of each stream
     * @param payloadBits the payload length in bits of each stream
     * @param coded the {@code MuxSlotLengthCoded} of each stream
     * @return the frame
     */
    private static byte[] frame(int objectType, List<Layer> layers, byte[][] payloads, long[] payloadBits,
                                int[] coded) {
        var writer = new MsbBitWriter();
        writer.write(0, 1);
        writer.write(0, 1);
        writer.write(1, 1);
        writer.write(0, 6);
        writer.write(0, 4);
        writer.write(layers.size() - 1, 3);
        for (var index = 0; index < layers.size(); index++) {
            if (index > 0) {
                writer.write(0, 1);
                celpEnhancement(writer);
            } else {
                celpBase(writer, objectType, 0);
            }
            var layer = layers.get(index);
            writer.write(layer.frameLengthType(), 3);
            switch (layer.frameLengthType()) {
                case 0 -> writer.write(0xFF, 8);
                case 1 -> writer.write(layer.tableIndex(), 9);
                case 3, 4, 5 -> writer.write(layer.tableIndex(), 6);
                default -> writer.write(layer.tableIndex(), 1);
            }
        }
        writer.write(0, 1);
        writer.write(0, 1);
        for (var index = 0; index < layers.size(); index++) {
            switch (layers.get(index).frameLengthType()) {
                case 0 -> {
                    var bytes = (int) (payloadBits[index] / 8);
                    while (bytes >= 255) {
                        writer.write(255, 8);
                        bytes -= 255;
                    }
                    writer.write(bytes, 8);
                }
                case 3, 5, 7 -> writer.write(coded[index], 2);
                default -> {
                }
            }
        }
        for (var index = 0; index < layers.size(); index++) {
            var bits = payloadBits[index];
            var data = payloads[index];
            for (var bit = 0L; bit < bits; bit++) {
                writer.write((data[(int) (bit >>> 3)] >> (7 - (bit & 7))) & 1, 1);
            }
        }
        writer.alignWithZeros();
        var element = writer.toByteArray();
        var out = new ByteArrayOutputStream();
        out.write(0x56);
        out.write(0xE0 | (element.length >> 8));
        out.write(element.length & 0xFF);
        out.writeBytes(element);
        return out.toByteArray();
    }

    /**
     * Returns random payload bits, the bits after {@code bits} cleared.
     */
    private static byte[] payload(Random random, long bits) {
        var data = new byte[(int) ((bits + 7) / 8)];
        random.nextBytes(data);
        if ((bits & 7) != 0) {
            data[data.length - 1] &= (byte) (0xFF << (8 - (bits & 7)));
        }
        return data;
    }

    @ParameterizedTest(name = "frameLengthType {0} of the base layer, index {1}")
    @CsvSource({"4, 0", "4, 61", "3, 7", "5, 30", "0, 0", "1, 9"})
    @DisplayName("returns the payloads of a CELP base layer bit exact, whatever the frame length type")
    void baseLayer(int type, int index) {
        var lengths = switch (type) {
            case 4 -> index == 0 ? new int[]{154, 154, 154, 154} : new int[]{338, 338, 338, 338};
            case 3 -> new int[]{122, 100, 0, 0};
            case 5 -> new int[]{244, 40, 8, 2};
            case 1 -> new int[]{232, 232, 232, 232};
            default -> new int[]{160, 160, 160, 160};
        };
        var layers = List.of(new Layer(type, index, lengths), new Layer(4, 4, new int[]{368, 368, 368, 368}));
        var random = new Random(type * 64L + index);
        var file = new ByteArrayOutputStream();
        var expected = new ArrayList<byte[]>();
        for (var frame = 0; frame < 12; frame++) {
            var coded = new int[]{type == 3 ? frame % 2 : type == 5 ? frame % 4 : 0, 0};
            var bits = new long[]{lengths[coded[0]], 368};
            var payloads = new byte[][]{payload(random, bits[0]), payload(random, bits[1])};
            expected.add(payloads[0]);
            file.writeBytes(frame(8, layers, payloads, bits, coded));
        }
        try (var demuxer = new LoasDemuxer(MediaSource.of(file.toByteArray()))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.CELP, track.codec());
            assertEquals(8000, track.sampleRate());
            assertEquals(ChannelLayout.MONO, track.layout().orElseThrow());
            assertEquals(8000, AudioConfigs.celpSampleRate(track.codecConfig().orElseThrow()));
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            assertEquals(expected.size(), packets.size());
            for (var i = 0; i < packets.size(); i++) {
                assertArrayEquals(expected.get(i), packets.get(i).data(), "payload " + i);
                assertEquals(320, packets.get(i).duration(), "duration " + i);
                assertEquals(320L * i, packets.get(i).pts(), "pts " + i);
            }
        }
    }

    @Test
    @DisplayName("reads the configuration of an ER CELP base layer without error protection")
    void errorResilient() {
        var random = new Random(24);
        var layers = List.of(new Layer(5, 0, new int[]{156, 23, 8, 2}));
        var file = new ByteArrayOutputStream();
        var expected = new ArrayList<byte[]>();
        for (var frame = 0; frame < 8; frame++) {
            var coded = new int[]{frame % 4};
            var bits = new long[]{layers.getFirst().lengths()[coded[0]]};
            var payloads = new byte[][]{payload(random, bits[0])};
            expected.add(payloads[0]);
            file.writeBytes(frame(24, layers, payloads, bits, coded));
        }
        try (var demuxer = new LoasDemuxer(MediaSource.of(file.toByteArray()))) {
            assertEquals(Codec.CELP, demuxer.tracks().getFirst().codec());
            for (var i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i), demuxer.read().data(), "payload " + i);
            }
        }
    }

    /**
     * Writes the {@code AudioSpecificConfig} of an 8 kHz ER CELP base layer with an {@code epConfig} and, for 2 and
     * 3, an {@code ErrorProtectionSpecificConfig} of one pre-defined set of two classes: 40 bits under a 3-bit
     * CRC, then the rest of the frame, the classes output in reverse order, with or without header protection.
     *
     * @param epConfig the {@code epConfig}
     * @param header whether the configuration protects the EP frame header
     * @return the configuration, byte aligned
     */
    private static byte[] protectedConfig(int epConfig, boolean header) {
        var writer = new MsbBitWriter();
        writer.write(24, 5);
        writer.write(11, 4);
        writer.write(1, 4);
        writer.write(1, 1);
        writer.write(0, 1);
        writer.write(0, 1);
        writer.write(0, 2);
        writer.write(0, 5);
        writer.write(0, 2);
        writer.write(0, 1);
        writer.write(epConfig, 2);
        if (epConfig >= 2) {
            writer.write(1, 8);
            writer.write(0, 2);
            writer.write(1, 3);
            writer.write(1, 3);
            writer.write(2, 6);
            for (var c = 0; c < 2; c++) {
                writer.write(c, 1);
                writer.write(0, 1);
                writer.write(0, 1);
                writer.write(0, 2);
                writer.write(0, 1);
                writer.write(0, 1);
                if (c == 0) {
                    writer.write(40, 16);
                } else {
                    writer.write(0, 4);
                }
                writer.write(0, 5);
                writer.write(c == 0 ? 3 : 0, 5);
            }
            writer.write(1, 1);
            writer.write(1, 6);
            writer.write(0, 6);
            writer.write(header ? 1 : 0, 1);
            if (header) {
                writer.write(8, 5);
                writer.write(6, 5);
            }
            if (epConfig == 3) {
                writer.write(1, 1);
            }
        }
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    /**
     * Writes an {@code AudioSyncStream} frame of one stream with its configuration.
     *
     * @param config the {@code AudioSpecificConfig}, its bits up to {@code configBits}
     * @param configBits the length of the configuration in bits
     * @param frameLengthType 0 or 4
     * @param payload the payload, a whole number of bytes
     * @return the frame
     */
    private static byte[] singleStream(byte[] config, long configBits, int frameLengthType, byte[] payload) {
        var writer = new MsbBitWriter();
        writer.write(0, 1);
        writer.write(0, 1);
        writer.write(1, 1);
        writer.write(0, 6);
        writer.write(0, 4);
        writer.write(0, 3);
        for (var bit = 0L; bit < configBits; bit++) {
            writer.write((config[(int) (bit >>> 3)] >> (7 - (bit & 7))) & 1, 1);
        }
        writer.write(frameLengthType, 3);
        if (frameLengthType == 0) {
            writer.write(0xFF, 8);
        } else {
            writer.write(0, 6);
        }
        writer.write(0, 1);
        writer.write(0, 1);
        if (frameLengthType == 0) {
            writer.write(payload.length, 8);
        }
        for (var value : payload) {
            writer.write(value & 0xFF, 8);
        }
        writer.alignWithZeros();
        var element = writer.toByteArray();
        var out = new ByteArrayOutputStream();
        out.write(0x56);
        out.write(0xE0 | (element.length >> 8));
        out.write(element.length & 0xFF);
        out.writeBytes(element);
        return out.toByteArray();
    }

    @ParameterizedTest(name = "starts with CELP: {0}")
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    @DisplayName("skips the frames of a configuration of the other codec family, keeping the codec of the track")
    void otherFamily(boolean celpFirst) {
        var celp = new MsbBitWriter();
        celpBase(celp, 8, 0);
        var celpBits = celp.bitCount();
        celp.alignWithZeros();
        var celpConfig = celp.toByteArray();
        // AAC LC at 8 kHz, mono
        var aacConfig = new byte[]{0x15, (byte) 0x88};
        var file = new ByteArrayOutputStream();
        var expected = new ArrayList<byte[]>();
        for (var frame = 0; frame < 6; frame++) {
            var payload = new byte[20];
            java.util.Arrays.fill(payload, (byte) (frame + 1));
            var first = frame < 2 || frame >= 4;
            if (first) {
                expected.add(payload);
            }
            var isCelp = first == celpFirst;
            file.writeBytes(isCelp ? singleStream(celpConfig, celpBits, 0, payload)
                    : singleStream(aacConfig, 16, 0, payload));
        }
        try (var demuxer = new LoasDemuxer(MediaSource.of(file.toByteArray()))) {
            assertEquals(celpFirst ? Codec.CELP : Codec.AAC, demuxer.tracks().getFirst().codec());
            for (var i = 0; i < expected.size(); i++) {
                var packet = demuxer.read();
                assertArrayEquals(expected.get(i), packet.data(), "payload " + i);
                assertEquals(java.util.Optional.empty(), packet.sideData().newCodecConfig(), "payload " + i);
            }
            assertNull(demuxer.read());
        }
    }

    @ParameterizedTest(name = "epConfig {0}, header protection {1}")
    @CsvSource({"2, false", "2, true", "3, false", "3, true"})
    @DisplayName("keeps the error protection configuration of an ER CELP stream and returns its EP frames")
    void errorProtected(int epConfig, boolean header) {
        var config = protectedConfig(epConfig, header);
        var configBits = Latm.audioSpecificConfigBits(config);
        assertTrue(configBits > 8L * (config.length - 1), "the configuration is read to its last byte: " + configBits);
        var random = new Random(epConfig * 2L + (header ? 1 : 0));
        var file = new ByteArrayOutputStream();
        var expected = new ArrayList<byte[]>();
        for (var frame = 0; frame < 10; frame++) {
            var payload = payload(random, 8L * (20 + frame));
            expected.add(payload);
            file.writeBytes(singleStream(config, configBits, 0, payload));
        }
        try (var demuxer = new LoasDemuxer(MediaSource.of(file.toByteArray()))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.CELP, track.codec());
            assertArrayEquals(config, track.codecConfig().orElseThrow());
            for (var i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i), demuxer.read().data(), "payload " + i);
            }
        }
    }

    @Test
    @DisplayName("rejects error protected payloads with a CELP frame length table, and epConfig 1")
    void errorProtectedRefusals() {
        var config = protectedConfig(3, false);
        var bits = Latm.audioSpecificConfigBits(config);
        var table = singleStream(config, bits, 4, new byte[20]);
        assertThrows(MediaException.Malformed.class, () -> new LoasDemuxer(MediaSource.of(table)).close());
        assertEquals(-1, Latm.audioSpecificConfigBits(protectedConfig(1, false)));
    }

    @ParameterizedTest(name = "epConfig {0}")
    @CsvSource({"0", "2", "3"})
    @DisplayName("writes a CELP track that the demuxer reads back with its configuration and access units")
    void muxer(int epConfig) {
        var config = protectedConfig(epConfig, epConfig == 3);
        var track = AudioTrack.builder().id(1).codec(Codec.CELP).timeBase(Rational.timeBaseOf(8000))
                .sampleRate(8000).codecConfig(config).build();
        var random = new Random(epConfig);
        var packets = new ArrayList<Packet>();
        for (var frame = 0; frame < 30; frame++) {
            packets.add(new Packet(1, payload(random, 8L * (18 + frame % 5)), 320L * frame, 320L * frame, 320, true));
        }
        var output = MuxSupport.toMemory(List.<Track>of(track), packets, LoasMuxer::new);
        try (var demuxer = new LoasDemuxer(MediaSource.of(output))) {
            var read = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.CELP, read.codec());
            assertEquals(8000, read.sampleRate());
            assertArrayEquals(config, read.codecConfig().orElseThrow());
            for (var i = 0; i < packets.size(); i++) {
                var packet = demuxer.read();
                assertArrayEquals(packets.get(i).data(), packet.data(), "access unit " + i);
                assertEquals(320L * i, packet.pts(), "pts " + i);
            }
            assertNull(demuxer.read());
        }
    }

    @Test
    @DisplayName("refuses a CELP enhancement layer track, which LOAS cannot carry alone, and epConfig 1")
    void muxerRefusals() {
        var writer = new MsbBitWriter();
        celpEnhancement(writer);
        writer.alignWithZeros();
        var track = AudioTrack.builder().id(1).codec(Codec.CELP).timeBase(Rational.timeBaseOf(8000))
                .sampleRate(8000).codecConfig(writer.toByteArray()).build();
        assertThrows(IllegalArgumentException.class, () -> new LoasMuxer(MediaSink.memory()).addTrack(track));
        assertTrue(LoasMuxer.supports(Codec.CELP));
        var separate = track.toBuilder().codecConfig(protectedConfig(1, false)).build();
        assertThrows(IllegalArgumentException.class, () -> new LoasMuxer(MediaSink.memory()).addTrack(separate));
    }
}
