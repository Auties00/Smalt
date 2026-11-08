package com.github.auties00.smalt.video.hevc.internal;

/**
 * Holds the variables a P or B slice of a layer decoded with the process of Annex I derives at
 * its start (ITU-T H.265 I.8.1.2 and I.8.3), and the pictures of other views the 3D-HEVC tools
 * read: the candidate pictures for disparity vector derivation (I.8.3.1), the default reference
 * view order index (I.8.3.2), the alternative target reference indices of temporal merging
 * candidates (I.8.3.4), the target reference indices of advanced residual prediction (I.8.3.5),
 * the views of the reference pictures, and the pictures of the reference views in the access unit
 * and in the decoded picture buffer.
 *
 * <p>The engine builds an instance when it builds the reference picture lists of a slice segment;
 * the slice decoder reads it.
 */
public final class InterViewContext {
    /**
     * The number of view order indices: a view order index is a scalability identifier of at most 8
     * bits (F.7.4.3.1.1), so every view of a set is in range.
     */
    static final int MAX_VIEWS = 256;

    /**
     * The 3D-HEVC part of the slice segment header.
     */
    public final Hevc3dSlice tools;

    /**
     * {@code ViewIdx} of each reference picture, indexed {@code [list][refIdx]}.
     */
    public final int[][] refViewIdx = new int[2][16];

    /**
     * {@code ViewIdVal} of each reference picture, indexed {@code [list][refIdx]}.
     */
    public final int[][] refViewId = new int[2][16];

    /**
     * {@code ViewIdVal} of the current picture.
     */
    public int viewId;

    /**
     * {@code ViewOrderIdx}, by layer identifier.
     */
    public int[] viewOrderIdx;

    /**
     * {@code view_id_val}, by view order index.
     */
    public int[] viewIdVal;

    /**
     * {@code NumDdvCandPics}.
     */
    public int numDdvCandPics;

    /**
     * {@code DdvCandPicList}.
     */
    public final HevcFrame[] ddvCandPic = new HevcFrame[2];

    /**
     * {@code DefaultRefViewIdx}, -1 when no inter-view reference picture of the access unit is in
     * the lists.
     */
    public int defaultRefViewIdx = -1;

    /**
     * {@code DispAvailFlag}.
     */
    public boolean dispAvail;

    /**
     * {@code AltRefIdxL0} and {@code AltRefIdxL1}, -1 when absent.
     */
    public final int[] altRefIdx = {-1, -1};

    /**
     * {@code RpRefIdxL0} and {@code RpRefIdxL1}, -1 when absent.
     */
    public final int[] rpRefIdx = {-1, -1};

    /**
     * {@code RpRefPicAvailFlag}.
     */
    public boolean rpRefPicAvail;

    /**
     * {@code RefRpRefAvailFlagLX}, indexed {@code [list][viewIdx]}.
     */
    public final boolean[][] refRpRefAvail = new boolean[2][MAX_VIEWS];

    /**
     * The texture picture of each view in the access unit, or {@code null}, by view order index.
     */
    public final HevcFrame[] texture = new HevcFrame[MAX_VIEWS];

    /**
     * The depth picture of each view in the access unit, or {@code null}, by view order index.
     */
    public final HevcFrame[] depthPicture = new HevcFrame[MAX_VIEWS];

    /**
     * The decoded texture pictures of the decoded picture buffer when the slice started, which the
     * residual prediction finds by view and picture order count.
     */
    public HevcFrame[] textures = new HevcFrame[0];

    /**
     * The texture layer of each view, or -1, by view order index.
     */
    public final int[] textureLayer = new int[MAX_VIEWS];

    /**
     * Constructs an empty context.
     *
     * @param tools the 3D-HEVC part of the slice segment header
     */
    public InterViewContext(Hevc3dSlice tools) {
        this.tools = tools;
        java.util.Arrays.fill(textureLayer, -1);
    }

    /**
     * {@return the view order index of a reference picture of a slice of another picture}, from
     * the layer it recorded for the entry
     *
     * @param picture the picture whose slice is meant
     * @param slice the slice index in the picture's slice tables
     * @param entry the reference list entry, {@code list * 16 + refIdx}
     */
    public int viewOfReference(HevcFrame picture, int slice, int entry) {
        var layers = picture.sliceRefLayer[slice];
        if (layers == null || layers[entry] < 0) {
            return -1;
        }
        return viewOrderIdx[layers[entry]];
    }

    /**
     * Returns the decoded texture picture of a view with a picture order count.
     *
     * @param view the view order index
     * @param poc the picture order count
     * @return the picture, or {@code null} when the decoded picture buffer held none
     */
    public HevcFrame texture(int view, int poc) {
        if (view < 0 || view >= MAX_VIEWS) {
            return null;
        }
        var layer = textureLayer[view];
        for (var frame : textures) {
            if (frame.layerId == layer && frame.poc == poc) {
                return frame;
            }
        }
        return null;
    }

    /**
     * {@return whether the reference picture lists of the slice hold a picture of a view}
     *
     * @param view the view order index
     * @param numRefIdx the sizes of the lists
     */
    public boolean listsHoldView(int view, int[] numRefIdx) {
        for (var list = 0; list < 2; list++) {
            for (var i = 0; i < numRefIdx[list]; i++) {
                if (refViewIdx[list][i] == view) {
                    return true;
                }
            }
        }
        return false;
    }
}
