package com.github.auties00.smalt.image.gif;

import com.github.auties00.smalt.image.RgbaPicture;
import com.github.auties00.smalt.image.ops.Dithering;
import com.github.auties00.smalt.image.ops.QuantizeMethod;
import com.github.auties00.smalt.image.png.ApngEncoder;
import com.github.auties00.smalt.testkit.Fixtures;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Measures single-threaded GIF and APNG encoding of natural video: 30 frames of the Xiph
 * {@code foreman} CIF sequence (352x288) when {@code ~/.smalt/cache/clips/foreman.yuv} exists,
 * else of ffmpeg's {@code testsrc2}. The frames are also written to
 * {@code target/media-fixtures} as raw RGBA, so the ffmpeg comparison encodes the same pixels:
 * {@snippet :
 * ffmpeg -threads 1 -benchmark -f rawvideo -pix_fmt rgba -s 352x288 -r 15 -i <frames> \
 *     -vf "split[a][b];[a]palettegen[p];[b][p]paletteuse" -f gif -y out.gif
 * }
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx1g"})
public class GifEncoderBenchmark {
    static final int WIDTH = 352;
    static final int HEIGHT = 288;
    static final int FRAMES = 30;

    @Param({"MEDIAN_CUT"})
    public String quantizer;

    @Param({"SIERRA"})
    public String dithering;

    private List<RgbaPicture> frames;
    private GifEncoderOptions options;

    /** Returns the raw RGBA frames, generating them with ffmpeg on first use. */
    public static Path rawFrames() {
        var clip = Path.of(System.getProperty("smalt.test.clips", System.getProperty("user.home") + "/.smalt/cache/clips"),
                "foreman.yuv");
        var input = Files.isRegularFile(clip)
                ? List.of("-f", "rawvideo", "-pix_fmt", "yuv420p", "-s", WIDTH + "x" + HEIGHT, "-i", clip.toString())
                : List.of("-f", "lavfi", "-i", "testsrc2=size=" + WIDTH + "x" + HEIGHT + ":rate=30");
        return Fixtures.ffmpeg("raw", input, List.of("-frames:v", Integer.toString(FRAMES), "-f", "rawvideo",
                "-pix_fmt", "rgba"));
    }

    public static List<RgbaPicture> frames() throws java.io.IOException {
        var raw = Files.readAllBytes(rawFrames());
        var list = new ArrayList<RgbaPicture>();
        for (var i = 0; i < FRAMES; i++) {
            var picture = RgbaPicture.allocate(WIDTH, HEIGHT, false);
            System.arraycopy(raw, i * WIDTH * HEIGHT * 4, picture.pixels(), 0, WIDTH * HEIGHT * 4);
            list.add(picture);
        }
        return list;
    }

    @Setup(Level.Trial)
    public void setup() throws java.io.IOException {
        frames = frames();
        options = GifEncoderOptions.builder().method(QuantizeMethod.valueOf(quantizer))
                .dithering(Dithering.valueOf(dithering)).build();
    }

    @Benchmark
    public byte[] encodeStill() {
        return GifEncoder.encode(frames.getFirst(), options);
    }

    @Benchmark
    public byte[] encodeAnimation() {
        var encoder = new GifAnimationEncoder(WIDTH, HEIGHT, options);
        for (var frame : frames) {
            encoder.addFrame(frame, 7);
        }
        return encoder.finish();
    }

    @Benchmark
    public byte[] encodeApng() {
        var encoder = new ApngEncoder(WIDTH, HEIGHT);
        for (var frame : frames) {
            encoder.addFrame(frame, 1, 15);
        }
        return encoder.finish();
    }
}
