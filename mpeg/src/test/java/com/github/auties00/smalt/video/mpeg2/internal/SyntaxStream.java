package com.github.auties00.smalt.video.mpeg2.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Writes MPEG-1 and MPEG-2 video elementary streams of pseudo-random syntax, for the decoder tests: the coding tools
 * the encoders on the test runners never emit (MPEG-1 D-pictures and whole sample vectors, concealment motion vectors,
 * every motion type of frame and field pictures with arbitrary vectors and {@code f_code}s, 4:4:4 macroblocks,
 * macroblock escapes and stuffing, extra slice information, quantizer matrix extensions with chroma matrices,
 * saturating coefficients) and per-picture control of the display flags ({@code top_field_first},
 * {@code repeat_first_field}, {@code progressive_frame}) and of the group of pictures flags.
 *
 * <p>Pictures hold random intra DC values, a few escape-coded coefficients per block, and motion compensated
 * macroblocks whose vectors are drawn so that every prediction, chroma included, stays inside the reference
 * pictures; the vector prediction, the DC prediction and their resets follow ISO/IEC 13818-2 7.2.1 and 7.6.3. The
 * stream is valid but its pictures look like noise; the tests compare its decode with the reference decoder.
 */
public final class SyntaxStream {
    public static final int I = 1;
    public static final int P = 2;
    public static final int B = 3;
    public static final int D = 4;

    public static final int TOP = 1;
    public static final int BOTTOM = 2;
    public static final int FRAME = 3;

    /** The sequence parameters. */
    public static final class Sequence {
        public boolean mpeg2 = true;
        public int width = 64;
        public int height = 64;
        public int chroma = 1;
        public boolean progressive;
        public boolean lowDelay;
        public int frameRateCode = 3;
        public int aspect = 2;
        public int[] intraMatrix;
        public int[] nonIntraMatrix;
        public boolean displayExtension;
        public int colorPrimaries = 1;
        public int transfer = 1;
        public int matrix = 1;
        /** Whether to insert {@code macroblock_stuffing} (MPEG-1 only). */
        public boolean stuffing;
        /** The largest share of P- and B-picture macroblocks skipped. */
        public double skipRate = 0.2;
        /** The largest coefficient level coded. */
        public int maxLevel = 2047;
    }

    /** The parameters of one picture, in coding order. */
    public static final class Picture {
        public int type;
        public int structure = FRAME;
        public int temporalReference;
        public boolean topFieldFirst = true;
        public boolean repeatFirstField;
        public boolean progressiveFrame;
        public boolean framePredFrameDct;
        public boolean concealment;
        public boolean qScaleType;
        public boolean intraVlc;
        public boolean alternateScan;
        public int dcPrecision;
        public int[][] fCode = {{2, 2}, {2, 2}};
        public boolean fullPelForward;
        public boolean fullPelBackward;
        public boolean group;
        public boolean closedGop = true;
        public boolean brokenLink;
        public boolean sequenceHeader;
        /** The intra and non-intra matrices of the sequence header repeated before the picture (null for the defaults). */
        public int[][] sequenceMatrices;
        /** The matrices of a quant matrix extension (intra, non-intra, chroma intra, chroma non-intra), or null. */
        public int[][] quantExtension;
        /** The {@code cc_data} constructs of an ATSC A/53 user data, or null. */
        public byte[] captions;
        /** Whether motion types may use dual-prime prediction (P-pictures without B-pictures around them). */
        public boolean dualPrime;
        /** The share of macroblocks coded intra in P- and B-pictures. */
        public double intraRate = 0.1;

        public Picture(int type) {
            this.type = type;
        }

        public Picture(int type, int structure) {
            this.type = type;
            this.structure = structure;
        }
    }

    private final Sequence seq;
    private final Random random;
    private final BitWriter out = new BitWriter();
    private final int mbWidth;
    private final int mbHeight;
    private final int codedWidth;
    private final int codedHeight;

    private Picture pic;
    private int blockCount;
    private int[] dcPredictor = new int[3];
    private final int[][][] pmv = new int[2][2][2];
    private int previousType;
    private int[][][] previousVectors;
    /** The reference frames completed before the frame being written. */
    private int completedReferences;
    /** Whether a first field was written whose second field is awaited. */
    private boolean pendingField;
    /** Whether the picture being written may predict from the field of its own parity of an earlier frame. */
    private boolean sameParityAvailable;
    /** The {@code quantiser_scale_code} in force. */
    private int quantizer;
    /** The quantizer matrices in force, raster order: intra, non-intra, chroma intra, chroma non-intra. */
    private final int[][] weights = new int[4][];

    /**
     * The largest sum of the magnitudes of a block's reconstructed coefficients. Blocks of many saturated
     * coefficients take the 32-bit integer inverse DCT past its range (its design bounds hold for the coefficients a
     * forward DCT of samples makes), where implementations differ; the budget keeps every block well inside it while
     * still saturating up to two coefficients.
     */
    private static final int COEFFICIENT_BUDGET = 6000;

    /** The non-linear quantizer scales of ISO/IEC 13818-2 Table 7-6. */
    private static final int[] NON_LINEAR = {0, 1, 2, 3, 4, 5, 6, 7, 8, 10, 12, 14, 16, 18, 20, 22, 24, 28, 32, 36,
            40, 44, 48, 52, 56, 64, 72, 80, 88, 96, 104, 112};

    /** The alternate scan (ISO/IEC 13818-2 Figure 7-3): the raster index of each scan position. */
    private static final int[] ALTERNATE = {
            0, 8, 16, 24, 1, 9, 2, 10, 17, 25, 32, 40, 48, 56, 57, 49,
            41, 33, 26, 18, 3, 11, 4, 12, 19, 27, 34, 42, 50, 58, 35, 43,
            51, 59, 20, 28, 5, 13, 6, 14, 21, 29, 36, 44, 52, 60, 37, 45,
            53, 61, 22, 30, 7, 15, 23, 31, 38, 46, 54, 62, 39, 47, 55, 63};

    private SyntaxStream(Sequence sequence, long seed) {
        this.seq = sequence;
        this.random = new Random(seed);
        this.mbWidth = (sequence.width + 15) / 16;
        this.mbHeight = !sequence.mpeg2 || sequence.progressive ? (sequence.height + 15) / 16
                : 2 * ((sequence.height + 31) / 32);
        this.codedWidth = mbWidth * 16;
        this.codedHeight = mbHeight * 16;
    }

    /** Writes a stream of pictures in coding order, with a sequence header first and a sequence end code last. */
    public static byte[] write(Sequence sequence, List<Picture> pictures, long seed) {
        var writer = new SyntaxStream(sequence, seed);
        writer.sequenceHeader();
        for (var i = 0; i < pictures.size(); i++) {
            var picture = pictures.get(i);
            if (i > 0 && picture.sequenceHeader) {
                if (picture.sequenceMatrices != null) {
                    sequence.intraMatrix = picture.sequenceMatrices[0];
                    sequence.nonIntraMatrix = picture.sequenceMatrices[1];
                }
                writer.sequenceHeader();
            }
            if (picture.group || i == 0) {
                writer.group(picture);
            }
            writer.picture(picture);
        }
        writer.out.startCode(0xB7);
        return writer.out.toByteArray();
    }

    private void sequenceHeader() {
        weights[0] = seq.intraMatrix != null ? seq.intraMatrix.clone()
                : com.github.auties00.smalt.internal.codec.MpegVideoHeaders.defaultIntraMatrix();
        weights[1] = seq.nonIntraMatrix != null ? seq.nonIntraMatrix.clone() : flat();
        weights[2] = weights[0].clone();
        weights[3] = weights[1].clone();
        out.startCode(0xB3);
        out.put(seq.width & 0xFFF, 12).put(seq.height & 0xFFF, 12);
        out.put(seq.aspect, 4).put(seq.frameRateCode, 4);
        out.put(0x3FFFF, 18).put(1, 1).put(seq.mpeg2 ? 112 : 20, 10).put(0, 1);
        matrix(seq.intraMatrix);
        matrix(seq.nonIntraMatrix);
        if (seq.mpeg2) {
            out.startCode(0xB5);
            out.put(1, 4).put(seq.chroma == 1 ? 0x48 : 0x85, 8).flag(seq.progressive).put(seq.chroma, 2);
            out.put(seq.width >> 12, 2).put(seq.height >> 12, 2).put(0, 12).put(1, 1).put(0, 8);
            out.flag(seq.lowDelay).put(0, 2).put(0, 5);
            if (seq.displayExtension) {
                out.startCode(0xB5);
                out.put(2, 4).put(1, 3).put(1, 1).put(seq.colorPrimaries, 8).put(seq.transfer, 8)
                        .put(seq.matrix, 8).put(seq.width, 14).put(1, 1).put(seq.height, 14);
            }
        }
    }

    private void matrix(int[] raster) {
        if (raster == null) {
            out.put(0, 1);
            return;
        }
        out.put(1, 1);
        var zigzag = com.github.auties00.smalt.internal.codec.MpegVideoHeaders.zigzag();
        for (var i = 0; i < 64; i++) {
            out.put(raster[zigzag[i]], 8);
        }
    }

    private void group(Picture picture) {
        out.startCode(0xB8);
        out.put(0, 1).put(0, 5).put(0, 6).put(1, 1).put(0, 6).put(0, 6);
        out.flag(picture.closedGop).flag(picture.brokenLink);
    }

    private void picture(Picture p) {
        this.pic = p;
        // the second field of the first reference frame predicts from its first field only
        sameParityAvailable = p.structure == FRAME || p.type != P || completedReferences > 0;
        writePicture(p);
        var completesFrame = p.structure == FRAME || pendingField;
        pendingField = p.structure != FRAME && !pendingField;
        if (completesFrame && (p.type == I || p.type == P)) {
            completedReferences++;
        }
    }

    private void writePicture(Picture p) {
        blockCount = seq.chroma == 3 ? 12 : seq.chroma == 2 ? 8 : 6;
        out.startCode(0x00);
        out.put(p.temporalReference & 0x3FF, 10).put(p.type, 3).put(0xFFFF, 16);
        if (p.type == P || p.type == B) {
            out.flag(!seq.mpeg2 && p.fullPelForward).put(seq.mpeg2 ? 7 : p.fCode[0][0], 3);
        }
        if (p.type == B) {
            out.flag(!seq.mpeg2 && p.fullPelBackward).put(seq.mpeg2 ? 7 : p.fCode[1][0], 3);
        }
        out.put(0, 1);
        if (seq.mpeg2) {
            out.startCode(0xB5);
            out.put(8, 4);
            for (var s = 0; s < 2; s++) {
                for (var t = 0; t < 2; t++) {
                    // the forward f_code also codes the concealment vectors of an I-picture
                    var used = s == 0 ? p.type != I || p.concealment : p.type == B;
                    out.put(used ? p.fCode[s][t] : 15, 4);
                }
            }
            out.put(p.dcPrecision, 2).put(p.structure, 2).flag(p.structure == FRAME && p.topFieldFirst);
            out.flag(p.framePredFrameDct).flag(p.concealment).flag(p.qScaleType).flag(p.intraVlc);
            out.flag(p.alternateScan).flag(p.repeatFirstField).flag(seq.chroma == 1 && p.progressiveFrame);
            out.flag(p.progressiveFrame).put(0, 1);
            if (p.quantExtension != null) {
                for (var m = 0; m < 4; m++) {
                    if (p.quantExtension[m] != null) {
                        weights[m] = p.quantExtension[m].clone();
                        if (m < 2) {
                            weights[m + 2] = p.quantExtension[m].clone();
                        }
                    }
                }
                out.startCode(0xB5);
                out.put(3, 4);
                var zigzag = com.github.auties00.smalt.internal.codec.MpegVideoHeaders.zigzag();
                for (var matrix : p.quantExtension) {
                    if (matrix == null) {
                        out.put(0, 1);
                        continue;
                    }
                    out.put(1, 1);
                    for (var i = 0; i < 64; i++) {
                        out.put(matrix[zigzag[i]], 8);
                    }
                }
            }
        }
        if (p.captions != null) {
            out.startCode(0xB2);
            out.put('G', 8).put('A', 8).put('9', 8).put('4', 8).put(3, 8).put(0x40 | p.captions.length / 3, 8);
            out.put(0xFF, 8);
            for (var b : p.captions) {
                out.put(b & 0xFF, 8);
            }
            out.put(0xFF, 8);
        }
        var rows = p.structure == FRAME ? mbHeight : mbHeight / 2;
        if (seq.mpeg2) {
            for (var row = 0; row < rows; row++) {
                var starts = new ArrayList<Integer>();
                starts.add(0);
                var extra = random.nextInt(3);
                for (var i = 0; i < extra && mbWidth > 2; i++) {
                    var column = 1 + random.nextInt(mbWidth - 1);
                    if (!starts.contains(column)) {
                        starts.add(column);
                    }
                }
                starts.sort(null);
                for (var i = 0; i < starts.size(); i++) {
                    var end = i + 1 < starts.size() ? starts.get(i + 1) : mbWidth;
                    slice(row, row * mbWidth + starts.get(i), row * mbWidth + end);
                }
            }
        } else {
            var total = rows * mbWidth;
            var address = 0;
            while (address < total) {
                var length = 1 + random.nextInt(Math.max(1, mbWidth * 2));
                var end = Math.min(total, address + length);
                slice(address / mbWidth, address, end);
                address = end;
            }
        }
    }

    private int quantizerCode() {
        quantizer = 1 + random.nextInt(31);
        return quantizer;
    }

    private void resetDc() {
        var reset = 1 << (7 + (seq.mpeg2 ? pic.dcPrecision : 0));
        dcPredictor[0] = dcPredictor[1] = dcPredictor[2] = reset;
    }

    private void resetPmv() {
        for (var r = 0; r < 2; r++) {
            for (var s = 0; s < 2; s++) {
                pmv[r][s][0] = 0;
                pmv[r][s][1] = 0;
            }
        }
    }

    /** Writes a slice of the macroblocks from {@code first} to {@code end} (exclusive) of the picture. */
    private void slice(int row, int first, int end) {
        out.startCode(1 + (row & 0x7F));
        if (seq.mpeg2 && seq.height > 2800) {
            out.put(row >> 7, 3);
        }
        out.put(quantizerCode(), 5);
        if (seq.mpeg2) {
            if (random.nextInt(4) == 0) {
                out.put(1, 1).put(pic.type == I ? 1 : 0, 1).put(0, 7);
                for (var i = random.nextInt(3); i > 0; i--) {
                    out.put(1, 1).put(random.nextInt(256), 8);
                }
            }
            out.put(0, 1);
        } else {
            for (var i = random.nextInt(2); i > 0; i--) {
                out.put(1, 1).put(random.nextInt(256), 8);
            }
            out.put(0, 1);
        }
        resetDc();
        resetPmv();
        previousType = 0;
        previousVectors = null;
        var previous = row * mbWidth - 1;
        var address = first;
        while (address < end) {
            var skip = 0;
            if (address != first && (pic.type == P || pic.type == B) && random.nextDouble() < seq.skipRate) {
                var run = 1 + random.nextInt(Math.min(40, Math.max(1, end - address - 1)));
                while (skip < run && address + skip < end - 1 && canSkip(address + skip)) {
                    skip++;
                }
            }
            for (var i = 0; i < skip; i++) {
                skipped(address + i);
            }
            address += skip;
            increment(address - previous);
            macroblock(address);
            previous = address;
            address++;
        }
    }

    private void increment(int increment) {
        if (!seq.mpeg2 && seq.stuffing && random.nextInt(4) == 0) {
            out.put(0b00000001111, 11);
        }
        while (increment > 33) {
            out.put(0b00000001000, 11);
            increment -= 33;
        }
        VlcCodes.ADDRESS_INCREMENT.get(increment).write(out);
    }

    /** Returns whether the macroblock at an address may be skipped, its implied prediction staying in bounds. */
    private boolean canSkip(int address) {
        if (pic.type == P) {
            return sameParityAvailable;
        }
        if (previousType == 0 || (previousType & VlcCodes.INTRA) != 0 || previousVectors == null) {
            return false;
        }
        return skippedFits(address);
    }

    private boolean skippedFits(int address) {
        var x = (address % mbWidth) * 16;
        var y = (address / mbWidth) * 16;
        for (var s = 0; s < 2; s++) {
            var used = s == 0 ? (previousType & VlcCodes.FORWARD) != 0 : (previousType & VlcCodes.BACKWARD) != 0;
            if (!used) {
                continue;
            }
            var mvx = pmv[0][s][0];
            var mvy = pmv[0][s][1];
            if (!seq.mpeg2) {
                var full = s == 0 ? pic.fullPelForward : pic.fullPelBackward;
                if (full) {
                    mvx <<= 1;
                    mvy <<= 1;
                }
            }
            var ok = pic.structure == FRAME ? fits(x, y, 16, 16, mvx, mvy, false)
                    : fits(x, y, 16, 16, mvx, mvy, true);
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private void skipped(int address) {
        resetDc();
        if (pic.type == P) {
            resetPmv();
        }
    }

    /**
     * Returns whether a prediction of a luma region and the chroma regions it implies stays inside the reference:
     * the region at {@code (x, y)} of {@code width} by {@code height} luma samples of the frame, or of a field when
     * {@code field}, displaced by a vector in half samples.
     */
    private boolean fits(int x, int y, int width, int height, int mvx, int mvy, boolean field) {
        var rows = field ? codedHeight / 2 : codedHeight;
        if (!inside(x, y, width, height, mvx, mvy, codedWidth, rows)) {
            return false;
        }
        var shiftX = seq.chroma == 3 ? 0 : 1;
        var shiftY = seq.chroma == 1 ? 1 : 0;
        var cmvx = shiftX == 0 ? mvx : mvx / 2;
        var cmvy = shiftY == 0 ? mvy : mvy / 2;
        return inside(x >> shiftX, y >> shiftY, width >> shiftX, height >> shiftY, cmvx, cmvy, codedWidth >> shiftX,
                rows >> shiftY);
    }

    private static boolean inside(int x, int y, int width, int height, int mvx, int mvy, int columns, int rows) {
        var left = x + (mvx >> 1);
        var top = y + (mvy >> 1);
        return left >= 0 && top >= 0 && left + width + (mvx & 1) <= columns && top + height + (mvy & 1) <= rows;
    }

    private int typeCode() {
        return switch (pic.type) {
            case I -> random.nextInt(4) == 0 ? VlcCodes.INTRA | VlcCodes.QUANT : VlcCodes.INTRA;
            case P -> {
                if (random.nextDouble() < pic.intraRate) {
                    yield random.nextBoolean() ? VlcCodes.INTRA : VlcCodes.INTRA | VlcCodes.QUANT;
                }
                var options = new int[]{VlcCodes.FORWARD | VlcCodes.PATTERN_FLAG, VlcCodes.FORWARD,
                        VlcCodes.QUANT | VlcCodes.FORWARD | VlcCodes.PATTERN_FLAG, VlcCodes.PATTERN_FLAG,
                        VlcCodes.QUANT | VlcCodes.PATTERN_FLAG};
                // the macroblocks without motion predict from the field of the same parity
                yield options[random.nextInt(sameParityAvailable ? options.length : 3)];
            }
            case B -> {
                if (random.nextDouble() < pic.intraRate) {
                    yield random.nextBoolean() ? VlcCodes.INTRA : VlcCodes.INTRA | VlcCodes.QUANT;
                }
                var options = new int[]{VlcCodes.FORWARD, VlcCodes.FORWARD | VlcCodes.PATTERN_FLAG, VlcCodes.BACKWARD,
                        VlcCodes.BACKWARD | VlcCodes.PATTERN_FLAG, VlcCodes.FORWARD | VlcCodes.BACKWARD,
                        VlcCodes.FORWARD | VlcCodes.BACKWARD | VlcCodes.PATTERN_FLAG,
                        VlcCodes.QUANT | VlcCodes.FORWARD | VlcCodes.PATTERN_FLAG,
                        VlcCodes.QUANT | VlcCodes.BACKWARD | VlcCodes.PATTERN_FLAG,
                        VlcCodes.QUANT | VlcCodes.FORWARD | VlcCodes.BACKWARD | VlcCodes.PATTERN_FLAG};
                yield options[random.nextInt(options.length)];
            }
            default -> VlcCodes.INTRA;
        };
    }

    private void macroblock(int address) {
        var type = typeCode();
        if (pic.type == D) {
            out.put(1, 1);
        } else {
            var table = switch (pic.type) {
                case I -> VlcCodes.INTRA_TYPE;
                case P -> VlcCodes.PREDICTIVE_TYPE;
                default -> VlcCodes.BIDIRECTIONAL_TYPE;
            };
            table.get(type).write(out);
        }
        var frame = pic.structure == FRAME;
        var intra = (type & VlcCodes.INTRA) != 0;
        var motionCoded = (type & (VlcCodes.FORWARD | VlcCodes.BACKWARD)) != 0;
        var concealment = intra && pic.concealment && seq.mpeg2;
        var motion = 2;
        if (seq.mpeg2) {
            if (motionCoded) {
                if (frame) {
                    if (!pic.framePredFrameDct) {
                        motion = chooseMotion(true);
                        out.put(motion, 2);
                    }
                } else {
                    motion = chooseMotion(false);
                    out.put(motion, 2);
                }
            } else if (concealment) {
                motion = frame ? 2 : 1;
            }
            if (frame && !pic.framePredFrameDct && (intra || (type & VlcCodes.PATTERN_FLAG) != 0)) {
                out.put(random.nextInt(2), 1);
            }
        }
        if ((type & VlcCodes.QUANT) != 0) {
            out.put(quantizerCode(), 5);
        }
        var x = (address % mbWidth) * 16;
        var y = (address / mbWidth) * 16;
        var vectors = new int[2][2][2];
        var select = new int[2][2];
        if ((type & VlcCodes.FORWARD) != 0 || concealment) {
            vectors(0, motion, x, y, vectors, select, concealment);
        }
        if ((type & VlcCodes.BACKWARD) != 0) {
            vectors(1, motion, x, y, vectors, select, false);
        }
        if (concealment) {
            out.put(1, 1);
        }
        int pattern;
        if ((type & VlcCodes.PATTERN_FLAG) != 0) {
            pattern = pattern();
        } else {
            pattern = intra ? (1 << blockCount) - 1 : 0;
        }
        for (var block = 0; block < blockCount; block++) {
            if ((pattern & 1 << (blockCount - 1 - block)) != 0) {
                if (intra) {
                    intraBlock(block);
                } else {
                    interBlock(block);
                }
            }
        }
        if (pic.type == D) {
            out.put(1, 1);
        }
        if (!intra) {
            resetDc();
        }
        if (intra && !concealment) {
            resetPmv();
        }
        if (pic.type == P && (type & (VlcCodes.FORWARD | VlcCodes.INTRA)) == 0) {
            resetPmv();
        }
        previousType = type;
        previousVectors = vectors;
    }

    private int pattern() {
        while (true) {
            var base = seq.mpeg2 && seq.chroma != 1 ? random.nextInt(64) : 1 + random.nextInt(63);
            var extension = seq.chroma == 2 ? random.nextInt(4) : seq.chroma == 3 ? random.nextInt(64) : 0;
            if (base == 0 && extension == 0) {
                continue;
            }
            VlcCodes.PATTERN.get(base).write(out);
            if (seq.chroma == 2) {
                out.put(extension, 2);
                return base << 2 | extension;
            }
            if (seq.chroma == 3) {
                out.put(extension, 6);
                return base << 6 | extension;
            }
            return base;
        }
    }

    /** Picks a motion type: frame pictures 1 field, 2 frame, 3 dual prime; field pictures 1 field, 2 16x8, 3 DP. */
    private int chooseMotion(boolean frame) {
        var dual = pic.type == P && pic.dualPrime && sameParityAvailable;
        var choice = random.nextInt(dual ? 3 : 2);
        if (choice == 2) {
            return 3;
        }
        return choice == 0 ? 1 : 2;
    }

    /** Draws the vectors of one direction, writes them and updates the predictors. */
    private void vectors(int s, int motion, int x, int y, int[][][] vectors, int[][] select, boolean concealment) {
        var frame = pic.structure == FRAME;
        var hSize = pic.fCode[s][0] - 1;
        var vSize = pic.fCode[s][1] - 1;
        if (!seq.mpeg2) {
            var full = s == 0 ? pic.fullPelForward : pic.fullPelBackward;
            var scale = full ? 2 : 1;
            int vx;
            int vy;
            var attempts = 0;
            do {
                vx = draw(hSize);
                vy = draw(vSize);
                attempts++;
            } while (!fits(x, y, 16, 16, vx * scale, vy * scale, false) && attempts < 50);
            if (!fits(x, y, 16, 16, vx * scale, vy * scale, false)) {
                vx = 0;
                vy = 0;
            }
            code(pmv[0][s], 0, vx, hSize, false);
            code(pmv[0][s], 1, vy, vSize, false);
            vectors[0][s][0] = vx * scale;
            vectors[0][s][1] = vy * scale;
            return;
        }
        int count;
        boolean fieldFormat;
        if (frame) {
            count = motion == 1 ? 2 : 1;
            fieldFormat = motion != 2;
        } else {
            count = motion == 2 ? 2 : 1;
            fieldFormat = true;
        }
        var dual = motion == 3;
        var scaled = fieldFormat && frame;
        if (count == 1) {
            int vx;
            int vy;
            var sel = 0;
            var dmv = new int[2];
            var attempts = 0;
            boolean ok;
            do {
                vx = dual ? draw(Math.min(hSize, 0)) / 2 : draw(hSize);
                vy = dual ? draw(Math.min(vSize, 0)) / 2 : draw(vSize);
                if (dual) {
                    dmv[0] = random.nextInt(3) - 1;
                    dmv[1] = random.nextInt(3) - 1;
                }
                sel = selection(s, frame);
                ok = (concealment || singleFits(x, y, frame, fieldFormat, dual, vx, vy, dmv, sel))
                        && codable(pmv[0][s], vx, vy, hSize, vSize, scaled);
                attempts++;
            } while (!ok && attempts < 60);
            if (!ok) {
                dmv[0] = dmv[1] = 0;
                sel = sameParityAvailable ? (pic.structure == BOTTOM ? 1 : 0) : selection(s, frame);
                var found = false;
                for (var candidate = 0; candidate < 129 * 129 && !found; candidate++) {
                    vx = spiral(candidate % 129);
                    vy = spiral(candidate / 129);
                    found = (concealment || singleFits(x, y, frame, fieldFormat, dual, vx, vy, dmv, sel))
                            && codable(pmv[0][s], vx, vy, hSize, vSize, scaled);
                }
                if (!found) {
                    throw new IllegalStateException("no vector fits");
                }
            }
            if (fieldFormat && !dual) {
                out.put(sel, 1);
            }
            codeVector(pmv[0][s], vx, vy, hSize, vSize, scaled, dual, dmv);
            pmv[1][s][0] = pmv[0][s][0];
            pmv[1][s][1] = pmv[0][s][1];
            vectors[0][s][0] = vx;
            vectors[0][s][1] = vy;
            select[0][s] = sel;
        } else {
            for (var r = 0; r < 2; r++) {
                int vx;
                int vy;
                int sel;
                var attempts = 0;
                boolean ok;
                do {
                    vx = draw(hSize);
                    vy = draw(vSize);
                    sel = selection(s, frame);
                    ok = (frame ? fits(x, y / 2, 16, 8, vx, vy, true) : fits(x, y + 8 * r, 16, 8, vx, vy, true))
                            && codable(pmv[r][s], vx, vy, hSize, vSize, scaled);
                    attempts++;
                } while (!ok && attempts < 60);
                for (var candidate = 0; !ok && candidate < 129 * 129; candidate++) {
                    vx = spiral(candidate % 129);
                    vy = spiral(candidate / 129);
                    ok = (frame ? fits(x, y / 2, 16, 8, vx, vy, true) : fits(x, y + 8 * r, 16, 8, vx, vy, true))
                            && codable(pmv[r][s], vx, vy, hSize, vSize, scaled);
                }
                if (!ok) {
                    throw new IllegalStateException("no field vector fits");
                }
                out.put(sel, 1);
                codeVector(pmv[r][s], vx, vy, hSize, vSize, scaled, false, null);
                vectors[r][s][0] = vx;
                vectors[r][s][1] = vy;
                select[r][s] = sel;
            }
        }
    }

    private boolean singleFits(int x, int y, boolean frame, boolean fieldFormat, boolean dual, int vx, int vy,
                               int[] dmv, int sel) {
        if (frame && !fieldFormat) {
            return fits(x, y, 16, 16, vx, vy, false);
        }
        if (!dual) {
            return frame ? fits(x, y / 2, 16, 8, vx, vy, true) : fits(x, y, 16, 16, vx, vy, true);
        }
        var roundX = vx > 0 ? 1 : 0;
        var roundY = vy > 0 ? 1 : 0;
        if (frame) {
            if (!fits(x, y / 2, 16, 8, vx, vy, true)) {
                return false;
            }
            var near = pic.topFieldFirst ? 1 : 3;
            var far = pic.topFieldFirst ? 3 : 1;
            var ax = ((near * vx + roundX) >> 1) + dmv[0];
            var ay = ((near * vy + roundY) >> 1) + dmv[1] - 1;
            var bx = ((far * vx + roundX) >> 1) + dmv[0];
            var by = ((far * vy + roundY) >> 1) + dmv[1] + 1;
            return fits(x, y / 2, 16, 8, ax, ay, true) && fits(x, y / 2, 16, 8, bx, by, true);
        }
        if (!fits(x, y, 16, 16, vx, vy, true)) {
            return false;
        }
        var dx = ((vx + roundX) >> 1) + dmv[0];
        var dy = ((vy + roundY) >> 1) + dmv[1] + (pic.structure == TOP ? -1 : 1);
        return fits(x, y, 16, 16, dx, dy, true);
    }

    /**
     * Draws a {@code motion_vertical_field_select}: the field of the opposite parity, the first field of the frame,
     * for the forward vectors of the second field of the first reference frame, which has no earlier field of its
     * parity to predict from.
     */
    private int selection(int s, boolean frame) {
        if (!frame && s == 0 && !sameParityAvailable) {
            return pic.structure == TOP ? 1 : 0;
        }
        return random.nextInt(2);
    }

    /** Returns 0, 1, -1, 2, -2 and so on, the candidate vector components of the fallback search. */
    private static int spiral(int index) {
        return (index & 1) == 0 ? -(index >> 1) : (index + 1) >> 1;
    }

    /** Returns whether both components of a vector can be coded from the predictors. */
    private static boolean codable(int[] predictor, int vx, int vy, int hSize, int vSize, boolean scaled) {
        return reach(predictor[0], vx, hSize) != Integer.MIN_VALUE
                && reach(scaled ? predictor[1] >> 1 : predictor[1], vy, vSize) != Integer.MIN_VALUE;
    }

    /**
     * Returns the vector delta, from -16 to 16 times {@code f}, that the decoding process (ISO/IEC 13818-2 7.6.3.1, as
     * the reference decoder applies it: a positive delta wraps down, a negative one up) turns into a value, or
     * {@code Integer.MIN_VALUE} when none does; the prediction of a frame vector following field vectors, held in
     * frame units, may lie outside the range.
     */
    static int reach(int prediction, int value, int rSize) {
        var limit = 16 << rSize;
        if (value < -limit || value >= limit) {
            return Integer.MIN_VALUE;
        }
        var best = Integer.MIN_VALUE;
        for (var k = -2; k <= 2; k++) {
            var delta = value - prediction + 2 * k * limit;
            if (delta < -limit || delta > limit) {
                continue;
            }
            var decoded = prediction + delta;
            if (delta > 0 && decoded >= limit) {
                decoded -= 2 * limit;
            } else if (delta < 0 && decoded < -limit) {
                decoded += 2 * limit;
            }
            if (decoded == value && (best == Integer.MIN_VALUE || Math.abs(delta) < Math.abs(best))) {
                best = delta;
            }
        }
        return best;
    }

    /** Draws a vector component in half samples within the range of an {@code r_size}, biased toward small values. */
    private int draw(int rSize) {
        var limit = 16 << rSize;
        var magnitude = random.nextInt(4) == 0 ? limit : Math.min(limit, 24);
        return random.nextInt(2 * magnitude) - magnitude;
    }

    /** Writes a vector as a delta from its predictors and updates them (ISO/IEC 13818-2 7.6.3.1). */
    private void codeVector(int[] predictor, int vx, int vy, int hSize, int vSize, boolean scaled, boolean dual,
                            int[] dmv) {
        code(predictor, 0, vx, hSize, false);
        if (dual) {
            VlcCodes.DUAL_PRIME.get(dmv[0]).write(out);
        }
        code(predictor, 1, vy, vSize, scaled);
        if (dual) {
            VlcCodes.DUAL_PRIME.get(dmv[1]).write(out);
        }
    }

    /**
     * Writes one component and updates its predictor: for a field vector of a frame picture the predictor holds the
     * vertical component in frame units.
     */
    private void code(int[] predictor, int t, int value, int rSize, boolean scaled) {
        var prediction = scaled ? predictor[t] >> 1 : predictor[t];
        var f = 1 << rSize;
        var delta = reach(prediction, value, rSize);
        if (delta == Integer.MIN_VALUE) {
            throw new IllegalStateException("vector component " + value + " cannot be reached from " + prediction);
        }
        if (delta == 0) {
            VlcCodes.MOTION.get(0).write(out);
        } else {
            var magnitude = Math.abs(delta) - 1;
            var motionCode = (magnitude >> rSize) + 1;
            var residual = magnitude & (f - 1);
            VlcCodes.MOTION.get(delta < 0 ? -motionCode : motionCode).write(out);
            if (rSize > 0) {
                out.put(residual, rSize);
            }
        }
        predictor[t] = scaled ? value << 1 : value;
    }

    private void intraBlock(int block) {
        var component = block < 4 ? 0 : 1 + (block & 1);
        var precision = seq.mpeg2 ? pic.dcPrecision : 0;
        var maximum = (1 << (8 + precision)) - 1;
        var spread = random.nextInt(8) == 0 ? maximum : 1 << (precision + 4);
        var target = Math.clamp(dcPredictor[component] + random.nextInt(2 * spread + 1) - spread, 0, maximum);
        var difference = target - dcPredictor[component];
        var size = difference == 0 ? 0 : 32 - Integer.numberOfLeadingZeros(Math.abs(difference));
        (component == 0 ? VlcCodes.LUMA_DC : VlcCodes.CHROMA_DC).get(size).write(out);
        if (size > 0) {
            out.put(difference > 0 ? difference : difference + (1 << size) - 1, size);
        }
        dcPredictor[component] = target;
        if (pic.type == D) {
            return;
        }
        coefficients(1, true, component, COEFFICIENT_BUDGET - (target << (3 - precision)));
        if (seq.mpeg2 && pic.intraVlc) {
            out.put(0b0110, 4);
        } else {
            out.put(0b10, 2);
        }
    }

    private void interBlock(int block) {
        coefficients(0, false, block < 4 ? 0 : 1, COEFFICIENT_BUDGET);
        out.put(0b10, 2);
    }

    private static int[] flat() {
        var matrix = new int[64];
        java.util.Arrays.fill(matrix, 16);
        return matrix;
    }

    /**
     * Writes escape-coded coefficients from a scan position on, at least one for a non-intra block, while the
     * magnitudes of their reconstructed values stay within a budget.
     */
    private void coefficients(int position, boolean intra, int component, int budget) {
        var count = position == 0 ? 1 + random.nextInt(4) : random.nextInt(5);
        var matrix = weights[(intra ? 0 : 1) + (seq.mpeg2 && seq.chroma != 1 && component > 0 ? 2 : 0)];
        var order = seq.mpeg2 && pic.alternateScan ? ALTERNATE
                : com.github.auties00.smalt.internal.codec.MpegVideoHeaders.zigzag();
        var scale = seq.mpeg2 && pic.qScaleType ? NON_LINEAR[quantizer] : quantizer << 1;
        for (var i = 0; i < count && position < 64; i++) {
            var run = random.nextInt(Math.min(64 - position, i == 0 ? 8 : 20));
            if (position + run > 63) {
                break;
            }
            var weight = matrix[order[position + run]];
            var magnitude = 1 + random.nextInt(Math.min(seq.mpeg2 ? 2047 : 255, Math.min(seq.maxLevel,
                    random.nextInt(10) == 0 ? 2047 : random.nextInt(4) == 0 ? 200 : 12)));
            while (magnitude > 1 && (reconstructed(magnitude, intra, scale, weight) > budget
                    || unsaturated(magnitude, intra, scale, weight) > Short.MAX_VALUE)) {
                magnitude = Math.max(1, magnitude / 2);
            }
            var cost = reconstructed(magnitude, intra, scale, weight);
            // a non-intra block codes at least one coefficient, which a level of one keeps small
            if (cost > budget && !(i == 0 && !intra)) {
                break;
            }
            budget -= cost;
            position += run;
            out.put(0b000001, 6).put(run, 6);
            level(magnitude);
            position++;
        }
    }

    /** Returns the magnitude of the coefficient a level reconstructs to, saturated (ISO/IEC 13818-2 7.4.2). */
    private static int reconstructed(int magnitude, boolean intra, int scale, int weight) {
        return Math.min(unsaturated(magnitude, intra, scale, weight), 2048);
    }

    /**
     * Returns the magnitude of the coefficient a level reconstructs to before saturation, which the generator keeps
     * within 16 bits: the reference decoder stores it in a 16-bit integer before it saturates it.
     */
    private static int unsaturated(int magnitude, boolean intra, int scale, int weight) {
        return intra ? (magnitude * scale * weight) >> 4 : ((2 * magnitude + 1) * scale * weight) >> 5;
    }

    /** Writes the escape-coded level of a magnitude, with a random sign. */
    private void level(int magnitude) {
        if (seq.mpeg2) {
            var value = random.nextBoolean() ? magnitude : -magnitude;
            out.put(value & 0xFFF, 12);
            return;
        }
        var value = random.nextBoolean() ? magnitude : -magnitude;
        if (value >= -127 && value <= 127) {
            out.put(value & 0xFF, 8);
        } else if (value > 0) {
            out.put(0, 8).put(value, 8);
        } else {
            out.put(0x80, 8).put(value + 256, 8);
        }
    }
}
