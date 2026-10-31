package com.github.auties00.smalt.container.flv;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.io.ByteWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static com.github.auties00.smalt.container.flv.FlvBuilder.bytes;
import static com.github.auties00.smalt.container.flv.FlvBuilder.concat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Covers the AMF0 values of FLV script data: what the writer writes reads back, every type of FLV specification
 * E.4.4 decodes, and corrupt data (truncation, sizes past the end, deep nesting, AMF3, more values or objects than
 * the budget) is malformed.
 */
@DisplayName("AMF0 script data")
class Amf0Test {
    static Object read(byte[] data) {
        return new Amf0(data, 0, data.length).read();
    }

    @Test
    @DisplayName("reads back what the writer writes, long strings included")
    void roundTrip() {
        var out = new ByteWriter(64);
        Amf0.writeNumber(out, -2.5);
        Amf0.writeBoolean(out, true);
        Amf0.writeString(out, "hé");
        var longText = "x".repeat(70_000);
        Amf0.writeString(out, longText);
        out.u8(Amf0.ECMA_ARRAY);
        out.u32be(1);
        Amf0.writeName(out, "times");
        Amf0.writeNumbers(out, new double[]{0, 1.5, 3}, 2);
        Amf0.writeObjectEnd(out);
        var data = out.toByteArray();
        var reader = new Amf0(data, 0, data.length);
        assertEquals(-2.5, reader.read());
        assertEquals(true, reader.read());
        assertEquals("hé", reader.read());
        assertEquals(longText, reader.read());
        var map = assertInstanceOf(Map.class, reader.read());
        assertEquals(List.of(0.0, 1.5), map.get("times"));
        assertFalse(reader.hasMore());
        assertEquals(data.length, reader.position());
    }

    @Test
    @DisplayName("decodes objects, typed objects, dates, null, undefined and references")
    void types() {
        var data = concat(bytes(3), bytes(0, 1, 'a'), bytes(5), bytes(0, 1, 'b'), bytes(6), bytes(0, 1, 'c'),
                bytes(7, 0, 3), bytes(0, 1, 'd'), bytes(11), new byte[8], bytes(0xFF, 0xC4), bytes(0, 0, 9));
        var object = assertInstanceOf(Map.class, read(data));
        assertEquals(4, object.size());
        assertNull(object.get("a"));
        assertNull(object.get("c"));
        var date = assertInstanceOf(Amf0.Date.class, object.get("d"));
        assertEquals(-60, date.offsetMinutes());
        var typed = concat(bytes(16, 0, 2, 'T', 'y'), bytes(0, 1, 'v'), bytes(1, 0), bytes(0, 0, 9));
        assertEquals(Map.of("v", false), read(typed));
        assertEquals("xml", read(bytes(15, 0, 0, 0, 3, 'x', 'm', 'l')));
        // an object that the data ends without its end marker keeps what it holds
        assertEquals(Map.of("v", true), read(bytes(3, 0, 1, 'v', 1, 1)));
    }

    @Test
    @DisplayName("rejects truncated values, sizes past the end, deep nesting and AMF3")
    void malformed() {
        assertThrows(MediaException.Malformed.class, () -> read(bytes(0, 1, 2, 3)));
        assertThrows(MediaException.Malformed.class, () -> read(bytes(2, 0, 9, 'a')));
        assertThrows(MediaException.Malformed.class, () -> read(bytes(12, 0x7F, 0, 0, 0)));
        assertThrows(MediaException.Malformed.class, () -> read(bytes(10, 0x7F, 0, 0, 0, 5)));
        assertThrows(MediaException.Malformed.class, () -> read(bytes(17, 1)));
        assertThrows(MediaException.Malformed.class, () -> read(bytes()));
        // strict arrays of one value nested past the depth bound, ending in a number
        var levels = Amf0.MAX_DEPTH + 2;
        var deep = new byte[levels * 5 + 9];
        for (var index = 0; index < levels; index++) {
            System.arraycopy(bytes(10, 0, 0, 0, 1), 0, deep, index * 5, 5);
        }
        var exception = assertThrows(MediaException.Malformed.class, () -> read(deep));
        assertEquals("FLV script data nested deeper than " + Amf0.MAX_DEPTH + " levels", exception.getMessage());
        var shallow = Arrays.copyOfRange(deep, 5 * (levels - Amf0.MAX_DEPTH), deep.length);
        assertInstanceOf(List.class, read(shallow));
        assertThrows(IllegalArgumentException.class, () -> Amf0.writeName(new ByteWriter(8), "n".repeat(70_000)));
    }

    /** Returns a strict array of {@code count} copies of one encoded value. */
    static byte[] array(int count, byte[] value) {
        var data = new byte[5 + count * value.length];
        data[0] = Amf0.STRICT_ARRAY;
        data[1] = (byte) (count >>> 24);
        data[2] = (byte) (count >>> 16);
        data[3] = (byte) (count >>> 8);
        data[4] = (byte) count;
        for (var index = 0; index < count; index++) {
            System.arraycopy(value, 0, data, 5 + index * value.length, value.length);
        }
        return data;
    }

    @Test
    @DisplayName("bounds the values and the objects one tag decodes to")
    void budget() {
        var nul = bytes(Amf0.NULL);
        assertEquals(Amf0.MAX_VALUES - 1, ((List<?>) read(array(Amf0.MAX_VALUES - 1, nul))).size());
        var values = assertThrows(MediaException.Malformed.class, () -> read(array(Amf0.MAX_VALUES, nul)));
        assertEquals("FLV script data of more than " + Amf0.MAX_VALUES + " values", values.getMessage());
        var empty = bytes(Amf0.OBJECT, 0, 0, Amf0.OBJECT_END);
        assertEquals(Amf0.MAX_CONTAINERS - 1, ((List<?>) read(array(Amf0.MAX_CONTAINERS - 1, empty))).size());
        var objects = assertThrows(MediaException.Malformed.class, () -> read(array(Amf0.MAX_CONTAINERS, empty)));
        assertEquals("FLV script data of more than " + Amf0.MAX_CONTAINERS + " objects and arrays",
                objects.getMessage());
    }
}
