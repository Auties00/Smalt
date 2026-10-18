package com.github.auties00.smalt.video.av1.internal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Covers {@link FrameHeader#reset}: every field, including those of the nested film grain and
 * global motion parameters, found by reflection so that a field added later is covered too.
 */
@DisplayName("AV1 frame header")
class FrameHeaderTest {
    @Test
    @DisplayName("reset returns every field to the value of a newly constructed header")
    void reset() throws ReflectiveOperationException {
        var header = new FrameHeader();
        scramble(header);
        assertNotNull(difference("header", new FrameHeader(), header), "scrambling left the header unchanged");
        header.reset();
        assertNull(difference("header", new FrameHeader(), header));
    }

    private static void scramble(Object object) throws ReflectiveOperationException {
        for (var field : object.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            var type = field.getType();
            if (type == int.class) {
                field.setInt(object, 7);
            } else if (type == boolean.class) {
                field.setBoolean(object, true);
            } else if (type == short.class) {
                field.setShort(object, (short) 3);
            } else if (type.isArray()) {
                scrambleArray(field.get(object));
            } else {
                scramble(field.get(object));
            }
        }
    }

    private static void scrambleArray(Object array) throws ReflectiveOperationException {
        var component = array.getClass().getComponentType();
        for (var i = 0; i < Array.getLength(array); i++) {
            if (component == int.class) {
                Array.setInt(array, i, 5);
            } else if (component == boolean.class) {
                Array.setBoolean(array, i, true);
            } else if (component == short.class) {
                Array.setShort(array, i, (short) 3);
            } else if (component.isArray()) {
                scrambleArray(Array.get(array, i));
            } else {
                scramble(Array.get(array, i));
            }
        }
    }

    private static String difference(String path, Object expected, Object actual) throws ReflectiveOperationException {
        var type = expected.getClass();
        if (type.isArray()) {
            for (var i = 0; i < Array.getLength(expected); i++) {
                var found = difference(path + "[" + i + "]", Array.get(expected, i), Array.get(actual, i));
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
        if (type == Integer.class || type == Boolean.class || type == Short.class) {
            return expected.equals(actual) ? null : path + ": expected " + expected + " but was " + actual;
        }
        for (var field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            field.setAccessible(true);
            var found = difference(path + "." + field.getName(), field.get(expected), field.get(actual));
            if (found != null) {
                return found;
            }
        }
        return null;
    }
}
