package com.github.auties00.smalt.video.av1.internal;

/**
 * Holds the sample planes of one decoded AV1 frame and the headers it was coded with.
 *
 * <p>Samples are unsigned values stored in {@code short} planes at every bit depth. The planes are
 * allocated larger than the frame: widths and heights are rounded up to 128 samples, because block
 * reconstruction writes whole blocks past the right and bottom frame edges. The chroma planes share
 * one stride and are absent for monochrome frames.
 *
 * <p>A picture is reference counted: decoding, every reference slot, every frame in flight that
 * predicts from it and the output each hold one reference, and the last {@link #release()} returns
 * the planes to the pool they came from. Instances are not thread-safe: references are taken and
 * released on the decoding thread only.
 *
 * @implNote This implementation mirrors dav1d's {@code Dav1dPicture} with the plane geometry of
 * its default allocator ({@code dav1d_default_picture_alloc}).
 */
public final class Av1Picture {
    /**
     * The luma, first chroma and second chroma planes; the chroma planes are {@code null} for
     * monochrome frames.
     */
    public final short[][] data = new short[3][];

    /**
     * The luma stride ({@code [0]}) and the chroma stride ({@code [1]}), in samples.
     */
    public final int[] stride = new int[2];

    /**
     * The frame width, in samples.
     */
    public int w;

    /**
     * The frame height, in samples.
     */
    public int h;

    /**
     * The pixel layout, a {@code SequenceHeader.LAYOUT_} constant.
     */
    public int layout;

    /**
     * The number of bits per sample: 8, 10 or 12.
     */
    public int bpc;

    /**
     * The header of the frame, or {@code null} for the output frame of a tile list OBU.
     */
    public FrameHeader frameHdr;

    /**
     * The sequence header the frame belongs to.
     */
    public SequenceHeader seqHdr;


    /**
     * The presentation timestamp of the packet the frame was coded in.
     */
    public long pts;

    /**
     * The duration of the packet the frame was coded in.
     */
    public long duration;

    /**
     * The progress of the decoding of this picture, which the frames decoded concurrently with it
     * wait for.
     */
    final PictureProgress progress = new PictureProgress();

    /**
     * The number, in decoding order, of the frame that decoded this picture last.
     */
    long frame;

    /**
     * The number of holders of this picture.
     */
    private int refs;

    /**
     * The pool the planes return to, or {@code null} for none.
     */
    private final Av1PicturePool pool;

    /**
     * Allocates a picture's planes.
     *
     * @param pool the pool the planes return to, or {@code null} for none
     * @param w the frame width
     * @param h the frame height
     * @param layout the pixel layout
     * @param bpc the bits per sample
     */
    Av1Picture(Av1PicturePool pool, int w, int h, int layout, int bpc) {
        this.pool = pool;
        this.w = w;
        this.h = h;
        this.layout = layout;
        this.bpc = bpc;
        var alignedW = (w + 127) & ~127;
        var alignedH = (h + 127) & ~127;
        var yStride = alignedW;
        if ((yStride & 511) == 0) {
            yStride += 32;
        }
        stride[0] = yStride;
        data[0] = new short[yStride * alignedH];
        if (layout != SequenceHeader.LAYOUT_I400) {
            var ssHor = layout != SequenceHeader.LAYOUT_I444 ? 1 : 0;
            var ssVer = layout == SequenceHeader.LAYOUT_I420 ? 1 : 0;
            var uvStride = yStride >> ssHor;
            stride[1] = uvStride;
            data[1] = new short[uvStride * (alignedH >> ssVer)];
            data[2] = new short[uvStride * (alignedH >> ssVer)];
        }
        refs = 1;
    }

    /**
     * Allocates a picture that belongs to no pool, for frames the caller provides, such as the
     * anchor frames of large scale tile decoding.
     *
     * @param w the frame width
     * @param h the frame height
     * @param layout the pixel layout, a {@code SequenceHeader.LAYOUT_} constant
     * @param bpc the bits per sample: 8, 10 or 12
     * @return the picture, with one holder and zero samples
     */
    public static Av1Picture allocate(int w, int h, int layout, int bpc) {
        return new Av1Picture(null, w, h, layout, bpc);
    }

    /**
     * Returns the number of samples the planes of a picture of a geometry hold, padding included.
     *
     * @param w the frame width
     * @param h the frame height
     * @param layout the pixel layout
     * @return the sample count of the three planes
     */
    public static long samples(int w, int h, int layout) {
        long alignedW = ((w + 127L) & ~127L) + 32;
        long alignedH = (h + 127L) & ~127L;
        long luma = alignedW * alignedH;
        if (layout == SequenceHeader.LAYOUT_I400) {
            return luma;
        }
        var shift = (layout != SequenceHeader.LAYOUT_I444 ? 1 : 0) + (layout == SequenceHeader.LAYOUT_I420 ? 1 : 0);
        return luma + 2 * (luma >> shift);
    }

    /**
     * Returns whether this picture's planes fit a geometry, so that they can be reused for it.
     *
     * @param w the frame width
     * @param h the frame height
     * @param layout the pixel layout
     * @return {@code true} when the allocation matches
     */
    boolean fits(int w, int h, int layout) {
        return this.layout == layout && ((this.w + 127) & ~127) == ((w + 127) & ~127)
                && ((this.h + 127) & ~127) == ((h + 127) & ~127);
    }

    /**
     * Resets the metadata of a pooled picture before reuse, leaving it with one holder.
     *
     * @param w the frame width
     * @param h the frame height
     * @param bpc the bits per sample
     */
    void reset(int w, int h, int bpc) {
        this.w = w;
        this.h = h;
        this.bpc = bpc;
        this.frameHdr = null;
        this.seqHdr = null;
        this.pts = 0;
        this.duration = 0;
        this.refs = 1;
    }

    /**
     * Adds a holder of this picture.
     *
     * @return this picture
     */
    public Av1Picture retain() {
        refs++;
        return this;
    }

    /**
     * Removes a holder of this picture, returning the planes to the pool when none is left.
     */
    public void release() {
        if (--refs == 0 && pool != null) {
            pool.recycle(this);
        }
    }
}
