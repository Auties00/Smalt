package com.github.auties00.smalt.image.color;

/**
 * Transforms 8-bit and 16-bit pixels by evaluating a linked pipeline on every pixel, for pipelines of so many inputs
 * that their lookup table would be too large.
 *
 * <p>8-bit samples are widened to 16 bits as {@code (v << 8) | v}; the pipeline is evaluated in floating point on the
 * 16-bit samples divided by 65535, its outputs rounded back to 16 bits, and 8-bit outputs reduced from those with
 * rounding.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @implNote This implementation follows Little CMS 2.17's evaluation of a pipeline it does not optimize
 * ({@code _LUTeval16}, {@code cmslut.c}), which it uses when the lookup table of {@code OptimizeByResampling}
 * ({@code cmsopt.c}) cannot be allocated: for ten inputs and more at its default 7 grid points per input.
 */
final class PipelineEngine implements ColorEngine {
    /**
     * The number of inputs.
     */
    private final int inputs;

    /**
     * The number of outputs.
     */
    private final int outputs;

    /**
     * The scratch space of each thread.
     */
    private final ThreadLocal<Scratch> scratch;

    /**
     * Creates the engine.
     *
     * @param pipeline the linked and simplified pipeline, which is not modified afterwards
     */
    PipelineEngine(Pipeline pipeline) {
        this.inputs = pipeline.inputs();
        this.outputs = pipeline.outputs();
        this.scratch = ThreadLocal.withInitial(() -> new Scratch(pipeline.evaluator()));
    }

    /**
     * Holds the scratch space of one thread.
     *
     * @param evaluator the pipeline evaluator
     * @param in the normalized inputs
     * @param out the normalized outputs
     */
    private record Scratch(Pipeline.Evaluator evaluator, float[] in, float[] out) {
        /**
         * Creates the scratch space of an evaluator.
         *
         * @param evaluator the pipeline evaluator
         */
        Scratch(Pipeline.Evaluator evaluator) {
            this(evaluator, new float[16], new float[16]);
        }
    }

    @Override
    public void apply(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int count) {
        var space = scratch.get();
        var in = space.in();
        var out = space.out();
        for (var i = 0; i < count; i++) {
            var s = srcOffset + i * srcStride;
            for (var c = 0; c < inputs; c++) {
                var value = src[s + c] & 0xFF;
                in[c] = ((value << 8) | value) / 65535.0f;
            }
            space.evaluator().evaluate(in, out);
            var d = dstOffset + i * dstStride;
            for (var c = 0; c < outputs; c++) {
                dst[d + c] = (byte) ColorMath.wordToByte(ColorMath.saturateWord(out[c] * 65535.0));
            }
        }
    }

    @Override
    public void apply(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride,
                      int count) {
        var space = scratch.get();
        var in = space.in();
        var out = space.out();
        for (var i = 0; i < count; i++) {
            var s = srcOffset + i * srcStride;
            for (var c = 0; c < inputs; c++) {
                in[c] = (src[s + c] & 0xFFFF) / 65535.0f;
            }
            space.evaluator().evaluate(in, out);
            var d = dstOffset + i * dstStride;
            for (var c = 0; c < outputs; c++) {
                dst[d + c] = (short) ColorMath.saturateWord(out[c] * 65535.0);
            }
        }
    }
}
