/**
 * Microphone capture and speaker playback through the operating system's own APIs:
 * {@link AudioDevices} lists and opens the {@link AudioDevice}s, an {@link AudioCaptureSession} reads
 * and an {@link AudioPlaybackSession} writes interleaved samples in the layout an
 * {@link AudioStreamOptions} requests, and {@link AudioDeviceListener}s receive the
 * {@link AudioDeviceEvent}s of devices added, removed or made the default.
 *
 * <p>Unless otherwise noted, passing a {@code null} argument to a constructor or method in any class or
 * interface in this package will cause a {@link NullPointerException} to be thrown. The {@code @param}
 * definition summarizes the null behavior; {@code @throws NullPointerException} is not documented on each
 * method. Methods that take an object and return a boolean return {@code false} for {@code null}.
 */
package com.github.auties00.smalt.capture.audio;
