package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.container.ContainerProbe;
import com.github.auties00.smalt.internal.nal.NalRecords;
import com.github.auties00.smalt.io.MediaSink;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The layered HEVC configuration of multi-layer tracks ({@code lhvC}, ISO/IEC 14496-15 clause 9.6.3):
 * the demuxer appends its parameter sets to the track's {@code hvcC} configuration, the muxer writes
 * the parameter sets of the layers above the base layer back to an {@code lhvC} box, and the record
 * helpers merge and split the two records.
 */
@DisplayName("MP4 layered HEVC configurations")
class Mp4LayeredHevcTest {
    /** Returns the sample HEVC movie. */
    private static byte[] movie() throws IOException {
        try (var in = Mp4LayeredHevcTest.class.getResourceAsStream("/samples/mp4/hevc.mp4")) {
            assertNotNull(in, "the sample movie");
            return in.readAllBytes();
        }
    }

    /** Returns a copy of a NAL unit with its nuh_layer_id set. */
    private static byte[] withLayer(byte[] nal, int layer) {
        var copy = nal.clone();
        copy[0] = (byte) ((copy[0] & 0xFE) | (layer >> 5));
        copy[1] = (byte) ((copy[1] & 0x07) | ((layer & 0x1F) << 3));
        return copy;
    }

    /** Builds an lhvC record holding the given NAL units, one array per unit. */
    private static byte[] lhvC(List<byte[]> units) {
        var out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{1, (byte) 0xF0, 0, (byte) 0xFC, (byte) 0xCF, (byte) units.size()});
        for (var unit : units) {
            out.write(0x80 | ((unit[0] >> 1) & 0x3F));
            out.write(0);
            out.write(1);
            out.write(unit.length >> 8);
            out.write(unit.length);
            out.writeBytes(unit);
        }
        return out.toByteArray();
    }

    /** Returns the layer of each NAL unit of an hvcC record. */
    private static List<Integer> layers(byte[] hvcC) {
        var result = new ArrayList<Integer>();
        for (var unit : NalRecords.parseHevcStrict(hvcC).parameterSets()) {
            result.add(((unit[0] & 1) << 5) | ((unit[1] & 0xFF) >> 3));
        }
        return result;
    }

    @Test
    @DisplayName("merging appends the layered arrays and splitting restores both records")
    void mergeAndSplit() throws IOException {
        ContainerProbe.Demuxed demuxed;
        try (var source = MediaSource.of(movie()); var demuxer = new Mp4Demuxer(source)) {
            demuxed = ContainerProbe.demuxAll(demuxer);
        }
        var hvcC = demuxed.tracks().getFirst().codecConfig().orElseThrow();
        var base = NalRecords.parseHevcStrict(hvcC).parameterSets();
        var upper = base.stream().filter(unit -> ((unit[0] >> 1) & 0x3F) != 32).map(unit -> withLayer(unit, 1)).toList();
        var layered = lhvC(upper);
        var merged = NalRecords.appendLayeredHevc(hvcC, layered);
        assertEquals(base.size() + upper.size(), NalRecords.parseHevcStrict(merged).parameterSets().size());
        assertEquals(base.size(), layers(merged).stream().filter(layer -> layer == 0).count());
        assertEquals(upper.size(), layers(merged).stream().filter(layer -> layer == 1).count());
        var split = NalRecords.splitLayeredHevc(merged);
        assertArrayEquals(hvcC, split[0], "the base record");
        assertNotNull(split[1]);
        assertArrayEquals(upper.toArray(byte[][]::new),
                NalRecords.parseHevcStrict(NalRecords.appendLayeredHevc(new byte[23], split[1])).parameterSets()
                        .toArray(byte[][]::new), "the layered units");
        assertEquals(hvcC[21] & 3, split[1][4] & 3, "the layered record keeps the length size");
        assertNull(NalRecords.splitLayeredHevc(hvcC)[1], "a single layer record has no layered part");
        assertSame(hvcC, NalRecords.appendLayeredHevc(hvcC, new byte[]{1, 2}), "a truncated lhvC is ignored");
        var truncated = layered.clone();
        truncated[5] = (byte) (upper.size() + 3);
        assertSame(hvcC, NalRecords.appendLayeredHevc(hvcC, truncated), "arrays past the record are ignored");
    }

    @Test
    @DisplayName("a muxed multi-layer track carries an lhvC box that demuxes back into the merged configuration")
    void remux() throws IOException {
        var packets = new ArrayList<Packet>();
        VideoTrack track;
        try (var source = MediaSource.of(movie()); var demuxer = new Mp4Demuxer(source)) {
            track = (VideoTrack) demuxer.tracks().getFirst();
            Packet packet;
            while ((packet = demuxer.read()) != null) {
                if (packet.trackId() == track.id()) {
                    packets.add(packet);
                }
            }
        }
        var hvcC = track.codecConfig().orElseThrow();
        var upper = NalRecords.parseHevcStrict(hvcC).parameterSets().stream()
                .filter(unit -> ((unit[0] >> 1) & 0x3F) != 32).map(unit -> withLayer(unit, 1)).toList();
        var merged = NalRecords.appendLayeredHevc(hvcC, lhvC(upper));
        var out = new ByteArrayOutputStream();
        try (var muxer = new Mp4Muxer(MediaSink.of(out), Mp4MuxerOptions.defaults())) {
            muxer.addTrack(track.toBuilder().codecConfig(merged).build());
            for (var packet : packets) {
                muxer.writePacket(packet);
            }
            muxer.finish();
        }
        var file = out.toByteArray();
        assertTrue(indexOf(file, "lhvC") > 0, "the sample entry has an lhvC box");
        try (var source = MediaSource.of(file); var demuxer = new Mp4Demuxer(source)) {
            var again = demuxer.tracks().getFirst().codecConfig().orElseThrow();
            assertArrayEquals(merged, again, "the merged configuration survives the round trip");
        }
    }

    /** Returns the index of the first occurrence of a four-character code in a file, or -1. */
    private static int indexOf(byte[] data, String code) {
        var pattern = code.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (var i = 0; i + pattern.length <= data.length; i++) {
            for (var k = 0; k < pattern.length; k++) {
                if (data[i + k] != pattern[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
