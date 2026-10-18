package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.Av1Encoder;
import com.github.auties00.smalt.video.av1.Av1EncoderOptions;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Encodes a raw 4:2:0 clip with the public {@link Av1Encoder} several times over and prints the
 * wall time per frame of each pass and a digest of the stream, for profiling and for checking that
 * thread counts produce the same stream. Not a test: run its {@code main} by hand.
 *
 * <p>Arguments: {@code clip width height frames kbps threads tileColumnsLog2 passes}.
 */
final class Av1EncodeLoopTool {
    private Av1EncodeLoopTool() {
    }

    public static void main(String[] args) throws Exception {
        var clip = Path.of(args[0]);
        var width = Integer.parseInt(args[1]);
        var height = Integer.parseInt(args[2]);
        var frames = Integer.parseInt(args[3]);
        var kbps = Integer.parseInt(args[4]);
        var threads = Integer.parseInt(args[5]);
        var tiles = Integer.parseInt(args[6]);
        var passes = Integer.parseInt(args[7]);
        var source = Av1RtQualityTool.read(clip, width, height, frames);
        var options = Av1EncoderOptions.forRealtime().toBuilder().bitrate(kbps * 1000L).frameRate(new Rational(30, 1))
                .dropFrames(false).threads(threads).tileColumnsLog2(tiles).build();
        for (var pass = 0; pass < passes; pass++) {
            var digest = MessageDigest.getInstance("SHA-256");
            long bytes = 0;
            var t0 = System.nanoTime();
            try (var enc = new Av1Encoder(width, height, options)) {
                for (var i = 0; i < source.size(); i++) {
                    var packet = enc.encodeFrame(new VideoFrame(source.get(i), i * 3000L, 3000, false)).orElse(null);
                    if (packet != null) {
                        digest.update(packet.data());
                        bytes += packet.data().length;
                    }
                }
            }
            var ms = (System.nanoTime() - t0) / 1e6 / source.size();
            System.out.printf(Locale.ROOT, "pass %d: %.2f ms/frame, %d bytes, sha %s%n", pass, ms, bytes,
                    HexFormat.of().formatHex(digest.digest()).substring(0, 16));
        }
    }
}
