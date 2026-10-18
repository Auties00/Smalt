package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ColorInfo;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Compares the encoder's key frame with another encoder's at the same quantizer: codes the first
 * picture of a raw clip as a key frame at a quantizer index and prints its size and luma PSNR next
 * to those of the first frame of a reference IVF file. Not a test: run its {@code main} by hand
 * with {@code clip width height qindex reference.ivf}.
 */
final class Av1KeyFrameTool {
    private Av1KeyFrameTool() {
    }

    public static void main(String[] args) throws Exception {
        var clip = Path.of(args[0]);
        var width = Integer.parseInt(args[1]);
        var height = Integer.parseInt(args[2]);
        var qindex = Integer.parseInt(args[3]);
        var source = Av1RtQualityTool.read(clip, width, height, 1);
        var fe = new FrameEncoder(width, height, ColorInfo.BT601_LIMITED, 8, MediaLimits.DEFAULT);
        var p = new FrameParams();
        p.key = true;
        p.qindex = qindex;
        p.speed = Integer.getInteger("av1rt.speed", 9);
        p.txSwitchable = Boolean.getBoolean("av1rt.txselect");
        var acq = Quantizer.acQuant(qindex, 0);
        var lf = Math.clamp((acq * 17563 - 421574 + (1 << 17)) >> 18, 0, 63);
        p.lfLevelY[0] = lf;
        p.lfLevelY[1] = lf;
        p.lfLevelU = lf;
        p.lfLevelV = lf;
        p.cdefDamping = 3 + (qindex >> 6);
        float fq = acq;
        var yf1 = Math.clamp(Math.round(fq * fq * 0.0000033731974f + fq * 0.008070594f + 0.0187634f), 0, 15);
        var yf2 = Math.clamp(Math.round(fq * fq * 0.0000029167343f + fq * 0.0027798624f + 0.0079405f), 0, 3);
        var uvf1 = Math.clamp(Math.round(fq * fq * -0.0000130790995f + fq * 0.012892405f - 0.00748388f), 0, 15);
        var uvf2 = Math.clamp(Math.round(fq * fq * 0.0000032651783f + fq * 0.00035520183f + 0.00228092f), 0, 3);
        p.cdefYStrength = yf1 * 4 + yf2;
        p.cdefUvStrength = uvf1 * 4 + uvf2;
        var unit = fe.encode(source.getFirst(), p);
        var ours = Av1RtQualityTool.measure(List.of(unit), source, width, height);
        System.out.printf(Locale.ROOT, "ours q%d: %d bytes psnr %.3f%n", qindex, unit.length, ours.psnr());
        if (args.length > 4) {
            var ref = Av1RtQualityTool.parseIvf(Files.readAllBytes(Path.of(args[4])));
            var theirs = Av1RtQualityTool.measure(ref.subList(0, 1), source, width, height);
            System.out.printf(Locale.ROOT, "reference: %d bytes psnr %.3f%n", ref.getFirst().length, theirs.psnr());
        }
    }
}
