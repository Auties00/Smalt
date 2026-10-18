package com.github.auties00.smalt.image.ops;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.Orientation;
import com.github.auties00.smalt.image.Picture;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
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

import java.util.concurrent.TimeUnit;

/**
 * Measures the pipeline operations on the sizes of the ffmpeg comparison: a 12-megapixel photo
 * (4032x3024) reduced to 1600x1200 and turned, and 1080p color conversion. Run the ffmpeg side
 * with {@code -threads 1 -filter_threads 1} on the same sizes.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx4g"})
public class ImageOpsBenchmark {
    private YuvPicture photo420;
    private RgbaPicture photoRgbaAlpha;
    private RgbaPicture photoRgba;
    private YuvPicture hd420;
    private RgbaPicture hdRgba;

    @Setup(Level.Trial)
    public void setup() {
        if (!Simd.ENABLED && !"false".equals(System.getProperty("smalt.simd"))) {
            throw new IllegalStateException("the Vector API is not enabled in the benchmark JVM");
        }
        var photo = Pictures.patternRgba(4032, 3024, true);
        photoRgbaAlpha = photo;
        photoRgba = RgbaPicture.of(4032, 3024, photo.stride(), photo.pixels().clone(), false);
        photo420 = ImageOps.toYuv(photoRgba, ChromaFormat.YUV420, ColorInfo.JPEG);
        hdRgba = Pictures.patternRgba(1920, 1080, false);
        hd420 = ImageOps.toYuv(hdRgba, ChromaFormat.YUV420, ColorInfo.BT709_LIMITED);
    }

    @Benchmark
    public Picture resizeYuv420Lanczos() {
        return ImageOps.resize(photo420, 1600, 1200, ResizeFilter.LANCZOS3);
    }

    @Benchmark
    public Picture resizeYuv420Area() {
        return ImageOps.resize(photo420, 1600, 1200, ResizeFilter.AREA);
    }

    @Benchmark
    public Picture resizeYuv420Bicubic() {
        return ImageOps.resize(photo420, 1600, 1200, ResizeFilter.BICUBIC);
    }

    @Benchmark
    public Picture resizeRgbaAlphaLanczos() {
        return ImageOps.resize(photoRgbaAlpha, 1600, 1200, ResizeFilter.LANCZOS3);
    }

    @Benchmark
    public Picture resizeRgbaOpaqueLanczos() {
        return ImageOps.resize(photoRgba, 1600, 1200, ResizeFilter.LANCZOS3);
    }

    @Benchmark
    public Picture resizeRgbaOpaqueArea() {
        return ImageOps.resize(photoRgba, 1600, 1200, ResizeFilter.AREA);
    }

    @Benchmark
    public RgbaPicture yuv420ToRgbaTriangle() {
        return ImageOps.toRgba(hd420, ChromaUpsampling.TRIANGLE);
    }

    @Benchmark
    public RgbaPicture yuv420ToRgbaNearest() {
        return ImageOps.toRgba(hd420, ChromaUpsampling.NEAREST);
    }

    @Benchmark
    public YuvPicture rgbaToYuv420() {
        return ImageOps.toYuv(hdRgba, ChromaFormat.YUV420, ColorInfo.BT709_LIMITED);
    }

    @Benchmark
    public Picture rotate90Yuv420() {
        return ImageOps.orient(photo420, Orientation.ROTATE_90);
    }

    @Benchmark
    public Picture rotate90Rgba() {
        return ImageOps.orient(photoRgba, Orientation.ROTATE_90);
    }

    @Benchmark
    public Picture flattenRgba() {
        return ImageOps.flatten(photoRgbaAlpha, 0xF7F7F7);
    }
}
