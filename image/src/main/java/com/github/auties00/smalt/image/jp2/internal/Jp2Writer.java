/* Ports OpenJPEG 2.5.3 (j2k.c, jp2.c, tcd.c, dwt.c, mct.c). Copyright (c) 2002-2014, Universite catholique de
   Louvain (UCL), Belgium, and the OpenJPEG authors; BSD 2-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.io.ByteWriter;
import com.github.auties00.smalt.internal.io.FourCc;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Encodes components into a JPEG 2000 codestream (ISO/IEC 15444-1 annex A to F), and wraps a
 * codestream in a JP2 file (annex I).
 *
 * <p>Each tile goes through the DC level shift, the forward component transform, the forward
 * wavelet transform, the code-block coder and the rate allocation, then its packets are written
 * in the configured progression order as one tile-part.
 *
 * <p>Lossless codestreams are byte-identical to {@code opj_compress}'s with the same parameters and
 * comment. Arguments are not checked; values outside the documented ranges give unspecified
 * results.
 *
 * @implNote This implementation follows OpenJPEG's rate allocation ({@code opj_tcd_rateallocate}
 * and {@code opj_tcd_makelayer}): a bisection on the rate-distortion slope threshold against each
 * layer's byte budget or distortion target.
 */
public final class Jp2Writer {
    /**
     * The approximate work of coding one sample, weighed against
     * {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long SAMPLE_WORK = 96;

    /**
     * The norms of the reversible component transform (OpenJPEG's {@code opj_mct_norms}).
     */
    private static final double[] RCT_NORMS = {1.732, .8292, .8292};

    /**
     * The norms of the irreversible component transform (OpenJPEG's {@code opj_mct_norms_real}).
     */
    private static final double[] ICT_NORMS = {1.732, 1.805, 1.573};

    /**
     * Prevents instantiation.
     */
    private Jp2Writer() {
        throw new AssertionError();
    }

    /**
     * Encodes components into a codestream.
     *
     * @param config the encoding parameters
     * @param planes the samples of each component, row-major over its sample grid
     * ({@code ceil(width / dx)} by {@code ceil(height / dy)})
     * @param limits the limits bounding the allocations
     * @return the codestream
     */
    public static byte[] codestream(EncoderConfig config, int[][] planes, MediaLimits limits) {
        var components = config.components();
        var quantizations = new Quantization[components];
        for (var c = 0; c < components; c++) {
            quantizations[c] = stepSizes(config, config.precisions()[c]);
        }
        var coding = coding(config);
        var out = new ByteWriter(4096);
        writeMainHeader(out, config, coding, quantizations);
        var tilesWide = Math.ceilDiv(config.width(), config.tileWidth());
        var tilesHigh = Math.ceilDiv(config.height(), config.tileHeight());
        var tiles = tilesWide * tilesHigh;
        var budgets = budgets(config, out.size(), tiles, tilesWide);
        var bodies = new byte[tiles][];
        for (var t = 0; t < tiles; t++) {
            bodies[t] = encodeTile(config, coding, quantizations, planes, t, tilesWide, budgets[t], limits);
            ParallelBands.checkpoint();
        }
        for (var t = 0; t < tiles; t++) {
            out.u16be(Codestream.SOT);
            out.u16be(10);
            out.u16be(t);
            out.u32be(14 + bodies[t].length);
            out.u8(0);
            out.u8(1);
            out.u16be(Codestream.SOD);
            out.bytes(bodies[t]);
        }
        out.u16be(Codestream.EOC);
        return out.toByteArray();
    }

    /**
     * Wraps a codestream in a JP2 file.
     *
     * @param codestream the codestream bytes
     * @param config the parameters it was encoded with
     * @param colorSpace the enumerated color space, used when {@code icc} is {@code null}
     * @param icc the ICC profile to embed, or {@code null}
     * @param alpha the index of the opacity channel, or {@code -1}
     * @param premultiplied whether the opacity channel is premultiplied opacity
     * @return the JP2 file
     */
    public static byte[] jp2(byte[] codestream, EncoderConfig config, int colorSpace, byte[] icc, int alpha,
                             boolean premultiplied) {
        var out = new ByteWriter(codestream.length + 256);
        out.bytes(Jp2Boxes.SIGNATURE);
        out.begin(FourCc.of("ftyp"));
        out.fourCc("jp2 ");
        out.u32be(0);
        out.fourCc("jp2 ");
        out.end();
        var components = config.components();
        var uniform = true;
        for (var c = 1; c < components; c++) {
            uniform &= config.precisions()[c] == config.precisions()[0] && config.signed()[c] == config.signed()[0];
        }
        out.begin(FourCc.of("jp2h"));
        out.begin(FourCc.of("ihdr"));
        out.u32be(config.height());
        out.u32be(config.width());
        out.u16be(components);
        out.u8(uniform ? depth(config, 0) : 255);
        out.u8(7);
        out.u8(0);
        out.u8(0);
        out.end();
        if (!uniform) {
            out.begin(FourCc.of("bpcc"));
            for (var c = 0; c < components; c++) {
                out.u8(depth(config, c));
            }
            out.end();
        }
        out.begin(FourCc.of("colr"));
        if (icc != null) {
            out.u8(2);
            out.u8(0);
            out.u8(0);
            out.bytes(icc);
        } else {
            out.u8(1);
            out.u8(0);
            out.u8(0);
            out.u32be(colorSpace);
        }
        out.end();
        if (alpha >= 0) {
            out.begin(FourCc.of("cdef"));
            out.u16be(components);
            var color = 1;
            for (var c = 0; c < components; c++) {
                out.u16be(c);
                if (c == alpha) {
                    out.u16be(premultiplied ? 2 : 1);
                    out.u16be(0);
                } else {
                    out.u16be(0);
                    out.u16be(color++);
                }
            }
            out.end();
        }
        out.end();
        out.begin(FourCc.of("jp2c"));
        out.bytes(codestream);
        out.end();
        return out.toByteArray();
    }

    /**
     * Returns the {@code BPC} byte of a component.
     *
     * @param config the parameters
     * @param component the component index
     * @return the precision minus one, with bit 7 set for signed components
     */
    private static int depth(EncoderConfig config, int component) {
        return config.precisions()[component] - 1 + (config.signed()[component] ? 0x80 : 0);
    }

    /**
     * Builds the coding style of every component.
     *
     * @param config the parameters
     * @return the coding style
     */
    private static ComponentCoding coding(EncoderConfig config) {
        var resolutions = config.resolutions();
        var widths = new int[resolutions];
        var heights = new int[resolutions];
        for (var r = 0; r < resolutions; r++) {
            widths[r] = config.precinctWidths() == null ? 15 : config.precinctWidths()[r];
            heights[r] = config.precinctHeights() == null ? 15 : config.precinctHeights()[r];
        }
        return new ComponentCoding(resolutions, config.codeBlockWidth(), config.codeBlockHeight(),
                config.codeBlockStyle(), config.reversible(), widths, heights);
    }

    /**
     * Computes the step sizes of a component (OpenJPEG's {@code opj_dwt_calc_explicit_stepsizes}):
     * no quantization for the 5-3 wavelet, the inverse of each sub-band's 9-7 synthesis norm for
     * the 9-7 one.
     *
     * @param config the parameters
     * @param precision the component precision
     * @return the quantization, with 2 guard bits
     */
    static Quantization stepSizes(EncoderConfig config, int precision) {
        var resolutions = config.resolutions();
        var bands = 3 * resolutions - 2;
        var exponents = new int[Quantization.MAX_BANDS];
        var mantissas = new int[Quantization.MAX_BANDS];
        for (var band = 0; band < bands; band++) {
            var resolution = band == 0 ? 0 : (band - 1) / 3 + 1;
            var orientation = band == 0 ? 0 : (band - 1) % 3 + 1;
            var level = resolutions - 1 - resolution;
            var gain = !config.reversible() ? 0 : orientation == 0 ? 0 : orientation == 3 ? 2 : 1;
            var stepSize = config.reversible() ? 1.0 : (1 << gain) / Tier1Encoder.norm(level, orientation, false);
            var value = (int) Math.floor(stepSize * 8192.0);
            var log = 31 - Integer.numberOfLeadingZeros(value);
            var p = log - 13;
            var n = 11 - log;
            mantissas[band] = (n < 0 ? value >> -n : value << n) & 0x7FF;
            exponents[band] = precision + gain - p;
        }
        return new Quantization(config.reversible() ? Quantization.NONE : Quantization.SCALAR_EXPOUNDED, 2,
                exponents, mantissas);
    }

    /**
     * Writes the main header: {@code SOC}, {@code SIZ}, {@code COD}, {@code QCD}, a {@code QCC}
     * for each component quantized differently from the first, and {@code COM}.
     *
     * @param out the stream
     * @param config the parameters
     * @param coding the coding style
     * @param quantizations the quantization of each component
     */
    private static void writeMainHeader(ByteWriter out, EncoderConfig config, ComponentCoding coding,
                                        Quantization[] quantizations) {
        var components = config.components();
        out.u16be(Codestream.SOC);
        out.u16be(Codestream.SIZ);
        out.u16be(38 + 3 * components);
        out.u16be(0);
        out.u32be(config.width());
        out.u32be(config.height());
        out.u32be(0);
        out.u32be(0);
        out.u32be(config.tileWidth());
        out.u32be(config.tileHeight());
        out.u32be(0);
        out.u32be(0);
        out.u16be(components);
        for (var c = 0; c < components; c++) {
            out.u8(depth(config, c));
            out.u8(config.subsamplingX()[c]);
            out.u8(config.subsamplingY()[c]);
        }
        var precincts = config.precinctWidths() != null;
        var flags = (precincts ? 1 : 0) | (config.sop() ? 2 : 0) | (config.eph() ? 4 : 0);
        out.u16be(Codestream.COD);
        out.u16be(12 + (precincts ? config.resolutions() : 0));
        out.u8(flags);
        out.u8(config.progression());
        out.u16be(config.layers());
        out.u8(config.transform() ? 1 : 0);
        writeCoding(out, coding, precincts);
        out.u16be(Codestream.QCD);
        out.u16be(2 + quantizationLength(quantizations[0], config.resolutions()));
        writeQuantization(out, quantizations[0], config.resolutions());
        var componentBytes = components <= 256 ? 1 : 2;
        for (var c = 1; c < components; c++) {
            if (Arrays.equals(exponents(quantizations[c], config.resolutions()),
                    exponents(quantizations[0], config.resolutions()))) {
                continue;
            }
            out.u16be(Codestream.QCC);
            out.u16be(2 + componentBytes + quantizationLength(quantizations[c], config.resolutions()));
            if (componentBytes == 1) {
                out.u8(c);
            } else {
                out.u16be(c);
            }
            writeQuantization(out, quantizations[c], config.resolutions());
        }
        if (config.comment() != null) {
            var text = config.comment().getBytes(StandardCharsets.ISO_8859_1);
            out.u16be(Codestream.COM);
            out.u16be(4 + text.length);
            out.u16be(1);
            out.bytes(text);
        }
    }

    /**
     * Returns the exponents and mantissas of a quantization, for comparison.
     *
     * @param quantization the step sizes and quantization style
     * @param resolutions the number of resolutions
     * @return the step sizes of its bands
     */
    private static int[] exponents(Quantization quantization, int resolutions) {
        var bands = 3 * resolutions - 2;
        var values = new int[2 * bands];
        for (var b = 0; b < bands; b++) {
            values[2 * b] = quantization.exponent(b);
            values[2 * b + 1] = quantization.mantissa(b);
        }
        return values;
    }

    /**
     * Writes the {@code SPcod} fields.
     *
     * @param out the stream
     * @param coding the coding style
     * @param precincts whether precinct sizes are written
     */
    private static void writeCoding(ByteWriter out, ComponentCoding coding, boolean precincts) {
        out.u8(coding.resolutions() - 1);
        out.u8(coding.codeBlockWidth() - 2);
        out.u8(coding.codeBlockHeight() - 2);
        out.u8(coding.codeBlockStyle());
        out.u8(coding.reversible() ? 1 : 0);
        if (precincts) {
            for (var r = 0; r < coding.resolutions(); r++) {
                out.u8(coding.precinctWidth(r) + (coding.precinctHeight(r) << 4));
            }
        }
    }

    /**
     * Returns the length of the quantization fields.
     *
     * @param quantization the step sizes and quantization style
     * @param resolutions the number of resolutions
     * @return the length in bytes
     */
    private static int quantizationLength(Quantization quantization, int resolutions) {
        var bands = 3 * resolutions - 2;
        return 1 + (quantization.style() == Quantization.NONE ? bands : 2 * bands);
    }

    /**
     * Writes the {@code Sqcx} and {@code SPqcx} fields.
     *
     * @param out the stream
     * @param quantization the step sizes and quantization style
     * @param resolutions the number of resolutions
     */
    private static void writeQuantization(ByteWriter out, Quantization quantization, int resolutions) {
        var bands = 3 * resolutions - 2;
        out.u8(quantization.style() + (quantization.guardBits() << 5));
        for (var b = 0; b < bands; b++) {
            if (quantization.style() == Quantization.NONE) {
                out.u8(quantization.exponent(b) << 3);
            } else {
                out.u16be((quantization.exponent(b) << 11) + quantization.mantissa(b));
            }
        }
    }

    /**
     * Computes the byte budget of each layer of each tile (OpenJPEG's
     * {@code opj_j2k_update_rates}).
     *
     * @param config the parameters
     * @param header the length of the main header
     * @param tiles the number of tiles
     * @param tilesWide the number of tile columns
     * @return the budget of each tile and layer, 0 for unconstrained layers
     */
    private static float[][] budgets(EncoderConfig config, int header, int tiles, int tilesWide) {
        var layers = config.layers();
        var budgets = new float[tiles][layers];
        if (config.ratios() == null) {
            return budgets;
        }
        var bitsEmpty = 8 * config.subsamplingX()[0] * config.subsamplingY()[0];
        var sizePixel = config.components() * config.precisions()[0];
        var remove = (float) header / (float) tiles;
        for (var t = 0; t < tiles; t++) {
            var x0 = (t % tilesWide) * config.tileWidth();
            var y0 = (t / tilesWide) * config.tileHeight();
            var x1 = Math.min(x0 + config.tileWidth(), config.width());
            var y1 = Math.min(y0 + config.tileHeight(), config.height());
            var rates = budgets[t];
            for (var k = 0; k < layers; k++) {
                var ratio = (float) config.ratios()[k];
                if (ratio > 0.0f) {
                    rates[k] = (float) (((double) sizePixel * (x1 - x0) * (y1 - y0)) / (ratio * (float) bitsEmpty));
                }
            }
            if (rates[0] > 0.0f) {
                rates[0] -= remove;
                if (rates[0] < 30.0f) {
                    rates[0] = 30.0f;
                }
            }
            for (var k = 1; k < layers - 1; k++) {
                if (rates[k] > 0.0f) {
                    rates[k] -= remove;
                    if (rates[k] < rates[k - 1] + 10.0f) {
                        rates[k] = rates[k - 1] + 20.0f;
                    }
                }
            }
            if (layers > 1 && rates[layers - 1] > 0.0f) {
                rates[layers - 1] -= remove + 2.0f;
                if (rates[layers - 1] < rates[layers - 2] + 10.0f) {
                    rates[layers - 1] = rates[layers - 2] + 20.0f;
                }
            }
        }
        return budgets;
    }

    /**
     * Encodes one tile into its packets.
     *
     * @param config the parameters
     * @param coding the coding style
     * @param quantizations the quantization of each component
     * @param planes the component samples
     * @param tile the tile index
     * @param tilesWide the number of tile columns
     * @param budgets the byte budget of each layer
     * @param limits the resource bounds checked before every allocation
     * @return the packets
     */
    private static byte[] encodeTile(EncoderConfig config, ComponentCoding coding, Quantization[] quantizations,
                                     int[][] planes, int tile, int tilesWide, float[] budgets, MediaLimits limits) {
        var count = config.components();
        var bounds = new int[]{
                (tile % tilesWide) * config.tileWidth(), (tile / tilesWide) * config.tileHeight(), 0, 0
        };
        bounds[2] = Math.min(bounds[0] + config.tileWidth(), config.width());
        bounds[3] = Math.min(bounds[1] + config.tileHeight(), config.height());
        var components = new TileComponent[count];
        var integers = new int[count][];
        var floats = new float[count][];
        for (var c = 0; c < count; c++) {
            var dx = config.subsamplingX()[c];
            var dy = config.subsamplingY()[c];
            var cx0 = Math.ceilDiv(bounds[0], dx);
            var cy0 = Math.ceilDiv(bounds[1], dy);
            var cx1 = Math.ceilDiv(bounds[2], dx);
            var cy1 = Math.ceilDiv(bounds[3], dy);
            components[c] = new TileComponent(cx0, cy0, cx1, cy1, coding, quantizations[c], config.precisions()[c],
                    coding.resolutions(), true, limits);
            var width = cx1 - cx0;
            var height = cy1 - cy0;
            LimitChecks.checkAllocation(limits, (long) width * height * 4);
            var planeWidth = Math.ceilDiv(config.width(), dx);
            var shift = config.signed()[c] ? 0 : 1 << (config.precisions()[c] - 1);
            var plane = planes[c];
            if (config.reversible()) {
                var samples = integers[c] = new int[width * height];
                for (var y = 0; y < height; y++) {
                    var source = (cy0 + y) * planeWidth + cx0;
                    for (var x = 0; x < width; x++) {
                        samples[y * width + x] = plane[source + x] - shift;
                    }
                }
            } else {
                var samples = floats[c] = new float[width * height];
                for (var y = 0; y < height; y++) {
                    var source = (cy0 + y) * planeWidth + cx0;
                    for (var x = 0; x < width; x++) {
                        samples[y * width + x] = (float) (plane[source + x] - shift);
                    }
                }
            }
        }
        var transform = config.transform() && count >= 3;
        if (transform) {
            forwardTransform(integers, floats, config.reversible());
        }
        for (var c = 0; c < count; c++) {
            var stride = components[c].x1 - components[c].x0;
            if (stride == 0 || components[c].y1 == components[c].y0) {
                continue;
            }
            if (config.reversible()) {
                Wavelet.forward53(integers[c], stride, components[c]);
            } else {
                Wavelet.forward97(floats[c], stride, components[c]);
            }
        }
        var blocks = encodeBlocks(config, components, integers, floats, transform);
        var distortion = 0.0;
        for (var value : blocks) {
            distortion += value;
        }
        var layers = config.layers();
        for (var component : components) {
            forEachBlock(component, (band, block) -> block.ensureLayers(layers));
        }
        allocate(config, components, bounds, budgets, distortion, limits);
        var out = new ByteArrayOutputStream();
        var packets = new PacketEncoder(config.sop(), config.eph());
        var order = new PacketOrder(bounds, components, config.subsamplingX(), config.subsamplingY(), layers, false,
                limits);
        order.list(new ProgressionChange(0, 0, layers, ComponentCoding.MAX_RESOLUTIONS, count, config.progression()),
                (layer, resolution, component, precinct) -> {
                    packets.write(components[component], resolution, precinct, layer, out);
                    return true;
                });
        return out.toByteArray();
    }

    /**
     * Applies the forward multiple component transformation to the first three components.
     *
     * @param integers the integer samples of the reversible components
     * @param floats the floating-point samples of the irreversible components
     * @param reversible whether the reversible transform is applied
     */
    private static void forwardTransform(int[][] integers, float[][] floats, boolean reversible) {
        if (reversible) {
            var r = integers[0];
            var g = integers[1];
            var b = integers[2];
            for (var i = 0; i < r.length; i++) {
                var red = r[i];
                var green = g[i];
                var blue = b[i];
                r[i] = (red + (green * 2) + blue) >> 2;
                g[i] = blue - green;
                b[i] = red - green;
            }
        } else {
            var r = floats[0];
            var g = floats[1];
            var b = floats[2];
            for (var i = 0; i < r.length; i++) {
                var red = r[i];
                var green = g[i];
                var blue = b[i];
                r[i] = 0.299f * red + 0.587f * green + 0.114f * blue;
                g[i] = -0.16875f * red - 0.331260f * green + 0.5f * blue;
                b[i] = 0.5f * red - 0.41869f * green - 0.08131f * blue;
            }
        }
    }

    /**
     * Visits every code-block of the non-empty sub-bands of a tile-component.
     */
    @FunctionalInterface
    private interface BlockVisitor {
        /**
         * Receives one code-block.
         *
         * @param band the sub-band
         * @param block the code-block
         */
        void visit(Band band, EncodedBlock block);
    }

    /**
     * Visits every code-block of a tile-component in codestream order.
     *
     * @param component the tile-component
     * @param visitor the callback
     */
    private static void forEachBlock(TileComponent component, BlockVisitor visitor) {
        for (var level : component.resolutions) {
            for (var band : level.bands()) {
                if (band.empty()) {
                    continue;
                }
                for (var precinct : band.precincts()) {
                    for (var block : precinct.encodedBlocks) {
                        visitor.visit(band, block);
                    }
                }
            }
        }
    }

    /**
     * Codes every code-block of a tile.
     *
     * @param config the parameters
     * @param components the tile-components
     * @param integers the integer coefficients
     * @param floats the floating-point coefficients
     * @param transform whether the component transform was applied
     * @return the weighted distortion reduction of each block, in codestream order
     */
    private static double[] encodeBlocks(EncoderConfig config, TileComponent[] components, int[][] integers,
                                         float[][] floats, boolean transform) {
        var jobs = new ArrayList<Job>();
        var work = 0L;
        for (var c = 0; c < components.length; c++) {
            var component = components[c];
            for (var r = 0; r < component.resolutions.length; r++) {
                for (var band : component.resolutions[r].bands()) {
                    if (band.empty()) {
                        continue;
                    }
                    for (var precinct : band.precincts()) {
                        for (var block : precinct.encodedBlocks) {
                            jobs.add(new Job(c, r, band, block));
                            work += (long) (block.x1 - block.x0) * (block.y1 - block.y0) * SAMPLE_WORK;
                        }
                    }
                }
            }
        }
        var distortions = new double[jobs.size()];
        if (jobs.isEmpty()) {
            return distortions;
        }
        var bands = ParallelBands.bands(jobs.size(), work);
        ParallelBands.run(bands, index -> {
            var coder = new Tier1Encoder();
            var end = ParallelBands.start(index + 1, bands, jobs.size());
            for (var j = ParallelBands.start(index, bands, jobs.size()); j < end; j++) {
                var job = jobs.get(j);
                var c = job.component();
                var r = job.resolution();
                var band = job.band();
                var block = job.block();
                var component = components[c];
                var x = block.x0 - band.x0();
                var y = block.y0 - band.y0();
                if ((band.orientation() & 1) != 0) {
                    x += component.resolutions[r - 1].width();
                }
                if ((band.orientation() & 2) != 0) {
                    y += component.resolutions[r - 1].height();
                }
                var stride = component.x1 - component.x0;
                var weight = transform && c < 3 ? (config.reversible() ? RCT_NORMS : ICT_NORMS)[c] : 1.0;
                distortions[j] = coder.encode(block, integers[c], floats[c], y * stride + x, stride, band.orientation(),
                        component.resolutions.length - 1 - r, band.stepSize(), config.reversible(),
                        config.codeBlockStyle(), weight);
            }
        });
        return distortions;
    }

    /**
     * Allocates the coding passes of every code-block to the quality layers (OpenJPEG's
     * {@code opj_tcd_rateallocate}).
     *
     * @param config the parameters
     * @param components the tile-components
     * @param bounds the tile's area
     * @param budgets the byte budget of each layer
     * @param distortion the tile's total distortion reduction
     * @param limits the resource bounds checked before every allocation
     */
    private static void allocate(EncoderConfig config, TileComponent[] components, int[] bounds, float[] budgets,
                                 double distortion, MediaLimits limits) {
        var min = Double.MAX_VALUE;
        var max = 0.0;
        var maxSquaredError = 0.0;
        for (var c = 0; c < components.length; c++) {
            var pixels = new long[1];
            var slopes = new double[]{min, max};
            forEachBlock(components[c], (band, block) -> {
                for (var pass = 0; pass < block.totalPasses; pass++) {
                    var rate = block.passLength(pass);
                    var gain = block.passDistortion[pass] - (pass == 0 ? 0 : block.passDistortion[pass - 1]);
                    if (rate == 0) {
                        continue;
                    }
                    var slope = gain / rate;
                    slopes[0] = Math.min(slopes[0], slope);
                    slopes[1] = Math.max(slopes[1], slope);
                }
                pixels[0] += (long) (block.x1 - block.x0) * (block.y1 - block.y0);
            });
            min = slopes[0];
            max = slopes[1];
            var peak = (double) (1 << config.precisions()[c]) - 1.0;
            maxSquaredError += peak * peak * (double) pixels[0];
        }
        var layers = config.layers();
        var layerDistortion = new double[layers];
        var cumulative = new double[layers];
        var rateMode = config.ratios() != null;
        for (var layer = 0; layer < layers; layer++) {
            var lo = min;
            var hi = max;
            var maxLength = budgets[layer] > 0.0f ? (long) Math.ceil(budgets[layer]) : Long.MAX_VALUE;
            var quality = rateMode ? 0.0 : config.qualities()[layer];
            var target = distortion - (maxSquaredError / Math.pow(10.0f, (float) quality / 10));
            double threshold;
            if ((rateMode && budgets[layer] > 0.0f) || (!rateMode && quality > 0.0)) {
                var current = 0.0;
                var stable = 0.0;
                var lastFits = false;
                for (var i = 0; i < 128; i++) {
                    var next = (lo + hi) / 2;
                    if (Math.abs(next - current) <= 0.5 * 1e-5 * current) {
                        break;
                    }
                    current = next;
                    var same = makeLayer(components, layer, current, false, layerDistortion) && i != 0;
                    if (!rateMode) {
                        var achieved = layer == 0 ? layerDistortion[0] : cumulative[layer - 1] + layerDistortion[layer];
                        if (achieved < target) {
                            hi = current;
                            stable = current;
                            continue;
                        }
                        lo = current;
                    } else {
                        if ((same && !lastFits) || (!same && measure(config, components, bounds, layer + 1,
                                maxLength, limits) > maxLength)) {
                            lastFits = false;
                            lo = current;
                            continue;
                        }
                        lastFits = true;
                        hi = current;
                        stable = current;
                    }
                }
                threshold = stable == 0 ? current : stable;
            } else {
                threshold = -1;
            }
            makeLayer(components, layer, threshold, true, layerDistortion);
            cumulative[layer] = layer == 0 ? layerDistortion[0] : cumulative[layer - 1] + layerDistortion[layer];
        }
    }

    /**
     * Measures the packets of the first layers of a tile.
     *
     * @param config the parameters
     * @param components the tile-components
     * @param bounds the tile's area
     * @param layers the number of layers to measure
     * @param limit the length beyond which measuring stops
     * @param limits the resource bounds checked before every allocation
     * @return the packets' length, or a value above {@code limit}
     */
    private static long measure(EncoderConfig config, TileComponent[] components, int[] bounds, int layers, long limit,
                                MediaLimits limits) {
        var packets = new PacketEncoder(config.sop(), config.eph());
        var order = new PacketOrder(bounds, components, config.subsamplingX(), config.subsamplingY(), config.layers(),
                false, limits);
        var total = new long[1];
        order.list(new ProgressionChange(0, 0, layers, ComponentCoding.MAX_RESOLUTIONS, components.length,
                config.progression()), (layer, resolution, component, precinct) -> {
            total[0] += packets.write(components[component], resolution, precinct, layer, null);
            return total[0] <= limit;
        });
        return total[0];
    }

    /**
     * Allocates the passes of every code-block whose rate-distortion slope reaches a threshold to
     * a layer (OpenJPEG's {@code opj_tcd_makelayer}).
     *
     * @param components the tile-components
     * @param layer the quality layer, from 0
     * @param threshold the slope threshold, or a negative value to include every pass left
     * @param last whether the allocation is final
     * @param distortion receives the layer's distortion reduction
     * @return whether no block's allocation changed since the previous call
     */
    private static boolean makeLayer(TileComponent[] components, int layer, double threshold, boolean last,
                                     double[] distortion) {
        var same = new boolean[]{true};
        distortion[layer] = 0;
        for (var component : components) {
            forEachBlock(component, (band, block) -> {
                if (layer == 0) {
                    block.passesInLayers = 0;
                }
                var n = block.passesInLayers;
                if (threshold < 0) {
                    n = block.totalPasses;
                } else {
                    for (var pass = block.passesInLayers; pass < block.totalPasses; pass++) {
                        int rate;
                        double gain;
                        if (n == 0) {
                            rate = block.passRate[pass];
                            gain = block.passDistortion[pass];
                        } else {
                            rate = block.passRate[pass] - block.passRate[n - 1];
                            gain = block.passDistortion[pass] - block.passDistortion[n - 1];
                        }
                        if (rate == 0) {
                            if (gain != 0) {
                                n = pass + 1;
                            }
                            continue;
                        }
                        if (threshold - (gain / rate) < Math.ulp(1.0)) {
                            n = pass + 1;
                        }
                    }
                }
                var passes = n - block.passesInLayers;
                if (block.layerPasses[layer] != passes) {
                    same[0] = false;
                    block.layerPasses[layer] = passes;
                }
                if (passes == 0) {
                    block.layerDistortion[layer] = 0;
                    return;
                }
                if (block.passesInLayers == 0) {
                    block.layerLength[layer] = block.passRate[n - 1];
                    block.layerOffset[layer] = 0;
                    block.layerDistortion[layer] = block.passDistortion[n - 1];
                } else {
                    var previous = block.passesInLayers - 1;
                    block.layerLength[layer] = block.passRate[n - 1] - block.passRate[previous];
                    block.layerOffset[layer] = block.passRate[previous];
                    block.layerDistortion[layer] = block.passDistortion[n - 1] - block.passDistortion[previous];
                }
                distortion[layer] += block.layerDistortion[layer];
                if (last) {
                    block.passesInLayers = n;
                }
            });
        }
        return same[0];
    }

    /**
     * Identifies one code-block to code.
     *
     * @param component the component index
     * @param resolution the resolution level
     * @param band the sub-band
     * @param block the code-block
     */
    private record Job(int component, int resolution, Band band, EncodedBlock block) {
    }
}
