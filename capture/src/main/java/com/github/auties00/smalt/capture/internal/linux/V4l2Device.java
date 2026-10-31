package com.github.auties00.smalt.capture.internal.linux;

import java.lang.foreign.MemorySegment;

/**
 * Issues Video4Linux2 requests to one open device node.
 *
 * <p>The production implementation calls {@code ioctl} on the node's descriptor; tests supply a
 * simulated device that fills the argument structures, so the enumeration and negotiation logic
 * built on this interface runs on every operating system.
 */
@FunctionalInterface
interface V4l2Device {
    /**
     * Issues a request.
     *
     * @param request  the request number, one of the {@code VIDIOC_*} constants of {@link V4l2}
     * @param argument the argument structure, read and written in place
     * @return {@code 0} on success, otherwise the positive {@code errno}
     */
    int ioctl(long request, MemorySegment argument);

    /**
     * Returns the buffer type the device captures with.
     *
     * @return {@link V4l2#BUF_TYPE_VIDEO_CAPTURE}, or {@link V4l2#BUF_TYPE_VIDEO_CAPTURE_MPLANE} for a
     * multi-planar device
     */
    default int bufferType() {
        return V4l2.BUF_TYPE_VIDEO_CAPTURE;
    }

    /**
     * Returns whether the device captures with the multi-planar API.
     *
     * @return whether {@link #bufferType()} is {@link V4l2#BUF_TYPE_VIDEO_CAPTURE_MPLANE}
     */
    default boolean multiPlanar() {
        return bufferType() == V4l2.BUF_TYPE_VIDEO_CAPTURE_MPLANE;
    }

    /**
     * Returns a device that issues the requests of another with a buffer type.
     *
     * @param requests the device issuing the requests
     * @param bufferType the buffer type
     * @return the device
     */
    static V4l2Device of(V4l2Device requests, int bufferType) {
        return new V4l2Device() {
            @Override
            public int ioctl(long request, MemorySegment argument) {
                return requests.ioctl(request, argument);
            }

            @Override
            public int bufferType() {
                return bufferType;
            }
        };
    }
}
