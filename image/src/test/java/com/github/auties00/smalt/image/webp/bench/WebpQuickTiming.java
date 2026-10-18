package com.github.auties00.smalt.image.webp.bench;

import com.github.auties00.smalt.image.ImageDecoderOptions;
import com.github.auties00.smalt.image.webp.WebpDecoder;
import com.github.auties00.smalt.io.MediaSource;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Times the decoder on a file without JMH, for quick profiling during development:
 * {@code WebpQuickTiming <file> [iterations]}.
 */
public final class WebpQuickTiming {
    private WebpQuickTiming() {
    }

    public static void main(String[] args) throws Exception {
        var data = Files.readAllBytes(Path.of(args[0]));
        var iterations = args.length > 1 ? Integer.parseInt(args[1]) : 30;
        long best = Long.MAX_VALUE;
        for (var i = 0; i < iterations; i++) {
            var start = System.nanoTime();
            WebpDecoder.decode(MediaSource.of(data), ImageDecoderOptions.defaults());
            var elapsed = System.nanoTime() - start;
            best = Math.min(best, elapsed);
            if (i % 5 == 4) {
                System.out.printf("iter %d: %.2f ms (best %.2f ms)%n", i, elapsed / 1e6, best / 1e6);
            }
        }
    }
}
