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
}
