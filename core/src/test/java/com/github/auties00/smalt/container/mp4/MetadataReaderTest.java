package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.internal.heif.BoxCursor;
import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.metadata.StandardTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("MetadataReader item names")
class MetadataReaderTest {
    @ParameterizedTest
    @EnumSource(StandardTag.class)
    @DisplayName("name every item a standard tag stores by that tag's key")
    void standard(StandardTag tag) {
        tag.mp4Atom().ifPresent(atom -> assertEquals(tag.key(), MetadataReader.itemName(FourCc.of(atom))));
    }

    @ParameterizedTest
    @CsvSource({
            "©xyz, location", "©des, description", "©cpy, copyright", "©swr, encoder",
            "©mak, make", "©mod, model", "desc, description", "ldes, synopsis", "gnre, genre",
            "tvsh, show", "tven, episode_id", "tvnn, network", "sonm, title_sort", "soar, artist_sort",
            "soaa, album_artist_sort", "soal, album_sort", "soco, composer_sort", "tmpo, bpm", "abcd, abcd",
            "©zzz, ©zzz"
    })
    @DisplayName("name the other items by the fixed table, or by their four characters")
    void others(String atom, String name) {
        assertEquals(name, MetadataReader.itemName(FourCc.of(atom)));
    }

    @Test
    @DisplayName("format creation times in UTC with microseconds")
    void creationTime() {
        assertEquals("1970-01-01T00:00:00.000000Z", MetadataReader.formatTime(2082844800L));
        assertNull(MetadataReader.formatTime(0));
    }

    private static byte[] box(String type, byte[]... parts) {
        var payload = new ByteArrayOutputStream();
        for (var part : parts) {
            payload.writeBytes(part);
        }
        return ByteBuffer.allocate(8 + payload.size()).putInt(8 + payload.size())
                .put(type.getBytes(StandardCharsets.ISO_8859_1)).put(payload.toByteArray()).array();
    }

    private static byte[] item(int index, int type, String hex) {
        var value = HexFormat.of().parseHex(hex);
        var data = box("data", ByteBuffer.allocate(8).putInt(type).putInt(0).array(), value);
        var out = ByteBuffer.allocate(8 + data.length).putInt(8 + data.length).putInt(index).put(data);
        return out.array();
    }

    @Test
    @DisplayName("read keyed metadata numbers of every fixed width and leave binary items out")
    void keyedTypes() {
        var names = new String[]{"com.example.binary", "com.example.u8", "com.example.s16", "com.example.u32",
                "com.example.s64", "com.example.text"};
        var keys = new ByteArrayOutputStream();
        keys.writeBytes(ByteBuffer.allocate(8).putInt(0).putInt(names.length).array());
        for (var name : names) {
            var bytes = name.getBytes(StandardCharsets.UTF_8);
            keys.writeBytes(ByteBuffer.allocate(8).putInt(8 + bytes.length).put("mdta".getBytes(StandardCharsets.ISO_8859_1))
                    .array());
            keys.writeBytes(bytes);
        }
        var handler = new byte[25];
        System.arraycopy("mdta".getBytes(StandardCharsets.ISO_8859_1), 0, handler, 8, 4);
        var payload = new ByteArrayOutputStream();
        payload.writeBytes(box("hdlr", handler));
        payload.writeBytes(box("keys", keys.toByteArray()));
        payload.writeBytes(box("ilst", item(1, 0, "01020304"), item(2, 75, "ff"), item(3, 66, "fffe"),
                item(4, 77, "ffffffff"), item(5, 74, "8000000000000000"), item(6, 1, "4142")));
        var tags = new HashMap<String, String>();
        new MetadataReader(tags).readMeta(BoxCursor.of(payload.toByteArray(), FourCc.of("meta")), 0);
        assertEquals(Map.of("com.example.u8", "255", "com.example.s16", "-2", "com.example.u32", "4294967295",
                "com.example.s64", "-9223372036854775808", "com.example.text", "AB"), tags);
    }

    @Test
    @DisplayName("read the coordinates of a 3GPP location box as the location unless a QuickTime atom gives one")
    void loci() {
        var loci = box("loci", HexFormat.of().parseHex("0000000055c40000ffffc000000080000000000065617274680000"));
        var tags = new HashMap<String, String>();
        new MetadataReader(tags).readUserData(BoxCursor.of(loci, FourCc.of("udta")));
        assertEquals(Map.of("location", "+00.5-000.25/"), tags);
        var xyz = box("©xyz", ByteBuffer.allocate(4).putShort((short) 5).putShort((short) 0x15C7).array(),
                "+1+2/".getBytes(StandardCharsets.US_ASCII));
        var both = new ByteArrayOutputStream();
        both.writeBytes(xyz);
        both.writeBytes(loci);
        tags.clear();
        new MetadataReader(tags).readUserData(BoxCursor.of(both.toByteArray(), FourCc.of("udta")));
        assertEquals(Map.of("location", "+1+2/"), tags);
    }
}
