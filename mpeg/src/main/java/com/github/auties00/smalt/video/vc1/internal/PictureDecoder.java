package com.github.auties00.smalt.video.vc1.internal;

import com.github.auties00.smalt.MediaException;

/**
 * Decodes the macroblock layer of VC-1 pictures (SMPTE ST 421 sections 7 to 10): I, BI, P and B pictures of the
 * simple, main and advanced profiles, progressive, interlace frame and interlace field.
 *
 * <p>The caller parses the picture header up to the profile specific part, then calls {@link #begin} (or
 * {@link #beginField} for each field of a field picture), which reads the rest of the header and the bitplanes, then
 * {@link #decodeRows} for the picture data and each slice, then {@link #finish}, which applies overlap smoothing and
 * the loop filter and buffers the motion vectors of anchors for direct mode. Instances are not thread-safe.
 */
final class PictureDecoder {
    /**
     * DQPROFILE: the alternate quantizer on all four picture edges.
     */
    private static final int DQ_ALL_EDGES = 0;

    /**
     * DQPROFILE: the alternate quantizer on two adjacent picture edges.
     */
    private static final int DQ_DOUBLE_EDGE = 1;

    /**
     * DQPROFILE: the alternate quantizer on one picture edge.
     */
    private static final int DQ_SINGLE_EDGE = 2;

    /**
     * DQPROFILE: a quantizer coded per macroblock.
     */
    private static final int DQ_ALL_MACROBLOCKS = 3;

    /**
     * The left picture edge, a bit of the edge masks.
     */
    private static final int EDGE_LEFT = 1;

    /**
     * The top picture edge.
     */
    private static final int EDGE_TOP = 2;

    /**
     * The right picture edge.
     */
    private static final int EDGE_RIGHT = 4;

    /**
     * The bottom picture edge.
     */
    private static final int EDGE_BOTTOM = 8;

    /**
     * The bit counts of the motion vector differential components of Figure 50, by component index.
     */
    private static final int[] SIZE_TABLE = {0, 2, 3, 4, 5, 8};

    /**
     * The offsets of the motion vector differential components of Figure 50, by component index.
     */
    private static final int[] OFFSET_TABLE = {0, 1, 3, 7, 15, 31};

    /**
     * The bits of an escaped horizontal motion vector differential, by MVRANGE (Table 75).
     */
    private static final int[] K_X = {9, 10, 12, 13};

    /**
     * The bits of an escaped vertical motion vector differential, by MVRANGE.
     */
    private static final int[] K_Y = {8, 9, 10, 11};

    /**
     * The horizontal motion vector range in quarter samples, by MVRANGE.
     */
    private static final int[] RANGE_X = {256, 512, 2048, 4096};

    /**
     * The vertical motion vector range in quarter samples, by MVRANGE.
     */
    private static final int[] RANGE_Y = {128, 256, 512, 1024};

    /**
     * The reader of the picture, field and slice data.
     */
    final Bits bits = new Bits();

    /**
     * The ACPRED bitplane.
     */
    final Bitplane acPredPlane = new Bitplane();

    /**
     * The OVERFLAGS bitplane.
     */
    final Bitplane overflagPlane = new Bitplane();

    /**
     * The MVTYPEMB bitplane.
     */
    final Bitplane mvTypePlane = new Bitplane();

    /**
     * The SKIPMB bitplane.
     */
    final Bitplane skipPlane = new Bitplane();

    /**
     * The DIRECTMB bitplane (FORWARDMB in B field pictures).
     */
    final Bitplane directPlane = new Bitplane();

    /**
     * The state of the luma blocks.
     */
    private final BlockState luma = new BlockState();

    /**
     * The state of the blue-difference and red-difference blocks.
     */
    private final BlockState[] chroma = {new BlockState(), new BlockState()};

    /**
     * The motion compensation interpolator.
     */
    private final MotionCompensation mc = new MotionCompensation();

    /**
     * The in-loop deblocking filter.
     */
    private final LoopFilter loopFilter = new LoopFilter();

    /**
     * The sequence of the picture.
     */
    private SequenceHeader sequence;

    /**
     * The entry point of the picture.
     */
    private EntryPoint entry;

    /**
     * The header of the picture.
     */
    private PictureHeader header;

    /**
     * The frame decoded into.
     */
    private Frame current;

    /**
     * The forward reference frame, or {@code null}.
     */
    private Frame forward;

    /**
     * The backward reference frame of a B picture, or {@code null}.
     */
    private Frame backward;

    /**
     * Whether the stream is in the advanced profile.
     */
    private boolean advanced;

    /**
     * The macroblock columns of the picture.
     */
    int mbWidth;

    /**
     * The macroblock rows of the picture (of the field, for a field picture).
     */
    int mbHeight;

    /**
     * The picture type.
     */
    private int type;

    /**
     * The picture quantizer.
     */
    private int pquant;

    /**
     * The motion vector mode of a P picture, a {@code Vc1Codes.MV_*} value.
     */
    private int mvMode;

    /**
     * Whether the picture applies intensity compensation.
     */
    private boolean intensity;

    /**
     * LUMSCALE.
     */
    private int lumScale;

    /**
     * LUMSHIFT.
     */
    private int lumShift;

    /**
     * The MVDATA table of a progressive picture.
     */
    private Vlc mvVlc;

    /**
     * The CBPCY table of a progressive P or B picture.
     */
    private Vlc cbpVlc;

    /**
     * TTMBF: whether one transform type serves the whole picture.
     */
    private boolean ttmbf;

    /**
     * TTFRM, the transform type of the picture when {@link #ttmbf} is set.
     */
    private int ttfrm;

    /**
     * The coding set of luma intra blocks.
     */
    private AcCodingSet lumaIntraSet;

    /**
     * The coding set of color-difference intra blocks.
     */
    private AcCodingSet chromaIntraSet;

    /**
     * The coding set of inter blocks.
     */
    private AcCodingSet interSet;

    /**
     * TRANSDCTAB: 0 for the low motion DC tables, 1 for the high motion ones.
     */
    private int dcTable;

    /**
     * DQUANTFRM: whether macroblock quantizers differ from the picture quantizer.
     */
    private boolean dquantFrame;

    /**
     * DQPROFILE.
     */
    private int dqProfile;

    /**
     * The picture edges whose macroblocks take the alternate quantizer, a mask of {@code EDGE_*} bits.
     */
    private int dqEdges;

    /**
     * DQBILEVEL: whether a macroblock quantizer is the picture quantizer or the alternate one.
     */
    private boolean dqBilevel;

    /**
     * The alternate quantizer.
     */
    private int altPquant;

    /**
     * CONDOVER: 0 for no overlap smoothing, 1 for every macroblock, 2 for the macroblocks OVERFLAGS selects.
     */
    private int condover;

    /**
     * Whether overlap smoothing runs on the picture.
     */
    private boolean overlapActive;

    /**
     * The bits of an escaped horizontal motion vector differential.
     */
    private int kx;

    /**
     * The bits of an escaped vertical motion vector differential.
     */
    private int ky;

    /**
     * The horizontal motion vector range.
     */
    private int rangeX;

    /**
     * The vertical motion vector range.
     */
    private int rangeY;

    /**
     * Whether motion vectors are in half samples.
     */
    private boolean halfpel;

    /**
     * The luma interpolation filter.
     */
    private int filter;

    /**
     * The rounding control.
     */
    private int rnd;

    /**
     * Whether the next escape mode 3 coefficient is the first of the slice, which carries the escape sizes.
     */
    private boolean firstMode3;

    /**
     * The level size of escape mode 3.
     */
    private int escapeLevelSize;

    /**
     * The run size of escape mode 3.
     */
    private int escapeRunSize;

    /**
     * The value added to the intra residuals: 128, or 0 in simple and main profile I pictures without overlap
     * smoothing, whose DC already includes it.
     */
    private int intraOffset;

    /**
     * The intra block residuals of each plane, kept for overlap smoothing until the picture is complete.
     */
    private short[][] residual = new short[3][0];

    /**
     * Whether overlap smoothing applies to each macroblock.
     */
    private boolean[] overflag = new boolean[0];

    /**
     * The first macroblock row of the slice of each row.
     */
    private int[] sliceTop = new int[0];

    /**
     * The forward motion vector of each macroblock of a progressive B picture.
     */
    private int[] forwardMv = new int[0];

    /**
     * The backward motion vector of each macroblock of a progressive B picture.
     */
    private int[] backwardMv = new int[0];

    /**
     * Whether each macroblock is intra coded.
     */
    private boolean[] mbIntra = new boolean[0];

    /**
     * The color-difference motion vector of each macroblock.
     */
    private int[] chromaMv = new int[0];

    /**
     * The color-difference motion vector of each macroblock as the loop filter compares it.
     */
    private int[] chromaMvFilter = new int[0];

    /**
     * Whether each macroblock is a 4-MV macroblock.
     */
    private boolean[] mbFourMv = new boolean[0];

    /**
     * The coefficients of the block being decoded.
     */
    private final int[] coefficients = new int[64];

    /**
     * Scratch space of the inverse transform.
     */
    private final int[] temp = new int[64];

    /**
     * The luma prediction of the macroblock, 16x16.
     */
    private final int[] prediction = new int[16 * 16];

    /**
     * The color-difference prediction of the macroblock: the two 8x8 blocks side by side in rows of 16.
     */
    private final int[] prediction2 = new int[16 * 16];

    /**
     * The motion vectors of the four blocks of a 4-MV macroblock.
     */
    private final int[] blockMv = new int[4];

    /**
     * Whether each block of the macroblock is intra coded.
     */
    private final boolean[] blockIntra = new boolean[6];

    /**
     * Whether each block of the macroblock has coefficients.
     */
    private final boolean[] blockCoded = new boolean[6];

    /**
     * The motion vector predictor last computed, horizontal then vertical.
     */
    private final int[] predictor = new int[2];

    /**
     * The luma intensity compensation tables of the picture: one, or one per reference field.
     */
    private final byte[][] icLuma = {new byte[256], new byte[256]};

    /**
     * The color-difference intensity compensation tables matching {@link #icLuma}.
     */
    private final byte[][] icChroma = {new byte[256], new byte[256]};

    /**
     * The macroblock quantizer.
     */
    private int mquant;

    /**
     * Whether the macroblock quantizer is the picture quantizer, the condition of HALFQP.
     */
    private boolean mquantIsPquant;

    /**
     * ACPRED of the macroblock.
     */
    private boolean acPred;

    /**
     * The damage of the last picture.
     */
    private String damage;

    /**
     * {@return the damage of the last picture, or {@code null} if it decoded cleanly}
     */
    String damage() {
        return damage;
    }

    /**
     * Reads the rest of the picture header and its bitplanes, and prepares the macroblock state.
     *
     * @param sequence the sequence header in force
     * @param entry the entry point controls
     * @param header the parsed start of the header
     * @param current the frame to decode into
     * @param forward the previous anchor frame, or {@code null}
     * @param backward the next anchor frame of a B picture, or {@code null}
     * @param mbWidth the macroblock columns
     * @param mbHeight the macroblock rows
     * @param rnd the rounding control
     * @throws MediaException.Malformed if the header is invalid
     */
    void begin(SequenceHeader sequence, EntryPoint entry, PictureHeader header, Frame current, Frame forward,
               Frame backward, int mbWidth, int mbHeight, int rnd) {
        this.sequence = sequence;
        this.entry = entry;
        this.header = header;
        this.current = current;
        this.forward = forward;
        this.backward = backward;
        this.advanced = sequence.advanced();
        this.mbWidth = mbWidth;
        this.mbHeight = mbHeight;
        this.type = header.type;
        this.pquant = header.pquant;
        this.rnd = rnd;
        this.damage = null;
        this.fieldParity = -1;
        this.secondField = false;
        lumaBase = 0;
        lumaPitch = current.width;
        chromaBase = 0;
        chromaPitch = current.chromaWidth;
        frameInterlace = header.frameCodingMode == PictureHeader.FRAME_INTERLACE;
        interlaceScans = frameInterlace;
        twoReferences = false;
        allocate();
        if (frameInterlace) {
            prepareFrameInterlace();
            parseFrameInterlaceHeader(true);
            if (bits.overrun()) {
                throw new MediaException.Malformed("truncated VC-1 picture header");
            }
        } else {
            parseRest(true);
        }
        startSlice(0);
    }

    /**
     * Reads a repeated picture header of a slice, which carries the values of the picture's header.
     *
     * @param repeat the parsed start of the repeated header
     * @throws MediaException.Malformed if the header is invalid
     */
    void skipRepeatedHeader(PictureHeader repeat) {
        var saved = header;
        header = repeat;
        try {
            if (frameInterlace) {
                parseFrameInterlaceHeader(false);
            } else {
                parseRest(false);
            }
        } finally {
            header = saved;
        }
    }

    /**
     * Reads the rest of a progressive picture header: the bitplanes, the tables and the quantizer profile, and sets up
     * the motion vector mode.
     *
     * @param apply whether to apply intensity compensation to the reference, false for a repeated header
     * @throws MediaException.Malformed if the header is invalid
     */
    private void parseRest(boolean apply) {
        dquantFrame = false;
        condover = 0;
        intensity = false;
        ttmbf = true;
        ttfrm = Vc1Codes.TT_8X8;
        var mvRange = header.mvRange;
        if (type == PictureHeader.I || type == PictureHeader.BI) {
            if (advanced) {
                acPredPlane.decode(bits, mbWidth, mbHeight);
                overflagPlane.clear(mbWidth, mbHeight);
                if (entry.overlap && pquant <= 8) {
                    condover = bits.readBit() == 0 ? 0 : bits.readBit() == 0 ? 1 : 2;
                    if (condover == 2) {
                        overflagPlane.decode(bits, mbWidth, mbHeight);
                    }
                }
            }
            var chromaIndex = transformAcIndex();
            var lumaIndex = transformAcIndex();
            lumaIntraSet = Vc1Codes.intraSet(lumaIndex, header.pqindex);
            chromaIntraSet = Vc1Codes.interSet(chromaIndex, header.pqindex);
            dcTable = bits.readBit();
            if (advanced && entry.dquant != 0) {
                parseVopDquant();
            }
            if (advanced) {
                overlapActive = entry.overlap && (pquant >= 9 || condover != 0);
                intraOffset = 128;
            } else {
                overlapActive = entry.overlap && pquant >= 9;
                intraOffset = overlapActive ? 128 : 0;
            }
        } else {
            if (advanced && entry.extendedMv) {
                mvRange = Vc1Codes.MV_RANGE.decode(bits);
                header.mvRange = mvRange;
            }
            if (type == PictureHeader.P) {
                var low = pquant > 12;
                mvMode = (low ? Vc1Codes.MV_MODE_LOW_RATE : Vc1Codes.MV_MODE_HIGH_RATE).decode(bits);
                if (mvMode == Vc1Codes.MV_INTENSITY) {
                    if (sequence.profile == SequenceHeader.SIMPLE) {
                        throw new MediaException.Malformed("VC-1 intensity compensation in the simple profile");
                    }
                    mvMode = (low ? Vc1Codes.MV_MODE2_LOW_RATE : Vc1Codes.MV_MODE2_HIGH_RATE).decode(bits);
                    intensity = true;
                    lumScale = bits.read(6);
                    lumShift = bits.read(6);
                }
                if (mvMode < 0) {
                    throw new MediaException.Malformed("invalid VC-1 MVMODE");
                }
                if (mvMode == Vc1Codes.MV_MIXED) {
                    mvTypePlane.decode(bits, mbWidth, mbHeight);
                } else {
                    mvTypePlane.clear(mbWidth, mbHeight);
                }
            } else {
                mvMode = bits.readBit() == 1 ? Vc1Codes.MV_1 : Vc1Codes.MV_1_HALF_BILINEAR;
                directPlane.decode(bits, mbWidth, mbHeight);
            }
            skipPlane.decode(bits, mbWidth, mbHeight);
            mvVlc = Vc1Codes.MV_DIFF[bits.read(2)];
            cbpVlc = Vc1Codes.P_CBPCY[bits.read(2)];
            if (entry.dquant != 0) {
                parseVopDquant();
            }
            if (entry.variableTransform) {
                ttmbf = bits.readFlag();
                if (ttmbf) {
                    ttfrm = bits.read(2);
                }
            }
            var index = transformAcIndex();
            lumaIntraSet = Vc1Codes.intraSet(index, header.pqindex);
            chromaIntraSet = Vc1Codes.interSet(index, header.pqindex);
            interSet = chromaIntraSet;
            dcTable = bits.readBit();
            overlapActive = type == PictureHeader.P && entry.overlap && pquant >= 9;
            intraOffset = 128;
            halfpel = mvMode == Vc1Codes.MV_1_HALF || mvMode == Vc1Codes.MV_1_HALF_BILINEAR;
            filter = mvMode == Vc1Codes.MV_1_HALF_BILINEAR ? MotionCompensation.BILINEAR : MotionCompensation.BICUBIC;
            if (mvRange < 0) {
                throw new MediaException.Malformed("invalid VC-1 MVRANGE");
            }
            kx = K_X[mvRange];
            ky = K_Y[mvRange];
            rangeX = RANGE_X[mvRange];
            rangeY = RANGE_Y[mvRange];
            if (apply && type == PictureHeader.P && intensity) {
                applyIntensityCompensation(forward);
            }
        }
        if (bits.overrun()) {
            throw new MediaException.Malformed("truncated VC-1 picture header");
        }
    }

    /**
     * Reads a TRANSACFRM or TRANSACFRM2 index.
     *
     * @return the index, 0 to 2
     * @throws MediaException.Malformed if the code is invalid
     */
    private int transformAcIndex() {
        var index = Vc1Codes.TRANSFORM_AC_INDEX.decode(bits);
        if (index < 0) {
            throw new MediaException.Malformed("invalid VC-1 TRANSACFRM");
        }
        return index;
    }

    /**
     * Reads VOPDQUANT (Table 24): the quantizer profile, its edges and the alternate quantizer.
     *
     * @throws MediaException.Malformed if the alternate quantizer is out of range
     */
    private void parseVopDquant() {
        if (entry.dquant == 2) {
            dquantFrame = true;
            dqProfile = DQ_ALL_EDGES;
            var pqdiff = bits.read(3);
            altPquant = pqdiff == 7 ? bits.read(5) : pquant + pqdiff + 1;
        } else {
            dquantFrame = bits.readFlag();
            if (!dquantFrame) {
                return;
            }
            dqProfile = bits.read(2);
            dqBilevel = false;
            switch (dqProfile) {
                case DQ_SINGLE_EDGE -> dqEdges = 1 << bits.read(2);
                case DQ_DOUBLE_EDGE -> {
                    var code = bits.read(2);
                    dqEdges = (1 << code) | (1 << ((code + 1) & 3));
                }
                case DQ_ALL_MACROBLOCKS -> dqBilevel = bits.readFlag();
                default -> dqEdges = EDGE_LEFT | EDGE_TOP | EDGE_RIGHT | EDGE_BOTTOM;
            }
            if (dqProfile != DQ_ALL_MACROBLOCKS || dqBilevel) {
                var pqdiff = bits.read(3);
                altPquant = pqdiff == 7 ? bits.read(5) : pquant + pqdiff + 1;
            }
        }
        if (dqProfile == DQ_ALL_EDGES) {
            dqEdges = EDGE_LEFT | EDGE_TOP | EDGE_RIGHT | EDGE_BOTTOM;
        }
        if (dqProfile != DQ_ALL_MACROBLOCKS || dqBilevel) {
            if (altPquant < 1 || altPquant > 31) {
                throw new MediaException.Malformed("VC-1 ALTPQUANT out of range");
            }
        }
    }

    /**
     * Sizes and clears the per picture state.
     */
    private void allocate() {
        luma.reset(mbWidth * 2, mbHeight * 2);
        chroma[0].reset(mbWidth, mbHeight);
        chroma[1].reset(mbWidth, mbHeight);
        var mbs = mbWidth * mbHeight;
        if (overflag.length < mbs) {
            overflag = new boolean[mbs];
            forwardMv = new int[mbs];
            backwardMv = new int[mbs];
            mbIntra = new boolean[mbs];
            chromaMv = new int[mbs];
            chromaMvFilter = new int[mbs];
            mbFourMv = new boolean[mbs];
        }
        java.util.Arrays.fill(mbIntra, 0, mbs, false);
        java.util.Arrays.fill(mbFourMv, 0, mbs, false);
        java.util.Arrays.fill(forwardMv, 0, mbs, 0);
        java.util.Arrays.fill(backwardMv, 0, mbs, 0);
        java.util.Arrays.fill(chromaMv, 0, mbs, 0);
        java.util.Arrays.fill(chromaMvFilter, 0, mbs, 0);
        if (sliceTop.length < mbHeight) {
            sliceTop = new int[mbHeight];
        }
        var lumaSize = mbWidth * 16 * mbHeight * 16;
        if (residual[0].length < lumaSize) {
            residual[0] = new short[lumaSize];
            residual[1] = new short[lumaSize / 4];
            residual[2] = new short[lumaSize / 4];
        }
    }

    /**
     * Resets the predictions at the start of a slice.
     *
     * @param row the first macroblock row of the slice
     */
    private void startSlice(int row) {
        firstMode3 = true;
        for (var r = row; r < mbHeight; r++) {
            sliceTop[r] = row;
        }
    }

    /**
     * Starts a slice of an advanced profile picture: prediction is reset at its first row and the escape mode 3 sizes
     * are read again.
     *
     * @param row the first macroblock row of the slice
     */
    void slice(int row) {
        startSlice(row);
    }

    /**
     * Decodes macroblock rows.
     *
     * @param fromRow the first row
     * @param toRow the row after the last
     * @throws MediaException.Malformed if the data is invalid
     */
    void decodeRows(int fromRow, int toRow) {
        for (var mby = fromRow; mby < toRow; mby++) {
            for (var mbx = 0; mbx < mbWidth; mbx++) {
                if (frameInterlace) {
                    switch (type) {
                        case PictureHeader.I, PictureHeader.BI -> decodeFrameIntraMacroblock(mbx, mby);
                        case PictureHeader.P -> decodeFramePMacroblock(mbx, mby);
                        default -> decodeFrameBMacroblock(mbx, mby);
                    }
                } else if (fieldParity >= 0 && type == PictureHeader.P) {
                    decodeFieldPMacroblock(mbx, mby);
                } else if (fieldParity >= 0 && type == PictureHeader.B) {
                    decodeFieldBMacroblock(mbx, mby);
                } else {
                    switch (type) {
                        case PictureHeader.I, PictureHeader.BI -> decodeIntraMacroblock(mbx, mby);
                        case PictureHeader.P -> decodePMacroblock(mbx, mby);
                        default -> decodeBMacroblock(mbx, mby);
                    }
                }
            }
            if (bits.overrun()) {
                throw new MediaException.Malformed("truncated VC-1 picture data");
            }
            if (!advanced && sequence.syncMarker && type != PictureHeader.B && mby < toRow - 1) {
                if (bits.readBit() == 0) {
                    bits.alignToByte();
                    var marker = bits.read(24);
                    if (marker == 0x0000AA) {
                        bits.skip(40);
                    } else if (marker == 0x0000AB) {
                        bits.skip(88);
                    } else {
                        throw new MediaException.Malformed("invalid VC-1 sync marker");
                    }
                }
            }
        }
    }

    /**
     * Completes the picture: overlap smoothing, the intra block reconstruction and the loop filter.
     */
    void finish() {
        if (overlapActive) {
            overlap();
        }
        finalizeIntra();
        if (frameInterlace) {
            if (entry.loopFilter) {
                loopFilter.filterFrameInterlace(current, luma, chroma, mbWidth, mbHeight, sliceTop, pquant,
                        type == PictureHeader.I || type == PictureHeader.BI, fieldTx);
            }
            if (type == PictureHeader.P) {
                bufferFrameInterlaceMotion();
            } else if (type == PictureHeader.I) {
                current.hasMotion = false;
            }
            return;
        }
        if (entry.loopFilter) {
            var mode = type == PictureHeader.P ? LoopFilter.PREDICTED
                    : type == PictureHeader.B && fieldParity >= 0 ? LoopFilter.B_FIELD : LoopFilter.ALL_BLOCKS;
            loopFilter.filter(current, lumaBase, lumaPitch, chromaBase, chromaPitch, luma, chroma, mbWidth, mbHeight,
                    sliceTop, pquant, mode, !advanced && sequence.profile == SequenceHeader.MAIN, mbFourMv);
        }
        if (fieldParity >= 0) {
            if (type == PictureHeader.I || type == PictureHeader.P) {
                bufferFieldMotion();
            }
        } else if (type == PictureHeader.P) {
            bufferDirectMotion();
        }
    }

    /**
     * Returns the quantizer of a macroblock from its position: the alternate quantizer on the edges the profile
     * selects.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @return the quantizer
     */
    private int mbQuantForPosition(int mbx, int mby) {
        if (!dquantFrame || dqProfile == DQ_ALL_MACROBLOCKS) {
            return pquant;
        }
        var edges = 0;
        if (mbx == 0) {
            edges |= EDGE_LEFT;
        }
        if (mby == 0) {
            edges |= EDGE_TOP;
        }
        if (mbx == mbWidth - 1) {
            edges |= EDGE_RIGHT;
        }
        if (mby == mbHeight - 1) {
            edges |= EDGE_BOTTOM;
        }
        return (edges & dqEdges) != 0 ? altPquant : pquant;
    }

    /**
     * Sets the quantizer of a macroblock from its position.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @throws MediaException.Malformed if the quantizer is out of range
     */
    private void startMacroblockQuant(int mbx, int mby) {
        setMquant(mbQuantForPosition(mbx, mby));
    }

    /**
     * Sets the macroblock quantizer.
     *
     * @param value the quantizer
     * @throws MediaException.Malformed if the quantizer is outside 1 to 31
     */
    private void setMquant(int value) {
        if (value < 1 || value > 31) {
            throw new MediaException.Malformed("VC-1 macroblock quantizer out of range");
        }
        mquant = value;
        mquantIsPquant = value == pquant;
    }

    /**
     * Reads MQDIFF and ABSMQ when every macroblock carries its quantizer.
     *
     * @throws MediaException.Malformed if the quantizer is out of range
     */
    private void readMquantDifferential() {
        if (!dquantFrame || dqProfile != DQ_ALL_MACROBLOCKS) {
            return;
        }
        if (dqBilevel) {
            setMquant(bits.readBit() == 0 ? pquant : altPquant);
        } else {
            var diff = bits.read(3);
            setMquant(diff != 7 ? pquant + diff : bits.read(5));
        }
    }

    /**
     * Returns twice the macroblock quantizer, plus HALFQP when it is the picture quantizer.
     *
     * @return the double quantizer
     */
    private int doubleQuant() {
        return 2 * mquant + (mquantIsPquant ? header.halfQp : 0);
    }

    /**
     * Returns the DC step size of the macroblock quantizer (section 8.1.3.8).
     *
     * @return the step
     */
    private int dcStepSize() {
        if (mquant <= 2) {
            return 2 * mquant;
        }
        if (mquant <= 4) {
            return 8;
        }
        return mquant / 2 + 6;
    }

    /**
     * Decodes a macroblock of an I or BI picture.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @throws MediaException.Malformed if the data is invalid
     */
    private void decodeIntraMacroblock(int mbx, int mby) {
        startMacroblockQuant(mbx, mby);
        var code = Vc1Codes.I_CBPCY.decode(bits);
        if (code < 0) {
            throw new MediaException.Malformed("invalid VC-1 CBPCY");
        }
        var mb = mby * mbWidth + mbx;
        if (advanced) {
            acPred = acPredPlane.raw() ? bits.readFlag() : acPredPlane.get(mbx, mby) != 0;
            if (condover == 2) {
                if (overflagPlane.raw()) {
                    overflagPlane.set(mbx, mby, bits.readBit());
                }
                overflag[mb] = overflagPlane.get(mbx, mby) != 0;
            } else {
                overflag[mb] = condover == 1;
            }
            readMquantDifferential();
        } else {
            acPred = bits.readFlag();
            overflag[mb] = true;
        }
        var cbp = predictCbp(mbx, mby, code);
        mbIntra[mb] = true;
        for (var block = 0; block < 6; block++) {
            var coded = ((cbp >>> (5 - block)) & 1) != 0;
            decodeIntraBlock(mbx, mby, block, coded, true, false);
        }
    }

    /**
     * Reconstructs the coded block pattern of an I picture macroblock from the predicted luma bits (section 8.1.2.2).
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param decoded the decoded CBPCY, luma bits differential
     * @return the pattern, block 0 in bit 5
     */
    private int predictCbp(int mbx, int mby, int decoded) {
        var bx = mbx * 2;
        var by = mby * 2;
        var top = sliceTop[mby] * 2;
        var l1 = codedStatus(bx - 1, by, top);
        var l3 = codedStatus(bx - 1, by + 1, top);
        var t2 = codedStatus(bx, by - 1, top);
        var t3 = codedStatus(bx + 1, by - 1, top);
        var lt3 = codedStatus(bx - 1, by - 1, top);
        var y0 = (lt3 == t2 ? l1 : t2) ^ ((decoded >>> 5) & 1);
        var y1 = (t2 == t3 ? y0 : t3) ^ ((decoded >>> 4) & 1);
        var y2 = (l1 == y0 ? l3 : y0) ^ ((decoded >>> 3) & 1);
        var y3 = (y0 == y1 ? y2 : y1) ^ ((decoded >>> 2) & 1);
        return (y0 << 5) | (y1 << 4) | (y2 << 3) | (y3 << 2) | (decoded & 3);
    }

    /**
     * Returns whether a luma block was coded, for CBPCY prediction.
     *
     * @param bx the block column
     * @param by the block row
     * @param topRow the first block row of the slice
     * @return 1 when coded, 0 when not or out of the slice
     */
    private int codedStatus(int bx, int by, int topRow) {
        if (bx < 0 || by < topRow || bx >= luma.columns) {
            return 0;
        }
        return luma.coded[by * luma.columns + bx] != 0 ? 1 : 0;
    }

    /**
     * Decodes an intra block, records its prediction state, and stores its residual for the overlap and
     * reconstruction pass.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param block the block, 0 to 3 luma, 4 blue-difference, 5 red-difference
     * @param coded whether AC coefficients are coded
     * @param intraPicture whether the picture is an I or BI picture, which selects the scans and the predictor rules
     * @param fourMv whether the block belongs to a 4-MV macroblock, where ACPRED was read only if a block had a
     * predictor
     */
    private void decodeIntraBlock(int mbx, int mby, int block, boolean coded, boolean intraPicture, boolean fourMv) {
        var plane = block < 4 ? 0 : block - 3;
        var state = plane == 0 ? luma : chroma[plane - 1];
        var bx = plane == 0 ? mbx * 2 + (block & 1) : mbx;
        var by = plane == 0 ? mby * 2 + (block >>> 1) : mby;
        var topRow = plane == 0 ? sliceTop[mby] * 2 : sliceTop[mby];
        var index = by * state.columns + bx;
        var dcVlc = Vc1Codes.DC[dcTable * 2 + (plane == 0 ? 0 : 1)];
        var diff = dcVlc.decode(bits);
        if (diff < 0) {
            throw new MediaException.Malformed("invalid VC-1 DC differential");
        }
        if (diff != 0) {
            if (diff == Vc1Codes.DC_ESCAPE) {
                diff = bits.read(mquant == 1 ? 10 : mquant == 2 ? 9 : 8);
            } else if (mquant == 1) {
                diff = diff * 4 + bits.read(2) - 3;
            } else if (mquant == 2) {
                diff = diff * 2 + bits.readBit() - 1;
            }
            if (bits.readBit() == 1) {
                diff = -diff;
            }
        }
        var dcStep = dcStepSize();
        var acStep = doubleQuant() - 1;
        var aIndex = by > topRow ? index - state.columns : -1;
        var cIndex = bx > 0 ? index - 1 : -1;
        var bIndex = aIndex >= 0 && cIndex >= 0 ? aIndex - 1 : -1;
        var aAvailable = aIndex >= 0 && (intraPicture || state.intra[aIndex]);
        var cAvailable = cIndex >= 0 && (intraPicture || state.intra[cIndex]);
        var bAvailable = bIndex >= 0 && (intraPicture || state.intra[bIndex]);
        var scale = advanced || !intraPicture;
        var dcA = aAvailable ? scaledDc(state, aIndex, dcStep, scale) : 0;
        var dcC = cAvailable ? scaledDc(state, cIndex, dcStep, scale) : 0;
        var dcB = bAvailable ? scaledDc(state, bIndex, dcStep, scale) : 0;
        boolean top;
        int dcPredictor;
        if (!advanced && intraPicture) {
            var fallback = overlapActive ? 0 : (1024 + (dcStep >> 1)) / dcStep;
            if (!aAvailable) {
                dcA = fallback;
            }
            if (!bAvailable) {
                dcB = fallback;
            }
            if (!cAvailable) {
                dcC = fallback;
            }
            if (Math.abs(dcB - dcA) <= Math.abs(dcB - dcC)) {
                top = false;
                dcPredictor = dcC;
            } else {
                top = true;
                dcPredictor = dcA;
            }
        } else if (aAvailable && cAvailable) {
            if (Math.abs(dcB - dcA) <= Math.abs(dcB - dcC)) {
                top = false;
                dcPredictor = dcC;
            } else {
                top = true;
                dcPredictor = dcA;
            }
        } else if (aAvailable) {
            top = true;
            dcPredictor = dcA;
        } else if (cAvailable) {
            top = false;
            dcPredictor = dcC;
        } else {
            top = false;
            dcPredictor = 0;
        }
        var dc = dcPredictor + diff;
        java.util.Arrays.fill(coefficients, 0);
        coefficients[0] = dc;
        var predictFrom = top ? (aAvailable ? aIndex : -1) : (cAvailable ? cIndex : -1);
        int[] scan;
        if (frameInterlace) {
            scan = !acPred || predictFrom < 0 ? Vc1BlockTables.INTERLACE_8X8_SCAN
                    : top ? Vc1BlockTables.INTRA_HORIZONTAL_SCAN : Vc1BlockTables.INTRA_VERTICAL_SCAN;
        } else if (intraPicture) {
            scan = !acPred ? Vc1BlockTables.INTRA_NORMAL_SCAN
                    : top ? Vc1BlockTables.INTRA_HORIZONTAL_SCAN : Vc1BlockTables.INTRA_VERTICAL_SCAN;
        } else {
            scan = interlaceScans ? Vc1BlockTables.INTERLACE_8X8_SCAN : Vc1BlockTables.INTER_8X8_SCAN;
        }
        if (coded) {
            var set = plane == 0 ? lumaIntraSet : chromaIntraSet;
            readCoefficients(set, scan, 1, 64);
        }
        if (acPred && predictFrom >= 0) {
            var base = predictFrom * 14;
            var predStep = state.acStep[predictFrom];
            var needScale = scale && predStep != acStep;
            if (top) {
                for (var i = 1; i < 8; i++) {
                    var value = state.ac[base + i - 1];
                    coefficients[i] += needScale ? scaleCoefficient(value, predStep, acStep) : value;
                }
            } else {
                for (var i = 1; i < 8; i++) {
                    var value = state.ac[base + 6 + i];
                    coefficients[i * 8] += needScale ? scaleCoefficient(value, predStep, acStep) : value;
                }
            }
        }
        state.dc[index] = dc;
        var base = index * 14;
        for (var i = 1; i < 8; i++) {
            state.ac[base + i - 1] = (short) coefficients[i];
            state.ac[base + 6 + i] = (short) coefficients[i * 8];
        }
        state.intra[index] = true;
        state.dcStep[index] = dcStep;
        state.acStep[index] = acStep;
        state.transform[index] = Vc1Codes.TT_8X8;
        state.coded[index] = (byte) (coded ? 15 : 0);
        coefficients[0] = dc * dcStep;
        dequantize(1, 64);
        Transform.inverse(coefficients, 0, 8, 8, temp);
        var res = residual[plane];
        var stride = plane == 0 ? mbWidth * 16 : mbWidth * 8;
        var origin = by * 8 * stride + bx * 8;
        for (var r = 0; r < 8; r++) {
            for (var c = 0; c < 8; c++) {
                res[origin + r * stride + c] = (short) coefficients[r * 8 + c];
            }
        }
    }

    /**
     * Returns the quantized DC of a predictor block, scaled to the step of the current block in the advanced profile
     * and in the P and B pictures of every profile (sections 8.1.3.9 and 8.3.6.1).
     *
     * @param state the plane
     * @param index the predictor block
     * @param dcStep the step of the current block
     * @param scale whether scaling applies
     * @return the predictor
     */
    private int scaledDc(BlockState state, int index, int dcStep, boolean scale) {
        var value = state.dc[index];
        var step = state.dcStep[index];
        if (!scale || step == dcStep) {
            return value;
        }
        return (int) (((long) value * step * DQ_SCALE[dcStep] + 0x20000) >> 18);
    }

    /**
     * Scales a quantized AC predictor to another quantizer step (section 8.1.3.9).
     *
     * @param value the coefficient
     * @param fromStep the step of the predictor block
     * @param toStep the step of the current block
     * @return the scaled coefficient
     */
    private static int scaleCoefficient(int value, int fromStep, int toStep) {
        return (int) (((long) value * fromStep * DQ_SCALE[toStep] + 0x20000) >> 18);
    }

    /**
     * Inverse quantizes coefficients in place with the macroblock quantizer (section 8.1.3.8).
     *
     * @param from the first index
     * @param count the index after the last
     */
    private void dequantize(int from, int count) {
        var dq = doubleQuant();
        var uniform = header.uniform;
        for (var i = from; i < count; i++) {
            var q = coefficients[i];
            if (q == 0) {
                continue;
            }
            var value = q * dq;
            if (!uniform) {
                value += q > 0 ? mquant : -mquant;
            }
            coefficients[i] = value;
        }
    }

    /**
     * Reads run-level coded coefficients into {@link #coefficients} at the positions of a scan.
     *
     * @param set the coding set
     * @param scan the scan order of the block
     * @param start the first scan index
     * @param count the number of scan positions
     */
    private void readCoefficients(AcCodingSet set, int[] scan, int start, int count) {
        var position = start;
        while (true) {
            var index = set.codes.decode(bits);
            if (index < 0) {
                throw new MediaException.Malformed("invalid VC-1 AC coefficient code");
            }
            int run;
            int level;
            boolean last;
            if (index != set.escape) {
                run = set.run[index];
                level = set.level[index];
                last = index >= set.startOfLast;
                if (bits.readBit() == 1) {
                    level = -level;
                }
            } else {
                var mode = Vc1Codes.ESCAPE_MODE.decode(bits);
                if (mode < 2) {
                    index = set.codes.decode(bits);
                    if (index < 0 || index == set.escape) {
                        throw new MediaException.Malformed("invalid VC-1 escaped AC coefficient code");
                    }
                    run = set.run[index];
                    level = set.level[index];
                    last = index >= set.startOfLast;
                    if (mode == 0) {
                        var table = last ? set.deltaLevelLast : set.deltaLevelNotLast;
                        level += run < table.length ? table[run] : 0;
                    } else {
                        var table = last ? set.deltaRunLast : set.deltaRunNotLast;
                        run += (level - 1 < table.length ? table[level - 1] : 0) + 1;
                    }
                    if (bits.readBit() == 1) {
                        level = -level;
                    }
                } else {
                    last = bits.readBit() == 1;
                    if (firstMode3) {
                        firstMode3 = false;
                        var conservative = pquant <= 7 || dquantFrame;
                        escapeLevelSize = (conservative ? Vc1Codes.ESCAPE_LEVEL_SIZE_CONSERVATIVE
                                : Vc1Codes.ESCAPE_LEVEL_SIZE_EFFICIENT).decode(bits);
                        if (escapeLevelSize < 0) {
                            throw new MediaException.Malformed("invalid VC-1 escape level size");
                        }
                        escapeRunSize = 3 + bits.read(2);
                    }
                    run = bits.read(escapeRunSize);
                    var negative = bits.readBit() == 1;
                    level = bits.read(escapeLevelSize);
                    if (negative) {
                        level = -level;
                    }
                }
            }
            position += run;
            if (position >= count) {
                throw new MediaException.Malformed("VC-1 coefficient run past the end of the block");
            }
            coefficients[scan[position]] = level;
            position++;
            if (last) {
                return;
            }
        }
    }

    /**
     * Decodes a macroblock of a progressive P picture.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @throws MediaException.Malformed if the data is invalid
     */
    private void decodePMacroblock(int mbx, int mby) {
        var mb = mby * mbWidth + mbx;
        startMacroblockQuant(mbx, mby);
        var fourMv = mvMode == Vc1Codes.MV_MIXED
                && (mvTypePlane.raw() ? bits.readBit() == 1 : mvTypePlane.get(mbx, mby) != 0);
        if (mvMode == Vc1Codes.MV_MIXED && mvTypePlane.raw()) {
            mvTypePlane.set(mbx, mby, fourMv ? 1 : 0);
        }
        var skipped = skipPlane.raw() ? bits.readBit() == 1 : skipPlane.get(mbx, mby) != 0;
        mbFourMv[mb] = fourMv;
        if (!fourMv) {
            decodeP1Mv(mbx, mby, mb, skipped);
        } else {
            decodeP4Mv(mbx, mby, mb, skipped);
        }
    }

    /**
     * Whether the last MVDATA signaled an intra macroblock or block.
     */
    private int mvIntraFlag;

    /**
     * Whether the last MVDATA signaled more data (coded blocks).
     */
    private int mvMore;

    /**
     * The horizontal motion vector differential last read.
     */
    private int dmvX;

    /**
     * The vertical motion vector differential last read.
     */
    private int dmvY;

    /**
     * Reads MVDATA of a progressive picture (section 8.3.5.2) into {@link #dmvX}, {@link #dmvY}, {@link #mvIntraFlag}
     * and {@link #mvMore}.
     *
     * @throws MediaException.Malformed if the code is invalid
     */
    private void readMvData() {
        var index = mvVlc.decode(bits);
        if (index < 0) {
            throw new MediaException.Malformed("invalid VC-1 motion vector code");
        }
        index++;
        if (index >= 37) {
            mvMore = 1;
            index -= 37;
        } else {
            mvMore = 0;
        }
        mvIntraFlag = 0;
        var half = halfpel ? 1 : 0;
        if (index == 0) {
            dmvX = 0;
            dmvY = 0;
        } else if (index == 35) {
            dmvX = bits.read(kx - half);
            dmvY = bits.read(ky - half);
        } else if (index == 36) {
            mvIntraFlag = 1;
            dmvX = 0;
            dmvY = 0;
        } else {
            dmvX = component(index % 6, half);
            dmvY = component(index / 6, half);
        }
    }

    /**
     * Reads one motion vector differential component.
     *
     * @param index the component index of Figure 50, 1 to 5
     * @param half 1 in half sample modes, which read one bit less for index 5
     * @return the differential
     */
    private int component(int index, int half) {
        var hpel = half == 1 && index == 5 ? 1 : 0;
        var value = bits.read(SIZE_TABLE[index] - hpel);
        var sign = -(value & 1);
        var result = sign ^ ((value >> 1) + OFFSET_TABLE[index]);
        return result - sign;
    }

    /**
     * Decodes a 1-MV macroblock of a progressive P picture, skipped or coded, inter or intra.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param mb the macroblock index
     * @param skipped whether the macroblock is skipped
     */
    private void decodeP1Mv(int mbx, int mby, int mb, boolean skipped) {
        var bx = mbx * 2;
        var by = mby * 2;
        var mvIndex = by * luma.columns + bx;
        if (skipped) {
            predictMv(bx, by, 0, true, mby);
            var mv = BlockState.pack(predictor[0], predictor[1]);
            setLumaMv(mvIndex, mv, true);
            interMacroblockNoResidual(mbx, mby, mb, mv);
            return;
        }
        readMvData();
        if (mvIntraFlag == 1) {
            setLumaMv(mvIndex, 0, false);
            mbIntra[mb] = true;
            boolean coded;
            int cbp = 0;
            if (mvMore == 0) {
                readMquantDifferential();
                acPred = bits.readFlag();
            } else {
                acPred = bits.readFlag();
                cbp = readCbp();
                readMquantDifferential();
            }
            for (var block = 0; block < 6; block++) {
                coded = ((cbp >>> (5 - block)) & 1) != 0;
                decodeIntraBlock(mbx, mby, block, coded, false, false);
            }
            for (var block = 0; block < 4; block++) {
                luma.mv[mvIndex + (block & 1) + (block >>> 1) * luma.columns] = 0;
            }
            chromaMvFilter[mb] = 0;
            chromaMv[mb] = 0;
            return;
        }
        predictMv(bx, by, 0, true, mby);
        var mvx = ((dmvX * (halfpel ? 2 : 1) + predictor[0] + rangeX) & (2 * rangeX - 1)) - rangeX;
        var mvy = ((dmvY * (halfpel ? 2 : 1) + predictor[1] + rangeY) & (2 * rangeY - 1)) - rangeY;
        var mv = BlockState.pack(mvx, mvy);
        setLumaMv(mvIndex, mv, true);
        var cbp = 0;
        if (mvMore != 0) {
            cbp = readCbp();
            readMquantDifferential();
        }
        var lumaMv = BlockState.pack(mvx, mvy);
        predictOneMv(mbx, mby, mb, lumaMv);
        decodeInterResidual(mbx, mby, cbp);
    }

    /**
     * Reads the CBPCY of a progressive P or B macroblock.
     *
     * @return the pattern, block 0 in bit 5
     * @throws MediaException.Malformed if the code is invalid
     */
    private int readCbp() {
        var cbp = cbpVlc.decode(bits);
        if (cbp < 0) {
            throw new MediaException.Malformed("invalid VC-1 CBPCY");
        }
        return cbp;
    }

    /**
     * Sets the motion vector and intra flag of the four luma blocks of a macroblock.
     *
     * @param index the index of the top-left block
     * @param mv the motion vector
     * @param inter whether the blocks are inter coded
     */
    private void setLumaMv(int index, int mv, boolean inter) {
        luma.mv[index] = mv;
        luma.mv[index + 1] = mv;
        luma.mv[index + luma.columns] = mv;
        luma.mv[index + luma.columns + 1] = mv;
        luma.intra[index] = !inter;
        luma.intra[index + 1] = !inter;
        luma.intra[index + luma.columns] = !inter;
        luma.intra[index + luma.columns + 1] = !inter;
    }

    /**
     * Predicts a 1-MV macroblock without residual and writes it.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param mb the macroblock index
     * @param mv the motion vector
     */
    private void interMacroblockNoResidual(int mbx, int mby, int mb, int mv) {
        predictOneMv(mbx, mby, mb, mv);
        noResidual(mbx, mby);
    }

    /**
     * Marks the blocks of a macroblock inter coded without coefficients and writes its prediction.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     */
    private void noResidual(int mbx, int mby) {
        for (var block = 0; block < 6; block++) {
            blockIntra[block] = false;
            markInterBlock(mbx, mby, block, true);
        }
        writePrediction(mbx, mby);
    }

    /**
     * Forms the prediction of a 1-MV macroblock into {@link #prediction} (luma, 16x16) and {@link #prediction2}
     * (the two chroma blocks, 8x8 each, side by side in rows of 16).
     */
    private void predictOneMv(int mbx, int mby, int mb, int mv) {
        var mvx = BlockState.mvx(mv);
        var mvy = BlockState.mvy(mv);
        var cmvx = chromaComponent(mvx);
        var cmvy = chromaComponent(mvy);
        if (entry.fastUvMc) {
            cmvx = fastRound(cmvx);
            cmvy = fastRound(cmvy);
        }
        var lx = mvx;
        var ly = mvy;
        if (!advanced) {
            lx = adjustLuma(mbx * 16, mvx, mbWidth * 16);
            ly = adjustLuma(mby * 16, mvy, mbHeight * 16);
            cmvx = adjustChroma(mbx * 8, cmvx, mbWidth * 8);
            cmvy = adjustChroma(mby * 8, cmvy, mbHeight * 8);
        }
        chromaMv[mb] = BlockState.pack(cmvx, cmvy);
        chromaMvFilter[mb] = chromaMv[mb];
        setReference(forward, 0);
        mc.predict(mbx * 16, mby * 16, 16, 16, lx, ly, filter, rnd, prediction, 0, 16);
        predictChroma(forward, mbx, mby, cmvx, cmvy, prediction2);
    }

    /**
     * Predicts the two color-difference blocks of a macroblock with the bilinear filter.
     *
     * @param reference the reference frame
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param cmvx the horizontal color-difference motion vector
     * @param cmvy the vertical color-difference motion vector
     * @param out the prediction, the blocks side by side in rows of 16
     */
    private void predictChroma(Frame reference, int mbx, int mby, int cmvx, int cmvy, int[] out) {
        setReference(reference, 1);
        mc.predict(mbx * 8, mby * 8, 8, 8, cmvx, cmvy, MotionCompensation.BILINEAR, rnd, out, 0, 16);
        setReference(reference, 2);
        mc.predict(mbx * 8, mby * 8, 8, 8, cmvx, cmvy, MotionCompensation.BILINEAR, rnd, out, 8, 16);
    }

    /**
     * Points the interpolator at one plane of a reference frame.
     *
     * @param reference the frame
     * @param plane 0 luma, 1 blue-difference, 2 red-difference
     */
    private void setReference(Frame reference, int plane) {
        var width = plane == 0 ? reference.width : reference.chromaWidth;
        var validWidth = plane == 0 ? reference.validWidth : (reference.validWidth + 1) >> 1;
        var validHeight = plane == 0 ? reference.validHeight : (reference.validHeight + 1) >> 1;
        mc.reference(reference.plane(plane), width, 0, 1, validWidth, validHeight,
                advanced && reference.interlaced);
        mc.compensation(plane == 0 ? reference.lumaLut[0] : reference.chromaLut[0],
                plane == 0 ? reference.lumaLut[1] : reference.chromaLut[1]);
    }

    /**
     * Derives a color-difference motion vector component from a luma one (section 8.3.5.4.4).
     *
     * @param value the luma component
     * @return the color-difference component
     */
    private static int chromaComponent(int value) {
        return (value + ((value & 3) == 3 ? 1 : 0)) >> 1;
    }

    /**
     * Rounds a color-difference motion vector component to half samples, as FASTUVMC requires.
     *
     * @param value the component
     * @return the rounded component
     */
    private static int fastRound(int value) {
        var remainder = value % 2;
        return value + (remainder == -1 ? 1 : remainder == 1 ? -1 : 0);
    }

    /**
     * Clamps a luma motion vector component so that the macroblock it points to starts no further than one macroblock
     * outside the plane, as the simple and main profiles require.
     *
     * @param coordinate the position of the macroblock
     * @param mv the component
     * @param size the plane size
     * @return the clamped component
     */
    private static int adjustLuma(int coordinate, int mv, int size) {
        var position = coordinate + (mv >> 2);
        if (position < -16) {
            return ((-16 - coordinate) << 2) + (mv & 3);
        }
        if (position > size) {
            return ((size - coordinate) << 2) + (mv & 3);
        }
        return mv;
    }

    /**
     * Clamps a color-difference motion vector component so that the block it points to starts no further than one block
     * outside the plane, as the simple and main profiles require.
     *
     * @param coordinate the position of the block
     * @param mv the component
     * @param size the plane size
     * @return the clamped component
     */
    private static int adjustChroma(int coordinate, int mv, int size) {
        var position = coordinate + (mv >> 2);
        if (position < -8) {
            return ((-8 - coordinate) << 2) + (mv & 3);
        }
        if (position > size) {
            return ((size - coordinate) << 2) + (mv & 3);
        }
        return mv;
    }

    /**
     * Computes the motion vector predictor of a luma block of a P picture (sections 8.3.5.3.1 to 8.3.5.3.5) into
     * {@link #predictor}, reading HYBRIDPRED when the rules call for it.
     *
     * @param bx the block column
     * @param by the block row
     * @param block the block within its macroblock, used for a 4-MV block's candidates
     * @param macroblock whether the vector is the vector of a whole macroblock
     * @param mby the macroblock row
     */
    private void predictMv(int bx, int by, int block, boolean macroblock, int mby) {
        var topRow = sliceTop[mby] * 2;
        var columns = luma.columns;
        var mbx = bx >> 1;
        int ax = bx;
        int ay = by - 1;
        int cx = bx - 1;
        int cy = by;
        int bxCandidate;
        int byCandidate = by - 1;
        if (macroblock) {
            bxCandidate = mbx == mbWidth - 1 ? bx - 1 : bx + 2;
        } else {
            switch (block) {
                case 0 -> bxCandidate = mbx == 0 ? bx + 1 : bx - 1;
                case 1 -> bxCandidate = mbx == mbWidth - 1 ? bx - 1 : bx + 1;
                case 2 -> bxCandidate = bx + 1;
                default -> bxCandidate = bx - 1;
            }
        }
        var aIn = ay >= topRow;
        var cIn = cx >= 0;
        var bIn = byCandidate >= topRow && bxCandidate >= 0 && bxCandidate < columns;
        int aX = 0;
        int aY = 0;
        int cX = 0;
        int cY = 0;
        int bX = 0;
        int bY = 0;
        if (aIn && !luma.intra[ay * columns + ax]) {
            aX = BlockState.mvx(luma.mv[ay * columns + ax]);
            aY = BlockState.mvy(luma.mv[ay * columns + ax]);
        }
        if (cIn && !luma.intra[cy * columns + cx]) {
            cX = BlockState.mvx(luma.mv[cy * columns + cx]);
            cY = BlockState.mvy(luma.mv[cy * columns + cx]);
        }
        if (bIn && !luma.intra[byCandidate * columns + bxCandidate]) {
            bX = BlockState.mvx(luma.mv[byCandidate * columns + bxCandidate]);
            bY = BlockState.mvy(luma.mv[byCandidate * columns + bxCandidate]);
        }
        int px;
        int py;
        if (mbWidth == 1) {
            if (aIn) {
                px = aX;
                py = aY;
            } else if (cIn) {
                px = cX;
                py = cY;
            } else {
                px = 0;
                py = 0;
            }
        } else if (aIn) {
            if (!cIn && !bIn) {
                px = aX;
                py = aY;
            } else {
                px = median3(aX, bX, cX);
                py = median3(aY, bY, cY);
            }
        } else if (cIn) {
            px = cX;
            py = cY;
        } else {
            px = 0;
            py = 0;
        }
        var maxX = mbWidth * 16 * 4 - 4;
        var maxY = mbHeight * 16 * 4 - 4;
        if (macroblock) {
            var qx = (bx >> 1) * 64;
            var qy = (by >> 1) * 64;
            px = pullback(qx, px, -60, maxX);
            py = pullback(qy, py, -60, maxY);
        } else {
            var qx = bx * 32;
            var qy = by * 32;
            px = pullback(qx, px, -28, maxX);
            py = pullback(qy, py, -28, maxY);
        }
        if (aIn && cIn) {
            var sum = Math.abs(px - aX) + Math.abs(py - aY);
            if (sum > 32) {
                if (bits.readBit() == 1) {
                    px = aX;
                    py = aY;
                } else {
                    px = cX;
                    py = cY;
                }
            } else {
                sum = Math.abs(px - cX) + Math.abs(py - cY);
                if (sum > 32) {
                    if (bits.readBit() == 1) {
                        px = aX;
                        py = aY;
                    } else {
                        px = cX;
                        py = cY;
                    }
                }
            }
        }
        predictor[0] = px;
        predictor[1] = py;
    }

    /**
     * Pulls a motion vector predictor back so that the predicted block lies within the picture area of section
     * 8.3.5.3.3.
     *
     * @param origin the position of the block in quarter samples
     * @param value the predictor component
     * @param min the lowest position allowed
     * @param max the highest position allowed
     * @return the pulled back component
     */
    private static int pullback(int origin, int value, int min, int max) {
        if (origin + value < min) {
            return min - origin;
        }
        if (origin + value > max) {
            return max - origin;
        }
        return value;
    }

    /**
     * Returns the median of three values.
     *
     * @param a a value
     * @param b a value
     * @param c a value
     * @return the median
     */
    static int median3(int a, int b, int c) {
        if (a > b) {
            if (b > c) {
                return b;
            }
            return Math.min(a, c);
        }
        if (a > c) {
            return a;
        }
        return Math.min(b, c);
    }

    /**
     * Returns the median of four values, the average of the two middle ones rounded toward zero.
     *
     * @param a a value
     * @param b a value
     * @param c a value
     * @param d a value
     * @return the median
     */
    static int median4(int a, int b, int c, int d) {
        var max = a;
        var min = a;
        if (b > max) {
            max = b;
        } else if (b < min) {
            min = b;
        }
        if (c > max) {
            max = c;
        } else if (c < min) {
            min = c;
        }
        if (d > max) {
            max = d;
        } else if (d < min) {
            min = d;
        }
        return (a + b + c + d - max - min) / 2;
    }

    /**
     * Decodes a 4-MV macroblock of a progressive P picture.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param mb the macroblock index
     * @param skipped whether the macroblock is skipped
     */
    private void decodeP4Mv(int mbx, int mby, int mb, boolean skipped) {
        var bx0 = mbx * 2;
        var by0 = mby * 2;
        var columns = luma.columns;
        var cbp = 0;
        if (!skipped) {
            cbp = readCbp();
        }
        var anyIntra = false;
        var anyMore = false;
        var intraCount = 0;
        for (var block = 0; block < 4; block++) {
            var bx = bx0 + (block & 1);
            var by = by0 + (block >>> 1);
            var index = by * columns + bx;
            blockIntra[block] = false;
            blockCoded[block] = false;
            var hasData = !skipped && ((cbp >>> (5 - block)) & 1) != 0;
            if (hasData) {
                readMvData();
                if (mvIntraFlag == 1) {
                    blockIntra[block] = true;
                    blockCoded[block] = mvMore != 0;
                    luma.mv[index] = 0;
                    luma.intra[index] = true;
                    anyIntra = true;
                    intraCount++;
                    continue;
                }
                blockCoded[block] = mvMore != 0;
                anyMore |= mvMore != 0;
            } else {
                dmvX = 0;
                dmvY = 0;
            }
            luma.intra[index] = false;
            predictMv(bx, by, block, false, mby);
            var mvx = ((dmvX * (halfpel ? 2 : 1) + predictor[0] + rangeX) & (2 * rangeX - 1)) - rangeX;
            var mvy = ((dmvY * (halfpel ? 2 : 1) + predictor[1] + rangeY) & (2 * rangeY - 1)) - rangeY;
            luma.mv[index] = BlockState.pack(mvx, mvy);
        }
        blockCoded[4] = !skipped && ((cbp >>> 1) & 1) != 0;
        blockCoded[5] = !skipped && (cbp & 1) != 0;
        var chromaIntra = intraCount >= 3;
        blockIntra[4] = chromaIntra;
        blockIntra[5] = chromaIntra;
        mbIntra[mb] = intraCount == 4;
        if (!chromaIntra) {
            int ix;
            int iy;
            var count = 0;
            for (var block = 0; block < 4; block++) {
                if (!blockIntra[block]) {
                    var index = (by0 + (block >>> 1)) * columns + bx0 + (block & 1);
                    blockMv[count++] = luma.mv[index];
                }
            }
            if (count == 4) {
                ix = median4(BlockState.mvx(blockMv[0]), BlockState.mvx(blockMv[1]), BlockState.mvx(blockMv[2]),
                        BlockState.mvx(blockMv[3]));
                iy = median4(BlockState.mvy(blockMv[0]), BlockState.mvy(blockMv[1]), BlockState.mvy(blockMv[2]),
                        BlockState.mvy(blockMv[3]));
            } else if (count == 3) {
                ix = median3(BlockState.mvx(blockMv[0]), BlockState.mvx(blockMv[1]), BlockState.mvx(blockMv[2]));
                iy = median3(BlockState.mvy(blockMv[0]), BlockState.mvy(blockMv[1]), BlockState.mvy(blockMv[2]));
            } else {
                ix = (BlockState.mvx(blockMv[0]) + BlockState.mvx(blockMv[1])) / 2;
                iy = (BlockState.mvy(blockMv[0]) + BlockState.mvy(blockMv[1])) / 2;
            }
            var cmvx = chromaComponent(ix);
            var cmvy = chromaComponent(iy);
            if (entry.fastUvMc) {
                cmvx = fastRound(cmvx);
                cmvy = fastRound(cmvy);
            }
            if (!advanced) {
                cmvx = adjustChroma(mbx * 8, cmvx, mbWidth * 8);
                cmvy = adjustChroma(mby * 8, cmvy, mbHeight * 8);
            }
            chromaMv[mb] = BlockState.pack(cmvx, cmvy);
            chromaMvFilter[mb] = chromaMv[mb];
        } else {
            chromaMv[mb] = 0;
            chromaMvFilter[mb] = 0;
        }
        for (var block = 0; block < 4; block++) {
            if (blockIntra[block]) {
                continue;
            }
            var index = (by0 + (block >>> 1)) * columns + bx0 + (block & 1);
            var mvx = BlockState.mvx(luma.mv[index]);
            var mvy = BlockState.mvy(luma.mv[index]);
            if (!advanced) {
                mvx = adjustLuma(mbx * 16, mvx, mbWidth * 16);
                mvy = adjustLuma(mby * 16, mvy, mbHeight * 16);
            }
            setReference(forward, 0);
            mc.predict(mbx * 16 + (block & 1) * 8, mby * 16 + (block >>> 1) * 8, 8, 8, mvx, mvy, filter, rnd,
                    prediction, (block >>> 1) * 8 * 16 + (block & 1) * 8, 16);
        }
        if (!chromaIntra) {
            predictChroma(forward, mbx, mby, BlockState.mvx(chromaMv[mb]), BlockState.mvy(chromaMv[mb]),
                    prediction2);
        }
        var anyChroma = blockCoded[4] || blockCoded[5];
        if (skipped || (!anyIntra && !anyMore && !anyChroma)) {
            noResidual(mbx, mby);
            return;
        }
        readMquantDifferential();
        acPred = false;
        if (anyIntra || chromaIntra) {
            var nonzero = false;
            for (var block = 0; block < 6; block++) {
                if (blockIntra[block] && hasIntraNeighbor(mbx, mby, block)) {
                    nonzero = true;
                }
            }
            if (nonzero) {
                acPred = bits.readFlag();
            }
        }
        decodeFourMvResidual(mbx, mby);
    }

    /**
     * Returns whether the block above or left of a block of a 4-MV macroblock is intra coded, the condition for ACPRED
     * to be present.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param block the block of the macroblock, 0 to 3 luma, 4 and 5 color-difference
     * @return {@code true} when a predictor is intra coded
     */
    private boolean hasIntraNeighbor(int mbx, int mby, int block) {
        var plane = block < 4 ? 0 : block - 3;
        var state = plane == 0 ? luma : chroma[plane - 1];
        var bx = plane == 0 ? mbx * 2 + (block & 1) : mbx;
        var by = plane == 0 ? mby * 2 + (block >>> 1) : mby;
        var topRow = plane == 0 ? sliceTop[mby] * 2 : sliceTop[mby];
        if (by > topRow && isIntraBlock(state, bx, by - 1, plane, mbx, mby, block, true)) {
            return true;
        }
        return bx > 0 && isIntraBlock(state, bx - 1, by, plane, mbx, mby, block, false);
    }

    /**
     * Returns whether a block is intra coded, reading a block of the current macroblock from its decoded mode.
     *
     * @param state the plane
     * @param bx the block column
     * @param by the block row
     * @param plane the plane index, 0 luma, 1 and 2 color-difference
     * @param mbx the column of the current macroblock
     * @param mby the row of the current macroblock
     * @param block the current block
     * @param above whether the block is the one above
     * @return {@code true} when intra coded
     */
    private boolean isIntraBlock(BlockState state, int bx, int by, int plane, int mbx, int mby, int block,
                                 boolean above) {
        if (plane == 0) {
            var inCurrent = (bx >> 1) == mbx && (by >> 1) == mby;
            if (inCurrent) {
                var neighbor = (by & 1) * 2 + (bx & 1);
                return blockIntra[neighbor];
            }
        }
        return state.intra[by * state.columns + bx];
    }

    /**
     * Decodes the residual of every block of a macroblock whose blocks may each be intra or inter coded: TTMB, then
     * each block with its transform type and subblock pattern.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @throws MediaException.Malformed if the data is invalid
     */
    private void decodeFourMvResidual(int mbx, int mby) {
        var transform = -1;
        var firstInter = true;
        var macroblockLevel = false;
        var pattern = 0;
        var anyInterCoded = false;
        for (var block = 0; block < 6; block++) {
            if (!blockIntra[block] && blockCoded[block]) {
                anyInterCoded = true;
            }
        }
        if (!ttmbf && anyInterCoded) {
            var symbol = ttmbVlc().decode(bits);
            if (symbol < 0) {
                throw new MediaException.Malformed("invalid VC-1 TTMB");
            }
            transform = symbol & 3;
            pattern = (symbol >>> 2) & 3;
            macroblockLevel = (symbol & Vc1Codes.TT_MACROBLOCK) != 0;
        }
        for (var block = 0; block < 6; block++) {
            if (blockIntra[block]) {
                markInterBlock(mbx, mby, block, false);
                decodeIntraBlock(mbx, mby, block, blockCoded[block], false, true);
                continue;
            }
            if (!blockCoded[block]) {
                markInterBlock(mbx, mby, block, true);
                writeBlock(mbx, mby, block, null);
                continue;
            }
            int blockTransform;
            int blockPattern;
            if (ttmbf) {
                blockTransform = ttfrm;
                blockPattern = readSubblockPattern(blockTransform, true);
            } else if (firstInter) {
                blockTransform = transform;
                blockPattern = blockTransform == Vc1Codes.TT_4X4 ? readSubblockPattern(blockTransform, true) : pattern;
                if (blockTransform == Vc1Codes.TT_8X8) {
                    blockPattern = 1;
                }
            } else if (macroblockLevel) {
                blockTransform = transform;
                blockPattern = readSubblockPattern(blockTransform, true);
            } else {
                var symbol = ttblkVlc().decode(bits);
                if (symbol < 0) {
                    throw new MediaException.Malformed("invalid VC-1 TTBLK");
                }
                blockTransform = symbol & 3;
                blockPattern = blockTransform == Vc1Codes.TT_4X4 ? readSubblockPattern(blockTransform, true)
                        : blockTransform == Vc1Codes.TT_8X8 ? 1 : (symbol >>> 2) & 3;
            }
            firstInter = false;
            decodeInterBlock(mbx, mby, block, blockTransform, blockPattern);
        }
    }

    /**
     * Records an inter block without coefficients, or an intra block before its decoding, in the block state.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param block the block of the macroblock, 0 to 3 luma, 4 and 5 color-difference
     * @param inter whether the block is inter coded
     */
    private void markInterBlock(int mbx, int mby, int block, boolean inter) {
        var plane = block < 4 ? 0 : block - 3;
        var state = plane == 0 ? luma : chroma[plane - 1];
        var bx = plane == 0 ? mbx * 2 + (block & 1) : mbx;
        var by = plane == 0 ? mby * 2 + (block >>> 1) : mby;
        var index = by * state.columns + bx;
        state.intra[index] = !inter;
        state.transform[index] = Vc1Codes.TT_8X8;
        state.coded[index] = 0;
        if (plane != 0) {
            state.mv[index] = chromaMvFilter[mby * mbWidth + mbx];
        }
    }

    /**
     * Returns the TTMB table of the picture quantizer.
     *
     * @return the table
     */
    private Vlc ttmbVlc() {
        return pquant <= 4 ? Vc1Codes.TTMB_HIGH_RATE : pquant <= 12 ? Vc1Codes.TTMB_MEDIUM_RATE : Vc1Codes.TTMB_LOW_RATE;
    }

    /**
     * Returns the TTBLK table of the picture quantizer.
     *
     * @return the table
     */
    private Vlc ttblkVlc() {
        return pquant <= 4 ? Vc1Codes.TTBLK_HIGH_RATE
                : pquant <= 12 ? Vc1Codes.TTBLK_MEDIUM_RATE : Vc1Codes.TTBLK_LOW_RATE;
    }

    /**
     * Reads SUBBLKPAT for a block whose transform type is not signaled with its pattern.
     *
     * @param transform the transform type
     * @param present whether the element is in the bitstream for 8x4 and 4x8 transforms
     * @return the pattern: 1 for 8x8; for 8x4 and 4x8, bit 1 the top or left half and bit 0 the other; for 4x4, 8
     * SB0 + 4 SB1 + 2 SB2 + SB3
     */
    private int readSubblockPattern(int transform, boolean present) {
        return switch (transform) {
            case Vc1Codes.TT_8X8 -> 1;
            case Vc1Codes.TT_4X4 -> {
                var vlc = pquant <= 4 ? Vc1Codes.SUBBLOCK_PATTERN_HIGH_RATE
                        : pquant <= 12 ? Vc1Codes.SUBBLOCK_PATTERN_MEDIUM_RATE : Vc1Codes.SUBBLOCK_PATTERN_LOW_RATE;
                var pattern = vlc.decode(bits);
                if (pattern < 0) {
                    throw new MediaException.Malformed("invalid VC-1 SUBBLKPAT");
                }
                yield pattern;
            }
            default -> {
                var pattern = Vc1Codes.HALF_SUBBLOCK_PATTERN.decode(bits);
                if (pattern < 0) {
                    throw new MediaException.Malformed("invalid VC-1 SUBBLKPAT");
                }
                yield pattern;
            }
        };
    }

    /**
     * Decodes the residual of an inter macroblock with a coded block pattern and writes its blocks.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param cbp the pattern, block 0 in bit 5
     */
    private void decodeInterResidual(int mbx, int mby, int cbp) {
        for (var block = 0; block < 6; block++) {
            blockIntra[block] = false;
            blockCoded[block] = ((cbp >>> (5 - block)) & 1) != 0;
        }
        if (cbp == 0) {
            noResidual(mbx, mby);
            return;
        }
        decodeFourMvResidual(mbx, mby);
    }

    /**
     * The residual of the inter block being decoded, rows 8 apart.
     */
    private final int[] residualBlock = new int[64];

    /**
     * Decodes the coefficients of an inter block, inverse transforms them and writes the block.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param block the block of the macroblock, 0 to 3 luma, 4 and 5 color-difference
     * @param transform the transform type
     * @param pattern the coded subblocks
     * @throws MediaException.Malformed if the data is invalid
     */
    private void decodeInterBlock(int mbx, int mby, int block, int transform, int pattern) {
        java.util.Arrays.fill(residualBlock, 0);
        var interlaceScan = false;
        int[] scan;
        var codedQuadrants = 0;
        switch (transform) {
            case Vc1Codes.TT_8X8 -> {
                scan = interlaceScans ? Vc1BlockTables.INTERLACE_8X8_SCAN : Vc1BlockTables.INTER_8X8_SCAN;
                decodeSubblock(scan, 8, 8, 0);
                codedQuadrants = 15;
            }
            case Vc1Codes.TT_8X4 -> {
                scan = interlaceScans ? Vc1BlockTables.INTERLACE_8X4_SCAN
                        : advanced ? Vc1BlockTables.PROGRESSIVE_8X4_SCAN : Vc1BlockTables.SIMPLE_8X4_SCAN;
                if ((pattern & 2) != 0) {
                    decodeSubblock(scan, 8, 4, 0);
                    codedQuadrants |= 3;
                }
                if ((pattern & 1) != 0) {
                    decodeSubblock(scan, 8, 4, 32);
                    codedQuadrants |= 12;
                }
            }
            case Vc1Codes.TT_4X8 -> {
                scan = interlaceScans ? Vc1BlockTables.INTERLACE_4X8_SCAN
                        : advanced ? Vc1BlockTables.PROGRESSIVE_4X8_SCAN : Vc1BlockTables.SIMPLE_4X8_SCAN;
                if ((pattern & 2) != 0) {
                    decodeSubblock(scan, 4, 8, 0);
                    codedQuadrants |= 5;
                }
                if ((pattern & 1) != 0) {
                    decodeSubblock(scan, 4, 8, 4);
                    codedQuadrants |= 10;
                }
            }
            default -> {
                scan = interlaceScans ? Vc1BlockTables.INTERLACE_4X4_SCAN : Vc1BlockTables.INTER_4X4_SCAN;
                for (var sub = 0; sub < 4; sub++) {
                    if (((pattern >>> (3 - sub)) & 1) != 0) {
                        decodeSubblock(scan, 4, 4, (sub >>> 1) * 32 + (sub & 1) * 4);
                        codedQuadrants |= 1 << sub;
                    }
                }
            }
        }
        var plane = block < 4 ? 0 : block - 3;
        var state = plane == 0 ? luma : chroma[plane - 1];
        var bx = plane == 0 ? mbx * 2 + (block & 1) : mbx;
        var by = plane == 0 ? mby * 2 + (block >>> 1) : mby;
        var index = by * state.columns + bx;
        state.intra[index] = false;
        state.transform[index] = (byte) transform;
        state.coded[index] = (byte) codedQuadrants;
        if (plane != 0) {
            state.mv[index] = chromaMvFilter[mby * mbWidth + mbx];
        }
        writeBlock(mbx, mby, block, residualBlock);
    }

    /**
     * Decodes one subblock of an inter block into {@link #residualBlock} and inverse transforms it.
     *
     * @param scan the scan order of the block
     * @param width the subblock width
     * @param height the subblock height
     * @param offset the index of the subblock in the block
     */
    private void decodeSubblock(int[] scan, int width, int height, int offset) {
        java.util.Arrays.fill(coefficients, 0);
        readCoefficients(interSet, scan, 0, width * height);
        dequantize(0, width * height);
        for (var r = 0; r < height; r++) {
            for (var c = 0; c < width; c++) {
                temp[r * 8 + c] = coefficients[r * width + c];
            }
        }
        for (var r = 0; r < height; r++) {
            for (var c = 0; c < width; c++) {
                residualBlock[offset + r * 8 + c] = temp[r * 8 + c];
            }
        }
        Transform.inverse(residualBlock, offset, width, height, temp);
    }

    /**
     * Writes prediction plus residual for one block of the current macroblock into the frame.
     *
     * @param residualBlock the residual, rows 8 apart, or {@code null} for none
     */
    private void writeBlock(int mbx, int mby, int block, int[] residualBlock) {
        byte[] plane;
        int stride;
        int x;
        int y;
        int[] source;
        int sourceOffset;
        int base;
        if (block < 4) {
            plane = current.y;
            stride = lumaPitch;
            base = lumaBase;
            x = mbx * 16 + (block & 1) * 8;
            y = mby * 16 + (block >>> 1) * 8;
            source = prediction;
            sourceOffset = (block >>> 1) * 8 * 16 + (block & 1) * 8;
        } else {
            plane = current.plane(block - 3);
            stride = chromaPitch;
            base = chromaBase;
            x = mbx * 8;
            y = mby * 8;
            source = prediction2;
            sourceOffset = block == 4 ? 0 : 8;
        }
        var permuted = block < 4 && frameInterlace && fieldTx[mby * mbWidth + mbx];
        if (permuted) {
            y = mby * 16 + (block >>> 1);
            sourceOffset = (block >>> 1) * 16 + (block & 1) * 8;
        }
        var rowStep = permuted ? 2 : 1;
        for (var r = 0; r < 8; r++) {
            var out = base + (y + r * rowStep) * stride + x;
            var in = sourceOffset + r * rowStep * 16;
            for (var c = 0; c < 8; c++) {
                var value = source[in + c];
                if (residualBlock != null) {
                    value += residualBlock[r * 8 + c];
                }
                plane[out + c] = (byte) Frame.clip(value);
            }
        }
    }

    /**
     * Writes the prediction of the inter blocks of a macroblock without residual.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     */
    private void writePrediction(int mbx, int mby) {
        for (var block = 0; block < 6; block++) {
            if (!blockIntra[block]) {
                writeBlock(mbx, mby, block, null);
            }
        }
    }

    /**
     * Applies the intensity compensation of a P picture to both fields of its reference.
     *
     * @param reference the reference, or {@code null}
     */
    private void applyIntensityCompensation(Frame reference) {
        if (reference == null) {
            return;
        }
        intensityTables(lumScale, lumShift, icLuma[0], icChroma[0]);
        reference.compensate(0, icLuma[0], icChroma[0]);
        reference.compensate(1, icLuma[0], icChroma[0]);
    }

    /**
     * Decodes a macroblock of a progressive B picture.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @throws MediaException.Malformed if the data is invalid
     */
    private void decodeBMacroblock(int mbx, int mby) {
        var mb = mby * mbWidth + mbx;
        startMacroblockQuant(mbx, mby);
        var direct = directPlane.raw() ? bits.readBit() == 1 : directPlane.get(mbx, mby) != 0;
        var skipped = skipPlane.raw() ? bits.readBit() == 1 : skipPlane.get(mbx, mby) != 0;
        var bType = 0;
        var intra = false;
        var more1 = 0;
        var dmv1x = 0;
        var dmv1y = 0;
        if (!direct) {
            if (!skipped) {
                readMvData();
                intra = mvIntraFlag == 1;
                more1 = mvMore;
                dmv1x = dmvX;
                dmv1y = dmvY;
            }
            if (!intra) {
                var code = Vc1Codes.B_MV_TYPE.decode(bits);
                if (code < 0) {
                    throw new MediaException.Malformed("invalid VC-1 BMVTYPE");
                }
                var atLeastHalf = header.fractionAtLeastHalf();
                bType = switch (code) {
                    case 0 -> atLeastHalf ? B_BACKWARD : B_FORWARD;
                    case 1 -> atLeastHalf ? B_FORWARD : B_BACKWARD;
                    default -> B_INTERPOLATED;
                };
            }
        } else {
            bType = B_DIRECT;
        }
        var directX = 0;
        var directY = 0;
        var directBx = 0;
        var directBy = 0;
        if (backward != null && backward.hasMotion && !backward.directIntra[mb]) {
            var buffered = backward.directMv[mb];
            var ref = BlockState.mvx(buffered);
            var refY = BlockState.mvy(buffered);
            var scaleFactor = header.scaleFactor;
            if (halfpel) {
                directX = 2 * ((ref * scaleFactor + 255) >> 9);
                directY = 2 * ((refY * scaleFactor + 255) >> 9);
                directBx = 2 * ((ref * (scaleFactor - 256) + 255) >> 9);
                directBy = 2 * ((refY * (scaleFactor - 256) + 255) >> 9);
            } else {
                directX = (ref * scaleFactor + 128) >> 8;
                directY = (refY * scaleFactor + 128) >> 8;
                directBx = (ref * (scaleFactor - 256) + 128) >> 8;
                directBy = (refY * (scaleFactor - 256) + 128) >> 8;
            }
            var qx = mbx * 64;
            var qy = mby * 64;
            var maxX = mbWidth * 64 - 4;
            var maxY = mbHeight * 64 - 4;
            directX = pullback(qx, directX, -60, maxX);
            directY = pullback(qy, directY, -60, maxY);
            directBx = pullback(qx, directBx, -60, maxX);
            directBy = pullback(qy, directBy, -60, maxY);
        }
        if (intra) {
            mbIntra[mb] = true;
            forwardMv[mb] = 0;
            backwardMv[mb] = 0;
            setLumaMv(mby * 2 * luma.columns + mbx * 2, 0, false);
            var cbp = 0;
            if (more1 == 0) {
                readMquantDifferential();
                acPred = bits.readFlag();
            } else {
                acPred = bits.readFlag();
                cbp = readCbp();
                readMquantDifferential();
            }
            for (var block = 0; block < 6; block++) {
                blockIntra[block] = true;
                decodeIntraBlock(mbx, mby, block, ((cbp >>> (5 - block)) & 1) != 0, false, false);
            }
            return;
        }
        int fx = 0;
        int fy = 0;
        int bxv = 0;
        int byv = 0;
        var residual = false;
        switch (bType) {
            case B_DIRECT -> {
                fx = directX;
                fy = directY;
                bxv = directBx;
                byv = directBy;
                residual = !skipped;
            }
            case B_FORWARD -> {
                predictB(mbx, mby, forwardMv);
                fx = wrap(dmv1x * (halfpel ? 2 : 1) + predictor[0], rangeX);
                fy = wrap(dmv1y * (halfpel ? 2 : 1) + predictor[1], rangeY);
                bxv = directBx;
                byv = directBy;
                residual = !skipped && more1 != 0;
            }
            case B_BACKWARD -> {
                predictB(mbx, mby, backwardMv);
                bxv = wrap(dmv1x * (halfpel ? 2 : 1) + predictor[0], rangeX);
                byv = wrap(dmv1y * (halfpel ? 2 : 1) + predictor[1], rangeY);
                fx = directX;
                fy = directY;
                residual = !skipped && more1 != 0;
            }
            default -> {
                var dmv2x = 0;
                var dmv2y = 0;
                var more2 = 0;
                if (!skipped && more1 != 0) {
                    readMvData();
                    if (mvIntraFlag != 0) {
                        throw new MediaException.Malformed("VC-1 BMV2 signals an intra macroblock");
                    }
                    dmv2x = dmvX;
                    dmv2y = dmvY;
                    more2 = mvMore;
                }
                predictB(mbx, mby, backwardMv);
                bxv = wrap(dmv1x * (halfpel ? 2 : 1) + predictor[0], rangeX);
                byv = wrap(dmv1y * (halfpel ? 2 : 1) + predictor[1], rangeY);
                predictB(mbx, mby, forwardMv);
                fx = wrap(dmv2x * (halfpel ? 2 : 1) + predictor[0], rangeX);
                fy = wrap(dmv2y * (halfpel ? 2 : 1) + predictor[1], rangeY);
                residual = !skipped && more2 != 0;
            }
        }
        forwardMv[mb] = BlockState.pack(fx, fy);
        backwardMv[mb] = BlockState.pack(bxv, byv);
        var useForward = bType != B_BACKWARD;
        var useBackward = bType != B_FORWARD;
        predictBidirectional(mbx, mby, fx, fy, bxv, byv, useForward, useBackward);
        var lumaIndex = mby * 2 * luma.columns + mbx * 2;
        setLumaMv(lumaIndex, 0, true);
        for (var block = 0; block < 6; block++) {
            blockIntra[block] = false;
        }
        if (!residual) {
            noResidual(mbx, mby);
            return;
        }
        var cbp = readCbp();
        readMquantDifferential();
        decodeInterResidual(mbx, mby, cbp);
    }

    /**
     * A forward predicted B macroblock.
     */
    private static final int B_FORWARD = 0;

    /**
     * A backward predicted B macroblock.
     */
    private static final int B_BACKWARD = 1;

    /**
     * An interpolated B macroblock.
     */
    private static final int B_INTERPOLATED = 2;

    /**
     * A direct B macroblock.
     */
    private static final int B_DIRECT = 3;

    /**
     * Wraps a motion vector component into its range.
     *
     * @param value the component
     * @param range the range, a power of two
     * @return the component within minus the range to the range minus 1
     */
    private static int wrap(int value, int range) {
        return ((value + range) & (2 * range - 1)) - range;
    }

    /**
     * Computes the motion vector predictor of a progressive B macroblock from one direction's vectors into {@link
     * #predictor}.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param context the vectors of the direction
     */
    private void predictB(int mbx, int mby, int[] context) {
        var topRow = sliceTop[mby];
        var aIn = mby > topRow;
        var cIn = mbx > 0;
        var bxCandidate = mbx == mbWidth - 1 ? mbx - 1 : mbx + 1;
        var bIn = mby > topRow && bxCandidate >= 0 && bxCandidate < mbWidth;
        var aMv = aIn ? context[(mby - 1) * mbWidth + mbx] : 0;
        var cMv = cIn ? context[mby * mbWidth + mbx - 1] : 0;
        var bMv = bIn ? context[(mby - 1) * mbWidth + bxCandidate] : 0;
        if (aIn && mbIntra[(mby - 1) * mbWidth + mbx]) {
            aMv = 0;
        }
        if (cIn && mbIntra[mby * mbWidth + mbx - 1]) {
            cMv = 0;
        }
        if (bIn && mbIntra[(mby - 1) * mbWidth + bxCandidate]) {
            bMv = 0;
        }
        int px;
        int py;
        if (mbWidth == 1) {
            px = aIn ? BlockState.mvx(aMv) : cIn ? BlockState.mvx(cMv) : 0;
            py = aIn ? BlockState.mvy(aMv) : cIn ? BlockState.mvy(cMv) : 0;
        } else if (aIn) {
            if (!cIn && !bIn) {
                px = BlockState.mvx(aMv);
                py = BlockState.mvy(aMv);
            } else {
                px = median3(BlockState.mvx(aMv), BlockState.mvx(bMv), BlockState.mvx(cMv));
                py = median3(BlockState.mvy(aMv), BlockState.mvy(bMv), BlockState.mvy(cMv));
            }
        } else if (cIn) {
            px = BlockState.mvx(cMv);
            py = BlockState.mvy(cMv);
        } else {
            px = 0;
            py = 0;
        }
        var qx = mbx * 64;
        var qy = mby * 64;
        if (advanced) {
            px = pullback(qx, px, -60, mbWidth * 64 - 4);
            py = pullback(qy, py, -60, mbHeight * 64 - 4);
        } else {
            px = pullback(qx / 2, px, -28, mbWidth * 32 - 4);
            py = pullback(qy / 2, py, -28, mbHeight * 32 - 4);
        }
        predictor[0] = px;
        predictor[1] = py;
    }

    /**
     * Predicts a progressive B macroblock from its forward vector, its backward vector or both, averaged.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param fx the horizontal forward vector
     * @param fy the vertical forward vector
     * @param bx the horizontal backward vector
     * @param by the vertical backward vector
     * @param useForward whether the forward prediction is used
     * @param useBackward whether the backward prediction is used
     */
    private void predictBidirectional(int mbx, int mby, int fx, int fy, int bx, int by, boolean useForward,
                                      boolean useBackward) {
        var mb = mby * mbWidth + mbx;
        if (!advanced) {
            fx = adjustLuma(mbx * 16, fx, mbWidth * 16);
            fy = adjustLuma(mby * 16, fy, mbHeight * 16);
            bx = adjustLuma(mbx * 16, bx, mbWidth * 16);
            by = adjustLuma(mby * 16, by, mbHeight * 16);
        }
        var cfx = chromaComponent(fx);
        var cfy = chromaComponent(fy);
        var cbx = chromaComponent(bx);
        var cby = chromaComponent(by);
        if (entry.fastUvMc) {
            cfx = towardZeroEven(cfx);
            cfy = towardZeroEven(cfy);
            cbx = towardZeroEven(cbx);
            cby = towardZeroEven(cby);
        }
        var forwardFrame = forward;
        var backwardFrame = backward;
        if (useForward) {
            setReference(forwardFrame, 0);
            mc.predict(mbx * 16, mby * 16, 16, 16, fx, fy, filter, rnd, prediction, 0, 16);
            predictChroma(forwardFrame, mbx, mby, cfx, cfy, prediction2);
        }
        if (useBackward) {
            var target = useForward ? interpolationLuma : prediction;
            var targetChroma = useForward ? interpolationChroma : prediction2;
            setReference(backwardFrame, 0);
            mc.predict(mbx * 16, mby * 16, 16, 16, bx, by, filter, rnd, target, 0, 16);
            predictChroma(backwardFrame, mbx, mby, cbx, cby, targetChroma);
            if (useForward) {
                for (var i = 0; i < 256; i++) {
                    prediction[i] = (prediction[i] + interpolationLuma[i] + 1) >> 1;
                }
                for (var i = 0; i < 128; i++) {
                    prediction2[i] = (prediction2[i] + interpolationChroma[i] + 1) >> 1;
                }
            }
        }
        chromaMvFilter[mb] = 0;
    }

    /**
     * The backward luma prediction of an interpolated macroblock.
     */
    private final int[] interpolationLuma = new int[256];

    /**
     * The backward color-difference prediction of an interpolated macroblock.
     */
    private final int[] interpolationChroma = new int[256];

    /**
     * Rounds an odd value toward zero to the even value next to it.
     *
     * @param value the value before the operation
     * @return the even value
     */
    private static int towardZeroEven(int value) {
        if ((value & 1) != 0) {
            return value > 0 ? value - 1 : value + 1;
        }
        return value;
    }

    /**
     * Records the motion vectors of a progressive P picture for the direct mode of the B pictures that follow (section
     * 8.4.5.4).
     */
    private void bufferDirectMotion() {
        var columns = luma.columns;
        for (var mby = 0; mby < mbHeight; mby++) {
            for (var mbx = 0; mbx < mbWidth; mbx++) {
                var mb = mby * mbWidth + mbx;
                var index = mby * 2 * columns + mbx * 2;
                var count = 0;
                for (var block = 0; block < 4; block++) {
                    var i = index + (block & 1) + (block >>> 1) * columns;
                    if (!luma.intra[i]) {
                        blockMv[count++] = luma.mv[i];
                    }
                }
                var intra = count < 2;
                int x = 0;
                int y = 0;
                if (!intra) {
                    if (!mbFourMv[mb]) {
                        x = BlockState.mvx(blockMv[0]);
                        y = BlockState.mvy(blockMv[0]);
                    } else if (count == 4) {
                        x = median4(BlockState.mvx(blockMv[0]), BlockState.mvx(blockMv[1]),
                                BlockState.mvx(blockMv[2]), BlockState.mvx(blockMv[3]));
                        y = median4(BlockState.mvy(blockMv[0]), BlockState.mvy(blockMv[1]),
                                BlockState.mvy(blockMv[2]), BlockState.mvy(blockMv[3]));
                    } else if (count == 3) {
                        x = median3(BlockState.mvx(blockMv[0]), BlockState.mvx(blockMv[1]),
                                BlockState.mvx(blockMv[2]));
                        y = median3(BlockState.mvy(blockMv[0]), BlockState.mvy(blockMv[1]),
                                BlockState.mvy(blockMv[2]));
                    } else {
                        x = (BlockState.mvx(blockMv[0]) + BlockState.mvx(blockMv[1])) / 2;
                        y = (BlockState.mvy(blockMv[0]) + BlockState.mvy(blockMv[1])) / 2;
                    }
                }
                current.directIntra[mb] = intra;
                current.directMv[mb] = BlockState.pack(x, y);
            }
        }
        current.hasMotion = true;
    }

    /**
     * Applies overlap smoothing (section 8.5) to the intra residuals: vertical edges first, then horizontal edges,
     * except in interlace frame pictures, which smooth vertical edges only.
     */
    private void overlap() {
        for (var plane = 0; plane < 3; plane++) {
            var state = plane == 0 ? luma : chroma[plane - 1];
            var res = residual[plane];
            var stride = plane == 0 ? mbWidth * 16 : mbWidth * 8;
            var columns = state.columns;
            var rows = state.rows;
            for (var by = 0; by < rows; by++) {
                for (var bx = 1; bx < columns; bx++) {
                    if (overlapEdge(state, plane, bx - 1, by, bx, by)) {
                        var x = bx * 8;
                        for (var r = 0; r < 8; r++) {
                            var at = (by * 8 + r) * stride + x;
                            smooth(res, at - 2, 1, (r & 1) == 0);
                        }
                    }
                }
            }
            for (var by = 1; by < rows && !frameInterlace; by++) {
                for (var bx = 0; bx < columns; bx++) {
                    if (overlapEdge(state, plane, bx, by - 1, bx, by)) {
                        var y = by * 8;
                        for (var c = 0; c < 8; c++) {
                            var at = y * stride + bx * 8 + c;
                            smooth(res, at - 2 * stride, stride, (c & 1) == 0);
                        }
                    }
                }
            }
        }
    }

    /**
     * Returns whether the edge between two blocks is smoothed: both intra, in the same slice, and selected by CONDOVER
     * and OVERFLAGS in advanced profile I pictures.
     *
     * @param state the plane
     * @param plane the plane index
     * @param bx0 the column of the first block
     * @param by0 the row of the first block
     * @param bx1 the column of the second block
     * @param by1 the row of the second block
     * @return {@code true} to smooth it
     */
    private boolean overlapEdge(BlockState state, int plane, int bx0, int by0, int bx1, int by1) {
        var i0 = by0 * state.columns + bx0;
        var i1 = by1 * state.columns + bx1;
        if (!state.intra[i0] || !state.intra[i1]) {
            return false;
        }
        var mbx0 = plane == 0 ? bx0 >> 1 : bx0;
        var mby0 = plane == 0 ? by0 >> 1 : by0;
        var mbx1 = plane == 0 ? bx1 >> 1 : bx1;
        var mby1 = plane == 0 ? by1 >> 1 : by1;
        if (mby0 != mby1 && sliceTop[mby1] != sliceTop[mby0]) {
            return false;
        }
        if (advanced && (type == PictureHeader.I || type == PictureHeader.BI) && pquant <= 8) {
            if (condover == 0) {
                return false;
            }
            if (condover == 2) {
                var flag0 = overflag[mby0 * mbWidth + mbx0];
                var flag1 = overflag[mby1 * mbWidth + mbx1];
                if (mbx0 == mbx1 && mby0 == mby1) {
                    return flag0;
                }
                return flag0 && flag1;
            }
        }
        return true;
    }

    /**
     * Applies the overlap smoothing filter of section 8.5.1 across one edge.
     *
     * @param samples the residuals
     * @param at the index of the first of the four samples
     * @param step the distance between them
     * @param odd whether the line is odd, which selects the rounding
     */
    private static void smooth(short[] samples, int at, int step, boolean odd) {
        var r0 = odd ? 4 : 3;
        var r1 = odd ? 3 : 4;
        int x0 = samples[at];
        int x1 = samples[at + step];
        int x2 = samples[at + 2 * step];
        int x3 = samples[at + 3 * step];
        samples[at] = (short) ((7 * x0 + x3 + r0) >> 3);
        samples[at + step] = (short) ((-x0 + 7 * x1 + x2 + x3 + r1) >> 3);
        samples[at + 2 * step] = (short) ((x0 + x1 + 7 * x2 - x3 + r0) >> 3);
        samples[at + 3 * step] = (short) ((x0 + 7 * x3 + r1) >> 3);
    }

    /**
     * Writes the intra blocks, the residual plus {@link #intraOffset}, clipped, and permutes the field transform luma
     * blocks of interlace frame pictures.
     */
    private void finalizeIntra() {
        for (var plane = 0; plane < 3; plane++) {
            var state = plane == 0 ? luma : chroma[plane - 1];
            var res = residual[plane];
            var samples = current.plane(plane);
            var stride = plane == 0 ? lumaPitch : chromaPitch;
            var base = plane == 0 ? lumaBase : chromaBase;
            var resStride = plane == 0 ? mbWidth * 16 : mbWidth * 8;
            for (var by = 0; by < state.rows; by++) {
                for (var bx = 0; bx < state.columns; bx++) {
                    if (!state.intra[by * state.columns + bx]) {
                        continue;
                    }
                    var permuted = plane == 0 && frameInterlace && fieldTx[(by >> 1) * mbWidth + (bx >> 1)];
                    var firstRow = permuted ? (by >> 1) * 16 + (by & 1) : by * 8;
                    var rowStep = permuted ? 2 : 1;
                    for (var r = 0; r < 8; r++) {
                        var in = (by * 8 + r) * resStride + bx * 8;
                        var out = base + (firstRow + r * rowStep) * stride + bx * 8;
                        for (var c = 0; c < 8; c++) {
                            samples[out + c] = (byte) Frame.clip(res[in + c] + intraOffset);
                        }
                    }
                }
            }
        }
    }

    /**
     * An intra macroblock of an interlace frame picture.
     */
    private static final int FRAME_INTRA = 0;

    /**
     * A 1-MV macroblock of an interlace frame picture.
     */
    private static final int FRAME_ONE_MV = 1;

    /**
     * A 2 field MV macroblock of an interlace frame picture.
     */
    private static final int FRAME_TWO_FIELD = 2;

    /**
     * A 4 frame MV macroblock of an interlace frame picture.
     */
    private static final int FRAME_FOUR_MV = 3;

    /**
     * A 4 field MV macroblock of an interlace frame picture.
     */
    private static final int FRAME_FOUR_FIELD = 4;

    /**
     * The macroblock type, motion vector presence and transform of each symbol of the interlace frame macroblock mode
     * tables (Tables 160 to 167): type in bits 0 to 2, MVP in bit 3, transform in bits 4 and 5 (0 frame, 1 field, 2
     * no coded blocks).
     */
    private static final int[] FRAME_MODE_SYMBOLS = {
            FRAME_ONE_MV | 8, FRAME_ONE_MV | 8 | 16, FRAME_ONE_MV | 8 | 32, FRAME_ONE_MV, FRAME_ONE_MV | 16,
            FRAME_TWO_FIELD, FRAME_TWO_FIELD | 16, FRAME_TWO_FIELD | 32, FRAME_FOUR_MV, FRAME_FOUR_MV | 16,
            FRAME_FOUR_MV | 32, FRAME_FOUR_FIELD, FRAME_FOUR_FIELD | 16, FRAME_FOUR_FIELD | 32, FRAME_INTRA
    };

    /**
     * The macroblock types of the interlace frame MBMODE tables without 4-MV (Tables 164 to 167), packed as {@link
     * #FRAME_MODE_SYMBOLS}.
     */
    private static final int[] FRAME_MODE_SYMBOLS_NO_FOUR_MV = {
            FRAME_ONE_MV | 8, FRAME_ONE_MV | 8 | 16, FRAME_ONE_MV | 8 | 32, FRAME_ONE_MV, FRAME_ONE_MV | 16,
            FRAME_TWO_FIELD, FRAME_TWO_FIELD | 16, FRAME_TWO_FIELD | 32, FRAME_INTRA
    };

    /**
     * s_RndTblField of Figure 148, the color-difference rounding of field motion vectors.
     */
    private static final int[] FIELD_CHROMA_ROUND = {0, 0, 1, 2, 4, 4, 5, 6, 2, 2, 3, 8, 6, 6, 7, 12};

    /**
     * Whether the picture is an interlace frame picture.
     */
    private boolean frameInterlace;

    /**
     * 4MVSWITCH: whether the P picture may use 4-MV macroblocks.
     */
    private boolean fourMvSwitch;

    /**
     * The 2MVBP table.
     */
    private Vlc twoMvPatternVlc;

    /**
     * The FIELDTX bitplane.
     */
    final Bitplane fieldTxPlane = new Bitplane();

    /**
     * Whether each macroblock of an interlace frame picture is field transform coded.
     */
    private boolean[] fieldTx = new boolean[0];

    /**
     * The type of each macroblock of an interlace frame picture in each prediction context (0 forward, 1 backward).
     */
    private byte[][] frameType = new byte[2][0];

    /**
     * The four motion vectors of each macroblock of an interlace frame picture in each prediction context: the frame
     * blocks in raster order, or the top field vectors then the bottom field ones.
     */
    private int[][] frameMv = new int[2][0];

    /**
     * The horizontal components of the candidates of an interlace frame motion vector predictor.
     */
    private final int[] validX = new int[3];

    /**
     * The vertical components matching {@link #validX}.
     */
    private final int[] validY = new int[3];

    /**
     * The number of candidates in {@link #validX}.
     */
    private int validCount;

    /**
     * Reads the rest of an interlace frame picture header (Tables 82 to 84).
     *
     * @param apply whether to apply intensity compensation to the reference, false for a repeated header
     * @throws MediaException.Malformed if the header is invalid
     */
    private void parseFrameInterlaceHeader(boolean apply) {
        if (type == PictureHeader.I || type == PictureHeader.BI) {
            fieldTxPlane.decode(bits, mbWidth, mbHeight);
            acPredPlane.decode(bits, mbWidth, mbHeight);
            overflagPlane.clear(mbWidth, mbHeight);
            if (entry.overlap && pquant <= 8) {
                condover = bits.readBit() == 0 ? 0 : bits.readBit() == 0 ? 1 : 2;
                if (condover == 2) {
                    overflagPlane.decode(bits, mbWidth, mbHeight);
                }
            }
            var chromaIndex = transformAcIndex();
            var lumaIndex = transformAcIndex();
            lumaIntraSet = Vc1Codes.intraSet(lumaIndex, header.pqindex);
            chromaIntraSet = Vc1Codes.interSet(chromaIndex, header.pqindex);
            dcTable = bits.readBit();
            if (entry.dquant != 0) {
                parseVopDquant();
            }
            overlapActive = entry.overlap && (pquant >= 9 || condover != 0);
            intraOffset = 128;
            return;
        }
        var p = type == PictureHeader.P;
        var mvRange = entry.extendedMv ? Vc1Codes.MV_RANGE.decode(bits) : 0;
        if (mvRange < 0) {
            throw new MediaException.Malformed("invalid VC-1 MVRANGE");
        }
        header.mvRange = mvRange;
        extendX = 0;
        extendY = 0;
        if (entry.extendedDmv) {
            var code = Vc1Codes.DMV_RANGE.decode(bits);
            extendX = code & 1;
            extendY = code >>> 1;
        }
        fourMvSwitch = p && bits.readFlag();
        intensity = bits.readFlag();
        if (intensity && !p) {
            throw new MediaException.Malformed("VC-1 intensity compensation in an interlace frame B picture");
        }
        if (intensity) {
            lumScale = bits.read(6);
            lumShift = bits.read(6);
        }
        if (!p) {
            directPlane.decode(bits, mbWidth, mbHeight);
        }
        skipPlane.decode(bits, mbWidth, mbHeight);
        var modeTable = bits.read(2);
        mbModeVlc = fourMvSwitch ? Vc1Codes.FRAME_FOUR_MV_MBMODE[modeTable] : Vc1Codes.FRAME_MBMODE[modeTable];
        fieldMvVlc = Vc1Codes.ONE_REF_MV[bits.read(2)];
        fieldCbpVlc = Vc1Codes.INTERLACED_CBPCY[bits.read(3)];
        twoMvPatternVlc = Vc1Codes.TWO_MV_BLOCK_PATTERN[bits.read(2)];
        if (fourMvSwitch || !p) {
            fourMvPatternVlc = Vc1Codes.FOUR_MV_BLOCK_PATTERN[bits.read(2)];
        }
        if (entry.dquant != 0) {
            parseVopDquant();
        }
        if (entry.variableTransform) {
            ttmbf = bits.readFlag();
            if (ttmbf) {
                ttfrm = bits.read(2);
            }
        }
        var index = transformAcIndex();
        lumaIntraSet = Vc1Codes.intraSet(index, header.pqindex);
        chromaIntraSet = Vc1Codes.interSet(index, header.pqindex);
        interSet = chromaIntraSet;
        dcTable = bits.readBit();
        overlapActive = p && entry.overlap && pquant >= 9;
        intraOffset = 128;
        halfpel = false;
        filter = MotionCompensation.BICUBIC;
        twoReferences = false;
        kx = K_X[mvRange];
        ky = K_Y[mvRange];
        rangeX = RANGE_X[mvRange];
        rangeY = RANGE_Y[mvRange];
        if (apply && p && intensity) {
            applyIntensityCompensation(forward);
        }
    }

    /**
     * Sizes and clears the macroblock state of an interlace frame picture.
     */
    private void prepareFrameInterlace() {
        var mbs = mbWidth * mbHeight;
        if (fieldTx.length < mbs) {
            fieldTx = new boolean[mbs];
            frameType[0] = new byte[mbs];
            frameType[1] = new byte[mbs];
            frameMv[0] = new int[mbs * 4];
            frameMv[1] = new int[mbs * 4];
        }
        java.util.Arrays.fill(fieldTx, 0, mbs, false);
        for (var context = 0; context < 2; context++) {
            java.util.Arrays.fill(frameType[context], 0, mbs, (byte) FRAME_INTRA);
            java.util.Arrays.fill(frameMv[context], 0, mbs * 4, 0);
        }
    }

    /**
     * Decodes a macroblock of an interlace frame I or BI picture (Table 90).
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @throws MediaException.Malformed if the data is invalid
     */
    private void decodeFrameIntraMacroblock(int mbx, int mby) {
        var mb = mby * mbWidth + mbx;
        startMacroblockQuant(mbx, mby);
        fieldTx[mb] = fieldTxPlane.raw() ? bits.readFlag() : fieldTxPlane.get(mbx, mby) != 0;
        var code = Vc1Codes.I_CBPCY.decode(bits);
        if (code < 0) {
            throw new MediaException.Malformed("invalid VC-1 CBPCY");
        }
        acPred = acPredPlane.raw() ? bits.readFlag() : acPredPlane.get(mbx, mby) != 0;
        if (condover == 2) {
            if (overflagPlane.raw()) {
                overflagPlane.set(mbx, mby, bits.readBit());
            }
            overflag[mb] = overflagPlane.get(mbx, mby) != 0;
        } else {
            overflag[mb] = condover == 1;
        }
        readMquantDifferential();
        var cbp = predictCbp(mbx, mby, code);
        mbIntra[mb] = true;
        for (var block = 0; block < 6; block++) {
            decodeIntraBlock(mbx, mby, block, ((cbp >>> (5 - block)) & 1) != 0, true, false);
        }
    }

    /**
     * Decodes an intra macroblock of an interlace frame P or B picture: FIELDTX, CBPPRESENT, CBPCY, ACPRED, the
     * quantizer and the blocks.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param mb the macroblock index
     */
    private void frameIntraMacroblock(int mbx, int mby, int mb) {
        mbIntra[mb] = true;
        frameType[0][mb] = FRAME_INTRA;
        frameType[1][mb] = FRAME_INTRA;
        setLumaMv(mby * 2 * luma.columns + mbx * 2, 0, false);
        fieldTx[mb] = bits.readFlag();
        var cbp = bits.readFlag() ? readFieldCbp() : 0;
        acPred = bits.readFlag();
        readMquantDifferential();
        for (var block = 0; block < 6; block++) {
            blockIntra[block] = true;
            decodeIntraBlock(mbx, mby, block, ((cbp >>> (5 - block)) & 1) != 0, false, false);
        }
    }

    /**
     * Reads the MBMODE of an interlace frame macroblock.
     *
     * @return the type, the motion vector presence and the transform, as {@link #FRAME_MODE_SYMBOLS} packs them
     * @throws MediaException.Malformed if the code is invalid
     */
    private int frameModeSymbol() {
        var symbol = mbModeVlc.decode(bits);
        if (symbol < 0) {
            throw new MediaException.Malformed("invalid VC-1 frame MBMODE");
        }
        return fourMvSwitch ? FRAME_MODE_SYMBOLS[symbol] : FRAME_MODE_SYMBOLS_NO_FOUR_MV[symbol];
    }

    /**
     * Decodes a macroblock of an interlace frame P picture (Table 91).
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @throws MediaException.Malformed if the data is invalid
     */
    private void decodeFramePMacroblock(int mbx, int mby) {
        var mb = mby * mbWidth + mbx;
        startMacroblockQuant(mbx, mby);
        var skipped = skipPlane.raw() ? bits.readBit() == 1 : skipPlane.get(mbx, mby) != 0;
        int mode = skipped ? FRAME_ONE_MV | 32 : frameModeSymbol();
        var mbType = mode & 7;
        if (mbType == FRAME_INTRA) {
            frameIntraMacroblock(mbx, mby, mb);
            return;
        }
        var transform = (mode >>> 4) & 3;
        var fieldMotion = mbType == FRAME_TWO_FIELD || mbType == FRAME_FOUR_FIELD;
        fieldTx[mb] = transform == 1 || (transform == 2 && fieldMotion);
        var cbp = transform != 2 ? readFieldCbp() : 0;
        var pattern = 0;
        if (mbType == FRAME_ONE_MV) {
            pattern = (mode & 8) != 0 ? 1 : 0;
        } else if (mbType == FRAME_TWO_FIELD) {
            pattern = readPattern(twoMvPatternVlc);
        } else {
            pattern = readPattern(fourMvPatternVlc);
        }
        frameType[0][mb] = (byte) mbType;
        decodeFrameVectors(0, mbx, mby, mbType, pattern, mbType == FRAME_ONE_MV ? 1 : mbType == FRAME_TWO_FIELD ? 2 : 4);
        markFrameMotion(mbx, mby, 0);
        frameMotion(0, mbx, mby, mbType, prediction, prediction2);
        frameResidual(mbx, mby, cbp);
    }

    /**
     * Reads a 2MVBP or 4MVBP.
     *
     * @param vlc the table
     * @return the pattern
     * @throws MediaException.Malformed if the code is invalid
     */
    private int readPattern(Vlc vlc) {
        var pattern = vlc.decode(bits);
        if (pattern < 0) {
            throw new MediaException.Malformed("invalid VC-1 motion vector block pattern");
        }
        return pattern;
    }

    /**
     * Decodes and reconstructs the vectors of one direction of an interlace frame macroblock into the context's
     * slots, in the order of the slots.
     *
     * @param pattern the presence bits of the differentials, the first vector in the highest bit
     * @param count the number of vectors: 1, 2 or 4
     */
    private void decodeFrameVectors(int context, int mbx, int mby, int mbType, int pattern, int count) {
        var mb = mby * mbWidth + mbx;
        var slots = frameMv[context];
        for (var i = 0; i < count; i++) {
            if (((pattern >>> (count - 1 - i)) & 1) != 0) {
                readFieldMv();
            } else {
                dmvX = 0;
                dmvY = 0;
            }
            int slot;
            if (count == 1) {
                slot = 0;
            } else if (count == 2) {
                slot = i * 2;
            } else {
                slot = i;
            }
            predictFrameMv(context, mbx, mby, mbType, slot);
            var mvx = smod(dmvX + predictor[0], rangeX);
            var mvy = smod(dmvY + predictor[1], rangeY);
            var mv = BlockState.pack(mvx, mvy);
            if (count == 1) {
                for (var s = 0; s < 4; s++) {
                    slots[mb * 4 + s] = mv;
                }
            } else if (count == 2) {
                slots[mb * 4 + slot] = mv;
                slots[mb * 4 + slot + 1] = mv;
            } else {
                slots[mb * 4 + slot] = mv;
            }
        }
    }

    /**
     * Records the frame vectors of a macroblock of an interlace frame picture as the motion of its luma blocks.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param context the context whose vectors are recorded
     */
    private void markFrameMotion(int mbx, int mby, int context) {
        var mb = mby * mbWidth + mbx;
        var index = mby * 2 * luma.columns + mbx * 2;
        for (var block = 0; block < 4; block++) {
            var i = index + (block & 1) + (block >>> 1) * luma.columns;
            luma.intra[i] = false;
            luma.mv[i] = frameMv[context][mb * 4 + block];
        }
    }

    /**
     * Returns whether a neighboring macroblock is inside the picture and the slice.
     *
     * @param mbx the column of the current macroblock
     * @param mby the row of the current macroblock
     * @param x the column of the neighbor
     * @param y the row of the neighbor
     * @return {@code true} when it exists
     */
    private boolean frameCandidateExists(int mbx, int mby, int x, int y) {
        return x >= 0 && x < mbWidth && y >= sliceTop[mby] && y <= mby;
    }

    /**
     * Averages two motion vectors (section 10.7.3.5.5).
     *
     * @param first a vector
     * @param second a vector
     * @return the average, rounded up
     */
    private static int average(int first, int second) {
        return BlockState.pack((BlockState.mvx(first) + BlockState.mvx(second) + 1) >> 1,
                (BlockState.mvy(first) + BlockState.mvy(second) + 1) >> 1);
    }

    /**
     * Returns the candidate a neighbor gives a frame motion vector: the vector at a slot of a frame-predicted
     * neighbor, or the average of the top and bottom field vectors in the slot's column of a field-predicted one.
     */
    private int frameCandidate(int context, int neighbor, int slot) {
        var t = frameType[context][neighbor];
        var mvs = frameMv[context];
        if (t == FRAME_TWO_FIELD || t == FRAME_FOUR_FIELD) {
            return average(mvs[neighbor * 4 + (slot & 1)], mvs[neighbor * 4 + (slot & 1) + 2]);
        }
        return mvs[neighbor * 4 + slot];
    }

    /**
     * Returns the candidate a neighbor gives a field motion vector.
     *
     * @param frameSlot the slot read from a frame-predicted (1-MV or 4 frame MV) neighbor
     * @param fieldSlot the slot read from a 4 field MV neighbor
     * @param parity the field of the vector, which selects the vector of a 2 field MV neighbor
     */
    private int fieldCandidate(int context, int neighbor, int frameSlot, int fieldSlot, int parity) {
        var t = frameType[context][neighbor];
        var mvs = frameMv[context];
        if (t == FRAME_TWO_FIELD) {
            return mvs[neighbor * 4 + parity * 2];
        }
        if (t == FRAME_FOUR_FIELD) {
            return mvs[neighbor * 4 + fieldSlot];
        }
        return mvs[neighbor * 4 + frameSlot];
    }

    /**
     * Adds a candidate to {@link #validX} and {@link #validY}.
     *
     * @param mv the candidate
     */
    private void addCandidate(int mv) {
        validX[validCount] = BlockState.mvx(mv);
        validY[validCount] = BlockState.mvy(mv);
        validCount++;
    }

    /**
     * Returns whether a neighboring macroblock exists and is inter coded in a context.
     *
     * @param context the prediction context, 0 forward and 1 backward
     * @param mbx the column of the current macroblock
     * @param mby the row of the current macroblock
     * @param x the column of the neighbor
     * @param y the row of the neighbor
     * @return {@code true} when it gives a candidate
     */
    private boolean interNeighbor(int context, int mbx, int mby, int x, int y) {
        return frameCandidateExists(mbx, mby, x, y) && frameType[context][y * mbWidth + x] != FRAME_INTRA;
    }

    /**
     * Computes the predictor of one vector of an interlace frame macroblock (section 10.7.3.5) into
     * {@link #predictor}.
     *
     * @param slot the vector's slot: 0 to 3 for a 4-MV macroblock (frame or field blocks in raster order), 0 and 2
     * for the top and bottom field vectors of a 2 field MV macroblock, 0 for a 1-MV macroblock
     */
    private void predictFrameMv(int context, int mbx, int mby, int mbType, int slot) {
        var mb = mby * mbWidth + mbx;
        var current = frameMv[context];
        var lastInRow = mbx == mbWidth - 1;
        var cx = lastInRow ? mbx - 1 : mbx + 1;
        var topRight = !lastInRow;
        var aIn = interNeighbor(context, mbx, mby, mbx - 1, mby);
        var bIn = interNeighbor(context, mbx, mby, mbx, mby - 1);
        var cIn = interNeighbor(context, mbx, mby, cx, mby - 1);
        var a = mb - 1;
        var b = mb - mbWidth;
        var c = (mby - 1) * mbWidth + cx;
        validCount = 0;
        var field = mbType == FRAME_TWO_FIELD || mbType == FRAME_FOUR_FIELD;
        if (!field) {
            switch (slot) {
                case 0 -> {
                    if (aIn) {
                        addCandidate(frameCandidate(context, a, 1));
                    }
                    if (bIn) {
                        addCandidate(frameCandidate(context, b, 2));
                    }
                    if (cIn) {
                        addCandidate(frameCandidate(context, c, topRight ? 2 : 3));
                    }
                }
                case 1 -> {
                    addCandidate(current[mb * 4]);
                    if (bIn) {
                        addCandidate(frameCandidate(context, b, 3));
                    }
                    if (cIn) {
                        addCandidate(frameCandidate(context, c, topRight ? 2 : 3));
                    }
                }
                case 2 -> {
                    if (aIn) {
                        addCandidate(frameCandidate(context, a, 3));
                    }
                    addCandidate(current[mb * 4]);
                    addCandidate(current[mb * 4 + 1]);
                }
                default -> {
                    addCandidate(current[mb * 4 + 2]);
                    addCandidate(current[mb * 4]);
                    addCandidate(current[mb * 4 + 1]);
                }
            }
            if (validCount >= 2) {
                var third = validCount == 3;
                predictor[0] = median3(validX[0], validX[1], third ? validX[2] : 0);
                predictor[1] = median3(validY[0], validY[1], third ? validY[2] : 0);
            } else if (validCount == 1) {
                predictor[0] = validX[0];
                predictor[1] = validY[0];
            } else {
                predictor[0] = 0;
                predictor[1] = 0;
            }
            return;
        }
        var parity = slot >>> 1;
        var side = mbType == FRAME_FOUR_FIELD ? slot & 1 : 0;
        if (side == 0) {
            if (aIn) {
                addCandidate(fieldCandidate(context, a, parity == 0 ? 1 : 3, parity * 2 + 1, parity));
            }
        } else {
            addCandidate(current[mb * 4 + parity * 2]);
        }
        if (bIn) {
            addCandidate(fieldCandidate(context, b, side == 0 ? 2 : 3, parity * 2 + side, parity));
        }
        if (cIn) {
            addCandidate(fieldCandidate(context, c, topRight ? 2 : 3, parity * 2 + (topRight ? 0 : 1), parity));
        }
        if (validCount == 0) {
            predictor[0] = 0;
            predictor[1] = 0;
            return;
        }
        if (validCount == 1) {
            predictor[0] = validX[0];
            predictor[1] = validY[0];
            return;
        }
        var sameCount = 0;
        var oppositeCount = 0;
        var firstSame = -1;
        var firstOpposite = -1;
        for (var i = 0; i < validCount; i++) {
            if ((validY[i] & 4) != 0) {
                oppositeCount++;
                if (firstOpposite < 0) {
                    firstOpposite = i;
                }
            } else {
                sameCount++;
                if (firstSame < 0) {
                    firstSame = i;
                }
            }
        }
        if (validCount == 3 && (sameCount == 3 || oppositeCount == 3)) {
            predictor[0] = median3(validX[0], validX[1], validX[2]);
            predictor[1] = median3(validY[0], validY[1], validY[2]);
        } else if (sameCount >= oppositeCount) {
            predictor[0] = validX[firstSame];
            predictor[1] = validY[firstSame];
        } else {
            predictor[0] = validX[firstOpposite];
            predictor[1] = validY[firstOpposite];
        }
    }

    /**
     * Derives the vertical color-difference component of a frame or field motion vector of an interlace frame picture
     * (Figure 148).
     *
     * @param lumaY the vertical luma component
     * @param field whether the vector is a field vector
     * @return the color-difference component
     */
    private static int frameChromaY(int lumaY, boolean field) {
        if (field) {
            return (lumaY >> 4) * 8 + FIELD_CHROMA_ROUND[lumaY & 15];
        }
        return chromaComponent(lumaY);
    }

    /**
     * Forms the prediction of an interlace frame macroblock from one reference frame and the vectors of a context.
     */
    private void frameMotion(int context, int mbx, int mby, int mbType, int[] lumaOut, int[] chromaOut) {
        var mb = mby * mbWidth + mbx;
        var mvs = frameMv[context];
        var reference = context == 0 ? forward : backward;
        var x0 = mbx * 16;
        var y0 = mby * 16;
        switch (mbType) {
            case FRAME_ONE_MV -> {
                var mv = mvs[mb * 4];
                frameBlock(reference, 0, x0, y0, 16, 16, BlockState.mvx(mv), BlockState.mvy(mv), lumaOut, 0, false);
                var cx = chromaComponent(BlockState.mvx(mv));
                var cy = frameChromaY(BlockState.mvy(mv), false);
                frameBlock(reference, 1, x0 / 2, y0 / 2, 8, 8, cx, cy, chromaOut, 0, false);
                frameBlock(reference, 2, x0 / 2, y0 / 2, 8, 8, cx, cy, chromaOut, 8, false);
            }
            case FRAME_FOUR_MV -> {
                for (var block = 0; block < 4; block++) {
                    var mv = mvs[mb * 4 + block];
                    var bx = (block & 1) * 8;
                    var by = (block >>> 1) * 8;
                    frameBlock(reference, 0, x0 + bx, y0 + by, 8, 8, BlockState.mvx(mv), BlockState.mvy(mv),
                            lumaOut, by * 16 + bx, false);
                    var cx = chromaComponent(BlockState.mvx(mv));
                    var cy = frameChromaY(BlockState.mvy(mv), false);
                    frameBlock(reference, 1, x0 / 2 + bx / 2, y0 / 2 + by / 2, 4, 4, cx, cy, chromaOut,
                            (by / 2) * 16 + bx / 2, false);
                    frameBlock(reference, 2, x0 / 2 + bx / 2, y0 / 2 + by / 2, 4, 4, cx, cy, chromaOut,
                            (by / 2) * 16 + 8 + bx / 2, false);
                }
            }
            case FRAME_TWO_FIELD -> {
                for (var parity = 0; parity < 2; parity++) {
                    var mv = mvs[mb * 4 + parity * 2];
                    frameBlock(reference, 0, x0, y0 + parity, 16, 8, BlockState.mvx(mv), BlockState.mvy(mv),
                            lumaOut, parity * 16, true);
                    var cx = chromaComponent(BlockState.mvx(mv));
                    var cy = frameChromaY(BlockState.mvy(mv), true);
                    frameBlock(reference, 1, x0 / 2, y0 / 2 + parity, 8, 4, cx, cy, chromaOut, parity * 16, true);
                    frameBlock(reference, 2, x0 / 2, y0 / 2 + parity, 8, 4, cx, cy, chromaOut, parity * 16 + 8,
                            true);
                }
            }
            default -> {
                for (var slot = 0; slot < 4; slot++) {
                    var mv = mvs[mb * 4 + slot];
                    var parity = slot >>> 1;
                    var side = (slot & 1) * 8;
                    frameBlock(reference, 0, x0 + side, y0 + parity, 8, 8, BlockState.mvx(mv), BlockState.mvy(mv),
                            lumaOut, parity * 16 + side, true);
                    var cx = chromaComponent(BlockState.mvx(mv));
                    var cy = frameChromaY(BlockState.mvy(mv), true);
                    frameBlock(reference, 1, x0 / 2 + side / 2, y0 / 2 + parity, 4, 4, cx, cy, chromaOut,
                            parity * 16 + side / 2, true);
                    frameBlock(reference, 2, x0 / 2 + side / 2, y0 / 2 + parity, 4, 4, cx, cy, chromaOut,
                            parity * 16 + 8 + side / 2, true);
                }
            }
        }
    }

    /**
     * Predicts a block of an interlace frame macroblock: a frame block, or the lines of one field when
     * {@code field} is set, written every other row of the output from {@code offset}.
     *
     * @param x the left column of the area in the plane
     * @param y the top row of the area in the frame (for a field area, its first line)
     * @param height the rows of a frame area, or the lines of a field area
     */
    private void frameBlock(Frame reference, int plane, int x, int y, int width, int height, int mvx, int mvy,
                            int[] out, int offset, boolean field) {
        if (reference == null) {
            damage = "VC-1 prediction from a missing reference frame";
            for (var r = 0; r < height; r++) {
                var at = offset + r * (field ? 32 : 16);
                java.util.Arrays.fill(out, at, at + width, 128);
            }
            return;
        }
        var filterType = plane == 0 ? filter : MotionCompensation.BILINEAR;
        if (!field) {
            setReference(reference, plane);
            mc.predict(x, y, width, height, mvx, mvy, filterType, rnd, out, offset, 16);
            return;
        }
        var start = (y * 4 + mvy) >> 2;
        var parity = start & 1;
        var line = start >> 1;
        var stride = plane == 0 ? reference.width : reference.chromaWidth;
        var validWidth = plane == 0 ? reference.validWidth : (reference.validWidth + 1) >> 1;
        var validHeight = plane == 0 ? reference.validHeight : (reference.validHeight + 1) >> 1;
        mc.reference(reference.plane(plane), stride, parity, 2, validWidth, validHeight, reference.interlaced);
        mc.compensation(plane == 0 ? reference.lumaLut[0] : reference.chromaLut[0],
                plane == 0 ? reference.lumaLut[1] : reference.chromaLut[1]);
        mc.predict(x, line, width, height, mvx, mvy & 3, filterType, rnd, out, offset, 32);
    }

    /**
     * Decodes the residual of an inter macroblock of an interlace frame picture.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param cbp the coded block pattern
     */
    private void frameResidual(int mbx, int mby, int cbp) {
        for (var block = 0; block < 6; block++) {
            blockIntra[block] = false;
            blockCoded[block] = ((cbp >>> (5 - block)) & 1) != 0;
        }
        if (cbp != 0) {
            readMquantDifferential();
        }
        decodeInterResidual(mbx, mby, cbp);
    }

    /**
     * Decodes a macroblock of an interlace frame B picture (Table 92).
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @throws MediaException.Malformed if the data is invalid
     */
    private void decodeFrameBMacroblock(int mbx, int mby) {
        var mb = mby * mbWidth + mbx;
        startMacroblockQuant(mbx, mby);
        var skipped = skipPlane.raw() ? bits.readBit() == 1 : skipPlane.get(mbx, mby) != 0;
        int mode = skipped ? FRAME_ONE_MV | 32 : frameModeSymbol();
        var mbType = mode & 7;
        if (mbType == FRAME_INTRA) {
            frameIntraMacroblock(mbx, mby, mb);
            return;
        }
        var direct = directPlane.raw() ? bits.readBit() == 1 : directPlane.get(mbx, mby) != 0;
        var bType = B_DIRECT;
        if (!direct) {
            var code = Vc1Codes.B_MV_TYPE.decode(bits);
            if (code < 0) {
                throw new MediaException.Malformed("invalid VC-1 BMVTYPE");
            }
            var atLeastHalf = header.fractionAtLeastHalf();
            bType = switch (code) {
                case 0 -> atLeastHalf ? B_BACKWARD : B_FORWARD;
                case 1 -> atLeastHalf ? B_FORWARD : B_BACKWARD;
                default -> B_INTERPOLATED;
            };
        }
        var switched = mbType == FRAME_TWO_FIELD && !direct && bType != B_INTERPOLATED && bits.readFlag();
        var transform = (mode >>> 4) & 3;
        var fieldMotion = mbType == FRAME_TWO_FIELD;
        fieldTx[mb] = transform == 1 || (transform == 2 && fieldMotion);
        var cbp = 0;
        var pattern = 0;
        if (!skipped) {
            cbp = transform != 2 ? readFieldCbp() : 0;
            if (!direct) {
                if ((mbType == FRAME_TWO_FIELD && bType != B_INTERPOLATED)
                        || (mbType == FRAME_ONE_MV && bType == B_INTERPOLATED)) {
                    pattern = readPattern(twoMvPatternVlc);
                } else if (mbType == FRAME_TWO_FIELD) {
                    pattern = readPattern(fourMvPatternVlc);
                } else {
                    pattern = (mode & 8) != 0 ? 1 : 0;
                }
            }
        }
        frameType[0][mb] = (byte) mbType;
        frameType[1][mb] = (byte) mbType;
        if (direct) {
            frameDirect(mbx, mby, mbType);
        } else if (bType == B_INTERPOLATED) {
            if (mbType == FRAME_ONE_MV) {
                decodeFrameVectors(0, mbx, mby, mbType, pattern >>> 1, 1);
                decodeFrameVectors(1, mbx, mby, mbType, pattern & 1, 1);
            } else {
                decodeInterpolatedFields(mbx, mby, pattern);
            }
        } else if (!switched) {
            var context = bType == B_FORWARD ? 0 : 1;
            decodeFrameVectors(context, mbx, mby, mbType, pattern, mbType == FRAME_ONE_MV ? 1 : 2);
            fillOther(1 - context, mbx, mby, mbType);
        } else {
            decodeSwitchedFields(mbx, mby, bType == B_FORWARD ? 0 : 1, pattern);
        }
        markFrameMotion(mbx, mby, bType == B_BACKWARD && !switched ? 1 : 0);
        if (switched) {
            var first = bType == B_FORWARD ? 0 : 1;
            frameFieldPart(first, mbx, mby, 0, prediction, prediction2);
            frameFieldPart(1 - first, mbx, mby, 1, prediction, prediction2);
        } else {
            var useForward = bType != B_BACKWARD;
            var useBackward = bType != B_FORWARD;
            if (useForward) {
                frameMotion(0, mbx, mby, mbType, prediction, prediction2);
            }
            if (useBackward) {
                frameMotion(1, mbx, mby, mbType, useForward ? interpolationLuma : prediction,
                        useForward ? interpolationChroma : prediction2);
                if (useForward) {
                    average();
                }
            }
        }
        frameResidual(mbx, mby, cbp);
    }

    /**
     * Predicts one field of a 2 field MV macroblock from one context's vector for that field.
     */
    private void frameFieldPart(int context, int mbx, int mby, int parity, int[] lumaOut, int[] chromaOut) {
        var mb = mby * mbWidth + mbx;
        var mv = frameMv[context][mb * 4 + parity * 2];
        var reference = context == 0 ? forward : backward;
        var x0 = mbx * 16;
        var y0 = mby * 16;
        frameBlock(reference, 0, x0, y0 + parity, 16, 8, BlockState.mvx(mv), BlockState.mvy(mv), lumaOut,
                parity * 16, true);
        var cx = chromaComponent(BlockState.mvx(mv));
        var cy = frameChromaY(BlockState.mvy(mv), true);
        frameBlock(reference, 1, x0 / 2, y0 / 2 + parity, 8, 4, cx, cy, chromaOut, parity * 16, true);
        frameBlock(reference, 2, x0 / 2, y0 / 2 + parity, 8, 4, cx, cy, chromaOut, parity * 16 + 8, true);
    }

    /**
     * Decodes the four vectors of an interpolated 2 field MV macroblock: top forward, top backward, bottom forward,
     * bottom backward.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param pattern the 4MVBP
     */
    private void decodeInterpolatedFields(int mbx, int mby, int pattern) {
        var mb = mby * mbWidth + mbx;
        for (var i = 0; i < 4; i++) {
            var context = i & 1;
            var parity = i >>> 1;
            if (((pattern >>> (3 - i)) & 1) != 0) {
                readFieldMv();
            } else {
                dmvX = 0;
                dmvY = 0;
            }
            predictFrameMv(context, mbx, mby, FRAME_TWO_FIELD, parity * 2);
            var mv = BlockState.pack(smod(dmvX + predictor[0], rangeX), smod(dmvY + predictor[1], rangeY));
            frameMv[context][mb * 4 + parity * 2] = mv;
            frameMv[context][mb * 4 + parity * 2 + 1] = mv;
        }
    }

    /**
     * Decodes the two vectors of a 2 field MV macroblock whose fields predict from different directions (MVSW), each
     * filling its context for both fields.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param topContext the direction of the top field
     * @param pattern the 2MVBP
     */
    private void decodeSwitchedFields(int mbx, int mby, int topContext, int pattern) {
        var mb = mby * mbWidth + mbx;
        for (var parity = 0; parity < 2; parity++) {
            var context = parity == 0 ? topContext : 1 - topContext;
            if (((pattern >>> (1 - parity)) & 1) != 0) {
                readFieldMv();
            } else {
                dmvX = 0;
                dmvY = 0;
            }
            predictFrameMv(context, mbx, mby, FRAME_TWO_FIELD, parity * 2);
            var mv = BlockState.pack(smod(dmvX + predictor[0], rangeX), smod(dmvY + predictor[1], rangeY));
            for (var s = 0; s < 4; s++) {
                frameMv[context][mb * 4 + s] = mv;
            }
        }
    }

    /**
     * Fills the other context of a forward or backward macroblock with its predicted vectors (section 10.8.6.7.1).
     */
    private void fillOther(int context, int mbx, int mby, int mbType) {
        var mb = mby * mbWidth + mbx;
        frameType[context][mb] = (byte) FRAME_TWO_FIELD;
        predictFrameMv(context, mbx, mby, FRAME_TWO_FIELD, 0);
        var top = BlockState.pack(predictor[0], predictor[1]);
        var bottom = top;
        if (mbType == FRAME_TWO_FIELD) {
            predictFrameMv(context, mbx, mby, FRAME_TWO_FIELD, 2);
            bottom = BlockState.pack(predictor[0], predictor[1]);
        }
        frameType[context][mb] = (byte) mbType;
        var slots = frameMv[context];
        slots[mb * 4] = top;
        slots[mb * 4 + 1] = top;
        slots[mb * 4 + 2] = bottom;
        slots[mb * 4 + 3] = bottom;
    }

    /**
     * Derives the direct mode vectors of an interlace frame B macroblock from the buffered vectors of the backward
     * anchor (Figure 150).
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param mbType the macroblock type
     */
    private void frameDirect(int mbx, int mby, int mbType) {
        var mb = mby * mbWidth + mbx;
        var top = 0;
        var bottom = 0;
        if (backward != null && backward.hasMotion && backward.interlaceMv != null && !backward.directIntra[mb]) {
            top = backward.interlaceMv[mb * 2];
            bottom = backward.interlaceMv[mb * 2 + 1];
        }
        var scale = header.scaleFactor;
        for (var parity = 0; parity < 2; parity++) {
            var mv = parity == 0 || mbType == FRAME_ONE_MV ? top : bottom;
            var x = BlockState.mvx(mv);
            var y = BlockState.mvy(mv);
            var forwardMv = BlockState.pack((x * scale + 128) >> 8, (y * scale + 128) >> 8);
            var backwardMv = BlockState.pack((x * (scale - 256) + 128) >> 8, (y * (scale - 256) + 128) >> 8);
            frameMv[0][mb * 4 + parity * 2] = forwardMv;
            frameMv[0][mb * 4 + parity * 2 + 1] = forwardMv;
            frameMv[1][mb * 4 + parity * 2] = backwardMv;
            frameMv[1][mb * 4 + parity * 2 + 1] = backwardMv;
        }
    }

    /**
     * Records the top and bottom field vectors of an interlace frame P picture's macroblocks for the direct mode of
     * later B pictures (section 10.8.6.6).
     */
    private void bufferFrameInterlaceMotion() {
        var mbs = mbWidth * mbHeight;
        if (current.interlaceMv == null || current.interlaceMv.length < mbs * 2) {
            current.interlaceMv = new int[mbs * 2];
        }
        for (var mb = 0; mb < mbs; mb++) {
            var t = frameType[0][mb];
            current.directIntra[mb] = t == FRAME_INTRA;
            var mvs = frameMv[0];
            current.interlaceMv[mb * 2] = t == FRAME_INTRA ? 0 : mvs[mb * 4];
            current.interlaceMv[mb * 2 + 1] = t == FRAME_INTRA ? 0 : mvs[mb * 4 + 2];
        }
        current.hasMotion = true;
    }

    /**
     * The offsets of the field motion vector differential components (Figure 113).
     */
    private static final int[] FIELD_OFFSETS_NORMAL = {0, 1, 2, 4, 8, 16, 32, 64, 128};

    /**
     * The offsets of the extended range field motion vector differential components (Figure 113).
     */
    private static final int[] FIELD_OFFSETS_EXTENDED = {0, 1, 3, 7, 15, 31, 63, 127, 255};

    /**
     * The bit counts of the vertical differential of a two-reference field motion vector, by size index (Figure 114).
     */
    private static final int[] FIELD_SIZES = {0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7};

    /**
     * The P field picture predictor scaling of Table 113 (first field) and Table 114 (second field), indexed by the
     * reference distance (3 or more share the last column): SCALEOPP, SCALESAME1, SCALESAME2, SCALEZONE1_X,
     * SCALEZONE1_Y, ZONE1OFFSET_X, ZONE1OFFSET_Y.
     */
    private static final int[][][] P_FIELD_SCALING = {
            {{128, 512, 219, 32, 8, 37, 10}, {192, 341, 236, 48, 12, 20, 5}, {213, 307, 242, 53, 13, 14, 4},
                    {224, 293, 245, 56, 14, 11, 3}},
            {{128, 512, 219, 32, 8, 37, 10}, {64, 1024, 204, 16, 4, 52, 13}, {43, 1536, 200, 11, 3, 56, 14},
                    {32, 2048, 198, 8, 2, 58, 15}}
    };

    /**
     * The first B field backward predictor scaling of Table 115, indexed by the backward reference distance:
     * SCALESAME, SCALEOPP1, SCALEOPP2, SCALEZONE1_X, SCALEZONE1_Y, ZONE1OFFSET_X, ZONE1OFFSET_Y.
     */
    private static final int[][] B_FIELD_BACKWARD_SCALING = {
            {171, 384, 230, 43, 11, 26, 7}, {205, 320, 239, 51, 13, 17, 4}, {219, 299, 244, 55, 14, 12, 3},
            {228, 288, 246, 57, 14, 10, 3}
    };

    /**
     * A predictor candidate outside the picture or slice.
     */
    private static final int OUT = 0;

    /**
     * An intra coded predictor candidate.
     */
    private static final int INTRA = 1;

    /**
     * An inter coded predictor candidate.
     */
    private static final int INTER = 2;

    /**
     * The field of the current interlace field picture, 0 for the top field, or -1 for a frame picture.
     */
    private int fieldParity = -1;

    /**
     * Whether the field is the second field of its frame.
     */
    private boolean secondField;

    /**
     * The index of the first luma sample of the picture in its frame.
     */
    private int lumaBase;

    /**
     * The distance between the luma rows of the picture: twice the frame width for a field.
     */
    private int lumaPitch;

    /**
     * The index of the first color-difference sample of the picture in its frame.
     */
    private int chromaBase;

    /**
     * The distance between the color-difference rows of the picture.
     */
    private int chromaPitch;

    /**
     * Whether inter blocks use the interlace scans (Tables 242 to 245).
     */
    private boolean interlaceScans;

    /**
     * The reference fields of a field picture: index 0 the same polarity field and 1 the opposite polarity field for
     * forward prediction, 2 and 3 for backward prediction.
     */
    private final Frame[] referenceFrames = new Frame[4];

    /**
     * The parity of each field of {@link #referenceFrames}.
     */
    private final int[] referenceParities = new int[4];

    /**
     * The field a one-reference P field predicts from: 0 the same polarity, 1 the opposite (REFFIELD).
     */
    private int referencePolarity;

    /**
     * The horizontal DMVRANGE extension, 0 or 1.
     */
    private int extendX;

    /**
     * The vertical DMVRANGE extension, 0 or 1.
     */
    private int extendY;

    /**
     * The MBMODE table of an interlace picture.
     */
    private Vlc mbModeVlc;

    /**
     * The 4MVBP table.
     */
    private Vlc fourMvPatternVlc;

    /**
     * The MVDATA table of an interlace picture.
     */
    private Vlc fieldMvVlc;

    /**
     * The CBPCY table of an interlace picture.
     */
    private Vlc fieldCbpVlc;

    /**
     * Whether the P field picture mixes 1-MV and 4-MV macroblocks.
     */
    private boolean mixedMv;

    /**
     * Whether the field picture predicts from two reference fields.
     */
    private boolean twoReferences;

    /**
     * The FORWARDMB bitplane of a B field picture.
     */
    private final Bitplane forwardPlane = new Bitplane();

    /**
     * The predictor scaling of the two prediction contexts (0 forward, 1 backward), and whether the context uses the
     * scaling of Figure 127 instead of Figure 119.
     */
    private final int[][] contextScaling = new int[2][];

    /**
     * Whether each context scales with Figure 127 (the backward context of a first B field).
     */
    private final boolean[] contextBackwardScaling = new boolean[2];

    /**
     * The motion vector of each luma block of the field in each prediction context.
     */
    private int[][] contextMv = new int[2][0];

    /**
     * Whether each vector of {@link #contextMv} refers to the opposite polarity field.
     */
    private byte[][] contextOpposite = new byte[2][0];

    /**
     * The predictor flag of the last two-reference MVDATA: whether the predictor of the other polarity is used.
     */
    private int predictorFlag;

    /**
     * The polarity of the last field motion vector predicted.
     */
    private int predictedOpposite;

    /**
     * The dominant polarity of the last field candidates, which a filled context entry takes.
     */
    private int dominantOpposite;

    /**
     * The polarity of the last field direct mode vectors.
     */
    private int fieldDirectOpposite;

    /**
     * The forward reference frame distance of a B field picture.
     */
    private int forwardDistance;

    /**
     * The backward reference frame distance of a B field picture.
     */
    private int backwardDistance;

    /**
     * The state of the A, B and C candidates of a field predictor: {@link #OUT}, {@link #INTRA} or {@link #INTER}.
     */
    private final int[] candidateState = new int[3];

    /**
     * The horizontal components of the candidates.
     */
    private final int[] candidateX = new int[3];

    /**
     * The vertical components of the candidates.
     */
    private final int[] candidateY = new int[3];

    /**
     * Whether each candidate refers to the opposite polarity field.
     */
    private final int[] candidateOpposite = new int[3];

    /**
     * The horizontal components of the candidates scaled to the same polarity field.
     */
    private final int[] sameX = new int[3];

    /**
     * The vertical components of the candidates scaled to the same polarity field.
     */
    private final int[] sameY = new int[3];

    /**
     * The horizontal components of the candidates scaled to the opposite polarity field.
     */
    private final int[] oppositeX = new int[3];

    /**
     * The vertical components of the candidates scaled to the opposite polarity field.
     */
    private final int[] oppositeY = new int[3];

    /**
     * The polarity of the vectors of the four blocks of a 4-MV field macroblock.
     */
    private final int[] blockOpposite = new int[4];

    /**
     * The horizontal components of the motion vectors of one polarity that a median is taken over.
     */
    private final int[] selectedX = new int[4];

    /**
     * The vertical components matching {@link #selectedX}.
     */
    private final int[] selectedY = new int[4];

    /**
     * Prepares the decoding of one field of an interlace field picture: the header has been parsed up to and
     * including POSTPROC, and this reads the rest of the field picture layer.
     *
     * @param sequence the sequence header in force
     * @param entry the entry point controls
     * @param header the field's header
     * @param current the frame the field is decoded into
     * @param parity the field, 0 for the top field
     * @param second whether the field is the second field of the frame
     * @param references the reference fields, as {@link #referenceFrames} orders them, entries {@code null} when
     * absent
     * @param parities the parity of each reference field
     * @param mbWidth the macroblock columns
     * @param mbHeight the macroblock rows of the field
     * @param rnd the rounding control
     * @param backwardDistanceCode the REFDIST of the backward reference frame, for B fields
     * @throws MediaException.Malformed if the header is invalid
     */
    void beginField(SequenceHeader sequence, EntryPoint entry, PictureHeader header, Frame current, int parity,
                    boolean second, Frame[] references, int[] parities, int mbWidth, int mbHeight, int rnd,
                    int backwardDistanceCode) {
        this.sequence = sequence;
        this.entry = entry;
        this.header = header;
        this.current = current;
        this.forward = references[0];
        this.backward = references[2];
        this.advanced = true;
        this.mbWidth = mbWidth;
        this.mbHeight = mbHeight;
        this.type = header.type;
        this.pquant = header.pquant;
        this.rnd = rnd;
        this.damage = null;
        this.fieldParity = parity;
        this.secondField = second;
        System.arraycopy(references, 0, referenceFrames, 0, 4);
        System.arraycopy(parities, 0, referenceParities, 0, 4);
        frameInterlace = false;
        lumaBase = parity * current.width;
        lumaPitch = current.width * 2;
        chromaBase = parity * current.chromaWidth;
        chromaPitch = current.chromaWidth * 2;
        interlaceScans = true;
        allocate();
        var blocks = mbWidth * mbHeight * 4;
        for (var context = 0; context < 2; context++) {
            if (contextMv[context].length < blocks) {
                contextMv[context] = new int[blocks];
                contextOpposite[context] = new byte[blocks];
            }
            java.util.Arrays.fill(contextMv[context], 0, blocks, 0);
            java.util.Arrays.fill(contextOpposite[context], 0, blocks, (byte) 0);
        }
        if (type == PictureHeader.I || type == PictureHeader.BI) {
            parseRest(true);
        } else {
            parseFieldInterHeader(backwardDistanceCode);
        }
        startSlice(0);
    }

    /**
     * Reads the repeated frame and field headers of a slice of an interlace field picture, which carry the values of
     * the field's headers.
     *
     * @param repeat the parsed start of the repeated headers, up to and including POSTPROC of the field header
     * @param backwardDistanceCode the REFDIST of the backward reference frame, for B fields
     * @throws MediaException.Malformed if the header is invalid
     */
    void skipRepeatedFieldHeader(PictureHeader repeat, int backwardDistanceCode) {
        var saved = header;
        header = repeat;
        try {
            if (type == PictureHeader.I || type == PictureHeader.BI) {
                parseRest(false);
            } else {
                repeatedHeader = true;
                parseFieldInterHeader(backwardDistanceCode);
            }
        } finally {
            repeatedHeader = false;
            header = saved;
        }
    }

    /**
     * Whether the header being read is a repeated one, whose intensity compensation is already applied.
     */
    private boolean repeatedHeader;

    /**
     * Reads the rest of a P or B field header (Tables 88 and 89).
     *
     * @param backwardDistanceCode the REFDIST of the backward reference frame, for B fields
     * @throws MediaException.Malformed if the header is invalid
     */
    private void parseFieldInterHeader(int backwardDistanceCode) {
        dquantFrame = false;
        condover = 0;
        intensity = false;
        ttmbf = true;
        ttfrm = Vc1Codes.TT_8X8;
        var p = type == PictureHeader.P;
        referencePolarity = 0;
        twoReferences = !p || bits.readBit() == 1;
        if (!twoReferences) {
            referencePolarity = bits.readBit() == 0 ? 1 : 0;
        }
        var mvRange = entry.extendedMv ? Vc1Codes.MV_RANGE.decode(bits) : 0;
        if (mvRange < 0) {
            throw new MediaException.Malformed("invalid VC-1 MVRANGE");
        }
        header.mvRange = mvRange;
        extendX = 0;
        extendY = 0;
        if (entry.extendedDmv) {
            var code = Vc1Codes.DMV_RANGE.decode(bits);
            extendX = code & 1;
            extendY = code >>> 1;
        }
        var low = pquant > 12;
        if (p) {
            mvMode = (low ? Vc1Codes.MV_MODE_LOW_RATE : Vc1Codes.MV_MODE_HIGH_RATE).decode(bits);
            if (mvMode == Vc1Codes.MV_INTENSITY) {
                mvMode = (low ? Vc1Codes.MV_MODE2_LOW_RATE : Vc1Codes.MV_MODE2_HIGH_RATE).decode(bits);
                intensity = true;
                var fields = Vc1Codes.INTENSITY_FIELDS.decode(bits);
                lumScale = bits.read(6);
                lumShift = bits.read(6);
                var scale2 = 0;
                var shift2 = 0;
                if (fields == 0) {
                    scale2 = bits.read(6);
                    shift2 = bits.read(6);
                }
                intensityFields = fields == 0 ? 3 : fields == 1 ? 1 : 2;
                lumScale2 = scale2;
                lumShift2 = shift2;
            }
        } else {
            mvMode = (low ? Vc1Codes.B_FIELD_MV_MODE_LOW_RATE : Vc1Codes.B_FIELD_MV_MODE_HIGH_RATE).decode(bits);
            forwardPlane.decode(bits, mbWidth, mbHeight);
        }
        if (mvMode < 0) {
            throw new MediaException.Malformed("invalid VC-1 MVMODE");
        }
        mixedMv = mvMode == Vc1Codes.MV_MIXED;
        var modeTable = bits.read(3);
        mbModeVlc = mixedMv ? Vc1Codes.FIELD_MIXED_MV_MBMODE[modeTable] : Vc1Codes.FIELD_ONE_MV_MBMODE[modeTable];
        fieldMvVlc = twoReferences ? Vc1Codes.TWO_REF_MV[bits.read(3)] : Vc1Codes.ONE_REF_MV[bits.read(2)];
        fieldCbpVlc = Vc1Codes.INTERLACED_CBPCY[bits.read(3)];
        if (mixedMv) {
            fourMvPatternVlc = Vc1Codes.FOUR_MV_BLOCK_PATTERN[bits.read(2)];
        }
        if (entry.dquant != 0) {
            parseVopDquant();
        }
        if (entry.variableTransform) {
            ttmbf = bits.readFlag();
            if (ttmbf) {
                ttfrm = bits.read(2);
            }
        }
        var index = transformAcIndex();
        lumaIntraSet = Vc1Codes.intraSet(index, header.pqindex);
        chromaIntraSet = Vc1Codes.interSet(index, header.pqindex);
        interSet = chromaIntraSet;
        dcTable = bits.readBit();
        overlapActive = p && entry.overlap && pquant >= 9;
        intraOffset = 128;
        halfpel = mvMode == Vc1Codes.MV_1_HALF || mvMode == Vc1Codes.MV_1_HALF_BILINEAR;
        filter = mvMode == Vc1Codes.MV_1_HALF_BILINEAR ? MotionCompensation.BILINEAR : MotionCompensation.BICUBIC;
        kx = K_X[mvRange];
        ky = K_Y[mvRange];
        rangeX = RANGE_X[mvRange];
        rangeY = RANGE_Y[mvRange];
        var table = secondField ? 1 : 0;
        if (p) {
            contextScaling[0] = P_FIELD_SCALING[table][Math.min(3, header.referenceDistance)];
            contextBackwardScaling[0] = false;
            if (intensity && !repeatedHeader) {
                applyFieldIntensityCompensation();
            }
        } else {
            forwardDistance = (header.bNumerator * INVERSE_DENOMINATOR[header.bDenominator - 1]
                    * backwardDistanceCode) >> 8;
            backwardDistance = Math.max(0, backwardDistanceCode - forwardDistance - 1);
            contextScaling[0] = P_FIELD_SCALING[table][Math.min(3, forwardDistance)];
            contextBackwardScaling[0] = false;
            if (secondField) {
                contextScaling[1] = P_FIELD_SCALING[0][Math.min(3, backwardDistance)];
                contextBackwardScaling[1] = false;
            } else {
                contextScaling[1] = B_FIELD_BACKWARD_SCALING[Math.min(3, backwardDistance)];
                contextBackwardScaling[1] = true;
            }
        }
        if (bits.overrun()) {
            throw new MediaException.Malformed("truncated VC-1 field picture header");
        }
    }

    /**
     * FrameReciprocal of each BFRACTION denominator 1 to 8 (section 8.4.5.4).
     */
    private static final int[] INVERSE_DENOMINATOR = {256, 128, 85, 64, 51, 43, 37, 32};

    /**
     * The fields INTFLD compensates: bit 0 the top field, bit 1 the bottom field.
     */
    private int intensityFields;

    /**
     * LUMSCALE2, of the second field compensated.
     */
    private int lumScale2;

    /**
     * LUMSHIFT2, of the second field compensated.
     */
    private int lumShift2;

    /**
     * Applies the intensity compensation of a P field to its reference fields.
     */
    private void applyFieldIntensityCompensation() {
        intensityTables(lumScale, lumShift, icLuma[0], icChroma[0]);
        intensityTables(lumScale2, lumShift2, icLuma[1], icChroma[1]);
        var first = true;
        for (var fieldPolarity = 0; fieldPolarity < 2; fieldPolarity++) {
            if ((intensityFields & (1 << fieldPolarity)) == 0) {
                continue;
            }
            var reference = fieldPolarity == fieldParity ? 0 : 1;
            var frame = referenceFrames[reference];
            if (frame != null) {
                var table = intensityFields == 3 && !first ? 1 : 0;
                frame.compensate(referenceParities[reference], icLuma[table], icChroma[table]);
            }
            first = false;
        }
    }

    /**
     * Fills the intensity compensation tables of LUMSCALE and LUMSHIFT (section 8.3.8).
     *
     * @param scaleCode LUMSCALE
     * @param shiftCode LUMSHIFT
     * @param lumaTable receives the luma table
     * @param chromaTable receives the color-difference table
     */
    private static void intensityTables(int scaleCode, int shiftCode, byte[] lumaTable, byte[] chromaTable) {
        int scale;
        int shift;
        if (scaleCode == 0) {
            scale = -64;
            shift = 255 * 64 - shiftCode * 2 * 64;
            if (shiftCode > 31) {
                shift += 128 * 64;
            }
        } else {
            scale = scaleCode + 32;
            shift = shiftCode > 31 ? shiftCode * 64 - 64 * 64 : shiftCode * 64;
        }
        for (var i = 0; i < 256; i++) {
            lumaTable[i] = (byte) Frame.clip((scale * i + shift + 32) >> 6);
            chromaTable[i] = (byte) Frame.clip((scale * (i - 128) + 128 * 64 + 32) >> 6);
        }
    }

    /**
     * Reads a field motion vector differential (Figures 113 and 114) into {@link #dmvX}, {@link #dmvY} and
     * {@link #predictorFlag}.
     */
    private void readFieldMv() {
        var index = fieldMvVlc.decode(bits);
        if (index < 0) {
            throw new MediaException.Malformed("invalid VC-1 field motion vector code");
        }
        predictorFlag = 0;
        if (!twoReferences) {
            if (index == 71) {
                dmvX = bits.read(kx);
                dmvY = bits.read(ky);
                return;
            }
            var index1 = (index + 1) % 9;
            dmvX = index1 != 0 ? fieldComponent(index1 + extendX, extendX, index1) : 0;
            index1 = (index + 1) / 9;
            dmvY = index1 != 0 ? fieldComponent(index1 + extendY, extendY, index1) : 0;
            return;
        }
        if (index == 125) {
            dmvX = bits.read(kx);
            dmvY = bits.read(ky);
            predictorFlag = dmvY & 1;
            dmvY = (dmvY + predictorFlag) >> 1;
            return;
        }
        var index1 = (index + 1) % 9;
        dmvX = index1 != 0 ? fieldComponent(index1 + extendX, extendX, index1) : 0;
        index1 = (index + 1) / 9;
        if (index1 > 1) {
            dmvY = fieldComponent(FIELD_SIZES[index1 + 2 * extendY], extendY, index1 >> 1);
        } else {
            dmvY = 0;
        }
        predictorFlag = index1 & 1;
    }

    /**
     * Reads one component of a field motion vector differential (Figure 113).
     *
     * @param size the bits
     * @param extend 1 for the extended range offsets
     * @param offsetIndex the offset index
     * @return the differential
     */
    private int fieldComponent(int size, int extend, int offsetIndex) {
        var value = bits.read(size);
        var sign = -(value & 1);
        var table = extend == 1 ? FIELD_OFFSETS_EXTENDED : FIELD_OFFSETS_NORMAL;
        return (sign ^ ((value >> 1) + table[offsetIndex])) - sign;
    }

    /**
     * Gathers the A, B and C predictor candidates of a field picture luma block (Figures 115 and 116).
     */
    private void fieldCandidates(int[] mvs, byte[] opposites, int bx, int by, int block, boolean macroblock, int mby) {
        var topRow = sliceTop[mby] * 2;
        var columns = luma.columns;
        var mbx = bx >> 1;
        int bxCandidate;
        if (macroblock) {
            bxCandidate = mbx == mbWidth - 1 ? bx - 2 : bx + 2;
        } else {
            bxCandidate = switch (block) {
                case 0 -> mbx == 0 ? bx + 1 : bx - 1;
                case 1 -> mbx == mbWidth - 1 ? bx - 1 : bx + 1;
                case 2 -> bx + 1;
                default -> bx - 1;
            };
        }
        candidate(0, mvs, opposites, bx, by - 1, by - 1 >= topRow, columns);
        var bIn = by - 1 >= topRow && bxCandidate >= 0 && bxCandidate < columns && mbWidth > 1;
        candidate(1, mvs, opposites, bxCandidate, by - 1, bIn, columns);
        var cIn = bx - 1 >= 0 && !(mbWidth == 1 && (block == 0 || block == 2 || macroblock));
        candidate(2, mvs, opposites, bx - 1, by, cIn, columns);
    }

    /**
     * Records one predictor candidate of a field picture.
     *
     * @param slot 0 for A, 1 for B, 2 for C
     * @param mvs the vectors of the context
     * @param opposites the polarities of the context
     * @param x the block column
     * @param y the block row
     * @param inside whether the block is inside the picture and slice
     * @param columns the block columns
     */
    private void candidate(int slot, int[] mvs, byte[] opposites, int x, int y, boolean inside, int columns) {
        if (!inside) {
            candidateState[slot] = OUT;
            candidateX[slot] = 0;
            candidateY[slot] = 0;
            candidateOpposite[slot] = 0;
            return;
        }
        var index = y * columns + x;
        if (luma.intra[index]) {
            candidateState[slot] = INTRA;
            candidateX[slot] = 0;
            candidateY[slot] = 0;
            candidateOpposite[slot] = 0;
            return;
        }
        candidateState[slot] = INTER;
        candidateX[slot] = BlockState.mvx(mvs[index]);
        candidateY[slot] = BlockState.mvy(mvs[index]);
        candidateOpposite[slot] = opposites[index];
    }

    /**
     * Computes the motion vector predictor of a field picture luma block (sections 10.3.5.4.3 and 10.4.6) into
     * {@link #predictor} and the polarity it refers to into {@link #predictedOpposite}, reading HYBRIDPRED for a P
     * field when the rules call for it.
     *
     * @param context the prediction context, 0 forward (and P fields), 1 backward
     * @param flag the predictor flag of the motion vector differential, 0 for the dominant predictor
     * @param hybrid whether hybrid prediction applies (P fields)
     */
    private void predictField(int context, int[] mvs, byte[] opposites, int bx, int by, int block,
                              boolean macroblock, int mby, int flag, boolean hybrid) {
        fieldCandidates(mvs, opposites, bx, by, block, macroblock, mby);
        var aState = candidateState[0];
        var cState = candidateState[2];
        int px;
        int py;
        int ax;
        int ay;
        int cx;
        int cy;
        if (!twoReferences) {
            int[] considered;
            if (aState != OUT) {
                considered = mbWidth == 1 ? ONLY_A : cState != OUT ? THREE : A_AND_B;
            } else {
                considered = ONLY_C;
            }
            var count = 0;
            var lastX = 0;
            var lastY = 0;
            for (var slot : considered) {
                if (candidateState[slot] == INTER) {
                    count++;
                    lastX = candidateX[slot];
                    lastY = candidateY[slot];
                }
            }
            if (count >= 2) {
                px = median3(value(candidateX, 0, considered), value(candidateX, 1, considered),
                        value(candidateX, 2, considered));
                py = median3(value(candidateY, 0, considered), value(candidateY, 1, considered),
                        value(candidateY, 2, considered));
            } else {
                px = lastX;
                py = lastY;
            }
            predictedOpposite = referencePolarity;
            ax = candidateX[0];
            ay = candidateY[0];
            cx = candidateX[2];
            cy = candidateY[2];
        } else {
            int[] considered;
            if (aState != OUT) {
                considered = mbWidth == 1 ? ONLY_A : cState != OUT ? THREE : A_AND_B;
            } else {
                considered = ONLY_C;
            }
            var sameCount = 0;
            var oppositeCount = 0;
            var lastSameX = 0;
            var lastSameY = 0;
            var lastOppositeX = 0;
            var lastOppositeY = 0;
            for (var slot = 0; slot < 3; slot++) {
                sameX[slot] = 0;
                sameY[slot] = 0;
                oppositeX[slot] = 0;
                oppositeY[slot] = 0;
            }
            for (var slot : considered) {
                if (candidateState[slot] != INTER) {
                    continue;
                }
                var x = candidateX[slot];
                var y = candidateY[slot];
                if (candidateOpposite[slot] == 0) {
                    sameCount++;
                    sameX[slot] = x;
                    sameY[slot] = y;
                    oppositeX[slot] = scaleOppositeX(context, x);
                    oppositeY[slot] = scaleOppositeY(context, y);
                } else {
                    oppositeCount++;
                    oppositeX[slot] = x;
                    oppositeY[slot] = y;
                    sameX[slot] = scaleSameX(context, x);
                    sameY[slot] = scaleSameY(context, y);
                }
                lastSameX = sameX[slot];
                lastSameY = sameY[slot];
                lastOppositeX = oppositeX[slot];
                lastOppositeY = oppositeY[slot];
            }
            int samePx;
            int samePy;
            int oppositePx;
            int oppositePy;
            if (sameCount + oppositeCount > 1) {
                samePx = median3(sameX[0], sameX[1], sameX[2]);
                samePy = median3(sameY[0], sameY[1], sameY[2]);
                oppositePx = median3(oppositeX[0], oppositeX[1], oppositeX[2]);
                oppositePy = median3(oppositeY[0], oppositeY[1], oppositeY[2]);
            } else {
                samePx = lastSameX;
                samePy = lastSameY;
                oppositePx = lastOppositeX;
                oppositePy = lastOppositeY;
            }
            dominantOpposite = sameCount > oppositeCount ? 0 : 1;
            predictedOpposite = flag == 0 ? dominantOpposite : 1 - dominantOpposite;
            if (predictedOpposite == 0) {
                px = samePx;
                py = samePy;
                ax = sameX[0];
                ay = sameY[0];
                cx = sameX[2];
                cy = sameY[2];
            } else {
                px = oppositePx;
                py = oppositePy;
                ax = oppositeX[0];
                ay = oppositeY[0];
                cx = oppositeX[2];
                cy = oppositeY[2];
            }
        }
        if (hybrid && aState == INTER && cState == INTER) {
            var threshold = halfpel ? 16 : 32;
            var sumA = Math.abs(px - ax) + Math.abs(py - ay);
            var sumC = Math.abs(px - cx) + Math.abs(py - cy);
            if (sumA > threshold || sumC > threshold) {
                if (bits.readBit() == 1) {
                    px = ax;
                    py = ay;
                } else {
                    px = cx;
                    py = cy;
                }
            }
        }
        predictor[0] = px;
        predictor[1] = py;
    }

    /**
     * The candidates A, B and C.
     */
    private static final int[] THREE = {0, 1, 2};

    /**
     * The candidates A and B.
     */
    private static final int[] A_AND_B = {0, 1};

    /**
     * The candidate A alone.
     */
    private static final int[] ONLY_A = {0};

    /**
     * The candidate C alone.
     */
    private static final int[] ONLY_C = {2};

    /**
     * Returns a candidate component if it is considered, 0 otherwise.
     *
     * @param values the components
     * @param slot the candidate
     * @param considered the candidates considered
     * @return the component or 0
     */
    private static int value(int[] values, int slot, int[] considered) {
        for (var candidate : considered) {
            if (candidate == slot) {
                return values[slot];
            }
        }
        return 0;
    }

    /**
     * Scales a horizontal candidate component to the opposite polarity field.
     *
     * @param context the prediction context
     * @param n the component
     * @return the scaled component
     */
    private int scaleOppositeX(int context, int n) {
        var table = contextScaling[context];
        if (contextBackwardScaling[context]) {
            return zone(n, false, table[1], table[2], table[3], table[5], true);
        }
        return (n * table[0]) >> 8;
    }

    /**
     * Scales a vertical candidate component to the opposite polarity field.
     *
     * @param context the prediction context
     * @param n the component
     * @return the scaled component
     */
    private int scaleOppositeY(int context, int n) {
        var table = contextScaling[context];
        if (contextBackwardScaling[context]) {
            return zone(n, true, table[1], table[2], table[4], table[6], true);
        }
        return (n * table[0]) >> 8;
    }

    /**
     * Scales a horizontal candidate component to the same polarity field.
     *
     * @param context the prediction context
     * @param n the component
     * @return the scaled component
     */
    private int scaleSameX(int context, int n) {
        var table = contextScaling[context];
        if (contextBackwardScaling[context]) {
            return (n * table[0]) >> 8;
        }
        return zone(n, false, table[1], table[2], table[3], table[5], false);
    }

    /**
     * Scales a vertical candidate component to the same polarity field.
     *
     * @param context the prediction context
     * @param n the component
     * @return the scaled component
     */
    private int scaleSameY(int context, int n) {
        var table = contextScaling[context];
        if (contextBackwardScaling[context]) {
            return (n * table[0]) >> 8;
        }
        return zone(n, true, table[1], table[2], table[4], table[6], false);
    }

    /**
     * Scales a candidate component to the other field (Figures 119 and 127): linear within the first zone, offset
     * beyond it, unchanged past 255 (63 vertically), then clamped to the motion vector range of the target field.
     *
     * @param opposite whether the target is the opposite polarity field
     */
    private int zone(int n, boolean vertical, int scale1, int scale2, int zone1, int offset, boolean opposite) {
        int value;
        if (Math.abs(n) > (vertical ? 63 : 255)) {
            value = n;
        } else if (Math.abs(n) < zone1) {
            value = (n * scale1) >> 8;
        } else if (n < 0) {
            value = ((n * scale2) >> 8) - offset;
        } else {
            value = ((n * scale2) >> 8) + offset;
        }
        if (!vertical) {
            return Math.clamp(value, -rangeX, rangeX - 1);
        }
        var half = rangeY / 2;
        if (fieldParity == 1 && opposite) {
            return Math.clamp(value, -half + 1, half);
        }
        return Math.clamp(value, -half, half - 1);
    }

    /**
     * Wraps a value into a signed range (the smod operation of section 10.7.3.7).
     *
     * @param value the value before the operation
     * @param range the range, a power of two
     * @return the value within minus the range to the range minus 1
     */
    private static int smod(int value, int range) {
        return ((value + range) & (2 * range - 1)) - range;
    }

    /**
     * Reconstructs a field motion vector from the differential and the predictor (section 10.3.5.4.4.1).
     */
    private int reconstructField(int opposite) {
        var mvx = smod(dmvX + predictor[0], rangeX);
        var range = twoReferences ? rangeY / 2 : rangeY;
        int mvy;
        if (opposite == 1 && fieldParity == 1) {
            mvy = smod(dmvY + predictor[1] - 1, range) + 1;
        } else {
            mvy = smod(dmvY + predictor[1], range);
        }
        return BlockState.pack(mvx, mvy);
    }

    /**
     * Selects a reference field for motion compensation.
     *
     * @param reference the index into {@link #referenceFrames}
     * @param plane the plane index, 0 luma, 1 and 2 color-difference
     * @return whether the reference is present
     */
    private boolean setFieldReference(int reference, int plane) {
        var frame = referenceFrames[reference];
        if (frame == null) {
            return false;
        }
        var parity = referenceParities[reference];
        var width = plane == 0 ? frame.width : frame.chromaWidth;
        var validWidth = plane == 0 ? frame.validWidth : (frame.validWidth + 1) >> 1;
        var validHeight = plane == 0 ? frame.validHeight : (frame.validHeight + 1) >> 1;
        var interlaced = frame == current || frame.interlaced;
        mc.reference(frame.plane(plane), width, parity, 2, validWidth, validHeight, interlaced);
        var luts = plane == 0 ? frame.lumaLut : frame.chromaLut;
        mc.compensation(luts[0], luts[1]);
        return true;
    }

    /**
     * Predicts a field picture block from a reference field.
     *
     * @param reference the index into {@link #referenceFrames}
     * @param opposite whether the reference has the opposite polarity, which shifts the vertical vector by half a
     * field line
     * @param x the block's left column in the field
     * @param y the block's top line in the field
     * @param mvx the horizontal vector, quarter samples
     * @param mvy the vertical vector, quarter field lines
     */
    private void fieldBlock(int reference, boolean opposite, int plane, int x, int y, int width, int height, int mvx,
                            int mvy, int filterType, int[] out, int offset) {
        if (!setFieldReference(reference, plane)) {
            damage = "VC-1 field prediction from a missing reference field";
            for (var r = 0; r < height; r++) {
                java.util.Arrays.fill(out, offset + r * 16, offset + r * 16 + width, 128);
            }
            return;
        }
        if (opposite) {
            mvy += fieldParity == 0 ? -2 : 2;
        }
        mc.predict(x, y, width, height, mvx, mvy, filterType, rnd, out, offset, 16);
    }

    /**
     * Converts a motion vector component to quarter samples.
     *
     * @param value the component in the picture's units
     * @return the component in quarter samples
     */
    private int lumaQuarter(int value) {
        return halfpel ? value * 2 : value;
    }

    /**
     * Predicts a whole 1-MV macroblock of a field picture.
     *
     * @param direction 0 forward (and P fields), 1 backward
     * @param mv the luma vector in the picture's units
     * @param opposite whether the vector refers to the opposite polarity field
     */
    private void fieldMacroblock(int direction, int mbx, int mby, int mv, int opposite, int[] lumaOut,
                                 int[] chromaOut) {
        var mvx = lumaQuarter(BlockState.mvx(mv));
        var mvy = lumaQuarter(BlockState.mvy(mv));
        var reference = direction * 2 + opposite;
        fieldBlock(reference, opposite == 1, 0, mbx * 16, mby * 16, 16, 16, mvx, mvy, filter, lumaOut, 0);
        var cmvx = chromaComponent(mvx);
        var cmvy = chromaComponent(mvy);
        if (entry.fastUvMc) {
            cmvx = fastRound(cmvx);
            cmvy = fastRound(cmvy);
        }
        fieldBlock(reference, opposite == 1, 1, mbx * 8, mby * 8, 8, 8, cmvx, cmvy, MotionCompensation.BILINEAR,
                chromaOut, 0);
        fieldBlock(reference, opposite == 1, 2, mbx * 8, mby * 8, 8, 8, cmvx, cmvy, MotionCompensation.BILINEAR,
                chromaOut, 8);
        chromaMvFilter[mby * mbWidth + mbx] = BlockState.pack(cmvx, cmvy) ^ (opposite << 31);
    }

    /**
     * Predicts a 4-MV macroblock of a field picture from the four luma vectors in {@link #blockMv} and their
     * polarities in {@link #blockOpposite}.
     */
    private void fieldFourMv(int direction, int mbx, int mby) {
        for (var block = 0; block < 4; block++) {
            var mvx = lumaQuarter(BlockState.mvx(blockMv[block]));
            var mvy = lumaQuarter(BlockState.mvy(blockMv[block]));
            fieldBlock(direction * 2 + blockOpposite[block], blockOpposite[block] == 1, 0,
                    mbx * 16 + (block & 1) * 8, mby * 16 + (block >>> 1) * 8, 8, 8, mvx, mvy, filter, prediction,
                    (block >>> 1) * 8 * 16 + (block & 1) * 8);
        }
        int ix;
        int iy;
        int chromaOpposite;
        if (!twoReferences) {
            ix = median4(lumaQuarter(BlockState.mvx(blockMv[0])), lumaQuarter(BlockState.mvx(blockMv[1])),
                    lumaQuarter(BlockState.mvx(blockMv[2])), lumaQuarter(BlockState.mvx(blockMv[3])));
            iy = median4(lumaQuarter(BlockState.mvy(blockMv[0])), lumaQuarter(BlockState.mvy(blockMv[1])),
                    lumaQuarter(BlockState.mvy(blockMv[2])), lumaQuarter(BlockState.mvy(blockMv[3])));
            chromaOpposite = blockOpposite[0];
        } else {
            var oppositeCount = 0;
            for (var block = 0; block < 4; block++) {
                oppositeCount += blockOpposite[block];
            }
            int polarity;
            if (oppositeCount == 4 || oppositeCount == 0) {
                polarity = oppositeCount == 4 ? 1 : 0;
                ix = median4(lumaQuarter(BlockState.mvx(blockMv[0])), lumaQuarter(BlockState.mvx(blockMv[1])),
                        lumaQuarter(BlockState.mvx(blockMv[2])), lumaQuarter(BlockState.mvx(blockMv[3])));
                iy = median4(lumaQuarter(BlockState.mvy(blockMv[0])), lumaQuarter(BlockState.mvy(blockMv[1])),
                        lumaQuarter(BlockState.mvy(blockMv[2])), lumaQuarter(BlockState.mvy(blockMv[3])));
            } else {
                polarity = oppositeCount == 3 ? 1 : oppositeCount == 1 ? 0 : 0;
                var count = 0;
                var xs = selectedX;
                var ys = selectedY;
                for (var block = 0; block < 4 && count < 3; block++) {
                    if (blockOpposite[block] == polarity) {
                        xs[count] = lumaQuarter(BlockState.mvx(blockMv[block]));
                        ys[count] = lumaQuarter(BlockState.mvy(blockMv[block]));
                        count++;
                    }
                }
                if (count == 3) {
                    ix = median3(xs[0], xs[1], xs[2]);
                    iy = median3(ys[0], ys[1], ys[2]);
                } else {
                    ix = (xs[0] + xs[1]) / 2;
                    iy = (ys[0] + ys[1]) / 2;
                }
            }
            chromaOpposite = polarity;
        }
        var cmvx = chromaComponent(ix);
        var cmvy = chromaComponent(iy);
        if (entry.fastUvMc) {
            cmvx = fastRound(cmvx);
            cmvy = fastRound(cmvy);
        }
        var reference = direction * 2 + chromaOpposite;
        fieldBlock(reference, chromaOpposite == 1, 1, mbx * 8, mby * 8, 8, 8, cmvx, cmvy,
                MotionCompensation.BILINEAR, prediction2, 0);
        fieldBlock(reference, chromaOpposite == 1, 2, mbx * 8, mby * 8, 8, 8, cmvx, cmvy,
                MotionCompensation.BILINEAR, prediction2, 8);
        chromaMvFilter[mby * mbWidth + mbx] = BlockState.pack(cmvx, cmvy) ^ (chromaOpposite << 31);
    }

    /**
     * Decodes an intra macroblock of a P or B field picture.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param mb the macroblock index
     * @param mode the MBMODE symbol, 1 when CBPCY is present
     */
    private void fieldIntraMacroblock(int mbx, int mby, int mb, int mode) {
        mbIntra[mb] = true;
        setLumaMv(mby * 2 * luma.columns + mbx * 2, 0, false);
        readMquantDifferential();
        acPred = bits.readFlag();
        var cbp = mode == 1 ? readFieldCbp() : 0;
        for (var block = 0; block < 6; block++) {
            blockIntra[block] = true;
            decodeIntraBlock(mbx, mby, block, ((cbp >>> (5 - block)) & 1) != 0, false, false);
        }
        chromaMvFilter[mb] = 0;
    }

    /**
     * Reads the CBPCY of an interlace picture.
     *
     * @return the pattern, 1 to 63, block 0 in bit 5
     * @throws MediaException.Malformed if the code is invalid
     */
    private int readFieldCbp() {
        var value = fieldCbpVlc.decode(bits);
        if (value < 0) {
            throw new MediaException.Malformed("invalid VC-1 interlaced CBPCY");
        }
        return value + 1;
    }

    /**
     * Stores the vector of one luma block in a prediction context.
     *
     * @param mvs the vectors of the context
     * @param opposites the polarities of the context
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param block the block of the macroblock, 0 to 3 luma, 4 and 5 color-difference
     * @param mv the vector
     * @param opposite the polarity
     */
    private void storeBlockMotion(int[] mvs, byte[] opposites, int mbx, int mby, int block, int mv, int opposite) {
        var index = (mby * 2 + (block >>> 1)) * luma.columns + mbx * 2 + (block & 1);
        mvs[index] = mv;
        opposites[index] = (byte) opposite;
    }

    /**
     * Decodes the residual of an inter macroblock of a field picture.
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @param cbp the coded block pattern
     */
    private void fieldResidual(int mbx, int mby, int cbp) {
        for (var block = 0; block < 6; block++) {
            blockIntra[block] = false;
            blockCoded[block] = ((cbp >>> (5 - block)) & 1) != 0;
        }
        if (cbp != 0) {
            readMquantDifferential();
        }
        decodeInterResidual(mbx, mby, cbp);
    }

    /**
     * Decodes a macroblock of a P field picture (Table 94).
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @throws MediaException.Malformed if the data is invalid
     */
    private void decodeFieldPMacroblock(int mbx, int mby) {
        var mb = mby * mbWidth + mbx;
        startMacroblockQuant(mbx, mby);
        var mode = mbModeVlc.decode(bits);
        if (mode < 0) {
            throw new MediaException.Malformed("invalid VC-1 field MBMODE");
        }
        if (mode < 2) {
            fieldIntraMacroblock(mbx, mby, mb, mode);
            return;
        }
        var bx = mbx * 2;
        var by = mby * 2;
        var fourMv = mode >= 6;
        mbFourMv[mb] = fourMv;
        boolean hasCbp;
        if (!fourMv) {
            var hasMv = mode == 3 || mode == 5;
            hasCbp = mode >= 4;
            if (hasMv) {
                readFieldMv();
            } else {
                dmvX = 0;
                dmvY = 0;
                predictorFlag = 0;
            }
            predictField(0, luma.mv, luma.opposite, bx, by, 0, true, mby, predictorFlag, true);
            var opposite = predictedOpposite;
            var mv = reconstructField(opposite);
            for (var block = 0; block < 4; block++) {
                storeBlockMotion(luma.mv, luma.opposite, mbx, mby, block, mv, opposite);
                var index = (by + (block >>> 1)) * luma.columns + bx + (block & 1);
                luma.intra[index] = false;
            }
            fieldMacroblock(0, mbx, mby, mv, opposite, prediction, prediction2);
        } else {
            hasCbp = mode == 7;
            var pattern = fourMvPatternVlc.decode(bits);
            if (pattern < 0) {
                throw new MediaException.Malformed("invalid VC-1 4MVBP");
            }
            for (var block = 0; block < 4; block++) {
                var index = (by + (block >>> 1)) * luma.columns + bx + (block & 1);
                luma.intra[index] = false;
                if (((pattern >>> (3 - block)) & 1) != 0) {
                    readFieldMv();
                } else {
                    dmvX = 0;
                    dmvY = 0;
                    predictorFlag = 0;
                }
                predictField(0, luma.mv, luma.opposite, bx + (block & 1), by + (block >>> 1), block, false, mby,
                        predictorFlag, true);
                var opposite = predictedOpposite;
                var mv = reconstructField(opposite);
                storeBlockMotion(luma.mv, luma.opposite, mbx, mby, block, mv, opposite);
                blockMv[block] = mv;
                blockOpposite[block] = opposite;
            }
            fieldFourMv(0, mbx, mby);
        }
        var cbp = hasCbp ? readFieldCbp() : 0;
        fieldResidual(mbx, mby, cbp);
    }

    /**
     * A direct macroblock of a B field picture.
     */
    private static final int B_FIELD_DIRECT = 3;

    /**
     * Decodes a macroblock of a B field picture (Table 95).
     *
     * @param mbx the macroblock column
     * @param mby the macroblock row
     * @throws MediaException.Malformed if the data is invalid
     */
    private void decodeFieldBMacroblock(int mbx, int mby) {
        var mb = mby * mbWidth + mbx;
        startMacroblockQuant(mbx, mby);
        var mode = mbModeVlc.decode(bits);
        if (mode < 0) {
            throw new MediaException.Malformed("invalid VC-1 field MBMODE");
        }
        var bx = mbx * 2;
        var by = mby * 2;
        if (mode < 2) {
            fieldIntraMacroblock(mbx, mby, mb, mode);
            for (var block = 0; block < 4; block++) {
                storeBlockMotion(contextMv[0], contextOpposite[0], mbx, mby, block, 0, 0);
                storeBlockMotion(contextMv[1], contextOpposite[1], mbx, mby, block, 0, 0);
            }
            return;
        }
        var fourMv = mode >= 6;
        mbFourMv[mb] = fourMv;
        var hasCbp = fourMv ? mode == 7 : mode >= 4;
        var hasMv = !fourMv && (mode == 3 || mode == 5);
        var isForward = forwardPlane.raw() ? bits.readBit() == 1 : forwardPlane.get(mbx, mby) != 0;
        int bType;
        if (isForward) {
            bType = B_FORWARD;
        } else if (fourMv) {
            bType = B_BACKWARD;
        } else {
            var code = Vc1Codes.B_FIELD_MV_TYPE.decode(bits);
            if (code < 0) {
                throw new MediaException.Malformed("invalid VC-1 field BMVTYPE");
            }
            bType = code == 0 ? B_BACKWARD : code == 1 ? B_FIELD_DIRECT : B_INTERPOLATED;
        }
        var interpolatedPresent = bType == B_INTERPOLATED && bits.readBit() == 1;
        for (var block = 0; block < 4; block++) {
            var index = (by + (block >>> 1)) * luma.columns + bx + (block & 1);
            luma.intra[index] = false;
        }
        if (!fourMv) {
            int forwardMv = 0;
            int forwardOpposite = 0;
            int backwardMv = 0;
            int backwardOpposite = 0;
            if (bType == B_FIELD_DIRECT) {
                fieldDirect(mbx, mby);
                forwardMv = blockMv[0];
                backwardMv = blockMv[1];
                forwardOpposite = fieldDirectOpposite;
                backwardOpposite = fieldDirectOpposite;
            } else {
                var direction1 = bType == B_BACKWARD ? 1 : 0;
                if (hasMv) {
                    readFieldMv();
                } else {
                    dmvX = 0;
                    dmvY = 0;
                    predictorFlag = 0;
                }
                predictField(direction1, contextMv[direction1], contextOpposite[direction1], bx, by, 0, true, mby,
                        predictorFlag, false);
                var opposite1 = predictedOpposite;
                var mv1 = reconstructField(opposite1);
                if (direction1 == 0) {
                    forwardMv = mv1;
                    forwardOpposite = opposite1;
                } else {
                    backwardMv = mv1;
                    backwardOpposite = opposite1;
                }
                if (bType == B_INTERPOLATED) {
                    if (interpolatedPresent) {
                        readFieldMv();
                    } else {
                        dmvX = 0;
                        dmvY = 0;
                        predictorFlag = 0;
                    }
                    predictField(1, contextMv[1], contextOpposite[1], bx, by, 0, true, mby, predictorFlag, false);
                    backwardOpposite = predictedOpposite;
                    backwardMv = reconstructField(backwardOpposite);
                } else {
                    var other = 1 - direction1;
                    predictField(other, contextMv[other], contextOpposite[other], bx, by, 0, true, mby, 0, false);
                    var filled = BlockState.pack(predictor[0], predictor[1]);
                    if (other == 0) {
                        forwardMv = filled;
                        forwardOpposite = dominantOpposite;
                    } else {
                        backwardMv = filled;
                        backwardOpposite = dominantOpposite;
                    }
                }
            }
            for (var block = 0; block < 4; block++) {
                storeBlockMotion(contextMv[0], contextOpposite[0], mbx, mby, block, forwardMv, forwardOpposite);
                storeBlockMotion(contextMv[1], contextOpposite[1], mbx, mby, block, backwardMv, backwardOpposite);
            }
            var useForward = bType != B_BACKWARD;
            var useBackward = bType != B_FORWARD;
            if (useForward) {
                fieldMacroblock(0, mbx, mby, forwardMv, forwardOpposite, prediction, prediction2);
            }
            if (useBackward) {
                fieldMacroblock(1, mbx, mby, backwardMv, backwardOpposite,
                        useForward ? interpolationLuma : prediction, useForward ? interpolationChroma : prediction2);
                if (useForward) {
                    average();
                }
            }
        } else {
            var direction = bType == B_BACKWARD ? 1 : 0;
            var pattern = fourMvPatternVlc.decode(bits);
            if (pattern < 0) {
                throw new MediaException.Malformed("invalid VC-1 4MVBP");
            }
            for (var block = 0; block < 4; block++) {
                if (((pattern >>> (3 - block)) & 1) != 0) {
                    readFieldMv();
                } else {
                    dmvX = 0;
                    dmvY = 0;
                    predictorFlag = 0;
                }
                predictField(direction, contextMv[direction], contextOpposite[direction], bx + (block & 1),
                        by + (block >>> 1), block, false, mby, predictorFlag, false);
                var opposite = predictedOpposite;
                var mv = reconstructField(opposite);
                storeBlockMotion(contextMv[direction], contextOpposite[direction], mbx, mby, block, mv, opposite);
                blockMv[block] = mv;
                blockOpposite[block] = opposite;
            }
            var other = 1 - direction;
            predictField(other, contextMv[other], contextOpposite[other], bx, by, 0, true, mby, 0, false);
            var filled = BlockState.pack(predictor[0], predictor[1]);
            for (var block = 0; block < 4; block++) {
                storeBlockMotion(contextMv[other], contextOpposite[other], mbx, mby, block, filled, dominantOpposite);
            }
            fieldFourMv(direction, mbx, mby);
        }
        var cbp = hasCbp ? readFieldCbp() : 0;
        fieldResidual(mbx, mby, cbp);
    }

    /**
     * Averages the forward prediction with the backward one of {@link #interpolationLuma} and {@link
     * #interpolationChroma}.
     */
    private void average() {
        for (var i = 0; i < 256; i++) {
            prediction[i] = (prediction[i] + interpolationLuma[i] + 1) >> 1;
        }
        for (var i = 0; i < 128; i++) {
            prediction2[i] = (prediction2[i] + interpolationChroma[i] + 1) >> 1;
        }
    }

    /**
     * Derives the direct mode vectors of a B field macroblock (section 10.4.5.6) into {@link #blockMv} (index 0
     * forward, 1 backward) and their polarity into {@link #fieldDirectOpposite}.
     */
    private void fieldDirect(int mbx, int mby) {
        var anchor = backward;
        var mb = mby * mbWidth + mbx;
        var x = 0;
        var y = 0;
        var opposite = 0;
        var anchorHalfpel = false;
        if (anchor != null && anchor.fieldPicture && anchor.fieldHasMotion[fieldParity]
                && !anchor.fieldIntra[fieldParity][mb]) {
            var mvs = anchor.fieldMv[fieldParity];
            var opposites = anchor.fieldOpposite[fieldParity];
            anchorHalfpel = anchor.fieldHalfpel[fieldParity];
            var base = mb * 4;
            if (!anchor.fieldFourMv[fieldParity][mb]) {
                x = BlockState.mvx(mvs[base]);
                y = BlockState.mvy(mvs[base]);
                opposite = opposites[base];
            } else {
                var oppositeCount = 0;
                for (var block = 0; block < 4; block++) {
                    oppositeCount += opposites[base + block];
                }
                opposite = oppositeCount > 4 - oppositeCount ? 1 : 0;
                var xs = selectedX;
                var ys = selectedY;
                var count = 0;
                for (var block = 0; block < 4; block++) {
                    if (opposites[base + block] == opposite) {
                        xs[count] = BlockState.mvx(mvs[base + block]);
                        ys[count] = BlockState.mvy(mvs[base + block]);
                        count++;
                    }
                }
                if (count == 4) {
                    x = median4(xs[0], xs[1], xs[2], xs[3]);
                    y = median4(ys[0], ys[1], ys[2], ys[3]);
                } else if (count == 3) {
                    x = median3(xs[0], xs[1], xs[2]);
                    y = median3(ys[0], ys[1], ys[2]);
                } else {
                    x = (xs[0] + xs[1]) / 2;
                    y = (ys[0] + ys[1]) / 2;
                }
            }
        }
        var scale = header.scaleFactor;
        var factor = anchorHalfpel ? 2 : 1;
        int fx;
        int fy;
        int bx;
        int by;
        if (halfpel) {
            fx = (x * factor * scale + 255) >> 9;
            fy = (y * factor * scale + 255) >> 9;
            bx = (x * factor * (scale - 256) + 255) >> 9;
            by = (y * factor * (scale - 256) + 255) >> 9;
        } else {
            fx = (x * factor * scale + 128) >> 8;
            fy = (y * factor * scale + 128) >> 8;
            bx = (x * factor * (scale - 256) + 128) >> 8;
            by = (y * factor * (scale - 256) + 128) >> 8;
        }
        blockMv[0] = BlockState.pack(fx, fy);
        blockMv[1] = BlockState.pack(bx, by);
        fieldDirectOpposite = opposite;
    }

    /**
     * Records the motion of a decoded P field for the direct mode of later B fields.
     */
    private void bufferFieldMotion() {
        current.fieldPicture = true;
        var mbs = mbWidth * mbHeight;
        current.fieldMotion(fieldParity, mbs);
        current.fieldHalfpel[fieldParity] = halfpel;
        current.fieldHasMotion[fieldParity] = type == PictureHeader.P;
        var columns = luma.columns;
        for (var mby = 0; mby < mbHeight; mby++) {
            for (var mbx = 0; mbx < mbWidth; mbx++) {
                var mb = mby * mbWidth + mbx;
                var index = mby * 2 * columns + mbx * 2;
                current.fieldIntra[fieldParity][mb] = type != PictureHeader.P || luma.intra[index];
                current.fieldFourMv[fieldParity][mb] = mbFourMv[mb];
                for (var block = 0; block < 4; block++) {
                    var i = index + (block & 1) + (block >>> 1) * columns;
                    current.fieldMv[fieldParity][mb * 4 + block] = luma.mv[i];
                    current.fieldOpposite[fieldParity][mb * 4 + block] = luma.opposite[i];
                }
            }
        }
    }

    /**
     * DQScale of Table 74: the reciprocals 2^18 / step used to scale DC and AC predictors, indexed by the step.
     */
    private static final int[] DQ_SCALE = {
            0, 262144, 131072, 87381, 65536, 52429, 43691, 37449, 32768, 29127, 26214, 23831, 21845, 20165, 18725,
            17476, 16384, 15420, 14564, 13797, 13107, 12483, 11916, 11398, 10923, 10486, 10082, 9709, 9362, 9039,
            8738, 8456, 8192, 7944, 7710, 7490, 7282, 7085, 6899, 6722, 6554, 6394, 6242, 6096, 5958, 5825, 5699,
            5578, 5461, 5350, 5243, 5140, 5041, 4946, 4855, 4766, 4681, 4599, 4520, 4443, 4369, 4297, 4228, 4161
    };
}
