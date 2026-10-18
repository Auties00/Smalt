package com.github.auties00.smalt.internal.probe;

import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.probe.MediaInfo;
import com.github.auties00.smalt.probe.ProbeOptions;

import java.util.Objects;

/**
 * Connects the {@link com.github.auties00.smalt.Media} facade to the probe implementation, which
 * stays package-private in the {@code probe} package so that only the facade is public API.
 *
 * <p>{@link ProbeOptions} registers the implementation when its class is initialized; every
 * caller holds a {@link ProbeOptions} instance before probing, so the registration has always
 * happened by then.
 */
public final class ProbeAccess {
    /**
     * Describes a media file.
     */
    @FunctionalInterface
    public interface Probe {
        /**
         * Describes a media file.
         *
         * @param source the input
         * @param options the probe options
         * @return the description
         */
        MediaInfo probe(MediaSource source, ProbeOptions options);
    }

    /**
     * The registered implementation, or {@code null} before the registration.
     */
    private static volatile Probe probe;

    /**
     * Prevents instantiation.
     */
    private ProbeAccess() {
        throw new AssertionError();
    }

    /**
     * Registers the implementation of {@code Media.probe}.
     *
     * @param implementation the prober, replacing any registered before
     */
    public static void register(Probe implementation) {
        probe = Objects.requireNonNull(implementation, "implementation");
    }

    /**
     * Describes a media file through the registered implementation.
     *
     * @param source the input
     * @param options the probe options, whose class initialization registered the implementation
     * @return the description
     */
    public static MediaInfo probe(MediaSource source, ProbeOptions options) {
        Objects.requireNonNull(options, "options");
        return probe.probe(source, options);
    }
}
