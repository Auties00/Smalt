/* Ports Little CMS 2.17 (cmsxform.c). Copyright Marti Maria Saguer; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.color;

/**
 * Applies a proofing transform with a gamut check: colors the check marks get the alarm values, the others the
 * values of the inner engine.
 *
 * <p>8-bit samples are widened to 16 bits for the check, and 8-bit outputs receive the alarm values reduced to 8 bits
 * with rounding. Instances are immutable and thread-safe.
 *
 * @implNote This implementation follows Little CMS 2.17's {@code TransformOnePixelWithGamutCheck}
 * ({@code cmsxform.c}).
 */
final class GamutAlarmEngine implements ColorEngine {
    /**
     * The number of pixels checked per block.
     */
    private static final int BLOCK = 64;

    /**
     * The engine of the colors in gamut.
     */
    private final ColorEngine inner;

    /**
     * The gamut check.
     */
    private final GamutCheck gamut;

    /**
     * The number of source channels.
     */
    private final int inputs;

    /**
     * The number of target channels.
     */
    private final int outputs;

    /**
     * The 16-bit alarm values, one per output.
     */
    private final short[] alarm16;

    /**
     * The 8-bit alarm values, one per output.
     */
    private final byte[] alarm8;

    /**
     * The scratch space of each thread.
     */
    private final ThreadLocal<Scratch> scratch;

    /**
     * Creates an engine.
     *
     * @param inner the engine of the transform without the check
     * @param gamut the gamut check
     * @param inputs the number of source channels
     * @param outputs the number of target channels
     * @param alarm the 16-bit alarm values, at least one per output
     */
    GamutAlarmEngine(ColorEngine inner, GamutCheck gamut, int inputs, int outputs, int[] alarm) {
        this.inner = inner;
        this.gamut = gamut;
        this.inputs = inputs;
        this.outputs = outputs;
        this.alarm16 = new short[outputs];
        this.alarm8 = new byte[outputs];
        for (var c = 0; c < outputs; c++) {
            alarm16[c] = (short) alarm[c];
            alarm8[c] = (byte) ColorMath.wordToByte(alarm[c]);
        }
        this.scratch = ThreadLocal.withInitial(() -> new Scratch(gamut));
    }

    /**
     * Holds the scratch space of one thread.
     */
    private static final class Scratch {
        /**
         * Whether each pixel of the block is out of gamut.
         */
        final boolean[] marked = new boolean[BLOCK];

        /**
         * The 16-bit inputs of the gamut check, zero past the source channels.
         */
        final int[] words;

        /**
         * The output of the gamut check.
         */
        final int[] marker = new int[1];

        /**
         * The gamut check interpolator's scratch space, or {@code null}.
         */
        final int[][] levels;

        /**
         * Creates the scratch space.
         *
         * @param gamut the gamut check
         */
        Scratch(GamutCheck gamut) {
            this.words = new int[gamut.inputs()];
            this.levels = gamut.scratch();
        }
    }

    @Override
    public void apply(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int count) {
        var space = scratch.get();
        var marked = space.marked;
        var words = space.words;
        for (var start = 0; start < count; start += BLOCK) {
            var n = Math.min(BLOCK, count - start);
            for (var i = 0; i < n; i++) {
                var s = srcOffset + (start + i) * srcStride;
                for (var c = 0; c < inputs; c++) {
                    words[c] = (src[s + c] & 0xFF) * 257;
                }
                marked[i] = gamut.isOutOfGamut(words, space.marker, space.levels);
            }
            inner.apply(src, srcOffset + start * srcStride, srcStride, dst, dstOffset + start * dstStride, dstStride,
                    n);
            for (var i = 0; i < n; i++) {
                if (marked[i]) {
                    System.arraycopy(alarm8, 0, dst, dstOffset + (start + i) * dstStride, outputs);
                }
            }
        }
    }

    @Override
    public void apply(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride, int count) {
        var space = scratch.get();
        var marked = space.marked;
        var words = space.words;
        for (var start = 0; start < count; start += BLOCK) {
            var n = Math.min(BLOCK, count - start);
            for (var i = 0; i < n; i++) {
                var s = srcOffset + (start + i) * srcStride;
                for (var c = 0; c < inputs; c++) {
                    words[c] = src[s + c] & 0xFFFF;
                }
                marked[i] = gamut.isOutOfGamut(words, space.marker, space.levels);
            }
            inner.apply(src, srcOffset + start * srcStride, srcStride, dst, dstOffset + start * dstStride, dstStride,
                    n);
            for (var i = 0; i < n; i++) {
                if (marked[i]) {
                    System.arraycopy(alarm16, 0, dst, dstOffset + (start + i) * dstStride, outputs);
                }
            }
        }
    }
}
