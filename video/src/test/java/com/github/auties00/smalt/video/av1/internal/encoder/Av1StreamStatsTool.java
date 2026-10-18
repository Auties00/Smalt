package com.github.auties00.smalt.video.av1.internal.encoder;

import com.github.auties00.smalt.video.av1.internal.BitReader;
import com.github.auties00.smalt.video.av1.internal.FrameHeader;
import com.github.auties00.smalt.video.av1.internal.Obu;
import com.github.auties00.smalt.video.av1.internal.SequenceHeader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Prints the frame type, size, quantizer, loop filter, CDEF, reference and tile settings of every
 * frame of an AV1 IVF file, to compare the decisions of two encoders. Not a test: run its
 * {@code main} by hand with the file as the argument.
 */
final class Av1StreamStatsTool {
    private Av1StreamStatsTool() {
    }

    public static void main(String[] args) throws Exception {
        var units = Av1RtQualityTool.parseIvf(Files.readAllBytes(Path.of(args[0])));
        SequenceHeader seq = null;
        var refs = new FrameHeader[8];
        for (var i = 0; i < units.size(); i++) {
            var data = units.get(i);
            var pos = 0;
            while (pos < data.length) {
                var header = data[pos] & 0xFF;
                var type = (header >> 3) & 15;
                var ext = (header & 4) != 0;
                pos += ext ? 2 : 1;
                long size = 0;
                for (var shift = 0; ; shift += 7) {
                    var b = data[pos++] & 0xFF;
                    size |= (long) (b & 0x7F) << shift;
                    if ((b & 0x80) == 0) {
                        break;
                    }
                }
                var gb = new BitReader();
                gb.init(data, pos, (int) size);
                if (type == Obu.OBU_SEQ_HDR) {
                    seq = new SequenceHeader();
                    seq.parse(gb, false);
                } else if (type == Obu.OBU_FRAME || type == Obu.OBU_FRAME_HDR) {
                    var h = new FrameHeader();
                    h.parse(seq, gb, refs, false);
                    System.out.printf(Locale.ROOT,
                            "%3d %-5s %7d bytes q %3d lf %2d/%2d/%2d/%2d sharp %d cdef %d:%d/%d damp %d tx %d er %d "
                                    + "prim %d refresh %02x refs %s tiles %dx%d hp %d filt %d seg %d dq %d%n",
                            i, h.frameType == FrameHeader.KEY ? "key" : "inter", data.length, h.quantYac,
                            h.loopfilterLevelY[0], h.loopfilterLevelY[1], h.loopfilterLevelU, h.loopfilterLevelV,
                            h.sharpness, h.cdefNBits, h.cdefYStrength[0], h.cdefUvStrength[0], h.cdefDamping,
                            h.txfmMode, h.errorResilientMode ? 1 : 0, h.primaryRefFrame, h.refreshFrameFlags,
                            java.util.Arrays.toString(h.refidx), h.tilingCols, h.tilingRows, h.hp ? 1 : 0,
                            h.subpelFilterMode, h.segmentationEnabled ? 1 : 0, h.deltaQPresent ? 1 : 0);
                    for (var r = 0; r < 8; r++) {
                        if ((h.refreshFrameFlags & (1 << r)) != 0) {
                            refs[r] = h;
                        }
                    }
                }
                pos += (int) size;
            }
        }
    }
}
