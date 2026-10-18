package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.vp9.Vp9Encoder;
import com.github.auties00.smalt.video.vp9.Vp9Setup;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Encodes the first frames of a clip forwards and backwards for a long time through the public
 * encoder, printing the time per frame every 300 frames:
 * the benchmark's workload in a plain JVM, for profiling. Not a test, and not a measurement: the
 * JMH benchmark and the bench lock are the measurement.
 *
 * <p>Arguments: {@code clip width height clipFrames totalFrames threads kbps [speed]}.
 */
final class Vp9RtSoakTool {
    private Vp9RtSoakTool() {
    }

    public static void main(String[] args) throws Exception {
        var width = Integer.parseInt(args[1]);
        var height = Integer.parseInt(args[2]);
        var frames = Vp9TestVideo.read(Path.of(args[0]), width, height, 0, Integer.parseInt(args[3]));
        var total = Integer.parseInt(args[4]);
        var threads = Integer.parseInt(args[5]);
        var bitrate = Integer.parseInt(args[6]) * 1000;
        var speed = args.length > 7 ? Integer.parseInt(args[7]) : 6;
        try (var encoder = Vp9Setup.of(width, height, new Rational(30, 1),
                new Rational(1, 30), bitrate, 2, 52, 0, speed, true, true, 1, false, 1000, 500, 600, 6, threads).encoder()) {
            var period = 2 * frames.size() - 2;
            var t0 = System.nanoTime();
            for (var i = 0; i < total; i++) {
                var phase = i % period;
                var picture = frames.get(phase < frames.size() ? phase : period - phase);
                encoder.encodeFrame(new VideoFrame(picture, i, 1, false));
                if ((i + 1) % 300 == 0) {
                    var t1 = System.nanoTime();
                    System.out.printf(Locale.ROOT, "frames %5d: %.2f ms/frame%n", i + 1, (t1 - t0) / 1e6 / 300);
                    t0 = t1;
                }
            }
        }
    }
}
