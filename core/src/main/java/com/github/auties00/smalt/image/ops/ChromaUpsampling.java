package com.github.auties00.smalt.image.ops;

/**
 * Enumerates the ways {@link ImageOps#toRgba(com.github.auties00.smalt.image.Picture, ChromaUpsampling)}
 * reconstructs full-resolution chroma from subsampled (4:2:0, 4:2:2 or 4:4:0) planes.
 *
 * <p>Both methods assume centered chroma siting (JPEG, WebP and MPEG-1 style): each chroma sample
 * sits at the center of the luma samples it covers.
 */
public enum ChromaUpsampling {
    /**
     * Interpolates with the triangle filter: every output pixel mixes its own chroma sample with
     * weight 3/4 and the nearest neighboring sample with weight 1/4, per subsampled axis (libjpeg's
     * "fancy upsampling"). Samples outside the plane are replaced by the nearest edge sample, and
     * the chroma of a horizontally halved plane at most 2 samples wide is replicated instead, as
     * libjpeg does. This is the default and matches libjpeg-turbo's decoder output bit for bit.
     */
    TRIANGLE,

    /**
     * Replicates each chroma sample over the pixels it covers. It is faster and exactly invertible
     * by box averaging, but shows blocky color edges.
     */
    NEAREST
}
