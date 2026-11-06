/* Ports libjxl 0.11.2 (lib/jxl/enc_ac_strategy.cc: EstimateEntropy, TryMergeAcs,
   FindBestFirstLevelDivisionForSquare, ProcessRectACS, AcStrategyHeuristics::Init;
   enc_adaptive_quantization.cc: AdjustQuantField and the 1x1 masking of AdaptiveQuantizationMap). Copyright the
   JPEG XL Project Authors; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.vardct;

import com.github.auties00.smalt.internal.task.ParallelBands;

import java.util.Arrays;

/**
 * Chooses the transform type of every block of a VarDCT frame: each 8x8 block takes the cheapest of the 8x8,
 * 4x4, 2x2 and identity transforms (and from effort 6 the 4x8 and AFV ones), and blocks are merged into larger
 * DCTs, up to 64x64, where the estimated cost in bits and visible loss drops.
 */
final class AcStrategies {
    private static final float[] MASK1X1_FILTER = {0.25647067633737227f, 0.2050056912354399075f,
            0.154082048668497307f, 0.08149576591362004441f, 0.0512750104812308467f};

    /**
     * The luma multipliers of X, Y and B in the cost estimate: the default chroma from luma map.
     */
    private static final float[] CMAP = {0.0f, 0.0f, 1.0f};

    /**
     * The transforms of a single 8x8 block, in the order they are tried.
     */
    private static final int[] TYPES_8X8 = {TransformType.DCT8, TransformType.DCT4, TransformType.DCT2,
            TransformType.DCT4X8, TransformType.DCT8X4, TransformType.HORNUSS, TransformType.AFV0, TransformType.AFV1,
            TransformType.AFV2, TransformType.AFV3};

    /**
     * The entropy multipliers of {@link #TYPES_8X8}.
     */
    private static final float[] MULS_8X8 = {0.8f, 1.08f, 0.95f, 0.85931637428340035f, 0.85931637428340035f,
            1.0427542510634957f, 0.81779489591359944f, 0.81779489591359944f, 0.81779489591359944f,
            0.81779489591359944f};

    /**
     * The weights of the X, Y and B losses in the cost estimate.
     */
    private static final double[] CHANNEL_MUL = {StrictMath.pow(10.2, 8.0), 1.0, StrictMath.pow(1.03, 8.0)};

    /**
     * The merges into rectangular DCTs tried over a tile, in order, with their priority and entropy multiplier.
     */
    private static final Merge[] MERGES = {new Merge(TransformType.DCT16X8, 2, 1.25f),
            new Merge(TransformType.DCT8X16, 2, 1.25f), new Merge(TransformType.DCT16X32, 4, 1.5f),
            new Merge(TransformType.DCT32X16, 4, 1.5f), new Merge(TransformType.DCT64X32, 6, 2.26f),
            new Merge(TransformType.DCT32X64, 6, 2.26f)};

    /**
     * A merge of blocks into one transform.
     *
     * @param type the transform type
     * @param priority the priority, which a block already merged at an equal or higher one keeps
     * @param mul the entropy multiplier of the merged transform
     */
    private record Merge(int type, int priority, float mul) {
    }

    private final VarDctEncoder e;

    private final float[][] xyb;

    private final float[] quant;

    private final float[] mask1x1;

    private final int stride;

    private final int[] owner;

    private final float infoLossMul;

    private final float zerosMul;

    private final float costDelta;

    private final float[] block = new float[3 * 64 * 64];

    private final float[] mem = new float[64 * 64];

    private final float[] scratch = new float[1024];

    private final float[] lf = new float[1];

    private final float[] entropy = new float[64];

    private final int[] priority = new int[64];

    private final float[] parts = new float[4];

    private AcStrategies(VarDctEncoder e, float[][] xyb, float[] quant, float[] mask1x1, int[] owner,
                         float distance) {
        this.e = e;
        this.xyb = xyb;
        this.quant = quant;
        this.mask1x1 = mask1x1;
        this.stride = e.bw() * 8;
        this.owner = owner;
        var ratio = (distance + 0.13731742964354549f) / (1.0f + 0.13731742964354549f);
        infoLossMul = 1.2f * (float) StrictMath.pow(ratio, 0.33677806662454718);
        zerosMul = 9.3089059022677905f * (float) StrictMath.pow(ratio, 0.50990926717963703);
        costDelta = 10.833273317067883f * (float) StrictMath.pow(ratio, 0.36702940662370243);
    }

    /**
     * Fills the encoder's strategy map and adjusts its quantization field over the chosen varblocks.
     *
     * @param e the encoder, its quantization field already set per block
     * @param xyb the XYB planes after the Gaborish inverse
     * @param quant the initial quantization field per block, or {@code null} below effort 5
     * @param mask the visual masking of every pixel, or {@code null} below effort 5
     * @param distance the distance target
     * @param effort the encoder effort, from 1 to 10
     */
    static void choose(VarDctEncoder e, float[][] xyb, float[] quant, float[] mask, float distance, int effort) {
        Arrays.fill(e.strategy, TransformType.DCT8);
        if (effort < 5 || quant == null) {
            return;
        }
        var owner = new int[e.bw() * e.bh()];
        for (var i = 0; i < owner.length; i++) {
            owner[i] = i;
        }
        // Each 64x64 tile is decided on its own: varblocks never cross a tile, so the bands of tile rows write
        // disjoint parts of the strategy map.
        var tilesY = (e.bh() + 7) / 8;
        var bands = ParallelBands.bands(tilesY, (long) e.bw() * e.bh() * 64 * 3 * 64);
        ParallelBands.run(bands, band -> {
            var s = new AcStrategies(e, xyb, quant, mask, owner, distance);
            for (var ty = ParallelBands.start(band, bands, tilesY); ty < ParallelBands.start(band + 1, bands, tilesY);
                 ty++) {
                for (var tx = 0; tx < (e.bw() + 7) / 8; tx++) {
                    s.processTile(tx * 8, ty * 8, Math.min(8, e.bw() - tx * 8), Math.min(8, e.bh() - ty * 8),
                            distance, effort);
                }
            }
        });
        adjustQuantField(e, quant, distance);
    }

    /**
     * Computes the blurred 1x1 masking of the Y plane (libjxl's {@code mask1x1}).
     *
     * @param y the Y plane, before the Gaborish inverse
     * @param w the width
     * @param h the height
     * @return the masking of every pixel
     */
    static float[] mask1x1(float[] y, int w, int h) {
        var raw = new float[w * h];
        var bands = ParallelBands.bands(h, (long) w * h * 32);
        ParallelBands.run(bands, band -> {
            for (var py = ParallelBands.start(band, bands, h); py < ParallelBands.start(band + 1, bands, h); py++) {
                var y1 = py > 0 ? py - 1 : py;
                var y2 = py + 1 < h ? py + 1 : py;
                for (var x = 0; x < w; x++) {
                    var x1 = x > 0 ? x - 1 : x;
                    var x2 = x + 1 < w ? x + 1 : x;
                    var v = y[py * w + x];
                    var base = 0.25f * (y[y2 * w + x] + y[y1 * w + x] + y[py * w + x1] + y[py * w + x2]);
                    var gammac = AdaptiveQuantization.ratio(v + 0.019f, false);
                    var diff = (float) StrictMath.log1p(Math.abs(gammac * (v - base)));
                    raw[py * w + x] = 1.0f / (diff + 0.01f);
                }
            }
        });
        var sum = 1.0 + 4 * (MASK1X1_FILTER[0] + MASK1X1_FILTER[1] + MASK1X1_FILTER[2] + MASK1X1_FILTER[4]
                + 2 * MASK1X1_FILTER[3]);
        var n = (float) (1.0 / sum);
        var r = n * MASK1X1_FILTER[0];
        var bigR = n * MASK1X1_FILTER[2];
        var d = n * MASK1X1_FILTER[1];
        var bigD = n * MASK1X1_FILTER[4];
        var l = n * MASK1X1_FILTER[3];
        var out = new float[w * h];
        ParallelBands.run(bands, band -> {
            for (var py = ParallelBands.start(band, bands, h); py < ParallelBands.start(band + 1, bands, h); py++) {
                for (var x = 0; x < w; x++) {
                    out[py * w + x] = n * at(raw, w, h, x, py)
                            + r * (at(raw, w, h, x, py - 1) + at(raw, w, h, x, py + 1) + at(raw, w, h, x - 1, py)
                            + at(raw, w, h, x + 1, py))
                            + bigR * (at(raw, w, h, x, py - 2) + at(raw, w, h, x, py + 2) + at(raw, w, h, x - 2, py)
                            + at(raw, w, h, x + 2, py))
                            + d * (at(raw, w, h, x - 1, py - 1) + at(raw, w, h, x + 1, py - 1)
                            + at(raw, w, h, x - 1, py + 1) + at(raw, w, h, x + 1, py + 1))
                            + bigD * (at(raw, w, h, x - 2, py - 2) + at(raw, w, h, x + 2, py - 2)
                            + at(raw, w, h, x - 2, py + 2) + at(raw, w, h, x + 2, py + 2))
                            + l * (at(raw, w, h, x - 1, py - 2) + at(raw, w, h, x + 1, py - 2)
                            + at(raw, w, h, x - 1, py + 2) + at(raw, w, h, x + 1, py + 2)
                            + at(raw, w, h, x - 2, py - 1) + at(raw, w, h, x + 2, py - 1)
                            + at(raw, w, h, x - 2, py + 1) + at(raw, w, h, x + 2, py + 1));
                }
            }
        });
        return out;
    }

    private static float at(float[] p, int w, int h, int x, int y) {
        x = x < 0 ? -x - 1 : x >= w ? 2 * w - 1 - x : x;
        y = y < 0 ? -y - 1 : y >= h ? 2 * h - 1 - y : y;
        return p[Math.clamp(y, 0, h - 1) * w + Math.clamp(x, 0, w - 1)];
    }

    private void processTile(int bx, int by, int tw, int th, float distance, int effort) {
        var entropy = this.entropy;
        var priority = this.priority;
        Arrays.fill(entropy, 0.0f);
        Arrays.fill(priority, 0);
        var mul8x8 = 1.0f + -0.4f / (distance + 1.4f);
        for (var iy = 0; iy < th; iy++) {
            for (var ix = 0; ix < tw; ix++) {
                entropy[iy * 8 + ix] = best8x8(bx + ix, by + iy, distance, effort) * mul8x8;
            }
        }
        for (var m : MERGES) {
            var cw = TransformType.width(m.type());
            var ch = TransformType.height(m.type());
            for (var cy = 0; cy + ch - 1 < th; cy += ch) {
                for (var cx = 0; cx + cw - 1 < tw; cx += cw) {
                    if (cy + 7 < th && cx + 7 < tw) {
                        if (m.type() == TransformType.DCT32X64) {
                            if ((cy | cx) % 8 == 0) {
                                divideSquare(8, true, bx, by, cx, cy, m.mul(), 2.26f, entropy);
                            }
                            continue;
                        } else if (m.type() == TransformType.DCT64X32) {
                            continue;
                        }
                    }
                    if ((m.type() == TransformType.DCT16X32 && cy % 4 != 0)
                            || (m.type() == TransformType.DCT32X16 && cx % 4 != 0)) {
                        continue;
                    }
                    if (cy + 3 < th && cx + 3 < tw) {
                        if (m.type() == TransformType.DCT16X32) {
                            if ((cy | cx) % 4 == 0) {
                                divideSquare(4, true, bx, by, cx, cy, m.mul(), 1.5f, entropy);
                            }
                            continue;
                        } else if (m.type() == TransformType.DCT32X16) {
                            continue;
                        }
                    }
                    if (cy + 1 < th && cx + 1 < tw) {
                        if (m.type() == TransformType.DCT8X16) {
                            if ((cy | cx) % 2 == 0) {
                                divideSquare(2, true, bx, by, cx, cy, m.mul(), 1.35f, entropy);
                            }
                            continue;
                        } else if (m.type() == TransformType.DCT16X8) {
                            continue;
                        }
                    }
                    if ((m.type() == TransformType.DCT8X16 && cy % 2 == 1)
                            || (m.type() == TransformType.DCT16X8 && cx % 2 == 1)) {
                        continue;
                    }
                    tryMerge(m.type(), bx, by, cx, cy, m.mul(), m.priority(), priority, entropy);
                }
            }
        }
        if (effort < 6) {
            return;
        }
        for (var cy = 0; cy + 1 < th; cy++) {
            for (var cx = 0; cx + 1 < tw; cx++) {
                if ((cy | cx) % 2 != 0) {
                    divideSquare(2, true, bx, by, cx, cy, 1.25f, 1.35f, entropy);
                }
            }
        }
        var step = effort <= 9 ? 2 : 1;
        for (var cy = 0; cy + 3 < th; cy += step) {
            for (var cx = 0; cx + 3 < tw; cx += step) {
                if ((cy | cx) % 4 == 0) {
                    continue;
                }
                divideSquare(4, true, bx, by, cx, cy, 1.5f, 1.5f, entropy);
            }
        }
    }

    private float best8x8(int bx, int by, float distance, int effort) {
        var types = TYPES_8X8;
        var muls = MULS_8X8;
        var best = Float.MAX_VALUE;
        var bestType = TransformType.DCT8;
        for (var k = 0; k < types.length; k++) {
            var type = types[k];
            if (effort < 6 && (type == TransformType.DCT4X8 || type == TransformType.DCT8X4
                    || (type >= TransformType.AFV0 && type <= TransformType.AFV3))) {
                continue;
            }
            var mul = muls[k] / muls[0];
            if ((type == TransformType.DCT2 || type == TransformType.HORNUSS) && distance < 5.0f) {
                var weight = (float) StrictMath.pow((5.0f - distance) / 5.0f, 2.0);
                mul -= 0.4f * weight;
            }
            if (type != TransformType.DCT8 && type != TransformType.DCT2 && type != TransformType.HORNUSS
                    && distance > 4.0f) {
                var m = distance < 12.0f ? (12.0f - 4.0f) / (distance - 4.0f) : 1.0f;
                mul += 0.5f * m;
            }
            var cost = estimate(type, mul, bx * 8, by * 8);
            if (cost < best) {
                best = cost;
                bestType = type;
            }
        }
        e.strategy[by * e.bw() + bx] = bestType;
        return best;
    }

    private void tryMerge(int type, int bx, int by, int cx, int cy, float mul, int candidatePriority, int[] priority,
                          float[] entropy) {
        var cw = TransformType.width(type);
        var ch = TransformType.height(type);
        var current = 0.0f;
        for (var iy = 0; iy < ch; iy++) {
            for (var ix = 0; ix < cw; ix++) {
                if (priority[(cy + iy) * 8 + cx + ix] >= candidatePriority) {
                    return;
                }
                current += entropy[(cy + iy) * 8 + cx + ix];
            }
        }
        var candidate = estimate(type, mul, (bx + cx) * 8, (by + cy) * 8);
        if (candidate >= current) {
            return;
        }
        for (var iy = 0; iy < ch; iy++) {
            for (var ix = 0; ix < cw; ix++) {
                entropy[(cy + iy) * 8 + cx + ix] = 0;
                priority[(cy + iy) * 8 + cx + ix] = candidatePriority;
            }
        }
        set(bx + cx, by + cy, type);
        entropy[cy * 8 + cx] = candidate;
    }

    private void setEntropy(int cx, int cy, int type, float value, float[] entropy) {
        for (var dy = 0; dy < TransformType.height(type); dy++) {
            for (var dx = 0; dx < TransformType.width(type); dx++) {
                entropy[(cy + dy) * 8 + cx + dx] = 0;
            }
        }
        entropy[cy * 8 + cx] = value;
    }

    private void divideSquare(int blocks, boolean allowSquare, int bx, int by, int cx, int cy, float mulJxk,
                              float mulJxj, float[] entropy) {
        var half = blocks / 2;
        var jxk = blocks == 2 ? TransformType.DCT16X8 : blocks == 4 ? TransformType.DCT32X16
                : TransformType.DCT64X32;
        var kxj = blocks == 2 ? TransformType.DCT8X16 : blocks == 4 ? TransformType.DCT16X32
                : TransformType.DCT32X64;
        var jxj = blocks == 2 ? TransformType.DCT16 : blocks == 4 ? TransformType.DCT32 : TransformType.DCT64;
        var x0 = bx + cx;
        var y0 = by + cy;
        if (crossesHorizontal(x0, y0, x0 + blocks) || crossesHorizontal(x0, y0 + blocks, x0 + blocks)
                || crossesVertical(x0, y0, y0 + blocks) || crossesVertical(x0 + blocks, y0, y0 + blocks)) {
            return;
        }
        var allowJxk = !crossesVertical(x0 + half, y0, y0 + blocks);
        var allowKxj = !crossesHorizontal(x0, y0 + half, x0 + blocks);
        var parts = this.parts;
        Arrays.fill(parts, 0.0f);
        for (var dy = 0; dy < blocks; dy++) {
            for (var dx = 0; dx < blocks; dx++) {
                parts[(dy / half) * 2 + dx / half] += entropy[(cy + dy) * 8 + cx + dx];
            }
        }
        var left = Float.MAX_VALUE;
        var right = Float.MAX_VALUE;
        var top = Float.MAX_VALUE;
        var bottom = Float.MAX_VALUE;
        var square = Float.MAX_VALUE;
        if (allowJxk) {
            if (typeAt(x0, y0) != jxk) {
                left = estimate(jxk, mulJxk, x0 * 8, y0 * 8);
            }
            if (typeAt(x0 + half, y0) != jxk) {
                right = estimate(jxk, mulJxk, (x0 + half) * 8, y0 * 8);
            }
        }
        if (allowKxj) {
            if (typeAt(x0, y0) != kxj) {
                top = estimate(kxj, mulJxk, x0 * 8, y0 * 8);
            }
            if (typeAt(x0, y0 + half) != kxj) {
                bottom = estimate(kxj, mulJxk, x0 * 8, (y0 + half) * 8);
            }
        }
        if (allowSquare) {
            square = estimate(jxj, mulJxj, x0 * 8, y0 * 8);
        }
        var topLeft = parts[0];
        var topRight = parts[1];
        var bottomLeft = parts[2];
        var bottomRight = parts[3];
        var costJxn = Math.min(left, topLeft + bottomLeft) + Math.min(right, topRight + bottomRight);
        var costNxj = Math.min(top, topLeft + topRight) + Math.min(bottom, bottomLeft + bottomRight);
        if (square < costJxn && square < costNxj) {
            set(x0, y0, jxj);
            setEntropy(cx, cy, jxj, square, entropy);
        } else if (costJxn < costNxj) {
            if (left < topLeft + bottomLeft) {
                set(x0, y0, jxk);
                setEntropy(cx, cy, jxk, left, entropy);
            }
            if (right < topRight + bottomRight) {
                set(x0 + half, y0, jxk);
                setEntropy(cx + half, cy, jxk, right, entropy);
            }
        } else {
            if (top < topLeft + topRight) {
                set(x0, y0, kxj);
                setEntropy(cx, cy, kxj, top, entropy);
            }
            if (bottom < bottomLeft + bottomRight) {
                set(x0, y0 + half, kxj);
                setEntropy(cx, cy + half, kxj, bottom, entropy);
            }
        }
    }

    private int typeAt(int bx, int by) {
        var o = owner[by * e.bw() + bx];
        return o == by * e.bw() + bx ? e.strategy[o] : -1;
    }

    private boolean crossesHorizontal(int x0, int y, int x1) {
        if (y >= e.bh() || y % 8 == 0) {
            return false;
        }
        for (var x = x0; x < x1 && x < e.bw(); x++) {
            if (owner[y * e.bw() + x] == owner[(y - 1) * e.bw() + x]) {
                return true;
            }
        }
        return false;
    }

    private boolean crossesVertical(int x, int y0, int y1) {
        if (x >= e.bw() || x % 8 == 0) {
            return false;
        }
        for (var y = y0; y < y1 && y < e.bh(); y++) {
            if (owner[y * e.bw() + x] == owner[y * e.bw() + x - 1]) {
                return true;
            }
        }
        return false;
    }

    private void set(int bx, int by, int type) {
        var w = TransformType.width(type);
        var h = TransformType.height(type);
        var first = by * e.bw() + bx;
        for (var dy = 0; dy < h; dy++) {
            for (var dx = 0; dx < w; dx++) {
                var i = (by + dy) * e.bw() + bx + dx;
                owner[i] = first;
                e.strategy[i] = -1;
            }
        }
        e.strategy[first] = type;
    }

    private float estimate(int type, float entropyMul, int x, int y) {
        var w8 = TransformType.width(type);
        var h8 = TransformType.height(type);
        var numBlocks = w8 * h8;
        var size = numBlocks * 64;
        for (var c = 0; c < 3; c++) {
            VarDctEncoder.forward(xyb[c], stride, x, y, type, mem, scratch);
            System.arraycopy(mem, 0, block, c * size, size);
        }
        var bw = e.bw();
        float quantNorm;
        if (numBlocks == 1) {
            quantNorm = quant[(y / 8) * bw + x / 8];
        } else if (numBlocks == 2) {
            quantNorm = h8 == 2 ? Math.max(quant[(y / 8) * bw + x / 8], quant[(y / 8 + 1) * bw + x / 8])
                    : Math.max(quant[(y / 8) * bw + x / 8], quant[(y / 8) * bw + x / 8 + 1]);
        } else {
            var sum = 0.0;
            for (var iy = 0; iy < h8; iy++) {
                for (var ix = 0; ix < w8; ix++) {
                    double q = quant[(y / 8 + iy) * bw + x / 8 + ix];
                    q *= q;
                    q *= q;
                    q *= q;
                    sum += q * q;
                }
            }
            quantNorm = (float) StrictMath.pow(sum / numBlocks, 1.0 / 16.0);
        }
        var matrices = e.matrices();
        var entropy = 0.0f;
        var loss = 0.0;
        var w = w8 * 8;
        var h = h8 * 8;
        for (var c = 0; c < 3; c++) {
            var matrix = matrices.matrix(type, c);
            var entropyV = 0.0f;
            var nonZeros = 0;
            for (var i = 0; i < size; i++) {
                if (i / w < h8 && i % w < w8) {
                    // The lowest frequencies are coded in the LF image: neither bits nor loss here.
                    mem[i] = 0.0f;
                    continue;
                }
                var in = block[c * size + i];
                var inY = block[size + i] * CMAP[c];
                var val = (in - inY) * (quantNorm / matrix[i]);
                var rval = Math.round(val);
                mem[i] = matrix[i] * (val - rval);
                var q = Math.abs(rval);
                entropyV += (float) Math.sqrt(q);
                if (q != 0) {
                    nonZeros++;
                }
            }
            if (numBlocks == 1 && type != TransformType.DCT8) {
                lf[0] = mem[0];
                Varblocks.transform(type, mem, 0, w, lf, 0, 1, scratch);
            } else {
                Dct.dct2d(mem, 0, w, w, h, scratch, true);
            }
            var lossC = 0.0;
            for (var py = 0; py < h; py++) {
                for (var px = 0; px < w; px++) {
                    var mx = x + px;
                    var my = y + py;
                    if (mx < stride && my * stride + mx < mask1x1.length) {
                        double v = Math.abs(mask1x1[my * stride + mx]) * mem[py * w + px];
                        v *= v;
                        v *= v;
                        v *= v;
                        lossC += v;
                    }
                }
            }
            loss += CHANNEL_MUL[c] * lossC;
            entropy += costDelta * entropyV;
            var nbits = ceilLog2(nonZeros + 1) + 1;
            entropy += zerosMul * (ceilLog2(nbits + 17) + nbits);
        }
        var lossScalar = (float) (StrictMath.pow(loss / size, 1.0 / 8.0) * size / quantNorm);
        return entropy * entropyMul + infoLossMul * lossScalar;
    }

    private static int ceilLog2(int x) {
        return x <= 1 ? 0 : 32 - Integer.numberOfLeadingZeros(x - 1);
    }

    private static void adjustQuantField(VarDctEncoder e, float[] quant, float distance) {
        var mixer = 1.0f;
        if (distance > 1.54138f) {
            mixer = Math.max(0.0f, 1.0f - (distance - 1.54138f) * 0.56391f);
        }
        var bw = e.bw();
        var inv = 65536.0f / e.globalScale;
        for (var by = 0; by < e.bh(); by++) {
            for (var bx = 0; bx < bw; bx++) {
                var type = e.strategy[by * bw + bx];
                if (type < 0) {
                    continue;
                }
                var cw = TransformType.width(type);
                var ch = TransformType.height(type);
                if (cw * ch == 1) {
                    continue;
                }
                var max = quant[by * bw + bx];
                var mean = 0.0f;
                for (var iy = 0; iy < ch; iy++) {
                    for (var ix = 0; ix < cw; ix++) {
                        var q = quant[(by + iy) * bw + bx + ix];
                        mean += q;
                        max = Math.max(q, max);
                    }
                }
                mean /= cw * ch;
                if (cw * ch >= 4) {
                    max = max * mixer + (1.0f - mixer) * mean;
                }
                e.quantField[by * bw + bx] = VarDctEncoder.clampQuant(max * inv + 0.5f);
            }
        }
    }
}
