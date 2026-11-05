/* Ports SVT-JPEG-XS 0.9.0 (PackHeaders.c, PackPrecinct.c, Quant.c, NltEnc.c). Copyright 2024 Intel Corporation;
   BSD-2-Clause-Patent. Modified for Smalt. */
package com.github.auties00.smalt.image.jxs.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.internal.task.ParallelBands;

/**
 * Encodes component samples into a JPEG XS codestream (ISO/IEC 21122-1).
 *
 * <p>The samples are scaled to the wavelet precision, color transformed, decomposed by the 5-3 wavelet and coded
 * precinct by precinct. Each precinct gets an equal share of the byte budget, plus what the earlier precincts of its
 * slice left unused; its quantization and refinement are the finest that fit the share. Every band of a precinct uses
 * the cheapest of the bit-plane count coding modes the precinct allows, and a packet switches to raw bit-plane counts
 * when that is cheaper and the parameters allow it. Slices are coded in parallel. This class is thread-safe.
 */
public final class JxsEncoderCore {
    private static final int VERTICAL = 1;
    private static final int SIGNIFICANCE = 2;

    private JxsEncoderCore() {
        throw new AssertionError();
    }

    /**
     * The coding parameters of a codestream.
     *
     * @param width the width of the sampling grid
     * @param height the height of the sampling grid
     * @param precision the precision of each component
     * @param sx the horizontal sampling factor of each component
     * @param sy the vertical sampling factor of each component
     * @param nlx the horizontal decomposition levels
     * @param nly the vertical decomposition levels
     * @param sd the number of trailing components without decomposition
     * @param colorTransform {@code Cpih}: 0, 1 (reversible color transform) or 3 (Star-Tetrix)
     * @param cf the Star-Tetrix extent
     * @param e1 the Star-Tetrix exponent of the first chroma component
     * @param e2 the Star-Tetrix exponent of the second chroma component
     * @param registrationX the component registration of a Star-Tetrix codestream, or {@code null}
     * @param registrationY the component registration of a Star-Tetrix codestream, or {@code null}
     * @param profile the profile code {@code Ppih}
     * @param level the level and sublevel {@code Plev}
     * @param bw the wavelet precision {@code Bw}
     * @param fq the fractional bits {@code Fq}
     * @param br the raw bit-plane count bits {@code Br}
     * @param uniform whether the quantizer is uniform rather than deadzone
     * @param runMode the run mode {@code Rm}
     * @param longHeaders whether long packet headers are enforced
     * @param rawPerPacket whether packets may switch to raw bit-plane counts
     * @param sliceLines the slice height in lines, a multiple of {@code 2^nly}
     * @param cw the precinct width {@code Cw}, or 0 for the full width
     * @param budget the byte budget of the codestream, or a negative value for lossless coding
     */
    public record Params(int width, int height, int[] precision, int[] sx, int[] sy, int nlx, int nly, int sd,
                         int colorTransform, int cf, int e1, int e2, int[] registrationX, int[] registrationY,
                         int profile, int level, int bw, int fq, int br, boolean uniform, int runMode,
                         boolean longHeaders, boolean rawPerPacket, int sliceLines, int cw, long budget) {
    }

    /**
     * Encodes one codestream.
     *
     * @param params the coding parameters
     * @param samples the samples of each component, unsigned 16-bit values; for a Star-Tetrix codestream the four
     * quad planes in the CFA component order of the decoder
     * @return the codestream
     * @throws MediaException.Unsupported if the parameters give a layout the codestream syntax cannot carry
     */
    public static byte[] encode(Params params, short[][] samples) {
        var components = params.precision().length;
        var layout = new JxsLayout(components, params.width(), params.height(), params.nlx(), params.nly(),
                params.sd(), params.sx(), params.sy(), params.cw(), params.sliceLines() >> params.nly(),
                params.longHeaders());
        var weights = JxsWeights.of(layout);
        var planes = new int[components][];
        ParallelBands.run(components, c -> {
            var count = layout.componentWidth[c] * layout.componentHeight[c];
            planes[c] = new int[count];
            JxsTransforms.input(samples[c], count, params.precision()[c], params.bw(), planes[c]);
        });
        if (params.colorTransform() == 1) {
            JxsTransforms.forwardRct(planes, layout.componentWidth[0] * layout.componentHeight[0]);
        } else if (params.colorTransform() == 3) {
            var type = params.registrationX()[1] == 32768 && params.registrationY()[1] == 0 ? 0 : 1;
            JxsTransforms.forwardStarTetrix(planes, layout.componentWidth[0], layout.componentHeight[0], params.cf(),
                    type, params.e1(), params.e2());
        }
        for (var c = 0; c < components; c++) {
            if (c < layout.decomposed) {
                JxsWavelet.forward(planes[c], layout, c);
            }
            var plane = planes[c];
            var fq = params.fq();
            if (fq > 0) {
                var half = 1 << (fq - 1);
                for (var i = 0; i < plane.length; i++) {
                    var v = plane[i];
                    plane[i] = v >= 0 ? (v + half) >> fq : -((-v + half) >> fq);
                }
            }
        }
        var header = header(params, layout, weights);
        var lossless = params.budget() < 0;
        var precincts = (long) layout.precinctRows * layout.precinctColumns;
        var available = lossless ? Long.MAX_VALUE : params.budget() - header.length - 2 - 6L * layout.slices;
        var share = lossless ? Long.MAX_VALUE : Math.max(0, available / precincts);
        var longHeaders = params.longHeaders() || !layout.shortHeaders;
        while (true) {
            var useLong = longHeaders;
            var parts = new byte[layout.slices][];
            var overflow = new boolean[1];
            var slices = layout.slices;
            var bands = ParallelBands.bands(slices, (long) params.width() * params.height() * components);
            ParallelBands.run(bands, band -> {
                var from = ParallelBands.start(band, bands, slices);
                var to = ParallelBands.start(band + 1, bands, slices);
                var coder = new SliceCoder(params, layout, planes, weights, useLong, share, lossless);
                for (var s = from; s < to; s++) {
                    parts[s] = coder.slice(s);
                    if (coder.overflow) {
                        overflow[0] = true;
                    }
                }
            });
            if (overflow[0] && !useLong) {
                longHeaders = true;
                continue;
            }
            if (overflow[0]) {
                throw new MediaException.Unsupported("JPEG XS", "a JPEG XS packet exceeds the largest packet size");
            }
            var size = (long) header.length + 2;
            for (var part : parts) {
                size += part.length;
            }
            if (size > Integer.MAX_VALUE - 16) {
                throw new MediaException.Unsupported("JPEG XS", "the JPEG XS codestream exceeds 2 GiB");
            }
            var out = new byte[(int) size];
            System.arraycopy(header, 0, out, 0, header.length);
            if (longHeaders != params.longHeaders() && !params.longHeaders()) {
                // the picture header was written with Lh = 0: set it now that long headers are needed
                out[headerFlagsOffset(header)] |= (byte) 0x80;
            }
            var position = header.length;
            for (var part : parts) {
                System.arraycopy(part, 0, out, position, part.length);
                position += part.length;
            }
            out[position] = (byte) 0xFF;
            out[position + 1] = 0x11;
            var lcod = size;
            var pih = picturHeaderOffset(header);
            out[pih + 4] = (byte) (lcod >>> 24);
            out[pih + 5] = (byte) (lcod >>> 16);
            out[pih + 6] = (byte) (lcod >>> 8);
            out[pih + 7] = (byte) lcod;
            return out;
        }
    }

    private static int picturHeaderOffset(byte[] header) {
        var cap = (header[4] & 0xFF) << 8 | header[5] & 0xFF;
        return 4 + cap;
    }

    private static int headerFlagsOffset(byte[] header) {
        return picturHeaderOffset(header) + 27;
    }

    /**
     * Writes the markers from {@code SOC} to the last one before the first slice.
     */
    private static byte[] header(Params p, JxsLayout layout, int[][] weights) {
        var out = new JxsBitWriter(256);
        var components = p.precision().length;
        out.write(JxsHeader.SOC, 16);
        var caps = new boolean[16];
        caps[1] = p.colorTransform() == 3;
        var anySy = false;
        for (var s : p.sy()) {
            anySy |= s == 2;
        }
        caps[4] = anySy;
        caps[5] = p.sd() > 0;
        caps[6] = p.fq() == 0;
        caps[8] = p.rawPerPacket();
        var last = -1;
        for (var i = 0; i < caps.length; i++) {
            if (caps[i]) {
                last = i;
            }
        }
        var capBytes = (last + 8) / 8;
        out.write(JxsHeader.CAP, 16);
        out.write(2 + capBytes, 16);
        for (var i = 0; i < capBytes * 8; i++) {
            out.write(i < caps.length && caps[i] ? 1 : 0, 1);
        }
        out.write(JxsHeader.PIH, 16);
        out.write(26, 16);
        out.write(0, 32);
        out.write(p.profile(), 16);
        out.write(p.level(), 16);
        out.write(p.width(), 16);
        out.write(p.height(), 16);
        out.write(p.cw(), 16);
        out.write(p.sliceLines() >> p.nly(), 16);
        out.write(components, 8);
        out.write(4, 8);
        out.write(8, 8);
        out.write(p.bw(), 8);
        out.write(p.fq(), 4);
        out.write(p.br(), 4);
        out.write(0, 1);
        out.write(0, 3);
        out.write(p.colorTransform(), 4);
        out.write(p.nlx(), 4);
        out.write(p.nly(), 4);
        out.write(p.longHeaders() ? 1 : 0, 1);
        out.write(p.rawPerPacket() ? 1 : 0, 1);
        out.write(p.uniform() ? 1 : 0, 2);
        out.write(0, 2);
        out.write(p.runMode(), 2);
        out.write(JxsHeader.CDT, 16);
        out.write(2 + 2 * components, 16);
        for (var c = 0; c < components; c++) {
            out.write(p.precision()[c], 8);
            out.write(p.sx()[c], 4);
            out.write(p.sy()[c], 4);
        }
        out.write(JxsHeader.WGT, 16);
        out.write(2 + 2 * layout.existingBands, 16);
        for (var b = 0; b < layout.existingBands; b++) {
            out.write(weights[0][b], 8);
            out.write(weights[1][b], 8);
        }
        if (p.sd() > 0) {
            out.write(JxsHeader.CWD, 16);
            out.write(3, 16);
            out.write(p.sd(), 8);
        }
        if (p.colorTransform() == 3) {
            out.write(JxsHeader.CTS, 16);
            out.write(4, 16);
            out.write(0, 4);
            out.write(p.cf(), 4);
            out.write(p.e1(), 4);
            out.write(p.e2(), 4);
            out.write(JxsHeader.CRG, 16);
            out.write(2 + 4 * components, 16);
            for (var c = 0; c < components; c++) {
                out.write(p.registrationX()[c], 16);
                out.write(p.registrationY()[c], 16);
            }
        }
        return out.toByteArray();
    }

    /**
     * Codes the slices of one band of slices, reusing its buffers.
     */
    private static final class SliceCoder {
        private final Params params;
        private final JxsLayout layout;
        private final int[][] planes;
        private final int[][] gains;
        private final int[][] priorities;
        private final boolean longHeaders;
        private final long share;
        private final boolean lossless;
        private final int maxRaw;
        private boolean overflow;
        /**
         * The bit-plane count of each group of each band line of the current precinct, {@code [c][b][line * groups +
         * i]}.
         */
        private final byte[][][] gcli;
        /**
         * The coded bit-plane counts of the precinct above, per column, {@code [column][c][b][line * groups + i]}.
         */
        private final byte[][][][] coded;
        private final int[][][] codedGtli;
        private final int[][] gtli;
        private final int[] mode;

        SliceCoder(Params params, JxsLayout layout, int[][] planes, int[][] weights, boolean longHeaders, long share,
                   boolean lossless) {
            this.params = params;
            this.layout = layout;
            this.planes = planes;
            this.longHeaders = longHeaders;
            this.share = share;
            this.lossless = lossless;
            this.maxRaw = (1 << params.br()) - 1;
            gains = new int[layout.components][];
            priorities = new int[layout.components][];
            gcli = new byte[layout.components][][];
            gtli = new int[layout.components][];
            coded = new byte[layout.precinctColumns][layout.components][][];
            codedGtli = new int[layout.precinctColumns][layout.components][];
            for (var c = 0; c < layout.components; c++) {
                var bands = layout.bandCount[c];
                gains[c] = new int[bands];
                priorities[c] = new int[bands];
                gcli[c] = new byte[bands][];
                gtli[c] = new int[bands];
                for (var col = 0; col < layout.precinctColumns; col++) {
                    coded[col][c] = new byte[bands][];
                }
                for (var b = 0; b < bands; b++) {
                    var width = Math.max(layout.precinctBandWidth[c][b], layout.lastPrecinctBandWidth[c][b]);
                    var size = ((width + 3) >>> 2) * layout.bandLines[c][b];
                    gcli[c][b] = new byte[size];
                    for (var col = 0; col < layout.precinctColumns; col++) {
                        coded[col][c][b] = new byte[size];
                    }
                }
                for (var col = 0; col < layout.precinctColumns; col++) {
                    codedGtli[col][c] = new int[bands];
                }
            }
            var index = 0;
            for (var g = 0; g < layout.globalBands; g++) {
                var c = layout.globalComponent[g];
                if (c >= 0) {
                    gains[c][layout.globalBand[g]] = weights[0][index];
                    priorities[c][layout.globalBand[g]] = weights[1][index];
                    index++;
                }
            }
            mode = new int[layout.globalBands];
        }

        byte[] slice(int slice) {
            var out = new JxsBitWriter(1 << 16);
            out.write(JxsHeader.SLH, 16);
            out.write(4, 16);
            out.write(slice, 16);
            var rows = JxsSliceDecoder.rowsOf(layout, slice);
            var carry = 0L;
            for (var line = 0; line < rows; line++) {
                var row = slice * layout.rowsPerSlice + line;
                for (var column = 0; column < layout.precinctColumns; column++) {
                    var budget = lossless ? Long.MAX_VALUE : share + carry;
                    var written = precinct(out, row, column, line == 0, budget);
                    if (!lossless) {
                        carry = Math.max(0, budget - written);
                    }
                }
            }
            return out.toByteArray();
        }

        private boolean lastRow(int row) {
            return row == layout.precinctRows - 1;
        }

        private int lines(int c, int b, int row) {
            return lastRow(row) ? layout.lastPrecinctBandLines[c][b] : layout.bandLines[c][b];
        }

        private int width(int c, int b, int column) {
            return column == layout.precinctColumns - 1 ? layout.lastPrecinctBandWidth[c][b]
                    : layout.precinctBandWidth[c][b];
        }

        private int base(int c, int b, int row, int column, int line) {
            var y = layout.bandY[c][b] + row * layout.bandLines[c][b] + line;
            var x = layout.bandX[c][b] + column * layout.precinctBandWidth[c][b];
            return y * layout.componentWidth[c] + x;
        }

        /**
         * Codes one precinct within {@code budget} bytes where possible and returns the bytes written.
         */
        private long precinct(JxsBitWriter out, int row, int column, boolean first, long budget) {
            for (var c = 0; c < layout.components; c++) {
                for (var b = 0; b < layout.bandCount[c]; b++) {
                    var width = width(c, b, column);
                    var groups = (width + 3) >>> 2;
                    var counts = gcli[c][b];
                    var plane = planes[c];
                    for (var line = 0; line < lines(c, b, row); line++) {
                        var base = base(c, b, row, column, line);
                        for (var g = 0; g < groups; g++) {
                            var max = 0;
                            var end = Math.min(width, g * 4 + 4);
                            for (var i = g * 4; i < end; i++) {
                                max |= Math.abs(plane[base + i]);
                            }
                            counts[line * groups + g] = (byte) (32 - Integer.numberOfLeadingZeros(max));
                        }
                    }
                }
            }
            var bandsTotal = layout.existingBands;
            int quantization;
            int refinement;
            if (lossless) {
                quantization = 0;
                refinement = 0;
            } else {
                var maxGain = 0;
                for (var c = 0; c < layout.components; c++) {
                    for (var g : gains[c]) {
                        maxGain = Math.max(maxGain, g);
                    }
                }
                var steps = (maxGain + 17) * (bandsTotal + 1);
                var low = 0;
                var high = steps - 1;
                while (low < high) {
                    var mid = (low + high) >>> 1;
                    var q = mid / (bandsTotal + 1);
                    var r = bandsTotal - mid % (bandsTotal + 1);
                    if (size(row, column, first, q, r) <= budget) {
                        high = mid;
                    } else {
                        low = mid + 1;
                    }
                }
                quantization = low / (bandsTotal + 1);
                refinement = bandsTotal - low % (bandsTotal + 1);
            }
            return write(out, row, column, first, quantization, refinement);
        }

        private void truncations(int quantization, int refinement) {
            for (var c = 0; c < layout.components; c++) {
                for (var b = 0; b < layout.bandCount[c]; b++) {
                    gtli[c][b] = JxsSliceDecoder.truncation(gains[c][b], priorities[c][b], quantization, refinement);
                }
            }
        }

        /**
         * Computes the size in bytes of a precinct coded with {@code quantization} and {@code refinement}.
         */
        private long size(int row, int column, boolean first, int quantization, int refinement) {
            truncations(quantization, refinement);
            chooseModes(row, column, first);
            long total = 5 + (2L * layout.existingBands + 7) / 8;
            var headerBytes = longHeaders ? 7 : 5;
            for (var p = 0; p < layout.packets; p++) {
                var line = layout.packetLine[p];
                var present = false;
                long sigBits = 0;
                long vlcBits = 0;
                long rawBits = 0;
                long dataBits = 0;
                var rawPossible = params.rawPerPacket();
                for (var g : layout.packetBands[p]) {
                    var c = layout.globalComponent[g];
                    var b = layout.globalBand[g];
                    if (line >= lines(c, b, row)) {
                        continue;
                    }
                    present = true;
                    var groups = (width(c, b, column) + 3) >>> 2;
                    var t = gtli[c][b];
                    var counts = gcli[c][b];
                    for (var i = 0; i < groups; i++) {
                        var planesCoded = counts[line * groups + i] - t;
                        if (planesCoded > 0) {
                            dataBits += 4L * planesCoded + 4;
                        }
                        if (Math.max(counts[line * groups + i], t) > maxRaw) {
                            rawPossible = false;
                        }
                    }
                    rawBits += (long) groups * params.br();
                    var cost = lineCost(c, b, line, row, column, mode[g]);
                    vlcBits += cost & 0xFFFFFFFFL;
                    sigBits += cost >>> 32;
                }
                if (!present) {
                    continue;
                }
                var gcliBytes = (sigBits + 7) / 8 + (vlcBits + 7) / 8;
                if (rawPossible && (rawBits + 7) / 8 < gcliBytes) {
                    gcliBytes = (rawBits + 7) / 8;
                }
                total += headerBytes + gcliBytes + (dataBits + 7) / 8;
            }
            return total;
        }

        /**
         * Chooses the coding mode of every band for the current truncations.
         */
        private void chooseModes(int row, int column, boolean first) {
            for (var g = 0; g < layout.globalBands; g++) {
                var c = layout.globalComponent[g];
                if (c < 0) {
                    continue;
                }
                var b = layout.globalBand[g];
                var best = -1;
                var bestBits = Long.MAX_VALUE;
                for (var m = 0; m < 4; m++) {
                    if (first && (m & VERTICAL) != 0) {
                        continue;
                    }
                    long bits = 0;
                    for (var line = 0; line < lines(c, b, row); line++) {
                        var cost = lineCost(c, b, line, row, column, m);
                        bits += (cost & 0xFFFFFFFFL) + (cost >>> 32);
                    }
                    if (bits < bestBits) {
                        bestBits = bits;
                        best = m;
                    }
                }
                mode[g] = best;
            }
        }

        /**
         * Returns the bits of the bit-plane counts of a band line coded in {@code mode}: the significance flag bits in
         * the upper 32 bits and the variable-length code bits in the lower.
         */
        private long lineCost(int c, int b, int line, int row, int column, int mode) {
            var groups = (width(c, b, column) + 3) >>> 2;
            var t = gtli[c][b];
            var counts = gcli[c][b];
            var offset = line * groups;
            long sig = 0;
            long vlc = 0;
            var significance = (mode & SIGNIFICANCE) != 0;
            byte[] above = null;
            var aboveOffset = 0;
            var tt = 0;
            if ((mode & VERTICAL) != 0) {
                if (line == 0) {
                    above = coded[column][c][b];
                    aboveOffset = (layout.bandLines[c][b] - 1) * groups;
                    tt = Math.max(codedGtli[column][c][b], t);
                } else {
                    // the line above in this precinct decodes to max(gcli, t) in every mode
                    above = counts;
                    aboveOffset = (line - 1) * groups;
                    tt = t;
                }
            }
            for (var start = 0; start < groups; start += 8) {
                var end = Math.min(groups, start + 8);
                if (significance) {
                    sig++;
                    var insignificant = true;
                    for (var i = start; i < end && insignificant; i++) {
                        insignificant = counts[offset + i] <= t;
                    }
                    if (insignificant) {
                        continue;
                    }
                }
                for (var i = start; i < end; i++) {
                    var value = Math.max(counts[offset + i], t);
                    if (above == null) {
                        vlc += value - t + 1;
                    } else {
                        vlc += vertical(value, Math.max(above[aboveOffset + i], tt), t) + 1;
                    }
                }
            }
            return sig << 32 | vlc;
        }

        /**
         * Returns the code of a bit-plane count predicted from the one above ({@code m_top}), as the decoder reads it.
         */
        private static int vertical(int value, int top, int t) {
            var delta = value - top;
            var threshold = Math.max(top - t, 0);
            if (delta == 0) {
                return 0;
            }
            if (delta > 0) {
                return delta <= threshold ? 2 * delta : delta + threshold;
            }
            return -2 * delta - 1;
        }

        /**
         * Writes a precinct and returns its size in bytes.
         */
        private long write(JxsBitWriter out, int row, int column, boolean first, int quantization, int refinement) {
            truncations(quantization, refinement);
            chooseModes(row, column, first);
            var start = out.size();
            out.write(0, 24);
            out.write(quantization, 8);
            out.write(refinement, 8);
            for (var g = 0; g < layout.globalBands; g++) {
                if (layout.globalComponent[g] >= 0) {
                    out.write(mode[g], 2);
                }
            }
            out.align();
            var body = out.size();
            var headerBytes = longHeaders ? 7 : 5;
            var data = new JxsBitWriter(1 << 12);
            var counts = new JxsBitWriter(1 << 10);
            var flags = new JxsBitWriter(1 << 8);
            for (var p = 0; p < layout.packets; p++) {
                var line = layout.packetLine[p];
                var present = false;
                var rawPossible = params.rawPerPacket();
                long rawBits = 0;
                for (var g : layout.packetBands[p]) {
                    var c = layout.globalComponent[g];
                    var b = layout.globalBand[g];
                    if (line >= lines(c, b, row)) {
                        continue;
                    }
                    present = true;
                    var groups = (width(c, b, column) + 3) >>> 2;
                    rawBits += (long) groups * params.br();
                    for (var i = 0; i < groups; i++) {
                        if (Math.max(gcli[c][b][line * groups + i], gtli[c][b]) > maxRaw) {
                            rawPossible = false;
                        }
                    }
                }
                if (!present) {
                    continue;
                }
                flags.truncate(0);
                counts.truncate(0);
                for (var g : layout.packetBands[p]) {
                    var c = layout.globalComponent[g];
                    var b = layout.globalBand[g];
                    if (line < lines(c, b, row)) {
                        writeCounts(flags, counts, c, b, line, row, column, mode[g]);
                    }
                }
                flags.align();
                counts.align();
                var raw = rawPossible && (rawBits + 7) / 8 < flags.size() + counts.size();
                if (raw) {
                    flags.truncate(0);
                    counts.truncate(0);
                    for (var g : layout.packetBands[p]) {
                        var c = layout.globalComponent[g];
                        var b = layout.globalBand[g];
                        if (line < lines(c, b, row)) {
                            var groups = (width(c, b, column) + 3) >>> 2;
                            for (var i = 0; i < groups; i++) {
                                counts.write(Math.max(gcli[c][b][line * groups + i], gtli[c][b]), params.br());
                            }
                        }
                    }
                    counts.align();
                }
                data.truncate(0);
                for (var g : layout.packetBands[p]) {
                    var c = layout.globalComponent[g];
                    var b = layout.globalBand[g];
                    if (line < lines(c, b, row)) {
                        writeData(data, c, b, line, row, column);
                    }
                }
                data.align();
                var dataLength = data.size();
                var gcliLength = counts.size();
                if (longHeaders) {
                    if (dataLength >= 1 << 20 || gcliLength >= 1 << 20) {
                        overflow = true;
                    }
                    out.write(raw ? 1 : 0, 1);
                    out.write(dataLength, 20);
                    out.write(gcliLength, 20);
                    out.write(0, 15);
                } else {
                    if (dataLength >= 1 << 15 || gcliLength >= 1 << 13) {
                        overflow = true;
                    }
                    out.write(raw ? 1 : 0, 1);
                    out.write(dataLength, 15);
                    out.write(gcliLength, 13);
                    out.write(0, 11);
                }
                out.bytes(flags.array(), 0, flags.size());
                out.bytes(counts.array(), 0, counts.size());
                out.bytes(data.array(), 0, data.size());
            }
            var length = out.size() - body;
            if (length >= 1 << 20) {
                overflow = true;
            }
            out.set(start, length >>> 16);
            out.set(start + 1, length >>> 8);
            out.set(start + 2, length);
            for (var c = 0; c < layout.components; c++) {
                for (var b = 0; b < layout.bandCount[c]; b++) {
                    var t = gtli[c][b];
                    var source = gcli[c][b];
                    var target = coded[column][c][b];
                    var count = ((width(c, b, column) + 3) >>> 2) * lines(c, b, row);
                    for (var i = 0; i < count; i++) {
                        target[i] = (byte) Math.max(source[i], t);
                    }
                    codedGtli[column][c][b] = t;
                }
            }
            return out.size() - start;
        }

        private void writeCounts(JxsBitWriter flags, JxsBitWriter counts, int c, int b, int line, int row, int column,
                                 int mode) {
            var groups = (width(c, b, column) + 3) >>> 2;
            var t = gtli[c][b];
            var gc = gcli[c][b];
            var offset = line * groups;
            var significance = (mode & SIGNIFICANCE) != 0;
            byte[] above = null;
            var aboveOffset = 0;
            var tt = 0;
            if ((mode & VERTICAL) != 0) {
                if (line == 0) {
                    above = coded[column][c][b];
                    aboveOffset = (layout.bandLines[c][b] - 1) * groups;
                    tt = Math.max(codedGtli[column][c][b], t);
                } else {
                    above = gc;
                    aboveOffset = (line - 1) * groups;
                    tt = t;
                }
            }
            var insignificant = new boolean[(groups + 7) >>> 3];
            if (significance) {
                for (var s = 0; s < insignificant.length; s++) {
                    var all = true;
                    for (var i = s * 8; i < Math.min(groups, s * 8 + 8) && all; i++) {
                        all = gc[offset + i] <= t;
                    }
                    insignificant[s] = all;
                    flags.write(all ? 1 : 0, 1);
                }
            }
            for (var i = 0; i < groups; i++) {
                if (significance && insignificant[i >>> 3]) {
                    continue;
                }
                var value = Math.max(gc[offset + i], t);
                if (above == null) {
                    counts.unary(value - t);
                } else {
                    counts.unary(vertical(value, Math.max(above[aboveOffset + i], tt), t));
                }
            }
        }

        private void writeData(JxsBitWriter data, int c, int b, int line, int row, int column) {
            var width = width(c, b, column);
            var groups = (width + 3) >>> 2;
            var t = gtli[c][b];
            var gc = gcli[c][b];
            var plane = planes[c];
            var base = base(c, b, row, column, line);
            var uniform = params.uniform();
            var q = new long[4];
            for (var group = 0; group < groups; group++) {
                var g = gc[line * groups + group];
                var planesCoded = g - t;
                if (planesCoded <= 0) {
                    continue;
                }
                var signs = 0;
                for (var i = 0; i < 4; i++) {
                    var index = group * 4 + i;
                    var v = index < width ? plane[base + index] : 0;
                    if (v < 0) {
                        signs |= 8 >>> i;
                    }
                    long m = Math.abs(v);
                    if (uniform) {
                        var s = g - t + 1;
                        q[i] = ((m << s) - m + (1L << g)) >> (g + 1);
                    } else {
                        q[i] = m >> t;
                    }
                }
                data.write(signs, 4);
                for (var k = planesCoded - 1; k >= 0; k--) {
                    var nibble = (int) ((q[0] >>> k & 1) << 3 | (q[1] >>> k & 1) << 2 | (q[2] >>> k & 1) << 1
                            | q[3] >>> k & 1);
                    data.write(nibble, 4);
                }
            }
        }
    }

    /**
     * Returns the smallest level of ISO/IEC 21122-2 whose picture size limits admit a {@code width x height}
     * picture, or 0 (unrestricted) when none does.
     *
     * @param width the width of the sampling grid
     * @param height the height of the sampling grid
     * @return the level bits of {@code Plev}
     */
    public static int level(int width, int height) {
        int[][] levels = {
                {0x0400, 1280, 5120, 26214400}, {0x1000, 2048, 8192, 4194304}, {0x2000, 4096, 16384, 8912896},
                {0x2400, 4096, 16384, 16777216}, {0x3000, 8192, 32768, 35651584},
                {0x3400, 8192, 32768, 67108864}, {0x4000, 10240, 40960, 104857600}
        };
        var samples = (long) width * height;
        for (var level : levels) {
            if (width <= level[1] && height <= level[2] && samples <= level[3]) {
                return level[0];
            }
        }
        return 0;
    }

    /**
     * Returns the smallest sublevel of ISO/IEC 21122-2 that admits {@code bitsPerPixel}, or the full sublevel above
     * 12 bits per pixel.
     *
     * @param bitsPerPixel the coded bits per pixel of the sampling grid
     * @return the sublevel bits of {@code Plev}
     */
    public static int sublevel(double bitsPerPixel) {
        int[][] sublevels = {{0x0003, 2}, {0x0004, 3}, {0x0006, 4}, {0x0008, 6}, {0x000C, 9}, {0x0010, 12}};
        for (var sublevel : sublevels) {
            if (bitsPerPixel <= sublevel[1]) {
                return sublevel[0];
            }
        }
        return 0x0080;
    }

}
