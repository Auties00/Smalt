package com.github.auties00.smalt.audio.vorbis.internal;

/**
 * Holds the configuration of a residue of type 0, 1 or 2: the coded range, the partitioning and
 * the books of each partition class and stage, plus the encoder's classification thresholds.
 *
 * @implNote This implementation mirrors libvorbis's {@code vorbis_info_residue0} (BSD-3).
 */
public final class Residue0Info {
    /**
     * The first coded line.
     */
    public int begin;

    /**
     * The end of the coded lines.
     */
    public int end;

    /**
     * The number of lines per partition.
     */
    public int grouping;

    /**
     * The number of partition classes.
     */
    public int partitions;

    /**
     * The number of partition class combinations the group book codes.
     */
    public int partVals;

    /**
     * The book coding the partition classes.
     */
    public int groupBook;

    /**
     * The stage bitmask of each partition class.
     */
    public final int[] secondStages = new int[64];

    /**
     * The books of the stages, in class then stage order.
     */
    public final int[] bookList = new int[512];

    /**
     * The largest magnitude of each class (the first classification metric).
     */
    public final int[] classMetric1 = new int[64];

    /**
     * The second classification metric of each class: the largest summed magnitude for residue
     * 1, the largest angle magnitude for residue 2, {@code -1} when unused.
     */
    public final int[] classMetric2 = new int[64];

    /**
     * Creates an empty configuration.
     */
    public Residue0Info() {
    }

    /**
     * Returns a copy of this configuration.
     *
     * @return the copy
     */
    public Residue0Info copy() {
        var copy = new Residue0Info();
        copy.begin = begin;
        copy.end = end;
        copy.grouping = grouping;
        copy.partitions = partitions;
        copy.partVals = partVals;
        copy.groupBook = groupBook;
        System.arraycopy(secondStages, 0, copy.secondStages, 0, 64);
        System.arraycopy(bookList, 0, copy.bookList, 0, 512);
        System.arraycopy(classMetric1, 0, copy.classMetric1, 0, 64);
        System.arraycopy(classMetric2, 0, copy.classMetric2, 0, 64);
        return copy;
    }
}
