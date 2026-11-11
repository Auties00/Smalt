package com.github.auties00.smalt.internal.tags;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers the expansion of ID3 genre values: version 2.4 bare numbers, version 2.3 parenthesized references with and
 * without a refinement, the Remix and Cover keywords, the doubled parenthesis that escapes a literal one, and the
 * malformed references that stay text.
 */
@DisplayName("Id3Genres")
class Id3GenresTest {
    @ParameterizedTest(name = "\"{0}\"")
    @CsvSource(delimiter = '|', value = {
            "17|Rock",
            "0|Blues",
            "191|Psybient",
            "255|255",
            "1234|1234",
            "(17)|Rock",
            "(17)(26)|Rock;Ambient",
            "(17)(26)Eurodisco|Rock;Ambient;Eurodisco",
            "(4)Eurodisco|Eurodisco",
            "(26)Ambient|Ambient",
            "(26)ambient|Ambient",
            "(RX)(CR)|Remix;Cover",
            "(rx)|Remix",
            "((Dark) Wave|(Dark) Wave",
            "(17)(26)((Dark)|Rock;Ambient;(Dark)",
            "(17|(17",
            "(999)|(999)",
            "(1234)|(1234)",
            "(XY)Folk|(XY)Folk",
            "'  (17)  '|Rock",
            "Jazz|Jazz"
    })
    @DisplayName("expands genre references and keeps the rest as text")
    void expand(String value, String expected) {
        assertEquals(List.of(expected.split(";")), Id3Genres.expand(value));
    }

    @Test
    @DisplayName("expands an empty value to nothing")
    void empty() {
        assertEquals(List.of(), Id3Genres.expand(""));
        assertEquals(List.of(), Id3Genres.expand("   "));
    }

    @Test
    @DisplayName("names the 192 genres of the Winamp list and nothing past them")
    void names() {
        assertEquals("Blues", Id3Genres.name(0));
        assertEquals("Psybient", Id3Genres.name(191));
        assertNull(Id3Genres.name(192));
        assertNull(Id3Genres.name(-1));
    }
}
