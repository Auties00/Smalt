package com.github.auties00.smalt.capture.internal.macos;

import java.lang.foreign.MemoryLayout;
import java.lang.foreign.StructLayout;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Declares the layouts of the small CoreFoundation, CoreMedia and CoreGraphics structures passed
 * or returned by value.
 *
 * <p>Every structure is declared as in the macOS SDK headers of the 64-bit Darwin targets, where
 * {@code CGFloat} is {@code double} and {@code CFIndex} is {@code long}. {@code CMTime} lives in
 * {@link CmTime#LAYOUT}.
 */
final class NativeLayouts {
    /**
     * {@code CMVideoDimensions} ({@code CMFormatDescription.h}): {@code int32_t width, height}, 8
     * bytes.
     */
    static final StructLayout CM_VIDEO_DIMENSIONS = MemoryLayout.structLayout(
            JAVA_INT.withName("width"),
            JAVA_INT.withName("height"));

    /**
     * {@code CGPoint} ({@code CGGeometry.h}): {@code CGFloat x, y}, 16 bytes.
     */
    static final StructLayout CG_POINT = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("x"),
            JAVA_DOUBLE.withName("y"));

    /**
     * {@code CGSize} ({@code CGGeometry.h}): {@code CGFloat width, height}, 16 bytes.
     */
    static final StructLayout CG_SIZE = MemoryLayout.structLayout(
            JAVA_DOUBLE.withName("width"),
            JAVA_DOUBLE.withName("height"));

    /**
     * {@code CGRect} ({@code CGGeometry.h}): {@code CGPoint origin; CGSize size}, 32 bytes.
     */
    static final StructLayout CG_RECT = MemoryLayout.structLayout(
            CG_POINT.withName("origin"),
            CG_SIZE.withName("size"));

    /**
     * {@code CFRange} ({@code CFBase.h}): {@code CFIndex location, length}, 16 bytes.
     */
    static final StructLayout CF_RANGE = MemoryLayout.structLayout(
            JAVA_LONG.withName("location"),
            JAVA_LONG.withName("length"));

    /**
     * Prevents instantiation.
     */
    private NativeLayouts() {
        throw new AssertionError();
    }
}
