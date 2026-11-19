package com.github.auties00.smalt.image.psd;

import com.github.auties00.smalt.MediaException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the reader of Photoshop action descriptors: the value types, the nesting bound and truncated input.
 */
@DisplayName("Photoshop descriptor reader")
class PsdDescriptorTest {
    @Test
    @DisplayName("reads booleans, numbers, nested descriptors and lists")
    void values() throws IOException {
        var bytes = new PsdLayerCompositeTest.Descriptor()
                .bool("masterFXSwitch", false)
                .doub("Top ", 2.5)
                .integer("artboardBackgroundType", 4)
                .object("Clr ", new PsdLayerCompositeTest.Descriptor().doub("Rd  ", 10))
                .list("dropShadowMulti", new PsdLayerCompositeTest.Descriptor().bool("enab", true))
                .versioned();
        var items = PsdDescriptor.versioned(bytes, 0, bytes.length);
        assertFalse(PsdDescriptor.bool(items, "masterFXSwitch", true));
        assertEquals(2.5, PsdDescriptor.number(items, "Top ", 0));
        assertEquals(4, PsdDescriptor.number(items, "artboardBackgroundType", 0));
        assertEquals(10, PsdDescriptor.number(PsdDescriptor.object(items, "Clr "), "Rd  ", 0));
        var list = assertInstanceOf(List.class, items.get("dropShadowMulti"));
        assertEquals(true, ((Map<?, ?>) list.getFirst()).get("enab"));
        assertTrue(PsdDescriptor.bool(items, "absent", true));
        assertNull(PsdDescriptor.object(items, "Top "));
    }

    @Test
    @DisplayName("rejects descriptors nested too deeply, truncated or of unknown types")
    void malformed() throws IOException {
        var inner = new PsdLayerCompositeTest.Descriptor().bool("enab", true);
        for (var level = 0; level < PsdDescriptor.MAX_DEPTH + 2; level++) {
            inner = new PsdLayerCompositeTest.Descriptor().object("Objc", inner);
        }
        var deep = inner.versioned();
        assertThrows(MediaException.Malformed.class, () -> PsdDescriptor.versioned(deep, 0, deep.length));
        var good = new PsdLayerCompositeTest.Descriptor().doub("Top ", 1).versioned();
        for (var length = 0; length < good.length; length++) {
            var end = length;
            assertThrows(MediaException.Malformed.class, () -> PsdDescriptor.versioned(good, 0, end));
        }
        var unknown = good.clone();
        unknown[unknown.length - 12] = 'X';
        assertThrows(MediaException.Malformed.class, () -> PsdDescriptor.versioned(unknown, 0, unknown.length));
        var version = good.clone();
        version[3] = 15;
        assertThrows(MediaException.Malformed.class, () -> PsdDescriptor.versioned(version, 0, version.length));
    }
}
