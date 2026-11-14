package com.github.auties00.smalt.testkit;

import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Checks the {@code equals}, {@code hashCode} and {@code toString} contract of a value type: two values built the
 * same way are equal with the same hash, and every value that differs from the base in one component is unequal to
 * it, so an {@code equals} that ignores a component fails.
 */
public final class ValueContract {
    private ValueContract() {
        throw new AssertionError();
    }

    /**
     * Checks a value type against variants that each change one component of the base.
     *
     * @param base builds the base value; called twice, both results must be equal
     * @param variants values each differing from the base in exactly one component
     * @param <T> the value type
     */
    public static <T> void check(Supplier<? extends T> base, List<? extends T> variants) {
        var first = base.get();
        var second = base.get();
        assertEquals(first, first);
        assertEquals(first, second);
        assertEquals(second, first);
        assertEquals(first.hashCode(), second.hashCode());
        assertNotNull(first.toString());
        assertNotEquals(null, first);
        assertNotEquals(first, new Object());
        for (var index = 0; index < variants.size(); index++) {
            var variant = variants.get(index);
            assertNotNull(variant.toString());
            assertNotEquals(first, variant, "variant " + index + " differs in one component: " + variant);
            assertNotEquals(variant, first, "variant " + index + " differs in one component: " + variant);
            assertEquals(variant, variant);
        }
    }
}
