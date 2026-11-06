package com.github.auties00.smalt.video.h264.internal;

import java.util.ArrayList;
import java.util.List;

/**
 * Maintains the decoded picture buffer of one dependency representation of the scalable decoding
 * process (ITU-T H.264 F.8.2): the picture order counts of its pictures (F.8.2.1), the gaps in
 * {@code frame_num} (F.8.2.5), the picture numbers and the reference picture lists of its slices
 * (F.8.2.2 and F.8.2.3) and the marking of its reference pictures and reference base pictures
 * (F.8.2.4), for frames and fields alike.
 *
 * <p>The entries are frames, complementary field pairs and non-paired fields, each a
 * {@link SvcPicture}; a field picture is stored into the entry of the first field of its frame when
 * it completes a complementary field pair.
 */
final class SvcDpb {
    /**
     * The entries of the buffer.
     */
    final List<SvcPicture> entries = new ArrayList<>();

    /**
     * The {@code prevPicOrderCntMsb}.
     */
    int prevPocMsb;

    /**
     * The {@code prevPicOrderCntLsb}.
     */
    int prevPocLsb;

    /**
     * The {@code prevFrameNumOffset}.
     */
    int prevFrameNumOffset;

    /**
     * The {@code frame_num} of the previous picture.
     */
    int prevFrameNum;

    /**
     * Whether the previous picture had a memory management control operation 5.
     */
    boolean prevMmco5;

    /**
     * The {@code PrevRefFrameNum}.
     */
    int prevRefFrameNum;

    /**
     * Whether a picture of the dependency was decoded since the stream started.
     */
    boolean started;

    /**
     * The {@code MaxLongTermFrameIdx}, -1 for "no long-term frame indices".
     */
    int maxLongTermFrameIdx = -1;

    /**
     * The picture order count {@code PicOrderCnt( CurrPic )} of the current picture.
     */
    int poc;

    /**
     * The {@code TopFieldOrderCnt} of the current picture, when it has a top field.
     */
    int topPoc;

    /**
     * The {@code BottomFieldOrderCnt} of the current picture, when it has a bottom field.
     */
    int bottomPoc;

    /**
     * The {@code PicOrderCntMsb} of the current picture.
     */
    int pocMsb;

    /**
     * The {@code FrameNumOffset} of the current picture.
     */
    int frameNumOffset;

    /**
     * The structure of the current picture: {@link SvcPicture#FRAME}, {@link SvcPicture#TOP} or
     * {@link SvcPicture#BOTTOM}.
     */
    int structure = SvcPicture.FRAME;

    /**
     * The entry of the decoded picture being decoded: a new entry, or the entry of the first field
     * the current field completes.
     */
    SvcPicture current;

    /**
     * The entry of the reference base picture of the picture being decoded, or {@code null}.
     */
    SvcPicture currentBase;

    /**
     * The entry of decoded pictures whose first field the current field completes, or
     * {@code null}.
     */
    SvcPicture pairDecoded;

    /**
     * The entry of reference base pictures whose first field the reference base field of the
     * current picture completes, or {@code null}.
     */
    SvcPicture pairBase;

    /**
     * The entry of decoded pictures holding the last decoded picture when it is a first field,
     * or {@code null}.
     */
    SvcPicture lastField;

    /**
     * The entry of reference base pictures holding the last reference base picture when it is a
     * first field, or {@code null}.
     */
    SvcPicture lastBaseField;

    /**
     * The working list of the reference picture list construction.
     */
    private final List<SvcPicture> candidates = new ArrayList<>();

    /**
     * The second working list of the reference picture list construction.
     */
    private final List<SvcPicture> secondCandidates = new ArrayList<>();

    /**
     * The working list of the long-term entries of a field list construction.
     */
    private final List<SvcPicture> longCandidates = new ArrayList<>();

    /**
     * The working pictures of the reference picture list modification.
     */
    private SvcPicture[] work = new SvcPicture[33];

    /**
     * The working structures of the reference picture list modification.
     */
    private int[] workStructures = new int[33];

    /**
     * The working pictures of a field list initialization, before truncation.
     */
    private SvcPicture[] initial = new SvcPicture[64];

    /**
     * The working structures of a field list initialization, before truncation.
     */
    private int[] initialStructures = new int[64];

    /**
     * The initial list 0 of a B field slice, before truncation, kept while its list 1 is derived.
     */
    private SvcPicture[] initialList0 = new SvcPicture[64];

    /**
     * The structures of {@link #initialList0}.
     */
    private int[] initialStructures0 = new int[64];

    /**
     * Whether the initial lists being built left out an entry standing for a lost picture.
     */
    private boolean leftOutLoss;

    /**
     * Supplies and recycles the entries.
     */
    interface Entries {
        /**
         * Returns a cleared entry.
         *
         * @return the entry
         */
        SvcPicture obtain();

        /**
         * Takes back an entry that left the buffer.
         *
         * @param picture the entry
         */
        void recycle(SvcPicture picture);
    }

    /**
     * Constructs an empty buffer.
     */
    SvcDpb() {
    }

    /**
     * Forgets every entry and the decoding state.
     *
     * @param pool the receiver of the entries
     */
    void reset(Entries pool) {
        for (var picture : entries) {
            pool.recycle(picture);
        }
        entries.clear();
        started = false;
        maxLongTermFrameIdx = -1;
        prevMmco5 = false;
        lastField = null;
        lastBaseField = null;
        pairDecoded = null;
        pairBase = null;
    }

    /**
     * Prepares the decoding of the current picture of the dependency: pairs a field with the
     * first field of its frame, fills the gaps in {@code frame_num} (F.8.2.5) and derives the
     * picture order counts (F.8.2.1).
     *
     * @param slice the first slice of the representation with {@code quality_id} 0
     * @param pool the source of entries for the frames lost to a gap
     * @param lossReported whether a loss of stream data was reported since the previous access
     * unit, so that a gap may stand for lost pictures even where the sequence allows gaps
     */
    void begin(SvcSlice slice, Entries pool, boolean lossReported) {
        var header = slice.header;
        var sps = header.sps;
        var maxFrameNum = 1 << sps.log2MaxFrameNum;
        structure = !header.fieldPic ? SvcPicture.FRAME : header.bottomField ? SvcPicture.BOTTOM : SvcPicture.TOP;
        var reference = slice.nalRefIdc > 0;
        pairDecoded = pairs(lastField, slice, reference) ? lastField : null;
        pairBase = reference && slice.storeRefBasePic && pairs(lastBaseField, slice, true) ? lastBaseField : null;
        if (slice.idr) {
            started = true;
        } else if (!started) {
            prevRefFrameNum = header.frameNum;
            prevFrameNum = header.frameNum;
            started = true;
        } else if (header.frameNum != prevRefFrameNum && header.frameNum != (prevRefFrameNum + 1) % maxFrameNum) {
            var lost = !sps.gapsInFrameNumAllowed || lossReported;
            if (lost) {
                markLongTermStale();
            }
            fillGap(header.frameNum, sps, pool, lost);
        }
        computePoc(slice, sps);
    }

    /**
     * Marks the long-term reference pictures of the dependency as damaged for prediction: a lost
     * picture may have given their {@code LongTermFrameIdx} to another picture, so that a later
     * reference to that index names another picture than the encoder's.
     */
    void markLongTermStale() {
        for (var picture : entries) {
            if (picture.anyLongTerm()) {
                picture.damaged = true;
            }
        }
    }

    /**
     * Returns whether a field picture completes the frame of an entry holding a first field
     * (ITU-T H.264 F.8.2, complementary field pairs).
     *
     * @param first the entry holding the last decoded first field, or {@code null}
     * @param slice the first slice of the current picture
     * @param reference whether the current picture is a reference picture
     * @return {@code true} when the current field is the second field of that frame
     */
    private boolean pairs(SvcPicture first, SvcSlice slice, boolean reference) {
        return first != null && structure != SvcPicture.FRAME && !slice.idr
                && first.frameNum == slice.header.frameNum && first.fields == SvcPicture.FRAME - structure
                && first.firstFieldReference == reference && !hasMmco5(slice.header);
    }

    /**
     * Returns whether a slice header holds a memory management control operation 5.
     *
     * @param header the slice header
     * @return {@code true} when one of its operations is 5
     */
    static boolean hasMmco5(H264SliceHeader header) {
        if (!header.adaptiveRefPicMarking) {
            return false;
        }
        for (var i = 0; i < header.mmcoCount; i++) {
            if (header.mmcoOp[i] == 5) {
                return true;
            }
        }
        return false;
    }

    /**
     * Inserts the frames lost to a gap in {@code frame_num} as non-existing reference frames
     * (ITU-T H.264 8.2.5.2 with F.8.2.5).
     *
     * @param frameNum the {@code frame_num} of the current picture
     * @param sps the sequence parameter set
     * @param pool the source of the entries
     * @param lostStandIn whether the frames of the gap may be lost pictures the encoder referred to
     */
    private void fillGap(int frameNum, H264Sps sps, Entries pool, boolean lostStandIn) {
        var maxFrameNum = 1 << sps.log2MaxFrameNum;
        var unused = (prevRefFrameNum + 1) % maxFrameNum;
        var guard = 0;
        while (unused != frameNum && guard++ < maxFrameNum) {
            number(unused, maxFrameNum);
            slidingWindow(sps, false, SvcPicture.FRAME, null, maxFrameNum);
            var picture = pool.obtain();
            picture.nonExisting = true;
            picture.lostStandIn = lostStandIn;
            picture.damaged = true;
            picture.frameNum = unused;
            picture.fields = SvcPicture.FRAME;
            picture.codedFrame = true;
            picture.mark(SvcPicture.FRAME, SvcPicture.SHORT_TERM);
            picture.topPoc = 0;
            picture.bottomPoc = 0;
            entries.add(picture);
            prevRefFrameNum = unused;
            prevFrameNum = unused;
            unused = (unused + 1) % maxFrameNum;
        }
        lastField = null;
        lastBaseField = null;
        pairDecoded = null;
        pairBase = null;
        removeUnused(pool);
    }

    /**
     * Computes the picture order counts of the current picture (ITU-T H.264 8.2.1).
     *
     * @param slice the first slice of the representation with {@code quality_id} 0
     * @param sps the sequence parameter set
     */
    private void computePoc(SvcSlice slice, H264Sps sps) {
        var header = slice.header;
        var maxFrameNum = 1 << sps.log2MaxFrameNum;
        var field = structure != SvcPicture.FRAME;
        var bottom = structure == SvcPicture.BOTTOM;
        int top = 0;
        int bot = 0;
        switch (sps.pocType) {
            case 0 -> {
                var prevMsb = prevPocMsb;
                var prevLsb = prevPocLsb;
                if (slice.idr) {
                    prevMsb = 0;
                    prevLsb = 0;
                }
                var maxLsb = 1 << sps.log2MaxPocLsb;
                var lsb = header.pocLsb;
                int msb;
                if (lsb < prevLsb && prevLsb - lsb >= maxLsb / 2) {
                    msb = prevMsb + maxLsb;
                } else if (lsb > prevLsb && lsb - prevLsb > maxLsb / 2) {
                    msb = prevMsb - maxLsb;
                } else {
                    msb = prevMsb;
                }
                pocMsb = msb;
                if (!bottom) {
                    top = msb + lsb;
                    bot = field ? 0 : top + header.deltaPocBottom;
                } else {
                    bot = msb + lsb;
                }
            }
            case 1 -> {
                var offset = frameNumOffset(slice, maxFrameNum);
                frameNumOffset = offset;
                var cycle = sps.offsetForRefFrame.length;
                var absFrameNum = cycle != 0 ? offset + header.frameNum : 0;
                if (slice.nalRefIdc == 0 && absFrameNum > 0) {
                    absFrameNum--;
                }
                var expected = 0;
                if (absFrameNum > 0) {
                    var cycleCount = (absFrameNum - 1) / cycle;
                    var inCycle = (absFrameNum - 1) % cycle;
                    var delta = 0;
                    for (var value : sps.offsetForRefFrame) {
                        delta += value;
                    }
                    expected = cycleCount * delta;
                    for (var i = 0; i <= inCycle; i++) {
                        expected += sps.offsetForRefFrame[i];
                    }
                }
                if (slice.nalRefIdc == 0) {
                    expected += sps.offsetForNonRefPic;
                }
                if (!field) {
                    top = expected + header.deltaPoc0;
                    bot = top + sps.offsetForTopToBottomField + header.deltaPoc1;
                } else if (!bottom) {
                    top = expected + header.deltaPoc0;
                } else {
                    bot = expected + sps.offsetForTopToBottomField + header.deltaPoc0;
                }
            }
            default -> {
                var offset = frameNumOffset(slice, maxFrameNum);
                frameNumOffset = offset;
                int temp;
                if (slice.idr) {
                    temp = 0;
                } else if (slice.nalRefIdc == 0) {
                    temp = 2 * (offset + header.frameNum) - 1;
                } else {
                    temp = 2 * (offset + header.frameNum);
                }
                top = temp;
                bot = temp;
            }
        }
        topPoc = top;
        bottomPoc = bot;
        poc = switch (structure) {
            case SvcPicture.TOP -> top;
            case SvcPicture.BOTTOM -> bot;
            default -> Math.min(top, bot);
        };
    }

    /**
     * Derives the {@code FrameNumOffset} of the current picture (ITU-T H.264 equations 8-6 and
     * 8-11).
     *
     * @param slice the first slice of the picture
     * @param maxFrameNum the {@code MaxFrameNum}
     * @return the offset
     */
    private int frameNumOffset(SvcSlice slice, int maxFrameNum) {
        if (slice.idr) {
            return 0;
        }
        var previous = prevMmco5 ? 0 : prevFrameNumOffset;
        if (prevFrameNum > slice.header.frameNum) {
            return previous + maxFrameNum;
        }
        return previous;
    }

    /**
     * Assigns {@code FrameNumWrap} to the entries with a short-term field (ITU-T H.264 8.2.4.1).
     *
     * @param frameNum the {@code frame_num} of the current picture
     * @param maxFrameNum the {@code MaxFrameNum}
     */
    void number(int frameNum, int maxFrameNum) {
        for (var picture : entries) {
            if (picture.anyShortTerm()) {
                picture.frameNumWrap = picture.frameNum > frameNum ? picture.frameNum - maxFrameNum
                        : picture.frameNum;
            }
        }
    }

    /**
     * Returns the {@code PicNum} of a short-term field for the current field (ITU-T H.264 equations
     * 8-30 and 8-31), or of a short-term frame for the current frame.
     *
     * @param picture the entry
     * @param parity the field, or {@link SvcPicture#FRAME}
     * @return the picture number
     */
    private int picNum(SvcPicture picture, int parity) {
        if (structure == SvcPicture.FRAME) {
            return picture.frameNumWrap;
        }
        return 2 * picture.frameNumWrap + (parity == structure ? 1 : 0);
    }

    /**
     * Returns the {@code LongTermPicNum} of a long-term field for the current field, or of a
     * long-term frame for the current frame.
     *
     * @param picture the entry
     * @param parity the field, or {@link SvcPicture#FRAME}
     * @return the long-term picture number
     */
    private int longTermPicNum(SvcPicture picture, int parity) {
        if (structure == SvcPicture.FRAME) {
            return picture.longTermFrameIdx;
        }
        return 2 * picture.longTermFrameIdx + (parity == structure ? 1 : 0);
    }

    /**
     * Returns whether an entry takes part in the reference picture list construction (ITU-T H.264
     * F.8.2.2).
     *
     * @param picture the entry
     * @param useRefBasePic the {@code use_ref_base_pic_flag} of the slice
     * @return {@code true} when the entry is present for the construction
     */
    private boolean listed(SvcPicture picture, boolean useRefBasePic) {
        if (!picture.isReference()) {
            return false;
        }
        if (!useRefBasePic) {
            return !picture.refBase;
        }
        if (picture.refBase) {
            return true;
        }
        for (var other : entries) {
            if (other == picture || !other.refBase) {
                continue;
            }
            if (picture.anyShortTerm() && other.anyShortTerm() && other.frameNum == picture.frameNum) {
                return false;
            }
            if (picture.anyLongTerm() && other.anyLongTerm()
                    && other.longTermFrameIdx == picture.longTermFrameIdx) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds the reference picture lists of a slice (ITU-T H.264 F.8.2.3), into
     * {@link SvcSlice#refLists} and {@link SvcSlice#refStructures}.
     *
     * @param slice the slice, with {@code quality_id} 0
     */
    void buildLists(SvcSlice slice) {
        var header = slice.header;
        var sps = header.sps;
        var maxFrameNum = 1 << sps.log2MaxFrameNum;
        number(header.frameNum, maxFrameNum);
        var b = header.sliceType % 5 == H264SliceHeader.B;
        var count0 = header.numRefIdxActive[0];
        var count1 = b ? header.numRefIdxActive[1] : 0;
        slice.allocateLists(count0, count1);
        leftOutLoss = false;
        var use = slice.useRefBasePic;
        var excludeNonExisting = sps.pocType == 0;
        candidates.clear();
        secondCandidates.clear();
        longCandidates.clear();
        if (structure == SvcPicture.FRAME) {
            if (!b) {
                frameShortTerms(use, candidates, Integer.MIN_VALUE, true, true, false);
                frameLongTerms(use, candidates);
                fill(slice, 0, candidates);
            } else {
                frameShortTerms(use, candidates, poc, true, false, excludeNonExisting);
                frameShortTerms(use, candidates, poc, false, false, excludeNonExisting);
                frameLongTerms(use, candidates);
                frameShortTerms(use, secondCandidates, poc, false, false, excludeNonExisting);
                frameShortTerms(use, secondCandidates, poc, true, false, excludeNonExisting);
                frameLongTerms(use, secondCandidates);
                if (secondCandidates.size() > 1 && secondCandidates.equals(candidates)) {
                    var first = secondCandidates.get(0);
                    secondCandidates.set(0, secondCandidates.get(1));
                    secondCandidates.set(1, first);
                }
                fill(slice, 0, candidates);
                fill(slice, 1, secondCandidates);
            }
        } else {
            fieldLongTerms(use, longCandidates);
            if (!b) {
                fieldShortTerms(use, candidates, Integer.MIN_VALUE, true, true, false);
                fillFields(slice, 0, candidates, longCandidates);
            } else {
                fieldShortTerms(use, candidates, poc, true, false, excludeNonExisting);
                fieldShortTerms(use, candidates, poc, false, false, excludeNonExisting);
                fieldShortTerms(use, secondCandidates, poc, false, false, excludeNonExisting);
                fieldShortTerms(use, secondCandidates, poc, true, false, excludeNonExisting);
                var size0 = fillFields(slice, 0, candidates, longCandidates);
                if (initialList0.length < size0) {
                    initialList0 = new SvcPicture[initial.length];
                    initialStructures0 = new int[initial.length];
                }
                System.arraycopy(initial, 0, initialList0, 0, size0);
                System.arraycopy(initialStructures, 0, initialStructures0, 0, size0);
                var size1 = initialFields(secondCandidates, longCandidates);
                if (size1 > 1 && size0 == size1 && sameInitialLists(size0)) {
                    swapFirstEntries();
                }
                copyFields(slice, 1, size1);
            }
        }
        slice.lostEntryLeftOut = leftOutLoss;
        for (var list = 0; list < (b ? 2 : 1); list++) {
            if (header.refPicListModification[list]) {
                modify(slice, list, maxFrameNum);
            }
        }
    }

    /**
     * Appends the short-term reference frames and complementary field pairs to a list in an
     * initial list order (ITU-T H.264 8.2.4.2.1 and 8.2.4.2.3).
     *
     * @param useRefBasePic the {@code use_ref_base_pic_flag} of the slice
     * @param target the list
     * @param currentPoc the picture order count of the current picture
     * @param before for B slices, whether the entries preceding the current picture are wanted
     * (in descending order) rather than the following ones (in ascending order); for P slices
     * ignored
     * @param byPicNum whether the order is by descending {@code PicNum} (P slices)
     * @param excludeNonExisting whether entries standing for frames lost to a gap are left out
     */
    private void frameShortTerms(boolean useRefBasePic, List<SvcPicture> target, int currentPoc, boolean before,
                                 boolean byPicNum, boolean excludeNonExisting) {
        var start = target.size();
        for (var picture : entries) {
            if (!picture.marked(SvcPicture.FRAME, SvcPicture.SHORT_TERM) || picture.fields != SvcPicture.FRAME
                    || !listed(picture, useRefBasePic)) {
                continue;
            }
            if (excludeNonExisting && picture.nonExisting) {
                leftOutLoss |= picture.lostStandIn;
                continue;
            }
            if (!byPicNum && (before ? picture.poc() >= currentPoc : picture.poc() <= currentPoc)) {
                continue;
            }
            target.add(picture);
        }
        sort(target.subList(start, target.size()), byPicNum, before);
    }

    /**
     * Appends the long-term reference frames and complementary field pairs to a list in
     * ascending {@code LongTermPicNum}.
     *
     * @param useRefBasePic the {@code use_ref_base_pic_flag} of the slice
     * @param target the list
     */
    private void frameLongTerms(boolean useRefBasePic, List<SvcPicture> target) {
        var start = target.size();
        for (var picture : entries) {
            if (picture.marked(SvcPicture.FRAME, SvcPicture.LONG_TERM) && picture.fields == SvcPicture.FRAME
                    && listed(picture, useRefBasePic)) {
                target.add(picture);
            }
        }
        target.subList(start, target.size()).sort((a, b) -> Integer.compare(a.longTermFrameIdx, b.longTermFrameIdx));
    }

    /**
     * Appends the entries with a short-term field to a list of reference frames in an initial
     * order (ITU-T H.264 8.2.4.2.2 and 8.2.4.2.4).
     *
     * @param useRefBasePic the {@code use_ref_base_pic_flag} of the slice
     * @param target the list
     * @param currentPoc the picture order count of the current field
     * @param before for B slices, whether the entries preceding the current field, or at its
     * order count, are wanted (in descending order) rather than the following ones (in ascending
     * order); for P slices ignored
     * @param byFrameNumWrap whether the order is by descending {@code FrameNumWrap} (P slices)
     * @param excludeNonExisting whether entries standing for frames lost to a gap are left out
     */
    private void fieldShortTerms(boolean useRefBasePic, List<SvcPicture> target, int currentPoc, boolean before,
                                 boolean byFrameNumWrap, boolean excludeNonExisting) {
        var start = target.size();
        for (var picture : entries) {
            if (!picture.anyShortTerm() || !listed(picture, useRefBasePic)) {
                continue;
            }
            if (excludeNonExisting && picture.nonExisting) {
                leftOutLoss |= picture.lostStandIn;
                continue;
            }
            if (!byFrameNumWrap && (before ? picture.poc() > currentPoc : picture.poc() <= currentPoc)) {
                continue;
            }
            target.add(picture);
        }
        sort(target.subList(start, target.size()), byFrameNumWrap, before);
    }

    /**
     * Appends the entries with a long-term field to a list in ascending {@code LongTermFrameIdx}.
     *
     * @param useRefBasePic the {@code use_ref_base_pic_flag} of the slice
     * @param target the list
     */
    private void fieldLongTerms(boolean useRefBasePic, List<SvcPicture> target) {
        for (var picture : entries) {
            if (picture.anyLongTerm() && listed(picture, useRefBasePic)) {
                target.add(picture);
            }
        }
        target.sort((a, b) -> Integer.compare(a.longTermFrameIdx, b.longTermFrameIdx));
    }

    /**
     * Sorts a section of an initial list.
     *
     * @param section the entries to sort, a view of the list
     * @param byNumber whether the order is by descending {@code FrameNumWrap}
     * @param before whether the order is by descending picture order count rather than ascending
     */
    private static void sort(List<SvcPicture> section, boolean byNumber, boolean before) {
        if (byNumber) {
            section.sort((a, b) -> Integer.compare(b.frameNumWrap, a.frameNumWrap));
        } else if (before) {
            section.sort((a, b) -> Integer.compare(b.poc(), a.poc()));
        } else {
            section.sort((a, b) -> Integer.compare(a.poc(), b.poc()));
        }
    }

    /**
     * Copies an initial list of frames into a reference picture list, truncated or padded with
     * no reference.
     *
     * @param slice the slice whose list receives the entries
     * @param list the list index
     * @param initialList the initial list
     */
    private static void fill(SvcSlice slice, int list, List<SvcPicture> initialList) {
        var pictures = slice.refLists[list];
        var structures = slice.refStructures[list];
        for (var i = 0; i < pictures.length; i++) {
            pictures[i] = i < initialList.size() ? initialList.get(i) : null;
            structures[i] = SvcPicture.FRAME;
        }
    }

    /**
     * Derives a reference picture list of fields from ordered lists of reference frames (ITU-T
     * H.264 8.2.4.2.5).
     *
     * @param slice the slice whose list receives the entries
     * @param list the list index
     * @param shortTerms the short-term reference frames in order
     * @param longTerms the long-term reference frames in order
     * @return the number of fields of the initial list before truncation
     */
    private int fillFields(SvcSlice slice, int list, List<SvcPicture> shortTerms, List<SvcPicture> longTerms) {
        var count = initialFields(shortTerms, longTerms);
        copyFields(slice, list, count);
        return count;
    }

    /**
     * Derives an initial reference picture list of fields from ordered lists of reference frames
     * into {@link #initial} (ITU-T H.264 8.2.4.2.5).
     *
     * @param shortTerms the short-term reference frames in order
     * @param longTerms the long-term reference frames in order
     * @return the number of fields of the initial list
     */
    private int initialFields(List<SvcPicture> shortTerms, List<SvcPicture> longTerms) {
        var count = alternate(shortTerms, SvcPicture.SHORT_TERM, 0);
        return alternate(longTerms, SvcPicture.LONG_TERM, count);
    }

    /**
     * Copies the initial list of fields in {@link #initial} into a reference picture list,
     * truncated or padded with no reference.
     *
     * @param slice the slice whose list receives the fields
     * @param list the list index
     * @param count the number of fields of the initial list
     */
    private void copyFields(SvcSlice slice, int list, int count) {
        var pictures = slice.refLists[list];
        var structures = slice.refStructures[list];
        for (var i = 0; i < pictures.length; i++) {
            pictures[i] = i < count ? initial[i] : null;
            structures[i] = i < count ? initialStructures[i] : SvcPicture.FRAME;
        }
    }

    /**
     * Appends the fields of ordered reference frames to {@link #initial}, alternating between
     * the parities starting with that of the current field.
     *
     * @param frames the ordered frames
     * @param marking the marking the fields must hold
     * @param start the number of fields already in {@link #initial}
     * @return the number of fields in {@link #initial}
     */
    private int alternate(List<SvcPicture> frames, int marking, int start) {
        var needed = start + 2 * frames.size();
        if (initial.length < needed) {
            initial = java.util.Arrays.copyOf(initial, needed);
            initialStructures = java.util.Arrays.copyOf(initialStructures, needed);
        }
        var same = structure;
        var opposite = SvcPicture.FRAME - structure;
        var nextSame = 0;
        var nextOpposite = 0;
        var count = start;
        var parity = same;
        while (true) {
            var next = parity == same ? nextSame : nextOpposite;
            while (next < frames.size() && !hasField(frames.get(next), parity, marking)) {
                next++;
            }
            if (next < frames.size()) {
                initial[count] = frames.get(next);
                initialStructures[count++] = parity;
                next++;
                if (parity == same) {
                    nextSame = next;
                } else {
                    nextOpposite = next;
                }
                parity = parity == same ? opposite : same;
                continue;
            }
            if (parity == same) {
                nextSame = next;
            } else {
                nextOpposite = next;
            }
            var other = parity == same ? opposite : same;
            var otherNext = other == same ? nextSame : nextOpposite;
            while (otherNext < frames.size()) {
                if (hasField(frames.get(otherNext), other, marking)) {
                    initial[count] = frames.get(otherNext);
                    initialStructures[count++] = other;
                }
                otherNext++;
            }
            return count;
        }
    }

    /**
     * Returns whether an entry holds a decoded field of a parity with a marking.
     *
     * @param picture the entry
     * @param parity {@link SvcPicture#TOP} or {@link SvcPicture#BOTTOM}
     * @param marking the marking wanted, {@link SvcPicture#SHORT_TERM} or {@link SvcPicture#LONG_TERM}
     * @return {@code true} when that field is decoded and marked so
     */
    private static boolean hasField(SvcPicture picture, int parity, int marking) {
        return (picture.fields & parity) != 0 && picture.marking(parity) == marking;
    }

    /**
     * Returns whether the initial list 1 of a B field slice in {@link #initial} is identical to
     * its initial list 0 in {@link #initialList0}, both before truncation.
     *
     * @param size the number of entries of each initial list
     * @return {@code true} when every entry matches
     */
    private boolean sameInitialLists(int size) {
        for (var i = 0; i < size; i++) {
            if (initialList0[i] != initial[i] || initialStructures0[i] != initialStructures[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * Switches the first two entries of the initial list in {@link #initial}.
     */
    private void swapFirstEntries() {
        var picture = initial[0];
        initial[0] = initial[1];
        initial[1] = picture;
        var value = initialStructures[0];
        initialStructures[0] = initialStructures[1];
        initialStructures[1] = value;
    }

    /**
     * Applies the reference picture list modification of a slice to one of its lists (ITU-T
     * H.264 8.2.4.3).
     *
     * @param slice the slice whose list is modified
     * @param list the list index
     * @param maxFrameNum the {@code MaxFrameNum}
     */
    private void modify(SvcSlice slice, int list, int maxFrameNum) {
        var header = slice.header;
        var entriesOfList = slice.refLists[list];
        var structuresOfList = slice.refStructures[list];
        var count = entriesOfList.length;
        if (work.length < count + 1) {
            work = new SvcPicture[count + 1];
            workStructures = new int[count + 1];
        }
        System.arraycopy(entriesOfList, 0, work, 0, count);
        System.arraycopy(structuresOfList, 0, workStructures, 0, count);
        work[count] = null;
        var field = structure != SvcPicture.FRAME;
        var maxPicNum = field ? 2 * maxFrameNum : maxFrameNum;
        var currPicNum = field ? 2 * header.frameNum + 1 : header.frameNum;
        var predicted = currPicNum;
        var refIdx = 0;
        for (var i = 0; i < header.modificationCount[list] && refIdx < count; i++) {
            var idc = header.modificationIdc[list][i];
            var value = header.modificationValue[list][i];
            SvcPicture chosen = null;
            var chosenStructure = SvcPicture.FRAME;
            if (idc == 0 || idc == 1) {
                int noWrap;
                if (idc == 0) {
                    noWrap = predicted - (value + 1);
                    if (noWrap < 0) {
                        noWrap += maxPicNum;
                    }
                } else {
                    noWrap = predicted + (value + 1);
                    if (noWrap >= maxPicNum) {
                        noWrap -= maxPicNum;
                    }
                }
                predicted = noWrap;
                var target = noWrap > currPicNum ? noWrap - maxPicNum : noWrap;
                search:
                for (var picture : entries) {
                    if (!listed(picture, slice.useRefBasePic)) {
                        continue;
                    }
                    if (!field) {
                        if (picture.fields == SvcPicture.FRAME && picture.marked(SvcPicture.FRAME, SvcPicture.SHORT_TERM)
                                && picture.frameNumWrap == target) {
                            chosen = picture;
                            break;
                        }
                        continue;
                    }
                    for (var parity = SvcPicture.TOP; parity <= SvcPicture.BOTTOM; parity++) {
                        if (hasField(picture, parity, SvcPicture.SHORT_TERM) && picNum(picture, parity) == target) {
                            chosen = picture;
                            chosenStructure = parity;
                            break search;
                        }
                    }
                }
            } else if (idc == 2) {
                search:
                for (var picture : entries) {
                    if (!listed(picture, slice.useRefBasePic)) {
                        continue;
                    }
                    if (!field) {
                        if (picture.fields == SvcPicture.FRAME && picture.marked(SvcPicture.FRAME, SvcPicture.LONG_TERM)
                                && picture.longTermFrameIdx == value) {
                            chosen = picture;
                            break;
                        }
                        continue;
                    }
                    for (var parity = SvcPicture.TOP; parity <= SvcPicture.BOTTOM; parity++) {
                        if (hasField(picture, parity, SvcPicture.LONG_TERM)
                                && longTermPicNum(picture, parity) == value) {
                            chosen = picture;
                            chosenStructure = parity;
                            break search;
                        }
                    }
                }
            } else {
                continue;
            }
            for (var c = count; c > refIdx; c--) {
                work[c] = work[c - 1];
                workStructures[c] = workStructures[c - 1];
            }
            work[refIdx] = chosen;
            workStructures[refIdx] = chosenStructure;
            refIdx++;
            var n = refIdx;
            for (var c = refIdx; c <= count; c++) {
                if (work[c] != chosen || workStructures[c] != chosenStructure) {
                    work[n] = work[c];
                    workStructures[n] = workStructures[c];
                    n++;
                }
            }
        }
        System.arraycopy(work, 0, entriesOfList, 0, count);
        System.arraycopy(workStructures, 0, structuresOfList, 0, count);
    }

    /**
     * Marks the reference pictures of the dependency after decoding its current picture (ITU-T
     * H.264 F.8.2.4.1). The entries of the current picture must be in {@link #entries}, with the
     * fields of the current picture not yet marked.
     *
     * @param slice the first slice of the representation with {@code quality_id} 0, which holds
     * the marking syntax
     * @param sps the sequence parameter set
     * @param picture the entry of the decoded picture
     * @param base the entry of the reference base picture, or {@code null}
     */
    void mark(SvcSlice slice, H264Sps sps, SvcPicture picture, SvcPicture base) {
        var header = slice.header;
        var maxFrameNum = 1 << sps.log2MaxFrameNum;
        var current = structure;
        if (slice.idr) {
            for (var entry : entries) {
                if (entry != picture && entry != base) {
                    entry.mark(SvcPicture.FRAME, SvcPicture.UNUSED);
                }
            }
            var marking = header.longTermReference ? SvcPicture.LONG_TERM : SvcPicture.SHORT_TERM;
            picture.mark(current, marking);
            picture.longTermFrameIdx = 0;
            maxLongTermFrameIdx = header.longTermReference ? 0 : -1;
            if (base != null) {
                base.mark(current, marking);
                base.longTermFrameIdx = 0;
            }
            return;
        }
        number(header.frameNum, maxFrameNum);
        var field = current != SvcPicture.FRAME;
        var currPicNum = field ? 2 * header.frameNum + 1 : header.frameNum;
        if (slice.adaptiveRefBasePicMarking) {
            for (var i = 0; i < slice.baseMmcoCount; i++) {
                var arg = slice.baseMmcoArg[i];
                if (slice.baseMmcoOp[i] == 1) {
                    unmarkShortTerm(currPicNum - (arg + 1), true);
                } else {
                    unmarkLongTerm(arg, true);
                }
            }
        }
        var currentLong = false;
        var baseLong = false;
        if (header.adaptiveRefPicMarking) {
            for (var i = 0; i < header.mmcoCount; i++) {
                var op = header.mmcoOp[i];
                var arg1 = header.mmcoArg1[i];
                var arg2 = header.mmcoArg2[i];
                switch (op) {
                    case 1 -> unmarkShortTerm(currPicNum - (arg1 + 1), false);
                    case 2 -> unmarkLongTerm(arg1, false);
                    case 3 -> {
                        var picNumX = currPicNum - (arg1 + 1);
                        if (assignLongTerm(picNumX, arg2, false)) {
                            if (!assignLongTerm(picNumX, arg2, true)) {
                                releaseBaseIndex(arg2, picNumX, null);
                            }
                        } else {
                            releaseBaseIndex(arg2, picNumX, null);
                        }
                    }
                    case 4 -> {
                        maxLongTermFrameIdx = arg1 - 1;
                        for (var entry : entries) {
                            if (entry.anyLongTerm() && entry.longTermFrameIdx > maxLongTermFrameIdx) {
                                if (entry.topMarking == SvcPicture.LONG_TERM) {
                                    entry.topMarking = SvcPicture.UNUSED;
                                }
                                if (entry.bottomMarking == SvcPicture.LONG_TERM) {
                                    entry.bottomMarking = SvcPicture.UNUSED;
                                }
                            }
                        }
                    }
                    case 5 -> {
                        for (var entry : entries) {
                            if (entry != picture && entry != base) {
                                entry.mark(SvcPicture.FRAME, SvcPicture.UNUSED);
                            } else {
                                entry.mark(SvcPicture.FRAME - current, SvcPicture.UNUSED);
                            }
                        }
                        maxLongTermFrameIdx = -1;
                    }
                    case 6 -> {
                        releaseIndex(arg2, picture, false);
                        picture.mark(current, SvcPicture.LONG_TERM);
                        picture.longTermFrameIdx = arg2;
                        currentLong = true;
                        if (base != null) {
                            releaseIndex(arg2, base, true);
                            base.mark(current, SvcPicture.LONG_TERM);
                            base.longTermFrameIdx = arg2;
                            baseLong = true;
                        } else {
                            releaseBaseIndex(arg2, Integer.MIN_VALUE, picture);
                        }
                    }
                    default -> {
                    }
                }
            }
        } else {
            slidingWindow(sps, false, current, pairDecoded, maxFrameNum);
        }
        if (!currentLong) {
            picture.mark(current, SvcPicture.SHORT_TERM);
        }
        if (base != null && !baseLong) {
            if (!slice.adaptiveRefBasePicMarking) {
                number(header.frameNum, maxFrameNum);
                slidingWindow(sps, true, current, pairBase, maxFrameNum);
            }
            base.mark(current, SvcPicture.SHORT_TERM);
        }
    }

    /**
     * Marks the short-term reference with a picture number as unused (ITU-T H.264 8.2.5.4.1),
     * considering only entries of one kind.
     *
     * @param picNumX the picture number
     * @param refBase whether reference base pictures are considered rather than decoded pictures
     */
    private void unmarkShortTerm(int picNumX, boolean refBase) {
        for (var entry : entries) {
            if (entry.refBase != refBase) {
                continue;
            }
            if (structure == SvcPicture.FRAME) {
                if (entry.fields == SvcPicture.FRAME && entry.marked(SvcPicture.FRAME, SvcPicture.SHORT_TERM)
                        && entry.frameNumWrap == picNumX) {
                    entry.mark(SvcPicture.FRAME, SvcPicture.UNUSED);
                }
                continue;
            }
            for (var parity = SvcPicture.TOP; parity <= SvcPicture.BOTTOM; parity++) {
                if (entry.marking(parity) == SvcPicture.SHORT_TERM && picNum(entry, parity) == picNumX) {
                    entry.mark(parity, SvcPicture.UNUSED);
                }
            }
        }
    }

    /**
     * Marks the long-term reference with a long-term picture number as unused (ITU-T H.264
     * 8.2.5.4.2), considering only entries of one kind.
     *
     * @param longTermPicNum the long-term picture number
     * @param refBase whether reference base pictures are considered rather than decoded pictures
     */
    private void unmarkLongTerm(int longTermPicNum, boolean refBase) {
        for (var entry : entries) {
            if (entry.refBase != refBase) {
                continue;
            }
            if (structure == SvcPicture.FRAME) {
                if (entry.fields == SvcPicture.FRAME && entry.marked(SvcPicture.FRAME, SvcPicture.LONG_TERM)
                        && entry.longTermFrameIdx == longTermPicNum) {
                    entry.mark(SvcPicture.FRAME, SvcPicture.UNUSED);
                }
                continue;
            }
            for (var parity = SvcPicture.TOP; parity <= SvcPicture.BOTTOM; parity++) {
                if (entry.marking(parity) == SvcPicture.LONG_TERM && longTermPicNum(entry, parity) == longTermPicNum) {
                    entry.mark(parity, SvcPicture.UNUSED);
                }
            }
        }
    }

    /**
     * Assigns a long-term frame index to the short-term reference with a picture number (ITU-T
     * H.264 8.2.5.4.3), considering only entries of one kind.
     *
     * @param picNumX the picture number
     * @param index the long-term frame index
     * @param refBase whether reference base pictures are considered rather than decoded pictures
     * @return whether a reference with that picture number exists
     */
    private boolean assignLongTerm(int picNumX, int index, boolean refBase) {
        SvcPicture target = null;
        var targetStructure = SvcPicture.FRAME;
        for (var entry : entries) {
            if (entry.refBase != refBase) {
                continue;
            }
            if (structure == SvcPicture.FRAME) {
                if (entry.fields == SvcPicture.FRAME && entry.marked(SvcPicture.FRAME, SvcPicture.SHORT_TERM)
                        && entry.frameNumWrap == picNumX) {
                    target = entry;
                    break;
                }
                continue;
            }
            for (var parity = SvcPicture.TOP; parity <= SvcPicture.BOTTOM && target == null; parity++) {
                if (entry.marking(parity) == SvcPicture.SHORT_TERM && picNum(entry, parity) == picNumX) {
                    target = entry;
                    targetStructure = parity;
                }
            }
            if (target != null) {
                break;
            }
        }
        if (target == null) {
            return false;
        }
        for (var entry : entries) {
            if (entry.refBase != refBase || !entry.anyLongTerm() || entry.longTermFrameIdx != index) {
                continue;
            }
            if (entry == target) {
                continue;
            }
            entry.mark(SvcPicture.TOP, entry.topMarking == SvcPicture.LONG_TERM ? SvcPicture.UNUSED : entry.topMarking);
            entry.mark(SvcPicture.BOTTOM,
                    entry.bottomMarking == SvcPicture.LONG_TERM ? SvcPicture.UNUSED : entry.bottomMarking);
        }
        if (target.anyLongTerm() && target.longTermFrameIdx != index) {
            target.mark(SvcPicture.FRAME - targetStructure, SvcPicture.UNUSED);
        }
        target.mark(targetStructure, SvcPicture.LONG_TERM);
        target.longTermFrameIdx = index;
        return true;
    }

    /**
     * Frees a long-term frame index of the reference base pictures that a memory management
     * control operation 3 or 6 assigned to decoded pictures without a matching reference base
     * picture (ITU-T H.264 F.8.2.4.4).
     *
     * @param index the long-term frame index
     * @param picNumX the picture number of the picture operation 3 named, or
     * {@link Integer#MIN_VALUE} for operation 6
     * @param current the entry of the current decoded picture for operation 6, or {@code null}
     */
    private void releaseBaseIndex(int index, int picNumX, SvcPicture current) {
        for (var entry : entries) {
            if (!entry.refBase || !entry.anyLongTerm() || entry.longTermFrameIdx != index) {
                continue;
            }
            if (entry.marked(SvcPicture.FRAME, SvcPicture.LONG_TERM) && entry.fields == SvcPicture.FRAME) {
                entry.mark(SvcPicture.FRAME, SvcPicture.UNUSED);
                continue;
            }
            var partner = false;
            for (var other : entries) {
                if (other.refBase || other.frameNum != entry.frameNum) {
                    continue;
                }
                if (current != null ? other == current : containsPicNum(other, picNumX)) {
                    partner = true;
                }
            }
            if (!partner) {
                if (entry.topMarking == SvcPicture.LONG_TERM) {
                    entry.topMarking = SvcPicture.UNUSED;
                }
                if (entry.bottomMarking == SvcPicture.LONG_TERM) {
                    entry.bottomMarking = SvcPicture.UNUSED;
                }
            }
        }
    }

    /**
     * Returns whether an entry holds the picture with a picture number.
     *
     * @param entry the decoded picture buffer entry
     * @param picNumX the picture number
     * @return {@code true} when one of its fields, or the frame, has that number
     */
    private boolean containsPicNum(SvcPicture entry, int picNumX) {
        if (structure == SvcPicture.FRAME) {
            return entry.frameNumWrap == picNumX;
        }
        return picNum(entry, SvcPicture.TOP) == picNumX || picNum(entry, SvcPicture.BOTTOM) == picNumX;
    }

    /**
     * Frees a long-term frame index before it is assigned to the current picture (ITU-T H.264
     * 8.2.5.4.6), considering only entries of one kind.
     *
     * @param index the long-term frame index
     * @param current the entry of the current picture
     * @param refBase whether reference base pictures are considered rather than decoded pictures
     */
    private void releaseIndex(int index, SvcPicture current, boolean refBase) {
        for (var entry : entries) {
            if (entry.refBase != refBase || !entry.anyLongTerm() || entry.longTermFrameIdx != index
                    || entry == current) {
                continue;
            }
            if (entry.topMarking == SvcPicture.LONG_TERM) {
                entry.topMarking = SvcPicture.UNUSED;
            }
            if (entry.bottomMarking == SvcPicture.LONG_TERM) {
                entry.bottomMarking = SvcPicture.UNUSED;
            }
        }
        if (current.anyLongTerm() && current.longTermFrameIdx != index) {
            current.mark(SvcPicture.FRAME, SvcPicture.UNUSED);
        }
    }

    /**
     * Applies the sliding window marking (ITU-T H.264 F.8.2.4.2).
     *
     * @param sps the sequence parameter set
     * @param refBasePicFlag whether the window runs for a reference base picture
     * @param current the structure of the current picture
     * @param pair the entry the current field completes, or {@code null}
     * @param maxFrameNum the {@code MaxFrameNum}
     */
    private void slidingWindow(H264Sps sps, boolean refBasePicFlag, int current, SvcPicture pair, int maxFrameNum) {
        if (current != SvcPicture.FRAME && pair != null
                && pair.marking(SvcPicture.FRAME - current) == SvcPicture.SHORT_TERM) {
            return;
        }
        var shortCount = 0;
        var longCount = 0;
        for (var entry : entries) {
            if (entry.anyShortTerm()) {
                shortCount++;
            }
            if (entry.anyLongTerm()) {
                longCount++;
            }
        }
        if (shortCount + longCount < Math.max(sps.maxNumRefFrames, 1) || shortCount == 0) {
            return;
        }
        SvcPicture decoded = null;
        SvcPicture base = null;
        for (var entry : entries) {
            if (!entry.anyShortTerm()) {
                continue;
            }
            if (entry.refBase) {
                if (base == null || entry.frameNumWrap < base.frameNumWrap) {
                    base = entry;
                }
            } else if (decoded == null || entry.frameNumWrap < decoded.frameNumWrap) {
                decoded = entry;
            }
        }
        var decodedWrap = decoded != null ? decoded.frameNumWrap : maxFrameNum;
        var baseWrap = base != null ? base.frameNumWrap : maxFrameNum;
        var chosen = decodedWrap < baseWrap ? decoded : base;
        if (chosen != null) {
            chosen.mark(SvcPicture.FRAME, SvcPicture.UNUSED);
        }
    }

    /**
     * Updates the state the picture order count and {@code frame_num} derivations of the next
     * picture read (ITU-T H.264 8.2.1), and records the order counts of the current picture in its
     * entries.
     *
     * @param slice the first slice of the current picture
     * @param picture the entry of the decoded picture, or {@code null}
     * @param base the entry of the reference base picture, or {@code null}
     */
    void finishPicture(SvcSlice slice, SvcPicture picture, SvcPicture base) {
        var header = slice.header;
        var mmco5 = hasMmco5(header);
        if (mmco5) {
            var temp = poc;
            if (structure != SvcPicture.BOTTOM) {
                topPoc -= temp;
            }
            if (structure != SvcPicture.TOP) {
                bottomPoc -= temp;
            }
            poc = 0;
        }
        recordOrder(picture, mmco5);
        recordOrder(base, mmco5);
        if (slice.nalRefIdc > 0) {
            if (mmco5) {
                prevPocMsb = 0;
                prevPocLsb = structure == SvcPicture.BOTTOM ? 0 : topPoc;
            } else {
                prevPocMsb = pocMsb;
                prevPocLsb = header.pocLsb;
            }
            prevRefFrameNum = mmco5 ? 0 : header.frameNum;
        }
        prevMmco5 = mmco5;
        prevFrameNumOffset = mmco5 ? 0 : frameNumOffset;
        prevFrameNum = mmco5 ? 0 : header.frameNum;
        var second = structure != SvcPicture.FRAME && pairDecoded != null;
        lastField = structure != SvcPicture.FRAME && !second && picture != null && entries.contains(picture)
                ? picture : null;
        var secondBase = structure != SvcPicture.FRAME && pairBase != null;
        lastBaseField = structure != SvcPicture.FRAME && !secondBase && base != null && entries.contains(base)
                ? base : null;
    }

    /**
     * Records the order counts and the fields of the current picture in one of its entries.
     *
     * @param entry the entry, or {@code null}
     * @param mmco5 whether the current picture carries memory management control operation 5
     */
    private void recordOrder(SvcPicture entry, boolean mmco5) {
        if (entry == null) {
            return;
        }
        if ((structure & SvcPicture.TOP) != 0) {
            entry.topPoc = topPoc;
        }
        if ((structure & SvcPicture.BOTTOM) != 0) {
            entry.bottomPoc = bottomPoc;
        }
        entry.fields |= structure;
        if (mmco5) {
            entry.frameNum = 0;
        }
    }

    /**
     * Removes the entries that are neither references nor waiting for output, other than the
     * entries holding a first field that a second field may still complete.
     *
     * @param pool the receiver of the removed entries
     */
    void removeUnused(Entries pool) {
        for (var i = entries.size() - 1; i >= 0; i--) {
            var entry = entries.get(i);
            if (!entry.isReference() && !entry.outputNeeded && entry != lastField && entry != lastBaseField) {
                entries.remove(i);
                pool.recycle(entry);
            }
        }
    }
}
