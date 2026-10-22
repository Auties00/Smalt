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
        return new ComponentCoding(resolutions, codeBlock, codeBlock, 0, wavelet, style, null, null);
    }

    private Quantization quantization(int exponent) {
        var exponents = new int[4096];
        var mantissas = new int[4096];
        java.util.Arrays.fill(exponents, exponent);
        java.util.Arrays.fill(mantissas, mantissa);
        return new Quantization(quantizationStyle, guardBits, exponents, mantissas);
    }

    private TileComponent structure(int c, Quantization quantization) {
        return new TileComponent(componentX0(c), componentY0(c), componentX0(c) + componentWidth(c),
                componentY0(c) + componentHeight(c), coding(), quantization, precisions[c], levels + 1, true, kernel,
                decomposition, MediaLimits.DEFAULT);
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
        return write(integers, null, exponent);
    }

    /** Writes an irreversible codestream from real coefficients, quantized with the configured step size. */
    byte[] write(float[][] coefficients) {
        if (exponent < 0) {
            throw new IllegalStateException("an irreversible codestream needs an explicit exponent");
        }
        return write(null, coefficients, exponent);
    }

    private byte[] write(int[][] integers, float[][] floats, int exponent) {
        var count = components();
        var quantization = quantization(exponent);
        var structures = new TileComponent[count];
        var bands = 0;
        for (var c = 0; c < count; c++) {
            structures[c] = structure(c, quantization);
            if (c == 0) {
                for (var resolution : structures[c].resolutions) {
                    bands += resolution.bands().length;
                }
            }
        }
        var coder = new Tier1Encoder();
        for (var c = 0; c < count; c++) {
            var component = structures[c];
            var stride = componentWidth(c);
            for (var resolution : component.resolutions) {
                for (var band : resolution.bands()) {
                    if (band.empty()) {
                        continue;
                    }
                    for (var precinct : band.precincts()) {
                        for (var block : precinct.encodedBlocks) {
                            var x = (int) (block.x0 - band.x0()) + band.offsetX();
                            var y = (int) (block.y0 - band.y0()) + band.offsetY();
                            var offset = y * stride + x;
                            var shift = 0;
                            if (regionScales != null && regionScales[c] != null) {
                                shift = scaleBlock(integers[c], regionScales[c], offset, stride, block);
                            }
                            coder.encode(block, integers == null ? null : integers[c], floats == null ? null : floats[c],
                                    offset, stride, band.orientation(), 0, band.stepSize(), integers != null, 0, 1.0);
                            if (block.bitPlanes - shift > band.bitPlanes()) {
                                throw new IllegalStateException("a code-block has " + block.bitPlanes
                                        + " bit-planes, more than the " + band.bitPlanes() + " of its sub-band");
                            }
                            block.bitPlanes -= shift;
                            block.ensureLayers(1);
                            var passes = block.totalPasses;
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
        var bounds = new long[]{x0, y0, x0 + width, y0 + height};
        var order = new PacketOrder(bounds, structures, subsamplingX, subsamplingY, 1, false, MediaLimits.DEFAULT);
        var packets = new PacketEncoder(false, false);
        var body = new ByteArrayOutputStream();
        order.list(new ProgressionChange(0, 0, 1, ComponentCoding.MAX_RESOLUTIONS, count, ProgressionChange.LRCP),
                (layer, resolution, component, precinct) -> {
                    packets.write(structures[component], resolution, precinct, layer, body);
                    return true;
                });
        return assemble(body.toByteArray(), bands, exponent);
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

    private byte[] assemble(byte[] packets, int bands, int exponent) {
        var out = new ByteArrayOutputStream();
        Part2Forward.u16(out, Codestream.SOC);
        var siz = new ByteArrayOutputStream();
        Part2Forward.u16(siz, rsiz);
        Part2Forward.u32(siz, x0 + width);
        Part2Forward.u32(siz, y0 + height);
        Part2Forward.u32(siz, x0);
        Part2Forward.u32(siz, y0);
        Part2Forward.u32(siz, width);
        Part2Forward.u32(siz, height);
        Part2Forward.u32(siz, x0);
        Part2Forward.u32(siz, y0);
        Part2Forward.u16(siz, components());
        for (var c = 0; c < components(); c++) {
            siz.write((precisions[c] - 1) | (signed[c] ? 0x80 : 0));
            siz.write(subsamplingX[c]);
            siz.write(subsamplingY[c]);
        }
        out.writeBytes(Part2Forward.segment(Codestream.SIZ, siz.toByteArray()));
        var cod = new ByteArrayOutputStream();
        cod.write(0);
        cod.write(ProgressionChange.LRCP);
        Part2Forward.u16(cod, 1);
        cod.write(mct);
        cod.write(decompositionByte >= 0 ? decompositionByte : levels);
        cod.write(codeBlock - 2);
        cod.write(codeBlock - 2);
        cod.write(0);
        cod.write(wavelet);
        out.writeBytes(Part2Forward.segment(Codestream.COD, cod.toByteArray()));
        var qcd = new ByteArrayOutputStream();
        qcd.write(quantizationStyle | (guardBits << 5));
        var signalled = quantizationStyle == Quantization.SCALAR_DERIVED ? 1 : bands;
        for (var b = 0; b < signalled; b++) {
            if (quantizationStyle == Quantization.NONE) {
                qcd.write(exponent << 3);
            } else {
                Part2Forward.u16(qcd, (exponent << 11) | mantissa);
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
        Part2Forward.u16(out, Codestream.SOT);
        Part2Forward.u16(out, 10);
        Part2Forward.u16(out, 0);
        Part2Forward.u32(out, 12L + header.size() + 2 + packets.length);
        out.write(0);
        out.write(1);
        out.writeBytes(header.toByteArray());
        Part2Forward.u16(out, Codestream.SOD);
        out.writeBytes(packets);
        Part2Forward.u16(out, Codestream.EOC);
        return out.toByteArray();
    }
}
