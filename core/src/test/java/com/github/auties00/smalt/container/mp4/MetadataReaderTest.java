package com.github.auties00.smalt.container.mp4;

import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.metadata.StandardTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

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
            "tvsh, show", "tven, episode_id", "tvnn, network", "sonm, sort_name", "soar, sort_artist",
            "soaa, sort_album_artist", "soal, sort_album", "soco, sort_composer", "tmpo, tmpo", "abcd, abcd",
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
}
