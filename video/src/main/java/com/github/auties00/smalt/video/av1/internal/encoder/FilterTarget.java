package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.Av1Picture;
import com.github.auties00.smalt.video.av1.internal.FrameContext;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;

/**
 * Exposes a reconstructed frame and its source to the in-loop filter searches
 * ({@link LoopFilterSearch} and {@link CdefSearch}), so the still picture encoder and the video
 * frame encoder share them.
 *
 * <p>The reconstruction and the source have the same plane layout and strides.
 */
interface FilterTarget {
    /**
     * Returns the decoder-side state of the frame, holding the reconstruction.
     *
     * @return the frame context
     */
    FrameContext context();

    /**
     * Returns the frame header the searches set the filter parameters in.
     *
     * @return the header
     */
    FrameHeader header();

    /**
     * Returns the source picture.
     *
     * @return the source
     */
    Av1Picture sourcePicture();

    /**
     * Returns the picture width.
     *
     * @return the width
     */
    int width();

    /**
     * Returns the picture height.
     *
     * @return the height
     */
    int height();

    /**
     * Returns the pixel layout, one of the {@link com.github.auties00.smalt.video.av1.internal.SequenceHeader}
     * layouts.
     *
     * @return the layout
     */
    int layout();

    /**
     * Returns whether chroma is subsampled horizontally.
     *
     * @return 0 or 1
     */
    int ssHor();

    /**
     * Returns whether chroma is subsampled vertically.
     *
     * @return 0 or 1
     */
    int ssVer();

    /**
     * Returns the bit depth.
     *
     * @return 8, 10 or 12
     */
    int bitDepth();

    /**
     * Returns the largest sample value.
     *
     * @return the value
     */
    int bdMax();

    /**
     * Returns the high bit depth index of the sequence header: 0 for 8 bits, 1 for 10, 2 for 12.
     *
     * @return the index
     */
    int hbd();

    /**
     * Returns the shift that brings squared errors at the bit depth to the 8-bit scale.
     *
     * @return the shift
     */
    int distShift();

    /**
     * Returns the number of 64x64 superblocks per row.
     *
     * @return the count
     */
    int sbCols();

    /**
     * Returns the number of 64x64 superblock rows.
     *
     * @return the count
     */
    int sbRows();

    /**
     * Returns the Lagrangian multiplier of the frame, at the 8-bit scale.
     *
     * @return the multiplier
     */
    int rdmult();

    /**
     * Returns whether the searches may run on several threads.
     *
     * @return {@code true} when bands of rows may run in parallel
     */
    boolean parallel();

    /**
     * Records the CDEF index of a superblock for the bitstream.
     *
     * @param sbIndex the superblock index in raster order
     * @param idx the index
     */
    void setCdefIndex(int sbIndex, int idx);
}
