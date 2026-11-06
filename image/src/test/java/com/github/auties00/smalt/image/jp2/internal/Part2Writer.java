package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaLimits;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes ISO/IEC 15444-2 test codestreams of one tile, one quality layer and the LRCP progression from wavelet
 * coefficients a test computed with {@link Part2Forward}. The code-blocks are coded with the encoder's block coder and
 * packets, which ISO/IEC 15444-1 conformance already covers; the sub-band geometry comes from the decoder's own
 * structures, so a geometry the decoder gets wrong places the coefficients where the test's transform did not put
 * them.
 */
final class Part2Writer {
    long x0;
    long y0;
    int width;
    int height;
    int rsiz = Codestream.RSIZ_PART2;
    int[] precisions;
    boolean[] signed;
    int[] subsamplingX;
    int[] subsamplingY;
    int mct;
    int levels;
    int decompositionByte = -1;
    int wavelet = 1;
    int codeBlock = 4;
    int guardBits = 2;
    int quantizationStyle = Quantization.NONE;
    int exponent = -1;
    int mantissa;
    /** The deadzone adjustment {@code num_nz} the variable deadzone styles signal, for every sub-band. */
    int deadzone;
    /** The step size exponent of each sub-band of an expounded style, in codestream order, or {@code null}. */
    int[] bandExponents;
    /** The deadzone adjustment of each sub-band of {@link Quantization#DEADZONE_EXPOUNDED}, or {@code null}. */
    int[] bandDeadzones;
    /** The base-2 logarithm of the precinct width and height of each resolution level, or {@code null}. */
    int[] precinctSizes;
    /** The precinct-dependent quantization the test's segments declare for each component, or {@code null}. */
    PrecinctQuantizations[] precinctQuantizations;
    /** The coding passes left out of the end of every code-block. */
    int droppedPasses;
    /** The code-block style flags ({@link ComponentCoding#TERMINATE_ALL} makes every truncation point exact). */
    int codeBlockStyle;
    /** The SSO field of Table A.11, written after the transformation byte when {@link #rsiz} declares SSO. */
    int overlap;
    LiftingKernel kernel;
    Decomposition decomposition;
    final List<byte[]> mainSegments = new ArrayList<>();
    final List<byte[]> tileSegments = new ArrayList<>();
    int[][] regionScales;

    Part2Writer(int width, int height, int components, int precision, boolean signed) {
        this.width = width;
        this.height = height;
        this.precisions = new int[components];
        this.signed = new boolean[components];
        this.subsamplingX = new int[components];
        this.subsamplingY = new int[components];
        for (var c = 0; c < components; c++) {
            precisions[c] = precision;
            this.signed[c] = signed;
            subsamplingX[c] = 1;
            subsamplingY[c] = 1;
        }
    }

    int components() {
        return precisions.length;
    }

    long componentX0(int c) {
        return Math.ceilDiv(x0, subsamplingX[c]);
    }

    long componentY0(int c) {
        return Math.ceilDiv(y0, subsamplingY[c]);
    }

    int componentWidth(int c) {
        return (int) (Math.ceilDiv(x0 + width, subsamplingX[c]) - componentX0(c));
    }

    int componentHeight(int c) {
        return (int) (Math.ceilDiv(y0 + height, subsamplingY[c]) - componentY0(c));
    }

    private ComponentCoding coding() {
        var resolutions = decomposition != null ? levels + 1 : levels + 1;
        var style = decompositionByte >= 0 ? decompositionByte & 0x7F : 0;
        int[] widths = null;
        if (precinctSizes != null) {
            widths = new int[resolutions];
            for (var r = 0; r < resolutions; r++) {
                widths[r] = precinctSizes[Math.min(r, precinctSizes.length - 1)];
            }
        }
        return new ComponentCoding(resolutions, codeBlock, codeBlock, codeBlockStyle, wavelet, style, widths, widths,
                overlap);
    }

    private boolean derived() {
        return quantizationStyle == Quantization.SCALAR_DERIVED || quantizationStyle == Quantization.DEADZONE_DERIVED
                || quantizationStyle == Quantization.TRELLIS_DERIVED;
    }

    private boolean deadzoned() {
        return quantizationStyle >= Quantization.DEADZONE_DERIVED
                && quantizationStyle <= Quantization.DEADZONE_EXPOUNDED;
    }

    private int bandExponent(int band, int exponent) {
        if (derived()) {
            return band == 0 ? exponent : Math.max(exponent - (band - 1) / 3, 0);
        }
        return bandExponents != null ? bandExponents[band] : exponent;
    }

    private int bandDeadzone(int band) {
        return bandDeadzones != null && quantizationStyle == Quantization.DEADZONE_EXPOUNDED ? bandDeadzones[band]
                : deadzone;
    }

    private Quantization quantization(int exponent) {
        var exponents = new int[4096];
        var mantissas = new int[4096];
        var deadzones = deadzoned() ? new int[4096] : null;
        for (var b = 0; b < exponents.length; b++) {
            exponents[b] = bandExponents != null && b >= bandExponents.length ? 0 : bandExponent(b, exponent);
            if (deadzones != null) {
                deadzones[b] = bandDeadzones != null && b >= bandDeadzones.length ? 0 : bandDeadzone(b);
            }
        }
        java.util.Arrays.fill(mantissas, mantissa);
        return new Quantization(quantizationStyle, guardBits, exponents, mantissas, deadzones);
    }

    private TileComponent structure(int c, Quantization quantization, long[] bounds) {
        return new TileComponent(Math.ceilDiv(bounds[0], subsamplingX[c]), Math.ceilDiv(bounds[1], subsamplingY[c]),
                Math.ceilDiv(bounds[2], subsamplingX[c]), Math.ceilDiv(bounds[3], subsamplingY[c]), coding(),
                quantization, precisions[c], levels + 1, true, kernel, decomposition,
                precinctQuantizations == null ? null : precinctQuantizations[c], MediaLimits.DEFAULT);
    }

    /**
     * Returns the encoder's structure of a component of the tile covering the image: its sub-bands with the encoder's
     * step sizes (sub-band gains included), bit-plane counts and buffer layout.
     */
    TileComponent encoderStructure(int c) {
        return structure(c, quantization(exponent), new long[]{x0, y0, x0 + width, y0 + height});
    }

    /** The nominal tile width and height, or 0 for one tile covering the image. */
    long tileWidth;
    long tileHeight;

    private boolean tiled() {
        return tileWidth > 0;
    }

    private int tilesWide() {
        return tiled() ? (int) Math.ceilDiv(x0 + width, tileWidth) : 1;
    }

    private int tilesHigh() {
        return tiled() ? (int) Math.ceilDiv(y0 + height, tileHeight) : 1;
    }

    /**
     * Returns the reference grid area a tile codes: its nominal area, grown by the column and row of the tile overlap
     * of ISO/IEC 15444-2 I.4 (equations I-46 to I-49) when {@link #overlap} sets TSSO.
     */
    long[] codingBounds(int tile) {
        if (!tiled()) {
            return new long[]{x0, y0, x0 + width, y0 + height};
        }
        var p = tile % tilesWide();
        var q = tile / tilesWide();
        var tsso = (overlap & 0x4000) != 0;
        var hovlp = (overlap & 0x1000) != 0 ? 1 : 0;
        var vovlp = (overlap & 0x2000) != 0 ? 1 : 0;
        long tx0 = p * tileWidth;
        long ty0 = q * tileHeight;
        long tx1 = (p + 1) * tileWidth;
        long ty1 = (q + 1) * tileHeight;
        if (tsso) {
            tx0 -= 1 - hovlp;
            ty0 -= 1 - vovlp;
            tx1 += hovlp;
            ty1 += vovlp;
        }
        return new long[]{Math.max(tx0, x0), Math.max(ty0, y0), Math.min(tx1, x0 + width),
                Math.min(ty1, y0 + height)};
    }

    /**
     * Writes a reversible codestream of {@link #tileWidth} by {@link #tileHeight} tiles; {@code coefficients} gives the
     * coefficients of each component of a tile, row-major over the tile-component, from the tile's index and coding
     * area (left, top, right and bottom on the reference grid).
     */
    byte[] writeTiles(java.util.function.BiFunction<Integer, long[], long[][]> coefficients, int exponent) {
        var quantization = quantization(exponent);
        var bodies = new ArrayList<byte[]>();
        for (var t = 0; t < tilesWide() * tilesHigh(); t++) {
            var bounds = codingBounds(t);
            var planes = coefficients.apply(t, bounds);
            var integers = new int[planes.length][];
            for (var c = 0; c < planes.length; c++) {
                integers[c] = new int[planes[c].length];
                for (var i = 0; i < integers[c].length; i++) {
                    integers[c][i] = Math.toIntExact(planes[c][i]);
                }
            }
            bodies.add(tileBody(integers, null, null, quantization, bounds));
        }
        return assemble(bodies, bandCount(quantization), exponent);
    }

    /** The irreversible counterpart of {@link #writeTiles(java.util.function.BiFunction, int)}. */
    byte[] writeIrreversibleTiles(java.util.function.BiFunction<Integer, long[], float[][]> coefficients) {
        var quantization = quantization(exponent);
        var bodies = new ArrayList<byte[]>();
        for (var t = 0; t < tilesWide() * tilesHigh(); t++) {
            var bounds = codingBounds(t);
            bodies.add(tileBody(null, coefficients.apply(t, bounds), null, quantization, bounds));
        }
        return assemble(bodies, bandCount(quantization), exponent);
    }

    private int bandCount(Quantization quantization) {
        var bands = 0;
        for (var resolution : structure(0, quantization, codingBounds(0)).resolutions) {
            bands += resolution.bands().length;
        }
        return bands;
    }

    /** Writes a reversible codestream from integer coefficients. */
    byte[] write(long[][] coefficients) {
        var max = 1L;
        for (var plane : coefficients) {
            for (var value : plane) {
                max = Math.max(max, Math.abs(value));
            }
        }
        var bits = 64 - Long.numberOfLeadingZeros(max);
        if (bits > 24) {
            throw new IllegalArgumentException("coefficients of " + bits + " bits overflow the block coder");
        }
        var exponent = this.exponent >= 0 ? this.exponent : bits;
        var integers = new int[coefficients.length][];
        for (var c = 0; c < coefficients.length; c++) {
            integers[c] = new int[coefficients[c].length];
            for (var i = 0; i < integers[c].length; i++) {
                integers[c][i] = (int) coefficients[c][i];
            }
        }
        return write(integers, null, null, exponent);
    }

    /** Writes an irreversible codestream from real coefficients, quantized with the configured step size. */
    byte[] write(float[][] coefficients) {
        if (exponent < 0) {
            throw new IllegalStateException("an irreversible codestream needs an explicit exponent");
        }
        return write(null, coefficients, null, exponent);
    }

    /** Quantizes the coefficients of one code-block to the indices the block coder codes losslessly. */
    interface BlockQuantizer {
        /**
         * Quantizes a code-block whose coefficients are row-major, with the step size of its precinct (the encoder's
         * step size of equation E-3, sub-band gain included), given the block's resolution level, the orientation of
         * its sub-band (0 LL, 1 HL, 2 LH, 3 HH) and its precinct index.
         */
        void quantize(float[] coefficients, int[] indices, int width, int height, float stepSize, int resolution,
                      int orientation, int precinct);
    }

    /**
     * Writes an irreversible codestream from real coefficients, each code-block quantized by the test, the indices
     * coded losslessly.
     */
    byte[] write(float[][] coefficients, BlockQuantizer quantizer) {
        if (exponent < 0) {
            throw new IllegalStateException("an irreversible codestream needs an explicit exponent");
        }
        var integers = new int[coefficients.length][];
        for (var c = 0; c < integers.length; c++) {
            integers[c] = new int[coefficients[c].length];
        }
        return write(integers, coefficients, quantizer, exponent);
    }

    private byte[] write(int[][] integers, float[][] floats, BlockQuantizer quantizer, int exponent) {
        var quantization = quantization(exponent);
        var bounds = new long[]{x0, y0, x0 + width, y0 + height};
        return assemble(List.of(tileBody(integers, floats, quantizer, quantization, bounds)),
                bandCount(quantization), exponent);
    }

    private byte[] tileBody(int[][] integers, float[][] floats, BlockQuantizer quantizer, Quantization quantization,
                            long[] bounds) {
        var count = components();
        var structures = new TileComponent[count];
        for (var c = 0; c < count; c++) {
            structures[c] = structure(c, quantization, bounds);
        }
        var coder = new Tier1Encoder();
        for (var c = 0; c < count; c++) {
            var component = structures[c];
            var stride = (int) (component.x1 - component.x0);
            for (var r = 0; r < component.resolutions.length; r++) {
                for (var band : component.resolutions[r].bands()) {
                    if (band.empty()) {
                        continue;
                    }
                    for (var p = 0; p < band.precincts().length; p++) {
                        var precinct = band.precincts()[p];
                        for (var block : precinct.encodedBlocks) {
                            var x = (int) (block.x0 - band.x0()) + band.offsetX();
                            var y = (int) (block.y0 - band.y0()) + band.offsetY();
                            var offset = y * stride + x;
                            var shift = 0;
                            if (quantizer != null) {
                                quantizeBlock(quantizer, floats[c], integers[c], offset, stride, block,
                                        precinct.stepSize, r, band.orientation(), p);
                            }
                            if (regionScales != null && regionScales[c] != null) {
                                shift = scaleBlock(integers[c], regionScales[c], offset, stride, block);
                            }
                            coder.encode(block, integers == null ? null : integers[c], null,
                                    integers != null ? null : floats[c], offset, stride, band.orientation(), 0,
                                    precinct.stepSize, integers != null, codeBlockStyle, 1.0, null, 0, 0);
                            if (block.bitPlanes - shift > precinct.bitPlanes) {
                                throw new IllegalStateException("a code-block has " + block.bitPlanes
                                        + " bit-planes, more than the " + precinct.bitPlanes + " of its precinct");
                            }
                            block.bitPlanes -= shift;
                            block.ensureLayers(1);
                            var passes = Math.max(block.totalPasses - droppedPasses, 0);
                            block.layerPasses[0] = passes;
                            if (passes > 0) {
                                block.layerLength[0] = block.passRate[passes - 1];
                                block.layerOffset[0] = 0;
                            }
                            block.passesInLayers = passes;
                        }
                    }
                }
            }
        }
        var order = new PacketOrder(bounds, structures, subsamplingX, subsamplingY, 1, false, MediaLimits.DEFAULT);
        var packets = new PacketEncoder(false, false);
        var body = new ByteArrayOutputStream();
        order.list(new ProgressionChange(0, 0, 1, ComponentCoding.MAX_RESOLUTIONS, count, ProgressionChange.LRCP),
                (layer, resolution, component, precinct) -> {
                    packets.write(structures[component], resolution, precinct, layer, body);
                    return true;
                });
        return body.toByteArray();
    }

    private static void quantizeBlock(BlockQuantizer quantizer, float[] plane, int[] indices, int offset, int stride,
                                      EncodedBlock block, float stepSize, int resolution, int orientation,
                                      int precinct) {
        var w = (int) (block.x1 - block.x0);
        var h = (int) (block.y1 - block.y0);
        var values = new float[w * h];
        for (var j = 0; j < h; j++) {
            System.arraycopy(plane, offset + j * stride, values, j * w, w);
        }
        var result = new int[w * h];
        quantizer.quantize(values, result, w, h, stepSize, resolution, orientation, precinct);
        for (var j = 0; j < h; j++) {
            System.arraycopy(result, j * w, indices, offset + j * stride, w);
        }
    }

    /**
     * Scales the coefficients of one code-block for arbitrary regions of interest (L.2.1): each by its scaling value
     * less the block's smallest. Returns {@code sMax - sMin}, the bit-planes the decoder adds to the block.
     */
    private static int scaleBlock(int[] data, int[] scales, int offset, int stride, EncodedBlock block) {
        var w = (int) (block.x1 - block.x0);
        var h = (int) (block.y1 - block.y0);
        var min = Integer.MAX_VALUE;
        var max = 0;
        for (var j = 0; j < h; j++) {
            for (var i = 0; i < w; i++) {
                var s = scales[offset + j * stride + i];
                min = Math.min(min, s);
                max = Math.max(max, s);
            }
        }
        for (var j = 0; j < h; j++) {
            for (var i = 0; i < w; i++) {
                var index = offset + j * stride + i;
                var value = data[index];
                var magnitude = Math.abs(value) << (scales[index] - min);
                data[index] = value < 0 ? -magnitude : magnitude;
            }
        }
        return max - min;
    }

    private byte[] assemble(List<byte[]> bodies, int bands, int exponent) {
        var out = new ByteArrayOutputStream();
        Part2Forward.u16(out, Codestream.SOC);
        var siz = new ByteArrayOutputStream();
        Part2Forward.u16(siz, rsiz);
        Part2Forward.u32(siz, x0 + width);
        Part2Forward.u32(siz, y0 + height);
        Part2Forward.u32(siz, x0);
        Part2Forward.u32(siz, y0);
        Part2Forward.u32(siz, tiled() ? tileWidth : width);
        Part2Forward.u32(siz, tiled() ? tileHeight : height);
        Part2Forward.u32(siz, tiled() ? 0 : x0);
        Part2Forward.u32(siz, tiled() ? 0 : y0);
        Part2Forward.u16(siz, components());
        for (var c = 0; c < components(); c++) {
            siz.write((precisions[c] - 1) | (signed[c] ? 0x80 : 0));
            siz.write(subsamplingX[c]);
            siz.write(subsamplingY[c]);
        }
        out.writeBytes(Part2Forward.segment(Codestream.SIZ, siz.toByteArray()));
        var cod = new ByteArrayOutputStream();
        cod.write(precinctSizes != null ? 1 : 0);
        cod.write(ProgressionChange.LRCP);
        Part2Forward.u16(cod, 1);
        cod.write(mct);
        cod.write(decompositionByte >= 0 ? decompositionByte : levels);
        cod.write(codeBlock - 2);
        cod.write(codeBlock - 2);
        cod.write(codeBlockStyle);
        cod.write(wavelet);
        if ((rsiz & Codestream.RSIZ_SSO) != 0) {
            Part2Forward.u16(cod, overlap);
        }
        if (precinctSizes != null) {
            for (var r = 0; r <= levels; r++) {
                var size = precinctSizes[Math.min(r, precinctSizes.length - 1)];
                cod.write(size | (size << 4));
            }
        }
        out.writeBytes(Part2Forward.segment(Codestream.COD, cod.toByteArray()));
        var qcd = new ByteArrayOutputStream();
        qcd.write(quantizationStyle | (guardBits << 5));
        var signalled = derived() ? 1 : bands;
        if (quantizationStyle == Quantization.DEADZONE_DERIVED_STEPS_EXPOUNDED) {
            Part2Forward.u16(qcd, deadzone & 0xFFFF);
        }
        for (var b = 0; b < signalled; b++) {
            if (quantizationStyle == Quantization.NONE) {
                qcd.write(exponent << 3);
            } else {
                if (quantizationStyle == Quantization.DEADZONE_DERIVED
                        || quantizationStyle == Quantization.DEADZONE_EXPOUNDED) {
                    Part2Forward.u16(qcd, bandDeadzone(b) & 0xFFFF);
                }
                Part2Forward.u16(qcd, (bandExponent(b, exponent) << 11) | mantissa);
            }
        }
        out.writeBytes(Part2Forward.segment(Codestream.QCD, qcd.toByteArray()));
        for (var segment : mainSegments) {
            out.writeBytes(segment);
        }
        var header = new ByteArrayOutputStream();
        for (var segment : tileSegments) {
            header.writeBytes(segment);
        }
        for (var t = 0; t < bodies.size(); t++) {
            var packets = bodies.get(t);
            Part2Forward.u16(out, Codestream.SOT);
            Part2Forward.u16(out, 10);
            Part2Forward.u16(out, t);
            Part2Forward.u32(out, 12L + header.size() + 2 + packets.length);
            out.write(0);
            out.write(1);
            out.writeBytes(header.toByteArray());
            Part2Forward.u16(out, Codestream.SOD);
            out.writeBytes(packets);
        }
        Part2Forward.u16(out, Codestream.EOC);
        return out.toByteArray();
    }
}
