/* Ports jxl-oxide 0.12.6 (crates/jxl-vardct: LfGlobalVarDct). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.vardct;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.entropy.EntropyDecoder;

/**
 * Holds the VarDCT part of the LF global section (ISO/IEC 18181-1 sections I.2.1 to I.2.3): the
 * quantizer, the block context map of the HF coefficients and the chroma-from-luma parameters.
 *
 * @param globalScale the global quantization scale
 * @param quantLf the LF quantization multiplier
 * @param qfThresholds the quantization field thresholds of the block contexts
 * @param lfThresholds the LF thresholds of the X, Y and B channels
 * @param blockContextMap the context of every (channel, order, quantization, LF) combination
 * @param numBlockClusters the number of block contexts
 * @param colorFactor the denominator of the chroma-from-luma factors
 * @param baseCorrelationX the base X from Y correlation
 * @param baseCorrelationB the base B from Y correlation
 * @param xFactorLf the LF X from Y factor, biased by 128
 * @param bFactorLf the LF B from Y factor, biased by 128
 */
public record LfGlobalVarDct(int globalScale, int quantLf, int[] qfThresholds, int[][] lfThresholds,
                             int[] blockContextMap, int numBlockClusters, int colorFactor, float baseCorrelationX,
                             float baseCorrelationB, int xFactorLf, int bFactorLf) {
    /**
     * The default block context map (ISO/IEC 18181-1 section I.2.2).
     */
    private static final int[] DEFAULT_CONTEXT_MAP = {
            0, 1, 2, 2, 3, 3, 4, 5, 6, 6, 6, 6, 6, 7, 8, 9, 9, 10, 11, 12, 13, 14, 14, 14,
            14, 14, 7, 8, 9, 9, 10, 11, 12, 13, 14, 14, 14, 14, 14,
    };

    /**
     * Reads the VarDCT globals.
     *
     * @param br the bitstream
     * @return the globals
     * @throws MediaException.Malformed if a field is invalid
     */
    public static LfGlobalVarDct read(JxlBitReader br) {
        var globalScale = br.readU32(1, 11, 2049, 11, 4097, 12, 8193, 16);
        var quantLf = br.readU32(16, 0, 1, 5, 1, 8, 1, 16);
        int[] qf;
        int[][] lf = {new int[0], new int[0], new int[0]};
        int[] map;
        int clusters;
        if (br.readBool()) {
            qf = new int[0];
            map = DEFAULT_CONTEXT_MAP;
            clusters = 15;
        } else {
            var bsize = 1;
            for (var c = 0; c < 3; c++) {
                var count = br.readBits(4);
                bsize *= count + 1;
                lf[c] = new int[count];
                for (var i = 0; i < count; i++) {
                    lf[c][i] = unpackSigned(br.readU32(0, 4, 16, 8, 272, 16, 65808, 32));
                }
            }
            var qfCount = br.readBits(4);
            bsize *= qfCount + 1;
            qf = new int[qfCount];
            for (var i = 0; i < qfCount; i++) {
                qf[i] = 1 + br.readU32(0, 2, 4, 3, 12, 5, 44, 8);
            }
            var clustering = EntropyDecoder.readClusters(br, bsize * 39);
            clusters = clustering[0][0];
            map = clustering[1];
        }
        int colorFactor = 84;
        var baseX = 0.0f;
        var baseB = 1.0f;
        var xFactor = 128;
        var bFactor = 128;
        if (!br.readBool()) {
            colorFactor = br.readU32(84, 0, 256, 0, 2, 8, 258, 16);
            baseX = br.readF16();
            baseB = br.readF16();
            xFactor = br.readBits(8);
            bFactor = br.readBits(8);
        }
        return new LfGlobalVarDct(globalScale, quantLf, qf, lf, map, clusters, colorFactor, baseX, baseB, xFactor,
                bFactor);
    }

    /**
     * Maps an unsigned value to a signed one ({@code UnpackSigned}).
     *
     * @param value the unsigned value
     * @return the signed value
     */
    private static int unpackSigned(int value) {
        return (value >>> 1) ^ -(value & 1);
    }
}
