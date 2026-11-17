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
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers HVXC in LOAS/LATM (ISO/IEC 14496-3 clause 1.7): streams written bit by bit with the HVXC frame length types
 * (Table 1.46), alone and over an enhancement layer, whose payloads of the first stream the demuxer must return bit
 * exact (the last byte padded with zero bits) on an HVXC track of 160 samples per frame; ER HVXC with and without
 * the error protection tool, whose configuration the track keeps bit exact; and the HVXC tracks {@link LoasMuxer}
 * writes, read back by the demuxer.
 */
@DisplayName("LOAS with HVXC")
class LoasHvxcTest {
    /**
     * Writes the {@code AudioSpecificConfig} of an HVXC base layer at 8 kHz.
     *
     * @param objectType 9 or 25
     * @param variable {@code HVXCvarMode}
     * @param rateMode {@code HVXCrateMode}
     * @param epConfig the {@code epConfig} of object type 25
     */
    private static void base(MsbBitWriter writer, int objectType, boolean variable, int rateMode, int epConfig) {
        writer.write(objectType, 5);
        writer.write(11, 4);
        writer.write(1, 4);
        writer.write(1, 1);
        writer.write(variable ? 1 : 0, 1);
        writer.write(rateMode, 2);
        writer.write(objectType == 25 ? 1 : 0, 1);
        if (objectType == 25) {
            writer.write(0, 1);
            writer.write(epConfig, 2);
        }
    }

    /**
     * Writes the {@code AudioSpecificConfig} of an HVXC enhancement layer.
     */
    private static void enhancement(MsbBitWriter writer) {
        writer.write(9, 5);
        writer.write(11, 4);
        writer.write(1, 4);
        writer.write(0, 1);
    }

    /**
     * Writes an {@code AudioSyncStream} frame of an HVXC base layer, and with {@code layers} 2 an enhancement layer
     * of the fixed 2 kbit/s length, in one {@code AudioMuxElement} with its configuration.
     */
    private static byte[] frame(boolean variable, int rateMode, int frameLengthType, int index, int layers,
                                byte[][] payloads, int[] bits, int coded) {
        var writer = new MsbBitWriter();
        writer.write(0, 1);
        writer.write(0, 1);
        writer.write(1, 1);
        writer.write(0, 6);
        writer.write(0, 4);
        writer.write(layers - 1, 3);
        base(writer, 9, variable, rateMode, 0);
        writer.write(frameLengthType, 3);
        switch (frameLengthType) {
            case 0 -> writer.write(0xFF, 8);
            case 1 -> writer.write(index, 9);
            default -> writer.write(index, 1);
        }
        if (layers == 2) {
            writer.write(0, 1);
            enhancement(writer);
            writer.write(6, 3);
            writer.write(0, 1);
        }
        writer.write(0, 1);
        writer.write(0, 1);
        switch (frameLengthType) {
            case 0 -> writer.write(bits[0] / 8, 8);
            case 7 -> writer.write(coded, 2);
            default -> {
            }
        }
        for (var layer = 0; layer < layers; layer++) {
            for (var bit = 0; bit < bits[layer]; bit++) {
                writer.write((payloads[layer][bit >>> 3] >> (7 - (bit & 7))) & 1, 1);
            }
        }
        return syncFrame(writer);
    }

    private static byte[] syncFrame(MsbBitWriter writer) {
        writer.alignWithZeros();
        var element = writer.toByteArray();
        var out = new ByteArrayOutputStream();
        out.write(0x56);
        out.write(0xE0 | (element.length >> 8));
        out.write(element.length & 0xFF);
        out.writeBytes(element);
        return out.toByteArray();
    }

    private static byte[] payload(Random random, int bits) {
        var data = new byte[(bits + 7) / 8];
        random.nextBytes(data);
        if ((bits & 7) != 0) {
            data[data.length - 1] &= (byte) (0xFF << (8 - (bits & 7)));
        }
        return data;
    }

    @ParameterizedTest(name = "frameLengthType {0}, index {1}, {2} layers")
    @CsvSource({"6, 0, 1", "6, 1, 1", "7, 0, 1", "7, 1, 1", "6, 0, 2", "7, 0, 2", "0, 0, 1", "1, 0, 1"})
    @DisplayName("returns the payloads of an HVXC base layer bit exact, whatever the frame length type")
    void baseLayer(int type, int index, int layers) {
        var lengths = switch (type) {
            case 6 -> index == 0 ? new int[]{40, 40, 40, 40} : new int[]{80, 80, 80, 80};
            case 7 -> index == 0 ? new int[]{40, 28, 2, 0} : new int[]{80, 40, 25, 3};
            case 1 -> new int[]{160, 160, 160, 160};
            default -> new int[]{40, 40, 40, 40};
        };
        var variable = type == 7;
        var rateMode = type == 0 || type == 1 || index == 0 ? 0 : 1;
        var random = new Random(type * 8L + index * 2L + layers);
        var file = new ByteArrayOutputStream();
        var expected = new ArrayList<byte[]>();
        for (var frame = 0; frame < 12; frame++) {
            var coded = type == 7 ? frame % (index == 0 ? 3 : 4) : 0;
            var bits = new int[]{lengths[coded], 40};
            var payloads = new byte[][]{payload(random, bits[0]), payload(random, bits[1])};
            expected.add(payloads[0]);
            file.writeBytes(frame(variable, rateMode, type, type == 1 ? 0 : index, layers, payloads, bits, coded));
        }
        try (var demuxer = new LoasDemuxer(MediaSource.of(file.toByteArray()))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.HVXC, track.codec());
            assertEquals(8000, track.sampleRate());
            assertEquals(ChannelLayout.MONO, track.layout().orElseThrow());
            assertEquals(8000, AudioConfigs.hvxcSampleRate(track.codecConfig().orElseThrow()));
            var packets = new ArrayList<Packet>();
            for (var packet = demuxer.read(); packet != null; packet = demuxer.read()) {
                packets.add(packet);
            }
            assertEquals(expected.size(), packets.size());
            for (var i = 0; i < packets.size(); i++) {
                assertArrayEquals(expected.get(i), packets.get(i).data(), "payload " + i);
                assertEquals(160, packets.get(i).duration(), "duration " + i);
                assertEquals(160L * i, packets.get(i).pts(), "pts " + i);
            }
        }
    }

    /**
     * Writes the {@code AudioSpecificConfig} of an 8 kHz ER HVXC base layer at 2 kbit/s with an {@code epConfig}
     * and, for 2 and 3, an {@code ErrorProtectionSpecificConfig} of one pre-defined set of two classes: 22 bits under
     * a 3-bit CRC, then the rest of the frame.
     */
    private static byte[] protectedConfig(int epConfig) {
        var writer = new MsbBitWriter();
        base(writer, 25, false, 0, epConfig);
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
                    writer.write(22, 16);
                } else {
                    writer.write(0, 4);
                }
                writer.write(0, 5);
                writer.write(c == 0 ? 3 : 0, 5);
            }
            writer.write(1, 1);
            writer.write(1, 6);
            writer.write(0, 6);
            writer.write(0, 1);
            if (epConfig == 3) {
                writer.write(1, 1);
            }
        }
        writer.alignWithZeros();
        return writer.toByteArray();
    }

    /**
     * Writes an {@code AudioSyncStream} frame of one stream with its configuration.
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
            writer.write(0, 1);
        }
        writer.write(0, 1);
        writer.write(0, 1);
        if (frameLengthType == 0) {
            writer.write(payload.length, 8);
        }
        for (var value : payload) {
            writer.write(value & 0xFF, 8);
        }
        return syncFrame(writer);
    }

    @ParameterizedTest(name = "epConfig {0}")
    @ValueSource(ints = {0, 2, 3})
    @DisplayName("keeps the configuration of an ER HVXC stream, error protected or not, and returns its frames")
    void errorResilient(int epConfig) {
        var config = protectedConfig(epConfig);
        var configBits = Latm.audioSpecificConfigBits(config);
        assertTrue(configBits > 8L * (config.length - 1), "the configuration is read to its last byte: " + configBits);
        assertEquals(160, Latm.audioSpecificConfigFrameSamples(config));
        var random = new Random(epConfig);
        var file = new ByteArrayOutputStream();
        var expected = new ArrayList<byte[]>();
        for (var frame = 0; frame < 10; frame++) {
            var payload = payload(random, 8 * (5 + frame % 3));
            expected.add(payload);
            file.writeBytes(singleStream(config, configBits, 0, payload));
        }
        try (var demuxer = new LoasDemuxer(MediaSource.of(file.toByteArray()))) {
            var track = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.HVXC, track.codec());
            assertArrayEquals(config, track.codecConfig().orElseThrow());
            for (var i = 0; i < expected.size(); i++) {
                assertArrayEquals(expected.get(i), demuxer.read().data(), "payload " + i);
            }
            assertNull(demuxer.read());
        }
    }

    @Test
    @DisplayName("rejects error protected payloads with an HVXC frame length table, epConfig 1 and the reserved rate")
    void refusals() {
        var config = protectedConfig(2);
        var table = singleStream(config, Latm.audioSpecificConfigBits(config), 6, new byte[5]);
        assertThrows(MediaException.Malformed.class, () -> new LoasDemuxer(MediaSource.of(table)).close());
        assertEquals(-1, Latm.audioSpecificConfigBits(protectedConfig(1)));
        var reserved = new MsbBitWriter();
        base(reserved, 9, false, 3, 0);
        reserved.alignWithZeros();
        assertEquals(-1, Latm.audioSpecificConfigBits(reserved.toByteArray()));
        var enhancement = new MsbBitWriter();
        enhancement(enhancement);
        enhancement.alignWithZeros();
        assertEquals(0, Latm.audioSpecificConfigFrameSamples(enhancement.toByteArray()));
    }

    @Test
    @DisplayName("skips the frames of a later CELP configuration, keeping the HVXC track")
    void otherCodec() {
        var hvxc = new MsbBitWriter();
        base(hvxc, 9, false, 0, 0);
        var hvxcBits = hvxc.bitCount();
        hvxc.alignWithZeros();
        var hvxcConfig = hvxc.toByteArray();
        // a CELP base layer at 8 kHz, multi-pulse MPE_Configuration 0
        var celpConfig = new byte[]{0x45, (byte) 0x8C, 0x00, 0x00};
        var file = new ByteArrayOutputStream();
        var expected = new ArrayList<byte[]>();
        for (var frame = 0; frame < 6; frame++) {
            var payload = new byte[5];
            Arrays.fill(payload, (byte) (frame + 1));
            var isHvxc = frame < 2 || frame >= 4;
            if (isHvxc) {
                expected.add(payload);
            }
            file.writeBytes(isHvxc ? singleStream(hvxcConfig, hvxcBits, 0, payload)
                    : singleStream(celpConfig, 25, 0, payload));
        }
        try (var demuxer = new LoasDemuxer(MediaSource.of(file.toByteArray()))) {
            assertEquals(Codec.HVXC, demuxer.tracks().getFirst().codec());
            for (var i = 0; i < expected.size(); i++) {
                var packet = demuxer.read();
                assertArrayEquals(expected.get(i), packet.data(), "payload " + i);
                assertEquals(Optional.empty(), packet.sideData().newCodecConfig(), "payload " + i);
            }
            assertNull(demuxer.read());
        }
    }

    @ParameterizedTest(name = "epConfig {0}")
    @ValueSource(ints = {0, 2, 3})
    @DisplayName("writes an HVXC track that the demuxer reads back with its configuration and access units")
    void muxer(int epConfig) {
        var config = protectedConfig(epConfig);
        var track = AudioTrack.builder().id(1).codec(Codec.HVXC).timeBase(Rational.timeBaseOf(8000))
                .sampleRate(8000).codecConfig(config).build();
        var random = new Random(10 + epConfig);
        var packets = new ArrayList<Packet>();
        for (var frame = 0; frame < 30; frame++) {
            packets.add(new Packet(1, payload(random, 8 * (5 + frame % 2)), 160L * frame, 160L * frame, 160, true));
        }
        var output = MuxSupport.toMemory(List.<Track>of(track), packets, LoasMuxer::new);
        try (var demuxer = new LoasDemuxer(MediaSource.of(output))) {
            var read = (AudioTrack) demuxer.tracks().getFirst();
            assertEquals(Codec.HVXC, read.codec());
            assertEquals(8000, read.sampleRate());
            assertArrayEquals(config, read.codecConfig().orElseThrow());
            for (var i = 0; i < packets.size(); i++) {
                var packet = demuxer.read();
                assertArrayEquals(packets.get(i).data(), packet.data(), "access unit " + i);
                assertEquals(160L * i, packet.pts(), "pts " + i);
            }
            assertNull(demuxer.read());
        }
        assertTrue(LoasMuxer.supports(Codec.HVXC));
    }

    @Test
    @DisplayName("refuses an HVXC enhancement layer track, which LOAS cannot carry alone")
    void muxerRefusals() {
        var writer = new MsbBitWriter();
        enhancement(writer);
        writer.alignWithZeros();
        var track = AudioTrack.builder().id(1).codec(Codec.HVXC).timeBase(Rational.timeBaseOf(8000))
                .sampleRate(8000).codecConfig(writer.toByteArray()).build();
        assertThrows(IllegalArgumentException.class, () -> new LoasMuxer(MediaSink.memory()).addTrack(track));
        var separate = track.toBuilder().codecConfig(protectedConfig(1)).build();
        assertThrows(IllegalArgumentException.class, () -> new LoasMuxer(MediaSink.memory()).addTrack(separate));
    }
}
