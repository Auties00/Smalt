package com.github.auties00.smalt.testkit.image;

import com.github.auties00.smalt.testkit.Fixtures;

import java.nio.file.Path;
import java.util.List;

/**
 * Generates (and caches) the inputs of the raster benchmarks with ffmpeg, so the JMH runs and the
 * ffmpeg CLI comparison decode the very same files.
 *
 * <p>The "smooth" images render the Mandelbrot set (large flat areas, like screenshots); the
 * "noisy" ones add uniform grain so they compress like photographs. The RGBA images take their
 * alpha from a gradient. Every PNG is written by ffmpeg with {@code -pred mixed}, which picks the
 * filter per row, so all five filter types occur.
 */
public final class RasterBenchmarkInputs {
    private RasterBenchmarkInputs() {
        throw new AssertionError();
    }

    public static Path png(String kind) {
        var size = "s=4000x3000";
        var noise = kind.endsWith("noise") ? ",noise=alls=6:allf=u" : "";
        if (kind.startsWith("rgba")) {
            return Fixtures.ffmpeg("png",
                    List.of("-f", "lavfi", "-i", "mandelbrot=" + size, "-f", "lavfi", "-i", "color=size=" + size + ",geq=r='255*X/W':g='255*Y/H':b='128+127*sin(X/37)*cos(Y/23)'"),
                    List.of("-filter_complex", "[0]format=rgb24" + noise + "[c];[1]format=gray[a];[c][a]alphamerge,format=rgba",
                            "-frames:v", "1", "-c:v", "png", "-pred", "mixed"));
        }
        return Fixtures.ffmpeg("png", List.of("-f", "lavfi", "-i", "mandelbrot=" + size),
                List.of("-vf", "format=rgb24" + noise, "-frames:v", "1", "-c:v", "png", "-pred", "mixed"));
    }

    /** A 300-frame 480x270 animated GIF with a per-frame palette and transparency-optimized frames. */
    public static Path gif() {
        return Fixtures.ffmpeg("gif",
                List.of("-f", "lavfi", "-i", "testsrc2=size=480x270:rate=30:duration=10"),
                List.of("-filter_complex", "[0]split[a][b];[a]palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer",
                        "-loop", "0"));
    }
}
