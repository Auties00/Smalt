package com.github.auties00.smalt.container.ts;

import com.github.auties00.smalt.internal.bits.MsbBitWriter;
import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.track.VideoTrack;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers VC-1 advanced profile streams in transport streams ({@code stream_type 0xEA}) built from hand-made
 * sequence headers, entry points and frame units: the track the sequence header describes, the packetization of
 * frames split over PES packets, and the key frames the entry points mark. Decoding through the transport stream is
 * covered against ffmpeg by the decoder's conformance test.
 */
@DisplayName("MPEG-TS VC-1")
class TsVc1Test {
    private static final byte[] ENTRY_POINT = {0, 0, 1, 0x0E, 0x4C, (byte) 0x8A, (byte) 0xF0, (byte) 0x80};
    private static final byte[] FRAME = {0, 0, 1, 0x0D, 0x12, 0x34, 0x56};
    private static final byte[] FIELD = {0, 0, 1, 0x0C, 0x78, (byte) 0x9A};

    /**
     * Builds a 1920x1080 interlaced advanced profile sequence header with a display extension of a 16:11 sample
     * aspect ratio at 30000/1001 frames per second.
     */
    static byte[] sequenceHeader() {
        var bits = new MsbBitWriter();
        bits.write(3, 2);
        bits.write(3, 3);
        bits.write(1, 2);
        bits.write(0, 3);
        bits.write(0, 5);
        bits.write(0, 1);
        bits.write(1920 / 2 - 1, 12);
        bits.write(1080 / 2 - 1, 12);
        bits.write(0, 1);
        bits.write(1, 1);
        bits.write(0, 1);
        bits.write(0, 1);
        bits.write(1, 1);
        bits.write(0, 1);
        bits.write(1, 1);
        bits.write(1919, 14);
        bits.write(1079, 14);
        bits.write(1, 1);
        bits.write(4, 4);
        bits.write(1, 1);
        bits.write(0, 1);
        bits.write(3, 8);
        bits.write(2, 4);
        bits.write(0, 1);
        bits.write(0, 1);
        bits.writeTrailingBits();
        var body = bits.toByteArray();
        var header = new byte[4 + body.length];
        header[2] = 1;
        header[3] = 0x0F;
        System.arraycopy(body, 0, header, 4, body.length);
        return header;
    }

    private static byte[] concat(byte[]... parts) {
        return TsWriter.concat(parts);
    }

    private static TsWriter stream() {
        return new TsWriter().pat(1, 0x1000, 0).pmt(0x1000, 1, 0, 0x100, new byte[0],
                List.of(new TsWriter.Stream(0xEA, 0x100)));
    }

    @Test
    @DisplayName("describes the track from the sequence header of the first key frame")
    void track() {
        var headers = concat(sequenceHeader(), ENTRY_POINT);
        var writer = stream();
        writer.pes(0x100, 0xFD, 0, -1, concat(headers, FRAME), true);
        writer.pes(0x100, 0xFD, 3003, -1, FRAME, false);
        var demuxed = TsTesting.demux(writer.toByteArray());
        var track = (VideoTrack) demuxed.tracks().getFirst();
        assertEquals(Codec.VC1, track.codec());
        assertEquals(1920, track.width());
        assertEquals(1080, track.height());
        assertEquals(new Rational(16, 11), track.sampleAspectRatio().orElseThrow());
        assertEquals(new Rational(30000, 1001), track.frameRate().orElseThrow());
        assertArrayEquals(headers, track.codecConfig().orElseThrow());
    }

    @Test
    @DisplayName("joins a PES packet that starts with a field start code to the frame before it")
    void continuation() {
        var headers = concat(sequenceHeader(), ENTRY_POINT);
        var writer = stream();
        writer.pes(0x100, 0xFD, 0, -1, concat(headers, FRAME), true);
        writer.pes(0x100, 0xFD, -1, -1, FIELD, false);
        writer.pes(0x100, 0xFD, 3003, -1, FRAME, false);
        writer.pes(0x100, 0xFD, 6006, -1, concat(ENTRY_POINT, FRAME), true);
        var demuxed = TsTesting.demux(writer.toByteArray());
        var packets = demuxed.packetsOf(demuxed.tracks().getFirst());
        assertEquals(3, packets.size());
        assertArrayEquals(concat(headers, FRAME, FIELD), packets.getFirst().data());
        assertTrue(packets.get(0).keyframe());
        assertFalse(packets.get(1).keyframe());
        assertTrue(packets.get(2).keyframe());
        assertEquals(3003, packets.get(1).pts() - packets.get(0).pts());
    }

    @Test
    @DisplayName("times a frame whose PES packet carries no timestamp one frame after the frame before it")
    void untimedFrame() {
        var headers = concat(sequenceHeader(), ENTRY_POINT);
        var writer = stream();
        writer.pes(0x100, 0xFD, 0, -1, concat(headers, FRAME), true);
        writer.pes(0x100, 0xFD, 3003, -1, FRAME, false);
        writer.pes(0x100, 0xFD, -1, -1, FRAME, false);
        var demuxed = TsTesting.demux(writer.toByteArray());
        var packets = demuxed.packetsOf(demuxed.tracks().getFirst());
        assertEquals(3, packets.size());
        var last = packets.getLast();
        assertEquals(packets.get(1).dts() + 3003, last.dts());
        assertEquals(last.dts(), last.pts());
        assertEquals(3003, last.duration());
    }

    @Test
    @DisplayName("drops the frames before the first key frame with a sequence header")
    void leading() {
        var headers = concat(sequenceHeader(), ENTRY_POINT);
        var writer = stream();
        writer.pes(0x100, 0xFD, 0, -1, FRAME, false);
        writer.pes(0x100, 0xFD, 3003, -1, concat(headers, FRAME), true);
        writer.pes(0x100, 0xFD, 6006, -1, FRAME, false);
        var demuxed = TsTesting.demux(writer.toByteArray());
        var packets = demuxed.packetsOf(demuxed.tracks().getFirst());
        assertEquals(2, packets.size());
        assertTrue(packets.getFirst().keyframe());
        assertArrayEquals(concat(headers, FRAME), packets.getFirst().data());
    }

    @Test
    @DisplayName("parses the sequence header through emulation prevention and rejects other profiles")
    void sequenceParsing() {
        var header = sequenceHeader();
        var sequence = Vc1Parser.sequence(header);
        assertEquals(new Vc1Parser.Sequence(1920, 1080, new Rational(30000, 1001), new Rational(16, 11)), sequence);
        var main = header.clone();
        main[4] = (byte) (main[4] & 0x3F | 0x40);
        assertNull(Vc1Parser.sequence(main));
        assertNull(Vc1Parser.sequence(FRAME));
        assertArrayEquals(concat(header, ENTRY_POINT), Vc1Parser.config(concat(header, ENTRY_POINT, FRAME)));
        assertNull(Vc1Parser.config(concat(ENTRY_POINT, FRAME)));
        assertArrayEquals(Arrays.copyOf(header, header.length), Vc1Parser.config(header));
    }
}
