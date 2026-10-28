/* Ports Little CMS 2.17 (cmsxform.c, cmspack.c). Copyright Marti Maria Saguer; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.color;

/**
 * Transforms floating-point pixels by evaluating a linked pipeline on every pixel, without lookup tables.
 *
 * <p>Source samples are brought to the pipeline's normalized encoding, L*a*b* divided as the version 4 encoding
 * prescribes and XYZ divided by {@link ColorMath#MAX_ENCODEABLE_XYZ}, and the outputs are brought back. Values
 * outside the nominal ranges pass through matrices and parametric and segmented curves; lookup tables, 16-bit and
 * floating-point, and tabulated curves clamp their inputs to {@code 0..1}. With a gamut check, a color the check
 * marks gets the alarm values.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @implNote This implementation follows Little CMS 2.17's floating-point transform ({@code FloatXFORM},
 * {@code cmsxform.c}) and its formatters ({@code cmspack.c}): the pipeline is only simplified, as
 * {@code PreOptimize} does, and a gamut check marks a color whose 16-bit check value is positive. Device values are
 * nominal {@code 0..1} for every color space, where Little CMS reads ink amounts as percents.
 */
final class PipelineFloatEngine implements FloatEngine {
    /**
     * The encoding of device values, used unchanged.
     */
    static final int DEVICE = 0;

    /**
     * The encoding of L*a*b* values in natural units.
     */
    static final int LAB = 1;

    /**
     * The encoding of XYZ values in natural units.
     */
    static final int XYZ = 2;

    /**
     * The encoding of named color indexes, on the source side only.
     */
    static final int INDEX = 3;

    /**
     * The encoding of the source samples.
     */
    private final int entry;

    /**
     * The encoding of the target samples.
     */
    private final int exit;

    /**
     * The number of source channels.
     */
    private final int inputs;

    /**
     * The number of target channels.
     */
    private final int outputs;

    /**
     * The number of named colors when the source is a named color profile, else zero.
     */
    private final int colors;

    /**
     * The gamut check, or {@code null}.
     */
    private final GamutCheck gamut;

    /**
     * The normalized alarm values, one per output.
     */
    private final float[] alarm;

    /**
     * The scratch space of each thread.
     */
    private final ThreadLocal<Scratch> scratch;

    /**
     * Creates an engine.
     *
     * @param pipeline the linked pipeline, no longer modified
     * @param entry the source encoding: {@link #DEVICE}, {@link #LAB}, {@link #XYZ} or {@link #INDEX}
     * @param exit the target encoding: {@link #DEVICE}, {@link #LAB} or {@link #XYZ}
     * @param gamut the gamut check, or {@code null}
     * @param alarm the 16-bit alarm values, one per output at least, when {@code gamut} is not {@code null}
     */
    PipelineFloatEngine(Pipeline pipeline, int entry, int exit, GamutCheck gamut, int[] alarm) {
        this.entry = entry;
        this.exit = exit;
        this.inputs = pipeline.inputs();
        this.outputs = pipeline.outputs();
        this.colors = entry == INDEX && !pipeline.stages().isEmpty()
                && pipeline.stages().getFirst() instanceof Stage.NamedColor list ? list.colors() : 0;
        this.gamut = gamut;
        this.alarm = new float[outputs];
        if (gamut != null) {
            for (var c = 0; c < outputs; c++) {
                this.alarm[c] = alarm[c] / 65535.0f;
            }
        }
        this.scratch = ThreadLocal.withInitial(() -> new Scratch(pipeline.evaluator(),
                gamut == null ? null : gamut.scratch(), gamut == null ? 0 : gamut.inputs()));
    }

    /**
     * Holds the scratch space of one thread.
     */
    private static final class Scratch {
        /**
         * The pipeline evaluator.
         */
        final Pipeline.Evaluator evaluator;

        /**
         * The normalized inputs.
         */
        final float[] in = new float[16];

        /**
         * The normalized outputs.
         */
        final float[] out = new float[16];

        /**
         * The 16-bit inputs of the gamut check, or {@code null}.
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
         * @param evaluator the pipeline evaluator
         * @param levels the gamut check's scratch space, or {@code null}
         * @param gamutInputs the number of gamut check inputs, or zero without one
         */
        Scratch(Pipeline.Evaluator evaluator, int[][] levels, int gamutInputs) {
            this.evaluator = evaluator;
            this.levels = levels;
            this.words = gamutInputs == 0 ? null : new int[gamutInputs];
        }
    }

    @Override
    public void apply(float[] src, int srcOffset, int srcStride, float[] dst, int dstOffset, int dstStride, int count) {
        var space = scratch.get();
        var in = space.in;
        var out = space.out;
        for (var i = 0; i < count; i++) {
            var s = srcOffset + i * srcStride;
            switch (entry) {
                case LAB -> {
                    in[0] = (float) (src[s] / 100.0);
                    in[1] = (float) ((src[s + 1] + 128) / 255.0);
                    in[2] = (float) ((src[s + 2] + 128) / 255.0);
                }
                case XYZ -> {
                    in[0] = (float) (src[s] / ColorMath.MAX_ENCODEABLE_XYZ);
                    in[1] = (float) (src[s + 1] / ColorMath.MAX_ENCODEABLE_XYZ);
                    in[2] = (float) (src[s + 2] / ColorMath.MAX_ENCODEABLE_XYZ);
                }
                case INDEX -> {
                    var value = src[s];
                    var index = value >= -0.5f && value < colors - 0.5f ? Math.round(value) : colors;
                    in[0] = (float) (Math.min(index, 0xFFFF) / 65535.0);
                }
                default -> System.arraycopy(src, s, in, 0, inputs);
            }
            if (gamut != null && isOutOfGamut(space)) {
                System.arraycopy(alarm, 0, out, 0, outputs);
            } else {
                space.evaluator.evaluate(in, out);
            }
            var d = dstOffset + i * dstStride;
            switch (exit) {
                case LAB -> {
                    dst[d] = (float) (out[0] * 100.0);
                    dst[d + 1] = (float) (out[1] * 255.0 - 128.0);
                    dst[d + 2] = (float) (out[2] * 255.0 - 128.0);
                }
                case XYZ -> {
                    dst[d] = (float) (out[0] * ColorMath.MAX_ENCODEABLE_XYZ);
                    dst[d + 1] = (float) (out[1] * ColorMath.MAX_ENCODEABLE_XYZ);
                    dst[d + 2] = (float) (out[2] * ColorMath.MAX_ENCODEABLE_XYZ);
                }
                default -> System.arraycopy(out, 0, dst, d, outputs);
            }
        }
    }

    /**
     * Returns whether the normalized inputs of the scratch space are out of the proofing gamut.
     *
     * @param space the scratch space, holding the inputs
     * @return {@code true} if the gamut check marks the color
     */
    private boolean isOutOfGamut(Scratch space) {
        var words = space.words;
        for (var c = 0; c < inputs; c++) {
            words[c] = ColorMath.saturateWord(space.in[c] * 65535.0);
        }
        return gamut.isOutOfGamut(words, space.marker, space.levels);
    }

    /**
     * {@return the encoding of a color space's samples: {@link #LAB}, {@link #XYZ} or {@link #DEVICE}}
     *
     * @param space the color space signature
     */
    static int encoding(int space) {
        if (space == IccReader.signature("Lab ")) {
            return LAB;
        }
        return space == IccReader.signature("XYZ ") ? XYZ : DEVICE;
    }
}
