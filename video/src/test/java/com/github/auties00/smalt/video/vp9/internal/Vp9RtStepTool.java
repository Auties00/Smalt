package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.MediaLimits;

import java.nio.file.Path;
import java.util.Locale;

/**
 * Prints the per-frame size, quantizer and buffer level of the real-time encoder over a clip
 * whose bitrate steps every segment, and the bitrate of each one-second window. A diagnostic for
 * the rate control; not a test.
 *
 * <p>Arguments: {@code clip width height segmentFrames maxQuantizer bitrate...}.
 */
final class Vp9RtStepTool {
    private Vp9RtStepTool() {
    }

    public static void main(String[] args) throws Exception {
        var clip = Path.of(args[0]);
        var width = Integer.parseInt(args[1]);
        var height = Integer.parseInt(args[2]);
        var segment = Integer.parseInt(args[3]);
        var maxQ = Integer.parseInt(args[4]);
        var steps = new int[args.length - 5];
        for (var i = 0; i < steps.length; i++) {
            steps[i] = Integer.parseInt(args[5 + i]);
        }
        var count = segment * steps.length;
        var frames = Vp9TestVideo.read(clip, width, height, 0, count);
        var enc = new Vp9RtEncoder(width, height, 1, 6, 1, MediaLimits.DEFAULT);
        enc.configure(Vp9TestVideo.rtOptions(steps[0], 6).quantizers(2, maxQ).errorResilient(true)
                .adaptiveQuantization(true).build());
        var tick = Vp9RtEncoder.TICKS_PER_SECOND / 30;
        var sizes = new long[count];
        for (var i = 0; i < count; i++) {
            if (i > 0 && i % segment == 0) {
                enc.configure(Vp9TestVideo.rtOptions(steps[i / segment], 6).quantizers(2, maxQ).errorResilient(true)
                        .adaptiveQuantization(true).build());
            }
            var data = enc.encode(frames.get(i % frames.size()), i * tick, (i + 1) * tick, false);
            sizes[i] = data == null ? 0 : data.length * 8L;
            System.out.printf(Locale.ROOT, "%4d %7d bits q=%3d buffer=%8d%n", i, sizes[i], enc.lastQuantizer(),
                    enc.bufferLevel());
        }
        for (var start = 0; start + 30 <= count; start += 15) {
            var bits = 0L;
            for (var i = start; i < start + 30; i++) {
                bits += sizes[i];
            }
            var target = steps[Math.min(steps.length - 1, (start + 29) / segment)];
            System.out.printf(Locale.ROOT, "window %3d-%3d %8d bps target %8d %+6.1f%%%n", start, start + 30, bits,
                    target, 100.0 * (bits - target) / target);
        }
    }
}
