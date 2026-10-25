package com.github.auties00.smalt.video.h263.internal;

import com.github.auties00.smalt.MediaException;
import com.github.auties00.smalt.video.mpeg4.internal.BitReader;
import com.github.auties00.smalt.video.mpeg4.internal.Tables;
import com.github.auties00.smalt.video.mpeg4.internal.Vlc;

import java.util.Arrays;

/**
 * Parses the macroblock and block layers of ITU-T H.263 for every picture type, with the variable length codes of
 * clause 5 and Annexes D, I, M, O, S, T and U, or the arithmetic codes of Annex E, and decodes the motion vectors and
 * coefficients they carry.
 *
 * <p>The parser keeps, for the macroblocks of the current picture parsed so far, what the prediction of later vectors
 * (6.1.1, F.2, O.5, M.2.2) and of Advanced INTRA Coding coefficients (I.3) reads from them. An instance is reused from
 * picture to picture and is not thread-safe.
 */
final class MacroblockParser {
    /**
     * The chrominance quantizer of the Modified Quantization mode (Table T.2), indexed by the luminance quantizer.
     */
    private static final int[] CHROMA_QUANT = {
            0, 1, 2, 3, 4, 5, 6, 6, 7, 8, 9, 9, 10, 10, 11, 11, 12, 12, 12, 13, 13, 13, 14, 14, 14, 14, 14, 15, 15, 15,
            15, 15
    };

    /**
     * The DQUANT differences of Table 13.
     */
    private static final int[] DQUANT = {-1, -2, 1, 2};

    /**
     * The arithmetic code index of a TCOEF escape (E.7).
     */
    private static final int SAC_ESCAPE = 102;

    /**
     * The shared failure of corrupt macroblock data, without a stack trace.
     */
    static final MediaException.Malformed CORRUPT = new MediaException.Malformed("corrupt H.263 macroblock data");

    /**
     * The arithmetic decoder of the Syntax-based Arithmetic Coding mode.
     */
    final ArithmeticDecoder arithmetic = new ArithmeticDecoder();

    /**
     * The stream.
     */
    private BitReader reader;

    /**
     * Whether the symbols are arithmetic coded.
     */
    private boolean sac;

    /**
     * The picture type.
     */
    private int type;

    /**
     * Whether the picture header carried PLUSPTYPE.
     */
    private boolean plus;

    /**
     * Whether the Unrestricted Motion Vector mode is on.
     */
    private boolean umv;

    /**
     * Whether the extended macroblock type 5 exists: PLUSPTYPE with the Advanced Prediction or Deblocking Filter
     * mode.
     */
    private boolean extendedTypes;

    /**
     * Whether four vectors per macroblock are allowed: the Advanced Prediction or Deblocking Filter mode.
     */
    private boolean fourVectors;

    /**
     * Whether the Advanced INTRA Coding mode is on.
     */
    private boolean aic;

    /**
     * Whether the Alternative INTER VLC mode is on.
     */
    private boolean aiv;

    /**
     * Whether the Modified Quantization mode is on.
     */
    private boolean mq;

    /**
     * Whether the Reduced-Resolution Update mode is on.
     */
    private boolean rru;

    /**
     * Whether the picture is a PB-frame of Annex G.
     */
    private boolean pbFrame;

    /**
     * Whether the picture is an Improved PB-frame.
     */
    private boolean improvedPb;

    /**
     * Whether the Slice Structured mode is on.
     */
    private boolean slices;

    /**
     * Whether the macroblocks carry picture reference parameters: the ERPS mode with MRPA set for the segment.
     */
    boolean multipleReferences;

    /**
     * Whether a B-picture predicts backward from two pictures.
     */
    boolean twoBackward;

    /**
     * The temporal distance of the B-part of a PB-frame from the previous picture (TRB).
     */
    private int trb;

    /**
     * The temporal distance between the anchors of a PB-frame (TRD).
     */
    private int trd;

    /**
     * The DBQUANT of a PB- or Improved PB-frame.
     */
    private int dbquant;

    /**
     * The macroblock columns of the coding grid.
     */
    private int columns;

    /**
     * The macroblock rows of the coding grid.
     */
    private int rows;

    /**
     * The video picture segment of every macroblock.
     */
    int[] segments = new int[0];

    /**
     * Whether every macroblock was parsed.
     */
    boolean[] parsed = new boolean[0];

    /**
     * The candidate predictors of the forward or P vectors, four per macroblock.
     */
    private int[] forwardX = new int[0];

    /**
     * The vertical components of {@link #forwardX}.
     */
    private int[] forwardY = new int[0];

    /**
     * The candidate predictors of the backward vectors, one per macroblock.
     */
    private int[] backwardX = new int[0];

    /**
     * The vertical components of {@link #backwardX}.
     */
    private int[] backwardY = new int[0];

    /**
     * The forward vectors of the B-parts of Improved PB-frames, one per macroblock.
     */
    private int[] partX = new int[0];

    /**
     * The vertical components of {@link #partX}.
     */
    private int[] partY = new int[0];

    /**
     * Whether the B-part of each macroblock has a forward vector.
     */
    private boolean[] partForward = new boolean[0];

    /**
     * Whether each macroblock is INTRA coded.
     */
    private boolean[] intra = new boolean[0];

    /**
     * The reconstructed DC coefficient of every block of the Advanced INTRA Coding mode: the luminance blocks, then
     * the Cb and Cr blocks.
     */
    private int[] dcStore = new int[0];

    /**
     * The reconstructed first row of every block, eight per block.
     */
    private int[] rowStore = new int[0];

    /**
     * The reconstructed first column of every block, eight per block.
     */
    private int[] columnStore = new int[0];

    /**
     * The quantizer in effect.
     */
    int quant;

    /**
     * Whether the last macroblock had COD 0 and PR0 1 without the MEPB0 bit.
     */
    private boolean pendingPr0;

    /**
     * The levels of the block being read, in raster order.
     */
    private final int[] levels = new int[64];

    /**
     * Creates a parser.
     */
    MacroblockParser() {
    }

    /**
     * Returns the chrominance quantizer of the Modified Quantization mode (Table T.2).
     *
     * @param quant the luminance quantizer
     * @return the chrominance quantizer
     */
    static int chromaQuant(int quant) {
        return CHROMA_QUANT[quant];
    }

    /**
     * Prepares the parser for a picture.
     *
     * @param header the picture header
     * @param columns the macroblock columns of the coding grid
     * @param rows the macroblock rows of the coding grid
     * @param trb the temporal distance of the B-part of a PB-frame
     * @param trd the temporal distance between the anchors of a PB-frame
     */
    void start(PictureHeader header, int columns, int rows, int trb, int trd) {
        this.type = header.type;
        this.plus = header.plus;
        this.umv = header.has(LayerState.UMV);
        this.sac = header.has(LayerState.SAC);
        var ap = header.has(LayerState.AP);
        var df = header.has(LayerState.DF);
        this.extendedTypes = plus && (ap || df);
        this.fourVectors = ap || df;
        this.aic = header.has(LayerState.AIC);
        this.aiv = header.has(LayerState.AIV);
        this.mq = header.has(LayerState.MQ);
        this.rru = header.reducedResolution;
        this.pbFrame = header.pbFrame;
        this.improvedPb = header.type == PictureHeader.IMPROVED_PB;
        this.slices = header.has(LayerState.SS);
        this.trb = trb;
        this.trd = Math.max(1, trd);
        this.dbquant = header.dbquant;
        this.columns = columns;
        this.rows = rows;
        this.quant = header.quant;
        var count = columns * rows;
        if (segments.length < count) {
            segments = new int[count];
            parsed = new boolean[count];
            forwardX = new int[count * 4];
            forwardY = new int[count * 4];
            backwardX = new int[count];
            backwardY = new int[count];
            partX = new int[count];
            partY = new int[count];
            partForward = new boolean[count];
            intra = new boolean[count];
            dcStore = new int[count * 6];
            rowStore = new int[count * 48];
            columnStore = new int[count * 48];
        }
        Arrays.fill(segments, 0, count, 0);
        Arrays.fill(parsed, 0, count, false);
        Arrays.fill(forwardX, 0, count * 4, 0);
        Arrays.fill(forwardY, 0, count * 4, 0);
        Arrays.fill(backwardX, 0, count, 0);
        Arrays.fill(backwardY, 0, count, 0);
        Arrays.fill(partForward, 0, count, false);
        Arrays.fill(intra, 0, count, false);
        pendingPr0 = false;
    }

    /**
     * Starts a video picture segment: the stream continues at the reader's position, and symbols are arithmetic
     * coded from here on when the mode is on.
     *
     * @param reader the stream, positioned after the header of the segment
     */
    void startSegment(BitReader reader) {
        this.reader = reader;
        pendingPr0 = false;
        if (sac) {
            arithmetic.start(reader);
        }
    }

    /**
     * Returns whether the symbols are arithmetic coded.
     *
     * @return whether they are
     */
    boolean arithmeticCoded() {
        return sac;
    }

    /**
     * Parses one macroblock of the picture.
     *
     * @param mb receives the macroblock, reset to its position
     * @return {@code false} when stuffing ended the segment before the macroblock
     * @throws MediaException.Malformed if the data is corrupt
     */
    boolean parse(Macroblock mb) {
        var result = switch (type) {
            case PictureHeader.I -> parseIntraPicture(mb);
            case PictureHeader.P, PictureHeader.IMPROVED_PB -> parseInterPicture(mb);
            case PictureHeader.B -> parseBPicture(mb);
            case PictureHeader.EP -> parseEpPicture(mb);
            default -> parseEiPicture(mb);
        };
        if (!result) {
            return false;
        }
        if (overrun()) {
            throw CORRUPT;
        }
        var index = mb.index;
        parsed[index] = true;
        intra[index] = mb.intra();
        return true;
    }

    /**
     * Parses a macroblock of an I-picture.
     *
     * @param mb the macroblock
     * @return whether a macroblock was parsed
     */
    private boolean parseIntraPicture(Macroblock mb) {
        int mcbpc;
        while (true) {
            mcbpc = sac ? sacMcbpcIntra() : vlc(Tables.MCBPC_I);
            if (mcbpc != Tables.STUFFING) {
                break;
            }
            if (atStartCode()) {
                return false;
            }
        }
        mb.coded = true;
        mb.kind = Macroblock.INTRA;
        var mbType = mcbpc >> 2;
        if (mbType != 3 && mbType != 4) {
            throw CORRUPT;
        }
        intraTail(mb, mcbpc & 3, mbType == 4);
        return true;
    }

    /**
     * Parses a macroblock of a P-picture, PB-frame or Improved PB-frame.
     *
     * @param mb the macroblock
     * @return whether a macroblock was parsed
     */
    private boolean parseInterPicture(Macroblock mb) {
        int mcbpc;
        while (true) {
            if (readCod()) {
                pendingPr0 = false;
                notCoded(mb);
                return true;
            }
            if (multipleReferences) {
                var pr0 = readIndex();
                if (pr0 == 1 && (slices || pendingPr0)) {
                    marker();
                    pendingPr0 = false;
                } else {
                    pendingPr0 = pr0 == 1;
                }
                if (pr0 != 0) {
                    mb.coded = true;
                    mb.kind = Macroblock.INTER;
                    Arrays.fill(mb.forwardReference, pr0);
                    storeForward(mb, true);
                    return true;
                }
            } else {
                pendingPr0 = false;
            }
            mcbpc = sac ? sacMcbpcInter() : vlc(extendedTypes ? Tables.MCBPC_P_EXTENDED : Tables.MCBPC_P);
            if (mcbpc != Tables.STUFFING) {
                break;
            }
            if (atStartCode()) {
                return false;
            }
        }
        mb.coded = true;
        var mbType = mcbpc >> 2;
        var cbpc = mcbpc & 3;
        if (mbType == 5 && !extendedTypes) {
            throw CORRUPT;
        }
        if ((mbType == 2 || mbType == 5) && !fourVectors) {
            throw CORRUPT;
        }
        var isIntra = mbType == 3 || mbType == 4;
        mb.kind = isIntra ? Macroblock.INTRA : mbType == 2 || mbType == 5 ? Macroblock.INTER4V : Macroblock.INTER;
        if (isIntra && aic) {
            mb.intraMode = readIntraMode();
        }
        var partCoded = false;
        if (pbFrame) {
            var modb = sac ? arithmetic.decode(SacModels.MODB_G) : vlc(PlusTables.MODB_PB);
            mb.partMode = Macroblock.PB_BIDIRECTIONAL;
            mb.partVector = modb >= 1;
            partCoded = modb == 2;
        } else if (improvedPb) {
            var modb = sac ? arithmetic.decode(SacModels.MODB_M) : vlc(PlusTables.MODB_IMPROVED);
            mb.partMode = modb < 2 ? Macroblock.PB_BIDIRECTIONAL : modb < 4 ? Macroblock.PB_FORWARD
                    : Macroblock.PB_BACKWARD;
            mb.partVector = modb == 2 || modb == 3;
            partCoded = modb == 1 || modb == 3 || modb == 5;
        }
        if (partCoded) {
            mb.partPattern = readCbpb();
        }
        var cbpy = readCbpy(isIntra || (aiv && cbpc == 3));
        if (!isIntra && !(aiv && cbpc == 3)) {
            cbpy = 15 - cbpy;
        }
        if (mbType == 1 || mbType == 4 || mbType == 5) {
            readDquant();
        }
        mb.pattern = cbpy << 2 | cbpc;
        mb.quant = quant;
        var vectors = !isIntra || pbFrame || improvedPb;
        if (vectors) {
            if (mb.kind == Macroblock.INTER4V) {
                for (var block = 0; block < 4; block++) {
                    if (multipleReferences) {
                        mb.forwardReference[block] = readReference();
                    }
                    readBlockVector(mb, block);
                }
            } else {
                if (multipleReferences) {
                    Arrays.fill(mb.forwardReference, readReference());
                }
                readBlockVector(mb, 0);
                for (var block = 1; block < 4; block++) {
                    mb.forwardX[block] = mb.forwardX[0];
                    mb.forwardY[block] = mb.forwardY[0];
                }
            }
        }
        var candidate = !isIntra || pbFrame || mb.partMode == Macroblock.PB_BIDIRECTIONAL;
        if (!candidate) {
            Arrays.fill(mb.forwardX, 0);
            Arrays.fill(mb.forwardY, 0);
        }
        storeForward(mb, candidate);
        if (mb.partVector) {
            if (multipleReferences) {
                mb.partReference = readReference();
            }
            readPartVector(mb);
        }
        if (isIntra) {
            readIntraBlocks(mb);
        } else {
            readInterBlocks(mb.coefficients, mb.pattern, mb.quant, true);
        }
        if (pbFrame || improvedPb) {
            readInterBlocks(mb.partCoefficients, mb.partPattern, Math.min(31, (5 + dbquant) * mb.quant / 4), false);
        }
        return true;
    }

    /**
     * Parses a macroblock of a B-picture.
     *
     * @param mb the macroblock
     * @return whether a macroblock was parsed
     */
    private boolean parseBPicture(Macroblock mb) {
        int mbType;
        while (true) {
            if (readCod()) {
                pendingPr0 = false;
                mb.kind = Macroblock.DIRECT;
                mb.quant = quant;
                return true;
            }
            if (multipleReferences && pr0(mb)) {
                return true;
            }
            mbType = vlc(PlusTables.MBTYPE_B);
            if (mbType != 13) {
                break;
            }
            if (atStartCode()) {
                return false;
            }
        }
        mb.coded = true;
        mb.kind = switch (mbType) {
            case 0, 1 -> Macroblock.DIRECT;
            case 2, 3, 4 -> Macroblock.FORWARD;
            case 5, 6, 7 -> Macroblock.BACKWARD;
            case 8, 9, 10 -> Macroblock.BIDIRECTIONAL;
            default -> Macroblock.INTRA;
        };
        var texture = mbType != 2 && mbType != 5 && mbType != 8;
        var dquant = mbType == 1 || mbType == 4 || mbType == 7 || mbType == 10 || mbType == 12;
        var isIntra = mb.kind == Macroblock.INTRA;
        enhancementTail(mb, texture, dquant, isIntra);
        var forward = mb.kind == Macroblock.FORWARD || mb.kind == Macroblock.BIDIRECTIONAL;
        var backward = mb.kind == Macroblock.BACKWARD || mb.kind == Macroblock.BIDIRECTIONAL;
        if (forward) {
            if (multipleReferences) {
                Arrays.fill(mb.forwardReference, readReference());
            }
            readSingleVector(mb, true);
        }
        if (backward) {
            if (twoBackward) {
                mb.backwardReference = reader.readBit();
                if (mb.backwardReference == 0 && !umv && reader.peekAt(reader.position() - 6, 5) == 0) {
                    marker();
                }
            }
            readSingleVector(mb, false);
        }
        storeForward(mb, forward);
        if (backward) {
            backwardX[mb.index] = mb.backwardX[0];
            backwardY[mb.index] = mb.backwardY[0];
        }
        enhancementBlocks(mb, isIntra);
        return true;
    }

    /**
     * Parses a macroblock of an EP-picture.
     *
     * @param mb the macroblock
     * @return whether a macroblock was parsed
     */
    private boolean parseEpPicture(Macroblock mb) {
        int mbType;
        while (true) {
            if (readCod()) {
                pendingPr0 = false;
                mb.kind = Macroblock.FORWARD;
                mb.quant = quant;
                storeForward(mb, true);
                return true;
            }
            if (multipleReferences && pr0(mb)) {
                return true;
            }
            mbType = vlc(PlusTables.MBTYPE_EP);
            if (mbType != 10) {
                break;
            }
            if (atStartCode()) {
                return false;
            }
        }
        mb.coded = true;
        mb.kind = switch (mbType) {
            case 0, 1 -> Macroblock.FORWARD;
            case 2, 3, 4 -> Macroblock.UPWARD;
            case 5, 6, 7 -> Macroblock.BIDIRECTIONAL;
            default -> Macroblock.INTRA;
        };
        var texture = mbType != 2 && mbType != 5;
        var dquant = mbType == 1 || mbType == 4 || mbType == 7 || mbType == 9;
        var isIntra = mb.kind == Macroblock.INTRA;
        enhancementTail(mb, texture, dquant, isIntra);
        var forwardVector = mbType == 0 || mbType == 1 || mbType == 6 || mbType == 7;
        if (forwardVector) {
            if (multipleReferences) {
                Arrays.fill(mb.forwardReference, readReference());
            }
            readSingleVector(mb, true);
        }
        storeForward(mb, forwardVector);
        enhancementBlocks(mb, isIntra);
        return true;
    }

    /**
     * Parses a macroblock of an EI-picture.
     *
     * @param mb the macroblock
     * @return whether a macroblock was parsed
     */
    private boolean parseEiPicture(Macroblock mb) {
        int mcbpc;
        while (true) {
            if (readCod()) {
                mb.kind = Macroblock.UPWARD;
                mb.quant = quant;
                return true;
            }
            mcbpc = vlc(PlusTables.MCBPC_EI);
            if (mcbpc != 16) {
                break;
            }
            if (atStartCode()) {
                return false;
            }
        }
        mb.coded = true;
        var isIntra = mcbpc >= 8;
        mb.kind = isIntra ? Macroblock.INTRA : Macroblock.UPWARD;
        var cbpc = mcbpc & 3;
        var dquant = (mcbpc >= 4 && mcbpc < 8) || mcbpc >= 12;
        if (isIntra) {
            intraTail(mb, cbpc, dquant);
            return true;
        }
        var cbpy = readCbpy(true);
        if (dquant) {
            readDquant();
        }
        mb.pattern = cbpy << 2 | cbpc;
        mb.quant = quant;
        readInterBlocks(mb.coefficients, mb.pattern, mb.quant, true);
        return true;
    }

    /**
     * Reads PR0 of a B- or EP-picture macroblock (U.3.2.2).
     *
     * @param mb the macroblock
     * @return whether PR0 coded the whole macroblock as forward prediction with a zero vector
     */
    private boolean pr0(Macroblock mb) {
        var pr0 = readIndex();
        if (pr0 == 1 && (slices || pendingPr0)) {
            marker();
            pendingPr0 = false;
        } else {
            pendingPr0 = pr0 == 1;
        }
        if (pr0 == 0) {
            return false;
        }
        mb.coded = true;
        mb.kind = Macroblock.FORWARD;
        mb.quant = quant;
        Arrays.fill(mb.forwardReference, pr0);
        storeForward(mb, true);
        return true;
    }

    /**
     * Reads the INTRA_MODE, CBPC, CBPY and DQUANT of a B- or EP-picture macroblock.
     *
     * @param mb the macroblock
     * @param texture whether CBPC and CBPY are present
     * @param dquant whether DQUANT is present
     * @param isIntra whether the macroblock is INTRA
     */
    private void enhancementTail(Macroblock mb, boolean texture, boolean dquant, boolean isIntra) {
        if (isIntra && aic) {
            mb.intraMode = readIntraMode();
        }
        var pattern = 0;
        if (texture) {
            var cbpc = vlc(PlusTables.CBPC);
            var intraDefinition = isIntra || mb.kind == Macroblock.UPWARD
                    || (type == PictureHeader.EP && mb.kind == Macroblock.BIDIRECTIONAL);
            var useIntra = intraDefinition || (aiv && cbpc == 3);
            var cbpy = readCbpy(useIntra);
            if (!useIntra) {
                cbpy = 15 - cbpy;
            }
            pattern = cbpy << 2 | cbpc;
        }
        if (dquant) {
            readDquant();
        }
        mb.pattern = pattern;
        mb.quant = quant;
    }

    /**
     * Reads the blocks of a B-, EP- or EI-picture macroblock.
     *
     * @param mb the macroblock
     * @param isIntra whether the macroblock is INTRA
     */
    private void enhancementBlocks(Macroblock mb, boolean isIntra) {
        if (isIntra) {
            readIntraBlocks(mb);
        } else {
            readInterBlocks(mb.coefficients, mb.pattern, mb.quant, true);
        }
    }

    /**
     * Reads the INTRA_MODE, CBPY, DQUANT and blocks of an INTRA macroblock of an I- or EI-picture.
     *
     * @param mb the macroblock
     * @param cbpc the chrominance pattern
     * @param dquant whether DQUANT is present
     */
    private void intraTail(Macroblock mb, int cbpc, boolean dquant) {
        if (aic) {
            mb.intraMode = readIntraMode();
        }
        var cbpy = readCbpy(true);
        if (dquant) {
            readDquant();
        }
        mb.pattern = cbpy << 2 | cbpc;
        mb.quant = quant;
        storeForward(mb, false);
        readIntraBlocks(mb);
    }

    /**
     * Records a macroblock that was not coded: predicted with a zero vector.
     *
     * @param mb the macroblock
     */
    private void notCoded(Macroblock mb) {
        mb.kind = Macroblock.SKIPPED;
        mb.quant = quant;
        storeForward(mb, false);
    }

    /**
     * Records the forward vectors of a macroblock as candidate predictors.
     *
     * @param mb the macroblock
     * @param candidate whether its vectors are candidates; zero vectors are recorded otherwise
     */
    private void storeForward(Macroblock mb, boolean candidate) {
        var at = mb.index * 4;
        for (var block = 0; block < 4; block++) {
            forwardX[at + block] = candidate ? mb.forwardX[block] : 0;
            forwardY[at + block] = candidate ? mb.forwardY[block] : 0;
        }
    }

    /**
     * Returns whether a start code or the end of the data follows, which ends the segment after macroblock stuffing.
     *
     * @return whether the segment ends
     */
    boolean atStartCode() {
        return sac ? arithmetic.stopped() : startCodeAt(reader);
    }

    /**
     * Returns whether a start code follows the reader's position, after zero stuffing up to the next byte boundary,
     * or the data ends.
     *
     * @param reader the stream
     * @return whether a start code or the end follows
     */
    static boolean startCodeAt(BitReader reader) {
        if (reader.remaining() < 17) {
            return reader.remaining() <= 0 || reader.peek((int) reader.remaining()) == 0;
        }
        if (reader.peek(17) == 1) {
            return true;
        }
        var position = reader.position();
        var aligned = (position + 7) & ~7L;
        return aligned != position && reader.peek((int) (aligned - position)) == 0
                && reader.peekAt(aligned, 17) == 1;
    }

    /**
     * Reads COD.
     *
     * @return whether the macroblock is not coded
     */
    private boolean readCod() {
        return sac ? arithmetic.decode(SacModels.COD) == 1 : reader.readBit() == 1;
    }

    /**
     * Decodes a variable length code.
     *
     * @param table the code table to decode with
     * @return the symbol
     */
    private int vlc(Vlc table) {
        var symbol = table.decode(reader);
        if (symbol == Vlc.INVALID) {
            throw CORRUPT;
        }
        return symbol;
    }

    /**
     * Reads an emulation prevention bit.
     */
    private void marker() {
        if (reader.readBit() != 1) {
            throw CORRUPT;
        }
    }

    /**
     * Reads a value of Table U.1.
     *
     * @return the value
     */
    private int readIndex() {
        var value = PlusTables.readIndex(reader);
        if (value == PlusTables.INVALID) {
            throw CORRUPT;
        }
        return value;
    }

    /**
     * Reads PR, PR2-4 or PRB with its emulation prevention bit.
     *
     * @return the picture reference parameter
     */
    private int readReference() {
        var value = readIndex();
        if (value == 1 && !umv) {
            marker();
        }
        return value;
    }

    /**
     * Decodes MCBPC of an I-picture with the arithmetic code.
     *
     * @return the MCBPC symbol as {@link Tables#MCBPC_I} returns it
     */
    private int sacMcbpcIntra() {
        var index = arithmetic.decode(SacModels.MCBPC_INTRA);
        return index == 8 ? Tables.STUFFING : index < 4 ? 3 << 2 | index : 4 << 2 | (index - 4);
    }

    /**
     * Decodes MCBPC of a P-picture with the arithmetic code.
     *
     * @return the MCBPC symbol as {@link Tables#MCBPC_P_EXTENDED} returns it
     */
    private int sacMcbpcInter() {
        var index = arithmetic.decode(extendedTypes ? SacModels.MCBPC_P_EXTENDED : SacModels.MCBPC_P);
        if (index == 20) {
            return Tables.STUFFING;
        }
        return index < 20 ? index : 5 << 2 | (index - 21);
    }

    /**
     * Reads INTRA_MODE (Table I.1).
     *
     * @return the prediction mode: 0 DC, 1 vertical, 2 horizontal
     */
    private int readIntraMode() {
        if (sac) {
            return arithmetic.decode(SacModels.INTRA_AC_DC);
        }
        return reader.readBit() == 0 ? 0 : 1 + reader.readBit();
    }

    /**
     * Reads CBPB.
     *
     * @return the coded block pattern of the B-part
     */
    private int readCbpb() {
        if (!sac) {
            return reader.read(6);
        }
        var pattern = 0;
        for (var block = 0; block < 6; block++) {
            pattern = pattern << 1 | arithmetic.decode(block < 4 ? SacModels.YCBPB : SacModels.UVCBPB);
        }
        return pattern;
    }

    /**
     * Reads CBPY.
     *
     * @param intraModel whether the INTRA model is used for the arithmetic code
     * @return the pattern index of Table 12, the INTRA pattern
     */
    private int readCbpy(boolean intraModel) {
        if (sac) {
            return arithmetic.decode(intraModel ? SacModels.CBPY_INTRA : SacModels.CBPY);
        }
        return vlc(Tables.CBPY);
    }

    /**
     * Reads DQUANT and updates the quantizer (5.3.6 and T.2).
     */
    private void readDquant() {
        if (sac) {
            quant = clampQuant(quant + DQUANT[arithmetic.decode(SacModels.DQUANT)]);
            return;
        }
        if (!mq) {
            quant = clampQuant(quant + DQUANT[reader.read(2)]);
            return;
        }
        if (reader.readBit() == 0) {
            var value = reader.read(5);
            if (value == 0) {
                throw CORRUPT;
            }
            quant = value;
            return;
        }
        var up = reader.readBit() == 1;
        int delta;
        if (quant == 1) {
            delta = up ? 1 : 2;
        } else if (quant <= 10) {
            delta = up ? 1 : -1;
        } else if (quant <= 20) {
            delta = up ? 2 : -2;
        } else if (quant <= 28) {
            delta = up ? 3 : -3;
        } else if (quant == 29) {
            delta = up ? 2 : -3;
        } else if (quant == 30) {
            delta = up ? 1 : -3;
        } else {
            delta = up ? -5 : -3;
        }
        quant = clampQuant(quant + delta);
    }

    /**
     * Clips a quantizer to 1 to 31.
     *
     * @param value the quantizer
     * @return the clipped quantizer
     */
    private static int clampQuant(int value) {
        return value < 1 ? 1 : Math.min(value, 31);
    }

    /**
     * Decodes the vector of one luminance block of a P-picture or the P-part of a PB-frame (6.1.1 and F.2).
     *
     * @param mb the macroblock
     * @param block the block, 0 to 3
     */
    private void readBlockVector(Macroblock mb, int block) {
        var px = predictor(mb, block, forwardX, true);
        var py = predictor(mb, block, forwardY, true);
        readVectorPair(px, py);
        mb.forwardX[block] = pairX;
        mb.forwardY[block] = pairY;
        var at = mb.index * 4 + block;
        forwardX[at] = pairX;
        forwardY[at] = pairY;
    }

    /**
     * Decodes the forward or backward vector of a B- or EP-picture macroblock (O.5.1).
     *
     * @param mb the macroblock
     * @param forward whether the vector is the forward one
     */
    private void readSingleVector(Macroblock mb, boolean forward) {
        int px;
        int py;
        if (forward) {
            px = predictor(mb, 0, forwardX, true);
            py = predictor(mb, 0, forwardY, true);
        } else {
            px = predictor(mb, 0, backwardX, false);
            py = predictor(mb, 0, backwardY, false);
        }
        readVectorPair(px, py);
        var x = forward ? mb.forwardX : mb.backwardX;
        var y = forward ? mb.forwardY : mb.backwardY;
        Arrays.fill(x, pairX);
        Arrays.fill(y, pairY);
    }

    /**
     * Decodes MVDB: the delta vector of a PB-frame (G.4) or the forward vector of the B-part of an Improved
     * PB-frame (M.2.2).
     *
     * @param mb the macroblock
     */
    private void readPartVector(Macroblock mb) {
        if (pbFrame) {
            var px = trb * mb.forwardX[0] / trd;
            var py = trb * mb.forwardY[0] / trd;
            readVectorPair(px, py);
            mb.partX = pairX - px;
            mb.partY = pairY - py;
            return;
        }
        var left = mb.column > 0 && sameSegment(mb.index - 1, mb.index) && partForward[mb.index - 1];
        var px = left ? partX[mb.index - 1] : 0;
        var py = left ? partY[mb.index - 1] : 0;
        readVectorPair(px, py);
        mb.partX = pairX;
        mb.partY = pairY;
        partX[mb.index] = pairX;
        partY[mb.index] = pairY;
        partForward[mb.index] = mb.partMode == Macroblock.PB_FORWARD;
    }

    /**
     * The horizontal component of the last vector pair read.
     */
    private int pairX;

    /**
     * The vertical component of the last vector pair read.
     */
    private int pairY;

    /**
     * Reads the two components of a vector from their predictors into {@link #pairX} and {@link #pairY}, with the
     * code and range rules of the picture's modes (5.3.7, D.2 and Q.4).
     *
     * @param px the horizontal predictor
     * @param py the vertical predictor
     */
    private void readVectorPair(int px, int py) {
        if (rru) {
            px = pseudo(px);
            py = pseudo(py);
        }
        int x;
        int y;
        if (plus && umv) {
            var dx = PlusTables.readReversible(reader);
            var dy = PlusTables.readReversible(reader);
            if (dx == PlusTables.INVALID || dy == PlusTables.INVALID) {
                throw CORRUPT;
            }
            if (dx == 1 && dy == 1) {
                marker();
            }
            x = px + dx;
            y = py + dy;
        } else {
            x = tableComponent(px);
            y = tableComponent(py);
        }
        if (rru) {
            x = actual(x);
            y = actual(y);
        }
        pairX = x;
        pairY = y;
    }

    /**
     * Decodes one vector component with Table 14 or its arithmetic model, wrapped into [-16, 15.5] or, in the
     * Unrestricted Motion Vector mode without PLUSPTYPE, into the range of D.2 around the predictor.
     *
     * @param predictor the predictor in half samples
     * @return the component in half samples
     */
    private int tableComponent(int predictor) {
        var symbol = sac ? arithmetic.decode(SacModels.MVD) : vlc(Tables.MVD);
        if (symbol > 63) {
            throw CORRUPT;
        }
        var value = predictor + symbol - 32;
        if (umv && !plus) {
            if (predictor < -31 && value < -63) {
                value += 64;
            } else if (predictor > 32 && value > 63) {
                value -= 64;
            }
            return value;
        }
        if (value < -32) {
            value += 64;
        } else if (value > 31) {
            value -= 64;
        }
        return value;
    }

    /**
     * Converts a vector component of the Reduced-Resolution Update mode to its pseudo component (Q.4 item 1).
     *
     * @param value the component in half samples, zero or odd
     * @return the pseudo component in half samples
     */
    static int pseudo(int value) {
        if (value == 0) {
            return 0;
        }
        var magnitude = (Math.abs(value) + 1) >> 1;
        return value < 0 ? -magnitude : magnitude;
    }

    /**
     * Converts a pseudo vector component of the Reduced-Resolution Update mode to the actual component (Q.4 item 3).
     *
     * @param value the pseudo component in half samples
     * @return the component in half samples
     */
    static int actual(int value) {
        if (value == 0) {
            return 0;
        }
        var magnitude = 2 * Math.abs(value) - 1;
        return value < 0 ? -magnitude : magnitude;
    }

    /**
     * Returns the median predictor of a vector component (6.1.1, F.2 and O.5.1).
     *
     * @param mb the macroblock
     * @param block the luminance block, 0 to 3, or 0 for a macroblock vector
     * @param vectors the candidate vectors: four per macroblock when {@code perBlock}, one otherwise
     * @param perBlock whether the vectors are kept per block
     * @return the predictor
     */
    private int predictor(Macroblock mb, int block, int[] vectors, boolean perBlock) {
        var index = mb.index;
        var column = mb.column;
        var row = mb.row;
        int mv1;
        int mv2;
        int mv3;
        int leftMb = column > 0 && sameSegment(index - 1, index) ? index - 1 : -1;
        int aboveMb = row > 0 && sameSegment(index - columns, index) ? index - columns : -1;
        int aboveRight = row > 0 && column + 1 < columns && sameSegment(index - columns + 1, index)
                ? index - columns + 1 : -1;
        var rightEdge = column + 1 >= columns;
        switch (block) {
            case 0 -> {
                mv1 = leftMb >= 0 ? value(vectors, leftMb, 1, perBlock) : 0;
                if (aboveMb < 0) {
                    mv2 = mv1;
                    mv3 = rightEdge ? 0 : mv1;
                } else {
                    mv2 = value(vectors, aboveMb, 2, perBlock);
                    mv3 = rightEdge ? 0 : aboveRight >= 0 ? value(vectors, aboveRight, 2, perBlock) : mv1;
                }
            }
            case 1 -> {
                mv1 = value(vectors, index, 0, true);
                if (aboveMb < 0) {
                    mv2 = mv1;
                    mv3 = rightEdge ? 0 : mv1;
                } else {
                    mv2 = value(vectors, aboveMb, 3, true);
                    mv3 = rightEdge ? 0 : aboveRight >= 0 ? value(vectors, aboveRight, 2, true) : mv1;
                }
            }
            case 2 -> {
                mv1 = leftMb >= 0 ? value(vectors, leftMb, 3, true) : 0;
                mv2 = value(vectors, index, 0, true);
                mv3 = value(vectors, index, 1, true);
            }
            default -> {
                mv1 = value(vectors, index, 2, true);
                mv2 = value(vectors, index, 0, true);
                mv3 = value(vectors, index, 1, true);
            }
        }
        return Math.max(Math.min(mv1, mv2), Math.min(Math.max(mv1, mv2), mv3));
    }

    /**
     * Returns a candidate vector component of a macroblock that was parsed.
     *
     * @param vectors the candidates
     * @param mb the macroblock
     * @param block the block of the macroblock, from 0 to 3
     * @param perBlock whether the candidates are kept per block
     * @return the component
     */
    private static int value(int[] vectors, int mb, int block, boolean perBlock) {
        return perBlock ? vectors[mb * 4 + block] : vectors[mb];
    }

    /**
     * Returns whether two macroblocks are in the same video picture segment.
     *
     * @param other a macroblock parsed before
     * @param current the current macroblock
     * @return whether they share the segment
     */
    boolean sameSegment(int other, int current) {
        return parsed[other] && segments[other] == segments[current];
    }

    /**
     * Reads the blocks of an INTRA macroblock: INTRADC and the coefficients of Table 16, or those of the Advanced
     * INTRA Coding mode.
     *
     * @param mb the macroblock
     */
    private void readIntraBlocks(Macroblock mb) {
        var chromaQ = mq ? CHROMA_QUANT[mb.quant] : mb.quant;
        for (var block = 0; block < 6; block++) {
            var coded = (mb.pattern & (32 >> block)) != 0;
            var blockQuant = block < 4 ? mb.quant : chromaQ;
            var offset = block * 64;
            if (aic) {
                readAdvancedIntraBlock(mb, block, coded, blockQuant);
                continue;
            }
            int dc;
            if (sac) {
                dc = (arithmetic.decode(SacModels.INTRADC) + 1) * 8;
            } else {
                var flc = reader.read(8);
                if (flc == 0 || flc == 128) {
                    throw CORRUPT;
                }
                dc = (flc == 255 ? 128 : flc) * 8;
            }
            mb.coefficients[offset] = (short) dc;
            if (coded) {
                Arrays.fill(levels, 0);
                if (readEvents(1, Tables.ZIGZAG, false, true, false) < 0) {
                    throw CORRUPT;
                }
                dequantize(mb.coefficients, offset, blockQuant);
            }
        }
    }

    /**
     * Reads and dequantizes the coefficients of the coded INTER blocks of a macroblock (5.4 and 6.2), re-reading a
     * block with Table I.2 when the Alternative INTER VLC mode is on and Table 16 addresses coefficients outside it
     * (S.2.2).
     *
     * @param out the coefficients of the six blocks
     * @param pattern the coded block pattern
     * @param quant the quantizer
     * @param pPart whether the blocks belong to the P-part or a normal macroblock rather than the B-part of a
     * PB-frame
     */
    private void readInterBlocks(short[] out, int pattern, int quant, boolean pPart) {
        var chromaQ = mq ? CHROMA_QUANT[quant] : quant;
        for (var block = 0; block < 6; block++) {
            if ((pattern & (32 >> block)) == 0) {
                continue;
            }
            Arrays.fill(levels, 0);
            var position = reader.position();
            if (readEvents(0, Tables.ZIGZAG, false, false, false) < 0) {
                if (!aiv || sac) {
                    throw CORRUPT;
                }
                reader.seek(position);
                Arrays.fill(levels, 0);
                if (readEvents(0, Tables.ZIGZAG, true, false, false) < 0) {
                    throw CORRUPT;
                }
            }
            dequantize(out, block * 64, block < 4 ? quant : chromaQ);
        }
    }

    /**
     * Dequantizes the levels of a block into its coefficients (6.2.1 and 6.2.2), leaving an INTRADC already stored.
     *
     * @param out the coefficients
     * @param offset the index of the block's first coefficient
     * @param quant the quantizer
     */
    private void dequantize(short[] out, int offset, int quant) {
        var even = 1 - (quant & 1);
        for (var i = 0; i < 64; i++) {
            var level = levels[i];
            if (level != 0) {
                var magnitude = (2 * Math.abs(level) + 1) * quant - even;
                var value = level < 0 ? -magnitude : magnitude;
                out[offset + (i & 7) * 8 + (i >> 3)] = (short) (value < -2048 ? -2048 : Math.min(value, 2047));
            }
        }
    }

    /**
     * Decodes one INTRA block of the Advanced INTRA Coding mode (I.3): the coefficients in the scan of the prediction
     * mode, reconstructed without dead zone, predicted from the block above or to the left in the same video picture
     * segment, with the DC oddification and the clipping of I.3.
     *
     * @param mb the macroblock
     * @param block the block, 0 to 5
     * @param coded whether the block has coefficients
     * @param blockQuant the quantizer of the block
     */
    private void readAdvancedIntraBlock(Macroblock mb, int block, boolean coded, int blockQuant) {
        var luma = block < 4;
        int bx;
        int by;
        int gridWidth;
        int base;
        int shift;
        var count = columns * rows;
        if (luma) {
            bx = 2 * mb.column + (block & 1);
            by = 2 * mb.row + (block >> 1);
            gridWidth = 2 * columns;
            base = 0;
            shift = 1;
        } else {
            bx = mb.column;
            by = mb.row;
            gridWidth = columns;
            base = block == 4 ? 4 * count : 5 * count;
            shift = 0;
        }
        var index = base + by * gridWidth + bx;
        var aboveMb = by > 0 ? ((by - 1) >> shift) * columns + (bx >> shift) : -1;
        var leftMb = bx > 0 ? (by >> shift) * columns + ((bx - 1) >> shift) : -1;
        var above = aboveMb >= 0 && availableIntra(aboveMb, mb.index);
        var left = leftMb >= 0 && availableIntra(leftMb, mb.index);
        var mode = mb.intraMode;
        var scan = mode == 0 ? Tables.ZIGZAG : mode == 1 ? Tables.ALTERNATE_HORIZONTAL : Tables.ALTERNATE_VERTICAL;
        Arrays.fill(levels, 0);
        if (coded && readEvents(0, scan, true, true, true) < 0) {
            throw CORRUPT;
        }
        var step = 2 * blockQuant;
        for (var i = 0; i < 64; i++) {
            levels[i] *= step;
        }
        var aboveIndex = index - gridWidth;
        var leftIndex = index - 1;
        int dc;
        switch (mode) {
            case 0 -> {
                if (above && left) {
                    dc = levels[0] + (dcStore[aboveIndex] + dcStore[leftIndex]) / 2;
                } else if (above) {
                    dc = levels[0] + dcStore[aboveIndex];
                } else if (left) {
                    dc = levels[0] + dcStore[leftIndex];
                } else {
                    dc = levels[0] + 1024;
                }
            }
            case 1 -> {
                if (above) {
                    dc = levels[0] + dcStore[aboveIndex];
                    for (var u = 1; u < 8; u++) {
                        levels[u] += rowStore[aboveIndex * 8 + u];
                    }
                } else {
                    dc = levels[0] + 1024;
                }
            }
            default -> {
                if (left) {
                    dc = levels[0] + dcStore[leftIndex];
                    for (var v = 1; v < 8; v++) {
                        levels[v * 8] += columnStore[leftIndex * 8 + v];
                    }
                } else {
                    dc = levels[0] + 1024;
                }
            }
        }
        if ((dc & 1) == 0) {
            dc++;
        }
        levels[0] = dc < 0 ? 0 : Math.min(dc, 2047);
        for (var i = 1; i < 64; i++) {
            levels[i] = levels[i] < -2048 ? -2048 : Math.min(levels[i], 2047);
        }
        dcStore[index] = levels[0];
        for (var u = 1; u < 8; u++) {
            rowStore[index * 8 + u] = levels[u];
            columnStore[index * 8 + u] = levels[u * 8];
        }
        var offset = block * 64;
        for (var i = 0; i < 64; i++) {
            mb.coefficients[offset + (i & 7) * 8 + (i >> 3)] = (short) levels[i];
        }
    }

    /**
     * Returns whether the blocks of a macroblock may predict the INTRA coefficients of the current one: it is INTRA
     * and in the same video picture segment (I.3).
     *
     * @param other the neighboring macroblock
     * @param current the current macroblock
     * @return whether it may
     */
    private boolean availableIntra(int other, int current) {
        return other == current || (sameSegment(other, current) && intra[other]);
    }

    /**
     * Reads the coefficient events of a block into {@link #levels} in raster order: the variable length codes of
     * Table 16 or Table I.2 with the escapes of 5.4.2 and T.4, or their arithmetic models (E.7).
     *
     * @param start the scan index of the first coefficient
     * @param scan the scan order
     * @param intraTable whether the events are those of Table I.2
     * @param intraModels whether the INTRA arithmetic models are used
     * @param advancedIntra whether the block is an Advanced INTRA Coding block
     * @return 0, or {@code -1} when the events address coefficients outside the block
     */
    private int readEvents(int start, int[] scan, boolean intraTable, boolean intraModels, boolean advancedIntra) {
        var index = start;
        var count = 0;
        while (true) {
            int last;
            int run;
            int level;
            if (sac) {
                var model = switch (count) {
                    case 0 -> intraModels ? SacModels.TCOEF1_INTRA : SacModels.TCOEF1;
                    case 1 -> intraModels ? SacModels.TCOEF2_INTRA : SacModels.TCOEF2;
                    case 2 -> intraModels ? SacModels.TCOEF3_INTRA : SacModels.TCOEF3;
                    default -> intraModels ? SacModels.TCOEFR_INTRA : SacModels.TCOEFR;
                };
                var symbol = arithmetic.decode(model);
                if (symbol == SAC_ESCAPE) {
                    last = arithmetic.decode(intraModels ? SacModels.LAST_INTRA : SacModels.LAST);
                    run = arithmetic.decode(intraModels ? SacModels.RUN_INTRA : SacModels.RUN);
                    var levelIndex = arithmetic.decode(intraModels ? SacModels.LEVEL_INTRA : SacModels.LEVEL);
                    level = levelIndex < 127 ? levelIndex - 127 : levelIndex - 126;
                } else {
                    var event = (advancedIntra ? PlusTables.INTRA_EVENTS : PlusTables.INTER_EVENTS)[symbol];
                    last = event >>> 15 & 1;
                    run = event >>> 9 & 63;
                    level = event & 0xFF;
                    if (arithmetic.decode(SacModels.SIGN) == 1) {
                        level = -level;
                    }
                }
            } else {
                var symbol = (intraTable ? Tables.AIC_TCOEF : Tables.INTER_TCOEF).decode(reader);
                if (symbol == Vlc.INVALID) {
                    throw CORRUPT;
                }
                if (symbol == Tables.ESCAPE) {
                    last = reader.readBit();
                    run = reader.read(6);
                    level = reader.read(8) << 24 >> 24;
                    if (level == -128 && mq) {
                        var rotated = reader.read(11);
                        level = (((rotated & 0x3F) << 5) | (rotated >>> 6)) << 21 >> 21;
                    } else if (level == 0 || level == -128) {
                        throw CORRUPT;
                    }
                } else {
                    last = symbol >>> 15 & 1;
                    run = symbol >>> 9 & 63;
                    level = (symbol & 0x100) != 0 ? -(symbol & 0xFF) : symbol & 0xFF;
                }
            }
            index += run;
            if (index > 63) {
                return -1;
            }
            levels[scan[index]] = level;
            index++;
            count++;
            if (last != 0) {
                return 0;
            }
            if (overrun()) {
                throw CORRUPT;
            }
        }
    }

    /**
     * Returns whether parsing ran past the data: past its end, or for arithmetic coded data past the 16 bits the
     * decoder reads ahead with a margin.
     *
     * @return whether the reader overran
     */
    private boolean overrun() {
        return sac ? reader.position() > reader.limit() + 64 : reader.overrun();
    }
}
