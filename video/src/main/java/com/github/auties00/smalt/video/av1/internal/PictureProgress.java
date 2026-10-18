package com.github.auties00.smalt.video.av1.internal;

/**
 * Tracks the decoding of one picture for the frames decoded concurrently with it: the number of
 * leading sample rows that are final, whether its frame failed, and whether its decoding ended.
 *
 * <p>The decoding of the picture publishes its progress from any thread; other threads read it
 * without waiting, {@link FrameScheduler} queuing their work once the rows they need are final. A
 * picture whose decoding ended, successfully or not, has every row final.
 */
final class PictureProgress {
    /**
     * The number of leading luma rows whose samples are final, {@link Integer#MAX_VALUE} for all.
     */
    private volatile int rows;

    /**
     * Whether the decoding of the picture ended.
     */
    private volatile boolean done;

    /**
     * Whether the frame of the picture is corrupt.
     */
    private volatile boolean failed;

    /**
     * Creates the progress of a picture whose decoding ended.
     */
    PictureProgress() {
        rows = Integer.MAX_VALUE;
        done = true;
    }

    /**
     * Starts tracking a new decoding of the picture: no row is final; called before any other
     * frame can reference the picture.
     */
    void start() {
        rows = 0;
        failed = false;
        done = false;
    }

    /**
     * Publishes that the leading rows of the picture are final; called by one thread at a time.
     *
     * @param rows the number of leading luma rows, {@link Integer#MAX_VALUE} for all
     */
    void publishRows(int rows) {
        if (rows > this.rows) {
            this.rows = rows;
        }
    }

    /**
     * Returns the number of leading luma rows whose samples are final.
     *
     * @return the rows, {@link Integer#MAX_VALUE} once the decoding ended
     */
    int rows() {
        return rows;
    }

    /**
     * Marks the frame of the picture as corrupt; frames referencing it fail.
     */
    void fail() {
        failed = true;
    }

    /**
     * Returns whether the frame of the picture is corrupt.
     *
     * @return {@code true} after {@link #fail()}
     */
    boolean failed() {
        return failed;
    }

    /**
     * Publishes that the decoding of the picture ended: every row is final.
     */
    void finish() {
        rows = Integer.MAX_VALUE;
        done = true;
    }

    /**
     * Returns whether the decoding of the picture ended.
     *
     * @return {@code true} after {@link #finish()}
     */
    boolean done() {
        return done;
    }
}
