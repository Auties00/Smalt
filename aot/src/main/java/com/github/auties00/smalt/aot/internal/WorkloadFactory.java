package com.github.auties00.smalt.aot.internal;

import com.github.auties00.smalt.aot.TrainingMode;
import com.github.auties00.smalt.aot.TrainingWorkload;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Creates one of Smalt's training workloads, deferring its setup until the training run starts.
 *
 * @param name    the name of the workload, under which it is also reported when it cannot be
 *                created
 * @param mode    the coding mode the workload exercises
 * @param creator the setup of the workload
 */
public record WorkloadFactory(String name, TrainingMode mode, Callable<TrainingWorkload> creator) {
    /**
     * Creates a factory.
     *
     * @param name    the name of the workload
     * @param mode    the coding mode the workload exercises
     * @param creator the setup of the workload
     * @throws NullPointerException if an argument is {@code null}
     */
    public WorkloadFactory {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(mode, "mode cannot be null");
        Objects.requireNonNull(creator, "creator cannot be null");
    }

    /**
     * Returns a factory of a {@link TrainingMode#FILE} workload.
     *
     * @param name    the name of the workload
     * @param creator the setup of the workload
     * @return the factory
     * @throws NullPointerException if an argument is {@code null}
     */
    static WorkloadFactory file(String name, Callable<TrainingWorkload> creator) {
        return new WorkloadFactory(name, TrainingMode.FILE, creator);
    }

    /**
     * Returns a factory of a {@link TrainingMode#REALTIME} workload.
     *
     * @param name    the name of the workload
     * @param creator the setup of the workload
     * @return the factory
     * @throws NullPointerException if an argument is {@code null}
     */
    static WorkloadFactory realtime(String name, Callable<TrainingWorkload> creator) {
        return new WorkloadFactory(name, TrainingMode.REALTIME, creator);
    }

    /**
     * Runs the setup and returns the workload.
     *
     * @return the workload
     * @throws Exception if the workload cannot be prepared on this platform
     */
    public TrainingWorkload create() throws Exception {
        return creator.call();
    }
}
