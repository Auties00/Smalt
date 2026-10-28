package com.github.auties00.smalt.video.h264.encoder.internal;

import java.util.Arrays;
import java.util.List;

/**
 * Estimates how much of each macroblock of a picture the following pictures inherit, and turns
 * it into a quantizer offset.
 *
 * <p>A macroblock that later pictures copy, by motion compensation, is worth coding better than
 * its own cost suggests, because every sample of it that survives is paid for once and reused.
 * The estimate walks the look-ahead pictures from the last to the first. A macroblock of a later
 * picture whose inter cost is a fraction {@code c} of its intra cost inherits the fraction
 * {@code 1 - c} of its information from the reference it points at; that part of its own cost
 * plus the cost it received from still later pictures is added to the macroblocks of the
 * previous picture its vector overlaps, in proportion to the overlapping areas. The offset of a
 * macroblock of the first picture is then {@code -strength * log2(1 + received / intra)}.
 */
final class MacroblockTree {
    /**
     * The picture width in macroblocks.
     */
    private final int mbWidth;

    /**
     * The picture height in macroblocks.
     */
    private final int mbHeight;

    /**
     * The cost the macroblocks of the picture being processed received from later pictures.
     */
    private float[] incoming;

    /**
     * The cost the macroblocks of the previous picture receive.
     */
    private float[] outgoing;

    /**
     * Constructs the estimator.
     *
     * @param mbWidth the picture width in macroblocks
     * @param mbHeight the picture height in macroblocks
     */
    MacroblockTree(int mbWidth, int mbHeight) {
        this.mbWidth = mbWidth;
        this.mbHeight = mbHeight;
        this.incoming = new float[mbWidth * mbHeight];
        this.outgoing = new float[mbWidth * mbHeight];
    }

    /**
     * Computes the quantizer offset of every macroblock of the first picture.
     *
     * @param frames the look-ahead pictures in display order, the first being the one to code
     * @param count the number of pictures that may propagate to the first one, at least 1
     * @param cuts for each picture after the first, whether it starts a new group of pictures
     * and so references none of the previous ones
     * @param strength the quantizer steps per doubling of the cost a macroblock carries
     * @param offsets receives the offset of each macroblock, zero or negative
     */
    void compute(List<LookaheadFrame> frames, int count, boolean[] cuts, double strength, float[] offsets) {
        Arrays.fill(incoming, 0f);
        for (var j = count - 1; j >= 1; j--) {
            if (cuts[j]) {
                Arrays.fill(incoming, 0f);
                continue;
            }
            Arrays.fill(outgoing, 0f);
            propagate(frames.get(j), incoming, outgoing);
            var swap = incoming;
            incoming = outgoing;
            outgoing = swap;
        }
        var intra = frames.get(0).intraCost;
        var scale = (float) (strength / StrictMath.log(2));
        for (var m = 0; m < offsets.length; m++) {
            var cost = Math.max(1, intra[m]);
            offsets[m] = -scale * (float) StrictMath.log(1 + incoming[m] / cost);
        }
    }

    /**
     * Passes the cost of one picture's macroblocks on to the previous picture.
     *
     * @param frame the picture
     * @param received the cost its macroblocks received from later pictures
     * @param target receives the cost for the previous picture's macroblocks
     */
    private void propagate(LookaheadFrame frame, float[] received, float[] target) {
        var intra = frame.intraCost;
        var inter = frame.interCost;
        var vectors = frame.vectors;
        for (var mbY = 0; mbY < mbHeight; mbY++) {
            for (var mbX = 0; mbX < mbWidth; mbX++) {
                var m = mbY * mbWidth + mbX;
                var intraCost = Math.max(1, intra[m]);
                var interCost = Math.min(inter[m], intraCost);
                if (interCost >= intraCost) {
                    continue;
                }
                var amount = (intraCost + received[m]) * (intraCost - interCost) / intraCost;
                var v = vectors[m];
                var px = mbX * 8 + MotionPredictor.x(v);
                var py = mbY * 8 + MotionPredictor.y(v);
                var x0 = Math.floorDiv(px, 8);
                var y0 = Math.floorDiv(py, 8);
                var fx = px - x0 * 8;
                var fy = py - y0 * 8;
                for (var k = 0; k < 4; k++) {
                    var x = x0 + (k & 1);
                    var y = y0 + (k >> 1);
                    if (x < 0 || y < 0 || x >= mbWidth || y >= mbHeight) {
                        continue;
                    }
                    var wx = (k & 1) == 0 ? 8 - fx : fx;
                    var wy = (k >> 1) == 0 ? 8 - fy : fy;
                    if (wx != 0 && wy != 0) {
                        target[y * mbWidth + x] += amount * wx * wy * (1f / 64);
                    }
                }
            }
        }
    }
}
