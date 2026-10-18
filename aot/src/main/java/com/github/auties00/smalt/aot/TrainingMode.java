package com.github.auties00.smalt.aot;

/**
 * The coding mode a training workload exercises.
 *
 * <p>Every workload of a {@link TrainingFamily} has one mode; {@link TrainingOptions#modes()}
 * selects the modes a run trains, so that a run can train only the paths whose first-use latency
 * matters to an application.
 */
public enum TrainingMode {
    /**
     * Coding of files and of streams prepared ahead of time: still images, file encoders and
     * decoders with any delay and lookahead, transcoding, remuxing, probing, metadata and the
     * picture and sample processing around them.
     */
    FILE,

    /**
     * Real-time coding: the low-delay encoders and decoders that code one frame or packet at a time
     * under live rate control, and the processing around them (capture conversion, voice
     * processing).
     */
    REALTIME
}
