package com.github.auties00.smalt.video.vp9.internal;

import com.github.auties00.smalt.MediaLimits;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.IdentityHashMap;
import java.util.Locale;

/**
 * Prints per-frame statistics of a VP9 IVF stream decoded with the module's decoder: quantizer,
 * loop filter level, size, block sizes, transform sizes, modes, references and skips. A
 * diagnostic for comparing the real-time encoder's decisions with libvpx's; not a test.
 *
 * <p>Arguments: {@code stream.ivf [frames]}.
 */
final class Vp9StreamStatsTool {
    private Vp9StreamStatsTool() {
    }

    public static void main(String[] args) throws Exception {
        var packets = Vp9RtQualityTool.parseIvf(Files.readAllBytes(Path.of(args[0])));
        var limit = args.length > 1 ? Integer.parseInt(args[1]) : packets.size();
        var decoder = new Vp9FrameDecoder(MediaLimits.DEFAULT, false);
        for (var i = 0; i < Math.min(limit, packets.size()); i++) {
            var data = packets.get(i);
            decoder.decodePacket(data, 0, data.length);
            var bsizes = new int[13];
            var tx = new int[4];
            var modes = new int[14];
            var refs = new int[4];
            var skips = 0;
            var blocks = 0;
            var seen = new IdentityHashMap<ModeInfo, Boolean>();
            var newMvAbs = 0L;
            for (var mi : decoder.miGrid) {
                if (mi == null || seen.put(mi, Boolean.TRUE) != null) {
                    continue;
                }
                blocks++;
                bsizes[mi.sbType]++;
                tx[mi.txSize]++;
                modes[mi.mode]++;
                refs[Math.max(0, mi.ref0)]++;
                skips += mi.skip;
                if (mi.mode == Vp9Constants.NEWMV) {
                    newMvAbs += Math.abs(mi.mv0 >> 16) + Math.abs((short) mi.mv0);
                }
            }
            var sb = new StringBuilder();
            sb.append(String.format(Locale.ROOT, "frame %3d %s q=%3d lf=%2d bytes=%6d tx=%d filt=%d seg=%s blocks=%5d skip=%5d",
                    i, decoder.keyFrame ? "K" : "I", decoder.baseQindex, decoder.filterLevel, data.length,
                    decoder.txMode, decoder.interpFilter, decoder.segEnabled ? "on" : "off", blocks, skips));
            sb.append(" bs=");
            for (var b = 0; b < 13; b++) {
                sb.append(bsizes[b]).append(b < 12 ? "," : "");
            }
            sb.append(" tx=").append(tx[0]).append(',').append(tx[1]).append(',').append(tx[2]).append(',').append(tx[3]);
            sb.append(" modes=");
            for (var m = 0; m < 14; m++) {
                sb.append(modes[m]).append(m < 13 ? "," : "");
            }
            sb.append(" refs=").append(refs[0]).append(',').append(refs[1]).append(',').append(refs[2]).append(',')
                    .append(refs[3]);
            sb.append(" newmv|mv|=").append(newMvAbs);
            System.out.println(sb);
        }
    }
}
