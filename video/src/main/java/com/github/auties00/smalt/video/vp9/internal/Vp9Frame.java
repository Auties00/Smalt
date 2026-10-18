package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;

/**
 * Holds one decoded VP9 frame: its three sample planes, its geometry, and the motion vectors
 * the next frame may predict from.
 *
 * <p>Samples are bytes for 8-bit frames and shorts for 10 and 12-bit frames. Sample
 * {@code (x, y)} of a plane is at index {@code y * stride + x}; there is no top or left border.
 * The planes extend past the frame by the area that blocks overhanging the frame edge write and
 * a margin for vector kernels, so that every write of the decoder stays in bounds.
 *
 * <p>Frames are pooled by the decoder and reused once no reference slot holds them.
 *
 * @implNote This implementation replaces libvpx's {@code YV12_BUFFER_CONFIG} and
 *           {@code RefCntBuffer} ({@code vpx_scale/yv12config.h},
 *           {@code vp9/common/vp9_onyxc_int.h}, BSD-3). The decoder never extends the frame
 *           borders: as in libvpx's VP9 decoder, inter prediction extends the reference edge on
 *           the fly when a block reads outside the frame.
 */
public final class Vp9Frame {
    /**
     * The margin, in samples, beyond the 8-aligned frame size on the right and below: blocks
     * overhang the 8-aligned size by at most 24 luma samples, and vector kernels read up to 16
     * samples past the end of a row.
     */
    private static final int MARGIN = 64;

    /**
     * The extra rows below the 8-aligned frame height.
     */
    private static final int ROW_MARGIN = 32;

    /**
     * The width in luma samples.
     */
    public int width;

    /**
     * The height in luma samples.
     */
    public int height;

    /**
     * The width rounded up to a multiple of 8, the width of the decoded area.
     */
    public int alignedWidth;

    /**
     * The height rounded up to a multiple of 8, the height of the decoded area.
     */
    public int alignedHeight;

    /**
     * The horizontal chroma subsampling shift.
     */
    public int ssX;

    /**
     * The vertical chroma subsampling shift.
     */
    public int ssY;

    /**
     * The bit depth: 8, 10 or 12.
     */
    public int bitDepth;

    /**
     * The 8-bit planes, luma then Cb then Cr, or {@code null} entries for a high bit depth
     * frame.
     */
    public final byte[][] planes8 = new byte[3][];

    /**
     * The high bit depth planes, luma then Cb then Cr, or {@code null} entries for an 8-bit
     * frame.
     */
    public final short[][] planes16 = new short[3][];

    /**
     * The row strides of the planes.
     */
    public final int[] strides = new int[3];

    /**
     * The decoded widths of the planes, the 8-aligned width subsampled (libvpx's
     * {@code y_width} and {@code uv_width}).
     */
    public final int[] planeWidths = new int[3];

    /**
     * The decoded heights of the planes, the 8-aligned height subsampled.
     */
    public final int[] planeHeights = new int[3];

    /**
     * The visible widths of the planes (libvpx's {@code y_crop_width} and
     * {@code uv_crop_width}).
     */
    public final int[] cropWidths = new int[3];

    /**
     * The visible heights of the planes.
     */
    public final int[] cropHeights = new int[3];

    /**
     * The motion vectors of the frame's blocks, four entries per 8x8 unit in raster order: the
     * first reference, the second reference, and the two packed vectors.
     */
    public int[] mvs = new int[0];

    /**
     * The number of 8x8 unit columns {@link #mvs} is laid out for.
     */
    public int mvCols;

    /**
     * The number of 8x8 unit rows {@link #mvs} is laid out for.
     */
    public int mvRows;

    /**
     * The number of reference slots and decoding stages holding the frame.
     */
    public int refCount;

    /**
     * The color space code of the frame header.
     */
    public int colorSpace;

    /**
     * Whether the samples use the full range.
     */
    public boolean fullRange;

    /**
     * The intended display width.
     */
    public int renderWidth;

    /**
     * The intended display height.
     */
    public int renderHeight;

    /**
     * Whether the frame was decoded as a key frame.
     */
    public boolean keyFrame;

    /**
     * Creates an empty frame.
     */
    public Vp9Frame() {
    }

    /**
     * Returns whether the samples are 16-bit.
     *
     * @return {@code true} for 10 and 12-bit frames
     */
    public boolean highBitDepth() {
        return bitDepth > 8;
    }

    /**
     * Returns the number of bytes the planes of a frame of a given format take.
     *
     * @param width    the width in luma samples
     * @param height   the height in luma samples
     * @param ssX      the horizontal chroma subsampling shift
     * @param ssY      the vertical chroma subsampling shift
     * @param bitDepth the bit depth
     * @return the size in bytes
     */
    public static long allocationSize(int width, int height, int ssX, int ssY, int bitDepth) {
        long alignedWidth = (width + 7) & ~7;
        long alignedHeight = (height + 7) & ~7;
        var luma = (alignedWidth + MARGIN) * (alignedHeight + ROW_MARGIN);
        var chroma = ((alignedWidth >> ssX) + MARGIN) * ((alignedHeight >> ssY) + ROW_MARGIN);
        return (luma + 2 * chroma) * (bitDepth > 8 ? 2 : 1);
    }

    /**
     * Sizes the frame for a format, reusing the planes when they are large enough.
     *
     * @param width    the width in luma samples
     * @param height   the height in luma samples
     * @param ssX      the horizontal chroma subsampling shift
     * @param ssY      the vertical chroma subsampling shift
     * @param bitDepth the bit depth: 8, 10 or 12
     * @param limits   the limits checked before any allocation
     * @throws com.github.auties00.smalt.MediaException.LimitExceeded if the frame exceeds
     *                                                                       the limits
     */
    public void allocate(int width, int height, int ssX, int ssY, int bitDepth, MediaLimits limits) {
        LimitChecks.checkPicture(limits, width, height);
        LimitChecks.checkAllocation(limits, allocationSize(width, height, ssX, ssY, bitDepth));
        this.width = width;
        this.height = height;
        this.alignedWidth = (width + 7) & ~7;
        this.alignedHeight = (height + 7) & ~7;
        this.ssX = ssX;
        this.ssY = ssY;
        var high = bitDepth > 8;
        if (high != (this.bitDepth > 8) || this.bitDepth == 0) {
            for (var i = 0; i < 3; i++) {
                planes8[i] = null;
                planes16[i] = null;
            }
        }
        this.bitDepth = bitDepth;
        for (var plane = 0; plane < 3; plane++) {
            var sx = plane == 0 ? 0 : ssX;
            var sy = plane == 0 ? 0 : ssY;
            planeWidths[plane] = alignedWidth >> sx;
            planeHeights[plane] = alignedHeight >> sy;
            cropWidths[plane] = (width + sx) >> sx;
            cropHeights[plane] = (height + sy) >> sy;
            var stride = planeWidths[plane] + MARGIN;
            strides[plane] = stride;
            var size = stride * (planeHeights[plane] + ROW_MARGIN);
            if (high) {
                if (planes16[plane] == null || planes16[plane].length < size) {
                    planes16[plane] = new short[size];
                }
            } else if (planes8[plane] == null || planes8[plane].length < size) {
                planes8[plane] = new byte[size];
            }
        }
    }

    /**
     * Sizes the motion vector storage for a grid of 8x8 units, reusing it when large enough.
     *
     * @param rows the number of unit rows
     * @param cols the number of unit columns
     */
    public void allocateMvs(int rows, int cols) {
        var size = rows * cols * 4;
        if (mvs.length < size) {
            mvs = new int[size];
        }
        mvRows = rows;
        mvCols = cols;
    }
}
