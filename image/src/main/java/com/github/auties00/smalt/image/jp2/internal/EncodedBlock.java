package com.github.auties00.smalt.image.jp2.internal;

import java.util.Arrays;

/**
 * Holds one code-block of a tile being encoded: its coded bytes, its coding passes, the passes
 * each quality layer includes, and the packet header state of its precinct's packets.
 *
 * @implNote This implementation follows OpenJPEG's {@code opj_tcd_cblk_enc_t},
 * {@code opj_tcd_pass_t} and {@code opj_tcd_layer_t}, with the pass and layer fields held in
 * parallel arrays.
 */
final class EncodedBlock {
    /**
     * The left edge in sub-band coordinates.
     */
    final int x0;

    /**
     * The top edge in sub-band coordinates.
     */
    final int y0;

    /**
     * The right edge in sub-band coordinates (exclusive).
     */
    final int x1;

    /**
     * The bottom edge in sub-band coordinates (exclusive).
     */
    final int y1;

    /**
     * The number of magnitude bit-planes coded; zero or negative when the block is all zero.
     */
    int bitPlanes;

    /**
     * The number of coding passes.
     */
    int totalPasses;

    /**
     * The coded bytes of every pass.
     */
    byte[] data = new byte[0];

    /**
     * The cumulative byte count after each pass.
     */
    int[] passRate = new int[0];

    /**
     * The cumulative weighted distortion reduction after each pass.
     */
    double[] passDistortion = new double[0];

    /**
     * Whether each pass ends with a termination of the coder.
     */
    boolean[] passTerminated = new boolean[0];

    /**
     * The number of passes each layer adds.
     */
    int[] layerPasses = new int[0];

    /**
     * The number of bytes each layer adds.
     */
    int[] layerLength = new int[0];

    /**
     * The offset in {@link #data} of each layer's bytes.
     */
    int[] layerOffset = new int[0];

    /**
     * The distortion reduction each layer adds.
     */
    double[] layerDistortion = new double[0];

    /**
     * The number of passes the layers allocated so far include.
     */
    int passesInLayers;

    /**
     * The number of passes the packets written so far include.
     */
    int writtenPasses;

    /**
     * The number of bits of the next length indicator, before the pass count adjustment.
     */
    int lengthBits;

    /**
     * The number of rate-distortion candidates of a high-throughput block: the HT sets it can be coded with.
     */
    int candidates;

    /**
     * The cleanup pass's bit-plane of each candidate.
     */
    int[] candidatePlane = new int[0];

    /**
     * The number of passes of each candidate, 1 or 3.
     */
    int[] candidatePasses = new int[0];

    /**
     * The cumulative byte count after each pass of each candidate, three entries per candidate.
     */
    int[] candidateRate = new int[0];

    /**
     * The cumulative weighted distortion reduction after each pass of each candidate, three entries per
     * candidate.
     */
    double[] candidateDistortion = new double[0];

    /**
     * The candidates on the lower convex hull of the block's rate-distortion points, by increasing rate.
     */
    int[] hull = new int[0];

    /**
     * The number of entries used in {@link #hull}.
     */
    int hullSize;

    /**
     * The candidates sorted by rate, while {@link #buildHull()} runs.
     */
    private int[] candidateOrder = new int[0];

    /**
     * The chosen candidate, or {@code -1} when the block is left out.
     */
    int chosen = -1;

    /**
     * Constructs a code-block.
     *
     * @param x0 the left edge
     * @param y0 the top edge
     * @param x1 the right edge
     * @param y1 the bottom edge
     */
    EncodedBlock(int x0, int y0, int x1, int y1) {
        this.x0 = x0;
        this.y0 = y0;
        this.x1 = x1;
        this.y1 = y1;
    }

    /**
     * Makes room for a number of passes.
     *
     * @param passes the largest number of passes
     */
    void ensurePasses(int passes) {
        if (passRate.length < passes) {
            passRate = Arrays.copyOf(passRate, passes);
            passDistortion = Arrays.copyOf(passDistortion, passes);
            passTerminated = Arrays.copyOf(passTerminated, passes);
        }
    }

    /**
     * Makes room for a number of layers.
     *
     * @param layers the number of layers
     */
    void ensureLayers(int layers) {
        if (layerPasses.length < layers) {
            layerPasses = new int[layers];
            layerLength = new int[layers];
            layerOffset = new int[layers];
            layerDistortion = new double[layers];
        }
    }

    /**
     * Makes room for a number of candidates, discarding the previous ones.
     *
     * @param count the number of candidates
     */
    void resetCandidates(int count) {
        if (candidatePlane.length < count) {
            candidatePlane = new int[count];
            candidatePasses = new int[count];
            candidateRate = new int[3 * count];
            candidateDistortion = new double[3 * count];
            hull = new int[count + 1];
            candidateOrder = new int[count];
        }
        candidates = 0;
        hullSize = 0;
        chosen = -1;
    }

    /**
     * Records a candidate.
     *
     * @param plane the cleanup pass's bit-plane
     * @param passes the number of passes, 1 or 3
     * @param rates the cumulative byte count after each pass
     * @param distortions the cumulative distortion reduction after each pass
     */
    void addCandidate(int plane, int passes, int[] rates, double[] distortions) {
        var c = candidates++;
        candidatePlane[c] = plane;
        candidatePasses[c] = passes;
        for (var p = 0; p < 3; p++) {
            candidateRate[3 * c + p] = rates[p];
            candidateDistortion[3 * c + p] = distortions[p];
        }
    }

    /**
     * Returns the byte count of a candidate.
     *
     * @param candidate the candidate index
     * @return the bytes of all its passes
     */
    int candidateBytes(int candidate) {
        return candidateRate[3 * candidate + candidatePasses[candidate] - 1];
    }

    /**
     * Returns the distortion reduction of a candidate.
     *
     * @param candidate the candidate index
     * @return the reduction of all its passes
     */
    double candidateGain(int candidate) {
        return candidateDistortion[3 * candidate + candidatePasses[candidate] - 1];
    }

    /**
     * Builds the lower convex hull of the candidates' rate-distortion points, starting from the empty block.
     */
    void buildHull() {
        hullSize = 0;
        var order = candidateOrder;
        for (var i = 0; i < candidates; i++) {
            var j = i;
            while (j > 0 && precedes(i, order[j - 1])) {
                order[j] = order[j - 1];
                j--;
            }
            order[j] = i;
        }
        for (var k = 0; k < candidates; k++) {
            var c = order[k];
            var rate = candidateBytes(c);
            var gain = candidateGain(c);
            if (gain <= (hullSize == 0 ? 0 : candidateGain(hull[hullSize - 1]))) {
                continue;
            }
            while (hullSize > 0) {
                var last = hull[hullSize - 1];
                var previousRate = hullSize > 1 ? candidateBytes(hull[hullSize - 2]) : 0;
                var previousGain = hullSize > 1 ? candidateGain(hull[hullSize - 2]) : 0;
                var lastSlope = (candidateGain(last) - previousGain) / (candidateBytes(last) - previousRate);
                var slope = (gain - previousGain) / (rate - previousRate);
                if (slope >= lastSlope) {
                    hullSize--;
                } else {
                    break;
                }
            }
            hull[hullSize++] = c;
        }
    }

    /**
     * Returns whether a candidate comes before another in rate order, the larger gain first at equal rates.
     *
     * @param a the first candidate
     * @param b the second candidate
     * @return whether {@code a} precedes {@code b}
     */
    private boolean precedes(int a, int b) {
        var byRate = Integer.compare(candidateBytes(a), candidateBytes(b));
        return byRate != 0 ? byRate < 0 : candidateGain(a) > candidateGain(b);
    }

    /**
     * Returns the hull candidate the slope threshold selects: the last one whose slope from its predecessor is
     * at least the threshold.
     *
     * @param threshold the slope threshold
     * @return the candidate, or {@code -1} for none
     */
    int select(double threshold) {
        var position = hullPosition(threshold);
        return position < 0 ? -1 : hull[position];
    }

    /**
     * Returns the position on the hull of the candidate {@link #select} returns.
     *
     * @param threshold the slope threshold
     * @return the position, or {@code -1} for none
     */
    int hullPosition(double threshold) {
        var position = -1;
        var previousRate = 0;
        var previousGain = 0.0;
        for (var i = 0; i < hullSize; i++) {
            var c = hull[i];
            var slope = (candidateGain(c) - previousGain) / (candidateBytes(c) - previousRate);
            if (slope < threshold) {
                break;
            }
            position = i;
            previousRate = candidateBytes(c);
            previousGain = candidateGain(c);
        }
        return position;
    }

    /**
     * Returns the number of bytes of a pass.
     *
     * @param pass the pass index, from 0
     * @return the bytes it adds to the previous passes
     */
    int passLength(int pass) {
        return passRate[pass] - (pass == 0 ? 0 : passRate[pass - 1]);
    }
}
