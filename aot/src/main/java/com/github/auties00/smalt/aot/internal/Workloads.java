package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingFamily;

import java.util.List;
import java.util.Objects;

/**
 * Lists the workloads of each {@link TrainingFamily}, in the order a training run creates and
 * runs them.
 */
public final class Workloads {
    /**
     * Prevents instantiation.
     */
    private Workloads() {
        throw new AssertionError();
    }

    /**
     * Returns the factories of a family's workloads, of every coding mode.
     *
     * @param family the family whose workloads are listed
     * @return the factories, in order
     * @throws NullPointerException if {@code family} is {@code null}
     */
    public static List<WorkloadFactory> of(TrainingFamily family) {
        Objects.requireNonNull(family, "family cannot be null");
        return switch (family) {
            case IMAGE -> ImageWorkloads.factories();
            case AUDIO -> AudioWorkloads.factories();
            case VIDEO -> VideoWorkloads.factories();
            case MPEG -> MpegWorkloads.factories();
            case CAPTURE -> CaptureWorkloads.factories();
            case RAW -> RawWorkloads.factories();
            case TRANSCODE -> TranscodeWorkloads.factories();
        };
    }
}
