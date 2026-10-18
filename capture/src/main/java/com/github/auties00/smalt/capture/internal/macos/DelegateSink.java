package com.github.auties00.smalt.capture.internal.macos;

import java.lang.foreign.MemorySegment;

/**
 * Receives the delegate callbacks of a capture session.
 *
 * <p>The {@link ObjcBridge} delegate class forwards
 * {@code captureOutput:didOutputSampleBuffer:fromConnection:} and
 * {@code stream:didOutputSampleBuffer:ofType:} to {@link #sampleBuffer(MemorySegment)}, and
 * {@code stream:didStopWithError:} to {@link #stopped(MemorySegment)}. Both run on a dispatch
 * thread inside an autorelease pool; the arguments are valid only for the duration of the call.
 *
 * @implSpec
 * Implementations must return promptly and must not throw: the bridge catches everything, but a
 * failure is better reported through the session.
 */
interface DelegateSink {
    /**
     * Receives a captured video sample buffer.
     *
     * @param sampleBuffer the {@code CMSampleBufferRef}, borrowed
     */
    void sampleBuffer(MemorySegment sampleBuffer);

    /**
     * Receives the end of a stream.
     *
     * @param error the {@code NSError} it stopped with, borrowed, or {@code NULL}
     */
    void stopped(MemorySegment error);

    /**
     * Receives a failure the bridge caught while forwarding a callback.
     */
    void failed(Throwable failure);
}
