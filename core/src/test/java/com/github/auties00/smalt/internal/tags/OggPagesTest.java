package com.github.auties00.smalt.internal.tags;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks how header packets are laid out on Ogg pages when a tag edit reproduces the old page layout.
 */
class OggPagesTest {
    // three packets of 300, 10 and 20 bytes: lacing values 255 45 | 10 | 20
    private static final List<byte[]> PACKETS = List.of(new byte[300], new byte[10], new byte[20]);

    static Stream<Arguments> layouts() {
        return Stream.of(
                Arguments.of(new int[]{2, 2}, new int[]{2, 2}),
                Arguments.of(new int[]{1, 3}, new int[]{1, 3}),
                // a page without lacing values, as a damaged file can have, cannot be reproduced
                Arguments.of(new int[]{0, 4}, new int[]{4}),
                // nor can a layout that holds fewer or more lacing values than the packets
                Arguments.of(new int[]{2}, new int[]{4}),
                Arguments.of(new int[]{2, 3}, new int[]{4}),
                Arguments.of(null, new int[]{4}));
    }

    @ParameterizedTest(name = "[{index}]")
    @MethodSource("layouts")
    @DisplayName("reproduces a layout that covers the packets and fills pages otherwise")
    void layout(int[] layout, int[] expected) {
        var pages = OggPages.paginate(PACKETS, 7, 1, layout);
        assertEquals(expected.length, pages.size());
        for (var index = 0; index < expected.length; index++) {
            assertEquals(expected[index], pages.get(index)[26] & 0xFF, "lacing values of page " + index);
        }
    }
}
