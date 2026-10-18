package com.github.auties00.smalt.capture.internal.audio;

/**
 * Drives the device of one session: the backend's thread or callbacks that move audio between
 * the operating system and the session's {@link AudioRing}.
 *
 * @implSpec
 * {@link #stop()} must be idempotent, callable from any thread including the engine's own, and
 * must return only once the engine no longer touches the session and the device is released, or
 * after a bounded wait when the operating system does not answer.
 */
@FunctionalInterface
public interface DeviceEngine {
    /**
     * Stops the device and releases it.
     */
    void stop();
}
