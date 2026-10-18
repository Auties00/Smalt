package com.github.auties00.smalt.video.vp8;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.testkit.Oracle;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.video.vp8.internal.Vp8;
import com.github.auties00.smalt.video.vp8.internal.Vp8KeyFrameSettings;
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
import org.openjdk.jmh.infra.Blackhole;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Benchmarks VP8 decoding of a 1280x720 stream (time per frame), WebP key-frame decoding of a
 * 2048x2048 image, and key-frame encoding of 512x512 and 2048x2048 pictures at q90 m4.
 *
 * <p>Missing inputs are generated once into {@code target/media-fixtures}: the 5-second 720p
 * stream with ffmpeg's libvpx (3 Mb/s for testsrc2, 4 Mb/s for mandelbrot), the raw 4:2:0
 * pictures with ffmpeg's lavfi sources, and the 2048x2048 WebP with {@code cwebp -q 75}. The same
 * files feed the ffmpeg, dwebp and cwebp comparison runs.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector"})
public class Vp8Benchmark {
    @Param({"testsrc2", "mandelbrot"})
    public String content;

    private List<byte[]> packets;
    private byte[] webpFrame;
    private YuvPicture picture512;
    private YuvPicture picture2048;

    static Path fixture(String name) throws IOException {
        var path = Path.of("target", "media-fixtures", "bench-" + name);
        if (Files.isRegularFile(path)) {
            return path;
        }
        Files.createDirectories(path.getParent());
        var content = name.substring(0, name.indexOf('-'));
        var temporary = path.resolveSibling(path.getFileName() + ".tmp");
        if (name.endsWith("-720p.ivf")) {
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(10), List.of("-v", "error", "-y", "-f", "lavfi", "-i",
                    content + "=size=1280x720:rate=30", "-t", "5", "-c:v", "libvpx", "-b:v",
                    content.equals("mandelbrot") ? "4M" : "3M", "-f", "ivf", temporary.toString()));
        } else if (name.endsWith(".yuv")) {
            var size = name.substring(name.indexOf('-') + 1, name.indexOf('.'));
            Oracle.run(Oracle.Tool.FFMPEG, Duration.ofMinutes(10), List.of("-v", "error", "-y", "-f", "lavfi", "-i",
                    content + "=size=" + size + "x" + size + ":rate=1", "-frames:v", "1", "-pix_fmt", "yuv420p",
                    "-f", "rawvideo", temporary.toString()));
        } else {
            Oracle.run(Oracle.Tool.CWEBP, Duration.ofMinutes(10), List.of("-quiet", "-s", "2048", "2048", "-q", "75",
                    fixture(content + "-2048.yuv").toString(), "-o", temporary.toString()));
        }
        Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        return path;
    }

    @Setup(Level.Trial)
    public void prepare() throws IOException {
        var ivf = Ivf.parse(Files.readAllBytes(fixture(content + "-720p.ivf")));
        packets = ivf.frames().stream().map(Ivf.Frame::data).toList();
        webpFrame = Ivf.vp8Chunk(Files.readAllBytes(fixture(content + "-2048.webp")));
        picture512 = Vp8EncoderExactTest.picture(Files.readAllBytes(fixture(content + "-512.yuv")), 512, 512);
        picture2048 = Vp8EncoderExactTest.picture(Files.readAllBytes(fixture(content + "-2048.yuv")), 2048, 2048);
    }

    /** Decodes the whole 720p stream; divide by the frame count for the time per frame. */
    @Benchmark
    public void decode720p(Blackhole blackhole) {
        try (var decoder = new Vp8Decoder(Vp8ConformanceTest.track(1280, 720))) {
            var pts = 0L;
            for (var packet : packets) {
                decoder.decode(new Packet(1, packet, pts, pts, 1, false), blackhole::consume);
                pts++;
            }
        }
    }

    @Benchmark
    public YuvPicture decodeWebp2048() {
        return Vp8.decodeKeyFrame(webpFrame, 0, webpFrame.length, MediaLimits.DEFAULT);
    }

    @Benchmark
    public byte[] encode512() {
        return Vp8.encodeKeyFrame(picture512, Vp8KeyFrameSettings.quality(90));
    }

    @Benchmark
    public byte[] encode2048() {
        return Vp8.encodeKeyFrame(picture2048, Vp8KeyFrameSettings.quality(90));
    }
}
