/* Ports VVdeC 3.3.0 (source/Lib/DecoderLib/HLSyntaxReader.cpp). Copyright (c) 2018-2026, Fraunhofer-Gesellschaft
 * zur Foerderung der angewandten Forschung e.V. & The VVdeC Authors; Clear BSD License. Modified for Smalt. */
package com.github.auties00.smalt.video.vvc.internal;

import com.github.auties00.smalt.video.h264.internal.H264BitReader;

/**
 * Holds a reference picture list structure ({@code ref_pic_list_struct}, ITU-T H.266 7.3.10).
 *
 * <p>For a short-term entry, {@link #identifier} is the POC difference to the current picture (negative for
 * earlier pictures, accumulated as equation 149 specifies); for a long-term entry it is {@code poc_lsb_lt}.
 */
public final class VvcRefPicList {
    /**
     * Constructs an empty list.
     */
    public VvcRefPicList() {
    }

    /**
     * The most entries a list may hold.
     */
    static final int MAX_ENTRIES = 29;

    /**
     * {@code num_ref_entries}.
     */
    public int numEntries;
    /**
     * {@code ltrp_in_header_flag}.
     */
    public boolean ltrpInHeader;
    /**
     * The identifier of each entry, as the class description defines it.
     */
    public final int[] identifier = new int[MAX_ENTRIES];
    /**
     * Whether each entry is a long-term reference picture.
     */
    public final boolean[] longTerm = new boolean[MAX_ENTRIES];
    /**
     * Whether each entry is an inter-layer reference picture ({@code inter_layer_ref_pic_flag}).
     */
    public final boolean[] interLayer = new boolean[MAX_ENTRIES];
    /**
     * {@code delta_poc_msb_cycle_present_flag} of each long-term entry.
     */
    public final boolean[] msbPresent = new boolean[MAX_ENTRIES];
    /**
     * {@code DeltaPocMsbCycleLt} of each long-term entry.
     */
    public final int[] msbCycle = new int[MAX_ENTRIES];

    /**
     * Parses {@code ref_pic_list_struct( listIdx, rplsIdx )}.
     *
     * @param r the reader
     * @param sps the active sequence parameter set
     * @param index {@code rplsIdx}, or -1 for a list coded in a picture or slice header
     * @return the list
     */
    static VvcRefPicList parse(H264BitReader r, VvcSps sps, int index) {
        var l = new VvcRefPicList();
        l.numEntries = r.readUe(MAX_ENTRIES, "num_ref_entries");
        if (sps.longTermRefs && l.numEntries > 0 && index != -1) {
            l.ltrpInHeader = r.readFlag();
        } else if (sps.longTermRefs) {
            l.ltrpInHeader = true;
        }
        var prev = 0;
        for (var i = 0; i < l.numEntries; i++) {
            if (sps.interLayerPrediction && r.readFlag()) {
                l.interLayer[i] = true;
                l.identifier[i] = r.readUe(63, "ilrp_idx");
                continue;
            }
            var longTerm = sps.longTermRefs && !r.readFlag();
            if (!longTerm) {
                var delta = r.readUe((1 << 15) - 1, "abs_delta_poc_st");
                if ((!sps.weightedPred && !sps.weightedBipred) || i == 0) {
                    delta++;
                }
                if (delta > 0 && r.readFlag()) {
                    delta = -delta;
                }
                delta += prev;
                prev = delta;
                l.identifier[i] = delta;
            } else {
                l.longTerm[i] = true;
                if (!l.ltrpInHeader) {
                    l.identifier[i] = r.readBits(sps.log2MaxPocLsb);
                }
            }
        }
        return l;
    }

    /**
     * {@return a copy of this list}
     */
    VvcRefPicList copy() {
        var c = new VvcRefPicList();
        copyInto(c);
        return c;
    }

    /**
     * Copies this list into another.
     *
     * @param c the destination
     */
    void copyInto(VvcRefPicList c) {
        c.numEntries = numEntries;
        c.ltrpInHeader = ltrpInHeader;
        System.arraycopy(identifier, 0, c.identifier, 0, MAX_ENTRIES);
        System.arraycopy(longTerm, 0, c.longTerm, 0, MAX_ENTRIES);
        System.arraycopy(interLayer, 0, c.interLayer, 0, MAX_ENTRIES);
        System.arraycopy(msbPresent, 0, c.msbPresent, 0, MAX_ENTRIES);
        System.arraycopy(msbCycle, 0, c.msbCycle, 0, MAX_ENTRIES);
    }

    /**
     * {@return the number of long-term entries}
     */
    int numLongTerm() {
        var n = 0;
        for (var i = 0; i < numEntries; i++) {
            if (longTerm[i]) {
                n++;
            }
        }
        return n;
    }

    /**
     * Parses {@code ref_pic_lists( )} of a picture or slice header into two lists.
     *
     * @param r the reader
     * @param sps the active sequence parameter set
     * @param pps the active picture parameter set
     * @param lists the two destination lists
     * @param rplIdx the two {@code RplsIdx} values written back, -1 for lists coded in the header
     */
    static void parseHeaderLists(H264BitReader r, VvcSps sps, VvcPps pps, VvcRefPicList[] lists, int[] rplIdx) {
        var spsFlag = new boolean[2];
        for (var li = 0; li < 2; li++) {
            var count = sps.rplLists[li].length;
            if (count > 0 && (li == 0 || pps.rpl1IdxPresent)) {
                spsFlag[li] = r.readFlag();
            } else if (count == 0) {
                spsFlag[li] = false;
            } else {
                spsFlag[li] = spsFlag[0];
            }
            if (spsFlag[li]) {
                var idx = 0;
                if (li == 1 && !pps.rpl1IdxPresent && count > 1) {
                    idx = rplIdx[0];
                }
                if (count > 1 && (li == 0 || pps.rpl1IdxPresent)) {
                    idx = r.readBits(VvcSps.ceilLog2(count));
                }
                if (idx >= count) {
                    throw new com.github.auties00.smalt.MediaException.Malformed("vvc rpl_idx out of range");
                }
                sps.rplLists[li][idx].copyInto(lists[li]);
                if (!sps.longTermRefs) {
                    for (var i = 0; i < lists[li].numEntries; i++) {
                        lists[li].longTerm[i] = false;
                    }
                }
                rplIdx[li] = idx;
            } else {
                parse(r, sps, -1).copyInto(lists[li]);
                rplIdx[li] = -1;
            }
            var list = lists[li];
            var cycle = 0;
            for (var j = 0; j < list.numEntries; j++) {
                list.msbPresent[j] = false;
                list.msbCycle[j] = 0;
                if (!list.longTerm[j]) {
                    continue;
                }
                if (list.ltrpInHeader) {
                    list.identifier[j] = r.readBits(sps.log2MaxPocLsb);
                }
                list.msbPresent[j] = r.readFlag();
                if (list.msbPresent[j]) {
                    cycle += r.readUe();
                    list.msbCycle[j] = cycle;
                }
            }
        }
    }
}
