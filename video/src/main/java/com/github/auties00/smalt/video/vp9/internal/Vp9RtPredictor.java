package com.github.auties00.smalt.video.vp9.internal;

/**
 * Builds the inter predictions of the VP9 encoder exactly as the decoder builds them
 * ({@code dec_build_inter_predictors}).
 *
 * <p>A reference picture, whose border repeats its edge samples as the decoder's edge emulation
 * does, is filtered with the decoder's sub-pixel filters. Only unscaled references occur in the
 * encoder. Instances are not thread-safe: they hold the filters' scratch memory.
 */
final class Vp9RtPredictor {
    /**
     * The decoder's pixel kernels.
     */
    private final Vp9Dsp dsp = Vp9Dsp.INSTANCE;

    /**
     * The decoder's scalar kernels, which predict the blocks 4 samples wide.
     *
     * <p>The decoder's 4-wide vector filters use 64-bit vectors, which the C2 compiler does not
     * always compile into vector instructions in an encoder's call profile; the scalar filters
     * are as fast on 16 samples and return the same prediction.
     */
    private final ScalarVp9Dsp scalarDsp = new ScalarVp9Dsp();

    /**
     * Scratch memory of the sub-pixel filters.
     */
    private final byte[] convolveScratch = new byte[Vp9Dsp.CONVOLVE_SCRATCH];

    /**
     * Predicts a block from an unscaled reference picture ({@code dec_build_inter_predictors}).
     *
     * @param ref the reference picture
     * @param plane the plane index, 0 for luma and 1 or 2 for chroma
     * @param x0 the block's column in the plane
     * @param y0 the block's row in the plane
     * @param mv the packed motion vector in 1/8 luma pixel
     * @param filter the interpolation filter
     * @param dst the plane receiving the prediction
     * @param dstOff the index of the block's top-left sample in {@code dst}
     * @param dstStride the row stride of {@code dst}
     * @param w the block width
     * @param h the block height
     * @param average whether to average into the destination, for the second reference
     */
    void predictInter(Vp9EncPicture ref, int plane, int x0, int y0, int mv, int filter, byte[] dst, int dstOff,
                      int dstStride, int w, int h, boolean average) {
        var ss = plane == 0 ? 0 : 1;
        var mvRow = (mv >> 16) * (1 << (1 - ss));
        var mvCol = (short) mv * (1 << (1 - ss));
        var subX = mvCol & 15;
        var subY = mvRow & 15;
        var x = x0 + (mvCol >> 4);
        var y = y0 + (mvRow >> 4);
        var stride = ref.strides[plane];
        (w <= 4 ? scalarDsp : dsp).convolve(ref.planes[plane], ref.origins[plane] + y * stride + x, stride, dst,
                dstOff, dstStride, Vp9Constants.FILTER_KERNELS, filter * 16 * 8, subX, 16, subY, 16, w, h, average,
                convolveScratch);
    }
}
