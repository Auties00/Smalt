package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.internal.simd.Simd;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Measures the image operations on a 12-megapixel (4032x3024) opaque RGBA photo, the size of the
 * Pillow and libvips comparison in {@code tools/testdata/imageops-bench.py}: Gaussian blurs, an
 * arbitrary rotation, the resize-and-sharpen of a photo send path, a watermark, a sepia tone and a
 * median. Run with {@code -Dsmalt.threads=1} in the fork arguments for the single-threaded numbers.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx3g"})
public class ImageEffectsBenchmark {
    private RgbaPicture photo;
    private RgbaPicture mark;

    /**
     * Builds the photo the Python side builds too: smooth gradients plus uniform noise.
     */
    static RgbaPicture photo(int width, int height) {
        var picture = RgbaPicture.allocate(width, height, false);
        var pixels = picture.pixels();
        var random = new SplittableRandom(12);
        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var p = 4 * (y * width + x);
                pixels[p] = (byte) Math.clamp(x * 255L / width + random.nextInt(-12, 13), 0, 255);
                pixels[p + 1] = (byte) Math.clamp(y * 255L / height + random.nextInt(-12, 13), 0, 255);
                pixels[p + 2] = (byte) Math.clamp((x + y) * 255L / (width + height) + random.nextInt(-12, 13), 0, 255);
                pixels[p + 3] = (byte) 255;
            }
        }
        return picture;
    }

    @Setup(Level.Trial)
    public void setup() {
        if (!Simd.ENABLED && !"false".equals(System.getProperty("smalt.simd"))) {
            throw new IllegalStateException("the Vector API is not enabled in the benchmark JVM");
        }
        photo = photo(4032, 3024);
        mark = Pictures.patternRgba(800, 300, true);
    }

    @Benchmark
    public Picture blurExact2() {
        return ImageFilters.gaussianBlur(photo, 2, BlurMethod.EXACT);
    }

    @Benchmark
    public Picture blurBox2() {
        return ImageFilters.gaussianBlur(photo, 2, BlurMethod.BOX);
    }

    @Benchmark
    public Picture blurBox10() {
        return ImageFilters.gaussianBlur(photo, 10, BlurMethod.BOX);
    }

    @Benchmark
    public Picture rotateBicubic12() {
        return ImageOps.rotate(photo, 12, Interpolation.BICUBIC, RotationCanvas.EXPAND, 0xFFFFFFFF);
    }

    @Benchmark
    public Picture rotateBilinear12() {
        return ImageOps.rotate(photo, 12, Interpolation.BILINEAR, RotationCanvas.EXPAND, 0xFFFFFFFF);
    }

    @Benchmark
    public Picture resizeSharpen() {
        return ImageFilters.unsharpMask(ImageOps.resize(photo, 1600, 1200, ResizeFilter.LANCZOS3), 1, 0.8, 2, BlurMethod.BOX);
    }

    @Benchmark
    public Picture watermark() {
        return ImageCompositing.watermark(photo, mark, WatermarkOptions.defaults());
    }

    @Benchmark
    public Picture sepia() {
        return ImageAdjustments.sepia(photo);
    }

    @Benchmark
    public Picture median3() {
        return ImageFilters.median(photo, 1);
    }
}
