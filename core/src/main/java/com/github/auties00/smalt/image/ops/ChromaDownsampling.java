package com.github.auties00.smalt.image.ops;

/**
 * Enumerates the ways {@link ImageOps#toYuv(com.github.auties00.smalt.image.Picture,
 * com.github.auties00.smalt.image.ChromaFormat, com.github.auties00.smalt.image.ColorInfo,
 * ChromaDownsampling)} computes a subsampled chroma sample from the pixels it covers.
 */
public enum ChromaDownsampling {
    /**
     * Averages the color of every covered pixel with equal weight, rounding once. This is the
     * default.
     */
    AVERAGE,

    /**
     * Weights the color of every covered pixel by its alpha, so fully transparent pixels, whose
     * color is invisible and often arbitrary, do not tint the chroma of visible neighbors. A
     * block whose pixels are all fully transparent falls back to {@link #AVERAGE}. Opaque
     * pictures produce the same result as {@link #AVERAGE}.
     */
    ALPHA_WEIGHTED
}
