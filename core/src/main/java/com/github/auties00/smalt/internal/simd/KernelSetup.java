package com.github.auties00.smalt.internal.simd;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method of a vector kernel that runs once per configuration (it builds tables or caches the hot path reads)
 * rather than once per block, row or frame, and so may allocate.
 *
 * <p>The JIT guard of the test kit requires every other kernel call to allocate nothing once C2 has compiled it, and
 * does not meter calls of a method marked so. The mark is read from the class files; it has no run-time presence.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target(ElementType.METHOD)
public @interface KernelSetup {
    /**
     * {@return why the method runs once per configuration, for a reader of the kernel}
     */
    String value();
}
