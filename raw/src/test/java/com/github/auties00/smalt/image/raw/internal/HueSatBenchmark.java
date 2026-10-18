package com.github.auties00.smalt.image.raw.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.raw.RawSamples;
import com.github.auties00.smalt.io.MediaSource;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Compares the per-pixel baseline of the DNG hue, saturation and value tables with the scalar and
 * Vector API row kernels on one 512x128 rendering tile, with the camera profile tables of the
 * Adobe DNG Converter 5D Mark III sample: its {@code ProfileHueSatMap} (90x30x1) and its
 * {@code ProfileLookTable} (36x8x16, sRGB-encoded value axis).
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
public class HueSatBenchmark {
    private static final int PIXELS = 512 * 128;

    @Param({"baseline", "scalar", "vector"})
    public String kind;

    @Param({"map", "look"})
    public String table;

    private HueSatTable kernelTable;
    private BaselineHueSatTable baselineTable;
    private HueSatKernel kernel;
    private float[] source;
    private float[] r;
    private float[] g;
    private float[] b;
    private final float[] rgb = new float[3];

    @Setup
    public void setup() {
        try (var input = MediaSource.of(RawSamples.ADC_5D3_LOSSLESS.fetch())) {
            var color = RawParser.parse(input, MediaLimits.DEFAULT).color;
            var dims = table.equals("map") ? color.hueSatDims : color.lookDims;
            var cells = table.equals("map") ? color.hueSatMap[0] : color.lookTable;
            var srgb = table.equals("map") ? color.hueSatSrgb : color.lookSrgb;
            kernelTable = new HueSatTable(dims, cells, srgb);
            baselineTable = new BaselineHueSatTable(dims, cells, srgb);
        }
        kernel = kind.equals("vector") ? VectorHueSatKernel.create() : new ScalarHueSatKernel();
        var random = new SplittableRandom(7);
        source = new float[3 * PIXELS];
        for (var index = 0; index < source.length; index++) {
            source[index] = (float) (random.nextDouble() * random.nextDouble() * 1.2);
        }
        r = new float[PIXELS];
        g = new float[PIXELS];
        b = new float[PIXELS];
    }

    @Benchmark
    public float tile() {
        System.arraycopy(source, 0, r, 0, PIXELS);
        System.arraycopy(source, PIXELS, g, 0, PIXELS);
        System.arraycopy(source, 2 * PIXELS, b, 0, PIXELS);
        if (kind.equals("baseline")) {
            for (var index = 0; index < PIXELS; index++) {
                rgb[0] = r[index];
                rgb[1] = g[index];
                rgb[2] = b[index];
                baselineTable.apply(rgb);
                r[index] = rgb[0];
                g[index] = rgb[1];
                b[index] = rgb[2];
            }
        } else {
            kernel.apply(kernelTable, r, g, b, PIXELS);
        }
        return r[17] + g[PIXELS - 1];
    }
}
