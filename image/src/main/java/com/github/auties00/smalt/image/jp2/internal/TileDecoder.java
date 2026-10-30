package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Decodes one tile: reads its packets, decodes its code-blocks, applies the inverse wavelet and
 * component transforms and the DC level shift, and returns each decoded component's samples.
 *
 * <p>A tile using the extensions of ISO/IEC 15444-2 also goes through the multiple component transformations
 * (annex J), the variable DC offset (annex B) and the non-linear point transformations (annex K), in that order,
 * and may carry arbitrary wavelet kernels (annexes G and H), arbitrary decompositions (annex F) and arbitrarily
 * shaped regions of interest (annex L).
 *
 * @implNote This implementation follows OpenJPEG's {@code opj_tcd_decode_tile} for whole tiles:
 * the dequantization of {@code opj_t1_clbl_decode_processor}, the region-of-interest descaling,
 * the irreversible component transform of {@code opj_mct_decode_real} in single precision, and
 * the rounding and clamping of {@code opj_tcd_dc_level_shift_decode}. Code-blocks are decoded in
 * parallel on the shared {@code ParallelBands} pool, each band with a decoder of its own.
 */
final class TileDecoder {
    /**
     * The approximate work of decoding one code-block sample, weighed against
     * {@link ParallelBands#MIN_PARALLEL_WORK}.
     */
    private static final long SAMPLE_WORK = 64;

    /**
     * The irreversible component transform's red weight of Cr.
     */
    private static final float ICT_RV = 1.402f;

    /**
     * The irreversible component transform's green weight of Cb.
     */
    private static final float ICT_GU = 0.34413f;

    /**
     * The irreversible component transform's green weight of Cr.
     */
    private static final float ICT_GV = 0.71414f;

    /**
     * The irreversible component transform's blue weight of Cb.
     */
    private static final float ICT_BU = 1.772f;

    /**
     * Prevents instantiation.
     */
    private TileDecoder() {
        throw new AssertionError();
    }

    /**
     * Holds the decoded samples of one decoded image component in a tile.
     *
     * @param x0 the left edge on the reduced component grid
     * @param y0 the top edge on the reduced component grid
     * @param width the width in samples
     * @param height the height in samples
     * @param samples the samples, row-major, or {@code null} for a component wider than 31 bits
     * @param wide the samples of a component wider than 31 bits, or {@code null}
     */
    record Samples(long x0, long y0, int width, int height, int[] samples, long[] wide) {
    }

    /**
     * Decodes a tile.
     *
     * @param codestream the parsed codestream
     * @param tile the tile index
     * @param reduction the number of resolution levels to discard
     * @param maxLayers the number of quality layers to decode
     * @param limits the limits bounding every allocation
     * @return the samples of each decoded image component
     * @throws MediaException.Malformed if the tile's data is invalid
     * @throws MediaException.Unsupported if the tile uses an extension this decoder does not implement
     * @throws MediaException.LimitExceeded if the tile's structures exceed the limits
     */
    static Samples[] decode(Codestream codestream, int tile, int reduction, int maxLayers, MediaLimits limits) {
        return decode(codestream, tile, reduction, maxLayers, limits, null);
    }

    /**
     * Decodes a tile, the samples of each component that lie outside a window left unspecified.
     *
     * <p>Within the window of each component the samples equal those {@link #decode(Codestream, int, int, int,
     * MediaLimits)} returns. In a tile of ISO/IEC 15444-1 that uses the 5-3 or the 9-7 wavelet, the code-blocks no
     * sample of a window depends on are not decoded; their coefficients stay zero.
     *
     * @param codestream the parsed codestream
     * @param tile the tile index
     * @param reduction the number of resolution levels to discard
     * @param maxLayers the number of quality layers to decode
     * @param limits the limits bounding every allocation
     * @param windows the left, top, right and bottom edges (the last two exclusive) of the window of each decoded
     * image component on the reduced component grid, or {@code null} to decode every sample
     * @return the samples of each decoded image component
     * @throws MediaException.Malformed if the tile's data is invalid
     * @throws MediaException.Unsupported if the tile uses an extension this decoder does not implement
     * @throws MediaException.LimitExceeded if the tile's structures exceed the limits
     */
    static Samples[] decode(Codestream codestream, int tile, int reduction, int maxLayers, MediaLimits limits,
                            long[][] windows) {
        var parameters = codestream.parameters(tile);
        var extensions = parameters.extensions();
        var bounds = codestream.tileBounds(tile);
        var count = codestream.components();
        var components = new TileComponent[count];
        var subsamplingX = new int[count];
        var subsamplingY = new int[count];
        for (var c = 0; c < count; c++) {
            subsamplingX[c] = codestream.subsamplingX(c);
            subsamplingY[c] = codestream.subsamplingY(c);
            var coding = parameters.codings()[c];
            var decoded = Math.max(1, coding.resolutions() - reduction);
            components[c] = new TileComponent(
                    Math.ceilDiv(bounds[0], subsamplingX[c]), Math.ceilDiv(bounds[1], subsamplingY[c]),
                    Math.ceilDiv(bounds[2], subsamplingX[c]), Math.ceilDiv(bounds[3], subsamplingY[c]),
                    coding, parameters.quantizations()[c], codestream.precision(c), decoded, false,
                    extensions == null ? null : extensions.kernels()[c],
                    extensions == null ? null : extensions.decompositions()[c], limits);
        }
        var data = codestream.tile(tile);
        byte[] body = null;
        if (data != null) {
            LimitChecks.checkAllocation(limits, data.bodyLength());
            body = data.body(codestream.data());
            readPackets(body, data.packedHeaders(), parameters, components, bounds, subsamplingX, subsamplingY,
                    maxLayers, codestream.mixedCoders(), limits);
        }
        if (extensions == null) {
            return reconstruct(codestream, parameters, components, body, limits,
                    windows == null ? null : interest(components, windows));
        }
        return reconstructExtended(codestream, parameters, components, body, bounds, reduction, limits);
    }

    /**
     * Reads every packet of a tile in codestream order.
     *
     * @param body the tile body
     * @param headers the packed packet headers, or {@code null}
     * @param parameters the tile's parameters
     * @param components the tile-components
     * @param bounds the tile's area on the reference grid
     * @param subsamplingX the horizontal subsampling of each component
     * @param subsamplingY the vertical subsampling of each component
     * @param maxLayers the number of layers to decode
     * @param mixable whether high-throughput tile-components may mix block coders
     * @param limits the resource bounds checked before every allocation
     */
    private static void readPackets(byte[] body, byte[] headers, TileParameters parameters, TileComponent[] components,
                                    long[] bounds, int[] subsamplingX, int[] subsamplingY, int maxLayers,
                                    boolean mixable, MediaLimits limits) {
        var progressions = PacketOrder.progressions(parameters, components.length);
        var order = new PacketOrder(bounds, components, subsamplingX, subsamplingY, parameters.layers(),
                !parameters.progressions().isEmpty(), limits);
        var packets = new PacketDecoder(body, headers, parameters.sopMarkers(), parameters.ephMarkers(), mixable);
        var layers = Math.min(maxLayers, parameters.layers());
        for (var progression : progressions) {
            var going = order.list(progression, (layer, resolution, component, precinct) -> {
                if (packets.exhausted()) {
                    return false;
                }
                var tileComponent = components[component];
                var skip = layer >= layers || resolution >= tileComponent.decodedResolutions;
                packets.read(tileComponent, resolution, precinct, layer, skip);
                return true;
            });
            if (!going) {
                break;
            }
        }
        ParallelBands.checkpoint();
    }

    /**
     * Decodes the code-blocks and applies the inverse transforms of a tile of ISO/IEC 15444-1.
     *
     * @param codestream the parsed codestream
     * @param parameters the tile's parameters
     * @param components the tile-components
     * @param body the tile body, or {@code null} when the tile has no tile-part
     * @param limits the resource bounds checked before every allocation
     * @param interest the code-blocks to decode, as {@link #interest} returns them, or {@code null} for all
     * @return the samples of each component
     */
    private static Samples[] reconstruct(Codestream codestream, TileParameters parameters, TileComponent[] components,
                                         byte[] body, MediaLimits limits, long[][][] interest) {
        var count = components.length;
        var integers = new int[count][];
        var floats = new float[count][];
        for (var c = 0; c < count; c++) {
            var top = components[c].top();
            var size = (long) top.width() * top.height();
            LimitChecks.checkAllocation(limits, size * 4);
            if (components[c].coding.reversible()) {
                integers[c] = new int[(int) size];
            } else {
                floats[c] = new float[(int) size];
            }
        }
        if (body != null) {
            decodeBlocks(body, parameters, components, integers, null, floats, null, interest);
        }
        for (var c = 0; c < count; c++) {
            var stride = components[c].top().width();
            if (integers[c] != null) {
                Wavelet.inverse53(integers[c], stride, components[c]);
            } else {
                Wavelet.inverse97(floats[c], stride, components[c]);
            }
        }
        if (parameters.transform() == 1 && count >= 3) {
            checkComponentTransform(components);
            if (components[0].coding.reversible()) {
                inverseRct(integers[0], integers[1], integers[2]);
            } else {
                inverseIct(floats[0], floats[1], floats[2]);
            }
        }
        var result = new Samples[count];
        for (var c = 0; c < count; c++) {
            var top = components[c].top();
            var precision = codestream.precision(c);
            var signed = codestream.signed(c);
            var min = signed ? -(1 << (precision - 1)) : 0;
            var max = signed ? (1 << (precision - 1)) - 1 : (int) ((1L << precision) - 1);
            var shift = signed ? 0 : 1 << (precision - 1);
            int[] samples;
            if (integers[c] != null) {
                samples = integers[c];
                for (var i = 0; i < samples.length; i++) {
                    samples[i] = (int) Math.clamp((long) samples[i] + shift, min, max);
                }
            } else {
                var values = floats[c];
                samples = new int[values.length];
                for (var i = 0; i < values.length; i++) {
                    var value = values[i];
                    if (value > (float) Integer.MAX_VALUE) {
                        samples[i] = max;
                    } else if (value < Integer.MIN_VALUE) {
                        samples[i] = min;
                    } else {
                        samples[i] = (int) Math.clamp((long) Math.rint(value) + shift, min, max);
                    }
                }
            }
            result[c] = new Samples(top.x0(), top.y0(), top.width(), top.height(), samples, null);
        }
        return result;
    }

    /**
     * Decodes the code-blocks and applies the inverse transforms of a tile that uses the extensions of ISO/IEC
     * 15444-2, or components wider than 31 bits.
     *
     * @param codestream the parsed codestream
     * @param parameters the tile's parameters
     * @param components the tile-components
     * @param body the tile body, or {@code null} when the tile has no tile-part
     * @param bounds the tile's area on the reference grid
     * @param reduction the number of resolution levels discarded
     * @param limits the resource bounds checked before every allocation
     * @return the samples of each decoded image component
     */
    private static Samples[] reconstructExtended(Codestream codestream, TileParameters parameters,
                                                 TileComponent[] components, byte[] body, long[] bounds,
                                                 int reduction, MediaLimits limits) {
        var extensions = parameters.extensions();
        var count = components.length;
        var integers = new int[count][];
        var longs = new long[count][];
        var floats = new float[count][];
        var masks = new byte[count][];
        for (var c = 0; c < count; c++) {
            var component = components[c];
            var top = component.top();
            var size = (long) top.width() * top.height();
            LimitChecks.checkAllocation(limits, size * 8);
            var regions = extensions.regions().get(c);
            var regionShift = 0;
            for (var region : regions) {
                regionShift = Math.max(regionShift, region.shift());
            }
            if (!regions.isEmpty()) {
                masks[c] = RoiMasks.scales(component, regions, codestream.subsamplingX(c), codestream.subsamplingY(c),
                        limits);
            }
            var roi = regions.isEmpty() ? parameters.roiShifts()[c] : regionShift;
            if (component.reversible()) {
                if (component.kernel == null && maxBitPlanes(component) + roi < 31) {
                    integers[c] = new int[(int) size];
                } else {
                    longs[c] = new long[(int) size];
                }
            } else {
                floats[c] = new float[(int) size];
            }
        }
        if (body != null) {
            decodeBlocks(body, parameters, components, integers, longs, floats, masks, null);
        }
        for (var c = 0; c < count; c++) {
            var stride = components[c].top().width();
            if (integers[c] != null) {
                Wavelet.inverse53(integers[c], stride, components[c]);
                longs[c] = widen(integers[c]);
                integers[c] = null;
            } else if (longs[c] != null) {
                Wavelet.inverseWide(longs[c], stride, components[c]);
            } else {
                Wavelet.inverse97(floats[c], stride, components[c]);
            }
        }
        if (parameters.transform() == 1 && count >= 3) {
            checkComponentTransform(components);
            if (longs[0] != null) {
                inverseRct(longs[0], longs[1], longs[2]);
            } else {
                inverseIct(floats[0], floats[1], floats[2]);
            }
        }
        var planes = new ComponentTransforms.Plane[count];
        for (var c = 0; c < count; c++) {
            var top = components[c].top();
            planes[c] = new ComponentTransforms.Plane(top.x0(), top.y0(), top.width(), top.height(),
                    codestream.subsamplingX(c), codestream.subsamplingY(c), longs[c], floats[c]);
        }
        var outputs = codestream.outputs();
        var transformed = !extensions.stages().isEmpty();
        ComponentTransforms.Plane[] reconstructed;
        if (transformed) {
            reconstructed = ComponentTransforms.apply(planes, extensions.stages(), extensions.arrays(),
                    extensions.allKernels(), outputs.count());
        } else {
            reconstructed = Arrays.copyOf(planes, outputs.count());
        }
        var result = new Samples[outputs.count()];
        for (var j = 0; j < outputs.count(); j++) {
            var plane = reconstructed[j];
            var dx = outputs.subsamplingX()[j];
            var dy = outputs.subsamplingY()[j];
            if (plane == null) {
                var depths = codestream.reductionDepths(j, reduction);
                var px0 = TileComponent.ceilDivPow2(Math.ceilDiv(bounds[0], dx), depths[0]);
                var py0 = TileComponent.ceilDivPow2(Math.ceilDiv(bounds[1], dy), depths[1]);
                var px1 = TileComponent.ceilDivPow2(Math.ceilDiv(bounds[2], dx), depths[0]);
                var py1 = TileComponent.ceilDivPow2(Math.ceilDiv(bounds[3], dy), depths[1]);
                LimitChecks.checkAllocation(limits, (px1 - px0) * (py1 - py0) * 8);
                plane = new ComponentTransforms.Plane(px0, py0, (int) (px1 - px0), (int) (py1 - py0), dx, dy,
                        new long[(int) ((px1 - px0) * (py1 - py0))], null);
            } else if (plane.subsamplingX() != dx || plane.subsamplingY() != dy) {
                throw new MediaException.Malformed("JPEG 2000 tile whose component transformation changes the "
                        + "subsampling of component " + j);
            }
            var precision = outputs.reconstructedPrecisions()[j];
            var signed = outputs.reconstructedSigned()[j];
            double offset;
            if (transformed) {
                offset = 0;
            } else if (extensions.dcOffsets() != null && j < count) {
                offset = extensions.dcOffsets()[j];
            } else if (j < count && !codestream.signed(j)) {
                offset = Math.scalb(1.0, codestream.precision(j) - 1);
            } else {
                offset = 0;
            }
            var nonLinearity = extensions.nonLinearities()[j];
            var unrounded = nonLinearity != null && nonLinearity.type() == NonLinearity.SIGN_MAGNITUDE_LOOKUP
                    && plane.floats() != null;
            var values = unrounded
                    ? nonLinearity.applyUnrounded(plane.floats(), offset + plane.addend(), precision, signed)
                    : finish(plane, offset, precision, signed);
            var finalPrecision = precision;
            var finalSigned = signed;
            if (nonLinearity != null) {
                if (!unrounded) {
                    nonLinearity.apply(values, precision, signed);
                }
                finalPrecision = nonLinearity.precision();
                finalSigned = nonLinearity.signed();
            }
            if (finalPrecision != outputs.precisions()[j] || finalSigned != outputs.signed()[j]) {
                throw new MediaException.Malformed("JPEG 2000 tile whose NLT segment changes the bit depth of "
                        + "component " + j);
            }
            if (finalPrecision > 31) {
                result[j] = new Samples(plane.x0(), plane.y0(), plane.width(), plane.height(), null, values);
            } else {
                var narrow = new int[values.length];
                for (var i = 0; i < values.length; i++) {
                    narrow[i] = (int) values[i];
                }
                result[j] = new Samples(plane.x0(), plane.y0(), plane.width(), plane.height(), narrow, null);
            }
        }
        return result;
    }

    /**
     * Adds a DC offset to a reconstructed component, rounds it to integers and clamps it to its range.
     *
     * @param plane the reconstructed component
     * @param offset the DC offset
     * @param precision the component's precision
     * @param signed whether the component is signed
     * @return the samples, in a new array or the plane's own
     */
    private static long[] finish(ComponentTransforms.Plane plane, double offset, int precision, boolean signed) {
        var min = signed ? -(1L << (precision - 1)) : 0;
        var max = signed ? (1L << (precision - 1)) - 1 : (1L << precision) - 1;
        if (plane.integers() != null) {
            var values = plane.integers();
            var integral = offset == Math.rint(offset);
            var whole = (long) offset;
            for (var i = 0; i < values.length; i++) {
                var value = integral ? values[i] + whole : (long) Math.rint(values[i] + offset);
                values[i] = Math.clamp(value, min, max);
            }
            return values;
        }
        var floats = plane.floats();
        var values = new long[floats.length];
        var whole = offset == Math.rint(offset);
        var addend = plane.addend();
        for (var i = 0; i < floats.length; i++) {
            var value = whole ? (long) Math.rint(floats[i]) + (long) offset : (long) Math.rint(floats[i] + offset);
            values[i] = Math.clamp(value + addend, min, max);
        }
        return values;
    }

    /**
     * Returns the largest number of magnitude bit-planes of any sub-band of a tile-component.
     *
     * @param component the tile-component
     * @return the largest {@code Mb}
     */
    private static int maxBitPlanes(TileComponent component) {
        var max = 0;
        for (var resolution : component.resolutions) {
            for (var band : resolution.bands()) {
                max = Math.max(max, band.bitPlanes() + 1);
            }
        }
        return max;
    }

    /**
     * Copies integers into a new array of longs.
     *
     * @param values the integers
     * @return the longs
     */
    private static long[] widen(int[] values) {
        var result = new long[values.length];
        for (var i = 0; i < values.length; i++) {
            result[i] = values[i];
        }
        return result;
    }

    /**
     * Checks that the first three components can go through the component transformation of ISO/IEC 15444-1.
     *
     * @param components the tile-components
     * @throws MediaException.Malformed if the three components differ in size or wavelet
     */
    private static void checkComponentTransform(TileComponent[] components) {
        var top = components[0].top();
        for (var c = 1; c < 3; c++) {
            var other = components[c].top();
            if ((long) other.width() * other.height() != (long) top.width() * top.height()
                    || components[c].decodedResolutions != components[0].decodedResolutions) {
                throw new MediaException.Malformed("JPEG 2000 component transform over components of different sizes");
            }
            if (components[c].reversible() != components[0].reversible()) {
                throw new MediaException.Malformed("JPEG 2000 component transform over mixed wavelets");
            }
        }
    }

    /**
     * Applies the inverse reversible component transformation (G.2).
     *
     * @param y the luma samples, replaced by red
     * @param u the blue difference samples, replaced by green
     * @param v the red difference samples, replaced by blue
     */
    private static void inverseRct(int[] y, int[] u, int[] v) {
        for (var i = 0; i < y.length; i++) {
            var g = y[i] - ((u[i] + v[i]) >> 2);
            var r = v[i] + g;
            var b = u[i] + g;
            y[i] = r;
            u[i] = g;
            v[i] = b;
        }
    }

    /**
     * Applies the inverse reversible component transformation (G.2) to 64-bit samples.
     *
     * @param y the luma samples, replaced by red
     * @param u the blue difference samples, replaced by green
     * @param v the red difference samples, replaced by blue
     */
    private static void inverseRct(long[] y, long[] u, long[] v) {
        for (var i = 0; i < y.length; i++) {
            var g = y[i] - ((u[i] + v[i]) >> 2);
            var r = v[i] + g;
            var b = u[i] + g;
            y[i] = r;
            u[i] = g;
            v[i] = b;
        }
    }

    /**
     * Applies the inverse irreversible component transformation (G.3).
     *
     * @param y the luma samples, replaced by red
     * @param u the blue difference samples, replaced by green
     * @param v the red difference samples, replaced by blue
     */
    private static void inverseIct(float[] y, float[] u, float[] v) {
        for (var i = 0; i < y.length; i++) {
            var luma = y[i];
            var cb = u[i];
            var cr = v[i];
            y[i] = luma + (cr * ICT_RV);
            u[i] = luma - (cb * ICT_GU) - (cr * ICT_GV);
            v[i] = luma + (cb * ICT_BU);
        }
    }

    /**
     * Decodes every code-block of the decoded resolutions into the component buffers.
     *
     * @param body the tile body
     * @param parameters the tile's parameters
     * @param components the tile-components
     * @param integers the 32-bit buffers of the 5-3 components, entries {@code null} for the others
     * @param longs the 64-bit buffers of the other reversible components, or {@code null}
     * @param floats the buffers of the irreversible components
     * @param masks the region-of-interest scaling value of each coefficient of each component (ISO/IEC 15444-2
     * annex L), entries {@code null} for components without arbitrary regions; or {@code null}
     * @param interest the sub-band rectangle of each resolution level of each component whose code-blocks are
     * decoded, as {@link #interest} returns them, entries {@code null} for every code-block; or {@code null}
     */
    private static void decodeBlocks(byte[] body, TileParameters parameters, TileComponent[] components,
                                     int[][] integers, long[][] longs, float[][] floats, byte[][] masks,
                                     long[][][] interest) {
        var jobs = new ArrayList<Job>();
        var work = 0L;
        for (var c = 0; c < components.length; c++) {
            var component = components[c];
            var rects = interest == null ? null : interest[c];
            for (var r = 0; r < component.decodedResolutions; r++) {
                var level = component.resolutions[r];
                var rect = rects == null ? null : rects[r];
                for (var band : level.bands()) {
                    if (band.empty()) {
                        continue;
                    }
                    for (var precinct : band.precincts()) {
                        if (precinct == null) {
                            continue;
                        }
                        for (var block : precinct.codeBlocks) {
                            if (block.chunkEntries == 0 || block.corrupted) {
                                continue;
                            }
                            if (rect != null && (block.x0 >= rect[2] || block.x1 <= rect[0] || block.y0 >= rect[3]
                                    || block.y1 <= rect[1])) {
                                continue;
                            }
                            jobs.add(new Job(c, r, band, block));
                            work += (long) block.width() * block.height() * SAMPLE_WORK;
                        }
                    }
                }
            }
        }
        if (jobs.isEmpty()) {
            return;
        }
        var bands = ParallelBands.bands(jobs.size(), work);
        ParallelBands.run(bands, index -> {
            Tier1Decoder decoder = null;
            HtDecoder htDecoder = null;
            long[] shifted = null;
            var end = ParallelBands.start(index + 1, bands, jobs.size());
            for (var j = ParallelBands.start(index, bands, jobs.size()); j < end; j++) {
                var job = jobs.get(j);
                var component = components[job.component];
                var style = component.coding.codeBlockStyle();
                var mask = masks == null ? null : masks[job.component];
                var roi = parameters.roiShifts()[job.component];
                var minimum = 0;
                if (mask != null) {
                    var range = scaleRange(mask, component, job);
                    minimum = range[0];
                    roi = range[1] - range[0];
                }
                int[] decoded = null;
                long[] wide = null;
                if (job.block.coder == CodeBlock.LEGACY) {
                    if (decoder == null) {
                        decoder = new Tier1Decoder();
                    }
                    decoder.decode(job.block, body, job.band.orientation(), roi, style);
                    decoded = decoder.data();
                    wide = decoder.wideData();
                } else {
                    if (htDecoder == null) {
                        htDecoder = new HtDecoder();
                    }
                    decodeHighThroughput(htDecoder, job.block, body, job.band, roi, style);
                    decoded = htDecoder.data();
                    wide = htDecoder.wideData();
                }
                var size = job.block.width() * job.block.height();
                if (mask != null || wide != null) {
                    if (shifted == null || shifted.length < size) {
                        shifted = new long[Math.max(size, 4096)];
                    }
                    for (var i = 0; i < size; i++) {
                        shifted[i] = wide != null ? wide[i] : decoded[i];
                    }
                    if (mask != null) {
                        descaleRegions(shifted, mask, component, job, minimum);
                    } else if (roi != 0) {
                        for (var i = 0; i < size; i++) {
                            var value = shifted[i];
                            var magnitude = Math.abs(value);
                            if (roi >= 63) {
                                shifted[i] = 0;
                            } else if (magnitude >= 1L << roi) {
                                magnitude >>= roi;
                                shifted[i] = value < 0 ? -magnitude : magnitude;
                            }
                        }
                    }
                    storeWide(shifted, job, component, integers[job.component],
                            longs == null ? null : longs[job.component], floats[job.component]);
                } else {
                    store(decoded, job, component, roi, integers[job.component],
                            longs == null ? null : longs[job.component], floats[job.component]);
                }
            }
        });
    }

    /**
     * Returns the sub-band rectangles of the code-blocks the samples of a window of each tile-component depend on.
     *
     * <p>Going down from the highest decoded resolution, the samples {@code [a, b)} of a resolution level are
     * synthesized from the low-pass and high-pass samples {@code [floor(a / 2) - m, ceil(b / 2) + m)} of the level
     * below, in each direction, where {@code m} covers the support of the synthesis filters: 2 for the 5-3 wavelet,
     * 4 for the 9-7. Those rectangles bound the code-blocks of the sub-bands of each level, and the low-pass one is
     * the window of the level below; the window of the lowest level bounds its LL sub-band.
     *
     * @param components the tile-components
     * @param windows the left, top, right and bottom edges of the window of each component on its highest decoded
     * resolution
     * @return the rectangles (left, top, right, bottom) of each resolution level of each component, an entry
     * {@code null} for a component whose code-blocks all decode (an arbitrary decomposition or a kernel of ISO/IEC
     * 15444-2)
     */
    private static long[][][] interest(TileComponent[] components, long[][] windows) {
        var result = new long[components.length][][];
        for (var c = 0; c < components.length; c++) {
            var component = components[c];
            var top = component.top();
            if (top.node() != null || component.kernel != null || windows[c] == null) {
                continue;
            }
            var margin = component.coding.reversible() ? 2 : 4;
            var levels = component.decodedResolutions;
            var rects = new long[levels][];
            var x0 = Math.max(windows[c][0], top.x0());
            var y0 = Math.max(windows[c][1], top.y0());
            var x1 = Math.min(windows[c][2], top.x1());
            var y1 = Math.min(windows[c][3], top.y1());
            for (var r = levels - 1; r >= 1; r--) {
                if (x0 >= x1 || y0 >= y1) {
                    rects[r] = new long[4];
                    continue;
                }
                var rect = new long[]{Math.floorDiv(x0, 2) - margin, Math.floorDiv(y0, 2) - margin,
                        Math.ceilDiv(x1, 2) + margin, Math.ceilDiv(y1, 2) + margin};
                rects[r] = rect;
                var lower = component.resolutions[r - 1];
                x0 = Math.max(rect[0], lower.x0());
                y0 = Math.max(rect[1], lower.y0());
                x1 = Math.min(rect[2], lower.x1());
                y1 = Math.min(rect[3], lower.y1());
            }
            rects[0] = x0 < x1 && y0 < y1 ? new long[]{x0, y0, x1, y1} : new long[4];
            result[c] = rects;
        }
        return result;
    }

    /**
     * Returns the smallest and largest region-of-interest scaling value of a code-block's coefficients.
     *
     * @param mask the scaling values of the tile-component, in the layout of its coefficient buffer
     * @param component the tile-component
     * @param job the code-block
     * @return {@code sMin} and {@code sMax} of ISO/IEC 15444-2 L.2.1
     */
    private static int[] scaleRange(byte[] mask, TileComponent component, Job job) {
        var stride = component.resolutions[component.resolutions.length - 1].width();
        var x = (int) (job.block.x0 - job.band.x0()) + job.band.offsetX();
        var y = (int) (job.block.y0 - job.band.y0()) + job.band.offsetY();
        var min = 255;
        var max = 0;
        for (var j = 0; j < job.block.height(); j++) {
            var base = (y + j) * stride + x;
            for (var i = 0; i < job.block.width(); i++) {
                var value = mask[base + i] & 0xFF;
                min = Math.min(min, value);
                max = Math.max(max, value);
            }
        }
        return new int[]{min, max};
    }

    /**
     * Shifts the coefficients of a code-block with arbitrary regions of interest back to their quantized
     * magnitudes (ISO/IEC 15444-2 L.1): each by its scaling value less the code-block's smallest.
     *
     * @param values the decoded coefficients, with one extra fractional bit
     * @param mask the scaling values of the tile-component
     * @param component the tile-component
     * @param job the code-block
     * @param minimum the code-block's smallest scaling value
     */
    private static void descaleRegions(long[] values, byte[] mask, TileComponent component, Job job, int minimum) {
        var stride = component.resolutions[component.resolutions.length - 1].width();
        var x = (int) (job.block.x0 - job.band.x0()) + job.band.offsetX();
        var y = (int) (job.block.y0 - job.band.y0()) + job.band.offsetY();
        var width = job.block.width();
        for (var j = 0; j < job.block.height(); j++) {
            var base = (y + j) * stride + x;
            for (var i = 0; i < width; i++) {
                var shift = (mask[base + i] & 0xFF) - minimum;
                if (shift == 0) {
                    continue;
                }
                var value = values[j * width + i];
                var magnitude = shift >= 63 ? 0 : Math.abs(value) >> shift;
                values[j * width + i] = value < 0 ? -magnitude : magnitude;
            }
        }
    }

    /**
     * Decodes a high-throughput code-block: the last HT set whose cleanup segment holds data, with the refinement
     * passes of that set that were read ({@code Z_blk} and {@code S_blk} of ISO/IEC 15444-15 B.3).
     *
     * @param decoder the high-throughput block decoder
     * @param block the code-block with its codeword segments
     * @param body the tile body holding the codeword chunks
     * @param band the sub-band
     * @param roi the region-of-interest shift
     * @param style the code-block style flags
     * @throws MediaException.Malformed if the HT set is invalid
     * @throws MediaException.Unsupported if the sub-band has more than 61 magnitude bit-planes
     */
    private static void decodeHighThroughput(HtDecoder decoder, CodeBlock block, byte[] body, Band band, int roi,
                                             int style) {
        var width = block.width();
        var height = block.height();
        var segments = block.dataSegments;
        var cleanup = -1;
        var cleanupOffset = 0;
        var offset = 0;
        for (var s = 0; s < segments; s++) {
            var passes = block.segmentDataPasses[s];
            if (passes > 0 && block.segmentLengths[s] > 0 && (block.segmentStart[s] + passes - 1) % 3 == 0) {
                cleanup = s;
                cleanupOffset = offset;
            }
            offset += block.segmentLengths[s];
        }
        var magnitudeBits = band.bitPlanes() + roi;
        if (cleanup < 0 || magnitudeBits == 0) {
            decoder.clear(width, height);
            return;
        }
        var set = (block.segmentStart[cleanup] + block.segmentDataPasses[cleanup] - 1) / 3;
        var refinement = 0;
        var refinementPasses = 0;
        for (var s = cleanup + 1; s < segments && block.segmentStart[s] <= 3 * set + 2; s++) {
            refinement += block.segmentLengths[s];
            refinementPasses = block.segmentStart[s] + block.segmentDataPasses[s] - 1 - 3 * set;
        }
        var passes = refinement == 0 ? 1 : 1 + Math.min(refinementPasses, 2);
        var skipped = band.bitPlanes() + 1 - block.bitPlanes + set;
        var total = 0;
        for (var i = 1; i < block.chunkEntries; i += 2) {
            total += block.chunks[i];
        }
        var buffer = decoder.buffer(total);
        var length = 0;
        for (var i = 0; i < block.chunkEntries; i += 2) {
            System.arraycopy(body, block.chunks[i], buffer, length, block.chunks[i + 1]);
            length += block.chunks[i + 1];
        }
        var cleanupLength = block.segmentLengths[cleanup];
        if (cleanupOffset > 0) {
            System.arraycopy(buffer, cleanupOffset, buffer, 0, cleanupLength + refinement);
        }
        decoder.decode(cleanupLength, refinement, passes, magnitudeBits, skipped, width, height,
                (style & ComponentCoding.VERTICALLY_CAUSAL) != 0);
    }

    /**
     * Writes the decoded coefficients of a code-block into its component's buffer.
     *
     * @param decoded the decoded coefficients, with one extra fractional bit
     * @param job the code-block and its location
     * @param component the tile-component
     * @param roi the maximum shift region-of-interest shift
     * @param integers the 32-bit buffer, or {@code null}
     * @param longs the 64-bit buffer, or {@code null}
     * @param floats the floating-point buffer, or {@code null}
     */
    private static void store(int[] decoded, Job job, TileComponent component, int roi, int[] integers,
                              long[] longs, float[] floats) {
        var block = job.block;
        var band = job.band;
        var width = block.width();
        var height = block.height();
        var x = (int) (block.x0 - band.x0()) + band.offsetX();
        var y = (int) (block.y0 - band.y0()) + band.offsetY();
        if (roi != 0) {
            if (roi >= 31) {
                Arrays.fill(decoded, 0, width * height, 0);
            } else {
                var threshold = 1 << roi;
                for (var i = 0; i < width * height; i++) {
                    var value = decoded[i];
                    var magnitude = Math.abs(value);
                    if (magnitude >= threshold) {
                        magnitude >>= roi;
                        decoded[i] = value < 0 ? -magnitude : magnitude;
                    }
                }
            }
        }
        var stride = component.top().width();
        if (integers != null) {
            for (var j = 0; j < height; j++) {
                var target = (y + j) * stride + x;
                var source = j * width;
                for (var i = 0; i < width; i++) {
                    integers[target + i] = decoded[source + i] / 2;
                }
            }
        } else if (longs != null) {
            for (var j = 0; j < height; j++) {
                var target = (y + j) * stride + x;
                var source = j * width;
                for (var i = 0; i < width; i++) {
                    longs[target + i] = decoded[source + i] / 2;
                }
            }
        } else {
            var step = 0.5f * band.stepSize();
            for (var j = 0; j < height; j++) {
                var target = (y + j) * stride + x;
                var source = j * width;
                for (var i = 0; i < width; i++) {
                    floats[target + i] = (float) decoded[source + i] * step;
                }
            }
        }
    }

    /**
     * Writes the 64-bit decoded coefficients of a code-block into its component's buffer, after any maximum shift
     * region of interest was removed.
     *
     * @param decoded the decoded coefficients, with one extra fractional bit
     * @param job the code-block and its location
     * @param component the tile-component
     * @param integers the 32-bit buffer, or {@code null}
     * @param longs the 64-bit buffer, or {@code null}
     * @param floats the floating-point buffer, or {@code null}
     */
    private static void storeWide(long[] decoded, Job job, TileComponent component, int[] integers, long[] longs,
                                  float[] floats) {
        var block = job.block;
        var band = job.band;
        var width = block.width();
        var height = block.height();
        var x = (int) (block.x0 - band.x0()) + band.offsetX();
        var y = (int) (block.y0 - band.y0()) + band.offsetY();
        var stride = component.top().width();
        var step = 0.5f * band.stepSize();
        for (var j = 0; j < height; j++) {
            var target = (y + j) * stride + x;
            var source = j * width;
            for (var i = 0; i < width; i++) {
                var value = decoded[source + i];
                if (integers != null) {
                    integers[target + i] = (int) (value / 2);
                } else if (longs != null) {
                    longs[target + i] = value / 2;
                } else {
                    floats[target + i] = (float) value * step;
                }
            }
        }
    }

    /**
     * Identifies one code-block to decode.
     *
     * @param component the component index
     * @param resolution the resolution level
     * @param band the sub-band
     * @param block the code-block
     */
    private record Job(int component, int resolution, Band band, CodeBlock block) {
    }
}
