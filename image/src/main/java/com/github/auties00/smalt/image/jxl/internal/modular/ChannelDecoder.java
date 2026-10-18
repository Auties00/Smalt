/* Ports jxl-oxide 0.12.6 (crates/jxl-modular: the channel decoder). Copyright Wonwoo Choi and the jxl-oxide
   contributors; MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.modular;

import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.entropy.EntropyDecoder;

import java.util.Arrays;

/**
 * Decodes the samples of one modular channel (ISO/IEC 18181-1 section H.5): for every sample in
 * raster order, the MA tree picks a context and a predictor from the sample's properties, and the
 * sample is the prediction plus the decoded residual.
 *
 * <p>Channels whose tree reduces to a single leaf take specialized loops that compute only what
 * the leaf needs; the others compute the sixteen local properties and the properties of the
 * previous channels the tree reads.
 *
 * @implNote This implementation keeps a fast path for the gradient predictor without offset or
 * multiplier.
 */
final class ChannelDecoder {
    /**
     * The number of local properties.
     */
    private static final int LOCAL_PROPERTIES = 16;

    /**
     * Prevents instantiation.
     */
    private ChannelDecoder() {
        throw new AssertionError();
    }

    /**
     * Decodes a channel.
     *
     * @param br the bitstream
     * @param decoder the entropy decoder of the stream
     * @param tree the MA tree pruned for this channel
     * @param wp the weighted predictor parameters
     * @param data the channel samples, written in place
     * @param width the channel width
     * @param height the channel height
     * @param prev the previous channels of the same size and shift, most recent first
     * @param distMultiplier the LZ77 distance multiplier of the stream
     */
    static void decode(JxlBitReader br, EntropyDecoder decoder, MaTree.Pruned tree, WpHeader wp, int[] data,
                       int width, int height, int[][] prev, int distMultiplier) {
        if (width == 0 || height == 0) {
            return;
        }
        if (tree.isLeaf()) {
            var predictor = tree.right()[0];
            var cluster = tree.left()[0];
            var offset = tree.value()[0];
            var multiplier = tree.multiplier()[0];
            if (predictor == Predictors.ZERO) {
                var token = decoder.singleToken(cluster);
                if (token >= 0) {
                    Arrays.fill(data, 0, width * height, MaTree.unpackSigned(token) * multiplier + offset);
                    return;
                }
                for (var i = 0; i < width * height; i++) {
                    data[i] = MaTree.unpackSigned(decoder.readUintClustered(br, cluster, distMultiplier)) * multiplier
                            + offset;
                }
                return;
            }
            if (predictor == Predictors.GRADIENT && offset == 0 && multiplier == 1) {
                decodeGradient(br, decoder, cluster, data, width, height, distMultiplier);
                return;
            }
            if (predictor != Predictors.WEIGHTED) {
                decodeSingleLeaf(br, decoder, predictor, cluster, offset, multiplier, data, width, height,
                        distMultiplier);
                return;
            }
        }
        decodeGeneral(br, decoder, tree, wp, data, width, height, prev, distMultiplier);
    }

    /**
     * Decodes a channel whose tree is a single gradient leaf without offset or multiplier.
     *
     * @param br the bitstream
     * @param decoder the entropy decoder
     * @param cluster the leaf cluster
     * @param data the channel samples
     * @param width the channel width in samples
     * @param height the channel height in samples
     * @param distMultiplier the LZ77 distance multiplier
     */
    private static void decodeGradient(JxlBitReader br, EntropyDecoder decoder, int cluster, int[] data, int width,
                                       int height, int distMultiplier) {
        var w = 0;
        for (var x = 0; x < width; x++) {
            w += MaTree.unpackSigned(decoder.readUintClustered(br, cluster, distMultiplier));
            data[x] = w;
        }
        for (var y = 1; y < height; y++) {
            var row = y * width;
            var up = row - width;
            w = MaTree.unpackSigned(decoder.readUintClustered(br, cluster, distMultiplier)) + data[up];
            data[row] = w;
            for (var x = 1; x < width; x++) {
                var pred = Predictors.gradient(data[up + x], w, data[up + x - 1]);
                w = MaTree.unpackSigned(decoder.readUintClustered(br, cluster, distMultiplier)) + pred;
                data[row + x] = w;
            }
        }
    }

    /**
     * Decodes a channel whose tree is a single leaf with a fixed predictor.
     *
     * @param br the bitstream
     * @param decoder the entropy decoder
     * @param predictor the predictor of the leaf
     * @param cluster the leaf cluster
     * @param offset the leaf offset
     * @param multiplier the leaf multiplier
     * @param data the channel samples
     * @param width the channel width in samples
     * @param height the channel height in samples
     * @param distMultiplier the LZ77 distance multiplier
     */
    private static void decodeSingleLeaf(JxlBitReader br, EntropyDecoder decoder, int predictor, int cluster,
                                         int offset, int multiplier, int[] data, int width, int height,
                                         int distMultiplier) {
        for (var y = 0; y < height; y++) {
            var row = y * width;
            for (var x = 0; x < width; x++) {
                var idx = row + x;
                var w = x > 0 ? data[idx - 1] : (y > 0 ? data[idx - width] : 0);
                var n = y > 0 ? data[idx - width] : w;
                var nw = x > 0 && y > 0 ? data[idx - width - 1] : w;
                var ne = y > 0 && x + 1 < width ? data[idx - width + 1] : n;
                var nn = y > 1 ? data[idx - 2 * width] : n;
                var ww = x > 1 ? data[idx - 2] : w;
                var nee = y > 0 && x + 2 < width ? data[idx - width + 2] : ne;
                var pred = Predictors.predict(predictor, n, w, nw, ne, nn, ww, nee);
                var residual = MaTree.unpackSigned(decoder.readUintClustered(br, cluster, distMultiplier));
                data[idx] = residual * multiplier + offset + pred;
            }
        }
    }

    /**
     * Decodes a channel with a full MA tree.
     *
     * @param br the bitstream
     * @param decoder the entropy decoder
     * @param tree the pruned tree
     * @param wp the weighted predictor parameters
     * @param data the channel samples
     * @param width the channel width in samples
     * @param height the channel height in samples
     * @param prev the previous channels the tree reads, most recent first
     * @param distMultiplier the LZ77 distance multiplier
     */
    private static void decodeGeneral(JxlBitReader br, EntropyDecoder decoder, MaTree.Pruned tree, WpHeader wp,
                                      int[] data, int width, int height, int[][] prev, int distMultiplier) {
        var property = tree.property();
        var value = tree.value();
        var left = tree.left();
        var right = tree.right();
        var multiplier = tree.multiplier();
        var maxPrev = Math.min(tree.maxPrev(), prev.length);
        var props = new int[LOCAL_PROPERTIES + 4 * Math.max(tree.maxPrev(), 0)];
        var weighted = tree.usesWeighted() ? new WeightedPredictor(wp, width) : null;
        for (var y = 0; y < height; y++) {
            var row = y * width;
            var prevGrad = 0;
            props[2] = y;
            for (var x = 0; x < width; x++) {
                var idx = row + x;
                var w = x > 0 ? data[idx - 1] : (y > 0 ? data[idx - width] : 0);
                var n = y > 0 ? data[idx - width] : w;
                var nw = x > 0 && y > 0 ? data[idx - width - 1] : w;
                var ne = y > 0 && x + 1 < width ? data[idx - width + 1] : n;
                var nn = y > 1 ? data[idx - 2 * width] : n;
                var ww = x > 1 ? data[idx - 2] : w;
                var nee = y > 0 && x + 2 < width ? data[idx - width + 2] : ne;
                if (weighted != null) {
                    weighted.predict(n, nw, ne, w, nn);
                    props[15] = weighted.maxError;
                }
                var grad = w - nw + n;
                props[3] = x;
                props[4] = Math.abs(n);
                props[5] = Math.abs(w);
                props[6] = n;
                props[7] = w;
                props[8] = w - prevGrad;
                props[9] = grad;
                props[10] = w - nw;
                props[11] = nw - n;
                props[12] = n - ne;
                props[13] = n - nn;
                props[14] = w - ww;
                for (var k = 0; k < maxPrev; k++) {
                    var channel = prev[k];
                    var c = channel[idx];
                    int g;
                    if (x == 0 && y == 0) {
                        g = 0;
                    } else if (x == 0) {
                        g = channel[idx - width];
                    } else if (y == 0) {
                        g = channel[idx - 1];
                    } else {
                        g = Predictors.gradient(channel[idx - width], channel[idx - 1], channel[idx - width - 1]);
                    }
                    var base = LOCAL_PROPERTIES + 4 * k;
                    props[base] = Math.abs(c);
                    props[base + 1] = c;
                    props[base + 2] = (int) Math.abs((long) c - g);
                    props[base + 3] = c - g;
                }
                var node = 0;
                while (property[node] >= 0) {
                    var p = property[node];
                    var v = p < props.length ? props[p] : 0;
                    node = v > value[node] ? left[node] : right[node];
                }
                var predictor = right[node];
                int pred;
                if (predictor == Predictors.WEIGHTED) {
                    pred = (int) ((weighted.prediction + 3) >> 3);
                } else {
                    pred = Predictors.predict(predictor, n, w, nw, ne, nn, ww, nee);
                }
                var residual = MaTree.unpackSigned(decoder.readUintClustered(br, left[node], distMultiplier));
                var sample = residual * multiplier[node] + value[node] + pred;
                data[idx] = sample;
                if (weighted != null) {
                    weighted.record(sample);
                }
                prevGrad = grad;
            }
        }
    }
}
