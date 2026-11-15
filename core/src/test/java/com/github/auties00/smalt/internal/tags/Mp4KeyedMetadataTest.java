package com.github.auties00.smalt.internal.tags;

import com.github.auties00.smalt.internal.io.ByteWriter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the QuickTime keyed metadata codec on hand-built {@code meta} boxes: the data types it reads as text, the
 * items it keeps opaque, and the forms a rebuild writes.
 */
@DisplayName("QuickTime keyed metadata")
class Mp4KeyedMetadataTest {
    /** One item: a key, its namespace and its data atoms as (type, locale, payload). */
    record Item(String key, String namespace, List<Object[]> atoms) {
    }

    static Item item(String key, int type, byte[] payload) {
        return new Item(key, "mdta", List.<Object[]>of(new Object[]{type, 0, payload}));
    }

    static byte[] meta(boolean fullBox, List<Item> items) {
        var keys = new ByteWriter(256);
        keys.u32be(0);
        keys.u32be(items.size());
        var ilst = new ByteWriter(256);
        for (var index = 0; index < items.size(); index++) {
            var item = items.get(index);
            var name = item.key().getBytes(StandardCharsets.UTF_8);
            keys.u32be(8 + name.length);
            keys.bytes(item.namespace().getBytes(StandardCharsets.US_ASCII));
            keys.bytes(name);
            var body = new ByteWriter(64);
            for (var atom : item.atoms()) {
                var payload = (byte[]) atom[2];
                body.u32be(16 + payload.length);
                body.bytes("data".getBytes(StandardCharsets.US_ASCII));
                body.u32be((Integer) atom[0]);
                body.u32be((Integer) atom[1]);
                body.bytes(payload);
            }
            ilst.u32be(8 + body.size());
            ilst.u32be(index + 1);
            ilst.bytes(body.toByteArray());
        }
        var out = new ByteWriter(512);
        if (fullBox) {
            out.u32be(0);
        }
        var handler = new byte[25];
        System.arraycopy("mdta".getBytes(StandardCharsets.US_ASCII), 0, handler, 8, 4);
        out.bytes(Mp4Boxes.box("hdlr", handler));
        out.bytes(Mp4Boxes.box("keys", keys.toByteArray()));
        out.bytes(Mp4Boxes.box("ilst", ilst.toByteArray()));
        return Mp4Boxes.box("meta", out.toByteArray());
    }

    static Mp4Boxes.Box root(byte[] box) {
        return Mp4Boxes.children(box, 0, box.length).getFirst();
    }

    static Map<String, List<String>> decoded(byte[] box) {
        var result = new LinkedHashMap<String, List<String>>();
        for (var item : Mp4KeyedMetadata.parse(box, root(box)).items()) {
            result.put(item.key(), item.values());
        }
        return result;
    }

    static byte[] be(int width, long value) {
        var bytes = new byte[width];
        for (var index = width - 1; index >= 0; index--) {
            bytes[index] = (byte) value;
            value >>= 8;
        }
        return bytes;
    }

    @ParameterizedTest(name = "type {0}, {1} bytes {2} -> {3}")
    @CsvSource({"1,0,4142,AB", "21,1,ff,-1", "21,2,8000,-32768", "21,3,800000,-8388608", "21,4,7fffffff,2147483647",
            "21,8,ffffffffffffffff,-1", "22,1,ff,255", "22,3,ffffff,16777215", "22,4,ffffffff,4294967295",
            "22,8,ffffffffffffffff,18446744073709551615", "65,1,80,-128", "66,2,ffff,-1", "67,4,80000000,-2147483648",
            "74,8,8000000000000000,-9223372036854775808", "75,1,ff,255", "76,2,ffff,65535", "77,4,ffffffff,4294967295",
            "78,8,ffffffffffffffff,18446744073709551615", "23,4,3f700000,0.9375", "24,8,400921fb54442d18,3.141592653589793",
            "2,0,00410042,AB", "23,4,7fc00000,NaN"})
    @DisplayName("reads text, integers of every width and signedness, and floats as text")
    void types(int type, int ignoredWidth, String hex, String expected) {
        var box = meta(false, List.of(item("com.example.value", type, HexFormat.of().parseHex(hex))));
        assertEquals(Map.of("com.example.value", List.of(expected)), decoded(box));
    }

    @ParameterizedTest(name = "type {0} payload {1}")
    @CsvSource({"0,010203", "13,ffd8ff", "16777217,41", "21,0000000000", "65,0000", "23,000000", "24,00", "1,''",
            "1,2020", "1,ff", "1,4100", "2,004100", "2,00", "99,00"})
    @DisplayName("keeps binary data, empty, blank and malformed values opaque")
    void opaque(int type, String hex) {
        var payload = hex.equals("''") ? new byte[0] : HexFormat.of().parseHex(hex);
        var box = meta(false, List.of(item("com.example.binary", type, payload)));
        var parsed = Mp4KeyedMetadata.parse(box, root(box));
        assertEquals(1, parsed.items().size());
        assertFalse(parsed.items().getFirst().isDecoded());
        assertNull(parsed.items().getFirst().values());
    }

    @Test
    @DisplayName("keeps items of another namespace, of an invalid key and with another child opaque, and skips dangling items")
    void otherItems() {
        var namespaced = new Item("com.example.udta", "udta", List.<Object[]>of(new Object[]{1, 0, "x".getBytes()}));
        var box = meta(true, List.of(namespaced, item("bad=key", 1, "x".getBytes()), item("good", 1, "y".getBytes())));
        var parsed = Mp4KeyedMetadata.parse(box, root(box));
        assertEquals(3, parsed.items().size());
        assertFalse(parsed.items().get(0).isDecoded());
        assertFalse(parsed.items().get(1).isDecoded());
        assertEquals(List.of("y"), parsed.items().get(2).values());
        // an ilst item naming key 9 of a keys box of one key is left out
        var dangling = meta(false, List.of(item("one", 1, "1".getBytes()))).clone();
        var ilst = Mp4Boxes.find(Mp4Boxes.children(dangling, 8, dangling.length), "ilst");
        ByteBuffer.wrap(dangling).putInt(ilst.payload() + 4, 9);
        assertTrue(Mp4KeyedMetadata.parse(dangling, root(dangling)).items().isEmpty());
    }

    @Test
    @DisplayName("returns the box unchanged when every value is kept, and rebuilds only what changes")
    void rebuild() {
        var items = List.of(item("com.example.float", 23, be(4, Float.floatToIntBits(1.5f))),
                item("com.example.short", 21, be(2, -2)), item("com.example.unsigned", 22, be(1, 200)),
                item("com.example.text", 1, "text".getBytes()), item("com.example.binary", 0, new byte[]{9}));
        for (var fullBox : new boolean[]{false, true}) {
            var box = meta(fullBox, items);
            var parsed = Mp4KeyedMetadata.parse(box, root(box));
            var values = new LinkedHashMap<String, List<String>>();
            values.put("com.example.float", List.of("1.5"));
            values.put("com.example.short", List.of("-2"));
            values.put("com.example.unsigned", List.of("200"));
            values.put("com.example.text", List.of("text"));
            assertArrayEquals(box, parsed.rebuild(box, root(box), values, true));
            values.put("com.example.float", List.of("-0.25"));
            values.put("com.example.short", List.of("x"));
            values.put("com.example.unsigned", List.of("256"));
            values.put("com.example.new", List.of("added"));
            var rebuilt = parsed.rebuild(box, root(box), values, true);
            var reparsed = Mp4KeyedMetadata.parse(rebuilt, root(rebuilt));
            var types = new ArrayList<Integer>();
            for (var item : reparsed.items()) {
                types.add(item.isDecoded() ? item.form().type() : -1);
            }
            assertEquals(List.of("com.example.float", "com.example.short", "com.example.unsigned", "com.example.text",
                    "com.example.binary", "com.example.new"), reparsed.items().stream().map(Mp4KeyedMetadata.Item::key)
                    .toList());
            // the float keeps its type, values the other types cannot hold become UTF-8 text
            assertEquals(List.of(23, 1, 1, 1, -1, 1), types);
            assertEquals(List.of("-0.25"), reparsed.items().getFirst().values());
            assertEquals(4, reparsed.items().getFirst().form().width());
            assertEquals(fullBox, rebuilt.length >= 12 && ByteBuffer.wrap(rebuilt).getInt(8) == 0);
            // without keeping opaque items, and with every key removed, the box disappears
            assertEquals(5, Mp4KeyedMetadata.parse(box, root(box)).items().size());
            assertEquals(0, parsed.rebuild(box, root(box), Map.of(), false).length);
            var binaryOnly = parsed.rebuild(box, root(box), Map.of(), true);
            assertEquals(List.of("com.example.binary"), Mp4KeyedMetadata.parse(binaryOnly, root(binaryOnly)).items()
                    .stream().map(Mp4KeyedMetadata.Item::key).toList());
        }
    }

    @Test
    @DisplayName("writes a number in the width the file used and keeps the locale of the item")
    void forms() {
        var box = meta(false, List.of(new Item("com.example.int", "mdta", List.<Object[]>of(
                new Object[]{21, 0x00550045, be(4, 7)}))));
        var parsed = Mp4KeyedMetadata.parse(box, root(box));
        var rebuilt = parsed.rebuild(box, root(box), Map.of("com.example.int", List.of("-9", "10")), false);
        var item = Mp4KeyedMetadata.parse(rebuilt, root(rebuilt)).items().getFirst();
        assertEquals(List.of("-9", "10"), item.values());
        var atoms = Mp4Boxes.children(item.body(), 0, item.body().length);
        assertEquals(2, atoms.size());
        for (var atom : atoms) {
            assertEquals(20, atom.size());
            assertEquals(21, ByteBuffer.wrap(item.body()).getInt(atom.payload()));
            assertEquals(0x00550045, ByteBuffer.wrap(item.body()).getInt(atom.payload() + 4));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"com.apple.quicktime.make", "com.android.capture.fps", "org.example.a-b.c_d"})
    @DisplayName("routes reverse-DNS names to keyed metadata")
    void reverseDns(String key) {
        assertTrue(Mp4KeyedMetadata.isReverseDns(key));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"title", "com.apple", "Com.apple.make", "com..apple", "com.apple.", "MusicBrainz Album Id",
            "a b.c.d", "replaygain_track_gain"})
    @DisplayName("routes the other keys to the iTunes list")
    void notReverseDns(String key) {
        assertFalse(Mp4KeyedMetadata.isReverseDns(key));
    }

    @Test
    @DisplayName("creates a box in QuickTime form with UTF-8 text")
    void create() {
        var values = new LinkedHashMap<String, List<String>>();
        values.put("com.apple.quicktime.make", List.of("Apple"));
        values.put("com.apple.quicktime.model", List.of("iPhone", "second"));
        var box = Mp4KeyedMetadata.create(values);
        assertTrue(Mp4KeyedMetadata.isKeyed(box, root(box)));
        assertEquals(values, decoded(box));
        assertEquals(8, Mp4KeyedMetadata.childrenStart(box, root(box)), "no version and flags");
        assertEquals("hdlr", Mp4Boxes.type(box, 12));
        for (var item : Mp4KeyedMetadata.parse(box, root(box)).items()) {
            assertEquals(1, item.form().type());
            assertEquals(0, item.form().locale());
        }
    }
}
