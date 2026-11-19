package com.github.auties00.smalt.video.h263.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.mpeg4.internal.BitReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses hand-written Enhanced Reference Picture Selection layers (Annex U): picture number re-mapping, the
 * memory management control operations with their sub-picture removal areas, and corrupt codes.
 */
@DisplayName("H.263 ERPS layer")
class ErpsLayerTest {
    static final class Bits {
        private final PictureHeaderTest.Bits bits = new PictureHeaderTest.Bits();

        Bits put(long value, int width) {
            bits.put(value, width);
            return this;
        }

        /**
         * Writes an unsigned value of the code of Table U.1.
         */
        Bits index(int value) {
            if (value == 0) {
                return put(1, 1);
            }
            var magnitude = value + 1;
            var count = 31 - Integer.numberOfLeadingZeros(magnitude);
            put(0, 1);
            for (var bit = count - 1; bit >= 0; bit--) {
                put(magnitude >>> bit & 1, 1).put(bit > 0 ? 1 : 0, 1);
            }
            return this;
        }

        ErpsLayer parse(int type, int spwi, int sphi) {
            var data = bits.bytes();
            var reader = new BitReader();
            reader.load(data, 0, data.length);
            var layer = new ErpsLayer();
            layer.parse(reader, type, 176, 144, spwi, sphi);
            return layer;
        }
    }

    @Test
    @DisplayName("reads every re-mapping kind and memory management operation")
    void full() {
        var bits = new Bits().put(1, 1)
                .put(1, 1).index(3)
                .put(0b10, 3).index(0)
                .put(0b011, 3).index(9);
        for (var index = 0; index < 8; index++) {
            bits.put(1, 1).index(index);
        }
        bits.put(0b001, 3)
                .put(0, 1)
                .put(0, 1).put(1, 1).put(1, 1).index(2)
                .put(0, 1).put(1, 1).put(0, 1).put(0, 1).index(5)
                .put(0, 1).put(1, 1).put(0, 1).put(1, 1).index(1).index(7)
                .put(0, 1).put(0, 1).put(1, 1).put(3, 2).put(3, 7).put(2, 7).index(4).put(1, 1)
                .put(0, 1).put(0, 1).put(1, 1).put(0, 2).index(6).put(0b1010, 4).put(0, 7).put(1, 1).put(0, 4)
                .put(0, 1).put(0, 1).put(1, 1).put(1, 2).index(0).put(0b1111, 4).put(0, 8).put(1, 1).put(0, 3)
                .put(0, 1).put(0, 1).put(1, 1).put(2, 2).index(11)
                .put(1, 1);
        var layer = bits.parse(PictureHeader.P, -1, -1);
        assertTrue(layer.multiple);
        assertEquals(11, layer.remaps);
        assertEquals(ErpsLayer.REMAP_NEGATIVE, layer.remapKinds[0]);
        assertEquals(4, layer.remapValues[0]);
        assertEquals(ErpsLayer.REMAP_POSITIVE, layer.remapKinds[1]);
        assertEquals(1, layer.remapValues[1]);
        assertEquals(ErpsLayer.REMAP_LONG_TERM, layer.remapKinds[2]);
        assertEquals(9, layer.remapValues[2]);
        assertFalse(layer.slidingWindow);
        assertEquals(7, layer.operations);
        assertEquals(ErpsLayer.MMCO_SHORT_UNUSED, layer.operationKinds[0]);
        assertEquals(2, layer.operationFirst[0]);
        assertEquals(ErpsLayer.MMCO_LONG_UNUSED, layer.operationKinds[1]);
        assertEquals(ErpsLayer.MMCO_ASSIGN_LONG, layer.operationKinds[2]);
        assertEquals(7, layer.operationSecond[2]);
        assertEquals(ErpsLayer.MMCO_BUFFER, layer.operationKinds[3]);
        assertEquals(3, layer.operationFirst[3]);
        assertEquals(5, layer.operationThird[3]);
        assertEquals(1, layer.operationFourth[3]);
        assertEquals(ErpsLayer.MMCO_SHORT_AREAS, layer.operationKinds[4]);
        var areas = layer.operationAreas[4];
        assertEquals(ErpsLayer.subPictures(176, 144, 3, 2), areas.length);
        assertTrue(areas[0]);
        assertFalse(areas[1]);
        assertEquals(ErpsLayer.MMCO_LONG_AREAS, layer.operationKinds[5]);
        assertEquals(ErpsLayer.MMCO_MAX_LONG, layer.operationKinds[6]);
        assertNull(layer.operationAreas[6]);
    }

    @Test
    @DisplayName("reads the sliding window of intra pictures and the backward flag of B pictures")
    void kinds() {
        assertTrue(new Bits().put(1, 1).parse(PictureHeader.I, -1, -1).slidingWindow);
        assertTrue(new Bits().put(1, 1).parse(PictureHeader.EI, -1, -1).slidingWindow);
        var b = new Bits().put(1, 1).put(0b001, 3).put(1, 1).parse(PictureHeader.B, -1, -1);
        assertTrue(b.twoBackward);
        var single = new Bits().put(0, 1).put(0b001, 3).parse(PictureHeader.B, -1, -1);
        assertFalse(single.twoBackward);
        assertEquals(3, ErpsLayer.columns(176, 4));
        assertEquals(8, ErpsLayer.rows(144, 0));
    }

    @Test
    @DisplayName("grows to many re-mappings and operations, and refuses more than 4096 of either")
    void manyCommands() {
        var remaps = new Bits().put(1, 1);
        for (var index = 0; index < 20; index++) {
            remaps.put(1, 1).index(index);
        }
        var grown = remaps.put(0b001, 3).put(1, 1).parse(PictureHeader.P, -1, -1);
        assertEquals(20, grown.remaps);
        assertEquals(20, grown.remapValues[19]);
        var tooManyRemaps = new Bits().put(1, 1);
        for (var index = 0; index <= 4096; index++) {
            tooManyRemaps.put(1, 1).index(0);
        }
        assertThrows(MediaException.Malformed.class, () -> tooManyRemaps.put(0b001, 3).put(1, 1)
                .parse(PictureHeader.P, -1, -1));
        var operations = new Bits().put(0, 1).put(0b001, 3).put(0, 1);
        for (var index = 0; index < 20; index++) {
            operations.put(0b011, 3).index(index);
        }
        var layer = operations.put(1, 1).parse(PictureHeader.P, -1, -1);
        assertEquals(20, layer.operations);
        assertEquals(19, layer.operationFirst[19]);
        assertEquals(ErpsLayer.MMCO_SHORT_UNUSED, layer.operationKinds[19]);
        var tooManyOperations = new Bits().put(0, 1).put(0b001, 3).put(0, 1);
        for (var index = 0; index <= 4096; index++) {
            tooManyOperations.put(0b011, 3).index(0);
        }
        assertThrows(MediaException.Malformed.class, () -> tooManyOperations.put(1, 1)
                .parse(PictureHeader.P, -1, -1));
    }

    @Test
    @DisplayName("refuses corrupt codes and removals without a buffer structure")
    void malformed() {
        assertThrows(MediaException.Malformed.class, () -> new Bits().put(0, 1).put(0b000, 3).put(0, 20)
                .parse(PictureHeader.P, -1, -1), "RMPNI 00");
        var overlong = new Bits().put(0, 1).put(1, 1).put(0, 1);
        for (var index = 0; index < 13; index++) {
            overlong.put(1, 1).put(1, 1);
        }
        assertThrows(MediaException.Malformed.class, () -> overlong.parse(PictureHeader.P, -1, -1));
        assertThrows(MediaException.Malformed.class, () -> new Bits().put(0, 1).put(0b001, 3).put(0, 1).put(0, 3)
                .put(0, 20).parse(PictureHeader.P, -1, -1), "MMCO 000");
        assertThrows(MediaException.Malformed.class, () -> new Bits().put(0, 1).put(0b001, 3).put(0, 1)
                .put(0b001, 3).put(0, 2).index(0).put(0, 20).parse(PictureHeader.P, -1, -1), "no buffer structure");
        var badIndex = new Bits().put(0, 1).put(0b001, 3).put(0, 1).put(0b011, 3).put(0, 1);
        for (var index = 0; index < 13; index++) {
            badIndex.put(1, 1).put(1, 1);
        }
        assertThrows(MediaException.Malformed.class, () -> badIndex.parse(PictureHeader.P, -1, -1));
        var zeros = new Bits().put(0, 1).put(0b001, 3).put(0, 1).put(0b001, 3).put(0, 2).index(0).put(0, 8).put(0, 30);
        assertThrows(MediaException.Malformed.class, () -> zeros.parse(PictureHeader.P, 0, 1), "SPRB marker");
        var truncated = new PictureHeaderTest.Bits().put(0, 1).put(0b001, 3).put(0, 1).put(0b011, 3).bytes();
        var reader = new BitReader();
        reader.load(truncated, 0, 1);
        assertThrows(MediaException.Malformed.class, () -> new ErpsLayer().parse(reader, PictureHeader.P, 176, 144,
                -1, -1));
        // four re-mappings, the last of which ends past the data in a code that would be valid
        var cut = new BitReader();
        cut.load(new byte[]{(byte) 0xFF, 0, 0}, 0, 1);
        assertThrows(MediaException.Malformed.class, () -> new ErpsLayer().parse(cut, PictureHeader.P, 176, 144,
                -1, -1));
    }
}
