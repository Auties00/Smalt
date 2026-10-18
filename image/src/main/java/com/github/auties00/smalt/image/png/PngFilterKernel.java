package com.github.auties00.smalt.image.png;

import com.github.auties00.smalt.internal.simd.Simd;

/**
 * Applies and reverses the five scanline filters of PNG filter method 0 (PNG 3rd edition 9.2),
 * and expands 8-bit RGB scanlines to RGBA.
 *
 * <p>Every method works on one scanline of {@code length} bytes, with the filter distance
 * {@code distance} (the {@code bpp} of the specification: the byte distance
 * between corresponding bytes of adjacent pixels, at least 1). The prior scanline is passed
 * explicitly; for the first scanline of an image or interlace pass the caller passes an all-zero
 * array, which the specification defines as the prior row. Bytes before the first pixel of a
 * scanline count as zero, so the first {@code distance} bytes are handled without reading before
 * the scanline. Reconstruction reads the filtered bytes from one array and writes the
 * reconstructed scanline to another range that must not overlap them, which lets vector
 * implementations write a few bytes past the pixel they reconstruct (within the scanline) and
 * overwrite them on the next step.
 *
 * <p>The forward filters used by the encoder also return the filter cost of the produced row: the
 * sum over all bytes of the absolute value of the byte read as a signed two's-complement number,
 * the per-row heuristic PNG 3rd edition 12.7 recommends.
 *
 * <p>Implementations must produce exactly the bytes the scalar reference
 * {@link ScalarPngFilterKernel} produces, for every distance from 1 to 8 and every length, and
 * must not read or write outside the given ranges. Arguments are not checked; values outside the
 * documented ranges give unspecified results.
 */
sealed interface PngFilterKernel permits ScalarPngFilterKernel, VectorPngFilterKernel {
    /**
     * The kernel of this JVM: the Vector API implementation when {@link Simd#ENABLED}, the scalar
     * reference otherwise.
     */
    PngFilterKernel INSTANCE = Simd.ENABLED ? VectorPngFilterKernel.create() : new ScalarPngFilterKernel();

    /**
     * Reverses the Sub filter: {@code Recon(x) = Filt(x) + Recon(a)}.
     *
     * @param raw the filtered scanline
     * @param rawOffset the index of its first byte
     * @param out the destination of the reconstructed scanline; must not overlap the
     * filtered bytes
     * @param outOffset the index of the first reconstructed byte
     * @param length the number of bytes in the scanline
     * @param distance the filter distance, from 1 to 8
     */
    void unfilterSub(byte[] raw, int rawOffset, byte[] out, int outOffset, int length, int distance);

    /**
     * Reverses the Up filter: {@code Recon(x) = Filt(x) + Recon(b)}.
     *
     * @param raw the filtered scanline
     * @param rawOffset the index of its first byte
     * @param out the destination of the reconstructed scanline; must not overlap the
     * filtered bytes or the prior scanline
     * @param outOffset the index of the first reconstructed byte
     * @param prior the reconstructed prior scanline
     * @param priorOffset the index of its first byte
     * @param length the number of bytes in the scanline
     */
    void unfilterUp(byte[] raw, int rawOffset, byte[] out, int outOffset, byte[] prior, int priorOffset, int length);

    /**
     * Reverses the Average filter:
     * {@code Recon(x) = Filt(x) + floor((Recon(a) + Recon(b)) / 2)}.
     *
     * @param raw the filtered scanline
     * @param rawOffset the index of its first byte
     * @param out the destination of the reconstructed scanline; must not overlap the
     * filtered bytes or the prior scanline
     * @param outOffset the index of the first reconstructed byte
     * @param prior the reconstructed prior scanline
     * @param priorOffset the index of its first byte
     * @param length the number of bytes in the scanline
     * @param distance the filter distance, from 1 to 8
     */
    void unfilterAverage(byte[] raw, int rawOffset, byte[] out, int outOffset, byte[] prior, int priorOffset,
                         int length, int distance);

    /**
     * Reverses the Paeth filter:
     * {@code Recon(x) = Filt(x) + PaethPredictor(Recon(a), Recon(b), Recon(c))}.
     *
     * @param raw the filtered scanline
     * @param rawOffset the index of its first byte
     * @param out the destination of the reconstructed scanline; must not overlap the
     * filtered bytes or the prior scanline
     * @param outOffset the index of the first reconstructed byte
     * @param prior the reconstructed prior scanline
     * @param priorOffset the index of its first byte
     * @param length the number of bytes in the scanline
     * @param distance the filter distance, from 1 to 8
     */
    void unfilterPaeth(byte[] raw, int rawOffset, byte[] out, int outOffset, byte[] prior, int priorOffset,
                       int length, int distance);

    /**
     * Reverses the Average filter of two consecutive scanlines, the second one's prior scanline
     * being the first one's reconstruction.
     *
     * <p>The result equals reconstructing the first scanline and then the second; implementations
     * may interleave the two to overlap their serial dependency chains.
     *
     * @param raw the array holding both filtered scanlines
     * @param firstOffset the index of the first filtered scanline
     * @param secondOffset the index of the second filtered scanline
     * @param first the destination of the first reconstructed scanline
     * @param firstOut the index of its first byte
     * @param second the destination of the second reconstructed scanline
     * @param secondOut the index of its first byte
     * @param prior the reconstructed scanline above the first one
     * @param priorOffset the index of its first byte
     * @param length the number of bytes in each scanline
     * @param distance the filter distance, from 1 to 8
     */
    void unfilterAveragePair(byte[] raw, int firstOffset, int secondOffset, byte[] first, int firstOut, byte[] second,
                          int secondOut, byte[] prior, int priorOffset, int length, int distance);

    /**
     * Reverses the Paeth filter of two consecutive scanlines, the second one's prior scanline
     * being the first one's reconstruction.
     *
     * <p>The result equals reconstructing the first scanline and then the second; implementations
     * may interleave the two to overlap their serial dependency chains.
     *
     * @param raw the array holding both filtered scanlines
     * @param firstOffset the index of the first filtered scanline
     * @param secondOffset the index of the second filtered scanline
     * @param first the destination of the first reconstructed scanline
     * @param firstOut the index of its first byte
     * @param second the destination of the second reconstructed scanline
     * @param secondOut the index of its first byte
     * @param prior the reconstructed scanline above the first one
     * @param priorOffset the index of its first byte
     * @param length the number of bytes in each scanline
     * @param distance the filter distance, from 1 to 8
     */
    void unfilterPaethPair(byte[] raw, int firstOffset, int secondOffset, byte[] first, int firstOut, byte[] second,
                          int secondOut, byte[] prior, int priorOffset, int length, int distance);

    /**
     * Expands packed 8-bit RGB pixels to RGBA with alpha 255.
     *
     * @param source the RGB samples
     * @param sourceOffset the index of the first sample
     * @param target the destination; must not overlap the source
     * @param targetOffset the index of the first destination byte
     * @param count the number of pixels
     */
    void expandRgb(byte[] source, int sourceOffset, byte[] target, int targetOffset, int count);

    /**
     * Copies a scanline unfiltered (filter type None) and returns its filter cost.
     *
     * @param row the scanline
     * @param offset the index of the first byte of the scanline
     * @param length the number of bytes in the scanline
     * @param out the destination
     * @param outOffset the index of the first destination byte
     * @return the filter cost of the copied bytes
     */
    int filterNone(byte[] row, int offset, int length, byte[] out, int outOffset);

    /**
     * Applies the Sub filter: {@code Filt(x) = Orig(x) - Orig(a)}, and returns the filter cost.
     *
     * @param row the scanline
     * @param offset the index of the first byte of the scanline
     * @param length the number of bytes in the scanline
     * @param distance the filter distance, from 1 to 8
     * @param out the destination
     * @param outOffset the index of the first destination byte
     * @return the filter cost of the filtered bytes
     */
    int filterSub(byte[] row, int offset, int length, int distance, byte[] out, int outOffset);

    /**
     * Applies the Up filter: {@code Filt(x) = Orig(x) - Orig(b)}, and returns the filter cost.
     *
     * @param row the scanline
     * @param offset the index of the first byte of the scanline
     * @param prior the prior scanline, unfiltered
     * @param priorOffset the index of the first byte of the prior scanline
     * @param length the number of bytes in the scanline
     * @param out the destination
     * @param outOffset the index of the first destination byte
     * @return the filter cost of the filtered bytes
     */
    int filterUp(byte[] row, int offset, byte[] prior, int priorOffset, int length, byte[] out, int outOffset);

    /**
     * Applies the Average filter: {@code Filt(x) = Orig(x) - floor((Orig(a) + Orig(b)) / 2)}, and
     * returns the filter cost.
     *
     * @param row the scanline
     * @param offset the index of the first byte of the scanline
     * @param prior the prior scanline, unfiltered
     * @param priorOffset the index of the first byte of the prior scanline
     * @param length the number of bytes in the scanline
     * @param distance the filter distance, from 1 to 8
     * @param out the destination
     * @param outOffset the index of the first destination byte
     * @return the filter cost of the filtered bytes
     */
    int filterAverage(byte[] row, int offset, byte[] prior, int priorOffset, int length, int distance,
                      byte[] out, int outOffset);

    /**
     * Applies the Paeth filter:
     * {@code Filt(x) = Orig(x) - PaethPredictor(Orig(a), Orig(b), Orig(c))}, and returns the
     * filter cost.
     *
     * @param row the scanline
     * @param offset the index of the first byte of the scanline
     * @param prior the prior scanline, unfiltered
     * @param priorOffset the index of the first byte of the prior scanline
     * @param length the number of bytes in the scanline
     * @param distance the filter distance, from 1 to 8
     * @param out the destination
     * @param outOffset the index of the first destination byte
     * @return the filter cost of the filtered bytes
     */
    int filterPaeth(byte[] row, int offset, byte[] prior, int priorOffset, int length, int distance,
                    byte[] out, int outOffset);
}
