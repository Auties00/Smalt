package com.github.auties00.smalt.video.h264.internal;

import java.util.Arrays;

/**
 * Carries everything a {@link PictureDecoder} needs to decode one slice: the parsed header,
 * the unescaped payload, the reference picture lists and the slice numbering.
 *
 * <p>When slices are decoded on another thread, a task owns copies of all of them, because the
 * engine reuses its own buffers for the next NAL unit.
 */
final class SliceTask {
    /**
     * The parsed slice header.
     */
    H264SliceHeader header;

    /**
     * The reference picture lists, 33 entries each.
     */
    final DecoderFrame[][] lists;

    /**
     * The unescaped slice NAL unit payload followed by zero padding.
     */
    byte[] rbsp;

    /**
     * The payload length.
     */
    int size;

    /**
     * The unique number of the slice.
     */
    int sliceNum;

    /**
     * The macroblock to slice group map, or {@code null} with a single slice group.
     */
    int[] sliceGroupMap;

    /**
     * The picture order count of each list entry when the lists were built, indexed
     * {@code [list][refIdx]}.
     */
    final int[][] refPoc = new int[2][33];

    /**
     * The reference marking ({@link DecoderFrame#SHORT_TERM} or {@link DecoderFrame#LONG_TERM})
     * of each list entry when the lists were built.
     */
    final int[][] refMarking = new int[2][33];

    /**
     * The picture order count of the picture the slice belongs to.
     */
    int currentPoc;

    /**
     * Records the picture order counts and markings of the list entries and of the current
     * picture, which the engine may change while the slice is decoded on another thread.
     *
     * @param currentPoc the picture order count of the current picture
     * @param counts the number of active entries of each list
     */
    void captureReferences(int currentPoc, int[] counts) {
        this.currentPoc = currentPoc;
        for (var list = 0; list < 2; list++) {
            for (var i = 0; i < counts[list]; i++) {
                var entry = lists[list][i];
                refPoc[list][i] = entry.poc;
                refMarking[list][i] = entry.reference;
            }
        }
    }

    /**
     * Constructs a task with its own header and list storage.
     */
    SliceTask() {
        this.header = new H264SliceHeader();
        this.lists = new DecoderFrame[2][33];
    }

    /**
     * Constructs a task that refers to existing header and list storage.
     *
     * @param header the slice header the task decodes
     * @param lists the reference picture lists
     */
    SliceTask(H264SliceHeader header, DecoderFrame[][] lists) {
        this.header = header;
        this.lists = lists;
    }

    /**
     * Points this task at a header and returns it.
     *
     * @param header the slice header the task decodes
     * @return this task
     */
    SliceTask forHeader(H264SliceHeader header) {
        this.header = header;
        return this;
    }

    /**
     * Returns a task holding copies of a slice, independent of the buffers they came from.
     *
     * @param header the slice header
     * @param rbsp the payload buffer
     * @param size the payload length
     * @param lists the reference picture lists
     * @param sliceNum the slice number
     * @param sliceGroupMap the slice group map, or {@code null}
     * @return the independent task
     */
    static SliceTask copyOf(H264SliceHeader header, byte[] rbsp, int size, DecoderFrame[][] lists, int sliceNum,
                            int[] sliceGroupMap) {
        var task = new SliceTask();
        task.header.copyFrom(header);
        task.rbsp = Arrays.copyOf(rbsp, size + H264BitReader.PADDING);
        task.size = size;
        System.arraycopy(lists[0], 0, task.lists[0], 0, lists[0].length);
        System.arraycopy(lists[1], 0, task.lists[1], 0, lists[1].length);
        task.sliceNum = sliceNum;
        task.sliceGroupMap = sliceGroupMap == null ? null : sliceGroupMap.clone();
        return task;
    }
}
