package com.github.auties00.smalt.video.av1.internal;

/**
 * Supplies the coefficients of transform blocks to the decoder's reconstruction in place of the
 * coefficient syntax of the bitstream.
 *
 * <p>The encoder installs a source in {@link TaskContext#coefSource} and runs the decoder's block
 * reconstruction, so the prediction, the inverse transforms and every context update are exactly
 * the decoder's; the source sees each transform block after its prediction is in the picture, in
 * the order the decoder reads the coefficient syntax, and chooses, writes or costs its
 * coefficients.
 */
public interface CoefficientSource {
    /**
     * Supplies the coefficients of one transform block, as the decoder's coefficient reader
     * produces them.
     *
     * <p>The block's position is the task context's {@link TaskContext#bx} and
     * {@link TaskContext#by}, in luma 4-sample units, and its prediction is already in the frame's
     * picture.
     *
     * @implSpec Implementations must leave the transform type in {@link TaskContext#txtp} and the
     * neighbor context byte in {@link TaskContext#cfCtx}, store the dequantized coefficients in
     * {@code cf} in the decoder's layout (the entries they do not use stay zero) and return what
     * the decoder's reader returns: the scan position of the last non-zero coefficient, or
     * {@code -1} when every coefficient is zero.
     *
     * @param t     the task context
     * @param a     the above coefficient contexts
     * @param aOff  the first above entry
     * @param l     the left coefficient contexts
     * @param lOff  the first left entry
     * @param tx    the transform size
     * @param bs    the block size
     * @param b     the block
     * @param intra whether the block is intra
     * @param plane the plane, 0 for luma
     * @param cf    the coefficient buffer, all zero on entry
     * @return the scan position of the last non-zero coefficient, or {@code -1}
     */
    int code(TaskContext t, byte[] a, int aOff, byte[] l, int lOff, int tx, int bs, Av1Block b, boolean intra,
             int plane, int[] cf);
}
