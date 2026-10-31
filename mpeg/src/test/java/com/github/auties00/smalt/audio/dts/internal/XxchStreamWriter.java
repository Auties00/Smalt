package com.github.auties00.smalt.audio.dts.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * Writes synthetic XXCH extension frames (ETSI TS 102 114 clause 6.5) to append to core frames written by
 * {@link CoreStreamWriter}.
 */
public final class XxchStreamWriter {
    private XxchStreamWriter() {
    }

    /**
     * One XXCH channel set.
     */
    public static final class Set {
        public final CoreStreamWriter.Channels channels;
        public int layoutMask;
        public boolean downmixCoefficients;
        public boolean downmixEmbedded;
        public int scaleCode;
        public final int[] mapMasks = new int[8];
        public final int[][] coefficientCodes = new int[8][32];
        public final List<CoreStreamWriter.Subframe> subframes = new ArrayList<>();

        public Set(CoreStreamWriter.Channels channels) {
            this.channels = channels;
        }
    }

    /**
     * Writes an XXCH frame.
     *
     * @param sets the channel sets
     * @param maskBits the loudspeaker mask width ({@code nuBits4SpkrMaskXXCh})
     * @param coreMask the core loudspeaker activity mask
     * @param syncEverySubsubframe the core's {@code ASPF}
     * @return the frame bytes, a multiple of four bytes
     */
    public static byte[] write(List<Set> sets, int maskBits, int coreMask, boolean syncEverySubsubframe) {
        var bodies = new ArrayList<byte[]>();
        for (var set : sets) {
            bodies.add(writeSet(set, maskBits, syncEverySubsubframe));
        }
        var header = new DtsBitWriter();
        header.write(CoreDecoder.XXCH_SYNC, 32);
        var fieldsBits = 6 + 1 + 5 + 2 + 14L * sets.size() + maskBits;
        var headerBytes = (int) ((32 + fieldsBits + 7) / 8 + 2);
        header.write(headerBytes - 1, 6);
        header.write(0, 1);
        header.write(maskBits - 1, 5);
        header.write(sets.size() - 1, 2);
        for (var body : bodies) {
            header.write(body.length - 1, 14);
        }
        header.write(coreMask, maskBits);
        header.alignTo(8);
        var bytes = header.toByteArray();
        var crc = DtsCrc.compute(bytes, 4, bytes.length - 4);
        var out = new DtsBitWriter();
        out.append(bytes);
        out.write(crc, 16);
        for (var body : bodies) {
            out.append(body);
        }
        out.alignTo(32);
        return out.toByteArray();
    }

    private static byte[] writeSet(Set set, int maskBits, boolean syncEverySubsubframe) {
        var header = new DtsBitWriter();
        header.write(0, 7);
        header.write(set.channels.count - 1, 3);
        header.write(set.layoutMask >>> 6, maskBits - 6);
        header.write(set.downmixCoefficients ? 1 : 0, 1);
        if (set.downmixCoefficients) {
            header.write(set.downmixEmbedded ? 1 : 0, 1);
            header.write(set.scaleCode, 6);
            for (var ch = 0; ch < set.channels.count; ch++) {
                header.write(set.mapMasks[ch], maskBits);
            }
            for (var ch = 0; ch < set.channels.count; ch++) {
                for (var bit = 0; bit < maskBits; bit++) {
                    if ((set.mapMasks[ch] >> bit & 1) != 0) {
                        header.write(set.coefficientCodes[ch][bit], 7);
                    }
                }
            }
        }
        CoreStreamWriter.writeChannelHeader(header, set.channels);
        header.alignTo(8);
        var headerBytes = header.toByteArray();
        headerBytes[0] = (byte) ((headerBytes.length - 1) << 1 | headerBytes[0] & 1);
        var out = new DtsBitWriter();
        out.append(headerBytes);
        for (var subframe : set.subframes) {
            CoreStreamWriter.writeSideInformation(out, set.channels, subframe);
            CoreStreamWriter.writeVectors(out, set.channels, subframe);
            for (var s = 0; s < subframe.subsubframes; s++) {
                CoreStreamWriter.writeSubsubframe(out, set.channels, subframe, s);
                if (s == subframe.subsubframes - 1 || syncEverySubsubframe) {
                    out.write(0xFFFF, 16);
                }
            }
        }
        return out.toByteArray();
    }
}
