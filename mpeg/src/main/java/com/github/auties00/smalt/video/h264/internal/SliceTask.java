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
     * The reference picture lists, 33 entries each: the frames holding the reference pictures.
     */
    final DecoderFrame[][] lists;

    /**
     * The structure of each reference picture list entry, {@link DecoderFrame#FRAME} for a frame
     * or complementary field pair and {@link DecoderFrame#TOP} or {@link DecoderFrame#BOTTOM} for
     * a field, indexed {@code [list][refIdx]}.
     */
    final byte[][] structures;

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
     * The picture order counts of the fields of each frame list entry when the lists were built,
     * indexed {@code [list][2 * refIdx + parity]}, for the field macroblocks of MBAFF frames.
     */
    final int[][] refFieldPoc = new int[2][66];

    /**
     * The reference markings of the fields of each frame list entry when the lists were built,
     * indexed like {@link #refFieldPoc}.
     */
    final int[][] refFieldMarking = new int[2][66];

    /**
     * Whether each list entry was a {@linkplain DecoderFrame#staleReference stale} long-term
     * reference when the lists were built, indexed {@code [list][refIdx]}; a loss reported later
     * does not concern the slice.
     */
    final boolean[][] refStale = new boolean[2][33];

    /**
     * The picture order count of the picture the slice belongs to.
     */
    int currentPoc;

    /**
     * The picture order counts of the top and bottom fields of the frame the slice belongs to,
     * for the field macroblocks of MBAFF frames.
     */
    final int[] currentFieldPoc = new int[2];

    /**
     * Records the picture order counts, markings and staleness of the list entries and of the
     * current picture, which the engine may change while the slice is decoded on another thread.
     *
     * @param currentPoc the picture order count of the current picture
     * @param topPoc the top field order count of the current frame
     * @param bottomPoc the bottom field order count of the current frame
     * @param counts the number of active entries of each list
     */
    void captureReferences(int currentPoc, int topPoc, int bottomPoc, int[] counts) {
        this.currentPoc = currentPoc;
        currentFieldPoc[0] = topPoc;
        currentFieldPoc[1] = bottomPoc;
        for (var list = 0; list < 2; list++) {
            for (var i = 0; i < counts[list]; i++) {
                var entry = lists[list][i];
                var structure = structures[list][i];
                refPoc[list][i] = entry.structurePoc(structure);
                refMarking[list][i] = entry.structureMarking(structure);
                refStale[list][i] = entry.staleReference;
                refFieldPoc[list][2 * i] = entry.topPoc;
                refFieldPoc[list][2 * i + 1] = entry.bottomPoc;
                refFieldMarking[list][2 * i] = entry.refTop;
                refFieldMarking[list][2 * i + 1] = entry.refBottom;
            }
        }
    }

    /**
     * Constructs a task with its own header and list storage.
     */
    SliceTask() {
        this.header = new H264SliceHeader();
        this.lists = new DecoderFrame[2][33];
        this.structures = new byte[2][33];
    }

    /**
     * Constructs a task that refers to existing header and list storage.
     *
     * @param header the slice header the task decodes
     * @param lists the reference picture lists
     * @param structures the structures of the list entries
     */
    SliceTask(H264SliceHeader header, DecoderFrame[][] lists, byte[][] structures) {
        this.header = header;
        this.lists = lists;
        this.structures = structures;
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
     * @param structures the structures of the list entries
     * @param sliceNum the slice number
     * @param sliceGroupMap the slice group map, or {@code null}
     * @return the independent task
     */
    static SliceTask copyOf(H264SliceHeader header, byte[] rbsp, int size, DecoderFrame[][] lists,
                            byte[][] structures, int sliceNum, int[] sliceGroupMap) {
        var task = new SliceTask();
        task.header.copyFrom(header);
        task.rbsp = Arrays.copyOf(rbsp, size + H264BitReader.PADDING);
        task.size = size;
        System.arraycopy(lists[0], 0, task.lists[0], 0, lists[0].length);
        System.arraycopy(lists[1], 0, task.lists[1], 0, lists[1].length);
        System.arraycopy(structures[0], 0, task.structures[0], 0, structures[0].length);
        System.arraycopy(structures[1], 0, task.structures[1], 0, structures[1].length);
        task.sliceNum = sliceNum;
        task.sliceGroupMap = sliceGroupMap == null ? null : sliceGroupMap.clone();
        return task;
    }
}
