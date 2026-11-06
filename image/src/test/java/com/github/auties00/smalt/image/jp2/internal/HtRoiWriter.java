package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaLimits;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.function.IntPredicate;

/**
 * Writes HTJ2K codestreams (ISO/IEC 15444-15) of one 38-bit signed component, one tile, one quality layer and the
 * 5-3 wavelet whose code-blocks hold a maximum shift region of interest: the coefficients a test selects are scaled
 * by {@code 2^shift}, which with the 37 magnitude bit-planes of the sub-bands (7 guard bits, exponent 31, by default) reaches
 * the 74 bit-planes of the HTJ2K bound. The code-blocks are coded with {@link WideHtEncoder} and the packets with the
 * encoder's {@link PacketEncoder}; the sub-band geometry comes from the decoder's structures.
 */
final class HtRoiWriter {
    static final int PRECISION = 38;

    final int width;
    final int height;
    int levels;
    int codeBlock = 5;
    /** The guard bits of the sub-bands; with {@link #exponent}, their {@code Mb} is 37 by default. */
    int guardBits = 7;
    /** The exponent of the sub-bands. */
    int exponent = 31;
    int shift;
    int plane;
    boolean refine;
    boolean causal;
    /**
     * The scaling value of each coefficient for the arbitrary regions of ISO/IEC 15444-2 L.2.1, laid out as the
     * coefficients, or {@code null} for the maximum shift method; with them {@link #regionSegment} is written.
     */
    int[] regionScales;
    /** The {@code RGN} segment of the arbitrary regions {@link #regionScales} describes. */
    byte[] regionSegment;

    HtRoiWriter(int width, int height) {
        this.width = width;
        this.height = height;
    }

    private TileComponent structure() {
        var style = ComponentCoding.HIGH_THROUGHPUT | (causal ? ComponentCoding.VERTICALLY_CAUSAL : 0);
        var coding = new ComponentCoding(levels + 1, codeBlock, codeBlock, style, 1, 0, null, null);
        var exponents = new int[4096];
        Arrays.fill(exponents, exponent);
        var quantization = new Quantization(Quantization.NONE, guardBits, exponents, new int[4096]);
        return new TileComponent(0, 0, width, height, coding, quantization, PRECISION, levels + 1, true, null, null,
                MediaLimits.DEFAULT);
    }

    /**
     * Writes a codestream from the wavelet coefficients of the component, laid out as the decoder's buffer, scaling
     * by {@code 2^shift} those the predicate selects by index.
     */
    byte[] write(long[] coefficients, IntPredicate region) {
        var component = structure();
        var stride = width;
        var bands = 0;
        for (var resolution : component.resolutions) {
            bands += resolution.bands().length;
            for (var band : resolution.bands()) {
                if (band.empty()) {
                    continue;
                }
                for (var precinct : band.precincts()) {
                    for (var block : precinct.encodedBlocks) {
                        code(block, band, coefficients, region, stride);
                    }
                }
            }
        }
        var bounds = new long[]{0, 0, width, height};
        var structures = new TileComponent[]{component};
        var order = new PacketOrder(bounds, structures, new int[]{1}, new int[]{1}, 1, false, MediaLimits.DEFAULT);
        var packets = new PacketEncoder(false, false);
        var body = new ByteArrayOutputStream();
        order.list(new ProgressionChange(0, 0, 1, ComponentCoding.MAX_RESOLUTIONS, 1, ProgressionChange.LRCP),
                (layer, resolution, c, precinct) -> {
                    packets.write(structures[c], resolution, precinct, layer, body);
                    return true;
                });
        return assemble(body.toByteArray(), bands);
    }

    private void code(EncodedBlock block, Band band, long[] coefficients, IntPredicate region, int stride) {
        var w = (int) (block.x1 - block.x0);
        var h = (int) (block.y1 - block.y0);
        var x = (int) (block.x0 - band.x0()) + band.offsetX();
        var y = (int) (block.y0 - band.y0()) + band.offsetY();
        var magnitudes = new BigInteger[w * h];
        var negative = new boolean[w * h];
        var max = BigInteger.ZERO;
        // arbitrary regions scale each coefficient by its scaling value less the block's smallest, and the block
        // gains their difference in bit-planes (ISO/IEC 15444-2 L.2.1)
        var smallest = Integer.MAX_VALUE;
        var largest = 0;
        if (regionScales != null) {
            for (var j = 0; j < h; j++) {
                for (var i = 0; i < w; i++) {
                    var scale = regionScales[(y + j) * stride + x + i];
                    smallest = Math.min(smallest, scale);
                    largest = Math.max(largest, scale);
                }
            }
        }
        var planes = regionScales != null ? largest - smallest : shift;
        for (var j = 0; j < h; j++) {
            for (var i = 0; i < w; i++) {
                var index = (y + j) * stride + x + i;
                var value = coefficients[index];
                var magnitude = BigInteger.valueOf(value).abs();
                if (regionScales != null) {
                    magnitude = magnitude.shiftLeft(regionScales[index] - smallest);
                } else if (region.test(index)) {
                    magnitude = magnitude.shiftLeft(shift);
                }
                magnitudes[j * w + i] = magnitude;
                negative[j * w + i] = value < 0;
                max = max.max(magnitude);
            }
        }
        if (max.bitLength() > guardBits + exponent - 1 + planes) {
            throw new IllegalArgumentException("a coefficient of " + max.bitLength() + " bits exceeds the sub-band");
        }
        block.ensureLayers(1);
        block.roiPlanes = planes;
        if (max.shiftRight(plane).signum() == 0) {
            block.totalPasses = 0;
            block.layerPasses[0] = 0;
            block.passesInLayers = 0;
            return;
        }
        var encoder = new WideHtEncoder(magnitudes, negative, w, h, causal);
        var refined = refine && plane > 0;
        encoder.encode(plane, refined);
        var cleanup = encoder.cleanupBytes();
        var refinement = encoder.refinementBytes();
        block.ensurePasses(3);
        block.data = new byte[cleanup.length + refinement.length];
        System.arraycopy(cleanup, 0, block.data, 0, cleanup.length);
        System.arraycopy(refinement, 0, block.data, cleanup.length, refinement.length);
        block.bitPlanes = plane + 1;
        block.passRate[0] = cleanup.length;
        block.passTerminated[0] = true;
        var passes = 1;
        if (refined) {
            passes = 3;
            block.passRate[1] = cleanup.length + encoder.sigPropLength();
            block.passRate[2] = block.data.length;
            block.passTerminated[1] = false;
            block.passTerminated[2] = true;
        }
        block.totalPasses = passes;
        block.layerPasses[0] = passes;
        block.layerLength[0] = block.passRate[passes - 1];
        block.layerOffset[0] = 0;
        block.passesInLayers = passes;
    }

    private byte[] assemble(byte[] packets, int bands) {
        var out = new ByteArrayOutputStream();
        Part2Forward.u16(out, Codestream.SOC);
        var siz = new ByteArrayOutputStream();
        Part2Forward.u16(siz, regionScales != null
                ? 0x4000 | Codestream.RSIZ_PART2 | Codestream.RSIZ_ROI : 0x4000);
        Part2Forward.u32(siz, width);
        Part2Forward.u32(siz, height);
        Part2Forward.u32(siz, 0);
        Part2Forward.u32(siz, 0);
        Part2Forward.u32(siz, width);
        Part2Forward.u32(siz, height);
        Part2Forward.u32(siz, 0);
        Part2Forward.u32(siz, 0);
        Part2Forward.u16(siz, 1);
        siz.write((PRECISION - 1) | 0x80);
        siz.write(1);
        siz.write(1);
        out.writeBytes(Part2Forward.segment(Codestream.SIZ, siz.toByteArray()));
        var cap = new ByteArrayOutputStream();
        Part2Forward.u32(cap, Codestream.PCAP_PART15);
        // the RGN set (bit 12) and the magnitude bound B = 74 (bits 0-4 = 31) of ISO/IEC 15444-15 A.3
        Part2Forward.u16(cap, 0x1000 | 31);
        out.writeBytes(Part2Forward.segment(Codestream.CAP, cap.toByteArray()));
        var cod = new ByteArrayOutputStream();
        cod.write(0);
        cod.write(ProgressionChange.LRCP);
        Part2Forward.u16(cod, 1);
        cod.write(0);
        cod.write(levels);
        cod.write(codeBlock - 2);
        cod.write(codeBlock - 2);
        cod.write(ComponentCoding.HIGH_THROUGHPUT | (causal ? ComponentCoding.VERTICALLY_CAUSAL : 0));
        cod.write(1);
        out.writeBytes(Part2Forward.segment(Codestream.COD, cod.toByteArray()));
        var qcd = new ByteArrayOutputStream();
        qcd.write(Quantization.NONE | (guardBits << 5));
        for (var b = 0; b < bands; b++) {
            qcd.write(exponent << 3);
        }
        out.writeBytes(Part2Forward.segment(Codestream.QCD, qcd.toByteArray()));
        if (regionSegment != null) {
            out.writeBytes(regionSegment);
        } else if (shift > 0) {
            out.writeBytes(Part2Forward.segment(Codestream.RGN, new byte[]{0, 0, (byte) shift}));
        }
        Part2Forward.u16(out, Codestream.SOT);
        Part2Forward.u16(out, 10);
        Part2Forward.u16(out, 0);
        Part2Forward.u32(out, 12L + 2 + packets.length);
        out.write(0);
        out.write(1);
        Part2Forward.u16(out, Codestream.SOD);
        out.writeBytes(packets);
        Part2Forward.u16(out, Codestream.EOC);
        return out.toByteArray();
    }
}
