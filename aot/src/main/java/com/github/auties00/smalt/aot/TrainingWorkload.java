package com.github.auties00.smalt.aot;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * A named, repeatable round of work that an ahead-of-time cache training run exercises.
 *
 * <p>{@link SmaltTraining} runs one round of every workload per cycle, in a fixed order, until a
 * cycle compiles no further method of the tracked packages ({@link TrainingOptions#trackedPackages()}).
 * Smalt's own workloads are selected through {@link TrainingFamily}; an application adds its own
 * through {@link TrainingOptions.Builder#addWorkloads(TrainingWorkload...)}, so that one training run
 * covers both.
 *
 * <p>A workload is created before the run starts, so that its setup (opening codecs, preparing
 * inputs) is not repeated, and keeps its state across rounds the way a long-running application
 * keeps its codecs. A round should take from a few milliseconds to a few hundred milliseconds and
 * take the code paths production takes, with representative inputs: the cache holds profiles of
 * the branches training takes only.
 *
 * @implSpec Implementations must allow {@link #round()} to be called any number of times from one
 *           thread. A round that throws ends the workload for the rest of the run; the run reports
 *           it in {@link TrainingReport#skipped()} and goes on with the other workloads.
 */
public interface TrainingWorkload {
    /**
     * Returns the name of this workload, unique within one training run.
     *
     * @return the name
     */
    String name();

    /**
     * Runs one round of this workload.
     *
     * @throws Exception if the round fails
     */
    void round() throws Exception;

    /**
     * Returns a workload that runs an action as its round.
     *
     * @param name  the name of the workload
     * @param round the work of one round
     * @return the workload
     * @throws NullPointerException if an argument is {@code null}
     */
    static TrainingWorkload of(String name, Runnable round) {
        Objects.requireNonNull(name, "name cannot be null");
        Objects.requireNonNull(round, "round cannot be null");
        return new TrainingWorkload() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void round() {
                round.run();
            }

            @Override
            public String toString() {
                return name;
            }
        };
    }

    /**
     * Returns a workload that creates its state with a setup action when the training run creates
     * its workloads, and then runs the rounds of the workload the setup returns.
     *
     * <p>{@link SmaltTraining#train(TrainingOptions)} runs the setup together with the setup of
     * Smalt's own workloads, counted in {@link TrainingReport#setup()}. A setup that throws, or
     * returns {@code null}, does not fail the run: the workload is reported in
     * {@link TrainingReport#skipped()} with the error and never runs a round. The setup runs at most
     * once; a workload that is not run by a training run runs it at its first {@link #round()}, and
     * a failed setup makes every round throw its exception. The returned workload's name is
     * {@code name}; the name of the workload the setup returns is not used.
     *
     * @apiNote This is the way to add a workload whose creation can fail on some platforms, such as
     * one that opens a device or loads an optional codec, without handling the failure in the
     * application:
     * {@snippet :
     * var options = TrainingOptions.builder()
     *         .addWorkloads(TrainingWorkload.deferred("camera-pipeline", () -> {
     *             var pipeline = CameraPipeline.open();
     *             return TrainingWorkload.of("camera-pipeline", pipeline::processFrame);
     *         }))
     *         .build();
     * }
     * @param name the name of the workload
     * @param setup the action that creates the workload, called at most once
     * @return the workload
     */
    static TrainingWorkload deferred(String name, Callable<? extends TrainingWorkload> setup) {
        return new DeferredTrainingWorkload(name, setup);
    }
}
