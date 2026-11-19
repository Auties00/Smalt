package com.github.auties00.smalt.internal.simd;

// A stand-in for smalt-core's Simd: the wide-species rule recognizes the guard by this name.
public final class Simd {
    public static final boolean WIDE = Boolean.getBoolean("fixture.wide");

    private Simd() {
    }
}
