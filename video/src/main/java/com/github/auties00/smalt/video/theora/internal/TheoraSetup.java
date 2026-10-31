/* Ports libtheora 1.2.0 (lib/decinfo.c, lib/dequant.c, lib/quant.c, lib/huffdec.c). Copyright (C) 2002-2009
   Xiph.org Foundation; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.theora.internal;

import com.github.auties00.smalt.MediaException;

/**
 * The parameters of a Theora stream: the frame geometry of the identification header, and the loop filter limits,
 * dequantization matrices and Huffman tables of the setup header.
 *
 * <p>Instances are immutable after construction.
 */
public final class TheoraSetup {
    /**
     * The zig-zag scan: the natural index of each coefficient in coding order.
     */
    static final int[] ZIG_ZAG = {
            0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5,
            12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
            35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51,
            58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63};

    private static final int QUANT_MAX = 1024 << 2;

    private static final int[] DC_QUANT_MIN = {4 << 2, 8 << 2};

    private static final int[] AC_QUANT_MIN = {2 << 2, 4 << 2};

    /** The bitstream version, major, minor and subminor. */
    public final int versionMajor;
    public final int versionMinor;
    public final int versionSubminor;

    /** The coded frame size, a multiple of 16. */
    public final int frameWidth;
    public final int frameHeight;

    /** The picture region; {@link #pictureY} counts from the top. */
    public final int pictureWidth;
    public final int pictureHeight;
    public final int pictureX;
    public final int pictureY;

    public final long fpsNumerator;
    public final long fpsDenominator;

    public final long aspectNumerator;
    public final long aspectDenominator;

    /** The color space: 0 undefined, 1 Rec. 470M, 2 Rec. 470BG. */
    public final int colorspace;

    /** The pixel format: 0 4:2:0, 2 4:2:2, 3 4:4:4. */
    public final int pixelFormat;

    public final int keyframeGranuleShift;

    final int[] loopFilterLimits = new int[64];

    /**
     * The dequantization tables, by qi, plane and intra (0) or inter (1), in zig-zag order and 4 times the
     * nominal values.
     */
    final int[][][][] dequant = new int[64][3][2][];

    /**
     * The 80 Huffman trees: the four groups of 16 tables for the DC and the AC coefficient bands.
     */
    final TheoraHuffman[] huffman = new TheoraHuffman[80];

    /**
     * Parses the three headers of a stream.
     *
     * @param identification the identification header packet
     * @param setup the setup header packet
     * @throws MediaException.Malformed if a header is invalid
     * @throws MediaException.Unsupported if the stream is of a newer bitstream version
     */
    public TheoraSetup(byte[] identification, byte[] setup) {
        var bits = new TheoraBits();
        checkType(identification, 0x80);
        bits.reset(identification, 7, identification.length - 7);
        versionMajor = (int) bits.read(8);
        versionMinor = (int) bits.read(8);
        versionSubminor = (int) bits.read(8);
        if (versionMajor > 3 || versionMajor == 3 && versionMinor > 2) {
            throw new MediaException.Unsupported("theora", "Theora bitstream version " + versionMajor + "."
                    + versionMinor + "." + versionSubminor);
        }
        frameWidth = (int) bits.read(16) << 4;
        frameHeight = (int) bits.read(16) << 4;
        pictureWidth = (int) bits.read(24);
        pictureHeight = (int) bits.read(24);
        pictureX = (int) bits.read(8);
        var bottomY = (int) bits.read(8);
        fpsNumerator = bits.read(32);
        fpsDenominator = bits.read(32);
        if (frameWidth == 0 || frameHeight == 0 || pictureWidth + pictureX > frameWidth
                || pictureHeight + bottomY > frameHeight || fpsNumerator == 0 || fpsDenominator == 0) {
            throw new MediaException.Malformed("invalid Theora identification header");
        }
        pictureY = frameHeight - pictureHeight - bottomY;
        aspectNumerator = bits.read(24);
        aspectDenominator = bits.read(24);
        colorspace = (int) bits.read(8);
        bits.read(24);
        bits.read(6);
        keyframeGranuleShift = (int) bits.read(5);
        pixelFormat = (int) bits.read(2);
        if (pixelFormat == 1) {
            throw new MediaException.Malformed("reserved Theora pixel format");
        }
        if (bits.read(3) != 0 || bits.exhausted()) {
            throw new MediaException.Malformed("invalid Theora identification header");
        }
        checkType(setup, 0x82);
        bits.reset(setup, 7, setup.length - 7);
        readQuantization(bits);
        for (var i = 0; i < 80; i++) {
            huffman[i] = new TheoraHuffman(bits);
        }
        if (bits.exhausted()) {
            throw new MediaException.Malformed("Theora setup header truncated");
        }
    }

    private static void checkType(byte[] packet, int type) {
        if (packet == null || packet.length < 7 || (packet[0] & 0xFF) != type || packet[1] != 't'
                || packet[2] != 'h' || packet[3] != 'e' || packet[4] != 'o' || packet[5] != 'r' || packet[6] != 'a') {
            throw new MediaException.Malformed("Theora header of type 0x" + Integer.toHexString(type) + " missing");
        }
    }

    private static int ilog(int value) {
        return 32 - Integer.numberOfLeadingZeros(value);
    }

    /**
     * Reads the quantization parameters ({@code oc_quant_params_unpack}) and builds the dequantization tables
     * ({@code oc_dequant_tables_init}).
     */
    private void readQuantization(TheoraBits bits) {
        var nbits = (int) bits.read(3);
        for (var qi = 0; qi < 64; qi++) {
            loopFilterLimits[qi] = (int) bits.read(nbits);
        }
        nbits = (int) bits.read(4) + 1;
        var acScale = new int[64];
        for (var qi = 0; qi < 64; qi++) {
            acScale[qi] = (int) bits.read(nbits);
        }
        nbits = (int) bits.read(4) + 1;
        var dcScale = new int[64];
        for (var qi = 0; qi < 64; qi++) {
            dcScale[qi] = (int) bits.read(nbits);
        }
        var count = (int) bits.read(9) + 1;
        var bases = new int[count][64];
        for (var b = 0; b < count; b++) {
            for (var c = 0; c < 64; c++) {
                bases[b][c] = (int) bits.read(8);
            }
        }
        nbits = ilog(count - 1);
        var rangeSizes = new int[6][];
        var rangeMatrices = new int[6][][];
        for (var i = 0; i < 6; i++) {
            var qti = i / 3;
            var pli = i % 3;
            if (i > 0 && bits.bit() == 0) {
                int source;
                if (qti > 0 && bits.bit() != 0) {
                    source = (qti - 1) * 3 + pli;
                } else {
                    source = i - 1;
                }
                rangeSizes[i] = rangeSizes[source];
                rangeMatrices[i] = rangeMatrices[source];
                continue;
            }
            var sizes = new int[64];
            var indices = new int[65];
            indices[0] = (int) bits.read(nbits);
            var qi = 0;
            var qri = 0;
            while (qi < 63) {
                var size = (int) bits.read(ilog(62 - qi)) + 1;
                sizes[qri] = size;
                qi += size;
                indices[++qri] = (int) bits.read(nbits);
            }
            if (qi > 63) {
                throw new MediaException.Malformed("Theora quantizer ranges exceed 64 indices");
            }
            var matrices = new int[qri + 1][];
            for (var r = 0; r <= qri; r++) {
                if (indices[r] >= count) {
                    throw new MediaException.Malformed("Theora quantizer range of an undefined base matrix");
                }
                matrices[r] = bases[indices[r]];
            }
            rangeSizes[i] = java.util.Arrays.copyOf(sizes, qri);
            rangeMatrices[i] = matrices;
        }
        for (var qti = 0; qti < 2; qti++) {
            for (var pli = 0; pli < 3; pli++) {
                var sizes = rangeSizes[qti * 3 + pli];
                var matrices = rangeMatrices[qti * 3 + pli];
                var qi = 0;
                for (var qri = 0; qri <= sizes.length; qri++) {
                    var base = matrices[qri].clone();
                    var qiStart = qi;
                    var qiEnd = qri == sizes.length ? qi + 1 : qi + sizes[qri];
                    while (true) {
                        var table = new int[64];
                        var factor = (long) dcScale[qi] * base[0];
                        var q = (int) ((factor / 100) << 2);
                        table[0] = Math.clamp(q, DC_QUANT_MIN[qti], QUANT_MAX);
                        for (var zzi = 1; zzi < 64; zzi++) {
                            q = (int) (((long) acScale[qi] * base[ZIG_ZAG[zzi]] / 100) << 2);
                            table[zzi] = Math.clamp(q, AC_QUANT_MIN[qti], QUANT_MAX);
                        }
                        dequant[qi][pli][qti] = table;
                        if (++qi >= qiEnd) {
                            break;
                        }
                        for (var c = 0; c < 64; c++) {
                            base[c] = (2 * ((qiEnd - qi) * matrices[qri][c] + (qi - qiStart) * matrices[qri + 1][c])
                                    + sizes[qri]) / (2 * sizes[qri]) & 0xFF;
                        }
                    }
                }
            }
        }
    }
}
