/* Ports libvpx 1.17.0 (vp8/encoder/bitstream.c, encodemv.c). Copyright (c) 2010 The WebM project authors; BSD 3-Clause
   License. Modified for Smalt. */
package com.github.auties00.smalt.video.vp8.internal;

import com.github.auties00.smalt.MediaException;

import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.B_DC_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.B_HE_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.B_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.B_TM_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.B_VE_PRED;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.GOLDEN_FRAME;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.INTRA_FRAME;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.LAST_FRAME;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.NEARESTMV;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.NEARMV;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.NEWMV;
import static com.github.auties00.smalt.video.vp8.internal.Vp8FrameDecoder.ZEROMV;

/**
 * Packs a frame of the real-time VP8 encoder into its bitstream (RFC 6386 sections 9 and 19): the
 * frame header, the first partition with the probability updates and the macroblock modes, and
 * the token partitions.
 *
 * <p>Probability updates are chosen when the bits they save in this frame exceed their cost:
 * token probabilities from the branch counts of the frame's tokens, mode and motion vector
 * probabilities from the frame's mode and vector counts. When the frame's entropy updates last
 * one frame, the probabilities they replace are saved first.
 *
 * @implNote This implementation codes with the key-frame encoder's {@link BoolEncoder}, whose
 * padding at the end of a partition differs from libvpx's but decodes identically.
 */
final class RtBitstream {
    /**
     * The subblock context implied by each whole-block luma mode in key frames.
     */
    private static final int[] BMODE_OF_MODE = {B_DC_PRED, B_VE_PRED, B_HE_PRED, B_TM_PRED, B_DC_PRED};

    /**
     * The encoder whose frames are packed.
     */
    private final RtEncoder enc;

    /**
     * The writer of the first partition.
     */
    private final BoolEncoder header;

    /**
     * The writers of the token partitions.
     */
    private final BoolEncoder[] parts;

    /**
     * The probability of a macroblock with coefficients of the frame being packed.
     */
    private int probSkipFalse;

    /**
     * The reference counts of the macroblock being packed.
     */
    private final int[] counts = new int[4];

    /**
     * The best, nearest and near vectors of the macroblock being packed.
     */
    private final int[] near = new int[3];

    /**
     * The branch counts of a mode tree.
     */
    private final int[] branchCounts = new int[2 * 9];

    /**
     * The new probabilities of a mode tree.
     */
    private final int[] newProbs = new int[9];

    /**
     * The counts of each bit value of the long motion vector magnitudes,
     * {@code [bit 10][value 2]}.
     */
    private final long[] mvBitCounts = new long[20];

    /**
     * The counts of the short motion vector magnitudes.
     */
    private final int[] mvShortCounts = new int[8];

    /**
     * Creates the packer of an encoder.
     *
     * @param enc the encoder
     */
    RtBitstream(RtEncoder enc) {
        this.enc = enc;
        this.header = new BoolEncoder(enc.mbs * 4);
        this.parts = new BoolEncoder[enc.partitions];
        for (var i = 0; i < parts.length; i++) {
            parts[i] = new BoolEncoder(enc.mbs * 16 / parts.length);
        }
    }

    /**
     * Packs the frame the encoder just coded.
     *
     * @return the frame
     * @throws MediaException.Unsupported if a partition exceeds the size the format allows
     */
    byte[] pack() {
        packStart();
        packModes();
        packTokens();
        return assemble();
    }

    /**
     * Writes the start of the first partition, up to and including the token probability
     * updates, which the token partitions are coded with.
     */
    void packStart() {
        var bw = header;
        bw.reset();
        var key = enc.keyFrame;
        if (key) {
            bw.putBitUniform(0);
            bw.putBitUniform(0);
        }
        writeSegmentation(bw);
        bw.putBitUniform(enc.speed >= 14 ? 1 : 0);
        bw.putBits(enc.filterLevel, 6);
        bw.putBits(0, 3);
        writeLoopFilterDeltas(bw);
        bw.putBits(Integer.numberOfTrailingZeros(enc.partitions), 2);
        bw.putBits(enc.baseQ, 7);
        bw.putBitUniform(0);
        putDeltaQ(bw, enc.y2DcDelta);
        bw.putBitUniform(0);
        bw.putBitUniform(0);
        bw.putBitUniform(0);
        if (!key) {
            bw.putBitUniform(enc.refreshGolden ? 1 : 0);
            bw.putBitUniform(enc.refreshAlt ? 1 : 0);
            if (!enc.refreshGolden) {
                bw.putBits(0, 2);
            }
            if (!enc.refreshAlt) {
                bw.putBits(enc.copyToAlt, 2);
            }
            bw.putBitUniform(0);
            bw.putBitUniform(0);
        }
        bw.putBitUniform(enc.refreshEntropy ? 1 : 0);
        if (!key) {
            bw.putBitUniform(enc.refreshLast ? 1 : 0);
        }
        if (!enc.refreshEntropy) {
            enc.saveEntropy();
        }
        updateCoefProbs(bw);
    }

    /**
     * Writes the rest of the first partition, the macroblock modes and vectors, after
     * {@link #packStart}; it may run concurrently with {@link #packTokens}.
     */
    void packModes() {
        var bw = header;
        var key = enc.keyFrame;
        bw.putBitUniform(1);
        var mbs = enc.mbs;
        probSkipFalse = Math.clamp((mbs - enc.stats.skipTrueCount) * 256L / mbs, 1, 255);
        if (key) {
            bw.putBits(probSkipFalse, 8);
            writeKeyFrameModes(bw);
        } else {
            writeInterFrameModes(bw);
        }
        bw.finish();
    }

    /**
     * Codes the tokens of every macroblock row into the token partitions, after
     * {@link #packStart}.
     */
    void packTokens() {
        for (var p = 0; p < parts.length; p++) {
            packPartition(p);
        }
    }

    /**
     * Codes the tokens of the macroblock rows of one token partition, after {@link #packStart};
     * it may run concurrently with {@link #packModes} and with the other partitions.
     *
     * @param partition the partition: rows {@code r} with {@code r % partitions == partition}
     */
    void packPartition(int partition) {
        var n = parts.length;
        var bw = parts[partition];
        bw.reset();
        var rows = enc.rows;
        for (var row = partition; row < rows.length; row += n) {
            rows[row].tokens.emit(bw, enc.coefProbs);
        }
        bw.finish();
    }

    /**
     * Assembles the frame tag, the first partition, the partition sizes and the token
     * partitions written by {@link #packStart}, {@link #packModes} and {@link #packTokens}.
     *
     * @return the frame
     * @throws MediaException.Unsupported if a partition exceeds the size the format allows
     */
    byte[] assemble() {
        return BoolEncoder.assembleFrame(enc.keyFrame, 0, enc.width, enc.height, header, parts);
    }

    /**
     * Writes the segmentation header: the cyclic refresh's quantizer deltas and the segment
     * tree probabilities.
     *
     * @param bw the first partition writer
     */
    private void writeSegmentation(BoolEncoder bw) {
        if (bw.putBitUniform(enc.segmentationEnabled ? 1 : 0) == 0) {
            return;
        }
        bw.putBitUniform(1);
        bw.putBitUniform(1);
        bw.putBitUniform(0);
        for (var s = 0; s < 4; s++) {
            var data = enc.segmentQuant[s];
            if (data != 0) {
                bw.putBitUniform(1);
                bw.putBits(Math.abs(data), 7);
                bw.putBitUniform(data < 0 ? 1 : 0);
            } else {
                bw.putBitUniform(0);
            }
        }
        for (var s = 0; s < 4; s++) {
            bw.putBitUniform(0);
        }
        for (var i = 0; i < 3; i++) {
            var prob = enc.segmentTreeProbs[i];
            if (prob != 255) {
                bw.putBitUniform(1);
                bw.putBits(prob, 8);
            } else {
                bw.putBitUniform(0);
            }
        }
    }

    /**
     * Writes the loop filter deltas of the references and mode classes, every one of them in
     * error resilient mode and the changed ones otherwise, libvpx's encoding of
     * {@code mode_ref_lf_delta_update}.
     *
     * @param bw the first partition writer
     */
    private void writeLoopFilterDeltas(BoolEncoder bw) {
        bw.putBitUniform(1);
        var errorResilient = enc.errorResilient;
        var send = enc.lfDeltaUpdate || errorResilient;
        bw.putBitUniform(send ? 1 : 0);
        if (!send) {
            return;
        }
        writeDeltas(bw, RtEncoder.REF_LF_DELTAS, enc.lastRefLfDeltas, errorResilient);
        writeDeltas(bw, RtEncoder.MODE_LF_DELTAS, enc.lastModeLfDeltas, errorResilient);
    }

    /**
     * Writes four loop filter deltas that changed since the decoder last received them.
     *
     * @param bw the first partition writer
     * @param deltas the deltas of the frame
     * @param last the deltas the decoder holds, updated
     * @param all whether to write every delta
     */
    private static void writeDeltas(BoolEncoder bw, int[] deltas, int[] last, boolean all) {
        for (var i = 0; i < 4; i++) {
            var data = deltas[i];
            if (data != last[i] || all) {
                last[i] = data;
                bw.putBitUniform(1);
                if (data > 0) {
                    bw.putBits(data & 0x3F, 6);
                    bw.putBitUniform(0);
                } else {
                    bw.putBits(-data & 0x3F, 6);
                    bw.putBitUniform(1);
                }
            } else {
                bw.putBitUniform(0);
            }
        }
    }

    /**
     * Writes one quantizer delta, libvpx's {@code put_delta_q}.
     *
     * @param bw    the first partition writer
     * @param delta the delta, {@code [-15, 15]}
     */
    private static void putDeltaQ(BoolEncoder bw, int delta) {
        if (delta != 0) {
            bw.putBitUniform(1);
            bw.putBits(Math.abs(delta), 4);
            bw.putBitUniform(delta < 0 ? 1 : 0);
        } else {
            bw.putBitUniform(0);
        }
    }

    /**
     * Returns the cost of coding a branch's counts with a probability, libvpx's
     * {@code vp8_cost_branch}.
     *
     * @param zeros the number of zeros
     * @param ones  the number of ones
     * @param prob  the probability of a zero
     * @return the cost in bits
     */
    private static long branchCost(long zeros, long ones, int prob) {
        return (zeros * RtTables.bitCost(prob, 0) + ones * RtTables.bitCost(prob, 1)) >> 8;
    }

    /**
     * Returns the probability of a zero estimated from counts, libvpx's
     * {@code vp8_tree_probs_from_distribution} node estimate.
     *
     * @param zeros the number of zeros
     * @param ones  the number of ones
     * @return the probability, {@code [1, 255]}, or {@code 128} without counts
     */
    private static int estimateProb(long zeros, long ones) {
        var total = zeros + ones;
        if (total == 0) {
            return 128;
        }
        var p = (zeros * 256 + (total >> 1)) / total;
        return p < 256 ? (p != 0 ? (int) p : 1) : 255;
    }

    /**
     * Chooses and writes the token probability updates, libvpx's
     * {@code vp8_update_coef_probs} without independent partitions.
     *
     * @param bw the first partition writer
     */
    private void updateCoefProbs(BoolEncoder bw) {
        var counts = enc.stats.coefCounts;
        var probs = enc.coefProbs;
        for (var i = 0; i < 1056; i++) {
            long zeros = counts[2 * i];
            long ones = counts[2 * i + 1];
            var newP = estimateProb(zeros, ones);
            var oldP = probs[i];
            var upd = Vp8Tables.COEF_UPDATE_PROBS[i];
            var updateBits = 8 + ((RtTables.bitCost(upd, 1) - RtTables.bitCost(upd, 0)) >> 8);
            var savings = branchCost(zeros, ones, oldP) - branchCost(zeros, ones, newP) - updateBits;
            var update = savings > 0 ? 1 : 0;
            bw.putBit(update, upd);
            if (update != 0) {
                probs[i] = newP;
                bw.putBits(newP, 8);
            }
        }
    }

    /**
     * Writes the segment, skip flag and intra modes of every key-frame macroblock, libvpx's
     * {@code write_kfmodes}.
     *
     * @param bw the first partition writer
     */
    private void writeKeyFrameModes(BoolEncoder bw) {
        var miStride = enc.miStride;
        for (var row = 0; row < enc.mbRows; row++) {
            for (var col = 0; col < enc.mbCols; col++) {
                var idx = (row + 1) * miStride + col + 1;
                writeSegment(bw, idx);
                bw.putBit(enc.mbSkip[idx], probSkipFalse);
                var mode = enc.mbMode[idx];
                RtTables.writeTree(bw, RtTables.KF_YMODE_CODES, Vp8Tables.KF_YMODE_TREE, Vp8Tables.KF_YMODE_PROBS, 0, mode);
                if (mode == B_PRED) {
                    var base = idx * 16;
                    for (var i = 0; i < 16; i++) {
                        int a;
                        if (i < 4) {
                            var above = idx - miStride;
                            var am = enc.mbMode[above];
                            a = am == B_PRED ? enc.bModes[above * 16 + i + 12] : BMODE_OF_MODE[am];
                        } else {
                            a = enc.bModes[base + i - 4];
                        }
                        int l;
                        if ((i & 3) == 0) {
                            var left = idx - 1;
                            var lm = enc.mbMode[left];
                            l = lm == B_PRED ? enc.bModes[left * 16 + i + 3] : BMODE_OF_MODE[lm];
                        } else {
                            l = enc.bModes[base + i - 1];
                        }
                        RtTables.writeTree(bw, RtTables.BMODE_CODES, Vp8Tables.BMODE_TREE, Vp8Tables.KF_BMODE_PROBS,
                                (a * 10 + l) * 9, enc.bModes[base + i]);
                    }
                }
                RtTables.writeTree(bw, RtTables.UV_MODE_CODES, Vp8Tables.UV_MODE_TREE, Vp8Tables.KF_UV_MODE_PROBS, 0,
                        enc.mbUvMode[idx]);
            }
        }
    }

    /**
     * Writes a macroblock's segment when the frame updates the segment map.
     *
     * @param bw  the first partition writer
     * @param idx the macroblock's mode info index
     */
    private void writeSegment(BoolEncoder bw, int idx) {
        if (!enc.segmentationEnabled) {
            return;
        }
        var probs = enc.segmentTreeProbs;
        var segment = enc.mbSegment[idx];
        if (bw.putBit(segment >= 2 ? 1 : 0, probs[0]) != 0) {
            bw.putBit(segment & 1, probs[2]);
        } else {
            bw.putBit(segment & 1, probs[1]);
        }
    }

    /**
     * Writes the reference probabilities, the mode and motion vector probability updates, and
     * the segment, skip flag, reference, modes and motion vector of every inter-frame
     * macroblock, libvpx's {@code pack_inter_mode_mvs}.
     *
     * @param bw the first partition writer
     */
    private void writeInterFrameModes(BoolEncoder bw) {
        var rfct = enc.stats.refUsage;
        var rfIntra = rfct[INTRA_FRAME];
        var rfInter = rfct[LAST_FRAME] + rfct[GOLDEN_FRAME] + rfct[Vp8FrameDecoder.ALTREF_FRAME];
        var probIntra = rfIntra * 255 / Math.max(1, rfIntra + rfInter);
        enc.probIntraCoded = probIntra == 0 ? 1 : probIntra;
        var probLast = rfInter != 0 ? rfct[LAST_FRAME] * 255 / rfInter : 128;
        enc.probLastCoded = probLast == 0 ? 1 : probLast;
        var golden = rfct[GOLDEN_FRAME] + rfct[Vp8FrameDecoder.ALTREF_FRAME];
        var probGf = golden != 0 ? rfct[GOLDEN_FRAME] * 255 / golden : 128;
        enc.probGfCoded = probGf == 0 ? 1 : probGf;
        bw.putBits(probSkipFalse, 8);
        bw.putBits(enc.probIntraCoded, 8);
        bw.putBits(enc.probLastCoded, 8);
        bw.putBits(enc.probGfCoded, 8);
        updateModeProbs(bw, Vp8Tables.YMODE_TREE, 5, enc.stats.ymodeCounts, enc.ymodeProbs);
        updateModeProbs(bw, Vp8Tables.UV_MODE_TREE, 4, enc.stats.uvModeCounts, enc.uvModeProbs);
        var mvUpdated = writeMvProbs(bw, 0) | writeMvProbs(bw, 1);
        if (mvUpdated) {
            enc.invalidateMvCosts();
        }
        var miStride = enc.miStride;
        var mc = Vp8Tables.MODE_CONTEXTS;
        for (var row = 0; row < enc.mbRows; row++) {
            for (var col = 0; col < enc.mbCols; col++) {
                var idx = (row + 1) * miStride + col + 1;
                writeSegment(bw, idx);
                bw.putBit(enc.mbSkip[idx], probSkipFalse);
                var ref = enc.mbRef[idx];
                var mode = enc.mbMode[idx];
                if (ref == INTRA_FRAME) {
                    bw.putBit(0, enc.probIntraCoded);
                    RtTables.writeTree(bw, RtTables.YMODE_CODES, Vp8Tables.YMODE_TREE, enc.ymodeProbs, 0, mode);
                    if (mode == B_PRED) {
                        var base = idx * 16;
                        for (var i = 0; i < 16; i++) {
                            RtTables.writeTree(bw, RtTables.BMODE_CODES, Vp8Tables.BMODE_TREE, Vp8Tables.BMODE_PROBS, 0,
                                    enc.bModes[base + i]);
                        }
                    }
                    RtTables.writeTree(bw, RtTables.UV_MODE_CODES, Vp8Tables.UV_MODE_TREE, enc.uvModeProbs, 0,
                            enc.mbUvMode[idx]);
                    continue;
                }
                bw.putBit(1, enc.probIntraCoded);
                if (ref == LAST_FRAME) {
                    bw.putBit(0, enc.probLastCoded);
                } else {
                    bw.putBit(1, enc.probLastCoded);
                    bw.putBit(ref == GOLDEN_FRAME ? 0 : 1, enc.probGfCoded);
                }
                var best = nearMvs(idx, row, col);
                var cnt = counts;
                if (bw.putBit(mode != ZEROMV ? 1 : 0, mc[cnt[0] * 4]) == 0) {
                    continue;
                }
                if (bw.putBit(mode != NEARESTMV ? 1 : 0, mc[cnt[1] * 4 + 1]) == 0) {
                    continue;
                }
                if (bw.putBit(mode != NEARMV ? 1 : 0, mc[cnt[2] * 4 + 2]) == 0) {
                    continue;
                }
                bw.putBit(0, mc[cnt[3] * 4 + 3]);
                if (mode == NEWMV) {
                    var mv = enc.mbMv[idx];
                    writeMvComponent(bw, (Mv.row(mv) - Mv.row(best)) >> 1, 0);
                    writeMvComponent(bw, (Mv.col(mv) - Mv.col(best)) >> 1, 19);
                }
            }
        }
    }

    /**
     * Computes the neighbor counts of a macroblock into {@link #counts} and returns its best
     * reference vector, clamped, libvpx's {@code vp8_find_near_mvs} followed by
     * {@code vp8_clamp_mv2}.
     *
     * @param idx the macroblock's mode info index
     * @param row the macroblock row
     * @param col the macroblock column
     * @return the packed best reference vector
     */
    private int nearMvs(int idx, int row, int col) {
        NearMvs.find(enc.mbRef, enc.mbMv, idx, enc.miStride, counts, near);
        return Mv.clamp(near[0], -((col * 16) << 3), ((enc.mbCols - 1 - col) * 16) << 3, -((row * 16) << 3),
                ((enc.mbRows - 1 - row) * 16) << 3);
    }

    /**
     * Writes one motion vector component, libvpx's {@code encode_mvcomponent}.
     *
     * @param bw   the first partition writer
     * @param v    the component in quarter pixels
     * @param base the index of the component's probabilities in the encoder's motion vector
     *             probabilities
     */
    private void writeMvComponent(BoolEncoder bw, int v, int base) {
        var p = enc.mvProbs;
        var x = Math.abs(v);
        if (x < 8) {
            bw.putBit(0, p[base]);
            RtTables.writeTree(bw, RtTables.SMALL_MV_CODES, Vp8Tables.SMALL_MV_TREE, p, base + 2, x);
            if (x == 0) {
                return;
            }
        } else {
            bw.putBit(1, p[base]);
            for (var i = 0; i < 3; i++) {
                bw.putBit((x >> i) & 1, p[base + 9 + i]);
            }
            for (var i = 9; i > 3; i--) {
                bw.putBit((x >> i) & 1, p[base + 9 + i]);
            }
            if ((x & 0xFFF0) != 0) {
                bw.putBit((x >> 3) & 1, p[base + 9 + 3]);
            }
        }
        bw.putBit(v < 0 ? 1 : 0, p[base + 1]);
    }

    /**
     * Computes the branch counts of a tree from its symbol counts.
     *
     * @param tree   the tree in libvpx layout
     * @param events the number of occurrences of each symbol
     * @param node   the index of the node's first branch
     * @return the number of occurrences below the node
     */
    private int countBranches(int[] tree, int[] events, int node) {
        var total = 0;
        for (var bit = 0; bit < 2; bit++) {
            var next = tree[node + bit];
            var count = next > 0 ? countBranches(tree, events, next) : events[-next];
            branchCounts[node + bit] = count;
            total += count;
        }
        return total;
    }

    /**
     * Chooses and writes the update of a mode tree's probabilities, libvpx's
     * {@code update_mode}.
     *
     * @param bw the first partition writer
     * @param tree the mode tree, in libvpx layout
     * @param symbols the number of symbols
     * @param events the number of macroblocks coded with each symbol
     * @param current the current probabilities, updated
     */
    private void updateModeProbs(BoolEncoder bw, int[] tree, int symbols, int[] events, int[] current) {
        countBranches(tree, events, 0);
        var nodes = symbols - 1;
        long newBits = 0;
        long oldBits = 0;
        for (var i = 0; i < nodes; i++) {
            long zeros = branchCounts[2 * i];
            long ones = branchCounts[2 * i + 1];
            newProbs[i] = estimateProb(zeros, ones);
            newBits += branchCost(zeros, ones, newProbs[i]);
            oldBits += branchCost(zeros, ones, current[i]);
        }
        if (newBits + ((long) nodes << 8) < oldBits) {
            bw.putBitUniform(1);
            for (var i = 0; i < nodes; i++) {
                current[i] = newProbs[i] != 0 ? newProbs[i] : 1;
                bw.putBits(current[i], 8);
            }
        } else {
            bw.putBitUniform(0);
        }
    }

    /**
     * Chooses and writes the probability updates of one motion vector component, libvpx's
     * {@code write_component_probs}.
     *
     * @param bw        the first partition writer
     * @param component {@code 0} for rows, {@code 1} for columns
     * @return {@code true} when a probability changed
     */
    private boolean writeMvProbs(BoolEncoder bw, int component) {
        var events = enc.stats.mvCounts;
        var off = component * RtStats.MV_VALS;
        var base = component * 19;
        long isShort0 = 0;
        long isShort1 = 0;
        long sign0 = 0;
        long sign1 = 0;
        var bits = mvBitCounts;
        var shortCounts = mvShortCounts;
        java.util.Arrays.fill(bits, 0);
        java.util.Arrays.fill(shortCounts, 0);
        var c0 = events[off + RtStats.MV_MAX];
        isShort0 += c0;
        shortCounts[0] += c0;
        for (var j = 1; j <= RtStats.MV_MAX; j++) {
            var positive = events[off + RtStats.MV_MAX + j];
            var negative = events[off + RtStats.MV_MAX - j];
            var c = positive + negative;
            sign0 += positive;
            sign1 += negative;
            if (j < 8) {
                isShort0 += c;
                shortCounts[j] += c;
            } else {
                isShort1 += c;
                for (var k = 9; k >= 0; k--) {
                    bits[2 * k + ((j >> k) & 1)] += c;
                }
            }
        }
        var current = enc.mvProbs;
        var updateProbs = Vp8Tables.MV_UPDATE_PROBS;
        var updated = false;
        updated |= updateMvProb(bw, isShort0, isShort1, current, base, Vp8Tables.MV_PROBS_DEFAULT[base], updateProbs[base]);
        updated |= updateMvProb(bw, sign0, sign1, current, base + 1, Vp8Tables.MV_PROBS_DEFAULT[base + 1],
                updateProbs[base + 1]);
        countBranches(Vp8Tables.SMALL_MV_TREE, shortCounts, 0);
        for (var j = 0; j < 7; j++) {
            updated |= updateMvProb(bw, branchCounts[2 * j], branchCounts[2 * j + 1], current, base + 2 + j,
                    Vp8Tables.MV_PROBS_DEFAULT[base + 2 + j], updateProbs[base + 2 + j]);
        }
        for (var j = 0; j < 10; j++) {
            updated |= updateMvProb(bw, bits[2 * j], bits[2 * j + 1], current, base + 9 + j,
                    Vp8Tables.MV_PROBS_DEFAULT[base + 9 + j], updateProbs[base + 9 + j]);
        }
        return updated;
    }

    /**
     * Chooses and writes the update of one motion vector probability, libvpx's
     * {@code calc_prob} and {@code update}.
     *
     * @param bw         the first partition writer
     * @param zeros      the number of zeros coded with the probability
     * @param ones       the number of ones
     * @param current    the current probabilities, updated
     * @param index      the index of the probability
     * @param fallback   the probability used without counts, libvpx's default context
     * @param updateProb the probability that the update flag is zero
     * @return {@code true} when the probability changed
     */
    private static boolean updateMvProb(BoolEncoder bw, long zeros, long ones, int[] current, int index, int fallback,
                                        int updateProb) {
        var newP = fallback;
        var total = zeros + ones;
        if (total != 0) {
            var x = (int) ((zeros * 255) / total) & ~1;
            newP = x != 0 ? x : 1;
        }
        var curBits = branchCost(zeros, ones, current[index]);
        var newBits = branchCost(zeros, ones, newP);
        var cost = 7 - 1 + ((RtTables.bitCost(updateProb, 1) - RtTables.bitCost(updateProb, 0) + 128) >> 8);
        if (curBits - newBits > cost) {
            current[index] = newP;
            bw.putBit(1, updateProb);
            bw.putBits(newP >> 1, 7);
            return true;
        }
        bw.putBit(0, updateProb);
        return false;
    }
}
