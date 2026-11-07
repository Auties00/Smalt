/* Ports Shaka Packager v3.9.3 (packager/media/codecs/h264_parser.cc and h265_parser.cc, the parameter set and
 * slice header syntax read to find where the slice data starts). Copyright 2014 Google LLC; BSD 3-Clause License.
 * Modified for Smalt. */
package com.github.auties00.smalt.internal.cenc;

import com.github.auties00.smalt.internal.bits.MsbBitReader;
import com.github.auties00.smalt.internal.nal.NalUnits;

import java.util.Arrays;

/**
 * Finds the end of the slice header of H.264 (ITU-T H.264 clause 7.3.3) and HEVC (ITU-T H.265 clause 7.3.6.1) slice
 * NAL units, which Common Encryption keeps clear: the protected bytes of a slice start after its header.
 *
 * <p>The parser keeps the sequence and picture parameter sets it is given, from the codec configuration and from the
 * parameter set NAL units of the stream, and reads each slice header with the sets it names. A slice it cannot read
 * (one naming a parameter set it was not given, an HEVC slice of a layer above the base layer, or a header that runs
 * past the unit) reports no header size, and the caller leaves the whole unit clear. Instances are not thread-safe.
 */
public final class SliceHeaders {
    /**
     * The H.264 slice types, by {@code slice_type % 5}.
     */
    private static final int P = 0, B = 1, I = 2, SP = 3, SI = 4;

    /**
     * The HEVC slice types.
     */
    private static final int HEVC_B = 0, HEVC_P = 1;

    private final boolean hevc;
    private final H264Sps[] avcSps = new H264Sps[32];
    private final H264Pps[] avcPps = new H264Pps[256];
    private final HevcSps[] hevcSps = new HevcSps[16];
    private final HevcPps[] hevcPps = new HevcPps[64];
    private byte[] rbsp = new byte[512];
    private int[] removed = new int[512 / 3 + 1];
    private final MsbBitReader reader = new MsbBitReader(new byte[0]);

    /**
     * Constructs a parser for one stream.
     *
     * @param hevc {@code true} for HEVC, {@code false} for H.264
     */
    public SliceHeaders(boolean hevc) {
        this.hevc = hevc;
    }

    /**
     * Records a parameter set, replacing any earlier set with its identifier; a unit of another type, or a set that
     * does not parse, is ignored.
     *
     * @param data the array holding the unit
     * @param offset the index of its NAL unit header
     * @param length its size in bytes
     */
    public void parameterSet(byte[] data, int offset, int length) {
        if (length < (hevc ? 3 : 2)) {
            return;
        }
        var type = NalUnits.type(data[offset], hevc);
        try {
            if (hevc) {
                var layer = ((data[offset] & 1) << 5) | ((data[offset + 1] & 0xFF) >>> 3);
                if (layer != 0) {
                    return;
                }
                if (type == 33) {
                    var sps = parseHevcSps(rbsp(data, offset + 2, length - 2));
                    if (sps != null) {
                        hevcSps[sps.id] = sps;
                    }
                } else if (type == 34) {
                    var pps = parseHevcPps(rbsp(data, offset + 2, length - 2));
                    if (pps != null) {
                        hevcPps[pps.id] = pps;
                    }
                }
            } else if (type == 7) {
                var sps = parseAvcSps(rbsp(data, offset + 1, length - 1));
                if (sps != null) {
                    avcSps[sps.id] = sps;
                }
            } else if (type == 8) {
                var pps = parseAvcPps(rbsp(data, offset + 1, length - 1));
                if (pps != null) {
                    avcPps[pps.id] = pps;
                }
            }
        } catch (RuntimeException ignored) {
            // a damaged parameter set is not recorded; the slices naming it are left clear
        }
    }

    /**
     * Returns whether a NAL unit is a slice whose data follows a slice header: a coded slice of an H.264 non-IDR or
     * IDR picture ({@code nal_unit_type} 1 or 5) or a VCL unit of HEVC (0 to 31). The H.264 slice data partitions
     * (types 2 to 4), of which only the first starts with a slice header, are not slices here, as for Shaka Packager.
     *
     * @param header the first byte of the NAL unit header
     * @return {@code true} for a slice
     */
    public boolean isSlice(byte header) {
        var type = NalUnits.type(header, hevc);
        return hevc ? type < 32 : type == 1 || type == 5;
    }

    /**
     * Returns the size of the NAL unit header and the slice header of a slice NAL unit, in bytes of the unit as
     * stored (emulation prevention bytes included), rounded up to a whole byte.
     *
     * @param data the array holding the unit
     * @param offset the index of its NAL unit header
     * @param length its size in bytes
     * @return the size, or {@code -1} when the header cannot be read
     */
    public int headerSize(byte[] data, int offset, int length) {
        var bits = readHeader(data, offset, length);
        if (bits < 0) {
            return -1;
        }
        var nalHeader = hevc ? 2 : 1;
        return nalHeader + escapedLength(length - nalHeader, (int) ((bits + 7) >>> 3));
    }

    /**
     * Returns the size of the NAL unit header and the slice header of a slice NAL unit in bits of the unescaped
     * unit (its raw byte sequence payload preceded by the NAL unit header).
     *
     * @param data the array holding the unit
     * @param offset the index of its NAL unit header
     * @param length its size in bytes
     * @return the size in bits, or {@code -1} when the header cannot be read
     */
    long headerBits(byte[] data, int offset, int length) {
        var bits = readHeader(data, offset, length);
        return bits < 0 ? -1 : bits + (hevc ? 16 : 8);
    }

    /**
     * Reads the slice header of a NAL unit, leaving the emulation prevention bytes of the part read in
     * {@link #removed}.
     *
     * @return the slice header size in bits of the unescaped payload, or -1
     */
    private long readHeader(byte[] data, int offset, int length) {
        var nalHeader = hevc ? 2 : 1;
        if (length <= nalHeader) {
            return -1;
        }
        try {
            var payload = Math.min(length - nalHeader, 4096);
            while (true) {
                var unescaped = unescape(data, offset + nalHeader, payload);
                reader.reset(rbsp, 0, unescaped);
                var bits = hevc ? hevcSliceHeader(data[offset], data[offset + 1]) : avcSliceHeader(data[offset]);
                if (bits < 0) {
                    return -1;
                }
                if (!reader.overrun() && bits <= (long) unescaped * 8) {
                    return bits;
                }
                if (payload == length - nalHeader) {
                    return -1;
                }
                payload = length - nalHeader;
            }
        } catch (RuntimeException exception) {
            return -1;
        }
    }

    /**
     * Unescapes a payload into {@link #rbsp}, recording the removed bytes.
     */
    private int unescape(byte[] data, int offset, int length) {
        if (rbsp.length < length) {
            rbsp = new byte[length];
            removed = new int[length / 3 + 1];
        }
        var result = NalUnits.unescape(data, offset, length, rbsp, removed);
        removedCount = (int) (result >>> 32);
        return (int) result;
    }

    /**
     * The number of emulation prevention bytes the last {@link #unescape} removed.
     */
    private int removedCount;

    /**
     * Converts a length of unescaped bytes into the length of the stored bytes that hold them.
     */
    private int escapedLength(int stored, int unescaped) {
        var length = unescaped;
        for (var index = 0; index < removedCount && removed[index] < length; index++) {
            length++;
        }
        return Math.min(length, stored);
    }

    /**
     * Returns a reader over the unescaped payload of a parameter set.
     */
    private MsbBitReader rbsp(byte[] data, int offset, int length) {
        var copy = new byte[length];
        var size = NalUnits.unescape(data, offset, length, copy);
        return new MsbBitReader(copy, 0, size);
    }

    /**
     * Reads an H.264 slice header from {@link #reader}.
     *
     * @return the header size in bits, or -1
     */
    private long avcSliceHeader(byte header) {
        var type = header & 0x1F;
        var refIdc = (header >>> 5) & 3;
        var idr = type == 5;
        var r = reader;
        r.readUe();
        var sliceType = r.readUe() % 5;
        var ppsId = r.readUe();
        var pps = ppsId >= 0 && ppsId < avcPps.length ? avcPps[ppsId] : null;
        if (pps == null || avcSps[pps.spsId] == null) {
            return -1;
        }
        var sps = avcSps[pps.spsId];
        if (sps.separateColourPlane) {
            r.read(2);
        }
        r.skip(sps.frameNumBits);
        var field = false;
        if (!sps.frameMbsOnly) {
            field = r.readFlag();
            if (field) {
                r.readFlag();
            }
        }
        if (idr) {
            r.readUe();
        }
        if (sps.pocType == 0) {
            r.skip(sps.pocLsbBits);
            if (pps.bottomFieldPicOrder && !field) {
                r.readSe();
            }
        }
        if (sps.pocType == 1 && !sps.deltaPicOrderAlwaysZero) {
            r.readSe();
            if (pps.bottomFieldPicOrder && !field) {
                r.readSe();
            }
        }
        if (pps.redundantPicCnt) {
            r.readUe();
        }
        if (sliceType == B) {
            r.readFlag();
        }
        var l0 = pps.refIdxL0;
        var l1 = pps.refIdxL1;
        if (sliceType == P || sliceType == SP || sliceType == B) {
            if (r.readFlag()) {
                l0 = r.readUe() + 1;
                if (sliceType == B) {
                    l1 = r.readUe() + 1;
                }
            }
        }
        if (l0 > 32 || l1 > 32) {
            return -1;
        }
        if (sliceType != I && sliceType != SI) {
            if (!skipAvcModifications()) {
                return -1;
            }
        }
        if (sliceType == B && !skipAvcModifications()) {
            return -1;
        }
        if ((pps.weightedPred && (sliceType == P || sliceType == SP)) || (pps.weightedBipred == 1 && sliceType == B)) {
            r.readUe();
            if (sps.chromaArrayType != 0) {
                r.readUe();
            }
            skipAvcWeights(l0, sps.chromaArrayType);
            if (sliceType == B) {
                skipAvcWeights(l1, sps.chromaArrayType);
            }
        }
        if (refIdc != 0) {
            if (idr) {
                r.readFlag();
                r.readFlag();
            } else if (r.readFlag()) {
                for (var operation = 0; operation < 66; operation++) {
                    var control = r.readUe();
                    if (control == 0) {
                        break;
                    }
                    if (control > 6 || r.overrun()) {
                        return -1;
                    }
                    if (control == 1 || control == 3) {
                        r.readUe();
                    }
                    if (control == 2) {
                        r.readUe();
                    }
                    if (control == 3 || control == 6) {
                        r.readUe();
                    }
                    if (control == 4) {
                        r.readUe();
                    }
                }
            }
        }
        if (pps.entropyCoding && sliceType != I && sliceType != SI) {
            r.readUe();
        }
        r.readSe();
        if (sliceType == SP || sliceType == SI) {
            if (sliceType == SP) {
                r.readFlag();
            }
            r.readSe();
        }
        if (pps.deblockingControl && r.readUe() != 1) {
            r.readSe();
            r.readSe();
        }
        if (pps.sliceGroups > 1 && pps.sliceGroupMapType >= 3 && pps.sliceGroupMapType <= 5) {
            // Ceil(Log2(PicSizeInMapUnits / SliceGroupChangeRate + 1)) with an exact division
            var mapUnits = (long) sps.widthInMbs * sps.heightInMapUnits;
            var rate = (long) pps.sliceGroupChangeRate;
            var bits = 0;
            while ((rate << bits) < mapUnits + rate && bits < 32) {
                bits++;
            }
            r.skip(bits);
        }
        return r.bitPosition();
    }

    /**
     * Skips one {@code ref_pic_list_modification} list of an H.264 slice header.
     *
     * @return {@code false} when the list is malformed
     */
    private boolean skipAvcModifications() {
        var r = reader;
        if (!r.readFlag()) {
            return true;
        }
        for (var entry = 0; entry < 33; entry++) {
            var idc = r.readUe();
            if (idc == 3) {
                return true;
            }
            if (idc > 2 || r.overrun()) {
                return false;
            }
            r.readUe();
        }
        return false;
    }

    /**
     * Skips the weights of one reference list of an H.264 {@code pred_weight_table}.
     */
    private void skipAvcWeights(int references, int chromaArrayType) {
        var r = reader;
        for (var index = 0; index < references && !r.overrun(); index++) {
            if (r.readFlag()) {
                r.readSe();
                r.readSe();
            }
            if (chromaArrayType != 0 && r.readFlag()) {
                for (var component = 0; component < 4; component++) {
                    r.readSe();
                }
            }
        }
    }

    /**
     * Reads an HEVC slice segment header from {@link #reader}.
     *
     * @return the header size in bits, or -1
     */
    private long hevcSliceHeader(byte first, byte second) {
        var type = (first >>> 1) & 0x3F;
        var layer = ((first & 1) << 5) | ((second & 0xFF) >>> 3);
        if (layer != 0) {
            return -1;
        }
        var r = reader;
        var firstSegment = r.readFlag();
        if (type >= 16 && type <= 23) {
            r.readFlag();
        }
        var ppsId = r.readUe();
        var pps = ppsId < hevcPps.length ? hevcPps[ppsId] : null;
        if (pps == null || hevcSps[pps.spsId] == null) {
            return -1;
        }
        var sps = hevcSps[pps.spsId];
        var dependent = false;
        if (!firstSegment) {
            if (pps.dependentSlices) {
                dependent = r.readFlag();
            }
            r.skip(ceilLog2(sps.pictureCtbs));
        }
        if (!dependent) {
            r.skip(pps.extraSliceHeaderBits);
            var sliceType = r.readUe();
            if (pps.outputFlagPresent) {
                r.readFlag();
            }
            if (sps.separateColourPlane) {
                r.read(2);
            }
            var temporalMvp = false;
            var currentPictures = 0;
            if (type != 19 && type != 20) {
                r.skip(sps.pocLsbBits);
                int[] rps;
                if (!r.readFlag()) {
                    rps = parseReferencePictureSet(r, sps.shortTermSets, sps.shortTermSets.length);
                    if (rps == null) {
                        return -1;
                    }
                } else {
                    var index = sps.shortTermSets.length > 1 ? r.read(ceilLog2(sps.shortTermSets.length)) : 0;
                    if (index >= sps.shortTermSets.length) {
                        return -1;
                    }
                    rps = sps.shortTermSets[index];
                }
                currentPictures = rps[2];
                if (sps.longTermRefs) {
                    var fromSps = sps.longTermUsed.length > 0 ? r.readUe() : 0;
                    var pictures = r.readUe();
                    if (fromSps + (long) pictures > 32) {
                        return -1;
                    }
                    for (var index = 0; index < fromSps + pictures; index++) {
                        if (index < fromSps) {
                            var lt = sps.longTermUsed.length > 1 ? r.read(ceilLog2(sps.longTermUsed.length)) : 0;
                            if (lt >= sps.longTermUsed.length) {
                                return -1;
                            }
                            currentPictures += sps.longTermUsed[lt] ? 1 : 0;
                        } else {
                            r.skip(sps.pocLsbBits);
                            currentPictures += r.readFlag() ? 1 : 0;
                        }
                        if (r.readFlag()) {
                            r.readUe();
                        }
                    }
                }
                if (sps.temporalMvp) {
                    temporalMvp = r.readFlag();
                }
            }
            var saoLuma = false;
            var saoChroma = false;
            if (sps.sao) {
                saoLuma = r.readFlag();
                if (sps.chromaArrayType != 0) {
                    saoChroma = r.readFlag();
                }
            }
            if (sliceType == HEVC_P || sliceType == HEVC_B) {
                var l0 = pps.refIdxL0;
                var l1 = pps.refIdxL1;
                if (r.readFlag()) {
                    l0 = r.readUe() + 1;
                    if (sliceType == HEVC_B) {
                        l1 = r.readUe() + 1;
                    }
                }
                if (l0 > 16 || l1 > 16) {
                    return -1;
                }
                if (pps.listsModification && currentPictures > 1) {
                    var bits = ceilLog2(currentPictures);
                    if (r.readFlag()) {
                        r.skip((long) bits * l0);
                    }
                    if (sliceType == HEVC_B && r.readFlag()) {
                        r.skip((long) bits * l1);
                    }
                }
                if (sliceType == HEVC_B) {
                    r.readFlag();
                }
                if (pps.cabacInitPresent) {
                    r.readFlag();
                }
                if (temporalMvp) {
                    var fromL0 = true;
                    if (sliceType == HEVC_B) {
                        fromL0 = r.readFlag();
                    }
                    if (fromL0 ? l0 > 1 : l1 > 1) {
                        r.readUe();
                    }
                }
                if ((pps.weightedPred && sliceType == HEVC_P) || (pps.weightedBipred && sliceType == HEVC_B)) {
                    r.readUe();
                    if (sps.chromaArrayType != 0) {
                        r.readSe();
                    }
                    skipHevcWeights(l0, sps.chromaArrayType);
                    if (sliceType == HEVC_B) {
                        skipHevcWeights(l1, sps.chromaArrayType);
                    }
                }
                r.readUe();
            }
            r.readSe();
            if (pps.sliceChromaQpOffsets) {
                r.readSe();
                r.readSe();
            }
            if (pps.chromaQpOffsetList) {
                r.readFlag();
            }
            var deblockingDisabled = pps.deblockingDisabled;
            if (pps.deblockingOverride && r.readFlag()) {
                deblockingDisabled = r.readFlag();
                if (!deblockingDisabled) {
                    r.readSe();
                    r.readSe();
                }
            }
            if (pps.loopFilterAcrossSlices && (saoLuma || saoChroma || !deblockingDisabled)) {
                r.readFlag();
            }
        }
        if (pps.tiles || pps.entropySync) {
            var entries = r.readUe();
            if (entries > 0) {
                var bits = r.readUe() + 1;
                if (bits > 32) {
                    return -1;
                }
                r.skip((long) entries * bits);
            }
        }
        if (pps.headerExtension) {
            r.skip(r.readUe() * 8L);
        }
        r.readFlag();
        if (!r.isByteAligned()) {
            r.alignToByte();
        }
        return r.bitPosition();
    }

    /**
     * Skips the weights of one reference list of an HEVC {@code pred_weight_table}.
     */
    private void skipHevcWeights(int references, int chromaArrayType) {
        var r = reader;
        var luma = new boolean[references];
        var chroma = new boolean[references];
        for (var index = 0; index < references; index++) {
            luma[index] = r.readFlag();
        }
        if (chromaArrayType != 0) {
            for (var index = 0; index < references; index++) {
                chroma[index] = r.readFlag();
            }
        }
        for (var index = 0; index < references; index++) {
            if (luma[index]) {
                r.readSe();
                r.readSe();
            }
            if (chroma[index]) {
                for (var component = 0; component < 4; component++) {
                    r.readSe();
                }
            }
        }
    }

    /**
     * Parses an HEVC {@code st_ref_pic_set}.
     *
     * @param r the reader
     * @param sets the sets of the SPS parsed so far
     * @param index the index of the set being parsed; {@code sets.length} for the set of a slice header
     * @return the delta POCs and flags as {@link HevcSps#shortTermSets} stores them, or {@code null} when malformed
     */
    private static int[] parseReferencePictureSet(MsbBitReader r, int[][] sets, int index) {
        var predicted = index != 0 && r.readFlag();
        if (predicted) {
            var delta = 1;
            if (index == sets.length) {
                delta = r.readUe() + 1;
            }
            if (delta > index) {
                return null;
            }
            var reference = sets[index - delta];
            var sign = r.readFlag();
            var deltaRps = (r.readUe() + 1) * (sign ? -1 : 1);
            var negatives = reference[0];
            var positives = reference[1];
            var count = negatives + positives;
            var used = new boolean[count + 1];
            var useDelta = new boolean[count + 1];
            for (var j = 0; j <= count; j++) {
                used[j] = r.readFlag();
                useDelta[j] = used[j] || r.readFlag();
            }
            var s0 = new int[33];
            var s0Used = new boolean[33];
            var s1 = new int[33];
            var s1Used = new boolean[33];
            var n0 = 0;
            for (var j = positives - 1; j >= 0; j--) {
                var poc = reference[3 + negatives * 2 + j * 2] + deltaRps;
                if (poc < 0 && useDelta[negatives + j] && n0 < 32) {
                    s0[n0] = poc;
                    s0Used[n0++] = used[negatives + j];
                }
            }
            if (deltaRps < 0 && useDelta[count] && n0 < 32) {
                s0[n0] = deltaRps;
                s0Used[n0++] = used[count];
            }
            for (var j = 0; j < negatives; j++) {
                var poc = reference[3 + j * 2] + deltaRps;
                if (poc < 0 && useDelta[j] && n0 < 32) {
                    s0[n0] = poc;
                    s0Used[n0++] = used[j];
                }
            }
            var n1 = 0;
            for (var j = negatives - 1; j >= 0; j--) {
                var poc = reference[3 + j * 2] + deltaRps;
                if (poc > 0 && useDelta[j] && n1 < 32) {
                    s1[n1] = poc;
                    s1Used[n1++] = used[j];
                }
            }
            if (deltaRps > 0 && useDelta[count] && n1 < 32) {
                s1[n1] = deltaRps;
                s1Used[n1++] = used[count];
            }
            for (var j = 0; j < positives; j++) {
                var poc = reference[3 + negatives * 2 + j * 2] + deltaRps;
                if (poc > 0 && useDelta[negatives + j] && n1 < 32) {
                    s1[n1] = poc;
                    s1Used[n1++] = used[negatives + j];
                }
            }
            return referencePictureSet(s0, s0Used, n0, s1, s1Used, n1);
        }
        var negatives = r.readUe();
        var positives = r.readUe();
        if (negatives > 16 || positives > 16) {
            return null;
        }
        var s0 = new int[negatives];
        var s0Used = new boolean[negatives];
        var previous = 0;
        for (var j = 0; j < negatives; j++) {
            previous -= r.readUe() + 1;
            s0[j] = previous;
            s0Used[j] = r.readFlag();
        }
        var s1 = new int[positives];
        var s1Used = new boolean[positives];
        previous = 0;
        for (var j = 0; j < positives; j++) {
            previous += r.readUe() + 1;
            s1[j] = previous;
            s1Used[j] = r.readFlag();
        }
        return referencePictureSet(s0, s0Used, negatives, s1, s1Used, positives);
    }

    /**
     * Packs a short-term reference picture set: the negative and positive counts, the number of pictures used by
     * the current picture, then a delta POC and a used flag per picture, negative ones first.
     */
    private static int[] referencePictureSet(int[] s0, boolean[] s0Used, int n0, int[] s1, boolean[] s1Used, int n1) {
        var result = new int[3 + 2 * (n0 + n1)];
        result[0] = n0;
        result[1] = n1;
        var used = 0;
        for (var j = 0; j < n0; j++) {
            result[3 + 2 * j] = s0[j];
            result[4 + 2 * j] = s0Used[j] ? 1 : 0;
            used += s0Used[j] ? 1 : 0;
        }
        for (var j = 0; j < n1; j++) {
            result[3 + 2 * (n0 + j)] = s1[j];
            result[4 + 2 * (n0 + j)] = s1Used[j] ? 1 : 0;
            used += s1Used[j] ? 1 : 0;
        }
        result[2] = used;
        return result;
    }

    /**
     * Parses the fields of an H.264 sequence parameter set that slice headers depend on.
     */
    private static H264Sps parseAvcSps(MsbBitReader r) {
        var sps = new H264Sps();
        var profile = r.read(8);
        r.skip(16);
        sps.id = r.readUe();
        if (sps.id >= 32) {
            return null;
        }
        var chromaFormat = 1;
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44 || profile == 83
                || profile == 86 || profile == 118 || profile == 128 || profile == 138 || profile == 139
                || profile == 134 || profile == 135) {
            chromaFormat = r.readUe();
            if (chromaFormat == 3) {
                sps.separateColourPlane = r.readFlag();
            }
            r.readUe();
            r.readUe();
            r.readFlag();
            if (r.readFlag()) {
                for (var list = 0; list < (chromaFormat != 3 ? 8 : 12); list++) {
                    if (r.readFlag()) {
                        skipAvcScalingList(r, list < 6 ? 16 : 64);
                    }
                }
            }
        }
        sps.chromaArrayType = sps.separateColourPlane ? 0 : chromaFormat;
        sps.frameNumBits = r.readUe() + 4;
        sps.pocType = r.readUe();
        if (sps.pocType == 0) {
            sps.pocLsbBits = r.readUe() + 4;
        } else if (sps.pocType == 1) {
            sps.deltaPicOrderAlwaysZero = r.readFlag();
            r.readSe();
            r.readSe();
            var cycle = r.readUe();
            if (cycle > 255) {
                return null;
            }
            for (var index = 0; index < cycle; index++) {
                r.readSe();
            }
        }
        r.readUe();
        r.readFlag();
        sps.widthInMbs = r.readUe() + 1;
        var heightInMapUnits = r.readUe() + 1;
        sps.frameMbsOnly = r.readFlag();
        sps.heightInMapUnits = heightInMapUnits;
        if (r.overrun() || sps.frameNumBits > 16 || sps.pocLsbBits > 16) {
            return null;
        }
        return sps;
    }

    /**
     * Skips an H.264 {@code scaling_list}.
     */
    private static void skipAvcScalingList(MsbBitReader r, int size) {
        var last = 8;
        var next = 8;
        for (var index = 0; index < size; index++) {
            if (next != 0) {
                next = (last + r.readSe() + 256) & 0xFF;
            }
            last = next == 0 ? last : next;
        }
    }

    /**
     * Parses the fields of an H.264 picture parameter set that slice headers depend on.
     */
    private static H264Pps parseAvcPps(MsbBitReader r) {
        var pps = new H264Pps();
        pps.id = r.readUe();
        pps.spsId = r.readUe();
        if (pps.id >= 256 || pps.spsId >= 32) {
            return null;
        }
        pps.entropyCoding = r.readFlag();
        pps.bottomFieldPicOrder = r.readFlag();
        pps.sliceGroups = r.readUe() + 1;
        if (pps.sliceGroups > 8) {
            return null;
        }
        if (pps.sliceGroups > 1) {
            pps.sliceGroupMapType = r.readUe();
            switch (pps.sliceGroupMapType) {
                case 0 -> {
                    for (var group = 0; group < pps.sliceGroups; group++) {
                        r.readUe();
                    }
                }
                case 2 -> {
                    for (var group = 0; group < pps.sliceGroups - 1; group++) {
                        r.readUe();
                        r.readUe();
                    }
                }
                case 3, 4, 5 -> {
                    r.readFlag();
                    pps.sliceGroupChangeRate = r.readUe() + 1;
                }
                case 6 -> {
                    var units = r.readUe() + 1;
                    r.skip((long) units * ceilLog2(pps.sliceGroups));
                }
                default -> {
                }
            }
        }
        pps.refIdxL0 = r.readUe() + 1;
        pps.refIdxL1 = r.readUe() + 1;
        pps.weightedPred = r.readFlag();
        pps.weightedBipred = r.read(2);
        r.readSe();
        r.readSe();
        r.readSe();
        pps.deblockingControl = r.readFlag();
        r.readFlag();
        pps.redundantPicCnt = r.readFlag();
        return r.overrun() ? null : pps;
    }

    /**
     * Parses the fields of an HEVC sequence parameter set that slice headers depend on.
     */
    private static HevcSps parseHevcSps(MsbBitReader r) {
        var sps = new HevcSps();
        r.read(4);
        var subLayers = r.read(3);
        r.readFlag();
        skipProfileTierLevel(r, subLayers);
        sps.id = r.readUe();
        if (sps.id >= 16) {
            return null;
        }
        var chromaFormat = r.readUe();
        if (chromaFormat == 3) {
            sps.separateColourPlane = r.readFlag();
        }
        sps.chromaArrayType = sps.separateColourPlane ? 0 : chromaFormat;
        var width = r.readUe();
        var height = r.readUe();
        if (r.readFlag()) {
            r.readUe();
            r.readUe();
            r.readUe();
            r.readUe();
        }
        r.readUe();
        r.readUe();
        sps.pocLsbBits = r.readUe() + 4;
        var orderingInfo = r.readFlag();
        for (var layer = orderingInfo ? 0 : subLayers; layer <= subLayers; layer++) {
            r.readUe();
            r.readUe();
            r.readUe();
        }
        var minCb = r.readUe() + 3;
        var ctb = minCb + r.readUe();
        r.readUe();
        r.readUe();
        r.readUe();
        r.readUe();
        if (ctb > 6 || sps.pocLsbBits > 16) {
            return null;
        }
        var ctbSize = 1 << ctb;
        sps.pictureCtbs = (long) ((width + ctbSize - 1) / ctbSize) * ((height + ctbSize - 1) / ctbSize);
        if (r.readFlag() && r.readFlag()) {
            skipHevcScalingLists(r);
        }
        r.readFlag();
        sps.sao = r.readFlag();
        if (r.readFlag()) {
            r.read(8);
            r.readUe();
            r.readUe();
            r.readFlag();
        }
        var sets = r.readUe();
        if (sets > 64) {
            return null;
        }
        sps.shortTermSets = new int[sets][];
        for (var index = 0; index < sets; index++) {
            var set = parseReferencePictureSet(r, sps.shortTermSets, index);
            if (set == null) {
                return null;
            }
            sps.shortTermSets[index] = set;
        }
        sps.longTermRefs = r.readFlag();
        if (sps.longTermRefs) {
            var count = r.readUe();
            if (count > 32) {
                return null;
            }
            sps.longTermUsed = new boolean[count];
            for (var index = 0; index < count; index++) {
                r.skip(sps.pocLsbBits);
                sps.longTermUsed[index] = r.readFlag();
            }
        }
        sps.temporalMvp = r.readFlag();
        return r.overrun() ? null : sps;
    }

    /**
     * Skips an HEVC {@code profile_tier_level} with its profile.
     */
    private static void skipProfileTierLevel(MsbBitReader r, int subLayers) {
        r.skip(88);
        r.skip(8);
        var profiles = new boolean[subLayers];
        var levels = new boolean[subLayers];
        for (var layer = 0; layer < subLayers; layer++) {
            profiles[layer] = r.readFlag();
            levels[layer] = r.readFlag();
        }
        if (subLayers > 0) {
            r.skip(2L * (8 - subLayers));
        }
        for (var layer = 0; layer < subLayers; layer++) {
            if (profiles[layer]) {
                r.skip(88);
            }
            if (levels[layer]) {
                r.skip(8);
            }
        }
    }

    /**
     * Skips an HEVC {@code scaling_list_data}.
     */
    private static void skipHevcScalingLists(MsbBitReader r) {
        for (var size = 0; size < 4; size++) {
            for (var matrix = 0; matrix < 6; matrix += size == 3 ? 3 : 1) {
                if (!r.readFlag()) {
                    r.readUe();
                } else {
                    var coefficients = Math.min(64, 1 << (4 + (size << 1)));
                    if (size > 1) {
                        r.readSe();
                    }
                    for (var index = 0; index < coefficients; index++) {
                        r.readSe();
                    }
                }
            }
        }
    }

    /**
     * Parses the fields of an HEVC picture parameter set that slice headers depend on.
     */
    private static HevcPps parseHevcPps(MsbBitReader r) {
        var pps = new HevcPps();
        pps.id = r.readUe();
        pps.spsId = r.readUe();
        if (pps.id >= 64 || pps.spsId >= 16) {
            return null;
        }
        pps.dependentSlices = r.readFlag();
        pps.outputFlagPresent = r.readFlag();
        pps.extraSliceHeaderBits = r.read(3);
        r.readFlag();
        pps.cabacInitPresent = r.readFlag();
        pps.refIdxL0 = r.readUe() + 1;
        pps.refIdxL1 = r.readUe() + 1;
        r.readSe();
        r.readFlag();
        var transformSkip = r.readFlag();
        if (r.readFlag()) {
            r.readUe();
        }
        r.readSe();
        r.readSe();
        pps.sliceChromaQpOffsets = r.readFlag();
        pps.weightedPred = r.readFlag();
        pps.weightedBipred = r.readFlag();
        r.readFlag();
        pps.tiles = r.readFlag();
        pps.entropySync = r.readFlag();
        if (pps.tiles) {
            var columns = r.readUe();
            var rows = r.readUe();
            if (columns > 64 || rows > 64) {
                return null;
            }
            if (!r.readFlag()) {
                for (var index = 0; index < columns + rows; index++) {
                    r.readUe();
                }
            }
            r.readFlag();
        }
        pps.loopFilterAcrossSlices = r.readFlag();
        if (r.readFlag()) {
            pps.deblockingOverride = r.readFlag();
            pps.deblockingDisabled = r.readFlag();
            if (!pps.deblockingDisabled) {
                r.readSe();
                r.readSe();
            }
        }
        if (r.readFlag()) {
            skipHevcScalingLists(r);
        }
        pps.listsModification = r.readFlag();
        r.readUe();
        pps.headerExtension = r.readFlag();
        if (r.readFlag()) {
            var range = r.readFlag();
            r.readFlag();
            r.readFlag();
            var scc = r.readFlag();
            r.read(4);
            if (scc) {
                return null;
            }
            if (range) {
                if (transformSkip) {
                    r.readUe();
                }
                r.readFlag();
                pps.chromaQpOffsetList = r.readFlag();
            }
        }
        return r.overrun() ? null : pps;
    }

    /**
     * Returns {@code Ceil(Log2(value))}, 0 for values up to 1.
     */
    private static int ceilLog2(long value) {
        return value <= 1 ? 0 : 64 - Long.numberOfLeadingZeros(value - 1);
    }

    /**
     * The fields of an H.264 sequence parameter set that slice headers depend on.
     */
    private static final class H264Sps {
        int id;
        boolean separateColourPlane;
        int chromaArrayType;
        int frameNumBits;
        int pocType;
        int pocLsbBits;
        boolean deltaPicOrderAlwaysZero;
        boolean frameMbsOnly;
        int widthInMbs;
        int heightInMapUnits;
    }

    /**
     * The fields of an H.264 picture parameter set that slice headers depend on.
     */
    private static final class H264Pps {
        int id;
        int spsId;
        boolean entropyCoding;
        boolean bottomFieldPicOrder;
        int sliceGroups;
        int sliceGroupMapType;
        int sliceGroupChangeRate = 1;
        int refIdxL0;
        int refIdxL1;
        boolean weightedPred;
        int weightedBipred;
        boolean deblockingControl;
        boolean redundantPicCnt;
    }

    /**
     * The fields of an HEVC sequence parameter set that slice headers depend on.
     */
    private static final class HevcSps {
        int id;
        boolean separateColourPlane;
        int chromaArrayType;
        int pocLsbBits;
        long pictureCtbs;
        boolean sao;
        /**
         * The short-term reference picture sets, as {@link #referencePictureSet} packs them.
         */
        int[][] shortTermSets = new int[0][];
        boolean longTermRefs;
        boolean[] longTermUsed = new boolean[0];
        boolean temporalMvp;
    }

    /**
     * The fields of an HEVC picture parameter set that slice headers depend on.
     */
    private static final class HevcPps {
        int id;
        int spsId;
        boolean dependentSlices;
        boolean outputFlagPresent;
        int extraSliceHeaderBits;
        boolean cabacInitPresent;
        int refIdxL0;
        int refIdxL1;
        boolean sliceChromaQpOffsets;
        boolean weightedPred;
        boolean weightedBipred;
        boolean tiles;
        boolean entropySync;
        boolean loopFilterAcrossSlices;
        boolean deblockingOverride;
        boolean deblockingDisabled;
        boolean listsModification;
        boolean headerExtension;
        boolean chromaQpOffsetList;
    }
}
