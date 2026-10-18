/* Ports libvorbis 1.3.7 (lib/res0.c). Copyright Xiph.Org Foundation; BSD 3-Clause. Modified for Smalt. */
package com.github.auties00.smalt.audio.vorbis.internal;

import com.github.auties00.smalt.internal.bits.LsbBitWriter;
import java.util.List;

/**
 * Classifies and codes the quantized residue of a submap with a residue of type 1 or 2.
 *
 * <p>Each partition of {@code grouping} lines gets the first class whose thresholds hold its
 * largest magnitude (and summed magnitude, or angle-channel magnitude for type 2); the classes are
 * coded with the group book, then the partitions stage by stage with the class's books, each
 * vector by its nearest lattice entry. Type 2 interleaves the channels into one vector first
 * ({@code _01class}, {@code _2class}, {@code _01forward}, {@code res1_forward}, {@code res2_forward}).
 */
public final class ResidueEncoder {
    /**
     * The residue configuration.
     */
    private final Residue0Info info;

    /**
     * The residue type, 1 or 2.
     */
    private final int type;

    /**
     * The group book.
     */
    private final EncoderCodebook phraseBook;

    /**
     * The book of each class and stage, or {@code null}.
     */
    private final EncoderCodebook[][] partBooks;

    /**
     * The number of stages.
     */
    private final int stages;

    /**
     * Scratch: the interleaved residue of type 2.
     */
    private int[] interleaved = new int[0];

    /**
     * Prepares the lookups of a residue ({@code res0_look}).
     *
     * @param info the configuration
     * @param type the residue type, 1 or 2
     * @param books the stream's codebooks
     */
    public ResidueEncoder(Residue0Info info, int type, List<EncoderCodebook> books) {
        this.info = info;
        this.type = type;
        this.phraseBook = books.get(info.groupBook);
        var parts = info.partitions;
        partBooks = new EncoderCodebook[parts][];
        var acc = 0;
        var maxStage = 0;
        for (var j = 0; j < parts; j++) {
            var stageCount = Ilog.ilog(info.secondStages[j]);
            if (stageCount != 0) {
                maxStage = Math.max(maxStage, stageCount);
                partBooks[j] = new EncoderCodebook[stageCount];
                for (var k = 0; k < stageCount; k++) {
                    if ((info.secondStages[j] & (1 << k)) != 0) {
                        partBooks[j][k] = books.get(info.bookList[acc++]);
                    }
                }
            }
        }
        this.stages = maxStage;
    }

    /**
     * Classifies the partitions of the channels of a submap.
     *
     * @param in the quantized residue of each channel in the submap
     * @param nonzero whether each channel is used
     * @param channels the number of channels in the submap
     * @return the class of each channel's partitions (one row for type 2), or {@code null}
     * when no channel is used
     */
    public long[][] classify(int[][] in, boolean[] nonzero, int channels) {
        if (type == 2) {
            var used = 0;
            for (var i = 0; i < channels; i++) {
                if (nonzero[i]) {
                    used++;
                }
            }
            return used != 0 ? classify2(in, channels) : null;
        }
        var used = 0;
        var compact = new int[channels][];
        for (var i = 0; i < channels; i++) {
            if (nonzero[i]) {
                compact[used++] = in[i];
            }
        }
        return used != 0 ? classify01(compact, used) : null;
    }

    /**
     * Classifies residue type 0 and 1 partitions ({@code _01class}).
     *
     * @param in the used channels
     * @param ch the number of used channels
     * @return the classes
     */
    private long[][] classify01(int[][] in, int ch) {
        var samplesPerPartition = info.grouping;
        var possiblePartitions = info.partitions;
        var n = info.end - info.begin;
        var partVals = n / samplesPerPartition;
        var partword = new long[ch][partVals];
        var scale = (float) (100. / samplesPerPartition);
        for (var i = 0; i < partVals; i++) {
            var offset = i * samplesPerPartition + info.begin;
            for (var j = 0; j < ch; j++) {
                var max = 0;
                var ent = 0;
                for (var k = 0; k < samplesPerPartition; k++) {
                    var value = Math.abs(in[j][offset + k]);
                    if (value > max) {
                        max = value;
                    }
                    ent += value;
                }
                ent = (int) (ent * scale);
                var k = 0;
                for (; k < possiblePartitions - 1; k++) {
                    if (max <= info.classMetric1[k] && (info.classMetric2[k] < 0 || ent < info.classMetric2[k])) {
                        break;
                    }
                }
                partword[j][i] = k;
            }
        }
        return partword;
    }

    /**
     * Classifies residue type 2 partitions ({@code _2class}).
     *
     * @param in the channels
     * @param ch the number of channels
     * @return the classes, one row
     */
    private long[][] classify2(int[][] in, int ch) {
        var samplesPerPartition = info.grouping;
        var possiblePartitions = info.partitions;
        var n = info.end - info.begin;
        var partVals = n / samplesPerPartition;
        var partword = new long[1][partVals];
        var l = info.begin / ch;
        for (var i = 0; i < partVals; i++) {
            var magMax = 0;
            var angMax = 0;
            for (var j = 0; j < samplesPerPartition; j += ch) {
                magMax = Math.max(magMax, Math.abs(in[0][l]));
                for (var k = 1; k < ch; k++) {
                    angMax = Math.max(angMax, Math.abs(in[k][l]));
                }
                l++;
            }
            var j = 0;
            for (; j < possiblePartitions - 1; j++) {
                if (magMax <= info.classMetric1[j] && angMax <= info.classMetric2[j]) {
                    break;
                }
            }
            partword[0][i] = j;
        }
        return partword;
    }

    /**
     * Codes the residue of a submap.
     *
     * @param out the packet
     * @param in the quantized residue of each channel in the submap; consumed
     * @param nonzero whether each channel is used
     * @param channels the number of channels in the submap
     * @param partword the classes from {@link #classify}
     * @param halfSize half the block size
     */
    public void forward(LsbBitWriter out, int[][] in, boolean[] nonzero, int channels, long[][] partword, int halfSize) {
        if (partword == null) {
            return;
        }
        if (type == 2) {
            var length = channels * halfSize;
            if (interleaved.length < length) {
                interleaved = new int[length];
            }
            var work = interleaved;
            for (var i = 0; i < channels; i++) {
                var pcm = in[i];
                for (int j = 0, k = i; j < halfSize; j++, k += channels) {
                    work[k] = pcm[j];
                }
            }
            forward01(out, new int[][]{work}, 1, partword);
            return;
        }
        var used = 0;
        var compact = new int[channels][];
        for (var i = 0; i < channels; i++) {
            if (nonzero[i]) {
                compact[used++] = in[i];
            }
        }
        if (used != 0) {
            forward01(out, compact, used, partword);
        }
    }

    /**
     * Codes the partitions ({@code _01forward}).
     *
     * @param out the packet
     * @param in the vectors
     * @param ch the number of vectors
     * @param partword the classes
     */
    private void forward01(LsbBitWriter out, int[][] in, int ch, long[][] partword) {
        var samplesPerPartition = info.grouping;
        var possiblePartitions = info.partitions;
        var partitionsPerWord = phraseBook.dim();
        var n = info.end - info.begin;
        var partVals = n / samplesPerPartition;
        for (var s = 0; s < stages; s++) {
            for (var i = 0; i < partVals; ) {
                if (s == 0) {
                    for (var j = 0; j < ch; j++) {
                        var val = partword[j][i];
                        for (var k = 1; k < partitionsPerWord; k++) {
                            val *= possiblePartitions;
                            if (i + k < partVals) {
                                val += partword[j][i + k];
                            }
                        }
                        if (val < phraseBook.entries()) {
                            phraseBook.encode((int) val, out);
                        }
                    }
                }
                for (var k = 0; k < partitionsPerWord && i < partVals; k++, i++) {
                    var offset = i * samplesPerPartition + info.begin;
                    for (var j = 0; j < ch; j++) {
                        var cls = (int) partword[j][i];
                        if ((info.secondStages[cls] & (1 << s)) != 0) {
                            var book = partBooks[cls][s];
                            if (book != null) {
                                encodePart(out, in[j], offset, samplesPerPartition, book);
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Codes one partition with a book ({@code _encodepart}).
     *
     * @param out the packet
     * @param vec the values
     * @param offset the index of the partition
     * @param n the number of values
     * @param book the codebook the values are coded with
     */
    private static void encodePart(LsbBitWriter out, int[] vec, int offset, int n, EncoderCodebook book) {
        var dim = book.dim();
        var step = n / dim;
        for (var i = 0; i < step; i++) {
            var entry = book.bestError(vec, offset + i * dim);
            book.encode(entry, out);
        }
    }
}
