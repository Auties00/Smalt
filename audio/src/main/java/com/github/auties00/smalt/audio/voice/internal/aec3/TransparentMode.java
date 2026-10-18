package com.github.auties00.smalt.audio.voice.internal.aec3;

/**
 * Detects that there is no echo to cancel: when the adaptive filters never converge although the
 * render signal has been strong for several seconds, the suppressor turns transparent.
 *
 * @implNote This implementation ports {@code LegacyTransparentModeImpl} of WebRTC's
 * {@code modules/audio_processing/aec3/transparent_mode.cc}, the variant selected without field
 * trials, with {@code echo_removal_control.linear_and_stable_echo_path} off.
 */
final class TransparentMode {
    /**
     * The initial value of the non-converged and sane-filter sequence counters.
     */
    private static final int BLOCKS_SINCE_INIT = 10000;

    /**
     * The number of updates.
     */
    private long captureBlockCounter;

    /**
     * Whether transparent mode is active.
     */
    private boolean transparencyActivated;

    /**
     * The number of active render blocks since a sane filter was seen.
     */
    private long activeBlocksSinceSaneFilter = BLOCKS_SINCE_INIT;

    /**
     * Whether a sane filter has been seen.
     */
    private boolean saneFilterObserved;

    /**
     * Whether a finite echo return loss was detected recently.
     */
    private boolean finiteErlRecentlyDetected;

    /**
     * The number of blocks since a filter converged.
     */
    private long nonConvergedSequenceSize = BLOCKS_SINCE_INIT;

    /**
     * The number of consecutive blocks with every filter diverged.
     */
    private long divergedSequenceSize;

    /**
     * The number of active render blocks since a filter converged.
     */
    private long activeNonConvergedSequenceSize;

    /**
     * The number of blocks with a converged filter.
     */
    private long numConvergedBlocks;

    /**
     * Whether a filter converged during render activity recently.
     */
    private boolean recentConvergenceDuringActivity;

    /**
     * The number of strong, unsaturated render blocks.
     */
    private long strongNotSaturatedRenderBlocks;

    /**
     * Creates a detector in its initial state.
     */
    TransparentMode() {
    }

    /**
     * Returns whether transparent mode is active.
     *
     * @return whether the suppressor should be transparent
     */
    boolean active() {
        return transparencyActivated;
    }

    /**
     * Resets the sequence counters after an echo path change.
     */
    void reset() {
        nonConvergedSequenceSize = BLOCKS_SINCE_INIT;
        divergedSequenceSize = 0;
        strongNotSaturatedRenderBlocks = 0;
    }

    /**
     * Updates the detector with one block.
     *
     * @param filterDelayBlocks      the delay of the filter peak in blocks
     * @param anyFilterConsistent    whether a filter estimate is consistent
     * @param anyFilterConverged     whether a filter converged
     * @param allFiltersDiverged     whether every filter diverged
     * @param activeRender           whether the render block is active
     * @param saturatedCapture       whether the capture signal is saturated
     */
    void update(int filterDelayBlocks, boolean anyFilterConsistent, boolean anyFilterConverged, boolean allFiltersDiverged,
                boolean activeRender, boolean saturatedCapture) {
        ++captureBlockCounter;
        strongNotSaturatedRenderBlocks += activeRender && !saturatedCapture ? 1 : 0;
        if (anyFilterConsistent && filterDelayBlocks < 5) {
            saneFilterObserved = true;
            activeBlocksSinceSaneFilter = 0;
        } else if (activeRender) {
            ++activeBlocksSinceSaneFilter;
        }
        boolean saneFilterRecentlySeen;
        if (!saneFilterObserved) {
            saneFilterRecentlySeen = captureBlockCounter <= 5 * Aec3.NUM_BLOCKS_PER_SECOND;
        } else {
            saneFilterRecentlySeen = activeBlocksSinceSaneFilter <= 30 * Aec3.NUM_BLOCKS_PER_SECOND;
        }
        if (anyFilterConverged) {
            recentConvergenceDuringActivity = true;
            activeNonConvergedSequenceSize = 0;
            nonConvergedSequenceSize = 0;
            ++numConvergedBlocks;
        } else {
            if (++nonConvergedSequenceSize > 20 * Aec3.NUM_BLOCKS_PER_SECOND) {
                numConvergedBlocks = 0;
            }
            if (activeRender && ++activeNonConvergedSequenceSize > 60 * Aec3.NUM_BLOCKS_PER_SECOND) {
                recentConvergenceDuringActivity = false;
            }
        }
        if (!allFiltersDiverged) {
            divergedSequenceSize = 0;
        } else if (++divergedSequenceSize >= 60) {
            nonConvergedSequenceSize = BLOCKS_SINCE_INIT;
        }
        if (activeNonConvergedSequenceSize > 60 * Aec3.NUM_BLOCKS_PER_SECOND) {
            finiteErlRecentlyDetected = false;
        }
        if (numConvergedBlocks > 50) {
            finiteErlRecentlyDetected = true;
        }
        if (finiteErlRecentlyDetected) {
            transparencyActivated = false;
        } else if (saneFilterRecentlySeen && recentConvergenceDuringActivity) {
            transparencyActivated = false;
        } else {
            transparencyActivated = strongNotSaturatedRenderBlocks > 6 * Aec3.NUM_BLOCKS_PER_SECOND;
        }
    }
}
