package com.github.auties00.smalt.video.vp9.internal;

import java.util.Arrays;

/**
 * Hands the parsed superblock rows of one tile column from the thread that parses the tile to the
 * thread that reconstructs it.
 *
 * <p>The parsing thread appends, for every block of a superblock row, a record of two ints (the
 * block's position, size and whether it has a residual) and, for a block with a residual, the
 * coefficients of each of its transform blocks as an end-of-block position followed by the
 * position and value of each non-zero coefficient. It then publishes the row. The reconstructing
 * thread waits for a published row, replays its blocks in the order they were parsed, and
 * releases the row's slab. At most {@link #SLABS} rows are in flight; the parsing thread waits
 * for a free slab.
 *
 * @implNote This implementation passes ownership of a slab through the progress counters of a
 * two-row {@link Vp9Wavefront}: row 0 counts the rows parsed and row 1 the rows
 * reconstructed, so every slab write happens before the publication that lets the other
 * thread read it.
 */
final class Vp9ReconQueue {
    /**
     * The number of superblock rows that may be parsed ahead of the reconstruction.
     */
    static final int SLABS = 6;

    /**
     * The block records of each slab, two ints per block.
     */
    private final int[][] blocks = new int[SLABS][1024];

    /**
     * The number of ints in use in each slab of {@link #blocks}.
     */
    private final int[] blockLength = new int[SLABS];

    /**
     * The coefficients of each slab.
     */
    private final int[][] coefficients = new int[SLABS][16384];

    /**
     * The number of ints in use in each slab of {@link #coefficients}.
     */
    private final int[] coefficientLength = new int[SLABS];

    /**
     * The hand-over counters: row 0 counts the parsed rows, row 1 the reconstructed rows.
     */
    private final Vp9Wavefront gate = new Vp9Wavefront(2, false);

    /**
     * The number of rows the parsing thread has started, touched by that thread only.
     */
    private int parsed;

    /**
     * The number of rows the reconstructing thread has finished, touched by that thread only.
     */
    private int reconstructed;

    /**
     * The slab the parsing thread is filling.
     */
    private int current;

    /**
     * Creates an empty queue.
     */
    Vp9ReconQueue() {
    }

    /**
     * Empties the queue for a new frame.
     */
    void reset() {
        gate.reset(2);
        parsed = 0;
        reconstructed = 0;
    }

    /**
     * Wakes both threads with a cancellation when the other one failed.
     */
    void abort() {
        gate.abort();
    }

    /**
     * Starts a superblock row on the parsing thread, waiting until its slab has been released.
     */
    void beginRow() {
        if (parsed >= SLABS) {
            gate.awaitProgress(1, parsed - SLABS + 1);
        }
        current = parsed % SLABS;
        blockLength[current] = 0;
        coefficientLength[current] = 0;
    }

    /**
     * Publishes the superblock row the parsing thread has finished.
     */
    void endRow() {
        gate.publish(0, ++parsed);
    }

    /**
     * Appends the record of a block to the current row.
     *
     * @param position the block's unit row in the high 16 bits and unit column in the low 16 bits
     * @param shape the block size, the base-2 logarithms of its width and height in 4x4 units, and
     * bit 24 when the block has a residual
     */
    void block(int position, int shape) {
        var length = blockLength[current];
        var array = blocks[current];
        if (length + 2 > array.length) {
            array = blocks[current] = Arrays.copyOf(array, array.length * 2);
        }
        array[length] = position;
        array[length + 1] = shape;
        blockLength[current] = length + 2;
    }

    /**
     * Makes room in the current row for the coefficients of one transform block.
     *
     * @param ints the largest number of ints the transform block can take
     * @return the index in {@link #coefficients()} where the transform block starts
     */
    int reserve(int ints) {
        var length = coefficientLength[current];
        if (length + ints > coefficients[current].length) {
            coefficients[current] = Arrays.copyOf(coefficients[current],
                    Math.max(coefficients[current].length * 2, length + ints));
        }
        return length;
    }

    /**
     * Returns the coefficients of the row being parsed; valid until the next {@link #reserve}.
     *
     * @return the array
     */
    int[] coefficients() {
        return coefficients[current];
    }

    /**
     * Completes the transform block that starts at {@code cursor}: stores its end-of-block
     * position and the number of non-zero coefficients in front of their positions and values.
     *
     * @param cursor the index returned by {@link #reserve}
     * @param eob the number of coded coefficients
     * @param pairs the number of non-zero coefficients appended after the two header ints
     */
    void commit(int cursor, int eob, int pairs) {
        var array = coefficients[current];
        array[cursor] = eob;
        if (eob > 0) {
            array[cursor + 1] = pairs;
            coefficientLength[current] = cursor + 2 + 2 * pairs;
        } else {
            coefficientLength[current] = cursor + 1;
        }
    }

    /**
     * Waits on the reconstructing thread for the next parsed row and returns its slab.
     *
     * @return the slab index to pass to {@link #blocks} and {@link #coefficients}
     */
    int awaitRow() {
        gate.awaitProgress(0, reconstructed + 1);
        return reconstructed % SLABS;
    }

    /**
     * Returns the block records of a slab.
     *
     * @param slab the slab index
     * @return the records
     */
    int[] blocks(int slab) {
        return blocks[slab];
    }

    /**
     * Returns the number of ints in use in the block records of a slab.
     *
     * @param slab the slab index
     * @return the length
     */
    int blockLength(int slab) {
        return blockLength[slab];
    }

    /**
     * Returns the coefficients of a slab.
     *
     * @param slab the slab index
     * @return the coefficients
     */
    int[] coefficients(int slab) {
        return coefficients[slab];
    }

    /**
     * Releases the slab the reconstructing thread has finished.
     */
    void endReconstruction() {
        gate.publish(1, ++reconstructed);
    }
}
