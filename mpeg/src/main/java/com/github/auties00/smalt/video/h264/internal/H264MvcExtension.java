package com.github.auties00.smalt.video.h264.internal;

import com.github.auties00.smalt.MediaException;

import java.util.Arrays;

/**
 * Holds a parsed sequence parameter set MVC extension (ITU-T H.264 G.7.3.2.1.4): the views of a
 * multiview sequence and the inter-view dependencies of their view components.
 *
 * <p>Instances are immutable after parsing.
 */
public final class H264MvcExtension {
    /**
     * The {@code view_id} of each view, indexed by view order index.
     */
    final int[] viewIds;

    /**
     * The {@code view_id}s of the inter-view references of the anchor view components of each view,
     * indexed {@code [voIdx][list][j]}.
     */
    final int[][][] anchorRefs;

    /**
     * The {@code view_id}s of the inter-view references of the non-anchor view components of each
     * view, indexed {@code [voIdx][list][j]}.
     */
    final int[][][] nonAnchorRefs;

    /**
     * The {@code mfc_format_idc} of the MFC High profile, or -1 when absent: 0 for side-by-side base view
     * components with top-bottom enhancement view components, 1 for the reverse.
     */
    final int mfcFormatIdc;

    /**
     * The {@code view0_grid_position_x}, {@code view0_grid_position_y}, {@code view1_grid_position_x} and
     * {@code view1_grid_position_y} of an MFC extension, as signaled or inferred (ITU-T H.264 G.7.4.2.1.4);
     * 8 each for other extensions.
     */
    final int[] gridPositions;

    /**
     * The {@code rpu_filter_enabled_flag} of an MFC extension.
     */
    final boolean rpuFilterEnabled;

    /**
     * The {@code rpu_field_processing_flag} of an MFC extension, {@code false} when absent.
     */
    final boolean rpuFieldProcessing;

    /**
     * Whether the extension is the depth part of an MVCD extension (ITU-T H.264 H.7.3.2.1.5): its views are
     * the views with a depth view, in view order, so that the first of them decodes on its own, and its
     * inter-view references are those of the depth views.
     */
    final boolean depth;

    /**
     * The view order index each view of a depth extension has among all the views of its MVCD extension,
     * or {@code null} for an MVC extension, whose indices are its own.
     */
    final int[] fullVoIdx;

    /**
     * Creates the extension from its parsed syntax elements.
     *
     * @param viewIds the {@code view_id} of each view, in view order
     * @param anchorRefs the inter-view references of the anchor view components, per view order
     * index and list
     * @param nonAnchorRefs the inter-view references of the non-anchor view components, per view
     * order index and list
     * @param mfcFormatIdc the {@code mfc_format_idc}, or -1 when absent
     * @param gridPositions the grid positions of the two views of an MFC extension
     * @param rpuFilterEnabled the {@code rpu_filter_enabled_flag}
     * @param rpuFieldProcessing the {@code rpu_field_processing_flag}
     * @param fullVoIdx the view order index of each view among all the views of an MVCD extension, or
     * {@code null} for an MVC extension
     */
    private H264MvcExtension(int[] viewIds, int[][][] anchorRefs, int[][][] nonAnchorRefs, int mfcFormatIdc,
                             int[] gridPositions, boolean rpuFilterEnabled, boolean rpuFieldProcessing,
                             int[] fullVoIdx) {
        this.viewIds = viewIds;
        this.anchorRefs = anchorRefs;
        this.nonAnchorRefs = nonAnchorRefs;
        this.mfcFormatIdc = mfcFormatIdc;
        this.gridPositions = gridPositions;
        this.rpuFilterEnabled = rpuFilterEnabled;
        this.rpuFieldProcessing = rpuFieldProcessing;
        this.depth = fullVoIdx != null;
        this.fullVoIdx = fullVoIdx;
    }

    /**
     * Parses a {@code seq_parameter_set_mvc_extension()}.
     *
     * @param reader the reader positioned at the extension
     * @param profileIdc the {@code profile_idc} of the set
     * @param frameMbsOnly the {@code frame_mbs_only_flag} of the set
     * @return the extension
     * @throws MediaException.Malformed if a field is out of range or the extension is truncated
     */
    static H264MvcExtension parse(H264BitReader reader, int profileIdc, boolean frameMbsOnly) {
        var count = reader.readUe() + 1;
        if (count > 1024) {
            throw new MediaException.Malformed("num_views_minus1 out of range");
        }
        var viewIds = new int[count];
        for (var i = 0; i < count; i++) {
            viewIds[i] = reader.readUe();
            if (viewIds[i] > 1023 || reader.overrun()) {
                throw new MediaException.Malformed("view_id out of range");
            }
        }
        var anchorRefs = new int[count][2][0];
        var nonAnchorRefs = new int[count][2][0];
        for (var i = 1; i < count; i++) {
            for (var list = 0; list < 2; list++) {
                anchorRefs[i][list] = readRefs(reader, count);
            }
        }
        for (var i = 1; i < count; i++) {
            for (var list = 0; list < 2; list++) {
                nonAnchorRefs[i][list] = readRefs(reader, count);
            }
        }
        var levels = reader.readUe() + 1;
        if (levels > 64) {
            throw new MediaException.Malformed("num_level_values_signalled_minus1 out of range");
        }
        for (var i = 0; i < levels; i++) {
            reader.readBits(8);
            var operations = reader.readUe() + 1;
            if (operations > 1024) {
                throw new MediaException.Malformed("num_applicable_ops_minus1 out of range");
            }
            for (var j = 0; j < operations; j++) {
                reader.readBits(3);
                var targets = reader.readUe() + 1;
                if (targets > 1024 || reader.overrun()) {
                    throw new MediaException.Malformed("applicable_op_num_target_views_minus1 out of range");
                }
                for (var k = 0; k < targets; k++) {
                    reader.readUe();
                }
                reader.readUe();
            }
        }
        var mfcFormatIdc = -1;
        var grid = new int[]{8, 8, 8, 8};
        var rpuFilter = false;
        var rpuField = false;
        if (profileIdc == 134) {
            mfcFormatIdc = reader.readBits(6);
            if (mfcFormatIdc > 1) {
                throw new MediaException.Malformed("mfc_format_idc " + mfcFormatIdc + " out of range");
            }
            if (reader.readFlag()) {
                grid = mfcFormatIdc == 0 ? new int[]{4, 8, 12, 8} : new int[]{8, 4, 8, 12};
            } else {
                for (var k = 0; k < 4; k++) {
                    grid[k] = reader.readBits(4);
                    if (grid[k] != 4 && grid[k] != 8 && grid[k] != 12) {
                        throw new MediaException.Malformed("MFC grid position out of range");
                    }
                }
            }
            rpuFilter = reader.readFlag();
            if (!frameMbsOnly) {
                rpuField = reader.readFlag();
            }
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated sequence parameter set MVC extension");
        }
        return new H264MvcExtension(viewIds, anchorRefs, nonAnchorRefs, mfcFormatIdc, grid, rpuFilter, rpuField,
                null);
    }

    /**
     * Parses a {@code seq_parameter_set_mvcd_extension()} (ITU-T H.264 H.7.3.2.1.5) into an extension of
     * its depth views (see {@link #depth}); the operation points that follow are skipped.
     *
     * @param reader the reader positioned at the extension
     * @return the extension
     * @throws MediaException.Malformed if a field is out of range or the extension is truncated
     */
    static H264MvcExtension parseMvcd(H264BitReader reader) {
        var count = reader.readUe() + 1;
        if (count > 1024) {
            throw new MediaException.Malformed("num_views_minus1 out of range");
        }
        var viewIds = new int[count];
        var depthPresent = new boolean[count];
        var texturePresent = new boolean[count];
        for (var i = 0; i < count; i++) {
            viewIds[i] = reader.readUe();
            if (viewIds[i] > 1023 || reader.overrun()) {
                throw new MediaException.Malformed("view_id out of range");
            }
            depthPresent[i] = reader.readFlag();
            texturePresent[i] = reader.readFlag();
            if (!depthPresent[i] && !texturePresent[i]) {
                throw new MediaException.Malformed("view with neither texture nor depth");
            }
        }
        var anchorRefs = new int[count][2][0];
        var nonAnchorRefs = new int[count][2][0];
        for (var i = 1; i < count; i++) {
            if (depthPresent[i]) {
                for (var list = 0; list < 2; list++) {
                    anchorRefs[i][list] = readRefs(reader, count);
                }
            }
        }
        for (var i = 1; i < count; i++) {
            if (depthPresent[i]) {
                for (var list = 0; list < 2; list++) {
                    nonAnchorRefs[i][list] = readRefs(reader, count);
                }
            }
        }
        if (reader.overrun()) {
            throw new MediaException.Malformed("truncated sequence parameter set MVCD extension");
        }
        var depthViews = 0;
        for (var present : depthPresent) {
            depthViews += present ? 1 : 0;
        }
        var depthIds = new int[depthViews];
        var depthAnchorRefs = new int[depthViews][][];
        var depthNonAnchorRefs = new int[depthViews][][];
        var fullVoIdx = new int[depthViews];
        for (int i = 0, k = 0; i < count; i++) {
            if (depthPresent[i]) {
                depthIds[k] = viewIds[i];
                depthAnchorRefs[k] = anchorRefs[i];
                depthNonAnchorRefs[k] = nonAnchorRefs[i];
                fullVoIdx[k] = i;
                k++;
            }
        }
        return new H264MvcExtension(depthIds, depthAnchorRefs, depthNonAnchorRefs, -1, new int[]{8, 8, 8, 8}, false,
                false, fullVoIdx);
    }

    /**
     * Reads a count followed by that many {@code view_id}s of inter-view references.
     *
     * @param reader the bit reader positioned at the count
     * @param views the number of views
     * @return the {@code view_id}s
     */
    private static int[] readRefs(H264BitReader reader, int views) {
        var count = reader.readUe();
        if (count > Math.min(15, views - 1) || reader.overrun()) {
            throw new MediaException.Malformed("number of inter-view references out of range");
        }
        var refs = new int[count];
        for (var j = 0; j < count; j++) {
            refs[j] = reader.readUe();
            if (refs[j] > 1023) {
                throw new MediaException.Malformed("inter-view reference view_id out of range");
            }
        }
        return refs;
    }

    /**
     * {@return the number of views, {@code num_views_minus1 + 1}}
     */
    public int views() {
        return viewIds.length;
    }

    /**
     * Returns the view order index of a view.
     *
     * @param viewId the {@code view_id}
     * @return the {@code VOIdx}, or -1 when the extension lists no such view
     */
    public int voIdxOf(int viewId) {
        for (var i = 0; i < viewIds.length; i++) {
            if (viewIds[i] == viewId) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Returns the {@code view_id} of a view.
     *
     * @param voIdx the view order index, in range
     * @return the {@code view_id}
     */
    public int viewIdOf(int voIdx) {
        return viewIds[voIdx];
    }

    /**
     * Returns the inter-view references of the view components of a view.
     *
     * @param voIdx the view order index of the view, in range
     * @param anchor whether the view component is an anchor view component
     * @param list the reference picture list, 0 or 1
     * @return the {@code view_id}s of the references, in list order; owned by this extension
     */
    int[] references(int voIdx, boolean anchor, int list) {
        return (anchor ? anchorRefs : nonAnchorRefs)[voIdx][list];
    }

    /**
     * Returns the view order indices of the views needed to decode a set of views: the views
     * themselves and every view they predict from, directly or through other views, in anchor or
     * non-anchor view components (ITU-T H.264 G.8.5.1 and G.8.5.2).
     *
     * @param targets whether each view order index is a target, indexed by view order index
     * @return whether each view order index is needed
     */
    boolean[] closure(boolean[] targets) {
        var needed = Arrays.copyOf(targets, viewIds.length);
        for (var changed = true; changed; ) {
            changed = false;
            for (var i = viewIds.length - 1; i > 0; i--) {
                if (!needed[i]) {
                    continue;
                }
                for (var refs : new int[][][]{anchorRefs[i], nonAnchorRefs[i]}) {
                    for (var list : refs) {
                        for (var ref : list) {
                            var index = voIdxOf(ref);
                            if (index >= 0 && !needed[index]) {
                                needed[index] = true;
                                changed = true;
                            }
                        }
                    }
                }
            }
        }
        return needed;
    }
}
