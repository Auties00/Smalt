package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Reconstructs one picture from its slices: runs the slice decoder, deblocks macroblock rows as
 * soon as the row below them is decoded, replicates finished rows into the frame borders,
 * conceals macroblocks no slice covered, and publishes the progress of the frame so that other
 * threads decoding later pictures can read its finished rows.
 *
 * <p>It also decides whether the picture is {@link DecoderFrame#damaged}: when a slice fails,
 * when a macroblock is concealed, when a partition predicts from a damaged or
 * {@linkplain DecoderFrame#staleReference stale} picture, or when a B slice derives direct motion
 * from a damaged co-located picture. Only the references the
 * partitions actually use count, so a picture that predicts only from intact pictures (such as a
 * long-term reference kept for recovery) is intact even though damaged pictures sit in its
 * reference lists.
 *
 * <p>An instance decodes one picture at a time and is confined to one thread while it does.
 */
final class PictureDecoder {
    /**
     * The slice decoder.
     */
    private final SliceDecoder sliceDecoder = new SliceDecoder();

    /**
     * The deblocking filter.
     */
    private final Deblocker deblocker = new Deblocker();

    /**
     * The per-macroblock state, sized for the current picture.
     */
    private PictureState state;

    /**
     * The number of decoded macroblocks of each row of the current picture.
     */
    private int[] rowDecoded = new int[0];

    /**
     * The next macroblock row to deblock.
     */
    private int nextDeblockRow;

    /**
     * The macroblock row of the last decoded macroblock, which saves a division for the next one.
     */
    private int lastRow;

    /**
     * The number of leading luma sample rows already final and replicated into the borders.
     */
    private int readyLumaRows;

    /**
     * The number of leading chroma sample rows already final and replicated into the borders.
     */
    private int readyChromaRows;

    /**
     * The number of leading rows completely decoded, whose motion is published as final.
     */
    private int decodedRows;

    /**
     * The picture being decoded.
     */
    private DecoderFrame frame;

    /**
     * The frame concealed macroblocks are copied from, or {@code null} for gray.
     */
    private DecoderFrame concealSource;

    /**
     * The number of the first slice of the picture.
     */
    private int firstSliceNum;

    /**
     * The slice number given to concealed macroblocks.
     */
    private int concealSliceNum;

    /**
     * The first failure of the current picture, or {@code null}.
     */
    private MediaException error;

    /**
     * The listener notified after each decoded macroblock.
     */
    private final SliceDecoder.MacroblockListener listener = this::macroblockDone;

    /**
     * The distinct pictures in the active part of the reference lists of the current picture's
     * slices.
     */
    private DecoderFrame[] references = new DecoderFrame[8];

    /**
     * Whether each entry of {@link #references} is the co-located picture of a B slice, whose
     * damage damages the whole picture.
     */
    private boolean[] colocated = new boolean[8];

    /**
     * The number of valid entries of {@link #references}.
     */
    private int referenceCount;

    /**
     * The identities of the damaged entries of {@link #references}.
     */
    private int[] damagedIds = new int[8];

    /**
     * Constructs a picture decoder.
     */
    PictureDecoder() {
    }

    /**
     * Begins decoding a picture.
     *
     * @param frame the frame to decode into
     * @param firstSliceNum the number the first slice of the picture will carry; every slice
     * of the picture carries a number at least as large
     * @param concealSliceNum the number given to concealed macroblocks, larger than every slice
     * number of the picture
     * @param concealSource the frame to copy concealed macroblocks from, or {@code null}
     * @param dsp the kernels the picture is reconstructed and deblocked with
     */
    void start(DecoderFrame frame, int firstSliceNum, int concealSliceNum, DecoderFrame concealSource, H264Dsp dsp) {
        this.frame = frame;
        this.firstSliceNum = firstSliceNum;
        this.concealSliceNum = concealSliceNum;
        this.concealSource = concealSource;
        this.error = null;
        if (state == null || state.mbWidth != frame.mbWidth || state.mbHeight != frame.mbHeight) {
            state = new PictureState(frame.mbWidth, frame.mbHeight);
        }
        if (rowDecoded.length != frame.mbHeight) {
            rowDecoded = new int[frame.mbHeight];
        } else {
            Arrays.fill(rowDecoded, 0);
        }
        nextDeblockRow = 0;
        lastRow = 0;
        readyLumaRows = 0;
        readyChromaRows = 0;
        decodedRows = 0;
        referenceCount = 0;
        frame.beginDecoding();
        sliceDecoder.startPicture(frame, state, dsp);
        deblocker.startPicture(firstSliceNum, dsp);
    }

    /**
     * Returns the per-macroblock state of the current picture.
     *
     * @return the state
     */
    PictureState state() {
        return state;
    }

    /**
     * Decodes one slice of the current picture, recording a failure instead of propagating it
     * so that the rest of the picture still decodes.
     *
     * @param task the slice
     * @throws MediaException.Unsupported if the slice uses an unsupported coding tool
     */
    void decodeSlice(SliceTask task) {
        noteReferences(task);
        try {
            sliceDecoder.decodeSlice(task, listener);
        } catch (MediaException.Malformed failure) {
            record(failure);
        } catch (MediaException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            record(new MediaException.Malformed("corrupt slice data", failure));
        }
    }

    /**
     * Records a failure of the current picture, keeping the first.
     *
     * @param failure the slice failure; only the first of a picture is kept
     */
    private void record(MediaException failure) {
        frame.damaged = true;
        if (error == null) {
            error = failure;
        }
    }

    /**
     * Remembers the pictures a slice may predict from, so {@link #finish()} can check whether the
     * partitions that use them inherit damage.
     *
     * @param task the slice
     */
    private void noteReferences(SliceTask task) {
        var header = task.header;
        if (header.isIntra()) {
            return;
        }
        var lists = header.sliceType == H264SliceHeader.B ? 2 : 1;
        for (var list = 0; list < lists; list++) {
            var entries = task.lists[list];
            var count = Math.min(header.numRefIdxActive[list], entries.length);
            for (var i = 0; i < count; i++) {
                noteReference(entries[i], false);
            }
        }
        if (lists == 2) {
            noteReference(task.lists[1][0], true);
        }
    }

    /**
     * Adds a picture to {@link #references} unless it is already there.
     *
     * @param reference the picture, or {@code null} for none
     * @param coLocated whether the picture is the co-located picture of a B slice
     */
    private void noteReference(DecoderFrame reference, boolean coLocated) {
        if (reference == null) {
            return;
        }
        for (var i = 0; i < referenceCount; i++) {
            if (references[i] == reference) {
                colocated[i] |= coLocated;
                return;
            }
        }
        if (referenceCount == references.length) {
            references = Arrays.copyOf(references, 2 * referenceCount);
            colocated = Arrays.copyOf(colocated, 2 * referenceCount);
            damagedIds = Arrays.copyOf(damagedIds, 2 * referenceCount);
        }
        references[referenceCount] = reference;
        colocated[referenceCount] = coLocated;
        referenceCount++;
    }

    /**
     * Returns whether a partition of the current picture predicts from a damaged picture, or a B
     * slice reads the motion of a damaged co-located picture.
     *
     * <p>Every reference is waited for until its decoding finished, since its damage is only known
     * then; references are older pictures, so the wait always ends.
     *
     * @return whether the current picture inherits damage from its references
     */
    private boolean inheritsDamage() {
        var damaged = 0;
        for (var i = 0; i < referenceCount; i++) {
            var reference = references[i];
            reference.awaitDone();
            if (reference.damaged || reference.nonExisting || reference.staleReference) {
                if (colocated[i]) {
                    return true;
                }
                damagedIds[damaged++] = reference.id;
            }
        }
        if (damaged == 0) {
            return false;
        }
        var current = frame;
        var partitions = current.mbWidth * current.mbHeight * 4;
        for (var list = 0; list < 2; list++) {
            var ids = current.refId[list];
            for (var i = 0; i < partitions; i++) {
                var id = ids[i];
                if (id < 0) {
                    continue;
                }
                for (var k = 0; k < damaged; k++) {
                    if (damagedIds[k] == id) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns whether every macroblock of the current picture was decoded.
     *
     * @return {@code true} when no macroblock is missing
     */
    boolean complete() {
        return frame.decodedMbs >= frame.mbWidth * frame.mbHeight;
    }

    /**
     * Completes the current picture: conceals missing macroblocks and finishes deblocking and
     * border replication. The caller publishes the frame as done afterwards.
     *
     * @return the first failure recorded since the last {@link #takeError()}, or {@code null}
     */
    MediaException finish() {
        var current = frame;
        try {
            if (!complete()) {
                conceal();
            }
            if (!current.damaged && referenceCount > 0 && inheritsDamage()) {
                current.damaged = true;
            }
            while (nextDeblockRow < current.mbHeight) {
                deblocker.filterRow(current, state, nextDeblockRow);
                nextDeblockRow++;
            }
            current.extendBorders(readyLumaRows, current.mbHeight * 16, readyChromaRows, current.mbHeight * 8);
        } catch (RuntimeException failure) {
            record(new MediaException.Malformed("corrupt picture", failure));
        } finally {
            frame = null;
            concealSource = null;
            Arrays.fill(references, 0, referenceCount, null);
            referenceCount = 0;
        }
        var failure = error;
        error = null;
        return failure;
    }

    /**
     * Returns and clears the first failure recorded since the last call.
     *
     * @return the failure, or {@code null}
     */
    MediaException takeError() {
        var failure = error;
        error = null;
        return failure;
    }

    /**
     * Tracks row completion, publishes the rows whose motion became final, deblocks every row
     * whose lower neighbor row is complete, and publishes the rows whose samples became final.
     *
     * @param mbAddr the address of the decoded macroblock
     */
    private void macroblockDone(int mbAddr) {
        var current = frame;
        var width = current.mbWidth;
        var row = lastRow;
        var column = mbAddr - row * width;
        if (column < 0 || column >= width) {
            row = mbAddr / width;
            lastRow = row;
        }
        if (++rowDecoded[row] >= width && row == decodedRows) {
            var rows = row + 1;
            while (rows < current.mbHeight && rowDecoded[rows] >= width) {
                rows++;
            }
            decodedRows = rows;
            current.publishDecodedRows(rows);
        }
        var advanced = false;
        while (nextDeblockRow + 1 < current.mbHeight
                && rowDecoded[nextDeblockRow] >= width && rowDecoded[nextDeblockRow + 1] >= width) {
            deblocker.filterRow(current, state, nextDeblockRow);
            nextDeblockRow++;
            advanced = true;
        }
        if (advanced) {
            // The next row's top edge filter can still change the 3 luma and 1 chroma sample rows
            // above it (H.264 8.7.2.3, 8.7.2.4); every row above those is final.
            var lumaReady = nextDeblockRow * 16 - 3;
            var chromaReady = nextDeblockRow * 8 - 1;
            current.extendBorders(readyLumaRows, lumaReady, readyChromaRows, chromaReady);
            readyLumaRows = lumaReady;
            readyChromaRows = chromaReady;
            current.publishRows(lumaReady, chromaReady);
        }
    }

    /**
     * Conceals the macroblocks of the current picture no slice covered by copying the co-located
     * samples of the source frame, or gray when there is none.
     */
    private void conceal() {
        var current = frame;
        current.damaged = true;
        var source = concealSource;
        if (source != null) {
            source.awaitDone();
        }
        var pic = state;
        for (var mb = 0; mb < current.mbWidth * current.mbHeight; mb++) {
            if (pic.sliceNum[mb] >= firstSliceNum) {
                continue;
            }
            var x = mb % current.mbWidth;
            var y = mb / current.mbWidth;
            var luma = current.lumaOrigin + y * 16 * current.lumaStride + x * 16;
            var chroma = current.chromaOrigin + y * 8 * current.chromaStride + x * 8;
            for (var line = 0; line < 16; line++) {
                var at = luma + line * current.lumaStride;
                if (source != null) {
                    System.arraycopy(source.luma, at, current.luma, at, 16);
                } else {
                    Arrays.fill(current.luma, at, at + 16, (byte) 128);
                }
            }
            if (current.hasChroma) {
                for (var line = 0; line < 8; line++) {
                    var at = chroma + line * current.chromaStride;
                    if (source != null && source.hasChroma) {
                        System.arraycopy(source.cb, at, current.cb, at, 8);
                        System.arraycopy(source.cr, at, current.cr, at, 8);
                    } else {
                        Arrays.fill(current.cb, at, at + 8, (byte) 128);
                        Arrays.fill(current.cr, at, at + 8, (byte) 128);
                    }
                }
            }
            current.mbType[mb] = DecoderFrame.MB_INTRA;
            for (var i = 0; i < 4; i++) {
                current.ref[0][mb * 4 + i] = -1;
                current.ref[1][mb * 4 + i] = -1;
                current.refId[0][mb * 4 + i] = -1;
                current.refId[1][mb * 4 + i] = -1;
            }
            Arrays.fill(current.mv[0], mb * 16, mb * 16 + 16, 0);
            Arrays.fill(current.mv[1], mb * 16, mb * 16 + 16, 0);
            pic.sliceNum[mb] = concealSliceNum;
            pic.filter[mb] = 1;
            pic.qp[mb] = 0;
            pic.nonZero[mb] = 0;
            current.decodedMbs++;
            rowDecoded[y]++;
        }
    }
}
