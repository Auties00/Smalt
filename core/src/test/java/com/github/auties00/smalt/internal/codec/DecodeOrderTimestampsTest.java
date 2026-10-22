package com.github.auties00.smalt.internal.codec;

import com.github.auties00.smalt.track.Codec;
import com.github.auties00.smalt.track.Packet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("Decoding-order timestamps")
class DecodeOrderTimestampsTest {
    private static Packet packet(long pts, long dts) {
        return new Packet(1, new byte[1], pts, dts, 1, false);
    }

    @Test
    @DisplayName("exist only for the codecs that reorder frames")
    void codecs() {
        assertNotNull(DecodeOrderTimestamps.of(Codec.H264));
        assertNotNull(DecodeOrderTimestamps.of(Codec.HEVC));
        assertNotNull(DecodeOrderTimestamps.of(Codec.MPEG4_PART2));
        assertNull(DecodeOrderTimestamps.of(Codec.VP9));
        assertNull(DecodeOrderTimestamps.of(Codec.AV1));
    }

    @Test
    @DisplayName("give frames returned in presentation order the sorted timestamps of the decoding-order packets")
    void restores() {
        var restore = DecodeOrderTimestamps.of(Codec.H264);
        // I0 P3 B1 B2 in decoding order, stamped 0..3 as AVI stamps them; the decoder returns I0 B1 B2 P3
        // carrying the packets' timestamps 0, 2, 3, 1.
        for (var index = 0; index < 4; index++) {
            restore.note(packet(index, index));
        }
        var restored = new ArrayList<Long>();
        for (var pts : new long[]{0, 2, 3, 1}) {
            restored.add(restore.presentation(pts));
        }
        assertEquals(List.of(0L, 1L, 2L, 3L), restored);
        assertEquals(9L, restore.presentation(9), "a frame beyond the packets keeps its timestamp");
    }

    @Test
    @DisplayName("leave the timestamps of a stream that stores reordering untouched")
    void inactive() {
        var restore = DecodeOrderTimestamps.of(Codec.H264);
        restore.note(packet(2, 0));
        restore.note(packet(0, 1));
        assertEquals(5L, restore.presentation(5));
        var noDts = DecodeOrderTimestamps.of(Codec.HEVC);
        noDts.note(packet(40, Packet.NO_TIMESTAMP));
        assertEquals(7L, noDts.presentation(7));
    }

    @Test
    @DisplayName("forget a rejected packet and everything on a seek")
    void discardAndClear() {
        var restore = DecodeOrderTimestamps.of(Codec.H264);
        for (var index = 0; index < 3; index++) {
            restore.note(packet(index, index));
        }
        restore.discard(1);
        assertEquals(0L, restore.presentation(0));
        assertEquals(2L, restore.presentation(2));
        restore.note(packet(7, 7));
        restore.clear();
        assertEquals(11L, restore.presentation(11));
        assertEquals(Packet.NO_TIMESTAMP, restore.presentation(Packet.NO_TIMESTAMP));
    }

    @Test
    @DisplayName("hold more pending timestamps than the initial capacity")
    void grows() {
        var restore = DecodeOrderTimestamps.of(Codec.H264);
        for (var index = 99; index >= 0; index--) {
            restore.note(packet(index, index));
        }
        for (var index = 0; index < 100; index++) {
            assertEquals(index, restore.presentation(-1));
        }
    }
}
