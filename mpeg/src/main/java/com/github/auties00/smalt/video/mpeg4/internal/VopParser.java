package com.github.auties00.smalt.video.mpeg4.internal;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Parses the macroblocks of one MPEG-4 Visual VOP (ISO/IEC 14496-2 6.2.5 to 6.2.7 and 7.4 to 7.8)
 * into {@link Macroblocks}: macroblock modes, motion vectors with their prediction, intra DC and
 * AC prediction, and the dequantized coefficients, for I-, P-, B- and S(GMC)-VOPs, progressive or
 * interlaced, with or without video packets, data partitioning and reversible VLCs, and for the
 * short video header with its groups of blocks.
 *
 * <p>Corrupt data inside a video packet (or group of blocks) does not abort the picture: the
 * macroblocks from the failing one up to the next resynchronization point are marked
 * {@link Macroblocks#LOST}, parsing resumes at the next resynchronization marker, and
 * {@link #parse} reports the damage.
 *
 * <p>An instance belongs to one decoder and is reused for every picture of a size.
 */
public final class VopParser {
    /**
     * The exception that unwinds the parsing of a damaged video packet.
     */
    private static final RuntimeException CORRUPT = new CorruptPacket();

    /**
     * Reads eight big-endian bytes of a bit reader's buffer.
     */
    private static final VarHandle LONG_BE = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);



    /**
     * The DC value of an unavailable predictor block, {@code 2^(bits_per_pixel + 2)} (7.4.3.1).
     */
    private static final int DEFAULT_DC = 1024;

    /**
     * The layer.
     */
    private VideoObjectLayer layer;

    /**
     * The macroblock columns.
     */
    private int mbWidth;

    /**
     * The macroblock rows.
     */
    private int mbHeight;

    /**
     * The number of macroblocks.
     */
    private int mbCount;

    /**
     * The parse results.
     */
    private Macroblocks blocks;

    /**
     * The DC values {@code F[0][0]} of every block for intra prediction: the luminance blocks of
     * the picture on a grid of {@code 2 * mbWidth} columns, then the blue-difference and the
     * red-difference blocks on grids of {@code mbWidth} columns.
     */
    private int[] dcStore;

    /**
     * The quantized first-row AC values of every block, 8 per block (index 0 unused).
     */
    private int[] rowStore;

    /**
     * The quantized first-column AC values of every block, 8 per block (index 0 unused).
     */
    private int[] columnStore;

    /**
     * The reader.
     */
    private BitReader reader;

    /**
     * The VOP header.
     */
    private Vop vop;

    /**
     * The picture being decoded, whose side information is filled during parsing.
     */
    private Frame current;

    /**
     * The backward reference of a B-VOP, or {@code null}.
     */
    private Frame backwardRef;

    /**
     * The global motion parameters of an S(GMC)-VOP.
     */
    private final GlobalMotion globalMotion = new GlobalMotion();

    /**
     * The quantizer in effect.
     */
    private int quant;

    /**
     * The quantizer of the previously coded macroblock, for {@code intra_dc_vlc_thr}, or
     * {@code -1} at the start of a video packet.
     */
    private int runningQuant;

    /**
     * The number of the first macroblock of the current video packet or non-empty group of blocks.
     */
    private int packetStart;

    /**
     * The temporal distance of the B-VOP from its forward reference, in ticks.
     */
    private long trb;

    /**
     * The temporal distance between the references of the B-VOP, in ticks.
     */
    private long trd;

    /**
     * The distance of the B-VOP from its forward reference in frame periods, for interlaced direct
     * mode.
     */
    private int framesB;

    /**
     * The distance between the references of the B-VOP in frame periods, for interlaced direct
     * mode.
     */
    private int framesD;

    /**
     * The B-VOP motion vector predictors: forward {@code x, y} and backward {@code x, y} of the
     * top field, then of the bottom field.
     */
    private final int[] bPredictors = new int[8];

    /**
     * The quantized coefficients of the intra block being decoded, in raster order.
     */
    private final int[] levels = new int[64];

    /**
     * The levels of the intra block being decoded, in raster order; every entry is zero between
     * blocks unless {@link #intraLevelsDirty}.
     */
    private final int[] intraLevels = new int[64];

    /**
     * Whether {@link #intraLevels} may hold levels of a block whose decoding failed.
     */
    private boolean intraLevelsDirty;

    /**
     * Whether the current picture met corrupt data.
     */
    private boolean damaged;

    /**
     * The H.263 picture header of a short-header picture, or {@code null} for the baseline
     * syntax.
     */
    private H263Header h263;

    /**
     * The macroblock types of a data-partitioned video packet, {@code derived_mb_type} or -1 for
     * a skipped macroblock.
     */
    private int[] partitionTypes;

    /**
     * The {@code cbpc} values of a data-partitioned video packet.
     */
    private int[] partitionChroma;

    /**
     * The intra DC differentials of a data-partitioned video packet, 6 per macroblock.
     */
    private int[] partitionDc;

    /**
     * The {@code ac_pred_flag} values of a data-partitioned video packet.
     */
    private boolean[] partitionAcPred;

    /**
     * Whether the intra DC differentials of a data-partitioned macroblock were read in the first
     * partitions.
     */
    private boolean[] partitionDcRead;

    /**
     * The {@code mcsel} values of a data-partitioned video packet.
     */
    private boolean[] partitionMcsel;

    /**
     * Creates a parser.
     */
    public VopParser() {
    }

    /**
     * Returns the parse results of the last picture.
     *
     * @return the macroblocks
     */
    public Macroblocks blocks() {
        return blocks;
    }

    /**
     * Returns the global motion parameters of the last S(GMC)-VOP.
     *
     * @return the parameters
     */
    public GlobalMotion globalMotion() {
        return globalMotion;
    }

    /**
     * Prepares the parser for the pictures of a layer.
     *
     * @param layer the active video object layer
     */
    public void configure(VideoObjectLayer layer) {
        this.layer = layer;
        var width = layer.mbWidth();
        var height = layer.mbHeight();
        if (blocks == null || width != mbWidth || height != mbHeight) {
            mbWidth = width;
            mbHeight = height;
            mbCount = width * height;
            blocks = new Macroblocks(mbCount);
            var blockCount = 6 * mbCount;
            dcStore = new int[blockCount];
            rowStore = new int[blockCount * 8];
            columnStore = new int[blockCount * 8];
            partitionTypes = new int[mbCount];
            partitionChroma = new int[mbCount];
            partitionDc = new int[mbCount * 6];
            partitionAcPred = new boolean[mbCount];
            partitionDcRead = new boolean[mbCount];
            partitionMcsel = new boolean[mbCount];
        }
    }

    /**
     * Parses the macroblocks of a coded VOP.
     *
     * @param reader the reader, positioned at the first macroblock
     * @param vop the VOP header
     * @param current the picture being decoded; its side information is written
     * @param backwardRef the backward reference of a B-VOP, or {@code null}
     * @param trb the temporal distance of a B-VOP from its forward reference
     * @param trd the temporal distance between the references of a B-VOP
     * @param framesB the distance of a B-VOP from its forward reference in frame periods, for
     *        interlaced direct mode
     * @param framesD the distance between the references of a B-VOP in frame periods, for
     *        interlaced direct mode
     * @return {@code true} when corrupt data was concealed
     */
    public boolean parse(BitReader reader, Vop vop, Frame current, Frame backwardRef, long trb, long trd,
                         int framesB, int framesD) {
        this.reader = reader;
        this.vop = vop;
        this.current = current;
        this.backwardRef = backwardRef;
        this.trb = trb;
        this.trd = trd;
        this.framesB = framesB;
        this.framesD = framesD;
        this.damaged = false;
        if (vop.codingType == Vop.S) {
            globalMotion.setup(layer, vop);
        }
        quant = vop.quant;
        blocks.segmented = layer.shortVideoHeader && h263 != null && h263.slices;
        if (layer.shortVideoHeader) {
            parseShortHeader();
        } else if (layer.dataPartitioned && vop.codingType != Vop.B) {
            parsePacketsPartitioned();
        } else {
            parsePackets();
        }
        return damaged;
    }

    /**
     * Parses the video packets of a VOP whose macroblocks are not partitioned.
     */
    private void parsePackets() {
        var mb = 0;
        startPacket(0);
        while (mb < mbCount) {
            if (mb > 0 && atResyncMarker()) {
                var next = readPacketHeader();
                if (next < 0 || next < mb) {
                    damaged = true;
                    lose(mb, mbCount);
                    return;
                }
                if (next > mb) {
                    if (bitlessGap(mb, next)) {
                        for (; mb < next; mb++) {
                            parseBMacroblock(mb);
                        }
                    } else {
                        damaged = true;
                        lose(mb, next);
                    }
                }
                mb = next;
                startPacket(mb);
            }
            try {
                if (vop.codingType == Vop.B) {
                    parseBMacroblock(mb);
                } else {
                    parseMacroblock(mb);
                }
                if (reader.overrun()) {
                    throw CORRUPT;
                }
                mb++;
            } catch (CorruptPacket corrupt) {
                damaged = true;
                var next = seekNextPacket();
                if (next < 0 || next <= mb) {
                    lose(mb, mbCount);
                    return;
                }
                lose(mb, next);
                mb = next;
                startPacket(mb);
            }
        }
    }

    /**
     * Returns whether every macroblock of a range of a B-VOP is coded without bits, because its
     * co-located macroblock of the backward reference is skipped; a video packet can then start
     * after them.
     *
     * @param from the first macroblock
     * @param to the macroblock after the range
     * @return {@code true} when the range carries no bits
     */
    private boolean bitlessGap(int from, int to) {
        if (vop.codingType != Vop.B || backwardRef.codingType != Vop.P) {
            return false;
        }
        for (var mb = from; mb < to; mb++) {
            if (backwardRef.mbType[mb] != Frame.MB_SKIPPED) {
                return false;
            }
        }
        return true;
    }

    /**
     * Resets the prediction state at the start of a video packet.
     *
     * @param mb the number of the packet's first macroblock
     */
    private void startPacket(int mb) {
        packetStart = mb;
        runningQuant = -1;
        Arrays.fill(bPredictors, 0);
    }

    /**
     * Returns the length of the resynchronization marker of the VOP, its zeros and the final one
     * (6.3.5).
     *
     * @return the length in bits
     */
    private int resyncLength() {
        return switch (vop.codingType) {
            case Vop.I -> 17;
            case Vop.B -> Math.max(15 + Math.max(vop.fcodeForward, vop.fcodeBackward), 17) + 1;
            default -> 16 + vop.fcodeForward;
        };
    }

    /**
     * Returns whether a resynchronization marker, after valid stuffing, follows the position.
     *
     * @return {@code true} at a resynchronization marker
     */
    private boolean atResyncMarker() {
        var position = reader.position();
        long aligned;
        if ((position & 7) == 0) {
            aligned = reader.peek(8) == 0x7F ? position + 8 : position;
        } else {
            aligned = (position + 7) & ~7L;
            var stuffing = (int) (aligned - position);
            if (reader.peek(stuffing) != (1 << (stuffing - 1)) - 1) {
                return false;
            }
        }
        var length = resyncLength();
        return reader.peekAt(aligned, length) == 1 && aligned + length <= reader.limit();
    }

    /**
     * Reads the video packet header (6.2.5.2) whose resynchronization marker follows the position
     * after stuffing, as {@link #atResyncMarker()} found it.
     *
     * @return the number of the packet's first macroblock, or {@code -1} when the header is
     * corrupt
     */
    private int readPacketHeader() {
        return readPacketHeaderAt(reader.byteAlignedPosition());
    }

    /**
     * Reads a video packet header whose resynchronization marker starts at a bit position.
     *
     * @param at the position of the marker
     * @return the number of the packet's first macroblock, or {@code -1} when the header is
     * corrupt
     */
    private int readPacketHeaderAt(long at) {
        reader.seek(at + resyncLength());
        var bits = Math.max(1, 32 - Integer.numberOfLeadingZeros(mbCount - 1));
        var mb = reader.read(bits);
        var scale = reader.read(layer.quantPrecision);
        if (scale == 0 || mb >= mbCount) {
            return -1;
        }
        if (reader.readFlag()) {
            var guard = 0;
            while (reader.readBit() == 1) {
                if (++guard > 3600 || reader.overrun()) {
                    return -1;
                }
            }
            if (reader.readBit() != 1) {
                return -1;
            }
            reader.skip(layer.timeIncrementBits);
            if (reader.readBit() != 1) {
                return -1;
            }
            var type = reader.read(2);
            if (type != vop.codingType) {
                return -1;
            }
            reader.skip(3);
            if (type == Vop.S) {
                for (var i = 0; i < 2 * layer.warpingPoints; i++) {
                    var length = Tables.SPRITE_DMV_LENGTH.decode(reader);
                    if (length == Vlc.INVALID) {
                        return -1;
                    }
                    reader.skip(length);
                    if (reader.readBit() != 1) {
                        return -1;
                    }
                }
            }
            if (type != Vop.I) {
                reader.skip(3);
            }
            if (type == Vop.B) {
                reader.skip(3);
            }
        }
        if (reader.overrun()) {
            return -1;
        }
        quant = scale;
        return mb;
    }

    /**
     * Moves the reader past the next valid video packet header after an error.
     *
     * @return the number of the next packet's first macroblock, or {@code -1} when none follows
     */
    private int seekNextPacket() {
        var length = resyncLength();
        var start = (int) ((reader.position() + 7) >>> 3);
        var end = reader.byteLength();
        for (var index = start; index + 2 < end; index++) {
            if (reader.byteAt(index) != 0 || reader.byteAt(index + 1) != 0) {
                continue;
            }
            var at = (long) index << 3;
            if (reader.peekAt(at, length) == 1) {
                var mb = readPacketHeaderAt(at);
                if (mb >= 0) {
                    return mb;
                }
            }
        }
        return -1;
    }

    /**
     * Marks a range of macroblocks lost: concealed by the forward reference, with zero vectors and
     * the default intra predictors.
     *
     * @param from the first macroblock
     * @param to the macroblock after the range
     */
    private void lose(int from, int to) {
        for (var mb = from; mb < to; mb++) {
            blocks.kind[mb] = Macroblocks.LOST;
            blocks.cbp[mb] = 0;
            blocks.flags[mb] = 0;
            blocks.direction[mb] = Macroblocks.FORWARD;
            Arrays.fill(blocks.forward, mb * 8, mb * 8 + 8, 0);
            Arrays.fill(blocks.coefficients, mb * 384, mb * 384 + 384, (short) 0);
            current.mbType[mb] = Frame.MB_SKIPPED;
            Arrays.fill(current.mv, mb * 8, mb * 8 + 8, 0);
            blocks.segment[mb] = -1;
            resetIntraStore(mb);
        }
    }

    /**
     * Parses one macroblock of an I-, P- or S(GMC)-VOP in the combined syntax (6.2.6).
     *
     * @param mb the macroblock number
     */
    private void parseMacroblock(int mb) {
        var type = vop.codingType;
        int mcbpc;
        while (true) {
            if (type != Vop.I && reader.readBit() == 1) {
                parseNotCoded(mb);
                return;
            }
            mcbpc = (type == Vop.I ? Tables.MCBPC_I : Tables.MCBPC_P).decode(reader);
            if (mcbpc == Vlc.INVALID) {
                throw CORRUPT;
            }
            if (mcbpc != Tables.STUFFING) {
                break;
            }
            if (reader.overrun()) {
                throw CORRUPT;
            }
        }
        var mbType = mcbpc >> 2;
        var cbpc = mcbpc & 3;
        var mcsel = false;
        if (type == Vop.S && mbType < 2) {
            mcsel = reader.readFlag();
        }
        var intra = mbType >= 3;
        var acPred = intra && reader.readFlag();
        var cbpy = Tables.CBPY.decode(reader);
        if (cbpy == Vlc.INVALID) {
            throw CORRUPT;
        }
        if (!intra) {
            cbpy = 15 - cbpy;
        }
        var previousQuant = quant;
        if (mbType == 1 || mbType == 4) {
            applyDquant(reader.read(2));
        }
        var cbp = cbpy << 2 | cbpc;
        var fieldDct = false;
        var fieldPrediction = false;
        var fieldRefs = 0;
        if (layer.interlaced) {
            if (intra || cbp != 0) {
                fieldDct = reader.readFlag();
            }
            if (!intra && mbType < 2 && !(type == Vop.S && mcsel)) {
                fieldPrediction = reader.readFlag();
                if (fieldPrediction) {
                    fieldRefs = reader.read(2);
                }
            }
        }
        blocks.quant[mb] = (byte) quant;
        blocks.flags[mb] = (byte) (fieldDct ? Macroblocks.FIELD_DCT : 0);
        blocks.cbp[mb] = (byte) patternToBits(cbp);
        if (intra) {
            blocks.kind[mb] = Macroblocks.INTRA;
            current.mbType[mb] = Frame.MB_INTRA;
            Arrays.fill(current.mv, mb * 8, mb * 8 + 8, 0);
            var useDcVlc = useIntraDcVlc(previousQuant);
            runningQuant = quant;
            for (var block = 0; block < 6; block++) {
                var coded = (cbp & (32 >> block)) != 0;
                decodeIntraBlock(mb, block, coded, acPred, useDcVlc, false, 0);
            }
            blocks.cbp[mb] = 63;
            return;
        }
        runningQuant = quant;
        resetIntraStore(mb);
        if (type == Vop.S && mcsel) {
            setGmc(mb);
        } else if (fieldPrediction) {
            parseFieldVectors(mb, fieldRefs);
        } else if (mbType == 2) {
            blocks.kind[mb] = Macroblocks.INTER8;
            blocks.direction[mb] = Macroblocks.FORWARD;
            current.mbType[mb] = Frame.MB_INTER4V;
            for (var block = 0; block < 4; block++) {
                decodeBlockVector(mb, block);
            }
        } else {
            blocks.kind[mb] = Macroblocks.INTER16;
            blocks.direction[mb] = Macroblocks.FORWARD;
            current.mbType[mb] = Frame.MB_INTER;
            decodeBlockVector(mb, 0);
            var x = current.mv[mb * 8];
            var y = current.mv[mb * 8 + 1];
            fillVectors(current.mv, mb, x, y);
            fillVectors(blocks.forward, mb, x, y);
        }
        if (!layer.obmcDisable && blocks.kind[mb] != Macroblocks.GMC && blocks.kind[mb] != Macroblocks.FIELD) {
            blocks.flags[mb] |= Macroblocks.OVERLAPPED;
        }
        for (var block = 0; block < 6; block++) {
            if ((cbp & (32 >> block)) != 0) {
                decodeInterBlock(mb, block);
            }
        }
    }

    /**
     * Records a skipped macroblock of a P-VOP or a not-coded GMC macroblock of an S(GMC)-VOP.
     *
     * @param mb the macroblock number
     */
    private void parseNotCoded(int mb) {
        blocks.cbp[mb] = 0;
        blocks.flags[mb] = 0;
        blocks.quant[mb] = (byte) quant;
        resetIntraStore(mb);
        if (vop.codingType == Vop.S) {
            setGmc(mb);
            return;
        }
        blocks.kind[mb] = Macroblocks.INTER16;
        blocks.direction[mb] = Macroblocks.FORWARD;
        if (!layer.obmcDisable) {
            blocks.flags[mb] = Macroblocks.OVERLAPPED;
        }
        current.mbType[mb] = Frame.MB_SKIPPED;
        Arrays.fill(current.mv, mb * 8, mb * 8 + 8, 0);
        Arrays.fill(blocks.forward, mb * 8, mb * 8 + 8, 0);
    }

    /**
     * Records a macroblock predicted by global motion compensation, with its averaged vector as
     * the vector later predictions see (7.8.7.3).
     *
     * @param mb the macroblock number
     */
    private void setGmc(int mb) {
        blocks.kind[mb] = Macroblocks.GMC;
        blocks.direction[mb] = Macroblocks.FORWARD;
        current.mbType[mb] = Frame.MB_GMC;
        var x = (mb % mbWidth) * 16;
        var y = (mb / mbWidth) * 16;
        var mvx = globalMotion.averageVector(x, y, layer.quarterSample, vop.fcodeForward, false);
        var mvy = globalMotion.averageVector(x, y, layer.quarterSample, vop.fcodeForward, true);
        fillVectors(current.mv, mb, mvx, mvy);
        fillVectors(blocks.forward, mb, mvx, mvy);
    }

    /**
     * Writes one vector into the four block slots of a macroblock.
     *
     * @param vectors the vector array
     * @param mb the macroblock number
     * @param x the horizontal component
     * @param y the vertical component
     */
    private static void fillVectors(int[] vectors, int mb, int x, int y) {
        var base = mb * 8;
        for (var i = 0; i < 4; i++) {
            vectors[base + 2 * i] = x;
            vectors[base + 2 * i + 1] = y;
        }
    }

    /**
     * Converts a coded block pattern with block 0 in its most significant bit into one with block
     * {@code b} in bit {@code b}.
     *
     * @param pattern the six-bit pattern, block 0 first
     * @return the pattern indexed by block
     */
    private static int patternToBits(int pattern) {
        var result = 0;
        for (var block = 0; block < 6; block++) {
            if ((pattern & (32 >> block)) != 0) {
                result |= 1 << block;
            }
        }
        return result;
    }

    /**
     * Applies a two-bit {@code dquant} (Table 6-27).
     *
     * @param code the {@code dquant} or {@code DQUANT} code
     */
    private void applyDquant(int code) {
        var delta = switch (code) {
            case 0 -> -1;
            case 1 -> -2;
            case 2 -> 1;
            default -> 2;
        };
        setQuant(quant + delta);
    }

    /**
     * Sets the quantizer, clipped to {@code 1..2^quant_precision - 1}.
     *
     * @param value the new quantizer
     */
    private void setQuant(int value) {
        var max = (1 << layer.quantPrecision) - 1;
        quant = value < 1 ? 1 : Math.min(value, max);
    }

    /**
     * Returns whether the intra DC coefficients of the macroblock use the DC VLCs, from
     * {@code intra_dc_vlc_thr} and the running quantizer (Table 6-21).
     *
     * @param previousQuant the quantizer before the macroblock's {@code dquant}
     * @return {@code true} for the DC VLCs
     */
    private boolean useIntraDcVlc(int previousQuant) {
        var threshold = vop.intraDcVlcThreshold;
        if (threshold == 0) {
            return true;
        }
        if (threshold == 7) {
            return false;
        }
        var running = runningQuant < 0 ? quant : previousQuant;
        return running < 11 + 2 * threshold;
    }

    /**
     * Writes the intra predictor values of a non-intra macroblock: the default DC and zero AC.
     *
     * @param mb the macroblock number
     */
    private void resetIntraStore(int mb) {
        var mx = mb % mbWidth;
        var my = mb / mbWidth;
        var gridWidth = 2 * mbWidth;
        for (var block = 0; block < 4; block++) {
            var index = (2 * my + (block >> 1)) * gridWidth + 2 * mx + (block & 1);
            resetBlockStore(index);
        }
        resetBlockStore(4 * mbCount + mb);
        resetBlockStore(5 * mbCount + mb);
    }

    /**
     * Writes the default predictor values of one block.
     *
     * @param index the block index in the stores
     */
    private void resetBlockStore(int index) {
        dcStore[index] = DEFAULT_DC;
        var base = index * 8;
        for (var i = 0; i < 8; i++) {
            rowStore[base + i] = 0;
            columnStore[base + i] = 0;
        }
    }

    /**
     * Decodes one intra block (7.4.1 to 7.4.4) with DC and AC prediction, and stores its
     * dequantized coefficients.
     *
     * @param mb the macroblock number
     * @param block the block index, 0 to 5
     * @param coded whether the block has AC coefficients (or, without the DC VLCs, any
     * coefficient)
     * @param acPred the {@code ac_pred_flag}
     * @param useDcVlc whether the DC coefficient uses the DC VLCs
     * @param dcGiven whether the DC differential was read from a data partition
     * @param dcValue the DC differential read from a data partition
     */
    private void decodeIntraBlock(int mb, int block, boolean coded, boolean acPred, boolean useDcVlc,
                                  boolean dcGiven, int dcValue) {
        var luma = block < 4;
        var mx = mb % mbWidth;
        var my = mb / mbWidth;
        int bx;
        int by;
        int gridWidth;
        int base;
        int shift;
        if (luma) {
            bx = 2 * mx + (block & 1);
            by = 2 * my + (block >> 1);
            gridWidth = 2 * mbWidth;
            base = 0;
            shift = 1;
        } else {
            bx = mx;
            by = my;
            gridWidth = mbWidth;
            base = block == 4 ? 4 * mbCount : 5 * mbCount;
            shift = 0;
        }
        var index = base + by * gridWidth + bx;
        var availA = bx > 0 && available(((by >> shift) * mbWidth) + ((bx - 1) >> shift));
        var availB = bx > 0 && by > 0 && available((((by - 1) >> shift) * mbWidth) + ((bx - 1) >> shift));
        var availC = by > 0 && available((((by - 1) >> shift) * mbWidth) + (bx >> shift));
        var indexA = index - 1;
        var indexB = index - gridWidth - 1;
        var indexC = index - gridWidth;
        var fa = availA ? dcStore[indexA] : DEFAULT_DC;
        var fb = availB ? dcStore[indexB] : DEFAULT_DC;
        var fc = availC ? dcStore[indexC] : DEFAULT_DC;
        var fromC = Math.abs(fa - fb) < Math.abs(fb - fc);
        var scaler = Tables.dcScaler(quant, luma);
        int[] scan;
        if (acPred) {
            scan = fromC ? Tables.ALTERNATE_HORIZONTAL : Tables.ALTERNATE_VERTICAL;
        } else {
            scan = vop.alternateVerticalScan ? Tables.ALTERNATE_VERTICAL : Tables.ZIGZAG;
        }
        var levels = this.intraLevels;
        if (intraLevelsDirty) {
            Arrays.fill(levels, 0);
        }
        intraLevelsDirty = true;
        var start = 0;
        if (useDcVlc) {
            levels[0] = dcGiven ? dcValue : readDcDifferential(luma);
            start = 1;
        }
        var nonzero = coded ? readIntraCoefficients(levels, start, scan) : 0L;
        var dc = levels[0] + divRound(fromC ? fc : fa, scaler);
        levels[0] = clamp(dc, -2048, 2047);
        if (acPred) {
            if (fromC) {
                if (availC) {
                    var predQuant = blocks.quant[luma ? (((by - 1) >> 1) * mbWidth + (bx >> 1)) : (by - 1) * mbWidth + bx];
                    for (var u = 1; u < 8; u++) {
                        var p = rowStore[indexC * 8 + u];
                        if (p != 0) {
                            levels[u] = clamp(levels[u] + divRound(p * predQuant, quant), -2048, 2047);
                            nonzero |= 1L << u;
                        }
                    }
                }
            } else if (availA) {
                var predQuant = blocks.quant[luma ? ((by >> 1) * mbWidth + ((bx - 1) >> 1)) : by * mbWidth + bx - 1];
                for (var v = 1; v < 8; v++) {
                    var p = columnStore[indexA * 8 + v];
                    if (p != 0) {
                        levels[v * 8] = clamp(levels[v * 8] + divRound(p * predQuant, quant), -2048, 2047);
                        nonzero |= 1L << v * 8;
                    }
                }
            }
        }
        for (var u = 1; u < 8; u++) {
            rowStore[index * 8 + u] = levels[u];
            columnStore[index * 8 + u] = levels[u * 8];
        }
        var coefficients = blocks.coefficients;
        var out = mb * 384 + block * 64;
        var dcValueF = clamp(levels[0] * scaler, -2048, 2047);
        dcStore[index] = dcValueF;
        if (layer.mpegQuant) {
            var matrix = layer.intraMatrix;
            var sum = dcValueF;
            coefficients[out] = (short) dcValueF;
            for (var bits = nonzero & ~1L; bits != 0; bits &= bits - 1) {
                var i = Long.numberOfTrailingZeros(bits);
                var level = levels[i];
                levels[i] = 0;
                if (level != 0) {
                    var value = clamp(level * matrix[i] * quant * 2 / 16, -2048, 2047);
                    coefficients[out + column(i)] = (short) value;
                    sum += value;
                }
            }
            if ((sum & 1) == 0) {
                coefficients[out + 63] ^= 1;
            }
        } else {
            coefficients[out] = (short) dcValueF;
            var odd = quant & 1;
            for (var bits = nonzero & ~1L; bits != 0; bits &= bits - 1) {
                var i = Long.numberOfTrailingZeros(bits);
                var level = levels[i];
                levels[i] = 0;
                if (level != 0) {
                    var negative = level >> 31;
                    var magnitude = (2 * ((level ^ negative) - negative) + 1) * quant - (1 - odd);
                    coefficients[out + column(i)] = (short) clamp((magnitude ^ negative) - negative, -2048, 2047);
                }
            }
        }
        levels[0] = 0;
        intraLevelsDirty = false;
    }

    /**
     * Returns the index of a raster position in the column-major order the coefficient blocks are
     * stored in.
     *
     * @param position the raster position, {@code row * 8 + column}
     * @return {@code column * 8 + row}
     */
    private static int column(int position) {
        return (position & 7) << 3 | position >> 3;
    }

    /**
     * Returns whether a neighboring macroblock can be used for prediction: it lies in the current
     * video packet.
     *
     * @param mb the neighbor's macroblock number
     * @return {@code true} when available
     */
    private boolean available(int mb) {
        return mb >= packetStart;
    }

    /**
     * Reads an intra DC differential: {@code dct_dc_size} and {@code dct_dc_differential}
     * (Tables B-13 to B-15) with the marker bit of large sizes.
     *
     * @param luma whether the block is a luminance block
     * @return the differential
     */
    private int readDcDifferential(boolean luma) {
        var size = (luma ? Tables.DC_SIZE_LUMA : Tables.DC_SIZE_CHROMA).decode(reader);
        if (size == Vlc.INVALID) {
            throw CORRUPT;
        }
        if (size == 0) {
            return 0;
        }
        var code = reader.read(size);
        var value = (code >>> (size - 1)) != 0 ? code : code - (1 << size) + 1;
        if (size > 8 && reader.readBit() != 1) {
            throw CORRUPT;
        }
        return value;
    }

    /**
     * Reads the coefficients of an intra block into a raster-ordered level array.
     *
     * @param levels receives the levels
     * @param start the scan index of the first coefficient
     * @param scan the scan order
     * @return the bit set of the raster positions that received a level
     */
    private long readIntraCoefficients(int[] levels, int start, int[] scan) {
        var nonzero = 0L;
        var reversible = layer.reversibleVlc && vop.codingType != Vop.B;
        var table = reversible ? Tables.RVLC_INTRA : Tables.INTRA_TCOEF;
        var index = start;
        var br = reader;
        var buffer = br.buffer();
        var guard = br.lastWord();
        var entries = table.entries();
        var rootBits = table.rootBits();
        var rootShift = 64 - rootBits;
        var pos = br.position();
        var cache = 0L;
        var have = 0;
        while (true) {
            if (have < 32) {
                var at = pos >>> 3;
                cache = at > guard ? 0L : (long) LONG_BE.get(buffer, (int) at) << (pos & 7);
                have = 57;
            }
            var entry = entries[(int) (cache >>> rootShift)];
            int symbol;
            int length;
            if (entry > 0) {
                length = entry & 31;
                symbol = entry >>> 5;
            } else if (entry == 0) {
                br.seek(pos);
                throw CORRUPT;
            } else {
                var link = -entry;
                var bits = link & 31;
                var sub = entries[(link >>> 5) + (int) (cache << rootBits >>> (64 - bits))];
                if (sub == 0) {
                    br.seek(pos);
                    throw CORRUPT;
                }
                length = rootBits + (sub & 31);
                symbol = sub >>> 5;
            }
            cache <<= length;
            have -= length;
            pos += length;
            if (symbol == Tables.ESCAPE) {
                br.seek(pos);
                if (reversible) {
                    symbol = readReversibleEscape();
                } else {
                    symbol = readEscape(table, Tables.INTRA_LMAX, Tables.INTRA_RMAX);
                }
                pos = br.position();
                have = 0;
            }
            var last = symbol >>> 15 & 1;
            var run = symbol >>> 9 & 63;
            var level = escapeLevel(symbol);
            index += run;
            if (index > 63) {
                br.seek(pos);
                throw CORRUPT;
            }
            var at = scan[index];
            levels[at] = level;
            nonzero |= 1L << at;
            index++;
            if (last != 0) {
                br.seek(pos);
                return nonzero;
            }
        }
    }

    /**
     * Returns the signed level of a coefficient symbol: the folded sign and 8-bit level of a table
     * symbol, or the 12-bit level of an escape symbol.
     *
     * @param symbol the decoded escape symbol
     * @return the level
     */
    private static int escapeLevel(int symbol) {
        if ((symbol & ESCAPED) != 0) {
            return symbol >> 20;
        }
        var negative = -(symbol >>> 8 & 1);
        return ((symbol & 0xFF) ^ negative) - negative;
    }

    /**
     * The flag of a symbol whose level is carried in its upper bits, as decoded escapes are.
     */
    private static final int ESCAPED = 1 << 16;

    /**
     * Packs the event of an escape into a symbol: the run and last flag at their usual places and
     * the signed 12-bit level in bits 20 to 31.
     *
     * @param last the last flag
     * @param run the run of zero coefficients
     * @param level the signed level
     * @return the symbol
     */
    private static int escapeSymbol(int last, int run, int level) {
        return (level << 20) | ESCAPED | last << 15 | run << 9;
    }

    /**
     * Decodes the three escape modes of 7.4.1.3 after an escape code.
     *
     * @param table the coefficient table
     * @param lmax the {@code LMAX} values of the table
     * @param rmax the {@code RMAX} values of the table
     * @return the event as a symbol
     */
    private int readEscape(Vlc table, int[] lmax, int[] rmax) {
        if (reader.readBit() == 0) {
            var symbol = table.decode(reader);
            if (symbol == Vlc.INVALID || symbol == Tables.ESCAPE) {
                throw CORRUPT;
            }
            var last = symbol >>> 15 & 1;
            var run = symbol >>> 9 & 63;
            var level = (symbol & 0xFF) + lmax[last * 64 + run];
            return escapeSymbol(last, run, (symbol & 0x100) != 0 ? -level : level);
        }
        if (reader.readBit() == 0) {
            var symbol = table.decode(reader);
            if (symbol == Vlc.INVALID || symbol == Tables.ESCAPE) {
                throw CORRUPT;
            }
            var last = symbol >>> 15 & 1;
            var level = symbol & 0xFF;
            var run = (symbol >>> 9 & 63) + rmax[last * 64 + level] + 1;
            if (run > 63) {
                throw CORRUPT;
            }
            return escapeSymbol(last, run, (symbol & 0x100) != 0 ? -level : level);
        }
        var last = reader.readBit();
        var run = reader.read(6);
        if (reader.readBit() != 1) {
            throw CORRUPT;
        }
        var level = reader.read(12) << 20 >> 20;
        if (reader.readBit() != 1 || level == 0 || level == -2048) {
            throw CORRUPT;
        }
        return escapeSymbol(last, run, level);
    }

    /**
     * Decodes the fixed-length event after a reversible escape code (Table B-23).
     *
     * @return the event as a symbol
     */
    private int readReversibleEscape() {
        var last = reader.readBit();
        var run = reader.read(6);
        if (reader.readBit() != 1) {
            throw CORRUPT;
        }
        var level = reader.read(11);
        if (reader.readBit() != 1 || level == 0) {
            throw CORRUPT;
        }
        if (reader.read(4) != 0) {
            throw CORRUPT;
        }
        var sign = reader.readBit();
        return escapeSymbol(last, run, sign != 0 ? -level : level);
    }

    /**
     * Decodes one inter block and stores its dequantized coefficients.
     *
     * @param mb the macroblock number
     * @param block the block index, 0 to 5
     */
    private void decodeInterBlock(int mb, int block) {
        var reversible = layer.reversibleVlc && vop.codingType != Vop.B;
        var table = reversible ? Tables.RVLC_INTER : Tables.INTER_TCOEF;
        var scan = vop.alternateVerticalScan ? Tables.ALTERNATE_VERTICAL : Tables.ZIGZAG;
        var coefficients = blocks.coefficients;
        var out = mb * 384 + block * 64;
        var mpeg = layer.mpegQuant;
        var matrix = layer.interMatrix;
        var q = quant;
        var even = 1 - (q & 1);
        var sum = 0;
        var index = 0;
        var br = reader;
        var buffer = br.buffer();
        var guard = br.lastWord();
        var entries = table.entries();
        var rootBits = table.rootBits();
        var rootShift = 64 - rootBits;
        var pos = br.position();
        var cache = 0L;
        var have = 0;
        while (true) {
            if (have < 32) {
                var at = pos >>> 3;
                cache = at > guard ? 0L : (long) LONG_BE.get(buffer, (int) at) << (pos & 7);
                have = 57;
            }
            var entry = entries[(int) (cache >>> rootShift)];
            int symbol;
            int length;
            if (entry > 0) {
                length = entry & 31;
                symbol = entry >>> 5;
            } else if (entry == 0) {
                br.seek(pos);
                throw CORRUPT;
            } else {
                var link = -entry;
                var bits = link & 31;
                var sub = entries[(link >>> 5) + (int) (cache << rootBits >>> (64 - bits))];
                if (sub == 0) {
                    br.seek(pos);
                    throw CORRUPT;
                }
                length = rootBits + (sub & 31);
                symbol = sub >>> 5;
            }
            cache <<= length;
            have -= length;
            pos += length;
            if (symbol == Tables.ESCAPE) {
                br.seek(pos);
                symbol = reversible ? readReversibleEscape() : readEscape(table, Tables.INTER_LMAX, Tables.INTER_RMAX);
                pos = br.position();
                have = 0;
            }
            var last = symbol >>> 15 & 1;
            index += symbol >>> 9 & 63;
            if (index > 63) {
                br.seek(pos);
                throw CORRUPT;
            }
            var position = scan[index];
            int magnitude;
            int negative;
            if ((symbol & ESCAPED) != 0) {
                var level = symbol >> 20;
                negative = level >> 31;
                magnitude = (level ^ negative) - negative;
            } else {
                negative = -(symbol >>> 8 & 1);
                magnitude = symbol & 0xFF;
            }
            int value;
            if (mpeg) {
                value = ((2 * magnitude + 1) * matrix[position] * q >> 4 ^ negative) - negative;
                value = clamp(value, -2048, 2047);
                sum += value;
            } else {
                value = clamp((((2 * magnitude + 1) * q - even) ^ negative) - negative, -2048, 2047);
            }
            coefficients[out + column(position)] = (short) value;
            index++;
            if (last != 0) {
                break;
            }
        }
        br.seek(pos);
        if (mpeg && (sum & 1) == 0) {
            coefficients[out + 63] ^= 1;
        }
    }

    /**
     * Decodes the vector of one luminance block of a P- or S(GMC)-VOP macroblock and records it.
     *
     * @param mb the macroblock number
     * @param block the block index, 0 to 3
     */
    private void decodeBlockVector(int mb, int block) {
        var fcode = vop.fcodeForward;
        var px = predictor(mb, block, 0);
        var py = predictor(mb, block, 1);
        var x = wrap(px + readVectorComponent(fcode), fcode);
        var y = wrap(py + readVectorComponent(fcode), fcode);
        var at = mb * 8 + 2 * block;
        current.mv[at] = x;
        current.mv[at + 1] = y;
        blocks.forward[at] = x;
        blocks.forward[at + 1] = y;
    }

    /**
     * Decodes the field vectors of a field-predicted macroblock of a P-VOP (7.7.2.1).
     *
     * @param mb the macroblock number
     * @param refs the two field reference bits, top field reference first
     */
    private void parseFieldVectors(int mb, int refs) {
        var fcode = vop.fcodeForward;
        var px = predictor(mb, 0, 0);
        var py = predictor(mb, 0, 1);
        var topX = wrap(px + readVectorComponent(fcode), fcode);
        var topY = 2 * wrap(readVectorComponent(fcode) + py / 2, fcode);
        var bottomX = wrap(px + readVectorComponent(fcode), fcode);
        var bottomY = 2 * wrap(readVectorComponent(fcode) + py / 2, fcode);
        blocks.kind[mb] = Macroblocks.FIELD;
        blocks.direction[mb] = Macroblocks.FORWARD;
        var at = mb * 4;
        blocks.forwardField[at] = topX;
        blocks.forwardField[at + 1] = topY;
        blocks.forwardField[at + 2] = bottomX;
        blocks.forwardField[at + 3] = bottomY;
        var topRef = (refs >> 1) & 1;
        var bottomRef = refs & 1;
        blocks.fieldRefs[mb] = (byte) (topRef | bottomRef << 1);
        current.mbType[mb] = Frame.MB_FIELD;
        System.arraycopy(blocks.forwardField, at, current.fieldMv, at, 4);
        current.fieldRef[mb] = blocks.fieldRefs[mb];
        var averageX = div2Round(topX + bottomX);
        var averageY = div2Round(topY + bottomY);
        fillVectors(current.mv, mb, averageX, averageY);
    }

    /**
     * Rounds half of a sum of two field vector components to the nearest half or quarter sample
     * position: {@code (x >> 1) | (x & 1)} (7.7.2.1).
     *
     * @param x the sum
     * @return the rounded half
     */
    private static int div2Round(int x) {
        return (x >> 1) | (x & 1);
    }

    /**
     * Reads one motion vector difference component (Table B-12 and 7.6.3).
     *
     * @param fcode the f_code
     * @return the difference in the layer's vector units
     */
    private int readVectorComponent(int fcode) {
        var symbol = Tables.MVD.decode(reader);
        if (symbol == Vlc.INVALID) {
            throw CORRUPT;
        }
        var data = symbol - 32;
        if (fcode == 1 || data == 0) {
            return data;
        }
        var size = fcode - 1;
        var value = ((Math.abs(data) - 1) << size) + reader.read(size) + 1;
        return data < 0 ? -value : value;
    }

    /**
     * Wraps a vector component into the range of an f_code (7.6.3).
     *
     * @param value the component
     * @param fcode the f_code
     * @return the wrapped component
     */
    private static int wrap(int value, int fcode) {
        var low = -(32 << (fcode - 1));
        var high = (32 << (fcode - 1)) - 1;
        if (value < low) {
            value += 64 << (fcode - 1);
        }
        if (value > high) {
            value -= 64 << (fcode - 1);
        }
        return value;
    }

    /**
     * Returns the median predictor of one component of a block vector of a P- or S(GMC)-VOP
     * (7.6.5, Figure 7-20), or of an H.263 picture (ITU-T H.263 6.1.1 and F.2), whose rules for
     * candidates outside the picture, group of blocks or slice differ: a candidate on the left is
     * then zero, one above or above right is the candidate on the left, and one above right
     * beyond the right edge is zero.
     *
     * @param mb the macroblock number
     * @param block the block index, 0 to 3
     * @param component 0 for the horizontal and 1 for the vertical component
     * @return the predictor
     */
    private int predictor(int mb, int block, int component) {
        var mx = mb % mbWidth;
        var my = mb / mbWidth;
        var mv = current.mv;
        int a;
        int b;
        int c;
        var validA = true;
        var validB = true;
        var validC = true;
        switch (block) {
            case 0 -> {
                validA = mx > 0 && available(mb - 1);
                a = validA ? mv[(mb - 1) * 8 + 2 + component] : 0;
                validB = my > 0 && available(mb - mbWidth);
                b = validB ? mv[(mb - mbWidth) * 8 + 4 + component] : 0;
                validC = my > 0 && mx < mbWidth - 1 && available(mb - mbWidth + 1);
                c = validC ? mv[(mb - mbWidth + 1) * 8 + 4 + component] : 0;
            }
            case 1 -> {
                a = mv[mb * 8 + component];
                validB = my > 0 && available(mb - mbWidth);
                b = validB ? mv[(mb - mbWidth) * 8 + 6 + component] : 0;
                validC = my > 0 && mx < mbWidth - 1 && available(mb - mbWidth + 1);
                c = validC ? mv[(mb - mbWidth + 1) * 8 + 4 + component] : 0;
            }
            case 2 -> {
                validA = mx > 0 && available(mb - 1);
                a = validA ? mv[(mb - 1) * 8 + 6 + component] : 0;
                b = mv[mb * 8 + component];
                c = mv[mb * 8 + 2 + component];
            }
            default -> {
                a = mv[mb * 8 + 4 + component];
                b = mv[mb * 8 + component];
                c = mv[mb * 8 + 2 + component];
            }
        }
        if (layer.shortVideoHeader) {
            if (!validB) {
                b = a;
            }
            if (!validC) {
                c = mx == mbWidth - 1 && block <= 1 ? 0 : a;
            }
            return median(a, b, c);
        }
        var invalid = (validA ? 0 : 1) + (validB ? 0 : 1) + (validC ? 0 : 1);
        if (invalid == 3) {
            return 0;
        }
        if (invalid == 2) {
            return validA ? a : validB ? b : c;
        }
        return median(a, b, c);
    }

    /**
     * Returns the median of three values.
     *
     * @param a the first value
     * @param b the second value
     * @param c the third value
     * @return the median
     */
    private static int median(int a, int b, int c) {
        return Math.max(Math.min(a, b), Math.min(Math.max(a, b), c));
    }

    /**
     * Parses one macroblock of a B-VOP (6.2.6, 7.6.8, 7.6.9 and 7.7.2.2).
     *
     * @param mb the macroblock number
     */
    private void parseBMacroblock(int mb) {
        var mx = mb % mbWidth;
        if (mx == 0) {
            Arrays.fill(bPredictors, 0);
        }
        resetIntraStore(mb);
        current.mbType[mb] = Frame.MB_INTER;
        blocks.flags[mb] = 0;
        blocks.quant[mb] = (byte) quant;
        var colocated = backwardRef.mbType[mb];
        if (backwardRef.codingType == Vop.P && colocated == Frame.MB_SKIPPED) {
            blocks.kind[mb] = Macroblocks.INTER16;
            blocks.direction[mb] = Macroblocks.FORWARD;
            blocks.cbp[mb] = 0;
            Arrays.fill(blocks.forward, mb * 8, mb * 8 + 8, 0);
            return;
        }
        var modbCoded = reader.readBit() == 0;
        var mode = 0;
        var cbp = 0;
        if (modbCoded) {
            var both = reader.readBit() == 0;
            mode = readBType();
            if (both) {
                cbp = reader.read(6);
            }
        }
        if (mode != 0 && cbp != 0) {
            if (reader.readBit() == 1) {
                setQuant(quant + (reader.readBit() == 1 ? 2 : -2));
            }
        }
        blocks.quant[mb] = (byte) quant;
        var fieldDct = false;
        var fieldPrediction = false;
        var refs = 0;
        if (layer.interlaced) {
            if (cbp != 0) {
                fieldDct = reader.readFlag();
            }
            if (mode != 0) {
                fieldPrediction = reader.readFlag();
                if (fieldPrediction) {
                    if (mode != 2) {
                        refs |= reader.read(2);
                    }
                    if (mode != 3) {
                        refs |= reader.read(2) << 2;
                    }
                }
            }
        }
        blocks.flags[mb] = (byte) (fieldDct ? Macroblocks.FIELD_DCT : 0);
        blocks.cbp[mb] = (byte) patternToBits(cbp);
        switch (mode) {
            case 0 -> parseDirect(mb, modbCoded);
            case 1 -> parseBVectors(mb, true, true, fieldPrediction, refs);
            case 2 -> parseBVectors(mb, false, true, fieldPrediction, refs);
            default -> parseBVectors(mb, true, false, fieldPrediction, refs);
        }
        for (var block = 0; block < 6; block++) {
            if ((cbp & (32 >> block)) != 0) {
                decodeInterBlock(mb, block);
            }
        }
    }

    /**
     * Reads the {@code mb_type} of a B-VOP macroblock (Table B-4).
     *
     * @return 0 for direct, 1 for interpolated, 2 for backward and 3 for forward
     */
    private int readBType() {
        if (reader.readBit() == 1) {
            return 0;
        }
        if (reader.readBit() == 1) {
            return 1;
        }
        if (reader.readBit() == 1) {
            return 2;
        }
        if (reader.readBit() == 1) {
            return 3;
        }
        throw CORRUPT;
    }

    /**
     * Decodes the forward and backward vectors of a non-direct B-VOP macroblock, frame or field
     * predicted, updating the predictors (7.6.8, Table 7-11).
     *
     * @param mb the macroblock number
     * @param forward whether forward prediction is used
     * @param backward whether backward prediction is used
     * @param fieldPrediction whether the macroblock is field predicted
     * @param refs the field reference bits as read
     */
    private void parseBVectors(int mb, boolean forward, boolean backward, boolean fieldPrediction, int refs) {
        var direction = (forward ? Macroblocks.FORWARD : 0) | (backward ? Macroblocks.BACKWARD : 0);
        blocks.direction[mb] = (byte) direction;
        var p = bPredictors;
        if (!fieldPrediction) {
            blocks.kind[mb] = Macroblocks.INTER16;
            if (forward) {
                var x = wrap(p[0] + readVectorComponent(vop.fcodeForward), vop.fcodeForward);
                var y = wrap(p[1] + readVectorComponent(vop.fcodeForward), vop.fcodeForward);
                p[0] = x;
                p[1] = y;
                p[4] = x;
                p[5] = y;
                fillVectors(blocks.forward, mb, x, y);
            }
            if (backward) {
                var x = wrap(p[2] + readVectorComponent(vop.fcodeBackward), vop.fcodeBackward);
                var y = wrap(p[3] + readVectorComponent(vop.fcodeBackward), vop.fcodeBackward);
                p[2] = x;
                p[3] = y;
                p[6] = x;
                p[7] = y;
                fillVectors(blocks.backward, mb, x, y);
            }
            return;
        }
        blocks.kind[mb] = Macroblocks.FIELD;
        var fieldRefs = 0;
        var at = mb * 4;
        if (forward) {
            var topX = wrap(p[0] + readVectorComponent(vop.fcodeForward), vop.fcodeForward);
            var topY = 2 * wrap(p[1] / 2 + readVectorComponent(vop.fcodeForward), vop.fcodeForward);
            var bottomX = wrap(p[4] + readVectorComponent(vop.fcodeForward), vop.fcodeForward);
            var bottomY = 2 * wrap(p[5] / 2 + readVectorComponent(vop.fcodeForward), vop.fcodeForward);
            p[0] = topX;
            p[1] = topY;
            p[4] = bottomX;
            p[5] = bottomY;
            blocks.forwardField[at] = topX;
            blocks.forwardField[at + 1] = topY;
            blocks.forwardField[at + 2] = bottomX;
            blocks.forwardField[at + 3] = bottomY;
            fieldRefs |= ((refs >> 1) & 1) | (refs & 1) << 1;
        }
        if (backward) {
            var topX = wrap(p[2] + readVectorComponent(vop.fcodeBackward), vop.fcodeBackward);
            var topY = 2 * wrap(p[3] / 2 + readVectorComponent(vop.fcodeBackward), vop.fcodeBackward);
            var bottomX = wrap(p[6] + readVectorComponent(vop.fcodeBackward), vop.fcodeBackward);
            var bottomY = 2 * wrap(p[7] / 2 + readVectorComponent(vop.fcodeBackward), vop.fcodeBackward);
            p[2] = topX;
            p[3] = topY;
            p[6] = bottomX;
            p[7] = bottomY;
            blocks.backwardField[at] = topX;
            blocks.backwardField[at + 1] = topY;
            blocks.backwardField[at + 2] = bottomX;
            blocks.backwardField[at + 3] = bottomY;
            fieldRefs |= ((refs >> 3) & 1) << 2 | ((refs >> 2) & 1) << 3;
        }
        blocks.fieldRefs[mb] = (byte) fieldRefs;
    }

    /**
     * Decodes a direct mode macroblock of a B-VOP: the delta vector, which a macroblock with
     * {@code modb == '1'} does not carry, and the forward and backward
     * vectors scaled from the co-located macroblock of the backward reference (7.6.9.5), or the
     * field vectors of interlaced direct mode when that macroblock is field predicted (7.7.2.2).
     *
     * @param mb the macroblock number
     * @param coded whether {@code modb} announced more data; without it the delta vector is zero
     */
    private void parseDirect(int mb, boolean coded) {
        var deltaX = coded ? wrap(readVectorComponent(1), 1) : 0;
        var deltaY = coded ? wrap(readVectorComponent(1), 1) : 0;
        blocks.direction[mb] = Macroblocks.FORWARD | Macroblocks.BACKWARD;
        var colocatedType = backwardRef.mbType[mb];
        if (colocatedType == Frame.MB_FIELD) {
            parseFieldDirect(mb, deltaX, deltaY);
            return;
        }
        blocks.kind[mb] = Macroblocks.INTER8;
        var vectors = backwardRef.mv;
        var at = mb * 8;
        var tb = trb;
        var td = trd;
        for (var i = 0; i < 8; i++) {
            var v = vectors[at + i];
            var delta = (i & 1) == 0 ? deltaX : deltaY;
            var f = (int) (tb * v / td) + delta;
            var b = delta == 0 ? (int) ((tb - td) * v / td) : f - v;
            blocks.forward[at + i] = f;
            blocks.backward[at + i] = b;
        }
    }

    /**
     * Decodes the field vectors of interlaced direct mode (7.7.2.2): each field of the macroblock
     * is predicted forward from the field its co-located field referenced and backward from the
     * same field of the backward reference.
     *
     * @param mb the macroblock number
     * @param deltaX the horizontal delta vector
     * @param deltaY the vertical delta vector
     */
    private void parseFieldDirect(int mb, int deltaX, int deltaY) {
        blocks.kind[mb] = Macroblocks.FIELD;
        var refs = backwardRef.fieldRef[mb];
        fieldDirectVectors(backwardRef.fieldMv, mb * 4, refs, vop.topFieldFirst, framesB, framesD, deltaX, deltaY,
                blocks.forwardField, blocks.backwardField);
        blocks.fieldRefs[mb] = (byte) ((refs & 3) | 1 << 3);
    }

    /**
     * Derives the forward and backward field vectors of an interlaced direct mode macroblock
     * (7.7.2.2): for each field, with {@code MV} the co-located field vector and {@code MVD} the
     * delta vector,
     * {@snippet :
     * TRD = 2 * framesD + delta    // delta from Table 7-12
     * TRB = 2 * framesB + delta
     * MVf = TRB * MV / TRD + MVD
     * MVb = MVD == 0 ? (TRB - TRD) * MV / TRD : MVf - MV
     * }
     * component by component, with {@code /} truncating toward zero. Vectors are in frame
     * coordinates (half samples; vertical components of field vectors are even), and the vertical
     * components of the results are rounded down to even, that is to whole half field lines.
     *
     * @param colocated the field vectors of the backward reference: top x, top y, bottom x, bottom y
     * @param at the index of the co-located macroblock's top field vector in {@code colocated} and of
     *        the results in {@code forward} and {@code backward}
     * @param refs the reference fields of the co-located macroblock: bit 0 for its top field, bit 1
     *        for its bottom field, set for the bottom field of its reference
     * @param topFieldFirst whether the top field is displayed first
     * @param framesB the distance of the B-VOP from its forward reference in frame periods
     * @param framesD the distance between the references in frame periods
     * @param deltaX the horizontal delta vector
     * @param deltaY the vertical delta vector
     * @param forward the forward field vectors, written in the layout of {@code colocated}
     * @param backward the backward field vectors, written in the layout of {@code colocated}
     */
    static void fieldDirectVectors(int[] colocated, int at, int refs, boolean topFieldFirst, int framesB,
                                   int framesD, int deltaX, int deltaY, int[] forward, int[] backward) {
        for (var field = 0; field < 2; field++) {
            var delta = fieldDelta(topFieldFirst, field, (refs >> field) & 1);
            var td = 2L * framesD + delta;
            var tb = 2L * framesB + delta;
            if (td == 0) {
                td = 1;
            }
            var mvx = colocated[at + 2 * field];
            var mvy = colocated[at + 2 * field + 1];
            var fx = (int) (tb * mvx / td) + deltaX;
            var fy = (int) (tb * mvy / td) + deltaY;
            var bx = deltaX == 0 ? (int) ((tb - td) * mvx / td) : fx - mvx;
            var by = deltaY == 0 ? (int) ((tb - td) * mvy / td) : fy - mvy;
            forward[at + 2 * field] = fx;
            forward[at + 2 * field + 1] = fy & ~1;
            backward[at + 2 * field] = bx;
            backward[at + 2 * field + 1] = by & ~1;
        }
    }

    /**
     * Returns the correction {@code delta} of Table 7-12: how many field periods longer (positive)
     * or shorter (negative) the distance from a field to the field it references is than the
     * distance to the same field of the reference picture.
     *
     * @param topFieldFirst whether the top field is displayed first
     * @param field the current field, 0 for top and 1 for bottom
     * @param reference the field the co-located field referenced, 0 for top and 1 for bottom
     * @return the correction, from -1 to 1
     */
    static int fieldDelta(boolean topFieldFirst, int field, int reference) {
        if (field == reference) {
            return 0;
        }
        return (field == 0) == topFieldFirst ? -1 : 1;
    }

    /**
     * Divides with rounding to nearest, halves away from zero (the {@code //} operator).
     *
     * @param numerator the dividend
     * @param denominator the denominator, positive
     * @return the quotient
     */
    static int divRound(int numerator, int denominator) {
        return numerator >= 0 ? (numerator + (denominator >> 1)) / denominator
                : -((-numerator + (denominator >> 1)) / denominator);
    }

    /**
     * Clamps a value.
     *
     * @param value the value to clamp
     * @param low the lower bound
     * @param high the upper bound
     * @return the clamped value
     */
    private static int clamp(int value, int low, int high) {
        return value < low ? low : Math.min(value, high);
    }

    /**
     * Parses the video packets of an I-, P- or S(GMC)-VOP with data partitioning (6.2.5.3).
     */
    private void parsePacketsPartitioned() {
        var mb = 0;
        startPacket(0);
        while (mb < mbCount) {
            if (mb > 0) {
                if (!atResyncMarker()) {
                    damaged = true;
                    lose(mb, mbCount);
                    return;
                }
                var next = readPacketHeader();
                if (next < 0 || next < mb) {
                    damaged = true;
                    lose(mb, mbCount);
                    return;
                }
                if (next > mb) {
                    damaged = true;
                    lose(mb, next);
                }
                mb = next;
                startPacket(mb);
            }
            try {
                mb = vop.codingType == Vop.I ? parsePartitionedI(mb) : parsePartitionedP(mb);
                if (reader.overrun()) {
                    throw CORRUPT;
                }
            } catch (CorruptPacket corrupt) {
                damaged = true;
                var next = seekNextPacket();
                if (next < 0 || next <= mb) {
                    lose(mb, mbCount);
                    return;
                }
                lose(mb, next);
                mb = next;
                startPacket(mb);
            }
        }
    }

    /**
     * Parses one data-partitioned video packet of an I-VOP.
     *
     * @param first the packet's first macroblock
     * @return the macroblock after the packet
     */
    private int parsePartitionedI(int first) {
        var mb = first;
        while (reader.peek(19) != 0x6B001) {
            if (mb >= mbCount) {
                throw CORRUPT;
            }
            var mcbpc = Tables.MCBPC_I.decode(reader);
            if (mcbpc == Vlc.INVALID) {
                throw CORRUPT;
            }
            if (mcbpc == Tables.STUFFING) {
                continue;
            }
            var mbType = mcbpc >> 2;
            partitionTypes[mb] = mbType;
            partitionChroma[mb] = mcbpc & 3;
            var previousQuant = quant;
            if (mbType == 4) {
                applyDquant(reader.read(2));
            }
            blocks.quant[mb] = (byte) quant;
            var useDcVlc = useIntraDcVlc(previousQuant);
            runningQuant = quant;
            partitionDcRead[mb] = useDcVlc;
            if (useDcVlc) {
                for (var block = 0; block < 6; block++) {
                    partitionDc[mb * 6 + block] = readDcDifferential(block < 4);
                }
            }
            if (reader.overrun()) {
                throw CORRUPT;
            }
            mb++;
        }
        reader.skip(19);
        var end = mb;
        for (var i = first; i < end; i++) {
            partitionAcPred[i] = reader.readFlag();
            var cbpy = Tables.CBPY.decode(reader);
            if (cbpy == Vlc.INVALID) {
                throw CORRUPT;
            }
            partitionChroma[i] |= cbpy << 2;
        }
        for (var i = first; i < end; i++) {
            decodePartitionedIntra(i);
        }
        return end;
    }

    /**
     * Decodes the texture of an intra macroblock of a data-partitioned packet.
     *
     * @param mb the macroblock number
     */
    private void decodePartitionedIntra(int mb) {
        var cbp = partitionChroma[mb];
        quant = blocks.quant[mb];
        blocks.kind[mb] = Macroblocks.INTRA;
        blocks.flags[mb] = 0;
        blocks.cbp[mb] = 63;
        current.mbType[mb] = Frame.MB_INTRA;
        Arrays.fill(current.mv, mb * 8, mb * 8 + 8, 0);
        var dcRead = partitionDcRead[mb];
        for (var block = 0; block < 6; block++) {
            var coded = (cbp & (32 >> block)) != 0;
            decodeIntraBlock(mb, block, coded, partitionAcPred[mb], dcRead, dcRead, partitionDc[mb * 6 + block]);
        }
    }

    /**
     * Parses one data-partitioned video packet of a P- or S(GMC)-VOP.
     *
     * @param first the packet's first macroblock
     * @return the macroblock after the packet
     */
    private int parsePartitionedP(int first) {
        var mb = first;
        var type = vop.codingType;
        while (reader.peek(17) != 0x1F001) {
            if (mb >= mbCount) {
                throw CORRUPT;
            }
            if (reader.readBit() == 1) {
                partitionTypes[mb] = -1;
                parseNotCoded(mb);
                mb++;
                continue;
            }
            var mcbpc = Tables.MCBPC_P.decode(reader);
            if (mcbpc == Vlc.INVALID) {
                throw CORRUPT;
            }
            if (mcbpc == Tables.STUFFING) {
                continue;
            }
            var mbType = mcbpc >> 2;
            partitionTypes[mb] = mbType;
            partitionChroma[mb] = mcbpc & 3;
            var mcsel = type == Vop.S && mbType < 2 && reader.readFlag();
            partitionMcsel[mb] = mcsel;
            blocks.flags[mb] = 0;
            if (mbType >= 3) {
                blocks.kind[mb] = Macroblocks.INTRA;
                current.mbType[mb] = Frame.MB_INTRA;
                Arrays.fill(current.mv, mb * 8, mb * 8 + 8, 0);
            } else {
                resetIntraStore(mb);
                if (mcsel) {
                    setGmc(mb);
                } else if (mbType == 2) {
                    blocks.kind[mb] = Macroblocks.INTER8;
                    blocks.direction[mb] = Macroblocks.FORWARD;
                    current.mbType[mb] = Frame.MB_INTER4V;
                    for (var block = 0; block < 4; block++) {
                        decodeBlockVector(mb, block);
                    }
                } else {
                    blocks.kind[mb] = Macroblocks.INTER16;
                    blocks.direction[mb] = Macroblocks.FORWARD;
                    current.mbType[mb] = Frame.MB_INTER;
                    decodeBlockVector(mb, 0);
                    fillVectors(current.mv, mb, current.mv[mb * 8], current.mv[mb * 8 + 1]);
                    fillVectors(blocks.forward, mb, current.mv[mb * 8], current.mv[mb * 8 + 1]);
                }
                if (!layer.obmcDisable && blocks.kind[mb] != Macroblocks.GMC) {
                    blocks.flags[mb] |= Macroblocks.OVERLAPPED;
                }
            }
            if (reader.overrun()) {
                throw CORRUPT;
            }
            mb++;
        }
        reader.skip(17);
        var end = mb;
        for (var i = first; i < end; i++) {
            var mbType = partitionTypes[i];
            if (mbType < 0) {
                continue;
            }
            var intra = mbType >= 3;
            partitionAcPred[i] = intra && reader.readFlag();
            var cbpy = Tables.CBPY.decode(reader);
            if (cbpy == Vlc.INVALID) {
                throw CORRUPT;
            }
            if (!intra) {
                cbpy = 15 - cbpy;
            }
            partitionChroma[i] |= cbpy << 2;
            var previousQuant = quant;
            if (mbType == 1 || mbType == 4) {
                applyDquant(reader.read(2));
            }
            blocks.quant[i] = (byte) quant;
            partitionDcRead[i] = false;
            if (intra) {
                var useDcVlc = useIntraDcVlc(previousQuant);
                partitionDcRead[i] = useDcVlc;
                if (useDcVlc) {
                    for (var block = 0; block < 6; block++) {
                        partitionDc[i * 6 + block] = readDcDifferential(block < 4);
                    }
                }
            }
            runningQuant = quant;
        }
        for (var i = first; i < end; i++) {
            var mbType = partitionTypes[i];
            if (mbType < 0) {
                continue;
            }
            if (mbType >= 3) {
                decodePartitionedIntra(i);
            } else {
                quant = blocks.quant[i];
                var cbp = partitionChroma[i];
                blocks.cbp[i] = (byte) patternToBits(cbp);
                for (var block = 0; block < 6; block++) {
                    if ((cbp & (32 >> block)) != 0) {
                        decodeInterBlock(i, block);
                    }
                }
            }
        }
        if (end > first) {
            quant = blocks.quant[end - 1];
        }
        return end;
    }

    /**
     * Sets the H.263 picture header whose modes the next short-header picture uses.
     *
     * @param header the header, or {@code null} for the baseline syntax
     */
    public void setH263(H263Header header) {
        this.h263 = header;
    }

    /**
     * Parses the groups of blocks of a picture with the short video header (6.2.5.2) or of an
     * H.263 picture (ITU-T H.263 5.2), or its slices in the Slice Structured mode (Annex K).
     */
    private void parseShortHeader() {
        if (h263 != null && h263.slices) {
            parseSlices();
            return;
        }
        var gobRows = H263Header.gobRows(layer.height);
        var gobSize = gobRows * mbWidth;
        var gobCount = (mbHeight + gobRows - 1) / gobRows;
        startPacket(0);
        var mb = 0;
        for (var gob = 0; gob < gobCount && mb < mbCount; gob++) {
            var gobStart = gob * gobSize;
            if (gob > 0) {
                var header = findGobHeader();
                if (header >= 0) {
                    reader.seek(header + 17);
                    var number = reader.read(5);
                    reader.skip(2);
                    var scale = reader.read(5);
                    if (number != gob || scale == 0) {
                        damaged = true;
                        if (number > gob && number < gobCount && scale != 0) {
                            lose(mb, number * gobSize);
                            gob = number;
                            gobStart = gob * gobSize;
                            mb = gobStart;
                        } else {
                            lose(mb, mbCount);
                            return;
                        }
                    }
                    quant = scale;
                    startPacket(gobStart);
                }
            }
            var end = Math.min(mbCount, gobStart + gobSize);
            try {
                for (; mb < end; mb++) {
                    parseShortMacroblock(mb);
                    if (reader.overrun()) {
                        throw CORRUPT;
                    }
                }
            } catch (CorruptPacket corrupt) {
                damaged = true;
                lose(mb, end);
                mb = end;
                if (!skipToNextGob()) {
                    lose(mb, mbCount);
                    return;
                }
            }
        }
    }

    /**
     * Returns the width of the slice header's macroblock address (Table K.2).
     *
     * @return the number of bits
     */
    private int sliceAddressBits() {
        if (mbCount <= 48) {
            return 6;
        }
        if (mbCount <= 99) {
            return 7;
        }
        if (mbCount <= 396) {
            return 9;
        }
        if (mbCount <= 1584) {
            return 11;
        }
        return mbCount <= 6336 ? 13 : 14;
    }

    /**
     * Parses the slices of an H.263 picture in the Slice Structured mode without rectangular
     * slices (Annex K), in sequential or arbitrary order.
     */
    private void parseSlices() {
        var decoded = new boolean[mbCount];
        var bits = sliceAddressBits();
        if (reader.readBit() != 1) {
            damaged = true;
            lose(0, mbCount);
            return;
        }
        var mb = reader.read(bits);
        if (reader.readBit() != 1 || mb >= mbCount) {
            damaged = true;
            lose(0, mbCount);
            return;
        }
        while (true) {
            startPacket(mb);
            try {
                do {
                    if (decoded[mb]) {
                        throw CORRUPT;
                    }
                    blocks.segment[mb] = packetStart;
                    parseShortMacroblock(mb);
                    if (reader.overrun()) {
                        throw CORRUPT;
                    }
                    decoded[mb] = true;
                    mb++;
                } while (mb < mbCount && findGobHeader() < 0);
            } catch (CorruptPacket corrupt) {
                damaged = true;
                if (!skipToNextGob()) {
                    break;
                }
            }
            var header = findGobHeader();
            if (header < 0) {
                break;
            }
            reader.seek(header + 17);
            if (reader.readBit() != 1) {
                damaged = true;
                break;
            }
            mb = reader.read(bits);
            if (bits > 11 && reader.readBit() != 1) {
                damaged = true;
                break;
            }
            var scale = reader.read(5);
            if (reader.readBit() != 1 || scale == 0 || mb >= mbCount) {
                damaged = true;
                break;
            }
            reader.skip(2);
            quant = scale;
        }
        for (var i = 0; i < mbCount; i++) {
            if (!decoded[i]) {
                damaged = true;
                lose(i, i + 1);
            }
        }
    }

    /**
     * Parses one macroblock of a picture with the short video header or of an H.263 picture: the
     * macroblock and block layers of ITU-T H.263 (5.3 and 5.4) with the optional modes of the
     * picture header (Annexes D, F, I, J, S and T).
     *
     * @param mb the macroblock number
     */
    private void parseShortMacroblock(int mb) {
        var modes = h263;
        var type = vop.codingType;
        var extended = modes != null && modes.extended;
        int mcbpc;
        while (true) {
            if (type != Vop.I && reader.readBit() == 1) {
                parseNotCoded(mb);
                return;
            }
            mcbpc = (type == Vop.I ? Tables.MCBPC_I : extended ? Tables.MCBPC_P_EXTENDED : Tables.MCBPC_P)
                    .decode(reader);
            if (mcbpc == Vlc.INVALID) {
                throw CORRUPT;
            }
            if (mcbpc != Tables.STUFFING) {
                break;
            }
            if (reader.overrun()) {
                throw CORRUPT;
            }
        }
        var mbType = mcbpc >> 2;
        var cbpc = mcbpc & 3;
        var fourVectors = mbType == 2 || mbType == 5;
        if (fourVectors && (modes == null || !(modes.advancedPrediction || modes.deblocking))) {
            throw CORRUPT;
        }
        var intra = mbType == 3 || mbType == 4;
        var intraMode = 0;
        if (intra && modes != null && modes.advancedIntra) {
            intraMode = reader.readBit() == 0 ? 0 : 1 + reader.readBit();
        }
        var cbpy = Tables.CBPY.decode(reader);
        if (cbpy == Vlc.INVALID) {
            throw CORRUPT;
        }
        if (!intra && !(modes != null && modes.alternativeInterVlc && cbpc == 3)) {
            cbpy = 15 - cbpy;
        }
        if (mbType == 1 || mbType == 4 || mbType == 5) {
            if (modes != null && modes.modifiedQuantization) {
                modifiedDquant();
            } else {
                applyDquant(reader.read(2));
            }
        }
        var cbp = cbpy << 2 | cbpc;
        blocks.quant[mb] = (byte) quant;
        blocks.flags[mb] = 0;
        var chromaQuant = modes == null ? quant : modes.chromaQuant(quant);
        if (intra) {
            blocks.kind[mb] = Macroblocks.INTRA;
            blocks.cbp[mb] = 63;
            current.mbType[mb] = Frame.MB_INTRA;
            Arrays.fill(current.mv, mb * 8, mb * 8 + 8, 0);
            for (var block = 0; block < 6; block++) {
                var coded = (cbp & (32 >> block)) != 0;
                var out = mb * 384 + block * 64;
                var blockQuant = block < 4 ? quant : chromaQuant;
                if (modes != null && modes.advancedIntra) {
                    decodeAdvancedIntraBlock(mb, block, intraMode, coded, blockQuant);
                    continue;
                }
                var dc = reader.read(8);
                if (dc == 0 || dc == 128) {
                    throw CORRUPT;
                }
                blocks.coefficients[out] = (short) ((dc == 255 ? 128 : dc) * 8);
                if (coded) {
                    readH263Block(out, 1, blockQuant, false);
                }
            }
            return;
        }
        resetIntraStore(mb);
        blocks.direction[mb] = Macroblocks.FORWARD;
        blocks.cbp[mb] = (byte) patternToBits(cbp);
        if (fourVectors) {
            blocks.kind[mb] = Macroblocks.INTER8;
            current.mbType[mb] = Frame.MB_INTER4V;
            for (var block = 0; block < 4; block++) {
                decodeH263Vector(mb, block);
            }
        } else {
            blocks.kind[mb] = Macroblocks.INTER16;
            current.mbType[mb] = Frame.MB_INTER;
            decodeH263Vector(mb, 0);
            fillVectors(current.mv, mb, current.mv[mb * 8], current.mv[mb * 8 + 1]);
            fillVectors(blocks.forward, mb, current.mv[mb * 8], current.mv[mb * 8 + 1]);
        }
        if (!layer.obmcDisable) {
            blocks.flags[mb] = Macroblocks.OVERLAPPED;
        }
        var alternative = modes != null && modes.alternativeInterVlc;
        for (var block = 0; block < 6; block++) {
            if ((cbp & (32 >> block)) != 0) {
                readH263Block(mb * 384 + block * 64, 0, block < 4 ? quant : chromaQuant, alternative);
            }
        }
    }

    /**
     * Applies the DQUANT of the Modified Quantization mode (ITU-T H.263 T.2): a small step that
     * depends on the prior quantizer (Table T.1), or a new quantizer.
     */
    private void modifiedDquant() {
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
        setQuant(quant + delta);
    }

    /**
     * Decodes the vector of one luminance block of an H.263 macroblock with the median prediction
     * of 6.1.1 and F.2, and the vector range of the picture's modes (Annex D).
     *
     * @param mb the macroblock number
     * @param block the block index, 0 to 3
     */
    private void decodeH263Vector(int mb, int block) {
        var px = predictor(mb, block, 0);
        var py = predictor(mb, block, 1);
        int x;
        int y;
        if (h263 != null && h263.extended && h263.unrestrictedVectors) {
            var dx = reversibleVectorDifference();
            var dy = reversibleVectorDifference();
            if (dx == 1 && dy == 1 && reader.readBit() != 1) {
                throw CORRUPT;
            }
            x = px + dx;
            y = py + dy;
        } else {
            x = tableVectorComponent(px);
            y = tableVectorComponent(py);
        }
        var at = mb * 8 + 2 * block;
        current.mv[at] = x;
        current.mv[at + 1] = y;
        blocks.forward[at] = x;
        blocks.forward[at + 1] = y;
    }

    /**
     * Decodes one vector component with Table 14 of ITU-T H.263: wrapped into [-16, 15.5]
     * normally, or into the extended range of D.2 that keeps the predictor's sign in the
     * Unrestricted Motion Vector mode.
     *
     * @param predictor the predictor in half samples
     * @return the component in half samples
     */
    private int tableVectorComponent(int predictor) {
        var symbol = Tables.MVD.decode(reader);
        if (symbol == Vlc.INVALID) {
            throw CORRUPT;
        }
        var value = predictor + symbol - 32;
        if (h263 != null && h263.unrestrictedVectors) {
            if (predictor < -31 && value < -63) {
                value += 64;
            } else if (predictor > 32 && value > 63) {
                value -= 64;
            }
            return value;
        }
        return wrap(value, 1);
    }

    /**
     * Decodes one vector difference of the reversible Table D.3 of ITU-T H.263.
     *
     * @return the difference in half samples
     */
    private int reversibleVectorDifference() {
        if (reader.readBit() == 1) {
            return 0;
        }
        var value = 1;
        while (true) {
            var bit = reader.readBit();
            if (reader.readBit() == 0) {
                return bit == 1 ? -value : value;
            }
            value = value << 1 | bit;
            if (value > 4096) {
                throw CORRUPT;
            }
        }
    }

    /**
     * Decodes one intra block of the Advanced INTRA Coding mode (ITU-T H.263 Annex I): the
     * coefficients of Table I.2 in the scan of the prediction mode, reconstructed without dead zone
     * and predicted from the block above or left in the same segment, with the DC oddification.
     *
     * @param mb the macroblock number
     * @param block the block index, 0 to 5
     * @param mode the INTRA_MODE: 0 for DC, 1 for vertical, 2 for horizontal prediction
     * @param coded whether the block has coefficients
     * @param blockQuant the quantizer of the block
     */
    private void decodeAdvancedIntraBlock(int mb, int block, int mode, boolean coded, int blockQuant) {
        var luma = block < 4;
        var mx = mb % mbWidth;
        var my = mb / mbWidth;
        int bx;
        int by;
        int gridWidth;
        int base;
        int shift;
        if (luma) {
            bx = 2 * mx + (block & 1);
            by = 2 * my + (block >> 1);
            gridWidth = 2 * mbWidth;
            base = 0;
            shift = 1;
        } else {
            bx = mx;
            by = my;
            gridWidth = mbWidth;
            base = block == 4 ? 4 * mbCount : 5 * mbCount;
            shift = 0;
        }
        var index = base + by * gridWidth + bx;
        var aboveMb = by > 0 ? ((by - 1) >> shift) * mbWidth + (bx >> shift) : -1;
        var leftMb = bx > 0 ? (by >> shift) * mbWidth + ((bx - 1) >> shift) : -1;
        var above = aboveMb >= 0 && available(aboveMb) && current.mbType[aboveMb] == Frame.MB_INTRA;
        var left = leftMb >= 0 && available(leftMb) && current.mbType[leftMb] == Frame.MB_INTRA;
        var scan = mode == 0 ? Tables.ZIGZAG : mode == 1 ? Tables.ALTERNATE_HORIZONTAL : Tables.ALTERNATE_VERTICAL;
        var levels = this.levels;
        Arrays.fill(levels, 0);
        if (coded && readH263Events(levels, 0, scan, Tables.AIC_TCOEF) < 0) {
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
        levels[0] = clamp(dc, 0, 2047);
        for (var i = 1; i < 64; i++) {
            levels[i] = clamp(levels[i], -2048, 2047);
        }
        dcStore[index] = levels[0];
        for (var u = 1; u < 8; u++) {
            rowStore[index * 8 + u] = levels[u];
            columnStore[index * 8 + u] = levels[u * 8];
        }
        var out = mb * 384 + block * 64;
        for (var i = 0; i < 64; i++) {
            blocks.coefficients[out + column(i)] = (short) levels[i];
        }
    }

    /**
     * Reads and dequantizes the coefficients of an H.263 block (ITU-T H.263 5.4.2 and 6.2.1),
     * re-reading an inter block with Table I.2 when the Alternative INTER VLC mode is on and Table
     * 16 addresses coefficients outside the block (S.2.2).
     *
     * @param out the index of the block's first coefficient
     * @param start the scan index of the first coefficient: 1 after an intra DC, 0
     * otherwise
     * @param blockQuant the quantizer of the block
     * @param alternative whether the Alternative INTER VLC mode applies
     */
    private void readH263Block(int out, int start, int blockQuant, boolean alternative) {
        var levels = this.levels;
        Arrays.fill(levels, 0);
        var position = reader.position();
        if (readH263Events(levels, start, Tables.ZIGZAG, Tables.INTER_TCOEF) < 0) {
            if (!alternative) {
                throw CORRUPT;
            }
            reader.seek(position);
            Arrays.fill(levels, 0);
            if (readH263Events(levels, start, Tables.ZIGZAG, Tables.AIC_TCOEF) < 0) {
                throw CORRUPT;
            }
        }
        var coefficients = blocks.coefficients;
        var even = 1 - (blockQuant & 1);
        for (var i = 0; i < 64; i++) {
            var level = levels[i];
            if (level != 0) {
                var magnitude = (2 * Math.abs(level) + 1) * blockQuant - even;
                coefficients[out + column(i)] = (short) clamp(level < 0 ? -magnitude : magnitude, -2048, 2047);
            }
        }
    }

    /**
     * Reads the coefficient events of an H.263 block into a raster-ordered level array, with the
     * fixed-length escape of 5.4.2 and the extended escape of the Modified Quantization mode (T.4).
     *
     * @param levels receives the levels
     * @param start the scan index of the first coefficient
     * @param scan the scan order
     * @param table the coefficient table
     * @return 0, or {@code -1} when the events address coefficients outside the block
     */
    private int readH263Events(int[] levels, int start, int[] scan, Vlc table) {
        var extendedEscape = h263 != null && h263.modifiedQuantization;
        var index = start;
        while (true) {
            var symbol = table.decode(reader);
            if (symbol == Vlc.INVALID) {
                throw CORRUPT;
            }
            int last;
            int run;
            int level;
            if (symbol == Tables.ESCAPE) {
                last = reader.readBit();
                run = reader.read(6);
                level = reader.read(8) << 24 >> 24;
                if (level == -128 && extendedEscape) {
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
            index += run;
            if (index > 63) {
                return -1;
            }
            levels[scan[index]] = level;
            index++;
            if (last != 0) {
                return 0;
            }
            if (reader.overrun()) {
                throw CORRUPT;
            }
        }
    }

    /**
     * Returns the bit position of a group of blocks resynchronization marker at the reader's
     * position or after zero stuffing up to the next byte boundary.
     *
     * @return the position, or {@code -1} when no marker follows
     */
    private long findGobHeader() {
        var position = reader.position();
        if (reader.peek(17) == 1) {
            return position;
        }
        var aligned = (position + 7) & ~7L;
        if (aligned != position && reader.peek((int) (aligned - position)) == 0 && reader.peekAt(aligned, 17) == 1) {
            return aligned;
        }
        return -1;
    }

    /**
     * Moves the reader to the next byte-aligned group of blocks marker after an error.
     *
     * @return {@code true} when one was found
     */
    private boolean skipToNextGob() {
        var start = (int) ((reader.position() + 7) >>> 3);
        var end = reader.byteLength();
        for (var index = start; index + 2 < end; index++) {
            if (reader.byteAt(index) == 0 && reader.byteAt(index + 1) == 0 && (reader.byteAt(index + 2) & 0x80) != 0
                    && reader.peekAt((long) index << 3, 22) != 0x20 && reader.peekAt((long) index << 3, 22) != 0x3F) {
                reader.seek((long) index << 3);
                return true;
            }
        }
        return false;
    }

    /**
     * Marks the exception that unwinds the parsing of a damaged video packet.
     */
    private static final class CorruptPacket extends RuntimeException {
        /**
         * Creates the shared instance, without a stack trace.
         */
        CorruptPacket() {
            super("corrupt video packet", null, false, false);
        }
    }
}
