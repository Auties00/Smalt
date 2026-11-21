package com.github.auties00.smalt.audio.dts.internal;

/**
 * Writes synthetic XBR extensions (ETSI TS 102 114 clause 6.3) of one channel set, without transient scale factors,
 * to carry in an extension substream frame after a core frame written by {@link CoreStreamWriter}.
 */
final class XbrStreamWriter {
    private static final int[] LEVELS = {0, 3, 5, 7, 9, 13, 17, 25};
    private static final int[] BLOCK_BITS = {0, 7, 10, 12, 13, 15, 17, 19};

    private XbrStreamWriter() {
    }

    /**
     * Writes an XBR extension of one channel set.
     *
     * @param bands the number of active subbands of each channel, 1 to 32
     * @param subsubframes the number of normal subsubframes of each subframe of the core frame
     * @param partials the number of samples of the partial subsubframe of each subframe, 0 for none
     * @param syncEverySubsubframe the core's {@code ASPF}
     * @param allocation the bit allocation index, 1 to 26, by subframe, channel and subband
     * @param scale the 6-bit scale factor index of every allocated subband
     * @param codes the residual codes by subframe, channel, subband and sample
     * @return the extension bytes
     */
    static byte[] write(int[] bands, int[] subsubframes, int[] partials, boolean syncEverySubsubframe,
                        int[][][] allocation, int scale, int[][][][] codes) {
        var channels = bands.length;
        var set = new DtsBitWriter();
        for (var subframe = 0; subframe < subsubframes.length; subframe++) {
            for (var ch = 0; ch < channels; ch++) {
                set.write(3, 2);
            }
            for (var ch = 0; ch < channels; ch++) {
                for (var n = 0; n < bands[ch]; n++) {
                    set.write(allocation[subframe][ch][n], 5);
                }
            }
            for (var ch = 0; ch < channels; ch++) {
                set.write(6, 3);
            }
            for (var ch = 0; ch < channels; ch++) {
                for (var n = 0; n < bands[ch]; n++) {
                    if (allocation[subframe][ch][n] > 0) {
                        set.write(scale, 6);
                    }
                }
            }
            var count = subsubframes[subframe];
            var partial = partials[subframe];
            var last = partial > 0 ? count : count - 1;
            for (var s = 0; s <= last; s++) {
                var samples = s < count ? 8 : partial;
                for (var ch = 0; ch < channels; ch++) {
                    for (var n = 0; n < bands[ch]; n++) {
                        var abits = allocation[subframe][ch][n];
                        if (abits > 0) {
                            writeCodes(set, abits, codes[subframe][ch][n], 8 * s, samples);
                        }
                    }
                }
                if (s >= count - 1 || syncEverySubsubframe) {
                    set.write(0xFFFF, 16);
                }
            }
        }
        set.alignTo(8);
        var body = set.toByteArray();
        var header = new DtsBitWriter();
        header.write(XbrDecoder.SYNC & 0xFFFFFFFFL, 32);
        var fieldBits = 6 + 2 + 14 + 1 + 3 + 2 + 5L * channels;
        var headerBytes = (int) ((32 + fieldBits + 7) / 8 + 2);
        header.write(headerBytes - 1, 6);
        header.write(0, 2);
        header.write(body.length - 1, 14);
        header.write(0, 1);
        header.write(channels - 1, 3);
        header.write(0, 2);
        for (var ch = 0; ch < channels; ch++) {
            header.write(bands[ch] - 1, 5);
        }
        header.alignTo(8);
        var bytes = header.toByteArray();
        var out = new DtsBitWriter();
        out.append(bytes);
        out.write(DtsCrc.compute(bytes, 4, bytes.length - 4), 16);
        out.append(body);
        return out.toByteArray();
    }

    private static void writeCodes(DtsBitWriter out, int abits, int[] codes, int base, int samples) {
        if (abits > 7) {
            for (var m = 0; m < samples; m++) {
                out.writeSigned(codes[base + m], abits - 3);
            }
            return;
        }
        var levels = LEVELS[abits];
        var offset = (levels - 1) / 2;
        for (var block = 0; block < (samples + 3) / 4; block++) {
            var code = 0;
            for (var m = 3; m >= 0; m--) {
                code = code * levels + codes[base + 4 * block + m] + offset;
            }
            out.write(code, BLOCK_BITS[abits]);
        }
    }
}
