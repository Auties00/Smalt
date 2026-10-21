package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Reconstructs one picture from its slices: runs the slice decoder, deblocks macroblock rows as
 * soon as the row below them is decoded, replicates finished rows into the frame borders,
 * conceals macroblocks no slice covered, and publishes the progress of the frame so that other
 * threads decoding later pictures can read its finished rows.
 *
 * <p>A picture is a frame, a field or an MBAFF frame. Frames, MBAFF frames included, publish
 * their rows while they are decoded; a field becomes readable when it is done. A field is
 * deblocked row by row like a frame, and an MBAFF frame row of macroblock pairs by row of pairs.
 *
 * <p>It also decides whether the picture is {@link DecoderFrame#damaged}: when a slice fails,
 * when a macroblock is concealed, when a partition predicts from a damaged or
 * {@linkplain DecoderFrame#staleReference stale} picture, or when a B slice derives direct motion
 * from a damaged co-located picture. Only the references the
 * partitions actually use count, so a picture that predicts only from intact pictures (such as a
 * long-term reference kept for recovery) is intact even though damaged pictures sit in its
 * reference lists.
 *
 * <p>The three color planes of a picture coded with {@code separate_colour_plane_flag} are
 * decoded as three monochrome pictures, each by a picture decoder of its own into the frame of its
 * plane ({@link DecoderFrame#planes}); this decoder then hands each slice to the decoder of the
 * slice's {@code colour_plane_id}.
 *
 * <p>An instance decodes one picture at a time and is confined to one thread while it does.
 */
final class PictureDecoder {
    /**
     * The slice decoder.
     */
    private final SliceDecoder sliceDecoder = new SliceDecoder();

    /**
     * The deblocking filter of frames and fields.
     */
    private final Deblocker deblocker = new Deblocker();

    /**
     * The deblocking filter of MBAFF frames.
     */
    private final MbaffDeblocker mbaffDeblocker = new MbaffDeblocker(deblocker);

    /**
     * The per-macroblock state, sized for the current picture.
     */
    private PictureState state;

    /**
     * The decoders of the three color planes of the current picture when they are coded
     * separately, or {@code null}; created on first use.
     */
    private PictureDecoder[] planeDecoders;

    /**
     * Whether the current picture's color planes are coded separately and decoded by
     * {@link #planeDecoders}.
     */
    private boolean separatePlanes;

    /**
     * The number of decoded macroblocks of each row of the current picture.
     */
    private int[] rowDecoded = new int[0];

    /**
     * The next macroblock row to deblock, a row of macroblock pairs in an MBAFF frame.
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
     * The number of macroblocks of the current picture decoded or concealed so far.
     */
    private int decodedMbs;

    /**
     * The picture being decoded.
     */
    private DecoderFrame frame;

    /**
     * The structure of the picture: {@link DecoderFrame#FRAME}, {@link DecoderFrame#TOP} or
     * {@link DecoderFrame#BOTTOM}.
     */
    private int structure;

    /**
     * Whether the picture is an MBAFF frame.
     */
    private boolean mbaff;

    /**
     * Whether the picture is a frame without macroblock-adaptive frame/field decoding.
     */
    private boolean progressive;

    /**
     * The number of macroblock rows of the picture.
     */
    private int pictureRows;

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
     * Whether a slice of the current picture listed each entry of {@link #references} while it
     * was a {@linkplain DecoderFrame#staleReference stale} long-term reference.
     */
    private boolean[] staleUse = new boolean[8];

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
     * Begins decoding a picture, whose frame the engine already marked as being decoded
     * ({@link DecoderFrame#beginDecoding(int, boolean)}).
     *
     * @param frame the frame to decode into
     * @param structure the structure of the picture: {@link DecoderFrame#FRAME},
     * {@link DecoderFrame#TOP} or {@link DecoderFrame#BOTTOM}
     * @param mbaff whether the picture is an MBAFF frame
     * @param firstSliceNum the number the first slice of the picture will carry; every slice
     * of the picture carries a number at least as large
     * @param concealSliceNum the number given to concealed macroblocks, larger than every slice
     * number of the picture
     * @param concealSource the frame to copy concealed macroblocks from, or {@code null}
     * @param dsp the kernels the picture is reconstructed and deblocked with
     */
    void start(DecoderFrame frame, int structure, boolean mbaff, int firstSliceNum, int concealSliceNum,
               DecoderFrame concealSource, H264Dsp dsp) {
        separatePlanes = frame.planes != null;
        if (separatePlanes) {
            if (planeDecoders == null) {
                planeDecoders = new PictureDecoder[]{new PictureDecoder(), new PictureDecoder(), new PictureDecoder()};
            }
            var source = concealSource != null && concealSource.planes != null ? concealSource : null;
            for (var p = 0; p < 3; p++) {
                planeDecoders[p].startPlane(frame.planes[p], p, structure, mbaff, firstSliceNum, concealSliceNum,
                        source == null ? null : source.planes[p], dsp);
            }
            this.frame = frame;
            this.error = null;
            return;
        }
        startPlane(frame, 0, structure, mbaff, firstSliceNum, concealSliceNum, concealSource, dsp);
    }

    /**
     * Begins decoding a picture that is one color plane, or holds every color plane, of the
     * current picture.
     *
     * @param frame the frame to decode into
     * @param colorPlane the {@code colour_plane_id} of the plane when the planes are coded
     * separately, 0 otherwise
     * @param structure the structure of the picture
     * @param mbaff whether the picture is an MBAFF frame
     * @param firstSliceNum the number the first slice of the picture will carry
     * @param concealSliceNum the number given to concealed macroblocks
     * @param concealSource the frame to copy concealed macroblocks from, or {@code null}
     * @param dsp the kernels the picture is reconstructed and deblocked with
     */
    private void startPlane(DecoderFrame frame, int colorPlane, int structure, boolean mbaff, int firstSliceNum,
                            int concealSliceNum, DecoderFrame concealSource, H264Dsp dsp) {
        this.frame = frame;
        this.structure = structure;
        this.mbaff = mbaff;
        this.progressive = structure == DecoderFrame.FRAME && !mbaff;
        this.pictureRows = structure == DecoderFrame.FRAME ? frame.mbHeight : frame.mbHeight / 2;
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
        decodedMbs = 0;
        referenceCount = 0;
        sliceDecoder.startPicture(frame, state, dsp, structure, mbaff, colorPlane);
        deblocker.startPicture(firstSliceNum, dsp, structure);
    }

    /**
     * Returns the per-macroblock state of one color plane of the current picture.
     *
     * @param colorPlane the {@code colour_plane_id} when the planes are coded separately, 0
     * otherwise
     * @return the state
     */
    PictureState state(int colorPlane) {
        return separatePlanes ? planeDecoders[colorPlane].state : state;
    }

    /**
     * Decodes one slice of the current picture, recording a failure instead of propagating it
     * so that the rest of the picture still decodes; a slice whose first macroblock an earlier
     * slice of the picture decoded is skipped.
     *
     * @param task the slice
     * @throws MediaException.Unsupported if the slice uses an unsupported coding tool
     */
    void decodeSlice(SliceTask task) {
        if (separatePlanes) {
            planeDecoders[task.header.colorPlaneId].decodeSlice(task);
            return;
        }
        var first = (long) task.header.firstMbInSlice * (mbaff ? 2 : 1);
        var slices = state.sliceNum;
        if (first < slices.length) {
            var index = PictureState.mbIndex((int) first, structure, mbaff, frame.mbWidth);
            if (index < slices.length && slices[index] >= firstSliceNum) {
                // a repeat of a slice the picture already decoded, which a single-threaded decode drops too
                return;
            }
        }
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
        frame.owner().damaged = true;
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
                noteReference(entries[i], false, task.refStale[list][i]);
            }
        }
        if (lists == 2) {
            noteReference(task.lists[1][0], true, task.refStale[1][0]);
        }
    }

    /**
     * Adds a picture to {@link #references} unless it is already there.
     *
     * @param reference the picture, or {@code null} for none
     * @param coLocated whether the picture is the co-located picture of a B slice
     * @param stale whether the picture was a stale long-term reference when the slice's lists were
     * built
     */
    private void noteReference(DecoderFrame reference, boolean coLocated, boolean stale) {
        if (reference == null) {
            return;
        }
        for (var i = 0; i < referenceCount; i++) {
            if (references[i] == reference) {
                colocated[i] |= coLocated;
                staleUse[i] |= stale;
                return;
            }
        }
        if (referenceCount == references.length) {
            references = Arrays.copyOf(references, 2 * referenceCount);
            colocated = Arrays.copyOf(colocated, 2 * referenceCount);
            staleUse = Arrays.copyOf(staleUse, 2 * referenceCount);
            damagedIds = Arrays.copyOf(damagedIds, 2 * referenceCount);
        }
        references[referenceCount] = reference;
        colocated[referenceCount] = coLocated;
        staleUse[referenceCount] = stale;
        referenceCount++;
    }

    /**
     * Returns whether a partition of the current picture predicts from a damaged picture, or a B
     * slice reads the motion of a damaged co-located picture.
     *
     * <p>Every reference is waited for until its decoding finished, since its damage is only known
     * then; references are older pictures, or the first field of the current frame, so the wait
     * always ends.
     *
     * @return whether the current picture inherits damage from its references
     */
    private boolean inheritsDamage() {
        var damaged = 0;
        var current = frame;
        var owner = current.owner();
        for (var i = 0; i < referenceCount; i++) {
            var reference = references[i];
            if (reference == owner) {
                reference.awaitDone(DecoderFrame.FRAME - structure);
            } else {
                reference.awaitDone();
            }
            if (reference.damaged || reference.nonExisting || staleUse[i]) {
                if (colocated[i]) {
                    return true;
                }
                damagedIds[damaged++] = reference.id;
            }
        }
        if (damaged == 0) {
            return false;
        }
        var width = current.mbWidth;
        var count = width * pictureRows;
        for (var address = 0; address < count; address++) {
            var mb = PictureState.mbIndex(address, structure, mbaff, width);
            for (var list = 0; list < 2; list++) {
                var ids = current.refId[list];
                for (var p = 0; p < 4; p++) {
                    var id = ids[mb * 4 + p];
                    if (id < 0) {
                        continue;
                    }
                    var picture = id >> 2;
                    for (var k = 0; k < damaged; k++) {
                        if (damagedIds[k] == picture) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * Returns the number of macroblocks of the current picture.
     *
     * @return the macroblock count
     */
    private int pictureMbs() {
        return frame.mbWidth * (structure == DecoderFrame.FRAME ? frame.mbHeight : frame.mbHeight / 2);
    }

    /**
     * Returns whether every macroblock of the current picture was decoded.
     *
     * @return {@code true} when no macroblock is missing
     */
    boolean complete() {
        if (separatePlanes) {
            return planeDecoders[0].complete() && planeDecoders[1].complete() && planeDecoders[2].complete();
        }
        return decodedMbs >= pictureMbs();
    }

    /**
     * Completes the current picture: conceals missing macroblocks and finishes deblocking and
     * border replication. The caller publishes the picture as done afterwards.
     *
     * <p>Deblocking and border replication run even when concealment or the damage check fail,
     * so that every sample a later picture may read is final.
     *
     * @return the first failure recorded since the last {@link #takeError()}, or {@code null}
     */
    MediaException finish() {
        if (separatePlanes) {
            MediaException failure = null;
            for (var decoder : planeDecoders) {
                var planeFailure = decoder.finish();
                if (failure == null) {
                    failure = planeFailure;
                }
            }
            frame = null;
            return failure;
        }
        var current = frame;
        var owner = current.owner();
        try {
            if (!complete()) {
                conceal();
            }
            if (!owner.damaged && referenceCount > 0 && inheritsDamage()) {
                owner.damaged = true;
            }
        } catch (RuntimeException failure) {
            record(new MediaException.Malformed("corrupt picture", failure));
        }
        try {
            if (mbaff) {
                var pairRows = current.mbHeight / 2;
                while (nextDeblockRow < pairRows) {
                    mbaffDeblocker.filterPairRow(current, state, firstSliceNum, nextDeblockRow);
                    nextDeblockRow++;
                }
                current.extendBorders(readyLumaRows, current.mbHeight * 16, readyChromaRows, current.chromaHeight);
            } else {
                while (nextDeblockRow < pictureRows) {
                    deblocker.filterRow(current, state, nextDeblockRow);
                    nextDeblockRow++;
                }
                if (progressive) {
                    current.extendBorders(readyLumaRows, current.mbHeight * 16, readyChromaRows,
                            current.chromaHeight);
                } else {
                    current.extendFieldBorders(structure == DecoderFrame.BOTTOM ? 1 : 0);
                }
            }
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
        if (separatePlanes) {
            MediaException failure = null;
            for (var decoder : planeDecoders) {
                var planeFailure = decoder.takeError();
                if (failure == null) {
                    failure = planeFailure;
                }
            }
            return failure;
        }
        var failure = error;
        error = null;
        return failure;
    }

    /**
     * Tracks row completion, publishes the rows whose motion became final, deblocks every row
     * whose lower neighbor row is complete, and publishes the rows whose samples became final.
     *
     * <p>Fields are deblocked row by row but publish nothing.
     *
     * @param mbIndex the index of the decoded macroblock in the per-macroblock arrays
     */
    private void macroblockDone(int mbIndex) {
        decodedMbs++;
        if (mbaff) {
            mbaffMacroblockDone(mbIndex);
            return;
        }
        var current = frame;
        var width = current.mbWidth;
        var row = lastRow;
        var column = mbIndex - (progressive ? row : 2 * row + (structure >> 1)) * width;
        if (column < 0 || column >= width) {
            row = progressive ? mbIndex / width : (mbIndex / width) >> 1;
            lastRow = row;
        }
        if (++rowDecoded[row] >= width && row == decodedRows) {
            var rows = row + 1;
            while (rows < pictureRows && rowDecoded[rows] >= width) {
                rows++;
            }
            decodedRows = rows;
            if (progressive) {
                current.publishDecodedRows(rows);
            }
        }
        var advanced = false;
        while (nextDeblockRow + 1 < pictureRows
                && rowDecoded[nextDeblockRow] >= width && rowDecoded[nextDeblockRow + 1] >= width) {
            deblocker.filterRow(current, state, nextDeblockRow);
            nextDeblockRow++;
            advanced = true;
        }
        if (advanced && progressive) {
            // The next row's top edge filter can still change the 3 luma and 1 chroma sample rows
            // above it, 3 for 4:4:4 chroma (H.264 8.7.2.3, 8.7.2.4); every row above those is final.
            var lumaReady = nextDeblockRow * 16 - 3;
            var chromaReady = nextDeblockRow * current.chromaMbHeight - (current.chromaFormat == 3 ? 3 : 1);
            current.extendBorders(readyLumaRows, lumaReady, readyChromaRows, chromaReady);
            readyLumaRows = lumaReady;
            readyChromaRows = chromaReady;
            current.publishRows(lumaReady, chromaReady);
        }
    }

    /**
     * Tracks row completion of an MBAFF frame, publishes the rows of macroblock pairs whose
     * motion became final, deblocks every row of pairs whose lower neighbor row of pairs is
     * complete, and publishes the rows whose samples became final.
     *
     * @param mbIndex the index of the decoded macroblock in the per-macroblock arrays: its frame
     * macroblock row times the width plus its column
     */
    private void mbaffMacroblockDone(int mbIndex) {
        var current = frame;
        var width = current.mbWidth;
        var row = mbIndex / width;
        if (++rowDecoded[row] >= width && row == decodedRows) {
            var rows = row + 1;
            while (rows < pictureRows && rowDecoded[rows] >= width) {
                rows++;
            }
            decodedRows = rows;
            current.publishDecodedRows(rows & ~1);
        }
        var pairRows = pictureRows / 2;
        var advanced = false;
        while (nextDeblockRow + 1 < pairRows && decodedRows >= 2 * nextDeblockRow + 4) {
            mbaffDeblocker.filterPairRow(current, state, firstSliceNum, nextDeblockRow);
            nextDeblockRow++;
            advanced = true;
        }
        if (advanced) {
            // The top edge filter of the next row of pairs can still change the 6 luma and 2
            // chroma sample rows above it, 3 and 1 rows of each field, 6 for 4:4:4 chroma (H.264
            // 8.7.2.3, 8.7.2.4).
            var lumaReady = nextDeblockRow * 32 - 6;
            var chromaReady = nextDeblockRow * 2 * current.chromaMbHeight - (current.chromaFormat == 3 ? 6 : 2);
            current.extendBorders(readyLumaRows, lumaReady, readyChromaRows, chromaReady);
            readyLumaRows = lumaReady;
            readyChromaRows = chromaReady;
            current.publishRows(lumaReady, chromaReady);
        }
    }

    /**
     * Conceals the macroblocks of the current picture no slice covered by copying the co-located
     * samples of the source frame, or gray when there is none.
     *
     * <p>A macroblock of an MBAFF frame is concealed as a frame macroblock.
     */
    private void conceal() {
        var current = frame;
        current.owner().damaged = true;
        var source = concealSource;
        if (source != null) {
            source.awaitDone();
        }
        var pic = state;
        var width = current.mbWidth;
        var field = structure != DecoderFrame.FRAME;
        var parity = structure == DecoderFrame.BOTTOM ? 1 : 0;
        var lumaPitch = field ? 2 * current.lumaStride : current.lumaStride;
        var chromaPitch = field ? 2 * current.chromaStride : current.chromaStride;
        var chromaRows = current.chromaMbHeight;
        var chromaColumns = current.chromaMbWidth;
        var chromaSource = source != null && source.chromaFormat == current.chromaFormat;
        var count = pictureMbs();
        for (var address = 0; address < count; address++) {
            var mb = PictureState.mbIndex(address, structure, mbaff, width);
            if (pic.sliceNum[mb] >= firstSliceNum) {
                continue;
            }
            var x = mb % width;
            int luma;
            int chroma;
            if (field) {
                var y = (mb / width) >> 1;
                luma = current.lumaOrigin + parity * current.lumaStride + y * 16 * lumaPitch + x * 16;
                chroma = current.chromaOrigin + parity * current.chromaStride + y * chromaRows * chromaPitch
                        + x * chromaColumns;
            } else {
                var y = mb / width;
                luma = current.lumaOrigin + y * 16 * lumaPitch + x * 16;
                chroma = current.chromaOrigin + y * chromaRows * chromaPitch + x * chromaColumns;
            }
            if (current.wide) {
                concealWide(current, source, chromaSource, luma, lumaPitch, chroma, chromaPitch);
            } else {
                for (var line = 0; line < 16; line++) {
                    var at = luma + line * lumaPitch;
                    if (source != null) {
                        System.arraycopy(source.luma, at, current.luma, at, 16);
                    } else {
                        Arrays.fill(current.luma, at, at + 16, (byte) 128);
                    }
                }
                if (current.hasChroma) {
                    for (var line = 0; line < chromaRows; line++) {
                        var at = chroma + line * chromaPitch;
                        if (chromaSource) {
                            System.arraycopy(source.cb, at, current.cb, at, chromaColumns);
                            System.arraycopy(source.cr, at, current.cr, at, chromaColumns);
                        } else {
                            Arrays.fill(current.cb, at, at + chromaColumns, (byte) 128);
                            Arrays.fill(current.cr, at, at + chromaColumns, (byte) 128);
                        }
                    }
                }
            }
            current.mbType[mb] = field ? DecoderFrame.MB_INTRA | DecoderFrame.MB_FIELD : DecoderFrame.MB_INTRA;
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
            pic.qpCb[mb] = 0;
            pic.qpCr[mb] = 0;
            pic.nonZero[mb] = 0;
            decodedMbs++;
            if (!mbaff) {
                rowDecoded[field ? (mb / width) >> 1 : mb / width]++;
            }
        }
    }

    /**
     * Conceals the samples of one macroblock of a frame of 16-bit samples by copying the
     * co-located samples of the source frame, or with mid-gray when there is none.
     *
     * @param current the frame being concealed
     * @param source the frame to copy from, of the same size and bit depths, or {@code null}
     * @param chromaSource whether {@code source} has the chroma format of {@code current}, which
     * its chroma is copied from only then
     * @param luma the index of the macroblock's top-left luma sample
     * @param lumaPitch the distance between the macroblock's luma rows
     * @param chroma the index of the macroblock's top-left chroma sample
     * @param chromaPitch the distance between the macroblock's chroma rows
     */
    private static void concealWide(DecoderFrame current, DecoderFrame source, boolean chromaSource, int luma,
                                    int lumaPitch, int chroma, int chromaPitch) {
        var lumaGray = (short) DecoderFrame.gray(current.bitDepthLuma);
        for (var line = 0; line < 16; line++) {
            var at = luma + line * lumaPitch;
            if (source != null) {
                System.arraycopy(source.luma16, at, current.luma16, at, 16);
            } else {
                Arrays.fill(current.luma16, at, at + 16, lumaGray);
            }
        }
        if (!current.hasChroma) {
            return;
        }
        var chromaGray = (short) DecoderFrame.gray(current.bitDepthChroma);
        for (var line = 0; line < current.chromaMbHeight; line++) {
            var at = chroma + line * chromaPitch;
            var columns = current.chromaMbWidth;
            if (chromaSource) {
                System.arraycopy(source.cb16, at, current.cb16, at, columns);
                System.arraycopy(source.cr16, at, current.cr16, at, columns);
            } else {
                Arrays.fill(current.cb16, at, at + columns, chromaGray);
                Arrays.fill(current.cr16, at, at + columns, chromaGray);
            }
        }
    }
}
