package com.github.auties00.smalt.image.webp;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.DecodedImage;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.webp.internal.alpha.AlphaDecoder;
import com.github.auties00.smalt.image.webp.internal.alpha.AlphaEncoder;
import com.github.auties00.smalt.image.webp.internal.color.YuvConverter;
import com.github.auties00.smalt.io.MediaSource;
import com.github.auties00.smalt.testkit.Fixtures;
import com.github.auties00.smalt.testkit.Oracle;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks of the WebP hot paths, single-threaded: 2048x2048 lossless decoding (random
 * noise with alpha from the libwebp corpus, and a Mandelbrot render coded by cwebp -lossless -m 4),
 * 512x512 RGBA lossless encoding at cwebp's defaults, the ALPH codec on a 512x512 sticker, and
 * the RGBA to Y'CbCrA conversion. Run with
 * {@code mvn -o -DskipTests test-compile exec:exec -Dexec.executable=java
 * -Dexec.args="--add-modules jdk.incubator.vector -cp %classpath org.openjdk.jmh.Main WebpBenchmark"}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 8, time = 2)
@Fork(value = 1, jvmArgsAppend = {"--add-modules=jdk.incubator.vector", "-Xmx4g"})
public class WebpBenchmark {
    @State(Scope.Benchmark)
    public static class DecodeState {
        @Param({"random", "mandelbrot"})
        public String input;

        byte[] file;

        @Setup(Level.Trial)
        public void setup() {
            file = switch (input) {
                case "random" -> LibwebpTestData.read("lossless_big_random_alpha.webp");
                default -> {
                    var png = Fixtures.ffmpeg("png", List.of("-f", "lavfi", "-i", "mandelbrot=size=2048x2048:end_pts=1"),
                            List.of("-frames:v", "1", "-pix_fmt", "rgba"));
                    var out = WebpTestSupport.scratchPath("bench-mandelbrot.webp");
                    Oracle.run(Oracle.Tool.CWEBP, Duration.ofMinutes(5), List.of("-quiet", "-lossless", "-m", "4",
                            png.toString(), "-o", out.toString()));
                    yield WebpTestSupport.readFile(out);
                }
            };
        }
    }

    @State(Scope.Benchmark)
    public static class StickerState {
        RgbaPicture picture;
        byte[] alphaPlane;
        byte[] alph;

        @Setup(Level.Trial)
        public void setup() {
            var png = Fixtures.ffmpeg("png", List.of("-f", "lavfi", "-i", "testsrc2=size=512x512"),
                    List.of("-frames:v", "1", "-vf", "format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)'"
                            + ":a='clip(400-hypot(X-256,Y-256)*2,0,255)'"));
            var rgba = Oracle.decodeVideo(png, "rgba", 1);
            picture = RgbaPicture.of(512, 512, 2048, rgba, true);
            alphaPlane = new byte[512 * 512];
            for (var i = 0; i < alphaPlane.length; i++) {
                alphaPlane[i] = rgba[4 * i + 3];
            }
            alph = AlphaEncoder.encode(alphaPlane, 512, 512, 100, AlphaEncoder.FILTER_FAST, 4);
        }
    }

    @Benchmark
    public DecodedImage decodeLossless2048(DecodeState state) {
        return WebpDecoder.decode(MediaSource.of(state.file), ImageDecoderOptions.defaults());
    }

    @Benchmark
    public byte[] encodeLossless512(StickerState state) {
        return WebpEncoder.encode(state.picture, WebpEncoderOptions.lossless());
    }

    @Benchmark
    public byte[] encodeAlpha512(StickerState state) {
        return AlphaEncoder.encode(state.alphaPlane, 512, 512, 100, AlphaEncoder.FILTER_FAST, 4);
    }

    @Benchmark
    public byte[] decodeAlpha512(StickerState state) {
        return AlphaDecoder.decode(state.alph, 0, state.alph.length, 512, 512, MediaLimits.DEFAULT);
    }

    @Benchmark
    public YuvPicture rgbaToYuva512(StickerState state) {
        var yuv = YuvConverter.fromRgba(state.picture.pixels(), 2048, 512, 512, true, MediaLimits.DEFAULT);
        YuvConverter.cleanupTransparentArea(yuv);
        return yuv;
    }
}
