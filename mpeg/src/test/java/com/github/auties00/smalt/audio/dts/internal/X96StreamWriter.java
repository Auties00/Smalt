package com.github.auties00.smalt.audio.dts.internal;

/**
 * Writes synthetic X96 extensions (ETSI TS 102 114 clause 6.2) to append to core frames written by
 * {@link CoreStreamWriter}: an extension that codes no upper subband, so that a decoder synthesizes the core through its
 * 64-band filter bank at twice the core rate. Subbands 27 to 32 are coded without bits, which decoders fill with noise
 * at the smallest scale factor, about 2^-23 of full scale; the upper subbands above 32 are silent.
 */
final class X96StreamWriter {
    private static final long SYNC = 0x1D95F262L;

    /**
     * The number of subbands the extension codes, 27 to 32: decoders reject an extension whose last active subband
     * is not above 32.
     */
    private static final int BANDS = 6;

    private X96StreamWriter() {
    }

    /**
     * Writes an X96 extension that codes subbands 27 to 32 without bits.
     *
     * @param channels the number of primary channels of the core frame
     * @param subsubframes the number of subsubframes of each subframe of the core frame
     * @param syncEverySubsubframe the core's {@code ASPF}
     * @param minimumSize the smallest extension size in bytes, a multiple of four; zero padding fills the rest
     * @return the extension bytes, a multiple of four bytes
     */
    static byte[] empty(int channels, int[] subsubframes, boolean syncEverySubsubframe, int minimumSize) {
        return write(channels, subsubframes, new int[subsubframes.length], syncEverySubsubframe, minimumSize, 0, false,
                null);
    }

    /**
     * Writes an X96 extension whose subbands 27 to 32 are coded with the three-level quantizer (ABITS96 2), so that no
     * subband is filled with noise.
     *
     * @param channels the number of primary channels of the core frame
     * @param subsubframes the number of normal subsubframes of each subframe of the core frame
     * @param partials the number of samples of the partial subsubframe of each subframe, 0 for none
     * @param syncEverySubsubframe the core's {@code ASPF}
     * @param scale the 6-bit scale factor index of every subband
     * @param block whether the codes are block codes rather than Huffman codes
     * @param codes the codes, -1 to 1, by subframe, channel, subband from 27 and sample
     * @return the extension bytes, a multiple of four bytes
     */
    static byte[] coded(int channels, int[] subsubframes, int[] partials, boolean syncEverySubsubframe, int scale,
                        boolean block, int[][][][] codes) {
        return write(channels, subsubframes, partials, syncEverySubsubframe, 0, scale, block, codes);
    }

    private static byte[] write(int channels, int[] subsubframes, int[] partials, boolean syncEverySubsubframe,
                                int minimumSize, int scale, boolean block, int[][][][] codes) {
        var body = new DtsBitWriter();
        // REVNO 1, the lowest resolution quantizers, residuals from subband 27
        body.write(1, 4);
        body.write(0, 1);
        body.write(27, 5);
        // SBE96: the residuals of subbands 27 to 31 and subband 32, without bits, at the smallest scale factor
        for (var ch = 0; ch < channels; ch++) {
            body.write(32, 6);
        }
        for (var ch = 0; ch < channels; ch++) {
            body.write(0, 3);
        }
        // scale factors and bit allocations in plain codes
        for (var ch = 0; ch < channels; ch++) {
            body.write(5, 3);
        }
        for (var ch = 0; ch < channels; ch++) {
            body.write(7, 3);
        }
        for (var ch = 0; ch < channels; ch++) {
            body.write(block ? 1 : 0, 1);
        }
        for (var n = 1; n < 5; n++) {
            for (var ch = 0; ch < channels; ch++) {
                body.write(0, 2);
            }
        }
        for (var ch = 0; ch < channels; ch++) {
            body.write(0, 3);
        }
        for (var subframe = 0; subframe < subsubframes.length; subframe++) {
            var count = subsubframes[subframe];
            var partial = partials[subframe];
            for (var ch = 0; ch < channels; ch++) {
                body.write(0, BANDS);
            }
            for (var ch = 0; ch < channels; ch++) {
                for (var n = 0; n < BANDS; n++) {
                    body.write(codes == null ? 0 : 2, 3);
                }
            }
            for (var ch = 0; ch < channels; ch++) {
                for (var n = 0; n < BANDS; n++) {
                    body.write(scale, 6);
                }
            }
            var last = partial > 0 ? count : count - 1;
            for (var s = 0; s <= last; s++) {
                if (codes != null) {
                    var samples = s < count ? 8 : partial;
                    for (var ch = 0; ch < channels; ch++) {
                        for (var n = 0; n < BANDS; n++) {
                            writeCodes(body, codes[subframe][ch][n], 8 * s, samples, block);
                        }
                    }
                }
                if (s >= count - 1 || syncEverySubsubframe) {
                    body.write(0xFFFF, 16);
                }
            }
        }
        var bodyBits = body.position();
        var bits = 32 + 12 + bodyBits;
        var size = Math.max(minimumSize, (int) ((bits + 31) / 32 * 4));
        var out = new DtsBitWriter();
        out.write(SYNC, 32);
        out.write(size - 1, 12);
        var bytes = body.toByteArray();
        var reader = new com.github.auties00.smalt.internal.bits.MsbBitReader(bytes);
        for (long i = 0; i < bodyBits; i++) {
            out.write(reader.read(1), 1);
        }
        out.alignTo(32);
        while (out.position() < size * 8L) {
            out.write(0, 32);
        }
        return out.toByteArray();
    }

    /**
     * Writes the codes of the three-level quantizer for the samples of one subband and subsubframe: Huffman codes of
     * code book A3, or block codes of four samples.
     */
    private static void writeCodes(DtsBitWriter out, int[] codes, int base, int samples, boolean block) {
        if (!block) {
            for (var m = 0; m < samples; m++) {
                CoreStreamWriter.huffman(out, "A3", codes[base + m]);
            }
            return;
        }
        for (var b = 0; b < (samples + 3) / 4; b++) {
            var code = 0;
            for (var m = 3; m >= 0; m--) {
                code = code * 3 + codes[base + 4 * b + m] + 1;
            }
            out.write(code, 7);
        }
    }
}
