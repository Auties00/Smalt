package com.github.auties00.smalt.image.jp2.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.internal.limits.LimitChecks;
import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.ArrayList;
import java.util.Arrays;

/**
 * Decodes one tile: reads its packets, decodes its code-blocks, applies the inverse wavelet and
 * component transforms and the DC level shift, and returns each component's samples.
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
     * Holds the decoded samples of one tile-component.
     *
     * @param x0 the left edge on the reduced component grid
     * @param y0 the top edge on the reduced component grid
     * @param width the width in samples
     * @param height the height in samples
     * @param samples the samples, row-major
     */
    record Samples(int x0, int y0, int width, int height, int[] samples) {
    }

    /**
     * Decodes a tile.
     *
     * @param codestream the parsed codestream
     * @param tile the tile index
     * @param reduction the number of resolution levels to discard
     * @param maxLayers the number of quality layers to decode
     * @param limits the limits bounding every allocation
     * @return the samples of each component
     * @throws MediaException.Malformed if the tile's data is invalid
     * @throws MediaException.LimitExceeded if the tile's structures exceed the limits
     */
    static Samples[] decode(Codestream codestream, int tile, int reduction, int maxLayers, MediaLimits limits) {
        var parameters = codestream.parameters(tile);
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
                    (int) Math.ceilDiv(bounds[0], subsamplingX[c]), (int) Math.ceilDiv(bounds[1], subsamplingY[c]),
                    (int) Math.ceilDiv(bounds[2], subsamplingX[c]), (int) Math.ceilDiv(bounds[3], subsamplingY[c]),
                    coding, parameters.quantizations()[c], codestream.precision(c), decoded, false, limits);
        }
        var data = codestream.tile(tile);
        byte[] body = null;
        if (data != null) {
            LimitChecks.checkAllocation(limits, data.bodyLength());
            body = data.body(codestream.data());
            readPackets(body, data.packedHeaders(), parameters, components, bounds, subsamplingX, subsamplingY,
                    maxLayers, codestream.mixedCoders(), limits);
        }
        return reconstruct(codestream, parameters, components, body, limits);
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
                                    int[] bounds, int[] subsamplingX, int[] subsamplingY, int maxLayers,
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
     * Decodes the code-blocks and applies the inverse transforms.
     *
     * @param codestream the parsed codestream
     * @param parameters the tile's parameters
     * @param components the tile-components
     * @param body the tile body, or {@code null} when the tile has no tile-part
     * @param limits the resource bounds checked before every allocation
     * @return the samples of each component
     */
    private static Samples[] reconstruct(Codestream codestream, TileParameters parameters, TileComponent[] components,
                                         byte[] body, MediaLimits limits) {
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
            decodeBlocks(body, parameters, components, integers, floats);
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
            componentTransform(components, integers, floats);
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
            result[c] = new Samples(top.x0(), top.y0(), top.width(), top.height(), samples);
        }
        return result;
    }

    /**
     * Applies the inverse multiple component transformation to the first three components: the
     * reversible RCT for the 5-3 wavelet, the irreversible ICT for the 9-7 one (G.2 and G.3).
     *
     * @param components the tile-components
     * @param integers the integer coefficients of the reversible components
     * @param floats the floating-point coefficients of the irreversible components
     * @throws MediaException.Malformed if the three components differ in size or wavelet
     */
    private static void componentTransform(TileComponent[] components, int[][] integers, float[][] floats) {
        var top = components[0].top();
        for (var c = 1; c < 3; c++) {
            var other = components[c].top();
            if ((long) other.width() * other.height() != (long) top.width() * top.height()
                    || components[c].decodedResolutions != components[0].decodedResolutions) {
                throw new MediaException.Malformed("JPEG 2000 component transform over components of different sizes");
            }
            if (components[c].coding.reversible() != components[0].coding.reversible()) {
                throw new MediaException.Malformed("JPEG 2000 component transform over mixed wavelets");
            }
        }
        if (components[0].coding.reversible()) {
            var y = integers[0];
            var u = integers[1];
            var v = integers[2];
            for (var i = 0; i < y.length; i++) {
                var g = y[i] - ((u[i] + v[i]) >> 2);
                var r = v[i] + g;
                var b = u[i] + g;
                y[i] = r;
                u[i] = g;
                v[i] = b;
            }
        } else {
            var y = floats[0];
            var u = floats[1];
            var v = floats[2];
            for (var i = 0; i < y.length; i++) {
                var luma = y[i];
                var cb = u[i];
                var cr = v[i];
                y[i] = luma + (cr * ICT_RV);
                u[i] = luma - (cb * ICT_GU) - (cr * ICT_GV);
                v[i] = luma + (cb * ICT_BU);
            }
        }
    }

    /**
     * Decodes every code-block of the decoded resolutions into the component buffers.
     *
     * @param body the tile body
     * @param parameters the tile's parameters
     * @param components the tile-components
     * @param integers the integer buffers of the reversible components
     * @param floats the floating-point buffers of the irreversible components
     */
    private static void decodeBlocks(byte[] body, TileParameters parameters, TileComponent[] components,
                                     int[][] integers, float[][] floats) {
        var jobs = new ArrayList<Job>();
        var work = 0L;
        for (var c = 0; c < components.length; c++) {
            var component = components[c];
            for (var r = 0; r < component.decodedResolutions; r++) {
                var level = component.resolutions[r];
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
            var end = ParallelBands.start(index + 1, bands, jobs.size());
            for (var j = ParallelBands.start(index, bands, jobs.size()); j < end; j++) {
                var job = jobs.get(j);
                var component = components[job.component];
                var style = component.coding.codeBlockStyle();
                var roi = parameters.roiShifts()[job.component];
                int[] decoded;
                if (job.block.coder == CodeBlock.LEGACY) {
                    if (decoder == null) {
                        decoder = new Tier1Decoder();
                    }
                    decoder.decode(job.block, body, job.band.orientation(), roi, style);
                    decoded = decoder.data();
                } else {
                    if (htDecoder == null) {
                        htDecoder = new HtDecoder();
                    }
                    decodeHighThroughput(htDecoder, job.block, body, job.band, roi, style);
                    decoded = htDecoder.data();
                }
                store(decoded, job, component, roi, integers[job.component], floats[job.component]);
            }
        });
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
     * @throws MediaException.Unsupported if the sub-band has more than 30 magnitude bit-planes
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
     * @param roi the region-of-interest shift
     * @param integers the integer buffer, or {@code null} for the 9-7 wavelet
     * @param floats the floating-point buffer, or {@code null} for the 5-3 wavelet
     */
    private static void store(int[] decoded, Job job, TileComponent component, int roi, int[] integers,
                              float[] floats) {
        var block = job.block;
        var band = job.band;
        var width = block.width();
        var height = block.height();
        var x = block.x0 - band.x0();
        var y = block.y0 - band.y0();
        if ((band.orientation() & 1) != 0) {
            x += component.resolutions[job.resolution - 1].width();
        }
        if ((band.orientation() & 2) != 0) {
            y += component.resolutions[job.resolution - 1].height();
        }
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
