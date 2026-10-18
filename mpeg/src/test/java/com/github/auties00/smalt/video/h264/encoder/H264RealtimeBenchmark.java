package com.github.auties00.smalt.video.h264.encoder;

import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
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
import org.openjdk.jmh.annotations.TearDown;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Per-frame latency of the real-time configuration ({@link H264Setup#forRealtime}:
 * Constrained Baseline, constant bitrate, one thread) on the Xiph "stockholm" clip at 480p
 * (848x480, 800 kbps) and 720p (1.5 Mbps). One operation encodes one frame; the encoder lives
 * for the whole trial and walks the first 100 frames of the clip forwards and backwards, so the
 * motion stays continuous. Sample mode reports the latency percentiles.
 *
 * <p>The 720p clip is read from {@code -Dh264.bench.dir}, the 480p one from
 * {@code -Dh264.bench.dir480} (both default to {@code target/seq}).
 */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Fork(value = 1, jvmArgsAppend = {"--add-modules", "jdk.incubator.vector", "-Xmx1g"})
@State(Scope.Benchmark)
public class H264RealtimeBenchmark {
    private static final int FRAMES = 100;

    @Param({"480p", "720p"})
    public String size;

    private YuvPicture[] pictures;
    private H264Encoder encoder;
    private long index;
    private long bytes;

    @Setup(Level.Trial)
    public void open() throws IOException {
        var width = size.equals("720p") ? 1280 : 848;
        var height = size.equals("720p") ? 720 : 480;
        var bitrate = size.equals("720p") ? 1_500_000 : 800_000;
        var dir = size.equals("720p") ? System.getProperty("h264.bench.dir", "target/seq")
                : System.getProperty("h264.bench.dir480", "target/seq");
        var file = Path.of(dir, "stockholm" + height + ".yuv");
        var frameSize = width * height * 3 / 2;
        if (!Files.isRegularFile(file) || Files.size(file) < (long) FRAMES * frameSize) {
            throw new IllegalStateException("missing " + file);
        }
        pictures = new YuvPicture[FRAMES];
        var frame = new byte[frameSize];
        try (var input = new RandomAccessFile(file.toFile(), "r")) {
            for (var i = 0; i < FRAMES; i++) {
                input.readFully(frame);
                var picture = YuvPicture.allocate(width, height, ChromaFormat.YUV420, false, ColorInfo.BT709_LIMITED);
                System.arraycopy(frame, 0, picture.y(), 0, width * height);
                System.arraycopy(frame, width * height, picture.u(), 0, width * height / 4);
                System.arraycopy(frame, width * height * 5 / 4, picture.v(), 0, width * height / 4);
                pictures[i] = picture;
            }
        }
        encoder = H264Setup.realtime(width, height, new Rational(30, 1), bitrate).encoder();
    }

    @TearDown(Level.Trial)
    public void close() {
        encoder.close();
    }

    /**
     * Encodes the next frame.
     */
    @Benchmark
    public long encodeFrame() {
        var cycle = index % (2 * FRAMES - 2);
        var picture = pictures[(int) (cycle < FRAMES ? cycle : 2 * FRAMES - 2 - cycle)];
        var frame = new VideoFrame(picture, index * 3000, 3000, false);
        index++;
        encoder.encode(frame, packet -> bytes += packet.size());
        return bytes;
    }
}
