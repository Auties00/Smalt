package com.github.auties00.smalt.video.h263.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the multi-picture reference buffer of Annex U with hand-built ERPS layers: the sliding window, every memory
 * management operation, the buffer structure with sub-picture removal, and the re-mapped prediction order. Every
 * picture the buffer drops must be released exactly once.
 */
@DisplayName("H.263 multi-picture reference buffer")
class ReferenceBufferTest {
    private final Map<PlusPicture, Integer> released = new IdentityHashMap<>();
    private final List<PlusPicture> retained = new ArrayList<>();
    private final ReferenceBuffer buffer = new ReferenceBuffer(picture -> released.merge(picture, 1, Integer::sum));

    private PlusPicture picture() {
        return new PlusPicture(64, 48);
    }

    private static ErpsLayer sliding() {
        return new ErpsLayer();
    }

    private static ErpsLayer operations(int... kindsAndArguments) {
        var layer = new ErpsLayer();
        layer.slidingWindow = false;
        var at = 0;
        while (at < kindsAndArguments.length) {
            var index = layer.operations++;
            layer.operationKinds[index] = kindsAndArguments[at++];
            layer.operationFirst[index] = kindsAndArguments[at++];
            layer.operationSecond[index] = kindsAndArguments[at++];
            layer.operationThird[index] = kindsAndArguments[at++];
            layer.operationFourth[index] = kindsAndArguments[at++];
        }
        return layer;
    }

    private PlusPicture store(int number, ErpsLayer layer) {
        var picture = picture();
        buffer.store(picture, number, layer, retained::add);
        return picture;
    }

    private List<PlusPicture> order(ErpsLayer layer, int current) {
        var out = new ArrayList<PlusPicture>();
        buffer.order(layer, current, out);
        return out;
    }

    @Test
    @DisplayName("keeps sixteen pictures in a sliding window, newest first")
    void slidingWindow() {
        var pictures = new ArrayList<PlusPicture>();
        for (var number = 0; number < 20; number++) {
            pictures.add(store(number, sliding()));
        }
        assertEquals(16, buffer.size());
        assertEquals(20, retained.size());
        for (var index = 0; index < 4; index++) {
            assertEquals(1, released.get(pictures.get(index)));
        }
        assertSame(pictures.getLast(), order(null, 20).getFirst());
        buffer.clear();
        assertTrue(buffer.isEmpty());
        assertEquals(20, released.size());
    }

    @Test
    @DisplayName("marks pictures unused, assigns long-term indices and bounds them")
    void operationsOnPictures() {
        var zero = store(0, sliding());
        var one = store(1, sliding());
        var two = store(2, sliding());
        store(3, operations(ErpsLayer.MMCO_SHORT_UNUSED, 3, 0, 0, 0, ErpsLayer.MMCO_ASSIGN_LONG, 2, 5, 0, 0,
                ErpsLayer.MMCO_ASSIGN_LONG, 9, 6, 0, 0));
        assertEquals(1, released.get(zero));
        assertEquals(3, buffer.size());
        store(4, operations(ErpsLayer.MMCO_ASSIGN_LONG, 2, 5, 0, 0));
        assertEquals(1, released.get(one), "a long-term index taken again releases the picture it held");
        var ordered = order(remap(ErpsLayer.REMAP_LONG_TERM, 5, ErpsLayer.REMAP_NEGATIVE, 2, ErpsLayer.REMAP_POSITIVE,
                1, ErpsLayer.REMAP_LONG_TERM, 7), 4);
        assertEquals(3, ordered.size());
        store(5, operations(ErpsLayer.MMCO_LONG_UNUSED, 5, 0, 0, 0, ErpsLayer.MMCO_LONG_UNUSED, 6, 0, 0, 0));
        store(6, operations(ErpsLayer.MMCO_ASSIGN_LONG, 1, 2, 0, 0, ErpsLayer.MMCO_ASSIGN_LONG, 2, 3, 0, 0,
                ErpsLayer.MMCO_MAX_LONG, 3, 0, 0, 0));
        assertEquals(1, released.get(two), "a long-term picture marked unused is released");
        assertEquals(3, buffer.size(), "the long-term picture past the new maximum index is released");
        buffer.clear();
        for (var picture : retained) {
            assertEquals(1, released.get(picture), "every picture is released once");
        }
    }

    @Test
    @DisplayName("resets to the current picture on a buffer structure with a reset, and counts removed sub-pictures")
    void bufferStructure() {
        store(0, sliding());
        store(1, sliding());
        store(2, operations(ErpsLayer.MMCO_ASSIGN_LONG, 1, 1, 0, 0));
        var current = store(3, operations(ErpsLayer.MMCO_BUFFER, 1, 1, 6, 1));
        assertEquals(1, buffer.size());
        assertSame(current, order(null, 3).getFirst());
        var areasShort = operations(ErpsLayer.MMCO_SHORT_AREAS, 0, 0, 0, 0);
        var count = ErpsLayer.subPictures(64, 48, 1, 1);
        var areas = new boolean[count];
        areas[0] = true;
        areas[1] = true;
        areasShort.operationAreas[0] = areas;
        store(4, areasShort);
        store(5, operations(ErpsLayer.MMCO_ASSIGN_LONG, 1, 4, 0, 0));
        var areasLong = operations(ErpsLayer.MMCO_LONG_AREAS, 4, 0, 0, 0, ErpsLayer.MMCO_LONG_AREAS, 9, 0, 0, 0);
        areasLong.operationAreas[0] = areas;
        areasLong.operationAreas[1] = areas;
        store(6, areasLong);
        for (var number = 7; number < 12; number++) {
            store(number, sliding());
        }
        assertTrue(buffer.size() <= 6, "the window holds the capacity of the buffer structure");
        buffer.clear();
        for (var picture : retained) {
            assertEquals(1, released.get(picture), "every picture is released once");
        }
    }

    @Test
    @DisplayName("resets the buffer when earlier operations moved the current picture out of the short-term list")
    void resetAfterCurrentMoved() {
        store(0, sliding());
        var older = store(1, sliding());
        assertFalse(buffer.isEmpty(), "short-term pictures alone");
        // the current picture becomes long-term, so the reset finds an older picture first in the short-term list
        var current = store(2, operations(ErpsLayer.MMCO_ASSIGN_LONG, 0, 3, 0, 0, ErpsLayer.MMCO_BUFFER, 1, 1, 6,
                1));
        assertTrue(buffer.isEmpty());
        assertEquals(1, released.get(older));
        assertEquals(1, released.get(current));
        // the current picture marked unused leaves the short-term list empty for the reset
        var marked = store(3, operations(ErpsLayer.MMCO_SHORT_UNUSED, 0, 0, 0, 0, ErpsLayer.MMCO_BUFFER, 1, 1, 6,
                1));
        assertTrue(buffer.isEmpty());
        assertEquals(1, released.get(marked));
        for (var picture : retained) {
            assertEquals(1, released.get(picture), "every picture is released once");
        }
    }

    @Test
    @DisplayName("keeps long-term pictures alone, and ignores operations naming no picture")
    void longTermOnly() {
        var zero = store(0, sliding());
        var one = store(1, operations(ErpsLayer.MMCO_ASSIGN_LONG, 1, 2, 0, 0));
        // a short-term re-mapping to picture number 0 skips the long-term picture that has that number
        var ordered = order(remap(ErpsLayer.REMAP_NEGATIVE, 1), 1);
        assertEquals(List.of(one, zero), ordered);
        var two = store(2, operations(ErpsLayer.MMCO_SHORT_UNUSED, 1, 0, 0, 0, ErpsLayer.MMCO_SHORT_UNUSED, 0, 0, 0, 0,
                ErpsLayer.MMCO_SHORT_UNUSED, 7, 0, 0, 0));
        assertEquals(1, released.get(one));
        assertEquals(1, released.get(two));
        assertFalse(buffer.isEmpty(), "the long-term picture remains");
        assertEquals(1, buffer.size());
        var three = store(3, operations(ErpsLayer.MMCO_BUFFER, 1, 1, 100, 0));
        assertEquals(2, buffer.size(), "a buffer structure without a reset keeps the pictures");
        var four = store(4, sliding());
        var areas = operations(ErpsLayer.MMCO_SHORT_AREAS, 2, 0, 0, 0, ErpsLayer.MMCO_SHORT_AREAS, 9, 0, 0, 0, 99, 0, 0,
                0, 0);
        areas.operationAreas[0] = new boolean[]{true};
        var five = store(5, areas);
        assertEquals(List.of(five, four, three, zero), order(null, 5));
        buffer.clear();
        assertTrue(buffer.isEmpty());
        for (var picture : retained) {
            assertEquals(1, released.get(picture), "every picture is released once");
        }
    }

    private static ErpsLayer remap(int... kindsAndValues) {
        var layer = new ErpsLayer();
        for (var at = 0; at < kindsAndValues.length; at += 2) {
            layer.remapKinds[layer.remaps] = kindsAndValues[at];
            layer.remapValues[layer.remaps++] = kindsAndValues[at + 1];
        }
        return layer;
    }
}
