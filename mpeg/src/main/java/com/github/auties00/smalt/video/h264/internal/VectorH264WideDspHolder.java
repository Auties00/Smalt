package com.github.auties00.smalt.video.h264.internal;

/**
 * Creates the Vector API kernel set of pictures deeper than 8 bits.
 *
 * <p>This class is loaded only when {@link com.github.auties00.smalt.internal.simd.Simd#ENABLED}
 * is {@code true}, so the vector types {@link VectorH264WideDsp} mentions are linked only when the
 * incubating module is present.
 */
final class VectorH264WideDspHolder {
    private VectorH264WideDspHolder() {
        throw new AssertionError();
    }

    /**
     * Returns a new Vector API kernel set.
     *
     * @return the kernel set
     */
    static H264WideDsp create() {
        return new VectorH264WideDsp();
    }
}
