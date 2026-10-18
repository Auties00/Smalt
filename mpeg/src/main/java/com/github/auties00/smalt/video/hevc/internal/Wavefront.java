package com.github.auties00.smalt.video.hevc.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.task.RowProgress;

/**
 * Tracks the progress of the wavefront rows of a slice segment decoded concurrently, one row per
 * thread (ITU-T H.265 wavefront parallel processing): a row may decode a coding tree block once
 * the row above has decoded the block above and to the right of it.
 *
 * <p>The rows of one batch are consecutive; the row before the first is complete before the batch
 * starts. When a row fails, the rows waiting for it stop with a failure too, so no thread waits
 * forever.
 */
final class Wavefront {
    /**
     * The number of coding tree blocks of a row.
     */
    private final int width;

    /**
     * The number of decoded coding tree blocks of each row of the batch, indexed from the first
     * row.
     */
    private final RowProgress progress = new RowProgress();

    /**
     * The first row of the batch.
     */
    private int firstRow;

    /**
     * Constructs a tracker for rows of a picture.
     *
     * @param width the number of coding tree blocks of a row
     */
    Wavefront(int width) {
        this.width = width;
    }

    /**
     * {@return the number of coding tree blocks of a row}
     */
    int width() {
        return width;
    }

    /**
     * Starts a batch of rows, none of which has decoded anything yet.
     *
     * @param firstRow the first row of the batch
     * @param rows the number of rows of the batch
     */
    void begin(int firstRow, int rows) {
        this.firstRow = firstRow;
        progress.begin(rows);
    }

    /**
     * Waits until the row above a coding tree block has decoded the blocks the block depends on:
     * up to the block above and to the right, or the block above at the end of the row.
     *
     * @param row the row of the block
     * @param x the column of the block
     * @throws MediaException.Malformed if a row of the batch failed
     */
    void awaitAbove(int row, int x) {
        if (row != firstRow && !progress.await(row - 1 - firstRow, Math.min(x + 2, width))) {
            throw new MediaException.Malformed("an earlier wavefront row of the slice segment is corrupt");
        }
    }

    /**
     * Publishes the number of decoded coding tree blocks of a row, waking the row below when it
     * waits.
     *
     * @param row the row, of the batch
     * @param decoded the number of its decoded coding tree blocks
     */
    void advance(int row, int decoded) {
        progress.advance(row - firstRow, decoded);
    }

    /**
     * Marks the batch as failed, releasing the rows that wait.
     */
    void fail() {
        progress.fail();
    }
}
