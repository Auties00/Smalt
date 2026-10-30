/* Ports libtheora 1.2.0 (lib/decode.c, lib/state.c, lib/idct.c, lib/fragment.c). Copyright (C) 2002-2009
   Xiph.org Foundation; BSD 3-Clause License. Modified for Smalt. */
package com.github.auties00.smalt.video.theora.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.MediaLimits;
import com.github.auties00.smalt.image.ChromaFormat;
import com.github.auties00.smalt.image.ColorInfo;
import com.github.auties00.smalt.image.YuvPicture;
import com.github.auties00.smalt.internal.limits.LimitChecks;

import java.util.Arrays;

/**
 * Decodes the frames of a Theora stream into reference frames and pictures, as libtheora's
 * {@code th_decode_packetin} does without its optional post-processing.
 *
 * <p>The planes are held bottom row first, as the bitstream codes them, with 16 luma samples of border replicated
 * around them for the unrestricted motion vectors. Fragments (8x8 blocks) are numbered row by row from the bottom
 * left of each plane, the luma plane first; super blocks (4x4 fragments) likewise, and the fragments of a super
 * block, and the macro blocks (2x2 luma fragments) of a luma super block, follow the Hilbert curve the
 * specification defines.
 *
 * <p>Instances are not thread-safe.
 */
public final class TheoraFrameDecoder {
    private static final int INTER_NOMV = 0;
    private static final int INTRA = 1;
    private static final int INTER_MV = 2;
    private static final int INTER_MV_LAST = 3;
    private static final int INTER_MV_LAST2 = 4;
    private static final int GOLDEN_NOMV = 5;
    private static final int GOLDEN_MV = 6;
    private static final int INTER_MV_FOUR = 7;

    private static final int FRAME_GOLD = 0;
    private static final int FRAME_PREV = 1;
    private static final int FRAME_SELF = 2;
    private static final int FRAME_NONE = 3;

    private static final int PADDING = 16;

    private static final long EOB_FINISH = Long.MAX_VALUE;

    /** The reference frame of each mode. */
    private static final int[] FRAME_FOR_MODE = {FRAME_PREV, FRAME_SELF, FRAME_PREV, FRAME_PREV, FRAME_PREV,
            FRAME_GOLD, FRAME_GOLD, FRAME_PREV};

    /** The mode alphabets of the schemes 1 to 7; scheme 0 permutes the last. */
    private static final int[][] MODE_ALPHABETS = {
            {INTER_MV_LAST, INTER_MV_LAST2, INTER_MV, INTER_NOMV, INTRA, GOLDEN_NOMV, GOLDEN_MV, INTER_MV_FOUR},
            {INTER_MV_LAST, INTER_MV_LAST2, INTER_NOMV, INTER_MV, INTRA, GOLDEN_NOMV, GOLDEN_MV, INTER_MV_FOUR},
            {INTER_MV_LAST, INTER_MV, INTER_MV_LAST2, INTER_NOMV, INTRA, GOLDEN_NOMV, GOLDEN_MV, INTER_MV_FOUR},
            {INTER_MV_LAST, INTER_MV, INTER_NOMV, INTER_MV_LAST2, INTRA, GOLDEN_NOMV, GOLDEN_MV, INTER_MV_FOUR},
            {INTER_NOMV, INTER_MV_LAST, INTER_MV_LAST2, INTER_MV, INTRA, GOLDEN_NOMV, GOLDEN_MV, INTER_MV_FOUR},
            {INTER_NOMV, GOLDEN_NOMV, INTER_MV_LAST, INTER_MV_LAST2, INTER_MV, INTRA, GOLDEN_MV, INTER_MV_FOUR},
            {INTER_NOMV, INTRA, INTER_MV, INTER_MV_LAST, INTER_MV_LAST2, GOLDEN_NOMV, GOLDEN_MV, INTER_MV_FOUR}};

    /** The (quadrant, block) of each fragment of a 4x4 super block, by row from the bottom and column. */
    private static final int[][][] SB_MAP = {
            {{0, 0}, {0, 1}, {3, 2}, {3, 3}},
            {{0, 3}, {0, 2}, {3, 1}, {3, 0}},
            {{1, 0}, {1, 3}, {2, 0}, {2, 3}},
            {{1, 1}, {1, 2}, {2, 1}, {2, 2}}};

    /** The quadrant of each macro block of a super block, by row and column. */
    private static final int[][] MB_MAP = {{0, 3}, {1, 2}};

    /** The (plane, block) entries of a macro block map that can be valid, by pixel format. */
    private static final int[][] MB_MAP_IDXS = {
            {0, 1, 2, 3, 4, 8},
            {0, 1, 2, 3, 4, 5, 8, 9},
            {0, 1, 2, 3, 4, 6, 8, 10},
            {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11}};

    /** The integer part of a motion vector component, by quarter-pixel precision and component + 31. */
    private static final int[][] MVMAP = {
            {-15, -15, -14, -14, -13, -13, -12, -12, -11, -11, -10, -10, -9, -9, -8,
                    -8, -7, -7, -6, -6, -5, -5, -4, -4, -3, -3, -2, -2, -1, -1, 0,
                    0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7,
                    8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14, 15, 15},
            {-7, -7, -7, -7, -6, -6, -6, -6, -5, -5, -5, -5, -4, -4, -4,
                    -4, -3, -3, -3, -3, -2, -2, -2, -2, -1, -1, -1, -1, 0, 0, 0,
                    0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3,
                    4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 6, 6, 7, 7, 7, 7}};

    /** The offset of the second prediction of a fractional motion vector component. */
    private static final int[][] MVMAP2 = {
            {-1, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1,
                    0, -1, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1, 0, -1,
                    0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1,
                    0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1, 0, 1},
            {-1, -1, -1, 0, -1, -1, -1, 0, -1, -1, -1, 0, -1, -1, -1,
                    0, -1, -1, -1, 0, -1, -1, -1, 0, -1, -1, -1, 0, -1, -1, -1,
                    0, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1,
                    0, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1, 0, 1, 1, 1}};

    private static final int C1S7 = 64277;
    private static final int C2S6 = 60547;
    private static final int C3S5 = 54491;
    private static final int C4S4 = 46341;
    private static final int C5S3 = 36410;
    private static final int C6S2 = 25080;
    private static final int C7S1 = 12785;

    private final TheoraSetup setup;
    private final TheoraBits bits = new TheoraBits();
    private final ColorInfo color;

    private final int shiftX;
    private final int shiftY;
    private final int[] hfrags = new int[3];
    private final int[] vfrags = new int[3];
    private final int[] fragOffset = new int[4];
    private final int[] sbOffset = new int[4];
    private final int[] padX = new int[3];
    private final int[] padY = new int[3];
    private final int[] stride = new int[3];
    private final int[] origin = new int[3];
    private final int[] planeSize = new int[3];
    private final int fragCount;
    private final int sbCount;
    private final int mbCount;

    /** The fragments of each super block, 16 per super block by quadrant and block, -1 outside the frame. */
    private final int[] sbMaps;
    /** The quadrants of each super block holding at least one fragment, one bit per quadrant. */
    private final int[] sbQuadValid;
    /** The 12 fragments of each macro block by plane and block, -1 where the pixel format has none. */
    private final int[] mbMaps;
    /** The mode of each macro block, -1 outside the frame. */
    private final int[] mbModes;

    private final boolean[] coded;
    private final byte[] refi;
    private final byte[] fragMode;
    private final byte[] qii;
    private final int[] dc;
    private final int[] mvX;
    private final int[] mvY;
    private final int[] codedIndex;
    private final int[] codedFrags;
    private final int[] codedPerPlane = new int[3];
    private int codedTotal;

    private final boolean[] sbPartial;
    private final boolean[] sbFull;

    private final short[] coefficients;
    private final byte[] tokenStart;
    private final byte[] lastStart;

    private final byte[][][] buffers = new byte[3][3][];
    private int gold = -1;
    private int prev = -1;

    private final int[] qis = new int[3];
    private int qiCount;
    private boolean intraFrame;

    private final int[] block = new int[64];
    private final int[] work = new int[64];
    private final int[] transpose = new int[64];
    private final int[] bounding = new int[256];
    private final int[] lumaMvX = new int[4];
    private final int[] lumaMvY = new int[4];
    private final int[] chromaMvX = new int[4];
    private final int[] chromaMvY = new int[4];
    private final int[] predLast = new int[3];

    /** The mode alphabet a frame of mode scheme 0 codes in its header. */
    private final int[] customAlphabet = new int[8];

    private YuvPicture picture;
    private boolean keyframe;

    /**
     * Constructs a decoder for the stream the headers describe.
     *
     * @param setup the stream's parameters
     * @param color the color description of the pictures
     * @param limits the resource limits
     * @throws MediaException.LimitExceeded if the frame size exceeds the limits
     */
    public TheoraFrameDecoder(TheoraSetup setup, ColorInfo color, MediaLimits limits) {
        this.setup = setup;
        this.color = color;
        LimitChecks.checkPicture(limits, setup.frameWidth, setup.frameHeight);
        LimitChecks.checkAllocation(limits, (long) setup.frameWidth * setup.frameHeight * 12);
        shiftX = setup.pixelFormat == 3 ? 0 : 1;
        shiftY = setup.pixelFormat == 0 ? 1 : 0;
        var sbs = 0;
        var frags = 0;
        for (var pli = 0; pli < 3; pli++) {
            var sx = pli == 0 ? 0 : shiftX;
            var sy = pli == 0 ? 0 : shiftY;
            hfrags[pli] = setup.frameWidth >> sx >> 3;
            vfrags[pli] = setup.frameHeight >> sy >> 3;
            fragOffset[pli] = frags;
            sbOffset[pli] = sbs;
            frags += hfrags[pli] * vfrags[pli];
            sbs += ((hfrags[pli] + 3) >> 2) * ((vfrags[pli] + 3) >> 2);
            padX[pli] = PADDING >> sx;
            padY[pli] = PADDING >> sy;
            stride[pli] = (hfrags[pli] << 3) + 2 * padX[pli];
            origin[pli] = padY[pli] * stride[pli] + padX[pli];
            planeSize[pli] = stride[pli] * ((vfrags[pli] << 3) + 2 * padY[pli]);
        }
        fragOffset[3] = frags;
        sbOffset[3] = sbs;
        fragCount = frags;
        sbCount = sbs;
        mbCount = (sbOffset[1] - sbOffset[0]) << 2;
        sbMaps = new int[sbCount * 16];
        sbQuadValid = new int[sbCount];
        mbMaps = new int[mbCount * 12];
        mbModes = new int[mbCount];
        coded = new boolean[fragCount];
        refi = new byte[fragCount];
        fragMode = new byte[fragCount];
        qii = new byte[fragCount];
        dc = new int[fragCount];
        mvX = new int[fragCount];
        mvY = new int[fragCount];
        codedIndex = new int[fragCount];
        codedFrags = new int[fragCount];
        sbPartial = new boolean[sbCount];
        sbFull = new boolean[sbCount];
        coefficients = new short[fragCount * 64];
        tokenStart = new byte[fragCount];
        lastStart = new byte[fragCount];
        for (var pli = 0; pli < 3; pli++) {
            createSuperBlockMaps(pli);
        }
        createMacroBlockMaps();
        for (var b = 0; b < 3; b++) {
            for (var pli = 0; pli < 3; pli++) {
                buffers[b][pli] = new byte[planeSize[pli]];
            }
        }
    }

    private void createSuperBlockMaps(int pli) {
        var sbi = sbOffset[pli];
        var rowFrag = fragOffset[pli];
        var h = hfrags[pli];
        var v = vfrags[pli];
        for (var y = 0; y < v; y += 4) {
            var imax = Math.min(v - y, 4);
            for (var x = 0; x < h; x += 4, sbi++) {
                var jmax = Math.min(h - x, 4);
                Arrays.fill(sbMaps, sbi * 16, sbi * 16 + 16, -1);
                var frag = rowFrag + x;
                for (var i = 0; i < imax; i++) {
                    for (var j = 0; j < jmax; j++) {
                        sbMaps[sbi * 16 + SB_MAP[i][j][0] * 4 + SB_MAP[i][j][1]] = frag + j;
                    }
                    frag += h;
                }
                for (var quad = 0; quad < 4; quad++) {
                    if (sbMaps[sbi * 16 + quad * 4 + (quad & quad << 1)] >= 0) {
                        sbQuadValid[sbi] |= 1 << quad;
                    }
                }
            }
            rowFrag += h << 2;
        }
    }

    private void createMacroBlockMaps() {
        var sbi = 0;
        for (var y = 0; y < vfrags[0]; y += 4) {
            for (var x = 0; x < hfrags[0]; x += 4, sbi++) {
                for (var ymb = 0; ymb < 2; ymb++) {
                    for (var xmb = 0; xmb < 2; xmb++) {
                        var mbi = sbi << 2 | MB_MAP[ymb][xmb];
                        var mbx = x | xmb << 1;
                        var mby = y | ymb << 1;
                        var base = mbi * 12;
                        Arrays.fill(mbMaps, base, base + 12, -1);
                        if (mbx >= hfrags[0] || mby >= vfrags[0]) {
                            mbModes[mbi] = -1;
                            continue;
                        }
                        for (var i = 0; i < 2; i++) {
                            for (var j = 0; j < 2; j++) {
                                mbMaps[base + (i << 1 | j)] = (mby + i) * hfrags[0] + mbx + j;
                            }
                        }
                        switch (setup.pixelFormat) {
                            case 0 -> {
                                var frag = (mby >> 1) * hfrags[1] + (mbx >> 1);
                                mbMaps[base + 4] = frag + fragOffset[1];
                                mbMaps[base + 8] = frag + fragOffset[2];
                            }
                            case 2 -> {
                                var frag = mby * hfrags[1] + (mbx >> 1);
                                for (var i = 0; i < 2; i++) {
                                    mbMaps[base + 4 + (i << 1)] = frag + fragOffset[1];
                                    mbMaps[base + 8 + (i << 1)] = frag + fragOffset[2];
                                    frag += hfrags[1];
                                }
                            }
                            default -> {
                                for (var k = 0; k < 4; k++) {
                                    mbMaps[base + 4 + k] = mbMaps[base + k] + fragOffset[1];
                                    mbMaps[base + 8 + k] = mbMaps[base + k] + fragOffset[2];
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * {@return whether the last decoded frame is a key frame}
     */
    public boolean keyframe() {
        return keyframe;
    }

    /**
     * Decodes one data packet.
     *
     * <p>An empty packet, and an inter frame coding no fragment, repeat the previous frame. An inter frame before
     * the first key frame predicts from mid-gray reference frames.
     *
     * @param data the array holding the packet
     * @param offset the index of the packet's first byte
     * @param length the number of bytes of the packet
     * @return the picture, which the next call may overwrite
     * @throws MediaException.Malformed if the packet is not a data packet
     * @throws MediaException.Unsupported if a key frame sets the reserved header bits
     */
    public YuvPicture decode(byte[] data, int offset, int length) {
        keyframe = false;
        if (length == 0) {
            intraFrame = false;
            codedTotal = 0;
        } else {
            bits.reset(data, offset, length);
            if (bits.bit() != 0) {
                throw new MediaException.Malformed("not a Theora data packet");
            }
            intraFrame = bits.bit() == 0;
            qis[0] = (int) bits.read(6);
            qiCount = 1;
            if (bits.bit() != 0) {
                qis[1] = (int) bits.read(6);
                qiCount = 2;
                if (bits.bit() != 0) {
                    qis[2] = (int) bits.read(6);
                    qiCount = 3;
                }
            }
            if (intraFrame) {
                if (bits.read(3) != 0) {
                    throw new MediaException.Unsupported("theora", "Theora key frame with reserved bits set");
                }
                markAllIntra();
            } else {
                readCodedFlags();
            }
        }
        if (!intraFrame && (gold < 0 || prev < 0)) {
            for (var pli = 0; pli < 3; pli++) {
                Arrays.fill(buffers[0][pli], (byte) 0x80);
            }
            gold = prev = 0;
        }
        if (codedTotal <= 0) {
            if (picture == null) {
                output(prev);
            }
            return picture;
        }
        var self = 0;
        while (self == gold || self == prev) {
            self++;
        }
        if (!intraFrame) {
            readModes();
            readMotionVectors();
        }
        readBlockQis();
        readTokens();
        for (var pli = 0; pli < 3; pli++) {
            predictDc(pli);
        }
        reconstruct(self);
        var limit = setup.loopFilterLimits[qis[0]];
        if (limit != 0) {
            initBounding(limit);
            for (var pli = 0; pli < 3; pli++) {
                loopFilter(buffers[self][pli], pli);
            }
        }
        for (var pli = 0; pli < 3; pli++) {
            fillBorders(buffers[self][pli], pli);
        }
        prev = self;
        if (intraFrame) {
            gold = self;
        }
        keyframe = intraFrame;
        output(self);
        return picture;
    }

    private void markAllIntra() {
        var count = 0;
        for (var pli = 0; pli < 3; pli++) {
            var start = count;
            for (var sbi = sbOffset[pli]; sbi < sbOffset[pli + 1]; sbi++) {
                for (var quad = 0; quad < 4; quad++) {
                    if ((sbQuadValid[sbi] & 1 << quad) == 0) {
                        continue;
                    }
                    for (var bi = 0; bi < 4; bi++) {
                        var frag = sbMaps[sbi * 16 + quad * 4 + bi];
                        if (frag >= 0) {
                            coded[frag] = true;
                            refi[frag] = FRAME_SELF;
                            fragMode[frag] = INTRA;
                            codedIndex[frag] = count;
                            codedFrags[count++] = frag;
                        }
                    }
                }
            }
            codedPerPlane[pli] = count - start;
        }
        codedTotal = count;
    }

    private int readSuperBlockRun() {
        if (bits.bit() == 0) {
            return 1;
        }
        if (bits.bit() == 0) {
            return 2 + bits.bit();
        }
        if (bits.bit() == 0) {
            return 4 + bits.bit();
        }
        if (bits.bit() == 0) {
            return 6 + (int) bits.read(2);
        }
        if (bits.bit() == 0) {
            return 10 + (int) bits.read(3);
        }
        if (bits.bit() == 0) {
            return 18 + (int) bits.read(4);
        }
        return 34 + (int) bits.read(12);
    }

    private int readBlockRun() {
        if (bits.bit() == 0) {
            return 1 + bits.bit();
        }
        if (bits.bit() == 0) {
            return 3 + bits.bit();
        }
        if (bits.bit() == 0) {
            return 5 + bits.bit();
        }
        if (bits.bit() == 0) {
            return 7 + (int) bits.read(2);
        }
        if (bits.bit() == 0) {
            return 11 + (int) bits.read(2);
        }
        return 15 + (int) bits.read(4);
    }

    private void readCodedFlags() {
        var flag = bits.bit() != 0;
        var partial = 0;
        var sbi = 0;
        while (sbi < sbCount) {
            var run = readSuperBlockRun();
            var fullRun = run >= 4129;
            do {
                sbPartial[sbi] = flag;
                sbFull[sbi] = false;
                if (flag) {
                    partial++;
                }
                sbi++;
            } while (--run > 0 && sbi < sbCount);
            flag = fullRun && sbi < sbCount ? bits.bit() != 0 : !flag;
        }
        if (partial < sbCount) {
            sbi = 0;
            while (sbPartial[sbi]) {
                sbi++;
            }
            flag = bits.bit() != 0;
            do {
                var run = readSuperBlockRun();
                var fullRun = run >= 4129;
                for (; sbi < sbCount; sbi++) {
                    if (sbPartial[sbi]) {
                        continue;
                    }
                    if (run-- <= 0) {
                        break;
                    }
                    sbFull[sbi] = flag;
                }
                flag = fullRun && sbi < sbCount ? bits.bit() != 0 : !flag;
            } while (sbi < sbCount);
        }
        var blockFlag = partial > 0 && bits.bit() == 0;
        var run = 0;
        var count = 0;
        for (var pli = 0; pli < 3; pli++) {
            var start = count;
            for (sbi = sbOffset[pli]; sbi < sbOffset[pli + 1]; sbi++) {
                for (var quad = 0; quad < 4; quad++) {
                    if ((sbQuadValid[sbi] & 1 << quad) == 0) {
                        continue;
                    }
                    var quadCoded = false;
                    for (var bi = 0; bi < 4; bi++) {
                        var frag = sbMaps[sbi * 16 + quad * 4 + bi];
                        if (frag < 0) {
                            continue;
                        }
                        boolean isCoded;
                        if (sbFull[sbi]) {
                            isCoded = true;
                        } else if (!sbPartial[sbi]) {
                            isCoded = false;
                        } else {
                            if (run <= 0) {
                                run = readBlockRun();
                                blockFlag = !blockFlag;
                            }
                            run--;
                            isCoded = blockFlag;
                        }
                        if (isCoded) {
                            codedIndex[frag] = count;
                            codedFrags[count++] = frag;
                        }
                        quadCoded |= isCoded;
                        coded[frag] = isCoded;
                        refi[frag] = FRAME_NONE;
                    }
                    if (pli == 0) {
                        mbModes[sbi << 2 | quad] = quadCoded ? 1 : 0;
                    }
                }
            }
            codedPerPlane[pli] = count - start;
        }
        codedTotal = count;
    }

    private void readModes() {
        var scheme = (int) bits.read(3);
        int[] alphabet;
        if (scheme == 0) {
            alphabet = customAlphabet;
            Arrays.fill(alphabet, INTER_NOMV);
            for (var mi = 0; mi < 8; mi++) {
                alphabet[(int) bits.read(3)] = MODE_ALPHABETS[6][mi];
            }
        } else {
            alphabet = MODE_ALPHABETS[scheme - 1];
        }
        for (var mbi = 0; mbi < mbCount; mbi++) {
            if (mbModes[mbi] > 0) {
                int index;
                if (scheme == 7) {
                    index = (int) bits.read(3);
                } else {
                    index = 0;
                    while (index < 7 && bits.bit() != 0) {
                        index++;
                    }
                }
                mbModes[mbi] = alphabet[index];
            }
        }
    }

    private int readMotionComponent(boolean fixed) {
        if (fixed) {
            var value = (int) bits.read(6);
            return (value & 1) != 0 ? -(value >> 1) : value >> 1;
        }
        var prefix = (int) bits.read(3);
        int magnitude;
        switch (prefix) {
            case 0 -> {
                return 0;
            }
            case 1 -> {
                return 1;
            }
            case 2 -> {
                return -1;
            }
            case 3 -> magnitude = 2;
            case 4 -> magnitude = 3;
            case 5 -> magnitude = 4 + (int) bits.read(2);
            case 6 -> magnitude = 8 + ((int) bits.read(2) << 1) + bits.bit();
            default -> magnitude = 16 + ((int) bits.read(2) << 2) + (int) bits.read(2);
        }
        return bits.bit() != 0 ? -magnitude : magnitude;
    }

    private void readMotionVectors() {
        var fixed = bits.bit() != 0;
        var idxs = MB_MAP_IDXS[setup.pixelFormat];
        int lastX = 0, lastY = 0, priorX = 0, priorY = 0;
        for (var mbi = 0; mbi < mbCount; mbi++) {
            var mode = mbModes[mbi];
            if (mode < 0) {
                continue;
            }
            var base = mbi * 12;
            if (mode == INTER_MV_FOUR) {
                priorX = lastX;
                priorY = lastY;
                for (var bi = 0; bi < 4; bi++) {
                    var frag = mbMaps[base + bi];
                    if (coded[frag]) {
                        refi[frag] = FRAME_PREV;
                        fragMode[frag] = INTER_MV_FOUR;
                        lastX = readMotionComponent(fixed);
                        lastY = readMotionComponent(fixed);
                        lumaMvX[bi] = mvX[frag] = lastX;
                        lumaMvY[bi] = mvY[frag] = lastY;
                    } else {
                        lumaMvX[bi] = 0;
                        lumaMvY[bi] = 0;
                    }
                }
                setChromaVectors();
                for (var mapii = 4; mapii < idxs.length; mapii++) {
                    var mapi = idxs[mapii];
                    var bi = mapi & 3;
                    var frag = mbMaps[base + mapi];
                    if (coded[frag]) {
                        refi[frag] = FRAME_PREV;
                        fragMode[frag] = INTER_MV_FOUR;
                        mvX[frag] = chromaMvX[bi];
                        mvY[frag] = chromaMvY[bi];
                    }
                }
                continue;
            }
            int x, y;
            switch (mode) {
                case INTER_MV -> {
                    priorX = lastX;
                    priorY = lastY;
                    x = lastX = readMotionComponent(fixed);
                    y = lastY = readMotionComponent(fixed);
                }
                case INTER_MV_LAST -> {
                    x = lastX;
                    y = lastY;
                }
                case INTER_MV_LAST2 -> {
                    x = priorX;
                    y = priorY;
                    priorX = lastX;
                    priorY = lastY;
                    lastX = x;
                    lastY = y;
                }
                case GOLDEN_MV -> {
                    x = readMotionComponent(fixed);
                    y = readMotionComponent(fixed);
                }
                default -> {
                    x = 0;
                    y = 0;
                }
            }
            var reference = FRAME_FOR_MODE[mode];
            for (var mapi : idxs) {
                var frag = mbMaps[base + mapi];
                if (coded[frag]) {
                    refi[frag] = (byte) reference;
                    fragMode[frag] = (byte) mode;
                    mvX[frag] = x;
                    mvY[frag] = y;
                }
            }
        }
    }

    private static int divRoundPow2(int dividend, int shift, int round) {
        return dividend + (dividend < 0 ? -1 : 0) + round >> shift;
    }

    private void setChromaVectors() {
        switch (setup.pixelFormat) {
            case 0 -> {
                chromaMvX[0] = divRoundPow2(lumaMvX[0] + lumaMvX[1] + lumaMvX[2] + lumaMvX[3], 2, 2);
                chromaMvY[0] = divRoundPow2(lumaMvY[0] + lumaMvY[1] + lumaMvY[2] + lumaMvY[3], 2, 2);
            }
            case 2 -> {
                chromaMvX[0] = divRoundPow2(lumaMvX[0] + lumaMvX[1], 1, 1);
                chromaMvY[0] = divRoundPow2(lumaMvY[0] + lumaMvY[1], 1, 1);
                chromaMvX[2] = divRoundPow2(lumaMvX[2] + lumaMvX[3], 1, 1);
                chromaMvY[2] = divRoundPow2(lumaMvY[2] + lumaMvY[3], 1, 1);
            }
            default -> {
                System.arraycopy(lumaMvX, 0, chromaMvX, 0, 4);
                System.arraycopy(lumaMvY, 0, chromaMvY, 0, 4);
            }
        }
    }

    private void readBlockQis() {
        if (qiCount == 1) {
            for (var i = 0; i < codedTotal; i++) {
                qii[codedFrags[i]] = 0;
            }
            return;
        }
        var flag = bits.bit();
        var nonZero = 0;
        var i = 0;
        while (i < codedTotal) {
            var run = readSuperBlockRun();
            var fullRun = run >= 4129;
            do {
                qii[codedFrags[i++]] = (byte) flag;
                nonZero += flag;
            } while (--run > 0 && i < codedTotal);
            flag = fullRun && i < codedTotal ? bits.bit() : 1 - flag;
        }
        if (qiCount == 3 && nonZero > 0) {
            i = 0;
            while (qii[codedFrags[i]] == 0) {
                i++;
            }
            flag = bits.bit();
            do {
                var run = readSuperBlockRun();
                var fullRun = run >= 4129;
                for (; i < codedTotal; i++) {
                    var frag = codedFrags[i];
                    if (qii[frag] == 0) {
                        continue;
                    }
                    if (run-- <= 0) {
                        break;
                    }
                    qii[frag] += (byte) flag;
                }
                flag = fullRun && i < codedTotal ? bits.bit() : 1 - flag;
            } while (i < codedTotal);
        }
    }

    /**
     * Reads the DCT tokens, coefficient by coefficient and plane by plane, into the quantized coefficients of each
     * coded fragment, in zig-zag order.
     *
     * <p>{@link #tokenStart} holds the next coefficient each fragment waits for, 64 once its block is complete, and
     * {@link #lastStart} the coefficient its last token started at, which selects the DC-only reconstruction.
     */
    private void readTokens() {
        Arrays.fill(coefficients, 0, codedTotal * 64, (short) 0);
        Arrays.fill(tokenStart, 0, codedTotal, (byte) 0);
        var eobRun = 0L;
        var lumaTable = 0;
        var chromaTable = 0;
        for (var zzi = 0; zzi < 64; zzi++) {
            if (zzi < 2) {
                lumaTable = (int) bits.read(4);
                chromaTable = (int) bits.read(4);
            }
            var group = zzi == 0 ? 0 : zzi < 6 ? 16 : zzi < 15 ? 32 : zzi < 28 ? 48 : 64;
            var start = 0;
            for (var pli = 0; pli < 3; pli++) {
                var table = setup.huffman[(pli == 0 ? lumaTable : chromaTable) + group];
                var end = start + codedPerPlane[pli];
                for (var ci = start; ci < end; ci++) {
                    if (tokenStart[ci] != zzi) {
                        continue;
                    }
                    lastStart[ci] = (byte) zzi;
                    if (eobRun > 0) {
                        eobRun--;
                        tokenStart[ci] = 64;
                        continue;
                    }
                    var token = table.decode(bits);
                    var base = ci * 64;
                    var next = zzi + 1;
                    switch (token) {
                        case 0, 1, 2 -> {
                            eobRun = token;
                            next = 64;
                        }
                        case 3 -> {
                            eobRun = 3 + bits.read(2);
                            next = 64;
                        }
                        case 4 -> {
                            eobRun = 7 + bits.read(3);
                            next = 64;
                        }
                        case 5 -> {
                            eobRun = 15 + bits.read(4);
                            next = 64;
                        }
                        case 6 -> {
                            var run = bits.read(12);
                            eobRun = run == 0 ? EOB_FINISH : run - 1;
                            next = 64;
                        }
                        case 7 -> next = zzi + 1 + (int) bits.read(3);
                        case 8 -> next = zzi + 1 + (int) bits.read(6);
                        case 9 -> coefficients[base + zzi] = 1;
                        case 10 -> coefficients[base + zzi] = -1;
                        case 11 -> coefficients[base + zzi] = 2;
                        case 12 -> coefficients[base + zzi] = -2;
                        case 13, 14, 15, 16 -> coefficients[base + zzi] = (short) signed(token - 10, bits.bit());
                        default -> next = valueToken(token, zzi, base);
                    }
                    tokenStart[ci] = (byte) Math.min(next, 64);
                }
                start = end;
            }
        }
        for (var ci = 0; ci < codedTotal; ci++) {
            dc[codedFrags[ci]] = coefficients[ci * 64];
        }
    }

    private static int signed(int magnitude, int sign) {
        return sign != 0 ? -magnitude : magnitude;
    }

    private int valueToken(int token, int zzi, int base) {
        int run;
        int value;
        switch (token) {
            case 17 -> {
                var sign = bits.bit();
                value = signed(7 + bits.bit(), sign);
                run = 0;
            }
            case 18 -> {
                var sign = bits.bit();
                value = signed(9 + (int) bits.read(2), sign);
                run = 0;
            }
            case 19 -> {
                var sign = bits.bit();
                value = signed(13 + (int) bits.read(3), sign);
                run = 0;
            }
            case 20 -> {
                var sign = bits.bit();
                value = signed(21 + (int) bits.read(4), sign);
                run = 0;
            }
            case 21 -> {
                var sign = bits.bit();
                value = signed(37 + (int) bits.read(5), sign);
                run = 0;
            }
            case 22 -> {
                var sign = bits.bit();
                value = signed(69 + (int) bits.read(9), sign);
                run = 0;
            }
            case 23, 24, 25, 26, 27 -> {
                value = signed(1, bits.bit());
                run = token - 22;
            }
            case 28 -> {
                var sign = bits.bit();
                value = signed(1, sign);
                run = 6 + (int) bits.read(2);
            }
            case 29 -> {
                var sign = bits.bit();
                value = signed(1, sign);
                run = 10 + (int) bits.read(3);
            }
            case 30 -> {
                var sign = bits.bit();
                value = signed(2 + bits.bit(), sign);
                run = 1;
            }
            default -> {
                var sign = bits.bit();
                var magnitude = bits.bit();
                value = signed(2 + magnitude, sign);
                run = 2 + bits.bit();
            }
        }
        var position = zzi + run;
        if (position < 64) {
            coefficients[base + position] = (short) value;
        }
        return position + 1;
    }

    private void predictDc(int pli) {
        Arrays.fill(predLast, 0);
        var h = hfrags[pli];
        var frag = fragOffset[pli];
        for (var y = 0; y < vfrags[pli]; y++) {
            if (y == 0) {
                for (var x = 0; x < h; x++, frag++) {
                    if (coded[frag]) {
                        var r = refi[frag];
                        predLast[r] = dc[frag] = (short) (dc[frag] + predLast[r]);
                    }
                }
                continue;
            }
            var lRef = -1;
            var ulRef = -1;
            var uRef = (int) refi[frag - h];
            for (var x = 0; x < h; x++, frag++) {
                var urRef = x + 1 >= h ? -1 : refi[frag - h + 1];
                if (coded[frag]) {
                    var r = (int) refi[frag];
                    var u = frag - h;
                    int pred;
                    switch ((lRef == r ? 1 : 0) | (ulRef == r ? 2 : 0) | (uRef == r ? 4 : 0) | (urRef == r ? 8 : 0)) {
                        case 1, 3 -> pred = dc[frag - 1];
                        case 2 -> pred = dc[u - 1];
                        case 4, 6, 12 -> pred = dc[u];
                        case 5 -> pred = (dc[frag - 1] + dc[u]) / 2;
                        case 8 -> pred = dc[u + 1];
                        case 9, 11, 13 -> pred = (75 * dc[frag - 1] + 53 * dc[u + 1]) / 128;
                        case 10 -> pred = (dc[u - 1] + dc[u + 1]) / 2;
                        case 14 -> pred = (3 * (dc[u - 1] + dc[u + 1]) + 10 * dc[u]) / 16;
                        case 7, 15 -> {
                            var p0 = dc[frag - 1];
                            var p1 = dc[u - 1];
                            var p2 = dc[u];
                            pred = (29 * (p0 + p2) - 26 * p1) / 32;
                            if (Math.abs(pred - p2) > 128) {
                                pred = p2;
                            } else if (Math.abs(pred - p0) > 128) {
                                pred = p0;
                            } else if (Math.abs(pred - p1) > 128) {
                                pred = p1;
                            }
                        }
                        default -> pred = predLast[r];
                    }
                    predLast[r] = dc[frag] = (short) (dc[frag] + pred);
                    lRef = r;
                } else {
                    lRef = -1;
                }
                ulRef = uRef;
                uRef = urRef;
            }
        }
    }

    private int planeOf(int frag) {
        return frag < fragOffset[1] ? 0 : frag < fragOffset[2] ? 1 : 2;
    }

    private int bufferOffset(int frag, int pli) {
        var index = frag - fragOffset[pli];
        var h = hfrags[pli];
        return origin[pli] + ((index / h) << 3) * stride[pli] + ((index % h) << 3);
    }

    private void reconstruct(int self) {
        for (var ci = 0; ci < codedTotal; ci++) {
            var frag = codedFrags[ci];
            var pli = planeOf(frag);
            var qti = fragMode[frag] != INTRA ? 1 : 0;
            var acQuant = setup.dequant[qis[qii[frag]]][pli][qti];
            var dcQuant = setup.dequant[qis[0]][pli][qti][0];
            if (lastStart[ci] < 2) {
                var p = (short) (dc[frag] * dcQuant + 15 >> 5);
                Arrays.fill(block, p);
            } else {
                Arrays.fill(work, 0);
                work[0] = (short) (dc[frag] * dcQuant);
                var base = ci * 64;
                for (var zzi = 1; zzi < 64; zzi++) {
                    var value = coefficients[base + zzi];
                    if (value != 0) {
                        work[TheoraSetup.ZIG_ZAG[zzi]] = (short) (value * acQuant[zzi]);
                    }
                }
                idct(work, block);
            }
            var s = stride[pli];
            var offset = bufferOffset(frag, pli);
            var dst = buffers[self][pli];
            var r = refi[frag];
            if (r == FRAME_SELF) {
                for (var i = 0; i < 8; i++) {
                    var row = offset + i * s;
                    for (var j = 0; j < 8; j++) {
                        dst[row + j] = clamp255(block[i * 8 + j] + 128);
                    }
                }
                continue;
            }
            var ref = buffers[r == FRAME_GOLD ? gold : prev][pli];
            var quarterY = pli != 0 && shiftY != 0 ? 1 : 0;
            var quarterX = pli != 0 && shiftX != 0 ? 1 : 0;
            var dx = mvX[frag] + 31;
            var dy = mvY[frag] + 31;
            var mx = MVMAP[quarterX][dx];
            var my = MVMAP[quarterY][dy];
            var mx2 = MVMAP2[quarterX][dx];
            var my2 = MVMAP2[quarterY][dy];
            var first = offset + my * s + mx;
            if (mx2 != 0 || my2 != 0) {
                var second = first + my2 * s + mx2;
                for (var i = 0; i < 8; i++) {
                    var row = i * s;
                    for (var j = 0; j < 8; j++) {
                        var average = (ref[first + row + j] & 0xFF) + (ref[second + row + j] & 0xFF) >> 1;
                        dst[offset + row + j] = clamp255(block[i * 8 + j] + average);
                    }
                }
            } else {
                for (var i = 0; i < 8; i++) {
                    var row = i * s;
                    for (var j = 0; j < 8; j++) {
                        dst[offset + row + j] = clamp255(block[i * 8 + j] + (ref[first + row + j] & 0xFF));
                    }
                }
            }
        }
        if (!intraFrame) {
            for (var pli = 0; pli < 3; pli++) {
                var src = buffers[prev][pli];
                var dst = buffers[self][pli];
                var s = stride[pli];
                for (var frag = fragOffset[pli]; frag < fragOffset[pli + 1]; frag++) {
                    if (coded[frag]) {
                        continue;
                    }
                    var offset = bufferOffset(frag, pli);
                    for (var i = 0; i < 8; i++) {
                        System.arraycopy(src, offset + i * s, dst, offset + i * s, 8);
                    }
                }
            }
        }
    }

    private static byte clamp255(int value) {
        return (byte) (value < 0 ? 0 : value > 255 ? 255 : value);
    }

    /**
     * Transforms the rows of {@code x} into the columns of a temporary block and those into the columns of
     * {@code y}, the exact integer inverse DCT of the specification, then scales by 1/16 with rounding.
     */
    private void idct(int[] x, int[] y) {
        var w = transpose;
        for (var i = 0; i < 8; i++) {
            idct8(x, i * 8, w, i);
        }
        for (var i = 0; i < 8; i++) {
            idct8(w, i * 8, y, i);
        }
        for (var i = 0; i < 64; i++) {
            y[i] = (short) (y[i] + 8 >> 4);
        }
    }

    private static void idct8(int[] x, int xo, int[] y, int yo) {
        var x0 = x[xo];
        var x1 = x[xo + 1];
        var x2 = x[xo + 2];
        var x3 = x[xo + 3];
        var x4 = x[xo + 4];
        var x5 = x[xo + 5];
        var x6 = x[xo + 6];
        var x7 = x[xo + 7];
        var t0 = C4S4 * (short) (x0 + x4) >> 16;
        var t1 = C4S4 * (short) (x0 - x4) >> 16;
        var t2 = (C6S2 * x2 >> 16) - (C2S6 * x6 >> 16);
        var t3 = (C2S6 * x2 >> 16) + (C6S2 * x6 >> 16);
        var t4 = (C7S1 * x1 >> 16) - (C1S7 * x7 >> 16);
        var t5 = (C3S5 * x5 >> 16) - (C5S3 * x3 >> 16);
        var t6 = (C5S3 * x5 >> 16) + (C3S5 * x3 >> 16);
        var t7 = (C1S7 * x1 >> 16) + (C7S1 * x7 >> 16);
        var r = t4 + t5;
        t5 = C4S4 * (short) (t4 - t5) >> 16;
        t4 = r;
        r = t7 + t6;
        t6 = C4S4 * (short) (t7 - t6) >> 16;
        t7 = r;
        r = t0 + t3;
        t3 = t0 - t3;
        t0 = r;
        r = t1 + t2;
        t2 = t1 - t2;
        t1 = r;
        r = t6 + t5;
        t5 = t6 - t5;
        t6 = r;
        y[yo] = (short) (t0 + t7);
        y[yo + 8] = (short) (t1 + t6);
        y[yo + 16] = (short) (t2 + t5);
        y[yo + 24] = (short) (t3 + t4);
        y[yo + 32] = (short) (t3 - t4);
        y[yo + 40] = (short) (t2 - t5);
        y[yo + 48] = (short) (t1 - t6);
        y[yo + 56] = (short) (t0 - t7);
    }

    private void initBounding(int limit) {
        Arrays.fill(bounding, 0);
        for (var i = 0; i < limit; i++) {
            if (127 - i - limit >= 0) {
                bounding[127 - i - limit] = i - limit;
            }
            bounding[127 - i] = -i;
            bounding[127 + i] = i;
            if (127 + i + limit < 256) {
                bounding[127 + i + limit] = limit - i;
            }
        }
    }

    private void filterHorizontal(byte[] pixels, int position, int s) {
        var p = position - 2;
        for (var y = 0; y < 8; y++, p += s) {
            var f = (pixels[p] & 0xFF) - (pixels[p + 3] & 0xFF)
                    + 3 * ((pixels[p + 2] & 0xFF) - (pixels[p + 1] & 0xFF));
            f = bounding[127 + (f + 4 >> 3)];
            pixels[p + 1] = clamp255((pixels[p + 1] & 0xFF) + f);
            pixels[p + 2] = clamp255((pixels[p + 2] & 0xFF) - f);
        }
    }

    private void filterVertical(byte[] pixels, int position, int s) {
        var p = position - 2 * s;
        for (var x = 0; x < 8; x++) {
            var f = (pixels[p + x] & 0xFF) - (pixels[p + 3 * s + x] & 0xFF)
                    + 3 * ((pixels[p + 2 * s + x] & 0xFF) - (pixels[p + s + x] & 0xFF));
            f = bounding[127 + (f + 4 >> 3)];
            pixels[p + s + x] = clamp255((pixels[p + s + x] & 0xFF) + f);
            pixels[p + 2 * s + x] = clamp255((pixels[p + 2 * s + x] & 0xFF) - f);
        }
    }

    private void loopFilter(byte[] pixels, int pli) {
        var h = hfrags[pli];
        var s = stride[pli];
        var top = fragOffset[pli];
        var bottom = fragOffset[pli + 1];
        for (var row = top; row < bottom; row += h) {
            var rowEnd = row + h;
            for (var frag = row; frag < rowEnd; frag++) {
                if (!coded[frag]) {
                    continue;
                }
                var position = bufferOffset(frag, pli);
                if (frag > row) {
                    filterHorizontal(pixels, position, s);
                }
                if (row > top) {
                    filterVertical(pixels, position, s);
                }
                if (frag + 1 < rowEnd && !coded[frag + 1]) {
                    filterHorizontal(pixels, position + 8, s);
                }
                if (frag + h < bottom && !coded[frag + h]) {
                    filterVertical(pixels, position + 8 * s, s);
                }
            }
        }
    }

    private void fillBorders(byte[] pixels, int pli) {
        var s = stride[pli];
        var width = hfrags[pli] << 3;
        var height = vfrags[pli] << 3;
        var px = padX[pli];
        var py = padY[pli];
        for (var y = 0; y < height; y++) {
            var left = origin[pli] + y * s;
            var right = left + width - 1;
            Arrays.fill(pixels, left - px, left, pixels[left]);
            Arrays.fill(pixels, right + 1, right + 1 + px, pixels[right]);
        }
        var first = origin[pli] - px;
        var last = first + (height - 1) * s;
        for (var y = 1; y <= py; y++) {
            System.arraycopy(pixels, first, pixels, first - y * s, s);
            System.arraycopy(pixels, last, pixels, last + y * s, s);
        }
    }

    private void output(int buffer) {
        var width = setup.pictureWidth;
        var height = setup.pictureHeight;
        var format = switch (setup.pixelFormat) {
            case 0 -> ChromaFormat.YUV420;
            case 2 -> ChromaFormat.YUV422;
            default -> ChromaFormat.YUV444;
        };
        if (picture == null) {
            picture = YuvPicture.allocate(width, height, format, false, color);
        }
        copyPlane(buffers[buffer][0], 0, picture.y(), picture.yStride(), width, height, setup.pictureX,
                setup.pictureY);
        var cw = picture.chromaWidth();
        var ch = picture.chromaHeight();
        copyPlane(buffers[buffer][1], 1, picture.u(), picture.uvStride(), cw, ch, setup.pictureX >> shiftX,
                setup.pictureY >> shiftY);
        copyPlane(buffers[buffer][2], 2, picture.v(), picture.uvStride(), cw, ch, setup.pictureX >> shiftX,
                setup.pictureY >> shiftY);
    }

    private void copyPlane(byte[] source, int pli, byte[] target, int targetStride, int width, int height, int x,
                           int y) {
        var rows = vfrags[pli] << 3;
        var s = stride[pli];
        for (var row = 0; row < height; row++) {
            var sourceRow = rows - 1 - (y + row);
            System.arraycopy(source, origin[pli] + sourceRow * s + x, target, row * targetStride, width);
        }
    }
}
