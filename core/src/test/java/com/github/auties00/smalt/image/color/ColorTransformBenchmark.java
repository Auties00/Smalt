package com.github.auties00.smalt.image.color;

import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.ColorPrimaries;
import com.github.auties00.smalt.image.ColorRange;
import com.github.auties00.smalt.image.MatrixCoefficients;
import com.github.auties00.smalt.image.TransferCharacteristics;
import com.github.auties00.smalt.internal.simd.Simd;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Measures single-threaded transforms of a 12-megapixel (4032x3024) picture, the size of the
 * Little CMS comparison ({@code tools/testdata/lcms-bench.py}): Display P3 to sRGB (matrix/TRC),
 * the color.org v4 appearance profile to sRGB (prelinearized lookup table), FOGRA39 CMYK to sRGB
 * (4-input lookup table), and at 16 bits Display P3 to sRGB and PQ BT.2020 to sRGB. The
 * {@code kernel} parameter runs the 8-bit matrix-shaper kernel alone, scalar against vector.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 3, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx2g"})
public class ColorTransformBenchmark {
    private static final int WIDTH = 4032;
    private static final int HEIGHT = 3024;

    @Param({"p3", "lut", "cmyk", "p3-16", "pq-16"})
    public String transform;

    private ColorTransform active;
    private byte[] source8;
    private byte[] target8;
    private short[] source16;
    private short[] target16;
    private boolean wide;
    private int stride;

    @Setup(Level.Trial)
    public void setup() {
        if (!Simd.ENABLED) {
            throw new IllegalStateException("the Vector API is not enabled in the benchmark JVM");
        }
        var p3 = ColorProfile.displayP3();
        active = switch (transform) {
            case "p3", "p3-16" -> ColorTransform.create(p3, ColorProfile.srgb());
            case "lut" -> ColorTransform.create(TestProfiles.profile("skia-icc-color.org-sRGB_ICC_v4_Appearance.icc"),
                    ColorProfile.srgb());
            case "cmyk" -> ColorTransform.create(TestProfiles.profile("skia-icc-misc-Coated_FOGRA39_CMYK.icc"),
                    ColorProfile.srgb());
            default -> ColorTransform.create(ColorProfile.of(new ColorInfo(MatrixCoefficients.BT2020_NCL,
                    ColorRange.LIMITED, ColorPrimaries.BT2020, TransferCharacteristics.PQ)), ColorProfile.srgb());
        };
        wide = transform.endsWith("-16");
        stride = 4;
        var random = new SplittableRandom(42);
        var pixels = WIDTH * HEIGHT;
        if (wide) {
            source16 = new short[pixels * 4];
            for (var i = 0; i < source16.length; i++) {
                source16[i] = (short) (((i / 4) % WIDTH) * 16 + random.nextInt(4096));
            }
            target16 = new short[pixels * 4];
            active.transform(source16, 0, stride, target16, 0, stride, 1);
        } else {
            source8 = new byte[pixels * 4];
            for (var i = 0; i < source8.length; i++) {
                source8[i] = (byte) (((i / 4) % WIDTH) / 16 + random.nextInt(32));
            }
            target8 = new byte[pixels * 4];
            active.transform(source8, 0, stride, target8, 0, stride, 1);
        }
    }

    @Benchmark
    public Object transform() {
        if (wide) {
            active.transform(source16, 0, stride, target16, 0, stride, WIDTH * HEIGHT);
            return target16;
        }
        active.transform(source8, 0, stride, target8, 0, stride, WIDTH * HEIGHT);
        return target8;
    }

    /**
     * Runs the 8-bit Display P3 to sRGB matrix-shaper kernel alone, scalar against vector.
     */
    @State(Scope.Benchmark)
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Fork(value = 3, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx2g"})
    public static class Kernel {
        @Param({"scalar", "vector"})
        public String kernel;

        private MatShaperKernel implementation;
        private MatShaperKernel.Tables tables;
        private byte[] source;
        private byte[] target;
        private int[] scratch;

        @Setup(Level.Trial)
        public void setup() {
            implementation = kernel.equals("vector") ? VectorMatShaperKernel.create() : new ScalarMatShaperKernel();
            var transform = ColorTransform.create(ColorProfile.displayP3(), ColorProfile.srgb());
            tables = ((MatShaperEngine) transform.engine8()).tables();
            source = new byte[WIDTH * HEIGHT * 4];
            new SplittableRandom(7).nextBytes(source);
            target = new byte[source.length];
            scratch = new int[MatShaperKernel.SCRATCH];
        }

        @Benchmark
        public byte[] matShaper() {
            var pixels = WIDTH * HEIGHT;
            for (var start = 0; start < pixels; start += MatShaperKernel.BLOCK) {
                var n = Math.min(MatShaperKernel.BLOCK, pixels - start);
                implementation.apply(source, start * 4, 4, target, start * 4, 4, n, tables, scratch);
            }
            return target;
        }
    }
}
