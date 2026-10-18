package com.github.auties00.smalt.image.jpeg;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.jpeg.internal.JpegReader;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.file.Files;
import java.util.function.Supplier;

/**
 * Measures the main JPEG operations one call at a time and reports the minimum and median wall
 * time, the minimum being the least disturbed by other processes on a busy machine, and the best
 * mean over 10 consecutive calls, comparable to the per-frame time of a 10-frame ffmpeg run;
 * arguments: iterations (default 30) and an optional name filter.
 */
public final class JpegCpuTimer {
    private JpegCpuTimer() {
    }

    public static void main(String[] args) throws Exception {
        var iterations = args.length > 0 ? Integer.parseInt(args[0]) : 30;
        var filter = args.length > 1 ? args[1] : "";
        var jpeg = Files.readAllBytes(JpegBenchmarkInputs.ensure("photo_q92_420.jpg"));
        var rgb = Files.readAllBytes(JpegBenchmarkInputs.ensure("photo.rgb"));
        var rgba = JpegTestSupport.rgba(JpegBenchmarkInputs.WIDTH, JpegBenchmarkInputs.HEIGHT, rgb);
        var yuv = (YuvPicture) JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults()).picture();
        time("decode full", filter, iterations, () -> JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.defaults()));
        time("decode 1/8", filter, iterations, () -> JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.ofMinimumSize(504, 378)));
        time("decode 1/2", filter, iterations, () -> JpegDecoder.decode(MediaSource.of(jpeg), ImageDecoderOptions.ofMinimumSize(2016, 1512)));
        var progressive = Files.readAllBytes(JpegBenchmarkInputs.ensure("photo_q92_420_progressive.jpg"));
        time("decode progressive full", filter, iterations, () -> JpegDecoder.decode(MediaSource.of(progressive), ImageDecoderOptions.defaults()));
        time("decode progressive 1/8", filter, iterations, () -> JpegDecoder.decode(MediaSource.of(progressive), ImageDecoderOptions.ofMinimumSize(504, 378)));
        time("decode coefficients", filter, iterations, () -> new JpegReader(jpeg, 0, jpeg.length, MediaLimits.DEFAULT).decodeCoefficients());
        time("encode rgba q92 420 optimized", filter, iterations, () -> JpegEncoder.encode(rgba,
                JpegEncoderOptions.ofQuality(92)));
        time("encode rgba q92 420 standard", filter, iterations, () -> JpegEncoder.encode(rgba,
                JpegEncoderOptions.builder().quality(92).chromaFormat(ChromaFormat.YUV420).optimizeHuffman(false)
                .build()));
        time("encode yuv420 q92 optimized", filter, iterations, () -> JpegEncoder.encode(yuv,
                JpegEncoderOptions.ofQuality(92)));
    }

    private static void time(String name, String filter, int iterations, Supplier<Object> operation) {
        if (!name.contains(filter)) {
            return;
        }
        for (var i = 0; i < Math.max(5, iterations / 2); i++) {
            operation.get();
        }
        var samples = new double[iterations];
        for (var i = 0; i < iterations; i++) {
            var start = System.nanoTime();
            operation.get();
            samples[i] = (System.nanoTime() - start) / 1e6;
        }
        var window = Math.min(10, iterations);
        var bestWindow = Double.MAX_VALUE;
        for (var i = 0; i + window <= iterations; i++) {
            var sum = 0.0;
            for (var j = i; j < i + window; j++) {
                sum += samples[j];
            }
            bestWindow = Math.min(bestWindow, sum / window);
        }
        java.util.Arrays.sort(samples);
        System.out.printf("%-34s min %7.1f ms  median %7.1f ms  best-10 %7.1f ms%n", name, samples[0], samples[iterations / 2], bestWindow);
    }
}
