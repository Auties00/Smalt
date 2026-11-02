package com.github.auties00.smalt.video.h264.internal;

/**
 * Finds the co-located motion of the direct prediction of a B slice of the layer representation
 * with DQId 0 (ITU-T H.264 8.4.1.2.1, Tables 8-6 and 8-8), for frames, frames with
 * {@code MbaffFrameFlag} 1 and fields, and maps the reference picture of the co-located motion into
 * the list 0 of the current slice for the temporal direct prediction (8.4.1.2.3).
 */
final class SvcColocated {
    /**
     * The vertical motion vector scaling {@code One_To_One}.
     */
    static final int ONE_TO_ONE = 0;

    /**
     * The vertical motion vector scaling {@code Frm_To_Fld}.
     */
    static final int FRM_TO_FLD = 1;

    /**
     * The vertical motion vector scaling {@code Fld_To_Frm}.
     */
    static final int FLD_TO_FRM = 2;

    /**
     * The co-located reference index {@code refIdxCol}, -1 for an intra co-located macroblock.
     */
    int refIdxCol;

    /**
     * The horizontal component of the co-located motion vector {@code mvCol}.
     */
    int mvColX;

    /**
     * The vertical component of the co-located motion vector {@code mvCol}, unscaled.
     */
    int mvColY;

    /**
     * The picture {@code refPicCol} the co-located reference index referred to, as
     * {@link SvcMotionStore#refPic0} identifies it.
     */
    int refPicCol;

    /**
     * The {@code vertMvScale}: {@link #ONE_TO_ONE}, {@link #FRM_TO_FLD} or {@link #FLD_TO_FRM}.
     */
    int vertMvScale;

    /**
     * The slice the co-located motion is derived for.
     */
    private SvcSlice slice;

    /**
     * The structure of the current picture: {@link SvcPicture#FRAME}, {@link SvcPicture#TOP} or
     * {@link SvcPicture#BOTTOM}.
     */
    private int structure;

    /**
     * Whether the current picture is a frame with {@code MbaffFrameFlag} 1.
     */
    private boolean mbaff;

    /**
     * The width of the current picture in macroblocks.
     */
    private int widthMbs;

    /**
     * The picture order counts of the current picture: frame, top field, bottom field.
     */
    private final int[] currentPocs = new int[4];

    /**
     * The entry {@code RefPicList1[ 0 ]} of the slice, or {@code null}.
     */
    private SvcPicture first;

    /**
     * Whether the motion of {@link #first} may differ from the encoder's: the picture was lost or
     * damaged.
     */
    private boolean unreliable;

    /**
     * Whether co-located motion of an unreliable picture was read since
     * {@link #clearUnreliable()}.
     */
    private boolean readUnreliable;

    /**
     * The structure of {@code RefPicList1[ 0 ]}.
     */
    private int firstStructure;

    /**
     * Constructs a co-located motion derivation.
     */
    SvcColocated() {
    }

    /**
     * Prepares the derivation for the macroblocks of a B slice.
     *
     * @param slice the slice, whose reference picture lists are built
     * @param layer the arrays of the current layer representation
     * @param structure the structure of the current picture
     * @param framePoc the picture order count of the current picture
     * @param topPoc the {@code TopFieldOrderCnt} of the current picture
     * @param bottomPoc the {@code BottomFieldOrderCnt} of the current picture
     * @return whether the slice has a co-located picture
     */
    boolean begin(SvcSlice slice, SvcLayer layer, int structure, int framePoc, int topPoc, int bottomPoc) {
        this.slice = slice;
        this.structure = structure;
        this.mbaff = layer.mbaff;
        this.widthMbs = layer.widthMbs;
        currentPocs[SvcPicture.FRAME] = framePoc;
        currentPocs[SvcPicture.TOP] = topPoc;
        currentPocs[SvcPicture.BOTTOM] = bottomPoc;
        first = slice.refLists != null && slice.refLists[1].length > 0 ? slice.refLists[1][0] : null;
        firstStructure = first != null ? slice.refStructures[1][0] : SvcPicture.FRAME;
        unreliable = first != null && (first.nonExisting || first.damaged);
        return first != null;
    }

    /**
     * Forgets the reads of unreliable co-located motion, for a new access unit.
     */
    void clearUnreliable() {
        readUnreliable = false;
    }

    /**
     * Returns whether co-located motion of a lost or damaged picture was read since
     * {@link #clearUnreliable()}.
     *
     * @return {@code true} when a direct prediction may differ from the encoder's
     */
    boolean readUnreliable() {
        return readUnreliable;
    }

    /**
     * Returns whether {@code RefPicList1[ 0 ]}, or its field of a parity, is a short-term reference.
     *
     * @param fieldMb whether the current macroblock is a field macroblock of a frame
     * @param mbAddr the current macroblock address
     * @return {@code true} when marked "used for short-term reference"
     */
    boolean firstShortTerm(boolean fieldMb, int mbAddr) {
        if (first == null) {
            return false;
        }
        if (firstStructure != SvcPicture.FRAME) {
            return first.marking(firstStructure) == SvcPicture.SHORT_TERM;
        }
        if (fieldMb) {
            return first.marking((mbAddr & 1) == 0 ? SvcPicture.TOP : SvcPicture.BOTTOM) == SvcPicture.SHORT_TERM;
        }
        return first.marked(SvcPicture.FRAME, SvcPicture.SHORT_TERM);
    }

    /**
     * Derives the co-located motion of a 4x4 block of the current macroblock into
     * {@link #refIdxCol}, {@link #mvColX}, {@link #mvColY}, {@link #refPicCol} and
     * {@link #vertMvScale}.
     *
     * @param mbAddr the current macroblock address
     * @param fieldMb whether the current macroblock is a field macroblock of a frame
     * @param xCol the horizontal luma location of the block in the macroblock
     * @param yCol the vertical luma location of the block in the macroblock
     */
    void locate(int mbAddr, boolean fieldMb, int xCol, int yCol) {
        refIdxCol = -1;
        mvColX = 0;
        mvColY = 0;
        refPicCol = -1;
        vertMvScale = ONE_TO_ONE;
        if (first == null) {
            return;
        }
        readUnreliable |= unreliable;
        SvcMotionStore col;
        if (structure != SvcPicture.FRAME) {
            col = first.codedFrame ? first.topMotion : first.motion(firstStructure);
        } else if (first.codedFrame) {
            col = first.topMotion;
        } else if (!fieldMb) {
            col = topCloser() ? first.topMotion : first.bottomMotion;
        } else {
            col = (mbAddr & 1) == 0 ? first.topMotion : first.bottomMotion;
        }
        if (!col.present) {
            return;
        }
        var w = widthMbs;
        int mbCol;
        int yM;
        if (structure != SvcPicture.FRAME) {
            if (col.field) {
                mbCol = mbAddr;
                yM = yCol;
            } else if (!col.mbaff) {
                mbCol = 2 * w * (mbAddr / w) + (mbAddr % w) + w * (yCol / 8);
                yM = (2 * yCol) % 16;
                vertMvScale = FRM_TO_FLD;
            } else if (!col.fieldMb[2 * mbAddr]) {
                mbCol = 2 * mbAddr + (yCol / 8);
                yM = (2 * yCol) % 16;
                vertMvScale = FRM_TO_FLD;
            } else {
                mbCol = 2 * mbAddr + (structure == SvcPicture.BOTTOM ? 1 : 0);
                yM = yCol;
            }
        } else if (!mbaff) {
            if (col.field) {
                mbCol = w * (mbAddr / (2 * w)) + (mbAddr % w);
                yM = 8 * ((mbAddr / w) % 2) + 4 * (yCol / 8);
                vertMvScale = FLD_TO_FRM;
            } else {
                mbCol = mbAddr;
                yM = yCol;
            }
        } else if (col.field) {
            mbCol = mbAddr / 2;
            if (!fieldMb) {
                yM = 8 * (mbAddr % 2) + 4 * (yCol / 8);
                vertMvScale = FLD_TO_FRM;
            } else {
                yM = yCol;
            }
        } else if (!fieldMb) {
            if (!col.fieldMb[mbAddr]) {
                mbCol = mbAddr;
                yM = yCol;
            } else {
                mbCol = 2 * (mbAddr / 2) + (topCloser() ? 0 : 1);
                yM = 8 * (mbAddr % 2) + 4 * (yCol / 8);
                vertMvScale = FLD_TO_FRM;
            }
        } else if (!col.fieldMb[mbAddr]) {
            mbCol = 2 * (mbAddr / 2) + (yCol / 8);
            yM = (2 * yCol) % 16;
            vertMvScale = FRM_TO_FLD;
        } else {
            mbCol = mbAddr;
            yM = yCol;
        }
        if (mbCol < 0 || mbCol >= col.mbCount) {
            return;
        }
        var quadrant = mbCol * 4 + (yM / 8) * 2 + xCol / 8;
        var block = mbCol * 32 + ((yM / 4) * 4 + xCol / 4) * 2;
        if (col.refIdx0[quadrant] >= 0) {
            refIdxCol = col.refIdx0[quadrant];
            refPicCol = col.refPic0[quadrant];
            mvColX = col.mv0[block];
            mvColY = col.mv0[block + 1];
        } else if (col.refIdx1[quadrant] >= 0) {
            refIdxCol = col.refIdx1[quadrant];
            refPicCol = col.refPic1[quadrant];
            mvColX = col.mv1[block];
            mvColY = col.mv1[block + 1];
        }
    }

    /**
     * Returns whether the top field of {@code RefPicList1[ 0 ]} is closer in output order to the
     * current picture than its bottom field (ITU-T H.264 equations 8-175 and 8-176).
     *
     * @return {@code topAbsDiffPOC < bottomAbsDiffPOC}
     */
    private boolean topCloser() {
        var current = currentPocs[SvcPicture.FRAME];
        return Math.abs(first.topPoc - current) < Math.abs(first.bottomPoc - current);
    }

    /**
     * Returns the list 0 reference index of the temporal direct prediction that refers to the
     * picture of the co-located motion ({@code MapColToList0}, ITU-T H.264 8.4.1.2.3).
     *
     * @param mbAddr the current macroblock address
     * @param fieldMb whether the current macroblock is a field macroblock of a frame
     * @return the reference index, 0 when the list holds no such picture
     */
    int mapToList0(int mbAddr, boolean fieldMb) {
        if (refIdxCol < 0 || refPicCol < 0) {
            return 0;
        }
        var id = refPicCol >> 2;
        var colStructure = refPicCol & 3;
        var pictures = slice.refLists[0];
        var structures = slice.refStructures[0];
        var parity = (mbAddr & 1) == 0 ? SvcPicture.TOP : SvcPicture.BOTTOM;
        if (vertMvScale == ONE_TO_ONE) {
            if (structure == SvcPicture.FRAME && fieldMb) {
                var frameIndex = lowest(pictures, structures, id, -1);
                return frameIndex < 0 ? 0 : (frameIndex << 1) + (colStructure == parity ? 0 : 1);
            }
            var index = lowest(pictures, structures, id, structure != SvcPicture.FRAME ? colStructure : -1);
            return Math.max(index, 0);
        }
        if (vertMvScale == FRM_TO_FLD) {
            if (structure == SvcPicture.FRAME) {
                var frameIndex = lowest(pictures, structures, id, -1);
                return frameIndex < 0 ? 0 : frameIndex << 1;
            }
            return Math.max(lowest(pictures, structures, id, structure), 0);
        }
        return Math.max(lowest(pictures, structures, id, -1), 0);
    }

    /**
     * Returns the lowest index of a list referring to an entry, and to a field of it.
     *
     * @param pictures the entries of the list
     * @param structures the structures of the list
     * @param id the {@link SvcPicture#id} of the entry
     * @param fieldStructure the field wanted, or -1 for any reference to the entry
     * @return the index, or -1 when the list holds none
     */
    private static int lowest(SvcPicture[] pictures, int[] structures, int id, int fieldStructure) {
        for (var i = 0; i < pictures.length; i++) {
            var picture = pictures[i];
            if (picture != null && picture.id == id && (fieldStructure < 0 || structures[i] == fieldStructure)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the picture order count of a reference of a list of the slice, as the temporal
     * direct and the implicit weighted prediction read it: the field of a field macroblock, or the
     * picture the list names.
     *
     * @param list the list index
     * @param refIdx the reference index
     * @param mbAddr the current macroblock address
     * @param fieldMb whether the current macroblock is a field macroblock of a frame
     * @return the picture order count
     */
    int referencePoc(int list, int refIdx, int mbAddr, boolean fieldMb) {
        return referencePoc(slice, list, refIdx, mbAddr, fieldMb);
    }

    /**
     * Returns the picture order count of a reference of a list of a slice, as the temporal direct
     * and the implicit weighted prediction read it: for a field macroblock of a frame, the field of
     * the frame the index names, of the parity of the macroblock for even indices and of the
     * opposite parity for odd ones; otherwise the picture the list names.
     *
     * @param slice the slice whose lists hold the reference
     * @param list the list index
     * @param refIdx the reference index
     * @param mbAddr the current macroblock address
     * @param fieldMb whether the current macroblock is a field macroblock of a frame
     * @return the picture order count, or {@link Integer#MIN_VALUE} when the list names no picture
     */
    static int referencePoc(SvcSlice slice, int list, int refIdx, int mbAddr, boolean fieldMb) {
        var index = fieldMb ? refIdx >> 1 : refIdx;
        if (slice.refLists == null || index < 0 || index >= slice.refLists[list].length) {
            return Integer.MIN_VALUE;
        }
        var picture = slice.refLists[list][index];
        if (picture == null) {
            return Integer.MIN_VALUE;
        }
        if (fieldMb) {
            var parity = (mbAddr & 1) == 0 ? SvcPicture.TOP : SvcPicture.BOTTOM;
            return picture.fieldPoc((refIdx & 1) == 0 ? parity : SvcPicture.FRAME - parity);
        }
        return picture.structurePoc(slice.refStructures[list][index]);
    }

    /**
     * Returns whether a reference of a list of a slice is a long-term reference: for a field
     * macroblock of a frame, the field the index names.
     *
     * @param slice the slice whose lists hold the reference
     * @param list the list index
     * @param refIdx the reference index
     * @param mbAddr the current macroblock address
     * @param fieldMb whether the current macroblock is a field macroblock of a frame
     * @return {@code true} when marked "used for long-term reference"
     */
    static boolean longTerm(SvcSlice slice, int list, int refIdx, int mbAddr, boolean fieldMb) {
        var index = fieldMb ? refIdx >> 1 : refIdx;
        if (slice.refLists == null || index < 0 || index >= slice.refLists[list].length) {
            return false;
        }
        var picture = slice.refLists[list][index];
        if (picture == null) {
            return false;
        }
        int structure;
        if (fieldMb) {
            var parity = (mbAddr & 1) == 0 ? SvcPicture.TOP : SvcPicture.BOTTOM;
            structure = (refIdx & 1) == 0 ? parity : SvcPicture.FRAME - parity;
        } else {
            structure = slice.refStructures[list][index];
        }
        return structure == SvcPicture.FRAME ? picture.anyLongTerm() : picture.marking(structure) == SvcPicture.LONG_TERM;
    }

    /**
     * Returns the picture order count of the current picture or of its field of the parity of a
     * field macroblock ({@code currPicOrField} of ITU-T H.264 8.4.1.2.3).
     *
     * @param mbAddr the current macroblock address
     * @param fieldMb whether the current macroblock is a field macroblock of a frame
     * @return the picture order count
     */
    int currentPoc(int mbAddr, boolean fieldMb) {
        if (fieldMb && structure == SvcPicture.FRAME) {
            return currentPocs[(mbAddr & 1) == 0 ? SvcPicture.TOP : SvcPicture.BOTTOM];
        }
        return currentPocs[structure];
    }
}
