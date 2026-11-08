package com.github.auties00.smalt.image.color;

/**
 * Transforms named color indexes through a pipeline that starts with a named color lookup, one table entry per
 * color.
 *
 * <p>The pipeline is evaluated without simplification on 16-bit samples for every index, and the 8-bit outputs
 * are the 16-bit ones reduced with rounding. An index past the last color converts as the index just past it, whose
 * lookup gives zeros.
 *
 * <p>Instances are immutable and thread-safe.
 *
 * @implNote This implementation follows Little CMS 2.17, which never optimizes a named color pipeline
 * ({@code _cmsOptimizePipeline}, {@code cmsopt.c}) and evaluates it per pixel on 16-bit samples
 * ({@code _LUTeval16}, {@code cmslut.c}); tabulating every index gives the same values.
 */
final class NamedColorEngine implements ColorEngine {
    /**
     * The number of table entries per depth: the colors and one entry for the indexes past them, at most 65536.
     */
    private final int entries;

    /**
     * The number of outputs.
     */
    private final int outputs;

    /**
     * The 16-bit outputs of every table entry, {@code outputs} per entry.
     */
    private final short[] table16;

    /**
     * The 8-bit outputs of every table entry, {@code outputs} per entry.
     */
    private final byte[] table8;

    /**
     * Tabulates a pipeline.
     *
     * @param pipeline the linked pipeline, whose first stage is a {@link Stage.NamedColor}
     */
    NamedColorEngine(Pipeline pipeline) {
        var colors = ((Stage.NamedColor) pipeline.stages().getFirst()).colors();
        this.entries = Math.min(colors + 1, 0x10000);
        this.outputs = pipeline.outputs();
        this.table16 = new short[entries * outputs];
        this.table8 = new byte[entries * outputs];
        var in = new int[1];
        var out = new int[outputs];
        for (var index = 0; index < entries; index++) {
            in[0] = index;
            pipeline.evaluate16(in, out);
            for (var o = 0; o < outputs; o++) {
                table16[index * outputs + o] = (short) out[o];
                table8[index * outputs + o] = (byte) ColorMath.wordToByte(out[o]);
            }
        }
    }

    @Override
    public void apply(byte[] src, int srcOffset, int srcStride, byte[] dst, int dstOffset, int dstStride, int count) {
        for (var i = 0; i < count; i++) {
            var entry = Math.min(src[srcOffset + i * srcStride] & 0xFF, entries - 1) * outputs;
            System.arraycopy(table8, entry, dst, dstOffset + i * dstStride, outputs);
        }
    }

    @Override
    public void apply(short[] src, int srcOffset, int srcStride, short[] dst, int dstOffset, int dstStride,
                      int count) {
        for (var i = 0; i < count; i++) {
            var entry = Math.min(src[srcOffset + i * srcStride] & 0xFFFF, entries - 1) * outputs;
            System.arraycopy(table16, entry, dst, dstOffset + i * dstStride, outputs);
        }
    }
}
