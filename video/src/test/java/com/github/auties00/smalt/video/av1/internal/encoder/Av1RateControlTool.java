package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.image.ops.ImageOps;
import com.github.auties00.smalt.image.ops.ResizeFilter;
import com.github.auties00.smalt.track.Packet;
import com.github.auties00.smalt.track.Rational;
import com.github.auties00.smalt.video.VideoFrame;
import com.github.auties00.smalt.video.av1.Av1Decoder;
import com.github.auties00.smalt.video.av1.Av1Encoder;
import com.github.auties00.smalt.video.av1.Av1EncoderOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Measures the call-shaped behavior of the real-time AV1 rate control through the public
 * {@link Av1Encoder} with the {@link Av1EncoderOptions#forRealtime()} preset: the size of the first
 * key frame against the frame budget, the drops and the luma PSNR of the first two seconds, the same
 * after a resize to three quarters and back, and after a bandwidth cut to a third and back. A dropped
 * frame repeats the last decoded picture. Not a test: run its {@code main} by hand.
 *
 * <p>Arguments: {@code clip width height kbps...}. The {@code av1rc.trace} property prints every
 * frame, {@code av1rc.threads} sets the encoder threads and {@code av1rc.cap} the key frame limit
 * in percent (0 for none) in place of the preset's.
 */
final class Av1RateControlTool {
    private static final int FPS = 30;

    private static final int SEGMENT = 60;

    private static final ThreadLocal<YuvPicture> LAST = new ThreadLocal<>();

    private Av1RateControlTool() {
    }

    record Segment(String name, int keyBytes, double keyBudgets, int drops, int maxConsec, double psnr, double kbps,
                   double minPsnr) {
    }

    public static void main(String[] args) throws Exception {
        var clip = Path.of(args[0]);
        var width = Integer.parseInt(args[1]);
        var height = Integer.parseInt(args[2]);
        var source = Av1RtQualityTool.read(clip, width, height, 4 * SEGMENT);
        var name = clip.getFileName().toString().replace(".yuv", "");
        for (var i = 3; i < args.length; i++) {
            var kbps = Integer.parseInt(args[i]);
            var out = new ArrayList<Segment>();
            out.addAll(resizeRun(source, width, height, kbps));
            out.addAll(bandwidthRun(source, width, height, kbps));
            for (var s : out) {
                System.out.printf(Locale.ROOT,
                        "%-12s %5d %-8s key %6d B %5.2f budgets  drops %2d maxrun %2d  psnr %6.3f min %6.3f  %7.1f kbps%n",
                        name, kbps, s.name, s.keyBytes, s.keyBudgets, s.drops, s.maxConsec, s.psnr, s.minPsnr, s.kbps);
            }
        }
    }

    private static Av1EncoderOptions options(int kbps) {
        var options = Av1EncoderOptions.forRealtime().toBuilder().bitrate(kbps * 1000L).frameRate(new Rational(FPS, 1))
                .threads(Integer.getInteger("av1rc.threads", 1)).build();
        var cap = Integer.getInteger("av1rc.cap");
        if (cap != null) {
            var builder = options.toBuilder();
            options = (cap > 0 ? builder.maxKeyframeBitratePercent(cap) : builder.noMaxKeyframeBitrate()).build();
        }
        return options;
    }

    private static List<Segment> resizeRun(List<YuvPicture> source, int width, int height, int kbps) {
        var segments = new ArrayList<Segment>();
        var w3 = (width * 3 / 4) & ~1;
        var h3 = (height * 3 / 4) & ~1;
        try (var enc = new Av1Encoder(width, height, options(kbps));
             var dec = new Av1Decoder(Av1EncTesting.track(width, height))) {
            var frame = 0;
            segments.add(run(enc, dec, "start", source, frame, SEGMENT, width, height, kbps));
            frame += SEGMENT;
            run(enc, dec, "steady", source, frame, SEGMENT, width, height, kbps);
            frame += SEGMENT;
            enc.reconfigure(w3, h3);
            segments.add(run(enc, dec, "down3/4", source, frame, SEGMENT, w3, h3, kbps));
            frame += SEGMENT;
            enc.reconfigure(width, height);
            segments.add(run(enc, dec, "up", source, frame, SEGMENT, width, height, kbps));
        }
        return segments;
    }

    private static List<Segment> bandwidthRun(List<YuvPicture> source, int width, int height, int kbps) {
        var segments = new ArrayList<Segment>();
        try (var enc = new Av1Encoder(width, height, options(kbps));
             var dec = new Av1Decoder(Av1EncTesting.track(width, height))) {
            var frame = 0;
            run(enc, dec, "warm", source, frame, 2 * SEGMENT, width, height, kbps);
            frame += 2 * SEGMENT;
            enc.setTargetBitrate(kbps * 1000L / 3);
            segments.add(run(enc, dec, "cut1/3", source, frame, SEGMENT, width, height, kbps / 3));
            frame += SEGMENT;
            enc.setTargetBitrate(kbps * 1000L);
            segments.add(run(enc, dec, "restore", source, frame, SEGMENT, width, height, kbps));
        }
        return segments;
    }

    private static Segment run(Av1Encoder enc, Av1Decoder dec, String name, List<YuvPicture> source, int first, int count, int width,
                               int height, int kbps) {
        var units = new ArrayList<byte[]>();
        var pictures = new ArrayList<YuvPicture>();
        var coded = new boolean[count];
        var keyBytes = 0;
        var drops = 0;
        var run = 0;
        var maxRun = 0;
        long bytes = 0;
        for (var i = 0; i < count; i++) {
            var picture = source.get((first + i) % source.size());
            if (picture.width() != width || picture.height() != height) {
                picture = (YuvPicture) ImageOps.resize(picture, width, height, ResizeFilter.BILINEAR);
            }
            pictures.add(picture);
            var packet = enc.encodeFrame(new VideoFrame(picture, (long) (first + i) * 3000, 3000, false)).orElse(null);
            if (packet == null) {
                drops++;
                run++;
                maxRun = Math.max(maxRun, run);
                continue;
            }
            run = 0;
            coded[i] = true;
            units.add(packet.data());
            bytes += packet.data().length;
            if (packet.keyframe() && keyBytes == 0) {
                keyBytes = packet.data().length;
            }
        }
        var decoded = new ArrayList<YuvPicture>();
        for (var unit : units) {
            dec.decode(new Packet(1, unit, System.nanoTime(), System.nanoTime(), 1, false),
                    f -> decoded.add(Av1EncTesting.copy((YuvPicture) f.picture())));
        }
        var trace = Boolean.getBoolean("av1rc.trace");
        double mseSum = 0;
        var minPsnr = 100.0;
        var last = LAST.get();
        var next = 0;
        for (var i = 0; i < count; i++) {
            if (coded[i]) {
                last = decoded.get(next++);
            }
            var m = last == null || last.width() != width ? 65025 : Av1RtQualityTool.mse(pictures.get(i), last);
            mseSum += m;
            minPsnr = Math.min(minPsnr, Av1RtQualityTool.psnr(m));
            if (trace) {
                System.out.printf(Locale.ROOT, "  %s %3d %s psnr %.3f%n", name, first + i,
                        coded[i] ? units.get(next - 1).length + " B" : "dropped", Av1RtQualityTool.psnr(m));
            }
        }
        LAST.set(last);
        var budget = kbps * 1000.0 / FPS / 8;
        return new Segment(name, keyBytes, keyBytes / budget, drops, maxRun, Av1RtQualityTool.psnr(mseSum / count),
                bytes * 8.0 / (count / (double) FPS) / 1000, minPsnr);
    }
}
