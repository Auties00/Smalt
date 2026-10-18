/* Ports jxl-oxide 0.12.6 (crates/jxl-vardct: HfPass). Copyright Wonwoo Choi and the jxl-oxide contributors;
   MIT License. Modified for Smalt. */
package com.github.auties00.smalt.image.jxl.internal.vardct;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.image.jxl.internal.bitstream.JxlBitReader;
import com.github.auties00.smalt.image.jxl.internal.entropy.EntropyDecoder;
import com.github.auties00.smalt.image.jxl.internal.entropy.Permutations;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

/**
 * Holds the coefficient orders and the entropy decoder of one HF pass (ISO/IEC 18181-1 section
 * I.3.2).
 *
 * <p>Each of the 13 orders lists the coefficient positions of a block shape in decoding order, as
 * {@code x | y << 16}; an order the pass does not override is the natural zig-zag order.
 */
public final class HfPass {
    /**
     * The coefficient block size of each order, as {@code width, height}.
     */
    private static final int[] BLOCK_SIZES = {
            8, 8, 8, 8, 16, 16, 32, 32, 16, 8, 32, 8, 32, 16, 64, 64, 64, 32, 128, 128, 128, 64, 256, 256, 256, 128,
    };

    /**
     * The natural orders, computed on first use and accessed only through {@link #NATURAL_SLOT}.
     */
    private static final int[][] NATURAL = new int[13][];

    /**
     * The element view of {@link #NATURAL} whose release stores and acquire loads publish each
     * order with its contents.
     */
    private static final VarHandle NATURAL_SLOT = MethodHandles.arrayElementVarHandle(int[][].class);

    /**
     * Serializes the computation of an order.
     */
    private static final Object NATURAL_LOCK = new Object();

    /**
     * The custom orders of each order and channel, or {@code null} for the natural order.
     */
    private final int[][][] orders;

    /**
     * The entropy decoder of the HF coefficients.
     */
    private final EntropyDecoder decoder;

    /**
     * Creates a pass.
     *
     * @param orders the custom orders
     * @param decoder the entropy decoder
     */
    private HfPass(int[][][] orders, EntropyDecoder decoder) {
        this.orders = orders;
        this.decoder = decoder;
    }

    /**
     * Reads a pass.
     *
     * @param br the bitstream
     * @param numPresets the number of HF presets
     * @param numBlockClusters the number of block contexts
     * @return the pass
     * @throws MediaException.Malformed if a permutation is invalid
     */
    public static HfPass read(JxlBitReader br, int numPresets, int numBlockClusters) {
        var used = br.readU32(0x5F, 0, 0x13, 0, 0, 0, 0, 13);
        var orders = new int[13][][];
        if (used != 0) {
            var decoder = EntropyDecoder.read(br, 8);
            for (var order = 0; order < 13; order++) {
                if ((used & (1 << order)) == 0) {
                    continue;
                }
                var size = BLOCK_SIZES[order * 2] * BLOCK_SIZES[order * 2 + 1];
                var natural = natural(order);
                orders[order] = new int[3][];
                for (var c = 0; c < 3; c++) {
                    var permutation = Permutations.read(br, decoder, size, size / 64);
                    var out = new int[size];
                    for (var i = 0; i < size; i++) {
                        out[i] = natural[permutation[i]];
                    }
                    orders[order][c] = out;
                }
            }
            decoder.checkFinalState();
        }
        var contexts = (long) 495 * numPresets * numBlockClusters;
        if (contexts > 1 << 24) {
            throw new MediaException.Malformed("too many HF contexts");
        }
        var hfDecoder = EntropyDecoder.read(br, (int) contexts);
        return new HfPass(orders, hfDecoder);
    }

    /**
     * Returns the coefficient order of a block shape and channel.
     *
     * @param order the order index
     * @param channel the channel, 0 for X, 1 for Y, 2 for B
     * @return the positions, as {@code x | y << 16}
     */
    public int[] order(int order, int channel) {
        var custom = orders[order];
        return custom != null ? custom[channel] : natural(order);
    }

    /**
     * Returns the HF entropy decoder template.
     *
     * @return the decoder, to be copied per group
     */
    public EntropyDecoder decoder() {
        return decoder;
    }

    /**
     * Returns the natural order of an 8x8 block, the zig-zag order of JPEG.
     *
     * @return the positions, as {@code x | y << 16}; a copy
     */
    public static int[] dct8NaturalOrder() {
        return natural(0).clone();
    }

    /**
     * Returns the natural order of a block shape.
     *
     * @param order the order index
     * @return the positions, as {@code x | y << 16}
     */
    static int[] natural(int order) {
        var cached = (int[]) NATURAL_SLOT.getAcquire(NATURAL, order);
        if (cached != null) {
            return cached;
        }
        synchronized (NATURAL_LOCK) {
            cached = (int[]) NATURAL_SLOT.getAcquire(NATURAL, order);
            if (cached == null) {
                cached = computeNatural(BLOCK_SIZES[order * 2], BLOCK_SIZES[order * 2 + 1]);
                NATURAL_SLOT.setRelease(NATURAL, order, cached);
            }
            return cached;
        }
    }

    /**
     * Computes the natural order of a block shape: the low-frequency coefficients in raster
     * order, then the others by anti-diagonals.
     *
     * @param bw the width, at least the height
     * @param bh the height
     * @return the positions, as {@code x | y << 16}
     */
    private static int[] computeNatural(int bw, int bh) {
        var out = new int[bw * bh];
        var yScale = bw / bh;
        var lbw = bw / 8;
        var lbh = bh / 8;
        var idx = 0;
        while (idx < lbw * lbh) {
            out[idx] = (idx % lbw) | ((idx / lbw) << 16);
            idx++;
        }
        for (var dist = 1; dist < 2 * bw; dist++) {
            var margin = Math.max(dist - bw, 0);
            for (var order = margin; order < dist - margin; order++) {
                int x;
                int y;
                if (dist % 2 == 1) {
                    x = order;
                    y = dist - 1 - order;
                } else {
                    x = dist - 1 - order;
                    y = order;
                }
                if (x < lbw && y < lbw) {
                    continue;
                }
                if (y % yScale != 0) {
                    continue;
                }
                out[idx++] = x | ((y / yScale) << 16);
            }
        }
        return out;
    }
}
