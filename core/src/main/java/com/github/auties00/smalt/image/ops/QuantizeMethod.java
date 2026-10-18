package com.github.auties00.smalt.image.ops;

/**
 * Enumerates the algorithms {@link ImageQuantization#quantize} builds a palette with when a
 * picture has more colors than the palette can hold.
 *
 * <p>Instances are immutable and thread-safe.
 */
public enum QuantizeMethod {
    /**
     * Splits the color histogram recursively at the variance-minimizing point of the channel of
     * largest variance (median cut after Heckbert, with the split of Wan, Wong and
     * Prusinkiewicz), then refines the entries with two k-means iterations. Fast and good on
     * most pictures.
     */
    MEDIAN_CUT,

    /**
     * Trains Anthony Dekker's NeuQuant self-organizing map on a sample of the colors: slower,
     * often better on photographs with smooth gradients.
     */
    NEUQUANT
}
