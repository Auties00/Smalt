package com.github.auties00.smalt.internal.simd;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// A stand-in for smalt-core's annotation: the lint recognizes it by this name.
@Retention(RetentionPolicy.CLASS)
@Target({ElementType.METHOD, ElementType.CONSTRUCTOR})
public @interface KernelLintSuppress {
    String[] value();

    String reason();
}
