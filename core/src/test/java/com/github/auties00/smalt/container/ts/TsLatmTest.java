package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.container.Containers;
import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.track.AudioTrack;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers LOAS/LATM layouts beyond the single-program one ffmpeg writes: an {@code AudioSpecificConfig} with a
 * program config element (channel configuration zero, from an ffmpeg ADTS stream whose layout has no channel
 * configuration), several programs and layers, chunked payloads and fixed frame lengths. The streams are built
 * field by field from ISO/IEC 14496-3 clause 1.7.3 and must read as the ADTS equivalent does.
 */
@DisplayName("MPEG-TS LATM")
class TsLatmTest {
    private static final int PID = 0x101;

    /** Returns the ADTS demux of an ffmpeg quad AAC stream, which carries a program config element. */
    static ContainerProbe.Demuxed adts() {
        var file = Fixtures.ffmpeg("aac", List.of("-f", "lavfi", "-i", "anoisesrc=d=1:r=48000:a=0.1"),
                List.of("-af", "pan=quad|c0=c0|c1=0.5*c0|c2=0.2*c0|c3=0.1*c0", "-c:a", "aac", "-f", "adts"));
        return ContainerProbe.demux(file, Containers::openDemuxer);
    }

    /** Returns the number of bits of a byte-aligned AudioSpecificConfig with a program config element. */
    static int ascBits(byte[] asc) {
        var bits = new MsbBitReader(asc);
        bits.skip(5 + 4);
        assertEquals(0, bits.read(4), "channel configuration zero");
        bits.skip(3 + 4 + 2 + 4);
        var front = bits.read(4);
        var side = bits.read(4);
        var back = bits.read(4);
        var lfe = bits.read(2);
        var assoc = bits.read(3);
        var cc = bits.read(4);
        for (var i = 0; i < 2; i++) {
            if (bits.readFlag()) {
                bits.skip(4);
            }
        }
        if (bits.readFlag()) {
            bits.skip(3);
        }
        bits.skip(5L * (front + side + back) + 4L * (lfe + assoc) + 5L * cc);
        bits.alignToByte();
        var comment = bits.read(8);
        bits.skip(8L * comment);
        return (int) bits.bitPosition();
    }

    /** Copies bits of a byte array. */
    static void copy(MsbBitWriter writer, byte[] data, int bits) {
        var reader = new MsbBitReader(data);
        for (var index = 0; index < bits; index++) {
            writer.writeBit(reader.readBit());
        }
    }

    /** Writes bytes at any bit position. */
    static void bytes(MsbBitWriter writer, byte[] data, int offset, int length) {
        for (var index = offset; index < offset + length; index++) {
            writer.write(data[index] & 0xFF, 8);
        }
    }

    /** Writes a LatmGetValue field. */
    static void latmValue(MsbBitWriter writer, long value) {
        var bytes = value < 0x100 ? 1 : value < 0x10000 ? 2 : 3;
        writer.write(bytes - 1, 2);
        for (var index = bytes - 1; index >= 0; index--) {
            writer.write((int) (value >> (8 * index)) & 0xFF, 8);
        }
    }

    /** Writes a MuxSlotLengthBytes field. */
    static void slotLength(MsbBitWriter writer, int length) {
        while (length >= 255) {
            writer.write(255, 8);
            length -= 255;
        }
        writer.write(length, 8);
    }

    /** One stream of a StreamMuxConfig: its configuration and frame length type. */
    record Layer(byte[] asc, int ascBits, int frameLengthType, int frameLength) {
    }

    /** Writes a StreamMuxConfig, after useSameStreamMux. */
    static void streamMuxConfig(MsbBitWriter writer, int version, boolean sameTimeFraming, List<List<Layer>> programs) {
        streamMuxConfig(writer, version, sameTimeFraming, 1, programs);
    }

    /** Writes a StreamMuxConfig of several subframes, after useSameStreamMux. */
    static void streamMuxConfig(MsbBitWriter writer, int version, boolean sameTimeFraming, int subframes,
                                List<List<Layer>> programs) {
        writer.write(version, 1);
        if (version == 1) {
            writer.write(0, 1);
            latmValue(writer, 0);
        }
        writer.write(sameTimeFraming ? 1 : 0, 1);
        writer.write(subframes - 1, 6);
        writer.write(programs.size() - 1, 4);
        for (var program = 0; program < programs.size(); program++) {
            var layers = programs.get(program);
            writer.write(layers.size() - 1, 3);
            for (var index = 0; index < layers.size(); index++) {
                var layer = layers.get(index);
                if (program != 0 || index != 0) {
                    writer.write(0, 1);
                }
                if (version == 1) {
                    // the length covers the configuration's padding to a byte, which the reader skips
                    latmValue(writer, layer.asc().length * 8L);
                    copy(writer, layer.asc(), layer.asc().length * 8);
                } else {
                    copy(writer, layer.asc(), layer.ascBits());
                }
                writer.write(layer.frameLengthType(), 3);
                if (layer.frameLengthType() == 0) {
                    writer.write(0xFF, 8);
                } else {
                    writer.write(layer.frameLength() - 20, 9);
                }
            }
        }
        writer.write(0, 1);
        writer.write(0, 1);
    }

    /** Wraps AudioMuxElements into LOAS frames and the frames into a transport stream of stream type 0x11. */
    static byte[] transportStream(List<byte[]> elements) {
        var writer = new TsWriter().pat(1, 0x1000, 0).pmt(0x1000, 1, 0, PID, new byte[0],
                List.of(new TsWriter.Stream(0x11, PID)));
        for (var index = 0; index < elements.size(); index += 2) {
            var pes = new ByteArrayOutputStream();
            for (var element = index; element < Math.min(index + 2, elements.size()); element++) {
                pes.writeBytes(loas(elements.get(element)));
            }
            writer.pes(PID, 0xC0, 90_000 + index * 1920L, -1, pes.toByteArray(), true);
        }
        return writer.toByteArray();
    }

    /** Returns the LOAS frame of an AudioMuxElement. */
    static byte[] loas(byte[] element) {
        var frame = new byte[3 + element.length];
        frame[0] = 0x56;
        frame[1] = (byte) (0xE0 | element.length >> 8);
        frame[2] = (byte) element.length;
        System.arraycopy(element, 0, frame, 3, element.length);
        return frame;
    }

    /** Returns the payloads of a track's packets. */
    static List<byte[]> payloads(ContainerProbe.Demuxed demuxed) {
        return demuxed.packetsOf(demuxed.tracks().getFirst()).stream().map(Packet::data).toList();
    }

    static void assertPayloads(List<byte[]> expected, List<byte[]> actual) {
        assertEquals(expected.size(), actual.size(), "payload count");
        for (var index = 0; index < expected.size(); index++) {
            assertArrayEquals(expected.get(index), actual.get(index), "payload " + index);
        }
    }

    @ParameterizedTest(name = "audioMuxVersion {0}")
    @ValueSource(ints = {0, 1})
    @DisplayName("reads a program config element in the AudioSpecificConfig as the ADTS stream declares it")
    void programConfig(int version) {
        var adts = adts();
        var track = (AudioTrack) adts.tracks().getFirst();
        var asc = track.codecConfig().orElseThrow();
        var raw = adts.packetsOf(track).stream().map(Packet::data).toList();
        var elements = new ArrayList<byte[]>();
        var layer = new Layer(asc, ascBits(asc), 0, 0);
        for (var index = 0; index < raw.size(); index++) {
            var writer = new MsbBitWriter(raw.get(index).length + 64);
            // the configuration rides in every fourth element and is reused in between
            var carries = index % 4 == 0;
            writer.write(carries ? 0 : 1, 1);
            if (carries) {
                streamMuxConfig(writer, version, true, List.of(List.of(layer)));
            }
            slotLength(writer, raw.get(index).length);
            bytes(writer, raw.get(index), 0, raw.get(index).length);
            writer.alignWithZeros();
            elements.add(writer.toByteArray());
        }
        var demuxed = TsTesting.demux(transportStream(elements));
        var latm = (AudioTrack) demuxed.tracks().getFirst();
        assertArrayEquals(asc, latm.codecConfig().orElseThrow(), "the ADTS demuxer's configuration");
        assertEquals(track.sampleRate(), latm.sampleRate());
        assertEquals(track.layout(), latm.layout());
        assertPayloads(raw, payloads(demuxed));
        var packets = demuxed.packetsOf(latm);
        for (var index = 0; index < packets.size(); index++) {
            assertEquals(index * 1024L, packets.get(index).pts());
        }
    }

    /** Returns a mono AAC-LC configuration at 48 kHz. */
    static byte[] monoConfig() {
        return new byte[]{0x11, (byte) 0x88};
    }

    @ParameterizedTest(name = "audioMuxVersion {0}")
    @ValueSource(ints = {0, 1})
    @DisplayName("returns the first program's payloads of several programs and layers")
    void programs(int version) {
        var adts = adts();
        var track = (AudioTrack) adts.tracks().getFirst();
        var asc = track.codecConfig().orElseThrow();
        var raw = adts.packetsOf(track).stream().map(Packet::data).toList();
        var random = new Random(14496);
        var config = List.of(List.of(new Layer(asc, ascBits(asc), 0, 0)),
                List.of(new Layer(monoConfig(), 16, 0, 0), new Layer(monoConfig(), 16, 1, 40)));
        var elements = new ArrayList<byte[]>();
        for (var payload : raw) {
            var writer = new MsbBitWriter(payload.length + 256);
            writer.write(0, 1);
            streamMuxConfig(writer, version, true, config);
            var other = new byte[10 + random.nextInt(300)];
            random.nextBytes(other);
            var fixed = new byte[40];
            random.nextBytes(fixed);
            slotLength(writer, payload.length);
            slotLength(writer, other.length);
            bytes(writer, payload, 0, payload.length);
            bytes(writer, other, 0, other.length);
            bytes(writer, fixed, 0, fixed.length);
            writer.alignWithZeros();
            elements.add(writer.toByteArray());
        }
        var demuxed = TsTesting.demux(transportStream(elements));
        assertArrayEquals(asc, demuxed.tracks().getFirst().codecConfig().orElseThrow());
        assertPayloads(raw, payloads(demuxed));
    }

    @Test
    @DisplayName("reassembles chunked payloads of streams without the same time framing")
    void chunks() {
        var adts = adts();
        var track = (AudioTrack) adts.tracks().getFirst();
        var asc = track.codecConfig().orElseThrow();
        var raw = adts.packetsOf(track).stream().map(Packet::data).toList();
        var random = new Random(173);
        var config = List.of(List.of(new Layer(asc, ascBits(asc), 0, 0)), List.of(new Layer(monoConfig(), 16, 0, 0)));
        var elements = new ArrayList<byte[]>();
        for (var payload : raw) {
            var writer = new MsbBitWriter(payload.length + 256);
            writer.write(0, 1);
            streamMuxConfig(writer, 1, false, config);
            var split = payload.length / 3;
            var other = new byte[25];
            random.nextBytes(other);
            // chunks: stream 0 first part, stream 1 whole, stream 0 rest
            writer.write(2, 4);
            writer.write(0, 4);
            slotLength(writer, split);
            writer.write(0, 1);
            writer.write(1, 4);
            slotLength(writer, other.length);
            writer.write(1, 1);
            writer.write(0, 4);
            slotLength(writer, payload.length - split);
            writer.write(1, 1);
            bytes(writer, payload, 0, split);
            bytes(writer, other, 0, other.length);
            bytes(writer, payload, split, payload.length - split);
            writer.alignWithZeros();
            elements.add(writer.toByteArray());
        }
        assertPayloads(raw, payloads(TsTesting.demux(transportStream(elements))));
    }

    @Test
    @DisplayName("reassembles a chunked payload that continues in the next subframe")
    void chunksAcrossSubframes() {
        var adts = adts();
        var track = (AudioTrack) adts.tracks().getFirst();
        var asc = track.codecConfig().orElseThrow();
        var raw = adts.packetsOf(track).stream().map(Packet::data).toList();
        var config = List.of(List.of(new Layer(asc, ascBits(asc), 0, 0)));
        var elements = new ArrayList<byte[]>();
        var expected = new ArrayList<byte[]>();
        for (var index = 0; index + 1 < raw.size(); index += 2) {
            var first = raw.get(index);
            var second = raw.get(index + 1);
            expected.add(first);
            expected.add(second);
            var writer = new MsbBitWriter(first.length + second.length + 256);
            writer.write(0, 1);
            streamMuxConfig(writer, 0, false, 2, config);
            var split = first.length / 2;
            // subframe 0: the first half of the first payload, its AuEndFlag clear
            writer.write(0, 4);
            writer.write(0, 4);
            slotLength(writer, split);
            writer.write(0, 1);
            bytes(writer, first, 0, split);
            // subframe 1: the rest of the first payload, then the whole second one
            writer.write(1, 4);
            writer.write(0, 4);
            slotLength(writer, first.length - split);
            writer.write(1, 1);
            writer.write(0, 4);
            slotLength(writer, second.length);
            writer.write(1, 1);
            bytes(writer, first, split, first.length - split);
            bytes(writer, second, 0, second.length);
            writer.alignWithZeros();
            elements.add(writer.toByteArray());
        }
        assertPayloads(expected, payloads(TsTesting.demux(transportStream(elements))));
    }

    @Test
    @DisplayName("reads payloads of a fixed frame length")
    void fixedLength() {
        var random = new Random(9);
        var config = List.of(List.of(new Layer(new byte[]{0x11, (byte) 0x90}, 16, 1, 120)));
        var expected = new ArrayList<byte[]>();
        var elements = new ArrayList<byte[]>();
        for (var index = 0; index < 40; index++) {
            var payload = new byte[120];
            random.nextBytes(payload);
            expected.add(payload);
            var writer = new MsbBitWriter(200);
            writer.write(0, 1);
            streamMuxConfig(writer, 0, true, config);
            bytes(writer, payload, 0, payload.length);
            writer.alignWithZeros();
            elements.add(writer.toByteArray());
        }
        assertPayloads(expected, payloads(TsTesting.demux(transportStream(elements))));
    }

    @Test
    @DisplayName("does not expose a stream with a CELP frame length type")
    void celp() {
        var writer = new MsbBitWriter(64);
        writer.write(0, 1);
        writer.write(0, 1);
        writer.write(1, 1);
        writer.write(0, 6);
        writer.write(0, 4);
        writer.write(0, 3);
        copy(writer, new byte[]{0x11, (byte) 0x90}, 16);
        writer.write(3, 3);
        writer.write(0, 6);
        writer.write(0, 2);
        bytes(writer, new byte[20], 0, 20);
        writer.alignWithZeros();
        var element = writer.toByteArray();
        var ts = transportStream(List.of(element, element, element, element));
        var failure = assertThrows(MediaException.Malformed.class, () -> TsTesting.demux(ts));
        assertTrue(failure.getMessage().contains("no recognized elementary stream"), failure.getMessage());
    }
}
