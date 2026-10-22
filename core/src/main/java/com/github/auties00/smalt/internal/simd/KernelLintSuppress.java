package com.github.auties00.smalt.internal.simd;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Accepts findings of the kernel lint (the {@code smalt-kernel-lint} build step) in one method, with a reason.
 *
 * <p>The lint fails on a suppression that names an unknown rule, gives a blank reason, or suppresses nothing, so a
 * suppression disappears with the code it excused. The bans of the build's forbidden-apis check
 * ({@code vector-convenience}, {@code vector-reinterpret}, {@code vector-confined}, {@code strict-math}) are
 * suppressed the same way: forbidden-apis skips a method carrying this annotation whole, and the lint then
 * reports, in that method, the bans the annotation does not name. The mark is read from the class files; it has
 * no run-time presence.
 */
@Documented
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface KernelLintSuppress {
    /**
     * {@return the ids of the rules the method may break, as the lint prints them}
     */
    String[] value();

    /**
     * {@return why the rules do not apply to the method}
     */
    String reason();
}
