package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingFamily;
import com.github.auties00.smalt.aot.TrainingWorkload;

import java.util.List;

import static com.github.auties00.smalt.aot.internal.WorkloadFactory.realtime;

/**
 * Creates the workload of {@link TrainingFamily#CAPTURE}: the conversion kernels of
 * {@code smalt-capture}, which turn the pixels the operating systems' cameras and screens deliver
 * into 4:2:0 pictures.
 *
 * <p>The kernels of every platform run on synthetic frames, whatever the platform of the training
 * run, so the workload needs no camera and no screen.
 */
final class CaptureWorkloads {
    /**
     * The rounds of every platform's kernels per workload round.
     */
    private static final int REPEATS = 10;

    /**
     * Prevents instantiation.
     */
    private CaptureWorkloads() {
        throw new AssertionError();
    }

    /**
     * Returns the factories of the family's workloads.
     *
     * @return the factories, in order
     */
    static List<WorkloadFactory> factories() {
        return List.of(realtime("capture-convert", CaptureWorkloads::convert));
    }

    /**
     * Returns the workload that runs every platform's conversion kernels.
     *
     * @return the workload
     */
    private static TrainingWorkload convert() {
        return TrainingWorkload.of("capture-convert", () -> {
            for (var i = 0; i < REPEATS; i++) {
                com.github.auties00.smalt.capture.internal.convert.CaptureTrainingRound.round();
            }
        });
    }
}
