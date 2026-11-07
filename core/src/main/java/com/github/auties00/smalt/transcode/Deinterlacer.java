package com.github.auties00.smalt.transcode;

/**
 * Enumerates how an interlaced frame is turned into a progressive one.
 *
 * <p>Each method keeps the rows of one field and rebuilds the rows of the other from them, so a
 * frame becomes the picture of one instant. At the frame rate, the first field in display order is
 * kept; at the field rate ({@link VideoTranscodeOptions#fieldRate()}) each field becomes a frame of
 * its own. Chroma and alpha planes are processed as luma is.
 *
 * <p>Constants may be added in minor releases, so a {@code switch} over this type needs a
 * {@code default} branch.
 */
public enum Deinterlacer {
    /**
     * Repeats each kept row in place of the missing row below it: the cheapest method, which halves
     * the vertical resolution and shows stair steps on diagonal edges.
     */
    BOB,

    /**
     * Rebuilds each missing row as the rounded mean of the kept rows above and below it.
     */
    LINEAR,

    /**
     * Rebuilds each missing sample with an edge-based line average: of the pairs of kept samples
     * above and below it along the vertical and the two diagonals one sample apart, the pair that
     * differs least gives the rounded mean, so diagonal edges stay smooth. A tie keeps the vertical.
     */
    ELA
}
