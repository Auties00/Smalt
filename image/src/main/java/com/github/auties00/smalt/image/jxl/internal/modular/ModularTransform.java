/* Ports jxl-oxide 0.12.6 (crates/jxl-modular: the transforms) and libjxl (lib/jxl/modular/transform/palette).
   Copyright Wonwoo Choi and the jxl-oxide contributors, and the JPEG XL Project Authors; MIT License and
   BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.modular;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;

import java.util.ArrayList;
import java.util.List;

/**
 * Describes one transform of a modular image (ISO/IEC 18181-1 section H.6): a reversible color
 * transform (RCT), a palette, or a squeeze, and applies its inverse.
 *
 * <p>Reading an image applies each transform forward to the channel list, which decides the size
 * of every channel that is then decoded; decoding ends by applying the inverses in reverse order.
 * Instances are immutable once their default squeeze steps are resolved.
 *
 * @implNote This implementation takes the palette semantics from libjxl where jxl-oxide differs.
 */
final class ModularTransform {
    /**
     * The reversible color transform.
     */
    static final int RCT = 0;

    /**
     * The palette transform.
     */
    static final int PALETTE = 1;

    /**
     * The squeeze (Haar-like) transform.
     */
    static final int SQUEEZE = 2;

    /**
     * The color deltas of the delta palette (ISO/IEC 18181-1 table H.5).
     */
    private static final int[] DELTA_PALETTE = {
            0, 0, 0, 4, 4, 4, 11, 0, 0, 0, 0, -13, 0, -12, 0, -10, -10, -10,
            -18, -18, -18, -27, -27, -27, -18, -18, 0, 0, 0, -32, -32, 0, 0, -37, -37, -37,
            0, -32, -32, 24, 24, 45, 50, 50, 50, -45, -24, -24, -24, -45, -45, 0, -24, -24,
            -34, -34, 0, -24, 0, -24, -45, -45, -24, 64, 64, 64, -32, 0, -32, 0, -32, 0,
            -32, 0, 32, -24, -45, -24, 45, 24, 45, 24, -24, -45, -45, -24, 24, 80, 80, 80,
            64, 0, 0, 0, 0, -64, 0, -64, -64, -24, -24, 45, 96, 96, 96, 64, 64, 0,
            45, -24, -24, 34, -34, 0, 112, 112, 112, 24, -45, -45, 45, 45, -24, 0, -32, 32,
            24, -24, 45, 0, 96, 96, 45, -24, 24, 24, -45, -24, -24, -45, 24, 0, -64, 0,
            96, 0, 0, 128, 128, 128, 64, 0, 64, 144, 144, 144, 96, 96, 0, -36, -36, 36,
            45, -24, -45, 45, -45, -24, 0, 0, -96, 0, 128, 128, 0, 96, 0, 45, 24, -45,
            -128, 0, 0, 24, -45, 24, -45, 24, -45, 64, 0, -64, 64, -64, -64, 96, 0, 96,
            45, -45, 24, 24, 45, -45, 64, 64, -64, 128, 128, 0, 0, 0, -128, -24, 45, -45,
    };

    /**
     * The transform kind: {@link #RCT}, {@link #PALETTE} or {@link #SQUEEZE}.
     */
    final int kind;

    /**
     * The first channel the transform applies to.
     */
    final int beginC;

    /**
     * The RCT type, from 0 to 41.
     */
    final int rctType;

    /**
     * The number of channels the palette covers.
     */
    final int numC;

    /**
     * The number of palette colors.
     */
    final int nbColors;

    /**
     * The number of delta palette entries.
     */
    final int nbDeltas;

    /**
     * The predictor of the delta palette entries.
     */
    final int dPred;

    /**
     * The squeeze steps, four values each: horizontal, in place, first channel and channel count.
     */
    private List<int[]> squeezeSteps;

    /**
     * Creates a transform.
     *
     * @param kind the transform kind constant
     * @param beginC the first channel
     * @param rctType the RCT type
     * @param numC the palette channel count
     * @param nbColors the palette color count
     * @param nbDeltas the delta entry count
     * @param dPred the delta predictor
     * @param squeezeSteps the squeeze steps
     */
    private ModularTransform(int kind, int beginC, int rctType, int numC, int nbColors, int nbDeltas, int dPred,
                             List<int[]> squeezeSteps) {
        this.kind = kind;
        this.beginC = beginC;
        this.rctType = rctType;
        this.numC = numC;
        this.nbColors = nbColors;
        this.nbDeltas = nbDeltas;
        this.dPred = dPred;
        this.squeezeSteps = squeezeSteps;
    }

    /**
     * Reads a {@code TransformInfo} bundle.
     *
     * @param br the bitstream
     * @return the transform
     * @throws MediaException.Malformed if the transform is unknown or a predictor is invalid
     */
    static ModularTransform read(JxlBitReader br) {
        var id = br.readBits(2);
        switch (id) {
            case RCT -> {
                var begin = br.readU32(0, 3, 8, 6, 72, 10, 1096, 13);
                var type = br.readU32(6, 0, 0, 2, 2, 4, 10, 6);
                if (type > 41) {
                    throw new MediaException.Malformed("invalid RCT type " + type);
                }
                return new ModularTransform(RCT, begin, type, 3, 0, 0, 0, null);
            }
            case PALETTE -> {
                var begin = br.readU32(0, 3, 8, 6, 72, 10, 1096, 13);
                var numC = br.readU32(1, 0, 3, 0, 4, 0, 1, 13);
                var colors = br.readU32(0, 8, 256, 10, 1280, 12, 5376, 16);
                var deltas = br.readU32(0, 0, 1, 8, 257, 10, 1281, 16);
                var pred = br.readBits(4);
                if (pred > 13) {
                    throw new MediaException.Malformed("invalid delta palette predictor " + pred);
                }
                return new ModularTransform(PALETTE, begin, 0, numC, colors, deltas, pred, null);
            }
            case SQUEEZE -> {
                var count = br.readU32(0, 0, 1, 4, 9, 6, 41, 8);
                var steps = new ArrayList<int[]>(Math.min(count, 1024));
                for (var i = 0; i < count; i++) {
                    var horizontal = br.readBool() ? 1 : 0;
                    var inPlace = br.readBool() ? 1 : 0;
                    var begin = br.readU32(0, 3, 8, 6, 72, 10, 1096, 13);
                    var num = br.readU32(1, 0, 2, 0, 3, 0, 4, 4);
                    steps.add(new int[]{horizontal, inPlace, begin, num});
                }
                return new ModularTransform(SQUEEZE, 0, 0, 0, 0, 0, 0, steps);
            }
            default -> throw new MediaException.Malformed("invalid modular transform " + id);
        }
    }

    /**
     * Applies the transform forward to the channel list, as the encoder did.
     *
     * @param channels the channels, modified
     * @param nbMeta the number of leading meta channels
     * @return the new number of leading meta channels
     * @throws MediaException.Malformed if the transform does not fit the channels
     */
    int applyForward(List<ModularChannel> channels, int nbMeta) {
        return switch (kind) {
            case RCT -> {
                if ((long) beginC + 3 > channels.size()) {
                    throw new MediaException.Malformed("RCT outside the channel list");
                }
                var first = channels.get(beginC);
                for (var i = beginC + 1; i < beginC + 3; i++) {
                    if (channels.get(i).width != first.width || channels.get(i).height != first.height) {
                        throw new MediaException.Malformed("RCT over channels of different sizes");
                    }
                }
                yield nbMeta;
            }
            case PALETTE -> {
                var end = (long) beginC + numC;
                if (end > channels.size()) {
                    throw new MediaException.Malformed("palette outside the channel list");
                }
                var meta = nbMeta;
                if (beginC < nbMeta) {
                    if (end > nbMeta) {
                        throw new MediaException.Malformed("palette across meta and image channels");
                    }
                    meta = nbMeta + 2 - numC;
                } else {
                    meta = nbMeta + 1;
                }
                var first = channels.get(beginC);
                for (var i = beginC + 1; i < end; i++) {
                    if (channels.get(i).width != first.width || channels.get(i).height != first.height) {
                        throw new MediaException.Malformed("palette over channels of different sizes");
                    }
                }
                channels.subList(beginC + 1, (int) end).clear();
                channels.addFirst(new ModularChannel(nbColors, numC, -1, -1));
                yield meta;
            }
            default -> {
                if (squeezeSteps.isEmpty()) {
                    squeezeSteps = defaultSqueeze(channels, nbMeta);
                }
                var meta = nbMeta;
                for (var step : squeezeSteps) {
                    var horizontal = step[0] != 0;
                    var inPlace = step[1] != 0;
                    var begin = step[2];
                    var end = (long) begin + step[3];
                    if (end > channels.size()) {
                        throw new MediaException.Malformed("squeeze outside the channel list");
                    }
                    if (begin < meta) {
                        if (!inPlace || end > meta) {
                            throw new MediaException.Malformed("invalid squeeze of meta channels");
                        }
                        meta += step[3];
                    }
                    var residuals = new ArrayList<ModularChannel>(step[3]);
                    for (var i = begin; i < end; i++) {
                        var channel = channels.get(i);
                        if (channel.width == 0 || channel.height == 0) {
                            throw new MediaException.Malformed("squeeze of an empty channel");
                        }
                        if (channel.hshift > 30 || channel.vshift > 30) {
                            throw new MediaException.Malformed("channel squeezed too much");
                        }
                        var residual = channel.copyInfo();
                        residual.residual = true;
                        if (horizontal) {
                            var w = channel.width;
                            channel.width = (w + 1) / 2;
                            residual.width = w / 2;
                            if (channel.hshift >= 0) {
                                channel.hshift++;
                                residual.hshift++;
                            }
                        } else {
                            var h = channel.height;
                            channel.height = (h + 1) / 2;
                            residual.height = h / 2;
                            if (channel.vshift >= 0) {
                                channel.vshift++;
                                residual.vshift++;
                            }
                        }
                        residuals.add(residual);
                    }
                    if (inPlace) {
                        channels.addAll((int) end, residuals);
                    } else {
                        channels.addAll(residuals);
                    }
                }
                yield meta;
            }
        };
    }

    /**
     * Builds the default squeeze steps for a channel list.
     *
     * @param channels the channels before the squeeze
     * @param nbMeta the number of leading meta channels
     * @return the steps
     * @throws MediaException.Malformed if there is no channel to squeeze
     */
    private static List<int[]> defaultSqueeze(List<ModularChannel> channels, int nbMeta) {
        var steps = new ArrayList<int[]>();
        if (nbMeta >= channels.size()) {
            throw new MediaException.Malformed("squeeze without image channels");
        }
        var first = channels.get(nbMeta);
        var w = first.width;
        var h = first.height;
        if (channels.size() - nbMeta >= 3) {
            var next = channels.get(nbMeta + 1);
            if (next.width == w && next.height == h) {
                steps.add(new int[]{1, 0, nbMeta + 1, 2});
                steps.add(new int[]{0, 0, nbMeta + 1, 2});
            }
        }
        var count = channels.size() - nbMeta;
        if (h >= w && h > 8) {
            steps.add(new int[]{0, 1, nbMeta, count});
            h = (h + 1) / 2;
        }
        while (w > 8 || h > 8) {
            if (w > 8) {
                steps.add(new int[]{1, 1, nbMeta, count});
                w = (w + 1) / 2;
            }
            if (h > 8) {
                steps.add(new int[]{0, 1, nbMeta, count});
                h = (h + 1) / 2;
            }
        }
        return steps;
    }

    /**
     * Applies the inverse transform to the decoded channels.
     *
     * @param channels the channels, modified
     * @param bitDepth the image bit depth, used by the synthesized palette colors
     * @param wp the weighted predictor parameters of the image
     */
    void inverse(List<ModularChannel> channels, int bitDepth, WpHeader wp) {
        switch (kind) {
            case RCT -> inverseRct(channels);
            case PALETTE -> inversePalette(channels, bitDepth, wp);
            default -> inverseSqueeze(channels);
        }
    }

    /**
     * Applies the inverse reversible color transform.
     *
     * @param channels the channel list, updated in place
     */
    private void inverseRct(List<ModularChannel> channels) {
        var permutation = rctType / 7;
        var type = rctType % 7;
        var ca = channels.get(beginC);
        var cb = channels.get(beginC + 1);
        var cc = channels.get(beginC + 2);
        var a = ca.data;
        var b = cb.data;
        var c = cc.data;
        var n = ca.width * ca.height;
        for (var i = 0; i < n; i++) {
            var va = a[i];
            var vb = b[i];
            var vc = c[i];
            int d;
            int e;
            int f;
            if (type == 6) {
                var tmp = va - (vc >> 1);
                e = vc + tmp;
                f = tmp - (vb >> 1);
                d = f + vb;
            } else {
                d = va;
                f = (type & 1) != 0 ? vc + va : vc;
                e = switch (type >> 1) {
                    case 1 -> vb + va;
                    case 2 -> vb + ((va + f) >> 1);
                    default -> vb;
                };
            }
            a[i] = d;
            b[i] = e;
            c[i] = f;
        }
        switch (permutation) {
            case 1 -> {
                swap(ca, cb);
                swap(ca, cc);
            }
            case 2 -> {
                swap(ca, cb);
                swap(cb, cc);
            }
            case 3 -> swap(cb, cc);
            case 4 -> swap(ca, cb);
            case 5 -> swap(ca, cc);
            default -> {
            }
        }
    }

    /**
     * Swaps the samples of two channels of the same size.
     *
     * @param x the first channel
     * @param y the second channel
     */
    private static void swap(ModularChannel x, ModularChannel y) {
        var tmp = x.data;
        x.data = y.data;
        y.data = tmp;
    }

    /**
     * Applies the inverse palette transform.
     *
     * @param channels the channel list, updated in place
     * @param bitDepth the image bit depth
     * @param wp the weighted predictor parameters
     */
    private void inversePalette(List<ModularChannel> channels, int bitDepth, WpHeader wp) {
        var palette = channels.removeFirst();
        var index = channels.get(beginC);
        var width = index.width;
        var height = index.height;
        var size = width * height;
        var depth = Math.min(bitDepth, 24);
        var indices = index.data;
        var pal = palette.data;
        var paletteWidth = palette.width;
        var outputs = new ModularChannel[numC];
        for (var c = 0; c < numC; c++) {
            outputs[c] = new ModularChannel(width, height, index.hshift, index.vshift);
            outputs[c].data = new int[size];
        }
        if (width != 0) {
            if (nbDeltas == 0 && dPred == Predictors.ZERO) {
                if (numC == 1) {
                    var out = outputs[0].data;
                    for (var i = 0; i < size; i++) {
                        var clamped = Math.min(Math.max(indices[i], 0), paletteWidth - 1);
                        out[i] = paletteValue(pal, paletteWidth, clamped, 0, depth);
                    }
                } else {
                    for (var c = 0; c < numC; c++) {
                        var out = outputs[c].data;
                        for (var i = 0; i < size; i++) {
                            out[i] = paletteValue(pal, paletteWidth, indices[i], c, depth);
                        }
                    }
                }
            } else {
                for (var c = 0; c < numC; c++) {
                    applyDeltas(outputs[c].data, pal, paletteWidth, indices, c, width, height, depth, wp);
                }
            }
        }
        channels.set(beginC, outputs[0]);
        for (var c = 1; c < numC; c++) {
            channels.add(beginC + c, outputs[c]);
        }
    }

    /**
     * Returns the value of a palette entry, synthesizing the implicit entries beyond the explicit
     * palette and the delta entries below zero.
     *
     * @param palette the palette samples, one row per channel
     * @param paletteWidth the number of explicit colors
     * @param index the palette index
     * @param c the channel
     * @param depth the bit depth, at most 24
     * @return the value
     */
    private static int paletteValue(int[] palette, int paletteWidth, int index, int c, int depth) {
        if (index < 0) {
            if (c >= 3) {
                return 0;
            }
            var d = -(index + 1);
            d %= 143;
            var result = DELTA_PALETTE[((d + 1) >> 1) * 3 + c];
            if ((d & 1) == 0) {
                result = -result;
            }
            if (depth > 8) {
                result *= 1 << (depth - 8);
            }
            return result;
        }
        if (index >= paletteWidth && index < paletteWidth + 64) {
            if (c >= 3) {
                return 0;
            }
            var k = (index - paletteWidth) >> (c * 2);
            return (int) (((k % 4) * ((1L << depth) - 1)) >> 2) + (1 << Math.max(0, depth - 3));
        }
        if (index >= paletteWidth + 64) {
            if (c >= 3) {
                return 0;
            }
            var k = index - paletteWidth - 64;
            if (c == 1) {
                k /= 5;
            } else if (c == 2) {
                k /= 25;
            }
            return (int) (((k % 5) * ((1L << depth) - 1)) >> 2);
        }
        return palette[c * paletteWidth + index];
    }

    /**
     * Expands one output channel of a palette that has delta entries or a predictor.
     *
     * @param data the output samples
     * @param palette the palette samples
     * @param paletteWidth the number of explicit colors
     * @param indices the palette indices
     * @param c the channel
     * @param width the channel width
     * @param height the channel height
     * @param depth the bit depth, at most 24
     * @param wp the weighted predictor parameters
     */
    private void applyDeltas(int[] data, int[] palette, int paletteWidth, int[] indices, int c, int width, int height,
                             int depth, WpHeader wp) {
        var weighted = dPred == Predictors.WEIGHTED ? new WeightedPredictor(wp, width) : null;
        for (var y = 0; y < height; y++) {
            var row = y * width;
            for (var x = 0; x < width; x++) {
                var idx = row + x;
                var entry = paletteValue(palette, paletteWidth, indices[idx], c, depth);
                var delta = indices[idx] < nbDeltas;
                int sample;
                if (weighted != null) {
                    var w = x > 0 ? data[idx - 1] : (y > 0 ? data[idx - width] : 0);
                    var n = y > 0 ? data[idx - width] : w;
                    var nw = x > 0 && y > 0 ? data[idx - width - 1] : w;
                    var ne = y > 0 && x + 1 < width ? data[idx - width + 1] : n;
                    var nn = y > 1 ? data[idx - 2 * width] : n;
                    weighted.predict(n, nw, ne, w, nn);
                    sample = delta ? (int) ((weighted.prediction + 3) >> 3) + entry : entry;
                    data[idx] = sample;
                    weighted.record(sample);
                } else if (delta) {
                    var w = x > 0 ? data[idx - 1] : (y > 0 ? data[idx - width] : 0);
                    var n = y > 0 ? data[idx - width] : w;
                    var nw = x > 0 && y > 0 ? data[idx - width - 1] : w;
                    var ne = y > 0 && x + 1 < width ? data[idx - width + 1] : n;
                    var nn = y > 1 ? data[idx - 2 * width] : n;
                    var ww = x > 1 ? data[idx - 2] : w;
                    var nee = y > 0 && x + 2 < width ? data[idx - width + 2] : ne;
                    data[idx] = Predictors.predict(dPred, n, w, nw, ne, nn, ww, nee) + entry;
                } else {
                    data[idx] = entry;
                }
            }
        }
    }

    /**
     * Applies the inverse squeeze steps in reverse order.
     *
     * @param channels the channel list, updated in place
     */
    private void inverseSqueeze(List<ModularChannel> channels) {
        for (var s = squeezeSteps.size() - 1; s >= 0; s--) {
            var step = squeezeSteps.get(s);
            var horizontal = step[0] != 0;
            var inPlace = step[1] != 0;
            var begin = step[2];
            var count = step[3];
            var end = begin + count;
            List<ModularChannel> residuals;
            if (inPlace) {
                var view = channels.subList(end, end + count);
                residuals = new ArrayList<>(view);
                view.clear();
            } else {
                var view = channels.subList(channels.size() - count, channels.size());
                residuals = new ArrayList<>(view);
                view.clear();
            }
            for (var i = 0; i < count; i++) {
                var avg = channels.get(begin + i);
                var residual = residuals.get(i);
                var merged = horizontal ? unsqueezeHorizontal(avg, residual) : unsqueezeVertical(avg, residual);
                channels.set(begin + i, merged);
            }
        }
    }

    /**
     * Merges an averages channel and a residual channel squeezed horizontally.
     *
     * @param avg the averages, {@code ceil(w / 2)} wide
     * @param residual the residuals, {@code floor(w / 2)} wide
     * @return the merged channel
     */
    private static ModularChannel unsqueezeHorizontal(ModularChannel avg, ModularChannel residual) {
        var width = avg.width + residual.width;
        var height = avg.height;
        var out = new ModularChannel(width, height, avg.hshift > 0 ? avg.hshift - 1 : avg.hshift, avg.vshift);
        out.data = new int[width * height];
        var avgWidth = avg.width;
        var resWidth = residual.width;
        var a = avg.data;
        var r = residual.data;
        var o = out.data;
        for (var y = 0; y < height; y++) {
            var avgRow = y * avgWidth;
            var resRow = y * resWidth;
            var outRow = y * width;
            var current = a[avgRow];
            var left = current;
            for (var x = 0; x < resWidth; x++) {
                var next = x + 1 < avgWidth ? a[avgRow + x + 1] : current;
                var diff = r[resRow + x] + tendency(left, current, next);
                var first = current + diff / 2;
                var second = first - diff;
                o[outRow + 2 * x] = first;
                o[outRow + 2 * x + 1] = second;
                current = next;
                left = second;
            }
            if ((width & 1) != 0) {
                o[outRow + width - 1] = a[avgRow + avgWidth - 1];
            }
        }
        return out;
    }

    /**
     * Merges an averages channel and a residual channel squeezed vertically.
     *
     * @param avg the averages, {@code ceil(h / 2)} tall
     * @param residual the residuals, {@code floor(h / 2)} tall
     * @return the merged channel
     */
    private static ModularChannel unsqueezeVertical(ModularChannel avg, ModularChannel residual) {
        var width = avg.width;
        var height = avg.height + residual.height;
        var out = new ModularChannel(width, height, avg.hshift, avg.vshift > 0 ? avg.vshift - 1 : avg.vshift);
        out.data = new int[width * height];
        var avgHeight = avg.height;
        var resHeight = residual.height;
        var a = avg.data;
        var r = residual.data;
        var o = out.data;
        for (var x = 0; x < width; x++) {
            var current = a[x];
            var top = current;
            for (var y = 0; y < resHeight; y++) {
                var next = y + 1 < avgHeight ? a[(y + 1) * width + x] : current;
                var diff = r[y * width + x] + tendency(top, current, next);
                var first = current + diff / 2;
                var second = first - diff;
                o[2 * y * width + x] = first;
                o[(2 * y + 1) * width + x] = second;
                current = next;
                top = second;
            }
            if ((height & 1) != 0) {
                o[(height - 1) * width + x] = a[(avgHeight - 1) * width + x];
            }
        }
        return out;
    }

    /**
     * Computes the squeeze tendency term (ISO/IEC 18181-1 section H.6.2).
     *
     * @param a the sample before
     * @param b the average
     * @param c the next average
     * @return the tendency
     */
    static int tendency(int a, int b, int c) {
        if (a >= b && b >= c) {
            var x = (4 * a - 3 * c - b + 6) / 12;
            if (x - (x & 1) > 2 * (a - b)) {
                x = 2 * (a - b) + 1;
            }
            if (x + (x & 1) > 2 * (b - c)) {
                x = 2 * (b - c);
            }
            return x;
        }
        if (a <= b && b <= c) {
            var x = (4 * a - 3 * c - b - 6) / 12;
            if (x + (x & 1) < 2 * (a - b)) {
                x = 2 * (a - b) - 1;
            }
            if (x - (x & 1) < 2 * (b - c)) {
                x = 2 * (b - c);
            }
            return x;
        }
        return 0;
    }
}
